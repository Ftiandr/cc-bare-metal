// POSIX pieces WASI lacks, on top of the CC: Bare Metal system calls: termios, waitpid, posix_spawn, system, popen,
// ioctl(TIOCGWINSZ). Part of libbaremetal, so programs written for Linux build unchanged.
#define BAREMETAL_NO_IOCTL_WRAP
#include <baremetal.hpp>

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <spawn.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <vector>

extern char **environ;

namespace {

std::vector<std::string> toVector(char *const list[]) {
    std::vector<std::string> out;
    for (int i = 0; list && list[i]; i++) out.emplace_back(list[i]);
    return out;
}

// Exit status in the POSIX layout: code << 8 (signals are reported by the kernel as 128 + signal).
int posixStatus(int code) {
    if (code > 128 && code < 160) return code - 128;
    return (code & 0xff) << 8;
}

std::map<FILE *, int> &popenChildren() {
    static std::map<FILE *, int> children;
    return children;
}

} // namespace

extern "C" {

// ---- termios -------------------------------------------------------------------------------------------------------

int tcgetattr(int fd, struct termios *t) {
    if (!t) {
        errno = EINVAL;
        return -1;
    }
    if (!isatty(fd)) {
        errno = ENOTTY;
        return -1;
    }
    std::memset(t, 0, sizeof(*t));
    int mode = cc_tty_mode(-1);
    t->c_iflag = IXON | IUTF8;
    if (!(mode & CC_TTY_RAW) || (mode & CC_TTY_ICRNL)) t->c_iflag |= ICRNL;
    t->c_oflag = OPOST | ONLCR;
    t->c_cflag = CS8 | CREAD;
    t->c_lflag = IEXTEN | ECHOE | ECHOK;
    if (!(mode & CC_TTY_RAW)) t->c_lflag |= ICANON;
    if (!(mode & CC_TTY_NOECHO)) t->c_lflag |= ECHO;
    if (!(mode & CC_TTY_NOSIG)) t->c_lflag |= ISIG;
    t->c_cc[VINTR] = 3;
    t->c_cc[VQUIT] = 28;
    t->c_cc[VERASE] = 127;
    t->c_cc[VKILL] = 21;
    t->c_cc[VEOF] = 4;
    t->c_cc[VMIN] = 1;
    if (mode & CC_TTY_TIMED) {
        t->c_cc[VMIN] = static_cast<cc_t>((mode >> 8) & 0xff);
        t->c_cc[VTIME] = static_cast<cc_t>((mode >> 16) & 0xff);
    }
    t->c_ispeed = t->c_ospeed = B38400;
    return 0;
}

int tcsetattr(int fd, int, const struct termios *t) {
    if (!t) {
        errno = EINVAL;
        return -1;
    }
    if (!isatty(fd)) {
        errno = ENOTTY;
        return -1;
    }
    int mode = 0;
    if (!(t->c_lflag & ICANON)) mode |= CC_TTY_RAW;
    if (!(t->c_lflag & ECHO)) mode |= CC_TTY_NOECHO;
    if (!(t->c_lflag & ISIG)) mode |= CC_TTY_NOSIG;
    if (t->c_iflag & ICRNL) mode |= CC_TTY_ICRNL;
    // VMIN 1 / VTIME 0 is the plain blocking read; anything else is passed on.
    if (t->c_cc[VMIN] != 1 || t->c_cc[VTIME] != 0) mode |= CC_TTY_TIMED | (t->c_cc[VMIN] << 8) | (t->c_cc[VTIME] << 16);
    cc_tty_mode(mode);
    return 0;
}

void cfmakeraw(struct termios *t) {
    t->c_iflag &= ~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON);
    t->c_oflag &= ~OPOST;
    t->c_lflag &= ~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
    t->c_cflag &= ~(CSIZE | PARENB);
    t->c_cflag |= CS8;
    t->c_cc[VMIN] = 1;
    t->c_cc[VTIME] = 0;
}

speed_t cfgetispeed(const struct termios *t) {
    return t->c_ispeed;
}

speed_t cfgetospeed(const struct termios *t) {
    return t->c_ospeed;
}

int cfsetispeed(struct termios *t, speed_t s) {
    t->c_ispeed = s;
    return 0;
}

int cfsetospeed(struct termios *t, speed_t s) {
    t->c_ospeed = s;
    return 0;
}

int tcflush(int, int) {
    return 0;
}

int tcdrain(int) {
    return 0;
}

int tcgetwinsize(int fd, struct winsize *w) {
    if (!isatty(fd)) {
        errno = ENOTTY;
        return -1;
    }
    int32_t cols = 0, rows = 0;
    cc_tty_size(&cols, &rows);
    w->ws_col = static_cast<unsigned short>(cols);
    w->ws_row = static_cast<unsigned short>(rows);
    w->ws_xpixel = static_cast<unsigned short>(cols * 6);
    w->ws_ypixel = static_cast<unsigned short>(rows * 9);
    return 0;
}

