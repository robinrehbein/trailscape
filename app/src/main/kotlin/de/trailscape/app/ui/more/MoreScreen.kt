package de.trailscape.app.ui.more

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.ScreenPadding
import kotlinx.coroutines.delay

/**
 * „Mehr"-Tab — Port von `lib/screens/more_screen.dart`.
 *
 * Neun Themenkarten untereinander: Profil, Daten & Backup, Health Connect,
 * Erinnerungen, Kartenstil, Offline-Karten, Karten für Offline-Routing,
 * Sync (Selfhost) und Über. Jede
 * Karte ist eine eigene, in sich geschlossene Datei in diesem Paket — siehe
 * deren KDoc fuer Details und (falls vorhanden) bewusste Abweichungen vom
 * Dart-Original.
 *
 * Die **Reihenfolge** ist auf den Erstnutzer hin sortiert (Begruendung im
 * Rumpf), nicht mehr die des Dart-Originals.
 *
 * Darueber liegt — nur wenn es etwas zu melden gibt — die Update-Karte
 * (`UpdateCard.kt`).
 *
 * ## Sprungziele von aussen
 * Neun Karten sind zu viele, um von einem Leerzustand aus nur „irgendwohin in
 * den Mehr-Tab" zu schicken. [AppViewModel.pendingMoreSection] nennt deshalb
 * die gemeinte Karte; dieser Screen scrollt beim Eintreffen dorthin und hebt
 * sie kurz hervor (siehe [HIGHLIGHT_MS]). Die Zuordnung Wert → Position steht
 * ausschliesslich in [moreSectionOrder].
 *
 * ## Bewusste Abweichungen vom Original
 *  * **Kartenstil-Auswahl** (neu, kein Dart-Vorbild): Der native Kartenstil-
 *    Katalog lebt im geteilten [AppViewModel] (`ui/MapStyles.kt`) und wird
 *    auch im Karten-Screen angeboten — die Auswahl gehoert deshalb sinnvoll
 *    auch hier ins „Mehr"-Tab.
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(appViewModel: AppViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    val updateVersion by appViewModel.updateAvailable.collectAsStateWithLifecycle()
    val requestedSection by appViewModel.pendingMoreSection.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    // Welche Karte gerade hervorgehoben ist; `null` = keine. Eigener Zustand
    // und nicht `requestedSection`, weil die Bitte sofort quittiert wird — die
    // Hervorhebung soll den Tab-Wechsel aber ueberdauern.
    var highlighted by remember { mutableStateOf<MoreSection?>(null) }

    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        appViewModel.refreshHealthConnection()
    }

    // Die Update-Karte steht ueber allem und verschiebt damit jeden Index um
    // eins — deshalb wird sie hier mitgezaehlt statt in [moreSectionOrder].
    val updateCardShown = updateVersion != null
    LaunchedEffect(requestedSection, updateCardShown) {
        val wanted = requestedSection ?: return@LaunchedEffect
        val index = moreSectionOrder.indexOf(wanted)
        if (index >= 0) {
            listState.animateScrollToItem(index + if (updateCardShown) 1 else 0)
        }
        appViewModel.consumeMoreSectionRequest()
        highlighted = wanted
    }

    // Eigener Effekt, damit das Quittieren oben die Wartezeit nicht abbricht:
    // `consumeMoreSectionRequest()` setzt `requestedSection` auf `null` und
    // startet den Effekt darueber sofort neu — ein `delay` in dessen Rumpf
    // wuerde dabei mit abgebrochen und die Hervorhebung bliebe ewig stehen.
    LaunchedEffect(highlighted) {
        if (highlighted == null) return@LaunchedEffect
        delay(HIGHLIGHT_MS)
        highlighted = null
    }

    Scaffold(
        // Siehe RidesScreen.kt: Die aeussere Huelle (TrailscapeApp) hat die
        // System-Insets bereits aufgeloest — hier duerfen sie nicht nochmal
        // aufschlagen.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                        // One-UI-Listentitel: gross und fett statt der
                        // kleinen Material-Leiste.
                        Text("Mehr", style = MaterialTheme.typography.headlineMedium)
                    },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                contentPadding = PaddingValues(ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                // Reihenfolge fuer den Erstnutzer: erst das Profil (ohne Alter
                // und Gewicht rechnet nichts richtig), dann die beiden Wege,
                // auf denen Daten hereinkommen — der Import und Health
                // Connect. Danach erst die Einstellungen, die man auch spaeter
                // noch entdecken kann. Vorher stand „Kartenstil" auf Platz
                // zwei und „Daten & Backup" hinter Health Connect, obwohl die
                // Leerzustaende aller Tabs auf den Import verweisen.
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
                item {
                    ProfileCard(appViewModel, modifier = highlight(highlighted, MoreSection.PROFILE))
                }
                item {
                    BackupCard(appViewModel, modifier = highlight(highlighted, MoreSection.BACKUP))
                }
                item {
                    HealthCard(appViewModel, modifier = highlight(highlighted, MoreSection.HEALTH))
                }
                // Vor den beiden Darstellungs-Einstellungen: Die Erinnerungen
                // entscheiden, ob die App von sich aus etwas sagt — das ist
                // eine Verhaltensfrage und wiegt schwerer als die Wahl des
                // Kartenhintergrunds. Sie stehen hinter Health Connect, weil
                // die Tageseinheit ohne Plan und ohne Daten nichts zu melden
                // haette.
                item { ReminderCard(appViewModel) }
                item { MapStyleCard(appViewModel) }
                item { OfflineMapsCard(onMessage = appViewModel::showMessage) }
                // Direkt hinter den Offline-Karten: Beide speichern „Karten",
                // meinen aber Verschiedenes (Bild gegen Wegedaten). Nebeneinander
                // ist der Unterschied eine Frage von zwei Zeilen Text; getrennt
                // waere er ein Missverstaendnis (siehe OfflineRoutingCard.kt).
                item { OfflineRoutingCard(appViewModel) }
                item { SyncCard(appViewModel) }
                item { AboutCard(appViewModel) }
            }
        }
    }
}

/**
 * Die Karten dieses Screens in Anzeigereihenfolge, soweit sie Sprungziel sein
 * koennen — der Index in dieser Liste ist der Index in der `LazyColumn`
 * (ohne die vorgeschaltete Update-Karte, siehe Aufrufstelle).
 *
 * Wird die Reihenfolge im Rumpf geaendert, muss sie hier mitgehen; deshalb
 * stehen beide in derselben Datei und unmittelbar untereinander.
 */
private val moreSectionOrder: List<MoreSection> = listOf(
    MoreSection.PROFILE,
    MoreSection.BACKUP,
    MoreSection.HEALTH,
)

/** Wie lange die angesprungene Karte hervorgehoben bleibt. */
private const val HIGHLIGHT_MS: Long = 1_800L

/**
 * Ein weicher Rahmen um die angesprungene Karte.
 *
 * Ohne ihn endet der Sprung bei einer Liste gleich aussehender Karten — der
 * Nutzer sieht, dass sich etwas bewegt hat, aber nicht, worauf er schauen soll.
 * Bewusst nur ein Rahmen und keine Farbflaeche: Die Karte soll gefunden werden,
 * nicht wie ein Fehler aussehen. Der Rahmen nimmt den Kartenradius aus dem
 * Theme (`MaterialTheme.shapes.medium`), damit er der Rundung der Karte folgt.
 */
@Composable
private fun highlight(highlighted: MoreSection?, section: MoreSection): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (highlighted == section) 1f else 0f,
        label = "Hervorhebung",
    )
    if (alpha <= 0f) return Modifier
    return Modifier.border(
        width = 2.dp,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        shape = MaterialTheme.shapes.medium,
    )
}
