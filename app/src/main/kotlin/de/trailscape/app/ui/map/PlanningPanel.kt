package de.trailscape.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.core.PlannedRoute
import de.trailscape.core.RouteProfile
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.maxRouteTargetKm
import de.trailscape.core.minRouteTargetKm
import de.trailscape.core.routeProfileLabels
import kotlin.math.roundToInt

/**
 * Routenplanung.
 *
 * Die Ortssuche stand hier frueher als eigenes `SearchPanel` (Karte im oberen
 * Panelstapel); sie ist seit dem Umbau auf ein von unten hochfahrendes Blatt
 * umgezogen (siehe `SearchSheet.kt`) und zeigt einen Treffer als Ort-Objekt
 * ueber die Ortskarte (`PlaceCard.kt`) an, statt ihn mit einem Textknopf in
 * der Trefferzeile sofort zu verarbeiten.
 */

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
    waypoints: List<Waypoint>,
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
    /** Entfernt den Wegpunkt am gegebenen Index — das X einer einzelnen Zeile der Liste. */
    onRemoveWaypoint: (Int) -> Unit,
    /**
     * Oeffnet die Ortssuche im Ortswaehler-Modus (siehe `openPlaceSearch` in
     * `MapScreen.kt`) und haengt den gewaehlten Ort als benannten Wegpunkt an
     * — die leere, gestrichelt gerahmte Zeile am Ende der Liste.
     */
    onAddWaypointViaSearch: () -> Unit,
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
        waypoints = waypoints,
        route = route,
        busy = busy,
        progress = progress,
        generated = generated,
        locating = locating,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
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
                        color = if (error != null) {
                            MaterialTheme.colorScheme.error
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
                    // Die generierte Runde hat keine Wegpunkte, die sich
                    // auflisten liessen (siehe [generated] oben) — die Liste
                    // gilt deshalb nur fuer selbst geplante Routen.
                    Spacer(Modifier.height(8.dp))
                    WaypointList(
                        waypoints = waypoints,
                        onRemove = onRemoveWaypoint,
                        onAddViaSearch = onAddWaypointViaSearch,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Was die Liste selbst schon zeigt (Namen, Entfernen ueber
                    // X) muss der Hinweis nicht mehr erklaeren — geblieben ist
                    // nur, was ausschliesslich am Kartentipp haengt.
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

                if (waypoints.isEmpty() && route == null && !busy) {
                    Spacer(Modifier.height(8.dp))
                    RoundTripEntry(onStart = onRoundTrip)
                }

                if (route != null && route.points.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    ElevationProfile(
                        points = route.points,
                        lineColor = MaterialTheme.colorScheme.primary,
                        onHover = onHoverPoint,
                    )
                }

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
                    TextButton(onClick = onUndo, enabled = waypoints.isNotEmpty()) {
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
                    TextButton(onClick = onClear, enabled = waypoints.isNotEmpty() || route != null) {
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
                    // One UI kennt keine Outline-Knoepfe: Die Nebenaktion ist
                    // eine gefuellte helle Flaeche (NeutralButton), nur die
                    // Hauptaktion traegt die Farbe.
                    NeutralButton(
                        onClick = onSave,
                        enabled = route != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Als Tour speichern") }
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
 * Die Wegpunkte der Planung als benannte Liste — das Google-Maps-Muster fuer
 * Wegbeschreibungen: je Zeile ein Buchstabe (A, B, C … die Reihenfolge), ein
 * Farbpunkt (dieselbe Zuordnung wie die Kartenmarker, siehe `buildMapMarkers`
 * in `MapScreen.kt`: gruen = Start, blau = Zwischenziele, rot = Ziel), der
 * Ortsname und ein X zum Entfernen genau dieses einen Wegpunkts.
 *
 * ## Warum kein eigener „+ Zwischenziel"-Knopf
 * Ein zusaetzlicher Knopf unter der Liste haette exakt dasselbe getan wie die
 * leere Zeile an ihrem Ende — Ortssuche oeffnen, Auswahl anhaengen. Zwei
 * Wege zum selben Ergebnis sind keine zwei Moeglichkeiten, nur zweimal dieselbe
 * Frage; die leere Zeile allein deckt den Fall vollstaendig ab.
 *
 * ## Warum kein Drag-Umsortieren
 * Bewusst ausserhalb dieses Schritts (siehe Aufgabenstellung) — eine Reihen-
 * folge laesst sich bis dahin nur ueber Entfernen und erneutes Setzen aendern.
 */
@Composable
private fun WaypointList(
    waypoints: List<Waypoint>,
    onRemove: (Int) -> Unit,
    onAddViaSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        waypoints.forEachIndexed { index, waypoint ->
            WaypointRow(
                index = index,
                label = waypoint.name ?: "Wegpunkt ${index + 1}",
                color = waypointColor(index, waypoints.lastIndex),
                onRemove = { onRemove(index) },
            )
        }
        AddWaypointRow(onClick = onAddViaSearch)
    }
}

/** Grün am Start, Rot am Ziel, Blau dazwischen — wie die Kartenmarker. */
private fun waypointColor(index: Int, lastIndex: Int): Color = when (index) {
    0 -> GravelGreen
    lastIndex -> RecordRed
    else -> RouteBlue
}

/** Eine einzelne Zeile der Wegpunktliste — mindestens 48 dp fuer den Daumen. */
@Composable
private fun WaypointRow(
    index: Int,
    label: String,
    color: Color,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = waypointLetter(index),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = color, shape = CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "$label entfernen")
        }
    }
}

/**
 * Die leere, gestrichelt gerahmte Zeile am Ende der Liste — der Einstieg in
 * die Ortssuche im Ortswaehler-Modus (siehe `openPlaceSearch` in
 * `MapScreen.kt`). Gestrichelt statt durchgezogen, damit sie sich auch ohne
 * Text erkennbar von einer echten Wegpunktzeile abhebt („hier fehlt noch
 * etwas", nicht „hier steht schon etwas").
 */
@Composable
private fun AddWaypointRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .dashedBorder(color = MaterialTheme.colorScheme.outline)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Ort suchen oder Karte antippen",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Buchstabenfolge der Wegpunkte — A, B, C … Z, AA, AB, … wie Tabellenspalten.
 * 26 Buchstaben reichen fuer jede realistische Wegpunktzahl bei weitem; die
 * Fortsetzung ist nur ein Sicherheitsnetz, kein erwarteter Fall.
 */
private fun waypointLetter(index: Int): String {
    var n = index + 1
    val letters = StringBuilder()
    while (n > 0) {
        val remainder = (n - 1) % 26
        letters.insert(0, 'A' + remainder)
        n = (n - 1) / 26
    }
    return letters.toString()
}

/**
 * Gestrichelter Rahmen fuer [AddWaypointRow] — Compose kennt fuer `border()`
 * keine gestrichelte Variante, deshalb hier von Hand ueber `drawBehind` und
 * [PathEffect.dashPathEffect]. [cornerRadius] ist bewusst dieselbe 18-dp-Ecke
 * wie `TrailscapeShapes.extraSmall` (`Shape.kt`, Slot fuer Menues und
 * Textfelder — genau das ist diese Zeile, ein Ortsfeld als Zeile statt als
 * `OutlinedTextField`), nicht aus dieser Form selbst gelesen: Eine
 * `CornerBasedShape` laesst sich ohne bekannte Flaechengroesse nicht generisch
 * in einen Zeichenradius uebersetzen.
 */
private fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp = 18.dp,
    strokeWidth: Dp = 1.dp,
): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
        ),
        cornerRadius = CornerRadius(cornerRadius.toPx()),
    )
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
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Die Zustandszeile der Planung — eingeklappt wie aufgeklappt dieselbe. */
private fun planningStatus(
    waypoints: List<Waypoint>,
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
        "${planningRouteLabel(waypoints)} · ${formatKmDe(route.distanceKm)} km · " +
            "${route.ascentM.roundToInt()} Hm ↑"

    waypoints.size == 1 -> "1 Wegpunkt – setze mindestens 2."
    waypoints.size > 1 -> "${waypoints.size} Wegpunkte – berechne Route …"
    else -> "Noch keine Wegpunkte"
}

