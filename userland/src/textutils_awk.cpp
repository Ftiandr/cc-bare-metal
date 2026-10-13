// awk - the POSIX awk language: patterns (BEGIN, END, expressions, regexes, ranges), fields, associative arrays,
// user functions, printf, getline, output redirection (> >> |) and the standard built-in functions.
#include "textutils.hpp"

#include <baremetal.hpp>

#include <algorithm>
#include <cmath>
#include <random>
#include <ctime>
#include <memory>
#include <unistd.h>
#include <unordered_map>

extern char **environ;

namespace {

using namespace tu;

// ---- Values -----------------------------------------------------------------------------------------------------

struct Value {
    enum Kind { Uninit, Num, Str, StrNum } kind = Uninit;
    double num = 0;
    std::string str;

    static Value number(double d) {
        Value v;
        v.kind = Num;
        v.num = d;
        return v;
    }
    static Value string(std::string s) {
        Value v;
        v.kind = Str;
        v.str = std::move(s);
        return v;
    }
};

bool looksNumeric(const std::string &s, double &out) {
    const char *p = s.c_str();
    while (*p == ' ' || *p == '\t' || *p == '\n') p++;
    if (!*p) return false;
    char *end;
    out = std::strtod(p, &end);
    if (end == p) return false;
    // strtod accepts "inf", "nan" and hex; awk does not.
    for (const char *q = p; q < end; q++) {
        if (std::isalpha(static_cast<unsigned char>(*q)) && *q != 'e' && *q != 'E') return false;
    }
    while (*end == ' ' || *end == '\t' || *end == '\n') end++;
    return *end == '\0';
}

// Input data (fields, getline, split): a number when it looks like one, but still a string.
Value input(std::string s) {
    Value v;
    v.str = std::move(s);
    double d;
    if (looksNumeric(v.str, d)) {
        v.kind = Value::StrNum;
        v.num = d;
    } else {
        v.kind = Value::Str;
    }
    return v;
}

using Array = std::unordered_map<std::string, Value>;

struct Cell {
    Value value;
    std::shared_ptr<Array> array;
    Array &arr() {
        if (!array) array = std::make_shared<Array>();
        return *array;
    }
};

// ---- Lexer ------------------------------------------------------------------------------------------------------

enum Tok {
    T_EOF, T_NEWLINE, T_LBRACE, T_RBRACE, T_LPAREN, T_RPAREN, T_LBRACKET, T_RBRACKET, T_SEMI, T_COMMA,
    T_NUMBER, T_STRING, T_ERE, T_NAME, T_FUNC_NAME, T_BUILTIN,
    T_PLUS, T_MINUS, T_STAR, T_SLASH, T_PERCENT, T_CARET, T_NOT, T_GT, T_LT, T_PIPE, T_QUESTION, T_COLON,
    T_TILDE, T_NOMATCH, T_DOLLAR, T_ASSIGN, T_ADD_ASSIGN, T_SUB_ASSIGN, T_MUL_ASSIGN, T_DIV_ASSIGN, T_MOD_ASSIGN,
    T_POW_ASSIGN, T_EQ, T_LE, T_GE, T_NE, T_INCR, T_DECR, T_AND, T_OR, T_APPEND,
    // keywords
    T_BEGIN, T_END, T_FUNCTION, T_IF, T_ELSE, T_WHILE, T_FOR, T_DO, T_BREAK, T_CONTINUE, T_NEXT, T_NEXTFILE,
    T_EXIT, T_RETURN, T_DELETE, T_IN, T_GETLINE, T_PRINT, T_PRINTF,
};

struct Token {
    Tok type = T_EOF;
    std::string text;
    double num = 0;
    int line = 1;
};

const std::map<std::string, Tok> &keywords() {
    static const std::map<std::string, Tok> k = {
        {"BEGIN", T_BEGIN}, {"END", T_END}, {"function", T_FUNCTION}, {"func", T_FUNCTION}, {"if", T_IF}, {"else", T_ELSE},
        {"while", T_WHILE}, {"for", T_FOR}, {"do", T_DO}, {"break", T_BREAK}, {"continue", T_CONTINUE}, {"next", T_NEXT},
        {"nextfile", T_NEXTFILE}, {"exit", T_EXIT}, {"return", T_RETURN}, {"delete", T_DELETE}, {"in", T_IN},
        {"getline", T_GETLINE}, {"print", T_PRINT}, {"printf", T_PRINTF},
    };
    return k;
}

const std::vector<std::string> BUILTINS = {"length", "substr", "index", "split", "sub", "gsub", "match", "sprintf", "sin", "cos",
                                           "atan2", "exp", "log", "sqrt", "int", "rand", "srand", "tolower", "toupper", "system",
                                           "close", "fflush"};

class Lexer {
public:
    explicit Lexer(std::string src) : s_(std::move(src)) {}

    Token next() {
        Token t = scan();
        lastType_ = t.type;
        return t;
    }

private:
    std::string s_;
    size_t p_ = 0;
    int line_ = 1;
    Tok lastType_ = T_NEWLINE;

    [[noreturn]] void error(const std::string &message) { die("syntax error at source line " + std::to_string(line_) + ": " + message); }

    // Whether '/' starts a regex here (not after an operand).
    bool regexAllowed() const {
        switch (lastType_) {
            case T_NUMBER: case T_STRING: case T_ERE: case T_NAME: case T_FUNC_NAME: case T_BUILTIN: case T_RPAREN:
            case T_RBRACKET: case T_DOLLAR: case T_INCR: case T_DECR:
                return false;
            default:
                return true;
        }
    }

    Token make(Tok type, std::string text = {}) {
        Token t;
        t.type = type;
        t.text = std::move(text);
        t.line = line_;
        return t;
    }

    Token scan() {
        while (p_ < s_.size()) {
            char c = s_[p_];
            if (c == ' ' || c == '\t' || c == '\r') {
                p_++;
            } else if (c == '\\' && p_ + 1 < s_.size() && s_[p_ + 1] == '\n') {
                p_ += 2;
                line_++;
            } else if (c == '#') {
                while (p_ < s_.size() && s_[p_] != '\n') p_++;
            } else {
                break;
            }
        }
        if (p_ >= s_.size()) return make(T_EOF);
        char c = s_[p_];
        if (c == '\n') {
            p_++;
            line_++;
            return make(T_NEWLINE);
        }
        if (std::isdigit(static_cast<unsigned char>(c)) || (c == '.' && p_ + 1 < s_.size() && std::isdigit(static_cast<unsigned char>(s_[p_ + 1])))) {
            char *end;
            double d = std::strtod(s_.c_str() + p_, &end);
            auto t = make(T_NUMBER, s_.substr(p_, static_cast<size_t>(end - (s_.c_str() + p_))));
            t.num = d;
            p_ = static_cast<size_t>(end - s_.c_str());
            return t;
        }
        if (std::isalpha(static_cast<unsigned char>(c)) || c == '_') {
            size_t start = p_;
            while (p_ < s_.size() && (std::isalnum(static_cast<unsigned char>(s_[p_])) || s_[p_] == '_')) p_++;
            auto word = s_.substr(start, p_ - start);
            auto kw = keywords().find(word);
            if (kw != keywords().end()) return make(kw->second, word);
            if (std::find(BUILTINS.begin(), BUILTINS.end(), word) != BUILTINS.end()) return make(T_BUILTIN, word);
            if (p_ < s_.size() && s_[p_] == '(') return make(T_FUNC_NAME, word);
            return make(T_NAME, word);
        }
        if (c == '"') {
            p_++;
            std::string out;
            while (p_ < s_.size() && s_[p_] != '"') {
                char ch = s_[p_++];
                if (ch == '\n') error("newline in string");
                if (ch == '\\' && p_ < s_.size()) {
                    char e = s_[p_++];
                    switch (e) {
                        case 'n': out += '\n'; break;
                        case 't': out += '\t'; break;
                        case 'r': out += '\r'; break;
                        case '\\': out += '\\'; break;
                        case '"': out += '"'; break;
                        case '/': out += '/'; break;
                        case 'a': out += '\a'; break;
                        case 'b': out += '\b'; break;
                        case 'f': out += '\f'; break;
                        case 'v': out += '\v'; break;
                        case '\n': line_++; break;
                        default:
                            if (e >= '0' && e <= '7') {
                                int v = e - '0';
                                for (int k = 0; k < 2 && p_ < s_.size() && s_[p_] >= '0' && s_[p_] <= '7'; k++) v = v * 8 + (s_[p_++] - '0');
                                out += static_cast<char>(v);
                            } else {
                                out += '\\';
                                out += e;
                            }
                    }
                    continue;
                }
                out += ch;
            }
            if (p_ >= s_.size()) error("unterminated string");
            p_++;
            return make(T_STRING, out);
        }
        if (c == '/' && regexAllowed()) {
            p_++;
            std::string out;
            bool bracket = false;
            while (p_ < s_.size() && (s_[p_] != '/' || bracket)) {
                char ch = s_[p_++];
                if (ch == '\n') error("newline in regex");
                if (ch == '\\' && p_ < s_.size()) {
                    if (s_[p_] == '/') {
                        out += '/';
                        p_++;
                        continue;
                    }
                    out += ch;
                    out += s_[p_++];
                    continue;
                }
                if (ch == '[') bracket = true;
                else if (ch == ']' && bracket && out.size() > 0 && out.back() != '[') bracket = false;
                out += ch;
            }
            if (p_ >= s_.size()) error("unterminated regex");
            p_++;
            return make(T_ERE, out);
        }
        p_++;
        auto two = [&](char second) {
            if (p_ < s_.size() && s_[p_] == second) {
                p_++;
                return true;
            }
            return false;
        };
        switch (c) {
            case '{': return make(T_LBRACE);
            case '}': return make(T_RBRACE);
            case '(': return make(T_LPAREN);
            case ')': return make(T_RPAREN);
            case '[': return make(T_LBRACKET);
            case ']': return make(T_RBRACKET);
            case ';': return make(T_SEMI);
            case ',': return make(T_COMMA);
            case '?': return make(T_QUESTION);
            case ':': return make(T_COLON);
            case '~': return make(T_TILDE);
            case '$': return make(T_DOLLAR);
            case '+': return two('+') ? make(T_INCR) : two('=') ? make(T_ADD_ASSIGN) : make(T_PLUS);
            case '-': return two('-') ? make(T_DECR) : two('=') ? make(T_SUB_ASSIGN) : make(T_MINUS);
            case '*':
                if (two('*')) return two('=') ? make(T_POW_ASSIGN) : make(T_CARET);
                return two('=') ? make(T_MUL_ASSIGN) : make(T_STAR);
            case '/': return two('=') ? make(T_DIV_ASSIGN) : make(T_SLASH);
            case '%': return two('=') ? make(T_MOD_ASSIGN) : make(T_PERCENT);
            case '^': return two('=') ? make(T_POW_ASSIGN) : make(T_CARET);
            case '!': return two('=') ? make(T_NE) : two('~') ? make(T_NOMATCH) : make(T_NOT);
            case '>': return two('=') ? make(T_GE) : two('>') ? make(T_APPEND) : make(T_GT);
            case '<': return two('=') ? make(T_LE) : make(T_LT);
            case '=': return two('=') ? make(T_EQ) : make(T_ASSIGN);
            case '|': return two('|') ? make(T_OR) : make(T_PIPE);
            case '&':
                if (two('&')) return make(T_AND);
                break;
            default: break;
        }
        error(std::string("unexpected character '") + c + "'");
    }
};

// ---- AST --------------------------------------------------------------------------------------------------------

enum NodeKind {
    // expressions
    N_NUM, N_STR, N_REGEX, N_VAR, N_LOCAL, N_INDEX, N_FIELD, N_ASSIGN, N_COND, N_AND, N_OR, N_NOT, N_IN, N_MATCH,
    N_BINARY, N_CMP, N_CONCAT, N_NEG, N_PLUS, N_PREINC, N_PREDEC, N_POSTINC, N_POSTDEC, N_CALL, N_BUILTIN, N_GETLINE,
    N_GROUP,
    // statements
    S_EXPR, S_PRINT, S_PRINTF, S_IF, S_WHILE, S_DO, S_FOR, S_FORIN, S_BLOCK, S_BREAK, S_CONTINUE, S_NEXT, S_NEXTFILE,
    S_EXIT, S_RETURN, S_DELETE, S_DELETE_ALL,
};

struct Node {
    NodeKind kind;
    int op = 0;      // operator token, redirection type, getline type
    int index = -1;  // variable / function slot
    double num = 0;
    std::string str;
    std::vector<std::unique_ptr<Node>> kids;
    std::shared_ptr<Regex> re;

