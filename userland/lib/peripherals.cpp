// Typed wrappers for the CC: Tweaked peripherals, rednet and gps (see <baremetal.hpp>).
//
// rednet and gps follow CC's Lua implementations (rom/apis/rednet.lua, gps.lua) message for message, so C++
// programs and Lua computers can talk to each other.
#include <baremetal.hpp>
#include <cstdio>
#include <cctype>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <ctime>
#include <random>
#include <set>

namespace cc {

namespace {

constexpr int MAX_ID_CHANNELS = 65500;

// rednet's channel for a computer ID.
int idChannel(int id) {
    return id % MAX_ID_CHANNELS;
}

double now() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) / 1e9;
}

// Milliseconds left until `deadline` (< 0: no deadline); 0 when it has passed.
int remainingMs(double deadline) {
    if (deadline < 0) return -1;
    double left = deadline - now();
    return left <= 0 ? 0 : static_cast<int>(std::ceil(left * 1000));
}

// First returned value of a successful call (null otherwise).
Json first(const Result &r) {
    return r.ok ? r.value[0] : Json();
}

template <typename T>
std::optional<T> findType(std::string_view type) {
    if (auto p = Peripheral::find(type)) return T(p->name());
    return std::nullopt;
}

template <typename T>
std::vector<T> allOfType(std::string_view type) {
    std::vector<T> out;
    for (auto &p : Peripheral::list()) {
        for (auto &t : p.types) {
            if (t == type) {
                out.emplace_back(p.name);
                break;
            }
        }
    }
    return out;
}

std::pair<int, int> pairOf(const Result &r) {
    return {r.value[0].asInt(), r.value[1].asInt()};
}

} // namespace

void wantEvents() {
    int flags = setProcessFlags(-1);
    if (flags >= 0 && !(flags & CC_PROC_EVENTS)) setProcessFlags(flags | CC_PROC_EVENTS);
}

int computerId() {
    static int id = systemInfo()["id"].asInt(-1);
    return id;
}

std::string hostname() {
    if (FILE *f = std::fopen("/etc/hostname", "r")) {
        char buf[128] = "";
        std::fgets(buf, sizeof(buf), f);
        std::fclose(f);
        std::string name = buf;
        while (!name.empty() && std::isspace(static_cast<unsigned char>(name.back()))) name.pop_back();
        if (!name.empty()) return name;
    }
    auto info = systemInfo();
    return info["label"].isString() && !info["label"].asString().empty() ? info["label"].asString()
                                                                          : "computer-" + std::to_string(computerId());
}

// ---- Modem ------------------------------------------------------------------------------------------------------

std::optional<Modem> Modem::find() {
    auto modems = all();
    for (auto &m : modems) {
        if (m.isWireless()) return m;
    }
    if (!modems.empty()) return modems.front();
    return std::nullopt;
}

std::vector<Modem> Modem::all() {
    return allOfType<Modem>("modem");
}

bool Modem::open(int channel) const {
    wantEvents();
    return call("open", Json::array({channel})).ok;
}

bool Modem::isOpen(int channel) const {
    return first(call("isOpen", Json::array({channel}))).asBool();
}

void Modem::close(int channel) const {
    call("close", Json::array({channel}));
}

void Modem::closeAll() const {
    call("closeAll");
}

bool Modem::transmit(int channel, int replyChannel, const Json &message) const {
    return call("transmit", Json::array({channel, replyChannel, message})).ok;
}

bool Modem::isWireless() const {
    return first(call("isWireless")).asBool();
}

std::vector<std::string> Modem::namesRemote() const {
    std::vector<std::string> out;
    for (auto &n : first(call("getNamesRemote")).items()) out.push_back(n.asString());
    return out;
}

std::string Modem::nameLocal() const {
    return first(call("getNameLocal")).asString({});
}

std::optional<ModemMessage> receiveModem(double timeout) {
    wantEvents();
    double deadline = timeout < 0 ? -1 : now() + timeout;
    while (true) {
        int ms = remainingMs(deadline);
        if (deadline >= 0 && ms == 0) return std::nullopt;
        auto e = pullEvent("modem_message", ms);
        if (!e) return std::nullopt;
        ModemMessage m;
        m.side = e->args[0].asString({});
        m.channel = e->args[1].asInt();
        m.replyChannel = e->args[2].asInt();
        m.message = e->args[3];
        m.distance = e->args[4].asNumber();
        return m;
    }
}

// ---- rednet -----------------------------------------------------------------------------------------------------

