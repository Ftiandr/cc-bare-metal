// pkg - the CC: Bare Metal package manager.
//
//   pkg list                 installed packages
//   pkg search [TEXT]        packages available in the repositories (/etc/pkg/repos)
//   pkg info NAME            details of a package
//   pkg install NAME|FILE|URL install (with dependencies) into /usr/local; a URL is downloaded with CC's HTTP
//   pkg remove NAME          uninstall
//   pkg files NAME           files owned by an installed package
//   pkg build DIR [OUT]      make a package from DIR/manifest.json + DIR/files/...
//   pkg update               refresh the network repositories' indexes; list newer versions of what is installed
//   pkg upgrade [NAME...]    install them
//   pkg repo [list|add X|remove X]  the repositories in /etc/pkg/repos (directories or URLs)
//   pkg index DIR            write DIR/index.json, which makes DIR a network repository once served over HTTP
//
// Every installed package remembers its origin (/var/lib/pkg/NAME.json, "origin"): a URL (checked again by
// downloading it), a package file (read again) or "repo" (the repositories below, by name).
// Repositories: the bundled one (/usr/share/pkg), floppy disks (/disk*/pkg) and what /etc/pkg/repos lists: directories
// of *.ccpkg files, or URLs of a directory with index.json (fetched by pkg update into /var/cache/pkg/index; packages
// are downloaded on install and checked against the index's size and SHA-256).
// Package format: "CCPKG1\n", a manifest JSON line, then "D path\n" / "F path size\n<bytes>" records and "E\n".
// Installing writes every file as PATH.pkgnew first and moves them in place only when all are written: a full disk
// or a broken package leaves the installed version untouched.
#include <baremetal.hpp>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <optional>
#include <string>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>
#include <vector>

namespace {

const std::string DB = "/var/lib/pkg";
const std::string CACHE = "/var/cache/pkg";
const std::string INDEXES = "/var/cache/pkg/index";
const std::string REPOS = "/etc/pkg/repos";
const std::string PREFIX = "/usr/local";

bool isUrl(const std::string &s) {
    return s.rfind("http://", 0) == 0 || s.rfind("https://", 0) == 0;
}

std::string hex(const uint8_t *data, size_t n) {
    static const char digits[] = "0123456789abcdef";
    std::string out;
    for (size_t i = 0; i < n; i++) {
        out += digits[data[i] >> 4];
        out += digits[data[i] & 15];
    }
    return out;
}

std::string sha256(const std::string &data) {
    uint8_t out[32];
    cc_sha256(data.data(), static_cast<int32_t>(data.size()), out);
    return hex(out, 32);
}

std::optional<std::string> readAll(const std::string &path) {
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) return std::nullopt;
    std::string data;
    char buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) data.append(buf, n);
    bool ok = !std::ferror(f);
    std::fclose(f);
    if (!ok) return std::nullopt;
    return data;
}

// Writes a whole file; false (errno set) if any part of it could not be written.
bool writeAll(const std::string &path, const std::string &data) {
    FILE *f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = std::fwrite(data.data(), 1, data.size(), f) == data.size();
    int err = errno;
    if (std::fclose(f) != 0) ok = false;
    else if (!ok) errno = err;
    return ok;
}

// Free bytes on the file system that holds path (or -1 if unknown).
long long freeSpace(const std::string &path) {
    struct statvfs st;
    if (statvfs(path.c_str(), &st) != 0) return -1;
    return static_cast<long long>(st.f_bavail) * static_cast<long long>(st.f_frsize);
}

std::string humanSize(long long n) {
    char buf[32];
    if (n >= 1 << 20) std::snprintf(buf, sizeof(buf), "%.1f MB", n / 1048576.0);
    else if (n >= 1 << 10) std::snprintf(buf, sizeof(buf), "%.1f KB", n / 1024.0);
    else std::snprintf(buf, sizeof(buf), "%lld B", n);
    return buf;
}

