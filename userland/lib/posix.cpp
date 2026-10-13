// POSIX functions WASI leaves out (or only stubs), on top of the system's own calls: processes and process groups,
// pipes and dup, temporary files, signal dispositions, exec, statvfs, resource limits, the terminal's name. Declared
// in the <unistd.h>, <stdlib.h>, <stdio.h>, <signal.h> and <sys/statvfs.h> wrappers of include/.
//
// Not possible here: fork() (a process cannot be copied; use posix_spawn) and asynchronous signal handlers (SIGINT
// and SIGTERM end the process, or are ignored with SIG_IGN; other dispositions are only remembered).
#include <baremetal.h>

#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <pwd.h>
#include <signal.h>
#include <spawn.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <vector>

extern char **environ;

namespace {

int fail(int err) {
    errno = err;
    return -1;
}

// ---- unique names for temporary files ----

uint64_t seed;

void randomTail(char *tail, size_t n) {
    static const char letters[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    if (seed == 0) {
        timespec t;
        clock_gettime(CLOCK_REALTIME, &t);
        seed = (uint64_t)t.tv_nsec ^ ((uint64_t)t.tv_sec << 20) ^ ((uint64_t)cc_getpid() << 40);
    }
    for (size_t i = 0; i < n; i++) {
        seed = seed * 6364136223846793005ULL + 1442695040888963407ULL;
        tail[i] = letters[(seed >> 33) % (sizeof(letters) - 1)];
    }
}

// Replaces the XXXXXX before `suffix` characters and creates the file (or directory).
int makeUnique(char *templ, int suffix, int flags, bool directory) {
    size_t length = std::strlen(templ);
    if (suffix < 0 || length < (size_t)suffix + 6 || std::memcmp(templ + length - suffix - 6, "XXXXXX", 6) != 0)
        return fail(EINVAL);
    char *tail = templ + length - suffix - 6;
    for (int attempt = 0; attempt < 100; attempt++) {
        randomTail(tail, 6);
        if (directory) {
            if (mkdir(templ, 0700) == 0) return 0;
        } else {
            int fd = open(templ, O_RDWR | O_CREAT | O_EXCL | (flags & ~O_ACCMODE), 0600);
            if (fd >= 0) return fd;
        }
        if (errno != EEXIST) return -1;
    }
    return fail(EEXIST);
}

const char *tmpDir() {
    const char *dir = std::getenv("TMPDIR");
    return dir && *dir ? dir : "/tmp";
}

// ---- signals: dispositions are remembered; SIG_IGN on SIGINT/SIGTERM makes the kernel stop delivering them ----

struct sigaction actions[65];
sigset_t blocked;

void applyCatch() {
    bool ignore = actions[SIGINT].sa_handler == SIG_IGN || actions[SIGTERM].sa_handler == SIG_IGN;
    int flags = cc_proc_flags(-1);
    cc_proc_flags(ignore ? flags | CC_PROC_CATCH : flags & ~CC_PROC_CATCH);
}

// ---- exec: run the program, wait for it and exit with its status (the pid differs, nothing else should) ----

[[noreturn]] void execDone(int status) {
    std::fflush(nullptr);
    _exit(status);
}

int execImpl(const char *path, char *const argv[], char *const envp[], bool search) {
    pid_t pid;
    int err = search ? posix_spawnp(&pid, path, nullptr, nullptr, argv, envp) : posix_spawn(&pid, path, nullptr, nullptr, argv, envp);
    if (err != 0) return fail(err);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) execDone(127);
    }
    execDone(WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status));
}

std::vector<char *> collectArgs(const char *first, va_list ap, char ***envp) {
    std::vector<char *> args{const_cast<char *>(first)};
    while (char *a = va_arg(ap, char *)) args.push_back(a);
    args.push_back(nullptr);
    if (envp) *envp = va_arg(ap, char **);
    return args;
}

} // namespace

