package de.trailscape.app.ui.erfolge

import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.data.ErfolgeStore
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.rememberNow
import de.trailscape.app.ui.more.SettingsSwitchRow
import de.trailscape.app.ui.progress.CardTitle
import de.trailscape.app.ui.progress.IconBubble
import de.trailscape.app.ui.progress.ProgressBar
import de.trailscape.app.ui.progress.ProgressRow
import de.trailscape.app.ui.progress.RowDivider
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.AchievementGroup
import de.trailscape.core.AchievementId
import de.trailscape.core.AchievementOverview
import de.trailscape.core.AchievementState
import de.trailscape.core.AchievementStatus
import de.trailscape.core.canMarkPauseWeek
import de.trailscape.core.rideFacts
import de.trailscape.core.unseenAchievements
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * # Bildschirm „Erfolge"
 *
 * Stufe mit Punkten und Weg zur naechsten, die Wochenserie mit Pausenwochen,
 * dann alle Abzeichen nach Gruppen — erreicht mit Datum, offen mit Bedingung
 * und Fortschritt. Abzeichen, deren Funktion es noch nicht gibt, stehen
 * gesammelt unter „Kommt bald". Regeln: `docs/erfolge.md`.
 *
 * Geoeffnet wird er als Blatt aus dem Training-Tab ([ErfolgeEntryCard]); der
 * Inhalt ([ErfolgeContent]) ist zustandslos und wird so auch im
 * Screenshot-Test gezeichnet.
 */

/** Was die Pausenschalter brauchen; `null` = kein Schalter fuer diese Woche (sie hat eine Tour). */
internal data class PauseOption(val monday: LocalDate, val marked: Boolean, val canToggle: Boolean)

internal data class PauseControls(
    val thisWeek: PauseOption?,
    val lastWeek: PauseOption?,
    val onToggle: (LocalDate) -> Unit,
)

/**
 * Die Karte im Training-Tab: Stufe, Zahl der Abzeichen, Weg zur naechsten
 * Stufe, „2 neu". Nichts, solange gerechnet wird oder die Erfolge aus sind.
 */
@Composable
internal fun ErfolgeEntry(appViewModel: AppViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val overview = rememberErfolge(appViewModel) ?: return
    val progress by ErfolgeStore.shared.state.collectAsStateWithLifecycle()
    val newCount = remember(overview, progress) { unseenAchievements(overview.states, progress).size }
    ErfolgeEntryCard(overview, newCount, onOpen, modifier)
}

@Composable
internal fun ErfolgeEntryCard(
    overview: AchievementOverview,
    newCount: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val level = overview.level
    Card(modifier = modifier.fillMaxWidth()) {
        ProgressRow(
            icon = ErfolgeIcons.Medal,
            tint = LocalSignalColors.current.warning,
            title = stringResource(R.string.erfolge_title),
            subtitle = stringResource(
                R.string.erfolge_entry_status,
                level.level,
                overview.reachedCount,
                overview.available.size,
            ),
            fraction = level.fraction.toFloat(),
            trailing = if (newCount > 0) pluralStringResource(R.plurals.erfolge_new_count, newCount, newCount) else null,
            onClick = onOpen,
            onClickLabel = stringResource(R.string.erfolge_entry_cd),
        )
    }
}

/**
 * Das Blatt „Erfolge". Laedt beim Oeffnen auch Runden, Kacheln und Wege.
 * Was beim Oeffnen neu war, bleibt fuer diese Ansicht als „Neu" markiert und
 * gilt danach als gesehen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ErfolgeSheet(appViewModel: AppViewModel, onDismiss: () -> Unit) {
    val overview = rememberErfolge(appViewModel, loadSources = true)
    val store = ErfolgeStore.shared
    val progress by store.state.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    var newIds by remember { mutableStateOf<Set<AchievementId>?>(null) }
    LaunchedEffect(overview) {
        val o = overview ?: return@LaunchedEffect
        val unseen = unseenAchievements(o.states, store.value)
        newIds = (newIds ?: emptySet()) + unseen
        if (unseen.isNotEmpty()) store.update { it.seeing(unseen) }
    }
    val facts = remember(rides) { rideFacts(rides) { localOfEpochMs(it).toLocalDate() } }
    val today = rememberNow().toLocalDate()
    val pause = remember(facts, progress, today) {
        val thisMonday = today.with(DayOfWeek.MONDAY)
        fun option(monday: LocalDate): PauseOption? {
            if (facts.any { it.date.with(DayOfWeek.MONDAY) == monday }) return null
            val marked = monday in progress.pauseWeeks
            return PauseOption(monday, marked, marked || canMarkPauseWeek(facts, progress.pauseWeeks, monday))
        }
        PauseControls(
            thisWeek = option(thisMonday),
            // Die letzte Woche nur, wenn davor eine Serie lief — sonst haelt sie nichts.
            lastWeek = option(thisMonday.minusWeeks(1))?.takeIf { facts.any { f -> f.date.isBefore(thisMonday.minusWeeks(1)) } },
            onToggle = { monday -> store.update { it.togglingPause(monday) } },
        )
    }
    val pointsSince = progress.pointsSince?.takeIf { since -> facts.any { it.date.isBefore(since) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        if (overview == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            de.trailscape.app.ui.training.SheetColumn {
                ErfolgeContent(
                    overview = overview,
                    newIds = newIds.orEmpty(),
                    hasPlan = plan != null,
                    pause = pause,
                    pointsSince = pointsSince,
                )
            }
        }
    }
}

/**
 * Der Inhalt des Blatts, zustandslos.
 *
 * @param pointsSince gesetzt, wenn es Touren vor dem Punktebeginn gibt — dann
 *   steht der Hinweis, dass sie nur fuer Abzeichen zaehlen.
 */
