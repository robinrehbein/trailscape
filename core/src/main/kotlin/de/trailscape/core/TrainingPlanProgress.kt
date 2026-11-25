package de.trailscape.core

import de.trailscape.core.i18n.CoreTexts
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

/** Beschriftung je Status — fuer die Oberflaeche, in der Sprache von [texts]. */
fun planSessionStatusLabel(status: PlanSessionStatus, texts: CoreTexts): String =
    texts.training.planSessionStatus(status)

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
    /**
     * Weitere Touren desselben Tages, die mitgezaehlt wurden (siehe
     * [weekSessionProgress], „Mehrere Fahrten an einem Tag"); [riddenKm],
     * [riddenLoad] und [riddenMinutes] sind dann Summen.
     */
    val extraRideIds: List<String> = emptyList(),
    /** Bewegungszeit der zugeordneten Tour(en) in Minuten, falls bekannt. */
    val riddenMinutes: Int? = null,
    /** Mindestens eine der zugeordneten Touren war drinnen auf der Rolle ([RideInfo.indoor]). */
    val riddenIndoor: Boolean = false,
    /** Mindestens eine der zugeordneten Touren war draussen. */
    val riddenOutdoor: Boolean = false,
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
 * Erfuellungsgrad ist das Maximum aus km-Anteil, (falls [rideLoads] die
 * Tour kennt und die Einheit ein Last-Ziel traegt) Last-Anteil und
 * Zeit-Anteil ([sessionTimeShare]); ab [sessionDoneShare] gilt ERLEDIGT,
 * darunter TEILWEISE. Ohne passende Tour entscheidet die Uhr: VERPASST erst,
 * wenn auch der Toleranztag vorbei ist.
 *
 * ## Draussen und drinnen gleich
 * Eine Indoor-Fahrt ([RideInfo.indoor]) ist eine gefahrene Tour und erledigt
 * eine Einheit genauso wie eine Fahrt draussen. Weil ihre Kilometer virtuell
 * sind (am Berg der Strecke langsamer, im ERG-Modus nach Zeit gefahren), zaehlt
 * der Zeit-Anteil mit — bei Indoor-Fahrten auch fuer Einheiten aus Plaenen ohne
 * [TrainingSession.durationMin] (dann die Dauer, die auch die ERG-Einheit faehrt).
 *
 * ## Mehrere Fahrten an einem Tag
 * Reicht die beste Tour allein nicht fuer ERLEDIGT, zaehlen die uebrigen noch
 * freien Touren **genau vom Tag der Einheit** dazu (km, Last und Zeit als
 * Summe) — draussen abgebrochen und drinnen zu Ende gefahren ist eine Einheit.
 * Touren vom Nachbartag werden nicht dazugenommen: Sie gehoeren moeglicherweise
 * zur Einheit dieses Tages.
 *
 * @param rideLoads Last je Tour-ID (z. B. aus `TrainingInsights.rideLoads`);
 *   leer, wenn keine Lasten vorliegen — dann entscheidet allein die Distanz.
 */
