/*
 * baremetal.h - system interface of the CC: Bare Metal kernel.
 *
 * Programs are ordinary WASI (wasm32-wasip1) programs; this header adds the kernel's own syscalls (import module
 * "ccbaremetal"): processes, terminal control, events, graphics, CC peripherals, Lua and redstone. It is the ABI and
 * works from C and C++; C++ programs normally use the classes in <baremetal.hpp> instead.
 *
 * Variable-length results (JSON text) are kept by the kernel and fetched with cc_take(); the helpers in libbaremetal
 * do this for you (the C ones return malloc'd strings).
 */
#ifndef BAREMETAL_H
#define BAREMETAL_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BAREMETAL_IMPORT(name) __attribute__((import_module("ccbaremetal"), import_name(name)))

/* ---- Processes ------------------------------------------------------------------------------------------------ */

#define CC_SPAWN_NEW_GROUP 1 /* child gets its own process group (a "job") */
#define CC_SPAWN_FOREGROUND 2 /* ...and becomes the terminal's foreground job */

/* argv/env are buffers of NUL-terminated strings; env == NULL inherits. fds of -1 inherit 0/1/2. */
BAREMETAL_IMPORT("spawn") int32_t __cc_spawn(const char *path, int32_t path_len, const char *argv, int32_t argv_len,
                                         const char *env, int32_t env_len, int32_t fd_in, int32_t fd_out,
                                         int32_t fd_err, int32_t flags, int32_t *pid_out);
/* Returns pid, 0 (CC_WNOHANG and nothing exited) or -errno. pid -1 waits for any child. */
#define CC_WNOHANG 1
BAREMETAL_IMPORT("wait") int32_t cc_wait(int32_t pid, int32_t options, int32_t *status);
BAREMETAL_IMPORT("kill") int32_t cc_kill(int32_t pid, int32_t sig);
BAREMETAL_IMPORT("getpid") int32_t cc_getpid(void);
BAREMETAL_IMPORT("getppid") int32_t cc_getppid(void);
BAREMETAL_IMPORT("getpgid") int32_t cc_getpgid(void);
BAREMETAL_IMPORT("setpgid") int32_t cc_setpgid(int32_t pid, int32_t pgid);
BAREMETAL_IMPORT("pipe") int32_t cc_pipe(int32_t fds[2]);
BAREMETAL_IMPORT("dup") int32_t cc_dup(int32_t fd);
BAREMETAL_IMPORT("dup2") int32_t cc_dup2(int32_t fd, int32_t to);
BAREMETAL_IMPORT("sleep") int32_t cc_sleep_ms(int32_t ms);
BAREMETAL_IMPORT("setcwd") int32_t __cc_setcwd(const char *path, int32_t len);
/* Bits: 1 = receive machine events, 2 = receive keyboard/mouse as events (when foreground), 4 = Ctrl+C/T
 * become a "terminate" event instead of killing. Pass -1 to only query. Returns the old flags. */
#define CC_PROC_EVENTS 1
#define CC_PROC_INPUT 2
#define CC_PROC_CATCH 4
BAREMETAL_IMPORT("proc_flags") int32_t cc_proc_flags(int32_t flags);

#define CC_SIGINT 2
#define CC_SIGKILL 9
#define CC_SIGTERM 15

/* ---- Users and permissions ------------------------------------------------------------------------------------ */
/* Normally used through the POSIX functions in libbaremetal (getuid, setresuid, chmod, chown, stat, getpwnam...). */

/* Writes [uid, euid, gid, egid, ngroups, groups...] (at most cap ints); returns the number written. */
BAREMETAL_IMPORT("cred_get") int32_t cc_cred_get(int32_t *out, int32_t cap);
/* -1 keeps a value; ngroups < 0 keeps the groups. Root may set anything, others only swap real/effective ids. */
BAREMETAL_IMPORT("cred_set") int32_t cc_cred_set(int32_t uid, int32_t euid, int32_t gid, int32_t egid, const int32_t *groups,
                                             int32_t ngroups);
