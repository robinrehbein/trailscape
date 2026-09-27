package de.trailscape.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.sensors.BleSensors
import de.trailscape.core.LiveKachel
import de.trailscape.core.LiveSensorAnzeige
import de.trailscape.core.liveSensorAnzeige
import kotlinx.coroutines.delay

/**
 * Die Live-Sensorwerte fuer Fahrmodus und Kompaktleiste.
 *
 * Sammelt Uhr-Puls ([RecordingRepository.heartRateBpm]/`.watchConnected`)
 * und die Bluetooth-Kanaele ([BleSensors.kanaele]) und laesst
 * [liveSensorAnzeige] in `:core` entscheiden, welche Kachel mit welchem Wert
 * erscheint (Gurt vor Uhr, Strich statt veraltetem Wert).
 *
 * Tickt jede Sekunde — aber nur, solange ein Bluetooth-Kanal aktiv ist: Ob
 * ein Wert frisch ist und seit wann Stille herrscht, aendert sich auch ohne
 * neues Paket. Ohne Sensor bleibt alles wie zuvor ereignisgetrieben.
 */
@Composable
internal fun rememberLiveSensorAnzeige(): LiveSensorAnzeige {
    val uhrBpm by RecordingRepository.heartRateBpm.collectAsStateWithLifecycle()
    val uhrVerbunden by RecordingRepository.watchConnected.collectAsStateWithLifecycle()
    val kanaele by BleSensors.kanaele.collectAsStateWithLifecycle()
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ticken = kanaele.irgendeinAktiv
    LaunchedEffect(ticken, kanaele) {
        jetzt = System.currentTimeMillis()
        while (ticken) {
            delay(1_000)
            jetzt = System.currentTimeMillis()
        }
    }
    return liveSensorAnzeige(
        jetzt = jetzt,
        gurt = kanaele.puls,
        leistung = kanaele.leistung,
        trittfrequenz = kanaele.trittfrequenz,
        uhrBpm = uhrBpm,
        uhrVerbunden = uhrVerbunden,
    )
}

/** Welche Messgroesse eine Kachel zeigt — bestimmt Texte und Vorlesesatz. */
internal enum class SensorKachelArt { PULS, LEISTUNG, TRITTFREQUENZ }

/** Wert, Beschriftung und Vorlesesatz einer Sensorkachel. */
internal data class SensorKachelText(val wert: String, val label: String, val spoken: String)

/**
 * Die Texte einer Kachel, geteilt von Fahrmodus und Kompaktleiste, damit
 * beide gleich formulieren. Mit Wert steht [label] darunter; ohne Wert der
 * Strich und statt der Beschriftung „seit X s nichts" bzw. „verbinde …" —
 * die Einheit ohne Zahl sagte nichts, die Stillzeit sagt, warum die Zahl fehlt.
 *
 * @param stillLabelKurz in der schmalen Kompaktleiste bleibt die Beschriftung
 *   die Einheit; nur der Vorlesesatz nennt die Stille.
 */
@Composable
internal fun sensorKachelText(
    art: SensorKachelArt,
    kachel: LiveKachel,
    label: String,
    stillLabelKurz: Boolean = false,
): SensorKachelText {
    val messgroesse = stringResource(
        when (art) {
            SensorKachelArt.PULS -> R.string.ble_type_heart_rate
            SensorKachelArt.LEISTUNG -> R.string.ble_type_power
            SensorKachelArt.TRITTFREQUENZ -> R.string.ble_type_cadence
        },
    )
    val wert = kachel.wert
    if (wert != null) {
        val spoken = when (art) {
            // Wortlaut der bisherigen Puls-Kachel (siehe RideModeScreen).
            SensorKachelArt.PULS -> "Puls $wert Schläge pro Minute"
            SensorKachelArt.LEISTUNG -> stringResource(R.string.ble_spoken_power, wert)
            SensorKachelArt.TRITTFREQUENZ -> stringResource(R.string.ble_spoken_cadence, wert)
        }
        return SensorKachelText(wert = "$wert", label = label, spoken = spoken)
    }
    val still = kachel.stillSeitS
    val stillLabel = if (still != null) {
        stringResource(R.string.ble_tile_silent, still)
    } else {
        stringResource(R.string.ble_tile_connecting)
    }
    val spoken = if (still != null) {
        stringResource(R.string.ble_spoken_silent, messgroesse, still)
    } else {
        stringResource(R.string.ble_spoken_connecting, messgroesse)
    }
    return SensorKachelText(wert = "–", label = if (stillLabelKurz) label else stillLabel, spoken = spoken)
}
