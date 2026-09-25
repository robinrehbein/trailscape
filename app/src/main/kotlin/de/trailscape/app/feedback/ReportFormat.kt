package de.trailscape.app.feedback

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Textbau der beiden Melde-Wege (Absturzbericht und „Problem melden") —
 * **bewusst ohne einen einzigen Android-Import**.
 *
 * Zwei Gruende:
 *  1. Der Absturzbericht entsteht im `uncaughtExceptionHandler`, also in einem
 *     Prozess, der gerade stirbt. Was dort laeuft, muss so wenig wie moeglich
 *     tun und darf nichts anfassen, das seinerseits scheitern kann. Hier steht
 *     nur String-Verkettung.
 *  2. `:app` hat kein Robolectric (siehe `app/build.gradle.kts`). Alles, was
 *     ohne `Context` auskommt, ist damit als reiner JVM-Test pruefbar —
 *     siehe `app/src/test/.../feedback/ReportFormatTest.kt`.
 *
 * Die Android-Seite (Geraetedaten einsammeln, Datei schreiben, Intents)
 * liegt in [CrashReporter] bzw. `ReportSharing.kt`.
 *
 * ## Was in einem Bericht steht — und was nicht
 * Ausschliesslich Technik: App-Version, Android-Version, Geraetemodell,
 * Speicherstand, Zeitstempel, der Stacktrace und — solange der Nutzer den
 * Haken nicht entfernt — das Diagnose-Log (`core/DiagLog.kt`: feste
 * Ereignisnamen, Zahlen, Fehlerklassen). **Keine** Standortpunkte, keine
 * Touren, keine Gesundheitsdaten, keine Sync-Zugangsdaten. Der einzige Text,
 * den Trailscape nicht selbst zusammensetzt, ist der Stacktrace; die App
 * traegt dort keine Nutzerdaten hinein.
 */

/** GitHub-Repository, in dem Fehler gemeldet werden. */
const val ISSUE_REPOSITORY_URL: String = "https://github.com/robinrehbein/trailscape"

/** Formular fuer einen neuen Fehlerbericht (nimmt `title`/`body` als Query-Parameter). */
const val NEW_ISSUE_URL: String = "$ISSUE_REPOSITORY_URL/issues/new"

/**
 * Ab dieser Laenge wird der Bericht im vorbefuellten GitHub-Link gekuerzt.
 *
 * Ein `issues/new`-Link traegt den Body URL-kodiert in der Query; sehr lange
 * URLs weisen Browser und Server irgendwann zurueck. 6000 Zeichen sind
 * grosszuegig fuer einen Stacktrace und bleiben unter den ueblichen Grenzen.
 * Wer den vollstaendigen Bericht braucht, nimmt „Teilen" — der Weg kennt
 * keine Laengenbegrenzung.
 */
const val ISSUE_BODY_MAX_CHARS: Int = 6000

/** Hinweis, der an einen gekuerzten Bericht angehaengt wird. */
const val ISSUE_TRUNCATION_NOTICE: String =
    "\n\n[… gekürzt. Der vollständige Bericht passt nicht in einen GitHub-Link — " +
        "bitte in der App auf „Teilen“ tippen und den kompletten Text hier einfügen.]"

/** Titel des Absturz-Issues. */
const val CRASH_ISSUE_TITLE: String = "Absturz: "

/**
 * Titel-Vorschlag des allgemeinen Problem-Issues. Bewusst ein Platzhalter,
 * den der Nutzer im GitHub-Formular ueberschreibt — die App kann nicht wissen,
 * was schiefgelaufen ist.
 */
const val PROBLEM_ISSUE_TITLE: String = "Problem: (bitte kurz beschreiben)"

/** Satz, der in jedem Bericht klarstellt, was NICHT enthalten ist. */
const val REPORT_PRIVACY_NOTE: String =
    "Dieser Bericht enthält ausschließlich technische Angaben — keine Standort-, " +
        "Touren- oder Gesundheitsdaten."

/**
 * Technische Eckdaten des Geraets und der Installation.
 *
 * Wird auf der Android-Seite von `CrashReporter.currentDeviceInfo()` gefuellt
 * und hier nur noch formatiert.
 */
data class DeviceInfo(
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val androidSdk: Int,
    val manufacturer: String,
    val model: String,
) {
    companion object {
        /** Platzhalter, falls sich die Werte nicht ermitteln lassen. */
        val UNKNOWN: DeviceInfo = DeviceInfo(
            appVersionName = "unbekannt",
            appVersionCode = 0,
            androidRelease = "unbekannt",
            androidSdk = 0,
            manufacturer = "unbekannt",
            model = "unbekannt",
        )
    }
}

/**
 * Speicherstand der JVM zum Zeitpunkt des Berichts (aus `Runtime`), in Bytes.
 *
 * Nicht der Systemspeicher: Fuer die Fehlersuche ist interessant, wie nah der
 * Heap an seinem Limit stand — genau das sagen diese drei Zahlen.
 */
data class MemoryInfo(
    val freeBytes: Long,
    val totalBytes: Long,
    val maxBytes: Long,
) {
    /** Tatsaechlich belegter Heap (`total - free`). */
    val usedBytes: Long get() = totalBytes - freeBytes
}

/** Zeitstempel-Format der Berichte: lokal, sekundengenau, ohne Zeitzonen-Raterei. */
private val reportTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)

