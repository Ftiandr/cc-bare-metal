// ssh, sshd, ssh-keygen, ssh-copy-id - log in to another computer through CC modems (wireless, ender or wired).
//
//   ssh [-p PORT] [-l USER] [-i KEY] [-o OPT=VAL] [USER@]HOST [COMMAND]    HOST: the computer's hostname
//   sshd [-p PORT] [-f CONFIG] [-t] [-D]      the server (root), configured by /etc/ssh/sshd_config
//   ssh-keygen, ssh-copy-id                   key pairs for logging in without a password
//
// Transport: a connection of the kernel's network (cc::net, man net) to HOST:PORT - the computer's hostname and a
// port, as TCP/IP. Two computers with one name (a copied computer) are noticed by the network: the client refuses.
// Protocol (ccbaremetal-ssh-2) on that stream, frames of a 4-byte big-endian length and the payload:
//   client:  {t="hello", v, epk, cn}                       ephemeral X25519 key, nonce (JSON)
//   server:  {t="hello", name, epk, sn, hk, sig, methods, banner}   or {t="refused", msg}
//            hk: the host's Ed25519 key, sig: its signature of the transcript (checked against known_hosts)
//   both:    keys = HKDF-SHA256(X25519 secret, transcript), one per direction; then only ChaCha20-Poly1305
//            sealed JSON frames with a counter as the nonce (a reordered, replayed or forged frame ends the session).
// Inside: auth (publickey: an Ed25519 signature of the transcript and user name, checked against the user's
// authorized_keys; or password), session (terminal size, command), in/out/err/eof (bytes), size, ping/pong, exit,
// bye. Passwords never travel unencrypted.
#include <baremetal.hpp>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <crypt.h>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <fnmatch.h>
#include <grp.h>
#include <map>
#include <pwd.h>
#include <shadow.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

using Args = std::vector<std::string>;
using Bytes = std::vector<uint8_t>;

constexpr const char *VERSION = "ccbaremetal-ssh-2";
constexpr size_t CHUNK = 2048;
constexpr size_t MAX_FRAME = 1 << 20;

std::string prog;

[[noreturn]] void die(const std::string &msg, int code = 255) {
    std::fprintf(stderr, "%s: %s\r\n", prog.c_str(), msg.c_str());
    std::exit(code);
}

// ---- Encoding ---------------------------------------------------------------------------------------------------

const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64(const uint8_t *data, size_t n) {
    std::string out;
    for (size_t i = 0; i < n; i += 3) {
        uint32_t v = data[i] << 16 | (i + 1 < n ? data[i + 1] << 8 : 0) | (i + 2 < n ? data[i + 2] : 0);
        out += B64[v >> 18 & 63];
        out += B64[v >> 12 & 63];
        out += i + 1 < n ? B64[v >> 6 & 63] : '=';
        out += i + 2 < n ? B64[v & 63] : '=';
    }
    return out;
}

std::string base64(const Bytes &b) {
    return base64(b.data(), b.size());
}

Bytes unbase64(const std::string &s) {
    Bytes out;
    uint32_t v = 0;
    int bits = 0;
    for (char c : s) {
        auto p = std::strchr(B64, c);
        if (c == '=' || !p || !c) continue;
        v = v << 6 | static_cast<uint32_t>(p - B64);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<uint8_t>(v >> bits));
        }
    }
    return out;
}

Bytes randomBytes(size_t n) {
    Bytes out(n);
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0 || read(fd, out.data(), n) != static_cast<ssize_t>(n)) die("cannot read /dev/urandom");
    close(fd);
    return out;
}

Bytes sha256(const Bytes &data) {
    Bytes out(32);
    cc_sha256(data.data(), static_cast<int32_t>(data.size()), out.data());
    return out;
}

Bytes hmac(const Bytes &key, const Bytes &data) {
    Bytes out(32);
    cc_hmac_sha256(key.data(), static_cast<int32_t>(key.size()), data.data(), static_cast<int32_t>(data.size()), out.data());
    return out;
}

// HKDF-SHA256 (RFC 5869) for one 32-byte key.
Bytes hkdf(const Bytes &secret, const Bytes &salt, const std::string &info) {
    auto prk = hmac(salt, secret);
    Bytes in(info.begin(), info.end());
    in.push_back(1);
    return hmac(prk, in);
}

std::string fingerprint(const Bytes &pub) {
    auto h = base64(sha256(pub));
    while (!h.empty() && h.back() == '=') h.pop_back();
    return "SHA256:" + h;
}

void append(Bytes &out, const Bytes &b) {
    out.insert(out.end(), b.begin(), b.end());
}

Bytes transcript(const Bytes &cepk, const Bytes &sepk, const Bytes &cn, const Bytes &sn, const Bytes &hk) {
    Bytes t(VERSION, VERSION + std::strlen(VERSION));
    for (auto *b : {&cepk, &sepk, &cn, &sn, &hk}) append(t, *b);
    return t;
}

// ---- An encrypted channel ---------------------------------------------------------------------------------------

long nowMs() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return static_cast<long>(t.tv_sec) * 1000 + t.tv_nsec / 1000000;
}

// Frames on a network connection: plain JSON until the keys are agreed, then sealed JSON.
struct Channel {
    int fd = -1;
    Bytes sendKey, receiveKey;
    uint64_t sendCount = 0, receiveCount = 0;
    std::string buffer; // bytes read, not yet a whole frame
    bool closed = false;
    int error = 0; // errno of the connection's end (0: closed by the other side)

    static Bytes nonce(uint64_t n) {
        Bytes out(12, 0);
        for (int i = 0; i < 8; i++) out[4 + i] = static_cast<uint8_t>(n >> (8 * i));
        return out;
    }

    void frame(const void *data, size_t n) {
        uint8_t head[4] = {static_cast<uint8_t>(n >> 24), static_cast<uint8_t>(n >> 16), static_cast<uint8_t>(n >> 8), static_cast<uint8_t>(n)};
        if (closed || !cc::net::sendAll(fd, head, 4) || !cc::net::sendAll(fd, data, n)) {
            if (!closed) error = errno;
            closed = true;
        }
    }

    void raw(const cc::Json &msg) {
        auto text = msg.dump();
        frame(text.data(), text.size());
    }

    void seal(const cc::Json &inner) {
        auto text = inner.dump();
        Bytes out(text.size() + 16);
        auto n = nonce(sendCount++);
        cc_aead_seal(sendKey.data(), n.data(), VERSION, static_cast<int32_t>(std::strlen(VERSION)), text.data(),
                     static_cast<int32_t>(text.size()), out.data());
        frame(out.data(), out.size());
    }

    // Read what is waiting (one read; blocks if nothing is). False when the connection has ended.
    bool fill() {
        if (closed) return false;
        char buf[8192];
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) {
            error = n < 0 ? errno : 0;
            closed = true;
            return false;
        }
        buffer.append(buf, static_cast<size_t>(n));
        return true;
    }

    // The next whole frame already read, if any.
    std::optional<std::string> next() {
        if (buffer.size() < 4) return std::nullopt;
        auto *b = reinterpret_cast<const uint8_t *>(buffer.data());
        size_t n = static_cast<size_t>(b[0]) << 24 | b[1] << 16 | b[2] << 8 | b[3];
        if (n > MAX_FRAME) {
            closed = true;
            error = EPROTO;
            buffer.clear();
            return std::nullopt;
        }
        if (buffer.size() < 4 + n) return std::nullopt;
        auto out = buffer.substr(4, n);
        buffer.erase(0, 4 + n);
        return out;
    }

    // A sealed frame's message, or nullopt (forged, replayed, out of order: the session cannot go on).
    std::optional<cc::Json> open(const std::string &sealed) {
        if (sealed.size() < 16) return std::nullopt;
        std::string plain(sealed.size() - 16, '\0');
        auto nn = nonce(receiveCount);
        if (cc_aead_open(receiveKey.data(), nn.data(), VERSION, static_cast<int32_t>(std::strlen(VERSION)), sealed.data(),
                         static_cast<int32_t>(sealed.size()), plain.data()) != 0) {
            return std::nullopt;
        }
        receiveCount++;
        return cc::Json::parse(plain);
    }

    // Wait for the next frame (plain or sealed) up to `timeoutMs`. nullopt: timeout or the connection ended.
    std::optional<cc::Json> await(bool sealed, int timeoutMs) {
        long deadline = nowMs() + timeoutMs;
        while (true) {
            if (auto f = next()) {
                if (!sealed) return cc::Json::parse(*f);
                auto inner = open(*f);
                if (!inner) {
                    closed = true;
                    error = EBADMSG;
                }
                return inner;
            }
            if (closed) return std::nullopt;
            long left = deadline - nowMs();
            if (left <= 0) return std::nullopt;
            auto w = cc::waitAny({fd}, static_cast<int>(left));
            if (w.event && w.event->name == "terminate") std::exit(130);
            if (w.fd == fd) fill();
        }
    }
};