/* Sets the umask (mask < 0: only query); returns the old one. */
BAREMETAL_IMPORT("umask") int32_t cc_umask(int32_t mask);
/* [mode (with S_IF* type bits), uid, gid] of a path or an open file. 0 or errno. */
BAREMETAL_IMPORT("path_stat_ext") int32_t __cc_path_stat_ext(const char *path, int32_t len, int32_t *out);
BAREMETAL_IMPORT("fd_stat_ext") int32_t cc_fd_stat_ext(int32_t fd, int32_t *out);
BAREMETAL_IMPORT("path_chmod") int32_t __cc_path_chmod(const char *path, int32_t len, int32_t mode);
BAREMETAL_IMPORT("path_chown") int32_t __cc_path_chown(const char *path, int32_t len, int32_t uid, int32_t gid);
BAREMETAL_IMPORT("fd_chmod") int32_t cc_fd_chmod(int32_t fd, int32_t mode);
BAREMETAL_IMPORT("fd_chown") int32_t cc_fd_chown(int32_t fd, int32_t uid, int32_t gid);
/* access() for the real user: mode 4 read, 2 write, 1 execute, 0 exists. 0 or errno. */
BAREMETAL_IMPORT("path_access") int32_t __cc_path_access(const char *path, int32_t len, int32_t mode);

/* ---- Pseudo-terminals, waiting, cryptography (ssh) ------------------------------------------------------------- */

#define CC_SPAWN_CTTY 4 /* the terminal on the child's stdin becomes its controlling terminal (a new session) */

/* out = [master fd, slave fd, id]. Output waiting on the master (or the session ending) queues a ("pty", id) event. */
BAREMETAL_IMPORT("pty_open") int32_t cc_pty_open(int32_t *out);
BAREMETAL_IMPORT("pty_resize") int32_t cc_pty_resize(int32_t master_fd, int32_t cols, int32_t rows);
/* Wait for an event or a readable descriptor: 1 event waiting, 2 descriptor *ready_fd is readable, 0 timeout. */
BAREMETAL_IMPORT("event_wait_fds") int32_t cc_event_wait_fds(const int32_t *fds, int32_t nfds, int32_t timeout_ms, int32_t *ready_fd);

/* Raw 32-byte keys (RFC 7748 / RFC 8032). Return 0, or errno (crypto_open: EACCES = forged or wrong key). */
BAREMETAL_IMPORT("crypto_x25519_keypair") int32_t cc_x25519_keypair(uint8_t priv[32], uint8_t pub[32]);
BAREMETAL_IMPORT("crypto_x25519") int32_t cc_x25519(const uint8_t priv[32], const uint8_t peer[32], uint8_t shared[32]);
BAREMETAL_IMPORT("crypto_ed25519_keypair") int32_t cc_ed25519_keypair(uint8_t seed[32], uint8_t pub[32]);
BAREMETAL_IMPORT("crypto_ed25519_sign") int32_t cc_ed25519_sign(const uint8_t seed[32], const void *msg, int32_t len, uint8_t sig[64]);
BAREMETAL_IMPORT("crypto_ed25519_verify") int32_t cc_ed25519_verify(const uint8_t pub[32], const void *msg, int32_t len, const uint8_t sig[64]);
/* ChaCha20-Poly1305: seal writes len + 16 bytes (ciphertext and tag), open len - 16. */
BAREMETAL_IMPORT("crypto_seal") int32_t cc_aead_seal(const uint8_t key[32], const uint8_t nonce[12], const void *aad, int32_t aad_len,
                                                 const void *plain, int32_t len, void *out);
BAREMETAL_IMPORT("crypto_open") int32_t cc_aead_open(const uint8_t key[32], const uint8_t nonce[12], const void *aad, int32_t aad_len,
                                                 const void *sealed, int32_t len, void *out);
BAREMETAL_IMPORT("crypto_sha256") int32_t cc_sha256(const void *data, int32_t len, uint8_t out[32]);
BAREMETAL_IMPORT("crypto_hmac_sha256") int32_t cc_hmac_sha256(const void *key, int32_t key_len, const void *data, int32_t len, uint8_t out[32]);

