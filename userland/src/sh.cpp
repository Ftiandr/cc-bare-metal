// sh - the CC: Bare Metal shell.
//
// A small POSIX-flavoured shell: pipelines, redirections, && || ; &, if/while/until/for, functions, $(...), $((...)),
// variables, aliases, globbing, job control and an interactive line editor with history and tab completion.
#include <baremetal.hpp>

#include <fnmatch.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <functional>
#include <glob.h>
#include <poll.h>
#include <pwd.h>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

extern char **environ;

namespace {

using Strings = std::vector<std::string>;

void err(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

void err(const char *fmt, ...) {
    std::fputs("sh: ", stderr);
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(stderr, fmt, ap);
    va_end(ap);
    std::fputc('\n', stderr);
}

bool startsWith(const std::string &s, std::string_view prefix) {
    return s.size() >= prefix.size() && std::string_view(s).substr(0, prefix.size()) == prefix;
}

std::string shellQuote(const std::string &s) {
    std::string out = "'";
    for (char c : s) {
        if (c == '\'') out += "'\\''";
        else out += c;
    }
    return out + "'";
}

std::optional<std::string> readFile(const std::string &path) {
    FILE *f = std::fopen(path.c_str(), "r");
    if (!f) return std::nullopt;
    std::string out;
    char buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    std::fclose(f);
    return out;
}

// ==== Shell state ===============================================================================================

struct Var {
    std::string value;
    bool exported = false;
};

struct Node;
using NodePtr = std::shared_ptr<Node>;

struct Job {
    int id = 0;
    int pgid = 0;
    std::vector<int> pids;
    std::string command;
};

struct Shell {
    std::map<std::string, Var> vars;
    std::map<std::string, std::string> aliases;
    std::map<std::string, NodePtr> functions;
    std::map<std::string, std::string> functionSource; // their text, for child shells
    std::vector<Job> jobs;
    Strings positional;
    std::string scriptName = "sh";
    int lastStatus = 0;
    bool interactive = false;
    int shellPgid = 0;
    int nextJob = 1;
    int lastBackground = 0; // $!
    int breakLevel = 0;
    int continueLevel = 0;
    int loopDepth = 0;
    bool returnFlag = false;
    int exitCode = -1; // >= 0 once "exit" ran
    std::vector<std::string> history;
};

Shell sh;

const std::string *varGet(const std::string &name) {
    auto it = sh.vars.find(name);
    return it == sh.vars.end() ? nullptr : &it->second.value;
}

std::string varGetOr(const std::string &name, const std::string &def) {
    auto *v = varGet(name);
    return v ? *v : def;
}

void varSet(const std::string &name, const std::string &value, bool exported = false) {
    auto &v = sh.vars[name];
    v.value = value;
    if (exported) v.exported = true;
    if (v.exported) setenv(name.c_str(), value.c_str(), 1);
}

void varUnset(const std::string &name) {
    sh.vars.erase(name);
    unsetenv(name.c_str());
}

bool validName(std::string_view s) {
    if (s.empty() || !(std::isalpha(static_cast<unsigned char>(s[0])) || s[0] == '_')) return false;
    return std::all_of(s.begin() + 1, s.end(), [](char c) { return std::isalnum(static_cast<unsigned char>(c)) || c == '_'; });
}

// ==== Lexer =====================================================================================================

enum class Tok { Word, Op, Newline, End };

struct Token {
    Tok type;
    std::string text; // raw word (quotes kept) or operator
    int ioNumber = -1; // for redirections like 2> : the fd
    size_t start = 0, end = 0; // where it is in the source (a compound command's text runs in a child shell)
};

enum class LexResult { Ok, Incomplete };

// Splits source into tokens. Incomplete means an unclosed quote or $( - more input is needed.
LexResult lex(std::string_view s, std::vector<Token> &out) {
    static const char *const ops[] = {"&>>", "&>", ">>", ">&", "<&", "&&", "||", ";;", "|", "&", ";", "<", ">", "(", ")"};
    size_t i = 0;
    auto at = [&](size_t k) { return k < s.size() ? s[k] : '\0'; };
    size_t tokenStart = 0;
    // every token pushed records [tokenStart, i)
    auto push = [&](Token t) {
        t.start = tokenStart;
        t.end = i;
        out.push_back(std::move(t));
    };
    while (i < s.size()) {
        char c = s[i];
        tokenStart = i;
        if (c == ' ' || c == '\t') {
            i++;
            continue;
        }
        if (c == '\\' && at(i + 1) == '\n') {
            i += 2;
            continue;
        }
        if (c == '#') {
            while (i < s.size() && s[i] != '\n') i++;
            continue;
        }
        if (c == '\n') {
            i++;
            push({Tok::Newline, "\n"});
            continue;
        }
        int io = -1;
        if (std::isdigit(static_cast<unsigned char>(c))) {
            size_t q = i;
            while (std::isdigit(static_cast<unsigned char>(at(q)))) q++;
            if (at(q) == '<' || at(q) == '>') {
                io = std::atoi(std::string(s.substr(i, q - i)).c_str());
                i = q;
                c = s[i];
            }
        }
        if (std::strchr("|&;<>()", c)) {
            for (auto *op : ops) {
                size_t n = std::strlen(op);
                if (s.substr(i, n) == op) {
                    i += n;
                    push({Tok::Op, op, io});
                    break;
                }
            }
            continue;
        }
        std::string w;
        while (i < s.size() && !std::strchr(" \t\n|&;<>()", s[i])) {
            c = s[i];
            if (c == '\\') {
                w += s[i++];
                if (i < s.size()) w += s[i++];
            } else if (c == '\'') {
                w += s[i++];
                while (i < s.size() && s[i] != '\'') w += s[i++];
                if (i >= s.size()) return LexResult::Incomplete;
                w += s[i++];
            } else if (c == '"') {
                w += s[i++];
                while (i < s.size() && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.size()) w += s[i++];
                    w += s[i++];
                }
                if (i >= s.size()) return LexResult::Incomplete;
                w += s[i++];
            } else if (c == '$' && at(i + 1) == '(') {
                int depth = 1;
                w += s[i++];
                w += s[i++];
                while (i < s.size() && depth > 0) {
                    if (s[i] == '(') depth++;
                    else if (s[i] == ')') depth--;
                    w += s[i++];
                }
                if (depth > 0) return LexResult::Incomplete;
            } else if (c == '`') {
                w += s[i++];
                while (i < s.size() && s[i] != '`') w += s[i++];
                if (i >= s.size()) return LexResult::Incomplete;
                w += s[i++];
            } else {
                w += s[i++];
            }
        }
        push({Tok::Word, std::move(w)});
    }
    tokenStart = i;
    push({Tok::End, ""});
    return LexResult::Ok;
}

// ==== Syntax tree and parser ====================================================================================

enum class RedirType { In, Out, Append, DupOut, DupIn, Both, BothAppend };

struct Redir {
    RedirType type;
    int fd;
    std::string target;
};

enum class NodeType { Command, Pipeline, And, Or, List, Background, If, While, Until, For, Case, Group, Function, Not };

struct Node {
    NodeType type;
    Strings words;                 // Command: words; For: the list; Case: the subject; case item: its patterns
    std::vector<Redir> redirs;     // Command, Group, compound commands
    NodePtr a, b, c;               // children (condition / body / else ...)
    std::vector<NodePtr> children; // Pipeline: commands; List: items
    std::string name;              // For variable, Function name
    std::string source;            // compound commands in a pipeline, functions: their text (for a child shell)

    explicit Node(NodeType t) : type(t) {}
};

class Parser {
public:
    explicit Parser(std::vector<Token> tokens, std::string_view src = {}) : t_(std::move(tokens)), src_(src) {}

    // The source text of tokens [from, to)
    std::string sourceOf(size_t from, size_t to) const {
        if (src_.empty() || to <= from || to > t_.size()) return "";
        auto a = t_[from].start, b = t_[to - 1].end;
        return b > a && b <= src_.size() ? std::string(src_.substr(a, b - a)) : "";
    }

    bool failed() const { return failed_; }
    const Token &peek() const { return t_[pos_]; }

    NodePtr list(std::initializer_list<const char *> stops = {}) {
        auto seq = std::make_shared<Node>(NodeType::List);
        skipNewlines();
        while (!failed_ && !atStop(stops)) {
            auto item = andOr();
            // '&' runs the item in the background and, like ';', separates it from the next one: "a & b"
            bool separated = false;
            if (isOp(peek(), "&")) {
                auto bg = std::make_shared<Node>(NodeType::Background);
                bg->a = item;
                item = bg;
                next();
                separated = true;
            }
            seq->children.push_back(item);
            if (separated || peek().type == Tok::Newline || isOp(peek(), ";")) skipNewlines();
            else if (!atStop(stops)) break;
        }
        return seq;
    }

private:
    std::vector<Token> t_;
    size_t pos_ = 0;
    std::string_view src_;
    bool failed_ = false;

    const Token &next() {
        const Token &k = t_[pos_];
        if (k.type != Tok::End) pos_++;
        return k;
    }

    static bool isOp(const Token &k, std::string_view op) { return k.type == Tok::Op && k.text == op; }
    static bool isWord(const Token &k, std::string_view w) { return k.type == Tok::Word && k.text == w; }

    void skipNewlines() {
        while (peek().type == Tok::Newline || isOp(peek(), ";")) next();
    }

    bool atStop(std::initializer_list<const char *> stops) const {
        if (peek().type == Tok::End) return true;
        for (auto *s : stops) {
            if (isWord(peek(), s) || isOp(peek(), s)) return true;
        }
        return false;
    }

    void error(const std::string &message) {
        if (!failed_) err("syntax error: %s", message.c_str());
        failed_ = true;
    }

    bool expectWord(const char *w) {
        skipNewlines();
        if (!isWord(peek(), w)) {
            error(std::string("expected '") + w + "' near '" + peek().text + "'");
            return false;
        }
        next();
        return true;
    }

