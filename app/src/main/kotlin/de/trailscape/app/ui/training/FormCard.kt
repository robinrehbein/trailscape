package de.trailscape.app.ui.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.core.FitnessAssessment
import de.trailscape.core.FitnessDirection
import de.trailscape.core.FitnessTrend
import de.trailscape.core.describeFitnessTrend
import de.trailscape.core.freshnessWord
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.CoachCard
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.LoadRatioBand
import de.trailscape.core.classifyLoadRatio
import de.trailscape.core.classifyRampRate
import de.trailscape.core.classifyTsb
import de.trailscape.core.loadRatioLabels
import de.trailscape.core.rampBandLabels
import de.trailscape.core.tsbBandLabels
import de.trailscape.core.tsbBandMessages
import kotlin.math.roundToInt

/**
 * # „Deine Form" — ein Satz vorn, alle Werte eine Ebene tiefer
 *
 * Im Redesign „Klartext" (`docs/design/prototyp-klartext.html`, Screen
 * `#s-training` und Blatt `#m-form`) steht im Tab nur noch **eine** antippbare
 * Karte ([FormSummaryCard]): ein Satz („Fitness steigt seit 6 Wochen", aus
 * `:core` [describeFitnessTrend]), eine Kurve und zwei Einordnungen (Fitness
 * mit Pfeil, Frische als Wort). Der Tipp oeffnet [FormSheet]: die drei Linien
 * Fitness / Muedigkeit / Frische je in einem Satz, die Fachbegriffe
 * (CTL/ATL/TSB) klein dahinter, und unter „Alle Werte" alles, was vorher im
 * Tab stand — [FormCard] (Lastskala, beide Kurven, Belastungssprung),
 * [FormCoachCard] (Formband, Rampenrate, Belastungsverhaeltnis),
 * [WeekCard] („Belastung dieser Woche") und [FitnessCard]. Verloren geht
 * nichts; es liegt nur eine Ebene tiefer.
 *
 * ## Klartext statt Kuerzel
 * Die drei Kennzahlen heissen Fitness, Muedigkeit und Frische. Wer aus einem
 * anderen Trainingstool umsteigt, findet die Kuerzel im Blatt klein hinter
 * jedem Satz und in [FormCard] als Fussnote.
 */

private fun trendArrow(trend: FitnessTrend?): String = when (trend?.direction) {
    FitnessDirection.STEIGT -> " ↑"
    FitnessDirection.SINKT -> " ↓"
    FitnessDirection.STABIL -> " →"
    null -> ""
}

/**
 * Die eine Formkarte im Tab: Satz, Fitness-Kurve (Flaeche), zwei Pillen. Die
 * ganze Karte ist der Knopf zu [FormSheet].
 */
@Composable
fun FormSummaryCard(insights: TrainingInsights, onClick: () -> Unit) {
    val theme = MaterialTheme.colorScheme
    val series = insights.fitness
    val latest = series.latest
    val trend = describeFitnessTrend(series.points)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Form erklärt öffnen", onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        latest == null -> "Deine Fitnesskurve entsteht mit der ersten Tour"
                        !series.displayReady ->
                            "Kurve wird aufgebaut (noch ${series.daysUntilDisplayReady} " +
                                "${if (series.daysUntilDisplayReady == 1) "Tag" else "Tage"})"
                        else -> trend?.sentence ?: "Fitness stabil"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = theme.onSurfaceVariant,
                )
            }
            if (latest != null && series.displayReady) {
                FitnessAreaSparkline(
                    values = series.lastDays(60).map { it.ctl },
                    lineColor = theme.primary,
                    fillColor = theme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                )
            }
            if (latest != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricChip(
                        "Fitness ${latest.ctl.roundToInt()}${trendArrow(trend)}",
                        when (trend?.direction) {
                            FitnessDirection.STEIGT -> trainingGood
                            FitnessDirection.SINKT -> trainingCaution
                            else -> Color.Unspecified
                        },
                    )
                    MetricChip(freshnessWord(latest.tsb), tsbBandColor(latest.tsb))
                }
            }
        }
    }
}

/**
 * Fitness als gefuellte Flaeche mit Linie und Endpunkt — das Kurvenbild der
 * Formkarte im Prototyp. Beide Kurven (Fitness und Muedigkeit) zeigt weiter
 * [PmcSparkline] unter „Alle Werte".
 */