std::string lower(std::string s) {
    for (auto &c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

bool fnmatchLike(const std::string &pattern, const std::string &name) {
    return fnmatch(pattern.c_str(), name.c_str(), 0) == 0;
}

std::string hostName() {
    return cc::hostname();
}

// ---- Keys, files, logging ---------------------------------------------------------------------------------------

std::string homeOf(uid_t uid) {
    if (auto *pw = getpwuid(uid)) return pw->pw_dir;
    const char *h = std::getenv("HOME");
    return h ? h : "/";
}

std::string homeDir() {
    return homeOf(getuid());
}

std::string expandHome(const std::string &path) {
    if (path == "~") return homeDir();
    if (path.rfind("~/", 0) == 0) return homeDir() + path.substr(1);
    return path;
}

struct KeyPair {
    Bytes seed, pub;
    std::string comment;
};

// Private key file: "ed25519 SEED PUBLIC COMMENT" (mode 0600); public key file: "ssh-ed25519 PUBLIC COMMENT".
std::optional<KeyPair> loadKey(const std::string &path) {
    FILE *f = std::fopen(path.c_str(), "r");
    if (!f) return std::nullopt;
    char seed[128], pub[128], comment[256] = "";
    int n = std::fscanf(f, "ed25519 %127s %127s %255[^\n]", seed, pub, comment);
    std::fclose(f);
    if (n < 2) return std::nullopt;
    KeyPair k{unbase64(seed), unbase64(pub), comment};
    if (k.seed.size() != 32 || k.pub.size() != 32) return std::nullopt;
    return k;
}

bool saveKey(const std::string &path, const KeyPair &k) {
    mode_t old = umask(077);
    FILE *f = std::fopen(path.c_str(), "w");
    if (f) {
        std::fprintf(f, "ed25519 %s %s %s\n", base64(k.seed).c_str(), base64(k.pub).c_str(), k.comment.c_str());
        std::fclose(f);
    }
    umask(022);
    FILE *p = f ? std::fopen((path + ".pub").c_str(), "w") : nullptr;
    if (p) {
        std::fprintf(p, "ssh-ed25519 %s %s\n", base64(k.pub).c_str(), k.comment.c_str());
        std::fclose(p);
    }
    umask(old);
    return f && p;
}

KeyPair newKey(const std::string &comment) {
    KeyPair k{Bytes(32), Bytes(32), comment};
    cc_ed25519_keypair(k.seed.data(), k.pub.data());
    return k;
}

// What a client signs to prove it holds a key: bound to this session's transcript and the user name.
Bytes userAuthData(const Bytes &sessionDigest, const std::string &user, const Bytes &pub) {
    std::string tag = "ccbaremetal-ssh-userauth";
    Bytes d(tag.begin(), tag.end());
    append(d, sessionDigest);
    d.insert(d.end(), user.begin(), user.end());
    append(d, pub);
    return sha256(d);
}

std::string knownHostsName(const std::string &host, int port) {
    return port == 22 ? lower(host) : "[" + lower(host) + "]:" + std::to_string(port);
}

// ---- Client -----------------------------------------------------------------------------------------------------

// known_hosts, as OpenSSH's: "NAME ssh-ed25519 KEY" per line ([NAME]:PORT for other ports).
// 0 known and equal, 1 unknown, 2 CHANGED.
int checkKnown(const std::string &entry, const Bytes &key) {
    FILE *f = std::fopen((homeDir() + "/.ssh/known_hosts").c_str(), "r");
    if (!f) return 1;
    char line[512];
    int result = 1;
    while (std::fgets(line, sizeof(line), f)) {
        char name[128], type[32], b64[128];
        if (std::sscanf(line, "%127s %31s %127s", name, type, b64) != 3 || lower(name) != entry) continue;
        result = unbase64(b64) == key ? 0 : 2;
        break;
    }
    std::fclose(f);
    return result;
}

void remember(const std::string &entry, const Bytes &key) {
    auto dir = homeDir() + "/.ssh";
    mkdir(dir.c_str(), 0700);
    if (FILE *f = std::fopen((dir + "/known_hosts").c_str(), "a")) {
        std::fprintf(f, "%s ssh-ed25519 %s\n", entry.c_str(), base64(key).c_str());
        std::fclose(f);
    }
}

std::optional<std::string> readTtyLine(bool echo) {
    int fd = open("/dev/tty", O_RDWR);
    if (fd < 0) fd = 0;
    cc::tty::Mode m(echo ? 0 : CC_TTY_NOECHO);
    std::string line;
    char c;
    bool any = false;
    while (read(fd, &c, 1) == 1) {
        any = true;
        if (c == '\n' || c == '\r') break;
        line += c;
    }
    if (fd != 0) close(fd);
    if (!echo) std::fputs("\n", stderr);
    if (!any) return std::nullopt;
    return line;
}

struct ClientOptions {
    std::string user, host, command;
    int port = 22;
    std::vector<std::string> identities;
    bool password = true, publickey = true;
    std::string strict = "ask"; // yes | ask | accept-new | no
    bool quiet = false;
    int connectTimeout = 10; // seconds
    int tty = 0; // -t: 1, -T: -1, else a terminal only for an interactive login (as OpenSSH)
};

// ~/.ssh/config, the common part of OpenSSH's: Host (patterns with *), HostName, User, Port, IdentityFile,
// PasswordAuthentication, PubkeyAuthentication, StrictHostKeyChecking. The first value found wins.
void readConfig(ClientOptions &o, bool userSet, bool portSet) {
    FILE *f = std::fopen((homeDir() + "/.ssh/config").c_str(), "r");
    if (!f) return;
    char buf[512];
    bool active = false, hostSet = false;
    auto alias = o.host;
    while (std::fgets(buf, sizeof(buf), f)) {
        std::string line = buf;
        auto hash = line.find('#');
        if (hash != std::string::npos) line.resize(hash);
        char key[64], value[256];
        if (std::sscanf(line.c_str(), " %63s %255[^\n]", key, value) != 2) continue;
        std::string k = lower(key), v = value;
        while (!v.empty() && std::isspace(static_cast<unsigned char>(v.back()))) v.pop_back();
        if (k == "host") {
            active = false;
            size_t start = 0;
            while (start < v.size()) {
                auto sp = v.find(' ', start);
                auto pat = v.substr(start, sp == std::string::npos ? std::string::npos : sp - start);
                if (!pat.empty() && fnmatchLike(pat, alias)) active = true;
                if (sp == std::string::npos) break;
                start = sp + 1;
            }
            continue;
        }
        if (!active) continue;
        if (k == "hostname" && !hostSet) {
            o.host = v;
            hostSet = true;
        } else if (k == "user" && !userSet) {
            o.user = v;
            userSet = true;
        } else if (k == "port" && !portSet) {
            o.port = std::atoi(v.c_str());
            portSet = true;
        } else if (k == "identityfile") {
            o.identities.push_back(expandHome(v));
        } else if (k == "passwordauthentication") {
            o.password = lower(v) == "yes";
        } else if (k == "pubkeyauthentication") {
            o.publickey = lower(v) == "yes";
        } else if (k == "stricthostkeychecking") {
            o.strict = lower(v) == "yes" ? "yes" : lower(v) == "no" || lower(v) == "off" ? "no" : lower(v) == "accept-new" ? "accept-new" : "ask";
        } else if (k == "connecttimeout") {
            o.connectTimeout = std::max(1, std::atoi(v.c_str()));
        }
    }
    std::fclose(f);
}

void applyOption(ClientOptions &o, const std::string &kv) {
    auto eq = kv.find('=');
    auto k = lower(kv.substr(0, eq));
    auto v = eq == std::string::npos ? "" : lower(kv.substr(eq + 1));
    if (k == "passwordauthentication") o.password = v == "yes";
    else if (k == "pubkeyauthentication") o.publickey = v == "yes";
    else if (k == "stricthostkeychecking") o.strict = v == "yes" ? "yes" : v == "no" ? "no" : v == "accept-new" ? "accept-new" : "ask";
    else if (k == "port") o.port = std::atoi(v.c_str());
    else if (k == "user") o.user = kv.substr(eq + 1);
    else if (k == "connecttimeout") o.connectTimeout = std::max(1, std::atoi(v.c_str()));
    else die("unsupported option: " + kv);
}

int session(Channel &ch, const std::string &hostLabel, bool pty);

int client(const Args &a) {
    ClientOptions o;
    bool userSet = false, portSet = false;
    std::string target;
    size_t i = 1;
    for (; i < a.size(); i++) {
        auto &arg = a[i];
        auto value = [&]() -> std::string {
            if (i + 1 >= a.size()) die("option requires an argument -- " + arg);
            return a[++i];
        };
        if (arg == "--help" || arg == "-h") {
            std::puts("usage: ssh [-p PORT] [-l USER] [-i KEYFILE] [-o OPTION=VALUE] [USER@]HOST [COMMAND]\n"
                      "  HOST is the computer's hostname; a modem is needed; ~. after Enter disconnects.\n"
                      "  Keys: ssh-keygen, ssh-copy-id; settings: ~/.ssh/config (man ssh)");
            return 0;
        }
        if (arg == "-V") {
            std::printf("%s (CC: Bare Metal)\n", VERSION);
            return 0;
        }
        if (!target.empty() || arg.empty() || arg[0] != '-') {
            if (target.empty()) target = arg;
            else o.command += (o.command.empty() ? "" : " ") + arg;
            continue;
        }
        if (arg == "-p") {
            o.port = std::atoi(value().c_str());
            portSet = true;
        } else if (arg.rfind("-p", 0) == 0) {
            o.port = std::atoi(arg.c_str() + 2);
            portSet = true;
        } else if (arg == "-l") {
            o.user = value();
            userSet = true;
        } else if (arg == "-i") {
            o.identities.push_back(expandHome(value()));
        } else if (arg == "-o") {
            applyOption(o, value());
        } else if (arg == "-q") {
            o.quiet = true;
        } else if (arg == "-t") {
            o.tty = 1;
        } else if (arg == "-T") {
            o.tty = -1;
        } else if (arg == "-4" || arg == "-6" || arg == "-C") {
            // accepted for compatibility: there is always a terminal and one kind of address
        } else {
            die("unknown option -- " + arg);
        }
    }
    if (target.empty()) die("usage: ssh [-p PORT] [USER@]HOST [COMMAND]");
    o.host = target;
    if (auto at = target.find('@'); at != std::string::npos) {
        o.user = target.substr(0, at);
        o.host = target.substr(at + 1);
        userSet = true;
    }
    readConfig(o, userSet, portSet);
    if (o.user.empty()) {
        if (auto *pw = getpwuid(getuid())) o.user = pw->pw_name;
    }
    if (o.port <= 0 || o.port > 65535) die("bad port");
    if (o.identities.empty()) o.identities.push_back(homeDir() + "/.ssh/id_ed25519");

    // a second computer answering as this name is noticed by the network: then the connection fails
    auto duplicate = [&]() {
        die("two computers answer as '" + o.host + "'. Give them different names (sudo hostname NEWNAME on one of them).");
    };

    Channel ch;
    ch.fd = cc::net::connect(o.host, o.port, o.connectTimeout * 1000);
    auto where = "connect to host " + o.host + " port " + std::to_string(o.port) + ": ";
    switch (-ch.fd) {
        case 0:
            break;
        case ECONNREFUSED:
            die(where + "Connection refused (is sshd running there on that port?)");
        case ETIMEDOUT:
            die(where + "Connection timed out (no computer called " + o.host +
                " answered: wrong name, out of range, or a wired modem without a cable to it?)");
        case ENETDOWN:
            die(where + "Network is down (no modem attached: a wireless, ender or wired modem is needed)");
        case EADDRINUSE:
            duplicate();
            break;
        default:
            if (ch.fd < 0) die(where + std::strerror(-ch.fd));
    }

    // key exchange
    Bytes epriv(32), epub(32);
    cc_x25519_keypair(epriv.data(), epub.data());
    auto cn = randomBytes(16);
    ch.raw(cc::Json::object({{"t", "hello"}, {"v", VERSION}, {"epk", base64(epub)}, {"cn", base64(cn)}}));
    auto answer = ch.await(false, 10000);
    if (!answer) {
        if (ch.error == EADDRINUSE) duplicate();
        die(where + (ch.closed ? "Connection closed by remote host" : "no answer from sshd"));
    }
    if ((*answer)["t"].asString("") == "refused") die("connection refused by " + o.host + ": " + (*answer)["msg"].asString(""));
    if ((*answer)["t"].asString("") != "hello") die("protocol error: not an sshd of CC: Bare Metal (" + std::string(VERSION) + ")");
    auto &h = *answer;
    auto sepk = unbase64(h["epk"].asString(""));
    auto sn = unbase64(h["sn"].asString(""));
    auto hk = unbase64(h["hk"].asString(""));
    auto sig = unbase64(h["sig"].asString(""));
    auto hostLabel = h["name"].asString(o.host);
    if (sepk.size() != 32 || hk.size() != 32 || sig.size() != 64) die("bad answer from " + o.host);
    auto digest = sha256(transcript(epub, sepk, cn, sn, hk));
    if (cc_ed25519_verify(hk.data(), digest.data(), 32, sig.data()) != 0) die("host key verification failed: bad signature");
    auto entry = knownHostsName(hostLabel, o.port);
    switch (checkKnown(entry, hk)) {
        case 0:
            break;
        case 2:
            std::fprintf(stderr, "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n"
                                 "@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @\n"
                                 "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n"
                                 "Someone could be eavesdropping on you right now, or the host key of %s has changed.\n"
                                 "The key sent is %s.\nRemove it with: ssh-keygen -R %s\n",
                         entry.c_str(), fingerprint(hk).c_str(), entry.c_str());
            if (o.strict != "no") return 255;
            break;
        default: {
            if (o.strict == "yes") die("No ED25519 host key is known for " + entry + " and you have requested strict checking.");
            if (o.strict == "ask") {
                std::fprintf(stderr, "The authenticity of host '%s' can't be established.\nED25519 key fingerprint is %s.\n"
                                     "Are you sure you want to continue connecting (yes/no)? ",
                             entry.c_str(), fingerprint(hk).c_str());
                auto answer = readTtyLine(true);
                if (!answer || *answer != "yes") die("Host key verification failed.");
            }
            remember(entry, hk);
            if (!o.quiet) std::fprintf(stderr, "Warning: Permanently added '%s' (ED25519) to the list of known hosts.\n", entry.c_str());
        }
    }
    if (auto banner = h["banner"].asString(""); !banner.empty() && !o.quiet) std::fputs(banner.c_str(), stderr);
    Bytes shared(32);
    if (cc_x25519(epriv.data(), sepk.data(), shared.data()) != 0) die("key exchange failed");
    ch.sendKey = hkdf(shared, digest, "c2s");
    ch.receiveKey = hkdf(shared, digest, "s2c");

    // a reply from the server, or nullopt on timeout
    auto awaitReply = [&](int seconds) -> std::optional<cc::Json> {
        auto reply = ch.await(true, seconds * 1000);
        if (!reply && ch.error == EADDRINUSE) duplicate();
        if (!reply && ch.closed) die("Connection closed by " + o.host);
        return reply;
    };
    std::vector<std::string> methods;
    for (auto &m : h["methods"].items()) methods.push_back(m.asString(""));
    auto allowed = [&](const std::string &m) { return std::find(methods.begin(), methods.end(), m) != methods.end(); };

    // publickey first (every identity that exists), then the password
    bool in = false, closed = false;
    std::string lastMessage;
    if (o.publickey && allowed("publickey")) {
        for (auto &file : o.identities) {
            auto key = loadKey(file);
            if (!key) continue;
            auto data = userAuthData(digest, o.user, key->pub);
            Bytes signature(64);
            cc_ed25519_sign(key->seed.data(), data.data(), 32, signature.data());
            ch.seal(cc::Json::object({{"k", "auth"}, {"method", "publickey"}, {"user", o.user}, {"pk", base64(key->pub)}, {"sig", base64(signature)}}));
            auto reply = awaitReply(10);
            if (!reply) die("connection timed out");
            if ((*reply)["k"].asString("") == "bye") {
                closed = true;
                break;
            }
            if ((*reply)["ok"].asBool()) {
                in = true;
                break;
            }
            lastMessage = (*reply)["msg"].asString("");
        }
    }
    if (!in && !closed && o.password && allowed("password")) {
        for (int attempt = 0; attempt < 3 && !in; attempt++) {
            std::fprintf(stderr, "%s@%s's password: ", o.user.c_str(), hostLabel.c_str());
            auto pw = readTtyLine(false);
            if (!pw) return 255;
            ch.seal(cc::Json::object({{"k", "auth"}, {"method", "password"}, {"user", o.user}, {"pw", *pw}}));
            auto reply = awaitReply(10);
            if (!reply) die("connection timed out");
            if ((*reply)["k"].asString("") == "bye") break;
            if ((*reply)["ok"].asBool()) in = true;
            else std::fprintf(stderr, "%s\n", (*reply)["msg"].asString("Permission denied, please try again.").c_str());
        }
    }
    if (!in) {
        std::string list;
        for (auto &m : methods) list += (list.empty() ? "" : ",") + m;
        die(o.user + "@" + hostLabel + ": Permission denied (" + (list.empty() ? "none" : list) + ")." +
            (lastMessage.empty() ? "" : " " + lastMessage));
    }
    auto size = cc::tty::size();
    const char *term = std::getenv("TERM");
    bool pty = o.tty > 0 || (o.tty == 0 && o.command.empty() && isatty(0));
    ch.seal(cc::Json::object({{"k", "session"}, {"cols", size.cols}, {"rows", size.rows}, {"term", term ? term : "ccbaremetal"},
                              {"cmd", o.command}, {"pty", pty}}));
    return session(ch, hostLabel, pty);
}

bool writeAll(int fd, const Bytes &data) {
    size_t done = 0;
    while (done < data.size()) {
        ssize_t n = write(fd, data.data() + done, data.size() - done);
        if (n <= 0) return false;
        done += static_cast<size_t>(n);
    }
    return true;
}

// Without a terminal: standard input, output and error carried as exact bytes (commands, scp).
int plainSession(Channel &ch) {
    int status = 0;
    bool input = true, open = true;
    int ping = cc::startTimer(15);
    long lastHeard = std::time(nullptr);
    while (open) {
        auto w = input ? cc::waitAny({0, ch.fd}) : cc::waitAny({ch.fd});
        if (w.fd == 0) {
            uint8_t buf[CHUNK];
            ssize_t n = read(0, buf, sizeof(buf));
            if (n <= 0) {
                ch.seal(cc::Json::object({{"k", "eof"}}));
                input = false;
            } else {
                ch.seal(cc::Json::object({{"k", "in"}, {"d", base64(buf, static_cast<size_t>(n))}}));
            }
            continue;
        }
        if (w.fd == ch.fd) {
            if (!ch.fill()) {
                std::fputs("Connection closed by remote host\n", stderr);
                status = 255;
                break;
            }
            lastHeard = std::time(nullptr);
            while (auto f = ch.next()) {
                auto inner = ch.open(*f);
                if (!inner) {
                    open = false;
                    status = 255;
                    break;
                }
                auto kind = (*inner)["k"].asString("");
                if (kind == "out" || kind == "err") {
                    std::fflush(stdout);
                    writeAll(kind == "out" ? 1 : 2, unbase64((*inner)["d"].asString("")));
                } else if (kind == "exit") {
                    status = (*inner)["status"].asInt();
                    open = false;
                } else if (kind == "bye") {
                    open = false;
                }
            }
            continue;
        }
        if (!w.event) continue;
        auto &e = *w.event;
        if (e.name == "timer" && e.args[0].asInt() == ping) {
            if (std::time(nullptr) - lastHeard > 45) {
                std::fputs("Connection timed out\n", stderr);
                status = 255;
                break;
            }
            ch.seal(cc::Json::object({{"k", "ping"}}));
            ping = cc::startTimer(15);
        } else if (e.name == "terminate") {
            status = 130;
            break;
        }
    }
    ch.seal(cc::Json::object({{"k", "bye"}}));
    close(ch.fd);
    return status;
}

// The session: raw terminal both ways until the remote side exits (or plain bytes without a terminal).
int session(Channel &ch, const std::string &hostLabel, bool pty) {
    if (!pty) return plainSession(ch);
    cc::tty::Mode raw(CC_TTY_RAW | CC_TTY_NOECHO | CC_TTY_NOSIG);
    int status = 0;
    bool afterNewline = true, tilde = false, open = true;
    int ping = cc::startTimer(15);
    long lastHeard = std::time(nullptr);
    while (open) {
        auto w = cc::waitAny({0, ch.fd});
        if (w.fd == 0) {
            char buf[512];
            ssize_t n = read(0, buf, sizeof(buf));
            if (n <= 0) break;
            std::string send;
            for (ssize_t i = 0; i < n; i++) {
                char c = buf[i];
                // OpenSSH's escape: Enter, ~ then . disconnects
                if (tilde) {
                    tilde = false;
                    if (c == '.') {
                        open = false;
                        break;
                    }
                    if (c != '~') send += '~';
                } else if (afterNewline && c == '~') {
                    tilde = true;
                    continue;
                }
                afterNewline = c == '\r' || c == '\n';
                send += c;
            }
            if (!send.empty()) ch.seal(cc::Json::object({{"k", "in"}, {"d", base64(reinterpret_cast<const uint8_t *>(send.data()), send.size())}}));
            continue;
        }
        if (w.fd == ch.fd) {
            if (!ch.fill()) {
                status = 255;
                break;
            }
            lastHeard = std::time(nullptr);
            while (auto f = ch.next()) {
                auto inner = ch.open(*f);
                if (!inner) {
                    open = false;
                    status = 255;
                    break;
                }
                auto kind = (*inner)["k"].asString("");
                if (kind == "out") {
                    auto data = unbase64((*inner)["d"].asString(""));
                    fwrite(data.data(), 1, data.size(), stdout);
                    std::fflush(stdout);
                } else if (kind == "exit") {
                    status = (*inner)["status"].asInt();
                    open = false;
                } else if (kind == "bye") {
                    open = false;
                }
            }
            continue;
        }
        if (!w.event) continue;
        auto &e = *w.event;
        if (e.name == "term_resize") {
            auto s = cc::tty::size();
            ch.seal(cc::Json::object({{"k", "size"}, {"cols", s.cols}, {"rows", s.rows}}));
        } else if (e.name == "timer" && e.args[0].asInt() == ping) {
            if (std::time(nullptr) - lastHeard > 45) {
                std::fputs("\r\nConnection timed out\r\n", stderr);
                status = 255;
                break;
            }
            ch.seal(cc::Json::object({{"k", "ping"}}));
            ping = cc::startTimer(15);
        }
    }
    ch.seal(cc::Json::object({{"k", "bye"}}));
    close(ch.fd);
    std::fprintf(stderr, "\r\nConnection to %s closed%s.\r\n", hostLabel.c_str(), ch.closed && ch.error ? (std::string(" (") + std::strerror(ch.error) + ")").c_str() : "");
    return status;
}

// ---- ssh-keygen, ssh-copy-id ------------------------------------------------------------------------------------

int keygen(const Args &a) {
    std::string file, comment;
    char action = 0;
    std::string removeHost;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        auto value = [&]() -> std::string {
            if (i + 1 >= a.size()) die("option requires an argument -- " + o);
            return a[++i];
        };
        if (o == "-f") file = expandHome(value());
        else if (o == "-C") comment = value();
        else if (o == "-t") {
            if (value() != "ed25519") die("only ed25519 keys are supported");
        } else if (o == "-N") {
            if (!value().empty()) die("passphrases are not supported: the key file is protected by its mode (0600)");
        } else if (o == "-l") action = 'l';
        else if (o == "-y") action = 'y';
        else if (o == "-R") {
            action = 'R';
            removeHost = value();
        } else if (o == "--help" || o == "-h") {
            std::puts("usage: ssh-keygen [-t ed25519] [-f FILE] [-C COMMENT]   make a key pair (~/.ssh/id_ed25519)\n"
                      "       ssh-keygen -l -f FILE      fingerprint of a key\n"
                      "       ssh-keygen -y -f FILE      the public key of a private key\n"
                      "       ssh-keygen -R HOST         forget HOST in ~/.ssh/known_hosts ([HOST]:PORT for other ports)");
            return 0;
        } else die("unknown option -- " + o);
    }
    if (action == 'R') {
        auto path = homeDir() + "/.ssh/known_hosts";
        FILE *f = std::fopen(path.c_str(), "r");
        if (!f) die(path + ": " + std::strerror(errno), 1);
        std::string kept;
        char line[512];
        int removed = 0;
        auto want = lower(removeHost);
        while (std::fgets(line, sizeof(line), f)) {
            char name[128];
            if (std::sscanf(line, "%127s", name) == 1 && lower(name) == want) {
                removed++;
                continue;
            }
            kept += line;
        }
        std::fclose(f);
        FILE *w = std::fopen(path.c_str(), "w");
        if (!w) die(path + ": " + std::strerror(errno), 1);
        std::fputs(kept.c_str(), w);
        std::fclose(w);
        std::printf("# Host %s found: %d line%s removed from %s\n", removeHost.c_str(), removed, removed == 1 ? "" : "s", path.c_str());
        return removed ? 0 : 1;
    }
    if (file.empty()) file = homeDir() + "/.ssh/id_ed25519";
    if (action == 'l' || action == 'y') {
        auto key = loadKey(file);
        Bytes pub;
        std::string c;
        if (key) {
            pub = key->pub;
            c = key->comment;
        } else if (FILE *f = std::fopen(file.c_str(), "r")) {
            char b64[128], rest[256] = "";
            if (std::fscanf(f, "ssh-ed25519 %127s %255[^\n]", b64, rest) >= 1) pub = unbase64(b64);
            c = rest;
            std::fclose(f);
        }
        if (pub.size() != 32) die(file + " is not a key file", 1);
        if (action == 'y') std::printf("ssh-ed25519 %s %s\n", base64(pub).c_str(), c.c_str());
        else std::printf("256 %s %s (ED25519)\n", fingerprint(pub).c_str(), c.c_str());
        return 0;
    }
    struct stat st;
    if (stat(file.c_str(), &st) == 0) {
        std::fprintf(stderr, "%s already exists.\nOverwrite (y/n)? ", file.c_str());
        auto answer = readTtyLine(true);
        if (!answer || (*answer != "y" && *answer != "yes")) return 1;
    }
    auto slash = file.rfind('/');
    if (slash != std::string::npos && slash > 0) mkdir(file.substr(0, slash).c_str(), 0700);
    if (comment.empty()) {
        auto *pw = getpwuid(getuid());
        comment = std::string(pw ? pw->pw_name : "user") + "@" + hostName();
    }
    std::puts("Generating public/private ed25519 key pair.");
    auto key = newKey(comment);
    if (!saveKey(file, key)) die("cannot write " + file + ": " + std::strerror(errno), 1);
    std::printf("Your identification has been saved in %s\nYour public key has been saved in %s.pub\n"
                "The key fingerprint is:\n%s %s\n",
                file.c_str(), file.c_str(), fingerprint(key.pub).c_str(), comment.c_str());
    return 0;
}

int copyId(const Args &a) {
    std::string identity = homeDir() + "/.ssh/id_ed25519.pub", port, target;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-i" && i + 1 < a.size()) identity = expandHome(a[++i]);
        else if (a[i] == "-p" && i + 1 < a.size()) port = a[++i];
        else if (a[i] == "--help" || a[i] == "-h") {
            std::puts("usage: ssh-copy-id [-i KEYFILE.pub] [-p PORT] [USER@]HOST   (adds your key to HOST's ~/.ssh/authorized_keys)");
            return 0;
        } else target = a[i];
    }
    if (target.empty()) die("usage: ssh-copy-id [-i KEYFILE.pub] [-p PORT] [USER@]HOST", 1);
    if (identity.size() < 4 || identity.substr(identity.size() - 4) != ".pub") identity += ".pub";
    FILE *f = std::fopen(identity.c_str(), "r");
    if (!f) die(identity + ": " + std::strerror(errno) + " (make a key with ssh-keygen)", 1);
    char line[512] = "";
    std::fgets(line, sizeof(line), f);
    std::fclose(f);
    std::string key = line;
    while (!key.empty() && (key.back() == '\n' || key.back() == '\r')) key.pop_back();
    if (key.rfind("ssh-ed25519 ", 0) != 0 || key.find('\'') != std::string::npos) die(identity + " is not an ed25519 public key", 1);
    std::printf("ssh-copy-id: adding %s to %s\n", identity.c_str(), target.c_str());
    // the remote side appends the key unless it is there already, with the modes sshd insists on
    auto remote = "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"
                  " && if grep -qF '" + key + "' ~/.ssh/authorized_keys; then echo 'key already present'; else echo '" + key +
                  "' >> ~/.ssh/authorized_keys && echo 'key added'; fi";
    Args argv = {"ssh"};
    if (!port.empty()) argv.insert(argv.end(), {"-p", port});
    argv.push_back(target);
    argv.push_back(remote);
    int r = cc::run(argv);
    if (r == 0) std::printf("Now try logging in: ssh %s%s\n", port.empty() ? "" : ("-p " + port + " ").c_str(), target.c_str());
    return r < 0 ? 1 : r;
}