/**
 * Formatiert einen Zeitpunkt fuer die Kopfzeile eines Berichts.
 *
 * [zone] ist ein Parameter (statt `ZoneId.systemDefault()` intern), damit der
 * Test nicht von der Zeitzone des Build-Rechners abhaengt.
 */
fun formatReportTimestamp(epochMs: Long, zone: ZoneId): String =
    reportTimestampFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** `12,3 MB` — deutsche Schreibweise, eine Nachkommastelle. */
fun formatMegabytes(bytes: Long): String =
    String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024.0))

/**
 * Die gemeinsame Kopfzeilen-Tabelle beider Berichtsarten. Ausgerichtete
 * Labels, damit der Bericht auch als Klartext im Issue lesbar bleibt.
 */
private fun headerLines(
    info: DeviceInfo,
    timestamp: String,
    memory: MemoryInfo?,
): List<String> = buildList {
    add("Zeitpunkt:    $timestamp")
    add("App-Version:  ${info.appVersionName} (${info.appVersionCode})")
    add("Android:      ${info.androidRelease} (API ${info.androidSdk})")
    add("Gerät:        ${info.manufacturer} ${info.model}")
    if (memory != null) {
        add(
            "Speicher:     frei ${formatMegabytes(memory.freeBytes)} · " +
                "belegt ${formatMegabytes(memory.usedBytes)} · " +
                "Limit ${formatMegabytes(memory.maxBytes)}",
        )
    }
}

/**
 * Baut den Text, der bei einem Absturz nach `<filesDir>/crash/last-crash.txt`
 * geschrieben wird — reine String-Arbeit, damit im Absturzpfad nichts
 * Aufwendiges passiert.
 *
 * @param stackTrace bereits fertiger Stacktrace (inkl. Ursachen), siehe
 *   `Throwable.stackTraceToString()`.
 */
fun buildCrashReport(
    info: DeviceInfo,
    timestamp: String,
    threadName: String,
    stackTrace: String,
    memory: MemoryInfo,
    diagLines: List<String>? = null,
): String = buildString {
    appendLine("Trailscape-Absturzbericht")
    appendLine("=========================")
    headerLines(info, timestamp, memory).forEach { appendLine(it) }
    appendLine("Thread:       $threadName")
    appendLine()
    appendLine(REPORT_PRIVACY_NOTE)
    appendLine()
    append(STACK_TRACE_MARKER)
    append(stackTrace.trimEnd())
    appendLine()
    // Die Diagnose steht HINTER dem Stacktrace: [crashIssueTitleFromReport]
    // liest nur dessen erste Zeile, der Titel bleibt also unveraendert, und
    // [withoutDiagSection] kann den Abschnitt spurlos wieder abschneiden.
    if (diagLines != null) appendDiagSection(diagLines)
}

