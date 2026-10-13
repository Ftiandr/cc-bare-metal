// textutils: files and shell helpers - tar realpath readlink mktemp truncate dd tree chmod sync xargs which time
// timeout watch nohup expr factor cal nproc id tty printenv.
#include "textutils.hpp"

#include <baremetal.hpp>

#include <algorithm>
#include <cmath>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <random>
#include <sys/stat.h>
#include <grp.h>
#include <pwd.h>
#include <unistd.h>

extern char **environ;

namespace {

using namespace tu;

const std::string &prog() {
    return textutilsProg();
}

constexpr int SIGTERM_ = 15;
constexpr int SIGKILL_ = 9;

double now() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) / 1e9;
}

std::string joinPath(const std::string &dir, const std::string &name) {
    return dir.empty() || dir.back() == '/' ? dir + name : dir + "/" + name;
}

std::vector<std::string> listDir(const std::string &path) {
    std::vector<std::string> out;
    if (DIR *d = opendir(path.c_str())) {
        while (auto *e = readdir(d)) {
            std::string n = e->d_name;
            if (n != "." && n != "..") out.push_back(n);
        }
        closedir(d);
    }
    std::sort(out.begin(), out.end());
    return out;
}

bool isDir(const std::string &path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool mkdirs(const std::string &path) {
    if (path.empty() || isDir(path)) return true;
    auto slash = path.rfind('/');
    if (slash != std::string::npos && slash > 0 && !mkdirs(path.substr(0, slash))) return false;
    return mkdir(path.c_str(), 0755) == 0 || errno == EEXIST;
}

std::string normalize(const std::string &path) {
    std::string full = !path.empty() && path[0] == '/' ? path : joinPath(cc::cwd(), path);
    std::vector<std::string> parts;
    size_t start = 0;
    while (start <= full.size()) {
        auto end = full.find('/', start);
        if (end == std::string::npos) end = full.size();
        auto part = full.substr(start, end - start);
        if (part == "..") {
            if (!parts.empty()) parts.pop_back();
        } else if (!part.empty() && part != ".") {
            parts.push_back(part);
        }
        start = end + 1;
    }
    std::string out;
    for (auto &p : parts) out += "/" + p;
    return out.empty() ? "/" : out;
}

// ---- tar (ustar, uncompressed) ----------------------------------------------------------------------------------

struct TarOptions {
    bool verbose = false;
    std::string dir;
};

void octal(char *field, size_t size, unsigned long long value) {
    std::snprintf(field, size, "%0*llo", static_cast<int>(size - 1), value);
}

std::string tarHeader(const std::string &name, unsigned long long size, bool directory, long mtime) {
    char h[512];
    std::memset(h, 0, sizeof(h));
    std::string n = name, prefix;
    if (n.size() > 99) {
        auto cut = n.rfind('/', 154);
        if (cut == std::string::npos || n.size() - cut - 1 > 99) return {};
        prefix = n.substr(0, cut);
        n = n.substr(cut + 1);
    }
    std::memcpy(h, n.data(), n.size());
    octal(h + 100, 8, directory ? 0755 : 0644);
    octal(h + 108, 8, 0);
    octal(h + 116, 8, 0);
    octal(h + 124, 12, size);
    octal(h + 136, 12, static_cast<unsigned long long>(mtime));
    h[156] = directory ? '5' : '0';
    std::memcpy(h + 257, "ustar", 6);
    std::memcpy(h + 263, "00", 2);
    std::memcpy(h + 265, "user", 4);
    std::memcpy(h + 297, "user", 4);
    std::memcpy(h + 345, prefix.data(), prefix.size());
    std::memset(h + 148, ' ', 8);
    unsigned sum = 0;
    for (unsigned char c : h) sum += c;
    std::snprintf(h + 148, 8, "%06o", sum);
    h[155] = ' ';
    return std::string(h, sizeof(h));
}

bool tarAdd(FILE *out, const std::string &path, const std::string &name, const TarOptions &o) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) {
        warnErrno(path);
        return false;
    }
    bool ok = true;
    if (S_ISDIR(st.st_mode)) {
        auto header = tarHeader(name + "/", 0, true, st.st_mtime);
        if (header.empty()) return warn(name + ": name too long") == 0;
        std::fwrite(header.data(), 1, header.size(), out);
        if (o.verbose) std::fprintf(stderr, "%s/\n", name.c_str());
        for (auto &e : listDir(path)) ok &= tarAdd(out, joinPath(path, e), name + "/" + e, o);
        return ok;
    }
    auto data = readFile(path);
    if (!data) {
        warnErrno(path);
        return false;
    }
    auto header = tarHeader(name, data->size(), false, st.st_mtime);
    if (header.empty()) return warn(name + ": name too long") == 0;
    std::fwrite(header.data(), 1, header.size(), out);
    std::fwrite(data->data(), 1, data->size(), out);
    static const char zeros[512] = {};
    std::fwrite(zeros, 1, (512 - data->size() % 512) % 512, out);
    if (o.verbose) std::fprintf(stderr, "%s\n", name.c_str());
    return true;
}

int tarRead(const std::string &archive, bool extract, const TarOptions &o, const std::vector<std::string> &only) {
    auto data = readFile(archive);
    if (!data) return warnErrno(archive);
    size_t p = 0;
    int status = 0;
    while (p + 512 <= data->size()) {
        const char *h = data->data() + p;
        if (h[0] == 0) break;
        std::string name(h, strnlen(h, 100));
        std::string prefix(h + 345, strnlen(h + 345, 155));
        if (!prefix.empty()) name = prefix + "/" + name;
        unsigned long long size = std::strtoull(std::string(h + 124, 12).c_str(), nullptr, 8);
        char type = h[156];
        p += 512;
        if (p + size > data->size()) return warn(archive + ": truncated archive");
        bool wanted = only.empty();
        for (auto &w : only) wanted |= name == w || name.rfind(w + "/", 0) == 0 || name == w + "/";
        // Never write outside the target directory.
        bool unsafe = name.find("..") != std::string::npos || (!name.empty() && name[0] == '/');
        if (wanted && unsafe) {
            warn(name + ": skipping unsafe path");
            wanted = false;
        }
        if (wanted) {
            if (!extract) {
                if (o.verbose) std::printf("%c %10llu %s\n", type == '5' ? 'd' : '-', size, name.c_str());
                else std::puts(name.c_str());
            } else {
                auto target = o.dir.empty() ? name : joinPath(o.dir, name);
                if (o.verbose) std::puts(name.c_str());
                if (type == '5') {
                    if (!mkdirs(target.back() == '/' ? target.substr(0, target.size() - 1) : target)) status = warnErrno(target);
                } else if (type == '0' || type == '\0' || type == '7') {
                    auto slash = target.rfind('/');
                    if (slash != std::string::npos) mkdirs(target.substr(0, slash));
                    if (!writeFile(target, data->substr(p, size))) status = warnErrno(target);
                }
            }
        }
        p += (size + 511) / 512 * 512;
    }
    return status;
}

