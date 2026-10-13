// edit - full screen text editor.
//
//   Moving:     arrows, Home/End (smart), PgUp/PgDn, Ctrl+Left/Right (words), Ctrl+Home/End, mouse click, wheel
//   Selecting:  Shift + any movement, mouse drag, double click (word), triple click (line), Ctrl+A (all)
//   Editing:    typing replaces the selection, Backspace/Delete remove it, Ctrl+Backspace deletes a word,
//               Enter keeps the indentation, Tab/Shift+Tab indent/unindent (the selected lines)
//   Clipboard:  Ctrl+C copy, Ctrl+X cut (the line without a selection), Ctrl+V paste - copies also go to the
//               player's clipboard, so Ctrl+V / Ctrl+Shift+V in the game window pastes them anywhere
//   Undo:       Ctrl+Z undo, Ctrl+Y redo
//   Other:      Ctrl+S save, Ctrl+Q quit, Ctrl+F find (F3 / Ctrl+N next), Ctrl+R replace, Ctrl+G go to line,
//               Ctrl+D duplicate line/selection, Ctrl+K delete line, F1 help
// UTF-8 aware; C/C++/Lua/sh syntax colours.
#include <baremetal.hpp>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <optional>
#include <set>
#include <string>
#include <unistd.h>
#include <vector>

namespace {

enum class Lang { Plain, Cpp, Lua, Shell };

constexpr int ctrl(char c) {
    return c - 'a' + 1;
}

bool continuation(char c) {
    return (static_cast<unsigned char>(c) & 0xC0) == 0x80;
}

const std::set<std::string> &keywords(Lang lang) {
    static const std::set<std::string> cpp = {
        "int", "char", "void", "return", "if", "else", "for", "while", "do", "struct", "typedef", "static", "const",
        "unsigned", "long", "short", "float", "double", "switch", "case", "break", "continue", "default", "sizeof",
        "enum", "union", "extern", "goto", "volatile", "bool", "true", "false", "class", "namespace", "using",
        "template", "typename", "public", "private", "protected", "virtual", "override", "final", "auto", "constexpr",
        "nullptr", "new", "delete", "this", "operator", "friend", "inline", "noexcept", "try", "catch", "throw",
        "explicit", "mutable", "static_cast", "const_cast", "reinterpret_cast", "dynamic_cast", "decltype",
        "#include", "#define", "#ifdef", "#ifndef", "#endif", "#if", "#else", "#elif", "#pragma",
    };
    static const std::set<std::string> lua = {"local", "function", "end", "if", "then", "else", "elseif", "for",
                                              "while", "do", "return", "repeat", "until", "in", "and", "or", "not",
                                              "nil", "true", "false", "break", "goto"};
    static const std::set<std::string> shell = {"if", "then", "else", "elif", "fi", "for", "while", "until", "do",
                                                "done", "in", "export", "echo", "cd", "exit", "return", "case", "esac"};
    static const std::set<std::string> none;
    switch (lang) {
        case Lang::Cpp: return cpp;
        case Lang::Lua: return lua;
        case Lang::Shell: return shell;
        default: return none;
    }
}

Lang languageOf(const std::string &file) {
    auto dot = file.rfind('.');
    std::string ext = dot == std::string::npos ? "" : file.substr(dot);
    for (auto *e : {".c", ".h", ".cpp", ".hpp", ".cc", ".cxx", ".hh", ".hxx", ".inl"}) {
        if (ext == e) return Lang::Cpp;
    }
    if (ext == ".lua") return Lang::Lua;
    if (ext == ".sh") return Lang::Shell;
    return Lang::Plain;
}

double now() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) / 1e9;
}

std::string base64(const std::string &data) {
    static const char *t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    for (size_t i = 0; i < data.size(); i += 3) {
        uint32_t v = static_cast<uint32_t>(static_cast<unsigned char>(data[i])) << 16;
        if (i + 1 < data.size()) v |= static_cast<uint32_t>(static_cast<unsigned char>(data[i + 1])) << 8;
        if (i + 2 < data.size()) v |= static_cast<unsigned char>(data[i + 2]);
        out += t[(v >> 18) & 63];
        out += t[(v >> 12) & 63];
        out += i + 1 < data.size() ? t[(v >> 6) & 63] : '=';
        out += i + 2 < data.size() ? t[v & 63] : '=';
    }
    return out;
}

// ---- Input ------------------------------------------------------------------------------------------------------

enum KeyCode {
    K_NONE = 0,
    K_UP = 1000, K_DOWN, K_LEFT, K_RIGHT, K_HOME, K_END, K_PGUP, K_PGDN, K_DELETE, K_INSERT, K_BACKTAB,
    K_F1, K_F3, K_PASTE, K_MOUSE, K_ESC,
};

constexpr int M_SHIFT = 1, M_CTRL = 4; // xterm modifier bits (alt = 2 is not used)

struct Key {
    int code = K_NONE; // a byte (< 256) or a KeyCode
    int mods = 0;
    // mouse (SGR): button 0 left 1 middle 2 right, 32+ drag, 64/65 wheel; 1-based cell
    int button = 0, mx = 0, my = 0;
    bool release = false;
};

int readByte() {
    unsigned char c;
    return read(0, &c, 1) == 1 ? c : -1;
}

