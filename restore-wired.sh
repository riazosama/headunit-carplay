#!/usr/bin/env bash
# restore-wired.sh — undo the WIRED CarPlay enable on an Autochips head unit,
# putting back what backup.sh saved in backup/.
# ponytail: reverses only the wired path (chip addr, carplay_on, screen app).
# The wireless undo is scripts/wireless-mode.sh off; this never touches /vendor.
#
#   ./restore-wired.sh [adb-serial]
set -euo pipefail
cd "$(dirname "$0")"

A=("${ADB:-adb}"); [ "${1:-}" ] && A+=(-s "$1")
B=${BACKUP:-backup}

echo ">> restoring wired CarPlay changes"
"${A[@]}" root >/dev/null 2>&1 || true
# over TCP, `adb root` restarts adbd and drops the link: reconnect
sleep "${ROOT_WAIT:-3}"
case "${1:-}" in *:*) "${A[0]}" connect "$1" >/dev/null 2>&1 || true ;; esac

if [ -f "$B/getprop.backup.txt" ]; then
  # chip address: the original value, or empty if it was never set
  orig=$(sed -n 's/^\[persist\.carplay\.i2c\.slave_addr\]: \[\(.*\)\]$/\1/p' "$B/getprop.backup.txt")
  echo ">> persist.carplay.i2c.slave_addr=${orig:-<unset>}"
  "${A[@]}" shell "setprop persist.carplay.i2c.slave_addr '$orig'"
else
  echo "!! no $B/getprop.backup.txt — leaving persist.carplay.i2c.slave_addr as-is"
  echo "   set it back by hand if you changed it:"
  echo "   adb shell setprop persist.carplay.i2c.slave_addr <orig>"
fi

# carplay_on: the original value, deleted if it was never set, off without a backup
if [ -f "$B/settings-global.backup.txt" ]; then
  on=$(sed -n 's/^carplay_on=//p' "$B/settings-global.backup.txt" | tr -d '\r')
  if [ -n "$on" ]; then
    "${A[@]}" shell settings put global carplay_on "$on"
  else
    "${A[@]}" shell settings delete global carplay_on >/dev/null
  fi
else
  "${A[@]}" shell settings put global carplay_on 0
fi

# screen app: remove it unless the unit came with it
if tr -d '\r' 2>/dev/null <"$B/packages.backup.txt" | grep -qx 'package:app.carplayscreen'; then
  echo ">> app.carplayscreen came with the unit: keeping it"
else
  "${A[@]}" shell pm uninstall app.carplayscreen 2>/dev/null || echo "   screen app not installed"
fi

# the article's optional cleanup removes Maps for the user: bring it back
# (a no-op when it was never removed)
for pkg in com.google.android.apps.maps; do
  "${A[@]}" shell cmd package install-existing "$pkg" >/dev/null 2>&1 || true
done

echo ">> done. reboot to be sure: adb reboot"
