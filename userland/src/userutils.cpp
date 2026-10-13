// userutils - users and logins: login, su, sudo, passwd, useradd, userdel, usermod, groupadd, groupdel, groups.
//
// Accounts live in /etc/passwd, /etc/group and /etc/shadow (root only, crypt() hashes: "$6$" SHA-512, as on Linux;
// an empty hash means no password, "!" or "*" a locked account). su, sudo and passwd are setuid root (the kernel
// gives them euid 0, the real uid says who called); the others are for root.
#include <baremetal.hpp>

#include <fnmatch.h>

#include <dirent.h>

#include <algorithm>

#include <cerrno>
#include <crypt.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <grp.h>
#include <map>
#include <optional>
#include <pwd.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

extern char **environ;

namespace {

using Args = std::vector<std::string>;

std::string prog;

[[noreturn]] void die(const std::string &msg, int code = 1) {
    std::fprintf(stderr, "%s: %s\n", prog.c_str(), msg.c_str());
    std::exit(code);
}

// ---- The account files ------------------------------------------------------------------------------------------

std::vector<std::string> split(const std::string &s, char sep) {
    std::vector<std::string> out;
    size_t start = 0;
    while (true) {
        auto at = s.find(sep, start);
        out.push_back(s.substr(start, at == std::string::npos ? std::string::npos : at - start));
        if (at == std::string::npos) break;
        start = at + 1;
    }
    return out;
}

std::string join(const std::vector<std::string> &parts, char sep) {
    std::string out;
    for (size_t i = 0; i < parts.size(); i++) out += (i ? std::string(1, sep) : "") + parts[i];
    return out;
}

// One file of colon-separated records, kept with its comments and unknown lines.
struct Table {
    std::string path;
    std::vector<std::vector<std::string>> rows;

    static Table load(const std::string &path, size_t fields) {
        Table t{path, {}};
        FILE *f = std::fopen(path.c_str(), "r");
        if (!f) {
            if (errno == ENOENT) return t;
            die(path + ": " + std::strerror(errno));
        }
        char buf[2048];
        while (std::fgets(buf, sizeof(buf), f)) {
            std::string line = buf;
            while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
            if (line.empty()) continue;
            auto row = split(line, ':');
            if (line[0] != '#' && row.size() < fields) row.resize(fields);
            t.rows.push_back(row);
        }
        std::fclose(f);
        return t;
    }

    std::vector<std::string> *find(const std::string &name) {
        for (auto &r : rows) {
            if (!r.empty() && r[0] == name) return &r;
        }
        return nullptr;
    }

    // Written to a new file, then renamed over the old one: never half-written; owner root, `mode`.
    void save(mode_t mode) const {
        auto tmp = path + "+";
        mode_t old = umask(077);
        FILE *f = std::fopen(tmp.c_str(), "w");
        umask(old);
        if (!f) die(tmp + ": " + std::strerror(errno));
        for (auto &r : rows) std::fprintf(f, "%s\n", join(r, ':').c_str());
        if (std::fclose(f) != 0) die(tmp + ": " + std::strerror(errno));
        chown(tmp.c_str(), 0, 0);
        chmod(tmp.c_str(), mode);
        if (std::rename(tmp.c_str(), path.c_str()) != 0) die(path + ": " + std::strerror(errno));
    }
};

Table passwdTable() { return Table::load("/etc/passwd", 7); }
Table groupTable() { return Table::load("/etc/group", 4); }
Table shadowTable() { return Table::load("/etc/shadow", 9); }

struct Account {
    std::string name;
    int uid = -1, gid = -1;
    std::string gecos, home, shell;
};

std::optional<Account> account(const std::string &name) {
    if (auto *pw = getpwnam(name.c_str())) return Account{pw->pw_name, static_cast<int>(pw->pw_uid), static_cast<int>(pw->pw_gid), pw->pw_gecos, pw->pw_dir, pw->pw_shell};
    return std::nullopt;
}

std::optional<Account> accountById(int uid) {
    if (auto *pw = getpwuid(static_cast<uid_t>(uid))) return Account{pw->pw_name, static_cast<int>(pw->pw_uid), static_cast<int>(pw->pw_gid), pw->pw_gecos, pw->pw_dir, pw->pw_shell};
    return std::nullopt;
}

// The password hash of a user (needs root): "" none, "!..." / "*" locked; nullopt if the user has no entry.
std::optional<std::string> hashOf(const std::string &name) {
    auto t = shadowTable();
    if (auto *r = t.find(name)) return (*r)[1];
    return std::nullopt;
}

bool locked(const std::string &hash) {
    return !hash.empty() && (hash[0] == '!' || hash[0] == '*');
}

bool verify(const std::string &hash, const std::string &password) {
    if (hash.empty()) return true;
    if (locked(hash)) return false;
    const char *out = crypt(password.c_str(), hash.c_str());
    return out && hash == out;
}

std::string newHash(const std::string &password) {
    static const char chars[] = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    unsigned char random[16];
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0 || read(fd, random, sizeof(random)) != static_cast<ssize_t>(sizeof(random))) die("cannot read /dev/urandom");
    close(fd);
    std::string salt = "$6$";
    for (auto b : random) salt += chars[b % 64];
    salt += "$";
    const char *out = crypt(password.c_str(), salt.c_str());
    if (!out || out[0] != '$') die("crypt failed");
    return out;
}

long today() {
    return static_cast<long>(std::time(nullptr) / 86400);
}

bool inGroup(const std::string &user, int primaryGid, const std::string &group) {
    auto *g = getgrnam(group.c_str());
    if (!g) return false;
    if (static_cast<int>(g->gr_gid) == primaryGid) return true;
    for (char **m = g->gr_mem; m && *m; m++) {
        if (user == *m) return true;
    }
    return false;
}

std::string hostName() {
    return cc::hostname();
}

