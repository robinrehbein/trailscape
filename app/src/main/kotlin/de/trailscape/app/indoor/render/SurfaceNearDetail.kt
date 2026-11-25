package de.trailscape.app.indoor.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # Nahdetail der Fahrbahn
 *
 * Regeln und Parameter fuer das Nahdetail in `surface.mat`/`surface_blend.mat`: je Belag eine
 * kachelbare Detailkarte (Gefaelle in R/G, Hoehe in B; erzeugt von
 * `tool/indoor-dev/filament/nahdetail.py`, Assets unter [DIR]) und die Parameter `nearA/B/C`
 * aus `detail.json`. Reine Regeln ohne Android und Filament — getestet; `filarender.cpp`
 * spiegelt [keyFor]. Texturen und Laden aus den Assets: [SurfaceNearTextures].
 *
 * Nur Fahrbahnen bekommen Nahdetail (`mat_route_<belag>`, `x_mat_road_<belag>`); Randstreifen
 * haben ihr eigenes Boden-Detail (`detailClass`). Sicherer Modus: die Karten werden gar nicht
 * geladen und die eigenen Materialien nicht benutzt (Ubershader).
 */
internal object SurfaceNearDetail {
    /** Verzeichnis in den Assets. */
    const val DIR = "indoor/surface-detail"

    /** Schluessel im Paket-Verzeichnis von [FilamentCore.materialPackages] (neben den `.filamat`). */
    const val PACKAGE_PREFIX = "surface-detail/"
    const val META = "detail.json"

    /** Parameter eines Belags (Bedeutung: `nahdetail.py`, Tabelle KINDS). */
    data class Kind(
        val map: String,
        val repeatM: Float,
        val normal: Float,
        val albedo: Float,
        val relief: Float,
        val rough: Float,
        val roughVar: Float,
        val trackGloss: Float,
        val trackU: Float,
        val reliefTexels: Float,
        val reliefMip: Float,
    )

    data class Spec(
        val rangeM: Float,
        val reliefRangeM: Float,
        /** Karte → Dateiname. */
        val maps: Map<String, String>,
        /** Belag (`SurfaceKind.textureKey`) → Parameter. */
        val kinds: Map<String, Kind>,
    ) {
        /** `nearA`, `nearB`, `nearC` fuer einen Belag; [on] = false: alles 0 (Material wie vorher). */
        fun params(kind: Kind, on: Boolean): Array<FloatArray> = if (!on) {
            Array(3) { FloatArray(4) }
        } else {
            arrayOf(
                floatArrayOf(rangeM, kind.normal, kind.albedo, kind.relief),
                floatArrayOf(kind.repeatM, kind.rough, kind.roughVar, kind.trackGloss),
                floatArrayOf(kind.trackU, reliefRangeM, kind.reliefTexels, kind.reliefMip),
            )
        }
    }

    /** Belag der Fahrbahn aus dem Materialnamen der Welt, sonst `null` (Randstreifen, Bordstein …). */
    fun keyFor(label: String?, spec: Spec): String? {
        val l = label ?: return null
        val k = when {
            l.startsWith("mat_route_") -> l.removePrefix("mat_route_")
            l.startsWith("x_mat_road_") -> l.removePrefix("x_mat_road_")
            else -> return null
        }
        return k.takeIf { it in spec.kinds }
    }

    /** Liest `detail.json`; `null` bei jedem Fehler (dann bleibt das Nahdetail aus). */
    fun parse(text: String): Spec? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.f(name: String, def: Float? = null): Float =
            this[name]?.jsonPrimitive?.content?.toFloat() ?: def ?: error("$name fehlt")
        val maps = root.getValue("maps").jsonObject.mapValues { (_, v) -> v.jsonObject.getValue("file").jsonPrimitive.content }
        val kinds = root.getValue("kinds").jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            Kind(
                map = o.getValue("map").jsonPrimitive.content,
                repeatM = o.f("repeatM"),
                normal = o.f("normal"),
                albedo = o.f("albedo"),
                relief = o.f("relief"),
                rough = o.f("rough"),
                roughVar = o.f("roughVar"),
                trackGloss = o.f("trackGloss"),
                trackU = o.f("trackU"),
                reliefTexels = o.f("reliefTexels", 1f),
                reliefMip = o.f("reliefMip", 0f),
            )
        }.filterValues { it.map in maps && it.repeatM > 0f }
        Spec(root.f("rangeM"), root.f("reliefRangeM"), maps, kinds)
    }.getOrNull()
}
