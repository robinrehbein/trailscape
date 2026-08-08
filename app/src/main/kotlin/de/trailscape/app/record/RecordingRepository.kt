package de.trailscape.app.record

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import de.trailscape.core.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Beobachtbarer Zustand der laufenden Aufzeichnung — die Bruecke zwischen
 * [RecordingService] und der Compose-Oberflaeche aus Phase 4.
 *
 * Bewusst ein eigenes Singleton und NICHT Teil von
 * [de.trailscape.app.data.AppServices]: Der Zustand gehoert dem Service, nicht
 * dem DI-Graphen. Der Service ist die einzige schreibende Instanz (Methoden
 * mit `internal` + `publish`-Praefix); die UI liest ausschliesslich die
 * [StateFlow]s und schickt Kommandos ueber [start]/[pause]/[resume]/[stop].
 *
 * Wichtig fuer Phase 4: Diese Flows sind ein *Spiegel*, keine Quelle der
 * Wahrheit. Die Wahrheit steht im Journal auf dem Datentraeger (siehe
 * [RecordingJournal]). Nach einem Prozesstod startet der Prozess mit leeren
 * Flows; der Service fuellt sie neu, sobald er sich aus dem Journal
 * wiederhergestellt hat.
 *
 * Beispiel (Phase 4):
 * ```kotlin
 * val recording by RecordingRepository.isRecording.collectAsStateWithLifecycle()
 * val km by RecordingRepository.distanceKm.collectAsStateWithLifecycle()
 * Button(onClick = { RecordingRepository.start(context) }) { ... }
 * ```
 */
object RecordingRepository {

    /**
     * Application-Context, gemerkt beim ersten [start] bzw. von
     * [RecordingService.onCreate]. Damit kommen [pause]/[resume]/[stop] ohne
     * Context aus — praktisch fuer Notification-Actions und ViewModels.
     */
    @Volatile
    private var appContext: Context? = null

    private val _isRecording = MutableStateFlow(false)
    private val _isPaused = MutableStateFlow(false)
    private val _startedAtMs = MutableStateFlow<Long?>(null)
    private val _elapsedMs = MutableStateFlow(0L)
    private val _distanceKm = MutableStateFlow(0.0)
    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    private val _lastPoint = MutableStateFlow<TrackPoint?>(null)
    private val _pointCount = MutableStateFlow(0)
    private val _speedKmh = MutableStateFlow<Double?>(null)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _lastFinishedRideId = MutableStateFlow<String?>(null)

    /** Ob eine Aufzeichnung laeuft — unabhaengig davon, ob sie pausiert ist. */
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Ob die laufende Aufzeichnung pausiert ist. */
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    /** Startzeitpunkt in ms seit Epoch, `null` wenn nicht aufgezeichnet wird. */
    val startedAtMs: StateFlow<Long?> = _startedAtMs.asStateFlow()

    /**
     * Reine Aufzeichnungsdauer in ms ohne Pausenzeiten
     * (`now - startedAt - pausedMs`, wie `map_screen.dart` es rechnet).
     * Wird vom Service sekuendlich fortgeschrieben.
     */
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** Bisher zurueckgelegte Distanz in km (Haversine ueber die angenommenen Punkte). */
    val distanceKm: StateFlow<Double> = _distanceKm.asStateFlow()

    /**
     * Alle bisher angenommenen Punkte — fuer die Live-Linie auf der Karte
     * (entspricht `_livePoints` in `map_screen.dart`).
     */
    val points: StateFlow<List<TrackPoint>> = _points.asStateFlow()

    /** Zuletzt angenommener Punkt (fuer das Nachfuehren der Karte). */
    val lastPoint: StateFlow<TrackPoint?> = _lastPoint.asStateFlow()

    /** Anzahl angenommener Punkte. */
    val pointCount: StateFlow<Int> = _pointCount.asStateFlow()

    /** Aktuelle Geschwindigkeit in km/h, `null` wenn unbekannt. */
    val speedKmh: StateFlow<Double?> = _speedKmh.asStateFlow()

