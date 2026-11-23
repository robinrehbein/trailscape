package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * # Baummodelle fuer [TreesLayer]
 *
 * Vier Arten, je drei Detailstufen, alle um den Fusspunkt (0, 0, 0) gebaut
 * (glTF-Achsen: y oben):
 *
 *  * [Species.BROAD] — Laubbaum mit runder Krone (Buche, Eiche, Linde): ein
 *    Hauptkoerper und bis zu drei ueberlappende Nebenkoerper.
 *  * [Species.TALL] — schlanker hoher Laubbaum (Pappel, Birke): zwei gestapelte
 *    schmale Koerper, bei der Haelfte heller Stamm.
 *  * [Species.CONIFER] — Fichte: bis zu vier gestapelte Kegel mit gezacktem Rand.
 *  * [Species.SMALL] — kleiner Baum mit breiter Krone (Obstbaum, Feldbaum).
 *
 * Kronenkoerper sind verbeulte Ellipsoide: Jeder Eckpunkt wird abhaengig von
 * seiner Richtung (stetiges Rauschen) nach aussen oder innen verschoben,
 * unten etwas abgeflacht, danach bekommen sie geglaettete Normalen — runde,
 * unregelmaessige Kronen ohne harte Kanten. Farben (linear, Vertexfarben):
 * unten dunkler, oben heller, je Baum abgetoent. Stamm mit Wurzelanlauf in der
 * feinsten Stufe.
 */
internal object TreeModels {
    enum class Species { BROAD, TALL, CONIFER, SMALL }

    enum class Lod { FAR, MID, NEAR }

    /** Ein Baum: Art, Hoehe, Breitenfaktor, Abtoenung, Zufall fuer Form und Drehung. */
    class Spec(val species: Species, val heightM: Double, val width: Double, val tint: Double, val seed: Int, val turn: Double, val pale: Boolean)

    /** Grundfarben der Kronen in sRGB. */
    private val LEAF = mapOf(
        Species.BROAD to intArrayOf(80, 108, 52),
        Species.TALL to intArrayOf(98, 124, 60),
        Species.CONIFER to intArrayOf(50, 74, 48),
        Species.SMALL to intArrayOf(88, 114, 54),
    )

    /** Baut den Baum [s] in Stufe [lod] in die Netze [trunk] und [crown]. */
    fun build(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder) {
        when (s.species) {
            Species.BROAD -> broad(s, lod, trunk, crown)
            Species.TALL -> tall(s, lod, trunk, crown)
            Species.CONIFER -> conifer(s, lod, trunk, crown)
            Species.SMALL -> small(s, lod, trunk, crown)
        }
    }

    private val costs = HashMap<Pair<Species, Lod>, Int>()

    /** Dreiecke eines Baums dieser Art und Stufe (haengt nicht von Groesse oder Zufall ab). */
    fun cost(species: Species, lod: Lod): Int = synchronized(costs) {
        costs.getOrPut(species to lod) {
            val t = MeshBuilder(64, 64)
            val c = MeshBuilder(256, 256)
            build(Spec(species, 12.0, 1.0, 1.0, 1, 0.0, false), lod, t, c)
            t.triangleCount + c.triangleCount
        }
    }

    // -----------------------------------------------------------------------

    private fun broad(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder) {
        val h = s.heightM
        val cb = 0.3 * h
        val ch = h - cb
        val rx = ch * 0.46 * s.width
        val ry = ch * 0.44
        val cy = cb + ch * 0.54
        stem(trunk, s, lod, 0.022 * h, cy, barkColor(s))
        val leaf = LEAF.getValue(s.species)
        when (lod) {
            Lod.NEAR -> {
                blob(crown, 0.0, cy, 0.0, rx, ry, rx, 10, 6, s, leaf, cb, h)
                for (k in 0 until 3) {
                    val a = s.turn + k * 2.1
                    val o = rx * 0.55
                    val up = if (k == 2) ch * 0.14 else -ch * 0.1
                    blob(crown, cos(a) * o, cy + up, -sin(a) * o, rx * 0.62, ry * 0.6, rx * 0.62, 8, 5, s, leaf, cb, h)
                }
            }
            Lod.MID -> {
                blob(crown, 0.0, cy, 0.0, rx, ry, rx, 8, 5, s, leaf, cb, h)
                val o = rx * 0.5
                blob(crown, cos(s.turn) * o, cy - ch * 0.08, -sin(s.turn) * o, rx * 0.62, ry * 0.6, rx * 0.62, 6, 4, s, leaf, cb, h)
            }
            Lod.FAR -> blob(crown, 0.0, cy, 0.0, rx * 1.05, ry, rx * 1.05, 6, 4, s, leaf, cb, h)
        }
    }

