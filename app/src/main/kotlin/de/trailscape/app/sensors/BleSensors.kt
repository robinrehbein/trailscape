package de.trailscape.app.sensors

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import de.trailscape.app.data.trailscapePrefs
import de.trailscape.core.BleKanal
import de.trailscape.core.BleSensorTyp
import de.trailscape.core.BleVerbindung
import de.trailscape.core.BleVerbindungsZustand
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.DrehzahlRechner
import de.trailscape.core.GemerkterSensor
import de.trailscape.core.LEISTUNG_ANZEIGE_FENSTER_MS
import de.trailscape.core.LeistungsPuffer
import de.trailscape.core.PunktSensorWerte
import de.trailscape.core.dekodiereGemerkteSensoren
import de.trailscape.core.kodiereGemerkteSensoren
import de.trailscape.core.parseCscMessung
import de.trailscape.core.parseLeistungsMessung
import de.trailscape.core.parsePulsMessung
import de.trailscape.core.punktSensorWerte
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Wer die Sensorverbindungen gerade braucht (siehe [BleSensors.acquire]). */
enum class BleNutzer { AUFZEICHNUNG, EINSTELLUNGEN }

/**
 * Die Live-Kanaele aller Sensoren, wie `:core` sie auswertet.
 *
 * @property naechsterVersuchMs je Typ der Zeitpunkt des geplanten
 *   Neuversuchs, falls die Verbindung gerade getrennt ist.
 */
data class BleKanaele(
    val puls: BleKanal,
    val leistung: BleKanal,
    val trittfrequenz: BleKanal,
    val naechsterVersuchMs: Map<BleSensorTyp, Long> = emptyMap(),
) {
    val irgendeinAktiv: Boolean get() = puls.aktiv || leistung.aktiv || trittfrequenz.aktiv

    companion object {
        val AUS = BleKanaele(BleKanal.AUS, BleKanal.AUS, BleKanal.AUS)
    }
}

/**
 * # Bruecke zwischen den Bluetooth-Sensoren und Aufzeichnung/Oberflaeche
 *
 * Prozessweites Objekt nach dem Muster von `RecordingRepository` und
 * `WearBridge`: Es haelt die GATT-Verbindungen ([BleSensorConnection]) zu
 * den gemerkten Sensoren, reicht deren Rohdaten an die Parser in `:core` und
 * veroeffentlicht das Ergebnis als [kanaele] und [status].
 *
 * ## Nur, solange es jemand braucht
 * Verbunden wird ausschliesslich, solange mindestens ein [BleNutzer] das
 * Objekt haelt ([acquire]/[release]): die laufende Aufzeichnung
 * (`RecordingService`) und die offene Seite Mehr → Sensoren (dort, damit man
 * die Werte vor der Fahrt pruefen kann). Sonst gibt es keine Verbindung und
 * keinen Funkverkehr — das schont den Akku von Handy und Sensor, und die App
 * haelt keine Verbindung zu einem Geraet in der Naehe, von der niemand weiss.
 *
 * ## Kein Bonding
 * Die drei Standardprofile senden ihre Messwerte unverschluesselt; ein
 * Android-Pairing braeuchte einen Systemdialog, braechte nichts und wuerde
 * bei manchen Gurten die gleichzeitige Verbindung zur Uhr verhindern.
 * „Koppeln" heisst in Trailscape nur: Adresse merken ([merke]).
 *
 * ## Threading
 * Alle Verbindungen und Rechner leben auf einem eigenen [HandlerThread]
 * („trailscape-ble"), nicht auf dem Aufzeichnungsthread — ein haengender
 * Bluetooth-Stack darf keine GPS-Punkte kosten. [acquire]/[release] und die
 * Prefs-Aenderungen posten dorthin. [punktWerte] liest vom
 * Aufzeichnungsthread aus; der gemeinsam benutzte [LeistungsPuffer] ist
 * deshalb mit [lock] geschuetzt, alles andere nur ueber die StateFlows.
 * Der Thread bleibt nach dem ersten Gebrauch bestehen (ein wartender
 * HandlerThread kostet nichts), damit Start und Stopp nicht gegeneinander
 * laufen koennen.
 */
object BleSensors {
    /** Prefs-Schluessel der gemerkten Sensoren (JSON, siehe `kodiereGemerkteSensoren`). */
    const val PREF_BLE_SENSORS = "trailscape.ble.sensors"

    /** Laengstes Intervall, ueber das [punktWerte] die Leistung mittelt. */
    private const val PUNKT_MITTEL_MAX_MS = 30_000L

    /** Zustaende, in denen gar nicht verbunden wird (siehe [veroeffentliche]). */
    private val BLOCKIERT = setOf(BleVerbindung.BLUETOOTH_AUS, BleVerbindung.KEINE_BERECHTIGUNG)