    /**
     * Letzte Fehlermeldung in deutscher Sprache (fehlende Berechtigung,
     * abgeschalteter Standortdienst, IO-Fehler). Die UI zeigt sie z. B. als
     * Snackbar und quittiert sie danach mit [clearError].
     */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * ID der zuletzt fertig gespeicherten Tour — auch der bei einer
     * Wiederherstellung gespeicherten. Die UI kann darauf hin die Tourenliste
     * neu laden bzw. direkt zur Tour springen. Quittieren mit
     * [clearFinishedRide].
     */
    val lastFinishedRideId: StateFlow<String?> = _lastFinishedRideId.asStateFlow()

    // ----------------------------------------------------------- Kommandos

    /**
     * Startet die Aufzeichnung. Die Standort- und (ab Android 13)
     * Notification-Berechtigung muss die UI vorher eingeholt haben — der
     * Service prueft nur defensiv und beendet sich mit einer Fehlermeldung in
     * [lastError], wenn sie fehlt.
     */
    fun start(context: Context) {
        appContext = context.applicationContext
        send(context, RecordingService.ACTION_START)
    }

    /** Pausiert die laufende Aufzeichnung (ohne Wirkung, wenn keine laeuft). */
    fun pause() = sendFromStoredContext(RecordingService.ACTION_PAUSE)

    /** Setzt eine pausierte Aufzeichnung fort. */
    fun resume() = sendFromStoredContext(RecordingService.ACTION_RESUME)

    /** Schaltet zwischen Pause und Fortsetzung um (wie der Pause-Knopf in `map_screen.dart`). */
    fun togglePause() {
        if (_isPaused.value) resume() else pause()
    }

    /**
     * Beendet die Aufzeichnung. Der Service schreibt die Tour aus dem Journal
     * ueber `RideStorage` und beendet sich danach selbst; die fertige Tour
     * taucht als [lastFinishedRideId] auf.
     */
    fun stop() = sendFromStoredContext(RecordingService.ACTION_STOP)

    /** Quittiert eine angezeigte Fehlermeldung. */
    fun clearError() {
        _lastError.value = null
    }

    /** Quittiert eine verarbeitete Tour-ID. */
    fun clearFinishedRide() {
        _lastFinishedRideId.value = null
    }

    private fun sendFromStoredContext(action: String) {
        val context = appContext ?: return
        send(context, action)
    }

    private fun send(context: Context, action: String) {
        val intent = Intent(context, RecordingService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Ab Android 12 kann das System den Start eines Vordergrunddienstes
            // aus dem Hintergrund ablehnen (ForegroundServiceStartNotAllowedException).
            // Das darf die App nicht abstuerzen lassen.
            _lastError.value = "Die Aufzeichnung konnte nicht gestartet werden. " +
                "Bitte oeffne Trailscape und versuche es erneut."
        }
    }

    // ------------------------------------------------- nur fuer den Service

    internal fun attach(context: Context) {
        appContext = context.applicationContext
    }

    internal fun publishStarted(startedAtMs: Long, points: List<TrackPoint>, paused: Boolean) {
        _isRecording.value = true
        _isPaused.value = paused
        _startedAtMs.value = startedAtMs
        _points.value = points
        _lastPoint.value = points.lastOrNull()
        _pointCount.value = points.size
        _lastError.value = null
    }

    internal fun publishPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    internal fun publishPoint(point: TrackPoint, distanceKm: Double, speedKmh: Double?) {
        _points.value = _points.value + point
        _lastPoint.value = point
        _pointCount.value = _points.value.size
        _distanceKm.value = distanceKm
        _speedKmh.value = speedKmh
    }

    internal fun publishTick(elapsedMs: Long, speedKmh: Double?) {
        _elapsedMs.value = elapsedMs
        _speedKmh.value = speedKmh
    }

    internal fun publishStopped(finishedRideId: String?) {
        _isRecording.value = false
        _isPaused.value = false
        _startedAtMs.value = null
        _elapsedMs.value = 0L
        _distanceKm.value = 0.0
        _points.value = emptyList()
        _lastPoint.value = null
        _pointCount.value = 0
        _speedKmh.value = null
        if (finishedRideId != null) {
            _lastFinishedRideId.value = finishedRideId
        }
    }

    internal fun publishError(message: String) {
        _lastError.value = message
    }

    internal fun publishFinishedRide(rideId: String) {
        _lastFinishedRideId.value = rideId
    }
}
