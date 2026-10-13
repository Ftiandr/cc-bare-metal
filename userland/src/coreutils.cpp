// coreutils - multi-call binary with the standard CC: Bare Metal commands. /bin/<name> files are exec aliases
// ("#!@/bin/coreutils"), so every command shares one compiled module.
#include <baremetal.hpp>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <fnmatch.h>
#include <functional>
#include <map>
#include <grp.h>
#include <optional>
#include <pwd.h>
#include <regex.h>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

extern char **environ;

namespace {

using Args = std::vector<std::string>;

std::string prog;

[[noreturn]] void die(const std::string &message) {
    std::fprintf(stderr, "%s: %s\n", prog.c_str(), message.c_str());
    std::exit(1);
}

int warnErrno(const std::string &what) {
    std::fprintf(stderr, "%s: %s: %s\n", prog.c_str(), what.c_str(), std::strerror(errno));
    return 1;
}

bool has(const std::string &s, char c) {
    return s.find(c) != std::string::npos;
}

bool isOption(const std::string &a) {
    return a.size() > 1 && a[0] == '-';
}

std::string baseName(const std::string &p) {
    auto s = p.rfind('/');
    return s != std::string::npos && s + 1 < p.size() ? p.substr(s + 1) : p;
}

std::string join(const std::string &dir, const std::string &name) {
    return !dir.empty() && dir.back() == '/' ? dir + name : dir + "/" + name;
}

std::optional<struct stat> statOf(const std::string &path) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return std::nullopt;
    return st;
}

bool isDir(const std::string &path) {
    auto st = statOf(path);
    return st && S_ISDIR(st->st_mode);
}

// Directory entries without "." and "..".
std::vector<std::string> listDir(const std::string &path, bool &ok) {
    std::vector<std::string> out;
    DIR *d = opendir(path.c_str());
    ok = d != nullptr;
    if (!d) return out;
    while (auto *e = readdir(d)) {
        std::string name = e->d_name;
        if (name != "." && name != "..") out.push_back(name);
    }
    closedir(d);
    return out;
}

// Run fn over each file named on the command line from `first`, or stdin if there are none.
int forEachInput(const Args &args, size_t first, const std::function<int(FILE *, const std::string &)> &fn) {
    // a read error (a device unplugged, a disk ejected while reading) is reported like GNU does: "cat: F: ..."
    auto run = [&](FILE *f, const std::string &name) {
        errno = 0;
        int status = fn(f, name);
        if (std::ferror(f)) status |= warnErrno(name == "-" ? "standard input" : name);
        return status;
    };
    if (first >= args.size()) return run(stdin, "-");
    int status = 0;
    for (size_t i = first; i < args.size(); i++) {
        FILE *f = args[i] == "-" ? stdin : std::fopen(args[i].c_str(), "r");
        if (!f) {
            status |= warnErrno(args[i]);
            continue;
        }
        status |= run(f, args[i]);
        if (f != stdin) std::fclose(f);
    }
    return status;
}

// Lines without their newline.
std::vector<std::string> readLines(FILE *f) {
    std::vector<std::string> out;
    std::string line;
    int c;
    while ((c = std::fgetc(f)) != EOF) {
        if (c == '\n') {
            out.push_back(std::move(line));
            line.clear();
        } else {
            line += static_cast<char>(c);
        }
    }
    if (!line.empty()) out.push_back(std::move(line));
    return out;
}

std::string readAllStdin() {
    std::string out;
    char buf[16384];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), stdin)) > 0) out.append(buf, n);
    return out;
}

std::optional<std::string> readAll(const std::string &path) {
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) return std::nullopt;
    std::string out;
    char buf[16384];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    bool failed = std::ferror(f);
    int err = errno;
    std::fclose(f);
    if (failed) { // read error (device or disk gone): the caller reports errno
        errno = err;
        return std::nullopt;
    }
    return out;
}

// Copy as data arrives, as GNU cat does: read() returns what is there, where fread() would wait for a full buffer
// (a stream that never ends - /dev/periph/x/events, a pipe, a terminal - would show nothing for a long time).
int copyStream(FILE *f, const std::string &name) {
    std::fflush(stdout);
    int in = fileno(f);
    char buf[8192];
    while (true) {
        ssize_t n = read(in, buf, sizeof(buf));
        if (n == 0) return 0;
        if (n < 0) {
            if (errno == EINTR) continue;
            return warnErrno(name == "-" ? "standard input" : name);
        }
        for (ssize_t done = 0; done < n;) {
            ssize_t w = write(1, buf + done, static_cast<size_t>(n - done));
            if (w <= 0) return warnErrno("write error");
            done += w;
        }
    }
}

// ---- cat / echo -------------------------------------------------------------------------------------------------

// cat [-n] [-b] [-s] [-E] [-T] [-A] [FILE...]: as GNU cat.
int cmdCat(Args &a) {
    bool number = false, numberNonBlank = false, squeeze = false, ends = false, tabs = false;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]) && a[i] != "-"; i++) {
        if (a[i] == "--") {
            i++;
            break;
        }
        if (a[i] == "--help") {
            std::puts("usage: cat [-nbsETA] [FILE...]   (-n number lines, -b number non-blank, -s squeeze blank, -E show $ at ends, -T show tabs)");
            return 0;
        }
        for (char f : a[i].substr(1)) {
            if (f == 'n') number = true;
            else if (f == 'b') numberNonBlank = true;
            else if (f == 's') squeeze = true;
            else if (f == 'E') ends = true;
            else if (f == 'T') tabs = true;
            else if (f == 'A') ends = tabs = true;
            else if (f != 'u' && f != 'v') die(std::string("invalid option -- '") + f + "'");
        }
    }
    if (!number && !numberNonBlank && !squeeze && !ends && !tabs) return forEachInput(a, i, copyStream);
    long lineNo = 0;
    bool lastBlank = false;
    return forEachInput(a, i, [&](FILE *f, const std::string &) {
        std::string line;
        int c;
        bool more = true;
        while (more) {
            line.clear();
            bool newline = false;
            while ((c = std::fgetc(f)) != EOF) {
                if (c == '\n') {
                    newline = true;
                    break;
                }
                line += static_cast<char>(c);
            }
            if (c == EOF && line.empty()) break;
            more = c != EOF;
            bool blank = line.empty();
            if (squeeze && blank && lastBlank) continue;
            lastBlank = blank;
            if (numberNonBlank ? !blank : number) std::printf("%6ld\t", ++lineNo);
            for (char ch : line) {
                if (tabs && ch == '\t') std::fputs("^I", stdout);
                else std::putchar(ch);
            }
            if (ends && newline) std::putchar('$');
            if (newline) std::putchar('\n');
        }
        return 0;
    });
}

// echo [-neE] [ARG...]: -n no newline, -e interpret backslash escapes (\n \t \\ \0NNN \c ...), -E don't (default).
int cmdEcho(Args &a) {
    size_t i = 1;
    bool newline = true, escapes = false;
    for (; i < a.size(); i++) {
        auto &opt = a[i];
        if (opt.size() < 2 || opt[0] != '-' || opt.find_first_not_of("neE", 1) != std::string::npos) break;
        for (char f : opt.substr(1)) {
            if (f == 'n') newline = false;
            else if (f == 'e') escapes = true;
            else escapes = false;
        }
    }
    for (; i < a.size(); i++) {
        if (!escapes) {
            std::fputs(a[i].c_str(), stdout);
        } else {
            auto &t = a[i];
            for (size_t k = 0; k < t.size(); k++) {
                if (t[k] != '\\' || k + 1 >= t.size()) {
                    std::putchar(t[k]);
                    continue;
                }
                char e = t[++k];
                switch (e) {
                    case 'n': std::putchar('\n'); break;
                    case 't': std::putchar('\t'); break;
                    case 'r': std::putchar('\r'); break;
                    case 'a': std::putchar('\a'); break;
                    case 'b': std::putchar('\b'); break;
                    case 'f': std::putchar('\f'); break;
                    case 'v': std::putchar('\v'); break;
                    case 'e': std::putchar(27); break;
                    case '\\': std::putchar('\\'); break;
                    case 'c': std::fflush(stdout); return 0;
                    case '0': {
                        int v = 0, n = 0;
                        while (n < 3 && k + 1 < t.size() && t[k + 1] >= '0' && t[k + 1] <= '7') v = v * 8 + (t[++k] - '0'), n++;
                        std::putchar(v);
                        break;
                    }
                    default: std::putchar('\\'); std::putchar(e);
                }
            }
        }
        if (i + 1 < a.size()) std::putchar(' ');
    }
    if (newline) std::putchar('\n');
    return 0;
}

// ---- ls ---------------------------------------------------------------------------------------------------------

std::string userName(uid_t uid);
std::string groupName(gid_t gid);

struct LsOptions {
    bool all = false, almostAll = false, longFormat = false, human = false, onePerLine = false, directory = false;
    bool reverse = false, byTime = false, bySize = false, classify = false, recursive = false;
};

// GNU's -h sizes: 512, 4.0K, 1.2M (one decimal below 10, rounded up)
std::string humanSize(long long size) {
    if (size < 1024) return std::to_string(size);
    const char *units = "KMGT";
    double v = static_cast<double>(size);
    int u = -1;
    while (v >= 1024 && u < 3) v /= 1024, u++;
    char buf[16];
    if (v < 10) std::snprintf(buf, sizeof(buf), "%.1f%c", std::ceil(v * 10) / 10, units[u]);
    else std::snprintf(buf, sizeof(buf), "%.0f%c", std::ceil(v), units[u]);
    return buf;
}

std::string modeString(const struct stat &st) {
    std::string m = S_ISDIR(st.st_mode) ? "d" : S_ISCHR(st.st_mode) ? "c" : S_ISLNK(st.st_mode) ? "l" : "-";
    unsigned perms = st.st_mode & 07777;
    const char *rwx = "rwxrwxrwx";
    for (int b = 8; b >= 0; b--) m += (perms >> b & 1) ? rwx[8 - b] : '-';
    // setuid, setgid, sticky: s/S over the x of owner and group, t/T over the others' x
    if (perms & 04000) m[3] = (perms & 0100) ? 's' : 'S';
    if (perms & 02000) m[6] = (perms & 0010) ? 's' : 'S';
    if (perms & 01000) m[9] = (perms & 0001) ? 't' : 'T';
    return m;
}

std::string lsTime(time_t t) {
    char buf[32] = "";
    time_t now = std::time(nullptr);
    if (auto *tm = std::localtime(&t)) {
        bool recent = now - t < 180L * 24 * 3600 && t <= now + 3600;
        std::strftime(buf, sizeof(buf), recent ? "%b %e %H:%M" : "%b %e  %Y", tm);
    }
    return buf;
}

struct LsEntry {
    std::string name, path;
    std::optional<struct stat> st;
    bool dir() const { return st && S_ISDIR(st->st_mode); }
};

void lsSort(std::vector<LsEntry> &entries, const LsOptions &o) {
    std::sort(entries.begin(), entries.end(), [&](const LsEntry &x, const LsEntry &y) {
        if (o.byTime && x.st && y.st && x.st->st_mtime != y.st->st_mtime) return x.st->st_mtime > y.st->st_mtime;
        if (o.bySize && x.st && y.st && x.st->st_size != y.st->st_size) return x.st->st_size > y.st->st_size;
        return x.name < y.name;
    });
    if (o.reverse) std::reverse(entries.begin(), entries.end());
}

void lsPrint(std::vector<LsEntry> &entries, const LsOptions &o) {
    bool color = isatty(1);
    auto shown = [&](const LsEntry &e) {
        std::string suffix = o.classify && e.dir() ? "/" : "";
        if (!color) return e.name + suffix;
        bool program = e.st && (e.st->st_mode & 0111) && !e.dir();
        if (e.dir()) return "\x1b[94m" + e.name + "\x1b[0m" + suffix;
        if (program || (e.name.size() > 4 && e.name.rfind(".lua") == e.name.size() - 4)) return "\x1b[92m" + e.name + "\x1b[0m";
        return e.name;
    };
    if (o.longFormat) {
        size_t sizeWidth = 1, linkWidth = 1, userWidth = 1, groupWidth = 1;
        for (auto &e : entries) {
            if (e.st) {
                userWidth = std::max(userWidth, userName(e.st->st_uid).size());
                groupWidth = std::max(groupWidth, groupName(e.st->st_gid).size());
            }
            long long size = e.st ? e.st->st_size : 0;
            sizeWidth = std::max(sizeWidth, (o.human ? humanSize(size) : std::to_string(size)).size());
            linkWidth = std::max(linkWidth, std::to_string(e.st ? e.st->st_nlink : 1).size());
        }
        for (auto &e : entries) {
            if (!e.st) {
                std::printf("?????????? ? ? ? ? ? %s\n", e.name.c_str());
                continue;
            }
            long long size = e.st->st_size;
            std::printf("%s %*llu %-*s %-*s %*s %s %s\n", modeString(*e.st).c_str(), static_cast<int>(linkWidth),
                        static_cast<unsigned long long>(e.st->st_nlink ? e.st->st_nlink : 1), static_cast<int>(userWidth),
                        userName(e.st->st_uid).c_str(), static_cast<int>(groupWidth), groupName(e.st->st_gid).c_str(),
                        static_cast<int>(sizeWidth), (o.human ? humanSize(size) : std::to_string(size)).c_str(),
                        lsTime(e.st->st_mtime).c_str(), shown(e).c_str());
        }
    } else if (o.onePerLine || !color) {
        for (auto &e : entries) std::printf("%s\n", (o.classify && e.dir() ? e.name + "/" : e.name).c_str());
    } else {
        size_t maxWidth = 1;
        for (auto &e : entries) maxWidth = std::max(maxWidth, e.name.size() + (o.classify && e.dir() ? 1 : 0));
        int width = static_cast<int>(maxWidth) + 2;
        int perRow = std::max(1, cc::tty::size().cols / std::max(1, width));
        int n = static_cast<int>(entries.size());
        int rows = (n + perRow - 1) / perRow;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < perRow; c++) {
                int k = c * rows + r;
                if (k >= n) continue;
                auto &e = entries[static_cast<size_t>(k)];
                std::fputs(shown(e).c_str(), stdout);
                int len = static_cast<int>(e.name.size()) + (o.classify && e.dir() ? 1 : 0);
                if (c + 1 < perRow && k + rows < n) std::printf("%*s", width - len, "");
            }
            std::putchar('\n');
        }
    }
}

