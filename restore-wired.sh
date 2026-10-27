#!/usr/bin/env bash
# restore-wired.sh — undo the WIRED CarPlay enable on an Autochips head unit.
# ponytail: reverses only the wired path (chip addr, carplay_on, screen app).
# Wireless revert is in README.md ("Revert"); this never touches /vendor.
set -euo pipefail

ADB="adb"
[ "${1:-}" ] && ADB="adb -s $1"   # optional: pass device IP:5555 or serial

echo ">> restoring wired CarPlay changes"
$ADB root >/dev/null 2>&1 || true
# over TCP, `adb root` restarts adbd and drops the link: reconnect
sleep 3; [ "${1:-}" ] && adb connect "$1" >/dev/null 2>&1 || true

# carplay_on: back off
$ADB shell settings put global carplay_on 0

# chip address: put back the original if a backup is present, else warn
if [ -f backup/getprop.backup.txt ]; then
  orig=$(grep -E 'persist\.carplay\.i2c\.slave_addr' backup/getprop.backup.txt \
         | sed -E 's/.*\]: \[([0-9]+)\].*/\1/')
  if [[ "${orig:-}" =~ ^[0-9]+$ ]]; then
    echo ">> restoring persist.carplay.i2c.slave_addr=$orig"
    $ADB shell setprop persist.carplay.i2c.slave_addr "$orig"
  fi
else
  echo "!! no backup/getprop.backup.txt — leaving persist.carplay.i2c.slave_addr as-is"
  echo "   set it back by hand if you changed it:"
  echo "   adb shell setprop persist.carplay.i2c.slave_addr <orig>"
fi

# screen app: remove it
$ADB shell pm uninstall app.carplayscreen 2>/dev/null || echo "   screen app not installed"

# anything you disabled for CarPlay: bring it back
for pkg in com.google.android.apps.maps; do
  $ADB shell cmd package install-existing "$pkg" >/dev/null 2>&1 || true
done

echo ">> done. reboot to be sure: adb reboot"
