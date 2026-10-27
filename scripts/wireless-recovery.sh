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

SKIP=""   # serials that turned out not to be the head unit

revert() { # $1 = adb serial
  local S="$1"
  echo "$(date +%T) connected via $S" >>"$LOG"
  # wireless-revert.sh gets root first and reboots only once the edit took.
  "$HERE/wireless-revert.sh" "$S" >>"$LOG" 2>&1
  case $? in
    0) echo "$(date +%T) revert applied; rebooting" >>"$LOG"; exit 0 ;;
    3) SKIP="$SKIP $S" ;;
    *) echo "$(date +%T) revert failed on $S; retrying" >>"$LOG" ;;
  esac
}

echo "$(date +%T) waiting for the unit..." >>"$LOG"
end=$(( $(date +%s) + TIMEOUT ))
while [ "$(date +%s)" -lt "$end" ]; do
  # Anything adb already knows about (USB or a previous connect).
  for s in $("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}'); do
    case " $SKIP " in *" $s "*) continue ;; esac
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