Key readKey() {
    Key k;
    int c = readByte();
    if (c < 0) {
        k.code = -1;
        return k;
    }
    if (c != 27) {
        k.code = c;
        return k;
    }
    int c1 = readByte();
    if (c1 == 'O') {
        int c2 = readByte();
        k.code = c2 == 'P' ? K_F1 : c2 == 'R' ? K_F3 : K_NONE;
        return k;
    }
    if (c1 != '[') {
        k.code = K_ESC;
        return k;
    }
    // CSI: [<] params ; ... final
    std::string params;
    int f;
    while ((f = readByte()) >= 0 && ((f >= '0' && f <= '9') || f == ';' || f == '<')) params += static_cast<char>(f);
    if (!params.empty() && params[0] == '<') {
        // SGR mouse: <b;x;yM or m
        int b = 0, x = 0, y = 0;
        std::sscanf(params.c_str() + 1, "%d;%d;%d", &b, &x, &y);
        k.code = K_MOUSE;
        k.button = b;
        k.mx = x;
        k.my = y;
        k.release = f == 'm';
        return k;
    }
    std::vector<int> p;
    size_t i = 0;
    while (i <= params.size()) {
        auto semi = params.find(';', i);
        if (semi == std::string::npos) semi = params.size();
        p.push_back(semi > i ? std::atoi(params.substr(i, semi - i).c_str()) : 0);
        i = semi + 1;
    }
    int mod = p.size() >= 2 ? std::max(0, p[1] - 1) : 0;
    k.mods = mod; // xterm: 1 shift, 2 alt, 4 ctrl
    switch (f) {
        case 'A': k.code = K_UP; break;
        case 'B': k.code = K_DOWN; break;
        case 'C': k.code = K_RIGHT; break;
        case 'D': k.code = K_LEFT; break;
        case 'H': k.code = K_HOME; break;
        case 'F': k.code = K_END; break;
        case 'Z': k.code = K_BACKTAB; break;
        case '~':
            switch (p.empty() ? 0 : p[0]) {
                case 1: case 7: k.code = K_HOME; break;
                case 4: case 8: k.code = K_END; break;
                case 2: k.code = K_INSERT; break;
                case 3: k.code = K_DELETE; break;
                case 5: k.code = K_PGUP; break;
                case 6: k.code = K_PGDN; break;
                case 13: k.code = K_F3; break;
                case 200: k.code = K_PASTE; break;
                default: break;
            }
            break;
        default: break;
    }
    return k;
}

// ---- Editor -----------------------------------------------------------------------------------------------------

struct Pos {
    size_t y = 0, x = 0; // line, byte offset
    bool operator==(const Pos &o) const { return y == o.y && x == o.x; }
    bool operator!=(const Pos &o) const { return !(*this == o); }
    bool operator<(const Pos &o) const { return y < o.y || (y == o.y && x < o.x); }
};

// One change, for undo: text inserted at `at` (insert) or removed from `at` (erase).
struct Change {
    bool insert;
    Pos at;
    std::string text;
    Pos cursorBefore, cursorAfter;
    std::optional<Pos> anchorBefore;
    long group;
};

class Editor {
public:
    explicit Editor(std::string file) : file_(std::move(file)), lang_(languageOf(file_)) {}

    int run() {
        load();
        auto size = cc::tty::size();
        cols_ = size.cols;
        rows_ = size.rows;
        cc::tty::Mode raw(CC_TTY_RAW | CC_TTY_NOECHO | CC_TTY_NOSIG);
        // alternate screen, bracketed paste, mouse (clicks + drags, SGR encoding)
        std::fputs("\x1b[?1049h\x1b[?2004h\x1b[?1000h\x1b[?1002h\x1b[?1006h\x1b[2J", stdout);
        bool quitWarned = false;
        for (;;) {
            refresh();
            auto k = readKey();
            if (k.code < 0) break;
            if (k.code == ctrl('q')) {
                if (dirty_ && !quitWarned) {
                    status_ = "Unsaved changes! Ctrl+Q again to quit, Ctrl+S to save";
                    quitWarned = true;
                    continue;
                }
                break;
            }
            quitWarned = false;
            handle(k);
        }
        std::fputs("\x1b[?1006l\x1b[?1002l\x1b[?1000l\x1b[?2004l\x1b[?1049l", stdout);
        std::fflush(stdout);
        return 0;
    }

private:
    std::string file_;
    Lang lang_;
    std::vector<std::string> lines_;
    Pos cur_;                    // cursor
    std::optional<Pos> anchor_;  // selection: from anchor_ to cur_
    size_t wantCol_ = 0;         // column kept when moving up/down
    size_t rowOff_ = 0, colOff_ = 0;
    int cols_ = 80, rows_ = 24;
    bool dirty_ = false;
    std::string status_;
    std::string clipboard_;
    std::string lastSearch_;
    std::vector<Change> undo_, redo_;
    long group_ = 0;
    int lastKind_ = 0; // 1 typing, 2 deleting: consecutive ones undo together
    double lastClick_ = 0;
    Pos lastClickPos_;
    int clicks_ = 0;
    bool dragging_ = false;

    // ---- files ----

    void load() {
        FILE *f = std::fopen(file_.c_str(), "r");
        if (!f) {
            lines_.emplace_back();
            status_ = "New file: " + file_;
            return;
        }
        std::string cur;
        int c;
        while ((c = std::fgetc(f)) != EOF) {
            if (c == '\n') {
                lines_.push_back(std::move(cur));
                cur.clear();
            } else if (c != '\r') {
                cur += static_cast<char>(c);
            }
        }
        if (!cur.empty()) lines_.push_back(std::move(cur));
        std::fclose(f);
        if (lines_.empty()) lines_.emplace_back();
        status_ = file_ + ": " + std::to_string(lines_.size()) + " lines - F1 help";
    }

