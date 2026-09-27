package de.trailscape.app.sensors

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import de.trailscape.core.BleSensorTyp
import de.trailscape.core.BleUuids
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.GemerkterSensor
import java.util.UUID

/**
 * Eine GATT-Verbindung zu genau einem gemerkten Sensor.
 *
 * ## Ablauf
 * `connectGatt(autoConnect = false, TRANSPORT_LE)` → bei CONNECTED
 * `discoverServices` → Messwert-Characteristic des Typs suchen →
 * Notifications einschalten (lokal per `setCharacteristicNotification` und
 * am Sensor per CCCD `0x2902` = `0x0100`). Danach kommt jede Messung als
 * Notification und geht roh an den [Listener]; das Parsen macht `:core`.
 *
 * `autoConnect = false`, weil die App den Neuversuch selbst taktet (siehe
 * `neuerVersuchNachMs` in `:core`): Der Hintergrund-Autoconnect von Android
 * verbindet je nach Hersteller erst nach Minuten und ist nicht abbrechbar.
 *
 * ## Threading
 * Die GATT-Callbacks kommen auf einem Binder-Thread. Jeder wird sofort auf
 * [handler] (den BLE-Thread von [BleSensors]) gepostet; alle Zustaende dieser
 * Klasse werden nur dort angefasst. Ein Callback einer bereits ersetzten
 * GATT-Instanz wird verworfen.
 *
 * ## Fehler
 * Jeder Abbruch — Status 133, Zeitueberschreitung, fehlender Dienst — endet
 * gleich: `close()` und [Listener.getrennt]; der Neuversuch ist Sache von
 * [BleSensors]. Eine [SecurityException] (Berechtigung entzogen) meldet
 * [Listener.keineBerechtigung] statt abzustuerzen.
 *
 * Der Name des Sensors kommt ausschliesslich aus dem gemerkten Eintrag;
 * `device.name` wird im Hintergrund nie gelesen (braucht BLUETOOTH_CONNECT und
 * gehoert nicht ins Log).
 */
