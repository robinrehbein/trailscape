package de.trailscape.app.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.Milestone
import de.trailscape.core.ChallengeProgress
import de.trailscape.core.MilestoneKind
import de.trailscape.core.MilestoneProgress
import de.trailscape.core.PeriodTotals
import de.trailscape.core.RecentTrend
import de.trailscape.core.RecordGain
import de.trailscape.core.RecordKind
import de.trailscape.core.RideRecord
import de.trailscape.core.StretchChallenge
import de.trailscape.core.WeekBucket
import de.trailscape.core.WeekStreak
import de.trailscape.core.YearGoalProgress
import de.trailscape.core.maxYearGoalKm
import de.trailscape.core.minYearGoalKm
import de.trailscape.core.roundYearGoalKm
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * # Bausteine fuers Dranbleiben
 *
 * Die Karten, die aus der Motivationsrechnung (`core/…/Motivation.kt`) etwas
 * Sichtbares machen: Wochenserie, naechste Bestweite, Jahresziel,
 * Meilensteine, der Verlauf der letzten Wochen, Bestwerte und die Erfolge
 * einer einzelnen Tour. Reine Darstellung — gerechnet wird in `:core`,
 * welcher Baustein wo erscheint, entscheiden „Heute", der Training-Tab und
 * das Tour-Detail.
 *
 * ## Gestaltung
 * One UI: weisse Karten mit der Rundung des Themes, darin Zeilen mit einem
 * runden, getoenten Symbol vorn wie in Samsungs Einstellungen. Es gibt hier
 * **keinen gefuellten Knopf** — der eine volle Knopf der Startseite bleibt
 * die Empfehlung in der Hero-Karte; alles hier ist ein Angebot daneben.
 */

// ---------------------------------------------------------------------------
// Gemeinsame Zeile
// ---------------------------------------------------------------------------

/** Rundes, getoentes Symbol am Zeilenanfang. */
@Composable
internal fun IconBubble(icon: ImageVector, tint: Color, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
internal fun ProgressBar(fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    )
}

/**
 * Eine Zeile: Symbol, Titel, Unterzeile, optional Fortschrittsbalken und
 * Pfeil. Mit [onClick] wird die ganze Zeile zur Schaltflaeche.
 */
@Composable
internal fun ProgressRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, tint)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (fraction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ProgressBar(fraction, tint)
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }
        if (onClick != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Trennlinie zwischen zwei Zeilen, eingerueckt bis zur Textkante. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = CardPadding + 54.dp, end = CardPadding),
    )
}

@Composable
internal fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() },
    )
}

// ---------------------------------------------------------------------------
// Wochenserie
// ---------------------------------------------------------------------------

/**
 * Die Wochenserie als Fusszeile der Wochenkarte auf „Heute".
 *
 * Drei Lagen, drei Toene:
 *  * laeuft und diese Woche gesichert — Flamme in Orange, ruhiger Satz;
 *  * laeuft, diese Woche fehlt noch — derselbe Ton, aber der Satz nennt die
 *    Tage, die bleiben. Keine Warnfarbe: Das ist eine Einladung, kein Alarm;
 *  * keine Serie — graue Flamme und der Weg zur naechsten.
 */
