package de.trailscape.app.ui.progress

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.ChallengeKind
import de.trailscape.core.ChallengeMonth
import de.trailscape.core.ChallengeProgress
import de.trailscape.core.ChallengeTier
import de.trailscape.core.WeeklyGoalKind
import de.trailscape.core.WeeklyGoalProgress
import de.trailscape.core.WeeklyRecap
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Die Bausteine rund um **Ziele auf Zeit**: das Wochenziel ohne Plan, der
 * Rueckblick auf die letzte Woche und die Monats-Challenges. Gerechnet wird in
 * `:core` (`WeeklyGoal.kt`, `WeeklyRecap.kt`, `Challenges.kt`); hier steht nur,
 * wie es aussieht und wie es klingt.
 *
 * ## Gestaltung
 * Dieselbe Sprache wie `ProgressCards.kt`: weisse Karte, Zeilen mit rundem
 * Symbol, Fortschrittsbalken in der Primaerfarbe — erreicht heisst Gut-Gruen.
 * Nirgends ein gefuellter Knopf (der eine gehoert der Hero-Karte) und nirgends
 * ein Alarmton: Ein verfehltes Ziel faerbt sich nicht rot, es ist nur noch
 * nicht erreicht.
 */

// ---------------------------------------------------------------------------
// Wochenziel
// ---------------------------------------------------------------------------

/**
 * Der Satz unter dem Balken der Wochenkarte: was fuer eine Woche das ist und
 * was noch fehlt — „Etwas mehr als deine uebliche Woche (55 km) · noch 18 km
 * in 3 Tagen".
 *
 * Nur fuer das **mitwachsende** Wochenziel. Ein Plan erklaert sich selbst
 * (Planwoche, Einheiten im Streifen); ohne Plan ist die Zahl sonst eine
 * Behauptung ohne Begruendung. Ist das Ziel erreicht, bleibt nur die
 * Entwarnung: Alles Weitere ist Zugabe, nichts mehr, was man schuldet.
 */
@Composable
internal fun weeklyGoalCaption(progress: WeeklyGoalProgress): String {
    if (progress.reached) return stringResource(R.string.progress_week_goal_done_hint)
    val kindText = when (progress.goal.kind) {
        WeeklyGoalKind.AUFBAU ->
            stringResource(R.string.progress_week_goal_aufbau, progress.goal.basisKm.roundToInt())

        WeeklyGoalKind.ENTLASTUNG -> stringResource(R.string.progress_week_goal_entlastung)
        WeeklyGoalKind.HALTEN -> stringResource(R.string.progress_week_goal_halten)
        WeeklyGoalKind.WIEDEREINSTIEG -> stringResource(R.string.progress_week_goal_wiedereinstieg)
        WeeklyGoalKind.EINSTIEG -> stringResource(R.string.progress_week_goal_einstieg)
    }
    // Aufgerundet: „noch 0 km" wuerde erreicht aussehen, ist es aber nicht.
    val left = ceil(progress.remainingKm).toInt().coerceAtLeast(1)
    val leftText = pluralStringResource(R.plurals.progress_week_goal_left_count, progress.daysLeft, left, progress.daysLeft)
    return "$kindText · $leftText"
}

/**
 * Schlanker Fortschrittsbalken der Wochenkarte, optional mit [caption] darunter.
 *
 * Der Balken ist gezeichnet und damit fuer TalkBack stumm; [description]
 * liest ihn als Satz vor („Wochenziel: 42 von 60 km gefahren"). Erreicht faerbt
 * ihn gut-gruen — dieselbe Regel wie bei den Challenges.
 */
