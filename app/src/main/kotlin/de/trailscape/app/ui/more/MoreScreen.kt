package de.trailscape.app.ui.more

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.trailscapePrefs
import de.trailscape.app.i18n.AppLocale
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.asString
import de.trailscape.app.record.PREF_AUTO_PAUSE
import de.trailscape.app.record.PREF_VOICE
import de.trailscape.app.record.autoPauseAktiviert
import de.trailscape.app.record.sprachansagenAktiviert
import de.trailscape.app.routing.SegmentDownloads
import de.trailscape.app.sensors.BleSensors
import de.trailscape.app.strava.StravaConfig
import de.trailscape.app.strava.StravaServices
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.FileImportNoticeEffect
import de.trailscape.app.ui.IndoorDestination
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.TwoPaneLayout
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.M3Transitions
import de.trailscape.app.ui.theme.TabletCardGap
import de.trailscape.app.ui.theme.TabletScreenPadding
import de.trailscape.app.ui.theme.WindowLayoutClass
import de.trailscape.app.ui.theme.useTwoPaneLayout
import de.trailscape.core.healthSyncStorageKey
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/**
 * Die Einstellungen — die Route „mehr" hinter dem ⚙ in den Kopfzeilen
 * aller vier Tabs (`ui/components/SettingsAction.kt`), verlassen
 * ueber den Zurueck-Pfeil oder die Systemzurueckgeste. Urspruenglich ein Port
 * von `lib/screens/more_screen.dart`, seit der Ueberarbeitung „Klartext"
 * (`docs/design/prototyp-klartext.html`, `#s-settings`) aber anders gebaut.
 *
 * ## Zwei Ebenen: Liste → Seite
 * Die fruehere Fassung hatte neun Akkordeon-Zeilen in drei Gruppen und bis zu
 * vier Ebenen (Zahnrad → Liste → Akkordeon → „Erweitert"); zugeklappt zeigte
 * keine Zeile, wie es um sie steht. Jetzt:
 *
 *  * **Liste** — fuenf benannte Gruppen (Du, Geräte, Fahren, Daten, App) mit
 *    zusammen zwoelf Zeilen, ohne Strava-Zugangsdaten elf ([SettingsNavRow]). Jede Zeile nennt ihren Zustand in
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
fun MoreScreen(
    appViewModel: AppViewModel,
    onBack: (() -> Unit)? = null,
    onOpenIndoorSettings: () -> Unit = { appViewModel.requestIndoor(IndoorDestination.SETTINGS) },
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val requestedSection by appViewModel.pendingMoreSection.collectAsStateWithLifecycle()
    val layoutClass = LocalWindowLayoutClass.current
    val isExpanded = useTwoPaneLayout(layoutClass)

    val listState = rememberLazyListState()
    // Ein anstehendes Sprungziel gilt schon fuer das erste Bild — sonst
    // blitzte vor der gemeinten Seite kurz die Liste auf.
    var page by rememberSaveable {
        mutableStateOf(appViewModel.pendingMoreSection.value?.toPage())
    }
    // Ob die aktuelle Seite per Sprungziel geoeffnet wurde — dann fuehrt
    // Zurueck aus den Einstellungen hinaus statt in die Liste.
    var arrivedDirectly by rememberSaveable { mutableStateOf(page != null) }
    var offlineRefreshTick by remember { mutableIntStateOf(0) }

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
    BackHandler(enabled = !isExpanded && page != null) { leavePage() }

    val density = LocalDensity.current
    Scaffold(
        // Siehe TourList.kt: Die aeussere Huelle (TrailscapeApp) hat die
        // System-Insets bereits aufgeloest — hier duerfen sie nicht nochmal
        // aufschlagen.
        containerColor = MaterialTheme.colorScheme.background,
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
        if (isExpanded) {
            val activePage = page ?: SettingsPage.PROFILE
            TwoPaneLayout(
                primaryWeight = 0.40f,
                gap = TabletCardGap,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = TabletScreenPadding),
                primary = {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ScreenHeader(
                            title = stringResource(R.string.more_screen_title),
                            backLabel = stringResource(R.string.common_action_back),
                            onBack = onBack,
                            modifier = Modifier.padding(top = TabletScreenPadding),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            SettingsList(
                                appViewModel = appViewModel,
                                listState = listState,
                                onOpen = {
                                    arrivedDirectly = false
                                    page = it
                                },
                                selectedPage = activePage,
                                onOpenIndoorSettings = onOpenIndoorSettings,
                                offlineRefreshTick = offlineRefreshTick,
                                contentPadding = PaddingValues(
                                    top = CardGap,
                                    bottom = TabletScreenPadding + LocalFloatingNavigationBarSpace.current,
                                ),
                            )
                        }
                    }
                },
                secondary = {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ScreenHeader(
                            title = stringResource(activePage.titleRes),
                            onBack = null,
                            modifier = Modifier.padding(top = TabletScreenPadding),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            key(activePage) {
                                SettingsPageContent(
                                    page = activePage,
                                    appViewModel = appViewModel,
                                    modifier = Modifier
                                        .widthIn(max = ContentMaxWidth)
                                        .fillMaxWidth(),
                                    contentPadding = PaddingValues(
                                        top = CardGap,
                                        bottom = TabletScreenPadding + LocalFloatingNavigationBarSpace.current,
                                    ),
                                    onOfflineChanged = { offlineRefreshTick++ },
                                )
                            }
                        }
                    }
                },
            )
        } else {
            val outerPadding = if (layoutClass == WindowLayoutClass.Compact) {
                ScreenPadding
            } else {
                TabletScreenPadding
            }
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
                            title = current?.let { stringResource(it.titleRes) }
                                ?: stringResource(R.string.more_screen_title),
                            backLabel = if (current != null && !arrivedDirectly) {
                                stringResource(R.string.more_screen_title)
                            } else {
                                stringResource(R.string.common_action_back)
                            },
                            onBack = if (current != null) ::leavePage else onBack,
                            modifier = Modifier.padding(
                                start = outerPadding,
                                end = outerPadding,
                                top = outerPadding,
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
                                    onOpenIndoorSettings = onOpenIndoorSettings,
                                    offlineRefreshTick = offlineRefreshTick,
                                )
                            } else {
                                SettingsPageContent(
                                    page = current,
                                    appViewModel = appViewModel,
                                    onOfflineChanged = { offlineRefreshTick++ },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Die Seiten der Einstellungen. Die Reihenfolge der Liste (fuenf Gruppen)
 * steht in [SettingsList]; „Indoor" ist keine Seite hier, sondern ein eigenes
 * Ziel der Huelle ([AppViewModel.requestIndoor]). [titleRes]
 * steht in der Listenzeile und in der Kopfzeile der Seite.
 */
