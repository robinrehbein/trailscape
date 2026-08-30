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
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiLargeTopAppBar
import de.trailscape.app.ui.components.SectionEyebrow
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.components.oneUiTopAppBarScrollBehavior
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.defaultTrainingProfile
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.adaptPlan
import de.trailscape.core.assessFitness
import de.trailscape.core.routeTargetForSession

/**
 * # Trainings-Tab: **ein** Scroll-Screen in drei Kapiteln
 *
 * Gestaltungsvorlage ist der Screen „Training" des Referenzprototyps
 * `docs/design/prototyp-eine-leiste.html`: Form → Plan → Werte, jedes Kapitel
 * mit einer Mono-Kapitelmarke ([SectionEyebrow]) darueber, und **keine**
 * Segmente, keine Reiter, kein Umschalten. Die drei Marken sind der ganze
 * Ersatz fuer eine zweite Navigationsebene — man erkennt beim Scrollen, wo man
 * ist, statt vorher zu waehlen, was man sehen will.
 *
 * ## Die drei Kapitel und was in ihnen wohnt
 *  * **Form** — [FormCard] (Lastskala-Hinweis, PMC-Kurve, die Kennzahlen
 *    Fitness/Ermuedung/Form als Chips), darunter [FormCoachCard] mit der
 *    Deutung als Akzentkarte, dann [FitnessCard] mit der Einstufung aus den
 *    letzten acht Wochen. Alles, was beschreibt, **wie fit du gerade bist**.
 *  * **Plan** — [WeekCard] (Wochenlast, Zielwert, Entlastungswoche),
 *    [PlanHeader] mit [PlanAdaptionNote], die [PlanWeekCard]s aller Wochen und
 *    zuletzt [GoalCard], das Zielformular. Alles, was beschreibt, **worauf du
 *    hinfaehrst**.
 *  * **Werte** — [VitalsTiles], das Kachel-Raster der Erholungssignale samt
 *    Deutungszeile. Alles, was **gemessen** wurde statt gerechnet.
 *
 * Die Zuordnung ist die einzige inhaltliche Entscheidung dieses Umbaus:
 * Karten, die der Prototyp nicht kennt (Fitnesslevel, Wochenlast, Zielformular),
 * sind nicht entfallen, sondern in das Kapitel gewandert, dessen Frage sie
 * beantworten. [GoalCard] steht dabei bewusst **am Ende** von „Plan": Sie ist
 * das Formular, mit dem der Plan entsteht oder geloescht wird — man liest den
 * Plan haeufiger, als man ihn neu setzt.
 *
 * ## Was hier nicht gerechnet wird
 * Die komplette sportwissenschaftliche Auswertung liegt fertig in
 * [AppViewModel.insights] ([de.trailscape.app.ui.TrainingInsights]); dieser
 * Screen ist reine Darstellung plus das Zielformular (Persistenz laeuft ueber
 * [AppViewModel.plan]/[AppViewModel.setPlan]).
 *
 * ## Die Tagesempfehlung ist umgezogen — vollstaendig
 * Die Karte „Heute" (Readiness-Score, Empfehlung, Knopf „Runde zum Plan
 * bauen") stand hier ganz oben und ist ersatzlos entfallen; sie ist jetzt die
 * Startseite (`ui/today/TodayScreen.kt`). Bewusst **nicht** in reduzierter Form
 * stehen geblieben: Zwei Orte, an denen derselbe Score und dieselbe Empfehlung
 * stehen, waeren genau die Redundanz, wegen der bisher niemand wusste, wo die
 * Tagesauskunft eigentlich zu Hause ist. Was hier bleibt, ist die Analyse
 * dahinter: die Einzelsignale im Kapitel „Werte", dort mit Messwert, Ampel und
 * Begruendung, also genau in der Tiefe, fuer die man diesen Tab oeffnet.
 *
 * ## Leerzustand
 * Ohne eine einzige Tour sagte dieser Tab bisher in jeder Karte einzeln „noch
 * keine Daten" — und erklaerte nirgends, *warum* und *wie lange* das so bleibt.
 * Deshalb steht bei leerer Tourenliste [TrainingEmptyState] ganz oben, noch vor
 * dem ersten Kapitel: zwei kurze Saetze, dass Fitness und Erholung ~2 Wochen
 * Historie brauchen, und die beiden kuerzesten Wege zu echten Daten.
 *
 * Drei Bausteine fehlen in diesem Zustand ganz: [WeekCard], [FitnessCard] und
 * [FormCoachCard]. Alle drei *behaupteten* ohne Datengrundlage etwas — „Keine
 * Entlastungswoche nötig" ist eine Entwarnung auf null Datenpunkten,
 * „Einsteiger" eine Einstufung ohne Grundlage, und ein Coach-Satz zur Form
 * waere ein Urteil ueber eine Kurve, die es noch nicht gibt. Der Leerzustand
 * darueber sagt bereits, dass alles davon Historie braucht; eine erfundene
 * Auskunft daneben macht ihn unglaubwuerdig. Die uebrigen Bausteine bleiben
 * stehen — Vitalwerte koennen naemlich auch ganz ohne Touren schon aus Health
 * Connect kommen, und das Zielformular funktioniert ebenfalls sofort.
 *
 * ## Ein Hinweis am Rand
 * **Ganz oben**, solange [AppViewModel.profileConfirmed] aus ist: dass alle
 * Zahlen dieses Tabs auf Standardwerten beruhen (siehe
 * [UnconfirmedProfileNotice]). Einen zweiten Hinweis gab es hier frueher ganz
 * unten — ein aufklappbares Glossar der Fachbegriffe (`GlossaryCard.kt`,
 * inzwischen geloescht). Es ist gegenstandslos geworden: Die Karten sprechen
 * die Begriffe jetzt selbst im Klartext (Fitness/Ermüdung/Form statt
 * CTL/ATL/TSB, Entlastungswoche statt Deload), und die wenigen Begriffe mit
 * echtem Erklaerungswert (VO₂max) stehen als gedaempfter Untertext direkt an
 * ihrer Kennzahl (siehe [VitalsTiles]).
 *
 * ## Die Kopfzeile bleibt
 * Anders als die Startseite traegt dieser Screen weiter die grosse
 * One-UI-Kopfzeile ([OneUiLargeTopAppBar]) mit dem Titel „Training" und dem ⚙
 * darin. Der Prototyp zeichnet den Titel als Inhaltszeile — hier ist er die
 * Kopfzeile, und das ist der bessere Handel: Dieser Tab **ist** eine lange
 * Liste, durch die man ohnehin scrollt (der Grund, aus dem der Leitfaden die
 * ausklappbare Kopfzeile ueberhaupt vorsieht), und das ⚙ steht damit an
 * derselben Stelle wie im Touren-Tab. Der grosse zentrierte Titel ist genau
 * der „grosse Screen-Titel" der Zielgestaltung, nur in der Fassung, die One UI
 * dafuer vorsieht.
 *
 * ## Bodenfreiheit
 * Der Inhalt scrollt unter der schwebenden Navigationskapsel hindurch; damit
 * das letzte Element vollstaendig ueber ihr ausrollt, traegt die Liste
 * [screenContentPadding] als `contentPadding` — es rechnet
 * [LocalFloatingNavigationBarSpace] unten dazu. Dieselbe Zahl bekommt der
 * `SnackbarHost`.
 *
 * ## Bewusste Abweichungen vom Dart-Original
 *  * **Keine `_EntranceFade`-Animation.** Das Original blendet die ersten
 *    Karten gestaffelt ein (~40 ms Versatz je Karte). Hier liegen alle Karten
 *    in derselben `LazyColumn` wie die Planwochen — bei vielen Wochen wuerden
 *    recycelte Items erneut einblenden. `ui/rides/TourList.kt` verzichtet
 *    aus demselben Grund bereits darauf.
 *  * **Kein `TweenAnimationBuilder`-Aequivalent** fuer die
 *    Fitness/Ermüdung/Form-Kennzahlen — sie werden statisch gezeigt.
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
    // Lastwerte je Tour fuer Status-Zuordnung und Plan-Adaption — die Karten
    // darunter brauchen nur die eine Zahl, nicht den ganzen RideLoad.
    val rideLoadValues = remember(insights) {
        insights.rideLoads.mapValues { it.value.load }
    }
    // Der ANGEZEIGTE Plan: an die gefahrene Realitaet angepasst, wenn ganze
    // Wochen deutlich unter Soll lagen (`:core`, adaptPlan). Der gespeicherte
    // Plan in [AppViewModel.plan] bleibt unveraendert — Format und Referenz
    // fuer kuenftige Vergleiche.
    val adaptedPlan = remember(plan, rides, rideLoadValues, insights.latest?.ctl) {
        plan?.let {
            adaptPlan(
                plan = it,
                rides = rides,
                currentCtl = insights.latest?.ctl,
                rideLoads = rideLoadValues,
            )
        }
    }
    // Ob Alter und Gewicht vom Nutzer stammen — sonst rechnet dieser ganze Tab
    // mit den Annahmen aus `defaultTrainingProfile` (siehe
    // AppViewModel.profileConfirmed).
    val profileConfirmed by appViewModel.profileConfirmed.collectAsStateWithLifecycle()
    // Der Kurzschlaefer-Hinweis ist ein Gesundheitshinweis, kein Statuswert:
    // `:core` deckelt ihn auf einmal pro Monat (`shouldShowShortSleeperHint`),
    // die Entscheidung faellt beim App-Start im ViewModel.
    val showShortSleeperHint by appViewModel.shortSleeperHintVisible
        .collectAsStateWithLifecycle()

    // Ohne eine einzige Fitnesskurve gibt es nichts zu deuten — dann entfaellt
    // die Coach-Karte des Form-Kapitels (siehe KDoc oben).
    val hasFitnessCurve = insights.fitness.latest != null

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
        topBar = {
            OneUiLargeTopAppBar(
                title = "Training",
                scrollBehavior = scrollBehavior,
                // Das Zahnrad rechts ist seit der Fuehrung „Eine Leiste" der
                // Einstieg in den Mehr-Bereich — er ist kein Tab mehr (siehe
                // `ui/TrailscapeApp.kt`). Dieselbe Stelle in allen drei
                // Listen-Tabs, damit man ihn nicht suchen muss.
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
            // Entspricht Darts `Center` + `ConstrainedBox(maxWidth: 640)`: auf
            // schmalen Bildschirmen nimmt die Liste die volle Breite, auf
            // breiten (Tablet) bleibt sie mittig und lesbar schmal.
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth(),
                // Unten steckt darin die Bodenfreiheit der schwebenden Kapsel
                // ([LocalFloatingNavigationBarSpace]) — ohne sie bliebe die
                // letzte Wochenkarte hinter der Leiste liegen.
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
                // ganz oben — nicht in jeder Karte einzeln, und vor dem ersten
                // Kapitel, weil der Vorbehalt fuer alle drei gilt.
                if (!profileConfirmed) {
                    item(key = "profil-hinweis") {
                        UnconfirmedProfileNotice(
                            onOpenProfile = {
                                appViewModel.requestMoreSection(MoreSection.PROFILE)
                            },
                        )
                    }
                }

                // ----------------------------------------------- Kapitel FORM
                item(key = "sec-form") { SectionEyebrow("Form") }
                item(key = "form") { FormCard(insights) }
                if (hasFitnessCurve) {
                    item(key = "form-coach") { FormCoachCard(insights) }
                }
                if (rides.isNotEmpty()) {
                    item(key = "fitness") { FitnessCard(assessment) }
                }

                // ----------------------------------------------- Kapitel PLAN
                item(key = "sec-plan") { SectionEyebrow("Plan") }
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

                adaptedPlan?.let { adapted ->
                    val currentPlan = adapted.plan
                    item(key = "plan-header") { PlanHeader(currentPlan) }
                    if (adapted.adapted) {
                        adapted.reason?.let { reason ->
                            item(key = "plan-adaption") { PlanAdaptionNote(reason) }
                        }
                    }
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
                            rideLoads = rideLoadValues,
                        )
                    }
                }

                item(key = "goal") {
                    GoalCard(
                        plan = plan,
                        rides = rides,
                        onSetPlan = { appViewModel.setPlan(it) },
                        currentCtl = insights.latest?.ctl,
                    )
                }

                // ---------------------------------------------- Kapitel WERTE
                item(key = "sec-werte") { SectionEyebrow("Werte") }
                item(key = "vitals") {
                    VitalsTiles(
                        insights = insights,
                        showShortSleeperHint = showShortSleeperHint,
                        onShortSleeperHintShown = appViewModel::markShortSleeperHintShown,
                    )
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
 * ([VitalsTiles], [FitnessCard]). Kein Hinweis mehr auf den Countdown — die
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
            NeutralButton(onClick = onImport) { Text("Alte Touren importieren") }
        },
    )
}