    void save() {
        FILE *f = std::fopen(file_.c_str(), "w");
        if (!f) {
            status_ = "Cannot save " + file_ + "!";
            return;
        }
        long bytes = 0;
        for (auto &l : lines_) {
            std::fwrite(l.data(), 1, l.size(), f);
            std::fputc('\n', f);
            bytes += static_cast<long>(l.size()) + 1;
        }
        std::fclose(f);
        dirty_ = false;
        status_ = "Saved " + file_ + " (" + std::to_string(bytes) + " bytes)";
    }

    // ---- text primitives (every change goes through these two, for undo) ----

    Pos endOf(Pos at, const std::string &text) const {
        auto nl = text.rfind('\n');
        if (nl == std::string::npos) return {at.y, at.x + text.size()};
        size_t lines = static_cast<size_t>(std::count(text.begin(), text.end(), '\n'));
        return {at.y + lines, text.size() - nl - 1};
    }

    Pos rawInsert(Pos at, const std::string &text) {
        auto &l = lines_[at.y];
        std::string tail = l.substr(at.x);
        l.erase(at.x);
        size_t start = 0, y = at.y;
        while (true) {
            auto nl = text.find('\n', start);
            if (nl == std::string::npos) {
                lines_[y] += text.substr(start);
                break;
            }
            lines_[y] += text.substr(start, nl - start);
            lines_.insert(lines_.begin() + static_cast<long>(y) + 1, std::string());
            y++;
            start = nl + 1;
        }
        Pos end{y, lines_[y].size()};
        lines_[y] += tail;
        dirty_ = true;
        return end;
    }

    std::string text(Pos a, Pos b) const {
        if (b < a) std::swap(a, b);
        if (a.y == b.y) return lines_[a.y].substr(a.x, b.x - a.x);
        std::string out = lines_[a.y].substr(a.x);
        for (size_t y = a.y + 1; y < b.y; y++) out += "\n" + lines_[y];
        out += "\n" + lines_[b.y].substr(0, b.x);
        return out;
    }

    void rawErase(Pos a, Pos b) {
        if (b < a) std::swap(a, b);
        if (a.y == b.y) {
            lines_[a.y].erase(a.x, b.x - a.x);
        } else {
            lines_[a.y] = lines_[a.y].substr(0, a.x) + lines_[b.y].substr(b.x);
            lines_.erase(lines_.begin() + static_cast<long>(a.y) + 1, lines_.begin() + static_cast<long>(b.y) + 1);
        }
        dirty_ = true;
    }

    void beginGroup(int kind = 0) {
        if (kind == 0 || kind != lastKind_) group_++;
        lastKind_ = kind;
        redo_.clear();
    }

    Pos insert(Pos at, const std::string &t) {
        if (t.empty()) return at;
        auto before = cur_;
        auto end = rawInsert(at, t);
        undo_.push_back({true, at, t, before, end, anchor_, group_});
        trimUndo();
        return end;
    }

    void erase(Pos a, Pos b) {
        if (b < a) std::swap(a, b);
        if (a == b) return;
        auto t = text(a, b);
        undo_.push_back({false, a, t, cur_, a, anchor_, group_});
        trimUndo();
        rawErase(a, b);
    }

    void trimUndo() {
        if (undo_.size() > 5000) undo_.erase(undo_.begin(), undo_.begin() + 1000);
    }

    void undo() {
        if (undo_.empty()) {
            status_ = "Nothing to undo";
            return;
        }
        long g = undo_.back().group;
        while (!undo_.empty() && undo_.back().group == g) {
            auto c = undo_.back();
            undo_.pop_back();
            if (c.insert) rawErase(c.at, endOf(c.at, c.text));
            else rawInsert(c.at, c.text);
            cur_ = c.cursorBefore;
            anchor_ = c.anchorBefore;
            redo_.push_back(c);
        }
        lastKind_ = 0;
        clampCursor();
        status_ = "Undone";
    }

    void redoChange() {
        if (redo_.empty()) {
            status_ = "Nothing to redo";
            return;
        }
        long g = redo_.back().group;
        while (!redo_.empty() && redo_.back().group == g) {
            auto c = redo_.back();
            redo_.pop_back();
            if (c.insert) cur_ = rawInsert(c.at, c.text);
            else {
                rawErase(c.at, endOf(c.at, c.text));
                cur_ = c.at;
            }
            anchor_.reset();
            undo_.push_back(c);
        }
        lastKind_ = 0;
        clampCursor();
        status_ = "Redone";
    }

    // ---- selection ----

    bool hasSelection() const { return anchor_ && *anchor_ != cur_; }

    std::pair<Pos, Pos> selection() const {
        auto a = *anchor_, b = cur_;
        if (b < a) std::swap(a, b);
        return {a, b};
    }

    // Delete the selection (if any); true if something was deleted.
    bool deleteSelection() {
        if (!hasSelection()) {
            anchor_.reset();
            return false;
        }
        auto [a, b] = selection();
        erase(a, b);
        cur_ = a;
        anchor_.reset();
        return true;
    }

    // Before a movement: Shift starts/extends the selection, otherwise it ends.
    void prepareMove(bool shift) {
        if (shift) {
            if (!anchor_) anchor_ = cur_;
        } else {
            anchor_.reset();
        }
        lastKind_ = 0;
    }

