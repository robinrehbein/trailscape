package de.trailscape.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # Lokale Erinnerungen — die Entscheidung, nicht die Anzeige
 *
 * Trailscape weiss morgens, was ansteht; bisher sagte es das nur, wenn man die
 * App von sich aus oeffnete. Diese Datei beantwortet die eine Frage, die ein
 * Hintergrundlauf dafuer stellen muss: **„Ist gerade etwas zu melden, und wie
 * lautet der Text?"**
 *
 * ## Warum das hier liegt und nicht in `:app`
 * [dueReminder] ist eine reine Funktion: Zeitpunkt, Plan, Touren, Einstellungen
 * und der bisher gemeldete Stand kommen herein, eine [ReminderNotice] (oder
 * `null`) kommt heraus. Kein `LocalDate.now()` im Rumpf, kein Android, keine
 * Datei- und keine Netzzugriffe — dadurch sind Ruhetag, abgelaufener Plan,
 * leere Tourenliste, Sonntag mit zwei faelligen Anlaessen und die Zeitumstellung
 * in Sekunden pruefbar (siehe `RemindersTest.kt`). Der WorkManager-Teil in
 * `:app` bleibt dadurch duenn: Daten holen, diese Funktion fragen, anzeigen,
 * Stand merken.
 *
 * ## Genau eine Meldung pro Lauf
 * [dueReminder] liefert hoechstens **eine** Meldung. Zwei Benachrichtigungen
 * gleichzeitig waeren dieselbe Unterbrechung zweimal; welche im Konfliktfall
 * gewinnt, steht bei der Funktion.
 *
 * ## Sommerzeit
 * Gerechnet wird durchgehend auf der **Wanduhr** (`LocalDate`/`LocalTime`),
 * nie mit festen Millisekunden-Abstaenden: „jeden Morgen um 7:00" bleibt damit
 * auch in der Nacht der Zeitumstellung 7:00 und der Tageswechsel bleibt der
 * Kalendertag. Die Umrechnung des naechsten Laufs in eine Wartezeit macht
 * `:app` ueber die Zeitzone des Geraets.
 */

/** Anlass einer Erinnerung. */
enum class ReminderKind {
    /** Morgens: die heute geplante Einheit (oder der Ruhetag). */
    TAGESEINHEIT,

    /** Sonntagabends: gefahrene gegen geplante Kilometer der laufenden Woche. */
    WOCHENRUECKSCHAU,

    /** Nach mehreren Tagen ohne Aufzeichnung. */
    ANSTUPSER,
}

/**
 * Ab wie vielen Tagen ohne Aufzeichnung der Anstupser faellig wird.
 *
 * Bewusst eine Konstante und keine Einstellung: Die Karte im Mehr-Tab bietet
 * drei Schalter und zwei Uhrzeiten an — eine vierte Stellschraube fuer eine
 * Zahl, die kaum jemand bewusst anders waehlen wuerde, waere nur mehr
 * Bedienflaeche ohne Gewinn.
 */
const val reminderNudgeAfterDays: Int = 5

/**
 * Mindestabstand zwischen zwei Anstupsern in Tagen — „hoechstens einmal pro
 * Woche". Ohne diese Sperre kaeme er ab dem fuenften Tag *jeden* Tag, und aus
 * einem Hinweis wuerde Nörgeln.
 */
const val reminderNudgeCooldownDays: Int = 7

/** Speicherschluessel der Einstellungen im [KeyValueStore]. */
const val reminderSettingsStorageKey: String = "trailscape.reminders"

/**
 * Speicherschluessel des Meldestands im [KeyValueStore] — bewusst getrennt von
 * den Einstellungen: Der Stand aendert sich bei jedem Lauf, die Einstellungen
 * nur, wenn jemand sie anfasst.
 */
const val reminderStateStorageKey: String = "trailscape.reminders.state"

/**
 * Was die Nutzerin eingestellt hat.
 *
 * **Alle drei Anlaesse sind ab Werk aus.** Eine App, die ungefragt anfaengt zu
 * benachrichtigen, verliert genau das Vertrauen, von dem der Rest dieser App
 * lebt.
 */
