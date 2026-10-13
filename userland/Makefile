# Builds the CC: Bare Metal system image (userland, C++) with wasi-sdk.
#   make WASI_SDK=/path/to/wasi-sdk        (defaults to ../tools/wasi-sdk-*)
# Output: ../src/main/rootfs (zipped into the mod jar by Gradle).
# The in-game toolchain (clang, libc, libc++) is assembled separately by build-toolchain.sh.

WASI_SDK ?= $(firstword $(wildcard ../tools/wasi-sdk-*))
CXX := $(WASI_SDK)/bin/clang++
AR := $(WASI_SDK)/bin/llvm-ar
TARGET := --target=wasm32-wasip1 --sysroot=$(WASI_SDK)/share/wasi-sysroot
CXXFLAGS := $(TARGET) -std=c++20 -O2 -Wall -Wextra -Wno-unused-parameter -fno-exceptions -Iinclude \
            -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_PROCESS_CLOCKS
# stat/chmod/access of the C library know no owners: libbaremetal wraps them (lib/users.cpp), as the in-game cc does
WRAP := -Wl,--wrap=stat,--wrap=lstat,--wrap=fstat,--wrap=fstatat,--wrap=chmod,--wrap=fchmod,--wrap=fchmodat,--wrap=access,--wrap=faccessat,--wrap=statvfs,--wrap=fstatvfs,--wrap=sysconf
LDFLAGS := $(TARGET) -Wl,--strip-all -Wl,-z,stack-size=1048576 $(WRAP) -lwasi-emulated-signal -lwasi-emulated-process-clocks

ROOT := ../src/main/rootfs
BUILD := build

COREUTILS := $(shell grep -o '^ *{"[^"]*", ' src/coreutils.cpp | sed 's/.*"\(.*\)".*/\1/')
# textutils/ccutils commands: {"name", fn} table entries and t["name"] registrations
TEXTUTILS := $(shell grep -oh '{"[a-z0-9]*", cmd[A-Za-z0-9]*}\|t\(able\)\?\["[a-z0-9]*"\]' src/textutils*.cpp | sed 's/.*"\(.*\)".*/\1/' | sort -u)
CCUTILS := rednet gps speaker lp disk eject monitor inv energy fbset evtest turtle wget curl nc ss netstat ip lsdev
PROGRAMS := sh coreutils textutils ccutils userutils ssh edit pkg periph init cc
USERUTILS := login su sudo visudo passwd lock autologin useradd userdel usermod groupadd groupdel groups

.PHONY: all clean install
all: install

LIBOBJS := $(BUILD)/baremetal.o $(BUILD)/peripherals.o $(BUILD)/compat.o $(BUILD)/users.o $(BUILD)/net.o $(BUILD)/posix.o

$(BUILD)/%.o: lib/%.cpp include/baremetal.h include/baremetal.hpp
	@mkdir -p $(BUILD)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(BUILD)/libbaremetal.a: $(LIBOBJS)
	rm -f $@
	$(AR) rcs $@ $(LIBOBJS)

# Linked as an object, not through the archive, so every program gets it (see lib/crt_cwd.cpp).
$(BUILD)/crt_cwd.o: lib/crt_cwd.cpp
	@mkdir -p $(BUILD)
	$(CXX) $(CXXFLAGS) -c $< -o $@

# The same library for programs with threads (cc -pthread: wasm32-wasip1-threads, shared memory and atomics).
TCXXFLAGS := $(subst wasm32-wasip1,wasm32-wasip1-threads,$(CXXFLAGS)) -pthread
TBUILD := $(BUILD)/threads
$(TBUILD)/%.o: lib/%.cpp include/baremetal.h include/baremetal.hpp
	@mkdir -p $(TBUILD)
	$(CXX) $(TCXXFLAGS) -c $< -o $@

$(TBUILD)/libbaremetal.a: $(subst $(BUILD)/,$(TBUILD)/,$(LIBOBJS))
	rm -f $@
	$(AR) rcs $@ $^

$(BUILD)/%.wasm: src/%.cpp $(BUILD)/crt_cwd.o $(BUILD)/libbaremetal.a include/baremetal.h include/baremetal.hpp
	$(CXX) $(CXXFLAGS) $< $(BUILD)/crt_cwd.o $(BUILD)/libbaremetal.a $(LDFLAGS) -o $@

$(BUILD)/textutils.wasm: src/textutils.cpp src/textutils_sed.cpp src/textutils_awk.cpp src/textutils_files.cpp src/textutils.hpp \
                         $(BUILD)/crt_cwd.o $(BUILD)/libbaremetal.a include/baremetal.h include/baremetal.hpp
	$(CXX) $(CXXFLAGS) src/textutils.cpp src/textutils_sed.cpp src/textutils_awk.cpp src/textutils_files.cpp \
	    $(BUILD)/crt_cwd.o $(BUILD)/libbaremetal.a $(LDFLAGS) -o $@