int cmdTar(Args &a) {
    const char *usage = "usage: tar -c|-x|-t [-v] -f ARCHIVE [-C DIR] [FILE...]   (ustar, no compression; - is stdin/stdout)";
    char mode = 0;
    std::string archive = "-";
    TarOptions o;
    std::vector<std::string> files;
    for (size_t i = 1; i < a.size(); i++) {
        auto opt = a[i];
        if (i == 1 && !opt.empty() && opt[0] != '-') opt = "-" + opt; // "tar cvf x.tar dir"
        if (opt.size() > 1 && opt[0] == '-' && opt != "-") {
            for (size_t k = 1; k < opt.size(); k++) {
                char c = opt[k];
                if (c == 'c' || c == 'x' || c == 't') mode = c;
                else if (c == 'v') o.verbose = true;
                else if (c == 'f') {
                    if (i + 1 >= a.size()) die(usage);
                    archive = a[++i];
                } else if (c == 'C') {
                    if (i + 1 >= a.size()) die(usage);
                    o.dir = a[++i];
                } else if (c == 'z' || c == 'j' || c == 'J') {
                    die("compression is not supported (plain tar only)");
                } else {
                    die(std::string("unknown option -") + c + "\n" + usage);
                }
            }
        } else {
            files.push_back(a[i]);
        }
    }
    if (!mode) die(usage);
    if (mode == 'c') {
        if (files.empty()) die("refusing to create an empty archive");
        FILE *out = archive == "-" ? stdout : std::fopen(archive.c_str(), "wb");
        if (!out) return warnErrno(archive);
        bool ok = true;
        for (auto &f : files) {
            auto source = o.dir.empty() ? f : joinPath(o.dir, f);
            auto name = f;
            while (name.size() > 1 && name.back() == '/') name.pop_back();
            while (name.rfind("./", 0) == 0) name = name.substr(2);
            if (!name.empty() && name[0] == '/') name = name.substr(1);
            ok &= tarAdd(out, source, name, o);
        }
        static const char zeros[1024] = {};
        std::fwrite(zeros, 1, sizeof(zeros), out);
        if (out != stdout) std::fclose(out);
        return ok ? 0 : 1;
    }
    if (mode == 'x' && !o.dir.empty() && !mkdirs(o.dir)) return warnErrno(o.dir);
    return tarRead(archive, mode == 'x', o, files);
}

// ---- small file tools -------------------------------------------------------------------------------------------

int cmdRealpath(Args &a) {
    int status = 0;
    for (size_t i = 1; i < a.size(); i++) {
        if (isOption(a[i])) continue;
        auto p = normalize(a[i]);
        struct stat st;
        if (stat(p.c_str(), &st) != 0 && prog() != "readlink") {
            status = warnErrno(a[i]);
            continue;
        }
        std::puts(p.c_str());
    }
    return status;
}

int cmdMktemp(Args &a) {
    bool directory = false;
    std::string tmpl = "tmp.XXXXXXXXXX";
    bool explicitTemplate = false;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-d") directory = true;
        else if (!isOption(a[i])) {
            tmpl = a[i];
            explicitTemplate = true;
        }
    }
    auto x = tmpl.find("XXX");
    if (x == std::string::npos) die("template must contain at least 3 X: " + tmpl);
    size_t count = 0;
    while (x + count < tmpl.size() && tmpl[x + count] == 'X') count++;
    std::mt19937 rng{std::random_device{}()};
    const char *chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    for (int attempt = 0; attempt < 100; attempt++) {
        auto name = tmpl;
        for (size_t k = 0; k < count; k++) name[x + k] = chars[rng() % 62];
        if (!explicitTemplate || name.find('/') == std::string::npos) {
            if (!explicitTemplate) name = "/tmp/" + name;
        }
        if (directory) {
            if (mkdir(name.c_str(), 0700) == 0) {
                std::puts(name.c_str());
                return 0;
            }
        } else {
            int fd = open(name.c_str(), O_CREAT | O_EXCL | O_WRONLY, 0600);
            if (fd >= 0) {
                close(fd);
                std::puts(name.c_str());
                return 0;
            }
        }
        if (errno != EEXIST) return warnErrno(name);
    }
    return warn("cannot create a unique name");
}

int cmdTruncate(Args &a) {
    long long size = -1;
    std::vector<std::string> files;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-s" && i + 1 < a.size()) {
            char *end;
            size = std::strtoll(a[++i].c_str(), &end, 10);
            if (*end == 'K' || *end == 'k') size *= 1024;
            if (*end == 'M' || *end == 'm') size *= 1024 * 1024;
        } else {
            files.push_back(a[i]);
        }
    }
    if (size < 0 || files.empty()) die("usage: truncate -s SIZE FILE...");
    int status = 0;
    for (auto &f : files) {
        int fd = open(f.c_str(), O_WRONLY | O_CREAT, 0644);
        if (fd < 0 || ftruncate(fd, size) != 0) status = warnErrno(f);
        if (fd >= 0) close(fd);
    }
    return status;
}

long long sizeArg(const std::string &v) {
    char *end;
    long long n = std::strtoll(v.c_str(), &end, 10);
    switch (*end) {
        case 'k': case 'K': n *= 1024; break;
        case 'M': n *= 1024 * 1024; break;
        case 'G': n *= 1024LL * 1024 * 1024; break;
        default: break;
    }
    return n;
}

