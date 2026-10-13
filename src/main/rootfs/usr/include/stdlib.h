/* CC: Bare Metal additions to the C library's <stdlib.h>: system() (runs the command with sh -c). */
#include_next <stdlib.h>
#ifndef BAREMETAL_STDLIB_EXTRA
#define BAREMETAL_STDLIB_EXTRA
#ifdef __cplusplus
extern "C" {
#endif
int system(const char *command);
#ifdef __cplusplus
}
#endif
#endif