fun weekSessionProgress(
    week: TrainingWeek,
    rides: List<RideInfo>,
    now: Long? = null,
    rideLoads: Map<String, Double> = emptyMap(),
): List<PlanSessionProgress> {
    val nowMs = now ?: System.currentTimeMillis()
    val weekStartDate = dartLocalOf(week.start).toLocalDate()
    val earliest = weekStartDate.minusDays(sessionMatchToleranceDays.toLong())
    val latest = weekStartDate.plusDays(6L + sessionMatchToleranceDays)

    // Kandidatinnen einmal vorbereiten: Kalendertag + Distanz.
    data class Candidate(val ride: RideInfo, val date: java.time.LocalDate) {
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
        var group = listOf(best.ride)
        if (fulfilledShare(session, group, rideLoads) < sessionDoneShare && best.date == date) {
            val sameDay = candidates.filter { !it.taken && it.date == date }
            sameDay.forEach { it.taken = true }
            group = group + sameDay.map { it.ride }
        }
        val fulfilled = fulfilledShare(session, group, rideLoads)
        val loads = group.mapNotNull { rideLoads[it.id] }
        results[session] = PlanSessionProgress(
            session = session,
            status = if (fulfilled >= sessionDoneShare) {
                PlanSessionStatus.ERLEDIGT
            } else {
                PlanSessionStatus.TEILWEISE
            },
            rideId = best.ride.id,
            riddenKm = group.sumOf { it.stats.distanceKm },
            riddenLoad = if (loads.isEmpty()) null else loads.sum(),
            extraRideIds = group.drop(1).map { it.id },
            riddenMinutes = groupMinutes(group),
            riddenIndoor = group.any { it.indoor },
            riddenOutdoor = group.any { !it.indoor },
        )
    }

    // Ergebnis in Originalreihenfolge der Woche, nicht in Zuordnungsreihenfolge.
    return week.sessions.map { results.getValue(it) }
}

/** Bewegungszeit (sonst Gesamtdauer) einer Tour in Sekunden, `null` ohne beides. */
private fun rideSeconds(ride: RideInfo): Int? =
    (ride.stats.movingTimeS ?: ride.stats.durationS)?.takeIf { it > 0 }

/** Summe der Zeiten in Minuten; `null`, wenn keine Tour eine Zeit kennt. */
private fun groupMinutes(group: List<RideInfo>): Int? {
    val seconds = group.mapNotNull(::rideSeconds)
    return if (seconds.isEmpty()) null else seconds.sum() / 60
}

/**
 * Zeit-Anteil der Touren [group] an der Einheit [session], oder `null`.
 *
 * Bezugsgroesse ist [TrainingSession.durationMin]. Fehlt sie (Plan aus der
 * Zeit vor dem Feld), zaehlt der Zeit-Anteil nur, wenn eine der Touren drinnen
 * war — dann gilt die Dauer, die auch die ERG-Einheit faehrt
 * (`sessionDurationMinutes`). Fuer reine Fahrten draussen bleibt es in diesem
 * Fall bei km und Last wie bisher.
 */
fun sessionTimeShare(session: TrainingSession, group: List<RideInfo>): Double? {
    val minutes = groupMinutes(group)?.takeIf { it > 0 } ?: return null
    val planned = session.durationMin?.takeIf { it > 0 }
        ?: if (group.any { it.indoor }) de.trailscape.core.indoor.sessionDurationMinutes(session) else null
    return planned?.let { minutes.toDouble() / it }
}

