#!/bin/bash
# Offline (JVM) self-test for the pure-Java iAP2 wire-format classes.
# Validates Iap2Frame checksums/framing and Iap2Tlv layout against the
# USENIX VehicleSec'25 Table 5 capture bytes. Nothing here touches the device.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
OUT="$ROOT/test/build"
rm -rf "$OUT"
mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" --release 8 -nowarn -d "$OUT" \
    "$ROOT/src/com/cpbridge/Iap2Constants.java" \
    "$ROOT/src/com/cpbridge/Iap2Frame.java" \
    "$ROOT/src/com/cpbridge/Iap2Tlv.java" \
    "$ROOT/src/com/cpbridge/CsmReassembler.java" \
    "$ROOT/test/ProtocolSelfTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" com.cpbridge.ProtocolSelfTest