    private val lock = Any()
    private var appContext: Context? = null
    private val nutzer = mutableSetOf<BleNutzer>()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // ---- nur auf dem BLE-Thread
    private val verbindungen = mutableMapOf<BleSensorTyp, BleSensorConnection>()
    private val zustaende = BleSensorTyp.entries.associateWith { BleVerbindungsZustand() }
    private val neuversuche = mutableMapOf<BleSensorTyp, Runnable>()
    private var gemerkt: List<GemerkterSensor> = emptyList()
    private var kurbelCsc = DrehzahlRechner.kurbel()
    private var kurbelCp = DrehzahlRechner.kurbel()
    private var cpLiefertKurbel = false
    private var puls = BleKanal.AUS
    private var leistung = BleKanal.AUS
    private var trittfrequenz = BleKanal.AUS
    private var receiverRegistriert = false

    // ---- geschuetzt durch [lock]
    private val leistungsPuffer = LeistungsPuffer()
    private var letzterPunktMs: Long? = null

    private val _status = MutableStateFlow<Map<BleSensorTyp, BleVerbindung>>(emptyMap())
    private val _kanaele = MutableStateFlow(BleKanaele.AUS)

    /** Verbindungszustand je gemerktem Sensor. */
    val status: StateFlow<Map<BleSensorTyp, BleVerbindung>> = _status.asStateFlow()

    /** Live-Werte je Kanal (siehe `liveSensorAnzeige` in `:core`). */
    val kanaele: StateFlow<BleKanaele> = _kanaele.asStateFlow()

    // ------------------------------------------------------------- Nutzer

