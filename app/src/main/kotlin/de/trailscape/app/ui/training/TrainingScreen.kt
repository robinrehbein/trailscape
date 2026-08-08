package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.core.assessFitness

/**
 * Trainings-Tab: Tagesempfehlung, Form-Kurve (CTL/ATL/TSB), Vitalwerte,
 * Wochenziel, Zielformular und Trainingsplan.
 *
 * Port von `lib/screens/training_screen.dart` (1.281 Zeilen). Die komplette
 * sportwissenschaftliche Auswertung liegt bereits fertig in
 * [AppViewModel.insights] ([de.trailscape.app.ui.TrainingInsights]); dieser
 * Screen ist reine Darstellung plus das Zielformular (Persistenz laeuft ueber
 * [AppViewModel.plan]/[AppViewModel.setPlan]).
 *
 * ## Bewusste Abweichungen vom Dart-Original
 *  * **Keine `_EntranceFade`-Animation.** Das Original blendet die ersten
 *    Karten gestaffelt ein (~40 ms Versatz je Karte). Hier liegen alle Karten
 *    in derselben `LazyColumn` wie die Planwochen — bei vielen Wochen wuerden
 *    recycelte Items erneut einblenden. `ui/rides/RidesScreen.kt` verzichtet
 *    aus demselben Grund bereits darauf.
 *  * **Kein `TweenAnimationBuilder`-Aequivalent** fuer Readiness-Score und die
 *    CTL/ATL/TSB-Kennzahlen — sie werden statisch gezeigt.
 *  * **Ampelpunkt statt Icon** bei den Vitalwerte-Zeilen (siehe KDoc von
 *    [SignalRow]): `:app` bindet nur `material-icons-core` ein, das Original
 *    benutzt dort ausschliesslich nicht enthaltene Outline-Icons.
 *  * **Zeitbudget-Hinweis antippbar.** Siehe KDoc von [WeekCard].
 *
 * Alle deutschen Texte, Zahlenformate (`formatKm`/`formatHours` aus `:core`)
 * und die fachliche Logik (Validierung mit `errorTooSoon`/`errorTooFar`,
 * `generatePlan`, `currentWeekIndex`, `weekKm`) sind unveraendert aus `:core`
 * bzw. dem Original uebernommen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(appViewModel: AppViewModel) {
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val assessment = remember(rides) { assessFitness(rides) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // Die aeussere Huelle (TrailscapeApp) hat die System-Insets bereits
        // aufgeloest und als Padding an den NavHost gegeben.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Training") },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Entspricht Darts `Center` + `ConstrainedBox(maxWidth: 640)`: auf
            // schmalen Bildschirmen nimmt die Liste die volle Breite, auf
            // breiten (Tablet) bleibt sie mittig und lesbar schmal.
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "today") { ReadinessCard(insights) }
                item(key = "form") { FormCard(insights) }
                item(key = "week") {
                    WeekCard(insights, onOpenMore = { appViewModel.requestTab(AppTab.MORE) })
                }
                item(key = "vitals") { VitalsCard(insights) }
                item(key = "fitness") { FitnessCard(assessment) }
                item(key = "goal") {
                    GoalCard(
                        plan = plan,
                        rides = rides,
                        onSetPlan = { appViewModel.setPlan(it) },
                    )
                }

                plan?.let { currentPlan ->
                    item(key = "plan-header") { PlanHeader(currentPlan) }
                    items(items = currentPlan.weeks, key = { "plan-week-${it.index}" }) { week ->
                        PlanWeekCard(week = week, plan = currentPlan, rides = rides)
                    }
                }
            }
        }
    }
}
