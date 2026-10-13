// ccutils - commands for CC: Tweaked peripherals and networking, as a multi-call binary like coreutils:
//   rednet  gps  speaker  lp  disk  eject  monitor  inv  energy  fbset  nc  ss  ip  lsdev
// They use the typed peripheral API of <baremetal.hpp>, and talk to Lua computers too (rednet, gps).
#include <baremetal.hpp>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <functional>
#include <fcntl.h>
#include <linux/input.h>
#include <map>
#include <pwd.h>
#include <string>
#include <unistd.h>
#include <vector>

namespace {

using Args = std::vector<std::string>;
std::string prog;

int fail(const std::string &message) {
    std::fprintf(stderr, "%s: %s\n", prog.c_str(), message.c_str());
    return 1;
}

int usage(const char *text) {
    std::fprintf(stderr, "usage: %s\n", text);
    return 1;
}

// A command-line value: number, true/false/nil, JSON, or else a string (like periph).
cc::Json value(const std::string &a) {
    char *end = nullptr;
    double d = std::strtod(a.c_str(), &end);
    if (!a.empty() && *end == '\0') return d;
    if (a == "true" || a == "false") return a == "true";
    if (a == "nil" || a == "null") return nullptr;
    if (!a.empty() && (a[0] == '{' || a[0] == '[' || a[0] == '"')) {
        if (auto parsed = cc::Json::parse(a)) return *parsed;
    }
    return a;
}

double number(const Args &a, size_t i, double def) {
    return i < a.size() ? std::atof(a[i].c_str()) : def;
}

// Option "-n NAME" (peripheral name) taken out of the arguments.
std::string takeOption(Args &a, const std::string &option) {
    for (size_t i = 1; i + 1 < a.size(); i++) {
        if (a[i] == option) {
            auto v = a[i + 1];
            a.erase(a.begin() + static_cast<long>(i), a.begin() + static_cast<long>(i) + 2);
            return v;
        }
    }
    return {};
}

template <typename T>
std::optional<T> pick(const std::string &name) {
    if (!name.empty()) {
        cc::Peripheral p(name);
        if (!p.present()) return std::nullopt;
        return T(name);
    }
    return T::find();
}

std::string readFile(const std::string &path, bool &ok) {
    FILE *f = path == "-" ? stdin : std::fopen(path.c_str(), "rb");
    ok = f != nullptr;
    std::string out;
    if (!f) return out;
    char buf[16384];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    if (f != stdin) std::fclose(f);
    return out;
}

// ---- rednet -----------------------------------------------------------------------------------------------------

void printMessage(const cc::rednet::Message &m) {
    std::printf("\x1b[93m#%d\x1b[0m", m.sender);
    if (!m.protocol.empty()) std::printf(" [%s]", m.protocol.c_str());
    std::printf(": %s\n", m.message.isString() ? m.message.asString().c_str() : m.message.dump().c_str());
    std::fflush(stdout);
}

int cmdRednet(Args &a) {
    const char *text = "rednet open [MODEM] | close [MODEM] | status\n"
                       "       rednet send ID MESSAGE [PROTOCOL] | broadcast MESSAGE [PROTOCOL]\n"
                       "       rednet receive [PROTOCOL] [TIMEOUT] | listen [PROTOCOL]\n"
                       "       rednet lookup PROTOCOL [HOST] | host PROTOCOL HOSTNAME\n"
                       "MESSAGE may be text, a number or JSON ({...} [...]); Lua computers get it as a table.";
    if (a.size() < 2) return usage(text);
    auto &c = a[1];
    if (c == "open") {
        if (!cc::rednet::open(a.size() > 2 ? a[2] : "")) return fail("no modem found");
        std::printf("rednet open, computer #%d\n", cc::computerId());
        return 0;
    }
    if (c == "close") {
        cc::rednet::close(a.size() > 2 ? a[2] : "");
        return 0;
    }
    if (c == "status") {
        std::printf("computer #%d, rednet %s\n", cc::computerId(), cc::rednet::isOpen() ? "open" : "closed");
        for (auto &m : cc::Modem::all()) std::printf("  %-12s %s\n", m.name().c_str(), m.isWireless() ? "wireless" : "wired");
        return 0;
    }
    // The other commands need an open modem; open all of them like CC's rednet programs expect.
    if (!cc::rednet::isOpen() && !cc::rednet::open()) return fail("no modem found");
    if (c == "send" && a.size() >= 4) {
        if (!cc::rednet::send(std::atoi(a[2].c_str()), value(a[3]), a.size() > 4 ? a[4] : "")) return fail("not sent");
        return 0;
    }
    if (c == "broadcast" && a.size() >= 3) {
        if (!cc::rednet::broadcast(value(a[2]), a.size() > 3 ? a[3] : "")) return fail("not sent");
        return 0;
    }
    if (c == "receive") {
        auto m = cc::rednet::receive(a.size() > 2 ? a[2] : "", number(a, 3, -1));
        if (!m) return fail("timed out");
        printMessage(*m);
        return 0;
    }
    if (c == "listen") {
        std::printf("listening as #%d (Ctrl+C to stop)\n", cc::computerId());
        std::fflush(stdout);
        while (auto m = cc::rednet::receive(a.size() > 2 ? a[2] : "")) printMessage(*m);
        return 0;
    }
    if (c == "lookup" && a.size() >= 3) {
        auto ids = cc::rednet::lookup(a[2], a.size() > 3 ? a[3] : "");
        if (ids.empty()) return fail("nobody hosts " + a[2]);
        for (int id : ids) std::printf("%d\n", id);
        return 0;
    }
    if (c == "host" && a.size() >= 4) {
        cc::rednet::host(a[2], a[3]);
        std::printf("hosting %s as '%s' (#%d), Ctrl+C to stop\n", a[2].c_str(), a[3].c_str(), cc::computerId());
        std::fflush(stdout);
        while (auto m = cc::rednet::receive()) {
            if (m->protocol != "dns") printMessage(*m);
        }
        return 0;
    }
    return usage(text);
}

// ---- gps --------------------------------------------------------------------------------------------------------

int cmdGps(Args &a) {
    const char *text = "gps locate [TIMEOUT] | gps host X Y Z";
    if (a.size() >= 2 && a[1] == "host" && a.size() >= 5) {
        auto modem = cc::Modem::find();
        if (!modem || !modem->isWireless()) return fail("needs a wireless modem");
        double x = std::atof(a[2].c_str()), y = std::atof(a[3].c_str()), z = std::atof(a[4].c_str());
        modem->open(65534);
        std::printf("gps host at %g %g %g (Ctrl+C to stop)\n", x, y, z);
        std::fflush(stdout);
        int served = 0;
        while (auto m = cc::receiveModem()) {
            if (m->side != modem->name() || m->channel != 65534 || !m->message.isString() || m->message.asString() != "PING") continue;
            if (m->distance <= 0) continue;
            modem->transmit(m->replyChannel, 65534, cc::Json::array({x, y, z}));
            std::printf("\r%d position requests served", ++served);
            std::fflush(stdout);
        }
        return 0;
    }
    if (a.size() < 2 || a[1] == "locate") {
        auto pos = cc::gps::locate(number(a, 2, 2));
        if (!pos) return fail("could not determine position (needs a wireless modem and 4 gps hosts in range)");
        std::printf("%g %g %g\n", pos->x, pos->y, pos->z);
        return 0;
    }
    return usage(text);
}

// ---- speaker ----------------------------------------------------------------------------------------------------

// DFPWM decoder, as CC's cc.audio.dfpwm (8 samples per byte, 48 kHz).
class Dfpwm {
public:
    void decode(const std::string &in, std::vector<int8_t> &out) {
        for (unsigned char byte : in) {
            for (int b = 0; b < 8; b++) {
                bool bit = (byte & 1) != 0;
                byte >>= 1;
                int c = predict(bit);
                int antijerk = bit != previousBit_ ? floorDiv(c + previousCharge_ + 1, 2) : c;
                previousCharge_ = c;
                previousBit_ = bit;
                lowPass_ += floorDiv((antijerk - lowPass_) * 140 + 0x80, 256);
                out.push_back(static_cast<int8_t>(std::max(-128, std::min(127, lowPass_))));
            }
        }
    }

private:
    static constexpr int PREC = 10;
    int charge_ = 0, strength_ = 0, lowPass_ = 0, previousCharge_ = 0;
    bool lastBit_ = false, previousBit_ = false;

