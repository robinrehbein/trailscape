package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Zustandslogik der Bluetooth-Sensoren — alles, was zwischen dem Byte-Paket
 * (Parser in `BleMessformate.kt`) und der Anzeige bzw. dem
 * aufgezeichneten Punkt entschieden wird, ohne Android und ohne Funk.
 *
 * ## Warum so viel davon in `:core` steht
 * Die GATT-Verbindung selbst laesst sich nur auf echter Hardware pruefen.
 * Deshalb bleibt sie in `:app` so duenn wie moeglich (`BleSensors`,
 * `BleSensorConnection`), und jede Entscheidung, die sich ohne Funk treffen
 * laesst, steht hier und ist getestet: Drehzahl mit Ueberlauf, Leistungsmittel,
 * Frische und Stillzeit, Takt der Neuversuche, Vorrang Gurt vor Uhr, das
 * Format der gemerkten Sensoren.
 */

/** Ab so langer Stille gilt ein Messwert als veraltet („liefert seit X s nichts"). */
const val SENSOR_TIMEOUT_MS: Long = 5_000

/** Ohne neue Kurbelumdrehung so lange → Trittfrequenz 0 (man rollt). */
const val STILLSTAND_KURBEL_MS: Long = 3_000

/** Fenster des Leistungsmittels der Live-Anzeige — die Rohwerte springen je Tritt. */
const val LEISTUNG_ANZEIGE_FENSTER_MS: Long = 3_000

/** Periode der 16-Bit-Ereigniszeit in Ticks. */
private const val EREIGNIS_TICKS_MODULO = 65_536L

// ---------------------------------------------------------------------------
// (1) Drehzahl aus kumulativen Umdrehungen
// ---------------------------------------------------------------------------

/**
 * Rechnet aus aufeinanderfolgenden [Umdrehungen] die Drehzahl in U/min.
 *
 * Sensoren senden keine Drehzahl, sondern einen kumulierten Zaehler und den
 * Zeitstempel der letzten Umdrehung. Die Drehzahl ist
 * `ΔUmdrehungen / ΔEreigniszeit` — beide Differenzen modulo ihrer Feldbreite,
 * denn der 16-Bit-Zeitstempel laeuft bei 1024 Ticks/s alle 64 s ueber, der
 * Kurbelzaehler nach 65 536 Umdrehungen.
 *
 * Regeln:
 *  * Das erste Paket ist nur die Basis → `null`.
 *  * Kein neues Ereignis (Zaehler gleich): der letzte Wert, nach
 *    [stillstandNachMs] ohne neue Umdrehung aber `0.0` — der Sensor wiederholt
 *    im Stand dasselbe Paket, und „letzter Wert" waere dann eine Luege.
 *  * `ΔTicks == 0` bei `ΔUmdrehungen > 0`: kaputtes Paket, ignoriert (die
 *    Basis bleibt, das naechste Paket rechnet ueber beide).
 *  * Unplausibel (ueber [maxUpm]) oder die letzte Umdrehung liegt laenger
 *    zurueck als 90 % der Ueberlaufperiode: Die Ereigniszeit ist dann
 *    mehrdeutig → neue Basis, `null`.
 */