    NodePtr compound() {
        const Token &k = peek();
        if (isWord(k, "if")) {
            next();
            NodePtr top;
            Node *last = nullptr;
            for (;;) {
                auto n = std::make_shared<Node>(NodeType::If);
                n->a = list({"then"});
                if (!expectWord("then")) return top ? top : n;
                n->b = list({"elif", "else", "fi"});
                if (last) last->c = n;
                else top = n;
                last = n.get();
                skipNewlines();
                if (isWord(peek(), "elif")) {
                    next();
                    continue;
                }
                if (isWord(peek(), "else")) {
                    next();
                    n->c = list({"fi"});
                }
                expectWord("fi");
                return top;
            }
        }
        if (isWord(k, "while") || isWord(k, "until")) {
            auto n = std::make_shared<Node>(isWord(k, "while") ? NodeType::While : NodeType::Until);
            next();
            n->a = list({"do"});
            if (!expectWord("do")) return n;
            n->b = list({"done"});
            expectWord("done");
            return n;
        }
        if (isWord(k, "for")) {
            auto n = std::make_shared<Node>(NodeType::For);
            next();
            if (peek().type != Tok::Word) {
                error("bad for loop");
                return n;
            }
            n->name = next().text;
            skipNewlines();
            if (isWord(peek(), "in")) {
                next();
                while (peek().type == Tok::Word) n->words.push_back(next().text);
            } else {
                n->words.push_back("\"$@\"");
            }
            if (!expectWord("do")) return n;
            n->b = list({"done"});
            expectWord("done");
            return n;
        }
        if (isWord(k, "case")) {
            // case WORD in [(]PATTERN[|PATTERN]...) LIST ;; ... esac
            auto n = std::make_shared<Node>(NodeType::Case);
            next();
            if (peek().type != Tok::Word) {
                error("bad case");
                return n;
            }
            n->words.push_back(next().text);
            if (!expectWord("in")) return n;
            for (;;) {
                while (peek().type == Tok::Newline) next();
                if (isWord(peek(), "esac")) {
                    next();
                    break;
                }
                if (peek().type == Tok::End) {
                    error("expected 'esac'");
                    break;
                }
                auto item = std::make_shared<Node>(NodeType::Group);
                if (isOp(peek(), "(")) next();
                for (;;) {
                    if (peek().type != Tok::Word) {
                        error("bad case pattern near '" + peek().text + "'");
                        return n;
                    }
                    item->words.push_back(next().text);
                    if (!isOp(peek(), "|")) break;
                    next();
                }
                if (!isOp(peek(), ")")) {
                    error("expected ')' after a case pattern");
                    return n;
                }
                next();
                item->a = list({";;", "esac"});
                n->children.push_back(item);
                if (isOp(peek(), ";;")) next();
            }
            return n;
        }
        if (isWord(k, "{")) {
            auto n = std::make_shared<Node>(NodeType::Group);
            next();
            n->a = list({"}"});
            expectWord("}");
            return n;
        }
        if (isOp(k, "(")) {
            auto n = std::make_shared<Node>(NodeType::Group);
            next();
            n->a = list({")"});
            skipNewlines();
            if (!isOp(peek(), ")")) error("expected ')'");
            else next();
            return n;
        }
        return nullptr;
    }

    bool redirection(std::vector<Redir> &out) {
        const Token &k = peek();
        if (k.type != Tok::Op) return false;
        static const std::pair<const char *, std::pair<RedirType, int>> table[] = {
            {"<", {RedirType::In, 0}},       {">", {RedirType::Out, 1}},     {">>", {RedirType::Append, 1}},
            {">&", {RedirType::DupOut, 1}},  {"<&", {RedirType::DupIn, 0}},  {"&>", {RedirType::Both, 1}},
            {"&>>", {RedirType::BothAppend, 1}},
        };
        for (auto &entry : table) {
            if (k.text != entry.first) continue;
            int fd = k.ioNumber >= 0 ? k.ioNumber : entry.second.second;
            next();
            if (peek().type != Tok::Word) {
                error("missing redirection target");
                return true;
            }
            out.push_back({entry.second.first, fd, next().text});
            return true;
        }
        return false;
    }

    NodePtr command() {
        if (auto n = compound()) {
            while (redirection(n->redirs)) {
            }
            return n;
        }
        // function definition: name () { ... }
        if (peek().type == Tok::Word && pos_ + 2 < t_.size() && isOp(t_[pos_ + 1], "(") && isOp(t_[pos_ + 2], ")")) {
            auto from = pos_;
            auto n = std::make_shared<Node>(NodeType::Function);
            n->name = next().text;
            next();
            next();
            skipNewlines();
            n->a = compound();
            if (!n->a) error("function body expected");
            n->source = sourceOf(from, pos_);
            return n;
        }
        auto n = std::make_shared<Node>(NodeType::Command);
        for (;;) {
            if (redirection(n->redirs)) continue;
            if (peek().type != Tok::Word) break;
            n->words.push_back(next().text);
        }
        if (n->words.empty() && n->redirs.empty()) {
            error("near '" + (peek().type == Tok::Newline ? std::string("newline") : peek().text) + "'");
            next();
        }
        return n;
    }

    NodePtr pipeline() {
        bool negate = false;
        if (isWord(peek(), "!")) {
            negate = true;
            next();
        }
        auto from = pos_;
        auto first = command();
        first->source = sourceOf(from, pos_);
        if (isOp(peek(), "|")) {
            auto p = std::make_shared<Node>(NodeType::Pipeline);
            p->children.push_back(first);
            while (isOp(peek(), "|") && !failed_) {
                next();
                while (peek().type == Tok::Newline) next();
                from = pos_;
                auto c = command();
                c->source = sourceOf(from, pos_);
                p->children.push_back(c);
            }
            first = p;
        }
        if (negate) {
            auto n = std::make_shared<Node>(NodeType::Not);
            n->a = first;
            first = n;
        }
        return first;
    }

    NodePtr andOr() {
        auto left = pipeline();
        while (!failed_ && (isOp(peek(), "&&") || isOp(peek(), "||"))) {
            auto n = std::make_shared<Node>(isOp(peek(), "&&") ? NodeType::And : NodeType::Or);
            next();
            while (peek().type == Tok::Newline) next();
            n->a = left;
            n->b = pipeline();
            left = n;
        }
        return left;
    }
};

// ==== Expansion =================================================================================================

std::string commandOutput(const std::string &cmd);

// $name, ${name}, ${name:-default}, ${#name}, $?, $$, $!, $#, $0-$9, $@, $*. `p` points after '$'.
std::string expandParam(std::string_view s, size_t &p) {
    std::string name;
    bool braced = false;
    if (p < s.size() && s[p] == '{') {
        braced = true;
        p++;
        while (p < s.size() && s[p] != '}') name += s[p++];
        if (p < s.size()) p++;
    } else if (p < s.size() && std::strchr("?$#!@*", s[p])) {
        name = s[p++];
    } else if (p < s.size() && std::isdigit(static_cast<unsigned char>(s[p]))) {
        name = s[p++];
    } else {
        while (p < s.size() && (std::isalnum(static_cast<unsigned char>(s[p])) || s[p] == '_')) name += s[p++];
    }
    if (name.empty()) return "$";
    if (braced && name.size() > 1 && name[0] == '#') {
        auto *v = varGet(name.substr(1));
        return std::to_string(v ? v->size() : 0);
    }
    if (name == "?") return std::to_string(sh.lastStatus);
    if (name == "$") return std::to_string(cc::pid());
    if (name == "!") return sh.lastBackground ? std::to_string(sh.lastBackground) : "";
    if (name == "#") return std::to_string(sh.positional.size());
    if (name == "0") return sh.scriptName;
    if (std::isdigit(static_cast<unsigned char>(name[0]))) {
        size_t i = static_cast<size_t>(std::atoi(name.c_str()));
        return i >= 1 && i <= sh.positional.size() ? sh.positional[i - 1] : "";
    }
    if (name == "@" || name == "*") {
        std::string out;
        for (size_t i = 0; i < sh.positional.size(); i++) {
            if (i) out += ' ';
            out += sh.positional[i];
        }
        return out;
    }
    auto colon = name.find(":-");
    auto assign = name.find(":=");
    if (colon != std::string::npos) {
        auto *v = varGet(name.substr(0, colon));
        return v && !v->empty() ? *v : name.substr(colon + 2);
    }
    if (assign != std::string::npos) {
        auto key = name.substr(0, assign);
        auto *v = varGet(key);
        if (v && !v->empty()) return *v;
        varSet(key, name.substr(assign + 2));
        return name.substr(assign + 2);
    }
    auto *v = varGet(name);
    return v ? *v : "";
}

// ---- Arithmetic: $(( expr )) with C integer operators; bare names read variables ----

class Arith {
public:
    explicit Arith(std::string_view s) : s_(s) {}

    std::optional<long> evaluate(std::string &error) {
        long v = expr(0);
        ws();
        if (divZero_) error = "division by zero";
        else if (bad_ || p_ != s_.size()) error = "syntax error in '" + std::string(s_) + "'";
        if (!error.empty()) return std::nullopt;
        return v;
    }

private:
    std::string_view s_;
    size_t p_ = 0;
    bool bad_ = false;
    bool divZero_ = false;

    char at() const { return p_ < s_.size() ? s_[p_] : '\0'; }

    void ws() {
        while (std::isspace(static_cast<unsigned char>(at()))) p_++;
    }

