/* CC: Bare Metal additions to the C library's <signal.h>: signal sets, sigaction and sigprocmask (lib/posix.cpp).
   Dispositions are remembered; SIGINT/SIGTERM end the process unless set to SIG_IGN (no asynchronous handlers). */
#ifndef _WASI_EMULATED_SIGNAL
#define _WASI_EMULATED_SIGNAL
#endif
#include_next <signal.h>
#ifndef BAREMETAL_SIGNAL_EXTRA
#define BAREMETAL_SIGNAL_EXTRA
#include <sys/types.h>
/* sigset_t: wasi-libc's <signal.h> only defines it in a part it hides */
#define __NEED_sigset_t
#include <bits/alltypes.h>
#ifdef __cplusplus
extern "C" {
#endif
/* what wasi-libc's header hides (as musl defines it) */
#define SIG_BLOCK 0
#define SIG_UNBLOCK 1
#define SIG_SETMASK 2
#define SA_NOCLDSTOP 1
#define SA_NOCLDWAIT 2
#define SA_SIGINFO 4
#define SA_ONSTACK 0x08000000
#define SA_RESTART 0x10000000
#define SA_NODEFER 0x40000000
#define SA_RESETHAND 0x80000000
union sigval {
    int sival_int;
    void *sival_ptr;
};
typedef struct {
    int si_signo, si_errno, si_code;
    pid_t si_pid;
    uid_t si_uid;
    int si_status;
    void *si_addr;
    union sigval si_value;
} siginfo_t;
struct sigaction {
    union {
        void (*sa_handler)(int);
        void (*sa_sigaction)(int, siginfo_t *, void *);
    } __sa_handler;
    sigset_t sa_mask;
    int sa_flags;
    void (*sa_restorer)(void);
};
#define sa_handler __sa_handler.sa_handler
#define sa_sigaction __sa_handler.sa_sigaction
int sigemptyset(sigset_t *set);
int sigfillset(sigset_t *set);
int sigaddset(sigset_t *set, int sig);
int sigdelset(sigset_t *set, int sig);
int sigismember(const sigset_t *set, int sig);
int sigaction(int sig, const struct sigaction *act, struct sigaction *old);
int sigprocmask(int how, const sigset_t *set, sigset_t *old);
int sigpending(sigset_t *set);
int sigsuspend(const sigset_t *mask);
int kill(pid_t pid, int sig);
int killpg(pid_t pgrp, int sig);
#ifdef __cplusplus
}
#endif
#endif
