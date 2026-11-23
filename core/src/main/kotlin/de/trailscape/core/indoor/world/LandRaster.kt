package de.trailscape.core.indoor.world

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Landnutzung als Raster
 *
 * [LandRaster] rastert die Flaechen der Kartendaten ([MapData.areas]) in ein
 * Klassenraster (ein Byte je Zelle, Bloecke von [blockCells]² Zellen, erst bei
 * Bedarf gebaut). Strassen und Gebaeude kommen als Zusatzmasken hinzu.
 * [classAt] kostet eine Tabellenabfrage.
 *
 * Ueberlappen Flaechen, gewinnt die Klasse mit hoeherem Rang:
 * Wasser > Wald > Feuchtgebiet > Gebuesch > Park > Gewerbe > Wohngebiet >
 * Fels/Sand > Acker > Wiese. (Wald und Parks liegen in OSM oft innerhalb von
 * Wohngebieten; das Kleinere, Genauere soll sichtbar sein.)
 *
 * Byte je Zelle: Bits 0–3 Klasse (`ordinal + 1`, 0 = nichts bekannt → Wiese),
 * Bit 4 Strasse, Bit 5 Gebaeude, Bits 6–7 Blattart aus den Daten
 * (0 unbekannt, 1 Laub, 2 Nadel, 3 Misch).
 */
