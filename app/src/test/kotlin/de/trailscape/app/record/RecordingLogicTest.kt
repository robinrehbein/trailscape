package de.trailscape.app.record

import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests der Entscheidungslogik hinter der dreistufigen Absturzsicherung.
 *
 * Bis hierher hatte genau der Teil der App, bei dem ein Fehler still Daten
 * kostet, keinen einzigen Test — er steckte im [RecordingService] und war
 * damit in diesem Modul unpruefbar (`:app` hat bewusst kein Robolectric).
 * [RecordingLogic] enthaelt ihn jetzt ohne Android-Import, und was hier steht,
 * ist die Liste der Faelle, an denen eine dreistuendige Ausfahrt haette
 * scheitern koennen.
 */
class RecordingLogicTest {

    private fun punkt(lat: Double, timeMs: Long) =
        TrackPoint(lat = lat, lon = 13.0, ele = 100.0, time = timeMs)

    // ------------------------------------------------- Format des Lebenszeichens

    @Test
    fun `Lebenszeichen mit monotoner Uhr und Boot-Kennung ueberlebt Schreiben und Lesen`() {
        val stempel = HeartbeatStamp(
            wallClockMs = 1_723_118_400_000L,
            elapsedRealtimeMs = 4_711_000L,
            bootId = "1b4e28ba-2fa1-11d2-883f-0016d3cca427",
        )

        assertEquals(stempel, HeartbeatStamp.parse(stempel.serialisiere()))
    }

    @Test
    fun `ein Lebenszeichen im Format von Version 1 x bleibt lesbar`() {
        // Version 1.x schrieb nur die Wanduhr. Ein Journal, das eine aeltere
        // App-Version hinterlassen hat, darf davon nicht unlesbar werden.
        val stempel = assertNotNull(HeartbeatStamp.parse("1723118400000"))

        assertEquals(1_723_118_400_000L, stempel.wallClockMs)
        assertNull(stempel.elapsedRealtimeMs)
        assertNull(stempel.bootId)
    }

    @Test
    fun `unbekannte Boot-Kennung wird als Platzhalter geschrieben und wieder null gelesen`() {
        val stempel = HeartbeatStamp(wallClockMs = 100L, elapsedRealtimeMs = 50L, bootId = null)

        assertEquals("100 50 -", stempel.serialisiere())
        assertNull(assertNotNull(HeartbeatStamp.parse(stempel.serialisiere())).bootId)
    }

    @Test
    fun `unbrauchbarer Inhalt liefert kein Lebenszeichen`() {
        assertNull(HeartbeatStamp.parse(""))
        assertNull(HeartbeatStamp.parse("   "))
        assertNull(HeartbeatStamp.parse("kaputt"))
    }

    // ------------------------------------------------ Bewertung des Lebenszeichens

    @Test
    fun `bei gleicher Boot-Kennung gilt die monotone Uhr und nicht die Wanduhr`() {
        // Der Fall, um den es geht: Das Geraet startete mit falscher Uhr und
        // hat sie per NTP um eine Stunde nach vorn korrigiert. Nach der
        // Wanduhr waere das Lebenszeichen eine Stunde alt — nach der monotonen
        // Uhr sind es zwei Sekunden, und die stimmen.
        val damals = HeartbeatStamp(wallClockMs = 1_000_000L, elapsedRealtimeMs = 60_000L, bootId = "boot-a")
        val jetzt = HeartbeatStamp(
            wallClockMs = 1_000_000L + 60 * 60_000L,
            elapsedRealtimeMs = 62_000L,
            bootId = "boot-a",
        )

        assertEquals(HeartbeatAge.Bekannt(2_000L), bewerteLebenszeichen(damals, jetzt))
    }

    @Test
    fun `eine andere Boot-Kennung heisst Neustart`() {
        val damals = HeartbeatStamp(wallClockMs = 1_000L, elapsedRealtimeMs = 900_000L, bootId = "boot-a")
        val jetzt = HeartbeatStamp(wallClockMs = 2_000L, elapsedRealtimeMs = 5_000L, bootId = "boot-b")

        assertEquals(HeartbeatAge.Neustart, bewerteLebenszeichen(damals, jetzt))
    }

    @Test
    fun `ohne Boot-Kennung verraet eine zurueckgesprungene monotone Uhr den Neustart`() {
        val damals = HeartbeatStamp(wallClockMs = 1_000L, elapsedRealtimeMs = 900_000L)
        val jetzt = HeartbeatStamp(wallClockMs = 2_000L, elapsedRealtimeMs = 5_000L)

        assertEquals(HeartbeatAge.Neustart, bewerteLebenszeichen(damals, jetzt))
    }

