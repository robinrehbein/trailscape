package de.trailscape.app.sensors.indoor

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
import android.os.SystemClock
import de.trailscape.app.sensors.bluetoothAdapter
import de.trailscape.app.sensors.bluetoothEnabled
import de.trailscape.app.sensors.hasConnectPermission
import de.trailscape.core.indoor.BleLink
import de.trailscape.core.indoor.BleLinkListener
import de.trailscape.core.BleUuids
import java.util.UUID

/**
 * # Das Rohr zu Trainer oder Schalttaster (Android-GATT)
 *
 * Die duenne Android-Seite von [BleLink] (Vertrag: `IndoorTrainer.kt` in
 * `:core`, `docs/indoor.md` Abschnitt 5.1). Alle Logik — Phasen, Schlange,
 * Fristen, Neuverbinden — steckt in [GattLinkKern]; hier stehen nur die
 * Aufrufe an `BluetoothGatt` und die Callbacks zurueck.
 *
 * ## Threading
 *  * Jede oeffentliche Methode postet auf [handler] (den BLE-Thread
 *    „trailscape-ble" aus `BleSensors`) — sie darf von jedem Thread kommen,
 *    auch aus einem Listener-Aufruf heraus.
 *  * GATT-Callbacks kommen auf einem Binder-Thread. Sie kopieren die Bytes,
 *    nehmen die Empfangszeit und posten auf [handler]. Dort wird ein Callback
 *    einer schon ersetzten oder geschlossenen GATT-Instanz verworfen — nach
 *    einem Neuverbinden darf kein spaetes Ereignis der alten Verbindung in
 *    die neue geraten.
 *  * Der Kern und damit der [BleLinkListener] laufen nur auf [handler].
 *
 * ## Verbindung
 * `connectGatt(context, autoConnect = false, callback, TRANSPORT_LE)`, kein
 * Bonding, keine Systemdialoge. `autoConnect = false`, weil der Kern den
 * Neuversuch selbst taktet (wie `BleSensorConnection`).
 *
 * ## API 33
 * Ab Android 13 die neuen `writeCharacteristic(ch, wert, typ)` /
 * `writeDescriptor(d, wert)` mit Statuscode, darunter die alten mit
 * `value`-Feld. Fuer `onCharacteristicChanged`/`onCharacteristicRead` gibt es
 * beide Ueberladungen; die alte kehrt ab API 33 sofort zurueck, sonst kaeme
 * jeder Wert doppelt.
 *
 * Der Geraetename kommt nur aus dem gemerkten Eintrag; `device.name` wird nie
 * gelesen.
 */