data class ReminderSettings(
    /** Morgens: was heute ansteht. */
    val dailySessionEnabled: Boolean = false,
    /** Sonntagabends: gefahrene gegen geplante Kilometer. */
    val weeklyReviewEnabled: Boolean = false,
    /** Nach [reminderNudgeAfterDays] Tagen ohne Aufzeichnung. */
    val nudgeEnabled: Boolean = false,
    /**
     * Uhrzeit der Tageseinheit. Der Anstupser haengt sich an dieselbe Uhrzeit,
     * statt einen dritten Weckzeitpunkt zu erfinden (siehe [nextReminderRun]).
     */
    val dailySessionTime: LocalTime = LocalTime.of(7, 0),
    /** Uhrzeit der Wochenrueckschau am Sonntag. */
    val weeklyReviewTime: LocalTime = LocalTime.of(18, 0),
) {
    /** Ob ueberhaupt etwas eingeschaltet ist — sonst laeuft gar keine Arbeit. */
    val anyEnabled: Boolean
        get() = dailySessionEnabled || weeklyReviewEnabled || nudgeEnabled

    fun toJson(): JsonObject = buildJsonObject {
        put("dailySessionEnabled", dailySessionEnabled)
        put("weeklyReviewEnabled", weeklyReviewEnabled)
        put("nudgeEnabled", nudgeEnabled)
        // Uhrzeiten als Minuten seit Mitternacht: eine Zahl ohne Zeitzone,
        // ohne Locale und ohne Formatfrage.
        put("dailySessionMinute", dailySessionTime.toSecondOfDay() / 60)
        put("weeklyReviewMinute", weeklyReviewTime.toSecondOfDay() / 60)
    }

    companion object {
        /**
         * Liest die Einstellungen so nachsichtig wie moeglich: Jedes fehlende
         * oder unsinnige Feld faellt auf seine Vorgabe zurueck. Ein halb
         * kaputter Eintrag darf hoechstens dazu fuehren, dass eine Erinnerung
         * ausbleibt — nie dazu, dass die App beim Start wirft.
         */
        fun fromJson(json: JsonObject): ReminderSettings {
            val defaults = ReminderSettings()
            return ReminderSettings(
                dailySessionEnabled = json.flag("dailySessionEnabled", defaults.dailySessionEnabled),
                weeklyReviewEnabled = json.flag("weeklyReviewEnabled", defaults.weeklyReviewEnabled),
                nudgeEnabled = json.flag("nudgeEnabled", defaults.nudgeEnabled),
                dailySessionTime = json.timeOfDay("dailySessionMinute") ?: defaults.dailySessionTime,
                weeklyReviewTime = json.timeOfDay("weeklyReviewMinute") ?: defaults.weeklyReviewTime,
            )
        }
    }
}

/**
 * Was zuletzt gemeldet wurde — je Anlass der Kalendertag der letzten Meldung.
 *
 * Der Stand haengt an *Tagen* und nicht an Zeitstempeln, weil genau das die
 * Regeln sind: „hoechstens einmal am Tag" bzw. „hoechstens einmal pro Woche".
 * Ein Zeitstempel wuerde bei der Zeitumstellung anfangen zu rutschen, ein
 * Kalendertag nicht.
 */
data class ReminderState(
    val lastDailySessionOn: LocalDate? = null,
    val lastWeeklyReviewOn: LocalDate? = null,
    val lastNudgeOn: LocalDate? = null,
) {
    /** Vermerkt eine soeben angezeigte Meldung. */
    fun markDelivered(kind: ReminderKind, on: LocalDate): ReminderState = when (kind) {
        ReminderKind.TAGESEINHEIT -> copy(lastDailySessionOn = on)
        ReminderKind.WOCHENRUECKSCHAU -> copy(lastWeeklyReviewOn = on)
        ReminderKind.ANSTUPSER -> copy(lastNudgeOn = on)
    }

    fun toJson(): JsonObject = buildJsonObject {
        // ISO-Datum (`2026-08-13`): lesbar, sortierbar, ohne Zeitzone.
        lastDailySessionOn?.let { put("lastDailySessionOn", it.toString()) }
        lastWeeklyReviewOn?.let { put("lastWeeklyReviewOn", it.toString()) }
        lastNudgeOn?.let { put("lastNudgeOn", it.toString()) }
    }

    companion object {
        /** Wie [ReminderSettings.fromJson] nachsichtig: Unlesbares gilt als „nie gemeldet". */
        fun fromJson(json: JsonObject): ReminderState = ReminderState(
            lastDailySessionOn = json.isoDate("lastDailySessionOn"),
            lastWeeklyReviewOn = json.isoDate("lastWeeklyReviewOn"),
            lastNudgeOn = json.isoDate("lastNudgeOn"),
        )
    }
}