    void clampCursor() {
        if (lines_.empty()) lines_.emplace_back();
        cur_.y = std::min(cur_.y, lines_.size() - 1);
        cur_.x = std::min(cur_.x, lines_[cur_.y].size());
        while (cur_.x > 0 && continuation(lines_[cur_.y][cur_.x])) cur_.x--;
        if (anchor_) {
            anchor_->y = std::min(anchor_->y, lines_.size() - 1);
            anchor_->x = std::min(anchor_->x, lines_[anchor_->y].size());
        }
    }

    // ---- columns ----

    static size_t renderCol(const std::string &l, size_t x) {
        size_t c = 0;
        for (size_t i = 0; i < x && i < l.size(); i++) {
            if (l[i] == '\t') c = (c / 4 + 1) * 4;
            else if (!continuation(l[i])) c++;
        }
        return c;
    }

    static size_t byteAtCol(const std::string &l, size_t col) {
        size_t c = 0, i = 0;
        while (i < l.size()) {
            size_t next = l[i] == '\t' ? (c / 4 + 1) * 4 : c + 1;
            if (next > col) break;
            c = next;
            i++;
            while (i < l.size() && continuation(l[i])) i++;
        }
        return i;
    }

    size_t gutter() const { return std::to_string(lines_.size()).size() + 1; }

    size_t textRows() const { return static_cast<size_t>(std::max(1, rows_ - 1)); }

    // ---- moving ----

    void left() {
        if (cur_.x > 0) {
            cur_.x--;
            while (cur_.x > 0 && continuation(lines_[cur_.y][cur_.x])) cur_.x--;
        } else if (cur_.y > 0) {
            cur_.y--;
            cur_.x = lines_[cur_.y].size();
        }
    }

    void right() {
        auto &l = lines_[cur_.y];
        if (cur_.x < l.size()) {
            cur_.x++;
            while (cur_.x < l.size() && continuation(l[cur_.x])) cur_.x++;
        } else if (cur_.y + 1 < lines_.size()) {
            cur_.y++;
            cur_.x = 0;
        }
    }

    static bool wordChar(unsigned char c) { return std::isalnum(c) || c == '_' || c >= 0x80; }

    void wordLeft() {
        if (cur_.x == 0) {
            left();
            return;
        }
        auto &l = lines_[cur_.y];
        size_t x = cur_.x;
        while (x > 0 && !wordChar(static_cast<unsigned char>(l[x - 1]))) x--;
        while (x > 0 && wordChar(static_cast<unsigned char>(l[x - 1]))) x--;
        cur_.x = x;
    }

    void wordRight() {
        auto &l = lines_[cur_.y];
        if (cur_.x >= l.size()) {
            right();
            return;
        }
        while (cur_.x < l.size() && wordChar(static_cast<unsigned char>(l[cur_.x]))) cur_.x++;
        while (cur_.x < l.size() && !wordChar(static_cast<unsigned char>(l[cur_.x]))) cur_.x++;
    }

    void vertical(long delta) {
        long y = static_cast<long>(cur_.y) + delta;
        y = std::max(0L, std::min(static_cast<long>(lines_.size()) - 1, y));
        cur_.y = static_cast<size_t>(y);
        cur_.x = byteAtCol(lines_[cur_.y], wantCol_);
    }

    void home() {
        auto &l = lines_[cur_.y];
        size_t indent = 0;
        while (indent < l.size() && (l[indent] == ' ' || l[indent] == '\t')) indent++;
        cur_.x = cur_.x == indent ? 0 : indent; // smart home
    }

    // ---- mouse ----

    Pos posAt(int mx, int my) const {
        size_t row = static_cast<size_t>(std::max(1, my) - 1);
        size_t y = std::min(lines_.size() - 1, rowOff_ + row);
        long col = static_cast<long>(mx) - 1 - static_cast<long>(gutter()) + static_cast<long>(colOff_);
        return {y, byteAtCol(lines_[y], static_cast<size_t>(std::max(0L, col)))};
    }

    void selectWordAt(Pos p) {
        auto &l = lines_[p.y];
        size_t a = p.x, b = p.x;
        while (a > 0 && wordChar(static_cast<unsigned char>(l[a - 1]))) a--;
        while (b < l.size() && wordChar(static_cast<unsigned char>(l[b]))) b++;
        anchor_ = Pos{p.y, a};
        cur_ = Pos{p.y, b};
    }

    void mouse(const Key &k) {
        int b = k.button;
        if (b == 64 || b == 65) { // wheel
            long delta = b == 64 ? -3 : 3;
            long off = static_cast<long>(rowOff_) + delta;
            long maxOff = std::max(0L, static_cast<long>(lines_.size()) - static_cast<long>(textRows()));
            rowOff_ = static_cast<size_t>(std::max(0L, std::min(maxOff, off)));
            // keep the cursor on screen
            if (cur_.y < rowOff_ || cur_.y >= rowOff_ + textRows()) {
                bool keepSelection = hasSelection();
                if (!keepSelection) anchor_.reset();
                cur_.y = std::min(lines_.size() - 1, cur_.y < rowOff_ ? rowOff_ : rowOff_ + textRows() - 1);
                cur_.x = byteAtCol(lines_[cur_.y], wantCol_);
            }
            scrollLock_ = true;
            return;
        }
        if (k.my > rows_ - 1 && !dragging_) return; // status bar
        auto p = posAt(k.mx, k.my);
        if ((b & 3) != 0 && !(b & 32)) return; // only the left button
        if (k.release) {
            dragging_ = false;
            if (anchor_ && *anchor_ == cur_) anchor_.reset();
            return;
        }
        if (b & 32) { // drag
            if (!dragging_) return;
            // dragging over the first or last row scrolls
            if (k.my <= 1 && rowOff_ > 0) rowOff_--;
            if (k.my >= rows_ - 1 && rowOff_ + textRows() < lines_.size()) rowOff_++;
            scrollLock_ = true;
            cur_ = p;
            return;
        }
        // press
        double t = now();
        clicks_ = (t - lastClick_ < 0.4 && p.y == lastClickPos_.y) ? clicks_ + 1 : 1;
        lastClick_ = t;
        lastClickPos_ = p;
        lastKind_ = 0;
        if (clicks_ == 2) {
            selectWordAt(p);
        } else if (clicks_ >= 3) {
            anchor_ = Pos{p.y, 0};
            cur_ = p.y + 1 < lines_.size() ? Pos{p.y + 1, 0} : Pos{p.y, lines_[p.y].size()};
            clicks_ = 3;
        } else {
            anchor_ = p;
            cur_ = p;
            dragging_ = true;
        }
        wantCol_ = renderCol(lines_[cur_.y], cur_.x);
    }

