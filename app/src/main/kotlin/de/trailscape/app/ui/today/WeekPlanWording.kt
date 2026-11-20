package de.trailscape.app.ui.today

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.PlannedRide
import de.trailscape.core.WeekForecastStatus
import de.trailscape.core.WeekPlan
import de.trailscape.core.WeekPlanReason
import de.trailscape.core.WeekPlanStatus
import de.trailscape.core.WeekPlanTightCause
import de.trailscape.core.i18n.CoreTexts
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * Die Saetze der Karte „Wochen-Fahrplan" ([WeekPlanCard]) — reine Funktionen
 * ueber dem `:core`-Ergebnis ([WeekPlan]), als [UiText] und damit ohne
 * Robolectric testbar. Gerechnet wird hier nichts.
 */

/** Was die Karte zum Wetter sagt. */
enum class WeekPlanWeatherUi {
    /** Schalter aus. */
    AUS,

    /** Schalter an, Vorhersage wird geholt. */
    LAEDT,

    /** Vorhersage liegt vor. */
    VORHANDEN,

    /** Schalter an, aber kein Ort bekannt. */
    KEIN_ORT,

    /** Schalter an, Anfrage gescheitert. */
    NICHT_ERREICHBAR,
}

/** Uebersetzt den Ladezustand aus `:core` in den Zustand der Karte. */
fun weekPlanWeatherUi(enabled: Boolean, status: WeekForecastStatus?): WeekPlanWeatherUi = when {
    !enabled -> WeekPlanWeatherUi.AUS
    status == null -> WeekPlanWeatherUi.LAEDT
    status == WeekForecastStatus.VORHANDEN -> WeekPlanWeatherUi.VORHANDEN
    status == WeekForecastStatus.KEIN_ORT -> WeekPlanWeatherUi.KEIN_ORT
    status == WeekForecastStatus.NICHT_ERREICHBAR -> WeekPlanWeatherUi.NICHT_ERREICHBAR
    // AUS trotz Schalter an: der Schalter wurde gerade umgelegt, das Laden laeuft gleich los.
    else -> WeekPlanWeatherUi.LAEDT
}

/** Kurzer Wochentag („Do"/„Thu") oder „Heute". */
fun weekPlanDayLabel(date: LocalDate, today: LocalDate, texts: CoreTexts): UiText =
    if (date == today) {
        UiText.Res(R.string.today_week_plan_today)
    } else {
        UiText.Plain(texts.format.weekdayShort(PLAN_DAY_CODES[date.dayOfWeek.value - 1]))
    }

/** Ausgeschriebener Wochentag („Donnerstag"/„Thursday") oder „Heute" — fuer TalkBack und das Routenlabel. */
fun weekPlanDayLong(date: LocalDate, today: LocalDate, texts: CoreTexts): UiText =
    if (date == today) {
        UiText.Res(R.string.today_week_plan_today)
    } else {
        UiText.Plain(texts.format.weekdayLong(PLAN_DAY_CODES[date.dayOfWeek.value - 1]))
    }

/** Kopfzeile einer Fahrt: „Do · 35 km". */
fun weekPlanRowTitle(ride: PlannedRide, today: LocalDate, texts: CoreTexts): UiText =
    UiText.Res(R.string.today_week_plan_row, listOf(weekPlanDayLabel(ride.date, today, texts), ride.km))

private fun reasonRes(reason: WeekPlanReason): Int = when (reason) {
    WeekPlanReason.TROCKEN -> R.string.today_week_plan_reason_dry
    WeekPlanReason.WENIG_WIND -> R.string.today_week_plan_reason_calm
    WeekPlanReason.UEBLICHER_FAHRTAG -> R.string.today_week_plan_reason_usual
    WeekPlanReason.FREIER_TAG -> R.string.today_week_plan_reason_free
    WeekPlanReason.REGEN_MOEGLICH -> R.string.today_week_plan_reason_rain
    WeekPlanReason.WINDIG -> R.string.today_week_plan_reason_windy
    WeekPlanReason.KALT -> R.string.today_week_plan_reason_cold
    WeekPlanReason.HEISS -> R.string.today_week_plan_reason_hot
}

