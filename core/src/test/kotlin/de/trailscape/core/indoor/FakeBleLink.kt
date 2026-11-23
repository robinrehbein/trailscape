package de.trailscape.core.indoor

/**
 * Attrappe des Rohrs [BleLink] fuer die Protokoll-Tests (FTMS, Schalttaster).
 *
 *  * **Protokoll:** Jede Operation landet als [Op] in [ops] (threadsicher).
 *  * **Einspeisen von Hand:** [linkState], [value], [result] rufen den
 *    Listener so, wie es das echte Rohr vom BLE-Thread aus taete — hier vom
 *    Thread des Tests.
 *  * **Synchrones Zurueckrufen:** Ist [onOperation] gesetzt, wird es *innerhalb*
 *    der Rohr-Methode aufgerufen (nach dem Protokollieren). Damit laesst sich
 *    ein Rohr nachbauen, das aus `write` heraus sofort antwortet — der
 *    haerteste Fall fuer die Reentranz der Protokollschicht.
 */
internal class FakeBleLink(override val deviceName: String? = "KICKR CORE 1A2B") : BleLink {
    sealed interface Op {
        data object Open : Op

        data class Subscribe(val service: String, val characteristic: String, val indicate: Boolean) : Op

        data class Read(val service: String, val characteristic: String) : Op

        class Write(val service: String, val characteristic: String, val value: ByteArray, val withResponse: Boolean) : Op {
            /** Nutzlast als „08 01“ — fuer lesbare Zusicherungen. */
            val hex: String get() = value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

            override fun toString() = "Write($characteristic, $hex, withResponse=$withResponse)"
        }

        data class Mtu(val mtu: Int) : Op

        data object Close : Op
    }

    private val protokoll = mutableListOf<Op>()

    @Volatile
    var listener: BleLinkListener? = null
        private set

    /** Wird synchron aus jeder Rohr-Methode heraus gerufen (nach dem Protokollieren). */
    @Volatile
    var onOperation: ((Op) -> Unit)? = null

    val ops: List<Op> get() = synchronized(protokoll) { protokoll.toList() }

    /** Alle Writes, optional nur auf [characteristic]. */
    fun writes(characteristic: String? = null): List<Op.Write> =
        ops.filterIsInstance<Op.Write>().filter { characteristic == null || it.characteristic == characteristic }

    /** Nutzlasten aller Writes als Hex. */
    fun writeHex(characteristic: String? = null): List<String> = writes(characteristic).map { it.hex }

    fun clearOps() = synchronized(protokoll) { protokoll.clear() }

    private fun log(op: Op) {
        synchronized(protokoll) { protokoll += op }
        onOperation?.invoke(op)
    }

    override fun open(listener: BleLinkListener) {
        this.listener = listener
        log(Op.Open)
    }

    override fun subscribe(serviceUuid: String, characteristicUuid: String, indicate: Boolean) =
        log(Op.Subscribe(serviceUuid, characteristicUuid, indicate))

    override fun read(serviceUuid: String, characteristicUuid: String) = log(Op.Read(serviceUuid, characteristicUuid))

    override fun write(serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean) =
        log(Op.Write(serviceUuid, characteristicUuid, value.copyOf(), withResponse))

    override fun requestMtu(mtu: Int) = log(Op.Mtu(mtu))

    override fun close() = log(Op.Close)

    // ------------------------------------------------------------ Einspeisen

    fun linkState(state: BleLinkState, services: Set<String> = emptySet()) {
        listener?.onLinkState(state, services)
    }

    fun value(characteristic: String, bytes: ByteArray, receivedMs: Long = 0L) {
        listener?.onValue(characteristic, bytes, receivedMs)
    }

    fun value(characteristic: String, vararg bytes: Int, receivedMs: Long = 0L) =
        value(characteristic, ByteArray(bytes.size) { bytes[it].toByte() }, receivedMs)

    fun result(operation: BleOperation, characteristic: String?, ok: Boolean = true, gattStatus: Int = if (ok) 0 else 133) {
        listener?.onOperationResult(operation, characteristic, ok, gattStatus)
    }
}
