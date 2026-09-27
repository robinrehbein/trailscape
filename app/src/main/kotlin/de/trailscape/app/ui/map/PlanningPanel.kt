package de.trailscape.app.ui.map

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.PlannedRoute
import de.trailscape.core.RouteProfile
import de.trailscape.core.RoutingSource
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDistanceKm
import de.trailscape.core.routeProfileLabel
import de.trailscape.core.unpavedLabel
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
 * Die Routenplanung als Kartenblatt — „Route hierher", „+ Als Wegpunkt" und
 * langes Druecken landen hier.
 *
 * ## Aufbau nach der Fuehrung „Klartext"
 * Das Blatt folgt denselben Regeln wie „Runde ab hier"
 * (`RoundTripSetupSheet.kt`) und den Zustaenden „Route steht" / „Route
 * anpassen" in `docs/design/prototyp-klartext.html`:
 *
 *  * **Kopf** wie jedes Aufgaben-Blatt: Titel, eine graue Zeile darunter,
 *    rechts ⋮ (Teilen, alles entfernen) und ✕. Der Titel sagt, was hier
 *    entsteht („Route planen", sobald sie steht „Start → Ziel"), die Zeile
 *    darunter den Stand (km, Hm, Herkunft oder was noch fehlt).
 *  * **Nummerierte Punkte** statt Buchstabe plus Farbpunkt, darunter
 *    „Punkt hinzufügen" als ruhige Listenzeile statt eines gestrichelten
 *    Suchfelds, „Mein Standort als Start" als Zeile daneben statt als
 *    Textknopf.
 *  * **„Zurück zum Start" als Schalter** wie „Neue Gegenden bevorzugen" —
 *    vorher ein Segment „Einfach | Zurück zum Start" in der Akzentfarbe, das
 *    lauter war als die eigentliche Hauptaktion.
 *  * **Untergrund als dieselben drei Segmente** wie in „Runde ab hier"
 *    (Gemischt / Asphalt / Schotter). Vorher stand das BRouter-Profil als
 *    abgeschnittener gruener Textknopf da und doppelte die Kopfzeile.
 *  * **Genau eine Hauptaktion**: „Losfahren", daneben „Speichern". Beide
 *    erscheinen erst, wenn eine Route steht — vorher standen sie ausgegraut
 *    da, zusammen mit vier Textknoepfen („Letzten entfernen" doppelte das ✕
 *    jeder Zeile, „Leeren" und „Teilen" wohnen jetzt im ⋮).
 *  * Die Distanz-Chips „Runde ab hier" im leeren Zustand entfallen: Die Runde
 *    hat ihr eigenes Blatt (eine Funktion, ein Ort); das ✕ fuehrt zurueck zum
 *    „Wohin?"-Blatt, wo sie steht.
 *
 * ## Der Griff bleibt die innere Stufe
 * Der Griff klappt zwischen vollem Inhalt, halber Hoehe und blossem Kopf um.
 * Wegpunkte werden auf der **Karte** gesetzt (langes Druecken), und dafuer
 * muss sich das Blatt wegraeumen lassen, ohne die Planung zu verlieren
 * (`onMapLongPress` in `MapScreen.kt`). Der Kopf traegt deshalb allein alles,
 * was man eingeklappt wissen muss.
 */
