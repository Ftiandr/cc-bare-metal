/* CC: Bare Metal additions to the C library's <pthread.h> (programs with threads: cc -pthread): scheduling
   parameters (accepted, no effect) and pthread_sigmask (as sigprocmask), which wasi-libc hides or lacks. */
#include_next <pthread.h>
#ifndef BAREMETAL_PTHREAD_EXTRA
#define BAREMETAL_PTHREAD_EXTRA
#include <sched.h>
#include <signal.h>
#ifdef __cplusplus
extern "C" {
#endif
int pthread_getschedparam(pthread_t thread, int *policy, struct sched_param *param);
int pthread_setschedparam(pthread_t thread, int policy, const struct sched_param *param);
int pthread_sigmask(int how, const sigset_t *set, sigset_t *old);
#ifdef __cplusplus
}
#endif
#endif
