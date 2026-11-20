package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

/**
 * Gemeinsamer Wire-Vertrag zwischen Uhr (`:wear`) und Telefon (`:app`) fuer
 * die Wear-OS-Datenschicht (Data Layer API: `MessageClient`/`DataClient`).
 *
 * Beide Module haengen auf `:core`, sehen sich aber niemals gegenseitig —
 * die Datenschicht spricht nur Pfade (Strings) und rohe Byte-Nutzlasten.
 * Diese Datei ist deshalb der einzige Ort, an dem beide Seiten "wissen"
 * muessen, wie eine Nutzlast aussieht: Pfad-/Faehigkeitskonstanten und die
 * Nachrichtentypen samt Kodierung. Weder `:wear` noch `:app` duerfen ihr
 * eigenes JSON-Layout dafuer erfinden — sonst driften sie unbemerkt
 * auseinander, sobald nur eine Seite aktualisiert wird.
 *
 * ## Warum Hand-JSON statt `@Serializable`
 * Genau dasselbe Argument wie in JsonSupport.kt/Models.kt: Optionale Felder
 * (z. B. [SensorSample.lat] waehrend der GPS noch keinen Fix hat) werden nur
 * bei Vorhandensein geschrieben, nie als explizites `null` — das haelt jede
 * einzelne Nachricht so klein wie moeglich, was auf einer Bluetooth-Funkstrecke
 * mit begrenzter Bandbreite und Akku-Budget zaehlt. Ein generierter Serializer
 * wuerde entweder immer alle Felder schreiben oder eine Sonderkonfiguration
 * pro Feld brauchen; expliziter Feldzugriff ist hier direkter.
 *
 * ## Warum `ByteArray` und nicht `String`
 * Die Data-Layer-APIs (`PutDataMapRequest.putByteArray`,
 * `MessageClient.sendMessage`) arbeiten mit rohen Bytes, nicht mit Strings.
 * Die [kodiere]/[dekodiere]-Funktionen kapseln die UTF-8-Kodierung an genau
 * einer Stelle pro Typ, statt sie an jedem Aufrufort zu wiederholen.
 */

// ---------------------------------------------------------------------------
// Pfade (Data-Layer-Pfade, siehe MessageClient/DataClient) und Faehigkeiten
// (Wearable-Capability-Namen fuer die Geraetesuche)
// ---------------------------------------------------------------------------

/** Sensordaten Uhr → Telefon (ein [SensorBatch] je Nachricht). */
const val PFAD_SENSOR = "/trailscape/sensor"

/** Befehl Uhr → Telefon (z. B. Aufzeichnung auf der Uhr gestartet). */
const val PFAD_BEFEHL_AN_TELEFON = "/trailscape/befehl-an-telefon"

/** Befehl Telefon → Uhr (bisher nur [Befehl.TELEFON_BEENDET]: Tour am Telefon beendet). */
const val PFAD_BEFEHL_AN_UHR = "/trailscape/befehl-an-uhr"

/** Aufzeichnungszustand Telefon → Uhr (fuer die Uhr-Anzeige waehrend der Fahrt). */
const val PFAD_ZUSTAND = "/trailscape/zustand"

/**
 * Was die gerade gespeicherte Tour gebracht hat, Telefon → Uhr (ein
 * [AbschlussInfo] je Tour, siehe dort). Additiv: Eine aeltere Uhr-App kennt
 * den Pfad nicht und verwirft die Nachricht (`CommandListenerService` hat ein
 * `when` ohne `else`), ein aelteres Telefon sendet ihn nie.
 */
const val PFAD_ABSCHLUSS = "/trailscape/abschluss"

/** Wearable-Capability-Name, unter dem sich das Telefon bei der Uhr anmeldet. */
const val FAEHIGKEIT_TELEFON = "trailscape_telefon"

/** Wearable-Capability-Name, unter dem sich die Uhr beim Telefon anmeldet. */
const val FAEHIGKEIT_UHR = "trailscape_uhr"

// ---------------------------------------------------------------------------
// Sensordaten (Uhr → Telefon, Pfad [PFAD_SENSOR])
// ---------------------------------------------------------------------------

/**
 * Eine einzelne Messung von der Uhr. Alle Felder ausser [zeitMs] sind
 * optional: Eine Uhr ohne eigenes GPS liefert z. B. nur [hf], eine Probe ohne
 * frischen GPS-Fix nur [tempoMps]/[hf].
 */
