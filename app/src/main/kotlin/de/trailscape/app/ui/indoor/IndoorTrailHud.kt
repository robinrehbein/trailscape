package de.trailscape.app.ui.indoor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.theme.DefaultCockpitColors
import de.trailscape.app.ui.theme.LocalCockpitColors
import de.trailscape.core.indoor.climb.Climb
import de.trailscape.core.indoor.climb.ClimbCategory
import de.trailscape.core.indoor.climb.ClimbEffort
import de.trailscape.core.indoor.climb.ClimbLive
import de.trailscape.core.indoor.climb.TrailLive
import de.trailscape.core.indoor.climb.TrailMarker
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * # Einblendungen auf der Strecke im HUD (Bergwertung, Kilometer)
 *
 * Kompakt unter den Kernwerten (`TopExtras` im `IndoorRideScreen`):
 *  * vor dem Anstieg eine Zeile „Anstieg in 300 m: 2,4 km, 6,1 %",
 *  * am Anstieg eine kleine Tafel mit Rest, Rest-Hoehe, Steigung, Zeit und
 *    Abstand zur eigenen Bestzeit,
 *  * an der Kuppe das Ergebnis mit Passhoehe und „Neue Bestzeit",
 *  * dezent daneben die Kilometermarke („km 10").
 *
 * Zustandslos; den Zustand rechnet der `TrailTracker` in `:core`.
 */
@Composable
internal fun IndoorTrailOverlay(trail: TrailLive, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        trail.climb?.let { climb ->
            AnimatedContent(
                targetState = climb,
                contentKey = { it::class },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "trail-climb",
            ) { c ->
                when (c) {
                    is ClimbLive.Ahead -> ClimbAheadChip(c)
                    is ClimbLive.On -> ClimbOnPanel(c)
                    is ClimbLive.Done -> ClimbDonePanel(c.effort)
                }
            }
        }
        (trail.marker as? TrailMarker.Kilometer)?.let { KilometerChip(it.km) }
    }
}

/** Kategorie als kurzes Kennzeichen („Kat. 3", „HC"); `null` ohne Kategorie. */
@Composable
internal fun climbCategoryLabel(category: ClimbCategory): String? = when (category) {
    ClimbCategory.UNCATEGORIZED -> null
    ClimbCategory.HC -> stringResource(R.string.trail_hud_category_hc)
    ClimbCategory.CAT1 -> stringResource(R.string.trail_hud_category, 1)
    ClimbCategory.CAT2 -> stringResource(R.string.trail_hud_category, 2)
    ClimbCategory.CAT3 -> stringResource(R.string.trail_hud_category, 3)
    ClimbCategory.CAT4 -> stringResource(R.string.trail_hud_category, 4)
}

/** Zeit am Anstieg als M:SS bzw. H:MM:SS. */
internal fun formatClimbTime(ms: Long): String = formatIndoorClock((ms / 1000L).toInt())

/** Abstand zur Bestzeit mit Vorzeichen („+0:12", „−0:08"). */
internal fun formatClimbDelta(ms: Long): String {
    val s = (abs(ms) / 1000L).toInt()
    return (if (ms < 0) "−" else "+") + formatIndoorClock(s)
}

/** Entfernung bis zum Fuss, auf 50 m gerundet (mindestens 50 m). */
internal fun aheadMeters(toStartM: Double): Int = ((toStartM / 50.0).roundToInt() * 50).coerceAtLeast(50)

internal fun AppFormats.climbKm(climb: Climb): String = decimal(climb.lengthM / 1000.0, 1)

internal fun AppFormats.climbGrade(climb: Climb): String = decimal(climb.avgGradePct, 1)

/** Schneller als die Bestzeit gruen, langsamer orange. */
private fun deltaColor(ms: Long): Color =
    if (ms <= 0) DefaultCockpitColors.good else DefaultCockpitColors.warning

@Composable
private fun TrailPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cockpit = LocalCockpitColors.current
    Surface(
        color = cockpit.panel,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, cockpit.border),
        modifier = modifier.widthIn(max = 460.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
private fun ClimbAheadChip(c: ClimbLive.Ahead) {
    val cockpit = LocalCockpitColors.current
    val formats = LocalAppFormats.current
    val text = stringResource(R.string.trail_hud_ahead, aheadMeters(c.toStartM), formats.climbKm(c.climb), formats.climbGrade(c.climb))
    val category = climbCategoryLabel(c.climb.category)
    val best = c.bestMs?.let { stringResource(R.string.trail_hud_ahead_best, formatClimbTime(it)) }
    HudChip(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = hudStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), cockpit.ink),
            )
            listOfNotNull(category, best).forEach { extra ->
                Text(
                    text = "·  $extra",
                    style = hudStyle(MaterialTheme.typography.labelLarge, cockpit.inkMuted),
                )
            }
        }
    }
}

