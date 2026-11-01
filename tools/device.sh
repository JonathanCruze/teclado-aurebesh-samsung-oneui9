#!/usr/bin/env bash
# Teléfono conectado por adb (USB o depuración inalámbrica). Uso: tools/device.sh <orden> [args]
#   check            el teléfono está conectado y autorizado
#   install          instala o actualiza el APK
#   enable           activa el teclado Aurebesh y lo deja como teclado actual
#   settings         abre Ajustes > Teclados (para activar el segundo idioma)
#   app              abre la app de configuración
#   field            abre un campo de texto real (nuevo SMS) para ver el teclado del sistema
#   shot [nombre]    captura de pantalla -> screenshots/device/<nombre>.png
#   tap X Y          toca en coordenadas de pantalla
#   text "hola"      escribe texto en el campo con foco
#   key KEYCODE      tecla (p. ej. 4 = atrás, 3 = inicio)
#   log              logcat filtrado por el teclado
#   samsung          vuelve al teclado de Samsung
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=com.aurebesh.teclado
IME=$PKG/.AurebeshIME
case "${1:-}" in
  check)    adb devices -l ;;
  install)  adb install -r TecladoAurebesh.apk ;;
  enable)   adb shell ime enable "$IME" && adb shell ime set "$IME" && echo "Teclado Aurebesh activo" ;;
  settings) adb shell am start -a android.settings.INPUT_METHOD_SETTINGS >/dev/null ;;
  app)      adb shell am start -n "$PKG/.MainActivity" >/dev/null ;;
  field)    adb shell am start -a android.intent.action.SENDTO -d "sms:" >/dev/null ;;
  shot)     mkdir -p screenshots/device; f="screenshots/device/${2:-captura}.png"; adb exec-out screencap -p > "$f"; echo "$f" ;;
  tap)      adb shell input tap "$2" "$3" ;;
  text)     adb shell input text "$(printf '%s' "$2" | sed 's/ /%s/g')" ;;
  key)      adb shell input keyevent "$2" ;;
  log)      adb logcat -v time | grep -iE "aurebesh|AndroidRuntime" ;;
  samsung)  id=$(adb shell ime list -s | tr -d '\r' | grep -iE -m1 'honeyboard|samsung|sec\.android' || true)
            [ -n "$id" ] && adb shell ime set "$id" && echo "Teclado Samsung activo" || echo "No encuentro el teclado de Samsung: adb shell ime list -s" ;;
  *)        sed -n '2,15p' "$0" ;;
esac