    explicit Node(NodeKind k) : kind(k) {}
    Node *kid(size_t i) const { return i < kids.size() ? kids[i].get() : nullptr; }
};
using NodeP = std::unique_ptr<Node>;

NodeP node(NodeKind k, NodeP a = nullptr, NodeP b = nullptr, NodeP c = nullptr) {
    auto n = std::make_unique<Node>(k);
    if (a) n->kids.push_back(std::move(a));
    if (b) n->kids.push_back(std::move(b));
    if (c) n->kids.push_back(std::move(c));
    return n;
}

struct Function {
    std::string name;
    std::vector<std::string> params;
    NodeP body;
    bool defined = false;
};

struct Rule {
    enum Kind { Begin, End, Main } kind = Main;
    NodeP pattern, pattern2; // pattern2: range end
    NodeP action;            // null: print $0
    bool inRange = false;
};

struct Program {
    std::vector<Rule> rules;
    std::vector<Function> functions;
    std::map<std::string, int> functionIndex;
    std::map<std::string, int> globalIndex;
    std::vector<std::string> globalNames;

    int global(const std::string &name) {
        auto it = globalIndex.find(name);
        if (it != globalIndex.end()) return it->second;
        int i = static_cast<int>(globalNames.size());
        globalIndex[name] = i;
        globalNames.push_back(name);
        return i;
    }
    int function(const std::string &name) {
        auto it = functionIndex.find(name);
        if (it != functionIndex.end()) return it->second;
        int i = static_cast<int>(functions.size());
        functionIndex[name] = i;
        functions.push_back({name, {}, nullptr, false});
        return i;
    }
};

// ---- Parser -----------------------------------------------------------------------------------------------------

class Parser {
public:
    Parser(const std::string &src, Program &prog) : lex_(src), prog_(prog) { advance(); }

    void parseProgram() {
        skipTerminators();
        while (tok_.type != T_EOF) {
            if (tok_.type == T_FUNCTION) {
                parseFunction();
            } else {
                parseRule();
            }
            skipTerminators();
        }
        for (auto &f : prog_.functions) {
            if (!f.defined) die("calling undefined function " + f.name);
        }
    }

private:
    Lexer lex_;
    Program &prog_;
    Token tok_;
    const std::vector<std::string> *params_ = nullptr; // inside a function
    int noIn_ = 0;  // "for (x in a)" header: no `in` operator
    int noGt_ = 0;  // print arguments: '>' is redirection

    [[noreturn]] void error(const std::string &message) {
        die("syntax error at source line " + std::to_string(tok_.line) + ": " + message + (tok_.text.empty() ? "" : " near '" + tok_.text + "'"));
    }

    void advance() { tok_ = lex_.next(); }
    bool accept(Tok t) {
        if (tok_.type != t) return false;
        advance();
        return true;
    }
    void expect(Tok t, const char *what) {
        if (!accept(t)) error(std::string("expected ") + what);
    }
    void skipNewlines() {
        while (tok_.type == T_NEWLINE) advance();
    }
    void skipTerminators() {
        while (tok_.type == T_NEWLINE || tok_.type == T_SEMI) advance();
    }

    void parseFunction() {
        advance();
        if (tok_.type != T_NAME && tok_.type != T_FUNC_NAME) error("expected function name");
        int idx = prog_.function(tok_.text);
        auto &f = prog_.functions[static_cast<size_t>(idx)];
        if (f.defined) error("function " + tok_.text + " redefined");
        advance();
        expect(T_LPAREN, "(");
        std::vector<std::string> params;
        while (tok_.type == T_NAME) {
            params.push_back(tok_.text);
            advance();
            if (!accept(T_COMMA)) break;
            skipNewlines();
        }
        expect(T_RPAREN, ")");
        skipNewlines();
        prog_.functions[static_cast<size_t>(idx)].params = params;
        params_ = &prog_.functions[static_cast<size_t>(idx)].params;
        auto body = block();
        params_ = nullptr;
        prog_.functions[static_cast<size_t>(idx)].body = std::move(body);
        prog_.functions[static_cast<size_t>(idx)].defined = true;
    }

    void parseRule() {
        Rule r;
        if (accept(T_BEGIN)) {
            r.kind = Rule::Begin;
        } else if (accept(T_END)) {
            r.kind = Rule::End;
        } else if (tok_.type != T_LBRACE) {
            r.pattern = expr();
            if (accept(T_COMMA)) {
                skipNewlines();
                r.pattern2 = expr();
            }
        }
        if (tok_.type == T_LBRACE) {
            r.action = block();
        } else if (r.kind != Rule::Main) {
            error("BEGIN/END need an action");
        }
        prog_.rules.push_back(std::move(r));
    }

    NodeP block() {
        expect(T_LBRACE, "{");
        auto b = node(S_BLOCK);
        skipTerminators();
        while (tok_.type != T_RBRACE) {
            if (tok_.type == T_EOF) error("unexpected end of program, missing }");
            b->kids.push_back(statement());
            skipTerminators();
        }
        advance();
        return b;
    }

    void endSimple() {
        if (tok_.type == T_SEMI || tok_.type == T_NEWLINE) {
            advance();
            return;
        }
        if (tok_.type == T_RBRACE || tok_.type == T_EOF) return;
        error("expected ; or newline");
    }

    NodeP simpleOrBlock() {
        skipNewlines();
        if (tok_.type == T_SEMI) {
            advance();
            return node(S_BLOCK);
        }
        return statement();
    }