    long primary() {
        ws();
        char c = at();
        if (c == '(') {
            p_++;
            long v = expr(0);
            ws();
            if (at() == ')') p_++;
            else bad_ = true;
            return v;
        }
        if (c == '-') { p_++; return -primary(); }
        if (c == '+') { p_++; return primary(); }
        if (c == '!') { p_++; return !primary(); }
        if (c == '~') { p_++; return ~primary(); }
        if (std::isdigit(static_cast<unsigned char>(c))) {
            std::string digits;
            while (std::isalnum(static_cast<unsigned char>(at()))) digits += s_[p_++];
            return std::strtol(digits.c_str(), nullptr, 0);
        }
        if (std::isalpha(static_cast<unsigned char>(c)) || c == '_') {
            std::string name;
            while (std::isalnum(static_cast<unsigned char>(at())) || at() == '_') name += s_[p_++];
            auto *v = varGet(name);
            return v ? std::strtol(v->c_str(), nullptr, 0) : 0;
        }
        bad_ = true;
        return 0;
    }

    // Binary operators, higher precedence binds tighter.
    int op(std::string &text) const {
        static const std::pair<const char *, int> ops[] = {
            {"||", 1}, {"&&", 2}, {"==", 6}, {"!=", 6}, {"<=", 7}, {">=", 7}, {"<<", 8}, {">>", 8},
            {"|", 3}, {"^", 4}, {"&", 5}, {"<", 7}, {">", 7}, {"+", 9}, {"-", 9}, {"*", 10}, {"/", 10}, {"%", 10},
        };
        for (auto &[o, prec] : ops) {
            if (s_.substr(p_, std::strlen(o)) == o) {
                text = o;
                return prec;
            }
        }
        return 0;
    }

    long expr(int minPrec) {
        long left = primary();
        for (;;) {
            ws();
            std::string o;
            int prec = op(o);
            if (prec == 0 || prec <= minPrec) return left;
            p_ += o.size();
            long right = expr(prec);
            if (o == "||") left = left || right;
            else if (o == "&&") left = left && right;
            else if (o == "==") left = left == right;
            else if (o == "!=") left = left != right;
            else if (o == "<=") left = left <= right;
            else if (o == ">=") left = left >= right;
            else if (o == "<<") left = left << right;
            else if (o == ">>") left = left >> right;
            else if (o == "|") left = left | right;
            else if (o == "^") left = left ^ right;
            else if (o == "&") left = left & right;
            else if (o == "<") left = left < right;
            else if (o == ">") left = left > right;
            else if (o == "+") left = left + right;
            else if (o == "-") left = left - right;
            else if (o == "*") left = left * right;
            else if (right == 0) {
                divZero_ = true;
                return 0;
            } else if (o == "/") left = left / right;
            else left = left % right;
        }
    }
};

std::string expandCommand(std::string_view s, size_t &p);

// `p` points at "$(("; returns the value and moves past "))".
std::string expandArith(std::string_view s, size_t &p) {
    size_t start = p + 3, q = start;
    int depth = 0;
    while (q < s.size() && !(depth == 0 && s[q] == ')' && q + 1 < s.size() && s[q + 1] == ')')) {
        if (s[q] == '(') depth++;
        else if (s[q] == ')') depth--;
        q++;
    }
    std::string expr;
    auto inner = s.substr(start, q - start);
    for (size_t i = 0; i < inner.size();) {
        if (inner.compare(i, 3, "$((") == 0) {
            expr += expandArith(inner, i);
        } else if (inner.compare(i, 2, "$(") == 0 || inner[i] == '`') {
            auto out = expandCommand(inner, i);
            while (!out.empty() && (out.back() == '\n' || out.back() == ' ')) out.pop_back();
            expr += out;
        } else if (inner[i] == '$') {
            i++;
            expr += expandParam(inner, i);
        } else {
            expr += inner[i++];
        }
    }
    p = q < s.size() ? q + 2 : q;
    std::string error;
    auto v = Arith(expr).evaluate(error);
    if (!v) err("arithmetic: %s", error.c_str());
    return std::to_string(v.value_or(0));
}

// Unquoted expansion results are field-split; \x01 marks the split points.
constexpr char FIELD_SEP = '\x01';

// `p` points at "$(" or "`"; runs the command and moves past it.
std::string expandCommand(std::string_view s, size_t &p) {
    std::string cmd;
    if (s[p] == '`') {
        size_t start = ++p;
        while (p < s.size() && s[p] != '`') p++;
        cmd = s.substr(start, p - start);
        if (p < s.size()) p++;
    } else {
        size_t start = p + 2;
        int depth = 1;
        p += 2;
        while (p < s.size() && depth) {
            if (s[p] == '(') depth++;
            else if (s[p] == ')') depth--;
            if (depth) p++;
        }
        cmd = s.substr(start, p - start);
        if (p < s.size()) p++;
    }
    return commandOutput(cmd);
}

// An unquoted expansion: split on whitespace; its backslashes are literal (POSIX does not remove them from expansion
// results), its glob characters take part in pathname expansion.
void appendSplit(std::string &cur, const std::string &value, bool &hasGlob) {
    for (char c : value) {
        if (std::isspace(static_cast<unsigned char>(c))) {
            cur += FIELD_SEP;
        } else if (c == '\\') {
            cur += "\\\\";
        } else {
            if (std::strchr("*?[", c)) hasGlob = true;
            cur += c;
        }
    }
}

// A quoted expansion: literal, protected from both backslash removal and globbing.
void appendQuoted(std::string &cur, const std::string &value) {
    for (char c : value) {
        if (std::strchr("*?[\\", c)) cur += '\\';
        cur += c;
    }
}

// Expand one raw word into fields: quotes removed, unquoted expansions split on whitespace, unquoted glob
// characters matched against the file system.
void expandWord(const std::string &w, Strings &fields, bool allowGlob) {
    if (w == "\"$@\"") {
        for (auto &a : sh.positional) fields.push_back(a);
        return;
    }
    std::string cur;
    bool quotedAny = false, hasGlob = false, hasField = false;
    size_t p = 0;
    std::string_view s = w;
    if (!s.empty() && s[0] == '~' && (s.size() == 1 || s[1] == '/')) {
        appendQuoted(cur, varGetOr("HOME", "/"));
        p = 1;
    } else if (!s.empty() && s[0] == '~') {
        // ~name: that user's home directory
        auto end = s.find('/');
        auto name = std::string(s.substr(1, end == std::string_view::npos ? std::string_view::npos : end - 1));
        if (auto *pw = getpwnam(name.c_str())) {
            appendQuoted(cur, pw->pw_dir);
            p = end == std::string_view::npos ? s.size() : end;
        }
    }
    while (p < s.size()) {
        char c = s[p];
        if (c == '\\') {
            p++;
            if (p < s.size()) {
                if (std::strchr("*?[\\", s[p])) cur += '\\';
                cur += s[p++];
            }
        } else if (c == '\'') {
            quotedAny = true;
            p++;
            while (p < s.size() && s[p] != '\'') {
                if (std::strchr("*?[\\", s[p])) cur += '\\';
                cur += s[p++];
            }
            if (p < s.size()) p++;
        } else if (c == '"') {
            quotedAny = true;
            p++;
            while (p < s.size() && s[p] != '"') {
                if (s[p] == '\\' && p + 1 < s.size() && std::strchr("$`\"\\\n", s[p + 1])) {
                    if (s[p + 1] == '\\') cur += '\\';
                    cur += s[p + 1];
                    p += 2;
                } else if (s[p] == '$' && s.substr(p, 3) == "$((") {
                    appendQuoted(cur, expandArith(s, p));
                } else if (s[p] == '$' && p + 1 < s.size() && s[p + 1] == '(') {
                    appendQuoted(cur, expandCommand(s, p));
                } else if (s[p] == '`') {
                    appendQuoted(cur, expandCommand(s, p));
                } else if (s[p] == '$') {
                    p++;
                    appendQuoted(cur, expandParam(s, p));
                } else {
                    if (std::strchr("*?[\\", s[p])) cur += '\\';
                    cur += s[p++];
                }
            }
            if (p < s.size()) p++;
        } else if (c == '$' && s.substr(p, 3) == "$((") {
            appendSplit(cur, expandArith(s, p), hasGlob);
            hasField = true;
        } else if ((c == '$' && p + 1 < s.size() && s[p + 1] == '(') || c == '`') {
            appendSplit(cur, expandCommand(s, p), hasGlob);
            hasField = true;
        } else if (c == '$') {
            p++;
            appendSplit(cur, expandParam(s, p), hasGlob);
            hasField = true;
        } else {
            if (std::strchr("*?[", c)) hasGlob = true;
            cur += c;
            p++;
        }
    }

    Strings parts;
    if (hasField) {
        size_t start = 0;
        for (size_t i = 0; i <= cur.size(); i++) {
            if (i == cur.size() || cur[i] == FIELD_SEP) {
                if (i > start || (quotedAny && i == cur.size() && parts.empty())) parts.push_back(cur.substr(start, i - start));
                start = i + 1;
            }
        }
    } else {
        parts.push_back(cur);
    }
    for (auto &part : parts) {
        if (allowGlob && hasGlob) {
            glob_t g;
            if (glob(part.c_str(), 0, nullptr, &g) == 0) {
                for (size_t j = 0; j < g.gl_pathc; j++) fields.emplace_back(g.gl_pathv[j]);
                globfree(&g);
                continue;
            }
        }
        // remove the backslashes that protected quoted glob characters
        std::string clean;
        for (size_t i = 0; i < part.size(); i++) {
            if (part[i] == '\\' && i + 1 < part.size()) i++;
            clean += part[i];
        }
        fields.push_back(std::move(clean));
    }
}

std::string expandSingle(const std::string &w) {
    Strings fields;
    expandWord(w, fields, false);
    std::string out;
    for (size_t i = 0; i < fields.size(); i++) {
        if (i) out += ' ';
        out += fields[i];
    }
    return out;
}

// ==== Execution =================================================================================================

int runNode(const NodePtr &n);
int runString(const std::string &src);

// $(...) as a separate sh process: only used if the subshell's output file cannot be created.
std::string spawnOutput(const std::string &cmd) {
    int32_t fds[2];
    if (cc_pipe(fds) != 0) return "";
    std::fflush(stdout);
    cc::SpawnOptions options;
    options.out = fds[1];
    int pid = cc::spawn({"/bin/sh", "-c", cmd}, options);
    close(fds[1]);
    if (pid < 0) {
        close(fds[0]);
        return "";
    }
    std::string out;
    char buf[1024];
    ssize_t n;
    while ((n = read(fds[0], buf, sizeof(buf))) > 0) out.append(buf, static_cast<size_t>(n));
    close(fds[0]);
    int status = 0;
    cc::wait(pid, &status);
    sh.lastStatus = status;
    while (!out.empty() && out.back() == '\n') out.pop_back();
    return out;
}

// Descriptors opened for a command, closed when it is done.
struct OpenedFiles {
    std::vector<int> fds;
    ~OpenedFiles() {
        for (int fd : fds) close(fd);
    }
};

// Open redirection targets; stdio[0..2] receive the resulting descriptors.
bool applyRedirs(const std::vector<Redir> &redirs, int stdio[3], OpenedFiles &opened) {
    for (auto &r : redirs) {
        auto target = expandSingle(r.target);
        int fd = -1;
        switch (r.type) {
            case RedirType::In: fd = open(target.c_str(), O_RDONLY); break;
            case RedirType::Out:
            case RedirType::Both: fd = open(target.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644); break;
            case RedirType::Append:
            case RedirType::BothAppend: fd = open(target.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644); break;
            case RedirType::DupOut:
            case RedirType::DupIn: {
                int src = std::atoi(target.c_str());
                if (r.fd >= 0 && r.fd < 3) stdio[r.fd] = src >= 0 && src < 3 ? stdio[src] : src;
                continue;
            }
        }
        if (fd < 0) {
            err("%s: %s", target.c_str(), std::strerror(errno));
            return false;
        }
        opened.fds.push_back(fd);
        if (r.type == RedirType::Both || r.type == RedirType::BothAppend) {
            stdio[1] = fd;
            stdio[2] = fd;
        } else if (r.fd >= 0 && r.fd < 3) {
            stdio[r.fd] = fd;
        }
    }
    return true;
}

// Temporarily replaces fds 0-2 (for builtins, functions and compound commands that run in-process).
class StdioSwap {
public:
    explicit StdioSwap(const int stdio[3]) {
        std::fflush(stdout);
        std::fflush(stderr);
        for (int i = 0; i < 3; i++) {
            saved_[i] = -1;
            if (stdio[i] != i) {
                saved_[i] = cc_dup(i);
                cc_dup2(stdio[i], i);
            }
        }
    }
    ~StdioSwap() {
        std::fflush(stdout);
        std::fflush(stderr);
        for (int i = 0; i < 3; i++) {
            if (saved_[i] >= 0) {
                cc_dup2(saved_[i], i);
                close(saved_[i]);
            }
        }
    }
    StdioSwap(const StdioSwap &) = delete;
    StdioSwap &operator=(const StdioSwap &) = delete;

private:
    int saved_[3];
};

// $(...): run the command in a subshell of this shell, like POSIX shells do (they fork): it sees all variables and
// functions, and nothing it changes (variables, functions, directory, exit) reaches us. The output goes through a
// temporary file in /tmp (no reader thread is needed, so any amount of output works).
std::string commandOutput(const std::string &cmd) {
    static int counter = 0;
    auto path = "/tmp/.sh-subst-" + std::to_string(cc::pid()) + "-" + std::to_string(counter++);
    int fd = open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return spawnOutput(cmd);

    auto savedVars = sh.vars;
    auto savedFunctions = sh.functions;
    auto savedAliases = sh.aliases;
    auto savedPositional = sh.positional;
    auto savedScript = sh.scriptName;
    auto savedInteractive = sh.interactive;
    auto savedLoop = sh.loopDepth;
    auto savedCwd = cc::cwd();
    sh.interactive = false;
    sh.breakLevel = sh.continueLevel = 0;
    sh.returnFlag = false;
    sh.exitCode = -1;
    {
        int stdio[3] = {0, fd, 2};
        StdioSwap swap(stdio);
        runString(cmd);
    }
    auto status = sh.exitCode >= 0 ? sh.exitCode : sh.lastStatus;
    sh.vars = std::move(savedVars);
    sh.functions = std::move(savedFunctions);
    sh.aliases = std::move(savedAliases);
    sh.positional = std::move(savedPositional);
    sh.scriptName = std::move(savedScript);
    sh.interactive = savedInteractive;
    sh.loopDepth = savedLoop;
    sh.breakLevel = sh.continueLevel = 0;
    sh.returnFlag = false;
    sh.exitCode = -1;
    if (cc::cwd() != savedCwd) cc::chdir(savedCwd);
    sh.lastStatus = status;

    std::string out;
    lseek(fd, 0, SEEK_SET);
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) out.append(buf, static_cast<size_t>(n));
    close(fd);
    unlink(path.c_str());
    while (!out.empty() && out.back() == '\n') out.pop_back();
    return out;
}


