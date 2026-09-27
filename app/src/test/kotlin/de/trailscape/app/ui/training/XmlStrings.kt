package de.trailscape.app.ui.training

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.i18n.AppLanguage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Loest [UiText] gegen die echten Ressourcendateien auf — ohne Robolectric.
 *
 * Die Textlogik des Trainings-Tabs liefert [UiText]; ein Vergleich mit
 * `UiText.Res(R.string.x, …)` allein bewiese nicht, dass der **Satz** noch
 * stimmt. Dieser kleine Aufloeser liest `values[-en]/strings*.xml`, findet den
 * Schluessel zur Ressourcen-ID ueber die `R`-Klasse und formatiert wie Android
 * (positionsbezogene Platzhalter, `%%`, Plural `one`/`other`). So bleiben die
 * deutschen Erwartungen der Tests woertlich erhalten, und dieselben Faelle
 * lassen sich auf Englisch pruefen.
 *
 * Bewusst schlicht: nur, was die Bereichsdateien benutzen (keine
 * Anfuehrungs-Quoting-Regeln, keine Styles).
 */
internal object XmlStrings {

    private val strings = mutableMapOf<AppLanguage, Map<String, String>>()
    private val plurals = mutableMapOf<AppLanguage, Map<String, Map<String, String>>>()

    /** Der Text in [language]. */
    fun resolve(text: UiText, language: AppLanguage): String = when (text) {
        is UiText.Plain -> text.text
        is UiText.Res -> {
            val name = nameOf(R.string::class.java, text.id)
            val raw = stringsOf(language)[name] ?: error("Kein String $name in $language")
            format(raw, text.args, language)
        }
        is UiText.Plural -> {
            val name = nameOf(R.plurals::class.java, text.id)
            val items = pluralsOf(language)[name] ?: error("Kein Plural $name in $language")
            // Deutsch und Englisch kennen nur „one" (genau 1) und „other".
            val raw = items.getValue(if (text.count == 1) "one" else "other")
            format(raw, text.args, language)
        }
    }

    /** Ein String-Schluessel ohne Argumente in [language]. */
    fun string(id: Int, language: AppLanguage): String = resolve(UiText.Res(id), language)

    fun de(text: UiText): String = resolve(text, AppLanguage.DE)

    fun en(text: UiText): String = resolve(text, AppLanguage.EN)

    private fun format(raw: String, args: List<Any>, language: AppLanguage): String {
        // Wie `getString(id)` ohne Argumente: keine Formatierung, `%%` bliebe stehen.
        if (args.isEmpty()) return raw
        val resolved = args.map { if (it is UiText) resolve(it, language) else it }.toTypedArray()
        return String.format(language.locale, raw, *resolved)
    }

    private fun nameOf(type: Class<*>, id: Int): String =
        type.fields.firstOrNull { it.getInt(null) == id }?.name ?: error("Unbekannte Ressourcen-ID $id")

    private fun dir(language: AppLanguage): File =
        File("src/main/res", if (language == AppLanguage.DE) "values" else "values-en")

    private fun files(language: AppLanguage): List<File> =
        dir(language).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().toList()

    @Synchronized
    private fun stringsOf(language: AppLanguage): Map<String, String> = strings.getOrPut(language) {
        buildMap {
            for (file in files(language)) {
                for (el in elements(file, "string")) put(el.getAttribute("name"), unescape(el.textContent))
            }
        }
    }

    @Synchronized
    private fun pluralsOf(language: AppLanguage): Map<String, Map<String, String>> = plurals.getOrPut(language) {
        buildMap {
            for (file in files(language)) {
                for (el in elements(file, "plurals")) {
                    val items = el.getElementsByTagName("item")
                    put(
                        el.getAttribute("name"),
                        (0 until items.length).associate { i ->
                            val item = items.item(i) as Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        },
                    )
                }
            }
        }
    }

    private fun elements(file: File, tag: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
    }

    /** Androids Escapes und das Zusammenfassen von Leerraum. */
    private fun unescape(value: String): String =
        value.trim()
            .replace(Regex("\\s+"), " ")
            .replace("\\n", "\n")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
}