    private fun tall(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder) {
        val h = s.heightM
        val cb = 0.22 * h
        val ch = h - cb
        val w = s.width
        stem(trunk, s, lod, 0.016 * h, cb + ch * 0.4, barkColor(s))
        val leaf = LEAF.getValue(s.species)
        when (lod) {
            Lod.NEAR -> {
                blob(crown, 0.0, cb + ch * 0.34, 0.0, ch * 0.22 * w, ch * 0.36, ch * 0.22 * w, 9, 6, s, leaf, cb, h)
                blob(crown, cos(s.turn) * ch * 0.04, cb + ch * 0.7, -sin(s.turn) * ch * 0.04, ch * 0.17 * w, ch * 0.3, ch * 0.17 * w, 8, 5, s, leaf, cb, h)
            }
            Lod.MID -> {
                blob(crown, 0.0, cb + ch * 0.36, 0.0, ch * 0.22 * w, ch * 0.36, ch * 0.22 * w, 7, 5, s, leaf, cb, h)
                blob(crown, 0.0, cb + ch * 0.72, 0.0, ch * 0.16 * w, ch * 0.28, ch * 0.16 * w, 6, 4, s, leaf, cb, h)
            }
            Lod.FAR -> blob(crown, 0.0, cb + ch * 0.5, 0.0, ch * 0.21 * w, ch * 0.5, ch * 0.21 * w, 6, 4, s, leaf, cb, h)
        }
    }

    private fun small(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder) {
        val h = s.heightM
        val cb = 0.32 * h
        val ch = h - cb
        val rx = ch * 0.62 * s.width
        val ry = ch * 0.46
        val cy = cb + ch * 0.5
        stem(trunk, s, lod, 0.03 * h, cy, barkColor(s))
        val leaf = LEAF.getValue(s.species)
        when (lod) {
            Lod.NEAR -> {
                blob(crown, 0.0, cy, 0.0, rx, ry, rx, 9, 5, s, leaf, cb, h)
                blob(crown, cos(s.turn) * rx * 0.45, cy + ch * 0.12, -sin(s.turn) * rx * 0.45, rx * 0.6, ry * 0.6, rx * 0.6, 7, 4, s, leaf, cb, h)
            }
            Lod.MID -> blob(crown, 0.0, cy, 0.0, rx, ry, rx, 7, 4, s, leaf, cb, h)
            Lod.FAR -> blob(crown, 0.0, cy, 0.0, rx, ry, rx, 5, 3, s, leaf, cb, h)
        }
    }

    private fun conifer(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder) {
        val h = s.heightM
        val leaf = LEAF.getValue(s.species)
        val bark = lin(Argb.rgb(66, 50, 40))
        val (tiers, seg) = when (lod) {
            Lod.NEAR -> 4 to 10
            Lod.MID -> 3 to 8
            Lod.FAR -> 1 to 6
        }
        stem(trunk, s, lod, 0.014 * h, 0.35 * h, bark)
        val b0 = 0.07 * h
        val r0 = 0.21 * h * s.width
        val top = 0.8 * h
        for (t in 0 until tiers) {
            val f = if (tiers == 1) 0.0 else t / (tiers - 1.0)
            val base = b0 + (top - b0) * f * 0.78
            val r = r0 * (1.0 - 0.62 * f)
            val apex = if (t == tiers - 1) h else base + (h - base) * 0.55
            cone(crown, base, r, apex, seg, s, leaf, s.turn + t * 0.37, h)
        }
    }

    // -----------------------------------------------------------------------

    private fun barkColor(s: Spec): Int =
        if (s.pale) lin(Argb.rgb(150, 146, 136)) else lin(Argb.rgb(74, 62, 50))

    /** Stamm bis [top] mit Radius [r], in der feinsten Stufe mit Wurzelanlauf. */
    private fun stem(m: MeshBuilder, s: Spec, lod: Lod, r: Double, top: Double, color: Int) {
        val seg = when (lod) {
            Lod.NEAR -> 6
            Lod.MID -> 4
            Lod.FAR -> 3
        }
        if (lod == Lod.NEAR) {
            m.addCylinder(Vec3(0.0, -0.3, 0.0), Vec3(0.0, 0.7, 0.0), r * 1.9, r * 1.05, seg, false, false, color)
        }
        m.addCylinder(Vec3(0.0, -0.3, 0.0), Vec3(0.0, top, 0.0), r * 1.1, r * 0.6, seg, false, false, color)
    }

