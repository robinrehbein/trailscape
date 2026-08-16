package de.trailscape.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical

/**
 * Das Ruhegesicht des einen Karten-Blatts — [MapMode.ERKUNDEN] ohne Auswahl.
 *
 * ## Warum ein Blatt statt einer Knopfreihe oben
 * Vorher lagen „Route planen", Lupe, Kartenstil und Offline-Download als
 * Pille plus drei Rundknoepfe am oberen Kartenrand. Das hatte zwei sichtbare
 * Fehler: Die Pille war niedriger als die Knoepfe daneben, und mit dem
 * laengeren aktiven Text („Planung beenden") wurde der letzte Knopf
 * zusammengequetscht — die Reihe hatte schlicht keine Platzlogik. Der
 * eigentliche Grund liegt aber tiefer: Die Karte hat seit dem Umbau EIN
 * unteres Blatt, das mit dem Modus sein Gesicht wechselt (Planung, Tour,
 * Ort, Aufzeichnung) — nur der Ruhezustand verstreute seine Werkzeuge noch
 * oben. Jetzt wohnen alle Werkzeuge im selben Blatt; oben bleibt Karte.
 *
 * ## Suchfeld zuerst
 * Die Suche ist die haeufigste Aktion und steht deshalb als volle Zeile im
 * Kopf (Google-Maps-Muster). Die Zeile SIEHT aus wie das Suchfeld des
 * Suchblatts (gleiche Flaeche, gleiche Form wie `OneUiTextField`), IST aber
 * nur ein Knopf: Das echte Feld mit Tastatur lebt im modalen [SearchSheet] —
 * zwei fokussierbare Felder waeren zwei Tastatur-Zustaende fuer eine Aufgabe.
 *
 * ## Drei gleichrangige Werkzeuge, keine Hauptaktion
 * „Route planen", Kartenstil und Offline sind Einstiege in Verschiedenes,
 * keine Stufen einer Entscheidung — deshalb drei gleiche helle Flaechen
 * (One UI: [NeutralButton]) und keine gefaerbte Pille, die eine Rangfolge
 * behauptet, die es nicht gibt.
 */
@Composable
internal fun ExploreSheet(
    onOpenSearch: () -> Unit,
    onStartPlanning: () -> Unit,
    onOpenStyle: () -> Unit,
    onDownload: () -> Unit,
    downloadEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = CardPadding,
                vertical = OverlayCardPaddingVertical,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(onClick = onOpenSearch)
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Ort, Stadt oder Straße suchen",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                ExploreTool(
                    icon = Icons.Filled.Place,
                    label = "Route planen",
                    onClick = onStartPlanning,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ExploreTool(
                    icon = Icons.Filled.Layers,
                    label = "Kartenstil",
                    onClick = onOpenStyle,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ExploreTool(
                    icon = Icons.Filled.DownloadForOffline,
                    label = "Offline",
                    onClick = onDownload,
                    enabled = downloadEnabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Ein Werkzeug des Erkunden-Blatts: Symbol ueber Beschriftung, damit drei
 * Stueck nebeneinander auf 360 dp passen — nebeneinander liefe „Route planen"
 * bereits in die Ellipse.
 */
@Composable
private fun ExploreTool(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    NeutralButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
