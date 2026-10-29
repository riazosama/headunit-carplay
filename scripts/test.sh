#!/bin/bash
# Offline checks for the scripts that drive the unit, against a fake adb.
# Nothing here touches a device.
#
#   ./scripts/test.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

# Fake adb. Each device is a directory $FAKE/dev/<serial> standing in for its
# root filesystem; shell commands run locally against it, with getprop/setprop/
# settings/pm stubbed. Every call goes to $FAKE/calls, reboots to $FAKE/reboots,
# /vendor writes to $FAKE/writes, device-side changes to $FAKE/log.
# Per device: a `flaky` file holds how many head-unit probes drop first, a
# `stuck` file makes sed -i writes not land, `fail-get`/`fail-put` files make
# settings reads/writes fail, an `i2c` file is what i2cdetect prints.
cat >"$T/stubs.sh" <<'EOF'
getprop() { if [ $# = 0 ]; then cat "$D/props"; else sed -n "s/^\[$1\]: \[\(.*\)\]\$/\1/p" "$D/props"; fi; }
setprop() { echo "setprop $*" >>"$FAKE/log"; }
settings() { case $1 in
  list) cat "$D/settings-$2" 2>/dev/null || true ;;
  get) [ -e "$D/fail-get" ] && return 1; sed -n "s/^$3=//p" "$D/settings-$2" 2>/dev/null || true ;;
  *) [ -e "$D/fail-put" ] && return 1; echo "settings $*" >>"$FAKE/log" ;;
esac; }
pm() { case $1 in list) cat "$D/packages" 2>/dev/null || true ;; *) echo "pm $*" >>"$FAKE/log" ;; esac; }
cmd() { :; }
id() { echo 0; }
mount() { :; }
sync() { :; }
i2cdetect() { cat "$D/i2c" 2>/dev/null || true; }
EOF
cat >"$T/adb" <<'EOF'
#!/bin/bash
S=; [ "$1" = -s ] && { S=$2; shift 2; }
D="$FAKE/dev/$S"
echo "$S $*" >>"$FAKE/calls"
case "$1" in
  devices) printf 'List of devices attached\n%s\n' "${FAKE_DEVICES:-}" ;;
  reboot) echo "$S" >>"$FAKE/reboots" ;;
  pull) exec cp "$D$2" "$3" ;;
  shell|exec-out)
    [ -d "$D" ] || exit 1
    shift; c="$*"   # adb joins its arguments into one device command
    case "$c" in *'getprop ro.atc'*)
      n=$(cat "$D/flaky" 2>/dev/null || echo 0)
      [ "$n" -gt 0 ] && { echo $((n - 1)) >"$D/flaky"; exit 1; } ;;
    esac
    case "$c" in *"sed -i"*) echo "$S" >>"$FAKE/writes"; [ -e "$D/stuck" ] && exit 0 ;; esac
    c=$(printf '%s' "$c" | sed "s#/vendor/#$D/vendor/#g; s#/sdcard/#$D/sdcard/#g; s#sed -i #sed -i.bak #g")
    D="$D" exec sh -c ". '$FAKE/stubs.sh'; $c" ;;
esac
exit 0
EOF
chmod +x "$T/adb"
export ADB="$T/adb" FAKE="$T" ROOT_WAIT=0 BACKUP="$T/backup"

# device <serial> <wireless line or ""> <running value>
device() {
  rm -rf "$T/dev/$1"; mkdir -p "$T/dev/$1/vendor" "$T/dev/$1/sdcard"
  printf 'ro.build.type=userdebug\n%s\nro.vendor.x=1\n' "$2" >"$T/dev/$1/vendor/build.prop"
  printf '[ro.atc.carplay.wireless]: [%s]\n' "$3" >"$T/dev/$1/props"
}
reset() { rm -rf "$T/calls" "$T/reboots" "$T/writes" "$T/log" "$T/backup"; }
line() { sed -n 's/^ro.atc.carplay.wireless=//p' "$T/dev/$1/vendor/build.prop"; }
n() { cat "$T/$1" 2>/dev/null | grep -c -- "${2:-.}"; }

