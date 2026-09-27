package de.trailscape.app.strava

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.work.WorkManager
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.OkHttpClientAdapter
import de.trailscape.app.data.PrefsKeyValueStore
import de.trailscape.app.data.trailscapePrefs
import de.trailscape.core.KeyValueStore
import de.trailscape.core.STRAVA_REDIRECT_URI
import de.trailscape.core.StravaCallbackResult
import de.trailscape.core.StravaClient
import de.trailscape.core.StravaError
import de.trailscape.core.StravaException
import de.trailscape.core.StravaUploadLog
import de.trailscape.core.StravaUploadRecord
import de.trailscape.core.StravaUploadState
import de.trailscape.core.parseStravaCallback
import de.trailscape.core.stravaAuthorizeUrl
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Verbindungszustand zu Strava, wie ihn die Oberflaeche zeigt. */
sealed interface StravaConnection {
    data object Disconnected : StravaConnection

    /** Der Code aus dem Rueckruf wird gerade gegen einen Zugang getauscht. */
    data object Connecting : StravaConnection

    data class Connected(val athleteFirstName: String?) : StravaConnection
}

/** Rueckmeldung zum Verbinden/Trennen auf der Einstellungsseite. */
enum class StravaAuthMessage {
    DENIED,
    MISSING_SCOPE,
    INVALID,
    CONNECT_FAILED,
    NO_BROWSER,
    KEYSTORE,
    DISCONNECTED,
}

/**
 * Alles, was die App fuer Strava braucht — an einer Stelle.
 *
 * Bewusst ein eigenes Singleton neben [AppServices] statt weiterer Felder
 * dort: Strava ist optional und in den meisten Builds gar nicht verfuegbar
 * ([StravaConfig.available]); so bleibt [AppServices] frei davon, und nichts
 * hiervon entsteht, solange niemand es anfasst (alles `lazy`).
 *
 * Muss per [init] einen Context bekommen, bevor etwas anderes aufgerufen wird
 * (Application, Worker und Rueckruf-Activity tun das; `init` ist idempotent).
 * Alle blockierenden Zugriffe (Keystore, Netz, Prefs-Commit) laufen auf
 * [Dispatchers.IO].
 */
