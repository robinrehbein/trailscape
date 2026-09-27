package de.trailscape.app.ui.map

import de.trailscape.core.AscentPreference
import de.trailscape.core.FIRST_ROUND_LABEL
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wann das Vorschlagsblatt auf „Wind berücksichtigen" hinweist ([shouldOfferWindTip]). */
class WindTipTest {

    private fun target(label: String, source: RouteTargetSource) = RouteTarget(
        distanceKm = 30.0,
        ascentPreference = AscentPreference.FLACH,
        durationH = 1.5,
        speedKmh = 20.0,
        intensity = SessionIntensity.GRUNDLAGE,
        label = label,
        source = source,
    )

    @Test
    fun `Tagesempfehlung ohne Wind bekommt den Tipp`() {
        assertTrue(
            shouldOfferWindTip(
                target("Grundlageneinheit", RouteTargetSource.TAGESEMPFEHLUNG),
                windUsed = false,
                windEnabled = false,
            ),
        )
    }

    @Test
    fun `die erste Runde bleibt ohne Tipp`() {
        assertFalse(
            shouldOfferWindTip(
                target(FIRST_ROUND_LABEL, RouteTargetSource.TAGESEMPFEHLUNG),
                windUsed = false,
                windEnabled = false,
            ),
        )
    }

    @Test
    fun `kein Tipp bei eigener Eingabe, eingeschaltetem Schalter oder genutztem Wind`() {
        assertFalse(
            shouldOfferWindTip(
                target("Runde ab hier", RouteTargetSource.SELBST_GEWAEHLT),
                windUsed = false,
                windEnabled = false,
            ),
        )
        val plan = target("GA1-Einheit", RouteTargetSource.PLAN)
        assertFalse(shouldOfferWindTip(plan, windUsed = false, windEnabled = true))
        assertFalse(shouldOfferWindTip(plan, windUsed = true, windEnabled = false))
    }
}
