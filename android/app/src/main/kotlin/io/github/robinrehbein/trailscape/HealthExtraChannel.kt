package io.github.robinrehbein.trailscape

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Startet den Health-Connect-Berechtigungsdialog fuer VO2max.
 *
 * Implementiert die Activity: `registerForActivityResult` darf nur vor
 * `onStart` aufgerufen werden, also beim Aufbau der Activity und nicht erst,
 * wenn ein MethodCall hereinkommt.
 */
interface Vo2MaxPermissionRequester {
    /**
     * Zeigt den Dialog an. [onResult] wird auf dem Main-Thread mit `true`
     * aufgerufen, wenn die VO2max-Leseberechtigung danach erteilt ist.
     */
    fun requestVo2MaxPermission(onResult: (Boolean) -> Unit)
}

/**
 * Schmaler Platform-Channel fuer Health-Connect-Datentypen, die das
 * Flutter-Paket `health` nicht abdeckt.
 *
 * Aktuell nur VO2max (`Vo2MaxRecord`): Health Connect kennt den Datensatz,
 * das Plugin bietet keinen passenden `HealthDataType` an.
 *
 * Methoden (siehe `lib/health_sync.dart`):
 *
 *  * `readVo2Max(startMs: Long, endMs: Long)` -> `List<Map<String, Any>>` mit
 *    `timeMs` (Long) und `vo2` (Double).
 *  * `requestVo2MaxPermission()` -> `Boolean`.
 *
 * Fehler werden als Channel-Error gemeldet (`unavailable`,
 * `permission_denied`, `read_failed`, `bad_args`) — die Dart-Seite behandelt
 * VO2max dann als nicht verfuegbar, ohne dass die App abstuerzt.
 */
class HealthExtraChannel(
    private val context: Context,
    private val permissionRequester: Vo2MaxPermissionRequester,
) : MethodChannel.MethodCallHandler {

    companion object {
        const val CHANNEL_NAME = "trailscape/health_extra"

        /** Health-Connect-Berechtigungsstring fuer das Lesen von VO2max. */
        val VO2MAX_READ_PERMISSION: String =
            HealthPermission.getReadPermission(Vo2MaxRecord::class)
    }

    // Dispatchers.Default statt Dispatchers.Main: haelt die Abhaengigkeit auf
    // kotlinx-coroutines-core beschraenkt. Antworten gehen ueber den
    // Main-Looper zurueck, weil MethodChannel.Result nur dort aufgerufen
    // werden darf.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var channel: MethodChannel? = null

    /** Registriert den Channel auf dem Messenger der Flutter-Engine. */
    fun attachTo(messenger: BinaryMessenger) {
        detach()
        channel = MethodChannel(messenger, CHANNEL_NAME).also {
            it.setMethodCallHandler(this)
        }
    }

    /** Loest den Channel wieder von der Engine. */
    fun detach() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    /** Wie [detach], beendet zusaetzlich laufende Abfragen. */
    fun dispose() {
        detach()
        scope.cancel()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "readVo2Max" -> {
                val startMs = (call.argument<Any>("startMs") as? Number)?.toLong()
                val endMs = (call.argument<Any>("endMs") as? Number)?.toLong()
                if (startMs == null || endMs == null) {
                    result.error(
                        "bad_args",
                        "startMs und endMs sind Pflichtangaben.",
                        null,
                    )
                    return
                }
                readVo2Max(startMs, endMs, result)
            }

            "requestVo2MaxPermission" -> requestVo2MaxPermission(result)

            else -> result.notImplemented()
        }
    }

    private fun readVo2Max(startMs: Long, endMs: Long, result: MethodChannel.Result) {
        scope.launch {
            val client = client()
            if (client == null) {
                fail(result, "unavailable", "Health Connect ist nicht verfuegbar.")
                return@launch
            }

            try {
                val samples = ArrayList<Map<String, Any>>()
                val filter = TimeRangeFilter.between(
                    Instant.ofEpochMilli(startMs),
                    Instant.ofEpochMilli(endMs),
                )

                // Health Connect liefert seitenweise (Standard 1000 Records);
                // ohne die pageToken-Schleife fehlten aeltere Messungen.
                var pageToken: String? = null
                do {
                    val response = client.readRecords(
                        ReadRecordsRequest(
                            recordType = Vo2MaxRecord::class,
                            timeRangeFilter = filter,
                            pageToken = pageToken,
                        )
                    )
                    for (record in response.records) {
                        samples.add(
                            mapOf<String, Any>(
                                "timeMs" to record.time.toEpochMilli(),
                                "vo2" to record.vo2MillilitersPerMinuteKilogram,
                            )
                        )
                    }
                    pageToken = response.pageToken
                } while (pageToken != null)

                succeed(result, samples)
            } catch (error: SecurityException) {
                fail(
                    result,
                    "permission_denied",
                    "Keine Leseberechtigung fuer VO2max: ${error.message}",
                )
            } catch (error: Throwable) {
                fail(
                    result,
                    "read_failed",
                    "VO2max konnte nicht gelesen werden: ${error.message}",
                )
            }
        }
    }

    private fun requestVo2MaxPermission(result: MethodChannel.Result) {
        scope.launch {
            val client = client()
            if (client == null) {
                fail(result, "unavailable", "Health Connect ist nicht verfuegbar.")
                return@launch
            }

            val alreadyGranted = try {
                client.permissionController.getGrantedPermissions()
                    .contains(VO2MAX_READ_PERMISSION)
            } catch (error: Throwable) {
                false
            }

            if (alreadyGranted) {
                succeed(result, true)
                return@launch
            }

            mainHandler.post {
                try {
                    permissionRequester.requestVo2MaxPermission { granted ->
                        result.success(granted)
                    }
                } catch (error: Throwable) {
                    // z. B. IllegalStateException, wenn die Activity schon weg ist.
                    result.error(
                        "permission_request_failed",
                        "Der VO2max-Dialog konnte nicht geoeffnet werden: ${error.message}",
                        null,
                    )
                }
            }
        }
    }

    /**
     * Client oder `null`, wenn Health Connect fehlt, veraltet ist oder sich
     * nicht instanziieren laesst.
     */
    private fun client(): HealthConnectClient? = try {
        if (HealthConnectClient.getSdkStatus(context) ==
            HealthConnectClient.SDK_AVAILABLE
        ) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    } catch (error: Throwable) {
        null
    }

    private fun succeed(result: MethodChannel.Result, value: Any?) {
        mainHandler.post { result.success(value) }
    }

    private fun fail(result: MethodChannel.Result, code: String, message: String) {
        mainHandler.post { result.error(code, message, null) }
    }
}
