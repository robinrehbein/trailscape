package de.trailscape.app.ui.training

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiLargeTopAppBar
import de.trailscape.app.ui.components.SectionEyebrow
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.components.oneUiTopAppBarScrollBehavior
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.defaultTrainingProfile
import de.trailscape.app.ui.planFeasibilityIdentityKey
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.TrainingSession
import de.trailscape.core.adaptPlan
import de.trailscape.core.assessFitness
import de.trailscape.core.assessPlanFeasibility
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.predictGoalFinish
import de.trailscape.core.projectedEventCtl
import de.trailscape.core.routeTargetForSession
import de.trailscape.core.weekKindLabels
import kotlinx.coroutines.launch

/**
 * # Trainings-Tab: „Schaffe ich mein Ziel?"
 *
 * Gestaltungsvorlage ist der Screen `#s-training` des Redesigns „Klartext"
 * (`docs/design/prototyp-klartext.html`, samt der Blaetter `#m-prognose` und
 * `#m-form`). Ein Scroll-Screen, von oben nach unten:
 *
 *  1. **Dein Ziel** — [GoalOverviewCard]: Zielname, Distanz, Hoehenmeter,
 *     Datum, verbleibende Wochen; „Stand heute" gegen die eigene Zielzeit, eine
 *     kleine Skala und der Satz, wo der Plan einen bis zum Renntag hinbringt
 *     (Prognose aus `:core`, [predictGoalFinish]). „Ändern" oeffnet das
 *     Zielformular als Blatt ([GoalEditorSheet]), „Wie wird das berechnet?"
 *     die Erklaerung ([PrognosisSheet]). Ohne Ziel steht dort
 *     [GoalSetupCard]. Direkt darunter, falls der Plan sein Ziel nicht traegt,
 *     [TrainingPlanFeasibilityCard] — sie stand frueher auf der Startseite.
 *  2. **Diese Woche im Plan** — nur die laufende Woche ([CurrentWeekCard]) mit
 *     beschriftetem „Runde"-Knopf am heutigen Tag; alle Wochen
 *     ([PlanWeekCard]) erst hinter „Alle Wochen ansehen". Die
 *     Anpassungs-Notiz ([PlanAdaptionNote]) bleibt, kompakt.
 *  3. **Deine Form** — eine antippbare Karte ([FormSummaryCard]); alles
 *     Weitere (Kurven, Rampenrate, Belastungsverhaeltnis, Wochenlast,
 *     Fitnesslevel) liegt eine Ebene tiefer in [FormSheet] unter „Alle Werte".
 *  4. **Körperwerte** — [VitalsTiles] mit Quellzeile; die Begruendungen im
 *     Blatt [VitalsSheet].
 *
 * Die fruehere Coach-Karte der Form ([FormCoachCard]) steht nicht mehr im Tab:
 * Ihr Inhalt lebt im Formblatt — zwei Stellen fuer dieselbe Deutung waeren die
 * Doppelung, die das Redesign abbaut.
 *
 * ## Was hier nicht gerechnet wird
 * Die sportwissenschaftliche Auswertung liegt fertig in
 * [AppViewModel.insights] ([de.trailscape.app.ui.TrainingInsights]), die
 * Prognose und die Klartext-Helfer in `:core` (`GoalPrognosis.kt`,
 * `PlanPlainText.kt`). Persistenz des Plans laeuft ueber
 * [AppViewModel.plan]/[AppViewModel.setPlan].
 *
 * ## Leerzustand
 * Bei leerer Tourenliste steht [TrainingEmptyState] ganz oben: Fitness und
 * Erholung brauchen ~2 Wochen Historie, plus die zwei kuerzesten Wege zu
 * echten Daten. Die Formkarte entfaellt dann, bis es eine Kurve gibt; das
 * Zielformular und die Koerperwerte (die auch ohne Touren aus Health Connect
 * kommen koennen) bleiben. Einen gefuellten Knopf hat dann nur der
 * Leerzustand; „Ziel festlegen" tritt neutral zurueck.
 *
 * ## Hinweis zum Profil
 * Solange [AppViewModel.profileConfirmed] aus ist, steht oben ein kompakter,
 * antippbarer Hinweis ([UnconfirmedProfileNotice]), dass die Zahlen auf
 * Standardwerten beruhen.
 *
 * ## Kopfzeile und Bodenfreiheit
 * Die grosse One-UI-Kopfzeile ([OneUiLargeTopAppBar]) traegt den Titel
 * „Training" und das ⚙ — dieselbe Stelle wie in den anderen Listen-Tabs. Der
 * Inhalt scrollt unter der schwebenden Navigationskapsel hindurch; die Liste
 * traegt dafuer [screenContentPadding] (mit
 * [LocalFloatingNavigationBarSpace]), der `SnackbarHost` dieselbe Zahl.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(appViewModel: AppViewModel) {
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val vitalsSyncedAt by appViewModel.vitalsSyncedAt.collectAsStateWithLifecycle()
    val planFeasibilityAckKey by appViewModel.planFeasibilityAckKey.collectAsStateWithLifecycle()
    val assessment = remember(rides) { assessFitness(rides) }
    // Lastwerte je Tour fuer Status-Zuordnung und Plan-Adaption.
    val rideLoadValues = remember(insights) {
        insights.rideLoads.mapValues { it.value.load }
    }
    val currentCtl = insights.latest?.ctl
    // Der ANGEZEIGTE Plan: an die gefahrene Realitaet angepasst (`:core`,
    // adaptPlan). Der gespeicherte Plan bleibt unveraendert — Format und
    // Referenz fuer kuenftige Vergleiche.
    val adaptedPlan = remember(plan, rides, rideLoadValues, currentCtl) {
        plan?.let {
            adaptPlan(
                plan = it,
                rides = rides,
                currentCtl = currentCtl,
                rideLoads = rideLoadValues,
            )
        }
    }
    val displayPlan = adaptedPlan?.plan
    // Prognose fuer das Ziel: heute und — mit der Fitness, die der Plan bis
    // zum Renntag aufbaut — am Renntag.
    val prediction = remember(displayPlan, rides, currentCtl) {
        displayPlan?.let {
            predictGoalFinish(
                goal = it.goal,
                rides = rides,
                currentCtl = currentCtl,
                projectedCtl = projectedEventCtl(it, currentCtl),
            )
        }
    }
    // Traegt der Plan sein eigenes Ziel? Bewertet wird der angepasste Stand;
    // quittiert wird ueber den Schluessel des gespeicherten Plans.
    val feasibility = remember(displayPlan) { displayPlan?.let { assessPlanFeasibility(it) } }
    val planKey = remember(plan) { plan?.let { planFeasibilityIdentityKey(it) } }
    val currentWeek = remember(displayPlan) {
        displayPlan?.let { p -> p.weeks.getOrNull(currentWeekIndex(p)) }
    }

    // Ob Alter und Gewicht vom Nutzer stammen — sonst rechnet dieser Tab mit
    // den Annahmen aus `defaultTrainingProfile`.
    val profileConfirmed by appViewModel.profileConfirmed.collectAsStateWithLifecycle()
    // Der Kurzschlaefer-Hinweis: hoechstens einmal pro Monat
    // (`shouldShowShortSleeperHint`), entschieden im ViewModel.
    val showShortSleeperHint by appViewModel.shortSleeperHintVisible
        .collectAsStateWithLifecycle()

    var showGoalEditor by rememberSaveable { mutableStateOf(false) }
    var showPrognosis by rememberSaveable { mutableStateOf(false) }
    var showForm by rememberSaveable { mutableStateOf(false) }
    var showVitals by rememberSaveable { mutableStateOf(false) }
    var allWeeks by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val scrollBehavior = oneUiTopAppBarScrollBehavior()
    // Passende Runde zu einer Einheit bauen; der Wunsch wechselt auf die Karte.
    val onPlanRoute: (TrainingSession) -> Unit = { session ->
        appViewModel.requestRouteGeneration(
            routeTargetForSession(
                session = session,
                profile = insights.profile,
                recentRides = rides,
            ),
        )
    }

    Scaffold(
        // Die aeussere Huelle (TrailscapeApp) hat die System-Insets bereits
        // aufgeloest und als Padding an den NavHost gegeben.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OneUiLargeTopAppBar(
                title = "Training",
                scrollBehavior = scrollBehavior,
                actions = {
                    SettingsAction(onClick = { appViewModel.requestTab(AppTab.MORE) })
                },
            )
        },
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
                if (rides.isEmpty()) {
                    item(key = "empty") {
                        TrainingEmptyState(
                            onRecord = { appViewModel.requestTab(AppTab.MAP) },
                            onImport = { appViewModel.requestMoreSection(MoreSection.BACKUP) },
                        )
                    }
                }

                if (!profileConfirmed) {
                    item(key = "profil-hinweis") {
                        UnconfirmedProfileNotice(
                            onOpenProfile = {
                                appViewModel.requestMoreSection(MoreSection.PROFILE)
                            },
                        )
                    }
                }

                // ---------------------------------------------------- Dein Ziel
                val shownPlan = displayPlan
                if (shownPlan != null && prediction != null) {
                    item(key = "goal") {
                        GoalOverviewCard(
                            goal = shownPlan.goal,
                            prediction = prediction,
                            onEdit = { showGoalEditor = true },
                            onExplain = { showPrognosis = true },
                        )
                    }
                } else {
                    item(key = "goal-setup") {
                        GoalSetupCard(
                            onSetUp = { showGoalEditor = true },
                            primary = rides.isNotEmpty(),
                        )
                    }
                }

                feasibility
                    ?.takeIf { !it.feasible && planKey != planFeasibilityAckKey }
                    ?.let { verdict ->
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

                // ------------------------------------------ Diese Woche im Plan
                if (shownPlan != null) {
                    currentWeek?.let { week ->
                        item(key = "sec-week") {
                            SectionEyebrow(
                                "Diese Woche im Plan · Woche ${week.index + 1} von " +
                                    "${shownPlan.weeks.size}, ${weekKindLabels.getValue(week.kind)}",
                            )
                        }
                        item(key = "week-now") {
                            CurrentWeekCard(
                                week = week,
                                plan = shownPlan,
                                rides = rides,
                                onPlanRoute = onPlanRoute,
                                rideLoads = rideLoadValues,
                            )
                        }
                    }
                    if (adaptedPlan.adapted) {
                        adaptedPlan.reason?.let { reason ->
                            item(key = "plan-adaption") { PlanAdaptionNote(reason) }
                        }
                    }
                    item(key = "all-weeks-toggle") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { allWeeks = !allWeeks }) {
                                Text(if (allWeeks) "Weniger anzeigen" else "Alle Wochen ansehen")
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
                            )
                        }
                    }
                }

                // --------------------------------------------------- Deine Form
                if (rides.isNotEmpty() || insights.fitness.latest != null) {
                    item(key = "sec-form") { SectionEyebrow("Deine Form") }
                    item(key = "form") {
                        FormSummaryCard(insights, onClick = { showForm = true })
                    }
                }

                // -------------------------------------------------- Koerperwerte
                item(key = "sec-werte") { SectionEyebrow("Körperwerte") }
                item(key = "vitals") {
                    VitalsTiles(
                        insights = insights,
                        syncedAt = vitalsSyncedAt,
                        onOpenDetails = { showVitals = true },
                        showShortSleeperHint = showShortSleeperHint,
                        onShortSleeperHintShown = appViewModel::markShortSleeperHintShown,
                    )
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
    if (showForm) {
        FormSheet(
            insights = insights,
            assessment = assessment,
            showDetails = rides.isNotEmpty(),
            onOpenProfile = {
                showForm = false
                appViewModel.requestMoreSection(MoreSection.PROFILE)
            },
            onDismiss = { showForm = false },
        )
    }
    if (showVitals) {
        VitalsSheet(insights = insights, onDismiss = { showVitals = false })
    }
}

/**
 * Der kompakte Hinweis, dass die Zahlen dieses Tabs auf Standardwerten
 * beruhen.
 *
 * Antippbar, weil ein Hinweis ohne Weg zur Loesung nur aergert: Der Tipp
 * springt in die Profilkarte der Einstellungen — genau dorthin, wo Alter und
 * Gewicht hingehoeren.
 */