internal class LandRaster(
    private val map: MapData,
    /** Zellenkante in Metern. */
    val cellM: Double = 2.0,
    private val blockCells: Int = 128,
) : LandMap, WaterMask {
    private val blockM = cellM * blockCells
    private val blocks = ConcurrentHashMap<Long, ByteArray>()
    private val canopy = ConcurrentHashMap<TileSpec, CanopyGrid>()

    private class Last(var key: Long = Long.MIN_VALUE, var data: ByteArray? = null)

    private val last = ThreadLocal.withInitial { Last() }

    /** Rohes Byte der Zelle an (`e`, `n`). */
    fun cellAt(e: Double, n: Double): Int {
        val cx = floor(e / cellM).toInt()
        val cy = floor(n / cellM).toInt()
        return cellByIndex(cx, cy)
    }

    private fun cellByIndex(cx: Int, cy: Int): Int {
        val bx = Math.floorDiv(cx, blockCells)
        val by = Math.floorDiv(cy, blockCells)
        val key = (bx.toLong() shl 32) xor (by.toLong() and 0xffffffffL)
        val l = last.get()
        val data = if (l.key == key) {
            l.data!!
        } else {
            val d = blocks.computeIfAbsent(key) { build(bx, by) }
            l.key = key
            l.data = d
            d
        }
        val ix = cx - bx * blockCells
        val iy = cy - by * blockCells
        return data[iy * blockCells + ix].toInt() and 0xFF
    }

    override fun classAt(e: Double, n: Double): LandClass = classOf(cellAt(e, n))

    /** Liegt die Stelle auf einer Strasse oder einem Weg (Zelle, nach Klassenbreite)? */
    fun isRoad(e: Double, n: Double): Boolean = cellAt(e, n) and ROAD_BIT != 0

    /** Liegt die Stelle in einem Gebaeudegrundriss (Zelle)? */
    fun isBuilding(e: Double, n: Double): Boolean = cellAt(e, n) and BUILDING_BIT != 0

    override fun coniferShare(e: Double, n: Double): Double {
        when ((cellAt(e, n) shr 6) and 3) {
            1 -> return 0.08
            2 -> return 0.92
            3 -> return 0.5
        }
        // Ohne Angabe: Mischwald-Inseln aus niederfrequentem Rauschen (Bestaende von einigen hundert Metern).
        val f = Noise.fbm(e / 420.0, n / 420.0, SEED_CONIFER, 3)
        return 0.05 + 0.85 * smoothstep(-0.2, 0.3, f)
    }

    override fun canopyAt(e: Double, n: Double): Double {
        val spec = TileSpec.at(0, e, n)
        val g = canopy[spec]
        if (g != null) return g.at((e - spec.minE) / spec.cellM, (spec.maxN - n) / spec.cellM)
        return when (classAt(e, n)) {
            LandClass.FOREST -> 1.0
            LandClass.SCRUB -> 0.3
            LandClass.PARK -> 0.12
            LandClass.RESIDENTIAL -> 0.08
            LandClass.WETLAND -> 0.08
            else -> 0.0
        }
    }

    /**
     * Haengt die aus dem Luftbild geschaetzte Baumkronen-Bedeckung
     * ([canopyFromAerial]) einer Kachel der **Stufe 0** ein; [canopyAt] nutzt
     * sie dann statt des Klassenwerts.
     */
    fun setCanopy(spec: TileSpec, grid: CanopyGrid) {
        require(spec.level == 0) { "Kronenraster nur fuer Kacheln der Stufe 0" }
        canopy[spec] = grid
    }

    /** Wasseranteil, bilinear zwischen den Zellmitten — stetig, fuer [CarvedGround]. */
    override fun waterAt(e: Double, n: Double): Double {
        val fx = e / cellM - 0.5
        val fy = n / cellM - 0.5
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        fun w(x: Int, y: Int): Double = if (cellByIndex(x, y) and CLASS_MASK == LandClass.WATER.ordinal + 1) 1.0 else 0.0
        val a = w(x0, y0)
        val b = w(x0 + 1, y0)
        val c = w(x0, y0 + 1)
        val d = w(x0 + 1, y0 + 1)
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    }

    private fun build(bx: Int, by: Int): ByteArray {
        val data = ByteArray(blockCells * blockCells)
        val minE = bx * blockM
        val minN = by * blockM
        val maxE = minE + blockM
        val maxN = minN + blockM
        for (a in map.areas(minE, minN, maxE, maxN)) {
            val code = a.kind.ordinal + 1
            val prio = PRIORITY[a.kind.ordinal]
            val leaf = leafHint(a.sourceClass)
            fillRings(a.rings, minE, minN) { idx ->
                val old = data[idx].toInt() and 0xFF
                val oldCode = old and CLASS_MASK
                val oldPrio = if (oldCode == 0) 0 else PRIORITY[oldCode - 1]
                if (prio > oldPrio) {
                    val leafBits = if (a.kind == LandClass.FOREST) leaf shl 6 else 0
                    data[idx] = ((old and (ROAD_BIT or BUILDING_BIT)) or code or leafBits).toByte()
                }
            }
        }
        for (b in map.buildings(minE, minN, maxE, maxN)) {
            fillRings(b.rings, minE, minN) { idx -> data[idx] = (data[idx].toInt() or BUILDING_BIT).toByte() }
        }
        for (r in map.roads(minE - 8, minN - 8, maxE + 8, maxN + 8)) {
            if (r.brunnel == "tunnel") continue
            val hw = CarvedGround.roadHalfWidthM(r.cls) ?: continue
            markLine(r.line, hw, minE, minN, data)
        }
        return data
    }

    /** Ruft [set] fuer jede Zelle des Blocks, deren Mitte in den Ringen liegt (gerade/ungerade). */
    private inline fun fillRings(rings: List<DoubleArray>, minE: Double, minN: Double, set: (Int) -> Unit) {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (r in rings) for (i in 1 until r.size step 2) {
            lo = min(lo, r[i])
            hi = max(hi, r[i])
        }
        val j0 = max(0, floor((lo - minN) / cellM - 0.5).toInt())
        val j1 = min(blockCells - 1, floor((hi - minN) / cellM - 0.5).toInt() + 1)
        var buf = DoubleArray(64)
        for (j in j0..j1) {
            val y = minN + (j + 0.5) * cellM
            var cnt = 0
            for (r in rings) {
                val m = r.size / 2
                var p = m - 1
                for (q in 0 until m) {
                    val yq = r[2 * q + 1]
                    val yp = r[2 * p + 1]
                    if ((yq > y) != (yp > y)) {
                        val x = r[2 * q] + (y - yq) / (yp - yq) * (r[2 * p] - r[2 * q])
                        if (cnt == buf.size) buf = buf.copyOf(cnt * 2)
                        buf[cnt++] = x
                    }
                    p = q
                }
            }
            if (cnt < 2) continue
            java.util.Arrays.sort(buf, 0, cnt)
            var k = 0
            while (k + 1 < cnt) {
                val i0 = max(0, kotlin.math.ceil((buf[k] - minE) / cellM - 0.5).toInt())
                val i1 = min(blockCells - 1, floor((buf[k + 1] - minE) / cellM - 0.5).toInt())
                for (i in i0..i1) set(j * blockCells + i)
                k += 2
            }
        }
    }

    private fun markLine(line: DoubleArray, hw: Double, minE: Double, minN: Double, data: ByteArray) {
        for (s in 0 until line.size / 2 - 1) {
            val ax = line[2 * s]
            val ay = line[2 * s + 1]
            val bx = line[2 * s + 2]
            val by = line[2 * s + 3]
            val i0 = max(0, floor((min(ax, bx) - hw - minE) / cellM).toInt())
            val i1 = min(blockCells - 1, floor((max(ax, bx) + hw - minE) / cellM).toInt())
            val j0 = max(0, floor((min(ay, by) - hw - minN) / cellM).toInt())
            val j1 = min(blockCells - 1, floor((max(ay, by) + hw - minN) / cellM).toInt())
            if (i0 > i1 || j0 > j1) continue
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            for (j in j0..j1) {
                val y = minN + (j + 0.5) * cellM
                for (i in i0..i1) {
                    val x = minE + (i + 0.5) * cellM
                    val t = if (len2 > 0) (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
                    val px = ax + dx * t - x
                    val py = ay + dy * t - y
                    if (px * px + py * py <= hw * hw) {
                        val idx = j * blockCells + i
                        data[idx] = (data[idx].toInt() or ROAD_BIT).toByte()
                    }
                }
            }
        }
    }

    companion object {
        const val CLASS_MASK = 0x0F
        const val ROAD_BIT = 0x10
        const val BUILDING_BIT = 0x20

        private const val SEED_CONIFER = 0x5c0f1e7

        /** Rang je [LandClass.ordinal] — hoeher gewinnt. */
        private val PRIORITY: IntArray = IntArray(LandClass.entries.size).also {
            it[LandClass.MEADOW.ordinal] = 1
            it[LandClass.FARMLAND.ordinal] = 2
            it[LandClass.BARE.ordinal] = 3
            it[LandClass.RESIDENTIAL.ordinal] = 4
            it[LandClass.INDUSTRIAL.ordinal] = 5
            it[LandClass.PARK.ordinal] = 6
            it[LandClass.SCRUB.ordinal] = 7
            it[LandClass.WETLAND.ordinal] = 8
            it[LandClass.FOREST.ordinal] = 9
            it[LandClass.WATER.ordinal] = 10
        }

        private val CLASSES = LandClass.entries.toTypedArray()

        fun classOf(cell: Int): LandClass {
            val c = cell and CLASS_MASK
            return if (c == 0 || c > CLASSES.size) LandClass.MEADOW else CLASSES[c - 1]
        }

        /** Blattart aus der Roh-Klasse/Unterklasse der Quelle (`needleleaved`, `broadleaved`, `mixed` …). */
        fun leafHint(source: String?): Int {
            val s = source?.lowercase() ?: return 0
            return when {
                "mixed" in s -> 3
                "needle" in s || "conifer" in s || "evergreen" in s -> 2
                "broad" in s || "decid" in s -> 1
                else -> 0
            }
        }
    }
}

/**
 * Baumkronen-Bedeckung einer Kachel, `size × size` Werte `0..255`, Norden
 * oben (Zeile 0 = Nordrand), aus dem Luftbild geschaetzt.
 */
internal class CanopyGrid(val size: Int, val values: ByteArray) {
    init {
        require(values.size == size * size) { "Kronenraster: ${values.size} Werte fuer $size²" }
    }

    /** Bilinear an `u` (0 = West … 1 = Ost), `v` (0 = Nord … 1 = Sued), in `0..1`. */
    fun at(u: Double, v: Double): Double {
        val fx = (u * size - 0.5).coerceIn(0.0, size - 1.0)
        val fy = (v * size - 0.5).coerceIn(0.0, size - 1.0)
        val x0 = fx.toInt().coerceAtMost(size - 1)
        val y0 = fy.toInt().coerceAtMost(size - 1)
        val x1 = min(x0 + 1, size - 1)
        val y1 = min(y0 + 1, size - 1)
        val tx = fx - x0
        val ty = fy - y0
        fun g(x: Int, y: Int) = (values[y * size + x].toInt() and 0xFF) / 255.0
        return (g(x0, y0) * (1 - tx) + g(x1, y0) * tx) * (1 - ty) + (g(x0, y1) * (1 - tx) + g(x1, y1) * tx) * ty
    }
}

/**
 * Schaetzt aus einem Luftbild (Norden oben), wo Baumkronen stehen: dunkles,
 * koerniges Gruen = Krone; helles, glattes Gruen = Wiese; helles Grau = Strasse
 * oder Dach. Je Rasterzelle (`img.width / size` Pixel) werden mittlere
 * Helligkeit, Gruenueberschuss und Koernung (Streuung der Helligkeit)
 * bewertet, das Ergebnis 3×3 geglaettet. Abgestimmt an NRW-Orthofotos
 * (Wald: Helligkeit 0,12–0,33; Wiese 0,47–0,57; Weg ≥ 0,7). Schlagschatten
 * von Baeumen auf Wiesen zaehlen teilweise mit — sie liegen neben Kronen.
 */
internal fun canopyFromAerial(img: RgbaImage, size: Int = 128): CanopyGrid {
    val bw = max(1, img.width / size)
    val bh = max(1, img.height / size)
    val raw = DoubleArray(size * size)
    for (gy in 0 until size) for (gx in 0 until size) {
        var sl = 0.0
        var sl2 = 0.0
        var sx = 0.0
        var cnt = 0
        val px0 = gx * img.width / size
        val py0 = gy * img.height / size
        for (y in py0 until min(img.height, py0 + bh)) for (x in px0 until min(img.width, px0 + bw)) {
            val c = img[x, y]
            val r = Argb.red(c) / 255.0
            val g = Argb.green(c) / 255.0
            val b = Argb.blue(c) / 255.0
            val l = 0.3 * r + 0.59 * g + 0.11 * b
            sl += l
            sl2 += l * l
            sx += (2 * g - r - b) / (r + g + b + 0.02)
            cnt++
        }
        if (cnt == 0) continue
        val mean = sl / cnt
        val sd = sqrt(max(0.0, sl2 / cnt - mean * mean))
        val exg = sx / cnt
        // Dunkel ist das staerkste Merkmal (Kronen und ihr Eigenschatten); Gruen und
        // Koernung heben Kronen gegen dunkle, glatte Flaechen (Asphalt, Wasser) ab.
        val dark = 1 - smoothstep(0.30, 0.45, mean)
        val green = smoothstep(-0.08, 0.04, exg)
        val grain = smoothstep(0.02, 0.06, sd)
        raw[gy * size + gx] = dark * (0.5 + 0.5 * green) * (0.6 + 0.4 * grain)
    }
    val out = ByteArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        var s = 0.0
        var c = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx
            val yy = y + dy
            if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue
            s += raw[yy * size + xx] * if (dx == 0 && dy == 0) 2.0 else 1.0
            c += if (dx == 0 && dy == 0) 2 else 1
        }
        out[y * size + x] = (smoothstep(0.15, 0.55, s / c) * 255 + 0.5).toInt().toByte()
    }
    return CanopyGrid(size, out)
}
