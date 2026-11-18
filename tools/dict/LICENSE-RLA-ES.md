# RLA-ES — diccionario Hunspell del español (es_MX y es_ES)

Proyecto: Recursos Lingüísticos Abiertos del Español (RLA-ES), https://github.com/sbosio/rla-es
Autor y coordinador: Santiago Bosio, con las aportaciones de la comunidad que cita su README.
Versión usada: Ortografía 2.9, tal como se distribuye en el repositorio de diccionarios de LibreOffice, etiqueta
`libreoffice-25.8.1.1`, carpeta `es/`:

| Archivo | SHA-256 | URL |
|---|---|---|
| `es_MX.dic` | `44ce35af220962c68f97962776639bef271f7d90a85ba924b539a36e33315f82` | https://raw.githubusercontent.com/LibreOffice/dictionaries/libreoffice-25.8.1.1/es/es_MX.dic |
| `es_MX.aff` | `d966cb748e4a688ed75ec84b50c0835ac438e5325ee4fce703a093794f9cba7e` | https://raw.githubusercontent.com/LibreOffice/dictionaries/libreoffice-25.8.1.1/es/es_MX.aff |
| `es_ES.dic` | `6975dddec3d5d2c676069537bc67b4b5f786c65c5d4cf6703a82acf779ac9ec1` | https://raw.githubusercontent.com/LibreOffice/dictionaries/libreoffice-25.8.1.1/es/es_ES.dic |
| `es_ES.aff` | `e73a9bf8e1383f4986a5dc9e2fbed49371c0c61f511c626d15586bd433c1cad9` | https://raw.githubusercontent.com/LibreOffice/dictionaries/libreoffice-25.8.1.1/es/es_ES.aff |

Los archivos no se guardan en este repositorio: `tools/build_dictionaries.py` los descarga (o los lee de una carpeta).

## Licencia

Triple licencia, a elegir: GNU GPL versión 3 o posterior, GNU LGPL versión 3 o posterior, o MPL versión 1.1 o
posterior. Texto de `LICENSE.md` del proyecto (https://raw.githubusercontent.com/sbosio/rla-es/master/LICENSE.md):

> El proyecto y los diccionarios se distribuyen bajo un triple esquema de licencias disjuntas: GNU GPL versión 3 o
> posterior, GNU LGPL versión 3 o posterior o MPL versión 1.1 o posterior. Puede seleccionar libremente bajo cuál de
> estas licencias realizará el uso.
>
> El detalle de cada licencia está accesible en:
> - https://www.gnu.org/licenses/gpl-3.0.en.html
> - https://www.gnu.org/licenses/lgpl-3.0.en.html
> - https://www.mozilla.org/en-US/MPL/1.1/

Y del `README` que acompaña a los diccionarios (`es/README_hunspell_es.txt` en el repositorio de LibreOffice):

> Este diccionario para corrección ortográfica, integrado por el fichero de afijos y la lista de palabras
> (`__LOCALE__[.aff|.dic]`) se distribuye bajo un triple esquema de licencias disjuntas: GNU GPL versión 3 o
> posterior, GNU LGPL versión 3 o posterior, ó MPL versión 1.1 o posterior. Puede seleccionar libremente bajo cuál de
> estas licencias utilizará este diccionario.

## Cómo se usa aquí

`tools/build_dict_es.py` expande las entradas con sus reglas de afijos para saber qué formas son válidas
(conjugaciones, plurales, femeninos, derivados, verbos con pronombres) y con eso decide qué formas de la lista de
frecuencia de los subtítulos entran en `app/src/main/res/raw/words_es.txt`; de los verbos más usados y de las entradas
propias de es_MX toma además formas que los subtítulos no traen. El teclado no incluye los archivos `.dic` ni `.aff`,
solo esa lista de palabras.

`words_es.txt` es, por tanto, una obra derivada en parte de RLA-ES. Se usa bajo la **MPL 1.1** (una de las tres
licencias que ofrece el proyecto), que afecta al archivo derivado y no al resto de la aplicación: `words_es.txt` se
distribuye bajo esos mismos términos, en texto plano, junto con el programa que lo genera
(`tools/build_dictionaries.py`, `tools/build_dict_es.py`) y esta nota de procedencia. Las demás fuentes de la lista
están en `FUENTES-diccionario-es.md`.