    /**
     * Verbeultes Ellipsoid um (cx, cy, cz) mit Radien rx/ry/rz; Farbe nach Hoehe
     * zwischen [crownBottom] und [height], geglaettete Normalen.
     */
    private fun blob(
        m: MeshBuilder, cx: Double, cy: Double, cz: Double, rx: Double, ry: Double, rz: Double,
        seg: Int, rings: Int, s: Spec, leaf: IntArray, crownBottom: Double, height: Double,
    ) {
        val firstVertex = m.vertexCount
        val firstTri = m.triangleCount
        m.addEllipsoid(cx, cy, cz, rx, ry, rz, seg, rings)
        for (v in firstVertex until m.vertexCount) {
            val px = m.positions[v * 3] - cx
            val py = m.positions[v * 3 + 1] - cy
            val pz = m.positions[v * 3 + 2] - cz
            val ux = px / rx
            val uy = py / ry
            val uz = pz / rz
            // Stetig ueber die Kugel: Rauschen ueber die Richtung (gleiche Richtung → gleiche Verschiebung, Naehte bleiben zu).
            val n1 = Noise.value(ux * 1.6 + 3.1, uz * 1.6 + uy * 0.9 + 7.3, s.seed)
            val n2 = Noise.value(uz * 3.1 - 1.7, ux * 3.1 - uy * 1.3, s.seed + 11)
            var k = 0.8 + 0.32 * n1 + 0.12 * n2
            var yScale = k
            if (uy < -0.2) yScale = k * (0.72 + 0.28 * (1 + uy) / 0.8) // unten abgeflacht
            if (uy > 0.85) k *= 0.96
            m.positions[v * 3] = (cx + px * k).toFloat()
            m.positions[v * 3 + 1] = (cy + py * yScale).toFloat()
            m.positions[v * 3 + 2] = (cz + pz * k).toFloat()
            val yWorld = cy + py * yScale
            val t = ((yWorld - crownBottom) / (height - crownBottom)).coerceIn(0.0, 1.0)
            val shade = s.tint * (0.58 + 0.5 * t) * (0.92 + 0.16 * n2)
            m.setColor(v, leafColor(leaf, shade))
            m.uvs[v * 2] *= 3f
            m.uvs[v * 2 + 1] *= 2f
        }
        m.computeSmoothNormals(firstTri, m.triangleCount, byPosition = true)
    }

    /** Kegel mit gezacktem, leicht haengendem Rand und Boden. */
    private fun cone(m: MeshBuilder, y0: Double, r: Double, yTop: Double, seg: Int, s: Spec, leaf: IntArray, rot: Double, height: Double) {
        val h = yTop - y0
        val sl = hypot(r, h)
        val ny = r / sl
        val nr = h / sl
        val base = m.vertexCount
        val lowShade = s.tint * (0.55 + 0.35 * y0 / height)
        val topShade = s.tint * (0.85 + 0.3 * yTop / height)
        fun rimR(k: Int) = r * (if (k % 2 == 0) 1.0 else 0.8)
        fun rimY(k: Int) = y0 + (if (k % 2 == 0) -0.04 * h else 0.05 * h)
        for (k in 0..seg) {
            val a = 2 * PI * (k + rot) / seg
            m.vertex(cos(a) * rimR(k), rimY(k), -sin(a) * rimR(k), cos(a) * nr, ny, -sin(a) * nr, k / seg.toDouble() * 3, 2.0, leafColor(leaf, lowShade))
        }
        for (k in 0 until seg) {
            val a = 2 * PI * (k + 0.5 + rot) / seg
            m.vertex(0.0, yTop, 0.0, cos(a) * nr, ny, -sin(a) * nr, (k + 0.5) / seg * 3, 0.0, leafColor(leaf, topShade))
        }
        for (k in 0 until seg) m.tri(base + k, base + k + 1, base + seg + 1 + k)
        val center = m.vertex(0.0, y0 + 0.1 * h, 0.0, 0.0, -1.0, 0.0, 0.5, 0.5, leafColor(leaf, lowShade * 0.7))
        val ring = m.vertexCount
        for (k in 0..seg) {
            val a = 2 * PI * (k + rot) / seg
            m.vertex(cos(a) * rimR(k), rimY(k), -sin(a) * rimR(k), 0.0, -1.0, 0.0, 0.5 + cos(a) / 2, 0.5 + sin(a) / 2, leafColor(leaf, lowShade * 0.7))
        }
        for (k in 0 until seg) m.tri(center, ring + k + 1, ring + k)
    }

    private fun leafColor(srgb: IntArray, factor: Double): Int {
        val f = factor.coerceIn(0.0, 1.45)
        return lin(Argb.rgb(min(255, (srgb[0] * f).toInt()), min(255, (srgb[1] * f).toInt()), min(255, (srgb[2] * f).toInt())))
    }
}
