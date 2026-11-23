package de.trailscape.core.indoor

/**
 * Attrappe des Rohrs fuer die Schalttaster-Tests: merkt jede Operation als
 * lesbare Zeile und laesst den Test die Antworten von Hand einspielen.
 * [onWrite] erlaubt synchrone Rueckrufe aus `write` (Reentranz).
 */
internal class ClickFakeLink(override val deviceName: String? = "Zwift Click") : BleLink {
    var listener: BleLinkListener? = null
        private set
    val ops = mutableListOf<String>()
    var opens = 0
        private set
    var closes = 0
        private set
    var onWrite: ((ClickFakeLink, ByteArray) -> Unit)? = null

    override fun open(listener: BleLinkListener) {
        opens++
        this.listener = listener
    }

    override fun subscribe(serviceUuid: String, characteristicUuid: String, indicate: Boolean) {
        ops += "sub ${kurz(serviceUuid)} ${kurz(characteristicUuid)} ${if (indicate) "indicate" else "notify"}"
    }

    override fun read(serviceUuid: String, characteristicUuid: String) {
        ops += "read ${kurz(serviceUuid)} ${kurz(characteristicUuid)}"
    }

    override fun write(serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean) {
        ops += "write ${kurz(serviceUuid)} ${kurz(characteristicUuid)} ${hex(value)} ${if (withResponse) "req" else "cmd"}"
        onWrite?.invoke(this, value)
    }

    override fun requestMtu(mtu: Int) {
        ops += "mtu $mtu"
    }

    override fun close() {
        closes++
        ops += "close"
    }

    fun state(state: BleLinkState, services: Set<String> = emptySet()) =
        listener!!.onLinkState(state, services)

    fun ready(vararg services: String) = state(BleLinkState.READY, services.toSet())

    fun async(bytes: String, receivedMs: Long = 0) =
        listener!!.onValue(IndoorUuids.ZWIFT_ASYNC, bytes(bytes), receivedMs)

    fun syncTx(bytes: String, receivedMs: Long = 0) =
        listener!!.onValue(IndoorUuids.ZWIFT_SYNC_TX, bytes(bytes), receivedMs)

    fun result(op: BleOperation, uuid: String?, ok: Boolean, status: Int = if (ok) 0 else 133) =
        listener!!.onOperationResult(op, uuid, ok, status)

    companion object {
        /** Kurzname fuer die Protokollzeilen: „fc82“, „zwift“, „async“, „rx“, „tx“. */
        fun kurz(uuid: String): String = when (uuid) {
            IndoorUuids.ZWIFT_RIDE_SERVICE -> "fc82"
            IndoorUuids.ZWIFT_SERVICE -> "zwift"
            IndoorUuids.ZWIFT_ASYNC -> "async"
            IndoorUuids.ZWIFT_SYNC_RX -> "rx"
            IndoorUuids.ZWIFT_SYNC_TX -> "tx"
            else -> uuid
        }

        fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

        /** „37 08 00“ → Bytes; Leerzeichen und Bindestriche erlaubt. */
        fun bytes(s: String): ByteArray {
            val clean = s.replace(" ", "").replace("-", "")
            return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}

/** Zeichnet alle Ereignisse des Schalttasters als Zeilen auf. */
internal class ClickRecorder : ShifterListener {
    val events = mutableListOf<String>()
    var onConnectionHook: ((ShifterConnection) -> Unit)? = null

    override fun onConnection(state: ShifterConnection) {
        events += "conn $state"
        onConnectionHook?.invoke(state)
    }

    override fun onShift(event: ShifterEvent) {
        events += "shift $event"
    }

    override fun onBattery(percent: Int) {
        events += "battery $percent"
    }

    fun take(): List<String> = events.toList().also { events.clear() }
}
