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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import de.trailscape.app.ui.theme.LocalSignalColors
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
 * ## Eine Karte, eine Aussage
 * Bis hierher stapelte die Karte bis zu drei gleichgeformte, farbig hinterlegte
 * Hinweisbloecke uebereinander: die Empfehlung, die Abweichungsbegruendung und
 * (ohne Daten) der Uhren-Hinweis — drei Flaechen, die um denselben Blick
 * konkurrierten, obwohl nur eine der Kern der Karte ist. Empfehlung und
 * Abweichung stehen jetzt als **ein** ungerahmter Textblock: Titel der
 * Empfehlung, darunter genau ein Satz — der [TodayRoute.note] der Abweichung,
 * wenn es eine gibt, sonst [de.trailscape.core.DailyRecommendation.detail].
 * Zwei Saetze zur selben Sache waeren die alte Doppelung nur kleiner gefasst.
 * Der Erklaersatz zum Score ([de.trailscape.core.Readiness.detail]) entfaellt
 * hier komplett: Er wiederholt nur, was der Trainings-Tab (`VitalsCard`) je
 * Signal bereits mit eigener Ampel zeigt, und diese Karte ist die Antwort auf
 * „was fahre ich heute", nicht die Diagnose dahinter.
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

            // Empfehlung und Abweichung als EIN Textblock, ohne NoticeBox-
            // Rahmen: Titel der Empfehlung, darunter genau ein erklaerender
            // Satz. Steht [TodayRoute.note], TRAEGT er diesen Satz — er nennt
            // ohnehin beide Zahlen und den Grund (siehe [TodayRoute]) und ist
            // damit die genauere Aussage; ohne Abweichung faellt die Karte auf
            // `recommendation.detail` zurueck. Zwei Saetze nebeneinander waeren
            // dieselbe Doppelung, die diese Karte gerade abgelegt hat.
            Text(
                text = recommendation.title,
                style = MaterialTheme.typography.titleSmall,
                color = color,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = todayRoute.note ?: recommendation.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.onSurfaceVariant,
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

            // Ein Satz statt zwei: Was die Uhr liefert, steht schon im
            // Mehr-Tab selbst — hier reicht der Anstoss, dorthin zu tippen.
            if (showHealthHint) {
                Spacer(modifier = Modifier.height(12.dp))
                NoticeBox(
                    icon = Icons.Filled.Watch,
                    color = theme.onSurfaceVariant,
                    text = "Verbinde eine Uhr im Mehr-Tab, um hier deine Tagesbereitschaft zu sehen.",
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
 * Hinweis, wenn der Plan sein eigenes Ziel nicht traegt.
 *
 * ## Warnung oder Auskunft?
 * Bis hierher stand hier Fehlerrot mit Warndreieck unter der Frage „Trägt dein
 * Plan?" — und das an jedem einzelnen Tag, an dem die Zieldistanz das Volumen
 * des Plans uebersteigt, also potenziell wochenlang. Fehlerrot ist die Farbe,
 * die diese App sonst fuer akute, handlungsbeduerftige Zustaende reserviert
 * (Ruhetag, abgebrochene Aufzeichnung); hier ist nichts akut — der Plan laeuft
 * unveraendert weiter, er traegt nur ein kuerzeres Ziel als eingetragen. Eine
 * Frage im Titel unterstellt zudem eine Unsicherheit, die die App gar nicht
 * hat: Sie hat die Antwort schon berechnet. Titel deshalb als Feststellung,
 * Farbe die mildere `caution`-Stufe (dieselbe wie ein Deload-Hinweis), Icon ein
 * schlichtes Info-Zeichen statt des Warndreiecks.
 *
 * ## Zahlen statt Fliesstext
 * [PlanFeasibility] traegt die Distanzen bereits als eigene Felder
 * ([PlanFeasibility.longestRideKm], [PlanFeasibility.goalDistanceKm],
 * [PlanFeasibility.suggestedDistanceKm]) — [PlanFeasibility.message] schreibt
 * exakt dieselben Zahlen nur in einen Absatz mit Prozentangabe um. Eine
 * kompakte Zahlenzeile im Stil von [Fact] sagt dasselbe auf einen Blick statt
 * in einem Satz zum Lesen. Der zweite, erlaeuternde Absatz „Der Plan bleibt
 * gültig …" entfaellt: Das sagt jetzt der Knopf „Ziel anpassen", nicht mehr
 * ein zweiter Text daneben.
 *
 * ## Quittierung
 * „Verstanden" ruft [onAcknowledge] — `TodayScreen` bindet das an
 * `AppViewModel.acknowledgePlanFeasibility` und blendet die Karte danach fuer
 * genau diesen Plan aus. Ohne dieses Gedaechtnis kaeme der Hinweis bei jedem
 * App-Start wieder, obwohl niemand am Plan etwas geaendert hat — ein neuer
 * oder veraenderter Plan (andere Zieldistanz, anderes Zieldatum, andere
 * Laufzeit) traegt einen anderen Schluessel und zeigt die Karte automatisch
 * wieder.
 */
@Composable
internal fun PlanFeasibilityCard(
    feasibility: PlanFeasibility,
    onAdjustGoal: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val cautionColor = LocalSignalColors.current.caution
    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = cautionColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Plan und Ziel passen nicht zusammen",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Fact(
                    label = "Längste Fahrt",
                    value = "${feasibility.longestRideKm} km",
                    compact = true,
                )
                Fact(
                    label = "Ziel",
                    value = "${formatKmDe(feasibility.goalDistanceKm)} km",
                    compact = true,
                )
                feasibility.suggestedDistanceKm?.let { suggested ->
                    Fact(
                        label = "Trägt bis",
                        value = "$suggested km",
                        compact = true,
                        valueColor = cautionColor,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAdjustGoal) { Text("Ziel anpassen") }
                TextButton(onClick = onAcknowledge) { Text("Verstanden") }
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
            // Dauer und Ziel-Last nur, wenn der Plan sie kennt: Plaene aus der
            // Zeit vor `TrainingSession.durationMin`/`targetLoad` tragen
            // keine, und eine hier hergeleitete Zahl waere eine zweite
            // Wahrheit neben der, mit der die Einheit erzeugt wurde.
            Text(
                text = buildString {
                    append("${session.targetKm} km")
                    session.durationMin?.let { append(" · ca. $it min") }
                    session.targetLoad?.let { append(" · Last ${it.roundToInt()}") }
                },
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
 * Der Knopf loest [de.trailscape.app.ui.AppViewModel.requestRecording] aus —
 * er startet die Aufzeichnung nicht selbst, sondern reicht die Bitte an den
 * Karten-Tab weiter. Das ist Absicht: Der Start haengt an Standort- und (ab
 * Android 13) Benachrichtigungs-Berechtigung, die `ui/map/MapScreen.kt` mit
 * eigenen Launchern einholt, bevor es
 * [de.trailscape.app.record.RecordingRepository.start] ruft. Ein zweiter
 * Startpfad hier haette diese Berechtigungslogik entweder verdoppelt oder
 * umgangen; so gibt es nur den einen, und der Nutzer sieht davon nichts
 * ausser dem einen Tab-Wechsel — die Berechtigungsfrage erscheint dort, wo
 * sie ohnehin hingehoert. Ein Erklaertext dazu erledigt sich damit von
 * selbst: Der Knopf verspricht „Aufzeichnung starten" und tut genau das.
 */
@Composable
internal fun RecordPromptCard(onStartRecording: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Aufzeichnung", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onStartRecording,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Aufzeichnung starten")
            }
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
