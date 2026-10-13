/* CC: Bare Metal additions to <sys/ioctl.h>: TIOCGWINSZ (terminal size) works. */
#include_next <sys/ioctl.h>
#ifndef BAREMETAL_IOCTL_EXTRA
#define BAREMETAL_IOCTL_EXTRA
#include <termios.h>
#define TIOCGWINSZ 0x5413
#define TIOCSWINSZ 0x5414
#ifdef __cplusplus
extern "C" {
#endif
int ccbaremetal_ioctl(int fd, int request, ...);
#ifdef __cplusplus
}
#endif
#ifndef BAREMETAL_NO_IOCTL_WRAP
#define ioctl(fd, request, ...) ccbaremetal_ioctl(fd, request, ##__VA_ARGS__)
#endif
#endif
