#!/bin/sh
# Build authd for the BWIC A100 head unit (Autochips AC8257, arm64, Android 9).
#
#   ./build.sh                 # build with the default NDK
#   NDK=/path/to/ndk ./build.sh
#   API=28 ./build.sh          # Android 9 == API 28
#
# Output: ./authd (aarch64 PIE, pure C, links only bionic libc/libdl/libpthread)
#         ./cpauth_client (reference test client for the unix socket)
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)

if [ -z "${NDK:-}" ]; then
    for cand in /opt/homebrew/share/android-commandlinetools/ndk \
                "$HOME/Library/Android/sdk/ndk"; do
        [ -d "$cand" ] && NDK=$cand && break
    done
fi
[ -n "${NDK:-}" ] || { echo "build.sh: NDK not found, set NDK=" >&2; exit 1; }

# if NDK points at the parent directory, pick the newest installed version
if [ ! -d "$NDK/toolchains/llvm" ]; then
    latest=$(ls -d "$NDK"/*/ 2>/dev/null | sort -V | tail -1 || true)
    [ -n "$latest" ] && NDK=${latest%/}
fi
[ -d "$NDK/toolchains/llvm" ] || { echo "build.sh: bad NDK '$NDK'" >&2; exit 1; }

HOST_TAG=darwin-x86_64
[ "$(uname -s)" = "Linux" ] && HOST_TAG=linux-x86_64
API=${API:-28}
CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android$API-clang"
[ -x "$CC" ] || CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android-clang"
[ -x "$CC" ] || { echo "build.sh: compiler not found under $NDK" >&2; exit 1; }

CFLAGS="-O2 -g -Wall -Wextra -Wno-unused-parameter -fPIE -pie"
LDFLAGS="-ldl"   # bionic: pthread is in libc

echo "NDK    : $NDK"
echo "CC     : $CC"

"$CC" $CFLAGS -o "$HERE/authd" "$HERE/src/authd.c" $LDFLAGS
"$CC" $CFLAGS -o "$HERE/cpauth_client" "$HERE/tools/cpauth_client.c"

# strip debug symbols for the pushable binary? keep them small but useful
if command -v "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip" >/dev/null 2>&1; then
    "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip" --strip-debug "$HERE/authd"
fi

ls -l "$HERE/authd" "$HERE/cpauth_client"
echo "built: $HERE/authd"