    bool scrollLock_ = false; // the view was scrolled with the wheel: do not jump back to the cursor

    // ---- clipboard ----

    void copyText(const std::string &t) {
        clipboard_ = t;
        // OSC 52: put it on the player's clipboard too
        std::printf("\x1b]52;c;%s\x07", base64(t).c_str());
    }

    void copy(bool cut) {
        std::string t;
        if (hasSelection()) {
            auto [a, b] = selection();
            t = text(a, b);
            if (cut) {
                beginGroup();
                deleteSelection();
            }
        } else {
            // no selection: the whole line
            t = lines_[cur_.y] + "\n";
            if (cut) {
                beginGroup();
                if (lines_.size() > 1) {
                    Pos a{cur_.y, 0};
                    Pos b = cur_.y + 1 < lines_.size() ? Pos{cur_.y + 1, 0} : Pos{cur_.y, lines_[cur_.y].size()};
                    if (cur_.y + 1 >= lines_.size()) a = Pos{cur_.y - 1, lines_[cur_.y - 1].size()};
                    erase(a, b);
                    cur_ = Pos{std::min(cur_.y, lines_.size() - 1), 0};
                } else {
                    erase({0, 0}, {0, lines_[0].size()});
                    cur_ = {0, 0};
                }
            }
        }
        copyText(t);
        auto chars = std::count_if(t.begin(), t.end(), [](char c) { return !continuation(c); });
        status_ = std::string(cut ? "Cut " : "Copied ") + std::to_string(chars) + " characters";
    }

    void pasteText(const std::string &raw) {
        std::string t;
        for (char c : raw) {
            if (c != '\r') t += c;
        }
        beginGroup();
        deleteSelection();
        cur_ = insert(cur_, t);
        wantCol_ = renderCol(lines_[cur_.y], cur_.x);
    }

    std::string readPaste() {
        static const std::string END = "\x1b[201~";
        std::string t;
        int c;
        while ((c = readByte()) >= 0) {
            t += static_cast<char>(c);
            if (t.size() >= END.size() && t.compare(t.size() - END.size(), END.size(), END) == 0) {
                t.resize(t.size() - END.size());
                break;
            }
        }
        return t;
    }

    // ---- editing commands ----

    void typeText(const std::string &t) {
        if (hasSelection()) lastKind_ = 0; // replacing a selection starts a new undo step (with the typing after it)
        beginGroup(1);
        deleteSelection();
        cur_ = insert(cur_, t);
        wantCol_ = renderCol(lines_[cur_.y], cur_.x);
    }

    void newline() {
        beginGroup();
        deleteSelection();
        auto &l = lines_[cur_.y];
        size_t indent = 0;
        while (indent < cur_.x && (l[indent] == ' ' || l[indent] == '\t')) indent++;
        cur_ = insert(cur_, "\n" + l.substr(0, indent));
        wantCol_ = renderCol(lines_[cur_.y], cur_.x);
    }

    void backspace(bool word) {
        beginGroup(2);
        if (deleteSelection()) return;
        Pos end = cur_;
        if (word) wordLeft();
        else left();
        Pos start = cur_;
        cur_ = end;
        erase(start, end);
        cur_ = start;
        wantCol_ = renderCol(lines_[cur_.y], cur_.x);
    }

    void deleteForward() {
        beginGroup(2);
        if (deleteSelection()) return;
        Pos a = cur_;
        right();
        Pos b = cur_;
        cur_ = a;
        erase(a, b);
    }

    // Lines covered by the selection (or the cursor line).
    std::pair<size_t, size_t> selectedLines() const {
        if (!hasSelection()) return {cur_.y, cur_.y};
        auto [a, b] = selection();
        size_t last = b.x == 0 && b.y > a.y ? b.y - 1 : b.y;
        return {a.y, last};
    }

    std::string indentUnit(size_t y) const {
        return !lines_[y].empty() && lines_[y][0] == '\t' ? "\t" : "    ";
    }