class DrehzahlRechner(
    private val zaehlerModulo: Long,
    private val ticksProSekunde: Int,
    private val maxUpm: Double,
    private val stillstandNachMs: Long = STILLSTAND_KURBEL_MS,
) {
    private var basis: Umdrehungen? = null
    private var letztesEreignisMs: Long = 0
    private var letzterWert: Double? = null

    /** Ab dieser Empfangsluecke ist die 16-Bit-Ereigniszeit nicht mehr eindeutig. */
    private val maxLueckeMs: Long =
        (EREIGNIS_TICKS_MODULO * 1000 / ticksProSekunde * 9 / 10)

    fun biete(u: Umdrehungen, empfangenMs: Long): Double? {
        val b = basis
        if (b == null) {
            neueBasis(u, empfangenMs)
            return null
        }
        val dRev = Math.floorMod(u.zaehler - b.zaehler, zaehlerModulo)
        if (dRev == 0L) {
            if (empfangenMs - letztesEreignisMs >= stillstandNachMs) {
                letzterWert = 0.0
            }
            return letzterWert
        }
        if (empfangenMs - letztesEreignisMs > maxLueckeMs) {
            neueBasis(u, empfangenMs)
            return null
        }
        val dTicks = Math.floorMod((u.ereignisTicks - b.ereignisTicks).toLong(), EREIGNIS_TICKS_MODULO)
        if (dTicks == 0L) {
            return letzterWert
        }
        val upm = dRev * 60.0 * ticksProSekunde / dTicks
        if (upm > maxUpm) {
            neueBasis(u, empfangenMs)
            return null
        }
        basis = u
        letztesEreignisMs = empfangenMs
        letzterWert = upm
        return upm
    }

    /** Vergisst alles, etwa nach einem Verbindungsabbruch. */
    fun zuruecksetzen() {
        basis = null
        letzterWert = null
    }

    private fun neueBasis(u: Umdrehungen, empfangenMs: Long) {
        basis = u
        letztesEreignisMs = empfangenMs
        letzterWert = null
    }

    companion object {
        /** Kurbel (CSC und Cycling Power): 16-Bit-Zaehler, 1/1024 s, hoechstens 250 U/min. */
        fun kurbel(): DrehzahlRechner = DrehzahlRechner(65_536L, 1024, 250.0)

        /** Rad am CSC-Sensor: 32-Bit-Zaehler, 1/1024 s. */
        fun cscRad(): DrehzahlRechner = DrehzahlRechner(1L shl 32, 1024, 2500.0)

        /** Rad am Leistungsmesser: 32-Bit-Zaehler, aber 1/2048 s (siehe BleMessformate.kt). */
        fun cpRad(): DrehzahlRechner = DrehzahlRechner(1L shl 32, 2048, 2500.0)
    }
}

/**
 * Tempo in km/h aus der Raddrehzahl und dem Abrollumfang (Standard 2105 mm,
 * ein 700×25C-Reifen).
 *
 * Gerechnet und getestet, aber bewusst **nicht verdrahtet**: GPS bleibt die
 * Tempoquelle, solange es im Profil keinen Radumfang gibt (Entscheidung des
 * Gruenders, siehe Bericht zu ble-sensors).
 */
fun radTempoKmh(radUpm: Double, umfangMm: Int = 2105): Double =
    radUpm * umfangMm / 1_000_000.0 * 60.0

// ---------------------------------------------------------------------------
// (3) Leistungsmittel
// ---------------------------------------------------------------------------

/**
 * Puffer der Leistungsrohwerte fuer zwei Mittel: das kurze der Live-Anzeige
 * ([anzeigeMittel]) und das Punktmittel fuer die Aufzeichnung ([punktMittel]).
 *
 * Warum ein Mittel je Punkt: Leistung schwankt je Pedaltritt um das Doppelte.
 * Ein Momentwert am Punkt waere fuer die Energie der Tour Zufall; das Mittel
 * ueber das Punktintervall ist genau das, was die Auswertung braucht (siehe
 * [TrackPoint.power]).
 *
 * @param halteMs wie lange Werte aufbewahrt werden. Laenger als die 10 s der
 *   Spezifikation, weil der PointFilter der Aufzeichnung im Stand oder bei
 *   langsamer Fahrt Punkte zurueckhaelt und das Punktintervall dann laenger
 *   wird — mit nur 10 s ginge die Energie davor verloren. 120 s sind bei 4 Hz
 *   knapp 500 Eintraege.
 */
class LeistungsPuffer(private val halteMs: Long = 120_000) {
    private val werte = ArrayDeque<Pair<Long, Int>>()

    fun add(ms: Long, watt: Int) {
        werte.addLast(ms to watt.coerceAtLeast(0))
        while (werte.isNotEmpty() && werte.first().first < ms - halteMs) {
            werte.removeFirst()
        }
    }

    /** Mittel der letzten [LEISTUNG_ANZEIGE_FENSTER_MS]; `null`, wenn darin nichts kam. */
    fun anzeigeMittel(jetzt: Long): Double? =
        mittel(werte.filter { it.first > jetzt - LEISTUNG_ANZEIGE_FENSTER_MS && it.first <= jetzt })