// Jeder Plattformaufruf hier ist durch [BleSensors] vorab mit
// `hasConnectPermission` geprueft und zusaetzlich gegen SecurityException
// gesichert — Lint erkennt keines von beiden als Pruefung.
@SuppressLint("MissingPermission")
internal class BleSensorConnection(
    private val context: Context,
    val sensor: GemerkterSensor,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun verbunden(typ: BleSensorTyp)
        fun daten(typ: BleSensorTyp, nutzlast: ByteArray, empfangenMs: Long)
        fun getrennt(typ: BleSensorTyp, status: Int)
        fun keineBerechtigung(typ: BleSensorTyp)
    }

    private var gatt: BluetoothGatt? = null
    private var geschlossen = false

    /**
     * Frist fuer den ganzen Aufbau: CONNECTED, Dienstsuche und CCCD-Schreiben.
     * Manche Stacks melden gar nichts, wenn der Sensor schlaeft, und bekannte
     * Android-Fehler lassen `onServicesDiscovered` oder `onDescriptorWrite`
     * nie kommen — ohne Frist bliebe die Verbindung ewig „verbunden" ohne
     * Daten und ohne Neuversuch. Erst ein erfolgreiches CCCD-Schreiben hebt
     * die Frist auf.
     *
     * Bewusst keine Stille-Frist danach: Trittfrequenz- und Leistungssensoren
     * schweigen im Stand oft ganz legitim; ein Abbruch nach Stille wuerde bei
     * jeder Pause neu verbinden.
     */
    private val watchdog = Runnable {
        scheitern(STATUS_ZEITUEBERSCHREITUNG)
    }

    /** Baut die Verbindung auf. Nur auf [handler] aufrufen. */
    fun verbinden() {
        if (geschlossen) return
        schliesseGatt()
        val adapter = bluetoothAdapter(context)
        if (adapter == null) {
            listener.getrennt(sensor.typ, STATUS_KEIN_ADAPTER)
            return
        }
        try {
            val device = adapter.getRemoteDevice(sensor.adresse)
            val callback = Callback()
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, VERBINDUNGS_FRIST_MS)
            if (gatt == null) scheitern(STATUS_KEIN_GATT)
        } catch (e: SecurityException) {
            schliesseGatt()
            DiagLog.shared.log(DiagEvent.BLE_NO_PERMISSION, error = e)
            listener.keineBerechtigung(sensor.typ)
        } catch (e: IllegalArgumentException) {
            // Ungueltige Adresse im gemerkten Eintrag.
            scheitern(STATUS_UNGUELTIGE_ADRESSE)
        }
    }

    /** Trennt und gibt alles frei; idempotent. Nur auf [handler] aufrufen. */
    fun schliessen() {
        geschlossen = true
        handler.removeCallbacks(watchdog)
        val g = gatt ?: return
        try {
            g.disconnect()
        } catch (e: SecurityException) {
            // Ohne Berechtigung laesst sich nur noch schliessen.
        }
        schliesseGatt()
    }

    private fun schliesseGatt() {
        val g = gatt ?: return
        gatt = null
        try {
            g.close()
        } catch (e: SecurityException) {
            // close() gibt nur lokale Ressourcen frei; ohne Berechtigung
            // bleibt nichts weiter zu tun.
        }
    }

    private fun scheitern(status: Int) {
        handler.removeCallbacks(watchdog)
        schliesseGatt()
        if (geschlossen) return
        DiagLog.shared.log(DiagEvent.BLE_GATT_ERROR, code = status)
        listener.getrennt(sensor.typ, status)
    }

    private fun notificationsEinschalten(g: BluetoothGatt) {
        val ch = g.getService(UUID.fromString(sensor.typ.dienstUuid))
            ?.getCharacteristic(UUID.fromString(sensor.typ.messungUuid))
        val cccd = ch?.getDescriptor(UUID.fromString(BleUuids.CCCD))
        if (ch == null || cccd == null) {
            DiagLog.shared.log(DiagEvent.BLE_SERVICE_MISSING, code = sensor.typ.ordinal)
            scheitern(STATUS_DIENST_FEHLT)
            return
        }
        if (!g.setCharacteristicNotification(ch, true)) {
            scheitern(STATUS_NOTIFICATION_ABGELEHNT)
            return
        }
        val wert = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, wert) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = wert
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        if (!ok) scheitern(STATUS_NOTIFICATION_ABGELEHNT)
    }

    private inner class Callback : BluetoothGattCallback() {
        /** Fuehrt [block] auf dem BLE-Thread aus, sofern [g] noch die aktuelle Verbindung ist. */
        private fun aufBleThread(g: BluetoothGatt, block: () -> Unit) {
            handler.post {
                if (g !== gatt || geschlossen) return@post
                try {
                    block()
                } catch (e: SecurityException) {
                    schliesseGatt()
                    DiagLog.shared.log(DiagEvent.BLE_NO_PERMISSION, error = e)
                    listener.keineBerechtigung(sensor.typ)
                }
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            aufBleThread(g) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    // Die Frist laeuft weiter bis zum erfolgreichen CCCD-Schreiben.
                    listener.verbunden(sensor.typ)
                    if (!g.discoverServices()) scheitern(STATUS_KEINE_DIENSTSUCHE)
                } else {
                    scheitern(status)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            aufBleThread(g) {
                if (status == BluetoothGatt.GATT_SUCCESS) notificationsEinschalten(g) else scheitern(status)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            aufBleThread(g) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    handler.removeCallbacks(watchdog)
                } else {
                    scheitern(status)
                }
            }
        }

        // Ab API 33: der Wert kommt als eigenes Array, sicher gegen spaetere
        // Pakete, die dieselbe Characteristic ueberschreiben.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            weiterreichen(g, value.copyOf())
        }

        // Bis API 32. Ab API 33 ruft Android BEIDE Ueberladungen auf; die
        // alte kehrt dort sofort zurueck, sonst kaeme jeder Wert doppelt.
        @Deprecated("Nur fuer API < 33, siehe Kommentar")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            weiterreichen(g, value.copyOf())
        }

        private fun weiterreichen(g: BluetoothGatt, value: ByteArray) {
            val empfangen = System.currentTimeMillis()
            aufBleThread(g) { listener.daten(sensor.typ, value, empfangen) }
        }
    }

    companion object {
        /** Frist fuer den Verbindungsaufbau bis einschliesslich CCCD-Schreiben. */
        const val VERBINDUNGS_FRIST_MS = 20_000L

        // Eigene Codes fuer das Diagnoseprotokoll — negativ, damit sie sich
        // nie mit einem echten GATT-Status (0…255) ueberschneiden.
        const val STATUS_ZEITUEBERSCHREITUNG = -1
        const val STATUS_KEIN_ADAPTER = -2
        const val STATUS_KEIN_GATT = -3
        const val STATUS_UNGUELTIGE_ADRESSE = -4
        const val STATUS_DIENST_FEHLT = -5
        const val STATUS_NOTIFICATION_ABGELEHNT = -6
        const val STATUS_KEINE_DIENSTSUCHE = -7
    }
}