extern "C" {

// ---- processes ----

pid_t getpid(void) { return cc_getpid(); }
pid_t getppid(void) { return cc_getppid(); }
pid_t getpgrp(void) { return cc_getpgid(); }

pid_t getpgid(pid_t pid) {
    if (pid != 0 && pid != cc_getpid()) return fail(ENOSYS);
    return cc_getpgid();
}

int setpgid(pid_t pid, pid_t pgid) {
    int err = cc_setpgid(pid, pgid);
    return err == 0 ? 0 : fail(err);
}

pid_t setsid(void) {
    int err = cc_setpgid(0, 0);
    return err == 0 ? cc_getpid() : fail(err);
}

pid_t getsid(pid_t) { return cc_getpgid(); }

int kill(pid_t pid, int sig) {
    int err = cc_kill(pid, sig);
    return err == 0 ? 0 : fail(err);
}

int killpg(pid_t pgrp, int sig) { return kill(-pgrp, sig); }

pid_t fork(void) { return fail(ENOSYS); }
pid_t vfork(void) { return fail(ENOSYS); }

int execve(const char *path, char *const argv[], char *const envp[]) { return execImpl(path, argv, envp, false); }
int execv(const char *path, char *const argv[]) { return execImpl(path, argv, environ, false); }
int execvp(const char *file, char *const argv[]) { return execImpl(file, argv, environ, true); }
int execvpe(const char *file, char *const argv[], char *const envp[]) { return execImpl(file, argv, envp, true); }

int execl(const char *path, const char *arg, ...) {
    va_list ap;
    va_start(ap, arg);
    auto args = collectArgs(arg, ap, nullptr);
    va_end(ap);
    return execv(path, args.data());
}

int execlp(const char *file, const char *arg, ...) {
    va_list ap;
    va_start(ap, arg);
    auto args = collectArgs(arg, ap, nullptr);
    va_end(ap);
    return execvp(file, args.data());
}

int execle(const char *path, const char *arg, ...) {
    va_list ap;
    va_start(ap, arg);
    char **envp = nullptr;
    auto args = collectArgs(arg, ap, &envp);
    va_end(ap);
    return execve(path, args.data(), envp);
}

int nice(int) { return 0; }

// ---- file descriptors ----

int pipe(int fds[2]) {
    int err = cc_pipe(fds);
    return err == 0 ? 0 : fail(err);
}

int pipe2(int fds[2], int flags) {
    if (pipe(fds) != 0) return -1;
    if (flags & O_NONBLOCK) {
        fcntl(fds[0], F_SETFL, fcntl(fds[0], F_GETFL) | O_NONBLOCK);
        fcntl(fds[1], F_SETFL, fcntl(fds[1], F_GETFL) | O_NONBLOCK);
    }
    return 0;
}

int dup(int fd) {
    int r = cc_dup(fd);
    return r >= 0 ? r : fail(-r);
}

int dup2(int fd, int to) {
    if (fd == to) return fcntl(fd, F_GETFL) < 0 ? -1 : to;
    int err = cc_dup2(fd, to);
    return err == 0 ? to : fail(err);
}

int dup3(int fd, int to, int) {
    if (fd == to) return fail(EINVAL);
    return dup2(fd, to);
}

// ---- temporary files ----

int mkstemp(char *templ) { return makeUnique(templ, 0, 0, false); }
int mkostemp(char *templ, int flags) { return makeUnique(templ, 0, flags, false); }
int mkstemps(char *templ, int suffix) { return makeUnique(templ, suffix, 0, false); }
int mkostemps(char *templ, int suffix, int flags) { return makeUnique(templ, suffix, flags, false); }
char *mkdtemp(char *templ) { return makeUnique(templ, 0, 0, true) == 0 ? templ : nullptr; }

char *mktemp(char *templ) {
    size_t length = std::strlen(templ);
    if (length < 6) {
        errno = EINVAL;
        templ[0] = 0;
        return templ;
    }
    struct stat st;
    do randomTail(templ + length - 6, 6);
    while (stat(templ, &st) == 0);
    return templ;
}

char *tempnam(const char *dir, const char *prefix) {
    const char *base = std::getenv("TMPDIR");
    if (!base || !*base) base = dir && *dir ? dir : "/tmp";
    char *name = (char *)std::malloc(std::strlen(base) + (prefix ? std::strlen(prefix) : 0) + 16);
    if (!name) return nullptr;
    std::sprintf(name, "%s/%.5sXXXXXX", base, prefix ? prefix : "tmp");
    return mktemp(name);
}

char *tmpnam(char *buf) {
    static char own[L_tmpnam + 16];
    char *out = buf ? buf : own;
    std::snprintf(out, L_tmpnam, "/tmp/tmpXXXXXX");
    return mktemp(out);
}

FILE *tmpfile(void) {
    char name[64];
    std::snprintf(name, sizeof(name), "%s/tmpfileXXXXXX", tmpDir());
    int fd = mkstemp(name);
    if (fd < 0) return nullptr;
    unlink(name); // gone with the last descriptor
    return fdopen(fd, "w+b");
}

// ---- signals ----

int sigemptyset(sigset_t *set) {
    std::memset(set, 0, sizeof(*set));
    return 0;
}

int sigfillset(sigset_t *set) {
    std::memset(set, 0xff, sizeof(*set));
    return 0;
}

int sigaddset(sigset_t *set, int sig) {
    if (sig <= 0 || sig > 64) return fail(EINVAL);
    ((unsigned char *)set)[(sig - 1) / 8] |= (unsigned char)(1 << ((sig - 1) % 8));
    return 0;
}

int sigdelset(sigset_t *set, int sig) {
    if (sig <= 0 || sig > 64) return fail(EINVAL);
    ((unsigned char *)set)[(sig - 1) / 8] &= (unsigned char)~(1 << ((sig - 1) % 8));
    return 0;
}

int sigismember(const sigset_t *set, int sig) {
    if (sig <= 0 || sig > 64) return fail(EINVAL);
    return (((const unsigned char *)set)[(sig - 1) / 8] >> ((sig - 1) % 8)) & 1;
}

int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
    if (sig <= 0 || sig > 64 || ((sig == SIGKILL || sig == SIGSTOP) && act)) return fail(EINVAL);
    if (old) *old = actions[sig];
    if (act) {
        actions[sig] = *act;
        if (sig == SIGINT || sig == SIGTERM) applyCatch();
    }
    return 0;
}

