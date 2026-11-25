package de.trailscape.app.wear

import de.trailscape.core.ZielZeile
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.zielZeileZahl
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Die Zielzeile der Uhr (`wear/…/ui/RecordingScreen.kt`) passt auf das runde
 * Display: Satz aus den Uhr-Ressourcen, Zahl aus `:core`, in beiden Sprachen
 * hoechstens zwei Zeilen.
 *
 * `:wear` hat keine Testinfrastruktur; deshalb liegt dieser Test hier und
 * liest die Ressourcen der Uhr als Datei (wie `StringResourceParityTest`).
 * Das Mass ist bewusst grob: Bei 12 sp und 24 dp Rand bleiben auf der
 * kleinsten runden Uhr (~192 dp) in Hoehe der Zeile gut 140 dp, also rund 20
 * Zeichen je Zeile. Geprueft wird mit einem Zeilenumbruch an Leerzeichen
 * (das geschuetzte Leerzeichen zwischen Zahl und „km" bricht nicht).
 */
class WearZielTexteTest {

    private val zeichenJeZeile = 20
    private val maxZeilen = 2

    private val schluessel = listOf(
        "wear_live_goal_week_rest",
        "wear_live_goal_week_done",
        "wear_live_goal_best_rest",
        "wear_live_goal_best_done",
    )

    private fun strings(ordner: String): Map<String, String> {
        val datei = File("../wear/src/main/res/$ordner/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(datei)
        val knoten = doc.getElementsByTagName("string")
        return (0 until knoten.length).associate { i ->
            val e = knoten.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    /** Zeilen nach einem einfachen Umbruch an normalen Leerzeichen. */
    private fun umbrechen(text: String): List<String> {
        val zeilen = mutableListOf<String>()
        var aktuell = ""
        for (wort in text.split(' ')) {
            aktuell = when {
                aktuell.isEmpty() -> wort
                aktuell.length + 1 + wort.length <= zeichenJeZeile -> "$aktuell $wort"
                else -> {
                    zeilen += aktuell
                    wort
                }
            }
        }
        if (aktuell.isNotEmpty()) zeilen += aktuell
        return zeilen
    }

    private fun zeile(vorlage: String, zahl: String?): String = vorlage.replace("%1\$s", zahl.orEmpty())

    private fun schluesselFuer(fall: ZielZeile): String = when (fall) {
        is ZielZeile.WochenzielRest -> "wear_live_goal_week_rest"
        ZielZeile.WochenzielGeschafft -> "wear_live_goal_week_done"
        is ZielZeile.BestweiteRest -> "wear_live_goal_best_rest"
        ZielZeile.NeueBestweite -> "wear_live_goal_best_done"
    }

    @Test
    fun `alle vier Saetze gibt es auf Deutsch und Englisch, mit Zahl nur bei den Resten`() {
        for (ordner in listOf("values", "values-en")) {
            val s = strings(ordner)
            for (key in schluessel) assertTrue(key in s, "$ordner: $key fehlt")
            assertTrue("%1\$s" in s.getValue("wear_live_goal_week_rest"))
            assertTrue("%1\$s" in s.getValue("wear_live_goal_best_rest"))
            assertTrue("✓" in s.getValue("wear_live_goal_week_done"), "$ordner: Haken fehlt")
            assertTrue("✓" in s.getValue("wear_live_goal_best_done"), "$ordner: Haken fehlt")
            // Zahl und Einheit bleiben zusammen.
            assertTrue(" km" in s.getValue("wear_live_goal_week_rest"))
            assertTrue(" km" in s.getValue("wear_live_goal_best_rest"))
        }
    }

    @Test
    fun `jede Zeile passt in beiden Sprachen in zwei Zeilen, auch mit dreistelligem Rest`() {
        val faelle = listOf(
            ZielZeile.WochenzielRest(999.0),
            ZielZeile.WochenzielRest(0.04),
            ZielZeile.BestweiteRest(999.0),
            ZielZeile.BestweiteRest(0.3),
            ZielZeile.WochenzielGeschafft,
            ZielZeile.NeueBestweite,
        )
        for ((ordner, sprache) in listOf("values" to AppLanguage.DE, "values-en" to AppLanguage.EN)) {
            val s = strings(ordner)
            for (fall in faelle) {
                val text = zeile(s.getValue(schluesselFuer(fall)), zielZeileZahl(fall, sprache))
                val zeilen = umbrechen(text)
                assertTrue(zeilen.size <= maxZeilen, "$ordner: „$text“ braucht ${zeilen.size} Zeilen: $zeilen")
                zeilen.forEach { assertTrue(it.length <= zeichenJeZeile, "$ordner: Wort zu lang in „$text“") }
            }
        }
    }

    @Test
    fun `die Zahl folgt der Sprache der Uhr`() {
        val fall = ZielZeile.WochenzielRest(0.34)
        assertEquals(
            "Noch 0,4 km bis zum Wochenziel",
            zeile(strings("values").getValue("wear_live_goal_week_rest"), zielZeileZahl(fall, AppLanguage.DE)),
        )
        assertEquals(
            "0.4 km to your weekly goal",
            zeile(strings("values-en").getValue("wear_live_goal_week_rest"), zielZeileZahl(fall, AppLanguage.EN)),
        )
        assertEquals(
            "Noch 12 km bis zur neuen Bestweite",
            zeile(
                strings("values").getValue("wear_live_goal_best_rest"),
                zielZeileZahl(ZielZeile.BestweiteRest(11.2), AppLanguage.DE),
            ),
        )
    }
}