using Builtin = std::function<int(Strings &)>;
const std::map<std::string, Builtin> &builtins();

int callFunction(const NodePtr &body, const Strings &argv) {
    auto saved = sh.positional;
    sh.positional.assign(argv.begin() + 1, argv.end());
    int r = runNode(body);
    if (sh.returnFlag) {
        sh.returnFlag = false;
        r = sh.lastStatus;
    }
    sh.positional = std::move(saved);
    return r;
}

// Assignments before the command name, then the expanded argv.
void expandCommand(const Node &n, Strings &assigns, Strings &argv) {
    size_t i = 0;
    for (; i < n.words.size(); i++) {
        auto &w = n.words[i];
        auto eq = w.find('=');
        if (eq == std::string::npos || !validName(std::string_view(w).substr(0, eq))) break;
        assigns.push_back(w.substr(0, eq + 1) + expandSingle(w.substr(eq + 1)));
    }
    for (; i < n.words.size(); i++) expandWord(n.words[i], argv, true);
}

// Environment for a child: exported variables plus per-command assignments.
Strings childEnv(const Strings &assigns) {
    Strings env;
    for (auto &[name, v] : sh.vars) {
        if (!v.exported) continue;
        bool overridden = std::any_of(assigns.begin(), assigns.end(), [&](const std::string &a) { return startsWith(a, name + "="); });
        if (!overridden) env.push_back(name + "=" + v.value);
    }
    env.insert(env.end(), assigns.begin(), assigns.end());
    if (env.empty()) env.push_back("PATH=/bin");
    return env;
}

// Start (or run, for builtins) one simple command. External commands: *pid is set and 0 returned. Builtins and
// functions: their status is returned and *pid is 0.
int startSimple(const Node &n, const int stdioIn[3], int &pid, int pgid, bool foreground) {
    pid = 0;
    Strings assigns, argv;
    expandCommand(n, assigns, argv);
    int stdio[3] = {stdioIn[0], stdioIn[1], stdioIn[2]};
    OpenedFiles opened;
    if (!applyRedirs(n.redirs, stdio, opened)) return 1;
    if (argv.empty()) {
        for (auto &a : assigns) {
            auto eq = a.find('=');
            varSet(a.substr(0, eq), a.substr(eq + 1));
        }
        return 0;
    }
    // "command NAME ARGS": run NAME as a builtin or program, skipping aliases and functions (POSIX).
    bool bypass = false;
    if (argv[0] == "command" && argv.size() > 1 && argv[1] != "-v" && argv[1] != "-V") {
        argv.erase(argv.begin());
        if (argv[0] == "-p" && argv.size() > 1) argv.erase(argv.begin());
        bypass = true;
    }
    auto alias = bypass ? sh.aliases.end() : sh.aliases.find(argv[0]);
    if (alias != sh.aliases.end()) {
        std::string cmd = alias->second;
        for (size_t i = 1; i < argv.size(); i++) cmd += " " + shellQuote(argv[i]);
        // prevent recursion for e.g. alias ls='ls -F'
        auto saved = *alias;
        sh.aliases.erase(alias);
        StdioSwap swap(stdio);
        int status = runString(cmd);
        sh.aliases.insert(saved);
        return status;
    }
    auto fn = bypass ? sh.functions.end() : sh.functions.find(argv[0]);
    if (fn != sh.functions.end()) {
        auto body = fn->second; // keep alive even if the function redefines itself
        StdioSwap swap(stdio);
        return callFunction(body, argv);
    }
    auto builtin = builtins().find(argv[0]);
    if (builtin != builtins().end()) {
        StdioSwap swap(stdio);
        int r = builtin->second(argv);
        // A failed write (e.g. "echo x > /dev/periph/..." with bad arguments) is a failure, so && stops.
        if (std::fflush(stdout) != 0 || std::ferror(stdout)) {
            int e = errno;
            std::clearerr(stdout);
            if (r == 0) {
                err("%s: write error: %s", argv[0].c_str(), std::strerror(e ? e : EIO));
                r = 1;
            }
        }
        return r;
    }

    cc::SpawnOptions options;
    options.env = childEnv(assigns);
    options.in = stdio[0];
    options.out = stdio[1];
    options.err = stdio[2];
    if (pgid == 0) options.flags |= CC_SPAWN_NEW_GROUP;
    if (pgid == 0 && foreground) options.flags |= CC_SPAWN_FOREGROUND;
    if (foreground) cc::tty::setMode(0);
    std::fflush(stdout);
    int r = cc::spawn(argv, options);
    if (r < 0) {
        if (-r == ENOENT) err("%s: command not found", argv[0].c_str());
        else if (-r == ENOEXEC) err("%s: cannot execute (not a WebAssembly program)", argv[0].c_str());
        else err("%s: %s", argv[0].c_str(), std::strerror(-r));
        return -r == ENOENT ? 127 : 126;
    }
    pid = r;
    if (pgid > 0) cc_setpgid(r, pgid);
    return 0;
}

