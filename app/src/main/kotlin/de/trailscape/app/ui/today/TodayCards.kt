package de.trailscape.app.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.CoachCard
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.training.formatSigned
import de.trailscape.app.ui.training.readinessBandColor
import de.trailscape.app.ui.training.tsbBandColor
import de.trailscape.core.PlanFeasibility
import de.trailscape.core.RideInfo
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
 * Die Bausteine der Startseite „Heute" nach der Zielgestaltung aus
 * `docs/design/prototyp-eine-leiste.html` (Screen „Heute"). Reine Darstellung —
 * welcher Baustein ueberhaupt erscheint, entscheidet [TodayScreen].
 *
 * ## Die Reihenfolge ist die Aussage
 * Der Prototyp setzt die Seite als **eine Auskunft in fuenf Stufen**, nicht als
 * Kartenstapel gleichrangiger Themen:
 *
 *  1. [ReadinessCard] — der Ring mit der Bereitschaft **neben** dem Coach-Satz
 *     und der Einheitszeile. Eine Flaeche, ein Blick.
 *  2. [BuildRouteButton] — der eine volle Primaerknopf der Seite, bewusst
 *     **ausserhalb** der Karte: Er ist die Antwort auf alles darueber, nicht
 *     eine Zeile darin.
 *  3. [TodayCockpitRow] — drei Zahlen ohne Karte drumherum. Wochenkilometer,
 *     Form, Planwoche stehen auf blankem Grund, weil eine Karte um sie herum
 *     behaupten wuerde, sie seien ein eigenes Thema; sie sind nur der
 *     Randbericht zur Auskunft darueber.
 *  4. [CoachCard] mit der Tagesbegruendung — Akzentflaeche, weil hier jemand
 *     spricht statt etwas angezeigt wird.
 *  5. [PlanOutlookCard] — der Ausblick, und zugleich der Weg in den
 *     Trainings-Tab.
 *
 * ## Was von der Vorgaengerfassung geblieben ist
 * Bis auf eine Karte wurde nichts weggelassen, alles umgruppiert. Die
 * frueheren Karten „Tagesempfehlung" (Titel, Erklaersatz, Bereitschaft,
 * geplante Einheit, Uhren-Hinweis) und „Diese Woche" (Wochenfortschritt,
 * Wochentyp) sind in die fuenf Stufen aufgegangen: Der Wochenfortschritt steckt
 * jetzt in der Zahlenzeile **und** — mit Balken und Wochentyp — in
 * [PlanOutlookCard], der Erklaersatz unter dem Ring, die Planeinheit als
 * Einheitszeile daneben. „Plan und Ziel passen nicht zusammen"
 * ([PlanFeasibilityCard]) und „Letzte Tour" ([LastRideCard]) sind unveraendert
 * eigene Karten — sie kommen im Prototyp nicht vor, weil er den Regelfall
 * zeigt, und ordnen sich hier unter die fuenf Stufen.
 *
 * ## Die Karte „Aufzeichnung" ist **entfallen**
 * Sie bestand aus einer Ueberschrift und einem vollbreiten Knopf „Aufzeichnung
 * starten" — und stand damit auf demselben Bildschirm wie der schwebende
 * ●-Knopf neben der Navigationskapsel, der seit der Fuehrung „Eine Leiste"
 * genau diese eine Aufgabe hat (siehe `ui/components/RecCapsuleButton.kt` und
 * `ui/TrailscapeApp.kt`). Zwei Startknoepfe fuer dieselbe Fahrt, einer davon
 * mitten im Lesefluss der Tagesauskunft: Das ist die Doppelung, wegen der der
 * ●-Knopf ueberhaupt aus der Leiste herausgeloest wurde — Fahren ist ein
 * Zustand, kein Listeneintrag. Verloren geht dabei nichts: Der ●-Knopf ist auf
 * jedem Tab sichtbar, also auch hier, und er kann mehr als der Knopf in der
 * Karte (Route bereit, laufende Aufzeichnung, Zeitanzeige).
 *
 * Der Weg ins Aufzeichnen bleibt zusaetzlich im Leerzustand des Erststarts
 * (`FirstRideState` in `TodayScreen.kt`) — dort erklaert er, was als Naechstes
 * zu tun ist, statt eine Dauerkarte zu sein.
 *
 * One UI: Karten erben Rundung (26 dp) und Flaeche vom Theme — kein eigener
 * Radius, kein eigener Farbwert. Die Ampelfarbe der Bereitschaft teilt sich
 * diese Seite mit dem Trainings-Tab ([readinessBandColor]), die Formfarbe mit
 * dessen Kennzahlen ([tsbBandColor]).
 */

