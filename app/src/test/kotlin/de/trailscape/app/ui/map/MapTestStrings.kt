package de.trailscape.app.ui.map

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.i18n.AppLanguage
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Loest [UiText] der Karte in reinen JVM-Tests auf — ohne Robolectric.
 *
 * Warum: Die Formulierungslogik der Karte liefert [UiText] (docs/i18n.md,
 * Abschnitt D). Die bestehenden Tests pruefen aber bewusst den deutschen
 * Wortlaut („In 250 m", „ca. 50 min"), und neue Tests sollen die englische
 * Fassung ebenso pruefen. Dafuer liest dieser Helfer die Ressourcendateien
 * (`strings_map.xml`, `strings_common.xml`) direkt und formatiert wie Android:
 * positionsbezogene Platzhalter, Plurals nach der englischen/deutschen Regel
 * (1 → `one`, sonst `other`), Android-Escapes (`\n`, `\'`, `\"`).
 */
internal object MapTestStrings {

    private val files = listOf("strings_map.xml", "strings_common.xml", "strings_segments.xml")

    private class Table(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val tables = mutableMapOf<AppLanguage, Table>()

    /** Name einer Ressourcen-ID (`R.string.x` → „x"), ueber Reflexion auf die R-Klassen. */
    private val names: Map<Int, String> by lazy {
        (R.string::class.java.fields + R.plurals::class.java.fields)
            .associate { it.getInt(null) to it.name }
    }

    private fun table(language: AppLanguage): Table = tables.getOrPut(language) {
        val dir = if (language == AppLanguage.DE) "values" else "values-en"
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        for (name in files) {
            val file = File("src/main/res/$dir/$name")
            if (!file.isFile) continue
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val stringNodes = doc.getElementsByTagName("string")
            for (i in 0 until stringNodes.length) {
                val el = stringNodes.item(i) as Element
                strings[el.getAttribute("name")] = unescape(el.textContent)
            }
            val pluralNodes = doc.getElementsByTagName("plurals")
            for (i in 0 until pluralNodes.length) {
                val el = pluralNodes.item(i) as Element
                val items = el.getElementsByTagName("item")
                plurals[el.getAttribute("name")] = (0 until items.length).associate {
                    val item = items.item(it) as Element
                    item.getAttribute("quantity") to unescape(item.textContent)
                }
            }
        }
        Table(strings, plurals)
    }

    private fun unescape(raw: String): String =
        raw.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"")

    /** Der Text in [language]. */
    fun resolve(text: UiText, language: AppLanguage): String {
        val t = table(language)
        return when (text) {
            is UiText.Plain -> text.text
            is UiText.Res -> {
                val name = names[text.id] ?: error("Unbekannte ID ${text.id}")
                val pattern = t.strings[name] ?: error("Fehlt in ${language.tag}: $name")
                format(pattern, text.args, language)
            }
            is UiText.Plural -> {
                val name = names[text.id] ?: error("Unbekannte ID ${text.id}")
                val forms = t.plurals[name] ?: error("Fehlt in ${language.tag}: $name")
                val pattern = forms[if (text.count == 1) "one" else "other"] ?: forms.getValue("other")
                format(pattern, text.args, language)
            }
        }
    }

    private fun format(pattern: String, args: List<Any>, language: AppLanguage): String {
        // Wie `getString(id)` ohne Argumente: unformatiert.
        if (args.isEmpty()) return pattern
        val resolved = args.map { if (it is UiText) resolve(it, language) else it }.toTypedArray()
        return String.format(Locale.ROOT, pattern, *resolved)
    }

    fun de(text: UiText): String = resolve(text, AppLanguage.DE)

    fun en(text: UiText): String = resolve(text, AppLanguage.EN)
}
