package de.trailscape.app.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests der Kilometer-Meilenstein-Logik ([MeilensteinAnsagen]) — hier geht
 * es um das Wann. Das Was (der Satz in Deutsch und Englisch) testet `:core`
 * in `SpeechTextsTest`; die Sprachausgabe selbst (`voice/VoiceAnnouncer.kt`)
 * bleibt ungetestet.
 */
class MeilensteinAnsagenTest {

    @Test
    fun `unterhalb des ersten Meilensteins kommt nichts`() {
        val ansagen = MeilensteinAnsagen()

        assertNull(ansagen.pruefe(0.0))
        assertNull(ansagen.pruefe(4.9))
    }

    @Test
    fun `bei 5 km faellt die erste Ansage`() {
        val ansagen = MeilensteinAnsagen()

        assertEquals(5, ansagen.pruefe(5.0))
    }

    @Test
    fun `jeder Meilenstein wird nur einmal angesagt`() {
        val ansagen = MeilensteinAnsagen()

        assertEquals(5, ansagen.pruefe(5.1))
        assertNull(ansagen.pruefe(5.2))
        assertNull(ansagen.pruefe(9.9))
        assertEquals(10, ansagen.pruefe(10.0))
    }

    @Test
    fun `mehrere uebersprungene Schwellen ergeben nur die juengste Ansage`() {
        val ansagen = MeilensteinAnsagen()

        // GPS-Luecke: von 4,9 direkt auf 15,2 km.
        assertEquals(15, ansagen.pruefe(15.2))
        assertNull(ansagen.pruefe(15.3))
        assertEquals(20, ansagen.pruefe(20.0))
    }

    @Test
    fun `setzeAufDistanz verhindert nachgeholte Ansagen nach einem Neustart`() {
        val ansagen = MeilensteinAnsagen()

        ansagen.setzeAufDistanz(23.4)
        assertNull(ansagen.pruefe(23.5))
        assertNull(ansagen.pruefe(24.9))
        assertEquals(25, ansagen.pruefe(25.0))
    }

    @Test
    fun `reset beginnt wieder bei 5 km`() {
        val ansagen = MeilensteinAnsagen()

        ansagen.pruefe(5.0)
        ansagen.reset()
        assertEquals(5, ansagen.pruefe(5.0))
    }
}
