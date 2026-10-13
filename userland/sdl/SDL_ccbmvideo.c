/*
  SDL video driver for CC: Bare Metal (part of the system's SDL2 build; this file is MPL-2.0 like the rest of
  CC: Bare Metal, SDL itself is zlib).

  A window is the whole screen (fb0) or a pixel monitor (SDL_CCBAREMETAL_FB=fbN), in 32 bpp true colour: SDL's
  window framebuffer is XRGB8888 like ours, so updates are copied as they are (cc_gfx_blit of the changed rectangles,
  then cc_gfx_present). Rendering is SDL's software renderer on top of it.

  Input: keys from /dev/input/event0 (Linux key codes, SDL's own table), the mouse from /dev/input/event1 (absolute,
  in framebuffer pixels), text from the terminal (raw mode: typed characters as UTF-8, whatever the keyboard layout).
  Ctrl+C on the terminal becomes SDL_QUIT.
*/
#include "../../SDL_internal.h"

#ifdef SDL_VIDEO_DRIVER_CCBAREMETAL

#include "SDL_video.h"
#include "SDL_mouse.h"
#include "../SDL_sysvideo.h"
#include "../SDL_pixels_c.h"
#include "../../events/SDL_events_c.h"
#include "../../events/SDL_keyboard_c.h"
#include "../../events/SDL_mouse_c.h"
#include "../../events/scancodes_linux.h"

#include <baremetal.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define CCBM_SURFACE "_SDL_CCBMSurface"

static int keyboardFd = -1, mouseFd = -1;
static int oldTtyMode = -1;
static int maxW = 1280, maxH = 800;
static int onScreen = 1;
static char text[64];
static int textLength;

static void readSysfs(const char *fb, const char *attr, char *out, size_t len)
{
    char path[128];
    FILE *f;
    out[0] = 0;
    snprintf(path, sizeof(path), "/sys/class/graphics/%s/%s", fb, attr);
    f = fopen(path, "r");
    if (!f) {
        return;
    }
    if (fgets(out, (int)len, f)) {
        out[strcspn(out, "\n")] = 0;
    }
    fclose(f);
}

static int CCBM_VideoInit(_THIS)
{
    SDL_DisplayMode mode;
    const char *fb = SDL_getenv("SDL_CCBAREMETAL_FB");
    char value[128];
    if (!fb || !*fb) {
        fb = "fb0";
    }
    if (SDL_strncmp(fb, "/dev/", 5) == 0) {
        fb += 5;
    }
    if (SDL_strcmp(fb, "fb0") != 0) {
        readSysfs(fb, "device", value, sizeof(value));
        if (!value[0] || cc_gfx_target(value) != 0) {
            return SDL_SetError("%s: no framebuffer to draw on (lsdev lists them)", fb);
        }
        onScreen = 0;
    }
    readSysfs(fb, "max_size", value, sizeof(value));
    if (sscanf(value, "%d,%d", &maxW, &maxH) != 2) {
        maxW = 1280;
        maxH = 800;
    }

    SDL_zero(mode);
    mode.format = SDL_PIXELFORMAT_RGB888;
    mode.w = maxW;
    mode.h = maxH;
    mode.refresh_rate = 20; /* what players see: the screen is sent once per server tick */
    if (SDL_AddBasicVideoDisplay(&mode) < 0) {
        return -1;
    }
    SDL_AddDisplayMode(&_this->displays[0], &mode);

    keyboardFd = open("/dev/input/event0", O_RDONLY | O_NONBLOCK);
    mouseFd = open("/dev/input/event1", O_RDONLY | O_NONBLOCK);
    if (isatty(0)) {
        /* typed text arrives on the terminal: quiet, unbuffered, and Ctrl+C as a byte (SDL_QUIT) */
        oldTtyMode = cc_tty_mode(CC_TTY_RAW | CC_TTY_NOECHO | CC_TTY_NOSIG);
        fcntl(0, F_SETFL, fcntl(0, F_GETFL) | O_NONBLOCK);
    }
    return 0;
}

