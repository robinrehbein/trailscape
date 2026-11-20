package de.trailscape.app.ui.today

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.DayForecast
import de.trailscape.core.PlannedRide
import de.trailscape.core.WeekForecastStatus
import de.trailscape.core.WeekPlan
import de.trailscape.core.WeekPlanReason
import de.trailscape.core.WeekPlanStatus
import de.trailscape.core.WeekPlanTightCause
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wortlaut der Karte „Wochen-Fahrplan" (`WeekPlanWording.kt`), deutsch und englisch. */
class WeekPlanWordingTest {
    private val wednesday = LocalDate.of(2026, 11, 18)
    private val thursday = wednesday.plusDays(1)

    private fun plan(status: WeekPlanStatus, withWeather: Boolean = false) =
        WeekPlan(emptyList(), status, remainingKm = 70, shortfallKm = 20, maxRideKm = 60, withWeather = withWeather)

    @Test
    fun `Zeile mit Wochentag und km, heute als Heute`() {
        val ride = PlannedRide(thursday, 35, WeekPlanReason.TROCKEN)
        assertEquals("Do · 35 km", TodayStrings.de(weekPlanRowTitle(ride, wednesday, CoreTextsDe)))
        assertEquals("Thu · 35 km", TodayStrings.en(weekPlanRowTitle(ride, wednesday, CoreTextsEn)))
        assertEquals("Heute · 35 km", TodayStrings.de(weekPlanRowTitle(ride, thursday, CoreTextsDe)))
    }

    @Test
    fun `Grund mit Temperatur nur bei Vorhersage`() {
        val dry = PlannedRide(thursday, 35, WeekPlanReason.TROCKEN, DayForecast(thursday, tempMaxC = 13.6))
        assertEquals("trocken, 14 °C", TodayStrings.de(weekPlanReason(dry)))
        assertEquals("dry, 14 °C", TodayStrings.en(weekPlanReason(dry)))
        val usual = PlannedRide(thursday, 35, WeekPlanReason.UEBLICHER_FAHRTAG)
        assertEquals("dein üblicher Fahrtag", TodayStrings.de(weekPlanReason(usual)))
    }

    @Test
    fun `Warnfarbe nur fuer schlechtes Wetter`() {
        assertTrue(weekPlanReasonIsWarning(WeekPlanReason.REGEN_MOEGLICH))
        assertEquals(false, weekPlanReasonIsWarning(WeekPlanReason.TROCKEN))
    }

    @Test
    fun `knapp und keine Tage`() {
        assertNull(weekPlanTightNote(plan(WeekPlanStatus.MACHBAR)))
        assertTrue(TodayStrings.de(weekPlanTightNote(plan(WeekPlanStatus.KNAPP))!!).contains("noch 20 km"))
        assertTrue(TodayStrings.de(weekPlanSummary(plan(WeekPlanStatus.KEINE_TAGE))).contains("20 km"))
        assertEquals(
            "Noch 70 km bis zum Wochenziel. So passt es:",
            TodayStrings.de(weekPlanSummary(plan(WeekPlanStatus.MACHBAR))),
        )
    }

    @Test
    fun `knapp nennt den Grund`() {
        val days = plan(WeekPlanStatus.KNAPP).copy(tightCause = WeekPlanTightCause.ZU_WENIGE_TAGE)
        assertEquals(
            "Knapp: Diese Woche bleiben zu wenige Tage, und mehr als 60 km pro Fahrt wären deutlich mehr als " +
                "deine längste Fahrt der letzten Wochen. Mit diesem Plan fehlen am Ende noch 20 km.",
            TodayStrings.de(weekPlanTightNote(days)!!),
        )
        assertTrue(TodayStrings.en(weekPlanTightNote(days)!!).startsWith("Tight: too few days are left this week"))

        val rides = (0 until 5).map { PlannedRide(wednesday.plusDays(it.toLong()), 60, WeekPlanReason.FREIER_TAG) }
        val cap = plan(WeekPlanStatus.KNAPP).copy(rides = rides, tightCause = WeekPlanTightCause.OBERGRENZE)
        assertEquals(
            "Knapp: Selbst mit 5 Fahrten bräuchte jede mehr als 60 km – deutlich mehr als deine längste Fahrt " +
                "der letzten Wochen. Mit diesem Plan fehlen am Ende noch 20 km.",
            TodayStrings.de(weekPlanTightNote(cap)!!),
        )
        assertTrue(TodayStrings.en(weekPlanTightNote(cap)!!).startsWith("Tight: even with 5 rides, each would need more than 60 km"))
    }

    @Test
    fun `jetzt fuer den Plan gleicht Tag und Stunde um Mitternacht ab`() {
        val hour = wednesday.atTime(17, 0)
        assertEquals(hour, weekPlanNow(wednesday, hour))
        // Stunde hinkt hinterher: Tagesanfang des neuen Tags.
        assertEquals(thursday.atStartOfDay(), weekPlanNow(thursday, wednesday.atTime(23, 0)))
        // Stunde ist schon im naechsten Tag: der Tag der Seite ist vorbei.
        assertEquals(wednesday.atTime(23, 59), weekPlanNow(wednesday, thursday.atTime(0, 0)))
    }

    @Test
    fun `Einwilligung nennt Rueckfall auf die Tour und den Zeitpunkt`() {
        val de = TodayStrings.de(UiText.Res(R.string.today_week_plan_weather_switch_hint))
        assertTrue(de.contains("Start deiner letzten Tour"))
        assertTrue(de.contains("beim Öffnen von „Heute“"))
        assertTrue(de.contains("3 Stunden"))
        val en = TodayStrings.en(UiText.Res(R.string.today_week_plan_weather_switch_hint))
        assertTrue(en.contains("start of your last ride"))
        assertTrue(en.contains("When you open Today"))
    }

    @Test
    fun `Wetterhinweis je Zustand`() {
        assertEquals(WeekPlanWeatherUi.AUS, weekPlanWeatherUi(false, WeekForecastStatus.VORHANDEN))
        assertEquals(WeekPlanWeatherUi.LAEDT, weekPlanWeatherUi(true, null))
        assertEquals(WeekPlanWeatherUi.KEIN_ORT, weekPlanWeatherUi(true, WeekForecastStatus.KEIN_ORT))
        assertEquals(
            "Wetter: Open-Meteo",
            TodayStrings.de(weekPlanWeatherHint(WeekPlanWeatherUi.VORHANDEN, plan(WeekPlanStatus.MACHBAR, true))),
        )
        // Vorhersage da, deckt aber keinen Tag ab: ohne Wetter geplant.
        assertEquals(
            "Ohne Wetter geplant.",
            TodayStrings.de(weekPlanWeatherHint(WeekPlanWeatherUi.VORHANDEN, plan(WeekPlanStatus.MACHBAR))),
        )
        assertEquals(
            "Planned without weather: forecast not reachable right now.",
            TodayStrings.en(weekPlanWeatherHint(WeekPlanWeatherUi.NICHT_ERREICHBAR, plan(WeekPlanStatus.MACHBAR))),
        )
    }
}
