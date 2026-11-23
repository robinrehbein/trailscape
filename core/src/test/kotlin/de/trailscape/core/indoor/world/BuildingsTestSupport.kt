package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Hilfen fuer die Gebaeude-Tests: Kontexte, Grundrisse und Pruefungen der Netze. */
internal object BuildingsTestSupport {
    val flatGround = GroundSurface { _, _ -> 100.0 }

    fun context(
        spec: TileSpec,
        map: MapData,
        ground: GroundSurface = flatGround,
        track: WorldTrack = PreviewSupport.curvyTrack(),
        quality: IndoorQuality = IndoorQuality.MEDIUM,
        textures: TextureCache = TextureCache(),
        aerial: RgbaImage? = null,
    ): TileContext = TileContext(
        spec, PreviewSupport.origin, quality, ground.heightAt(spec.anchorE, spec.anchorN), ground, TrackLine(track), map,
        object : LandMap {
            override fun classAt(e: Double, n: Double) = LandClass.RESIDENTIAL
        },
        textures, TileBudget.of(quality, spec.level), aerial,
    )

    /** Rechteck `w × h` um (`ce`, `cn`), um [angleDeg] gedreht. */
    fun rect(ce: Double, cn: Double, w: Double, h: Double, angleDeg: Double = 0.0): DoubleArray =
        BuildingsVillage.place(doubleArrayOf(-w / 2, -h / 2, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2), ce, cn, Math.toRadians(angleDeg))

    fun building(ring: DoubleArray, heightM: Double = 0.0, vararg holes: DoubleArray, id: Long = 1): MapBuilding =
        MapBuilding(id, listOf(ring) + holes.toList(), heightM)

    /** Ein Dreieck in Kachelkoordinaten (glTF: x Ost, y oben, z −Nord). */
    class Tri(val p: DoubleArray) {
        val nx: Double
        val ny: Double
        val nz: Double
        val area2: Double

        init {
            val ux = p[3] - p[0]
            val uy = p[4] - p[1]
            val uz = p[5] - p[2]
            val vx = p[6] - p[0]
            val vy = p[7] - p[1]
            val vz = p[8] - p[2]
            nx = uy * vz - uz * vy
            ny = uz * vx - ux * vz
            nz = ux * vy - uy * vx
            area2 = sqrt(nx * nx + ny * ny + nz * nz)
        }
    }

    fun triangles(vararg meshes: MeshBuilder): List<Tri> {
        val out = ArrayList<Tri>()
        for (m in meshes) {
            for (t in 0 until m.triangleCount) {
                val p = DoubleArray(9)
                for (c in 0 until 3) {
                    val i = m.indices[3 * t + c]
                    p[3 * c] = m.x(i).toDouble()
                    p[3 * c + 1] = m.y(i).toDouble()
                    p[3 * c + 2] = m.z(i).toDouble()
                }
                out.add(Tri(p))
            }
        }
        return out
    }

    private fun key(x: Double, y: Double, z: Double): String = "${(x * 500).roundToLong()}/${(y * 500).roundToLong()}/${(z * 500).roundToLong()}"

    /**
     * Kanten, die nicht von genau zwei Dreiecken in gegenlaeufiger Richtung
     * geteilt werden. Eckpunkte werden auf 2 mm verschweisst; Kanten, die ganz
     * auf oder unter [openBelowY] liegen, duerfen offen sein (Unterkante im Boden).
     */
    fun openEdges(tris: List<Tri>, openBelowY: Double = Double.NEGATIVE_INFINITY): List<String> {
        val count = HashMap<String, IntArray>()
        val lowest = HashMap<String, Double>()
        for (t in tris) {
            if (t.area2 < 1e-9) continue
            for (c in 0 until 3) {
                val a = 3 * c
                val b = 3 * ((c + 1) % 3)
                val ka = key(t.p[a], t.p[a + 1], t.p[a + 2])
                val kb = key(t.p[b], t.p[b + 1], t.p[b + 2])
                if (ka == kb) continue
                val fwd = ka < kb
                val k = if (fwd) "$ka>$kb" else "$kb>$ka"
                count.getOrPut(k) { IntArray(2) }[if (fwd) 0 else 1]++
                lowest[k] = maxOf(t.p[a + 1], t.p[b + 1])
            }
        }
        return count.filter { (k, v) -> !(v[0] == 1 && v[1] == 1) && lowest[k]!! > openBelowY + 1e-3 }.map { "${it.key} ${it.value[0]}/${it.value[1]}" }
    }

