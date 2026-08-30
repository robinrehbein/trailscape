package de.trailscape.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.LocalNavigationBarColors
import de.trailscape.app.ui.theme.OneUiMotion

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
            // One UI 9 setzt schwebende Flaechen mit **geschichteter** Tiefe
            // ab: ein schmales Randlicht an der Kante und ein leichterer
            // Schatten darunter. Vorher trug ein 12-dp-Schatten die Schwebe
            // allein und wirkte dadurch schwerer, als Samsungs Kapsel es tut.
            border = BorderStroke(1.dp, colors.rim),
            shadowElevation = 8.dp,
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
                    // `heightIn` statt `height`: Bei vergroesserter Schrift
                    // waechst die Kapsel mit, statt die Beschriftung
                    // abzuschneiden. Mit der frueheren festen Hoehe von 62 dp
                    // war die Zeile schon ab rund 144 % Schriftgroesse unten
                    // angeschnitten — nicht erst bei den 200 %, die der
                    // Leitfaden verlangt.
                    .heightIn(min = NavigationBarHeight)
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
 * Die Pille wechselt weich (Farbe und ein Hauch Skalierung, beides mit
 * derselben Kurve aus [OneUiMotion]) und der Wechsel bekommt ein feines
 * haptisches Echo — One UI antwortet auf jede Beruehrung sichtbar *und*
 * fuehlbar, ein stummer harter Umschlag wirkte dort fremd. Fuer die
 * Bildschirmlesehilfe ist beides belanglos: [selectable] meldet den Zustand
 * unabhaengig von Animation und Haptik.
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
    val haptics = LocalHapticFeedback.current

    // Feste Dauer und die One-UI-Kurve statt einer parameterlosen Feder: Eine
    // Feder hat konstruktionsbedingt keine begrenzte Dauer, der Leitfaden
    // verlangt aber 100 bis 500 ms (siehe `theme/Motion.kt`).
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) colors.indicator else Color.Transparent,
        animationSpec = OneUiMotion.short(),
        label = "navIndicator",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.selectedContent else colors.unselectedContent,
        animationSpec = OneUiMotion.short(),
        label = "navContent",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = OneUiMotion.short(),
        label = "navIconScale",
    )

    Box(
        modifier = modifier
            .weight(1f)
            // Kein `fillMaxHeight` mehr: Die Kapsel darf jetzt mit der Schrift
            // wachsen (siehe `heightIn` oben), und eine Fuellung auf volle
            // Hoehe haette in einer nur nach unten offenen Zeile keinen
            // definierten Bezug mehr.
            .padding(vertical = 6.dp)
            .clip(MaterialTheme.shapes.small)
            .background(indicatorColor)
            .selectable(
                selected = selected,
                onClick = {
                    // One UI gibt jeder Beruehrung ein feines, klickendes
                    // Echo — hier war bisher gar keins. Bewusst nur beim
                    // *Wechsel*: Wer denselben Reiter noch einmal antippt,
                    // loest keinen Wechsel aus und soll auch nichts spueren.
                    if (!selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    onClick()
                },
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = ripple(),
            )
            // Innenabstand der Pille: Sie umschliesst Symbol *und*
            // Beschriftung, so wie in den Samsung-Apps.
            .padding(vertical = 6.dp, horizontal = 4.dp),
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
 * Mindesthoehe der Kapsel. Knapper als die 80 dp der Material-`NavigationBar`
 * und knapper als die frueheren 62 dp: One UI 9 zieht die schwebende Leiste
 * gegenueber 8.5 kompakter und gibt ihr dafuer mehr Luft zum Rand.
 *
 * **Mindest**hoehe, nicht feste Hoehe — bei vergroesserter Schrift waechst
 * die Kapsel mit, statt die Beschriftung abzuschneiden.
 */
private val NavigationBarHeight = 56.dp

/**
 * Abstand der Kapsel zu den Bildschirmseiten. Dieselben 24 dp wie ueberall
 * sonst (Kruemmung, Reject-Zone) — und zugleich die Richtung, in die One UI 9
 * die Kapsel zieht: kompakter, mit groesserem Abstand zum Rand.
 */
private val NavigationBarSideMargin = 24.dp

/** Breitengrenze der Kapsel — sie soll auch auf dem Tablet in Daumenreichweite bleiben. */
private val NavigationBarMaxWidth = 520.dp

/** Abstand der Kapsel zur Gestenleiste bzw. zum unteren Rand. */
private val NavigationBarBottomMargin = 12.dp

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

    /**
     * Der seitliche Rand, den die Kapsel sich selbst gibt.
     *
     * Oeffentlich, seit **neben** der Kapsel noch etwas steht: Der schwebende
     * Aufnahme-Knopf ([RecCapsuleButton], eingesetzt in `ui/TrailscapeApp.kt`)
     * sitzt als eigenstaendiger Kreis rechts daneben und muss zum
     * Bildschirmrand denselben Abstand halten wie die Kapsel zu ihrem —
     * sonst stuenden die beiden Nachbarn sichtbar auf verschiedenen Linien.
     * Ein zweiter, von Hand gepflegter 24-dp-Wert in der Huelle waere genau
     * die Art Kopie, die beim naechsten Feinschliff auseinanderlaeuft.
     */
    val SideMargin: Dp = NavigationBarSideMargin
}
