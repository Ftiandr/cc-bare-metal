/* CC: Bare Metal additions to the C library's <stdlib.h>: system() (runs the command with sh -c), temporary files. */
#include_next <stdlib.h>
#ifndef BAREMETAL_STDLIB_EXTRA
#define BAREMETAL_STDLIB_EXTRA
#ifdef __cplusplus
extern "C" {
#endif
int system(const char *command);
/* temporary files (lib/posix.cpp) */
int mkstemp(char *templ);
int mkostemp(char *templ, int flags);
int mkstemps(char *templ, int suffix);
int mkostemps(char *templ, int suffix, int flags);
char *mkdtemp(char *templ);
char *mktemp(char *templ);
int getloadavg(double loads[], int n);
#ifdef __cplusplus
}
#endif
#endif
