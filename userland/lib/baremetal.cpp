// libbaremetal: the C++ API of <baremetal.hpp> plus the C helpers declared in <baremetal.h>.
#include "baremetal.hpp"

#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>

extern char **environ;

namespace cc {

// ---- JSON -------------------------------------------------------------------------------------------------------

namespace {
const Json NULL_JSON;
const std::string EMPTY_STRING;
const Json::Array EMPTY_ARRAY;
const Json::Object EMPTY_OBJECT;

class Parser {
public:
    explicit Parser(std::string_view text) : p_(text.data()), end_(text.data() + text.size()) {}

    std::optional<Json> document() {
        auto v = value(0);
        ws();
        if (!v || p_ != end_) return std::nullopt;
        return v;
    }

private:
    const char *p_;
    const char *end_;

    void ws() {
        while (p_ < end_ && (*p_ == ' ' || *p_ == '\t' || *p_ == '\n' || *p_ == '\r')) p_++;
    }

    bool literal(std::string_view word) {
        if (static_cast<size_t>(end_ - p_) < word.size() || std::string_view(p_, word.size()) != word) return false;
        p_ += word.size();
        return true;
    }

    static void utf8(std::string &out, unsigned cp) {
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }

    unsigned hex4() {
        unsigned cp = 0;
        for (int i = 0; i < 4 && p_ < end_; i++, p_++) {
            char c = *p_;
            cp <<= 4;
            if (c >= '0' && c <= '9') cp |= c - '0';
            else if (c >= 'a' && c <= 'f') cp |= c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') cp |= c - 'A' + 10;
        }
        return cp;
    }

    std::optional<std::string> string() {
        std::string out;
        p_++; // opening quote
        while (p_ < end_ && *p_ != '"') {
            char c = *p_++;
            if (c != '\\') {
                out += c;
                continue;
            }
            if (p_ >= end_) return std::nullopt;
            c = *p_++;
            switch (c) {
                case 'n': out += '\n'; break;
                case 't': out += '\t'; break;
                case 'r': out += '\r'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'u': {
                    unsigned cp = hex4();
                    if (cp >= 0xD800 && cp < 0xDC00 && end_ - p_ >= 6 && p_[0] == '\\' && p_[1] == 'u') {
                        p_ += 2;
                        unsigned low = hex4();
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                    }
                    utf8(out, cp);
                    break;
                }
                default: out += c; break;
            }
        }
        if (p_ >= end_) return std::nullopt;
        p_++;
        return out;
    }

