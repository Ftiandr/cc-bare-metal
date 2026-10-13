// Compile and run:  c++ hello.cpp -o hello && ./hello a b
#include <iostream>
#include <string>
#include <vector>

int main(int argc, char **argv) {
    std::vector<std::string> args(argv, argv + argc);
    std::cout << "Hello from C++ on WebAssembly! Привет!\n";
    for (size_t i = 0; i < args.size(); ++i) std::cout << "argv[" << i << "] = " << args[i] << '\n';
    return 0;
}