    static int floorDiv(int a, int b) {
        return static_cast<int>(std::floor(static_cast<double>(a) / b));
    }

    int predict(bool bit) {
        int target = bit ? 127 : -128;
        int next = charge_ + floorDiv(strength_ * (target - charge_) + (1 << (PREC - 1)), 1 << PREC);
        if (next == charge_ && next != target) next += bit ? 1 : -1;
        int z = bit == lastBit_ ? (1 << PREC) - 1 : 0;
        int strength = strength_;
        if (strength != z) strength += bit == lastBit_ ? 1 : -1;
        if (strength < 2 << (PREC - 8)) strength = 2 << (PREC - 8);
        charge_ = next;
        strength_ = strength;
        lastBit_ = bit;
        return charge_;
    }
};

int playFile(const cc::Speaker &speaker, const std::string &path, double volume) {
    bool ok;
    auto data = readFile(path, ok);
    if (!ok) return fail(path + ": " + std::strerror(errno));
    Dfpwm decoder;
    cc::wantEvents();
    const size_t chunk = 16 * 1024; // bytes of DFPWM: 128 Ki samples, the speaker's limit per call
    for (size_t i = 0; i < data.size(); i += chunk) {
        std::vector<int8_t> samples;
        decoder.decode(data.substr(i, chunk), samples);
        while (!speaker.playAudio(samples, volume)) cc::pullEvent("speaker_audio_empty");
    }
    return 0;
}

int cmdSpeaker(Args &a) {
    const char *text = "speaker [-n NAME] note INSTRUMENT [VOLUME 0-3] [PITCH 0-24]\n"
                       "       speaker sound minecraft:entity.cat.ambient [VOLUME] [PITCH 0.5-2]\n"
                       "       speaker play FILE.dfpwm [VOLUME] | speaker stop";
    auto name = takeOption(a, "-n");
    if (a.size() < 2) return usage(text);
    auto speaker = pick<cc::Speaker>(name);
    if (!speaker) return fail("no speaker found");
    if (a[1] == "note" && a.size() >= 3) return speaker->playNote(a[2], number(a, 3, 1), number(a, 4, 12)) ? 0 : fail("cannot play");
    if (a[1] == "sound" && a.size() >= 3) return speaker->playSound(a[2], number(a, 3, 1), number(a, 4, 1)) ? 0 : fail("cannot play");
    if (a[1] == "play" && a.size() >= 3) return playFile(*speaker, a[2], number(a, 3, 1));
    if (a[1] == "stop") {
        speaker->stop();
        return 0;
    }
    return usage(text);
}

// ---- lp (printer) -----------------------------------------------------------------------------------------------

int cmdLp(Args &a) {
    auto name = takeOption(a, "-n");
    auto title = takeOption(a, "-t");
    auto printer = pick<cc::Printer>(name);
    if (!printer) return fail("no printer found");
    std::string text;
    if (a.size() < 2) a.push_back("-");
    for (size_t i = 1; i < a.size(); i++) {
        bool ok;
        text += readFile(a[i], ok);
        if (!ok) return fail(a[i] + ": " + std::strerror(errno));
    }
    int pages = 0, x = 1, y = 1, width = 0, height = 0;
    auto newPage = [&]() {
        if (!printer->newPage()) return false;
        auto [w, h] = printer->pageSize();
        width = w;
        height = h;
        x = y = 1;
        pages++;
        if (!title.empty()) printer->setPageTitle(pages == 1 ? title : title + " (" + std::to_string(pages) + ")");
        return true;
    };
    if (!newPage()) return fail("cannot start a page (paper and ink?)");
    std::string line;
    auto flush = [&]() {
        if (line.empty()) return;
        printer->setCursorPos(x, y);
        printer->write(line);
        x += static_cast<int>(line.size());
        line.clear();
    };
    for (char ch : text) {
        if (ch == '\n' || x + static_cast<int>(line.size()) > width) {
            flush();
            x = 1;
            if (++y > height) {
                printer->endPage();
                if (!newPage()) return fail("out of paper or ink after " + std::to_string(pages) + " pages");
            }
            if (ch == '\n') continue;
        }
        if (ch == '\t') {
            line += std::string(static_cast<size_t>(4 - (x + line.size() - 1) % 4), ' ');
        } else if (static_cast<unsigned char>(ch) >= 32) {
            line += ch;
        }
    }
    flush();
    if (!printer->endPage()) return fail("cannot finish the page (output tray full?)");
    std::printf("%d page%s printed\n", pages, pages == 1 ? "" : "s");
    return 0;
}

// ---- disk -------------------------------------------------------------------------------------------------------

int cmdDisk(Args &a) {
    const char *text = "disk [-n DRIVE] [info] | eject | label [TEXT] | play | stop";
    auto name = takeOption(a, "-n");
    auto drive = pick<cc::Drive>(name);
    if (!drive) return fail("no disk drive found");
    auto c = prog == "eject" ? "eject" : a.size() > 1 ? a[1] : "info";
    if (c == "info") {
        if (!drive->isDiskPresent()) {
            std::printf("%s: empty\n", drive->name().c_str());
            return 0;
        }
        std::printf("%s:", drive->name().c_str());
        if (drive->hasData()) {
            std::printf(" disk #%d", drive->diskId());
            auto label = drive->label();
            if (!label.empty()) std::printf(" '%s'", label.c_str());
            std::printf(", mounted at %s", drive->mountPath().c_str());
        }
        if (drive->hasAudio()) std::printf(" music: %s", drive->audioTitle().c_str());
        std::putchar('\n');
        return 0;
    }
    if (c == "eject") {
        drive->eject();
        return 0;
    }
    if (c == "label") {
        if (a.size() > 2) drive->setLabel(a[2]);
        else std::puts(drive->label().c_str());
        return 0;
    }
    if (c == "play") {
        drive->playAudio();
        return 0;
    }
    if (c == "stop") {
        drive->stopAudio();
        return 0;
    }
    return usage(text);
}

// ---- monitor ----------------------------------------------------------------------------------------------------

// Text on a CC monitor: "monitor TEXT...", or lines from stdin ("ls | monitor"), scrolling at the bottom.
int cmdMonitor(Args &a) {
    auto name = takeOption(a, "-n");
    auto scale = takeOption(a, "-s");
    auto colour = takeOption(a, "-c");
    bool clear = false;
    for (size_t i = 1; i < a.size();) {
        if (a[i] == "--clear" || a[i] == "-C") {
            clear = true;
            a.erase(a.begin() + static_cast<long>(i));
        } else if (a[i] == "-h" || a[i] == "--help") {
            return usage("monitor [-n NAME] [-s SCALE] [-c COLOUR 0-15] [-C clear] [TEXT...]   (no TEXT: lines from stdin)");
        } else {
            i++;
        }
    }
    auto monitor = pick<cc::Monitor>(name);
    if (!monitor) return fail("no monitor found");
    if (!scale.empty()) monitor->setTextScale(std::atof(scale.c_str()));
    if (!colour.empty()) monitor->setTextColour(1 << (std::atoi(colour.c_str()) & 15));
    if (clear) {
        monitor->clear();
        monitor->setCursorPos(1, 1);
    }
    auto [width, height] = monitor->size();
    auto writeLine = [&](const std::string &text) {
        auto [x, y] = monitor->cursorPos();
        (void) x;
        for (size_t i = 0; i == 0 || i < text.size(); i += static_cast<size_t>(width)) {
            if (y > height) {
                monitor->scroll(y - height);
                y = height;
            }
            monitor->setCursorPos(1, y);
            monitor->clearLine();
            monitor->write(text.substr(i, static_cast<size_t>(width)));
            y++;
        }
        monitor->setCursorPos(1, y);
    };
    if (a.size() > 1) {
        std::string text;
        for (size_t i = 1; i < a.size(); i++) text += (i > 1 ? " " : "") + a[i];
        writeLine(text);
        return 0;
    }
    char buf[4096];
    while (std::fgets(buf, sizeof(buf), stdin)) {
        std::string line = buf;
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        writeLine(line);
    }
    return 0;
}

// ---- inv / energy -----------------------------------------------------------------------------------------------

int cmdInv(Args &a) {
    const char *text = "inv [NAME]                      list the items (all inventories without NAME)\n"
                       "       inv push FROM TO SLOT [COUNT] [TOSLOT]   move items between inventories\n"
                       "       inv detail NAME SLOT";
    if (a.size() >= 5 && a[1] == "push") {
        int moved = cc::Inventory(a[2]).pushItems(a[3], std::atoi(a[4].c_str()), a.size() > 5 ? std::atoi(a[5].c_str()) : -1,
                                                   a.size() > 6 ? std::atoi(a[6].c_str()) : -1);
        std::printf("%d moved\n", moved);
        return moved > 0 ? 0 : 1;
    }
    if (a.size() >= 4 && a[1] == "detail") {
        std::puts(cc::Inventory(a[2]).itemDetail(std::atoi(a[3].c_str())).dump().c_str());
        return 0;
    }
    if (a.size() >= 2 && (a[1] == "-h" || a[1] == "--help")) return usage(text);
    std::vector<cc::Inventory> inventories;
    if (a.size() >= 2) inventories.emplace_back(a[1]);
    else inventories = cc::Inventory::all();
    if (inventories.empty()) return fail("no inventories (chests, machines...) attached");
    for (auto &inv : inventories) {
        auto items = inv.list();
        std::printf("\x1b[93m%s\x1b[0m: %d slots, %zu used\n", inv.name().c_str(), inv.size(), items.size());
        std::map<std::string, int> totals;
        for (auto &item : items) totals[item.name] += item.count;
        for (auto &[item, count] : totals) std::printf("  %6d  %s\n", count, item.c_str());
    }
    return 0;
}

int cmdEnergy(Args &a) {
    bool any = false;
    for (auto &p : cc::Peripheral::list()) {
        for (auto &t : p.types) {
            if (t == "energy_storage") {
                cc::EnergyStorage e(p.name);
                auto have = e.energy(), cap = e.capacity();
                std::printf("%-20s %12lld / %-12lld FE  %5.1f%%\n", p.name.c_str(), have, cap, cap > 0 ? 100.0 * have / cap : 0.0);
                any = true;
            } else if (t == "fluid_storage") {
                for (auto &tank : cc::FluidStorage(p.name).tanks()) std::printf("%-20s %12lld mB  %s\n", p.name.c_str(), tank.amount, tank.name.c_str());
                any = true;
            }
        }
    }
    if (!any) return fail("no energy or fluid storage attached");
    return 0;
}

// ---- evtest -----------------------------------------------------------------------------------------------------

const char *keyName(int code) {
    static const std::map<int, const char *> names = {
        {1, "KEY_ESC"}, {14, "KEY_BACKSPACE"}, {15, "KEY_TAB"}, {28, "KEY_ENTER"}, {29, "KEY_LEFTCTRL"}, {42, "KEY_LEFTSHIFT"},
        {54, "KEY_RIGHTSHIFT"}, {56, "KEY_LEFTALT"}, {57, "KEY_SPACE"}, {97, "KEY_RIGHTCTRL"}, {100, "KEY_RIGHTALT"},
        {102, "KEY_HOME"}, {103, "KEY_UP"}, {104, "KEY_PAGEUP"}, {105, "KEY_LEFT"}, {106, "KEY_RIGHT"}, {107, "KEY_END"},
        {108, "KEY_DOWN"}, {109, "KEY_PAGEDOWN"}, {110, "KEY_INSERT"}, {111, "KEY_DELETE"}, {0x110, "BTN_LEFT"},
        {0x111, "BTN_RIGHT"}, {0x112, "BTN_MIDDLE"}, {0x14a, "BTN_TOUCH"}};
    static const char *letters[] = {"KEY_Q", "KEY_W", "KEY_E", "KEY_R", "KEY_T", "KEY_Y", "KEY_U", "KEY_I", "KEY_O", "KEY_P"};
    static const char *row2[] = {"KEY_A", "KEY_S", "KEY_D", "KEY_F", "KEY_G", "KEY_H", "KEY_J", "KEY_K", "KEY_L"};
    static const char *row3[] = {"KEY_Z", "KEY_X", "KEY_C", "KEY_V", "KEY_B", "KEY_N", "KEY_M"};
    static const char *digits[] = {"KEY_1", "KEY_2", "KEY_3", "KEY_4", "KEY_5", "KEY_6", "KEY_7", "KEY_8", "KEY_9", "KEY_0"};
    if (code >= 16 && code <= 25) return letters[code - 16];
    if (code >= 30 && code <= 38) return row2[code - 30];
    if (code >= 44 && code <= 50) return row3[code - 44];
    if (code >= 2 && code <= 11) return digits[code - 2];
    if (code >= 59 && code <= 68) {
        static char f[8];
        std::snprintf(f, sizeof(f), "KEY_F%d", code - 58);
        return f;
    }
    auto it = names.find(code);
    return it == names.end() ? "?" : it->second;
}

// /sys/class/input/eventN/ATTR without the trailing newline ("" if missing).
std::string inputAttr(const std::string &event, const std::string &attr) {
    bool ok;
    auto v = readFile("/sys/class/input/" + event + "/" + attr, ok);
    while (!v.empty() && v.back() == '\n') v.pop_back();
    return v;
}

// What an input device is: "keyboard", "mouse", or "pixel_monitor NAME (fb1, 320x200)" for touchscreens.
std::string inputDescription(const std::string &event) {
    auto name = inputAttr(event, "name");
    auto device = inputAttr(event, "device");
    auto player = inputAttr(event, "player");
    if (!player.empty()) return name + " of " + player + " (keys, captured mouse)";
    if (device.empty()) return name;
    auto size = inputAttr(event, "size");
    auto fb = inputAttr(event, "fb");
    auto comma = size.find(',');
    if (comma != std::string::npos) size[comma] = 'x';
    std::string extra = fb.empty() ? size : fb + ", " + size;
    return name + " " + device + (extra.empty() ? "" : " (" + extra + ")");
}

// Print events from an input device, like Linux evtest (Ctrl+C to stop); -l lists the devices.
int cmdEvtest(Args &a) {
    if (a.size() > 1 && (a[1] == "-h" || a[1] == "--help")) return usage("evtest [-l] [DEVICE]   (default /dev/input/event0; -l lists devices)");
    if (a.size() > 1 && a[1] == "-l") {
        DIR *dir = opendir("/sys/class/input");
        if (!dir) return fail("/sys/class/input: " + std::string(std::strerror(errno)));
        std::vector<std::string> events;
        while (auto *e = readdir(dir)) {
            if (std::strncmp(e->d_name, "event", 5) == 0) events.emplace_back(e->d_name);
        }
        closedir(dir);
        std::sort(events.begin(), events.end(), [](const std::string &x, const std::string &y) {
            return std::atoi(x.c_str() + 5) < std::atoi(y.c_str() + 5);
        });
        for (auto &e : events) std::printf("/dev/input/%s\t%s\n", e.c_str(), inputDescription(e).c_str());
        return 0;
    }
    std::string device = a.size() > 1 ? a[1] : "/dev/input/event0";
    int fd = open(device.c_str(), O_RDONLY);
    if (fd < 0) return fail(device + ": " + std::strerror(errno));
    bool mice = device.find("mice") != std::string::npos;
    auto slash = device.rfind('/');
    std::printf("Input device %s (%s). Ctrl+C to stop.\n", device.c_str(),
                mice ? "PS/2 packets" : inputDescription(device.substr(slash + 1)).c_str());
    std::fflush(stdout);
    // Keys also reach the terminal; keep them from being echoed or piling up for the shell.
    cc::tty::Mode quiet(CC_TTY_RAW | CC_TTY_NOECHO);
    unsigned char buf[24 * 16];
    while (true) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) break;
        if (mice) {
            for (ssize_t i = 0; i + 2 < n; i += 3) {
                int dx = static_cast<int8_t>(buf[i + 1]), dy = static_cast<int8_t>(buf[i + 2]);
                std::printf("buttons %c%c%c  dx %4d  dy %4d\n", buf[i] & 1 ? 'L' : '-', buf[i] & 4 ? 'M' : '-', buf[i] & 2 ? 'R' : '-', dx, dy);
            }
        } else {
            for (ssize_t i = 0; i + 23 < n; i += 24) {
                input_event e;
                std::memcpy(&e, buf + i, sizeof(e));
                static const char *types[] = {"EV_SYN", "EV_KEY", "EV_REL", "EV_ABS"};
                const char *type = e.type < 4 ? types[e.type] : "?";
                if (e.type == EV_SYN) {
                    std::printf("Event: time %lld.%06lld, -------------- SYN_REPORT ------------\n", static_cast<long long>(e.time.tv_sec),
                                static_cast<long long>(e.time.tv_usec));
                    continue;
                }
                const char *code = e.type == EV_KEY ? keyName(e.code)
                                   : e.type == EV_ABS ? (e.code == ABS_X ? "ABS_X" : "ABS_Y")
                                   : e.code == REL_WHEEL ? "REL_WHEEL" : e.code == REL_X ? "REL_X" : e.code == REL_Y ? "REL_Y" : "?";
                std::printf("Event: time %lld.%06lld, type %d (%s), code %d (%s), value %d\n", static_cast<long long>(e.time.tv_sec),
                            static_cast<long long>(e.time.tv_usec), e.type, type, e.code, code, e.value);
            }
        }
        std::fflush(stdout);
    }
    close(fd);
    return 0;
}

