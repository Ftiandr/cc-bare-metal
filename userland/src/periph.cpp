// periph - talk to CC peripherals (of any mod) and addon APIs from the command line.
//   periph list | periph methods NAME | periph call NAME METHOD [ARGS...] | periph lua CODE [ARGS...]
//   periph api [NAME [METHOD [ARGS...]]] | periph obj ID METHOD [ARGS...]
// Arguments: numbers, true/false/nil, JSON ({...} [...] "..."), anything else is a string.
#include <baremetal.hpp>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

namespace {

const char *const USAGE = "Usage: periph list | methods NAME | call NAME METHOD [ARGS...] | lua CODE [ARGS...]\n"
                          "       periph api [NAME [METHOD [ARGS...]]]   APIs added by addons\n"
                          "       periph obj ID METHOD [ARGS...]          call an object a method returned (<object ID>)";

cc::Json argument(const std::string &a) {
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

cc::Json arguments(const std::vector<std::string> &args, size_t from) {
    auto out = cc::Json::array();
    for (size_t i = from; i < args.size(); i++) out.push(argument(args[i]));
    return out;
}

// Lua-style display of a value.
void pretty(const cc::Json &v, int indent) {
    switch (v.type()) {
        case cc::Json::Type::Null: std::printf("nil"); break;
        case cc::Json::Type::Bool: std::printf(v.asBool() ? "true" : "false"); break;
        case cc::Json::Type::Number: std::printf("%.14g", v.asNumber()); break;
        case cc::Json::Type::String: std::printf("\"%s\"", v.asString().c_str()); break;
        case cc::Json::Type::Array:
        case cc::Json::Type::Object: {
            if (auto obj = cc::Object::from(v)) {
                std::printf("<object %d:", obj->id());
                for (auto &m : obj->methods()) std::printf(" %s", m.c_str());
                std::printf(">");
                break;
            }
            if (v.size() == 0) {
                std::printf("{}");
                break;
            }
            std::printf("{\n");
            for (size_t i = 0; i < v.size(); i++) {
                std::printf("%*s", indent + 2, "");
                if (v.isObject()) std::printf("%s = ", v.members()[i].first.c_str());
                pretty(v[i], indent + 2);
                std::printf(i + 1 < v.size() ? ",\n" : "\n");
            }
            std::printf("%*s}", indent, "");
            break;
        }
    }
}

int show(const cc::Result &r) {
    if (!r) {
        std::fprintf(stderr, "\x1b[91merror:\x1b[0m %s\n", r.error.c_str());
        return 1;
    }
    for (auto &v : r.value.items()) {
        pretty(v, 0);
        std::putchar('\n');
    }
    return 0;
}

} // namespace

int main(int argc, char **argv) {
    std::vector<std::string> args(argv, argv + argc);
    if (args.size() < 2 || args[1] == "list") {
        auto list = cc::Peripheral::list();
        if (list.empty()) std::puts("No peripherals attached.");
        for (auto &p : list) {
            std::printf("\x1b[93m%-16s\x1b[0m", p.name.c_str());
            for (auto &t : p.types) std::printf(" %s", t.c_str());
            std::putchar('\n');
        }
        if (args.size() < 2) std::printf("\n%s\n", USAGE);
        return 0;
    }
    if (args[1] == "methods" && args.size() > 2) {
        cc::Peripheral p(args[2]);
        if (!p.present()) {
            std::fprintf(stderr, "No such peripheral %s\n", args[2].c_str());
            return 1;
        }
        for (auto &m : p.methods()) std::puts(m.c_str());
        return 0;
    }
    if (args[1] == "call" && args.size() > 3) return show(cc::Peripheral(args[2]).call(args[3], arguments(args, 4)));
    if (args[1] == "lua" && args.size() > 2) return show(cc::lua(args[2], arguments(args, 3)));
    if (args[1] == "api") {
        if (args.size() == 2) {
            auto names = cc::api::list();
            if (names.empty()) std::puts("No addon APIs installed.");
            for (auto &n : names) {
                std::printf("\x1b[93m%-16s\x1b[0m", n.c_str());
                if (auto api = cc::api::get(n)) {
                    for (auto &m : api->methods()) std::printf(" %s", m.c_str());
                }
                std::putchar('\n');
            }
            return 0;
        }
        auto api = cc::api::get(args[2]);
        if (!api) {
            std::fprintf(stderr, "No such API %s\n", args[2].c_str());
            return 1;
        }
        if (args.size() == 3) {
            for (auto &m : api->methods()) std::puts(m.c_str());
            return 0;
        }
        return show(api->call(args[3], arguments(args, 4)));
    }
    if (args[1] == "obj" && args.size() > 3) {
        // Handles live per process: only objects returned to this same command line are reachable, so this is
        // mostly useful from programs. Kept for symmetry and debugging.
        return show(cc::Object(std::atoi(args[2].c_str())).call(args[3], arguments(args, 4)));
    }
    std::fprintf(stderr, "%s\n", USAGE);
    return 1;
}
