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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Diccionario espanol ampliado (words_es.txt, que genera tools/build_dictionaries.py con tools/build_dict_es.py): por
 * contenido y no por tamano exacto. Conjugaciones de verbos regulares e irregulares en todos los tiempos y personas
 * ("ustedes" incluida), verbos con pronombres, sustantivos con plural, femenino, diminutivo y aumentativo, adverbios en
 * -mente y mexicanismos; que las erratas corrientes se siguen corrigiendo (erratas_es.tsv: quinientas erratas reales
 * con su correccion) y que la tilde manda, con tope; que una forma verbal valida sin tilde no cambia de tiempo; que
 * no entran formas basura ni quedan en el nucleo las erratas de siempre; que las raras no se imponen como correccion
 * y el deslizado solo toma las mas usadas; y que el ingles y el aleman siguen con sus 40 000 palabras.
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
    public void usoDiarioQueFaltaba() {
        // verbos irregulares que el diccionario de referencia trae sueltos y a medias
        conocidas("haber, andar y caber", "hube hubiste hubo hubimos hubieron hubiera hubiéramos anduve anduviste anduvo"
                + " anduvimos anduvieron anduviera anduviéramos caber quepo cabes cabe cabemos caben cupe cupiste cupimos"
                + " cupieron cabía cabré cabría quepa quepas quepan cupiera cupieras cabiendo cabido");
        // verbos de todos los dias en Mexico, con todo su paradigma aunque los subtitulos apenas los traigan
        conocidas("verbos diarios", "agendo agendas agenda agendamos agendan agendé agendaste agendó agendaron agendaré"
                + " agendaría agende agendes agenden agendando agendado reagendar reagendamos reprogramo reprogramar"
                + " googleo googlear googleando texteo textear hackear hackea hackeando hackearon masajeo desinstalo"
                + " desinstalar trapeo trapeas trapeamos trapeé trapeando cotizo cotizas cotizó cotizamos cotizando"
                + " reenvío reenvías reenviamos reenviaste reenviando reenviaré facturo facturas facturamos facturé"
                + " facturando adjunto adjuntas adjuntamos adjunté adjuntando vacuno vacunamos vacunaron capacito"
                + " capacitamos cachó cachamos truena tronó chismeo chismeando tuiteo depositaría rentábamos"
                + " felicitábamos sobraría reiniciamos ubicaste timbro tramito tramitamos estaciono estacionamos");
        // verbos con pronombre que casi no salen en los subtitulos, y los imperativos con -le
        conocidas("pronombres", "platicarte platicarle platícale platíqueme checarte checarle checarla chécame chécale"
                + " chécala chéquelo depositarte depositarle deposítame deposítale reenvíame reenvíalo compárteme"
                + " agéndame agendarlo facturarle cotízame márcales márqueme confírmeme cancélame préstalo mándaselo"
                + " cáele cómele piénsale apréndele apágale atínale bríncale cálmale ándenle córranle jálenle"
                + " lléguenle muévanle");
        // chat, prestamos y cosas de casa
        conocidas("chat", "finde findes apá amá jeje jejeje jiji jajajaja cel depa oki plis porfis abue holis like likes"
                + " link links podcast podcasts router routers sticker stickers emoji emojis selfie selfies laptop"
                + " laptops app apps hashtag hashtags whatsapp");
        conocidas("Mexico", "bolillo bolillos tinaco tinacos plumón plumones chícharo chícharos crayón crayones champiñón"
                + " champiñones changarro garnachas apapachos jefita carnal carnala fregón tapatío morras güeros"
                + " padrísima chingos defeño gasolinería desarmador destapador engrapadora rostizado boletería"
                + " manicurista ostiones basquetbolista patineta pijamada puntaje televisora narco regaño maña megas");
        conocidas("lo que el diccionario de referencia no trae", "juguetería pescadería verdulería plastilina reembolsos"
                + " chequeo chequeos escaneos prospectos combos babuinos supernovas cocteles diabético diabética"
                + " traqueotomía trillón poquitín criminalista parlanchín calabacín celulitis fortachón reubicación"
                + " fibrosis glaucoma exhumación ginecología radiólogo urólogo odontólogo ortodoncista humidificador"
                + " mamografía reprogramación pancreatitis colonoscopía amoxicilina tendinitis gingivitis sedación");
        // ninguna de estas se cambia por otra (antes: "finde" -> "fines", "depa" -> "sepa", "links" -> "libros")
        for (String w : new String[]{"finde", "jeje", "cel", "depa", "bolillo", "porfis", "apá", "amá", "links", "plumones",
                "tinaco", "hackeando", "hackea", "cotizo", "cotizó", "trapeo", "cachó", "cabiendo", "cupieras", "cáele",
                "cómele", "piénsale", "préstalo", "chingos", "morras", "defeño"}) {
            Dictionary.Result r = es.suggest(w);
            assertTrue(w, r.known);
            assertNull(w + " -> " + r.correction, r.correction);
        }
        // y sin su tilde la reciben, como cualquier otra
        for (String[] c : new String[][]{{"checame", "chécame"}, {"depositame", "deposítame"}, {"reenviame", "reenvíame"},
                {"agendame", "agéndame"}, {"gasolineria", "gasolinería"}, {"jugueteria", "juguetería"},
                {"mamografia", "mamografía"}}) {
            assertEquals(c[0], c[1], es.suggest(c[0]).correction);
        }
        // Palabras corrientes que el lexico Star Wars trae como nombres: con el lexico activo siguen siendo palabras
        // normales, sin mayuscula forzada ("depa" no es Depa Billaba)
        conocidas("corrientes y de la saga", "depa dogma concordia rugosa separatista separatistas milenario inquisidores");
        es.setStarWars(true);
        for (String w : new String[]{"depa", "dogma", "concordia", "separatista", "milenario"}) {
            assertTrue(w, es.suggest(w).known);
            assertNull(w, es.suggest(w).correction);
            assertNull(w, es.canonical(w));
        }
        es.setStarWars(false);
    }

    @Test
    public void elDeslizadoTomaLasFormasNuevasMasUsadas() throws IOException {
        Application app = (Application) RuntimeEnvironment.getApplication();
        assertTrue(es.glideSize() > es.coreSize() && es.glideSize() < es.size());
        for (String w : new String[]{"platicamos", "depositaron", "checando", "rentamos", "estaciono"}) {
            assertTrue(w + " se puede deslizar: " + es.rankOf(w), es.rankOf(w) >= es.coreSize() && es.rankOf(w) < es.glideSize());
        }
        assertTrue("las ultimas, sin uso en los subtitulos, no", es.rankOf("chéquelo") >= es.glideSize());
        for (String lang : new String[]{"en", "de"}) assertEquals(lang, 40000, Dictionary.get(app, lang).glideSize());
    }

    @Test
    public void laListaDeSiempreVaDelanteYLasOtrasNoCambian() throws IOException {
        Application app = (Application) RuntimeEnvironment.getApplication();
        assertEquals("de", es.word(0));
        assertEquals("que", es.word(1));
        assertEquals("no", es.word(2));
        assertTrue("lista ampliada: " + es.size(), es.size() > 100000 && es.size() < 175000);
        // El nucleo es lo que queda de la lista de siempre y no un numero fijo: en el archivo lo separa de las formas
        // nuevas una linea en blanco, y de ahi lo saca el diccionario
        String ultima = null, primera = null;
        int nucleo = 0;
        boolean dentro = true;
        BufferedReader r = new BufferedReader(new InputStreamReader(app.getResources().openRawResource(R.raw.words_es), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) { dentro = false; continue; }
                if (dentro) { ultima = line; nucleo++; }
                else if (primera == null) primera = line;
            }
        } finally {
            r.close();
        }
        assertFalse("el archivo marca donde acaba el nucleo", dentro);
        assertEquals(nucleo, es.coreSize());
        assertEquals("la ultima del nucleo", ultima, es.word(es.coreSize() - 1));
        assertEquals("la primera forma nueva", primera, es.word(es.coreSize()));
        assertTrue("el nucleo son las de siempre menos sus erratas: " + nucleo, nucleo > 39000 && nucleo < 40000);
        assertTrue("jueguitos es del nucleo: " + es.rankOf("jueguitos"), es.rankOf("jueguitos") >= 0 && es.rankOf("jueguitos") < es.coreSize());
        assertTrue("las formas nuevas van detras: " + es.rankOf("platicaré"), es.rankOf("platicaré") >= es.coreSize());
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
    public void laTildeTieneTope() {
        // Lo escrito es una errata corriente de una palabra frecuente y, con tilde, una forma rara de fuera del nucleo:
        // gana la correccion de siempre, y la forma con tilde, si esta en la lista, queda como segunda sugerencia
        String[][] casos = {
                {"peto", "pero"}, {"tene", "tiene"}, {"deci", "decir"}, {"quere", "quiere"}, {"duos", "dios"},
                {"ronde", "donde"}, {"obre", "sobre"}, {"adio", "adiós"}, {"espeto", "espero"}, {"podo", "poco"},
                {"tento", "tengo"}, {"tare", "tarde"}, {"hacelo", "hacerlo"}, {"amonos", "vámonos"}, {"sias", "días"},
                {"ladre", "padre"}, {"neve", "nueve"}, {"nevo", "nuevo"}, {"tente", "gente"}, {"ficho", "dicho"}};
        for (String[] c : casos) {
            Dictionary.Result r = es.suggest(c[0]);
            assertEquals(c[0] + " " + r.suggestions, c[1], r.correction);
        }
        for (String[] c : new String[][]{{"peto", "petó"}, {"duos", "dúos"}, {"amonos", "ámonos"}, {"tento", "tentó"}}) {
            assertTrue(c[1], es.rankOf(c[1]) >= es.coreSize());
            assertEquals(c[0], c[1], es.suggest(c[0]).suggestions.get(1));
            assertNull(c[1] + " escrita con su tilde se respeta", es.suggest(c[1]).correction);
        }
        // si la forma con tilde es del nucleo, la otra tiene que ser muchisimo mas frecuente
        assertEquals("como", es.suggest("comi").correction);
        assertEquals("comí", es.suggest("comi").suggestions.get(1));
        for (String[] c : new String[][]{{"metodo", "método"}, {"funcion", "función"}, {"unicos", "únicos"},
                {"llevalo", "llévalo"}, {"matame", "mátame"}, {"movio", "movió"}, {"vendre", "vendré"}}) {
            assertEquals(c[0], c[1], es.suggest(c[0]).correction);
        }
        // sin una palabra frecuente tan cerca, la forma rara sigue recibiendo su tilde
        assertEquals("platicábamos", es.suggest("platicabamos").correction);
        assertEquals("chécalo", es.suggest("checalo").correction);
        // la tilde que sobra o esta en otra vocal tambien se arregla (y esas formas ya no estan en la lista)
        for (String[] c : new String[][]{{"dió", "dio"}, {"fué", "fue"}, {"tí", "ti"}, {"vió", "vio"}, {"exámen", "examen"},
                {"jóven", "joven"}, {"despúes", "después"}, {"díficil", "difícil"}, {"crées", "crees"}, {"véte", "vete"}}) {
            assertEquals(c[0], -1, es.rankOf(c[0]));
            assertEquals(c[0], c[1], es.suggest(c[0]).correction);
        }
    }

    @Test
    public void unVerboValidoSinTildeNoCambiaDeTiempo() {
        // "alcanzara" (subjuntivo) no es "alcanzará", ni "agendo" es "agendó", ni "facture" es "facturé": las dos formas
        // estan en la lista, la escrita se respeta y la otra se ofrece la primera
        String[][] pares = {
                {"alcanzara", "alcanzará"}, {"mejorara", "mejorará"}, {"recordaran", "recordarán"},
                {"colgaran", "colgarán"}, {"aguantara", "aguantará"}, {"castigara", "castigará"},
                {"acordaras", "acordarás"}, {"continuaran", "continuarán"}, {"tardaran", "tardarán"},
                {"extrañara", "extrañará"}, {"ahorrara", "ahorrará"}, {"funcionara", "funcionará"},
                {"agendo", "agendó"}, {"agende", "agendé"}, {"facture", "facturé"}, {"adjunte", "adjunté"},
                {"vacune", "vacuné"}, {"desenchufo", "desenchufó"}, {"tramito", "tramitó"}, {"timbro", "timbró"},
                {"estallo", "estalló"}, {"agoto", "agotó"}, {"marques", "marqués"}, {"borro", "borró"}};
        for (String[] p : pares) {
            assertTrue(p[0] + " esta en la lista", es.rankOf(p[0]) >= 0);
            assertTrue(p[1] + " esta en la lista", es.rankOf(p[1]) >= 0);
            Dictionary.Result r = es.suggest(p[0]);
            assertTrue(p[0], r.known);
            assertNull(p[0] + " -> " + r.correction, r.correction);
            if (es.rankOf(p[0]) >= es.coreSize()) assertEquals(p[0] + " " + r.suggestions, p[1], r.suggestions.get(0));
            assertNull(p[1], es.suggest(p[1]).correction);
        }
        // salvo diferencia de uso abrumadora: la forma sin tilde no entra y se le pone
        for (String[] p : new String[][]{{"amare", "amaré"}, {"genero", "género"}, {"comprare", "compraré"}, {"cascara", "cáscara"}}) {
            assertEquals(p[0], -1, es.rankOf(p[0]));
            assertEquals(p[0], p[1], es.suggest(p[0]).correction);
        }
    }

    @Test
    public void unaFormaRaraOfreceLaPalabraFrecuenteQueSeLeParece() {
        // Formas validas que estan a una tecla de una palabra muy frecuente: se respetan, y la frecuente va la primera
        // de las sugerencias, sin aplicarse sola
        for (String[] c : new String[][]{{"bañas", "balas"}, {"verbos", "vernos"}, {"causalidad", "casualidad"},
                {"legua", "lengua"}, {"sudas", "dudas"}}) {
            Dictionary.Result r = es.suggest(c[0]);
            assertTrue(c[0], r.known);
            assertNull(c[0] + " -> " + r.correction, r.correction);
            assertTrue(c[0] + " " + r.suggestions, r.suggestions.contains(c[1]));
        }
        // ... y las que casi nunca se quieren escribir no entran en la lista: se corrigen como siempre
        for (String[] c : new String[][]{{"memos", "menos"}, {"miso", "mismo"}, {"nade", "nadie"}, {"henos", "hemos"},
                {"mientra", "mientras"}, {"incuso", "incluso"}, {"docto", "doctor"}, {"naca", "nada"},
                {"llegad", "llegar"}, {"sabed", "sabes"}, {"trabajad", "trabajar"}, {"dormid", "dormir"}}) {
            assertEquals(c[0], -1, es.rankOf(c[0]));
            assertEquals(c[0], c[1], es.suggest(c[0]).correction);
        }
    }

    @Test
    public void lasPalabrasJuntasSeSeparan() {
        for (String[] c : new String[][]{{"porfavor", "por favor"}, {"talvez", "tal vez"}, {"aver", "a ver"},
                {"osea", "o sea"}, {"enserio", "en serio"}, {"aveces", "a veces"}, {"asique", "así que"},
                {"deacuerdo", "de acuerdo"}, {"denuevo", "de nuevo"}, {"nose", "no sé"}, {"llendo", "yendo"}}) {
            Dictionary.Result r = es.suggest(c[0]);
            assertEquals(c[0], -1, es.rankOf(c[0]));
            assertFalse(c[0], r.known);
            assertEquals(c[0], c[1], r.correction);
        }
        es.setPersonal(Arrays.asList("aver"));           // aprendida (desde el chip de lo escrito): se respeta
        assertTrue(es.suggest("aver").known);
        assertNull(es.suggest("aver").correction);
        // en ingles y aleman no hay nada de esto
        Dictionary en = Dictionary.cached("en");
        if (en != null) assertFalse("por favor".equals(en.suggest("porfavor").correction));
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
        // ... y las dos clases que anadio la revision, que tienen que salir enteras: el desliz de una palabra frecuente
        // que con tilde seria una forma rara, y la forma verbal valida sin tilde (antes, 37 de 40 y 21 de 100)
        suelo.put("desliz-tilde", 40);
        suelo.put("verbo-sin-tilde", 100);
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
        assertTrue("en conjunto mejora (antes 462 + 58 de las clases nuevas): " + total, total >= 462 + 140 + 40);
        assertTrue("al menos 500 erratas reales", cuenta.get("tilde")[0] + cuenta.get("tilde-rara")[0]
                + cuenta.get("desliz")[0] + cuenta.get("ortografia")[0] >= 500);
        assertTrue("las palabras juntas se separan: " + cuenta.get("juntas")[1], cuenta.get("juntas")[1] >= 15);
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
                "toad", "guitar", "tells", "oven", "bread", "salad", "responded", "creme", "federica", "palatino",
                "federicas", "jorges", "breen", "gaye", "dieses", "edit", "univ", "pdte",
                // formas que no existen y el diccionario de referencia da por conjugacion; el voseo
                "disminuamos", "construamos", "síamos", "sían", "conducí", "decí", "hacé", "tené", "queré", "necesitareis",
                "séis", "aquéllo",
                // imperativos de vosotros, a una tecla de un infinitivo o de una segunda persona
                "llegad", "sabed", "trabajad", "dormid", "pedid", "estaos", "haceos", "soltadle", "matadme",
                // erratas de la lista de siempre: sin su tilde, con una que no llevan, ortografia y palabras juntas
                "tambien", "despues", "podria", "corazon", "oir", "lider", "maiz", "dolar", "egoista", "ibamos",
                "tí", "fué", "dió", "vió", "fuí", "bién", "jóven", "exámen", "despúes", "construído",
                "llendo", "estubo", "hize", "quizo", "dijistes", "conciente", "manana", "ninos", "pequeno", "carino",
                "aver", "enserio", "talvez", "porfavor", "asique", "denuevo", "deacuerdo", "osea", "aveces", "thesubfactory",
                // derivados y verbos con pronombre que una regla daria por buenos
                "testamente", "apartamente", "bellaza", "canonazo", "megamente", "telemundo", "dícelo", "siéntante",
                "levántante", "sela", "selo", "dete", "dite", "intensión", "delos", "amis",
                // formas raras a una errata de una palabra frecuentisima, y las que caen con ellas
                "memos", "miso", "nade", "mientra", "henos", "roda", "incuso", "halar", "docto", "naca", "mensajee",
                "ensenó", "cremé", "losé"};
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
        assertTrue(es.rankOf("platicaríamos") >= es.coreSize());
        assertTrue(es.suggest("platicaría").suggestions.toString(), es.suggest("platicaría").suggestions.contains("platicaríamos"));
        // pero una errata sigue yendo a la palabra frecuente, no a una rara que se le parezca mas
        assertEquals("gracias", es.suggest("grcias").correction);
        assertEquals("siempre", es.suggest("simpre").correction);
        assertEquals("trabajo", es.suggest("trbajo").correction);
        // las formas nuevas de las familias vetadas se respetan si se escriben, pero no se proponen
        for (String w : new String[]{"chingaste", "pendejito", "jodiéndome"}) {
            assertTrue(w, es.rankOf(w) >= es.coreSize());
            assertTrue(w, es.suggest(w).known);
            String prefijo = w.substring(0, w.length() - 2);
            assertFalse(w + " al completar " + prefijo, es.completions(prefijo, 5).contains(w));
            assertFalse(w + " como sugerencia", es.suggest(prefijo).suggestions.contains(w));
        }
    }

    @Test
    public void lasFamiliasVetadasNoSeProponen() {
        // Recorre toda la lista: ninguna forma nueva de las familias malsonantes se propone (ni completa, ni corrige)
        String[] raices = {"ching", "pendej", "apendej", "mierd", "comemierd", "cabron", "encabron", "maricon", "gilipoll",
                "soplapoll", "calientapoll", "chupapoll", "hijoput", "malparid", "puñeter", "jode", "joda", "jodi", "jodo",
                "folla", "folle", "follo", "caga", "cago", "cague", "pajea", "pajee", "pajeo", "pajer", "puta", "puto",
                "puti", "verga", "coño"};
        Set<String> inocentes = new HashSet<>(Arrays.asList("putativo", "putativa", "putativos", "putativas", "follaje",
                "follajes", "folleto", "folletos", "folletín", "folletines", "follón", "follones", "vergara", "putin",
                "jodie", "cagoule"));
        List<String> sueltas = new ArrayList<>();
        int vistas = 0;
        for (int r = es.coreSize(); r < es.size(); r++) {
            String w = es.word(r);
            String f = Dictionary.deaccent(w);
            for (String raiz : raices) {
                if (!f.startsWith(raiz) || inocentes.contains(w)) continue;
                vistas++;
                if (!es.vetoed(w)) sueltas.add(w);
                break;
            }
        }
        assertTrue("formas de esas familias fuera del nucleo: " + vistas, vistas > 200);
        assertTrue("se propondrian: " + sueltas, sueltas.isEmpty());
        // las del nucleo, una a una; y las sueltas que senalo la revision
        for (String w : new String[]{"cabrones", "mierdas", "culos", "maricones", "pendejos", "putita", "hijoputa", "negrata",
                "chupavergas", "chupapollas", "comemierda", "encabronado", "soplapollas", "malparido", "puñeteros", "folló",
                "follada", "cagaron", "cágate", "pajero", "tetona", "pichas", "cipote", "bollera", "panocha", "piruja",
                "ojetes", "madrazo", "madriza", "chúpamelo", "mámamela"}) {
            if (es.rankOf(w) < 0) continue;
            assertTrue(w, es.vetoed(w));
            assertTrue(w + " se respeta si se escribe", es.suggest(w).known);
            assertNull(w, es.suggest(w).correction);
            String prefijo = w.substring(0, w.length() - 1);
            assertFalse(w + " al completar", es.completions(prefijo, 8).contains(w));
            assertFalse(w + " como sugerencia", es.suggest(prefijo).suggestions.contains(w));
        }
        // sin pasarse: palabras corrientes que empiezan igual, y las del nucleo que no estan una a una
        for (String w : new String[]{"putativo", "follaje", "folleto", "jodido", "cagney", "vergonzoso", "coñac"}) {
            assertFalse(w, es.vetoed(w));
        }
        // escrita sin su tilde, una vetada se queda como esta: ni se le pone ni se cambia por otra
        assertNull(es.suggest("cabron").correction);
        assertNull(es.suggest("chingon").correction);
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
