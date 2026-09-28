package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import kotlin.test.Test
import kotlin.test.assertEquals

/** Klartext-Titel und -Hinweise der Planeinheiten ([plainSessionTitle], [plainSessionHint]). */
class PlanPlainTextTest {

    private fun s(title: String, km: Int, intensity: SessionIntensity, isEvent: Boolean = false) =
        TrainingSession("Di", title, "", km, intensity = intensity, isEvent = isEvent)

    private val hilly = Goal("Rennen", 60.0, 700.0, 0L)

    @Test
    fun `Titel ohne Fachbegriffe`() {
        assertEquals("Locker 45 km", plainSessionTitle(s("GA1", 45, SessionIntensity.GRUNDLAGE), texts = CoreTextsDe))
        assertEquals("Locker 20 km", plainSessionTitle(s("GA1 kompensatorisch", 20, SessionIntensity.LOCKER), texts = CoreTextsDe))
        assertEquals("Lange Fahrt 80 km", plainSessionTitle(s("Lange Tour", 80, SessionIntensity.GRUNDLAGE), texts = CoreTextsDe))
        assertEquals("Hart 30 km", plainSessionTitle(s("Intervalle", 30, SessionIntensity.HART), texts = CoreTextsDe))
        assertEquals(
            "Rennen 60 km",
            plainSessionTitle(s("Zielevent: Rennen", 60, SessionIntensity.HART, isEvent = true), texts = CoreTextsDe),
        )
    }

    @Test
    fun `Hinweise je Art`() {
        assertEquals(
            "gleichmäßig, mit Höhenmetern wie im Rennen",
            plainSessionHint(s("Lange Tour", 80, SessionIntensity.GRUNDLAGE), hilly, texts = CoreTextsDe),
        )
        assertEquals(
            "gleichmäßig, lange im Sattel",
            plainSessionHint(s("Lange Tour", 80, SessionIntensity.GRUNDLAGE), hilly.copy(ascentM = null), texts = CoreTextsDe),
        )
        assertEquals(
            "ruhig, du kannst dich dabei unterhalten",
            plainSessionHint(s("GA1", 45, SessionIntensity.GRUNDLAGE), texts = CoreTextsDe),
        )
        assertEquals("ganz ruhig, für frische Beine", plainSessionHint(s("GA1 kompensatorisch", 20, SessionIntensity.LOCKER), texts = CoreTextsDe))
    }
}
