#!/bin/bash
# Build CarPlayBridge.apk with the no-Gradle Android toolchain.
#
#   aapt2 (link) + javac + d8 + zipalign + apksigner
#
# The vendor HIDL Java classes (vendor.autochips.hardware.iap.V1_0.*) are on the
# device boot classpath but NOT in android.jar, so they are compiled from
# hand-written stubs in client/stubs/ into a *compile-time-only* classpath jar.
# That stub jar is passed to javac and d8 --lib, and is NEVER fed to d8 as a
# program input: only com/cpbridge/** ends up in classes.dex.
#
# Output: client/CarPlayBridge.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BUILD_TOOLS="$SDK/build-tools/35.0.1"
PLATFORM="$SDK/platforms/android-35/android.jar"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
KEYTOOL="$JAVA_HOME/bin/keytool"

for f in "$JAVAC" "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" \
         "$BUILD_TOOLS/apksigner" "$PLATFORM"; do
    if [ ! -e "$f" ]; then
        echo "ERROR: missing required tool: $f" >&2
        exit 1
    fi
done

OUT="$ROOT/build"
STUB_CLASSES="$OUT/stubs"
STUB_JAR="$OUT/stubs.jar"
APP_CLASSES="$OUT/classes"
DEX_DIR="$OUT/dex"
KEYSTORE="$ROOT/debug.keystore"

rm -rf "$OUT"
mkdir -p "$STUB_CLASSES" "$APP_CLASSES" "$DEX_DIR"

echo "==> [1/8] compile compile-time-only HAL/framework stubs"
find "$ROOT/stubs" -name '*.java' | sort > "$OUT/stubs.list"
"$JAVAC" --release 8 -nowarn -encoding UTF-8 -cp "$PLATFORM" \
    -d "$STUB_CLASSES" @"$OUT/stubs.list"
"$JAR" cf "$STUB_JAR" -C "$STUB_CLASSES" .
echo "    stub classes: $(find "$STUB_CLASSES" -name '*.class' | wc -l | tr -d ' ')"

echo "==> [2/8] compile app sources against android.jar + stub classpath"
find "$ROOT/src" -name '*.java' | sort > "$OUT/src.list"
"$JAVAC" --release 8 -nowarn -encoding UTF-8 \
    -cp "$PLATFORM:$STUB_JAR" -d "$APP_CLASSES" @"$OUT/src.list"
echo "    app classes: $(find "$APP_CLASSES" -name '*.class' | wc -l | tr -d ' ')"

echo "==> [3/8] aapt2 link (manifest only, no resources)"
"$BUILD_TOOLS/aapt2" link \
    -o "$OUT/unsigned.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --min-sdk-version 28 \
    --target-sdk-version 28

echo "==> [4/8] d8 dex + desugar (program input = app classes only)"
"$JAR" cf "$OUT/program.jar" -C "$APP_CLASSES" .
# --lib tells d8 about android.jar and the boot-classpath stubs WITHOUT bundling
# them; the real implementations are loaded from the device at runtime.
"$BUILD_TOOLS/d8" --release --min-api 28 \
    --lib "$PLATFORM" \
    --lib "$STUB_JAR" \
    --output "$DEX_DIR" \
    "$OUT/program.jar"

echo "==> [5/8] add classes.dex to apk"
(cd "$DEX_DIR" && zip -q "$OUT/unsigned.apk" classes.dex)

echo "==> [6/8] zipalign"
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> [7/8] ensure debug keystore"
if [ ! -f "$KEYSTORE" ]; then
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias cpbridge \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=CarPlay Bridge,O=CPBRIDGE,C=US" >/dev/null 2>&1
    echo "    created $KEYSTORE"
else
    echo "    reusing $KEYSTORE"
fi

echo "==> [8/8] apksigner sign"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version 28 \
    --out "$ROOT/CarPlayBridge.apk" \
    "$OUT/aligned.apk"

echo
echo "==> verifying dex does not bundle boot-classpath stub classes"
DEXCLASSES=$(unzip -p "$ROOT/CarPlayBridge.apk" classes.dex | strings | grep -E '^L(vendor/autochips|android/os/HwBinder|android/os/IHwBinder)' || true)
if echo "$DEXCLASSES" | grep -q 'HwBinder'; then
    echo "WARNING: framework stub class names leaked into dex (as references only is expected)" >&2
fi
unzip -p "$ROOT/CarPlayBridge.apk" classes.dex > "$OUT/classes.dexdump" || true
if "$BUILD_TOOLS/dexdump" -f "$OUT/classes.dexdump" 2>/dev/null | grep -q 'Class descriptor.*vendor/autochips'; then
    echo "ERROR: vendor stub classes were dexed into the apk!" >&2
    exit 1
fi
"$BUILD_TOOLS/dexdump" -f "$OUT/classes.dexdump" 2>/dev/null | grep 'Class descriptor' \
    | sed 's/^ *//' | head -20

echo
ls -l "$ROOT/CarPlayBridge.apk"
echo "BUILD OK: $ROOT/CarPlayBridge.apk"
