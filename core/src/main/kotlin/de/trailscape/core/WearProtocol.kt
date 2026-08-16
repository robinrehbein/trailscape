package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * vier Nachrichtentypen samt Kodierung. Weder `:wear` noch `:app` duerfen ihr
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

/** Befehl Telefon → Uhr (z. B. Pause vom Telefon aus ausgeloest). */
const val PFAD_BEFEHL_AN_UHR = "/trailscape/befehl-an-uhr"

/** Aufzeichnungszustand Telefon → Uhr (fuer die Uhr-Anzeige waehrend der Fahrt). */
const val PFAD_ZUSTAND = "/trailscape/zustand"

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

/** Ein Steuerbefehl fuer die Aufzeichnung. [cmd] ist eine der [Befehl]-Konstanten. */
data class Befehl(val cmd: String) {
    fun toJson(): JsonObject = buildJsonObject { put("cmd", cmd) }

    companion object {
        const val START = "start"
        const val PAUSE = "pause"
        const val WEITER = "weiter"
        const val STOPP = "stopp"

        fun fromJson(json: JsonObject): Befehl = Befehl(cmd = json.requiredString("cmd"))
    }
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
// Gemeinsame Kodierhilfen
// ---------------------------------------------------------------------------

private fun kodiere(json: JsonObject): ByteArray = json.toString().toByteArray(StandardCharsets.UTF_8)

/** Wirft [MissingOrInvalidFieldException]/eine JSON-Parse-Exception bei kaputten Bytes — bewusst ungeschuetzt, siehe SyncClient.kt. */
private fun dekodiere(bytes: ByteArray): JsonObject =
    Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).asRequiredObject()