/* ---- Screen lock (man users), shutdown messages ---------------------------------------------------------------
 * Only from the computer's own screen (the console; ENOTTY elsewhere, e.g. over ssh). screen_lock hides the screen
 * behind a password prompt for the caller's user (root may name another uid; -1 = the caller); screen_autolock
 * locks after `seconds` without input (0 = never; seconds < 0 returns the current setting). screen_unlock is for
 * the prompt itself (lock --screen). tty_broadcast writes to every terminal (root or the console's user). */
BAREMETAL_IMPORT("screen_lock") int32_t cc_screen_lock(int32_t uid);
BAREMETAL_IMPORT("screen_autolock") int32_t cc_screen_autolock(int32_t seconds, int32_t uid);
BAREMETAL_IMPORT("screen_unlock") int32_t cc_screen_unlock(void);
BAREMETAL_IMPORT("tty_broadcast") int32_t cc_tty_broadcast(const char *text, int32_t len);

/* ---- Network: stream sockets between computers over modems (man net) ------------------------------------------
 * A computer is addressed by its hostname, a service by its port (1..65535; below 1024 only root may listen).
 * Sockets are file descriptors: read(), write(), close(), poll() and cc_event_wait_fds work on them. A connection
 * closed by the other side reads as end of file. All return 0 or an errno: ECONNREFUSED (nothing listens on that
 * port), ETIMEDOUT (nobody answered: wrong name, out of range), ENETDOWN (no modem), EADDRINUSE (port taken; on a
 * connection: two computers answer for that name). */
BAREMETAL_IMPORT("net_listen") int32_t cc_net_listen(int32_t port, int32_t *fd);
/* flags 1 = do not wait (EAGAIN when nobody is waiting). The listening descriptor is readable while one is. */
BAREMETAL_IMPORT("net_accept") int32_t cc_net_accept(int32_t listen_fd, int32_t flags, int32_t *fd);
/* timeout_ms <= 0: 10 seconds. "localhost" is this computer. */
BAREMETAL_IMPORT("net_connect") int32_t cc_net_connect(const char *host, int32_t len, int32_t port, int32_t timeout_ms, int32_t *fd);
/* No more writes (shutdown(SHUT_WR)): the peer reads end of file, this side can still read. */
BAREMETAL_IMPORT("net_shutdown") int32_t cc_net_shutdown(int32_t fd);
/* JSON {"state","local","lport","remote","rport",...} of a socket (fetch with cc_take), or -errno. */
BAREMETAL_IMPORT("net_info") int32_t __cc_net_info(int32_t fd);
/* Every socket of the computer: read /proc/net/ccnet. Interfaces: /sys/class/net (man net). */

/* ---- Terminal ------------------------------------------------------------------------------------------------- */

#define CC_TTY_RAW 1    /* no line editing: bytes arrive as typed, keys as VT100 escape sequences */
#define CC_TTY_NOECHO 2
#define CC_TTY_NOSIG 16 /* Ctrl+C is delivered as a byte */
#define CC_TTY_ICRNL 32 /* raw mode: Enter arrives as \n instead of \r (termios ICRNL) */
#define CC_TTY_TIMED 64 /* raw mode: read() follows VMIN (bits 8-15) and VTIME (bits 16-23, tenths of a second) */
BAREMETAL_IMPORT("tty_mode") int32_t cc_tty_mode(int32_t flags); /* -1 = query; returns old mode */
BAREMETAL_IMPORT("tty_size") int32_t cc_tty_size(int32_t *cols, int32_t *rows);
BAREMETAL_IMPORT("tty_setfg") int32_t cc_tty_setfg(int32_t pgid);
BAREMETAL_IMPORT("tty_getfg") int32_t cc_tty_getfg(void);
BAREMETAL_IMPORT("term_palette") int32_t cc_term_palette(int32_t index, int32_t rgb);

/* ---- Events (CC style: ["name", args...] as JSON) -------------------------------------------------------------- */

/* Wait for an event; returns JSON length (then cc_take), 0 on timeout. timeout_ms < 0 waits forever. */
BAREMETAL_IMPORT("event_wait") int32_t __cc_event_wait(int32_t timeout_ms, const char *filter, int32_t filter_len);
BAREMETAL_IMPORT("event_queue") int32_t __cc_event_queue(const char *json, int32_t len);
BAREMETAL_IMPORT("timer_start") int32_t cc_timer_start(int32_t ms);
BAREMETAL_IMPORT("timer_cancel") int32_t cc_timer_cancel(int32_t id);
BAREMETAL_IMPORT("take") int32_t cc_take(char *buf, int32_t len);