/**
 * Stufe 1: Bereitschaftsring neben Coach-Satz und Einheitszeile.
 *
 * ## Warum der Ring
 * Der Wert stand bis hierher als nackte `displaySmall`-Zahl mit der Beschriftung
 * „Erholung (0–100)" daneben — eine Zahl, deren Skala man mitlesen musste. Der
 * Ring *zeigt* die Skala: Der gefuellte Bogen ist der Anteil an 100, die Zahl
 * darin der Wert, „bereit" darunter, wofuer er steht. Fuer die
 * Bildschirmvorlesung bleibt die vollstaendige Auskunft erhalten — sie liest
 * „Erholung 82 von 100 — bereit für eine harte Einheit" (siehe
 * [ReadinessRing]), also mehr, als je auf dem Schirm stand.
 *
 * ## Die Karte darf sich nicht selbst widersprechen
 * Unveraendert gueltig: Alles kommt aus derselben Entscheidung ([TodayRoute]
 * aus `:core`). Wurde heruntergestuft, nennt die Karte beide Zahlen und den
 * Grund ([TodayRoute.note]); ohne Abweichung steht dort
 * [de.trailscape.core.DailyRecommendation.detail]. Zwei Saetze zur selben Sache
 * waeren die Doppelung, die diese Karte laengst abgelegt hat.
 *
 * @param todayRoute Ergebnis von [de.trailscape.core.decideTodayRoute] —
 *   Routenziel, geplante Einheit und der erklaerende Satz in einem.
 * @param showHealthHint zeigt **einmal** — genau hier, nicht in jeder Karte —
 *   den Hinweis, dass eine Uhr mit Health-Connect-Anbindung die Bereitschaft
 *   freischaltet. Er erscheint nur, wenn ueberhaupt kein Erholungssignal
 *   vorliegt: Wer eine Uhr angebunden hat und nur noch Tage sammelt, braucht
 *   keine Kaufberatung, sondern Geduld.
 */
