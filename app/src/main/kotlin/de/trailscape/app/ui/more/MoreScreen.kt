package de.trailscape.app.ui.more

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.ScreenPadding

/**
 * „Mehr"-Tab — Port von `lib/screens/more_screen.dart`.
 *
 * Sieben Themenkarten untereinander: Profil, Daten & Backup, Samsung Health,
 * Kartenstil, Offline-Karten, Sync (Selfhost) und Über. Jede Karte ist eine
 * eigene, in sich geschlossene Datei in diesem Paket — siehe deren KDoc fuer
 * Details und (falls vorhanden) bewusste Abweichungen vom Dart-Original.
 *
 * Die **Reihenfolge** ist auf den Erstnutzer hin sortiert (Begruendung im
 * Rumpf), nicht mehr die des Dart-Originals.
 *
 * Darueber liegt — nur wenn es etwas zu melden gibt — die Update-Karte
 * (`UpdateCard.kt`).
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

    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        appViewModel.refreshHealthConnection()
    }

    Scaffold(
        // Siehe RidesScreen.kt: Die aeussere Huelle (TrailscapeApp) hat die
        // System-Insets bereits aufgeloest — hier duerfen sie nicht nochmal
        // aufschlagen.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Mehr") },
                windowInsets = WindowInsets(0, 0, 0, 0),
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
                item { ProfileCard(appViewModel) }
                item { BackupCard(appViewModel) }
                item { HealthCard(appViewModel) }
                item { MapStyleCard(appViewModel) }
                item { OfflineMapsCard(onMessage = appViewModel::showMessage) }
                item { SyncCard(appViewModel) }
                item { AboutCard(appViewModel) }
            }
        }
    }
}
