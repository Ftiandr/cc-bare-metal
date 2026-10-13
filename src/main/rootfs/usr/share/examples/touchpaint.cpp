// Touch painting on a pixel monitor, using only Linux-style files: /dev/fbN for the picture and the monitor's
// touchscreen /dev/input/eventN for touches (found through /sys/class/input). No CC events, no special API.
//
// Right-click the monitor: the strip at the bottom picks a colour (the last cell, with the cross, clears the
// picture), anywhere else draws a dot. Every touch is also printed here. Ctrl+C quits.
//
//   c++ touchpaint.cpp -o touchpaint && ./touchpaint          (first pixel monitor)
//   ./touchpaint top                                          (the monitor named "top")
#include <cstdio>
#include <fcntl.h>
#include <fstream>
#include <linux/input.h>
#include <string>
#include <unistd.h>
#include <vector>

// /sys/class/input/EVENT/ATTR, first line ("" if missing).
static std::string attr(const std::string &event, const char *name) {
    std::string value;
    std::ifstream f("/sys/class/input/" + event + "/" + name);
    std::getline(f, value);
    return value;
}

int main(int argc, char **argv) {
    std::string wanted = argc > 1 ? argv[1] : "";
    // Touchscreens are event2, event3...; a pixel monitor's one names its framebuffer in "fb".
    std::string event, fbName;
    for (int i = 2; i < 64 && event.empty(); i++) {
        std::string e = "event" + std::to_string(i);
        std::string fb = attr(e, "fb");
        if (!fb.empty() && (wanted.empty() || attr(e, "device") == wanted)) {
            event = e;
            fbName = fb;
        }
    }
    if (event.empty()) {
        std::fprintf(stderr, "touchpaint: no pixel monitor%s%s (see: evtest -l)\n", wanted.empty() ? "" : " named ", wanted.c_str());
        return 1;
    }

    // The picture: one byte (palette index) per pixel. 16 colours are enough here: indices 0-15 are CC's colours
    // (0 black ... 15 white) in both 4- and 8-bit modes.
    std::string sys = "/sys/class/graphics/" + fbName + "/";
    int bpp = 0;
    std::ifstream(sys + "bits_per_pixel") >> bpp;
    if (bpp < 4) std::ofstream(sys + "bits_per_pixel") << "4\n";
    int w = 0, h = 0;
    char comma;
    std::ifstream(sys + "virtual_size") >> w >> comma >> h;
    int fb = open(("/dev/" + fbName).c_str(), O_RDWR);
    int touch = open(("/dev/input/" + event).c_str(), O_RDONLY);
    if (fb < 0 || touch < 0 || w <= 0 || h <= 0) {
        std::perror("touchpaint");
        return 1;
    }
    std::printf("touchpaint: %s %dx%d, touches from /dev/input/%s. Ctrl+C quits.\n", fbName.c_str(), w, h, event.c_str());
    std::fflush(stdout);

    std::vector<unsigned char> pixels(static_cast<size_t>(w) * h, 0);
    // Send rows y0..y1 of the picture to the monitor.
    auto flush = [&](int y0, int y1) {
        y0 = y0 < 0 ? 0 : y0;
        y1 = y1 >= h ? h - 1 : y1;
        if (y0 <= y1) pwrite(fb, &pixels[static_cast<size_t>(y0) * w], static_cast<size_t>(y1 - y0 + 1) * w, static_cast<off_t>(y0) * w);
    };
    auto put = [&](int x, int y, unsigned char c) {
        if (x >= 0 && y >= 0 && x < w && y < h) pixels[static_cast<size_t>(y) * w + x] = c;
    };

    // Bottom strip: 16 colour cells and a "clear" cell.
    const int cells = 17;
    const int strip = h / 8 < 4 ? 4 : h / 8;
    const int top = h - strip;
    int colour = 14; // orange-red
    auto drawStrip = [&]() {
        for (int x = 0; x < w; x++) {
            int cell = x * cells / w;
            int left = cell * w / cells, right = (cell + 1) * w / cells - 1;
            for (int y = top; y < h; y++) {
                unsigned char c;
                if (cell == 16) {
                    // clear: a cross on grey
                    int u = (x - left) * strip / (right - left + 1), v = y - top;
                    c = (u == v || u == strip - 1 - v) ? 15 : 8;
                } else {
                    c = static_cast<unsigned char>(cell);
                }
                bool border = cell == colour && (x == left || x == right || y == top || y == h - 1);
                put(x, y, border ? (colour == 15 ? 7 : 15) : c);
            }
        }
        flush(top, h - 1);
    };
    auto clear = [&]() {
        for (int y = 0; y < top; y++) for (int x = 0; x < w; x++) put(x, y, 0);
        flush(0, top - 1);
    };
    const int radius = w / 40 < 1 ? 1 : w / 40;
    auto dot = [&](int cx, int cy) {
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                int dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= radius * radius && y < top) put(x, y, static_cast<unsigned char>(colour));
            }
        }
        flush(cy - radius, cy + radius < top ? cy + radius : top - 1);
    };

    clear();
    drawStrip();

    input_event events[32];
    int x = 0, y = 0;
    ssize_t n;
    while ((n = read(touch, events, sizeof(events))) > 0) {
        for (ssize_t i = 0; i < n / static_cast<ssize_t>(sizeof(input_event)); i++) {
            const auto &e = events[i];
            if (e.type == EV_ABS && e.code == ABS_X) x = e.value;
            if (e.type == EV_ABS && e.code == ABS_Y) y = e.value;
            if (e.type != EV_KEY || e.code != BTN_TOUCH || e.value != 1) continue;
            if (y >= top) {
                int cell = x * cells / w;
                if (cell == 16) {
                    clear();
                    std::printf("touch %d %d: clear\n", x, y);
                } else {
                    colour = cell;
                    drawStrip();
                    std::printf("touch %d %d: colour %d\n", x, y, colour);
                }
            } else {
                dot(x, y);
                std::printf("touch %d %d: dot\n", x, y);
            }
            std::fflush(stdout);
        }
    }
    return 0;
}
