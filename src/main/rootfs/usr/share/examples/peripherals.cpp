// Lists peripherals (from any mod) and their methods:  c++ peripherals.cpp -o p && ./p
#include <baremetal.hpp>
#include <cstdio>

int main() {
    for (auto &info : cc::Peripheral::list()) {
        std::printf("%s (%s):", info.name.c_str(), info.types.empty() ? "?" : info.types[0].c_str());
        for (auto &m : cc::Peripheral(info.name).methods()) std::printf(" %s", m.c_str());
        std::printf("\n");
    }
    // e.g. a CC monitor: cc::Peripheral::find("monitor")->call("write", cc::Json::array({"hi"}));
}
