package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.core.GeoResult

/**
 * Ein ausgewaehlter Ort auf der Karte — das Google-Maps-Muster „der Ort ist
 * ein Objekt", nicht eine Sofortaktion an einer Suchtrefferzeile.
 *
 * Bewusst ein eigener Typ und nicht direkt [GeoResult]: [GeoResult] ist die
 * rohe Antwortform der Nominatim-Suche (siehe `:core/Geocoding.kt`, an deren
 * Feldnamen und Zuschnitt gebunden) und gehoert dorthin. Alles, was einen Ort
 * *entgegennimmt* — die Ortskarte, das Suchblatt, kuenftig auch ein
 * Ortswaehler fuer die Planungsliste (siehe `MapScreen.kt`s `openPlaceSearch`)
 * — soll nicht an dieses API-Detail gebunden sein, sondern an die drei Werte,
 * die tatsaechlich gebraucht werden.
 */
data class Place(val displayName: String, val lat: Double, val lon: Double)

/** Wandelt einen Nominatim-Treffer in das allgemeine Ort-Objekt um. */
internal fun GeoResult.toPlace(): Place = Place(displayName = displayName, lat = lat, lon = lon)

/**
 * Zerlegt [displayName] (Nominatims kommagetrennte Adresszeile) in Titel
 * (erstes Glied — Strasse, Ort oder POI-Name) und Gegend (der Rest). Genutzt
 * von [PlaceCard] und `SearchSheet.kt` fuer dieselbe zweizeilige Darstellung.
 */
internal fun placeTitleAndArea(displayName: String): Pair<String, String> {
    val title = displayName.substringBefore(',').trim().ifEmpty { displayName.trim() }
    val area = displayName.substringAfter(',', "").trim()
    return title to area
}

/**
 * Die Ortskarte — Schwester von [RideCard], nur fuer einen gesuchten statt
 * einen aufgezeichneten Ort.
 *
 * ## Aktionen haengen vom Kartenmodus ab, nicht von einer eigenen Auswahl
 * Es gibt bewusst keinen eigenen Schalter „was soll mit dem Ort passieren" —
 * das entscheidet [mode] (siehe `MapMode.kt`): Im Ruhezustand
 * ([MapMode.ERKUNDEN]) fuehrt ein Ort zu einer neuen Fahrt (hin oder als
 * Rundenstart), waehrend der Planung ([MapMode.PLANEN]) ist er ein weiterer
 * Wegpunkt der laufenden Route. Zwei verschiedene Knopfsaetze fuer denselben
 * Zustand haetten dieselbe Frage zweimal beantworten muessen.
 *
 * ## Keine Aktion waehrend [MapMode.NAVIGIEREN]
 * `MapMode.kt` beschreibt genau eine Ausnahme von den dokumentierten
 * Uebergaengen (Navigieren der eigenen geplanten Route bleibt in
 * [MapMode.PLANEN]) — jede weitere Kombination ist bewusst *nicht*
 * vorgesehen. Ein Wechsel nach [MapMode.PLANEN] waehrend
 * [MapMode.NAVIGIEREN] (etwa ueber „Route hierher") waere ein neuer,
 * undokumentierter Uebergang und wuerde denselben Fehler riskieren, den das
 * SSOT-Modell gerade verhindern soll. Die Karte bleibt in diesem Fall reine
 * Information — schliessen ueber das X geht immer.
 *
 * @param distanceKm Entfernung zur zuletzt bekannten eigenen Position, oder
 *   `null`, wenn keine vorliegt (keine Freigabe, noch kein Fix). Kommt vom
 *   Aufrufer (`MapScreen.kt`) synchron aus `MapController.lastKnownLocation()`
 *   — ein zweiter GPS-Abonnent nur fuer diese eine Zahl waere unnoetig, der
 *   Standortpunkt der Karte ist ohnehin schon aktiv.
 */
@Composable
internal fun PlaceCard(
    place: Place,
    mode: MapMode,
    distanceKm: Double?,
    onRouteHere: () -> Unit,
    onRoundTripHere: () -> Unit,
    onAddWaypoint: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, area) = placeTitleAndArea(place.displayName)
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        // Rechts 8 dp statt CardPadding: derselbe Ausgleich fuer den
        // eigenen Beruehrungsrand des Schliessen-Knopfs wie bei RideCard.
        Column(
            modifier = Modifier.padding(
                start = CardPadding,
                top = OverlayCardPaddingVertical,
                end = 8.dp,
                bottom = OverlayCardPaddingVertical,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val subtitle = placeSubtitle(area, distanceKm)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Ort schließen")
                }
            }
            Spacer(Modifier.height(OverlayCardPaddingVertical))
            when (mode) {
                MapMode.PLANEN -> PrimaryButton(
                    text = "Als Wegpunkt",
                    onClick = onAddWaypoint,
                    modifier = Modifier.fillMaxWidth(),
                )

                MapMode.ERKUNDEN -> Row {
                    PrimaryButton(
                        text = "Route hierher",
                        onClick = onRouteHere,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    NeutralButton(
                        onClick = onRoundTripHere,
                        modifier = Modifier.weight(1f),
                    ) { Text("Runde ab hier") }
                }

                // Siehe Klassen-KDoc: kein neuer, undokumentierter
                // Modus-Uebergang aus MapMode.NAVIGIEREN heraus.
                MapMode.NAVIGIEREN -> Unit
            }
        }
    }
}

/** Baut Gegend und Entfernung zu einer Unterzeile zusammen — beides optional. */
private fun placeSubtitle(area: String, distanceKm: Double?): String? {
    val distance = distanceKm?.let { "${formatKmDe(it)} km entfernt" }
    return listOfNotNull(area.takeIf(String::isNotBlank), distance)
        .joinToString(" · ")
        .takeIf(String::isNotBlank)
}