    NodeP statement() {
        switch (tok_.type) {
            case T_LBRACE: return block();
            case T_IF: {
                advance();
                expect(T_LPAREN, "(");
                auto cond = expr();
                expect(T_RPAREN, ")");
                auto then = simpleOrBlock();
                // "else" may follow after newlines/semicolons
                auto save = tok_;
                (void) save;
                while (tok_.type == T_NEWLINE || tok_.type == T_SEMI) {
                    advance();
                    if (tok_.type == T_ELSE) break;
                    if (tok_.type != T_NEWLINE && tok_.type != T_SEMI) {
                        auto n = node(S_IF, std::move(cond), std::move(then));
                        return n;
                    }
                }
                if (accept(T_ELSE)) return node(S_IF, std::move(cond), std::move(then), simpleOrBlock());
                return node(S_IF, std::move(cond), std::move(then));
            }
            case T_WHILE: {
                advance();
                expect(T_LPAREN, "(");
                auto cond = expr();
                expect(T_RPAREN, ")");
                if (tok_.type == T_SEMI) {
                    advance();
                    return node(S_WHILE, std::move(cond), node(S_BLOCK));
                }
                return node(S_WHILE, std::move(cond), simpleOrBlock());
            }
            case T_DO: {
                advance();
                auto body = simpleOrBlock();
                skipTerminators();
                expect(T_WHILE, "while");
                expect(T_LPAREN, "(");
                auto cond = expr();
                expect(T_RPAREN, ")");
                endSimple();
                return node(S_DO, std::move(body), std::move(cond));
            }
            case T_FOR: {
                advance();
                expect(T_LPAREN, "(");
                // for (k in arr)
                if (tok_.type == T_NAME) {
                    Lexer saveLex = lex_;
                    Token saveTok = tok_;
                    auto name = tok_.text;
                    advance();
                    if (accept(T_IN)) {
                        if (tok_.type != T_NAME) error("expected array name");
                        auto arr = variable(tok_.text);
                        advance();
                        if (accept(T_RPAREN)) {
                            auto n = node(S_FORIN, variable(name), std::move(arr), simpleOrBlock());
                            return n;
                        }
                    }
                    lex_ = saveLex;
                    tok_ = saveTok;
                }
                NodeP init, cond, step;
                if (tok_.type != T_SEMI) init = simpleExpr();
                expect(T_SEMI, ";");
                skipNewlines();
                if (tok_.type != T_SEMI) cond = expr();
                expect(T_SEMI, ";");
                skipNewlines();
                if (tok_.type != T_RPAREN) step = simpleExpr();
                expect(T_RPAREN, ")");
                auto n = std::make_unique<Node>(S_FOR);
                n->kids.push_back(std::move(init));
                n->kids.push_back(std::move(cond));
                n->kids.push_back(std::move(step));
                if (tok_.type == T_SEMI) {
                    advance();
                    n->kids.push_back(node(S_BLOCK));
                } else {
                    n->kids.push_back(simpleOrBlock());
                }
                return n;
            }
            case T_SEMI: advance(); return node(S_BLOCK);
            default: break;
        }
        auto s = simpleStatement();
        endSimple();
        return s;
    }

    NodeP simpleExpr() {
        auto e = expr();
        return node(S_EXPR, std::move(e));
    }

    NodeP simpleStatement() {
        switch (tok_.type) {
            case T_BREAK: advance(); return node(S_BREAK);
            case T_CONTINUE: advance(); return node(S_CONTINUE);
            case T_NEXT: advance(); return node(S_NEXT);
            case T_NEXTFILE: advance(); return node(S_NEXTFILE);
            case T_EXIT: {
                advance();
                auto n = node(S_EXIT);
                if (startsExpr()) n->kids.push_back(expr());
                return n;
            }
            case T_RETURN: {
                advance();
                auto n = node(S_RETURN);
                if (startsExpr()) n->kids.push_back(expr());
                return n;
            }
            case T_DELETE: {
                advance();
                if (tok_.type != T_NAME) error("expected array name");
                auto arr = variable(tok_.text);
                advance();
                if (accept(T_LBRACKET)) {
                    auto n = node(S_DELETE, std::move(arr));
                    n->kids.push_back(subscript());
                    return n;
                }
                return node(S_DELETE_ALL, std::move(arr));
            }
            case T_PRINT:
            case T_PRINTF: {
                bool isPrintf = tok_.type == T_PRINTF;
                advance();
                auto n = node(isPrintf ? S_PRINTF : S_PRINT);
                std::vector<NodeP> args;
                if (startsExpr()) {
                    noGt_++;
                    if (tok_.type == T_LPAREN) {
                        // print (a, b) > file  or  print (a)(b)
                        advance();
                        noGt_--;
                        auto first = expr();
                        if (accept(T_COMMA)) {
                            args.push_back(std::move(first));
                            do {
                                skipNewlines();
                                args.push_back(expr());
                            } while (accept(T_COMMA));
                            expect(T_RPAREN, ")");
                        } else {
                            expect(T_RPAREN, ")");
                            auto g = node(N_GROUP, std::move(first));
                            noGt_++;
                            args.push_back(continueExpr(std::move(g)));
                            noGt_--;
                        }
                        noGt_++;
                        while (accept(T_COMMA)) {
                            skipNewlines();
                            args.push_back(expr());
                        }
                    } else {
                        args.push_back(expr());
                        while (accept(T_COMMA)) {
                            skipNewlines();
                            args.push_back(expr());
                        }
                    }
                    noGt_--;
                }
                n->kids.push_back(nullptr); // redirection target
                for (auto &a : args) n->kids.push_back(std::move(a));
                if (tok_.type == T_GT || tok_.type == T_APPEND || tok_.type == T_PIPE) {
                    n->op = tok_.type;
                    advance();
                    n->kids[0] = concatExpr();
                }
                if (isPrintf && n->kids.size() < 2) error("printf: no format");
                return n;
            }
            default:
                return simpleExpr();
        }
    }

    bool startsExpr() const {
        switch (tok_.type) {
            case T_NUMBER: case T_STRING: case T_ERE: case T_NAME: case T_FUNC_NAME: case T_BUILTIN: case T_DOLLAR:
            case T_NOT: case T_LPAREN: case T_MINUS: case T_PLUS: case T_INCR: case T_DECR: case T_GETLINE:
                return true;
            default:
                return false;
        }
    }

    NodeP variable(const std::string &name) {
        if (params_) {
            for (size_t i = 0; i < params_->size(); i++) {
                if ((*params_)[i] == name) {
                    auto n = node(N_LOCAL);
                    n->index = static_cast<int>(i);
                    n->str = name;
                    return n;
                }
            }
        }
        auto n = node(N_VAR);
        n->index = prog_.global(name);
        n->str = name;
        return n;
    }

    // a[i, j] -> key i SUBSEP j
    NodeP subscript() {
        auto first = expr();
        if (tok_.type == T_COMMA) {
            auto n = node(N_CONCAT);
            n->op = 1; // joined with SUBSEP
            n->kids.push_back(std::move(first));
            while (accept(T_COMMA)) {
                skipNewlines();
                n->kids.push_back(expr());
            }
            expect(T_RBRACKET, "]");
            return n;
        }
        expect(T_RBRACKET, "]");
        return first;
    }

    static bool isLvalue(const Node *n) { return n->kind == N_VAR || n->kind == N_LOCAL || n->kind == N_INDEX || n->kind == N_FIELD; }

    NodeP expr() {
        auto left = ternary();
        return assignment(std::move(left));
    }

    NodeP assignment(NodeP left) {
        int op = 0;
        switch (tok_.type) {
            case T_ASSIGN: case T_ADD_ASSIGN: case T_SUB_ASSIGN: case T_MUL_ASSIGN: case T_DIV_ASSIGN: case T_MOD_ASSIGN:
            case T_POW_ASSIGN:
                op = tok_.type;
                break;
            default:
                return left;
        }
        if (!isLvalue(left.get())) return left;
        advance();
        skipNewlines();
        auto n = node(N_ASSIGN, std::move(left), expr());
        n->op = op;
        return n;
    }

    // After a parenthesised group in print: continue parsing the rest of an expression.
    NodeP continueExpr(NodeP group) {
        pending_ = std::move(group);
        return expr();
    }
    NodeP pending_;

    NodeP ternary() {
        auto cond = orExpr();
        if (accept(T_QUESTION)) {
            skipNewlines();
            auto a = expr();
            skipNewlines();
            expect(T_COLON, ":");
            skipNewlines();
            auto b = expr();
            return node(N_COND, std::move(cond), std::move(a), std::move(b));
        }
        return cond;
    }

    NodeP orExpr() {
        auto left = andExpr();
        while (tok_.type == T_OR) {
            advance();
            skipNewlines();
            left = node(N_OR, std::move(left), andExpr());
        }
        return left;
    }

    NodeP andExpr() {
        auto left = inExpr();
        while (tok_.type == T_AND) {
            advance();
            skipNewlines();
            left = node(N_AND, std::move(left), inExpr());
        }
        return left;
    }

    NodeP inExpr() {
        auto left = matchExpr();
        while (tok_.type == T_IN && !noIn_) {
            advance();
            if (tok_.type != T_NAME) error("expected array name after 'in'");
            auto arr = variable(tok_.text);
            advance();
            left = node(N_IN, std::move(left), std::move(arr));
        }
        return left;
    }

    NodeP matchExpr() {
        auto left = comparison();
        while (tok_.type == T_TILDE || tok_.type == T_NOMATCH) {
            int op = tok_.type;
            advance();
            auto n = node(N_MATCH, std::move(left), comparison());
            n->op = op;
            left = std::move(n);
        }
        return left;
    }

    NodeP comparison() {
        auto left = concatExpr();
        switch (tok_.type) {
            case T_LT: case T_LE: case T_NE: case T_EQ: case T_GE:
                break;
            case T_GT:
                if (noGt_) return left;
                break;
            case T_PIPE:
                // cmd | getline [var]
                if (lex_peekGetline()) {
                    advance(); // |
                    advance(); // getline
                    auto n = node(N_GETLINE);
                    n->op = 2;
                    n->kids.push_back(std::move(left));
                    if (tok_.type == T_NAME || tok_.type == T_DOLLAR) n->kids.push_back(primaryLvalue());
                    return n;
                }
                return left;
            default:
                return left;
        }
        int op = tok_.type;
        advance();
        auto n = node(N_CMP, std::move(left), concatExpr());
        n->op = op;
        return n;
    }

    bool lex_peekGetline() {
        Lexer copy = lex_;
        return copy.next().type == T_GETLINE;
    }

