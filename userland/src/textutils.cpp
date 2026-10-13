// textutils - the rest of the standard Unix toolbox, as a multi-call binary like coreutils (/bin/<name> are aliases):
//
//   text:    sed awk diff cmp comm nl fold tac paste split expand fmt column strings join
//   data:    od xxd base64 md5sum sha1sum sha256sum cksum
//   files:   tar realpath readlink mktemp truncate dd tree ln chmod sync
//   shell:   xargs which time timeout watch nohup expr factor cal shuf nproc id tty printenv
#include <baremetal.hpp>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <random>
#include <regex.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>

extern char **environ;

namespace {

using Args = std::vector<std::string>;
std::string prog;

[[noreturn]] void die(const std::string &message) {
    std::fprintf(stderr, "%s: %s\n", prog.c_str(), message.c_str());
    std::exit(1);
}

int warn(const std::string &message) {
    std::fprintf(stderr, "%s: %s\n", prog.c_str(), message.c_str());
    return 1;
}

int warnErrno(const std::string &what) {
    return warn(what + ": " + std::strerror(errno));
}

bool isOption(const std::string &a) {
    return a.size() > 1 && a[0] == '-';
}

std::optional<std::string> readFile(const std::string &path) {
    FILE *f = path == "-" ? stdin : std::fopen(path.c_str(), "rb");
    if (!f) return std::nullopt;
    std::string out;
    char buf[16384];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    bool failed = std::ferror(f);
    int err = errno;
    if (f != stdin) std::fclose(f);
    if (failed) { // read error (device or disk gone): the caller reports errno
        errno = err;
        return std::nullopt;
    }
    return out;
}

bool writeFile(const std::string &path, const std::string &data) {
    FILE *f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = std::fwrite(data.data(), 1, data.size(), f) == data.size();
    return std::fclose(f) == 0 && ok;
}

std::vector<std::string> splitLines(const std::string &text) {
    std::vector<std::string> out;
    size_t start = 0;
    while (start < text.size()) {
        auto end = text.find('\n', start);
        if (end == std::string::npos) end = text.size();
        out.push_back(text.substr(start, end - start));
        start = end + 1;
    }
    return out;
}

// All input files (or stdin) as one text.
std::string readInputs(const Args &a, size_t first) {
    if (first >= a.size()) return readFile("-").value_or("");
    std::string all;
    for (size_t i = first; i < a.size(); i++) {
        auto t = readFile(a[i]);
        if (!t) {
            warnErrno(a[i]);
            continue;
        }
        all += *t;
    }
    return all;
}

std::vector<std::string> inputLines(const Args &a, size_t first) {
    return splitLines(readInputs(a, first));
}

bool isDir(const std::string &path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

std::vector<std::string> listDir(const std::string &path) {
    std::vector<std::string> out;
    if (DIR *d = opendir(path.c_str())) {
        while (auto *e = readdir(d)) {
            std::string n = e->d_name;
            if (n != "." && n != "..") out.push_back(n);
        }
        closedir(d);
    }
    std::sort(out.begin(), out.end());
    return out;
}

std::string joinPath(const std::string &dir, const std::string &name) {
    return dir.empty() || dir.back() == '/' ? dir + name : dir + "/" + name;
}

// ---- Small text tools -------------------------------------------------------------------------------------------

int cmdTac(Args &a) {
    auto lines = inputLines(a, 1);
    for (auto it = lines.rbegin(); it != lines.rend(); ++it) std::puts(it->c_str());
    return 0;
}

int cmdNl(Args &a) {
    bool all = false;
    size_t i = 1;
    if (i < a.size() && (a[i] == "-ba" || a[i] == "-b" )) {
        all = true;
        i += a[i] == "-b" ? 2 : 1;
    }
    int n = 0;
    for (auto &line : inputLines(a, i)) {
        if (line.empty() && !all) std::puts("");
        else std::printf("%6d\t%s\n", ++n, line.c_str());
    }
    return 0;
}

int cmdFold(Args &a) {
    size_t width = 80;
    bool spaces = false;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-w" && i + 1 < a.size()) width = std::strtoul(a[++i].c_str(), nullptr, 10);
        else if (a[i] == "-s") spaces = true;
        else if (a[i].rfind("-w", 0) == 0) width = std::strtoul(a[i].c_str() + 2, nullptr, 10);
    }
    if (width == 0) width = 80;
    for (auto line : inputLines(a, i)) {
        while (line.size() > width) {
            size_t cut = width;
            if (spaces) {
                auto sp = line.rfind(' ', width - 1);
                if (sp != std::string::npos && sp > 0) cut = sp + 1;
            }
            std::puts(line.substr(0, cut).c_str());
            line = line.substr(cut);
        }
        std::puts(line.c_str());
    }
    return 0;
}

int cmdFmt(Args &a) {
    size_t width = 75;
    size_t i = 1;
    if (i + 1 < a.size() && a[i] == "-w") {
        width = std::strtoul(a[i + 1].c_str(), nullptr, 10);
        i += 2;
    }
    std::string para;
    auto flush = [&]() {
        std::string line;
        size_t p = 0;
        while (p < para.size()) {
            while (p < para.size() && para[p] == ' ') p++;
            auto e = para.find(' ', p);
            if (e == std::string::npos) e = para.size();
            auto word = para.substr(p, e - p);
            p = e;
            if (word.empty()) continue;
            if (!line.empty() && line.size() + 1 + word.size() > width) {
                std::puts(line.c_str());
                line.clear();
            }
            line += (line.empty() ? "" : " ") + word;
        }
        if (!line.empty()) std::puts(line.c_str());
        para.clear();
    };
    for (auto &line : inputLines(a, i)) {
        if (line.find_first_not_of(" \t") == std::string::npos) {
            flush();
            std::puts("");
        } else {
            para += " " + line;
        }
    }
    flush();
    return 0;
}

int cmdExpand(Args &a) {
    size_t tab = 8, i = 1;
    if (i + 1 < a.size() && a[i] == "-t") {
        tab = std::max<size_t>(1, std::strtoul(a[i + 1].c_str(), nullptr, 10));
        i += 2;
    }
    for (auto &line : inputLines(a, i)) {
        std::string out;
        for (char c : line) {
            if (c == '\t') out.append(tab - out.size() % tab, ' ');
            else out += c;
        }
        std::puts(out.c_str());
    }
    return 0;
}

int cmdPaste(Args &a) {
    std::string delim = "\t";
    bool serial = false;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-d" && i + 1 < a.size()) delim = a[++i];
        else if (a[i].rfind("-d", 0) == 0) delim = a[i].substr(2);
        else if (a[i] == "-s") serial = true;
    }
    if (delim.empty()) delim = "\t";
    std::vector<std::vector<std::string>> files;
    if (i >= a.size()) a.push_back("-");
    for (; i < a.size(); i++) {
        auto t = readFile(a[i]);
        if (!t) return warnErrno(a[i]);
        files.push_back(splitLines(*t));
    }
    if (serial) {
        for (auto &f : files) {
            for (size_t j = 0; j < f.size(); j++) std::printf("%s%s", j ? std::string(1, delim[(j - 1) % delim.size()]).c_str() : "", f[j].c_str());
            std::puts("");
        }
        return 0;
    }
    size_t rows = 0;
    for (auto &f : files) rows = std::max(rows, f.size());
    for (size_t r = 0; r < rows; r++) {
        for (size_t c = 0; c < files.size(); c++) {
            if (c) std::putchar(delim[(c - 1) % delim.size()]);
            if (r < files[c].size()) std::fputs(files[c][r].c_str(), stdout);
        }
        std::puts("");
    }
    return 0;
}