// ---- wget / curl (CC's HTTP API: the server's HTTP settings and address rules apply) -------------------------------

bool writeAll(const std::string &path, const std::string &data) {
    FILE *f = path == "-" ? stdout : std::fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = std::fwrite(data.data(), 1, data.size(), f) == data.size();
    if (f == stdout) std::fflush(stdout);
    else ok = std::fclose(f) == 0 && ok;
    return ok;
}

// Download a URL to a file (default: the last part of its path).
int cmdWget(Args &a) {
    const char *text = "wget [-q] [-O FILE] URL   (-O - writes to standard output)";
    std::string url, out;
    bool quiet = false;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-h" || a[i] == "--help") return usage(text);
        if (a[i] == "-q") quiet = true;
        else if (a[i] == "-O" && i + 1 < a.size()) out = a[++i];
        else url = a[i];
    }
    if (url.empty()) return usage(text);
    if (out.empty()) {
        auto path = url.substr(url.find("://") == std::string::npos ? 0 : url.find("://") + 3);
        auto q = path.find_first_of("?#");
        if (q != std::string::npos) path.resize(q);
        auto slash = path.rfind('/');
        out = slash == std::string::npos || slash + 1 == path.size() ? "index.html" : path.substr(slash + 1);
    }
    if (!quiet) std::fprintf(stderr, "Connecting to %s...\n", url.c_str());
    auto r = cc::http::get(url);
    if (!r.ok) {
        std::fprintf(stderr, "wget: %s: %s\n", url.c_str(), r.error.c_str());
        return 1;
    }
    if (!writeAll(out, r.body)) return fail("cannot write " + out);
    if (!quiet && out != "-") std::fprintf(stderr, "Saved %s (%zu bytes)\n", out.c_str(), r.body.size());
    return 0;
}