// ---- scp --------------------------------------------------------------------------------------------------------
//
// The classic SCP protocol, as OpenSSH's scp speaks it with "scp -t" (sink) and "scp -f" (source) on the other end
// of an ssh connection without a terminal: "C0644 SIZE NAME\n" then the bytes and "\0", "D0755 0 NAME\n" ... "E\n"
// for folders, "T MTIME 0 ATIME 0\n" before an entry with -p. Every line and file is answered with "\0" (ok),
// "\1message\n" (warning) or "\2message\n" (fatal).

struct Io {
    int in, out; // read the peer from `in`, write to it on `out`
};

bool sendBytes(int fd, const void *data, size_t n) {
    auto *p = static_cast<const uint8_t *>(data);
    while (n > 0) {
        ssize_t w = write(fd, p, n);
        if (w <= 0) return false;
        p += w;
        n -= static_cast<size_t>(w);
    }
    return true;
}

bool sendText(int fd, const std::string &text) {
    return sendBytes(fd, text.data(), text.size());
}

std::optional<std::string> readProtoLine(int fd) {
    std::string line;
    char c;
    while (true) {
        if (read(fd, &c, 1) != 1) return line.empty() ? std::nullopt : std::optional<std::string>(line);
        if (c == '\n') return line;
        line += c;
        if (line.size() > 4096) return std::nullopt;
    }
}