@Composable
internal fun PlanningSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    /**
     * Mittelstufe (nur zusammen mit [expanded]): halb aufgezogen zeigt das
     * Blatt die ersten Punkte, die Karte behaelt die obere Haelfte — die
     * Stufe zum Wegpunktsetzen.
     */
    half: Boolean,
    onHalfChange: (Boolean) -> Unit,
    profile: RouteProfile,
    onProfileChange: (RouteProfile) -> Unit,
    /**
     * „Zurück zum Start": `true` heisst, die Route kehrt vom letzten Wegpunkt
     * zum **ersten** zurueck. Gerechnet wird das im Karten-Screen
     * (`MapScreen.kt`, `routingWaypoints`), weshalb [waypoints] hier ohne den
     * zurueckfuehrenden Punkt ankommt.
     *
     * Nicht zu verwechseln mit dem Rundkurs-**Generator** („Runde ab hier"),
     * der eine fertige Runde ohne Wegpunkte vorschlaegt. Bei einer
     * generierten Runde ([generated]) steht der Schalter deshalb gar nicht da.
     */
    roundTrip: Boolean,
    onRoundTripChange: (Boolean) -> Unit,
    waypoints: List<Waypoint>,
    route: PlannedRoute?,
    busy: Boolean,
    error: String?,
    /**
     * Obergrenze des aufgeklappten Koerpers — aus dem Platz gerechnet, den der
     * Stapel dem Blatt lassen kann (`overlaySheetBudget` in `MapScreen.kt`).
     */
    maxHeight: Dp,
    /**
     * Rueckmeldung waehrend der Berechnung (Etappen oder Rechnen auf dem
     * Geraet, siehe `planProgressText` in `MapScreen.kt`); `null`, wenn es
     * nichts zu sagen gibt.
     */
    progress: String? = null,
    /** Ob [route] aus dem Rundkurs-Generator stammt — dann gibt es keine Wegpunkte. */
    generated: Boolean = false,
    /**
     * Woher [route] stammt — auf dem Geraet oder ueber den Routing-Server
     * (siehe `OfflineFirstRouting.kt`). Ein Wort in der Kopfzeile macht
     * ehrlich, warum eine lange Route am oeffentlichen Server scheitern kann.
     */
    source: RoutingSource? = null,
    /** Ob gerade auf einen GPS-Fix gewartet wird (bis zu zehn Sekunden). */
    locating: Boolean = false,
    onUseMyPosition: () -> Unit,
    /** Entfernt den Wegpunkt am gegebenen Index — das ✕ einer Zeile. */
    onRemoveWaypoint: (Int) -> Unit,
    /**
     * Oeffnet die Ortssuche im Ortswaehler-Modus (siehe `openPlaceSearch` in
     * `MapScreen.kt`) und haengt den gewaehlten Ort als Wegpunkt an.
     */
    onAddWaypointViaSearch: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onNavigate: () -> Unit,
    onHoverPoint: (TrackPoint?) -> Unit,
    /**
     * Beendet die Planung — das ✕ im Kopf. Der Aufrufer (`MapScreen.kt`)
     * faehrt damit zurueck auf „Wohin?" und bietet die Planung per Snackbar
     * wieder an.
     */
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val hasRoute = route != null && route.points.size >= 2

    SwipeableSheet(
        bottomInset = bottomInset,
        stop = when {
            !expanded -> SheetStop.Peek
            half -> SheetStop.Half
            else -> SheetStop.Full
        },
        onStopChange = { stop ->
            onHalfChange(stop == SheetStop.Half)
            onExpandedChange(stop != SheetStop.Peek)
        },
        halfStop = true,
        modifier = modifier,
        peek = {
            PlanningHeader(
                title = planningTitle(waypoints, route, generated, roundTrip).asString(),
                subtitle = planningSubtitle(
                    waypoints = waypoints,
                    route = route,
                    busy = busy,
                    progress = progress,
                    generated = generated,
                    source = source,
                    locating = locating,
                    failed = error != null,
                    language = LocalAppLanguage.current,
                ).asString(),
                isError = error != null && !busy,
                working = busy || locating,
                canShare = route != null,
                canClear = waypoints.isNotEmpty() || route != null,
                onShare = onShare,
                onClear = onClear,
                onClose = onClose,
            )
        },
        body = {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = CardPadding, end = CardPadding, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hasRoute) {
                    Column {
                        ElevationProfile(
                            points = route!!.points,
                            lineColor = MaterialTheme.colorScheme.primary,
                            onHover = onHoverPoint,
                        )
                        // Der Schotteranteil steht direkt unter dem Profil —
                        // die zweite Frage an eine Route („wie faehrt sie
                        // sich?"). Ohne verlaessliche Belagsdaten entfaellt er.
                        unpavedLabel(route, LocalCoreTexts.current)?.let { surface ->
                            Text(
                                text = surface,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (generated) {
                    Text(
                        text = stringResource(R.string.map_planning_generated_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    WaypointList(
                        waypoints = waypoints,
                        onRemove = onRemoveWaypoint,
                        onAddViaSearch = onAddWaypointViaSearch,
                        // Der Standort-Wegpunkt traegt den Namen in der Sprache,
                        // in der er gesetzt wurde (siehe `map_planning_my_position_name`).
                        showUseMyPosition = stringResource(R.string.map_planning_my_position_name)
                            .let { myPosition -> waypoints.none { it.name == myPosition } },
                        locating = locating,
                        onUseMyPosition = onUseMyPosition,
                    )

                    SwitchRow(
                        title = stringResource(R.string.map_planning_round_trip_title),
                        subtitle = stringResource(R.string.map_planning_round_trip_body),
                        checked = roundTrip,
                        onCheckedChange = onRoundTripChange,
                    )

                    SurfaceChoice(profile = profile, onProfileChange = onProfileChange)
                }

                if (error != null && !busy) {
                    NoticeBox(
                        icon = Icons.Filled.Warning,
                        color = LocalSignalColors.current.danger,
                        text = error,
                    )
                    // Der Fehler nennt den Server, kennt aber den Ausweg
                    // nicht: Trailscape rechnet Routen auch ohne Netz, sobald
                    // die Routing-Karten der Gegend auf dem Geraet liegen
                    // (den Download bietet `AppViewModel.offerMissingSegments`
                    // direkt an).
                    NoticeBox(
                        icon = Icons.Filled.Info,
                        color = LocalSignalColors.current.caution,
                        text = stringResource(R.string.map_planning_offline_hint),
                    )
                }

                // Eine Hauptaktion, und erst wenn es etwas zu tun gibt: Vorher
                // standen „Route speichern" und „Losfahren" ausgegraut da und
                // nahmen dem Blatt ein Viertel seiner Hoehe.
                if (hasRoute) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NeutralButton(
                            onClick = onSave,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.map_planning_save_action), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        PrimaryButton(
                            text = stringResource(R.string.map_planning_start_action),
                            onClick = onNavigate,
                            modifier = Modifier.weight(1.4f),
                            leading = {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * Der Kopf des Blatts — dieselbe Anordnung wie „Runde ab hier" und die
 * Ortskarte: Titel und graue Zeile links, Knoepfe rechts. Er bleibt auch
 * eingeklappt stehen und traegt deshalb den ganzen Stand der Planung.
 */
@Composable
private fun PlanningHeader(
    title: String,
    subtitle: String,
    isError: Boolean,
    working: Boolean,
    canShare: Boolean,
    canClear: Boolean,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = CardPadding, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (working) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (canShare || canClear) {
            PlanningMenu(
                canShare = canShare,
                canClear = canClear,
                onShare = onShare,
                onClear = onClear,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.map_planning_close_cd))
        }
    }
}

/**
 * „Weitere Aktionen" — was man selten braucht, aber finden muss. Wie das ⋮
 * der Route-Karte im Prototyp; „Alles entfernen" bleibt per Snackbar
 * rueckholbar (siehe `onClear` in `MapScreen.kt`).
 */
@Composable
private fun PlanningMenu(
    canShare: Boolean,
    canClear: Boolean,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.map_planning_more_cd))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_planning_share_action)) },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                enabled = canShare,
                onClick = {
                    open = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_planning_clear_action)) },
                leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                enabled = canClear,
                onClick = {
                    open = false
                    onClear()
                },
            )
        }
    }
}

