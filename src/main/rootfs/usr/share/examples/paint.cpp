// A paint program using only Linux-style files: /dev/fb0 for the screen, /dev/input/event1 for the mouse and
// /dev/input/event0 for the keyboard. Left button draws, right button erases, keys 1-9 pick a colour, Esc quits.
//
//   c++ paint.cpp -o paint && ./paint
#include <fcntl.h>
#include <fstream>
#include <linux/input.h>
#include <poll.h>
#include <unistd.h>
#include <vector>

int main() {
    int w = 0, h = 0;
    char comma;
    std::ifstream("/sys/class/graphics/fb0/virtual_size") >> w >> comma >> h;
    std::ofstream("/sys/class/graphics/fb0/bits_per_pixel") << "8\n"; // 256 colours, one byte per pixel

    int fb = open("/dev/fb0", O_RDWR);
    int mouse = open("/dev/input/event1", O_RDONLY);
    int keyboard = open("/dev/input/event0", O_RDONLY);
    if (fb < 0 || mouse < 0 || keyboard < 0) return 1;

    std::vector<unsigned char> row(static_cast<size_t>(w), 0); // colour 0: black
    for (int y = 0; y < h; y++) pwrite(fb, row.data(), row.size(), static_cast<off_t>(y) * w);

    int x = 0, y = 0, colour = 14; // red
    bool left = false, right = false;
    auto dot = [&](int cx, int cy, unsigned char c) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int px = cx + dx, py = cy + dy;
                if (px >= 0 && py >= 0 && px < w && py < h) pwrite(fb, &c, 1, static_cast<off_t>(py) * w + px);
            }
        }
    };

    pollfd fds[2] = {{mouse, POLLIN, 0}, {keyboard, POLLIN, 0}};
    input_event events[64];
    while (poll(fds, 2, -1) > 0) {
        if (fds[1].revents & POLLIN) {
            ssize_t n = read(keyboard, events, sizeof(events));
            for (ssize_t i = 0; i < n / static_cast<ssize_t>(sizeof(input_event)); i++) {
                auto &e = events[i];
                if (e.type != EV_KEY || e.value != 1) continue;
                if (e.code == KEY_ESC) return 0;
                if (e.code >= KEY_1 && e.code <= KEY_9) colour = e.code - KEY_1 + 1;
            }
        }
        if (fds[0].revents & POLLIN) {
            ssize_t n = read(mouse, events, sizeof(events));
            for (ssize_t i = 0; i < n / static_cast<ssize_t>(sizeof(input_event)); i++) {
                auto &e = events[i];
                if (e.type == EV_ABS && e.code == ABS_X) x = e.value;
                if (e.type == EV_ABS && e.code == ABS_Y) y = e.value;
                if (e.type == EV_KEY && e.code == BTN_LEFT) left = e.value;
                if (e.type == EV_KEY && e.code == BTN_RIGHT) right = e.value;
                if (e.type == EV_SYN && (left || right)) dot(x, y, left ? static_cast<unsigned char>(colour) : 0);
            }
        }
    }
}
