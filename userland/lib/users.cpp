// Users, groups and permissions for C/C++ programs: the POSIX functions WASI lacks (getuid, setresuid, chown,
// umask, getpwnam, getgrouplist, getspnam...) on top of the kernel's calls, and real modes and owners in stat()
// plus a working chmod() and access() (wrapped with -Wl,--wrap: the C library's own versions know no owners).
#include <baremetal.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <grp.h>
#include <pwd.h>
#include <shadow.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

struct Ids {
    int uid, euid, gid, egid;
    std::vector<gid_t> groups;
};

Ids ids() {
    int32_t buf[5 + 64];
    int n = cc_cred_get(buf, 5 + 64);
    Ids out{buf[0], buf[1], buf[2], buf[3], {}};
    for (int i = 5; i < n && i < 5 + buf[4]; i++) out.groups.push_back(static_cast<gid_t>(buf[i]));
    return out;
}

// 0 or errno from the kernel -> 0 or -1 with errno
int result(int32_t r) {
    if (r == 0) return 0;
    errno = r;
    return -1;
}

void augment(struct stat *st, const int32_t ext[3]) {
    st->st_mode = static_cast<mode_t>(ext[0]);
    st->st_uid = static_cast<uid_t>(ext[1]);
    st->st_gid = static_cast<gid_t>(ext[2]);
}

bool pathBased(int dirfd, const char *path) {
    return dirfd == AT_FDCWD || (path && path[0] == '/');
}

std::vector<std::string> fields(const std::string &line) {
    std::vector<std::string> out;
    size_t start = 0;
    while (true) {
        auto colon = line.find(':', start);
        out.push_back(line.substr(start, colon == std::string::npos ? std::string::npos : colon - start));
        if (colon == std::string::npos) break;
        start = colon + 1;
    }
    return out;
}

std::vector<std::vector<std::string>> readTable(const char *path) {
    std::vector<std::vector<std::string>> out;
    FILE *f = std::fopen(path, "r");
    if (!f) return out;
    char buf[1024];
    while (std::fgets(buf, sizeof(buf), f)) {
        std::string line = buf;
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        out.push_back(fields(line));
    }
    std::fclose(f);
    return out;
}

// ---- passwd ----

struct PwStorage {
    std::vector<std::string> f;
    struct passwd pw;
};

struct passwd *fillPw(PwStorage &s, const std::vector<std::string> &f) {
    if (f.size() < 7) return nullptr;
    s.f = f;
    s.pw.pw_name = s.f[0].data();
    s.pw.pw_passwd = s.f[1].data();
    s.pw.pw_uid = static_cast<uid_t>(std::atoi(s.f[2].c_str()));
    s.pw.pw_gid = static_cast<gid_t>(std::atoi(s.f[3].c_str()));
    s.pw.pw_gecos = s.f[4].data();
    s.pw.pw_dir = s.f[5].data();
    s.pw.pw_shell = s.f[6].data();
    return &s.pw;
}

template <typename Match>
struct passwd *findPw(Match match) {
    static PwStorage storage;
    for (auto &f : readTable("/etc/passwd")) {
        if (f.size() >= 7 && match(f)) return fillPw(storage, f);
    }
    return nullptr;
}

// Copy into the caller's buffer for the _r variants.
int copyPw(struct passwd *src, struct passwd *pw, char *buf, size_t size, struct passwd **result) {
    *result = nullptr;
    if (!src) return 0;
    char *p = buf, *end = buf + size;
    auto put = [&](const char *s) -> char * {
        size_t n = std::strlen(s) + 1;
        if (p + n > end) return nullptr;
        std::memcpy(p, s, n);
        char *at = p;
        p += n;
        return at;
    };
    *pw = *src;
    if (!(pw->pw_name = put(src->pw_name)) || !(pw->pw_passwd = put(src->pw_passwd)) || !(pw->pw_gecos = put(src->pw_gecos))
        || !(pw->pw_dir = put(src->pw_dir)) || !(pw->pw_shell = put(src->pw_shell))) {
        return ERANGE;
    }
    *result = pw;
    return 0;
}

std::vector<std::vector<std::string>> pwTable;
size_t pwIndex;

// ---- group ----

struct GrStorage {
    std::vector<std::string> f;
    std::vector<std::string> members;
    std::vector<char *> memberPtrs;
    struct group gr;
};

struct group *fillGr(GrStorage &s, const std::vector<std::string> &f) {
    if (f.size() < 4) return nullptr;
    s.f = f;
    s.members.clear();
    size_t start = 0;
    while (start < s.f[3].size()) {
        auto comma = s.f[3].find(',', start);
        auto name = s.f[3].substr(start, comma == std::string::npos ? std::string::npos : comma - start);
        if (!name.empty()) s.members.push_back(name);
        if (comma == std::string::npos) break;
        start = comma + 1;
    }
    s.memberPtrs.clear();
    for (auto &m : s.members) s.memberPtrs.push_back(m.data());
    s.memberPtrs.push_back(nullptr);
    s.gr.gr_name = s.f[0].data();
    s.gr.gr_passwd = s.f[1].data();
    s.gr.gr_gid = static_cast<gid_t>(std::atoi(s.f[2].c_str()));
    s.gr.gr_mem = s.memberPtrs.data();
    return &s.gr;
}

