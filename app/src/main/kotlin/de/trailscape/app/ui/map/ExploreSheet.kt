package de.trailscape.app.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.components.OneUiSearchField
import de.trailscape.app.ui.components.RouteCard
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.rides.plannedRouteMeta
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalAtlasColors
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.PrimaryButtonMinHeight
import de.trailscape.app.ui.theme.useNavigationRail
import de.trailscape.core.GeoResult
import de.trailscape.core.Ride
import de.trailscape.core.RideSummary
import kotlin.math.roundToInt

/**
 * Das „Wohin?"-Blatt der Karte (Fuehrung „Klartext",
 * `docs/design/prototyp-klartext.html`, Karte im Ruhezustand).
 *
 * Es beantwortet genau eine Frage — „Wohin?" — und hat dafuer drei Wege, alle
 * ohne Aufziehen sichtbar:
 *
 *  * das **Suchfeld** (Ort, Stadt, Adresse; bei Fokus darunter Treffer bzw.
 *    der Suchverlauf, die Karte bleibt oben sichtbar), rechts daneben das
 *    Zahnrad zu den Einstellungen,
 *  * **„Route planen"** — beginnt die Planung direkt, auch ohne vorher
 *    gewaehlten Ort (`enterPlanning` in `MapScreen.kt`),
 *  * **„Runde bauen"** — oeffnet das Blatt, in dem Laenge und Untergrund
 *    gewaehlt werden ([RoundTripSetupSheet]).
 *
 * Beide Knoepfe sind **gleich breit** nebeneinander. Die Runde des Tages
 * („Runde für heute bauen") hat ihren Einstieg auf „Heute" und im Blatt
 * „Losfahren", nicht mehr hier (docs/ux/seitenkarte.md, Befund 5).
 *
 * ## Hochgewischt: was man schon hat
 * Hochgewischt zeigt das Blatt die gespeicherten Routen (Planungen, neueste
 * zuerst) und die zuletzt gesuchten Orte. Ganz oben steht eine kleine Zeile,
 * dass ein Tipp auf die Karte einen Punkt setzt (siehe `LongPressHint.kt`).
 * Kartenstil und Offline-Ausschnitt haben genau einen Einstieg: den
 * Ebenen-Knopf ueber dem Blatt.
 *
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
    onPlanRoute: () -> Unit,
    onRoundTripHere: () -> Unit,
    onOpenSettings: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    savedRoutes: List<RideSummary>,
    onSelectRoute: (RideSummary) -> Unit,
    expandedHeight: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    onRevealFraction: ((Float) -> Unit)? = null,
    loadRide: suspend (String) -> Ride? = { null },
    inSidePanel: Boolean = false,
) {
    val atlas = LocalAtlasColors.current
    val hideSettingsAction = useNavigationRail(LocalWindowLayoutClass.current)
    SwipeableSheet(
        // Waehrend der Suche gehoert der Platz den Treffern.
        expanded = expanded && !searching,
        onExpandedChange = { open ->
            if (searching) onEndSearch()
            onExpandedChange(open)
        },
        modifier = modifier,
        bottomInset = bottomInset,
        // Ganz aufgezogen reicht die Karte bis oben — der Platz fuer
        // gespeicherte Routen und Orte, ohne einen eigenen Bildschirm.
        expandedHeight = expandedHeight,
        onRevealFraction = onRevealFraction,
        inSidePanel = inSidePanel,
        peek = {
            Column(
                modifier = Modifier.padding(
                    start = CardPadding,
                    end = CardPadding,
                    top = 0.dp,
                    bottom = 12.dp,
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OneUiSearchField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = stringResource(R.string.map_explore_search_placeholder),
                        modifier = Modifier.weight(1f),
                        busy = searchBusy,
                        onFocusChange = onSearchingChange,
                        onSearch = onSubmitSearch,
                    )
                    // Auf Telefonen steht das Zahnrad hier; bei aktiver Rail
                    // traegt bereits die Rail den Einstieg.
                    if (!searching && !hideSettingsAction) SettingsAction(onClick = onOpenSettings)
                }

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
                        modifier = if (inSidePanel) {
                            Modifier
                        } else {
                            Modifier
                                .heightIn(max = searchMaxHeight)
                                .verticalScroll(rememberScrollState())
                        },
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onPlanRoute,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = PrimaryButtonMinHeight),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = atlas.action,
                                contentColor = atlas.onAction,
                            ),
                            contentPadding = ChipPadding,
                        ) {
                            ChipContent(icon = Icons.Rounded.Route, label = stringResource(R.string.map_explore_plan_route_action))
                        }
                        Button(
                            onClick = onRoundTripHere,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = PrimaryButtonMinHeight),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, atlas.cardBorder),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            contentPadding = ChipPadding,
                        ) {
                            ChipContent(icon = Icons.Rounded.Loop, label = stringResource(R.string.map_explore_build_loop_action))
                        }
                    }
                }
            }
        },
        body = {
            if (!inSidePanel || !searching) {
                ExploreSheetBody(
                    savedRoutes = savedRoutes,
                    recentPlaces = searchHistory,
                    loadRide = loadRide,
                    onSelectRoute = onSelectRoute,
                    onSelectPlace = onSelectPlace,
                    modifier = if (inSidePanel) {
                        Modifier
                    } else {
                        Modifier
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    },
                )
            }
        },
    )
}

@Composable
private fun ExploreSheetBody(
    savedRoutes: List<RideSummary>,
    recentPlaces: List<Place>,
    loadRide: suspend (String) -> Ride?,
    onSelectRoute: (RideSummary) -> Unit,
    onSelectPlace: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = LocalAppFormats.current
    val language = LocalAppLanguage.current
    Column(modifier = modifier.padding(start = CardPadding, end = CardPadding, bottom = 12.dp)) {
        LongPressHintLine()
        if (savedRoutes.isNotEmpty()) {
            SectionLabel(stringResource(R.string.map_explore_saved_routes_label))
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                savedRoutes.take(MAX_ROWS).forEach { ride ->
                    val stats = ride.stats
                    val durationMinutes = stats.durationS
                        ?.takeIf { it > 0 }
                        ?.let { (it / 60.0).roundToInt().coerceAtLeast(1) }
                    val durationText = if (durationMinutes != null) {
                        restzeitDauer(durationMinutes).asString()
                    } else {
                        formatRouteDurationApprox(estimatedDurationMinutes(stats.distanceKm)).asString()
                    }
                    RouteCard(
                        title = ride.name,
                        subtitle = plannedRouteMeta(localOfEpochMs(ride.createdAt), stats, language).asString(),
                        distanceText = stringResource(R.string.map_route_distance_value, formats.km(stats.distanceKm)),
                        ascentText = stringResource(R.string.map_route_ascent_value, stats.ascentM.roundToInt()),
                        durationText = durationText,
                        media = {
                            SavedRoutePreview(
                                ride = ride,
                                loadRide = loadRide,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                        onClick = { onSelectRoute(ride) },
                    )
                }
            }
        }
        if (recentPlaces.isNotEmpty()) {
            SectionLabel(stringResource(R.string.map_explore_recent_label))
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
    }
}

/**
 * Die leise, dauerhafte Erklaerung, dass ein Tipp auf die Karte einen Punkt
 * setzt (Massnahme U6, siehe `LongPressHint.kt`): Die Geste hat keinen Knopf,
 * und die einmalige Snackbar ist nach dem ersten Mal weg. Hier, wo man ohnehin nach dem „Wohin?" sucht,
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
            text = stringResource(R.string.map_long_press_hint_line),
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