// 0 ok, 1 warning, 2 fatal (message in `msg`), -1 the connection ended
int readAck(const Io &io, std::string &msg) {
    char c;
    if (read(io.in, &c, 1) != 1) return -1;
    if (c == 0) return 0;
    auto line = readProtoLine(io.in);
    msg = line.value_or("");
    return c == 1 ? 1 : 2;
}

bool ack(const Io &io) {
    char zero = 0;
    return sendBytes(io.out, &zero, 1);
}

void sendError(const Io &io, const std::string &msg, bool fatal) {
    char kind = fatal ? 2 : 1;
    sendBytes(io.out, &kind, 1);
    sendText(io.out, "scp: " + msg + "\n");
}

bool isDirPath(const std::string &path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

std::string baseName(std::string path) {
    while (path.size() > 1 && path.back() == '/') path.pop_back();
    auto slash = path.rfind('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

struct ScpOptions {
    bool recursive = false, preserve = false, quiet = false, targetDir = false;
};

void progress(const ScpOptions &o, const std::string &name, long long size, long started) {
    if (o.quiet || !isatty(2)) return;
    long secs = std::max(1L, static_cast<long>(std::time(nullptr) - started));
    std::fprintf(stderr, "%-32s 100%% %8lldKB %7.1fKB/s %02ld:%02ld\n", baseName(name).substr(0, 32).c_str(), (size + 1023) / 1024,
                 size / 1024.0 / static_cast<double>(secs), secs / 60, secs % 60);
}

// Send one file or folder (source side). False after a fatal error.
bool sendEntry(const Io &io, const ScpOptions &o, const std::string &path, int &errors) {
    struct stat st;
    std::string msg;
    if (stat(path.c_str(), &st) != 0) {
        std::fprintf(stderr, "scp: %s: %s\n", path.c_str(), std::strerror(errno));
        sendError(io, path + ": " + std::strerror(errno), false);
        errors++;
        return readAck(io, msg) >= 0;
    }
    if (o.preserve) {
        sendText(io.out, "T" + std::to_string(static_cast<long long>(st.st_mtime)) + " 0 " + std::to_string(static_cast<long long>(st.st_atime)) + " 0\n");
        if (readAck(io, msg) != 0) {
            std::fprintf(stderr, "%s\n", msg.c_str());
            return false;
        }
    }
    char header[64];
    if (S_ISDIR(st.st_mode)) {
        if (!o.recursive) {
            std::fprintf(stderr, "scp: %s: not a regular file\n", path.c_str());
            errors++;
            return true;
        }
        std::snprintf(header, sizeof(header), "D%04o 0 ", static_cast<unsigned>(st.st_mode & 07777));
        sendText(io.out, header + baseName(path) + "\n");
        int r = readAck(io, msg);
        if (r != 0) {
            std::fprintf(stderr, "%s\n", msg.c_str());
            if (r != 1) return false;
            errors++;
            return true;
        }
        if (DIR *d = opendir(path.c_str())) {
            std::vector<std::string> names;
            while (auto *e = readdir(d)) {
                std::string n = e->d_name;
                if (n != "." && n != "..") names.push_back(n);
            }
            closedir(d);
            std::sort(names.begin(), names.end());
            for (auto &n : names) {
                if (!sendEntry(io, o, path + "/" + n, errors)) return false;
            }
        }
        sendText(io.out, "E\n");
        return readAck(io, msg) == 0;
    }
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        std::fprintf(stderr, "scp: %s: %s\n", path.c_str(), std::strerror(errno));
        errors++;
        return true;
    }
    std::snprintf(header, sizeof(header), "C%04o %lld ", static_cast<unsigned>(st.st_mode & 07777), static_cast<long long>(st.st_size));
    sendText(io.out, header + baseName(path) + "\n");
    int r = readAck(io, msg);
    if (r != 0) {
        close(fd);
        std::fprintf(stderr, "%s\n", msg.c_str());
        if (r != 1) return false;
        errors++;
        return true;
    }
    long started = static_cast<long>(std::time(nullptr));
    char buf[CHUNK * 4];
    long long left = st.st_size;
    while (left > 0) {
        ssize_t n = read(fd, buf, static_cast<size_t>(std::min<long long>(left, sizeof(buf))));
        if (n <= 0) {
            // the file shrank: pad, so the other side stays in step, and report it
            std::memset(buf, 0, sizeof(buf));
            n = static_cast<ssize_t>(std::min<long long>(left, sizeof(buf)));
            errors++;
        }
        if (!sendBytes(io.out, buf, static_cast<size_t>(n))) {
            close(fd);
            return false;
        }
        left -= n;
    }
    close(fd);
    ack(io);
    r = readAck(io, msg);
    if (r != 0) {
        std::fprintf(stderr, "%s\n", msg.c_str());
        errors++;
        return r == 1;
    }
    progress(o, path, st.st_size, started);
    return true;
}

// Source side ("scp -f" remotely, or the local files of an upload): waits for the sink to be ready first.
int source(const Io &io, const ScpOptions &o, const std::vector<std::string> &paths) {
    std::string msg;
    if (readAck(io, msg) != 0) {
        if (!msg.empty()) std::fprintf(stderr, "%s\n", msg.c_str());
        return 1;
    }
    int errors = 0;
    for (auto &p : paths) {
        if (!sendEntry(io, o, p, errors)) return 1;
    }
    return errors ? 1 : 0;
}

// Sink side ("scp -t" remotely, or the local target of a download).
int sink(const Io &io, const ScpOptions &o, const std::string &target) {
    if (o.targetDir && !isDirPath(target)) {
        sendError(io, target + ": Not a directory", true);
        return 1;
    }
    ack(io);
    std::vector<std::string> dirs; // folders entered with D
    long long mtime = -1, atime = -1;
    int errors = 0;
    mode_t mask = umask(0);
    umask(mask);
    auto destination = [&](const std::string &name) {
        if (!dirs.empty()) return dirs.back() + "/" + name;
        return isDirPath(target) ? target + "/" + name : target;
    };
    while (auto line = readProtoLine(io.in)) {
        if (line->empty()) break;
        char kind = (*line)[0];
        auto rest = line->substr(1);
        if (kind == 1 || kind == 2) {
            std::fprintf(stderr, "%s\n", rest.c_str());
            if (kind == 2) return 1;
            errors++;
            continue;
        }
        if (kind == 'T') {
            long long ms, mu, as, au;
            if (std::sscanf(rest.c_str(), "%lld %lld %lld %lld", &ms, &mu, &as, &au) != 4) {
                sendError(io, "protocol error: bad time", true);
                return 1;
            }
            mtime = ms;
            atime = as;
            ack(io);
            continue;
        }
        if (kind == 'E') {
            if (dirs.empty()) {
                sendError(io, "protocol error: unexpected E", true);
                return 1;
            }
            dirs.pop_back();
            ack(io);
            continue;
        }
        unsigned mode;
        long long size;
        char nameBuf[1024];
        if ((kind != 'C' && kind != 'D') || std::sscanf(rest.c_str(), "%o %lld %1023[^\n]", &mode, &size, nameBuf) != 3) {
            sendError(io, "protocol error: " + *line, true);
            return 1;
        }
        std::string name = nameBuf;
        // a name, never a path: nothing may land outside the target
        if (name.empty() || name == "." || name == ".." || name.find('/') != std::string::npos) {
            sendError(io, "protocol error: unexpected filename: " + name, true);
            return 1;
        }
        auto dest = destination(name);
        if (kind == 'D') {
            if (!o.recursive) {
                sendError(io, "received directory without -r", true);
                return 1;
            }
            if (!isDirPath(dest) && mkdir(dest.c_str(), 0700) != 0) {
                sendError(io, dest + ": " + std::strerror(errno), true);
                return 1;
            }
            chmod(dest.c_str(), o.preserve ? mode & 07777 : (mode & 0777 & ~mask) | 0700);
            dirs.push_back(dest);
            mtime = atime = -1;
            ack(io);
            continue;
        }
        bool existed = access(dest.c_str(), F_OK) == 0;
        int fd = open(dest.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0666);
        int openErr = errno;
        ack(io); // the data comes in any case: read it, report a failure afterwards
        long started = static_cast<long>(std::time(nullptr));
        char buf[CHUNK * 4];
        long long left = size;
        bool writeFailed = fd < 0;
        int writeErr = openErr;
        while (left > 0) {
            ssize_t n = read(io.in, buf, static_cast<size_t>(std::min<long long>(left, sizeof(buf))));
            if (n <= 0) {
                std::fprintf(stderr, "scp: lost connection\n");
                if (fd >= 0) close(fd);
                return 1;
            }
            if (!writeFailed && !sendBytes(fd, buf, static_cast<size_t>(n))) {
                writeFailed = true;
                writeErr = errno;
            }
            left -= n;
        }
        if (fd >= 0) close(fd);
        std::string msg;
        int r = readAck(io, msg); // the source's "\0" after the data
        if (r != 0) std::fprintf(stderr, "%s\n", msg.c_str());
        if (writeFailed) {
            std::fprintf(stderr, "scp: %s: %s\n", dest.c_str(), std::strerror(writeErr));
            sendError(io, dest + ": " + std::strerror(writeErr), false);
            errors++;
            continue;
        }
        // like OpenSSH: a new file gets the sender's mode minus the umask; -p keeps it (and the times) exactly
        if (o.preserve) chmod(dest.c_str(), mode & 07777);
        else if (!existed) chmod(dest.c_str(), mode & 0777 & ~mask);
        if (o.preserve && mtime >= 0) {
            struct timespec times[2] = {{static_cast<time_t>(atime), 0}, {static_cast<time_t>(mtime), 0}};
            utimensat(AT_FDCWD, dest.c_str(), times, 0);
        }
        mtime = atime = -1;
        progress(o, dest, size, started);
        ack(io);
    }
    return errors ? 1 : 0;
}

struct Remote {
    bool remote = false;
    std::string user, host, path;
};

// [USER@]HOST:PATH (a colon before any slash); anything else is a local path.
Remote parseRemote(const std::string &arg) {
    Remote r;
    auto colon = arg.find(':');
    auto slash = arg.find('/');
    if (colon == std::string::npos || colon == 0 || (slash != std::string::npos && slash < colon)) {
        r.path = arg;
        return r;
    }
    r.remote = true;
    auto hostPart = arg.substr(0, colon);
    r.path = arg.substr(colon + 1);
    if (r.path.empty()) r.path = ".";
    if (auto at = hostPart.rfind('@'); at != std::string::npos) {
        r.user = hostPart.substr(0, at);
        r.host = hostPart.substr(at + 1);
    } else {
        r.host = hostPart;
    }
    return r;
}

std::string shellQuote(const std::string &s) {
    std::string out = "'";
    for (char c : s) out += c == '\'' ? std::string("'\\''") : std::string(1, c);
    return out + "'";
}

int scp(const Args &a) {
    ScpOptions o;
    bool sinkMode = false, sourceMode = false;
    std::vector<std::string> sshArgs, files;
    for (size_t i = 1; i < a.size(); i++) {
        auto &arg = a[i];
        if (arg == "--") {
            files.insert(files.end(), a.begin() + static_cast<long>(i) + 1, a.end());
            break;
        }
        if (arg.size() < 2 || arg[0] != '-') {
            files.push_back(arg);
            continue;
        }
        if (arg == "-P" || arg == "-i" || arg == "-o" || arg == "-l") {
            if (i + 1 >= a.size()) die("option requires an argument -- " + arg, 1);
            sshArgs.push_back(arg == "-P" ? "-p" : arg);
            sshArgs.push_back(a[++i]);
            continue;
        }
        if (arg == "--help" || arg == "-h") {
            std::puts("usage: scp [-r] [-p] [-q] [-P PORT] [-i KEY] [-o OPT=VAL] SOURCE... TARGET\n"
                      "  a remote file is [USER@]HOST:PATH (relative to the remote home); one side must be local.\n"
                      "  scp notes.txt user@base:            upload into the home folder\n"
                      "  scp -r user@base:project .          download a folder");
            return 0;
        }
        for (char f : arg.substr(1)) {
            if (f == 'r') o.recursive = true;
            else if (f == 'p') o.preserve = true;
            else if (f == 'q') o.quiet = true;
            else if (f == 't') sinkMode = true;
            else if (f == 'f') sourceMode = true;
            else if (f == 'd') o.targetDir = true;
            else if (f == 'v' || f == 'C' || f == 'B' || f == '3' || f == '4' || f == '6') {
            } else die(std::string("unknown option -- ") + f, 1);
        }
    }
    // the remote end, started by ssh: talk on standard input and output
    if (sinkMode) {
        if (files.size() != 1) die("ambiguous target", 1);
        return sink(Io{0, 1}, o, files[0]);
    }
    if (sourceMode) return source(Io{0, 1}, o, files);
    if (files.size() < 2) die("usage: scp [-r] [-p] [-P PORT] SOURCE... TARGET", 1);
    auto target = parseRemote(files.back());
    std::vector<Remote> sources;
    for (size_t i = 0; i + 1 < files.size(); i++) sources.push_back(parseRemote(files[i]));
    bool anyRemoteSource = false;
    for (auto &src : sources) anyRemoteSource |= src.remote;
    if (!target.remote && !anyRemoteSource) {
        Args argv = {"cp"};
        if (o.recursive) argv.push_back("-r");
        if (o.preserve) argv.push_back("-p");
        for (auto &src : sources) argv.push_back(src.path);
        argv.push_back(target.path);
        return cc::run(argv);
    }
    if (target.remote && anyRemoteSource) die("copying between two remote computers is not supported: copy through this one", 1);

    // ssh without a terminal to "scp -t TARGET" (upload) or "scp -f SOURCE" (download), through pipes
    auto &remote = target.remote ? target : sources.front();
    if (!target.remote && sources.size() != 1) die("downloading: one remote source at a time", 1);
    std::string flags = std::string(o.recursive ? " -r" : "") + (o.preserve ? " -p" : "");
    std::string command = target.remote ? "scp -t" + flags + (sources.size() > 1 ? " -d" : "") + " -- " + shellQuote(target.path)
                                        : "scp -f" + flags + " -- " + shellQuote(remote.path);
    int32_t toSsh[2], fromSsh[2];
    if (cc_pipe(toSsh) != 0 || cc_pipe(fromSsh) != 0) die("cannot create pipes", 1);
    Args argv = {"ssh", "-T"};
    argv.insert(argv.end(), sshArgs.begin(), sshArgs.end());
    if (!remote.user.empty()) argv.insert(argv.end(), {"-l", remote.user});
    argv.push_back(remote.host);
    argv.push_back(command);
    cc::SpawnOptions options;
    options.in = toSsh[0];
    options.out = fromSsh[1];
    int pid = cc::spawn(argv, options);
    close(toSsh[0]);
    close(fromSsh[1]);
    if (pid < 0) die("cannot run ssh", 1);
    Io io{fromSsh[0], toSsh[1]};
    int r;
    if (target.remote) {
        std::vector<std::string> paths;
        for (auto &src : sources) paths.push_back(src.path);
        r = source(io, o, paths);
    } else {
        r = sink(io, o, target.path);
    }
    close(toSsh[1]);
    close(fromSsh[0]);
    int status = 0;
    cc::wait(pid, &status);
    return r ? r : status;
}

// ---- Server -----------------------------------------------------------------------------------------------------

constexpr const char *KEY_FILE = "/etc/ssh/ssh_host_ed25519_key";
constexpr const char *CONFIG_FILE = "/etc/ssh/sshd_config";

KeyPair hostKey() {
    if (auto k = loadKey(KEY_FILE)) return *k;
    mkdir("/etc/ssh", 0755);
    auto k = newKey("root@" + hostName());
    saveKey(KEY_FILE, k);
    std::printf("sshd: generated the host key %s\n", fingerprint(k.pub).c_str());
    return k;
}

// /etc/ssh/sshd_config: the usual OpenSSH settings that make sense here.
struct ServerConfig {
    int port = 22;
    std::string permitRootLogin = "prohibit-password"; // yes | no | prohibit-password
    bool passwordAuthentication = true;
    bool pubkeyAuthentication = true;
    bool permitEmptyPasswords = false;
    int maxAuthTries = 6;
    int maxSessions = 10;
    int clientAliveInterval = 15;
    int clientAliveCountMax = 4;
    std::vector<std::string> allowUsers, denyUsers, allowGroups, denyGroups;
    std::string banner;
    bool printMotd = true;
    std::string authorizedKeysFile = ".ssh/authorized_keys";
};

const char *DEFAULT_CONFIG = R"(# /etc/ssh/sshd_config - settings of sshd (man ssh). Lines starting with # are comments.
# After a change: sudo sshd -t (check), then restart: sudo pkill sshd; sudo sshd

#Port 22
#PermitRootLogin prohibit-password
#PasswordAuthentication yes
#PubkeyAuthentication yes
#PermitEmptyPasswords no
#AuthorizedKeysFile .ssh/authorized_keys
#MaxAuthTries 6
#MaxSessions 10
#ClientAliveInterval 15
#ClientAliveCountMax 4
#AllowUsers user bob
#DenyUsers guest
#AllowGroups wheel
#DenyGroups nologin
#Banner /etc/issue.net
#PrintMotd yes
)";

std::vector<std::string> words(const std::string &s) {
    std::vector<std::string> out;
    size_t i = 0;
    while (i < s.size()) {
        while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) i++;
        size_t j = i;
        while (j < s.size() && !std::isspace(static_cast<unsigned char>(s[j]))) j++;
        if (j > i) out.push_back(s.substr(i, j - i));
        i = j;
    }
    return out;
}

