#!/bin/sh
# Builds CarPlay.apk with plain SDK tools (no Gradle). Needs ANDROID_HOME with build-tools + a platform.
# stubs/ is compile-only: the real com.autochips.carplay classes are on the head unit's boot classpath.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:?set ANDROID_HOME}
BT=$(ls -d "$SDK"/build-tools/* | tail -1)
JAR=$(ls -d "$SDK"/platforms/*/android.jar | tail -1)

rm -rf build && mkdir -p build/stubs build/classes
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -d build/stubs $(find stubs -name '*.java')
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -cp build/stubs -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 27 --lib "$JAR" --output build $(find build/classes -name '*.class')
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml
(cd build && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out CarPlay.apk build/aligned.apk
echo "built $(pwd)/CarPlay.apk"
