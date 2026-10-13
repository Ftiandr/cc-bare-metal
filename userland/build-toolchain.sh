#!/bin/sh
# Assembles the in-game C/C++ toolchain into build/toolchain (zipped into the mod jar as ccbaremetal/toolchain.zip by
# Gradle, unpacked once per installation and mounted read-only):
#
#   llvm/llvm.wasm            clang 22 + lld + binutils as one WebAssembly program (YoWASP build, plain WASI p1);
#                             written by Gradle's precompileToolchain as a slim copy of build/llvm-full.wasm
#   llvm/include, llvm/lib    clang's resource directory (builtin headers, compiler-rt builtins)
#   wasi/include, wasi/lib    the sysroot: wasi-libc and libc++/libc++abi/libunwind with exceptions (wasi-sdk)
#
# Needs: wasi-sdk in tools/ (as for the userland), curl, tar. Downloads YoWASP clang into tools/ on first use.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
TOOLS=$HERE/../tools
OUT=$HERE/../build/toolchain
WASI_SDK=${WASI_SDK:-$(ls -d "$TOOLS"/wasi-sdk-* | head -n1)}
SYSROOT=$WASI_SDK/share/wasi-sysroot

YOWASP_VERSION=22.0.0-git20542-10
YOWASP_SHA1=99e06cff031c57bc040b68ce0dbf41d239cde14c
YOWASP=$TOOLS/yowasp-clang

if [ ! -f "$YOWASP/package/gen/llvm.core.wasm" ]; then
    mkdir -p "$YOWASP"
    curl -sSL -o "$YOWASP/clang.tgz" "https://registry.npmjs.org/@yowasp/clang/-/clang-$YOWASP_VERSION.tgz"
    echo "$YOWASP_SHA1  $YOWASP/clang.tgz" | sha1sum -c -
    tar xzf "$YOWASP/clang.tgz" -C "$YOWASP"
fi

rm -rf "$OUT"
mkdir -p "$OUT/llvm" "$OUT/wasi/include" "$OUT/wasi/lib/wasm32-wasip1"

# --- compiler and its resource directory ---
cp "$YOWASP/package/gen/llvm.core.wasm" "$OUT/../llvm-full.wasm"
RES=$(mktemp -d)
tar xf "$YOWASP/package/gen/llvm-resources.tar" -C "$RES"
mkdir -p "$OUT/llvm/include" "$OUT/llvm/lib"
# builtin headers only: libc and libc++ headers come from the sysroot
for f in "$RES"/include/*; do
    case $(basename "$f") in
        c++|wasm32-wasip1|cuda_wrappers|openmp_wrappers|ppc_wrappers|hlsl|zos_wrappers|llvm_offload_wrappers) ;;
        *) cp -r "$f" "$OUT/llvm/include/" ;;
    esac
done
cp -r "$RES/lib/wasm32-unknown-wasip1" "$OUT/llvm/lib/"
rm -rf "$RES"

# --- sysroot ---
mkdir -p "$OUT/wasi/include/wasm32-wasip1" "$OUT/wasi/include/c++"
for f in "$SYSROOT"/include/wasm32-wasip1/*; do
    case $(basename "$f") in
        eh|noeh) ;;
        *) cp -r "$f" "$OUT/wasi/include/wasm32-wasip1/" ;;
    esac
done
cp -r "$SYSROOT/include/wasm32-wasip1/eh/c++/v1" "$OUT/wasi/include/c++/"
rm -rf "$OUT/wasi/include/c++/v1/__cxx03" # C++03 compatibility headers, unused
LIB=$SYSROOT/lib/wasm32-wasip1
for f in "$LIB"/*.a "$LIB"/*.o "$LIB"/eh/*.a; do
    cp "$f" "$OUT/wasi/lib/wasm32-wasip1/"
done
# Debug info is most of the size of the C++ libraries.
"$WASI_SDK/bin/llvm-strip" --strip-debug "$OUT"/wasi/lib/wasm32-wasip1/*.a "$OUT"/wasi/lib/wasm32-wasip1/*.o

du -sh "$OUT/llvm" "$OUT/wasi"
echo "toolchain assembled in $OUT"
