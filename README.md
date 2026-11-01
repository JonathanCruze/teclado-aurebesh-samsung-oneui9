# Teclado Aurebesh para Samsung One UI 9.0

> **Compatibilidad.** Este teclado está optimizado y probado en **Samsung Galaxy con One UI 9.0 (Android 17)**.
> Cualquier otra versión de Android, u otro fabricante, es **experimental y está sujeta a fallos**: la app lo
> detecta (lee `Build.VERSION.SEM_PLATFORM_INT`, el número de versión de One UI) y, si el teléfono no es un
> Samsung con One UI 9, muestra un aviso de "funcionamiento experimental" y el dispositivo detectado en el pie.
>
> Repositorio: `JonathanCruze/teclado-aurebesh-samsung-oneui9`.

Teclado (IME) que muestra el alfabeto Aurebesh de Star Wars. Las teclas llevan los glifos de la fuente
Aurebesh de SilvinoR (OFL) y el texto se envía con letras latinas normales, así cualquiera puede leer lo que
escribes.

- Sin permisos, sin internet, sin dependencias: ~500 KB (los diccionarios de autocorrección ocupan casi todo).
- Android 8.0 o superior (`minSdk 26`), icono adaptativo.
- Cinco temas de teclado: Estelar, Noche, Datapad, Imperial y Arena. La app sigue el modo claro/noche del sistema.
- Versión 1.7.

## Instalar en el teléfono
1. Copia `TecladoAurebesh.apk` al teléfono y ábrelo (permite "Instalar apps desconocidas" para la app desde la que lo abres).
2. Abre **Teclado Aurebesh** y sigue los dos pasos: activar en Ajustes y elegir como teclado actual.
3. Para volver al teclado de Samsung, mantén pulsada la barra espaciadora y elige Samsung Keyboard.

## Funciones del teclado
- **Barra de herramientas** sobre las teclas, como la de Samsung Keyboard: autocorrector a la izquierda; a la
  derecha 😊 emojis y 📋 portapapeles (opción "Barra de herramientas", activada por defecto). Mientras se escribe una
  palabra, la parte izquierda muestra las sugerencias; la barra de traducción (opcional) se dibuja en ese mismo espacio.
- **😊 Emojis**: panel con la clasificación oficial de Unicode: pestañas por grupo (recientes, caritas, personas,
  naturaleza, comida, viajes, actividades, objetos, símbolos, banderas) y, dentro de cada grupo, una sección por
  subgrupo en el orden canónico de `emoji-test.txt` (caras sonrientes, caras con cariño…, manos, profesiones,
  mamíferos, aves…). Se desplaza de forma continua y la pestaña sigue al desplazamiento; se queda abierto al elegir
  y ABC vuelve a las letras. Los datos están en `app/src/main/res/raw/emoji.txt`, generado por
  `tools/build_emoji.py` a partir de los datos de emojis de Unicode (Emoji 16.0, sin tonos de piel).
- **📋 Portapapeles**: historial de lo copiado (hasta 20 textos), guardado solo en el teléfono (preferencias privadas
  de la app), con "Borrar todo" y borrado por elemento; tocar un elemento lo pega.
- **Autocorrección y sugerencias sin internet**, con diccionarios integrados de español e inglés (ver abajo).
- **Modo a una mano** (izquierda o derecha, 78 % del ancho): opción "Modo a una mano".
- **Globo**: solo aparece cuando hay dos idiomas Aurebesh activos y alterna español/inglés sin salir del teclado.
  Mantener el espacio abre el selector de teclados del sistema (para volver a Samsung Keyboard).
- Los ajustes se aplican al teclado **en tiempo real**: el servicio escucha los cambios de preferencias, no hace
  falta cerrar y abrir el teclado.
