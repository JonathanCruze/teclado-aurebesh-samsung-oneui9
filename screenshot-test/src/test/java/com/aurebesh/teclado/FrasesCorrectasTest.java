package com.aurebesh.teclado;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Frases bien escritas, tecleadas tal cual en un chat: lo que el teclado les cambie es una correccion de mas. La lista
 * de cambios admitidos es corta y explicita (los que son a proposito y los que ya se conocen); cualquier otro cambio, o
 * que uno de estos deje de producirse, hace fallar la prueba para que se mire. Salio de la revision independiente de la
 * 2.8, que asi encontro nombres propios que tambien son palabra comun ("armando", "lucía", "una cuba", "perro
 * chihuahua") y direcciones con mayuscula ("Facebook.com", "Juan.perez@gmail.com").
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class FrasesCorrectasTest extends ArnesServicio {

    static final String[] ES = {
            "Hola, ¿cómo estás? Yo bien, gracias. ", "¿Qué vas a hacer mañana en la tarde? ", "No sé si pueda ir, tengo mucho trabajo. ",
            "Ya voy para allá, llego en 10 min. ", "Está bien, nos vemos el sábado. ", "¿Que no ibas a venir? ", "¿Como a qué hora llegas? ",
            "¿Cuando llegues me avisas? ", "¿Donde siempre? ", "¿Quien quiera puede venir? ", "Dijo que sí, ¿que si podías venir? ",
            "Mi mamá dice que sí puedo ir. ", "Él es mi hermano y ella mi prima. ", "A mí me gusta más el café. ",
            "Vi a Juan y a María en el Oxxo. ", "El vestido lucía increíble. ", "Estamos armando la fiesta de Lalo. ",
            "Se armó la carnita asada y seguimos armando el plan. ", "No hagas pancho, wey. ", "Tengo un perro chihuahua. ",
            "Pide una cuba y dos chelas. ", "La gente corea su nombre. ", "Simón, ahí nos vemos. ", "Mi correo es juan.perez@gmail.com, escríbeme. ",
            "Entra a facebook.com o a www.google.com.mx y busca. ", "Sígueme en instagram.com/usuario o en tiktok.com/@usuario. ",
            "La página es mercadolibre.com.mx/ofertas. ", "Son 2 kg de tortillas, 1 lt de leche y 500 gr de jamón. ",
            "Nos vemos a las 8 pm en casa de Pedro (el de la esquina). ", "Le dije \"no, gracias\" y se fue. ", "Dijo: «ya voy» y nunca llegó. ",
            "El 15 de septiembre es el Grito. ", "Voy con el Dr. López y la Lic. Pérez. ", "Trabajo en la SEP y antes en el IMSS. ",
            "Mi RFC es XAXX010101000 y mi CURP también. ", "Vivo en la col. Roma, CDMX. ", "Av. Insurgentes Sur 123, int. 4. ",
            "El papa Francisco llegó a México. ", "Me comí una papa asada. ", "Compré papas y un refresco. ", "Seria bueno que fueras serio. ",
            "La secretaria de la secretaría. ", "El sábado toco la guitarra y él tocó el piano. ", "Hablo inglés y él habló francés. ",
            "Esta casa está sola. ", "Solo quiero un café solo. ", "Aun así, aún no llega. ", "Si quieres, sí voy. ", "Tu casa y tú. ",
            "Mi casa es para mí. ", "Te quiero y quiero té. ", "De nada, que te dé gusto. ", "Se fue, no sé más. ", "Mas no pudo más. ",
            "El lunes y el martes voy; el miércoles no. ", "En enero hace frío, en julio calor. ", "Rosa compró una rosa en mayo. ",
            "Ángel es un ángel. ", "El león de León. ", "La victoria de Victoria. ", "Vamos al cine Xola. ", "Soy fan de Xbox y PlayStation. ",
            "Mi iPhone y mi iPad. ", "Uso WhatsApp, TikTok e Instagram. ", "Hola Sr. Gómez, buen día. ", "Atte. Lic. Juan Pérez. ",
            "Ok, va. Sale, nos vemos. ", "Jajaja no manches. ", "Ntp, todo bn. ", "Tqm, bb. ", "Xq no viniste? ", "Pq no quise. ",
            "Voy x ti a las 5. ", "Q onda, k haces? ", "Ps nada, aquí nomás. ", "Órale, pues. ", "Ándale, vámonos. ", "Híjole, se me olvidó. ",
            "No mames, güey. ", "Qué chido, carnal. ", "Ahorita vengo, voy al Oxxo. ", "Neta que sí. ", "A poco no. ", "Sepa la bola. ",
            "1) Leche 2) Huevos 3) Pan ", "a) uno b) dos c) tres ", "- leche\n- huevos\n- pan\n", "Total: $1,250.50 (IVA incluido). ",
            "Mide 1.75 m y pesa 70 kg. ", "Llego 5:30 pm; salgo 7:00 am. ", "Es 50% más barato. ", "#FelizLunes a todos. ", "@juan ven acá. ",
            "Código: ABC-123_x. ", "El wifi es Casa_5G y la clave es hola1234. ", "C:\\Users\\Juan o /home/juan. ", "x = y + 2. ",
            "T-shirt talla M. ", "Vuelo AM123 a NY. ", "USA vs. MX. ", "Ja ja ja. ", "Mmm... no sé. ", "Eh... ¿qué? ", "Sí... pero no. ",
    };

    /** Cambios admitidos en espanol: frase tecleada -> lo que queda. */
    private static Map<String, String> admitidosEs() {
        Map<String, String> m = new LinkedHashMap<>();
        // A proposito: pegado a la apertura, el interrogativo lleva tilde casi siempre; borrar lo deshace y se recuerda
        m.put("\u00bfQue no ibas a venir? ", "\u00bfQu\u00e9 no ibas a venir? ");
        m.put("\u00bfComo a qu\u00e9 hora llegas? ", "\u00bfC\u00f3mo a qu\u00e9 hora llegas? ");
        m.put("\u00bfCuando llegues me avisas? ", "\u00bfCu\u00e1ndo llegues me avisas? ");
        m.put("\u00bfDonde siempre? ", "\u00bfD\u00f3nde siempre? ");
        m.put("\u00bfQuien quiera puede venir? ", "\u00bfQui\u00e9n quiera puede venir? ");
        m.put("Dijo que s\u00ed, \u00bfque si pod\u00edas venir? ", "Dijo que s\u00ed, \u00bfqu\u00e9 si pod\u00edas venir? ");
        // Conocidos de siempre: la gemela con tilde es muchisimo mas frecuente (ahora se pueden rechazar y conservar)
        m.put("El papa Francisco lleg\u00f3 a M\u00e9xico. ", "El pap\u00e1 Francisco lleg\u00f3 a M\u00e9xico. ");
        m.put("Me com\u00ed una papa asada. ", "Me com\u00ed una pap\u00e1 asada. ");
        m.put("Seria bueno que fueras serio. ", "Ser\u00eda bueno que fueras serio. ");
        m.put("Mas no pudo m\u00e1s. ", "M\u00e1s no pudo m\u00e1s. ");
        return m;
    }

    @Test
    public void lasFrasesCorrectasEnEspanolNoCambian() throws Exception {
        Map<String, String> changed = new LinkedHashMap<>();
        for (String s : ES) {
            newField(CHAT, 0, "");
            String out = more(s);
            if (!out.equals(s)) changed.put(s, out);
        }
        assertEquals(admitidosEs().toString().replace(", ", "\n"), changed.toString().replace(", ", "\n"));
    }

    static final String[] EN = {
            "Hello, how are you? I'm fine, thanks. ", "I don't know what to do. ", "See you on Friday at the office. ",
            "We went to Paris in June. ", "My friend John and Mary. ", "I will go in May or March. ", "It's ok. I think so. ",
            "He said \"no way\" and left. ", "The USA and the UK. ", "I use Google and YouTube. ", "Will you mark it? ",
            "A polish for the china. ", "Email me at john.smith@gmail.com please. ", "Go to amazon.com or www.google.com. ",
            "5 kg and 10 mb. ", "I saw Mr. Smith (the tall one). ", "It was an august occasion. ", "Turkey for dinner. ",
            "i'm sure i'll do it. ", "the english and the french. ", "(i think) ", "ok, lol. brb. omg. idk. ",
    };

    private static Map<String, String> admitidosEn() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("A polish for the china. ", "A polish for the China. ");             // la porcelana es rarisima frente al pais
        m.put("It was an august occasion. ", "It was an August occasion. ");       // lo mismo con el adjetivo
        m.put("i'm sure i'll do it. ", "I'm sure I'll do it. ");                   // a proposito
        m.put("the english and the french. ", "the English and the French. ");     // a proposito
        m.put("(i think) ", "(I think) ");                                         // a proposito
        return m;
    }

    @Test
    public void lasFrasesCorrectasEnInglesNoCambian() throws Exception {
        setLang("en");
        Map<String, String> changed = new LinkedHashMap<>();
        for (String s : EN) {
            newField(CHAT, 0, "");
            String out = more(s);
            if (!out.equals(s)) changed.put(s, out);
        }
        assertEquals(admitidosEn().toString().replace(", ", "\n"), changed.toString().replace(", ", "\n"));
    }
}
