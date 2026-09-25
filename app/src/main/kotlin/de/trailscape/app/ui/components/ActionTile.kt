package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * dieselbe Familie, nur mit Symbol ueber dem Text. [destructive] faerbt
 * anders als dort nur Symbol und Wort rot, die Flaeche bleibt neutral: Eine
 * rote *Flaeche* war im Dunkeln (`errorContainer`, tiefes Rot) neben dem
 * mintgruenen Hauptknopf das lauteste Element des Bildschirms — Loeschen
 * waere lauter gewesen als die Hauptaktion. One UI zeigt Loeschen in seiner
 * Aktionsleiste genauso: neutrale Kachel, rotes Symbol, rotes Wort. Das
 * reicht, um es im Augenwinkel zu erkennen. `error` erreicht auf beiden
 * Kachelflaechen hell wie dunkel mehr als 4,5:1.
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
 *
 * @param contentDescription was die Bildschirmlesehilfe statt [label] sagt,
 *   wenn das kurze Wort allein das Objekt nicht nennt („Tour löschen" statt
 *   „Löschen"). Es muss [label] enthalten, damit Sprachsteuerung („Tippe auf
 *   Löschen") den Knopf weiter findet.
 */
@Composable
fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onCard: Boolean = false,
    contentDescription: String? = null,
) {
    Button(
        onClick = onClick,
        // Mindestens 64 dp: Symbol (24) plus eine Zeile `labelMedium` plus
        // Innenabstand — und damit deutlich ueber der 48-dp-Beruehrflaeche.
        modifier = modifier
            .heightIn(min = 64.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        // Kleinere Rundung als die Pillen-Knoepfe: Eine hohe Pille wirkt wie
        // ein Oval, die Galerie-Leiste von One UI nimmt abgerundete Rechtecke.
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = TilePaddingH, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (onCard) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
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

/**
 * Eine Aktion fuer [ActionTileRow]: Beschriftung, Symbol, Handlung.
 *
 * @param contentDescription siehe [ActionTile].
 */
class TileAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val contentDescription: String? = null,
    val onClick: () -> Unit,
)

/** Innenabstand links und rechts in einer [ActionTile]. */
private val TilePaddingH = 4.dp

/** Abstand zwischen den Kacheln, waagerecht wie senkrecht. */
private val TileGap = 8.dp

/**
 * Mehrere [ActionTile] gleich breit und gleich hoch nebeneinander.
 *
 * Gleich hoch ueber `IntrinsicSize.Min`: Bricht eine Beschriftung auf zwei
 * Zeilen um, wachsen die Nachbarn mit, statt als Treppe dazustehen.
 *
 * ## Wann zwei Spalten
 * Passt das laengste *Wort* einer Beschriftung nicht mehr in eine Kachel,
 * stehen je zwei Kacheln in einer Reihe (siehe [actionTileColumns]).
 * Entschieden wird nach dem tatsaechlich verfuegbaren Platz, nicht nach der
 * Schriftgroesse: „Umbenennen" ist bei 100 % rund 74 dp breit, eine von vier
 * Kacheln auf einem 360-dp-Geraet bietet aber nur rund 68 dp — schon bei
 * Standardschrift wuerde Compose das Wort mitten drin trennen
 * („Umbenenne/n"). Umgekehrt reicht auf einem breiten Geraet auch bei
 * 115 % noch eine Reihe. Gemessen wird deshalb mit derselben Schrift
 * (`labelMedium`) und derselben Dichte samt Schriftskalierung, mit der die
 * Kachel die Beschriftung setzt. Zwei Spalten sind lesbarer als vier
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
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val labels = actions.map { it.label }
    // Nur das laengste Wort zaehlt: An Leerzeichen darf die Beschriftung
    // umbrechen („Karte / zeigen"), mitten im Wort nicht.
    val widestWord = remember(labels, style, density) {
        val px = labels
            .flatMap { it.split(' ') }
            .maxOfOrNull { word ->
                measurer.measure(word, style, softWrap = false, maxLines = 1).size.width
            } ?: 0
        with(density) { px.toDp() }
    }
    BoxWithConstraints(modifier = modifier) {
        val perRow = if (constraints.hasBoundedWidth) {
            actionTileColumns(actions.size, maxWidth, widestWord)
        } else {
            actions.size
        }
        Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
            actions.chunked(perRow.coerceAtLeast(1)).forEach { chunk ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(TileGap),
                ) {
                    chunk.forEach { action ->
                        ActionTile(
                            label = action.label,
                            icon = action.icon,
                            onClick = action.onClick,
                            destructive = action.destructive,
                            onCard = onCard,
                            contentDescription = action.contentDescription,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Wie viele Kacheln je Reihe: alle nebeneinander, sonst zwei, sonst eine —
 * die erste Aufteilung, bei der [widestWord] samt Innenabstand in eine
 * Kachel passt. Drei je Reihe gibt es bewusst nicht: Vier Aktionen als drei
 * plus eine saehen aus wie vergessen.
 *
 * Zwei dp Reserve fangen Rundung und Glyphenueberhang ab — lieber einmal
 * zu frueh zwei Spalten als ein abgeschnittenes „n".
 */
internal fun actionTileColumns(count: Int, available: Dp, widestWord: Dp): Int {
    if (count <= 1) return count.coerceAtLeast(1)
    val candidates = listOf(count, 2, 1).filter { it <= count }.distinct()
    return candidates.firstOrNull { columns ->
        val tile = (available - TileGap * (columns - 1)) / columns
        tile - TilePaddingH * 2 - 2.dp >= widestWord
    } ?: 1
}
