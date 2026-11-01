#!/usr/bin/env bash
# Compila el APK sin Android Studio usando las herramientas de Ubuntu:
#   sudo apt install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23 openjdk-17-jdk
set -euo pipefail
cd "$(dirname "$0")"
RES_JAR=${RES_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}   # recursos (aapt)
SDK_JAR=${SDK_JAR:-$RES_JAR}   # javac: necesita un android.jar de API 28 o superior
PKG=com.aurebesh.teclado
VERSION_CODE=${VERSION_CODE:-18}
VERSION_NAME=${VERSION_NAME:-$(sed -n 's|.*<string name="version"[^>]*>\([^<]*\)</string>.*|\1|p' app/src/main/res/values/strings.xml)}
KS_PASS=${KEYSTORE_PASS:-aurebesh}   # cambia esto (o exporta KEYSTORE_PASS) antes de publicar
M=app/src/main
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# El manifiesto fuente no lleva 'package' (estilo Gradle); se inserta aqui.
sed "s|<manifest |<manifest package=\"$PKG\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\" |" \
  "$M/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

aapt package -f -m -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S "$M/res" -I "$RES_JAR" \
  --min-sdk-version 26 --target-sdk-version 34 -F "$OUT/base.apk"

javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options -classpath "$SDK_JAR" \
  -d "$OUT/classes" $(find "$M/java" "$OUT/gen" -name '*.java')

dalvik-exchange --dex --no-locals --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes"
(cd "$OUT/dex" && zip -q ../base.apk classes.dex)

zipalign -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

KS=${KEYSTORE:-aurebesh.keystore}
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias aurebesh \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Teclado Aurebesh" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out TecladoAurebesh.apk "$OUT/aligned.apk"
apksigner verify TecladoAurebesh.apk
echo "Listo: $(pwd)/TecladoAurebesh.apk"