int cmdComm(Args &a) {
    bool hide[4] = {false, false, false, false};
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        for (char c : a[i].substr(1)) {
            if (c >= '1' && c <= '3') hide[c - '0'] = true;
        }
    }
    if (i + 2 > a.size()) die("usage: comm [-123] FILE1 FILE2");
    auto f1 = readFile(a[i]), f2 = readFile(a[i + 1]);
    if (!f1 || !f2) return warnErrno(!f1 ? a[i] : a[i + 1]);
    auto l1 = splitLines(*f1), l2 = splitLines(*f2);
    size_t x = 0, y = 0;
    auto out = [&](int col, const std::string &s) {
        if (hide[col]) return;
        int tabs = 0;
        for (int c = 1; c < col; c++) tabs += hide[c] ? 0 : 1;
        std::printf("%s%s\n", std::string(static_cast<size_t>(tabs), '\t').c_str(), s.c_str());
    };
    while (x < l1.size() || y < l2.size()) {
        if (y >= l2.size() || (x < l1.size() && l1[x] < l2[y])) out(1, l1[x++]);
        else if (x >= l1.size() || l2[y] < l1[x]) out(2, l2[y++]);
        else {
            out(3, l1[x]);
            x++;
            y++;
        }
    }
    return 0;
}

std::vector<std::string> fields(const std::string &line, char sep) {
    std::vector<std::string> out;
    if (sep == 0) {
        size_t p = 0;
        while (p < line.size()) {
            while (p < line.size() && std::isspace(static_cast<unsigned char>(line[p]))) p++;
            if (p >= line.size()) break;
            auto e = p;
            while (e < line.size() && !std::isspace(static_cast<unsigned char>(line[e]))) e++;
            out.push_back(line.substr(p, e - p));
            p = e;
        }
        return out;
    }
    size_t start = 0;
    while (true) {
        auto e = line.find(sep, start);
        out.push_back(line.substr(start, e == std::string::npos ? std::string::npos : e - start));
        if (e == std::string::npos) break;
        start = e + 1;
    }
    return out;
}

int cmdJoin(Args &a) {
    char sep = 0;
    size_t i = 1, f1 = 0, f2 = 0;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-t" && i + 1 < a.size()) sep = a[++i][0];
        else if (a[i] == "-1" && i + 1 < a.size()) f1 = std::strtoul(a[++i].c_str(), nullptr, 10) - 1;
        else if (a[i] == "-2" && i + 1 < a.size()) f2 = std::strtoul(a[++i].c_str(), nullptr, 10) - 1;
    }
    if (i + 2 > a.size()) die("usage: join [-t C] [-1 F] [-2 F] FILE1 FILE2");
    auto t1 = readFile(a[i]), t2 = readFile(a[i + 1]);
    if (!t1 || !t2) return warnErrno(!t1 ? a[i] : a[i + 1]);
    std::multimap<std::string, std::vector<std::string>> right;
    for (auto &line : splitLines(*t2)) {
        auto f = fields(line, sep);
        if (f2 < f.size()) right.emplace(f[f2], f);
    }
    std::string out(1, sep ? sep : ' ');
    for (auto &line : splitLines(*t1)) {
        auto f = fields(line, sep);
        if (f1 >= f.size()) continue;
        auto range = right.equal_range(f[f1]);
        for (auto it = range.first; it != range.second; ++it) {
            std::string s = f[f1];
            for (size_t k = 0; k < f.size(); k++) if (k != f1) s += out + f[k];
            for (size_t k = 0; k < it->second.size(); k++) if (k != f2) s += out + it->second[k];
            std::puts(s.c_str());
        }
    }
    return 0;
}

