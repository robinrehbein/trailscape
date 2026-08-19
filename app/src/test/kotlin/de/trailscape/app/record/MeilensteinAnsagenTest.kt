package de.trailscape.app.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests der Kilometer-Meilenstein-Logik ([MeilensteinAnsagen]) und der
 * Textform ([meilensteinText]). Reine JVM-Tests, wie alles in diesem Paket —
 * die Sprachausgabe selbst (`voice/VoiceAnnouncer.kt`) bleibt ungetestet,
 * hier geht es um das Wann und das Was der Ansage.
 */
class MeilensteinAnsagenTest {

    private fun minuten(m: Int): Long = m * 60_000L

    // ------------------------------------------------------------ Zeitpunkt

    @Test
    fun `unterhalb des ersten Meilensteins kommt nichts`() {
        val ansagen = MeilensteinAnsagen()

        assertNull(ansagen.pruefe(0.0, 0L))
        assertNull(ansagen.pruefe(4.9, minuten(12)))
    }

    @Test
    fun `bei 5 km faellt die erste Ansage`() {
        val ansagen = MeilensteinAnsagen()

        assertEquals("5 Kilometer, 12 Minuten.", ansagen.pruefe(5.0, minuten(12)))
    }

    @Test
    fun `jeder Meilenstein wird nur einmal angesagt`() {
        val ansagen = MeilensteinAnsagen()

        assertEquals("5 Kilometer, 12 Minuten.", ansagen.pruefe(5.1, minuten(12)))
        assertNull(ansagen.pruefe(5.2, minuten(13)))
        assertNull(ansagen.pruefe(9.9, minuten(24)))
        assertEquals("10 Kilometer, 25 Minuten.", ansagen.pruefe(10.0, minuten(25)))
    }

    @Test
    fun `mehrere uebersprungene Schwellen ergeben nur die juengste Ansage`() {
        val ansagen = MeilensteinAnsagen()

        // GPS-Luecke: von 4,9 direkt auf 15,2 km.
        assertEquals("15 Kilometer, 42 Minuten.", ansagen.pruefe(15.2, minuten(42)))
        assertNull(ansagen.pruefe(15.3, minuten(43)))
        assertEquals("20 Kilometer, 55 Minuten.", ansagen.pruefe(20.0, minuten(55)))
    }

    @Test
    fun `setzeAufDistanz verhindert nachgeholte Ansagen nach einem Neustart`() {
        val ansagen = MeilensteinAnsagen()

        ansagen.setzeAufDistanz(23.4)
        assertNull(ansagen.pruefe(23.5, minuten(70)))
        assertNull(ansagen.pruefe(24.9, minuten(74)))
        assertEquals("25 Kilometer, 1 Stunde 15 Minuten.", ansagen.pruefe(25.0, minuten(75)))
    }

    @Test
    fun `reset beginnt wieder bei 5 km`() {
        val ansagen = MeilensteinAnsagen()

        ansagen.pruefe(5.0, minuten(12))
        ansagen.reset()
        assertEquals("5 Kilometer, 14 Minuten.", ansagen.pruefe(5.0, minuten(14)))
    }

    // ----------------------------------------------------------------- Text

    @Test
    fun `Minuten unter einer Stunde`() {
        assertEquals("15 Kilometer, 42 Minuten.", meilensteinText(15, minuten(42)))
        assertEquals("5 Kilometer, 0 Minuten.", meilensteinText(5, 0L))
    }

    @Test
    fun `Singular bei einer Minute und einer Stunde`() {
        assertEquals("5 Kilometer, 1 Minute.", meilensteinText(5, minuten(1)))
        assertEquals("30 Kilometer, 1 Stunde.", meilensteinText(30, minuten(60)))
        assertEquals("35 Kilometer, 1 Stunde 1 Minute.", meilensteinText(35, minuten(61)))
    }

    @Test
    fun `volle Stunden ohne Minutenanhang`() {
        assertEquals("60 Kilometer, 2 Stunden.", meilensteinText(60, minuten(120)))
        assertEquals("65 Kilometer, 2 Stunden 5 Minuten.", meilensteinText(65, minuten(125)))
    }

    @Test
    fun `Sekunden werden abgeschnitten nicht gerundet`() {
        assertEquals("5 Kilometer, 12 Minuten.", meilensteinText(5, minuten(12) + 59_000L))
    }
}
