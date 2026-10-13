// C++ exceptions work as usual:  c++ exceptions.cpp -o exc && ./exc
#include <iostream>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>

struct Resource {
    std::string name;
    explicit Resource(std::string n) : name(std::move(n)) { std::cout << "open " << name << '\n'; }
    ~Resource() { std::cout << "close " << name << '\n'; }
};

int parse(const std::string &text) {
    auto r = std::make_unique<Resource>("parser");
    size_t used = 0;
    int value = std::stoi(text, &used); // throws std::invalid_argument
    if (used != text.size()) throw std::runtime_error("trailing characters in '" + text + "'");
    return value;
}

int main() {
    for (std::string s : {"42", "12abc", "hello"}) {
        try {
            std::cout << s << " -> " << parse(s) << '\n';
        } catch (const std::invalid_argument &) {
            std::cout << s << " -> not a number\n";
        } catch (const std::exception &e) {
            std::cout << s << " -> error: " << e.what() << '\n';
        }
    }
    std::map<std::string, int> m;
    try {
        m.at("missing");
    } catch (const std::out_of_range &e) {
        std::cout << "std::out_of_range caught\n";
    }
}
