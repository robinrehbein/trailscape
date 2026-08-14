package de.trailscape.app.ui.map

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.core.GeoResult
import de.trailscape.core.PlannedRoute
import de.trailscape.core.RouteProfile
import de.trailscape.core.TrackPoint
import de.trailscape.core.maxRouteTargetKm
import de.trailscape.core.minRouteTargetKm
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
    /** Ob gerade eine Markierung eines Suchtreffers auf der Karte liegt. */
    hasMarker: Boolean,
    onSearchNow: () -> Unit,
    onSelect: (GeoResult) -> Unit,
    /** Nimmt die Markierung des letzten Treffers wieder von der Karte. */
    onClearMarker: () -> Unit,
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

            // Eine gesetzte Markierung blieb bisher bis zum naechsten Treffer
            // auf der Karte liegen — ohne jeden Weg, sie loszuwerden.
            if (hasMarker) {
                TextButton(onClick = onClearMarker) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Markierung entfernen")
                }
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
 * Die Routenplanung als **unteres Blatt mit zwei Stufen**.
 *
 * ## Warum unten, und warum zwei Stufen
 * Vorher war das hier eine Karte im oberen Stapel — zusammen mit Suche,
 * Navigationsleiste und Generator-Panel belegten die Panels auf einem
 * 360×800-dp-Geraet ueber 600 der rund 720 nutzbaren dp. Uebrig blieb ein
 * Streifen Karte von rund 80 dp, und ausgerechnet dort soll die Nutzerin ihre
 * Wegpunkte hintippen. Die runden Knoepfe (Aufnahme, Position) lagen
 * ausserdem **auf** der Planungskarte.
 *
 * Als unteres Blatt loest sich beides auf einmal: Die Knoepfe stapeln sich
 * darueber statt darauf, und eingeklappt bleibt von der Planung nur eine Zeile
 * („Gravel · 3 Wegpunkte · 42,1 km · 380 Hm") — der Rest des Bildschirms ist
 * Karte. Aufgeklappt steht alles da, was zum Planen gebraucht wird. Es ist
 * zugleich die Anordnung, die jeder aus Komoot kennt.
 *
 * Die Stufe steuert der Screen ([expanded]/[onExpandedChange]), damit sie
 * einen Tabwechsel uebersteht und damit er sie selbst schliessen kann, sobald
 * die Nutzerin sichtbar mit der Karte arbeitet.
 */
@Composable
internal fun PlanningSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    profile: RouteProfile,
    onProfileChange: (RouteProfile) -> Unit,
    waypointCount: Int,
    route: PlannedRoute?,
    busy: Boolean,
    error: String?,
    maxHeight: Dp,
    /**
     * Rueckmeldung waehrend der Berechnung — entweder weil die Route in
     * mehrere Etappen zerlegt wurde (siehe `Routing.kt`) oder weil **auf dem
     * Geraet** gerechnet wird, was spuerbar dauert (siehe
     * `planProgressText` in `MapScreen.kt`). `null`, wenn es nichts zu sagen
     * gibt: eine kurze Route ueber den Server ist schneller da als der Text
     * gelesen waere.
     */
    progress: String? = null,
    /**
     * Ob [route] aus dem Rundkurs-Generator stammt (siehe
     * `RouteGenerationPanel.kt`). Dann gibt es keine Wegpunkte, die sich
     * zaehlen liessen — die Zeile nennt stattdessen die Herkunft.
     */
    generated: Boolean = false,
    /**
     * Ob gerade auf einen GPS-Fix gewartet wird. Das dauert bis zu zehn
     * Sekunden und geschah vorher ohne jede Anzeige — der Knopf sah kaputt aus.
     */
    locating: Boolean = false,
    /** Startet die Rundkurs-Suche ueber die gewaehlte Distanz in km. */
    onRoundTrip: (Double) -> Unit,
    onUseMyPosition: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onNavigate: () -> Unit,
    onHoverPoint: (TrackPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profileLabel = routeProfileLabels[profile] ?: "Route"
    val status = planningStatus(
        waypointCount = waypointCount,
        route = route,
        busy = busy,
        progress = progress,
        generated = generated,
        locating = locating,
    )

    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column {
            // Kopfzeile: eingeklappt die ganze Planung in einer Zeile,
            // aufgeklappt der Griff, mit dem sie wieder zugeht. Die ganze
            // Zeile ist die Flaeche — ein Pfeil allein waere ein 24-dp-Ziel
            // am Daumen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .heightIn(min = 48.dp)
                    .padding(start = CardPadding, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profileLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = error ?: status,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (error != null) {
                            RecordRed
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (busy || locating) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.ArrowDropDown
                    } else {
                        Icons.Filled.ArrowDropUp
                    },
                    contentDescription = if (expanded) {
                        "Planung einklappen"
                    } else {
                        "Planung aufklappen"
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .padding(12.dp),
                )
            }

            if (!expanded) return@Column

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = CardPadding,
                        vertical = OverlayCardPaddingVertical,
                    ),
            ) {
                RouteProfileDropdown(
                    profile = profile,
                    onProfileChange = onProfileChange,
                    // Bei einer generierten Runde war das Dropdown ein toter
                    // Knopf: Der Generator rechnet immer mit dem
                    // Gravel-Profil, und eine fertige Runde laesst sich ohne
                    // Wegpunkte auch nicht nachrechnen.
                    enabled = !generated,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (generated) {
                    Text(
                        text = "Vorschläge berechnet Trailscape immer mit dem Gravel-Profil " +
                            "„Schotter & Kieswege“. Das Routenprofil gilt wieder, sobald du " +
                            "selbst Wegpunkte setzt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!generated) {
                    // Bisher verschwand die Anleitung ab dem ersten Wegpunkt —
                    // also genau dann, wenn es etwas zu entfernen gaebe.
                    Text(
                        text = PLAN_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    NoticeBox(
                        icon = Icons.Filled.Warning,
                        color = LocalSignalColors.current.danger,
                        text = error,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Der Fehler nennt den Server, kennt aber den Ausweg
                    // nicht: Trailscape rechnet Routen auch ohne Netz, sobald
                    // die Routing-Karten der Gegend auf dem Geraet liegen.
                    NoticeBox(
                        icon = Icons.Filled.Info,
                        color = LocalSignalColors.current.caution,
                        text = "Ohne Netz rechnet Trailscape auch auf dem Gerät — dafür " +
                            "braucht es die Routing-Karten dieser Gegend. Du lädst sie " +
                            "unter „Mehr“ → „Karten für Offline-Routing“, am besten vor " +
                            "der Fahrt im WLAN.",
                    )
                }

                if (waypointCount == 0 && route == null && !busy) {
                    Spacer(Modifier.height(8.dp))
                    RoundTripEntry(onStart = onRoundTrip)
                }

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
                    TextButton(onClick = onUseMyPosition, enabled = !locating) {
                        Icon(
                            Icons.Filled.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (locating) "Position wird geholt …" else "Position als Start")
                    }
                    TextButton(onClick = onUndo, enabled = waypointCount > 0) {
                        // „Rückgängig" mit einem Kreispfeil („Neu laden") war
                        // doppelt falsch: falsches Symbol und ein Versprechen,
                        // das die Aktion nicht haelt — sie nimmt den zuletzt
                        // gesetzten Wegpunkt weg, mehr nicht.
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Letzten entfernen")
                    }
                    TextButton(onClick = onClear, enabled = waypointCount > 0 || route != null) {
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
}

/**
 * Der Einstieg „Runde ab hier über X km" — der haeufigste Wunsch eines
 * Gravelfahrers und bis hierher von der Karte aus **gar nicht** erreichbar:
 * Das Rundkurs-Panel oeffnete sich ausschliesslich ueber ein Ziel aus dem
 * Heute- oder Trainings-Tab, an einem Ruhetag also gar nicht, und eine eigene
 * Distanz liess sich nirgends eingeben. Die Rechenmaschinerie stand die ganze
 * Zeit bereit (siehe `RouteGenerationController`); es fehlte nur die Tuer.
 *
 * ## Chips **und** freie Eingabe
 * Der Review schlug drei Chips vor (30/50/80 km). Die bleiben — sie sind der
 * kuerzeste Weg (ein Tipp) und decken den Grossteil der Wuensche ab. Sie
 * allein waeren aber zu wenig: Wer zwei Stunden Zeit hat und 22 km/h faehrt,
 * will 45 km und nicht „30 oder 50". Deshalb steht darunter ein Feld fuer die
 * eigene Zahl. Umgekehrt waere ein Feld allein der schlechtere Tausch — fuer
 * die drei haeufigen Faelle Tastatur, Tippen und Bestaetigen statt eines
 * einzigen Tipps.
 *
 * Die Grenzen sind die des Generators in `:core` ([minRouteTargetKm] …
 * [maxRouteTargetKm]); darunter oder darueber saehe die Nutzerin sonst erst
 * nach einer halben Minute Suche einen Hinweis, dass ihre Zahl gar nicht
 * benutzt wurde.
 */
@Composable
private fun RoundTripEntry(
    onStart: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var custom by rememberSaveable { mutableStateOf("") }
    // Die eingegebene Distanz, sofern sie im Bereich des Generators liegt —
    // sonst `null`, und das ist zugleich die Antwort auf „darf gesucht
    // werden?".
    val customKm = custom.toIntOrNull()?.toDouble()
        ?.takeIf { it >= minRouteTargetKm && it <= maxRouteTargetKm }
    val outOfRange = custom.isNotEmpty() && customKm == null

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Runde ab hier",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Trailscape sucht Rundkurse ab deiner Position – ohne Standortfreigabe " +
                "ab der Kartenmitte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ROUND_TRIP_SUGGESTIONS.forEach { km ->
                AssistChip(
                    onClick = { onStart(km) },
                    label = { Text("${km.roundToInt()} km") },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom,
                // Nur Ziffern: Ein Komma oder ein Buchstabe im Feld waere eine
                // Zahl, die niemand berechnen kann — km auf den Kilometer
                // genau reichen fuer eine Zieldistanz voellig.
                onValueChange = { input -> custom = input.filter { it.isDigit() }.take(3) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Eigene Distanz") },
                suffix = { Text("km") },
                isError = outOfRange,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { customKm?.let(onStart) },
                ),
            )
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                text = "Suchen",
                onClick = { customKm?.let(onStart) },
                enabled = customKm != null,
            )
        }
        if (outOfRange) {
            Text(
                text = "Zwischen ${minRouteTargetKm.roundToInt()} und " +
                    "${maxRouteTargetKm.roundToInt()} km.",
                style = MaterialTheme.typography.bodySmall,
                color = RecordRed,
            )
        }
    }
}

/** Die Zustandszeile der Planung — eingeklappt wie aufgeklappt dieselbe. */
private fun planningStatus(
    waypointCount: Int,
    route: PlannedRoute?,
    busy: Boolean,
    progress: String?,
    generated: Boolean,
    locating: Boolean,
): String = when {
    locating -> "Position wird ermittelt …"
    busy && progress != null -> progress
    route != null && generated ->
        "${formatKmDe(route.distanceKm)} km · ${route.ascentM.roundToInt()} Hm ↑ · " +
            "vorgeschlagene Runde"

    route != null ->
        "$waypointCount Wegpunkte · ${formatKmDe(route.distanceKm)} km · " +
            "${route.ascentM.roundToInt()} Hm ↑"

    waypointCount == 1 -> "1 Wegpunkt – setze mindestens 2."
    waypointCount > 1 -> "$waypointCount Wegpunkte – berechne Route …"
    else -> "Noch keine Wegpunkte"
}

@Composable
private fun RouteProfileDropdown(
    profile: RouteProfile,
    onProfileChange: (RouteProfile) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
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
                    text = {
                        Column {
                            Text(label)
                            routeProfileHint(value)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onProfileChange(value)
                    },
                )
            }
        }
    }
}

