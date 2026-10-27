#!/bin/sh
# Triage a captured real-session logcat dump for the libAudioConverter hookup.
#
#   adb -s <unit-ip>:5555 logcat -d -v time > /tmp/unit.log
#   ./audio/test/triage_logs.sh /tmp/unit.log
#
# It prints (and PASS/FAILs on) the expected sequence inside the real AirPlay
# receiver process:
#   1. SO LOADED                         (shim dlopen("libAudioConverter.so") worked)
#   2. AudioConverterNew ... vendor36     (SETUP created a converter)
#   3. AAC decoder started / output fmt   (MediaCodec came up)
#   4. Fill#1 ... produced=1024           (first compressed packet decoded)
set -eu
LOG=${1:?usage: triage_logs.sh <logcat-dump>}

say() { printf '%s\n' "$*"; }

say "== libAudioConverter lines =="
grep -E 'LibAudioConverter' "$LOG" | tail -40 || true
say
say "== AirPlay SETUP / stream / audio lines =="
grep -E 'AirPlay|RTSP' "$LOG" | grep -iE 'SETUP|stream type|mainAudio|altAudio|audio format|Tearing down' | tail -25 || true
say

fail=0
check() { # $1 = label, $2 = grep -E pattern
    if grep -qE "$2" "$LOG"; then
        say "PASS  $1"
    else
        say "FAIL  $1   (no match: $2)"
        fail=1
    fi
}

say "== expected sequence =="
check "shim dlopen loaded our lib (SO LOADED)"        'LibAudioConverter.*SO LOADED'
check "AudioConverterNew succeeded (vendor36 layout)" 'AudioConverterNew: in\[.*vendor36'
check "AAC MediaCodec decoder started"                'AAC decoder started'
check "first Fill produced PCM"                       'Fill#1 .*produced=[1-9]'
say
if [ "$fail" = 0 ]; then say "RESULT: hookup looks correct"; else say "RESULT: hookup incomplete (see above)"; fi
exit "$fail"