int cmdDd(Args &a) {
    std::string in = "-", out = "-";
    long long bs = 512, count = -1, skip = 0, seek = 0;
    for (size_t i = 1; i < a.size(); i++) {
        auto eq = a[i].find('=');
        if (eq == std::string::npos) die("usage: dd [if=FILE] [of=FILE] [bs=N] [count=N] [skip=N] [seek=N]");
        auto k = a[i].substr(0, eq), v = a[i].substr(eq + 1);
        if (k == "if") in = v;
        else if (k == "of") out = v;
        else if (k == "bs") bs = std::max(1LL, sizeArg(v));
        else if (k == "count") count = sizeArg(v);
        else if (k == "skip") skip = sizeArg(v);
        else if (k == "seek") seek = sizeArg(v);
    }
    FILE *fi = in == "-" ? stdin : std::fopen(in.c_str(), "rb");
    if (!fi) return warnErrno(in);
    FILE *fo = out == "-" ? stdout : std::fopen(out.c_str(), "r+b");
    if (!fo && out != "-") fo = std::fopen(out.c_str(), "wb");
    if (!fo) return warnErrno(out);
    if (skip) std::fseek(fi, static_cast<long>(skip * bs), SEEK_SET);
    if (seek) std::fseek(fo, static_cast<long>(seek * bs), SEEK_SET);
    std::vector<char> buf(static_cast<size_t>(bs));
    long long full = 0, partial = 0, bytes = 0;
    double start = now();
    while (count < 0 || full + partial < count) {
        size_t n = std::fread(buf.data(), 1, buf.size(), fi);
        if (n == 0) break;
        std::fwrite(buf.data(), 1, n, fo);
        bytes += static_cast<long long>(n);
        (n == buf.size() ? full : partial)++;
    }
    if (fi != stdin) std::fclose(fi);
    if (fo != stdout) std::fclose(fo);
    double t = std::max(1e-6, now() - start);
    std::fprintf(stderr, "%lld+%lld records in\n%lld+%lld records out\n%lld bytes copied, %.3f s, %.1f kB/s\n", full, partial, full, partial,
                 bytes, t, static_cast<double>(bytes) / t / 1000);
    return 0;
}

void treeWalk(const std::string &path, const std::string &indent, int &dirs, int &files, bool all, int depth, int maxDepth) {
    auto entries = listDir(path);
    if (!all) entries.erase(std::remove_if(entries.begin(), entries.end(), [](const std::string &e) { return e[0] == '.'; }), entries.end());
    bool color = isatty(1);
    for (size_t i = 0; i < entries.size(); i++) {
        bool last = i + 1 == entries.size();
        auto full = joinPath(path, entries[i]);
        bool dir = isDir(full);
        std::printf("%s%s%s%s%s\n", indent.c_str(), last ? "└── " : "├── ", dir && color ? "\x1b[94m" : "",
                    entries[i].c_str(), dir && color ? "\x1b[0m" : "");
        if (dir) {
            dirs++;
            if (maxDepth < 0 || depth + 1 < maxDepth) treeWalk(full, indent + (last ? "    " : "│   "), dirs, files, all, depth + 1, maxDepth);
        } else {
            files++;
        }
    }
}

int cmdTree(Args &a) {
    bool all = false;
    int maxDepth = -1;
    std::vector<std::string> roots;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-a") all = true;
        else if (a[i] == "-L" && i + 1 < a.size()) maxDepth = std::atoi(a[++i].c_str());
        else roots.push_back(a[i]);
    }
    if (roots.empty()) roots.push_back(".");
    int dirs = 0, files = 0;
    for (auto &r : roots) {
        std::printf("%s\n", r.c_str());
        treeWalk(r, "", dirs, files, all, 0, maxDepth);
    }
    std::printf("\n%d director%s, %d file%s\n", dirs, dirs == 1 ? "y" : "ies", files, files == 1 ? "" : "s");
    return 0;
}

// ---- running commands -------------------------------------------------------------------------------------------

// Which: the first PATH entry with the command.
// Every match of NAME on $PATH (only the first unless `all`), as which(1).
std::vector<std::string> findCommands(const std::string &name, bool all) {
    std::vector<std::string> found;
    struct stat st;
    if (name.find('/') != std::string::npos) {
        if (stat(name.c_str(), &st) == 0 && S_ISREG(st.st_mode)) found.push_back(name);
        return found;
    }
    const char *path = std::getenv("PATH");
    std::string p = path ? path : "/usr/local/bin:/bin:/usr/bin:/sbin";
    size_t start = 0;
    while (start <= p.size()) {
        auto end = p.find(':', start);
        if (end == std::string::npos) end = p.size();
        auto dir = p.substr(start, end - start);
        if (!dir.empty()) {
            auto full = joinPath(dir, name);
            if (stat(full.c_str(), &st) == 0 && S_ISREG(st.st_mode)) {
                found.push_back(full);
                if (!all) break;
            }
        }
        start = end + 1;
    }
    return found;
}

// which [-a] NAME...: the path of each command on $PATH; status 1 if any is missing.
int cmdWhich(Args &a) {
    bool all = false;
    int status = 0;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "--help") {
            std::puts("usage: which [-a] NAME...   (-a: every match on $PATH)");
            return 0;
        }
        if (a[i] == "-a") {
            all = true;
            continue;
        }
        auto found = findCommands(a[i], all);
        for (auto &f : found) std::puts(f.c_str());
        if (found.empty()) status = 1;
    }
    return status;
}

// ---- file -------------------------------------------------------------------------------------------------------

struct FileKind {
    std::string text, mime, charset;
};

uint32_t le32(const std::string &d, size_t at) {
    if (at + 4 > d.size()) return 0;
    return static_cast<uint8_t>(d[at]) | static_cast<uint8_t>(d[at + 1]) << 8 | static_cast<uint8_t>(d[at + 2]) << 16 |
           static_cast<uint32_t>(static_cast<uint8_t>(d[at + 3])) << 24;
}

uint32_t be32(const std::string &d, size_t at) {
    if (at + 4 > d.size()) return 0;
    return static_cast<uint32_t>(static_cast<uint8_t>(d[at])) << 24 | static_cast<uint8_t>(d[at + 1]) << 16 |
           static_cast<uint8_t>(d[at + 2]) << 8 | static_cast<uint8_t>(d[at + 3]);
}

// LEB128 as in WebAssembly section headers
uint32_t leb(const std::string &d, size_t &at) {
    uint32_t v = 0;
    for (int shift = 0; at < d.size() && shift < 35; shift += 7) {
        auto b = static_cast<uint8_t>(d[at++]);
        v |= static_cast<uint32_t>(b & 0x7f) << shift;
        if (!(b & 0x80)) break;
    }
    return v;
}

// A WebAssembly module: an object file (it has a "linking" section, from cc -c) or a program.
std::string wasmKind(const std::string &d) {
    size_t at = 8;
    bool start = false;
    while (at < d.size()) {
        auto id = static_cast<uint8_t>(d[at++]);
        auto size = leb(d, at);
        auto end = at + size;
        if (id == 0) {
            auto p = at;
            auto len = leb(d, p);
            if (p + len <= d.size()) {
                auto name = d.substr(p, len);
                if (name.rfind("linking", 0) == 0) return "relocatable object";
            }
        }
        if (id == 7) start = true; // exports: _start or not, it is a module meant to run
        if (end > d.size() || end <= at - 1) break;
        at = end;
    }
    (void)start;
    return "binary module";
}