int ccbaremetal_ioctl(int fd, int request, ...) {
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (request == TIOCGWINSZ) return tcgetwinsize(fd, static_cast<struct winsize *>(arg));
    if (request == TIOCSWINSZ) return 0;
    // device controls the kernel knows (OSS on /dev/dspN...); anything else as the C library does it
    int err = cc_fd_ioctl(fd, request, arg);
    if (err == 0) return 0;
    if (err != ENOTTY) {
        errno = err;
        return -1;
    }
    return (ioctl)(fd, request, arg);
}

// ---- processes ----------------------------------------------------------------------------------------------------

pid_t waitpid(pid_t pid, int *status, int options) {
    int32_t code = 0;
    int r = cc_wait(pid, (options & WNOHANG) ? CC_WNOHANG : 0, &code);
    if (r < 0) {
        errno = -r;
        return -1;
    }
    if (r > 0 && status) *status = posixStatus(code);
    return r;
}

pid_t wait(int *status) {
    return waitpid(-1, status, 0);
}

int posix_spawn_file_actions_init(posix_spawn_file_actions_t *fa) {
    fa->fds[0] = fa->fds[1] = fa->fds[2] = -1;
    fa->error = 0;
    return 0;
}

int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t *) {
    return 0;
}

int posix_spawn_file_actions_adddup2(posix_spawn_file_actions_t *fa, int fd, int newfd) {
    if (newfd < 0 || newfd > 2) return fa->error = ENOTSUP;
    fa->fds[newfd] = fd;
    return 0;
}

int posix_spawn_file_actions_addclose(posix_spawn_file_actions_t *, int) {
    return 0;
}

int posix_spawn_file_actions_addopen(posix_spawn_file_actions_t *fa, int newfd, const char *path, int oflag, mode_t mode) {
    if (newfd < 0 || newfd > 2) return fa->error = ENOTSUP;
    int fd = open(path, oflag, mode);
    if (fd < 0) return fa->error = errno;
    fa->fds[newfd] = fd;
    return 0;
}

int posix_spawnattr_init(posix_spawnattr_t *a) {
    a->flags = 0;
    return 0;
}

int posix_spawnattr_destroy(posix_spawnattr_t *) {
    return 0;
}

int posix_spawnattr_setflags(posix_spawnattr_t *a, short flags) {
    a->flags = flags;
    return 0;
}

static int spawnWith(pid_t *pid, const char *path, bool search, const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *attr,
                     char *const argv[], char *const envp[]) {
    if (fa && fa->error) return fa->error;
    cc::SpawnOptions o;
    if (!search || std::strchr(path, '/')) o.program = path;
    if (envp && envp != environ) o.env = toVector(envp);
    if (fa) {
        o.in = fa->fds[0];
        o.out = fa->fds[1];
        o.err = fa->fds[2];
    }
    if (attr && (attr->flags & POSIX_SPAWN_SETPGROUP)) o.flags |= CC_SPAWN_NEW_GROUP;
    auto args = toVector(argv);
    if (args.empty()) args.emplace_back(path);
    if (search && !std::strchr(path, '/')) args[0] = path;
    std::fflush(nullptr);
    int r = cc::spawn(args, o);
    if (r < 0) return -r;
    if (pid) *pid = r;
    return 0;
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *attr, char *const argv[],
                char *const envp[]) {
    return spawnWith(pid, path, false, fa, attr, argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *attr, char *const argv[],
                 char *const envp[]) {
    return spawnWith(pid, file, true, fa, attr, argv, envp);
}

int system(const char *command) {
    if (!command) return 1; // a shell is available
    std::fflush(nullptr);
    int r = cc::run({"sh", "-c", command});
    if (r < 0) return 127 << 8;
    return posixStatus(r);
}

FILE *popen(const char *command, const char *mode) {
    if (!command || !mode || (mode[0] != 'r' && mode[0] != 'w')) {
        errno = EINVAL;
        return nullptr;
    }
    int32_t fds[2];
    if (cc_pipe(fds) != 0) return nullptr;
    bool reading = mode[0] == 'r';
    cc::SpawnOptions o;
    if (reading) o.out = fds[1];
    else o.in = fds[0];
    std::fflush(nullptr);
    int pid = cc::spawn({"sh", "-c", command}, o);
    close(reading ? fds[1] : fds[0]);
    if (pid < 0) {
        close(reading ? fds[0] : fds[1]);
        errno = -pid;
        return nullptr;
    }
    FILE *f = fdopen(reading ? fds[0] : fds[1], reading ? "r" : "w");
    if (f) popenChildren()[f] = pid;
    return f;
}

int pclose(FILE *stream) {
    auto &children = popenChildren();
    auto it = children.find(stream);
    if (it == children.end()) {
        errno = ECHILD;
        return -1;
    }
    int pid = it->second;
    children.erase(it);
    std::fclose(stream);
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) return -1;
    return status;
}

} // extern "C"
