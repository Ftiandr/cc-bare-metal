/* termios for CC: Bare Metal: terminal modes over the system's TTY (cc_tty_mode). WASI has none; programs written for
 * Linux (raw mode editors, ncurses-style code) build unchanged. Supported: ICANON (line editing), ECHO, ISIG
 * (Ctrl+C sends SIGINT); the other flags are kept but have no effect. */
#ifndef BAREMETAL_TERMIOS_H
#define BAREMETAL_TERMIOS_H

#ifdef __cplusplus
extern "C" {
#endif

typedef unsigned int tcflag_t;
typedef unsigned char cc_t;
typedef unsigned int speed_t;

#define NCCS 32
struct termios {
    tcflag_t c_iflag, c_oflag, c_cflag, c_lflag;
    cc_t c_line;
    cc_t c_cc[NCCS];
    speed_t c_ispeed, c_ospeed;
};

struct winsize {
    unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel;
};

/* c_lflag */
#define ISIG    0000001
#define ICANON  0000002
#define ECHO    0000010
#define ECHOE   0000020
#define ECHOK   0000040
#define ECHONL  0000100
#define NOFLSH  0000200
#define TOSTOP  0000400
#define IEXTEN  0100000
/* c_iflag */
#define IGNBRK  0000001
#define BRKINT  0000002
#define IGNPAR  0000004
#define PARMRK  0000010
#define INPCK   0000020
#define ISTRIP  0000040
#define INLCR   0000100
#define IGNCR   0000200
#define ICRNL   0000400
#define IXON    0002000
#define IXANY   0004000
#define IXOFF   0010000
#define IUTF8   0040000
/* c_oflag */
#define OPOST   0000001
#define ONLCR   0000004
/* c_cflag */
#define CSIZE   0000060
#define CS8     0000060
#define CREAD   0000200
#define PARENB  0000400
/* c_cc */
#define VINTR 0
#define VQUIT 1
#define VERASE 2
#define VKILL 3
#define VEOF 4
#define VTIME 5
#define VMIN 6
/* tcsetattr */
#define TCSANOW 0
#define TCSADRAIN 1
#define TCSAFLUSH 2
/* tcflush */
#define TCIFLUSH 0
#define TCOFLUSH 1
#define TCIOFLUSH 2
#define B0 0
#define B9600 9600
#define B38400 38400
#define B115200 115200

int tcgetattr(int fd, struct termios *t);
int tcsetattr(int fd, int when, const struct termios *t);
void cfmakeraw(struct termios *t);
speed_t cfgetispeed(const struct termios *t);
speed_t cfgetospeed(const struct termios *t);
int cfsetispeed(struct termios *t, speed_t s);
int cfsetospeed(struct termios *t, speed_t s);
int tcflush(int fd, int queue);
int tcdrain(int fd);
int tcgetwinsize(int fd, struct winsize *w);

#ifdef __cplusplus
}
#endif
#endif