std::string textKind(const std::string &path, const std::string &d, std::string &charset) {
    bool ascii = true, crlf = false;
    size_t longest = 0, current = 0, lines = 0;
    for (size_t i = 0; i < d.size(); i++) {
        auto c = static_cast<uint8_t>(d[i]);
        if (c >= 0x80) ascii = false;
        if (c == '\n') {
            if (i > 0 && d[i - 1] == '\r') crlf = true;
            longest = std::max(longest, current);
            current = 0;
            lines++;
        } else {
            current++;
        }
    }
    longest = std::max(longest, current);
    charset = ascii ? "us-ascii" : "utf-8";
    auto dot = path.rfind('.');
    auto ext = dot == std::string::npos ? std::string() : path.substr(dot + 1);
    std::string kind;
    if (ext == "c" || ext == "h") kind = "C source, ";
    else if (ext == "cpp" || ext == "cc" || ext == "cxx" || ext == "hpp" || ext == "hh") kind = "C++ source, ";
    else if (ext == "lua") kind = "Lua script, ";
    else if (ext == "sh") kind = "POSIX shell script, ";
    else if (ext == "py") kind = "Python script, ";
    else if (ext == "json" && !d.empty() && (d[0] == '{' || d[0] == '[')) return std::string("JSON text data");
    else if (ext == "md") kind = "Markdown ";
    std::string out = kind + (ascii ? "ASCII text" : "Unicode text, UTF-8 text");
    if (longest > 300) out += ", with very long lines (" + std::to_string(longest) + ")";
    if (crlf) out += ", with CRLF line terminators";
    if (lines == 0) out += ", with no line terminators";
    return out;
}

bool looksLikeText(const std::string &d) {
    // valid UTF-8 without NUL bytes or other control characters than whitespace, backspace and ESC
    bool truncated = d.size() == 4096; // a character may be cut at the end of what was read
    for (size_t i = 0; i < d.size();) {
        auto c = static_cast<uint8_t>(d[i]);
        if (c < 0x80) {
            if (c == 0 || (c < 32 && c != '\n' && c != '\r' && c != '\t' && c != '\f' && c != 27 && c != '\b')) return false;
            i++;
            continue;
        }
        int n = c >= 0xf0 && c < 0xf8 ? 3 : c >= 0xe0 && c < 0xf0 ? 2 : c >= 0xc0 && c < 0xe0 ? 1 : -1;
        if (n < 0) return false;
        for (int k = 1; k <= n; k++) {
            if (i + k >= d.size()) return truncated;
            if ((static_cast<uint8_t>(d[i + k]) & 0xc0) != 0x80) return false;
        }
        i += static_cast<size_t>(n) + 1;
    }
    return true;
}

FileKind classify(const std::string &path) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return {std::string("cannot open `") + path + "' (" + std::strerror(errno) + ")", "", ""};
    if (S_ISDIR(st.st_mode)) return {"directory", "inode/directory", "binary"};
    if (S_ISCHR(st.st_mode)) return {"character special", "inode/chardevice", "binary"};
    if (st.st_size == 0) return {"empty", "inode/x-empty", "binary"};
    std::string d;
    if (FILE *f = std::fopen(path.c_str(), "rb")) {
        char buf[4096];
        auto n = std::fread(buf, 1, sizeof(buf), f);
        d.assign(buf, n);
        std::fclose(f);
    } else {
        return {std::string("regular file, no read permission"), "application/octet-stream", "binary"};
    }
    bool executable = st.st_mode & 0111;
    auto starts = [&](const char *magic, size_t len) { return d.size() >= len && std::memcmp(d.data(), magic, len) == 0; };
    if (starts("\0asm", 4)) {
        auto kind = wasmKind(d);
        return {"WebAssembly (wasm) " + kind + ", version " + std::to_string(le32(d, 4)) + (kind == "binary module" ? " (CC: Bare Metal program)" : ""),
                "application/wasm", "binary"};
    }
    if (starts("#!@", 3)) {
        auto end = d.find('\n');
        return {"multi-call program alias to " + d.substr(3, end == std::string::npos ? std::string::npos : end - 3), "text/plain", "us-ascii"};
    }
    if (starts("#!", 2)) {
        auto end = d.find('\n');
        auto line = d.substr(2, end == std::string::npos ? std::string::npos : end - 2);
        while (!line.empty() && line[0] == ' ') line.erase(0, 1);
        auto interp = line.substr(0, line.find(' '));
        if (interp.find("env") != std::string::npos && line.find(' ') != std::string::npos) interp = line.substr(line.find(' ') + 1);
        auto name = interp.substr(interp.rfind('/') + 1);
        std::string charset;
        auto text = textKind("", d, charset);
        std::string kind = name == "sh" || name == "bash" ? "POSIX shell script" : name == "lua" ? "Lua script" : "a " + interp + " script";
        return {kind + ", " + text + (executable ? " executable" : ""), name == "sh" ? "text/x-shellscript" : "text/plain", charset};
    }
    if (starts("!<arch>\n", 8)) return {"current ar archive", "application/x-archive", "binary"};
    if (starts("CCPKG1\n", 7)) {
        auto end = d.find('\n', 7);
        auto manifest = cc::Json::parse(d.substr(7, end == std::string::npos ? std::string::npos : end - 7));
        std::string what = "CC: Bare Metal package";
        if (manifest) what += ", " + (*manifest)["name"].asString("?") + " " + (*manifest)["version"].asString("");
        return {what, "application/x-ccpkg", "binary"};
    }
    if (starts("\x89PNG\r\n\x1a\n", 8) && d.size() >= 26) {
        static const char *colors[] = {"grayscale", "?", "RGB", "colormap", "gray+alpha", "?", "RGBA"};
        auto type = static_cast<uint8_t>(d[25]);
        return {"PNG image data, " + std::to_string(be32(d, 16)) + " x " + std::to_string(be32(d, 20)) + ", " +
                    std::to_string(static_cast<uint8_t>(d[24])) + "-bit" + (type < 7 ? std::string("/") + colors[type] : ""),
                "image/png", "binary"};
    }
    if (starts("GIF87a", 6) || starts("GIF89a", 6)) {
        int w = static_cast<uint8_t>(d[6]) | static_cast<uint8_t>(d[7]) << 8, h = static_cast<uint8_t>(d[8]) | static_cast<uint8_t>(d[9]) << 8;
        return {"GIF image data, version " + d.substr(3, 3) + ", " + std::to_string(w) + " x " + std::to_string(h), "image/gif", "binary"};
    }
    if (starts("\xff\xd8\xff", 3)) return {"JPEG image data", "image/jpeg", "binary"};
    if (starts("%PDF-", 5)) return {"PDF document, version " + d.substr(5, 3), "application/pdf", "binary"};
    if (starts("PK\x03\x04", 4)) return {"Zip archive data", "application/zip", "binary"};
    if (starts("\x1f\x8b", 2)) return {"gzip compressed data", "application/gzip", "binary"};
    if (starts("\x7f" "ELF", 4)) return {"ELF executable (Linux machine code; this computer runs WebAssembly)", "application/x-executable", "binary"};
    if (starts("RIFF", 4) && d.size() >= 12 && d.compare(8, 4, "WAVE") == 0) return {"RIFF (little-endian) data, WAVE audio", "audio/x-wav", "binary"};
    if (starts("OggS", 4)) return {"Ogg data", "audio/ogg", "binary"};
    if (starts("ID3", 3)) return {"Audio file with ID3 version 2, MPEG layer III", "audio/mpeg", "binary"};
    if (starts("\x1bLua", 4)) return {"Lua bytecode", "application/x-lua-bytecode", "binary"};
    if (d.size() > 262 && d.compare(257, 5, "ustar") == 0) return {"POSIX tar archive", "application/x-tar", "binary"};
    if (path.size() > 6 && path.substr(path.size() - 6) == ".dfpwm") return {"DFPWM audio (ComputerCraft)", "audio/x-dfpwm", "binary"};
    if (looksLikeText(d)) {
        std::string charset;
        auto text = textKind(path, d, charset);
        auto mime = text.rfind("C source", 0) == 0 ? "text/x-c" : text.rfind("C++", 0) == 0 ? "text/x-c++" : text.rfind("JSON", 0) == 0 ? "application/json" : "text/plain";
        return {text, mime, charset};
    }
    return {"data", "application/octet-stream", "binary"};
}

