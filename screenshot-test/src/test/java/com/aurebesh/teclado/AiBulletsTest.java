package com.aurebesh.teclado;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Accion Vinetas con respaldo determinista (sin modelo: solo el postproceso). La lista sale SIEMPRE con el formato
 * fijo, titulo terminado en dos puntos (si se deduce) y un elemento por linea con "- ": si el modelo devolvio el texto
 * casi igual, un parrafo o menos de dos elementos, se construye desde el texto original (frases, enumeraciones por
 * comas y conjuncion final, entradilla antes de dos puntos); si dio elementos sin titulo, el titulo se toma del
 * original; una salida ya correcta se respeta; y nunca queda vacio si el original tiene texto.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class AiBulletsTest {

    /** El ejemplo del usuario: una enumeracion y una frase que la explica. */
    private static final String PEDIDO = "Huevos, leche, pan y tortillas. Son cosas que me pidió mamá para la comida";
    private static final String PEDIDO_LISTA = "Son cosas que me pidió mamá para la comida:\n- Huevos\n- leche\n- pan\n- tortillas";

    @Test
    public void modelEchoesTheTextSoTheListComesFromTheOriginal() {
        assertEquals(PEDIDO_LISTA, AiRunner.bulletList(PEDIDO, PEDIDO));
        // Con punto final y sin salto de linea tambien es "casi igual"
        assertEquals(PEDIDO_LISTA, AiRunner.bulletList(PEDIDO + ".", PEDIDO + "\n"));
        // Un parrafo distinto, sin marcas de lista: tambien respaldo desde el original
        assertEquals(PEDIDO_LISTA, AiRunner.bulletList("Mamá me pidió para la comida huevos, leche, pan y tortillas.", PEDIDO));
        assertEquals(PEDIDO_LISTA, AiRunner.bulletList("Huevos, leche, pan y tortillas.\nSon cosas que me pidió mamá para la comida.", PEDIDO));
        // La limpieza completa de la accion pasa el original
        AiRunner.Cleaned c = AiRunner.cleanChecked(AiRunner.Action.BULLETS, PEDIDO, PEDIDO);
        assertEquals(PEDIDO_LISTA, c.text);
        assertEquals(0, c.errorRes);
    }

    @Test
    public void modelItemsWithoutTitleGetTheTitleFromTheOriginal() {
        String want = "Son cosas que me pidió mamá para la comida:\n- Huevo\n- Leche\n- Pan\n- Tortillas";
        assertEquals(want, AiRunner.bulletList("• Huevo • Leche • Pan • Tortillas", PEDIDO));
        assertEquals(want, AiRunner.bulletList("- Huevo\n- Leche\n- Pan\n- Tortillas", PEDIDO));
        assertEquals(want, AiRunner.bulletList("Huevo\nLeche\nPan\nTortillas", PEDIDO));
        // Sin titulo que ofrecer en el original (ninguna enumeracion) no se inventa
        assertEquals("- Huevo\n- Leche", AiRunner.bulletList("- Huevo\n- Leche", "huevo leche"));
        // Ni se anade uno que repita un elemento
        assertEquals("- Huevos\n- leche\n- pan\n- Tortillas",
                AiRunner.bulletList("- Huevos\n- leche\n- pan\n- Tortillas", "Huevos, leche y pan. Tortillas"));
    }

    @Test
    public void correctModelOutputIsKept() {
        assertEquals("Lista de compras:\n- Huevos\n- Leche", AiRunner.bulletList("Lista de compras:\n- Huevos\n- Leche", PEDIDO));
        assertEquals("Lista de compras:\n- Huevos\n- Leche", AiRunner.bulletList("**Lista de compras:**\n* Huevos\n* Leche", PEDIDO));
        assertEquals("Compra:\n- Uno\n- Dos\n- Tres", AiRunner.bulletList("Compra:\n1. Uno\n2. Dos\n3. Tres", PEDIDO));
    }

    @Test
    public void paragraphWithoutCommasGivesOneBulletPerSentenceWithoutTitle() {
        String p = "Fui al mercado. Compré fruta. Volví a casa.";
        assertEquals("- Fui al mercado\n- Compré fruta\n- Volví a casa", AiRunner.bulletList(p, p));
        assertEquals("- Fui al mercado\n- Compré fruta\n- Volví a casa", AiRunner.bulletList("", p));
        // Exclamaciones, preguntas y saltos de linea; ! y ? se conservan
        assertEquals("- ¡Hola!\n- ¿Qué tal?\n- Bien", AiRunner.bulletList("¡Hola! ¿Qué tal?\nBien", "¡Hola! ¿Qué tal?\nBien"));
        // Una lista numerada escrita por el usuario: los numeros fuera
        assertEquals("- pan\n- leche\n- huevos", AiRunner.bulletList("", "1. pan\n2. leche\n3. huevos"));
    }

    @Test
    public void leadBeforeColonIsTheTitle() {
        String cena = "Para la cena: pasta, ensalada y vino";
        assertEquals("Para la cena:\n- pasta\n- ensalada\n- vino", AiRunner.bulletList(cena, cena));
        assertEquals("Para la cena:\n- pasta\n- ensalada\n- vino", AiRunner.bulletList("", cena));
        // La entradilla manda sobre otra frase sin enumeracion, que pasa a ser elemento
        assertEquals("Para la cena:\n- Hola Juan\n- pasta\n- ensalada\n- vino", AiRunner.bulletList("", "Hola Juan. " + cena));
        // Una linea que es solo entradilla, seguida de una cosa por linea
        assertEquals("Lista:\n- pan\n- leche", AiRunner.bulletList("", "Lista:\npan\nleche"));
    }

    @Test
    public void englishAndGermanConjunctions() {
        String en = "Eggs, milk and bread. Things mom asked for";
        assertEquals("Things mom asked for:\n- Eggs\n- milk\n- bread", AiRunner.bulletList(en, en));
        assertEquals("Pick one:\n- Eggs\n- milk\n- bread", AiRunner.bulletList("", "Eggs, milk, or bread. Pick one"));
        String de = "Eier, Milch und Brot. Dinge, die Mama wollte";
        assertEquals("Dinge, die Mama wollte:\n- Eier\n- Milch\n- Brot", AiRunner.bulletList(de, de));
        assertEquals("Was möchtest du:\n- Kaffee\n- Tee", AiRunner.bulletList("", "Kaffee oder Tee. Was möchtest du?"));
        // Dos clausulas largas unidas por "y" son una frase, no una enumeracion
        assertEquals("Hoy fue un día largo y estoy cansado de tanto trabajo:\n- Compra pan\n- leche\n- huevos",
                AiRunner.bulletList("", "Hoy fue un día largo y estoy cansado de tanto trabajo. Compra pan, leche y huevos."));
    }

    @Test
    public void nothingToSplitIsASingleBulletAndNeverEmptyWithText() {
        assertEquals("- Hola mundo", AiRunner.bulletList("Hola mundo", "Hola mundo"));
        assertEquals("- Hola mundo", AiRunner.bulletList("", "Hola mundo"));
        assertEquals("- Hola, Juan", AiRunner.bulletList("Hola, Juan", "Hola, Juan"));     // dos partes sin conjuncion no es enumeracion
        assertEquals("Lista de la compra:\n- Hola mundo", AiRunner.bulletList("Lista de la compra:", "Hola mundo"));
        // Sin original (compatibilidad) todo sigue igual: vacio si el modelo no dio elementos
        assertEquals("", AiRunner.bulletList("Solo un titulo:"));
        assertEquals("", AiRunner.bulletList("", "   "));
        assertEquals("Lista de compras:\n- Huevo\n- Leche\n- Pan\n- Tortillas", AiRunner.bulletList("Lista de compras: Huevo, Leche, Pan y Tortillas"));
    }

    @Test
    public void decimalCommaAbbreviationsAndDecimalPointAreNotSplit() {
        String harina = "1,5 kg de harina, 2 huevos y sal";
        assertEquals("- 1,5 kg de harina\n- 2 huevos\n- sal", AiRunner.bulletList(harina, harina));
        assertEquals("Para mañana:\n- Llama a J. Pérez\n- compra 2.5 kg de arroz\n- pan",
                AiRunner.bulletList("", "Llama a J. Pérez, compra 2.5 kg de arroz y pan. Para mañana"));
        List<String> frases = AiRunner.sentences("Llama a J. Pérez. Son 2.5 kg... ¿Vale?! Sí; claro. www.ejemplo.com");
        assertEquals(Arrays.asList("Llama a J. Pérez.", "Son 2.5 kg...", "¿Vale?!", "Sí;", "claro.", "www.ejemplo.com"), frases);
    }

    @Test
    public void promptShowsAnInputOutputExampleAndItsLabelIsNotATitle() {
        AiRunner.Request r = new AiRunner.Request(AiRunner.Action.BULLETS, PEDIDO);
        r.lang = "es";
        String p = AiRunner.userPrompt(r, PEDIDO, "es");
        assertTrue(p, p.contains("Respuesta:\nLo que pidió mamá:\n- Huevos\n- Leche\n- Pan"));
        assertTrue(p, p.endsWith("Texto:\n" + PEDIDO));
        assertTrue(AiRunner.userPrompt(r, PEDIDO, "en").contains("Answer:\nWhat mom asked for:\n- Eggs"));
        assertTrue(AiRunner.userPrompt(r, PEDIDO, "de").contains("Antwort:\nWas Mama haben wollte:\n- Eier"));
        // Si el modelo copia la etiqueta del ejemplo, o pone "Lista:" a secas, no es el titulo
        assertEquals("Lo que pidió mamá:\n- Huevos\n- Leche",
                AiRunner.clean(AiRunner.Action.BULLETS, "Respuesta:\nLo que pidió mamá:\n- Huevos\n- Leche", PEDIDO));
        assertEquals("- a\n- b", AiRunner.clean(AiRunner.Action.BULLETS, "Lista:\n- a\n- b", "a b"));
    }
}