@Composable
internal fun ReadinessCard(
    insights: TrainingInsights,
    todayRoute: TodayRoute,
    showHealthHint: Boolean,
    onOpenHealth: () -> Unit,
) {
    val theme = MaterialTheme.colorScheme
    val readiness = insights.readiness
    val recommendation = insights.recommendation
    val bandLabel = readinessBandLabels.getValue(readiness.band)
    val color = if (readiness.available) readinessBandColor(readiness.band) else theme.primary
    val session = todayRoute.session

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Ohne Gesundheitsdaten bleibt der Ring weg statt leer zu
                // stehen: Ein Bogen bei 0 % waere eine Aussage ueber den
                // Nutzer, die niemand getroffen hat. Der Grund dafuer steht
                // dann unter dem Satz.
                if (readiness.available) {
                    ReadinessRing(
                        score = readiness.score.roundToInt(),
                        bandLabel = bandLabel,
                        color = color,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = recommendation.title, style = MaterialTheme.typography.titleMedium)
                    if (readiness.available) {
                        Text(
                            text = bandLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                    }
                    session?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = plannedUnitLine(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Ohne Gesamtwert bleibt die Karte nicht stumm: `:core` sagt genau,
            // WORAN es noch fehlt (Ruhepuls-Baseline, Schlaf,
            // Trainingshistorie). Ohne diesen Satz sieht jemand, der seine Uhr
            // gerade verbunden hat, wochenlang ueberhaupt nichts und haelt die
            // Verbindung fuer kaputt.
            if (!readiness.available) {
                readiness.unavailableReason?.let { grund ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = grund,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = todayRoute.note ?: recommendation.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.onSurfaceVariant,
            )

            // Die Beschreibung der Planeinheit steht in voller Breite unter der
            // Zeile, nicht neben dem Ring — dieselbe Begruendung wie im Plan des
            // Trainings-Tabs: In der Restspalte neben einem 92-dp-Ring braeche
            // sie pro Wort um.
            session?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Eyebrow(text = "Heute im Plan")
                Text(
                    text = it.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
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

/**
 * Der Bereitschaftsring — gefuellter Bogen als Anteil an 100, Wert und
 * „bereit" in der Mitte.
 *
 * Zwei Boegen statt eines Farbverlaufs: die Bahn in der getoenten Flaeche des
 * Schemas, darauf der Wert in der Ampelfarbe des Bands. Beide Farben kommen aus
 * dem Theme bzw. aus [readinessBandColor] — der Ring bringt keinen eigenen
 * Farbwert mit, nur seine Geometrie.
 *
 * Die Semantik ist **gebuendelt**: Von aussen ist der Ring ein einziger Halt,
 * der die vollstaendige Auskunft vorliest. Ohne das
 * [clearAndSetSemantics] laese eine Bildschirmvorlesung „82" und „bereit" als
 * zwei zusammenhanglose Fetzen vor, und die Skala 0–100 gar nicht.
 */
@Composable
private fun ReadinessRing(score: Int, bandLabel: String, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = Modifier
            .size(RingSize)
            .clearAndSetSemantics {
                contentDescription = "Erholung $score von 100 — $bandLabel"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = RingStroke.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val arcOffset = Offset(stroke / 2f, stroke / 2f)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcOffset,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = color,
                // Bei −90° beginnt der Bogen oben, wie jede Fortschrittsuhr.
                startAngle = -90f,
                sweepAngle = 360f * (score.coerceIn(0, 100) / 100f),
                useCenter = false,
                topLeft = arcOffset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = color,
            )
            Text(
                text = "bereit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Aussenmass des Bereitschaftsrings — die 92 px der Referenz. */
private val RingSize = 92.dp

/** Staerke des Rings. Schmal genug, dass Zahl und Beschriftung innen Platz haben. */
private val RingStroke = 9.dp

/**
 * Stufe 2: der eine volle Primaerknopf der Seite.
 *
 * Er steht **ausserhalb** der Bereitschaftskarte und ueber die volle Breite —
 * so verlangt es die Referenz, und der Grund ist inhaltlich: Der Knopf
 * beantwortet die ganze Karte darueber, er ist nicht deren letzte Zeile. Als
 * Zeile in der Karte konkurrierte er ausserdem mit dem Uhren-Hinweis um
 * dieselbe Position.
 *
 * ## Die Zahl steht unter dem Knopf, nicht in ihm
 * Die Beschriftung hiess bis hierher „Passende Runde planen · 22,2 km flach"
 * — auf dem Geraet **zwei Zeilen** in einer Pille, deren Text damit anfing,
 * um das Symbol herumzufliessen. Ein Primaerknopf mit umbrechender
 * Beschriftung liest sich wie ein Absatz mit Rahmen, nicht wie eine Aktion.
 *
 * Der Knopf traegt deshalb nur noch den Wortlaut der Referenz — „Runde zum
 * Plan bauen", einzeilig —, und die Zahl steht als eigene, ruhige Zeile
 * darunter. Weggelassen wird sie ausdruecklich **nicht**: Ohne sie bliebe
 * offen, ob gleich die Plandistanz oder die heruntergestufte gebaut wird, und
 * genau diese Verwechslung war der Grund, sie ueberhaupt anzuschreiben.
 */
@Composable
internal fun BuildRouteButton(target: RouteTarget?, onPlanRoute: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onPlanRoute,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Icon(Icons.Filled.Route, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Runde zum Plan bauen", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        target?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = routeButtonSuffix(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** „55 km flach" — was der Knopf gleich erzeugt. */
private fun routeButtonSuffix(target: RouteTarget): String =
    "${formatKmDe(target.distanceKm)} km " +
        ascentPreferenceLabels.getValue(target.ascentPreference).lowercase()

/**
 * Stufe 3: die dreiteilige Zahlenzeile — **ohne** Karte drumherum.
 *
 * Wochenkilometer, Form und Planwoche sind Randbericht, nicht Thema: Eine Karte
 * um sie herum haette sie zu einem vierten gleichrangigen Block gemacht,
 * obwohl sie zusammen eine einzige Zeile Auskunft sind. Auf blankem Grund
 * stehen sie deshalb wie die Datumszeile darueber, um [CardPadding]
 * eingerueckt, damit sie auf derselben Kante sitzen wie der Text *in* den
 * Karten.
 *
 * Die Grammatik bleibt die der ganzen App ([Fact]: Beschriftung ueber der
 * Zahl) — die Referenz setzt die Beschriftung darunter, aber eine zweite
 * Kennzahlen-Grammatik nur fuer diese eine Zeile waere der teurere Bruch.
 *
 * Jede Zahl erscheint nur, wenn es sie gibt: ohne Plan keine Wochenkilometer
 * und keine Planwoche, ohne Fitnesskurve keine Form. Sind alle drei leer,
 * zeichnet [TodayScreen] die Zeile gar nicht erst.
 *
 * @param tsb der Formwert (`insights.fitness.latest.tsb`) — er faerbt sich
 *   selbst ueber [tsbBandColor], damit dieselbe Zahl hier und im Trainings-Tab
 *   nicht unterschiedlich eingefaerbt wird.
 */
@Composable
internal fun TodayCockpitRow(weekKmText: String?, tsb: Double?, planWeekText: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        weekKmText?.let {
            Fact(label = "Wochen-km", value = it, modifier = Modifier.weight(1f))
        }
        tsb?.let {
            Fact(
                label = "Form",
                value = formatSigned(it),
                valueColor = tsbBandColor(it),
                modifier = Modifier.weight(1f),
            )
        }
        planWeekText?.let {
            Fact(label = "Planwoche", value = it, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Stufe 4: die Tagesbegruendung in der Stimme des Coachs.
 *
 * Inhalt sind die [de.trailscape.core.DailyRecommendation.reasons] aus `:core`
 * — die Saetze, aus denen die heutige Empfehlung folgt (HRV, Ruhepuls, Schlaf,
 * Form). Sie waren bislang **nirgends** in der Oberflaeche zu sehen, obwohl
 * `:core` sie zu jeder Empfehlung mitliefert: Wer wissen wollte, warum heute
 * „locker" dasteht, musste es sich im Trainings-Tab aus vier Einzelampeln
 * zusammenreimen.
 *
 * Sie stehen als Akzentkarte und nicht als weitere weisse Flaeche, weil hier
 * jemand *spricht*. Die Karte entfaellt, wenn `:core` keinen Grund nennen kann
 * (kein einziges verfuegbares Signal) — eine Coach-Karte ohne Satz waere ein
 * leeres Sprechblasenbild.
 */
@Composable
internal fun TodayCoachCard(reasons: List<String>) {
    CoachCard {
        for (reason in reasons) {
            Text(text = reason, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Stufe 5: der Ausblick auf den Plan — und der Weg in den Trainings-Tab.
 *
 * ## Was hier zusammengelegt wurde
 * Bis hierher standen Wochenfortschritt („Diese Woche": Balken, Wochentyp,
 * „78 von 120 km") und der Blick nach vorn gar nicht zusammen — den Blick nach
 * vorn gab es ueberhaupt nicht, man musste dafuer in den Trainings-Tab
 * wechseln. Diese Karte ist beides: der Stand der laufenden Woche **und** die
 * Einheit, auf die sie hinauslaeuft.
 *
 * ## „Schluessel-Einheit" heisst: die laengste der Woche
 * [keySession] waehlt [TodayScreen] als die Einheit mit den meisten
 * Kilometern. Das ist keine Heuristik am Kalender vorbei, sondern genau das,
 * was die Woche traegt — und es kommt ohne Wochentags-Rechnerei aus, denn
 * [TrainingSession.day] ist ein Kuerzel („Sa"), kein Datum.
 *
 * Ein Tipp fuehrt in den Trainings-Tab, wo die ganze Woche steht. Der Chevron
 * sagt das an; er ist die einzige Zutat, die diese Karte von einer reinen
 * Anzeige unterscheidet.
 */
@Composable
internal fun PlanOutlookCard(
    week: TrainingWeek,
    weekCount: Int,
    riddenKm: Double,
    keySession: TrainingSession?,
    onOpenTraining: () -> Unit,
) {
    val theme = MaterialTheme.colorScheme
    val progress = if (week.targetKm > 0) {
        (riddenKm / week.targetKm).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenTraining),
    ) {
        Row(
            modifier = Modifier.padding(CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Eyebrow(text = "Plan-Ausblick")
                Text(
                    text = if (weekCount > 0) {
                        "Woche ${week.index + 1}/$weekCount · ${weekKindLabels.getValue(week.kind)}"
                    } else {
                        weekKindLabels.getValue(week.kind)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${formatKmDe(riddenKm)} von ${week.targetKm} km",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
                keySession?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Schlüssel-Einheit ${it.day}: ${plannedUnitLine(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = theme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Die Einheitszeile der Referenz: „GA1 · 45 km · Zone 2 · ~2:00 h".
 *
 * Dauer und Ziel-Last nur, wenn der Plan sie kennt: Plaene aus der Zeit vor
 * [TrainingSession.durationMin]/[TrainingSession.targetLoad] tragen keine, und
 * eine hier hergeleitete Zahl waere eine zweite Wahrheit neben der, mit der die
 * Einheit erzeugt wurde.
 */
private fun plannedUnitLine(session: TrainingSession): String = buildString {
    append(session.title)
    append(" · ${session.targetKm} km")
    session.durationMin?.let { append(" · ca. $it min") }
    session.targetLoad?.let { append(" · Last ${it.roundToInt()}") }
}

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
 * in einem Satz zum Lesen.
 *
 * ## Ihr Platz in der neuen Ordnung
 * Sie steht direkt unter der Coach-Karte und ueber dem Plan-Ausblick: Beide
 * reden vom Plan, und wer liest, dass der Plan sein Ziel nicht traegt, soll die
 * laufende Woche gleich darunter sehen. Weiter oben — zwischen Zahlenzeile und
 * Coach — haette sie die Tagesauskunft zerschnitten, um etwas zu sagen, das
 * nicht heute passiert.
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
 * Kurzfassung der juengsten Tour; ein Tipp fuehrt in den Touren-Tab.
 *
 * Die Tour wird dabei **nicht** ausgewaehlt: Eine Auswahl oeffnet sie im
 * Karten-Tab (siehe `AppViewModel.selectedRide`), und wer hier tippt, will die
 * Liste sehen, nicht nebenbei den Kartenzustand veraendern.
 */
@Composable
internal fun LastRideCard(ride: RideInfo, onOpenRides: () -> Unit) {
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