// file [-b] [-i | --mime-type] FILE...: what a file contains, from its first bytes, as file(1).
int cmdFile(Args &a) {
    bool brief = false, mime = false, mimeType = false;
    Args files;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        if (o == "--help") {
            std::puts("usage: file [-b] [-i | --mime-type] FILE...   (-b without the name, -i MIME type and charset)");
            return 0;
        }
        if (o == "--mime" || o == "-i") mime = true;
        else if (o == "--mime-type") mimeType = true;
        else if (o == "--brief") brief = true;
        else if (o.size() > 1 && o[0] == '-' && o.find_first_not_of("bLzhi", 1) == std::string::npos) {
            brief |= o.find('b') != std::string::npos;
            mime |= o.find('i') != std::string::npos;
        } else files.push_back(o);
    }
    if (files.empty()) tu::die("usage: file [-b] [-i] FILE...");
    size_t width = 0;
    for (auto &f : files) width = std::max(width, f.size());
    int status = 0;
    for (auto &f : files) {
        auto t = classify(f);
        if (t.mime.empty()) status = 1;
        std::string what = mimeType && !t.mime.empty() ? t.mime : mime && !t.mime.empty() ? t.mime + "; charset=" + t.charset : t.text;
        if (brief) std::printf("%s\n", what.c_str());
        else std::printf("%s:%*s %s\n", f.c_str(), static_cast<int>(width - f.size()), "", what.c_str());
    }
    return status;
}

int cmdXargs(Args &a) {
    size_t maxArgs = 0;
    std::string replace;
    bool nulls = false, trace = false, noEmpty = false;
    char delim = 0;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-n" && i + 1 < a.size()) maxArgs = std::strtoul(a[++i].c_str(), nullptr, 10);
        else if (a[i] == "-I" && i + 1 < a.size()) replace = a[++i];
        else if (a[i] == "-0") nulls = true;
        else if (a[i] == "-t") trace = true;
        else if (a[i] == "-r") noEmpty = true;
        else if (a[i] == "-d" && i + 1 < a.size()) delim = a[++i][0];
        else die("usage: xargs [-n N] [-I STR] [-0] [-d C] [-t] [-r] [COMMAND [ARGS...]]");
    }
    Args base(a.begin() + static_cast<long>(i), a.end());
    if (base.empty()) base.push_back("echo");
    auto data = readFile("-").value_or("");
    std::vector<std::string> items;
    if (nulls || delim) {
        char d = nulls ? '\0' : delim;
        size_t start = 0;
        while (start < data.size()) {
            auto end = data.find(d, start);
            if (end == std::string::npos) end = data.size();
            items.push_back(data.substr(start, end - start));
            start = end + 1;
        }
        if (!nulls && !items.empty() && !items.back().empty() && items.back().back() == '\n') items.back().pop_back();
    } else if (!replace.empty()) {
        size_t start = 0;
        while (start < data.size()) {
            auto end = data.find('\n', start);
            if (end == std::string::npos) end = data.size();
            if (end > start) items.push_back(data.substr(start, end - start));
            start = end + 1;
        }
    } else {
        // whitespace separated, with simple quotes
        std::string cur;
        bool have = false;
        char quote = 0;
        for (size_t k = 0; k < data.size(); k++) {
            char c = data[k];
            if (quote) {
                if (c == quote) quote = 0;
                else cur += c;
            } else if (c == '\'' || c == '"') {
                quote = c;
                have = true;
            } else if (c == '\\' && k + 1 < data.size()) {
                cur += data[++k];
                have = true;
            } else if (std::isspace(static_cast<unsigned char>(c))) {
                if (have) items.push_back(cur);
                cur.clear();
                have = false;
            } else {
                cur += c;
                have = true;
            }
        }
        if (have) items.push_back(cur);
    }
    if (items.empty() && noEmpty) return 0;
    int result = 0;
    auto runOne = [&](const Args &cmd) {
        if (trace) {
            for (auto &c : cmd) std::fprintf(stderr, "%s ", c.c_str());
            std::fprintf(stderr, "\n");
        }
        std::fflush(stdout);
        int r = cc::run(cmd);
        if (r < 0) {
            warn(cmd[0] + ": command not found");
            result = 127;
        } else if (r != 0) {
            result = 123;
        }
    };
    if (!replace.empty()) {
        for (auto &item : items) {
            Args cmd;
            for (auto arg : base) {
                size_t p;
                while ((p = arg.find(replace)) != std::string::npos) arg.replace(p, replace.size(), item);
                cmd.push_back(arg);
            }
            runOne(cmd);
        }
        return result;
    }
    if (items.empty()) {
        runOne(base);
        return result;
    }
    size_t per = maxArgs ? maxArgs : 5000;
    for (size_t k = 0; k < items.size(); k += per) {
        Args cmd = base;
        cmd.insert(cmd.end(), items.begin() + static_cast<long>(k), items.begin() + static_cast<long>(std::min(items.size(), k + per)));
        runOne(cmd);
    }
    return result;
}

