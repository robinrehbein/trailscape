package de.trailscape.app.wear

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Die Vorschlagszeile der Kachel (`wear/…/tile/WochenTileService.kt`, eine
 * Zeile bei 12 sp unter den Kilometern) passt auch mit dreistelliger
 * Minutenzahl auf das runde Display. Wie [WearZielTexteTest] liest der Test die
 * Ressourcen der Uhr als Datei; das Mass ist grob: In Hoehe der Zeile bleiben
 * auf der kleinsten runden Uhr rund 150 dp, also etwa 24 Zeichen.
 */
class WearHeuteTexteTest {

    private val maxZeichen = 24

    private val schluessel = listOf(
        "idee02_wear_today_outdoor",
        "idee02_wear_today_indoor",
        "idee02_wear_today_outdoor_min",
        "idee02_wear_today_indoor_min",
        "idee02_wear_today_done",
    )

    private fun strings(ordner: String): Map<String, String> {
        val datei = File("../wear/src/main/res/$ordner/strings_idee02.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(datei)
        val knoten = doc.getElementsByTagName("string")
        return (0 until knoten.length).associate { i ->
            val e = knoten.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    @Test
    fun `jede Vorschlagszeile passt in eine Zeile, auch mit 600 min`() {
        for (ordner in listOf("values", "values-en")) {
            val s = strings(ordner)
            for (key in schluessel) {
                val vorlage = s[key]
                assertTrue(vorlage != null, "$ordner: $key fehlt")
                val text = vorlage.replace("%1\$d", "600")
                assertTrue(text.length <= maxZeichen, "$ordner/$key zu lang (${text.length}): $text")
            }
        }
    }
}
