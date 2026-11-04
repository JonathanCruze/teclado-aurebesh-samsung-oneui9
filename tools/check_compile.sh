#!/usr/bin/env bash
# Comprobacion rapida de compilacion sin tocar build/ ni el APK: genera R.java con aapt y compila con javac
# en una carpeta privada. Uso: tools/check_compile.sh <carpeta_salida>   (sale 0 si no hay errores).
# Pensado para trabajar en paralelo sobre el mismo arbol: cada agente usa su propia carpeta.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=${1:?carpeta de salida}
RES_JAR=${RES_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
SDK_JAR=${SDK_JAR:-$HOME/.m2/repository/org/robolectric/android-all/15-robolectric-13954326/android-all-15-robolectric-13954326.jar}
M=app/src/main
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"
sed "s|<manifest |<manifest package=\"com.aurebesh.teclado\" android:versionCode=\"1\" android:versionName=\"dev\" |" \
  "$M/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt package -f -m -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S "$M/res" -I "$RES_JAR" \
  --min-sdk-version 26 --target-sdk-version 34 -F "$OUT/base.apk" || { echo "## aapt: error en recursos"; exit 2; }
javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options -Xmaxerrs 200 -classpath "$SDK_JAR" \
  -d "$OUT/classes" $(find "$M/java" "$OUT/gen" -name '*.java') 2> "$OUT/javac.log"
RC=$?
grep -E "error:" "$OUT/javac.log" | sed 's|^app/src/main/java/com/aurebesh/teclado/||' | sort | uniq -c | sort -rn | head -50
if [ $RC = 0 ]; then echo "## COMPILA"; else echo "## $(grep -c 'error:' "$OUT/javac.log") errores (detalle en $OUT/javac.log)"; fi
exit $RC