bool readServerConfig(const std::string &path, ServerConfig &c, std::string &error) {
    FILE *f = std::fopen(path.c_str(), "r");
    if (!f) {
        if (errno == ENOENT) return true;
        error = path + ": " + std::strerror(errno);
        return false;
    }
    char buf[512];
    int lineNo = 0;
    bool ok = true;
    auto yesNo = [&](const std::string &v, bool &out) {
        if (lower(v) == "yes") out = true;
        else if (lower(v) == "no") out = false;
        else return false;
        return true;
    };
    while (std::fgets(buf, sizeof(buf), f)) {
        lineNo++;
        std::string line = buf;
        if (auto hash = line.find('#'); hash != std::string::npos) line.resize(hash);
        auto w = words(line);
        if (w.empty()) continue;
        auto key = lower(w[0]);
        std::vector<std::string> rest(w.begin() + 1, w.end());
        auto bad = [&](const std::string &why) {
            error += path + " line " + std::to_string(lineNo) + ": " + why + "\n";
            ok = false;
        };
        if (rest.empty()) {
            bad("missing value for " + w[0]);
            continue;
        }
        auto &v = rest[0];
        if (key == "port") {
            c.port = std::atoi(v.c_str());
            if (c.port <= 0 || c.port > 65535) bad("bad port " + v);
        } else if (key == "permitrootlogin") {
            c.permitRootLogin = lower(v) == "without-password" ? "prohibit-password" : lower(v);
            if (c.permitRootLogin != "yes" && c.permitRootLogin != "no" && c.permitRootLogin != "prohibit-password") bad("bad PermitRootLogin " + v);
        } else if (key == "passwordauthentication") {
            if (!yesNo(v, c.passwordAuthentication)) bad("yes or no expected");
        } else if (key == "pubkeyauthentication") {
            if (!yesNo(v, c.pubkeyAuthentication)) bad("yes or no expected");
        } else if (key == "permitemptypasswords") {
            if (!yesNo(v, c.permitEmptyPasswords)) bad("yes or no expected");
        } else if (key == "printmotd") {
            if (!yesNo(v, c.printMotd)) bad("yes or no expected");
        } else if (key == "maxauthtries") {
            c.maxAuthTries = std::max(1, std::atoi(v.c_str()));
        } else if (key == "maxsessions") {
            c.maxSessions = std::max(1, std::atoi(v.c_str()));
        } else if (key == "clientaliveinterval") {
            c.clientAliveInterval = std::max(1, std::atoi(v.c_str()));
        } else if (key == "clientalivecountmax") {
            c.clientAliveCountMax = std::max(1, std::atoi(v.c_str()));
        } else if (key == "allowusers") {
            c.allowUsers.insert(c.allowUsers.end(), rest.begin(), rest.end());
        } else if (key == "denyusers") {
            c.denyUsers.insert(c.denyUsers.end(), rest.begin(), rest.end());
        } else if (key == "allowgroups") {
            c.allowGroups.insert(c.allowGroups.end(), rest.begin(), rest.end());
        } else if (key == "denygroups") {
            c.denyGroups.insert(c.denyGroups.end(), rest.begin(), rest.end());
        } else if (key == "banner") {
            c.banner = lower(v) == "none" ? "" : v;
        } else if (key == "authorizedkeysfile") {
            c.authorizedKeysFile = v;
        } else {
            bad("unsupported option " + w[0]);
        }
    }
    std::fclose(f);
    return ok;
}

