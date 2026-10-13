// baremetal.hpp - C++ interface to the CC: Bare Metal kernel.
//
// Programs are ordinary WASI (wasm32-wasip1) programs. <baremetal.h> declares the kernel's own syscalls (processes,
// terminal, events, graphics, CC peripherals, Lua, redstone); this header wraps them in a C++ API:
//
//     #include <baremetal.hpp>
//     int main() {
//         for (auto &p : cc::Peripheral::list()) std::printf("%s\n", p.name.c_str());
//         cc::Peripheral monitor("right");
//         auto r = monitor.call("setPixel", cc::Json::array({3, 4, 7}));
//         if (!r) std::fprintf(stderr, "%s\n", r.error.c_str());
//     }
//
// Build in game:  c++ prog.cpp -o prog        (links libbaremetal automatically)
// Build outside:  clang++ --target=wasm32-wasip1 prog.cpp -lbaremetal ...  (wasi-sdk)
#ifndef BAREMETAL_HPP
#define BAREMETAL_HPP

#include "baremetal.h"

#include <cstdint>
#include <initializer_list>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace cc {

// ---- JSON (the kernel's data format for events, peripheral calls and Lua) -------------------------------------

class Json {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };
    using Array = std::vector<Json>;
    using Object = std::vector<std::pair<std::string, Json>>; // keeps member order

    Json() = default;
    Json(std::nullptr_t) {}
    Json(bool b) : type_(Type::Bool), number_(b ? 1 : 0) {}
    Json(int n) : type_(Type::Number), number_(n) {}
    Json(long n) : type_(Type::Number), number_(static_cast<double>(n)) {}
    Json(long long n) : type_(Type::Number), number_(static_cast<double>(n)) {}
    Json(unsigned n) : type_(Type::Number), number_(n) {}
    Json(double n) : type_(Type::Number), number_(n) {}
    Json(const char *s) : type_(Type::String), string_(s) {}
    Json(std::string s) : type_(Type::String), string_(std::move(s)) {}
    Json(std::string_view s) : type_(Type::String), string_(s) {}

    static Json array(std::initializer_list<Json> items = {});
    static Json array(Array items);
    static Json object(std::initializer_list<std::pair<std::string, Json>> members = {});

    // Parse JSON text; nullopt on a syntax error.
    static std::optional<Json> parse(std::string_view text);
    std::string dump() const;

    Type type() const { return type_; }
    bool isNull() const { return type_ == Type::Null; }
    bool isBool() const { return type_ == Type::Bool; }
    bool isNumber() const { return type_ == Type::Number; }
    bool isString() const { return type_ == Type::String; }
    bool isArray() const { return type_ == Type::Array; }
    bool isObject() const { return type_ == Type::Object; }

    bool asBool(bool def = false) const { return type_ == Type::Bool || type_ == Type::Number ? number_ != 0 : def; }
    double asNumber(double def = 0) const { return type_ == Type::Number || type_ == Type::Bool ? number_ : def; }
    int asInt(int def = 0) const { return type_ == Type::Number ? static_cast<int>(number_) : def; }
    const std::string &asString() const;
    std::string asString(std::string_view def) const { return isString() ? string_ : std::string(def); }

    // Arrays and objects. Missing elements read as null.
    size_t size() const;
    const Json &operator[](size_t index) const;
    const Json &operator[](std::string_view key) const;
    const Json *find(std::string_view key) const;
    const Array &items() const;
    const Object &members() const;
    Json &push(Json value);
    Json &set(std::string key, Json value);

    // Text for humans: strings without quotes, numbers without ".0", the rest as JSON.
    std::string toString() const;

private:
    Type type_ = Type::Null;
    double number_ = 0;
    std::string string_;
    std::shared_ptr<Array> array_;
    std::shared_ptr<Object> object_;

    void dumpTo(std::string &out) const;
};

std::string quote(std::string_view s); // JSON string literal

// Result of a kernel call that can fail with a message (peripheral calls, Lua).
struct Result {
    bool ok = false;
    Json value;        // on success: array of returned values
    std::string error; // on failure
    explicit operator bool() const { return ok; }
};

// ---- Processes --------------------------------------------------------------------------------------------------

struct SpawnOptions {
    std::optional<std::string> program;          // file to run; default: argv[0] searched in PATH
    std::optional<std::vector<std::string>> env; // "NAME=value" entries; default: our environment
    int in = -1, out = -1, err = -1;             // -1 = inherit 0/1/2
    int flags = 0;                               // CC_SPAWN_NEW_GROUP, CC_SPAWN_FOREGROUND
};

