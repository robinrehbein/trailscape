package de.trailscape.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R

/**
 * Das Zahnrad, hinter dem die Einstellungen liegen.
 *
 * Die Einstellungen sind kein Tab, sondern ein gepushtes Ziel (siehe
 * `ui/TrailscapeApp.kt`). Der Einstieg dorthin steht auf **allen vier Tabs**
 * und oeffnet immer dieselbe Seite: rechts in der Kopfzeile von Heute, Indoor
 * und Verlauf — dort, wo One UI und Samsungs eigene Apps ihn erwarten lassen —
 * und auf der Karte neben dem Suchfeld im Blatt „Wohin?" (Seitenkarte,
 * `docs/ux/umbau.md`).
 *
 * Als eigener Baustein und nicht viermal von Hand: Es geht um dieselbe
 * Handlung mit derselben Ansage fuer die Bildschirmlesehilfe. Drei Kopien
 * waeren drei Gelegenheiten, sie auseinanderlaufen zu lassen — und genau
 * dieser Text ist das Einzige, was ein blinder Nutzer von diesem Knopf hoert.
 *
 * Bewusst die duenne (`Outlined`) Variante wie die Symbole der
 * Navigationskapsel: One UI reserviert gefuellte Symbole dem ausgewaehlten
 * Zustand, und ein Kopfzeilen-Knopf ist nie ausgewaehlt.
 *
 * Der Karten-Screen hat keine Kopfzeile (die Karte laeuft bis unter die
 * Statusleiste), und sein Knopfstapel am unteren Rand gehoert den Handgriffen
 * der Fahrt. Sein Zahnrad steht deshalb im Blatt „Wohin?" — frueher fehlte es
 * dort ganz, obwohl eine Meldung der Karte auf die Einstellungen verweist.
 */
@Composable
fun SettingsAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = stringResource(R.string.common_settings_cd),
        )
    }
}
