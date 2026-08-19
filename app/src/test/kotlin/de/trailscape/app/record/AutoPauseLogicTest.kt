package de.trailscape.app.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der Auto-Pause-Zustandsmaschine ([AutoPauseLogic]) — Eintritt nach
 * anhaltendem Stillstand, Hysterese gegen GPS-Jitter, ersatzweise
 * Tempoableitung aus rohen Proben und die Wiederherstellung nach einem
 * Dienst-Neustart. Reine JVM-Tests, wie alles in diesem Paket.
 */
class AutoPauseLogicTest {

    private val t0 = 1_723_118_400_000L

    /** Probe mit vom Geraet gemessenem Tempo an einer festen Position. */
    private fun AutoPauseLogic.gemessen(zeitMs: Long, kmh: Double) =
        probe(zeitMs = zeitMs, lat = 52.0, lon = 13.0, gemesseneKmh = kmh)

    // ------------------------------------------------------------- Eintritt

    @Test
    fun `eine einzelne langsame Probe pausiert noch nicht`() {
        val logic = AutoPauseLogic()

        assertNull(logic.gemessen(t0, 0.5))
        assertFalse(logic.autoPausiert)
    }

    @Test
    fun `Stillstand ueber 5 s pausiert`() {
        val logic = AutoPauseLogic()

        assertNull(logic.gemessen(t0, 0.5))
        assertEquals(AutoPauseLogic.Uebergang.PAUSIEREN, logic.gemessen(t0 + 5_000L, 0.5))
        assertTrue(logic.autoPausiert)
    }

    @Test
    fun `unter 5 s Stillstand pausiert nicht`() {
        val logic = AutoPauseLogic()

        assertNull(logic.gemessen(t0, 1.5))
        assertNull(logic.gemessen(t0 + 4_999L, 1.5))
        assertFalse(logic.autoPausiert)
    }

    @Test
    fun `eine schnelle Probe setzt die Langsamphase zurueck`() {
        val logic = AutoPauseLogic()

        assertNull(logic.gemessen(t0, 0.5))
        // Kurz angefahren (ueber der Eintrittsschwelle) — die Phase beginnt neu.
        assertNull(logic.gemessen(t0 + 3_000L, 6.0))
        assertNull(logic.gemessen(t0 + 6_000L, 0.5))
        // 5 s seit der letzten Rueckstellung sind noch nicht um.
        assertNull(logic.gemessen(t0 + 10_000L, 0.5))
        assertFalse(logic.autoPausiert)

        assertEquals(AutoPauseLogic.Uebergang.PAUSIEREN, logic.gemessen(t0 + 11_000L, 0.5))
    }

    @Test
    fun `genau an der Eintrittsschwelle wird nicht pausiert`() {
        val logic = AutoPauseLogic()

        assertNull(logic.gemessen(t0, AutoPauseLogic.EINTRITT_KMH))
        assertNull(logic.gemessen(t0 + 10_000L, AutoPauseLogic.EINTRITT_KMH))
        assertFalse(logic.autoPausiert)
    }

    @Test
    fun `ein ruecklaeufiger Zeitstempel beginnt die Langsamphase neu`() {
        val logic = AutoPauseLogic()

        assertNull(logic.gemessen(t0, 0.5))
        // Uhrkorrektur nach hinten: Daraus darf keine negative bzw.
        // kuenstlich lange Dauer werden.
        assertNull(logic.gemessen(t0 - 60_000L, 0.5))
        assertFalse(logic.autoPausiert)
        assertEquals(AutoPauseLogic.Uebergang.PAUSIEREN, logic.gemessen(t0 - 55_000L, 0.5))
    }

    // ------------------------------------------------- Rueckkehr / Hysterese

    @Test
    fun `Weiterfahrt ab 3,5 km-h setzt in einer einzigen Probe fort`() {
        val logic = pausiert()

        assertEquals(
            AutoPauseLogic.Uebergang.FORTSETZEN,
            logic.gemessen(t0 + 60_000L, AutoPauseLogic.FORTSETZUNG_KMH),
        )
        assertFalse(logic.autoPausiert)
    }

    @Test
    fun `zwischen den Schwellen bleibt die Auto-Pause bestehen (Hysterese)`() {
        val logic = pausiert()

        // GPS-Jitter im Stand liefert gern 2-3 km/h — das darf die Pause
        // nicht beenden, obwohl es ueber der Eintrittsschwelle liegt.
        assertNull(logic.gemessen(t0 + 60_000L, 2.5))
        assertNull(logic.gemessen(t0 + 65_000L, 3.4))
        assertTrue(logic.autoPausiert)
    }

