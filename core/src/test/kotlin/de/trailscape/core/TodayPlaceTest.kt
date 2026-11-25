package de.trailscape.core

import de.trailscape.core.i18n.SessionTextKey
import de.trailscape.core.indoor.DaySession
import de.trailscape.core.indoor.DaySessionKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer `SunTimes.kt` und `TodayPlace.kt`: Sonnenzeiten gegen bekannte
 * Kalenderwerte und die Regeln des Drinnen/Draussen-Vorschlags (Tabelle im
 * Dateikopf von `TodayPlace.kt`).
 */
class TodayPlaceTest {

    private val berlin = TrackPoint(lat = 52.5, lon = 13.4)
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 11, 25) // Sonnenuntergang Berlin ≈ 16:02 MEZ

    private fun at(hour: Int, minute: Int = 0): ZonedDateTime = day.atTime(hour, minute).atZone(zone)

    private fun session(
        intensity: SessionIntensity = SessionIntensity.GRUNDLAGE,
        durationMin: Int? = 60,
        textKey: SessionTextKey? = null,
    ) = TrainingSession(
        day = "Mi",
        title = "Einheit",
        description = "",
        targetKm = 30,
        intensity = intensity,
        durationMin = durationMin,
        textKey = textKey,
    )

    private val target = RouteTarget(
        distanceKm = 30.0,
        ascentPreference = AscentPreference.MODERAT,
        durationH = 1.2,
        speedKmh = 25.0,
        intensity = SessionIntensity.GRUNDLAGE,
        label = "Runde",
        source = RouteTargetSource.PLAN,
    )

    private fun today(s: TrainingSession?) = TodayRoute(target, s, s?.targetKm, 1.0, false, note = null)

    private fun suggest(
        s: TrainingSession,
        now: ZonedDateTime,
        location: TrackPoint? = berlin,
        forecast: DayForecast? = null,
        kind: DaySessionKind = DaySessionKind.PLANNED,
    ): TodayPlace? = suggestTodayPlace(DaySession(kind, s, s), today(s), now, location, forecast)

    private fun utc(date: LocalDate, h: Int, m: Int): Instant = LocalDateTime.of(date, java.time.LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

    private fun assertNear(expected: Instant, actual: Instant?, toleranceMin: Long = 3) {
        assertNotNull(actual)
        val diff = abs(Duration.between(expected, actual).toMinutes())
        assertTrue(diff <= toleranceMin, "erwartet $expected, bekommen $actual (Abstand $diff min)")
    }

    // --- Sonnenzeiten -------------------------------------------------------

    @Test
    fun `Sonnenzeiten in Berlin stimmen mit dem Kalender ueberein`() {
        // Kalenderwerte Berlin: 21.6. 04:43 / 21:33 MESZ, 21.12. 08:15 / 15:54 MEZ.
        val summer = sunTimes(LocalDate.of(2026, 6, 21), 52.52, 13.405)
        assertNear(utc(LocalDate.of(2026, 6, 21), 2, 43), summer.sunrise)
        assertNear(utc(LocalDate.of(2026, 6, 21), 19, 33), summer.sunset)
        val winter = sunTimes(LocalDate.of(2026, 12, 21), 52.52, 13.405)
        assertNear(utc(LocalDate.of(2026, 12, 21), 7, 15), winter.sunrise)
        assertNear(utc(LocalDate.of(2026, 12, 21), 14, 54), winter.sunset)
    }

    @Test
    fun `Polartag und Polarnacht werden erkannt`() {
        val tromsoeSommer = sunTimes(LocalDate.of(2026, 6, 21), 69.65, 18.96)
        assertTrue(tromsoeSommer.polarDay)
        assertNull(tromsoeSommer.sunset)
        val tromsoeWinter = sunTimes(LocalDate.of(2026, 12, 21), 69.65, 18.96)
        assertTrue(tromsoeWinter.polarNight)
        assertEquals(PlaceReason.DARK, daylightReason(tromsoeWinter, Instant.now(), 60))
        assertNull(daylightReason(tromsoeSommer, Instant.now(), 60))
    }

    // --- Tageslicht ------------------------------------------------------------

    @Test
    fun `Grundlage mittags bei Tageslicht ist draussen`() {
        val p = suggest(session(durationMin = 90), at(11))!!
        assertEquals(RidePlace.OUTDOOR, p.suggested)
        assertEquals(listOf(PlaceReason.DAYLIGHT), p.reasons)
        assertNotNull(p.sunset)
        assertEquals(target, p.outdoorTarget)
    }

    @Test
    fun `am Abend nach Sonnenuntergang ist jede Einheit drinnen, auch die lange`() {
        val kurz = suggest(session(durationMin = 60), at(18))!!
        assertEquals(RidePlace.INDOOR, kurz.suggested)
        assertEquals(listOf(PlaceReason.DARK), kurz.reasons)
        val lang = suggest(session(durationMin = 150), at(18))!!
        assertEquals(RidePlace.INDOOR, lang.suggested)
        assertEquals(listOf(PlaceReason.LONG_ENDURANCE), lang.counter)
    }

    @Test
    fun `vor Sonnenaufgang ist es dunkel`() {
        val p = suggest(session(), at(6, 30))!!
        assertEquals(RidePlace.INDOOR, p.suggested)
        assertEquals(PlaceReason.DARK, p.reasons.first())
    }

    @Test
    fun `reicht das Licht nicht fuer die Einheit, entscheidet der Anteil im Dunkeln`() {
        // 15:00 + 15 min Vorlauf, 90 min → Ende 16:45, Sonnenuntergang ≈ 16:02: ≈ 48 % im Dunkeln.
        val p = suggest(session(durationMin = 90), at(15))!!
        assertEquals(RidePlace.INDOOR, p.suggested)
        assertEquals(listOf(PlaceReason.DARK), p.reasons)
        // 14:30 + 15 min, 90 min → Ende 16:15: ≈ 14 % → nur Daemmerung, bleibt draussen.
        val q = suggest(session(durationMin = 90), at(14, 30))!!
        assertEquals(RidePlace.OUTDOOR, q.suggested)
        assertEquals(listOf(PlaceReason.DUSK), q.counter)
    }

    @Test
    fun `ohne Ort kein Tageslichtkriterium`() {
        val p = suggest(session(), at(18), location = null)!!
        assertEquals(RidePlace.OUTDOOR, p.suggested)
        assertTrue(p.reasons.isEmpty() && p.counter.isEmpty())
        assertNull(p.sunset)
    }

    // --- Art der Einheit ----------------------------------------------------

    @Test
    fun `Intervalle bei Tageslicht sind drinnen, lange Grundlage draussen`() {
        val intervalle = suggest(session(SessionIntensity.HART, 52, SessionTextKey.INTERVALS), at(10))!!
        assertEquals(RidePlace.INDOOR, intervalle.suggested)
        assertEquals(listOf(PlaceReason.INTERVALS), intervalle.reasons)
        assertEquals(listOf(PlaceReason.DAYLIGHT), intervalle.counter)
        assertEquals(52, intervalle.minutes)

        val lang = suggest(session(durationMin = 150), at(9))!!
        assertEquals(RidePlace.OUTDOOR, lang.suggested)
        assertEquals(listOf(PlaceReason.LONG_ENDURANCE, PlaceReason.DAYLIGHT), lang.reasons)
    }

    @Test
    fun `Qualitaetseinheit und Test zaehlen wie Intervalle`() {
        assertTrue(isStructuredSession(session(textKey = SessionTextKey.WORKOUT)))
        assertTrue(isStructuredSession(session(textKey = SessionTextKey.FTP_TEST)))
        assertTrue(isStructuredSession(session(SessionIntensity.HART)))
        assertFalse(isStructuredSession(session(SessionIntensity.LOCKER)))
    }

    @Test
    fun `Ruhetag und Zieltag haben keinen Vorschlag`() {
        assertNull(suggest(session(), at(10), kind = DaySessionKind.REST))
        assertNull(suggest(session(), at(10), kind = DaySessionKind.EVENT))
    }

    // --- Wetter -----------------------------------------------------------------

    @Test
    fun `Regen, Sturm und Frost zaehlen nur mit einer Vorhersage fuer heute`() {
        val regen = DayForecast(day, precipProbabilityPct = 85, precipMm = 4.0)
        val p = suggest(session(), at(11), forecast = regen)!!
        assertEquals(RidePlace.INDOOR, p.suggested) // +3 −1
        assertEquals(listOf(PlaceReason.RAIN), p.reasons)

        // Vorhersage von gestern zaehlt nicht.
        val gestern = suggest(session(), at(11), forecast = regen.copy(date = day.minusDays(1)))!!
        assertEquals(RidePlace.OUTDOOR, gestern.suggested)

        assertEquals(listOf(PlaceReason.STORM), weatherReasons(DayForecast(day, windMaxKmh = 45.0)))
        assertEquals(listOf(PlaceReason.FROST), weatherReasons(DayForecast(day, tempMaxC = 0.5)))
        assertEquals(listOf(PlaceReason.RAIN), weatherReasons(DayForecast(day, precipMm = 6.0)))
        // Hohe Wahrscheinlichkeit, aber kaum Menge: kein Regengrund.
        assertTrue(weatherReasons(DayForecast(day, precipProbabilityPct = 80, precipMm = 0.3)).isEmpty())
        assertTrue(weatherReasons(DayForecast(day)).isEmpty())
    }

    // --- Wahl des Nutzers ---------------------------------------------------------

    @Test
    fun `Umdrehen gilt nur fuer den Tag, an dem gewaehlt wurde`() {
        val p = suggest(session(), at(11))!!
        val flipped = applyPlaceChoice(p, PlaceChoice(day, RidePlace.INDOOR), day)
        assertEquals(RidePlace.INDOOR, flipped.place)
        assertEquals(RidePlace.OUTDOOR, flipped.suggested)
        assertTrue(flipped.chosen)
        assertEquals(p, applyPlaceChoice(p, PlaceChoice(day.minusDays(1), RidePlace.INDOOR), day))
        assertFalse(applyPlaceChoice(p, PlaceChoice(day, RidePlace.OUTDOOR), day).chosen)
        assertEquals(RidePlace.INDOOR, RidePlace.OUTDOOR.flipped())
    }

    @Test
    fun `Wahl wird tolerant gelesen und beim Zurueckdrehen entfernt`() {
        val store = MemoryStore()
        writePlaceChoice(store, PlaceChoice(day, RidePlace.INDOOR), suggested = RidePlace.OUTDOOR)
        assertEquals(PlaceChoice(day, RidePlace.INDOOR), readPlaceChoice(store))
        writePlaceChoice(store, PlaceChoice(day, RidePlace.OUTDOOR), suggested = RidePlace.OUTDOOR)
        assertNull(store.getString(TODAY_PLACE_CHOICE_KEY))
        assertNull(PlaceChoice.decode("kaputt"))
        assertNull(PlaceChoice.decode("2026-11-25|IRGENDWO"))
        assertNull(PlaceChoice.decode("2026-13-40|INDOOR"))
    }

    @Test
    fun `grober Ort wird gerundet, unbrauchbare Koordinaten fallen weg`() {
        assertEquals(TrackPoint(lat = 52.5, lon = 13.4), coarsePlacePoint(TrackPoint(lat = 52.5213, lon = 13.4049, time = 5L)))
        assertNull(coarsePlacePoint(TrackPoint(lat = 0.0, lon = 0.0)))
        assertNull(coarsePlacePoint(TrackPoint(lat = Double.NaN, lon = 1.0)))
        assertNull(coarsePlacePoint(null))
    }

    // --- Fuer die Uhr ----------------------------------------------------------------

    @Test
    fun `Vorschlag fuer die Uhr traegt Ort, Dauer, Wahl und Erledigt`() {
        val p = applyPlaceChoice(suggest(session(durationMin = 75), at(11))!!, PlaceChoice(day, RidePlace.INDOOR), day)
        val v = heuteVorschlagVon(p, day, done = false)!!
        assertEquals(HeuteVorschlag(day.toEpochDay(), drinnen = true, minuten = 75, erledigt = false, gewaehlt = true), v)
        // Erledigt draussen: Der Ort der Fahrt gewinnt.
        val erledigt = heuteVorschlagVon(p, day, done = true, doneIndoor = false)!!
        assertFalse(erledigt.drinnen)
        assertTrue(erledigt.erledigt)
        assertNull(heuteVorschlagVon(null, day, done = false))

        val store = MemoryStore()
        assertTrue(writeShownHeute(store, v))
        assertFalse(writeShownHeute(store, v))
        assertEquals(v, readShownHeute(store, day))
        assertNull(readShownHeute(store, day.plusDays(1)))
        assertTrue(writeShownHeute(store, null))
        assertNull(readShownHeute(store, day))
    }

    private class MemoryStore : KeyValueStore {
        private val map = HashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun setString(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }
}
