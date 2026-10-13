// cc - the C/C++ compiler driver: cc, c++, gcc, g++, clang, clang++, c99.
//
// Runs clang/lld (the LLVM toolchain compiled to WebAssembly, mounted at /usr/lib/llvm) with the settings of this
// system: target wasm32-wasip1, the libc/libc++ sysroot at /usr/lib/wasi, C++ exceptions, <baremetal.h>/<baremetal.hpp>
// and libbaremetal. Like gcc, it compiles and links in one go:
//
//     c++ -O2 game.cpp util.cpp -o game        g++ -c lib.cpp && ar rcs libx.a lib.o
//
// WASI programs cannot start other programs, so clang cannot run its own steps; the driver asks clang for them
// ("-###") and runs each one as a separate process. Paths are made absolute first: clang does not know our working
// directory.
#include <baremetal.hpp>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <optional>
#include <set>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

using Args = std::vector<std::string>;

const std::string LLVM = "/usr/lib/llvm/llvm.wasm";
const std::string RESOURCE_DIR = "/usr/lib/llvm";
const std::string SYSROOT = "/usr/lib/wasi";

std::string prog = "cc";
bool verbose = false;

bool startsWith(const std::string &s, std::string_view prefix) {
    return s.rfind(prefix, 0) == 0;
}

std::string absolute(const std::string &path) {
    if (path.empty() || path[0] == '/' || path == "-") return path;
    auto dir = cc::cwd();
    return dir == "/" ? "/" + path : dir + "/" + path;
}

// Options followed by a path, separately or joined ("-I dir" / "-Idir").
const std::set<std::string> &pathOptions() {
    static const std::set<std::string> options = {"-o", "-I", "-L", "-isystem", "-iquote", "-idirafter", "-include",
                                                  "-imacros", "-MF", "--sysroot", "-isysroot", "-T"};
    return options;
}

// Options followed by a value that is not a path.
const std::set<std::string> &valueOptions() {
    static const std::set<std::string> options = {"-x", "-MT", "-MQ", "-D", "-U", "-Xlinker", "-Xclang", "-mllvm",
                                                  "-target", "-arch", "-std", "-l"};
    return options;
}

// Run the LLVM multi-call binary as `tool`; returns the exit status.
int runTool(const std::string &tool, Args args) {
    args[0] = tool;
    if (verbose) {
        std::string line;
        for (auto &a : args) line += (line.empty() ? "" : " ") + a;
        std::fprintf(stderr, " %s\n", line.c_str());
    }
    cc::SpawnOptions options;
    options.program = LLVM;
    std::fflush(stdout);
    int r = cc::run(args, options);
    if (r < 0) {
        std::fprintf(stderr, "%s: cannot run %s: %s (is the toolchain installed? see 'man cc')\n", prog.c_str(), tool.c_str(),
                     std::strerror(-r));
        return 127;
    }
    return r;
}

// Lines of clang's own banner ("clang version", "Target:" ...), not worth showing on errors.
bool bannerLine(const std::string &line) {
    for (auto *p : {"clang version", "Target:", "Thread model:", "InstalledDir:", "Build config:", " \""}) {
        if (startsWith(line, p)) return true;
    }
    return false;
}

// Run a command and capture its stderr (for clang -###).
std::optional<std::string> capture(const std::string &tool, Args args) {
    int32_t fds[2];
    if (cc_pipe(fds) != 0) return std::nullopt;
    args[0] = tool;
    cc::SpawnOptions options;
    options.program = LLVM;
    options.err = fds[1];
    int pid = cc::spawn(args, options);
    close(fds[1]);
    if (pid < 0) {
        close(fds[0]);
        return std::nullopt;
    }
    std::string out;
    char buf[4096];
    ssize_t n;
    while ((n = read(fds[0], buf, sizeof(buf))) > 0) out.append(buf, static_cast<size_t>(n));
    close(fds[0]);
    int status = 0;
    cc::wait(pid, &status);
    if (status != 0) {
        size_t start = 0;
        while (start < out.size()) {
            auto end = out.find('\n', start);
            if (end == std::string::npos) end = out.size();
            auto line = out.substr(start, end - start);
            if (!bannerLine(line)) std::fprintf(stderr, "%s\n", line.c_str());
            start = end + 1;
        }
        return std::nullopt;
    }
    return out;
}

