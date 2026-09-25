package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Klartext-Titel und -Hinweise der Planeinheiten ([plainSessionTitle], [plainSessionHint]). */
class PlanPlainTextTest {

    private fun s(title: String, km: Int, intensity: SessionIntensity, isEvent: Boolean = false) =
        TrainingSession("Di", title, "", km, intensity = intensity, isEvent = isEvent)

    private val hilly = Goal("Rennen", 60.0, 700.0, 0L)

    @Test
    fun `Titel ohne Fachbegriffe`() {
        assertEquals("Locker 45 km", plainSessionTitle(s("GA1", 45, SessionIntensity.GRUNDLAGE)))
        assertEquals("Locker 20 km", plainSessionTitle(s("GA1 kompensatorisch", 20, SessionIntensity.LOCKER)))
        assertEquals("Lange Fahrt 80 km", plainSessionTitle(s("Lange Tour", 80, SessionIntensity.GRUNDLAGE)))
        assertEquals("Hart 30 km", plainSessionTitle(s("Intervalle", 30, SessionIntensity.HART)))
        assertEquals(
            "Rennen 60 km",
            plainSessionTitle(s("Zielevent: Rennen", 60, SessionIntensity.HART, isEvent = true)),
        )
    }

    @Test
    fun `Hinweise je Art`() {
        assertEquals(
            "gleichmäßig, mit Höhenmetern wie im Rennen",
            plainSessionHint(s("Lange Tour", 80, SessionIntensity.GRUNDLAGE), hilly),
        )
        assertEquals(
            "gleichmäßig, lange im Sattel",
            plainSessionHint(s("Lange Tour", 80, SessionIntensity.GRUNDLAGE), hilly.copy(ascentM = null)),
        )
        assertEquals(
            "ruhig, du kannst dich dabei unterhalten",
            plainSessionHint(s("GA1", 45, SessionIntensity.GRUNDLAGE)),
        )
        assertEquals("ganz ruhig, für frische Beine", plainSessionHint(s("GA1 kompensatorisch", 20, SessionIntensity.LOCKER)))
    }
}
