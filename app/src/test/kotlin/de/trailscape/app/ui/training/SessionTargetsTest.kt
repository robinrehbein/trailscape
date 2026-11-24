package de.trailscape.app.ui.training

import de.trailscape.core.Confidence
import de.trailscape.core.PersonalTargetRange
import de.trailscape.core.SessionPersonalTargets
import de.trailscape.core.SessionTargetZones
import de.trailscape.core.TargetZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reine Formatierungslogik der Zielbereiche (ohne Compose). */
class SessionTargetsTest {

    private fun range(
        zone: TargetZone = TargetZone.THRESHOLD,
        hr: Pair<Int, Int> = 152 to 163,
        power: Pair<Int, Int> = 215 to 240,
        hrConf: Confidence = Confidence.HIGH,
        powerConf: Confidence = Confidence.HIGH,
    ) = PersonalTargetRange(zone, hr.first, hr.second, hrConf, power.first, power.second, powerConf)

    @Test
    fun rangeFormatsWithEnDash() {
        assertEquals("152–163", formatTargetRange(152, 163))
    }

    @Test
    fun equalBoundsCollapse() {
        assertEquals("150", formatTargetRange(150, 150))
    }

    @Test
    fun invalidRangesAreNull() {
        assertNull(formatTargetRange(0, 150))
        assertNull(formatTargetRange(150, 0))
        assertNull(formatTargetRange(-1, 5))
        assertNull(formatTargetRange(170, 150))
    }

    @Test
    fun estimatedOnlyBelowMedium() {
        assertTrue(isEstimated(Confidence.NONE))
        assertTrue(isEstimated(Confidence.LOW))
        assertFalse(isEstimated(Confidence.MEDIUM))
        assertFalse(isEstimated(Confidence.HIGH))
    }

    @Test
    fun lineWithPowerAndNoEstimate() {
        val line = targetLine(range())!!
        assertEquals("152–163", line.hrText)
        assertEquals("215–240", line.powerText)
        assertEquals(EstimateMark.NONE, line.estimate)
    }

    @Test
    fun powerDroppedWithoutPowerValues() {
        val line = targetLine(range(power = 0 to 0, powerConf = Confidence.NONE))!!
        assertNull(line.powerText)
        // Eine fehlende Leistung ist nicht „geschätzt".
        assertEquals(EstimateMark.NONE, line.estimate)
    }

    @Test
    fun estimateMarksFollowConfidence() {
        assertEquals(EstimateMark.HEART_RATE, targetLine(range(hrConf = Confidence.LOW))!!.estimate)
        assertEquals(EstimateMark.POWER, targetLine(range(powerConf = Confidence.LOW))!!.estimate)
        assertEquals(
            EstimateMark.BOTH,
            targetLine(range(hrConf = Confidence.LOW, powerConf = Confidence.NONE))!!.estimate,
        )
    }

    @Test
    fun lineNullWithoutHeartRate() {
        assertNull(targetLine(range(hr = 0 to 0)))
    }

    @Test
    fun intervalSessionListsWorkBeforeMain() {
        val main = range(zone = TargetZone.ENDURANCE, hr = 120 to 138, power = 0 to 0)
        val work = range(zone = TargetZone.THRESHOLD)
        val targets = SessionPersonalTargets(SessionTargetZones(TargetZone.ENDURANCE, TargetZone.THRESHOLD, 4, 8), main, work)
        val lines = sessionTargetLines(targets)
        assertEquals(listOf(TargetZone.THRESHOLD, TargetZone.ENDURANCE), lines.map { it.zone })
        assertEquals("120–138", lines[1].hrText)
        assertNull(lines[1].powerText)
    }

    @Test
    fun steadySessionHasOneLine() {
        val main = range(zone = TargetZone.ENDURANCE, hr = 120 to 138)
        val targets = SessionPersonalTargets(SessionTargetZones(TargetZone.ENDURANCE, null), main, null)
        assertEquals(1, sessionTargetLines(targets).size)
    }

    @Test
    fun noTargetsNoLines() {
        assertTrue(sessionTargetLines(null).isEmpty())
    }
}
