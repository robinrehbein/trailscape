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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.planFeasibilityIdentityKey
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.app.ui.weekdayDateFormat
import de.trailscape.core.adaptPlan
import de.trailscape.core.assessPlanFeasibility
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.decideTodayRoute
import de.trailscape.core.sessionsForDay
import de.trailscape.core.weekKm
import java.time.LocalDateTime

/**
 * # Startseite „Heute" — die Antwort auf „Was soll ich heute fahren?"
 *
 * Gestaltungsvorlage ist der Screen „Heute" des Referenzprototyps
 * `docs/design/prototyp-eine-leiste.html` (samt den Mockups im Abschnitt
 * „Empfehlung" von `docs/design/ui-navigationsstudien.html`). Er setzt die
 * Seite nicht als Kartenstapel, sondern als **eine Auskunft in fuenf Stufen**;
 * die Bausteine dazu stehen in `TodayCards.kt`, die Reihenfolge hier.
 *
 * ## Was hier NICHT passiert
 * Kein einziger Wert wird hier gerechnet. Bereitschaft, Empfehlung und
 * Wochenziel kommen fertig aus [AppViewModel.insights]
 * ([de.trailscape.app.ui.TrainingInsights]), das Tagesprogramm aus
 * [sessionsForDay], die Verrechnung von Tagesform und Planeinheit aus
 * [decideTodayRoute], das Urteil ueber den Plan aus [assessPlanFeasibility] und
 * der Wochenfortschritt aus [weekKm]/[currentWeekIndex] — alles `:core`. Der
 * Screen entscheidet nur, *welche* Stufe etwas zu sagen hat.
 *
 * ## Die Reihenfolge — und wann eine Stufe entfaellt
 *  1. **Kopf** — grosser Screen-Titel „Heute", darunter dezent Wochentag und
 *     Datum. Immer.
 *  2. **Bereitschaft** ([ReadinessCard]) — Ring, Coach-Satz, Einheitszeile.
 *     Immer: Auch ohne jede Historie liefert `:core` eine Empfehlung (dann
 *     „Grundlageneinheit"); ohne Erholungssignale entfaellt nur der Ring.
 *  3. **„Runde zum Plan bauen"** ([BuildRouteButton]) — nur, wenn `:core` ein
 *     Routenziel liefert. An einem Ruhetag und am Zieltag gibt es keins, und
 *     ein Angebot zur Ausfahrt waere dort der falsche Rat.
 *  4. **Zahlenzeile** ([TodayCockpitRow]) — Wochen-km, Form, Planwoche, ohne
 *     Karte drumherum. Nur, wenn wenigstens eine der drei Zahlen existiert.
 *  5. **Coach** ([TodayCoachCard]) — die Gruende, aus denen die heutige
 *     Empfehlung folgt. Nur, wenn `:core` welche nennt.
 *  6. **„Plan und Ziel passen nicht zusammen"** ([PlanFeasibilityCard]) — nur,
 *     wenn die laengste geplante Fahrt die Zieldistanz deutlich verfehlt UND
 *     diese Fassung des Plans noch nicht mit „Verstanden" quittiert wurde
 *     ([AppViewModel.planFeasibilityAckKey]). Sie steht bei den Plan-Stufen und
 *     nicht mehr ganz oben: Ein Plan, der sein Ziel nicht einholt, ist eine
 *     wichtige Auskunft — aber keine ueber *heute*, und heute ist, wofuer es
 *     diese Seite gibt.
 *  7. **Plan-Ausblick** ([PlanOutlookCard]) — mit laufendem Plan; ohne Plan
 *     steht an dieser Stelle die Einladung, ein Ziel festzulegen.
 *  8. **Letzte Tour** — bzw. der Erststart-Zustand, wenn es keine gibt.
 *
 * ## Die Karte „Aufzeichnung" ist entfallen
 * Der schwebende ●-Knopf neben der Navigationskapsel ist seit der Fuehrung
 * „Eine Leiste" der eine Weg in die Fahrt; eine zweite, vollbreite
 * „Aufzeichnung starten"-Karte mitten in der Tagesauskunft war dieselbe
 * Handlung ein zweites Mal. Die ausfuehrliche Begruendung steht im KDoc von
 * `TodayCards.kt`.
 *
 * ## Kein `TopAppBar` — der Titel steht im Inhalt
 * Anders als Training, Mehr und die Tourenansicht traegt dieser Screen keine
 * einklappende Titelleiste. Das ist kein Rest, sondern eine Entscheidung.
 *
 * Samsungs ausgeklappte Kopfzeile nimmt **39,67 % der Bildschirmhoehe** ein
 * (siehe `ui/components/OneUiTopAppBar.kt`). Bei einer Liste, durch die man
 * ohnehin scrollt, ist das ein fairer Handel: eine Bildschirmhoehe Ruhe gegen
 * einen Ankerpunkt in Daumenreichweite. Diese Seite ist keine Liste, sondern
 * eine Auskunft — man oeffnet sie, um *eine* Sache zu sehen (die Bereitschaft
 * und ihren einen Knopf) und ist dann fertig. Ein Drittel Leere davor
 * tauschte genau die Information weg, fuer die es die Seite gibt.
 *
 * Der grosse Titel selbst ist damit nicht verschwunden, er ist nur Inhalt
 * geworden ([TodayHeader]) — genau wie im Prototyp, dessen `.bigtitle` in
 * derselben Spalte steht wie die Karten darunter und mit ihnen wegscrollt.
 *
 * Das eine Bedienelement, das sonst in einer Kopfzeile saesse, gibt es
 * trotzdem: das ⚙ in den Mehr-Bereich (seit der Fuehrung „Eine Leiste" kein
 * Tab mehr, siehe `ui/TrailscapeApp.kt`). Es schwebt hier oben rechts ueber
 * dem Inhalt — an genau der Stelle, an der es in den Kopfzeilen von Touren und
 * Training steht, und genau so, wie der Prototyp der Studie es zeigt.
 *
 * ## Bodenfreiheit
 * Der Inhalt scrollt unter der schwebenden Navigationskapsel hindurch; damit
 * das letzte Element trotzdem vollstaendig ueber ihr ausrollt, traegt die
 * Liste [screenContentPadding] als `contentPadding` — es rechnet
 * [LocalFloatingNavigationBarSpace] unten dazu. Dieselbe Zahl bekommt der
 * `SnackbarHost`, sonst erschiene die Meldung hinter der Kapsel.
 *
 * ## Verhaeltnis zum Trainings-Tab
 * Die Tagesempfehlung steht **nur hier**. `ui/training/TrainingScreen.kt` zeigt
 * ausschliesslich Form, Plan und Werte — die Begruendung dazu steht dort im
 * KDoc.
 */
