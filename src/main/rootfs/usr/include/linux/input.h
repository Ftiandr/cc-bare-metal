/* Linux evdev records, as read from /dev/input/event0 (keyboard), /dev/input/event1 (mouse) and
 * /dev/input/event2... (touchscreens: pixel monitors, then CC monitors) on CC: Bare Metal.
 * Mouse positions are absolute: EV_ABS ABS_X/ABS_Y in framebuffer pixels (graphics) or character cells from 0
 * (text). A touch is ABS_X, ABS_Y, BTN_TOUCH 1, SYN_REPORT, BTN_TOUCH 0, SYN_REPORT (pixels of the monitor, or
 * cells from 0 for CC monitors). Values of EV_KEY: 1 press, 0 release, 2 repeat. There is no ioctl: which device
 * is which, and its size, are in /sys/class/input/eventN (name, device, fb, size). */
#ifndef _LINUX_INPUT_H
#define _LINUX_INPUT_H

#include <stdint.h>
#include <sys/time.h>
#include <linux/input-event-codes.h>

struct input_event {
    struct timeval time; /* 16 bytes: 64-bit seconds and microseconds */
    uint16_t type;
    uint16_t code;
    int32_t value;
};

#ifdef __cplusplus
static_assert(sizeof(struct input_event) == 24, "input_event layout");
#else
_Static_assert(sizeof(struct input_event) == 24, "input_event layout");
#endif

#endif