data class SensorSample(
    val zeitMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val hoeheM: Double? = null,
    val genauigkeitM: Double? = null,
    val tempoMps: Double? = null,
    val hf: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("zeitMs", zeitMs)
        lat?.let { put("lat", it) }
        lon?.let { put("lon", it) }
        hoeheM?.let { put("hoeheM", it) }
        genauigkeitM?.let { put("genauigkeitM", it) }
        tempoMps?.let { put("tempoMps", it) }
        hf?.let { put("hf", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): SensorSample = SensorSample(
            zeitMs = json.requiredLong("zeitMs"),
            lat = json.optionalDouble("lat"),
            lon = json.optionalDouble("lon"),
            hoeheM = json.optionalDouble("hoeheM"),
            genauigkeitM = json.optionalDouble("genauigkeitM"),
            tempoMps = json.optionalDouble("tempoMps"),
            hf = json.optionalInt("hf"),
        )
    }
}

/**
 * Buendel mehrerer [SensorSample]s in einer Nachricht.
 *
 * Die Uhr sammelt Proben lokal und sendet sie gebuendelt statt je Probe eine
 * eigene Data-Layer-Nachricht abzusetzen — deutlich sparsamer bei Funk und
 * Akku, und robuster gegen kurze Verbindungsaussetzer (siehe [LocationFusion],
 * die genau fuer nachtraeglich eintreffende, leicht unsortierte Proben ausgelegt ist).
 */
data class SensorBatch(val samples: List<SensorSample>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("samples", buildJsonArray { samples.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JsonObject): SensorBatch = SensorBatch(
            samples = json.requiredArray("samples").map { SensorSample.fromJson(it.asRequiredObject()) },
        )
    }
}

/** Kodiert [batch] als UTF-8-JSON-Bytes fuer die Data-Layer-Nachricht unter [PFAD_SENSOR]. */
fun kodiereSensorBatch(batch: SensorBatch): ByteArray = kodiere(batch.toJson())

/** Dekodiert eine unter [PFAD_SENSOR] empfangene Nachricht. Wirft bei kaputtem/falschem JSON. */
fun dekodiereSensorBatch(bytes: ByteArray): SensorBatch = SensorBatch.fromJson(dekodiere(bytes))

// ---------------------------------------------------------------------------
// Befehle (beide Richtungen, Pfade [PFAD_BEFEHL_AN_TELEFON]/[PFAD_BEFEHL_AN_UHR])
// ---------------------------------------------------------------------------

/**
 * Ein Steuerbefehl fuer die Aufzeichnung. [cmd] ist eine der [Befehl]-Konstanten.
 *
 * [zeitMs] (Wanduhr des Absenders, ms seit Epoch) ist optional und wird nur
 * geschrieben, wenn gesetzt — bisher nur bei [TELEFON_BEENDET], siehe
 * [telefonEndeBeendetUhr]. Aeltere Gegenseiten lesen ausschliesslich `cmd`
 * und uebergehen das Zusatzfeld.
 */
data class Befehl(val cmd: String, val zeitMs: Long? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("cmd", cmd)
        zeitMs?.let { put("zeitMs", it) }
    }

    companion object {
        const val START = "start"
        const val PAUSE = "pause"
        const val WEITER = "weiter"
        const val STOPP = "stopp"

        /**
         * Telefon → Uhr: Die Tour am Telefon wurde regulaer beendet (Knopf in
         * der App oder in der Benachrichtigung), immer mit [zeitMs].
         *
         * Bewusst NICHT [STOPP]: Eine heutige Uhr setzt `stopp` sofort und
         * ohne jede Pruefung um und meldet ihr Ende anschliessend selbst mit
         * `stopp` ans Telefon zurueck — das gaebe auf alten Uhren eine
         * Schleife (das Telefon startete fuer den Rueckmelde-Stopp seinen
         * Dienst noch einmal) und keinerlei Schutz gegen eine verspaetete
         * Nachricht. Ein neuer Wert faellt auf alten Uhren in deren `else`
         * und bleibt folgenlos; nur eine neue Uhr wertet ihn aus, und zwar
         * ueber [telefonEndeBeendetUhr].
         */
        const val TELEFON_BEENDET = "telefon-beendet"

        fun fromJson(json: JsonObject): Befehl = Befehl(
            cmd = json.requiredString("cmd"),
            zeitMs = json.optionalLong("zeitMs"),
        )
    }
}

/**
 * Wie alt (nach der Uhr der Uhr) ein [Befehl.TELEFON_BEENDET] hoechstens sein
 * darf, um noch zu wirken. Eine Data-Layer-Nachricht kommt normalerweise
 * binnen Sekunden an oder gar nicht; was deutlich spaeter eintrifft (der
 * Listener wurde erst spaet geweckt), gehoert mit hoher Wahrscheinlichkeit zu
 * einer Lage, die sich inzwischen geaendert hat. Eine Minute laesst Raum fuer
 * das Speichern der Tour vor dem Senden und fuer einen kurzen Funkaussetzer.
 */
const val TELEFON_ENDE_MAX_ALTER_MS = 60_000L

