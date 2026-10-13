/* CC: Bare Metal additions to the C library's <stdio.h>: popen() and pclose() (run a command through sh), tempnam(). */
#include_next <stdio.h>
#ifndef BAREMETAL_STDIO_EXTRA
#define BAREMETAL_STDIO_EXTRA
#ifdef __cplusplus
extern "C" {
#endif
FILE *popen(const char *command, const char *mode);
int pclose(FILE *stream);
/* temporary files (lib/posix.cpp) */
#ifndef L_tmpnam
#define L_tmpnam 20
#endif
#ifndef TMP_MAX
#define TMP_MAX 10000
#endif
char *tempnam(const char *dir, const char *prefix);
char *ctermid(char *buf);
#ifdef __cplusplus
}
#endif
#endif
