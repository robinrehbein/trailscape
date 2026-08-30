package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.confidenceLabels
import de.trailscape.core.recoveryFlagLabels
import de.trailscape.core.shortSleeperHint
import kotlin.math.roundToInt

/**
 * Der Abschnitt „Werte": Ruhepuls, HRV, Schlaf und VO₂max als **Kachel-Raster**
 * mit gemeinsamer Deutungszeile.
 *
 * ## Vom Listenblock zum Raster
 * Bis hierher war das eine Karte „Vitalwerte" mit vier untereinander gesetzten
 * [SignalRow]s — vier Ampelpunkte in einer Spalte, jede Zeile so hoch wie ihr
 * laengster Begruendungssatz. Der Blick fand darin keinen Wert wieder, weil
 * alle vier dieselbe Form hatten und keine eine eigene Flaeche.
 *
 * Die Zielgestaltung (`docs/design/prototyp-eine-leiste.html`, Abschnitt
 * „Werte") setzt sie als 2×2-Raster: **eine Kachel je Signal**, darin die
 * Augenbraue mit dem Namen, der Messwert gross, die Ampelstufe daneben und
 * darunter die Begruendung als Deutungszeile. Verloren geht dabei nichts —
 * jede Zeile der alten Fassung ist eine Kachel geworden, samt ihrem Text.
 *
 * ## Messwert und Bewertung sind zweierlei
 * Unveraendert: In der Kopfzeile jeder Kachel steht der **zuletzt gemessene
 * Tageswert** ([de.trailscape.core.HrvAssessment.lastRmssd],
 * [de.trailscape.core.RestingHrAssessment.last]) — das ist die Zahl, die
 * jemand erwartet, der „HRV 48 ms" liest. Bewertet wird dagegen mit dem
 * 7-Tage-Rollmittel bzw. dem 3-Tage-Median; die stehen in der Deutungszeile
 * der Kachel.
 *
 * ## Die Ampel bleibt ein Farbwort, kein Icon
 * Wo frueher ein farbiger Punkt vor der Zeile stand, faerbt jetzt dieselbe
 * Ampelfarbe ([recoveryFlagColor]) den Messwert und die Stufenbezeichnung der
 * Kachel. Dieselbe Information, dieselbe Palette, nur ohne das zusaetzliche
 * Punktobjekt — in einer Kachel ist der Wert selbst gross genug, um Farbe zu
 * tragen.
 *
 * [showShortSleeperHint] setzt die Regel „hoechstens einmal im Monat" aus
 * `:core` (`shouldShowShortSleeperHint`) durch — sie war definiert, getestet
 * und nie aufgerufen. [onShortSleeperHintShown] quittiert die Anzeige.
 */
