package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * # Die grosse One-UI-Kopfzeile
 *
 * Samsung-Apps beginnen mit einem **grossen Titel**, der beim Scrollen in eine
 * schmale Leiste zusammenfaellt (Telefon, Kontakte, Einstellungen, Uhr). Genau
 * das ist die `LargeTopAppBar` von Material 3 — sie braucht nur die
 * One-UI-Kleidung: keine eigene Flaeche (der Bildschirmgrund traegt), auch
 * nicht im gescrollten Zustand, wo Material sonst eine getoente Leiste
 * einblendet.
 *
 * Vorher stand in diesen drei Tabs ein *statischer* `headlineMedium`-Titel in
 * einer normalen `TopAppBar`: gross wie bei One UI, aber unbeweglich — er nahm
 * auch beim Scrollen seine volle Hoehe ein, und genau daran erkennt man eine
 * nachgebaute Kopfzeile.
 *
 * ## Benutzung
 * ```kotlin
 * val scrollBehavior = oneUiTopAppBarScrollBehavior()
 * Scaffold(
 *     modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
 *     topBar = { OneUiLargeTopAppBar("Touren", scrollBehavior) },
 * ) { … }
 * ```
 * Ohne den `nestedScroll`-Modifier am `Scaffold` bewegt sich nichts — die
 * Leiste erfaehrt sonst nie, dass die Liste gescrollt wurde.
 *
 * `windowInsets` ist bewusst leer: Die Huelle (`ui/TrailscapeApp.kt`) hat die
 * Statusleiste bereits aufgeloest, ein zweites Mal waere es ein doppelter Rand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneUiLargeTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    LargeTopAppBar(
        title = {
            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

/**
 * Das Scrollverhalten der [OneUiLargeTopAppBar]: Der grosse Titel faellt beim
 * Scrollen ganz zusammen und kommt erst zurueck, wenn die Liste wieder oben
 * steht (`exitUntilCollapsed`) — dasselbe Verhalten wie in den Samsung-Apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun oneUiTopAppBarScrollBehavior(): TopAppBarScrollBehavior =
    TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
