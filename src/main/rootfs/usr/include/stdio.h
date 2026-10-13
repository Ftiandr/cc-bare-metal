/* CC: Bare Metal additions to the C library's <stdio.h>: popen() and pclose() (run a command through sh). */
#include_next <stdio.h>
#ifndef BAREMETAL_STDIO_EXTRA
#define BAREMETAL_STDIO_EXTRA
#ifdef __cplusplus
extern "C" {
#endif
FILE *popen(const char *command, const char *mode);
int pclose(FILE *stream);
#ifdef __cplusplus
}
#endif
#endif
