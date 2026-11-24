package de.trailscape.core

import de.trailscape.core.i18n.AppLanguage
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveZielTest {

    private val tag = LocalDate.of(2026, 11, 18)

    private fun fact(id: String, km: Double) = RideFact(id, tag, km, 0.0, 3600, 20.0)

    private fun rest(zeile: ZielZeile?): Double = when (zeile) {
        is ZielZeile.WochenzielRest -> zeile.restKm
        is ZielZeile.BestweiteRest -> zeile.restKm
        else -> error("keine Restzeile: $zeile")
    }

    // --- Snapshot ---

    @Test
    fun `Snapshot rechnet Wochenrest und weiteste Tour`() {
        val s = zielSnapshot(listOf(fact("a", 40.0), fact("b", 61.0), fact("c", 20.0)), wochenZielKm = 80, wochenGefahrenKm = 48.0)
        assertEquals(ZielSnapshot(wochenRestKm = 32.0, laengsteKm = 61.0), s)
    }

    @Test
    fun `Snapshot ohne Wochenziel oder mit erreichtem Ziel hat keinen Wochenrest`() {
        val facts = listOf(fact("a", 30.0))
        assertEquals(ZielSnapshot(null, 30.0), zielSnapshot(facts, wochenZielKm = null, wochenGefahrenKm = 12.0))
        assertEquals(ZielSnapshot(null, 30.0), zielSnapshot(facts, wochenZielKm = 60, wochenGefahrenKm = 60.0))
        assertEquals(ZielSnapshot(null, 30.0), zielSnapshot(facts, wochenZielKm = 60, wochenGefahrenKm = 75.0))
        assertEquals(ZielSnapshot(null, 30.0), zielSnapshot(facts, wochenZielKm = 0, wochenGefahrenKm = 0.0))
    }

    @Test
    fun `Snapshot ohne Touren nur mit Wochenziel, ganz ohne alles null`() {
        assertEquals(ZielSnapshot(30.0, null), zielSnapshot(emptyList(), wochenZielKm = 30, wochenGefahrenKm = 0.0))
        assertNull(zielSnapshot(emptyList(), wochenZielKm = null, wochenGefahrenKm = 0.0))
    }

    @Test
    fun `kurze Touren sind keine Bestweite`() {
        assertNull(zielSnapshot(listOf(fact("a", 3.0)), wochenZielKm = null, wochenGefahrenKm = 3.0))
    }

    @Test
    fun `ungueltige gefahrene Kilometer zaehlen als null`() {
        assertEquals(ZielSnapshot(50.0, null), zielSnapshot(emptyList(), 50, Double.NaN))
        assertEquals(ZielSnapshot(50.0, null), zielSnapshot(emptyList(), 50, -4.0))
    }

    // --- Vorrang ---

    private val beides = ZielSnapshot(wochenRestKm = 20.0, laengsteKm = 60.0)

    @Test
    fun `offenes Wochenziel geht vor, auch wenn die Bestweite nah ist`() {
        assertEquals(ZielZeile.WochenzielRest(20.0), zielZeile(beides, 0.0))
        assertEquals(12.0, rest(zielZeile(beides, 8.0)), 1e-9)
        val nahDran = ZielSnapshot(wochenRestKm = 55.0, laengsteKm = 60.0)
        assertEquals(5.0, rest(zielZeile(nahDran, 50.0)), 1e-9)
        assertTrue(zielZeile(nahDran, 50.0) is ZielZeile.WochenzielRest)
    }

    @Test
    fun `genau am Wochenziel ist es geschafft`() {
        assertEquals(ZielZeile.WochenzielGeschafft, zielZeile(beides, 20.0))
    }

    @Test
    fun `der Haken bleibt im Fenster, danach uebernimmt eine nahe Bestweite`() {
        val s = ZielSnapshot(wochenRestKm = 41.5, laengsteKm = 60.0)
        // 42 km ≥ 70 % von 60, aber noch im Fenster nach dem Wochenziel.
        assertEquals(ZielZeile.WochenzielGeschafft, zielZeile(s, 42.0))
        assertEquals(ZielZeile.WochenzielGeschafft, zielZeile(s, 41.5 + GESCHAFFT_FENSTER_KM - 0.01))
        assertEquals(ZielZeile.BestweiteRest(60.0 - 42.5), zielZeile(s, 42.5))
    }

    @Test
    fun `ohne Bestweite in Reichweite bleibt der Haken stehen`() {
        assertEquals(ZielZeile.WochenzielGeschafft, zielZeile(beides, 25.0))
        assertEquals(ZielZeile.WochenzielGeschafft, zielZeile(ZielSnapshot(10.0, null), 80.0))
    }

    @Test
    fun `Bestweite erst ab 70 Prozent der weitesten Tour`() {
        val s = ZielSnapshot(wochenRestKm = null, laengsteKm = 60.0)
        assertNull(zielZeile(s, 0.0))
        assertNull(zielZeile(s, 41.9))
        assertEquals(ZielZeile.BestweiteRest(18.0), zielZeile(s, 42.0))
        assertEquals(ZielZeile.BestweiteRest(0.0), zielZeile(s, 60.0))
    }

    @Test
    fun `weiter als je zuvor ist die neue Bestweite und bleibt es`() {
        val s = ZielSnapshot(wochenRestKm = null, laengsteKm = 60.0)
        assertEquals(ZielZeile.NeueBestweite, zielZeile(s, 60.01))
        assertEquals(ZielZeile.NeueBestweite, zielZeile(s, 140.0))
    }

    @Test
    fun `nach dem Wochenziel kann die neue Bestweite folgen`() {
        assertEquals(ZielZeile.NeueBestweite, zielZeile(beides, 61.0))
    }

    @Test
    fun `ohne Snapshot oder ohne Werte keine Zeile`() {
        assertNull(zielZeile(null, 10.0))
        assertNull(zielZeile(ZielSnapshot(null, null), 10.0))
    }

    @Test
    fun `ungueltige Werte zaehlen nicht`() {
        assertNull(zielZeile(ZielSnapshot(Double.NaN, Double.POSITIVE_INFINITY), 10.0))
        assertNull(zielZeile(ZielSnapshot(-3.0, 4.0), 3.5))
        assertNull(zielZeile(ZielSnapshot(ZIEL_MAX_KM + 1, null), 1.0))
        // Kaputte Distanz zaehlt als Start der Tour.
        assertEquals(ZielZeile.WochenzielRest(20.0), zielZeile(beides, Double.NaN))
        assertEquals(ZielZeile.WochenzielRest(20.0), zielZeile(beides, -2.0))
    }

    @Test
    fun `Pause aendert nichts, solange die Distanz steht`() {
        // Die Zeile haengt nur an der Distanz; in der Pause bleibt sie also
        // stehen — zu jedem Zeitpunkt der Pause dieselbe.
        val vorPause = zielZeile(beides, 13.7)
        repeat(5) { assertEquals(vorPause, zielZeile(beides, 13.7)) }
        assertEquals(6.3, rest(vorPause), 1e-9)
    }

    @Test
    fun `die Zeile ist monoton - kein Zurueckspringen auf ein offenes Ziel`() {
        var vorher: ZielZeile? = null
        var km = 0.0
        val reihenfolge = mutableListOf<String>()
        while (km <= 80.0) {
            val z = zielZeile(beides, km)
            val name = z?.let { it::class.simpleName } ?: "keine"
            if (reihenfolge.lastOrNull() != name) reihenfolge += name
            vorher = z
            km += 0.1
        }
        assertEquals(listOf("WochenzielRest", "WochenzielGeschafft", "BestweiteRest", "NeueBestweite"), reihenfolge)
        assertEquals(ZielZeile.NeueBestweite, vorher)
    }

    // --- Rundung ---

    @Test
    fun `Rest wird aufgerundet, ab 1 km ganz, darunter in Zehnteln`() {
        assertEquals(ZielRest(12.0, 0), zielRestAnzeige(12.0))
        assertEquals(ZielRest(13.0, 0), zielRestAnzeige(12.01))
        assertEquals(ZielRest(1.0, 0), zielRestAnzeige(1.0))
        assertEquals(ZielRest(1.0, 0), zielRestAnzeige(0.95))
        assertEquals(ZielRest(0.5, 1), zielRestAnzeige(0.41))
        assertEquals(ZielRest(0.4, 1), zielRestAnzeige(0.4))
        assertEquals(ZielRest(0.1, 1), zielRestAnzeige(0.01))
    }

    @Test
    fun `nie Noch 0 km`() {
        assertEquals(ZielRest(0.1, 1), zielRestAnzeige(0.0))
        assertEquals(ZielRest(0.1, 1), zielRestAnzeige(-1.0))
        assertEquals(ZielRest(0.1, 1), zielRestAnzeige(Double.NaN))
    }

    @Test
    fun `Gleitkommareste springen nicht eine Stufe hoeher`() {
        // 60 - 48,0000000001 o. Ae.: 11,9999999999 bleibt 12, nicht 13.
        assertEquals(ZielRest(12.0, 0), zielRestAnzeige(60.0 - 48.0 + 1e-12))
        assertEquals(ZielRest(0.3, 1), zielRestAnzeige(0.1 + 0.2))
    }

    @Test
    fun `Zahl im Format der Sprache`() {
        assertEquals("12", zielZeileZahl(ZielZeile.WochenzielRest(11.2), AppLanguage.DE))
        assertEquals("0,4", zielZeileZahl(ZielZeile.BestweiteRest(0.35), AppLanguage.DE))
        assertEquals("0.4", zielZeileZahl(ZielZeile.BestweiteRest(0.35), AppLanguage.EN))
        assertNull(zielZeileZahl(ZielZeile.WochenzielGeschafft, AppLanguage.DE))
        assertNull(zielZeileZahl(ZielZeile.NeueBestweite, AppLanguage.EN))
    }
}
