#!/usr/bin/env bash
# Builds BlurEnabler.apk with plain SDK tools (no Gradle).
set -euo pipefail

cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/37.0.0
ANDROID_JAR=$SDK/platforms/android-36.1/android.jar
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
OUT=out
XPOSED_API=libs/api-82.jar

# Compile-only Xposed API; LSPosed/Vector provide the implementation at runtime.
if [ ! -f "$XPOSED_API" ]; then
    mkdir -p libs
    curl -fsSL -o "$XPOSED_API" https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

javac -source 17 -target 17 -Xlint:-options -encoding UTF-8 \
    -cp "$ANDROID_JAR:$XPOSED_API" -d "$OUT/classes" \
    $(find src -name '*.java')
"$BT/d8" --release --min-api 36 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
    -o "$OUT/unsigned.apk" "$OUT/res.zip"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/BlurEnabler.apk" "$OUT/aligned.apk" 2>/dev/null
echo "$OUT/BlurEnabler.apk"
