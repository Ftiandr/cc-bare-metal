// Graphics:  c++ circles.cpp -o circles && ./circles   (press any key to quit)
#include <baremetal.hpp>

int main() {
    cc::gfx::mode(256, 256, 4);
    cc::setProcessFlags(CC_PROC_EVENTS | CC_PROC_INPUT);
    for (int frame = 0;; ++frame) {
        if (auto e = cc::pullEvent({}, 0); e && (e->name == "key" || e->name == "char")) break;
        cc::gfx::clear(0);
        for (int i = 0; i < 12; ++i) cc::gfx::circle(128, 128, (frame * 2 + i * 10) % 128, 1 + i % 15);
        cc::gfx::text(8, 8, "Circles! Press a key", 15);
        cc::gfx::present();
    }
    cc::gfx::textMode();
}