/* ---- Graphics ------------------------------------------------------------------------------------------------- */
/*
 * The screen is up to 1280x800 pixels (default: a common resolution filling the text area, see cc_sys_info screen_width), indexed colour: 1, 2, 4 or 8 bits per pixel (2, 4, 16 or 256 colours).
 * gfx_mode(0, 0, 0, 0) returns to the text terminal; the screen also returns to text when the program exits.
 * gfx_target() redirects drawing to an attached pixel monitor peripheral (NULL/0 = back to the screen).
 */
#define CC_GFX_GRAY 1
BAREMETAL_IMPORT("gfx_target") int32_t __cc_gfx_target(const char *name, int32_t len);
BAREMETAL_IMPORT("gfx_mode") int32_t cc_gfx_mode(int32_t w, int32_t h, int32_t bpp, int32_t flags);
BAREMETAL_IMPORT("gfx_info") int32_t cc_gfx_info(int32_t info[4]); /* w, h, bpp, graphics_active */
BAREMETAL_IMPORT("gfx_palette") int32_t cc_gfx_palette(int32_t start, int32_t count, const uint32_t *rgb);
BAREMETAL_IMPORT("gfx_get_palette") int32_t cc_gfx_get_palette(int32_t start, int32_t count, uint32_t *rgb);
BAREMETAL_IMPORT("gfx_blit") int32_t cc_gfx_blit(int32_t x, int32_t y, int32_t w, int32_t h, const uint8_t *px, int32_t stride);
BAREMETAL_IMPORT("gfx_blit_key") int32_t cc_gfx_blit_key(int32_t x, int32_t y, int32_t w, int32_t h, const uint8_t *px,
                                                      int32_t stride, int32_t key);
BAREMETAL_IMPORT("gfx_read") int32_t cc_gfx_read(int32_t x, int32_t y, int32_t w, int32_t h, uint8_t *px, int32_t stride);
BAREMETAL_IMPORT("gfx_fill") int32_t cc_gfx_fill(int32_t x, int32_t y, int32_t w, int32_t h, int32_t c);
BAREMETAL_IMPORT("gfx_clear") int32_t cc_gfx_clear(int32_t c);
BAREMETAL_IMPORT("gfx_pixel") int32_t cc_gfx_pixel(int32_t x, int32_t y, int32_t c);
BAREMETAL_IMPORT("gfx_get_pixel") int32_t cc_gfx_get_pixel(int32_t x, int32_t y);
BAREMETAL_IMPORT("gfx_line") int32_t cc_gfx_line(int32_t x0, int32_t y0, int32_t x1, int32_t y1, int32_t c);
BAREMETAL_IMPORT("gfx_rect") int32_t cc_gfx_rect(int32_t x, int32_t y, int32_t w, int32_t h, int32_t c);
BAREMETAL_IMPORT("gfx_circle") int32_t cc_gfx_circle(int32_t cx, int32_t cy, int32_t r, int32_t c, int32_t filled);
/* Draws UTF-8 text with the terminal font (6x9 cells); bg < 0 is transparent. Returns the x after the text. */
BAREMETAL_IMPORT("gfx_text") int32_t __cc_gfx_text(int32_t x, int32_t y, const char *s, int32_t len, int32_t fg, int32_t bg, int32_t scale);
BAREMETAL_IMPORT("gfx_scroll") int32_t cc_gfx_scroll(int32_t dy, int32_t c);
/* Frame pacing: waits until the previous frame has been sent to players (at most 100 ms). */
BAREMETAL_IMPORT("gfx_present") int32_t cc_gfx_present(void);

/* ---- Peripherals (any CC peripheral from any mod) ------------------------------------------------------------- */

