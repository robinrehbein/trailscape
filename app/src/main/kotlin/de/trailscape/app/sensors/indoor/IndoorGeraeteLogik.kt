package de.trailscape.app.sensors.indoor

import de.trailscape.core.indoor.IndoorDeviceRole
import de.trailscape.core.indoor.IndoorUuids
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * Reine Logik der Indoor-Geraete ohne Android: das Speicherformat der
 * gemerkten Geraete und das Erkennen der Rolle aus einer Bluetooth-Werbung.
 * Getestet in `IndoorGeraeteLogikTest`.
 */

/**
 * Kodiert die gemerkten Geraete — sinngemaess wie `kodiereGemerkteSensoren`:
 * ein JSON-Array von `{"rolle", "adresse", "name"?}`, je Rolle hoechstens
 * ein Eintrag, Reihenfolge der Rollen fest.
 */
internal fun kodiereIndoorGeraete(geraete: Map<IndoorDeviceRole, IndoorDeviceRef>): String =
    buildJsonArray {
        for (rolle in IndoorDeviceRole.entries) {
            val ref = geraete[rolle] ?: continue
            add(
                buildJsonObject {
                    put("rolle", rolle.name)
                    put("adresse", ref.address)
                    ref.name?.let { put("name", it) }
                },
            )
        }
    }.toString()

/**
 * Liest die gemerkten Geraete. Kaputtes JSON ergibt „nichts gemerkt" (dann
 * koppelt man neu, statt dass die App abstuerzt); unbekannte Rollen (Datei
 * einer neueren App-Version) und Eintraege ohne Adresse werden
 * uebersprungen; je Rolle gewinnt der letzte Eintrag.
 */
internal fun dekodiereIndoorGeraete(raw: String?): Map<IndoorDeviceRole, IndoorDeviceRef> {
    if (raw.isNullOrBlank()) return emptyMap()
    val arr = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyMap()
    val ergebnis = linkedMapOf<IndoorDeviceRole, IndoorDeviceRef>()
    for (el in arr) {
        val obj = el as? JsonObject ?: continue
        val rolle = obj.text("rolle")?.let { n -> IndoorDeviceRole.entries.firstOrNull { it.name == n } } ?: continue
        val adresse = obj.text("adresse")?.takeIf { it.isNotBlank() } ?: continue
        ergebnis[rolle] = IndoorDeviceRef(rolle, adresse, obj.text("name")?.takeIf { it.isNotBlank() })
    }
    return ergebnis
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * Welche Rollen ein Geraet nach seiner Werbung spielen kann.
 *
 *  * **Trainer:** bewirbt den Fitness Machine Service (0x1826).
 *  * **Schalttaster:** bewirbt den Zwift-Dienst **oder** traegt die
 *    Zwift-Firmenkennung in den Herstellerdaten — manche Firmware nennt den
 *    Dienst nicht in der Werbung (docs/indoor.md, Abschnitt 5.1).
 *
 * Ein Geraet, das beides bewirbt (etwa ein Trainer mit eigener
 * Bedienschnittstelle), steht in beiden Listen; gekoppelt wird je Rolle.
 *
 * @param dienste beworbene Dienst-UUIDs, volle Form, beliebige Schreibweise.
 * @param herstellerIds Firmenkennungen aus den Herstellerdaten der Werbung.
 */
internal fun indoorRollenAusWerbung(dienste: Collection<String>, herstellerIds: Collection<Int>): Set<IndoorDeviceRole> {
    val klein = dienste.map { it.lowercase() }.toSet()
    return buildSet {
        if (IndoorUuids.FTMS_SERVICE in klein) add(IndoorDeviceRole.TRAINER)
        if (IndoorUuids.ZWIFT_SERVICE in klein || IndoorUuids.ZWIFT_COMPANY_ID in herstellerIds) {
            add(IndoorDeviceRole.SHIFTER)
        }
    }
}

/** Ein Suchtreffer fuer Trainer oder Schalttaster. */
internal data class IndoorFund(
    val adresse: String,
    val name: String?,
    val rollen: Set<IndoorDeviceRole>,
    val rssi: Int,
)

/**
 * Fuegt [fund] in [liste] ein: je Adresse ein Eintrag (Rollen vereinigt,
 * Name behalten, falls der neue keinen hat — die Scan-Antwort mit dem Namen
 * kommt oft getrennt von der Werbung), sortiert nach Signalstaerke, das
 * naechste Geraet oben.
 */
internal fun fuegeIndoorFundHinzu(liste: List<IndoorFund>, fund: IndoorFund): List<IndoorFund> {
    val alt = liste.firstOrNull { it.adresse == fund.adresse }
    val neu = if (alt == null) fund else fund.copy(name = fund.name ?: alt.name, rollen = alt.rollen + fund.rollen)
    return (liste.filter { it.adresse != fund.adresse } + neu).sortedByDescending { it.rssi }
}
