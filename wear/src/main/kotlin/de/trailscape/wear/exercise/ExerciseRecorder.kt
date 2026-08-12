package de.trailscape.wear.exercise

import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.WarmUpConfig
import androidx.health.services.client.endExercise
import androidx.health.services.client.pauseExercise
import androidx.health.services.client.prepareExercise
import androidx.health.services.client.resumeExercise
import androidx.health.services.client.startExercise
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Duenner Mantel um den [ExerciseClient]: Lebenszyklus als suspend-Funktionen,
 * Rueckmeldungen als [Flow].
 *
 * Bewusst duenn — der Spike soll das Verhalten von Health Services messen,
 * nicht wegabstrahieren. Alles, was ankommt, wird unveraendert
 * durchgereicht; die Auswertung passiert eine Ebene hoeher im
 * [de.trailscape.wear.record.SpikeService], damit sie im Journal
 * nachvollziehbar bleibt.
 */
class ExerciseRecorder(private val client: ExerciseClient) {

    /** Was der Callback von Health Services liefert, als Datenstrom. */
    sealed interface Ereignis {
        /** Der Callback ist registriert — ab jetzt kommen Aktualisierungen. */
        data object Registriert : Ereignis

        /** Registrierung fehlgeschlagen; ohne sie gibt es keine Daten. */
        data class RegistrierungFehlgeschlagen(val ursache: Throwable) : Ereignis

        /** Der regulaere Herzschlag der Uebung — Metriken, Zustand, Punkte. */
        data class Aktualisierung(val update: ExerciseUpdate) : Ereignis

        /**
         * Verfuegbarkeit eines Sensors hat sich geaendert (GPS sucht, GPS hat,
         * HF am Handgelenk verloren, …). Das ist Frage 2 in Reinform und
         * gehoert deshalb mit Zeitstempel ins Journal.
         */
        data class Verfuegbarkeit(
            val datentyp: DataType<*, *>,
            val zustand: Availability,
        ) : Ereignis

        /** Runden-Zusammenfassung; der Spike markiert keine Runden, aber es kostet nichts. */
        data class Runde(val zusammenfassung: ExerciseLapSummary) : Ereignis
    }

    /**
     * Registriert den Callback und liefert alles, was Health Services meldet.
     *
     * Der Callback wird beim Beenden des Flows wieder abgemeldet — mit der
     * `…Async`-Variante, weil [awaitClose] nicht suspendieren darf. Das
     * Ergebnis interessiert an dieser Stelle niemanden mehr.
     */
    fun ereignisse(): Flow<Ereignis> = callbackFlow {
        val callback = object : ExerciseUpdateCallback {
            override fun onRegistered() {
                trySend(Ereignis.Registriert)
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                trySend(Ereignis.RegistrierungFehlgeschlagen(throwable))
            }

            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                trySend(Ereignis.Aktualisierung(update))
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
                trySend(Ereignis.Runde(lapSummary))
            }

            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
                trySend(Ereignis.Verfuegbarkeit(dataType, availability))
            }
        }

        client.setUpdateCallback(callback)
        awaitClose { client.clearUpdateCallbackAsync(callback) }
    }

    /**
     * Waermt Sensoren vor, BEVOR die Uebung startet.
     *
     * Ohne diesen Schritt fehlen die ersten 30–60 Sekunden: GPS braucht die
     * Zeit bis zum ersten Fix, der optische HF-Sensor bis zur stabilen
     * Messung. Beides wuerde sonst in die Aufzeichnung fallen und Frage 2
     * (Punktdichte, Luecken) mit einem Startartefakt verfaelschen.
     */
    suspend fun vorbereiten(datentypen: Set<DeltaDataType<*, *>>) {
        client.prepareExercise(
            WarmUpConfig(
                exerciseType = ExerciseType.BIKING,
                dataTypes = datentypen,
            ),
        )
    }

    /**
     * Startet die Uebung mit exakt den Datentypen, die die Uhr laut
     * [ermittleFaehigkeiten] beherrscht.
     *
     * `isGpsEnabled = true` ist Pflicht und nicht optional dekorativ: Ohne das
     * Flag liefert Health Services trotz angefordertem `DataType.LOCATION`
     * keine einzige Position.
     */
    suspend fun starten(datentypen: Set<DataType<*, *>>) {
        client.startExercise(
            ExerciseConfig(
                exerciseType = ExerciseType.BIKING,
                dataTypes = datentypen,
                isGpsEnabled = true,
                // Auto-Pause bewusst aus: Sie wuerde die Punktfolge an
                // Ampeln unterbrechen und damit ausgerechnet die Luecken
                // erzeugen, die der Spike als GPS-Aussetzer vermessen will.
                isAutoPauseAndResumeEnabled = false,
            ),
        )
    }

    // Block-Koerper, kein `=`: Die Coroutine-Erweiterungen fuer pause/resume/end
    // liefern in 1.0.0 `Void` (das leere Java-Gegenstueck des
    // ListenableFuture<Void>), was sich als Rueckgabetyp nicht sinnvoll
    // weiterreichen laesst.
    suspend fun pausieren() {
        client.pauseExercise()
    }

    suspend fun fortsetzen() {
        client.resumeExercise()
    }

    suspend fun beenden() {
        client.endExercise()
    }
}
