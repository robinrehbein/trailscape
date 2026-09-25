package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Eine Aktion aus der Aktionsreihe unter einer Tour: Symbol ueber Beschriftung.
 *
 * ## Warum sichtbar statt ⋮
 * Teilen, Umbenennen, Auf der Karte zeigen und Loeschen lagen im Tourdetail
 * hinter ⋮, auf dem Karten-Tourblatt waren Teilen und Loeschen nackte Symbole.
 * Beides widerspricht dem Leitsatz „keine versteckten Funktionen": Wer eine
 * Tour teilen will, soll das Wort „Teilen" *sehen*. Die Form — kleine
 * Flaechen, Symbol oben, Wort darunter — ist die Aktionsleiste, die One UI
 * etwa in der Galerie unter einem Bild zeigt: ruhig, gleich breit, ohne dass
 * eine der Nebenaktionen lauter waere als die andere.
 *
 * ## Knopf-Hierarchie
 * Die Hauptaktion („Losfahren", „Nochmal fahren") bleibt der gefuellte Knopf
 * in der Akzentfarbe. Diese Kacheln sind die Nebenaktionen und tragen deshalb
 * die neutrale Flaeche von [NeutralButton] (`surfaceContainerHighest`) —
 * dieselbe Familie, nur mit Symbol ueber dem Text. [destructive] faerbt wie
 * dort die *Flaeche* in der Fehler-Tonung, damit „Löschen" auch im
 * Augenwinkel als das erkennbar ist, was es ist.
 *
 * ## [onCard]
 * Auf dem Bildschirmgrund (`surface`) hebt sich `surfaceContainerHighest` als
 * helle Kachel ab — genau wie eine Karte. Liegen die Kacheln aber selbst *auf*
 * einer Karte (das angedockte Tourblatt ist eine), waere das Weiss auf Weiss
 * und die Kachel nur noch ein schwebendes Symbol. Dort nehmen sie deshalb
 * `surfaceContainerHigh`, die getoente Flaeche eine Stufe darunter, die sich
 * hell wie dunkel von der Karte absetzt.
 *
 * Die Beschriftung darf zweizeilig werden („Karte zeigen" bei grosser
 * Schrift); mehr nicht, sonst wachsen die Kacheln ueber die Hauptaktion.
 */
@Composable
fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onCard: Boolean = false,
) {
    Button(
        onClick = onClick,
        // Mindestens 64 dp: Symbol (24) plus eine Zeile `labelMedium` plus
        // Innenabstand — und damit deutlich ueber der 48-dp-Beruehrflaeche.
        modifier = modifier.heightIn(min = 64.dp),
        // Kleinere Rundung als die Pillen-Knoepfe: Eine hohe Pille wirkt wie
        // ein Oval, die Galerie-Leiste von One UI nimmt abgerundete Rechtecke.
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = if (onCard) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Das Symbol ist Schmuck, die Beschriftung sagt schon alles —
            // sonst hoerte die Bildschirmlesehilfe jede Aktion doppelt.
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Eine Aktion fuer [ActionTileRow]: Beschriftung, Symbol, Handlung. */
class TileAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Mehrere [ActionTile] gleich breit und gleich hoch nebeneinander.
 *
 * Gleich hoch ueber `IntrinsicSize.Min`: Bricht eine Beschriftung auf zwei
 * Zeilen um, wachsen die Nachbarn mit, statt als Treppe dazustehen.
 *
 * Ab grosser Systemschrift (ab 130 %) und mehr als zwei Aktionen stehen je
 * zwei in einer Reihe: Vier Kacheln auf 360 dp sind je rund 76 dp breit, und
 * ein Wort wie „Umbenennen" passt dann nicht mehr in eine Zeile — Compose
 * wuerde es mitten im Wort trennen. Zwei Spalten sind lesbarer als vier
 * zerhackte.
 *
 * @param onCard die Kacheln liegen auf einer Karte statt auf dem Grund —
 *   siehe [ActionTile].
 */
@Composable
fun ActionTileRow(
    actions: List<TileAction>,
    modifier: Modifier = Modifier,
    onCard: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    val perRow = if (actions.size > 2 && fontScale >= 1.3f) 2 else actions.size
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(perRow.coerceAtLeast(1)).forEach { chunk ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chunk.forEach { action ->
                    ActionTile(
                        label = action.label,
                        icon = action.icon,
                        onClick = action.onClick,
                        destructive = action.destructive,
                        onCard = onCard,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}
