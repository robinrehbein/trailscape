package de.trailscape.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tests der Rundenguete (`RouteQuality.kt`). */
class RouteQualityTest {

    private val lat0 = 48.5
    private val lon0 = 9.0
    private val mPerDegLat = 6371000.0 * PI / 180
    private val mPerDegLon = mPerDegLat * cos(lat0 * PI / 180)

    /** Punkt [x] m oestlich und [y] m noerdlich des Ursprungs. */
    private fun p(x: Double, y: Double, ele: Double? = 400.0) =
        TrackPoint(lat = lat0 + y / mPerDegLat, lon = lon0 + x / mPerDegLon, ele = ele)

    private fun route(vararg points: TrackPoint): PlannedRoute {
        val list = points.toList()
        val km = list.zipWithNext { a, b -> haversineM(a, b) }.sum() / 1000
        return PlannedRoute(points = list, distanceKm = km, ascentM = 100.0, pavedKm = km / 2, unpavedKm = km / 2)
    }

    /** Quadratrunde mit 2 km Kantenlaenge, gegen den Uhrzeigersinn. */
    private val square = arrayOf(p(0.0, 0.0), p(2000.0, 0.0), p(2000.0, 2000.0), p(0.0, 2000.0), p(0.0, 0.0))

    // ------------------------------------------------------- retraceShare

    @Test
    fun cleanLoopIsNotRetraced() {
        assertTrue(retraceShare(square.toList()) < 0.01)
    }

    @Test
    fun outAndBackIsFullyRetraced() {
        val share = retraceShare(listOf(p(0.0, 0.0), p(3000.0, 0.0), p(0.0, 0.0)))
        // Nur die Lagen direkt am Wendepunkt haben keinen Gegenpart mit Abstand.
        assertTrue(share > 0.9, "Anteil $share")
    }

    @Test
    fun lollipopCountsBothPassesOfTheStick() {
        // 1 km Stiel hin, 4 km Schleife, 1 km Stiel zurueck → 2 von 6 km doppelt.
        val share = retraceShare(
            listOf(
                p(0.0, 0.0), p(1000.0, 0.0),
                p(2000.0, 0.0), p(2000.0, 1000.0), p(1000.0, 1000.0), p(1000.0, 0.0),
                p(0.0, 0.0),
            ),
        )
        assertEquals(2.0 / 6.0, share, 0.03)
    }

    @Test
    fun aCrossingIsNotARetrace() {
        // Achter: zwei Schleifen, die sich in der Mitte kreuzen.
        val share = retraceShare(
            listOf(
                p(0.0, 0.0), p(1000.0, 1000.0), p(2000.0, 2000.0), p(2000.0, 0.0),
                p(1000.0, 1000.0), p(0.0, 2000.0), p(0.0, 0.0),
            ),
        )
        assertTrue(share < 0.03, "Anteil $share")
    }

    @Test
    fun degenerateInputIsZero() {
        assertEquals(0.0, retraceShare(emptyList()))
        assertEquals(0.0, retraceShare(listOf(p(0.0, 0.0))))
        assertEquals(0.0, retraceShare(listOf(p(0.0, 0.0), p(0.0, 0.0))))
    }

    @Test
    fun retraceScoreIsClampedAndFinite() {
        assertEquals(0.0, retraceScore(0.0))
        assertEquals(10.0, retraceScore(0.25))
        assertEquals(retraceScoreWeight, retraceScore(3.0))
        assertEquals(0.0, retraceScore(Double.NaN))
    }

    // ---------------------------------------------------------- trimSpurs

    @Test
    fun cleanLoopStaysUntouched() {
        val original = route(*square)
        val trim = trimSpurs(original)
        assertSame(original, trim.route)
        assertEquals(0, trim.spurs)
    }

    @Test
    fun aSpurToAViaPointIsCutOut() {
        // Auf der Suedkante 200 m in einen Feldweg nach Sueden und zurueck.
        val original = route(
            p(0.0, 0.0), p(1000.0, 0.0), p(1000.0, -200.0), p(1000.0, 0.0),
            p(2000.0, 0.0), p(2000.0, 2000.0), p(0.0, 2000.0), p(0.0, 0.0),
        )
        val trim = trimSpurs(original)
        assertEquals(1, trim.spurs)
        assertEquals(0.4, trim.removedKm, 0.03)
        assertEquals(8.0, trim.route.distanceKm, 0.03)
        // Kein Punkt liegt mehr nennenswert suedlich der Kante.
        val southMost = trim.route.points.minOf { (it.lat - lat0) * mPerDegLat }
        assertTrue(southMost > -spurToleranceM - 1, "suedlichster Punkt $southMost m")
        // Belag und Hoehenmeter wurden anteilig nachgefuehrt.
        assertEquals(4.0, trim.route.pavedKm!!, 0.03)
        assertTrue(retraceShare(trim.route.points) < 0.01)
        // Start und Ziel bleiben.
        assertEquals(original.points.first(), trim.route.points.first())
        assertEquals(original.points.last(), trim.route.points.last())
    }

    @Test
    fun severalSpursAreAllCut() {
        val original = route(
            p(0.0, 0.0), p(1000.0, 0.0), p(1000.0, -150.0), p(1000.0, 0.0),
            p(2000.0, 0.0), p(2000.0, 1000.0), p(2250.0, 1000.0), p(2000.0, 1000.0),
            p(2000.0, 2000.0), p(0.0, 2000.0), p(0.0, 0.0),
        )
        val trim = trimSpurs(original)
        assertEquals(2, trim.spurs)
        assertEquals(8.0, trim.route.distanceKm, 0.04)
    }

