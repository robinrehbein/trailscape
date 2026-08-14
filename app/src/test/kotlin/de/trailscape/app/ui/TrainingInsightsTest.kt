package de.trailscape.app.ui

import de.trailscape.core.Confidence
import de.trailscape.core.DailyValue
import de.trailscape.core.EftpSource
import de.trailscape.core.Ride
import de.trailscape.core.RideLoad
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingProfile
import de.trailscape.core.VitalsSummary
import de.trailscape.core.VitalsTrend
import de.trailscape.core.computeRideLoadForRide
import de.trailscape.core.computeStats
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests der Zustands-Rechenschicht (Port von `_computeInsights` aus
 * `lib/state.dart`).
 *
 * Laufen als normale JVM-Unit-Tests, weil [computeInsights] bewusst keinen
 * einzigen Android-Import hat — [AppViewModel] selbst (Android-`ViewModel`)
 * bleibt ungetestet, seine Rechenarbeit steckt komplett hier.
 */
class TrainingInsightsTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 6, 15, 12, 0)
    private val profile = TrainingProfile(ageYears = 40, weightKg = 78.0)

    private fun epochMs(at: LocalDateTime): Long =
        at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * Eine Stunde gleichmaessiges Fahren mit Puls.
     *
     * [speedMs] steuert, ob das Physikmodell ueberhaupt nennenswerte Last
     * sieht: Bei Schrittgeschwindigkeit ist die geschaetzte Leistung so klein,
     * dass HF- und Physikpfad nicht mehr vergleichbar sind.
     */
    private fun ride(
        id: String,
        startedAt: LocalDateTime,
        hr: Int? = 140,
        speedMs: Double = 1.11,
    ): Ride {
        val startMs = epochMs(startedAt)
        val stepDeg = speedMs * 5.0 / 111_320.0
        val points = (0..720).map { i ->
            TrackPoint(
                lat = 48.0 + i * stepDeg,
                lon = 11.0,
                ele = 500.0,
                time = startMs + i * 5_000L,
                hr = hr,
            )
        }
        return Ride(
            id = id,
            name = "Tour $id",
            createdAt = startMs,
            stats = computeStats(points),
            points = points,
        )
    }

    @Test
    fun `ohne Daten bleiben alle abgeleiteten Werte leer`() {
        val insights = computeInsights(
            rides = emptyList(),
            vitals = null,
            profile = profile,
            now = now,
        )

        assertTrue(insights.rideLoads.isEmpty())
        assertTrue(insights.fitness.points.isEmpty())
        assertEquals(0, insights.fitness.historyDays)
        assertEquals(0.0, insights.weeklyLoad)
        assertNull(insights.fourWeekMeanWeeklyLoad)
        assertNull(insights.weeklyTarget)
        assertNull(insights.latest)
        assertEquals(profile, insights.profile)
        // Die Ampeln liefern trotzdem ein anzeigbares Ergebnis.
        assertTrue(insights.readinessLast7.isEmpty())
        assertTrue(insights.recommendation.title.isNotBlank())
    }

    @Test
    fun `Tourlast entspricht dem Rechenkern und die Fitnesskurve laeuft bis heute`() {
        val ride = ride("a", now.minusDays(3))
        val insights = computeInsights(
            rides = listOf(ride),
            vitals = null,
            profile = profile,
            now = now,
        )

        assertEquals(setOf("a"), insights.rideLoads.keys)
        // Unter fuenf Kalibrierungspaaren ist α = 1,0 — die Last muss deshalb
        // exakt der unveraenderten :core-Berechnung entsprechen.
        assertEquals(1.0, insights.calibration.alpha)
        assertEquals(
            computeRideLoadForRide(ride, profile, eftpW = insights.eftp.watts),
            insights.rideLoads.getValue("a"),
        )

        val latest = assertNotNull(insights.latest)
        assertEquals(now.toLocalDate().atStartOfDay(), latest.day)
        assertEquals(4, insights.fitness.historyDays)
        assertTrue(insights.weeklyLoad > 0.0)
        assertNotNull(insights.fourWeekMeanWeeklyLoad)
        assertNotNull(insights.weeklyTarget)
    }

    @Test
    fun `unveraenderte Touren werden aus dem Cache bedient`() {
        val ride = ride("a", now.minusDays(2))
        val cache = mutableMapOf<String, RideLoad>()

        val first = computeInsights(listOf(ride), null, profile, now, cache)
        assertEquals(1, cache.size)

        val second = computeInsights(listOf(ride), null, profile, now, cache)
        // Identitaet, nicht nur Gleichheit: der zweite Lauf hat nicht gerechnet.
        assertSame(first.rideLoads.getValue("a"), second.rideLoads.getValue("a"))

        // Eine geloeschte Tour raeumt ihren Cache-Eintrag mit ab.
        computeInsights(emptyList(), null, profile, now, cache)
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `gemessener Ruhepuls ersetzt den fehlenden Profilwert`() {
        val series = (1..10).map { DailyValue(day = now.minusDays(it.toLong()), value = 48.0 + it % 2) }
        val vitals = VitalsSummary(
            days = VITALS_WINDOW_DAYS,
            from = now.minusDays(VITALS_WINDOW_DAYS.toLong()),
            to = now,
            restingHeartRate = VitalsTrend(
                series = series,
                lastWeekAvg = null,
                previousWeekAvg = null,
            ),
            sleepHours = VitalsTrend.empty,
        )

        val effective = effectiveProfile(profile, vitals)
        assertEquals(48.5, effective.restingHrOverride)

        // Ein eigener Wert im Profil gewinnt weiterhin.
        val own = profile.copyWith(restingHrOverride = 55.0)
        assertEquals(55.0, effectiveProfile(own, vitals).restingHrOverride)

        // Und die Auswertung benutzt genau dieses effektive Profil.
        assertEquals(effective, computeInsights(emptyList(), vitals, profile, now).profile)
    }

    // -----------------------------------------------------------------------
    // Lastskala: FTP-Aufloesung (K1)
    // -----------------------------------------------------------------------

    @Test
    fun `ohne Belege bleibt es beim Profil-Default, aber ehrlich beschriftet`() {
        val insights = computeInsights(emptyList(), null, profile, now)
        assertEquals(EftpSource.GESCHAETZT, insights.eftp.source)
        // 2,4 W/kg × 78 kg.
        assertEquals(187.2, insights.eftp.watts, 1e-9)
        assertEquals(Confidence.LOW, insights.eftp.confidence)
        assertTrue(insights.loadScaleNote.contains("187 W"))
        assertTrue(insights.loadScaleNote.contains("2,4"))
        // Der Hinweis nennt die Konsequenz einer Aenderung.
        assertTrue(insights.loadScaleNote.contains("bisherigen"))
    }

    @Test
    fun `eingetragene FTP bestimmt die Lastskala und steht so im Hinweis`() {
        val strong = profile.copyWith(eftpOverrideW = 250.0)
        val ride = ride("a", now.minusDays(2), hr = null)
        val insights = computeInsights(listOf(ride), null, strong, now)

        assertEquals(EftpSource.EINGETRAGEN, insights.eftp.source)
        assertEquals(250.0, insights.eftp.watts, 0.0)
        assertEquals(Confidence.HIGH, insights.eftp.confidence)
        assertTrue(insights.loadScaleNote.contains("250 W"))
        assertTrue(insights.loadScaleNote.contains("3,2 W/kg"))

        // Und die Skala haengt wirklich daran: `eTSS ∝ 1/FTP²`, die haelftige
        // FTP ergibt die vierfache Last.
        val weak = profile.copyWith(eftpOverrideW = 125.0)
        val weakLoad = computeInsights(listOf(ride), null, weak, now)
            .rideLoads.getValue("a").load
        val strongLoad = insights.rideLoads.getValue("a").load
        assertEquals(4.0, weakLoad / strongLoad, 0.01)
    }

    @Test
    fun `die Kalibrierung wird als FTP-Korrektur benutzt statt verworfen`() {
        // Sechs Touren mit Puls **und** Hoehenprofil: Die Herzfrequenz sagt
        // deutlich weniger Last als das Physikmodell, weil die Default-FTP zu
        // niedrig angesetzt ist. Frueher lag α unter 0,6 und wurde verworfen.
        val rides = (0 until 6).map { i ->
            ride("r$i", now.minusDays((3 + i * 2).toLong()), speedMs = 8.33)
        }
        val insights = computeInsights(rides, null, profile, now)

        assertTrue(insights.calibration.sampleCount >= 5)
        assertTrue(insights.calibration.rawAlpha!! < 0.6, "α = ${insights.calibration.rawAlpha}")
        assertFalse(insights.calibration.clamped)
        assertNotNull(insights.calibration.rawAlpha)
        assertEquals(EftpSource.KALIBRIERT, insights.eftp.source)
        assertNotNull(insights.eftp.alphaApplied)
        // Die FTP folgt dem Fixpunkt FTP / √α.
        assertEquals(
            187.2 / kotlin.math.sqrt(insights.eftp.alphaApplied!!),
            insights.eftp.watts,
            0.5,
        )
        // α darf danach nicht noch einmal als Faktor auf der Last liegen.
        assertTrue(insights.loadScaleNote.contains("Herzfrequenz"))
    }

    @Test
    fun `bei eingetragener FTP bleibt alpha ein Faktor und verbiegt nichts`() {
        val entered = profile.copyWith(eftpOverrideW = 250.0)
        val rides = (0 until 6).map { i ->
            ride("r$i", now.minusDays((3 + i * 2).toLong()), speedMs = 8.33)
        }
        val insights = computeInsights(rides, null, entered, now)
        assertEquals(250.0, insights.eftp.watts, 0.0)
        assertNull(insights.eftp.alphaApplied)
    }

    // -----------------------------------------------------------------------
    // Ruhepuls-Snapshot je Tour (M8)
    // -----------------------------------------------------------------------

    @Test
    fun `der Ruhepuls einer Tour kommt aus ihrer eigenen Zeit`() {
        // Ruhepuls faellt von 62 (vor einem Jahr) auf 46 (heute).
        val old = (0 until 40).map {
            DailyValue(day = now.minusDays(360L + it), value = 62.0)
        }
        val recent = (0 until 40).map { DailyValue(day = now.minusDays(it.toLong()), value = 46.0) }
        val series = (old + recent).sortedBy { it.day }

        assertEquals(62.0, restingHrNear(series, now.minusDays(340))!!, 1e-9)
        assertEquals(46.0, restingHrNear(series, now.minusDays(5))!!, 1e-9)
        // Ohne genug Material im Fenster bleibt es beim Profilwert.
        assertNull(restingHrNear(series, now.minusDays(180)))
        assertNull(restingHrNear(emptyList(), now))
    }

    @Test
    fun `jede Tour rechnet mit dem Ruhepuls ihrer eigenen Zeit`() {
        // Ruhepuls 62 rund um die alte Tour, 46 rund um die neue.
        val around = { center: Long, value: Double ->
            (-20..20).map { DailyValue(day = now.minusDays(center + it), value = value) }
        }
        val vitals = vitalsWithRestingHr(
            (around(340, 62.0) + around(3, 46.0)).sortedBy { it.day },
        )
        val oldRide = ride("alt", now.minusDays(340))
        val newRide = ride("neu", now.minusDays(3))
        val insights = computeInsights(listOf(oldRide, newRide), vitals, profile, now)

        // Der Ruhepuls geht ueber die Herzfrequenzreserve in jeden TRIMP ein:
        // Bei gleichem Puls von 140 ergibt ein tieferer Ruhepuls einen
        // hoeheren Anteil der Reserve und damit mehr Last. Ohne
        // Zeitbezug wuerde die alte Tour mit dem heutigen Ruhepuls gerechnet.
        val expectedOld = computeRideLoadForRide(
            oldRide,
            profile.copyWith(restingHrOverride = 62.0),
            eftpW = insights.eftp.watts,
        ).load
        val expectedNew = computeRideLoadForRide(
            newRide,
            profile.copyWith(restingHrOverride = 46.0),
            eftpW = insights.eftp.watts,
        ).load

        assertEquals(expectedOld, insights.rideLoads.getValue("alt").load, 1e-9)
        assertEquals(expectedNew, insights.rideLoads.getValue("neu").load, 1e-9)
        assertTrue(expectedNew > expectedOld)

        // Gegenprobe: Mit dem globalen Median (54 bpm) waere die alte Tour
        // anders bewertet worden.
        val globalMedian = computeRideLoadForRide(
            oldRide,
            profile.copyWith(restingHrOverride = 54.0),
            eftpW = insights.eftp.watts,
        ).load
        assertTrue(kotlin.math.abs(globalMedian - expectedOld) > 0.5)
    }

    private fun vitalsWithRestingHr(series: List<DailyValue>): VitalsSummary = VitalsSummary(
        days = VITALS_WINDOW_DAYS,
        from = now.minusDays(VITALS_WINDOW_DAYS.toLong()),
        to = now,
        restingHeartRate = VitalsTrend(series = series, lastWeekAvg = null, previousWeekAvg = null),
        sleepHours = VitalsTrend.empty,
    )

    @Test
    fun `Kartenstil faellt bei unbekannter Kennung auf den Standard zurueck`() {
        assertEquals("cyclosm", mapStyleById("cyclosm").id)
        assertEquals(defaultMapStyle, mapStyleById("gibt-es-nicht"))
        assertEquals(defaultMapStyle, mapStyleById(null))
        assertTrue(defaultMapStyle.toRasterStyleJson().contains("\"type\": \"raster\""))
    }
}
