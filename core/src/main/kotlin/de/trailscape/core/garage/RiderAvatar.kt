package de.trailscape.core.garage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # Aussehen von Fahrer und Rad („Garage", docs/indoor/garage.md)
 *
 * Rein kosmetisch: nichts hier beruehrt Physik, Leistung oder Vergleiche
 * (`RiderSetup` bleibt davon unabhaengig). Farben sind sRGB `0xRRGGBB`, der
 * Hautton ist ein Index in [GaragePalettes.SKIN].
 *
 * **Format** (klein, formatstabil, ohne Freitext — kein Name, kein Bild):
 *
 * ```json
 * {"v":1,"body":{"skin":2},
 *  "kit":{"jersey":"#D8432C","jerseyAccent":"#F1EEE8","pattern":"band","bibs":"#25262B","shoes":"#F1EEE8","helmet":"#F1EEE8"},
 *  "bike":{"frame":"#1F6272","frameAccent":"#F1EEE8","barTape":"#25262B"}}
 * ```
 *
 * Lesen ist nachsichtig und wirft nie: fehlende oder ungueltige Felder nehmen
 * die Vorgabe, unbekannte Felder und Kennungen werden ueberlesen (so liest
 * diese Version auch eine kuenftige `v` mit weiteren Feldern). Gueltige Farben
 * ausserhalb der Paletten bleiben erhalten (eine neuere Version mag mehr
 * Farben anbieten); die Garage zeigt sie dann ohne Auswahlmarke.
 */
data class RiderAvatar(
    val skin: Int = DEFAULT_SKIN,
    val jersey: Int = 0xD8432C,
    val jerseyAccent: Int = 0xF1EEE8,
    val pattern: JerseyPattern = JerseyPattern.BAND,
    val bibs: Int = 0x25262B,
    val shoes: Int = 0xF1EEE8,
    val helmet: Int = 0xF1EEE8,
    val frame: Int = 0x1F6272,
    val frameAccent: Int = 0xF1EEE8,
    val barTape: Int = 0x25262B,
) {
    init {
        require(skin in GaragePalettes.SKIN.indices) { "Hautton $skin ausserhalb der Palette" }
        for (c in listOf(jersey, jerseyAccent, bibs, shoes, helmet, frame, frameAccent, barTape)) {
            require(c in 0..0xFFFFFF) { "Farbe ${c.toString(16)} ist kein sRGB-Wert" }
        }
    }

    /** Das JSON-Objekt (fuer das Backup). Feste Reihenfolge der Schluessel. */
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("v", FORMAT_VERSION)
        put("body", buildJsonObject { put("skin", skin) })
        put(
            "kit",
            buildJsonObject {
                put("jersey", hex(jersey))
                put("jerseyAccent", hex(jerseyAccent))
                put("pattern", pattern.id)
                put("bibs", hex(bibs))
                put("shoes", hex(shoes))
                put("helmet", hex(helmet))
            },
        )
        put(
            "bike",
            buildJsonObject {
                put("frame", hex(frame))
                put("frameAccent", hex(frameAccent))
                put("barTape", hex(barTape))
            },
        )
    }

    /** Kompakte Zeichenkette (fuer die Einstellungen); etwa 250 Byte. */
    fun toJson(): String = toJsonObject().toString()

    companion object {
        const val FORMAT_VERSION = 1

        /** Vorgabe-Hautton (Index), der Ton des bisherigen Fahrermodells. */
        const val DEFAULT_SKIN = 2

        val DEFAULT = RiderAvatar()

        /** Liest [text]; `null` nur, wenn es gar kein JSON-Objekt ist. Wirft nie. */
        fun parse(text: String?): RiderAvatar? {
            if (text.isNullOrBlank()) return null
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
            return fromJson(element)
        }

        /** Liest ein JSON-Element; `null`, wenn es kein Objekt ist. Wirft nie. */
        fun fromJson(element: JsonElement?): RiderAvatar? {
            val obj = element as? JsonObject ?: return null
            val d = DEFAULT
            val body = obj["body"] as? JsonObject
            val kit = obj["kit"] as? JsonObject
            val bike = obj["bike"] as? JsonObject
            val skin = (body?.get("skin") as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.content?.toIntOrNull()
                ?.takeIf { it in GaragePalettes.SKIN.indices }
                ?: d.skin
            return RiderAvatar(
                skin = skin,
                jersey = color(kit, "jersey") ?: d.jersey,
                jerseyAccent = color(kit, "jerseyAccent") ?: d.jerseyAccent,
                pattern = (kit?.get("pattern") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let(JerseyPattern::fromId) ?: d.pattern,
                bibs = color(kit, "bibs") ?: d.bibs,
                shoes = color(kit, "shoes") ?: d.shoes,
                helmet = color(kit, "helmet") ?: d.helmet,
                frame = color(bike, "frame") ?: d.frame,
                frameAccent = color(bike, "frameAccent") ?: d.frameAccent,
                barTape = color(bike, "barTape") ?: d.barTape,
            )
        }

        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

        private fun color(obj: JsonObject?, key: String): Int? {
            val p = obj?.get(key) as? JsonPrimitive ?: return null
            if (!p.isString || !HEX.matches(p.content)) return null
            return p.content.substring(1).toInt(16)
        }

        /** `#RRGGBB` in Grossbuchstaben. */
        fun hex(srgb: Int): String = "#" + (srgb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
    }
}

/**
 * Trikotmuster. Ohne Texturen: Das Trikot des Fahrermodells ist in Felder
 * geteilt ([RiderZone]), die Muster legen fest, welches Feld Grund- oder
 * Akzentfarbe (oder eine Mischung) bekommt ([RiderLook]). [id] steht im JSON
 * und aendert sich nie.
 */
enum class JerseyPattern(val id: String) {
    /** Einfarbig. */
    SOLID("solid"),

    /** Brustband in der Akzentfarbe, dunkle Seitenteile — das bisherige Trikot. */
    BAND("band"),

    /** Zweifarbig: oben Akzent, unten Grundfarbe. */
    SPLIT("split"),

    /** Farbverlauf von der Grundfarbe unten zur Akzentfarbe oben, in Stufen. */
    FADE("fade"),

    /** Quer gestreift. */
    STRIPES("stripes"),
    ;

    companion object {
        /** Unbekannte Kennung → `null` (der Aufrufer nimmt die Vorgabe). */
        fun fromId(id: String): JerseyPattern? = entries.firstOrNull { it.id == id }
    }
}