/** Erfuellungsgrad: Maximum aus km-, Last- und Zeit-Anteil (Regeln bei [weekSessionProgress]). */
private fun fulfilledShare(session: TrainingSession, group: List<RideInfo>, rideLoads: Map<String, Double>): Double {
    val km = group.sumOf { it.stats.distanceKm }
    val kmShare = if (session.targetKm > 0) km / session.targetKm else 1.0
    val loads = group.mapNotNull { rideLoads[it.id] }
    val loadShare = session.targetLoad
        ?.takeIf { it > 0 && loads.isNotEmpty() }
        ?.let { target -> loads.sum() / target }
    return max(max(kmShare, loadShare ?: 0.0), sessionTimeShare(session, group) ?: 0.0)
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
 * (`buildPlanWeekSessions` in `PlanWeeks.kt`, mit den gespeicherten
 * [TrainingPlan.preferences] und [TrainingPlan.planSpeedKmh]) — Startvolumen
 * ist das **tatsaechlich erreichte** Maximum der letzten
 * [adaptLookbackWeeks] abgeschlossenen Wochen, der +15-%-Deckel
 * ([MAX_WEEKLY_INCREASE]) gilt unveraendert. Phasen und Progressionsstufen
 * der Qualitaetseinheiten laufen ueber den ganzen Plan weiter (gezaehlt an den
 * bereits geplanten Wochen, bei Altplaenen auch an deren Intervallen alten
 * Formats). Der Taper rechnet mit dem Spitzenumfang der **neu gebauten**
 * Aufbauwochen (ohne solche: mit dem tatsaechlich erreichten Umfang).
 * Blockstruktur (Aufbau/Erholung/Taper/Zielwoche), Wochengrenzen und das
 * Zieldatum bleiben stehen; die Zielwoche bleibt woertlich unveraendert und
 * zaehlt fest in die Pruefung der harten Tage.
 *
 * ## Was NICHT
 * Der gespeicherte Plan wird **nicht ueberschrieben** — das Ergebnis ist ein
 * abgeleiteter Anzeige-Plan. Das Plan-JSON ist mit Web-App und Sync-Server
 * abgestimmt; eine stille Mutation wuerde ausserdem die Referenz zerstoeren,
 * gegen die kommende Wochen verglichen werden. Aufrufer zeigen
 * `adaptPlan(...).plan` an (und bewerten ihn mit [assessPlanFeasibility]),
 * persistieren aber weiterhin das Original.
 *
 * @param currentCtl aktuelle CTL fuer den Lastdeckel der neu gerechneten
 *   Aufbauwochen (≤ 1,30 × 7 × CTL); `null` = nur die km-Rampe.
 * @param rideLoads Last je Tour-ID (aus `TrainingInsights.rideLoads`).
 */
fun adaptPlan(
    plan: TrainingPlan,
    rides: List<RideInfo>,
    now: Long? = null,
    currentCtl: Double? = null,
    rideLoads: Map<String, Double> = emptyMap(),
    texts: CoreTexts,
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
        weekRideList.sumOf { rideLoads.getValue(it.id) } / budget
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

    // Neu gerechnet wird mit derselben Maschinerie und denselben Annahmen
    // (Praeferenzen, Tempo), mit denen der Plan entstand; Phasen und
    // Progressionsstufen laufen ueber den ganzen Plan weiter.
    val fromIndex = remaining.minOf { it.index }
    val kinds = plan.weeks.map { it.kind }
    val input = PlanBuildInput(
        goal = plan.goal,
        level = plan.level,
        texts = texts,
        speedKmh = plan.planSpeedKmh,
        preferences = plan.preferences,
        currentCtl = currentCtl,
        kinds = kinds,
        startKm = newStartKm,
        peakKm = peakKmFor(plan.goal, newStartKm),
    )
    // Die Zielwoche bleibt woertlich — sie wird deshalb gar nicht erst neu
    // gebaut, sondern zaehlt mit ihren harten Einheiten fest in die Pruefung
    // der harten Tage, damit der neu gebaute Taper zu ihr passt.
    val sessions = buildPlanWeekSessions(
        input = input,
        state = planBuildStateBefore(plan.weeks.filter { it.index < fromIndex }, newStartKm)
            .apply { holdNextBuild = true },
        fromIndex = fromIndex,
        priorWeeks = plan.weeks.map { it.sessions },
        frozen = plan.weeks.filter { it.kind == WeekKind.ZIELWOCHE }.map { it.index }.toSet(),
    )

    val rebuilt = remaining.map { week ->
        if (week.kind == WeekKind.ZIELWOCHE) {
            week
        } else {
            val weekSessions = sessions[week.index]
            week.copy(targetKm = weekSessions.sumOf { it.targetKm }, sessions = weekSessions)
        }
    }.associateBy { it.index }

    val percent = dartRound(ratio * 100).toInt()
    val reason = texts.training.planAdapted(last.index + 1, percent)

    return AdaptedPlan(
        plan = plan.copy(weeks = plan.weeks.map { rebuilt[it.index] ?: it }),
        adapted = true,
        reason = reason,
    )
}