template <typename Match>
struct group *findGr(Match match) {
    static GrStorage storage;
    for (auto &f : readTable("/etc/group")) {
        if (f.size() >= 4 && match(f)) return fillGr(storage, f);
    }
    return nullptr;
}

std::vector<std::vector<std::string>> grTable;
size_t grIndex;

} // namespace

extern "C" {

// ---- ids ----

uid_t getuid(void) { return static_cast<uid_t>(ids().uid); }
uid_t geteuid(void) { return static_cast<uid_t>(ids().euid); }
gid_t getgid(void) { return static_cast<gid_t>(ids().gid); }
gid_t getegid(void) { return static_cast<gid_t>(ids().egid); }

int getgroups(int size, gid_t list[]) {
    auto g = ids().groups;
    if (size == 0) return static_cast<int>(g.size());
    if (size < static_cast<int>(g.size())) {
        errno = EINVAL;
        return -1;
    }
    for (size_t i = 0; i < g.size(); i++) list[i] = g[i];
    return static_cast<int>(g.size());
}

int setresuid(uid_t ruid, uid_t euid, uid_t) {
    return result(cc_cred_set(static_cast<int32_t>(ruid), static_cast<int32_t>(euid), -1, -1, nullptr, -1));
}

int setresgid(gid_t rgid, gid_t egid, gid_t) {
    return result(cc_cred_set(-1, -1, static_cast<int32_t>(rgid), static_cast<int32_t>(egid), nullptr, -1));
}

int setreuid(uid_t ruid, uid_t euid) { return setresuid(ruid, euid, static_cast<uid_t>(-1)); }
int setregid(gid_t rgid, gid_t egid) { return setresgid(rgid, egid, static_cast<gid_t>(-1)); }

// As on Linux: root sets every id; others only the effective one.
int setuid(uid_t uid) {
    if (geteuid() == 0) return setresuid(uid, uid, uid);
    return setresuid(static_cast<uid_t>(-1), uid, static_cast<uid_t>(-1));
}

int setgid(gid_t gid) {
    if (geteuid() == 0) return setresgid(gid, gid, gid);
    return setresgid(static_cast<gid_t>(-1), gid, static_cast<gid_t>(-1));
}

int seteuid(uid_t uid) { return setresuid(static_cast<uid_t>(-1), uid, static_cast<uid_t>(-1)); }
int setegid(gid_t gid) { return setresgid(static_cast<gid_t>(-1), gid, static_cast<gid_t>(-1)); }

int setgroups(size_t size, const gid_t *list) {
    std::vector<int32_t> g(list, list + size);
    return result(cc_cred_set(-1, -1, -1, -1, g.data(), static_cast<int32_t>(g.size())));
}

int getgrouplist(const char *user, gid_t group, gid_t *groups, int *ngroups) {
    std::vector<gid_t> all = {group};
    for (auto &f : readTable("/etc/group")) {
        if (f.size() < 4) continue;
        auto gid = static_cast<gid_t>(std::atoi(f[2].c_str()));
        std::string members = "," + f[3] + ",";
        if (gid != group && members.find("," + std::string(user) + ",") != std::string::npos) all.push_back(gid);
    }
    int capacity = *ngroups;
    *ngroups = static_cast<int>(all.size());
    for (int i = 0; i < capacity && i < static_cast<int>(all.size()); i++) groups[i] = all[i];
    return capacity < static_cast<int>(all.size()) ? -1 : static_cast<int>(all.size());
}

int initgroups(const char *user, gid_t group) {
    gid_t list[64];
    int n = 64;
    if (getgrouplist(user, group, list, &n) < 0) n = 64;
    return setgroups(static_cast<size_t>(n), list);
}

mode_t umask(mode_t mask) {
    return static_cast<mode_t>(cc_umask(static_cast<int32_t>(mask & 0777)));
}

// ---- owners and modes ----

int chown(const char *path, uid_t uid, gid_t gid) {
    return result(__cc_path_chown(path, static_cast<int32_t>(std::strlen(path)), static_cast<int32_t>(uid), static_cast<int32_t>(gid)));
}

int lchown(const char *path, uid_t uid, gid_t gid) { return chown(path, uid, gid); }

int fchown(int fd, uid_t uid, gid_t gid) {
    return result(cc_fd_chown(fd, static_cast<int32_t>(uid), static_cast<int32_t>(gid)));
}

int __real_stat(const char *, struct stat *);
int __real_lstat(const char *, struct stat *);
int __real_fstat(int, struct stat *);
int __real_fstatat(int, const char *, struct stat *, int);

int __wrap_stat(const char *path, struct stat *st) {
    int r = __real_stat(path, st);
    int32_t ext[3];
    if (r == 0 && __cc_path_stat_ext(path, static_cast<int32_t>(std::strlen(path)), ext) == 0) augment(st, ext);
    return r;
}

int __wrap_lstat(const char *path, struct stat *st) {
    int r = __real_lstat(path, st);
    int32_t ext[3];
    if (r == 0 && __cc_path_stat_ext(path, static_cast<int32_t>(std::strlen(path)), ext) == 0) augment(st, ext);
    return r;
}

int __wrap_fstat(int fd, struct stat *st) {
    int r = __real_fstat(fd, st);
    int32_t ext[3];
    if (r == 0 && cc_fd_stat_ext(fd, ext) == 0) augment(st, ext);
    return r;
}

int __wrap_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    int r = __real_fstatat(dirfd, path, st, flags);
    int32_t ext[3];
    if (r == 0 && pathBased(dirfd, path) && __cc_path_stat_ext(path, static_cast<int32_t>(std::strlen(path)), ext) == 0) augment(st, ext);
    return r;
}