@Composable
private fun FitnessAreaSparkline(
    values: List<Double>,
    lineColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val maxV = values.max().coerceAtLeast(1.0)
        val minV = (values.min() * 0.85).coerceAtMost(maxV - 1)
        val span = maxV - minV
        val pad = 4.dp.toPx()
        val h = size.height - pad
        fun y(v: Double) = pad + (h - pad) * (1 - ((v - minV) / span).toFloat())
        val last = values.size - 1
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / last
            if (i == 0) line.moveTo(x, y(v)) else line.lineTo(x, y(v))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, color = fillColor.copy(alpha = 0.7f))
        drawPath(line, color = lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(size.width - 2.dp.toPx(), y(values[last])))
    }
}

/**
 * Blatt „Deine Form" — Vorlage `#m-form` im Prototyp: drei Saetze, die
 * Fachbegriffe klein dahinter, und unter „Alle Werte" die komplette bisherige
 * Auswertung.
 *
 * @param showDetails ob die Detailkarten (Wochenlast, Fitnesslevel) Sinn
 *   haben — ohne Touren behaupteten sie etwas ohne Grundlage (siehe KDoc von
 *   [TrainingScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    insights: TrainingInsights,
    assessment: FitnessAssessment,
    showDetails: Boolean,
    onOpenProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val latest = insights.fitness.latest
    val trend = describeFitnessTrend(insights.fitness.points)
    var allValues by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetColumn {
            Text("Deine Form", style = MaterialTheme.typography.titleLarge)
            Text(
                "Drei Linien, einfach erklärt. Die Fachbegriffe aus anderen Apps stehen klein dahinter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExplainRow(
                label = "Fitness",
                pill = latest?.let { "${it.ctl.roundToInt()}${trendArrow(trend)}" },
                pillColor = trainingGood,
                text = "Was du über Wochen aufgebaut hast. Steigt langsam.",
                jargon = "CTL",
            )
            ExplainRow(
                label = "Müdigkeit",
                pill = latest?.let { "${it.atl.roundToInt()}" },
                pillColor = trainingWarning,
                text = "Was die letzten Tage gekostet haben. Fällt schnell wieder.",
                jargon = "ATL",
            )
            ExplainRow(
                label = "Frische",
                pill = latest?.let { "${formatSigned(it.tsb)} · ${freshnessWord(it.tsb)}" },
                pillColor = latest?.let { tsbBandColor(it.tsb) } ?: Color.Unspecified,
                text = "Fitness minus Müdigkeit. Leicht negativ heißt: du trainierst gerade produktiv.",
                jargon = "TSB",
            )
            NeutralButton(onClick = { allValues = !allValues }, modifier = Modifier.fillMaxWidth()) {
                Text(if (allValues) "Weniger anzeigen" else "Alle Werte")
            }
            if (allValues) {
                FormCard(insights)
                if (latest != null) FormCoachCard(insights)
                if (showDetails) {
                    WeekCard(insights, onOpenMore = onOpenProfile)
                    FitnessCard(assessment)
                }
            }
        }
    }
}

/**
 * Bild der Form: Lastskala-Hinweis, PMC-Kurve und die drei Kennzahlen als
 * Chips.
 *
 * Die Kennzahlen standen frueher als [FigureText]-Trio (Beschriftung ueber der
 * Zahl). Als **Chips** ([MetricChip]) sitzen sie naeher an der Kurve, zu der sie
 * gehoeren, und lesen sich als deren Legende statt als eigener Zahlenblock —
 * genau so setzt es die Referenz („Fit 62 · Erm 71 · Form −9"). Ausgeschrieben
 * bleiben sie trotzdem: Der Prototyp kuerzt, weil er 8-px-Text hat.
 */
