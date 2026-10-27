#!/usr/bin/env bash
# backup.sh — save the unit's original CarPlay-related state to backup/ before
# any change; restore-wired.sh and the wireless undo read it back. backup/ is
# git-ignored: it holds device-specific values.
#
#   ./backup.sh [adb-serial]
#
# Takes the backup once: on a second run the unit already holds the changed
# values, and overwriting the first backup would lose the originals.
set -euo pipefail
cd "$(dirname "$0")"

A=("${ADB:-adb}"); [ "${1:-}" ] && A+=(-s "$1")
B=${BACKUP:-backup}

if [ -e "$B/getprop.backup.txt" ]; then
  echo ">> $B/ already holds a backup: keeping the original"
  exit 0
fi
mkdir -p "$B"
for ns in global system secure; do
  "${A[@]}" shell settings list "$ns" >"$B/settings-$ns.backup.txt"
done
"${A[@]}" shell pm list packages >"$B/packages.backup.txt"
"${A[@]}" pull /vendor/build.prop "$B/vendor-build.prop" >/dev/null   # the wireless undo needs it
# last: its presence marks a complete backup
"${A[@]}" shell getprop >"$B/getprop.tmp" && mv "$B/getprop.tmp" "$B/getprop.backup.txt"
echo ">> backed up to $B/"
