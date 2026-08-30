package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Arrangement
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
 * Der Abschnitt „Form" des Trainings-Tabs, aufgeteilt in **Bild** und
 * **Deutung**: [FormCard] zeigt die Kurve und die drei Kennzahlen,
 * [FormCoachCard] sagt, was sie bedeuten.
 *
 * ## Warum zwei Karten statt einer
 * Bis hierher war das eine einzige, sehr lange Karte: Ueberschrift „Form",
 * Lastskala-Hinweis, Kurve, drei Kennzahlen, Kuerzel-Fussnote und darunter drei
 * bis vier Saetze Auswertung. Die Zielgestaltung
 * (`docs/design/prototyp-eine-leiste.html`, Screen „Training") trennt beides:
 * eine weisse Karte mit Kurve und Chips, darunter eine **Akzentkarte**, in der
 * der Coach spricht. Das ist kein Layout-Geschmack, sondern die Trennung von
 * Messwert und Urteil — und sie macht die Saetze ueberhaupt erst auffindbar,
 * die vorher am Fuss einer Karte verschwanden, durch die man schon
 * hindurchgescrollt war.
 *
 * ## Die Ueberschrift ist weg — und das ist der Punkt
 * „Form" stand als Kartentitel darin; jetzt steht es als Kapitelmarke
 * ([de.trailscape.app.ui.components.SectionEyebrow]) darueber. Beides zugleich
 * waere dasselbe Wort zweimal untereinander.
 *
 * ## Klartext statt Kuerzel
 * Unveraendert: Die drei Kennzahlen heissen Fitness, Ermuedung und Form — nicht
 * CTL, ATL, TSB. Wer aus einem anderen Trainingstool umsteigt, bekommt die
 * Kuerzel trotzdem: einmal, gebuendelt, als kleine Fussnote unter der
 * Chip-Zeile — nicht an jeder einzelnen Kennzahl, denn dann waere die
 * Uebersetzung nur Dekoration neben dem eigentlich gemeinten Kuerzel.
 */

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
