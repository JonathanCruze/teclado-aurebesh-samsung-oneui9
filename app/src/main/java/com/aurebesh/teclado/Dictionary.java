package com.aurebesh.teclado;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Diccionario de frecuencia por idioma (res/raw/words_es.txt, words_en.txt y words_de.txt: una palabra
 * por linea, de mas a menos frecuente). Da autocorreccion a una edicion, restauracion de tildes y completado
 * de palabras. Todo ocurre en el telefono, sin internet.
 * <p>
 * Lleva ademas el lexico Star Wars (res/raw/starwars_words.txt, una entrada por linea en su forma canonica:
 * personajes, especies, planetas, naves y conceptos, con sus variantes en espanol y aleman; los nombres comunes de
 * la saga van en minuscula, como "bantha" o "lightsaber"), compartido por los tres idiomas y activable con
 * {@link #setStarWars}: sus entradas se aceptan como conocidas, se completan y se corrige hacia ellas como si fueran
 * palabras aprendidas; {@link #canonical} devuelve la caja propia de cada una. Las entradas que son ademas palabras
 * normales del idioma ("orden", "fuerza", "ghost") siguen las reglas de su diccionario, y las que en minusculas
 * serian un error evidente de una palabra frecuente ("rael" -> "real", "hoth" -> "both") solo cuentan escritas con
 * su mayuscula, igual que las de menos de cuatro letras.
 * <p>
 * <b>Lista ampliada del espanol.</b> words_es.txt lleva, detras de las formas mas frecuentes de siempre (el nucleo,
 * {@link #coreSize}: una linea en blanco del archivo marca donde acaba), unas cien mil mas: conjugaciones, plurales,
 * femeninos, diminutivos, verbos con pronombre y mexicanismos validados con el diccionario RLA-ES
 * (tools/build_dict_es.py). La politica para que una forma rara pero valida no estropee la correccion de las erratas
 * corrientes se reparte entre la lista y este codigo:
 * <ul>
 * <li>Las formas raras son <i>conocidas y completables</i>: escritas tal cual se respetan y se ofrecen al completar,
 * pero no se corrige hacia ellas a dos ediciones ({@link #scan} solo recorre el nucleo) ni, por su puntuacion
 * (ln del rango), sin preguntar a una edicion: quedan como sugerencia. Asi ni el coste ni el acierto dependen del
 * tamano de la lista. El deslizado reconoce el nucleo y las formas nuevas mas usadas ({@link #glideSize}).</li>
 * <li>La tilde manda, con tope: si lo escrito no es una palabra pero lo seria con sus tildes ("platicabamos",
 * "llegare", "comio"), se le ponen aunque la forma acentuada sea rara. Cede ante una errata corriente (tecla vecina,
 * letras bailadas, letra que falta o sobra, falta de ortografia) de una palabra del nucleo: siempre que la forma con
 * tilde sea de fuera del nucleo ("peto" es "pero" y no "petó"; "duos", "dios" y no "dúos") y, si es del nucleo, solo
 * cuando la otra es muchisimo mas frecuente ({@link #TILDE_MARGIN}: "comi" es "como", pero "metodo" es "método" y no
 * "metido"). La forma con tilde queda entonces como primera sugerencia. Tambien se quita la tilde que sobra
 * ("dió", "fué", "exámen").</li>
 * <li>Entre dos formas validas, con y sin tilde, las dos del nucleo, sigue decidiendo la frecuencia
 * ({@link #resolve}: "mas" -> "más", pero "esta" se respeta); la que se escribe con su tilde no se toca ("publicó" no
 * pasa a "público"), y la forma sin tilde de fuera del nucleo tampoco: "alcanzara", "agendo" o "facture" son otro
 * tiempo del verbo que "alcanzará", "agendó" y "facturé", y solo la lista decide cuando la diferencia de uso es tan
 * abrumadora que no entran. La otra se ofrece como primera sugerencia.</li>
 * <li>Lo que casi siempre es una errata no entra en la lista aunque sea una forma valida, y por eso se sigue
 * corrigiendo: la forma sin tilde de otra mucho mas frecuente ("tenia", "ingles", "medico"), el futuro de subjuntivo
 * en -ar, que es el futuro escrito sin tilde ("hablare"), y la que esta a una falta de ortografia o a un desliz de
 * una palabra muchisimo mas usada ("ablando", "carera", "memos"). Los umbrales estan en tools/build_dict_es.py. De las
 * que si entran y se parecen a una palabra frecuente, esa se ofrece como sugerencia sin cambiar lo escrito.</li>
 * <li>Las formas de las familias de {@link #NO_SUGGEST} ("chingaste", "pendejito", "cabrones") se respetan si se
 * escriben, pero no se proponen ({@link #vetoed}).</li>
 * <li>Las palabras que se escriben juntas por costumbre ("porfavor", "talvez", "aver") no estan en la lista y se
 * separan ({@link #FIXED}).</li>
 * </ul>
 */
final class Dictionary {

    /** Resultado para la palabra que se esta escribiendo. */
    static final class Result {
        final List<String> suggestions = new ArrayList<>();   // mejores primero, en minusculas
        String correction;      // se aplica al teclear un separador; null si no procede
        boolean known;          // la palabra escrita existe tal cual
    }

    private static final Map<String, Dictionary> CACHE = new java.util.concurrent.ConcurrentHashMap<>();   // lectura sin bloqueo
    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_LEN = 24;
    /** Bloques de la lista alfabetica con su mejor rango precalculado, para completar sin recorrer todo el prefijo. */
    private static final int BLOCK_BITS = 6, BLOCK = 1 << BLOCK_BITS;
    private static final double ED2_CUTOFF = 2.6;      // coste maximo de la busqueda a dos ediciones
    /**
     * Puntuacion base de una palabra aprendida del usuario: como una de frecuencia media (rango ~1500), asi
     * gana a los candidatos raros del diccionario pero no a las palabras muy frecuentes.
     */
    static final double PERSONAL_SCORE = Math.log(1500);
    private static final double MAX_EDIT = 2.5;        // coste maximo de una sola edicion (letra cambiada por otra cualquiera)
    /**
     * Ventaja (en ln del rango: unas veinte veces mas frecuente) que necesita una palabra a una falta de ortografia
     * de lo escrito para ganarle a la lectura con tildes. Vease {@link #spellingRival}.
     */
    private static final double SPELLING_MARGIN = 3.0;
    /**
     * Ventaja (en ln del rango, contado el coste de la errata) que necesita una palabra del nucleo a un desliz de lo
     * escrito para ganarle a la lectura con tildes cuando esa tambien es del nucleo: "comi" es "como" (rango 30) y no
     * "comí" (5500), y "temia" es "tenía" y no "temía"; pero "metodo" es "método" y no "metido", y "comio" es "comió".
     * Olvidar una tilde es treinta o cuarenta veces mas corriente que pulsar una tecla vecina concreta o bailar dos
     * letras ({@link #TILDE_MARGIN}: errata de coste hasta {@link #CHEAP_EDIT}), y cientos de veces mas que comerse una
     * letra o meter una de mas ({@link #TILDE_MARGIN_FAR}). Vease {@link #slipRival}.
     */
    private static final double TILDE_MARGIN = 2.8, TILDE_MARGIN_FAR = 3.4, CHEAP_EDIT = 0.6;
    /** Formas de fuera del nucleo (las mas frecuentes) que tambien se pueden deslizar. Vease {@link #glideSize}. */
    private static final int GLIDE_TAIL = 40000;
    /** Coste maximo de la errata de un rival de la lectura con tildes: todo menos una letra cambiada por otra lejana. */
    private static final double SLIP_EDIT = 2.0;
    /**
     * Longitud desde la que una entrada del lexico Star Wars cuenta siempre. Las mas cortas (Rex, Plo, Gon, AT) solo
     * se reconocen escritas con su caja canonica y nunca sirven de correccion: "gon" o "plo" en minusculas siguen
     * siendo los errores de "con" y "pelo" de toda la vida.
     */
    private static final int SW_MIN = 4;
    /**
     * Puntuacion de una entrada larga del lexico Star Wars (mas de cinco letras): algo mejor que una aprendida
     * ({@link #PERSONAL_SCORE}), porque parecerse a "palpatine" o "coruscant" rara vez es casualidad.
     */
    static final double SW_LONG_SCORE = Math.log(800);
    /** Coste maximo hacia una entrada larga del lexico: dos errores caros ("palatino" -> "palpatine"). */
    private static final double SW_ED2_CUTOFF = 4.0;
    /**
     * Puntuacion (ln del rango mas el coste de la edicion) por debajo de la cual una entrada del lexico escrita en
     * minusculas se considera un error evidente de una palabra del idioma y no se reconoce sin su mayuscula: una
     * tecla vecina o dos letras cambiadas de una de las dos mil mas frecuentes, o errores mas caros de otras aun mas
     * frecuentes ("rael" -> "real", "hoth" -> "both", "kamino" -> "camino"). Vease {@link #swCaseOnly}.
     */
    private static final double SW_CASE_LIMIT = Math.log(2000) + 0.6;
    /**
     * Entradas del lexico que las listas de frecuencia tambien traen (salen de subtitulos de peliculas, y las de la
     * saga estan entre ellas) pero que no son palabras normales de ningun idioma del teclado: se tratan como las
     * demas del lexico, con su caja ("anakin" -> "Anakin"). Las que si lo son ("fuerza", "hunter", "solo", "macht",
     * "fett") siguen las reglas del diccionario del idioma. Al final, los sustantivos alemanes propios de la saga.
     */
    private static final Set<String> SW_NAMES = new HashSet<>(Arrays.asList(
            "anakin", "skywalker", "luke", "leia", "vader", "darth", "yoda", "kenobi", "jedi", "sith", "ahsoka",
            "chewbacca", "chewie", "lando", "jabba", "dooku", "jango", "naboo", "kanan", "bridger", "pryce", "quinlan",
            "biggs", "ezra", "lars", "owen", "gideon", "beckett", "dexter", "gregor", "galen", "sabine",
            "morgan", "jesse", "cody", "hera", "satine", "merrin", "elsbeth", "artoo", "reva", "padawan",
            "lichtschwert", "todesstern", "hyperraum", "hyperantrieb", "droiden"));

    /** Nunca se proponen ni se usan como correccion (si se escriben, se respetan). */
    private static final Set<String> NO_SUGGEST = new HashSet<>(Arrays.asList(
            "puta", "puto", "putas", "putos", "mierda", "joder", "coño", "cabron", "cabrón", "carajo",
            "pendejo", "pendeja", "chingar", "chingada", "chingado", "verga", "culo", "culero", "mamón",
            "pinche", "maricon", "maricón", "polla", "gilipollas", "zorra", "perra",
            "tetona", "tetonas", "tetazas", "picha", "pichas", "cipote", "cipotes", "bollera", "bolleras", "panocha",
            "panochas", "piruja", "pirujas", "ojete", "ojetes", "madrazo", "madrazos", "madriza", "madrizas",
            "cabrones", "cabrona", "cabronazo", "mierdas", "culos", "maricones", "pendejos", "pendejas", "pendejada",
            "putita", "putitas", "putada", "hijoputa", "negrata", "chupavergas", "chupapollas", "comemierda",
            "pajero", "pajeros",
            "fuck", "fucking", "fucked", "fucker", "shit", "shitty", "bitch", "asshole", "cunt", "dick",
            "cock", "pussy", "bastard", "whore", "slut", "nigger", "faggot"));

    /**
     * Comienzos (sin tildes) de las formas espanolas de las familias de {@link #NO_SUGGEST}: sus flexiones, derivados y
     * compuestos que la lista ampliada trae fuera del nucleo ("chingaste", "jodiendo", "pendejito", "comemierdas",
     * "encabronado") tampoco se proponen. Las del nucleo siguen como estaban: solo las que figuran una a una en
     * NO_SUGGEST ("jodido" si se propone: es de las tres mil palabras mas escritas). Solo en espanol: en ingles
     * "follow" o "jodie" no tienen nada que ver.
     */
    private static final String[] VETO_STEMS = {
            "ching", "pendej", "apendej", "jode", "joda", "jodi", "jodo", "mierd", "comemierd", "cabron", "encabron",
            "mamon", "maricon", "gilipoll", "soplapoll", "calientapoll", "chupapoll", "chupaverg", "culer", "culit",
            "culon", "culot", "culaz", "culos", "puta", "puto", "puti", "pute", "puton", "hijoput", "verga", "vergui",
            "coño", "coñi", "coñaz", "pinches", "malparid", "puñeter", "caga", "cago", "cague", "pajea", "pajee", "pajeo",
            "pajer", "folla", "folle", "follo", "negrat", "chupamel", "chupasel", "mamamel", "mamasel", "violame",
            "violal"};

    /** Palabras corrientes que empiezan como una de {@link #VETO_STEMS} sin ser de su familia. */
    private static final Set<String> VETO_EXCEPT = new HashSet<>(Arrays.asList(
            "putativo", "putativa", "putativos", "putativas", "putin", "jodie", "vergara", "follaje", "follajes",
            "folleto", "folletos", "folletín", "folletines", "follón", "follones", "cagoule"));

    /**
     * Palabras que en un chat se escriben juntas por costumbre y son dos o tres ("porfavor", "talvez", "aver"), y unas
     * pocas faltas corrientes que quedan a mas de una letra de su palabra ("llendo", "nose"): no estan en la lista y se
     * cambian al cerrarlas, como una correccion mas (el chip de lo escrito las recupera). Solo en espanol.
     */
    private static final Map<String, String> FIXED = new HashMap<>();

    /**
     * Abreviaturas y formas cortas corrientes en un chat en espanol que la lista no trae y que el corrector cambiaba por
     * otra palabra ("av" por "a", "hr" por "he", "mx" por "me", "tb" por "te", "gmail" por "genial"): se respetan como
     * si fueran palabras conocidas (no se sustituyen ni llevan chip entre comillas); las sugerencias siguen saliendo.
     */
    private static final HashSet<String> SHORT_FORMS = new HashSet<>(Arrays.asList(
            "av", "esq", "mtro", "mtra", "profr", "profra", "lic", "ing", "mx", "cdmx",
            "tb", "tmb", "bn", "xq", "pq", "tqm", "ntp", "xd", "grax", "fb", "ig", "dm", "whats"));
    // Fuera a proposito: "yt" (desliz corriente de "y", con la letra vecina), "pza" (letras bailadas de "paz") y las
    // unidades ("hr" es tambien el desliz de "he"): esas solo se respetan tras una cifra, y eso lo decide el teclado

    /** Forma corta de un chat en espanol que se respeta tal cual (en minusculas). */
    static boolean shortForm(String w) { return w != null && SHORT_FORMS.contains(w); }

    static {
        String[] pairs = {
                "aver", "a ver", "aveces", "a veces", "talvez", "tal vez", "osea", "o sea", "porfavor", "por favor",
                "enserio", "en serio", "almenos", "al menos", "derrepente", "de repente", "apesar", "a pesar",
                "asique", "así que", "amenudo", "a menudo", "alomejor", "a lo mejor", "sinembargo", "sin embargo",
                "deacuerdo", "de acuerdo", "porsupuesto", "por supuesto", "atravez", "a través", "envez", "en vez",
                "denuevo", "de nuevo", "nisiquiera", "ni siquiera", "enmedio", "en medio", "yahora", "y ahora",
                "porcierto", "por cierto", "derepente", "de repente", "nose", "no sé", "llendo", "yendo",
                "coje", "coge", "cojer", "coger", "ivamos", "íbamos"};
        for (int i = 0; i + 1 < pairs.length; i += 2) FIXED.put(pairs[i], pairs[i + 1]);
    }

    /** Contracciones inglesas: la lista de frecuencia no lleva apostrofos y la primera pagina del teclado tampoco. */
    private static final Map<String, String> CONTRACTIONS = new HashMap<>();
    static {
        String[] pairs = {
                "im", "I'm", "ive", "I've", "dont", "don't", "doesnt", "doesn't", "didnt", "didn't",
                "cant", "can't", "couldnt", "couldn't", "wont", "won't", "wouldnt", "wouldn't", "shouldnt", "shouldn't",
                "isnt", "isn't", "arent", "aren't", "wasnt", "wasn't", "werent", "weren't", "hasnt", "hasn't",
                "havent", "haven't", "hadnt", "hadn't", "youre", "you're", "youve", "you've", "youll", "you'll",
                "youd", "you'd", "theyre", "they're", "theyve", "they've", "theyll", "they'll", "weve", "we've",
                "thats", "that's", "whats", "what's", "hes", "he's", "shes", "she's", "aint", "ain't",
                "wheres", "where's", "theres", "there's", "heres", "here's", "whos", "who's", "hows", "how's",
                "itll", "it'll", "thatll", "that'll"};
        for (int i = 0; i + 1 < pairs.length; i += 2) CONTRACTIONS.put(pairs[i], pairs[i + 1]);
    }

    /** Pares que suenan igual o parecido: cambiar uno por otro es un error frecuente (s/z, b/v, c/k...). */
    private static final Set<String> PHONETIC = new HashSet<>(Arrays.asList(
            "sz", "sc", "cz", "bv", "gj", "yi", "ck", "qk", "ks", "cq", "zs"));

    /**
     * Filas de letras de cada idioma, tal como las dibuja el teclado: QWERTY con ñ en espanol, QWERTY en
     * ingles y QWERTZ con ü ö ä en aleman. De ellas salen las teclas vecinas de cada letra.
     */
    private static String[] rows(String lang) {
        if ("de".equals(lang)) return new String[]{"qwertzuiopü", "asdfghjklöä", "yxcvbnm"};
        if ("en".equals(lang)) return new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        return new String[]{"qwertyuiop", "asdfghjklñ", "zxcvbnm"};
    }

    /** Teclas vecinas por idioma: cambiar una letra por una contigua es el error mas probable. */
    private static final Map<String, Map<Character, String>> NEIGHBORS = new HashMap<>();

    /** Letras vecinas de cada letra en la distribucion del idioma (se calcula una vez por idioma). */
    private static synchronized Map<Character, String> neighbors(String lang) {
        Map<Character, String> map = NEIGHBORS.get(lang);
        if (map != null) return map;
        map = new HashMap<>();
        String[] rows = rows(lang);
        float[] offset = {0f, 0.5f, 1.5f};
        for (int r = 0; r < rows.length; r++) {
            for (int i = 0; i < rows[r].length(); i++) {
                StringBuilder nb = new StringBuilder();
                float x = i + offset[r];
                for (int r2 = Math.max(0, r - 1); r2 <= Math.min(rows.length - 1, r + 1); r2++) {
                    for (int j = 0; j < rows[r2].length(); j++) {
                        if (r2 == r && j == i) continue;
                        if (Math.abs(j + offset[r2] - x) <= 1.01f) nb.append(rows[r2].charAt(j));
                    }
                }
                map.put(rows[r].charAt(i), nb.toString());
            }
        }
        NEIGHBORS.put(lang, map);
        return map;
    }

    /** Idioma admitido ("es", "en" o "de"); cualquier otro valor se trata como espanol. */
    private static String normalize(String lang) {
        return "en".equals(lang) ? "en" : "de".equals(lang) ? "de" : "es";
    }

    final String lang;
    private final String alphabet;
    private final boolean[] closeTable = new boolean[1 << 16];           // [x << 8 | y]: y es tecla vecina de x en la distribucion del idioma, o suena parecido
    private final boolean foldUmlaut;                                    // ü -> u al comparar (no en aleman: es letra propia)
    private final boolean spanish;                                       // la unica lista con formas fuera del nucleo
    private final String[] byRank;                                       // por frecuencia (indice = rango)
    /**
     * Nucleo de la lista: las palabras con rango menor que este. En espanol, las de la lista de siempre (el archivo
     * las separa de las formas nuevas con una linea en blanco); en ingles y aleman, que no tienen mas, toda la lista.
     * La busqueda a dos ediciones y el deslizado solo trabajan con ellas; el resto son formas conocidas y completables.
     */
    private final int core;
    // Tablas hash abiertas de enteros (rango + 1; 0 = hueco): con mas de cien mil palabras ocupan una fraccion de lo que
    // un HashMap con sus nodos y sus Integer, y se consultan igual de rapido
    private final int[] slots;                                           // palabra -> rango
    private final int[] accentSlots;                                     // forma sin tildes -> su forma con tildes mas frecuente
    private final int[] alpha;                                           // rangos en orden alfabetico, para completar
    private final int[] alphaMin;                                        // mejor rango de cada bloque de BLOCK entradas de alpha
    private final String[][] byLength = new String[MAX_LEN + 3][];        // palabras del nucleo por longitud, en orden de frecuencia
    private volatile Set<String> personal = Collections.emptySet();      // palabras aprendidas del usuario
    private volatile Map<String, String> personalPlain = Collections.emptyMap();   // forma sin tildes -> palabra aprendida
    // ---- lexico Star Wars: el mismo archivo para los tres idiomas, cargado una vez con cada instancia
    private volatile boolean starWars;                                   // activo (Prefs.STARWARS); apagado hasta que lo fije el IME
    private final HashMap<String, String> swCanon = new HashMap<>();     // minusculas -> forma canonica ("coruscant" -> "Coruscant")
    private final HashMap<String, String> swPlain = new HashMap<>();     // sin tildes -> entrada en minusculas ("padme" -> "padmé")
    private final String[] swWords;                                      // entradas en minusculas, en el orden del archivo
    // Nombres propios y marcas del idioma (res/raw/propios_*.txt): minusculas, y sin tildes si no chocan con otra palabra,
    // -> forma con su caja ("juan" -> "Juan", "mexico" -> "México", "tiktok" -> "TikTok"). No dependen del lexico Star Wars.
    private final HashMap<String, String> proper = new HashMap<>();
    private final String[] swFolded;                                     // las mismas en la forma de comparacion ({@link #fold})
    /**
     * Entradas que en minusculas serian un error evidente de una palabra frecuente de este idioma ({@link #SW_CASE_LIMIT}):
     * solo cuentan escritas con su caja ("Hoth", "Rael", "Kamino") y nunca se completan ni sirven de correccion.
     * Depende del idioma: "hoth" se reconoce en espanol, pero en ingles es "both" mal escrito.
     */
    private final HashSet<String> swCaseOnly = new HashSet<>();
    private static final String[] NO_WORDS = new String[0];
    private final String[] swGlide;                                      // entradas que se pueden deslizar (las de saga()), en minusculas

    /** Carga (o recupera de la cache) el diccionario de un idioma. Puede llamarse en segundo plano. */
    static synchronized Dictionary get(Context c, String lang) throws IOException {
        String l = normalize(lang);
        Dictionary d = CACHE.get(l);
        if (d == null) {
            d = new Dictionary(c.getApplicationContext(), l);
            CACHE.put(l, d);
        }
        return d;
    }

    /** Diccionario ya cargado, o null (sin bloquear aunque otro hilo este cargando). */
    static Dictionary cached(String lang) {
        return CACHE.get(normalize(lang));
    }

    private Dictionary(Context c, String lang) throws IOException {
        this.lang = lang;
        boolean de = "de".equals(lang);
        alphabet = "en".equals(lang) ? "abcdefghijklmnopqrstuvwxyz"
                : de ? "abcdefghijklmnopqrstuvwxyzäöüß" : "abcdefghijklmnopqrstuvwxyzñ";
        Map<Character, String> neighbors = neighbors(lang);
        for (int i = 0; i < alphabet.length(); i++) {
            char x = alphabet.charAt(i);
            String nb = neighbors.get(x);
            for (int j = 0; j < alphabet.length(); j++) {
                char y = alphabet.charAt(j);
                closeTable[x << 8 | y] = (nb != null && nb.indexOf(y) >= 0)
                        || PHONETIC.contains("" + x + y) || PHONETIC.contains("" + y + x);
            }
        }
        foldUmlaut = !de;
        spanish = !de && !"en".equals(lang);
        int res = "en".equals(lang) ? R.raw.words_en : de ? R.raw.words_de : R.raw.words_es;
        List<String> all = new ArrayList<>(spanish ? 170000 : 40000);
        int mark = -1;                                              // linea en blanco: hasta ahi llega el nucleo
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(res), "UTF-8"), 1 << 15);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String w = line.trim();
                if (!w.isEmpty()) all.add(w);
                else if (mark < 0 && !all.isEmpty()) mark = all.size();
            }
        } finally {
            r.close();
        }
        String[] words = new String[all.size()];
        int[] table = new int[tableSize(all.size())];
        int count = 0, accents = 0, coreCount = -1;
        for (int i = 0; i < words.length; i++) {
            if (i == mark) coreCount = count;
            String w = all.get(i);
            if (!insert(table, words, w, count)) continue;         // repetida
            words[count++] = w;
            if (hasMark(w)) accents++;
        }
        core = coreCount < 0 ? count : coreCount;
        byRank = count == words.length ? words : Arrays.copyOf(words, count);
        slots = table;
        accentSlots = new int[tableSize(accents)];
        int amask = accentSlots.length - 1;
        for (int i = 0; i < count; i++) {                           // por orden de frecuencia: se queda la primera de cada forma
            String w = byRank[i];
            if (!hasMark(w)) continue;
            int k = mix(foldHash(w)) & amask;
            while (accentSlots[k] != 0 && !sameFolded(byRank[accentSlots[k] - 1], w)) k = (k + 1) & amask;
            if (accentSlots[k] == 0) accentSlots[k] = i + 1;
        }
        String[] sorted = alphabetical(byRank);
        alpha = new int[count];
        alphaMin = new int[(count + BLOCK - 1) >> BLOCK_BITS];
        Arrays.fill(alphaMin, Integer.MAX_VALUE);
        for (int i = 0; i < count; i++) {
            int rk = rankOf(sorted[i]);
            alpha[i] = rk;
            if (rk < alphaMin[i >> BLOCK_BITS]) alphaMin[i >> BLOCK_BITS] = rk;
        }
        List<List<String>> buckets = new ArrayList<>();
        for (int i = 0; i < byLength.length; i++) buckets.add(new ArrayList<String>());
        for (int i = 0; i < core; i++) {
            String w = byRank[i];
            if (w.length() < byLength.length) buckets.get(w.length()).add(w);
        }
        for (int i = 0; i < byLength.length; i++) byLength[i] = buckets.get(i).toArray(new String[0]);

        List<String> saga = new ArrayList<>(800);
        r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(R.raw.starwars_words), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String w = line.trim();
                if (w.length() < 2) continue;
                String lower = w.toLowerCase(Locale.ROOT);
                if (swCanon.containsKey(lower)) continue;
                swCanon.put(lower, w);
                saga.add(lower);
                String plain = fold(lower);
                if (!plain.equals(lower) && !swPlain.containsKey(plain)) swPlain.put(plain, lower);
            }
        } finally {
            r.close();
        }
        int properRes = "en".equals(lang) ? R.raw.propios_en : de ? 0 : R.raw.propios_es;   // en aleman todo sustantivo va en mayuscula
        if (properRes != 0) {
            r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(properRes), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String w = line.trim();
                    if (w.isEmpty() || w.startsWith("#")) continue;
                    boolean exact = w.startsWith("=");          // solo con sus tildes: sin ellas es otra palabra ("tomas")
                    if (exact) w = w.substring(1);
                    String lower = w.toLowerCase(Locale.ROOT);
                    if (swCanon.containsKey(lower)) continue;   // el lexico manda sobre sus entradas (y se puede apagar)
                    proper.put(lower, w);
                    String plain = fold(lower);
                    if (!exact && !plain.equals(lower) && !proper.containsKey(plain) && !swCanon.containsKey(plain)) proper.put(plain, w);
                }
            } finally {
                r.close();
            }
        }
        swWords = saga.toArray(new String[0]);
        swFolded = new String[swWords.length];
        for (int i = 0; i < swWords.length; i++) swFolded[i] = fold(swWords[i]);
        // Entradas con mayuscula que en minusculas corregiria el diccionario del idioma sin dudar: las variantes a una
        // edicion de suggest(), con el lexico aun apagado (solo cuentan las palabras del idioma y no hay aprendidas)
        HashMap<String, Double> cand = new HashMap<>();
        for (String w : swWords) {
            if (w.length() < SW_MIN || rankOf(w) >= 0 || w.equals(swCanon.get(w))) continue;
            cand.clear();
            edits(w, cand);
            double best = Double.MAX_VALUE;
            for (Map.Entry<String, Double> e : cand.entrySet()) {
                if (!e.getKey().equals(w) && !vetoed(e.getKey()) && e.getValue() < best) best = e.getValue();
            }
            if (best <= SW_CASE_LIMIT) swCaseOnly.add(w);
        }
        ArrayList<String> glide = new ArrayList<>();
        for (String w : swWords) if (w.length() >= SW_MIN && !ordinary(w) && !swCaseOnly.contains(w)) glide.add(w);
        swGlide = glide.toArray(new String[0]);
    }

    // ---- tablas de la lista: palabra -> rango y forma sin tildes -> forma con tildes

    /** Tamano de una tabla hash abierta para n entradas: potencia de dos, ocupada entre un tercio y dos tercios. */
    private static int tableSize(int n) {
        return Math.max(16, Integer.highestOneBit(Math.max(1, n) * 3 / 2) << 1);
    }

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        return h ^ (h >>> 13);
    }

    /**
     * Las palabras en el orden de {@link String#compareTo}. Ordenar cien mil cadenas es lo mas lento de la carga, asi
     * que primero se ordenan numeros (las cinco primeras letras de cada palabra empaquetadas con su posicion) y solo
     * los tramos que comparten esas cinco letras se terminan de ordenar comparando cadenas.
     */
    private static String[] alphabetical(String[] words) {
        int n = words.length;
        String[] sorted = new String[n];
        boolean packed = n < (1 << 23);
        long[] keys = new long[packed ? n : 0];
        for (int i = 0; i < n && packed; i++) {
            String w = words[i];
            long k = 0;
            for (int j = 0; j < 5; j++) {
                int ch = j < w.length() ? w.charAt(j) : 0;
                if (ch > 0xFF) packed = false;              // fuera de Latin-1 la clave no conserva el orden: orden corriente
                k = (k << 8) | ch;
            }
            keys[i] = (k << 23) | i;
        }
        if (!packed) {
            System.arraycopy(words, 0, sorted, 0, n);
            Arrays.sort(sorted);
            return sorted;
        }
        Arrays.sort(keys);
        for (int i = 0; i < n; i++) sorted[i] = words[(int) (keys[i] & 0x7FFFFF)];
        for (int i = 0; i < n; ) {
            int j = i + 1;
            long prefix = keys[i] >>> 23;
            while (j < n && (keys[j] >>> 23) == prefix) j++;
            if (j - i > 1) Arrays.sort(sorted, i, j);
            i = j;
        }
        return sorted;
    }

    /** Anota la palabra con su rango en la tabla; false si ya estaba. */
    private static boolean insert(int[] table, String[] words, String w, int rank) {
        int mask = table.length - 1;
        int k = mix(w.hashCode()) & mask;
        while (table[k] != 0) {
            if (words[table[k] - 1].equals(w)) return false;
            k = (k + 1) & mask;
        }
        table[k] = rank + 1;
        return true;
    }

    /** La palabra lleva alguna tilde (o dieresis, donde se pliega): su forma de comparacion es otra. */
    private boolean hasMark(String w) {
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            if (ch > 'z' && plain(ch, foldUmlaut) != ch) return true;
        }
        return false;
    }

    /** Hash de la forma de comparacion ({@link #fold}) sin construirla. */
    private int foldHash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 31 * h + plain(s.charAt(i), foldUmlaut);
        return h;
    }

    /** Las dos palabras son la misma sin contar las tildes. */
    private boolean sameFolded(String a, String b) {
        int n = a.length();
        if (b.length() != n) return false;
        for (int i = 0; i < n; i++) {
            if (plain(a.charAt(i), foldUmlaut) != plain(b.charAt(i), foldUmlaut)) return false;
        }
        return true;
    }

    /**
     * Forma con tildes mas frecuente de lo escrito (se compara sin tildes: vale pasar la forma plegada o cualquier
     * otra), o null si ninguna palabra de la lista con tilde coincide.
     */
    private String accented(String v) {
        int mask = accentSlots.length - 1;
        for (int k = mix(foldHash(v)) & mask; ; k = (k + 1) & mask) {
            int e = accentSlots[k];
            if (e == 0) return null;
            String w = byRank[e - 1];
            if (sameFolded(w, v)) return w;
        }
    }

    private static final int TWIN_CHIP_RATIO = 12;
    /** Tildes diacriticas: monosilabos e interrogativos que la llevan sin que lo pida la regla general. */
    private static final HashSet<String> DIACRITICS = new HashSet<>(Arrays.asList(
            "s\u00ed", "\u00e9l", "t\u00fa", "m\u00ed", "s\u00e9", "m\u00e1s", "t\u00e9", "d\u00e9", "qu\u00e9", "c\u00f3mo",
            "cu\u00e1ndo", "d\u00f3nde", "ad\u00f3nde", "qui\u00e9n", "qui\u00e9nes", "cu\u00e1l", "cu\u00e1les", "cu\u00e1nto",
            "cu\u00e1nta", "cu\u00e1ntos", "cu\u00e1ntas"));

    /**
     * La otra lectura de una palabra de la lista que solo cambia en la tilde ("si" y "sí", "que" y "qué", "esta" y
     * "está", "hablo" y "habló"), para ofrecerla a un toque sin aplicarla nunca sola; null si no la hay. Solo en
     * espanol, solo palabras del nucleo, nunca una mas de {@link #TWIN_CHIP_RATIO} veces mas rara que lo escrito ("de"
     * no ofrece "dé") ni una forma cuya tilde no sea legitima ("ésto", "sólo", "dí").
     */
    String twin(String w) {
        if (!spanish || w == null || w.length() < 2) return null;
        int own = rankOf(w);
        if (own < 0) return null;
        String plain = fold(w), other, marked;
        if (plain.equals(w)) { other = accented(w); marked = other; }                  // sin tilde: su forma con ella
        else { other = rankOf(plain) >= 0 ? plain : null; marked = w; }                 // con tilde: la forma sin ella
        if (other == null || other.equals(w) || vetoed(other)) return null;
        int r = rankOf(other);
        if (r < 0 || r >= core || r + 1L > (long) TWIN_CHIP_RATIO * (own + 1L)) return null;
        if (!tildePosible(marked) && !DIACRITICS.contains(marked)) return null;
        return other;
    }

    /** Palabras del nucleo: las de la lista de siempre, sin las formas nuevas del espanol. En ingles y aleman, todas. */
    int coreSize() { return core; }

    /**
     * Palabras que indexa el deslizado: el nucleo y las {@link #GLIDE_TAIL} formas nuevas mas usadas ("platicamos",
     * "depositaron", "checaste"). El resto de la cola (formas que los subtitulos casi no traen) no entra: alargaria
     * cada reconocimiento sin que casi nadie las deslice, y siguen pudiendose teclear y completar.
     */
    int glideSize() { return Math.min(byRank.length, core + GLIDE_TAIL); }

    /**
     * No se propone nunca (ni como correccion, ni al completar, ni al deslizar): las de {@link #NO_SUGGEST} y, en
     * espanol, las formas de fuera del nucleo que empiezan como ellas ({@link #VETO_STEMS}). Si se escriben, se respetan.
     */
    boolean vetoed(String w) {
        if (NO_SUGGEST.contains(w)) return true;
        if (!spanish || rankOf(w) < core) return false;
        if (personal.contains(w)) return false;            // guardada por el usuario: se completa, se corrige hacia ella y se desliza
        String f = fold(w);
        for (String stem : VETO_STEMS) if (f.startsWith(stem)) return !VETO_EXCEPT.contains(w);
        return false;
    }

    /**
     * Entradas del lexico Star Wars que el deslizado reconoce ademas de las palabras del idioma: las de {@link #saga},
     * en minusculas y en el orden del archivo. Vacio con el lexico apagado. El IME les pone despues su forma canonica.
     */
    String[] glideLexicon() { return starWars ? swGlide : NO_WORDS; }

    /**
     * Palabras aprendidas del usuario: se aceptan como conocidas, se completan y tambien se corrige hacia ellas
     * (como entradas de frecuencia media, {@link #PERSONAL_SCORE}, con la misma distancia ponderada).
     */
    void setPersonal(Collection<String> words) {
        HashSet<String> set = new HashSet<>();
        HashMap<String, String> plain = new HashMap<>();
        if (words != null) {
            for (String w : words) {
                if (w == null || w.isEmpty()) continue;
                set.add(w);
                String f = fold(w);
                if (!plain.containsKey(f)) plain.put(f, w);
            }
        }
        personalPlain = plain;
        personal = set;
    }

    /**
     * Clave de la tabla que separa palabras juntas ("alomejor", "sinembargo"). La version anterior, que no tenia la
     * tabla, las aprendia sola por repeticion; {@link Prefs#migrate} las quita una vez de las aprendidas para que la
     * tabla actue. Despues, quien quiera conservar una la guarda con el chip de lo escrito y se respeta.
     */
    static boolean fixedKey(String w) {
        return w != null && FIXED.containsKey(w.toLowerCase(Locale.ROOT));
    }

    /** La palabra esta entre las aprendidas del usuario (se comparan en minusculas, como se guardan). */
    boolean isPersonal(String w) {
        return w != null && personal.contains(w.toLowerCase(Locale.ROOT));
    }

    /** Palabra aprendida igual a la forma dada o a su forma sin tildes (el usuario pudo escribirla sin ellas), o null. */
    private String personalWord(String v) {
        Set<String> set = personal;
        if (set.isEmpty()) return null;
        if (set.contains(v)) return v;
        return personalPlain.get(fold(v));
    }

    // ---- lexico Star Wars

    /** Activa o desactiva el lexico Star Wars en conocidas, completados, correcciones y {@link #canonical}. */
    void setStarWars(boolean on) { starWars = on; }

    boolean starWars() { return starWars; }

    /**
     * Forma canonica de una entrada del lexico Star Wars ("coruscant" -> "Coruscant"), o null si no lo es, si el
     * lexico esta desactivado, si la entrada va en minusculas ("bantha", "lightsaber": se respeta la caja de lo
     * escrito) o si la palabra es ademas una palabra normal del idioma ("orden", "fuerza", "ghost", "wing"; en
     * espanol tambien "leia", que es "leía" sin tilde): esas no cambian de forma.
     */
    String canonical(String lower) {
        if (lower == null) return null;
        if (starWars) {
            String c = swCanon.get(lower);
            if (c != null && !c.equals(lower) && !ordinary(lower)) return c;
        }
        return properForm(lower);
    }

    /**
     * Nombre propio o marca con su caja y sus tildes ("juan" -> "Juan", "mexico" -> "México", "tiktok" -> "TikTok"), o
     * null. Una que el usuario guardo en su diccionario personal (rechazo la mayuscula varias veces) ya no cuenta.
     */
    String properForm(String lower) {
        if (lower == null || personal.contains(lower)) return null;
        return proper.get(lower);
    }

    /** Numero de formas propias cargadas para este idioma (para pruebas). */
    int properCount() { return proper.size(); }

    /**
     * Palabra normal del idioma: esta en su diccionario (y no es uno de los nombres de {@link #SW_NAMES}) o es la
     * forma sin tilde de una ("leia" es "leía" mal escrita en espanol). Sigue las reglas del diccionario, no las
     * del lexico, aunque figure en el.
     */
    private boolean ordinary(String w) {
        if (rankOf(w) >= 0 && !SW_NAMES.contains(w)) return true;
        String acc = accented(w);
        return acc != null && !acc.equals(w);
    }

    /**
     * Como {@link #ordinary}, pero del diccionario solo cuenta el nucleo: una entrada del lexico que la lista trae en su
     * cola ("separatista", "inquisidores", "milenario") no es destino de correccion como palabra del idioma, asi que
     * tiene que seguir siendolo como entrada del lexico; si no, su errata acababa en la forma inglesa o alemana.
     */
    private boolean coreWord(String w) {
        int r = rankOf(w);
        if (r >= 0 && r < core && !SW_NAMES.contains(w)) return true;
        String acc = accented(w);
        return acc != null && !acc.equals(w);
    }

    /**
     * Entrada del lexico activo, de {@link #SW_MIN} letras o mas, que no es palabra frecuente del idioma. La caja la
     * decide {@link #canonical} con {@link #ordinary}: una palabra de la lista, aunque sea de la cola, no cambia de caja.
     */
    private boolean lexicon(String w) {
        return starWars && w.length() >= SW_MIN && swCanon.containsKey(w) && !coreWord(w);
    }

    /**
     * Entrada del lexico que cuenta escrita de cualquier forma, se completa y sirve de correccion: las de
     * {@link #lexicon} salvo las que en minusculas serian un error evidente ({@link #swCaseOnly}).
     */
    private boolean saga(String w) {
        return lexicon(w) && !swCaseOnly.contains(w);
    }

    /** Entrada del lexico ({@link #saga}) igual a la forma dada o a su forma sin tildes ("padme" -> "padmé"), o null. */
    private String swWord(String v) {
        if (!starWars) return null;
        String s = swCanon.containsKey(v) ? v : swPlain.get(fold(v));
        return s != null && saga(s) ? s : null;
    }

    /**
     * Lo escrito es una entrada del lexico activo que no es palabra normal del idioma: tal cual o con tildes de mas
     * ("ahsóka" se respeta); las cortas ("Rex", "Plo", "AT") y las que en minusculas serian otra palabra ("Hoth" en
     * ingles) solo con su caja canonica; tambien su plural regular ("ewoks", "wookiees", "Hutts").
     */
    private boolean swKnown(String typed, String w) {
        if (!starWars) return false;
        String canon = swCanon.get(w);
        if (canon == null && !w.equals(fold(w))) canon = swCanon.get(fold(w));
        if (canon != null) {
            String entry = canon.toLowerCase(Locale.ROOT);
            // con su caja canonica tambien la que solo es "normal" por parecerse a una palabra con tilde y no esta en
            // la lista: "Leia" se respeta, "leia" sigue siendo "leía"
            return saga(entry) || (canon.equals(typed) && (!ordinary(entry) || rankOf(entry) < 0));
        }
        int n = w.length();
        if (n <= SW_MIN || w.charAt(n - 1) != 's') return false;
        String stem = w.substring(0, n - 1);
        String sc = swCanon.get(stem);
        return sc != null && (saga(stem) || (!ordinary(stem) && typed.startsWith(sc)));
    }

    /**
     * Quita las tildes (á é í ó ú). La ñ y las letras alemanas ä ö ü ß se conservan: son letras propias
     * del teclado, no una letra base con acento. Independiente del idioma.
     */
    static String deaccent(String s) {
        return strip(s, false);
    }

    /** Forma de comparacion de este diccionario: sin tildes y, salvo en aleman, con la ü plegada en u. */
    private String fold(String s) {
        return strip(s, foldUmlaut);
    }

    private static String strip(String s, boolean foldUmlaut) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            char p = plain(ch, foldUmlaut);
            if (p != ch && b == null) {
                b = new StringBuilder(s.length());
                b.append(s, 0, i);
            }
            if (b != null) b.append(p);
        }
        return b == null ? s : b.toString();
    }

    private static char plain(char ch, boolean foldUmlaut) {
        switch (ch) {
            case 'á': return 'a';
            case 'é': return 'e';
            case 'í': return 'i';
            case 'ó': return 'o';
            case 'ú': return 'u';
            case 'ü': return foldUmlaut ? 'u' : ch;
            default: return ch;
        }
    }

    /** La palabra (en minusculas) esta en la lista del idioma. */
    boolean inList(String w) { return w != null && rankOf(w) >= 0; }

    /** Palabra del idioma, aprendida o del lexico Star Wars activo (estas, aunque exijan su caja: no se aprenden). */
    boolean contains(String w) {
        return rankOf(w) >= 0 || personal.contains(w) || lexicon(w) || proper.containsKey(w);
    }

    /** Entrada del lexico Star Wars (en minusculas), este o no activo: esas no se aprenden como palabras del usuario. */
    boolean inLexicon(String w) {
        if (w == null) return false;
        String e = swCanon.containsKey(w) ? w : swPlain.get(fold(w));
        // no cuenta la entrada que este idioma trata como otra palabra sin su tilde ("leia" es "leía" en espanol): si
        // el usuario rechaza esa correccion, la palabra tiene que poder aprenderse
        return e != null && !(rankOf(e) < 0 && ordinary(e));
    }

    /**
     * Lo escrito parece una errata de una palabra frecuente del idioma: es una palabra del diccionario sin su tilde (o
     * con una de mas), o esta a una sola edicion (letra de mas, de menos, cambiada por cualquier otra o dos letras
     * invertidas, sin ponderar) de una entrada con rango menor que {@code maxRank}. Las palabras aprendidas y el lexico
     * Star Wars no cuentan. Lo usa la politica del diccionario personal ({@link Learning}).
     */
    boolean nearFrequent(String w, int maxRank) {
        if (w == null || w.isEmpty()) return false;
        String a = fold(w);
        if (!a.equals(w) && (rankOf(a) >= 0 || accented(a) != null)) return true;   // con tildes de mas o de otra forma
        String acc = accented(a);
        if (acc != null && !acc.equals(w)) return true;                                       // la misma sin tilde
        int n = a.length();
        for (int i = 0; i < n; i++) {
            if (frequent(a.substring(0, i) + a.substring(i + 1), maxRank)) return true;
        }
        for (int i = 0; i + 1 < n; i++) {
            if (a.charAt(i) == a.charAt(i + 1)) continue;
            if (frequent(a.substring(0, i) + a.charAt(i + 1) + a.charAt(i) + a.substring(i + 2), maxRank)) return true;
        }
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < alphabet.length(); k++) {
                char ch = alphabet.charAt(k);
                if (ch == a.charAt(i)) continue;
                if (frequent(a.substring(0, i) + ch + a.substring(i + 1), maxRank)) return true;
            }
        }
        for (int i = 0; i <= n; i++) {
            for (int k = 0; k < alphabet.length(); k++) {
                if (frequent(a.substring(0, i) + alphabet.charAt(k) + a.substring(i), maxRank)) return true;
            }
        }
        return false;
    }

    /** La forma (sin tildes) es, o tiene como forma con tildes, una palabra del idioma con rango menor que maxRank. */
    private boolean frequent(String plainForm, int maxRank) {
        int r = rankOf(plainForm);
        if (r >= 0 && r < maxRank) return true;
        String acc = accented(plainForm);
        return acc != null && rankOf(acc) < maxRank;
    }

    int size() { return byRank.length; }

    // ---- acceso por rango (lo usa SwipeDecoder para construir su indice; no cambia nada de lo anterior)

    /** Palabra con ese rango de frecuencia: 0 es la mas frecuente y {@link #size()} - 1 la ultima. */
    String word(int rank) { return byRank[rank]; }

    /** Rango de frecuencia de una palabra (tal cual aparece en la lista, con tildes), o -1 si no esta. */
    int rankOf(String w) {
        int mask = slots.length - 1;
        for (int k = mix(w.hashCode()) & mask; ; k = (k + 1) & mask) {
            int e = slots[k];
            if (e == 0) return -1;
            if (byRank[e - 1].equals(w)) return e - 1;
        }
    }

    /** Palabras aprendidas del usuario (instantanea de solo lectura). */
    Set<String> personal() { return personal; }

    /** Contraccion inglesa para una forma sin apostrofo ("dont" -> "don't"), o null; en espanol siempre null. */
    String contraction(String w) { return "en".equals(lang) ? CONTRACTIONS.get(w) : null; }

    /** Palabras que nunca se proponen (ni como correccion ni como resultado del deslizado). */
    static boolean noSuggest(String w) { return NO_SUGGEST.contains(w); }

    /**
     * Entrada del diccionario para una forma escrita: la misma palabra o, si su forma con tildes
     * es mucho mas frecuente (seis veces o mas), esa forma. Null si no hay nada parecido.
     * Una palabra de la lista escrita con sus tildes se respeta siempre: "publicó", "vació" o "criticó" no se cambian
     * por "público", "vacío" o "crítico" por mucho mas frecuentes que sean; quien puso la tilde sabia donde la ponia.
     * Tambien se respeta la forma sin tilde de fuera del nucleo: si la lista la trae junto a la otra ("alcanzara" y
     * "alcanzará", "agendo" y "agendó") es que su generador no vio una diferencia de uso abrumadora, y la regla de las
     * seis veces, pensada para "mas" y "más", le cambiaria el tiempo al verbo. Y en espanol, lo escrito con una tilde
     * que no puede ser suya ({@link #tildePosible}) es la palabra sin ella o con ella en su sitio ("dió", "fué",
     * "exámen", "despúes").
     */
    private String resolve(String v) {
        return resolve(v, true);
    }

    /**
     * {@code strip}: se admite quitar la tilde que sobra. Solo a lo escrito tal cual: a las variantes a una edicion no,
     * porque entonces "Jaén" (que la lista no trae) pasaba por "Jané" y acababa en "Jane", o "Padmé" en "padre".
     */
    private String resolve(String v, boolean strip) {
        int own = rankOf(v);
        String acc = accented(v);
        if (strip && own < 0 && spanish && hasMark(v) && !tildePosible(v)) {   // con una tilde que no es la suya: la mas
            int p = rankOf(fold(v));                         // frecuente de las lecturas, con o sin tilde ("crées" es "crees")
            if (p >= 0 && (acc == null || p < rankOf(acc))) return byRank[p];
        }
        if (acc != null && !acc.equals(v)) {
            if (own < 0 || (own < core && own >= TWIN_RATIO * (rankOf(acc) + 1) && !hasMark(v))) return acc;
        }
        return own < 0 ? null : v;
    }

    private static boolean vowel(char c) {
        return "aeiou\u00e1\u00e9\u00ed\u00f3\u00fa\u00fc".indexOf(c) >= 0;
    }

    private static boolean strongVowel(char c) {
        return "aeo\u00e1\u00e9\u00f3".indexOf(c) >= 0;
    }

    /**
     * La tilde de lo escrito puede ser suya segun las reglas de acentuacion: una sola tilde y, o bien deshace un
     * diptongo (i o u con tilde junto a vocal abierta: "Macías", "Díez", "subsecretaría"), o va en una aguda acabada en
     * vocal, n o s ("Jaén", "leonés", "Zoé"), en una llana acabada en otra consonante ("Páez") o en una esdrujula.
     * Entonces la palabra que la lista no trae se deja como esta: es un apellido, un gentilicio o una palabra rara bien
     * escrita, y quitarle la tilde la convertia en otra ("leonés" en "leones"). No puede ser suya en un monosilabo
     * ("dió", "fué", "bién", "fuí") ni en una llana acabada en vocal, n o s ("exámen", "crées", "díme").
     */
    static boolean tildePosible(String v) {
        int n = v.length();
        int marks = 0, nuclei = 0, markNucleus = -1;
        boolean markHiatus = false;
        int i = 0;
        while (i < n) {
            char c = v.charAt(i);
            if (!vowel(c)) { i++; continue; }
            if (c == 'u' && i > 0 && (v.charAt(i - 1) == 'q' || v.charAt(i - 1) == 'g') && i + 1 < n
                    && "ei\u00e9\u00ed".indexOf(v.charAt(i + 1)) >= 0) { i++; continue; }   // u muda de que, qui, gue, gui
            int j = i;
            while (j < n && vowel(v.charAt(j))) j++;
            for (int k = i; k < j; k++) {                    // grupo de vocales: un nucleo por cada tramo sin hiato
                char x = v.charAt(k);
                boolean weakMark = x == '\u00ed' || x == '\u00fa';
                boolean hiatus = false;
                if (k > i) {
                    char p = v.charAt(k - 1);
                    boolean pWeakMark = p == '\u00ed' || p == '\u00fa';
                    hiatus = (strongVowel(p) && strongVowel(x)) || (weakMark && strongVowel(p)) || (pWeakMark && strongVowel(x));
                }
                if (k == i || hiatus) nuclei++;
                if (x > 'z' && x != '\u00fc') {              // vocal con tilde
                    marks++;
                    markNucleus = nuclei;
                    if (weakMark && ((k > i && strongVowel(v.charAt(k - 1))) || (k + 1 < j && strongVowel(v.charAt(k + 1))))) markHiatus = true;
                }
            }
            i = j;
        }
        if (marks != 1) return false;
        if (markHiatus) return true;
        if (nuclei <= 1) return false;
        int fromEnd = nuclei - markNucleus + 1;
        char last = v.charAt(n - 1);
        boolean vns = vowel(last) || last == 'n' || last == 's';
        if (fromEnd == 1) return vns;
        if (fromEnd == 2) return !vns;
        return true;
    }

    /**
     * Veces que el rango de una forma del nucleo tiene que superar al de su forma con tildes para ceder ante ella:
     * las seis de siempre, menos lo que bajaron los rangos al quitar de la lista sus erratas (un 1,7 %), para que los
     * pares que estaban en el limite ("limite" y "límite") sigan como estaban.
     */
    private static final double TWIN_RATIO = 5.9;

    /** Puntuacion maxima (ln del rango mas la penalizacion) para corregir sin preguntar, por longitud. */
    private static double limit(int n) {
        return n <= 2 ? 5.7 : n == 3 ? 7.3 : n == 4 ? 8.7 : 9.6;
    }

    /**
     * ln(rango) de una palabra del diccionario, o {@link #PERSONAL_SCORE} si es aprendida, mas la penalizacion. Una
     * entrada del lexico Star Wars ({@link #saga}) puntua al menos como una aprendida ({@link #SW_LONG_SCORE} si es
     * larga) aunque figure en la cola del diccionario del idioma ("anakin", "skywalker"): asi no la tapan palabras raras.
     */
    private double score(String word, double penalty) {
        int r = rankOf(word);
        double base = r < 0 ? PERSONAL_SCORE : Math.log(r + 1);
        if (saga(word)) {
            double s = word.length() > 5 ? SW_LONG_SCORE : PERSONAL_SCORE;
            if (s < base) base = s;
        }
        // una aprendida que la lista trae en su cola ("platicamos", "agendar") sigue siendo destino de correccion
        if (r >= core && PERSONAL_SCORE < base && personal.contains(word)) base = PERSONAL_SCORE;
        return base + penalty;
    }

    /**
     * Sugerencias y correccion para lo que se lleva escrito de una palabra. Lo escrito con c con cedilla no se corrige
     * nunca sin preguntar: la cedilla solo sale manteniendo la c, asi que es a proposito ("Barça", "força", "ça"), y
     * ninguna lista la trae; las sugerencias se siguen ofreciendo.
     */
    Result suggest(String typed) {
        Result res = compute(typed);
        if (res.correction != null && (typed.indexOf('\u00e7') >= 0 || typed.indexOf('\u00c7') >= 0)) res.correction = null;
        return res;
    }

    private Result compute(String typed) {
        Result res = new Result();
        String w = typed.toLowerCase(Locale.ROOT);
        int n = w.length();
        if (n == 0 || n > 24) return res;
        if ("en".equals(lang)) {
            if (w.equals("i")) { res.correction = "I"; return res; }
            String c = CONTRACTIONS.get(w);
            if (c != null) {
                res.correction = c;
                res.suggestions.add(c);
                return res;
            }
            if (w.indexOf('\'') >= 0) {              // "don't" se respeta tal cual
                res.known = rankOf(w.replace("'", "")) >= 0 || proper.containsKey(w);   // "i'm": conocida, y el teclado le pone la I
                return res;
            }
        }
        if (n == 1 && Character.isLetter(w.charAt(0))) {   // una sola letra: nada que corregir, pero ya se puede completar
            res.known = true;
            res.suggestions.addAll(complete(w, MAX_SUGGESTIONS));
            return res;
        }
        if (n < 2 || n > MAX_LEN) return res;
        boolean learned = personal.contains(w);
        if (!learned && !w.equals(fold(w))) {              // escrita con tildes y aprendida sin ellas: se respeta tal cual
            String plainLearned = personalPlain.get(fold(w));
            learned = plainLearned != null && plainLearned.equals(fold(w));
        }
        boolean inLexicon = swKnown(typed, w);             // del lexico Star Wars, si esta activo
        int own = rankOf(w);
        // "av", "tb" o un nombre propio que la lista no trae ("tiktok", "rappi"): se respeta tal cual; la caja la pone el teclado
        boolean shortForm = (spanish && SHORT_FORMS.contains(w)) || (!learned && proper.containsKey(w));
        res.known = own >= 0 || learned || inLexicon || shortForm;
        if (spanish && !res.known) {                       // "porfavor", "talvez": dos palabras escritas como una
            String fixed = FIXED.get(w);
            if (fixed != null) {
                res.correction = fixed;
                res.suggestions.add(fixed);
                return res;
            }
        }

        HashMap<String, Double> cand = new HashMap<>();
        String acc = learned || inLexicon || shortForm ? null : resolve(w);
        boolean accentFix = acc != null && !acc.equals(w);
        if (accentFix) put(cand, acc, score(acc, 0));
        String marked = null;                            // lo escrito con sus tildes, si asi es una palabra y tal cual no
        String rival = null;                             // palabra del nucleo de la que lo escrito seria un desliz
        String hint = null;                              // primera sugerencia que no se aplica sola
        if (!res.known) {
            String pacc = personalWord(w);               // aprendida con tildes de lo escrito sin ellas
            if (pacc != null && !pacc.equals(w)) put(cand, pacc, score(pacc, 0));
            String sacc = swWord(w);                     // entrada del lexico con tildes ("padme" -> "padmé")
            if (sacc != null && !sacc.equals(w)) put(cand, sacc, score(sacc, 0));
            if (accentFix && vetoed(acc)) return res;    // "cabron": es la que es, sin su tilde; ni se le pone ni se cambia
            for (Map.Entry<String, Double> e : cand.entrySet()) {
                if (vetoed(e.getKey())) continue;
                if (marked == null || e.getValue() < cand.get(marked)) marked = e.getKey();
            }
            if (spanish && marked != null && marked.equals(acc)) {
                HashMap<String, Double> one = new HashMap<>();
                edits(w, one);
                rival = slipRival(w, marked, one);
                for (Map.Entry<String, Double> e : one.entrySet()) put(cand, e.getKey(), e.getValue());
            } else {
                edits(w, cand);
            }
            if (n >= 5) scan(w, cand);                   // tambien a dos ediciones
        } else if (spanish && own >= core && !learned && !inLexicon) {
            // Forma rara pero valida: se respeta, y se ofrece (sin aplicarla) la otra lectura mas probable: la misma con
            // su tilde ("alcanzara": "alcanzará") o la palabra frecuente de la que seria un desliz ("lisos": "listos")
            String twin = accented(w);
            if (twin != null && !twin.equals(w) && !vetoed(twin)) hint = twin;
            else {
                HashMap<String, Double> one = new HashMap<>();
                edits(w, one);
                hint = slipRival(w, w, one);
            }
        } else if (spanish && learned && own >= 0) {
            // Palabra de la lista que el usuario guardo para que no se le cambie ("papa", "deposito", "seria"): se
            // respeta, y su gemela con tilde sigue a un toque en la cinta, sin aplicarse sola
            String twin = accented(w);
            if (twin != null && !twin.equals(w) && !vetoed(twin)) hint = twin;
        }

        String best = null;
        double bestScore = Double.MAX_VALUE;
        for (Map.Entry<String, Double> e : cand.entrySet()) {
            if (e.getKey().equals(w) || vetoed(e.getKey())) continue;
            if (e.getValue() < bestScore) { bestScore = e.getValue(); best = e.getKey(); }
        }
        if (marked != null) {
            // La tilde manda: a lo escrito solo le faltan las tildes para ser una palabra, y se le ponen aunque esa
            // palabra sea rara (su puntuacion no llegaria al limite). Cede ante una falta de ortografia de una palabra
            // muchisimo mas usada ("parese" es "parece") y ante un desliz de una palabra del nucleo ("peto" es "pero")
            String spelling = spellingRival(w, cand.get(marked));
            if (spelling != null) rival = spelling;
            res.correction = rival != null ? rival : marked;
            if (rival != null) hint = marked;
        } else if (best != null) {
            if (!res.known) {
                // una palabra aprendida a una sola edicion de lo escrito se corrige aunque el limite por longitud
                // la dejara fuera: el usuario la guardo a proposito
                boolean personalEdit = n >= 3 && (rankOf(best) < 0 || rankOf(best) >= core) && personal.contains(best)
                        && bestScore - PERSONAL_SCORE <= MAX_EDIT;
                // lo mismo para una entrada del lexico Star Wars; las largas admiten hasta dos errores caros
                // ("palatino" -> "palpatine"): pocas palabras normales se les parecen tanto
                boolean sagaEdit = n >= 3 && saga(best)
                        && bestScore - score(best, 0) <= (best.length() > 5 ? SW_ED2_CUTOFF : MAX_EDIT);
                if (bestScore <= limit(n) || personalEdit || sagaEdit) res.correction = best;
            }
            else if (accentFix && best.equals(acc)) res.correction = acc;
        }

        for (String s : completions(w, MAX_SUGGESTIONS)) put(cand, s, score(s, 0.5));
        for (String p : personal) if (p.startsWith(w) && !p.equals(w)) put(cand, p, 1.0);
        if (starWars) {                                  // entradas del lexico que empiezan asi: como mucho dos, las mas cortas, con
            double pen = n >= 5 ? 0.5 : n == 4 ? 1.2 : 2.0;   // prefijos cortos por detras de las palabras corrientes ("man" no trae Mandalore)
            String a = null, b = null;
            for (String s : swWords) {
                if (!s.startsWith(w) || s.equals(w) || !saga(s)) continue;
                if (a == null || s.length() < a.length()) { b = a; a = s; }
                else if (b == null || s.length() < b.length()) b = s;
            }
            if (a != null) put(cand, a, PERSONAL_SCORE + pen + 0.1 * (a.length() - n));   // cuantas menos letras falten, mejor
            if (b != null) put(cand, b, PERSONAL_SCORE + pen + 0.1 * (b.length() - n));
        }
        if (n >= 3 && !proper.isEmpty()) {               // nombres propios que empiezan asi, se teclee o no su tilde ("mexi" -> México):
            double pen = n >= 5 ? 0.5 : n == 4 ? 1.2 : 2.0;   // como mucho dos, los mas cortos, y con prefijos cortos por detras
            String fw = fold(w), a = null, b = null;
            for (Map.Entry<String, String> e : proper.entrySet()) {
                String key = e.getKey(), s = e.getValue().toLowerCase(Locale.ROOT);
                if (!key.startsWith(fw) || key.length() == n || s.equals(w) || personal.contains(s)) continue;
                if (s.equals(a) || s.equals(b)) continue;
                if (a == null || s.length() < a.length()) { b = a; a = s; }
                else if (b == null || s.length() < b.length()) b = s;
            }
            if (a != null) put(cand, a, PERSONAL_SCORE + pen + 0.1 * (a.length() - n));
            if (b != null) put(cand, b, PERSONAL_SCORE + pen + 0.1 * (b.length() - n));
        }
        fuzzyCompletions(w, cand);
        List<Map.Entry<String, Double>> list = new ArrayList<>(cand.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Double>>() {
            @Override public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(a.getValue(), b.getValue());
            }
        });
        if (res.correction != null) res.suggestions.add(res.correction);
        if (hint != null && !hint.equals(res.correction)) res.suggestions.add(hint);
        for (Map.Entry<String, Double> e : list) {
            if (res.suggestions.size() >= MAX_SUGGESTIONS) break;
            String s = e.getKey();
            if (s.equals(w) || vetoed(s) || res.suggestions.contains(s)) continue;
            res.suggestions.add(s);
        }
        return res;
    }

    /**
     * Palabra de la que lo escrito seria una falta de ortografia corriente (una letra por otra que suena igual, una
     * hache de mas o de menos, erre o ele sencilla por doble) y que es muchisimo mas frecuente que la lectura con
     * tildes, de puntuacion {@code markedScore}: su ln del rango queda al menos {@link #SPELLING_MARGIN} por debajo.
     * Null si no hay ninguna. Los deslices de teclado no cuentan: olvidar la tilde es mucho mas corriente.
     */
    private String spellingRival(String w, double markedScore) {
        int n = w.length();
        String best = null;
        double bestScore = markedScore - SPELLING_MARGIN;
        for (int i = 0; i <= n; i++) {
            for (int k = 0; k < 4; k++) {
                String v;
                if (k == 0) {                                // una hache de mas
                    if (i >= n || w.charAt(i) != 'h') continue;
                    v = w.substring(0, i) + w.substring(i + 1);
                } else if (k == 1) {                         // la hache que falta
                    v = w.substring(0, i) + 'h' + w.substring(i);
                } else if (k == 2) {                         // erre o ele sencilla por doble
                    if (i >= n || (w.charAt(i) != 'r' && w.charAt(i) != 'l')) continue;
                    v = w.substring(0, i) + w.charAt(i) + w.substring(i);
                } else {                                     // una letra por otra que suena igual
                    if (i >= n) continue;
                    char c = w.charAt(i);
                    for (int a = 0; a < alphabet.length(); a++) {
                        char ch = alphabet.charAt(a);
                        if (ch == c || !(PHONETIC.contains("" + c + ch) || PHONETIC.contains("" + ch + c)
                                || (c == 'n' && ch == 'ñ'))) continue;
                        if (soft(c, ch) && !(i + 1 < n && (w.charAt(i + 1) == 'e' || w.charAt(i + 1) == 'i'))) continue;
                        String r = resolve(w.substring(0, i) + ch + w.substring(i + 1));
                        if (r != null && !vetoed(r) && score(r, 0) <= bestScore) { best = r; bestScore = score(r, 0); }
                    }
                    continue;
                }
                String r = resolve(v);
                if (r != null && !vetoed(r) && score(r, 0) <= bestScore) { best = r; bestScore = score(r, 0); }
            }
        }
        return best;
    }

    /** Pareja que solo suena igual delante de e o i: c y s, c y z, g y j ("nesesito", "haser", "jente"; no "colo"). */
    private static boolean soft(char a, char b) {
        if (a > b) { char t = a; a = b; b = t; }
        return (a == 'c' && (b == 's' || b == 'z')) || (a == 'g' && b == 'j');
    }

    /**
     * Palabra del nucleo de la que lo escrito seria una errata corriente (tecla vecina, letras bailadas, letra que
     * falta o sobra: todo lo de {@link #edits} menos una letra cambiada por otra lejana) y que debe ganarle a la
     * lectura con tildes {@code marked}, o null. Si esa lectura es de fuera del nucleo basta con que la correccion
     * normal se aplicara sola ({@link #limit}): hacia una forma rara no se corrige habiendo una frecuente tan cerca.
     * Si es del nucleo, la otra tiene que ser mucho mas frecuente ({@link #TILDE_MARGIN}). Con {@code marked}
     * igual a lo escrito (una palabra rara ya valida) devuelve la frecuente que se ofrece como sugerencia.
     * {@code one} son las variantes a una edicion de lo escrito con su puntuacion.
     */
    private String slipRival(String w, String marked, Map<String, Double> one) {
        int rank = rankOf(marked);
        if (rank < 0) return null;
        double top = limit(w.length());
        double own = rank < core ? Math.log(rank + 1) : Double.MAX_VALUE;
        String best = null;
        for (Map.Entry<String, Double> e : one.entrySet()) {
            String c = e.getKey();
            double sc = e.getValue();
            if (sc > top) continue;
            int r = rankOf(c);
            if (r < 0 || r >= core) continue;
            double cost = sc - Math.log(r + 1);
            if (cost > SLIP_EDIT + 1e-9 || sc > own - (cost <= CHEAP_EDIT + 1e-9 ? TILDE_MARGIN : TILDE_MARGIN_FAR)) continue;
            if (c.equals(w) || c.equals(marked) || vetoed(c) || sameFolded(c, w)) continue;
            best = c;
            top = sc;
        }
        return best;
    }

    /**
     * Variantes a una edicion con la penalizacion de cada tipo de error: letra repetida de mas (barata),
     * letra de mas, dos letras invertidas, letra cambiada por una vecina o de sonido parecido (barata)
     * o por otra cualquiera (cara), letra de menos (barata si era una doble).
     */
    private void edits(String w, Map<String, Double> cand) {
        int n = w.length();
        for (int i = 0; i < n; i++) {
            boolean doubled = (i > 0 && w.charAt(i) == w.charAt(i - 1)) || (i + 1 < n && w.charAt(i) == w.charAt(i + 1));
            offer(cand, w.substring(0, i) + w.substring(i + 1), doubled ? 0.4 : 2.0);
        }
        for (int i = 0; i + 1 < n; i++) {
            if (w.charAt(i) == w.charAt(i + 1)) continue;
            offer(cand, w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) + w.substring(i + 2), 0.6);
        }
        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);
            for (int a = 0; a < alphabet.length(); a++) {
                char ch = alphabet.charAt(a);
                if (ch == c) continue;
                // una ene por una eñe ("ninos", "manana") es falta corriente; al reves no, y a dos ediciones abriria
                // demasiadas palabras frecuentes con eñe ("senir" no es "señor")
                boolean cheap = close(c, ch) || (spanish && c == 'n' && ch == 'ñ');
                offer(cand, w.substring(0, i) + ch + w.substring(i + 1), cheap ? 0.4 : 2.5);
            }
        }
        for (int i = 0; i <= n; i++) {
            for (int a = 0; a < alphabet.length(); a++) {
                char ch = alphabet.charAt(a);
                boolean doubled = (i > 0 && w.charAt(i - 1) == ch) || (i < n && w.charAt(i) == ch);
                offer(cand, w.substring(0, i) + ch + w.substring(i), doubled ? 0.3 : 1.0);
            }
        }
    }

    /**
     * Busqueda a dos ediciones: palabras del nucleo ({@link #coreSize}; las raras de mas alla no llegarian al limite de
     * puntuacion y recorrerlas triplicaria el coste) de longitud parecida que empiezan por la misma letra, con la
     * distancia ponderada (los mismos costes que las variantes a una edicion) y corte temprano. Las palabras
     * aprendidas del usuario (como mucho unos cientos) y el lexico Star Wars (unas setecientas entradas) se
     * recorren con el mismo criterio; hacia las entradas largas del lexico se admite un coste mayor.
     */
    private void scan(String w, Map<String, Double> cand) {
        String a = fold(w);
        char first = a.charAt(0);
        int n = a.length();
        double[][] dp = new double[n + 1][n + 4];
        for (int len = Math.max(2, n - 2); len <= Math.min(byLength.length - 1, n + 2); len++) {
            for (String word : byLength[len]) {
                if (plain(word.charAt(0), foldUmlaut) != first) continue;
                String b = fold(word);
                double cost = distance(a, b, ED2_CUTOFF, dp);
                if (cost <= 0 || cost > ED2_CUTOFF) continue;
                put(cand, word, score(word, cost));
            }
        }
        for (String p : personal) {
            int len = p.length();
            if (len < 2 || len < n - 2 || len > n + 2 || plain(p.charAt(0), foldUmlaut) != first) continue;
            double cost = distance(a, fold(p), ED2_CUTOFF, dp);
            if (cost <= 0 || cost > ED2_CUTOFF) continue;
            put(cand, p, score(p, cost));
        }
        if (!starWars) return;
        for (int i = 0; i < swWords.length; i++) {
            String s = swWords[i];
            int len = s.length();
            if (len < n - 2 || len > n + 2 || swFolded[i].charAt(0) != first || !saga(s)) continue;
            double cutoff = len > 5 ? SW_ED2_CUTOFF : ED2_CUTOFF;
            double cost = distance(a, swFolded[i], cutoff, dp);
            if (cost <= 0 || cost > cutoff) continue;
            put(cand, s, score(s, cost));
        }
    }

    /** Cambiar x por y es un error probable: tecla vecina o letra de sonido parecido (tabla precalculada del alfabeto). */
    private boolean close(char x, char y) {
        return x < 256 && y < 256 && closeTable[x << 8 | y];
    }

    private static double deleteCost(String a, int i) {
        boolean doubled = (i > 0 && a.charAt(i) == a.charAt(i - 1)) || (i + 1 < a.length() && a.charAt(i) == a.charAt(i + 1));
        return doubled ? 0.4 : 2.0;
    }

    private static double insertCost(String b, int j) {
        boolean doubled = (j > 0 && b.charAt(j) == b.charAt(j - 1)) || (j + 1 < b.length() && b.charAt(j) == b.charAt(j + 1));
        return doubled ? 0.3 : 1.0;
    }

    /**
     * Distancia de edicion ponderada entre lo escrito {@code a} y la palabra {@code b} (ambas sin tildes):
     * Damerau-Levenshtein con el coste de cada tipo de error. Devuelve MAX_VALUE si supera el corte.
     */
    private double distance(String a, String b, double cutoff, double[][] d) {
        int n = a.length(), m = b.length();
        d[0][0] = 0;
        for (int j = 1; j <= m; j++) d[0][j] = d[0][j - 1] + insertCost(b, j - 1);
        for (int i = 1; i <= n; i++) {
            d[i][0] = d[i - 1][0] + deleteCost(a, i - 1);
            double rowMin = d[i][0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                double best = d[i - 1][j - 1] + (ca == cb ? 0 : close(ca, cb) ? 0.4 : 2.5);
                best = Math.min(best, d[i - 1][j] + deleteCost(a, i - 1));
                best = Math.min(best, d[i][j - 1] + insertCost(b, j - 1));
                if (i > 1 && j > 1 && ca != cb && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    best = Math.min(best, d[i - 2][j - 2] + 0.6);
                }
                d[i][j] = best;
                if (best < rowMin) rowMin = best;
            }
            if (rowMin > cutoff) return Double.MAX_VALUE;
        }
        return d[n][m];
    }

    /** Completados aunque el prefijo lleve un error: variantes a una edicion que sean prefijo de alguna palabra. */
    private void fuzzyCompletions(String w, Map<String, Double> cand) {
        int n = w.length();
        if (n < 3) return;
        HashSet<String> variants = new HashSet<>();
        for (int i = 0; i < n; i++) variants.add(w.substring(0, i) + w.substring(i + 1));
        for (int i = 0; i + 1 < n; i++) variants.add(w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) + w.substring(i + 2));
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < alphabet.length(); a++) {
                char ch = alphabet.charAt(a);
                if (close(w.charAt(i), ch)) variants.add(w.substring(0, i) + ch + w.substring(i + 1));
            }
        }
        for (int i = 0; i <= n; i++) {
            for (int a = 0; a < alphabet.length(); a++) variants.add(w.substring(0, i) + alphabet.charAt(a) + w.substring(i));
        }
        for (String v : variants) {
            if (v.length() < 3 || v.equals(w)) continue;
            for (String s : completions(v, 2)) put(cand, s, score(s, 1.2));
        }
        for (String p : personal) {                      // aprendidas que extienden alguna variante
            if (p.equals(w)) continue;
            for (int len = Math.max(3, n - 1); len <= n + 1 && len < p.length(); len++) {
                if (variants.contains(p.substring(0, len))) { put(cand, p, score(p, 1.2)); break; }
            }
        }
        if (!starWars) return;
        // Entradas del lexico Star Wars que extienden una variante de igual longitud o una letra mas larga (no la de
        // una letra de menos: para una palabra completa como "hola" abriria todas las que empiezan por "hol"), con
        // mas penalizacion que las aprendidas porque son muchas mas
        for (String s : swWords) {
            if (s.length() <= n) continue;
            for (int len = n; len <= n + 1 && len < s.length(); len++) {
                if (variants.contains(s.substring(0, len))) { if (saga(s)) put(cand, s, score(s, 2.0)); break; }
            }
        }
    }

    /**
     * Anota la variante si existe (o su forma con tildes), con puntuacion ln(rango) + penalizacion; si es una
     * palabra aprendida o del lexico Star Wars, con {@link #PERSONAL_SCORE} (o {@link #SW_LONG_SCORE}) + penalizacion.
     */
    private void offer(Map<String, Double> cand, String variant, double penalty) {
        String r = resolve(variant, false);              // a una variante no se le quita la tilde: solo a lo escrito
        if (r != null) put(cand, r, score(r, penalty));
        String p = personalWord(variant);
        if (p != null && !p.equals(r)) put(cand, p, score(p, penalty));
        String s = swWord(variant);
        if (s != null && !s.equals(r) && !s.equals(p)) put(cand, s, score(s, penalty));
    }

    private static void put(Map<String, Double> cand, String word, double score) {
        Double old = cand.get(word);
        if (old == null || score < old) cand.put(word, score);
    }

    /**
     * Palabras mas frecuentes que empiezan por el prefijo: una busqueda binaria en el orden alfabetico da el principio
     * del tramo, otra (a saltos, porque suele ser corto) el final, y de cada bloque de {@link #BLOCK} entradas solo se
     * mira dentro si su mejor rango puede entrar entre las elegidas. El coste casi no depende de cuantas palabras
     * compartan el prefijo, y la mayoria de las consultas (prefijos que nadie lleva) acaban en la primera busqueda.
     */
    List<String> completions(String prefix, int max) {
        if (prefix.length() < 2) return new ArrayList<>();
        return complete(prefix, max);
    }

    /** Como {@link #completions} pero tambien con una sola letra (lo que se ofrece al empezar una palabra). */
    List<String> complete(String prefix, int max) {
        List<String> out = new ArrayList<>();
        if (prefix.isEmpty()) return out;
        int lo = 0, hi = alpha.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (byRank[alpha[mid]].compareTo(prefix) < 0) lo = mid + 1; else hi = mid;
        }
        if (lo >= alpha.length || !byRank[alpha[lo]].startsWith(prefix)) return out;
        int step = 1;
        while (lo + step < alpha.length && byRank[alpha[lo + step]].startsWith(prefix)) step <<= 1;
        int end = lo + (step >> 1) + 1;                    // la ultima que se sabe que empieza asi, mas uno
        hi = Math.min(alpha.length, lo + step);
        while (end < hi) {
            int mid = (end + hi) >>> 1;
            if (byRank[alpha[mid]].startsWith(prefix)) end = mid + 1; else hi = mid;
        }
        int[] best = new int[max];
        Arrays.fill(best, Integer.MAX_VALUE);
        int i = lo;
        while (i < end) {
            int stop = Math.min(end, (i | (BLOCK - 1)) + 1);
            if ((i & (BLOCK - 1)) == 0 && stop - i == BLOCK && alphaMin[i >> BLOCK_BITS] >= best[max - 1]) {
                i = stop;                                // bloque entero sin nada mejor que lo ya elegido
                continue;
            }
            for (; i < stop; i++) {
                int r = alpha[i];
                if (r >= best[max - 1]) continue;
                String s = byRank[r];
                if (s.equals(prefix) || vetoed(s)) continue;
                int j = max - 1;
                while (j > 0 && best[j - 1] > r) { best[j] = best[j - 1]; j--; }
                best[j] = r;
            }
        }
        for (int r : best) if (r != Integer.MAX_VALUE) out.add(byRank[r]);
        return out;
    }
}