@Composable
internal fun StreakFooter(streak: WeekStreak, modifier: Modifier = Modifier) {
    val signals = LocalSignalColors.current
    val active = streak.weeks > 0
    val title = if (active) {
        pluralStringResource(R.plurals.progress_streak_weeks_count, streak.weeks, streak.weeks)
    } else {
        stringResource(R.string.progress_streak_start_title)
    }
    val hint = when {
        streak.atRisk ->
            pluralStringResource(R.plurals.progress_streak_risk_days_count, streak.daysLeft, streak.daysLeft)

        active && streak.weeks >= 3 && streak.weeks == streak.best ->
            stringResource(R.string.progress_streak_longest_hint)

        active -> stringResource(R.string.progress_streak_done_hint)
        streak.best >= 2 -> stringResource(R.string.progress_streak_start_best_hint, streak.best)
        else -> stringResource(R.string.progress_streak_start_hint)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(
            icon = Icons.Rounded.LocalFireDepartment,
            tint = if (active) signals.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            size = 36.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Naechste Bestweite
// ---------------------------------------------------------------------------

/**
 * Die naechste Bestweite ([StretchChallenge]) mit dem Knopf, der genau diese
 * Runde baut. Der Knopf ist neutral: Die Tagesempfehlung in der Hero-Karte
 * bleibt die eine volle Aktion der Seite.
 */
@Composable
internal fun ChallengeCard(challenge: StretchChallenge, onBuild: () -> Unit, modifier: Modifier = Modifier) {
    val formats = LocalAppFormats.current
    val more = (challenge.targetKm - challenge.baseKm).roundToInt().coerceAtLeast(1)
    val body = stringResource(
        if (challenge.beatsAllTime) R.string.progress_challenge_all_time_body else R.string.progress_challenge_body,
        formats.km(challenge.baseKm),
        more,
    )
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.AutoMirrored.Rounded.TrendingUp, MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.progress_challenge_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.progress_challenge_title, challenge.targetKm),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))
            NeutralButton(
                onClick = onBuild,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Route, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.progress_challenge_action, challenge.targetKm),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Jahresziel und naechster Meilenstein (Startseite)
// ---------------------------------------------------------------------------

/** Die eine Zeile unter dem Jahresziel: voraus, zurueck, geschafft. */
@Composable
private fun yearGoalHint(progress: YearGoalProgress): String {
    val ahead = progress.aheadKm
    return when {
        progress.reached -> stringResource(R.string.progress_year_goal_reached_hint)
        abs(ahead) < 1.0 -> stringResource(R.string.progress_year_goal_on_track_hint)
        ahead > 0 -> stringResource(R.string.progress_year_goal_ahead_hint, whole(ahead))
        else -> stringResource(
            R.string.progress_year_goal_behind_hint,
            whole(-ahead),
            whole(progress.neededPerWeekKm),
        )
    }
}

@Composable
private fun yearGoalTint(progress: YearGoalProgress?): Color =
    if (progress != null && !progress.reached && progress.aheadKm <= -1.0) {
        LocalSignalColors.current.caution
    } else {
        MaterialTheme.colorScheme.primary
    }

/**
 * „Als Naechstes" auf der Startseite: Jahresziel und der naechstgelegene
 * Meilenstein als zwei Zeilen einer Karte.
 *
 * @param yearGoal Stand zum Jahresziel, `null` ohne gesetztes Ziel — dann
 *   laedt die Zeile dazu ein, eines zu setzen ([suggestedKm]).
 * @param suggestedKm Vorschlag fuers Jahresziel; `null` ohne Touren.
 * @param milestone die naechste Stufe, `null` oberhalb aller Leitern.
 * @param challenge die offene Monats-Challenge mit dem groessten Fortschritt
 *   ([nextOpenChallenge]) als dritte Zeile, nach den Zielen deren Bonus; `null`, wenn alles erreicht ist.
 */
@Composable
internal fun NextGoalsCard(
    yearGoal: YearGoalProgress?,
    year: Int,
    suggestedKm: Int?,
    milestone: MilestoneProgress?,
    onYearGoal: () -> Unit,
    onOpenProgress: () -> Unit,
    modifier: Modifier = Modifier,
    challenge: ChallengeProgress? = null,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            if (yearGoal != null) {
                ProgressRow(
                    icon = Icons.Rounded.Flag,
                    tint = yearGoalTint(yearGoal),
                    title = stringResource(
                        R.string.progress_year_goal_value,
                        wholeTowards(yearGoal.riddenKm, yearGoal.targetKm),
                        whole(yearGoal.targetKm),
                    ),
                    subtitle = stringResource(R.string.progress_year_goal_title, year) + " · " + yearGoalHint(yearGoal),
                    fraction = yearGoal.fraction.toFloat(),
                    onClick = onOpenProgress,
                    onClickLabel = stringResource(R.string.progress_milestone_open_cd),
                )
            } else {
                ProgressRow(
                    icon = Icons.Rounded.Flag,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.progress_year_goal_prompt_title),
                    subtitle = if (suggestedKm != null) {
                        stringResource(R.string.progress_year_goal_prompt_body, suggestedKm)
                    } else {
                        stringResource(R.string.progress_year_goal_prompt_empty_body)
                    },
                    onClick = onYearGoal,
                    onClickLabel = stringResource(R.string.progress_year_goal_open_cd),
                )
            }
            val remaining = milestone?.let { milestoneRemaining(it) }
            if (milestone != null && remaining != null) {
                RowDivider()
                ProgressRow(
                    icon = Icons.Rounded.EmojiEvents,
                    tint = LocalSignalColors.current.accentBlue,
                    title = remaining,
                    subtitle = null,
                    fraction = milestone.fraction.toFloat(),
                    onClick = onOpenProgress,
                    onClickLabel = stringResource(R.string.progress_milestone_open_cd),
                )
            }
            if (challenge != null) {
                RowDivider()
                ProgressRow(
                    icon = challengeNextIcon(challenge.challenge.kind),
                    tint = if (challenge.reached) LocalSignalColors.current.accentBlue else MaterialTheme.colorScheme.primary,
                    title = challengeNextTitle(challenge),
                    subtitle = null,
                    fraction = (if (challenge.reached) challenge.bonusFraction else challenge.fraction).toFloat(),
                    onClick = onOpenProgress,
                    onClickLabel = stringResource(R.string.progress_milestone_open_cd),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Verlauf der letzten Wochen
// ---------------------------------------------------------------------------

/**
 * Kilometer je Woche als Balken, darueber die Summe der letzten vier Wochen
 * und der Vergleich mit den vier davor.
 *
 * Die Zahl darueber ist ein **gleitender** Zeitraum (die letzten 28 Tage),
 * die Balken sind **Kalenderwochen** — beides deckt sich nur sonntags. Die
 * Balken heben deshalb bewusst keine „vier Wochen" hervor: Alle
 * abgeschlossenen Wochen tragen dieselbe Farbe, nur die laufende ist heller,
 * weil sie angeschnitten ist und noch waechst.
 */
@Composable
internal fun TrendCard(buckets: List<WeekBucket>, trend: RecentTrend, modifier: Modifier = Modifier) {
    val theme = MaterialTheme.colorScheme
    val signals = LocalSignalColors.current
    val peakKm = buckets.maxOfOrNull { it.totals.km } ?: 0.0
    val change = trend.kmChangePct
    val changeText = when {
        change == null -> stringResource(R.string.progress_trend_first)
        change > 0 -> stringResource(R.string.progress_trend_up, change)
        change < 0 -> stringResource(R.string.progress_trend_down, -change)
        else -> stringResource(R.string.progress_trend_same)
    }
    val changeColor = when {
        change == null || change == 0 -> theme.onSurfaceVariant
        change > 0 -> signals.good
        else -> signals.caution
    }
    val chartDescription = stringResource(
        R.string.progress_trend_chart_cd,
        whole(trend.recent.km),
        whole(peakKm),
    )

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text(
                text = stringResource(R.string.progress_trend_headline, whole(trend.recent.km)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = changeText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = changeColor,
            )
            Spacer(modifier = Modifier.height(16.dp))
            WeekBars(
                values = buckets.map { it.totals.km },
                recentColor = theme.primary,
                currentColor = theme.primary.copy(alpha = 0.5f),
                baseline = theme.outlineVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clearAndSetSemantics { contentDescription = chartDescription },
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.progress_trend_axis_start),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.progress_trend_axis_end),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.onSurfaceVariant,
                )
            }
            if (peakKm > 0) {
                Text(
                    text = stringResource(R.string.progress_trend_peak, whole(peakKm)),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = theme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))
            TotalsRow(trend.recent)
        }
    }
}

