#!/bin/sh
# Build the on-device test executable (aarch64, API 28).
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
NDK=${NDK:-"$HOME/Library/Android/sdk/ndk/26.1.10909125"}
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang"
"$CC" -O2 -g -std=c17 -Wall -Wextra -Wno-unused-parameter \
    -o "$HERE/test_audioconv" "$HERE/test_audioconv.c" -ldl
echo "built: $HERE/test_audioconv"
