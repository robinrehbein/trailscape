package de.trailscape.core.indoor.world

import de.trailscape.core.requiredDouble
import de.trailscape.core.requiredInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * # Streckenband: feineres Luftbild nah an der Fahrlinie
 *
 * Heute liegt in Stufe 0 je 256-m-Kachel **ein** Luftbild mit 1024 px (25 cm je
 * Pixel). Das Band legt in einem schmalen Streifen um die Fahrlinie ein feineres
 * Luftbild darueber: Die Kachel wird gedanklich in Felder von [AerialBandOptions.cellM]
 * (Vorgabe 32 m) geteilt; jedes Feld, das hoechstens [AerialBandOptions.bandM]
 * (Vorgabe 20 m) von der Fahrlinie entfernt ist, bekommt ein eigenes Luftbild mit
 * [AerialBandOptions.px] (Vorgabe 256 px = 12,5 cm) vom selben Dienst wie die Kachel.
 *
 * **Gelaende:** Das fertige Gelaendenetz der Kachel ([TerrainLayer] bzw.
 * [LaserTerrainLayer]) wird nicht neu gerechnet. Dreiecke, deren drei Ecken in einem
 * Bandfeld liegen, wandern in ein eigenes Netz (gleicher Knoten [WorldNames.NODE_TERRAIN],
 * Material `x_mat_terrain_band_<n>_<i>_<r>`, UV ueber das Feld); alle anderen bleiben
 * beim Kachel-Luftbild. Die Eckpunkte bleiben dieselben — keine neuen Kanten, keine Risse.
 * Dreiecke ueber einer Feldgrenze (grobe Dreiecke des Laser-Netzes) behalten das
 * Kachel-Luftbild.
 *
 * **Material:** `n` = Felder je Kachelkante, `i` = Spalte nach Osten, `r` = Zeile von
 * Norden. Daraus rechnet die Darstellung (`terrain_band.mat`) die Kachel-UV fuer das
 * Boden-Detail zurueck, damit das Detail nahtlos ueber Band und Kachel laeuft. Eine
 * Darstellung, die den Namen nicht kennt, behandelt ihn wie jedes `x_`-Material
 * (Ubershader mit dem feineren Bild) — kein Absturz, nur ohne Detail.
 *
 * **Bild:** leicht geschaerft (unscharfe Maske, der Dienst tastet 20 cm auf 12,5 cm
 * hoch), im Wald ([ForestFloor]) die abgetoente Farbe der Kachel uebernommen, damit an
 * den Feldgrenzen keine Farbkante entsteht. Kodiert als Baseline-JPEG.
 *
 * Nur fuer den Bau auf dem Server und im Katalog ([createWorldBuilder] mit Optionen):
 * Das Geraet fragt keine zusaetzlichen Bilder an.
 */
internal object AerialBand {
    const val MATERIAL_PREFIX = "x_mat_terrain_band_"

    fun materialName(perSide: Int, col: Int, rowFromNorth: Int): String = "$MATERIAL_PREFIX${perSide}_${col}_$rowFromNorth"

    /** Ein Bandfeld: Spalte [i] nach Osten, Zeile [j] nach Norden, Abstand [distM] zur Fahrlinie. */
    class Cell(val i: Int, val j: Int, val distM: Double)

