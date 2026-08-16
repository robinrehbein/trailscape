package de.trailscape.app.ui.more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding

/**
 * Gemeinsame Bausteine des Mehr-Tabs: eine Gruppen-Grammatik statt neun
 * gleichwertiger Vollkarten (siehe `MoreScreen.kt`-KDoc fuer die
 * Begruendung).
 *
 * Eine [MoreGroup] ist ein versales Label ueber genau einer [MoreGroupCard];
 * darin liegen flache [MoreRow]s, die ihren Inhalt beim Antippen inline
 * aufklappen. Das entspricht der One-UI-Einstellungsliste: Gruppen sortieren
 * grob, Zeilen zeigen erst auf Nachfrage Tiefe.
 */

/**
 * Versales Gruppenlabel ueber einer [MoreGroupCard] — One-UI-Einstellungen
 * kennzeichnen Gruppen so, nicht mit einer eigenen Karten-Ueberschrift pro
 * Karte. Der linke Einzug entspricht [CardPadding], damit das Label ueber dem
 * Zeilentext der Karte darunter steht statt darueber hinauszuragen.
 */
@Composable
fun MoreGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = CardPadding, bottom = 4.dp),
    )
}

/**
 * Die eine Karte einer Gruppe: keine eigene Farbe oder Rundung (erbt beides
 * aus dem Theme, wie jede andere Karte der App), aber auch **kein**
 * Innenabstand auf Kartenebene — den setzt jede [MoreRow] fuer sich, sonst
 * bekaeme die erste Zeile doppelten Abstand nach oben.
 */
@Composable
fun MoreGroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), content = content)
}

/** [MoreGroupLabel] und [MoreGroupCard] als eine Einheit — der uebliche Aufruf einer Gruppe. */
@Composable
fun MoreGroup(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        MoreGroupLabel(text = label)
        MoreGroupCard(content = content)
    }
}

/**
 * Eine flache, aufklappbare Zeile innerhalb einer [MoreGroupCard]: Titel,
 * optionale einzeilige Statuszeile und ein Auf/Zu-Pfeil. Antippen klappt
 * [content] inline auf (`AnimatedVisibility`) — kein eigener Screen, keine
 * Karte-in-Karte.
 *
 * Der Aufklappzustand ist [rememberSaveable] und bewusst **je Zeile**
 * unabhaengig: Die neun vormaligen Vollkarten waren gleichrangig, keine ist
 * die "Hauptzeile" einer Gruppe — ein Akkordeon, das beim Oeffnen einer Zeile
 * die anderen zuklappt, wuerde eine Rangfolge behaupten, die es nicht gibt.
 * Eine Drehung des Geraets oder ein Prozesswechsel darf eine offene Zeile
 * ausserdem nicht zuklappen.
 *
 * @param expandOnArrival Von aussen gesetztes Aufklapp-Signal fuer
 *   Sprungziele (siehe `MoreScreen.kt`): Wechselt der Wert nach `true`,
 *   klappt die Zeile einmalig auf — das Aufklappen selbst zeigt dann, wo man
 *   gelandet ist, ein zusaetzlicher Leuchtrahmen braucht es dafuer nicht
 *   mehr. Manuelles Zuklappen danach bleibt jederzeit moeglich.
 */
@Composable
fun MoreRow(
    title: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    expandOnArrival: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expandOnArrival) {
        if (expandOnArrival) expanded = true
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = CardPadding, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Zuklappen" else "Aufklappen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, bottom = CardPadding),
                content = content,
            )
        }
    }
}
