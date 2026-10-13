/* posix_spawn for CC: Bare Metal (there is no fork in WebAssembly; spawning is how programs start others). File actions
 * support redirecting stdin/stdout/stderr (dup2 to 0-2, open to 0-2); close is accepted and ignored. */
#ifndef BAREMETAL_SPAWN_H
#define BAREMETAL_SPAWN_H
#include <sys/types.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct {
    int fds[3]; /* what becomes 0, 1, 2 (-1: inherit) */
    int error;
} posix_spawn_file_actions_t;
typedef struct {
    short flags;
} posix_spawnattr_t;
#define POSIX_SPAWN_SETPGROUP 2
int posix_spawn_file_actions_init(posix_spawn_file_actions_t *fa);
int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t *fa);
int posix_spawn_file_actions_adddup2(posix_spawn_file_actions_t *fa, int fd, int newfd);
int posix_spawn_file_actions_addclose(posix_spawn_file_actions_t *fa, int fd);
int posix_spawn_file_actions_addopen(posix_spawn_file_actions_t *fa, int newfd, const char *path, int oflag, mode_t mode);
int posix_spawnattr_init(posix_spawnattr_t *a);
int posix_spawnattr_destroy(posix_spawnattr_t *a);
int posix_spawnattr_setflags(posix_spawnattr_t *a, short flags);
int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *attr,
                char *const argv[], char *const envp[]);
int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *attr,
                 char *const argv[], char *const envp[]);
#ifdef __cplusplus
}
#endif
#endif