int cmdColumn(Args &a) {
    bool table = false;
    char sep = 0;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-t") table = true;
        else if (a[i] == "-s" && i + 1 < a.size()) sep = a[++i][0];
    }
    auto lines = inputLines(a, i);
    if (!table) {
        // Fill columns across an 80 (or $COLUMNS) wide screen.
        size_t width = 0;
        for (auto &l : lines) width = std::max(width, l.size());
        auto screen = static_cast<size_t>(std::max(20, cc::tty::size().cols));
        size_t cols = std::max<size_t>(1, screen / (width + 2)), rows = (lines.size() + cols - 1) / cols;
        for (size_t r = 0; r < rows; r++) {
            std::string s;
            for (size_t c = 0; c < cols; c++) {
                size_t k = c * rows + r;
                if (k < lines.size()) s += lines[k] + std::string(width + 2 - lines[k].size(), ' ');
            }
            while (!s.empty() && s.back() == ' ') s.pop_back();
            std::puts(s.c_str());
        }
        return 0;
    }
    std::vector<std::vector<std::string>> rows;
    std::vector<size_t> widths;
    for (auto &l : lines) {
        rows.push_back(fields(l, sep));
        for (size_t c = 0; c < rows.back().size(); c++) {
            if (widths.size() <= c) widths.push_back(0);
            widths[c] = std::max(widths[c], rows.back()[c].size());
        }
    }
    for (auto &r : rows) {
        std::string s;
        for (size_t c = 0; c < r.size(); c++) s += r[c] + (c + 1 < r.size() ? std::string(widths[c] - r[c].size() + 2, ' ') : "");
        std::puts(s.c_str());
    }
    return 0;
}

int cmdStrings(Args &a) {
    size_t min = 4, i = 1;
    if (i + 1 < a.size() && a[i] == "-n") {
        min = std::strtoul(a[i + 1].c_str(), nullptr, 10);
        i += 2;
    }
    auto data = readInputs(a, i);
    std::string run;
    for (char c : data) {
        if (std::isprint(static_cast<unsigned char>(c)) || c == '\t') {
            run += c;
        } else {
            if (run.size() >= min) std::puts(run.c_str());
            run.clear();
        }
    }
    if (run.size() >= min) std::puts(run.c_str());
    return 0;
}

int cmdSplit(Args &a) {
    size_t lines = 1000, bytes = 0, i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-l" && i + 1 < a.size()) lines = std::strtoul(a[++i].c_str(), nullptr, 10);
        else if (a[i] == "-b" && i + 1 < a.size()) {
            char *end;
            bytes = std::strtoul(a[++i].c_str(), &end, 10);
            if (*end == 'k' || *end == 'K') bytes *= 1024;
            if (*end == 'm' || *end == 'M') bytes *= 1024 * 1024;
        }
    }
    std::string input = i < a.size() ? a[i++] : "-";
    std::string prefix = i < a.size() ? a[i] : "x";
    auto data = readFile(input);
    if (!data) return warnErrno(input);
    int n = 0;
    auto name = [&]() {
        std::string s = prefix;
        s += static_cast<char>('a' + n / 26);
        s += static_cast<char>('a' + n % 26);
        n++;
        return s;
    };
    size_t p = 0;
    while (p < data->size()) {
        size_t end;
        if (bytes) {
            end = std::min(data->size(), p + bytes);
        } else {
            end = p;
            for (size_t k = 0; k < lines && end < data->size(); k++) {
                auto nl = data->find('\n', end);
                end = nl == std::string::npos ? data->size() : nl + 1;
            }
        }
        if (!writeFile(name(), data->substr(p, end - p))) return warnErrno("write");
        p = end;
    }
    return 0;
}

int cmdShuf(Args &a) {
    std::vector<std::string> items;
    size_t i = 1, count = SIZE_MAX;
    bool echo = false;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-n" && i + 1 < a.size()) count = std::strtoul(a[++i].c_str(), nullptr, 10);
        else if (a[i] == "-e") echo = true;
        else if (a[i] == "-i" && i + 1 < a.size()) {
            auto r = a[++i];
            auto dash = r.find('-');
            long lo = std::atol(r.c_str()), hi = dash == std::string::npos ? lo : std::atol(r.c_str() + dash + 1);
            for (long v = lo; v <= hi; v++) items.push_back(std::to_string(v));
        }
    }
    if (echo) items.assign(a.begin() + static_cast<long>(i), a.end());
    else if (items.empty()) items = inputLines(a, i);
    std::mt19937 rng{std::random_device{}()};
    std::shuffle(items.begin(), items.end(), rng);
    for (size_t k = 0; k < items.size() && k < count; k++) std::puts(items[k].c_str());
    return 0;
}

// ---- cmp / diff -------------------------------------------------------------------------------------------------