// Our state as shell source: what a forked shell would have (variables, functions, aliases, positional parameters).
std::string stateScript() {
    std::string out;
    for (auto &[name, v] : sh.vars) {
        if (!v.exported) out += name + "=" + shellQuote(v.value) + "\n";
    }
    for (auto &[name, text] : sh.functionSource) {
        if (sh.functions.count(name)) out += text + "\n";
    }
    for (auto &[name, value] : sh.aliases) out += "alias " + name + "=" + shellQuote(value) + "\n";
    if (!sh.positional.empty()) {
        out += "set --";
        for (auto &a : sh.positional) out += " " + shellQuote(a);
        out += "\n";
    }
    return out;
}

// A compound command of a pipeline as a child shell (with our state) on the given stdio.
int startSubshell(const Node &n, const int stdio[3], int &pid, int pgid, bool foreground) {
    pid = 0;
    cc::SpawnOptions options;
    options.env = childEnv({});
    options.in = stdio[0];
    options.out = stdio[1];
    options.err = stdio[2];
    if (pgid == 0) options.flags |= CC_SPAWN_NEW_GROUP;
    if (pgid == 0 && foreground) options.flags |= CC_SPAWN_FOREGROUND;
    std::fflush(stdout);
    int r = cc::spawn({"/bin/sh", "-c", stateScript() + n.source}, options);
    if (r < 0) {
        err("sh: %s", std::strerror(-r));
        return 126;
    }
    pid = r;
    if (pgid > 0) cc_setpgid(r, pgid);
    return 0;
}

int waitPids(const std::vector<int> &pids) {
    int status = 0;
    for (int pid : pids) {
        int st = 0;
        if (pid > 0 && cc::wait(pid, &st) > 0) status = st;
    }
    return status;
}

void restoreForeground() {
    cc_tty_setfg(sh.shellPgid);
}

int runPipeline(const std::vector<NodePtr> &commands, bool background, const std::string &text) {
    std::vector<int> pids;
    int status = 0, pgid = 0, prevRead = -1;
    for (size_t i = 0; i < commands.size(); i++) {
        auto &n = commands[i];
        int stdio[3] = {0, 1, 2};
        int32_t p[2] = {-1, -1};
        int pid = 0;
        if (prevRead >= 0) stdio[0] = prevRead;
        if (i + 1 < commands.size()) {
            if (cc_pipe(p) != 0) {
                err("pipe failed");
                break;
            }
            stdio[1] = p[1];
        }
        if (n->type == NodeType::Command) {
            status = startSimple(*n, stdio, pid, pgid, !background);
        } else if ((commands.size() > 1 || background) && !n->source.empty()) {
            // a compound command in a pipeline (or with &) runs in its own shell process, at the same time as the
            // others (POSIX shells fork): run in-process it would have to finish first, and block once the pipe is full
            status = startSubshell(*n, stdio, pid, pgid, !background);
        } else {
            StdioSwap swap(stdio);
            status = runNode(n);
        }
        pids.push_back(pid);
        if (pid > 0 && pgid == 0) pgid = pid;
        if (prevRead >= 0) close(prevRead);
        if (p[1] >= 0) close(p[1]);
        prevRead = p[0];
    }
    if (prevRead >= 0) close(prevRead);

    if (background) {
        Job job;
        job.id = sh.nextJob++;
        job.pgid = pgid;
        job.pids = pids;
        job.command = text;
        for (int pid : pids) {
            if (pid > 0) sh.lastBackground = pid; // the last command of the pipeline, as POSIX says
        }
        if (sh.interactive) std::fprintf(stderr, "[%d] %d\n", job.id, pgid);
        sh.jobs.push_back(std::move(job));
        return 0;
    }
    if (std::any_of(pids.begin(), pids.end(), [](int pid) { return pid > 0; })) {
        int st = waitPids(pids);
        if (pids.back() > 0) status = st;
        restoreForeground();
        if (status == 130 && sh.interactive) std::fputc('\n', stdout);
    }
    return status;
}

// Loop bookkeeping after one iteration; returns true if the loop must stop.
bool loopShouldStop() {
    if (sh.breakLevel) {
        sh.breakLevel--;
        return true;
    }
    if (sh.continueLevel) {
        sh.continueLevel--;
        if (sh.continueLevel) return true;
    }
    return sh.returnFlag || sh.exitCode >= 0;
}

int runNode(const NodePtr &n) {
    if (!n) return 0;
    int status = 0;
    switch (n->type) {
        case NodeType::List:
            for (auto &item : n->children) {
                if (sh.exitCode >= 0) break;
                status = runNode(item);
                sh.lastStatus = status;
                if (sh.breakLevel || sh.continueLevel || sh.returnFlag) break;
            }
            return status;
        case NodeType::Background: {
            auto &a = n->a;
            if (a->type == NodeType::Pipeline) return runPipeline(a->children, true, "job");
            if (a->type == NodeType::Command) return runPipeline({a}, true, a->words.empty() ? "job" : a->words[0]);
            // a compound command ({ ...; }, ( ... ), loops) runs in a child shell, so it does not hold up this one
            return runPipeline({a}, true, "job");
        }
        case NodeType::Command:
            return runPipeline({n}, false, "");
        case NodeType::Pipeline:
            return runPipeline(n->children, false, "");
        case NodeType::Not:
            return runNode(n->a) == 0 ? 1 : 0;
        case NodeType::And:
            status = runNode(n->a);
            sh.lastStatus = status;
            return status == 0 ? runNode(n->b) : status;
        case NodeType::Or:
            status = runNode(n->a);
            sh.lastStatus = status;
            return status != 0 ? runNode(n->b) : status;
        case NodeType::If:
            if (runNode(n->a) == 0) return runNode(n->b);
            return n->c ? runNode(n->c) : 0;
        case NodeType::While:
        case NodeType::Until:
            sh.loopDepth++;
            for (;;) {
                int c = runNode(n->a);
                if ((n->type == NodeType::While) != (c == 0)) break;
                status = runNode(n->b);
                if (loopShouldStop()) break;
            }
            sh.loopDepth--;
            return status;
        case NodeType::For: {
            Strings items;
            for (auto &w : n->words) expandWord(w, items, true);
            sh.loopDepth++;
            for (auto &item : items) {
                varSet(n->name, item);
                status = runNode(n->b);
                if (loopShouldStop()) break;
            }
            sh.loopDepth--;
            return status;
        }
        case NodeType::Case: {
            auto subject = expandSingle(n->words[0]);
            for (auto &item : n->children) {
                for (auto &p : item->words) {
                    if (fnmatch(expandSingle(p).c_str(), subject.c_str(), 0) == 0) return runNode(item->a);
                }
            }
            return 0;
        }
        case NodeType::Group: {
            int stdio[3] = {0, 1, 2};
            OpenedFiles opened;
            if (!applyRedirs(n->redirs, stdio, opened)) return 1;
            StdioSwap swap(stdio);
            return runNode(n->a);
        }
        case NodeType::Function:
            sh.functions[n->name] = n->a;
            if (!n->source.empty()) sh.functionSource[n->name] = n->source;
            return 0;
    }
    return 0;
}

// Parse and run a complete piece of source.
int runString(const std::string &src) {
    std::vector<Token> tokens;
    if (lex(src, tokens) != LexResult::Ok) {
        err("syntax error: unterminated quote");
        return 2;
    }
    Parser parser(std::move(tokens), src);
    auto tree = parser.list();
    if (!parser.failed() && parser.peek().type != Tok::End) {
        err("syntax error near '%s'", parser.peek().text.c_str());
        sh.lastStatus = 2;
        return 2;
    }
    if (parser.failed()) {
        sh.lastStatus = 2;
        return 2;
    }
    int r = runNode(tree);
    sh.lastStatus = r;
    return r;
}

// Returns -1 if the file cannot be read.
int sourceFile(const std::string &path) {
    auto src = readFile(path);
    if (!src) return -1;
    return runString(*src);
}

// ==== Builtins ==================================================================================================

bool findInPath(const std::string &name, std::string &out) {
    struct stat st;
    if (name.find('/') != std::string::npos) {
        out = name;
        return stat(name.c_str(), &st) == 0;
    }
    auto path = varGetOr("PATH", "/bin");
    size_t start = 0;
    while (start <= path.size()) {
        auto end = path.find(':', start);
        if (end == std::string::npos) end = path.size();
        out = path.substr(start, end - start) + "/" + name;
        if (stat(out.c_str(), &st) == 0 && !S_ISDIR(st.st_mode)) return true;
        start = end + 1;
    }
    return false;
}

void reapJobs(bool report) {
    for (auto it = sh.jobs.begin(); it != sh.jobs.end();) {
        bool alive = false;
        for (auto &pid : it->pids) {
            if (pid <= 0) continue;
            int st;
            if (cc::wait(pid, &st, true) == 0) alive = true;
            else pid = 0;
        }
        if (!alive) {
            if (report && sh.interactive) std::fprintf(stderr, "[%d] Done    %s\n", it->id, it->command.c_str());
            it = sh.jobs.erase(it);
        } else {
            ++it;
        }
    }
}

int builtinCd(Strings &argv) {
    std::string dir = argv.size() > 1 ? argv[1] : varGetOr("HOME", "/");
    if (dir == "-") dir = varGetOr("OLDPWD", "/");
    auto old = cc::cwd();
    if (!cc::chdir(dir)) {
        err("cd: %s: %s", dir.c_str(), std::strerror(errno));
        return 1;
    }
    varSet("OLDPWD", old, true);
    varSet("PWD", cc::cwd(), true);
    return 0;
}

