// Drawing the Linux way: the screen is the file /dev/fb0, its mode is in /sys/class/graphics/fb0.
// No special API - any language or tool that can write a file can draw (try: cat picture.raw > /dev/fb0).
// The default mode fills the text area with a common resolution (e.g. 512x288) at 256 colours.
//
//   c++ framebuffer.cpp -o fb && ./fb        (any key returns to the text screen)
#include <fstream>
#include <iostream>
#include <vector>

int main() {
    int w = 0, h = 0;
    char comma;
    std::ifstream("/sys/class/graphics/fb0/virtual_size") >> w >> comma >> h;
    // 256 colours: 0-15 are the CC colours, 16-231 a 6x6x6 colour cube, 232-255 greys.
    std::vector<char> pixels(static_cast<size_t>(w * h)); // one byte per pixel: offset y * width + x
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int r = x * 6 / w, g = y * 6 / h, b = ((x ^ y) >> 5) % 6;
            pixels[static_cast<size_t>(y * w + x)] = static_cast<char>(16 + r * 36 + g * 6 + b);
        }
    }
    std::ofstream fb("/dev/fb0", std::ios::binary);
    fb.write(pixels.data(), static_cast<std::streamsize>(pixels.size()));
    std::cout << "Drew " << w << "x" << h << " pixels; press any key to return." << std::endl;
}