@Composable
fun TodayScreen(appViewModel: AppViewModel) {
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val planFeasibilityAckKey by appViewModel.planFeasibilityAckKey.collectAsStateWithLifecycle()

    // Der ANGEZEIGTE Plan: von `:core` (adaptPlan) an die gefahrene Realitaet
    // angepasst, wenn ganze Wochen deutlich unter Soll lagen. Der gespeicherte
    // Plan bleibt unveraendert — hier zaehlt, was heute realistisch ansteht,
    // nicht, was vor Wochen aufgeschrieben wurde. Der Trainings-Tab leitet
    // denselben Anzeige-Plan ab und erklaert die Anpassung dort.
    val displayPlan = remember(plan, rides, insights) {
        plan?.let {
            adaptPlan(
                plan = it,
                rides = rides,
                currentCtl = insights.latest?.ctl,
                rideLoads = insights.rideLoads.mapValues { entry -> entry.value.load },
            ).plan
        }
    }

    // Das Tagesprogramm des Plans: hoechstens eine Einheit wird gezeigt. Plaene
    // aus `:core` setzen nie zwei Einheiten auf denselben Tag; kaeme durch ein
    // fremdes Plan-JSON doch eine zweite dazu, ist die erste die richtige
    // Auskunft und der Trainings-Tab zeigt weiterhin alle.
    val todaySession = remember(displayPlan) { displayPlan?.let { sessionsForDay(it).firstOrNull() } }

    // Die Tagesentscheidung selbst liegt in `:core` ([decideTodayRoute]) und
    // nicht mehr hier. Sie stand frueher als `when`-Block in dieser Datei — die
    // zentrale Verkettung der App, mitten in Compose-Code und damit ohne einen
    // einzigen Test. Dort wirkte die Bereitschaft ausserdem binaer: entweder
    // Ruhetag oder volle Plandistanz. Jetzt daempft die Tagesform Distanz,
    // Hoehenprofil und Intensitaet, liefert den erklaerenden Satz gleich mit —
    // und dieser Screen entscheidet weiterhin nur, welche Stufe etwas zu sagen
    // hat.
    val todayRoute = remember(insights, rides, todaySession) {
        decideTodayRoute(
            recommendation = insights.recommendation,
            session = todaySession,
            profile = insights.profile,
            recentRides = rides,
            weeklyTarget = insights.weeklyTarget,
        )
    }

    // Traegt der Plan sein eigenes Ziel? Die Antwort steht hier und nicht nur
    // beim Anlegen: Wer den Plan vor acht Wochen erstellt hat, liest die
    // Warnung sonst nie wieder. Bewertet wird der ADAPTIERTE Stand — wenn die
    // Realitaet den Aufbau eingedampft hat, muss auch das Urteil damit rechnen.
    val feasibility = remember(displayPlan) { displayPlan?.let { assessPlanFeasibility(it) } }

    // Schluessel des aktuellen Plans fuer die Quittierung der Karte (siehe
    // [AppViewModel.acknowledgePlanFeasibility]): Nur ohne Plan `null`.
    val planKey = remember(plan) { plan?.let { planFeasibilityIdentityKey(it) } }

    // Laufende Planwoche (aus dem Anzeige-Plan); `null` vor Planbeginn und
    // ohne Plan.
    val currentWeek = remember(displayPlan) {
        displayPlan?.let { current -> current.weeks.getOrNull(currentWeekIndex(current)) }
    }
    val riddenKm = remember(currentWeek, rides) { currentWeek?.let { weekKm(it, rides) } }

    // Die „Schluessel-Einheit" der laufenden Woche fuer den Plan-Ausblick: die
    // Einheit mit den meisten Kilometern. Bewusst keine Rechnung ueber den
    // Kalender — `TrainingSession.day` ist ein Wochentagskuerzel („Sa"), kein
    // Datum; und was die Woche traegt, ist ohnehin ihre laengste Fahrt.
    val keySession = remember(currentWeek) { currentWeek?.sessions?.maxByOrNull { it.targetKm } }

    // Ohne ein einziges Erholungssignal ist die Bereitschaft kein „leerer
    // Wert", sondern schlicht nicht Teil dieser Seite (siehe [ReadinessCard]).
    val hasHealthData = insights.restingHr.available ||
        insights.hrv.available ||
        insights.sleep.available

    // Die drei Zahlen der Cockpit-Zeile. Jede fuer sich optional; sind alle
    // drei leer, entfaellt die Zeile ganz.
    val weekKmText = if (currentWeek != null && riddenKm != null) {
        "${formatKmDe(riddenKm)}/${currentWeek.targetKm}"
    } else {
        null
    }
    val planWeekText = if (currentWeek != null && displayPlan != null) {
        "${currentWeek.index + 1}/${displayPlan.weeks.size}"
    } else {
        null
    }
    val formValue = insights.latest?.tsb

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
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
                // Unten steckt darin die Bodenfreiheit der schwebenden Kapsel
                // ([LocalFloatingNavigationBarSpace]) — ohne sie bliebe das
                // letzte Element hinter der Leiste liegen.
                contentPadding = screenContentPadding(),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                item(key = "kopf") { TodayHeader() }

                item(key = "bereitschaft") {
                    ReadinessCard(
                        insights = insights,
                        todayRoute = todayRoute,
                        showHealthHint = !insights.readiness.available && !hasHealthData,
                        onOpenHealth = { appViewModel.requestMoreSection(MoreSection.HEALTH) },
                    )
                }

                // Kein Routenziel heisst: heute wird nicht gefahren (Ruhetag)
                // oder die Strecke steht schon (Zieltag). Beides sind Faelle,
                // in denen der Knopf nichts anzubieten haette.
                todayRoute.target?.let { target ->
                    item(key = "runde") {
                        BuildRouteButton(
                            target = target,
                            onPlanRoute = { appViewModel.requestRouteGeneration(target) },
                        )
                    }
                }

                if (weekKmText != null || formValue != null || planWeekText != null) {
                    item(key = "cockpit") {
                        TodayCockpitRow(
                            weekKmText = weekKmText,
                            tsb = formValue,
                            planWeekText = planWeekText,
                        )
                    }
                }

                val reasons = insights.recommendation.reasons
                if (reasons.isNotEmpty()) {
                    item(key = "coach") { TodayCoachCard(reasons) }
                }

                // Nur, wenn der Plan sein Ziel nicht traegt UND diese
                // Fassung des Plans noch nicht mit „Verstanden" quittiert
                // wurde — sonst waere es eine Karte, die jeden Tag dasselbe
                // Unauffaellige sagt.
                feasibility?.takeIf { !it.feasible && planKey != planFeasibilityAckKey }?.let { verdict ->
                    item(key = "plan-tragfaehigkeit") {
                        PlanFeasibilityCard(
                            feasibility = verdict,
                            onAdjustGoal = { appViewModel.requestTab(AppTab.TRAINING) },
                            onAcknowledge = {
                                planKey?.let { appViewModel.acknowledgePlanFeasibility(it) }
                            },
                        )
                    }
                }

                if (currentWeek != null && riddenKm != null) {
                    item(key = "plan-ausblick") {
                        PlanOutlookCard(
                            week = currentWeek,
                            weekCount = displayPlan?.weeks?.size ?: 0,
                            riddenKm = riddenKm,
                            keySession = keySession,
                            onOpenTraining = { appViewModel.requestTab(AppTab.TRAINING) },
                        )
                    }
                } else if (plan == null) {
                    item(key = "kein-ziel") {
                        GoalPromptState(onOpenTraining = { appViewModel.requestTab(AppTab.TRAINING) })
                    }
                }

                val lastRide = rides.firstOrNull()
                if (lastRide != null) {
                    item(key = "letzte-tour") {
                        LastRideCard(
                            ride = lastRide,
                            onOpenRides = { appViewModel.requestRideDetail(lastRide.id) },
                        )
                    }
                } else {
                    item(key = "erste-tour") {
                        FirstRideState(
                            onRecord = appViewModel::requestRecording,
                            onImport = { appViewModel.requestMoreSection(MoreSection.BACKUP) },
                        )
                    }
                }
            }

            // Das Zahnrad in den Mehr-Bereich (seit der Fuehrung „Eine
            // Leiste" kein Tab mehr, siehe `ui/TrailscapeApp.kt`). Auf
            // Touren und Training sitzt es rechts in der Kopfzeile — diese
            // Seite hat bewusst keine (Begruendung im KDoc oben), also
            // schwebt es hier ueber dem Inhalt, an derselben Stelle wie
            // dort. Genau das tut auch der Prototyp der Studie: ein
            // freistehendes ⚙ oben rechts.
            //
            // Der Innenabstand ist [ScreenPadding] abzueglich der 12 dp, die
            // ein [androidx.compose.material3.IconButton] als Beruehrungsrand
            // um sein 24-dp-Symbol legt — so steht das Symbol auf derselben
            // Linie wie der Karteninhalt darunter und der Knopf behaelt
            // trotzdem seine volle Trefferflaeche.
            SettingsAction(
                onClick = { appViewModel.requestTab(AppTab.MORE) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(ScreenPadding - 12.dp),
            )
        }
    }
}

