package de.trailscape.app.indoor.background

import de.trailscape.app.indoor.IndoorRideRuntime
import de.trailscape.app.indoor.indoorRideToRide
import de.trailscape.core.KeyValueStore
import de.trailscape.core.Ride
import de.trailscape.core.indoor.IndoorRideRecord
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorStartPoints
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * # Die laufende Indoor-Fahrt, unabhaengig von der Oberflaeche (docs/indoor/hintergrund.md)
 *
 * Mit „Im Hintergrund weiterfahren" gehoert eine Fahrt nicht mehr dem
 * Bildschirm, sondern dem Prozess: Trainerverbindung, Steuerbefehle,
 * Sitzungszustand und Sicherung stecken in der [IndoorRideRuntime], und die
 * haelt dieser Halter fest — solange der [IndoorRideService] im Vordergrund
 * laeuft, auch wenn die Activity geht (aus den Letzten Apps gewischt, vom
 * System zerstoert). Das `IndoorViewModel` meldet die Fahrt hier an, haengt
 * sich als Oberflaeche an ([attachUi]) und beim Aufraeumen wieder ab; ein
 * neues ViewModel findet die Fahrt in [ride] und uebernimmt sie.
 *
 * Eine Instanz je Prozess ([shared]); Tests bauen eigene.
 */
class IndoorRideHost {

    /**
     * Eine laufende Fahrt.
     *
     * @property routeName Anzeigename der Strecke (Benachrichtigung).
     * @property closer Abschluss ohne Oberflaeche (Speichern direkt).
     * @property parked was die Oberflaeche zum Wiederaufnehmen braucht; nur das
     *   ViewModel kennt den Typ.
     */
    class Ride(
        val runtime: IndoorRideRuntime,
        val routeName: String,
        val journal: IndoorRideJournal?,
        val closer: IndoorRideCloser,
    ) {
        @Volatile var parked: Any? = null

        /**
         * Der Dienst kam nicht in den Vordergrund (z. B. ab Android 14 ohne
         * `BLUETOOTH_CONNECT`): Dann pausiert das Verlassen der App die Fahrt
         * wie frueher, und die Oberflaeche verwirft sie beim Aufraeumen.
         */
        @Volatile var serviceFailed: Boolean = false

        /** Wird gerade ohne Oberflaeche abgeschlossen (doppeltes „Beenden" wirkt einmal). */
        @Volatile var closing: Boolean = false
            internal set
    }

    /** Ergebnis eines Abschlusses ohne Oberflaeche — fuer die Benachrichtigung danach. */
    sealed interface Outcome {
        data class Saved(val rideId: String) : Outcome
        data object TooShort : Outcome
        data object Failed : Outcome

        /** Die Oberflaeche hat uebernommen und zeigt die Auswertung. */
        data object ShownInApp : Outcome
    }

    private val _ride = MutableStateFlow<Ride?>(null)

    /** Die laufende Fahrt; `null` = keine. */
    val ride: StateFlow<Ride?> = _ride.asStateFlow()

    @Volatile private var uiFinisher: (() -> Unit)? = null

