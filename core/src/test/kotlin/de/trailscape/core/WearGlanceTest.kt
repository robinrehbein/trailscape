package de.trailscape.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearGlanceTest {

    // 2026-11-16 ist ein Montag.
    private val montag = LocalDate.of(2026, 11, 16)
    private val mittwoch = montag.plusDays(2)
    private val sonntag = montag.plusDays(6)
    private val naechsterMontag = montag.plusWeeks(1)

    private fun stand(
        serie: Int = 5,
        erledigt: Boolean = true,
        km: Double = 42.0,
        ziel: Int? = 60,
        heute: LocalDate = mittwoch,
    ) = WochenGlance.von(serie, erledigt, km, ziel, heute, standMs = 1_000L)

    // --- Kodierung ---------------------------------------------------------

    @Test
    fun `Hin und zurueck ergibt denselben Stand`() {
        val s = stand()
        assertEquals(s, dekodiereWochenGlance(kodiereWochenGlance(s)))
    }

    @Test
    fun `Ohne Ziel bleibt das Feld weg und kommt als null zurueck`() {
        val s = stand(ziel = null)
        val text = kodiereWochenGlance(s).toString(Charsets.UTF_8)
        assertFalse(text.contains("ziel"))
        assertNull(dekodiereWochenGlance(kodiereWochenGlance(s))!!.zielKm)
    }

    @Test
    fun `von rundet Kilometer, klemmt Grenzen und verwirft ungueltige Ziele`() {
        val s = WochenGlance.von(9999, true, 12.3456, 0, mittwoch, 0)
        assertEquals(WochenGlance.MAX_SERIE_WOCHEN, s.serieWochen)
        assertEquals(12.3, s.wochenKm)
        assertNull(s.zielKm)
        assertEquals(0.0, WochenGlance.von(1, true, Double.NaN, 10, mittwoch, 0).wochenKm)
        assertEquals(0, WochenGlance.von(-3, false, 0.0, null, mittwoch, 0).serieWochen)
        assertEquals(montag.toEpochDay(), s.montagEpochTag)
    }

    @Test
    fun `Montag, Sonntag und Folgemontag gehoeren zur richtigen Woche`() {
        assertEquals(montag.toEpochDay(), stand(heute = montag).montagEpochTag)
        assertEquals(montag.toEpochDay(), stand(heute = sonntag).montagEpochTag)
        assertEquals(naechsterMontag.toEpochDay(), stand(heute = naechsterMontag).montagEpochTag)
    }

    @Test
    fun `Kaputte und fremde Daten ergeben null`() {
        fun dec(text: String) = dekodiereWochenGlance(text.toByteArray())
        assertNull(dec(""))
        assertNull(dec("kein json"))
        assertNull(dec("[]"))
        assertNull(dec("{}"))
        assertNull(dekodiereWochenGlance(byteArrayOf(0xFF.toByte(), 0x00, 0x7B)))
        val ok = kodiereWochenGlance(stand()).toString(Charsets.UTF_8)
        assertNotNull(dec(ok))
        // Neuere Version: unbekannt.
        assertNull(dec(ok.replace("\"v\":1", "\"v\":2")))
        // Fehlende Pflichtfelder.
        assertNull(dec(ok.replace("\"serie\"", "\"x\"")))
        assertNull(dec(ok.replace("\"km\"", "\"x\"")))
        assertNull(dec(ok.replace("\"erledigt\"", "\"x\"")))
        // Falsche Typen.
        assertNull(dec(ok.replace("\"serie\":5", "\"serie\":\"5\"")))
        assertNull(dec(ok.replace("\"erledigt\":true", "\"erledigt\":\"true\"")))
        assertNull(dec(ok.replace("\"serie\":5", "\"serie\":5.5")))
    }

    @Test
    fun `Werte ausserhalb der Grenzen ergeben null`() {
        val ok = kodiereWochenGlance(stand()).toString(Charsets.UTF_8)
        fun dec(text: String) = dekodiereWochenGlance(text.toByteArray())
        assertNull(dec(ok.replace("\"serie\":5", "\"serie\":-1")))
        assertNull(dec(ok.replace("\"serie\":5", "\"serie\":5201")))
        assertNull(dec(ok.replace("\"km\":42.0", "\"km\":-0.1")))
        assertNull(dec(ok.replace("\"km\":42.0", "\"km\":10001")))
        assertNull(dec(ok.replace("\"ziel\":60", "\"ziel\":0")))
        assertNull(dec(ok.replace("\"ziel\":60", "\"ziel\":null")))
        assertNull(dec(ok.replace("\"stand\":1000", "\"stand\":-5")))
        // Ein Dienstag als „Montag" ist kein Wochenstart.
        assertNull(dec(ok.replace("\"montag\":${montag.toEpochDay()}", "\"montag\":${montag.toEpochDay() + 1}")))
    }

    @Test
    fun `Gleicher Inhalt ignoriert nur den Zeitstempel`() {
        val a = stand()
        assertTrue(a.gleicherInhalt(a.copy(standMs = 99L)))
        assertFalse(a.gleicherInhalt(a.copy(wochenKm = 43.0)))
        assertFalse(a.gleicherInhalt(a.copy(wocheErledigt = false)))
        assertFalse(a.gleicherInhalt(null))
    }

    // --- Veraltete Daten ---------------------------------------------------

    @Test
    fun `Gleiche Woche bleibt unveraendert, bis zum Sonntag`() {
        val s = stand(serie = 5, erledigt = true, km = 42.0, ziel = 60)
        for (tag in listOf(montag, mittwoch, sonntag)) {
            val a = glanceFuerHeute(s, tag)!!
            assertTrue(a.wocheErledigt)
            assertFalse(a.veraltet)
            assertEquals(5, a.serieWochen)
            assertEquals(42.0, a.wochenKm)
            assertEquals(0.7f, a.anteil, 0.001f)
        }
    }

    @Test
    fun `Gleiche Woche ohne Fahrt zeigt die Serie in Gefahr`() {
        val a = glanceFuerHeute(stand(serie = 3, erledigt = false, km = 0.0), sonntag)!!
        assertTrue(a.serieInGefahr)
        assertFalse(a.wocheErledigt)
    }

    @Test
    fun `Am Montag nach einer erledigten Woche lebt die Serie, ist aber nicht erledigt`() {
        val a = glanceFuerHeute(stand(serie = 5, erledigt = true), naechsterMontag)!!
        assertEquals(5, a.serieWochen)
        assertFalse(a.wocheErledigt)
        assertTrue(a.serieInGefahr)
        assertEquals(0.0, a.wochenKm)
        assertEquals(60, a.zielKm)
        assertTrue(a.veraltet)
        assertFalse(a.zielErreicht)
    }

    @Test
    fun `Am Montag nach einer Woche ohne Fahrt ist die Serie gerissen`() {
        val a = glanceFuerHeute(stand(serie = 5, erledigt = false), naechsterMontag)!!
        assertEquals(0, a.serieWochen)
        assertFalse(a.serieInGefahr)
        assertFalse(a.wocheErledigt)
    }

    @Test
    fun `Zwei Wochen alt reisst die Serie auch bei erledigtem Stand`() {
        val a = glanceFuerHeute(stand(serie = 9, erledigt = true), montag.plusWeeks(2))!!
        assertEquals(0, a.serieWochen)
        assertFalse(a.wocheErledigt)
        assertTrue(a.veraltet)
    }

    @Test
    fun `Eine Serie von null bleibt auch eine Woche spaeter null`() {
        val a = glanceFuerHeute(stand(serie = 0, erledigt = false), naechsterMontag)!!
        assertEquals(0, a.serieWochen)
        assertFalse(a.serieInGefahr)
    }

    @Test
    fun `Stand aus der Zukunft bleibt unveraendert`() {
        val s = stand(heute = naechsterMontag)
        val a = glanceFuerHeute(s, mittwoch)!!
        assertFalse(a.veraltet)
        assertEquals(s.wochenKm, a.wochenKm)
    }

    @Test
    fun `Kein Stand ergibt keine Anzeige`() {
        assertNull(glanceFuerHeute(null, mittwoch))
    }

    @Test
    fun `Anteil ohne Ziel ist null, Ziel erreicht ab Gleichstand`() {
        assertEquals(0f, glanceFuerHeute(stand(ziel = null), mittwoch)!!.anteil)
        val erreicht = glanceFuerHeute(stand(km = 60.0, ziel = 60), mittwoch)!!
        assertTrue(erreicht.zielErreicht)
        assertEquals(1f, erreicht.anteil)
        assertEquals(1f, glanceFuerHeute(stand(km = 90.0, ziel = 60), mittwoch)!!.anteil)
    }

    @Test
    fun `Jahreswechsel liegt mitten in einer Woche`() {
        // Do 2026-12-31 und Fr 2027-01-01 gehoeren zur Woche ab Mo 2026-12-28.
        val s = WochenGlance.von(4, true, 10.0, 50, LocalDate.of(2026, 12, 31), 0)
        assertFalse(glanceFuerHeute(s, LocalDate.of(2027, 1, 1))!!.veraltet)
        assertTrue(glanceFuerHeute(s, LocalDate.of(2027, 1, 4))!!.veraltet)
    }
}
