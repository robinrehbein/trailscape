package de.trailscape.app.data

import android.content.Context
import de.trailscape.core.HealthSyncStore
import de.trailscape.core.HttpClient
import de.trailscape.core.KeyValueStore
import de.trailscape.core.TrainingPlanStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Zentrale, einfache manuelle Dependency-Injection fuer `:app`.
 *
 * Kein DI-Framework (Hilt/Koin): Bei der ueberschaubaren Anzahl an
 * Abhaengigkeiten in Phase 3/4 lohnt sich der Build-Zeit- und
 * Boilerplate-Aufwand eines Frameworks nicht. Alle Properties sind `lazy`,
 * damit [init] selbst billig bleibt (nur den `Context` merken) und die
 * eigentlichen Objekte erst beim ersten Zugriff entstehen — praktisch fuer
 * Tests/Previews, die nur einen Teil brauchen.
 *
 * Muss vor jedem Zugriff auf eine der Properties per [init] mit einem
 * `Application`-Context versorgt sein; siehe [de.trailscape.app.TrailscapeApplication].
 *
 * Verwendung durch ViewModels (Phase 4): `AppServices.rideStorage`,
 * `AppServices.httpClient`, `AppServices.keyValueStore`,
 * `AppServices.healthSyncStore`, `AppServices.trainingPlanStore`,
 * `AppServices.appScope`. Ein `HealthGateway` (`:core`-Interface, siehe
 * `HealthSyncLogic.kt`) ist hier bewusst NICHT verdrahtet — das ist Aufgabe
 * des Health-Connect-Gateway-Strangs, der eine konkrete Implementierung
 * gegen `androidx.health.connect:connect-client` beisteuert und dann hier
 * ergaenzt.
 */
object AppServices {
    private lateinit var appContext: Context

    /**
     * Muss genau einmal beim App-Start aufgerufen werden (siehe
     * [de.trailscape.app.TrailscapeApplication.onCreate]). Wiederholte
     * Aufrufe (z. B. in Tests) sind unschaedlich, solange derselbe
     * Application-Context hereingereicht wird.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Lang laufender Scope fuer App-weite Hintergrundarbeit (Health-Sync,
     * periodische Aufraeumarbeiten), NICHT fuer UI-gebundene Arbeit — dafuer
     * ist `viewModelScope` in den kommenden ViewModels zustaendig.
     * `SupervisorJob`, damit ein fehlgeschlagenes Kind (z. B. ein
     * abgebrochener Sync) nicht die Geschwister-Coroutinen mitreisst.
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /** Siehe [RideStorage]-Klassendoc: Tour-Dateien unter `<filesDir>/rides`. */
    val rideStorage: RideStorage by lazy {
        RideStorage(File(appContext.filesDir, "rides"))
    }

    private val prefs by lazy { trailscapePrefs(appContext) }

    /** Implementierung von `:core`s [KeyValueStore] (u. a. fuer `SyncConfig`). */
    val keyValueStore: KeyValueStore by lazy { PrefsKeyValueStore(prefs) }

    /** Implementierung von `:core`s [HealthSyncStore] (Zeitstempel des letzten Health-Imports). */
    val healthSyncStore: HealthSyncStore by lazy { PrefsHealthSyncStore(prefs) }

    /** Implementierung von `:core`s [TrainingPlanStore]. */
    val trainingPlanStore: TrainingPlanStore by lazy { PrefsTrainingPlanStore(prefs) }

    /** Implementierung von `:core`s [HttpClient] (BRouter-Routing, Geocoding, Selfhost-Sync). */
    val httpClient: HttpClient by lazy { OkHttpClientAdapter() }
}
