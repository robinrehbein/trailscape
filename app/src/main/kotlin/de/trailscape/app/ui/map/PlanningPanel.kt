package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.core.GeoResult
import de.trailscape.core.PlannedRoute
import de.trailscape.core.RouteProfile
import de.trailscape.core.TrackPoint
import de.trailscape.core.routeProfileLabels
import kotlin.math.roundToInt

/**
 * Ortssuche und Routenplanung.
 *
 * ## Bewusst anders als im Flutter-Original
 * Dort steckte das Suchfeld **im** Planungs-Panel und war nur im Planungsmodus
 * erreichbar; ein Treffer wurde immer als Wegpunkt angehaengt. Hier ist die
 * Suche ein eigenes, jederzeit ueber die Lupe erreichbares Feld
 * ([SearchPanel]): Wer nur nachsehen will, wo ein Ort liegt, muss dafuer keine
 * Route planen. Im Planungsmodus verhaelt sich ein Treffer weiterhin wie im
 * Original (Ziel = neuer letzter Wegpunkt).
 *
 * Zusaetzlich sucht das Feld **von selbst** (kurze Wartezeit nach der letzten
 * Eingabe) statt erst auf Knopfdruck — der Knopf bleibt fuer die ungeduldige
 * Variante erhalten.
 */

/** Ortssuche (Nominatim). Zeigt hoechstens fuenf Treffer, wie in Dart. */
@Composable
internal fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    results: List<GeoResult>,
    planning: Boolean,
    onSearchNow: () -> Unit,
    onSelect: (GeoResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Ort, Stadt oder Straße suchen…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearchNow() }),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Suche schließen")
                }
            }

            if (error != null) {
                Text(
                    text = error,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = RecordRed,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            results.take(MAX_SEARCH_RESULTS).forEach { result ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = RouteBlue,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = result.displayName,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onSelect(result) }) {
                        Text(if (planning) "Als Ziel" else "Anzeigen")
                    }
                }
            }
        }
    }
}

/**
 * Panel der Routenplanung: Profil, Zustand der Route, Aktionen und — sobald
 * eine Route berechnet ist — ihr Hoehenprofil.
 */
@Composable
internal fun PlanningCard(
    profile: RouteProfile,
    onProfileChange: (RouteProfile) -> Unit,
    waypointCount: Int,
    route: PlannedRoute?,
    busy: Boolean,
    error: String?,
    maxHeight: Dp,
    /**
     * Fortschrittstext, wenn die Route wegen weit auseinanderliegender
     * Wegpunkte in mehreren Server-Anfragen berechnet wird (siehe
     * `Routing.kt`). `null`, solange es bei einer Anfrage bleibt.
     */
    progress: String? = null,
    /**
     * Ob [route] aus dem Rundkurs-Generator stammt (siehe
     * `RouteGenerationPanel.kt`). Dann gibt es keine Wegpunkte, die sich
     * zaehlen liessen — die Zeile nennt stattdessen die Herkunft.
     */
    generated: Boolean = false,
    onUseMyPosition: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onNavigate: () -> Unit,
    onHoverPoint: (TrackPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = when {
        busy && progress != null -> progress

        route != null && generated ->
            "${formatKmDe(route.distanceKm)} km · ${route.ascentM.roundToInt()} Hm ↑ · " +
                "vorgeschlagene Runde"

        route != null ->
            "${formatKmDe(route.distanceKm)} km · ${route.ascentM.roundToInt()} Hm ↑ · " +
                "$waypointCount Wegpunkte"

        waypointCount == 1 -> "1 Wegpunkt – setze mindestens 2."
        waypointCount > 1 -> "$waypointCount Wegpunkte – berechne Route …"
        else -> PLAN_HINT
    }

    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = CardPadding,
                    vertical = OverlayCardPaddingVertical,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RouteProfileDropdown(
                    profile = profile,
                    onProfileChange = onProfileChange,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = error ?: info,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) RecordRed else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (error != null) FontWeight.SemiBold else FontWeight.Normal,
            )

            if (route != null && route.points.size >= 2) {
                Spacer(Modifier.height(8.dp))
                ElevationProfile(
                    points = route.points,
                    lineColor = RouteBlue,
                    onHover = onHoverPoint,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onUseMyPosition) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Position als Start")
                }
                TextButton(onClick = onUndo, enabled = waypointCount > 0) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Rückgängig")
                }
                TextButton(onClick = onClear, enabled = waypointCount > 0) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Leeren")
                }
                TextButton(onClick = onShare, enabled = route != null) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Teilen")
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = "Als Tour speichern",
                    onClick = onSave,
                    enabled = route != null,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PrimaryButton(
                    text = "Navigieren",
                    onClick = onNavigate,
                    enabled = route != null && route.points.size >= 2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RouteProfileDropdown(
    profile: RouteProfile,
    onProfileChange: (RouteProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = routeProfileLabels[profile] ?: "Routenprofil",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Routenprofil wählen")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            routeProfileLabels.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onProfileChange(value)
                    },
                )
            }
        }
    }
}

/** Hinweistext der Planung — woertlich wie `_planHint` in Dart. */
internal const val PLAN_HINT: String =
    "Tippe auf die Karte, um Wegpunkte zu setzen. Tippe auf einen Wegpunkt, " +
        "um ihn zu entfernen."

/** Wie viele Suchtreffer angezeigt werden (Dart: `results.take(5)`). */
internal const val MAX_SEARCH_RESULTS: Int = 5
