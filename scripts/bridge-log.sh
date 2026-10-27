#!/bin/bash
# Print the bridge's session log with the unit's identity stripped, so it is
# safe to show in a conversation or an issue. The raw log on the unit carries
# the hotspot SSID, MAC addresses, device names, the MFi serial and
# certificate, and hex dumps of every frame (the Wi-Fi credentials frame holds
# the passphrase): never paste it unfiltered.
#
#   ./scripts/bridge-log.sh <adb serial> [all]
#
# Default: the last CPBRIDGE_DIAG stage lines, the funnel a session walks
# through. `all`: every line. Optional environment: ADB, TAIL (lines, default 80).
set -u

ADB=${ADB:-adb}
LOGFILE=/sdcard/Android/data/com.cpbridge/files/cpbridge.log
if [ -z "${1:-}" ]; then
  echo "usage: $0 <adb serial> [all]" >&2
  exit 2
fi
PICK=CPBRIDGE_DIAG
[ "${2:-}" = all ] && PICK=.

# ponytail: pattern redaction tuned to the bridge's own log lines; extend it
# when a new log line carries an identifier.
"$ADB" -s "$1" exec-out cat "$LOGFILE" | grep -a -- "$PICK" | tail -n "${TAIL:-80}" | sed -E \
  -e 's/(passphrase=)".*"( security=)/\1<redacted>\2/' \
  -e 's/(passphrase|ssid|serial|value)="[^"]*"/\1=<redacted>/g' \
  -e 's/(serial|sn)=[^ ",)]+/\1=<redacted>/g' \
  -e 's/ name=[^="]*( [a-zA-Z_]+=|$)/ name=<name>\1/g' \
  -e 's/\([^)]*\) via SDP/(<name>) via SDP/' \
  -e 's/([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}/<mac>/g' \
  -e 's/([0-9A-Fa-f]{2} ){7,}[0-9A-Fa-f]{2}/<hex>/g' \
  -e 's/[0-9A-Fa-f]{16,}/<hex>/g'
