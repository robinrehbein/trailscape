package de.trailscape.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ruhepuls aus dem Nacht-Puls, Schlaf-Zusammenfuehrung, Diagnosezeilen und
 * Routen-Einzelfreigabe — die reinen Funktionen ohne Gateway.
 */
class HealthVitalsDerivationTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(year, month, day, hour, minute)

    private fun series(
        from: LocalDateTime,
        count: Int,
        stepMinutes: Long,
        bpm: (Int) -> Double,
    ): List<HealthHeartRateSample> =
        (0 until count).map { HealthHeartRateSample(from.plusMinutes(it * stepMinutes), bpm(it)) }

    // -----------------------------------------------------------------------
    // mergeOverlappingSleep
    // -----------------------------------------------------------------------

    @Test
    fun `mergeOverlappingSleep - vereinigt dieselbe Nacht zweier Apps`() {
        val merged = mergeOverlappingSleep(
            listOf(
                HealthSleepSession(at(2026, 8, 8, 23), at(2026, 8, 9, 6), "a"),
                HealthSleepSession(at(2026, 8, 8, 22, 30), at(2026, 8, 9, 7), "b"),
            ),
        )
        assertEquals(listOf(HealthSleepSession(at(2026, 8, 8, 22, 30), at(2026, 8, 9, 7), null)), merged)
    }

    @Test
    fun `mergeOverlappingSleep - laesst getrennte und aneinanderstossende Sitzungen stehen`() {
        val night = HealthSleepSession(at(2026, 8, 8, 23), at(2026, 8, 9, 5), "a")
        val continued = HealthSleepSession(at(2026, 8, 9, 5), at(2026, 8, 9, 6), "a")
        val nap = HealthSleepSession(at(2026, 8, 9, 14), at(2026, 8, 9, 15), "a")
        assertEquals(listOf(night, continued, nap), mergeOverlappingSleep(listOf(nap, continued, night)))
    }

    @Test
    fun `mergeOverlappingSleep - behaelt die Quelle, wenn alle Teile aus einer App stammen`() {
        val merged = mergeOverlappingSleep(
            listOf(
                HealthSleepSession(at(2026, 8, 8, 23), at(2026, 8, 9, 3), "a"),
                HealthSleepSession(at(2026, 8, 9, 2), at(2026, 8, 9, 7), "a"),
            ),
        )
        assertEquals("a", merged.single().source)
        assertEquals(8 * 60L, merged.single().durationMinutes)
    }

    // -----------------------------------------------------------------------
    // nightWindowForDay
    // -----------------------------------------------------------------------

    @Test
    fun `nightWindowForDay - nimmt die laengste Schlafsitzung, die an dem Tag endet`() {
        val window = nightWindowForDay(
            at(2026, 8, 9, 15),
            listOf(
                HealthSleepSession(at(2026, 8, 8, 23), at(2026, 8, 9, 6, 30)),
                HealthSleepSession(at(2026, 8, 9, 13), at(2026, 8, 9, 14)),
                HealthSleepSession(at(2026, 8, 9, 23), at(2026, 8, 10, 7)),
            ),
        )
        assertEquals(NightWindow(at(2026, 8, 8, 23), at(2026, 8, 9, 6, 30), fromSleep = true), window)
    }

    @Test
    fun `nightWindowForDay - ohne echte Nacht gilt 0 bis 6 Uhr`() {
        val window = nightWindowForDay(
            at(2026, 8, 9),
            listOf(HealthSleepSession(at(2026, 8, 9, 13), at(2026, 8, 9, 14))),
        )
        assertEquals(NightWindow(at(2026, 8, 9), at(2026, 8, 9, 6), fromSleep = false), window)
    }

    // -----------------------------------------------------------------------
    // nightlyRestingHeartRate
    // -----------------------------------------------------------------------

    @Test
    fun `nightlyRestingHeartRate - niedrigster 30-Minuten-Mittelwert der Nacht`() {
        // Alle 5 min von 0 bis 6 Uhr: 60 bpm, zwischen 3:00 und 3:40 52 bpm.
        val samples = series(at(2026, 8, 9), 72, 5) { i -> if (i in 36..43) 52.0 else 60.0 }
        val rhr = nightlyRestingHeartRate(samples, at(2026, 8, 9), at(2026, 8, 9, 6))
        assertEquals(52.0, rhr!!, 1e-9)
    }

    @Test
    fun `nightlyRestingHeartRate - ein einzelner Ausreisser bestimmt den Wert nicht`() {
        val samples = series(at(2026, 8, 9), 72, 5) { i ->
            when (i) {
                10 -> 30.0 // Messfehler
                in 36..43 -> 52.0
                else -> 60.0
            }
        }
        val rhr = nightlyRestingHeartRate(samples, at(2026, 8, 9), at(2026, 8, 9, 6))!!
        // Das Fenster um den Ausreisser liegt bei (30 + 5 × 60) / 6 = 55.
        assertEquals(52.0, rhr, 1e-9)
    }

    @Test
    fun `nightlyRestingHeartRate - kommt mit 10-Minuten-Abtastung aus`() {
        val samples = series(at(2026, 8, 8, 23), 48, 10) { i -> if (i in 20..22) 48.0 else 58.0 }
        val rhr = nightlyRestingHeartRate(samples, at(2026, 8, 8, 23), at(2026, 8, 9, 7))
        assertEquals(48.0, rhr!!, 1e-9)
    }

    @Test
    fun `nightlyRestingHeartRate - zu wenige Messungen ergeben keinen Wert`() {
        val samples = series(at(2026, 8, 9, 1), 9, 5) { 55.0 }
        assertNull(nightlyRestingHeartRate(samples, at(2026, 8, 9), at(2026, 8, 9, 6)))
    }

    @Test
    fun `nightlyRestingHeartRate - ignoriert Messungen ausserhalb des Fensters und Unplausibles`() {
        val inside = series(at(2026, 8, 9, 1), 12, 5) { 56.0 }
        val outside = series(at(2026, 8, 9, 7), 12, 5) { 40.0 }
        val junk = listOf(HealthHeartRateSample(at(2026, 8, 9, 2), 0.0))
        val rhr = nightlyRestingHeartRate(inside + outside + junk, at(2026, 8, 9), at(2026, 8, 9, 6))
        assertEquals(56.0, rhr!!, 1e-9)
    }

    // -----------------------------------------------------------------------
    // Diagnosezeilen
    // -----------------------------------------------------------------------

    @Test
    fun `describeVitalsEntry - Ruhepuls leer mit Freigabe und aktivem Ersatz`() {
        val line = describeVitalsEntry(
            VitalsTypeDiagnostics(
                type = HealthReadType.RUHEPULS,
                permissionGranted = true,
                recordCount = 0,
                derivedDays = 12,
                fallbackAttempted = true,
            ),
        )
        assertEquals(
            "Ruhepuls: 0 Einträge (Freigabe ja) — Samsung Health schreibt diesen Wert vermutlich " +
                "nicht; Ersatz aus Nacht-Puls aktiv (12 Tage)",
            line,
        )
    }

    @Test
    fun `describeVitalsEntry - Schlaf mit Quellen und zusammengefuehrten Sitzungen`() {
        val line = describeVitalsEntry(
            VitalsTypeDiagnostics(
                type = HealthReadType.SCHLAF,
                permissionGranted = true,
                recordCount = 1,
                origins = listOf("com.sec.android.app.shealth"),
                mergedOverlaps = 0,
            ),
        )
        assertEquals("Schlaf: 1 Eintrag (Freigabe ja) · Quelle: com.sec.android.app.shealth", line)

        val doubled = describeVitalsEntry(
            VitalsTypeDiagnostics(
                type = HealthReadType.SCHLAF,
                permissionGranted = null,
                recordCount = 4,
                origins = listOf("a", "b"),
                mergedOverlaps = 2,
            ),
        )
        assertEquals(
            "Schlaf: 4 Einträge (Freigabe unbekannt) · Quelle: a, b — 2 überlappende " +
                "Sitzung(en) zusammengeführt",
            doubled,
        )
    }

    @Test
    fun `describeVitalsEntry - fehlende Freigabe wird benannt`() {
        val line = describeVitalsEntry(
            VitalsTypeDiagnostics(
                type = HealthReadType.VO2MAX,
                permissionGranted = false,
                recordCount = 0,
                error = "Der VO2max-Wert: Health Connect verweigert den Zugriff.",
            ),
        )
        assertEquals(
            "VO2max: Lesefehler (Freigabe nein) — Der VO2max-Wert: Health Connect verweigert " +
                "den Zugriff.; Freigabe in Health Connect erteilen",
            line,
        )
    }

    @Test
    fun `withVitalsDiagnostics - haengt den Abschnitt an und ersetzt ihn beim naechsten Mal`() {
        val report = HealthSyncReport.empty(at(2026, 8, 1), at(2026, 8, 10))
            .copy(debugLines = listOf("Zeitraum: …", "Ergebnis: …"))
        fun summary(count: Int) = VitalsSummary(
            days = 2,
            from = at(2026, 8, 9),
            to = at(2026, 8, 10, 12),
            restingHeartRate = VitalsTrend.empty,
            sleepHours = VitalsTrend.empty,
            diagnostics = listOf(
                VitalsTypeDiagnostics(HealthReadType.HRV, permissionGranted = true, recordCount = count),
            ),
        )

        val once = report.withVitalsDiagnostics(summary(3))
        assertEquals(
            listOf(
                "Zeitraum: …",
                "Ergebnis: …",
                "Vitalwerte (2 Tage ab 09.08. 00:00)",
                "  · HRV: 3 Einträge (Freigabe ja)",
            ),
            once.debugLines,
        )
        val twice = once.withVitalsDiagnostics(summary(5))
        assertEquals(4, twice.debugLines.size)
        assertEquals("  · HRV: 5 Einträge (Freigabe ja)", twice.debugLines.last())
        assertEquals(5, twice.vitals.single().recordCount)
    }

    @Test
    fun `describeVitalsDiagnostics - ohne Diagnose kein Abschnitt`() {
        val summary = VitalsSummary(
            days = 1,
            from = at(2026, 8, 10),
            to = at(2026, 8, 10, 12),
            restingHeartRate = VitalsTrend.empty,
            sleepHours = VitalsTrend.empty,
        )
        assertTrue(describeVitalsDiagnostics(summary).isEmpty())
    }

    // -----------------------------------------------------------------------
    // Routen-Einzelfreigabe
    // -----------------------------------------------------------------------

    @Test
    fun `RouteConsentRequest - Speicherformat hin und zurueck`() {
        val requests = listOf(
            RouteConsentRequest("s1", "hc-s1", at(2026, 8, 1, 10), at(2026, 8, 1, 12), "com.sec.android.app.shealth"),
            RouteConsentRequest("s2", "hc-s2", at(2026, 8, 2, 10), at(2026, 8, 2, 11)),
        )
        assertEquals(requests, decodeRouteConsentRequests(encodeRouteConsentRequests(requests)))
        assertTrue(decodeRouteConsentRequests(null).isEmpty())
        assertTrue(decodeRouteConsentRequests("kaputt").isEmpty())
    }

    @Test
    fun `mergeRouteConsentRequests - je Session ein Eintrag, neueste zuerst`() {
        val old = RouteConsentRequest("s1", "hc-s1", at(2026, 8, 1), at(2026, 8, 1, 1))
        val newer = RouteConsentRequest("s2", "hc-s2", at(2026, 8, 3), at(2026, 8, 3, 1))
        val again = old.copy(source = "neu")
        val merged = mergeRouteConsentRequests(listOf(old), listOf(newer, again))
        assertEquals(listOf(newer, again), merged)
    }

    @Test
    fun `attachRouteToRide - traegt Punkte ein und behaelt die Geraetedistanz`() {
        val start = at(2026, 8, 1, 10)
        val ride = Ride(
            id = "hc-s1",
            name = "Tour 01.08.2026 (Watch)",
            createdAt = dartEpochMs(start),
            stats = RideStats(
                distanceKm = 21.5,
                ascentM = 0.0,
                descentM = 0.0,
                durationS = 3600,
                avgHrBpm = 140,
                maxHrBpm = 170,
            ),
        )
        val route = (0 until 7).map { i ->
            HealthRoutePoint(50.0 + i * 0.01, 8.0, start.plusMinutes(i * 10L), ele = 100.0 + i * 10)
        }
        val heartRate = listOf(HealthHeartRateSample(start.plusMinutes(10), 135.0))

        val updated = attachRouteToRide(ride, route, heartRate)
        assertEquals(7, updated.points.size)
        assertEquals(135, updated.points[1].hr)
        assertNull(updated.points[3].hr)
        assertEquals(21.5, updated.stats.distanceKm)
        assertEquals(3600, updated.stats.durationS)
        assertEquals(140, updated.stats.avgHrBpm)
        assertTrue(updated.stats.ascentM > 0)
        assertEquals(ride.id, updated.id)
        assertFalse(updated.planned)
        assertEquals(ride, attachRouteToRide(ride, emptyList()))
    }
}
