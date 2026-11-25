package de.trailscape.core

import de.trailscape.core.i18n.SessionTextKey
import de.trailscape.core.indoor.DaySession
import de.trailscape.core.indoor.DaySessionKind
import de.trailscape.core.indoor.sessionDurationMinutes
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.round

/**
 * # Draussen oder drinnen? Der begruendete Vorschlag zur Einheit des Tages
 *
 * Die Einheit des Tages ([DaySession], aus [decideTodayRoute] ueber
 * `daySessionFor`) kann draussen als Runde ([TodayRoute.target]) oder drinnen auf
 * der Rolle (Indoor-Strecke zur Einheit, `rankDayRoutes`) gefahren werden.
 * [suggestTodayPlace] entscheidet, welcher der beiden Wege oben steht, und sagt
 * warum. Der Nutzer dreht den Vorschlag mit einem Tipp um ([applyPlaceChoice]).
 *
 * ## Woher die Kriterien kommen — nur Daten, die schon auf dem Geraet sind
 *  * **Tageslicht**: Sonnenuntergang aus Datum und grobem Ort ([sunTimes]). Der
 *    Ort ist der Start der juengsten Tour draussen, auf eine Nachkommastelle
 *    gerundet ([coarsePlacePoint]) — er verlaesst das Geraet nicht.
 *  * **Art und Dauer der Einheit**: aus der Planeinheit selbst.
 *  * **Wetter**: nur, wenn schon eine Tagesvorhersage auf dem Geraet liegt (der
 *    Schalter „Wetter für den Wochenplan", `WeekPlanWeather.kt`). Fuer diesen
 *    Vorschlag wird **nichts** zusaetzlich abgefragt; ohne Vorhersage entscheidet
 *    der Rest.
 *
 * ## Punkte (positiv = drinnen, Regeln in docs/trainingswissenschaft.md 17)
 * | Grund | Punkte | Bedingung |
 * |---|---|---|
 * | [PlaceReason.DARK] | +5 | jetzt schon dunkel (vor Sonnenauf- oder nach -untergang), Polarnacht, oder ≥ [DARK_SHARE_MIN] der Einheit faellt nach Sonnenuntergang |
 * | [PlaceReason.DUSK] | +1 | ein kleiner Teil (< [DARK_SHARE_MIN]) faellt nach Sonnenuntergang |
 * | [PlaceReason.DAYLIGHT] | −1 | die Einheit endet samt [PLACE_START_BUFFER_MIN] vor Sonnenuntergang |
 * | [PlaceReason.INTERVALS] | +3 | Intervalle, Qualitaetseinheit, Test oder harte Einheit |
 * | [PlaceReason.LONG_ENDURANCE] | −3 | ruhige Einheit (locker/Grundlage) ab [LONG_ENDURANCE_MIN] min |
 * | [PlaceReason.RAIN] | +3 | Regenwahrscheinlichkeit ≥ [RAIN_PROBABILITY_PCT] % (und, falls bekannt, ≥ 1 mm) oder ≥ [RAIN_HEAVY_MM] mm |
 * | [PlaceReason.STORM] | +2 | Mittelwind ≥ [STORM_WIND_KMH] km/h (Beaufort 6) |
 * | [PlaceReason.FROST] | +2 | Hoechstwert ≤ [FROST_MAX_C] °C (Glaette) |
 *
 * Ab [INDOOR_SCORE_MIN] Punkten heisst es drinnen, sonst draussen — im Zweifel
 * draussen, weil die Runde draussen die gewohnte Einheit ist. Damit gilt:
 * Intervalle bei Tageslicht und trocken → drinnen (+3 −1); lange Grundlage bei
 * Tageslicht → draussen; jede Einheit nach Einbruch der Dunkelheit → drinnen,
 * auch die lange (+5 −3).
 *
 * Reine Rechnung ohne Speicher und ohne Texte; die App formuliert aus
 * [PlaceReason] und den Zahlen.
 */

/** Wo die Einheit heute gefahren wird. */
enum class RidePlace {
    /** Draussen: die Runde aus [TodayRoute.target]. */
    OUTDOOR,

    /** Drinnen auf der Rolle: die Indoor-Strecke zur Einheit. */
    INDOOR,
}

/** Ein Grund fuer oder gegen drinnen, mit festen Punkten (siehe Tabelle im Dateikopf). */
enum class PlaceReason(val points: Int) {
    DARK(5),
    DUSK(1),
    DAYLIGHT(-1),
    INTERVALS(3),
    LONG_ENDURANCE(-3),
    RAIN(3),
    STORM(2),
    FROST(2),
    ;