int lsDir(const std::string &path, const LsOptions &o, bool header) {
    bool ok;
    auto names = listDir(path, ok);
    if (!ok) return warnErrno(path);
    if (o.all) {
        names.push_back(".");
        names.push_back("..");
    } else if (!o.almostAll) {
        names.erase(std::remove_if(names.begin(), names.end(), [](auto &n) { return n[0] == '.'; }), names.end());
    }
    std::vector<LsEntry> entries;
    for (auto &n : names) entries.push_back({n, join(path, n), statOf(join(path, n))});
    lsSort(entries, o);
    if (header) std::printf("%s:\n", path.c_str());
    if (o.longFormat) {
        long long blocks = 0;
        for (auto &e : entries) if (e.st && !e.dir()) blocks += (e.st->st_size + 1023) / 1024;
        std::printf("total %lld\n", blocks);
    }
    lsPrint(entries, o);
    return 0;
}

// ls -R: a folder, then every folder below it, each with a "path:" header (as GNU ls; "." and ".." are not entered).
int lsTree(const std::string &path, const LsOptions &o) {
    int status = lsDir(path, o, true);
    bool ok;
    auto names = listDir(path, ok);
    if (!ok) return status;
    std::vector<LsEntry> subdirs;
    for (auto &n : names) {
        if (n[0] == '.' && !o.all && !o.almostAll) continue;
        auto full = join(path, n);
        auto st = statOf(full);
        if (st && S_ISDIR(st->st_mode)) subdirs.push_back({n, full, st});
    }
    lsSort(subdirs, o);
    for (auto &d : subdirs) {
        std::putchar('\n');
        status |= lsTree(d.path, o);
    }
    return status;
}

// ls [-aAlh1drtSF] [FILE...]: as GNU ls (long format: mode, links, owner, group, size, time, name).
int cmdLs(Args &a) {
    LsOptions o;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "--help") {
            std::puts("usage: ls [-aAlh1drtSFR] [FILE...]   (-R: folders below too)");
            return 0;
        }
        for (char f : a[i].substr(1)) {
            if (f == 'a') o.all = true;
            else if (f == 'A') o.almostAll = true;
            else if (f == 'l') o.longFormat = true;
            else if (f == 'h') o.human = true;
            else if (f == '1') o.onePerLine = true;
            else if (f == 'd') o.directory = true;
            else if (f == 'r') o.reverse = true;
            else if (f == 't') o.byTime = true;
            else if (f == 'S') o.bySize = true;
            else if (f == 'F') o.classify = true;
            else if (f == 'R') o.recursive = true;
            else die(std::string("invalid option -- '") + f + "'");
        }
    }
    Args targets(a.begin() + static_cast<long>(i), a.end());
    if (targets.empty()) targets.push_back(".");
    int status = 0;
    std::vector<LsEntry> files;
    std::vector<std::string> dirs;
    for (auto &t : targets) {
        auto st = statOf(t);
        if (!st) status |= warnErrno(t);
        else if (S_ISDIR(st->st_mode) && !o.directory) dirs.push_back(t);
        else files.push_back({t, t, st});
    }
    lsSort(files, o);
    if (!files.empty()) lsPrint(files, o);
    for (size_t k = 0; k < dirs.size(); k++) {
        if (k > 0 || !files.empty()) std::putchar('\n');
        status |= o.recursive ? lsTree(dirs[k], o) : lsDir(dirs[k], o, targets.size() > 1);
    }
    return status;
}

// ---- cp / mv / rm / mkdir / rmdir / touch / pwd -----------------------------------------------------------------

int copyFile(const std::string &from, const std::string &to) {
    FILE *in = std::fopen(from.c_str(), "rb");
    if (!in) return warnErrno(from);
    FILE *out = std::fopen(to.c_str(), "wb");
    if (!out) {
        std::fclose(in);
        return warnErrno(to);
    }
    char buf[16384];
    size_t n;
    int status = 0;
    while ((n = std::fread(buf, 1, sizeof(buf), in)) > 0) {
        if (std::fwrite(buf, 1, n, out) != n) {
            status = warnErrno(to);
            break;
        }
    }
    std::fclose(in);
    if (std::fclose(out) != 0 && status == 0) status = warnErrno(to);
    return status;
}

mode_t currentUmask() {
    mode_t m = umask(0);
    umask(m);
    return m;
}

// Like GNU cp: a new copy gets the source's permission bits minus the umask (an existing file keeps its own);
// with `preserve` (-p) the exact mode and, for root, the owner.
int copyTree(const std::string &from, const std::string &to, bool preserve = false) {
    auto st = statOf(from);
    if (!st) return warnErrno(from);
    bool existed = statOf(to).has_value();
    int status = 0;
    if (!S_ISDIR(st->st_mode)) {
        status = copyFile(from, to);
        if (status) return status;
    } else {
        if (mkdir(to.c_str(), 0700) != 0 && errno != EEXIST) return warnErrno(to);
        bool ok;
        for (auto &name : listDir(from, ok)) status |= copyTree(join(from, name), join(to, name), preserve);
        if (!ok) status |= warnErrno(from);
    }
    if (preserve) {
        if (geteuid() == 0) chown(to.c_str(), st->st_uid, st->st_gid);
        chmod(to.c_str(), st->st_mode & 07777);
    } else if (!existed || S_ISDIR(st->st_mode)) {
        chmod(to.c_str(), st->st_mode & 0777 & ~currentUmask());
    }
    return status;
}

int removeTree(const std::string &path) {
    auto st = statOf(path);
    if (!st) return warnErrno(path);
    if (!S_ISDIR(st->st_mode)) return unlink(path.c_str()) == 0 ? 0 : warnErrno(path);
    bool ok;
    int status = 0;
    for (auto &name : listDir(path, ok)) status |= removeTree(join(path, name));
    if (rmdir(path.c_str()) != 0) return warnErrno(path);
    return status;
}

// "cp a dir" copies into dir/a.
std::string targetPath(const std::string &src, const std::string &dst) {
    return isDir(dst) ? join(dst, baseName(src)) : dst;
}

int cmdCp(Args &a) {
    size_t i = 1;
    bool recursive = false, preserve = false;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (has(a[i], 'r') || has(a[i], 'R') || has(a[i], 'a')) recursive = true;
        if (has(a[i], 'p') || has(a[i], 'a')) preserve = true;
    }
    if (a.size() - i < 2) die("usage: cp [-r] SOURCE... DEST");
    int status = 0;
    for (; i + 1 < a.size(); i++) {
        if (isDir(a[i]) && !recursive) {
            std::fprintf(stderr, "cp: %s is a directory (use -r)\n", a[i].c_str());
            status = 1;
            continue;
        }
        status |= copyTree(a[i], targetPath(a[i], a.back()), preserve);
    }
    return status;
}

int cmdMv(Args &a) {
    if (a.size() < 3) die("usage: mv SOURCE... DEST");
    int status = 0;
    for (size_t i = 1; i + 1 < a.size(); i++) {
        auto to = targetPath(a[i], a.back());
        if (std::rename(a[i].c_str(), to.c_str()) == 0) continue;
        if (errno == EXDEV) {
            int s = copyTree(a[i], to);
            status |= s ? s : removeTree(a[i]);
        } else {
            status |= warnErrno(a[i]);
        }
    }
    return status;
}

int cmdRm(Args &a) {
    size_t i = 1;
    bool recursive = false, force = false;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (has(a[i], 'r') || has(a[i], 'R')) recursive = true;
        if (has(a[i], 'f')) force = true;
    }
    int status = 0;
    for (; i < a.size(); i++) {
        auto st = statOf(a[i]);
        if (!st) {
            if (!force) status |= warnErrno(a[i]);
        } else if (S_ISDIR(st->st_mode)) {
            if (recursive) status |= removeTree(a[i]);
            else {
                std::fprintf(stderr, "rm: %s is a directory (use -r)\n", a[i].c_str());
                status = 1;
            }
        } else if (unlink(a[i].c_str()) != 0) {
            status |= warnErrno(a[i]);
        }
    }
    return status;
}

int mkdirParents(const std::string &path) {
    for (size_t i = 1; i < path.size(); i++) {
        if (path[i] == '/') mkdir(path.substr(0, i).c_str(), 0777);
    }
    if (mkdir(path.c_str(), 0777) != 0 && errno != EEXIST) return warnErrno(path);
    return 0;
}

// mkdir [-p] [-m MODE] DIR...: new directories get 0777 minus the umask, or MODE (octal).
int cmdMkdir(Args &a) {
    bool parents = false;
    long mode = -1;
    Args dirs;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-p" || a[i] == "--parents") parents = true;
        else if (a[i] == "-m" && i + 1 < a.size()) mode = std::strtol(a[++i].c_str(), nullptr, 8);
        else if (a[i].rfind("-m", 0) == 0 && a[i].size() > 2) mode = std::strtol(a[i].c_str() + 2, nullptr, 8);
        else if (a[i] == "--help") {
            std::puts("usage: mkdir [-p] [-m MODE] DIR...");
            return 0;
        } else dirs.push_back(a[i]);
    }
    int status = 0;
    for (auto &d : dirs) {
        int r = parents ? mkdirParents(d) : (mkdir(d.c_str(), 0777) != 0 ? warnErrno(d) : 0);
        if (r == 0 && mode >= 0 && chmod(d.c_str(), static_cast<mode_t>(mode)) != 0) r = warnErrno(d);
        status |= r;
    }
    return status;
}

std::string userName(uid_t uid) {
    static std::map<uid_t, std::string> cache;
    auto it = cache.find(uid);
    if (it != cache.end()) return it->second;
    auto *pw = getpwuid(uid);
    return cache[uid] = pw ? pw->pw_name : std::to_string(uid);
}

std::string groupName(gid_t gid) {
    static std::map<gid_t, std::string> cache;
    auto it = cache.find(gid);
    if (it != cache.end()) return it->second;
    auto *gr = getgrgid(gid);
    return cache[gid] = gr ? gr->gr_name : std::to_string(gid);
}

int chownTree(const std::string &path, long uid, long gid, bool recursive) {
    int status = 0;
    if (chown(path.c_str(), static_cast<uid_t>(uid), static_cast<gid_t>(gid)) != 0) status = warnErrno("changing ownership of '" + path + "'");
    auto st = statOf(path);
    if (recursive && st && S_ISDIR(st->st_mode)) {
        bool ok;
        for (auto &name : listDir(path, ok)) status |= chownTree(join(path, name), uid, gid, true);
    }
    return status;
}

// chown [-R] OWNER[:GROUP] FILE... / chgrp [-R] GROUP FILE...
int cmdChown(Args &a) {
    bool recursive = false;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]) && a[i] != "--help"; i++) recursive |= has(a[i], 'R');
    if (i < a.size() && a[i] == "--help") {
        std::puts(prog == "chgrp" ? "usage: chgrp [-R] GROUP FILE..." : "usage: chown [-R] OWNER[:GROUP] FILE...   (owner or group may be a name or a number)");
        return 0;
    }
    if (a.size() - i < 2) die(prog == "chgrp" ? "usage: chgrp [-R] GROUP FILE..." : "usage: chown [-R] OWNER[:GROUP] FILE...");
    auto spec = a[i];
    std::string owner = spec, group;
    if (prog == "chgrp") {
        owner.clear();
        group = spec;
    } else if (auto colon = spec.find_first_of(":."); colon != std::string::npos) {
        owner = spec.substr(0, colon);
        group = spec.substr(colon + 1);
    }
    long uid = -1, gid = -1;
    auto number = [](const std::string &s) { return !s.empty() && s.find_first_not_of("0123456789") == std::string::npos; };
    if (!owner.empty()) {
        if (number(owner)) uid = std::atol(owner.c_str());
        else if (auto *pw = getpwnam(owner.c_str())) uid = pw->pw_uid;
        else die("invalid user: '" + owner + "'");
    }
    if (!group.empty()) {
        if (number(group)) gid = std::atol(group.c_str());
        else if (auto *gr = getgrnam(group.c_str())) gid = gr->gr_gid;
        else die("invalid group: '" + group + "'");
    } else if (prog == "chown" && spec.back() == ':' && uid >= 0) {
        // "chown user: file": the user's login group
        if (auto *pw = getpwuid(static_cast<uid_t>(uid))) gid = pw->pw_gid;
    }
    int status = 0;
    for (size_t k = i + 1; k < a.size(); k++) status |= chownTree(a[k], uid, gid, recursive);
    return status;
}

int cmdRmdir(Args &a) {
    int status = 0;
    for (size_t i = 1; i < a.size(); i++) {
        if (rmdir(a[i].c_str()) != 0) status |= warnErrno(a[i]);
    }
    return status;
}

int cmdTouch(Args &a) {
    int status = 0;
    for (size_t i = 1; i < a.size(); i++) {
        int fd = open(a[i].c_str(), O_WRONLY | O_CREAT, 0644);
        if (fd < 0) status |= warnErrno(a[i]);
        else close(fd);
    }
    return status;
}

int cmdPwd(Args &) {
    std::puts(cc::cwd().c_str());
    return 0;
}

// ---- head / tail / wc -------------------------------------------------------------------------------------------

struct HeadTailOptions {
    long count = 10;
    bool bytes = false, fromStart = false, follow = false, quiet = false, verbose = false;
    Args files;
};

