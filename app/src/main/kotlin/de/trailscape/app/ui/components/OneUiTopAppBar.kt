package de.trailscape.app.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.OneUiMotion

/**
 * # Die grosse One-UI-Kopfzeile
 *
 * Samsung-Apps beginnen mit einem **grossen, zentrierten Titel**, der beim
 * Scrollen in eine schmale Leiste zusammenfaellt (Telefon, Kontakte,
 * Einstellungen, Uhr).
 *
 * ## Die Zahl, um die es geht
 *
 * Samsungs Designleitfaden gibt der ausgeklappten Kopfzeile **39,67 % der
 * Bildschirmhoehe** am Telefon (18,78 % am Tablet) und stellt den Titel
 * **zentriert** an deren unteren Rand. Das ist kein Schoenheitsmass, sondern
 * die Umsetzung des One-UI-Grundprinzips: oben eine ruhige Ansichtszone, unten
 * die Bedienzone in Daumenreichweite.
 *
 * Hier stand vorher `expandedHeight = 96.dp` mit linksbuendigem Titel,
 * begruendet mit „in den Samsung-Apps steht derselbe Titel bei rund 40 dp
 * unter der Statusleiste". Das war an einer *eingeklappten* Leiste
 * abgelesen — ausgeklappt nimmt sie in der Telefon-App rund ein Drittel des
 * Bildschirms ein. Auf einem 800-dp-Geraet fehlten damit ueber 200 dp.
 *
 * ## Wann es diese Leiste nicht gibt
 *
 * Der Leitfaden nimmt die ausklappbare Kopfzeile ausdruecklich zurueck, wenn
 * die Bildschirmhoehe **580 dp oder weniger** betraegt — auf einem Telefon im
 * Querformat also immer. Ein Drittel von 400 dp waere kein ruhiger Kopfbereich
 * mehr, sondern eine Wand. Dort steht deshalb die schlichte, feste Leiste;
 * [oneUiTopAppBarScrollBehavior] liefert dazu passend ein Verhalten, das
 * nichts bewegt.
 *
 * ## Benutzung
 * ```kotlin
 * val scrollBehavior = oneUiTopAppBarScrollBehavior()
 * Scaffold(
 *     modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
 *     topBar = { OneUiLargeTopAppBar("Training", scrollBehavior) },
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
    val transparent = TopAppBarDefaults.topAppBarColors(
        // Keine eigene Flaeche — der Bildschirmgrund traegt, auch im
        // gescrollten Zustand, wo Material sonst eine getoente Leiste
        // einblendet.
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent,
    )

    if (!oneUiExpandableTopAppBarFits()) {
        TopAppBar(
            title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = transparent,
            modifier = modifier,
        )
        return
    }

    LargeTopAppBar(
        title = {
            // Der eigentliche Punkt dieser Datei: ausgeklappt zentriert wie
            // bei Samsung, eingeklappt linksbuendig. Material 3 kann das nicht
            // von sich aus — der `titleHorizontalAlignment`-Parameter existiert
            // erst in der Expressive-Reihe und ist in 1.4.0 noch `internal`.
            //
            // `collapsedFraction` ist der Ausklappgrad zwischen 0 (ganz offen)
            // und 1 (ganz zu). Der Umschlag liegt bei der Haelfte, wo Material
            // ohnehin gerade die eine Titelzeile aus- und die andere einblendet
            // — der Wechsel faellt damit in den unsichtbaren Moment.
            val expanded = scrollBehavior.state.collapsedFraction < 0.5f
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (expanded) TextAlign.Center else TextAlign.Start,
                modifier = if (expanded) Modifier.fillMaxWidth() else Modifier,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
        expandedHeight = oneUiExpandedTopAppBarHeight(),
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = transparent,
        scrollBehavior = scrollBehavior,
        modifier = modifier,
    )
}

/**
 * Die ausgeklappte Hoehe nach Samsungs Vorgabe: 39,67 % der Bildschirmhoehe.
 *
 * Bewusst gerechnet statt fest verdrahtet — dieselbe App laeuft auf einem
 * kompakten Telefon und auf einem Tablet, und ein fester dp-Wert waere auf
 * genau einem davon richtig.
 */
@Composable
private fun oneUiExpandedTopAppBarHeight(): Dp {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    return (screenHeight * EXPANDED_SHARE_OF_SCREEN).dp
}

/**
 * Ob die ausklappbare Kopfzeile auf diesen Bildschirm gehoert.
 *
 * Der Leitfaden zieht die Grenze bei 580 dp Bildschirmhoehe. Darunter — also
 * auf jedem Telefon im Querformat — bleibt die feste Leiste.
 */
@Composable
private fun oneUiExpandableTopAppBarFits(): Boolean =
    LocalConfiguration.current.screenHeightDp > MIN_SCREEN_HEIGHT_DP

/** Samsungs Vorgabe fuer die ausgeklappte Kopfzeile am Telefon. */
private const val EXPANDED_SHARE_OF_SCREEN = 0.3967f

/** Unterhalb dieser Bildschirmhoehe gibt es keine ausklappbare Kopfzeile. */
private const val MIN_SCREEN_HEIGHT_DP = 580

/**
 * Das Scrollverhalten der [OneUiLargeTopAppBar].
 *
 * `exitUntilCollapsed`: Der grosse Titel faellt beim Scrollen ganz zusammen und
 * kommt erst zurueck, wenn die Liste wieder oben steht.
 *
 * Der `snapAnimationSpec` ist der Grund, warum hier ueberhaupt Parameter
 * stehen. One UI kennt fuer diese Leiste **nur zwei Zustaende** — ausgeklappt
 * oder eingeklappt, nichts dazwischen: Laesst der Finger in der Mitte los,
 * schnappt sie ueber eine Schwelle in einen der beiden. Material schnappt zwar
 * auch, aber mit einer Feder und damit ohne feste Dauer. Hier steht deshalb
 * die One-UI-Kurve mit einer Dauer im vorgeschriebenen Fenster von 100 bis
 * 500 ms (siehe `theme/Motion.kt`).
 *
 * @param initiallyCollapsed Ob die Leiste eingeklappt startet. Der Leitfaden
 *   unterscheidet nach Ebene: Der **erste Hauptbildschirm** empfaengt den
 *   Nutzer ausgeklappt (`false`, die Vorgabe), **ab der zweiten Ebene** —
 *   Detailansicht, Suche, Mehrfachauswahl — steht die Leiste eingeklappt und
 *   laesst sich durch Ziehen oeffnen (`true`). Der Grund ist einleuchtend: Wer
 *   eine Tour geoeffnet hat, will die Tour sehen, nicht noch einmal deren
 *   Namen in Grossschrift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun oneUiTopAppBarScrollBehavior(
    initiallyCollapsed: Boolean = false,
): TopAppBarScrollBehavior {
    // Der Versatz, um den die Leiste nach oben geschoben ist: 0 heisst offen,
    // die volle Differenz zwischen ausgeklappter und eingeklappter Hoehe
    // heisst zu. Als Pixelwert, weil der Zustand von Material in Pixeln
    // rechnet.
    val collapsedOffset = if (initiallyCollapsed) {
        val expanded = oneUiExpandedTopAppBarHeight()
        val collapsed = TopAppBarDefaults.LargeAppBarCollapsedHeight
        with(LocalDensity.current) { -(expanded - collapsed).toPx() }
    } else {
        0f
    }

    return TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(initialHeightOffset = collapsedOffset),
        snapAnimationSpec = tween(
            durationMillis = OneUiMotion.MediumMillis,
            easing = OneUiMotion.Easing,
        ),
    )
}