int cmdCmp(Args &a) {
    bool quiet = false;
    size_t i = 1;
    if (i < a.size() && a[i] == "-s") {
        quiet = true;
        i++;
    }
    if (i + 2 > a.size()) die("usage: cmp [-s] FILE1 FILE2");
    auto x = readFile(a[i]), y = readFile(a[i + 1]);
    if (!x || !y) return warnErrno(!x ? a[i] : a[i + 1]) + 1;
    size_t line = 1;
    for (size_t k = 0; k < std::min(x->size(), y->size()); k++) {
        if ((*x)[k] != (*y)[k]) {
            if (!quiet) std::printf("%s %s differ: byte %zu, line %zu\n", a[i].c_str(), a[i + 1].c_str(), k + 1, line);
            return 1;
        }
        if ((*x)[k] == '\n') line++;
    }
    if (x->size() != y->size()) {
        if (!quiet) std::printf("cmp: EOF on %s\n", (x->size() < y->size() ? a[i] : a[i + 1]).c_str());
        return 1;
    }
    return 0;
}

// Edit script between two line lists (LCS, Myers' O(ND) algorithm with a linear trace).
struct Edit {
    char op; // ' ', '-', '+'
    size_t a, b; // line indexes in the old and new file
};

std::vector<Edit> diffLines(const std::vector<std::string> &x, const std::vector<std::string> &y) {
    long n = static_cast<long>(x.size()), m = static_cast<long>(y.size()), max = n + m;
    std::vector<long> v(static_cast<size_t>(2 * max + 2), 0);
    std::vector<std::vector<long>> trace;
    long offset = max + 1;
    for (long d = 0; d <= max; d++) {
        trace.push_back(v);
        bool done = false;
        for (long k = -d; k <= d; k += 2) {
            long xi;
            if (k == -d || (k != d && v[static_cast<size_t>(k - 1 + offset)] < v[static_cast<size_t>(k + 1 + offset)])) {
                xi = v[static_cast<size_t>(k + 1 + offset)];
            } else {
                xi = v[static_cast<size_t>(k - 1 + offset)] + 1;
            }
            long yi = xi - k;
            while (xi < n && yi < m && x[static_cast<size_t>(xi)] == y[static_cast<size_t>(yi)]) {
                xi++;
                yi++;
            }
            v[static_cast<size_t>(k + offset)] = xi;
            if (xi >= n && yi >= m) {
                done = true;
                break;
            }
        }
        if (done) break;
    }
    // Walk back through the trace.
    std::vector<Edit> edits;
    long xi = n, yi = m;
    for (long d = static_cast<long>(trace.size()) - 1; d >= 0; d--) {
        auto &tv = trace[static_cast<size_t>(d)];
        long k = xi - yi;
        long prevK;
        if (k == -d || (k != d && tv[static_cast<size_t>(k - 1 + offset)] < tv[static_cast<size_t>(k + 1 + offset)])) prevK = k + 1;
        else prevK = k - 1;
        long prevX = tv[static_cast<size_t>(prevK + offset)], prevY = prevX - prevK;
        while (xi > prevX && yi > prevY) {
            edits.push_back({' ', static_cast<size_t>(--xi), static_cast<size_t>(--yi)});
        }
        if (d > 0) {
            if (xi == prevX) edits.push_back({'+', static_cast<size_t>(xi), static_cast<size_t>(--yi)});
            else edits.push_back({'-', static_cast<size_t>(--xi), static_cast<size_t>(yi)});
        }
    }
    std::reverse(edits.begin(), edits.end());
    return edits;
}

