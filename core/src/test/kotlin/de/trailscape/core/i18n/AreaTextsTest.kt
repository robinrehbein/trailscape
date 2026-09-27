package de.trailscape.core.i18n

import de.trailscape.core.AscentPreference
import de.trailscape.core.BulkImportError
import de.trailscape.core.BulkImportResult
import de.trailscape.core.DailyRecommendation
import de.trailscape.core.DailyRecommendationKind
import de.trailscape.core.FitnessDirection
import de.trailscape.core.FitnessPoint
import de.trailscape.core.FormatException
import de.trailscape.core.Goal
import de.trailscape.core.HealthAvailability
import de.trailscape.core.HealthConnection
import de.trailscape.core.HealthSyncReport
import de.trailscape.core.LoadCalibration
import de.trailscape.core.Confidence
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrainingSession
import de.trailscape.core.WindConditions
import de.trailscape.core.bulkImportFailureText
import de.trailscape.core.bulkImportMessage
import de.trailscape.core.decideTodayRoute
import de.trailscape.core.TrainingProfile
import de.trailscape.core.describeFitnessTrend
import de.trailscape.core.estimateVo2MaxFromHrRatio
import de.trailscape.core.parseBackupJson
import de.trailscape.core.parseGpx
import de.trailscape.core.predictGoalFinish
import de.trailscape.core.sentence
import de.trailscape.core.suggestSegmentName
import de.trailscape.core.summaryLine
import de.trailscape.core.parseSegmentTile
import de.trailscape.core.weeklyLoadCapText
import de.trailscape.core.weeklyLoadTarget
import de.trailscape.core.windLine
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Je Bereich ein paar repraesentative Faelle ueber die echten Funktionen, in
 * beiden Sprachen. Die Wortlaute der deutschen Faelle sind die bisherigen —
 * die englischen legen die Uebersetzung fest.
 */
class AreaTextsTest {

    private val profile = TrainingProfile(ageYears = 40)

    // ------------------------------------------------------------ Last

    @Test
    fun `Wochenziel-Deckel werden erst bei der Anzeige zum Satz`() {
        val target = weeklyLoadTarget(ctl = 60.0, targetRamp = 5.0, recentWeeklyMean = 300.0, weeklyHours = 4.5)
        assertEquals(2, target.caps.size)
        assertEquals("Capped at your time budget of 4.5 h per week.", weeklyLoadCapText(target.caps.last(), target, CoreTextsEn))
        assertEquals("Begrenzt auf dein Zeitbudget von 4,5 h pro Woche.", weeklyLoadCapText(target.caps.last(), target, CoreTextsDe))
        assertEquals("Capped at 130% of your last four weeks.", weeklyLoadCapText(target.caps.first(), target, CoreTextsEn))
    }

    @Test
    fun `Kalibrierung und VO2max mit Dezimaltrennzeichen je Sprache`() {
        val calibration = LoadCalibration(alpha = 1.0, sampleCount = 4, clamped = true, confidence = Confidence.LOW, rawAlpha = 2.5)
        assertTrue(calibration.note(CoreTextsDe)!!.contains("Faktor 2,50"))
        assertTrue(calibration.note(CoreTextsEn)!!.contains("factor of 2.50"))
        val vo2 = estimateVo2MaxFromHrRatio(profile, texts = CoreTextsEn)
        assertTrue(Regex("""Estimated VO2max: \d+–\d+ ml/kg/min""").matches(vo2.text(CoreTextsEn)), vo2.text(CoreTextsEn))
    }

    // ------------------------------------------------------------ Ziel

    @Test
    fun `Prognose ohne passende Touren und Fitness-Trend`() {
        val goal = Goal(name = "X", distanceKm = 100.0, date = 0L)
        assertEquals(
            "Ride 2–3 longer rides (from about 40 km) to get a prediction.",
            predictGoalFinish(goal, emptyList(), now = 1L, texts = CoreTextsEn).missing,
        )
        assertEquals(
            "Für eine Prognose braucht das Ziel eine Distanz.",
            predictGoalFinish(goal.copy(distanceKm = 0.0), emptyList(), texts = CoreTextsDe).missing,
        )
        val start = LocalDateTime.of(2026, 1, 1, 0, 0)
        val rising = (0 until 30).map { FitnessPoint(start.plusDays(it.toLong()), 0.0, 20.0 + it, 0.0, 0.0, null, null) }
        val trend = assertNotNull(describeFitnessTrend(rising))
        assertEquals(FitnessDirection.STEIGT, trend.direction)
        assertEquals("Fitness steigt seit ${trend.weeks} Wochen", trend.sentence(CoreTextsDe))
        assertEquals("Fitness rising for ${trend.weeks} weeks", trend.sentence(CoreTextsEn))
    }

    // ------------------------------------------------------------ Heute

    @Test
    fun `Tagesentscheidung am Ruhetag mit Planeinheit`() {
        val session = TrainingSession(day = "Sa", title = "Lange Tour", description = "x", targetKm = 80)
        val rest = DailyRecommendation(DailyRecommendationKind.RUHETAG, "t", "d", emptyList())
        val en = decideTodayRoute(rest, session, profile, emptyList(), texts = CoreTextsEn)
        assertEquals(
            "Your plan has “Lange Tour” over 80 km today – skipped because your recovery signals point " +
                "to a break. Better move the session by a day.",
            en.note,
        )
        val de = decideTodayRoute(rest, session, profile, emptyList(), texts = CoreTextsDe)
        assertEquals(
            "Im Plan steht heute „Lange Tour“ über 80 km – ausgesetzt, weil deine Erholungssignale für " +
                "eine Pause sprechen. Schieb die Einheit lieber um einen Tag.",
            de.note,
        )
    }

