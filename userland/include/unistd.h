/* CC: Bare Metal additions to the C library's <unistd.h>: users (WASI has none), implemented in libbaremetal. */
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
#ifdef __cplusplus
}
#endif
#endif