@Composable
fun VitalsTiles(
    insights: TrainingInsights,
    showShortSleeperHint: Boolean = true,
    onShortSleeperHintShown: () -> Unit = {},
) {
    val theme = MaterialTheme.colorScheme
    val unknown = theme.onSurfaceVariant
    val readiness = insights.readiness
    val hrv = insights.hrv
    val rhr = insights.restingHr
    val sleep = insights.sleep
    val vo2 = insights.vo2max

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        // `IntrinsicSize.Min` macht beide Kacheln einer Zeile gleich hoch:
        // Ohne sie richtete sich jede nach ihrem eigenen Begruendungstext, und
        // aus dem Raster wuerde eine Treppe.
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            VitalTile(
                label = "Ruhepuls",
                value = rhr.last?.let { "${it.roundToInt()}" },
                unit = "bpm",
                flag = if (rhr.available) recoveryFlagLabels.getValue(rhr.flag) else null,
                color = recoveryFlagColor(rhr.flag, unknown),
                reading = if (rhr.available) {
                    rhr.message
                } else {
                    rhr.unavailableReason ?: "Keine Aussage möglich."
                },
            )
            VitalTile(
                label = "HRV",
                value = hrv.lastRmssd?.let { "${it.roundToInt()}" },
                unit = "ms",
                flag = if (hrv.available) recoveryFlagLabels.getValue(hrv.flag) else null,
                color = recoveryFlagColor(hrv.flag, unknown),
                reading = if (hrv.available) {
                    "${hrv.message} ${hrvTrendText(hrv)}"
                } else {
                    hrv.unavailableReason ?: "Keine Aussage möglich."
                },
            )
        }

        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            VitalTile(
                label = "Schlaf",
                value = sleep.lastNightH?.let { germanFixed(it, 1) },
                unit = "h",
                flag = if (sleep.available) recoveryFlagLabels.getValue(sleep.flag) else null,
                color = recoveryFlagColor(sleep.flag, unknown),
                reading = if (sleep.available) {
                    sleep.message
                } else {
                    sleep.unavailableReason ?: "Keine Aussage möglich."
                },
            )
            if (vo2.available) {
                VitalTile(
                    label = "VO₂max",
                    // Immer als Band, nie als Punktwert (§8.5) — deshalb steht
                    // hier die Spanne und nicht `vo2.value`.
                    value = "${vo2.lower!!.roundToInt()}–${vo2.upper!!.roundToInt()}",
                    unit = "ml/kg/min",
                    flag = null,
                    color = unknown,
                    // Der gedaempfte Untertext erklaert den Begriff direkt an
                    // der Kennzahl mit — genau die Definition, die vorher nur im
                    // separaten Glossar stand (`GlossaryCard.kt`, geloescht).
                    // Wer die Zahl nicht kennt, braucht dafuer keinen zweiten
                    // Ort.
                    reading = "Ein Maß für deine maximale Sauerstoffaufnahme unter Volllast — " +
                        "geschätzt (${confidenceLabels.getValue(vo2.confidence)}), deshalb " +
                        "ein Bereich, keine Messung.",
                )
            } else {
                // Ohne Schaetzung bleibt der Platz leer statt eine Kachel mit
                // „—" zu zeigen: VO₂max braucht Touren mit Puls und
                // Hoehenprofil, und eine leere Kachel behauptete, der Wert sei
                // gemessen und bloss schlecht.
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // Die Deutungszeile unter dem Raster: Sie sagt, wie aus diesen vier
        // Signalen ein Erholungsbild wird — und, wenn es dafuer noch nicht
        // reicht, woran es fehlt. Der Erholungs*wert* selbst steht bewusst
        // nicht hier, sondern nur auf der Startseite: Zwei Orte fuer dieselbe
        // Zahl waeren genau die Doppelung, wegen der die Tagesauskunft
        // ueberhaupt dorthin gezogen ist.
        Text(
            text = if (readiness.available) {
                readiness.detail
            } else {
                readiness.unavailableReason ?: readiness.detail
            },
            style = MaterialTheme.typography.bodySmall,
            color = theme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = CardPadding),
        )

        if (sleep.available && sleep.shortSleeper && showShortSleeperHint) {
            NoticeBox(icon = Icons.Filled.Info, color = unknown, text = shortSleeperHint)
            LaunchedEffect(Unit) { onShortSleeperHintShown() }
        }
    }
}

/**
 * Eine Kachel des Rasters: Augenbraue, Messwert mit Einheit, Ampelstufe,
 * Deutungszeile.
 *
 * @param value der Messwert als fertige Zeichenkette, oder `null`, wenn es
 *   keinen gibt. Dann zeigt die Kachel nur Namen und Grund — eine „—"-Zahl
 *   waere eine Aussage ueber den Nutzer, die niemand getroffen hat.
 * @param flag die Ampelstufe im Klartext („grün", „gelb", …), oder `null`,
 *   solange nicht genug Tage fuer eine Bewertung vorliegen.
 * @param color die Ampelfarbe aus [recoveryFlagColor]; sie faerbt Wert und
 *   Stufe, nicht die Flaeche — die Kachel bleibt eine normale Karte des Themes.
 */
@Composable
private fun RowScope.VitalTile(
    label: String,
    value: String?,
    unit: String,
    flag: String?,
    color: Color,
    reading: String,
) {
    val theme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Eyebrow(text = label)
            if (value != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineSmall,
                        color = color,
                    )
                    Text(
                        text = " $unit",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            flag?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium, color = color)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = reading,
                style = MaterialTheme.typography.bodySmall,
                color = theme.onSurfaceVariant,
            )
        }
    }
}
