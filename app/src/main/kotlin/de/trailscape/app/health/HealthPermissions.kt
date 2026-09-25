package de.trailscape.app.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord

/**
 * Die Health-Connect-Leserechte, die Trailscape braucht.
 *
 * Gegenstueck zu `healthReadTypes` / `healthOptionalReadTypes` aus dem
 * Dart-Original (`lib/health_sync.dart`), nur eine Ebene tiefer: dort waren es
 * `HealthDataType`-Werte des `health`-Plugins, hier sind es direkt die
 * Berechtigungsstrings von `androidx.health.connect`.
 *
 * Die Aufteilung in [required] und [optional] ist das entscheidende Detail:
 * [HealthGateway.hasPermissions][de.trailscape.core.HealthGateway.hasPermissions]
 * darf nur gegen [required] pruefen. Sonst wuerde eine einzelne verweigerte
 * Zusatzfreigabe (HRV, VO2max, Routen) die gesamte Verbindung als „nicht
 * verbunden" erscheinen lassen, obwohl der Import laufen koennte —
 * `HealthSyncService.readVitals` faengt fehlende Zusatztypen ab und meldet sie
 * ueber `VitalsSummary.unavailable`.
 *
 * Alle hier gelisteten Strings muessen zusaetzlich im Manifest als
 * `uses-permission` stehen, sonst weist Health Connect die Anfrage ab.
 */
object HealthPermissions {

    /**
     * Leserecht fuer die GPS-Routen der Trainings.
     *
     * Bewusst als Stringliteral: `HealthPermission.PERMISSION_READ_EXERCISE_ROUTES`
     * existiert erst ab connect-client 1.2.0-alpha; die stabile 1.1.0-Reihe, gegen
     * die `:app` baut, kennt nur `PERMISSION_WRITE_EXERCISE_ROUTE` (Singular,
     * fuers Schreiben). Der Wert ist der offizielle Plattform-Permissionname und
     * deckt sich mit der `uses-permission`-Zeile im Manifest.
     */
    const val READ_EXERCISE_ROUTES: String = "android.permission.health.READ_EXERCISE_ROUTES"

    /**
     * Pflichtrechte: ohne sie ist kein sinnvoller Import moeglich.
     *
     * Entspricht `healthReadTypes` aus dem Dart-Original — mit einer Ausnahme:
     * `READ_STEPS` faellt weg. Das war dort nur noetig, weil das `health`-Plugin
     * jede Session intern mit Schrittdaten anreicherte; der native Reader hier
     * liest ausschliesslich, was er wirklich braucht.
     */
    val required: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )

    /**
     * Zusatzrechte: werden im selben Dialog mit angefragt, sind aber nicht
     * zwingend.
     *
     *  * HRV (rMSSD) und VO2max — wie im Dart-Original: nicht jede Uhr schreibt
     *    sie, und eine Verweigerung darf die Verbindung nicht entwerten.
     *  * Routen — Health Connect behandelt Routen als besonders sensibel und
     *    gibt sie je nach Provider-Version nur ueber einen eigenen Dialog pro
     *    Route heraus (`ExerciseRouteRequestContract`). Faellt die Freigabe aus,
     *    liefert `ExerciseSessionRecord.exerciseRouteResult` schlicht
     *    `ConsentRequired`/`NoData`; der Import laeuft weiter,
     *    `HealthSyncReport.routesMissing` zaehlt die betroffenen Touren und
     *    `HealthSyncReport.routeConsentPending` merkt sich die, deren Route per
     *    Einzel-Freigabe nachgeholt werden kann (`ui/health/RouteConsent.kt`).
     */
    val optional: Set<String> = setOf(
        READ_EXERCISE_ROUTES,
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
    )

    /**
     * Zugriff auf Daten, die aelter sind als 30 Tage vor der ersten Freigabe.
     *
     * Ohne dieses Recht liefert Health Connect grundsaetzlich nur die letzten
     * 30 Tage vor dem Zeitpunkt der Zustimmung — die Vitalhistorie und die
     * Ruhepuls-Baseline wollen aber bis zu 120 Tage. Optional, weil nicht
     * jede Health-Connect-Version das Recht kennt: Angefragt wird es nur,
     * wenn [HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY] verfuegbar
     * ist (siehe [requestSet]); die Verbindung gilt auch ohne als hergestellt.
     */
    const val READ_HEALTH_DATA_HISTORY: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** Was der Berechtigungsdialog anfragt: Pflicht- und Zusatzrechte in einem Rutsch. */
    val all: Set<String> = required + optional

    /**
     * Die tatsaechlich anzufragende Menge: [all], plus
     * [READ_HEALTH_DATA_HISTORY], sofern das Geraet die Funktion kennt. Ein
     * Recht anzufragen, das der Provider nicht kennt, ist bestenfalls
     * wirkungslos — deshalb die Pruefung vorab.
     */
    fun requestSet(client: HealthConnectClient): Set<String> =
        if (isHistoryFeatureAvailable(client)) all + READ_HEALTH_DATA_HISTORY else all

    /** Ob Health Connect auf diesem Geraet die Historien-Freigabe kennt. */
    fun isHistoryFeatureAvailable(client: HealthConnectClient): Boolean = try {
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (_: Throwable) {
        false
    }

    /** Ob [granted] alle Pflichtrechte enthaelt. */
    fun hasAllRequired(granted: Set<String>): Boolean = granted.containsAll(required)

    /**
     * Fragt die erteilten Rechte bei Health Connect ab und prueft [required].
     *
     * `suspend`, weil `PermissionController.getGrantedPermissions()` es ist —
     * der synchrone Rand liegt in
     * [HealthConnectGateway.hasPermissions].
     */
    suspend fun hasAllRequired(client: HealthConnectClient): Boolean =
        hasAllRequired(client.permissionController.getGrantedPermissions())
}