    bool startsConcatOperand() const {
        switch (tok_.type) {
            case T_NUMBER: case T_STRING: case T_ERE: case T_NAME: case T_FUNC_NAME: case T_BUILTIN: case T_DOLLAR:
            case T_NOT: case T_LPAREN: case T_INCR: case T_DECR: case T_MINUS: case T_PLUS:
                return true;
            case T_IN:
                return false;
            default:
                return false;
        }
    }

    NodeP concatExpr() {
        auto left = additive();
        while (startsConcatOperand() && tok_.type != T_MINUS && tok_.type != T_PLUS && !(tok_.type == T_NOT)) {
            if (tok_.type == T_IN) break;
            left = node(N_CONCAT, std::move(left), additive());
        }
        return left;
    }

    NodeP additive() {
        auto left = multiplicative();
        while (tok_.type == T_PLUS || tok_.type == T_MINUS) {
            int op = tok_.type;
            advance();
            auto n = node(N_BINARY, std::move(left), multiplicative());
            n->op = op;
            left = std::move(n);
        }
        return left;
    }

    NodeP multiplicative() {
        auto left = unary();
        while (tok_.type == T_STAR || tok_.type == T_SLASH || tok_.type == T_PERCENT) {
            int op = tok_.type;
            advance();
            auto n = node(N_BINARY, std::move(left), unary());
            n->op = op;
            left = std::move(n);
        }
        return left;
    }

    NodeP unary() {
        if (tok_.type == T_NOT) {
            advance();
            return node(N_NOT, unary());
        }
        if (tok_.type == T_MINUS) {
            advance();
            return node(N_NEG, unary());
        }
        if (tok_.type == T_PLUS) {
            advance();
            return node(N_PLUS, unary());
        }
        return power();
    }

    NodeP power() {
        auto base = postfix();
        if (tok_.type == T_CARET) {
            advance();
            auto n = node(N_BINARY, std::move(base), unaryPower());
            n->op = T_CARET;
            return n;
        }
        return base;
    }

    // Right side of ^: right associative, allows unary minus (2^-1).
    NodeP unaryPower() {
        if (tok_.type == T_MINUS) {
            advance();
            return node(N_NEG, unaryPower());
        }
        if (tok_.type == T_PLUS) {
            advance();
            return unaryPower();
        }
        return power();
    }

    NodeP postfix() {
        if (tok_.type == T_INCR || tok_.type == T_DECR) {
            bool inc = tok_.type == T_INCR;
            advance();
            auto target = primaryLvalue();
            return node(inc ? N_PREINC : N_PREDEC, std::move(target));
        }
        auto p = primary();
        if (isLvalue(p.get())) {
            if (tok_.type == T_INCR) {
                advance();
                return node(N_POSTINC, std::move(p));
            }
            if (tok_.type == T_DECR) {
                advance();
                return node(N_POSTDEC, std::move(p));
            }
        }
        return p;
    }

    NodeP primaryLvalue() {
        if (tok_.type == T_DOLLAR) {
            advance();
            return node(N_FIELD, fieldOperand());
        }
        if (tok_.type != T_NAME) error("expected variable");
        auto name = tok_.text;
        advance();
        if (accept(T_LBRACKET)) {
            auto n = node(N_INDEX, variable(name));
            n->kids.push_back(subscript());
            return n;
        }
        return variable(name);
    }

    // The operand of $: a primary with ++/-- but not binary operators ($i+1 is ($i)+1).
    NodeP fieldOperand() {
        if (tok_.type == T_INCR || tok_.type == T_DECR) {
            bool inc = tok_.type == T_INCR;
            advance();
            return node(inc ? N_PREINC : N_PREDEC, primaryLvalue());
        }
        if (tok_.type == T_MINUS) {
            advance();
            return node(N_NEG, fieldOperand());
        }
        return primary();
    }

    NodeP primary() {
        if (pending_) return std::move(pending_);
        switch (tok_.type) {
            case T_NUMBER: {
                auto n = node(N_NUM);
                n->num = tok_.num;
                advance();
                return n;
            }
            case T_STRING: {
                auto n = node(N_STR);
                n->str = tok_.text;
                advance();
                return n;
            }
            case T_ERE: {
                auto n = node(N_REGEX);
                n->str = tok_.text;
                n->re = std::make_shared<Regex>(tok_.text, REG_EXTENDED);
                if (!n->re->ok()) error("bad regex /" + tok_.text + "/");
                advance();
                return n;
            }
            case T_DOLLAR: {
                advance();
                return node(N_FIELD, fieldOperand());
            }
            case T_LPAREN: {
                advance();
                noIn_ = 0;
                int savedGt = noGt_;
                noGt_ = 0;
                auto first = expr();
                if (tok_.type == T_COMMA) {
                    // (i, j) in arr
                    auto key = node(N_CONCAT);
                    key->op = 1;
                    key->kids.push_back(std::move(first));
                    while (accept(T_COMMA)) key->kids.push_back(expr());
                    expect(T_RPAREN, ")");
                    noGt_ = savedGt;
                    if (tok_.type != T_IN) error("expected 'in' after (list)");
                    advance();
                    if (tok_.type != T_NAME) error("expected array name");
                    auto arr = variable(tok_.text);
                    advance();
                    return node(N_IN, std::move(key), std::move(arr));
                }
                expect(T_RPAREN, ")");
                noGt_ = savedGt;
                return node(N_GROUP, std::move(first));
            }
            case T_GETLINE: {
                advance();
                auto n = node(N_GETLINE);
                n->op = 0; // from the main input
                n->kids.push_back(nullptr);
                if (tok_.type == T_NAME || tok_.type == T_DOLLAR) n->kids.push_back(primaryLvalue());
                if (tok_.type == T_LT) {
                    advance();
                    n->op = 1;
                    n->kids[0] = concatOperandNoGetline();
                }
                return n;
            }
            case T_FUNC_NAME: {
                auto name = tok_.text;
                advance();
                expect(T_LPAREN, "(");
                auto n = node(N_CALL);
                n->index = prog_.function(name);
                n->str = name;
                skipNewlines();
                if (tok_.type != T_RPAREN) {
                    do {
                        skipNewlines();
                        n->kids.push_back(expr());
                        skipNewlines();
                    } while (accept(T_COMMA));
                }
                expect(T_RPAREN, ")");
                return n;
            }
            case T_BUILTIN: {
                auto name = tok_.text;
                advance();
                auto n = node(N_BUILTIN);
                n->str = name;
                if (accept(T_LPAREN)) {
                    skipNewlines();
                    if (tok_.type != T_RPAREN) {
                        do {
                            skipNewlines();
                            n->kids.push_back(expr());
                            skipNewlines();
                        } while (accept(T_COMMA));
                    }
                    expect(T_RPAREN, ")");
                } else if (name != "length") {
                    error(name + " needs arguments");
                }
                return n;
            }
            case T_NAME: {
                auto name = tok_.text;
                advance();
                if (accept(T_LBRACKET)) {
                    auto n = node(N_INDEX, variable(name));
                    n->kids.push_back(subscript());
                    return n;
                }
                return variable(name);
            }
            case T_MINUS: {
                advance();
                return node(N_NEG, unary());
            }
            case T_NOT: {
                advance();
                return node(N_NOT, unary());
            }
            default:
                error("unexpected token");
        }
    }

    NodeP concatOperandNoGetline() {
        return primary();
    }
};

// ---- Interpreter ------------------------------------------------------------------------------------------------

enum Flow { F_NORMAL, F_BREAK, F_CONTINUE, F_NEXT, F_NEXTFILE, F_EXIT, F_RETURN };

struct Stream {
    FILE *file = nullptr;
    int fd = -1;        // pipe end for commands
    int pid = -1;
    std::string buffer; // for reading
    bool eof = false;
    bool reading = false;
};

class Interp {
public:
    Program &prog;
    std::vector<Cell> globals;
    std::vector<std::vector<Cell> *> frames;
    Value returnValue;
    int exitCode = 0;

    // Special variables.
    int NR, NF, FNR, FS, OFS, ORS, RS, FILENAME, SUBSEP, RSTART, RLENGTH, CONVFMT, OFMT, ENVIRON, ARGC, ARGV;

    // Current record.
    std::string record;
    std::vector<std::string> fieldStore;
    bool fieldsValid = false;
    bool recordValid = true;

    // Main input.
    std::vector<std::string> files;
    size_t fileIndex = 0;
    FILE *current = nullptr;
    bool inputDone = false;

    std::map<std::string, Stream> streams;
    std::unordered_map<std::string, std::shared_ptr<Regex>> regexCache;
    std::mt19937 rng{0};
    double seed = 0;

    explicit Interp(Program &p) : prog(p) {
        NR = p.global("NR");
        NF = p.global("NF");
        FNR = p.global("FNR");
        FS = p.global("FS");
        OFS = p.global("OFS");
        ORS = p.global("ORS");
        RS = p.global("RS");
        FILENAME = p.global("FILENAME");
        SUBSEP = p.global("SUBSEP");
        RSTART = p.global("RSTART");
        RLENGTH = p.global("RLENGTH");
        CONVFMT = p.global("CONVFMT");
        OFMT = p.global("OFMT");
        ENVIRON = p.global("ENVIRON");
        ARGC = p.global("ARGC");
        ARGV = p.global("ARGV");
        globals.resize(p.globalNames.size());
        g(FS).value = Value::string(" ");
        g(OFS).value = Value::string(" ");
        g(ORS).value = Value::string("\n");
        g(RS).value = Value::string("\n");
        g(SUBSEP).value = Value::string("\x1c");
        g(CONVFMT).value = Value::string("%.6g");
        g(OFMT).value = Value::string("%.6g");
        g(NR).value = Value::number(0);
        g(FNR).value = Value::number(0);
        g(RSTART).value = Value::number(0);
        g(RLENGTH).value = Value::number(-1);
        for (char **e = environ; e && *e; e++) {
            std::string kv = *e;
            auto eq = kv.find('=');
            if (eq != std::string::npos) g(ENVIRON).arr()[kv.substr(0, eq)] = input(kv.substr(eq + 1));
        }
    }

