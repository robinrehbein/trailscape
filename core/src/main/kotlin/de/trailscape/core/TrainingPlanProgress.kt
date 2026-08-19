package de.trailscape.core

import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Rueckkopplung zwischen Trainingsplan und gefahrener Realitaet.
 *
 * Zwei reine Funktionen, beide ohne jede Persistenz:
 *
 *  * [weekSessionProgress] ordnet die gefahrenen Touren einer Woche den
 *    Planeinheiten zu und liefert je Einheit einen Status (offen, erledigt,
 *    teilweise, verpasst). Der Status wird **berechnet, nie gespeichert** —
 *    Touren und Plan liegen ohnehin auf der Platte, ein drittes persistiertes
 *    Feld waere nur eine zweite Wahrheit, die mit beiden auseinanderlaufen
 *    kann.
 *  * [adaptPlan] skaliert die verbleibenden Planwochen neu, wenn die
 *    Vergangenheit deutlich unter Soll lag. Auch hier gilt: Der gespeicherte
 *    Plan wird **nicht angefasst** (sein JSON-Format ist mit Web-App und
 *    Sync-Server abgestimmt); das Ergebnis ist ein abgeleiteter Anzeige-Plan,
 *    den die Oberflaeche an Stelle des gespeicherten zeigt.
 */

/** Status einer Planeinheit gegen die tatsaechlich gefahrenen Touren. */
enum class PlanSessionStatus {
    /** Der Tag der Einheit (plus Toleranz) liegt noch nicht hinter uns. */
    OFFEN,

    /** Eine Tour deckt die Einheit zu mindestens [sessionDoneShare]. */
    ERLEDIGT,

    /** Eine Tour passt zeitlich, bleibt aber unter [sessionDoneShare]. */
    TEILWEISE,

    /** Keine passende Tour, und der Tag (plus Toleranz) ist vorbei. */
    VERPASST,
}

/** Deutsche Beschriftung je Status — fuer die Oberflaeche. */
val planSessionStatusLabels: Map<PlanSessionStatus, String> = mapOf(
    PlanSessionStatus.OFFEN to "Offen",
    PlanSessionStatus.ERLEDIGT to "Erledigt",
    PlanSessionStatus.TEILWEISE to "Teilweise",
    PlanSessionStatus.VERPASST to "Verpasst",
)

/**
 * Zeitliche Toleranz der Zuordnung in Tagen: Eine Tour am Vor- oder Folgetag
 * zaehlt noch fuer die Einheit. Wer die Samstagstour auf Sonntag schiebt, hat
 * die Einheit gemacht — ein Plan, der das als „verpasst" fuehrt, erzieht zum
 * Ignorieren des Plans. Mehr als ein Tag wuerde dagegen Einheiten
 * zusammenziehen, die bewusst getrennt sind (Di/Do/Sa-Raster).
 */
const val sessionMatchToleranceDays: Int = 1

/**
 * Mindestanteil an Ziel-Kilometern **oder** Ziel-Last, ab dem eine Einheit als
 * erledigt gilt (0,6 = 60 %). Derselbe Gedanke wie [minLongestRideShare]:
 * Unterhalb von 60 % ist es eine andere Einheit, nicht eine etwas kuerzere.
 * Es zaehlt das Maximum beider Anteile — wer die Kilometer haerter faehrt als
 * geplant, hat den Trainingsreiz gesetzt, auch wenn die Distanz fehlt.
 */
const val sessionDoneShare: Double = 0.6

/** Zuordnungsergebnis fuer eine einzelne Planeinheit. */
data class PlanSessionProgress(
    val session: TrainingSession,
    val status: PlanSessionStatus,
    /** ID der zugeordneten Tour; `null` bei OFFEN/VERPASST. */
    val rideId: String? = null,
    /** Kilometer der zugeordneten Tour. */
    val riddenKm: Double? = null,
    /** Last der zugeordneten Tour, falls im uebergebenen Lastverzeichnis. */
    val riddenLoad: Double? = null,
)

/** Kalendertag (lokal) einer Einheit innerhalb ihrer Woche; `null` bei fremdem Kuerzel. */
private fun sessionDate(week: TrainingWeek, session: TrainingSession): java.time.LocalDate? {
    val index = planWeekdayIndex(session.day)
    if (index < 0) {
        return null
    }
    return dartLocalOf(week.start).toLocalDate().plusDays(index.toLong())
}