    /** Spricht dieser Grund fuer [place]? */
    fun supports(place: RidePlace): Boolean = if (place == RidePlace.INDOOR) points > 0 else points < 0
}

/** Ab so vielen Punkten lautet der Vorschlag drinnen. */
const val INDOOR_SCORE_MIN: Int = 2

/** Ab diesem Anteil der Einheit nach Sonnenuntergang zaehlt sie als Fahrt im Dunkeln. */
const val DARK_SHARE_MIN: Double = 0.25

/** Vorlauf bis zum Losfahren (Umziehen, Rad), in Minuten — er zaehlt zur Tageslichtrechnung. */
const val PLACE_START_BUFFER_MIN: Int = 15

/** Ab dieser Dauer gilt eine ruhige Einheit als lange Grundlage, in Minuten. */
const val LONG_ENDURANCE_MIN: Int = 120

/** Regenwahrscheinlichkeit, ab der Regen zaehlt, in Prozent. */
const val RAIN_PROBABILITY_PCT: Int = 70

/** Niederschlagssumme, ab der Regen auch ohne hohe Wahrscheinlichkeit zaehlt, in mm. */
const val RAIN_HEAVY_MM: Double = 5.0

/** Mittelwind, ab dem Sturm zaehlt, in km/h (Beaufort 6 beginnt bei 39 km/h). */
const val STORM_WIND_KMH: Double = 39.0

/** Hoechstwert, bis zu dem Glaette zaehlt, in °C. */
const val FROST_MAX_C: Double = 1.0

/**
 * Der Vorschlag.
 *
 * @property suggested was die Regeln vorschlagen.
 * @property place was gilt — [suggested], ausser der Nutzer hat umgedreht ([chosen]).
 * @property chosen `true`, wenn der Nutzer den Vorschlag heute umgedreht hat.
 * @property reasons die Gruende fuer [suggested], wichtigste zuerst.
 * @property counter die Gruende dagegen, wichtigste zuerst.
 * @property score Punktsumme (positiv = drinnen).
 * @property minutes Dauer der Einheit in Minuten (wie die ERG-Einheit sie rechnet).
 * @property sunset Sonnenuntergang heute; `null` ohne Ort oder bei Polartag/-nacht.
 * @property sunrise Sonnenaufgang heute; `null` wie [sunset].
 * @property outdoorTarget die Runde fuer draussen ([TodayRoute.target]).
 */
data class TodayPlace(
    val suggested: RidePlace,
    val place: RidePlace,
    val chosen: Boolean,
    val reasons: List<PlaceReason>,
    val counter: List<PlaceReason>,
    val score: Int,
    val minutes: Int,
    val sunset: Instant?,
    val sunrise: Instant?,
    val outdoorTarget: RouteTarget?,
)

/** Ist [session] strukturiert oder hart — eine Einheit, die auf der Rolle sauberer laeuft? */
fun isStructuredSession(session: TrainingSession): Boolean =
    session.intensity == SessionIntensity.HART ||
        session.textKey == SessionTextKey.INTERVALS ||
        session.textKey == SessionTextKey.WORKOUT ||
        session.textKey == SessionTextKey.FTP_TEST

/**
 * Der Ort fuer die Tageslichtrechnung: [point] auf eine Nachkommastelle
 * gerundet (etwa 10 km). Fuer den Sonnenuntergang macht das weniger als eine
 * Minute aus; mehr Genauigkeit braucht die Frage nicht. `null` bei
 * unbrauchbarer Koordinate.
 */
fun coarsePlacePoint(point: TrackPoint?): TrackPoint? {
    if (point == null) return null
    if (!point.lat.isFinite() || !point.lon.isFinite()) return null
    if (point.lat !in -90.0..90.0 || point.lon !in -180.0..180.0) return null
    if (point.lat == 0.0 && point.lon == 0.0) return null
    return TrackPoint(lat = round(point.lat * 10.0) / 10.0, lon = round(point.lon * 10.0) / 10.0)
}

/**
 * Schlaegt draussen oder drinnen fuer die Einheit [day] vor.
 *
 * @param day die Einheit des Tages (`daySessionFor`). Am Ruhe- und am Zieltag
 *   gibt es keinen Vorschlag (`null`): Dort steht keine Einheit an bzw. hat das
 *   Event seine eigene Strecke.
 * @param today die Tagesentscheidung; liefert die Runde fuer draussen.
 * @param now jetzt, in der Zeitzone des Geraets.
 * @param location grober Ort ([coarsePlacePoint]); `null` → kein Tageslichtkriterium.
 * @param forecast Tagesvorhersage; zaehlt nur, wenn ihr Datum heute ist.
 */
