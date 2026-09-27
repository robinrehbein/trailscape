package de.trailscape.app.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Element

/**
 * Deutsch und Englisch bleiben deckungsgleich — ein reiner JVM-Test ueber die
 * Ressourcendateien von `:app` und `:wear` (Regeln: `docs/i18n.md`, Abschnitt E).
 *
 * Geprueft wird je `values/strings*.xml`:
 *  * es gibt dieselbe Datei in `values-en/` (sofern sie Uebersetzbares enthaelt),
 *  * dieselben Schluessel (Strings und Plurals, ohne `translatable="false"`),
 *  * dieselben Platzhalter je Schluessel, und nur positionsbezogene (`%1$s`),
 *  * Plurals mit `one` und `other`,
 *  * keine leeren Werte,
 *  * Schluessel mit dem Praefix ihrer Bereichsdatei,
 *  * im Englischen keine deutschen Anfuehrungszeichen „…".
 */
class StringResourceParityTest {

    /** Die Ressourcenwurzeln, relativ zum Modulverzeichnis von `:app` (Arbeitsverzeichnis der Tests). */
    private val roots = listOf(File("src/main/res"), File("../wear/src/main/res"))

    /** Praefix je Bereichsdatei (siehe docs/i18n.md, Abschnitt A). */
    private val areaPrefixes = mapOf(
        "strings_today.xml" to "today_",
        "strings_map.xml" to "map_",
        "strings_rides.xml" to "rides_",
        "strings_training.xml" to "training_",
        "strings_more.xml" to "more_",
        "strings_shell.xml" to "shell_",
        "strings_common.xml" to "common_",
        "strings_ble_sensors.xml" to "ble_",
        "strings_strava.xml" to "strava_",
    )

    /** Schluessel aus der Zeit vor den Bereichsdateien — sie behalten ihren Namen. */
    private val legacyPrefixes = listOf("recording_", "reminder_", "segment_", "notification_", "wear_")

    private data class Entry(val values: List<String>, val quantities: Set<String>)

    @Test
    fun `beide Ressourcenwurzeln existieren`() {
        for (root in roots) {
            assertTrue(File(root, "values").isDirectory, "Fehlt: ${root.path}/values")
        }
    }

    @Test
    fun `jede deutsche Datei hat ein englisches Gegenstueck mit denselben Schluesseln`() {
        val problems = mutableListOf<String>()
        for (root in roots) {
            val deDir = File(root, "values")
            val enDir = File(root, "values-en")
            val deFiles = deDir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
            for (deFile in deFiles.sortedBy { it.name }) {
                val de = readEntries(deFile)
                val enFile = File(enDir, deFile.name)
                if (de.isEmpty()) {
                    // Nur Nicht-Uebersetzbares (z. B. Sprachnamen) — kein Gegenstueck noetig.
                    continue
                }
                if (!enFile.isFile) {
                    problems += "${root.path}: values-en/${deFile.name} fehlt"
                    continue
                }
                val en = readEntries(enFile)
                val missing = de.keys - en.keys
                val extra = en.keys - de.keys
                if (missing.isNotEmpty()) problems += "${deFile.name}: in values-en fehlen $missing"
                if (extra.isNotEmpty()) problems += "${deFile.name}: nur in values-en $extra"
                for (key in de.keys intersect en.keys) {
                    val dePlaceholders = de.getValue(key).values.map(::placeholders).toSet()
                    val enPlaceholders = en.getValue(key).values.map(::placeholders).toSet()
                    if (dePlaceholders != enPlaceholders) {
                        problems += "${deFile.name}/$key: Platzhalter DE $dePlaceholders ≠ EN $enPlaceholders"
                    }
                    if (de.getValue(key).quantities != en.getValue(key).quantities) {
                        problems += "${deFile.name}/$key: Plural-Kategorien unterscheiden sich"
                    }
                }
            }
            // Keine englische Datei ohne deutsches Original.
            enDir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().forEach { enFile ->
                if (!File(deDir, enFile.name).isFile) problems += "${root.path}: values-en/${enFile.name} ohne Original"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `Werte sind nicht leer, Plurals vollstaendig, Platzhalter positionsbezogen`() {
        val problems = mutableListOf<String>()
        forEachFile { dir, file, entries ->
            for ((key, entry) in entries) {
                if (entry.values.any { it.isBlank() }) problems += "$dir/${file.name}/$key ist leer"
                if (entry.quantities.isNotEmpty() && !entry.quantities.containsAll(setOf("one", "other"))) {
                    problems += "$dir/${file.name}/$key: Plurals brauchen one und other"
                }
                for (value in entry.values) {
                    val unpositioned = Regex("""%(?!\d+\$)(?!%)[a-zA-Z]""").find(value.replace("%%", ""))
                    if (unpositioned != null) {
                        problems += "$dir/${file.name}/$key: Platzhalter ohne Position (${unpositioned.value})"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `Schluessel tragen das Praefix ihrer Bereichsdatei`() {
        val problems = mutableListOf<String>()
        forEachFile { dir, file, entries ->
            val prefix = areaPrefixes[file.name]
            for (key in entries.keys) {
                val ok = when {
                    prefix != null -> key.startsWith(prefix)
                    else -> legacyPrefixes.any { key.startsWith(it) }
                }
                if (!ok) problems += "$dir/${file.name}/$key: falsches Praefix (erwartet ${prefix ?: legacyPrefixes})"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `englische Texte benutzen englische Anfuehrungszeichen`() {
        val problems = mutableListOf<String>()
        forEachFile { dir, file, entries ->
            if (!dir.endsWith("values-en")) return@forEachFile
            for ((key, entry) in entries) {
                if (entry.values.any { it.contains('„') }) problems += "${file.name}/$key enthaelt „"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `Bereichsdateien gibt es in beiden Sprachen`() {
        val res = roots.first()
        for (name in areaPrefixes.keys) {
            assertTrue(File(res, "values/$name").isFile, "values/$name fehlt")
            assertTrue(File(res, "values-en/$name").isFile, "values-en/$name fehlt")
        }
        assertEquals(
            listOf("de", "en"),
            Regex("""android:name="(\w+)"""")
                .findAll(File(res, "xml/locales_config.xml").readText())
                .map { it.groupValues[1] }
                .toList(),
        )
    }

    private fun forEachFile(block: (dir: String, file: File, entries: Map<String, Entry>) -> Unit) {
        for (root in roots) {
            for (dirName in listOf("values", "values-en")) {
                val dir = File(root, dirName)
                dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
                    .sortedBy { it.name }
                    .forEach { block("${root.path}/$dirName", it, readEntries(it)) }
            }
        }
    }

    /** Alle uebersetzbaren Strings und Plurals einer Datei. */
    private fun readEntries(file: File): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            if (el.getAttribute("translatable") == "false") continue
            val name = el.getAttribute("name")
            when (el.tagName) {
                "string" -> result[name] = Entry(listOf(el.textContent), emptySet())
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    val values = mutableListOf<String>()
                    val quantities = mutableSetOf<String>()
                    for (k in 0 until items.length) {
                        val item = items.item(k) as Element
                        values += item.textContent
                        quantities += item.getAttribute("quantity")
                    }
                    result[name] = Entry(values, quantities)
                }
            }
        }
        return result
    }

    /** Die Platzhalter eines Werts, sortiert — `%%` zaehlt nicht. */
    private fun placeholders(value: String): List<String> =
        Regex("""%\d+\$[a-zA-Z]""").findAll(value.replace("%%", "")).map { it.value }.toList().sorted()
}
