#!/bin/bash
# Offline check for wireless-revert.sh / wireless-recovery.sh / restore-wired.sh against a fake adb.
# Nothing here touches a device.
#
#   ./scripts/test-revert.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

# Fake adb: answers from env, logs reboots. FAKE_UNIT = the serial that is the head unit.
cat >"$T/adb" <<'EOF'
#!/bin/bash
[ "$1" = -s ] && { S=$2; shift 2; }
echo "$*" >>"${FAKE_CALLS:-/dev/null}"
case "$1 ${2:-}" in
  "devices "*) printf 'List of devices attached\n%s\n' "$FAKE_DEVICES" ;;
  "shell getprop") [ "${S:-}" = "$FAKE_UNIT" ] && echo 1 ;;
  "shell grep -q"*) exit "$FAKE_GREP" ;;
  "reboot "*) echo "$S" >>"$FAKE_REBOOTS" ;;
esac
exit 0
EOF
chmod +x "$T/adb"
export ADB="$T/adb" FAKE_REBOOTS="$T/reboots" FAKE_UNIT=unit FAKE_GREP=0 FAKE_DEVICES=""

fail=0
check() { # name, expected exit, expected reboots, actual exit
  local got; got=$(cat "$FAKE_REBOOTS" 2>/dev/null | tr '\n' ' ')
  if [ "$4" = "$2" ] && [ "$got" = "$3" ]; then echo "ok: $1"
  else echo "FAIL: $1 (exit $4, reboots '$got')"; fail=1; fi
  rm -f "$FAKE_REBOOTS"
}

"$HERE/wireless-revert.sh" phone >/dev/null 2>&1
check "revert leaves a non-head-unit alone" 3 "" $?

FAKE_GREP=1 "$HERE/wireless-revert.sh" unit >/dev/null 2>&1
check "revert does not reboot when the edit did not take" 1 "" $?

"$HERE/wireless-revert.sh" unit >/dev/null 2>&1
check "revert reboots once the edit took" 0 "unit " $?

FAKE_DEVICES=$'phone\tdevice\nunit\tdevice' LOG="$T/log" TIMEOUT=20 "$HERE/wireless-recovery.sh" >/dev/null 2>&1
check "recovery skips other devices and reverts the unit" 0 "unit " $?

# restore-wired.sh: settings go back to the backup value, or are unset when the backup had none
mkdir -p "$T/r/backup"
printf 'carplay_on=0\n' >"$T/r/backup/settings-global.backup.txt"
printf 'enabled_accessibility_services=com.foo/.Svc\n' >"$T/r/backup/settings-secure.backup.txt"
(cd "$T/r" && FAKE_CALLS="$T/calls" "$HERE/../restore-wired.sh" >/dev/null 2>&1)
if grep -qxF "shell settings delete global hidden_api_policy" "$T/calls" &&
   grep -qxF "shell settings put secure enabled_accessibility_services 'com.foo/.Svc'" "$T/calls" &&
   grep -qxF "shell settings delete secure accessibility_enabled" "$T/calls" &&
   grep -qxF "shell pm enable --user 0 com.bw.ipodservice" "$T/calls"; then
  echo "ok: restore-wired puts settings back from the backup"
else echo "FAIL: restore-wired settings"; fail=1; fi

[ "$fail" = 0 ] && echo "PASS" || { echo "FAILED"; exit 1; }
