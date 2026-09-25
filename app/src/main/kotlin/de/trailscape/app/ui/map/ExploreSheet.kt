package de.trailscape.app.ui.map

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import de.trailscape.app.ui.components.OneUiSearchField
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.rides.plannedRouteMeta
import de.trailscape.app.ui.today.TodayOffer
import de.trailscape.app.ui.today.offerChipLabel
import de.trailscape.core.GeoResult
import de.trailscape.core.RideSummary

/**
 * Das „Wohin?"-Blatt der Karte (Fuehrung „Klartext",
 * `docs/design/prototyp-klartext.html`, Karte im Ruhezustand).
 *
 * Es beantwortet genau eine Frage — „Wohin?" — und hat dafuer drei Wege, alle
 * ohne Aufziehen sichtbar:
 *
 *  * das **Suchfeld** (Ort, Stadt, Adresse; bei Fokus darunter Treffer bzw.
 *    der Suchverlauf, die Karte bleibt oben sichtbar),
 *  * **„Heute 45 km"** — die Runde, die „Heute" empfiehlt (fehlt an einem
 *    Ruhetag oder ohne Empfehlung),
 *  * **„Runde ab hier"** — oeffnet das Blatt, in dem Laenge und Untergrund
 *    gewaehlt werden ([RoundTripSetupSheet]).
 *
 * Beide Knoepfe sind **gleich breit** nebeneinander: Es sind zwei Einstiege
 * in dasselbe (eine Runde bauen), keine Rangfolge. Fehlt die Tagesrunde,
 * nimmt „Runde ab hier" die ganze Breite.
 *
 * ## Hochgewischt: was man schon hat
 * Der Griff haelt, was er verspricht: Hochgewischt zeigt das Blatt die
 * gespeicherten Routen (Planungen, neueste zuerst), die zuletzt gesuchten
 * Orte und den Weg zu den Offline-Karten. Vorher hatte das Blatt keinen
 * Koerper, und Wischen am Griff tat schlicht nichts. Ganz oben steht eine
 * kleine Zeile zum langen Druck auf die Karte — der einzigen Geste, die man
 * sonst nirgends sieht (siehe `LongPressHint.kt`).
 *
 * @param todayOffer die heute angebotene Runde oder `null`, wenn es heute
 *   keine gibt (Zieltag). Am Ruhetag ist es die lockere Runde; der Knopf sagt
 *   das dann auch („Locker · 16 km", Spa-Symbol) und tritt grau statt farbig
 *   auf — ein Angebot, keine Aufforderung.
 * @param bottomInset Platz, den das Blatt unten frei haelt (Kapsel bzw.
 *   Gestenleiste) — die Flaeche selbst laeuft bis an den Rand.
 */
@Composable
internal fun ExploreSheet(
    searchMaxHeight: Dp,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    onEndSearch: () -> Unit,
    searchBusy: Boolean,
    searchError: String?,
    searchResults: List<GeoResult>,
    searchHistory: List<Place>,
    onSelectPlace: (Place) -> Unit,
    todayOffer: TodayOffer?,
    onTodayRoute: () -> Unit,
    onRoundTripHere: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    savedRoutes: List<RideSummary>,
    onSelectRoute: (RideSummary) -> Unit,
    onOpenOfflineMaps: () -> Unit,
    bodyMaxHeight: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    SwipeableSheet(
        // Waehrend der Suche gehoert der Platz den Treffern.
        expanded = expanded && !searching,
        onExpandedChange = { open ->
            if (searching) onEndSearch()
            onExpandedChange(open)
        },
        modifier = modifier,
        bottomInset = bottomInset,
        peek = {
            Column(
                modifier = Modifier.padding(
                    start = CardPadding,
                    end = CardPadding,
                    top = 0.dp,
                    bottom = 12.dp,
                ),
            ) {
                OneUiSearchField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = "Wohin?",
                    busy = searchBusy,
                    onFocusChange = onSearchingChange,
                    onSearch = onSubmitSearch,
                )

                if (searching) {
                    Spacer(Modifier.height(4.dp))
                    PlaceResults(
                        query = searchQuery,
                        busy = searchBusy,
                        error = searchError,
                        results = searchResults,
                        history = searchHistory,
                        onSelect = onSelectPlace,
                        onSearch = onSubmitSearch,
                        modifier = Modifier
                            .heightIn(max = searchMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (todayOffer != null) {
                            FilledTonalButton(
                                onClick = onTodayRoute,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                contentPadding = ChipPadding,
                                colors = if (todayOffer.restDay) {
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    )
                                } else {
                                    ButtonDefaults.filledTonalButtonColors()
                                },
                            ) {
                                ChipContent(
                                    icon = if (todayOffer.restDay) Icons.Rounded.Spa else Icons.Rounded.Star,
                                    label = offerChipLabel(todayOffer),
                                )
                            }
                        }
                        // Grau statt NeutralButton: dessen Flaeche hat die
                        // Farbe der Karte und waere auf diesem Blatt unsichtbar.
                        FilledTonalButton(
                            onClick = onRoundTripHere,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            contentPadding = ChipPadding,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            ChipContent(icon = Icons.Rounded.Loop, label = "Runde ab hier")
                        }
                    }
                }
            }
        },
        body = {
            ExploreSheetBody(
                savedRoutes = savedRoutes,
                recentPlaces = searchHistory,
                onSelectRoute = onSelectRoute,
                onSelectPlace = onSelectPlace,
                onOpenOfflineMaps = onOpenOfflineMaps,
                modifier = Modifier
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
            )
        },
    )
}