/**
 * Kopfzeile des Diagnose-Abschnitts. Konstante, weil [withoutDiagSection]
 * den Bericht daran wieder aufteilt.
 */
const val DIAG_SECTION_MARKER: String =
    "Technische Diagnose (neueste zuerst)\n------------------------------------\n"

/** Platzhalter, wenn der Anhang gewaehlt, das Log aber leer ist. */
const val DIAG_EMPTY_NOTE: String = "(keine Einträge)"

/**
 * Hoechstzahl der Diagnose-Zeilen in einem Bericht. Eine Zeile sind rund
 * 60-100 Zeichen; 150 Zeilen bleiben gut lesbar und passen zusammen mit
 * dem Kopf in eine Mail. Das vollstaendige Log (bis 128 KB) waere fuer einen
 * Menschen ohnehin nicht mehr zu ueberblicken.
 */
const val DIAG_REPORT_MAX_LINES: Int = 150

/**
 * Waehlt die Diagnose-Zeilen fuer einen Bericht: die juengsten [maxLines],
 * **neueste zuerst**. Die Reihenfolge ist Absicht: Der GitHub-Link kuerzt
 * den Bericht am Ende ([truncateForIssueBody]) — dort sollen die aeltesten
 * Eintraege wegfallen, nicht die zum Problem gehoerenden.
 *
 * @param lines alle Zeilen, aelteste zuerst (so liefert sie `DiagLog`).
 */
fun selectDiagLines(lines: List<String>, maxLines: Int = DIAG_REPORT_MAX_LINES): List<String> =
    lines.takeLast(maxLines).asReversed()

private fun StringBuilder.appendDiagSection(diagLines: List<String>) {
    appendLine()
    append(DIAG_SECTION_MARKER)
    if (diagLines.isEmpty()) {
        appendLine(DIAG_EMPTY_NOTE)
    } else {
        diagLines.forEach { appendLine(it) }
    }
}

/**
 * Der Bericht ohne Diagnose-Abschnitt — fuer die abgewaehlte Checkbox im
 * Absturz-Dialog, dessen Bericht schon im Absturz fertig geschrieben wurde.
 * Ohne Abschnitt geht der Bericht unveraendert durch.
 */
fun withoutDiagSection(report: String): String {
    val index = report.indexOf("\n\n$DIAG_SECTION_MARKER")
    return if (index < 0) report else report.substring(0, index + 1)
}

/** Ob der Bericht einen Diagnose-Abschnitt enthaelt. */
fun hasDiagSection(report: String): Boolean = report.contains("\n\n$DIAG_SECTION_MARKER")

/**
 * Trennzeile vor dem Stacktrace. Steht als Konstante da, weil
 * [crashIssueTitleFromReport] den Bericht daran wieder aufteilt — Bauen und
 * Lesen duerfen nicht auseinanderlaufen.
 */
const val STACK_TRACE_MARKER: String = "Stacktrace\n----------\n"

/**
 * Baut den Text fuer „Problem melden" aus dem Mehr-Screen.
 *
 * @param healthDiagnostics die `debugLines` des letzten Health-Sync-Reports —
 *   leer, wenn der Nutzer den Anhang nicht angehakt hat oder es keinen Report
 *   gibt. Die Zeilen enthalten Zaehler und Zeitraeume, keine Messwerte.
 * @param diagLines Zeilen des Diagnose-Logs (bereits per [selectDiagLines]
 *   ausgewaehlt), oder `null`, wenn der Nutzer den Anhang abgewaehlt hat.
 *   Eine leere Liste erscheint als [DIAG_EMPTY_NOTE] — damit sichtbar ist,
 *   dass der Anhang gewaehlt, aber nichts protokolliert war.
 */
