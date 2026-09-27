package de.trailscape.app.ui.today

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Loest [UiText] gegen die Ressourcendateien auf — als reiner JVM-Test, ohne
 * Robolectric.
 *
 * ## Warum
 * Die Formulierungslogik von „Heute" liefert seit dem Umzug in Ressourcen
 * [UiText] statt Strings. Ein Vergleich gegen `UiText.Res(R.string.x, …)`
 * prueft nur, WELCHER Schluessel gewaehlt wurde — nicht, dass der Satz am Ende
 * derselbe ist wie vorher. Die bestehenden Tests pruefen den deutschen
 * Wortlaut („Gut erholt. Heute eine lockere Runde."); mit diesem Helfer
 * bleiben sie Wort fuer Wort gueltig und pruefen dieselben Saetze auch auf
 * Englisch.
 *
 * ## Wie
 * Liest `src/main/res/values[-en]/strings*.xml` (Arbeitsverzeichnis der Tests
 * ist das Modul `:app`, wie im `StringResourceParityTest`), ordnet die Namen
 * ueber `R.string`/`R.plurals` den IDs zu und formatiert wie Android
 * (`String.format` mit der Locale der Sprache). Plural-Regel fuer Deutsch und
 * Englisch: `one` genau bei 1, sonst `other`. Aufgeloest werden die in diesen
 * Dateien vorkommenden Escapes (`\n`, `\'`, `\"`, `\\`).
 */
object TodayStrings {
    private val stringIds: Map<Int, String> = idsOf(R.string::class.java)
    private val pluralIds: Map<Int, String> = idsOf(R.plurals::class.java)

    private val de = Table(File("src/main/res/values"), Locale.GERMANY)
    private val en = Table(File("src/main/res/values-en"), Locale.UK, fallback = de)

    /** Der Text, wie ihn ein deutsches Geraet zeigt. */
    fun de(text: UiText): String = de.resolve(text)

    /** Der Text, wie ihn ein englisches Geraet zeigt. */
    fun en(text: UiText): String = en.resolve(text)

    private fun idsOf(type: Class<*>): Map<Int, String> =
        type.fields.associate { it.getInt(null) to it.name }

    private class Table(dir: File, val locale: Locale, val fallback: Table? = null) {
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()

        init {
            check(dir.isDirectory) { "Ressourcen nicht gefunden: ${dir.absolutePath}" }
            dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().forEach(::read)
        }

        fun resolve(text: UiText): String = when (text) {
            is UiText.Plain -> text.text
            is UiText.Res -> format(string(stringIds.getValue(text.id)), text.args)
            is UiText.Plural -> {
                val forms = plural(pluralIds.getValue(text.id))
                format(forms[if (text.count == 1) "one" else "other"] ?: forms.getValue("other"), text.args)
            }
        }

        private fun string(name: String): String =
            strings[name] ?: fallback?.string(name) ?: error("Kein String „$name“")

        private fun plural(name: String): Map<String, String> =
            plurals[name] ?: fallback?.plural(name) ?: error("Kein Plural „$name“")

        private fun format(pattern: String, args: List<Any>): String {
            if (args.isEmpty()) return pattern
            val resolved = args.map { if (it is UiText) resolve(it) else it }.toTypedArray()
            return String.format(locale, pattern, *resolved)
        }

        private fun read(file: File) {
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

        private fun unescape(raw: String): String = raw.trim()
            .replace(Regex("\\s+"), " ")
            .replace("\\n", "\n")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}
