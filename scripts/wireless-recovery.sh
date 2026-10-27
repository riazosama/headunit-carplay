#!/bin/bash
# Watch for the head unit on any adb transport (USB, the home-LAN boot window,
# or an address you pass in), flip ro.atc.carplay.wireless back to 0, and
# reboot into normal operation.
#
#   ./scripts/wireless-recovery.sh [unit-ip[:5555] ...]
#
# If the unit is stuck in hotspot mode, power-cycle it and run this: at boot the
# unit rejoins your home Wi-Fi for roughly 20-60 seconds before the hotspot
# takes over, which is the window this script waits for.
#
# Optional environment: ADB (default: adb), LOG, TIMEOUT (seconds, default 1800).
set -u

ADB=${ADB:-adb}
HERE=$(cd "$(dirname "$0")" && pwd)
LOG=${LOG:-"$HERE/wireless-recovery.log"}
TIMEOUT=${TIMEOUT:-1800}
IPS="$*"

: >"$LOG"
"$ADB" kill-server >/dev/null 2>&1
"$ADB" start-server >/dev/null 2>&1

revert() { # $1 = adb serial
  local S="$1"
  echo "$(date +%T) connected via $S" >>"$LOG"
  "$ADB" -s "$S" shell 'echo "wireless_prop=$(getprop ro.atc.carplay.wireless)"' >>"$LOG" 2>&1
  "$ADB" -s "$S" remount >>"$LOG" 2>&1
  "$ADB" -s "$S" shell 'sed -i "s/^ro.atc.carplay.wireless=1$/ro.atc.carplay.wireless=0/" /vendor/build.prop; sync; grep -n "atc.carplay.wireless" /vendor/build.prop' >>"$LOG" 2>&1
  echo "$(date +%T) revert applied; rebooting" >>"$LOG"
  "$ADB" -s "$S" reboot >>"$LOG" 2>&1
  exit 0
}

echo "$(date +%T) waiting for the unit..." >>"$LOG"
end=$(( $(date +%s) + TIMEOUT ))
while [ "$(date +%s)" -lt "$end" ]; do
  # Anything adb already knows about (USB or a previous connect).
  for s in $("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}'); do
    revert "$s"
  done

  # Addresses passed on the command line.
  for ip in $IPS; do
    case "$ip" in *:*) ;; *) ip="$ip:5555" ;; esac
    "$ADB" connect "$ip" >/dev/null 2>&1
  done

  sleep 1
done

echo "$(date +%T) timeout: the unit never appeared" >>"$LOG"
exit 1
