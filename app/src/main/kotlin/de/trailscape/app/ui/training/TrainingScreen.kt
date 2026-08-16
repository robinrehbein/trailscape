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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiLargeTopAppBar
import de.trailscape.app.ui.components.oneUiTopAppBarScrollBehavior
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.defaultTrainingProfile
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.assessFitness
import de.trailscape.core.routeTargetForSession

/**
 * Trainings-Tab: Form-Kurve (Fitness/Ermüdung/Form), Vitalwerte, Wochenziel,
 * Zielformular und Trainingsplan.
 *
 * Port von `lib/screens/training_screen.dart` (1.281 Zeilen). Die komplette
 * sportwissenschaftliche Auswertung liegt bereits fertig in
 * [AppViewModel.insights] ([de.trailscape.app.ui.TrainingInsights]); dieser
 * Screen ist reine Darstellung plus das Zielformular (Persistenz laeuft ueber
 * [AppViewModel.plan]/[AppViewModel.setPlan]).
 *
 * ## Die Tagesempfehlung ist umgezogen — vollstaendig
 * Die Karte „Heute" (Readiness-Score, Empfehlung, Knopf „Passende Runde
 * planen") stand hier ganz oben und ist ersatzlos entfallen; sie ist jetzt die
 * Startseite (`ui/today/TodayScreen.kt`). Bewusst **nicht** in reduzierter Form
 * stehen geblieben: Zwei Orte, an denen derselbe Score und dieselbe Empfehlung
 * stehen, waeren genau die Redundanz, wegen der bisher niemand wusste, wo die
 * Tagesauskunft eigentlich zu Hause ist — und die Version hier war die, die
 * kaum jemand gefunden hat. Die dazugehoerige Datei `ReadinessCard.kt` ist
 * deshalb geloescht statt verwaist zurueckgelassen; ihre Ampelfarbe
 * ([readinessBandColor], `TrainingColors.kt`) benutzt die Startseite weiter.
 *
 * Was hier bleibt, ist die Analyse dahinter: Die Einzelsignale, aus denen sich
 * die Bereitschaft zusammensetzt, stehen unveraendert in [VitalsCard] — dort
 * mit Messwert, Ampel und Begruendung, also genau in der Tiefe, fuer die man
 * diesen Tab oeffnet.
 *
 * ## Leerzustand
 * Ohne eine einzige Tour sagte dieser Tab bisher in jeder Karte einzeln „noch
 * keine Daten" — und erklaerte nirgends, *warum* und *wie lange* das so bleibt.
 * Deshalb steht bei leerer Tourenliste [TrainingEmptyState] ganz oben: zwei
 * kurze Saetze, dass Fitness und Erholung ~2 Wochen Historie brauchen, und die
 * beiden kuerzesten Wege zu echten Daten. Die Modellerklaerung (CTL/ATL)
 * gehoert in die Kartentiefe, nicht in die Einstiegszeile.
 *
 * Zwei Karten fehlen in diesem Zustand ganz: [WeekCard] und [FitnessCard].
 * Beide *behaupteten* ohne Datengrundlage etwas — die eine „Keine
 * Entlastungswoche nötig — Deine Belastung sieht aktuell tragfähig aus" (eine
 * Entwarnung auf null Datenpunkten), die andere stufte den Nutzer am ersten
 * Tag als „Einsteiger" ein. Der Leerzustand darueber sagt bereits, dass beides
 * Historie braucht; eine erfundene Auskunft daneben macht ihn unglaubwuerdig.
 * Die uebrigen Karten
 * bleiben stehen — Vitalwerte koennen naemlich auch ganz ohne Touren schon aus
 * Health Connect kommen, und das Zielformular funktioniert ebenfalls sofort.
 *
 * ## Ein Hinweis am Rand
 * **Ganz oben**, solange [AppViewModel.profileConfirmed] aus ist: dass alle
 * Zahlen dieses Tabs auf Standardwerten beruhen (siehe
 * [UnconfirmedProfileNotice]). Einen zweiten Hinweis gab es hier frueher ganz
 * unten — ein aufklappbares Glossar der Fachbegriffe (`GlossaryCard.kt`,
 * inzwischen geloescht). Es ist gegenstandslos geworden: Die Karten sprechen
 * die Begriffe jetzt selbst im Klartext (Fitness/Ermüdung/Form statt
 * CTL/ATL/TSB, Entlastungswoche statt Deload), und die wenigen Begriffe mit
 * echtem Erklaerungswert (VO2max) stehen als gedaempfter Untertext direkt an
 * ihrer Kennzahl (siehe [VitalsCard]). Ein Nachschlagewerk fuer eine Sprache,
 * die man gar nicht mehr uebersetzen muss, waere selbst wieder erklaerungs-
 * beduerftig.
 *
 * ## Bewusste Abweichungen vom Dart-Original
 *  * **Keine `_EntranceFade`-Animation.** Das Original blendet die ersten
 *    Karten gestaffelt ein (~40 ms Versatz je Karte). Hier liegen alle Karten
 *    in derselben `LazyColumn` wie die Planwochen — bei vielen Wochen wuerden
 *    recycelte Items erneut einblenden. `ui/rides/RidesScreen.kt` verzichtet
 *    aus demselben Grund bereits darauf.
 *  * **Kein `TweenAnimationBuilder`-Aequivalent** fuer Readiness-Score und die
 *    Fitness/Ermüdung/Form-Kennzahlen — sie werden statisch gezeigt.
 *  * **Ampelpunkt statt Icon** bei den Vitalwerte-Zeilen (siehe KDoc von
 *    [SignalRow]) — bewusste Gestaltung passend zur Ampel-Metapher des
 *    Readiness-Systems, unabhaengig vom verfuegbaren Icon-Satz.
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
    // Ob Alter und Gewicht vom Nutzer stammen — sonst rechnet dieser ganze Tab
    // mit den Annahmen aus `defaultTrainingProfile` (siehe
    // AppViewModel.profileConfirmed).
    val profileConfirmed by appViewModel.profileConfirmed.collectAsStateWithLifecycle()
    // Der Kurzschlaefer-Hinweis ist ein Gesundheitshinweis, kein Statuswert:
    // `:core` deckelt ihn auf einmal pro Monat (`shouldShowShortSleeperHint`),
    // die Entscheidung faellt beim App-Start im ViewModel.
    val showShortSleeperHint by appViewModel.shortSleeperHintVisible
        .collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val scrollBehavior = oneUiTopAppBarScrollBehavior()

    Scaffold(
        // Die aeussere Huelle (TrailscapeApp) hat die System-Insets bereits
        // aufgeloest und als Padding an den NavHost gegeben.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { OneUiLargeTopAppBar("Training", scrollBehavior) },
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
            // Entspricht Darts `Center` + `ConstrainedBox(maxWidth: 640)`: auf
            // schmalen Bildschirmen nimmt die Liste die volle Breite, auf
            // breiten (Tablet) bleibt sie mittig und lesbar schmal.
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
                            // Nicht mehr nur „irgendwohin in den Mehr-Tab":
                            // Das Sprungziel scrollt zur Karte „Daten & Backup",
                            // in der die Import-Knoepfe wirklich stehen (siehe
                            // AppViewModel.pendingMoreSection).
                            onImport = { appViewModel.requestMoreSection(MoreSection.BACKUP) },
                        )
                    }
                }

                // Solange das Profil nicht bestaetigt ist, stehen unter allen
                // Zahlen dieses Tabs Annahmen (Alter 40, 75 kg). Einmal gesagt,
                // ganz oben — nicht in jeder Karte einzeln.
                if (!profileConfirmed) {
                    item(key = "profil-hinweis") {
                        UnconfirmedProfileNotice(
                            onOpenProfile = {
                                appViewModel.requestMoreSection(MoreSection.PROFILE)
                            },
                        )
                    }
                }

                item(key = "form") { FormCard(insights) }
                // Ohne eine einzige Tour haetten beide Karten nichts zu sagen —
                // sie sagten es aber trotzdem, und zwar falsch: „Kein Deload
                // nötig — Deine Belastung sieht aktuell tragfähig aus" ist eine
                // Entwarnung auf null Datenpunkten, und „Einsteiger" eine
                // Einstufung ohne Grundlage. Der Leerzustand ganz oben erklaert
                // bereits, dass beides Historie braucht.
                if (rides.isNotEmpty()) {
                    item(key = "week") {
                        WeekCard(
                            insights,
                            // Der Zeitbudget-Hinweis meint das Feld „Zeit pro
                            // Woche" im Profil — also dorthin, nicht an den
                            // Anfang der Kartenliste.
                            onOpenMore = {
                                appViewModel.requestMoreSection(MoreSection.PROFILE)
                            },
                        )
                    }
                }
                item(key = "vitals") {
                    VitalsCard(
                        insights = insights,
                        showShortSleeperHint = showShortSleeperHint,
                        onShortSleeperHintShown = appViewModel::markShortSleeperHintShown,
                    )
                }
                if (rides.isNotEmpty()) {
                    item(key = "fitness") { FitnessCard(assessment) }
                }
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
                        PlanWeekCard(
                            week = week,
                            plan = currentPlan,
                            rides = rides,
                            onPlanRoute = { session ->
                                appViewModel.requestRouteGeneration(
                                    routeTargetForSession(
                                        session = session,
                                        profile = insights.profile,
                                        recentRides = rides,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Der einmalige Hinweis, dass die Zahlen dieses Tabs auf Standardwerten
 * beruhen.
 *
 * Antippbar, weil ein Hinweis ohne Weg zur Loesung nur aergert: Der Tipp
 * springt in die Profilkarte des Mehr-Tabs — genau dorthin, wo Alter und
 * Gewicht hingehoeren.
 */