- QWERTY con ñ y fila de numerales Aurebesh (desactivable; sin ella, q…p dan 1…0 en pulsación larga, con el
  número insinuado en la esquina). Pulsación larga = **menú de alternativas** deslizable, como en Gboard:
  vocales → á à ä â ã…, n → ñ, c → ç, `,`/`.` → `¿`/`?`; en `?123`, `?` y `!` dan `¿` y `¡` y los signos
  tienen alternativas (`_ « » [ ] \ ~ € |`).
- Mayúscula automática al inicio de frase; doble toque en ⇧ = bloqueo.
- `?123`: signos con glifo propio, dígrafos Aurebesh (ch, ae, eo, kh, ng, oo, sh, th) y símbolos latinos.
- Burbuja al pulsar con el glifo grande y la letra latina (para aprender el alfabeto).
- Opciones: tema, tamaño del teclado (4 niveles), modo a una mano, barra de herramientas, letra latina en las teclas
  (con las alternativas de pulsación larga), barra de traducción, dígrafos, fila de números, autocorrección, sonido y
  vibración.
- `¿` y `¡` tienen glifo propio: el de `?` y `!` girado 180°, como en la tipografía española.
- `+ = # * % @ &` y el resto de signos vienen de la fuente base; en la página `?123` cada signo lleva su leyenda latina en la esquina.
- La app incluye campos reales (texto y contraseña) que usan el teclado elegido en Ajustes.
- Enter adapta su icono a la acción del campo (buscar, enviar, siguiente, listo).
- Campos numéricos y de teléfono abren en `?123`; en contraseñas no hay burbuja, barra ni sugerencias.
- En apaisado las filas son más bajas y nunca se usa el editor a pantalla completa.

## Autocorrección
Los teléfonos Samsung suelen venir **sin ningún servicio de corrector ortográfico del sistema** (Samsung Keyboard
no lo registra y Gboard no siempre está instalado), así que el marco de corrección de Android
(`TextServicesManager`) no sirve: desarrolladores de otros teclados llevan años reportando que su corrector
"funciona en todas partes menos en Samsung". Por eso el teclado lleva sus propios diccionarios:

- `app/src/main/res/raw/words_es.txt` y `words_en.txt`: 40 000 palabras por idioma, de más a menos frecuente,
  generadas por `tools/build_dictionaries.py` a partir de las listas de frecuencia de
  [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) (MIT,
  `tools/dict/LICENSE-FrequencyWords.md`), calculadas sobre los subtítulos de OpenSubtitles 2018.
- Palabra desconocida → mejor candidato a **una edición** (una letra de más, de menos, cambiada por una vecina en el
  teclado, o dos letras invertidas), ponderado por frecuencia.
- **Restauración de tildes** cuando la forma acentuada es mucho más frecuente: "mas" → "más", "cancion" → "canción",
  "tambien" → "también". Las parejas ambiguas ("esta/está", "si/sí", "el/él") se dejan como están.
- **Completado de palabras** en la barra; en inglés, "i" → "I" y contracciones ("dont" → "don't").
- La corrección se aplica al teclear espacio o puntuación. **Retroceso justo después la deshace.** El primer chip de
  la barra (la palabra escrita, entre comillas) la conserva tal cual.
- Nunca corrige en contraseñas, correos, URL ni campos marcados sin sugerencias.