// Start argv[0] (searched in PATH) in our working directory. Returns the pid or -errno.
int spawn(const std::vector<std::string> &argv, const SpawnOptions &options = {});
// Wait for a child (-1 = any). Returns the pid, 0 if nohang and none exited, or -errno.
int wait(int pid, int *status = nullptr, bool nohang = false);
// Run a command and wait: its exit status, or -errno if it could not start.
int run(const std::vector<std::string> &argv, const SpawnOptions &options = {});
// chdir() that also updates $PWD and the kernel's working directory (inherited by children).
bool chdir(const std::string &path);
std::string cwd();
inline int pid() { return cc_getpid(); }
void sleep(double seconds);

// ---- Terminal ---------------------------------------------------------------------------------------------------

namespace tty {
struct Size {
    int cols = 80, rows = 24;
};
Size size();
int mode(); // CC_TTY_* flags
int setMode(int flags);

// Raw (or other) terminal mode for the lifetime of the object.
class Mode {
public:
    explicit Mode(int flags) : old_(setMode(flags)) {}
    ~Mode() { setMode(old_); }
    Mode(const Mode &) = delete;
    Mode &operator=(const Mode &) = delete;

private:
    int old_;
};
} // namespace tty

// ---- Events (CC style: name plus arguments) ---------------------------------------------------------------------

struct Event {
    std::string name;
    Json args; // array of the arguments after the name

    const Json &operator[](size_t i) const { return args[i]; }
};

// Wait for the next event (optionally only one named `filter`). nullopt on timeout. timeoutMs < 0 waits forever.
std::optional<Event> pullEvent(std::string_view filter = {}, int timeoutMs = -1);
bool queueEvent(std::string_view name, const Json &args = Json::array());
// Wait for the next event or for one of `fds` to become readable, without polling: {event} or {fd}; both empty on
// timeout (timeoutMs < 0 waits forever). For programs that talk to a terminal and the network at once (ssh).
struct Wakeup {
    std::optional<Event> event;
    int fd = -1;
};
Wakeup waitAny(const std::vector<int> &fds, int timeoutMs = -1);
int startTimer(double seconds);
void cancelTimer(int id);

// Ask for machine events (redstone, peripheral, timer...) and/or keyboard/mouse as events. See CC_PROC_*.
int setProcessFlags(int flags);

// ---- Graphics ---------------------------------------------------------------------------------------------------

namespace gfx {
struct Info {
    int width = 0, height = 0, bpp = 0;
    bool active = false;
};
// Switch the screen to graphics (bpp 1, 2, 4 or 8 with a palette; 16 = RGB565 or 32 = XRGB8888, colours 0xRRGGBB;
// flags CC_GFX_GRAY). Returns false if unsupported. blit/read data: bytes per pixel 1, 2 or 4; stride in pixels.
bool mode(int width, int height, int bpp, int flags = 0);
void textMode();
Info info();
// Draw on a pixel monitor peripheral instead of the screen ("" = back to the screen).
bool target(std::string_view peripheral);
void palette(int start, const std::vector<uint32_t> &rgb);
std::vector<uint32_t> palette(int start, int count);
inline void clear(int c) { cc_gfx_clear(c); }
inline void pixel(int x, int y, int c) { cc_gfx_pixel(x, y, c); }
inline int getPixel(int x, int y) { return cc_gfx_get_pixel(x, y); }
inline void fill(int x, int y, int w, int h, int c) { cc_gfx_fill(x, y, w, h, c); }
inline void rect(int x, int y, int w, int h, int c) { cc_gfx_rect(x, y, w, h, c); }
inline void line(int x0, int y0, int x1, int y1, int c) { cc_gfx_line(x0, y0, x1, y1, c); }
inline void circle(int cx, int cy, int r, int c, bool filled = false) { cc_gfx_circle(cx, cy, r, c, filled ? 1 : 0); }
inline void scroll(int dy, int c) { cc_gfx_scroll(dy, c); }
inline void blit(int x, int y, int w, int h, const uint8_t *px, int stride) { cc_gfx_blit(x, y, w, h, px, stride); }
inline void present() { cc_gfx_present(); }
// UTF-8 text in the terminal font (6x9 per character, times scale); bg < 0 is transparent. Returns the end x.
int text(int x, int y, std::string_view utf8, int fg, int bg = -1, int scale = 1);
} // namespace gfx

// ---- Peripherals (any CC peripheral from any mod) ---------------------------------------------------------------

class Peripheral {
public:
    struct Info {
        std::string name;
        std::vector<std::string> types;
    };

