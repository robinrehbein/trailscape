package de.trailscape.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.LocalNavigationBarColors

/**
 * # Die schwebende One-UI-Navigationsleiste
 *
 * Das auffaelligste Erkennungsmerkmal einer aktuellen Samsung-App (One UI
 * 8.5/9, z. B. Samsung Health): Die Hauptnavigation klebt nicht als
 * randlose Leiste am unteren Bildschirmrand, sondern schwebt als **Kapsel**
 * ueber dem Inhalt — voll gerundet, mit eigener Flaeche, mit Luft zum Rand
 * und zur Gestenleiste. Das aktive Ziel liegt in einer neutralen Pille;
 * Symbol und Beschriftung werden dabei nicht gruen, sondern **deckend** —
 * One UI markiert ueber Kontrast, nicht ueber Farbe (die Toene stehen in
 * `theme/NavigationColors.kt`).
 *
 * Warum nicht Material-3-`NavigationBar` mit anderen Farben: Deren Aufbau ist
 * ein anderer — volle Bildschirmbreite, eigene Tonal-Elevation, ein Indikator,
 * der nur das Symbol umschliesst und die Beschriftung darunter stehen laesst.
 * Alles drei laesst sich nicht per Parameter abstellen. Die Kapsel ist deshalb
 * aus Grundbausteinen gebaut — und bleibt trotzdem eine gewoehnliche
 * Navigationsleiste: `selectable` mit [Role.Tab] gibt TalkBack dieselbe
 * Auskunft („Reiter, ausgewaehlt, 1 von 5") wie das Material-Original.
 *
 * ## Benutzung
 * ```kotlin
 * OneUiNavigationBar {
 *     OneUiNavigationBarItem(selected = …, onClick = …, icon = …, label = "Heute")
 * }
 * ```
 * Die Leiste bringt ihren Abstand zur Gestenleiste selbst mit
 * ([WindowInsets.navigationBars]). Sie belegt **keinen** Platz im Layout,
 * sondern wird ueber den Inhalt gelegt (`Box` + `Alignment.BottomCenter`, siehe
 * `TrailscapeApp`); die noetige Bodenfreiheit reichen die Bildschirme sich
 * ueber [LocalFloatingNavigationBarSpace] an — das ist der Preis fuer den
 * One-UI-Effekt, dass Inhalt unter der Kapsel hindurchscrollt.
 */
@Composable
fun OneUiNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalNavigationBarColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = NavigationBarSideMargin, vertical = NavigationBarBottomMargin),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            // Deckend. One UI laesst den Inhalt hinter der Kapsel durchaus
            // durchschimmern — aber immer mit einer Weichzeichnung dahinter,
            // die Compose ohne Fremdbibliothek nicht hergibt. Ohne sie stand
            // hier bei 94 % Deckkraft die naechste Karte lesbar *in* der
            // Leiste; das sieht nach Fehler aus, nicht nach Glas.
            color = colors.container,
            // Der Schatten traegt den Schwebe-Eindruck im Hellmodus allein:
            // Dort ist die Kapsel weiss wie die Karten, die unter ihr
            // wegscrollen — ohne ihn verschwaemme ihre Kante genau in dem
            // Moment, in dem eine Karte darunter steht. Im Dunkelmodus ist er
            // auf fast schwarzem Grund unsichtbar; dort trennt die Helligkeit.
            shadowElevation = 12.dp,
            // Volle Pille aus dem small-Slot des Themes — dieselbe Rundung wie
            // Knoepfe und Chips, damit die Leiste zur uebrigen App gehoert.
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier
                    // Auf dem Telefon greift die Grenze nie; auf dem Tablet
                    // bleibt die Kapsel mittig und in Daumenbreite, statt sich
                    // ueber den halben Meter Bildschirm zu ziehen.
                    .widthIn(max = NavigationBarMaxWidth)
                    .height(NavigationBarHeight)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

/**
 * Ein Ziel der [OneUiNavigationBar]: Symbol ueber Beschriftung, im
 * ausgewaehlten Zustand in einer Pille.
 *
 * Die Pille wechselt weich (Farbe und ein Hauch Skalierung, beides mit der
 * gleichen Feder) — One UI reagiert auf jede Beruehrung sichtbar, ein harter
 * Umschlag wirkte dort fremd. Fuer die Bildschirmlesehilfe ist der Wechsel
 * belanglos: [selectable] meldet den Zustand unabhaengig von der Animation.
 */
@Composable
fun RowScope.OneUiNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNavigationBarColors.current
    val interactionSource = remember { MutableInteractionSource() }

    val indicatorColor by animateColorAsState(
        targetValue = if (selected) colors.indicator else Color.Transparent,
        animationSpec = spring(),
        label = "navIndicator",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.selectedContent else colors.unselectedContent,
        animationSpec = spring(),
        label = "navContent",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = spring(),
        label = "navIconScale",
    )

    Box(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(vertical = 7.dp)
            .clip(MaterialTheme.shapes.small)
            .background(indicatorColor)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = ripple(),
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(NavigationBarIconSize)
                        .scale(iconScale),
                )
                // Die Beschriftung traegt die Auskunft mit — One UI beschriftet
                // jedes Ziel, auch das nicht gewaehlte. Einzeilig mit Ellipse,
                // damit fuenf Ziele auf ein 320-dp-Geraet passen, ohne dass
                // ein Umbruch die Kapsel hoeher macht.
                Text(
                    text = label,
                    // Nur das aktive Ziel steht fett. One UI gewichtet die
                    // Beschriftung mit der Auswahl mit; alle fuenf Labels im
                    // selben halbfetten Schnitt liessen die Leiste laut und
                    // die Auswahl schwerer erkennbar wirken.
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}

/**
 * Hoehe der Kapsel. Knapper als die 80 dp der Material-`NavigationBar`: Die
 * Kapsel steht frei und braucht deshalb keinen eigenen Rand nach unten —
 * dafuer sorgt [NavigationBarBottomMargin].
 */
private val NavigationBarHeight = 62.dp

/** Abstand der Kapsel zu den Bildschirmseiten. */
private val NavigationBarSideMargin = 16.dp

/** Breitengrenze der Kapsel — sie soll auch auf dem Tablet in Daumenreichweite bleiben. */
private val NavigationBarMaxWidth = 520.dp

/** Abstand der Kapsel zur Gestenleiste bzw. zum unteren Rand. */
private val NavigationBarBottomMargin = 8.dp

/** Symbolgroesse in der Kapsel; kleiner als die 24 dp von Material. */
private val NavigationBarIconSize = 22.dp

/** Kennzahlen der Leiste fuer alle, die um sie herum layouten muessen. */
object OneUiNavigationBarDefaults {
    /**
     * Platzbedarf der Kapsel **ohne** die Gestenleiste des Systems: Hoehe plus
     * der Rand ober- und unterhalb. Wer die Leiste ueberlagert (siehe
     * [LocalFloatingNavigationBarSpace]), rechnet mit diesem Wert plus dem
     * unteren System-Inset.
     */
    val OverlaySpace: Dp = NavigationBarHeight + NavigationBarBottomMargin * 2
}