// head/tail options: -n N, -nN, -N, -c N, tail's +N ("from line N"), -f, -q, -v.
HeadTailOptions parseHeadTail(const Args &a) {
    HeadTailOptions o;
    auto setCount = [&](std::string v) {
        if (!v.empty() && v[0] == '+') o.fromStart = true, v.erase(0, 1);
        else if (!v.empty() && v[0] == '-') v.erase(0, 1);
        char *end = nullptr;
        o.count = std::strtol(v.c_str(), &end, 10);
        if (v.empty() || (*end && *end != 'k' && *end != 'K' && *end != 'M')) die("invalid number: '" + v + "'");
        if (*end == 'k' || *end == 'K') o.count *= 1024;
        if (*end == 'M') o.count *= 1024 * 1024;
    };
    bool options = true;
    for (size_t i = 1; i < a.size(); i++) {
        auto &arg = a[i];
        if (!options || arg == "-" || arg[0] != '-' || arg.size() < 2) {
            if (options && arg.size() > 1 && arg[0] == '+' && prog == "tail" && o.files.empty()) setCount(arg);
            else o.files.push_back(arg);
            continue;
        }
        if (arg == "--") {
            options = false;
        } else if (arg == "--help") {
            std::printf("usage: %s [-n [+]N] [-c [+]N]%s [-q|-v] [FILE...]\n", prog.c_str(), prog == "tail" ? " [-f]" : "");
            std::exit(0);
        } else if (std::isdigit(static_cast<unsigned char>(arg[1]))) {
            setCount(arg.substr(1));
        } else {
            for (size_t k = 1; k < arg.size(); k++) {
                char f = arg[k];
                if (f == 'n' || f == 'c') {
                    o.bytes = f == 'c';
                    std::string v = arg.substr(k + 1);
                    if (v.empty()) {
                        if (i + 1 >= a.size()) die(std::string("option requires an argument -- '") + f + "'");
                        v = a[++i];
                    }
                    setCount(v);
                    break;
                }
                if (f == 'f' || f == 'F') o.follow = true;
                else if (f == 'q') o.quiet = true;
                else if (f == 'v') o.verbose = true;
                else die(std::string("invalid option -- '") + f + "'");
            }
        }
    }
    if (o.files.empty()) o.files.push_back("-");
    return o;
}

// Runs fn on each file with "==> NAME <==" headers when there are several (as GNU head/tail).
int eachWithHeader(const HeadTailOptions &o, const std::function<int(FILE *, const std::string &)> &fn) {
    bool headers = o.verbose || (o.files.size() > 1 && !o.quiet);
    int status = 0;
    for (size_t i = 0; i < o.files.size(); i++) {
        auto &name = o.files[i];
        FILE *f = name == "-" ? stdin : std::fopen(name.c_str(), "r");
        if (!f) {
            status |= warnErrno(name);
            continue;
        }
        if (headers) std::printf("%s==> %s <==\n", i ? "\n" : "", name == "-" ? "standard input" : name.c_str());
        status |= fn(f, name);
        if (f != stdin) std::fclose(f);
    }
    return status;
}

// head [-n N] [-c N] [-q|-v] [FILE...]: the first N lines (or bytes).
int cmdHead(Args &a) {
    auto o = parseHeadTail(a);
    return eachWithHeader(o, [&](FILE *f, const std::string &) {
        long n = 0;
        int c;
        while (n < o.count && (c = std::fgetc(f)) != EOF) {
            std::putchar(c);
            if (o.bytes || c == '\n') n++;
        }
        return 0;
    });
}

// tail [-n [+]N] [-c [+]N] [-f] [-q|-v] [FILE...]: the last N lines (or bytes), +N from line N; -f keeps printing
// what is appended to the (last) file.
int cmdTail(Args &a) {
    auto o = parseHeadTail(a);
    int status = eachWithHeader(o, [&](FILE *f, const std::string &) {
        std::string data;
        char buf[8192];
        size_t n;
        while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) data.append(buf, n);
        size_t start = 0;
        if (o.bytes) {
            if (o.fromStart) start = std::min(data.size(), static_cast<size_t>(std::max(1L, o.count) - 1));
            else start = data.size() > static_cast<size_t>(o.count) ? data.size() - static_cast<size_t>(o.count) : 0;
        } else if (o.fromStart) {
            long line = 1;
            while (line < o.count && start < data.size()) {
                auto nl = data.find('\n', start);
                if (nl == std::string::npos) {
                    start = data.size();
                    break;
                }
                start = nl + 1;
                line++;
            }
        } else {
            start = data.size();
            long lines = 0;
            // a final line without newline counts as a line too
            size_t k = data.size();
            if (k > 0 && data[k - 1] == '\n') k--;
            while (k > 0) {
                if (data[k - 1] == '\n' && ++lines == o.count) break;
                k--;
            }
            start = o.count <= 0 ? data.size() : k;
        }
        std::fwrite(data.data() + start, 1, data.size() - start, stdout);
        return 0;
    });
    if (o.follow && o.files.back() != "-") {
        auto &name = o.files.back();
        auto st = statOf(name);
        long long offset = st ? st->st_size : 0;
        while (true) {
            std::fflush(stdout);
            cc::sleep(0.5);
            auto now = statOf(name);
            if (!now) continue;
            if (now->st_size < offset) offset = 0; // truncated
            if (now->st_size == offset) continue;
            if (FILE *f = std::fopen(name.c_str(), "r")) {
                std::fseek(f, static_cast<long>(offset), SEEK_SET);
                char buf[4096];
                size_t n;
                while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) std::fwrite(buf, 1, n, stdout), offset += static_cast<long long>(n);
                std::fclose(f);
            }
        }
    }
    return status;
}

// wc [-lwcm] [FILE...]: counts in columns as GNU wc (width 7 when reading standard input, else as wide as the
// largest count), a total line for several files.
int cmdWc(Args &a) {
    bool l = false, w = false, c = false;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]) && a[i] != "-"; i++) {
        if (a[i] == "--help") {
            std::puts("usage: wc [-lwcm] [FILE...]");
            return 0;
        }
        l |= has(a[i], 'l');
        w |= has(a[i], 'w');
        c |= has(a[i], 'c') || has(a[i], 'm');
    }
    if (!l && !w && !c) l = w = c = true;
    struct Count {
        long lines = 0, words = 0, chars = 0;
        std::string name;
    };
    std::vector<Count> counts;
    bool stdinUsed = i >= a.size();
    int status = forEachInput(a, i, [&](FILE *f, const std::string &name) {
        Count k;
        k.name = name == "-" ? "" : name;
        if (name == "-") stdinUsed = true;
        bool inWord = false;
        int ch;
        while ((ch = std::fgetc(f)) != EOF) {
            k.chars++;
            if (ch == '\n') k.lines++;
            if (std::isspace(ch)) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                k.words++;
            }
        }
        counts.push_back(k);
        return 0;
    });
    if (counts.size() > 1) {
        Count total;
        total.name = "total";
        for (auto &k : counts) total.lines += k.lines, total.words += k.words, total.chars += k.chars;
        counts.push_back(total);
    }
    int width = 1;
    if (stdinUsed) {
        width = 7;
    } else {
        for (auto &k : counts) {
            for (long v : {k.lines, k.words, k.chars}) width = std::max(width, static_cast<int>(std::to_string(v).size()));
        }
    }
    for (auto &k : counts) {
        std::string out;
        auto field = [&](long v) {
            char buf[32];
            std::snprintf(buf, sizeof(buf), "%*ld", width, v);
            if (!out.empty()) out += ' ';
            out += buf;
        };
        if (l) field(k.lines);
        if (w) field(k.words);
        if (c) field(k.chars);
        if (!k.name.empty()) out += " " + k.name;
        std::puts(out.c_str());
    }
    return status;
}

// ---- grep -------------------------------------------------------------------------------------------------------

class Regex {
public:
    Regex(const std::string &pattern, int flags) : ok_(regcomp(&re_, pattern.c_str(), flags) == 0) {}
    ~Regex() {
        if (ok_) regfree(&re_);
    }
    Regex(const Regex &) = delete;
    Regex &operator=(const Regex &) = delete;
    bool ok() const { return ok_; }
    bool match(const std::string &s, regmatch_t &m) const { return regexec(&re_, s.c_str(), 1, &m, 0) == 0; }
    const regex_t *raw() const { return &re_; }

private:
    regex_t re_{};
    bool ok_;
};

struct GrepOptions {
    bool invert = false, countOnly = false, numbers = false, filesOnly = false, onlyMatching = false, quiet = false;
    bool noNames = false, withNames = false, recursive = false, fixed = false, word = false, line = false;
    int flags = REG_EXTENDED;
};

// One input's lines; returns whether anything matched.
bool grepText(const std::string &text, const std::string &name, const Regex &re, const GrepOptions &o, bool showNames) {
    bool color = isatty(1);
    long n = 0, count = 0;
    size_t start = 0;
    while (start < text.size()) {
        auto end = text.find('\n', start);
        if (end == std::string::npos) end = text.size();
        std::string line = text.substr(start, end - start);
        start = end + 1;
        n++;
        regmatch_t m{};
        bool match = re.match(line, m);
        if (match == o.invert) continue;
        count++;
        if (o.quiet) return true;
        if (o.countOnly || o.filesOnly) continue;
        auto prefix = [&]() {
            if (showNames) std::printf(color ? "\x1b[95m%s\x1b[0m:" : "%s:", name.c_str());
            if (o.numbers) std::printf(color ? "\x1b[92m%ld\x1b[0m:" : "%ld:", n);
        };
        if (o.onlyMatching && !o.invert) {
            size_t pos = 0;
            while (pos <= line.size() && regexec(re.raw(), line.c_str() + pos, 1, &m, pos ? REG_NOTBOL : 0) == 0) {
                if (m.rm_eo == m.rm_so) {
                    pos += static_cast<size_t>(m.rm_so) + 1;
                    continue;
                }
                prefix();
                std::printf(color ? "\x1b[91m%.*s\x1b[0m\n" : "%.*s\n", static_cast<int>(m.rm_eo - m.rm_so), line.c_str() + pos + m.rm_so);
                pos += static_cast<size_t>(m.rm_eo);
            }
            continue;
        }
        prefix();
        if (color && match && !o.invert && m.rm_eo > m.rm_so) {
            std::printf("%.*s\x1b[91m%.*s\x1b[0m%s\n", static_cast<int>(m.rm_so), line.c_str(), static_cast<int>(m.rm_eo - m.rm_so),
                        line.c_str() + m.rm_so, line.c_str() + m.rm_eo);
        } else {
            std::puts(line.c_str());
        }
    }
    if (o.filesOnly && count) std::puts(name.c_str());
    if (o.countOnly) {
        if (showNames) std::printf("%s:", name.c_str());
        std::printf("%ld\n", count);
    }
    return count > 0;
}

bool grepPath(const std::string &path, const Regex &re, const GrepOptions &o, bool showNames) {
    if (isDir(path)) {
        if (!o.recursive) {
            std::fprintf(stderr, "grep: %s: Is a directory\n", path.c_str());
            return false;
        }
        bool ok, found = false;
        auto names = listDir(path, ok);
        std::sort(names.begin(), names.end());
        for (auto &name : names) {
            if (path == "/" && (name == "proc" || name == "dev")) continue;
            found |= grepPath(join(path, name), re, o, showNames);
            if (found && o.quiet) return true;
        }
        return found;
    }
    auto text = path == "-" ? std::optional<std::string>(readAllStdin()) : readAll(path);
    if (!text) {
        warnErrno(path);
        return false;
    }
    // Skip binary files when searching directories.
    if (o.recursive && text->find('\0') != std::string::npos) return false;
    return grepText(*text, path, re, o, showNames);
}

std::string regexQuote(const std::string &s) {
    std::string out;
    for (char c : s) {
        if (std::strchr("\\^$.[]|()*+?{}", c)) out += '\\';
        out += c;
    }
    return out;
}

int cmdGrep(Args &a) {
    GrepOptions o;
    std::vector<std::string> patterns;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "--") {
            i++;
            break;
        }
        if (a[i] == "-e" && i + 1 < a.size()) {
            patterns.push_back(a[++i]);
            continue;
        }
        for (char f : a[i].substr(1)) {
            switch (f) {
                case 'i': o.flags |= REG_ICASE; break;
                case 'v': o.invert = true; break;
                case 'c': o.countOnly = true; break;
                case 'n': o.numbers = true; break;
                case 'l': o.filesOnly = true; break;
                case 'o': o.onlyMatching = true; break;
                case 'q': case 's': o.quiet = f == 'q' || o.quiet; break;
                case 'h': o.noNames = true; break;
                case 'H': o.withNames = true; break;
                case 'r': case 'R': o.recursive = true; break;
                case 'F': o.fixed = true; break;
                case 'E': o.flags |= REG_EXTENDED; break;
                case 'G': o.flags &= ~REG_EXTENDED; break;
                case 'w': o.word = true; break;
                case 'x': o.line = true; break;
                default: die(std::string("unknown option -") + f);
            }
        }
    }
    if (patterns.empty()) {
        if (i >= a.size()) die("usage: grep [-ivcnlohHrqFEwx] [-e PATTERN] PATTERN [FILE...]");
        patterns.push_back(a[i++]);
    }
    std::string pattern;
    for (auto &p : patterns) {
        auto one = o.fixed ? regexQuote(p) : p;
        if (o.word) one = "(^|[^[:alnum:]_])(" + one + ")([^[:alnum:]_]|$)";
        if (o.line) one = "^(" + one + ")$";
        pattern += (pattern.empty() ? "" : "|") + (patterns.size() > 1 ? "(" + one + ")" : one);
    }
    if (o.fixed || o.word || o.line || patterns.size() > 1) o.flags |= REG_EXTENDED;
    Regex re(pattern, o.flags);
    if (!re.ok()) die("bad pattern: " + pattern);
    std::vector<std::string> files(a.begin() + static_cast<long>(i), a.end());
    if (files.empty()) files.push_back(o.recursive ? "." : "-");
    bool showNames = !o.noNames && (o.withNames || files.size() > 1 || o.recursive);
    bool found = false;
    for (auto &f : files) {
        found |= grepPath(f, re, o, showNames);
        if (found && o.quiet) break;
    }
    return found ? 0 : 1;
}

// ---- sort / uniq ------------------------------------------------------------------------------------------------