    explicit Peripheral(std::string name) : name_(std::move(name)) {}

    static std::vector<Info> list();
    // First peripheral with this type ("monitor", "modem", "pixel_monitor"...).
    static std::optional<Peripheral> find(std::string_view type);

    const std::string &name() const { return name_; }
    bool present() const;
    std::vector<std::string> types() const;
    std::vector<std::string> methods() const;
    Result call(std::string_view method, const Json &args = Json::array()) const;
    // call with C++ arguments: p.invoke("pushItems", "minecraft:chest_1", 1, 64)
    template <typename... A>
    Result invoke(std::string_view method, A &&...args) const {
        return call(method, Json::array({Json(std::forward<A>(args))...}));
    }

private:
    std::string name_;
};

// An object with methods returned by a peripheral or API of any mod (handles, item/fluid/entity objects,
// functions). In results it appears as {"__object": id, "methods": [...]}; it can also be passed back as an
// argument. Functions have one method, "call".
class Object {
public:
    explicit Object(int id = 0, std::vector<std::string> methods = {}) : id_(id), methods_(std::move(methods)) {}
    // From a result value; nullopt if it is plain data.
    static std::optional<Object> from(const Json &value);

    int id() const { return id_; }
    const std::vector<std::string> &methods() const { return methods_; }
    bool has(std::string_view method) const;
    Result call(std::string_view method, const Json &args = Json::array()) const;
    template <typename... A>
    Result invoke(std::string_view method, A &&...args) const {
        return call(method, Json::array({Json(std::forward<A>(args))...}));
    }
    // For methods that return bytes (an HTTP body, a binary file handle): the first result exactly as bytes.
    // nullopt if it is nil (end of data) or on error (then *error is set, if given).
    std::optional<std::string> callBytes(std::string_view method, const Json &args = Json::array(), std::string *error = nullptr) const;
    Json json() const; // to pass it as an argument
    void release() const { cc_obj_free(id_); }

private:
    int id_;
    std::vector<std::string> methods_;
};

// Global APIs that addons add to every computer (what Lua programs see as globals besides CC's own).
namespace api {
std::vector<std::string> list();
std::optional<Object> get(std::string_view name);
} // namespace api

// ---- HTTP (CC: Tweaked's own HTTP API: same server settings, address rules and limits as CC's computers) ---------

namespace http {
struct Response {
    bool ok = false;   // a response arrived with a success status
    int status = 0;    // HTTP status (also for error statuses), 0 if no response
    std::string body;  // the body, exact bytes
    Json headers;      // response headers (object)
    std::string error; // why it failed: CC's message ("Domain not permitted", "Not Found"...)
};

struct Request {
    std::string url;
    std::string method;               // empty: GET, or POST with a body
    std::optional<std::string> body;  // request body (text)
    std::vector<std::pair<std::string, std::string>> headers;
    bool redirect = true;
    double timeout = 0;               // seconds; 0: CC's default
};

// Whether HTTP is enabled on this server (CC's config, http.enabled).
bool enabled();
// Blocking requests. They wait for CC's http_success / http_failure events (other events meanwhile are skipped).
Response request(const Request &request);
Response get(const std::string &url);
Response post(const std::string &url, const std::string &body, const std::string &contentType = "application/x-www-form-urlencoded");
// Whether a URL may be fetched (CC's address rules); *error says why not.
bool checkURL(const std::string &url, std::string *error = nullptr);

class WebSocket {
public:
    explicit WebSocket(Object handle) : handle_(std::move(handle)) {}
    bool send(std::string_view message, bool binary = false);
    // Next message (bytes), nullopt on timeout (seconds; < 0 waits forever) or when closed.
    std::optional<std::string> receive(double timeout = -1);
    void close();

private:
    Object handle_;
};
std::optional<WebSocket> websocket(const std::string &url, std::string *error = nullptr,
                                   const std::vector<std::pair<std::string, std::string>> &headers = {});
} // namespace http

// ---- CC: Tweaked peripherals ------------------------------------------------------------------------------------
// Typed wrappers for the standard CC: Tweaked peripherals, the same methods as their Lua API. They work with any
// peripheral of that type: on a side, through wired modems, or from addons that implement it (Peripheral::call
// reaches everything else). find() returns the first one attached.

// CC colours: bit masks, as in Lua's colors API (colors.white = 1 ... colors.black = 32768).
namespace colors {
enum : int {
    white = 1, orange = 2, magenta = 4, lightBlue = 8, yellow = 16, lime = 32, pink = 64, gray = 128,
    lightGray = 256, cyan = 512, purple = 1024, blue = 2048, brown = 4096, green = 8192, red = 16384, black = 32768
};
} // namespace colors