    @Test
    fun `ohne Boot-Kennung gilt bei vorwaerts laufender monotoner Uhr deren Abstand`() {
        val damals = HeartbeatStamp(wallClockMs = 1_000L, elapsedRealtimeMs = 10_000L)
        val jetzt = HeartbeatStamp(wallClockMs = 99_000L, elapsedRealtimeMs = 13_000L)

        assertEquals(HeartbeatAge.Bekannt(3_000L), bewerteLebenszeichen(damals, jetzt))
    }

    @Test
    fun `ohne monotone Uhr bleibt es bei der Wanduhr`() {
        val damals = HeartbeatStamp(wallClockMs = 10_000L)
        val jetzt = HeartbeatStamp(wallClockMs = 42_000L, elapsedRealtimeMs = 5L, bootId = "boot-a")

        assertEquals(HeartbeatAge.Bekannt(32_000L), bewerteLebenszeichen(damals, jetzt))
    }

    @Test
    fun `eine zurueckgestellte Wanduhr ergibt kein negatives Alter`() {
        val damals = HeartbeatStamp(wallClockMs = 100_000L)
        val jetzt = HeartbeatStamp(wallClockMs = 40_000L)

        assertEquals(HeartbeatAge.Bekannt(0L), bewerteLebenszeichen(damals, jetzt))
    }

    @Test
    fun `ohne Lebenszeichen ist das Alter unbekannt und nicht unendlich`() {
        assertEquals(
            HeartbeatAge.Unbekannt,
            bewerteLebenszeichen(null, HeartbeatStamp(wallClockMs = 1L)),
        )
    }

    // ------------------------------------------------------- Urteil ueber das Journal

    private val verfall = 30_000L

    @Test
    fun `ein frisches Lebenszeichen verschont das Journal`() {
        assertEquals(
            JournalUrteil.VERSCHONEN,
            beurteileJournal(HeartbeatAge.Bekannt(5_000L), journalAlterMs = 5_000L, verfallsalterMs = verfall),
        )
    }

    @Test
    fun `ein erloschenes Lebenszeichen gibt das Journal zur Wiederherstellung frei`() {
        assertEquals(
            JournalUrteil.WIEDERHERSTELLEN,
            beurteileJournal(HeartbeatAge.Bekannt(30_000L), journalAlterMs = 0L, verfallsalterMs = verfall),
        )
    }

    @Test
    fun `nach einem Neustart des Geraets schreibt niemand mehr - wiederherstellen`() {
        assertEquals(
            JournalUrteil.WIEDERHERSTELLEN,
            beurteileJournal(HeartbeatAge.Neustart, journalAlterMs = 0L, verfallsalterMs = verfall),
        )
    }

    /**
     * Der Kern von Befund 2: Ein fehlgeschlagenes `touchHeartbeat` (voller
     * Speicher) hinterlaesst kein Lebenszeichen. Frueher galt das als „sicher
     * tot", und die Wiederherstellung benannte `active.jsonl` um, waehrend der
     * Dienst ueber seinen offenen Dateideskriptor weiterschrieb — in eine
     * Datei, die anschliessend geloescht wurde. Am Ende war die ganze Tour weg.
     */
    @Test
    fun `ohne Lebenszeichen entscheidet die Aenderungszeit des Journals`() {
        assertEquals(
            JournalUrteil.VERSCHONEN,
            beurteileJournal(HeartbeatAge.Unbekannt, journalAlterMs = 2_000L, verfallsalterMs = verfall),
        )
        assertEquals(
            JournalUrteil.WIEDERHERSTELLEN,
            beurteileJournal(HeartbeatAge.Unbekannt, journalAlterMs = 60_000L, verfallsalterMs = verfall),
        )
    }

    @Test
    fun `sagt auch das Dateisystem nichts, wird verschont`() {
        assertEquals(
            JournalUrteil.VERSCHONEN,
            beurteileJournal(HeartbeatAge.Unbekannt, journalAlterMs = null, verfallsalterMs = verfall),
        )
    }

    // --------------------------------------------------------------- Wettrennen