    Cell &g(int i) {
        if (static_cast<size_t>(i) >= globals.size()) globals.resize(static_cast<size_t>(i) + 1);
        return globals[static_cast<size_t>(i)];
    }

    // ---- conversions ----

    std::string numToString(double d, int fmtVar) {
        if (d == static_cast<double>(static_cast<long long>(d)) && std::abs(d) < 1e16) return std::to_string(static_cast<long long>(d));
        if (std::isnan(d)) return "nan";
        if (std::isinf(d)) return d > 0 ? "inf" : "-inf";
        char buf[64];
        std::snprintf(buf, sizeof(buf), toStr(g(fmtVar).value).c_str(), d);
        return buf;
    }

    std::string toStr(const Value &v) {
        switch (v.kind) {
            case Value::Num: return numToString(v.num, CONVFMT);
            case Value::Uninit: return "";
            default: return v.str;
        }
    }

    std::string toOutput(const Value &v) {
        return v.kind == Value::Num ? numToString(v.num, OFMT) : toStr(v);
    }

    double toNum(const Value &v) {
        switch (v.kind) {
            case Value::Num: case Value::StrNum: return v.num;
            case Value::Uninit: return 0;
            default: return std::strtod(v.str.c_str(), nullptr);
        }
    }

    bool toBool(const Value &v) {
        switch (v.kind) {
            case Value::Num: return v.num != 0;
            case Value::StrNum: return v.num != 0;
            case Value::Str: return !v.str.empty();
            default: return false;
        }
    }

    std::shared_ptr<Regex> regex(const std::string &pattern) {
        auto it = regexCache.find(pattern);
        if (it != regexCache.end()) return it->second;
        auto re = std::make_shared<Regex>(pattern, REG_EXTENDED);
        if (!re->ok()) die("bad regex: " + pattern);
        if (regexCache.size() > 500) regexCache.clear();
        regexCache[pattern] = re;
        return re;
    }

    std::shared_ptr<Regex> regexOf(Node *n) {
        if (n->kind == N_REGEX) return n->re;
        return regex(toStr(eval(n)));
    }

    // ---- fields ----

    void setRecord(std::string r) {
        record = std::move(r);
        recordValid = true;
        fieldsValid = false;
    }

    void splitRecord() {
        if (fieldsValid) return;
        fieldStore = splitFields(record, toStr(g(FS).value));
        fieldsValid = true;
        g(NF).value = Value::number(static_cast<double>(fieldStore.size()));
    }

    std::vector<std::string> splitFields(const std::string &s, const std::string &fs) {
        std::vector<std::string> out;
        if (fs == " ") {
            size_t p = 0;
            while (p < s.size()) {
                while (p < s.size() && (s[p] == ' ' || s[p] == '\t' || s[p] == '\n')) p++;
                if (p >= s.size()) break;
                size_t e = p;
                while (e < s.size() && s[e] != ' ' && s[e] != '\t' && s[e] != '\n') e++;
                out.push_back(s.substr(p, e - p));
                p = e;
            }
            return out;
        }
        if (s.empty()) return out;
        if (fs.size() == 1 && fs != "\\") {
            size_t start = 0;
            while (true) {
                auto e = s.find(fs[0], start);
                out.push_back(s.substr(start, e == std::string::npos ? std::string::npos : e - start));
                if (e == std::string::npos) break;
                start = e + 1;
            }
            return out;
        }
        auto re = regex(fs);
        size_t pos = 0;
        regmatch_t m;
        while (pos <= s.size() && re->search(s.c_str() + pos, &m, 1, pos ? REG_NOTBOL : 0) && m.rm_eo > m.rm_so) {
            out.push_back(s.substr(pos, static_cast<size_t>(m.rm_so)));
            pos += static_cast<size_t>(m.rm_eo);
        }
        out.push_back(s.substr(pos));
        return out;
    }

    Value getField(long i) {
        if (i < 0) die("trying to access out of range field " + std::to_string(i));
        if (i == 0) {
            if (!recordValid) rebuildRecord();
            return input(record);
        }
        splitRecord();
        if (static_cast<size_t>(i) > fieldStore.size()) return Value();
        return input(fieldStore[static_cast<size_t>(i) - 1]);
    }

    void rebuildRecord() {
        std::string ofs = toStr(g(OFS).value), r;
        for (size_t k = 0; k < fieldStore.size(); k++) r += (k ? ofs : "") + fieldStore[k];
        record = r;
        recordValid = true;
    }

    void setField(long i, const std::string &v) {
        if (i < 0) die("trying to access out of range field " + std::to_string(i));
        if (i == 0) {
            setRecord(v);
            return;
        }
        splitRecord();
        if (static_cast<size_t>(i) > fieldStore.size()) fieldStore.resize(static_cast<size_t>(i));
        fieldStore[static_cast<size_t>(i) - 1] = v;
        g(NF).value = Value::number(static_cast<double>(fieldStore.size()));
        recordValid = false;
        rebuildRecord();
    }

    void setNF(long n) {
        splitRecord();
        fieldStore.resize(static_cast<size_t>(std::max(0L, n)));
        rebuildRecord();
    }

    // ---- variables ----

    Cell &cellOf(Node *n) {
        if (n->kind == N_LOCAL) return (*frames.back())[static_cast<size_t>(n->index)];
        if (n->index == NF) splitRecord();
        return g(n->index);
    }

    Array &arrayOf(Node *n) {
        return cellOf(n).arr();
    }

    std::string key(Node *n) {
        if (n->kind == N_CONCAT && n->op == 1) {
            std::string sep = toStr(g(SUBSEP).value), out;
            for (size_t i = 0; i < n->kids.size(); i++) out += (i ? sep : "") + toStr(eval(n->kid(i)));
            return out;
        }
        return toStr(eval(n));
    }

    Value load(Node *n) {
        switch (n->kind) {
            case N_VAR: case N_LOCAL: return cellOf(n).value;
            case N_INDEX: return arrayOf(n->kid(0))[key(n->kid(1))];
            case N_FIELD: return getField(static_cast<long>(toNum(eval(n->kid(0)))));
            default: return eval(n);
        }
    }

    void store(Node *n, const Value &v) {
        switch (n->kind) {
            case N_VAR: case N_LOCAL: {
                cellOf(n).value = v;
                if (n->kind == N_VAR) {
                    if (n->index == NF) setNF(static_cast<long>(toNum(v)));
                }
                break;
            }
            case N_INDEX: arrayOf(n->kid(0))[key(n->kid(1))] = v; break;
            case N_FIELD: setField(static_cast<long>(toNum(eval(n->kid(0)))), toStr(v)); break;
            default: break;
        }
    }

    // ---- expressions ----

    int compare(const Value &a, const Value &b) {
        bool numA = a.kind == Value::Num || a.kind == Value::StrNum || a.kind == Value::Uninit;
        bool numB = b.kind == Value::Num || b.kind == Value::StrNum || b.kind == Value::Uninit;
        if (numA && numB) {
            double x = toNum(a), y = toNum(b);
            return x < y ? -1 : x > y ? 1 : 0;
        }
        auto x = toStr(a), y = toStr(b);
        return x < y ? -1 : x > y ? 1 : 0;
    }

    bool matchRecord(Node *pattern) {
        if (pattern->kind == N_REGEX) return pattern->re->matches(record.empty() && !recordValid ? (rebuildRecord(), record) : record);
        return toBool(eval(pattern));
    }

