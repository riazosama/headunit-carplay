#!/usr/bin/env bash
# restore-wired.sh — undo the WIRED and receiver-app (DiPlay) CarPlay enables on
# an Autochips head unit, putting back what backup.sh saved in backup/.
# ponytail: reverses only those paths (chip addr, settings, screen app, DiPlay).
# The wireless undo is scripts/wireless-mode.sh off; this never touches /vendor.
#
#   ./restore-wired.sh [adb-serial]
set -euo pipefail
cd "$(dirname "$0")"

A=("${ADB:-adb}"); [ "${1:-}" ] && A+=(-s "$1")
B=${BACKUP:-backup}

echo ">> restoring wired CarPlay changes"
# over TCP: connect first (adb may have forgotten the unit since the enable),
# and again after `adb root`, which restarts adbd and drops the link
reconnect() { case "${1:-}" in *:*) "${A[0]}" connect "$1" >/dev/null 2>&1 || true ;; esac; }
reconnect "${1:-}"
"${A[@]}" root >/dev/null 2>&1 || true
sleep "${ROOT_WAIT:-3}"
reconnect "${1:-}"

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

# a setting back to its backed-up value, deleted if it was never set;
# without a backup, $3 if given, else left as is
restore_setting() { # <namespace> <key> [value-without-backup]
  local f="$B/settings-$1.backup.txt" v
  if [ -f "$f" ]; then
    v=$(sed -n "s/^$2=//p" "$f" | tr -d '\r')
    if [ -n "$v" ]; then
      "${A[@]}" shell "settings put $1 $2 '$v'"
    else
      "${A[@]}" shell settings delete "$1" "$2" >/dev/null
    fi
  elif [ -n "${3:-}" ]; then
    "${A[@]}" shell settings put "$1" "$2" "$3"
  else
    echo "!! no $f — leaving $1 $2 as-is"
  fi
}

restore_setting global carplay_on 0
# Android 10+ hidden-API allowance for the screen app, and DiPlay's USB helper
for key in hidden_api_policy hidden_api_policy_pre_p_apps hidden_api_policy_p_apps; do
  restore_setting global "$key"
done
restore_setting secure enabled_accessibility_services
restore_setting secure accessibility_enabled

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

# receiver-app path: remove DiPlay (its app-ops go with it), and re-enable the
# iPod service the skill disabled for it (a no-op when it never was)
"${A[@]}" shell pm uninstall com.shihab.diplay >/dev/null 2>&1 || true
for pkg in com.bw.ipodservice com.bw.ipod; do
  "${A[@]}" shell pm enable --user 0 "$pkg" >/dev/null 2>&1 || true
done

echo ">> done. reboot to be sure: adb reboot"