namespace rednet {
namespace {

std::set<std::string> openModems;
std::map<std::string, std::string> hosted; // protocol -> hostname
std::map<long long, double> seen;          // message id -> expiry, to drop repeats

long long messageId() {
    static std::mt19937 rng{std::random_device{}()};
    return std::uniform_int_distribution<long long>(1, 2147483647)(rng);
}

bool remember(long long id) {
    double t = now();
    for (auto it = seen.begin(); it != seen.end();) it = it->second < t ? seen.erase(it) : std::next(it);
    if (seen.count(id)) return false;
    seen[id] = t + 9.5;
    return true;
}

std::vector<Modem> targets(const std::string &modem) {
    if (!modem.empty()) return {Modem(modem)};
    return Modem::all();
}

bool transmitAll(int recipient, const Json &packet) {
    bool sent = false;
    int reply = idChannel(computerId());
    for (auto &m : Modem::all()) {
        if (!isOpen(m.name())) continue;
        m.transmit(recipient == BROADCAST ? BROADCAST : idChannel(recipient), reply, packet);
        m.transmit(REPEAT, reply, packet);
        sent = true;
    }
    return sent;
}

bool sendPacket(int recipient, const Json &message, std::string_view protocol) {
    auto id = messageId();
    remember(id);
    auto packet = Json::object({{"nMessageID", Json(static_cast<double>(id))},
                                {"nRecipient", recipient},
                                {"nSender", computerId()},
                                {"message", message}});
    if (!protocol.empty()) packet.set("sProtocol", Json(protocol));
    if (recipient == computerId()) {
        auto args = Json::array({computerId(), message});
        if (!protocol.empty()) args.push(Json(protocol));
        return queueEvent("rednet_message", args);
    }
    return transmitAll(recipient, packet);
}

} // namespace

bool open(const std::string &modem) {
    bool any = false;
    for (auto &m : targets(modem)) {
        if (m.open(idChannel(computerId())) && m.open(BROADCAST)) {
            openModems.insert(m.name());
            any = true;
        }
    }
    wantEvents();
    return any;
}

void close(const std::string &modem) {
    for (auto &m : targets(modem)) {
        m.close(idChannel(computerId()));
        m.close(BROADCAST);
        openModems.erase(m.name());
    }
}

bool isOpen(const std::string &modem) {
    for (auto &m : targets(modem)) {
        if (m.isOpen(idChannel(computerId())) && m.isOpen(BROADCAST)) return true;
    }
    return false;
}

bool send(int recipient, const Json &message, std::string_view protocol) {
    return sendPacket(recipient, message, protocol);
}

bool broadcast(const Json &message, std::string_view protocol) {
    return sendPacket(BROADCAST, message, protocol);
}

std::optional<Message> receive(std::string_view protocol, double timeout) {
    wantEvents();
    double deadline = timeout < 0 ? -1 : now() + timeout;
    while (true) {
        int ms = remainingMs(deadline);
        if (deadline >= 0 && ms == 0) return std::nullopt;
        auto e = pullEvent({}, ms);
        if (!e) return std::nullopt;
        Message m;
        if (e->name == "rednet_message") { // sent to ourselves, or by a Lua program on this machine
            m.sender = e->args[0].asInt(-1);
            m.message = e->args[1];
            m.protocol = e->args[2].asString({});
        } else if (e->name == "modem_message") {
            int channel = e->args[1].asInt();
            const Json &packet = e->args[3];
            if (channel != idChannel(computerId()) && channel != BROADCAST) continue;
            if (!packet.isObject() || !packet["nMessageID"].isNumber()) continue;
            int recipient = packet["nRecipient"].asInt(-1);
            if (recipient != computerId() && recipient != BROADCAST) continue;
            if (!remember(static_cast<long long>(packet["nMessageID"].asNumber()))) continue;
            m.sender = packet["nSender"].isNumber() ? packet["nSender"].asInt() : e->args[2].asInt();
            m.message = packet["message"];
            m.protocol = packet["sProtocol"].asString({});
            m.distance = e->args[4].asNumber();
        } else {
            continue;
        }
        // Answer DNS lookups for our hosted names (CC's rednet.run does this in the background).
        if (m.protocol == "dns" && m.message.isObject() && m.message["sType"].asString({}) == "lookup") {
            auto it = hosted.find(m.message["sProtocol"].asString({}));
            if (it != hosted.end()) {
                auto wanted = m.message["sHostname"].asString({});
                if (wanted.empty() || wanted == it->second) {
                    send(m.sender, Json::object({{"sType", "lookup response"}, {"sHostname", it->second}, {"sProtocol", it->first}}),
                         "dns");
                }
            }
            if (protocol != "dns") continue;
        }
        if (!protocol.empty() && m.protocol != protocol) continue;
        return m;
    }
}

void host(const std::string &protocol, const std::string &hostname) {
    hosted[protocol] = hostname;
}

void unhost(const std::string &protocol) {
    hosted.erase(protocol);
}

std::vector<int> lookup(const std::string &protocol, const std::string &hostname, double timeout) {
    std::vector<int> out;
    auto request = Json::object({{"sType", "lookup"}, {"sProtocol", protocol}});
    if (!hostname.empty()) request.set("sHostname", Json(hostname));
    broadcast(request, "dns");
    double deadline = now() + timeout;
    while (true) {
        auto m = receive("dns", std::max(0.0, deadline - now()));
        if (!m) break;
        if (!m->message.isObject() || m->message["sType"].asString({}) != "lookup response") continue;
        if (m->message["sProtocol"].asString({}) != protocol) continue;
        if (!hostname.empty() && m->message["sHostname"].asString({}) != hostname) continue;
        if (std::find(out.begin(), out.end(), m->sender) == out.end()) out.push_back(m->sender);
        if (!hostname.empty()) break;
    }
    return out;
}

} // namespace rednet

