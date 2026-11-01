# Teclado Aurebesh para Android

Teclado (IME) que muestra el alfabeto Aurebesh de Star Wars. Las teclas llevan los glifos
Aurebesh vectorizados de la tabla original y el texto se envía con letras latinas normales,
así cualquiera puede leer lo que escribes.

- Sin permisos, sin internet, sin dependencias: ~45 KB.
- Android 8.0 o superior (`minSdk 26`), icono adaptativo.
- Cinco temas de teclado: Estelar, Noche, Datapad, Imperial y Arena. La app sigue el modo claro/noche del sistema.

## Instalar en el teléfono
1. Copia `TecladoAurebesh.apk` al teléfono y ábrelo (permite "Instalar apps desconocidas" para la app desde la que lo abres).
2. Abre **Teclado Aurebesh** y sigue los dos pasos: activar en Ajustes y elegir como teclado actual.
3. Para volver al teclado de Samsung toca el globo (o mantén pulsado el espacio).

## Funciones del teclado
- QWERTY con ñ y fila de numerales Aurebesh (desactivable; sin ella, q…p dan 1…0 en pulsación larga, con el
  número insinuado en la esquina). Pulsación larga = **menú de alternativas** deslizable, como en Gboard:
  vocales → á à ä â ã…, n → ñ, c → ç, `,`/`.` → `¿`/`?`; en `?123`, `?` y `!` dan `¿` y `¡` y los signos
  tienen alternativas (`_ « » [ ] \ ~ € |`).
- Mayúscula automática al inicio de frase; doble toque en ⇧ = bloqueo.
- `?123`: signos con glifo propio, dígrafos Aurebesh (ch, ae, eo, kh, ng, oo, sh, th) y símbolos latinos.
- Burbuja al pulsar con el glifo grande y la letra latina (para aprender el alfabeto).
- Opciones: tema, tamaño del teclado (4 niveles), letra latina en las teclas (con las alternativas de pulsación larga), barra de traducción, dígrafos, fila de números, sonido y vibración.
- `¿` y `¡` tienen glifo propio: el de `?` y `!` girado 180°, como en la tipografía española.
- `+ = # * % @ &` también tienen glifo propio, construido con barras, cuadrados y chevrones del alfabeto (el `&` es un ocho de diamantes con un cubo macizo centrado en la esquina inferior derecha: mitad embebido en el trazo, mitad a la vista, en una sola pieza); en la página `?123` cada signo lleva su leyenda latina en la esquina.
- La app incluye campos reales (texto y contraseña) que usan el teclado elegido en Ajustes.
- Enter adapta su icono a la acción del campo (buscar, enviar, siguiente, listo).
- Campos numéricos y de teléfono abren en `?123`; en contraseñas no hay burbuja ni barra.
- En apaisado las filas son más bajas y nunca se usa el editor a pantalla completa.

## Estructura
```
app/src/main/java/com/aurebesh/teclado/
  GlyphData.java           glifos vectorizados (generado; incluye ñ y vocales con tilde)
  Glyphs.java              dibujo de glifos y conversión texto → glifos
  AurebeshKeyboardView.java vista del teclado: distribución, dibujo, toque
  Themes.java              los cinco temas (cada uno cambia relleno, borde, esquina, sombra, halo)
  ThemeSwatchView.java     muestra de tema para el selector de la app
  AurebeshIME.java         servicio de teclado
  AurebeshTextView.java    texto renderizado en Aurebesh
  MainActivity.java        pantalla de configuración y prueba
  Prefs.java               preferencias
app/src/main/res/          strings, colores (claro/noche), tema, icono, method.xml
screenshot-test/           prueba Robolectric: renderiza el teclado y la app a PNG y
                           comprueba que teclear "hola" produce "hola"
build.sh                   compilación sin Android Studio
promo/make_promo.py        imágenes promocionales (1080×1920 y gráfico 1024×500) a partir
                           de las capturas reales; salida en promo/out/
```

## Compilar
**Android Studio**: abre la carpeta (Gradle) y ejecuta.

