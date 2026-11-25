package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests des gespeicherten Erfolgsstands (`AchievementProgress.kt`) und seines
 * Platzes im Backup (`Export.kt`): Hin und zurueck, alte und neue Staende,
 * unbekannte und kaputte Eintraege.
 */
class AchievementProgressTest {

    private val day = LocalDate.of(2026, 11, 4)
    private val monday = LocalDate.of(2026, 11, 2)

    private val full = AchievementProgressData(
        keptLevel = 7,
        pointsSince = LocalDate.of(2025, 3, 1),
        pauseWeeks = setOf(LocalDate.of(2026, 8, 3), monday),
        earned = mapOf("first_ride" to LocalDate.of(2025, 3, 2), "aus_der_zukunft" to day),
        seen = setOf("first_ride"),
        hidden = true,
    )

    private fun ride() = Ride(
        id = "r1",
        name = "Runde",
        createdAt = 1_700_000_000_000,
        stats = RideStats(distanceKm = 12.0, ascentM = 50.0, descentM = 50.0),
    )

    // -----------------------------------------------------------------------
    // Format
    // -----------------------------------------------------------------------

    @Test
    fun roundTripKeepsEverything() {
        assertEquals(full, AchievementProgressData.decode(full.encode()))
        assertEquals(full, AchievementProgressData.fromJson(full.toJson()))
        assertEquals(AchievementProgressData(), AchievementProgressData.decode(AchievementProgressData().encode()))
    }

    @Test
    fun defaultStateWritesOnlyTheFixedKeys() {
        val json = AchievementProgressData().toJson()
        assertEquals(setOf("version", "keptLevel", "pauseWeeks", "earned", "seen"), json.keys)
        assertEquals(
            """{"version":1,"keptLevel":1,"pauseWeeks":[],"earned":{},"seen":[]}""",
            AchievementProgressData().encode(),
        )
    }

    @Test
    fun outputIsSortedAndStable() {
        val a = AchievementProgressData(
            earned = mapOf("b" to day, "a" to day),
            seen = setOf("z", "a"),
            pauseWeeks = setOf(monday, monday.minusWeeks(3)),
        )
        val b = AchievementProgressData(
            earned = mapOf("a" to day, "b" to day),
            seen = setOf("a", "z"),
            pauseWeeks = setOf(monday.minusWeeks(3), monday),
        )
        assertEquals(a.encode(), b.encode())
        assertTrue(a.encode().indexOf("\"a\"") < a.encode().indexOf("\"b\""))
    }

    @Test
    fun readsAnOlderMinimalState() {
        // Ein Stand, wie ihn eine fruehe Version schreiben koennte: nur Stufe und Gesehenes.
        val old = AchievementProgressData.decode("""{"version":1,"keptLevel":4,"seen":["rides_10"]}""")
        assertEquals(AchievementProgressData(keptLevel = 4, seen = setOf("rides_10")), old)
    }

    @Test
    fun readsANewerStateTolerantly() {
        val newer = """
            {"version":7,"keptLevel":3,"pointsSince":"2026-01-05","pauseWeeks":["2026-08-05","kaputt",17],
             "earned":{"first_ride":"2026-01-06","zahl":5,"":"2026-01-01","neu":"2026-02-30"},
             "seen":["first_ride",3,null,""],"hidden":false,"garage":{"helm":"rot"},"rang":12}
        """.trimIndent()
        val read = assertNotNull(AchievementProgressData.decode(newer))
        assertEquals(3, read.keptLevel)
        assertEquals(LocalDate.of(2026, 1, 5), read.pointsSince)
        // Ein Mittwoch meint seine Woche.
        assertEquals(setOf(LocalDate.of(2026, 8, 3)), read.pauseWeeks)
        assertEquals(mapOf("first_ride" to LocalDate.of(2026, 1, 6)), read.earned)
        assertEquals(setOf("first_ride"), read.seen)
        assertFalse(read.hidden)
    }