int sigprocmask(int how, const sigset_t *set, sigset_t *old) {
    if (old) *old = blocked;
    if (!set) return 0;
    unsigned char *b = (unsigned char *)&blocked;
    const unsigned char *s = (const unsigned char *)set;
    for (size_t i = 0; i < sizeof(blocked); i++) {
        if (how == SIG_BLOCK) b[i] |= s[i];
        else if (how == SIG_UNBLOCK) b[i] &= (unsigned char)~s[i];
        else if (how == SIG_SETMASK) b[i] = s[i];
        else return fail(EINVAL);
    }
    return 0;
}

int sigpending(sigset_t *set) { return sigemptyset(set); }
int pthread_sigmask(int how, const sigset_t *set, sigset_t *old) {
    return sigprocmask(how, set, old) == 0 ? 0 : errno;
}

// One scheduling policy; priorities are accepted and have no effect.
int sched_get_priority_max(int) { return 0; }
int sched_get_priority_min(int) { return 0; }
int sigsuspend(const sigset_t *) { return fail(EINTR); }
unsigned alarm(unsigned) { return 0; }

// ---- file systems (linked with --wrap=statvfs,--wrap=fstatvfs, like stat): from /proc/mounts ("TYPE PATH MODE CAPACITY FREE"), the longest mount point that holds path ----

int __wrap_statvfs(const char *path, struct statvfs *out) {
    char real[1024];
    if (!realpath(path, real)) return -1;
    FILE *mounts = std::fopen("/proc/mounts", "r");
    if (!mounts) return -1;
    char line[512], type[64], mount[256], mode[8];
    long long capacity = 0, free = 0, bestCapacity = 0, bestFree = 0;
    size_t best = 0;
    bool readOnly = false, found = false;
    while (std::fgets(line, sizeof(line), mounts)) {
        if (std::sscanf(line, "%63s %255s %7s %lld %lld", type, mount, mode, &capacity, &free) < 3) continue;
        size_t n = std::strlen(mount);
        bool holds = std::strcmp(mount, "/") == 0 || (std::strncmp(real, mount, n) == 0 && (real[n] == 0 || real[n] == '/'));
        if (holds && n >= best) {
            best = n;
            bestCapacity = capacity;
            bestFree = free;
            readOnly = std::strcmp(mode, "ro") == 0;
            found = true;
        }
    }
    std::fclose(mounts);
    if (!found) return fail(ENOENT);
    std::memset(out, 0, sizeof(*out));
    out->f_bsize = out->f_frsize = 1024;
    out->f_blocks = (fsblkcnt_t)(bestCapacity / 1024);
    out->f_bfree = out->f_bavail = (fsblkcnt_t)(bestFree / 1024);
    out->f_files = out->f_ffree = out->f_favail = 1 << 20;
    out->f_namemax = 255;
    out->f_flag = readOnly ? ST_RDONLY : 0;
    return 0;
}