// An HTTP request; the body goes to standard output (or -o FILE).
int cmdCurl(Args &a) {
    const char *text = "curl [-s] [-f] [-i] [-o FILE] [-X METHOD] [-d DATA|@FILE] [-H 'Name: value']... URL";
    cc::http::Request req;
    std::string out = "-";
    bool silent = false, failOnError = false, showHeaders = false;
    // Combined short flags, as curl takes them: -sf = -s -f
    Args expanded{a[0]};
    for (size_t i = 1; i < a.size(); i++) {
        auto &arg = a[i];
        if (arg.size() > 2 && arg[0] == '-' && arg[1] != '-' && arg.find_first_not_of("sfiL", 1) == std::string::npos) {
            for (size_t k = 1; k < arg.size(); k++) expanded.push_back(std::string("-") + arg[k]);
        } else {
            expanded.push_back(arg);
        }
    }
    for (size_t i = 1; i < expanded.size(); i++) {
        auto &arg = expanded[i];
        if (arg == "-h" || arg == "--help") return usage(text);
        if (arg == "-s" || arg == "--silent") silent = true;
        else if (arg == "-f" || arg == "--fail") failOnError = true;
        else if (arg == "-i" || arg == "--include") showHeaders = true;
        else if (arg == "-L" || arg == "--location") req.redirect = true;
        else if ((arg == "-o" || arg == "--output") && i + 1 < expanded.size()) out = expanded[++i];
        else if ((arg == "-X" || arg == "--request") && i + 1 < expanded.size()) req.method = expanded[++i];
        else if ((arg == "-d" || arg == "--data") && i + 1 < expanded.size()) {
            auto data = expanded[++i];
            if (!data.empty() && data[0] == '@') {
                bool ok;
                data = readFile(data.substr(1), ok);
                if (!ok) return fail("cannot read " + expanded[i].substr(1));
            }
            req.body = data;
        } else if ((arg == "-H" || arg == "--header") && i + 1 < expanded.size()) {
            auto h = expanded[++i];
            auto colon = h.find(':');
            if (colon == std::string::npos) return usage(text);
            auto value = h.substr(colon + 1);
            value.erase(0, value.find_first_not_of(' '));
            req.headers.emplace_back(h.substr(0, colon), value);
        } else req.url = arg;
    }
    if (req.url.empty()) return usage(text);
    if (req.body && req.method.empty()) req.method = "POST";
    auto r = cc::http::request(req);
    if (r.status == 0) {
        if (!silent) std::fprintf(stderr, "curl: %s: %s\n", req.url.c_str(), r.error.c_str());
        return 1;
    }
    if (failOnError && !r.ok) {
        if (!silent) std::fprintf(stderr, "curl: %s: HTTP %d %s\n", req.url.c_str(), r.status, r.error.c_str());
        return 22;
    }
    if (showHeaders) {
        std::printf("HTTP %d\n", r.status);
        for (auto &[name, value] : r.headers.members()) std::printf("%s: %s\n", name.c_str(), value.asString("").c_str());
        std::printf("\n");
    }
    if (!writeAll(out, r.body)) return fail("cannot write " + out);
    return 0;
}