// Jeder Plattformaufruf ist vorab ueber `GattPort.darfVerbinden` geprueft und
// im Kern gegen SecurityException gesichert — Lint erkennt beides nicht.
@SuppressLint("MissingPermission")
internal class GattBleLink(
    private val context: Context,
    private val ref: IndoorDeviceRef,
    private val handler: Handler,
) : BleLink {

    override val deviceName: String? get() = ref.name

    /** Nur auf [handler]. */
    private var gatt: BluetoothGatt? = null

    private val takt = object : LinkTakt {
        override fun jetztMs(): Long = SystemClock.elapsedRealtime()

        override fun nach(verzoegerungMs: Long, block: () -> Unit): Abbrechbar {
            val r = Runnable { block() }
            handler.postDelayed(r, verzoegerungMs)
            return Abbrechbar { handler.removeCallbacks(r) }
        }
    }

    private val port = AndroidPort()
    private val kern = GattLinkKern(port, takt)

    override fun open(listener: BleLinkListener) {
        handler.post { kern.open(listener) }
    }

    override fun subscribe(serviceUuid: String, characteristicUuid: String, indicate: Boolean) {
        handler.post { kern.enqueue(GattOp.Abonnieren(serviceUuid, characteristicUuid.lowercase(), indicate)) }
    }

    override fun read(serviceUuid: String, characteristicUuid: String) {
        handler.post { kern.enqueue(GattOp.Lesen(serviceUuid, characteristicUuid.lowercase())) }
    }

    override fun write(serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean) {
        val kopie = value.copyOf() // der Aufrufer darf sein Array danach weiterverwenden
        handler.post {
            kern.enqueue(GattOp.Schreiben(serviceUuid, characteristicUuid.lowercase(), kopie, withResponse))
        }
    }

    override fun requestMtu(mtu: Int) {
        handler.post { kern.enqueue(GattOp.Mtu(mtu)) }
    }

    override fun close() {
        handler.post { kern.close() }
    }

    // ------------------------------------------------------------------ Port

    private inner class AndroidPort : GattPort {
        override fun bluetoothEin(): Boolean = bluetoothEnabled(context)

        override fun darfVerbinden(): Boolean = hasConnectPermission(context)

        override fun verbinde(): Boolean {
            schliessen()
            val adapter = bluetoothAdapter(context) ?: return false
            val device = try {
                adapter.getRemoteDevice(ref.address)
            } catch (e: IllegalArgumentException) {
                return false // ungueltige Adresse im gemerkten Eintrag
            }
            gatt = device.connectGatt(context, false, Callback(), BluetoothDevice.TRANSPORT_LE)
            return gatt != null
        }

        override fun discoverServices(): Boolean = gatt?.discoverServices() == true

        override fun serviceUuids(): Set<String> =
            gatt?.services.orEmpty().map { it.uuid.toString().lowercase() }.toSet()

        override fun starte(op: GattOp): Int {
            val g = gatt ?: return GattLinkKern.STATUS_NICHT_BEREIT
            return when (op) {
                is GattOp.Mtu -> if (g.requestMtu(op.mtu)) OK else GattLinkKern.STATUS_ABGELEHNT
                is GattOp.Lesen -> {
                    val ch = finde(g, op.serviceUuid, op.characteristicUuid) ?: return fehlt(op)
                    if (g.readCharacteristic(ch)) OK else GattLinkKern.STATUS_ABGELEHNT
                }
                is GattOp.Schreiben -> {
                    val ch = finde(g, op.serviceUuid, op.characteristicUuid) ?: return fehlt(op)
                    schreibe(g, ch, op.wert, op.mitAntwort)
                }
                is GattOp.Abonnieren -> {
                    val ch = finde(g, op.serviceUuid, op.characteristicUuid) ?: return fehlt(op)
                    abonniere(g, ch, op.indicate)
                }
            }
        }

        override fun schliessen() {
            val g = gatt ?: return
            gatt = null
            try {
                g.disconnect()
            } catch (e: SecurityException) {
                // Ohne Berechtigung laesst sich nur noch schliessen.
            }
            try {
                g.close()
            } catch (e: SecurityException) {
                // close() gibt nur lokale Ressourcen frei.
            }
        }

        private fun finde(g: BluetoothGatt, service: String, characteristic: String): BluetoothGattCharacteristic? =
            try {
                g.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(characteristic))
            } catch (e: IllegalArgumentException) {
                null
            }

        private fun fehlt(op: GattOp): Int = GattLinkKern.STATUS_DIENST_FEHLT.also {
            de.trailscape.core.DiagLog.shared.log(de.trailscape.core.DiagEvent.BLE_SERVICE_MISSING, code = op.art.ordinal)
        }

        private fun schreibe(g: BluetoothGatt, ch: BluetoothGattCharacteristic, wert: ByteArray, mitAntwort: Boolean): Int {
            val typ = if (mitAntwort) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = g.writeCharacteristic(ch, wert, typ)
                if (code == BluetoothStatusCodes.SUCCESS) OK else statusCode(code)
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = typ
                @Suppress("DEPRECATION")
                ch.value = wert
                @Suppress("DEPRECATION")
                if (g.writeCharacteristic(ch)) OK else GattLinkKern.STATUS_ABGELEHNT
            }
        }

        private fun abonniere(g: BluetoothGatt, ch: BluetoothGattCharacteristic, indicate: Boolean): Int {
            val noetig = if (indicate) {
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            } else {
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
            }
            if (ch.properties and noetig == 0) return GattLinkKern.STATUS_NICHT_UNTERSTUETZT
            val cccd = ch.getDescriptor(UUID.fromString(BleUuids.CCCD)) ?: return GattLinkKern.STATUS_DIENST_FEHLT
            if (!g.setCharacteristicNotification(ch, true)) return GattLinkKern.STATUS_ABGELEHNT
            // CCCD 0x2902: 0x0001 Notifications, 0x0002 Indications (little endian).
            val wert = if (indicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = g.writeDescriptor(cccd, wert)
                if (code == BluetoothStatusCodes.SUCCESS) OK else statusCode(code)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = wert
                @Suppress("DEPRECATION")
                if (g.writeDescriptor(cccd)) OK else GattLinkKern.STATUS_ABGELEHNT
            }
        }

        /** Ein `BluetoothStatusCodes`-Fehler, so verschoben, dass er nie 0 und nie ein eigener Code ist. */
        private fun statusCode(code: Int): Int = if (code > 0) 1000 + code else GattLinkKern.STATUS_ABGELEHNT
    }

    // -------------------------------------------------------------- Callback

    private inner class Callback : BluetoothGattCallback() {
        /** Postet [block] auf den BLE-Thread, sofern [g] noch die aktuelle Verbindung ist. */
        private fun aufBleThread(g: BluetoothGatt, block: () -> Unit) {
            handler.post {
                if (g !== gatt) return@post
                try {
                    block()
                } catch (e: SecurityException) {
                    kern.onKeineBerechtigung()
                }
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            aufBleThread(g) { kern.onVerbindung(status, newState == BluetoothProfile.STATE_CONNECTED) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            aufBleThread(g) { kern.onDiensteErkannt(status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.characteristic.uuid.toString()
            aufBleThread(g) { kern.onDescriptorGeschrieben(uuid, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val uuid = characteristic.uuid.toString()
            aufBleThread(g) { kern.onGeschrieben(uuid, status) }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val uuid = characteristic.uuid.toString()
            val kopie = value.copyOf()
            aufBleThread(g) { kern.onGelesen(uuid, kopie, status) }
        }

        @Deprecated("Nur fuer API < 33, siehe Klassenkommentar")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val uuid = characteristic.uuid.toString()

            @Suppress("DEPRECATION")
            val kopie = characteristic.value?.copyOf() ?: ByteArray(0)
            aufBleThread(g) { kern.onGelesen(uuid, kopie, status) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            aufBleThread(g) { kern.onMtu(status) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            weiterreichen(g, characteristic.uuid.toString(), value.copyOf())
        }

        @Deprecated("Nur fuer API < 33, siehe Klassenkommentar")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            weiterreichen(g, characteristic.uuid.toString(), value.copyOf())
        }

        private fun weiterreichen(g: BluetoothGatt, uuid: String, value: ByteArray) {
            val empfangen = SystemClock.elapsedRealtime()
            aufBleThread(g) { kern.onWert(uuid, value, empfangen) }
        }
    }

    private companion object {
        const val OK = GattLinkKern.GATT_SUCCESS
    }
}