std::string lowerCase(std::string s) {
    for (auto &c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

// ---- Terminal input ---------------------------------------------------------------------------------------------

// A line from the terminal (not stdin: "echo x | sudo tee f" still asks there). Ctrl+C cancels (nullopt when
// `cancel` is set), Ctrl+D on an empty line is end of input.
std::optional<std::string> readLine(bool echo, bool *cancelled = nullptr) {
    int fd = open("/dev/tty", O_RDWR);
    if (fd < 0) fd = 0;
    int mode = CC_TTY_NOSIG | (echo ? 0 : CC_TTY_NOECHO);
    cc::tty::Mode m(mode);
    std::string line;
    char c;
    bool any = false;
    while (true) {
        ssize_t n = read(fd, &c, 1);
        if (n <= 0) break;
        any = true;
        if (c == '\n' || c == '\r') break;
        if (c == 3) { // Ctrl+C
            if (cancelled) *cancelled = true;
            if (fd != 0) close(fd);
            if (!echo) std::fputc('\n', stderr);
            return std::nullopt;
        }
        line += c;
    }
    if (fd != 0) close(fd);
    if (!echo) std::fputc('\n', stderr);
    if (!any) return std::nullopt;
    return line;
}

std::optional<std::string> askPassword(const std::string &prompt) {
    std::fputs(prompt.c_str(), stderr);
    std::fflush(stderr);
    return readLine(false);
}

// ---- Sessions ---------------------------------------------------------------------------------------------------

std::string defaultPath(int uid) {
    return uid == 0 ? "/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin" : "/usr/local/bin:/bin:/usr/bin:/sbin";
}

// Become `a`: groups from /etc/group, then group, then user (in this order: after setuid there is no way back).
void become(const Account &a) {
    if (initgroups(a.name.c_str(), static_cast<gid_t>(a.gid)) != 0) die(std::string("initgroups: ") + std::strerror(errno));
    if (setresgid(static_cast<gid_t>(a.gid), static_cast<gid_t>(a.gid), static_cast<gid_t>(a.gid)) != 0) die(std::string("setgid: ") + std::strerror(errno));
    if (setresuid(static_cast<uid_t>(a.uid), static_cast<uid_t>(a.uid), static_cast<uid_t>(a.uid)) != 0) die(std::string("setuid: ") + std::strerror(errno));
}

std::vector<std::string> loginEnv(const Account &a) {
    const char *term = std::getenv("TERM");
    return {"TERM=" + std::string(term ? term : "ccbaremetal"), "HOME=" + a.home, "SHELL=" + a.shell, "USER=" + a.name,
            "LOGNAME=" + a.name, "PATH=" + defaultPath(a.uid)};
}

void goHome(const Account &a) {
    if (!cc::chdir(a.home)) {
        std::fprintf(stderr, "No directory %s, logging in with HOME=/\n", a.home.c_str());
        cc::chdir("/");
    }
}

// Run argv and wait for it (reaping orphans meanwhile); its exit status.
int runAndWait(const Args &argv, const cc::SpawnOptions &options) {
    int pid = cc::spawn(argv, options);
    if (pid < 0) {
        std::fprintf(stderr, "%s: %s: %s\n", prog.c_str(), argv[0].c_str(), std::strerror(-pid));
        return 127;
    }
    int status = 0;
    while (true) {
        int r = cc::wait(-1, &status);
        if (r == pid || r < 0) break;
    }
    return status;
}

// ---- login ------------------------------------------------------------------------------------------------------

// Tells init that a password session ended: show the login prompt again (instead of turning off, like CC does
// when the shell of an automatic login exits).
constexpr int LOGIN_AGAIN = 200;

int lockConfSeconds();

bool fileOwnedByRoot(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && st.st_uid == 0 && !(st.st_mode & 022);
}

int session(const Account &a) {
    // the screen's auto-lock (/etc/lock.conf) for this user, if a password can unlock it; nothing over ssh (ENOTTY)
    auto hash = hashOf(a.name);
    int lockAfter = hash && !hash->empty() && !locked(*hash) ? lockConfSeconds() : 0;
    cc_screen_autolock(lockAfter, a.uid);
    become(a);
    goHome(a);
    cc::SpawnOptions options;
    options.env = loginEnv(a);
    options.flags = CC_SPAWN_NEW_GROUP | CC_SPAWN_FOREGROUND;
    int status = runAndWait({a.shell, "-l"}, options);
    cc_screen_autolock(0, -1); // logged out: the login prompt is not locked
    return status;
}

int cmdLogin(Args &a) {
    if (geteuid() != 0) die("cannot possibly work without effective root");
    std::string forced;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-f" && i + 1 < a.size()) forced = a[++i];
        else if (a[i] == "--help") {
            std::puts("usage: login [-f USER]   (-f: no password, for root)");
            return 0;
        }
    }
    if (!forced.empty()) {
        auto acc = account(forced);
        if (!acc) die("no such user: " + forced);
        return session(*acc);
    }
    // automatic login on the console (/etc/autologin, as getty --autologin): "NAME always" (set with the autologin
    // command) logs NAME in at every boot; a plain "NAME" only while NAME has no password. Once per boot: after a
    // logout the prompt is shown (/run is emptied at boot).
    struct stat st;
    if (stat("/run/autologin-done", &st) != 0) {
        if (FILE *f = std::fopen("/etc/autologin", "r")) {
            char buf[128] = "";
            std::fgets(buf, sizeof(buf), f);
            std::fclose(f);
            char name[64] = "", mode[32] = "";
            std::sscanf(buf, "%63s %31s", name, mode);
            auto acc = name[0] ? account(name) : std::nullopt;
            auto hash = acc ? hashOf(name) : std::nullopt;
            bool always = std::string(mode) == "always" && fileOwnedByRoot("/etc/autologin");
            if (acc && hash && (hash->empty() || always)) {
                if (always) {
                    if (FILE *done = std::fopen("/run/autologin-done", "w")) std::fclose(done);
                    session(*acc);
                    return LOGIN_AGAIN;
                }
                return session(*acc);
            }
        }
    }
    while (true) {
        std::printf("\n%s login: ", hostName().c_str());
        std::fflush(stdout);
        auto name = readLine(true);
        if (!name) {
            std::putchar('\n');
            continue;
        }
        if (name->empty()) continue;
        auto acc = account(*name);
        auto hash = acc ? hashOf(*name) : std::nullopt;
        bool ok = false;
        if (acc && hash && hash->empty()) {
            ok = true;
        } else {
            auto pw = askPassword("Password: ");
            ok = pw && acc && hash && verify(*hash, *pw);
        }
        if (!ok) {
            cc::sleep(2);
            std::puts("Login incorrect");
            continue;
        }
        session(*acc);
        return LOGIN_AGAIN;
    }
}

// ---- su ---------------------------------------------------------------------------------------------------------

int cmdSu(Args &a) {
    bool login = false;
    std::string command, shell, target = "root";
    bool targetSet = false;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        if (o == "-" || o == "-l" || o == "--login") login = true;
        else if ((o == "-c" || o == "--command") && i + 1 < a.size()) command = a[++i];
        else if ((o == "-s" || o == "--shell") && i + 1 < a.size()) shell = a[++i];
        else if (o == "--help") {
            std::puts("usage: su [-] [-c COMMAND] [-s SHELL] [USER]   (default root; asks USER's password)");
            return 0;
        } else if (!targetSet && o[0] != '-') {
            target = o;
            targetSet = true;
        } else {
            die("invalid option '" + o + "'");
        }
    }
    if (geteuid() != 0) die("must be setuid root");
    auto acc = account(target);
    if (!acc) die("user " + target + " does not exist");
    if (getuid() != 0) {
        auto hash = hashOf(target);
        bool ok = hash && !locked(*hash);
        if (ok && !hash->empty()) {
            auto pw = askPassword("Password: ");
            ok = pw && verify(*hash, *pw);
        }
        if (!ok) {
            cc::sleep(2);
            die("Authentication failure");
        }
    }
    if (!shell.empty() && getuid() != 0) shell.clear(); // only root may choose another shell
    if (shell.empty()) shell = acc->shell.empty() ? "/bin/sh" : acc->shell;
    become(*acc);
    cc::SpawnOptions options;
    if (login) {
        options.env = loginEnv(*acc);
        goHome(*acc);
    } else {
        setenv("HOME", acc->home.c_str(), 1);
        setenv("SHELL", shell.c_str(), 1);
        setenv("USER", acc->name.c_str(), 1);
        setenv("LOGNAME", acc->name.c_str(), 1);
    }
    Args argv = {shell};
    if (login) argv.push_back("-l");
    if (!command.empty()) {
        argv.push_back("-c");
        argv.push_back(command);
    }
    return runAndWait(argv, options);
}

// ---- sudo -------------------------------------------------------------------------------------------------------

// ---- sudoers ----------------------------------------------------------------------------------------------------
//
// /etc/sudoers as sudo reads it (the common part): Defaults, User_Alias / Runas_Alias / Host_Alias / Cmnd_Alias,
// rules "WHO HOSTS = (RUNAS[:GROUPS]) [NOPASSWD:|PASSWD:] COMMANDS" and #includedir. The last matching rule
// decides. Commands: ALL, an alias, a full path (any arguments), a path with arguments (exactly those, * allowed),
// a path with "" (no arguments), a directory ending in / (any program in it); ! negates.

struct CmdSpec {
    bool negate = false;
    std::string text; // as written: "ALL", "/bin/ls -l", "ALIAS"
    std::vector<std::string> runasUsers, runasGroups;
    bool runasGiven = false;
    bool nopasswd = false;
};

struct Rule {
    std::vector<std::string> who, hosts;
    std::vector<CmdSpec> commands;
    std::string source; // file:line, for sudo -l and errors
};

struct Sudoers {
    std::map<std::string, std::map<std::string, std::vector<std::string>>> aliases; // kind -> name -> members
    std::vector<Rule> rules;
    long timeout = 15 * 60; // seconds; < 0 never expires, 0 always asks
    int passwdTries = 3;
    std::string securePath = "/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin";
    std::vector<std::string> envKeep;
    std::vector<std::string> errors, warnings;
};

std::string trim(std::string s) {
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.pop_back();
    size_t b = 0;
    while (b < s.size() && std::isspace(static_cast<unsigned char>(s[b]))) b++;
    return s.substr(b);
}

// split on `sep` outside quotes, trimmed
std::vector<std::string> splitList(const std::string &s, char sep) {
    std::vector<std::string> out;
    std::string cur;
    bool quote = false;
    for (size_t i = 0; i < s.size(); i++) {
        char c = s[i];
        if (c == '\\' && i + 1 < s.size()) {
            cur += s[++i];
            continue;
        }
        if (c == '"') quote = !quote;
        if (c == sep && !quote) {
            out.push_back(trim(cur));
            cur.clear();
        } else {
            cur += c;
        }
    }
    if (!trim(cur).empty() || !out.empty()) out.push_back(trim(cur));
    return out;
}

bool isAliasName(const std::string &s) {
    if (s.empty() || !std::isupper(static_cast<unsigned char>(s[0]))) return false;
    for (char c : s) {
        if (!(std::isupper(static_cast<unsigned char>(c)) || std::isdigit(static_cast<unsigned char>(c)) || c == '_')) return false;
    }
    return s != "ALL";
}

void parseSudoersFile(Sudoers &sd, const std::string &path, int depth);

