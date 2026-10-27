#!/bin/bash
# Revert ro.atc.carplay.wireless to 0 and reboot the unit into normal (station)
# operation. Works over the unit's own hotspot, over USB, or during the boot
# window on your home network.
#
#   ./scripts/wireless-revert.sh <adb serial> [more serials ...]
#
# Examples (the hotspot gateway changes on every start; read it from your laptop):
#   ./scripts/wireless-revert.sh 192.168.43.x:5555     # through the hotspot
#   ./scripts/wireless-revert.sh 192.0.2.10:5555       # boot window on your LAN
set -u

ADB=${ADB:-adb}
DEVICES="$*"
if [ -z "$DEVICES" ]; then
  echo "usage: $0 <adb serial> [more serials ...]" >&2
  echo "       e.g. $0 192.168.43.x:5555   (hotspot gateway; 192.0.2.10 is a placeholder)" >&2
  exit 2
fi

WORK=""
for d in $DEVICES; do
  "$ADB" connect "$d" >/dev/null 2>&1
  if "$ADB" -s "$d" shell true 2>/dev/null; then
    WORK="$d"
    break
  fi
done
if [ -z "$WORK" ]; then
  echo "no adb transport answered; check the address and that adb is running" >&2
  exit 1
fi

# Only ever touch the head unit: never remount or reboot some other device.
if [ "$("$ADB" -s "$WORK" shell getprop ro.atc.carplay.support | tr -d '\r')" != 1 ]; then
  echo "$WORK is not an Autochips CarPlay unit; leaving it alone" >&2
  exit 3
fi

echo "using $WORK"
# adbd boots unprivileged (ro.secure=1); remount needs root, and over TCP
# `adb root` restarts adbd, so reconnect before going on.
"$ADB" -s "$WORK" root >/dev/null 2>&1
sleep 3
"$ADB" connect "$WORK" >/dev/null 2>&1 || true
"$ADB" -s "$WORK" remount 2>&1 | tail -1
"$ADB" -s "$WORK" shell 'sed -i "s/^ro.atc.carplay.wireless=1$/ro.atc.carplay.wireless=0/" /vendor/build.prop; sync; grep -n atc.carplay.wireless /vendor/build.prop'
# Reboot only if the edit took; rebooting with =1 just brings the hotspot back.
if ! "$ADB" -s "$WORK" shell 'grep -q "^ro.atc.carplay.wireless=0$" /vendor/build.prop'; then
  echo "revert did NOT apply (no root yet, or /vendor still read-only); not rebooting" >&2
  exit 1
fi
"$ADB" -s "$WORK" reboot
echo "revert applied; the unit is rebooting into normal operation"