    /**
     * Schiesst Strahlen von aussen (oberhalb von [fromY]) auf die Netze und
     * zaehlt die, die zuerst eine **Rueckseite** treffen — das waere ein Loch
     * oder eine nach innen gestuelpte Flaeche. Rueckseiten, hinter denen
     * innerhalb von 8 cm eine Vorderseite liegt (aufgesetzte Tueren), zaehlen nicht.
     */
    fun backFaceHits(tris: List<Tri>, fromY: Double, rays: Int = 2500, seed: Int = 7): Int {
        if (tris.isEmpty()) return 0
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (t in tris) for (c in 0 until 3) {
            minX = minOf(minX, t.p[3 * c]); maxX = maxOf(maxX, t.p[3 * c])
            minY = minOf(minY, t.p[3 * c + 1]); maxY = maxOf(maxY, t.p[3 * c + 1])
            minZ = minOf(minZ, t.p[3 * c + 2]); maxZ = maxOf(maxZ, t.p[3 * c + 2])
        }
        val cx = (minX + maxX) / 2
        val cz = (minZ + maxZ) / 2
        val radius = maxOf(maxX - minX, maxZ - minZ) + 25.0
        var bad = 0
        for (r in 0 until rays) {
            val az = Noise.hash01(r, 1, seed) * 2 * Math.PI
            val el = Noise.hash01(r, 2, seed) * 1.35
            val ox = cx + radius * cos(az) * cos(el)
            val oz = cz + radius * sin(az) * cos(el)
            val oy = fromY + 1.2 + radius * sin(el)
            val tx = minX + (maxX - minX) * Noise.hash01(r, 3, seed)
            val ty = maxOf(fromY, minY) + (maxY - maxOf(fromY, minY)) * Noise.hash01(r, 4, seed)
            val tz = minZ + (maxZ - minZ) * Noise.hash01(r, 5, seed)
            var dx = tx - ox
            var dy = ty - oy
            var dz = tz - oz
            val l = sqrt(dx * dx + dy * dy + dz * dz)
            dx /= l; dy /= l; dz /= l
            var bestBack = Double.MAX_VALUE
            var bestFront = Double.MAX_VALUE
            for (t in tris) {
                if (t.area2 < 1e-9) continue
                val hit = intersect(t, ox, oy, oz, dx, dy, dz)
                if (hit.isNaN()) continue
                val facing = t.nx * dx + t.ny * dy + t.nz * dz
                if (facing > 0) {
                    if (hit < bestBack) bestBack = hit
                } else if (hit < bestFront) {
                    bestFront = hit
                }
            }
            if (bestBack < bestFront - 0.08) bad++
        }
        return bad
    }

    /** Moeller–Trumbore; Entfernung des Treffers oder `NaN`. */
    private fun intersect(t: Tri, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double {
        val p = t.p
        val e1x = p[3] - p[0]
        val e1y = p[4] - p[1]
        val e1z = p[5] - p[2]
        val e2x = p[6] - p[0]
        val e2y = p[7] - p[1]
        val e2z = p[8] - p[2]
        val hx = dy * e2z - dz * e2y
        val hy = dz * e2x - dx * e2z
        val hz = dx * e2y - dy * e2x
        val a = e1x * hx + e1y * hy + e1z * hz
        if (abs(a) < 1e-12) return Double.NaN
        val f = 1.0 / a
        val sx = ox - p[0]
        val sy = oy - p[1]
        val sz = oz - p[2]
        val u = f * (sx * hx + sy * hy + sz * hz)
        if (u < -1e-7 || u > 1 + 1e-7) return Double.NaN
        val qx = sy * e1z - sz * e1y
        val qy = sz * e1x - sx * e1z
        val qz = sx * e1y - sy * e1x
        val v = f * (dx * qx + dy * qy + dz * qz)
        if (v < -1e-7 || u + v > 1 + 1e-7) return Double.NaN
        val dist = f * (e2x * qx + e2y * qy + e2z * qz)
        return if (dist > 1e-6) dist else Double.NaN
    }
}
