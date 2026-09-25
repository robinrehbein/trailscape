package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.RouteProfile
import kotlin.math.roundToInt

/**
 * „Runde ab hier" — das **eine** Blatt, in dem eine Runde bestellt wird
 * (Fuehrung „Klartext", `docs/design/prototyp-klartext.html`).
 *
 * Vorher gab es fuenf Einstiege mit je eigener Oberflaeche: Distanz-Chips und
 * Freitextfeld in der Planung, ein fester 30-km-Knopf auf der Ortskarte, der
 * Heute-Knopf, das Trainings-Symbol und der Bereit-Dialog. Jetzt fuehren die
 * Einstiege von der Karte hierher; Heute und Training schicken ihr Ziel
 * weiterhin direkt an den Generator, weil dort Laenge und Anspruch schon
 * feststehen.
 *
 * Gefragt wird nur, was die Nutzerin wirklich entscheidet:
 *
 *  * **Laenge** — ein Regler von 15 bis 150 km in 5-km-Schritten, die Zahl
 *    gross darueber.
 *  * **Untergrund** — Gemischt / Asphalt / Schotter. Dahinter stehen die
 *    BRouter-Profile [RouteProfile.GRAVEL] (Trekking, gemischt),
 *    [RouteProfile.ASPHALT] und [RouteProfile.SCHOTTER] (echtes Gravel); die
 *    beiden Sonderprofile Radwege/Kuerzeste bleiben der Planung von Hand
 *    vorbehalten und erscheinen hier als „Gemischt".
 *  * **Neue Gegenden bevorzugen** — bevorzugt Runden durch noch nicht
 *    befahrene Kacheln (Squadrats-Idee, siehe `core/.../ExplorerTiles.kt`).
 *
 * @param startLabel Woher die Runde startet („ab deinem Standort" oder der
 *   Name des angetippten Orts).
 */
@Composable
internal fun RoundTripSetupSheet(
    startLabel: String,
    distanceKm: Int,
    onDistanceChange: (Int) -> Unit,
    profile: RouteProfile,
    onProfileChange: (RouteProfile) -> Unit,
    preferNewAreas: Boolean,
    onPreferNewAreasChange: (Boolean) -> Unit,
    onShowSuggestions: () -> Unit,
    onClose: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    SwipeableSheet(
        expanded = false,
        onExpandedChange = {},
        modifier = modifier,
        bottomInset = bottomInset,
        peek = {
            Column(
                modifier = Modifier.padding(start = CardPadding, end = CardPadding, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Runde ab hier", style = MaterialTheme.typography.titleMedium)
                        Text(
                            startLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Schließen")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "$distanceKm",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 44.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "km",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Slider(
                    value = distanceKm.toFloat(),
                    onValueChange = { onDistanceChange(snapDistance(it)) },
                    valueRange = MIN_ROUND_TRIP_KM.toFloat()..MAX_ROUND_TRIP_KM.toFloat(),
                    steps = (MAX_ROUND_TRIP_KM - MIN_ROUND_TRIP_KM) / ROUND_TRIP_STEP_KM - 1,
                    modifier = Modifier.semantics { contentDescription = "Länge der Runde" },
                )

                val surfaces = listOf(
                    RouteProfile.GRAVEL to "Gemischt",
                    RouteProfile.ASPHALT to "Asphalt",
                    RouteProfile.SCHOTTER to "Schotter",
                )
                val selected = surfaceFor(profile)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    surfaces.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = selected == value,
                            onClick = { onProfileChange(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, surfaces.size),
                        ) { Text(label) }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Neue Gegenden bevorzugen", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Führt durch Kacheln, die du noch nicht kennst",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = preferNewAreas, onCheckedChange = onPreferNewAreasChange)
                }

                Button(
                    onClick = onShowSuggestions,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Vorschläge zeigen") }
            }
        },
        body = {},
    )
}

/** Untergrund-Segment zu einem Profil — Sonderprofile zaehlen als „Gemischt". */
internal fun surfaceFor(profile: RouteProfile): RouteProfile = when (profile) {
    RouteProfile.ASPHALT -> RouteProfile.ASPHALT
    RouteProfile.SCHOTTER -> RouteProfile.SCHOTTER
    else -> RouteProfile.GRAVEL
}

/** Rundet einen Reglerwert auf das 5-km-Raster und klemmt ihn auf 15–150 km. */
internal fun snapDistance(value: Float): Int =
    ((value / ROUND_TRIP_STEP_KM).roundToInt() * ROUND_TRIP_STEP_KM)
        .coerceIn(MIN_ROUND_TRIP_KM, MAX_ROUND_TRIP_KM)

internal const val MIN_ROUND_TRIP_KM = 15
internal const val MAX_ROUND_TRIP_KM = 150
internal const val ROUND_TRIP_STEP_KM = 5
internal const val DEFAULT_ROUND_TRIP_KM = 45
