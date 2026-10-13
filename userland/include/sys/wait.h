/* waitpid() for CC: Bare Metal (WASI has none). Children are started with posix_spawn(), system() or cc_spawn(). */
#ifndef BAREMETAL_SYS_WAIT_H
#define BAREMETAL_SYS_WAIT_H
#include <sys/types.h>
#ifdef __cplusplus
extern "C" {
#endif
#define WNOHANG 1
#define WUNTRACED 2
#define WEXITSTATUS(s) (((s) >> 8) & 0xff)
#define WTERMSIG(s) ((s) & 0x7f)
#define WIFEXITED(s) (WTERMSIG(s) == 0)
#define WIFSIGNALED(s) (WTERMSIG(s) != 0)
#define WIFSTOPPED(s) 0
pid_t waitpid(pid_t pid, int *status, int options);
pid_t wait(int *status);
#ifdef __cplusplus
}
#endif
#endif