    @Test
    fun brokenValuesFallBackToDefaults() {
        val read = assertNotNull(
            AchievementProgressData.decode(
                """{"keptLevel":"9","pointsSince":20260105,"pauseWeeks":{},"earned":[],"seen":"x","hidden":"true"}""",
            ),
        )
        assertEquals(AchievementProgressData(), read)
        assertEquals(maxAchievementLevel, AchievementProgressData.decode("""{"keptLevel":900}""")?.keptLevel)
        assertEquals(1, AchievementProgressData.decode("""{"keptLevel":-2}""")?.keptLevel)
        assertEquals(1, AchievementProgressData.decode("""{"keptLevel":2.5}""")?.keptLevel)
    }

    @Test
    fun notAnObjectIsNull() {
        for (text in listOf(null, "", "  ", "kaputt{", "5", "\"text\"", "[]", "null")) {
            assertNull(AchievementProgressData.decode(text), text.toString())
        }
    }

    // -----------------------------------------------------------------------
    // Fortschreiben
    // -----------------------------------------------------------------------

    @Test
    fun mergeTakesTheBestOfBoth() {
        val local = AchievementProgressData(
            keptLevel = 9,
            pointsSince = LocalDate.of(2026, 5, 1),
            pauseWeeks = setOf(monday),
            earned = mapOf("first_ride" to LocalDate.of(2026, 5, 2), "rides_10" to LocalDate.of(2026, 6, 1)),
            seen = setOf("rides_10"),
        )
        val merged = local.mergedWith(full)
        assertEquals(9, merged.keptLevel)
        assertEquals(LocalDate.of(2025, 3, 1), merged.pointsSince)
        assertEquals(setOf(LocalDate.of(2026, 8, 3), monday), merged.pauseWeeks)
        assertEquals(LocalDate.of(2025, 3, 2), merged.earned["first_ride"])
        assertEquals(LocalDate.of(2026, 6, 1), merged.earned["rides_10"])
        assertEquals(day, merged.earned["aus_der_zukunft"])
        assertEquals(setOf("first_ride", "rides_10"), merged.seen)
        assertTrue(merged.hidden)
        // Ohne Punktebeginn auf beiden Seiten bleibt es dabei.
        assertNull(AchievementProgressData().mergedWith(AchievementProgressData()).pointsSince)
    }

    @Test
    fun recordingKeepsReachedBadgesAndRaisesTheLevel() {
        val facts = listOf(RideFact("a", day, 60.0, 100.0, 3 * 3600, 20.0))
        val overview = achievementOverview(AchievementInput(facts, day))
        val recorded = AchievementProgressData().recording(overview, fallbackDate = day)
        assertEquals(day, recorded.earned["first_ride"])
        assertEquals(day, recorded.earned["ride_50km"])
        assertEquals(overview.level.level, recorded.keptLevel)
        // Nichts Neues: dieselbe Instanz, der Aufrufer schreibt nicht.
        assertSame(recorded, recorded.recording(overview, fallbackDate = day))
        // Ein frueheres Datum bleibt.
        val earlier = AchievementProgressData(earned = mapOf("first_ride" to day.minusDays(9)))
        assertEquals(day.minusDays(9), earlier.recording(overview, day).earned["first_ride"])
    }

    @Test
    fun recordingUsesTheFallbackDateWhenTheDayIsUnknown() {
        val facts = listOf(RideFact("a", day, 10.0, 0.0, 600, 20.0))
        val overview = achievementOverview(
            AchievementInput(facts, day, sources = AchievementSources(exploration = ExplorationAchievementFacts(150, 2))),
        )
        assertEquals(day.plusDays(1), AchievementProgressData().recording(overview, day.plusDays(1)).earned["tiles_100"])
    }

    @Test
    fun seeingAndPauses() {
        val seen = AchievementProgressData().seeing(listOf(AchievementId.FIRST_RIDE, AchievementId.RIDES_10))
        assertEquals(setOf("first_ride", "rides_10"), seen.seen)
        assertSame(seen, seen.seeing(listOf(AchievementId.FIRST_RIDE)))
        val paused = AchievementProgressData().togglingPause(day)
        assertEquals(setOf(monday), paused.pauseWeeks)
        assertEquals(emptySet<LocalDate>(), paused.togglingPause(monday.plusDays(6)).pauseWeeks)
    }