// /var/log/auth.log, as syslog writes it: "Oct 11 22:00:01 base sshd[19]: ..."
void logAuth(const std::string &text) {
    mkdir("/var/log", 0755);
    mode_t old = umask(027);
    if (FILE *f = std::fopen("/var/log/auth.log", "a")) {
        char when[32];
        time_t now = std::time(nullptr);
        std::strftime(when, sizeof(when), "%b %e %H:%M:%S", std::localtime(&now));
        std::fprintf(f, "%s %s sshd[%d]: %s\n", when, hostName().c_str(), cc::pid(), text.c_str());
        std::fclose(f);
    }
    umask(old);
}

struct Session {
    Channel ch;
    bool keyed = false; // keys agreed (after the hellos)
    Bytes digest;
    std::string user, from;
    int tries = 0;
    bool authenticated = false;
    int master = -1, ptyId = -1, pid = -1;
    // without a terminal (a command, scp): the program's standard input, output and error as pipes
    int in = -1, out = -1, err = -1;
    bool started = false;
    long lastHeard = 0;
};

bool memberOf(const std::string &user, gid_t primary, const std::string &group) {
    auto *g = getgrnam(group.c_str());
    if (!g) return false;
    if (g->gr_gid == primary) return true;
    for (char **m = g->gr_mem; m && *m; m++) {
        if (user == *m) return true;
    }
    return false;
}