/**
 * Was ein Profil wirklich tut — als zweite Zeile im Dropdown.
 *
 * Der Anlass ist eine echte Verwechslungsgefahr: „Gravel (gemischt)" routet
 * mit dem oeffentlichen `trekking`-Profil, das **eigentliche** Gravel-Profil
 * haengt an „Schotter & Kieswege" (siehe `brouterProfile` in `:core`). Wer
 * Schotter sucht und „Gravel" waehlt, bekommt also gerade nicht, was er
 * erwartet. Die Namen selbst stehen in `:core` und bleiben unangetastet; hier
 * steht die Erklaerung daneben.
 */
private fun routeProfileHint(profile: RouteProfile): String? = when (profile) {
    RouteProfile.GRAVEL -> "Trekking: Asphalt und feste Wege gemischt"
    RouteProfile.SCHOTTER -> "Das eigentliche Gravel-Profil: bevorzugt unbefestigte Wege"
    RouteProfile.ASPHALT -> "Meidet unbefestigte Wege"
    RouteProfile.RADWEGE -> "Bevorzugt ausgewiesene Radwege"
    RouteProfile.KUERZESTER -> "Kürzeste Strecke, ohne Rücksicht auf den Belag"
}

/** Hinweistext der Planung — woertlich wie `_planHint` in Dart. */
internal const val PLAN_HINT: String =
    "Tippe auf die Karte, um Wegpunkte zu setzen. Tippe auf einen Wegpunkt, " +
        "um ihn zu entfernen."

/** Wie viele Suchtreffer angezeigt werden (Dart: `results.take(5)`). */
internal const val MAX_SEARCH_RESULTS: Int = 5

/**
 * Die drei Distanzen mit einem Tipp. Gewaehlt nach dem, was eine Feierabend-,
 * eine halbe Tages- und eine Tagesrunde auf dem Gravelbike ueblicherweise
 * misst; alles dazwischen und darueber deckt das Feld daneben ab.
 */
private val ROUND_TRIP_SUGGESTIONS = listOf(30.0, 50.0, 80.0)