fun suggestTodayPlace(
    day: DaySession,
    today: TodayRoute,
    now: ZonedDateTime,
    location: TrackPoint?,
    forecast: DayForecast? = null,
): TodayPlace? {
    if (day.kind == DaySessionKind.REST || day.kind == DaySessionKind.EVENT) return null
    val session = day.session ?: return null
    val minutes = sessionDurationMinutes(session)
    val reasons = mutableListOf<PlaceReason>()

    // Tageslicht.
    var sunrise: Instant? = null
    var sunset: Instant? = null
    if (location != null) {
        val sun = sunTimes(now.toLocalDate(), location.lat, location.lon)
        sunrise = sun.sunrise
        sunset = sun.sunset
        daylightReason(sun, now.toInstant(), minutes)?.let(reasons::add)
    }

    // Art und Dauer der Einheit.
    if (isStructuredSession(session)) {
        reasons += PlaceReason.INTERVALS
    } else if (minutes >= LONG_ENDURANCE_MIN) {
        reasons += PlaceReason.LONG_ENDURANCE
    }

    // Wetter, nur wenn schon vorhanden und fuer heute.
    forecast?.takeIf { it.date == now.toLocalDate() }?.let { reasons += weatherReasons(it) }

    val score = reasons.sumOf { it.points }
    val suggested = if (score >= INDOOR_SCORE_MIN) RidePlace.INDOOR else RidePlace.OUTDOOR
    val byWeight = reasons.sortedWith(compareByDescending<PlaceReason> { abs(it.points) }.thenBy { it.ordinal })
    return TodayPlace(
        suggested = suggested,
        place = suggested,
        chosen = false,
        reasons = byWeight.filter { it.supports(suggested) },
        counter = byWeight.filter { !it.supports(suggested) },
        score = score,
        minutes = minutes,
        sunset = sunset,
        sunrise = sunrise,
        outdoorTarget = today.target,
    )
}

/** Der Tageslichtgrund, oder `null` (Polartag: kein Grund, das Licht reicht immer). */
internal fun daylightReason(sun: SunTimes, now: Instant, minutes: Int): PlaceReason? {
    if (sun.polarNight) return PlaceReason.DARK
    if (sun.polarDay) return null
    val rise = sun.sunrise ?: return null
    val set = sun.sunset ?: return null
    if (now.isBefore(rise) || !now.isBefore(set)) return PlaceReason.DARK
    val start = now.plus(Duration.ofMinutes(PLACE_START_BUFFER_MIN.toLong()))
    val end = start.plus(Duration.ofMinutes(minutes.toLong()))
    if (!end.isAfter(set)) return PlaceReason.DAYLIGHT
    val darkMin = Duration.between(maxOf(start, set), end).toMinutes().toDouble()
    val share = if (minutes > 0) darkMin / minutes else 1.0
    return if (share >= DARK_SHARE_MIN) PlaceReason.DARK else PlaceReason.DUSK
}

/** Die Wettergruende aus einer Tagesvorhersage (fehlende Werte zaehlen nicht). */
internal fun weatherReasons(f: DayForecast): List<PlaceReason> {
    val out = mutableListOf<PlaceReason>()
    val prob = f.precipProbabilityPct
    val mm = f.precipMm
    val rain = (mm != null && mm >= RAIN_HEAVY_MM) ||
        (prob != null && prob >= RAIN_PROBABILITY_PCT && (mm == null || mm >= 1.0))
    if (rain) out += PlaceReason.RAIN
    if ((f.windMaxKmh ?: 0.0) >= STORM_WIND_KMH) out += PlaceReason.STORM
    f.tempMaxC?.let { if (it <= FROST_MAX_C) out += PlaceReason.FROST }
    return out
}

/**
 * Wendet die Wahl des Nutzers an: [choice] ist der Ort, den er heute
 * ([date]) gewaehlt hat, oder `null`. Eine Wahl fuer einen anderen Tag gilt
 * nicht — morgen entscheidet wieder der Vorschlag.
 */
fun applyPlaceChoice(place: TodayPlace, choice: PlaceChoice?, date: LocalDate): TodayPlace {
    val c = choice?.takeIf { it.date == date } ?: return place
    return place.copy(place = c.place, chosen = c.place != place.suggested)
}

/** Der umgekehrte Ort. */
fun RidePlace.flipped(): RidePlace = if (this == RidePlace.INDOOR) RidePlace.OUTDOOR else RidePlace.INDOOR

/** Die Wahl des Nutzers fuer einen Tag. */
data class PlaceChoice(val date: LocalDate, val place: RidePlace) {
    fun encode(): String = "$date|${place.name}"

