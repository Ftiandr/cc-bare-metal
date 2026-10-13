// sed - stream editor (POSIX commands; -n -e -f -i -E; addresses N, $, /re/, ranges, !; s///gpNI with & and \1-\9;
// d D p P q Q a i c y = n N g G h H x l b t T : and { } blocks).
#include "textutils.hpp"

#include <memory>
#include <unistd.h>

namespace {

using namespace tu;

struct Address {
    enum Kind { None, Line, Last, Pattern } kind = None;
    long line = 0;
    std::shared_ptr<Regex> re;
};

struct Command {
    Address a1, a2;
    bool negate = false;
    char cmd = 0;
    // s
    std::shared_ptr<Regex> re;
    std::string replacement;
    bool global = false, print = false;
    int nth = 1;
    std::string writeFile;
    // a i c, labels, y
    std::string text;
    std::string from, to;
    size_t jump = 0;     // b/t/T target, '{' matching '}'
    bool inRange = false; // range state
};

class Sed {
public:
    bool quiet = false;
    int reFlags = 0;
    std::vector<Command> program;

    void parse(const std::string &script);
    void link();
    // Run over one input text; returns the output. Sets `quit` when a q command ran.
    void run(const std::string &input, std::string &out, bool last);
    bool quit = false;

private:
    long lineNo_ = 0;
    std::string hold_;
    std::string pos_;
    size_t p_ = 0;
    std::string s_;