std::vector<std::string> collectLines(const Args &a, size_t first) {
    std::vector<std::string> all;
    forEachInput(a, first, [&](FILE *f, const std::string &) {
        auto lines = readLines(f);
        all.insert(all.end(), lines.begin(), lines.end());
        return 0;
    });
    return all;
}

int cmdSort(Args &a) {
    bool reverse = false, numeric = false, unique = false;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        reverse |= has(a[i], 'r');
        numeric |= has(a[i], 'n');
        unique |= has(a[i], 'u');
    }
    auto lines = collectLines(a, i);
    std::stable_sort(lines.begin(), lines.end(), [&](const std::string &x, const std::string &y) {
        bool less = numeric ? std::atof(x.c_str()) < std::atof(y.c_str()) : x < y;
        bool greater = numeric ? std::atof(y.c_str()) < std::atof(x.c_str()) : y < x;
        return reverse ? greater : less;
    });
    if (unique) lines.erase(std::unique(lines.begin(), lines.end()), lines.end());
    for (auto &l : lines) std::puts(l.c_str());
    return 0;
}

int cmdUniq(Args &a) {
    size_t i = 1;
    bool count = i < a.size() && a[i] == "-c";
    if (count) i++;
    auto lines = collectLines(a, i);
    for (size_t k = 0; k < lines.size();) {
        size_t run = 1;
        while (k + run < lines.size() && lines[k + run] == lines[k]) run++;
        if (count) std::printf("%7zu %s\n", run, lines[k].c_str());
        else std::puts(lines[k].c_str());
        k += run;
    }
    return 0;
}

// ---- printf -----------------------------------------------------------------------------------------------------

// Backslash escapes of printf/echo -e; `i` points at the backslash.
void printEscape(const std::string &s, size_t &i) {
    if (i + 1 >= s.size()) {
        std::putchar('\\');
        return;
    }
    switch (s[++i]) {
        case 'n': std::putchar('\n'); break;
        case 't': std::putchar('\t'); break;
        case 'r': std::putchar('\r'); break;
        case 'e': std::putchar(27); break;
        case 'a': std::putchar(7); break;
        case '\\': std::putchar('\\'); break;
        case '0': {
            int v = 0, n = 0;
            while (n < 3 && i + 1 < s.size() && s[i + 1] >= '0' && s[i + 1] <= '7') v = v * 8 + (s[++i] - '0'), n++;
            std::putchar(v);
            break;
        }
        default:
            std::putchar('\\');
            std::putchar(s[i]);
    }
}

int cmdPrintf(Args &a) {
    if (a.size() < 2) die("usage: printf FORMAT [ARGUMENT...]");
    const auto &fmt = a[1];
    size_t next = 2;
    // Like the shell's printf, the format is reused until all arguments are consumed.
    do {
        for (size_t i = 0; i < fmt.size(); i++) {
            if (fmt[i] == '\\') {
                printEscape(fmt, i);
                continue;
            }
            if (fmt[i] != '%') {
                std::putchar(fmt[i]);
                continue;
            }
            if (i + 1 < fmt.size() && fmt[i + 1] == '%') {
                std::putchar('%');
                i++;
                continue;
            }
            // %[flags][width][.precision]conversion
            size_t j = i + 1;
            while (j < fmt.size() && std::strchr("-+ #0123456789.", fmt[j])) j++;
            if (j >= fmt.size()) break;
            std::string spec = fmt.substr(i, j - i);
            char conv = fmt[j];
            std::string arg = next < a.size() ? a[next++] : "";
            switch (conv) {
                case 'd':
                case 'i': std::printf((spec + "lld").c_str(), std::atoll(arg.c_str())); break;
                case 'u':
                case 'x':
                case 'X':
                case 'o': std::printf((spec + "ll" + conv).c_str(), static_cast<unsigned long long>(std::atoll(arg.c_str()))); break;
                case 'f':
                case 'g':
                case 'e': std::printf((spec + conv).c_str(), std::atof(arg.c_str())); break;
                case 'c': std::putchar(arg.empty() ? 0 : arg[0]); break;
                case 'b':
                    for (size_t k = 0; k < arg.size(); k++) {
                        if (arg[k] == '\\') printEscape(arg, k);
                        else std::putchar(arg[k]);
                    }
                    break;
                default: std::printf((spec + "s").c_str(), arg.c_str()); break;
            }
            i = j;
        }
    } while (next < a.size());
    return 0;
}

// ---- tee / yes / seq / basename / dirname / rev / tr / cut ------------------------------------------------------

int cmdTee(Args &a) {
    std::vector<FILE *> outs;
    bool append = false;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-a") {
            append = true;
            continue;
        }
        if (FILE *f = std::fopen(a[i].c_str(), append ? "a" : "w")) outs.push_back(f);
        else warnErrno(a[i]);
    }
    char buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), stdin)) > 0) {
        std::fwrite(buf, 1, n, stdout);
        std::fflush(stdout);
        for (auto *f : outs) std::fwrite(buf, 1, n, f);
    }
    for (auto *f : outs) std::fclose(f);
    return 0;
}

int cmdYes(Args &a) {
    std::string s = a.size() > 1 ? a[1] : "y";
    while (std::puts(s.c_str()) >= 0) {
    }
    return 0;
}

int cmdSeq(Args &a) {
    double first = 1, step = 1, last;
    if (a.size() == 2) {
        last = std::atof(a[1].c_str());
    } else if (a.size() == 3) {
        first = std::atof(a[1].c_str());
        last = std::atof(a[2].c_str());
    } else if (a.size() == 4) {
        first = std::atof(a[1].c_str());
        step = std::atof(a[2].c_str());
        last = std::atof(a[3].c_str());
    } else {
        die("usage: seq [FIRST [STEP]] LAST");
    }
    if (step == 0) return 1;
    for (double x = first; step > 0 ? x <= last : x >= last; x += step) std::printf("%g\n", x);
    return 0;
}

int cmdBasename(Args &a) {
    if (a.size() < 2) return 1;
    auto path = a[1];
    while (path.size() > 1 && path.back() == '/') path.pop_back();
    auto b = baseName(path);
    if (a.size() > 2 && b.size() > a[2].size() && b.compare(b.size() - a[2].size(), a[2].size(), a[2]) == 0) b.resize(b.size() - a[2].size());
    std::puts(b.c_str());
    return 0;
}

int cmdDirname(Args &a) {
    if (a.size() < 2) return 1;
    auto s = a[1].rfind('/');
    if (s == std::string::npos) std::puts(".");
    else if (s == 0) std::puts("/");
    else std::puts(a[1].substr(0, s).c_str());
    return 0;
}

int cmdRev(Args &) {
    for (auto line : readLines(stdin)) {
        std::reverse(line.begin(), line.end());
        std::puts(line.c_str());
    }
    return 0;
}

// A tr SET: ranges (a-z), classes ([:upper:] ...), escapes (\n \t \\ \NNN).
std::string trSet(const std::string &spec) {
    std::string raw;
    for (size_t k = 0; k < spec.size(); k++) {
        if (spec.compare(k, 2, "[:") == 0) {
            auto close = spec.find(":]", k + 2);
            if (close != std::string::npos) {
                auto name = spec.substr(k + 2, close - k - 2);
                for (int c = 0; c < 256; c++) {
                    bool in = name == "upper"  ? std::isupper(c)
                            : name == "lower"  ? std::islower(c)
                            : name == "digit"  ? std::isdigit(c)
                            : name == "alpha"  ? std::isalpha(c)
                            : name == "alnum"  ? std::isalnum(c)
                            : name == "space"  ? std::isspace(c)
                            : name == "blank"  ? (c == ' ' || c == '\t')
                            : name == "punct"  ? std::ispunct(c)
                            : name == "print"  ? std::isprint(c)
                            : name == "graph"  ? std::isgraph(c)
                            : name == "cntrl"  ? std::iscntrl(c)
                            : name == "xdigit" ? std::isxdigit(c) : false;
                    if (c < 128 && in) raw += static_cast<char>(c);
                }
                k = close + 1;
                continue;
            }
        }
        char c = spec[k];
        if (c == '\\' && k + 1 < spec.size()) {
            char e = spec[++k];
            if (e >= '0' && e <= '7') {
                int v = e - '0', n = 1;
                while (n < 3 && k + 1 < spec.size() && spec[k + 1] >= '0' && spec[k + 1] <= '7') v = v * 8 + (spec[++k] - '0'), n++;
                c = static_cast<char>(v);
            } else {
                c = e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == 'a' ? '\a' : e == 'b' ? '\b' : e == 'f' ? '\f' : e == 'v' ? '\v' : e;
            }
        }
        if (k + 2 < spec.size() && spec[k + 1] == '-') {
            char to = spec[k + 2];
            for (int x = static_cast<unsigned char>(c); x <= static_cast<unsigned char>(to); x++) raw += static_cast<char>(x);
            k += 2;
            continue;
        }
        raw += c;
    }
    return raw;
}

// tr [-c] [-d] [-s] SET1 [SET2]: translate, delete or squeeze characters, as GNU tr.
int cmdTr(Args &a) {
    bool complement = false, del = false, squeeze = false;
    size_t i = 1;
    for (; i < a.size() && a[i].size() > 1 && a[i][0] == '-'; i++) {
        if (a[i] == "--help") {
            std::puts("usage: tr [-cds] SET1 [SET2]   (ranges a-z, classes [:upper:] [:digit:] ..., escapes \\n \\t)");
            return 0;
        }
        for (char f : a[i].substr(1)) {
            if (f == 'c' || f == 'C') complement = true;
            else if (f == 'd') del = true;
            else if (f == 's') squeeze = true;
            else die(std::string("invalid option -- '") + f + "'");
        }
    }
    if (i >= a.size()) die("missing operand (usage: tr [-cds] SET1 [SET2])");
    auto set1 = trSet(a[i]);
    auto set2 = i + 1 < a.size() ? trSet(a[i + 1]) : std::string();
    bool in1[256] = {};
    for (unsigned char c : set1) in1[c] = true;
    if (complement) {
        for (auto &b : in1) b = !b;
        set1.clear();
        for (int c = 0; c < 256; c++) if (in1[c]) set1 += static_cast<char>(c);
    }
    int map[256];
    for (int c = 0; c < 256; c++) map[c] = c;
    bool translate = !del && !set2.empty();
    if (translate) {
        for (size_t k = 0; k < set1.size(); k++) {
            map[static_cast<unsigned char>(set1[k])] = static_cast<unsigned char>(set2[std::min(k, set2.size() - 1)]);
        }
    }
    // -s squeezes repeats of the characters of the last set given (SET2 when translating or deleting, else SET1)
    bool squeezeSet[256] = {};
    const std::string &sq = (translate || del) && !set2.empty() ? set2 : set1;
    for (unsigned char c : sq) squeezeSet[c] = true;
    int c, last = -1;
    while ((c = std::getchar()) != EOF) {
        if (del && in1[c]) continue;
        int out = translate ? map[c] : c;
        if (squeeze && squeezeSet[out] && out == last) continue;
        std::putchar(out);
        last = out;
    }
    return 0;
}

// A cut LIST ("1,3-5,7-"): whether position n (1-based) is selected.
struct CutList {
    std::vector<std::pair<long, long>> ranges;
    bool has(long n) const {
        for (auto &[a, b] : ranges) if (n >= a && n <= b) return true;
        return false;
    }
};