    /**
     * Die Felder der Kachel [spec] (Kante [cellM]) mit Abstand ≤ [bandM] zur Fahrlinie,
     * die naechsten zuerst, hoechstens [limit].
     */
    fun cellsNear(spec: TileSpec, track: WorldTrack, bandM: Double, cellM: Double, limit: Int): List<Cell> {
        val n = (spec.cellM / cellM).toInt()
        if (n <= 0 || bandM <= 0.0) return emptyList()
        val best = DoubleArray(n * n) { Double.POSITIVE_INFINITY }
        val cnt = track.sampleCount
        val reach = bandM + cellM
        for (k in 0 until cnt - 1) {
            val ax = track.e[k].toDouble(); val ay = track.n[k].toDouble()
            val bx = track.e[k + 1].toDouble(); val by = track.n[k + 1].toDouble()
            if (max(ax, bx) < spec.minE - reach || min(ax, bx) > spec.maxE + reach) continue
            if (max(ay, by) < spec.minN - reach || min(ay, by) > spec.maxN + reach) continue
            val i0 = floor((min(ax, bx) - bandM - spec.minE) / cellM).toInt().coerceIn(0, n - 1)
            val i1 = floor((max(ax, bx) + bandM - spec.minE) / cellM).toInt().coerceIn(0, n - 1)
            val j0 = floor((min(ay, by) - bandM - spec.minN) / cellM).toInt().coerceIn(0, n - 1)
            val j1 = floor((max(ay, by) + bandM - spec.minN) / cellM).toInt().coerceIn(0, n - 1)
            for (i in i0..i1) for (j in j0..j1) {
                val x0 = spec.minE + i * cellM
                val y0 = spec.minN + j * cellM
                val d = segmentRectDistance(ax, ay, bx, by, x0, y0, x0 + cellM, y0 + cellM)
                if (d < best[j * n + i]) best[j * n + i] = d
            }
        }
        val out = ArrayList<Cell>()
        for (j in 0 until n) for (i in 0 until n) {
            val d = best[j * n + i]
            if (d <= bandM) out += Cell(i, j, d)
        }
        out.sortWith(compareBy<Cell> { it.distM }.thenBy { it.j }.thenBy { it.i })
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /**
     * Legt das Band ueber das Gelaende der Kachel in [out] (nur Stufe 0 mit Luftbild).
     * [fetch] holt das Bild eines ENU-Rechtecks in `px × px` (oder `null`).
     *
     * @return Zahl der Bandfelder mit eigenem Bild.
     */
    fun apply(ctx: TileContext, out: TileOutput, options: AerialBandOptions, fetch: (Double, Double, Double, Double) -> RgbaImage?): Int {
        val spec = ctx.spec
        if (spec.level != 0 || ctx.aerialTexture == null) return 0
        // LOW hat 128 MB Texturbudget und 512-px-Kacheln: dort kein Band (Grafikspeicher).
        if (ctx.quality == IndoorQuality.LOW) return 0
        val base = out.level0Mesh(WorldNames.NODE_TERRAIN, WorldNames.MAT_TERRAIN) ?: return 0
        val perSide = (spec.cellM / options.cellM).toInt()
        val cells = cellsNear(spec, ctx.track.track, options.bandM, options.cellM, options.maxCellsPerTile)
        if (cells.isEmpty()) return 0
        val textures = arrayOfNulls<EncodedTexture>(perSide * perSide)
        for (c in cells) {
            val minE = spec.minE + c.i * options.cellM
            val minN = spec.minN + c.j * options.cellM
            val img = fetch(minE, minN, minE + options.cellM, minN + options.cellM) ?: continue
            if (img.width != options.px || img.height != options.px) continue
            val prepared = prepare(img, ctx, minE, minN + options.cellM, options)
            textures[c.j * perSide + c.i] = EncodedTexture(encodeJpegBaseline(prepared, options.jpegQuality), "image/jpeg", options.px, options.px)
        }
        if (textures.all { it == null }) return 0
        return split(spec, out, base, options, perSide, textures)
    }

    /** Teilt [base] auf: Dreiecke ganz in einem Feld mit Bild gehen in dessen Netz. */
    fun split(
        spec: TileSpec,
        out: TileOutput,
        base: MeshBuilder,
        options: AerialBandOptions,
        perSide: Int,
        textures: Array<EncodedTexture?>,
    ): Int {
        val cellM = options.cellM
        val eps = 1e-3
        val rest = MeshBuilder(base.vertexCount, base.triangleCount)
        val restIndex = IntArray(base.vertexCount) { -1 }
        val band = HashMap<Int, Pair<MeshBuilder, HashMap<Int, Int>>>()
        val inset = 0.5 / options.px
        val scale = 1.0 - 2 * inset
        fun copy(dst: MeshBuilder, v: Int, u: Double, w: Double): Int {
            val p = v * 3
            return dst.vertex(
                base.positions[p].toDouble(), base.positions[p + 1].toDouble(), base.positions[p + 2].toDouble(),
                base.normals[p].toDouble(), base.normals[p + 1].toDouble(), base.normals[p + 2].toDouble(),
                u, w, base.colors[v],
            )
        }
        fun restVertex(v: Int): Int {
            if (restIndex[v] < 0) restIndex[v] = copy(rest, v, base.uvs[v * 2].toDouble(), base.uvs[v * 2 + 1].toDouble())
            return restIndex[v]
        }
        val idx = base.indices
        val used = HashSet<Int>()
        for (t in 0 until base.triangleCount) {
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            val ea = base.x(a) + spec.anchorE; val na = spec.anchorN - base.z(a)
            val eb = base.x(b) + spec.anchorE; val nb = spec.anchorN - base.z(b)
            val ec = base.x(c) + spec.anchorE; val nc = spec.anchorN - base.z(c)
            val ci = floor(((ea + eb + ec) / 3 - spec.minE) / cellM).toInt()
            val cj = floor(((na + nb + nc) / 3 - spec.minN) / cellM).toInt()
            val tex = if (ci in 0 until perSide && cj in 0 until perSide) textures[cj * perSide + ci] else null
            val x0 = spec.minE + ci * cellM
            val y0 = spec.minN + cj * cellM
            fun inside(e: Double, n: Double) = e >= x0 - eps && e <= x0 + cellM + eps && n >= y0 - eps && n <= y0 + cellM + eps
            if (tex == null || !inside(ea, na) || !inside(eb, nb) || !inside(ec, nc)) {
                rest.tri(restVertex(a), restVertex(b), restVertex(c))
                continue
            }
            val key = cj * perSide + ci
            used += key
            val (mesh, map) = band.getOrPut(key) {
                val row = perSide - 1 - cj
                val mat = TileMaterial(AerialBand.materialName(perSide, ci, row), texture = tex, repeat = false, roughness = 0.95f)
                out.mesh(WorldNames.NODE_TERRAIN, mat, x0 + cellM / 2, y0 + cellM / 2) to HashMap()
            }
            fun bandVertex(v: Int, e: Double, n: Double): Int = map.getOrPut(v) {
                val u = inset + ((e - x0) / cellM).coerceIn(0.0, 1.0) * scale
                val w = inset + ((y0 + cellM - n) / cellM).coerceIn(0.0, 1.0) * scale
                copy(mesh, v, u, w)
            }
            mesh.tri(bandVertex(a, ea, na), bandVertex(b, eb, nb), bandVertex(c, ec, nc))
        }
        if (used.isEmpty()) return 0
        out.replaceLevel0Mesh(WorldNames.NODE_TERRAIN, WorldNames.MAT_TERRAIN, rest)
        return used.size
    }

    /**
     * Schaerft [img] (Feld mit Nordwestecke `e0`, `n1`) und uebernimmt dort, wo die Kachel
     * abgetoent wurde ([ForestFloor]: [TileContext.aerial] ≠ [TileContext.aerialRaw]),
     * die abgetoente Farbe der Kachel.
     */
    fun prepare(img: RgbaImage, ctx: TileContext, e0: Double, n1: Double, options: AerialBandOptions): RgbaImage {
        val w = img.width
        val h = img.height
        val sharp = if (options.sharpen > 0.0) unsharp(img, options.sharpen) else RgbaImage(w, h, img.argb.copyOf())
        val toned = ctx.aerial
        val raw = ctx.aerialRaw
        if (toned == null || raw == null || toned === raw) return sharp
        val spec = ctx.spec
        val mpp = options.cellM / w
        for (y in 0 until h) for (x in 0 until w) {
            val e = e0 + (x + 0.5) * mpp
            val n = n1 - (y + 0.5) * mpp
            val fx = (e - spec.minE) / spec.cellM * raw.width - 0.5
            val fy = (spec.maxN - n) / spec.cellM * raw.height - 0.5
            val t = bilinear(toned, fx, fy)
            val r = bilinear(raw, fx, fy)
            val d = Math.abs(Argb.red(t) - Argb.red(r)) + Math.abs(Argb.green(t) - Argb.green(r)) + Math.abs(Argb.blue(t) - Argb.blue(r))
            val k = smooth(TONE_FROM, TONE_TO, d.toDouble())
            if (k <= 0.0) continue
            val s = sharp[x, y]
            sharp[x, y] = Argb.rgb(mix(Argb.red(s), Argb.red(t), k), mix(Argb.green(s), Argb.green(t), k), mix(Argb.blue(s), Argb.blue(t), k))
        }
        return sharp
    }

    /** Unscharfe Maske mit 3 × 3-Binomialfilter: `p + amount · (p − blur)`, je Kanal geklemmt. */
    fun unsharp(img: RgbaImage, amount: Double): RgbaImage {
        val w = img.width
        val h = img.height
        val out = RgbaImage(w, h)
        val k = intArrayOf(1, 2, 1)
        for (y in 0 until h) for (x in 0 until w) {
            var br = 0; var bg = 0; var bb = 0
            for (dy in -1..1) for (dx in -1..1) {
                val c = img[(x + dx).coerceIn(0, w - 1), (y + dy).coerceIn(0, h - 1)]
                val f = k[dx + 1] * k[dy + 1]
                br += Argb.red(c) * f; bg += Argb.green(c) * f; bb += Argb.blue(c) * f
            }
            val c = img[x, y]
            fun ch(v: Int, blur: Int) = (v + amount * (v - blur / 16.0)).toInt().coerceIn(0, 255)
            out[x, y] = Argb.rgb(ch(Argb.red(c), br), ch(Argb.green(c), bg), ch(Argb.blue(c), bb))
        }
        return out
    }

    private fun bilinear(img: RgbaImage, fx: Double, fy: Double): Int {
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        fun px(x: Int, y: Int) = img[x.coerceIn(0, img.width - 1), y.coerceIn(0, img.height - 1)]
        val a = px(x0, y0); val b = px(x0 + 1, y0); val c = px(x0, y0 + 1); val d = px(x0 + 1, y0 + 1)
        fun ch(sh: Int): Int {
            fun v(p: Int) = ((p ushr sh) and 0xFF).toDouble()
            val top = v(a) + (v(b) - v(a)) * tx
            val bot = v(c) + (v(d) - v(c)) * tx
            return (top + (bot - top) * ty + 0.5).toInt().coerceIn(0, 255)
        }
        return Argb.rgb(ch(16), ch(8), ch(0))
    }

    private fun mix(a: Int, b: Int, k: Double): Int = (a + (b - a) * k + 0.5).toInt().coerceIn(0, 255)

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Farbabstand (Summe der Kanaele) zwischen abgetoenter und roher Kachel: ab hier Waldboden, bis hier voll. */
    private const val TONE_FROM = 4.0
    private const val TONE_TO = 20.0

    /** Abstand der Strecke a–b zum Rechteck (0, wenn sie es beruehrt). */
    fun segmentRectDistance(ax: Double, ay: Double, bx: Double, by: Double, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        fun inRect(x: Double, y: Double) = x in x0..x1 && y in y0..y1
        if (inRect(ax, ay) || inRect(bx, by)) return 0.0
        var best = min(pointRect(ax, ay, x0, y0, x1, y1), pointRect(bx, by, x0, y0, x1, y1))
        val corners = arrayOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y0), doubleArrayOf(x1, y1), doubleArrayOf(x0, y1))
        for (c in corners) best = min(best, pointSegment(c[0], c[1], ax, ay, bx, by))
        // Schnitt mit einer Rechteckkante → 0
        for (k in 0 until 4) {
            val p = corners[k]
            val q = corners[(k + 1) % 4]
            if (segmentsIntersect(ax, ay, bx, by, p[0], p[1], q[0], q[1])) return 0.0
        }
        return best
    }

    private fun pointRect(x: Double, y: Double, x0: Double, y0: Double, x1: Double, y1: Double): Double =
        hypot(max(0.0, max(x0 - x, x - x1)), max(0.0, max(y0 - y, y - y1)))

    private fun pointSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-12) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun segmentsIntersect(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        fun cross(ox: Double, oy: Double, px: Double, py: Double, qx: Double, qy: Double) = (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }
}

