package de.trailscape.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.training.readinessBandColor
import de.trailscape.core.Ride
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.formatDuration
import de.trailscape.core.readinessBandLabels
import de.trailscape.core.weekKindLabels
import kotlin.math.roundToInt

/**
 * Die Karten der Startseite „Heute". Reine Darstellung — welche davon
 * ueberhaupt erscheint, entscheidet [TodayScreen].
 *
 * Farben, Abstaende und Formate kommen ausnahmslos aus `ui/theme/`,
 * `ui/UiFormat.kt` und `:core`; die Ampelfarbe der Bereitschaft teilt sich diese
 * Seite mit dem Trainings-Tab ([readinessBandColor]), damit derselbe Wert nicht
 * an zwei Stellen unterschiedlich eingefaerbt wird.
 */

/**
 * Das Herzstueck: Bereitschaft, heutige Einheit und der Weg zur passenden
 * Runde.
 *
 * @param session die heute geplante Einheit, oder `null` (Ruhetag im Plan bzw.
 *   gar kein Plan).
 * @param showHealthHint zeigt **einmal** — genau hier, nicht in jeder Karte —
 *   den Hinweis, dass eine Uhr mit Health-Connect-Anbindung die Bereitschaft
 *   freischaltet. Er erscheint nur, wenn ueberhaupt kein Erholungssignal
 *   vorliegt: Wer eine Uhr angebunden hat und nur noch Tage sammelt, braucht
 *   keine Kaufberatung, sondern Geduld.
 * @param onPlanRoute loest die bestehende Routengenerierung aus
 *   (`AppViewModel.requestRouteGeneration`, danach uebernimmt der Karten-Tab).
 *   `null` an einem Ruhetag: `:core` liefert dann bewusst kein Routenziel, und
 *   ein Angebot zur Ausfahrt waere der falsche Rat.
 */
@Composable
internal fun TodayRecommendationCard(
    insights: TrainingInsights,
    session: TrainingSession?,
    showHealthHint: Boolean,
    onPlanRoute: (() -> Unit)?,
    onOpenHealth: () -> Unit,
) {
    val theme = MaterialTheme.colorScheme
    val readiness = insights.readiness
    val recommendation = insights.recommendation
    val color = if (readiness.available) readinessBandColor(readiness.band) else theme.primary

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Tagesempfehlung", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            // Ohne Gesundheitsdaten wird der Wert weggelassen statt leer
            // angezeigt: Eine „—"-Zahl waere eine Aussage ueber den Nutzer, die
            // niemand getroffen hat.
            if (readiness.available) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = readiness.score.roundToInt().toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Erholung (0–100)",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.onSurfaceVariant,
                        )
                        Text(
                            text = readinessBandLabels.getValue(readiness.band),
                            style = MaterialTheme.typography.titleSmall,
                            color = color,
                        )
                    }
                }
                // Der Satz zum Wert („woraus sich der Score speist") bleibt
                // erhalten; die Einzelbegruendungen der Empfehlung
                // (`recommendation.reasons`) nicht — sie stehen als Messwert
                // samt Ampel im Trainings-Tab (`VitalsCard`) und wuerden diese
                // Karte in eine Diagnose verwandeln.
                Text(
                    text = readiness.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            NoticeBox(
                icon = Icons.Filled.Favorite,
                color = color,
                title = recommendation.title,
                text = recommendation.detail,
            )

            if (session != null) {
                Spacer(modifier = Modifier.height(12.dp))
                PlannedSessionBlock(session)
            }

            if (onPlanRoute != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onPlanRoute, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Filled.Route,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Passende Runde planen")
                }
            }

            if (showHealthHint) {
                Spacer(modifier = Modifier.height(12.dp))
                NoticeBox(
                    icon = Icons.Filled.Watch,
                    color = theme.onSurfaceVariant,
                    text = "Mit einer Uhr, die Ruhepuls, HRV und Schlaf nach Health Connect " +
                        "schreibt, wird hier zusätzlich deine Tagesbereitschaft angezeigt. " +
                        "Verbinden lässt sie sich im Mehr-Tab.",
                    modifier = Modifier.clickable(onClick = onOpenHealth),
                )
            }
        }
    }
}

