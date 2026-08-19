package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.formatDateShort
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.PlanSessionProgress
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.Ride
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.canGenerateRouteFor
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.planSessionStatusLabels
import de.trailscape.core.weekKindLabels
import de.trailscape.core.weekKm
import de.trailscape.core.weekSessionProgress
import kotlin.math.roundToInt

/**
 * Titelzeile plus eine Karte je Trainingswoche.
 *
 * Port von `_buildPlanWeeks` (`lib/screens/training_screen.dart`). Bewusst
 * ohne `_EntranceFade`-Aequivalent: die Wochenliste steht in derselben
 * `LazyColumn` wie die uebrigen Karten von [de.trailscape.app.ui.training.TrainingScreen]
 * und wuerde beim Scrollen recycelt — dieselbe Begruendung, mit der
 * `ui/rides/TourList.kt` das gestaffelte Einblenden bereits wegliess.
 */
@Composable
fun PlanHeader(plan: TrainingPlan) {
    Text(
        text = "${plan.goal.name} – ${formatKmDe(plan.goal.distanceKm)} km am " +
            formatDate(plan.goal.date),
        style = MaterialTheme.typography.titleMedium,
    )
}

/**
 * Hinweiszeile ueber den Wochenkarten, wenn der angezeigte Plan von
 * [de.trailscape.core.adaptPlan] an die gefahrene Realitaet angepasst wurde.
 * Der gespeicherte Plan bleibt unveraendert — genau deshalb muss die
 * Oberflaeche sagen, warum hier andere Zahlen stehen als beim Erstellen.
 */
@Composable
fun PlanAdaptionNote(reason: String) {
    NoticeBox(
        icon = Icons.Filled.Info,
        color = LocalSignalColors.current.caution,
        title = "Plan an deine letzten Wochen angepasst",
        text = reason,
    )
}

/**
 * @param onPlanRoute sucht zu einer Einheit eine passende Runde (siehe
 *   `ui/map/RouteGenerationSheet.kt`). Der Knopf steht bewusst nur an den
 *   Einheiten der **laufenden** Woche: Fuer eine Einheit in vier Wochen ist
 *   eine heute berechnete Runde wertlos, und die Zeile bliebe ueberladen.
 * @param rideLoads Last je Tour-ID (aus `insights.rideLoads`) — verfeinert die
 *   Status-Zuordnung der Einheiten; ohne sie entscheidet die Distanz allein.
 */
@Composable
fun PlanWeekCard(
    week: TrainingWeek,
    plan: TrainingPlan,
    rides: List<Ride>,
    onPlanRoute: ((TrainingSession) -> Unit)? = null,
    rideLoads: Map<String, Double> = emptyMap(),
) {
    val theme = MaterialTheme.colorScheme
    val activeIndex = currentWeekIndex(plan)
    val isCurrent = week.index == activeIndex
    val isPastOrCurrent = week.index <= activeIndex
    val ridden = if (isPastOrCurrent) weekKm(week, rides) else 0.0
    val progress = if (week.targetKm > 0) (ridden / week.targetKm).toFloat().coerceIn(0f, 1f) else 0f
    val kindColor = weekKindColor(week.kind)
    // Erledigt-Status je Einheit — nur fuer Wochen, die schon laufen oder
    // vorbei sind; fuer kuenftige Wochen ist „offen" keine Auskunft.
    val sessionProgress: Map<TrainingSession, PlanSessionProgress> = if (isPastOrCurrent) {
        weekSessionProgress(week, rides, rideLoads = rideLoads).associateBy { it.session }
    } else {
        emptyMap()
    }

    Card(
        colors = if (isCurrent) {
            // Solide Tonflaeche statt halbtransparenter Kopie: Eine Flaeche,
            // deren Erscheinung vom Untergrund abhaengt, ist keine Flaeche.
            CardDefaults.cardColors(containerColor = theme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Woche ${week.index + 1} · " +
                        "${formatDateShort(week.start)}–${formatDateShort(week.end)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // Wochentyp-Marke: dieselbe Pille wie der Fitnesslevel-Chip
                // (`TagPill`) — getoente Flaeche, Text in der Vollfarbe.
                TagPill(
                    text = weekKindLabels.getValue(week.kind),
                    containerColor = kindColor.copy(alpha = 0.15f),
                    contentColor = kindColor,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isPastOrCurrent) {
                    "${formatKmDe(ridden)} von ${week.targetKm} km"
                } else {
                    "Ziel: ${week.targetKm} km"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Kopfzeile (Tag, Titel, Distanz, Planungs-Knopf) und darunter die
            // Beschreibung in voller Breite. Titel und Beschreibung duerfen
            // NICHT in einer Zeile stehen: die Beschreibung bekaeme nur die
            // Restbreite neben Distanz und Knopf und braeche pro Wort um.
            for (session in week.sessions) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    WeekdayLabel(session.day)
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                // Ziel-Last neben den Kilometern, wenn der Plan
                                // sie kennt — alte Plaene ohne `targetLoad`
                                // zeigen weiterhin nur die Distanz.
                                text = session.targetLoad
                                    ?.let { "${session.targetKm} km · Last ${it.roundToInt()}" }
                                    ?: "${session.targetKm} km",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            sessionProgress[session]?.let { entry ->
                                SessionStatusIcon(entry.status)
                            }
                            // Am Zielevent gibt es nichts zu generieren — die
                            // Strecke steht schon (siehe canGenerateRouteFor).
                            if (isCurrent && onPlanRoute != null &&
                                canGenerateRouteFor(session)
                            ) {
                                // Ohne Groessenangabe: `IconButton` bringt die
                                // 48-dp-Mindestflaeche von Material selbst mit.
                                // `Modifier.size(32.dp)` unterlief sie — bei
                                // einem Knopf, der in einer eng gesetzten
                                // Einheitenzeile neben zwei Textspalten sitzt
                                // und deshalb erst recht getroffen werden will.
                                IconButton(onClick = { onPlanRoute(session) }) {
                                    Icon(
                                        Icons.Filled.Route,
                                        // "Runde", nicht "Route": Das Ergebnis ist ein generierter
                                        // Rundkurs (siehe KDoc oben und `RouteGenerationSheet.kt`,
                                        // das denselben Vorschlag ebenfalls "Runde" nennt) — Route
                                        // meint in dieser App eine geplante Strecke von A nach B.
                                        contentDescription = "Passende Runde für „${session.title}“ planen",
                                        tint = theme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = session.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Status-Marke einer Einheit: gruener Haken (erledigt), gelber Haken
 * (teilweise), gedaempftes Kreuz (verpasst). „Offen" bekommt bewusst keine
 * Marke — eine Zeile ohne Symbol *ist* die offene Einheit, und ein viertes
 * Symbol wuerde die Ausnahmen verwaessern. Ein Kalendertag Toleranz und die
 * 60-%-Schwelle stecken in `:core` ([weekSessionProgress]).
 */
@Composable
private fun SessionStatusIcon(status: PlanSessionStatus) {
    val signals = LocalSignalColors.current
    val (icon, tint) = when (status) {
        PlanSessionStatus.OFFEN -> return
        PlanSessionStatus.ERLEDIGT -> Icons.Filled.CheckCircle to signals.good
        PlanSessionStatus.TEILWEISE -> Icons.Filled.CheckCircle to signals.caution
        PlanSessionStatus.VERPASST ->
            Icons.Filled.Cancel to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Spacer(modifier = Modifier.width(6.dp))
    Icon(
        icon,
        contentDescription = planSessionStatusLabels.getValue(status),
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}