    /**
     * Mittel der Werte im Intervall `(seitMs, bisMs]`. Kam darin nichts (der
     * Leistungsmesser sendet seltener als Punkte entstehen), gilt der letzte
     * Wert, solange er frisch ist; sonst `null`.
     */
    fun punktMittel(seitMs: Long, bisMs: Long): Double? {
        val drin = werte.filter { it.first > seitMs && it.first <= bisMs }
        if (drin.isNotEmpty()) return mittel(drin)
        val letzter = werte.lastOrNull { it.first <= bisMs } ?: return null
        return if (istFrisch(letzter.first, bisMs)) letzter.second.toDouble() else null
    }

    fun leeren() = werte.clear()

    private fun mittel(liste: List<Pair<Long, Int>>): Double? =
        if (liste.isEmpty()) null else liste.sumOf { it.second }.toDouble() / liste.size
}

// ---------------------------------------------------------------------------
// (4) Frische
// ---------------------------------------------------------------------------

/** Ganze Sekunden seit dem letzten Wert, `null` ohne je einen Wert. */
fun stillSeitS(letzteMs: Long?, jetzt: Long): Int? =
    letzteMs?.let { ((jetzt - it).coerceAtLeast(0) / 1000).toInt() }

/** Ob der letzte Wert hoechstens [timeout] alt ist (Grenze einschliesslich). */
fun istFrisch(letzteMs: Long?, jetzt: Long, timeout: Long = SENSOR_TIMEOUT_MS): Boolean =
    letzteMs != null && jetzt - letzteMs <= timeout

// ---------------------------------------------------------------------------
// (5) Verbindung und Neuversuch
// ---------------------------------------------------------------------------

/**
 * Wartezeit vor dem naechsten Verbindungsversuch nach [fehlversuche]
 * aufeinanderfolgenden Fehlschlaegen: 2 s, 5 s, 10 s, danach immer 30 s.
 *
 * Nicht schneller: Viele Android-Stacks antworten auf zu dichte Versuche mit
 * GATT-Status 133 und brauchen Luft. Nicht langsamer als 30 s: Wer den Gurt
 * erst nach dem Start anlegt, soll nicht minutenlang warten.
 */
fun neuerVersuchNachMs(fehlversuche: Int): Long = when {
    fehlversuche <= 1 -> 2_000
    fehlversuche == 2 -> 5_000
    fehlversuche == 3 -> 10_000
    else -> 30_000
}

/** Verbindungszustand eines Sensors, wie ihn die Einstellungsseite zeigt. */
enum class BleVerbindung { AUS, VERBINDE, VERBUNDEN, WARTET, KEINE_BERECHTIGUNG, BLUETOOTH_AUS }

/**
 * Zustandsmaschine einer Sensorverbindung ohne Funk: wann verbunden, wann
 * neu versucht wird, wann Schluss ist. `BleSensorConnection` in `:app` meldet
 * nur Ereignisse hierher und plant den Neuversuch zu dem Zeitpunkt, den
 * [getrennt] liefert.
 */
class BleVerbindungsZustand {
    var status: BleVerbindung = BleVerbindung.AUS
        private set
    var fehlversuche: Int = 0
        private set

    /** Geplanter Zeitpunkt des naechsten Versuchs, `null` ohne geplanten. */
    var naechsterVersuchMs: Long? = null
        private set

    private var aktiv = false

    fun start() {
        aktiv = true
        status = BleVerbindung.VERBINDE
        naechsterVersuchMs = null
    }

    fun verbunden() {
        if (!aktiv) return
        status = BleVerbindung.VERBUNDEN
        naechsterVersuchMs = null
    }

    /**
     * Ein Messwert kam an. Erst jetzt gilt die Verbindung als gesund und der
     * Zaehler der Fehlversuche faellt auf null — ein Sensor, der verbindet
     * und sofort wieder abbricht, soll nicht im 2-s-Takt haemmern.
     */
    fun datenErhalten() {
        if (!aktiv) return
        fehlversuche = 0
    }

    /** Verbindung abgebrochen oder gescheitert; liefert den Zeitpunkt des Neuversuchs oder `null` nach [stop]. */
    fun getrennt(jetzt: Long): Long? {
        if (!aktiv) return null
        fehlversuche++
        status = BleVerbindung.WARTET
        val zeitpunkt = jetzt + neuerVersuchNachMs(fehlversuche)
        naechsterVersuchMs = zeitpunkt
        return zeitpunkt
    }

    /** Der geplante Neuversuch beginnt. */
    fun neuerVersuch() {
        if (!aktiv) return
        status = BleVerbindung.VERBINDE
        naechsterVersuchMs = null
    }