/**
 * Wie weit die Uhrzeit des Telefons der der Uhr voraus sein darf, ohne dass
 * ein [Befehl.TELEFON_BEENDET] als unplausibel verworfen wird. Wear OS stellt
 * die Uhrzeit der Uhr ueber das gekoppelte Telefon; ein paar Sekunden
 * Abweichung sind normal, eine halbe Minute ist grosszuegig.
 */
const val TELEFON_ENDE_UHRZEIT_TOLERANZ_MS = 30_000L

/**
 * Soll die Uhr ihre Aufzeichnung beenden, weil das Telefon [befehl] geschickt
 * hat? Reine Entscheidung, damit sie testbar in `:core` liegt; die Uhr ruft
 * sie in `RecordingStatus` auf und geht bei `true` denselben Weg wie ihr
 * eigener Stopp-Knopf.
 *
 * Im Zweifel nein — eine faelschlich beendete Aufzeichnung wiegt schwerer als
 * eine, die die Fahrerin selbst noch stoppen muss:
 *  * nur [Befehl.TELEFON_BEENDET] mit [Befehl.zeitMs] (ohne Zeitstempel kein
 *    Nachweis, worauf er sich bezieht);
 *  * nur, wenn auf der Uhr gerade eine Aufzeichnung laeuft oder pausiert
 *    ([eigenerStartMs] nicht `null`) — sonst ist nichts zu beenden, und das
 *    Echo auf einen Stopp, den die Uhr selbst ausgeloest hat, verpufft;
 *  * nur, wenn diese Aufzeichnung **vor** dem Ende am Telefon begann. Eine
 *    verspaetete Nachricht von der vorigen Tour beendet so keine neue;
 *  * nur, wenn die Nachricht hoechstens [TELEFON_ENDE_MAX_ALTER_MS] alt ist
 *    und nicht mehr als [TELEFON_ENDE_UHRZEIT_TOLERANZ_MS] aus der Zukunft
 *    kommt (sonst sind die beiden Uhrzeiten nicht vergleichbar).
 *
 * @param eigenerStartMs Wanduhr-Start der laufenden Uhr-Aufzeichnung, `null`
 *   wenn keine laeuft oder pausiert.
 * @param jetztMs Wanduhr der Uhr beim Empfang.
 */
fun telefonEndeBeendetUhr(befehl: Befehl, eigenerStartMs: Long?, jetztMs: Long): Boolean {
    if (befehl.cmd != Befehl.TELEFON_BEENDET) return false
    val endeMs = befehl.zeitMs ?: return false
    val start = eigenerStartMs ?: return false
    if (start >= endeMs) return false
    val alterMs = jetztMs - endeMs
    return alterMs in -TELEFON_ENDE_UHRZEIT_TOLERANZ_MS..TELEFON_ENDE_MAX_ALTER_MS
}

/** Kodiert [befehl] als UTF-8-JSON-Bytes. */
fun kodiereBefehl(befehl: Befehl): ByteArray = kodiere(befehl.toJson())

/** Dekodiert eine unter [PFAD_BEFEHL_AN_TELEFON]/[PFAD_BEFEHL_AN_UHR] empfangene Nachricht. */
fun dekodiereBefehl(bytes: ByteArray): Befehl = Befehl.fromJson(dekodiere(bytes))

// ---------------------------------------------------------------------------
// Aufzeichnungszustand (Telefon → Uhr, Pfad [PFAD_ZUSTAND])
// ---------------------------------------------------------------------------

/**
 * Aktueller Aufzeichnungszustand, wie ihn das Telefon periodisch an die Uhr
 * schickt, damit deren Anzeige (Dauer, Distanz, ggf. Herzfrequenz) auch dann
 * stimmt, wenn die Aufzeichnung selbst auf dem Telefon laeuft.
 */
data class AufzeichnungsZustand(
    val laeuft: Boolean,
    val pausiert: Boolean,
    val dauerMs: Long,
    val distanzKm: Double,
    val hf: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("laeuft", laeuft)
        put("pausiert", pausiert)
        put("dauerMs", dauerMs)
        put("distanzKm", distanzKm)
        hf?.let { put("hf", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): AufzeichnungsZustand = AufzeichnungsZustand(
            laeuft = json.optionalBoolean("laeuft") ?: false,
            pausiert = json.optionalBoolean("pausiert") ?: false,
            dauerMs = json.requiredLong("dauerMs"),
            distanzKm = json.requiredDouble("distanzKm"),
            hf = json.optionalInt("hf"),
        )
    }
}

/** Kodiert [zustand] als UTF-8-JSON-Bytes. */
fun kodiereAufzeichnungsZustand(zustand: AufzeichnungsZustand): ByteArray = kodiere(zustand.toJson())