/**
 * Holt die Bilder der Bandfelder vom selben Dienst wie das Kachel-Luftbild (dieselbe
 * Positivliste, derselbe Cache, dieselbe Leerbild-Erkennung wie [AerialStore]).
 */
internal class AerialBandStore(
    private val fetcher: GeoFetcher,
    private val decoder: RasterDecoder,
    private val origin: GeoOrigin,
    private val sources: List<AerialSource>,
    val options: AerialBandOptions,
) {
    /** Bild des Rechtecks in `px × px`; der Dienst der Kachel ([preferred]) wird zuerst gefragt. */
    fun fetch(preferred: AerialSource?, minE: Double, minN: Double, maxE: Double, maxN: Double): RgbaImage? {
        val order = if (preferred != null && preferred in sources) listOf(preferred) + (sources - preferred) else sources
        val img = fetchAerial(fetcher, decoder, origin, minE, minN, maxE, maxN, options.px, order) ?: return null
        if (img.width != options.px || img.height != options.px) return null
        val raster = decoder.decode(img.bytes) ?: return null
        return RgbaImage.of(raster)
    }
}

/**
 * Paketqualitaet des Luftbilds: Stufe des Streckenbands. Fuer Katalog- und Server-Bau
 * waehlbar (`--aerial-band`, `request.json` → `aerialBand`).
 */