internal enum class SettingsPage(@param:StringRes val titleRes: Int) {
    PROFILE(R.string.more_page_profile_title),
    HEALTH(R.string.more_page_health_title),
    SENSORS(R.string.more_page_sensors_title),
    RECORDING(R.string.more_page_recording_title),
    REMINDERS(R.string.more_page_reminders_title),
    OFFLINE(R.string.more_page_offline_title),
    BACKUP(R.string.more_page_backup_title),
    // Nur in Builds mit Strava-Zugangsdaten sichtbar (StravaConfig.available).
    STRAVA(R.string.more_page_strava_title),
    SYNC(R.string.more_page_sync_title),
    LANGUAGE(R.string.more_language_title),
    ABOUT(R.string.more_page_about_title),
}

/** Welche Seite ein Sprungziel von aussen meint. */
private fun MoreSection.toPage(): SettingsPage = when (this) {
    MoreSection.PROFILE -> SettingsPage.PROFILE
    MoreSection.HEALTH -> SettingsPage.HEALTH
    MoreSection.STRAVA -> SettingsPage.STRAVA
}

/**
 * Die Liste: Update-Hinweis (falls vorhanden), dann fuenf benannte Gruppen
 * (`docs/ux/seitenkarte.md`, Bereich „Einstellungen"):
 *
 *  * **Du** — Profil, Uhr & Gesundheitsdaten
 *  * **Geräte** — Sensoren & Trainer (der eine Ort zum Koppeln)
 *  * **Fahren** — Aufzeichnung & Ansagen, Indoor, Erinnerungen
 *  * **Daten** — Backup, Karten offline, Strava (nur in Builds mit
 *    Zugangsdaten), Sync mit eigenem Server
 *  * **App** — Sprache, Über Trailscape
 *
 * Die Reihenfolge folgt dem Erstnutzer: erst das Profil (ohne Alter und
 * Gewicht rechnet nichts richtig) und die Uhr, dann die Geraete, dann das
 * Verhalten beim Fahren, zuletzt Daten und die App selbst.
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
    selectedPage: SettingsPage? = null,
    onOpenIndoorSettings: () -> Unit = { appViewModel.requestIndoor(IndoorDestination.SETTINGS) },
    offlineRefreshTick: Int = 0,
    contentPadding: PaddingValues = screenContentPadding(),
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val updateVersion by appViewModel.updateAvailable.collectAsStateWithLifecycle()
    val profile by appViewModel.profile.collectAsStateWithLifecycle()
    val profileConfirmed by appViewModel.profileConfirmed.collectAsStateWithLifecycle()
    val health by appViewModel.healthConnection.collectAsStateWithLifecycle()
    val lastSyncReport by appViewModel.lastSyncReport.collectAsStateWithLifecycle()
    val reminders by appViewModel.reminderSettings.collectAsStateWithLifecycle()
    val syncConfig by appViewModel.syncConfig.collectAsStateWithLifecycle()

    val prefs = remember(context) { trailscapePrefs(context) }
    var prefsTick by remember { mutableIntStateOf(0) }
    var healthRefreshTick by remember { mutableIntStateOf(0) }
    var localOfflineRefreshTick by remember { mutableIntStateOf(0) }

    DisposableEffect(prefs) {
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            fun bump() {
                when (key) {
                    null -> {
                        prefsTick++
                        healthRefreshTick++
                    }
                    PREF_AUTO_PAUSE,
                    PREF_VOICE,
                    PREF_LAST_BACKUP_AT,
                    BleSensors.PREF_BLE_SENSORS,
                    -> prefsTick++
                    healthSyncStorageKey -> healthRefreshTick++
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                bump()
            } else {
                mainHandler.post { bump() }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LifecycleResumeEffect(Unit) {
        prefsTick++
        healthRefreshTick++
        localOfflineRefreshTick++
        onPauseOrDispose { }
    }

    val segmentDownloadStatus by remember(context) {
        runCatching { SegmentDownloads.statusFlow(context) }.getOrNull() ?: emptyFlow()
    }.collectAsStateWithLifecycle(initialValue = null)

    val recordingStatus = remember(prefsTick) {
        recordingStatusText(autoPauseAktiviert(context), sprachansagenAktiviert(context))
    }
    val lastBackupAt = remember(prefsTick) { lastBackupAt(context) }
    val sensorsStatus = remember(prefsTick) { sensorsListStatus(context) }
    var lastHealthImport by remember { mutableStateOf<LocalDateTime?>(null) }
    var offlineStatus by remember { mutableStateOf(offlineStatusText(null, null, 0L, language)) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    } ?: stringResource(R.string.common_unknown)

    LaunchedEffect(health, lastSyncReport, healthRefreshTick) {
        lastHealthImport = runCatching {
            withContext(Dispatchers.IO) { appViewModel.healthSync.lastImportAt() }
        }.getOrNull()
    }
    LaunchedEffect(
        language,
        selectedPage,
        offlineRefreshTick,
        localOfflineRefreshTick,
        segmentDownloadStatus?.finished,
        segmentDownloadStatus?.fileName,
    ) {
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
            language = language,
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
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
        // ---------------------------------------------------------------- Du
        item {
            MoreGroup(label = stringResource(R.string.more_list_group_you)) {
                SettingsNavRow(
                    title = stringResource(SettingsPage.PROFILE.titleRes),
                    status = profileStatusText(profile, profileConfirmed, language).asString(),
                    onClick = { onOpen(SettingsPage.PROFILE) },
                    selected = selectedPage == SettingsPage.PROFILE,
                )
                ListDivider()
                SettingsNavRow(
                    title = stringResource(SettingsPage.HEALTH.titleRes),
                    status = healthStatusText(health, lastHealthImport, language).asString(),
                    onClick = { onOpen(SettingsPage.HEALTH) },
                    selected = selectedPage == SettingsPage.HEALTH,
                )
            }
        }
        // ------------------------------------------------------------- Geraete
        item {
            MoreGroup(label = stringResource(R.string.more_list_group_devices)) {
                SettingsNavRow(
                    title = stringResource(SettingsPage.SENSORS.titleRes),
                    status = sensorsStatus,
                    onClick = { onOpen(SettingsPage.SENSORS) },
                    selected = selectedPage == SettingsPage.SENSORS,
                )
            }
        }
        // -------------------------------------------------------------- Fahren
        item {
            MoreGroup(label = stringResource(R.string.more_list_group_riding)) {
                SettingsNavRow(
                    title = stringResource(SettingsPage.RECORDING.titleRes),
                    status = recordingStatus.asString(),
                    onClick = { onOpen(SettingsPage.RECORDING) },
                    selected = selectedPage == SettingsPage.RECORDING,
                )
                ListDivider()
                // Indoor ist keine Seite dieses Bildschirms, sondern ein
                // eigenes Ziel im `NavHost` (die Seite haengt am
                // Indoor-ViewModel): Die Huelle oeffnet es ueber
                // [AppViewModel.requestIndoor], Zurueck fuehrt wieder hierher.
                // Fahrgefuehl, Strecken auf dem Geraet, Fahrprotokolle und die
                // Diagnose liegen dort — die frueheren Sprungzeilen
                // „Indoor-Training" und „Indoor-Diagnose" sind entfallen
                // (Indoor ist ein Tab, die Diagnose ein Teil dieser Seite).
                SettingsNavRow(
                    title = stringResource(R.string.more_list_indoor_title),
                    status = stringResource(R.string.more_list_indoor_status),
                    onClick = onOpenIndoorSettings,
                )
                ListDivider()
                SettingsNavRow(
                    title = stringResource(SettingsPage.REMINDERS.titleRes),
                    status = reminderStatusText(reminders).joinedStatus(),
                    onClick = { onOpen(SettingsPage.REMINDERS) },
                    selected = selectedPage == SettingsPage.REMINDERS,
                )
            }
        }
        // --------------------------------------------------------------- Daten
        item {
            MoreGroup(label = stringResource(R.string.more_list_group_data)) {
                val backupStatus = backupStatusText(lastBackupAt, language)
                SettingsNavRow(
                    title = stringResource(SettingsPage.BACKUP.titleRes),
                    status = (backupStatus ?: BACKUP_NEVER_TEXT).asString(),
                    // Eine Sicherung, die es nie gab, ist der eine Zustand in
                    // dieser Liste, der Handeln verlangt — deshalb in der
                    // Warnfarbe statt im ruhigen Grau.
                    statusColor = if (backupStatus == null) {
                        LocalSignalColors.current.warning
                    } else {
                        Color.Unspecified
                    },
                    onClick = { onOpen(SettingsPage.BACKUP) },
                    selected = selectedPage == SettingsPage.BACKUP,
                )
                ListDivider()
                SettingsNavRow(
                    title = stringResource(SettingsPage.OFFLINE.titleRes),
                    status = offlineStatus.joinedStatus(),
                    onClick = { onOpen(SettingsPage.OFFLINE) },
                    selected = selectedPage == SettingsPage.OFFLINE,
                )
                // Strava steht nur da, wenn der Build es kann — sonst gibt es
                // die Zeile schlicht nicht (siehe StravaConfig).
                if (StravaConfig.available) {
                    val stravaConnection by StravaServices.connection.collectAsStateWithLifecycle()
                    val stravaAutoUpload by StravaServices.autoUpload.collectAsStateWithLifecycle()
                    val stravaMessage by StravaServices.authMessage.collectAsStateWithLifecycle()
                    ListDivider()
                    SettingsNavRow(
                        title = stringResource(SettingsPage.STRAVA.titleRes),
                        status = stravaStatusText(stravaConnection, stravaAutoUpload, stravaMessage),
                        statusColor = if (stravaNeedsReconnect(stravaConnection, stravaMessage)) {
                            LocalSignalColors.current.warning
                        } else {
                            Color.Unspecified
                        },
                        onClick = { onOpen(SettingsPage.STRAVA) },
                        selected = selectedPage == SettingsPage.STRAVA,
                    )
                }
                ListDivider()
                SettingsNavRow(
                    title = stringResource(SettingsPage.SYNC.titleRes),
                    status = syncStatusText(syncConfig).asString(),
                    onClick = { onOpen(SettingsPage.SYNC) },
                    selected = selectedPage == SettingsPage.SYNC,
                )
            }
        }
        // ----------------------------------------------------------------- App
        item {
            MoreGroup(label = stringResource(R.string.more_list_group_app)) {
                SettingsNavRow(
                    title = stringResource(SettingsPage.LANGUAGE.titleRes),
                    status = languageStatusAnnotated(AppLocale.preference(context), LocalAppLanguage.current),
                    onClick = { onOpen(SettingsPage.LANGUAGE) },
                    selected = selectedPage == SettingsPage.LANGUAGE,
                )
                ListDivider()
                SettingsNavRow(
                    title = stringResource(SettingsPage.ABOUT.titleRes),
                    status = stringResource(R.string.more_list_about_status, versionName),
                    onClick = { onOpen(SettingsPage.ABOUT) },
                    selected = selectedPage == SettingsPage.ABOUT,
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
private fun SettingsPageContent(
    page: SettingsPage,
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = screenContentPadding(),
    onOfflineChanged: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        when (page) {
            SettingsPage.PROFILE -> SettingsSection { ProfileCardContent(appViewModel) }
            SettingsPage.HEALTH -> SettingsSection { HealthCardContent(appViewModel) }
            SettingsPage.SENSORS -> SettingsSection { SensorsCardContent() }
            SettingsPage.RECORDING -> {
                SettingsSection(label = stringResource(R.string.more_recording_section_recording)) {
                    RecordingCardContent()
                }
                SettingsSection(label = stringResource(R.string.more_recording_section_voice)) {
                    AnnouncementsCardContent()
                }
            }
            // Zweiter Abschnitt: der Schalter „Erfolge anzeigen" (docs/erfolge.md,
            // docs/ux/umbau.md) — beides sind Dinge, die zum Fahren anstossen.
            SettingsPage.REMINDERS -> {
                SettingsSection { ReminderCardContent(appViewModel) }
                de.trailscape.app.ui.erfolge.ErfolgeSettingsSection()
            }
            // Kartenbild und Routingdaten auf einer Seite: Beide laden etwas
            // fuers netzlose Fahren herunter, meinen aber Verschiedenes — die
            // beiden Abschnittstitel sagen den Unterschied.
            SettingsPage.OFFLINE -> {
                SettingsSection(label = stringResource(R.string.more_offline_section_maps)) {
                    OfflineMapsCardContent(
                        onMessage = appViewModel::showMessage,
                        onOpenMap = { appViewModel.requestTab(AppTab.MAP) },
                        onRegionsChanged = onOfflineChanged,
                    )
                }
                SettingsSection(label = stringResource(R.string.more_offline_section_routing)) {
                    OfflineRoutingCardContent(
                        appViewModel = appViewModel,
                        onSegmentsChanged = onOfflineChanged,
                    )
                }
            }
            SettingsPage.BACKUP -> SettingsSection { BackupCardContent(appViewModel) }
            SettingsPage.STRAVA -> SettingsSection { StravaCardContent() }
            SettingsPage.SYNC -> SettingsSection { SyncCardContent(appViewModel) }
            SettingsPage.LANGUAGE -> SettingsSection { LanguageCardContent() }
            SettingsPage.ABOUT -> {
                SettingsSection { ScienceCardContent() }
                SettingsSection { AboutCardContent(appViewModel) }
                SettingsSection(label = stringResource(R.string.more_about_section_licenses)) {
                    OpenSourceLicensesContent()
                }
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