int builtinEcho(Strings &argv) {
    size_t i = 1;
    bool newline = true, escapes = false;
    while (i < argv.size() && argv[i].size() > 1 && argv[i][0] == '-' && argv[i].find_first_not_of("neE", 1) == std::string::npos) {
        if (argv[i].find('n') != std::string::npos) newline = false;
        if (argv[i].find('e') != std::string::npos) escapes = true;
        i++;
    }
    for (; i < argv.size(); i++) {
        auto &s = argv[i];
        if (!escapes) {
            std::fputs(s.c_str(), stdout);
        } else {
            for (size_t k = 0; k < s.size(); k++) {
                if (s[k] != '\\' || k + 1 >= s.size()) {
                    std::putchar(s[k]);
                    continue;
                }
                switch (s[++k]) {
                    case 'n': std::putchar('\n'); break;
                    case 't': std::putchar('\t'); break;
                    case 'e': std::putchar(27); break;
                    case '\\': std::putchar('\\'); break;
                    case 'c': return 0;
                    default: std::putchar('\\'); std::putchar(s[k]); break;
                }
            }
        }
        if (i + 1 < argv.size()) std::putchar(' ');
    }
    if (newline) std::putchar('\n');
    return 0;
}

int builtinExport(Strings &argv) {
    if (argv.size() == 1) {
        for (auto &[name, v] : sh.vars) {
            if (v.exported) std::printf("export %s=%s\n", name.c_str(), shellQuote(v.value).c_str());
        }
        return 0;
    }
    for (size_t i = 1; i < argv.size(); i++) {
        auto eq = argv[i].find('=');
        if (eq != std::string::npos) varSet(argv[i].substr(0, eq), argv[i].substr(eq + 1), true);
        else varSet(argv[i], varGetOr(argv[i], ""), true);
    }
    return 0;
}

int builtinAlias(Strings &argv) {
    if (argv.size() == 1) {
        for (auto &[name, value] : sh.aliases) std::printf("alias %s=%s\n", name.c_str(), shellQuote(value).c_str());
        return 0;
    }
    for (size_t i = 1; i < argv.size(); i++) {
        auto eq = argv[i].find('=');
        if (eq == std::string::npos) {
            auto it = sh.aliases.find(argv[i]);
            if (it == sh.aliases.end()) {
                err("alias: %s: not found", argv[i].c_str());
                return 1;
            }
            std::printf("alias %s=%s\n", it->first.c_str(), shellQuote(it->second).c_str());
            continue;
        }
        sh.aliases[argv[i].substr(0, eq)] = argv[i].substr(eq + 1);
    }
    return 0;
}

int builtinType(Strings &argv);

// command -v NAME...: how NAME would be run, as a script can reuse it (path, builtin or function name, alias
// definition); command -V: described, like type. Exit status 1 if any NAME is not found.
int builtinCommand(Strings &argv) {
    if (argv.size() < 2) return 0;
    bool verbose = argv[1] == "-V";
    int status = 0;
    for (size_t i = 2; i < argv.size(); i++) {
        auto &name = argv[i];
        std::string path;
        if (verbose) {
            Strings one{"type", name};
            status |= builtinType(one);
        } else if (sh.aliases.count(name)) {
            std::printf("alias %s=%s\n", name.c_str(), shellQuote(sh.aliases[name]).c_str());
        } else if (sh.functions.count(name) || builtins().count(name)) {
            std::printf("%s\n", name.c_str());
        } else if (findInPath(name, path)) {
            std::printf("%s\n", path.c_str());
        } else {
            status = 1;
        }
    }
    return status;
}

int builtinType(Strings &argv) {
    int status = 0;
    for (size_t i = 1; i < argv.size(); i++) {
        auto &name = argv[i];
        std::string path;
        if (sh.aliases.count(name)) std::printf("%s is aliased to `%s'\n", name.c_str(), sh.aliases[name].c_str());
        else if (sh.functions.count(name)) std::printf("%s is a function\n", name.c_str());
        else if (builtins().count(name)) std::printf("%s is a shell builtin\n", name.c_str());
        else if (findInPath(name, path)) std::printf("%s is %s\n", name.c_str(), path.c_str());
        else {
            std::printf("%s: not found\n", name.c_str());
            status = 1;
        }
    }
    return status;
}

int builtinRead(Strings &argv) {
    size_t argi = 1;
    bool raw = false;
    if (argi < argv.size() && argv[argi] == "-r") {
        raw = true;
        argi++;
    }
    std::string line;
    bool got = false;
    char c;
    while (read(0, &c, 1) == 1) {
        got = true;
        if (c == '\n') break;
        if (c == '\\' && !raw) {
            if (read(0, &c, 1) != 1) break;
            if (c == '\n') continue;
        }
        line += c;
    }
    if (argi >= argv.size()) {
        varSet("REPLY", line);
    } else {
        size_t p = 0;
        for (size_t i = argi; i < argv.size(); i++) {
            while (p < line.size() && (line[p] == ' ' || line[p] == '\t')) p++;
            if (i == argv.size() - 1) {
                varSet(argv[i], line.substr(p));
            } else {
                size_t e = p;
                while (e < line.size() && line[e] != ' ' && line[e] != '\t') e++;
                varSet(argv[i], line.substr(p, e - p));
                p = e;
            }
        }
    }
    return got ? 0 : 1;
}

int builtinFg(Strings &argv) {
    if (sh.jobs.empty()) {
        err("fg: no current job");
        return 1;
    }
    auto it = sh.jobs.end() - 1;
    if (argv.size() > 1) {
        int id = std::atoi(argv[1].c_str() + (argv[1][0] == '%' ? 1 : 0));
        it = std::find_if(sh.jobs.begin(), sh.jobs.end(), [&](const Job &j) { return j.id == id; });
        if (it == sh.jobs.end()) {
            err("fg: no such job");
            return 1;
        }
    }
    auto job = *it;
    sh.jobs.erase(it);
    cc_tty_setfg(job.pgid);
    cc::tty::setMode(0);
    int status = waitPids(job.pids);
    restoreForeground();
    return status;
}

int builtinHelp(Strings &) {
    std::puts("CC: Bare Metal shell. Builtins:");
    std::puts("  cd [dir]  pwd  echo  export [N=V]  unset N  set  alias [n=v]  unalias");
    std::puts("  source FILE (or . FILE)  type CMD  read [-r] VAR  exit [N]  return [N]");
    std::puts("  jobs  fg [%N]  wait  history  break  continue  shift  true  false  help");
    std::puts("Syntax: a | b, a && b, a || b, a; b, a &, > >> < 2> 2>&1 &>, $VAR ${VAR:-x} ${#VAR} $(cmd) $((1+2)),");
    std::puts("        if/elif/else/fi, while/until ... do ... done, for x in ...; do ...; done, f() { ...; }");
    std::puts("Commands live in /bin, /usr/bin and /usr/local/bin; try 'ls /bin' and 'man intro'.");
    return 0;
}

const std::map<std::string, Builtin> &builtins() {
    static const std::map<std::string, Builtin> table = {
        {"cd", builtinCd},
        {"pwd", [](Strings &) {
             std::puts(cc::cwd().c_str());
             return 0;
         }},
        {"echo", builtinEcho},
        {"export", builtinExport},
        {"unset", [](Strings &argv) {
             for (size_t i = 1; i < argv.size(); i++) varUnset(argv[i]);
             return 0;
         }},
        {"set", [](Strings &argv) {
             if (argv.size() > 1) {
                 size_t start = argv[1] == "--" ? 2 : 1;
                 sh.positional.assign(argv.begin() + static_cast<long>(start), argv.end());
                 return 0;
             }
             for (auto &[name, v] : sh.vars) std::printf("%s=%s\n", name.c_str(), shellQuote(v.value).c_str());
             return 0;
         }},
        {"alias", builtinAlias},
        {"unalias", [](Strings &argv) {
             for (size_t i = 1; i < argv.size(); i++) sh.aliases.erase(argv[i]);
             return 0;
         }},
        {"source", [](Strings &argv) {
             if (argv.size() < 2) {
                 err("%s: filename argument required", argv[0].c_str());
                 return 2;
             }
             int r = sourceFile(argv[1]);
             if (r < 0) {
                 err("%s: %s", argv[1].c_str(), std::strerror(errno));
                 return 1;
             }
             return r;
         }},
        {"type", builtinType},
        {"command", builtinCommand},
        {"eval", [](Strings &argv) {
             std::string code;
             for (size_t i = 1; i < argv.size(); i++) {
                 if (i > 1) code += ' ';
                 code += argv[i];
             }
             return code.empty() ? 0 : runString(code);
         }},
        {"read", builtinRead},
        {"exit", [](Strings &argv) {
             sh.exitCode = argv.size() > 1 ? std::atoi(argv[1].c_str()) : sh.lastStatus;
             return sh.exitCode;
         }},
        {"return", [](Strings &argv) {
             sh.returnFlag = true;
             sh.lastStatus = argv.size() > 1 ? std::atoi(argv[1].c_str()) : sh.lastStatus;
             return sh.lastStatus;
         }},
        {"break", [](Strings &argv) {
             if (sh.loopDepth) sh.breakLevel = argv.size() > 1 ? std::atoi(argv[1].c_str()) : 1;
             return 0;
         }},
        {"continue", [](Strings &argv) {
             if (sh.loopDepth) sh.continueLevel = argv.size() > 1 ? std::atoi(argv[1].c_str()) : 1;
             return 0;
         }},
        {"shift", [](Strings &argv) {
             size_t n = argv.size() > 1 ? static_cast<size_t>(std::atoi(argv[1].c_str())) : 1;
             n = std::min(n, sh.positional.size());
             sh.positional.erase(sh.positional.begin(), sh.positional.begin() + static_cast<long>(n));
             return 0;
         }},
        {"jobs", [](Strings &) {
             for (auto &j : sh.jobs) std::printf("[%d] %d %s\n", j.id, j.pgid, j.command.c_str());
             return 0;
         }},
        {"fg", builtinFg},
        {"wait", [](Strings &) {
             for (auto &j : sh.jobs) waitPids(j.pids);
             sh.jobs.clear();
             return 0;
         }},
        {"history", [](Strings &) {
             for (size_t i = 0; i < sh.history.size(); i++) std::printf("%4zu  %s\n", i + 1, sh.history[i].c_str());
             return 0;
         }},
        {"umask", [](Strings &argv) {
             // umask [-S] [MODE]: octal (022) or symbolic (u=rwx,g=rx,o=rx)
             bool symbolic = argv.size() > 1 && argv[1] == "-S";
             size_t i = symbolic ? 2 : 1;
             mode_t mask = umask(0);
             umask(mask);
             if (i >= argv.size()) {
                 if (!symbolic) {
                     std::printf("%04o\n", static_cast<unsigned>(mask));
                 } else {
                     auto part = [&](int shift) {
                         int allowed = ~(mask >> shift) & 7;
                         return std::string(allowed & 4 ? "r" : "") + (allowed & 2 ? "w" : "") + (allowed & 1 ? "x" : "");
                     };
                     std::printf("u=%s,g=%s,o=%s\n", part(6).c_str(), part(3).c_str(), part(0).c_str());
                 }
                 return 0;
             }
             auto &m = argv[i];
             if (m.find_first_not_of("01234567") == std::string::npos) {
                 umask(static_cast<mode_t>(std::strtoul(m.c_str(), nullptr, 8)) & 0777);
                 return 0;
             }
             // symbolic: the bits given are the ones allowed
             mode_t allowed = ~mask & 0777;
             Strings clauses;
             for (size_t start = 0; start <= m.size();) {
                 auto comma = m.find(',', start);
                 clauses.push_back(m.substr(start, comma == std::string::npos ? std::string::npos : comma - start));
                 if (comma == std::string::npos) break;
                 start = comma + 1;
             }
             for (auto &clause : clauses) {
                 auto eq = clause.find_first_of("=+-");
                 if (eq == std::string::npos) {
                     std::fprintf(stderr, "umask: invalid mode: %s\n", m.c_str());
                     return 1;
                 }
                 mode_t who = 0;
                 for (char c : clause.substr(0, eq)) who |= c == 'u' ? 0700 : c == 'g' ? 0070 : c == 'o' ? 0007 : 0777;
                 if (!who) who = 0777;
                 mode_t perm = 0;
                 for (char c : clause.substr(eq + 1)) perm |= c == 'r' ? 0444 : c == 'w' ? 0222 : c == 'x' ? 0111 : 0;
                 perm &= who;
                 if (clause[eq] == '=') allowed = (allowed & ~who) | perm;
                 else if (clause[eq] == '+') allowed |= perm;
                 else allowed &= ~perm;
             }
             umask(~allowed & 0777);
             return 0;
         }},
        {"true", [](Strings &) { return 0; }},
        {":", [](Strings &) { return 0; }},
        {"false", [](Strings &) { return 1; }},
        {"help", builtinHelp},
        {"exec", [](Strings &argv) {
             if (argv.size() < 2) return 0;
             int r = cc::run(Strings(argv.begin() + 1, argv.end()));
             sh.exitCode = r < 0 ? 127 : r;
             return sh.exitCode;
         }},
    };
    static const bool aliasesAdded = [] {
        auto &t = const_cast<std::map<std::string, Builtin> &>(table);
        t["."] = t["source"];
        return true;
    }();
    (void) aliasesAdded;
    return table;
}