object StravaServices {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // Zustand im Hintergrund laden: Das Entschluesseln des Zugangs
        // beruehrt den Keystore und gehoert nicht in die erste Komposition.
        if (StravaConfig.available) {
            AppServices.appScope.launch { refreshConnection() }
        }
    }

    private val context: Context
        get() = checkNotNull(appContext) { "StravaServices.init() fehlt" }

    private val store: KeyValueStore by lazy { PrefsKeyValueStore(trailscapePrefs(context)) }

    internal val tokenStore: KeystoreStravaTokenStore by lazy { KeystoreStravaTokenStore(trailscapePrefs(context)) }

    /**
     * Eigener OkHttp-Client mit laengeren Zeitgrenzen als der
     * Standard-Client (15 s): Eine 5-Stunden-Tour ist als GPX schnell ein
     * paar Megabyte, und die gehen oft ueber Mobilfunk.
     */
    private val http by lazy {
        OkHttpClientAdapter(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build(),
        )
    }

    /** `null`, wenn der Build keine Strava-Zugangsdaten hat. */
    internal val client: StravaClient? by lazy {
        StravaConfig.credentials?.let { StravaClient(http, it, tokenStore) }
    }

    private val uploadLog by lazy { StravaUploadLog(store) }

    private val _connection = MutableStateFlow<StravaConnection>(StravaConnection.Disconnected)
    val connection: StateFlow<StravaConnection> = _connection.asStateFlow()

    private val _authMessage = MutableStateFlow<StravaAuthMessage?>(null)
    val authMessage: StateFlow<StravaAuthMessage?> = _authMessage.asStateFlow()

    private val _autoUpload by lazy { MutableStateFlow(store.getString(AUTO_UPLOAD_KEY) == "true") }

    /** „Neue Touren automatisch hochladen" — ab Werk aus. */
    val autoUpload: StateFlow<Boolean> by lazy { _autoUpload.asStateFlow() }

    private val _records by lazy { MutableStateFlow(uploadLog.all()) }

    /** Upload-Vermerke je Tour-ID (siehe [StravaUploadLog]). */
    val records: StateFlow<Map<String, StravaUploadRecord>> by lazy { _records.asStateFlow() }

    /** Liest den Verbindungszustand neu aus dem Speicher. Blockiert (Keystore). */
    fun refreshConnection() {
        if (_connection.value == StravaConnection.Connecting) return
        val tokens = tokenStore.read()
        _connection.value = if (tokens != null) {
            StravaConnection.Connected(tokens.athleteFirstName)
        } else {
            StravaConnection.Disconnected
        }
    }

    // ---------------------------------------------------------------- Verbinden

    /**
     * Startet die Anmeldung: Zufalls-Nonce merken (es muss einen Prozesstod
     * im Browser ueberleben, deshalb in den Prefs) und Strava im Custom Tab
     * oeffnen. Den Rest erledigt [handleCallback].
     */
    fun beginAuth(activityContext: Context, toolbarColor: Int? = null) {
        val credentials = StravaConfig.credentials ?: return
        val state = randomState()
        store.setString(PENDING_STATE_KEY, state)
        _authMessage.value = null
        val url = stravaAuthorizeUrl(credentials.clientId, STRAVA_REDIRECT_URI, state)
        if (!openInCustomTab(activityContext, Uri.parse(url), toolbarColor)) {
            store.remove(PENDING_STATE_KEY)
            _authMessage.value = StravaAuthMessage.NO_BROWSER
        }
    }

    /**
     * Wertet den Rueckruf aus ([parseStravaCallback] gegen das gemerkte
     * Nonce) und tauscht bei Erfolg den Code gegen einen Zugang. Das Nonce
     * gilt genau einmal.
     */
    suspend fun handleCallback(params: Map<String, String?>) = withContext(Dispatchers.IO) {
        val expected = store.getString(PENDING_STATE_KEY)
        val result = parseStravaCallback(params, expected)
        // Ein fremder Rueckruf ohne passendes Nonce darf eine laufende
        // Anmeldung nicht abbrechen; nur ein passender verbraucht es.
        if (result !is StravaCallbackResult.Invalid) store.remove(PENDING_STATE_KEY)
        when (result) {
            StravaCallbackResult.Denied -> _authMessage.value = StravaAuthMessage.DENIED
            StravaCallbackResult.MissingScope -> _authMessage.value = StravaAuthMessage.MISSING_SCOPE
            StravaCallbackResult.Invalid -> _authMessage.value = StravaAuthMessage.INVALID
            is StravaCallbackResult.Code -> exchange(result.code)
        }
    }

    private fun exchange(code: String) {
        val client = client ?: return
        _connection.value = StravaConnection.Connecting
        _authMessage.value = null
        val connected = try {
            client.exchangeCode(code)
        } catch (e: StravaException) {
            _authMessage.value = if (e.error == StravaError.UNAUTHORIZED) {
                StravaAuthMessage.INVALID
            } else {
                StravaAuthMessage.CONNECT_FAILED
            }
            null
        } catch (e: StravaKeystoreException) {
            _authMessage.value = StravaAuthMessage.KEYSTORE
            null
        }
        _connection.value = connected?.let { StravaConnection.Connected(it.athleteFirstName) }
            ?: StravaConnection.Disconnected
    }

    /**
     * Trennt die Verbindung: Strava den Zugang entziehen (best effort), lokal
     * immer vergessen, laufende Uploads abbrechen, Auto-Upload aus. Vermerke
     * erfolgreicher Uploads bleiben, damit „Auf Strava ansehen" weiter geht;
     * offene und fehlgeschlagene verschwinden — sie gehoeren zu einem Zugang,
     * den es nicht mehr gibt.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelAllWorkByTag(StravaUploadScheduler.TAG)
        client?.disconnect() ?: tokenStore.clear()
        setAutoUploadBlocking(false)
        uploadLog.removeWhere { it.state == StravaUploadState.UPLOADING || it.state == StravaUploadState.FAILED }
        _records.value = uploadLog.all()
        _connection.value = StravaConnection.Disconnected
        _authMessage.value = StravaAuthMessage.DISCONNECTED
    }

    fun setAutoUpload(enabled: Boolean) {
        _autoUpload.value = enabled
        AppServices.appScope.launch { setAutoUploadBlocking(enabled) }
    }

    private fun setAutoUploadBlocking(enabled: Boolean) {
        _autoUpload.value = enabled
        if (enabled) store.setString(AUTO_UPLOAD_KEY, "true") else store.remove(AUTO_UPLOAD_KEY)
    }

    // ---------------------------------------------------------------- Hochladen

    /**
     * Reiht den Upload einer Tour ein und vermerkt sofort „wird hochgeladen",
     * damit die Tour den Stand zeigt, noch bevor der Worker laeuft (etwa ohne
     * Netz).
     *
     * @param replace `true` fuer „Zu Strava hochladen"/„Erneut versuchen" von
     *   Hand (ersetzt eine haengende Arbeit), `false` fuer den Auto-Upload
     *   (eine schon eingereihte Arbeit bleibt).
     */
    fun requestUpload(rideId: String, replace: Boolean) {
        if (!StravaConfig.available) return
        val previous = uploadLog.get(rideId)
        // Eine schon angenommene Upload-ID nur weiterverwenden, wenn der
        // Upload noch offen ist — nach einem Fehlschlag wird neu hochgeladen.
        val uploadId = previous?.uploadId?.takeIf { previous.state == StravaUploadState.UPLOADING }
        record(
            StravaUploadRecord(
                rideId = rideId,
                state = StravaUploadState.UPLOADING,
                uploadId = uploadId,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        StravaUploadScheduler.enqueue(context, rideId, replace)
    }

    /**
     * Auto-Upload nach dem Ende einer Aufzeichnung (auch einer
     * wiederhergestellten). Nur mit Verbindung und eingeschaltetem Schalter,
     * und nur fuer Touren ohne Vermerk — eine doppelt gemeldete Tour wird so
     * nicht zweimal eingereiht.
     */
    fun onRideFinished(rideId: String) {
        if (!StravaConfig.available || !_autoUpload.value) return
        if (tokenStore.read() == null) return
        if (uploadLog.get(rideId) != null) return
        requestUpload(rideId, replace = false)
    }

    /** Schreibt einen Vermerk und aktualisiert [records]. */
    internal fun record(record: StravaUploadRecord) {
        uploadLog.put(record)
        _records.value = uploadLog.all()
    }

    internal fun recordFor(rideId: String): StravaUploadRecord? = uploadLog.get(rideId)

    internal fun forget(rideId: String) {
        uploadLog.remove(rideId)
        _records.value = uploadLog.all()
    }

    private val random = SecureRandom()

    private fun randomState(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private const val PENDING_STATE_KEY = "trailscape.strava.pendingState"
    private const val AUTO_UPLOAD_KEY = "trailscape.strava.autoUpload"
}