/** Dekodiert eine unter [PFAD_ZUSTAND] empfangene Nachricht. */
fun dekodiereAufzeichnungsZustand(bytes: ByteArray): AufzeichnungsZustand =
    AufzeichnungsZustand.fromJson(dekodiere(bytes))

// ---------------------------------------------------------------------------
// Abschluss nach der Fahrt (Telefon → Uhr, Pfad [PFAD_ABSCHLUSS])
// ---------------------------------------------------------------------------

/**
 * Hoechstens [MAX_ZEILEN] fertige, bereits lokalisierte Zeilen dazu, was die
 * Tour [rideId] gebracht hat („Neue Bestweite: 82,4 km (vorher 61,0)"). Das
 * Telefon rechnet und formuliert (`rideHighlights` in `:core`, Saetze aus den
 * `map_highlight_*`-Ressourcen der App), die Uhr zeigt nur Text.
 *
 * Anders als die uebrigen Typen dekodiert dieser **tolerant**
 * ([dekodiereAbschlussInfo] liefert `null` statt zu werfen): Die Anzeige ist
 * reine Zugabe, eine kaputte oder unerwartete Nutzlast soll sie stillschweigend
 * entfallen lassen. Kuerzen und Zeilenzahl gelten beim Kodieren UND beim
 * Dekodieren — eine kuenftige Telefonversion mit mehr oder laengeren Zeilen
 * sprengt den Uhrbildschirm einer heutigen Uhr nicht.
 */
data class AbschlussInfo(val rideId: String, val zeilen: List<String>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("rideId", rideId)
        put("zeilen", buildJsonArray { zeilen.forEach { add(JsonPrimitive(it)) } })
    }

    companion object {
        /** Mehr als zwei Zeilen passen auf eine runde Uhr nicht unter die Zusammenfassung. */
        const val MAX_ZEILEN = 2

        /** Laenge einer Zeile in Zeichen, danach wird mit „…" gekuerzt. */
        const val MAX_ZEICHEN = 60
    }
}

/**
 * Die Zeilen so, wie sie uebertragen und angezeigt werden: getrimmt, leere
 * weg, hoechstens [AbschlussInfo.MAX_ZEILEN], jede hoechstens
 * [AbschlussInfo.MAX_ZEICHEN] Zeichen (sonst gekuerzt mit „…", ohne ein
 * Surrogatpaar zu zerschneiden).
 */
internal fun bereinigeAbschlussZeilen(zeilen: List<String>): List<String> =
    zeilen.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(AbschlussInfo.MAX_ZEILEN)
        .map(::kuerzeZeile)
        .toList()

private fun kuerzeZeile(zeile: String): String {
    if (zeile.length <= AbschlussInfo.MAX_ZEICHEN) return zeile
    var ende = AbschlussInfo.MAX_ZEICHEN - 1
    if (Character.isHighSurrogate(zeile[ende - 1])) ende -= 1
    return zeile.substring(0, ende).trimEnd() + "…"
}

/**
 * Kodiert [info] fuer [PFAD_ABSCHLUSS], mit bereinigten Zeilen (siehe
 * [bereinigeAbschlussZeilen]). `null`, wenn danach keine Zeile uebrig ist —
 * dann gibt es nichts zu senden.
 */
fun kodiereAbschlussInfo(info: AbschlussInfo): ByteArray? {
    val zeilen = bereinigeAbschlussZeilen(info.zeilen)
    if (zeilen.isEmpty()) return null
    return kodiere(info.copy(zeilen = zeilen).toJson())
}

/**
 * Dekodiert eine unter [PFAD_ABSCHLUSS] empfangene Nachricht. Wirft nie:
 * kaputtes JSON, fehlende Felder, falsche Typen oder keine einzige Zeile
 * ergeben `null`. Einzelne Nicht-Text-Eintraege in `zeilen` werden
 * uebersprungen.
 */
fun dekodiereAbschlussInfo(bytes: ByteArray): AbschlussInfo? = runCatching {
    val json = dekodiere(bytes)
    val rideId = json.optionalString("rideId") ?: return null
    val roh = (json["zeilen"] as? JsonArray) ?: return null
    val zeilen = bereinigeAbschlussZeilen(
        roh.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
    )
    if (zeilen.isEmpty()) null else AbschlussInfo(rideId, zeilen)
}.getOrNull()

// ---------------------------------------------------------------------------
// Gemeinsame Kodierhilfen
// ---------------------------------------------------------------------------

private fun kodiere(json: JsonObject): ByteArray = json.toString().toByteArray(StandardCharsets.UTF_8)

/** Wirft [MissingOrInvalidFieldException]/eine JSON-Parse-Exception bei kaputten Bytes — bewusst ungeschuetzt, siehe SyncClient.kt. */
private fun dekodiere(bytes: ByteArray): JsonObject =
    Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).asRequiredObject()