    void indent(bool out) {
        beginGroup();
        auto [first, last] = selectedLines();
        bool selected = hasSelection();
        for (size_t y = first; y <= last; y++) {
            if (out) {
                auto &l = lines_[y];
                size_t n = 0;
                if (!l.empty() && l[0] == '\t') n = 1;
                else while (n < 4 && n < l.size() && l[n] == ' ') n++;
                if (n == 0) continue;
                erase({y, 0}, {y, n});
                if (cur_.y == y) cur_.x = cur_.x >= n ? cur_.x - n : 0;
                if (anchor_ && anchor_->y == y) anchor_->x = anchor_->x >= n ? anchor_->x - n : 0;
            } else {
                auto unit = indentUnit(y);
                if (!selected && first == last) {
                    // no selection: Tab inserts at the cursor
                    cur_ = insert(cur_, "\t" == unit ? "\t" : std::string(4 - renderCol(lines_[y], cur_.x) % 4, ' '));
                    break;
                }
                insert({y, 0}, unit);
                if (cur_.y == y && cur_.x > 0) cur_.x += unit.size();
                if (anchor_ && anchor_->y == y && anchor_->x > 0) anchor_->x += unit.size();
            }
        }
        if (selected && anchor_) {
            // keep whole lines selected
            anchor_ = Pos{first, 0};
            cur_ = last + 1 < lines_.size() ? Pos{last + 1, 0} : Pos{last, lines_[last].size()};
        }
        wantCol_ = renderCol(lines_[cur_.y], cur_.x);
    }

    void duplicate() {
        beginGroup();
        if (hasSelection()) {
            auto [a, b] = selection();
            auto t = text(a, b);
            insert(b, t);
            anchor_ = b;
            cur_ = endOf(b, t);
        } else {
            insert({cur_.y, lines_[cur_.y].size()}, "\n" + lines_[cur_.y]);
            cur_.y++;
        }
    }

    void deleteLine() {
        beginGroup();
        anchor_.reset();
        if (lines_.size() == 1) {
            erase({0, 0}, {0, lines_[0].size()});
            cur_ = {0, 0};
            return;
        }
        if (cur_.y + 1 < lines_.size()) erase({cur_.y, 0}, {cur_.y + 1, 0});
        else erase({cur_.y - 1, lines_[cur_.y - 1].size()}, {cur_.y, lines_[cur_.y].size()});
        cur_.y = std::min(cur_.y, lines_.size() - 1);
        cur_.x = byteAtCol(lines_[cur_.y], wantCol_);
    }

    // ---- find / replace / goto ----

    std::optional<std::string> prompt(const std::string &message, const std::string &initial = {}) {
        std::string answer = initial;
        for (;;) {
            status_ = message + answer;
            refresh(true);
            auto k = readKey();
            if (k.code < 0 || k.code == K_ESC || k.code == ctrl('c') || k.code == ctrl('q')) {
                status_.clear();
                return std::nullopt;
            }
            if (k.code == '\r' || k.code == '\n') {
                status_.clear();
                return answer;
            }
            if (k.code == K_PASTE) {
                for (char c : readPaste()) {
                    if (c != '\n' && c != '\r') answer += c;
                }
            } else if (k.code == 127 || k.code == 8) {
                while (!answer.empty() && continuation(answer.back())) answer.pop_back();
                if (!answer.empty()) answer.pop_back();
            } else if (k.code >= 32 && k.code < 256 && answer.size() < 200) {
                answer += static_cast<char>(k.code);
            }
        }
    }

    bool findNext(const std::string &what, bool fromStart = false) {
        if (what.empty()) return false;
        Pos start = fromStart ? Pos{0, 0} : (hasSelection() ? selection().second : cur_);
        for (size_t n = 0; n <= lines_.size(); n++) {
            size_t y = (start.y + n) % lines_.size();
            size_t from = n == 0 ? start.x : 0;
            if (n == lines_.size() && from == 0) break;
            auto at = lines_[y].find(what, from);
            if (at != std::string::npos) {
                anchor_ = Pos{y, at};
                cur_ = Pos{y, at + what.size()};
                wantCol_ = renderCol(lines_[y], cur_.x);
                scrollLock_ = false;
                status_ = n > 0 && y < start.y ? "Found (wrapped)" : "Found";
                return true;
            }
        }
        status_ = "Not found: " + what;
        return false;
    }

    void find() {
        auto initial = hasSelection() && selection().first.y == selection().second.y ? text(selection().first, selection().second) : lastSearch_;
        auto what = prompt("Find: ", initial);
        if (!what || what->empty()) return;
        lastSearch_ = *what;
        findNext(lastSearch_);
    }

    void replace() {
        auto what = prompt("Replace: ", lastSearch_);
        if (!what || what->empty()) return;
        auto with = prompt("Replace \"" + *what + "\" with: ");
        if (!with) return;
        lastSearch_ = *what;
        beginGroup();
        size_t count = 0;
        for (size_t y = 0; y < lines_.size(); y++) {
            size_t from = 0;
            while (true) {
                auto at = lines_[y].find(*what, from);
                if (at == std::string::npos) break;
                erase({y, at}, {y, at + what->size()});
                insert({y, at}, *with);
                from = at + with->size();
                count++;
            }
        }
        anchor_.reset();
        clampCursor();
        status_ = "Replaced " + std::to_string(count) + " times";
    }

    void gotoLine() {
        auto answer = prompt("Go to line: ");
        if (!answer) return;
        long n = std::atol(answer->c_str());
        if (n >= 1 && static_cast<size_t>(n) <= lines_.size()) {
            anchor_.reset();
            cur_ = {static_cast<size_t>(n) - 1, 0};
            scrollLock_ = false;
        }
    }

