package de.trailscape.app.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import de.trailscape.app.health.HealthConnectGateway
import de.trailscape.app.reminder.ReminderStore
import de.trailscape.app.routing.RoutingServerSettings
import de.trailscape.app.routing.SegmentDownloadWorker
import de.trailscape.app.routing.SegmentDownloader
import de.trailscape.app.routing.SegmentInventory
import de.trailscape.app.routing.SegmentMetadataStore
import de.trailscape.app.routing.SegmentSettings
import de.trailscape.app.update.UpdateChecker
import de.trailscape.app.update.runNumberFromVersionCode
import de.trailscape.core.HealthGateway
import de.trailscape.core.HealthSyncService
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
 * `AppServices.healthGateway`, `AppServices.healthSyncService`,
 * `AppServices.updateChecker`, `AppServices.reminderStore`,
 * `AppServices.appScope`, `AppServices.segmentInventory`,
 * `AppServices.segmentDownloader`, `AppServices.segmentSettings`,
 * `AppServices.routingServerSettings`.
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

    /**
     * Loesch-Merkzettel des Selfhost-Syncs (siehe [TombstoneStore]) — liegt
     * im selben Verzeichnis wie die Tour-Dateien (`<filesDir>/rides`).
     */
    val tombstoneStore: TombstoneStore by lazy {
        TombstoneStore(File(appContext.filesDir, "rides"))
    }

    private val prefs by lazy { trailscapePrefs(appContext) }

    /** Implementierung von `:core`s [KeyValueStore] (u. a. fuer `SyncConfig`). */
    val keyValueStore: KeyValueStore by lazy { PrefsKeyValueStore(prefs) }

    /** Implementierung von `:core`s [HealthSyncStore] (Zeitstempel des letzten Health-Imports). */
    val healthSyncStore: HealthSyncStore by lazy { PrefsHealthSyncStore(prefs) }

    /** Implementierung von `:core`s [TrainingPlanStore]. */
    val trainingPlanStore: TrainingPlanStore by lazy { PrefsTrainingPlanStore(prefs) }

    /**
     * Einstellungen und Meldestand der lokalen Erinnerungen (siehe
     * [de.trailscape.app.reminder.ReminderScheduler]). Liegt auf demselben
     * [keyValueStore] wie Profil und Kartenstil — die Erinnerungen bringen
     * keinen eigenen Speicher mit.
     */
    val reminderStore: ReminderStore by lazy { ReminderStore(keyValueStore) }

    /** Implementierung von `:core`s [HttpClient] (BRouter-Routing, Geocoding, Selfhost-Sync). */
    val httpClient: HttpClient by lazy { OkHttpClientAdapter() }

    /**
     * Der Bestand an Offline-Routing-Kacheln unter `<filesDir>/segments`
     * (siehe [OfflineRoutingFiles.segmentDir]).
     *
     * Die Metadaten je Kachel (`ETag`, `Last-Modified`, Zeitpunkt des
     * Downloads) liegen auf demselben [keyValueStore] wie alles andere — die
     * Kachelverwaltung bringt keinen eigenen Speicher mit.
     */
    val segmentInventory: SegmentInventory by lazy {
        SegmentInventory(
            dir = OfflineRoutingFiles.segmentDir(appContext),
            metadata = SegmentMetadataStore(keyValueStore),
        )
    }

    /**
     * Holt und aktualisiert Kacheln (siehe [SegmentDownloader]).
     *
     * Bewusst ein eigener OkHttp-Client mit anderen Zeitgrenzen als
     * [httpClient] — Begruendung im KDoc von `routing/SegmentDownloader.kt`.
     * Aufrufe blockieren und gehoeren in den [SegmentDownloadWorker], nicht in
     * ein ViewModel.
     */
    val segmentDownloader: SegmentDownloader by lazy { SegmentDownloader(segmentInventory) }

    /** „Nur im WLAN laden?" — die eine Einstellung der Kachelverwaltung. */
    val segmentSettings: SegmentSettings by lazy { SegmentSettings(keyValueStore) }

    /**
     * Die eigene Server-URL fuer die Routenberechnung (siehe
     * [RoutingServerSettings]) — leer ist die Vorgabe (oeffentlicher
     * brouter.de). Betrifft NICHT [segmentDownloader]; Begruendung dort.
     */
    val routingServerSettings: RoutingServerSettings by lazy { RoutingServerSettings(keyValueStore) }

    /**
     * Die Lauf-Nummer der installierten APK — `versionCode` minus dem in
     * `app/build.gradle.kts` gesetzten Offset (siehe
     * [de.trailscape.app.update.runNumberFromVersionCode]).
     *
     * `null`, wenn der PackageManager nichts hergibt oder der Code aus einer
     * fremden Pipeline stammt; der Update-Check laesst es dann bleiben, statt
     * gegen eine geratene Zahl zu vergleichen.
     */
    val installedRunNumber: Int? by lazy {
        runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            // PackageInfoCompat statt `versionCode`: Letzteres ist seit API 28
            // zugunsten von `longVersionCode` veraltet, minSdk ist 26.
            runNumberFromVersionCode(PackageInfoCompat.getLongVersionCode(info))
        }.getOrNull()
    }

    /**
     * Der Update-Kanal (siehe [UpdateChecker]). Benutzt denselben
     * [httpClient] und [keyValueStore] wie der Rest der App.
     */
    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(
            httpClient = httpClient,
            store = keyValueStore,
            installedRunNumber = { installedRunNumber },
        )
    }

    /**
     * Implementierung von `:core`s [HealthGateway] gegen
     * `androidx.health.connect:connect-client` (siehe
     * [de.trailscape.app.health.HealthConnectGateway]).
     *
     * Alle Methoden blockieren den aufrufenden Thread — Zugriffe gehoeren auf
     * [appScope] bzw. einen anderen `Dispatchers.IO`-Kontext, nie auf den
     * Main-Thread.
     */
    val healthGateway: HealthGateway by lazy { HealthConnectGateway(appContext) }

    /**
     * Der Import-/Vitaldaten-Dienst aus `:core`.
     *
     * Bekommt bewusst nur [healthGateway] und [healthSyncStore]: Die Touren
     * gibt der Aufrufer bei jedem Lauf selbst herein
     * (`importWithReport(existing = ...)`) und speichert das Ergebnis auch
     * selbst — `HealthSyncService` kennt weder [RideStorage] noch das
     * Dateisystem. Der uebliche Ablauf in Phase 4 ist deshalb:
     * `rideStorage.listRides()` → `healthSyncService.importWithReport(...)` →
     * die Touren aus `imported` und `mergedRides` ueber [rideStorage]
     * zurueckschreiben.
     *
     * Der dritte Konstruktorparameter (`now`) bleibt auf seiner Vorgabe
     * `LocalDateTime.now()`; er existiert nur, damit die `:core`-Tests die Uhr
     * festhalten koennen.
     */
    val healthSyncService: HealthSyncService by lazy {
        HealthSyncService(gateway = healthGateway, store = healthSyncStore)
    }
}