int diffFiles(const std::string &p1, const std::string &p2, bool unified, int context, bool brief) {
    auto t1 = readFile(p1), t2 = readFile(p2);
    if (!t1 || !t2) {
        warnErrno(!t1 ? p1 : p2);
        return 2;
    }
    if (*t1 == *t2) return 0;
    if (brief) {
        std::printf("Files %s and %s differ\n", p1.c_str(), p2.c_str());
        return 1;
    }
    auto x = splitLines(*t1), y = splitLines(*t2);
    auto edits = diffLines(x, y);
    bool color = isatty(1);
    if (unified) {
        std::printf("--- %s\n+++ %s\n", p1.c_str(), p2.c_str());
        size_t i = 0;
        while (i < edits.size()) {
            if (edits[i].op == ' ') {
                i++;
                continue;
            }
            // A hunk: changes with up to `context` lines around, merging nearby changes.
            size_t start = i >= static_cast<size_t>(context) ? i - static_cast<size_t>(context) : 0, end = i;
            while (true) {
                while (end < edits.size() && edits[end].op != ' ') end++;
                size_t next = end;
                while (next < edits.size() && edits[next].op == ' ') next++;
                if (next < edits.size() && next - end <= static_cast<size_t>(2 * context)) end = next;
                else break;
            }
            end = std::min(edits.size(), end + static_cast<size_t>(context));
            size_t aStart = 0, bStart = 0, aCount = 0, bCount = 0;
            bool firstA = true, firstB = true;
            for (size_t k = start; k < end; k++) {
                if (edits[k].op != '+') {
                    if (firstA) aStart = edits[k].a;
                    firstA = false;
                    aCount++;
                }
                if (edits[k].op != '-') {
                    if (firstB) bStart = edits[k].b;
                    firstB = false;
                    bCount++;
                }
            }
            if (firstA) aStart = edits[start].a;
            if (firstB) bStart = edits[start].b;
            std::printf(color ? "\x1b[96m@@ -%zu,%zu +%zu,%zu @@\x1b[0m\n" : "@@ -%zu,%zu +%zu,%zu @@\n", aCount ? aStart + 1 : aStart, aCount,
                        bCount ? bStart + 1 : bStart, bCount);
            for (size_t k = start; k < end; k++) {
                auto &e = edits[k];
                const auto &text = e.op == '+' ? y[e.b] : x[e.a];
                if (color && e.op != ' ') std::printf("%s%c%s\x1b[0m\n", e.op == '+' ? "\x1b[92m" : "\x1b[91m", e.op, text.c_str());
                else std::printf("%c%s\n", e.op, text.c_str());
            }
            i = end;
        }
        return 1;
    }
    // Normal format: "3,4c3" etc.
    size_t i = 0;
    while (i < edits.size()) {
        if (edits[i].op == ' ') {
            i++;
            continue;
        }
        size_t j = i;
        std::vector<size_t> dels, adds;
        while (j < edits.size() && edits[j].op != ' ') {
            (edits[j].op == '-' ? dels : adds).push_back(edits[j].op == '-' ? edits[j].a : edits[j].b);
            j++;
        }
        auto range = [](size_t from, size_t count) {
            return count <= 1 ? std::to_string(from + 1) : std::to_string(from + 1) + "," + std::to_string(from + count);
        };
        size_t aPos = dels.empty() ? edits[i].a : dels[0], bPos = adds.empty() ? edits[i].b : adds[0];
        char op = dels.empty() ? 'a' : adds.empty() ? 'd' : 'c';
        std::printf("%s%c%s\n", (dels.empty() ? std::to_string(aPos) : range(aPos, dels.size())).c_str(), op,
                    (adds.empty() ? std::to_string(bPos) : range(bPos, adds.size())).c_str());
        for (auto d : dels) std::printf("< %s\n", x[d].c_str());
        if (!dels.empty() && !adds.empty()) std::puts("---");
        for (auto d : adds) std::printf("> %s\n", y[d].c_str());
        i = j;
    }
    return 1;
}

int diffPaths(const std::string &p1, const std::string &p2, bool unified, int context, bool brief, bool recursive) {
    if (isDir(p1) && isDir(p2)) {
        if (!recursive) {
            std::printf("Common directories: %s and %s\n", p1.c_str(), p2.c_str());
            return 0;
        }
        auto l1 = listDir(p1), l2 = listDir(p2);
        int status = 0;
        for (auto &n : l1) {
            if (std::find(l2.begin(), l2.end(), n) == l2.end()) {
                std::printf("Only in %s: %s\n", p1.c_str(), n.c_str());
                status = 1;
            } else {
                status = std::max(status, diffPaths(joinPath(p1, n), joinPath(p2, n), unified, context, brief, recursive));
            }
        }
        for (auto &n : l2) {
            if (std::find(l1.begin(), l1.end(), n) == l1.end()) {
                std::printf("Only in %s: %s\n", p2.c_str(), n.c_str());
                status = 1;
            }
        }
        return status;
    }
    if (isDir(p2)) return diffFiles(p1, joinPath(p2, p1.substr(p1.rfind('/') + 1)), unified, context, brief);
    return diffFiles(p1, p2, unified, context, brief);
}

int cmdDiff(Args &a) {
    bool unified = false, brief = false, recursive = false;
    int context = 3;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-U" && i + 1 < a.size()) {
            unified = true;
            context = std::atoi(a[++i].c_str());
            continue;
        }
        for (char c : a[i].substr(1)) {
            if (c == 'u') unified = true;
            else if (c == 'q') brief = true;
            else if (c == 'r') recursive = true;
        }
    }
    if (i + 2 > a.size()) die("usage: diff [-u] [-q] [-r] [-U N] OLD NEW");
    return diffPaths(a[i], a[i + 1], unified, context, brief, recursive);
}

// ---- od / xxd / base64 ------------------------------------------------------------------------------------------

int cmdXxd(Args &a) {
    bool reverse = false, plain = false;
    size_t i = 1, cols = 16;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-r") reverse = true;
        else if (a[i] == "-p" || a[i] == "-ps") plain = true;
        else if (a[i] == "-c" && i + 1 < a.size()) cols = std::max<size_t>(1, std::strtoul(a[++i].c_str(), nullptr, 10));
    }
    auto data = readFile(i < a.size() ? a[i] : "-");
    if (!data) return warnErrno(a[i]);
    if (reverse) {
        std::string out;
        for (auto &line : splitLines(*data)) {
            std::string hex = line;
            if (!plain) {
                auto colon = line.find(':');
                hex = colon == std::string::npos ? "" : line.substr(colon + 1);
                auto text = hex.find("  ");
                if (text != std::string::npos) hex = hex.substr(0, text);
            }
            int nibble = -1;
            for (char c : hex) {
                int v = std::isdigit(static_cast<unsigned char>(c)) ? c - '0' : (c >= 'a' && c <= 'f') ? c - 'a' + 10 : (c >= 'A' && c <= 'F') ? c - 'A' + 10 : -1;
                if (v < 0) continue;
                if (nibble < 0) nibble = v;
                else {
                    out += static_cast<char>(nibble * 16 + v);
                    nibble = -1;
                }
            }
        }
        std::fwrite(out.data(), 1, out.size(), stdout);
        return 0;
    }
    if (plain) {
        for (size_t k = 0; k < data->size(); k++) {
            std::printf("%02x", static_cast<unsigned char>((*data)[k]));
            if ((k + 1) % 30 == 0) std::puts("");
        }
        std::puts("");
        return 0;
    }
    for (size_t off = 0; off < data->size(); off += cols) {
        std::printf("%08zx:", off);
        for (size_t k = 0; k < cols; k++) {
            if (k % 2 == 0) std::putchar(' ');
            if (off + k < data->size()) std::printf("%02x", static_cast<unsigned char>((*data)[off + k]));
            else std::printf("  ");
        }
        std::printf("  ");
        for (size_t k = 0; k < cols && off + k < data->size(); k++) {
            auto c = static_cast<unsigned char>((*data)[off + k]);
            std::putchar(c >= 32 && c < 127 ? c : '.');
        }
        std::puts("");
    }
    return 0;
}