/**
 * Eine faellige Meldung.
 *
 * [title] und [text] sind fertige deutsche Anzeigetexte — sachlich, ohne
 * Ausrufezeichen und ohne Motivationssprech, wie der Rest der App.
 */
data class ReminderNotice(
    val kind: ReminderKind,
    val title: String,
    val text: String,
)

/**
 * Entscheidet, welche Meldung zum Zeitpunkt [now] faellig ist — oder `null`,
 * wenn gerade nichts zu sagen ist.
 *
 * ## Wann etwas faellig ist
 * Eine Meldung wird faellig, sobald ihre Uhrzeit am jeweiligen Tag erreicht
 * ist, und bleibt es **bis Mitternacht**. Der Hintergrundlauf muss dadurch
 * nicht auf die Minute genau kommen (er tut es systembedingt auch nicht): Ein
 * um zwanzig Minuten verspaeteter Lauf holt die Meldung nach. Am Folgetag holt
 * er sie *nicht* mehr nach — „Heute: Lange Tour, 80 km" waere dann schlicht
 * falsch.
 *
 * ## Wenn mehrere Anlaesse zusammenfallen
 * Der Regelfall ist **kein** Konflikt: Die Tageseinheit haengt an der
 * Morgen-Uhrzeit, die Wochenrueckschau am Sonntagabend — am Sonntag kommen
 * beide, nur eben in zwei getrennten Laeufen. Trifft doch einmal alles auf
 * denselben Lauf (Geraet war den ganzen Sonntag aus, oder beide Uhrzeiten sind
 * gleich gestellt), gilt diese Reihenfolge:
 *
 *  1. **Wochenrueckschau** — sie ist die einzige der drei, die abends noch
 *     stimmt.
 *  2. **Tageseinheit** — am Abend nachgereicht bringt sie wenig, aber sie ist
 *     immer noch die konkretere Auskunft als der Anstupser.
 *  3. **Anstupser** — der unwichtigste; er hat als einziger keinen festen Tag
 *     und kann problemlos morgen kommen.
 *
 * Uebergangene Anlaesse werden **nicht** nachgereicht: Der Aufrufer vermerkt
 * nur die tatsaechlich angezeigte Meldung, alle anderen werden am naechsten
 * regulaeren Termin neu bewertet.
 *
 * @param now Zeitpunkt des Laufs in lokaler Zeit.
 * @param state Stand der zuletzt angezeigten Meldungen (siehe [ReminderState]).
 * @param plan der gespeicherte Trainingsplan, oder `null`.
 * @param rides alle gespeicherten Touren; Reihenfolge egal.
 */
fun dueReminder(
    now: LocalDateTime,
    settings: ReminderSettings,
    state: ReminderState,
    plan: TrainingPlan?,
    rides: List<Ride>,
): ReminderNotice? {
    val today = now.toLocalDate()
    val timeOfDay = now.toLocalTime()

    if (settings.weeklyReviewEnabled &&
        today.dayOfWeek == DayOfWeek.SUNDAY &&
        !timeOfDay.isBefore(settings.weeklyReviewTime) &&
        state.lastWeeklyReviewOn != today
    ) {
        weeklyReviewNotice(now, plan, rides)?.let { return it }
    }

    // Tageseinheit und Anstupser teilen sich die Morgen-Uhrzeit, siehe
    // [ReminderSettings.dailySessionTime].
    val morningReached = !timeOfDay.isBefore(settings.dailySessionTime)

    if (settings.dailySessionEnabled && morningReached && state.lastDailySessionOn != today) {
        dailySessionNotice(now, plan)?.let { return it }
    }

    if (settings.nudgeEnabled && morningReached && nudgeAllowed(state, today)) {
        nudgeNotice(today, rides)?.let { return it }
    }

    return null
}

