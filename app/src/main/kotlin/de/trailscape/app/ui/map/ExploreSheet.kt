package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.OneUiSearchField
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.core.GeoResult

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
 * ## Suchfeld zuerst — und es IST jetzt das Feld
 * Die Suche ist die haeufigste Aktion und steht deshalb als volle Zeile im
 * Kopf (Google-Maps-Muster).
 *
 * Hier stand bis dahin eine **Attrappe**: eine `Row`, die aussah wie ein Feld,
 * aber ein Knopf war und ein zweites, modales Blatt mit dem echten Feld
 * oeffnete. Begruendet war das damit, dass zwei fokussierbare Felder zwei
 * Tastatur-Zustaende fuer eine Aufgabe waeren — was stimmt, aber den Preis
 * verschwieg: zwei Blaetter uebereinander, zwei Griffe, ein Scrim, der die
 * Karte wegnahm, und ein Bedienelement, das eine Erwartung weckte und sie an
 * einen anderen Ort weiterreichte.
 *
 * Jetzt gibt es nur noch **ein** Feld, und es sitzt hier. Bekommt es den
 * Fokus, faehrt dasselbe Blatt auf und sein Koerper wechselt von der
 * Tourenliste zu [PlaceResults] — Treffer, oder bei leerem Feld sofort der
 * Suchverlauf. Die Karte bleibt dabei oben sichtbar, und das ist bei einer
 * *Orts*suche keine Nebensache.
 *
 * Das modale [SearchSheet] bleibt bestehen, aber nur noch fuer die
 * Wegpunktsuche der Planung — dort ist dieses Blatt gar nicht komponiert
 * (Begruendung in dessen KDoc).
 *
 * ## Drei gleichrangige Werkzeuge, keine Hauptaktion
 * „Route planen", Kartenstil und Offline sind Einstiege in Verschiedenes,
 * keine Stufen einer Entscheidung — deshalb drei gleiche helle Flaechen
 * (One UI: [NeutralButton]) und keine gefaerbte Pille, die eine Rangfolge
 * behauptet, die es nicht gibt.
 *
 * ## Die Touren-Zeile: sagen, was hinter dem Griff liegt
 * Unter den Werkzeugen steht eine kompakte Zeile „N Touren" (Tipp klappt die
 * Liste auf, derselbe Weg wie der Griff) und rechts „Importieren"
 * ([onImport]). Vorher verriet der eingeklappte Zustand mit keinem Wort, dass
 * hinter dem Griff die Tourenliste — und in ihr der einzige Import-Einstieg
 * dieses Tabs — wohnt; seit dem Wegfall des eigenen Touren-Tabs war der
 * GPX-Import damit faktisch unauffindbar („kann ich keine GPX-Dateien mehr
 * importieren?"). Jetzt stehen beide Auskuenfte im Ruhezustand: dass es Touren
 * gibt, und wie eine neue hereinkommt. Waehrend der Suche tritt die Zeile mit
 * den Werkzeugen ab (gleiche Begruendung dort im Code).
 *
 * ## Inhalt des Koerpers
 * [tours] in einer `Box` mit [toursMaxHeight] als Obergrenze — die fruehere
 * Kopfzeile „Touren · Anzahl" ist entfallen, seit die Touren-Zeile im Peek
 * dieselbe Auskunft dauerhaft gibt. [tours] bekommt nur eine [PaddingValues]
 * gereicht und weiss nichts von `Card` oder Kopfzeile — der Aufrufer
 * (`MapScreen.kt`) fuellt sie mit `TourListContent` aus `ui/rides/TourList.kt`;
 * deren `LazyColumn` nimmt die Werte unveraendert als eigenes
 * `contentPadding`, damit der letzte Eintrag nicht am unteren Blattrand klebt.
 * Waagerecht und senkrecht gelten dieselben Randmasse wie im aufgeklappten
 * Planungsblatt ([CardPadding]/[OverlayCardPaddingVertical]) — ein drittes
 * Randmass haette hier nichts erklaert, was diese beiden nicht schon tun.
 *
 * @param toursMaxHeight Obergrenze der Tourenliste. Muss aus dem Platz kommen,
 *   den der Stapel dem Blatt wirklich lassen kann (`overlaySheetBudget` in
 *   `MapScreen.kt`) — **nicht** aus einem Anteil der Bildschirmhoehe. Hier
 *   stand `0,8 * Bildschirmhoehe`, also rund 665 dp, waehrend das Blatt selbst
 *   nur gut 200 dp hoch werden konnte. Die `LazyColumn` blieb damit unter ihrer
 *   Obergrenze, wurde inhaltsgross und hatte keinen Scrollweg; sichtbar war
 *   trotzdem nur, was ins kleinere Fenster passte. Zwei Touren, eine zu sehen,
 *   kein Scrollen — der Deckel war zu weit, nicht zu eng.
 */
@Composable
internal fun ExploreSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    rideCount: Int,
    toursMaxHeight: Dp,
    searchMaxHeight: Dp,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    searchBusy: Boolean,
    searchError: String?,
    searchResults: List<GeoResult>,
    searchHistory: List<Place>,
    onSelectPlace: (Place) -> Unit,
    onStartPlanning: () -> Unit,
    onOpenStyle: () -> Unit,
    onDownload: () -> Unit,
    downloadEnabled: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    tours: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    SwipeableSheet(
        // Waehrend der Suche gibt es keinen ziehbaren Koerper — die Treffer
        // stehen im Peek (Begruendung gleich darunter).
        expanded = expanded && !searching,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        peek = {
            Column(
                modifier = Modifier.padding(
                    horizontal = CardPadding,
                    vertical = OverlayCardPaddingVertical,
                ),
            ) {
                OneUiSearchField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = "Ort, Stadt oder Straße suchen",
                    busy = searchBusy,
                    onFocusChange = onSearchingChange,
                )

                if (searching) {
                    // ## Warum die Treffer im Peek stehen und nicht im Koerper
                    //
                    // Der erste Anlauf legte sie in den ziehbaren Koerper —
                    // naheliegend, denn dort steht auch die Tourenliste. Auf
                    // dem Geraet blieben sie unsichtbar.
                    //
                    // Der Grund: Die Hoehe des Koerpers ist keine gemessene
                    // Groesse, sondern der **Zieh-Offset** — eine von Hand
                    // animierte Zahl zwischen zwei Ankern. Faehrt gleichzeitig
                    // die Tastatur auf, animiert `imePadding` den verfuegbaren
                    // Platz, waehrend `anchoredDraggable` seine Anker aus einer
                    // Messung neu bildet, die genau in diesem Moment wandert.
                    // Zwei Animationen um dieselbe Hoehe, und der Offset
                    // gewinnt: Er stand auf null, das Fenster war zu, der
                    // Inhalt dahinter.
                    //
                    // Der Peek hat dieses Problem nicht. Er ist gewoehnlicher
                    // Inhalt mit gewoehnlicher Messung — er ist einfach da.
                    // Deshalb wandert die Suche dorthin, statt den
                    // Zieh-Mechanismus mit einem Sonderfall zu belasten.
                    Spacer(Modifier.height(4.dp))
                    PlaceResults(
                        query = searchQuery,
                        error = searchError,
                        results = searchResults,
                        history = searchHistory,
                        onSelect = onSelectPlace,
                        // Die Obergrenze ist hier nur noch ein Sicherheitsnetz:
                        // Der Peek wird ohnehin gegen den wirklich vorhandenen
                        // Platz gemessen, die Tastatur ist also schon
                        // eingerechnet. Was trotzdem nicht hineinpasst, scrollt.
                        modifier = Modifier
                            .heightIn(max = searchMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    )
                } else {
                    // Waehrend gesucht wird, treten die drei Werkzeuge ab. Sie
                    // sind Einstiege in etwas Anderes und haetten unter einer
                    // laufenden Trefferliste nur Hoehe gekostet, ohne je
                    // gemeint zu sein.
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

                    // Die Touren-Zeile (siehe Klassen-KDoc): links der Weg zur
                    // Liste, rechts der Import — beide auch im Ruhezustand
                    // sichtbar, nicht erst hinter dem Griff.
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onExpandedChange(!expanded) }) {
                            Text(
                                text = if (rideCount == 1) "1 Tour" else "$rideCount Touren",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = if (expanded) {
                                    Icons.Filled.KeyboardArrowDown
                                } else {
                                    Icons.Filled.KeyboardArrowUp
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        TextButton(onClick = onImport, enabled = !importing) {
                            if (importing) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Importieren")
                            }
                        }
                    }
                }
            }
        },
        body = {
            Box(modifier = Modifier.heightIn(max = toursMaxHeight)) {
                // Keine eigene Kopfzeile mehr — die Touren-Zeile im Peek nennt
                // die Anzahl bereits (siehe Klassen-KDoc). Und keine eigene
                // `verticalScroll` — der Inhalt ist eine `LazyColumn` und
                // scrollt selbst; ein zweiter Scrollcontainer aussen wuerde
                // nur widerspruechliche Gesten erzeugen.
                tours(
                    PaddingValues(
                        horizontal = CardPadding,
                        vertical = OverlayCardPaddingVertical,
                    ),
                )
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