void parseSudoersLine(Sudoers &sd, const std::string &line, const std::string &where) {
    auto bad = [&](const std::string &why) { sd.errors.push_back(where + ": syntax error: " + why); };
    auto firstSpace = line.find_first_of(" \t");
    auto head = line.substr(0, firstSpace);
    auto rest = firstSpace == std::string::npos ? "" : trim(line.substr(firstSpace));
    if (head == "Defaults" || head.rfind("Defaults", 0) == 0) {
        for (auto &item : splitList(rest, ',')) {
            if (item.empty()) continue;
            bool neg = item[0] == '!';
            auto body = neg ? item.substr(1) : item;
            auto eq = body.find('=');
            bool add = eq != std::string::npos && eq > 0 && body[eq - 1] == '+';
            auto name = trim(body.substr(0, add ? eq - 1 : eq));
            auto value = eq == std::string::npos ? "" : trim(body.substr(eq + 1));
            if (value.size() >= 2 && value.front() == '"' && value.back() == '"') value = value.substr(1, value.size() - 2);
            if (name == "timestamp_timeout") {
                double minutes = std::atof(value.c_str());
                sd.timeout = minutes < 0 ? -1 : static_cast<long>(minutes * 60);
            } else if (name == "passwd_tries") {
                sd.passwdTries = std::max(1, std::atoi(value.c_str()));
            } else if (name == "secure_path") {
                sd.securePath = value;
            } else if (name == "env_keep") {
                if (!add) sd.envKeep.clear();
                size_t a = 0;
                while (a < value.size()) {
                    auto b = value.find(' ', a);
                    auto v = value.substr(a, b == std::string::npos ? std::string::npos : b - a);
                    if (!v.empty()) sd.envKeep.push_back(v);
                    if (b == std::string::npos) break;
                    a = b + 1;
                }
            } else if (name == "env_reset" || name == "mail_badpass" || name == "use_pty" || name == "lecture" || name == "insults") {
                // always on (env_reset) or meaningless here
            } else {
                sd.warnings.push_back(where + ": unknown Defaults entry \"" + name + "\"");
            }
        }
        return;
    }
    for (auto kind : {"User_Alias", "Runas_Alias", "Host_Alias", "Cmnd_Alias", "Cmd_Alias"}) {
        if (head != kind) continue;
        std::string k = head == "Cmd_Alias" ? "Cmnd_Alias" : head;
        for (auto &def : splitList(rest, ':')) {
            auto eq = def.find('=');
            if (eq == std::string::npos) {
                bad("alias without '='");
                return;
            }
            auto name = trim(def.substr(0, eq));
            if (!isAliasName(name)) {
                bad("alias names are in capitals: " + name);
                return;
            }
            sd.aliases[k][name] = splitList(def.substr(eq + 1), ',');
        }
        return;
    }
    // a rule: WHO HOSTS = SPECS
    auto eq = line.find('=');
    if (eq == std::string::npos) {
        bad("expected USER HOST = COMMANDS");
        return;
    }
    auto left = trim(line.substr(0, eq));
    auto sp = left.find_last_of(" \t");
    if (sp == std::string::npos) {
        bad("expected USER HOST = COMMANDS");
        return;
    }
    Rule r;
    r.source = where;
    r.who = splitList(left.substr(0, sp), ',');
    r.hosts = splitList(left.substr(sp + 1), ',');
    CmdSpec carry;
    for (auto item : splitList(line.substr(eq + 1), ',')) {
        CmdSpec c = carry;
        c.negate = false;
        if (!item.empty() && item[0] == '(') {
            auto close = item.find(')');
            if (close == std::string::npos) {
                bad("missing ')'");
                return;
            }
            auto runas = item.substr(1, close - 1);
            auto colon = runas.find(':');
            c.runasUsers = splitList(runas.substr(0, colon), ',');
            c.runasGroups = colon == std::string::npos ? std::vector<std::string>{} : splitList(runas.substr(colon + 1), ',');
            if (c.runasUsers.size() == 1 && c.runasUsers[0].empty()) c.runasUsers.clear();
            c.runasGiven = true;
            item = trim(item.substr(close + 1));
        }
        while (true) {
            if (item.rfind("NOPASSWD:", 0) == 0) {
                c.nopasswd = true;
                item = trim(item.substr(9));
            } else if (item.rfind("PASSWD:", 0) == 0) {
                c.nopasswd = false;
                item = trim(item.substr(7));
            } else if (item.rfind("SETENV:", 0) == 0 || item.rfind("NOSETENV:", 0) == 0 || item.rfind("EXEC:", 0) == 0 ||
                       item.rfind("NOEXEC:", 0) == 0) {
                item = trim(item.substr(item.find(':') + 1));
            } else {
                break;
            }
        }
        if (!item.empty() && item[0] == '!') {
            c.negate = true;
            item = trim(item.substr(1));
        }
        if (item.empty()) {
            bad("empty command");
            return;
        }
        if (item != "ALL" && item[0] != '/' && !isAliasName(item)) {
            bad("commands need a full path: " + item);
            return;
        }
        c.text = item;
        r.commands.push_back(c);
        carry = c;
    }
    if (r.commands.empty()) {
        bad("expected commands after '='");
        return;
    }
    sd.rules.push_back(r);
}

void parseSudoersFile(Sudoers &sd, const std::string &path, int depth) {
    FILE *f = std::fopen(path.c_str(), "r");
    if (!f) {
        sd.errors.push_back(path + ": " + std::strerror(errno));
        return;
    }
    char buf[1024];
    int lineNo = 0;
    std::string pending;
    int start = 0;
    while (std::fgets(buf, sizeof(buf), f)) {
        lineNo++;
        std::string line = buf;
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        if (pending.empty()) start = lineNo;
        if (!line.empty() && line.back() == '\\') { // continued
            pending += line.substr(0, line.size() - 1) + " ";
            continue;
        }
        line = pending + line;
        pending.clear();
        auto t = trim(line);
        if (t.rfind("#includedir", 0) == 0 || t.rfind("@includedir", 0) == 0) {
            auto dir = trim(t.substr(11));
            if (depth > 4) continue;
            if (DIR *d = opendir(dir.c_str())) {
                std::vector<std::string> names;
                while (auto *e = readdir(d)) {
                    std::string n = e->d_name;
                    // as sudo: skip names with a '.' or ending in '~' (editor backups)
                    if (n.find('.') == std::string::npos && n.back() != '~') names.push_back(n);
                }
                closedir(d);
                std::sort(names.begin(), names.end());
                for (auto &n : names) parseSudoersFile(sd, dir + "/" + n, depth + 1);
            }
            continue;
        }
        if (t.rfind("#include", 0) == 0 || t.rfind("@include", 0) == 0) {
            if (depth <= 4) parseSudoersFile(sd, trim(t.substr(8)), depth + 1);
            continue;
        }
        auto hash = t.find('#');
        if (hash != std::string::npos) t = trim(t.substr(0, hash));
        if (t.empty()) continue;
        parseSudoersLine(sd, t, path + ":" + std::to_string(start));
    }
    std::fclose(f);
}

Sudoers loadSudoers(const std::string &path = "/etc/sudoers") {
    Sudoers sd;
    parseSudoersFile(sd, path, 0);
    return sd;
}

// Does `item` (a user, %group, #uid, ALL, an alias, !negated) match this user? -1 no opinion, 0 no, 1 yes.
int matchUserItem(const Sudoers &sd, const std::string &kind, const std::string &item, const Account &u, int depth = 0) {
    if (item.empty()) return -1;
    if (item[0] == '!') {
        int r = matchUserItem(sd, kind, item.substr(1), u, depth);
        return r == 1 ? 0 : -1;
    }
    if (item == "ALL") return 1;
    if (item[0] == '%') return inGroup(u.name, u.gid, item.substr(1)) ? 1 : -1;
    if (item[0] == '#') return std::atoi(item.c_str() + 1) == u.uid ? 1 : -1;
    if (isAliasName(item) && depth < 8) {
        auto k = sd.aliases.find(kind);
        if (k != sd.aliases.end()) {
            auto a = k->second.find(item);
            if (a != k->second.end()) {
                int result = -1;
                for (auto &m : a->second) {
                    int r = matchUserItem(sd, kind, m, u, depth + 1);
                    if (r != -1) result = r;
                }
                return result;
            }
        }
        return -1;
    }
    return item == u.name ? 1 : -1;
}

bool matchUserList(const Sudoers &sd, const std::string &kind, const std::vector<std::string> &list, const Account &u) {
    int result = -1;
    for (auto &i : list) {
        int r = matchUserItem(sd, kind, i, u);
        if (r != -1) result = r;
    }
    return result == 1;
}

bool matchHost(const Sudoers &sd, const std::vector<std::string> &hosts, int depth = 0) {
    auto me = lowerCase(hostName());
    int result = -1;
    for (auto &h : hosts) {
        bool neg = !h.empty() && h[0] == '!';
        auto name = neg ? h.substr(1) : h;
        int r = -1;
        if (name == "ALL" || lowerCase(name) == me) r = 1;
        else if (isAliasName(name) && depth < 8) {
            auto k = sd.aliases.find("Host_Alias");
            if (k != sd.aliases.end() && k->second.count(name)) r = matchHost(sd, k->second.at(name), depth + 1) ? 1 : -1;
        }
        if (r == 1) result = neg ? 0 : 1;
    }
    return result == 1;
}