fail=0
check() { # name, condition...
  local name=$1; shift
  if "$@"; then echo "ok: $name"; else echo "FAIL: $name"; fail=1; fi
}
MODE="$HERE/wireless-mode.sh"

# --- wireless-mode.sh ----------------------------------------------------
reset; device phone "" ""; "$MODE" off phone >/dev/null 2>&1; rc=$?
check "mode leaves a device without the line alone" [ "$rc:$(n reboots):$(n writes)" = 3:0:0 ]

reset; device unit ro.atc.carplay.wireless=1 1; "$MODE" off unit >/dev/null 2>&1; rc=$?
check "mode off writes once, reads back, reboots" [ "$rc:$(n writes):$(n reboots):$(line unit)" = 0:1:1:0 ]

reset; device unit ro.atc.carplay.wireless=0 1; "$MODE" off unit >/dev/null 2>&1; rc=$?
check "mode off with the file already 0 only reboots" [ "$rc:$(n writes):$(n reboots)" = 0:0:1 ]

reset; device unit ro.atc.carplay.wireless=0 0; "$MODE" off unit >/dev/null 2>&1; rc=$?
check "mode off when already off does nothing" [ "$rc:$(n writes):$(n reboots)" = 0:0:0 ]

reset; device unit ro.atc.carplay.wireless=1 1; touch "$T/dev/unit/stuck"; "$MODE" off unit >/dev/null 2>&1; rc=$?
check "mode stops (4) without rebooting when the write does not read back" [ "$rc:$(n writes):$(n reboots)" = 4:1:0 ]

reset; device unit ro.atc.carplay.wireless=1 1; chmod a-w "$T/dev/unit/vendor/build.prop"; "$MODE" off unit >/dev/null 2>&1; rc=$?
check "mode writes nothing (1) while /vendor is read-only" [ "$rc:$(n writes):$(n reboots)" = 1:0:0 ]

reset; device unit ro.atc.carplay.wireless=2 1; "$MODE" off unit >/dev/null 2>&1; rc=$?
check "mode refuses (4) an unexpected value" [ "$rc:$(n writes):$(n reboots)" = 4:0:0 ]

reset; device unit ro.atc.carplay.wireless=1 1; echo 1 >"$T/dev/unit/flaky"; "$MODE" off unit >/dev/null 2>&1; rc=$?
check "mode treats a dropped probe as retry (1), not 'not the unit'" [ "$rc:$(n writes)" = 1:0 ]

reset; device unit ro.atc.carplay.wireless=0 0; "$MODE" on unit >/dev/null 2>&1; rc=$?
check "mode on backs up first, writes once, reboots" \
  [ "$rc:$(n writes):$(n reboots):$(line unit):$(grep -c =0 "$T/backup/vendor-build.prop")" = 0:1:1:1:1 ]

"$MODE" maybe unit >/dev/null 2>&1; rc=$?
check "mode rejects a bad argument" [ "$rc" = 2 ]

# --- wireless-recovery.sh --------------------------------------------------
R() { LOG="$T/rlog" "$HERE/wireless-recovery.sh" >/dev/null 2>&1; }

reset; device phone "" ""; device unit ro.atc.carplay.wireless=1 1
FAKE_DEVICES=$'phone\tdevice\nunit\tdevice' TIMEOUT=20 R; rc=$?
check "recovery skips other devices and reverts the unit" [ "$rc:$(n reboots unit):$(n reboots phone)" = 0:1:0 ]

reset; device unit ro.atc.carplay.wireless=1 1; touch "$T/dev/unit/stuck"
FAKE_DEVICES=$'unit\tdevice' TIMEOUT=20 R; rc=$?
check "recovery stops after one failed write instead of looping" [ "$rc:$(n writes)" = 4:1 ]

reset; device phone "" ""
FAKE_DEVICES=$'phone\tdevice' TIMEOUT=3 R; rc=$?
check "recovery probes a skipped device only once" [ "$rc:$(n calls '^phone shell .*getprop ro.atc')" = 1:1 ]