/**
 * Die Punkte der Planung als nummerierte Liste (Prototyp „Route anpassen"):
 * je Zeile ein Kreis mit der Nummer in der Farbe des Kartenmarkers (gruen =
 * Start, blau = dazwischen, rot = Ziel, siehe `buildMapMarkers` in
 * `MapScreen.kt`), der Name und ein ✕ zum Entfernen genau dieses Punkts.
 *
 * Darunter die beiden Wege, einen Punkt dazuzunehmen, als ruhige Zeilen mit
 * derselben Einrueckung: „Punkt hinzufügen" (Suche, oder lange drücken) und
 * „Mein Standort als Start", solange der Standort nicht schon Start ist.
 */
@Composable
private fun WaypointList(
    waypoints: List<Waypoint>,
    onRemove: (Int) -> Unit,
    onAddViaSearch: () -> Unit,
    showUseMyPosition: Boolean,
    locating: Boolean,
    onUseMyPosition: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        waypoints.forEachIndexed { index, waypoint ->
            val label = waypoint.name ?: stringResource(R.string.map_planning_point_label, index + 1)
            ListRow(
                badge = {
                    NumberBadge(
                        number = index + 1,
                        color = waypointColor(index, waypoints.lastIndex),
                    )
                },
                title = label,
                trailing = {
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.map_planning_remove_point_cd, label),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        ListRow(
            badge = { IconBadge(Icons.Filled.Add) },
            title = if (waypoints.isEmpty()) {
                stringResource(R.string.map_planning_add_start_action)
            } else {
                stringResource(R.string.map_planning_add_point_action)
            },
            subtitle = stringResource(R.string.map_planning_add_point_hint),
            muted = true,
            onClick = onAddViaSearch,
        )
        if (showUseMyPosition) {
            ListRow(
                badge = { IconBadge(Icons.Filled.MyLocation) },
                title = if (locating) {
                    stringResource(R.string.map_planning_locating_status)
                } else {
                    stringResource(R.string.map_planning_use_position_action)
                },
                muted = true,
                onClick = if (locating) null else onUseMyPosition,
            )
        }
    }
}

/** Grün am Start, Rot am Ziel, Blau dazwischen — wie die Kartenmarker. */
private fun waypointColor(index: Int, lastIndex: Int): Color = when {
    index == 0 -> GravelGreen
    index == lastIndex -> RecordRed
    else -> RouteBlue
}

/** Eine Zeile der Punktliste — mindestens 48 dp fuer den Daumen. */
@Composable
private fun ListRow(
    badge: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    muted: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badge()
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (muted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Der Kreis mit der Nummer eines Punkts. */
@Composable
private fun NumberBadge(number: Int, color: Color) {
    Box(
        modifier = Modifier
            .size(BadgeSize)
            .background(color = color, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$number",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontSize = if (number < 10) 13.sp else 11.sp,
        )
    }
}

/** Derselbe Kreis, hell und mit Symbol — fuer „Punkt hinzufügen" und „Mein Standort". */
@Composable
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(BadgeSize)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

private val BadgeSize = 26.dp

/** Ein Schalter mit Titel und Erklaerzeile — wie „Neue Gegenden bevorzugen". */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Untergrund — dieselben drei Segmente wie in „Runde ab hier"
 * (`RoundTripSetupSheet.kt`, [surfaceFor]). Die beiden Sonderprofile
 * „Radwege bevorzugt" und „Kürzeste Route" gibt es nur hier, beim Planen von
 * Hand; sie stehen deshalb in einem kleinen Menue darunter. Ist eines davon
 * gewaehlt, ist keins der drei Segmente markiert und die Zeile nennt es.
 */
@Composable
private fun SurfaceChoice(
    profile: RouteProfile,
    onProfileChange: (RouteProfile) -> Unit,
) {
    val coreTexts = LocalCoreTexts.current
    val surfaces = listOf(
        RouteProfile.GRAVEL to stringResource(R.string.map_planning_surface_mixed),
        RouteProfile.ASPHALT to stringResource(R.string.map_planning_surface_paved),
        RouteProfile.SCHOTTER to stringResource(R.string.map_planning_surface_gravel),
    )
    val surfaceCd = stringResource(R.string.map_planning_surface_cd)
    val profileCd = if (profile == RouteProfile.RADWEGE || profile == RouteProfile.KUERZESTER) {
        stringResource(R.string.map_planning_profile_cd, routeProfileLabel(profile, coreTexts))
    } else {
        stringResource(R.string.map_planning_more_profiles_cd)
    }
    val special = profile == RouteProfile.RADWEGE || profile == RouteProfile.KUERZESTER
    var menuOpen by remember { mutableStateOf(false) }

    Column {
        PillSegments(
            options = surfaces.map { it.second },
            selectedIndex = if (special) -1 else surfaces.indexOfFirst { it.first == profile },
            onSelect = { onProfileChange(surfaces[it].first) },
            modifier = Modifier.semantics { contentDescription = surfaceCd },
        )
        Box {
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Button) { menuOpen = true }
                    .padding(horizontal = 4.dp)
                    .clearAndSetSemantics { contentDescription = profileCd },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (special) {
                        routeProfileLabel(profile, coreTexts)
                    } else {
                        stringResource(R.string.map_planning_more_profiles_action)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (special) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf(RouteProfile.RADWEGE, RouteProfile.KUERZESTER).forEach { value ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(routeProfileLabel(value, coreTexts))
                                routeProfileHint(value)?.let {
                                    Text(
                                        text = stringResource(it),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onProfileChange(value)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Der Titel im Kopf: was hier entsteht. Sobald eine Route steht, ihr Name
 * („Mein Standort → Herkules", „Rundweg ab …") — vorher „Route planen".
 */
private fun planningTitle(
    waypoints: List<Waypoint>,
    route: PlannedRoute?,
    generated: Boolean,
    roundTrip: Boolean,
): UiText = when {
    route != null && generated -> UiText.Res(R.string.map_planning_generated_title)
    route != null -> planningRouteLabel(waypoints, roundTrip)
    else -> UiText.Res(R.string.map_planning_title)
}

/**
 * Die graue Zeile im Kopf — der Stand der Planung. Sie lebt nur hier; der
 * Koerper wiederholt sie nicht.
 *
 * Steht eine selbst geplante Route, haengt [routeSourceSuffix] „ · Gerät"
 * bzw. „ · Server" an.
 */
private fun planningSubtitle(
    waypoints: List<Waypoint>,
    route: PlannedRoute?,
    busy: Boolean,
    progress: String?,
    generated: Boolean,
    source: RoutingSource?,
    locating: Boolean,
    failed: Boolean,
    language: AppLanguage,
): UiText = when {
    locating -> UiText.Res(R.string.map_planning_locating_subtitle)
    busy -> progress?.let(UiText::Plain) ?: UiText.Res(R.string.map_planning_calculating_status)
    failed -> UiText.Res(R.string.map_planning_failed_status)
    route != null -> {
        val km = formatDistanceKm(route.distanceKm, language)
        val ascent = route.ascentM.roundToInt()
        val source = if (generated) null else routeSourceLabel(source)
        if (source == null) {
            UiText.Res(R.string.map_planning_route_summary, listOf(km, ascent))
        } else {
            UiText.Res(R.string.map_planning_route_summary_source, listOf(km, ascent, source))
        }
    }

    waypoints.size == 1 -> UiText.Res(R.string.map_planning_need_destination_status)
    waypoints.size > 1 -> UiText.Res(R.string.map_planning_calculating_status)
    else -> UiText.Res(R.string.map_planning_choose_status)
}

/**
 * „Gerät" bzw. „Server" — `null`, solange [source] `null` ist (keine
 * Route, oder eine vom Generator).
 */
private fun routeSourceLabel(source: RoutingSource?): UiText? = when (source) {
    RoutingSource.OFFLINE -> UiText.Res(R.string.map_planning_source_device)
    RoutingSource.SERVER -> UiText.Res(R.string.map_planning_source_server)
    null -> null
}

/**
 * Der Titel, sobald eine Route steht: „Mein Standort → Herkules" statt
 * „3 Wegpunkte", sofern Start oder Ziel einen Namen tragen (Suchtreffer oder
 * eigene Position, siehe `Waypoint.name`) — sonst bleibt es bei der Anzahl.
 *
 * Bei „Zurück zum Start" fuehrt die Route zum Start zurueck — „Start → Ziel"
 * behauptete dann das Falsche; der Titel sagt stattdessen „Rundweg ab …".
 */
private fun planningRouteLabel(waypoints: List<Waypoint>, roundTrip: Boolean): UiText {
    val start = waypoints.firstOrNull()
    if (roundTrip) {
        return start?.name?.let { UiText.Res(R.string.map_planning_loop_from, listOf(it)) }
            ?: UiText.Plural(R.plurals.map_planning_loop_points_count, waypoints.size)
    }
    val end = waypoints.lastOrNull()
    if (start?.name == null && end?.name == null) {
        return UiText.Plural(R.plurals.map_planning_points_count, waypoints.size)
    }
    val startLabel: UiText = start?.name?.let(UiText::Plain)
        ?: UiText.Res(R.string.map_planning_point_label, listOf(1))
    val endLabel: UiText = end?.name?.let(UiText::Plain)
        ?: UiText.Res(R.string.map_planning_point_label, listOf(waypoints.size))
    return UiText.Res(R.string.map_planning_from_to, listOf(startLabel, endLabel))
}

/**
 * Was ein Sonderprofil tut — als zweite Zeile im Menue „Weitere Profile".
 */
@StringRes
private fun routeProfileHint(profile: RouteProfile): Int? = when (profile) {
    RouteProfile.GRAVEL -> R.string.map_planning_profile_hint_gravel
    RouteProfile.SCHOTTER -> R.string.map_planning_profile_hint_schotter
    RouteProfile.ASPHALT -> R.string.map_planning_profile_hint_asphalt
    RouteProfile.RADWEGE -> R.string.map_planning_profile_hint_radwege
    RouteProfile.KUERZESTER -> R.string.map_planning_profile_hint_kuerzester
}

/*
 * Der Name des Wegpunkts, den „Mein Standort als Start" setzt, steht als
 * `map_planning_my_position_name` in `strings_map.xml` — daran erkennt die
 * Liste, dass der Standort schon Start ist, und blendet die Zeile aus. Er
 * wird in der Sprache gespeichert, in der der Punkt gesetzt wurde.
 */

/** Wie viele Suchtreffer angezeigt werden (Dart: `results.take(5)`). */
internal const val MAX_SEARCH_RESULTS: Int = 5