/**
 * Der Kopf der Seite: grosser Screen-Titel „Heute", darunter Wochentag und
 * Datum.
 *
 * ## Warum der Titel wieder da ist
 * Hier stand zuletzt nur eine Datumszeile. Der Titel fehlte, weil es keine
 * Kopfzeile gibt — und damit fehlte der Seite ihr Name: Der Leitfaden verlangt
 * einen Titel, der **gleichlautend** zum Reiter ist, und der Prototyp zeigt ihn
 * gross ueber der ersten Karte. Als Inhalt statt als Leiste kostet er nur seine
 * eigene Zeilenhoehe und nicht ein Drittel Bildschirm.
 *
 * Er laeuft im `headlineLarge`-Slot — der groessten Stufe, die neben dem
 * schwebenden ⚙ noch ruhig wirkt — und laesst rechts genau dessen Trefferflaeche
 * frei, damit lange Uebersetzungen nicht unter dem Symbol verschwinden.
 *
 * Das Datum bleibt, was es war: eine Zeile auf blankem Grund, um [CardPadding]
 * eingerueckt, damit sie auf derselben Kante steht wie der Text *in* der Karte
 * darunter. Es eroeffnet die Seite, informiert aber nicht — das tut die Karte
 * darunter. Genau so setzt Samsungs Telefon-App ihre Datumsueberschriften.
 *
 * Ein einmal gemerkter Zeitpunkt genuegt; wer die App ueber Mitternacht offen
 * liegen laesst, sieht das Datum beim naechsten Wechsel in diesen Tab
 * aktualisiert.
 */