/**
 * Ordnet die gefahrenen Touren den Planeinheiten von [week] zu und liefert je
 * Einheit einen [PlanSessionStatus].
 *
 * ## Zuordnungsregeln
 *  * Nur **gefahrene** Touren zaehlen ([riddenRides]); gespeicherte Planungen
 *    erledigen keine Einheit — dieselbe Regel wie bei [weekKm].
 *  * Kandidatinnen sind Touren, deren Kalendertag hoechstens
 *    [sessionMatchToleranceDays] vom Tag der Einheit abweicht (auch ueber die
 *    Wochengrenze hinweg).
 *  * **Eine Tour deckt hoechstens eine Einheit.** Die Einheiten werden in
 *    Wochenreihenfolge abgearbeitet; jede nimmt die beste noch freie Tour:
 *    kleinste Tagesabweichung, bei Gleichstand die laengere Tour (die grosse
 *    Samstagsrunde gehoert zur langen Tour, nicht zur Regenerationsfahrt),
 *    danach die aeltere. Deterministisch und damit testbar.
 *
 * ## Status
 * Erfuellungsgrad ist das Maximum aus km-Anteil und (falls [rideLoads] die
 * Tour kennt und die Einheit ein Last-Ziel traegt) Last-Anteil; ab
 * [sessionDoneShare] gilt ERLEDIGT, darunter TEILWEISE. Ohne passende Tour
 * entscheidet die Uhr: VERPASST erst, wenn auch der Toleranztag vorbei ist.
 *
 * @param rideLoads Last je Tour-ID (z. B. aus `TrainingInsights.rideLoads`);
 *   leer, wenn keine Lasten vorliegen — dann entscheidet allein die Distanz.
 */
fun weekSessionProgress(
    week: TrainingWeek,
    rides: List<Ride>,
    now: Long? = null,
    rideLoads: Map<String, Double> = emptyMap(),
): List<PlanSessionProgress> {
    val nowMs = now ?: System.currentTimeMillis()
    val weekStartDate = dartLocalOf(week.start).toLocalDate()
    val earliest = weekStartDate.minusDays(sessionMatchToleranceDays.toLong())
    val latest = weekStartDate.plusDays(6L + sessionMatchToleranceDays)

    // Kandidatinnen einmal vorbereiten: Kalendertag + Distanz.
    data class Candidate(val ride: Ride, val date: java.time.LocalDate) {
        var taken: Boolean = false
    }

    val candidates = riddenRides(rides)
        .map { Candidate(it, dartLocalOf(it.createdAt).toLocalDate()) }
        .filter { !it.date.isBefore(earliest) && !it.date.isAfter(latest) }

    val ordered = week.sessions.sortedBy { planWeekdayIndex(it.day) }
    val results = LinkedHashMap<TrainingSession, PlanSessionProgress>()

    for (session in ordered) {
        val date = sessionDate(week, session)
        if (date == null) {
            // Fremdes Tageskuerzel (fremdes Plan-JSON): keine Zuordnung moeglich.
            results[session] = PlanSessionProgress(session, PlanSessionStatus.OFFEN)
            continue
        }

        val best = candidates
            .filter {
                !it.taken &&
                    abs(ChronoUnit.DAYS.between(date, it.date)) <= sessionMatchToleranceDays
            }
            .minWithOrNull(
                compareBy(
                    { candidate: Candidate -> abs(ChronoUnit.DAYS.between(date, candidate.date)) },
                ).thenByDescending { candidate: Candidate -> candidate.ride.stats.distanceKm }
                    .thenBy { candidate: Candidate -> candidate.ride.createdAt },
            )

        if (best == null) {
            // Verpasst erst, wenn auch der Toleranztag komplett vorbei ist.
            val deadline = dartEpochMs(
                date.plusDays(sessionMatchToleranceDays + 1L).atStartOfDay(),
            )
            val status = if (nowMs >= deadline) {
                PlanSessionStatus.VERPASST
            } else {
                PlanSessionStatus.OFFEN
            }
            results[session] = PlanSessionProgress(session, status)
            continue
        }

        best.taken = true
        val riddenKm = best.ride.stats.distanceKm
        val riddenLoad = rideLoads[best.ride.id]
        val kmShare = if (session.targetKm > 0) riddenKm / session.targetKm else 1.0
        val loadShare = session.targetLoad
            ?.takeIf { it > 0 }
            ?.let { target -> riddenLoad?.div(target) }
        val fulfilled = max(kmShare, loadShare ?: 0.0)
        results[session] = PlanSessionProgress(
            session = session,
            status = if (fulfilled >= sessionDoneShare) {
                PlanSessionStatus.ERLEDIGT
            } else {
                PlanSessionStatus.TEILWEISE
            },
            rideId = best.ride.id,
            riddenKm = riddenKm,
            riddenLoad = riddenLoad,
        )
    }

    // Ergebnis in Originalreihenfolge der Woche, nicht in Zuordnungsreihenfolge.
    return week.sessions.map { results.getValue(it) }
}

