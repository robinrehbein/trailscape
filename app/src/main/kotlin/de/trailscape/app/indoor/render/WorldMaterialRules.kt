package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.WorldNames

/**
 * Welche glTF-Materialien der Kacheln die Darstellung durch eigene Filament-Materialien
 * ersetzt ([WorldMaterialProvider]) — reine Regeln nach dem Materialnamen der Welt
 * (`WorldNames`, `x_…`), ohne Filament, getestet. `filarender.cpp` (Referenzrenderer)
 * spiegelt sie (`kindFor`, `detailClass`).
 */
internal object WorldMaterialRules {
    enum class Kind(val asset: String?) {
        /** gltfio-Ubershader wie bisher. */
        UBER(null),

        /** Gelaende (`mat_terrain`): Luftbild + Boden-Detail, waermerer Waldboden. */
        TERRAIN("terrain"),

        /** Laub, Nadeln, Grasbueschel mit Wind. */
        FOLIAGE("foliage"),

        /** Deckende Belaege und Randstreifen: Software-Anisotropie, Detail auf Randstreifen. */
        SURFACE("surface"),

        /** Auslaeufe der Randstreifen und Graskante (durchsichtig). */
        SURFACE_BLEND("surface_blend"),

        /** Gelaende im Streckenband (`x_mat_terrain_band_<n>_<i>_<r>`): feineres Luftbild, Detail, Anisotropie. */
        TERRAIN_BAND("terrain_band"),
    }

    /** glTF-`alphaMode` wie in gltfios `MaterialKey.alphaMode`. */
    const val ALPHA_OPAQUE = 0
    const val ALPHA_MASK = 1
    const val ALPHA_BLEND = 2

    const val MAT_TREE_CARDS = "x_mat_tree_cards"

    /** Materialname der Bandfelder (Weltbau `AerialBand.MATERIAL_PREFIX`). */
    const val MAT_TERRAIN_BAND_PREFIX = "x_mat_terrain_band_"

    fun kindFor(label: String?, alphaMode: Int, hasBaseColorTexture: Boolean, unlit: Boolean): Kind {
        val l = label ?: return Kind.UBER
        if (!hasBaseColorTexture || unlit) return Kind.UBER
        if (l == WorldNames.MAT_TERRAIN) return if (alphaMode == ALPHA_OPAQUE) Kind.TERRAIN else Kind.UBER
        if (l.startsWith(MAT_TERRAIN_BAND_PREFIX)) {
            return if (alphaMode == ALPHA_OPAQUE && bandCell(l) != null) Kind.TERRAIN_BAND else Kind.UBER
        }
        if (l == MAT_TREE_CARDS || l == WorldNames.MAT_TREE_LEAVES) return if (alphaMode == ALPHA_MASK) Kind.FOLIAGE else Kind.UBER
        if (alphaMode == ALPHA_OPAQUE && (
                l.startsWith(WorldNames.MAT_ROUTE_PREFIX) ||
                    (l.startsWith("x_mat_road_") && !l.endsWith("_edge")) ||
                    (l.startsWith("x_mat_route_verge_") && !l.endsWith("_fade")) ||
                    l == "x_mat_route_shoulder" || l == "x_mat_route_sidewalk"
                )
        ) {
            return Kind.SURFACE
        }
        if (alphaMode == ALPHA_BLEND && ((l.startsWith("x_mat_route_") && l.endsWith("_fade")) || l == "x_mat_route_fringe")) {
            return Kind.SURFACE_BLEND
        }
        return Kind.UBER
    }

    /** Gewichte der Bodenarten (Wiese, Erde, Waldboden, Schotter) je Randstreifen; 0 = kein Detail. */
    fun detailClass(label: String?): FloatArray {
        val l = label ?: return floatArrayOf(0f, 0f, 0f, 0f)
        return when {
            "verge_grass" in l -> floatArrayOf(1f, 0f, 0f, 0f)
            "verge_forest" in l -> floatArrayOf(0f, 0f, 1f, 0f)
            "verge_shoulder" in l || l == "x_mat_route_shoulder" -> floatArrayOf(0f, 0.4f, 0f, 0.6f)
            l == "x_mat_route_fringe" -> floatArrayOf(0.7f, 0f, 0f, 0f)
            else -> floatArrayOf(0f, 0f, 0f, 0f)
        }
    }

    /**
     * `bandCell` eines Bandfelds aus dem Namen `x_mat_terrain_band_<n>_<i>_<r>`: (Spalte nach Osten,
     * Zeile von Norden, Felder je Kachelkante); `null`, wenn der Name nicht passt.
     */
    fun bandCell(label: String?): FloatArray? {
        val l = label ?: return null
        if (!l.startsWith(MAT_TERRAIN_BAND_PREFIX)) return null
        val p = l.removePrefix(MAT_TERRAIN_BAND_PREFIX).split('_')
        if (p.size != 3) return null
        val n = p[0].toIntOrNull() ?: return null
        val i = p[1].toIntOrNull() ?: return null
        val r = p[2].toIntOrNull() ?: return null
        if (n !in 1..64 || i !in 0 until n || r !in 0 until n) return null
        return floatArrayOf(i.toFloat(), r.toFloat(), n.toFloat())
    }

    /** `atlasMode` des Laubs: 1 = Atlas von FoliageAtlas (Gewicht aus der Lage), 0 = Kronendach. */
    fun atlasMode(label: String?): Float = if (label == MAT_TREE_CARDS) 1f else 0f
}
