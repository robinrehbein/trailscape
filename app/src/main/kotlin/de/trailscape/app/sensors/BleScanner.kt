package de.trailscape.app.sensors

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import de.trailscape.core.BleSensorTyp
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.sensorTypenAusDiensten
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Ein Suchtreffer: Adresse, beworbener Name, erkannte Sensorarten, Signalstaerke. */
data class BleFund(
    val adresse: String,
    val name: String?,
    val typen: Set<BleSensorTyp>,
    val rssi: Int,
)

/**
 * Die Suche fuer die Seite Mehr → Sensoren — nur dort und nur auf Knopfdruck.
 *
 * Gesucht wird mit drei [ScanFilter]n auf die Dienst-UUIDs der
 * Standardprofile: Andere Geraete in der Naehe tauchen gar nicht erst auf,
 * und genau deshalb darf BLUETOOTH_SCAN im Manifest `neverForLocation` tragen.
 * Nach [DAUER_MS] endet die Suche von selbst (Akku); Android drosselt ausserdem
 * mehr als fuenf Starts in 30 s stillschweigend — die Seite sperrt deshalb
 * den Knopf, solange [laeuft].
 *
 * Grenze: Ein Sensor, der seine Dienst-UUID nicht in der Werbung nennt, wird
 * so nicht gefunden. Die drei Profile sehen das Nennen vor; bekannt
 * abweichende Geraete gibt es, getestet ist das nicht (siehe Bericht).
 */
// Aufrufe nur nach `hasBluetoothPermissions` (siehe [start]) und zusaetzlich
// gegen SecurityException gesichert.
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val _treffer = MutableStateFlow<List<BleFund>>(emptyList())
    private val _fehler = MutableStateFlow<Int?>(null)
    private val _laeuft = MutableStateFlow(false)

    val treffer: StateFlow<List<BleFund>> = _treffer.asStateFlow()
    val fehler: StateFlow<Int?> = _fehler.asStateFlow()
    val laeuft: StateFlow<Boolean> = _laeuft.asStateFlow()

    private val autoStopp = Runnable { stop() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { uebernimm(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            main.post { results.forEach { uebernimm(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                DiagLog.shared.log(DiagEvent.BLE_SCAN_FAILED, code = errorCode)
                _fehler.value = errorCode
                _laeuft.value = false
                main.removeCallbacks(autoStopp)
            }
        }
    }

    /** Startet die Suche; `false`, wenn es nicht ging (Berechtigung, Bluetooth aus). */
    fun start(): Boolean {
        if (_laeuft.value) return true
        if (!hasBluetoothPermissions(context) || !bluetoothEnabled(context)) return false
        val scanner = bluetoothAdapter(context)?.bluetoothLeScanner ?: return false
        val filter = BleSensorTyp.entries.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(it.dienstUuid))).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            _fehler.value = null
            _treffer.value = emptyList()
            scanner.startScan(filter, settings, callback)
            _laeuft.value = true
            main.postDelayed(autoStopp, DAUER_MS)
            true
        } catch (e: SecurityException) {
            DiagLog.shared.log(DiagEvent.BLE_NO_PERMISSION, error = e)
            false
        } catch (e: IllegalStateException) {
            // Bluetooth wurde gerade ausgeschaltet.
            false
        }
    }

    /** Beendet die Suche; idempotent. */
    fun stop() {
        main.removeCallbacks(autoStopp)
        if (!_laeuft.value) return
        _laeuft.value = false
        try {
            bluetoothAdapter(context)?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            // Berechtigung inzwischen entzogen — die Suche endet dann ohnehin.
        } catch (e: IllegalStateException) {
            // Bluetooth aus — dito.
        }
    }

    private fun uebernimm(result: ScanResult) {
        if (!_laeuft.value) return
        val record = result.scanRecord
        val typen = sensorTypenAusDiensten(record?.serviceUuids?.map { it.uuid.toString() }.orEmpty())
        if (typen.isEmpty()) return
        val adresse = result.device.address ?: return
        val fund = BleFund(
            adresse = adresse,
            name = record?.deviceName?.takeIf { it.isNotBlank() },
            typen = typen,
            rssi = result.rssi,
        )
        _treffer.value = fuegeFundHinzu(_treffer.value, fund)
    }

    companion object {
        /** Dauer einer Suche. */
        const val DAUER_MS = 20_000L
    }
}

/**
 * Fuegt [fund] in [liste] ein: je Adresse ein Eintrag (Typen vereinigt, Name
 * behalten, falls der neue keinen hat, Signal aktualisiert), sortiert nach
 * Staerke — der naechste Sensor steht oben, und das ist fast immer der eigene.
 */
internal fun fuegeFundHinzu(liste: List<BleFund>, fund: BleFund): List<BleFund> {
    val alt = liste.firstOrNull { it.adresse == fund.adresse }
    val neu = if (alt == null) {
        fund
    } else {
        fund.copy(name = fund.name ?: alt.name, typen = alt.typen + fund.typen)
    }
    return (liste.filter { it.adresse != fund.adresse } + neu).sortedByDescending { it.rssi }
}