    void help() {
        static const char *const text[] = {
            "edit - keys",
            "",
            "  Arrows PgUp PgDn Home End   move (Home: start of text, then of line)",
            "  Ctrl+Left/Right             by words; Ctrl+Home/End: start/end of the file",
            "  Shift + any of these        select; mouse drag selects too",
            "  Double / triple click       select a word / a line; Ctrl+A: everything",
            "  Mouse wheel                 scroll",
            "",
            "  Typing                      replaces the selection",
            "  Backspace / Delete          delete the selection or a character",
            "  Ctrl+Backspace              delete the word before the cursor",
            "  Tab / Shift+Tab             indent / unindent (the selected lines)",
            "  Enter                       new line with the same indentation",
            "",
            "  Ctrl+C / Ctrl+X / Ctrl+V    copy / cut / paste (without a selection: the line)",
            "                              copies also go to your clipboard (Ctrl+Shift+V anywhere)",
            "  Ctrl+Z / Ctrl+Y             undo / redo",
            "  Ctrl+D                      duplicate the line or the selection",
            "  Ctrl+K                      delete the line",
            "",
            "  Ctrl+F, F3 or Ctrl+N        find, find next",
            "  Ctrl+R                      replace all",
            "  Ctrl+G                      go to line",
            "  Ctrl+S / Ctrl+Q             save / quit",
            "",
            "Press any key to return.",
        };
        std::string out = "\x1b[H\x1b[2J";
        for (auto *line : text) out += std::string(line) + "\x1b[K\r\n";
        std::fwrite(out.data(), 1, out.size(), stdout);
        std::fflush(stdout);
        readKey();
    }

    // ---- input dispatch ----

    void handle(const Key &k) {
        bool shift = k.mods & M_SHIFT;
        bool ctrlMod = k.mods & M_CTRL;
        bool keepScroll = false;
        switch (k.code) {
            case K_UP: prepareMove(shift); vertical(-1); break;
            case K_DOWN: prepareMove(shift); vertical(1); break;
            case K_LEFT:
                if (!shift && hasSelection()) {
                    cur_ = selection().first;
                    anchor_.reset();
                } else {
                    prepareMove(shift);
                    ctrlMod ? wordLeft() : left();
                }
                wantCol_ = renderCol(lines_[cur_.y], cur_.x);
                break;
            case K_RIGHT:
                if (!shift && hasSelection()) {
                    cur_ = selection().second;
                    anchor_.reset();
                } else {
                    prepareMove(shift);
                    ctrlMod ? wordRight() : right();
                }
                wantCol_ = renderCol(lines_[cur_.y], cur_.x);
                break;
            case K_HOME:
                prepareMove(shift);
                if (ctrlMod) cur_ = {0, 0};
                else home();
                wantCol_ = renderCol(lines_[cur_.y], cur_.x);
                break;
            case K_END:
                prepareMove(shift);
                if (ctrlMod) cur_.y = lines_.size() - 1;
                cur_.x = lines_[cur_.y].size();
                wantCol_ = renderCol(lines_[cur_.y], cur_.x);
                break;
            case K_PGUP: prepareMove(shift); vertical(-static_cast<long>(textRows())); break;
            case K_PGDN: prepareMove(shift); vertical(static_cast<long>(textRows())); break;
            case K_MOUSE:
                mouse(k);
                keepScroll = scrollLock_;
                break;
            case K_PASTE: pasteText(readPaste()); break;
            case K_DELETE: deleteForward(); break;
            case K_BACKTAB: indent(true); break;
            case K_F1: help(); break;
            case K_F3: findNext(lastSearch_); break;
            case K_ESC: anchor_.reset(); break;
            case '\r':
            case '\n': newline(); break;
            case '\t': indent(false); break;
            case 127:
            case 8: backspace(false); break;
            case 23: backspace(true); break; // Ctrl+W / Ctrl+Backspace
            case ctrl('s'): save(); break;
            case ctrl('z'): undo(); break;
            case ctrl('y'): redoChange(); break;
            case ctrl('c'): copy(false); break;
            case ctrl('x'): copy(true); break;
            case ctrl('v'): if (!clipboard_.empty()) pasteText(clipboard_); break;
            case ctrl('a'):
                anchor_ = Pos{0, 0};
                cur_ = {lines_.size() - 1, lines_.back().size()};
                break;
            case ctrl('f'): find(); break;
            case ctrl('n'): findNext(lastSearch_); break;
            case ctrl('r'): replace(); break;
            case ctrl('g'): gotoLine(); break;
            case ctrl('d'): duplicate(); break;
            case ctrl('k'): deleteLine(); break;
            default:
                if (k.code >= 32 && k.code < 256) {
                    // a character, possibly the first byte of a UTF-8 sequence
                    std::string t(1, static_cast<char>(k.code));
                    int more = k.code >= 0xF0 ? 3 : k.code >= 0xE0 ? 2 : k.code >= 0xC0 ? 1 : 0;
                    for (int i = 0; i < more; i++) {
                        int c = readByte();
                        if (c < 0) break;
                        t += static_cast<char>(c);
                    }
                    typeText(t);
                }
                break;
        }
        if (!keepScroll) scrollLock_ = false;
        clampCursor();
    }

    // ---- drawing ----

