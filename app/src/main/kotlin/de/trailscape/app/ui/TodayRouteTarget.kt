package de.trailscape.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.today.TodayEffort
import de.trailscape.app.ui.today.TodayOffer
import de.trailscape.app.ui.today.offeredTarget
import de.trailscape.app.ui.today.todayEffort
import de.trailscape.core.RideSummary
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.adaptPlan
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.decideTodayRoute
import de.trailscape.core.restDayRideTarget
import de.trailscape.core.sessionsForDay
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Alles, was „heute" ausmacht — einmal gerechnet fuer jede Stelle, die davon
 * spricht: „Heute", der Knopf „★ Heute 45 km" auf der Karte und der
 * Losfahren-Dialog des Aufnahme-Knopfs.
 *
 * ## Warum ein gemeinsames Ergebnis
 * Bisher rechneten die Karte und der Dialog eine verkuerzte Kette
 * (`rememberTodayRoute`): angepasster Plan → heutige Einheit →
 * [decideTodayRoute]. Was fehlte, war der **Plan-Ruhetag** — den kannte nur
 * `today/TodayScreen.kt`. An einem planfreien Tag stand deshalb oben „Heute
 * ist Ruhetag" und auf der Karte die volle Tagesrunde. Jetzt gibt es genau
 * eine Kette, und [offer] ist die eine Antwort auf „was biete ich an?".
 *
 * @param displayPlan der an die gefahrene Realitaet angepasste Plan (`adaptPlan`).
 * @param currentWeek die laufende Planwoche — nur, wenn heute wirklich in ihr
 *   liegt; vor Planbeginn und nach Planende `null`.
 * @param planRestDay heute liegt in einer Planwoche, aber ohne Einheit.
 * @param offer die angebotene Runde, oder `null` (Zieltag, oder kein Ziel).
 */
data class TodayDecision(
    val displayPlan: TrainingPlan?,
    val todaySession: TrainingSession?,
    val currentWeek: TrainingWeek?,
    val planRestDay: Boolean,
    val route: TodayRoute,
    val effort: TodayEffort,
    val offer: TodayOffer?,
) {
    /** Einheiten der laufenden Planwoche (leer ohne Plan). */
    val weekSessions: List<TrainingSession> get() = currentWeek?.sessions.orEmpty()
}

/**
 * Die Tagesentscheidung als reine Funktion — ohne Compose, damit „Heute" und
 * die Karte nachweislich dasselbe rechnen.
 */
fun decideToday(
    insights: TrainingInsights,
    plan: TrainingPlan?,
    rides: List<RideSummary>,
    nowMs: Long,
): TodayDecision {
    val displayPlan = plan?.let { current ->
        adaptPlan(
            plan = current,
            rides = rides,
            currentCtl = insights.latest?.ctl,
            rideLoads = insights.rideLoads.mapValues { entry -> entry.value.load },
        ).plan
    }
    // Hoechstens eine Einheit ist das Tagesprogramm; `:core` setzt nie zwei
    // auf denselben Tag.
    val todaySession = displayPlan?.let { sessionsForDay(it, nowMs).firstOrNull() }
    val currentWeek = displayPlan?.let { p ->
        p.weeks.getOrNull(currentWeekIndex(p, nowMs))?.takeIf { nowMs >= it.start && nowMs < it.end }
    }
    val planRestDay = currentWeek != null && todaySession == null
    val route = decideTodayRoute(
        recommendation = insights.recommendation,
        session = todaySession,
        profile = insights.profile,
        recentRides = rides,
        weeklyTarget = insights.weeklyTarget,
    )
    val effort = todayEffort(route, planRestDay, currentWeek?.sessions.orEmpty())
    return TodayDecision(
        displayPlan = displayPlan,
        todaySession = todaySession,
        currentWeek = currentWeek,
        planRestDay = planRestDay,
        route = route,
        effort = effort,
        offer = offeredTarget(route, effort, restDayRideTarget(insights.profile, rides)),
    )
}

/**
 * „Jetzt", aber mit dem Kalendertag als Takt: beim Zurueckkehren in die App
 * ([LifecycleResumeEffect]) neu gelesen und nur dann uebernommen, wenn ein
 * neuer Tag begonnen hat.
 *
 * Vorher stand hier ein schlichtes `remember { LocalDateTime.now() }`. Wer die
 * App abends offen liess und morgens wieder hervorholte, sah den Vortag —
 * samt dessen Einheit und dessen Ruhetag. Nur beim Tageswechsel neu zu setzen
 * haelt die davon abhaengigen Rechnungen (Plan anpassen, Tagesentscheidung)
 * ruhig: ein gewoehnliches Zurueckkehren am selben Tag aendert nichts.
 */
@Composable
fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LifecycleResumeEffect(Unit) {
        val fresh = LocalDateTime.now()
        if (fresh.toLocalDate() != now.toLocalDate()) now = fresh
        onPauseOrDispose { }
    }
    return now
}

/**
 * [decideToday] fuer eine Composable-Stelle, mit den Flows des [AppViewModel]
 * und dem tagesgenauen [rememberNow].
 */
@Composable
fun rememberTodayDecision(appViewModel: AppViewModel, now: LocalDateTime = rememberNow()): TodayDecision {
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val nowMs = remember(now) { now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    return remember(insights, plan, rides, nowMs) { decideToday(insights, plan, rides, nowMs) }
}
