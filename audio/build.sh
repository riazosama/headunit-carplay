#!/bin/sh
# Build libAudioConverter.so for the BWIC A100 head unit:
#   Autochips AC8257, aarch64, Android 9 (API 28), NDK r26b.
#
# Output: audio/libAudioConverter.so
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
NDK=${NDK:-"$HOME/Library/Android/sdk/ndk/26.1.10909125"}
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
CC="$TOOLCHAIN/bin/aarch64-linux-android28-clang"
STRIP="$TOOLCHAIN/bin/llvm-strip"

[ -x "$CC" ] || { echo "error: clang not found at $CC" >&2; exit 1; }

mkdir -p "$HERE/build"

CFLAGS="-O2 -g -fPIC -fvisibility=hidden -std=c17 -Wall -Wextra \
        -Wno-unused-parameter -Wno-deprecated-declarations \
        -DANDROID"

SO="$HERE/libAudioConverter.so"

"$CC" $CFLAGS -shared \
    -Wl,-soname,libAudioConverter.so \
    -Wl,-z,max-page-size=4096 \
    -Wl,--build-id=sha1 \
    -o "$SO" \
    "$HERE/src/audio_converter.c" \
    -llog -lmediandk -ldl -lm

if [ "${NOSTRIP:-0}" != "1" ] && [ -x "$STRIP" ]; then
    "$STRIP" --strip-unneeded "$SO"
fi

echo "built: $SO"
file "$SO" 2>/dev/null || true
echo "--- exported symbols ---"
"$TOOLCHAIN/bin/llvm-readelf" --dyn-syms "$SO" | grep -E 'AudioConverter' || true
echo "--- NEEDED ---"
"$TOOLCHAIN/bin/llvm-readelf" -d "$SO" | grep NEEDED || true