@Composable
internal fun ErfolgeContent(
    overview: AchievementOverview,
    newIds: Set<AchievementId>,
    hasPlan: Boolean,
    pause: PauseControls?,
    pointsSince: LocalDate?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CardGap)) {
        Text(
            text = stringResource(R.string.erfolge_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        LevelCard(overview, pointsSince)
        StreakCard(overview, pause)
        for (group in AchievementGroup.entries) {
            val states = overview.states.filter { it.id.group == group && it.status != AchievementStatus.COMING_SOON }
            if (states.isEmpty()) continue
            BadgeGroupCard(
                title = stringResource(groupTitleRes(group)),
                states = states,
                newIds = newIds,
                hasPlan = hasPlan,
            )
        }
        val soon = overview.states.filter { it.status == AchievementStatus.COMING_SOON }
        if (soon.isNotEmpty()) ComingSoonCard(soon)
    }
}

@Composable
private fun LevelCard(overview: AchievementOverview, pointsSince: LocalDate?) {
    val level = overview.level
    val points = overview.points
    val accent = LocalSignalColors.current.warning
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = level.level.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = accent,
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.erfolge_level_title, level.level),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.erfolge_level_points, level.points),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
            ProgressBar(level.fraction.toFloat(), accent)
            Text(
                text = level.nextLevelPoints?.let {
                    stringResource(R.string.erfolge_level_next, level.remaining, level.level + 1)
                } ?: stringResource(R.string.erfolge_level_top),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Text(
                text = stringResource(R.string.erfolge_points_breakdown, points.ride, points.plan, points.week, points.challenge),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.erfolge_points_explain),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            if (pointsSince != null) {
                Text(
                    text = stringResource(R.string.erfolge_points_since, LocalAppFormats.current.dateFull(pointsSince)),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
    }
}

@Composable
private fun StreakCard(overview: AchievementOverview, pause: PauseControls?) {
    val streak = overview.streak
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ProgressRow(
                icon = ErfolgeIcons.Calendar,
                tint = LocalSignalColors.current.warning,
                title = stringResource(R.string.erfolge_streak_title),
                subtitle = pluralStringResource(R.plurals.erfolge_streak_best_count, streak.best, streak.best),
                trailing = pluralStringResource(R.plurals.erfolge_streak_weeks_count, streak.weeks, streak.weeks),
            )
            val options = listOfNotNull(
                pause?.thisWeek?.let { it to R.string.erfolge_streak_pause_this_week },
                pause?.lastWeek?.let { it to R.string.erfolge_streak_pause_last_week },
            )
            if (pause != null && options.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = CardPadding, end = CardPadding, bottom = CardPadding),
                ) {
                    for ((option, title) in options) {
                        SettingsSwitchRow(
                            title = stringResource(title),
                            checked = option.marked,
                            onCheckedChange = { pause.onToggle(option.monday) },
                            enabled = option.canToggle,
                        )
                    }
                    val full = options.any { !it.first.canToggle }
                    Text(
                        text = stringResource(
                            if (full) R.string.erfolge_streak_pause_full else R.string.erfolge_streak_pause_hint,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgeGroupCard(
    title: String,
    states: List<AchievementState>,
    newIds: Set<AchievementId>,
    hasPlan: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            CardTitle(
                title,
                Modifier.padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = 4.dp),
            )
            states.forEachIndexed { index, state ->
                if (index > 0) RowDivider()
                BadgeRow(state, isNew = state.id in newIds, hasPlan = hasPlan)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/** Ein Abzeichen: Symbol (farbig, wenn erreicht), Name, Bedingung, Stand. */
@Composable
private fun BadgeRow(state: AchievementState, isNew: Boolean, hasPlan: Boolean) {
    val signals = LocalSignalColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint: Color = if (state.reached) signals.warning else muted
    val reachedCd = stringResource(R.string.erfolge_reached_cd)
    val openCd = stringResource(R.string.erfolge_open_cd)
    LocalConfiguration.current
    val status = badgeStatusText(
        LocalContext.current.resources,
        LocalAppFormats.current,
        state,
        hasPlan,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { stateDescription = if (state.reached) reachedCd else openCd }
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBubble(ErfolgeIcons.forBadge(state.id), tint)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(badgeTitleRes(state.id)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isNew) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TagPill(text = stringResource(R.string.erfolge_new_label))
                }
            }
            Text(
                text = stringResource(badgeBodyRes(state.id)),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.reached) signals.good else MaterialTheme.colorScheme.onSurface,
            )
            val fraction = state.fraction
            if (!state.reached && fraction != null && fraction > 0.0) {
                Spacer(modifier = Modifier.height(6.dp))
                ProgressBar(fraction.toFloat(), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ComingSoonCard(states: List<AchievementState>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(stringResource(R.string.erfolge_group_soon))
            Text(text = stringResource(R.string.erfolge_soon_hint), style = MaterialTheme.typography.bodySmall, color = muted)
            for (state in states) {
                SoonLine(ErfolgeIcons.Soon, stringResource(badgeTitleRes(state.id)), stringResource(badgeBodyRes(state.id)))
            }
        }
    }
}

@Composable
private fun SoonLine(icon: ImageVector, title: String, body: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, muted, size = 32.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}