    /** Dauerhaftes Hindernis (Berechtigung, Bluetooth aus): kein Neuversuch nach Takt. */
    fun blockiert(grund: BleVerbindung) {
        status = grund
        naechsterVersuchMs = null
    }

    fun stop() {
        aktiv = false
        status = BleVerbindung.AUS
        fehlversuche = 0
        naechsterVersuchMs = null
    }
}

// ---------------------------------------------------------------------------
// (6) Live-Anzeige
// ---------------------------------------------------------------------------

/**
 * Ein Messkanal, wie `BleSensors` ihn veroeffentlicht.
 *
 * @property aktiv ein Sensor fuer diesen Kanal ist gemerkt und die App
 *   verbindet gerade (Aufzeichnung oder Einstellungsseite).
 * @property wert letzter Messwert (Puls bpm, Leistung als 3-s-Mittel in W,
 *   Trittfrequenz U/min), `null` ohne je einen.
 * @property zeitMs Empfangszeit von [wert].
 */
data class BleKanal(val aktiv: Boolean, val wert: Int?, val zeitMs: Long?) {
    companion object {
        val AUS = BleKanal(aktiv = false, wert = null, zeitMs = null)
    }
}

/**
 * Eine Kachel der Live-Anzeige: Wert, oder — ist er veraltet — kein Wert
 * plus die Stillzeit. `wert == null && stillSeitS == null` heisst: verbunden
 * wird noch, es kam nie etwas.
 */
data class LiveKachel(val wert: Int?, val stillSeitS: Int?)

enum class PulsQuelle { GURT, UHR }

/**
 * Was Fahrmodus und Kompaktleiste zeigen. `null` je Kachel heisst: Kachel
 * fehlt ganz (keine Quelle), nicht „Strich".
 */
data class LiveSensorAnzeige(
    val puls: LiveKachel?,
    val pulsQuelle: PulsQuelle?,
    val leistung: LiveKachel?,
    val trittfrequenz: LiveKachel?,
) {
    /** Zahl der vorhandenen Kacheln. */
    val anzahl: Int get() = listOfNotNull(puls, leistung, trittfrequenz).size

    companion object {
        val LEER = LiveSensorAnzeige(puls = null, pulsQuelle = null, leistung = null, trittfrequenz = null)
    }
}

/**
 * Entscheidet je Kachel ueber Wert, Strich und Stillzeit.
 *
 * Puls, in dieser Reihenfolge:
 *  1. ein **frischer** Gurtwert ueber 0 — der Gurt misst direkt an der Brust
 *     und schlaegt die Uhr;
 *  2. sonst die **verbundene** Uhr mit Wert (die bisherige Regel: ein Wert
 *     einer getrennten Uhr waere ein stilles Falschanzeigen);
 *  3. sonst, falls ein Gurt aktiv ist, der Strich samt Stillzeit;
 *  4. sonst keine Kachel.
 *
 * Leistung und Trittfrequenz: Kachel, sobald der Kanal aktiv ist; einen Wert
 * zeigt sie nur, solange er frisch ist — ein veralteter Wert waere im Fahren
 * nicht von einem echten zu unterscheiden.
 */
fun liveSensorAnzeige(
    jetzt: Long,
    gurt: BleKanal,
    leistung: BleKanal,
    trittfrequenz: BleKanal,
    uhrBpm: Int?,
    uhrVerbunden: Boolean,
): LiveSensorAnzeige {
    val gurtWert = gurt.wert
    val (puls, quelle) = when {
        gurt.aktiv && gurtWert != null && gurtWert > 0 && istFrisch(gurt.zeitMs, jetzt) ->
            LiveKachel(gurtWert, null) to PulsQuelle.GURT
        uhrVerbunden && uhrBpm != null && uhrBpm > 0 ->
            LiveKachel(uhrBpm, null) to PulsQuelle.UHR
        gurt.aktiv -> LiveKachel(null, stillSeitS(gurt.zeitMs, jetzt)) to PulsQuelle.GURT
        else -> null to null
    }
    return LiveSensorAnzeige(
        puls = puls,
        pulsQuelle = quelle,
        leistung = kachel(leistung, jetzt),
        trittfrequenz = kachel(trittfrequenz, jetzt),
    )
}