int cmdTime(Args &a) {
    if (a.size() < 2 || a[1] == "--help") {
        std::puts("usage: time COMMAND [ARGS...]   (prints the real time it took to stderr)");
        return a.size() < 2 ? 1 : 0;
    }
    Args cmd(a.begin() + 1, a.end());
    double start = now();
    std::fflush(stdout);
    int r = cc::run(cmd);
    double t = now() - start;
    std::fprintf(stderr, "\nreal\t%dm%.3fs\n", static_cast<int>(t / 60), std::fmod(t, 60.0));
    if (r < 0) {
        warn(cmd[0] + ": command not found");
        return 127;
    }
    return r;
}

int cmdTimeout(Args &a) {
    size_t i = 1;
    int signal = SIGTERM_;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-s" && i + 1 < a.size()) {
            auto s = a[++i];
            signal = s == "KILL" || s == "9" ? SIGKILL_ : s == "INT" || s == "2" ? 2 : SIGTERM_;
        }
    }
    if (i + 1 >= a.size()) die("usage: timeout [-s SIGNAL] SECONDS COMMAND [ARGS...]");
    double limit = std::atof(a[i].c_str());
    Args cmd(a.begin() + static_cast<long>(i) + 1, a.end());
    std::fflush(stdout);
    int pid = cc::spawn(cmd);
    if (pid < 0) {
        warn(cmd[0] + ": command not found");
        return 127;
    }
    double deadline = now() + limit;
    while (true) {
        int st = 0;
        int r = cc::wait(pid, &st, true);
        if (r == pid) return st;
        if (r < 0) return 1;
        if (now() >= deadline) {
            cc_kill(pid, signal);
            double grace = now() + 2;
            while (cc::wait(pid, &st, true) == 0 && now() < grace) cc::sleep(0.05);
            if (cc::wait(pid, &st, true) == 0) {
                cc_kill(pid, SIGKILL_);
                cc::wait(pid, &st);
            }
            return 124;
        }
        cc::sleep(0.05);
    }
}

int cmdWatch(Args &a) {
    double interval = 2;
    size_t i = 1;
    if (i + 1 < a.size() && a[i] == "-n") {
        interval = std::max(0.1, std::atof(a[i + 1].c_str()));
        i += 2;
    }
    if (i >= a.size()) die("usage: watch [-n SECONDS] COMMAND...");
    std::string command;
    for (size_t k = i; k < a.size(); k++) command += (k > i ? " " : "") + a[k];
    while (true) {
        std::printf("\x1b[H\x1b[2J\x1b[3JEvery %.1fs: %s\n\n", interval, command.c_str());
        std::fflush(stdout);
        cc::run({"sh", "-c", command});
        cc::sleep(interval);
    }
}

int cmdNohup(Args &a) {
    if (a.size() < 2 || a[1] == "--help") {
        std::puts("usage: nohup COMMAND [ARGS...]");
        return a.size() < 2 ? 1 : 0;
    }
    Args cmd(a.begin() + 1, a.end());
    std::fflush(stdout);
    int r = cc::run(cmd);
    return r < 0 ? 127 : r;
}

// ---- expr / factor / cal ----------------------------------------------------------------------------------------

class Expr {
public:
    explicit Expr(const Args &a) : a_(a), i_(1) {}
    std::string parse() {
        auto v = orExpr();
        if (i_ < a_.size()) die("syntax error near '" + a_[i_] + "'");
        return v;
    }

private:
    const Args &a_;
    size_t i_;

    bool peek(const char *op) const { return i_ < a_.size() && a_[i_] == op; }
    static bool isInt(const std::string &s, long long &v) {
        if (s.empty()) return false;
        char *end;
        v = std::strtoll(s.c_str(), &end, 10);
        return *end == '\0';
    }
    static bool truthy(const std::string &s) {
        long long v;
        return !s.empty() && !(isInt(s, v) && v == 0);
    }
    long long num(const std::string &s) const {
        long long v;
        if (!isInt(s, v)) die("non-integer argument");
        return v;
    }
    std::string orExpr() {
        auto l = andExpr();
        while (peek("|")) {
            i_++;
            auto r = andExpr();
            l = truthy(l) ? l : truthy(r) ? r : "0";
        }
        return l;
    }
    std::string andExpr() {
        auto l = cmp();
        while (peek("&")) {
            i_++;
            auto r = cmp();
            l = truthy(l) && truthy(r) ? l : "0";
        }
        return l;
    }
    std::string cmp() {
        auto l = add();
        for (const char *op : {"=", "!=", "<", "<=", ">", ">="}) {
            if (peek(op)) {
                i_++;
                auto r = add();
                long long x, y;
                int c = isInt(l, x) && isInt(r, y) ? (x < y ? -1 : x > y) : l.compare(r);
                std::string o = op;
                bool res = o == "=" ? c == 0 : o == "!=" ? c != 0 : o == "<" ? c < 0 : o == "<=" ? c <= 0 : o == ">" ? c > 0 : c >= 0;
                return res ? "1" : "0";
            }
        }
        return l;
    }
    std::string add() {
        auto l = mul();
        while (peek("+") || peek("-")) {
            bool plus = a_[i_++] == "+";
            auto r = mul();
            l = std::to_string(plus ? num(l) + num(r) : num(l) - num(r));
        }
        return l;
    }
    std::string mul() {
        auto l = match();
        while (peek("*") || peek("/") || peek("%")) {
            auto op = a_[i_++];
            auto r = match();
            long long x = num(l), y = num(r);
            if (op != "*" && y == 0) die("division by zero");
            l = std::to_string(op == "*" ? x * y : op == "/" ? x / y : x % y);
        }
        return l;
    }
    std::string match() {
        auto l = primary();
        while (peek(":")) {
            i_++;
            auto pattern = primary();
            Regex re("^(" + pattern + ")", 0);
            regmatch_t m[2];
            if (!re.ok()) die("bad regex");
            Regex groups("^" + pattern, 0);
            if (groups.search(l.c_str(), m, 2)) {
                l = m[1].rm_so >= 0 ? l.substr(static_cast<size_t>(m[1].rm_so), static_cast<size_t>(m[1].rm_eo - m[1].rm_so))
                                    : std::to_string(m[0].rm_eo);
            } else {
                l = pattern.find("\\(") != std::string::npos ? "" : "0";
            }
        }
        return l;
    }
    std::string primary() {
        if (i_ >= a_.size()) die("missing argument");
        if (peek("(")) {
            i_++;
            auto v = orExpr();
            if (!peek(")")) die("expected )");
            i_++;
            return v;
        }
        if (peek("length") && i_ + 1 < a_.size()) {
            i_++;
            return std::to_string(primary().size());
        }
        if (peek("substr") && i_ + 3 < a_.size()) {
            i_++;
            auto s = primary();
            long long pos = num(primary()), len = num(primary());
            if (pos < 1 || len < 1 || static_cast<size_t>(pos) > s.size()) return "";
            return s.substr(static_cast<size_t>(pos - 1), static_cast<size_t>(len));
        }
        if (peek("index") && i_ + 2 < a_.size()) {
            i_++;
            auto s = primary(), chars = primary();
            auto p = s.find_first_of(chars);
            return std::to_string(p == std::string::npos ? 0 : p + 1);
        }
        return a_[i_++];
    }
};