BAREMETAL_IMPORT("periph_list") int32_t __cc_periph_list(void);
BAREMETAL_IMPORT("periph_types") int32_t __cc_periph_types(const char *name, int32_t len);
BAREMETAL_IMPORT("periph_methods") int32_t __cc_periph_methods(const char *name, int32_t len);
/* args: JSON array. Returns result JSON length (>0) or -(error message length); fetch with cc_take. */
BAREMETAL_IMPORT("periph_call") int32_t __cc_periph_call(const char *name, int32_t nlen, const char *method, int32_t mlen,
                                                      const char *args, int32_t alen);

/* ---- Lua ------------------------------------------------------------------------------------------------------ */

/* Run Lua code in this process's persistent Lua state; "..." receives the JSON array args. Same return convention
 * as __cc_periph_call. The state has term, fs, os, peripheral (wrap/find), redstone, gfx, colors. */
/* Objects with methods returned by peripherals/APIs arrive in JSON as {"__object": id, "methods": [...]}. */
BAREMETAL_IMPORT("obj_call") int32_t __cc_obj_call(int32_t id, const char *method, int32_t mlen, const char *args, int32_t alen);
/* Like __cc_obj_call for a method returning bytes: the first result raw (no JSON). Returns length + 1 (cc_take the
 * length), 0 if it is not a string (nil), or -(error message length). */
BAREMETAL_IMPORT("obj_call_raw") int32_t __cc_obj_call_raw(int32_t id, const char *method, int32_t mlen, const char *args, int32_t alen);
BAREMETAL_IMPORT("obj_free") int32_t cc_obj_free(int32_t id);
/* Global APIs registered by addons (ComputerCraftAPI.registerAPIFactory): names, and a handle for one. */
BAREMETAL_IMPORT("api_list") int32_t __cc_api_list(void);
BAREMETAL_IMPORT("api_get") int32_t __cc_api_get(const char *name, int32_t nlen);
BAREMETAL_IMPORT("lua_eval") int32_t __cc_lua_eval(const char *code, int32_t clen, const char *args, int32_t alen);

/* ---- System --------------------------------------------------------------------------------------------------- */

BAREMETAL_IMPORT("sys_info") int32_t __cc_sys_info(void);
BAREMETAL_IMPORT("sys_set_label") int32_t __cc_sys_set_label(const char *label, int32_t len);
BAREMETAL_IMPORT("sys_shutdown") int32_t cc_shutdown(int32_t reboot);
/* Sides: 0 bottom, 1 top, 2 back, 3 front, 4 right, 5 left. Levels 0-15. */
BAREMETAL_IMPORT("rs_get") int32_t cc_rs_get(int32_t side);
BAREMETAL_IMPORT("rs_get_output") int32_t cc_rs_get_output(int32_t side);
BAREMETAL_IMPORT("rs_set") int32_t cc_rs_set(int32_t side, int32_t level);

/* ---- libbaremetal helpers ---------------------------------------------------------------------------------------- */

/* Start argv[0] (searched in PATH) with the given stdio (-1 = inherit). Returns pid or -errno. */
int cc_spawn(char *const argv[], char *const envp[], int fd_in, int fd_out, int fd_err, int flags);
/* Run a command and wait: returns its exit status or -errno. */
int cc_run(char *const argv[]);
/* chdir() that also updates $PWD and the kernel's idea of our cwd (children inherit it). */
int cc_chdir(const char *path);
int cc_side(const char *name); /* "left" -> 5, ... or -1 */

/* Fetch the pending kernel result (after a call returned n) as a malloc'd NUL-terminated string. */
char *cc_take_str(int n);
char *cc_event_wait(int timeout_ms, const char *filter); /* malloc'd JSON array or NULL on timeout */
int cc_event_queue(const char *json);
char *cc_periph_list(void);                       /* JSON: [{"name":..,"types":[..]},...] */
char *cc_periph_methods(const char *name);        /* JSON array of names, NULL if absent */
/* On success returns 0 and sets *result (JSON array, malloc'd); on error returns -1 and sets *result to the message. */
int cc_periph_call(const char *name, const char *method, const char *args_json, char **result);
int cc_lua(const char *code, const char *args_json, char **result);
char *cc_sys_info(void);
int cc_set_label(const char *label);
int cc_gfx_target(const char *name);
int cc_gfx_text(int x, int y, const char *utf8, int fg, int bg, int scale);

#ifdef __cplusplus
}
#endif
#endif