/** Grund, mit Hoechsttemperatur, wenn eine Vorhersage vorliegt: „trocken, 14 °C". */
fun weekPlanReason(ride: PlannedRide): UiText {
    val reason = UiText.Res(reasonRes(ride.reason))
    val tMax = ride.forecast?.tempMaxC ?: return reason
    return UiText.Res(R.string.today_week_plan_reason_with_temp, listOf(reason, tMax.roundToInt()))
}

/** Ob die Grundzeile einer Fahrt eine Warnung ist (schlechtes Wetter, aber trotzdem im Plan). */
fun weekPlanReasonIsWarning(reason: WeekPlanReason): Boolean = when (reason) {
    WeekPlanReason.REGEN_MOEGLICH, WeekPlanReason.WINDIG, WeekPlanReason.KALT, WeekPlanReason.HEISS -> true
    else -> false
}

/** Satz unter dem Titel; bei „keine Tage" der ganze Inhalt der Karte. */
fun weekPlanSummary(plan: WeekPlan): UiText = when (plan.status) {
    WeekPlanStatus.KEINE_TAGE -> UiText.Res(R.string.today_week_plan_no_days, listOf(plan.shortfallKm))
    else -> UiText.Res(R.string.today_week_plan_summary, listOf(plan.remainingKm))
}

/**
 * Hinweis „knapp" unter den Zeilen, sonst `null`. Nennt den Grund
 * ([WeekPlanTightCause]): zu wenige Tage — oder Tage waeren frei, aber selbst
 * fuenf Fahrten bis zur Obergrenze reichen nicht.
 */
fun weekPlanTightNote(plan: WeekPlan): UiText? {
    if (plan.status != WeekPlanStatus.KNAPP) return null
    return when (plan.tightCause) {
        WeekPlanTightCause.ZU_WENIGE_TAGE ->
            UiText.Res(R.string.today_week_plan_tight_days, listOf(plan.maxRideKm, plan.shortfallKm))

        WeekPlanTightCause.OBERGRENZE, null ->
            UiText.Res(R.string.today_week_plan_tight, listOf(plan.maxRideKm, plan.shortfallKm, plan.rides.size))
    }
}

/**
 * „Jetzt" fuer den Wochenplan aus dem Kalendertag der Seite ([today]) und der
 * stuendlich getakteten Uhr ([hourNow]). Beide laufen mit eigenem Wecker und
 * koennen um Mitternacht kurz auseinanderliegen: Hinkt die Stunde hinterher,
 * gilt der Tagesanfang; ist sie schon im naechsten Tag, gilt 23:59 — der Tag
 * der Seite ist dann vorbei, fuer ihn wird nichts mehr vorgeschlagen.
 */
fun weekPlanNow(today: LocalDate, hourNow: LocalDateTime): LocalDateTime {
    val hourDate = hourNow.toLocalDate()
    return when {
        hourDate == today -> hourNow
        hourDate.isAfter(today) -> today.atTime(23, 59)
        else -> today.atStartOfDay()
    }
}

/**
 * Die kleine Wetterzeile: Quelle, wenn mit Wetter geplant wurde; sonst, warum
 * ohne. Liegt eine Vorhersage vor, deckt aber keinen der Tage ab, ist auch
 * das „ohne Wetter".
 */
fun weekPlanWeatherHint(weather: WeekPlanWeatherUi, plan: WeekPlan): UiText = when (weather) {
    WeekPlanWeatherUi.AUS -> UiText.Res(R.string.today_week_plan_weather_off)
    WeekPlanWeatherUi.LAEDT -> UiText.Res(R.string.today_week_plan_weather_loading)
    WeekPlanWeatherUi.KEIN_ORT -> UiText.Res(R.string.today_week_plan_weather_no_location)
    WeekPlanWeatherUi.NICHT_ERREICHBAR -> UiText.Res(R.string.today_week_plan_weather_unavailable)
    WeekPlanWeatherUi.VORHANDEN -> if (plan.withWeather) {
        UiText.Res(R.string.today_week_plan_weather_source)
    } else {
        UiText.Res(R.string.today_week_plan_weather_off)
    }
}
