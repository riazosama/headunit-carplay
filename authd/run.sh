#!/bin/sh
# Host-side helper for authd on the unit.
#
#   ./run.sh build          rebuild both binaries with the NDK
#   ./run.sh push           push authd + cpauth_client to /data/local/tmp
#   ./run.sh start          start the daemon on the unit (via su, detached)
#   ./run.sh stop           stop the daemon
#   ./run.sh status         show daemon state
#   ./run.sh selftest [N]   run the on-device selftest N times (default 1)
#   ./run.sh roundtrip      one socket round trip (cert + sign + serial)
#   ./run.sh log [N]        tail the daemon log (default 40 lines)
#   ./run.sh shell          adb shell on the unit
#
# Override the target with DEVICE=ip:port, or ADB=/path/to/adb.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
DEVICE=${DEVICE:?set DEVICE=<unit-ip>:5555}
ADB=${ADB:-adb}
A() { "$ADB" -s "$DEVICE" "$@"; }
T=/data/local/tmp

cmd=${1:-help}
shift || true

case "$cmd" in
    build)     "$HERE/build.sh" ;;
    push)
        A push "$HERE/authd" "$HERE/cpauth_client" "$T/"
        A shell "chmod 755 $T/authd $T/cpauth_client"
        ;;
    start)
        A shell "$T/authd spawn; sleep 2; $T/authd status"
        ;;
    stop)
        A shell "$T/authd stop"
        ;;
    status)
        A shell "$T/authd status"
        ;;
    selftest)
        n=${1:-1}
        A shell "$T/authd selftest $n"
        ;;
    roundtrip)
        A shell "$T/cpauth_client $T/cpauth.sock roundtrip"
        ;;
    log)
        n=${1:-40}
        A shell "tail -n $n $T/authd.log"
        ;;
    shell)
        exec "$ADB" -s "$DEVICE" shell
        ;;
    *)
        sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
        ;;
esac