// ==== Line editor ===============================================================================================

int utf8Length(std::string_view s) {
    return static_cast<int>(std::count_if(s.begin(), s.end(), [](char c) { return (c & 0xC0) != 0x80; }));
}

// Display width of a prompt: skips escape sequences.
int visibleLength(std::string_view s) {
    int n = 0;
    for (size_t i = 0; i < s.size();) {
        if (s[i] == 27) {
            while (i < s.size() && !std::isalpha(static_cast<unsigned char>(s[i]))) i++;
            if (i < s.size()) i++;
            continue;
        }
        if ((s[i] & 0xC0) != 0x80) n++;
        i++;
    }
    return n;
}

std::string historyPath() {
    auto *home = varGet("HOME");
    return home ? *home + "/.sh_history" : "";
}

void historyAdd(const std::string &line) {
    if (line.empty() || (!sh.history.empty() && sh.history.back() == line)) return;
    if (sh.history.size() >= 500) sh.history.erase(sh.history.begin());
    sh.history.push_back(line);
}

void historyLoad() {
    auto path = historyPath();
    if (path.empty()) return;
    if (auto text = readFile(path)) {
        size_t start = 0;
        while (start < text->size()) {
            auto end = text->find('\n', start);
            if (end == std::string::npos) end = text->size();
            historyAdd(text->substr(start, end - start));
            start = end + 1;
        }
    }
}

void historySave(const std::string &line) {
    auto path = historyPath();
    if (path.empty() || line.empty()) return;
    if (FILE *f = std::fopen(path.c_str(), "a")) {
        std::fprintf(f, "%s\n", line.c_str());
        std::fclose(f);
    }
}

class LineEditor {
public:
    // The last read() waited longer than $TMOUT seconds for input (bash's auto-logout).
    bool timedOut = false;
    // The last read() ended with Ctrl+C.
    bool interrupted = false;
    // Returns the line, or nullopt on EOF (Ctrl+D on an empty line).
    std::optional<std::string> read(std::string prompt) {
        interrupted = false;
        timedOut = false;
        auto timeout = std::atol(varGetOr("TMOUT", "0").c_str());
        cols_ = std::max(1, cc::tty::size().cols);
        cc::tty::setMode(CC_TTY_RAW | CC_TTY_NOECHO | CC_TTY_NOSIG);
        std::fputs(prompt.c_str(), stdout);
        std::fflush(stdout);
        row_ = 0;
        // the prompt may span lines; only its last line matters for the cursor column
        auto nl = prompt.rfind('\n');
        if (nl != std::string::npos) prompt = prompt.substr(nl + 1);
        prompt_ = prompt;
        promptCols_ = visibleLength(prompt_);
        line_.clear();
        cursor_ = 0;
        hist_ = sh.history.size();
        std::optional<std::string> current;
        std::optional<std::string> result;

        for (;;) {
            unsigned char c;
            if (timeout > 0) {
                struct pollfd in = {0, POLLIN, 0};
                if (poll(&in, 1, static_cast<int>(std::min(timeout, 86400L) * 1000)) == 0) {
                    timedOut = true;
                    break;
                }
            }
            if (::read(0, &c, 1) != 1) break;
            if (c == '\r' || c == '\n') {
                redraw(line_.size());
                if ((promptCols_ + utf8Length(line_)) % cols_ != 0 || line_.empty()) std::putchar('\n');
                result = line_;
                break;
            }
            if (c == 4) { // Ctrl+D
                if (line_.empty()) {
                    std::putchar('\n');
                    break;
                }
                continue;
            }
            if (c == 3) { // Ctrl+C: drop the line (and any unfinished command), as bash
                redraw(line_.size());
                std::fputs("^C\n", stdout);
                interrupted = true;
                result = "";
                break;
            }
            if (c == 127 || c == 8) {
                if (cursor_ > 0) {
                    size_t s = cursor_ - 1;
                    while (s > 0 && (line_[s] & 0xC0) == 0x80) s--;
                    line_.erase(s, cursor_ - s);
                    cursor_ = s;
                }
            } else if (c == 1) {
                cursor_ = 0;
            } else if (c == 5) {
                cursor_ = line_.size();
            } else if (c == 11) {
                line_.erase(cursor_);
            } else if (c == 21) {
                line_.erase(0, cursor_);
                cursor_ = 0;
            } else if (c == 23) {
                size_t s = cursor_;
                while (s > 0 && line_[s - 1] == ' ') s--;
                while (s > 0 && line_[s - 1] != ' ') s--;
                line_.erase(s, cursor_ - s);
                cursor_ = s;
            } else if (c == 12) {
                std::fputs("\x1b[2J\x1b[H", stdout);
                row_ = 0;
            } else if (c == '\t') {
                complete();
            } else if (c == 27) {
                escape(current);
            } else if (c >= 32) {
                line_.insert(cursor_, 1, static_cast<char>(c));
                cursor_++;
            }
            redraw(cursor_);
        }
        cc::tty::setMode(0);
        std::fflush(stdout);
        return result;
    }

private:
    std::string line_;
    std::string prompt_;
    size_t cursor_ = 0;
    size_t hist_ = 0;
    int promptCols_ = 0;
    int cols_ = 80;
    int row_ = 0; // row of the cursor relative to the first row of the prompt

    void escape(std::optional<std::string> &current) {
        unsigned char seq[3];
        if (::read(0, &seq[0], 1) != 1 || (seq[0] != '[' && seq[0] != 'O')) return;
        if (::read(0, &seq[1], 1) != 1) return;
        if (seq[1] >= '0' && seq[1] <= '9') {
            if (::read(0, &seq[2], 1) != 1) return;
            if (seq[1] == '3' && seq[2] == '~' && cursor_ < line_.size()) { // Delete
                size_t e = cursor_ + 1;
                while (e < line_.size() && (line_[e] & 0xC0) == 0x80) e++;
                line_.erase(cursor_, e - cursor_);
            }
            return;
        }
        switch (seq[1]) {
            case 'A':
            case 'B':
                if (hist_ == sh.history.size()) current = line_;
                if (seq[1] == 'A' && hist_ > 0) hist_--;
                else if (seq[1] == 'B' && hist_ < sh.history.size()) hist_++;
                line_ = hist_ < sh.history.size() ? sh.history[hist_] : current.value_or("");
                cursor_ = line_.size();
                break;
            case 'C':
                if (cursor_ < line_.size()) {
                    cursor_++;
                    while (cursor_ < line_.size() && (line_[cursor_] & 0xC0) == 0x80) cursor_++;
                }
                break;
            case 'D':
                if (cursor_ > 0) {
                    cursor_--;
                    while (cursor_ > 0 && (line_[cursor_] & 0xC0) == 0x80) cursor_--;
                }
                break;
            case 'H': cursor_ = 0; break;
            case 'F': cursor_ = line_.size(); break;
            default: break;
        }
    }

