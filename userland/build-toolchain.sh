#!/bin/sh
# Assembles the in-game C/C++ toolchain into build/toolchain (zipped into the mod jar as ccbaremetal/toolchain.zip by
# Gradle, unpacked once per installation and mounted read-only):
#
#   llvm/llvm.wasm            clang 22 + lld + binutils as one WebAssembly program (YoWASP build, plain WASI p1);
#                             written by Gradle's precompileToolchain as a slim copy of build/llvm-full.wasm
#   llvm/include, llvm/lib    clang's resource directory (builtin headers, compiler-rt builtins)
#   wasi/include, wasi/lib    the sysroot: wasi-libc and libc++/libc++abi/libunwind with exceptions (wasi-sdk), for
#                             wasm32-wasip1 and wasm32-wasip1-threads (pthreads: cc -pthread); the C++ headers are
#                             the same for both
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
mkdir -p "$OUT/llvm" "$OUT/wasi/include" "$OUT/wasi/lib/wasm32-wasip1" "$OUT/wasi/lib/wasm32-wasip1-threads"

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
# compiler-rt for the threads target is not in YoWASP's resources: wasi-sdk's (plain WebAssembly objects)
cp -r "$(ls -d "$WASI_SDK"/lib/clang/*/lib/wasm32-unknown-wasip1-threads | head -n1)" "$OUT/llvm/lib/"

# --- sysroot ---
mkdir -p "$OUT/wasi/include/c++"
for target in wasm32-wasip1 wasm32-wasip1-threads; do
    mkdir -p "$OUT/wasi/include/$target"
    for f in "$SYSROOT/include/$target"/*; do
        case $(basename "$f") in
            eh|noeh) ;;
            *) cp -r "$f" "$OUT/wasi/include/$target/" ;;
        esac
    done
done
cp -r "$SYSROOT/include/wasm32-wasip1/eh/c++/v1" "$OUT/wasi/include/c++/"
rm -rf "$OUT/wasi/include/c++/v1/__cxx03" # C++03 compatibility headers, unused
for target in wasm32-wasip1 wasm32-wasip1-threads; do
    LIB=$SYSROOT/lib/$target
    for f in "$LIB"/*.a "$LIB"/*.o "$LIB"/eh/*.a; do
        cp "$f" "$OUT/wasi/lib/$target/"
    done
    # Debug info is most of the size of the C++ libraries.
    "$WASI_SDK/bin/llvm-strip" --strip-debug "$OUT/wasi/lib/$target"/*.a "$OUT/wasi/lib/$target"/*.o
done

du -sh "$OUT/llvm" "$OUT/wasi"
echo "toolchain assembled in $OUT"