int cmdOd(Args &a) {
    char format = 'o';
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-c") format = 'c';
        else if (a[i] == "-x") format = 'x';
        else if (a[i] == "-t" && i + 1 < a.size()) format = a[++i][0] == 'x' ? 'x' : a[i][0] == 'c' ? 'c' : a[i][0] == 'd' ? 'd' : 'o';
        else if (a[i] == "-An") continue;
    }
    auto data = readInputs(a, i);
    for (size_t off = 0; off < data.size(); off += 16) {
        std::printf("%07zo", off);
        for (size_t k = off; k < std::min(data.size(), off + 16); k++) {
            auto c = static_cast<unsigned char>(data[k]);
            if (format == 'c') {
                if (c >= 32 && c < 127) std::printf("   %c", c);
                else if (c == '\n') std::printf("  \\n");
                else if (c == '\t') std::printf("  \\t");
                else if (c == 0) std::printf("  \\0");
                else std::printf(" %03o", c);
            } else if (format == 'x') {
                std::printf(" %02x", c);
            } else if (format == 'd') {
                std::printf(" %4d", c);
            } else {
                std::printf(" %03o", c);
            }
        }
        std::puts("");
    }
    std::printf("%07zo\n", data.size());
    return 0;
}

const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

int cmdBase64(Args &a) {
    bool decode = false;
    size_t i = 1, wrap = 76;
    for (; i < a.size() && isOption(a[i]); i++) {
        if (a[i] == "-d" || a[i] == "--decode") decode = true;
        else if (a[i] == "-w" && i + 1 < a.size()) wrap = std::strtoul(a[++i].c_str(), nullptr, 10);
    }
    auto data = readInputs(a, i);
    std::string out;
    if (decode) {
        uint32_t acc = 0;
        int bits = 0;
        for (char c : data) {
            const char *p = c ? std::strchr(B64, c) : nullptr;
            if (!p) continue;
            acc = (acc << 6) | static_cast<uint32_t>(p - B64);
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out += static_cast<char>((acc >> bits) & 0xFF);
            }
        }
        std::fwrite(out.data(), 1, out.size(), stdout);
        return 0;
    }
    for (size_t k = 0; k < data.size(); k += 3) {
        uint32_t v = static_cast<uint32_t>(static_cast<unsigned char>(data[k])) << 16;
        if (k + 1 < data.size()) v |= static_cast<uint32_t>(static_cast<unsigned char>(data[k + 1])) << 8;
        if (k + 2 < data.size()) v |= static_cast<unsigned char>(data[k + 2]);
        out += B64[(v >> 18) & 63];
        out += B64[(v >> 12) & 63];
        out += k + 1 < data.size() ? B64[(v >> 6) & 63] : '=';
        out += k + 2 < data.size() ? B64[v & 63] : '=';
    }
    for (size_t k = 0; k < out.size(); k += wrap ? wrap : out.size() + 1) std::puts(out.substr(k, wrap ? wrap : std::string::npos).c_str());
    if (out.empty()) std::puts("");
    return 0;
}

// ---- Checksums --------------------------------------------------------------------------------------------------

uint32_t rotl(uint32_t x, int n) {
    return (x << n) | (x >> (32 - n));
}
uint32_t rotr(uint32_t x, int n) {
    return (x >> n) | (x << (32 - n));
}

std::string hex(const uint8_t *p, size_t n) {
    std::string s;
    char buf[3];
    for (size_t i = 0; i < n; i++) {
        std::snprintf(buf, sizeof(buf), "%02x", p[i]);
        s += buf;
    }
    return s;
}

// Message padding shared by MD5 (little-endian length) and SHA (big-endian length).
std::string pad(const std::string &data, bool bigEndian) {
    std::string m = data;
    uint64_t bits = static_cast<uint64_t>(data.size()) * 8;
    m += static_cast<char>(0x80);
    while (m.size() % 64 != 56) m += '\0';
    for (int i = 0; i < 8; i++) m += static_cast<char>(bigEndian ? (bits >> (56 - 8 * i)) & 0xFF : (bits >> (8 * i)) & 0xFF);
    return m;
}

