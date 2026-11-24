package de.trailscape.app.ui.today

import de.trailscape.app.ui.training.fitnessTestWeek
import de.trailscape.app.ui.training.goalTitleParts
import de.trailscape.app.ui.training.planSessionTargets
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.asString
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
import de.trailscape.app.ui.indoor.IndoorTodayEntry
import de.trailscape.app.ui.indoor.indoorErgTitleFor
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.rememberNow
import de.trailscape.app.ui.rememberTodayDecision
import de.trailscape.app.ui.rememberWeekTarget
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.app.ui.progress.ChallengeCard
import de.trailscape.app.ui.progress.NextGoalsCard
import de.trailscape.app.ui.progress.StreakFooter
import de.trailscape.app.ui.progress.WeeklyRecapCard
import de.trailscape.app.ui.progress.nextOpenChallenge
import de.trailscape.app.ui.progress.weeklyGoalCaption
import de.trailscape.app.ui.progress.YearGoalDialog
import de.trailscape.app.ui.progress.rememberRideFacts
import de.trailscape.app.ui.progress.flooredTowards
import de.trailscape.core.AscentPreference
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.WeekPlanInput
import de.trailscape.core.WeekPlanStatus
import de.trailscape.core.challengeProgress
import de.trailscape.core.planWeek
import de.trailscape.core.ridingHabits
import de.trailscape.core.lastWeekRecap
import de.trailscape.core.nearestMilestone
import de.trailscape.core.planningSpeedKmh
import de.trailscape.core.predictGoalFinish
import de.trailscape.core.stretchChallenge
import de.trailscape.core.suggestYearGoalKm
import de.trailscape.core.weekStreak
import de.trailscape.core.yearGoalProgress
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
 *  2b. **Letzte Woche** ([WeeklyRecapCard]) — nur Montag bis Mittwoch und nur,
 *     wenn in der Woche davor gefahren wurde.
 *  3. **Diese Woche** ([WeekCard]) — bzw. am Erststart „Los geht's".
 *     Mit Balken zum Wochenziel (Plan oder mitwachsendes Ziel ohne Plan) und am
 *     Fuss die Wochenserie ([StreakFooter]).
 *  3a. **Wochen-Fahrplan** ([WeekPlanCard]) — nur zum offenen mitwachsenden
 *     Wochenziel ohne Trainingsplan: an welchen Tagen wie weit, mit „Runde
 *     planen" je Tag. Gerechnet in `core/…/WeekPlan.kt`.
 *  3b. **Als Naechstes** — die naechste Bestweite ([ChallengeCard], nicht am
 *     Zieltag und nicht bei „Ruhe") sowie Jahresziel und naechster Meilenstein
 *     ([NextGoalsCard], mit der offenen Monats-Challenge als dritter Zeile). Gerechnet in `core/…/Motivation.kt`. Erst ab der
 *     ersten Tour: Davor gibt es nichts, woran sich etwas steigern liesse.
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
    val coreTexts = LocalCoreTexts.current
    val formats = LocalAppFormats.current
    val language = LocalAppLanguage.current
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val ridesLoading by appViewModel.ridesLoading.collectAsStateWithLifecycle()

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
    // Einstieg ins Indoor-Training (docs/indoor.md 10.1): mit der Einheit des
    // Tages, wenn sie sich als ERG-Einheit fahren laesst.
    val indoorErgTitle = remember(todaySession, coreTexts) {
        runCatching { indoorErgTitleFor(AppServices.indoorParts, todaySession, coreTexts) }.getOrNull()
    }
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
                texts = coreTexts,
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
    val strip = weekStrip(today, weekSessions, riddenByDate, todayKm, coreTexts)
    val hasRiddenRide = remember(rides) { riddenRides(rides).isNotEmpty() }
    val rideCount = remember(rides, monday) {
        riddenRides(rides).count {
            val date = localOfEpochMs(it.createdAt).toLocalDate()
            !date.isBefore(monday) && !date.isAfter(monday.plusDays(6))
        }
    }
    // Dranbleiben: alles aus `core/…/Motivation.kt` und Verwandten, hier nur abgeholt.
    val facts = rememberRideFacts(rides)
    // Ohne Plan-Woche gilt das mitwachsende Wochenziel — auch wer ohne Event
    // faehrt, soll „42 von 60 km" sehen. Die Regel steht in [weekTarget]
    // (`ui/TodayRouteTarget.kt`), damit das Tourblatt nach der Fahrt
    // dasselbe Ziel meint.
    val weekTarget = rememberWeekTarget(decision, facts, today)
    val adaptiveGoal = weekTarget.adaptive
    val weekTargetKm = weekTarget.targetKm
    // Fuers Widget notieren, damit es dieselbe Zahl zeigt (siehe
    // `widget/WidgetState.kt`, [ShownWeekTarget]).
    LaunchedEffect(monday, weekTargetKm) { appViewModel.rememberShownWeekTarget(monday, weekTargetKm) }
    val weekRiddenKm = riddenByDate.values.sum()
    // Dieselbe Zahl in Kopfzeile, Balken und Wochen-Fahrplan: der Stand des
    // mitwachsenden Ziels, auf dem Weg dorthin abgerundet ([flooredTowards]),
    // damit „42 von 60 km" und „Noch 18 km" zusammenpassen.
    val weekGoalRiddenKm = adaptiveGoal?.riddenKm ?: weekRiddenKm
    val weekSummaryText = weekSummary(
        riddenKm = weekGoalRiddenKm,
        targetKm = weekTargetKm,
        strip = strip,
        rideCount = rideCount,
    )
    val goalLine = weekTargetKm?.takeIf { it > 0 }?.let { target ->
        val ridden = weekGoalRiddenKm
        WeekGoalLine(
            fraction = (ridden / target).toFloat().coerceIn(0f, 1f),
            reached = ridden >= target,
            description = stringResource(
                R.string.progress_week_goal_bar_cd,
                flooredTowards(ridden, target.toDouble()),
                target,
            ),
            caption = adaptiveGoal?.let { weeklyGoalCaption(it) },
        )
    }
    // Rueckblick nur Montag bis Mittwoch: danach ist die Woche davor kein Thema mehr.
    val recap = remember(facts, today) {
        lastWeekRecap(facts, today).takeIf { today.dayOfWeek.value <= DayOfWeek.WEDNESDAY.value }
    }

    var showWhy by rememberSaveable { mutableStateOf(false) }

    val streak = remember(facts, today) { weekStreak(facts, today) }
    val yearGoalSetting by appViewModel.yearGoal.collectAsStateWithLifecycle()
    val yearGoal = remember(facts, yearGoalSetting, today) {
        yearGoalSetting?.let { yearGoalProgress(facts, it, today) }
    }
    val suggestedYearGoalKm = remember(facts, today) { suggestYearGoalKm(facts, today) }
    val milestone = remember(facts) { nearestMilestone(facts) }
    val nextChallenge = remember(facts, today) { nextOpenChallenge(challengeProgress(facts, today)) }
    // Die Bestweite ist ein Angebot fuer einen guten Tag: nicht am Zieltag
    // und nicht, wenn die Erholung „Ruhe" sagt.
    val challenge = remember(facts, today, effort, band) {
        stretchChallenge(facts, today)
            ?.takeIf { effort != TodayEffort.ZIELTAG && band != ReadinessBand.RUHE }
    }
    val challengeLabel = stringResource(R.string.progress_challenge_route_label)
    var showYearGoal by rememberSaveable { mutableStateOf(false) }

    // Wochen-Fahrplan (`core/…/WeekPlan.kt`): nur zum offenen mitwachsenden
    // Wochenziel und nie neben einem Trainingsplan — der hat Vorrang, auch
    // wenn er erst naechste Woche beginnt.
    val trainingPlanActive = displayPlan != null &&
        (currentWeek != null || !localOfEpochMs(displayPlan.goal.date).toLocalDate().isBefore(today))
    val weekPlanRemainingKm = adaptiveGoal?.takeIf { !it.reached }?.remainingKm
    val weekPlanEligible = !ridesLoading && hasRiddenRide && !trainingPlanActive && weekPlanRemainingKm != null
    // Tag der Seite und stuendliche Uhr laufen mit eigenem Wecker; um
    // Mitternacht gleicht [weekPlanNow] sie ab.
    val planNow = weekPlanNow(today, rememberHourNow())
    val habits = remember(facts, today) { ridingHabits(facts, today) }
    val rodeToday = (riddenByDate[today] ?: 0.0) > 0.0
    // Erst ohne Wetter planen: Nur wenn es danach Fahrtage gibt, wird nach
    // einer Vorhersage gefragt — kein Plan (Rest zu klein) und „keine Tage"
    // brauchen keine.
    val basePlan = remember(weekPlanEligible, weekPlanRemainingKm, planNow, habits, rodeToday, trainingPlanActive) {
        weekPlanRemainingKm?.takeIf { weekPlanEligible }?.let { remainingKm ->
            planWeek(WeekPlanInput(remainingKm, planNow, habits, emptyList(), trainingPlanActive, rodeToday))
        }
    }
    val weekPlanWeather = rememberWeekPlanWeather(
        active = basePlan != null && basePlan.status != WeekPlanStatus.KEINE_TAGE,
        today = today,
        rides = rides,
    )
    val weekPlanForecastDays = weekPlanWeather.forecast?.days.orEmpty()
    val weekPlan = remember(basePlan, weekPlanForecastDays) {
        if (basePlan == null || weekPlanForecastDays.isEmpty() || weekPlanRemainingKm == null) {
            basePlan
        } else {
            planWeek(
                WeekPlanInput(weekPlanRemainingKm, planNow, habits, weekPlanForecastDays, trainingPlanActive, rodeToday),
            )
        }
    }
    val context = LocalContext.current

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
                        title = stringResource(R.string.today_screen_title),
                        overline = formats.weekdayDate(today),
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
                        sessionTargets = todaySession
                            ?.takeIf { showSessionTargets(it, todayRoute) }
                            ?.let { planSessionTargets(it, insights) },
                        offer = offer,
                        // Die erste Runde ohne Historie baut sofort („Runde bauen ·
                        // 26 km"), alle anderen Angebote oeffnen wie bisher nur
                        // das Panel.
                        onBuildRoute = {
                            offer?.let { appViewModel.requestRouteGeneration(it.target, autoStart = it.firstRound) }
                        },
                        onWhy = { showWhy = true },
                        healthHint = healthHint,
                        onOpenHealth = { appViewModel.requestMoreSection(MoreSection.HEALTH) },
                    )
                }

                item(key = "indoor") {
                    // Einheit des Tages fahren: passende Strecke zur Einheit
                    // (docs/indoor/app.md), sonst der allgemeine Einstieg.
                    IndoorTodayEntry(
                        today = todayRoute,
                        profile = insights.profile,
                        rideCount = rides.size,
                        coreTexts = coreTexts,
                        fallbackErgTitle = indoorErgTitle,
                        onOpen = { appViewModel.requestIndoor() },
                        onRideDay = appViewModel::requestIndoorDay,
                        onPlanRoute = { target -> appViewModel.requestRouteGeneration(target) },
                    )
                }

                // Solange die Touren noch aus der Datenbank kommen, weder
                // Wochenstreifen noch „Noch keine Touren" — sonst blitzt der
                // Erststart kurz auch bei vorhandener Historie auf.
                if (ridesLoading) {
                    Unit
                } else if (!hasRiddenRide) {
                    // Erststart: Ein Streifen aus lauter „–" sagte nichts —
                    // hier steht stattdessen der Weg zur ersten Tour. Nur
                    // gespeicherte Planungen zaehlen dabei nicht als Tour: Der
                    // Streifen waere sonst genauso leer.
                    item(key = "erste-tour") {
                        FirstRideState(
                            onRecord = appViewModel::requestRecording,
                            onImport = { appViewModel.requestMoreSection(MoreSection.BACKUP) },
                            hasOffer = offer != null,
                        )
                    }
                } else {
                    recap?.let { lastWeek ->
                        item(key = "sec-rueckblick") { SectionEyebrow(stringResource(R.string.progress_recap_eyebrow)) }
                        item(key = "rueckblick") { WeeklyRecapCard(lastWeek) }
                    }
                    item(key = "sec-woche") { SectionEyebrow(stringResource(R.string.today_week_eyebrow)) }
                    item(key = "woche") {
                        WeekCard(summary = weekSummaryText, strip = strip, goal = goalLine, footer = { StreakFooter(streak) })
                    }
                    weekPlan?.let { plan ->
                        item(key = "wochenplan") {
                            WeekPlanCard(
                                plan = plan,
                                today = today,
                                weather = weekPlanWeather.ui,
                                onWeatherEnabledChange = { weekPlanWeather.setEnabled(it) },
                                onPlanRide = { ride ->
                                    val speed = planningSpeedKmh(SessionIntensity.GRUNDLAGE, insights.profile, rides)
                                    val day = weekPlanDayLong(ride.date, today, coreTexts).resolve(context)
                                    appViewModel.requestRouteGeneration(
                                        RouteTarget(
                                            distanceKm = ride.km.toDouble(),
                                            ascentPreference = AscentPreference.MODERAT,
                                            durationH = ride.km / speed,
                                            speedKmh = speed,
                                            intensity = SessionIntensity.GRUNDLAGE,
                                            label = context.getString(R.string.today_week_plan_route_label, day),
                                            source = RouteTargetSource.SELBST_GEWAEHLT,
                                        ),
                                    )
                                },
                            )
                        }
                    }

                    item(key = "sec-naechstes") { SectionEyebrow(stringResource(R.string.progress_next_eyebrow)) }
                    challenge?.let { stretch ->
                        item(key = "bestweite") {
                            ChallengeCard(
                                challenge = stretch,
                                onBuild = {
                                    val speed = planningSpeedKmh(SessionIntensity.GRUNDLAGE, insights.profile, rides)
                                    appViewModel.requestRouteGeneration(
                                        RouteTarget(
                                            distanceKm = stretch.targetKm.toDouble(),
                                            ascentPreference = AscentPreference.MODERAT,
                                            durationH = stretch.targetKm / speed,
                                            speedKmh = speed,
                                            intensity = SessionIntensity.GRUNDLAGE,
                                            label = challengeLabel,
                                            source = RouteTargetSource.SELBST_GEWAEHLT,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    item(key = "ziele") {
                        NextGoalsCard(
                            yearGoal = yearGoal,
                            year = today.year,
                            suggestedKm = suggestedYearGoalKm,
                            milestone = milestone,
                            onYearGoal = { showYearGoal = true },
                            onOpenProgress = { appViewModel.requestTab(AppTab.TRAINING) },
                            challenge = nextChallenge,
                        )
                    }
                }

                item(key = "sec-ziel") { SectionEyebrow(stringResource(R.string.today_goal_eyebrow)) }
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
                        val lineParts = if (targetMin != null) {
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
                                texts = coreTexts,
                            )
                        }
                        // Fitnessziel: kein „0 km", sondern wie die Zielkarte im Training.
                        val titleParts = goalTitleParts(goal, language, fitnessTestWeek(shownPlan), formats::km)
                            .map { it.asString() }.joinToString(" · ")
                        GoalCard(
                            title = "${goal.name} · $titleParts",
                            // Erst aufloesen, dann mit dem sprachneutralen „ · " verbinden.
                            line = lineParts.map { it.asString() }.joinToString(" · "),
                            progress = goalProgress(localOfEpochMs(planStart).toLocalDate(), goalDate, today),
                            onOpenTraining = openTraining,
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
                texts = coreTexts,
            ),
            onDismiss = { showWhy = false },
        )
    }
}

/**
 * Erststart: noch keine einzige Tour.
 *
 * Wer noch keine Tour hat, soll nicht raten muessen, was als Naechstes zu tun
 * ist. Der eine volle Knopf der Seite bleibt in der Hero-Karte („Runde bauen ·
 * 26 km", die erste Runde); hier stehen die beiden anderen Wege als neutrale
 * Zweitknoepfe: zuerst der Import (mit der Historie wird die Empfehlung sofort
 * persoenlich), dann das Aufzeichnen — [onRecord] ist dieselbe
 * [AppViewModel.requestRecording]-Bitte wie der schwebende ●-Knopf.
 *
 * @param hasOffer ob die Hero-Karte eine Runde anbietet. Ohne Angebot (etwa am
 *   Zieltag) verweist der Text nicht nach oben.
 */
@Composable
private fun FirstRideState(onRecord: () -> Unit, onImport: () -> Unit, hasOffer: Boolean) {
    EmptyState(
        title = stringResource(R.string.today_first_ride_title),
        body = stringResource(if (hasOffer) R.string.today_first_ride_offer_body else R.string.today_first_ride_body),
        actions = {
            NeutralButton(onClick = onImport) { Text(stringResource(R.string.today_first_ride_import_action)) }
            NeutralButton(onClick = onRecord) { Text(stringResource(R.string.today_first_ride_record_action)) }
        },
    )
}
