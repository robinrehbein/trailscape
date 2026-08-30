package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * Die beiden Erkunden-Stufen des **einen** Kartenblatts — [MapSheetStage.EINGEKLAPPT]
 * und [MapSheetStage.AUFGEZOGEN] — als [SwipeableSheet]: Peek ist die Suchzeile,
 * Koerper ist die Aktionszeile „Route planen · Kartenstil · Offline".
 *
 * Die dritte Stufe [MapSheetStage.PLANEN] steht an derselben Stelle im Stapel,
 * wird aber von `PlanningSheet` (`PlanningPanel.kt`) gezeichnet; die Zuordnung
 * Stufe → Blatt und die Uebergaenge stehen im KDoc von `MapScreen.kt`
 * („Das eine Kartenblatt und seine drei Stufen"). Design-Referenz ist der vom
 * Nutzer freigegebene Karte-Screen in `docs/design/prototyp-eine-leiste.html`.
 *
 * ## Warum hier keine Tourenliste und kein „Importieren" mehr stehen
 * Beides stand bis hierher im Koerper bzw. im Peek dieses Blatts — die
 * Tourenliste als aufziehbarer Koerper, der Import als Textknopf daneben. Der
 * Grund dafuer ist mit der Fuehrung „Eine Leiste" entfallen: Touren haben
 * seither einen **eigenen Tab** (`ui/rides/RidesScreen.kt`), und in ihm wohnt
 * auch der GPX-Import. Was hier stand, war damit eine Dublette — dieselbe
 * Liste, derselbe Einstieg, nur schlechter erreichbar (erst Blatt aufziehen)
 * und in Konkurrenz zu genau dem Platz, den die Karte braucht.
 *
 * Die Karte behaelt trotzdem ihre **raeumliche** Sicht auf den Bestand, nur auf
 * Zuruf statt als stehende Liste: Der Touren-Tab schickt ueber
 * [de.trailscape.app.ui.AppViewModel.showRideOnMapRequest] eine Tour-Kennung
 * herueber („zeig mir das auf der Karte"), der Karten-Screen waehlt sie aus,
 * zoomt auf ihre Spur und laesst dieses Blatt dabei eingeklappt (siehe den
 * zugehoerigen Effekt in `MapScreen.kt`). Der Bestand liegt damit an einer
 * Stelle, die Karte zeigt eine Tour — statt beides an beiden Orten halb.
 *
 * ## Eingeklappt ist der Ruhezustand — und er ist wirklich schlank
 * Vorher war „eingeklappt" schon eine Suchzeile **plus** Werkzeugreihe **plus**
 * Touren-Zeile: rund 240 dp, die auf einem 360×800-dp-Geraet dauerhaft ueber
 * der Karte lagen. Jetzt bleiben Griff und Suchzeile stehen (rund 130 dp), und
 * die Aktionszeile ist genau das, was das Aufziehen freigibt. Die Karte ist im
 * Ruhezustand damit fast vollstaendig sichtbar — das ist der Punkt des ganzen
 * Umbaus.
 *
 * ## Suchfeld zuerst — und es IST das Feld
 * Die Suche ist die haeufigste Aktion und steht deshalb als volle Zeile im Peek
 * (Google-Maps-Muster). Hier stand einmal eine **Attrappe**: eine `Row`, die
 * aussah wie ein Feld, aber ein Knopf war und ein zweites, modales Blatt mit
 * dem echten Feld oeffnete — zwei Blaetter uebereinander, zwei Griffe und ein
 * Scrim, der die Karte wegnahm.
 *
 * Jetzt gibt es nur noch **ein** Feld, und es sitzt hier. Bekommt es den Fokus,
 * zeigt derselbe Peek darunter [PlaceResults] — Treffer, oder bei leerem Feld
 * sofort den Suchverlauf. Die Karte bleibt oben sichtbar, und das ist bei einer
 * *Orts*suche keine Nebensache.
 *
 * Das modale [SearchSheet] bleibt bestehen, aber nur noch fuer die
 * Wegpunktsuche der Planung — dort ist dieses Blatt gar nicht komponiert
 * (Begruendung in dessen KDoc).
 *
 * ## Drei gleichrangige Aktionen, keine Hauptaktion
 * „Route planen", Kartenstil und Offline sind Einstiege in Verschiedenes, keine
 * Stufen einer Entscheidung — deshalb drei gleiche helle Flaechen (One UI:
 * [NeutralButton]) und keine gefaerbte Pille, die eine Rangfolge behauptet, die
 * es nicht gibt.
 *
 * @param expanded Ob die Aktionszeile freigegeben ist — also
 *   [MapSheetStage.AUFGEZOGEN] statt [MapSheetStage.EINGEKLAPPT]. Der Zustand
 *   bleibt beim Aufrufer (und dort in `rememberSaveable`), damit ein Tabwechsel
 *   oder eine Drehung die Stufe nicht zuruecksetzt.
 * @param searchMaxHeight Obergrenze der Trefferliste — ein Sicherheitsnetz, kein
 *   Deckel: Die Liste steht im Peek und wird ohnehin gegen den wirklich
 *   vorhandenen Platz gemessen (siehe `SEARCH_RESULTS_MAX_HEIGHT_FACTOR` in
 *   `MapScreen.kt`).
 */
@Composable
internal fun ExploreSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    SwipeableSheet(
        // Waehrend der Suche gibt es keinen ziehbaren Koerper — die Treffer
        // stehen im Peek (Begruendung gleich darunter).
        expanded = expanded && !searching,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        peek = {
            Column(
                // Oben KEIN eigener Abstand: Die Griffzeile des
                // [SwipeableSheet] reserviert bereits 48 dp Beruehrflaeche —
                // ein zusaetzliches Polster darunter stapelte im ersten
                // Geraetetest sichtbar Leerraum auf Leerraum, und der
                // "schlanke" eingeklappte Zustand war ein hoher. Unten reicht
                // ein knapperes Mass als das allgemeine Overlay-Polster.
                modifier = Modifier.padding(
                    start = CardPadding,
                    end = CardPadding,
                    top = 0.dp,
                    bottom = 10.dp,
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
                    // Der erste Anlauf legte sie in den ziehbaren Koerper. Auf
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
                        modifier = Modifier
                            .heightIn(max = searchMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        body = {
            // Die Aktionszeile — das, was das Aufziehen freigibt. Kein
            // `heightIn(max = …)` und kein Scrollcontainer: Anders als
            // Tourenliste, Planung und Rundenwahl ist das hier eine Zeile
            // fester Hoehe, die in jedes Budget passt (siehe
            // `overlaySheetBudget` in `MapScreen.kt`). Ein Deckel haette hier
            // nichts begrenzt, nur eine Zahl zu pflegen gegeben.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = CardPadding,
                        end = CardPadding,
                        bottom = OverlayCardPaddingVertical,
                    ),
            ) {
                MapSheetAction(
                    icon = Icons.Filled.Place,
                    label = "Route planen",
                    onClick = onStartPlanning,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                MapSheetAction(
                    icon = Icons.Filled.Layers,
                    label = "Kartenstil",
                    onClick = onOpenStyle,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                MapSheetAction(
                    icon = Icons.Filled.DownloadForOffline,
                    label = "Offline",
                    onClick = onDownload,
                    enabled = downloadEnabled,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

/**
 * Eine Aktion der aufgezogenen Stufe: Symbol ueber Beschriftung, damit drei
 * Stueck nebeneinander auf 360 dp passen — nebeneinander liefe „Route planen"
 * bereits in die Ellipse.
 */
@Composable
private fun MapSheetAction(
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