// ---- turtle -----------------------------------------------------------------------------------------------------

// The turtle this computer is (its API is the peripheral "turtle"): CC's turtle API from the shell.
int cmdTurtle(Args &a) {
    const char *text = "turtle forward|back|up|down|left|right [N]     move or turn N times\n"
                       "       turtle dig|digup|digdown [left|right]           dig with a tool upgrade\n"
                       "       turtle place|placeup|placedown | attack[up|down] | drop[up|down] [N] | suck[up|down] [N]\n"
                       "       turtle select SLOT | inv | refuel [N] | fuel | equip left|right | detect[up|down] | inspect[up|down]\n"
                       "       turtle METHOD [ARGS...]                         any method of the turtle API (see man turtle)";
    if (a.size() < 2 || a[1] == "-h" || a[1] == "--help") return usage(text);
    cc::Peripheral turtle("turtle");
    if (!turtle.present()) return fail("this computer is not a turtle");
    auto cmd = a[1];
    std::transform(cmd.begin(), cmd.end(), cmd.begin(), [](unsigned char c) { return std::tolower(c); });

    // Run a method; prints a failure's reason. Returns the result values after the leading true/false.
    auto run = [&](const std::string &method, const cc::Json &args, bool &ok) {
        auto r = turtle.call(method, args);
        if (!r) {
            std::fprintf(stderr, "turtle: %s\n", r.error.c_str());
            ok = false;
            return cc::Json::array();
        }
        ok = true;
        auto rest = cc::Json::array();
        size_t from = 0;
        if (r.value.size() > 0 && r.value[0].isBool()) {
            ok = r.value[0].asBool();
            from = 1;
            if (!ok && r.value.size() > 1 && r.value[1].isString()) {
                std::fprintf(stderr, "turtle: %s\n", r.value[1].asString().c_str());
                return rest;
            }
        }
        for (size_t i = from; i < r.value.size(); i++) rest.push(r.value[i]);
        return rest;
    };

    static const std::map<std::string, std::string> moves = {
        {"forward", "forward"}, {"back", "back"}, {"up", "up"}, {"down", "down"}, {"left", "turnLeft"}, {"right", "turnRight"}};
    if (auto m = moves.find(cmd); m != moves.end()) {
        int n = a.size() > 2 ? std::atoi(a[2].c_str()) : 1;
        for (int i = 0; i < n; i++) {
            bool ok;
            run(m->second, cc::Json::array(), ok);
            if (!ok) return 1;
        }
        return 0;
    }
    if (cmd == "fuel") {
        bool ok;
        auto level = run("getFuelLevel", cc::Json::array(), ok);
        auto limit = run("getFuelLimit", cc::Json::array(), ok);
        std::printf("%s / %s\n", level.size() ? level[0].dump().c_str() : "?", limit.size() ? limit[0].dump().c_str() : "?");
        return 0;
    }
    if (cmd == "inv") {
        bool ok;
        auto sel = run("getSelectedSlot", cc::Json::array(), ok);
        int selected = sel.size() ? sel[0].asInt() : 0;
        for (int slot = 1; slot <= 16; slot++) {
            auto detail = run("getItemDetail", cc::Json::array({slot}), ok);
            auto item = detail.size() ? detail[0] : cc::Json();
            if (item.isNull() && slot != selected) continue;
            std::printf("%c%2d  %s", slot == selected ? '*' : ' ', slot, item.isNull() ? "-" : item["name"].asString("?").c_str());
            if (!item.isNull()) std::printf(" x%d", item["count"].asInt());
            std::printf("\n");
        }
        return 0;
    }
    static const std::map<std::string, std::string> names = {
        {"digup", "digUp"},         {"digdown", "digDown"},         {"placeup", "placeUp"},     {"placedown", "placeDown"},
        {"attackup", "attackUp"},   {"attackdown", "attackDown"},   {"dropup", "dropUp"},       {"dropdown", "dropDown"},
        {"suckup", "suckUp"},       {"suckdown", "suckDown"},       {"detectup", "detectUp"},   {"detectdown", "detectDown"},
        {"inspectup", "inspectUp"}, {"inspectdown", "inspectDown"}, {"compareup", "compareUp"}, {"comparedown", "compareDown"}};
    std::string method = a[1];
    size_t first = 2;
    if (cmd == "equip" && a.size() > 2) {
        method = a[2] == "right" ? "equipRight" : "equipLeft";
        first = 3;
    } else if (auto n = names.find(cmd); n != names.end()) {
        method = n->second;
    }
    auto args = cc::Json::array();
    for (size_t i = first; i < a.size(); i++) {
        char *end = nullptr;
        double number = std::strtod(a[i].c_str(), &end);
        if (end && *end == 0 && !a[i].empty()) args.push(number);
        else args.push(a[i]);
    }
    bool ok;
    auto rest = run(method, args, ok);
    for (size_t i = 0; i < rest.size(); i++) std::printf("%s\n", rest[i].isString() ? rest[i].asString().c_str() : rest[i].dump().c_str());
    // detect/compare answer only true or false: printed, and it is the exit status
    if (method.rfind("detect", 0) == 0 || method.rfind("compare", 0) == 0) std::printf("%s\n", ok ? "true" : "false");
    return ok ? 0 : 1;
}

// ---- fbset ------------------------------------------------------------------------------------------------------

// /sys/class/graphics/fbN attributes (see /dev/fb0 in 'man gfx'); fbN chosen with -fb.
std::string fbDevice = "fb0";

