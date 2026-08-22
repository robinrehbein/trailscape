package de.trailscape.app.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.formatTime
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.RideWindow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Die Wetterkarte der Startseite: **wann ist heute das beste Fenster zu
 * fahren?** — die Antwort auf die haeufigste Ausrede („das Wetter passt ja
 * nicht").
 *
 * ## Nutzergesteuerter Abruf
 * Der erste Abruf passiert nur per Knopf („Fenster suchen") — wie jede
 * Netzwerkanfrage der App folgt er einer Aktion und keinem stillen Hintergrund
 * (Ausnahme siehe `PRIVACY.md`: die Update-Pruefung). Nach dem ersten Mal
 * zeigt die Karte das Ergebnis und einen kleinen „Aktualisieren"-Knopf.
 *
 * ## Was die Karte NICHT tut
 * Sie bewertet nichts und rechnet nichts: Das Fenster kommt fertig aus
 * `:core` ([de.trailscape.core.bestRideWindow]), der Zustand aus
 * [AppViewModel.weather]. Sie uebersetzt nur Zeitstempel und Werte in
 * deutsche Zeilen.
 *
 * ## Standort
 * Der Abruf nutzt den Startpunkt der juengsten Tour als Ort — deshalb steht
 * diese Karte nur, wenn es mindestens eine Tour gibt (siehe `TodayScreen`).
 * Eine Standortabfrage und -berechtigung gibt es dafuer nicht.
 */
@Composable
internal fun TodayWeatherCard(
    state: AppViewModel.WeatherUiState,
    durationH: Double?,
    onRefresh: (durationH: Double, force: Boolean) -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text(text = "Wetterfenster", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                AppViewModel.WeatherUiState.Idle -> {
                    Text(
                        text = "Wann passt die heutige Einheit ins Wetter? " +
                            "Vorhersage von Open-Meteo, gerechnet für den Start deiner letzten Tour.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { durationH?.let { onRefresh(it, false) } },
                        enabled = durationH != null,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                    ) { Text("Fenster suchen") }
                }

                AppViewModel.WeatherUiState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Sucht das beste Fenster …",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is AppViewModel.WeatherUiState.Ready -> {
                    val window = state.window
                    val start = formatTime(localHour(window.startMs))
                    val end = formatTime(localHour(window.endMs))
                    Text(
                        text = "$start–$end Uhr",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = windowLine(window),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { durationH?.let { onRefresh(it, true) } },
                        enabled = durationH != null,
                    ) { Text("Aktualisieren") }
                }

                AppViewModel.WeatherUiState.Unavailable -> {
                    Text(
                        text = "Gerade kein Fenster gefunden — offline, oder der Tag ist zu " +
                            "Ende. Ein erneuter Versuch kostet einen Tipp.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { durationH?.let { onRefresh(it, false) } },
                        enabled = durationH != null,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                    ) { Text("Erneut versuchen") }
                }
            }
        }
    }
}

/** `HH:mm`-LocalTime eines Epochen-Millisekunden-Werts in der Geraetezeitzone. */
private fun localHour(epochMs: Long) =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()

/** Die Kennzahlenzeile des Fensters: Regen, Temperatur, Wind. */
private fun windowLine(window: RideWindow): String {
    val parts = mutableListOf<String>()
    parts += "${window.avgPrecipProbPct} % Regen"
    parts += "${window.avgTempC.roundToInt()} °C"
    if (window.maxWindKmh > 0) {
        parts += "Wind bis ${window.maxWindKmh.roundToInt()} km/h"
    }
    return parts.joinToString(" · ")
}