/**
 * Die heute geplante Einheit: Titel, Zielkilometer, Beschreibung.
 *
 * Titel und Beschreibung stehen bewusst untereinander und nicht in einer Zeile
 * — dieselbe Begruendung wie im Plan des Trainings-Tabs: Neben Titel und
 * Distanz bliebe der Beschreibung nur eine Restspalte, in der sie pro Wort
 * umbraeche.
 */
@Composable
private fun PlannedSessionBlock(session: TrainingSession) {
    val theme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Heute im Plan",
            style = MaterialTheme.typography.labelSmall,
            color = theme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${session.targetKm} km",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            text = session.description,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.onSurfaceVariant,
        )
    }
}

/**
 * Der zweite deutliche Weg von dieser Seite weg: aufzeichnen.
 *
 * Der Knopf **wechselt nur** in den Karten-Tab und startet die Aufzeichnung
 * nicht selbst. Das ist Absicht: Der Start haengt an Standort- und (ab
 * Android 13) Benachrichtigungs-Berechtigung, die `ui/map/MapScreen.kt` mit
 * eigenen Launchern einholt, bevor es
 * [de.trailscape.app.record.RecordingRepository.start] ruft. Ein zweiter
 * Startpfad haette diese Berechtigungslogik entweder verdoppelt oder umgangen
 * — beides schlechter als ein Klick mehr. Beschriftung und Hinweis sagen
 * deshalb genau das, was passiert.
 */
@Composable
internal fun RecordPromptCard(onOpenMap: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Aufzeichnung", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Karte öffnen zum Aufzeichnen")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Die Aufzeichnung startest du auf der Karte mit dem grünen Knopf unten " +
                    "rechts — dort wird auch nach der Standortfreigabe gefragt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Wochenfortschritt gegen das Ziel der laufenden Planwoche.
 *
 * Bewusst nur Kilometer — Last, Zielwert und Deload-Empfehlung bleiben im
 * Trainings-Tab (`WeekCard`). Auf der Startseite zaehlt die eine Zahl, die man
 * ohne Erklaerung versteht.
 *
 * @param riddenKm bereits gefahrene Kilometer dieser Woche (`:core`: `weekKm`).
 */
@Composable
internal fun WeekProgressCard(week: TrainingWeek, weekCount: Int, riddenKm: Double) {
    val theme = MaterialTheme.colorScheme
    val progress = if (week.targetKm > 0) {
        (riddenKm / week.targetKm).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Diese Woche", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (weekCount > 0) {
                    "Woche ${week.index + 1} von $weekCount · ${weekKindLabels.getValue(week.kind)}"
                } else {
                    weekKindLabels.getValue(week.kind)
                },
                style = MaterialTheme.typography.bodySmall,
                color = theme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${formatKmDe(riddenKm)} von ${week.targetKm} km",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Kurzfassung der juengsten Tour; ein Tipp fuehrt in den Touren-Tab.
 *
 * Die Tour wird dabei **nicht** ausgewaehlt: Eine Auswahl oeffnet sie im
 * Karten-Tab (siehe `AppViewModel.selectedRide`), und wer hier tippt, will die
 * Liste sehen, nicht nebenbei den Kartenzustand veraendern.
 */
@Composable
internal fun LastRideCard(ride: Ride, onOpenRides: () -> Unit) {
    val theme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenRides),
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Letzte Tour", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ride.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatDate(ride.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                }
                // Dekorativ: Die ganze Karte ist bedienbar und traegt ihren
                // Namen als Beschriftung.
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = theme.onSurfaceVariant,
                )
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RideFigure("Distanz", "${formatKmDe(ride.stats.distanceKm)} km")
                RideFigure("Dauer", formatDuration(ride.stats.durationS))
                RideFigure("Höhenmeter", "${ride.stats.ascentM.roundToInt()} hm")
                ride.stats.avgHrBpm?.let { RideFigure("Ø Puls", "$it bpm") }
            }
        }
    }
}

/** Eine Kennzahl der Tour: kleines Label, darunter der Wert. */
@Composable
private fun RideFigure(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