int cmdExpr(Args &a) {
    if (a.size() < 2) die("usage: expr EXPRESSION");
    auto v = Expr(a).parse();
    std::puts(v.c_str());
    long long n;
    char *end;
    n = std::strtoll(v.c_str(), &end, 10);
    return v.empty() || (*end == '\0' && n == 0) ? 1 : 0;
}

int cmdFactor(Args &a) {
    std::vector<std::string> nums(a.begin() + 1, a.end());
    if (nums.empty()) {
        auto text = readFile("-").value_or("");
        std::string cur;
        for (char c : text + " ") {
            if (std::isspace(static_cast<unsigned char>(c))) {
                if (!cur.empty()) nums.push_back(cur);
                cur.clear();
            } else {
                cur += c;
            }
        }
    }
    for (auto &s : nums) {
        unsigned long long n = std::strtoull(s.c_str(), nullptr, 10);
        std::printf("%llu:", n);
        for (unsigned long long d = 2; d * d <= n; d += d == 2 ? 1 : 2) {
            while (n % d == 0) {
                std::printf(" %llu", d);
                n /= d;
            }
        }
        if (n > 1) std::printf(" %llu", n);
        std::puts("");
    }
    return 0;
}

int cmdCal(Args &a) {
    std::time_t t = std::time(nullptr);
    std::tm now = *std::localtime(&t);
    int month = now.tm_mon + 1, year = now.tm_year + 1900;
    if (a.size() == 2) year = std::atoi(a[1].c_str());
    if (a.size() >= 3) {
        month = std::atoi(a[1].c_str());
        year = std::atoi(a[2].c_str());
    }
    static const char *const names[] = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
                                        "November", "December"};
    auto days = [](int m, int y) {
        static const int d[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        bool leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
        return m == 2 && leap ? 29 : d[m - 1];
    };
    auto weekday = [](int d, int m, int y) { // 0 = Sunday (Zeller-like, Sakamoto)
        static const int tbl[] = {0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4};
        if (m < 3) y--;
        return (y + y / 4 - y / 100 + y / 400 + tbl[m - 1] + d) % 7;
    };
    auto printMonth = [&](int m) {
        std::string title = std::string(names[m - 1]) + " " + std::to_string(year);
        std::printf("%*s%s\n", static_cast<int>((20 - title.size()) / 2), "", title.c_str());
        std::printf("Su Mo Tu We Th Fr Sa\n");
        int first = weekday(1, m, year);
        bool color = isatty(1);
        for (int k = 0; k < first; k++) std::printf("   ");
        for (int d = 1; d <= days(m, year); d++) {
            bool today = color && d == now.tm_mday && m == now.tm_mon + 1 && year == now.tm_year + 1900;
            std::printf(today ? "\x1b[7m%2d\x1b[0m" : "%2d", d);
            std::printf((first + d) % 7 == 0 ? "\n" : " ");
        }
        if ((first + days(m, year)) % 7 != 0) std::puts("");
    };
    if (a.size() == 2) {
        for (int m = 1; m <= 12; m++) {
            printMonth(m);
            std::puts("");
        }
    } else {
        printMonth(month);
    }
    return 0;
}

std::string userName(uid_t uid) {
    auto *pw = getpwuid(uid);
    return pw ? pw->pw_name : std::to_string(uid);
}

std::string groupName(gid_t gid) {
    auto *gr = getgrgid(gid);
    return gr ? gr->gr_name : std::to_string(gid);
}

// id [-u|-g|-G] [-n] [-r] [USER]: as GNU id ("uid=1000(user) gid=1000(user) groups=1000(user),10(wheel)").
int cmdId(Args &a) {
    char only = 0;
    bool names = false, real = false;
    std::string user;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "--help") {
            std::puts("usage: id [-u | -g | -G] [-n] [-r] [USER]");
            return 0;
        }
        if (a[i][0] != '-') {
            user = a[i];
            continue;
        }
        for (char f : a[i].substr(1)) {
            if (f == 'u' || f == 'g' || f == 'G') only = f;
            else if (f == 'n') names = true;
            else if (f == 'r') real = true;
            else tu::die(std::string("invalid option -- '") + f + "'");
        }
    }
    uid_t uid, euid;
    gid_t gid, egid;
    std::vector<gid_t> groups;
    if (!user.empty()) {
        auto *pw = getpwnam(user.c_str());
        if (!pw) tu::die("'" + user + "': no such user");
        uid = euid = pw->pw_uid;
        gid = egid = pw->pw_gid;
        gid_t list[64];
        int n = 64;
        getgrouplist(pw->pw_name, pw->pw_gid, list, &n);
        groups.assign(list, list + std::min(n, 64));
    } else {
        uid = getuid();
        euid = geteuid();
        gid = getgid();
        egid = getegid();
        gid_t list[64];
        int n = getgroups(64, list);
        groups.push_back(egid);
        for (int k = 0; k < n; k++) if (list[k] != egid) groups.push_back(list[k]);
    }
    auto show = [&](unsigned id, const std::string &name) { return names ? name : std::to_string(id); };
    if (only == 'u') return std::puts(show(real ? uid : euid, userName(real ? uid : euid)).c_str()) < 0;
    if (only == 'g') return std::puts(show(real ? gid : egid, groupName(real ? gid : egid)).c_str()) < 0;
    if (only == 'G') {
        std::string out;
        for (auto g : groups) out += (out.empty() ? "" : " ") + show(g, groupName(g));
        return std::puts(out.c_str()) < 0;
    }
    std::string out = "uid=" + std::to_string(uid) + "(" + userName(uid) + ") gid=" + std::to_string(gid) + "(" + groupName(gid) + ")";
    if (euid != uid) out += " euid=" + std::to_string(euid) + "(" + userName(euid) + ")";
    if (egid != gid) out += " egid=" + std::to_string(egid) + "(" + groupName(egid) + ")";
    out += " groups=";
    for (size_t k = 0; k < groups.size(); k++) out += (k ? "," : "") + std::to_string(groups[k]) + "(" + groupName(groups[k]) + ")";
    std::puts(out.c_str());
    return 0;
}

