package de.trailscape.app.ui.map

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trailscape.app.R
import de.trailscape.app.ui.components.PillSegments
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
 *  * **Neu entdecken: Aus / Wege / Gegenden** — eine Wahl statt zweier
 *    Schalter, siehe [DiscoveryMode] und [DiscoveryChoice]. Deutsch ohne
 *    „Neue" vor jedem Segment: „Neue Gegenden" passt auf einem 360-dp-Geraet
 *    nicht in ein Drittel der Breite, und die Ueberschrift sagt es schon.
 *  * **Wind berücksichtigen** — hin gegen den Wind, heim mit Rueckenwind
 *    (`core/.../WindScore.kt`). Die einzige Netzanfrage dieses Blatts
 *    (aktueller Wind bei Open-Meteo, Startpunkt auf etwa 1 km gerundet),
 *    deshalb ab Werk aus und mit dem Hinweis direkt darunter; gemerkt wird
 *    der Schalter im `RouteGenerationController`.
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
    discoveryMode: DiscoveryMode,
    onDiscoveryModeChange: (DiscoveryMode) -> Unit,
    considerWind: Boolean,
    onConsiderWindChange: (Boolean) -> Unit,
    onShowSuggestions: () -> Unit,
    onClose: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    StaticSheet(
        modifier = modifier,
        bottomInset = bottomInset,
    ) {
        Column(
            modifier = Modifier.padding(start = CardPadding, end = CardPadding, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.map_round_trip_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        startLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.map_round_trip_close_cd))
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
            val sliderCd = stringResource(R.string.map_round_trip_length_cd)
            Slider(
                value = distanceKm.toFloat(),
                onValueChange = { onDistanceChange(snapDistance(it)) },
                valueRange = MIN_ROUND_TRIP_KM.toFloat()..MAX_ROUND_TRIP_KM.toFloat(),
                steps = (MAX_ROUND_TRIP_KM - MIN_ROUND_TRIP_KM) / ROUND_TRIP_STEP_KM - 1,
                modifier = Modifier.semantics { contentDescription = sliderCd },
            )

            val surfaces = listOf(
                RouteProfile.GRAVEL to stringResource(R.string.map_round_trip_surface_mixed),
                RouteProfile.ASPHALT to stringResource(R.string.map_round_trip_surface_paved),
                RouteProfile.SCHOTTER to stringResource(R.string.map_round_trip_surface_gravel),
            )
            val selected = surfaces.indexOfFirst { it.first == surfaceFor(profile) }
            PillSegments(
                options = surfaces.map { it.second },
                selectedIndex = selected,
                onSelect = { onProfileChange(surfaces[it].first) },
            )

            DiscoveryChoice(mode = discoveryMode, onModeChange = onDiscoveryModeChange)

            SetupSwitchRow(
                title = stringResource(R.string.map_round_trip_wind_title),
                note = stringResource(R.string.map_round_trip_wind_body),
                checked = considerWind,
                onCheckedChange = onConsiderWindChange,
            )

            Button(
                onClick = onShowSuggestions,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.map_round_trip_show_action)) }
        }
    }
}

/**
 * Schalterzeile des Blatts. Die ganze Zeile ist `toggleable` mit
 * `Role.Switch` (wie `MoreComponents`): TalkBack liest Titel und Hinweis als
 * Beschriftung des Schalters vor — beim Wind-Schalter ist das die
 * Einwilligung, die muss man hoeren koennen — und die Zeile ist die
 * Tippflaeche, nicht nur der 48-dp-Schalter.
 */
@Composable
private fun SetupSwitchRow(
    title: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Was „Neues entdecken" bevorzugt.
 *
 * ## Warum eine Wahl und nicht zwei Schalter
 * Zwei Schalter („Neue Wege", „Neue Gegenden") liessen beide zugleich zu,
 * und dann waere nicht mehr zu erklaeren, was gewinnt: Eine Runde ueber
 * neue Feldwege in der bekannten Kachel oder eine ueber bekannte Strassen
 * in einer neuen Kachel? Die beiden Ziele ziehen oft in verschiedene
 * Richtungen. Als Entweder-oder ist jede Stellung ein klarer Satz, der
 * Hinweis darunter erklaert genau die gewaehlte, und „Aus" ist sichtbar
 * eine eigene Antwort statt zweier ausgeschalteter Schalter.
 *
 * Ab Werk [NEW_AREAS] — so verhielt sich der fruehere Schalter „Neue
 * Gegenden bevorzugen", der ab Werk an war.
 */
internal enum class DiscoveryMode {
    OFF,
    NEW_WAYS,
    NEW_AREAS,
    ;

    /** An `RouteGenerationController.start` als `preferNewAreas`. */
    val preferNewAreas: Boolean get() = this == NEW_AREAS

    /** An `RouteGenerationController.start` als `preferNewWays`. */
    val preferNewWays: Boolean get() = this == NEW_WAYS
}

/** Hinweiszeile unter der Wahl — erklaert genau die gewaehlte Stellung. */
@StringRes
internal fun discoveryNoteRes(mode: DiscoveryMode): Int = when (mode) {
    DiscoveryMode.OFF -> R.string.map_round_trip_discover_off_body
    DiscoveryMode.NEW_WAYS -> R.string.map_round_trip_discover_ways_body
    DiscoveryMode.NEW_AREAS -> R.string.map_round_trip_discover_areas_body
}

/**
 * „Neues entdecken" — Ueberschrift, drei Segmente, ein Hinweis. Die
 * Ueberschrift ist fuer TalkBack eine Ueberschrift (`heading()`), die
 * Segmente sind Optionsfelder in einer `selectableGroup` ([PillSegments]) und
 * tragen die Ueberschrift in ihrer Beschreibung mit („Neu entdecken: Wege") —
 * wer per Wischen direkt auf einem Segment landet, hoert sonst nur „Wege".
 */
@Composable
private fun DiscoveryChoice(mode: DiscoveryMode, onModeChange: (DiscoveryMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.map_round_trip_discover_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        val modes = DiscoveryMode.entries
        PillSegments(
            options = listOf(
                stringResource(R.string.map_round_trip_discover_off),
                stringResource(R.string.map_round_trip_discover_ways),
                stringResource(R.string.map_round_trip_discover_areas),
            ),
            optionDescriptions = modes.map { stringResource(discoveryDescriptionRes(it)) },
            selectedIndex = modes.indexOf(mode),
            onSelect = { onModeChange(modes[it]) },
        )
        Text(
            stringResource(discoveryNoteRes(mode)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** TalkBack-Beschreibung eines Segments — Ueberschrift und Stellung in einem Satz. */
@StringRes
internal fun discoveryDescriptionRes(mode: DiscoveryMode): Int = when (mode) {
    DiscoveryMode.OFF -> R.string.map_round_trip_discover_off_cd
    DiscoveryMode.NEW_WAYS -> R.string.map_round_trip_discover_ways_cd
    DiscoveryMode.NEW_AREAS -> R.string.map_round_trip_discover_areas_cd
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