// One command item against the program (full path) and its arguments: -1 no match, 0 matched but negated, 1 allowed.
int matchCommand(const Sudoers &sd, const std::string &item, const std::string &path, const std::string &args, int depth = 0) {
    bool neg = !item.empty() && item[0] == '!';
    auto text = neg ? trim(item.substr(1)) : item;
    bool match = false;
    if (text == "ALL") {
        match = true;
    } else if (isAliasName(text) && depth < 8) {
        auto k = sd.aliases.find("Cmnd_Alias");
        int result = -1;
        if (k != sd.aliases.end() && k->second.count(text)) {
            for (auto &m : k->second.at(text)) {
                int r = matchCommand(sd, m, path, args, depth + 1);
                if (r != -1) result = r;
            }
        }
        if (result == -1) return -1;
        match = result == 1;
        if (!match) return neg ? 1 : 0;
    } else {
        auto sp = text.find(' ');
        auto specPath = text.substr(0, sp);
        auto specArgs = sp == std::string::npos ? std::optional<std::string>() : std::optional<std::string>(trim(text.substr(sp + 1)));
        bool pathOk = specPath.back() == '/' ? path.rfind(specPath, 0) == 0 && path.find('/', specPath.size()) == std::string::npos
                                             : fnmatch(specPath.c_str(), path.c_str(), FNM_PATHNAME) == 0;
        bool argsOk = !specArgs || (*specArgs == "\"\"" ? args.empty() : fnmatch(specArgs->c_str(), args.c_str(), 0) == 0);
        match = pathOk && argsOk;
    }
    if (!match) return -1;
    return neg ? 0 : 1;
}

bool matchRunas(const Sudoers &sd, const CmdSpec &c, const Account &target, bool groupGiven, const std::string &group) {
    // no (RUNAS): root only; "(:GROUP)" alone: as the invoking user, with that group
    if (!c.runasGiven) return target.uid == 0 && !groupGiven;
    bool userOk = c.runasUsers.empty() ? target.uid == static_cast<int>(getuid()) : matchUserList(sd, "Runas_Alias", c.runasUsers, target);
    if (!groupGiven) return userOk;
    for (auto &g : c.runasGroups) {
        if (g == "ALL" || g == group || (g[0] == '%' && g.substr(1) == group)) return userOk;
    }
    return false;
}

struct Verdict {
    bool listed = false;  // the user appears in some rule for this host
    bool allowed = false;
    bool nopasswd = false;
};

Verdict judge(const Sudoers &sd, const Account &me, const Account &target, const std::string &path, const std::string &args,
              bool groupGiven = false, const std::string &group = "") {
    Verdict v;
    for (auto &r : sd.rules) {
        if (!matchUserList(sd, "User_Alias", r.who, me) || !matchHost(sd, r.hosts)) continue;
        v.listed = true;
        for (auto &c : r.commands) {
            if (!matchRunas(sd, c, target, groupGiven, group)) continue;
            int m = matchCommand(sd, (c.negate ? "!" : "") + c.text, path, args);
            if (m == -1) continue;
            v.allowed = m == 1;
            v.nopasswd = c.nopasswd;
        }
    }
    return v;
}

std::string stamp(int uid) {
    return "/run/sudo/" + std::to_string(uid);
}

bool freshStamp(int uid, long timeout) {
    if (timeout == 0) return false;
    struct stat st;
    if (stat(stamp(uid).c_str(), &st) != 0) return false;
    auto age = std::time(nullptr) - st.st_mtime;
    return age >= 0 && (timeout < 0 || age < timeout);
}

void touchStamp(int uid) {
    mkdir("/run/sudo", 0700);
    chmod("/run/sudo", 0700);
    if (FILE *f = std::fopen(stamp(uid).c_str(), "w")) std::fclose(f);
}

// /var/log/auth.log, as sudo logs through syslog
void logSudo(const std::string &user, const std::string &text) {
    mkdir("/var/log", 0755);
    mode_t old = umask(027);
    if (FILE *f = std::fopen("/var/log/auth.log", "a")) {
        char when[32];
        time_t now = std::time(nullptr);
        std::strftime(when, sizeof(when), "%b %e %H:%M:%S", std::localtime(&now));
        std::fprintf(f, "%s %s sudo: %8s : %s\n", when, hostName().c_str(), user.c_str(), text.c_str());
        std::fclose(f);
    }
    umask(old);
}

// The program sudo would run: a path (relative ones from the current folder) or a name looked up in secure_path.
std::string resolveCommand(const std::string &name, const std::string &path) {
    if (name.find('/') != std::string::npos) return name[0] == '/' ? name : cc::cwd() + "/" + name;
    size_t start = 0;
    while (start <= path.size()) {
        auto end = path.find(':', start);
        auto dir = path.substr(start, end == std::string::npos ? std::string::npos : end - start);
        if (!dir.empty()) {
            auto full = dir + "/" + name;
            struct stat st;
            if (stat(full.c_str(), &st) == 0 && S_ISREG(st.st_mode) && (st.st_mode & 0111)) return full;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return "";
}

// sudo refuses a sudoers file that someone but root could change
bool sudoersSafe(const std::string &path, std::string &why) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) {
        why = path + ": " + std::strerror(errno);
        return false;
    }
    if (st.st_uid != 0) {
        why = path + " is owned by uid " + std::to_string(st.st_uid) + ", should be 0";
        return false;
    }
    if (st.st_mode & 022) {
        why = path + " is world or group writable";
        return false;
    }
    return true;
}

void listRules(const Sudoers &sd, const Account &me) {
    std::printf("Matching Defaults entries for %s on %s:\n    env_reset, secure_path=%s, timestamp_timeout=%ld\n\n", me.name.c_str(),
                hostName().c_str(), sd.securePath.c_str(), sd.timeout < 0 ? -1L : sd.timeout / 60);
    std::printf("User %s may run the following commands on %s:\n", me.name.c_str(), hostName().c_str());
    for (auto &r : sd.rules) {
        if (!matchUserList(sd, "User_Alias", r.who, me) || !matchHost(sd, r.hosts)) continue;
        for (auto &c : r.commands) {
            std::string runas = c.runasGiven ? "(" + join(c.runasUsers, ',') + (c.runasGroups.empty() ? "" : " : " + join(c.runasGroups, ',')) + ")"
                                             : "(root)";
            std::printf("    %s %s%s%s\n", runas.c_str(), c.nopasswd ? "NOPASSWD: " : "", c.negate ? "!" : "", c.text.c_str());
        }
    }
}

