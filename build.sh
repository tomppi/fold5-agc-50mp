#!/usr/bin/env bash
# Build the LineageOS-ready AGC fork (single-shot 50 MP) from upstream releases.
#
#   ./build.sh [path-to-v6.apk] [path-to-v4.1.apk]
#
# Needs: JDK 17+, Android SDK build-tools (d8, zipalign, apksigner), apktool 2.11+.
# Upstream APKs come from https://github.com/HyperRamzey/fold5-camera2-research/releases
set -euo pipefail

V6_APK=${1:-AGC9.2.14_V6.0.0_fold5.apk}
V41_APK=${2:-AGC9.2.14_V14.0_ruler_fold5fix_v4.1.apk}
APKTOOL=${APKTOOL:-apktool}
BT=${BT:-$ANDROID_HOME/build-tools/36.0.0}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_PASS=${KS_PASS:-android}
KS_ALIAS=${KS_ALIAS:-androiddebugkey}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE/build}

[ -f "$V6_APK" ] || { echo "missing upstream v6.0.0 apk: $V6_APK"; exit 1; }
[ -f "$V41_APK" ] || { echo "missing upstream v4.1 apk: $V41_APK"; exit 1; }
mkdir -p "$OUT"

echo "== 1/6 decompile == v6.0.0"
[ -d "$OUT/v6" ] || "$APKTOOL" d -f -o "$OUT/v6" "$V6_APK"
echo "== 1/6 decompile == v4.1 (source of the 50 MP feature classes)"
[ -d "$OUT/v41" ] || "$APKTOOL" d -f -o "$OUT/v41" "$V41_APK"

echo "== 2/6 apply the port =="
python3 "$HERE/patch_agc.py" --v6 "$OUT/v6" --v41 "$OUT/v41" --here "$HERE"

echo "== 3/6 compile Rea50Shot (single-shot 50 MP) =="
AJ=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)
rm -rf "$OUT/classes" "$OUT/dex" && mkdir -p "$OUT/classes" "$OUT/dex"
javac --release 8 -nowarn -cp "$AJ" -d "$OUT/classes" \
    "$HERE/java/com/agc/Rea50Shot.java" "$HERE/java/stubs/com/agc/LEraw50.java" \
    "$HERE/java/stubs/com/agc/Eraw50IO.java"
"$BT/d8" --release --lib "$AJ" --output "$OUT/dex" "$OUT"/classes/com/agc/Rea50Shot*.class

echo "== 4/6 rebuild the APK =="
"$APKTOOL" b "$OUT/v6" -o "$OUT/agc-rea.apk"
mkdir -p "$OUT/inject" && cp "$OUT/dex/classes.dex" "$OUT/inject/classes4.dex"
cp "$OUT/agc-rea.apk" "$OUT/agc-rea-4dex.apk"
(cd "$OUT/inject" && zip -q -X "$OUT/agc-rea-4dex.apk" classes4.dex)

echo "== 5/6 align and sign =="
"$BT/zipalign" -f -p 4 "$OUT/agc-rea-4dex.apk" "$OUT/agc-rea-aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --out "$OUT/AGC9.2.14_V6.0.0_fold5_rea-debug.apk" "$OUT/agc-rea-aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/AGC9.2.14_V6.0.0_fold5_rea-debug.apk" | head -3

echo "== 6/6 done =="
echo "APK: $OUT/AGC9.2.14_V6.0.0_fold5_rea-debug.apk"
echo "Install on a LineageOS Fold 5 with the cameraserver module active, then:"
echo "  Settings -> Lens Setting -> Main -> 50MP Expert RAW Mode -> On, and press the 50MP button."
