package de.trailscape.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * GPX-Import/-Export fuer Trailscape.
 *
 * 1:1-Portierung von `lib/gpx.dart` (nutzt dort `package:xml`). Parst GPX
 * 1.0/1.1-Dateien (Track- und Routenpunkte) und erzeugt valide
 * GPX-1.1-Dateien aus einer Liste von [TrackPoint]s.
 *
 * Bewusst ohne `javax.xml.stream` (StAX) — existiert auf Android nicht.
 * Verwendet stattdessen ausschliesslich `javax.xml.parsers` (DOM),
 * `org.w3c.dom` und `org.xml.sax`, die auf der Android-Laufzeit vorhanden
 * sind, sowie einen handgeschriebenen XML-Writer fuer [buildGpx].
 */

/**
 * Fehler beim Einlesen einer GPX- oder Backup-Datei — Aequivalent zu Darts
 * `FormatException`, inkl. der (deutschsprachigen) Nutzer-Fehlermeldung.
 */
class FormatException(message: String) : Exception(message)

/** Ergebnis von [parseGpx]: Name (falls vorhanden) sowie alle Trackpunkte in Reihenfolge. */
data class GpxParseResult(val name: String?, val points: List<TrackPoint>)

/**
 * Namespace der Garmin-TrackPointExtension, in der die Herzfrequenz je
 * Trackpunkt transportiert wird (`gpxtpx:hr`) — de-facto-Standard, den auch
 * Komoot, Strava & Co. beim GPX-Export verwenden.
 */
private const val GARMIN_TRACK_POINT_EXTENSION_NS =
    "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"

private val ISO_UTC_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Entspricht Darts `DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true).toIso8601String()`. */
internal fun formatIso8601Utc(epochMs: Long): String = ISO_UTC_FORMATTER.format(Instant.ofEpochMilli(epochMs))

// ---------------------------------------------------------------------------
// Parsing-Hilfsfunktionen (DOM, namensraum-tolerant ueber lokale Namen)
// ---------------------------------------------------------------------------

/** Liest den unqualifizierten (lokalen) Tag-Namen eines Elements, unabhaengig vom Praefix. */
private fun localName(el: Element): String {
    val tag = el.tagName
    val idx = tag.indexOf(':')
    return if (idx >= 0) tag.substring(idx + 1) else tag
}

private fun childElements(el: Element): List<Element> {
    val result = mutableListOf<Element>()
    var node = el.firstChild
    while (node != null) {
        if (node is Element) result.add(node)
        node = node.nextSibling
    }
    return result
}

private fun descendantElements(el: Element): List<Element> {
    val result = mutableListOf<Element>()
    fun walk(e: Element) {
        for (child in childElements(e)) {
            result.add(child)
            walk(child)
        }
    }
    walk(el)
    return result
}

/** Aequivalent zu `XmlDocument.findAllElements`: alle Nachfahren (inkl. sich selbst) mit gegebenem lokalen Namen. */
private fun findAllByLocalName(root: Element, tagName: String): List<Element> {
    val result = mutableListOf<Element>()
    fun walk(e: Element) {
        if (localName(e) == tagName) result.add(e)
        for (child in childElements(e)) walk(child)
    }
    walk(root)
    return result
}

/** Sucht den ersten direkten Kind-Text eines Elements mit gegebenem (unqualifiziertem) Tag-Namen. */
private fun findChildText(parent: Element, tagName: String): String? {
    for (child in childElements(parent)) {
        if (localName(child) == tagName) return child.textContent
    }
    return null
}

/**
 * Sucht den Text des ersten Nachfahren-Elements mit gegebenem (unqualifiziertem)
 * Tag-Namen — anders als [findChildText] auch beliebig tief verschachtelt, wie
 * es `gpxtpx:hr` innerhalb von `<extensions><gpxtpx:TrackPointExtension>` ist.
 */
private fun findDescendantText(parent: Element, tagName: String): String? {
    for (el in descendantElements(parent)) {
        if (localName(el) == tagName) {
            val text = el.textContent.trim()
            if (text.isNotEmpty()) return text
        }
    }
    return null
}

private fun attrOrNull(el: Element, name: String): String? =
    if (el.hasAttribute(name)) el.getAttribute(name) else null

private fun parseTimeToMs(raw: String?): Long? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return try {
        OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        try {
            Instant.parse(trimmed).toEpochMilli()
        } catch (e2: DateTimeParseException) {
            null
        }
    }
}

private fun parseEleM(raw: String?): Double? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toDoubleOrNull() ?: return null
    if (!value.isFinite()) return null
    return value
}

private fun parseHrBpm(raw: String?): Int? {
    val value = parseEleM(raw) ?: return null
    return dartRound(value).toInt()
}

private fun parsePoint(el: Element): TrackPoint {
    val lat = attrOrNull(el, "lat")?.trim()?.toDoubleOrNull()
    val lon = attrOrNull(el, "lon")?.trim()?.toDoubleOrNull()

    if (lat == null || !lat.isFinite() || lon == null || !lon.isFinite()) {
        throw FormatException("Ungültige Koordinaten in der GPX-Datei.")
    }

    return TrackPoint(
        lat = lat,
        lon = lon,
        ele = parseEleM(findChildText(el, "ele")),
        time = parseTimeToMs(findChildText(el, "time")),
        hr = parseHrBpm(findDescendantText(el, "hr")),
    )
}

