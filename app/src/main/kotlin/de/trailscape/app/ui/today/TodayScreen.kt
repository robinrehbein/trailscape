package de.trailscape.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.weekdayDateFormat
import de.trailscape.core.assessPlanFeasibility
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.decideTodayRoute
import de.trailscape.core.sessionsForDay
import de.trailscape.core.weekKm
import java.time.LocalDateTime

/**
 * # Startseite „Heute" — die Antwort auf „Was soll ich heute fahren?"
 *
 * Bis hierher startete die App auf der Karte: Wer sie morgens oeffnete, sah
 * eine Landkarte und musste sich die eigentliche Auskunft selbst
 * zusammensuchen — Tagesform im Trainings-Tab, Wochenfortschritt weiter unten,
 * die automatische Rundengenerierung als kleiner Knopf am Ende einer Karte.
 * Dieser Screen buendelt genau diese Auskunft und macht sie zum Erstkontakt:
 * Bereitschaft → heutige Einheit → passende Runde → losfahren.
 *
 * ## Was hier NICHT passiert
 * Kein einziger Wert wird hier gerechnet. Bereitschaft, Empfehlung und
 * Wochenziel kommen fertig aus [AppViewModel.insights]
 * ([de.trailscape.app.ui.TrainingInsights]), das Tagesprogramm aus
 * [sessionsForDay], die Verrechnung von Tagesform und Planeinheit aus
 * [decideTodayRoute], das Urteil ueber den Plan aus [assessPlanFeasibility] und
 * der Wochenfortschritt aus [weekKm]/[currentWeekIndex] — alles `:core`. Der
 * Screen entscheidet nur, *welche* Karte etwas zu sagen hat.
 *
 * ## Reihenfolge und Sichtbarkeit
 * Jede Karte erscheint nur, wenn sie eine Aussage traegt:
 *  1. **Kopf** — Begruessung, Wochentag, Datum. Immer.
 *  2. **Tagesempfehlung** — immer: Auch ohne jede Historie liefert `:core` eine
 *     Empfehlung (dann „Grundlageneinheit") und daraus ein Routenziel.
 *  3. **Traegt der Plan?** — nur, wenn die laengste geplante Fahrt die
 *     Zieldistanz deutlich verfehlt. Sie steht bewusst weit oben: Ein Plan, der
 *     das Ziel nicht einholt, ist die wichtigste Auskunft der Seite.
 *  4. **Aufzeichnung** — nur, wenn schon Touren existieren. Beim Erststart
 *     traegt der Leerzustand ganz unten denselben Knopf; zweimal „Tour
 *     aufzeichnen" auf einem Bildschirm waere genau die Doppelung, die diese
 *     Seite abschaffen soll.
 *  5. **Diese Woche** — nur mit laufendem Plan; ohne Plan steht an dieser
 *     Stelle die Einladung, ein Ziel festzulegen.
 *  6. **Letzte Tour** — bzw. der Erststart-Zustand, wenn es keine gibt.
 *
 * ## Kein `TopAppBar`
 * Anders als Touren, Training und Mehr traegt dieser Screen keine Titelleiste:
 * Der Kopf mit Begruessung und Datum sagt dasselbe waermer, und eine Leiste
 * „Heute" ueber „Guten Morgen" waere doppelt. Die Navigationsleiste beschriftet
 * den Tab ohnehin.
 *
 * ## Verhaeltnis zum Trainings-Tab
 * Die Tagesempfehlung steht **nur hier**. `ui/training/TrainingScreen.kt` zeigt
 * seit dieser Aenderung ausschliesslich Plan, Verlauf und Auswertung — die
 * Begruendung dazu steht dort im KDoc.
 */
