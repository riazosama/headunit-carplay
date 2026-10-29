#!/usr/bin/env bash
# restore-wired.sh — undo the WIRED and receiver-app (DiPlay) CarPlay enables on an Autochips
# head unit.
# ponytail: reverses only those paths (chip addr, carplay_on, screen app, DiPlay and its settings).
# Wireless revert is in README.md ("Revert"); this never touches /vendor.
set -euo pipefail

A="${ADB:-adb}"                     # ADB= overrides the binary (scripts/test-revert.sh)
ADB="$A"
[ "${1:-}" ] && ADB="$A -s $1"   # optional: pass device IP:5555 or serial

echo ">> restoring wired CarPlay changes"
$ADB root >/dev/null 2>&1 || true
# over TCP, `adb root` restarts adbd and drops the link: reconnect
sleep 3; [ "${1:-}" ] && $A connect "$1" >/dev/null 2>&1 || true

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

# receiver app (firmware without the stock receiver): remove it, its app-ops go with it
$ADB shell pm uninstall com.shihab.diplay >/dev/null 2>&1 || true

# anything you disabled for CarPlay: bring it back
for pkg in com.google.android.apps.maps; do
  $ADB shell cmd package install-existing "$pkg" >/dev/null 2>&1 || true
done
for pkg in com.bw.ipodservice com.bw.ipod; do
  $ADB shell pm enable --user 0 "$pkg" >/dev/null 2>&1 || true
done

# settings changed for Android 11 / DiPlay: back to the backup value, unset if it had none
restore_setting() { # <namespace> <key>
  local f="backup/settings-$1.backup.txt" line
  [ -f "$f" ] || { echo "!! no $f — leaving $1 $2 as-is"; return 0; }
  if line=$(grep -m1 "^$2=" "$f"); then
    $ADB shell settings put "$1" "$2" "'${line#*=}'"
  else
    $ADB shell settings delete "$1" "$2" >/dev/null || true
  fi
}
for key in hidden_api_policy hidden_api_policy_pre_p_apps hidden_api_policy_p_apps; do
  restore_setting global "$key"
done
restore_setting secure enabled_accessibility_services
restore_setting secure accessibility_enabled

echo ">> done. reboot to be sure: adb reboot"