class Modem : public Peripheral {
public:
    using Peripheral::Peripheral;
    static std::optional<Modem> find(); // prefers a wireless (or ender) modem
    static std::vector<Modem> all();

    bool open(int channel) const;
    bool isOpen(int channel) const;
    void close(int channel) const;
    void closeAll() const;
    bool transmit(int channel, int replyChannel, const Json &message) const;
    bool isWireless() const;
    // Wired modems: peripherals on the network, and our name on it.
    std::vector<std::string> namesRemote() const;
    std::string nameLocal() const;
};

// A "modem_message" event.
struct ModemMessage {
    std::string side;
    int channel = 0;
    int replyChannel = 0;
    Json message;
    double distance = 0; // 0 for wired modems
};
// Wait for a modem message on any open channel; nullopt on timeout (seconds; < 0 waits forever).
std::optional<ModemMessage> receiveModem(double timeout = -1);
// Make modem events (and other machine events) reach this process; called by the functions above.
void wantEvents();

// rednet, compatible with CC's Lua rednet: C++ and Lua computers talk to each other.
namespace rednet {
constexpr int BROADCAST = 65535;
constexpr int REPEAT = 65533;
struct Message {
    int sender = -1;
    Json message;
    std::string protocol;
    double distance = 0;
};
bool open(const std::string &modem = {});  // all modems if empty; false if there is none
void close(const std::string &modem = {}); // all modems if empty
bool isOpen(const std::string &modem = {});
bool send(int recipient, const Json &message, std::string_view protocol = {});
bool broadcast(const Json &message, std::string_view protocol = {});
// Next message (for `protocol`, if not empty); nullopt on timeout (seconds; < 0 waits forever). While waiting it
// also answers lookups for names registered with host().
std::optional<Message> receive(std::string_view protocol = {}, double timeout = -1);
void host(const std::string &protocol, const std::string &hostname);
void unhost(const std::string &protocol);
// Computers hosting `protocol` (with `hostname`, if not empty), collected for `timeout` seconds.
std::vector<int> lookup(const std::string &protocol, const std::string &hostname = {}, double timeout = 2);
} // namespace rednet

// GPS: position from gps hosts (needs a wireless modem); nullopt if fewer than 3 hosts answer in time.
namespace gps {
struct Position {
    double x, y, z;
};
std::optional<Position> locate(double timeout = 2);
} // namespace gps

// Monitor (and other "terminal" peripherals): text output with CC colours.
class Monitor : public Peripheral {
public:
    using Peripheral::Peripheral;
    static std::optional<Monitor> find();

    void write(std::string_view text) const;
    void setCursorPos(int x, int y) const; // 1-based
    std::pair<int, int> cursorPos() const;
    std::pair<int, int> size() const;
    void clear() const;
    void clearLine() const;
    void scroll(int lines) const;
    void setTextColour(int colour) const; // colors::*
    void setBackgroundColour(int colour) const;
    void setTextScale(double scale) const; // 0.5 .. 5
    void setCursorBlink(bool blink) const;
    bool isColour() const;
    // blit(text, fg, bg): one hex digit per character, as in Lua.
    void blit(std::string_view text, std::string_view fg, std::string_view bg) const;
    void setPaletteColour(int colour, uint32_t rgb) const;
};

class Speaker : public Peripheral {
public:
    using Peripheral::Peripheral;
    static std::optional<Speaker> find();

    // instrument: harp, bass, basedrum, snare, hat, bell, flute, chime, guitar, xylophone, iron_xylophone,
    // cow_bell, didgeridoo, bit, banjo, pling; pitch 0..24.
    bool playNote(std::string_view instrument, double volume = 1, double pitch = 12) const;
    bool playSound(std::string_view name, double volume = 1, double pitch = 1) const; // "minecraft:entity.cat.ambient"
    // 8-bit signed PCM at 48 kHz, up to 128 * 1024 samples per call; false while the buffer is full
    // (wait for a "speaker_audio_empty" event).
    bool playAudio(const std::vector<int8_t> &samples, double volume = 1) const;
    void stop() const;
};

class Printer : public Peripheral {
public:
    using Peripheral::Peripheral;
    static std::optional<Printer> find();

    bool newPage() const;
    bool endPage() const;
    void write(std::string_view text) const;
    void setCursorPos(int x, int y) const;
    std::pair<int, int> pageSize() const;
    void setPageTitle(std::string_view title) const;
    int inkLevel() const;
    int paperLevel() const;
};