enum class AerialBandLevel(val id: String, val options: AerialBandOptions?) {
    /** Wie bisher: nur das Kachel-Luftbild (25 cm). */
    OFF("aus", null),

    /** ±20 m um die Fahrlinie, 12,5 cm je Pixel. */
    BAND("band", AerialBandOptions()),

    /** ±30 m um die Fahrlinie, 12,5 cm je Pixel (breiter, mehr Grafikspeicher). */
    WIDE("band-breit", AerialBandOptions(bandM = 30.0, maxCellsPerTile = 32)),
    ;

    companion object {
        fun fromId(id: String?): AerialBandLevel? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * Masse des Streckenbands.
 *
 * @property bandM Abstand zur Fahrlinie, bis zu dem ein Feld das feinere Bild bekommt.
 * @property cellM Feldkante; muss 256 m ganzzahlig teilen.
 * @property px Bildkante eines Felds (Zweierpotenz); `cellM / px` = Bodenaufloesung.
 * @property sharpen Staerke der unscharfen Maske (0 = aus).
 * @property maxCellsPerTile Obergrenze je Kachel (Grafikspeicher): die naechsten Felder zuerst.
 * @property jpegQuality Qualitaet der JPEG-Kodierung der Feldbilder.
 */
data class AerialBandOptions(
    val bandM: Double = 20.0,
    val cellM: Double = 32.0,
    val px: Int = 256,
    val sharpen: Double = 0.35,
    val maxCellsPerTile: Int = 24,
    val jpegQuality: Int = 90,
) {
    init {
        require(bandM > 0.0) { "bandM muss positiv sein" }
        require(cellM > 0.0 && WORLD_LEVEL0_CELL_M % cellM == 0.0) { "cellM muss 256 m ganzzahlig teilen" }
        require(px in 16..2048 && (px and (px - 1)) == 0) { "px muss eine Zweierpotenz bis 2048 sein" }
        require(maxCellsPerTile > 0) { "maxCellsPerTile muss positiv sein" }
    }

    /** Bodenaufloesung in Metern je Pixel. */
    val metersPerPixel: Double get() = cellM / px

    /** Grafikspeicher eines Feldbilds (RGBA8 samt Mipmaps, wie gltfio es hochlaedt). */
    val cellGpuBytes: Long get() = textureGpuBytes(px)
}

/**
 * Optionales Manifestfeld `aerialBand`: Masse des Streckenbands dieser Welt. Fehlt es,
 * hat die Welt kein Band (aeltere Pakete, Bau auf dem Geraet).
 */
data class WorldAerialBand(val bandM: Double, val cellM: Double, val texturePx: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("bandM", bandM)
        put("cellM", cellM)
        put("texturePx", texturePx)
    }

    companion object {
        fun fromJson(o: JsonObject): WorldAerialBand? = runCatching {
            WorldAerialBand(o.requiredDouble("bandM"), o.requiredDouble("cellM"), o.requiredInt("texturePx"))
        }.getOrNull()
    }
}

/**
 * Weltbau fuer Katalog und Server: wie [createWorldBuilder] bzw. [createLaserWorldBuilder],
 * dazu wahlweise das Streckenband ([aerialBand], `null` = aus).
 */
fun createWorldBuilder(laser: LaserOptions?, aerialBand: AerialBandOptions?): WorldBuilder =
    WorldBuilderImpl(FullWorldParts, laser = laser, aerialBand = aerialBand)
