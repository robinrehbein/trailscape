package de.trailscape.app.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.data.AppServices
import de.trailscape.app.record.autoPauseAktiviert
import de.trailscape.app.record.sprachansagenAktiviert
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.FileImportNoticeEffect
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.M3Transitions
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Die Einstellungen — die Route „mehr" hinter dem ⚙ in den Kopfzeilen von
 * Heute, Verlauf und Training (`ui/components/SettingsAction.kt`), verlassen
 * ueber den Zurueck-Pfeil oder die Systemzurueckgeste. Urspruenglich ein Port
 * von `lib/screens/more_screen.dart`, seit der Ueberarbeitung „Klartext"
 * (`docs/design/prototyp-klartext.html`, `#s-settings`) aber anders gebaut.
 *
 * ## Zwei Ebenen: Liste → Seite
 * Die fruehere Fassung hatte neun Akkordeon-Zeilen in drei Gruppen und bis zu
 * vier Ebenen (Zahnrad → Liste → Akkordeon → „Erweitert"); zugeklappt zeigte
 * keine Zeile, wie es um sie steht. Jetzt:
 *
 *  * **Liste** — eine flache Karte mit sechs Zeilen und die Gruppe „App" mit
 *    zwei weiteren ([SettingsNavRow]). Jede Zeile nennt ihren Zustand in
 *    einer Statuszeile („Auto-Pause an · Ansagen an", „Noch nie gesichert"),
 *    siehe `SettingsStatus.kt`.
 *  * **Seite** — Antippen oeffnet die Seite der Zeile ([SettingsPage]) im
 *    selben Bildschirm; die Kopfzeile traegt dann deren Titel. Was darunter
 *    noch aufklappt („Erweitert" im Profil, Lizenzen), ist ein Abschnitt der
 *    Seite, keine dritte Ebene.
 *
 * Die Seiten sind **kein** eigener Navigationsgraph, sondern ein
 * [rememberSaveable]-Zustand dieses Bildschirms samt [BackHandler]: Die Route
 * „mehr" gehoert der Huelle (`ui/TrailscapeApp.kt`), und eine zweite
 * Navigationsebene darin waere mehr Maschinerie als ein Aufzaehlungswert.
 * Zurueck (Pfeil oder Geste) fuehrt von einer Seite in die Liste, von der
 * Liste hinaus — mit einer Ausnahme, siehe „Sprungziele".
 *
 * ## Alles speichert sofort
 * Es gibt keinen Speichern-Knopf mehr, auf keiner Seite. Das Profil prueft
 * jedes Feld fuer sich und uebernimmt gueltige Werte waehrend der Eingabe
 * (`ProfileCard.kt`), der Sync schreibt Server-URL und Token beim Tippen
 * (`SyncCard.kt`); der Knopf dort gleicht nur noch ab.
 *
 * ## Sprungziele von aussen
 * [AppViewModel.pendingMoreSection] nennt eine Seite, auf der dieser
 * Bildschirm oeffnen soll (Heute, Training und Verlauf verweisen so etwa auf
 * den Import oder das Profil). Er oeffnet dann direkt diese Seite. Zurueck
 * fuehrt in diesem Fall dorthin, **woher man kam** — nicht in eine Liste, die
 * man nie gesehen hat.
 *
 * ## Update-Hinweis
 * Ueber der Liste steht — nur wenn es etwas zu melden gibt — die Update-Karte
 * (`UpdateCard.kt`).
 *
 * ## Kartenstil ist umgezogen
 * Die Kartenstil-Auswahl lebt ausschliesslich auf der Karte, ueber dem
 * Ebenen-Knopf (`ui/map/MapScreen.kt`) — dort, wo ihre Wirkung sofort
 * sichtbar ist.
 *
 * @param onBack fuehrt aus den Einstellungen zurueck dorthin, von wo das
 *   Zahnrad angetippt wurde (in der App `navController.popBackStack()`).
 *   Optional, damit Vorschauen und Tests den Bildschirm ohne Navigationsgraph
 *   zeigen koennen — ohne Rueckweg entfaellt auf der Liste schlicht der Pfeil.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(appViewModel: AppViewModel, onBack: (() -> Unit)? = null) {
    val snackbarHostState = remember { SnackbarHostState() }
    val requestedSection by appViewModel.pendingMoreSection.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    // Ein anstehendes Sprungziel gilt schon fuer das erste Bild — sonst
    // blitzte vor der gemeinten Seite kurz die Liste auf.
    var page by rememberSaveable {
        mutableStateOf(appViewModel.pendingMoreSection.value?.toPage())
    }
    // Ob die aktuelle Seite per Sprungziel geoeffnet wurde — dann fuehrt
    // Zurueck aus den Einstellungen hinaus statt in die Liste.
    var arrivedDirectly by rememberSaveable { mutableStateOf(page != null) }

    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    // Ergebnis des Datei-Imports unter Daten & Backup. Meldungen aus
    // Teilen/Oeffnen bleiben liegen: Dafuer springt die App in den Verlauf.
    FileImportNoticeEffect(appViewModel, snackbarHostState, acceptHistory = false)
    LaunchedEffect(Unit) {
        appViewModel.refreshHealthConnection()
    }
    LaunchedEffect(requestedSection) {
        val wanted = requestedSection ?: return@LaunchedEffect
        page = wanted.toPage()
        arrivedDirectly = true
        appViewModel.consumeMoreSectionRequest()
    }

    fun leavePage() {
        if (arrivedDirectly && onBack != null) {
            onBack()
        } else {
            page = null
        }
        arrivedDirectly = false
    }
    BackHandler(enabled = page != null) { leavePage() }

    val density = LocalDensity.current
    Scaffold(
        // Siehe TourList.kt: Die aeussere Huelle (TrailscapeApp) hat die
        // System-Insets bereits aufgeloest — hier duerfen sie nicht nochmal
        // aufschlagen.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
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
            // M3 „Forward and backward": Liste → Seite gleitet nach links,
            // zurueck nach rechts; die Kopfzeile gehoert zur Seite und
            // gleitet mit. Wer direkt auf eine Seite gesprungen ist, kommt
            // schon mit dem Uebergang des `NavHost` herein — ein zweiter
            // Schub waere doppelte Bewegung.
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (arrivedDirectly && initialState == null) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        M3Transitions.sharedAxisX(forward = targetState != null, density)
                    }
                },
                label = "Einstellungsseite",
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth(),
            ) { current ->
                Column(modifier = Modifier.fillMaxSize()) {
                    // Dieselbe Kopfzeile wie auf den Tabs (Fuehrung
                    // „Klartext"): „‹ Zurück" bzw. „‹ Einstellungen" oben,
                    // darunter der Titel.
                    ScreenHeader(
                        title = current?.title ?: "Einstellungen",
                        backLabel = if (current != null && !arrivedDirectly) "Einstellungen" else "Zurück",
                        onBack = if (current != null) ::leavePage else onBack,
                        modifier = Modifier.padding(
                            start = ScreenPadding,
                            end = ScreenPadding,
                            top = ScreenPadding,
                        ),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (current == null) {
                            SettingsList(
                                appViewModel = appViewModel,
                                listState = listState,
                                onOpen = {
                                    arrivedDirectly = false
                                    page = it
                                },
                            )
                        } else {
                            SettingsPageContent(page = current, appViewModel = appViewModel)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Die Seiten der Einstellungen, in der Reihenfolge der Liste. [title] steht
 * in der Listenzeile und in der Kopfzeile der Seite.
 */
internal enum class SettingsPage(val title: String) {
    PROFILE("Profil"),
    HEALTH("Uhr & Gesundheitsdaten"),
    RECORDING("Aufzeichnung & Ansagen"),
    REMINDERS("Erinnerungen"),
    OFFLINE("Karten offline"),
    BACKUP("Import & Backup"),
    SYNC("Sync mit eigenem Server"),
    ABOUT("Über Trailscape"),
}

/** Welche Seite ein Sprungziel von aussen meint. */
private fun MoreSection.toPage(): SettingsPage = when (this) {
    MoreSection.PROFILE -> SettingsPage.PROFILE
    MoreSection.BACKUP -> SettingsPage.BACKUP
    MoreSection.HEALTH -> SettingsPage.HEALTH
}

/**
 * Die Liste: Update-Hinweis (falls vorhanden), sechs Zeilen ohne
 * Gruppenlabel, dann die Gruppe „App".
 *
 * Die Reihenfolge folgt dem Erstnutzer: erst das Profil (ohne Alter und
 * Gewicht rechnet nichts richtig), dann die Uhr als Datenquelle, dann das
 * Verhalten beim Fahren, zuletzt Speicher und Sicherung. Sync und „Über"
 * betreffen die App selbst und stehen deshalb abgesetzt.
 *
 * Die Zustaende, die nicht als `StateFlow` vorliegen (Einstellungen in den
 * `SharedPreferences`, Offline-Bestand, letzter Import), liest die Liste bei
 * jedem Erscheinen neu — also auch nach der Rueckkehr von einer Seite, auf
 * der sie sich gerade geaendert haben.
 */
@Composable
private fun SettingsList(
    appViewModel: AppViewModel,
    listState: LazyListState,
    onOpen: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current
    val updateVersion by appViewModel.updateAvailable.collectAsStateWithLifecycle()
    val profile by appViewModel.profile.collectAsStateWithLifecycle()
    val profileConfirmed by appViewModel.profileConfirmed.collectAsStateWithLifecycle()
    val health by appViewModel.healthConnection.collectAsStateWithLifecycle()
    val reminders by appViewModel.reminderSettings.collectAsStateWithLifecycle()
    val syncConfig by appViewModel.syncConfig.collectAsStateWithLifecycle()

    val recordingStatus = remember {
        recordingStatusText(autoPauseAktiviert(context), sprachansagenAktiviert(context))
    }
    val lastBackupAt = remember { lastBackupAt(context) }
    var lastHealthImport by remember { mutableStateOf<LocalDateTime?>(null) }
    var offlineStatus by remember { mutableStateOf(offlineStatusText(null, null, 0L)) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unbekannt"
    }

    LaunchedEffect(Unit) {
        lastHealthImport = runCatching {
            withContext(Dispatchers.IO) { appViewModel.healthSync.lastImportAt() }
        }.getOrNull()
    }
    LaunchedEffect(Unit) {
        // Beide Bestaende getrennt abfragen: Ist der Kartenspeicher gerade
        // nicht lesbar, soll die Zahl der Routing-Kacheln trotzdem erscheinen.
        val maps = runCatching { offlineMapsSummary(context) }.getOrNull()
        val routing = runCatching {
            withContext(Dispatchers.IO) { AppServices.segmentInventory.list() }
        }.getOrNull()
        offlineStatus = offlineStatusText(
            mapRegions = maps?.first ?: 0,
            routingTiles = routing?.size ?: 0,
            totalBytes = (maps?.second ?: 0L) + (routing?.sumOf { it.sizeBytes } ?: 0L),
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenContentPadding(),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        // Ganz oben und nur, wenn es wirklich etwas Neues gibt: Die App
        // aktualisiert sich nicht von selbst (Sideload), der Hinweis ist also
        // die einzige Nachricht darueber.
        updateVersion?.let { version ->
            item {
                UpdateNoticeCard(
                    versionName = version,
                    onDismiss = appViewModel::dismissUpdateNotice,
                )
            }
        }
        item {
            MoreGroup(label = null) {
                SettingsNavRow(
                    title = SettingsPage.PROFILE.title,
                    status = profileStatusText(profile, profileConfirmed),
                    onClick = { onOpen(SettingsPage.PROFILE) },
                )
                ListDivider()
                SettingsNavRow(
                    title = SettingsPage.HEALTH.title,
                    status = healthStatusText(health, lastHealthImport),
                    onClick = { onOpen(SettingsPage.HEALTH) },
                )
                ListDivider()
                SettingsNavRow(
                    title = SettingsPage.RECORDING.title,
                    status = recordingStatus,
                    onClick = { onOpen(SettingsPage.RECORDING) },
                )
                ListDivider()
                SettingsNavRow(
                    title = SettingsPage.REMINDERS.title,
                    status = reminderStatusText(reminders),
                    onClick = { onOpen(SettingsPage.REMINDERS) },
                )
                ListDivider()
                SettingsNavRow(
                    title = SettingsPage.OFFLINE.title,
                    status = offlineStatus,
                    onClick = { onOpen(SettingsPage.OFFLINE) },
                )
                ListDivider()
                val backupStatus = backupStatusText(lastBackupAt)
                SettingsNavRow(
                    title = SettingsPage.BACKUP.title,
                    status = backupStatus ?: BACKUP_NEVER_TEXT,
                    // Eine Sicherung, die es nie gab, ist der eine Zustand in
                    // dieser Liste, der Handeln verlangt — deshalb in der
                    // Warnfarbe statt im ruhigen Grau.
                    statusColor = if (backupStatus == null) {
                        LocalSignalColors.current.warning
                    } else {
                        Color.Unspecified
                    },
                    onClick = { onOpen(SettingsPage.BACKUP) },
                )
            }
        }
        item {
            MoreGroup(label = "App") {
                SettingsNavRow(
                    title = SettingsPage.SYNC.title,
                    status = syncStatusText(syncConfig),
                    onClick = { onOpen(SettingsPage.SYNC) },
                )
                ListDivider()
                SettingsNavRow(
                    title = SettingsPage.ABOUT.title,
                    status = "Version $versionName",
                    onClick = { onOpen(SettingsPage.ABOUT) },
                )
            }
        }
    }
}

/**
 * Eine Seite: die Inhalte der jeweiligen Datei dieses Pakets, in Abschnitte
 * ([SettingsSection]) gefasst. Die Seite scrollt als Ganzes.
 */
@Composable
private fun SettingsPageContent(page: SettingsPage, appViewModel: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenContentPadding()),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        when (page) {
            SettingsPage.PROFILE -> SettingsSection { ProfileCardContent(appViewModel) }
            SettingsPage.HEALTH -> SettingsSection { HealthCardContent(appViewModel) }
            SettingsPage.RECORDING -> {
                SettingsSection(label = "Aufzeichnung") { RecordingCardContent() }
                SettingsSection(label = "Ansagen") { AnnouncementsCardContent() }
            }
            SettingsPage.REMINDERS -> SettingsSection { ReminderCardContent(appViewModel) }
            // Kartenbild und Routingdaten auf einer Seite: Beide laden etwas
            // fuers netzlose Fahren herunter, meinen aber Verschiedenes — die
            // beiden Abschnittstitel sagen den Unterschied.
            SettingsPage.OFFLINE -> {
                SettingsSection(label = "Kartenbild") {
                    OfflineMapsCardContent(onMessage = appViewModel::showMessage)
                }
                SettingsSection(label = "Routingdaten") { OfflineRoutingCardContent(appViewModel) }
            }
            SettingsPage.BACKUP -> SettingsSection { BackupCardContent(appViewModel) }
            SettingsPage.SYNC -> SettingsSection { SyncCardContent(appViewModel) }
            SettingsPage.ABOUT -> {
                SettingsSection { AboutCardContent(appViewModel) }
                SettingsSection(label = "Open-Source-Lizenzen") { OpenSourceLicensesContent() }
            }
        }
    }
}

/**
 * Trennlinie zwischen zwei Zeilen einer Einstellungskarte — eingerueckt bis
 * zum Text, wie in der Liste des Verlaufs, statt randlos.
 */
@Composable
private fun ListDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = CardPadding),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