@Composable
private fun ExploreSheetBody(
    savedRoutes: List<RideSummary>,
    recentPlaces: List<Place>,
    onSelectRoute: (RideSummary) -> Unit,
    onSelectPlace: (Place) -> Unit,
    onOpenOfflineMaps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(start = CardPadding, end = CardPadding, bottom = 12.dp)) {
        LongPressHintLine()
        if (savedRoutes.isNotEmpty()) {
            SectionLabel("Gespeicherte Routen")
            savedRoutes.take(MAX_ROWS).forEach { ride ->
                SheetRow(
                    title = ride.name,
                    // Dieselbe Zeile wie im Abschnitt „Geplant" des Verlaufs —
                    // mit „erstellt", damit das Datum nicht als Fahrtag gilt.
                    subtitle = plannedRouteMeta(localOfEpochMs(ride.createdAt), ride.stats),
                    icon = Icons.Rounded.Route,
                    onClick = { onSelectRoute(ride) },
                )
            }
        }
        if (recentPlaces.isNotEmpty()) {
            SectionLabel("Zuletzt gesucht")
            recentPlaces.take(MAX_ROWS).forEach { place ->
                val (title, area) = placeTitleAndArea(place.displayName)
                SheetRow(
                    title = title,
                    subtitle = area,
                    icon = Icons.Rounded.History,
                    onClick = { onSelectPlace(place) },
                )
            }
        }
        SectionLabel("Karte")
        SheetRow(
            title = "Offline-Karten",
            subtitle = "Gegenden für unterwegs ohne Netz laden",
            icon = Icons.Rounded.DownloadForOffline,
            onClick = onOpenOfflineMaps,
        )
    }
}

/**
 * Die leise, dauerhafte Erklaerung des langen Drucks (Massnahme U6, siehe
 * `LongPressHint.kt`): Die Geste hat keinen Knopf, und die einmalige Snackbar
 * ist nach dem ersten Mal weg. Hier, wo man ohnehin nach dem „Wohin?" sucht,
 * steht sie deshalb immer — klein und in `onSurfaceVariant`, damit sie
 * erklaert, ohne mit den Eintraegen darunter zu konkurrieren.
 */
@Composable
private fun LongPressHintLine() {
    Row(
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.TouchApp,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = LONG_PRESS_HINT_LINE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Hoechstens so viele Zeilen je Abschnitt — der Rest steht im Verlauf-Tab. */
private const val MAX_ROWS = 5

@Composable
private fun ChipContent(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        // Schrumpft bei grosser Systemschrift, statt abgeschnitten zu werden
        // („Runde ab h…" auf dem Geraet).
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelLarge,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 15.sp),
        )
    }
}

/** Innenabstand der beiden Knoepfe — knapper als der Material-Standard (24 dp). */
private val ChipPadding = PaddingValues(horizontal = 12.dp)
