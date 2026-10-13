/* CC: Bare Metal additions to the C library's <sys/stat.h>: umask (WASI has none). stat() reports real modes and
 * owners and chmod() works through libbaremetal (the compiler links them with -Wl,--wrap). */
#include_next <sys/stat.h>
#ifndef BAREMETAL_SYS_STAT_EXTRA
#define BAREMETAL_SYS_STAT_EXTRA
#ifdef __cplusplus
extern "C" {
#endif
mode_t umask(mode_t mask);
#ifdef __cplusplus
}
#endif
#endif