// ---------------------------------------------------------------------------
// Adaption: der Plan folgt der Realitaet, nicht umgekehrt
// ---------------------------------------------------------------------------

/**
 * Unterhalb dieses Anteils vom Wochen-Soll gilt eine abgeschlossene Woche als
 * „deutlich unter Soll" und loest die Neuskalierung aus (0,7 = 70 %).
 *
 * Bewusst unter [sessionDoneShare] plus Luft: Eine einzelne verkuerzte
 * Einheit (60–70 % der Woche) ist Alltag und kein Grund, den Aufbau
 * umzuwerfen. Wer dagegen weniger als 70 % einer ganzen Woche faehrt, dem
 * fehlt der Trainingsreiz, auf dem die Folgewochen aufbauen — die naechste
 * Woche waere dann kein +15-%-Schritt mehr, sondern ein Sprung, den
 * [MAX_WEEKLY_INCREASE] genau verhindern soll.
 */
const val adaptTriggerShare: Double = 0.7

/**
 * Wie viele abgeschlossene Wochen ruecklaufend nach dem tatsaechlich
 * erreichten Volumen abgesucht werden. Das Maximum ueber vier Wochen statt
 * nur der letzten: Nach einer (planmaessigen oder erzwungenen)
 * Erholungswoche ist die zuletzt gefahrene Woche klein, das vertragene
 * Niveau aber das der Aufbauwochen davor — derselbe Gedanke, mit dem die
 * Rampengrenze gegen die letzte **Aufbau**woche rechnet.
 */
const val adaptLookbackWeeks: Int = 4

/**
 * Ergebnis von [adaptPlan].
 *
 * @param plan der anzuzeigende Plan — bei [adapted] `false` unveraendert das
 *   Original.
 * @param adapted ob die verbleibenden Wochen neu skaliert wurden.
 * @param reason kurze deutsche Begruendung fuer die Oberflaeche; `null`, wenn
 *   nichts geaendert wurde.
 */
data class AdaptedPlan(
    val plan: TrainingPlan,
    val adapted: Boolean,
    val reason: String?,
)

/**
 * Passt die **verbleibenden** Wochen eines Plans an die gefahrene Realitaet
 * an.
 *
 * ## Wann
 * Die zuletzt abgeschlossene Woche (kein Taper, keine Zielwoche) hat weniger
 * als [adaptTriggerShare] ihres Solls erreicht. Verglichen wird in **Last**
 * (Summe der Tour-Lasten gegen die Summe der [TrainingSession.targetLoad]),
 * sobald beides vorliegt — sonst in Kilometern. Wochen ueber Soll aendern den
 * Plan nie: Wer mehr faehrt als geplant, bekommt kein hoeheres Soll
 * aufgebrummt, das waere die Rolle der Deload-Warnungen, nicht des Plans.
 *
 * ## Was
 * Die Wochen, die noch nicht abgeschlossen sind (die laufende eingeschlossen),
 * werden mit derselben Maschinerie neu gerechnet, mit der der Plan entstand
 * ([planWeekVolumes] + [buildSessions] + [attachSessionLoads]) — Startvolumen
 * ist das **tatsaechlich erreichte** Maximum der letzten
 * [adaptLookbackWeeks] abgeschlossenen Wochen, der +15-%-Deckel
 * ([MAX_WEEKLY_INCREASE]) gilt unveraendert. Blockstruktur
 * (Aufbau/Erholung/Taper/Zielwoche), Wochengrenzen und das Zieldatum bleiben
 * stehen; die Zielwoche bleibt woertlich unveraendert.
 *
 * ## Was NICHT
 * Der gespeicherte Plan wird **nicht ueberschrieben** — das Ergebnis ist ein
 * abgeleiteter Anzeige-Plan. Das Plan-JSON ist mit Web-App und Sync-Server
 * abgestimmt; eine stille Mutation wuerde ausserdem die Referenz zerstoeren,
 * gegen die kommende Wochen verglichen werden. Aufrufer zeigen
 * `adaptPlan(...).plan` an (und bewerten ihn mit [assessPlanFeasibility]),
 * persistieren aber weiterhin das Original.
 *
 * @param currentCtl aktuelle CTL fuer die Last-Budgets der neu gerechneten
 *   Wochen ([planWeekLoadBudgets]); `null` laesst die Budgets aus den
 *   Kilometern entstehen.
 * @param rideLoads Last je Tour-ID (aus `TrainingInsights.rideLoads`).
 */