static void CCBM_VideoQuit(_THIS)
{
    if (keyboardFd >= 0) {
        close(keyboardFd);
    }
    if (mouseFd >= 0) {
        close(mouseFd);
    }
    keyboardFd = mouseFd = -1;
    if (oldTtyMode >= 0) {
        fcntl(0, F_SETFL, fcntl(0, F_GETFL) & ~O_NONBLOCK);
        cc_tty_mode(oldTtyMode);
        oldTtyMode = -1;
    }
    cc_gfx_mode(0, 0, 0, 0);
    if (!onScreen) {
        cc_gfx_target(NULL);
    }
}

/* Windows are at most the framebuffer's size; there is only the one picture. */
static int CCBM_CreateWindow(_THIS, SDL_Window *window)
{
    if (window->w > maxW) {
        window->w = maxW;
    }
    if (window->h > maxH) {
        window->h = maxH;
    }
    SDL_SetKeyboardFocus(window);
    SDL_SetMouseFocus(window);
    return 0;
}

static void CCBM_SetWindowSize(_THIS, SDL_Window *window)
{
    if (window->w > maxW) {
        window->w = maxW;
    }
    if (window->h > maxH) {
        window->h = maxH;
    }
}

static void CCBM_DestroyWindowFramebuffer(_THIS, SDL_Window *window);

static int CCBM_CreateWindowFramebuffer(_THIS, SDL_Window *window, Uint32 *format, void **pixels, int *pitch)
{
    SDL_Surface *surface;
    int w, h;
    CCBM_DestroyWindowFramebuffer(_this, window);
    SDL_GetWindowSizeInPixels(window, &w, &h);
    if (w > maxW) {
        w = maxW;
    }
    if (h > maxH) {
        h = maxH;
    }
    if (cc_gfx_mode(w, h, 32, 0) != 0) {
        return SDL_SetError("cannot show %dx%d graphics", w, h);
    }
    surface = SDL_CreateRGBSurfaceWithFormat(0, w, h, 0, SDL_PIXELFORMAT_RGB888);
    if (!surface) {
        return -1;
    }
    SDL_SetWindowData(window, CCBM_SURFACE, surface);
    *format = SDL_PIXELFORMAT_RGB888;
    *pixels = surface->pixels;
    *pitch = surface->pitch;
    return 0;
}

static int CCBM_UpdateWindowFramebuffer(_THIS, SDL_Window *window, const SDL_Rect *rects, int numrects)
{
    SDL_Surface *surface = (SDL_Surface *)SDL_GetWindowData(window, CCBM_SURFACE);
    int i, stride;
    if (!surface) {
        return SDL_SetError("no framebuffer surface for the window");
    }
    stride = surface->pitch / 4;
    for (i = 0; i < numrects; i++) {
        SDL_Rect r = rects[i];
        SDL_Rect all = { 0, 0, surface->w, surface->h };
        if (!SDL_IntersectRect(&r, &all, &r)) {
            continue;
        }
        cc_gfx_blit(r.x, r.y, r.w, r.h, (const uint8_t *)surface->pixels + (r.y * stride + r.x) * 4, stride);
    }
    cc_gfx_present();
    return 0;
}

static void CCBM_DestroyWindowFramebuffer(_THIS, SDL_Window *window)
{
    SDL_Surface *surface = (SDL_Surface *)SDL_SetWindowData(window, CCBM_SURFACE, NULL);
    SDL_FreeSurface(surface);
}

/* struct input_event as the kernel writes it: int64 seconds, int64 microseconds, u16 type, u16 code, s32 value */
static int readEvent(int fd, uint16_t *type, uint16_t *code, int32_t *value)
{
    uint8_t buf[24];
    if (fd < 0 || read(fd, buf, sizeof(buf)) != (ssize_t)sizeof(buf)) {
        return 0;
    }
    memcpy(type, buf + 16, 2);
    memcpy(code, buf + 18, 2);
    memcpy(value, buf + 20, 4);
    return 1;
}