std::string md5(const std::string &data) {
    static const uint32_t K[64] = {
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501, 0x698098d8, 0x8b44f7af, 0xffff5bb1,
        0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821, 0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453,
        0xd8a1e681, 0xe7d3fbc8, 0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a, 0xfffa3942,
        0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70, 0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05,
        0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665, 0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d,
        0x85845dd1, 0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391};
    static const int S[64] = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20,
                              4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};
    uint32_t h[4] = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
    auto m = pad(data, false);
    for (size_t off = 0; off < m.size(); off += 64) {
        uint32_t w[16];
        for (int i = 0; i < 16; i++) {
            auto *p = reinterpret_cast<const uint8_t *>(m.data() + off + 4 * i);
            w[i] = p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
        }
        uint32_t A = h[0], B = h[1], C = h[2], D = h[3];
        for (int i = 0; i < 64; i++) {
            uint32_t F;
            int g;
            if (i < 16) {
                F = (B & C) | (~B & D);
                g = i;
            } else if (i < 32) {
                F = (D & B) | (~D & C);
                g = (5 * i + 1) % 16;
            } else if (i < 48) {
                F = B ^ C ^ D;
                g = (3 * i + 5) % 16;
            } else {
                F = C ^ (B | ~D);
                g = (7 * i) % 16;
            }
            F += A + K[i] + w[g];
            A = D;
            D = C;
            C = B;
            B += rotl(F, S[i]);
        }
        h[0] += A;
        h[1] += B;
        h[2] += C;
        h[3] += D;
    }
    uint8_t out[16];
    for (int i = 0; i < 16; i++) out[i] = static_cast<uint8_t>(h[i / 4] >> (8 * (i % 4)));
    return hex(out, 16);
}

std::string sha1(const std::string &data) {
    uint32_t h[5] = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};
    auto m = pad(data, true);
    for (size_t off = 0; off < m.size(); off += 64) {
        uint32_t w[80];
        for (int i = 0; i < 16; i++) {
            auto *p = reinterpret_cast<const uint8_t *>(m.data() + off + 4 * i);
            w[i] = (static_cast<uint32_t>(p[0]) << 24) | (p[1] << 16) | (p[2] << 8) | p[3];
        }
        for (int i = 16; i < 80; i++) w[i] = rotl(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        uint32_t A = h[0], B = h[1], C = h[2], D = h[3], E = h[4];
        for (int i = 0; i < 80; i++) {
            uint32_t f, k;
            if (i < 20) {
                f = (B & C) | (~B & D);
                k = 0x5A827999;
            } else if (i < 40) {
                f = B ^ C ^ D;
                k = 0x6ED9EBA1;
            } else if (i < 60) {
                f = (B & C) | (B & D) | (C & D);
                k = 0x8F1BBCDC;
            } else {
                f = B ^ C ^ D;
                k = 0xCA62C1D6;
            }
            uint32_t t = rotl(A, 5) + f + E + k + w[i];
            E = D;
            D = C;
            C = rotl(B, 30);
            B = A;
            A = t;
        }
        h[0] += A;
        h[1] += B;
        h[2] += C;
        h[3] += D;
        h[4] += E;
    }
    uint8_t out[20];
    for (int i = 0; i < 20; i++) out[i] = static_cast<uint8_t>(h[i / 4] >> (24 - 8 * (i % 4)));
    return hex(out, 20);
}

std::string sha256(const std::string &data) {
    static const uint32_t K[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be,
        0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa,
        0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85,
        0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f,
        0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
    uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    auto m = pad(data, true);
    for (size_t off = 0; off < m.size(); off += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++) {
            auto *p = reinterpret_cast<const uint8_t *>(m.data() + off + 4 * i);
            w[i] = (static_cast<uint32_t>(p[0]) << 24) | (p[1] << 16) | (p[2] << 8) | p[3];
        }
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t v[8];
        std::memcpy(v, h, sizeof(v));
        for (int i = 0; i < 64; i++) {
            uint32_t S1 = rotr(v[4], 6) ^ rotr(v[4], 11) ^ rotr(v[4], 25);
            uint32_t ch = (v[4] & v[5]) ^ (~v[4] & v[6]);
            uint32_t t1 = v[7] + S1 + ch + K[i] + w[i];
            uint32_t S0 = rotr(v[0], 2) ^ rotr(v[0], 13) ^ rotr(v[0], 22);
            uint32_t maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
            uint32_t t2 = S0 + maj;
            for (int k = 7; k > 0; k--) v[k] = v[k - 1];
            v[4] += t1;
            v[0] = t1 + t2;
        }
        for (int i = 0; i < 8; i++) h[i] += v[i];
    }
    uint8_t out[32];
    for (int i = 0; i < 32; i++) out[i] = static_cast<uint8_t>(h[i / 4] >> (24 - 8 * (i % 4)));
    return hex(out, 32);
}

// POSIX cksum: CRC-32 (polynomial 0x04C11DB7, MSB first) over the data and its length.
uint32_t posixCksum(const std::string &data) {
    uint32_t crc = 0;
    auto update = [&](uint8_t byte) {
        crc ^= static_cast<uint32_t>(byte) << 24;
        for (int i = 0; i < 8; i++) crc = crc & 0x80000000u ? (crc << 1) ^ 0x04C11DB7u : crc << 1;
    };
    for (unsigned char c : data) update(c);
    for (auto n = data.size(); n; n >>= 8) update(static_cast<uint8_t>(n & 0xFF));
    return ~crc;
}