reset; device unit ro.atc.carplay.wireless=1 1; echo 1 >"$T/dev/unit/flaky"
FAKE_DEVICES=$'unit\tdevice' TIMEOUT=20 R; rc=$?
check "recovery retries the unit after a dropped probe" [ "$rc:$(n writes):$(n reboots)" = 0:1:1 ]

# --- backup.sh / restore-wired.sh -----------------------------------------
reset; device unit ro.atc.carplay.wireless=0 0; D="$T/dev/unit"
printf '[persist.carplay.i2c.slave_addr]: [17]\n' >>"$D/props"
printf 'carplay_on=0\n' >"$D/settings-global"
printf 'package:com.example\n' >"$D/packages"
"$REPO/backup.sh" unit >/dev/null 2>&1; rc=$?
printf '[persist.carplay.i2c.slave_addr]: [16]\n' >"$D/props"
"$REPO/backup.sh" unit >/dev/null 2>&1
check "backup takes everything once and keeps the original" \
  [ "$rc:$(ls "$T/backup" | wc -l | tr -d ' '):$(grep -c '\[17\]' "$T/backup/getprop.backup.txt")" = 0:6:1 ]

"$REPO/restore-wired.sh" unit >/dev/null 2>&1; rc=$?
check "restore puts back the backed-up address and switch, removes the screen app" \
  [ "$rc:$(n log 'slave_addr 17$'):$(n log 'put global carplay_on 0'):$(n log 'uninstall app.carplayscreen')" = 0:1:1:1 ]

reset; device unit ro.atc.carplay.wireless=0 0; D="$T/dev/unit"
printf 'package:app.carplayscreen\n' >"$D/packages"
"$REPO/backup.sh" unit >/dev/null 2>&1
"$REPO/restore-wired.sh" unit >/dev/null 2>&1; rc=$?
check "restore clears never-set values and keeps a factory screen app" \
  [ "$rc:$(n log 'slave_addr $'):$(n log 'delete global carplay_on'):$(n log uninstall)" = 0:1:1:0 ]

# --- enable-carplay.command ------------------------------------------------
# unit <i2c row or ""> [slave_addr] [carplay_on] [package]: a CarPlay unit,
# by default with the chip address wrongly 0x11 (17) and the switch off
unit() {
  reset; device unit:5555 "" ""; D="$T/dev/unit:5555"
  printf '[persist.carplay.i2c.slave_addr]: [%s]\n' "${2:-17}" >>"$D/props"
  printf 'carplay_on=%s\n' "${3:-0}" >"$D/settings-global"
  printf 'package:com.autochips.carplay\n%s\n' "${4:-}" >"$D/packages"
  [ -z "$1" ] || printf '%s\n' "$1" >"$D/i2c"
}
E() { rm -f "$T/elog"; printf '%s\n' "$1" | LOG="$T/elog" "$REPO/enable-carplay.command" unit:5555 2>&1; }
lg() { grep -c -- "$1" "$T/elog"; }

unit "10: 10 -- "; E y >/dev/null; rc=$?; b=$([ -e "$T/backup/getprop.backup.txt" ] && echo y)
check "enable backs up, installs the app, points at the probed chip, switches on" \
  [ "$rc:$b:$(n calls 'install carplay-screen'):$(n log 'slave_addr 16$'):$(n log 'put global carplay_on 1')" = 0:y:1:1:1 ]

unit "10: 10 -- "; E n >/dev/null; rc=$?
check "enable changes nothing when the user says no" [ "$rc:$(n log):$(n calls install)" = 0:0:0 ]

unit "10: 10 -- " 16 1 package:app.carplayscreen; E y >/dev/null; rc=$?
check "enable leaves a unit that is already set up alone" [ "$rc:$(n log):$(n calls install)" = 0:0:0 ]

unit ""; E y >/dev/null; rc=$?
check "enable never guesses the address when the chip doesn't answer" \
  [ "$rc:$(n log setprop):$(n log 'put global carplay_on 1')" = 0:0:1 ]