std::string sysRead(const std::string &name) {
    bool ok;
    auto v = readFile("/sys/class/graphics/" + fbDevice + "/" + name, ok);
    while (!v.empty() && v.back() == '\n') v.pop_back();
    return v;
}

bool sysWrite(const std::string &name, const std::string &value) {
    FILE *f = std::fopen(("/sys/class/graphics/" + fbDevice + "/" + name).c_str(), "w");
    if (!f) return false;
    bool ok = std::fprintf(f, "%s\n", value.c_str()) > 0;
    return std::fclose(f) == 0 && ok;
}

int cmdFbset(Args &a) {
    const char *text = "fbset                      show the mode\n"
                       "       fbset WxH[xBPP]            set the resolution (and depth: 1 2 4 8 bits per pixel, 16 or 32 true colour)\n"
                       "       fbset -g W H | -depth BPP  same, Linux style\n"
                       "       fbset auto                 back to the default size (fills the text area)\n"
                       "       fbset graphics | text      show the framebuffer / the text screen\n"
                       "       fbset -pal INDEX RRGGBB    set a palette colour\n"
                       "       -fb /dev/fbN               another framebuffer (fb1... are pixel monitors)";
    for (size_t i = 1; i + 1 < a.size(); i++) {
        if (a[i] != "-fb") continue;
        auto dev = a[i + 1];
        fbDevice = dev.substr(dev.rfind('/') + 1);
        a.erase(a.begin() + static_cast<long>(i), a.begin() + static_cast<long>(i) + 2);
        break;
    }
    if (!sysRead("bits_per_pixel").size()) return fail("no such framebuffer: " + fbDevice);
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        bool ok = true;
        if (o == "-h" || o == "--help") return usage(text);
        if (o == "auto") {
            ok = sysWrite("virtual_size", "auto");
        } else if (o == "text" || o == "graphics") {
            ok = sysWrite("display", o);
        } else if (o == "-g" && i + 2 < a.size()) {
            ok = sysWrite("virtual_size", a[i + 1] + "," + a[i + 2]);
            i += 2;
        } else if (o == "-depth" && i + 1 < a.size()) {
            ok = sysWrite("bits_per_pixel", a[++i]);
        } else if (o == "-pal" && i + 2 < a.size()) {
            ok = sysWrite("palette", a[i + 1] + " " + a[i + 2]);
            i += 2;
        } else if (!o.empty() && std::isdigit(static_cast<unsigned char>(o[0]))) {
            int w = 0, h = 0, bpp = 0;
            int n = std::sscanf(o.c_str(), "%dx%dx%d", &w, &h, &bpp);
            if (n < 2) return usage(text);
            if (n == 3) ok = sysWrite("bits_per_pixel", std::to_string(bpp));
            ok = ok && sysWrite("virtual_size", std::to_string(w) + "," + std::to_string(h));
        } else {
            return usage(text);
        }
        if (!ok) return fail("invalid value: " + o + " (max size " + sysRead("max_size") + ", depth 1/2/4/8)");
    }
    auto size = sysRead("virtual_size"), bpp = sysRead("bits_per_pixel"), max = sysRead("max_size");
    int w = 0, h = 0, b = std::atoi(bpp.c_str());
    std::sscanf(size.c_str(), "%d,%d", &w, &h);
    std::string colours = b > 8 ? (b == 16 ? "true colour, RGB565" : "true colour, XRGB8888") : std::to_string(1 << b) + " colours";
    if (fbDevice == "fb0") {
        std::printf("mode:     %dx%d, %d bpp (%s), screen shows %s\n", w, h, b, colours.c_str(), sysRead("display").c_str());
        std::printf("default:  %s (a common resolution filling the text area; 'fbset auto')\n", sysRead("default_size").c_str());
    } else {
        std::printf("monitor:  %s (pixel monitor), %dx%d, %d bpp (%s)\n", sysRead("device").c_str(), w, h, b, colours.c_str());
    }
    std::printf("max:      %s; depths 1/2/4/8 bpp (24-bit palette), 16/32 bpp (true colour)\n", max.c_str());
    int bytes = b > 8 ? b / 8 : 1;
    if (b > 8)
        std::printf("device:   /dev/%s - %d bytes per pixel (%s), offset = (y * %d + x) * %d\n", fbDevice.c_str(), bytes,
                    b == 16 ? "RGB565, little-endian" : "blue, green, red, unused", w, bytes);
    else
        std::printf("device:   /dev/%s - one byte (palette index) per pixel, offset = y * %d + x\n", fbDevice.c_str(), w);
    std::printf("network:  only changed rectangles are sent, %d bits per pixel, compressed (full frame %d KiB raw)\n",
                b == 32 ? 24 : b, w * h * (b == 32 ? 24 : b) / 8 / 1024);
    return 0;
}

// ---- nc, ss: the kernel's network (cc::net, man net) -------------------------------------------------------------

std::string netError(int err) {
    switch (err) {
        case ECONNREFUSED: return "Connection refused";
        case ETIMEDOUT: return "Connection timed out (no such computer in reach?)";
        case ENETDOWN: return "Network is down (no modem attached)";
        case EADDRINUSE: return "Address in use";
        default: return std::strerror(err);
    }
}

// Copy standard input to the connection and the connection to standard output until the other side closes.
// At the end of standard input the connection is half-closed: the other side reads end of file, as nc -N.
int relay(int fd, bool input) {
    char buf[4096];
    while (true) {
        auto w = input ? cc::waitAny({0, fd}) : cc::waitAny({fd});
        if (w.fd == 0) {
            ssize_t n = read(0, buf, sizeof(buf));
            if (n <= 0) {
                cc::net::shutdown(fd);
                input = false;
            } else if (!cc::net::sendAll(fd, buf, static_cast<size_t>(n))) {
                return fail(std::string("write: ") + netError(errno));
            }
        } else if (w.fd == fd) {
            ssize_t n = read(fd, buf, sizeof(buf));
            if (n < 0) return fail(std::string("read: ") + netError(errno));
            if (n == 0) return 0;
            fwrite(buf, 1, static_cast<size_t>(n), stdout);
            std::fflush(stdout);
        } else if (w.event && w.event->name == "terminate") {
            return 130;
        }
    }
}