CutList parseCutList(const std::string &spec) {
    CutList list;
    size_t start = 0;
    while (start <= spec.size()) {
        auto end = spec.find(',', start);
        auto part = spec.substr(start, end == std::string::npos ? std::string::npos : end - start);
        auto dash = part.find('-');
        long from, to;
        if (dash == std::string::npos) {
            from = to = std::atol(part.c_str());
        } else {
            from = dash == 0 ? 1 : std::atol(part.substr(0, dash).c_str());
            to = dash + 1 >= part.size() ? LONG_MAX : std::atol(part.substr(dash + 1).c_str());
        }
        if (from < 1) die("fields and positions are numbered from 1");
        list.ranges.emplace_back(from, to);
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return list;
}

// cut -f LIST [-d C] [-s] | -c LIST | -b LIST [--output-delimiter S] [FILE...], as GNU cut.
int cmdCut(Args &a) {
    char delim = '\t', mode = 0;
    bool onlyDelimited = false;
    std::string outDelim, spec;
    bool outDelimSet = false;
    Args files;
    for (size_t i = 1; i < a.size(); i++) {
        auto &arg = a[i];
        auto value = [&](size_t prefix) -> std::string {
            if (arg.size() > prefix) return arg.substr(prefix);
            if (i + 1 >= a.size()) die("option requires an argument -- '" + arg.substr(1) + "'");
            return a[++i];
        };
        if (arg == "--help") {
            std::puts("usage: cut -f LIST [-d DELIM] [-s] | -c LIST | -b LIST [--output-delimiter STR] [FILE...]   (LIST: 1,3-5,7-)");
            return 0;
        } else if (arg.rfind("--output-delimiter=", 0) == 0) {
            outDelim = arg.substr(19), outDelimSet = true;
        } else if (arg == "--output-delimiter" && i + 1 < a.size()) {
            outDelim = a[++i], outDelimSet = true;
        } else if (arg.rfind("-d", 0) == 0) {
            auto d = value(2);
            delim = d.empty() ? '\t' : d[0];
        } else if (arg.rfind("-f", 0) == 0 || arg.rfind("-c", 0) == 0 || arg.rfind("-b", 0) == 0) {
            mode = arg[1];
            spec = value(2);
        } else if (arg == "-s") {
            onlyDelimited = true;
        } else if (arg == "-" || arg[0] != '-') {
            files.push_back(arg);
        } else {
            die("invalid option '" + arg + "'");
        }
    }
    if (!mode) die("you must specify a list of bytes, characters, or fields");
    auto list = parseCutList(spec);
    if (!outDelimSet) outDelim = mode == 'f' ? std::string(1, delim) : "";
    Args args{"cut"};
    args.insert(args.end(), files.begin(), files.end());
    return forEachInput(args, 1, [&](FILE *f, const std::string &) {
        for (auto &line : readLines(f)) {
            std::string out;
            if (mode == 'f') {
                if (line.find(delim) == std::string::npos) {
                    if (!onlyDelimited) std::puts(line.c_str());
                    continue;
                }
                long n = 0;
                bool first = true;
                size_t start = 0;
                while (true) {
                    auto end = line.find(delim, start);
                    n++;
                    if (list.has(n)) {
                        if (!first) out += outDelim;
                        out += line.substr(start, end == std::string::npos ? std::string::npos : end - start);
                        first = false;
                    }
                    if (end == std::string::npos) break;
                    start = end + 1;
                }
            } else {
                for (size_t k = 0; k < line.size(); k++) {
                    if (list.has(static_cast<long>(k) + 1)) out += line[k];
                }
            }
            std::puts(out.c_str());
        }
        return 0;
    });
}

// ---- env / sleep / true / false / clear / date / uname ----------------------------------------------------------

// env [-i] [-u NAME]... [NAME=VALUE]... [COMMAND [ARGS...]]: as GNU env.
int cmdEnv(Args &a) {
    size_t i = 1;
    for (; i < a.size() && a[i].size() > 1 && a[i][0] == '-'; i++) {
        if (a[i] == "--help" || a[i] == "-h") {
            std::puts("usage: env [-i] [-u NAME]... [NAME=VALUE]... [COMMAND [ARGS...]]   (no command: print the environment)");
            return 0;
        }
        if (a[i] == "--") {
            i++;
            break;
        }
        if (a[i] == "-i" || a[i] == "--ignore-environment") {
            clearenv();
        } else if (a[i] == "-u" && i + 1 < a.size()) {
            unsetenv(a[++i].c_str());
        } else if (a[i].rfind("-u", 0) == 0 && a[i].size() > 2) {
            unsetenv(a[i].c_str() + 2);
        } else {
            die("invalid option '" + a[i] + "'");
        }
    }
    if (i < a.size() && a[i] == "-") {
        clearenv();
        i++;
    }
    for (; i < a.size() && has(a[i], '='); i++) {
        auto eq = a[i].find('=');
        setenv(a[i].substr(0, eq).c_str(), a[i].substr(eq + 1).c_str(), 1);
    }
    if (i < a.size()) {
        int r = cc::run(Args(a.begin() + static_cast<long>(i), a.end()));
        if (r < 0) {
            std::fprintf(stderr, "env: '%s': No such file or directory\n", a[i].c_str());
            return 127;
        }
        return r;
    }
    for (char **e = environ; e && *e; e++) std::puts(*e);
    return 0;
}

int cmdSleep(Args &a) {
    cc::sleep(a.size() > 1 ? std::atof(a[1].c_str()) : 1);
    return 0;
}

int cmdClear(Args &a) {
    // like xterm's clear: also drops the scrollback (-x keeps it)
    std::fputs(a.size() > 1 && a[1] == "-x" ? "\x1b[2J\x1b[H" : "\x1b[2J\x1b[3J\x1b[H", stdout);
    return 0;
}

int cmdDate(Args &a) {
    time_t t = std::time(nullptr);
    char buf[256];
    std::string fmt = a.size() > 1 && a[1][0] == '+' ? a[1].substr(1) : "%a %b %d %H:%M:%S %Y";
    std::strftime(buf, sizeof(buf), fmt.c_str(), std::localtime(&t));
    std::puts(buf);
    return 0;
}

std::string hostName() {
    return cc::hostname();
}

// hostname [NAME]: show or set (root) the name in /etc/hostname: letters, digits, '-', as on Linux.
int cmdHostname(Args &a) {
    if (a.size() < 2) return std::puts(hostName().c_str()) < 0 ? 1 : 0;
    if (a[1] == "--help") {
        std::puts("usage: hostname [NAME]   (setting it needs root; ssh finds the computer by this name)");
        return 0;
    }
    auto &name = a[1];
    bool ok = !name.empty() && name.size() <= 63 && name.front() != '-' && name.back() != '-';
    for (char c : name) ok &= std::isalnum(static_cast<unsigned char>(c)) || c == '-';
    if (!ok) die("invalid name '" + name + "' (letters, digits and '-', up to 63)");
    FILE *f = std::fopen("/etc/hostname", "w");
    if (!f) return warnErrno("/etc/hostname");
    std::fprintf(f, "%s\n", name.c_str());
    return std::fclose(f) == 0 ? 0 : warnErrno("/etc/hostname");
}

// uname [-asnrvmpio]: sysname BareMetal, release = the system's version, machine wasm32.
int cmdUname(Args &a) {
    bool s = false, n = false, r = false, v = false, m = false, p = false, hw = false, o = false;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "--help") {
            std::puts("usage: uname [-asnrvmpio]");
            return 0;
        }
        if (!isOption(a[i])) die("extra operand '" + a[i] + "'");
        for (char f : a[i].substr(1)) {
            if (f == 'a') s = n = r = v = m = o = true;
            else if (f == 's') s = true;
            else if (f == 'n') n = true;
            else if (f == 'r') r = true;
            else if (f == 'v') v = true;
            else if (f == 'm') m = true;
            else if (f == 'p') p = true;
            else if (f == 'i') hw = true;
            else if (f == 'o') o = true;
            else die(std::string("invalid option -- '") + f + "'");
        }
    }
    if (!s && !n && !r && !v && !m && !p && !hw && !o) s = true;
    auto info = cc::systemInfo();
    std::vector<std::string> parts;
    if (s) parts.push_back("BareMetal");
    if (n) parts.push_back(hostName());
    if (r) parts.push_back(info["version"].asString("?"));
    if (v) parts.push_back("#1");
    if (m) parts.push_back("wasm32");
    if (p) parts.push_back("wasm32");
    if (hw) parts.push_back("wasm32");
    if (o) parts.push_back("CC:BareMetal");
    std::string out;
    for (auto &part : parts) out += (out.empty() ? "" : " ") + part;
    std::puts(out.c_str());
    return 0;
}

// ---- ps / kill / uptime / free / df / du ------------------------------------------------------------------------

int cmdPs(Args &) {
    bool ok;
    auto entries = listDir("/proc", ok);
    if (!ok) return warnErrno("/proc");
    std::vector<int> pids;
    for (auto &e : entries) {
        if (int pid = std::atoi(e.c_str()); pid > 0) pids.push_back(pid);
    }
    std::sort(pids.begin(), pids.end());
    std::printf("  PID  PPID  PGID USER     STAT     MEM COMMAND\n");
    for (int pid : pids) {
        auto status = readAll("/proc/" + std::to_string(pid) + "/status");
        if (!status) continue;
        std::string name = "?";
        int ppid = 0, pgid = 0, uid = 0;
        long mem = 0;
        char state = '?';
        size_t start = 0;
        while (start < status->size()) {
            auto end = status->find('\n', start);
            if (end == std::string::npos) end = status->size();
            auto line = status->substr(start, end - start);
            start = end + 1;
            char word[128];
            if (std::sscanf(line.c_str(), "Name:\t%127s", word) == 1) name = word;
            else if (std::sscanf(line.c_str(), "PPid:\t%d", &ppid) == 1) continue;
            else if (std::sscanf(line.c_str(), "Pgid:\t%d", &pgid) == 1) continue;
            else if (std::sscanf(line.c_str(), "Uid:\t%*d\t%d", &uid) == 1) continue;
            else if (std::sscanf(line.c_str(), "VmSize:\t%ld", &mem) == 1) continue;
            else if (line.rfind("State:\t", 0) == 0 && line.size() > 7) state = line[7];
        }
        auto cmd = readAll("/proc/" + std::to_string(pid) + "/cmdline").value_or("");
        while (!cmd.empty() && cmd.back() == '\0') cmd.pop_back();
        std::replace(cmd.begin(), cmd.end(), '\0', ' ');
        std::printf("%5d %5d %5d %-8s %c   %6ldK %s\n", pid, ppid, pgid, userName(static_cast<uid_t>(uid)).substr(0, 8).c_str(), state, mem,
                    cmd.empty() ? name.c_str() : cmd.c_str());
    }
    return 0;
}

// Signal names and numbers as in Linux (the ones the kernel acts on: INT, KILL, TERM; the others end a process too).
int signalNumber(std::string s) {
    if (!s.empty() && std::isdigit(static_cast<unsigned char>(s[0]))) return std::atoi(s.c_str());
    if (s.rfind("SIG", 0) == 0) s = s.substr(3);
    static const std::map<std::string, int> names = {{"HUP", 1}, {"INT", 2}, {"QUIT", 3}, {"KILL", 9}, {"USR1", 10}, {"USR2", 12}, {"TERM", 15}};
    auto it = names.find(s);
    return it == names.end() ? -1 : it->second;
}

int signalOption(const std::string &opt) {
    auto name = opt.substr(1);
    if (name == "s" || name == "n") return -2; // value follows
    int sig = signalNumber(name);
    if (sig < 0) die("invalid signal: " + opt);
    return sig;
}

// kill [-SIGNAL | -s SIGNAL] PID... ; kill -l
int cmdKill(Args &a) {
    size_t i = 1;
    int sig = CC_SIGTERM;
    if (i < a.size() && (a[i] == "-l" || a[i] == "-L")) {
        std::puts(" 1) SIGHUP  2) SIGINT  3) SIGQUIT  9) SIGKILL 10) SIGUSR1 12) SIGUSR2 15) SIGTERM");
        return 0;
    }
    if (i < a.size() && a[i] == "--help") {
        std::puts("usage: kill [-SIGNAL] PID...   (PIDs from ps; by name: pkill NAME, killall NAME)");
        return 0;
    }
    if (i < a.size() && a[i].size() > 1 && a[i][0] == '-' && a[i] != "--") {
        sig = signalOption(a[i]);
        if (sig == -2 && i + 1 < a.size()) sig = signalNumber(a[++i]);
        if (sig < 0) die("invalid signal");
        i++;
    }
    if (i < a.size() && a[i] == "--") i++;
    if (i >= a.size()) die("usage: kill [-SIGNAL] PID...");
    int status = 0;
    for (; i < a.size(); i++) {
        char *end = nullptr;
        long pid = std::strtol(a[i].c_str(), &end, 10);
        if (a[i].empty() || *end) {
            std::fprintf(stderr, "kill: failed to parse argument: '%s' (a PID is needed; by name: pkill %s)\n", a[i].c_str(), a[i].c_str());
            status = 1;
            continue;
        }
        if (int r = cc_kill(static_cast<int>(pid), sig); r != 0) {
            std::fprintf(stderr, "kill: (%s) - %s\n", a[i].c_str(), r == ESRCH ? "No such process" : std::strerror(r));
            status = 1;
        }
    }
    return status;
}

struct ProcInfo {
    int pid, uid;
    std::string name, cmdline;
};

std::vector<ProcInfo> processList() {
    std::vector<ProcInfo> out;
    bool ok;
    for (auto &e : listDir("/proc", ok)) {
        int pid = std::atoi(e.c_str());
        if (pid <= 0) continue;
        auto status = readAll("/proc/" + e + "/status");
        if (!status) continue;
        ProcInfo p{pid, -1, "", ""};
        char word[128];
        size_t start = 0;
        while (start < status->size()) {
            auto end = status->find('\n', start);
            if (end == std::string::npos) end = status->size();
            auto line = status->substr(start, end - start);
            start = end + 1;
            if (std::sscanf(line.c_str(), "Name:\t%127s", word) == 1) p.name = word;
            std::sscanf(line.c_str(), "Uid:\t%d", &p.uid);
        }
        auto cmd = readAll("/proc/" + e + "/cmdline").value_or("");
        while (!cmd.empty() && cmd.back() == '\0') cmd.pop_back();
        auto first = cmd.substr(0, cmd.find('\0'));
        if (!first.empty()) p.name = baseName(first);
        std::replace(cmd.begin(), cmd.end(), '\0', ' ');
        p.cmdline = cmd.empty() ? p.name : cmd;
        out.push_back(p);
    }
    std::sort(out.begin(), out.end(), [](auto &x, auto &y) { return x.pid < y.pid; });
    return out;
}

// ---- shutdown, reboot, poweroff, halt ---------------------------------------------------------------------------
// As systemd's: shutdown [-r|-h|-P|-k|-c] [--no-wall] [TIME] [MESSAGE]; TIME is now, +MINUTES or HH:MM (default
// +1). A scheduled shutdown is a background process "shutdown --scheduled WHEN ACTION MESSAGE" that warns every
// terminal and then turns the computer off; shutdown -c ends it. Allowed for root and for whoever sits at the
// computer (its own screen), not for other users over ssh.

int fail(const std::string &message) {
    std::fprintf(stderr, "%s: %s\n", prog.c_str(), message.c_str());
    return 1;
}

bool atConsole() {
    return cc_screen_autolock(-1, -1) >= 0; // only answers on the computer's own screen
}

bool mayPowerOff() {
    return getuid() == 0 || atConsole();
}

int powerOff(bool reboot) {
    if (cc_shutdown(reboot ? 1 : 0) != 0) {
        return fail("Permission denied: only root, or the user at the computer itself (use sudo)");
    }
    return 0;
}

std::string clockTime(time_t t, bool withDate) {
    char buf[64];
    std::strftime(buf, sizeof(buf), withDate ? "%a %Y-%m-%d %H:%M:%S" : "%H:%M", std::localtime(&t));
    return buf;
}

void wall(const std::string &text) {
    auto *pw = getpwuid(getuid());
    time_t now = std::time(nullptr);
    std::string msg = "\r\nBroadcast message from " + std::string(pw ? pw->pw_name : "root") + "@" + cc::hostname() + " (" +
                      clockTime(now, true) + "):\r\n\r\n";
    for (char c : text) {
        if (c == '\n') msg += '\r';
        msg += c;
    }
    msg += "\r\n\r\n";
    cc_tty_broadcast(msg.data(), static_cast<int32_t>(msg.size()));
}

std::vector<ProcInfo> scheduledShutdowns() {
    std::vector<ProcInfo> out;
    for (auto &p : processList()) {
        if (p.pid != cc::pid() && p.cmdline.find("shutdown --scheduled ") != std::string::npos) out.push_back(p);
    }
    return out;
}