// ---- gps --------------------------------------------------------------------------------------------------------

namespace gps {
namespace {

constexpr int CHANNEL_GPS = 65534;

struct Vec {
    double x, y, z;
    Vec operator+(const Vec &o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec operator-(const Vec &o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec operator*(double k) const { return {x * k, y * k, z * k}; }
    double dot(const Vec &o) const { return x * o.x + y * o.y + z * o.z; }
    Vec cross(const Vec &o) const { return {y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x}; }
    double length() const { return std::sqrt(dot(*this)); }
    Vec normalize() const { return *this * (1 / length()); }
    Vec round() const { return {std::floor(x + 0.5), std::floor(y + 0.5), std::floor(z + 0.5)}; }
    bool operator==(const Vec &o) const { return x == o.x && y == o.y && z == o.z; }
};

struct Fix {
    Vec pos;
    double distance;
};

// Same trilateration as CC's gps.lua: one or two candidate points.
std::vector<Vec> trilaterate(const Fix &a, const Fix &b, const Fix &c) {
    Vec a2b = b.pos - a.pos, a2c = c.pos - a.pos;
    if (std::abs(a2b.normalize().dot(a2c.normalize())) > 0.999) return {};
    double d = a2b.length();
    Vec ex = a2b.normalize();
    double i = ex.dot(a2c);
    Vec ey = (a2c - ex * i).normalize();
    double j = ey.dot(a2c);
    Vec ez = ex.cross(ey);
    double r1 = a.distance, r2 = b.distance, r3 = c.distance;
    double x = (r1 * r1 - r2 * r2 + d * d) / (2 * d);
    double y = (r1 * r1 - r3 * r3 - x * x + (x - i) * (x - i) + j * j) / (2 * j);
    Vec result = a.pos + ex * x + ey * y;
    double zSquared = r1 * r1 - x * x - y * y;
    if (zSquared > 0) {
        double z = std::sqrt(zSquared);
        return {result + ez * z, result - ez * z};
    }
    return {result};
}

std::optional<Vec> narrow(const std::vector<Vec> &candidates, const Fix &fix) {
    if (candidates.size() == 1) return candidates[0];
    double d1 = std::abs((candidates[0] - fix.pos).length() - fix.distance);
    double d2 = std::abs((candidates[1] - fix.pos).length() - fix.distance);
    if (std::abs(d1 - d2) < 0.01) return std::nullopt;
    return d1 < d2 ? candidates[0].round() : candidates[1].round();
}

} // namespace

std::optional<Position> locate(double timeout) {
    std::optional<Modem> modem;
    for (auto &m : Modem::all()) {
        if (m.isWireless()) {
            modem = m;
            break;
        }
    }
    if (!modem) return std::nullopt;
    int self = idChannel(computerId());
    bool wasOpen = modem->isOpen(self);
    if (!wasOpen) modem->open(self);
    modem->transmit(CHANNEL_GPS, self, Json("PING"));

    std::vector<Fix> fixes;
    std::optional<Vec> result;
    double deadline = now() + timeout;
    while (!result) {
        auto m = receiveModem(std::max(0.0, deadline - now()));
        if (!m) break;
        if (m->side != modem->name() || m->channel != self || m->replyChannel != CHANNEL_GPS || m->distance <= 0) continue;
        auto &p = m->message;
        if (!p.isArray() || p.size() != 3 || !p[0].isNumber() || !p[1].isNumber() || !p[2].isNumber()) continue;
        Fix fix{{p[0].asNumber(), p[1].asNumber(), p[2].asNumber()}, m->distance};
        if (fix.distance == 0) {
            result = fix.pos;
            break;
        }
        fixes.push_back(fix);
        if (fixes.size() >= 3) {
            auto n = fixes.size();
            auto candidates = trilaterate(fixes[0], fixes[1], fixes[n - 1]);
            if (candidates.empty()) continue;
            if (candidates.size() == 1) {
                result = candidates[0].round();
            } else if (n >= 4) {
                result = narrow(candidates, fixes[n - 2]);
            }
        }
    }
    if (!wasOpen) modem->close(self);
    if (!result) return std::nullopt;
    return Position{result->x, result->y, result->z};
}

} // namespace gps

// ---- Monitor ----------------------------------------------------------------------------------------------------

std::optional<Monitor> Monitor::find() {
    return findType<Monitor>("monitor");
}

void Monitor::write(std::string_view text) const {
    call("write", Json::array({Json(text)}));
}

void Monitor::setCursorPos(int x, int y) const {
    call("setCursorPos", Json::array({x, y}));
}

std::pair<int, int> Monitor::cursorPos() const {
    return pairOf(call("getCursorPos"));
}

std::pair<int, int> Monitor::size() const {
    return pairOf(call("getSize"));
}

void Monitor::clear() const {
    call("clear");
}

void Monitor::clearLine() const {
    call("clearLine");
}

void Monitor::scroll(int lines) const {
    call("scroll", Json::array({lines}));
}

void Monitor::setTextColour(int colour) const {
    call("setTextColour", Json::array({colour}));
}

void Monitor::setBackgroundColour(int colour) const {
    call("setBackgroundColour", Json::array({colour}));
}

void Monitor::setTextScale(double scale) const {
    call("setTextScale", Json::array({scale}));
}

void Monitor::setCursorBlink(bool blink) const {
    call("setCursorBlink", Json::array({blink}));
}

bool Monitor::isColour() const {
    return first(call("isColour")).asBool();
}

void Monitor::blit(std::string_view text, std::string_view fg, std::string_view bg) const {
    call("blit", Json::array({Json(text), Json(fg), Json(bg)}));
}

void Monitor::setPaletteColour(int colour, uint32_t rgb) const {
    call("setPaletteColour", Json::array({colour, static_cast<double>(rgb & 0xFFFFFF)}));
}

// ---- Speaker ----------------------------------------------------------------------------------------------------

std::optional<Speaker> Speaker::find() {
    return findType<Speaker>("speaker");
}

bool Speaker::playNote(std::string_view instrument, double volume, double pitch) const {
    return first(call("playNote", Json::array({Json(instrument), volume, pitch}))).asBool();
}

bool Speaker::playSound(std::string_view name, double volume, double pitch) const {
    return first(call("playSound", Json::array({Json(name), volume, pitch}))).asBool();
}

bool Speaker::playAudio(const std::vector<int8_t> &samples, double volume) const {
    Json::Array data;
    data.reserve(samples.size());
    for (auto s : samples) data.emplace_back(static_cast<int>(s));
    return first(call("playAudio", Json::array({Json::array(std::move(data)), volume}))).asBool();
}

void Speaker::stop() const {
    call("stop");
}

// ---- Printer ----------------------------------------------------------------------------------------------------

std::optional<Printer> Printer::find() {
    return findType<Printer>("printer");
}

bool Printer::newPage() const {
    return first(call("newPage")).asBool();
}

bool Printer::endPage() const {
    return first(call("endPage")).asBool();
}

void Printer::write(std::string_view text) const {
    call("write", Json::array({Json(text)}));
}

void Printer::setCursorPos(int x, int y) const {
    call("setCursorPos", Json::array({x, y}));
}

std::pair<int, int> Printer::pageSize() const {
    return pairOf(call("getPageSize"));
}

void Printer::setPageTitle(std::string_view title) const {
    call("setPageTitle", Json::array({Json(title)}));
}

int Printer::inkLevel() const {
    return first(call("getInkLevel")).asInt();
}

int Printer::paperLevel() const {
    return first(call("getPaperLevel")).asInt();
}

// ---- Drive ------------------------------------------------------------------------------------------------------

std::optional<Drive> Drive::find() {
    return findType<Drive>("drive");
}

bool Drive::isDiskPresent() const {
    return first(call("isDiskPresent")).asBool();
}

std::string Drive::label() const {
    return first(call("getDiskLabel")).asString({});
}

void Drive::setLabel(std::string_view label) const {
    call("setDiskLabel", label.empty() ? Json::array() : Json::array({Json(label)}));
}

bool Drive::hasData() const {
    return first(call("hasData")).asBool();
}

std::string Drive::mountPath() const {
    auto path = first(call("getMountPath")).asString({});
    return path.empty() || path[0] == '/' ? path : "/" + path;
}

int Drive::diskId() const {
    return first(call("getDiskID")).asInt(-1);
}

bool Drive::hasAudio() const {
    return first(call("hasAudio")).asBool();
}

std::string Drive::audioTitle() const {
    return first(call("getAudioTitle")).asString({});
}

void Drive::playAudio() const {
    call("playAudio");
}

void Drive::stopAudio() const {
    call("stopAudio");
}

void Drive::eject() const {
    call("ejectDisk");
}

// ---- Inventory, energy and fluids -------------------------------------------------------------------------------

std::optional<Inventory> Inventory::find() {
    return findType<Inventory>("inventory");
}

std::vector<Inventory> Inventory::all() {
    return allOfType<Inventory>("inventory");
}

int Inventory::size() const {
    return first(call("size")).asInt();
}

std::vector<Inventory::Item> Inventory::list() const {
    std::vector<Item> out;
    auto items = first(call("list"));
    // A Lua table {[slot] = item}: an array when the slots from 1 are all filled, an object otherwise.
    if (items.isArray()) {
        for (size_t i = 0; i < items.size(); i++) {
            if (items[i].isObject()) out.push_back({static_cast<int>(i + 1), items[i]["name"].asString({}), items[i]["count"].asInt()});
        }
    } else if (items.isObject()) {
        for (auto &[slot, item] : items.members()) out.push_back({std::atoi(slot.c_str()), item["name"].asString({}), item["count"].asInt()});
    }
    std::sort(out.begin(), out.end(), [](const Item &a, const Item &b) { return a.slot < b.slot; });
    return out;
}

Json Inventory::itemDetail(int slot) const {
    return first(call("getItemDetail", Json::array({slot})));
}

int Inventory::itemLimit(int slot) const {
    return first(call("getItemLimit", Json::array({slot}))).asInt();
}

namespace {
Json moveArgs(const std::string &other, int slot, int limit, int toSlot) {
    auto args = Json::array({Json(other), slot});
    if (limit >= 0 || toSlot >= 0) args.push(limit >= 0 ? Json(limit) : Json());
    if (toSlot >= 0) args.push(toSlot);
    return args;
}
} // namespace

int Inventory::pushItems(const std::string &to, int fromSlot, int limit, int toSlot) const {
    return first(call("pushItems", moveArgs(to, fromSlot, limit, toSlot))).asInt();
}

int Inventory::pullItems(const std::string &from, int fromSlot, int limit, int toSlot) const {
    return first(call("pullItems", moveArgs(from, fromSlot, limit, toSlot))).asInt();
}

std::optional<EnergyStorage> EnergyStorage::find() {
    return findType<EnergyStorage>("energy_storage");
}

long long EnergyStorage::energy() const {
    return static_cast<long long>(first(call("getEnergy")).asNumber());
}

long long EnergyStorage::capacity() const {
    return static_cast<long long>(first(call("getEnergyCapacity")).asNumber());
}

std::optional<FluidStorage> FluidStorage::find() {
    return findType<FluidStorage>("fluid_storage");
}

std::vector<FluidStorage::Tank> FluidStorage::tanks() const {
    std::vector<Tank> out;
    auto tanks = first(call("tanks"));
    auto add = [&](const Json &t) {
        if (t.isObject()) out.push_back({t["name"].asString({}), static_cast<long long>(t["amount"].asNumber())});
    };
    if (tanks.isArray()) {
        for (auto &t : tanks.items()) add(t);
    } else if (tanks.isObject()) {
        for (auto &[k, t] : tanks.members()) add(t);
    }
    return out;
}

namespace {
Json fluidArgs(const std::string &other, long long limit, std::string_view fluid) {
    auto args = Json::array({Json(other)});
    if (limit >= 0 || !fluid.empty()) args.push(limit >= 0 ? Json(static_cast<double>(limit)) : Json());
    if (!fluid.empty()) args.push(Json(fluid));
    return args;
}
} // namespace

long long FluidStorage::pushFluid(const std::string &to, long long limit, std::string_view fluid) const {
    return static_cast<long long>(first(call("pushFluid", fluidArgs(to, limit, fluid))).asNumber());
}

long long FluidStorage::pullFluid(const std::string &from, long long limit, std::string_view fluid) const {
    return static_cast<long long>(first(call("pullFluid", fluidArgs(from, limit, fluid))).asNumber());
}

} // namespace cc