int cmdNc(Args &a) {
    bool listen = false, keep = false, scan = false, verbose = false, noInput = false;
    int timeout = 10;
    std::vector<std::string> rest;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        if (o == "--help" || o == "-h") {
            std::puts("usage: nc [-v] [-d] [-w SECONDS] HOST PORT        connect; stdin goes there, the answer to stdout\n"
                      "       nc -l [-k] [-v] [-p] PORT              wait for a connection (-k: one after another)\n"
                      "       nc -z [-v] HOST PORT[-LAST]            only check which ports accept connections\n"
                      "  -d: do not read standard input (a server in the background). Ends when the other side closes;\n"
                      "  the end of standard input closes our direction. HOST is a hostname (localhost: this one). man net");
            return 0;
        }
        if (o.size() > 1 && o[0] == '-' && !std::isdigit(static_cast<unsigned char>(o[1]))) {
            for (size_t k = 1; k < o.size(); k++) {
                char c = o[k];
                if (c == 'l') listen = true;
                else if (c == 'k') keep = true;
                else if (c == 'z') scan = true;
                else if (c == 'v') verbose = true;
                else if (c == 'd') noInput = true;
                else if (c == 'n' || c == 'N' || c == 'p') {
                    // -n: no name lookup, -N: shutdown on EOF (always), -p: the port follows
                } else if (c == 'w') {
                    if (k + 1 < o.size()) timeout = std::atoi(o.c_str() + k + 1);
                    else if (i + 1 < a.size()) timeout = std::atoi(a[++i].c_str());
                    break;
                } else {
                    return fail(std::string("invalid option -- '") + c + "' (nc --help)");
                }
            }
            continue;
        }
        rest.push_back(o);
    }
    if (listen) {
        if (rest.size() != 1) return usage("nc -l [-k] PORT");
        int port = std::atoi(rest[0].c_str());
        int l = cc::net::listen(port);
        if (l < 0) return fail("port " + rest[0] + ": " + netError(-l));
        if (verbose) std::fprintf(stderr, "Listening on %s port %d\n", cc::hostname().c_str(), port);
        int status = 0;
        do {
            int fd = cc::net::accept(l);
            if (fd < 0) return fail("accept: " + netError(-fd));
            if (verbose) {
                if (auto peer = cc::net::info(fd)) std::fprintf(stderr, "Connection from %s %d\n", peer->remote.c_str(), peer->remotePort);
            }
            status = relay(fd, !noInput);
            close(fd);
        } while (keep && status != 130);
        close(l);
        return status;
    }
    if (rest.size() != 2) return usage("nc [-v] HOST PORT (nc --help)");
    auto &host = rest[0];
    int first = 0, last = 0;
    if (std::sscanf(rest[1].c_str(), "%d-%d", &first, &last) < 2) last = first;
    if (first <= 0 || last > 65535 || last < first) return fail("invalid port: " + rest[1]);
    if (scan) {
        int open = 0;
        for (int port = first; port <= last; port++) {
            int fd = cc::net::connect(host, port, timeout * 1000);
            if (fd >= 0) {
                open++;
                close(fd);
                std::printf("Connection to %s %d port succeeded!\n", host.c_str(), port);
            } else {
                if (verbose || first == last || fd != -ECONNREFUSED) {
                    std::fprintf(stderr, "nc: connect to %s port %d failed: %s\n", host.c_str(), port, netError(-fd).c_str());
                }
                if (fd != -ECONNREFUSED) return 1; // nobody there: the other ports will not answer either
            }
        }
        return open ? 0 : 1;
    }
    int fd = cc::net::connect(host, first, timeout * 1000);
    if (fd < 0) return fail("connect to " + host + " port " + std::to_string(first) + " failed: " + netError(-fd));
    if (verbose) std::fprintf(stderr, "Connection to %s %d port succeeded!\n", host.c_str(), first);
    int status = relay(fd, !noInput);
    close(fd);
    return status;
}

// ---- ip, lsdev: the device tree through /sys (man net, man dev) ---------------------------------------------------

std::vector<std::string> dirEntries(const std::string &path) {
    std::vector<std::string> out;
    if (DIR *d = opendir(path.c_str())) {
        while (auto *e = readdir(d)) {
            std::string n = e->d_name;
            if (n != "." && n != "..") out.push_back(n);
        }
        closedir(d);
    }
    return out;
}

// One line of a /sys attribute, without the newline ("" if missing).
std::string attr(const std::string &path) {
    bool ok = false;
    auto v = readFile(path, ok);
    while (!v.empty() && (v.back() == '\n' || v.back() == '\r')) v.pop_back();
    return ok ? v : "";
}

int cmdIp(Args &a) {
    bool stats = false;
    Args words;
    for (size_t i = 1; i < a.size(); i++) {
        if (a[i] == "-s" || a[i] == "-stats") stats = true;
        else if (a[i] == "--help" || a[i] == "-h" || a[i] == "help") {
            std::puts("usage: ip [-s] link [show [DEV]]       network interfaces (-s: packet counters)\n"
                      "       ip link set DEV up|down          switch one on or off (root)\n"
                      "       ip addr [show [DEV]]             the name and modem channel they listen on\n"
                      "  Interfaces: lo (this computer), wlanN (wireless and ender modems), ethN (wired). man net");
            return 0;
        } else words.push_back(a[i]);
    }
    auto object = words.empty() ? std::string("link") : words[0];
    if (object == "l" || object == "li") object = "link";
    if (object == "a" || object == "addr" || object == "address") object = "addr";
    if (object != "link" && object != "addr") return fail("Object \"" + object + "\" is unknown, try \"ip help\".");
    if (object == "link" && words.size() >= 4 && words[1] == "set") {
        auto path = "/sys/class/net/" + words[2] + "/operstate";
        if (words[3] != "up" && words[3] != "down") return fail("Either \"up\" or \"down\" expected.");
        if (access(("/sys/class/net/" + words[2]).c_str(), F_OK) != 0) return fail("Cannot find device \"" + words[2] + "\"");
        FILE *f = std::fopen(path.c_str(), "w");
        if (!f) return fail(std::string("RTNETLINK answers: ") + (errno == EACCES ? "Operation not permitted (sudo)" : std::strerror(errno)));
        std::fprintf(f, "%s\n", words[3].c_str());
        if (std::fclose(f) != 0) return fail(std::string("RTNETLINK answers: ") + std::strerror(errno));
        return 0;
    }
    std::string only = words.size() >= 3 && words[1] == "show" ? words[2] : words.size() == 2 && words[1] != "show" ? words[1] : "";
    auto names = dirEntries("/sys/class/net");
    // lo first, as Linux numbers it
    std::sort(names.begin(), names.end(), [](auto &x, auto &y) { return (x == "lo") != (y == "lo") ? x == "lo" : x < y; });
    if (!only.empty() && std::find(names.begin(), names.end(), only) == names.end()) return fail("Device \"" + only + "\" does not exist.");
    auto host = cc::hostname();
    int index = 0;
    for (auto &n : names) {
        index++;
        if (!only.empty() && n != only) continue;
        auto dir = "/sys/class/net/" + n + "/";
        auto type = attr(dir + "type"), state = attr(dir + "operstate"), address = attr(dir + "address"), device = attr(dir + "device");
        bool up = state == "up";
        std::printf("%d: %s: <%s%s> mtu %s state %s\n", index, n.c_str(), type == "loopback" ? "LOOPBACK," : "", up ? "UP" : "DOWN",
                    attr(dir + "mtu").c_str(), up ? "UP" : "DOWN");
        auto range = attr(dir + "range");
        std::printf("    link/%s%s%s\n", type.c_str(), device.empty() ? "" : (" modem " + device).c_str(),
                    range.empty() ? "" : (" range " + range).c_str());
        if (object == "addr" && up) std::printf("    inet %s channel %s\n", host.c_str(), address.empty() ? "-" : address.c_str());
        if (stats) {
            auto s = dir + "statistics/";
            std::printf("    RX: bytes %s packets %s\n    TX: bytes %s packets %s\n", attr(s + "rx_bytes").c_str(), attr(s + "rx_packets").c_str(),
                        attr(s + "tx_bytes").c_str(), attr(s + "tx_packets").c_str());
        }
    }
    return 0;
}

