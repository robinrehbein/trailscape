package de.trailscape.app.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.OneUiTextField
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.GeoResult

/**
 * # Das Suchblatt — die Ortssuche als **kurze Besorgung** aus einem anderen Blatt
 *
 * ## Wofuer es noch da ist, seit die Suche im Erkunden-Blatt wohnt
 * Bis dahin bediente dieses Blatt beide Einstiege in die Ortssuche. Der
 * haeufigere — die Suche im Erkunden-Blatt — laeuft inzwischen dort an Ort und
 * Stelle (`ExploreSheet.kt`): Das Feld IST das Feld, die Treffer stehen direkt
 * darunter, die Karte bleibt sichtbar.
 *
 * Uebrig bleibt der zweite Einstieg, und der ist etwas anderes: **„Wegpunkt
 * per Suche"** aus der Routenplanung (`openPlaceSearch { … }` mit Rueckruf in
 * `MapScreen.kt`). Dort ist das Erkunden-Blatt gar nicht komponiert — der
 * Bildschirm gehoert der Planung —, und die Suche ist eine kurze Besorgung:
 * einen Ort holen, zurueckgeben, verschwinden. Genau dafuer ist ein modales
 * Blatt gebaut. Was beide Faelle wirklich teilen, ist nicht das Blatt, sondern
 * die Trefferliste; die steht deshalb als [PlaceResults] fuer sich.
 *
 * Ersetzt das fruehere `SearchPanel` (Karte im oberen Panelstapel,
 * `PlanningPanel.kt`), das mit Trefferzeile UND Textknopf („Anzeigen"/„Als
 * Wegpunkt") gleich zwei Aufgaben in einer Zeile vermischte. Seit diesem
 * Umbau ist ein Treffer nur noch **auswaehlbar** — was mit der Auswahl
 * passiert, entscheidet [PlaceCard] anhand des Kartenmodus (siehe deren
 * KDoc), nicht mehr die Suchzeile selbst.
 *
 * ## Warum `ModalBottomSheet` und nicht die Bauart von `PlanningSheet`/`ExploreSheet`
 * `PlanningSheet` und `ExploreSheet` sind Karten, die dauerhaft am unteren
 * Bildschirmrand mitlaufen (kein Scrim, koexistieren mit der Karte, dazu
 * ueber [SwipeableSheet] echt ziehbar) — dieses Blatt dagegen ist ein kurzer,
 * modaler Vorgang mit eigener Tastatur, der beim Verlassen wieder ganz
 * verschwindet. Genau dafuer hat diese Datei-Familie schon ein Vorbild:
 * `MapStyleSheet` (unten in `MapScreen.kt`) ist bereits ein „von unten
 * hochfahrendes Blatt" ueber `ModalBottomSheet` — mit Scrim, eigenem Fenster
 * und dem in Material 3 eingebauten Griff ([BottomSheetDefaults] `DragHandle`,
 * hier der geforderte „Grabber"). Dieses Blatt uebernimmt genau diese Mechanik
 * und nur den *Inhalt* (Suchfeld, Trefferzeilen) von
 * `PlanningSheet`/`ExploreSheet`.
 *
 * ## Autofokus
 * Das Suchfeld bekommt den Fokus (und damit die Tastatur), sobald das Blatt
 * steht — wer die Suchzeile antippt, will tippen, nicht erst noch das Feld selbst
 * treffen.
 *
 * ## „Zuletzt gesucht" statt Treffer, wenn das Feld leer ist
 * Dasselbe Muster wie Google Maps: Ein frisch geoeffnetes, leeres Suchfeld
 * zeigt die zuletzt gewaehlten Orte statt einer leeren Flaeche — sobald
 * getippt wird, weichen sie den echten Treffern. Die Historie selbst liegt im
 * [de.trailscape.app.ui.AppViewModel] (siehe dessen „Suchverlauf"-Abschnitt);
 * dieses Blatt zeigt nur, was es bekommt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    results: List<GeoResult>,
    history: List<Place>,
    onSelect: (Place) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * SEARCH_SHEET_MAX_HEIGHT_FACTOR

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(horizontal = CardPadding)
                .padding(bottom = CardPadding),
        ) {
            OneUiTextField(
                label = "Ort suchen",
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Ort, Stadt oder Straße",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    when {
                        busy -> CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )

                        query.isNotEmpty() -> IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Suche leeren")
                        }
                    }
                },
                fieldModifier = Modifier.focusRequester(focusRequester),
            )

            PlaceResults(
                query = query,
                error = error,
                results = results,
                history = history,
                onSelect = onSelect,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * Was unter einem Suchfeld steht: Fehlermeldung, dann entweder Treffer, der
 * Suchverlauf oder — wenn es beides nicht gibt — ein Satz, der sagt, was hier
 * hingehoert.
 *
 * Eigene Funktion, weil es diese Liste inzwischen an **zwei** Stellen gibt:
 * fest im Erkunden-Blatt der Karte (siehe `ExploreSheet.kt`) und in diesem
 * modalen Blatt fuer die Wegpunktsuche der Planung. Das Gemeinsame ist die
 * Liste, nicht das Blatt — genau deshalb steht sie hier fuer sich und nicht
 * zweimal.
 *
 * Der **Verlauf erscheint sofort**, sobald das Feld leer und fokussiert ist,
 * nicht erst nach dem ersten Zeichen: Wer die Suche oeffnet, hat meist ein
 * Ziel im Kopf, das er schon einmal gesucht hat. Ein Tipp statt acht.
 */
@Composable
internal fun PlaceResults(
    query: String,
    error: String?,
    results: List<GeoResult>,
    history: List<Place>,
    onSelect: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when {
            results.isNotEmpty() -> results.forEach { result ->
                PlaceRow(
                    displayName = result.displayName,
                    onClick = { onSelect(result.toPlace()) },
                )
            }

            query.isBlank() && history.isNotEmpty() -> {
                Text(
                    text = "Zuletzt gesucht",
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                history.forEach { place ->
                    PlaceRow(
                        displayName = place.displayName,
                        icon = Icons.Filled.History,
                        onClick = { onSelect(place) },
                    )
                }
            }

            query.isBlank() -> Text(
                text = "Suche nach einem Ort, einer Stadt oder einer Adresse.",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Eine volle, antippbare Trefferzeile — mindestens 48 dp hoch, Chevron
 * rechts, zweizeilig (Name + Gegend). Bewusst **kein** Textknopf mehr in der
 * Zeile (siehe Datei-KDoc): Die ganze Zeile ist die Aktion.
 */
@Composable
private fun PlaceRow(
    displayName: String,
    onClick: () -> Unit,
    icon: ImageVector = Icons.Filled.LocationOn,
) {
    val (title, area) = placeTitleAndArea(displayName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            if (area.isNotBlank()) {
                Text(
                    text = area,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Anteil der Bildschirmhoehe, den das aufgeklappte Suchblatt hoechstens einnimmt. */
private const val SEARCH_SHEET_MAX_HEIGHT_FACTOR = 0.7f
