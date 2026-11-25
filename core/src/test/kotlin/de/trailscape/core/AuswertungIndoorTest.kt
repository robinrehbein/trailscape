package de.trailscape.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * „Eine Auswertung für alles": die Regeln für Indoor-Fahrten (`docs/auswertung.md`)
 * und die Herkunft der Leistung ([Ride.powerMeasured]).
 */
class AuswertungIndoorTest {

    private val monday = LocalDate.of(2026, 11, 16)

    private fun summary(
        id: String,
        day: LocalDate,
        km: Double,
        indoor: Boolean = false,
        planned: Boolean = false,
        speed: Double = 28.0,
    ): RideSummary {
        val at = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + 9 * 3_600_000L
        return RideSummary(
            id = id,
            name = id,
            createdAt = at,
            updatedAt = at,
            stats = RideStats(
                distanceKm = km,
                ascentM = 100.0,
                descentM = 100.0,
                durationS = (km / speed * 3600).toInt(),
                movingTimeS = (km / speed * 3600).toInt(),
                avgSpeedKmh = speed,
            ),
            planned = planned,
            indoor = indoor,
        )
    }

    private fun facts(vararg rides: RideSummary) =
        rideFacts(rides.toList()) { LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L)) }

    // ------------------------------------------------------------- Regeln

    @Test
    fun `Indoor zaehlt fuer Wochenziel und Jahresziel mit Distanz und Zeit`() {
        val f = facts(
            summary("aussen", monday, 40.0),
            summary("rolle", monday.plusDays(1), 30.0, indoor = true),
            summary("plan", monday.plusDays(1), 99.0, planned = true),
        )
        val today = monday.plusDays(2)
        val goal = WeeklyGoal(monday = monday, targetKm = 100, basisKm = 100.0, kind = WeeklyGoalKind.EINSTIEG)
        assertEquals(70.0, weeklyGoalProgress(goal, f, today).riddenKm, 1e-9)
        assertEquals(70.0, lifetimeTotals(f).km, 1e-9)
        assertEquals(2, f.size)
    }

    @Test
    fun `Tempo-Bestwert zaehlt nur draussen, Distanz und Zeit auch indoor`() {
        val f = facts(
            summary("aussen", monday, 40.0, speed = 24.0),
            // Simuliert und schneller — darf den Tempo-Rekord nicht halten.
            summary("rolle", monday.plusDays(1), 50.0, indoor = true, speed = 33.0),
        )
        val records = personalRecords(f).associateBy { it.kind }
        assertEquals("aussen", records.getValue(RecordKind.SPEED).rideId)
        assertEquals(24.0, records.getValue(RecordKind.SPEED).value, 1e-9)
        assertEquals("rolle", records.getValue(RecordKind.DISTANCE).rideId)
        assertEquals("rolle", records.getValue(RecordKind.TIME).rideId)

        // Und auch als „gesetzt von": die schnellere Rolle bricht keinen Tempo-Rekord.
        assertTrue(recordsSetBy("rolle", f).none { it.kind == RecordKind.SPEED })
        assertTrue(recordsSetBy("rolle", f).any { it.kind == RecordKind.DISTANCE })
    }

    @Test
    fun `outdoorRides schliesst Indoor und Planung aus, riddenRides nur die Planung`() {
        val all = listOf(
            summary("a", monday, 10.0),
            summary("i", monday, 10.0, indoor = true),
            summary("p", monday, 10.0, planned = true),
        )
        assertEquals(listOf("a", "i"), riddenRides(all).map { it.id })
        assertEquals(listOf("a"), outdoorRides(all).map { it.id })
    }

    // ------------------------------------------------- Herkunft der Leistung

    private fun point(i: Int, power: Int?) = TrackPoint(lat = 48.0 + i * 1e-4, lon = 11.0, time = i * 1000L, power = power)

    @Test
    fun `gemessen ab 80 Prozent der Punkte mit Leistung`() {
        assertTrue(derivePowerMeasured(List(10) { point(it, 200) }))
        assertTrue(derivePowerMeasured(List(10) { point(it, if (it < 8) 200 else null) }))
        assertFalse(derivePowerMeasured(List(10) { point(it, if (it < 7) 200 else null) }))
        assertFalse(derivePowerMeasured(List(10) { point(it, null) }))
        assertFalse(derivePowerMeasured(listOf(point(0, 200))))
        assertFalse(derivePowerMeasured(emptyList()))
    }

    private fun ride(points: List<TrackPoint>, powerMeasured: Boolean = false, indoor: Boolean = false) = Ride(
        id = "r",
        name = "r",
        createdAt = 1_000L,
        stats = RideStats.empty,
        points = points,
        indoor = indoor,
        powerMeasured = powerMeasured,
    )

    @Test
    fun `withDerivedPowerMeasured setzt das Feld nur wo es stimmt und nimmt es nie zurueck`() {
        assertTrue(ride(List(10) { point(it, 180) }).withDerivedPowerMeasured().powerMeasured)
        assertFalse(ride(List(10) { point(it, null) }).withDerivedPowerMeasured().powerMeasured)
        // Ein bereits gesetztes Kennzeichen bleibt, auch wenn Punkte fehlen.
        assertTrue(ride(emptyList(), powerMeasured = true).withDerivedPowerMeasured().powerMeasured)
    }

    @Test
    fun `Tour-JSON ohne Messung bleibt byteweise wie bisher, alte Dateien lesen sich als geschaetzt`() {
        val old = ride(listOf(point(0, null), point(1, null)))
        val text = old.toJson().toString()
        assertFalse(text.contains("powerMeasured"))
        assertFalse(old.toSummary().toJson().toString().contains("powerMeasured"))
        assertFalse(Ride.fromJson(Json.parseToJsonElement(text).jsonObject).powerMeasured)
        assertFalse(RideSummary.fromJson(old.toSummary().toJson()).powerMeasured)
    }

    @Test
    fun `Tour-JSON mit Messung traegt das Feld durch Datei, Index und Sync`() {
        val measured = ride(List(5) { point(it, 250) }, powerMeasured = true, indoor = true)
        val json = measured.toJson()
        assertEquals(true, json["powerMeasured"]?.toString()?.toBoolean())
        // Reihenfolge: nach `indoor`, vor dem immer letzten `updatedAt`.
        val keys = json.keys.toList()
        assertTrue(keys.indexOf("indoor") < keys.indexOf("powerMeasured"))
        assertEquals("updatedAt", keys.last())
        val back = Ride.fromJson(Json.parseToJsonElement(json.toString()).jsonObject)
        assertTrue(back.powerMeasured)
        assertTrue(back.indoor)
        assertTrue(back.toSummary().powerMeasured)
        assertTrue(RideSummary.fromJson(back.toSummary().toJson()).powerMeasured)
        // Ein aelterer Leser (ohne das Feld) ignoriert den Schluessel: das Restobjekt ist lesbar.
        val withoutKey = JsonObject(json.filterKeys { it != "powerMeasured" })
        assertFalse(Ride.fromJson(withoutKey).powerMeasured)
    }

    @Test
    fun `Lastdestillat ohne den Schluessel liest sich als geschaetzt`() {
        val facts = RideLoadFacts.fromJson(JsonObject(emptyMap()))
        assertFalse(facts.physicsMeasured)
        assertNull(facts.bestTwentyMinW)
    }

    // ------------------------------------------------------------- FTP-Herkunft

    @Test
    fun `20-min-Mittel aus gemessener Leistung heisst gemessen, sonst geschaetzt`() {
        val profile = TrainingProfile(ageYears = 40, weightKg = 78.0)
        val measured = resolveEftp(profile, bestTwentyMinW = 300.0, bestTwentyMinMeasured = true)
        assertEquals(EftpSource.ZWANZIG_MINUTEN_GEMESSEN, measured.source)
        assertEquals(285.0, measured.watts, 1e-9)

        val estimated = resolveEftp(profile, bestTwentyMinW = 300.0)
        assertEquals(EftpSource.ZWANZIG_MINUTEN, estimated.source)
        assertEquals(285.0, estimated.watts, 1e-9)

        // Unter der Untergrenze bleibt es bei der Profilschaetzung, auch mit Messung.
        val weak = resolveEftp(profile, bestTwentyMinW = 120.0, bestTwentyMinMeasured = true)
        assertEquals(EftpSource.GESCHAETZT, weak.source)
    }
}
