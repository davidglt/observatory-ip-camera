#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
umask 077
PLATFORM="${ANDROID_PLATFORM_JAR:-$HOME/.local/share/android-sdk/platforms/android-33/android.jar}"
for tool in javac jar keytool aapt2 d8 zip zipalign apksigner; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done
[[ -s "$PLATFORM" ]] || { echo "Missing API 33 platform JAR: $PLATFORM" >&2; exit 1; }
jar tf "$PLATFORM" >/dev/null
IDENTITY="$HOME/.local/share/observatory-ip-camera/signing"
KEY="$IDENTITY/identity.p12"
PASS="$IDENTITY/password"
mkdir -p "$IDENTITY"
chmod 700 "$IDENTITY"
if [[ -e "$KEY" || -e "$PASS" ]]; then
    [[ -s "$KEY" && -s "$PASS" ]] || { echo 'Incomplete signing identity; restore it before building.' >&2; exit 1; }
else
    TMP=$(mktemp -d "$IDENTITY/.new.XXXXXX")
    trap 'rm -rf "$TMP"' EXIT
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$TMP/password"
    keytool -genkeypair -keystore "$TMP/identity.p12" -storetype PKCS12 -storepass:file "$TMP/password" -alias app -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Observatory IP Camera'
    mv "$TMP/identity.p12" "$KEY"
    mv "$TMP/password" "$PASS"
fi
keytool -list -keystore "$KEY" -storepass:file "$PASS" -alias app >/dev/null
rm -rf build
mkdir -p build/classes build/dex dist
rm -f dist/observatory-ip-camera.apk
find src -name '*.java' -print > build/sources.txt
javac -encoding UTF-8 --release 8 -classpath "$PLATFORM" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
d8 --lib "$PLATFORM" --min-api 30 --output build/dex build/classes.jar
aapt2 link -o build/base.apk -I "$PLATFORM" --manifest AndroidManifest.xml
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KEY" --ks-key-alias app --ks-pass "file:$PASS" --out dist/observatory-ip-camera.apk build/aligned.apk
zipalign -c 4 dist/observatory-ip-camera.apk
apksigner verify --verbose dist/observatory-ip-camera.apk
printf 'Built: dist/observatory-ip-camera.apk\n'