## Galaxy AI ("Inteligencia avanzada") y otras funciones de Samsung Keyboard
Galaxy AI (Asistente de escritura, Asistente de chat, traducción de chat) vive dentro de Samsung Keyboard y
**Samsung no ofrece una API para otros teclados**. Desde One UI 7, Samsung muestra el Asistente de escritura en el
menú de selección de texto de cualquier app
([SamMobile](https://www.sammobile.com/news/one-ui-7-0-galaxy-ai-writing-tools-any-keyboard/),
[Samsung](https://www.samsung.com/us/support/answer/ANS10000943/)), y eso sigue funcionando mientras se usa este
teclado: seleccionar texto → icono de Galaxy AI en el menú de selección. Dentro del teclado no hay botón de Galaxy AI,
porque no existe forma de integrarlo.

| Función | Samsung Keyboard | Teclado Aurebesh 1.7 |
|---|---|---|
| Galaxy AI / Inteligencia avanzada | Sí | No (exclusivo de Samsung Keyboard); el menú de selección de texto sigue ofreciendo el Asistente de escritura |
| Portapapeles con historial | Sí | Sí (📋, hasta 20 textos, borrable) |
| Modo a una mano | Sí | Sí |
| Teclado flotante | Sí | No en 1.7 |
| Autocorrección y predicción | Sí | Sí, con diccionarios propios (sin internet) |
| Emojis | Sí | Sí (panel por categorías con recientes) |
| Dictado por voz | Sí | No |
| Cambio de idioma | Sí | Sí (globo con dos idiomas activos) |

## Estructura
```
app/src/main/java/com/aurebesh/teclado/
  GlyphData.java           glifos vectorizados con métricas de fuente (generado por tools/build_font_set.py)
  Glyphs.java              dibujo de glifos y conversión texto → glifos
  AurebeshKeyboardView.java vista del teclado: distribución, barra de herramientas, dibujo, toque
  Themes.java              los cinco temas (cada uno cambia relleno, borde, esquina, sombra, halo)
  ThemeSwatchView.java     muestra de tema para el selector de la app
  AurebeshIME.java         servicio de teclado
  Dictionary.java          diccionario y autocorrección
  ClipHistory.java         historial del portapapeles
  KeyboardTray.java        bandeja sobre las teclas: historial del portapapeles
  EmojiTray.java           panel de emojis por categorías, con recientes
  DeviceInfo.java          detección de Samsung / One UI
  AurebeshTextView.java    texto renderizado en Aurebesh
  MainActivity.java        pantalla de configuración y prueba
  Prefs.java               preferencias
app/src/main/res/          strings, colores (claro/noche), tema, icono, method.xml
app/src/main/res/raw/      words_es.txt y words_en.txt (diccionarios) y emoji.txt (emojis por categoría)
screenshot-test/           prueba Robolectric: renderiza el teclado y la app a PNG, comprueba que
                           teclear "hola" produce "hola" y prueba el diccionario
build.sh                   compilación sin Android Studio
tools/build_dictionaries.py genera los diccionarios a partir de FrequencyWords
tools/build_emoji.py       genera emoji.txt a partir de los datos de emojis de Unicode
tools/dict/                licencia de las listas de palabras
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

Para regenerar los diccionarios: `python3 tools/build_dictionaries.py` (descarga las listas) o
`python3 tools/build_dictionaries.py carpeta/` con `es_50k.txt` y `en_50k.txt` ya descargados.

## Idiomas
El teclado declara dos subtipos, **español** e **inglés**. Con los dos activos aparece el globo, que alterna entre
ellos sin salir del teclado, y la barra espaciadora muestra el idioma actual. En español hay tecla ñ y mantener `,`
da `¿`; en inglés la fila tiene nueve teclas y mantener `,` da `!`. La autocorrección usa el diccionario del idioma
activo. La app está en español e inglés y sigue el idioma del teléfono.

## Fuente
Los glifos salen de la fuente **Aurebesh de SilvinoR** (`font/base-silvinor-Aurebesh.ttf`, SIL Open Font
License 1.1, `font/OFL-silvinor.md`): letras, numerales, puntuación, signos y los ocho dígrafos como
ligaduras. A ese conjunto se añaden la ñ con cuadrado y las vocales con tilde. `tools/build_font_set.py`
genera a partir de ello `GlyphData.java` (vectores con avance y margen de cada glifo, que es lo que dibuja
el teclado) y **`font/AurekTeclado.ttf`**, una fuente OpenType instalable con las mismas ligaduras, también
bajo OFL (con nombre propio, como exige la licencia por el nombre reservado "Aurebesh").

## Español: ñ y tildes
El Aurebesh canónico no tiene ñ ni acentos, y tampoco usa diacríticos: amplía el alfabeto con
letras compuestas a partir de sus propias piezas (barras, chevrones, cuadrados). Siguiendo ese
criterio, la **ñ es *nern* con un cuadrado sobre el vértice** (el cuadrado del dos puntos y de *shen*)
y las vocales con tilde llevan una **barra corta a 45°** (el trazo de *senth* y *resh*), al grosor de la
fuente. Son diseño propio, no canon; otros diacríticos (ü, ç…) se muestran con el glifo base. El texto
enviado siempre lleva los caracteres correctos.

## Temas
| Tema | Construcción |
|---|---|
| Estelar | acero mate, teclas blancas con sombra, tinta azul noche como único acento |
| Noche | oscuro neutro, cian apagado solo en Enter y mayúsculas |
| Datapad | fondo casi negro, teclas solo trazadas, glifos azul luminoso con halo, esquinas de 3 dp |
| Imperial | grafito, glifos blancos, esquinas duras, un solo rojo apagado |
| Arena | tonos de arena, teclas hueso muy redondeadas, naranja de atardecer |

Añadir un tema es añadir una línea en `Themes.java`; el selector, la copia del teclado, la bandeja y las capturas lo recogen solos.

## Privacidad
La app no pide permisos ni usa internet y no guarda nada de lo que escribes. Lo único que conserva es el
historial del portapapeles (los textos que copias, hasta 20), en las preferencias privadas de la app, en el
teléfono; se borra desde la propia bandeja 📋, por elemento o con "Borrar todo".

## Créditos y licencias
- **Fuente Aurebesh** © 2022 SilvinoR, [github.com/silvinor/font-aurebesh](https://github.com/silvinor/font-aurebesh),
  SIL Open Font License 1.1 (`font/OFL-silvinor.md`). La fuente derivada **Aurek Teclado** (`font/AurekTeclado.ttf`)
  se distribuye también bajo OFL.
- **Listas de palabras** de [FrequencyWords](https://github.com/hermitdave/FrequencyWords) © 2016 Hermit Dave,
  licencia MIT (`tools/dict/LICENSE-FrequencyWords.md`), construidas sobre los subtítulos de OpenSubtitles (corpus OPUS).
- *Star Wars* y *Aurebesh* son marcas de Lucasfilm Ltd. / Disney. Este es un proyecto de aficionado, sin afiliación
  ni respaldo de Lucasfilm, Disney ni Samsung.

## Limitaciones conocidas
- Sin soporte de TalkBack en el teclado (la vista es un `Canvas` sin nodos de accesibilidad).
- Sin teclado flotante; sin dictado por voz.
- Galaxy AI no está integrado en el teclado porque Samsung no lo ofrece a terceros; sigue disponible en el menú de
  selección de texto de Android.
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
Genera un teclado, una pantalla `?123`, una barra y una burbuja por tema, la barra de sugerencias
(`sugerencias.png`), el modo a una mano (`una_mano.png`), la bandeja del portapapeles
(`portapapeles_estelar.png`, `portapapeles_noche.png`), el panel de emojis (`emojis_estelar.png`,
`emojis_noche.png`), la app en
claro y noche, el aviso de dispositivo no probado (`app_experimental.png`) y el icono. La prueba también comprueba
que teclear "hola" produce "hola" y que el diccionario corrige "holq" → "hola", "cancion" → "canción" y
"teh" → "the". Después, `python3 promo/make_promo.py` compone las imágenes promocionales.
Usa Robolectric con gráficos nativos. Necesita acceso a Maven Central; `androidx.test:monitor`
y `espresso-idling-resource` deben estar en el repositorio local de Maven (vienen del Maven de
Google o se compilan desde `github.com/android/android-test`).