int cmdSudo(Args &a) {
    std::string target = "root", group;
    bool loginShell = false, shellMode = false, list = false, validate = false, forget = false, nonInteractive = false;
    size_t i = 1;
    for (; i < a.size() && a[i].size() > 1 && a[i][0] == '-'; i++) {
        auto &o = a[i];
        if (o == "--") {
            i++;
            break;
        }
        if (o == "-u" && i + 1 < a.size()) target = a[++i];
        else if (o == "-g" && i + 1 < a.size()) group = a[++i];
        else if (o == "-i" || o == "--login") loginShell = true;
        else if (o == "-s" || o == "--shell") shellMode = true;
        else if (o == "-l" || o == "--list") list = true;
        else if (o == "-v" || o == "--validate") validate = true;
        else if (o == "-n" || o == "--non-interactive") nonInteractive = true;
        else if (o == "-k" || o == "-K" || o == "--reset-timestamp") forget = true;
        else if (o == "-h" || o == "--help") {
            std::puts("usage: sudo [-u USER] [-g GROUP] [-i | -s] [-n] [COMMAND [ARGS...]]   (-k forget the password, -v refresh, -l list)\n"
                      "who may run what is in /etc/sudoers (edit it with visudo; man sudoers)");
            return 0;
        } else {
            die("invalid option '" + o + "'");
        }
    }
    if (geteuid() != 0) die("must be setuid root");
    int caller = static_cast<int>(getuid());
    auto me = accountById(caller);
    if (!me) die("you do not exist in the passwd database");
    if (forget) {
        unlink(stamp(caller).c_str());
        if (i >= a.size()) return 0;
    }
    std::string why;
    if (!sudoersSafe("/etc/sudoers", why)) die(why);
    auto sd = loadSudoers();
    if (!sd.errors.empty()) {
        for (auto &e : sd.errors) std::fprintf(stderr, "sudo: %s\n", e.c_str());
        die("no valid sudoers sources found, quitting (fix /etc/sudoers with visudo as root, or su)");
    }
    auto acc = account(target);
    if (!acc) die("unknown user " + target);
    std::string shell = acc->shell.empty() ? "/bin/sh" : acc->shell;
    if (i >= a.size() && !loginShell && !shellMode && !list && !validate) {
        std::fputs("usage: sudo [-u USER] [-i | -s] [COMMAND [ARGS...]]\n", stderr);
        return 1;
    }

    // what will run, and its arguments as one string (what sudoers patterns see)
    std::string program, args, commandLine;
    for (size_t k = i; k < a.size(); k++) commandLine += (k > i ? " " : "") + a[k];
    if (loginShell || shellMode) {
        program = shell;
        args = commandLine.empty() ? "" : "-c " + commandLine;
    } else if (i < a.size()) {
        program = resolveCommand(a[i], sd.securePath);
        if (program.empty()) die(a[i] + ": command not found");
        for (size_t k = i + 1; k < a.size(); k++) args += (k > i + 1 ? " " : "") + a[k];
    }
    Verdict v;
    if (caller == 0) {
        v = {true, true, true}; // root may do anything without being asked
    } else if (list || (validate && program.empty())) {
        v = judge(sd, *me, *acc, "", "");
        v.allowed = v.listed;
        v.nopasswd = false;
    } else {
        v = judge(sd, *me, *acc, program, args, !group.empty(), group);
    }
    if (!v.listed) {
        logSudo(me->name, "user NOT in sudoers ; PWD=" + cc::cwd() + " ; USER=" + target + " ; COMMAND=" + program + (args.empty() ? "" : " " + args));
        die(me->name + " is not in the sudoers file. This incident has been reported to the administrator.");
    }
    if (!v.allowed) {
        logSudo(me->name, "command not allowed ; PWD=" + cc::cwd() + " ; USER=" + target + " ; COMMAND=" + program + (args.empty() ? "" : " " + args));
        die("Sorry, user " + me->name + " is not allowed to execute '" + program + (args.empty() ? "" : " " + args) + "' as " + target +
            (group.empty() ? "" : ":" + group) + " on " + hostName() + ".");
    }
    if (caller != 0 && !v.nopasswd && !freshStamp(caller, sd.timeout)) {
        auto hash = hashOf(me->name);
        if (!hash || locked(*hash)) die("account " + me->name + " is locked");
        if (!hash->empty()) {
            if (nonInteractive) die("a password is required");
            int tries = 0;
            while (true) {
                auto pw = askPassword("[sudo] password for " + me->name + ": ");
                if (!pw) return 1;
                if (verify(*hash, *pw)) break;
                if (++tries >= sd.passwdTries) {
                    logSudo(me->name, std::to_string(tries) + " incorrect password attempts ; PWD=" + cc::cwd() + " ; USER=" + target +
                                          " ; COMMAND=" + program);
                    die(std::to_string(tries) + " incorrect password attempt" + (tries == 1 ? "" : "s"));
                }
                cc::sleep(1);
                std::fputs("Sorry, try again.\n", stderr);
            }
        }
    }
    if (caller != 0 && !v.nopasswd) touchStamp(caller);
    if (list) {
        listRules(sd, *me);
        return 0;
    }
    if (validate && program.empty()) return 0;
    logSudo(me->name, "PWD=" + cc::cwd() + " ; USER=" + target + " ; COMMAND=" + program + (args.empty() ? "" : " " + args));

    // env_reset, as sudo does by default: a small known environment, plus env_keep
    std::vector<std::string> env;
    if (const char *term = std::getenv("TERM")) env.push_back(std::string("TERM=") + term);
    for (auto &k : sd.envKeep) {
        if (k == "TERM" || k == "PATH" || k == "HOME") continue;
        if (const char *val = std::getenv(k.c_str())) env.push_back(k + "=" + val);
    }
    env.push_back("PATH=" + sd.securePath);
    env.push_back("HOME=" + acc->home);
    env.push_back("SHELL=" + shell);
    env.push_back("USER=" + acc->name);
    env.push_back("LOGNAME=" + acc->name);
    env.push_back("SUDO_USER=" + me->name);
    env.push_back("SUDO_UID=" + std::to_string(caller));
    env.push_back("SUDO_GID=" + std::to_string(me->gid));
    env.push_back("SUDO_COMMAND=" + program + (args.empty() ? "" : " " + args));
    if (!group.empty()) {
        auto *g = getgrnam(group.c_str());
        if (!g) die("unknown group " + group);
        acc->gid = static_cast<int>(g->gr_gid);
    }
    become(*acc);
    cc::SpawnOptions options;
    options.env = env;
    Args argv;
    if (loginShell) {
        goHome(*acc);
        argv = {shell, "-l"};
        if (!commandLine.empty()) argv.insert(argv.end(), {"-c", commandLine});
    } else if (shellMode) {
        argv = {shell};
        if (!commandLine.empty()) argv.insert(argv.end(), {"-c", commandLine});
    } else {
        argv.assign(a.begin() + static_cast<long>(i), a.end());
        options.program = program; // exactly the program that was allowed
    }
    int pid = cc::spawn(argv, options);
    if (pid < 0) die(argv[0] + ": " + (pid == -ENOENT ? std::string("command not found") : std::strerror(-pid)));
    int status = 0;
    while (true) {
        int r = cc::wait(-1, &status);
        if (r == pid || r < 0) break;
    }
    return status;
}

void needRoot();

// visudo: edit /etc/sudoers (or -f FILE) in a copy, check it, and only then put it in place (root, 0440).
int cmdVisudo(Args &a) {
    std::string file = "/etc/sudoers";
    bool check = false;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-c" || a[i] == "--check") check = true;
        else if (a[i] == "-f" && i + 1 < a.size()) file = a[++i];
        else if (a[i] == "--help" || a[i] == "-h") {
            std::puts("usage: visudo [-c] [-f FILE]   (edit /etc/sudoers safely; -c only checks it; man sudoers)");
            return 0;
        } else die("invalid option '" + a[i] + "'");
    }
    if (check) {
        auto sd = loadSudoers(file);
        for (auto &w : sd.warnings) std::fprintf(stderr, "visudo: warning: %s\n", w.c_str());
        for (auto &e : sd.errors) std::fprintf(stderr, "visudo: %s\n", e.c_str());
        std::string why;
        if (sd.errors.empty() && file == "/etc/sudoers" && !sudoersSafe(file, why)) {
            std::fprintf(stderr, "visudo: %s\n", why.c_str());
            return 1;
        }
        if (sd.errors.empty()) std::printf("%s: parsed OK\n", file.c_str());
        return sd.errors.empty() ? 0 : 1;
    }
    needRoot();
    auto tmp = file + ".tmp";
    int fd = open(tmp.c_str(), O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (fd < 0) die(tmp + ": " + std::strerror(errno) + (errno == EEXIST ? " (another visudo is running? if not, remove it)" : ""));
    if (FILE *in = std::fopen(file.c_str(), "r")) {
        char buf[4096];
        size_t n;
        while ((n = std::fread(buf, 1, sizeof(buf), in)) > 0) write(fd, buf, n);
        std::fclose(in);
    }
    close(fd);
    const char *editor = std::getenv("SUDO_EDITOR");
    if (!editor || !*editor) editor = std::getenv("VISUAL");
    if (!editor || !*editor) editor = std::getenv("EDITOR");
    if (!editor || !*editor) editor = "edit";
    while (true) {
        cc::run({editor, tmp});
        auto sd = loadSudoers(tmp);
        for (auto &w : sd.warnings) std::fprintf(stderr, "visudo: warning: %s\n", w.c_str());
        if (sd.errors.empty()) break;
        for (auto &e : sd.errors) std::fprintf(stderr, ">>> %s <<<\n", e.c_str());
        std::fputs("What now? (e)dit again, e(x)it without saving changes, (Q)uit and save anyway: ", stderr);
        auto answer = readLine(true);
        if (!answer || *answer == "x") {
            unlink(tmp.c_str());
            return 1;
        }
        if (*answer == "Q") break;
    }
    chown(tmp.c_str(), 0, 0);
    chmod(tmp.c_str(), 0440);
    if (std::rename(tmp.c_str(), file.c_str()) != 0) die(file + ": " + std::strerror(errno));
    return 0;
}

// ---- passwd -----------------------------------------------------------------------------------------------------

