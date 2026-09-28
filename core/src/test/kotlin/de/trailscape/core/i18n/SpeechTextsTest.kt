package de.trailscape.core.i18n

import de.trailscape.core.TurnAnnouncer
import de.trailscape.core.TurnHint
import de.trailscape.core.TurnRichtung
import de.trailscape.core.ansageAbstandM
import de.trailscape.core.turnAnsageText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeechTextsTest {

    private fun minuten(m: Int): Long = m * 60_000L

    @Test
    fun `Abbiegeansagen auf Deutsch`() {
        val s = CoreTextsDe.speech
        assertEquals("In 100 Metern links.", s.turn(TurnRichtung.LINKS, 110.0))
        assertEquals("In 50 Metern rechts.", s.turn(TurnRichtung.RECHTS, 40.0))
        assertEquals("Gleich scharf links.", s.turn(TurnRichtung.KEHRE_LINKS, 39.9))
        assertEquals("In 250 Metern scharf rechts.", s.turn(TurnRichtung.KEHRE_RECHTS, 240.0))
    }

    @Test
    fun `Abbiegeansagen auf Englisch`() {
        val s = CoreTextsEn.speech
        assertEquals("In 100 metres, turn left.", s.turn(TurnRichtung.LINKS, 110.0))
        assertEquals("In 50 metres, turn right.", s.turn(TurnRichtung.RECHTS, 40.0))
        assertEquals("Now turn sharp left.", s.turn(TurnRichtung.KEHRE_LINKS, 10.0))
        assertEquals("Now turn sharp right.", s.turn(TurnRichtung.KEHRE_RECHTS, 0.0))
    }

    @Test
    fun `Rundung auf 50 m gilt fuer beide Sprachen`() {
        assertNull(ansageAbstandM(39.0))
        assertEquals(50, ansageAbstandM(40.0))
        assertEquals(150, ansageAbstandM(137.0))
        assertEquals(
            "In 150 metres, turn left.",
            turnAnsageText(TurnRichtung.LINKS, 137.0, CoreTextsEn),
        )
    }

    @Test
    fun `der Announcer liefert Richtung und Restweg statt eines Satzes`() {
        val hint = TurnHint(0, 0.0, 0.0, TurnRichtung.RECHTS, 60.0, 500.0)
        val ansage = TurnAnnouncer(listOf(hint)).melde(440.0, 30.0)!!
        assertEquals(TurnRichtung.RECHTS, ansage.richtung)
        assertEquals(60.0, ansage.abstandM, 1e-9)
        assertEquals("In 50 Metern rechts.", CoreTextsDe.speech.turn(ansage.richtung, ansage.abstandM))
        assertEquals("In 50 metres, turn right.", CoreTextsEn.speech.turn(ansage.richtung, ansage.abstandM))
    }

    @Test
    fun `Meilensteine auf Deutsch`() {
        val s = CoreTextsDe.speech
        assertEquals("15 Kilometer, 42 Minuten.", s.milestone(15, minuten(42)))
        assertEquals("5 Kilometer, 0 Minuten.", s.milestone(5, 0L))
        assertEquals("5 Kilometer, 1 Minute.", s.milestone(5, minuten(1)))
        assertEquals("30 Kilometer, 1 Stunde.", s.milestone(30, minuten(60)))
        assertEquals("35 Kilometer, 1 Stunde 1 Minute.", s.milestone(35, minuten(61)))
        assertEquals("65 Kilometer, 2 Stunden 5 Minuten.", s.milestone(65, minuten(125)))
        // Sekunden werden abgeschnitten, nicht gerundet.
        assertEquals("5 Kilometer, 12 Minuten.", s.milestone(5, minuten(12) + 59_000L))
    }

    @Test
    fun `Meilensteine auf Englisch`() {
        val s = CoreTextsEn.speech
        assertEquals("15 kilometres, 42 minutes.", s.milestone(15, minuten(42)))
        assertEquals("5 kilometres, 1 minute.", s.milestone(5, minuten(1)))
        assertEquals("25 kilometres, 1 hour 12 minutes.", s.milestone(25, minuten(72)))
        assertEquals("60 kilometres, 2 hours.", s.milestone(60, minuten(120)))
        assertEquals("1 kilometre, 3 minutes.", s.milestone(1, minuten(3)))
    }

    @Test
    fun `Navigations- und Aufzeichnungsansagen`() {
        assertEquals("Du bist abseits der Route.", CoreTextsDe.speech.offRoute())
        assertEquals("You’re off route.", CoreTextsEn.speech.offRoute())
        assertEquals("Back on route.", CoreTextsEn.speech.backOnRoute())
        assertEquals("You’ve arrived.", CoreTextsEn.speech.destinationReached())
        assertEquals("Aufzeichnung gestartet.", CoreTextsDe.speech.recordingStarted())
        assertEquals("Recording stopped.", CoreTextsEn.speech.recordingStopped())
        assertEquals("Recording paused.", CoreTextsEn.speech.recordingPaused())
        assertEquals("Aufzeichnung fortgesetzt.", CoreTextsDe.speech.recordingResumed())
    }
}