    @Test
    fun longOutAndBackStaysAndIsLeftToTheScore() {
        // 1,5 km einfach bei 11 km Runde: weit ueber 300 m und ueber 4 %.
        val original = route(
            p(0.0, 0.0), p(1000.0, 0.0), p(1000.0, -1500.0), p(1000.0, 0.0),
            p(2000.0, 0.0), p(2000.0, 2000.0), p(0.0, 2000.0), p(0.0, 0.0),
        )
        val trim = trimSpurs(original)
        assertEquals(0, trim.spurs)
        assertSame(original, trim.route)
        assertTrue(retraceShare(original.points) > 0.2)
    }

    @Test
    fun aSharpCornerIsNotASpur() {
        // Zwei spitze Ecken mit rund 9° zwischen den Schenkeln: verschiedene
        // Wege, die auseinanderlaufen — keine Stichstrecke.
        val original = route(p(0.0, 0.0), p(2000.0, 0.0), p(100.0, 300.0), p(-800.0, 1500.0), p(0.0, 0.0))
        assertEquals(0, trimSpurs(original).spurs)
    }

    @Test
    fun switchbackLegsOnDifferentHeightsAreKept() {
        // Zwei Schenkel 8 m nebeneinander, aber mit 10 % Steigung: nach 80 m
        // liegen sie 16 Hoehenmeter auseinander — eine Serpentine.
        val original = route(
            p(0.0, 0.0, 400.0), p(1000.0, 0.0, 400.0), p(1300.0, 0.0, 430.0),
            p(1300.0, 8.0, 431.0), p(1000.0, 8.0, 461.0), p(0.0, 2000.0, 461.0), p(0.0, 0.0, 400.0),
        )
        assertEquals(0, trimSpurs(original).spurs)
    }

    @Test
    fun spurWithoutElevationIsStillCut() {
        val original = route(
            p(0.0, 0.0, null), p(1000.0, 0.0, null), p(1000.0, -200.0, null), p(1000.0, 0.0, null),
            p(2000.0, 0.0, null), p(2000.0, 2000.0, null), p(0.0, 2000.0, null), p(0.0, 0.0, null),
        )
        val trim = trimSpurs(original)
        assertEquals(1, trim.spurs)
        // Ohne Hoehen folgt der Anstieg der Strecke: 8 von 8,4 km.
        assertEquals(100.0 * 8.0 / 8.4, trim.route.ascentM, 0.5)
    }

    @Test
    fun aBentSpurIsCutAsOne() {
        // 100 m nach Sueden, dann 100 m nach Osten, wenden, denselben Weg zurueck.
        val original = route(
            p(0.0, 0.0), p(1000.0, 0.0), p(1000.0, -100.0), p(1100.0, -100.0), p(1000.0, -100.0), p(1000.0, 0.0),
            p(2000.0, 0.0), p(2000.0, 2000.0), p(0.0, 2000.0), p(0.0, 0.0),
        )
        val trim = trimSpurs(original)
        assertEquals(1, trim.spurs)
        assertEquals(8.0, trim.route.distanceKm, 0.03)
    }

    @Test
    fun degenerateRoutesAreReturnedAsIs() {
        val empty = PlannedRoute(emptyList(), 0.0, 0.0)
        assertSame(empty, trimSpurs(empty).route)
        val pureOutAndBack = route(p(0.0, 0.0), p(200.0, 0.0), p(0.0, 0.0))
        // Start und Ziel sind kein Wendepunkt in der Route; die Spitze ist es
        // zwar, aber eine Route nur aus Stichstrecke bleibt sinnvollerweise … weg.
        assertTrue(trimSpurs(pureOutAndBack).route.points.size >= 2)
    }

    // -------------------------------------------------------- surfaceScore

    private fun surfaced(pavedKm: Double?, unpavedKm: Double?) =
        PlannedRoute(points = emptyList(), distanceKm = 40.0, ascentM = 0.0, pavedKm = pavedKm, unpavedKm = unpavedKm)

    @Test
    fun roadProfilePaysForGravel() {
        assertEquals(0.0, surfaceScore(RouteProfile.ASPHALT, surfaced(40.0, 0.0)))
        assertEquals(10.0, surfaceScore(RouteProfile.ASPHALT, surfaced(30.0, 10.0)), 1e-9)
    }

    @Test
    fun gravelProfileIsRewardedUpToSaturation() {
        assertEquals(0.0, surfaceScore(RouteProfile.SCHOTTER, surfaced(40.0, 0.0)))
        assertEquals(-5.0, surfaceScore(RouteProfile.SCHOTTER, surfaced(26.0, 14.0)), 1e-9)
        assertEquals(-gravelUnpavedBonus, surfaceScore(RouteProfile.SCHOTTER, surfaced(12.0, 28.0)), 1e-9)
        assertEquals(-gravelUnpavedBonus, surfaceScore(RouteProfile.SCHOTTER, surfaced(0.0, 40.0)), 1e-9)
        // Gemischtes Profil: halber Bonus, schon bei 40 % satt.
        assertEquals(-mixedUnpavedBonus, surfaceScore(RouteProfile.GRAVEL, surfaced(20.0, 20.0)), 1e-9)
    }

    @Test
    fun noSurfaceDataOrNoPreferenceMeansNoScore() {
        assertEquals(0.0, surfaceScore(RouteProfile.ASPHALT, surfaced(null, null)))
        // Mehr als die Haelfte unklassifiziert: nicht verlaesslich.
        assertEquals(0.0, surfaceScore(RouteProfile.SCHOTTER, surfaced(5.0, 5.0)))
        assertEquals(0.0, surfaceScore(RouteProfile.RADWEGE, surfaced(10.0, 30.0)))
        assertEquals(0.0, surfaceScore(RouteProfile.KUERZESTER, surfaced(10.0, 30.0)))
    }
}