@Composable
private fun UnconfirmedProfileNotice(onOpenProfile: () -> Unit) {
    NoticeBox(
        icon = Icons.Filled.Info,
        color = LocalSignalColors.current.caution,
        title = "Noch nicht eingetragen",
        text = "Alter und Gewicht fehlen — wir rechnen bis dahin mit Standardwerten " +
            "(${defaultTrainingProfile.ageYears} Jahre, " +
            "${defaultTrainingProfile.weightKg.toInt()} kg). Trainingslast, HFmax, " +
            "Schwelle und geschätzte Leistung auf dieser Seite sind deshalb grobe " +
            "Schätzungen. Tippe hier, um sie einzutragen.",
        modifier = Modifier.clickable(onClick = onOpenProfile),
    )
}

/**
 * Was der Trainings-Tab kann, solange er noch keine Tour kennt.
 *
 * Textbudget: zwei kurze Saetze, dann die Knoepfe. Die Groessenordnung
 * („rund zwei Wochen") bleibt die einzige Ausnahme vom Ein-Satz-Budget der
 * uebrigen Leerzustaende, weil sie verhindert, dass ein leerer Trainings-Tab
 * am zweiten Tag wie ein Fehler wirkt. Die CTL/ATL-Modellerklaerung entfaellt
 * dagegen: Wer die Begriffe wissen will, findet sie in den Karten selbst
 * ([VitalsCard], [FitnessCard]). Kein Hinweis mehr auf den Countdown — die
 * Karten unten zaehlen ohnehin selbst herunter
 * (`FitnessSeries.daysUntilDisplayReady` aus `:core`).
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
            OutlinedButton(onClick = onImport) { Text("Alte Touren importieren") }
        },
    )
}
