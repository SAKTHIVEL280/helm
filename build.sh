#!/usr/bin/env bash
# Builds Helm.apk with the Android SDK tools directly: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# No Gradle. Needs JDK 17+ and an Android SDK with platform 36 and build-tools 36.x.
#
#   ANDROID_HOME   Android SDK folder (default: ./.toolchain/sdk)
#   JAVA_HOME      JDK folder         (default: ./.toolchain/jdk17, else java on PATH)
#   BUILD_TOOLS    build-tools version (default: newest installed 36.x)
#   HELM_KEYSTORE  signing keystore   (default: ./keystore/helm.jks, created on first build)
#   HELM_KS_PASS   its password       (default: helmhelm; set your own for anything you share)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$ROOT/.toolchain/sdk}}"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$ROOT/.toolchain/jdk17" ]; then JAVA_HOME="$ROOT/.toolchain/jdk17"; fi
if [ -n "${JAVA_HOME:-}" ]; then export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"; fi
export JAVA_TOOL_OPTIONS="-XX:-UsePerfData"   # no hsperfdata files left in the temp folder

if [ -z "${BUILD_TOOLS:-}" ]; then
    BUILD_TOOLS="$(ls "$SDK/build-tools" 2>/dev/null | grep '^36\.' | sort -V | tail -n 1 || true)"
fi
BT="$SDK/build-tools/$BUILD_TOOLS"
JAR="$SDK/platforms/android-36/android.jar"
OUT="$ROOT/build"
KS="${HELM_KEYSTORE:-$ROOT/keystore/helm.jks}"
PASS="${HELM_KS_PASS:-helmhelm}"

[ -f "$JAR" ] || { echo "Android platform 36 not found under $SDK (set ANDROID_HOME)"; exit 1; }
[ -d "$BT" ] && [ -n "$BUILD_TOOLS" ] || { echo "build-tools 36.x not found under $SDK/build-tools"; exit 1; }
command -v javac > /dev/null || { echo "javac not found (set JAVA_HOME to a JDK 17+)"; exit 1; }

# Windows (Git Bash) tools want Windows paths and .exe names; elsewhere use paths as they are.
if command -v cygpath > /dev/null; then w() { cygpath -m "$1"; }; EXE=".exe"; else w() { echo "$1"; }; EXE=""; fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "> resources"
"$BT/aapt2$EXE" compile --dir "$(w "$ROOT/app/res")" -o "$(w "$OUT/res.zip")"
"$BT/aapt2$EXE" link -o "$(w "$OUT/unsigned.apk")" -I "$(w "$JAR")" \
    --manifest "$(w "$ROOT/app/AndroidManifest.xml")" \
    --min-sdk-version 33 --target-sdk-version 36 \
    --version-code 3 --version-name 3.0 \
    --java "$(w "$OUT/gen")" \
    "$(w "$OUT/res.zip")"

echo "> java"
find "$ROOT/app/src" "$OUT/gen" -name '*.java' | while read -r f; do w "$f"; done > "$OUT/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -Xlint:-options -Xlint:deprecation -Xlint:unchecked \
    -classpath "$(w "$JAR")" -d "$(w "$OUT/classes")" "@$(w "$OUT/sources.txt")"

echo "> dex"
(cd "$OUT/classes" && jar cf ../classes.jar .)
java -cp "$(w "$BT/lib/d8.jar")" com.android.tools.r8.D8 --release --min-api 33 \
    --lib "$(w "$JAR")" --output "$(w "$OUT/dex")" "$(w "$OUT/classes.jar")"

echo "> package"
(cd "$OUT/dex" && "$BT/aapt$EXE" add "$(w "$OUT/unsigned.apk")" classes.dex > /dev/null)
"$BT/zipalign$EXE" -f 4 "$(w "$OUT/unsigned.apk")" "$(w "$OUT/aligned.apk")"

if [ ! -f "$KS" ]; then
    echo "> keystore (new; keep it: Android only installs updates signed with the same key)"
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$(w "$KS")" -storepass "$PASS" -keypass "$PASS" -alias helm \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Helm" > /dev/null 2>&1
fi

echo "> sign"
java -jar "$(w "$BT/lib/apksigner.jar")" sign --ks "$(w "$KS")" --ks-pass "pass:$PASS" \
    --key-pass "pass:$PASS" --out "$(w "$ROOT/Helm.apk")" "$(w "$OUT/aligned.apk")"
java -jar "$(w "$BT/lib/apksigner.jar")" verify "$(w "$ROOT/Helm.apk")"

echo "> done: $ROOT/Helm.apk ($(du -k "$ROOT/Helm.apk" | cut -f1) KB)"