int cmdLsdev(Args &a) {
    if (a.size() > 1) {
        std::puts("usage: lsdev   devices by class with their /dev node and peripheral, then peripherals no driver took\n"
                  "  (those are reachable as /dev/periph/NAME). From /sys/class and /sys/bus/peripheral. man dev");
        return a[1] == "--help" || a[1] == "-h" ? 0 : 1;
    }
    std::printf("%-9s %-10s %-18s %s\n", "CLASS", "DEVICE", "NODE", "PERIPHERAL");
    auto classes = dirEntries("/sys/class");
    std::sort(classes.begin(), classes.end());
    for (auto &c : classes) {
        auto devs = dirEntries("/sys/class/" + c);
        std::sort(devs.begin(), devs.end());
        for (auto &d : devs) {
            auto dir = "/sys/class/" + c + "/" + d + "/";
            auto uevent = attr(dir + "uevent");
            auto node = uevent.rfind("DEVNAME=", 0) == 0 ? "/dev/" + uevent.substr(8) : std::string("-");
            auto periph = attr(dir + "device");
            auto player = attr(dir + "player");
            auto from = !periph.empty() ? periph : !player.empty() ? "keyboard of " + player : std::string("(built in)");
            std::printf("%-9s %-10s %-18s %s\n", c.c_str(), d.c_str(), node.c_str(), from.c_str());
        }
    }
    auto bus = dirEntries("/sys/bus/peripheral/devices");
    std::sort(bus.begin(), bus.end());
    bool header = false;
    for (auto &p : bus) {
        if (!attr("/sys/bus/peripheral/devices/" + p + "/driver").empty()) continue;
        if (!header) {
            std::printf("\nwithout a driver (/dev/periph/NAME):\n");
            header = true;
        }
        auto types = attr("/sys/bus/peripheral/devices/" + p + "/type");
        std::printf("  %-16s %s\n", p.c_str(), types.c_str());
    }
    return 0;
}

int cmdSs(Args &a) {
    bool listening = false, all = false, summary = false;
    for (size_t i = 1; i < a.size(); i++) {
        auto &o = a[i];
        if (o == "--help" || o == "-h") {
            std::puts("usage: ss [-l] [-a] [-s]   (also netstat)\n"
                      "  connections of this computer; -l listening sockets, -a both, -s summary (name, modem channel)");
            return 0;
        }
        if (o.size() < 2 || o[0] != '-') return fail("unknown argument " + o);
        for (size_t k = 1; k < o.size(); k++) {
            char c = o[k];
            if (c == 'l') listening = true;
            else if (c == 'a') all = true;
            else if (c == 's') summary = true;
            else if (std::strchr("tnpuexr", c)) {
                // accepted for habit: there is one kind of socket and no name lookup
            } else {
                return fail(std::string("invalid option -- '") + c + "'");
            }
        }
    }
    // netstat shows everything, as netstat -a would be needed for listening sockets otherwise
    if (prog == "netstat" && !listening) all = true;
    auto st = cc::net::status();
    if (summary) {
        int listen = 0, conns = 0;
        for (auto &s : st.sockets) (s.state == "LISTEN" ? listen : conns)++;
        std::printf("Host %s, modem channel %d (other computers send to it)\n", st.hostname.c_str(), st.channel);
        std::string ifaces;
        for (auto &n : dirEntries("/sys/class/net")) {
            if (n != "lo") ifaces += (ifaces.empty() ? "" : ", ") + n + " (" + attr("/sys/class/net/" + n + "/type") + ", " +
                                     attr("/sys/class/net/" + n + "/operstate") + ")";
        }
        std::printf("Interfaces: %s\n", ifaces.empty() ? "none (no modem: only localhost)" : ifaces.c_str());
        std::printf("Sockets: %d listening, %d connections\n", listen, conns);
        return 0;
    }
    std::printf("%-11s %6s %6s  %-24s %-24s %s\n", "State", "Recv-Q", "Send-Q", "Local Address:Port", "Peer Address:Port", "User");
    for (auto &s : st.sockets) {
        bool isListen = s.state == "LISTEN";
        if (!all && listening != isListen) continue;
        auto local = s.local + ":" + std::to_string(s.localPort);
        auto peer = isListen ? std::string("*:*") : s.remote + ":" + std::to_string(s.remotePort);
        auto *pw = getpwuid(static_cast<uid_t>(s.uid));
        std::printf("%-11s %6ld %6ld  %-24s %-24s %s\n", s.state.c_str(), s.recvQueue, s.sendQueue, local.c_str(), peer.c_str(),
                    pw ? pw->pw_name : std::to_string(s.uid).c_str());
    }
    return 0;
}

} // namespace

// Usage of the commands that do not parse --help themselves (it would be taken as a file or ignored).
const char *usageOf(const std::string &name) {
    static const std::map<std::string, const char *> table = {
        {"disk", "disk [-n DRIVE] [info] | eject | label [TEXT] | play | stop"},
        {"eject", "eject [-n DRIVE]"},
        {"lp", "lp [-n PRINTER] [-t TITLE] [FILE...]"},
        {"energy", "energy   (energy and fluid storage of attached blocks)"},
        {"rednet", "rednet open [MODEM] | close [MODEM] | status | send ID MESSAGE [PROTOCOL] | broadcast MESSAGE [PROTOCOL] | receive [PROTOCOL] [TIMEOUT] | listen [PROTOCOL] | lookup PROTOCOL [HOST] | host PROTOCOL HOSTNAME"},
        {"speaker", "speaker [-n NAME] note INSTRUMENT [VOLUME] [PITCH] | sound ID [VOLUME] [PITCH] | play FILE.dfpwm [VOLUME] | stop"},
        {"gps", "gps locate [TIMEOUT] | gps host X Y Z"},
        {"inv", "inv [NAME] | push FROM TO SLOT [COUNT] [TOSLOT] | detail NAME SLOT"},
    };
    auto it = table.find(name);
    return it == table.end() ? nullptr : it->second;
}

int main(int argc, char **argv) {
    Args args(argv, argv + argc);
    auto slash = args[0].rfind('/');
    prog = slash == std::string::npos ? args[0] : args[0].substr(slash + 1);
    static const std::map<std::string, std::function<int(Args &)>> commands = {
        {"rednet", cmdRednet}, {"gps", cmdGps}, {"speaker", cmdSpeaker}, {"lp", cmdLp},   {"disk", cmdDisk},
        {"eject", cmdDisk},    {"monitor", cmdMonitor}, {"inv", cmdInv}, {"energy", cmdEnergy}, {"fbset", cmdFbset}, {"evtest", cmdEvtest}, {"turtle", cmdTurtle}, {"wget", cmdWget}, {"curl", cmdCurl},
        {"nc", cmdNc}, {"ss", cmdSs}, {"netstat", cmdSs}, {"ip", cmdIp}, {"lsdev", cmdLsdev},
    };
    auto it = commands.find(prog);
    if (it == commands.end() && args.size() > 1) {
        // "ccutils rednet ..."
        args.erase(args.begin());
        prog = args[0];
        it = commands.find(prog);
    }
    if (args.size() > 1 && args[1] == "--help") {
        if (auto *u = usageOf(prog)) {
            std::printf("usage: %s\n", u);
            return 0;
        }
    }
    if (it == commands.end()) {
        std::fprintf(stderr, "ccutils: commands:");
        for (auto &[name, fn] : commands) std::fprintf(stderr, " %s", name.c_str());
        std::fprintf(stderr, "\n");
        return 1;
    }
    return it->second(args);
}