    // Draw one line (from colOff_, `width` columns) with syntax colours and the selection highlighted.
    void drawLine(std::string &out, size_t y, size_t width) const {
        const auto &l = lines_[y];
        std::optional<std::pair<Pos, Pos>> sel;
        if (hasSelection()) sel = selection();
        auto selected = [&](size_t x) {
            if (!sel) return false;
            Pos p{y, x};
            return !(p < sel->first) && p < sel->second;
        };
        bool lineBreakSelected = sel && !(Pos{y, l.size()} < sel->first) && Pos{y, l.size()} < sel->second;

        size_t i = 0, col = 0;
        int colour = -1;
        bool inverse = false, inString = false;
        char quote = 0;
        auto setStyle = [&](int want, bool inv) {
            if (want == colour && inv == inverse) return;
            out += "\x1b[0";
            if (want > 0) out += ";" + std::to_string(want);
            if (inv) out += ";7";
            out += "m";
            colour = want;
            inverse = inv;
        };
        while (i < l.size() && col < colOff_ + width) {
            unsigned char ch = static_cast<unsigned char>(l[i]);
            int want = 0;
            size_t n = 1;
            if (lang_ != Lang::Plain) {
                bool comment = (lang_ == Lang::Cpp && ch == '/' && i + 1 < l.size() && l[i + 1] == '/')
                               || (lang_ == Lang::Lua && ch == '-' && i + 1 < l.size() && l[i + 1] == '-')
                               || (lang_ == Lang::Shell && ch == '#');
                if (inString) {
                    want = 93;
                    if (ch == '\\' && i + 1 < l.size()) n = 2;
                    else if (static_cast<char>(ch) == quote) inString = false;
                } else if (ch == '"' || ch == '\'') {
                    inString = true;
                    quote = static_cast<char>(ch);
                    want = 93;
                } else if (comment) {
                    n = l.size() - i;
                    want = 90;
                } else if (std::isdigit(ch)) {
                    want = 95;
                } else if (std::isalpha(ch) || ch == '_' || ch == '#') {
                    size_t j = i + 1;
                    while (j < l.size() && (std::isalnum(static_cast<unsigned char>(l[j])) || l[j] == '_')) j++;
                    n = j - i;
                    want = keywords(lang_).count(l.substr(i, n)) ? 96 : 0;
                }
            }
            for (size_t k = 0; k < n && i < l.size(); k++) {
                char c = l[i];
                bool inv = selected(i);
                if (c == '\t') {
                    size_t next = (col / 4 + 1) * 4;
                    for (; col < next; col++) {
                        if (col >= colOff_ && col < colOff_ + width) {
                            setStyle(want, inv);
                            out += ' ';
                        }
                    }
                    i++;
                    continue;
                }
                size_t len = 1;
                while (i + len < l.size() && continuation(l[i + len])) len++;
                if (col >= colOff_ && col < colOff_ + width) {
                    setStyle(want, inv);
                    out.append(l, i, len);
                }
                col++;
                i += len;
                if (len > 1) k += len - 1;
            }
        }
        // the selected line break shows as one highlighted cell
        if (lineBreakSelected && col >= colOff_ && col < colOff_ + width) {
            setStyle(0, true);
            out += ' ';
        }
        out += "\x1b[0m";
    }

    void refresh(bool keepView = false) {
        auto size = cc::tty::size();
        bool resized = size.cols != cols_ || size.rows != rows_;
        cols_ = size.cols;
        rows_ = size.rows;
        size_t g = gutter();
        size_t width = cols_ > static_cast<int>(g) ? static_cast<size_t>(cols_) - g : 1;
        size_t rx = renderCol(lines_[cur_.y], cur_.x);
        if (!keepView && !scrollLock_) {
            if (cur_.y < rowOff_) rowOff_ = cur_.y;
            if (cur_.y >= rowOff_ + textRows()) rowOff_ = cur_.y - textRows() + 1;
            if (rx < colOff_) colOff_ = rx;
            if (rx >= colOff_ + width) colOff_ = rx - width + 1;
        }
        std::string out = resized ? "\x1b[2J" : "";
        out += "\x1b[?25l\x1b[H";
        char num[32];
        for (size_t row = 0; row < textRows(); row++) {
            size_t y = row + rowOff_;
            if (y < lines_.size()) {
                std::snprintf(num, sizeof(num), "%*zu ", static_cast<int>(g - 1), y + 1);
                out += y == cur_.y ? "\x1b[33m" : "\x1b[90m";
                out += num;
                out += "\x1b[0m";
                drawLine(out, y, width);
            } else {
                out += "\x1b[94m~\x1b[0m";
            }
            out += "\x1b[K\r\n";
        }
        std::string info = " " + file_ + (dirty_ ? " [+]" : "") + " | " + std::to_string(cur_.y + 1) + ":" + std::to_string(rx + 1);
        if (hasSelection()) {
            auto [a, b] = selection();
            auto t = text(a, b);
            info += " | sel " + std::to_string(std::count_if(t.begin(), t.end(), [](char c) { return !continuation(c); }));
        }
        info += " | ^S save ^Q quit ^Z undo ^F find F1 help | " + status_;
        // cut to the screen width (in characters)
        std::string bar;
        int shown = 0;
        for (size_t i = 0; i < info.size() && shown < cols_; i++) {
            if (!continuation(info[i])) shown++;
            if (shown > cols_) break;
            bar += info[i];
        }
        out += "\x1b[7m" + bar + std::string(static_cast<size_t>(std::max(0, cols_ - shown)), ' ') + "\x1b[0m";
        bool cursorVisible = cur_.y >= rowOff_ && cur_.y < rowOff_ + textRows() && rx >= colOff_ && rx < colOff_ + width;
        if (cursorVisible) {
            std::snprintf(num, sizeof(num), "\x1b[%zu;%zuH\x1b[?25h", cur_.y - rowOff_ + 1, rx - colOff_ + g + 1);
            out += num;
        }
        std::fwrite(out.data(), 1, out.size(), stdout);
        std::fflush(stdout);
    }
};

} // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: edit FILE\n");
        return 1;
    }
    return Editor(argv[1]).run();
}
