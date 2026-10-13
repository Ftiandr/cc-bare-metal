#!/bin/sh
# Builds SDL2 for CC: Bare Metal: build/SDL2 (fetched at a fixed commit) -> build/sdl/libSDL2.a and build/sdl/include.
# SDL is zlib-licensed. Ours: SDL_config.h (the platform) and SDL_ccbmvideo.c (the video driver); the patches below
# register the driver and turn off SDL's dynamic API (a static library, no dlopen on WASI).
# Programs need threads (wasm32-wasip1-threads): SDL's audio and timers run on their own threads.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
USERLAND=$HERE/..
WASI_SDK=${WASI_SDK:-$(ls -d "$USERLAND"/../tools/wasi-sdk-* | head -n1)}
SRC=$USERLAND/build/SDL2
OUT=$USERLAND/build/sdl
TAG=release-2.32.10
COMMIT=5d249570393f7a37e037abf22cd6012a4cc56a71

if [ ! -f "$SRC/.fetched" ]; then
    rm -rf "$SRC"
    git -c advice.detachedHead=false clone -q --depth 1 --branch $TAG https://github.com/libsdl-org/SDL.git "$SRC"
    test "$(git -C "$SRC" rev-parse HEAD)" = $COMMIT
    touch "$SRC/.fetched"
fi

# our driver and the patches (idempotent)
mkdir -p "$SRC/src/video/ccbaremetal"
cp "$HERE/SDL_ccbmvideo.c" "$SRC/src/video/ccbaremetal/"
grep -q CCBAREMETAL_bootstrap "$SRC/src/video/SDL_video.c" || sed -i 's|^static VideoBootStrap \*bootstrap\[\] = {$|&\n#ifdef SDL_VIDEO_DRIVER_CCBAREMETAL\n    \&CCBAREMETAL_bootstrap,\n#endif|' "$SRC/src/video/SDL_video.c"
grep -q CCBAREMETAL_bootstrap "$SRC/src/video/SDL_sysvideo.h" || sed -i 's|^extern VideoBootStrap DUMMY_bootstrap;$|extern VideoBootStrap CCBAREMETAL_bootstrap;\n&|' "$SRC/src/video/SDL_sysvideo.h"
grep -q __wasi__ "$SRC/src/dynapi/SDL_dynapi.h" || sed -i 's|^#elif defined(__PS2__)$|#elif defined(__wasi__)\n#define SDL_DYNAMIC_API 0 /* CC: Bare Metal: a static library */\n&|' "$SRC/src/dynapi/SDL_dynapi.h"

rm -rf "$OUT"
mkdir -p "$OUT/obj" "$OUT/include/SDL2"
cp "$SRC"/include/*.h "$OUT/include/SDL2/"
cp "$HERE/SDL_config.h" "$OUT/include/SDL2/SDL_config.h"

CFLAGS="--target=wasm32-wasip1-threads -pthread --sysroot=$WASI_SDK/share/wasi-sysroot -O2 -w \
  -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_PROCESS_CLOCKS -D_WASI_EMULATED_MMAN -D_WASI_EMULATED_GETPID -D_GNU_SOURCE \
  -I$OUT/include/SDL2 -I$SRC/src -I$USERLAND/include"
SOURCES=$(cd "$SRC/src" && ls \
  *.c atomic/*.c audio/*.c audio/dsp/*.c audio/disk/*.c audio/dummy/*.c cpuinfo/*.c dynapi/*.c events/*.c file/*.c \
  filesystem/dummy/*.c haptic/*.c haptic/dummy/*.c hidapi/*.c joystick/*.c joystick/dummy/*.c libm/*.c loadso/dummy/*.c \
  locale/*.c locale/dummy/*.c misc/*.c misc/dummy/*.c power/*.c render/*.c render/software/*.c sensor/*.c \
  sensor/dummy/*.c stdlib/*.c thread/*.c thread/pthread/*.c timer/*.c timer/unix/*.c video/*.c video/dummy/*.c \
  video/yuv2rgb/*.c video/ccbaremetal/*.c)
for f in $SOURCES; do
    o="$OUT/obj/$(echo "$f" | tr / _ | sed 's/\.c$/.o/')"
    "$WASI_SDK/bin/clang" $CFLAGS -c "$SRC/src/$f" -o "$o" &
    [ "$(jobs -r | wc -l)" -ge 8 ] && wait -n 2>/dev/null || true
done
wait
"$WASI_SDK/bin/llvm-ar" rcs "$OUT/libSDL2.a" "$OUT"/obj/*.o
ls -la "$OUT/libSDL2.a"