    std::optional<Json> value(int depth) {
        if (depth > 128) return std::nullopt;
        ws();
        if (p_ >= end_) return std::nullopt;
        switch (*p_) {
            case '{': {
                p_++;
                auto obj = Json::object();
                ws();
                if (p_ < end_ && *p_ == '}') {
                    p_++;
                    return obj;
                }
                for (;;) {
                    ws();
                    if (p_ >= end_ || *p_ != '"') return std::nullopt;
                    auto key = string();
                    if (!key) return std::nullopt;
                    ws();
                    if (p_ >= end_ || *p_++ != ':') return std::nullopt;
                    auto v = value(depth + 1);
                    if (!v) return std::nullopt;
                    obj.set(std::move(*key), std::move(*v));
                    ws();
                    if (p_ < end_ && *p_ == ',') {
                        p_++;
                        continue;
                    }
                    if (p_ < end_ && *p_ == '}') {
                        p_++;
                        return obj;
                    }
                    return std::nullopt;
                }
            }
            case '[': {
                p_++;
                auto arr = Json::array();
                ws();
                if (p_ < end_ && *p_ == ']') {
                    p_++;
                    return arr;
                }
                for (;;) {
                    auto v = value(depth + 1);
                    if (!v) return std::nullopt;
                    arr.push(std::move(*v));
                    ws();
                    if (p_ < end_ && *p_ == ',') {
                        p_++;
                        continue;
                    }
                    if (p_ < end_ && *p_ == ']') {
                        p_++;
                        return arr;
                    }
                    return std::nullopt;
                }
            }
            case '"': {
                auto s = string();
                if (!s) return std::nullopt;
                return Json(std::move(*s));
            }
            case 't':
                if (literal("true")) return Json(true);
                return std::nullopt;
            case 'f':
                if (literal("false")) return Json(false);
                return std::nullopt;
            case 'n':
                if (literal("null")) return Json();
                return std::nullopt;
            default: {
                std::string number(p_, static_cast<size_t>(std::min<ptrdiff_t>(end_ - p_, 64)));
                char *stop = nullptr;
                double d = std::strtod(number.c_str(), &stop);
                if (stop == number.c_str()) return std::nullopt;
                p_ += stop - number.c_str();
                return Json(d);
            }
        }
    }
};
} // namespace

Json Json::array(std::initializer_list<Json> items) {
    return array(Array(items));
}

Json Json::array(Array items) {
    Json j;
    j.type_ = Type::Array;
    j.array_ = std::make_shared<Array>(std::move(items));
    return j;
}

Json Json::object(std::initializer_list<std::pair<std::string, Json>> members) {
    Json j;
    j.type_ = Type::Object;
    j.object_ = std::make_shared<Object>(members);
    return j;
}

std::optional<Json> Json::parse(std::string_view text) {
    return Parser(text).document();
}

const std::string &Json::asString() const {
    return type_ == Type::String ? string_ : EMPTY_STRING;
}

size_t Json::size() const {
    if (type_ == Type::Array) return array_->size();
    if (type_ == Type::Object) return object_->size();
    return 0;
}

const Json &Json::operator[](size_t index) const {
    if (type_ == Type::Array && index < array_->size()) return (*array_)[index];
    if (type_ == Type::Object && index < object_->size()) return (*object_)[index].second;
    return NULL_JSON;
}

const Json *Json::find(std::string_view key) const {
    if (type_ != Type::Object) return nullptr;
    for (auto &m : *object_) {
        if (m.first == key) return &m.second;
    }
    return nullptr;
}

const Json &Json::operator[](std::string_view key) const {
    auto *v = find(key);
    return v ? *v : NULL_JSON;
}

const Json::Array &Json::items() const {
    return type_ == Type::Array ? *array_ : EMPTY_ARRAY;
}

const Json::Object &Json::members() const {
    return type_ == Type::Object ? *object_ : EMPTY_OBJECT;
}

Json &Json::push(Json value) {
    if (type_ != Type::Array) *this = array();
    if (array_.use_count() > 1) array_ = std::make_shared<Array>(*array_);
    array_->push_back(std::move(value));
    return *this;
}

Json &Json::set(std::string key, Json value) {
    if (type_ != Type::Object) *this = object();
    if (object_.use_count() > 1) object_ = std::make_shared<Object>(*object_);
    for (auto &m : *object_) {
        if (m.first == key) {
            m.second = std::move(value);
            return *this;
        }
    }
    object_->emplace_back(std::move(key), std::move(value));
    return *this;
}

std::string quote(std::string_view s) {
    static const char hex[] = "0123456789abcdef";
    std::string out;
    out.reserve(s.size() + 2);
    out += '"';
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\t': out += "\\t"; break;
            case '\r': out += "\\r"; break;
            default:
                if (c < 0x20) {
                    out += "\\u00";
                    out += hex[c >> 4];
                    out += hex[c & 15];
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    out += '"';
    return out;
}

static void formatNumber(std::string &out, double d) {
    char buf[40];
    if (std::isfinite(d) && d == std::floor(d) && std::fabs(d) < 1e15) {
        std::snprintf(buf, sizeof(buf), "%lld", static_cast<long long>(d));
    } else if (std::isfinite(d)) {
        std::snprintf(buf, sizeof(buf), "%.17g", d);
    } else {
        std::snprintf(buf, sizeof(buf), "null");
    }
    out += buf;
}

void Json::dumpTo(std::string &out) const {
    switch (type_) {
        case Type::Null: out += "null"; break;
        case Type::Bool: out += number_ != 0 ? "true" : "false"; break;
        case Type::Number: formatNumber(out, number_); break;
        case Type::String: out += quote(string_); break;
        case Type::Array: {
            out += '[';
            bool first = true;
            for (auto &v : *array_) {
                if (!first) out += ',';
                first = false;
                v.dumpTo(out);
            }
            out += ']';
            break;
        }
        case Type::Object: {
            out += '{';
            bool first = true;
            for (auto &m : *object_) {
                if (!first) out += ',';
                first = false;
                out += quote(m.first);
                out += ':';
                m.second.dumpTo(out);
            }
            out += '}';
            break;
        }
    }
}

std::string Json::dump() const {
    std::string out;
    dumpTo(out);
    return out;
}

std::string Json::toString() const {
    if (type_ == Type::String) return string_;
    if (type_ == Type::Number) {
        std::string out;
        formatNumber(out, number_);
        return out;
    }
    return dump();
}

// ---- Kernel results ---------------------------------------------------------------------------------------------

std::string take(int n) {
    if (n < 0) n = -n;
    std::string out(static_cast<size_t>(n), '\0');
    int got = n > 0 ? cc_take(out.data(), n) : 0;
    out.resize(got < 0 ? 0 : static_cast<size_t>(got));
    return out;
}

static Result result(int n) {
    Result r;
    auto text = take(n);
    if (n < 0) {
        r.error = text;
        return r;
    }
    auto parsed = Json::parse(text);
    r.ok = true;
    r.value = parsed ? *parsed : Json::array();
    return r;
}

// ---- Processes --------------------------------------------------------------------------------------------------

static std::string pack(const std::vector<std::string> &items) {
    std::string out;
    for (auto &s : items) {
        out += s;
        out += '\0';
    }
    return out;
}

// "/home/user/../x/./y" -> "/home/x/y" (the file system has no symbolic links, so this is exact).
static std::string normalizePath(const std::string &path) {
    std::vector<std::string> parts;
    size_t start = 0;
    while (start <= path.size()) {
        auto end = path.find('/', start);
        if (end == std::string::npos) end = path.size();
        auto part = path.substr(start, end - start);
        if (part == "..") {
            if (!parts.empty()) parts.pop_back();
        } else if (!part.empty() && part != ".") {
            parts.push_back(part);
        }
        start = end + 1;
    }
    std::string out;
    for (auto &p : parts) out += "/" + p;
    return out.empty() ? "/" : out;
}

std::string cwd() {
    char buf[4096];
    return normalizePath(getcwd(buf, sizeof(buf)) ? std::string(buf) : std::string("/"));
}

int spawn(const std::vector<std::string> &argv, const SpawnOptions &options) {
    if (argv.empty()) return -EINVAL;
    std::vector<std::string> env;
    if (options.env) {
        env = *options.env;
    } else {
        for (char **e = environ; e && *e; e++) env.emplace_back(*e);
    }
    // The child starts in our working directory: replace PWD.
    std::vector<std::string> childEnv;
    for (auto &e : env) {
        if (e.rfind("PWD=", 0) != 0) childEnv.push_back(e);
    }
    childEnv.push_back("PWD=" + cwd());
    auto argvBuf = pack(argv);
    auto envBuf = pack(childEnv);
    int32_t pid = 0;
    const std::string &program = options.program ? *options.program : argv[0];
    int r = __cc_spawn(program.data(), static_cast<int32_t>(program.size()), argvBuf.data(), static_cast<int32_t>(argvBuf.size()),
                       envBuf.data(), static_cast<int32_t>(envBuf.size()), options.in, options.out, options.err, options.flags, &pid);
    return r != 0 ? -r : pid;
}

int wait(int pid, int *status, bool nohang) {
    int32_t st = 0;
    int r = cc_wait(pid, nohang ? CC_WNOHANG : 0, &st);
    if (status) *status = st;
    return r;
}

int run(const std::vector<std::string> &argv, const SpawnOptions &options) {
    int pid = spawn(argv, options);
    if (pid < 0) return pid;
    int status = 0;
    if (wait(pid, &status) < 0) return -ECHILD;
    return status;
}

bool chdir(const std::string &path) {
    // Resolve "." and ".." ourselves: the C library only appends the path to the current directory.
    auto target = normalizePath(!path.empty() && path[0] == '/' ? path : cwd() + "/" + path);
    // the kernel checks the permission to enter (x), the C library only that it is a directory
    if (int r = __cc_setcwd(target.data(), static_cast<int32_t>(target.size())); r != 0) {
        errno = r;
        return false;
    }
    if (::chdir(target.c_str()) != 0) return false;
    auto dir = cwd();
    setenv("PWD", dir.c_str(), 1);
    return true;
}

void sleep(double seconds) {
    cc_sleep_ms(static_cast<int32_t>(seconds * 1000));
}

// ---- Terminal ---------------------------------------------------------------------------------------------------

namespace tty {
Size size() {
    int32_t cols = 80, rows = 24;
    cc_tty_size(&cols, &rows);
    return {cols, rows};
}

int mode() {
    return cc_tty_mode(-1);
}

int setMode(int flags) {
    return cc_tty_mode(flags);
}
} // namespace tty

// ---- Events -----------------------------------------------------------------------------------------------------

std::optional<Event> pullEvent(std::string_view filter, int timeoutMs) {
    int n = __cc_event_wait(timeoutMs, filter.data(), static_cast<int32_t>(filter.size()));
    if (n <= 0) return std::nullopt;
    auto parsed = Json::parse(take(n));
    if (!parsed || !parsed->isArray() || parsed->size() == 0) return std::nullopt;
    Event e;
    e.name = (*parsed)[0].asString();
    Json::Array rest(parsed->items().begin() + 1, parsed->items().end());
    e.args = Json::array(std::move(rest));
    return e;
}

Wakeup waitAny(const std::vector<int> &fds, int timeoutMs) {
    std::vector<int32_t> list(fds.begin(), fds.end());
    while (true) {
        int32_t ready = -1;
        int r = cc_event_wait_fds(list.data(), static_cast<int32_t>(list.size()), timeoutMs, &ready);
        if (r == 2) return {std::nullopt, ready};
        if (r != 1) return {};
        if (auto e = pullEvent("", 0)) return {e, -1};
        // the event was taken in between (filtered elsewhere): wait again
    }
}

bool queueEvent(std::string_view name, const Json &args) {
    auto event = Json::array({Json(name)});
    for (auto &a : args.items()) event.push(a);
    auto text = event.dump();
    return __cc_event_queue(text.data(), static_cast<int32_t>(text.size())) == 0;
}

int startTimer(double seconds) {
    return cc_timer_start(static_cast<int32_t>(seconds * 1000));
}

void cancelTimer(int id) {
    cc_timer_cancel(id);
}

int setProcessFlags(int flags) {
    return cc_proc_flags(flags);
}

// ---- Graphics ---------------------------------------------------------------------------------------------------

namespace gfx {
bool mode(int width, int height, int bpp, int flags) {
    return cc_gfx_mode(width, height, bpp, flags) == 0;
}

void textMode() {
    cc_gfx_mode(0, 0, 0, 0);
}

Info info() {
    int32_t v[4] = {0, 0, 0, 0};
    cc_gfx_info(v);
    return {v[0], v[1], v[2], v[3] != 0};
}

bool target(std::string_view peripheral) {
    return __cc_gfx_target(peripheral.data(), static_cast<int32_t>(peripheral.size())) == 0;
}

void palette(int start, const std::vector<uint32_t> &rgb) {
    cc_gfx_palette(start, static_cast<int32_t>(rgb.size()), rgb.data());
}

std::vector<uint32_t> palette(int start, int count) {
    std::vector<uint32_t> out(static_cast<size_t>(count > 0 ? count : 0));
    if (!out.empty()) cc_gfx_get_palette(start, count, out.data());
    return out;
}

int text(int x, int y, std::string_view utf8, int fg, int bg, int scale) {
    return __cc_gfx_text(x, y, utf8.data(), static_cast<int32_t>(utf8.size()), fg, bg, scale);
}
} // namespace gfx

// ---- Peripherals ------------------------------------------------------------------------------------------------

std::vector<Peripheral::Info> Peripheral::list() {
    std::vector<Info> out;
    auto parsed = Json::parse(take(__cc_periph_list()));
    if (!parsed) return out;
    for (auto &p : parsed->items()) {
        Info info;
        info.name = p["name"].asString();
        for (auto &t : p["types"].items()) info.types.push_back(t.asString());
        out.push_back(std::move(info));
    }
    return out;
}

std::optional<Peripheral> Peripheral::find(std::string_view type) {
    for (auto &p : list()) {
        for (auto &t : p.types) {
            if (t == type) return Peripheral(p.name);
        }
    }
    return std::nullopt;
}

bool Peripheral::present() const {
    for (auto &p : list()) {
        if (p.name == name_) return true;
    }
    return false;
}

std::vector<std::string> Peripheral::types() const {
    std::vector<std::string> out;
    int n = __cc_periph_types(name_.data(), static_cast<int32_t>(name_.size()));
    auto text = take(n);
    if (n < 0) return out;
    if (auto parsed = Json::parse(text)) {
        for (auto &t : parsed->items()) out.push_back(t.asString());
    }
    return out;
}

std::vector<std::string> Peripheral::methods() const {
    std::vector<std::string> out;
    int n = __cc_periph_methods(name_.data(), static_cast<int32_t>(name_.size()));
    auto text = take(n);
    if (n < 0) return out;
    if (auto parsed = Json::parse(text)) {
        for (auto &m : parsed->items()) out.push_back(m.asString());
    }
    return out;
}

Result Peripheral::call(std::string_view method, const Json &args) const {
    auto a = args.isArray() ? args.dump() : Json::array({args}).dump();
    return result(__cc_periph_call(name_.data(), static_cast<int32_t>(name_.size()), method.data(),
                                   static_cast<int32_t>(method.size()), a.data(), static_cast<int32_t>(a.size())));
}

// ---- Objects and addon APIs ------------------------------------------------------------------------------------

std::optional<Object> Object::from(const Json &value) {
    if (!value.isObject() || !value["__object"].isNumber()) return std::nullopt;
    std::vector<std::string> methods;
    for (auto &m : value["methods"].items()) methods.push_back(m.asString());
    return Object(value["__object"].asInt(), std::move(methods));
}

bool Object::has(std::string_view method) const {
    for (auto &m : methods_) {
        if (m == method) return true;
    }
    return false;
}

Result Object::call(std::string_view method, const Json &args) const {
    auto a = args.isArray() ? args.dump() : Json::array({args}).dump();
    return result(__cc_obj_call(id_, method.data(), static_cast<int32_t>(method.size()), a.data(), static_cast<int32_t>(a.size())));
}

std::optional<std::string> Object::callBytes(std::string_view method, const Json &args, std::string *error) const {
    auto a = args.isArray() ? args.dump() : Json::array({args}).dump();
    int n = __cc_obj_call_raw(id_, method.data(), static_cast<int32_t>(method.size()), a.data(), static_cast<int32_t>(a.size()));
    if (n < 0) {
        auto message = take(n);
        if (error) *error = message;
        return std::nullopt;
    }
    if (n == 0) return std::nullopt;
    return take(n - 1);
}

Json Object::json() const {
    return Json::object({{"__object", id_}});
}

namespace http {
namespace {
std::optional<Object> api(std::string *error) {
    auto h = cc::api::get("http");
    if (!h && error) *error = "HTTP is disabled on this server";
    return h;
}

Json headerTable(const std::vector<std::pair<std::string, std::string>> &headers) {
    auto out = Json::object();
    for (auto &[k, v] : headers) out.set(k, Json(v));
    return out;
}

// A response handle from an http_success / http_failure event: status, headers, body; then closed.
void collect(const Json &value, Response &r) {
    auto handle = Object::from(value);
    if (!handle) return;
    auto code = handle->call("getResponseCode");
    if (code && code.value.size() > 0) r.status = code.value[0].asInt();
    auto headers = handle->call("getResponseHeaders");
    if (headers && headers.value.size() > 0) r.headers = headers.value[0];
    if (auto body = handle->callBytes("readAll")) r.body = std::move(*body);
    handle->call("close");
    handle->release();
}
} // namespace

bool enabled() {
    return cc::api::get("http").has_value();
}

Response request(const Request &req) {
    Response r;
    auto h = api(&r.error);
    if (!h) return r;
    wantEvents();
    auto options = Json::object({{"url", req.url}, {"binary", true}, {"redirect", req.redirect}});
    if (!req.method.empty()) options.set("method", Json(req.method));
    if (req.body) options.set("body", Json(*req.body));
    if (!req.headers.empty()) options.set("headers", headerTable(req.headers));
    if (req.timeout > 0) options.set("timeout", Json(req.timeout));
    auto started = h->call("request", Json::array({options}));
    if (!started) {
        r.error = started.error;
        return r;
    }
    if (started.value.size() > 0 && started.value[0].isBool() && !started.value[0].asBool()) {
        r.error = started.value.size() > 1 ? started.value[1].asString("") : "request failed";
        return r;
    }
    while (auto e = pullEvent()) {
        if ((e->name != "http_success" && e->name != "http_failure") || e->args.size() == 0 || e->args[0].asString("") != req.url) continue;
        if (e->name == "http_success") {
            r.ok = true;
            collect(e->args[1], r);
        } else {
            r.error = e->args.size() > 1 ? e->args[1].asString("request failed") : "request failed";
            if (e->args.size() > 2) collect(e->args[2], r); // an error status still has a response
        }
        break;
    }
    return r;
}

Response get(const std::string &url) {
    Request req;
    req.url = url;
    return request(req);
}

Response post(const std::string &url, const std::string &body, const std::string &contentType) {
    Request req;
    req.url = url;
    req.method = "POST";
    req.body = body;
    req.headers.emplace_back("Content-Type", contentType);
    return request(req);
}

bool checkURL(const std::string &url, std::string *error) {
    auto h = api(error);
    if (!h) return false;
    wantEvents();
    auto started = h->call("checkURL", Json::array({url}));
    if (!started || (started.value.size() > 0 && !started.value[0].asBool())) {
        if (error) *error = !started ? started.error : started.value.size() > 1 ? started.value[1].asString("") : "invalid URL";
        return false;
    }
    while (auto e = pullEvent("http_check")) {
        if (e->args.size() == 0 || e->args[0].asString("") != url) continue;
        bool ok = e->args.size() > 1 && e->args[1].asBool();
        if (!ok && error) *error = e->args.size() > 2 ? e->args[2].asString("") : "not allowed";
        return ok;
    }
    return false;
}

bool WebSocket::send(std::string_view message, bool binary) {
    return static_cast<bool>(handle_.call("send", Json::array({Json(message), Json(binary)})));
}

std::optional<std::string> WebSocket::receive(double timeout) {
    auto args = timeout >= 0 ? Json::array({Json(timeout)}) : Json::array();
    return handle_.callBytes("receive", args);
}

void WebSocket::close() {
    handle_.call("close");
    handle_.release();
}

std::optional<WebSocket> websocket(const std::string &url, std::string *error, const std::vector<std::pair<std::string, std::string>> &headers) {
    auto h = api(error);
    if (!h) return std::nullopt;
    wantEvents();
    auto args = headers.empty() ? Json::array({url}) : Json::array({Json(url), headerTable(headers)});
    auto started = h->call("websocket", args);
    if (!started || (started.value.size() > 0 && started.value[0].isBool() && !started.value[0].asBool())) {
        if (error) *error = !started ? started.error : started.value.size() > 1 ? started.value[1].asString("") : "websocket failed";
        return std::nullopt;
    }
    while (auto e = pullEvent()) {
        if ((e->name != "websocket_success" && e->name != "websocket_failure") || e->args.size() == 0 || e->args[0].asString("") != url) continue;
        if (e->name == "websocket_failure") {
            if (error) *error = e->args.size() > 1 ? e->args[1].asString("") : "websocket failed";
            return std::nullopt;
        }
        auto handle = Object::from(e->args[1]);
        if (!handle) return std::nullopt;
        return WebSocket(*handle);
    }
    return std::nullopt;
}
} // namespace http

namespace api {
std::vector<std::string> list() {
    std::vector<std::string> out;
    if (auto parsed = Json::parse(take(__cc_api_list()))) {
        for (auto &n : parsed->items()) out.push_back(n.asString());
    }
    return out;
}

std::optional<Object> get(std::string_view name) {
    int n = __cc_api_get(name.data(), static_cast<int32_t>(name.size()));
    auto text = take(n);
    if (n < 0) return std::nullopt;
    auto parsed = Json::parse(text);
    return parsed ? Object::from(*parsed) : std::nullopt;
}
} // namespace api

// ---- Lua and system ---------------------------------------------------------------------------------------------

Result lua(std::string_view code, const Json &args) {
    auto a = args.isArray() ? args.dump() : Json::array({args}).dump();
    return result(__cc_lua_eval(code.data(), static_cast<int32_t>(code.size()), a.data(), static_cast<int32_t>(a.size())));
}

Json systemInfo() {
    auto parsed = Json::parse(take(__cc_sys_info()));
    return parsed ? *parsed : Json::object();
}

bool setLabel(std::string_view label) {
    return __cc_sys_set_label(label.data(), static_cast<int32_t>(label.size())) == 0;
}

int side(std::string_view name) {
    static const char *const sides[] = {"bottom", "top", "back", "front", "right", "left"};
    for (int i = 0; i < 6; i++) {
        if (name == sides[i]) return i;
    }
    return -1;
}

} // namespace cc

// ---- C helpers (<baremetal.h>) -------------------------------------------------------------------------------------

static char *dupString(const std::string &s) {
    auto *out = static_cast<char *>(std::malloc(s.size() + 1));
    if (out) std::memcpy(out, s.c_str(), s.size() + 1);
    return out;
}

static std::vector<std::string> toVector(char *const list[]) {
    std::vector<std::string> out;
    for (int i = 0; list && list[i]; i++) out.emplace_back(list[i]);
    return out;
}

extern "C" {

int cc_spawn(char *const argv[], char *const envp[], int fd_in, int fd_out, int fd_err, int flags) {
    cc::SpawnOptions options;
    if (envp) options.env = toVector(envp);
    options.in = fd_in;
    options.out = fd_out;
    options.err = fd_err;
    options.flags = flags;
    return cc::spawn(toVector(argv), options);
}

int cc_run(char *const argv[]) {
    return cc::run(toVector(argv));
}

int cc_chdir(const char *path) {
    return cc::chdir(path) ? 0 : -1;
}

int cc_side(const char *name) {
    return cc::side(name);
}

char *cc_take_str(int n) {
    return dupString(cc::take(n));
}

char *cc_event_wait(int timeout_ms, const char *filter) {
    int n = __cc_event_wait(timeout_ms, filter, filter ? static_cast<int32_t>(std::strlen(filter)) : 0);
    return n > 0 ? cc_take_str(n) : nullptr;
}

int cc_event_queue(const char *json) {
    return __cc_event_queue(json, static_cast<int32_t>(std::strlen(json)));
}

char *cc_periph_list(void) {
    return cc_take_str(__cc_periph_list());
}

char *cc_periph_methods(const char *name) {
    int n = __cc_periph_methods(name, static_cast<int32_t>(std::strlen(name)));
    auto s = cc::take(n);
    return n < 0 ? nullptr : dupString(s);
}

int cc_periph_call(const char *name, const char *method, const char *args_json, char **result) {
    int n = __cc_periph_call(name, static_cast<int32_t>(std::strlen(name)), method, static_cast<int32_t>(std::strlen(method)),
                             args_json, args_json ? static_cast<int32_t>(std::strlen(args_json)) : 0);
    auto s = cc::take(n);
    if (result) *result = dupString(s);
    return n < 0 ? -1 : 0;
}

int cc_lua(const char *code, const char *args_json, char **result) {
    int n = __cc_lua_eval(code, static_cast<int32_t>(std::strlen(code)), args_json, args_json ? static_cast<int32_t>(std::strlen(args_json)) : 0);
    auto s = cc::take(n);
    if (result) *result = dupString(s);
    return n < 0 ? -1 : 0;
}

char *cc_sys_info(void) {
    return cc_take_str(__cc_sys_info());
}

int cc_set_label(const char *label) {
    return __cc_sys_set_label(label, label ? static_cast<int32_t>(std::strlen(label)) : 0);
}

int cc_gfx_target(const char *name) {
    return __cc_gfx_target(name, name ? static_cast<int32_t>(std::strlen(name)) : 0);
}

int cc_gfx_text(int x, int y, const char *utf8, int fg, int bg, int scale) {
    return __cc_gfx_text(x, y, utf8, static_cast<int32_t>(std::strlen(utf8)), fg, bg, scale);
}

} // extern "C"
