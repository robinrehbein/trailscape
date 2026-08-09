package de.trailscape.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.app.ui.theme.OverlayGap
import de.trailscape.core.Ride
import de.trailscape.core.TrackPoint
import de.trailscape.core.formatDuration
import kotlin.math.roundToInt

/**
 * Die Bedienflaechen, die auf der Karte liegen: Live-Leiste der Aufzeichnung,
 * Statistik-Karte der ausgewaehlten Tour, Navigationsleiste, Downloadanzeige
 * und die kleinen Knoepfe am oberen Rand.
 *
 * Alle Flaechen kommen aus `MaterialTheme` (heller/dunkler Modus), nur die drei
 * Kartenfarben sind fest (siehe `MapColors.kt`).
 *
 * Bewusst anders als das Flutter-Original: Dort war jedes Panel in
 * `AnimatedSwitcher`/`AnimatedContainer` verpackt und jeder Zahlenwechsel
 * animiert. Hier bleibt es bei einfachen, ruhigen Karten — Compose animiert
 * Sichtbarkeit ueber `AnimatedVisibility` im Screen, und eine Kennzahl, die
 * sich im Sekundentakt hereinschiebt, ist auf dem Rad eher unruhig als schoen.
 */

/** Ein grosser Wert mit Beschriftung (`_Metric` im Original). */
@Composable
internal fun Metric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (big) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Live-Leiste waehrend der Aufzeichnung.
 *
 * Die Werte kommen unveraendert aus dem
 * [de.trailscape.app.record.RecordingRepository]; nur die Hoehenmeter rechnet
 * der Screen selbst aus den bisherigen Punkten.
 */
@Composable
internal fun LiveRecordingCard(
    speedKmh: Double?,
    distanceKm: Double,
    elapsedS: Int,
    ascentM: Double,
    pointCount: Int,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(
            modifier = Modifier.padding(
                horizontal = CardPadding,
                vertical = OverlayCardPaddingVertical,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordDot(color = RecordRed, size = 12.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (paused) "Pausiert" else "Aufzeichnung läuft",
                    style = MaterialTheme.typography.labelMedium,
                    color = RecordRed,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$pointCount Punkte",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Metric(
                    modifier = Modifier.weight(1f),
                    big = true,
                    value = speedKmh?.let { formatOneDecimalDe(it) } ?: "–",
                    label = "km/h",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    big = true,
                    value = formatKmDe(distanceKm),
                    label = "km",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    big = true,
                    value = formatDuration(elapsedS),
                    label = "Zeit",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    big = true,
                    value = "${ascentM.roundToInt()}",
                    label = "Hm ↑",
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = onTogglePause, modifier = Modifier.weight(1f)) {
                    if (paused) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    } else {
                        PauseGlyph(color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (paused) "Weiter" else "Pause")
                }
                Spacer(Modifier.width(OverlayGap))
                DangerButton(
                    text = "Beenden",
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                ) {
                    StopGlyph(color = Color.White)
                }
            }
        }
    }
}

/**
 * Statistik-Karte der ausgewaehlten Tour, inklusive Hoehenprofil.
 *
 * @param onHoverPoint meldet den im Hoehenprofil abgelesenen Punkt nach oben,
 *   damit der Screen ihn auf der Karte markieren kann.
 */
@Composable
internal fun RideCard(
    ride: Ride,
    navigating: Boolean,
    onNavigate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    onHoverPoint: (TrackPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = ride.stats
    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        // Rechts 8 dp statt 16 dp: der Schliessen-IconButton bringt seinen
        // eigenen Beruehrungsrand mit — dasselbe Zugestaendnis wie in der
        // Tourenkarte des Touren-Tabs.
        Column(
            modifier = Modifier.padding(
                start = CardPadding,
                top = OverlayCardPaddingVertical,
                end = 8.dp,
                bottom = OverlayCardPaddingVertical,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ride.name,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Auswahl aufheben")
                }
            }
            Row(modifier = Modifier.padding(end = 8.dp)) {
                Metric(
                    modifier = Modifier.weight(1f),
                    value = formatKmDe(stats.distanceKm),
                    label = "km",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    value = formatDuration(stats.durationS),
                    label = "Dauer",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    value = stats.avgSpeedKmh?.let { formatOneDecimalDe(it) } ?: "–",
                    label = "Ø km/h",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    value = "${stats.ascentM.roundToInt()}",
                    label = "Hm ↑",
                )
            }
            Spacer(Modifier.height(8.dp))
            ElevationProfile(
                points = ride.points,
                modifier = Modifier.padding(end = 8.dp),
                lineColor = GravelGreen,
                onHover = onHoverPoint,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(
                    text = if (navigating) "Navigation läuft" else "Navigieren",
                    onClick = onNavigate,
                    enabled = !navigating,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Als GPX teilen")
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Tour löschen", tint = RecordRed)
                }
            }
        }
    }
}