private fun kachel(kanal: BleKanal, jetzt: Long): LiveKachel? {
    if (!kanal.aktiv) return null
    return if (kanal.wert != null && istFrisch(kanal.zeitMs, jetzt)) {
        LiveKachel(kanal.wert, null)
    } else {
        LiveKachel(null, stillSeitS(kanal.zeitMs, jetzt))
    }
}

// ---------------------------------------------------------------------------
// (7) Werte je aufgezeichnetem Punkt
// ---------------------------------------------------------------------------

/** Was an einem aufgezeichneten Punkt haengt (siehe [TrackPoint.hr]/[TrackPoint.power]/[TrackPoint.cad]). */
data class PunktSensorWerte(val hr: Int?, val powerW: Int?, val cadRpm: Int?) {
    companion object {
        val LEER = PunktSensorWerte(hr = null, powerW = null, cadRpm = null)
    }
}

/**
 * Die Werte fuer einen aufgezeichneten Punkt.
 *
 * Puls: frischer Gurt vor Uhr. Anders als die Anzeige nimmt die Aufzeichnung
 * den Uhr-Wert auch ohne `watchConnected` — so hat sie es vor den
 * Bluetooth-Sensoren schon getan, und daran soll sich fuer Uhr-Nutzer nichts
 * aendern. Leistung: das Punktmittel ([LeistungsPuffer.punktMittel]).
 * Trittfrequenz: der aktuelle Wert, solange frisch.
 */
fun punktSensorWerte(
    jetzt: Long,
    gurt: BleKanal,
    leistungMittelW: Double?,
    trittfrequenz: BleKanal,
    uhrBpm: Int?,
): PunktSensorWerte {
    val gurtWert = gurt.wert
    val hr = if (gurt.aktiv && gurtWert != null && gurtWert > 0 && istFrisch(gurt.zeitMs, jetzt)) {
        gurtWert
    } else {
        uhrBpm
    }
    val cad = trittfrequenz.wert?.takeIf { trittfrequenz.aktiv && istFrisch(trittfrequenz.zeitMs, jetzt) }
    return PunktSensorWerte(hr = hr, powerW = leistungMittelW?.roundToInt(), cadRpm = cad)
}

// ---------------------------------------------------------------------------
// (8) Gemerkte Sensoren
// ---------------------------------------------------------------------------

/** Ein gekoppelter Sensor: je [BleSensorTyp] hoechstens einer. */
data class GemerkterSensor(val typ: BleSensorTyp, val adresse: String, val name: String?)

/** Format der Prefs: JSON-Liste `[{"typ":"PULS","adresse":"…","name":"…"}]`. */
fun kodiereGemerkteSensoren(list: List<GemerkterSensor>): String =
    buildJsonArray {
        list.forEach { s ->
            add(
                buildJsonObject {
                    put("typ", s.typ.name)
                    put("adresse", s.adresse)
                    s.name?.let { put("name", it) }
                },
            )
        }
    }.toString()

/**
 * Liest die gemerkten Sensoren. Kaputtes JSON ergibt eine leere Liste (dann
 * koppelt man neu, statt dass die App abstuerzt), unbekannte Typen werden
 * uebersprungen (Datei einer neueren App-Version), und je Typ gewinnt der
 * letzte Eintrag.
 */
fun dekodiereGemerkteSensoren(raw: String?): List<GemerkterSensor> {
    if (raw.isNullOrBlank()) return emptyList()
    val arr = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
    var ergebnis = emptyList<GemerkterSensor>()
    for (el in arr) {
        val obj = el as? JsonObject ?: continue
        val typ = obj.optionalString("typ")?.let { n -> BleSensorTyp.entries.firstOrNull { it.name == n } } ?: continue
        val adresse = obj.optionalString("adresse")?.takeIf { it.isNotBlank() } ?: continue
        ergebnis = merke(ergebnis, GemerkterSensor(typ, adresse, obj.optionalString("name")))
    }
    return ergebnis
}

/** Merkt [neu] und ersetzt dabei einen Sensor desselben Typs. */
fun merke(list: List<GemerkterSensor>, neu: GemerkterSensor): List<GemerkterSensor> =
    list.filter { it.typ != neu.typ } + neu

/** Vergisst den Sensor des Typs [typ]. */
fun vergiss(list: List<GemerkterSensor>, typ: BleSensorTyp): List<GemerkterSensor> =
    list.filter { it.typ != typ }