    /** Beginnt zu verbinden, falls [wer] der erste Nutzer ist. */
    fun acquire(context: Context, wer: BleNutzer) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (wer == BleNutzer.AUFZEICHNUNG) {
                // Neue Aufzeichnung: Das erste Punktmittel beginnt jetzt.
                letzterPunktMs = null
            }
            if (!nutzer.add(wer)) return
            bleHandler().post { verbindeAlle() }
        }
    }

    /** Trennt alles, sobald [wer] der letzte Nutzer war. Idempotent. */
    fun release(wer: BleNutzer) {
        synchronized(lock) {
            if (!nutzer.remove(wer)) return
            if (nutzer.isNotEmpty()) return
            handler?.post { trenneAlle() }
        }
    }

    /** Baut fehlende Verbindungen neu auf, etwa nachdem eine Berechtigung erteilt wurde. */
    fun neuVerbinden() {
        handler?.post { verbindeAlle() }
    }

    private fun hatNutzer(): Boolean = synchronized(lock) { nutzer.isNotEmpty() }

    private fun bleHandler(): Handler = synchronized(lock) {
        handler ?: HandlerThread("trailscape-ble").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
    }

    // ------------------------------------------------------- Gemerkte

    fun gemerkte(context: Context): List<GemerkterSensor> =
        dekodiereGemerkteSensoren(trailscapePrefs(context).getString(PREF_BLE_SENSORS, null))

    /** Merkt [sensor] (ersetzt einen Sensor desselben Typs) und verbindet laufend neu. */
    fun merke(context: Context, sensor: GemerkterSensor) {
        schreibe(context, de.trailscape.core.merke(gemerkte(context), sensor))
    }

    /** Vergisst den Sensor des Typs [typ] und trennt ihn. */
    fun vergiss(context: Context, typ: BleSensorTyp) {
        schreibe(context, de.trailscape.core.vergiss(gemerkte(context), typ))
    }

    private fun schreibe(context: Context, liste: List<GemerkterSensor>) {
        trailscapePrefs(context).edit().putString(PREF_BLE_SENSORS, kodiereGemerkteSensoren(liste)).apply()
        handler?.post { if (hatNutzer()) verbindeAlle() }
    }

    // ------------------------------------------------------ Aufzeichnung

    /**
     * Die Werte fuer den gerade aufgezeichneten Punkt (Puls Gurt vor Uhr,
     * Leistung als Mittel seit dem vorigen Aufruf, aktuelle Trittfrequenz).
     * Aufgerufen vom Aufzeichnungsthread, je Punkt genau einmal.
     */
    fun punktWerte(jetzt: Long, uhrBpm: Int?, uhrVerbunden: Boolean): PunktSensorWerte {
        val mittel = synchronized(lock) {
            // Hoechstens 30 s zurueck: Nach einer Pause oder GPS-Luecke
            // gehoert die Zwischenzeit nicht in den Punkt — die Auswertung
            // wertet Abstaende ueber 30 s ohnehin nicht (maxHrGapS).
            val seit = maxOf(letzterPunktMs ?: (jetzt - LEISTUNG_ANZEIGE_FENSTER_MS), jetzt - PUNKT_MITTEL_MAX_MS)
            letzterPunktMs = jetzt
            leistungsPuffer.punktMittel(seit, jetzt)
        }
        val k = _kanaele.value
        return punktSensorWerte(jetzt, k.puls, mittel, k.trittfrequenz, uhrBpm, uhrVerbunden)
    }

    /** Der wirksame Live-Puls (frischer Gurt vor Uhr), ohne das Punktmittel weiterzuschieben. */
    fun livePulsBpm(jetzt: Long, uhrBpm: Int?, uhrVerbunden: Boolean): Int? {
        val k = _kanaele.value
        return punktSensorWerte(jetzt, k.puls, null, k.trittfrequenz, uhrBpm, uhrVerbunden).hr
    }

    // ------------------------------------------- Verbinden (BLE-Thread)

    private fun verbindeAlle() {
        val ctx = synchronized(lock) { appContext } ?: return
        if (!hatNutzer()) return
        gemerkt = gemerkte(ctx)
        registriereReceiver(ctx)
        val sollTypen = gemerkt.associateBy { it.typ }

        for (typ in BleSensorTyp.entries) {
            val soll = sollTypen[typ]
            val ist = verbindungen[typ]
            if (soll == null) {
                if (ist != null) trenne(typ)
                continue
            }
            val zustand = zustaende.getValue(typ)
            val laeuft = zustand.status == BleVerbindung.VERBINDE ||
                zustand.status == BleVerbindung.VERBUNDEN ||
                zustand.status == BleVerbindung.WARTET
            if (ist != null && ist.sensor.adresse == soll.adresse && laeuft) continue
            if (ist != null) trenne(typ)
            starte(ctx, soll)
        }
        if (sollTypen[BleSensorTyp.LEISTUNG] == null) cpLiefertKurbel = false
        veroeffentliche()
    }

    private fun starte(ctx: Context, sensor: GemerkterSensor) {
        val zustand = zustaende.getValue(sensor.typ)
        when {
            !bluetoothLeSupported(ctx) || !bluetoothEnabled(ctx) -> {
                zustand.start()
                zustand.blockiert(BleVerbindung.BLUETOOTH_AUS)
            }
            !hasConnectPermission(ctx) -> {
                DiagLog.shared.log(DiagEvent.BLE_NO_PERMISSION)
                zustand.start()
                zustand.blockiert(BleVerbindung.KEINE_BERECHTIGUNG)
            }
            else -> {
                zustand.start()
                val conn = BleSensorConnection(ctx, sensor, bleHandler(), listener)
                verbindungen[sensor.typ] = conn
                conn.verbinden()
            }
        }
    }

    private fun trenne(typ: BleSensorTyp) {
        neuversuche.remove(typ)?.let { handler?.removeCallbacks(it) }
        verbindungen.remove(typ)?.schliessen()
        zustaende.getValue(typ).stop()
        when (typ) {
            BleSensorTyp.PULS -> puls = BleKanal.AUS
            BleSensorTyp.LEISTUNG -> {
                leistung = BleKanal.AUS
                kurbelCp.zuruecksetzen()
                synchronized(lock) { leistungsPuffer.leeren() }
            }
            BleSensorTyp.TRITTFREQUENZ -> {
                trittfrequenz = BleKanal.AUS
                kurbelCsc.zuruecksetzen()
            }
        }
    }

    private fun trenneAlle() {
        if (hatNutzer()) return // inzwischen wieder gebraucht
        BleSensorTyp.entries.forEach { trenne(it) }
        cpLiefertKurbel = false
        appContext?.let { ctx -> abmeldenReceiver(ctx) }
        veroeffentliche()
    }

    private val listener = object : BleSensorConnection.Listener {
        override fun verbunden(typ: BleSensorTyp) {
            zustaende.getValue(typ).verbunden()
            veroeffentliche()
        }

        override fun daten(typ: BleSensorTyp, nutzlast: ByteArray, empfangenMs: Long) {
            zustaende.getValue(typ).datenErhalten()
            when (typ) {
                BleSensorTyp.PULS -> parsePulsMessung(nutzlast)?.let {
                    puls = BleKanal(true, it.bpm, empfangenMs)
                }
                BleSensorTyp.LEISTUNG -> parseLeistungsMessung(nutzlast)?.let { m ->
                    val mittel = synchronized(lock) {
                        leistungsPuffer.add(empfangenMs, m.watt)
                        leistungsPuffer.anzeigeMittel(empfangenMs)
                    }
                    leistung = BleKanal(true, mittel?.roundToInt(), empfangenMs)
                    // Trittfrequenz aus dem Leistungsmesser nur ohne eigenen
                    // CSC-Sensor — der misst direkt an der Kurbel.
                    val kurbel = m.kurbel
                    if (kurbel != null && gemerkt.none { it.typ == BleSensorTyp.TRITTFREQUENZ }) {
                        cpLiefertKurbel = true
                        kurbelCp.biete(kurbel, empfangenMs)?.let {
                            trittfrequenz = BleKanal(true, it.roundToInt(), empfangenMs)
                        }
                    }
                }
                BleSensorTyp.TRITTFREQUENZ -> parseCscMessung(nutzlast)?.kurbel?.let { k ->
                    kurbelCsc.biete(k, empfangenMs)?.let {
                        trittfrequenz = BleKanal(true, it.roundToInt(), empfangenMs)
                    }
                }
            }
            veroeffentliche()
        }

        override fun getrennt(typ: BleSensorTyp, status: Int) {
            val zustand = zustaende.getValue(typ)
            if (typ == BleSensorTyp.TRITTFREQUENZ) kurbelCsc.zuruecksetzen()
            if (typ == BleSensorTyp.LEISTUNG) kurbelCp.zuruecksetzen()
            val jetzt = System.currentTimeMillis()
            val zeitpunkt = zustand.getrennt(jetzt)
            if (zeitpunkt != null && hatNutzer()) {
                val h = bleHandler()
                neuversuche.remove(typ)?.let { h.removeCallbacks(it) }
                val r = Runnable {
                    neuversuche.remove(typ)
                    val conn = verbindungen[typ] ?: return@Runnable
                    if (!hatNutzer()) return@Runnable
                    zustand.neuerVersuch()
                    veroeffentliche()
                    conn.verbinden()
                }
                neuversuche[typ] = r
                h.postDelayed(r, zeitpunkt - jetzt)
            }
            veroeffentliche()
        }

        override fun keineBerechtigung(typ: BleSensorTyp) {
            zustaende.getValue(typ).blockiert(BleVerbindung.KEINE_BERECHTIGUNG)
            veroeffentliche()
        }
    }

    private fun veroeffentliche() {
        val alleTypen = gemerkt.map { it.typ }.toSet()
        val aktiv = hatNutzer()
        _status.value = alleTypen.associateWith { zustaende.getValue(it).status }
        // Ein blockierter Sensor (Bluetooth aus, keine Berechtigung) ist kein
        // aktiver Kanal: Es wird gar nicht verbunden, eine Kachel „verbinde …"
        // wuerde die ganze Fahrt ueber etwas Falsches behaupten. Ohne Kanal
        // fehlt die Kachel wie ohne Sensor, und beim Puls greift die Uhr.
        val typen = alleTypen.filterTo(mutableSetOf()) { zustaende.getValue(it).status !in BLOCKIERT }
        fun kanal(k: BleKanal, an: Boolean) = if (aktiv && an) k.copy(aktiv = true) else BleKanal.AUS
        _kanaele.value = BleKanaele(
            puls = kanal(puls, BleSensorTyp.PULS in typen),
            leistung = kanal(leistung, BleSensorTyp.LEISTUNG in typen),
            trittfrequenz = kanal(
                trittfrequenz,
                BleSensorTyp.TRITTFREQUENZ in typen || (BleSensorTyp.LEISTUNG in typen && cpLiefertKurbel),
            ),
            naechsterVersuchMs = typen.mapNotNull { t ->
                zustaende.getValue(t).naechsterVersuchMs?.let { t to it }
            }.toMap(),
        )
    }

    // ------------------------------------------- Bluetooth an/aus

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> handler?.post { verbindeAlle() }
                BluetoothAdapter.STATE_OFF -> handler?.post {
                    // Ohne Funk gibt es nichts neu zu versuchen, bis Bluetooth
                    // wieder an ist — dann verbindet STATE_ON alles neu.
                    for ((typ, conn) in verbindungen.toMap()) {
                        neuversuche.remove(typ)?.let { handler?.removeCallbacks(it) }
                        conn.schliessen()
                        verbindungen.remove(typ)
                        zustaende.getValue(typ).blockiert(BleVerbindung.BLUETOOTH_AUS)
                    }
                    veroeffentliche()
                }
            }
        }
    }

    private fun registriereReceiver(ctx: Context) {
        if (receiverRegistriert) return
        runCatching {
            ContextCompat.registerReceiver(
                ctx,
                bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                null,
                bleHandler(),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistriert = true
        }
    }

    private fun abmeldenReceiver(ctx: Context) {
        if (!receiverRegistriert) return
        runCatching { ctx.unregisterReceiver(bluetoothReceiver) }
        receiverRegistriert = false
    }
}

/**
 * Ob Verbindungen erlaubt sind. Unter API 31 ist BLUETOOTH eine
 * Installationsberechtigung — verbinden geht immer; die Standortfreigabe
 * braucht dort nur die Suche.
 */
internal fun hasConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