    // Redraw prompt and line, handling lines longer than the terminal width.
    void redraw(size_t cursor) {
        if (row_ > 0) std::printf("\x1b[%dA", row_);
        std::fputs("\r", stdout);
        std::fputs(prompt_.c_str(), stdout);
        std::fwrite(line_.data(), 1, line_.size(), stdout);
        std::fputs("\x1b[J", stdout);
        int total = promptCols_ + utf8Length(line_);
        int endRow = total > 0 ? (total - 1) / cols_ : 0;
        int pos = promptCols_ + utf8Length(std::string_view(line_).substr(0, cursor));
        int targetRow = pos / cols_, targetCol = pos % cols_;
        if (targetRow > endRow) {
            std::fputs("\n", stdout);
        } else {
            if (endRow > targetRow) std::printf("\x1b[%dA", endRow - targetRow);
            std::fputs("\r", stdout);
            if (targetCol > 0) std::printf("\x1b[%dC", targetCol);
        }
        row_ = targetRow;
        std::fflush(stdout);
    }

    // Tab completion of the word before the cursor: commands for the first word, else paths.
    void complete() {
        size_t start = cursor_;
        while (start > 0 && line_[start - 1] != ' ') start--;
        std::string word = line_.substr(start, cursor_ - start);
        bool firstWord = true;
        for (size_t i = 0; i < start; i++) {
            if (line_[i] != ' ') firstWord = false;
            if (std::strchr("|;&", line_[i])) firstWord = true;
        }
        Strings matches;
        if (firstWord && word.find('/') == std::string::npos) {
            for (auto &[name, fn] : builtins()) {
                if (startsWith(name, word)) matches.push_back(name);
            }
            auto path = varGetOr("PATH", "/bin");
            size_t s = 0;
            while (s <= path.size()) {
                auto e = path.find(':', s);
                if (e == std::string::npos) e = path.size();
                if (DIR *dp = opendir(path.substr(s, e - s).c_str())) {
                    while (auto *ent = readdir(dp)) {
                        if (ent->d_name[0] != '.' && startsWith(ent->d_name, word)) matches.emplace_back(ent->d_name);
                    }
                    closedir(dp);
                }
                s = e + 1;
            }
        } else {
            auto slash = word.rfind('/');
            std::string dir = slash == std::string::npos ? "" : word.substr(0, slash + 1);
            std::string base = slash == std::string::npos ? word : word.substr(slash + 1);
            if (DIR *dp = opendir(dir.empty() ? "." : dir.c_str())) {
                while (auto *ent = readdir(dp)) {
                    std::string name = ent->d_name;
                    if (name == "." || name == "..") continue;
                    if (name[0] == '.' && (base.empty() || base[0] != '.')) continue;
                    if (!startsWith(name, base)) continue;
                    std::string full = dir + name;
                    struct stat st;
                    if (stat(full.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) full += '/';
                    matches.push_back(full);
                }
                closedir(dp);
            }
        }
        if (matches.empty()) return;
        std::sort(matches.begin(), matches.end());
        matches.erase(std::unique(matches.begin(), matches.end()), matches.end());
        size_t common = matches[0].size();
        for (auto &m : matches) {
            size_t k = 0;
            while (k < common && k < m.size() && m[k] == matches[0][k]) k++;
            common = k;
        }
        if (common > word.size()) {
            std::string replacement = matches[0].substr(0, common);
            if (matches.size() == 1 && replacement.back() != '/') replacement += ' ';
            line_ = line_.substr(0, start) + replacement + line_.substr(cursor_);
            cursor_ = start + replacement.size();
        } else if (matches.size() > 1) {
            int col = 0;
            std::putchar('\n');
            for (size_t i = 0; i < matches.size() && i < 200; i++) {
                auto &m = matches[i];
                auto s = m.size() > 1 ? m.rfind('/', m.size() - 2) : std::string::npos;
                std::string shown = s == std::string::npos ? m : m.substr(s + 1);
                int w = visibleLength(shown) + 2;
                if (col + w > cols_) {
                    std::putchar('\n');
                    col = 0;
                }
                std::printf("%s  ", shown.c_str());
                col += w;
            }
            std::putchar('\n');
            row_ = 0;
            std::fputs(prompt_.c_str(), stdout);
        }
    }
};

std::string expandPrompt(const std::string &ps1) {
    std::string out;
    for (size_t i = 0; i < ps1.size(); i++) {
        if (ps1[i] != '\\' || i + 1 >= ps1.size()) {
            out += ps1[i];
            continue;
        }
        char c = ps1[++i];
        switch (c) {
            case 'u': {
                auto *pw = getpwuid(geteuid());
                out += pw ? pw->pw_name : varGetOr("USER", "user");
                break;
            }
            case 'h': {
                out += cc::hostname();
                break;
            }
            case 'w':
            case 'W': {
                auto cwd = cc::cwd();
                auto home = varGetOr("HOME", "");
                if (c == 'W') {
                    auto s = cwd.rfind('/');
                    out += s != std::string::npos && s + 1 < cwd.size() ? cwd.substr(s + 1) : cwd;
                } else if (!home.empty() && startsWith(cwd, home) && (cwd.size() == home.size() || cwd[home.size()] == '/')) {
                    out += "~" + cwd.substr(home.size());
                } else {
                    out += cwd;
                }
                break;
            }
            case '$': out += geteuid() == 0 ? '#' : '$'; break; // as bash: # for root
            case 'e': out += '\x1b'; break;
            case 'n': out += '\n'; break;
            case '\\': out += '\\'; break;
            default:
                out += '\\';
                out += c;
                break;
        }
    }
    return out;
}

// An "if" without "fi" (and similar) means the command continues on the next line.
bool blockOpen(const std::string &src) {
    std::vector<Token> t;
    lex(src, t);
    int depth = 0;
    for (size_t i = 0; i < t.size(); i++) {
        auto &k = t[i];
        bool atCommand = i == 0 || t[i - 1].type != Tok::Word || t[i - 1].text == "then" || t[i - 1].text == "do"
                         || t[i - 1].text == "else";
        if (k.type != Tok::Word || !atCommand) continue;
        if (k.text == "if" || k.text == "while" || k.text == "until" || k.text == "for" || k.text == "case" || k.text == "{") depth++;
        if (k.text == "fi" || k.text == "done" || k.text == "esac" || k.text == "}") depth--;
    }
    return depth > 0;
}

int interactiveLoop() {
    historyLoad();
    LineEditor editor;
    std::string pending;
    for (;;) {
        reapJobs(true);
        auto prompt = pending.empty() ? expandPrompt(varGetOr("PS1", "\\e[92m\\u@\\h\\e[0m:\\e[94m\\w\\e[0m\\$ ")) : "> ";
        auto line = editor.read(prompt);
        if (editor.timedOut) {
            std::fputs("\ntimed out waiting for input: auto-logout\n", stdout);
            std::fflush(stdout);
            return sh.lastStatus;
        }
        if (!line) {
            if (!pending.empty()) {
                pending.clear();
                continue;
            }
            return sh.lastStatus;
        }
        if (editor.interrupted) {
            pending.clear();
            sh.lastStatus = 130;
            continue;
        }
        pending += *line;
        std::vector<Token> t;
        bool incomplete = lex(pending, t) == LexResult::Incomplete;
        if (incomplete || (!pending.empty() && pending.back() == '\\')) {
            if (pending.back() == '\\') pending.pop_back();
            pending += '\n';
            continue;
        }
        if (blockOpen(pending)) {
            pending += '\n';
            continue;
        }
        historyAdd(pending);
        historySave(pending);
        runString(pending);
        pending.clear();
        std::fflush(stdout);
        if (sh.exitCode >= 0) return sh.exitCode;
    }
}

} // namespace

int main(int argc, char **argv) {
    for (char **e = environ; e && *e; e++) {
        std::string entry = *e;
        auto eq = entry.find('=');
        if (eq != std::string::npos) varSet(entry.substr(0, eq), entry.substr(eq + 1), true);
    }
    sh.shellPgid = cc_getpgid();
    if (!varGet("PATH")) varSet("PATH", "/usr/local/bin:/bin:/usr/bin", true);
    varSet("PWD", cc::cwd(), true);

    bool login = false;
    std::optional<std::string> command;
    int i = 1;
    for (; i < argc && argv[i][0] == '-'; i++) {
        std::string a = argv[i];
        if (a == "-l" || a == "--login") {
            login = true;
        } else if (a == "-c" && i + 1 < argc) {
            command = argv[++i];
            i++;
            break;
        } else if (a == "--") {
            i++;
            break;
        }
    }
    auto finish = [] {
        std::fflush(stdout);
        return sh.exitCode >= 0 ? sh.exitCode : sh.lastStatus;
    };
    if (command) {
        if (i < argc) sh.scriptName = argv[i++];
        sh.positional.assign(argv + i, argv + argc);
        runString(*command);
        return finish();
    }
    if (i < argc) {
        sh.scriptName = argv[i];
        sh.positional.assign(argv + i + 1, argv + argc);
        if (sourceFile(argv[i]) < 0) {
            err("%s: %s", argv[i], std::strerror(errno));
            return 127;
        }
        return finish();
    }
    sh.interactive = isatty(0);
    auto home = varGetOr("HOME", "");
    if (login) {
        sourceFile("/etc/profile");
        if (!home.empty()) sourceFile(home + "/.profile");
    }
    if (sh.interactive) {
        if (!home.empty()) sourceFile(home + "/.shrc");
        return interactiveLoop();
    }
    // commands from a pipe
    std::string all;
    char buf[4096];
    ssize_t n;
    while ((n = read(0, buf, sizeof(buf))) > 0) all.append(buf, static_cast<size_t>(n));
    if (!all.empty()) runString(all);
    return finish();
}