@Composable
private fun ClimbOnPanel(c: ClimbLive.On) {
    val cockpit = LocalCockpitColors.current
    val formats = LocalAppFormats.current
    TrailPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.trail_hud_on_title),
                style = hudStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), cockpit.good),
                maxLines = 1,
            )
            val extras = listOfNotNull(
                climbCategoryLabel(c.climb.category),
                if (!c.ranked) stringResource(R.string.trail_hud_on_unranked) else null,
            )
            extras.forEach {
                Text(text = "  ·  $it", style = hudStyle(MaterialTheme.typography.labelLarge, cockpit.inkMuted), maxLines = 1)
            }
        }
        // Bricht auf schmalen Geraeten und bei grosser Schrift in eine zweite Zeile um.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            TrailValue(stringResource(R.string.trail_hud_label_left), stringResource(R.string.common_value_km, formats.decimal(c.remainingM / 1000.0, 1)))
            TrailValue(stringResource(R.string.trail_hud_label_gain), stringResource(R.string.common_value_elevation, formats.decimal(c.remainingGainM, 0)))
            TrailValue(
                stringResource(R.string.trail_hud_label_grade),
                stringResource(R.string.trail_hud_value_percent, formats.indoorGrade(c.gradePct)),
                gradeColor(c.gradePct),
            )
            TrailValue(stringResource(R.string.trail_hud_label_time), formatClimbTime(c.elapsedMs))
            c.deltaToBestMs?.let { d ->
                TrailValue(stringResource(R.string.trail_hud_label_delta), formatClimbDelta(d), deltaColor(d))
            }
        }
    }
}

@Composable
private fun TrailValue(label: String, value: String, color: Color = LocalCockpitColors.current.ink) {
    val cockpit = LocalCockpitColors.current
    Column {
        Text(text = label, style = hudStyle(MaterialTheme.typography.labelSmall, cockpit.inkMuted), maxLines = 1)
        Text(
            text = value,
            style = hudStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Wie ein gefahrener Anstieg ausging — fuer die Farbe in HUD und Auswertung. */
internal enum class ClimbOutcome { UNRANKED, FIRST, NEW_BEST, SLOWER }

internal fun climbOutcome(e: ClimbEffort): ClimbOutcome = when {
    !e.ranked -> ClimbOutcome.UNRANKED
    e.firstTime -> ClimbOutcome.FIRST
    e.newBest -> ClimbOutcome.NEW_BEST
    else -> ClimbOutcome.SLOWER
}

/** Ergebnis-Satz eines gefahrenen Anstiegs (HUD und Auswertung). */
@Composable
internal fun climbVerdictText(e: ClimbEffort): String = when (climbOutcome(e)) {
    ClimbOutcome.UNRANKED -> stringResource(R.string.trail_hud_done_unranked)
    ClimbOutcome.FIRST -> stringResource(R.string.trail_hud_done_first)
    ClimbOutcome.NEW_BEST -> {
        val by = e.improvementMs?.takeIf { it >= 1000 }
        if (by != null) stringResource(R.string.trail_hud_done_new_best_by, formatClimbTime(by)) else stringResource(R.string.trail_hud_done_new_best)
    }
    ClimbOutcome.SLOWER -> stringResource(R.string.trail_hud_done_slower, formatClimbTime(-(e.improvementMs ?: 0L)))
}

private fun hudOutcomeColor(o: ClimbOutcome): Color = when (o) {
    ClimbOutcome.UNRANKED -> DefaultCockpitColors.inkMuted
    ClimbOutcome.FIRST, ClimbOutcome.NEW_BEST -> DefaultCockpitColors.good
    ClimbOutcome.SLOWER -> DefaultCockpitColors.warning
}

@Composable
private fun ClimbDonePanel(e: ClimbEffort) {
    val cockpit = LocalCockpitColors.current
    val formats = LocalAppFormats.current
    val verdict = climbVerdictText(e)
    val color = hudOutcomeColor(climbOutcome(e))
    TrailPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.trail_hud_done_title, formats.decimal(e.climb.topEleM, 0)),
                style = hudStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), cockpit.ink),
                maxLines = 1,
            )
            climbCategoryLabel(e.climb.category)?.let {
                Text(text = "  ·  $it", style = hudStyle(MaterialTheme.typography.labelLarge, cockpit.inkMuted), maxLines = 1)
            }
        }
        Text(
            text = stringResource(R.string.trail_hud_done_time, formatClimbTime(e.timeMs)),
            style = hudStyle(MaterialTheme.typography.labelLarge, cockpit.ink),
            maxLines = 1,
        )
        Text(
            text = verdict,
            style = hudStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun KilometerChip(km: Int) {
    val cockpit = LocalCockpitColors.current
    HudChip(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            text = stringResource(R.string.trail_hud_km, km),
            style = hudStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), cockpit.inkMuted),
            maxLines = 1,
        )
    }
}
