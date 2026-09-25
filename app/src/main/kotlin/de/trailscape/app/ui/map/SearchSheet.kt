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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.CancellationException

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
 * ## Gesucht wird erst beim Absenden
 * Die Ortssuche fragt Nominatim, und dessen Nutzungsrichtlinie verbietet
 * Autovervollstaendigung (https://operations.osmfoundation.org/policies/nominatim/:
 * „Auto-complete search … is not yet supported … you must not implement
 * such a service on the client side using the API"). Frueher lief die Suche
 * nach einer kurzen Tipp-Pause von selbst los — das ist genau dieses
 * Muster, nur entprellt. Jetzt fragt die App erst, wenn die Nutzerin
 * absendet: Suchtaste der Tastatur ([onSearch]) oder die Zeile
 * „„…" suchen" unter dem Feld ([PlaceResults]), damit das Absenden nicht
 * nur auf der Tastatur zu finden ist.
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
    onSearch: () -> Unit,
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
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
                busy = busy,
                error = error,
                results = results,
                history = history,
                onSelect = onSelect,
                onSearch = onSearch,
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
 *
 * Steht Text im Feld, aber (noch) kein Treffer darunter, bietet die Liste
 * das Absenden als eigene Zeile an („„Tübingen" suchen"): Gesucht wird nur
 * auf Wunsch (siehe Datei-KDoc), und die Suchtaste der Tastatur allein waere
 * ein Weg, den nicht jede Tastatur gleich deutlich zeigt.
 */
@Composable
internal fun PlaceResults(
    query: String,
    error: String?,
    results: List<GeoResult>,
    history: List<Place>,
    onSelect: (Place) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
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

            // Zu kurz fuer eine Suche: Statt einer Zeile, die beim Antippen
            // nur die Mindestlaenge anmahnt, gleich der Hinweis selbst.
            query.isNotBlank() && placeSearchQueryOrNull(query) == null -> Text(
                text = "Mindestens $MIN_PLACE_SEARCH_LENGTH Zeichen eingeben, dann suchen.",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Nach einer Meldung (keine Treffer, Netzfehler) steht dieselbe
            // Aktion als „erneut" da — das sagt, dass Tippen nichts Neues
            // ausloest, ein Tipp hier aber schon.
            query.isNotBlank() && !busy -> SheetRow(
                title = if (error != null) "Erneut suchen" else "„${query.trim()}“ suchen",
                subtitle = if (error != null) "„${query.trim()}“" else "Ortssuche über OpenStreetMap",
                icon = Icons.Filled.Search,
                onClick = onSearch,
            )

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
    SheetRow(title = title, subtitle = area, icon = icon, onClick = onClick)
}

/**
 * Die Listenzeile der Kartenblaetter (Suchtreffer, gespeicherte Routen …):
 * Symbol, Titel mit optionaler Unterzeile, Chevron. Die ganze Zeile ist die
 * Aktion.
 */
@Composable
internal fun SheetRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
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
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
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

/**
 * So viele Zeichen braucht eine Ortssuche mindestens. Kuerzeres liefert bei
 * Nominatim nur Rauschen und waere eine Anfrage fuer nichts.
 */
internal const val MIN_PLACE_SEARCH_LENGTH = 3

/**
 * Was aus dem Feldinhalt [raw] an Nominatim geht: getrimmt, oder `null`, wenn
 * es zu kurz ist ([MIN_PLACE_SEARCH_LENGTH]).
 */
internal fun placeSearchQueryOrNull(raw: String): String? =
    raw.trim().takeIf { it.length >= MIN_PLACE_SEARCH_LENGTH }

/**
 * Eine abgesendete Ortssuche. Die laufende Nummer [seq] macht jedes Absenden
 * zu einem neuen Schluessel fuer [PlaceSearchEffect] — auch dann, wenn
 * derselbe Text nach einem Netzfehler noch einmal geschickt wird.
 */
internal data class PlaceSearchSubmission(val query: String, val seq: Int)

/**
 * Zustand der Ortssuche im Karten-Screen: Feldtext, letzte Abgabe, Treffer,
 * Meldung.
 *
 * Steht als eigener Halter hier (und nicht als fuenf lose Variablen im
 * Screen), damit die Regel „Tippen fragt nie, nur Absenden" an einer Stelle
 * haengt, die ein Test ohne den ganzen Karten-Screen erreicht
 * (`PlaceSearchTest`). Nur [changeQuery] aendert den Text, und es setzt
 * dabei die Abgabe zurueck; nur [submit] erzeugt eine neue.
 */
@Stable
internal class PlaceSearchState(initialQuery: String = "") {
    var query by mutableStateOf(initialQuery)
        private set
    var submission by mutableStateOf<PlaceSearchSubmission?>(null)
        private set
    var results by mutableStateOf<List<GeoResult>>(emptyList())
        internal set
    var busy by mutableStateOf(false)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    /**
     * Neuer Text im Suchfeld — ohne jede Anfrage. Alte Treffer und Meldungen
     * verschwinden, weil sie zu einem anderen Text gehoeren; eine noch
     * laufende Suche bricht ab, weil ihr Schluessel ([submission]) wegfaellt.
     */
    fun changeQuery(text: String) {
        query = text
        submission = null
        results = emptyList()
        error = null
        busy = false
    }

    /**
     * Schickt den Feldinhalt ab. Liefert `false` (mit Meldung), wenn er zu
     * kurz ist ([MIN_PLACE_SEARCH_LENGTH]) — dann geht nichts an Nominatim.
     */
    fun submit(): Boolean {
        val trimmed = placeSearchQueryOrNull(query)
        if (trimmed == null) {
            error = "Bitte mindestens $MIN_PLACE_SEARCH_LENGTH Zeichen eingeben."
            return false
        }
        submission = PlaceSearchSubmission(trimmed, (submission?.seq ?: 0) + 1)
        return true
    }

    companion object {
        /** Ueber Drehen hinweg bleibt nur der Text; Treffer holt ein neues Absenden. */
        val Saver: Saver<PlaceSearchState, String> = Saver(
            save = { it.query },
            restore = { PlaceSearchState(it) },
        )
    }
}

/**
 * Fuehrt die Ortssuche aus — **nur** fuer eine Abgabe
 * ([PlaceSearchState.submission]), nie fuer den blossen Feldtext.
 *
 * Frueher hing dieser Effekt am Suchtext und fragte nach einer Tipp-Pause von
 * selbst; das ist die Autovervollstaendigung, die Nominatim verbietet (siehe
 * Datei-KDoc). Der Schluessel ist deshalb ausschliesslich die Abgabe: Wird
 * weitergetippt, setzt [PlaceSearchState.changeQuery] sie zurueck, und der
 * Schluesselwechsel bricht eine noch laufende Anfrage ab. `PlaceSearchTest`
 * haelt genau das fest — wer hier den Text als Schluessel eintraegt, bekommt
 * einen roten Test.
 *
 * [search] ist die eigentliche Anfrage (im Screen: Nominatim ueber
 * `searchPlaces`); Fehler daraus landen als Meldung in [state].
 */
@Composable
internal fun PlaceSearchEffect(
    state: PlaceSearchState,
    maxResults: Int,
    search: suspend (String) -> List<GeoResult>,
) {
    val submission = state.submission
    LaunchedEffect(state, submission) {
        val query = submission?.query ?: return@LaunchedEffect
        state.busy = true
        state.error = null
        val result = runCatching { search(query) }
        if (result.exceptionOrNull() is CancellationException) return@LaunchedEffect
        result
            .onSuccess { hits ->
                state.results = hits.take(maxResults)
                state.error = if (hits.isEmpty()) "Keine Treffer gefunden." else null
            }
            .onFailure {
                state.results = emptyList()
                state.error = it.message?.takeIf(String::isNotBlank) ?: "Ortssuche fehlgeschlagen."
            }
        state.busy = false
    }
}

/** Anteil der Bildschirmhoehe, den das aufgeklappte Suchblatt hoechstens einnimmt. */
private const val SEARCH_SHEET_MAX_HEIGHT_FACTOR = 0.7f