private fun findName(root: Element): String? {
    val nameEls = findAllByLocalName(root, "name")

    for (el in nameEls) {
        val parent = el.parentNode as? Element
        if (parent != null && localName(parent) == "trk") {
            val text = el.textContent.trim()
            if (text.isNotEmpty()) return text
        }
    }

    for (el in nameEls) {
        val parent = el.parentNode as? Element
        if (parent != null && localName(parent) == "metadata") {
            val text = el.textContent.trim()
            if (text.isNotEmpty()) return text
        }
    }

    return null
}

/**
 * Parst eine GPX-1.0/1.1-Datei (String) und liefert Name sowie alle
 * Trackpunkte in Reihenfolge. Faellt auf Routenpunkte (`rtept`) zurueck,
 * falls keine Trackpunkte vorhanden sind.
 */
fun parseGpx(xmlString: String): GpxParseResult {
    val doc: Document
    try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            isExpandEntityReferences = false
            isIgnoringComments = true
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (ignored: Exception) {
                // Feature evtl. nicht unterstuetzt (z. B. anderer XML-Parser-Provider) — dann ohne Haerte weiter.
            }
        }
        val builder = factory.newDocumentBuilder()
        // Anders als der strenge Java-DOM-Parser toleriert Darts package:xml
        // fuehrende Leerzeichen/Zeilenumbrueche vor der XML-Deklaration (z. B.
        // aus mehrzeiligen String-Literalen in Tests oder manchen Export-Tools)
        // — per `trim()` bilden wir dieselbe Nachsicht nach.
        doc = builder.parse(InputSource(StringReader(xmlString.trim())))
    } catch (e: Exception) {
        throw FormatException("Die GPX-Datei enthält ungültiges XML.")
    }

    val root = doc.documentElement ?: throw FormatException("Die GPX-Datei enthält ungültiges XML.")
    if (localName(root) != "gpx") {
        throw FormatException("Die Datei ist keine gültige GPX-Datei.")
    }

    var pointEls = findAllByLocalName(root, "trkpt")
    if (pointEls.isEmpty()) {
        pointEls = findAllByLocalName(root, "rtept")
    }

    if (pointEls.isEmpty()) {
        throw FormatException("Die GPX-Datei enthält keine Trackpunkte.")
    }

    val points = pointEls.map { parsePoint(it) }
    val name = findName(root)

    return GpxParseResult(name = name, points = points)
}

// ---------------------------------------------------------------------------
// Erzeugen von GPX
// ---------------------------------------------------------------------------

private fun escapeXmlText(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}

private fun escapeXmlAttr(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\n' -> append("&#10;")
            '\t' -> append("&#9;")
            '\r' -> append("&#13;")
            else -> append(c)
        }
    }
}

/**
 * Erzeugt eine valide GPX-1.1-Datei mit einem einzelnen Track/Segment.
 *
 * Traegt ein Trackpunkt eine Herzfrequenz ([TrackPoint.hr]), wird sie als
 * Garmin-TrackPointExtension (`gpxtpx:hr`) mitgeschrieben — das Format, das
 * auch Komoot, Strava und die meisten Sportuhren beim GPX-Export nutzen und
 * das beim erneuten Einlesen (siehe [parseGpx]) wieder erkannt wird.
 *
 * [time] wird — falls angegeben (ms seit Epoch) — zusaetzlich als
 * `<metadata><time>` geschrieben (z. B. der Aufnahmezeitpunkt einer Tour).
 */
fun buildGpx(name: String, points: List<TrackPoint>, time: Long? = null): String {
    val hasHr = points.any { it.hr != null }

    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"Trailscape\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
    if (hasHr) {
        sb.append(" xmlns:gpxtpx=\"").append(GARMIN_TRACK_POINT_EXTENSION_NS).append('"')
    }
    sb.append(">\n")

    sb.append("  <metadata>\n")
    sb.append("    <name>").append(escapeXmlText(name)).append("</name>\n")
    if (time != null) {
        sb.append("    <time>").append(formatIso8601Utc(time)).append("</time>\n")
    }
    sb.append("  </metadata>\n")

    sb.append("  <trk>\n")
    sb.append("    <name>").append(escapeXmlText(name)).append("</name>\n")
    sb.append("    <trkseg>\n")
    for (point in points) {
        val hasChildren = point.ele != null || point.time != null || point.hr != null
        sb.append("      <trkpt lat=\"")
            .append(escapeXmlAttr(point.lat.toString()))
            .append("\" lon=\"")
            .append(escapeXmlAttr(point.lon.toString()))
            .append('"')
        if (!hasChildren) {
            sb.append("/>\n")
            continue
        }
        sb.append(">\n")
        if (point.ele != null) {
            sb.append("        <ele>").append(point.ele).append("</ele>\n")
        }
        if (point.time != null) {
            sb.append("        <time>").append(formatIso8601Utc(point.time)).append("</time>\n")
        }
        if (point.hr != null) {
            sb.append("        <extensions>\n")
            sb.append("          <gpxtpx:TrackPointExtension>\n")
            sb.append("            <gpxtpx:hr>").append(point.hr).append("</gpxtpx:hr>\n")
            sb.append("          </gpxtpx:TrackPointExtension>\n")
            sb.append("        </extensions>\n")
        }
        sb.append("      </trkpt>\n")
    }
    sb.append("    </trkseg>\n")
    sb.append("  </trk>\n")
    sb.append("</gpx>\n")

    return sb.toString()
}