    Value eval(Node *n) {
        switch (n->kind) {
            case N_NUM: return Value::number(n->num);
            case N_STR: return Value::string(n->str);
            case N_REGEX: {
                if (!recordValid) rebuildRecord();
                return Value::number(n->re->matches(record) ? 1 : 0);
            }
            case N_GROUP: return eval(n->kid(0));
            case N_VAR: case N_LOCAL: case N_INDEX: case N_FIELD: return load(n);
            case N_ASSIGN: {
                Value v;
                if (n->op == T_ASSIGN) {
                    v = eval(n->kid(1));
                } else {
                    double rhs = toNum(eval(n->kid(1)));
                    double lhs = toNum(load(n->kid(0)));
                    v = Value::number(arith(n->op == T_ADD_ASSIGN ? T_PLUS : n->op == T_SUB_ASSIGN ? T_MINUS : n->op == T_MUL_ASSIGN ? T_STAR
                                                : n->op == T_DIV_ASSIGN ? T_SLASH : n->op == T_MOD_ASSIGN ? T_PERCENT : T_CARET,
                                            lhs, rhs));
                }
                store(n->kid(0), v);
                return v;
            }
            case N_COND: return toBool(eval(n->kid(0))) ? eval(n->kid(1)) : eval(n->kid(2));
            case N_AND: return Value::number(toBool(eval(n->kid(0))) && toBool(eval(n->kid(1))) ? 1 : 0);
            case N_OR: return Value::number(toBool(eval(n->kid(0))) || toBool(eval(n->kid(1))) ? 1 : 0);
            case N_NOT: return Value::number(toBool(eval(n->kid(0))) ? 0 : 1);
            case N_IN: {
                auto k = key(n->kid(0));
                auto &arr = arrayOf(n->kid(1));
                return Value::number(arr.count(k) ? 1 : 0);
            }
            case N_MATCH: {
                auto s = toStr(eval(n->kid(0)));
                bool m = regexOf(n->kid(1))->matches(s);
                return Value::number(m == (n->op == T_TILDE) ? 1 : 0);
            }
            case N_BINARY: return Value::number(arith(n->op, toNum(eval(n->kid(0))), toNum(eval(n->kid(1)))));
            case N_CMP: {
                int c = compare(eval(n->kid(0)), eval(n->kid(1)));
                bool r = false;
                switch (n->op) {
                    case T_LT: r = c < 0; break;
                    case T_LE: r = c <= 0; break;
                    case T_GT: r = c > 0; break;
                    case T_GE: r = c >= 0; break;
                    case T_EQ: r = c == 0; break;
                    case T_NE: r = c != 0; break;
                    default: break;
                }
                return Value::number(r ? 1 : 0);
            }
            case N_CONCAT: {
                if (n->op == 1) return Value::string(key(n));
                return Value::string(toStr(eval(n->kid(0))) + toStr(eval(n->kid(1))));
            }
            case N_NEG: return Value::number(-toNum(eval(n->kid(0))));
            case N_PLUS: return Value::number(toNum(eval(n->kid(0))));
            case N_PREINC: case N_PREDEC: {
                double v = toNum(load(n->kid(0))) + (n->kind == N_PREINC ? 1 : -1);
                store(n->kid(0), Value::number(v));
                return Value::number(v);
            }
            case N_POSTINC: case N_POSTDEC: {
                double v = toNum(load(n->kid(0)));
                store(n->kid(0), Value::number(v + (n->kind == N_POSTINC ? 1 : -1)));
                return Value::number(v);
            }
            case N_CALL: return call(n);
            case N_BUILTIN: return builtin(n);
            case N_GETLINE: return getline(n);
            default: break;
        }
        return Value();
    }

    double arith(int op, double a, double b) {
        switch (op) {
            case T_PLUS: return a + b;
            case T_MINUS: return a - b;
            case T_STAR: return a * b;
            case T_SLASH:
                if (b == 0) die("division by zero");
                return a / b;
            case T_PERCENT:
                if (b == 0) die("division by zero in %");
                return std::fmod(a, b);
            case T_CARET: return std::pow(a, b);
            default: return 0;
        }
    }

    Value call(Node *n) {
        auto &f = prog.functions[static_cast<size_t>(n->index)];
        if (n->kids.size() > f.params.size()) die("function " + f.name + " called with too many arguments");
        std::vector<Cell> frame(f.params.size());
        for (size_t i = 0; i < n->kids.size(); i++) {
            Node *arg = n->kid(i);
            if (arg->kind == N_VAR || arg->kind == N_LOCAL) {
                auto &c = cellOf(arg);
                if (c.array) {
                    frame[i].array = c.array; // arrays by reference
                    continue;
                }
                if (c.value.kind == Value::Uninit) {
                    // Could be used as an array by the callee: share a fresh one.
                    c.array = std::make_shared<Array>();
                    frame[i].array = c.array;
                    continue;
                }
            }
            frame[i].value = eval(arg);
        }
        if (frames.size() > 1000) die("function call nesting too deep");
        frames.push_back(&frame);
        returnValue = Value();
        auto flow = exec(f.body.get());
        frames.pop_back();
        if (flow == F_EXIT) {
            pendingExit = true;
        }
        if (flow == F_NEXT) pendingNext = true;
        return returnValue;
    }
    bool pendingExit = false, pendingNext = false;

    std::string format(const std::string &fmt, Node *n, size_t firstArg) {
        std::string out;
        size_t arg = firstArg;
        auto nextArg = [&]() -> Value { return arg < n->kids.size() ? eval(n->kid(arg++)) : Value(); };
        for (size_t i = 0; i < fmt.size(); i++) {
            if (fmt[i] != '%') {
                out += fmt[i];
                continue;
            }
            if (i + 1 < fmt.size() && fmt[i + 1] == '%') {
                out += '%';
                i++;
                continue;
            }
            std::string spec = "%";
            size_t j = i + 1;
            while (j < fmt.size() && std::strchr("-+ #0", fmt[j])) spec += fmt[j++];
            auto number = [&]() {
                if (j < fmt.size() && fmt[j] == '*') {
                    spec += std::to_string(static_cast<int>(toNum(nextArg())));
                    j++;
                    return;
                }
                while (j < fmt.size() && std::isdigit(static_cast<unsigned char>(fmt[j]))) spec += fmt[j++];
            };
            number();
            if (j < fmt.size() && fmt[j] == '.') {
                spec += fmt[j++];
                number();
            }
            while (j < fmt.size() && std::strchr("hlLqjzt", fmt[j])) j++;
            if (j >= fmt.size()) {
                out += spec;
                break;
            }
            char conv = fmt[j];
            char buf[512];
            switch (conv) {
                case 'd': case 'i':
                    spec += "lld";
                    std::snprintf(buf, sizeof(buf), spec.c_str(), static_cast<long long>(toNum(nextArg())));
                    out += buf;
                    break;
                case 'o': case 'x': case 'X': case 'u':
                    spec += "ll";
                    spec += conv;
                    std::snprintf(buf, sizeof(buf), spec.c_str(), static_cast<unsigned long long>(static_cast<long long>(toNum(nextArg()))));
                    out += buf;
                    break;
                case 'e': case 'E': case 'f': case 'F': case 'g': case 'G': case 'a': case 'A':
                    spec += conv;
                    std::snprintf(buf, sizeof(buf), spec.c_str(), toNum(nextArg()));
                    out += buf;
                    break;
                case 'c': {
                    auto v = nextArg();
                    std::string s;
                    if (v.kind == Value::Num) {
                        // a code point, as UTF-8
                        auto cp = static_cast<unsigned>(v.num);
                        if (cp < 0x80) s += static_cast<char>(cp);
                        else if (cp < 0x800) {
                            s += static_cast<char>(0xC0 | (cp >> 6));
                            s += static_cast<char>(0x80 | (cp & 0x3F));
                        } else {
                            s += static_cast<char>(0xE0 | (cp >> 12));
                            s += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
                            s += static_cast<char>(0x80 | (cp & 0x3F));
                        }
                    } else {
                        s = toStr(v).substr(0, 1);
                    }
                    spec += 's';
                    std::snprintf(buf, sizeof(buf), spec.c_str(), s.c_str());
                    out += buf;
                    break;
                }
                case 's': {
                    auto s = toStr(nextArg());
                    spec += 's';
                    int len = std::snprintf(nullptr, 0, spec.c_str(), s.c_str());
                    std::string tmp(static_cast<size_t>(len) + 1, '\0');
                    std::snprintf(tmp.data(), tmp.size(), spec.c_str(), s.c_str());
                    tmp.pop_back();
                    out += tmp;
                    break;
                }
                default:
                    out += spec + conv;
            }
            i = j;
        }
        return out;
    }

    // sub/gsub: replace in target, returns the number of replacements.
    int substitute(Node *reNode, const std::string &repl, Node *target, bool global) {
        auto re = regexOf(reNode);
        std::string s = target ? toStr(load(target)) : (recordValid ? record : (rebuildRecord(), record));
        std::string out;
        size_t pos = 0;
        int count = 0;
        regmatch_t m;
        while (pos <= s.size() && re->search(s.c_str() + pos, &m, 1, pos ? REG_NOTBOL : 0)) {
            size_t so = pos + static_cast<size_t>(m.rm_so), eo = pos + static_cast<size_t>(m.rm_eo);
            out.append(s, pos, so - pos);
            for (size_t k = 0; k < repl.size(); k++) {
                if (repl[k] == '\\' && k + 1 < repl.size() && (repl[k + 1] == '&' || repl[k + 1] == '\\')) {
                    out += repl[++k];
                } else if (repl[k] == '&') {
                    out.append(s, so, eo - so);
                } else {
                    out += repl[k];
                }
            }
            count++;
            if (eo == so) {
                if (so < s.size()) out += s[so];
                pos = so + 1;
            } else {
                pos = eo;
            }
            if (!global) break;
        }
        if (!count) return 0;
        if (pos < s.size()) out.append(s, pos, std::string::npos);
        if (target) store(target, Value::string(out));
        else setRecord(out);
        return count;
    }