int __wrap_chmod(const char *path, mode_t mode) {
    return result(__cc_path_chmod(path, static_cast<int32_t>(std::strlen(path)), static_cast<int32_t>(mode & 07777)));
}

int __wrap_fchmod(int fd, mode_t mode) {
    return result(cc_fd_chmod(fd, static_cast<int32_t>(mode & 07777)));
}

int __wrap_fchmodat(int dirfd, const char *path, mode_t mode, int) {
    if (!pathBased(dirfd, path)) {
        errno = ENOTSUP;
        return -1;
    }
    return __wrap_chmod(path, mode);
}

int __wrap_access(const char *path, int mode) {
    return result(__cc_path_access(path, static_cast<int32_t>(std::strlen(path)), mode));
}

int __wrap_faccessat(int dirfd, const char *path, int mode, int) {
    if (!pathBased(dirfd, path)) {
        errno = ENOTSUP;
        return -1;
    }
    return __wrap_access(path, mode);
}

// ---- passwd ----

struct passwd *getpwnam(const char *name) {
    return findPw([&](auto &f) { return f[0] == name; });
}

struct passwd *getpwuid(uid_t uid) {
    return findPw([&](auto &f) { return static_cast<uid_t>(std::atoi(f[2].c_str())) == uid; });
}

int getpwnam_r(const char *name, struct passwd *pw, char *buf, size_t size, struct passwd **result) {
    return copyPw(getpwnam(name), pw, buf, size, result);
}

int getpwuid_r(uid_t uid, struct passwd *pw, char *buf, size_t size, struct passwd **result) {
    return copyPw(getpwuid(uid), pw, buf, size, result);
}

void setpwent(void) {
    pwTable = readTable("/etc/passwd");
    pwIndex = 0;
}

struct passwd *getpwent(void) {
    static PwStorage storage;
    if (pwIndex == 0 && pwTable.empty()) setpwent();
    while (pwIndex < pwTable.size()) {
        if (auto *pw = fillPw(storage, pwTable[pwIndex++])) return pw;
    }
    return nullptr;
}

void endpwent(void) {
    pwTable.clear();
    pwIndex = 0;
}

int putpwent(const struct passwd *pw, FILE *f) {
    return std::fprintf(f, "%s:%s:%u:%u:%s:%s:%s\n", pw->pw_name, pw->pw_passwd, static_cast<unsigned>(pw->pw_uid),
                        static_cast<unsigned>(pw->pw_gid), pw->pw_gecos, pw->pw_dir, pw->pw_shell) < 0 ? -1 : 0;
}

// ---- group ----

struct group *getgrnam(const char *name) {
    return findGr([&](auto &f) { return f[0] == name; });
}

struct group *getgrgid(gid_t gid) {
    return findGr([&](auto &f) { return static_cast<gid_t>(std::atoi(f[2].c_str())) == gid; });
}

void setgrent(void) {
    grTable = readTable("/etc/group");
    grIndex = 0;
}

struct group *getgrent(void) {
    static GrStorage storage;
    if (grIndex == 0 && grTable.empty()) setgrent();
    while (grIndex < grTable.size()) {
        if (auto *g = fillGr(storage, grTable[grIndex++])) return g;
    }
    return nullptr;
}

void endgrent(void) {
    grTable.clear();
    grIndex = 0;
}

// ---- shadow ----

struct spwd *getspnam(const char *name) {
    static std::vector<std::string> f;
    static struct spwd sp;
    errno = 0;
    FILE *file = std::fopen("/etc/shadow", "r");
    if (!file) return nullptr; // errno: EACCES for everyone but root
    std::fclose(file);
    for (auto &row : readTable("/etc/shadow")) {
        if (row.empty() || row[0] != name) continue;
        f = row;
        f.resize(9);
        auto num = [](const std::string &s) { return s.empty() ? -1L : std::atol(s.c_str()); };
        sp.sp_namp = f[0].data();
        sp.sp_pwdp = f[1].data();
        sp.sp_lstchg = num(f[2]);
        sp.sp_min = num(f[3]);
        sp.sp_max = num(f[4]);
        sp.sp_warn = num(f[5]);
        sp.sp_inact = num(f[6]);
        sp.sp_expire = num(f[7]);
        sp.sp_flag = 0;
        return &sp;
    }
    return nullptr;
}

} // extern "C"
