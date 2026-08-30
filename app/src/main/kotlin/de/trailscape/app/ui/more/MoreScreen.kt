package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.OneUiLargeTopAppBar
import de.trailscape.app.ui.components.oneUiTopAppBarScrollBehavior
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth

/**
 * Der „Mehr"-Bereich — Port von `lib/screens/more_screen.dart`.
 *
 * ## Kein Tab mehr, sondern die zweite Ebene hinterm Zahnrad
 * Dieser Bildschirm war einmal der vierte Reiter der Navigationskapsel. Seit
 * der Fuehrung „Eine Leiste" (siehe `ui/TrailscapeApp.kt`) ist er ein
 * **gepushtes Ziel** der Route „mehr": Erreichbar ueber das ⚙ rechts in den
 * Kopfzeilen von Heute, Touren und Training
 * (`ui/components/SettingsAction.kt`), verlassen ueber den Zurueck-Pfeil oder
 * die Systemzurueckgeste.
 *
 * Der Grund ist eine Platzrechnung: „Mehr" ist kein Ort, an den man geht,
 * sondern eine Schublade, in der man etwas nachschlaegt — Profil, Import,
 * Offline-Karten, Sync. Ein Viertel der immer sichtbaren Hauptnavigation war
 * dafuer der teuerste Platz der App fuer den seltensten Handgriff; „Touren"
 * hat ihn bekommen.
 *
 * Praktisch aendert das an dieser Datei zweierlei: Die Kopfzeile traegt einen
 * Zurueck-Pfeil ([onBack]) und startet eingeklappt — beides die Konvention des
 * Leitfadens fuer die zweite Ebene (siehe
 * `ui/components/OneUiTopAppBar.kt`, `initiallyCollapsed`). Auf der Route
 * „mehr" gibt es ausserdem weder Navigationskapsel noch Aufnahme-Knopf; die
 * Bodenfreiheit ([LocalFloatingNavigationBarSpace]) meldet dort nur noch die
 * Gestenleiste, ohne dass diese Datei etwas davon wissen muesste.
 *
 * ## Gruppen statt neun Vollkarten
 * Der Screen zeigte frueher neun vollstaendig ausgeklappte Themenkarten
 * untereinander — eine Schublade mit neun Faechern, alle gleichzeitig offen.
 * Er ist jetzt nach dem Muster der One-UI-Einstellungsliste gebaut: drei
 * versal beschriftete Gruppen ([MoreGroup]), jede eine einzige Karte
 * ([MoreGroupCard]) mit flachen, einzeln aufklappbaren Zeilen ([MoreRow]).
 * Tiefe entsteht erst beim Antippen einer Zeile — vorher sieht man nur den
 * Titel und, wo vorhanden, eine Statuszeile.
 *
 *  * **„Profil & Daten"** — Profil, Daten & Backup, Health Connect. Alle drei
 *    drehen sich um dieselbe Frage: Woher kommen die Zahlen, mit denen die
 *    App rechnet?
 *  * **„Karte"** — Offline-Karten, Karten fuer Offline-Routing. Beide laden
 *    etwas fuers netzlose Fahren herunter (siehe `OfflineRoutingCard.kt` fuer
 *    die Abgrenzung).
 *  * **„App"** — Aufzeichnung, Erinnerungen, Sync (Selfhost), Ueber.
 *    Verhalten und Rahmendaten der App selbst, ohne Bezug zu einer
 *    bestimmten Tour.
 *
 * Jede Zeile ruft eine `…Content()`-Funktion aus der jeweiligen Datei dieses
 * Pakets auf — das unveraenderte Innenleben (Formulare, Dialoge, Launcher)
 * der frueheren Vollkarte, nur ohne deren eigene Karten-Huelle und
 * Titel-Text (das uebernimmt jetzt [MoreRow]). Details und bewusste
 * Abweichungen vom Dart-Original stehen weiterhin im KDoc der jeweiligen
 * Datei.
 *
 * Die **Reihenfolge** der Gruppen und Zeilen ist auf den Erstnutzer hin
 * sortiert (Begruendung im Rumpf), nicht mehr die des Dart-Originals.
 *
 * Darueber liegt — nur wenn es etwas zu melden gibt — die Update-Karte
 * (`UpdateCard.kt`), ausserhalb jeder Gruppe.
 *
 * ## Kartenstil ist umgezogen
 * Die Kartenstil-Auswahl hatte zwei Wohnorte: als Bottom-Sheet auf der Karte
 * *und* als eigene Karte hier. Dieselbe Entscheidung an zwei Stellen zu
 * treffen ist keine Bequemlichkeit, sondern eine offene Frage, welche der
 * beiden gerade gilt. Die Auswahl lebt jetzt ausschliesslich dort, wo ihre
 * Wirkung sofort sichtbar ist — auf der Karte, ueber dem Ebenen-Knopf
 * (`ui/map/MapScreen.kt`). `MapStyleCard.kt` ist ersatzlos entfallen.
 *
 * ## Sprungziele von aussen
 * [AppViewModel.pendingMoreSection] nennt eine Zeile, zu der dieser Screen
 * von aussen springen soll (siehe [moreGroupIndex] fuer die Zuordnung zur
 * Gruppe). Der Screen scrollt beim Eintreffen zur Gruppe **und** klappt die
 * gemeinte Zeile auf ([MoreRow.expandOnArrival]) — das Aufklappen selbst
 * zeigt, wo man gelandet ist. Einen zusaetzlichen Leuchtrahmen braucht es
 * dafuer nicht mehr: Der fruehere Rahmen war das Eingestaendnis, dass sich
 * neun gleich aussehende Vollkarten sonst nicht unterscheiden liessen: eine
 * aufgeklappte Zeile neben eingeklappten braucht diese Krücke nicht.
 *
 * ## Weitere bewusste Abweichungen vom Original
 *  * **Offline-Karten-Verwaltung** ersetzt die Kachel-Cache-Karte des
 *    Originals (`TileCache`): Die native App nutzt MapLibres eigene
 *    Offline-Regionen statt eines selbstgebauten Tile-Caches. Der Download
 *    neuer Regionen gehoert dem Karten-Screen; diese Karte verwaltet nur
 *    (Auflisten, Loeschen).
 *  * **Erinnerungen** (neu, kein Dart-Vorbild): Die Flutter-App hatte
 *    keinerlei geplante Hintergrundarbeit; siehe `ReminderCard.kt` und
 *    `reminder/ReminderScheduler.kt`.
 *  * **Kein gestaffeltes Einblenden** (`_EntranceFade` im Original): rein
 *    kosmetisch, verzichtbar fuer die Kernfunktion — siehe Report des Agents.
 *  * **Kein Animations-Toggle fuer „Erweitert"** im Profil (`AnimatedSize`
 *    im Original): einfacher Sichtbarkeits-Umschalter statt Groessen-
 *    Animation, gleiches Ergebnis ohne zusaetzliche Animations-API.
 */
