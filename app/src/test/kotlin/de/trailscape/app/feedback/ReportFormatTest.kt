package de.trailscape.app.feedback

import java.net.URLDecoder
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests des Berichts-Textbaus (`feedback/ReportFormat.kt`).
 *
 * Reiner JVM-Test — genau dafuer ist die Datei frei von Android-Imports: Der
 * Absturzpfad ist der einzige Codeweg der App, der sich am Geraet praktisch
 * nicht ausprobieren laesst, also muss wenigstens sein Ergebnis pruefbar sein.
 */
class ReportFormatTest {

    private val info = DeviceInfo(
        appVersionName = "2.0.0",
        appVersionCode = 2042,
        androidRelease = "15",
        androidSdk = 35,
        manufacturer = "samsung",
        model = "SM-S911B",
    )

    private val memory = MemoryInfo(
        freeBytes = 12L * 1024 * 1024,
        totalBytes = 64L * 1024 * 1024,
        maxBytes = 256L * 1024 * 1024,
    )

    private fun crashReport(stackTrace: String = "java.lang.IllegalStateException: kaputt\n\tat A.b(A.kt:1)") =
        buildCrashReport(
            info = info,
            timestamp = "2026-08-09 14:03:11",
            threadName = "main",
            stackTrace = stackTrace,
            memory = memory,
        )

    @Test
    fun `Absturzbericht enthaelt alle technischen Eckdaten`() {
        val report = crashReport()

        assertContains(report, "Trailscape-Absturzbericht")
        assertContains(report, "Zeitpunkt:    2026-08-09 14:03:11")
        assertContains(report, "App-Version:  2.0.0 (2042)")
        assertContains(report, "Android:      15 (API 35)")
        assertContains(report, "Gerät:        samsung SM-S911B")
        assertContains(report, "Thread:       main")
        assertContains(report, "java.lang.IllegalStateException: kaputt")
    }

    @Test
    fun `Speicherangaben stehen in Megabyte`() {
        val report = crashReport()

        // belegt = total - frei = 64 - 12 = 52 MB
        assertContains(report, "frei 12,0 MB · belegt 52,0 MB · Limit 256,0 MB")
    }

    @Test
    fun `Bericht weist ausdruecklich auf fehlende Standort- und Tourdaten hin`() {
        assertContains(crashReport(), REPORT_PRIVACY_NOTE)
        assertContains(buildProblemReport(info, "2026-08-09 14:03:11"), REPORT_PRIVACY_NOTE)
    }

    @Test
    fun `Problembericht haengt Health-Diagnose nur an wenn Zeilen da sind`() {
        val ohne = buildProblemReport(info, "2026-08-09 14:03:11")
        assertFalse(ohne.contains("Health-Sync-Diagnose"))

        val mit = buildProblemReport(
            info = info,
            timestamp = "2026-08-09 14:03:11",
            healthDiagnostics = listOf("Fenster: 30 Tage", "Workouts: 4"),
        )
        assertContains(mit, "Health-Sync-Diagnose")
        assertContains(mit, "Fenster: 30 Tage")
        assertContains(mit, "Workouts: 4")
        // Kein Speicherstand im Problembericht — der sagt ohne Absturz nichts.
        assertFalse(mit.contains("Speicher:"))
    }

    @Test
    fun `kurzer Bericht wird nicht gekuerzt`() {
        val kurz = "a".repeat(ISSUE_BODY_MAX_CHARS)
        assertEquals(kurz, truncateForIssueBody(kurz))
    }

    @Test
    fun `langer Bericht wird gekuerzt und bekommt einen Hinweis aufs Teilen`() {
        val lang = "a".repeat(ISSUE_BODY_MAX_CHARS + 500)
        val gekuerzt = truncateForIssueBody(lang)

        assertEquals(ISSUE_BODY_MAX_CHARS + ISSUE_TRUNCATION_NOTICE.length, gekuerzt.length)
        assertTrue(gekuerzt.startsWith("a".repeat(ISSUE_BODY_MAX_CHARS)))
        assertContains(gekuerzt, "Teilen")
    }

    @Test
    fun `Issue-Body traegt eine leere Rubrik fuer die Beschreibung und den Bericht im Codeblock`() {
        val body = buildIssueBody(crashReport(), reportHeading = "Absturzbericht")

        assertContains(body, "## Was ist passiert?")
        assertContains(body, "## Absturzbericht")
        assertContains(body, "```text")
        assertTrue(body.trimEnd().endsWith("```"))
        assertContains(body, "java.lang.IllegalStateException: kaputt")
    }

    @Test
    fun `Issue-Link ist URL-kodiert und laesst sich wieder auspacken`() {
        val body = buildIssueBody(crashReport(), reportHeading = "Absturzbericht")
        val url = buildIssueUrl("Absturz: IllegalStateException", body)

        assertTrue(url.startsWith("$NEW_ISSUE_URL?title="))
        // Roh darf im Link weder ein Zeilenumbruch noch ein Leerzeichen stehen.
        assertFalse(url.contains('\n'))
        assertFalse(url.contains(' '))

        val decodedTitle = URLDecoder.decode(url.substringAfter("?title=").substringBefore("&body="), "UTF-8")
        val decodedBody = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertEquals("Absturz: IllegalStateException", decodedTitle)
        assertEquals(body, decodedBody)
    }

    @Test
    fun `Issue-Titel kuerzt den Paketnamen weg und behaelt die Meldung`() {
        assertEquals(
            "Absturz: IllegalStateException: Karte nicht bereit",
            crashIssueTitle("java.lang.IllegalStateException: Karte nicht bereit\n\tat A.b(A.kt:1)"),
        )
    }

    @Test
    fun `ein Punkt in der Meldung bringt den Titel nicht durcheinander`() {
        assertEquals(
            "Absturz: IOException: Datei fehlt. Bitte neu laden.",
            crashIssueTitle("java.io.IOException: Datei fehlt. Bitte neu laden."),
        )
    }

    @Test
    fun `Ausnahme ohne Meldung ergibt nur den Klassennamen`() {
        assertEquals(
            "Absturz: NullPointerException",
            crashIssueTitle("java.lang.NullPointerException\n\tat A.b(A.kt:1)"),
        )
    }

    @Test
    fun `leerer Stacktrace ergibt einen sprechenden Ersatztitel`() {
        assertEquals("Absturz: unbekannter Fehler", crashIssueTitle(""))
    }

    @Test
    fun `Titel wird aus dem gespeicherten Bericht wiedergewonnen`() {
        assertEquals(
            "Absturz: IllegalStateException: kaputt",
            crashIssueTitleFromReport(crashReport()),
        )
    }

    @Test
    fun `Zeitstempel haengt nicht von der Zeitzone des Build-Rechners ab`() {
        // 2026-08-09T12:00:00Z
        val epochMs = 1_786_276_800_000L
        assertEquals("2026-08-09 12:00:00", formatReportTimestamp(epochMs, ZoneId.of("UTC")))
        assertEquals("2026-08-09 14:00:00", formatReportTimestamp(epochMs, ZoneId.of("Europe/Berlin")))
    }
}