// One command of clang's "-###" output: "path" "arg" "arg"...
std::optional<Args> parseCommand(const std::string &line) {
    Args out;
    size_t i = 0;
    while (i < line.size()) {
        while (i < line.size() && line[i] == ' ') i++;
        if (i >= line.size()) break;
        if (line[i] != '"') return std::nullopt;
        std::string arg;
        for (i++; i < line.size() && line[i] != '"'; i++) {
            if (line[i] == '\\' && i + 1 < line.size()) i++;
            arg += line[i];
        }
        i++;
        out.push_back(std::move(arg));
    }
    if (out.empty()) return std::nullopt;
    return out;
}

// Turn a step from "-###" into the arguments for runTool. The compiler step is printed as
// "" "clang++" "-cc1" ... (it normally runs in-process); it becomes "clang -cc1 ...".
std::pair<std::string, Args> toolCommand(const Args &command) {
    auto cc1 = std::find(command.begin(), command.end(), "-cc1");
    if (cc1 != command.end()) {
        Args args = {"clang"};
        args.insert(args.end(), cc1, command.end());
        return {"clang", args};
    }
    auto slash = command[0].rfind('/');
    auto name = slash == std::string::npos ? command[0] : command[0].substr(slash + 1);
    if (name == "lld" || name == "ld" || name == "wasm-ld") name = "wasm-ld";
    return {name, command};
}

// Binutils (ar, nm, objdump, strip, ...): the same LLVM program; only the paths need to be made absolute.
const std::set<std::string> &binutils() {
    static const std::set<std::string> tools = {"ar", "ranlib", "nm", "objdump", "objcopy", "strip", "size",
                                                "c++filt", "addr2line", "ld", "wasm-ld"};
    return tools;
}

int binutilMain(const Args &user) {
    std::string tool = prog == "ld" ? "wasm-ld" : prog;
    Args args = {tool};
    for (size_t i = 0; i < user.size(); i++) {
        const auto &a = user[i];
        bool operation = tool == "ar" && i == 0 && !a.empty() && a[0] != '-'; // "ar rcs lib.a x.o"
        if ((a == "-o" || a == "-L" || a == "-T" || a == "-e") && i + 1 < user.size()) {
            args.push_back(a);
            args.push_back(absolute(user[++i]));
        } else if (startsWith(a, "-L") && a.size() > 2) {
            args.push_back("-L" + absolute(a.substr(2)));
        } else if (!a.empty() && a[0] != '-' && !operation && tool != "c++filt") {
            args.push_back(absolute(a));
        } else {
            args.push_back(a);
        }
    }
    return runTool(tool, args);
}

void usage() {
    std::printf("usage: %s [options] file...     (C and C++ compiler, clang/LLVM for WebAssembly)\n", prog.c_str());
    std::printf("  -o FILE   output          -c   compile only          -O0..-O3/-Os  optimise\n");
    std::printf("  -I DIR    include path    -L DIR / -lNAME  libraries -g   debug info\n");
    std::printf("  -std=c++20  -Wall  -fno-exceptions  -v (show steps)  --version\n");
    std::printf("Programs can use <baremetal.hpp> (C++ API) and <baremetal.h> (C syscalls); see 'man cc'.\n");
}

} // namespace