int cmdHashsum(Args &a) {
    std::function<std::string(const std::string &)> fn = prog == "md5sum" ? md5 : prog == "sha1sum" ? sha1 : sha256;
    bool check = false;
    size_t i = 1;
    if (i < a.size() && a[i] == "-c") {
        check = true;
        i++;
    }
    if (check) {
        int bad = 0;
        for (auto &line : inputLines(a, i)) {
            auto sp = line.find("  ");
            if (sp == std::string::npos) continue;
            auto name = line.substr(sp + 2);
            auto data = readFile(name);
            bool ok = data && fn(*data) == line.substr(0, sp);
            std::printf("%s: %s\n", name.c_str(), ok ? "OK" : "FAILED");
            bad += ok ? 0 : 1;
        }
        return bad ? 1 : 0;
    }
    if (i >= a.size()) a.push_back("-");
    int status = 0;
    for (; i < a.size(); i++) {
        auto data = readFile(a[i]);
        if (!data) {
            status = warnErrno(a[i]);
            continue;
        }
        std::printf("%s  %s\n", fn(*data).c_str(), a[i].c_str());
    }
    return status;
}

int cmdCksum(Args &a) {
    if (a.size() < 2) a.push_back("-");
    int status = 0;
    for (size_t i = 1; i < a.size(); i++) {
        auto data = readFile(a[i]);
        if (!data) {
            status = warnErrno(a[i]);
            continue;
        }
        std::printf("%u %zu%s%s\n", posixCksum(*data), data->size(), a[i] == "-" ? "" : " ", a[i] == "-" ? "" : a[i].c_str());
    }
    return status;
}

} // namespace

// The rest of the applets live in textutils_*.cpp; each part registers its own.
using Applet = int (*)(std::vector<std::string> &);
void registerSed(std::map<std::string, Applet> &);
void registerAwk(std::map<std::string, Applet> &);
void registerFiles(std::map<std::string, Applet> &);

namespace {
const std::map<std::string, Applet> &applets() {
    static const std::map<std::string, Applet> table = [] {
        std::map<std::string, Applet> t = {
            {"tac", cmdTac},       {"nl", cmdNl},         {"fold", cmdFold},       {"fmt", cmdFmt},         {"expand", cmdExpand},
            {"paste", cmdPaste},   {"comm", cmdComm},     {"join", cmdJoin},       {"column", cmdColumn},   {"strings", cmdStrings},
            {"split", cmdSplit},   {"shuf", cmdShuf},     {"cmp", cmdCmp},         {"diff", cmdDiff},       {"xxd", cmdXxd},
            {"od", cmdOd},         {"base64", cmdBase64}, {"md5sum", cmdHashsum},  {"sha1sum", cmdHashsum}, {"sha256sum", cmdHashsum},
            {"cksum", cmdCksum},
        };
        registerSed(t);
        registerAwk(t);
        registerFiles(t);
        return t;
    }();
    return table;
}
} // namespace

std::string &textutilsProg() {
    return prog;
}

// Usage of the commands that do not parse --help themselves (it would be taken as a file or ignored).
const char *usageOf(const std::string &name) {
    static const std::map<std::string, const char *> table = {
        {"cal", "cal [[MONTH] YEAR]"},
        {"cksum", "cksum [FILE...]"},
        {"md5sum", "md5sum [-c] [FILE...]"},
        {"sha1sum", "sha1sum [-c] [FILE...]"},
        {"sha256sum", "sha256sum [-c] [FILE...]"},
        {"expand", "expand [-t N] [FILE...]"},
        {"expr", "expr EXPRESSION   (arithmetic, comparisons, STRING : REGEX, length, substr, index)"},
        {"fmt", "fmt [-w WIDTH] [FILE...]"},
        {"id", "id"},
        {"mktemp", "mktemp [-d] [TEMPLATE]"},
        {"nproc", "nproc"},
        {"od", "od [-c|-x|-t c|x|d|o] [-An] [FILE]"},
        {"strings", "strings [-n MIN] [FILE...]"},
        {"tac", "tac [FILE...]"},
        {"tree", "tree [-a] [-L DEPTH] [DIR]"},
        {"tty", "tty"},
        {"watch", "watch [-n SECONDS] COMMAND..."},
        {"xxd", "xxd [-p] [-r] [-c COLS] [FILE]"},
        {"awk", "awk [-F FS] [-v VAR=VALUE] 'PROGRAM' | -f FILE [FILE...]"},
        {"sed", "sed [-n] [-E] [-i] [-e SCRIPT | SCRIPT] [FILE...]"},
        {"tar", "tar -c|-x|-t [-v] -f ARCHIVE [-C DIR] [FILE...]   (ustar, no compression; - is stdin/stdout)"},
    };
    auto it = table.find(name);
    return it == table.end() ? nullptr : it->second;
}

int main(int argc, char **argv) {
    Args args(argv, argv + argc);
    auto slash = args[0].rfind('/');
    prog = slash == std::string::npos ? args[0] : args[0].substr(slash + 1);
    if (prog == "textutils") {
        if (args.size() < 2 || args[1] == "--help" || args[1] == "-h") {
            std::printf("textutils: multi-call binary. Commands:\n");
            for (auto &[name, fn] : applets()) std::printf("%s ", name.c_str());
            std::puts("");
            return 0;
        }
        args.erase(args.begin());
        prog = args[0];
    }
    if (args.size() > 1 && args[1] == "--help") {
        if (auto *u = usageOf(prog)) {
            std::printf("usage: %s\n", u);
            return 0;
        }
    }
    auto it = applets().find(prog);
    if (it == applets().end()) {
        std::fprintf(stderr, "textutils: unknown command %s\n", prog.c_str());
        return 127;
    }
    int r = it->second(args);
    std::fflush(stdout);
    return r;
}
