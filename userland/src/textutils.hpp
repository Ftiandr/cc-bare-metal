// Helpers shared by the parts of textutils (sed, awk, file tools).
#pragma once

#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <optional>
#include <regex.h>
#include <string>
#include <vector>

std::string &textutilsProg();

namespace tu {

using Args = std::vector<std::string>;
using Applet = int (*)(Args &);

[[noreturn]] inline void die(const std::string &message) {
    std::fprintf(stderr, "%s: %s\n", textutilsProg().c_str(), message.c_str());
    std::exit(1);
}

inline int warn(const std::string &message) {
    std::fprintf(stderr, "%s: %s\n", textutilsProg().c_str(), message.c_str());
    return 1;
}

inline int warnErrno(const std::string &what) {
    return warn(what + ": " + std::strerror(errno));
}

inline bool isOption(const std::string &a) {
    return a.size() > 1 && a[0] == '-';
}

inline std::optional<std::string> readFile(const std::string &path) {
    FILE *f = path == "-" ? stdin : std::fopen(path.c_str(), "rb");
    if (!f) return std::nullopt;
    std::string out;
    char buf[16384];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    if (f != stdin) std::fclose(f);
    return out;
}

inline bool writeFile(const std::string &path, const std::string &data) {
    FILE *f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = std::fwrite(data.data(), 1, data.size(), f) == data.size();
    return std::fclose(f) == 0 && ok;
}

// POSIX regex with RAII.
class Regex {
public:
    Regex() = default;
    Regex(const std::string &pattern, int flags) { compile(pattern, flags); }
    ~Regex() {
        if (ok_) regfree(&re_);
    }
    Regex(const Regex &) = delete;
    Regex &operator=(const Regex &) = delete;
    bool compile(const std::string &pattern, int flags) {
        if (ok_) regfree(&re_);
        ok_ = regcomp(&re_, pattern.c_str(), flags) == 0;
        return ok_;
    }
    bool ok() const { return ok_; }
    bool search(const char *s, regmatch_t *m, size_t n, int flags = 0) const { return ok_ && regexec(&re_, s, n, m, flags) == 0; }
    bool matches(const std::string &s) const { return ok_ && regexec(&re_, s.c_str(), 0, nullptr, 0) == 0; }

private:
    regex_t re_{};
    bool ok_ = false;
};

} // namespace tu
