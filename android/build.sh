#!/usr/bin/env bash
# Build APK di Brave Origin per Android senza Gradle: aapt2 + javac + d8 + apksigner.
# Richiede JDK 17 e Android SDK (build-tools 34.0.0, platforms;android-34).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
KS="$ROOT/keystore/brave-origin.jks"

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/34.0.0}"
PLATFORM_JAR="${PLATFORM_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}"
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(ls -d "$HOME"/tools/jdk-17* 2>/dev/null | head -1 || true)"
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

VERSION_CODE=1
VERSION_NAME=1.0.0
KS_PASS="${BO_KEYSTORE_PASS:-braveorigin}"
KEY_ALIAS="${BO_KEY_ALIAS:-braveorigin}"
APK_NAME="BraveOrigin-$VERSION_NAME-universal.apk"

for tool in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner"; do
    [ -x "$tool" ] || { echo "Strumento mancante: $tool (imposta ANDROID_HOME)" >&2; exit 1; }
done
[ -f "$PLATFORM_JAR" ] || { echo "android.jar mancante: $PLATFORM_JAR" >&2; exit 1; }
command -v javac >/dev/null || { echo "javac non trovato (JDK 17 richiesto)" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> compilazione risorse"
"$BUILD_TOOLS/aapt2" compile --dir "$APP/res" -o "$OUT/res/resources.zip"

echo "==> link risorse + manifest"
"$BUILD_TOOLS/aapt2" link \
    -o "$OUT/app-unaligned.apk" \
    -I "$PLATFORM_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    -A "$APP/assets" \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --java "$OUT/gen" \
    "$OUT/res/resources.zip"

echo "==> compilazione Java"
find "$OUT/gen" "$APP/java" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -nowarn \
    -bootclasspath "$PLATFORM_JAR" -classpath "$PLATFORM_JAR" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BUILD_TOOLS/d8" --lib "$PLATFORM_JAR" --min-api 24 --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> packaging"
(cd "$OUT" && zip -q -j app-unaligned.apk dex/classes.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/app-unaligned.apk" "$OUT/app-aligned.apk"

if [ ! -f "$KS" ]; then
    echo "==> creazione keystore di firma"
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 \
        -validity 10950 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=Brave Origin Android, O=TixiGX, C=IT"
fi

echo "==> firma"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KS" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$ROOT/$APK_NAME" "$OUT/app-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --print-certs "$ROOT/$APK_NAME"
echo "==> pronto: $ROOT/$APK_NAME"