/**
 * @param onBack fuehrt aus dem Mehr-Bereich zurueck dorthin, von wo das
 *   Zahnrad angetippt wurde (in der App `navController.popBackStack()`).
 *   Optional, damit Vorschauen und Tests den Bildschirm ohne Navigationsgraph
 *   zeigen koennen — ohne Rueckweg entfaellt schlicht der Pfeil.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(appViewModel: AppViewModel, onBack: (() -> Unit)? = null) {
    val snackbarHostState = remember { SnackbarHostState() }
    val updateVersion by appViewModel.updateAvailable.collectAsStateWithLifecycle()
    val requestedSection by appViewModel.pendingMoreSection.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    // Welche Zeile beim Eintreffen aufklappen soll; `null` = keine. Eigener
    // Zustand und nicht `requestedSection` direkt, weil die Bitte sofort
    // quittiert wird (siehe unten) — das Aufklapp-Signal soll den
    // Tab-Wechsel aber ueberdauern, bis [MoreRow] es uebernimmt.
    var expandTarget by remember { mutableStateOf<MoreSection?>(null) }

    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        appViewModel.refreshHealthConnection()
    }

    // Die Update-Karte steht ueber allem und verschiebt damit jeden Index um
    // eins — deshalb wird sie hier mitgezaehlt statt in [moreGroupIndex].
    val updateCardShown = updateVersion != null
    LaunchedEffect(requestedSection, updateCardShown) {
        val wanted = requestedSection ?: return@LaunchedEffect
        val index = moreGroupIndex(wanted)
        listState.animateScrollToItem(index + if (updateCardShown) 1 else 0)
        appViewModel.consumeMoreSectionRequest()
        expandTarget = wanted
    }

    // Zweite Ebene, also eingeklappt startend: Wer das Zahnrad antippt, will
    // die Einstellungen sehen und nicht zuerst das Wort „Mehr" in Grossschrift
    // (siehe `oneUiTopAppBarScrollBehavior`). Aufziehen laesst sie sich
    // trotzdem, die Leiste bleibt dieselbe.
    val scrollBehavior = oneUiTopAppBarScrollBehavior(initiallyCollapsed = true)

    Scaffold(
        // Siehe TourList.kt: Die aeussere Huelle (TrailscapeApp) hat die
        // System-Insets bereits aufgeloest — hier duerfen sie nicht nochmal
        // aufschlagen.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OneUiLargeTopAppBar(
                title = "Mehr",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Zurück",
                            )
                        }
                    }
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
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth(),
                contentPadding = screenContentPadding(),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                // Ganz oben und nur, wenn es wirklich etwas Neues gibt: Die
                // App aktualisiert sich nicht von selbst (Sideload), der
                // Hinweis ist also die einzige Nachricht darueber — und
                // verschwindet dauerhaft, sobald er weggewischt wird.
                updateVersion?.let { version ->
                    item {
                        UpdateNoticeCard(
                            versionName = version,
                            onDismiss = appViewModel::dismissUpdateNotice,
                        )
                    }
                }
                // Gruppe 1 von 3, Index 0 in [moreGroupIndex]: erst das
                // Profil (ohne Alter und Gewicht rechnet nichts richtig),
                // dann die beiden Wege, auf denen Daten hereinkommen — der
                // Import und Health Connect.
                item {
                    MoreGroup(label = "Profil & Daten") {
                        MoreRow(
                            title = "Profil",
                            expandOnArrival = expandTarget == MoreSection.PROFILE,
                        ) { ProfileCardContent(appViewModel) }
                        HorizontalDivider()
                        MoreRow(
                            title = "Daten & Backup",
                            expandOnArrival = expandTarget == MoreSection.BACKUP,
                        ) { BackupCardContent(appViewModel) }
                        HorizontalDivider()
                        MoreRow(
                            title = "Health Connect",
                            expandOnArrival = expandTarget == MoreSection.HEALTH,
                        ) { HealthCardContent(appViewModel) }
                    }
                }
                // Gruppe 2 von 3: Beide Zeilen laden etwas fuer die netzlose
                // Fahrt herunter, meinen aber Verschiedenes (Kartenbild gegen
                // Wegedaten) — nebeneinander ist der Unterschied eine Frage
                // von zwei Zeilen Text (siehe OfflineRoutingCard.kt).
                item {
                    MoreGroup(label = "Karte") {
                        MoreRow(
                            title = "Offline-Karten",
                        ) { OfflineMapsCardContent(onMessage = appViewModel::showMessage) }
                        HorizontalDivider()
                        MoreRow(
                            title = "Karten für Offline-Routing",
                        ) { OfflineRoutingCardContent(appViewModel) }
                    }
                }
                // Gruppe 3 von 3: Verhalten und Rahmendaten der App selbst.
                // Die Erinnerungen stehen zuerst, weil sie entscheiden, ob
                // die App von sich aus etwas sagt — das wiegt schwerer als
                // Sync-Zugangsdaten oder die Über-Karte.
                item {
                    MoreGroup(label = "App") {
                        MoreRow(title = "Aufzeichnung") { RecordingCardContent() }
                        HorizontalDivider()
                        MoreRow(title = "Erinnerungen") { ReminderCardContent(appViewModel) }
                        HorizontalDivider()
                        MoreRow(title = "Sync (Selfhost)") { SyncCardContent(appViewModel) }
                        HorizontalDivider()
                        MoreRow(title = "Über") { AboutCardContent(appViewModel) }
                    }
                }
            }
        }
    }
}

/**
 * Zu welcher Gruppe (Index in der `LazyColumn`, ohne die vorgeschaltete
 * Update-Karte — siehe Aufrufstelle) ein Sprungziel gehoert. Alle drei
 * moeglichen Werte liegen heute in derselben Gruppe „Profil & Daten"
 * (Index 0); ein Sprung faehrt dorthin, [MoreRow.expandOnArrival] klappt
 * dann die passende Zeile auf.
 *
 * Wird die Gruppierung im Rumpf geaendert, muss diese Zuordnung mitgehen;
 * deshalb stehen beide in derselben Datei und unmittelbar untereinander.
 */
private fun moreGroupIndex(section: MoreSection): Int = when (section) {
    MoreSection.PROFILE, MoreSection.BACKUP, MoreSection.HEALTH -> 0
}