    private val _savedRides = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)

    /** IDs von Touren, die ohne Oberflaeche gespeichert wurden (Abschluss, Wiederherstellung). */
    val savedRides: SharedFlow<String> = _savedRides.asSharedFlow()

    /** Ob gerade eine Fahrt laeuft (z. B. fuer den App-Start: zurueck in die Fahrt). */
    val isRiding: Boolean get() = _ride.value != null

    /** Meldet eine Fahrt an; eine vorige wird ersetzt (darf es nicht geben). */
    fun begin(ride: Ride) {
        _ride.value = ride
    }

    /** Die Fahrt [ride] ist zu Ende (beendet oder verworfen). */
    fun end(ride: Ride) {
        _ride.compareAndSet(ride, null)
    }

    /** Die Oberflaeche ist da; „Beenden" aus der Benachrichtigung geht dann an sie. */
    fun attachUi(finish: () -> Unit) {
        uiFinisher = finish
    }

    fun detachUi(finish: () -> Unit) {
        if (uiFinisher === finish) uiFinisher = null
    }

    /**
     * „Beenden" aus der Benachrichtigung. Mit Oberflaeche beendet sie die Fahrt
     * und zeigt die Auswertung; ohne wird die Fahrt hier abgeschlossen und
     * gespeichert. Hauptthread.
     */
    suspend fun finishFromNotification(): Outcome? {
        val current = _ride.value ?: return null
        if (current.closing) return null
        uiFinisher?.let { finish ->
            finish()
            return Outcome.ShownInApp
        }
        current.closing = true
        val record = current.runtime.finish()
        // Erst speichern, dann abmelden: Bis dahin bleibt der Dienst im Vordergrund.
        val outcome = try {
            current.closer.complete(record, current.runtime.state.value.session)
        } finally {
            end(current)
        }
        if (outcome is Outcome.Saved) {
            current.journal?.discard()
            _savedRides.tryEmit(outcome.rideId)
        } else if (outcome is Outcome.TooShort) {
            current.journal?.discard()
        }
        return outcome
    }

    /** Eine Tour wurde ohne Oberflaeche gespeichert (Wiederherstellung beim Start). */
    fun announceSaved(rideId: String) {
        _savedRides.tryEmit(rideId)
    }

    companion object {
        /** Die Instanz des App-Prozesses. */
        val shared: IndoorRideHost = IndoorRideHost()
    }
}

/**
 * Schliesst eine Fahrt ohne Oberflaeche ab: Stelle merken, Tour bauen,
 * speichern — dieselben Regeln wie die Auswertung (`indoorRideToRide`:
 * unter 60 s Fahrzeit keine Tour). Haelt keine Referenz auf das ViewModel.
 *
 * @param nameOf Tourname; `startKm` = Kilometer des Startpunkts, `null` = am Anfang.
 * @param startRouteM liefert die Startstelle in der Runde, falls die Oberflaeche sie kannte.
 */
class IndoorRideCloser(
    private val save: suspend (Ride) -> Unit,
    private val store: KeyValueStore,
    private val nameOf: (startKm: Double?) -> String,
    private val resumeKey: String?,
    private val routeLengthM: Double,
    private val routeIsLoop: Boolean,
    private val wallClock: () -> Long,
    private val minShownStartM: Double,
    @Volatile var startRouteM: Double? = null,
) {
    suspend fun complete(record: IndoorRideRecord?, last: IndoorSessionState?): IndoorRideHost.Outcome {
        if (last != null && resumeKey != null) {
            val point = IndoorStartPoints.afterStop(last.routeDistM, routeLengthM, routeIsLoop, wallClock())
            runCatching { if (point == null) store.remove(resumeKey) else store.setString(resumeKey, point.toJson()) }
        }
        record ?: return IndoorRideHost.Outcome.Failed
        val start = startRouteM ?: last?.let { startOf(it) }
        val startKm = start?.takeIf { it >= minShownStartM }?.let { it / 1000.0 }
        val ride = runCatching { indoorRideToRide(record, nameOf(startKm)) }.getOrNull() ?: return IndoorRideHost.Outcome.TooShort
        return if (runCatching { save(ride) }.isSuccess) IndoorRideHost.Outcome.Saved(ride.id) else IndoorRideHost.Outcome.Failed
    }

    /** Startstelle aus dem letzten Zustand: Stelle minus Gefahrenes (auf Rundkursen gefaltet). */
    internal fun startOf(s: IndoorSessionState): Double {
        var d = s.routeDistM - s.totals.distanceM
        if (routeIsLoop && routeLengthM > 0) d = ((d % routeLengthM) + routeLengthM) % routeLengthM
        return d.coerceAtLeast(0.0)
    }
}
