package de.trailscape.app.sensors.indoor

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import de.trailscape.app.sensors.bluetoothAdapter
import de.trailscape.app.sensors.bluetoothEnabled
import de.trailscape.app.sensors.hasBluetoothPermissions
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.indoor.IndoorUuids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Die Suche nach Rollentrainer und Schalttaster — nur auf Knopfdruck, nur
 * solange die Seite offen ist, nach [DAUER_MS] von selbst zu Ende.
 *
 * Drei [ScanFilter]: der Fitness Machine Service, der Zwift-Dienst und die
 * Zwift-Firmenkennung in den Herstellerdaten (manche Schalttaster bewerben
 * ihren Dienst nicht). Andere Geraete in der Naehe tauchen gar nicht erst
 * auf — deshalb darf BLUETOOTH_SCAN `neverForLocation` tragen. Ein leeres
 * Datenmuster beim Herstellerfilter heisst: jede Nutzlast mit dieser
 * Kennung passt.
 *
 * Gleiches Muster wie `BleScanner` der Sensoren; Ergebnisse kommen auf dem
 * Hauptthread an, und die Seite sperrt den Knopf, solange [laeuft] (Android
 * drosselt mehr als fuenf Starts in 30 s stillschweigend).
 */
// Aufrufe nur nach `hasBluetoothPermissions` (siehe [start]) und zusaetzlich
// gegen SecurityException gesichert.
@SuppressLint("MissingPermission")
internal class IndoorScanner(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val _treffer = MutableStateFlow<List<IndoorFund>>(emptyList())
    private val _fehler = MutableStateFlow<Int?>(null)
    private val _laeuft = MutableStateFlow(false)

    val treffer: StateFlow<List<IndoorFund>> = _treffer.asStateFlow()
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
        val filter = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(IndoorUuids.FTMS_SERVICE))).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(IndoorUuids.ZWIFT_SERVICE))).build(),
            ScanFilter.Builder().setManufacturerData(IndoorUuids.ZWIFT_COMPANY_ID, ByteArray(0)).build(),
        )
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
        val hersteller = record?.manufacturerSpecificData
        val herstellerIds = buildList { if (hersteller != null) for (i in 0 until hersteller.size()) add(hersteller.keyAt(i)) }
        val rollen = indoorRollenAusWerbung(record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(), herstellerIds)
        if (rollen.isEmpty()) return
        val adresse = result.device.address ?: return
        val fund = IndoorFund(
            adresse = adresse,
            name = record?.deviceName?.takeIf { it.isNotBlank() },
            rollen = rollen,
            rssi = result.rssi,
        )
        _treffer.value = fuegeIndoorFundHinzu(_treffer.value, fund)
    }

    companion object {
        /** Dauer einer Suche. */
        const val DAUER_MS = 20_000L
    }
}