    Value builtin(Node *n) {
        const auto &name = n->str;
        auto arg = [&](size_t i) { return eval(n->kid(i)); };
        auto argc = n->kids.size();
        if (name == "length") {
            if (argc == 0) return Value::number(static_cast<double>(utf8Length(recordValid ? record : (rebuildRecord(), record))));
            Node *a = n->kid(0);
            if (a->kind == N_VAR || a->kind == N_LOCAL) {
                auto &c = cellOf(a);
                if (c.array) return Value::number(static_cast<double>(c.array->size()));
            }
            return Value::number(static_cast<double>(utf8Length(toStr(arg(0)))));
        }
        if (name == "substr") {
            auto s = toStr(arg(0));
            auto chars = utf8Chars(s);
            double m = std::round(toNum(arg(1)));
            double len = argc > 2 ? std::round(toNum(arg(2))) : 1e18;
            double start = std::max(1.0, m), end = std::min(static_cast<double>(chars.size()) + 1, m + len);
            std::string out;
            for (double k = start; k < end; k++) out += chars[static_cast<size_t>(k) - 1];
            return Value::string(out);
        }
        if (name == "index") {
            auto s = toStr(arg(0)), t = toStr(arg(1));
            auto p = s.find(t);
            return Value::number(p == std::string::npos ? 0 : static_cast<double>(utf8Length(s.substr(0, p)) + 1));
        }
        if (name == "split") {
            auto s = toStr(arg(0));
            auto &arr = arrayOf(n->kid(1));
            arr.clear();
            std::vector<std::string> parts;
            if (argc > 2) {
                Node *sep = n->kid(2);
                if (sep->kind == N_REGEX) {
                    parts = splitRegex(s, sep->re);
                } else {
                    parts = splitFields(s, toStr(eval(sep)));
                }
            } else {
                parts = splitFields(s, toStr(g(FS).value));
            }
            for (size_t k = 0; k < parts.size(); k++) arr[std::to_string(k + 1)] = input(parts[k]);
            return Value::number(static_cast<double>(parts.size()));
        }
        if (name == "sub" || name == "gsub") {
            if (argc < 2) die(name + " needs 2 or 3 arguments");
            return Value::number(substitute(n->kid(0), toStr(arg(1)), argc > 2 ? n->kid(2) : nullptr, name == "gsub"));
        }
        if (name == "match") {
            auto s = toStr(arg(0));
            auto re = regexOf(n->kid(1));
            regmatch_t m;
            if (re->search(s.c_str(), &m, 1)) {
                g(RSTART).value = Value::number(static_cast<double>(utf8Length(s.substr(0, static_cast<size_t>(m.rm_so))) + 1));
                g(RLENGTH).value = Value::number(static_cast<double>(utf8Length(s.substr(static_cast<size_t>(m.rm_so), static_cast<size_t>(m.rm_eo - m.rm_so)))));
            } else {
                g(RSTART).value = Value::number(0);
                g(RLENGTH).value = Value::number(-1);
            }
            return g(RSTART).value;
        }
        if (name == "sprintf") {
            if (argc == 0) return Value::string("");
            return Value::string(format(toStr(arg(0)), n, 1));
        }
        if (name == "sin") return Value::number(std::sin(toNum(arg(0))));
        if (name == "cos") return Value::number(std::cos(toNum(arg(0))));
        if (name == "atan2") return Value::number(std::atan2(toNum(arg(0)), toNum(arg(1))));
        if (name == "exp") return Value::number(std::exp(toNum(arg(0))));
        if (name == "log") return Value::number(std::log(toNum(arg(0))));
        if (name == "sqrt") return Value::number(std::sqrt(toNum(arg(0))));
        if (name == "int") return Value::number(std::trunc(toNum(arg(0))));
        if (name == "rand") return Value::number(std::uniform_real_distribution<double>(0, 1)(rng));
        if (name == "srand") {
            double old = seed;
            seed = argc ? toNum(arg(0)) : static_cast<double>(std::time(nullptr));
            rng.seed(static_cast<unsigned>(seed));
            return Value::number(old);
        }
        if (name == "tolower" || name == "toupper") {
            auto s = toStr(arg(0));
            for (auto &c : s) c = static_cast<char>(name == "tolower" ? std::tolower(static_cast<unsigned char>(c)) : std::toupper(static_cast<unsigned char>(c)));
            return Value::string(s);
        }
        if (name == "system") {
            std::fflush(stdout);
            for (auto &[k, st] : streams) {
                if (st.file) std::fflush(st.file);
            }
            int r = cc::run({"sh", "-c", toStr(arg(0))});
            return Value::number(r);
        }
        if (name == "close") {
            return Value::number(closeStream(toStr(arg(0))));
        }
        if (name == "fflush") {
            std::fflush(stdout);
            for (auto &[k, st] : streams) {
                if (st.file) std::fflush(st.file);
            }
            return Value::number(0);
        }
        die("unknown function " + name);
    }

    std::vector<std::string> splitRegex(const std::string &s, const std::shared_ptr<Regex> &re) {
        std::vector<std::string> out;
        if (s.empty()) return out;
        size_t pos = 0;
        regmatch_t m;
        while (pos <= s.size() && re->search(s.c_str() + pos, &m, 1, pos ? REG_NOTBOL : 0) && m.rm_eo > m.rm_so) {
            out.push_back(s.substr(pos, static_cast<size_t>(m.rm_so)));
            pos += static_cast<size_t>(m.rm_eo);
        }
        out.push_back(s.substr(pos));
        return out;
    }

    static size_t utf8Length(const std::string &s) {
        size_t n = 0;
        for (unsigned char c : s) n += (c & 0xC0) != 0x80;
        return n;
    }

    static std::vector<std::string> utf8Chars(const std::string &s) {
        std::vector<std::string> out;
        for (size_t i = 0; i < s.size();) {
            size_t len = 1;
            auto c = static_cast<unsigned char>(s[i]);
            if (c >= 0xF0) len = 4;
            else if (c >= 0xE0) len = 3;
            else if (c >= 0xC0) len = 2;
            out.push_back(s.substr(i, len));
            i += len;
        }
        return out;
    }

    // ---- input ----

    // Read one record from a FILE (RS "\n", a single character, or "" for paragraph mode).
    bool readRecord(FILE *f, std::string &out) {
        auto rs = toStr(g(RS).value);
        out.clear();
        int c;
        if (rs.empty()) {
            // paragraph mode: records separated by blank lines
            while ((c = std::fgetc(f)) == '\n') {}
            if (c == EOF) return false;
            out += static_cast<char>(c);
            int prev = c;
            while ((c = std::fgetc(f)) != EOF) {
                if (c == '\n' && prev == '\n') {
                    out.pop_back();
                    while ((c = std::fgetc(f)) == '\n') {}
                    if (c != EOF) std::ungetc(c, f);
                    return true;
                }
                out += static_cast<char>(c);
                prev = c;
            }
            if (!out.empty() && out.back() == '\n') out.pop_back();
            return true;
        }
        char sep = rs[0];
        bool any = false;
        while ((c = std::fgetc(f)) != EOF) {
            any = true;
            if (c == sep) return true;
            out += static_cast<char>(c);
        }
        return any;
    }

    bool openNextFile() {
        while (fileIndex < files.size()) {
            auto arg = files[fileIndex++];
            if (arg.empty()) continue;
            auto eq = arg.find('=');
            if (eq != std::string::npos && eq > 0 && (std::isalpha(static_cast<unsigned char>(arg[0])) || arg[0] == '_')) {
                bool ident = true;
                for (size_t k = 0; k < eq; k++) ident &= std::isalnum(static_cast<unsigned char>(arg[k])) || arg[k] == '_';
                if (ident) {
                    // var=value assignment between files
                    g(prog.global(arg.substr(0, eq))).value = input(unescape(arg.substr(eq + 1)));
                    continue;
                }
            }
            current = arg == "-" ? stdin : std::fopen(arg.c_str(), "r");
            if (!current) {
                warnErrno(arg);
                exitCode = 2;
                continue;
            }
            g(FILENAME).value = Value::string(arg);
            g(FNR).value = Value::number(0);
            return true;
        }
        return false;
    }

    static std::string unescape(const std::string &s) {
        std::string out;
        for (size_t i = 0; i < s.size(); i++) {
            if (s[i] == '\\' && i + 1 < s.size()) {
                char e = s[++i];
                out += e == 'n' ? '\n' : e == 't' ? '\t' : e == '\\' ? '\\' : e;
            } else {
                out += s[i];
            }
        }
        return out;
    }

    // Next main-input record into `out`; false at the end of all input.
    bool nextMainRecord(std::string &out) {
        while (true) {
            if (!current) {
                if (inputDone || !openNextFile()) {
                    inputDone = true;
                    return false;
                }
            }
            if (readRecord(current, out)) {
                g(NR).value = Value::number(toNum(g(NR).value) + 1);
                g(FNR).value = Value::number(toNum(g(FNR).value) + 1);
                return true;
            }
            if (current != stdin) std::fclose(current);
            current = nullptr;
        }
    }

    void skipFile() {
        if (current && current != stdin) std::fclose(current);
        current = nullptr;
    }

    Value getline(Node *n) {
        Node *target = n->kid(1);
        std::string rec;
        if (n->op == 0) {
            if (!nextMainRecord(rec)) return Value::number(0);
        } else {
            auto name = toStr(eval(n->kid(0)));
            auto &st = readStream(name, n->op == 2);
            if (!st.file) return Value::number(-1);
            if (!readRecord(st.file, rec)) return Value::number(0);
            if (!target) g(NR).value = Value::number(toNum(g(NR).value) + 1);
        }
        if (target) store(target, input(rec));
        else setRecord(rec);
        return Value::number(1);
    }

    // ---- output ----

    Stream &readStream(const std::string &name, bool command) {
        auto it = streams.find(name);
        if (it != streams.end()) return it->second;
        Stream st;
        st.reading = true;
        if (command) {
            int32_t fds[2];
            if (cc_pipe(fds) == 0) {
                cc::SpawnOptions o;
                o.out = fds[1];
                std::fflush(stdout);
                st.pid = cc::spawn({"sh", "-c", name}, o);
                close(fds[1]);
                st.file = fdopen(fds[0], "r");
            }
        } else {
            st.file = name == "-" || name == "/dev/stdin" ? stdin : std::fopen(name.c_str(), "r");
        }
        return streams[name] = st;
    }

    FILE *writeStream(const std::string &name, int mode) {
        auto it = streams.find(name);
        if (it != streams.end()) return it->second.file;
        Stream st;
        if (mode == T_PIPE) {
            int32_t fds[2];
            if (cc_pipe(fds) != 0) die("cannot create a pipe for " + name);
            cc::SpawnOptions o;
            o.in = fds[0];
            std::fflush(stdout);
            st.pid = cc::spawn({"sh", "-c", name}, o);
            close(fds[0]);
            st.file = fdopen(fds[1], "w");
        } else if (name == "/dev/stdout" || name == "-") {
            st.file = stdout;
        } else if (name == "/dev/stderr") {
            st.file = stderr;
        } else {
            st.file = std::fopen(name.c_str(), mode == T_APPEND ? "a" : "w");
            if (!st.file) die("can't redirect to " + name + ": " + std::strerror(errno));
        }
        streams[name] = st;
        return st.file;
    }

