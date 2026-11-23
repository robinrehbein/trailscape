package de.trailscape.app.indoor

import android.content.Context
import de.trailscape.app.sensors.BleNutzer
import de.trailscape.app.sensors.BleSensors

/**
 * Puls des gemerkten Gurts waehrend einer Indoor-Fahrt (docs/indoor.md 5.7):
 * `BleSensors.acquire(context, BleNutzer.INDOOR)` haelt die Verbindung, solange
 * die Fahrt laeuft; gelesen wird der Kanal aus `BleSensors.kanaele`.
 *
 * Ein Wert gilt nur, solange er frisch ist ([MAX_AGE_MS]) — ein abgerissener
 * Gurt soll nicht minutenlang denselben Puls in die Aufzeichnung schreiben.
 * `BleKanal.zeitMs` ist Wanduhrzeit (siehe `BleSensors`), daher hier
 * `System.currentTimeMillis()`.
 */
class BleHeartRateSource(
    context: Context,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : IndoorHeartRateSource {
    private val appContext = context.applicationContext

    override fun acquire() {
        BleSensors.acquire(appContext, BleNutzer.INDOOR)
    }

    override fun currentBpm(): Int? {
        val puls = BleSensors.kanaele.value.puls
        if (!puls.aktiv) return null
        val wert = puls.wert ?: return null
        val zeit = puls.zeitMs ?: return null
        if (wallClock() - zeit > MAX_AGE_MS) return null
        return wert.takeIf { it in 25..250 }
    }

    override fun release() {
        BleSensors.release(BleNutzer.INDOOR)
    }

    private companion object {
        const val MAX_AGE_MS = 5_000L
    }
}