int cmdPasswd(Args &a) {
    char action = 0;
    std::string target;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        if (o == "-d" || o == "--delete") action = 'd';
        else if (o == "-l" || o == "--lock") action = 'l';
        else if (o == "-u" || o == "--unlock") action = 'u';
        else if (o == "-S" || o == "--status") action = 'S';
        else if (o == "--help") {
            std::puts("usage: passwd [-d | -l | -u | -S] [USER]   (-d no password, -l lock, -u unlock, -S status)");
            return 0;
        } else if (o[0] != '-' && target.empty()) target = o;
        else die("invalid option '" + o + "'");
    }
    if (geteuid() != 0) die("must be setuid root");
    int caller = static_cast<int>(getuid());
    auto me = accountById(caller);
    if (target.empty()) {
        if (!me) die("you do not exist in the passwd database");
        target = me->name;
    }
    auto acc = account(target);
    if (!acc) die("user '" + target + "' does not exist");
    if (caller != 0 && (acc->uid != caller || (action && action != 'S'))) die("You may not view or modify password information for " + target + ".");
    auto shadow = shadowTable();
    auto *row = shadow.find(target);
    if (!row) {
        shadow.rows.push_back({target, "!", std::to_string(today()), "0", "99999", "7", "", "", ""});
        row = &shadow.rows.back();
    }
    auto &hash = (*row)[1];
    switch (action) {
        case 'S': {
            char date[16];
            time_t t = static_cast<time_t>(std::atol((*row)[2].c_str())) * 86400;
            std::strftime(date, sizeof(date), "%Y-%m-%d", std::gmtime(&t));
            std::printf("%s %s %s %s %s %s %s\n", target.c_str(), locked(hash) ? "L" : hash.empty() ? "NP" : "P", date,
                        (*row)[3].empty() ? "0" : (*row)[3].c_str(), (*row)[4].empty() ? "99999" : (*row)[4].c_str(),
                        (*row)[5].empty() ? "7" : (*row)[5].c_str(), (*row)[6].empty() ? "-1" : (*row)[6].c_str());
            return 0;
        }
        case 'd':
            hash.clear();
            break;
        case 'l':
            if (!locked(hash)) hash = "!" + hash;
            break;
        case 'u':
            if (hash.size() > 1 && hash[0] == '!') hash.erase(0, 1);
            else if (hash == "!") die("unlocking the password would leave the account without one (use passwd -d)");
            break;
        default: {
            if (caller != 0 && !hash.empty()) {
                if (locked(hash)) die("Authentication token manipulation error (the password is locked)");
                auto current = askPassword("Current password: ");
                if (!current || !verify(hash, *current)) {
                    cc::sleep(2);
                    die("Authentication token manipulation error");
                }
            }
            std::string fresh;
            for (int tries = 0;; tries++) {
                auto first = askPassword("New password: ");
                if (!first) return 1;
                auto second = askPassword("Retype new password: ");
                if (!second) return 1;
                if (*first != *second) {
                    std::fputs("Sorry, passwords do not match.\n", stderr);
                } else if (first->empty()) {
                    std::fputs("No password has been supplied (passwd -d removes the password).\n", stderr);
                } else {
                    fresh = *first;
                    break;
                }
                if (tries >= 2) die("Have exhausted maximum number of retries for service", 10);
            }
            hash = newHash(fresh);
        }
    }
    (*row)[2] = std::to_string(today());
    shadow.save(0600);
    std::printf("passwd: password %s successfully\n", action == 'l' ? "locked" : action == 'u' ? "unlocked" : action == 'd' ? "removed" : "updated");
    return 0;
}

// ---- lock -------------------------------------------------------------------------------------------------------

// lock: hide the screen until the user's (or root's) password is typed, as vlock. Setuid root, to check the
// password; Ctrl+C, Ctrl+T and Ctrl+D do nothing. Holding Ctrl+R still reboots the computer, which then asks for
// the password at login.
// "300", "300s", "5m", "1h" -> seconds; -1 if not a duration.
int parseDuration(const std::string &text) {
    if (text.empty() || !std::isdigit(static_cast<unsigned char>(text[0]))) return -1;
    char *end = nullptr;
    long n = std::strtol(text.c_str(), &end, 10);
    std::string unit = end;
    if (unit.empty() || unit == "s") return static_cast<int>(n);
    if (unit == "m" || unit == "min") return static_cast<int>(n * 60);
    if (unit == "h") return static_cast<int>(n * 3600);
    return -1;
}

// LOCK_AFTER of /etc/lock.conf (seconds; 0 = off).
int lockConfSeconds() {
    FILE *f = std::fopen("/etc/lock.conf", "r");
    if (!f) return 0;
    char buf[256];
    int seconds = 0;
    while (std::fgets(buf, sizeof(buf), f)) {
        std::string line = buf;
        if (auto hash = line.find('#'); hash != std::string::npos) line.resize(hash);
        while (!line.empty() && std::isspace(static_cast<unsigned char>(line.back()))) line.pop_back();
        if (line.rfind("LOCK_AFTER=", 0) == 0) seconds = std::max(0, parseDuration(line.substr(11)));
    }
    std::fclose(f);
    return seconds;
}

std::string describeSeconds(int s) {
    if (s % 3600 == 0) return std::to_string(s / 3600) + " h";
    if (s % 60 == 0) return std::to_string(s / 60) + " min";
    return std::to_string(s) + " s";
}

// The password prompt the kernel shows while the screen is locked (lock --screen UID): runs as root on its own
// terminal; the right password of the user (or root's) unlocks.
int lockScreen(int uid) {
    auto me = accountById(uid);
    auto hash = me ? hashOf(me->name) : std::nullopt;
    if (!me || !hash || hash->empty() || locked(*hash)) {
        // nobody could unlock it: do not lock
        cc_screen_unlock();
        return 0;
    }
    auto rootHash = hashOf("root");
    cc::setProcessFlags(CC_PROC_CATCH);
    char since[16];
    time_t now = std::time(nullptr);
    std::strftime(since, sizeof(since), "%H:%M", std::localtime(&now));
    while (true) {
        std::printf("\x1b[2J\x1b[H\n  %s is locked by %s since %s.\n\n  Password: ", hostName().c_str(), me->name.c_str(), since);
        std::fflush(stdout);
        bool cancelled = false;
        auto pw = readLine(false, &cancelled);
        if (!pw) continue;
        if (verify(*hash, *pw) || (rootHash && !rootHash->empty() && !locked(*rootHash) && verify(*rootHash, *pw))) break;
        std::puts("\n  Wrong password.");
        std::fflush(stdout);
        cc::sleep(2);
    }
    if (cc_screen_unlock() != 0) die("not the screen's lock prompt");
    return 0;
}

int cmdLock(Args &a) {
    if (a.size() > 1 && a[1] == "--help") {
        std::puts("usage: lock            hide the screen until your password is typed\n"
                  "       lock -t TIME    lock by itself after TIME without input (300, 90s, 5m, 1h; 0: never)\n"
                  "       lock -s         the current auto-lock setting\n"
                  "  A password is needed (passwd). The default for every login: LOCK_AFTER in /etc/lock.conf.\n"
                  "  Programs go on running behind the lock; only the password prompt gets the keyboard.");
        return 0;
    }
    if (a.size() > 2 && a[1] == "--screen") return lockScreen(std::atoi(a[2].c_str()));
    if (geteuid() != 0) die("must be setuid root");
    auto me = accountById(static_cast<int>(getuid()));
    if (!me) die("you do not exist in the passwd database");
    auto hash = hashOf(me->name);
    bool hasPassword = hash && !hash->empty() && !locked(*hash);
    if (a.size() > 1 && a[1] == "-s") {
        int s = cc_screen_autolock(-1, -1);
        if (s < 0) die("not the computer's own screen (auto-lock is for the screen in the world)");
        if (s == 0) std::puts("auto-lock: off");
        else std::printf("auto-lock: after %s without input\n", describeSeconds(s).c_str());
        return 0;
    }
    if (a.size() > 1 && a[1] == "-t") {
        if (a.size() < 3) die("-t needs a time: lock -t 5m (0: never)");
        int seconds = parseDuration(a[2]);
        if (seconds < 0) die("invalid time '" + a[2] + "' (300, 90s, 5m, 1h)");
        if (seconds > 0 && seconds < 10) die("at least 10 seconds");
        if (seconds > 0 && !hasPassword) die(me->name + " has no password to unlock with: set one with passwd first");
        if (cc_screen_autolock(seconds, -1) != 0) die("not the computer's own screen (auto-lock is for the screen in the world)");
        if (seconds == 0) std::puts("auto-lock: off");
        else std::printf("auto-lock: after %s without input\n", describeSeconds(seconds).c_str());
        return 0;
    }
    if (a.size() > 1) die("unknown option " + a[1] + " (lock --help)");
    if (!hasPassword) die(me->name + " has no password to unlock with: set one with passwd first");
    // the computer's own screen: the kernel shows the prompt on a terminal of its own, the programs go on
    if (cc_screen_lock(-1) == 0) return 0;
    // another terminal (an ssh session): hide it behind the alternate screen until the password is typed
    auto rootHash = hashOf("root");
    cc::setProcessFlags(CC_PROC_CATCH); // signals (Ctrl+C, Ctrl+T) become events that nobody waits for
    std::fputs("\x1b[?1049h", stdout); // the alternate screen: what was on the terminal stays hidden behind it
    while (true) {
        std::printf("\x1b[2J\x1b[HThis terminal on %s is locked by %s.\n\nPassword: ", hostName().c_str(), me->name.c_str());
        std::fflush(stdout);
        bool cancelled = false;
        auto pw = readLine(false, &cancelled);
        if (!pw) continue;
        if (verify(*hash, *pw) || (rootHash && !rootHash->empty() && !locked(*rootHash) && verify(*rootHash, *pw))) break;
        std::puts("Wrong password.");
        std::fflush(stdout);
        cc::sleep(2);
    }
    std::fputs("\x1b[?1049l", stdout);
    std::fflush(stdout);
    return 0;
}

