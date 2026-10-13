// gfxdemo - shows off the graphics modes: Mandelbrot in 256 colours, a plasma animation in 16 colours and
// dithered 1-bit art. Keys: 1/2/3 switch demo, q quits.
#include <baremetal.hpp>

#include <cmath>
#include <cstdint>
#include <vector>

namespace {

std::vector<uint8_t> pixels(512 * 512);

void mandelbrot(int w, int h) {
    cc::gfx::mode(w, h, 8);
    std::vector<uint32_t> palette(256);
    for (int i = 0; i < 256; i++) {
        double t = i / 255.0;
        int r = static_cast<int>(9 * (1 - t) * t * t * t * 255), g = static_cast<int>(15 * (1 - t) * (1 - t) * t * t * 255),
            b = static_cast<int>(8.5 * (1 - t) * (1 - t) * (1 - t) * t * 255);
        palette[static_cast<size_t>(i)] = static_cast<uint32_t>((r & 255) << 16 | (g & 255) << 8 | (b & 255));
    }
    palette[0] = 0;
    cc::gfx::palette(0, palette);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            double cr = -2.2 + 3.0 * x / w, ci = -1.5 + 3.0 * y / h, zr = 0, zi = 0;
            int it = 0;
            while (zr * zr + zi * zi < 4 && it < 255) {
                double t = zr * zr - zi * zi + cr;
                zi = 2 * zr * zi + ci;
                zr = t;
                it++;
            }
            pixels[static_cast<size_t>(y * w + x)] = static_cast<uint8_t>(it == 255 ? 0 : it);
        }
        if (y % 32 == 31) cc::gfx::blit(0, y - 31, w, 32, pixels.data() + (y - 31) * w, w);
    }
    cc::gfx::text(4, 4, "Mandelbrot - 256 colours  [1/2/3, q]", 255, 0);
}

void plasma(int frame) {
    const int w = 256, h = 256;
    if (frame == 0) cc::gfx::mode(w, h, 4);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            double v = std::sin(x / 16.0 + frame / 8.0) + std::sin((y + frame) / 12.0) + std::sin((x + y) / 20.0)
                       + std::sin(std::sqrt(static_cast<double>((x - 128) * (x - 128) + (y - 128) * (y - 128))) / 10.0 - frame / 6.0);
            pixels[static_cast<size_t>(y * w + x)] = static_cast<uint8_t>(static_cast<int>((v + 4) * 2) & 15);
        }
    }
    cc::gfx::blit(0, 0, w, h, pixels.data(), w);
    cc::gfx::text(4, 4, "Plasma - 16 colours", 15, 0);
}

void dither() {
    static const int bayer[4][4] = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    const int w = 512, h = 512;
    cc::gfx::mode(w, h, 1);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            double dx = x - 256, dy = y - 256, d = std::sqrt(dx * dx + dy * dy);
            double v = 0.5 + 0.5 * std::cos(d / 18.0) * (1 - d / 400.0);
            pixels[static_cast<size_t>(y * w + x)] = static_cast<uint8_t>(v * 16 > bayer[y & 3][x & 3]);
        }
    }
    cc::gfx::blit(0, 0, w, h, pixels.data(), w);
    cc::gfx::text(8, 8, "1 bit per pixel, 512x512, ordered dither", 1, 0, 2);
}

} // namespace

int main() {
    int demo = 1, frame = 0;
    bool drawn = false;
    cc::setProcessFlags(CC_PROC_EVENTS | CC_PROC_INPUT | CC_PROC_CATCH);
    for (;;) {
        if (auto e = cc::pullEvent({}, demo == 2 ? 0 : -1)) {
            if (e->name == "terminate") break;
            if (e->name == "char") {
                char c = e->args[0].asString("")[0];
                if (c == 'q') break;
                if (c >= '1' && c <= '3') {
                    demo = c - '0';
                    drawn = false;
                    frame = 0;
                }
            }
        }
        if (demo == 1 && !drawn) mandelbrot(512, 512);
        if (demo == 3 && !drawn) dither();
        if (demo == 2) plasma(frame++);
        drawn = true;
        cc::gfx::present();
    }
    cc::gfx::textMode();
    return 0;
}