std::string actionWords(const std::string &action) {
    return action == "reboot" ? "reboot" : "power off";
}

int scheduledDaemon(Args &a) {
    // shutdown --scheduled WHEN ACTION [MESSAGE]
    time_t when = static_cast<time_t>(std::atoll(a[2].c_str()));
    auto action = a[3];
    auto message = a.size() > 4 ? a[4] : "";
    auto note = message.empty() ? "" : "\n" + message;
    std::vector<int> warnings = {3600, 1800, 900, 600, 300, 120, 60, 30, 10};
    while (true) {
        long left = static_cast<long>(when - std::time(nullptr));
        if (left <= 0) break;
        // the next warning: the largest mark below what is left (warned once when passing it)
        int next = 0;
        for (int w : warnings) {
            if (w < left) {
                next = w;
                break;
            }
        }
        cc::sleep(static_cast<double>(left - next));
        left = static_cast<long>(when - std::time(nullptr));
        if (left > 0) {
            auto in = left >= 120 ? std::to_string((left + 30) / 60) + " minutes" : std::to_string(left) + " seconds";
            wall("The system will " + actionWords(action) + " at " + clockTime(when, true) + " (in " + in + ")!" + note);
        }
    }
    if (action == "warn") return 0;
    wall("The system will " + actionWords(action) + " now!" + note);
    cc::sleep(1);
    return powerOff(action == "reboot");
}

int cmdShutdown(Args &a) {
    if (a.size() > 4 && a[1] == "--scheduled") return scheduledDaemon(a);
    std::string action = prog == "reboot" ? "reboot" : "poweroff";
    bool cancel = false, warnOnly = false, noWall = false, show = false, timeGiven = false;
    std::string timeSpec = prog == "shutdown" ? "+1" : "now";
    std::string message;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        if (o == "--help") {
            std::printf("usage: shutdown [-r|-h|-P] [TIME] [MESSAGE]   TIME: now, +MINUTES, HH:MM (default +1)\n"
                        "       shutdown -c      cancel a scheduled shutdown\n"
                        "       shutdown -k      only send the warnings;  --show: what is scheduled\n"
                        "       reboot, poweroff, halt   at once\n"
                        "  Every terminal is warned beforehand. Root, or the user at the computer's own screen.\n");
            return 0;
        }
        if (o == "-r" || o == "--reboot") action = "reboot";
        else if (o == "-h" || o == "-P" || o == "-H" || o == "--poweroff" || o == "--halt") action = "poweroff";
        else if (o == "-c") cancel = true;
        else if (o == "-k") warnOnly = true;
        else if (o == "--no-wall") noWall = true;
        else if (o == "--show") show = true;
        else if (o == "-f" || o == "-n" || o == "--no-sync") {
            // accepted (nothing to sync)
        } else if (o.size() > 1 && o[0] == '-') {
            return fail("invalid option '" + o + "' (shutdown --help)");
        } else if (!timeGiven && prog == "shutdown") {
            timeSpec = o;
            timeGiven = true;
        } else {
            message += (message.empty() ? "" : " ") + o;
        }
    }
    if (show) {
        auto list = scheduledShutdowns();
        if (list.empty()) {
            std::puts("No scheduled shutdown.");
            return 1;
        }
        for (auto &p : list) {
            Args w;
            std::string word;
            for (char c : p.cmdline + " ") {
                if (c == ' ') {
                    if (!word.empty()) w.push_back(word);
                    word.clear();
                } else {
                    word += c;
                }
            }
            if (w.size() >= 4) {
                std::printf("%s scheduled for %s (pid %d), use 'shutdown -c' to cancel.\n",
                            w[3] == "reboot" ? "Reboot" : w[3] == "warn" ? "Warning" : "Shutdown",
                            clockTime(static_cast<time_t>(std::atoll(w[2].c_str())), true).c_str(), p.pid);
            }
        }
        return 0;
    }
    if (!mayPowerOff()) return fail("Permission denied: only root, or the user at the computer itself (use sudo)");
    if (cancel) {
        auto list = scheduledShutdowns();
        for (auto &p : list) cc_kill(p.pid, CC_SIGTERM);
        if (!list.empty() && !noWall) wall("The system shutdown has been cancelled" + (message.empty() ? "" : ": " + message));
        return 0;
    }
    // when
    time_t now = std::time(nullptr), when = now;
    if (timeSpec == "now") {
        when = now;
    } else if (timeSpec[0] == '+') {
        char *end = nullptr;
        long minutes = std::strtol(timeSpec.c_str() + 1, &end, 10);
        if (*end || minutes < 0) return fail("Failed to parse time specification: " + timeSpec);
        when = now + minutes * 60;
    } else {
        int h = 0, m = 0;
        char extra = 0;
        if (std::sscanf(timeSpec.c_str(), "%d:%d%c", &h, &m, &extra) != 2 || h < 0 || h > 23 || m < 0 || m > 59) {
            return fail("Failed to parse time specification: " + timeSpec);
        }
        struct tm t = *std::localtime(&now);
        t.tm_hour = h;
        t.tm_min = m;
        t.tm_sec = 0;
        when = std::mktime(&t);
        if (when <= now) when += 24 * 3600; // tomorrow
    }
    if (warnOnly) action = "warn";
    if (when <= now && action != "warn") return powerOff(action == "reboot");
    // a new schedule replaces the old one, as systemd does
    for (auto &p : scheduledShutdowns()) cc_kill(p.pid, CC_SIGTERM);
    if (!noWall) {
        wall("The system will " + actionWords(action) + " at " + clockTime(when, true) + "!" + (message.empty() ? "" : "\n" + message));
    }
    if (action == "warn") return 0;
    cc::SpawnOptions options;
    options.flags = CC_SPAWN_NEW_GROUP;
    int null = open("/dev/null", O_RDWR);
    options.in = options.out = options.err = null;
    int pid = cc::spawn({"/bin/shutdown", "--scheduled", std::to_string(static_cast<long long>(when)), action, message}, options);
    close(null);
    if (pid < 0) return fail(std::string("cannot schedule: ") + std::strerror(-pid));
    std::printf("%s scheduled for %s, use 'shutdown -c' to cancel.\n", action == "reboot" ? "Reboot" : "Shutdown",
                clockTime(when, true).c_str());
    return 0;
}

// pgrep [-l] [-a] [-f] [-x] [-u USER] PATTERN / pkill [-SIGNAL] ... PATTERN / killall [-SIGNAL] NAME...
// As procps: PATTERN is an extended regular expression matched against the name (-f: the whole command line).
int cmdPgrep(Args &a) {
    bool list = false, full = false, exact = prog == "killall", all = false;
    int sig = CC_SIGTERM;
    long uid = -1;
    Args patterns;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        if (o == "--help") {
            std::printf("usage: %s\n", prog == "pgrep" ? "pgrep [-l | -a] [-f] [-x] [-u USER] PATTERN"
                                    : prog == "pkill" ? "pkill [-SIGNAL] [-f] [-x] [-u USER] PATTERN"
                                                      : "killall [-SIGNAL] [-u USER] NAME...");
            return 0;
        }
        if (o == "-u" && i + 1 < a.size()) {
            auto &u = a[++i];
            if (auto *pw = getpwnam(u.c_str())) uid = pw->pw_uid;
            else if (!u.empty() && std::isdigit(static_cast<unsigned char>(u[0]))) uid = std::atol(u.c_str());
            else die("invalid user name: " + u);
        } else if (o == "-l") list = true;
        else if (o == "-a") all = true;
        else if (o == "-f") full = true;
        else if (o == "-x") exact = true;
        else if (prog != "pgrep" && o.size() > 1 && o[0] == '-') {
            sig = o == "-s" && i + 1 < a.size() ? signalNumber(a[++i]) : signalNumber(o.substr(1));
            if (sig < 0) die("invalid signal: " + o);
        } else patterns.push_back(o);
    }
    if (patterns.empty()) die("no process name given");
    int self = cc::pid();
    int found = 0, failed = 0;
    for (auto &p : processList()) {
        if (p.pid == self || (uid >= 0 && p.uid != uid)) continue;
        bool match = false;
        for (auto &pat : patterns) {
            auto &subject = full ? p.cmdline : p.name;
            if (prog == "killall") {
                match |= subject == pat;
                continue;
            }
            regex_t re;
            auto expr = exact ? "^(" + pat + ")$" : pat;
            if (regcomp(&re, expr.c_str(), REG_EXTENDED | REG_NOSUB) != 0) die("invalid pattern: " + pat);
            match |= regexec(&re, subject.c_str(), 0, nullptr, 0) == 0;
            regfree(&re);
        }
        if (!match) continue;
        found++;
        if (prog == "pgrep") {
            if (all) std::printf("%d %s\n", p.pid, p.cmdline.c_str());
            else if (list) std::printf("%d %s\n", p.pid, p.name.c_str());
            else std::printf("%d\n", p.pid);
        } else if (int r = cc_kill(p.pid, sig); r != 0) {
            std::fprintf(stderr, "%s: killing pid %d failed: %s\n", prog.c_str(), p.pid, std::strerror(r));
            failed++;
        }
    }
    if (prog == "killall" && found == 0) {
        for (auto &pat : patterns) std::fprintf(stderr, "%s: no process found\n", pat.c_str());
    }
    return found == 0 || failed ? 1 : 0;
}

// uptime: as GNU uptime ("  12:34:56 up 5 min,  1 user,  load average: 0.00, 0.00, 0.00").
int cmdUptime(Args &a) {
    if (a.size() > 1 && a[1] == "--help") {
        std::puts("usage: uptime [-p]   (-p: only how long the computer is on)");
        return 0;
    }
    double up = std::atof(readAll("/proc/uptime").value_or("0").c_str());
    long s = static_cast<long>(up);
    if (a.size() > 1 && (a[1] == "-p" || a[1] == "--pretty")) {
        std::printf("up %ld minutes\n", s / 60);
        return 0;
    }
    char now[16];
    time_t t = std::time(nullptr);
    std::strftime(now, sizeof(now), "%H:%M:%S", std::localtime(&t));
    std::string upText;
    long days = s / 86400, hours = (s / 3600) % 24, minutes = (s / 60) % 60;
    if (days) upText = std::to_string(days) + (days == 1 ? " day, " : " days, ");
    if (hours) {
        char buf[16];
        std::snprintf(buf, sizeof(buf), "%2ld:%02ld", hours, minutes);
        upText += buf;
    } else {
        upText += std::to_string(minutes) + " min";
    }
    std::printf(" %s up %s,  1 user,  load average: 0.00, 0.00, 0.00\n", now, upText.c_str());
    return 0;
}

// free [-k|-m|-h]: memory as GNU free (in KiB by default). The machine's memory is per process: "total" is what
// one process may use, "used" what all of them use now.
int cmdFree(Args &a) {
    int unit = 1; // KiB
    bool human = false;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-m") unit = 1024;
        else if (a[i] == "-g") unit = 1024 * 1024;
        else if (a[i] == "-h") human = true;
        else if (a[i] == "--help") {
            std::puts("usage: free [-k|-m|-g|-h]");
            return 0;
        }
    }
    long total = 0, used = 0;
    auto info = readAll("/proc/meminfo").value_or("");
    std::sscanf(info.c_str(), "MemPerProcess: %ld kB\nMemUsed: %ld kB", &total, &used);
    long free = std::max(0L, total - used);
    auto show = [&](long kb) {
        return human ? humanSize(static_cast<long long>(kb) * 1024) : std::to_string(kb / unit);
    };
    std::printf("%15s %11s %11s %11s %11s %11s\n", "total", "used", "free", "shared", "buff/cache", "available");
    std::printf("Mem:   %11s %11s %11s %11s %11s %11s\n", show(total).c_str(), show(used).c_str(), show(free).c_str(), show(0).c_str(),
                show(0).c_str(), show(free).c_str());
    std::printf("Swap:  %11s %11s %11s\n", show(0).c_str(), show(0).c_str(), show(0).c_str());
    return 0;
}

// df [-h] [-k] [-a]: as GNU df. Mounts without a size (read-only system parts, /proc, /dev) are listed only with -a.
int cmdDf(Args &a) {
    bool human = false, all = false;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "--help") {
            std::puts("usage: df [-h] [-a]");
            return 0;
        }
        human |= has(a[i], 'h');
        all |= has(a[i], 'a');
    }
    auto mounts = readAll("/proc/mounts");
    if (!mounts) return warnErrno("/proc/mounts");
    std::printf(human ? "%-12s %6s %6s %6s %4s %s\n" : "%-12s %10s %10s %10s %4s %s\n", "Filesystem", human ? "Size" : "1K-blocks", "Used",
                human ? "Avail" : "Available", "Use%", "Mounted on");
    size_t start = 0;
    while (start < mounts->size()) {
        auto end = mounts->find('\n', start);
        if (end == std::string::npos) end = mounts->size();
        char type[64], path[256], mode[8];
        long long capacity = 0, freeBytes = 0;
        int n = std::sscanf(mounts->substr(start, end - start).c_str(), "%63s %255s %7s %lld %lld", type, path, mode, &capacity, &freeBytes);
        start = end + 1;
        if (n < 3 || (capacity <= 0 && !all)) continue;
        long long used = std::max(0LL, capacity - freeBytes);
        std::string pct = capacity > 0 ? std::to_string((used * 100 + capacity - 1) / capacity) + "%" : "-";
        if (human) {
            std::printf("%-12s %6s %6s %6s %4s %s\n", type, humanSize(capacity).c_str(), humanSize(used).c_str(), humanSize(freeBytes).c_str(),
                        pct.c_str(), path);
        } else {
            std::printf("%-12s %10lld %10lld %10lld %4s %s\n", type, capacity / 1024, used / 1024, freeBytes / 1024, pct.c_str(), path);
        }
    }
    return 0;
}