    @Test
    fun `die Wiederherstellung wartet auf die Entscheidung des Dienstes`() {
        val gate = RecoveryGate(gnadenfristMs = 10_000L)
        val wartetBereits = CountDownLatch(1)
        val fertig = CountDownLatch(1)
        var ergebnis = false

        val wiederherstellung = Thread {
            wartetBereits.countDown()
            ergebnis = gate.warteAufDienst()
            fertig.countDown()
        }
        wiederherstellung.start()
        assertTrue(wartetBereits.await(5, TimeUnit.SECONDS))

        gate.freigeben()

        assertTrue(
            fertig.await(5, TimeUnit.SECONDS),
            "Die Wiederherstellung haette nach der Freigabe sofort weiterlaufen muessen.",
        )
        wiederherstellung.join()
        assertTrue(ergebnis, "Die Wiederherstellung haette die Entscheidung des Dienstes sehen muessen.")
    }

    @Test
    fun `nach der Freigabe wartet die naechste Wiederherstellung gar nicht mehr`() {
        val gate = RecoveryGate(gnadenfristMs = 10_000L)
        gate.freigeben()

        val begonnen = System.nanoTime()
        assertTrue(gate.warteAufDienst())
        assertTrue(
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begonnen) < 5_000L,
            "Der Aufruf haette sofort zurueckkehren muessen.",
        )
    }

    @Test
    fun `meldet sich kein Dienst, laeuft die Wiederherstellung nach der Gnadenfrist trotzdem`() {
        val gnadenfristMs = 120L
        val gate = RecoveryGate(gnadenfristMs = gnadenfristMs)

        val begonnen = System.nanoTime()
        assertFalse(gate.warteAufDienst())
        val gewartetMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begonnen)

        // Nur die untere Schranke pruefen: Eine obere waere eine Wette auf den
        // Scheduler der Testmaschine.
        assertTrue(gewartetMs >= gnadenfristMs - 5, "hat nur $gewartetMs ms gewartet")
    }

    @Test
    fun `eine abgelaufene Gnadenfrist haelt niemanden auf`() {
        val gate = RecoveryGate(gnadenfristMs = 0L)

        assertFalse(gate.warteAufDienst())
    }

    // ------------------------------------------------------- Punkte zusammenfuehren

    @Test
    fun `bei vollstaendiger Datei bleibt es bei den Punkten aus der Datei`() {
        val datei = listOf(punkt(52.0, 1_000L), punkt(52.1, 2_000L))
        val ram = listOf(punkt(52.0, 1_000L), punkt(52.1, 2_000L))

        assertSame(datei, vereinigePunkte(datei, ram))
    }

    @Test
    fun `nach einem Schreibfehler gewinnen die laengeren RAM-Punkte`() {
        val datei = listOf(punkt(52.0, 1_000L))
        val ram = listOf(punkt(52.0, 1_000L), punkt(52.1, 2_000L), punkt(52.2, 3_000L))

        assertEquals(ram, vereinigePunkte(datei, ram))
    }

    @Test
    fun `ein leerer RAM-Stand kann die Datei nicht verkuerzen`() {
        val datei = listOf(punkt(52.0, 1_000L), punkt(52.1, 2_000L))

        assertEquals(datei, vereinigePunkte(datei, emptyList()))
    }

    // ------------------------------------------------------------------ Dauer

    private val stats = RideStats(
        distanceKm = 42.0,
        ascentM = 100.0,
        descentM = 100.0,
        durationS = 9_000,
        movingTimeS = 7_000,
        avgSpeedKmh = 21.0,
    )

    @Test
    fun `die Pausenzeit wird aus der gespeicherten Dauer herausgerechnet`() {
        // 2:30 h Gesamtzeit, davon 30 min Pause — die Notification zeigte
        // waehrend der Fahrt 2:00 h, die gespeicherte Tour bisher 2:30 h.
        val korrigiert = ohnePausenzeit(stats, pausedMs = 30 * 60 * 1_000L)

        assertEquals(7_200, korrigiert.durationS)
        // Die Fahrzeit ist bereits pausenfrei und bleibt unangetastet, ebenso
        // alles andere.
        assertEquals(7_000, korrigiert.movingTimeS)
        assertEquals(42.0, korrigiert.distanceKm)
        assertEquals(21.0, korrigiert.avgSpeedKmh)
    }

    @Test
    fun `ohne Pause bleiben die Kennzahlen unveraendert`() {
        assertSame(stats, ohnePausenzeit(stats, pausedMs = 0L))
    }

    @Test
    fun `eine unbekannte Dauer laesst sich nicht korrigieren`() {
        val ohneDauer = stats.copy(durationS = null)

        assertNull(ohnePausenzeit(ohneDauer, pausedMs = 60_000L).durationS)
    }

    @Test
    fun `eine laengere Pause als Dauer ergibt keine negative Dauer`() {
        assertEquals(0, ohnePausenzeit(stats, pausedMs = 99_000_000L).durationS)
    }
}