$(BUILD)/gfxdemo.wasm: packages/demos/gfxdemo.cpp $(BUILD)/crt_cwd.o $(BUILD)/libbaremetal.a
	$(CXX) $(CXXFLAGS) $< $(BUILD)/crt_cwd.o $(BUILD)/libbaremetal.a $(LDFLAGS) -o $@

# SDL2 (zlib) for the system: fetched at a fixed commit and built by sdl/build.sh (our platform config and video driver)
$(BUILD)/sdl/libSDL2.a: sdl/build.sh sdl/SDL_config.h sdl/SDL_ccbmvideo.c $(TBUILD)/libbaremetal.a
	sh sdl/build.sh

install: $(addprefix $(BUILD)/,$(addsuffix .wasm,$(PROGRAMS))) $(BUILD)/gfxdemo.wasm $(BUILD)/libbaremetal.a $(BUILD)/crt_cwd.o \
         $(TBUILD)/libbaremetal.a $(TBUILD)/crt_cwd.o $(BUILD)/sdl/libSDL2.a
	@mkdir -p $(ROOT)/bin $(ROOT)/sbin $(ROOT)/usr/bin $(ROOT)/usr/lib $(ROOT)/usr/include
	cp $(BUILD)/sh.wasm $(ROOT)/bin/sh
	cp $(BUILD)/coreutils.wasm $(ROOT)/bin/coreutils
	cp $(BUILD)/textutils.wasm $(ROOT)/bin/textutils
	cp $(BUILD)/ccutils.wasm $(ROOT)/bin/ccutils
	cp $(BUILD)/userutils.wasm $(ROOT)/bin/userutils
	cp $(BUILD)/ssh.wasm $(ROOT)/bin/ssh
	for a in sshd ssh-keygen ssh-copy-id scp; do printf '#!@/bin/ssh\n' > "$(ROOT)/bin/$$a"; done
	cp $(BUILD)/edit.wasm $(ROOT)/bin/edit
	cp $(BUILD)/pkg.wasm $(ROOT)/bin/pkg
	cp $(BUILD)/periph.wasm $(ROOT)/bin/periph
	cp $(BUILD)/init.wasm $(ROOT)/sbin/init
	for a in $(COREUTILS); do printf '#!@/bin/coreutils\n' > "$(ROOT)/bin/$$a"; done
	for a in $(TEXTUTILS); do printf '#!@/bin/textutils\n' > "$(ROOT)/bin/$$a"; done
	for a in $(CCUTILS); do printf '#!@/bin/ccutils\n' > "$(ROOT)/bin/$$a"; done
	# su, sudo and passwd are setuid root (the kernel marks them, see ImageBackend)
	for a in $(USERUTILS); do printf '#!@/bin/userutils\n' > "$(ROOT)/bin/$$a"; done
	printf '#!@/bin/userutils\n' > $(ROOT)/sbin/setup
	# compiler driver; clang itself lives in the toolchain mount (/usr/lib/llvm)
	cp $(BUILD)/cc.wasm $(ROOT)/usr/bin/cc
	# binutils go through the driver too, which makes their paths absolute (WASI programs have no cwd)
	for a in c++ g++ gcc clang clang++ c99 ld wasm-ld ar ranlib nm objdump objcopy strip size c++filt addr2line; do \
	    printf '#!@/usr/bin/cc\n' > "$(ROOT)/usr/bin/$$a"; done
	cp include/baremetal.h include/baremetal.hpp $(ROOT)/usr/include/
	mkdir -p $(ROOT)/usr/include/linux $(ROOT)/usr/include/sys && cp include/linux/*.h $(ROOT)/usr/include/linux/
	# POSIX pieces WASI lacks (implemented in libbaremetal, lib/compat.cpp and lib/posix.cpp)
	cp include/termios.h include/spawn.h include/stdio.h include/stdlib.h include/unistd.h include/signal.h include/sched.h include/pthread.h include/pwd.h include/grp.h include/shadow.h $(ROOT)/usr/include/
	cp include/sys/wait.h include/sys/ioctl.h include/sys/stat.h include/sys/resource.h include/sys/soundcard.h $(ROOT)/usr/include/sys/
	cp $(BUILD)/libbaremetal.a $(BUILD)/crt_cwd.o $(ROOT)/usr/lib/
	mkdir -p $(ROOT)/usr/lib/wasm32-wasip1-threads && cp $(TBUILD)/libbaremetal.a $(TBUILD)/crt_cwd.o $(ROOT)/usr/lib/wasm32-wasip1-threads/
	cp $(BUILD)/sdl/libSDL2.a $(ROOT)/usr/lib/wasm32-wasip1-threads/
	rm -rf $(ROOT)/usr/include/SDL2 && cp -r $(BUILD)/sdl/include/SDL2 $(ROOT)/usr/include/
	cp sdl/sdl2-config $(ROOT)/usr/bin/sdl2-config
	@mkdir -p $(ROOT)/usr/share/pkg
	python3 mkpkg.py packages/demos/manifest.json $(ROOT)/usr/share/pkg/demos-1.0.ccpkg bin/gfxdemo=$(BUILD)/gfxdemo.wasm

clean:
	rm -rf $(BUILD)