@Composable
private fun TodayHeader() {
    val now = remember { LocalDateTime.now() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Rechts bleibt die Flaeche des schwebenden Zahnrads frei.
            .padding(start = CardPadding, end = SettingsActionWidth),
    ) {
        Text(text = "Heute", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = weekdayDateFormat.format(now),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Breite, die der Kopf rechts fuer das schwebende ⚙ freihaelt — die
 * Trefferflaeche eines [androidx.compose.material3.IconButton].
 */
private val SettingsActionWidth = 48.dp

/**
 * Ohne Trainingsziel gibt es keinen Plan — und damit weder Wochenziel noch
 * Tagesprogramm. Statt eines leeren Plan-Ausblicks steht hier der kuerzeste
 * Weg dorthin.
 *
 * Textbudget: ein Satz Fliesstext, dann der Knopf. Was der Knopf bewirkt,
 * sagt der Knopf selbst — eine Hinweiszeile darunter waere ein zweites
 * Eingestaendnis derselben Sache.
 */
@Composable
private fun GoalPromptState(onOpenTraining: () -> Unit) {
    EmptyState(
        title = "Trainingsziel festlegen",
        body = "Sag Trailscape, worauf du hinfährst — Distanz und Datum genügen, daraus " +
            "entsteht dein Wochenplan.",
        actions = {
            Button(onClick = onOpenTraining) { Text("Ziel eintragen") }
        },
    )
}

/**
 * Erststart: noch keine einzige Tour.
 *
 * Traegt hier — und nur hier — den Aufzeichnen-Knopf. Seit die Dauerkarte
 * „Aufzeichnung" entfallen ist (der schwebende ●-Knopf tut dasselbe, siehe
 * KDoc von `TodayCards.kt`), ist das kein zweiter Startweg mehr, sondern die
 * Wegbeschreibung des Leerzustands: Wer noch keine Tour hat, soll nicht raten
 * muessen, was als Naechstes zu tun ist. [onRecord] ist dieselbe
 * [de.trailscape.app.ui.AppViewModel.requestRecording]-Bitte, die auch der
 * ●-Knopf ausloest — ein Leerzustand, der bloss den Tab wechselt statt die
 * Aufzeichnung wirklich anzustossen, waere genau die Zwei-Schritt-Huerde, die
 * dieser Knopf woanders schon abgebaut hat.
 *
 * Textbudget: ein Satz Fliesstext. Das ZIP-Import-Wissen wohnt in der
 * Backup-Karte unter Mehr → Daten & Backup, wo der Import tatsaechlich
 * stattfindet — eine Hinweiszeile hier waere nur ein Vorgriff darauf.
 */
@Composable
private fun FirstRideState(onRecord: () -> Unit, onImport: () -> Unit) {
    EmptyState(
        title = "Los geht's",
        body = "Sobald die erste Tour gefahren oder importiert ist, landet sie hier.",
        actions = {
            Button(onClick = onRecord) { Text("Tour aufzeichnen") }
            NeutralButton(onClick = onImport) { Text("Touren importieren") }
        },
    )
}
