#!/bin/sh
# Build lib + test, push to the head unit, run every mode, pull PCM and compare.
#
#   DEV=<unit-ip>:5555 ./audio/test/run_test.sh
#
# NOTE: coordinate with the Lead before running this while a real CarPlay
# session is being validated on the unit: the test process emits
# LibAudioConverter logs that interleave with the real AirPlay process logs.
#
# Modes exercised:
#   apple        Apple 40-byte ASBD (host/unit-test ABI)
#   vendor       36-byte descriptor (real libAirPlay ABI)
#   shim         real /system/lib64/libAirPlaySupport.so -> dlopen(libAudioConverter.so)
#   passthrough  lpcm->lpcm bit-exact
# Plus 48 kHz decode and an ADTS-stripped + magic-cookie decode.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$HERE/../.." && pwd)
ADB=${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}
DEV=${DEV:?set DEV=<unit-ip>:5555}
REMOTE=${REMOTE:-/data/local/tmp/audioconv}
OUT="$HERE/out"
ASSETS="$HERE/assets"
mkdir -p "$OUT"

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }
[ -x "$ASSETS/tone44.aac" ] || { echo "no assets, generating" >&2; "$HERE/gen_assets.sh"; }

echo "== build =="
"$ROOT/audio/build.sh" >/dev/null
"$HERE/build_test.sh" >/dev/null

echo "== device $DEV =="
"$ADB" -s "$DEV" wait-for-device
"$ADB" -s "$DEV" shell mkdir -p "$REMOTE"
"$ADB" -s "$DEV" push "$ROOT/audio/libAudioConverter.so" "$REMOTE/" >/dev/null
"$ADB" -s "$DEV" push "$HERE/test_audioconv" "$REMOTE/" >/dev/null
for f in tone44.aac tone44_ref.raw tone44_src.raw tone48.aac tone48_ref.raw tone22.aac tone22_ref.raw noise44.aac noise44_ref.raw; do
    "$ADB" -s "$DEV" push "$ASSETS/$f" "$REMOTE/" >/dev/null
done
"$ADB" -s "$DEV" shell "chmod 755 $REMOTE/test_audioconv"

run() { # $1 = shell command (cwd is $REMOTE, LD_LIBRARY_PATH set)
    "$ADB" -s "$DEV" shell "cd $REMOTE && LD_LIBRARY_PATH=$REMOTE ./test_audioconv $*" \
        2>&1 | sed 's/^/  /'
}

fail=0
echo "== decode tests =="
for spec in "apple tone44.aac out_apple.raw" \
            "vendor tone44.aac out_vendor.raw" \
            "shim tone44.aac out_shim.raw" \
            "apple tone48.aac out_apple48.raw" \
            "vendor tone48.aac out_vendor48.raw" \
            "vendor tone22.aac out_vendor22.raw" \
            "vendor noise44.aac out_noise.raw" \
            "live tone44.aac out_live.raw" \
            "apple tone44.aac out_apple_raw.raw - strip"; do
    # shellcheck disable=SC2086
    set -- $spec
    mode=$1; infile=$2; outfile=$3; cookie=${4:--}; strip=${5:-}
    echo "-- $mode $infile -> $outfile${strip:+ ($strip)}"
    if [ "$strip" = "strip" ]; then
        run "$mode" "$infile" "$outfile" "$cookie" strip | tee "$OUT/$outfile.log"
    else
        run "$mode" "$infile" "$outfile" "$cookie" | tee "$OUT/$outfile.log"
    fi
    "$ADB" -s "$DEV" pull "$REMOTE/$outfile" "$OUT/$outfile" >/dev/null 2>&1 || true
done

echo "== passthrough (bit-exact) =="
run passthrough tone44_src.raw out_passthrough.raw | tee "$OUT/out_passthrough.raw.log"
"$ADB" -s "$DEV" pull "$REMOTE/out_passthrough.raw" "$OUT/out_passthrough.raw" >/dev/null 2>&1 || true
if cmp -s "$OUT/out_passthrough.raw" "$ASSETS/tone44_src.raw"; then
    echo "PASS passthrough bit-exact"
else
    echo "FAIL passthrough differs ($(wc -c < "$OUT/out_passthrough.raw" 2>/dev/null || echo 0) vs $(wc -c < "$ASSETS/tone44_src.raw") bytes)"
    fail=1
fi

echo "== robustness (bounds / no-data / EOS / encode-stub) =="
for m in "nodata tone44.aac x.raw" "eos tone44.aac x.raw"          "bounds tone44.aac x.raw" "encode-stub tone44.aac x.raw"; do
    # shellcheck disable=SC2086
    set -- $m
    out=$(run "$1" "$2" "$3" 2>&1)
    echo "$out" | tail -4
    echo "$out" | grep -q "status=PASS" || { echo "FAIL robustness $1"; fail=1; }
done

echo "== comparisons vs ffmpeg reference =="
python3 "$HERE/compare_pcm.py" "$ASSETS/tone44_ref.raw" "$OUT/out_apple.raw" 2 44100 | tee "$OUT/cmp_apple.txt" || fail=1
python3 "$HERE/compare_pcm.py" "$ASSETS/tone44_ref.raw" "$OUT/out_vendor.raw" 2 44100 | tee "$OUT/cmp_vendor.txt" || fail=1
python3 "$HERE/compare_pcm.py" "$ASSETS/tone44_ref.raw" "$OUT/out_shim.raw" 2 44100 | tee "$OUT/cmp_shim.txt" || fail=1
python3 "$HERE/compare_pcm.py" "$ASSETS/tone44_ref.raw" "$OUT/out_apple_raw.raw" 2 44100 | tee "$OUT/cmp_apple_raw.txt" || fail=1
python3 "$HERE/compare_pcm.py" "$ASSETS/tone48_ref.raw" "$OUT/out_apple48.raw" 2 48000 | tee "$OUT/cmp_apple48.txt" || fail=1
python3 "$HERE/compare_pcm.py" "$ASSETS/tone48_ref.raw" "$OUT/out_vendor48.raw" 2 48000 | tee "$OUT/cmp_vendor48.txt" || fail=1
python3 "$HERE/compare_pcm.py" "$ASSETS/tone22_ref.raw" "$OUT/out_vendor22.raw" 2 22050 | tee "$OUT/cmp_vendor22.txt" || fail=1
python3 "$HERE/compare_pcm.py" "$ASSETS/noise44_ref.raw" "$OUT/out_noise.raw" 2 44100 | tee "$OUT/cmp_noise.txt" || fail=1
python3 "$HERE/compare_pcm.py" "$ASSETS/tone44_ref.raw" "$OUT/out_live.raw" 2 44100 | tee "$OUT/cmp_live.txt" || fail=1

echo "== logcat (LibAudioConverter) =="
"$ADB" -s "$DEV" logcat -d -v brief -s LibAudioConverter:* 2>/dev/null | tail -60 | tee "$OUT/logcat.txt" || true

echo
if [ "$fail" = 0 ]; then echo "RESULT: ALL PASS"; else echo "RESULT: FAILURES"; fi
exit "$fail"