int main(int argc, char **argv) {
    Args user(argv + 1, argv + argc);
    auto slash = std::string(argv[0]).rfind('/');
    prog = slash == std::string::npos ? argv[0] : std::string(argv[0]).substr(slash + 1);
    if (binutils().count(prog)) return binutilMain(user);
    bool cxx = prog == "c++" || prog == "g++" || prog == "clang++";
    if (user.empty()) {
        usage();
        return 1;
    }

    // Exceptions are on for every driver: "cc game.cpp" compiles C++ by the file name, and the flags do not affect C.
    bool link = true, exceptions = true, dryRun = false, hasInput = false, infoOnly = false;
    Args args;
    for (size_t i = 0; i < user.size(); i++) {
        auto a = user[i];
        if (a == "-c" || a == "-S" || a == "-E" || a == "-M" || a == "-MM" || a == "-fsyntax-only") link = false;
        if (a == "-fno-exceptions") exceptions = false;
        if (a == "-fexceptions" || a == "-fcxx-exceptions") exceptions = true;
        if (a == "-v") verbose = true;
        if (a == "-###") dryRun = true;
        if (a == "--help" || a == "-help") {
            usage();
            return 0;
        }
        if (a == "--version" || a == "-dumpmachine" || a == "-dumpversion" || startsWith(a, "-print-")) infoOnly = true;
        if (pathOptions().count(a) && i + 1 < user.size()) {
            args.push_back(a);
            args.push_back(absolute(user[++i]));
            continue;
        }
        if (valueOptions().count(a) && i + 1 < user.size()) {
            if (a == "-l") hasInput = true;
            args.push_back(a);
            args.push_back(user[++i]);
            continue;
        }
        if (startsWith(a, "-l") && a.size() > 2) hasInput = true; // "c++ -o prog -L. -lfoo" links only libraries
        bool joined = false;
        for (auto *opt : {"-I", "-L", "-isystem", "-iquote", "-idirafter", "-MF"}) {
            std::string o = opt;
            if (startsWith(a, o) && a.size() > o.size()) {
                args.push_back(o + absolute(a.substr(o.size())));
                joined = true;
                break;
            }
        }
        if (joined) continue;
        if (startsWith(a, "--sysroot=")) {
            args.push_back("--sysroot=" + absolute(a.substr(10)));
            continue;
        }
        if (!a.empty() && a[0] != '-') {
            hasInput = true;
            args.push_back(absolute(a));
            continue;
        }
        args.push_back(a);
    }
    if (!hasInput && !infoOnly && !verbose) {
        std::fprintf(stderr, "%s: no input files\n", prog.c_str());
        return 1;
    }

    std::string clang = cxx ? "clang++" : "clang";
    Args command = {clang, "--target=wasm32-wasip1", "--sysroot=" + SYSROOT, "-resource-dir=" + RESOURCE_DIR,
                    "-isystem", "/usr/local/include", "-isystem", "/usr/include", "-D__BAREMETAL__=1"};
    if (exceptions) {
        // The runtime implements the current WebAssembly exception handling, not the legacy one.
        command.insert(command.end(), {"-fwasm-exceptions", "-mllvm", "-wasm-use-legacy-eh=false"});
    } else {
        command.push_back("-fno-exceptions");
    }
    command.insert(command.end(), args.begin(), args.end());
    if (link && hasInput) {
        // crt_cwd.o makes programs start in the shell's working directory (WASI has no inherited cwd).
        command.insert(command.end(), {"-L/usr/local/lib", "-L/usr/lib", "/usr/lib/crt_cwd.o", "-lbaremetal",
                                       "-Wl,-z,stack-size=1048576",
                                       // owners and modes: libbaremetal's stat/chmod/access replace the C library's
                                       "-Wl,--wrap=stat,--wrap=lstat,--wrap=fstat,--wrap=fstatat,--wrap=chmod,--wrap=fchmod,"
                                       "--wrap=fchmodat,--wrap=access,--wrap=faccessat"});
        // The C++ runtime for every driver, like a C++-aware gcc: "cc game.cpp" and "cc a.o b.o" with C++ objects
        // must link too. Archive members are only pulled in when used, so C programs do not grow.
        command.insert(command.end(), {"-lc++", "-lc++abi", "-lunwind"});
    }
    if (infoOnly || !hasInput) return runTool(clang, command);

    // Ask clang for the steps, then run them.
    auto plan = command;
    plan.push_back("-###");
    auto steps = capture(clang, plan);
    if (!steps) return 1;
    std::vector<Args> commands;
    size_t start = 0;
    while (start < steps->size()) {
        auto end = steps->find('\n', start);
        if (end == std::string::npos) end = steps->size();
        auto line = steps->substr(start, end - start);
        start = end + 1;
        if (startsWith(line, " \"")) {
            if (auto c = parseCommand(line)) commands.push_back(*c);
        } else if (verbose && !dryRun && !line.empty()) {
            std::fprintf(stderr, "%s\n", line.c_str());
        }
    }
    if (dryRun) {
        std::fwrite(steps->data(), 1, steps->size(), stderr);
        return 0;
    }
    for (auto &c : commands) {
        auto [tool, args] = toolCommand(c);
        int r = runTool(tool, args);
        if (r != 0) return r;
    }
    if (link) {
        // the linker of a WASI program cannot set modes: make the program runnable here, as ld does on Linux
        std::string output = "a.out";
        for (size_t i = 0; i + 1 < args.size(); i++) {
            if (args[i] == "-o") output = args[i + 1];
        }
        for (auto &a : args) {
            if (startsWith(a, "-o") && a.size() > 2) output = a.substr(2);
        }
        mode_t mask = umask(0);
        umask(mask);
        chmod(absolute(output).c_str(), 0777 & ~mask);
    }
    return 0;
}