// A chmod mode: octal (755) or symbolic (u+x,go-w,a=r,+X,u+s,o+t...) applied to `old`.
bool parseMode(const std::string &spec, mode_t old, bool dir, mode_t &out) {
    if (!spec.empty() && spec.find_first_not_of("01234567") == std::string::npos) {
        out = static_cast<mode_t>(std::strtoul(spec.c_str(), nullptr, 8)) & 07777;
        // like GNU: a short octal mode on a directory keeps its setgid/setuid bits
        if (dir && spec.size() <= 3) out |= old & 06000;
        return true;
    }
    mode_t mode = old & 07777;
    mode_t mask = umask(0);
    umask(mask);
    size_t i = 0;
    while (i <= spec.size()) {
        mode_t who = 0;
        bool whoGiven = false;
        for (; i < spec.size() && std::strchr("ugoa", spec[i]); i++) {
            whoGiven = true;
            who |= spec[i] == 'u' ? 04700 : spec[i] == 'g' ? 02070 : spec[i] == 'o' ? 01007 : 07777;
        }
        if (!whoGiven) who = 07777;
        if (i >= spec.size() || !std::strchr("+-=", spec[i])) return false;
        while (i < spec.size() && std::strchr("+-=", spec[i])) {
            char op = spec[i++];
            mode_t perm = 0;
            for (; i < spec.size() && std::strchr("rwxXstugo", spec[i]); i++) {
                switch (spec[i]) {
                    case 'r': perm |= 0444; break;
                    case 'w': perm |= 0222; break;
                    case 'x': perm |= 0111; break;
                    case 'X': if (dir || (mode & 0111)) perm |= 0111; break;
                    case 's': perm |= 06000; break;
                    case 't': perm |= 01000; break;
                    case 'u': perm |= ((mode >> 6) & 7) * 0111; break;
                    case 'g': perm |= ((mode >> 3) & 7) * 0111; break;
                    case 'o': perm |= (mode & 7) * 0111; break;
                }
            }
            perm &= who;
            // without u/g/o/a, + and = respect the umask (POSIX)
            if (!whoGiven && op != '-') perm &= ~mask | 07000;
            if (op == '+') mode |= perm;
            else if (op == '-') mode &= ~perm;
            else mode = (mode & ~(who & 0777) & ~(who & 07000 & ~01000)) | perm;
        }
        if (i < spec.size() && spec[i] == ',') {
            i++;
            continue;
        }
        if (i == spec.size()) break;
        return false;
    }
    out = mode;
    return true;
}

int chmodTree(const std::string &path, const std::string &spec, bool recursive, bool verbose) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return tu::warnErrno(path);
    mode_t mode;
    if (!parseMode(spec, st.st_mode, S_ISDIR(st.st_mode), mode)) tu::die("invalid mode: '" + spec + "'");
    int status = 0;
    if (chmod(path.c_str(), mode) != 0) status = tu::warnErrno("changing permissions of '" + path + "'");
    else if (verbose) std::printf("mode of '%s' changed to %04o\n", path.c_str(), static_cast<unsigned>(mode));
    if (recursive && S_ISDIR(st.st_mode)) {
        if (DIR *d = opendir(path.c_str())) {
            std::vector<std::string> names;
            while (auto *e = readdir(d)) {
                std::string n = e->d_name;
                if (n != "." && n != "..") names.push_back(n);
            }
            closedir(d);
            for (auto &n : names) status |= chmodTree(path + "/" + n, spec, true, verbose);
        }
    }
    return status;
}

// chmod [-R] [-v] MODE FILE...
int cmdChmod(Args &a) {
    bool recursive = false, verbose = false;
    size_t i = 1;
    for (; i < a.size() && a[i].size() > 1 && a[i][0] == '-' && a[i].find_first_not_of("Rvf", 1) == std::string::npos; i++) {
        recursive |= a[i].find('R') != std::string::npos;
        verbose |= a[i].find('v') != std::string::npos;
    }
    if (i < a.size() && a[i] == "--help") {
        std::puts("usage: chmod [-R] [-v] MODE FILE...   (MODE: 755, u+x, go-w, a=r, +X, u+s, +t; several with commas)");
        return 0;
    }
    if (a.size() - i < 2) tu::die("usage: chmod [-R] MODE FILE...");
    int status = 0;
    for (size_t k = i + 1; k < a.size(); k++) status |= chmodTree(a[k], a[i], recursive, verbose);
    return status;
}

int cmdPrintenv(Args &a) {
    if (a.size() > 1) {
        int status = 0;
        for (size_t i = 1; i < a.size(); i++) {
            const char *v = std::getenv(a[i].c_str());
            if (v) std::puts(v);
            else status = 1;
        }
        return status;
    }
    for (char **e = environ; e && *e; e++) std::puts(*e);
    return 0;
}

} // namespace

void registerFiles(std::map<std::string, tu::Applet> &t) {
    t["tar"] = cmdTar;
    t["realpath"] = cmdRealpath;
    t["readlink"] = cmdRealpath;
    t["mktemp"] = cmdMktemp;
    t["truncate"] = cmdTruncate;
    t["dd"] = cmdDd;
    t["tree"] = cmdTree;
    t["which"] = cmdWhich;
    t["file"] = cmdFile;
    t["xargs"] = cmdXargs;
    t["time"] = cmdTime;
    t["timeout"] = cmdTimeout;
    t["watch"] = cmdWatch;
    t["nohup"] = cmdNohup;
    t["expr"] = cmdExpr;
    t["factor"] = cmdFactor;
    t["cal"] = cmdCal;
    t["id"] = cmdId;
    t["printenv"] = cmdPrintenv;
    t["nproc"] = [](tu::Args &) { return std::puts("1") < 0 ? 1 : 0; };
    t["tty"] = [](tu::Args &) {
        bool tty = isatty(0);
        std::puts(tty ? "/dev/tty" : "not a tty");
        return tty ? 0 : 1;
    };
    t["sync"] = [](tu::Args &) { return 0; };
    t["chmod"] = cmdChmod;
}
