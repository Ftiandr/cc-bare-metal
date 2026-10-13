// init - process 1 (root): runs /etc/rc, then keeps login on the console. login signs the user in (by itself while
// the console user has no password, see /etc/autologin) and runs their shell.
#include <baremetal.hpp>

#include <cstdio>
#include <sys/stat.h>

namespace {

// login's exit status when a password session ended (see userutils.cpp)
constexpr int LOGIN_AGAIN = 200;

void banner() {
    auto info = cc::systemInfo();
    std::printf("\x1b[96mCC: Bare Metal\x1b[0m %s - WebAssembly computer #%d\n", info["version"].asString("?").c_str(), info["id"].asInt());
    if (FILE *motd = std::fopen("/etc/motd", "r")) {
        char line[256];
        while (std::fgets(line, sizeof(line), motd)) std::fputs(line, stdout);
        std::fclose(motd);
    }
    std::fflush(stdout);
}

} // namespace

int main() {
    banner();
    struct stat st;
    // a new computer: name it and create its first user before anyone logs in (again after an interruption)
    while (stat("/etc/firstboot", &st) == 0) {
        cc::SpawnOptions options;
        options.flags = CC_SPAWN_NEW_GROUP | CC_SPAWN_FOREGROUND;
        int pid = cc::spawn({"/sbin/setup"}, options);
        if (pid < 0) break;
        int status = 0;
        for (;;) {
            int r = cc::wait(-1, &status);
            if (r == pid || r < 0) break;
        }
        cc_tty_setfg(cc_getpgid());
        cc::tty::setMode(0);
    }
    if (stat("/etc/rc", &st) == 0) cc::run({"/bin/sh", "/etc/rc"});
    for (;;) {
        cc::SpawnOptions options;
        options.flags = CC_SPAWN_NEW_GROUP | CC_SPAWN_FOREGROUND;
        int pid = cc::spawn({"/bin/login"}, options);
        if (pid < 0) {
            std::fprintf(stderr, "init: cannot start /bin/login\n");
            cc::sleep(5);
            continue;
        }
        // Reap everything (orphans are adopted by init) until the shell itself exits.
        int status = 0;
        for (;;) {
            int r = cc::wait(-1, &status);
            if (r == pid || r < 0) break;
        }
        cc_tty_setfg(cc_getpgid());
        cc::tty::setMode(0);
        // a user logged out (password login): the login prompt again
        if (status == LOGIN_AGAIN) continue;
        if (status >= 128 && status != 130) {
            std::printf("\ninit: shell exited with status %d, restarting\n", status);
            continue;
        }
        // Like CC: leaving the shell turns the computer off.
        cc_shutdown(0);
        cc::sleep(10);
    }
}
