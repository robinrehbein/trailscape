package de.trailscape.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.SectionEyebrow
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.rememberNow
import de.trailscape.app.ui.rememberTodayDecision
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.app.ui.weekdayDateFormat
import de.trailscape.core.predictGoalFinish
import de.trailscape.core.projectedEventCtl
import de.trailscape.core.riddenRides
import java.time.DayOfWeek
import kotlin.math.roundToInt

/**
 * # Startseite „Heute" — die Antwort auf „Was fahre ich heute?"
 *
 * Gestaltungsvorlage ist der Screen „Heute" aus
 * `docs/design/prototyp-klartext.html` samt Blatt „Warum?". Die Seite spricht
 * **Klartext**: ein Ring mit passendem Wort, ein Satz, ein Knopf — kein GA1,
 * kein Z2, kein Formwert. Die Saetze stehen in `TodayWording.kt` (reine
 * Funktionen, getestet), die Bausteine in `TodayCards.kt`, die Reihenfolge
 * hier.
 *
 * ## Was hier NICHT passiert
 * Kein Trainingswert wird hier gerechnet. Bereitschaft und Empfehlung kommen
 * aus [AppViewModel.insights], das Tagesprogramm aus [sessionsForDay], die
 * Verrechnung von Tagesform und Planeinheit aus [de.trailscape.core.decideTodayRoute] — alles
 * `:core`. Der Screen leitet nur ab, *welche* Tagesart das ist
 * ([todayEffort]) und was davon auf die Seite kommt.
 *
 * ## Die Reihenfolge
 *  1. **Kopf** — Datumszeile mit ⚙, darunter gross „Heute".
 *  2. **Hero** ([HeroCard]) — Ring (nur mit Gesamtwert), Schlagzeile, Satz,
 *     „Runde für heute bauen" (nicht am Zieltag; am Ruhetag stattdessen das
 *     ruhigere „Locker rollen", siehe [offeredTarget]), „Warum diese
 *     Empfehlung?" ([WhySheet]).
 *  3. **Diese Woche** ([WeekCard]) — bzw. am Erststart „Los geht's".
 *  4. **Dein Ziel** ([GoalCard]) — ohne Plan die Einladung
 *     ([GoalPromptCard]). Tippen oeffnet Training.
 *
 * ## Was entfallen ist
 * Zahlenzeile (Wochen-km, Form, Planwoche), Coach-Karte (jetzt im
 * „Warum?"-Blatt), Plan-Ausblick (jetzt Wochenstreifen und Zielzeile),
 * „Plan und Ziel passen nicht zusammen" (gehoert in den Trainings-Tab, siehe
 * `PlanFeasibilityCard.kt`) und „Letzte Tour" (steht im Verlauf).
 *
 * ## Kein `TopAppBar` — der Titel steht im Inhalt
 * Samsungs ausgeklappte Kopfzeile nimmt fast 40 % der Bildschirmhoehe ein
 * (siehe `ui/components/OneUiTopAppBar.kt`). Diese Seite ist keine Liste,
 * sondern eine Auskunft; ein Drittel Leere davor tauschte genau die
 * Information weg, fuer die es die Seite gibt. Der Titel ist deshalb Inhalt
 * ([TodayHeader]), das ⚙ schwebt oben rechts auf Hoehe der Datumszeile.
 *
 * ## Bodenfreiheit
 * Die Liste traegt [screenContentPadding] als `contentPadding` — es rechnet
 * [LocalFloatingNavigationBarSpace] unten dazu. Dieselbe Zahl bekommt der
 * `SnackbarHost`, sonst erschiene die Meldung hinter der Kapsel.
 */