// The descriptor's file system cannot be found from the descriptor alone: the working directory's is used.
int __wrap_fstatvfs(int fd, struct statvfs *out) {
    if (fcntl(fd, F_GETFL) < 0) return -1;
    return __wrap_statvfs(".", out);
}

// ---- resources ----

int getrlimit(int resource, struct rlimit *limit) {
    rlim_t value = RLIM_INFINITY;
    if (resource == RLIMIT_NOFILE) value = 1024;
    else if (resource == RLIMIT_STACK) value = 1024 * 1024;
    limit->rlim_cur = limit->rlim_max = value;
    return 0;
}

int setrlimit(int, const struct rlimit *) { return 0; }

// ---- sysconf (linked with --wrap=sysconf): the processor count is the kernel's (threads a process may use) ----

long __real_sysconf(int name);

long __wrap_sysconf(int name) {
    if (name == _SC_NPROCESSORS_ONLN || name == _SC_NPROCESSORS_CONF) return cc_cpu_count();
    return __real_sysconf(name);
}

// ---- the terminal ----

char *ttyname(int fd) {
    static char name[] = "/dev/tty";
    return isatty(fd) ? name : nullptr;
}

int ttyname_r(int fd, char *buf, size_t len) {
    if (!isatty(fd)) return ENOTTY;
    if (len < 9) return ERANGE;
    std::strcpy(buf, "/dev/tty");
    return 0;
}

char *ctermid(char *buf) {
    static char own[L_ctermid];
    char *out = buf ? buf : own;
    std::strcpy(out, "/dev/tty");
    return out;
}

char *getlogin(void) {
    struct passwd *pw = getpwuid(getuid());
    return pw ? pw->pw_name : nullptr;
}

int getlogin_r(char *buf, size_t len) {
    char *name = getlogin();
    if (!name) return ENOENT;
    if (std::strlen(name) >= len) return ERANGE;
    std::strcpy(buf, name);
    return 0;
}

// Reads a line from the terminal without echo.
char *getpass(const char *prompt) {
    static char buf[256];
    int fd = open("/dev/tty", O_RDWR);
    if (fd < 0) fd = 0;
    termios old{}, quiet{};
    bool tty = tcgetattr(fd, &old) == 0;
    if (tty) {
        quiet = old;
        quiet.c_lflag &= ~(tcflag_t)ECHO;
        tcsetattr(fd, TCSANOW, &quiet);
    }
    write(fd == 0 ? 2 : fd, prompt, std::strlen(prompt));
    size_t n = 0;
    char c;
    while (n + 1 < sizeof(buf) && read(fd, &c, 1) == 1 && c != '\n' && c != '\r') buf[n++] = c;
    buf[n] = 0;
    if (tty) tcsetattr(fd, TCSANOW, &old);
    write(fd == 0 ? 2 : fd, "\n", 1);
    if (fd != 0) close(fd);
    return buf;
}

int getloadavg(double loads[], int n) {
    FILE *f = std::fopen("/proc/loadavg", "r");
    if (!f) return -1;
    double l[3] = {0, 0, 0};
    int got = std::fscanf(f, "%lf %lf %lf", &l[0], &l[1], &l[2]);
    std::fclose(f);
    if (got <= 0) return -1;
    for (int i = 0; i < n && i < got; i++) loads[i] = l[i];
    return n < got ? n : got;
}

} // extern "C"