// Disk usage of a tree in bytes (files rounded up to 1K blocks); prints directories (and files with -a) on the way, as GNU du.
long long duTree(const std::string &path, bool printDirs, bool printFiles, bool human, bool top) {
    auto st = statOf(path);
    if (!st) {
        warnErrno(path);
        return 0;
    }
    long long total = 0;
    bool dir = S_ISDIR(st->st_mode);
    if (dir) {
        bool ok;
        for (auto &name : listDir(path, ok)) total += duTree(join(path, name), printDirs, printFiles, human, false);
    } else {
        total = (st->st_size + 1023) / 1024 * 1024; // whole 1K blocks, as disk usage
    }
    if ((dir && (printDirs || top)) || (!dir && (printFiles || top))) {
        std::string size = human ? humanSize(total) : std::to_string((total + 1023) / 1024);
        std::printf("%s\t%s\n", size.c_str(), path.c_str());
    }
    return total;
}

// du [-s] [-a] [-h] [-c] [FILE...]: sizes in KiB (or -h), every directory unless -s.
int cmdDu(Args &a) {
    bool summary = false, allFiles = false, human = false, grand = false;
    Args paths;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "--help") {
            std::puts("usage: du [-sahc] [FILE...]");
            return 0;
        }
        if (isOption(a[i])) {
            summary |= has(a[i], 's');
            allFiles |= has(a[i], 'a');
            human |= has(a[i], 'h');
            grand |= has(a[i], 'c');
        } else {
            paths.push_back(a[i]);
        }
    }
    if (paths.empty()) paths.push_back(".");
    long long total = 0;
    for (auto &p : paths) total += duTree(p, !summary, allFiles && !summary, human, true);
    if (grand) std::printf("%s\ttotal\n", human ? humanSize(total).c_str() : std::to_string((total + 1023) / 1024).c_str());
    return 0;
}

// ---- find -------------------------------------------------------------------------------------------------------

struct FindOptions {
    std::string name;
    bool ignoreCase = false;
    char type = 0;
    int maxDepth = -1, minDepth = 0;
    long long minSize = -1, maxSize = -1; // -size +N / -N (bytes, k, M)
    Args exec;                            // -exec CMD {} ;
    bool remove = false;                  // -delete
    bool print = true;
};

int findStatus = 0;

void findWalk(const std::string &path, const FindOptions &o, int depth) {
    auto st = statOf(path);
    if (!st) return;
    bool dir = S_ISDIR(st->st_mode);
    // -delete removes directories after their contents (depth first).
    if (dir && (o.maxDepth < 0 || depth < o.maxDepth)) {
        bool ok;
        auto names = listDir(path, ok);
        std::sort(names.begin(), names.end());
        auto visitSelf = [&]() {
            bool match = depth >= o.minDepth;
            if (!o.name.empty() && fnmatch(o.name.c_str(), baseName(path).c_str(), o.ignoreCase ? FNM_CASEFOLD : 0) != 0) match = false;
            if (o.type == 'f') match = false;
            if (o.minSize >= 0 || o.maxSize >= 0) match = false;
            return match;
        };
        bool self = visitSelf();
        auto act = [&]() {
            if (!self) return;
            if (!o.exec.empty()) {
                Args cmd;
                for (auto &x : o.exec) cmd.push_back(x == "{}" ? path : x);
                std::fflush(stdout);
                if (cc::run(cmd) != 0) findStatus = 1;
            } else if (o.remove) {
                if (rmdir(path.c_str()) != 0 && path != ".") findStatus = warnErrno(path);
            } else if (o.print) {
                std::puts(path.c_str());
            }
        };
        if (!o.remove) act();
        for (auto &name : names) {
            if (path == "/" && (name == "proc" || name == "dev")) continue;
            findWalk(join(path, name), o, depth + 1);
        }
        if (o.remove) act();
        return;
    }
    bool match = depth >= o.minDepth;
    if (!o.name.empty() && fnmatch(o.name.c_str(), baseName(path).c_str(), o.ignoreCase ? FNM_CASEFOLD : 0) != 0) match = false;
    if (o.type == 'f' && dir) match = false;
    if (o.type == 'd' && !dir) match = false;
    if (o.minSize >= 0 && st->st_size <= o.minSize) match = false;
    if (o.maxSize >= 0 && st->st_size >= o.maxSize) match = false;
    if (!match) return;
    if (!o.exec.empty()) {
        Args cmd;
        for (auto &x : o.exec) cmd.push_back(x == "{}" ? path : x);
        std::fflush(stdout);
        if (cc::run(cmd) != 0) findStatus = 1;
    } else if (o.remove) {
        if (unlink(path.c_str()) != 0) findStatus = warnErrno(path);
    } else if (o.print) {
        std::puts(path.c_str());
    }
}

int cmdFind(Args &a) {
    size_t i = 1;
    std::vector<std::string> roots;
    while (i < a.size() && (a[i].empty() || a[i][0] != '-')) roots.push_back(a[i++]);
    if (roots.empty()) roots.push_back(".");
    FindOptions o;
    for (; i < a.size(); i++) {
        auto &opt = a[i];
        bool hasArg = i + 1 < a.size();
        if ((opt == "-name" || opt == "-iname") && hasArg) {
            o.name = a[++i];
            o.ignoreCase = opt == "-iname";
        } else if (opt == "-type" && hasArg) {
            o.type = a[++i][0];
        } else if (opt == "-maxdepth" && hasArg) {
            o.maxDepth = std::atoi(a[++i].c_str());
        } else if (opt == "-mindepth" && hasArg) {
            o.minDepth = std::atoi(a[++i].c_str());
        } else if (opt == "-size" && hasArg) {
            auto v = a[++i];
            char sign = v[0] == '+' || v[0] == '-' ? v[0] : 0;
            char *end;
            long long n = std::strtoll(v.c_str() + (sign ? 1 : 0), &end, 10);
            if (*end == 'k') n *= 1024;
            else if (*end == 'M') n *= 1024 * 1024;
            else if (*end == 'c') {
            } else n *= 512; // blocks, as in POSIX
            if (sign == '+') o.minSize = n;
            else if (sign == '-') o.maxSize = n;
            else {
                o.minSize = n - 1;
                o.maxSize = n + 1;
            }
        } else if (opt == "-exec") {
            for (i++; i < a.size() && a[i] != ";" && a[i] != "+"; i++) o.exec.push_back(a[i]);
            if (o.exec.empty()) die("-exec needs a command");
        } else if (opt == "-delete") {
            o.remove = true;
        } else if (opt == "-print") {
            o.print = true;
        } else {
            die("unknown option " + opt + "\nusage: find [PATH...] [-name|-iname PATTERN] [-type f|d] [-maxdepth N] [-mindepth N]"
                                          " [-size [+-]N[ckM]] [-exec CMD {} ;] [-delete]");
        }
    }
    for (auto &r : roots) findWalk(r, o, 0);
    return findStatus;
}

// ---- stat / hexdump ---------------------------------------------------------------------------------------------

// stat [-c FORMAT] FILE...: as GNU stat (FORMAT: %n %s %F %a %A %U %G %u %g %h %Y %y).
int cmdStat(Args &a) {
    std::string format;
    Args files;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "--help") {
            std::puts("usage: stat [-c FORMAT] FILE...   (%n name, %s size, %F type, %a mode, %A mode text, %y/%Y modified)");
            return 0;
        }
        if ((a[i] == "-c" || a[i] == "--format") && i + 1 < a.size()) format = a[++i] + "\n";
        else if (a[i].rfind("--format=", 0) == 0) format = a[i].substr(9) + "\n";
        else if (a[i].rfind("--printf=", 0) == 0) format = a[i].substr(9);
        else files.push_back(a[i]);
    }
    if (files.empty()) die("missing operand");
    int status = 0;
    for (auto &file : files) {
        auto st = statOf(file);
        if (!st) {
            status |= warnErrno(file);
            continue;
        }
        char when[64];
        time_t t = st->st_mtime;
        std::strftime(when, sizeof(when), "%Y-%m-%d %H:%M:%S.000000000 %z", std::localtime(&t));
        const char *kind = S_ISDIR(st->st_mode) ? "directory" : S_ISCHR(st->st_mode) ? "character special file"
                         : st->st_size == 0 ? "regular empty file" : "regular file";
        auto mode = modeString(*st);
        unsigned perms = st->st_mode & 07777;
        if (!format.empty()) {
            std::string out;
            for (size_t k = 0; k < format.size(); k++) {
                if (format[k] != '%' || k + 1 >= format.size()) {
                    if (format[k] == '\\' && k + 1 < format.size() && format[k + 1] == 'n') {
                        out += '\n';
                        k++;
                    } else {
                        out += format[k];
                    }
                    continue;
                }
                char f = format[++k];
                char buf[64];
                switch (f) {
                    case 'n': out += file; break;
                    case 's': out += std::to_string(st->st_size); break;
                    case 'F': out += kind; break;
                    case 'a': std::snprintf(buf, sizeof(buf), "%o", perms); out += buf; break;
                    case 'A': out += mode; break;
                    case 'U': out += userName(st->st_uid); break;
                    case 'G': out += groupName(st->st_gid); break;
                    case 'u': out += std::to_string(st->st_uid); break;
                    case 'g': out += std::to_string(st->st_gid); break;
                    case 'h': out += std::to_string(st->st_nlink ? st->st_nlink : 1); break;
                    case 'Y': out += std::to_string(static_cast<long long>(st->st_mtime)); break;
                    case 'y': out += when; break;
                    case '%': out += '%'; break;
                    default: out += '?';
                }
            }
            std::fputs(out.c_str(), stdout);
            continue;
        }
        std::printf("  File: %s\n", file.c_str());
        std::printf("  Size: %-15lld Blocks: %-10lld IO Block: 4096   %s\n", static_cast<long long>(st->st_size),
                    static_cast<long long>((st->st_size + 511) / 512), kind);
        std::printf("Device: 0,0\tInode: %-11llu Links: %llu\n", static_cast<unsigned long long>(st->st_ino),
                    static_cast<unsigned long long>(st->st_nlink ? st->st_nlink : 1));
        std::printf("Access: (%04o/%s)  Uid: (%5u/%8s)   Gid: (%5u/%8s)\n", perms, mode.c_str(), static_cast<unsigned>(st->st_uid),
                    userName(st->st_uid).c_str(), static_cast<unsigned>(st->st_gid), groupName(st->st_gid).c_str());
        std::printf("Access: %s\nModify: %s\nChange: %s\n Birth: -\n", when, when, when);
    }
    return status;
}

int cmdHexdump(Args &a) {
    return forEachInput(a, 1, [](FILE *f, const std::string &) {
        unsigned char buf[16];
        size_t n, offset = 0;
        while ((n = std::fread(buf, 1, 16, f)) > 0) {
            std::printf("%08zx  ", offset);
            for (size_t i = 0; i < 16; i++) {
                if (i < n) std::printf("%02x ", buf[i]);
                else std::printf("   ");
                if (i == 7) std::putchar(' ');
            }
            std::printf(" |");
            for (size_t i = 0; i < n; i++) std::putchar(buf[i] >= 32 && buf[i] < 127 ? buf[i] : '.');
            std::printf("|\n");
            offset += n;
        }
        return 0;
    });
}

// ---- test / [ ---------------------------------------------------------------------------------------------------

int testExpr(const std::vector<std::string> &e) {
    if (e.empty()) return 1;
    if (e.size() == 1) return e[0].empty() ? 1 : 0;
    if (e[0] == "!") return testExpr({e.begin() + 1, e.end()}) == 0 ? 1 : 0;
    if (e.size() > 3 && (e[3] == "-a" || e[3] == "-o")) {
        int left = testExpr({e.begin(), e.begin() + 3});
        int right = testExpr({e.begin() + 4, e.end()});
        return e[3] == "-a" ? (left || right) : (left && right);
    }
    if (e.size() == 2) {
        auto &op = e[0];
        auto st = statOf(e[1]);
        if (op == "-n") return e[1].empty() ? 1 : 0;
        if (op == "-z") return e[1].empty() ? 0 : 1;
        if (op == "-e" || op == "-x" || op == "-r" || op == "-w") return st ? 0 : 1;
        if (op == "-f") return st && S_ISREG(st->st_mode) ? 0 : 1;
        if (op == "-d") return st && S_ISDIR(st->st_mode) ? 0 : 1;
        if (op == "-s") return st && st->st_size > 0 ? 0 : 1;
        return 2;
    }
    auto &x = e[0], &op = e[1], &y = e[2];
    if (op == "=" || op == "==") return x == y ? 0 : 1;
    if (op == "!=") return x != y ? 0 : 1;
    long a = std::atol(x.c_str()), b = std::atol(y.c_str());
    bool r;
    if (op == "-eq") r = a == b;
    else if (op == "-ne") r = a != b;
    else if (op == "-lt") r = a < b;
    else if (op == "-le") r = a <= b;
    else if (op == "-gt") r = a > b;
    else if (op == "-ge") r = a >= b;
    else return 2;
    return r ? 0 : 1;
}

int cmdTest(Args &a) {
    std::vector<std::string> e(a.begin() + 1, a.end());
    if (prog == "[") {
        if (e.empty() || e.back() != "]") die("missing ]");
        e.pop_back();
    }
    return testExpr(e);
}

// ---- power, label, hostname, whoami, redstone -------------------------------------------------------------------

int cmdLabel(Args &a) {
    if (a.size() > 2 && a[1] == "set") return cc::setLabel(a[2]) ? 0 : 1;
    if (a.size() > 1 && a[1] == "clear") return cc::setLabel("") ? 0 : 1;
    auto label = cc::systemInfo()["label"];
    if (label.isString()) std::printf("Computer label is \"%s\"\n", label.asString().c_str());
    else std::puts("No label set. Use: label set NAME");
    return 0;
}

int redstoneSide(const std::string &name) {
    int side = cc::side(name);
    if (side < 0) die("unknown side " + name + " (top bottom left right front back)");
    return side;
}

int redstoneLevel(const std::string &v) {
    if (v == "on" || v == "true") return 15;
    if (v == "off" || v == "false") return 0;
    return std::max(0, std::min(15, std::atoi(v.c_str())));
}