    int closeStream(const std::string &name) {
        auto it = streams.find(name);
        if (it == streams.end()) return -1;
        int r = 0;
        auto &st = it->second;
        if (st.file && st.file != stdout && st.file != stderr && st.file != stdin) r = std::fclose(st.file);
        if (st.pid > 0) {
            int status = 0;
            cc::wait(st.pid, &status);
            r = status;
        }
        streams.erase(it);
        return r;
    }

    void closeAll() {
        std::fflush(stdout);
        while (!streams.empty()) closeStream(streams.begin()->first);
    }

    // ---- statements ----

    Flow exec(Node *n) {
        if (!n) return F_NORMAL;
        switch (n->kind) {
            case S_BLOCK:
                for (auto &k : n->kids) {
                    auto f = exec(k.get());
                    if (f != F_NORMAL) return f;
                }
                return F_NORMAL;
            case S_EXPR:
                eval(n->kid(0));
                return checkPending();
            case S_PRINT: case S_PRINTF: {
                std::string out;
                if (n->kind == S_PRINT) {
                    if (n->kids.size() == 1) {
                        if (!recordValid) rebuildRecord();
                        out = record;
                    } else {
                        auto ofs = toStr(g(OFS).value);
                        for (size_t i = 1; i < n->kids.size(); i++) out += (i > 1 ? ofs : "") + toOutput(eval(n->kid(i)));
                    }
                    out += toStr(g(ORS).value);
                } else {
                    out = format(toStr(eval(n->kid(1))), n, 2);
                }
                FILE *f = stdout;
                if (n->kid(0)) f = writeStream(toStr(eval(n->kid(0))), n->op);
                std::fwrite(out.data(), 1, out.size(), f);
                if (f != stdout) std::fflush(f);
                return checkPending();
            }
            case S_IF:
                if (toBool(eval(n->kid(0)))) return exec(n->kid(1));
                return exec(n->kid(2));
            case S_WHILE:
                while (toBool(eval(n->kid(0)))) {
                    auto f = exec(n->kid(1));
                    if (f == F_BREAK) break;
                    if (f != F_NORMAL && f != F_CONTINUE) return f;
                }
                return F_NORMAL;
            case S_DO:
                do {
                    auto f = exec(n->kid(0));
                    if (f == F_BREAK) break;
                    if (f != F_NORMAL && f != F_CONTINUE) return f;
                } while (toBool(eval(n->kid(1))));
                return F_NORMAL;
            case S_FOR:
                if (n->kid(0)) exec(n->kid(0));
                while (!n->kid(1) || toBool(eval(n->kid(1)))) {
                    auto f = exec(n->kid(3));
                    if (f == F_BREAK) break;
                    if (f != F_NORMAL && f != F_CONTINUE) return f;
                    if (n->kid(2)) exec(n->kid(2));
                }
                return F_NORMAL;
            case S_FORIN: {
                auto &arr = arrayOf(n->kid(1));
                std::vector<std::string> keys;
                keys.reserve(arr.size());
                for (auto &[k, v] : arr) keys.push_back(k);
                // Numeric keys in order, then the rest sorted: predictable output.
                std::sort(keys.begin(), keys.end(), [](const std::string &a, const std::string &b) {
                    double x, y;
                    bool na = looksNumeric(a, x), nb = looksNumeric(b, y);
                    if (na && nb) return x < y;
                    if (na != nb) return na;
                    return a < b;
                });
                for (auto &k : keys) {
                    if (!arr.count(k)) continue;
                    store(n->kid(0), input(k));
                    auto f = exec(n->kid(2));
                    if (f == F_BREAK) break;
                    if (f != F_NORMAL && f != F_CONTINUE) return f;
                }
                return F_NORMAL;
            }
            case S_BREAK: return F_BREAK;
            case S_CONTINUE: return F_CONTINUE;
            case S_NEXT: return F_NEXT;
            case S_NEXTFILE: return F_NEXTFILE;
            case S_EXIT:
                if (n->kid(0)) exitCode = static_cast<int>(toNum(eval(n->kid(0))));
                return F_EXIT;
            case S_RETURN:
                returnValue = n->kid(0) ? eval(n->kid(0)) : Value();
                return F_RETURN;
            case S_DELETE:
                arrayOf(n->kid(0)).erase(key(n->kid(1)));
                return F_NORMAL;
            case S_DELETE_ALL:
                arrayOf(n->kid(0)).clear();
                return F_NORMAL;
            default:
                eval(n);
                return checkPending();
        }
    }

    Flow checkPending() {
        if (pendingExit) {
            pendingExit = false;
            return F_EXIT;
        }
        if (pendingNext) {
            pendingNext = false;
            return F_NEXT;
        }
        return F_NORMAL;
    }

    int run() {
        frames.push_back(&globals); // locals of the main program are never used
        bool exiting = false;
        for (auto &r : prog.rules) {
            if (r.kind != Rule::Begin) continue;
            if (exec(r.action.get()) == F_EXIT) {
                exiting = true;
                break;
            }
        }
        bool hasMain = false;
        for (auto &r : prog.rules) hasMain |= r.kind != Rule::Begin;
        if (!exiting && hasMain) {
            std::string rec;
            while (!exiting && nextMainRecord(rec)) {
                setRecord(rec);
                for (auto &r : prog.rules) {
                    if (r.kind != Rule::Main) continue;
                    bool match;
                    if (!r.pattern) {
                        match = true;
                    } else if (r.pattern2) {
                        if (r.inRange) {
                            match = true;
                            if (matchRecord(r.pattern2.get())) r.inRange = false;
                        } else {
                            match = matchRecord(r.pattern.get());
                            if (match && !matchRecord(r.pattern2.get())) r.inRange = true;
                        }
                    } else {
                        match = matchRecord(r.pattern.get());
                    }
                    if (!match) continue;
                    Flow f;
                    if (r.action) {
                        f = exec(r.action.get());
                    } else {
                        if (!recordValid) rebuildRecord();
                        std::string out = record + toStr(g(ORS).value);
                        std::fwrite(out.data(), 1, out.size(), stdout);
                        f = F_NORMAL;
                    }
                    if (f == F_NEXT) break;
                    if (f == F_NEXTFILE) {
                        skipFile();
                        break;
                    }
                    if (f == F_EXIT) {
                        exiting = true;
                        break;
                    }
                }
            }
        }
        for (auto &r : prog.rules) {
            if (r.kind != Rule::End) continue;
            if (exec(r.action.get()) == F_EXIT) break;
        }
        closeAll();
        return exitCode;
    }
};

int cmdAwk(Args &a) {
    std::string program;
    std::vector<std::pair<std::string, std::string>> assigns;
    std::string fs;
    bool haveProgram = false;
    size_t i = 1;
    for (; i < a.size(); i++) {
        auto &o = a[i];
        if (o == "--") {
            i++;
            break;
        }
        if (o.size() < 2 || o[0] != '-') break;
        if (o == "-f" && i + 1 < a.size()) {
            auto t = readFile(a[++i]);
            if (!t) return warnErrno(a[i]);
            program += *t + "\n";
            haveProgram = true;
        } else if (o == "-F" && i + 1 < a.size()) {
            fs = a[++i];
        } else if (o.rfind("-F", 0) == 0) {
            fs = o.substr(2);
        } else if (o == "-v" && i + 1 < a.size()) {
            auto kv = a[++i];
            auto eq = kv.find('=');
            if (eq == std::string::npos) die("-v needs NAME=VALUE");
            assigns.emplace_back(kv.substr(0, eq), kv.substr(eq + 1));
        } else if (o.rfind("-v", 0) == 0) {
            auto kv = o.substr(2);
            auto eq = kv.find('=');
            if (eq == std::string::npos) die("-v needs NAME=VALUE");
            assigns.emplace_back(kv.substr(0, eq), kv.substr(eq + 1));
        } else {
            die("unknown option " + o + "\nusage: awk [-F FS] [-v VAR=VALUE] 'PROGRAM' | -f FILE [FILE...]");
        }
    }
    if (!haveProgram) {
        if (i >= a.size()) die("usage: awk [-F FS] [-v VAR=VALUE] 'PROGRAM' | -f FILE [FILE...]");
        program = a[i++];
    }
    Program prog;
    Parser parser(program, prog);
    parser.parseProgram();
    Interp interp(prog);
    if (!fs.empty()) interp.g(interp.FS).value = Value::string(fs == "t" ? "\t" : Interp::unescape(fs));
    for (auto &[k, v] : assigns) interp.g(prog.global(k)).value = input(Interp::unescape(v));
    interp.globals.resize(prog.globalNames.size());
    interp.files.assign(a.begin() + static_cast<long>(i), a.end());
    if (interp.files.empty()) interp.files.push_back("-");
    auto &argv = interp.g(interp.ARGV).arr();
    argv["0"] = Value::string("awk");
    for (size_t k = i; k < a.size(); k++) argv[std::to_string(k - i + 1)] = input(a[k]);
    interp.g(interp.ARGC).value = Value::number(static_cast<double>(a.size() - i + 1));
    return interp.run();
}

} // namespace

void registerAwk(std::map<std::string, tu::Applet> &table) {
    table["awk"] = cmdAwk;
}
