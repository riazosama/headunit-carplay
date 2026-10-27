#!/bin/sh
# Generate test assets with ffmpeg: ADTS AAC-LC stereo tones + reference PCM.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
A="$HERE/assets"
mkdir -p "$A"
FFMPEG=${FFMPEG:-/opt/homebrew/bin/ffmpeg}
FFPROBE=${FFPROBE:-/opt/homebrew/bin/ffprobe}

gen() { # $1=rate $2=name
    rate=$1; name=$2
    "$FFMPEG" -hide_banner -loglevel error -y \
        -f lavfi -i "aevalsrc='0.40*sin(2*PI*440*t)+0.10*sin(2*PI*1320*t)|0.40*sin(2*PI*554.365*t)+0.10*sin(2*PI*1662*t)':s=${rate}:d=4" \
        -c:a aac -b:a 128k -ar "$rate" -ac 2 -f adts "$A/$name.aac"
    "$FFMPEG" -hide_banner -loglevel error -y -i "$A/$name.aac" \
        -f s16le -acodec pcm_s16le "$A/${name}_ref.raw"
    "$FFMPEG" -hide_banner -loglevel error -y \
        -f lavfi -i "aevalsrc='0.40*sin(2*PI*440*t)+0.10*sin(2*PI*1320*t)|0.40*sin(2*PI*554.365*t)+0.10*sin(2*PI*1662*t)':s=${rate}:d=4" \
        -f s16le -acodec pcm_s16le "$A/${name}_src.raw"
    "$FFPROBE" -hide_banner -loglevel error -show_entries stream=codec_name,sample_rate,channels,duration \
        -of default=nw=1 "$A/$name.aac" | sed "s/^/$name: /"
}

gen 44100 tone44
gen 48000 tone48
gen 22050 tone22

echo "assets:"
ls -l "$A"

# Pink+white noise stereo (harder content than a pure tone) to exercise the
# decoder on a broad spectrum instead of a few sinusoids.
"$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "anoisesrc=d=4:c=pink:r=44100:a=0.35:seed=7" \
    -f lavfi -i "anoisesrc=d=4:c=white:r=44100:a=0.15:seed=11" \
    -filter_complex "[0:a][1:a]join=inputs=2:channel_layout=stereo[a]" -map "[a]" \
    -c:a aac -aac_pns 0 -aac_tns 0 -b:a 128k -ar 44100 -ac 2 -f adts "$A/noise44.aac"
"$FFMPEG" -hide_banner -loglevel error -y -i "$A/noise44.aac" \
    -f s16le -acodec pcm_s16le "$A/noise44_ref.raw"
echo "noise44: $("$FFPROBE" -hide_banner -loglevel error -show_entries stream=sample_rate,channels,duration -of default=nw=1 "$A/noise44.aac" | tr '\n' ' ')"
