#!/usr/bin/env bash
# enable-carplay.command — turn on WIRED CarPlay without a coding assistant.
# Double-click it on a Mac, or run it from a terminal (Linux, Git Bash on
# Windows). It does the skill's wired steps — back up, find the MFi chip,
# install the screen app, switch CarPlay on — and asks once before changing
# anything on the unit.
# ponytail: wired only. Wireless writes /vendor and stays a by-hand job.
#
#   ./enable-carplay.command [unit-ip[:port]]
set -Eeuo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git Bash would rewrite /vendor/... before adb sees it

# Log every run next to this script, so a failed run can be sent as is: rerun
# with the output going to both the screen and the log.
export LOG=${LOG:-$PWD/enable-carplay.log}
if [ -z "${LOGGING:-}" ]; then
  printf '\n== %s | %s | bash %s\n' "$(date)" "$(uname -srm)" "$BASH_VERSION" >>"$LOG"
  rc=0; LOGGING=1 "$BASH" "${0##*/}" "$@" 2>&1 | tee -a "$LOG" || rc=${PIPESTATUS[0]}
  printf '\n   Log of this run: %s\n   Send that file if something went wrong.\n' "$LOG"
  exit "$rc"
fi

undo=   # the undo command, once the unit has been changed
stopped() {
  [ "$BASH_SUBSHELL" = 0 ] || return 0   # report once, from the main shell
  printf '\n!! stopped: a command failed (line %s).\n' "$1"
  [ -z "$undo" ] || echo "   Undo what changed: $undo"
}
trap 'stopped $LINENO' ERR

ADB=${ADB:-adb}
say() { printf '\n>> %s\n' "$*"; }
die() { printf '\n!! %s\n' "$*"; exit 1; }
ask() { local r; read -r -p "   $1 [y/N] " r || true; [[ $r == [Yy]* ]]; }

# 1. adb
if ! command -v "$ADB" >/dev/null; then
  if command -v brew >/dev/null && ask "adb is missing. Install it with Homebrew?"; then
    brew install android-platform-tools
  else
    die "adb is missing. Install Android platform-tools, then run this again:
   macOS:   brew install android-platform-tools
   Linux:   sudo apt install adb
   Windows: https://developer.android.com/tools/releases/platform-tools (run this in Git Bash)"
  fi
fi

"$ADB" version >>"$LOG" 2>&1 || true

# 2. connect as root
ip=${1:-}
[ -n "$ip" ] || read -r -p "Head unit IP address (unit's Wi-Fi settings, or your router): " ip || true
[ -n "$ip" ] || die "no IP address given"
S=$ip; [[ $S == *:* ]] || S=$S:5555
A=("$ADB" -s "$S")
run() { "${A[@]}" shell "$@" 2>>"$LOG" | tr -d '\r'; }

say "connecting to $S"
"$ADB" connect "$S" >>"$LOG" 2>&1 || true
"${A[@]}" root >>"$LOG" 2>&1 || true
sleep "${ROOT_WAIT:-3}"   # over Wi-Fi `adb root` restarts adbd and drops the link
"$ADB" connect "$S" >>"$LOG" 2>&1 || true
case $(run id -u || true) in
  0) ;;
  "") die "can't reach the unit at $S.
   - Laptop and unit on the same Wi-Fi?
   - Debugging on? Unit: Settings > About > tap Build number 7 times, then Developer options.
   - An 'Allow debugging?' prompt on the unit's screen? Accept it and run this again.
   - macOS 'No route to host': allow Terminal in System Settings > Privacy & Security > Local Network." ;;
  *) die "the unit refused root: it is locked, and this can't enable CarPlay on it." ;;
esac

# 3. back up (once: later runs keep the factory values)
./backup.sh "$S" || die "the backup failed, so nothing was changed. Check the unit is still
   connected (same Wi-Fi, ignition on) and run this again."

# 4. diagnose
pkgs=$(run pm list packages)
vendor=$(grep -iE 'carplay|autochips|airplay' <<<"$pkgs" | grep -v carplayscreen || true)
echo "CarPlay packages:" $vendor >>"$LOG"
app=$(grep -x 'package:app.carplayscreen' <<<"$pkgs" || true)
cur=$(run getprop persist.carplay.i2c.slave_addr)
on=$(run settings get global carplay_on)
# Same probe as SKILL.md Step 3.3: change both together. The chip NACKs while
# asleep, so it probes five times and one answer counts.
# ponytail: bus 6 only. The vendor library opens bus 6 itself, so a chip on
# another bus can't be fixed with an address; the skill covers that case.
probe=$(run 'command -v i2cdetect >/dev/null || { echo no-i2cdetect; exit; }
  for i in 1 2 3 4 5; do i2cdetect -y -r 6 0x10 0x11; done' | tee -a "$LOG" | grep -oE ' 1[01]|no-i2cdetect' | sort -u | tr -d ' ' || true)
want=$cur   # never guess: without one clear answer, keep the address as is
case $probe in
  10|11) want=$((16#$probe)) chip=0x$probe ;;
  no-i2cdetect) chip="unknown: the unit has no i2cdetect" ;;
  "") chip="no answer (it may be asleep: run this again)" ;;
  *) chip="unclear: both 0x10 and 0x11 answered" ;;
esac

say "what I found"
echo "   CarPlay software:     $([ -n "$vendor" ] && echo found || echo 'NOT found')"
echo "   MFi chip:             $chip"
echo "   CarPlay chip address: ${cur:-unset}"
echo "   CarPlay switch:       $([ "$on" = 1 ] && echo on || echo off)"
echo "   Screen app:           $([ -n "$app" ] && echo installed || echo missing)"
[ -n "$vendor" ] || echo "   !! No Apple CarPlay software matched; enabling may do nothing on this unit."
[[ $chip == 0x* ]] || echo "   !! Chip not confirmed: leaving the chip address as is."

todo=()
[ -n "$app" ]        || todo+=("install the CarPlay screen app")
[ "$want" = "$cur" ] || todo+=("set the chip address: ${cur:-unset} -> $want ($chip)")
[ "$on" = 1 ]        || todo+=("switch CarPlay on")
[ ${#todo[@]} -gt 0 ] || { say "nothing to change. Plug your iPhone into the unit's USB port."; exit 0; }

say "I will:"
printf '   - %s\n' "${todo[@]}"
ask "Make these changes?" || { say "nothing changed."; exit 0; }

# 5. enable. The install goes first: it is the step most likely to fail, and
# a failed one leaves the unit untouched.
[ -n "$app" ] || "${A[@]}" install carplay-screen/CarPlay.apk ||
  die "the screen app didn't install, so nothing was changed. This unit's CarPlay
   framework may differ: rebuild the app with carplay-screen/build.sh (see README)."
undo="cd $(printf %q "$PWD") && ./restore-wired.sh $S"
[ "$want" = "$cur" ] || run setprop persist.carplay.i2c.slave_addr "$want"
[ "$on" = 1 ]        || run settings put global carplay_on 1

say "done. Plug your iPhone into the unit's USB port."
if [[ $chip == 0x* ]]; then
  echo "   CarPlay should open. If not, reboot the unit ($ADB -s $S reboot) and plug in again."
else
  echo "   The chip wasn't confirmed, so CarPlay may fail to authenticate."
  echo "   See 'By hand' in the README."
fi
echo "   Undo everything: $undo"