class Drive : public Peripheral {
public:
    using Peripheral::Peripheral;
    static std::optional<Drive> find();

    bool isDiskPresent() const;
    std::string label() const;
    void setLabel(std::string_view label) const;
    bool hasData() const;
    std::string mountPath() const; // where the disk is mounted, e.g. "/disk"
    int diskId() const;
    bool hasAudio() const;
    std::string audioTitle() const;
    void playAudio() const;
    void stopAudio() const;
    void eject() const;
};

// Any block with an item inventory (chests, furnaces, machines from other mods): CC's generic "inventory" type.
class Inventory : public Peripheral {
public:
    struct Item {
        int slot = 0;
        std::string name; // "minecraft:cobblestone"
        int count = 0;
    };
    using Peripheral::Peripheral;
    static std::optional<Inventory> find();
    static std::vector<Inventory> all();

    int size() const;
    std::vector<Item> list() const;
    Json itemDetail(int slot) const; // name, count, displayName, maxCount, tags... (null if empty)
    int itemLimit(int slot) const;
    // Move items to/from another inventory on the same wired network (by peripheral name). Returns the count moved.
    int pushItems(const std::string &to, int fromSlot, int limit = -1, int toSlot = -1) const;
    int pullItems(const std::string &from, int fromSlot, int limit = -1, int toSlot = -1) const;
};

// Generic "energy_storage" (Forge Energy blocks) and "fluid_storage" peripherals.
class EnergyStorage : public Peripheral {
public:
    using Peripheral::Peripheral;
    static std::optional<EnergyStorage> find();
    long long energy() const;
    long long capacity() const;
};

class FluidStorage : public Peripheral {
public:
    struct Tank {
        std::string name; // "minecraft:water"
        long long amount = 0; // mB
    };
    using Peripheral::Peripheral;
    static std::optional<FluidStorage> find();
    std::vector<Tank> tanks() const;
    long long pushFluid(const std::string &to, long long limit = -1, std::string_view fluid = {}) const;
    long long pullFluid(const std::string &from, long long limit = -1, std::string_view fluid = {}) const;
};

// ---- Lua and system ---------------------------------------------------------------------------------------------

// Run Lua in this process's persistent Lua state (term, fs, os, peripheral, redstone, gfx, colors). The code
// receives `args` as "...". Returns its results.
Result lua(std::string_view code, const Json &args = Json::array());

Json systemInfo();
int computerId();
// The computer's name: /etc/hostname (set with `hostname NAME`), else its label, else "computer-ID". ssh addresses
// computers by it; the id alone is not unique (a copied computer keeps it).
std::string hostname();
bool setLabel(std::string_view label); // "" removes it
int side(std::string_view name);       // "left" -> 5 ... or -1
inline int redstoneInput(int side) { return cc_rs_get(side); }
inline void setRedstone(int side, int level) { cc_rs_set(side, level); }

// ---- Network (man net) ------------------------------------------------------------------------------------------
// Stream sockets between computers over modems, like TCP: a computer is reached by its hostname, a service by its
// port. The functions return a file descriptor (read/write/close it, wait for it with waitAny) or -errno.
//
//   int fd = cc::net::connect("server", 1234);         // -ECONNREFUSED, -ETIMEDOUT, -ENETDOWN ...
//   int l = cc::net::listen(1234); int c = cc::net::accept(l);
namespace net {
int listen(int port);
int accept(int listenFd, bool wait = true);
int connect(const std::string &host, int port, int timeoutMs = 10000);
int shutdown(int fd); // no more writes: the peer reads end of file; 0 or -errno

struct Socket {
    std::string state; // LISTEN, SYN-SENT, ESTAB, FIN-WAIT, CLOSE-WAIT, LAST-ACK, CLOSED
    std::string local, remote;
    int localPort = 0, remotePort = 0, uid = 0;
    long recvQueue = 0, sendQueue = 0;
};
std::optional<Socket> info(int fd);
// This computer's sockets (/proc/net/ccnet), its name and the modem channel it listens on.
struct Status {
    std::string hostname;
    int channel = 0;
    std::vector<Socket> sockets;
};
Status status();
// The modem channel a computer called `name` listens on (a hash of the name).
int channel(const std::string &name);
// Write all of `data` (write() may take less). False on an error (errno set).
bool sendAll(int fd, const void *data, size_t n);
} // namespace net

// Fetch the pending kernel result (after a call returned n) as a string.
std::string take(int n);

} // namespace cc

#endif