    // -----------------------------------------------------------------------
    // Backup
    // -----------------------------------------------------------------------

    @Test
    fun backupRoundTripCarriesTheProgress() {
        val json = buildBackupJson(listOf(ride()), null, exportedAtMs = 0, progress = full)
        val data = parseBackupJson(json, CoreTextsDe)
        assertEquals(full, data.progress)
        assertEquals(listOf("r1"), data.rides.map { it.id })
    }

    @Test
    fun streamedBackupWithProgressIsByteIdentical() {
        val goal = YearGoal(3000, LocalDate.of(2026, 2, 1))
        val streamed = StringBuilder()
        writeBackupJson(streamed, listOf(ride()).asSequence(), null, exportedAtMs = 5, yearGoal = goal, progress = full)
        assertEquals(buildBackupJson(listOf(ride()), null, exportedAtMs = 5, yearGoal = goal, progress = full), streamed.toString())
        val empty = StringBuilder()
        writeBackupJson(empty, emptySequence(), null, exportedAtMs = 5, progress = AchievementProgressData())
        assertEquals(buildBackupJson(emptyList(), null, exportedAtMs = 5, progress = AchievementProgressData()), empty.toString())
    }

    @Test
    fun backupWithoutProgressIsUnchanged() {
        val json = buildBackupJson(listOf(ride()), null, exportedAtMs = 0)
        assertFalse(json.contains("progress"))
        assertNull(parseBackupJson(json, CoreTextsDe).progress)
        // Der Kopf liegt vor den Touren — und `progress` dort, wo es hingehoert.
        val withProgress = buildBackupJson(listOf(ride()), null, exportedAtMs = 0, progress = full)
        assertTrue(withProgress.indexOf("\"progress\"") < withProgress.indexOf("\"rides\""))
        val keys = Json.parseToJsonElement(withProgress).jsonObject.keys.toList()
        assertEquals(listOf("app", "backupVersion", "exportedAt", "profile", "progress", "rides"), keys)
    }

    @Test
    fun oldBackupWithoutProgressStillImports() {
        val old = """{"app": "trailscape", "backupVersion": 1, "exportedAt": "2025-01-01T00:00:00.000Z", "profile": null, "rides": []}"""
        val data = parseBackupJson(old, CoreTextsDe)
        assertNull(data.progress)
        assertTrue(data.rides.isEmpty())
    }

    @Test
    fun brokenOrForeignProgressNeverBreaksTheImport() {
        for (value in listOf("5", "\"x\"", "[]", "null", "{\"earned\": 3}", "{\"keptLevel\": {}}")) {
            val raw = """{"app": "trailscape", "backupVersion": 1, "progress": $value, "rides": []}"""
            val data = parseBackupJson(raw, CoreTextsDe)
            assertTrue(data.rides.isEmpty(), value)
        }
        val raw = """{"app": "trailscape", "backupVersion": 1, "progress": {"earned": 3}, "rides": []}"""
        assertEquals(AchievementProgressData(), parseBackupJson(raw, CoreTextsDe).progress)
    }

    @Test
    fun unknownTopLevelKeysAreIgnoredLikeInOlderVersions() {
        // So liest eine aeltere App ein neues Backup: `progress` und weitere
        // Schluessel sind fuer sie unbekannt und werden uebergangen.
        val raw = buildBackupJson(listOf(ride()), null, exportedAtMs = 0, progress = full)
            .replace("\"progress\"", "\"garage\"")
        val data = parseBackupJson(raw, CoreTextsDe)
        assertNull(data.progress)
        assertEquals(1, data.rides.size)
        assertTrue(Json.parseToJsonElement(raw).jsonObject["garage"] is JsonObject)
    }
}
