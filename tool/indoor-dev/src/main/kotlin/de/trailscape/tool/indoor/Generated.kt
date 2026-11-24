package de.trailscape.tool.indoor

import de.trailscape.core.indoor.generated.GeneratedProfile
import de.trailscape.core.indoor.generated.GeneratedProfile.Kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import java.io.File

/**
 * Profile fuer `generated` (docs/indoor/generiert.md):
 *  * Vorlage: `grundlage60`, `intervalle4x8`, `erholung30`, `berg`;
 *  * JSON-Datei: `{"seed": 1, "legs": [{"lengthM": 3000, "gradePct": 0, "kind": "FLAT"}, …]}`;
 *  * Kurzschreibweise: Legs mit Komma, je `<laenge>[km|m][@<steigung>][:f|r|c|d]`,
 *    z. B. `3km:f,2.4km@6:c,1.5km:f,5km@0:r` (Vorgabe: km, 0 %, f).
 */
internal fun parseGeneratedProfile(spec: String, seed: Long?): GeneratedProfile {
    presetProfile(spec)?.let { return if (seed != null) it.copy(seed = seed) else it }
    val file = File(spec)
    if (file.isFile) {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        val legs = o.getValue("legs").jsonArray.map { e ->
            val l = e.jsonObject
            GeneratedProfile.Leg(
                l.getValue("lengthM").jsonPrimitive.double,
                l.getValue("gradePct").jsonPrimitive.double,
                Kind.valueOf(l.getValue("kind").jsonPrimitive.content.uppercase()),
            )
        }
        return GeneratedProfile(seed ?: (o["seed"]?.jsonPrimitive?.long ?: 1L), legs)
    }
    val legs = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::parseLeg)
    require(legs.isNotEmpty()) { "Profil ohne Abschnitte: $spec" }
    return GeneratedProfile(seed ?: 1L, legs)
}

private val LEG = Regex("""^([0-9]+(?:\.[0-9]+)?)(km|m)?(?:@(-?[0-9]+(?:\.[0-9]+)?)%?)?(?::([frcd]))?$""")

private fun parseLeg(s: String): GeneratedProfile.Leg {
    val m = LEG.matchEntire(s.lowercase()) ?: throw IllegalArgumentException("Leg unlesbar: \"$s\" (Beispiel 2.4km@6:c)")
    val v = m.groupValues[1].toDouble()
    val len = if (m.groupValues[2] == "m") v else v * 1000
    val grade = m.groupValues[3].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
    val kind = when (m.groupValues[4]) {
        "r" -> Kind.ROLLING
        "c" -> Kind.CLIMB
        "d" -> Kind.DESCENT
        else -> Kind.FLAT
    }
    return GeneratedProfile.Leg(len, grade, kind)
}

/** Die drei Belegprofile der Doku. Laengen ≈ Dauer × Tempo bei Soll-Leistung (grob geschaetzt). */
internal fun presetProfile(name: String): GeneratedProfile? = when (name.lowercase()) {
    // 60 min Grundlage flach-wellig, ~30 km/h
    "grundlage60" -> GeneratedProfile(
        seed = 6001,
        legs = listOf(
            GeneratedProfile.Leg(3_000.0, 0.0, Kind.FLAT),
            GeneratedProfile.Leg(8_000.0, 0.5, Kind.ROLLING),
            GeneratedProfile.Leg(8_000.0, -0.3, Kind.ROLLING),
            GeneratedProfile.Leg(8_000.0, 0.2, Kind.ROLLING),
            GeneratedProfile.Leg(3_000.0, 0.0, Kind.FLAT),
        ),
    )
    // 10 min Einfahren, 4 × (8 min Anstieg 6 %, 4 min flach), 10 min Ausfahren
    "intervalle4x8" -> GeneratedProfile(
        seed = 4808,
        legs = buildList {
            add(GeneratedProfile.Leg(5_000.0, 0.0, Kind.FLAT))
            repeat(4) { i ->
                add(GeneratedProfile.Leg(2_400.0, 6.0, Kind.CLIMB))
                if (i < 3) add(GeneratedProfile.Leg(1_800.0, -1.0, Kind.FLAT))
            }
            add(GeneratedProfile.Leg(4_500.0, -0.5, Kind.FLAT))
        },
    )
    // 30 min Erholung, ~25 km/h, flach
    "erholung30" -> GeneratedProfile(
        seed = 3003,
        legs = listOf(
            GeneratedProfile.Leg(2_000.0, 0.0, Kind.FLAT),
            GeneratedProfile.Leg(8_500.0, 0.0, Kind.FLAT),
            GeneratedProfile.Leg(2_000.0, 0.0, Kind.FLAT),
        ),
    )
    // Bergtour: Anstieg und Abfahrt je 7 % (zeigt Serpentinen und eine lange Abfahrt)
    "berg" -> GeneratedProfile(
        seed = 7007,
        legs = listOf(
            GeneratedProfile.Leg(4_000.0, 0.0, Kind.FLAT),
            GeneratedProfile.Leg(5_000.0, 7.0, Kind.CLIMB),
            GeneratedProfile.Leg(2_000.0, 1.0, Kind.ROLLING),
            GeneratedProfile.Leg(5_000.0, -7.0, Kind.DESCENT),
            GeneratedProfile.Leg(3_000.0, 0.0, Kind.FLAT),
        ),
    )
    else -> null
}