**Sin Android Studio (Ubuntu/Debian)**:
```bash
sudo apt install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23 openjdk-17-jdk
SDK_JAR=/ruta/a/android-28-o-superior.jar ./build.sh
```
`RES_JAR` (recursos, por defecto el `android.jar` 23 de Ubuntu) y `SDK_JAR` (compilación Java,
necesita API 28 o superior) se pueden sobrescribir. `KEYSTORE_PASS`, `VERSION_CODE` y
`VERSION_NAME` también. El script genera un keystore propio la primera vez: guárdalo para
poder actualizar la app instalada.

## Idiomas
El teclado declara dos subtipos, **español** e **inglés**. Con los dos activos, el globo alterna entre
ellos y la barra espaciadora muestra el idioma actual. En español hay tecla ñ y mantener `,` da `¿`;
en inglés la fila tiene nueve teclas y mantener `,` da `!`. La app está en español e inglés y sigue el
idioma del teléfono.

## Español: ñ y tildes
El Aurebesh canónico no tiene ñ ni acentos, y tampoco usa diacríticos: amplía el alfabeto con
letras compuestas a partir de sus propias piezas (barras, chevrones, cuadrados). Siguiendo ese
criterio, la **ñ es *nern* con un cuadrado sobre el vértice** (el cuadrado del dos puntos y de *shen*)
y las vocales con tilde llevan una **barra corta a 45°** (el trazo de *senth* y *resh*). Son diseño
propio, no canon; otros diacríticos (ü, ç…) se muestran con el glifo base. El texto enviado siempre
lleva los caracteres correctos.

## Temas
| Tema | Construcción |
|---|---|
| Estelar | acero mate, teclas blancas con sombra, tinta azul noche como único acento |
| Noche | oscuro neutro, cian apagado solo en Enter y mayúsculas |
| Datapad | fondo casi negro, teclas solo trazadas, glifos azul luminoso con halo, esquinas de 3 dp |
| Imperial | grafito, glifos blancos, esquinas duras, un solo rojo apagado |
| Arena | tonos de arena, teclas hueso muy redondeadas, naranja de atardecer |

Añadir un tema es añadir una línea en `Themes.java`; el selector, la copia del teclado y las capturas lo recogen solos.

## Limitaciones conocidas
- Sin soporte de TalkBack en el teclado (la vista es un `Canvas` sin nodos de accesibilidad).
- Sin predicción ni autocorrección; sin emojis ni portapapeles propios.
- `targetSdk 34`: subir a 35 exige probar el modo *edge-to-edge* en un dispositivo.
- El proyecto Gradle no se ha compilado en Android Studio desde este entorno (solo `build.sh`).

## Probar en un teléfono
Con el Samsung conectado por USB (depuración USB activada) y `adb` en el ordenador:
```bash
tools/device.sh install   # instala o actualiza el APK
tools/device.sh enable    # activa el teclado y lo deja como actual
tools/device.sh field     # abre un campo de texto real
tools/device.sh shot      # captura -> screenshots/device/captura.png
```
Desde una sesión de Claude Code en la nube, el teléfono se alcanza activando **Remote Control** en la app
de escritorio de Claude (Mac o Linux) sobre esta carpeta; en una sesión local basta con `adb`.

Sin teléfono a mano, `tools/testlab.sh` ejecuta la app en Galaxy reales de **Firebase Test Lab** (cuota
gratuita diaria) y deja capturas y vídeo en la consola de Firebase. Necesita `FIREBASE_PROJECT` y una
clave de cuenta de servicio (`GOOGLE_APPLICATION_CREDENTIALS` o `GOOGLE_APPLICATION_CREDENTIALS_JSON`).

## Probar (capturas reales sin emulador)
```bash
./build.sh                      # deja los recursos compilados en build/
cd screenshot-test && mvn test  # capturas en ../screenshots/
```
Genera un teclado, una pantalla `?123`, una barra y una burbuja por tema, la app en claro y noche,
y el icono. Después, `python3 promo/make_promo.py` compone las imágenes promocionales.
Usa Robolectric con gráficos nativos. Necesita acceso a Maven Central; `androidx.test:monitor`
y `espresso-idling-resource` deben estar en el repositorio local de Maven (vienen del Maven de
Google o se compilan desde `github.com/android/android-test`).
