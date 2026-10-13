/* CC: Bare Metal additions to the C library's <sys/resource.h>: resource limits (lib/posix.cpp; getrlimit reports
   generous fixed limits, setrlimit accepts and ignores). wasi-libc hides these definitions. */
#ifndef _WASI_EMULATED_PROCESS_CLOCKS
#define _WASI_EMULATED_PROCESS_CLOCKS
#endif
#include_next <sys/resource.h>
#ifndef BAREMETAL_RESOURCE_EXTRA
#define BAREMETAL_RESOURCE_EXTRA
#ifdef __cplusplus
extern "C" {
#endif
typedef unsigned long long rlim_t;
struct rlimit {
    rlim_t rlim_cur;
    rlim_t rlim_max;
};
#define RLIM_INFINITY (~0ULL)
#define RLIM_SAVED_CUR RLIM_INFINITY
#define RLIM_SAVED_MAX RLIM_INFINITY
#define RLIMIT_CPU 0
#define RLIMIT_FSIZE 1
#define RLIMIT_DATA 2
#define RLIMIT_STACK 3
#define RLIMIT_CORE 4
#define RLIMIT_RSS 5
#define RLIMIT_NPROC 6
#define RLIMIT_NOFILE 7
#define RLIMIT_MEMLOCK 8
#define RLIMIT_AS 9
#define RLIM_NLIMITS 16
int getrlimit(int resource, struct rlimit *limit);
int setrlimit(int resource, const struct rlimit *limit);
#ifdef __cplusplus
}
#endif
#endif
