// Calling Lua (and every CC API / Lua library) from C++:  c++ lua.cpp -o lua_demo && ./lua_demo
#include <baremetal.hpp>
#include <cstdio>

int main() {
    auto r = cc::lua("local a, b = ... return a + b, os.getComputerID(), #peripheral.getNames()", cc::Json::array({2, 40}));
    if (!r) {
        std::printf("Lua error: %s\n", r.error.c_str());
        return 1;
    }
    std::printf("2 + 40 = %d, computer #%d, %d peripherals\n", r.value[0].asInt(), r.value[1].asInt(), r.value[2].asInt());
}