@Composable
fun FormCard(insights: TrainingInsights) {
    val theme = MaterialTheme.colorScheme
    val series = insights.fitness
    val latest = series.latest

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            if (latest == null) {
                Text(
                    text = "Sobald die erste Tour ausgewertet ist, entsteht hier deine " +
                        "Fitness-Kurve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
                return@Card
            }

            // Die Lastskala ist die stille Voraussetzung jeder Zahl auf dieser
            // Karte. Wer nicht weiss, dass CTL/ATL/TSB relativ zu einer
            // geschaetzten FTP stehen, haelt sie fuer Messwerte — und einen
            // Sprung nach einer FTP-Aenderung fuer einen Fehler.
            NoticeBox(
                icon = Icons.Filled.Info,
                color = theme.onSurfaceVariant,
                text = insights.loadScaleNote,
            )
            insights.calibration.note?.let { note ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            val ratioBand = classifyLoadRatio(latest.loadRatio)
            val window = series.lastDays(60)

            if (!series.displayReady) {
                NoticeBox(
                    icon = Icons.Filled.Info,
                    color = theme.onSurfaceVariant,
                    text = "Kurve wird aufgebaut (noch " +
                        "${series.daysUntilDisplayReady} " +
                        "${if (series.daysUntilDisplayReady == 1) "Tag" else "Tage"}).",
                )
            } else {
                PmcSparkline(
                    ctl = window.map { it.ctl },
                    atl = window.map { it.atl },
                    ctlColor = trainingGood,
                    atlColor = trainingWarning,
                    gridColor = theme.outlineVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Letzte ${window.size} " +
                        "${if (window.size == 1) "Tag" else "Tage"} · " +
                        "grün: Fitness, orange: Ermüdung",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricChip("Fitness ${latest.ctl.roundToInt()}", trainingGood)
                MetricChip("Ermüdung ${latest.atl.roundToInt()}", trainingWarning)
                MetricChip("Form ${formatSigned(latest.tsb)}", tsbBandColor(latest.tsb))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "In anderen Trainings-Apps: CTL, ATL, TSB.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onSurfaceVariant,
            )

            // Der Belastungssprung bleibt hier und wandert NICHT in die
            // Coach-Karte: Er ist eine Warnung ueber einen gemessenen Wert und
            // traegt deshalb die Ampelfarbe. Auf der Akzentflaeche der
            // Coach-Karte laege eine zweite getoente Flaeche in einer dritten
            // Farbe — die Warnung wuerde leiser statt lauter.
            if (ratioBand == LoadRatioBand.BELASTUNGSSPRUNG) {
                Spacer(modifier = Modifier.height(12.dp))
                NoticeBox(
                    icon = Icons.Filled.Warning,
                    color = trainingWarning,
                    text = "Belastungssprung: dein Verhältnis von akuter zu " +
                        "gewohnter Belastung liegt bei " +
                        "${germanFixed(latest.loadRatio!!, 2)} " +
                        "— außerhalb des Bandes 0,8–1,5.",
                )
            }
        }
    }
}

/**
 * Deutung der Form — die Saetze, die frueher am Fuss der Formkarte standen.
 *
 * Inhalt ist unveraendert das Urteil aus `:core`: das Formband
 * ([tsbBandLabels]/[tsbBandMessages]), die Rampenrate ([rampBandLabels]) und —
 * ausser im Warnfall, den die Karte darueber schon zeigt — das
 * Belastungsverhaeltnis ([loadRatioLabels]). Kein Satz ist dazugekommen, keiner
 * weggefallen; sie stehen nur dort, wo man sie liest.
 *
 * Der Aufrufer zeigt diese Karte nur, wenn es ueberhaupt eine Fitnesskurve gibt
 * (`insights.fitness.latest != null`) — ein Coach, der ohne Datengrundlage ein
 * Urteil spricht, ist genau die erfundene Auskunft, die der Leerzustand des
 * Trainings-Tabs vermeiden soll.
 */
@Composable
fun FormCoachCard(insights: TrainingInsights) {
    val latest = insights.fitness.latest ?: return
    val tsbBand = classifyTsb(latest.tsb)
    val ramp = latest.rampRate7d
    val rampBand = ramp?.let { classifyRampRate(it) }
    val ratioBand = classifyLoadRatio(latest.loadRatio)

    CoachCard {
        Text(
            text = "${tsbBandLabels.getValue(tsbBand)} — ${tsbBandMessages.getValue(tsbBand)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (ramp == null || rampBand == null) {
                "Rampenrate: noch keine Aussage möglich (weniger als 7 Tage Historie)."
            } else {
                "Rampenrate: ${formatSigned(ramp)} Fitness-Punkte pro Woche — " +
                    "${rampBandLabels.getValue(rampBand)}."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (ratioBand != LoadRatioBand.BELASTUNGSSPRUNG) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Belastungsverhältnis: ${loadRatioLabels.getValue(ratioBand)}" +
                    (latest.loadRatio?.let { " (${germanFixed(it, 2)})" } ?: "") +
                    ".",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