/**
 * Der erste Teil der Zustandszeile, sobald eine Route steht: „Mein Standort →
 * Herkules" statt „3 Wegpunkte", sofern Start oder Ziel einen Namen tragen
 * (Suchtreffer oder eigene Position, siehe `Waypoint.name`) — sonst bleibt es
 * bei der reinen Anzahl, denn zwei „Wegpunkt N"-Platzhalter waeren keine
 * Verbesserung gegenueber der Zahl.
 */
private fun planningRouteLabel(waypoints: List<Waypoint>): String {
    val start = waypoints.firstOrNull()
    val end = waypoints.lastOrNull()
    if (start?.name == null && end?.name == null) return "${waypoints.size} Wegpunkte"
    val startLabel = start?.name ?: "Wegpunkt 1"
    val endLabel = end?.name ?: "Wegpunkt ${waypoints.size}"
    return "$startLabel → $endLabel"
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

/**
 * Hinweistext der Planung. Stand frueher woertlich wie `_planHint` in Dart und
 * erklaerte Setzen **und** Entfernen eines Wegpunkts per Kartentipp — seit
 * [WaypointList] zeigt die Liste selbst, wie ein Wegpunkt heisst und wie er
 * (ueber das X) verschwindet, der Hinweis bleibt darum nur fuer das, was
 * ausschliesslich am Kartentipp haengt.
 */
internal const val PLAN_HINT: String =
    "Tippe auf die Karte, um einen Wegpunkt zu setzen oder zu entfernen."

/** Wie viele Suchtreffer angezeigt werden (Dart: `results.take(5)`). */
internal const val MAX_SEARCH_RESULTS: Int = 5

/**
 * Die drei Distanzen mit einem Tipp. Gewaehlt nach dem, was eine Feierabend-,
 * eine halbe Tages- und eine Tagesrunde auf dem Gravelbike ueblicherweise
 * misst; alles dazwischen und darueber deckt das Feld daneben ab.
 */
private val ROUND_TRIP_SUGGESTIONS = listOf(30.0, 50.0, 80.0)