    @Test
    fun `nach der Fortsetzung beginnt der Eintritt wieder von vorn`() {
        val logic = pausiert()
        logic.gemessen(t0 + 60_000L, 10.0)

        assertNull(logic.gemessen(t0 + 65_000L, 0.5))
        assertFalse(logic.autoPausiert)
        assertEquals(AutoPauseLogic.Uebergang.PAUSIEREN, logic.gemessen(t0 + 70_000L, 0.5))
    }

    // --------------------------------------------- Ableitung ohne Geraetetempo

    @Test
    fun `ohne Geraetetempo entscheidet die Positionsdifferenz`() {
        val logic = AutoPauseLogic()

        // Zwei Proben an derselben Position, 5 s auseinander: 0 km/h.
        assertNull(logic.probe(t0, 52.0, 13.0, gemesseneKmh = null))
        assertNull(logic.probe(t0 + 5_000L, 52.0, 13.0, gemesseneKmh = null))
        assertEquals(
            AutoPauseLogic.Uebergang.PAUSIEREN,
            logic.probe(t0 + 10_000L, 52.0, 13.0, gemesseneKmh = null),
        )
        assertTrue(logic.autoPausiert)

        // Rund 11 m in 5 s (0,0001 Breitengrad) sind etwa 8 km/h — Weiterfahrt.
        assertEquals(
            AutoPauseLogic.Uebergang.FORTSETZEN,
            logic.probe(t0 + 15_000L, 52.0001, 13.0, gemesseneKmh = null),
        )
    }

    @Test
    fun `die allererste Probe ohne Geraetetempo ist kein Beleg`() {
        val logic = AutoPauseLogic()

        // Keine Vorgaengerprobe, kein gemessenes Tempo: nichts ableitbar.
        assertNull(logic.probe(t0, 52.0, 13.0, gemesseneKmh = null))
        assertFalse(logic.autoPausiert)
    }

    @Test
    fun `eine zu grosse Zeitluecke macht die Ableitung wertlos`() {
        val logic = pausiert()

        // 11 m nach 60 s sind zwar rechnerisch langsam, aber die Luecke liegt
        // ueber der Obergrenze — die Probe aendert nichts, erst die naechste
        // (jetzt mit frischer Vorgaengerprobe) zaehlt wieder.
        assertNull(logic.probe(t0 + 120_000L, 52.0001, 13.0, gemesseneKmh = null))
        assertTrue(logic.autoPausiert)
        assertEquals(
            AutoPauseLogic.Uebergang.FORTSETZEN,
            logic.probe(t0 + 125_000L, 52.0002, 13.0, gemesseneKmh = null),
        )
    }

    // ------------------------------------------------------ Wiederherstellung

    @Test
    fun `nach der Wiederherstellung endet die Auto-Pause bei Weiterfahrt`() {
        // Dienst-Neustart mit offener Auto-Pause im Journal (siehe
        // RecordingService.continueFromJournal): Die Maschine startet im
        // Zustand „autopausiert" und setzt bei Fahrt von selbst fort.
        val logic = AutoPauseLogic()
        logic.stelleAutoPauseWiederHer()
        assertTrue(logic.autoPausiert)

        assertNull(logic.gemessen(t0, 1.0))
        assertEquals(AutoPauseLogic.Uebergang.FORTSETZEN, logic.gemessen(t0 + 5_000L, 12.0))
        assertFalse(logic.autoPausiert)
    }

    @Test
    fun `reset stellt den Fahrzustand her und vergisst alle Proben`() {
        val logic = pausiert()

        logic.reset()

        assertFalse(logic.autoPausiert)
        // Nach dem Reset zaehlt die Langsamphase von vorn.
        assertNull(logic.gemessen(t0 + 60_000L, 0.5))
        assertEquals(AutoPauseLogic.Uebergang.PAUSIEREN, logic.gemessen(t0 + 65_000L, 0.5))
    }

    /** Eine Maschine, die bei [t0] + 5 s in die Auto-Pause gegangen ist. */
    private fun pausiert(): AutoPauseLogic {
        val logic = AutoPauseLogic()
        logic.gemessen(t0, 0.5)
        assertEquals(AutoPauseLogic.Uebergang.PAUSIEREN, logic.gemessen(t0 + 5_000L, 0.5))
        return logic
    }
}