// redstone [probe] | get SIDE [input|output] | set SIDE LEVEL|on|off | pulse SIDE [COUNT] [PERIOD] | wait SIDE [on|off|change]
int cmdRedstone(Args &a) {
    static const char *const names[] = {"bottom", "top", "back", "front", "right", "left"};
    auto c = a.size() > 1 ? a[1] : "probe";
    if (c == "get" && a.size() >= 3) {
        // Prints the level, and returns it as the exit status too (0-15): "redstone get left; echo $?".
        int side = redstoneSide(a[2]);
        bool output = a.size() > 3 && a[3] == "output";
        int level = output ? cc_rs_get_output(side) : cc_rs_get(side);
        std::printf("%d\n", level);
        return level;
    }
    if (c == "set" && a.size() >= 4) {
        return cc_rs_set(redstoneSide(a[2]), redstoneLevel(a[3])) ? 1 : 0;
    }
    if (c == "pulse" && a.size() >= 3) {
        int side = redstoneSide(a[2]);
        int count = a.size() > 3 ? std::max(1, std::atoi(a[3].c_str())) : 1;
        double period = a.size() > 4 ? std::atof(a[4].c_str()) : 0.5;
        for (int i = 0; i < count; i++) {
            cc_rs_set(side, 15);
            cc::sleep(period / 2);
            cc_rs_set(side, 0);
            cc::sleep(period / 2);
        }
        return 0;
    }
    if (c == "wait" && a.size() >= 3) {
        // Block until the input on SIDE turns on (default), off, or changes.
        int side = redstoneSide(a[2]);
        auto until = a.size() > 3 ? a[3] : "on";
        cc::wantEvents();
        int start = cc_rs_get(side);
        auto done = [&](int level) {
            return until == "off" ? level == 0 : until == "change" ? level != start : level > 0;
        };
        while (!done(cc_rs_get(side))) cc::pullEvent("redstone");
        std::printf("%d\n", cc_rs_get(side));
        return 0;
    }
    if (c == "probe" || c == "status") {
        std::printf("side    input output\n");
        for (int i = 0; i < 6; i++) std::printf("%-7s %5d %6d\n", names[i], cc_rs_get(i), cc_rs_get_output(i));
        if (a.size() < 2) {
            std::printf("\nUsage: redstone get SIDE [output] | set SIDE LEVEL|on|off | pulse SIDE [COUNT] [PERIOD]\n"
                        "       redstone wait SIDE [on|off|change] | probe\n");
        }
        return 0;
    }
    std::fprintf(stderr, "usage: redstone [probe] | get SIDE [input|output] | set SIDE LEVEL|on|off\n"
                         "       redstone pulse SIDE [COUNT] [PERIOD] | wait SIDE [on|off|change]\n");
    return 2;
}

// ---- ldd / wasm-info: inspect WebAssembly modules ---------------------------------------------------------------

class WasmReader {
public:
    WasmReader(const std::string &data, size_t pos, size_t end) : d_(data), p_(pos), end_(end) {}
    bool more() const { return p_ < end_; }
    size_t pos() const { return p_; }
    void seek(size_t p) { p_ = p; }
    unsigned char byte() { return p_ < end_ ? static_cast<unsigned char>(d_[p_++]) : 0; }
    unsigned long leb() {
        unsigned long r = 0;
        int shift = 0;
        while (p_ < end_) {
            unsigned char b = byte();
            r |= static_cast<unsigned long>(b & 0x7F) << shift;
            if (!(b & 0x80)) break;
            shift += 7;
        }
        return r;
    }
    std::string name() {
        auto n = leb();
        auto s = d_.substr(p_, std::min<size_t>(n, end_ - p_));
        p_ += n;
        return s;
    }

private:
    const std::string &d_;
    size_t p_, end_;
};

int cmdLdd(Args &a) {
    bool exports = prog == "wasm-info";
    static const char *const kinds[] = {"func", "table", "memory", "global", "tag"};
    if (a.size() < 2) die("usage: ldd FILE.wasm");
    for (size_t i = 1; i < a.size(); i++) {
        auto data = readAll(a[i]);
        if (!data) return warnErrno(a[i]);
        if (data->size() < 8 || data->compare(0, 4, std::string("\0asm", 4)) != 0) {
            std::printf("%s: not a WebAssembly module\n", a[i].c_str());
            continue;
        }
        if (a.size() > 2) std::printf("%s:\n", a[i].c_str());
        bool missing = false;
        WasmReader r(*data, 8, data->size());
        while (r.more()) {
            unsigned char id = r.byte();
            size_t len = r.leb();
            size_t next = r.pos() + len;
            if (id == 2 || (id == 7 && exports)) {
                auto n = r.leb();
                std::printf(id == 2 ? "  imports (%lu):\n" : "  exports (%lu):\n", n);
                for (unsigned long k = 0; k < n && r.pos() < next; k++) {
                    std::string module = id == 2 ? r.name() : "";
                    auto name = r.name();
                    unsigned char kind = r.byte();
                    const char *kindName = kind < 5 ? kinds[kind] : "?";
                    if (id == 7) {
                        r.leb();
                        std::printf("    %s (%s)\n", name.c_str(), kindName);
                        continue;
                    }
                    bool known = module == "wasi_snapshot_preview1" || module == "ccbaremetal";
                    std::printf("    %s.%s (%s)%s\n", module.c_str(), name.c_str(), kindName, known ? "" : "  <- not provided by this system");
                    missing |= !known;
                    if (kind == 0) {
                        r.leb();
                    } else if (kind == 1) {
                        r.byte();
                        if (r.byte() & 1) r.leb();
                        r.leb();
                    } else if (kind == 2) {
                        unsigned char flags = r.byte();
                        r.leb();
                        if (flags & 1) r.leb();
                    } else if (kind == 3) {
                        r.byte();
                        r.byte();
                    } else if (kind == 4) {
                        r.byte();
                        r.leb();
                    }
                }
            }
            r.seek(next);
        }
        if (missing) std::printf("  warning: this program needs imports that are not available\n");
    }
    return 0;
}

// ---- more / man / lsperiph --------------------------------------------------------------------------------------

int pager(FILE *in) {
    int tty = open("/dev/tty", O_RDONLY);
    int rows = cc::tty::size().rows, shown = 0;
    std::string line;
    int c;
    bool quit = false;
    while (!quit && (c = std::fgetc(in)) != EOF) {
        std::putchar(c);
        if (c != '\n') continue;
        if (++shown >= rows - 1 && isatty(1) && tty >= 0) {
            std::fputs("\x1b[7m-- more -- (space: page, enter: line, q: quit)\x1b[0m", stdout);
            std::fflush(stdout);
            char key = 'q';
            {
                cc::tty::Mode raw(CC_TTY_RAW | CC_TTY_NOECHO);
                if (read(tty, &key, 1) != 1) key = 'q';
            }
            std::fputs("\r\x1b[K", stdout);
            quit = key == 'q';
            shown = key == '\r' || key == '\n' ? rows - 2 : 0;
        }
    }
    if (tty >= 0) close(tty);
    return 0;
}

int cmdMore(Args &a) {
    Args files;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "--help" || a[i] == "-h") {
            std::printf("usage: %s [FILE...]   (space/PgDn next page, Enter next line, q quit)\n", prog.c_str());
            return 0;
        }
        if (a[i] == "-" || !isOption(a[i])) files.push_back(a[i]);
    }
    if (files.empty()) return pager(stdin);
    int status = 0;
    for (auto &file : files) {
        if (file == "-") {
            pager(stdin);
            continue;
        }
        FILE *f = std::fopen(file.c_str(), "r");
        if (!f) {
            status |= warnErrno(file);
            continue;
        }
        pager(f);
        std::fclose(f);
    }
    return status;
}

int cmdMan(Args &a) {
    std::string page = a.size() > 1 ? a[1] : "intro";
    for (auto *dir : {"/usr/share/man/", "/usr/local/share/man/"}) {
        std::string path = dir + page + ".txt";
        if (statOf(path)) {
            Args args = {"more", path};
            return cmdMore(args);
        }
    }
    std::fprintf(stderr, "man: no manual entry for %s (see 'ls /usr/share/man')\n", page.c_str());
    return 1;
}

int cmdLsperiph(Args &) {
    std::printf("%-20s %s\n", "NAME", "TYPES");
    for (auto &p : cc::Peripheral::list()) {
        std::printf("%-20s ", p.name.c_str());
        for (auto &t : p.types) std::printf("%s ", t.c_str());
        std::putchar('\n');
    }
    return 0;
}

// ---- dispatch ---------------------------------------------------------------------------------------------------

using Applet = int (*)(Args &);

const std::map<std::string, Applet> &applets() {
    static const std::map<std::string, Applet> table = {
        {"cat", cmdCat},
        {"echo", cmdEcho},
        {"ls", cmdLs},
        {"cp", cmdCp},
        {"mv", cmdMv},
        {"rm", cmdRm},
        {"mkdir", cmdMkdir},
        {"rmdir", cmdRmdir},
        {"touch", cmdTouch},
        {"pwd", cmdPwd},
        {"head", cmdHead},
        {"tail", cmdTail},
        {"wc", cmdWc},
        {"grep", cmdGrep},
        {"sort", cmdSort},
        {"uniq", cmdUniq},
        {"printf", cmdPrintf},
        {"tee", cmdTee},
        {"yes", cmdYes},
        {"seq", cmdSeq},
        {"basename", cmdBasename},
        {"dirname", cmdDirname},
        {"rev", cmdRev},
        {"tr", cmdTr},
        {"cut", cmdCut},
        {"env", cmdEnv},
        {"sleep", cmdSleep},
        {"true", [](Args &) { return 0; }},
        {"false", [](Args &) { return 1; }},
        {"clear", cmdClear},
        {"date", cmdDate},
        {"uname", cmdUname},
        {"ps", cmdPs},
        {"kill", cmdKill},
        {"pkill", cmdPgrep},
        {"pgrep", cmdPgrep},
        {"killall", cmdPgrep},
        {"uptime", cmdUptime},
        {"free", cmdFree},
        {"df", cmdDf},
        {"du", cmdDu},
        {"find", cmdFind},
        {"stat", cmdStat},
        {"hexdump", cmdHexdump},
        {"test", cmdTest},
        {"[", cmdTest},
        {"reboot", cmdShutdown},
        {"shutdown", cmdShutdown},
        {"poweroff", cmdShutdown},
        {"halt", cmdShutdown},
        {"label", cmdLabel},
        {"hostname", cmdHostname},
        {"whoami", [](Args &) {
             // the effective user, as GNU whoami (not $USER: "sudo whoami" says root)
             std::puts(userName(geteuid()).c_str());
             return 0;
         }},
        {"chown", cmdChown},
        {"chgrp", cmdChown},
        {"redstone", cmdRedstone},
        {"ldd", cmdLdd},
        {"wasm-info", cmdLdd},
        {"more", cmdMore},
        {"less", cmdMore},
        {"man", cmdMan},
        {"lsperiph", cmdLsperiph},
        {"ln", [](Args &a) {
             if (a.size() > 1 && a[1] == "--help") {
                 std::puts("usage: ln [-s] TARGET LINK   (not available: the file system has no links; use cp or a shell alias)");
                 return 0;
             }
             std::fputs("ln: links are not supported by this file system; use cp, or alias/function in sh\n", stderr);
             return 1;
         }},
    };
    return table;
}

} // namespace

// Usage of the commands that do not parse --help themselves (it would be taken as a file or ignored).
const char *usageOf(const std::string &name) {
    static const std::map<std::string, const char *> table = {
        {"basename", "basename NAME [SUFFIX]"},
        {"dirname", "dirname NAME"},
        {"date", "date [+FORMAT]   (strftime format, e.g. date +%H:%M)"},
        {"find", "find [PATH...] [-name|-iname PATTERN] [-type f|d] [-maxdepth N] [-mindepth N] [-size [+-]N[ckM]] [-print] [-delete] [-exec CMD {} ;]"},
        {"grep", "grep [-ivcnloqhHrFEG] [-e PATTERN] PATTERN [FILE...]"},
        {"hexdump", "hexdump [FILE...]   (hex and ASCII, as hexdump -C)"},
        {"hostname", "hostname   (the computer label, else computer-ID)"},
        {"label", "label [get] | set NAME | clear"},
        {"ldd", "ldd FILE.wasm   (imports, exports and memory of a WebAssembly module)"},
        {"wasm-info", "wasm-info FILE.wasm   (imports, exports and memory of a WebAssembly module)"},
        {"man", "man [PAGE]   (pages: ls /usr/share/man)"},
        {"ps", "ps   (all processes: PID, parent, group, state, memory, command)"},
        {"pwd", "pwd"},
        {"kill", "kill [-SIGNAL] PID..."},
        {"sleep", "sleep SECONDS"},
        {"whoami", "whoami"},
        {"lsperiph", "lsperiph   (attached peripherals and their types)"},
        {"touch", "touch FILE..."},
        {"rmdir", "rmdir DIR..."},
        {"clear", "clear [-x]   (-x keeps the scrollback)"},
    };
    auto it = table.find(name);
    return it == table.end() ? nullptr : it->second;
}

int main(int argc, char **argv) {
    Args args(argv, argv + argc);
    prog = baseName(args[0]);
    if (prog == "coreutils") {
        if (args.size() < 2 || args[1] == "--help" || args[1] == "-h") {
            std::puts("coreutils: multi-call binary. Commands:");
            int col = 0;
            for (auto &[name, fn] : applets()) {
                col += std::printf("%s ", name.c_str());
                if (col > 70) {
                    std::putchar('\n');
                    col = 0;
                }
            }
            std::putchar('\n');
            return 0;
        }
        args.erase(args.begin());
        prog = baseName(args[0]);
    }
    if (args.size() > 1 && args[1] == "--help") {
        if (auto *u = usageOf(prog)) {
            std::printf("usage: %s\n", u);
            return 0;
        }
    }
    auto it = applets().find(prog);
    if (it == applets().end()) {
        std::fprintf(stderr, "coreutils: unknown command %s\n", prog.c_str());
        return 127;
    }
    int r = it->second(args);
    std::fflush(stdout);
    return r;
}
