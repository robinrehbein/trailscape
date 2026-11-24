package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
        Species.CONIFER to intArrayOf(58, 84, 54),
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
                val first = crown.triangleCount
                blob(crown, 0.0, cy, 0.0, rx * 0.92, ry * 0.92, rx * 0.92, 14, 8, s, leaf, cb, h)
                for (k in 0 until 4) {
                    val a = s.turn + k * 1.63
                    val o = rx * (0.42 + 0.08 * (k and 1))
                    val up = ch * (if (k == 3) 0.2 else -0.06 + 0.05 * k)
                    blob(crown, cos(a) * o, cy + up, -sin(a) * o, rx * 0.6, ry * 0.56, rx * 0.6, 10, 6, s, leaf, cb, h)
                }
                fluffNormals(crown, first, 0.0, cy, 0.0, rx, ry)
            }
            Lod.MID -> {
                val first = crown.triangleCount
                blob(crown, 0.0, cy, 0.0, rx, ry, rx, 11, 6, s, leaf, cb, h)
                val o = rx * 0.48
                blob(crown, cos(s.turn) * o, cy - ch * 0.06, -sin(s.turn) * o, rx * 0.6, ry * 0.58, rx * 0.6, 8, 5, s, leaf, cb, h)
                fluffNormals(crown, first, 0.0, cy, 0.0, rx, ry)
            }
            Lod.FAR -> {
                val first = crown.triangleCount
                blob(crown, 0.0, cy, 0.0, rx * 1.05, ry, rx * 1.05, 8, 5, s, leaf, cb, h)
                fluffNormals(crown, first, 0.0, cy, 0.0, rx, ry)
            }
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
                val first = crown.triangleCount
                blob(crown, 0.0, cb + ch * 0.34, 0.0, ch * 0.22 * w, ch * 0.36, ch * 0.22 * w, 12, 8, s, leaf, cb, h)
                blob(crown, cos(s.turn) * ch * 0.04, cb + ch * 0.7, -sin(s.turn) * ch * 0.04, ch * 0.17 * w, ch * 0.3, ch * 0.17 * w, 10, 7, s, leaf, cb, h)
                fluffNormals(crown, first, 0.0, cb + ch * 0.5, 0.0, ch * 0.22 * w, ch * 0.5)
            }
            Lod.MID -> {
                val first = crown.triangleCount
                blob(crown, 0.0, cb + ch * 0.36, 0.0, ch * 0.22 * w, ch * 0.36, ch * 0.22 * w, 9, 6, s, leaf, cb, h)
                blob(crown, 0.0, cb + ch * 0.72, 0.0, ch * 0.16 * w, ch * 0.28, ch * 0.16 * w, 7, 5, s, leaf, cb, h)
                fluffNormals(crown, first, 0.0, cb + ch * 0.5, 0.0, ch * 0.22 * w, ch * 0.5)
            }
            Lod.FAR -> {
                val first = crown.triangleCount
                blob(crown, 0.0, cb + ch * 0.5, 0.0, ch * 0.21 * w, ch * 0.5, ch * 0.21 * w, 7, 5, s, leaf, cb, h)
                fluffNormals(crown, first, 0.0, cb + ch * 0.5, 0.0, ch * 0.21 * w, ch * 0.5)
            }
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
                val first = crown.triangleCount
                blob(crown, 0.0, cy, 0.0, rx, ry, rx, 12, 7, s, leaf, cb, h)
                blob(crown, cos(s.turn) * rx * 0.45, cy + ch * 0.12, -sin(s.turn) * rx * 0.45, rx * 0.6, ry * 0.6, rx * 0.6, 9, 6, s, leaf, cb, h)
                fluffNormals(crown, first, 0.0, cy, 0.0, rx, ry)
            }
            Lod.MID -> {
                val first = crown.triangleCount
                blob(crown, 0.0, cy, 0.0, rx, ry, rx, 9, 5, s, leaf, cb, h)
                fluffNormals(crown, first, 0.0, cy, 0.0, rx, ry)
            }
            Lod.FAR -> {
                val first = crown.triangleCount
                blob(crown, 0.0, cy, 0.0, rx, ry, rx, 7, 4, s, leaf, cb, h)
                fluffNormals(crown, first, 0.0, cy, 0.0, rx, ry)
            }
        }
    }

    private fun conifer(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder) {
        val h = s.heightM
        val leaf = LEAF.getValue(s.species)
        val bark = lin(Argb.rgb(66, 50, 40))
        val (tiers, seg) = when (lod) {
            Lod.NEAR -> 6 to 12
            Lod.MID -> 4 to 9
            Lod.FAR -> 1 to 7
        }
        stem(trunk, s, lod, 0.014 * h, 0.35 * h, bark)
        spruce(crown, s, leaf, h, tiers, seg)
    }

    /**
     * Fichte als ein Drehkoerper: Astetagen als Saegezahn im Profil (unten
     * breit und leicht haengend, nach oben eingezogen), Rand stetig verbeult,
     * Normalen nach aussen-oben gemischt — weiche Silhouette ohne Kegelkanten.
     */
    private fun spruce(m: MeshBuilder, s: Spec, leaf: IntArray, h: Double, tiers: Int, seg: Int) {
        val b0 = 0.06 * h
        val r0 = 0.22 * h * s.width
        val first = m.triangleCount
        // Profil: Paare (y, r, Schatten) von unten nach oben.
        val ys = ArrayList<Double>()
        val rs = ArrayList<Double>()
        val sh = ArrayList<Double>()
        val outerRing = ArrayList<Boolean>()
        val span = h - b0
        val tierH = span * 0.86 / tiers
        for (t in 0 until tiers) {
            val yb = b0 + t * tierH
            val env = (1.0 - (yb - b0) / span).let { r0 * it * (0.35 + 0.65 * it) }
            val envN = r0 * (1.0 - (yb + tierH - b0) / span)
            // Unterseite (dunkel), Rand (breit, etwas haengend), dann eingezogen zur naechsten Etage.
            val rim = maxOf(env, envN * 1.25, r0 * 0.12)
            val f = t.toDouble() / tiers
            if (t > 0) { ys += yb - 0.12 * tierH; rs += rim * 0.8; sh += 0.5 + 0.2 * f; outerRing += false }
            ys += yb - 0.04 * tierH; rs += rim; sh += 0.74 + 0.3 * f; outerRing += true
            ys += yb + 0.6 * tierH; rs += rim * 0.48; sh += 0.86 + 0.3 * f; outerRing += false
        }
        if (tiers == 1) { ys[1] = b0 + span * 0.55; rs[1] = r0 * 0.42 }
        val rings = ys.size
        val base = m.vertexCount
        for (k in 0 until rings) {
            val y = ys[k]
            val outer = outerRing[k]
            for (j in 0..seg) {
                val a = 2 * PI * j / seg + s.turn
                val jj = j % seg
                val wob = if (outer) 0.82 + 0.36 * Noise.value(jj * 0.9 + k * 3.7, k * 1.3, s.seed + 5, seg) else 0.92 + 0.12 * Noise.value(jj * 0.9, k * 2.1, s.seed + 9, seg)
                val r = rs[k] * wob
                val shade = s.tint * sh[k] * (if (outer) 1.0 else 0.82)
                m.vertex(cos(a) * r, y + (if (outer) -0.05 * tierH * (wob - 0.82) * 3 else 0.0), -sin(a) * r, cos(a), 0.4, -sin(a),
                    j.toDouble() / seg * 3, k * 0.7, leafColor(leaf, shade))
            }
        }
        val apex = m.vertex(0.0, h, 0.0, 0.0, 1.0, 0.0, 1.5, rings * 0.7, leafColor(leaf, s.tint * 1.15))
        val row = seg + 1
        for (k in 0 until rings - 1) for (j in 0 until seg) {
            val a = base + k * row + j
            m.quad(a, a + 1, a + row + 1, a + row)
        }
        val last = base + (rings - 1) * row
        for (j in 0 until seg) m.tri(last + j, last + j + 1, apex)
        // Unterseite der untersten Etage zu (von unten dunkel).
        val c = m.vertex(0.0, b0 + 0.25 * tierH, 0.0, 0.0, -1.0, 0.0, 0.5, 0.5, leafColor(leaf, s.tint * 0.4))
        for (j in 0 until seg) m.tri(base + j + 1, base + j, c)
        m.computeSmoothNormals(first, m.triangleCount, byPosition = true)
        fluffNormals(m, first, 0.0, b0 + span * 0.35, 0.0, r0, span * 0.6, up = 0.12)
    }

    /**
     * Mischt die Normalen der Dreiecke ab [firstTri] mit der Richtung vom
     * Kronenmittelpunkt aus: Die Krone wird wie ein weicher Koerper beleuchtet,
     * Dellen und Einzelkoerper bilden keine harten Licht-Schatten-Kanten.
     */
    private fun fluffNormals(m: MeshBuilder, firstTri: Int, cx: Double, cy: Double, cz: Double, rx: Double, ry: Double, up: Double = 0.25) {
        val seen = HashSet<Int>()
        for (k in firstTri * 3 until m.triangleCount * 3) {
            val v = m.indices[k]
            if (!seen.add(v)) continue
            val px = (m.positions[v * 3] - cx) / rx
            val py = (m.positions[v * 3 + 1] - cy) / ry
            val pz = (m.positions[v * 3 + 2] - cz) / rx
            val l = sqrt(px * px + py * py + pz * pz).coerceAtLeast(1e-6)
            var nx = 0.4 * m.normals[v * 3] + 0.6 * px / l
            var ny = 0.4 * m.normals[v * 3 + 1] + 0.6 * py / l + up
            var nz = 0.4 * m.normals[v * 3 + 2] + 0.6 * pz / l
            val n = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6)
            nx /= n; ny /= n; nz /= n
            m.setNormal(v, nx, ny, nz)
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
            var k = 0.84 + 0.26 * n1 + 0.06 * n2
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

    private fun leafColor(srgb: IntArray, factor: Double): Int {
        val f = factor.coerceIn(0.0, 1.45)
        return lin(Argb.rgb(min(255, (srgb[0] * f).toInt()), min(255, (srgb[1] * f).toInt()), min(255, (srgb[2] * f).toInt())))
    }
}