@Composable
private fun UnconfirmedProfileNotice(onOpenProfile: () -> Unit) {
    NoticeBox(
        icon = Icons.Filled.Info,
        color = LocalSignalColors.current.caution,
        text = "Alter und Gewicht fehlen – bis dahin rechnen wir mit " +
            "${defaultTrainingProfile.ageYears} Jahren und " +
            "${defaultTrainingProfile.weightKg.toInt()} kg, die Zahlen hier sind grob. " +
            "Tippe hier, um sie einzutragen.",
        modifier = Modifier.clickable(onClick = onOpenProfile),
    )
}

/**
 * Was der Trainings-Tab kann, solange er noch keine Tour kennt.
 *
 * Textbudget: zwei kurze Saetze, dann die Knoepfe. Die Groessenordnung
 * („rund zwei Wochen") bleibt die einzige Ausnahme vom Ein-Satz-Budget der
 * uebrigen Leerzustaende, weil sie verhindert, dass ein leerer Trainings-Tab
 * am zweiten Tag wie ein Fehler wirkt.
 */
@Composable
private fun TrainingEmptyState(onRecord: () -> Unit, onImport: () -> Unit) {
    EmptyState(
        title = "Hier entsteht dein Trainingsbild",
        body = "Trailscape baut aus deinen Touren dein Trainingsbild auf. Belastbar wird " +
            "es erst mit rund zwei Wochen Historie — am schnellsten bist du dort mit " +
            "importierten Touren.",
        actions = {
            Button(onClick = onRecord) { Text("Tour aufzeichnen") }
            NeutralButton(onClick = onImport) { Text("Alte Touren importieren") }
        },
    )
}