fun adaptPlan(
    plan: TrainingPlan,
    rides: List<Ride>,
    now: Long? = null,
    currentCtl: Double? = null,
    rideLoads: Map<String, Double> = emptyMap(),
): AdaptedPlan {
    val nowMs = now ?: System.currentTimeMillis()
    val unchanged = AdaptedPlan(plan, adapted = false, reason = null)

    val completed = plan.weeks.filter { it.end <= nowMs }
    val last = completed.maxByOrNull { it.index } ?: return unchanged
    if (last.kind == WeekKind.TAPER || last.kind == WeekKind.ZIELWOCHE) {
        // Der Aufbau ist durch — kurz vor dem Event wird nicht mehr umgebaut.
        return unchanged
    }

    val ridden = riddenRides(rides)
    val weekRideList = ridden.filter { it.createdAt >= last.start && it.createdAt < last.end }
    val budget = last.sessions.mapNotNull { it.targetLoad }.takeIf { it.isNotEmpty() }?.sum()
    val loadKnown = budget != null && budget > 0 &&
        weekRideList.all { rideLoads.containsKey(it.id) }

    val ratio = if (loadKnown) {
        weekRideList.sumOf { rideLoads.getValue(it.id) } / budget!!
    } else {
        if (last.targetKm <= 0) return unchanged
        weekKm(last, rides) / last.targetKm
    }
    if (!ratio.isFinite() || ratio >= adaptTriggerShare) {
        return unchanged
    }

    val remaining = plan.weeks.filter { it.end > nowMs }
    if (remaining.none { it.kind == WeekKind.AUFBAU || it.kind == WeekKind.ERHOLUNG || it.kind == WeekKind.TAPER }) {
        return unchanged
    }

    // Startvolumen: das Beste, was in den letzten Wochen wirklich gefahren
    // wurde — nie unter 5 km, sonst erzeugt round5 keine sinnvolle Woche.
    val recentCompleted = completed.sortedByDescending { it.index }.take(adaptLookbackWeeks)
    val achievedKm = recentCompleted.maxOfOrNull { weekKm(it, rides) } ?: 0.0
    val newStartKm = max(achievedKm, 5.0)

    val remainingKinds = remaining.map { it.kind }
    val eventWeekKm = zielwocheSessions(plan.goal).sumOf { it.targetKm }
    val volumes = planWeekVolumes(
        kinds = remainingKinds,
        startKm = newStartKm,
        peakKm = peakKmFor(plan.goal, newStartKm),
        eventWeekKm = eventWeekKm,
    )
    val budgets = planWeekLoadBudgets(remainingKinds, currentCtl)
    val hilly = planIsHilly(plan.goal)

    val rebuilt = remaining.mapIndexed { j, week ->
        if (week.kind == WeekKind.ZIELWOCHE) {
            week
        } else {
            week.copy(
                targetKm = volumes[j],
                sessions = attachSessionLoads(
                    sessions = buildSessions(week.kind, plan.level, volumes[j], plan.goal),
                    weekBudget = budgets[j],
                    hilly = hilly,
                ),
            )
        }
    }.associateBy { it.index }

    val percent = dartRound(ratio * 100).toInt()
    val reason = "Plan angepasst: In Woche ${last.index + 1} hast du nur $percent % des " +
        "Wochen-Solls erreicht. Die verbleibenden Wochen bauen wieder von deinem " +
        "tatsächlichen Umfang auf – Ziel und Termin bleiben unverändert."

    return AdaptedPlan(
        plan = plan.copy(weeks = plan.weeks.map { rebuilt[it.index] ?: it }),
        adapted = true,
        reason = reason,
    )
}
