# Procedencia de las tablas de bigramas y del diccionario alemán

Generados por `tools/build_bigrams.py` (bigramas) y `tools/build_dictionaries.py` (listas de palabras).
Todas las fuentes se descargan de `raw.githubusercontent.com`; la licencia completa de cada una está en el
archivo indicado de esta carpeta. Las tablas resultantes (`app/src/main/res/raw/bigrams_es.txt`, `bigrams_en.txt`,
`bigrams_de.txt`) son obras derivadas: mezclan texto CC BY-SA, así que se distribuyen bajo **CC BY-SA 4.0** con la
atribución de abajo. `words_de.txt` procede solo de FrequencyWords (MIT).

| Archivo descargado | Idioma | Fuente | Licencia | URL |
|---|---|---|---|---|
| `cv_es_sentence-collector.txt` | es | Mozilla Common Voice, frases enviadas por la comunidad | CC0 1.0 (`LICENSE-CommonVoice.md`) | https://raw.githubusercontent.com/common-voice/common-voice/main/server/data/es/sentence-collector.txt |
| `cv_es_wiki.txt` | es | Mozilla Common Voice, frases extraídas de Wikipedia en español | CC BY-SA 3.0 (texto de Wikipedia; `LICENSE-CommonVoice.md`) | https://raw.githubusercontent.com/common-voice/common-voice/main/server/data/es/wiki.es.txt |
| `cv_en_sentence-collector.txt` | en | Mozilla Common Voice, frases enviadas por la comunidad | CC0 1.0 | https://raw.githubusercontent.com/common-voice/common-voice/main/server/data/en/sentence-collector.txt |
| `cv_en_wiki.txt`, `cv_en_wiki-rerun.txt` | en | Mozilla Common Voice, frases de Wikipedia en inglés | CC BY-SA 3.0 | https://raw.githubusercontent.com/common-voice/common-voice/main/server/data/en/wiki.en.txt , .../en/wiki.en.rerun-2021-11.txt |
| `cv_de_sentence-collector.txt` | de | Mozilla Common Voice, frases enviadas por la comunidad | CC0 1.0 | https://raw.githubusercontent.com/common-voice/common-voice/main/server/data/de/sentence-collector.txt |
| `cv_de_wiki.txt` | de | Mozilla Common Voice, frases de Wikipedia en alemán | CC BY-SA 3.0 | https://raw.githubusercontent.com/common-voice/common-voice/main/server/data/de/wiki.de.txt |
| `ud_es_gsd-{train,dev,test}.conllu` | es | Universal Dependencies, UD_Spanish-GSD (texto web) | CC BY-SA 4.0 (`LICENSE-UniversalDependencies.md`) | https://raw.githubusercontent.com/UniversalDependencies/UD_Spanish-GSD/master/es_gsd-ud-train.conllu (+ -dev, -test) |
| `ud_es_ancora-{train,dev,test}.conllu` | es | Universal Dependencies, UD_Spanish-AnCora (prensa) | CC BY 4.0 | https://raw.githubusercontent.com/UniversalDependencies/UD_Spanish-AnCora/master/es_ancora-ud-train.conllu (+ -dev, -test) |
| `ud_en_ewt-{train,dev,test}.conllu` | en | Universal Dependencies, UD_English-EWT (correos, reseñas, blogs) | CC BY-SA 4.0 | https://raw.githubusercontent.com/UniversalDependencies/UD_English-EWT/master/en_ewt-ud-train.conllu (+ -dev, -test) |
| `ud_de_gsd-{train,dev,test}.conllu` | de | Universal Dependencies, UD_German-GSD (texto web) | CC BY-SA 4.0 | https://raw.githubusercontent.com/UniversalDependencies/UD_German-GSD/master/de_gsd-ud-train.conllu (+ -dev, -test) |
| `europarl_raw.zip` (carpetas `spanish`, `english`, `german`) | es, en, de | Muestra de Europarl (actas del Parlamento Europeo) distribuida con nltk_data | Sin restricciones de copyright conocidas (`LICENSE-Europarl.md`) | https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/corpora/europarl_raw.zip |
| `squad_es.json` (solo las preguntas) | es | SQuAD-es v1.1 (TranslateAlignRetrieve) | MIT; el SQuAD original es CC BY-SA 4.0 (`LICENSE-SQuAD-es.md`) | https://raw.githubusercontent.com/ccasimiro88/TranslateAlignRetrieve/master/SQuAD-es-v1.1/train-v1.1-es.json |
| `symspell_bigrams_en.txt` | en | symspellpy `frequency_bigramdictionary_en_243_342.txt` (Google Books Ngram × SCOWL) | MIT; datos de Google Books Ngram CC BY 3.0 (`LICENSE-symspellpy.md`) | https://raw.githubusercontent.com/mammothb/symspellpy/master/symspellpy/frequency_bigramdictionary_en_243_342.txt |
| `orgtre_2grams_{spanish,english,german}.csv` | es, en, de | orgtre/google-books-ngram-frequency (Google Books Ngram v3, 2010-2019) | CC BY 3.0 (`LICENSE-GoogleBooksNgram.md`) | https://raw.githubusercontent.com/orgtre/google-books-ngram-frequency/main/ngrams/2grams_spanish.csv (+ _english, _german) |
| `de_50k.txt` | de | hermitdave/FrequencyWords (subtítulos de OpenSubtitles 2018) → `words_de.txt` | MIT (`LICENSE-FrequencyWords.md`) | https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_50k.txt |

## Fuentes revisadas y descartadas
- `cess_esp` (nltk_data): licencia ambigua; el mismo texto está limpio en UD_Spanish-AnCora.
- `conll2002` (nltk_data): solo investigación, prohíbe el uso comercial.
- `brown`, `nps_chat`, `twitter_samples`, `reuters`, `webtext` (nltk_data): no comerciales, condiciones de Twitter o sin licencia.

## Atribución
Contiene frases de Mozilla Common Voice (CC0 y extractos de Wikipedia, CC BY-SA 3.0, https://www.wikipedia.org),
de los treebanks Universal Dependencies UD_Spanish-GSD, UD_Spanish-AnCora, UD_English-EWT y UD_German-GSD
(CC BY-SA 4.0 / CC BY 4.0, https://universaldependencies.org), de la muestra de Europarl de NLTK (Philipp Koehn,
Universidad de Edimburgo), de las preguntas de SQuAD-es (MIT, Casimiro Pio Carrino et al.), de los recuentos de
Google Books Ngram (CC BY 3.0) a través de symspellpy (MIT) y orgtre/google-books-ngram-frequency (CC BY 3.0),
y de las listas de frecuencia de FrequencyWords (MIT, Hermit Dave).