// autologin [USER | -d]: log USER in on the computer's screen at every boot, without the password (getty
// --autologin). The rest stays protected: ssh, su and sudo still ask; combine with LOCK_AFTER in /etc/lock.conf.
int cmdAutologin(Args &a) {
    if (a.size() > 1 && a[1] == "--help") {
        std::puts("usage: autologin USER   log USER in at every boot without the password (on this screen only)\n"
                  "       autologin -d     turn it off\n"
                  "       autologin        show the setting\n"
                  "  As root (sudo). ssh, su and sudo still ask for passwords; LOCK_AFTER in /etc/lock.conf locks the\n"
                  "  screen after a while without input.");
        return 0;
    }
    if (a.size() == 1) {
        FILE *f = std::fopen("/etc/autologin", "r");
        char buf[128] = "", name[64] = "", mode[32] = "";
        if (f) {
            std::fgets(buf, sizeof(buf), f);
            std::fclose(f);
        }
        std::sscanf(buf, "%63s %31s", name, mode);
        if (!name[0]) std::puts("autologin: off");
        else if (std::string(mode) == "always") std::printf("autologin: %s, at every boot\n", name);
        else std::printf("autologin: %s, while %s has no password\n", name, name);
        return 0;
    }
    if (geteuid() != 0 || getuid() != 0) die("Permission denied. (run it with sudo)");
    if (a[1] == "-d") {
        if (unlink("/etc/autologin") != 0 && errno != ENOENT) die(std::string("/etc/autologin: ") + std::strerror(errno));
        std::puts("autologin: off");
        return 0;
    }
    auto acc = account(a[1]);
    if (!acc) die("no such user: " + a[1]);
    mode_t old = umask(022);
    FILE *f = std::fopen("/etc/autologin", "w");
    umask(old);
    if (!f) die(std::string("/etc/autologin: ") + std::strerror(errno));
    std::fprintf(f, "%s always\n", acc->name.c_str());
    std::fclose(f);
    chmod("/etc/autologin", 0644);
    std::printf("autologin: %s, at every boot\n", acc->name.c_str());
    if (acc->uid == 0) std::puts("autologin: warning: anyone at this computer is root now");
    if (lockConfSeconds() == 0) std::puts("autologin: tip: LOCK_AFTER=5m in /etc/lock.conf locks the screen when nobody uses it");
    return 0;
}

// ---- useradd / userdel / usermod / groupadd / groupdel ------------------------------------------------------------

void needRoot() {
    if (geteuid() != 0) die("Permission denied. (run it with sudo)");
}

bool validName(const std::string &n) {
    if (n.empty() || n.size() > 32 || !(std::islower(static_cast<unsigned char>(n[0])) || n[0] == '_')) return false;
    for (char c : n) {
        if (!(std::islower(static_cast<unsigned char>(c)) || std::isdigit(static_cast<unsigned char>(c)) || c == '_' || c == '-')) return false;
    }
    return true;
}

int freeId(const Table &t, int column, int from) {
    int id = from;
    bool clash = true;
    while (clash) {
        clash = false;
        for (auto &r : t.rows) {
            if (r.size() > static_cast<size_t>(column) && std::atoi(r[column].c_str()) == id && !r[column].empty()) {
                clash = true;
                id++;
                break;
            }
        }
    }
    return id;
}

int groupId(const std::string &g) {
    if (!g.empty() && std::isdigit(static_cast<unsigned char>(g[0]))) return std::atoi(g.c_str());
    auto *gr = getgrnam(g.c_str());
    if (!gr) die("group '" + g + "' does not exist");
    return static_cast<int>(gr->gr_gid);
}

// Add `user` to (or remove from) the member list of the groups in `names`.
void setMembership(Table &groups, const std::string &user, const std::vector<std::string> &names, bool replace) {
    for (auto &r : groups.rows) {
        if (r.size() < 4 || r[0][0] == '#') continue;
        auto members = r[3].empty() ? std::vector<std::string>{} : split(r[3], ',');
        bool wanted = false;
        for (auto &n : names) wanted |= n == r[0] || n == r[2];
        std::vector<std::string> kept;
        bool present = false;
        for (auto &m : members) {
            if (m == user) {
                present = true;
                if (!replace || wanted) kept.push_back(m);
            } else {
                kept.push_back(m);
            }
        }
        if (wanted && !present) kept.push_back(user);
        r[3] = join(kept, ',');
    }
}

void removeTree(const std::string &path) {
    cc::run({"rm", "-rf", path});
}

// ---- setup (first boot) -----------------------------------------------------------------------------------------

bool validHostname(const std::string &name) {
    bool ok = !name.empty() && name.size() <= 63 && name.front() != '-' && name.back() != '-';
    for (char c : name) ok &= std::isalnum(static_cast<unsigned char>(c)) || c == '-';
    return ok;
}

// A password asked twice; empty only if `allowEmpty`.
std::string askNewPassword(const std::string &who, bool allowEmpty) {
    while (true) {
        auto first = askPassword(allowEmpty ? "Password for " + who + " (empty: none, use sudo): " : "Password for " + who + ": ");
        if (!first) continue;
        if (first->empty()) {
            if (allowEmpty) return "";
            std::fputs("The password cannot be empty.\n", stderr);
            continue;
        }
        auto second = askPassword("Retype the password: ");
        if (second && *first == *second) return *first;
        std::fputs("The passwords do not match, again.\n", stderr);
    }
}

// setup: run by init on a new computer (while /etc/firstboot exists): the computer's name, the first user (in
// wheel, so sudo works) and the passwords, as a Linux installer asks. Interrupted, it simply runs again next boot.
int cmdSetup(Args &) {
    needRoot();
    auto id = cc::computerId();
    auto suggested = hostName();
    std::printf("\x1b[2J\x1b[H\x1b[96mCC: Bare Metal setup\x1b[0m\n\n"
                "This computer is new: give it a name and create your account.\n"
                "The account may use sudo; root stays without a password unless you give it one.\n\n");
    std::string host;
    while (true) {
        std::printf("Computer name [%s]: ", suggested.c_str());
        std::fflush(stdout);
        auto line = readLine(true);
        if (!line) continue;
        host = line->empty() ? suggested : *line;
        if (validHostname(host)) break;
        std::puts("Letters, digits and '-' only (up to 63).");
    }
    std::string name;
    while (true) {
        std::printf("Your user name: ");
        std::fflush(stdout);
        auto line = readLine(true);
        if (!line) continue;
        name = *line;
        if (!validName(name)) {
            std::puts("Lower-case letters, digits, '_' and '-', starting with a letter (up to 32).");
            continue;
        }
        if (account(name)) {
            std::puts("That name is taken, choose another.");
            continue;
        }
        break;
    }
    auto password = askNewPassword(name, false);
    auto rootPassword = askNewPassword("root", true);

    if (FILE *f = std::fopen("/etc/hostname", "w")) {
        std::fprintf(f, "%s\n", host.c_str());
        std::fclose(f);
    }
    auto passwd = passwdTable();
    auto grp = groupTable();
    auto shadow = shadowTable();
    int uid = freeId(passwd, 2, 1000);
    int gid = freeId(grp, 2, uid);
    auto home = "/home/" + name;
    passwd.rows.push_back({name, "x", std::to_string(uid), std::to_string(gid), name, home, "/bin/sh"});
    grp.rows.push_back({name, "x", std::to_string(gid), ""});
    setMembership(grp, name, {"wheel", "users"}, false);
    shadow.rows.push_back({name, newHash(password), std::to_string(today()), "0", "99999", "7", "", "", ""});
    if (!rootPassword.empty()) {
        if (auto *root = shadow.find("root")) {
            (*root)[1] = newHash(rootPassword);
            (*root)[2] = std::to_string(today());
        }
    }
    passwd.save(0644);
    grp.save(0644);
    shadow.save(0600);
    mkdir("/home", 0755);
    mkdir(home.c_str(), 0750);
    chown(home.c_str(), static_cast<uid_t>(uid), static_cast<gid_t>(gid));
    chmod(home.c_str(), 0750);
    unlink("/etc/firstboot");
    std::printf("\nDone: computer #%d is %s, log in as %s.\n", id, host.c_str(), name.c_str());
    return 0;
}

int cmdUseradd(Args &a) {
    std::string home, shell = "/bin/sh", group, groups, comment, name;
    int uid = -1;
    bool createHome = true;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        auto next = [&]() -> std::string {
            if (i + 1 >= a.size()) die("option " + o + " needs a value");
            return a[++i];
        };
        if (o == "-m" || o == "--create-home") createHome = true;
        else if (o == "-M" || o == "--no-create-home") createHome = false;
        else if (o == "-d" || o == "--home-dir") home = next();
        else if (o == "-s" || o == "--shell") shell = next();
        else if (o == "-g" || o == "--gid") group = next();
        else if (o == "-G" || o == "--groups") groups = next();
        else if (o == "-u" || o == "--uid") uid = std::atoi(next().c_str());
        else if (o == "-c" || o == "--comment") comment = next();
        else if (o == "--help") {
            std::puts("usage: useradd [-M] [-d HOME] [-s SHELL] [-g GROUP] [-G G1,G2] [-u UID] [-c COMMENT] NAME\n"
                      "the account is locked until `passwd NAME`; a group named like the user is created");
            return 0;
        } else if (o[0] != '-' && name.empty()) name = o;
        else die("invalid option '" + o + "'");
    }
    needRoot();
    if (!validName(name)) die("invalid user name '" + name + "'");
    auto passwd = passwdTable();
    auto grp = groupTable();
    auto shadow = shadowTable();
    if (passwd.find(name)) die("user '" + name + "' already exists", 9);
    if (uid < 0) uid = freeId(passwd, 2, 1000);
    else if (accountById(uid)) die("UID " + std::to_string(uid) + " is not unique", 4);
    int gid;
    if (!group.empty()) {
        gid = groupId(group);
    } else {
        if (grp.find(name)) die("group " + name + " exists - if you want to add this user to that group, use -g " + name, 9);
        gid = freeId(grp, 2, uid);
        grp.rows.push_back({name, "x", std::to_string(gid), ""});
    }
    if (home.empty()) home = "/home/" + name;
    passwd.rows.push_back({name, "x", std::to_string(uid), std::to_string(gid), comment, home, shell});
    shadow.rows.push_back({name, "!", std::to_string(today()), "0", "99999", "7", "", "", ""});
    if (!groups.empty()) setMembership(grp, name, split(groups, ','), false);
    passwd.save(0644);
    grp.save(0644);
    shadow.save(0600);
    if (createHome) {
        if (mkdir(home.c_str(), 0750) != 0 && errno != EEXIST) std::fprintf(stderr, "useradd: cannot create %s: %s\n", home.c_str(), std::strerror(errno));
        chown(home.c_str(), static_cast<uid_t>(uid), static_cast<gid_t>(gid));
        chmod(home.c_str(), 0750);
    }
    return 0;
}