/**
 * Der naechste Zeitpunkt, zu dem ein Lauf ueberhaupt etwas zu melden haben
 * kann — `null`, wenn alle drei Anlaesse aus sind (dann braucht es gar keine
 * Hintergrundarbeit).
 *
 * `:app` rechnet daraus die Wartezeit bis zum naechsten Lauf. Die Funktion
 * gehoert hierher und nicht dorthin, weil sie dieselben Regeln kennen muss wie
 * [dueReminder] — insbesondere, dass der Anstupser keine eigene Uhrzeit hat
 * und dass die Wochenrueckschau nur sonntags faellig wird.
 *
 * Geliefert wird immer ein Zeitpunkt **echt nach** [now]: Was jetzt gerade
 * faellig ist, hat der laufende Durchgang bereits in der Hand.
 */
fun nextReminderRun(now: LocalDateTime, settings: ReminderSettings): LocalDateTime? {
    if (!settings.anyEnabled) return null
    val morningNeeded = settings.dailySessionEnabled || settings.nudgeEnabled

    // Acht Tage reichen sicher: Der seltenste Anlass (Wochenrueckschau) kommt
    // spaetestens nach sieben Tagen wieder.
    for (offset in 0L..7L) {
        val date = now.toLocalDate().plusDays(offset)
        val candidates = mutableListOf<LocalDateTime>()
        if (morningNeeded) {
            candidates += date.atTime(settings.dailySessionTime)
        }
        if (settings.weeklyReviewEnabled && date.dayOfWeek == DayOfWeek.SUNDAY) {
            candidates += date.atTime(settings.weeklyReviewTime)
        }
        candidates.filter { it.isAfter(now) }.minOrNull()?.let { return it }
    }
    return null
}

// ---------------------------------------------------------------------------
// Die einzelnen Meldungen
// ---------------------------------------------------------------------------

/**
 * „Was steht heute an?" — dieselbe Auskunft, die die Startseite gibt, nur
 * ungefragt.
 *
 * **Ohne Plan kommt nichts.** [sessionsForDay] liefert dann zwar ebenfalls
 * nichts, aber die Unterscheidung ist wichtig: „kein Plan" ist etwas anderes
 * als „Plan sagt: heute frei". Auch ein **abgelaufener** Plan schweigt —
 * [currentWeekIndex] klemmt nach Planende auf die letzte Woche, deren
 * Einheiten laengst vorbei sind; ohne die Pruefung auf die laufende Woche
 * wuerde ein alter Plan jeden Morgen die Einheiten seiner Zielwoche wiederholen.
 *
 * **Am Ruhetag kommt ein Hinweis, keine Stille.** Das ist eine Entscheidung
 * gegen die naheliegende Alternative: Wer diese Erinnerung einschaltet, will
 * jeden Morgen wissen, woran er ist. Schweigen ist zweideutig — es kann „frei"
 * heissen oder „Akku war leer", „Berechtigung fehlt", „vergessen". Der Ruhetag
 * ist ausserdem keine Leerstelle, sondern eine Ansage des Plans: Er steht dort
 * genauso bewusst wie eine Einheit. Der Preis (an Ruhetagen eine
 * Benachrichtigung, die nichts fordert) ist gering und jederzeit mit dem
 * Schalter abstellbar.
 */
private fun dailySessionNotice(now: LocalDateTime, plan: TrainingPlan?): ReminderNotice? {
    if (plan == null) return null
    val nowMs = dartEpochMs(now)
    if (activeWeek(plan, nowMs) == null) return null

    // Wie auf der Startseite: hoechstens eine Einheit pro Tag. Plaene aus
    // `:core` setzen nie zwei auf denselben Tag.
    val session = sessionsForDay(plan, nowMs).firstOrNull()
        ?: return ReminderNotice(
            kind = ReminderKind.TAGESEINHEIT,
            title = "Heute",
            text = "Ruhetag — im Plan steht heute keine Einheit.",
        )

    return ReminderNotice(
        kind = ReminderKind.TAGESEINHEIT,
        title = "Heute",
        text = "${session.title}, ${session.targetKm} km",
    )
}