/** Touren, Stunden, Hoehenmeter eines Zeitraums nebeneinander. */
@Composable
private fun TotalsRow(totals: PeriodTotals, withKm: Boolean = false) {
    val formats = LocalAppFormats.current
    Row(modifier = Modifier.fillMaxWidth()) {
        if (withKm) {
            Fact(
                label = stringResource(R.string.progress_fact_km),
                value = whole(totals.km),
                modifier = Modifier.weight(1f),
            )
        }
        Fact(
            label = stringResource(R.string.progress_fact_rides),
            value = totals.rides.toString(),
            modifier = Modifier.weight(1f),
        )
        Fact(
            label = stringResource(R.string.progress_fact_hours),
            value = formats.decimal(totals.timeS / 3600.0, if (totals.timeS >= 360_000) 0 else 1),
            modifier = Modifier.weight(1f),
        )
        Fact(
            label = stringResource(R.string.progress_fact_ascent),
            value = whole(totals.ascentM),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WeekBars(
    values: List<Double>,
    recentColor: Color,
    currentColor: Color,
    baseline: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val count = values.size
        if (count == 0) return@Canvas
        val max = values.max().coerceAtLeast(1.0)
        val gap = 6.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val radius = CornerRadius(minOf(barWidth / 2f, 6.dp.toPx()))
        val floor = size.height - 1.dp.toPx()
        // Auch eine Woche ohne Tour bekommt einen Stummel: Die Luecke soll
        // als Woche lesbar bleiben, nicht als fehlender Balken.
        val stub = 3.dp.toPx()
        values.forEachIndexed { index, value ->
            val height = if (value > 0) ((value / max).toFloat() * floor).coerceAtLeast(stub * 2) else stub
            val color = when {
                value <= 0 -> baseline
                index == count - 1 -> currentColor
                else -> recentColor
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), floor - height),
                size = Size(barWidth, height),
                cornerRadius = radius,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Jahresziel (Training-Tab)
// ---------------------------------------------------------------------------

/** Das Jahresziel mit Ring, Stand, Hochrechnung und „Ändern". */
@Composable
internal fun YearGoalCard(
    progress: YearGoalProgress?,
    year: Int,
    suggestedKm: Int?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (progress == null) {
        Card(modifier = modifier.fillMaxWidth()) {
            ProgressRow(
                icon = Icons.Rounded.Flag,
                tint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.progress_year_goal_prompt_title),
                subtitle = if (suggestedKm != null) {
                    stringResource(R.string.progress_year_goal_prompt_body, suggestedKm)
                } else {
                    stringResource(R.string.progress_year_goal_prompt_empty_body)
                },
                onClick = onEdit,
                onClickLabel = stringResource(R.string.progress_year_goal_open_cd),
            )
        }
        return
    }
    val tint = yearGoalTint(progress)
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle(stringResource(R.string.progress_year_goal_title, year), Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text(stringResource(R.string.progress_year_goal_edit_action)) }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 9.dp.toPx()
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        val arcOffset = Offset(stroke / 2f, stroke / 2f)
                        drawArc(track, 0f, 360f, false, arcOffset, arcSize, style = Stroke(stroke))
                        drawArc(
                            color = tint,
                            startAngle = -90f,
                            sweepAngle = 360f * progress.fraction.toFloat(),
                            useCenter = false,
                            topLeft = arcOffset,
                            size = arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                    Text(
                        // Abgerundet: 99,6 % sind noch nicht 100.
                        text = stringResource(R.string.progress_percent, (progress.fraction * 100).toInt()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.progress_year_goal_value,
                            wholeTowards(progress.riddenKm, progress.targetKm),
                            whole(progress.targetKm),
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = yearGoalHint(progress),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                    )
                    if (!progress.reached && progress.riddenKm > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.progress_year_goal_projection, whole(progress.projectedKm)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Jahresziel setzen: eine grosse Zahl, ein Regler und die Uebersetzung in
 * Kilometer je Woche fuer den Rest des Jahres — die Zahl, an der man im
 * Alltag merkt, ob das Ziel passt.
 *
 * Der Regler reicht bis zum Dreifachen des Vorschlags (mindestens 5000 km):
 * Ein fester Bereich bis 30 000 km liesse fuer die Ziele, die fast alle
 * setzen, nur das linke Zehntel uebrig. Die Zahl rastet ueber
 * [roundYearGoalKm] auf merkbare Werte.
 *
 * @param weeksLeft verbleibende Wochen des Jahres, fuer „pro Woche".
 * @param riddenKm in diesem Jahr schon gefahren.
 */
@Composable
internal fun YearGoalDialog(
    currentKm: Int?,
    suggestedKm: Int,
    weeksLeft: Double,
    riddenKm: Double,
    onSave: (Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sliderMax = maxOf(5000, suggestedKm * 3, (currentKm ?: 0) * 2).coerceAtMost(maxYearGoalKm)
    var value by rememberSaveable { mutableFloatStateOf((currentKm ?: suggestedKm).toFloat()) }
    val km = roundYearGoalKm(value.toDouble())
    val perWeek = ((km - riddenKm).coerceAtLeast(0.0) / weeksLeft.coerceAtLeast(1.0))
    val sliderDescription = stringResource(R.string.progress_year_goal_dialog_slider_cd)
    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.progress_year_goal_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.progress_year_goal_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.common_value_km, km.toString()),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(
                    text = stringResource(R.string.progress_year_goal_dialog_per_week, whole(perWeek)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = minYearGoalKm.toFloat()..sliderMax.toFloat(),
                    modifier = Modifier.semantics { contentDescription = sliderDescription },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(km) }) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = {
            Row {
                if (currentKm != null) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.progress_year_goal_remove_action))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Bestwerte, Meilensteine, Gesamt
// ---------------------------------------------------------------------------

internal fun recordIcon(kind: RecordKind): ImageVector = when (kind) {
    RecordKind.DISTANCE -> Icons.Rounded.Straighten
    RecordKind.ASCENT -> Icons.Rounded.Terrain
    RecordKind.TIME -> Icons.Rounded.Timer
    RecordKind.SPEED -> Icons.Rounded.Speed
}

@Composable
internal fun recordLabel(kind: RecordKind): String = stringResource(
    when (kind) {
        RecordKind.DISTANCE -> R.string.progress_record_distance
        RecordKind.ASCENT -> R.string.progress_record_ascent
        RecordKind.TIME -> R.string.progress_record_time
        RecordKind.SPEED -> R.string.progress_record_speed
    },
)

/** Die Bestwerte; ein Tipp oeffnet die Tour, die den Wert haelt. */
@Composable
internal fun RecordsCard(records: List<RideRecord>, onOpenRide: (String) -> Unit, modifier: Modifier = Modifier) {
    val formats = LocalAppFormats.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            CardTitle(
                stringResource(R.string.progress_records_title),
                Modifier.padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = 4.dp),
            )
            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.progress_records_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = CardPadding, end = CardPadding, bottom = CardPadding),
                )
            }
            records.forEachIndexed { index, record ->
                if (index > 0) RowDivider()
                val date = formats.dateShort(record.date)
                ProgressRow(
                    icon = recordIcon(record.kind),
                    tint = MaterialTheme.colorScheme.primary,
                    title = recordLabel(record.kind),
                    subtitle = if (record.kind == RecordKind.SPEED) {
                        "$date · " + stringResource(R.string.progress_record_speed_note)
                    } else {
                        date
                    },
                    trailing = recordValue(record.kind, record.value),
                    onClick = { onOpenRide(record.rideId) },
                    onClickLabel = stringResource(R.string.progress_record_open_cd),
                )
            }
            if (records.isNotEmpty()) Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private fun milestoneIcon(kind: MilestoneKind): ImageVector = when (kind) {
    MilestoneKind.TOTAL_KM -> Icons.Rounded.Route
    MilestoneKind.TOTAL_ASCENT -> Icons.Rounded.Terrain
    MilestoneKind.RIDE_COUNT -> Icons.Rounded.EmojiEvents
    MilestoneKind.LONGEST_RIDE -> Icons.Rounded.Straighten
}

@Composable
private fun milestoneKindLabel(kind: MilestoneKind): String = stringResource(
    when (kind) {
        MilestoneKind.TOTAL_KM -> R.string.progress_milestone_kind_total_km
        MilestoneKind.TOTAL_ASCENT -> R.string.progress_milestone_kind_total_ascent
        MilestoneKind.RIDE_COUNT -> R.string.progress_milestone_kind_ride_count
        MilestoneKind.LONGEST_RIDE -> R.string.progress_milestone_kind_longest_ride
    },
)

/** Alle vier Leitern mit Stand und Weg zur naechsten Stufe. */
@Composable
internal fun MilestonesCard(progress: List<MilestoneProgress>, modifier: Modifier = Modifier) {
    val accent = LocalSignalColors.current.accentBlue
    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            CardTitle(
                stringResource(R.string.progress_milestones_title),
                Modifier.padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = 4.dp),
            )
            progress.forEachIndexed { index, step ->
                if (index > 0) RowDivider()
                ProgressRow(
                    icon = milestoneIcon(step.kind),
                    tint = accent,
                    title = milestoneKindLabel(step.kind),
                    subtitle = milestoneRemaining(step) ?: stringResource(R.string.progress_milestone_all_done),
                    fraction = step.fraction.toFloat(),
                    trailing = whole(step.current),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/** Alles zusammen: Kilometer, Touren, Stunden, Hoehenmeter und die laengste Serie. */
@Composable
internal fun LifetimeCard(totals: PeriodTotals, bestStreak: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) {
            CardTitle(stringResource(R.string.progress_lifetime_title))
            Spacer(modifier = Modifier.height(12.dp))
            TotalsRow(totals, withKm = true)
            if (bestStreak >= 2) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.LocalFireDepartment,
                        contentDescription = null,
                        tint = LocalSignalColors.current.warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.progress_lifetime_best_streak, bestStreak),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Erfolge einer Tour
// ---------------------------------------------------------------------------

/**
 * Was eine Tour an Bestwerten gesetzt und an Meilensteinen ueberschritten
 * hat. Ohne beides erscheint nichts — eine leere „Erfolge"-Karte unter einer
 * gewoehnlichen Feierabendrunde waere das Gegenteil von Motivation.
 */
@Composable
internal fun AchievementsCard(records: List<RecordGain>, milestones: List<Milestone>, modifier: Modifier = Modifier) {
    if (records.isEmpty() && milestones.isEmpty()) return
    val signals = LocalSignalColors.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.progress_achievements_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            for (record in records) {
                val text = stringResource(
                    when (record.kind) {
                        RecordKind.DISTANCE -> R.string.progress_achievement_record_distance
                        RecordKind.ASCENT -> R.string.progress_achievement_record_ascent
                        RecordKind.TIME -> R.string.progress_achievement_record_time
                        RecordKind.SPEED -> R.string.progress_achievement_record_speed
                    },
                    recordValue(record.kind, record.value),
                    recordValue(record.kind, record.previous),
                )
                AchievementLine(Icons.Rounded.EmojiEvents, signals.warning, text)
            }
            for (milestone in milestones) {
                AchievementLine(
                    icon = Icons.Rounded.Flag,
                    tint = signals.accentBlue,
                    text = stringResource(
                        R.string.progress_achievement_milestone,
                        milestoneName(milestone.kind, milestone.threshold),
                    ),
                )
            }
        }
    }
}

@Composable
private fun AchievementLine(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tint, size = 32.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
