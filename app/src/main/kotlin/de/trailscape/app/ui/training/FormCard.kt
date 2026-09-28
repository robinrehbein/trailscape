package de.trailscape.app.ui.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.CoachCard
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.FitnessAssessment
import de.trailscape.core.FitnessDirection
import de.trailscape.core.FitnessTrend
import de.trailscape.core.LoadRatioBand
import de.trailscape.core.classifyLoadRatio
import de.trailscape.core.classifyRampRate
import de.trailscape.core.classifyTsb
import de.trailscape.core.describeFitnessTrend
import de.trailscape.core.freshnessWord
import de.trailscape.core.sentence
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
    val coreTexts = LocalCoreTexts.current
    val theme = MaterialTheme.colorScheme
    val series = insights.fitness
    val latest = series.latest
    val trend = describeFitnessTrend(series.points)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.training_form_open_cd), onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        latest == null -> stringResource(R.string.training_form_summary_empty)
                        !series.displayReady -> pluralStringResource(
                            R.plurals.training_form_summary_building_count,
                            series.daysUntilDisplayReady,
                            series.daysUntilDisplayReady,
                        )
                        else -> trend?.sentence(coreTexts) ?: stringResource(R.string.training_form_summary_stable)
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
                        stringResource(R.string.training_form_fitness_chip, "${latest.ctl.roundToInt()}${trendArrow(trend)}"),
                        when (trend?.direction) {
                            FitnessDirection.STEIGT -> trainingGood
                            FitnessDirection.SINKT -> trainingCaution
                            else -> Color.Unspecified
                        },
                    )
                    MetricChip(freshnessWord(latest.tsb, coreTexts), tsbBandColor(latest.tsb))
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
    val coreTexts = LocalCoreTexts.current
    val latest = insights.fitness.latest
    val trend = describeFitnessTrend(insights.fitness.points)
    var allValues by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetColumn {
            Text(stringResource(R.string.training_form_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.training_form_sheet_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExplainRow(
                label = stringResource(R.string.training_form_fitness_label),
                pill = latest?.let { "${it.ctl.roundToInt()}${trendArrow(trend)}" },
                pillColor = trainingGood,
                text = stringResource(R.string.training_form_fitness_body),
                jargon = "CTL",
            )
            ExplainRow(
                label = stringResource(R.string.training_form_fatigue_label),
                pill = latest?.let { "${it.atl.roundToInt()}" },
                pillColor = trainingWarning,
                text = stringResource(R.string.training_form_fatigue_body),
                jargon = "ATL",
            )
            ExplainRow(
                label = stringResource(R.string.training_form_freshness_label),
                pill = latest?.let { "${formatSigned(it.tsb)} · ${freshnessWord(it.tsb, coreTexts)}" },
                pillColor = latest?.let { tsbBandColor(it.tsb) } ?: Color.Unspecified,
                text = stringResource(R.string.training_form_freshness_body),
                jargon = "TSB",
            )
            NeutralButton(onClick = { allValues = !allValues }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (allValues) R.string.training_form_show_less_action else R.string.training_form_all_values_action,
                    ),
                )
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
    val coreTexts = LocalCoreTexts.current
    val formats = LocalAppFormats.current
    val theme = MaterialTheme.colorScheme
    val series = insights.fitness
    val latest = series.latest

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            if (latest == null) {
                Text(
                    text = stringResource(R.string.training_form_empty),
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
                icon = TrainingInfoIcon,
                color = theme.onSurfaceVariant,
                text = insights.loadScaleNote(LocalAppLanguage.current).asString(),
            )
            insights.calibration.note(coreTexts)?.let { note ->
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
                    icon = TrainingInfoIcon,
                    color = theme.onSurfaceVariant,
                    text = pluralStringResource(
                        R.plurals.training_form_building_count,
                        series.daysUntilDisplayReady,
                        series.daysUntilDisplayReady,
                    ),
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
                    text = pluralStringResource(R.plurals.training_form_legend_count, window.size, window.size),
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
                MetricChip(
                    stringResource(R.string.training_form_fitness_chip, latest.ctl.roundToInt().toString()),
                    trainingGood,
                )
                MetricChip(stringResource(R.string.training_form_fatigue_chip, latest.atl.roundToInt()), trainingWarning)
                MetricChip(
                    stringResource(R.string.training_form_form_chip, formatSigned(latest.tsb)),
                    tsbBandColor(latest.tsb),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.training_form_jargon),
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
                    icon = TrainingWarningIcon,
                    color = trainingWarning,
                    text = stringResource(R.string.training_form_load_jump, formats.decimal(latest.loadRatio!!, 2)),
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
    val coreTexts = LocalCoreTexts.current
    val formats = LocalAppFormats.current
    val latest = insights.fitness.latest ?: return
    val tsbBand = classifyTsb(latest.tsb)
    val ramp = latest.rampRate7d
    val rampBand = ramp?.let { classifyRampRate(it) }
    val ratioBand = classifyLoadRatio(latest.loadRatio)

    CoachCard {
        Text(
            text = "${coreTexts.load.tsbBand(tsbBand)} — ${coreTexts.load.tsbBandMessage(tsbBand)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (ramp == null || rampBand == null) {
                stringResource(R.string.training_form_ramp_unknown)
            } else {
                stringResource(R.string.training_form_ramp, formatSigned(ramp), coreTexts.load.rampBand(rampBand))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (ratioBand != LoadRatioBand.BELASTUNGSSPRUNG) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = latest.loadRatio?.let {
                    stringResource(
                        R.string.training_form_load_ratio_value,
                        coreTexts.load.loadRatioBand(ratioBand),
                        formats.decimal(it, 2),
                    )
                } ?: stringResource(R.string.training_form_load_ratio, coreTexts.load.loadRatioBand(ratioBand)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