/**
 * Gefahrene gegen geplante Kilometer der laufenden Woche.
 *
 * Ohne laufende Planwoche gibt es keine geplanten Kilometer und damit keinen
 * Vergleich — dann bleibt es still, statt eine nackte Wochensumme zu melden,
 * die niemand bestellt hat.
 *
 * Gerundet auf ganze Kilometer: In einer Benachrichtigung ist die
 * Nachkommastelle Ballast, und das Planziel ist ohnehin ganzzahlig.
 */
private fun weeklyReviewNotice(
    now: LocalDateTime,
    plan: TrainingPlan?,
    rides: List<Ride>,
): ReminderNotice? {
    if (plan == null) return null
    val week = activeWeek(plan, dartEpochMs(now)) ?: return null
    val ridden = dartRound(weekKm(week, rides)).toInt()

    return ReminderNotice(
        kind = ReminderKind.WOCHENRUECKSCHAU,
        title = "Wochenrückschau",
        text = "Diese Woche: $ridden von ${week.targetKm} km gefahren.",
    )
}

/**
 * Der Anstupser nach [reminderNudgeAfterDays] Tagen ohne Aufzeichnung.
 *
 * **Ohne jede Tour kommt er nicht.** Er meldet eine *Pause*, und wer noch nie
 * aufgezeichnet hat, macht keine Pause — es gaebe auch gar keinen Bezugstag,
 * ab dem gezaehlt werden koennte. Die Einladung zur ersten Tour steht dort,
 * wo sie hingehoert: im Leerzustand der Startseite.
 *
 * Gezaehlt werden Kalendertage zwischen der juengsten Tour und heute, nicht
 * 24-Stunden-Bloecke: „seit fuenf Tagen" soll dasselbe heissen wie im
 * Kalender. Eine gespeicherte **Planung** ([Ride.planned]) beendet die Pause
 * nicht — sie ist keine Fahrt, und der Anstupser darf nicht verstummen, weil
 * jemand eine Route abgelegt hat.
 */
private fun nudgeNotice(today: LocalDate, rides: List<Ride>): ReminderNotice? {
    val lastRideOn = riddenRides(rides).maxOfOrNull { it.createdAt }
        ?.let { dartLocalOf(it).toLocalDate() }
        ?: return null

    val days = ChronoUnit.DAYS.between(lastRideOn, today)
    if (days < reminderNudgeAfterDays) return null

    return ReminderNotice(
        kind = ReminderKind.ANSTUPSER,
        title = "Seit $days Tagen keine Tour",
        text = "Wenn du wieder unterwegs bist, zeichnet Trailscape die Runde auf — " +
            "auch eine kurze zählt für die Auswertung.",
    )
}

private fun nudgeAllowed(state: ReminderState, today: LocalDate): Boolean {
    val last = state.lastNudgeOn ?: return true
    return ChronoUnit.DAYS.between(last, today) >= reminderNudgeCooldownDays
}

/**
 * Die Planwoche, in der [nowMs] tatsaechlich liegt — `null` vor Planbeginn und
 * nach Planende. Dieselbe Pruefung wie in [sessionsForDay]; sie steht hier
 * noch einmal, weil die Erinnerungen „kein Plan aktiv" von „heute frei"
 * unterscheiden muessen, was die Einheitenliste allein nicht hergibt.
 */
private fun activeWeek(plan: TrainingPlan, nowMs: Long): TrainingWeek? {
    val index = currentWeekIndex(plan, nowMs)
    if (index < 0) return null
    return plan.weeks.getOrNull(index)?.takeIf { nowMs >= it.start && nowMs < it.end }
}

// ---------------------------------------------------------------------------
// JSON-Hilfen (nur fuer diese Datei)
// ---------------------------------------------------------------------------

private fun JsonObject.flag(key: String, fallback: Boolean): Boolean =
    (fieldOrNull(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: fallback

private fun JsonObject.timeOfDay(key: String): LocalTime? =
    optionalInt(key)?.takeIf { it in 0 until 24 * 60 }?.let { LocalTime.of(it / 60, it % 60) }

private fun JsonObject.isoDate(key: String): LocalDate? =
    (fieldOrNull(key) as? JsonPrimitive)?.content?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }
