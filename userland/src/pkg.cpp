// pkg - the CC: Bare Metal package manager.
//
//   pkg list                 installed packages
//   pkg search [TEXT]        packages available in the repositories (/etc/pkg/repos)
//   pkg info NAME            details of a package
//   pkg install NAME|FILE|URL install (with dependencies) into /usr/local; a URL is downloaded with CC's HTTP
//   pkg remove NAME          uninstall
//   pkg files NAME           files owned by an installed package
//   pkg build DIR [OUT]      make a package from DIR/manifest.json + DIR/files/...
//   pkg update               newer versions of the installed packages, where they came from
//   pkg upgrade [NAME...]    install them
//
// Every installed package remembers its origin (/var/lib/pkg/NAME.json, "origin"): a URL (checked again by
// downloading it), a package file (read again) or "repo" (the repositories below, by name).
// Repositories are directories of *.ccpkg files: the bundled one, floppy disks (/disk/pkg), or anything else
// listed in /etc/pkg/repos. Package format: "CCPKG1\n", a manifest JSON line, then "D path\n" / "F path size\n<bytes>"
// records and a final "E\n".
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
#include <unistd.h>
#include <vector>

namespace {

const std::string DB = "/var/lib/pkg";
const std::string CACHE = "/var/cache/pkg";
const std::string PREFIX = "/usr/local";

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
    std::string path;
    cc::Json manifest;

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
        if (auto m = readManifest(path)) available.push_back({path, *m});
    }
}

void scanRepos() {
    scanRepo("/usr/share/pkg");
    File repos("/etc/pkg/repos", "r");
    if (repos) {
        while (auto line = repos.line()) {
            while (!line->empty() && (line->back() == '\r' || line->back() == ' ')) line->pop_back();
            if (!line->empty() && (*line)[0] != '#') scanRepo(*line);
        }
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

int installName(const std::string &name, int depth) {
    if (isInstalled(name)) return 0;
    auto *p = findAvailable(name);
    if (!p) {
        std::fprintf(stderr, "pkg: package '%s' not found in any repository\n", name.c_str());
        return 1;
    }
    return installFile(p->path, depth, "repo");
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
    std::printf("%s %s %s...\n", upgrade ? "Upgrading" : "Installing", name.c_str(), manifest["version"].asString("").c_str());
    mkdirs(DB);
    File list(DB + "/" + name + ".list", "w");
    std::vector<std::string> newFiles;
    int count = 0;
    while (auto rec = f.line()) {
        if (rec->empty()) continue;
        if ((*rec)[0] == 'E') break;
        if (startsWith(*rec, "D ") && safePath(rec->substr(2))) {
            mkdirs(prefix + "/" + rec->substr(2));
            continue;
        }
        if (!startsWith(*rec, "F ")) continue;
        auto space = rec->rfind(' ');
        long size = std::atol(rec->c_str() + space + 1);
        auto rel = rec->substr(2, space - 2);
        if (!safePath(rel)) {
            std::fprintf(stderr, "pkg: refusing unsafe path %s\n", rel.c_str());
            std::fseek(f.get(), size, SEEK_CUR);
            continue;
        }
        auto path = prefix + "/" + rel;
        mkdirs(path.substr(0, path.rfind('/')));
        File out(path, "wb");
        int err = errno;
        char buf[8192];
        bool program = false, first = true;
        for (long left = size; left > 0;) {
            size_t n = std::fread(buf, 1, static_cast<size_t>(std::min<long>(left, sizeof(buf))), f.get());
            if (n == 0) break;
            // WebAssembly modules and scripts become runnable (0755 minus the umask)
            if (first && n >= 2) program = (buf[0] == 0 && buf[1] == 'a') || (buf[0] == '#' && buf[1] == '!');
            first = false;
            if (out) std::fwrite(buf, 1, n, out.get());
            left -= static_cast<long>(n);
        }
        if (out) {
            if (program) {
                mode_t mask = umask(0);
                umask(mask);
                chmod(path.c_str(), 0777 & ~mask);
            }
            if (list) std::fprintf(list.get(), "%s\n", path.c_str());
            newFiles.push_back(path);
            count++;
        } else {
            std::fprintf(stderr, "pkg: cannot write %s: %s%s\n", path.c_str(), std::strerror(err),
                         err == EACCES ? " (installing needs the group wheel or sudo)" : "");
        }
    }
    for (auto &old : oldFiles) {
        if (std::find(newFiles.begin(), newFiles.end(), old) == newFiles.end()) unlink(old.c_str());
    }
    manifest.set("origin", origin);
    File db(DB + "/" + name + ".json", "w");
    if (db) std::fprintf(db.get(), "%s\n", manifest.dump().c_str());
    std::printf("  %d files installed.\n", count);
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
    FILE *f = std::fopen(path.c_str(), "wb");
    if (!f || std::fwrite(r.body.data(), 1, r.body.size(), f) != r.body.size()) {
        if (f) std::fclose(f);
        std::fprintf(stderr, "pkg: cannot write %s: %s\n", path.c_str(), std::strerror(errno));
        return std::nullopt;
    }
    std::fclose(f);
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
            if (auto *p = findAvailable(name)) file = p->path;
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
        std::printf("Name:        %s\nVersion:     %s\nDescription: %s\nFile:        %s\nInstalled:   %s\n", p->name().c_str(),
                    p->version().c_str(), p->description().c_str(), p->path.c_str(), isInstalled(args[2]) ? "yes" : "no");
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
        auto upgrades = findUpgrades({});
        if (upgrades.empty()) {
            std::puts("All packages are up to date.");
            return 0;
        }
        for (auto &u : upgrades) std::printf("%-20s %s -> %s\n", u.name.c_str(), u.from.c_str(), u.to.c_str());
        std::printf("%zu package%s can be upgraded: pkg upgrade\n", upgrades.size(), upgrades.size() == 1 ? "" : "s");
        return 0;
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
        for (auto &u : upgrades) status |= installFile(u.file, 0, u.origin, true);
        return status;
    }
    if (cmd == "build" && args.size() > 2) return build(args[2], args.size() > 3 ? std::optional(args[3]) : std::nullopt);
    std::puts("usage: pkg list | search [TEXT] | info NAME | install NAME|FILE.ccpkg|URL | remove NAME | files NAME\n"
              "       pkg update | upgrade [NAME...] | build DIR [OUT]");
    return cmd == "help" ? 0 : 1;
}