@Composable
fun TodayScreen(appViewModel: AppViewModel) {
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    // Tagesgenau und beim Zurueckkehren nach Mitternacht erneuert (siehe
    // [rememberNow]) — sonst galt nach einer Nacht im Hintergrund noch gestern.
    val now = rememberNow()
    val today = now.toLocalDate()

    // Plan, heutige Einheit, Tagesentscheidung und Angebot: dieselbe Rechnung
    // wie auf der Karte und im Losfahren-Dialog ([decideToday]). Der ANGEZEIGTE
    // Plan ist von `:core` (adaptPlan) an die gefahrene Realitaet angepasst;
    // der gespeicherte bleibt unveraendert.
    val decision = rememberTodayDecision(appViewModel, now)
    val displayPlan = decision.displayPlan
    val todaySession = decision.todaySession
    val currentWeek = decision.currentWeek
    val weekSessions = decision.weekSessions
    val planRestDay = decision.planRestDay
    val todayRoute = decision.route
    val effort = decision.effort
    val offer = decision.offer

    // Prognose fuer die Ziel-Zeile — dieselbe Rechnung wie im Training-Tab.
    val goalPrediction = remember(displayPlan, rides, insights) {
        displayPlan?.takeIf { it.goal.targetDurationMin != null }?.let {
            predictGoalFinish(
                goal = it.goal,
                rides = rides,
                currentCtl = insights.latest?.ctl,
                projectedCtl = projectedEventCtl(it, insights.latest?.ctl),
            )
        }
    }

    val readiness = insights.readiness
    val band = if (readiness.available) readiness.band else null
    val hasHealthData = insights.restingHr.available || insights.hrv.available || insights.sleep.available ||
        insights.restingHr.baselineDays > 0 || insights.hrv.historyDays > 0 || insights.sleep.validNights > 0
    val healthHint = when {
        readiness.available -> HealthHint.NONE
        !hasHealthData -> HealthHint.CONNECT
        else -> HealthHint.COLLECTING
    }

    // Dieselbe Zahl in Satz, Knopf und Streifen. Die lockere Ruhetagsrunde
    // zaehlt nicht: Der Streifen zeigt, was ansteht, und am Ruhetag steht
    // nichts an — sie ist ein Angebot, kein Programm.
    val todayKm = when {
        offer != null && !offer.restDay -> offer.target.distanceKm.roundToInt()
        effort == TodayEffort.ZIELTAG -> todaySession?.targetKm
        else -> null
    }

    val monday = today.with(DayOfWeek.MONDAY)
    val riddenByDate = remember(rides, monday) { riddenKmByDate(rides, monday, monday.plusDays(6)) }
    val strip = weekStrip(today, weekSessions, riddenByDate, todayKm)
    val rideCount = remember(rides, monday) {
        riddenRides(rides).count {
            val date = localOfEpochMs(it.createdAt).toLocalDate()
            !date.isBefore(monday) && !date.isAfter(monday.plusDays(6))
        }
    }
    val weekSummaryText = weekSummary(
        riddenKm = riddenByDate.values.sum(),
        targetKm = currentWeek?.targetKm,
        strip = strip,
        rideCount = rideCount,
    )

    var showWhy by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // Die aeussere Huelle (TrailscapeApp) hat die System-Insets bereits
        // aufgeloest und als Padding an den NavHost gegeben.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = LocalFloatingNavigationBarSpace.current),
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
                        title = "Heute",
                        overline = weekdayDateFormat.format(now),
                        actions = {
                            SettingsAction(onClick = { appViewModel.requestTab(AppTab.MORE) })
                        },
                    )
                }

                item(key = "hero") {
                    HeroCard(
                        score = if (readiness.available) readiness.score.roundToInt() else null,
                        band = band,
                        headline = todayHeadline(effort, todayRoute, band, planRestDay),
                        sentence = todaySentence(effort, todayRoute),
                        offer = offer,
                        onBuildRoute = { offer?.let { appViewModel.requestRouteGeneration(it.target) } },
                        onWhy = { showWhy = true },
                        healthHint = healthHint,
                        onOpenHealth = { appViewModel.requestMoreSection(MoreSection.HEALTH) },
                    )
                }

                if (rides.isEmpty()) {
                    // Erststart: Ein Streifen aus lauter „–" sagte nichts —
                    // hier steht stattdessen der Weg zur ersten Tour.
                    item(key = "erste-tour") {
                        FirstRideState(
                            onRecord = appViewModel::requestRecording,
                            onImport = { appViewModel.requestMoreSection(MoreSection.BACKUP) },
                        )
                    }
                } else {
                    item(key = "sec-woche") { SectionEyebrow("Diese Woche") }
                    item(key = "woche") { WeekCard(summary = weekSummaryText, strip = strip) }
                }

                item(key = "sec-ziel") { SectionEyebrow("Dein Ziel") }
                item(key = "ziel") {
                    val openTraining = { appViewModel.requestTab(AppTab.TRAINING) }
                    val shownPlan = displayPlan
                    if (shownPlan == null) {
                        GoalPromptCard(onOpenTraining = openTraining)
                    } else {
                        val goal = shownPlan.goal
                        val goalDate = localOfEpochMs(goal.date).toLocalDate()
                        val planStart = shownPlan.weeks.firstOrNull()?.start ?: shownPlan.createdAt
                        val targetMin = goal.targetDurationMin
                        GoalCard(
                            title = "${goal.name} · ${formatKmDe(goal.distanceKm)} km",
                            line = if (targetMin != null) {
                                goalTimeLine(
                                    today = today,
                                    goalDate = goalDate,
                                    targetMin = targetMin,
                                    currentMin = goalPrediction?.prognosis?.currentMin,
                                )
                            } else {
                                goalLine(
                                    today = today,
                                    goalDate = goalDate,
                                    weekIndex = currentWeek?.index ?: -1,
                                    weekCount = shownPlan.weeks.size,
                                )
                            },
                            progress = goalProgress(localOfEpochMs(planStart).toLocalDate(), goalDate, today),
                            onOpenTraining = openTraining,
                        )
                    }
                }
            }
        }
    }

    if (showWhy) {
        WhySheet(
            title = whyTitle(effort, todayRoute),
            signals = listOf(
                sleepSignal(insights.sleep),
                restingHrSignal(insights.restingHr),
                hrvSignal(insights.hrv),
                loadSignal(insights.latest?.tsb),
            ),
            note = whyNote(
                effort = effort,
                route = todayRoute,
                planRestDay = planRestDay,
                upcoming = upcomingKeySession(weekSessions, today.dayOfWeek.value - 1, todayKm),
                deloadRecommended = insights.deload.recommended,
                hasPlan = displayPlan != null,
            ),
            onDismiss = { showWhy = false },
        )
    }
}

/**
 * Erststart: noch keine einzige Tour.
 *
 * Der Weg ins Aufzeichnen steht hier — und nur hier — als Knopf: Wer noch
 * keine Tour hat, soll nicht raten muessen, was als Naechstes zu tun ist.
 * [onRecord] ist dieselbe [AppViewModel.requestRecording]-Bitte wie der
 * schwebende ●-Knopf. Beide Knoepfe sind neutral: Der eine volle Knopf der
 * Seite ist „Runde für heute bauen" in der Hero-Karte.
 */
@Composable
private fun FirstRideState(onRecord: () -> Unit, onImport: () -> Unit) {
    EmptyState(
        title = "Los geht's",
        body = "Sobald die erste Tour gefahren oder importiert ist, siehst du hier deine Woche.",
        actions = {
            NeutralButton(onClick = onRecord) { Text("Tour aufzeichnen") }
            NeutralButton(onClick = onImport) { Text("Touren importieren") }
        },
    )
}