    @Test
    fun `Abweichungssatz nennt beide Zahlen`() {
        val t = CoreTextsEn.today
        assertEquals(
            "Plan: 90 km hilly – reduced to 55 km flat today because your fatigue is high right now.",
            t.downgradeNote(90, AscentPreference.BERGIG, 55, AscentPreference.FLACH, true, true, t.downgradeReason(DailyRecommendationKind.RECOVERY)),
        )
        assertEquals("Ruhetag – locker rollen", CoreTextsDe.today.restDayRideLabel())
        assertEquals("No ride for 5 days", t.reminderNudgeTitle(5))
    }

    // ------------------------------------------------------------ Routing

    @Test
    fun `Windzeile, Kachelnamen und Segmentnamen`() {
        val wind = WindConditions(speedKmh = 18.0, fromDeg = 270.0, gustsKmh = 35.0)
        assertEquals(
            "Wind 18 km/h aus West, Böen bis 35 km/h – Rückenwind auf dem Heimweg",
            windLine(wind, 0.5, CoreTextsDe),
        )
        assertEquals(
            "Wind 18 km/h from the west, gusts up to 35 km/h – tailwind on the way home",
            windLine(wind, 0.5, CoreTextsEn),
        )
        val tile = assertNotNull(parseSegmentTile("E10_N50"))
        assertEquals("Berlin, Dresden, Prague and more", tile.title(CoreTextsEn))
        assertEquals("50°–55° N, 10°–15° E", tile.boundsLabel(CoreTextsEn))
        assertEquals("50°–55° N, 10°–15° O", tile.boundsLabel(CoreTextsDe))
        assertEquals("Anstieg 4,2 km / 180 Hm", suggestSegmentName(4200.0, 180.0, CoreTextsDe))
        assertEquals("Climb 4.2 km / 180 m", suggestSegmentName(4200.0, 180.0, CoreTextsEn))
        assertEquals("Climb 800 m / 45 m", suggestSegmentName(803.0, 45.0, CoreTextsEn))
        assertEquals("approx. 62% unpaved", CoreTextsEn.routing.unpavedShare(62))
    }

    // ------------------------------------------------------------ Gesundheit

    @Test
    fun `Health-Connect-Zustand und Import-Zusammenfassung`() {
        val connection = HealthConnection(HealthAvailability.VERFUEGBAR, hasPermissions = true)
        assertEquals("Health Connect ist verbunden.", connection.message(CoreTextsDe))
        assertEquals("Health Connect is connected.", connection.message(CoreTextsEn))
        val at = LocalDateTime.of(2026, 8, 8, 0, 0)
        val empty = HealthSyncReport(
            from = at,
            to = at,
            workoutsFound = 0,
            imported = emptyList(),
            mergedRides = emptyList(),
            duplicatesSkipped = 0,
            routesMissing = 0,
        )
        assertEquals("No new rides", empty.summaryLine(CoreTextsEn))
        assertEquals("Keine neuen Touren", empty.summaryLine(CoreTextsDe))
        assertEquals("Ride 8 Aug 2026 (watch) (indoor)", CoreTextsEn.health.importedRideName(8, 8, 2026, indoor = true))
        assertEquals("Tour 08.08.2026 (Watch)", CoreTextsDe.health.importedRideName(8, 8, 2026, indoor = false))
    }

    // ------------------------------------------------------------ Dateien

    @Test
    fun `Importmeldungen mit Einzahl und Mehrzahl`() {
        val failed = BulkImportResult(
            rides = emptyList(),
            duplicates = emptyList(),
            errors = listOf(BulkImportError("a.gpx", "kaputt"), BulkImportError("b.gpx", "kaputt")),
        )
        assertEquals("2 unreadable", bulkImportMessage(failed, CoreTextsEn))
        assertEquals(
            "None of the 2 files could be imported. Trailscape reads GPX and FIT files, " +
                "including .gz-compressed ones. (kaputt)",
            bulkImportFailureText(failed, CoreTextsEn),
        )
        assertTrue(bulkImportFailureText(failed, CoreTextsDe)!!.startsWith("Keine der 2 Dateien"))
    }

    @Test
    fun `Parse-Fehler in der Sprache der Texte`() {
        val gpx = assertFailsWith<FormatException> { parseGpx("<kein xml", CoreTextsEn) }
        assertEquals("The GPX file contains invalid XML.", gpx.message)
        val backup = assertFailsWith<FormatException> { parseBackupJson("""{"app":"trailscape"}""", CoreTextsEn) }
        assertEquals("The backup doesn’t contain a valid version.", backup.message)
        val backupDe = assertFailsWith<FormatException> { parseBackupJson("{", CoreTextsDe) }
        assertEquals("Die Datei enthält kein gültiges JSON und kann nicht importiert werden.", backupDe.message)
    }

    // ------------------------------------------------------------ Sync

    @Test
    fun `Sync-Meldungen`() {
        assertEquals("Sync failed (HTTP 500).", CoreTextsEn.sync.syncFailed(500))
        assertEquals("Uploading the ride “Alb” failed (HTTP 413).", CoreTextsEn.sync.uploadFailed("Alb", 413))
        assertEquals("Sync ist nicht konfiguriert.", CoreTextsDe.sync.notConfigured())
    }

    @Test
    fun `Intensitaet und Profil-Labels`() {
        assertEquals("intense", CoreTextsEn.training.sessionIntensity(SessionIntensity.HART))
        assertEquals("Rolling", CoreTextsEn.today.ascentPreference(AscentPreference.MODERAT))
    }
}