static void flushText(void)
{
    if (textLength > 0) {
        text[textLength] = 0;
        SDL_SendKeyboardText(text);
        textLength = 0;
    }
}

static void CCBM_PumpEvents(_THIS)
{
    SDL_Window *window = _this->windows;
    uint16_t type, code;
    int32_t value;
    unsigned char bytes[64];
    ssize_t n;

    while (readEvent(keyboardFd, &type, &code, &value)) {
        if (type == 1 && code < SDL_arraysize(linux_scancode_table)) {
            SDL_Scancode sc = linux_scancode_table[code];
            if (sc != SDL_SCANCODE_UNKNOWN) {
                SDL_SendKeyboardKey(value ? SDL_PRESSED : SDL_RELEASED, sc);
            }
        }
    }
    while (readEvent(mouseFd, &type, &code, &value)) {
        if (!window) {
            continue;
        }
        if (type == 3) { /* EV_ABS: pixels of the framebuffer */
            SDL_Mouse *mouse = SDL_GetMouse();
            int x = code == 0 ? value : mouse->x, y = code == 1 ? value : mouse->y;
            SDL_SendMouseMotion(window, 0, 0, x, y);
        } else if (type == 1) {
            Uint8 button = code == 0x110 ? SDL_BUTTON_LEFT : code == 0x111 ? SDL_BUTTON_RIGHT : code == 0x112 ? SDL_BUTTON_MIDDLE : 0;
            if (button) {
                SDL_SendMouseButton(window, 0, value ? SDL_PRESSED : SDL_RELEASED, button);
            }
        } else if (type == 2 && code == 8) { /* REL_WHEEL */
            SDL_SendMouseWheel(window, 0, 0, (float)value, SDL_MOUSEWHEEL_NORMAL);
        }
    }
    if (oldTtyMode >= 0) {
        /* text: printable characters (escape sequences and control keys come as key events instead) */
        while ((n = read(0, bytes, sizeof(bytes))) > 0) {
            ssize_t i;
            for (i = 0; i < n; i++) {
                unsigned char c = bytes[i];
                if (c == 3) {
                    SDL_SendQuit();
                    continue;
                }
                if (c == 27) { /* an escape sequence: skip it */
                    i++;
                    if (i < n && (bytes[i] == '[' || bytes[i] == 'O')) {
                        i++;
                        while (i < n && !(bytes[i] >= 0x40 && bytes[i] <= 0x7e)) {
                            i++;
                        }
                    }
                    continue;
                }
                if (c < 0x20 || c == 0x7f) {
                    continue;
                }
                /* keep UTF-8 sequences whole */
                if (textLength + 4 >= (int)sizeof(text) || (c < 0x80 || c >= 0xc0)) {
                    flushText();
                }
                text[textLength++] = (char)c;
            }
            flushText();
        }
    }
}

static void CCBM_DeleteDevice(SDL_VideoDevice *device)
{
    SDL_free(device);
}

static SDL_VideoDevice *CCBM_CreateDevice(void)
{
    SDL_VideoDevice *device = (SDL_VideoDevice *)SDL_calloc(1, sizeof(SDL_VideoDevice));
    if (!device) {
        SDL_OutOfMemory();
        return NULL;
    }
    device->VideoInit = CCBM_VideoInit;
    device->VideoQuit = CCBM_VideoQuit;
    device->PumpEvents = CCBM_PumpEvents;
    device->CreateSDLWindow = CCBM_CreateWindow;
    device->SetWindowSize = CCBM_SetWindowSize;
    device->CreateWindowFramebuffer = CCBM_CreateWindowFramebuffer;
    device->UpdateWindowFramebuffer = CCBM_UpdateWindowFramebuffer;
    device->DestroyWindowFramebuffer = CCBM_DestroyWindowFramebuffer;
    device->free = CCBM_DeleteDevice;
    return device;
}

VideoBootStrap CCBAREMETAL_bootstrap = {
    "ccbaremetal", "CC: Bare Metal framebuffer",
    CCBM_CreateDevice,
    NULL
};

#endif /* SDL_VIDEO_DRIVER_CCBAREMETAL */