/** Leiste waehrend der Navigation (`_NavBar` im Original). */
@Composable
internal fun NavigationCard(
    label: String,
    remainingKm: Double,
    doneKm: Double?,
    offRoute: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Row(
            modifier = Modifier.padding(
                start = CardPadding,
                top = OverlayCardPaddingVertical,
                end = 8.dp,
                bottom = OverlayCardPaddingVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${formatKmDe(remainingKm)} km übrig",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = GravelGreen,
                )
                Text(
                    text = if (doneKm == null) {
                        label
                    } else {
                        "$label · ${formatKmDe(doneKm)} km geschafft"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (offRoute) {
                    Text(
                        text = "⚠️ Abseits der Route",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = RecordRed,
                    )
                }
            }
            TextButton(onClick = onStop) { Text("Beenden") }
        }
    }
}

/** Fortschritt des Offline-Downloads (`_DownloadProgress` im Original). */
@Composable
internal fun DownloadProgressCard(
    done: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(
            modifier = Modifier.padding(
                horizontal = CardPadding,
                vertical = OverlayCardPaddingVertical,
            ),
        ) {
            Text(
                text = "Lade Kacheln … $done/$total",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = GravelGreen,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = GravelGreen,
                )
            }
        }
    }
}

// --------------------------------------------------------------- Bedienteile

/** Pillenfoermiger Knopf mit Text (oben links: „Route planen"). */
@Composable
internal fun MapPillButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (active) activeColor else MaterialTheme.colorScheme.surface
    val foreground = if (active) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = background,
        contentColor = foreground,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = label, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Runder Knopf mit Symbol (Suche, Kartenstil, Offline). */
@Composable
internal fun MapCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        enabled = enabled,
        shape = CircleShape,
        color = if (active) RouteBlue else MaterialTheme.colorScheme.surface,
        contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Der grosse Aufnahmeknopf. Zeichnet Punkt bzw. Quadrat selbst — die
 * schlanke `material-icons-core`-Auswahl im Projekt kennt weder
 * `fiber_manual_record` noch `stop` (siehe `app/build.gradle.kts`: das
 * vollstaendige Icon-Paket bringt mehrere tausend Vektoren mit).
 */
@Composable
internal fun RecordButton(
    recording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (recording) 8.dp else 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (recording) {
                StopGlyph(color = RecordRed, size = 18.dp)
            } else {
                RecordDot(color = GravelGreen, size = 22.dp)
            }
        }
    }
}

/** Runder Knopf „Meine Position". */
@Composable
internal fun LocateButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = GravelGreen,
        contentColor = Color.White,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CrosshairGlyph(color = Color.White)
        }
    }
}

@Composable
private fun RecordDot(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawCircle(color = color)
    }
}

@Composable
private fun StopGlyph(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawRect(color = color, size = this.size)
    }
}

@Composable
private fun PauseGlyph(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val barWidth = this.size.width / 3f
        drawRect(color = color, size = Size(barWidth, this.size.height))
        drawRect(
            color = color,
            topLeft = Offset(this.size.width - barWidth, 0f),
            size = Size(barWidth, this.size.height),
        )
    }
}

/** Fadenkreuz fuer „Meine Position" — `Icons.Filled.MyLocation` fehlt im Kernpaket. */
@Composable
private fun CrosshairGlyph(color: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension / 4f
        drawCircle(color = color, radius = radius, center = center, style = CrosshairStroke)
        drawCircle(color = color, radius = radius / 3f, center = center)
        val arm = this.size.minDimension / 2f
        drawLine(color, Offset(center.x, 0f), Offset(center.x, center.y - radius), strokeWidth = 4f)
        drawLine(color, Offset(center.x, center.y + radius), Offset(center.x, arm * 2), strokeWidth = 4f)
        drawLine(color, Offset(0f, center.y), Offset(center.x - radius, center.y), strokeWidth = 4f)
        drawLine(color, Offset(center.x + radius, center.y), Offset(arm * 2, center.y), strokeWidth = 4f)
    }
}

private val CrosshairStroke = Stroke(width = 4f)

/** Ausgefuellter Knopf in Gravel-Gruen. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = if (enabled) GravelGreen else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Ausgefuellter Knopf in Warnrot mit optionalem Symbol davor. */
@Composable
internal fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = RecordRed,
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

