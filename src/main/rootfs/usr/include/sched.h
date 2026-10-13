/* CC: Bare Metal additions to the C library's <sched.h>: the policy names and priority ranges (lib/posix.cpp) that
   wasi-libc hides. There is one policy, and priorities have no effect. */
#include_next <sched.h>
#ifndef BAREMETAL_SCHED_EXTRA
#define BAREMETAL_SCHED_EXTRA
#ifdef __cplusplus
extern "C" {
#endif
#ifndef SCHED_OTHER
#define SCHED_OTHER 0
#define SCHED_FIFO 1
#define SCHED_RR 2
#define SCHED_BATCH 3
#define SCHED_IDLE 5
#endif
int sched_get_priority_max(int policy);
int sched_get_priority_min(int policy);
#ifdef __cplusplus
}
#endif
#endif