bool endsWith(const std::string &s, const std::string &suffix) {
    return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool startsWith(const std::string &s, const std::string &prefix) {
    return s.rfind(prefix, 0) == 0;
}

void mkdirs(const std::string &path) {
    for (size_t i = 1; i < path.size(); i++) {
        if (path[i] == '/') mkdir(path.substr(0, i).c_str(), 0755);
    }
    mkdir(path.c_str(), 0755);
}

std::vector<std::string> listDir(const std::string &dir) {
    std::vector<std::string> out;
    if (DIR *d = opendir(dir.c_str())) {
        while (auto *e = readdir(d)) out.emplace_back(e->d_name);
        closedir(d);
    }
    return out;
}

bool exists(const std::string &path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0;
}

// A FILE* that closes itself.
class File {
public:
    File(const std::string &path, const char *mode) : f_(std::fopen(path.c_str(), mode)) {}
    ~File() {
        if (f_) std::fclose(f_);
    }
    File(const File &) = delete;
    File &operator=(const File &) = delete;
    explicit operator bool() const { return f_ != nullptr; }
    FILE *get() const { return f_; }

    std::optional<std::string> line() {
        std::string s;
        int c;
        while ((c = std::fgetc(f_)) != EOF && c != '\n') s += static_cast<char>(c);
        if (c == EOF && s.empty()) return std::nullopt;
        return s;
    }

private:
    FILE *f_;
};

struct Package {
    std::string path; // a local file, or empty for a network repository's package (then url, size, sha256)
    cc::Json manifest;
    std::string url, sha256;
    long long size = -1;

    std::string name() const { return manifest["name"].asString(""); }
    std::string version() const { return manifest["version"].asString(""); }
    std::string description() const { return manifest["description"].asString(""); }
};

// Read just the manifest of a package file.
std::optional<cc::Json> readManifest(const std::string &path) {
    File f(path, "rb");
    if (!f || f.line() != std::optional<std::string>("CCPKG1")) return std::nullopt;
    auto json = f.line();
    if (!json) return std::nullopt;
    return cc::Json::parse(*json);
}

bool isInstalled(const std::string &name) {
    return exists(DB + "/" + name + ".json");
}

// The record of an installed package: its manifest plus "origin".
std::optional<cc::Json> installedRecord(const std::string &name) {
    File f(DB + "/" + name + ".json", "r");
    if (!f) return std::nullopt;
    return cc::Json::parse(f.line().value_or(""));
}

// Versions compare part by part ("1.10" > "1.9"; "2.0-beta" < "2.0"): numbers as numbers, the rest as text.
int compareVersions(const std::string &a, const std::string &b) {
    size_t i = 0, j = 0;
    while (i < a.size() || j < b.size()) {
        auto part = [](const std::string &s, size_t &k) {
            std::string p;
            while (k < s.size() && s[k] != '.' && s[k] != '-') p += s[k++];
            if (k < s.size()) k++;
            return p;
        };
        auto pa = part(a, i), pb = part(b, j);
        if (pa.empty() && !pb.empty()) return std::isdigit(static_cast<unsigned char>(pb[0])) ? -1 : 1; // 2.0 > 2.0-beta
        if (pb.empty() && !pa.empty()) return std::isdigit(static_cast<unsigned char>(pa[0])) ? 1 : -1;
        bool na = !pa.empty() && std::all_of(pa.begin(), pa.end(), ::isdigit);
        bool nb = !pb.empty() && std::all_of(pb.begin(), pb.end(), ::isdigit);
        if (na && nb) {
            auto x = std::stoll(pa), y = std::stoll(pb);
            if (x != y) return x < y ? -1 : 1;
        } else if (pa != pb) {
            return na ? 1 : nb ? -1 : pa < pb ? -1 : 1;
        }
    }
    return 0;
}

// ---- repositories ----

std::vector<Package> available;

void scanRepo(const std::string &dir) {
    for (auto &name : listDir(dir)) {
        if (!endsWith(name, ".ccpkg")) continue;
        auto path = dir + "/" + name;
        if (auto m = readManifest(path)) available.push_back({path, *m, "", "", -1});
    }
}

// The cached index of a network repository: one file per URL.
std::string indexPath(const std::string &url) {
    std::string name;
    for (char c : url) name += std::isalnum(static_cast<unsigned char>(c)) ? c : '_';
    return INDEXES + "/" + name + ".json";
}

std::string baseUrl(std::string url) {
    if (endsWith(url, "/index.json")) url.resize(url.size() - 11);
    while (!url.empty() && url.back() == '/') url.pop_back();
    return url;
}

void scanIndex(const std::string &url) {
    auto text = readAll(indexPath(url));
    if (!text) return;
    auto index = cc::Json::parse(*text);
    if (!index) return;
    for (auto &entry : (*index)["packages"].items()) {
        Package p;
        p.manifest = entry;
        p.url = baseUrl(url) + "/" + entry["file"].asString("");
        p.sha256 = entry["sha256"].asString("");
        p.size = static_cast<long long>(entry["size"].asNumber(-1));
        if (!p.name().empty() && !entry["file"].asString("").empty()) available.push_back(p);
    }
}

std::vector<std::string> repoLines() {
    std::vector<std::string> out;
    File repos(REPOS, "r");
    if (!repos) return out;
    while (auto line = repos.line()) {
        while (!line->empty() && (line->back() == '\r' || line->back() == ' ')) line->pop_back();
        if (!line->empty() && (*line)[0] != '#') out.push_back(*line);
    }
    return out;
}

void scanRepos() {
    scanRepo("/usr/share/pkg");
    for (auto &repo : repoLines()) {
        if (isUrl(repo)) scanIndex(repo);
        else scanRepo(repo);
    }
    // any mounted disk with a pkg directory
    for (auto &name : listDir("/")) {
        if (startsWith(name, "disk")) scanRepo("/" + name + "/pkg");
    }
}

const Package *findAvailable(const std::string &name) {
    const Package *best = nullptr;
    for (auto &p : available) {
        if (p.name() == name && (!best || compareVersions(p.version(), best->version()) > 0)) best = &p;
    }
    return best;
}

// ---- install / remove ----

bool safePath(const std::string &p) {
    return !p.empty() && p[0] != '/' && p.find("..") == std::string::npos;
}

int installFile(const std::string &file, int depth, const std::string &origin, bool upgrade = false);
std::optional<std::string> download(const std::string &url, const std::string &name);

// A local file of the package: its own path, or a download (checked against the index) in the cache.
std::optional<std::string> fetch(const Package &p) {
    if (!p.path.empty()) return p.path;
    auto file = download(p.url, p.name() + "-" + p.version());
    if (!file) return std::nullopt;
    auto data = readAll(*file);
    if (!data || (p.size >= 0 && static_cast<long long>(data->size()) != p.size) ||
        (!p.sha256.empty() && sha256(*data) != p.sha256)) {
        std::fprintf(stderr, "pkg: %s: does not match the repository's index (size or SHA-256); not installed\n",
                     p.url.c_str());
        unlink(file->c_str());
        return std::nullopt;
    }
    return file;
}

int installName(const std::string &name, int depth) {
    if (isInstalled(name)) return 0;
    auto *p = findAvailable(name);
    if (!p) {
        std::fprintf(stderr, "pkg: package '%s' not found in any repository\n", name.c_str());
        return 1;
    }
    auto file = fetch(*p);
    if (!file) return 1;
    return installFile(*file, depth, "repo");
}

// Install a package file. origin: where it came from, for update ("repo", a URL, or the file). upgrade: replace the
// installed version (files the new one no longer has are removed).
int installFile(const std::string &file, int depth, const std::string &origin, bool upgrade) {
    if (depth > 16) {
        std::fprintf(stderr, "pkg: dependency loop\n");
        return 1;
    }
    File f(file, "rb");
    if (!f) {
        std::fprintf(stderr, "pkg: %s: %s\n", file.c_str(), std::strerror(errno));
        return 1;
    }
    if (f.line() != std::optional<std::string>("CCPKG1")) {
        std::fprintf(stderr, "pkg: %s: not a package\n", file.c_str());
        return 1;
    }
    auto json = f.line().value_or("");
    auto manifest = cc::Json::parse(json).value_or(cc::Json());
    auto name = manifest["name"].asString("");
    if (name.empty()) {
        std::fprintf(stderr, "pkg: %s: bad manifest\n", file.c_str());
        return 1;
    }
    if (isInstalled(name) && depth > 0 && !upgrade) return 0;
    std::vector<std::string> oldFiles;
    if (upgrade) {
        File old(DB + "/" + name + ".list", "r");
        while (old) {
            auto line = old.line();
            if (!line) break;
            oldFiles.push_back(*line);
        }
    }
    for (auto &dep : manifest["depends"].items()) {
        if (installName(dep.asString(), depth + 1) != 0) return 1;
    }
    auto prefix = manifest["prefix"].asString(PREFIX);
    if (!startsWith(prefix, "/usr/local") && !startsWith(prefix, "/opt") && !startsWith(prefix, "/home")) prefix = PREFIX;

    // First pass: the records must be whole, and the files must fit.
    struct Record {
        bool dir;
        std::string rel;
        long offset, size;
    };
    std::vector<Record> records;
    long long total = 0;
    bool ended = false;
    while (auto rec = f.line()) {
        if (rec->empty()) continue;
        if ((*rec)[0] == 'E') {
            ended = true;
            break;
        }
        if (startsWith(*rec, "D ")) {
            if (safePath(rec->substr(2))) records.push_back({true, rec->substr(2), 0, 0});
            continue;
        }
        if (!startsWith(*rec, "F ")) continue;
        auto space = rec->rfind(' ');
        long size = std::atol(rec->c_str() + space + 1);
        auto rel = rec->substr(2, space - 2);
        long offset = std::ftell(f.get());
        if (size < 0 || std::fseek(f.get(), size, SEEK_CUR) != 0) break;
        if (!safePath(rel)) {
            std::fprintf(stderr, "pkg: refusing unsafe path %s\n", rel.c_str());
            continue;
        }
        records.push_back({false, rel, offset, size});
        total += size;
    }
    std::fseek(f.get(), 0, SEEK_END);
    long end = std::ftell(f.get());
    bool whole = ended;
    for (auto &r : records)
        if (!r.dir && r.offset + r.size > end) whole = false;
    if (!whole) {
        std::fprintf(stderr, "pkg: %s: the package is cut short (an incomplete download or copy?); nothing installed\n",
                     file.c_str());
        return 1;
    }
    mkdirs(prefix);
    auto space = freeSpace(prefix);
    // the new files are written next to the old ones before they replace them
    if (space >= 0 && total + 64 * 1024 > space) {
        std::fprintf(stderr, "pkg: %s needs %s, only %s free on %s; nothing installed\n", name.c_str(),
                     humanSize(total).c_str(), humanSize(space).c_str(), prefix.c_str());
        return 1;
    }

    std::printf("%s %s %s...\n", upgrade ? "Upgrading" : "Installing", name.c_str(), manifest["version"].asString("").c_str());
    std::fflush(stdout);
    // Second pass: every file as PATH.pkgnew; any failure removes them and leaves what is installed as it was.
    std::vector<std::pair<std::string, bool>> written; // path, runnable
    auto rollback = [&](const std::string &why) {
        for (auto &w : written) unlink((w.first + ".pkgnew").c_str());
        std::fprintf(stderr, "pkg: %s; nothing installed\n", why.c_str());
        return 1;
    };
    for (auto &r : records) {
        auto path = prefix + "/" + r.rel;
        if (r.dir) {
            mkdirs(path);
            continue;
        }
        mkdirs(path.substr(0, path.rfind('/')));
        auto tmp = path + ".pkgnew";
        FILE *out = std::fopen(tmp.c_str(), "wb");
        if (!out) {
            int err = errno;
            return rollback("cannot write " + path + ": " + std::strerror(err) +
                            (err == EACCES ? " (installing needs the group wheel or sudo)" : ""));
        }
        written.push_back({path, false});
        std::fseek(f.get(), r.offset, SEEK_SET);
        char buf[8192];
        bool ok = true, first = true;
        for (long left = r.size; left > 0 && ok;) {
            size_t n = std::fread(buf, 1, static_cast<size_t>(std::min<long>(left, sizeof(buf))), f.get());
            if (n == 0) {
                ok = false;
                break;
            }
            // WebAssembly modules and scripts become runnable (0755 minus the umask)
            if (first && n >= 2) written.back().second = (buf[0] == 0 && buf[1] == 'a') || (buf[0] == '#' && buf[1] == '!');
            first = false;
            if (std::fwrite(buf, 1, n, out) != n) ok = false;
            left -= static_cast<long>(n);
        }
        int err = errno;
        if (std::fclose(out) != 0) {
            ok = false;
            err = errno;
        }
        if (!ok) return rollback("cannot write " + path + ": " + std::strerror(err ? err : EIO));
    }
    // All written: move them in place.
    mode_t mask = umask(0);
    umask(mask);
    std::vector<std::string> newFiles;
    for (auto &w : written) {
        if (std::rename((w.first + ".pkgnew").c_str(), w.first.c_str()) != 0) {
            std::fprintf(stderr, "pkg: cannot replace %s: %s\n", w.first.c_str(), std::strerror(errno));
            unlink((w.first + ".pkgnew").c_str());
            continue;
        }
        if (w.second) chmod(w.first.c_str(), 0777 & ~mask);
        newFiles.push_back(w.first);
    }
    for (auto &old : oldFiles) {
        if (std::find(newFiles.begin(), newFiles.end(), old) == newFiles.end()) unlink(old.c_str());
    }
    mkdirs(DB);
    std::string listText;
    for (auto &n : newFiles) listText += n + "\n";
    manifest.set("origin", origin);
    if (!writeAll(DB + "/" + name + ".list", listText) || !writeAll(DB + "/" + name + ".json", manifest.dump() + "\n")) {
        std::fprintf(stderr, "pkg: %s is installed but its record could not be written: %s\n", name.c_str(), std::strerror(errno));
        return 1;
    }
    std::printf("  %zu files installed.\n", newFiles.size());
    return 0;
}

// Download a package to the cache; its path, or nullopt (message printed).
std::optional<std::string> download(const std::string &url, const std::string &name) {
    std::printf("Downloading %s...\n", url.c_str());
    std::fflush(stdout);
    auto r = cc::http::get(url);
    if (!r.ok) {
        std::fprintf(stderr, "pkg: %s: %s\n", url.c_str(), r.error.c_str());
        return std::nullopt;
    }
    mkdirs(CACHE);
    auto path = CACHE + "/" + name + ".ccpkg";
    if (!writeAll(path, r.body)) {
        std::fprintf(stderr, "pkg: cannot write %s: %s\n", path.c_str(), std::strerror(errno));
        unlink(path.c_str());
        return std::nullopt;
    }
    return path;
}

// ---- update / upgrade ----

struct Upgrade {
    std::string name, from, to, file, origin;
};

// Newer versions of the installed packages, looked for where each came from.
std::vector<Upgrade> findUpgrades(const std::vector<std::string> &only) {
    scanRepos();
    std::vector<Upgrade> out;
    for (auto &entry : listDir(DB)) {
        if (!endsWith(entry, ".json")) continue;
        auto name = entry.substr(0, entry.size() - 5);
        if (!only.empty() && std::find(only.begin(), only.end(), name) == only.end()) continue;
        auto record = installedRecord(name);
        if (!record) continue;
        auto version = (*record)["version"].asString("");
        auto origin = (*record)["origin"].asString("repo");
        std::optional<std::string> file;
        if (startsWith(origin, "http://") || startsWith(origin, "https://")) {
            file = download(origin, name);
        } else if (origin == "repo") {
            if (auto *p = findAvailable(name)) {
                if (compareVersions(p->version(), version) > 0) out.push_back({name, version, p->version(), "", origin});
                continue;
            }
        } else if (exists(origin)) {
            file = origin;
        }
        if (!file) continue;
        auto m = readManifest(*file);
        if (!m || (*m)["name"].asString("") != name) continue;
        auto newVersion = (*m)["version"].asString("");
        if (compareVersions(newVersion, version) > 0) out.push_back({name, version, newVersion, *file, origin});
    }
    return out;
}

int removePackage(const std::string &name) {
    auto listPath = DB + "/" + name + ".list";
    int count = 0;
    {
        File list(listPath, "r");
        if (!list) {
            std::fprintf(stderr, "pkg: %s is not installed\n", name.c_str());
            return 1;
        }
        while (auto line = list.line()) {
            if (unlink(line->c_str()) == 0) count++;
        }
    }
    unlink(listPath.c_str());
    unlink((DB + "/" + name + ".json").c_str());
    std::printf("Removed %s (%d files).\n", name.c_str(), count);
    return 0;
}

// ---- build ----

void addTree(FILE *out, const std::string &root, const std::string &rel) {
    for (auto &name : listDir(rel.empty() ? root : root + "/" + rel)) {
        if (name == "." || name == "..") continue;
        auto childRel = rel.empty() ? name : rel + "/" + name;
        auto child = root + "/" + childRel;
        struct stat st;
        if (stat(child.c_str(), &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            std::fprintf(out, "D %s\n", childRel.c_str());
            addTree(out, root, childRel);
            continue;
        }
        File in(child, "rb");
        if (!in) continue;
        std::fprintf(out, "F %s %lld\n", childRel.c_str(), static_cast<long long>(st.st_size));
        char buf[8192];
        size_t n;
        while ((n = std::fread(buf, 1, sizeof(buf), in.get())) > 0) std::fwrite(buf, 1, n, out);
    }
}

int build(const std::string &dir, std::optional<std::string> outPath) {
    auto manifestPath = dir + "/manifest.json";
    std::string json;
    {
        File mf(manifestPath, "r");
        if (!mf) {
            std::fprintf(stderr, "pkg: %s: %s\n", manifestPath.c_str(), std::strerror(errno));
            return 1;
        }
        int c;
        while ((c = std::fgetc(mf.get())) != EOF) json += c == '\n' || c == '\r' ? ' ' : static_cast<char>(c);
    }
    auto manifest = cc::Json::parse(json);
    if (!manifest || manifest->find("name") == nullptr) {
        std::fprintf(stderr, "pkg: manifest.json needs at least a \"name\"\n");
        return 1;
    }
    auto out = outPath.value_or((*manifest)["name"].asString("pkg") + "-" + (*manifest)["version"].asString("0") + ".ccpkg");
    File f(out, "wb");
    if (!f) {
        std::fprintf(stderr, "pkg: %s: %s\n", out.c_str(), std::strerror(errno));
        return 1;
    }
    std::fprintf(f.get(), "CCPKG1\n%s\n", json.c_str());
    addTree(f.get(), dir + "/files", "");
    std::fputs("E\n", f.get());
    std::printf("Built %s\n", out.c_str());
    return 0;
}

// Downloads the index of every network repository (pkg update). Returns how many failed.
int refreshIndexes() {
    int failed = 0;
    for (auto &repo : repoLines()) {
        if (!isUrl(repo)) continue;
        auto url = baseUrl(repo) + "/index.json";
        std::printf("Fetching %s\n", url.c_str());
        std::fflush(stdout);
        auto r = cc::http::get(url);
        if (!r.ok || !cc::Json::parse(r.body)) {
            std::fprintf(stderr, "pkg: %s: %s\n", url.c_str(), r.ok ? "not a package index" : r.error.c_str());
            failed++;
            continue;
        }
        mkdirs(INDEXES);
        if (!writeAll(indexPath(repo), r.body)) {
            std::fprintf(stderr, "pkg: cannot write %s: %s\n", indexPath(repo).c_str(), std::strerror(errno));
            failed++;
        }
    }
    return failed;
}

int repoCommand(const std::vector<std::string> &args) {
    std::string sub = args.size() > 2 ? args[2] : "list";
    auto lines = repoLines();
    if (sub == "list") {
        std::puts("/usr/share/pkg (bundled)");
        for (auto &l : lines) std::printf("%s%s\n", l.c_str(), isUrl(l) && !exists(indexPath(l)) ? "  (not fetched yet: pkg update)" : "");
        return 0;
    }
    if ((sub != "add" && sub != "remove") || args.size() < 4) {
        std::fputs("usage: pkg repo [list] | add DIR|URL | remove DIR|URL\n", stderr);
        return 1;
    }
    auto target = args[3];
    if (!isUrl(target)) {
        char real[1024];
        if (realpath(target.c_str(), real)) target = real;
    }
    std::string text;
    {
        File f(REPOS, "r");
        while (f) {
            auto line = f.line();
            if (!line) break;
            if (sub == "remove" && *line == target) continue;
            text += *line + "\n";
        }
    }
    if (sub == "add") {
        if (std::find(lines.begin(), lines.end(), target) != lines.end()) {
            std::printf("%s is already listed.\n", target.c_str());
            return 0;
        }
        text += target + "\n";
    } else if (std::find(lines.begin(), lines.end(), target) == lines.end()) {
        std::fprintf(stderr, "pkg: %s is not listed\n", target.c_str());
        return 1;
    }
    mkdirs("/etc/pkg");
    if (!writeAll(REPOS, text)) {
        std::fprintf(stderr, "pkg: %s: %s%s\n", REPOS.c_str(), std::strerror(errno), errno == EACCES ? " (needs sudo)" : "");
        return 1;
    }
    if (sub == "remove" && isUrl(target)) unlink(indexPath(target).c_str());
    std::printf("%s %s%s\n", sub == "add" ? "Added" : "Removed", target.c_str(),
                sub == "add" && isUrl(target) ? "; fetch its index with pkg update" : "");
    return 0;
}

// Writes DIR/index.json for the *.ccpkg files in DIR: name, version, description, depends, file, size, sha256.
int writeIndex(const std::string &dir) {
    auto index = cc::Json::object();
    auto packages = cc::Json::array();
    int count = 0;
    auto names = listDir(dir);
    std::sort(names.begin(), names.end());
    for (auto &name : names) {
        if (!endsWith(name, ".ccpkg")) continue;
        auto path = dir + "/" + name;
        auto m = readManifest(path);
        auto data = readAll(path);
        if (!m || !data) {
            std::fprintf(stderr, "pkg: %s: not a package, skipped\n", path.c_str());
            continue;
        }
        auto entry = *m;
        entry.set("file", name);
        entry.set("size", static_cast<double>(data->size()));
        entry.set("sha256", sha256(*data));
        packages.push(entry);
        count++;
    }
    index.set("packages", packages);
    if (!writeAll(dir + "/index.json", index.dump() + "\n")) {
        std::fprintf(stderr, "pkg: %s/index.json: %s\n", dir.c_str(), std::strerror(errno));
        return 1;
    }
    std::printf("Wrote %s/index.json (%d package%s). Serve %s over HTTP and add its URL with pkg repo add.\n", dir.c_str(),
                count, count == 1 ? "" : "s", dir.c_str());
    return 0;
}

int list() {
    for (auto &name : listDir(DB)) {
        if (!endsWith(name, ".json")) continue;
        File f(DB + "/" + name, "r");
        if (!f) continue;
        auto m = cc::Json::parse(f.line().value_or("")).value_or(cc::Json());
        std::printf("%-20s %-10s %s\n", m["name"].asString("?").c_str(), m["version"].asString("").c_str(),
                    m["description"].asString("").c_str());
    }
    return 0;
}

} // namespace

int main(int argc, char **argv) {
    std::vector<std::string> args(argv, argv + argc);
    std::string cmd = args.size() > 1 ? args[1] : "help";
    int status = 0;
    if (cmd == "list") return list();
    if (cmd == "search" || cmd == "avail") {
        scanRepos();
        for (auto &p : available) {
            auto name = p.name(), desc = p.description();
            if (args.size() > 2 && name.find(args[2]) == std::string::npos && desc.find(args[2]) == std::string::npos) continue;
            std::printf("%s%-20s\x1b[0m %-10s %s\n", isInstalled(name) ? "\x1b[92m" : "", name.c_str(), p.version().c_str(), desc.c_str());
        }
        return 0;
    }
    if (cmd == "info" && args.size() > 2) {
        scanRepos();
        auto *p = findAvailable(args[2]);
        if (!p) {
            std::fprintf(stderr, "pkg: %s not found\n", args[2].c_str());
            return 1;
        }
        std::printf("Name:        %s\nVersion:     %s\nDescription: %s\n%s     %s\nInstalled:   %s\n", p->name().c_str(),
                    p->version().c_str(), p->description().c_str(), p->path.empty() ? "URL: " : "File:", p->path.empty() ? p->url.c_str() : p->path.c_str(),
                    isInstalled(args[2]) ? "yes" : "no");
        return 0;
    }
    if (cmd == "install" && args.size() > 2) {
        scanRepos();
        for (size_t i = 2; i < args.size(); i++) {
            if (startsWith(args[i], "http://") || startsWith(args[i], "https://")) {
                std::printf("Downloading %s...\n", args[i].c_str());
                std::fflush(stdout);
                auto r = cc::http::get(args[i]);
                if (!r.ok) {
                    std::fprintf(stderr, "pkg: %s: %s\n", args[i].c_str(), r.error.c_str());
                    status |= 1;
                    continue;
                }
                auto tmp = "/tmp/pkg-download-" + std::to_string(cc::pid()) + ".ccpkg";
                FILE *f = std::fopen(tmp.c_str(), "wb");
                if (!f || std::fwrite(r.body.data(), 1, r.body.size(), f) != r.body.size()) {
                    if (f) std::fclose(f);
                    std::fprintf(stderr, "pkg: cannot write %s\n", tmp.c_str());
                    status |= 1;
                    continue;
                }
                std::fclose(f);
                status |= installFile(tmp, 0, args[i]);
                std::remove(tmp.c_str());
            } else if (endsWith(args[i], ".ccpkg")) {
                // remembered by its full path, to look there for a newer version
                char real[1024];
                auto path = realpath(args[i].c_str(), real) ? std::string(real) : args[i];
                status |= installFile(args[i], 0, path);
            }
            else if (isInstalled(args[i])) std::printf("%s is already installed.\n", args[i].c_str());
            else status |= installName(args[i], 0);
        }
        return status;
    }
    if ((cmd == "remove" || cmd == "uninstall") && args.size() > 2) {
        for (size_t i = 2; i < args.size(); i++) status |= removePackage(args[i]);
        return status;
    }
    if (cmd == "files" && args.size() > 2) {
        File f(DB + "/" + args[2] + ".list", "r");
        if (!f) {
            std::fprintf(stderr, "pkg: %s is not installed\n", args[2].c_str());
            return 1;
        }
        while (auto line = f.line()) std::puts(line->c_str());
        return 0;
    }
    if (cmd == "update") {
        status = refreshIndexes() > 0 ? 1 : 0;
        auto upgrades = findUpgrades({});
        if (upgrades.empty()) {
            std::puts("All packages are up to date.");
            return status;
        }
        for (auto &u : upgrades) std::printf("%-20s %s -> %s\n", u.name.c_str(), u.from.c_str(), u.to.c_str());
        std::printf("%zu package%s can be upgraded: pkg upgrade\n", upgrades.size(), upgrades.size() == 1 ? "" : "s");
        return status;
    }
    if (cmd == "upgrade") {
        std::vector<std::string> only(args.begin() + 2, args.end());
        for (auto &n : only) {
            if (!isInstalled(n)) {
                std::fprintf(stderr, "pkg: %s is not installed\n", n.c_str());
                status = 1;
            }
        }
        auto upgrades = findUpgrades(only);
        if (upgrades.empty()) {
            std::puts("Nothing to upgrade.");
            return status;
        }
        for (auto &u : upgrades) {
            auto file = u.file.empty() ? std::optional<std::string>() : std::optional(u.file);
            if (!file) {
                if (auto *p = findAvailable(u.name)) file = fetch(*p);
            }
            if (!file) {
                status = 1;
                continue;
            }
            status |= installFile(*file, 0, u.origin, true);
        }
        return status;
    }
    if (cmd == "build" && args.size() > 2) return build(args[2], args.size() > 3 ? std::optional(args[3]) : std::nullopt);
    if (cmd == "repo") return repoCommand(args);
    if (cmd == "index" && args.size() > 2) return writeIndex(args[2]);
    std::puts("usage: pkg list | search [TEXT] | info NAME | install NAME|FILE.ccpkg|URL | remove NAME | files NAME\n"
              "       pkg update | upgrade [NAME...] | repo [list|add X|remove X] | build DIR [OUT] | index DIR");
    return cmd == "help" ? 0 : 1;
}