    [[noreturn]] void error(const std::string &message) const { die("-e expression, char " + std::to_string(p_) + ": " + message); }
    void skipSpace() {
        while (p_ < s_.size() && (s_[p_] == ' ' || s_[p_] == '\t')) p_++;
    }
    std::string delimited(char delim);
    Address address();
    bool matches(Command &c, const std::string &pattern, bool lastLine);
    bool addressMatches(const Address &a, const std::string &pattern, bool lastLine) const;
    bool substitute(Command &c, std::string &pattern);
};

std::string Sed::delimited(char delim) {
    std::string out;
    while (p_ < s_.size() && s_[p_] != delim) {
        if (s_[p_] == '\\' && p_ + 1 < s_.size()) {
            if (s_[p_ + 1] == delim) {
                out += delim;
                p_ += 2;
                continue;
            }
            if (s_[p_ + 1] == 'n') {
                out += '\n';
                p_ += 2;
                continue;
            }
            out += s_[p_++];
        }
        out += s_[p_++];
    }
    if (p_ >= s_.size()) error("unterminated `s' command");
    p_++;
    return out;
}

Address Sed::address() {
    Address a;
    if (p_ >= s_.size()) return a;
    if (std::isdigit(static_cast<unsigned char>(s_[p_]))) {
        a.kind = Address::Line;
        while (p_ < s_.size() && std::isdigit(static_cast<unsigned char>(s_[p_]))) a.line = a.line * 10 + (s_[p_++] - '0');
    } else if (s_[p_] == '$') {
        a.kind = Address::Last;
        p_++;
    } else if (s_[p_] == '/' || s_[p_] == '\\') {
        char delim = s_[p_] == '\\' ? s_[++p_] : '/';
        p_++;
        auto re = delimited(delim);
        a.kind = Address::Pattern;
        a.re = std::make_shared<Regex>(re, reFlags);
        if (!a.re->ok()) error("bad regex: " + re);
    }
    return a;
}

void Sed::parse(const std::string &script) {
    s_ = script;
    p_ = 0;
    while (true) {
        while (p_ < s_.size() && (std::isspace(static_cast<unsigned char>(s_[p_])) || s_[p_] == ';')) p_++;
        if (p_ >= s_.size()) break;
        if (s_[p_] == '#') {
            while (p_ < s_.size() && s_[p_] != '\n') p_++;
            continue;
        }
        Command c;
        c.a1 = address();
        if (c.a1.kind != Address::None && p_ < s_.size() && s_[p_] == ',') {
            p_++;
            c.a2 = address();
            if (c.a2.kind == Address::None) error("unexpected `,'");
        }
        skipSpace();
        if (p_ < s_.size() && s_[p_] == '!') {
            c.negate = true;
            p_++;
            skipSpace();
        }
        if (p_ >= s_.size()) error("missing command");
        c.cmd = s_[p_++];
        switch (c.cmd) {
            case '{': case '}': case '=': case 'd': case 'D': case 'p': case 'P': case 'n': case 'N': case 'g': case 'G':
            case 'h': case 'H': case 'x': case 'l': case 'z':
                break;
            case 'q': case 'Q':
                skipSpace();
                while (p_ < s_.size() && std::isdigit(static_cast<unsigned char>(s_[p_]))) p_++;
                break;
            case 'a': case 'i': case 'c': {
                skipSpace();
                if (p_ < s_.size() && s_[p_] == '\\') {
                    p_++;
                    if (p_ < s_.size() && s_[p_] == '\n') p_++;
                }
                std::string text;
                while (p_ < s_.size() && s_[p_] != '\n') {
                    if (s_[p_] == '\\' && p_ + 1 < s_.size()) {
                        p_++;
                        text += s_[p_] == 'n' ? '\n' : s_[p_] == 't' ? '\t' : s_[p_];
                        p_++;
                        continue;
                    }
                    text += s_[p_++];
                }
                c.text = text;
                break;
            }
            case ':': case 'b': case 't': case 'T': {
                skipSpace();
                std::string label;
                while (p_ < s_.size() && s_[p_] != '\n' && s_[p_] != ';' && s_[p_] != '}') label += s_[p_++];
                while (!label.empty() && label.back() == ' ') label.pop_back();
                c.text = label;
                if (c.cmd == ':' && label.empty()) error("\":\" lacks a label");
                break;
            }
            case 'r': case 'w': {
                skipSpace();
                std::string file;
                while (p_ < s_.size() && s_[p_] != '\n') file += s_[p_++];
                c.text = file;
                break;
            }
            case 's': {
                if (p_ >= s_.size()) error("unterminated `s' command");
                char delim = s_[p_++];
                auto re = delimited(delim);
                c.replacement = delimited(delim);
                int flags = reFlags;
                while (p_ < s_.size() && s_[p_] != ';' && s_[p_] != '\n' && s_[p_] != '}') {
                    char f = s_[p_++];
                    if (f == 'g') c.global = true;
                    else if (f == 'p') c.print = true;
                    else if (f == 'i' || f == 'I') flags |= REG_ICASE;
                    else if (std::isdigit(static_cast<unsigned char>(f))) {
                        c.nth = f - '0';
                        while (p_ < s_.size() && std::isdigit(static_cast<unsigned char>(s_[p_]))) c.nth = c.nth * 10 + (s_[p_++] - '0');
                    } else if (f == 'w') {
                        skipSpace();
                        while (p_ < s_.size() && s_[p_] != '\n') c.writeFile += s_[p_++];
                    } else if (f != ' ') {
                        error(std::string("unknown option to `s': ") + f);
                    }
                }
                c.re = std::make_shared<Regex>(re, flags);
                if (!c.re->ok()) error("bad regex: " + re);
                break;
            }
            case 'y': {
                char delim = s_[p_++];
                c.from = delimited(delim);
                c.to = delimited(delim);
                if (c.from.size() != c.to.size()) error("strings for `y' command are different lengths");
                break;
            }
            default:
                error(std::string("unknown command: `") + c.cmd + "'");
        }
        program.push_back(std::move(c));
    }
}

void Sed::link() {
    std::vector<size_t> open;
    std::map<std::string, size_t> labels;
    for (size_t i = 0; i < program.size(); i++) {
        if (program[i].cmd == '{') open.push_back(i);
        if (program[i].cmd == '}') {
            if (open.empty()) die("unexpected `}'");
            program[open.back()].jump = i;
            open.pop_back();
        }
        if (program[i].cmd == ':') labels[program[i].text] = i;
    }
    if (!open.empty()) die("unmatched `{'");
    for (auto &c : program) {
        if (c.cmd == 'b' || c.cmd == 't' || c.cmd == 'T') {
            if (c.text.empty()) {
                c.jump = program.size();
            } else {
                auto it = labels.find(c.text);
                if (it == labels.end()) die("can't find label for jump to `" + c.text + "'");
                c.jump = it->second;
            }
        }
    }
}

bool Sed::addressMatches(const Address &a, const std::string &pattern, bool lastLine) const {
    switch (a.kind) {
        case Address::None: return true;
        case Address::Line: return lineNo_ == a.line;
        case Address::Last: return lastLine;
        case Address::Pattern: return a.re->matches(pattern);
    }
    return false;
}

bool Sed::matches(Command &c, const std::string &pattern, bool lastLine) {
    bool m;
    if (c.a1.kind == Address::None) {
        m = true;
    } else if (c.a2.kind == Address::None) {
        m = addressMatches(c.a1, pattern, lastLine);
    } else if (c.inRange) {
        m = true;
        bool end = c.a2.kind == Address::Line ? lineNo_ >= c.a2.line : addressMatches(c.a2, pattern, lastLine);
        if (end) c.inRange = false;
    } else {
        m = addressMatches(c.a1, pattern, lastLine);
        if (m) {
            bool endsHere = c.a2.kind == Address::Line ? c.a2.line <= lineNo_ : false;
            c.inRange = !endsHere && !(c.a2.kind == Address::Last && lastLine);
        }
    }
    return m != c.negate;
}

bool Sed::substitute(Command &c, std::string &pattern) {
    std::string out;
    size_t pos = 0;
    int count = 0;
    bool replaced = false;
    regmatch_t m[10];
    while (pos <= pattern.size()) {
        if (!c.re->search(pattern.c_str() + pos, m, 10, pos > 0 ? REG_NOTBOL : 0)) break;
        count++;
        size_t so = pos + static_cast<size_t>(m[0].rm_so), eo = pos + static_cast<size_t>(m[0].rm_eo);
        out.append(pattern, pos, so - pos);
        if (count >= c.nth) {
            for (size_t k = 0; k < c.replacement.size(); k++) {
                char ch = c.replacement[k];
                if (ch == '&') {
                    out.append(pattern, so, eo - so);
                } else if (ch == '\\' && k + 1 < c.replacement.size()) {
                    char n = c.replacement[++k];
                    if (n >= '0' && n <= '9') {
                        auto &g = m[n - '0'];
                        if (g.rm_so >= 0) out.append(pattern, pos + static_cast<size_t>(g.rm_so), static_cast<size_t>(g.rm_eo - g.rm_so));
                    } else {
                        out += n == 'n' ? '\n' : n == 't' ? '\t' : n;
                    }
                } else {
                    out += ch;
                }
            }
            replaced = true;
        } else {
            out.append(pattern, so, eo - so);
        }
        if (eo == so) {
            if (so < pattern.size()) out += pattern[so];
            pos = so + 1;
        } else {
            pos = eo;
        }
        if (replaced && !c.global) break;
    }
    if (!replaced) return false;
    if (pos < pattern.size()) out.append(pattern, pos, std::string::npos);
    pattern = out;
    return true;
}

void Sed::run(const std::string &input, std::string &out, bool lastFile) {
    std::vector<std::string> lines;
    size_t start = 0;
    while (start < input.size()) {
        auto end = input.find('\n', start);
        if (end == std::string::npos) end = input.size();
        lines.push_back(input.substr(start, end - start));
        start = end + 1;
    }
    size_t next = 0;
    while (next < lines.size() && !quit) {
        std::string pattern = lines[next++];
        lineNo_++;
        std::string append;
        bool substituted = false, deleted = false;
        size_t pc = 0;
        while (pc < program.size()) {
            auto &c = program[pc];
            bool last = lastFile && next >= lines.size();
            if (c.cmd == '}' || c.cmd == ':') {
                pc++;
                continue;
            }
            if (!matches(c, pattern, last)) {
                pc = c.cmd == '{' ? c.jump + 1 : pc + 1;
                continue;
            }
            switch (c.cmd) {
                case '{': break;
                case '=': out += std::to_string(lineNo_) + "\n"; break;
                case 'p': out += pattern + "\n"; break;
                case 'P': out += pattern.substr(0, pattern.find('\n')) + "\n"; break;
                case 'l': {
                    for (unsigned char ch : pattern) {
                        if (ch == '\\') out += "\\\\";
                        else if (ch == '\n') out += "\\n";
                        else if (ch == '\t') out += "\\t";
                        else if (ch < 32 || ch >= 127) {
                            char buf[8];
                            std::snprintf(buf, sizeof(buf), "\\%03o", ch);
                            out += buf;
                        } else out += static_cast<char>(ch);
                    }
                    out += "$\n";
                    break;
                }
                case 'd': deleted = true; pc = program.size(); continue;
                case 'D': {
                    auto nl = pattern.find('\n');
                    if (nl == std::string::npos) {
                        deleted = true;
                        pc = program.size();
                        continue;
                    }
                    pattern = pattern.substr(nl + 1);
                    lineNo_--;
                    lines.insert(lines.begin() + static_cast<long>(next), pattern);
                    deleted = true;
                    pc = program.size();
                    continue;
                }
                case 'q': quit = true; pc = program.size(); continue;
                case 'Q': quit = true; deleted = true; pc = program.size(); continue;
                case 'a': append += c.text + "\n"; break;
                case 'i': out += c.text + "\n"; break;
                case 'c':
                    if (c.a2.kind == Address::None || !c.inRange) out += c.text + "\n";
                    deleted = true;
                    pc = program.size();
                    continue;
                case 'r':
                    if (auto t = readFile(c.text)) append += *t;
                    break;
                case 'w':
                    if (FILE *f = std::fopen(c.text.c_str(), "a")) {
                        std::fprintf(f, "%s\n", pattern.c_str());
                        std::fclose(f);
                    }
                    break;
                case 'n':
                    if (!quiet) out += pattern + "\n";
                    if (next >= lines.size()) {
                        deleted = true;
                        quit = lastFile;
                        pc = program.size();
                        continue;
                    }
                    pattern = lines[next++];
                    lineNo_++;
                    break;
                case 'N':
                    if (next >= lines.size()) {
                        pc = program.size();
                        continue;
                    }
                    pattern += "\n" + lines[next++];
                    lineNo_++;
                    break;
                case 'g': pattern = hold_; break;
                case 'G': pattern += "\n" + hold_; break;
                case 'h': hold_ = pattern; break;
                case 'H': hold_ += "\n" + pattern; break;
                case 'x': std::swap(pattern, hold_); break;
                case 'z': pattern.clear(); break;
                case 'y':
                    for (auto &ch : pattern) {
                        auto k = c.from.find(ch);
                        if (k != std::string::npos) ch = c.to[k];
                    }
                    break;
                case 's':
                    if (substitute(c, pattern)) {
                        substituted = true;
                        if (c.print) out += pattern + "\n";
                        if (!c.writeFile.empty()) {
                            if (FILE *f = std::fopen(c.writeFile.c_str(), "a")) {
                                std::fprintf(f, "%s\n", pattern.c_str());
                                std::fclose(f);
                            }
                        }
                    }
                    break;
                case 'b': pc = c.jump; continue;
                case 't':
                    if (substituted) {
                        substituted = false;
                        pc = c.jump;
                        continue;
                    }
                    break;
                case 'T':
                    if (!substituted) {
                        pc = c.jump;
                        continue;
                    }
                    substituted = false;
                    break;
                default: break;
            }
            pc++;
        }
        if (!deleted && !quiet) out += pattern + "\n";
        out += append;
    }
}

int cmdSed(Args &a) {
    Sed sed;
    bool inPlace = false;
    std::vector<std::string> scripts;
    size_t i = 1;
    for (; i < a.size() && isOption(a[i]); i++) {
        auto &o = a[i];
        if (o == "-e" && i + 1 < a.size()) scripts.push_back(a[++i]);
        else if (o == "-f" && i + 1 < a.size()) {
            auto t = readFile(a[++i]);
            if (!t) return warnErrno(a[i]);
            scripts.push_back(*t);
        } else if (o == "--") {
            i++;
            break;
        } else {
            for (char f : o.substr(1)) {
                if (f == 'n') sed.quiet = true;
                else if (f == 'i') inPlace = true;
                else if (f == 'E' || f == 'r') sed.reFlags |= REG_EXTENDED;
                else if (f == 's') continue;
                else die(std::string("unknown option -") + f + "\nusage: sed [-n] [-E] [-i] [-e SCRIPT | SCRIPT] [FILE...]");
            }
        }
    }
    if (scripts.empty()) {
        if (i >= a.size()) die("usage: sed [-n] [-E] [-i] [-e SCRIPT | SCRIPT] [FILE...]");
        scripts.push_back(a[i++]);
    }
    std::string all;
    for (auto &s : scripts) all += s + "\n";
    sed.parse(all);
    sed.link();
    std::vector<std::string> files(a.begin() + static_cast<long>(i), a.end());
    if (files.empty()) {
        if (inPlace) die("no input files for -i");
        files.push_back("-");
    }
    int status = 0;
    if (inPlace) {
        for (auto &f : files) {
            auto t = readFile(f);
            if (!t) {
                status = warnErrno(f);
                continue;
            }
            std::string out;
            sed.quit = false;
            sed.run(*t, out, true);
            if (!writeFile(f, out)) status = warnErrno(f);
        }
        return status;
    }
    // Without -i the files form one stream ($ is the last line of the last file).
    for (size_t k = 0; k < files.size() && !sed.quit; k++) {
        auto t = readFile(files[k]);
        if (!t) {
            status = warnErrno(files[k]);
            continue;
        }
        std::string out;
        sed.run(*t, out, k + 1 == files.size());
        std::fwrite(out.data(), 1, out.size(), stdout);
    }
    return status;
}

} // namespace

void registerSed(std::map<std::string, tu::Applet> &table) {
    table["sed"] = cmdSed;
}
