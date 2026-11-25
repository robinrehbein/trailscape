package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.SectionEyebrow
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.planFeasibilityIdentityKey
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.core.TrainingSession
import de.trailscape.core.adaptPlan
import de.trailscape.core.assessPlanFeasibility
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.predictGoalFinish
import de.trailscape.core.projectedEventCtl
import de.trailscape.core.routeTargetForSession
import kotlinx.coroutines.launch

/**
 * # Die Sicht „Plan": „Schaffe ich mein Ziel?"
 *
 * Bis zum Umbau nach der Seitenkarte (`docs/ux/umbau.md`) war das das zweite
 * Segment des Tabs „Training". Den Tab gibt es nicht mehr; der Plan ist jetzt
 * das Segment „Plan" des Tabs „Indoor" (`ui/indoor/IndoorTabScreen.kt`), und
 * jeder Knopf, der „Plan", „Diese Woche" oder „Dein Ziel" sagt, springt ueber
 * [AppViewModel.requestPlan] genau hierher.
 *
 * Ein Scroll-Screen, von oben nach unten:
 *
 *  1. [header] — Titel und Segmente des Tabs (stellt der Aufrufer).
 *  2. **Dein Ziel** — [GoalOverviewCard]: Zielname, Distanz, Hoehenmeter,
 *     Datum, verbleibende Wochen; „Stand heute" gegen die eigene Zielzeit und
 *     der Satz, wo der Plan einen bis zum Renntag hinbringt (Prognose aus
 *     `:core`, [predictGoalFinish]). „Ändern" oeffnet das Zielformular als
 *     Blatt ([GoalEditorSheet]), „Wie wird das berechnet?" die Erklaerung
 *     ([PrognosisSheet]). Ohne Ziel steht dort [GoalSetupCard].
 *  3. Plan-Rezept ([PlanRecipeSection]) und, falls der Plan sein Ziel nicht
 *     traegt, [TrainingPlanFeasibilityCard].
 *  4. **Diese Woche im Plan** — die laufende Woche ([CurrentWeekCard]); alle
 *     Wochen ([PlanWeekCard]) hinter „Alle Wochen ansehen".
 *
 * ## Hinweise und Warnungen
 * Solange [AppViewModel.profileConfirmed] aus ist, steht oben ein Hinweis
 * ([UnconfirmedProfileNotice]). Pro Screen gibt es hoechstens EINE Flaeche in
 * Warnfarbe ([trainingNoticeLayout]).
 *
 * ## „Ziel festlegen" von aussen
 * [AppViewModel.pendingGoalEditor] oeffnet das Zielformular gleich mit — so
 * landet „Ziel festlegen" auf „Heute" mit einem Tipp im Formular.
 *
 * @param header Kopf des Tabs (Titel, Zahnrad, Segmente); erstes Listenelement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(appViewModel: AppViewModel, header: @Composable () -> Unit) {
    val coreTexts = LocalCoreTexts.current
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val planFeasibilityAckKey by appViewModel.planFeasibilityAckKey.collectAsStateWithLifecycle()
    // Lastwerte je Tour fuer Status-Zuordnung und Plan-Adaption.
    val rideLoadValues = remember(insights) {
        insights.rideLoads.mapValues { it.value.load }
    }
    val currentCtl = insights.latest?.ctl
    // Der ANGEZEIGTE Plan: an die gefahrene Realitaet angepasst (`:core`,
    // adaptPlan). Der gespeicherte Plan bleibt unveraendert — Format und
    // Referenz fuer kuenftige Vergleiche.
    val adaptedPlan = remember(plan, rides, rideLoadValues, currentCtl, coreTexts) {
        plan?.let {
            adaptPlan(
                plan = it,
                rides = rides,
                currentCtl = currentCtl,
                rideLoads = rideLoadValues,
                texts = coreTexts,
            )
        }
    }
    val displayPlan = adaptedPlan?.plan
    // Prognose fuer das Ziel: heute und — mit der Fitness, die der Plan bis
    // zum Renntag aufbaut — am Renntag.
    // Ein importierter Plan-Rezeptplan (recipe != null) hat kein Event, also
    // weder Prognose noch Tragfaehigkeitsurteil.
    val prediction = remember(displayPlan, rides, currentCtl, coreTexts) {
        displayPlan?.takeIf { it.recipe == null }?.let {
            predictGoalFinish(
                goal = it.goal,
                rides = rides,
                currentCtl = currentCtl,
                projectedCtl = projectedEventCtl(it, currentCtl),
                texts = coreTexts,
            )
        }
    }
    // Traegt der Plan sein eigenes Ziel? Bewertet wird der angepasste Stand;
    // quittiert wird ueber den Schluessel des gespeicherten Plans.
    // Mit der Prognose: nur so kennt das Urteil die Zielzeit-Luecke.
    val feasibility = remember(displayPlan, prediction, coreTexts) {
        displayPlan?.takeIf { it.recipe == null }?.let { assessPlanFeasibility(it, prognosis = prediction?.prognosis, texts = coreTexts) }
    }
    val feasibilityNotes = remember(displayPlan, feasibility, coreTexts) {
        if (displayPlan != null && feasibility != null) {
            feasibilityNoteLines(displayPlan, feasibility, texts = coreTexts)
        } else {
            emptyList()
        }
    }
    val planKey = remember(plan) { plan?.let { planFeasibilityIdentityKey(it) } }
    val openFeasibility = feasibility?.takeIf { !it.feasible && planKey != planFeasibilityAckKey }
    val currentWeek = remember(displayPlan) {
        displayPlan?.let { p -> p.weeks.getOrNull(currentWeekIndex(p)) }
    }

    // Ob Alter und Gewicht vom Nutzer stammen — sonst rechnet dieser Tab mit
    // den Annahmen aus `defaultTrainingProfile`.
    val profileConfirmed by appViewModel.profileConfirmed.collectAsStateWithLifecycle()
    // Hoechstens eine Warnflaeche: Stehen Tragfaehigkeit und Profil zugleich
    // an, bekommt nur die wichtigere die Karte (siehe TrainingNotices.kt).
    val notices = trainingNoticeLayout(
        feasibilityOpen = openFeasibility != null,
        profileMissing = !profileConfirmed,
    )

    var showGoalEditor by rememberSaveable { mutableStateOf(false) }
    var showPrognosis by rememberSaveable { mutableStateOf(false) }
    var allWeeks by rememberSaveable { mutableStateOf(false) }

    // „Ziel festlegen" von „Heute": das Blatt gleich mit oeffnen.
    val pendingGoalEditor by appViewModel.pendingGoalEditor.collectAsStateWithLifecycle()
    LaunchedEffect(pendingGoalEditor) {
        if (pendingGoalEditor) {
            showGoalEditor = true
            appViewModel.consumeGoalEditorRequest()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Passende Runde zu einer Einheit bauen; der Wunsch wechselt auf die Karte.
    val onPlanRoute: (TrainingSession) -> Unit = { session ->
        appViewModel.requestRouteGeneration(
            routeTargetForSession(
                session = session,
                profile = insights.profile,
                recentRides = rides,
                texts = coreTexts,
            ),
        )
    }

    Scaffold(
        // Die aeussere Huelle (TrailscapeApp) hat die System-Insets bereits
        // aufgeloest und als Padding an den NavHost gegeben.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Ohne dieses Padding erschiene die Meldung hinter der schwebenden
            // Navigationskapsel (siehe LocalFloatingNavigationBarSpace).
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(
                    bottom = LocalFloatingNavigationBarSpace.current,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth(),
                contentPadding = screenContentPadding(),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                item(key = "kopf") { header() }

                if (!profileConfirmed) {
                    item(key = "profil-hinweis") {
                        UnconfirmedProfileNotice(
                            asCard = notices.card == TrainingWarning.PROFILE,
                            onOpenProfile = {
                                appViewModel.requestMoreSection(MoreSection.PROFILE)
                            },
                        )
                    }
                }

                // ---------------------------------------------------- Dein Ziel
                val shownPlan = displayPlan
                if (shownPlan != null && prediction != null) {
                    item(key = "sec-goal") { SectionEyebrow(stringResource(R.string.training_screen_goal_eyebrow)) }
                    item(key = "goal") {
                        GoalOverviewCard(
                            goal = shownPlan.goal,
                            prediction = prediction,
                            onEdit = { showGoalEditor = true },
                            onExplain = { showPrognosis = true },
                            testWeek = fitnessTestWeek(shownPlan),
                            // Eine Zielzeit-Luecke nur einmal sagen: den konkreteren
                            // Hinweis (mit Tempo) unter der Karte, nicht beide.
                            targetTimeNoteShown = feasibility?.targetTimeMessage?.isNotBlank() == true,
                        )
                    }
                } else if (shownPlan?.recipe == null) {
                    item(key = "goal-setup") {
                        GoalSetupCard(
                            onSetUp = { showGoalEditor = true },
                            primary = true,
                        )
                    }
                }

                // Plan-Rezepte: Datei oeffnen, Plan/Einheit teilen (docs/plan-rezepte.md).
                item(key = "plan-rezept") { PlanRecipeSection(appViewModel, plan) }

                // Die Tragfaehigkeit ist die wichtigste Warnung
                // (trainingNoticeLayout) und steht deshalb immer als Karte.
                openFeasibility?.let { verdict ->
                    item(key = "plan-tragfaehigkeit") {
                        TrainingPlanFeasibilityCard(
                            feasibility = verdict,
                            onAdjustGoal = { showGoalEditor = true },
                            onAcknowledge = {
                                planKey?.let { appViewModel.acknowledgePlanFeasibility(it) }
                            },
                        )
                    }
                }

                // Leise Zeilen zu Zeitbudget und Zielzeit (kein zweites Warnfeld).
                if (shownPlan != null) {
                    feasibilityNotes.forEachIndexed { index, note ->
                        item(key = "plan-hinweis-$index") { QuietNoteLine(text = note) }
                    }
                }

                // ------------------------------------------ Diese Woche im Plan
                if (shownPlan != null) {
                    currentWeek?.let { week ->
                        item(key = "sec-week") {
                            SectionEyebrow(stringResource(R.string.training_screen_week_eyebrow))
                        }
                        item(key = "week-now") {
                            CurrentWeekCard(
                                week = week,
                                plan = shownPlan,
                                rides = rides,
                                onPlanRoute = onPlanRoute,
                                rideLoads = rideLoadValues,
                                insights = insights,
                                headline = stringResource(
                                    R.string.training_screen_week_headline,
                                    week.index + 1,
                                    shownPlan.weeks.size,
                                    coreTexts.training.weekKind(week.kind),
                                ),
                            )
                        }
                    }
                    if (adaptedPlan?.adapted == true) {
                        adaptedPlan.reason?.let { reason ->
                            item(key = "plan-adaption") { PlanAdaptionNote(reason) }
                        }
                    }
                    item(key = "all-weeks-toggle") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { allWeeks = !allWeeks }) {
                                Text(
                                    stringResource(
                                        if (allWeeks) {
                                            R.string.training_screen_show_less_action
                                        } else {
                                            R.string.training_screen_all_weeks_action
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    if (allWeeks) {
                        items(items = shownPlan.weeks, key = { "plan-week-${it.index}" }) { week ->
                            PlanWeekCard(
                                week = week,
                                plan = shownPlan,
                                rides = rides,
                                onPlanRoute = onPlanRoute,
                                rideLoads = rideLoadValues,
                                insights = insights,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGoalEditor) {
        GoalEditorSheet(
            plan = plan,
            rides = rides,
            onSetPlan = { appViewModel.setPlan(it) },
            onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
            onDismiss = { showGoalEditor = false },
            currentCtl = currentCtl,
            profileWeeklyHours = insights.profile.weeklyHours,
        )
    }
    val sheetPlan = displayPlan
    if (showPrognosis && sheetPlan != null && prediction != null) {
        PrognosisSheet(
            goal = sheetPlan.goal,
            prediction = prediction,
            currentCtl = currentCtl,
            onDismiss = { showPrognosis = false },
        )
    }
}