unit "10: 10 11"; E y >/dev/null; rc=$?
check "enable never guesses the address when both 0x10 and 0x11 answer" \
  [ "$rc:$(n log setprop):$(n log 'put global carplay_on 1')" = 0:0:1 ]

says() { printf '%s\n' "$out" | grep -c -- "$1"; }
unit "10: 10 -- "; touch "$D/fail-put"; out=$(E y); rc=$?
check "enable reports a half-done change once, with the undo" \
  [ "$rc:$(n log 'slave_addr 16$'):$(says 'stopped'):$(says 'Undo what changed: cd .* && ./restore-wired.sh unit:5555')" = 1:1:1:1 ]
check "enable logs the run, the raw probe and the failure, and says where the log is" \
  [ "$(lg '^== '):$(lg 'MFi chip: *0x10'):$(lg '^10: 10'):$(lg stopped):$(says "Log of this run: $T/elog")" = 1:1:5:1:1 ]

unit "10: 10 -- "; touch "$D/fail-get"; out=$(E y); rc=$?
check "enable reports a failed read once, before any change, without an undo" \
  [ "$rc:$(n log):$(says 'stopped'):$(says Undo)" = 1:0:1:0 ]

unit "10: 10 -- "; rm "$D/vendor/build.prop"; out=$(E y); rc=$?
check "enable says plainly when the backup fails, and changes nothing" \
  [ "$rc:$(n log):$(n calls install):$(says 'backup failed, so nothing was changed'):$(says 'stopped')" = 1:0:0:1:0 ]

# --- bridge-log.sh -----------------------------------------------------------
reset; device unit "" ""; L="$T/dev/unit/sdcard/Android/data/com.cpbridge/files"; mkdir -p "$L"
cat >"$L/cpbridge.log" <<'EOF'
1730000000000 E/CPBRIDGE_DIAG: [stage=a_rfcomm_connect] DIAL 4C:57:CA:00:11:22 (Sam's iPhone) via SDP 00000000-deca-fade-deca-deafdecacaff
1730000000001 E/CPBRIDGE_DIAG: [stage=e_auth] AUTH daemon MFi serial="ABCD1234EFGH"
1730000000002 E/CPBRIDGE_DIAG: [stage=e_auth] AUTH daemon certificate OK: 607B first16=308202570201a1a0
1730000000003 I/CPBRIDGE_IAP2: CSM TX AccessoryWiFiConfigurationInformation (0x5703): bssid=02:1a:11:f0:00:01 ssid="Car Hotspot" passphrase="pa"ss word" security=2 (WPA/WPA2) channel=6
1730000000004 I/CPBRIDGE_IAP2:   tx csm: 40 40 00 2a 57 03 00 0a 00 00 02 1a 11 f0 00 01
1730000000005 I/CPBRIDGE_IAP2: identification: name="CarPlay" model=A100 serial=SN12345 fw=1.0
1730000000006 I/CPBRIDGE_BT: dial attempt peer=4C:57:CA:00:11:22 name=Sam's iPhone bond=BONDED
1730000000007 E/CPBRIDGE_DIAG: [stage=g_carplay] SENT 0x4301 CarPlayStartSession port=7000 deviceId=02:1a:11:f0:00:01
EOF
diag=$("$HERE/bridge-log.sh" unit); all=$("$HERE/bridge-log.sh" unit all)
leaks() { printf '%s\n' "$all" | grep -cE '4C:57|02:1a|ABCD1234|30820257|Car Hotspot|pa"ss|ss word|SN12345|Sam|40 40 00'; }
check "bridge-log redacts identity and hex dumps" [ "$(leaks)" = 0 ]
check "bridge-log keeps the stage funnel by default" \
  [ "$(printf '%s\n' "$diag" | grep -c 'stage='):$(printf '%s\n' "$diag" | grep -c IAP2)" = 4:0 ]

[ "$fail" = 0 ] && echo "PASS" || { echo "FAILED"; exit 1; }