fun buildProblemReport(
    info: DeviceInfo,
    timestamp: String,
    healthDiagnostics: List<String> = emptyList(),
    diagLines: List<String>? = null,
): String = buildString {
    appendLine("Trailscape-Problembericht")
    appendLine("=========================")
    headerLines(info, timestamp, memory = null).forEach { appendLine(it) }
    appendLine()
    appendLine(REPORT_PRIVACY_NOTE)
    if (healthDiagnostics.isNotEmpty()) {
        appendLine()
        appendLine("Health-Sync-Diagnose")
        appendLine("--------------------")
        healthDiagnostics.forEach { appendLine(it) }
    }
    if (diagLines != null) appendDiagSection(diagLines)
}

/**
 * Kuerzt einen Bericht auf [maxChars] und haengt [ISSUE_TRUNCATION_NOTICE] an.
 * Kurze Berichte gehen unveraendert durch.
 */
fun truncateForIssueBody(report: String, maxChars: Int = ISSUE_BODY_MAX_CHARS): String {
    if (report.length <= maxChars) return report
    return report.take(maxChars) + ISSUE_TRUNCATION_NOTICE
}

/**
 * Setzt den Markdown-Body eines GitHub-Issues zusammen: erst eine leere
 * Rubrik fuer die Beschreibung des Nutzers (die kann nur er ausfuellen), dann
 * der Bericht in einem Codeblock.
 */
fun buildIssueBody(
    report: String,
    reportHeading: String,
    maxChars: Int = ISSUE_BODY_MAX_CHARS,
): String = buildString {
    appendLine("## Was ist passiert?")
    appendLine()
    appendLine("<!-- Bitte kurz beschreiben: Was hast du gemacht, was hast du erwartet? -->")
    appendLine()
    appendLine("## $reportHeading")
    appendLine()
    appendLine("```text")
    appendLine(truncateForIssueBody(report, maxChars))
    append("```")
}

/**
 * Baut den `issues/new`-Link mit vorbefuelltem Titel und Body.
 *
 * `URLEncoder` kodiert Leerzeichen als `+`; das ist in einer Query gueltig und
 * wird von GitHub korrekt zurueckuebersetzt.
 */
fun buildIssueUrl(title: String, body: String): String {
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    val encodedBody = URLEncoder.encode(body, "UTF-8")
    return "$NEW_ISSUE_URL?title=$encodedTitle&body=$encodedBody"
}

/**
 * Titelvorschlag fuer ein Absturz-Issue: Ausnahmeklasse (ohne Paket) plus
 * erste Zeile der Meldung, damit gleichartige Abstuerze im Issue-Tracker
 * aufeinander zu finden sind.
 */
fun crashIssueTitle(stackTrace: String): String {
    val firstLine = stackTrace.lineSequence().firstOrNull()?.trim().orEmpty()
    if (firstLine.isBlank()) return CRASH_ISSUE_TITLE + "unbekannter Fehler"
    // Nur der Teil VOR dem ersten Doppelpunkt ist der Klassenname; ein Punkt
    // in der Meldung dahinter darf die Kuerzung nicht durcheinanderbringen.
    val className = firstLine.substringBefore(':')
    val message = firstLine.substringAfter(':', "").trim()
    val shortClass = className.substringAfterLast('.')
    val summary = if (message.isEmpty()) shortClass else "$shortClass: $message"
    return CRASH_ISSUE_TITLE + summary.take(120)
}

/**
 * Wie [crashIssueTitle], nur ausgehend vom kompletten gespeicherten Bericht:
 * schneidet alles vor dem Stacktrace ab und benutzt dessen erste Zeile.
 */
fun crashIssueTitleFromReport(report: String): String =
    crashIssueTitle(report.substringAfter(STACK_TRACE_MARKER, ""))