bool matches(const std::vector<std::string> &patterns, const std::string &name) {
    for (auto &p : patterns) {
        if (fnmatchLike(p, name)) return true;
    }
    return false;
}

// Whether `user` may log in at all (before checking a password or key): an empty string, or why not (logged only;
// the client just hears "Permission denied", as OpenSSH does).
std::string accountProblem(const ServerConfig &c, const std::string &user, const std::string &method) {
    auto *pw = getpwnam(user.c_str());
    if (!pw) return "Invalid user " + user;
    auto *sp = getspnam(user.c_str());
    if (sp && (sp->sp_pwdp[0] == '!' || sp->sp_pwdp[0] == '*')) return "User " + user + " not allowed because account is locked";
    if (pw->pw_uid == 0 && (c.permitRootLogin == "no" || (c.permitRootLogin == "prohibit-password" && method != "publickey"))) {
        return "ROOT LOGIN REFUSED (PermitRootLogin " + c.permitRootLogin + ")";
    }
    if (matches(c.denyUsers, user)) return "User " + user + " not allowed because listed in DenyUsers";
    if (!c.allowUsers.empty() && !matches(c.allowUsers, user)) return "User " + user + " not allowed because not listed in AllowUsers";
    for (auto &g : c.denyGroups) {
        if (memberOf(user, pw->pw_gid, g)) return "User " + user + " not allowed because a group is listed in DenyGroups";
    }
    if (!c.allowGroups.empty()) {
        bool any = false;
        for (auto &g : c.allowGroups) any |= memberOf(user, pw->pw_gid, g);
        if (!any) return "User " + user + " not allowed because none of user's groups are listed in AllowGroups";
    }
    return "";
}

bool checkPassword(const ServerConfig &c, const std::string &user, const std::string &password) {
    auto *sp = getspnam(user.c_str());
    if (!sp) return false;
    std::string hash = sp->sp_pwdp;
    if (hash.empty()) return c.permitEmptyPasswords && password.empty();
    const char *out = crypt(password.c_str(), hash.c_str());
    return out && hash == out;
}

// The user's authorized_keys, if sshd may trust it (StrictModes: owned by the user or root, not writable by others).
bool keyAuthorized(const ServerConfig &c, const std::string &user, const Bytes &pub) {
    auto *pw = getpwnam(user.c_str());
    if (!pw) return false;
    auto file = c.authorizedKeysFile[0] == '/' ? c.authorizedKeysFile : std::string(pw->pw_dir) + "/" + c.authorizedKeysFile;
    struct stat st;
    for (auto &path : {file, file.substr(0, file.rfind('/'))}) {
        if (stat(path.c_str(), &st) != 0) return false;
        if ((st.st_uid != pw->pw_uid && st.st_uid != 0) || (st.st_mode & 022)) {
            logAuth("Authentication refused: bad ownership or modes for " + path);
            return false;
        }
    }
    FILE *f = std::fopen(file.c_str(), "r");
    if (!f) return false;
    char line[512];
    bool found = false;
    auto want = base64(pub);
    while (!found && std::fgets(line, sizeof(line), f)) {
        auto w = words(line);
        for (size_t i = 0; i + 1 < w.size(); i++) {
            if (w[i] == "ssh-ed25519" && w[i + 1] == want) found = true;
        }
    }
    std::fclose(f);
    return found;
}

void startSession(const ServerConfig &c, Session &s, const cc::Json &req) {
    s.started = true;
    auto command = req["cmd"].asString("");
    auto env = std::vector<std::string>{"TERM=" + req["term"].asString("ccbaremetal"), "PATH=/usr/local/bin:/bin:/usr/bin:/sbin",
                                        "SSH_CONNECTION=" + s.from + " " + std::to_string(c.port)};
    cc::SpawnOptions options;
    options.env = env;
    if (!req["pty"].asBool(true)) {
        // no terminal (as OpenSSH for "ssh host command" and scp): exact bytes through pipes
        int32_t in[2], out[2], err[2];
        if (cc_pipe(in) != 0 || cc_pipe(out) != 0 || cc_pipe(err) != 0) {
            s.ch.seal(cc::Json::object({{"k", "exit"}, {"status", 255}}));
            return;
        }
        options.in = in[0];
        options.out = out[1];
        options.err = err[1];
        options.flags = CC_SPAWN_NEW_GROUP;
        Args argv = command.empty() ? Args{"/bin/su", "-", s.user} : Args{"/bin/su", "-", s.user, "-c", command};
        s.pid = cc::spawn(argv, options);
        close(in[0]);
        close(out[1]);
        close(err[1]);
        s.in = in[1];
        s.out = out[0];
        s.err = err[0];
        if (s.pid < 0) {
            s.ch.seal(cc::Json::object({{"k", "exit"}, {"status", 127}}));
            for (int *fd : {&s.in, &s.out, &s.err}) {
                close(*fd);
                *fd = -1;
            }
        }
        return;
    }
    int32_t pty[3];
    if (cc_pty_open(pty) != 0) {
        s.ch.seal(cc::Json::object({{"k", "exit"}, {"status", 255}}));
        return;
    }
    s.master = pty[0];
    s.ptyId = pty[2];
    cc_pty_resize(s.master, req["cols"].asInt(80), req["rows"].asInt(24));
    if (command.empty() && c.printMotd) {
        // the message of the day, as a login on the console shows it
        if (FILE *f = std::fopen("/etc/motd", "r")) {
            std::string motd;
            char buf[256];
            while (std::fgets(buf, sizeof(buf), f)) {
                motd += buf;
                if (motd.back() == '\n') motd.insert(motd.size() - 1, "\r");
            }
            std::fclose(f);
            if (!motd.empty()) s.ch.seal(cc::Json::object({{"k", "out"}, {"d", base64(reinterpret_cast<const uint8_t *>(motd.data()), motd.size())}}));
        }
    }
    options.in = options.out = options.err = pty[1];
    options.flags = CC_SPAWN_NEW_GROUP | CC_SPAWN_FOREGROUND | CC_SPAWN_CTTY;
    Args argv = command.empty() ? Args{"/bin/login", "-f", s.user} : Args{"/bin/su", "-", s.user, "-c", command};
    s.pid = cc::spawn(argv, options);
    close(pty[1]); // the session holds the slave now
    if (s.pid < 0) {
        s.ch.seal(cc::Json::object({{"k", "exit"}, {"status", 127}}));
        close(s.master);
        s.master = -1;
    }
}

// Forward what is waiting on one of a session's descriptors ("out" or "err"). False at its end.
bool pump(Session &s, int fd, const char *kind) {
    char buf[CHUNK];
    while (true) {
        int32_t ready = -1;
        // only read what is there: an empty pipe or master would block
        if (cc_event_wait_fds(&fd, 1, 0, &ready) != 2) return true;
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) return false;
        s.ch.seal(cc::Json::object({{"k", kind}, {"d", base64(reinterpret_cast<uint8_t *>(buf), static_cast<size_t>(n))}}));
    }
}

void endSession(Session &s) {
    int status = 0;
    if (s.pid > 0) cc::wait(s.pid, &status);
    s.pid = -1;
    s.ch.seal(cc::Json::object({{"k", "exit"}, {"status", status}}));
    for (int *fd : {&s.master, &s.in, &s.out, &s.err}) {
        if (*fd >= 0) close(*fd);
        *fd = -1;
    }
    logAuth("session closed for user " + s.user + " from " + s.from);
}

std::string pidFile(int port) {
    return "/run/sshd" + (port == 22 ? std::string() : "-" + std::to_string(port)) + ".pid";
}