int cmdUserdel(Args &a) {
    bool removeHome = false;
    std::string name;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-r" || a[i] == "--remove") removeHome = true;
        else if (a[i] == "--help") {
            std::puts("usage: userdel [-r] NAME   (-r also deletes the home directory)");
            return 0;
        } else name = a[i];
    }
    needRoot();
    auto acc = account(name);
    if (!acc) die("user '" + name + "' does not exist", 6);
    if (acc->uid == 0) die("refusing to delete root");
    auto passwd = passwdTable();
    auto grp = groupTable();
    auto shadow = shadowTable();
    auto drop = [&](Table &t) {
        std::erase_if(t.rows, [&](auto &r) { return !r.empty() && r[0] == name; });
    };
    drop(passwd);
    drop(shadow);
    setMembership(grp, name, {}, true);
    // the user's own group goes too when nobody else is in it
    std::erase_if(grp.rows, [&](auto &r) { return r.size() >= 4 && r[0] == name && r[3].empty() && std::atoi(r[2].c_str()) == acc->gid; });
    passwd.save(0644);
    grp.save(0644);
    shadow.save(0600);
    if (removeHome && acc->home.size() > 1) removeTree(acc->home);
    return 0;
}

int cmdUsermod(Args &a) {
    std::string groups, shell, home, comment, group, name;
    bool append = false, moveHome = false, lock = false, unlock = false;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        auto next = [&]() -> std::string {
            if (i + 1 >= a.size()) die("option " + o + " needs a value");
            return a[++i];
        };
        if (o == "-a" || o == "--append") append = true;
        else if (o == "-aG") {
            append = true;
            groups = next();
        } else if (o == "-G" || o == "--groups") groups = next();
        else if (o == "-g" || o == "--gid") group = next();
        else if (o == "-s" || o == "--shell") shell = next();
        else if (o == "-d" || o == "--home") home = next();
        else if (o == "-m" || o == "--move-home") moveHome = true;
        else if (o == "-c" || o == "--comment") comment = next();
        else if (o == "-L" || o == "--lock") lock = true;
        else if (o == "-U" || o == "--unlock") unlock = true;
        else if (o == "--help") {
            std::puts("usage: usermod [-a] [-G G1,G2] [-g GROUP] [-s SHELL] [-d HOME [-m]] [-c COMMENT] [-L | -U] NAME\n"
                      "  usermod -aG wheel NAME   lets NAME use sudo");
            return 0;
        } else if (o[0] != '-' && name.empty()) name = o;
        else die("invalid option '" + o + "'");
    }
    needRoot();
    auto passwd = passwdTable();
    auto *row = passwd.find(name);
    if (!row) die("user '" + name + "' does not exist", 6);
    if (!shell.empty()) (*row)[6] = shell;
    if (!comment.empty()) (*row)[4] = comment;
    if (!group.empty()) (*row)[3] = std::to_string(groupId(group));
    if (!home.empty()) {
        if (moveHome && std::rename((*row)[5].c_str(), home.c_str()) != 0) die("cannot move " + (*row)[5] + ": " + std::strerror(errno));
        (*row)[5] = home;
    }
    passwd.save(0644);
    if (!groups.empty()) {
        auto grp = groupTable();
        auto names = split(groups, ',');
        for (auto &g : names) groupId(g); // every group must exist
        setMembership(grp, name, names, !append);
        grp.save(0644);
    }
    if (lock || unlock) {
        auto shadow = shadowTable();
        if (auto *s = shadow.find(name)) {
            auto &hash = (*s)[1];
            if (lock && !locked(hash)) hash = "!" + hash;
            if (unlock && hash.size() > 1 && hash[0] == '!') hash.erase(0, 1);
            shadow.save(0600);
        }
    }
    return 0;
}

int cmdGroupadd(Args &a) {
    int gid = -1;
    std::string name;
    for (size_t i = 1; i < a.size(); i++) {
        if ((a[i] == "-g" || a[i] == "--gid") && i + 1 < a.size()) gid = std::atoi(a[++i].c_str());
        else if (a[i] == "--help") {
            std::puts("usage: groupadd [-g GID] NAME");
            return 0;
        } else name = a[i];
    }
    needRoot();
    if (!validName(name)) die("'" + name + "' is not a valid group name");
    auto grp = groupTable();
    if (grp.find(name)) die("group '" + name + "' already exists", 9);
    if (gid < 0) gid = freeId(grp, 2, 1000);
    grp.rows.push_back({name, "x", std::to_string(gid), ""});
    grp.save(0644);
    return 0;
}

int cmdGroupdel(Args &a) {
    if (a.size() < 2 || a[1] == "--help") {
        std::puts("usage: groupdel NAME");
        return a.size() < 2;
    }
    needRoot();
    auto grp = groupTable();
    auto *row = grp.find(a[1]);
    if (!row) die("group '" + a[1] + "' does not exist", 6);
    int gid = std::atoi((*row)[2].c_str());
    setpwent();
    while (auto *pw = getpwent()) {
        if (static_cast<int>(pw->pw_gid) == gid) die("cannot remove the primary group of user '" + std::string(pw->pw_name) + "'", 8);
    }
    endpwent();
    std::erase_if(grp.rows, [&](auto &r) { return !r.empty() && r[0] == a[1]; });
    grp.save(0644);
    return 0;
}

int cmdGroups(Args &a) {
    std::vector<std::string> users(a.begin() + 1, a.end());
    if (users.empty()) {
        // our own process groups
        gid_t list[64];
        int n = getgroups(64, list);
        std::string out;
        auto *g = getgrgid(getegid());
        out = g ? g->gr_name : std::to_string(getegid());
        for (int k = 0; k < n; k++) {
            if (list[k] == getegid()) continue;
            auto *gr = getgrgid(list[k]);
            out += " " + (gr ? std::string(gr->gr_name) : std::to_string(list[k]));
        }
        std::puts(out.c_str());
        return 0;
    }
    int status = 0;
    for (auto &u : users) {
        auto acc = account(u);
        if (!acc) {
            std::fprintf(stderr, "groups: '%s': no such user\n", u.c_str());
            status = 1;
            continue;
        }
        gid_t list[64];
        int n = 64;
        getgrouplist(u.c_str(), static_cast<gid_t>(acc->gid), list, &n);
        std::string out = u + " :";
        for (int k = 0; k < n && k < 64; k++) {
            auto *gr = getgrgid(list[k]);
            out += " " + (gr ? std::string(gr->gr_name) : std::to_string(list[k]));
        }
        std::puts(out.c_str());
    }
    return status;
}

} // namespace

int main(int argc, char **argv) {
    Args args(argv, argv + argc);
    auto slash = args[0].rfind('/');
    prog = slash == std::string::npos ? args[0] : args[0].substr(slash + 1);
    static const std::map<std::string, int (*)(Args &)> commands = {
        {"login", cmdLogin}, {"su", cmdSu}, {"sudo", cmdSudo}, {"passwd", cmdPasswd}, {"useradd", cmdUseradd},
        {"userdel", cmdUserdel}, {"usermod", cmdUsermod}, {"groupadd", cmdGroupadd}, {"groupdel", cmdGroupdel},
        {"groups", cmdGroups}, {"lock", cmdLock}, {"autologin", cmdAutologin}, {"visudo", cmdVisudo}, {"setup", cmdSetup},
    };
    auto it = commands.find(prog);
    if (it == commands.end() && args.size() > 1) {
        args.erase(args.begin());
        prog = args[0];
        it = commands.find(prog);
    }
    if (it == commands.end()) {
        std::fputs("userutils: commands: login su sudo visudo passwd lock useradd userdel usermod groupadd groupdel groups\n", stderr);
        return 1;
    }
    int r = it->second(args);
    std::fflush(stdout);
    return r;
}
