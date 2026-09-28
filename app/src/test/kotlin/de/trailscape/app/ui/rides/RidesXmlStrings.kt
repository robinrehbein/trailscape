package de.trailscape.app.ui.rides

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.i18n.AppLanguage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Loest [UiText] gegen die echten Ressourcendateien auf — als reiner JVM-Test,
 * ohne Robolectric.
 *
 * Warum: Die Formulierungslogik des Verlaufs (`RideImpactWording.kt`,
 * `RideEffort.kt`, `RideListLogic.kt`, `ShareCard.kt` …) liefert seit der
 * Uebersetzung [UiText] statt fertiger Saetze. Ein Test, der nur
 * `UiText.Res(R.string.x, …)` vergleicht, sieht den Satz nicht mehr — und die
 * bestehenden Tests prueften gerade den Wortlaut. Dieser Leser nimmt die
 * Dateien aus `src/main/res/values[-en]/strings*.xml`, ordnet die Ressourcen-ID
 * ueber die `R`-Klasse ihrem Namen zu und formatiert wie Android
 * (`String.format` mit der Locale der Sprache, Plural `one` nur fuer 1 — die
 * Regel fuer Deutsch und Englisch). So bleiben die deutschen Erwartungen Wort
 * fuer Wort gueltig, und dieselben Tests laufen auch englisch.
 *
 * Nur, was die Verlaufsdateien brauchen: `\n`, `\'`, `\"`, `\\` und
 * umschliessende Anfuehrungszeichen werden wie von aapt behandelt.
 */
internal class RidesXmlStrings private constructor(val language: AppLanguage) {

    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        read(File("src/main/res/values"))
        if (language == AppLanguage.EN) read(File("src/main/res/values-en"))
    }

    /** Der aufgeloeste Text in der Sprache dieses Lesers. */
    fun resolve(text: UiText): String = when (text) {
        is UiText.Plain -> text.text
        is UiText.Res -> format(stringValue(text.id), text.args)
        is UiText.Plural -> {
            val name = nameOf(R.plurals::class.java, text.id)
            val items = plurals[name] ?: error("Plural $name fehlt")
            val quantity = if (text.count == 1) "one" else "other"
            format(items[quantity] ?: items.getValue("other"), text.args)
        }
    }

    /** Wie `context.getString(id, *args)`. */
    fun string(id: Int, vararg args: Any): String = format(stringValue(id), args.toList())

    private fun stringValue(id: Int): String {
        val name = nameOf(R.string::class.java, id)
        return strings[name] ?: error("String $name fehlt")
    }

    private fun format(pattern: String, args: List<Any>): String {
        if (args.isEmpty()) return pattern
        val resolved = args.map { if (it is UiText) resolve(it) else it }.toTypedArray()
        return String.format(language.locale, pattern, *resolved)
    }

    private fun read(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
        check(files.isNotEmpty()) { "Keine Ressourcen unter ${dir.absolutePath}" }
        for (file in files) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val name = el.getAttribute("name")
                when (el.tagName) {
                    "string" -> strings[name] = unescape(el.textContent)
                    "plurals" -> {
                        val items = el.getElementsByTagName("item")
                        plurals[name] = (0 until items.length).associate { k ->
                            val item = items.item(k) as Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Deutsch — die Default-Ressourcen. */
        val DE: RidesXmlStrings by lazy { RidesXmlStrings(AppLanguage.DE) }

        /** Englisch — `values-en` ueber den Default-Ressourcen. */
        val EN: RidesXmlStrings by lazy { RidesXmlStrings(AppLanguage.EN) }

        private val names = mutableMapOf<Class<*>, Map<Int, String>>()

        @Synchronized
        private fun nameOf(type: Class<*>, id: Int): String {
            val map = names.getOrPut(type) {
                type.fields.associate { it.getInt(null) to it.name }
            }
            return map[id] ?: error("Unbekannte Ressourcen-ID $id in ${type.simpleName}")
        }

        private fun unescape(raw: String): String {
            var text = raw.trim()
            if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
                text = text.substring(1, text.length - 1)
            }
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    when (val next = text[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> out.append(next)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
