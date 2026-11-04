#!/data/data/com.termux/files/usr/bin/bash
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

set -euo pipefail
cd "$(dirname "$0")"
umask 077
PLATFORM="${ANDROID_PLATFORM_JAR:-$HOME/.local/share/android-sdk/platforms/android-33/android.jar}"
for tool in javac jar keytool aapt2 d8 zip zipalign apksigner curl sha256sum; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done
[[ -s "$PLATFORM" ]] || { echo "Missing API 33 platform JAR: $PLATFORM" >&2; exit 1; }
jar tf "$PLATFORM" >/dev/null
MQTT_CACHE="$HOME/.cache/observatory-ip-camera"
mkdir -p "$MQTT_CACHE"
MQTT_ARTIFACTS=(
    "com/hivemq/hivemq-mqtt-client/1.4.2/hivemq-mqtt-client-1.4.2.jar 10ad6fe3735747fb4fe33f9b1d358caf045c40dfc06e652e38e9ed33dfe0b650"
    "io/reactivex/rxjava2/rxjava/2.2.21/rxjava-2.2.21.jar 59df6541a840018f0f4c899aae4f4c1f4383f4c16feb5268615fbe384d28501c"
    "org/reactivestreams/reactive-streams/1.0.4/reactive-streams-1.0.4.jar f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28"
    "org/jetbrains/annotations/26.1.0/annotations-26.1.0.jar ebc7aec252ed0c7d2d04c039d7f00e69f7b86b1f493c741d67b3ef31b986b054"
    "io/netty/netty-buffer/4.1.140.Final/netty-buffer-4.1.140.Final.jar d79b3012f1cce4052cf18a89132a839019807e52c4db21bb5bc34e6e3a5248fc"
    "io/netty/netty-codec/4.1.140.Final/netty-codec-4.1.140.Final.jar 80d2ea6050ec628464c3beea6549a09544e4c19fd7579543e1a658e1caae7e41"
    "io/netty/netty-common/4.1.140.Final/netty-common-4.1.140.Final.jar 298e6c4ac1e01362c8be9db30728b8ab4c93e32fd5d916bbe15170df6321b97c"
    "io/netty/netty-handler/4.1.140.Final/netty-handler-4.1.140.Final.jar ed825f76a081de170ce6224f5a1807846db86236f4943394a7e7a1a379c41bf9"
    "io/netty/netty-transport/4.1.140.Final/netty-transport-4.1.140.Final.jar bfb7a296c1dceaeee706e1330ecf1cb324b32badb68098f70b663717aacfe8b0"
    "io/netty/netty-resolver/4.1.140.Final/netty-resolver-4.1.140.Final.jar 02513f495dcb4400b4b9df700c462b831c7efa3f2d0515c7ede496fb8852cc92"
    "io/netty/netty-transport-native-unix-common/4.1.140.Final/netty-transport-native-unix-common-4.1.140.Final.jar 612226327f45efc42fe9e7864c0115d8a3f7281aea8c554afc1fc40d9a41d554"
    "org/jctools/jctools-core/4.0.7/jctools-core-4.0.7.jar fab373e48733e00bdf7114edbdb49f54114b0237e088b161c05984a6705fed50"
    "com/google/dagger/dagger/2.42/dagger-2.42.jar 7ad8147fdb5dfb481b7cde8c70da73afbaa21430ce757fcd96709fcfb6a4d56b"
    "javax/inject/javax.inject/1/javax.inject-1.jar 91c77044a50c481636c32d916fd89c9118a72195390452c81065080f957de7ff"
)
MQTT_JARS=()
for dependency in "${MQTT_ARTIFACTS[@]}"; do
    artifact="${dependency%% *}"
    checksum="${dependency##* }"
    jar_path="$MQTT_CACHE/${artifact##*/}"
    if [[ ! -s "$jar_path" ]] || [[ "$(sha256sum "$jar_path" | cut -d' ' -f1)" != "$checksum" ]]; then
        temp_jar="$jar_path.tmp"
        curl --fail --location --silent --show-error "https://repo.maven.apache.org/maven2/$artifact" -o "$temp_jar"
        echo "$checksum  $temp_jar" | sha256sum --check --status || { rm -f "$temp_jar"; echo "MQTT dependency checksum mismatch: ${artifact##*/}" >&2; exit 1; }
        mv "$temp_jar" "$jar_path"
    fi
    MQTT_JARS+=("$jar_path")
done
MQTT_CLASSPATH=$(IFS=:; echo "${MQTT_JARS[*]}")
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
mkdir -p build/classes build/dex build/assets dist
cp LICENSE build/assets/LICENSE
rm -f dist/observatory-ip-camera.apk
find src -name '*.java' -print > build/sources.txt
javac -encoding UTF-8 --release 8 -classpath "$PLATFORM:$MQTT_CLASSPATH" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
d8 --lib "$PLATFORM" --min-api 30 --output build/dex build/classes.jar "${MQTT_JARS[@]}"
aapt2 link -o build/base.apk -I "$PLATFORM" -A build/assets --manifest AndroidManifest.xml
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KEY" --ks-key-alias app --ks-pass "file:$PASS" --out dist/observatory-ip-camera.apk build/aligned.apk
zipalign -c 4 dist/observatory-ip-camera.apk
apksigner verify --verbose dist/observatory-ip-camera.apk
printf 'Built: dist/observatory-ip-camera.apk\n'
