package de.trailscape.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Das Zahnrad, hinter dem der Mehr-Bereich liegt.
 *
 * Seit der Navigationsfuehrung „Eine Leiste" ist „Mehr" kein Tab mehr, sondern
 * ein gepushtes Ziel (siehe `ui/TrailscapeApp.kt`). Der Einstieg dorthin steht
 * rechts in der Kopfzeile der drei Listen-Tabs — Heute, Touren, Training —,
 * also genau dort, wo One UI und Samsungs eigene Apps ihn erwarten lassen.
 *
 * Als eigener Baustein und nicht dreimal von Hand: Es geht um dieselbe
 * Handlung mit derselben Ansage fuer die Bildschirmlesehilfe. Drei Kopien
 * waeren drei Gelegenheiten, sie auseinanderlaufen zu lassen — und genau
 * dieser Text ist das Einzige, was ein blinder Nutzer von diesem Knopf hoert.
 *
 * Bewusst die duenne (`Outlined`) Variante wie die Symbole der
 * Navigationskapsel: One UI reserviert gefuellte Symbole dem ausgewaehlten
 * Zustand, und ein Kopfzeilen-Knopf ist nie ausgewaehlt.
 *
 * Der Karten-Screen traegt dieses Zahnrad **nicht**: Er hat gar keine
 * Kopfzeile (die Karte soll bis unter die Statusleiste laufen), und sein
 * Knopfstapel am unteren Rand gehoert den Handgriffen der Fahrt — Aufzeichnen,
 * Position, Ebenen. Ein Einstellungs-Knopf dazwischen waere der einzige dort,
 * der nichts mit der Karte zu tun hat. Wer von der Karte aus in den
 * Mehr-Bereich will, wechselt einen Tab weiter; alle drei Nachbarn tragen das
 * Zahnrad.
 */
@Composable
fun SettingsAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = "Mehr und Einstellungen",
        )
    }
}
