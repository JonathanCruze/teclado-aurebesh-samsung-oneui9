package com.aurebesh.teclado;

import android.app.Application;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Diccionario espanol ampliado (words_es.txt, que genera tools/build_dictionaries.py con tools/build_dict_es.py): por
 * contenido y no por tamano exacto. Conjugaciones de verbos regulares e irregulares en todos los tiempos y personas
 * ("ustedes" incluida), verbos con pronombres, sustantivos con plural, femenino, diminutivo y aumentativo, adverbios en
 * -mente y mexicanismos; que las erratas corrientes se siguen corrigiendo (erratas_es.tsv: quinientas erratas reales
 * con su correccion) y que la tilde manda; que no entran formas basura; que las raras ni se proponen como correccion
 * ni entran en el deslizado; y que el ingles y el aleman siguen con sus 40 000 palabras.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class DiccionarioEsTest {

    private Dictionary es;

    @Before
    public void setUp() throws IOException {
        es = Dictionary.get((Application) RuntimeEnvironment.getApplication(), "es");
        es.setPersonal(null);                              // el diccionario se comparte entre pruebas
        es.setStarWars(false);
    }

    @After
    public void tearDown() {
        es.setPersonal(null);
        es.setStarWars(false);
    }

    /** Todas las palabras (separadas por espacios) figuran en la lista y, escritas tal cual, se respetan. */
    private void conocidas(String que, String palabras) {
        List<String> faltan = new ArrayList<>();
        for (String w : palabras.trim().split("\\s+")) {
            if (es.rankOf(w) < 0) { faltan.add(w); continue; }
            Dictionary.Result r = es.suggest(w);
            assertTrue(que + ": " + w + " deberia ser conocida", r.known);
        }
        assertTrue(que + ", faltan: " + faltan, faltan.isEmpty());
    }

    @Test
    public void verbosRegularesEnTodosLosTiemposYPersonas() {
        conocidas("hablar", "hablo hablas habla hablamos hablan hablé hablaste habló hablaron hablaba hablabas hablábamos"
                + " hablaban hablaré hablarás hablará hablaremos hablarán hablaría hablarías hablaríamos hablarían hable"
                + " hables hablemos hablen hablara hablaras habláramos hablaran hablando hablado hablar");
        conocidas("comer", "como comes come comemos comen comí comiste comió comimos comieron comía comías comíamos"
                + " comían comeré comerás comerá comeremos comerán comería comerías comeríamos comerían coma comas comamos"
                + " coman comiera comieras comiéramos comieran comiendo comido comer");
        conocidas("vivir", "vivo vives vive vivimos viven viví viviste vivió vivieron vivía vivías vivíamos vivían viviré"
                + " vivirás vivirá viviremos vivirán viviría vivirías viviríamos vivirían viva vivas vivamos vivan viviera"
                + " vivieras viviéramos vivieran viviendo vivido");
        // verbos de uso medio, que la lista anterior traia a medias: tambien completos
        conocidas("platicar", "platico platicas platica platicamos platican platiqué platicaste platicó platicaron"
                + " platicaba platicábamos platicaré platicarás platicará platicaremos platicarán platicaría platique"
                + " platiques platiquemos platiquen platicara platicando platicado");
        conocidas("regresar", "regreso regresas regresa regresamos regresan regresé regresaste regresó regresaron"
                + " regresaré regresarás regresará regresaremos regresarán regresaría regresaríamos regrese regreses"
                + " regresemos regresen regresara regresáramos regresando regresado");
        conocidas("manejar y cocinar", "manejo manejas manejamos manejan manejé manejaste manejó manejaron manejaré"
                + " manejarán manejaría maneje manejen manejando cocino cocinas cocinamos cocinan cociné cocinaste cocinó"
                + " cocinaron cocinaré cocinarás cocinaremos cocinarán cocinaría cocine cocinen cocinando cocinado");
        conocidas("desayunar y almorzar", "desayuno desayunas desayunamos desayunan desayuné desayunaste desayunó"
                + " desayunaron desayunaré desayunaremos desayunemos desayunando almuerzo almuerzas almorzamos almuerzan"
                + " almorcé almorzaste almorzó almorzaron almorzaremos almorcemos almorzando");
    }

    @Test
    public void verbosIrregulares() {
        conocidas("tener", "tengo tienes tiene tenemos tienen tuve tuviste tuvo tuvimos tuvieron tendré tendrás tendrá"
                + " tendremos tendrán tendría tendríamos tenga tengas tengamos tengan tuviera tuvieras tuviéramos tuvieran"
                + " teniendo tenido ten");
        conocidas("hacer", "hago haces hace hacemos hacen hice hiciste hizo hicimos hicieron haré harás hará haremos"
                + " harán haría haríamos haga hagas hagamos hagan hiciera hicieras hiciéramos hicieran haciendo hecho haz");
        conocidas("decir", "digo dices dice decimos dicen dije dijiste dijo dijimos dijeron diré dirás dirá diremos dirán"
                + " diría diríamos diga digas digamos digan dijera dijeras dijéramos dijeran diciendo dicho di");
        conocidas("ir", "voy vas va vamos van fui fuiste fue fuimos fueron iré irás irá iremos irán iría iríamos vaya"
                + " vayas vayamos vayan fuera fueras fuéramos fueran yendo ido ve");
        conocidas("poder y querer", "puedo puedes puede podemos pueden pude pudiste pudo pudimos pudieron podré podrás"
                + " podrá podremos podrán podría pueda puedas podamos puedan pudiera pudieras pudiéramos pudieran pudiendo"
                + " quiero quieres quiere queremos quieren quise quisiste quiso quisimos quisieron querré querrás querrá"
                + " querremos querrán querría quiera quieras queramos quieran quisiera quisieras quisiéramos quisieran");
        conocidas("poner, venir y salir", "pongo pones ponemos ponen puse pusiste puso pusimos pusieron pondré pondrás"
                + " pondrá pondremos pondrán ponga pongas pongamos pongan pusiera poniendo puesto pon vengo vienes viene"
                + " venimos vienen vine viniste vino vinimos vinieron vendré vendrás vendrá vendremos vendrán venga vengas"
                + " vengamos vengan viniera ven salgo sales salimos salen salí saliste salió salieron saldré saldrás saldrá"
                + " saldremos saldrán salga salgas salgamos salgan sal");
        conocidas("dar, traer, dormir, pedir, conducir, caber", "doy das da damos dan diste dio dimos dieron daré darás"
                + " dará daremos darán dé des demos den diera dando dado traigo traes trae traemos traen traje trajiste"
                + " trajo trajimos trajeron traeré traiga traigas traigamos traigan trajera trayendo traído duermo duermes"
                + " duerme dormimos duermen dormí dormiste durmió durmieron dormiré duerma durmamos durmiendo dormido pido"
                + " pides pide pedimos piden pedí pediste pidió pidieron pediré pida pidamos pidiendo pedido conduzco"
                + " conduje condujo condujeron conduzca quepo cupo cabrá");
    }

    @Test
    public void verbosConPronombres() {
        conocidas("imperativos", "dime dímelo dame dámelo dámela dale dáselo hazlo hazme házmelo ponlo ponte póntelo vete"
                + " vente vámonos váyanse váyase siéntate siéntese siéntense cállate cállese cállense espérame espérate"
                + " espérense avísame avísale márcame márcale mándame mándamelo mándale pásame pásamelo pásale tráeme"
                + " tráemelo llámame llámale llámalo escríbeme búscame ayúdame ayúdale perdóname discúlpame créeme mírame"
                + " mírala míralo óyeme escúchame cuídate cálmate apúrate apúrale fíjate acuérdate imagínate párate"
                + " quítate muévete duérmete despiértate levántate báñate relájate diviértete pórtate córrele éntrale"
                + " échale síguele bájale súbele");
        conocidas("infinitivos y gerundios", "hacerlo hacerla hacerte hacerme hacernos hacerse hacérselo decirlo decirle"
                + " decirte decirme decírtelo decírselo verlo verla verte verme vernos darle darte darme dártelo dárselo"
                + " irse irme irte irnos haciéndolo diciéndole viéndolo dándole pensándolo esperándote buscándote quedarme"
                + " quedarte quedarse quedarnos sentarme sentarse levantarme acordarme acordarte bañarme dormirme");
    }

    @Test
    public void sustantivosAdjetivosYAdverbios() {
        conocidas("plurales y femeninos", "casa casas perro perros perra perras gato gatos gata gatas amigo amigos amiga"
                + " amigas mujer mujeres hombre hombres ciudad ciudades país países lápiz lápices luz luces pez peces flor"
                + " flores canción canciones camión camiones corazón corazones doctor doctora doctores doctoras maestro"
                + " maestra maestros maestras profesor profesora profesores profesoras sobrino sobrina sobrinos sobrinas");
        conocidas("diminutivos", "casita casitas perrito perritos perrita gatito gatitos amiguito amiguitos amiguita"
                + " chiquito chiquita chiquitos chiquitas chiquitito mujercita hombrecito trabajito problemita ratito"
                + " momentito favorcito cafecito pancito agüita manita manitas cabecita corazoncito cancioncita camioncito"
                + " lucecita pececito florecita abuelita abuelito mamita mamacita papito papacito hermanito hermanita"
                + " hijito hijita mensajito tiendita");
        conocidas("aumentativos y superlativos", "grandote grandota grandotes problemón cabezón panzón fiestón golpazo"
                + " cochazo carrazo cuerpazo buenísimo buenísima riquísimo carísimo muchísimo muchísimas poquísimo"
                + " rapidísimo facilísimo padrísimo");
        conocidas("adverbios y compuestos", "rápidamente fácilmente lentamente realmente tranquilamente seguramente"
                + " exnovio exnovia exesposa superhéroe");
    }

    @Test
    public void espanolDeMexico() {
        conocidas("trato", "ahorita ahoritita órale ándale híjole épale chale nel mande nomás orita güey güeyes wey cuate"
                + " cuates compa compas chavo chava chavos chavas chamaco chamaca chamacos escuincle escuincles morro morra"
                + " vato vatos mijo mija mijito mijita güero güera chaparro chaparrito naco fresa menso mensa metiche");
        conocidas("comida", "tortilla tortillas quesadilla quesadillas enchilada enchiladas taco tacos torta tortas tamal"
                + " tamales pozole mole chile chiles frijol frijoles nopal nopales elote elotes jitomate jitomates aguacate"
                + " aguacates camote guajolote chilaquiles totopos tinga arrachera sope sopes gordita gorditas antojitos"
                + " botana botanas chela chelas michelada caguama tequila mezcal pulque tepache popote popotes jícama"
                + " tamarindo tuna");
        conocidas("casa y calle", "camión camiones banqueta banquetas alberca albercas colonia cuadra cuadras glorieta"
                + " camellón tope topes bache baches tianguis abarrotes papelería tortillería taquería tlapalería"
                + " rosticería zócalo fraccionamiento carro carros camioneta troca pesero combi llanta llantas cajuela"
                + " claxon estacionamiento gasolinera credencial celular celulares computadora computadoras compu refri"
                + " regadera estufa trapeador foco focos apagador cobija cobijas recámara chamarra chamarras sudadera"
                + " playera playeras huarache huaraches chancla chanclas brasier mochila lonche lonchera prepa secu kínder");
        conocidas("relajo", "chamba chambas chido chida chidos chidas padrísimo chafa gacho neta desmadre relajo pachanga"
                + " peda cruda crudo fiestón reventón antro antros mariachi mariachis charro charros chilango chilangos"
                + " lana feria varo varos chisme chismes chismoso berrinche apapacho madrazo trancazo cachetada zape hueva"
                + " lueguito tantito arroba");
        conocidas("verbos", "platicar checar checo checas checa checamos checan chequé checaste checó checaron checaré"
                + " checará cheque chequen checando checado chécalo chambear chambeo chambeas chambea chambeamos chambean"
                + " chambeé chambeaste chambeó chambearon chambearé chambeando apapachar trapear manejar estacionar"
                + " chismear cotorrear");
    }

    @Test
    public void laListaDeSiempreVaDelanteYLasOtrasNoCambian() throws IOException {
        Application app = (Application) RuntimeEnvironment.getApplication();
        assertEquals("de", es.word(0));
        assertEquals("que", es.word(1));
        assertEquals("no", es.word(2));
        assertTrue("el nucleo son las 40 000 de siempre: " + es.rankOf("jueguitos"), es.rankOf("jueguitos") < Dictionary.CORE);
        assertTrue("las formas nuevas van detras: " + es.rankOf("platicaré"), es.rankOf("platicaré") >= Dictionary.CORE - 100);
        assertTrue("lista ampliada: " + es.size(), es.size() > 100000 && es.size() < 160000);
        assertEquals(Dictionary.CORE, es.coreSize());
        for (String lang : new String[]{"en", "de"}) {
            Dictionary d = Dictionary.get(app, lang);
            assertEquals(lang + " no cambia de tamano", 40000, d.size());
            assertEquals(lang, 40000, d.coreSize());
        }
        assertEquals("you", Dictionary.get(app, "en").word(0));
        assertEquals("ich", Dictionary.get(app, "de").word(0));
    }

    @Test
    public void lasErratasClaveSeSiguenCorrigiendo() {
        String[][] casos = {
                {"qeu", "que"}, {"porqe", "porque"}, {"nesesito", "necesito"}, {"nesecito", "necesito"},
                {"tambien", "también"}, {"cancion", "canción"}, {"mas", "más"}, {"holq", "hola"}, {"holla", "hola"},
                {"fuersa", "fuerza"}, {"qiero", "quiero"}, {"probalemnte", "probablemente"}, {"grasias", "gracias"},
                {"exelente", "excelente"}, {"travez", "través"}, {"tanbien", "también"}, {"uste", "usted"},
                {"desicion", "decisión"}, {"entonce", "entonces"}, {"ablando", "hablando"}, {"carera", "carrera"},
                {"vedad", "verdad"}, {"cunado", "cuando"}};
        for (String[] c : casos) {
            Dictionary.Result r = es.suggest(c[0]);
            assertEquals(c[0] + " " + r.suggestions, c[1], r.correction);
        }
        // demasiado lejos para cambiarla sin preguntar, pero no es una palabra y la buena va la primera
        assertFalse(es.suggest("ensenar").known);
        assertEquals("enseñar", es.suggest("ensenar").suggestions.get(0));
        // palabras validas que se parecen a otra: se respetan
        for (String w : new String[]{"ola", "hola", "esta", "si", "solo", "aun", "halla", "haya", "tubo", "tuvo", "echo",
                "hecho", "hablo", "llego", "paso", "nevó", "querremos", "chido", "güera", "charro", "borro"}) {
            Dictionary.Result r = es.suggest(w);
            assertTrue(w, r.known);
            assertNull(w + " -> " + r.correction, r.correction);
        }
    }

    @Test
    public void laTildeManda() {
        // a lo escrito solo le faltan las tildes: se le ponen aunque la forma sea rara o haya otra palabra a una tecla
        String[][] casos = {
                {"platicabamos", "platicábamos"}, {"comiamos", "comíamos"}, {"deciamos", "decíamos"},
                {"llegare", "llegaré"}, {"caminare", "caminaré"}, {"cocinare", "cocinaré"}, {"marcare", "marcaré"},
                {"comio", "comió"}, {"pasamelo", "pásamelo"}, {"mandamelo", "mándamelo"}, {"marcame", "márcame"},
                {"esperate", "espérate"}, {"sabiendolo", "sabiéndolo"}, {"pasandola", "pasándola"},
                {"helicoptero", "helicóptero"}, {"exhibicion", "exhibición"}, {"verguenza", "vergüenza"}};
        for (String[] c : casos) {
            Dictionary.Result r = es.suggest(c[0]);
            assertEquals(c[0] + " " + r.suggestions, c[1], r.correction);
        }
        // salvo que sea una falta de ortografia de una palabra muchisimo mas frecuente
        assertEquals("parece", es.suggest("parese").correction);        // no "párese"
        assertEquals("guerra", es.suggest("guera").correction);         // no "güera"
        // y entre dos formas validas decide la frecuencia, como siempre
        assertNull(es.suggest("esta").correction);
        assertEquals("más", es.suggest("mas").correction);
        // pero la que se escribe con su tilde se respeta, por mucho mas frecuente que sea la otra
        for (String w : new String[]{"publicó", "criticó", "vació", "animó", "tituló", "pronosticó", "circuló", "envió",
                "confió", "terminó", "público", "crítico", "vacío", "ánimo", "título", "está", "más", "papá"}) {
            Dictionary.Result r = es.suggest(w);
            assertTrue(w, r.known);
            assertNull(w + " -> " + r.correction, r.correction);
        }
        // el futuro de subjuntivo no esta en la lista: es el futuro escrito sin tilde
        assertTrue(es.rankOf("compraré") >= 0);
        assertEquals(-1, es.rankOf("comprare"));
        assertEquals("compraré", es.suggest("comprare").correction);
    }

    @Test
    public void erratasRealesNoPeorQueConLaListaAnterior() throws IOException {
        // Aciertos por clase con la lista de 40 000 palabras y el corrector anterior (medidos con DiccBench): el suelo
        Map<String, Integer> suelo = new HashMap<>();
        suelo.put("tilde", 174);
        suelo.put("tilde-rara", 45);
        suelo.put("desliz", 66);
        suelo.put("ortografia", 132);
        suelo.put("se-respetan", 45);
        Map<String, int[]> cuenta = new HashMap<>();
        StringBuilder fallos = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/erratas_es.tsv"), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\t");
                Dictionary.Result res = es.suggest(p[0]);
                String got = res.correction != null ? res.correction : res.known ? p[0] : "";
                int[] c = cuenta.get(p[2]);
                if (c == null) cuenta.put(p[2], c = new int[2]);
                c[0]++;
                if (got.equals(p[1])) c[1]++;
                else if (suelo.containsKey(p[2])) fallos.append(' ').append(p[0]).append("->").append(got);
            }
        } finally {
            r.close();
        }
        int total = 0;
        for (Map.Entry<String, Integer> e : suelo.entrySet()) {
            int[] c = cuenta.get(e.getKey());
            System.out.println("ERRATAS " + e.getKey() + " " + c[1] + "/" + c[0] + " (antes " + e.getValue() + ")");
            assertTrue(e.getKey() + ": " + c[1] + " de " + c[0] + ", antes " + e.getValue() + ";" + fallos, c[1] >= e.getValue());
            total += c[1];
        }
        assertTrue("en conjunto mejora (antes 462): " + total, total >= 478);
        assertTrue("al menos 500 erratas reales", cuenta.get("tilde")[0] + cuenta.get("tilde-rara")[0]
                + cuenta.get("desliz")[0] + cuenta.get("ortografia")[0] >= 500);
    }

    @Test
    public void lasFormasBasuraNoEntran() {
        String[] basura = {
                // erratas corrientes que el diccionario de referencia daria por validas
                "ablando", "carera", "ensenar", "enseno", "montanas", "suenos", "escusa", "entonce", "vedad", "cunado",
                "levar", "legar", "lamas", "avía", "segur", "onde", "mejo", "paree", "festa",
                // la forma sin tilde de otra mucho mas frecuente, y el futuro de subjuntivo
                "japones", "genero", "transito", "solido", "merito", "dialogo", "catalogo", "musculo", "vinculo",
                "fabricas", "unicamente", "comprare", "llegare", "necesitare", "agachate", "apresurate",
                // erratas de siempre, con y sin tilde
                "nesesito", "nesecito", "qeu", "haver", "porqe", "exelente", "travez", "desición", "usté", "depués",
                "iqué", "yqué",
                // lo que las reglas de derivacion no deben dar por bueno
                "llevarón", "televisón", "pedazito", "amigito", "posiones", "aeropuesto", "idota", "esquisita",
                "pobresita", "especiamente", "murillo", "hermosillo", "sedona", "remote", "vanilla", "tranquillo",
                // palabras de otros idiomas y nombres
                "toad", "guitar", "tells", "oven", "bread", "salad", "responded", "creme", "federica", "palatino"};
        List<String> dentro = new ArrayList<>();
        for (String w : basura) if (es.rankOf(w) >= 0) dentro.add(w);
        assertTrue("no deberian estar: " + dentro, dentro.isEmpty());
    }

    @Test
    public void lasFormasRarasSeCompletanPeroNoSeImponen() {
        // completar: las tres mas frecuentes con ese prefijo, sea cual sea su numero (antes se miraban 4000 como mucho)
        for (String prefijo : new String[]{"de", "co", "es", "re", "pla", "plati", "chec", "desa", "quer", "cocin"}) {
            List<String> esperadas = new ArrayList<>();
            for (int r = 0; r < es.size() && esperadas.size() < 3; r++) {
                String w = es.word(r);
                if (w.startsWith(prefijo) && !w.equals(prefijo) && !Dictionary.noSuggest(w)) esperadas.add(w);
            }
            assertEquals(prefijo, esperadas, es.completions(prefijo, 3));
        }
        // una forma rara se ofrece al completar
        assertTrue(es.rankOf("platicaríamos") >= Dictionary.CORE);
        assertTrue(es.suggest("platicaría").suggestions.toString(), es.suggest("platicaría").suggestions.contains("platicaríamos"));
        // pero una errata sigue yendo a la palabra frecuente, no a una rara que se le parezca mas
        assertEquals("gracias", es.suggest("grcias").correction);
        assertEquals("siempre", es.suggest("simpre").correction);
        assertEquals("trabajo", es.suggest("trbajo").correction);
        // las formas nuevas de las familias vetadas se respetan si se escriben, pero no se proponen
        for (String w : new String[]{"chingaste", "pendejito", "jodiéndome"}) {
            assertTrue(w, es.rankOf(w) >= Dictionary.CORE);
            assertTrue(w, es.suggest(w).known);
            String prefijo = w.substring(0, w.length() - 2);
            assertFalse(w + " al completar " + prefijo, es.completions(prefijo, 5).contains(w));
            assertFalse(w + " como sugerencia", es.suggest(prefijo).suggestions.contains(w));
        }
    }

    @Test
    public void elCosteNoCreceConLaLista() {
        // No es una medida fina (para eso esta DiccBench): solo que nada se dispara. Con la busqueda a dos ediciones sin
        // acotar, estas 400 consultas tardaban el triple.
        String[] entradas = {"hola", "qeu", "nesecito", "probalemnte", "plati", "de", "trbajo", "platicabamos", "xxzzy",
                "cocinare", "estacionamento", "refrigeradro", "gracais", "computadroa", "ahortia", "chambeando"};
        for (int i = 0; i < 50; i++) for (String w : entradas) es.suggest(w);            // calentamiento
        long t = System.nanoTime();
        for (int i = 0; i < 25; i++) for (String w : entradas) es.suggest(w);
        long ms = (System.nanoTime() - t) / 1000000;
        System.out.println("DICC 400 consultas en " + ms + " ms");
        assertTrue("400 consultas en " + ms + " ms", ms < 4000);
    }
}
