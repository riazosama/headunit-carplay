#!/bin/bash
# Set ro.atc.carplay.wireless in /vendor/build.prop and reboot the unit: `on`
# brings up its own hotspot for wireless CarPlay, `off` puts it back into normal
# (station) operation. Works over the hotspot, over USB, or during the boot
# window on your home network.
#
#   ./scripts/wireless-mode.sh on|off <adb serial> [more serials ...]
#
# Examples (the hotspot gateway changes on every start; read it from your laptop):
#   ./scripts/wireless-mode.sh off 192.168.43.x:5555    # through the hotspot
#   ./scripts/wireless-mode.sh off 192.0.2.10:5555      # boot window on your LAN
#
# Repeated /vendor writes corrupted that partition on the reference unit, so a
# run writes at most once, only when the line holds the other value, and
# reboots only after the edit reads back.
#
# Exit codes (wireless-recovery.sh relies on them):
#   0  the line holds the target value; rebooting if the running mode differs
#   1  nothing written, try again: no transport, no root, /vendor read-only
#   2  usage
#   3  not the head unit (no ro.atc.carplay.wireless property or line); left alone
#   4  stop, do not retry: verity is on, the line has an unexpected value, or
#      the read-back after the write is wrong
#
# Optional environment: ADB (default: adb), ROOT_WAIT (seconds, default 3),
# BACKUP (default: backup/ at the repo root; `on` keeps vendor-build.prop there).
set -u

ADB=${ADB:-adb}
ROOT_WAIT=${ROOT_WAIT:-3}
BACKUP=${BACKUP:-"$(cd "$(dirname "$0")/.." && pwd)/backup"}
PROP=ro.atc.carplay.wireless
FILE=/vendor/build.prop

case "${1:-}" in
  on) WANT=1 OLD=0 ;;
  off) WANT=0 OLD=1 ;;
  *) WANT= ;;
esac
shift 2>/dev/null
if [ -z "$WANT" ] || [ $# = 0 ]; then
  echo "usage: $0 on|off <adb serial> [more serials ...]" >&2
  echo "       e.g. $0 off 192.168.43.x:5555   (hotspot gateway; 192.0.2.10 is a placeholder)" >&2
  exit 2
fi

WORK=""
for d in "$@"; do
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
on_unit() { "$ADB" -s "$WORK" shell "$1" 2>/dev/null | tr -d '\r'; }

# Only ever touch the head unit: never remount or reboot some other device.
# The unit has the property (readable without root) or at least the line.
# No answer at all is a dropped link (the boot window is short), not a verdict.
case "$(on_unit "[ -n \"\$(getprop $PROP)\" ] || grep -q '^$PROP=' $FILE && echo unit || echo other")" in
  unit) ;;
  other) echo "$WORK has no $PROP property or line: not the head unit, leaving it alone" >&2; exit 3 ;;
  *) echo "$WORK did not answer; nothing written" >&2; exit 1 ;;
esac

if [ "$WANT" = 1 ] && [ ! -s "$BACKUP/vendor-build.prop" ]; then
  mkdir -p "$BACKUP"
  if ! "$ADB" -s "$WORK" pull "$FILE" "$BACKUP/vendor-build.prop" >/dev/null 2>&1; then
    echo "could not back up $FILE; nothing written" >&2
    exit 1
  fi
fi

echo "using $WORK"
# adbd boots unprivileged (ro.secure=1); remount needs root, and over TCP
# `adb root` restarts adbd, so reconnect before going on.
"$ADB" -s "$WORK" root >/dev/null 2>&1
sleep "$ROOT_WAIT"
case "$WORK" in *:*) "$ADB" connect "$WORK" >/dev/null 2>&1 ;; esac
if [ "$(on_unit 'id -u')" != 0 ]; then
  echo "no root on $WORK yet; nothing written" >&2
  exit 1
fi

cur=$(on_unit "sed -n 's/^$PROP=//p' $FILE")
if [ "$cur" = "$WANT" ]; then
  if [ "$(on_unit "getprop $PROP")" = "$WANT" ]; then
    echo "$PROP is already $WANT and running; nothing to do"
    exit 0
  fi
  echo "$PROP is already $WANT in $FILE; rebooting to apply it"
elif [ "$cur" != "$OLD" ]; then
  echo "$FILE has '$PROP=$cur', expected $OLD or $WANT; not editing it" >&2
  exit 4
else
  out=$("$ADB" -s "$WORK" remount 2>&1)
  if echo "$out" | grep -qi verity; then
    echo "verity is on for /vendor: stop here, and never run adb disable-verity" >&2
    exit 4
  fi
  [ "$(on_unit "[ -w $FILE ] && echo rw")" = rw ] || on_unit 'mount -o rw,remount /vendor' >/dev/null
  if [ "$(on_unit "[ -w $FILE ] && echo rw")" != rw ]; then
    echo "/vendor is still read-only on $WORK; nothing written" >&2
    exit 1
  fi
  # The edit swaps one character, so the size must not change.
  size=$(on_unit "wc -c < $FILE")
  echo "writing $PROP=$WANT (the one /vendor write)"
  on_unit "sed -i 's/^$PROP=$OLD\$/$PROP=$WANT/' $FILE; sync"
  if [ "$(on_unit "sed -n 's/^$PROP=//p' $FILE")" != "$WANT" ] ||
     [ "$(on_unit "wc -c < $FILE")" != "$size" ]; then
    echo "$FILE did not read back as expected. Stop: do not retry and do not reboot;" >&2
    echo "push $BACKUP/vendor-build.prop back by hand while adb still works" >&2
    exit 4
  fi
fi
"$ADB" -s "$WORK" reboot
echo "$PROP=$WANT; $WORK is rebooting"