    companion object {
        /** Tolerant: kaputt oder unbekannt → `null`. */
        fun decode(text: String?): PlaceChoice? {
            val parts = text?.split('|') ?: return null
            if (parts.size != 2) return null
            val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
            val place = RidePlace.entries.firstOrNull { it.name == parts[1] } ?: return null
            return PlaceChoice(date, place)
        }
    }
}

/** Prefs-Schluessel der Wahl fuer heute ([PlaceChoice]). */
const val TODAY_PLACE_CHOICE_KEY: String = "trailscape.today.place.choice.v1"

/** Liest die gespeicherte Wahl; wirft nie. */
fun readPlaceChoice(store: KeyValueStore): PlaceChoice? =
    runCatching { PlaceChoice.decode(store.getString(TODAY_PLACE_CHOICE_KEY)) }.getOrNull()

/**
 * Speichert die Wahl. Entspricht sie dem Vorschlag ([suggested]), wird sie
 * entfernt — dann gilt wieder der Vorschlag, auch wenn er sich im Laufe des
 * Tages aendert (z. B. weil es dunkel wird).
 */
fun writePlaceChoice(store: KeyValueStore, choice: PlaceChoice, suggested: RidePlace) {
    runCatching {
        if (choice.place == suggested) store.remove(TODAY_PLACE_CHOICE_KEY)
        else store.setString(TODAY_PLACE_CHOICE_KEY, choice.encode())
    }
}

/**
 * Der Stand der heutigen Planeinheit: die Zuordnung aus [weekSessionProgress]
 * fuer [session] in [week], oder `null` ohne Planeinheit bzw. ohne Woche.
 */
fun todaySessionProgress(
    week: TrainingWeek?,
    session: TrainingSession?,
    rides: List<RideInfo>,
    nowMs: Long,
    rideLoads: Map<String, Double> = emptyMap(),
): PlanSessionProgress? {
    if (week == null || session == null) return null
    return weekSessionProgress(week, rides, now = nowMs, rideLoads = rideLoads).firstOrNull { it.session == session }
}

// ---------------------------------------------------------------------------
// Fuer die Uhr: der zuletzt auf „Heute" gezeigte Vorschlag
// ---------------------------------------------------------------------------

/**
 * Prefs-Schluessel des zuletzt auf „Heute" gezeigten Vorschlags (JSON wie
 * [HeuteVorschlag.toJson]). Dasselbe Muster wie das gezeigte Wochenziel des
 * Widgets: Die Uhr bekommt, was „Heute" gezeigt hat, nicht eine zweite Rechnung.
 */
const val TODAY_PLACE_SHOWN_KEY: String = "trailscape.today.place.shown.v1"

/**
 * Der Vorschlag fuer die Uhr. `null` ohne Vorschlag (Ruhetag, Zieltag) —
 * ausser die Einheit ist schon erledigt ([done]); dann reist der Ort mit, an
 * dem sie gefahren wurde ([doneIndoor], sonst der gewaehlte Ort).
 */
fun heuteVorschlagVon(place: TodayPlace?, date: LocalDate, done: Boolean, doneIndoor: Boolean? = null): HeuteVorschlag? {
    if (place == null) return null
    val drinnen = if (done && doneIndoor != null) doneIndoor else place.place == RidePlace.INDOOR
    return HeuteVorschlag(
        tagEpochTag = date.toEpochDay(),
        drinnen = drinnen,
        minuten = place.minutes.takeIf { it in 1..HeuteVorschlag.MAX_MINUTEN },
        erledigt = done,
        gewaehlt = place.chosen,
    )
}

/** Merkt den gezeigten Vorschlag ([TODAY_PLACE_SHOWN_KEY]); `null` entfernt ihn. Gibt `true` zurueck, wenn sich etwas geaendert hat. */
fun writeShownHeute(store: KeyValueStore, vorschlag: HeuteVorschlag?): Boolean = runCatching {
    val neu = vorschlag?.toJson()?.toString()
    if (store.getString(TODAY_PLACE_SHOWN_KEY) == neu) return false
    if (neu == null) store.remove(TODAY_PLACE_SHOWN_KEY) else store.setString(TODAY_PLACE_SHOWN_KEY, neu)
    true
}.getOrDefault(false)

/** Der gezeigte Vorschlag, wenn er fuer [date] gilt; sonst `null`. Wirft nie. */
fun readShownHeute(store: KeyValueStore, date: LocalDate): HeuteVorschlag? =
    runCatching { parseHeuteVorschlag(store.getString(TODAY_PLACE_SHOWN_KEY)) }.getOrNull()
        ?.takeIf { it.tagEpochTag == date.toEpochDay() }
