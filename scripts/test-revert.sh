#!/bin/bash
# Offline check for wireless-revert.sh / wireless-recovery.sh against a fake adb.
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

[ "$fail" = 0 ] && echo "PASS" || { echo "FAILED"; exit 1; }
