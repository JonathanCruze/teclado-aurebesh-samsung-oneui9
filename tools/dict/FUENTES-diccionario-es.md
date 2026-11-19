# Procedencia de la lista de palabras del español (`words_es.txt`)

Generada por `tools/build_dictionaries.py`, que para el español llama a `tools/build_dict_es.py`. Las listas del
inglés y del alemán (`words_en.txt`, `words_de.txt`) no cambian: siguen siendo las 40 000 formas más frecuentes de
FrequencyWords.

| Archivo | Para qué | Fuente | Licencia | URL |
|---|---|---|---|---|
| `es_50k.txt` | Las 40 000 formas de siempre, que encabezan la lista en su mismo orden | hermitdave/FrequencyWords (subtítulos de OpenSubtitles 2018) | MIT (`LICENSE-FrequencyWords.md`) | https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_50k.txt |
| `es_full.txt` | Recuento de cada forma: qué entra y en qué orden | hermitdave/FrequencyWords | MIT | https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_full.txt |
| `es_MX.dic`, `es_MX.aff`, `es_ES.dic`, `es_ES.aff` | Qué formas son válidas; paradigmas de los verbos; entradas propias de México | RLA-ES 2.9 (Santiago Bosio y colaboradores), copia del repositorio de diccionarios de LibreOffice, etiqueta `libreoffice-25.8.1.1` | GPL 3+ / LGPL 3+ / MPL 1.1+, a elegir (`LICENSE-RLA-ES.md`) | https://raw.githubusercontent.com/LibreOffice/dictionaries/libreoffice-25.8.1.1/es/es_MX.dic (y los otros tres) |
| `tools/dict/es_extra.dic` | Mexicanismos, coloquialismos de chat y palabras corrientes que RLA-ES no trae | Escrita a mano para este teclado | La del repositorio | — |
| `tools/dict/es_verbos.txt` | Verbos de uso diario en México a los que se rellena el paradigma completo | Escrita a mano para este teclado | La del repositorio | — |
| `tools/dict/es_excluir.txt`, `tools/dict/es_quitar.txt` | Formas válidas que casi siempre son una errata y no deben entrar; erratas que se quitan de la lista de siempre | Escritas a mano para este teclado (la segunda, con las erratas del núcleo que señaló la revisión) | La del repositorio | — |

Solo para medir (no entra nada de ellos en la lista): los treebanks UD_Spanish-AnCora y UD_Spanish-GSD de Universal
Dependencies (`LICENSE-UniversalDependencies.md`), con `tools/dict_cobertura.py`.

## Qué entra

1. Las 40 000 formas de siempre, en su orden, menos sus erratas: por regla, la forma sin tilde que RLA-ES rechaza y
   cuya forma con tilde es tres veces más frecuente («tambien», «podria», «corazon»: 515); y, una a una, las de
   `es_quitar.txt` (con una tilde que no llevan, faltas de ortografía, palabras juntas y restos de los créditos de
   los subtítulos: 148). Lo que queda es el **núcleo**: en `words_es.txt` una línea en blanco lo separa de las formas
   nuevas, y de ahí saca `Dictionary.java` su tamaño.
2. Las formas de los subtítulos que RLA-ES acepta, a partir de un recuento mínimo (más bajo si son flexiones de una
   palabra frecuente o entradas propias de es_MX; más alto si solo las acepta es_ES, que son casi todas de «vosotros»).
3. Tres familias que RLA-ES no trae completas, validadas por regla y por uso: verbo con pronombres enclíticos bien
   acentuado, diminutivos, golpes en -azo, superlativos y adverbios en -mente de una palabra válida, y compuestos con
   prefijo.
4. El paradigma completo (formas simples; sin «vosotros», voseo, futuro de subjuntivo ni imperfecto de subjuntivo
   en -se, que solo entran si los subtítulos los usan) de los mil verbos más usados, de los de `es_extra.dic` y de los
   de `es_verbos.txt`, aunque no aparezca en los subtítulos; y las formas básicas de los mil verbos siguientes. Esas
   formas van al final de la lista.
5. La pareja sin tilde de toda forma verbal que solo se distingue de otro tiempo por la tilde final («agendo» y
   «agendó», «facture» y «facturé», «alcanzara» y «alcanzará»), para que el teclado no cambie el tiempo del verbo.

Y qué no entra, aunque sea una forma válida (la política, con sus umbrales, está en `tools/build_dict_es.py` y en el
comentario de `Dictionary.java`): la forma sin tilde de otra mucho más frecuente, el futuro de subjuntivo en -ar, lo
que está a una falta de ortografía o a un desliz de una palabra muchísimo más usada, los imperativos de «vosotros»,
el voseo, lo que choca con el léxico Star Wars y las flexiones de palabras raras que además son palabras corrientes
del inglés o del alemán.

## Regenerar y medir

```
python3 tools/build_dictionaries.py            # descarga las fuentes; o: ... <carpeta con los archivos> [es]
python3 tools/dict_cobertura.py <carpeta con los .conllu> app/src/main/res/raw/words_es.txt \
        --anterior <lista anterior> --formas /tmp/formas.tsv
cd screenshot-test && mvn -q -o test -Dtest=DiccBench -Ddicc.out=/tmp/medida.txt -Ddicc.formas=/tmp/formas.tsv   # tras ./build.sh
```

Medidas de esta versión frente a la lista anterior (JVM de escritorio, `DiccBench`; cobertura y palabras cambiadas
sobre los verbos y sustantivos de AnCora y GSD; los tiempos varían un 15 % de una ejecución a otra):

| | Lista anterior | Lista ampliada |
|---|---|---|
| Palabras | 40 000 | 134 653 |
| `words_es.txt` | 347 864 bytes | 1 360 714 bytes |
| APK | 1 553 718 bytes | 1 967 414 bytes |
| Verbos cubiertos (tipos / apariciones) | 49,27 % / 86,64 % | 89,46 % / 98,17 % |
| Sustantivos cubiertos (tipos / apariciones) | 55,25 % / 91,35 % | 81,44 % / 97,51 % |
| Verbos bien escritos que el corrector cambiaría por otra palabra (tipos / apariciones) | 4,98 % / 1,62 % | 1,67 % / 0,34 % |
| Sustantivos bien escritos que cambiaría (tipos / apariciones) | 5,66 % / 0,99 % | 3,12 % / 0,44 % |
| Erratas reales corregidas (`erratas_es.tsv`, 567 casos) | 462 | 483 |
| Carga del diccionario (mediana de 20) | 55 ms | 126 ms |
| Memoria del diccionario | 4,3 MB | 9,5 MB |
| `suggest()`, 2000 entradas (media / p95) | 984 µs / 2984 µs | 574 µs / 1607 µs |
| Deslizado: palabras indexadas, reconocimiento (media / p95), acierto | 39 995, 131 µs / 304 µs, 293 de 300 | 39 995, 138 µs / 316 µs, 293 de 300 |

De las 40 000 formas anteriores siguen 39 989 (99,97 %), en el mismo orden (correlación de rangos 1,000000).
