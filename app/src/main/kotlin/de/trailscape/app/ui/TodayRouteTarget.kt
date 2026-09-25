package de.trailscape.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.core.TodayRoute
import de.trailscape.core.adaptPlan
import de.trailscape.core.decideTodayRoute
import de.trailscape.core.sessionsForDay

/**
 * Die heutige Runde, wie sie „Heute" empfiehlt — als eine Rechnung fuer alle
 * Stellen, die sie ausserhalb des Heute-Tabs brauchen (der Knopf
 * „Heute · 45 km" auf der Karte, der Bereit-Dialog des Fahren-Knopfs).
 *
 * Die Kette ist dieselbe wie in `today/TodayScreen.kt`: der an die gefahrene
 * Realitaet angepasste Plan, daraus die heutige Einheit, daraus mit der
 * Tagesform die Entscheidung. [TodayRoute.target] ist `null` an einem Ruhetag
 * oder am Zieltag — dann gibt es keine Runde zu bauen.
 */
@Composable
fun rememberTodayRoute(appViewModel: AppViewModel): TodayRoute {
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    return remember(insights, plan, rides) {
        val displayPlan = plan?.let { current ->
            adaptPlan(
                plan = current,
                rides = rides,
                currentCtl = insights.latest?.ctl,
                rideLoads = insights.rideLoads.mapValues { entry -> entry.value.load },
            ).plan
        }
        decideTodayRoute(
            recommendation = insights.recommendation,
            session = displayPlan?.let { current -> sessionsForDay(current).firstOrNull() },
            profile = insights.profile,
            recentRides = rides,
            weeklyTarget = insights.weeklyTarget,
        )
    }
}