@Composable
internal fun WeeklyGoalBar(
    fraction: Float,
    reached: Boolean,
    description: String,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
            ProgressBar(
                fraction = fraction,
                color = if (reached) LocalSignalColors.current.good else MaterialTheme.colorScheme.primary,
            )
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Wochenrueckblick
// ---------------------------------------------------------------------------

/**
 * Rueckblick auf die letzte Woche, Montag bis Mittwoch unter der Hero-Karte.
 *
 * Der Montag ist der Moment, in dem man die Woche davor noch im Kopf hat —
 * und noch keine neue Zahl, an der man sich misst. Ein grosser Satz („86 km in
 * 3 Touren") und darunter hoechstens drei kleine Pillen: Aenderung zur Woche
 * davor, neue Bestwerte, laufende Serie. Pillen statt Zeilen, weil jede nur
 * ein Wort ist; sie brechen bei grosser Schrift um, statt abgeschnitten zu
 * werden. Kein Knopf: Das ist eine Auskunft, kein Angebot.
 */
@Composable
internal fun WeeklyRecapCard(recap: WeeklyRecap, modifier: Modifier = Modifier) {
    val signals = LocalSignalColors.current
    val change = recap.kmChangePct
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text(
                text = pluralStringResource(
                    R.plurals.progress_recap_headline_count,
                    recap.totals.rides,
                    whole(recap.totals.km),
                    recap.totals.rides,
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            val showPills = change != null || recap.records.isNotEmpty() || recap.streakWeeks >= 2
            if (showPills) {
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (change != null) {
                        when {
                            change > 0 -> RecapPill(
                                icon = Icons.AutoMirrored.Rounded.TrendingUp,
                                tint = signals.good,
                                text = stringResource(R.string.progress_recap_change_up, change),
                            )

                            change < 0 -> RecapPill(
                                icon = Icons.AutoMirrored.Rounded.TrendingDown,
                                tint = signals.caution,
                                text = stringResource(R.string.progress_recap_change_down, -change),
                            )

                            else -> RecapPill(
                                icon = Icons.AutoMirrored.Rounded.TrendingFlat,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = stringResource(R.string.progress_recap_change_same),
                            )
                        }
                    }
                    if (recap.records.isNotEmpty()) {
                        val names = recap.records.map { recordLabel(it) }.joinToString(", ")
                        RecapPill(
                            icon = Icons.Rounded.EmojiEvents,
                            tint = signals.accentBlue,
                            text = pluralStringResource(
                                R.plurals.progress_recap_records_count,
                                recap.records.size,
                                recap.records.size,
                                names,
                            ),
                        )
                    }
                    if (recap.streakWeeks >= 2) {
                        RecapPill(
                            icon = Icons.Rounded.LocalFireDepartment,
                            tint = signals.warning,
                            text = pluralStringResource(
                                R.plurals.progress_streak_weeks_count,
                                recap.streakWeeks,
                                recap.streakWeeks,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Eine Pille des Rueckblicks: Symbol und ein kurzer Satz auf getoenter
 * Flaeche. Eigene Fassung statt `TagPill`, weil diese Saetze bei grosser
 * Schrift in zwei Zeilen duerfen — `TagPill` schneidet bei einer Zeile ab.
 */
@Composable
private fun RecapPill(icon: ImageVector, tint: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
    }
}

// ---------------------------------------------------------------------------
// Monats-Challenges
// ---------------------------------------------------------------------------

private fun challengeIcon(kind: ChallengeKind): ImageVector = when (kind) {
    ChallengeKind.DISTANCE -> Icons.Rounded.Route
    ChallengeKind.ASCENT -> Icons.Rounded.Terrain
    ChallengeKind.ACTIVE_DAYS -> Icons.Rounded.CalendarMonth
    ChallengeKind.LONG_RIDE -> Icons.Rounded.Straighten
}

/** Name der Challenge als Aufgabe: „150 km fahren", „8 Tage aufs Rad". */
@Composable
private fun challengeName(progress: ChallengeProgress): String {
    val target = progress.challenge.target
    return when (progress.challenge.kind) {
        ChallengeKind.DISTANCE -> stringResource(R.string.progress_challenge_month_distance, whole(target))
        ChallengeKind.ASCENT -> stringResource(R.string.progress_challenge_month_ascent, whole(target))
        ChallengeKind.ACTIVE_DAYS ->
            pluralStringResource(R.plurals.progress_challenge_month_days_count, target.toInt(), target.toInt())

        ChallengeKind.LONG_RIDE -> stringResource(R.string.progress_challenge_month_long_ride, whole(target))
    }
}

/** Name des Bonus als Aufgabe: „Bonus: 160 km fahren". */
@Composable
private fun challengeBonusName(progress: ChallengeProgress): String {
    val target = progress.bonusTarget
    return when (progress.challenge.kind) {
        ChallengeKind.DISTANCE -> stringResource(R.string.progress_challenge_bonus_distance, whole(target))
        ChallengeKind.ASCENT -> stringResource(R.string.progress_challenge_bonus_ascent, whole(target))
        ChallengeKind.ACTIVE_DAYS ->
            pluralStringResource(R.plurals.progress_challenge_bonus_days_count, target.toInt(), target.toInt())

        ChallengeKind.LONG_RIDE -> stringResource(R.string.progress_challenge_bonus_long_ride, whole(target))
    }
}

/**
 * Zeile einer Challenge in der Bonus-Stufe, Bonus noch offen: Das Haekchen
 * des erreichten Ziels bleibt gruen, der Balken daneben ist blau und laeuft
 * vom Ziel zum Bonus. Eigene Zeile statt [ProgressRow], weil Symbol und
 * Balken hier zwei Farben haben.
 */
@Composable
private fun BonusProgressRow(item: ChallengeProgress, modifier: Modifier = Modifier) {
    val signals = LocalSignalColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(Icons.Rounded.CheckCircle, signals.good)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = challengeBonusName(item), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            ProgressBar(item.bonusFraction.toFloat(), signals.accentBlue)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(
                R.string.progress_challenge_month_value,
                wholeTowards(item.current, item.bonusTarget),
                whole(item.bonusTarget),
            ),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

/**
 * Die Monats-Challenges als Karte im Fortschritt-Segment des Training-Tabs.
 *
 * Eine Zeile je Challenge wie bei den Bestwerten: Symbol, Aufgabe, „x von y"
 * rechts, Balken darunter. Erreichte Zeilen zeigen ein Haekchen und die
 * Gut-Toenung — das Haekchen traegt die Aussage auch ohne Farbe. Die Fusszeile
 * nennt die Tage, die der Monat noch hat, und — falls es einen gibt — den
 * Vormonat, damit „3 von 4" Gewicht bekommt, ohne dass ein Rueckstand
 * vorgehalten wird.
 *
 * ## Bonus
 * Wer ein Ziel frueh erreicht, soll nicht bis zum Ersten vor einer vollen
 * Zeile stehen. Die Zeile bekommt deshalb ein zweites Ziel (den Bonus), das
 * Haekchen bleibt. Der Balken **setzt fort statt neu anzufangen**: Er laeuft
 * vom erreichten Ziel zum Bonus und wechselt dafuer die Farbe (blau wie bei
 * den Meilensteinen statt gut-gruen). So ist es sichtbar eine zweite Runde —
 * ein leerer gruener Balken wuerde aussehen, als waere das Ziel wieder offen
 * und der Erfolg zurueckgenommen. Ist auch der Bonus erreicht, zeigt die
 * Zeile einen Pokal und „Bonus geschafft"; Challenges ohne moeglichen Bonus
 * (Obergrenze) bleiben bei ihrem Haekchen.
 *
 * @param month ein Tag des betrachteten Monats (nur fuer den Namen).
 * @param lastMonth Bilanz des Vormonats, `null` ohne Touren darin.
 */
@Composable
internal fun ChallengesCard(
    progress: List<ChallengeProgress>,
    month: LocalDate,
    lastMonth: ChallengeMonth?,
    modifier: Modifier = Modifier,
) {
    val signals = LocalSignalColors.current
    val reachedText = stringResource(R.string.progress_challenge_month_reached_cd)
    val bonusReachedText = stringResource(R.string.progress_challenge_bonus_reached)
    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            CardTitle(
                stringResource(R.string.progress_challenges_title, LocalAppFormats.current.monthName(month)),
                Modifier.padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = 4.dp),
            )
            progress.forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                val rowModifier = Modifier.semantics(mergeDescendants = true) {
                    // „Bonus geschafft" steht in diesem Fall schon als
                    // Unterzeile da — als Zustand zusaetzlich gesetzt, laese
                    // die Sprachausgabe es doppelt.
                    if (item.reached && !(item.bonusReached && item.hasBonus)) {
                        stateDescription = reachedText
                    }
                }
                when {
                    item.hasBonus && item.bonusReached -> ProgressRow(
                        icon = Icons.Rounded.EmojiEvents,
                        tint = signals.accentBlue,
                        title = challengeBonusName(item),
                        subtitle = bonusReachedText,
                        fraction = 1f,
                        modifier = rowModifier,
                    )

                    item.hasBonus && item.tier == ChallengeTier.BONUS -> BonusProgressRow(item, rowModifier)

                    else -> ProgressRow(
                        icon = if (item.reached) Icons.Rounded.CheckCircle else challengeIcon(item.challenge.kind),
                        tint = if (item.reached) signals.good else MaterialTheme.colorScheme.primary,
                        title = challengeName(item),
                        subtitle = null,
                        fraction = item.fraction.toFloat(),
                        trailing = stringResource(
                            R.string.progress_challenge_month_value,
                            wholeTowards(item.current, item.challenge.target),
                            whole(item.challenge.target),
                        ),
                        modifier = rowModifier,
                    )
                }
            }
            val footer = listOfNotNull(
                progress.firstOrNull()?.daysLeft?.takeIf { it > 0 }?.let {
                    pluralStringResource(R.plurals.progress_challenges_days_left_count, it, it)
                },
                lastMonth?.let {
                    if (it.bonusReached > 0) {
                        pluralStringResource(
                            R.plurals.progress_challenge_bonus_last_month_count,
                            it.bonusReached,
                            it.reached,
                            it.total,
                            it.bonusReached,
                        )
                    } else {
                        stringResource(R.string.progress_challenges_last_month, it.reached, it.total)
                    }
                },
            )
            if (footer.isNotEmpty()) {
                Text(
                    text = footer.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = CardPadding, end = CardPadding, top = 4.dp, bottom = CardPadding),
                )
            }
        }
    }
}

/**
 * Die offene Challenge, die am weitesten ist — der Kandidat fuer „Als
 * Naechstes".
 *
 * Solange ein Ziel offen und noch machbar ist, kommt es zuerst. Sind alle
 * Ziele erreicht (oder die offenen nicht mehr zu schaffen), wird der offene
 * **Bonus** mit dem groessten [ChallengeProgress.bonusFraction] angeboten:
 * Wer gut im Lauf ist, soll nicht bis zum Ersten ohne naechstes Ziel
 * dastehen. Ob eine Rueckgabe ein Ziel oder ein Bonus meint, zeigt
 * [ChallengeProgress.reached] (erreicht = Bonus-Stufe). `null`, wenn auch
 * alle machbaren Boni erreicht sind.
 */
internal fun nextOpenChallenge(progress: List<ChallengeProgress>): ChallengeProgress? {
    val base = progress
        .filter { !it.reached }
        // Was sich in diesem Monat nicht mehr schaffen laesst, wird nicht
        // mehr angeboten: „noch 4 Tage aufs Rad" bei drei verbleibenden
        // Tagen waere eine Einladung zum Scheitern.
        .filterNot { it.challenge.kind == ChallengeKind.ACTIVE_DAYS && ceil(it.remaining) > it.daysLeft }
        .maxByOrNull { it.fraction }
    if (base != null) return base
    return progress
        .filter { it.reached && it.hasBonus && !it.bonusReached }
        .filterNot { it.challenge.kind == ChallengeKind.ACTIVE_DAYS && ceil(it.bonusRemaining) > it.daysLeft }
        .maxByOrNull { it.bonusFraction }
}

/**
 * „Noch 38 km bis zur Monats-Challenge 150 km" — der Titel der Zeile in
 * „Als Naechstes"; in der Bonus-Stufe „Noch 28 km bis zum Bonus 160 km". Der
 * Rest wird aufgerundet (wie bei den Meilensteinen): „Noch 0 km" sah erreicht
 * aus, ohne es zu sein.
 */
@Composable
internal fun challengeNextTitle(progress: ChallengeProgress): String {
    val bonus = progress.reached
    val remaining = ceil(if (bonus) progress.bonusRemaining else progress.remaining).coerceAtLeast(1.0)
    val target = if (bonus) progress.bonusTarget else progress.challenge.target
    return when (progress.challenge.kind) {
        ChallengeKind.DISTANCE -> stringResource(
            if (bonus) R.string.progress_challenge_bonus_next_distance else R.string.progress_next_challenge_distance,
            whole(remaining),
            whole(target),
        )

        ChallengeKind.ASCENT -> stringResource(
            if (bonus) R.string.progress_challenge_bonus_next_ascent else R.string.progress_next_challenge_ascent,
            whole(remaining),
            whole(target),
        )

        ChallengeKind.ACTIVE_DAYS -> pluralStringResource(
            if (bonus) R.plurals.progress_challenge_bonus_next_days_count else R.plurals.progress_next_challenge_days_count,
            remaining.toInt(),
            remaining.toInt(),
        )

        ChallengeKind.LONG_RIDE -> stringResource(
            if (bonus) R.string.progress_challenge_bonus_next_long_ride else R.string.progress_next_challenge_long_ride,
            whole(remaining),
            whole(target),
        )
    }
}

/** Symbol der Challenge-Zeile in „Als Naechstes". */
internal fun challengeNextIcon(kind: ChallengeKind): ImageVector = challengeIcon(kind)
