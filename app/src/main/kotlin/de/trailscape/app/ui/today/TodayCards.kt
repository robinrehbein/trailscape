package de.trailscape.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.training.readinessBandColor
import de.trailscape.core.PlanFeasibility
import de.trailscape.core.Ride
import de.trailscape.core.RouteTarget
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.ascentPreferenceLabels
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
 *
 * One UI: Die Karten erben Rundung (26 dp) und Kartenfarbe (`surfaceContainerLow`,
 * weiss bzw. fast schwarz) vom Theme — keine eigene Form, keine eigene Flaeche.
 * Die grossen Zahlen (Bereitschaftswert, Wochenkilometer, Tourenkennzahlen)
 * laufen in den fetten Headline-Slots des Schriftsystems; ein Gewicht wird
 * nirgends dazugeschrieben.
 */

/**
 * Das Herzstueck: Bereitschaft, heutige Einheit und der Weg zur passenden
 * Runde.
 *
 * ## Die Karte darf sich nicht selbst widersprechen
 * Frueher stand hier oben „Locker in Z2, 60–90 min" und direkt darunter
 * „Heute im Plan · Lange Tour · 90 km", waehrend der Knopf 90 km **bergig**
 * erzeugte. Drei Aussagen, drei Richtungen. Jetzt kommt alles aus derselben
 * Entscheidung ([TodayRoute] aus `:core`): Wurde heruntergestuft, nennt die
 * Karte beide Zahlen und den Grund, und der Knopf traegt genau die Distanz, die
 * er auch erzeugt.
 *
 * @param todayRoute Ergebnis von [de.trailscape.core.decideTodayRoute] —
 *   Routenziel, geplante Einheit und der erklaerende Satz in einem.
 * @param showHealthHint zeigt **einmal** — genau hier, nicht in jeder Karte —
 *   den Hinweis, dass eine Uhr mit Health-Connect-Anbindung die Bereitschaft
 *   freischaltet. Er erscheint nur, wenn ueberhaupt kein Erholungssignal
 *   vorliegt: Wer eine Uhr angebunden hat und nur noch Tage sammelt, braucht
 *   keine Kaufberatung, sondern Geduld.
 * @param onPlanRoute loest die bestehende Routengenerierung aus
 *   (`AppViewModel.requestRouteGeneration`, danach uebernimmt der Karten-Tab).
 *   `null` an einem Ruhetag und am Zieltag: `:core` liefert dann bewusst kein
 *   Routenziel, und ein Angebot zur Ausfahrt waere der falsche Rat.
 */
@Composable
internal fun TodayRecommendationCard(
    insights: TrainingInsights,
    todayRoute: TodayRoute,
    showHealthHint: Boolean,
    onPlanRoute: (() -> Unit)?,
    onOpenHealth: () -> Unit,
) {
    val theme = MaterialTheme.colorScheme
    val readiness = insights.readiness
    val recommendation = insights.recommendation
    val color = if (readiness.available) readinessBandColor(readiness.band) else theme.primary
    val session = todayRoute.session

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
            } else {
                // Ohne Gesamtwert bleibt die Karte nicht stumm: `:core` sagt
                // genau, WORAN es noch fehlt (Ruhepuls-Baseline, Schlaf,
                // Trainingshistorie). Ohne diesen Satz sieht jemand, der seine
                // Uhr gerade verbunden hat, wochenlang ueberhaupt nichts und
                // haelt die Verbindung fuer kaputt.
                readiness.unavailableReason?.let { grund ->
                    Text(
                        text = grund,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
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

            // Der Satz zur Abweichung — er gehoert zwischen Plan und Knopf,
            // weil er genau erklaert, warum der Knopf gleich eine andere Zahl
            // traegt als die Zeile darueber.
            todayRoute.note?.let { note ->
                Spacer(modifier = Modifier.height(12.dp))
                NoticeBox(
                    icon = Icons.Filled.Route,
                    color = color,
                    text = note,
                )
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
                    // Die Beschriftung nennt, was der Knopf erzeugt. Ohne die
                    // Zahl blieb offen, ob er die Plandistanz oder die
                    // heruntergestufte nimmt — und er nahm bis vor Kurzem
                    // wortlos die falsche.
                    Text(
                        text = todayRoute.target
                            ?.let { "Passende Runde planen · ${routeButtonSuffix(it)}" }
                            ?: "Passende Runde planen",
                    )
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

/** „55 km flach" — was der Knopf gleich erzeugt. */
private fun routeButtonSuffix(target: RouteTarget): String =
    "${formatKmDe(target.distanceKm)} km " +
        ascentPreferenceLabels.getValue(target.ascentPreference).lowercase()

/**
 * Warnung, wenn der Plan sein eigenes Ziel nicht traegt.
 *
 * Bis hierher gab es sie nicht: Ein Einsteiger mit dem Ziel „200 km in 12
 * Wochen" bekam einen Plan, dessen laengste Fahrt 45 km war, las „Plan mit 12
 * Wochen erstellt." und hielt ihn fuer tragfaehig. Der Text nennt deshalb drei
 * Dinge, nicht nur das Problem: was der Plan hergibt, welches Ziel er truege
 * und wie lange das gewuenschte braeuchte — der Rat ist die Haelfte der
 * Auskunft.
 */
@Composable
internal fun PlanFeasibilityCard(feasibility: PlanFeasibility) {
    val theme = MaterialTheme.colorScheme
    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Trägt dein Plan?", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            NoticeBox(
                icon = Icons.Filled.Warning,
                color = theme.error,
                text = feasibility.message ?: "",
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Der Plan bleibt gültig – er bereitet dich nur auf eine kürzere Distanz " +
                    "vor, als du eingetragen hast. Ein neues Ziel legst du im Trainings-Tab an.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onSurfaceVariant,
            )
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
            // Dauer nur, wenn der Plan sie kennt: Plaene aus der Zeit vor
            // `TrainingSession.durationMin` tragen keine, und eine hier
            // hergeleitete Zahl waere eine zweite Wahrheit neben der, mit der
            // die Einheit erzeugt wurde.
            Text(
                text = session.durationMin
                    ?.let { "${session.targetKm} km · ca. $it min" }
                    ?: "${session.targetKm} km",
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
            Button(
                onClick = onOpenMap,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
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
                style = MaterialTheme.typography.headlineSmall,
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

/** Eine Kennzahl der Tour — dieselbe Grammatik wie ueberall ([Fact]). */
@Composable
private fun RideFigure(label: String, value: String) {
    Fact(label = label, value = value)
}