int server(const Args &a) {
    bool foreground = false, test = false;
    std::string configPath = CONFIG_FILE;
    int portOverride = 0;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        if (o == "-D") foreground = true;
        else if (o == "-t") test = true;
        else if (o == "-f" && i + 1 < a.size()) configPath = a[++i];
        else if (o == "-p" && i + 1 < a.size()) portOverride = std::atoi(a[++i].c_str());
        else if (o.rfind("-p", 0) == 0 && o.size() > 2) portOverride = std::atoi(o.c_str() + 2);
        else if (o == "--help" || o == "-h") {
            std::puts("usage: sshd [-p PORT] [-f CONFIG] [-t] [-D]\n"
                      "  as root; -p overrides the Port of /etc/ssh/sshd_config, -t checks the configuration,\n"
                      "  -D stays in the foreground. Several servers on different ports may run at once.\n"
                      "  Stop: sudo pkill sshd. Start at boot: add a line 'sshd' to /etc/rc. Log: /var/log/auth.log");
            return 0;
        } else die("unknown option " + o + " (sshd --help)", 1);
    }
    if (geteuid() != 0) die("sshd must run as root (sudo sshd)", 1);
    struct stat st;
    if (configPath == CONFIG_FILE && stat(CONFIG_FILE, &st) != 0) {
        mkdir("/etc/ssh", 0755);
        if (FILE *f = std::fopen(CONFIG_FILE, "w")) {
            std::fputs(DEFAULT_CONFIG, f);
            std::fclose(f);
        }
    }
    ServerConfig c;
    std::string error;
    if (!readServerConfig(configPath, c, error)) {
        std::fputs(error.c_str(), stderr);
        return 1;
    }
    if (portOverride) {
        if (portOverride <= 0 || portOverride > 65535) die("bad port", 1);
        c.port = portOverride;
    }
    if (test) return 0;
    auto key = hostKey();
    if (!foreground) {
        // is the port free? (the daemon would only find out after we said it started)
        int probe = cc::net::listen(c.port);
        if (probe == -EADDRINUSE) die("port " + std::to_string(c.port) + " is in use (sshd already running? ss -l shows who listens)", 1);
        if (probe < 0) die("cannot listen on port " + std::to_string(c.port) + ": " + std::strerror(-probe), 1);
        close(probe);
        // a daemon: detached from the terminal, in its own process group
        cc::SpawnOptions options;
        options.flags = CC_SPAWN_NEW_GROUP;
        int null = open("/dev/null", O_RDWR);
        options.in = options.out = options.err = null;
        Args argv = {"/bin/sshd", "-D", "-p", std::to_string(c.port)};
        if (configPath != CONFIG_FILE) argv.insert(argv.end(), {"-f", configPath});
        int pid = cc::spawn(argv, options);
        if (pid < 0) die(std::string("cannot start: ") + std::strerror(-pid), 1);
        std::string target = c.port == 22 ? "ssh user@" + hostName() : "ssh -p " + std::to_string(c.port) + " user@" + hostName();
        std::printf("sshd: listening as %s port %d (%s), pid %d\n", hostName().c_str(), c.port, target.c_str(), pid);
        if (cc::Modem::all().empty()) std::printf("sshd: no modem attached yet: other computers reach this one once a modem is\n");
        return 0;
    }
    int listenFd = cc::net::listen(c.port);
    if (listenFd == -EADDRINUSE) die("port " + std::to_string(c.port) + " is in use (sshd already running? ss -l shows who listens)", 1);
    if (listenFd < 0) die("cannot listen on port " + std::to_string(c.port) + ": " + std::strerror(-listenFd), 1);
    cc::wantEvents();
    if (FILE *f = std::fopen(pidFile(c.port).c_str(), "w")) {
        std::fprintf(f, "%d\n", cc::pid());
        std::fclose(f);
    }
    logAuth("Server listening as " + hostName() + " port " + std::to_string(c.port));
    // in the foreground (-D) say so too, or a silent console looks hung
    if (isatty(1)) {
        std::printf("sshd: listening as %s port %d in the foreground (Ctrl+C stops it; without -D it runs in the background)\n",
                    hostName().c_str(), c.port);
        std::fflush(stdout);
    }
    std::vector<std::string> methods;
    if (c.pubkeyAuthentication) methods.push_back("publickey");
    if (c.passwordAuthentication) methods.push_back("password");
    std::string banner;
    if (!c.banner.empty()) {
        if (FILE *f = std::fopen(c.banner.c_str(), "r")) {
            char buf[256];
            while (std::fgets(buf, sizeof(buf), f)) banner += buf;
            std::fclose(f);
        }
    }
    std::map<int, Session> sessions; // by connection descriptor
    auto close = [&](std::map<int, Session>::iterator it) {
        for (int fd : {it->second.master, it->second.in, it->second.out, it->second.err}) {
            if (fd >= 0) ::close(fd); // hangs up the session's programs
        }
        ::close(it->first);
        return sessions.erase(it);
    };
    // one message of a session; false: the session is over
    auto handle = [&](Session &s, const std::string &frame) -> bool {
        if (!s.keyed) {
            auto msg = cc::Json::parse(frame).value_or(cc::Json());
            if (msg["t"].asString("") != "hello") return false;
            auto cepk = unbase64(msg["epk"].asString(""));
            auto cn = unbase64(msg["cn"].asString(""));
            if (cepk.size() != 32 || cn.size() != 16) return false;
            Bytes epriv(32), epub(32), shared(32);
            cc_x25519_keypair(epriv.data(), epub.data());
            if (cc_x25519(epriv.data(), cepk.data(), shared.data()) != 0) return false;
            auto sn = randomBytes(16);
            auto digest = sha256(transcript(cepk, epub, cn, sn, key.pub));
            Bytes sig(64);
            cc_ed25519_sign(key.seed.data(), digest.data(), 32, sig.data());
            auto methodList = cc::Json::array();
            for (auto &m : methods) methodList.push(m);
            s.ch.raw(cc::Json::object({{"t", "hello"}, {"name", hostName()}, {"epk", base64(epub)}, {"sn", base64(sn)},
                                       {"hk", base64(key.pub)}, {"sig", base64(sig)}, {"methods", methodList}, {"banner", banner}}));
            s.ch.receiveKey = hkdf(shared, digest, "c2s");
            s.ch.sendKey = hkdf(shared, digest, "s2c");
            s.digest = digest;
            s.keyed = true;
            return true;
        }
        auto inner = s.ch.open(frame);
        if (!inner) return false;
        auto kind = (*inner)["k"].asString("");
        if (kind == "auth" && !s.authenticated) {
            auto user = (*inner)["user"].asString("");
            auto method = (*inner)["method"].asString("password");
            auto problem = accountProblem(c, user, method);
            bool ok = false;
            std::string detail;
            if (problem.empty() && method == "publickey" && c.pubkeyAuthentication) {
                auto pub = unbase64((*inner)["pk"].asString(""));
                auto signature = unbase64((*inner)["sig"].asString(""));
                if (pub.size() == 32 && signature.size() == 64 && keyAuthorized(c, user, pub)) {
                    auto data = userAuthData(s.digest, user, pub);
                    ok = cc_ed25519_verify(pub.data(), data.data(), 32, signature.data()) == 0;
                }
                detail = pub.size() == 32 ? " ED25519 " + fingerprint(pub) : "";
            } else if (problem.empty() && method == "password" && c.passwordAuthentication) {
                ok = checkPassword(c, user, (*inner)["pw"].asString(""));
            }
            if (!problem.empty()) logAuth(problem + " from " + s.from);
            if (ok) {
                s.authenticated = true;
                s.user = user;
                logAuth("Accepted " + method + " for " + user + " from " + s.from + detail);
                s.ch.seal(cc::Json::object({{"k", "auth"}, {"ok", true}}));
                return true;
            }
            if (problem.empty()) logAuth("Failed " + method + " for " + user + " from " + s.from + detail);
            if (method == "password") cc::sleep(1); // slows down guessing
            if (++s.tries >= c.maxAuthTries) {
                logAuth("Disconnecting authenticating user " + user + " from " + s.from + ": too many authentication failures");
                s.ch.seal(cc::Json::object({{"k", "bye"}}));
                return false;
            }
            s.ch.seal(cc::Json::object({{"k", "auth"}, {"ok", false}, {"msg", "Permission denied, please try again."}}));
        } else if (kind == "session" && s.authenticated && !s.started) {
            startSession(c, s, *inner);
        } else if (kind == "in" && (s.master >= 0 || s.in >= 0)) {
            auto data = unbase64((*inner)["d"].asString(""));
            write(s.master >= 0 ? s.master : s.in, data.data(), data.size());
        } else if (kind == "eof" && s.in >= 0) {
            // the client's input ended: the program sees end of file
            ::close(s.in);
            s.in = -1;
        } else if (kind == "size" && s.master >= 0) {
            cc_pty_resize(s.master, (*inner)["cols"].asInt(80), (*inner)["rows"].asInt(24));
        } else if (kind == "ping") {
            s.ch.seal(cc::Json::object({{"k", "pong"}}));
        } else if (kind == "bye") {
            return false;
        }
        return true;
    };
    int housekeeping = cc::startTimer(c.clientAliveInterval);
    long idleLimit = static_cast<long>(c.clientAliveInterval) * c.clientAliveCountMax;
    while (true) {
        // new connections, every session's connection and output, and events (timers): whichever comes first
        std::vector<int> fds{listenFd};
        for (auto &[fd, s] : sessions) {
            fds.push_back(fd);
            for (int f : {s.master, s.out, s.err}) {
                if (f >= 0) fds.push_back(f);
            }
        }
        auto w = cc::waitAny(fds);
        if (w.fd == listenFd) {
            int fd = cc::net::accept(listenFd, false);
            if (fd < 0) continue;
            auto peer = cc::net::info(fd);
            auto from = peer ? peer->remote + " port " + std::to_string(peer->remotePort) : std::string("?");
            if (static_cast<int>(sessions.size()) >= c.maxSessions) {
                Channel refuse;
                refuse.fd = fd;
                refuse.raw(cc::Json::object({{"t", "refused"}, {"msg", "too many sessions"}}));
                ::close(fd);
                logAuth("refused connection from " + from + ": MaxSessions " + std::to_string(c.maxSessions));
                continue;
            }
            Session s;
            s.ch.fd = fd;
            s.from = from;
            s.lastHeard = std::time(nullptr);
            sessions[fd] = std::move(s);
            continue;
        }
        if (w.fd >= 0) {
            if (auto it = sessions.find(w.fd); it != sessions.end()) {
                auto &s = it->second;
                bool alive = s.ch.fill();
                s.lastHeard = std::time(nullptr);
                while (alive) {
                    auto f = s.ch.next();
                    if (!f) break;
                    alive = handle(s, *f);
                }
                if (!alive || s.ch.closed) close(it);
                continue;
            }
            for (auto &[fd, s] : sessions) {
                if (w.fd == s.master) {
                    if (!pump(s, s.master, "out")) endSession(s);
                } else if (w.fd == s.out || w.fd == s.err) {
                    bool isOut = w.fd == s.out;
                    if (!pump(s, w.fd, isOut ? "out" : "err")) {
                        ::close(w.fd);
                        (isOut ? s.out : s.err) = -1;
                        // both closed: the program is done
                        if (s.out < 0 && s.err < 0) endSession(s);
                    }
                } else {
                    continue;
                }
                break;
            }
            continue;
        }
        auto &e = w.event;
        if (!e) continue;
        if (e->name == "terminate") break;
        if (e->name == "timer" && e->args[0].asInt() == housekeeping) {
            // drop sessions whose client went away without a word (out of range, turned off)
            long now = std::time(nullptr);
            for (auto it = sessions.begin(); it != sessions.end();) {
                if (now - it->second.lastHeard > idleLimit) {
                    logAuth("Timeout, client not responding from " + it->second.from);
                    it = close(it);
                } else {
                    ++it;
                }
            }
            while (cc::wait(-1, nullptr, true) > 0) {
            }
            housekeeping = cc::startTimer(c.clientAliveInterval);
        }
    }
    for (auto it = sessions.begin(); it != sessions.end();) it = close(it);
    ::close(listenFd);
    unlink(pidFile(c.port).c_str());
    return 0;
}

} // namespace

int main(int argc, char **argv) {
    Args args(argv, argv + argc);
    auto slash = args[0].rfind('/');
    prog = slash == std::string::npos ? args[0] : args[0].substr(slash + 1);
    int r;
    if (prog == "sshd") r = server(args);
    else if (prog == "ssh-keygen") r = keygen(args);
    else if (prog == "ssh-copy-id") r = copyId(args);
    else if (prog == "scp") r = scp(args);
    else r = client(args);
    std::fflush(stdout);
    return r;
}
