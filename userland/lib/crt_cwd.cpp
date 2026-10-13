// Start-up code linked into every program (see the Makefile and the c++ driver).
//
// WASI has no inherited working directory, so the kernel passes it in $PWD and we chdir there before main().
// This must not live in libbaremetal.a: the linker only takes archive members whose symbols are referenced, so
// programs that use no libbaremetal function would silently start in "/".
#include <cstdlib>
#include <unistd.h>

namespace {
struct InitCwd {
    InitCwd() {
        const char *pwd = std::getenv("PWD");
        if (pwd != nullptr && pwd[0] == '/') chdir(pwd);
    }
};

// Constructor priority 101: before other static objects, so they already see the right directory.
__attribute__((init_priority(101))) InitCwd initCwd;
} // namespace
