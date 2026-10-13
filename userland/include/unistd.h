/* CC: Bare Metal additions to the C library's <unistd.h>: users, processes, pipes and exec (WASI has none), implemented in libbaremetal. */
#include_next <unistd.h>
#ifndef BAREMETAL_UNISTD_EXTRA
#define BAREMETAL_UNISTD_EXTRA
#include <sys/types.h>
#ifdef __cplusplus
extern "C" {
#endif
uid_t getuid(void);
uid_t geteuid(void);
gid_t getgid(void);
gid_t getegid(void);
int getgroups(int size, gid_t list[]);
int setuid(uid_t uid);
int seteuid(uid_t uid);
int setgid(gid_t gid);
int setegid(gid_t gid);
int setreuid(uid_t ruid, uid_t euid);
int setregid(gid_t rgid, gid_t egid);
int setresuid(uid_t ruid, uid_t euid, uid_t suid);
int setresgid(gid_t rgid, gid_t egid, gid_t sgid);
int chown(const char *path, uid_t uid, gid_t gid);
int fchown(int fd, uid_t uid, gid_t gid);
int lchown(const char *path, uid_t uid, gid_t gid);
/* processes, pipes and exec (lib/posix.cpp): fork() fails with ENOSYS (use posix_spawn); exec runs the program,
   waits and exits with its status */
pid_t getppid(void);
pid_t getpgrp(void);
pid_t getpgid(pid_t pid);
int setpgid(pid_t pid, pid_t pgid);
pid_t setsid(void);
pid_t getsid(pid_t pid);
pid_t fork(void);
pid_t vfork(void);
int execve(const char *path, char *const argv[], char *const envp[]);
int execv(const char *path, char *const argv[]);
int execvp(const char *file, char *const argv[]);
int execvpe(const char *file, char *const argv[], char *const envp[]);
int execl(const char *path, const char *arg, ...);
int execlp(const char *file, const char *arg, ...);
int execle(const char *path, const char *arg, ...);
int pipe2(int fds[2], int flags);
int dup3(int fd, int to, int flags);
int nice(int inc);
char *ttyname(int fd);
int ttyname_r(int fd, char *buf, size_t len);
char *getpass(const char *prompt);
int getlogin_r(char *buf, size_t len);
#ifdef __cplusplus
}
#endif
#endif
