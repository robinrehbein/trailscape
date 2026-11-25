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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.progress.LifetimeCard
import de.trailscape.app.ui.erfolge.ErfolgeEntry
import de.trailscape.app.ui.erfolge.ErfolgeSheet
import de.trailscape.app.ui.progress.MilestonesCard
import de.trailscape.app.ui.progress.RecordsCard
import de.trailscape.app.ui.progress.ChallengesCard
import de.trailscape.app.ui.progress.FitnessTrendCard
import de.trailscape.app.ui.progress.TrendCard
import de.trailscape.app.ui.progress.YearGoalCard
import de.trailscape.app.ui.progress.YearGoalDialog
import de.trailscape.app.ui.progress.rememberRideFacts
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.rememberNow
import de.trailscape.core.challengeHistory
import de.trailscape.core.challengeProgress
import de.trailscape.core.fitnessComparison
import de.trailscape.core.lifetimeTotals
import de.trailscape.core.milestoneProgress
import de.trailscape.core.personalRecords
import de.trailscape.core.recentTrend
import de.trailscape.core.suggestYearGoalKm
import de.trailscape.core.weekStreak
import de.trailscape.core.weeklyBuckets
import de.trailscape.core.yearGoalProgress
import java.time.YearMonth
import androidx.compose.runtime.mutableIntStateOf
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.components.SectionEyebrow
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.planFeasibilityIdentityKey
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.core.TrainingSession
import de.trailscape.core.adaptPlan
import de.trailscape.core.assessFitness
import de.trailscape.core.assessPlanFeasibility
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.predictGoalFinish
import de.trailscape.core.projectedEventCtl
import de.trailscape.core.routeTargetForSession
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
 *     Anpassungs-Notiz ([PlanAdaptionNote]) ist eine schlichte Textzeile.
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
 * ## Hinweise und Warnungen
 * Solange [AppViewModel.profileConfirmed] aus ist, steht oben ein Hinweis
 * ([UnconfirmedProfileNotice]) mit der Aktion „Profil öffnen", dass die
 * Zahlen auf Standardwerten beruhen. Pro Screen gibt es hoechstens EINE
 * Flaeche in Warnfarbe: Steht zugleich [TrainingPlanFeasibilityCard] an,
 * wird der Profil-Hinweis zur ruhigen Zeile ([trainingNoticeLayout]).
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
    val coreTexts = LocalCoreTexts.current
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
    // Der Kurzschlaefer-Hinweis: hoechstens einmal pro Monat
    // (`shouldShowShortSleeperHint`), entschieden im ViewModel.
    val showShortSleeperHint by appViewModel.shortSleeperHintVisible
        .collectAsStateWithLifecycle()
    // Hoechstens eine Warnflaeche: Stehen Tragfaehigkeit und Profil zugleich
    // an, bekommt nur die wichtigere die Karte (siehe TrainingNotices.kt).
    val notices = trainingNoticeLayout(
        feasibilityOpen = openFeasibility != null,
        profileMissing = !profileConfirmed,
    )

    var showGoalEditor by rememberSaveable { mutableStateOf(false) }
    var showPrognosis by rememberSaveable { mutableStateOf(false) }
    var showForm by rememberSaveable { mutableStateOf(false) }
    var showVitals by rememberSaveable { mutableStateOf(false) }
    var allWeeks by rememberSaveable { mutableStateOf(false) }

    // Zwei Sichten: „Fortschritt" (was war und wohin es geht — Verlauf,
    // Jahresziel, Bestwerte, Meilensteine, Form, Koerperwerte) und „Plan"
    // (das Ziel-Event und seine Wochen). Fortschritt steht vorn: Den hat
    // jeder, einen Plan nicht.
    var segment by rememberSaveable { mutableIntStateOf(SEGMENT_PROGRESS) }
    val now = rememberNow()
    val today = now.toLocalDate()
    val facts = rememberRideFacts(rides)
    val fitnessTrend = remember(rides, today) {
        fitnessComparison(rides, { localOfEpochMs(it).toLocalDate() }, today)
    }
    val buckets = remember(facts, today) { weeklyBuckets(facts, today) }
    val trend = remember(facts, today) { recentTrend(facts, today) }
    val challenges = remember(facts, today) { challengeProgress(facts, today) }
    val lastMonthChallenges = remember(facts, today) {
        val lastMonth = YearMonth.from(today).minusMonths(1)
        challengeHistory(facts, today).firstOrNull { it.month == lastMonth }
    }
    val records = remember(facts) { personalRecords(facts) }
    val milestones = remember(facts) { milestoneProgress(facts) }
    val lifetime = remember(facts) { lifetimeTotals(facts) }
    val bestStreak = remember(facts, today) { weekStreak(facts, today).best }
    val yearGoalSetting by appViewModel.yearGoal.collectAsStateWithLifecycle()
    val yearGoal = remember(facts, yearGoalSetting, today) {
        yearGoalSetting?.let { yearGoalProgress(facts, it, today) }
    }
    val suggestedYearGoalKm = remember(facts, today) { suggestYearGoalKm(facts, today) }
    var showYearGoal by rememberSaveable { mutableStateOf(false) }
    var showErfolge by rememberSaveable { mutableStateOf(false) }

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
                item(key = "kopf") {
                    ScreenHeader(
                        title = stringResource(R.string.training_screen_title),
                        actions = {
                            SettingsAction(onClick = { appViewModel.requestTab(AppTab.MORE) })
                        },
                    )
                }
                item(key = "sicht") {
                    PillSegments(
                        options = listOf(
                            stringResource(R.string.progress_segment_progress),
                            stringResource(R.string.progress_segment_plan),
                        ),
                        selectedIndex = segment,
                        onSelect = { segment = it },
                        // Wechselt die Ansicht, ist also ein Tab und keine Auswahl.
                        role = Role.Tab,
                    )
                }
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
                            asCard = notices.card == TrainingWarning.PROFILE,
                            onOpenProfile = {
                                appViewModel.requestMoreSection(MoreSection.PROFILE)
                            },
                        )
                    }
                }

                // ---------------------------------------------------- Dein Ziel
                if (segment == SEGMENT_PROGRESS && facts.isNotEmpty()) {
                    item(key = "sec-trend") { SectionEyebrow(stringResource(R.string.progress_trend_eyebrow)) }
                    item(key = "trend") { TrendCard(buckets = buckets, trend = trend) }
                    item(key = "challenges") {
                        ChallengesCard(
                            progress = challenges,
                            month = today,
                            lastMonth = lastMonthChallenges,
                        )
                    }
                    // Erfolge (docs/erfolge.md); leer, wenn sie ausgeschaltet sind.
                    item(key = "erfolge") { ErfolgeEntry(appViewModel, onOpen = { showErfolge = true }) }
                    item(key = "jahresziel") {
                        YearGoalCard(
                            progress = yearGoal,
                            year = today.year,
                            suggestedKm = suggestedYearGoalKm,
                            onEdit = { showYearGoal = true },
                        )
                    }
                    item(key = "bestwerte") {
                        RecordsCard(records = records, onOpenRide = appViewModel::requestRideDetail)
                    }
                    item(key = "meilensteine") { MilestonesCard(milestones) }
                    item(key = "gesamt") { LifetimeCard(totals = lifetime, bestStreak = bestStreak) }
                }

                val shownPlan = displayPlan.takeIf { segment == SEGMENT_PLAN }
                if (segment == SEGMENT_PROGRESS) {
                    Unit
                } else if (shownPlan != null && prediction != null) {
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
                            primary = rides.isNotEmpty(),
                        )
                    }
                }

                // Plan-Rezepte: Datei oeffnen, Plan/Einheit teilen (docs/plan-rezepte.md).
                if (segment == SEGMENT_PLAN) {
                    item(key = "plan-rezept") { PlanRecipeSection(appViewModel, plan) }
                }

                // Die Tragfaehigkeit ist die wichtigste Warnung
                // (trainingNoticeLayout) und steht deshalb immer als Karte.
                openFeasibility?.takeIf { segment == SEGMENT_PLAN }?.let { verdict ->
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

                // --------------------------------------------------- Deine Form
                if (segment == SEGMENT_PROGRESS && (rides.isNotEmpty() || insights.fitness.latest != null)) {
                    item(key = "fitness-trend") { FitnessTrendCard(fitnessTrend) }
                    item(key = "sec-form") { SectionEyebrow(stringResource(R.string.training_screen_form_eyebrow)) }
                    item(key = "form") {
                        FormSummaryCard(insights, onClick = { showForm = true })
                    }
                }

                // -------------------------------------------------- Koerperwerte
                if (segment == SEGMENT_PROGRESS) {
                    item(key = "sec-werte") {
                        SectionEyebrow(stringResource(R.string.training_screen_vitals_eyebrow))
                    }
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
    }

    if (showYearGoal) {
        YearGoalDialog(
            currentKm = yearGoalSetting?.targetKm,
            suggestedKm = suggestedYearGoalKm,
            weeksLeft = (today.lengthOfYear() - today.dayOfYear) / 7.0,
            riddenKm = facts.filter { it.date.year == today.year }.sumOf { it.distanceKm },
            onSave = {
                appViewModel.setYearGoalKm(it)
                showYearGoal = false
            },
            onRemove = {
                appViewModel.setYearGoalKm(null)
                showYearGoal = false
            },
            onDismiss = { showYearGoal = false },
        )
    }

    if (showErfolge) {
        ErfolgeSheet(appViewModel, onDismiss = { showErfolge = false })
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
        title = stringResource(R.string.training_screen_empty_title),
        body = stringResource(R.string.training_screen_empty_body),
        actions = {
            Button(onClick = onRecord) { Text(stringResource(R.string.training_screen_empty_record_action)) }
            NeutralButton(onClick = onImport) { Text(stringResource(R.string.training_screen_empty_import_action)) }
        },
    )
}

/** Sicht „Fortschritt" des Training-Tabs (Index in den [PillSegments]). */
private const val SEGMENT_PROGRESS = 0

/** Sicht „Plan" des Training-Tabs. */
private const val SEGMENT_PLAN = 1
