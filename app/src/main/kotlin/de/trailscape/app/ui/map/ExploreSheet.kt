package de.trailscape.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical

/**
 * Das Ruhegesicht des einen Karten-Blatts — [MapMode.ERKUNDEN] ohne Auswahl —
 * als [SwipeableSheet]: Peek ist Suchzeile und Werkzeugreihe (unveraendert),
 * der Koerper ist die Tourenliste, die frueher ein eigenes drittes Blatt
 * (`TourSheet`, inzwischen entfernt) unter der Planung war.
 *
 * ## Warum die Tourenliste jetzt IM Erkunden-Blatt steckt statt daneben
 * `TourSheet` mit seiner Stufe `HIDDEN` loeste ein Problem, das mit dem
 * echten Ziehen ueber [SwipeableSheet] gar nicht mehr existiert: Ein
 * eigenes drittes Blatt war noetig, weil das Planungsblatt nur zwei Stufen
 * kannte und die Tourenliste trotzdem *irgendwo* still bleiben musste,
 * waehrend Aufzeichnung, Navigation oder eine gewaehlte Tour den unteren Rand
 * beanspruchten. Diese Faelle komponieren aber ohnehin schon KEIN
 * Erkunden-Blatt (siehe die Sichtbarkeitsbedingung um den Aufruf in
 * `MapScreen.kt`) — die Tourenliste braucht also keinen eigenen
 * Vorrang-Zustand mehr, sie verschwindet automatisch mit ihrem Blatt.
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
 *
 * ## Kopfzeile und Inhalt des Koerpers
 * Dieselbe Bauart wie zuvor bei `TourSheet`: „Touren" links (titleSmall),
 * die Anzahl rechts (labelMedium), darunter [tours] in einer `Box` mit
 * [toursMaxHeight] als Obergrenze. [tours] bekommt nur eine [PaddingValues]
 * gereicht und weiss nichts von `Card` oder Kopfzeile — der Aufrufer
 * (`MapScreen.kt`) fuellt sie mit `TourListContent` aus `ui/rides/TourList.kt`;
 * deren `LazyColumn` nimmt die Werte unveraendert als eigenes
 * `contentPadding`, damit weder die Kopfzeile ueberdeckt wird noch der letzte
 * Eintrag am unteren Blattrand klebt. Waagerecht und senkrecht gelten
 * dieselben Randmasse wie im aufgeklappten Planungsblatt
 * ([CardPadding]/[OverlayCardPaddingVertical]) — ein drittes Randmass haette
 * hier nichts erklaert, was diese beiden nicht schon tun.
 */
@Composable
internal fun ExploreSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    rideCount: Int,
    toursMaxHeight: Dp,
    onOpenSearch: () -> Unit,
    onStartPlanning: () -> Unit,
    onOpenStyle: () -> Unit,
    onDownload: () -> Unit,
    downloadEnabled: Boolean,
    modifier: Modifier = Modifier,
    tours: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    SwipeableSheet(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        peek = {
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
        },
        body = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = CardPadding, end = CardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Touren",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = rideCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Nur die Obergrenze wird hier erzwungen, keine eigene
                // `verticalScroll` — der Inhalt ist eine `LazyColumn` und
                // scrollt selbst; ein zweiter Scrollcontainer aussen wuerde
                // nur widerspruechliche Gesten erzeugen.
                Box(modifier = Modifier.heightIn(max = toursMaxHeight)) {
                    tours(
                        PaddingValues(
                            horizontal = CardPadding,
                            vertical = OverlayCardPaddingVertical,
                        ),
                    )
                }
            }
        },
    )
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