@Composable
fun TodayScreen(appViewModel: AppViewModel) {
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    // Das Tagesprogramm des Plans: hoechstens eine Einheit wird gezeigt. Plaene
    // aus `:core` setzen nie zwei Einheiten auf denselben Tag; kaeme durch ein
    // fremdes Plan-JSON doch eine zweite dazu, ist die erste die richtige
    // Auskunft und der Trainings-Tab zeigt weiterhin alle.
    val todaySession = remember(plan) { plan?.let { sessionsForDay(it).firstOrNull() } }

    // Die Tagesentscheidung selbst liegt in `:core` ([decideTodayRoute]) und
    // nicht mehr hier. Sie stand frueher als `when`-Block in dieser Datei — die
    // zentrale Verkettung der App, mitten in Compose-Code und damit ohne einen
    // einzigen Test. Dort wirkte die Bereitschaft ausserdem binaer: entweder
    // Ruhetag oder volle Plandistanz. Jetzt daempft die Tagesform Distanz,
    // Hoehenprofil und Intensitaet, liefert den erklaerenden Satz gleich mit —
    // und dieser Screen entscheidet weiterhin nur, welche Karte etwas zu sagen
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
    // Warnung sonst nie wieder.
    val feasibility = remember(plan) { plan?.let { assessPlanFeasibility(it) } }

    // Laufende Planwoche; `null` vor Planbeginn und ohne Plan.
    val currentWeek = remember(plan) {
        plan?.let { current -> current.weeks.getOrNull(currentWeekIndex(current)) }
    }
    val riddenKm = remember(currentWeek, rides) { currentWeek?.let { weekKm(it, rides) } }

    // Ohne ein einziges Erholungssignal ist die Bereitschaft kein „leerer
    // Wert", sondern schlicht nicht Teil dieser Seite (siehe
    // [TodayRecommendationCard]).
    val hasHealthData = insights.restingHr.available ||
        insights.hrv.available ||
        insights.sleep.available

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
                contentPadding = screenContentPadding(),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                item(key = "kopf") { TodayHeader() }

                item(key = "empfehlung") {
                    TodayRecommendationCard(
                        insights = insights,
                        todayRoute = todayRoute,
                        showHealthHint = !insights.readiness.available && !hasHealthData,
                        onPlanRoute = todayRoute.target?.let { target ->
                            { appViewModel.requestRouteGeneration(target) }
                        },
                        onOpenHealth = { appViewModel.requestMoreSection(MoreSection.HEALTH) },
                    )
                }

                // Nur, wenn der Plan sein Ziel nicht traegt — sonst waere es
                // eine Karte, die jeden Tag dasselbe Unauffaellige sagt.
                feasibility?.takeIf { !it.feasible }?.let { verdict ->
                    item(key = "plan-tragfaehigkeit") { PlanFeasibilityCard(verdict) }
                }

                if (rides.isNotEmpty()) {
                    item(key = "aufzeichnen") {
                        RecordPromptCard(onOpenMap = { appViewModel.requestTab(AppTab.MAP) })
                    }
                }

                if (currentWeek != null && riddenKm != null) {
                    item(key = "woche") {
                        WeekProgressCard(
                            week = currentWeek,
                            weekCount = plan?.weeks?.size ?: 0,
                            riddenKm = riddenKm,
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
                            onRecord = { appViewModel.requestTab(AppTab.MAP) },
                            onImport = { appViewModel.requestMoreSection(MoreSection.BACKUP) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Begruessung, Wochentag und Datum.
 *
 * Die Tageszeit steuert nur die Anrede — mehr Zustand traegt der Kopf bewusst
 * nicht: Er soll die Seite eroeffnen, nicht selbst informieren. Ein einmal
 * gemerkter Zeitpunkt genuegt; wer die App ueber Mitternacht offen liegen
 * laesst, sieht das Datum beim naechsten Wechsel in diesen Tab aktualisiert.
 *
 * Das One-UI-Moment der Seite: Die Begruessung laeuft im groessten Slot
 * unterhalb von Titelleisten (headlineLarge — 30 sp und fett direkt aus dem
 * Theme-Slot), das Datum darunter mit einem kleinen Atemzug ruehig in
 * bodyLarge und onSurfaceVariant.
 */
@Composable
private fun TodayHeader() {
    val now = remember { LocalDateTime.now() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = greetingFor(now.hour),
            // Eine Stufe unter der Erholungszahl der Empfehlungskarte: Der
            // Kopf eroeffnet die Seite, aber die eine Zahl, fuer die es sie
            // gibt, traegt den groessten Slot.
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = weekdayDateFormat.format(now),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Anrede nach Tageszeit — die Grenzen folgen dem Alltag, nicht der Uhr. */
private fun greetingFor(hour: Int): String = when (hour) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Guten Tag"
    else -> "Guten Abend"
}

/**
 * Ohne Trainingsziel gibt es keinen Plan — und damit weder Wochenziel noch
 * Tagesprogramm. Statt eine leere Wochenkarte zu zeigen, steht hier der
 * kuerzeste Weg dorthin.
 */
@Composable
private fun GoalPromptState(onOpenTraining: () -> Unit) {
    EmptyState(
        title = "Trainingsziel festlegen",
        body = "Sag Trailscape, worauf du hinfährst — Distanz und Datum genügen. Daraus " +
            "entsteht ein Wochenplan mit Aufbau-, Erholungs- und Taperwochen, und hier " +
            "steht dann jeden Morgen die Einheit des Tages mit Zielkilometern.",
        hint = "Ohne Ziel bleibt die Tagesempfehlung oben trotzdem gültig — sie kommt aus " +
            "deinen Erholungswerten, nicht aus dem Plan.",
        actions = {
            Button(onClick = onOpenTraining) { Text("Ziel eintragen") }
        },
    )
}

/**
 * Erststart: noch keine einzige Tour.
 *
 * Traegt hier — und nur hier — den Aufzeichnen-Knopf, weil die Karte
 * „Aufzeichnung" in diesem Fall ausgeblendet ist (siehe KDoc von
 * [TodayScreen]).
 */
@Composable
private fun FirstRideState(onRecord: () -> Unit, onImport: () -> Unit) {
    EmptyState(
        title = "Los geht's",
        body = "Sobald die erste Tour gefahren oder importiert ist, steht sie hier — mit " +
            "Distanz, Dauer und Höhenmetern. Aus den Touren wächst außerdem dein " +
            "Trainingsbild: Fitness, Ermüdung und die Empfehlung oben werden mit jeder " +
            "Fahrt genauer.",
        hint = "Ein ganzer Strava- oder Garmin-Export lässt sich als ZIP-Archiv auf einmal " +
            "einlesen — unter Mehr → Daten & Backup.",
        actions = {
            Button(onClick = onRecord) { Text("Tour aufzeichnen") }
            OutlinedButton(onClick = onImport) { Text("Touren importieren") }
        },
    )
}
