package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * # Erzeugte Bodentextur
 *
 * Fuer Kacheln ohne Luftbild (`WorldTile.aerial = false`): ein Bild der
 * Kachel, das aus der Drohne und aus der Ferne wie ein Luftbild einer
 * mitteleuropaeischen Kulturlandschaft wirkt — Wiesen fleckig und gemaeht,
 * Aecker als Schlaege mit eigener Saatrichtung und Farbe samt Fahrgassen,
 * Wald als koerniges Kronendach, Siedlung aus Gaerten, Hoefen und Daechern,
 * Strassen und Wege in ihrer Breite.
 *
 * **Nahtlos:** Jeder Pixel wird allein aus seinen Welt-Koordinaten gerechnet
 * (Rauschen und Voronoi-Schlaege mit festen Samen, Karteninhalt aus
 * unbeschnittenen Abfragen ueber den Kachelrand hinaus). Dieselbe Funktion
 * malt alle Stufen; Details unter etwa drei Pixeln werden ausgeblendet
 * (Bandbegrenzung), damit Stufe 1 und 2 dieselben mittleren Farben zeigen,
 * ohne zu flimmern.
 *
 * Farben: Albedo in sRGB nach der Farbigkeit der NRW-Orthofotos (leicht
 * entsaettigt und etwas dunkler, weil die Darstellung die Sonne dazurechnet:
 * Wiese um `(100, 120, 78)`, Wald um `(60, 78, 50)`, Asphalt um `(108, 108, 106)`),
 * keine gemalten Schlagschatten.
 *
 * @param track die Strecke — sie wird mit ihrem Belag eingezeichnet (wichtig
 *   fuer Stufe 1 und 2, wo es keine Fahrbahn-Geometrie gibt).
 */
internal fun groundTexture(spec: TileSpec, land: LandMap, map: MapData, track: TrackLine?, sizePx: Int): RgbaImage {
    val p = GroundPainter(spec, land, sizePx)
    p.paintBase()
    p.paintBuildings(map)
    p.paintWaterways(map)
    p.paintRoads(map)
    if (track != null) p.paintTrack(track)
    p.grain()
    return p.img
}

/** Farben der Strecke je Belag (sRGB). */
internal fun trackSurfaceColor(kind: SurfaceKind): Int = when (kind.visual) {
    SurfaceKind.ASPHALT -> Argb.rgb(122, 122, 119)
    SurfaceKind.CONCRETE -> Argb.rgb(158, 156, 150)
    SurfaceKind.PAVING_STONES -> Argb.rgb(146, 136, 124)
    SurfaceKind.COBBLESTONE -> Argb.rgb(124, 118, 110)
    SurfaceKind.WOOD -> Argb.rgb(128, 104, 76)
    SurfaceKind.COMPACTED -> Argb.rgb(174, 162, 136)
    SurfaceKind.GRAVEL -> Argb.rgb(162, 156, 144)
    SurfaceKind.DIRT -> Argb.rgb(136, 116, 88)
    SurfaceKind.GRASS -> Argb.rgb(122, 140, 86)
    SurfaceKind.SAND -> Argb.rgb(194, 178, 140)
    SurfaceKind.UNKNOWN -> Argb.rgb(174, 162, 136)
}

private class GroundPainter(val spec: TileSpec, val land: LandMap, val size: Int) {
    val img = RgbaImage(size, size)
    val mpp = spec.cellM / size

    /** Das Klassenraster, falls [land] eines ist — unterscheidet „Wiese" von „nichts bekannt". */
    private val raster = land as? LandRaster

    /** Feines Zittern der Klassengrenzen; unterhalb der Pixelgroesse ohne Wirkung und deshalb aus. */
    private val edgeJitter = if (mpp < 1.0) 2.4 else 0.0

    /** Gitter fuer niederfrequente Felder: etwa alle 2 m ein Stuetzpunkt. */
    private val latStep = max(1, (2.0 / mpp).toInt())
    private val latN = size / latStep + 2
    private val warpE = FloatArray(latN * latN)
    private val warpN = FloatArray(latN * latN)
    private val patch = FloatArray(latN * latN)
    private val patch2 = FloatArray(latN * latN)
    private val worley = DoubleArray(3)

    init {
        for (j in 0 until latN) for (i in 0 until latN) {
            val e = spec.minE + (i * latStep) * mpp
            val n = spec.maxN - (j * latStep) * mpp
            val k = j * latN + i
            warpE[k] = (2.6 * Noise.fbm(e / 22.0, n / 22.0, SEED_WARP, 3)).toFloat()
            warpN[k] = (2.6 * Noise.fbm(e / 22.0, n / 22.0, SEED_WARP + 77, 3)).toFloat()
            patch[k] = Noise.fbm(e / 26.0, n / 26.0, SEED_PATCH, 3).toFloat()
            patch2[k] = Noise.fbm(e / 160.0, n / 160.0, SEED_PATCH2, 2).toFloat()
        }
    }

    private fun lattice(f: FloatArray, x: Int, y: Int): Double {
        // Stuetzpunkte an Pixelecken (i·latStep); Pixelmitte liegt bei +0,5.
        val fx = (x + 0.5) / latStep
        val fy = (y + 0.5) / latStep
        val i = fx.toInt().coerceAtMost(latN - 2)
        val j = fy.toInt().coerceAtMost(latN - 2)
        val tx = fx - i
        val ty = fy - j
        val a = f[j * latN + i]
        val b = f[j * latN + i + 1]
        val c = f[(j + 1) * latN + i]
        val d = f[(j + 1) * latN + i + 1]
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    }

    /** Sichtbarkeit eines Musters der Wellenlaenge [lambdaM] (0 = unter drei Pixeln). */
    private fun bl(lambdaM: Double): Double = smoothstep(2.0, 4.0, lambdaM / mpp)

    fun eOf(x: Int) = spec.minE + (x + 0.5) * mpp
    fun nOf(y: Int) = spec.maxN - (y + 0.5) * mpp

    // -----------------------------------------------------------------------
    // Flaechen
    // -----------------------------------------------------------------------

    fun paintBase() {
        for (y in 0 until size) {
            val n = nOf(y)
            for (x in 0 until size) {
                val e = eOf(x)
                // Grob verbogen (Lattice) plus feines Zittern: Klassengrenzen werden
                // unregelmaessig statt treppig (das Klassenraster hat 2-m-Zellen).
                val jitter = edgeJitter * (Noise.value(e / 2.3, n / 2.3, SEED_JITTER) - 0.5)
                val we = e + lattice(warpE, x, y) + jitter
                val wn = n + lattice(warpN, x, y) - jitter
                val p1 = lattice(patch, x, y)
                val p2 = lattice(patch2, x, y)
                val c = when (land.classAt(we, wn)) {
                    LandClass.MEADOW -> if (raster != null && raster.cellAt(we, wn) and LandRaster.CLASS_MASK == 0) {
                        openLand(e, n, p1, p2)
                    } else {
                        meadow(e, n, p1, p2)
                    }
                    LandClass.FARMLAND -> farmland(e, n, p1, p2)
                    LandClass.FOREST -> forest(e, n, we, wn, p1)
                    LandClass.SCRUB -> scrub(e, n, p1, p2)
                    LandClass.RESIDENTIAL -> residential(e, n, p1, p2)
                    LandClass.INDUSTRIAL -> industrial(e, n, p1)
                    LandClass.PARK -> park(e, n, p1, p2)
                    LandClass.WATER -> Argb.scale(WATER, 1.0 + 0.04 * p1)
                    LandClass.WETLAND -> wetland(e, n, p1)
                    LandClass.BARE -> Argb.scale(BARE, 1.0 + 0.09 * p1 + 0.05 * p2)
                }
                img.argb[y * size + x] = c
            }
        }
    }

    private fun meadow(e: Double, n: Double, p1: Double, p2: Double): Int {
        // Ton stetig aus niederfrequentem Rauschen: satte, gemaehte und trockene Partien.
        val t = smoothstep(-0.45, 0.55, 0.8 * p2 + 0.45 * p1)
        var c = if (t < 0.5) Argb.mix(MEADOW_LUSH, MEADOW_MOWN, t * 2) else Argb.mix(MEADOW_MOWN, MEADOW_DRY, t * 2 - 1)
        var f = 1.0 + 0.08 * p1
        // Grasbueschel und Kleinflecken (1–3 m), Bodenwellen/Feuchte (8 m).
        f += 0.06 * bl(2.0) * Noise.gradient(e / 1.6, n / 1.6, SEED_TUFT)
        f += 0.06 * bl(0.7) * (Noise.value(e / 0.45, n / 0.45, SEED_TUFT + 2) - 0.5)
        f += 0.04 * bl(8.0) * Noise.gradient(e / 7.0, n / 7.0, SEED_TUFT + 1)
        // Parzellen (Voronoi, 110 m), in einem Teil davon breite, schwache Maehbahnen.
        Noise.worley(e / 110.0, n / 110.0, SEED_PLOT, 0, worley)
        val id = worley[2]
        val inner = smoothstep(0.0, 3.0, (worley[1] - worley[0]) * 110.0 * 0.5)
        f += 0.025 * (id - 0.5) * inner
        if (id < 0.35) {
            val a = id * 37.0
            val proj = e * cos(a) + n * sin(a)
            f += 0.03 * bl(5.2) * inner * cos(2 * PI * proj / 5.2)
        }
        if (t > 0.75) c = Argb.mix(c, MEADOW_YELLOW, (t - 0.75) * 1.2)
        return Argb.scale(c, f)
    }

    /**
     * Offenes Land ohne Kartenangabe (keine Flaeche in den Vektordaten, etwa in
     * Stufe 2): grossraeumig wechselnd Aecker und Gruenland — eine Kulturlandschaft
     * statt einer endlosen Wiese. Wald wird nicht erfunden.
     */
    private fun openLand(e: Double, n: Double, p1: Double, p2: Double): Int {
        // Je Schlag entschieden (gerade Grenzen); der Anteil Gruenland wechselt grossraeumig.
        val share = 0.35 + 0.35 * Noise.fbm(e / 1800.0, n / 1800.0, SEED_OPEN, 2)
        fieldCell(e, n)
        val h = Noise.hash01((fieldId * 1e6).toInt(), 3, SEED_OPEN)
        return if (h < share) meadow(e, n, p1, p2) else farmland(e, n, p1, p2)
    }

    /** Abstand zur Schlaggrenze in Metern, nach [fieldCell]. */
    private var fieldEdge = 0.0

    /** Kennzahl des Schlags, nach [fieldCell]. */
    private var fieldId = 0.0

    /**
     * Voronoi-Schlag an der Stelle: Zellpunkte auf einem gedrehten, gestreckten
     * Gitter (240 m × 90 m, gejittert), Abstand metrisch — so entstehen
     * laengliche Schlaege mit geraden Grenzen. Setzt [fieldId] und den exakten
     * Abstand zur naechsten Grenze [fieldEdge].
     */
    private fun fieldCell(e: Double, n: Double) {
        val ca = cos(FIELD_ANGLE)
        val sa = sin(FIELD_ANGLE)
        val x = e * ca + n * sa
        val y = -e * sa + n * ca
        val ix = floor(x / FIELD_W).toInt()
        val iy = floor(y / FIELD_H).toInt()
        var d1 = Double.MAX_VALUE
        var d2 = Double.MAX_VALUE
        var x1 = 0.0
        var y1 = 0.0
        var x2 = 0.0
        var y2 = 0.0
        var h1 = 0
        for (dy in -2..2) for (dx in -1..1) {
            val h = Noise.hash(ix + dx, iy + dy, SEED_FIELD)
            val sx = (ix + dx + 0.1 + 0.8 * ((h and 0xFFFF) / 65536.0)) * FIELD_W
            val sy = (iy + dy + 0.1 + 0.8 * (((h ushr 16) and 0xFFFF) / 65536.0)) * FIELD_H
            val d = (x - sx) * (x - sx) + (y - sy) * (y - sy)
            if (d < d1) {
                d2 = d1; x2 = x1; y2 = y1
                d1 = d; x1 = sx; y1 = sy; h1 = h
            } else if (d < d2) {
                d2 = d; x2 = sx; y2 = sy
            }
        }
        val sep = kotlin.math.hypot(x2 - x1, y2 - y1)
        fieldEdge = if (sep > 0) (d2 - d1) / (2 * sep) else Double.MAX_VALUE
        fieldId = (Noise.mix(h1) ushr 8) / 16777216.0
    }

    private fun farmland(e: Double, n: Double, p1: Double, p2: Double): Int {
        fieldCell(e, n)
        val margin = fieldEdge
        val id = fieldId
        val h2 = Noise.hash01((id * 1e6).toInt(), 7, SEED_FIELD)
        val base: Int
        val spacing: Double
        val rowAmp: Double
        when {
            id < 0.30 -> { base = CROP_GREEN; spacing = 0.75; rowAmp = 0.05 }
            id < 0.45 -> { base = CROP_RIPE; spacing = 0.6; rowAmp = 0.04 }
            id < 0.55 -> { base = SOIL; spacing = 1.4; rowAmp = 0.08 }
            id < 0.67 -> { base = STUBBLE; spacing = 0.5; rowAmp = 0.07 }
            id < 0.80 -> { base = MAIZE; spacing = 0.75; rowAmp = 0.08 }
            else -> { base = LEY; spacing = 0.0; rowAmp = 0.0 }
        }
        // Reihen laengs oder quer zum Schlag.
        val rowA = FIELD_ANGLE + if (h2 < 0.65) 0.0 else PI / 2
        val across = e * cos(rowA) + n * sin(rowA)
        var f = 1.0 + 0.07 * p1 + 0.05 * p2
        // Feuchte-/Bodenflecken im Schlag (grob und fein).
        f += 0.09 * Noise.gradient(e / 45.0 + id * 31, n / 45.0, SEED_SOIL)
        f += 0.035 * bl(6.0) * Noise.gradient(e / 6.0, n / 6.0, SEED_SOIL + 1)
        f += 0.03 * bl(1.2) * Noise.gradient(e / 1.1, n / 1.1, SEED_SOIL + 2)
        if (spacing > 0) f += rowAmp * bl(spacing) * cos(2 * PI * across / spacing)
        var c = Argb.scale(base, f)
        if (spacing > 0 && base != SOIL && base != STUBBLE) {
            // Fahrgassen alle 20 m: zwei Radspuren im Abstand 1,8 m.
            val m = ((across + id * 97.0) % 20.0 + 20.0) % 20.0 - 10.0
            val d = abs(abs(m) - 0.9)
            val t = (1 - smoothstep(0.12, 0.3, d)) * bl(1.2)
            c = Argb.mix(c, TRAMLINE, t * 0.55)
        }
        // Feldrain: schmaler Grasstreifen an der Schlaggrenze.
        val rain = (1 - smoothstep(0.5, 1.4, margin)) * bl(2.0)
        if (rain > 0) c = Argb.mix(c, Argb.scale(MEADOW_DRY, 0.95 + 0.1 * p1), rain)
        return c
    }

    /** Ergebnis von [crowns]: Kennzahl der naechsten Krone. */
    private var crownId = 0.0

    /**
     * Kronendach aus runden Kuppeln: naechster jitterter Zellpunkt (Zelle
     * [cell] Meter), Kronenradius je Baum 0,55–0,8 Zellen. Liefert die
     * Helligkeit der Stelle: Kuppel mit sanfter Lichtseite nach Suedwest
     * (dezent — die Darstellung rechnet die Sonne selbst), Luecken dunkel.
     * [crownId] haelt danach die Kennzahl des Baums.
     */
    private fun crowns(e: Double, n: Double, cell: Double, seed: Int): Double {
        val x = e / cell
        val y = n / cell
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        var best = Double.MAX_VALUE
        var bx = 0.0
        var by = 0.0
        var bh = 0
        for (dy in -1..1) for (dx in -1..1) {
            val h = Noise.hash(ix + dx, iy + dy, seed)
            val sx = ix + dx + 0.15 + 0.7 * ((h and 0xFFFF) / 65536.0)
            val sy = iy + dy + 0.15 + 0.7 * (((h ushr 16) and 0xFFFF) / 65536.0)
            val ox = x - sx
            val oy = y - sy
            val d2 = ox * ox + oy * oy
            if (d2 < best) {
                best = d2
                bx = ox
                by = oy
                bh = h
            }
        }
        val id = (Noise.mix(bh) ushr 8) / 16777216.0
        crownId = id
        val r = 0.55 + 0.25 * id
        val d = kotlin.math.sqrt(best) / r
        if (d >= 1.0) return 0.55
        val nz = kotlin.math.sqrt(1 - d * d)
        // Lichtseite: Versatz vom Kronenmittelpunkt Richtung Suedwest.
        val side = (-bx - by) * 0.7071 / r
        return 0.62 + 0.3 * nz + 0.14 * side
    }

    private fun crown(e: Double, n: Double, cell: Double, seed: Int): Double {
        val b = crowns(e, n, cell, seed)
        return ((b - 0.55) / 0.45).coerceIn(0.0, 1.0)
    }

    private fun forest(e: Double, n: Double, we: Double, wn: Double, p1: Double): Int {
        val cs = land.coniferShare(we, wn)
        val stand = 0.5 + 0.6 * Noise.fbm(e / 45.0, n / 45.0, SEED_STAND, 2)
        val conifer = stand < cs
        val cell = if (conifer) 3.2 else 5.2
        // Kronen leicht verbogen (unregelmaessige Umrisse), mit Blattkorn.
        val wob = 0.6 * Noise.gradient(e / 2.1, n / 2.1, SEED_LEAF)
        val b = crowns(e + wob, n - wob, cell, if (conifer) SEED_CONIFER else SEED_CROWN)
        val id = crownId
        val leaf = 0.08 * bl(0.8) * Noise.gradient(e / 0.5, n / 0.5, SEED_LEAF + 1)
        val vis = bl(cell * 1.2)
        val lit = 0.86 + vis * (b - 0.86 + 0.1 * (id - 0.5)) + leaf
        val base = if (conifer) Argb.mix(CONIFER, CONIFER_LIGHT, id) else Argb.mix(BROADLEAF, BROADLEAF_LIGHT, id)
        return Argb.scale(base, lit * (1.0 + 0.06 * p1))
    }

    private fun scrub(e: Double, n: Double, p1: Double, p2: Double): Int {
        val g = meadow(e, n, p1, p2)
        val bush = smoothstep(0.42, 0.58, Noise.value(e / 7.0, n / 7.0, SEED_BUSH))
        if (bush <= 0) return Argb.mix(g, MEADOW_DRY, 0.35)
        val shape = crown(e, n, 2.8, SEED_BUSH)
        val b = Argb.scale(BROADLEAF, 0.85 + bl(3.0) * (0.3 * shape - 0.12))
        return Argb.mix(Argb.mix(g, MEADOW_DRY, 0.35), b, bush)
    }

    private fun residential(e: Double, n: Double, p1: Double, p2: Double): Int {
        // Gaerten: Rasen mit Beeten und Straeuchern, dazwischen Gartenbaeume.
        // Befestigte Hofflaechen kommen von den Gebaeuden ([paintBuildings]).
        val lawn = Argb.scale(LAWN, 1.0 + 0.08 * p1 + 0.08 * bl(2.0) * Noise.gradient(e / 1.8, n / 1.8, SEED_TUFT))
        var c = lawn
        // Weiche Raender (vorher 0,50–0,62 bzw. 0,66–0,72): aus Fahrerhoehe lasen sich Beete und Hofflecken
        // als harte graue Kanten im Rasen.
        val bed = smoothstep(0.48, 0.66, Noise.value(e / 6.0, n / 6.0, SEED_YARD))
        if (bed > 0) {
            val s = crown(e, n, 2.4, SEED_BUSH)
            c = Argb.mix(c, Argb.scale(GARDEN, 0.85 + bl(3.0) * 0.25 * s), bed * smoothstep(0.1, 0.45, s))
        }
        // Terrassen, Wege, Einfahrten: kleine befestigte Flecken.
        val paved = smoothstep(0.66, 0.80, Noise.value(e / 4.0, n / 4.0, SEED_YARD + 3))
        if (paved > 0) c = Argb.mix(c, Argb.scale(PAVED, 1.0 + 0.05 * p1), paved * 0.85)
        val b = crown(e, n, 7.5, SEED_CROWN + 7)
        if (crownId < 0.3) c = Argb.mix(c, Argb.scale(BROADLEAF, 0.8 + 0.3 * b), smoothstep(0.05, 0.35, b))
        return Argb.scale(c, 1.0 + 0.03 * p2)
    }

    private fun industrial(e: Double, n: Double, p1: Double): Int {
        Noise.worley(e / 34.0, n / 34.0, SEED_YARD + 5, 0, worley)
        val id = worley[2]
        val base = when {
            id < 0.38 -> CONCRETE
            id < 0.70 -> ASPHALT
            id < 0.85 -> GRAVEL
            else -> LAWN
        }
        return Argb.scale(base, 1.0 + 0.06 * p1 + 0.05 * bl(1.0) * Noise.gradient(e / 0.9, n / 0.9, SEED_TUFT))
    }

    private fun park(e: Double, n: Double, p1: Double, p2: Double): Int {
        var f = 1.0 + 0.07 * p1 + 0.04 * p2
        f += 0.05 * bl(6.0) * cos(2 * PI * (e * 0.8 + n * 0.6) / 6.0)
        var c = Argb.scale(PARK_LAWN, f)
        val s = crown(e, n, 9.0, SEED_PARKTREE)
        if (crownId < 0.22) c = Argb.mix(c, Argb.scale(BROADLEAF, 0.78 + 0.34 * s), smoothstep(0.05, 0.35, s))
        return c
    }

    private fun wetland(e: Double, n: Double, p1: Double): Int {
        val reed = 0.08 * bl(1.5) * Noise.gradient(e / 0.7, n / 2.4, SEED_TUFT)
        var c = Argb.scale(WETLAND, 1.0 + 0.08 * p1 + reed)
        val pool = smoothstep(0.58, 0.66, Noise.fbmValue(e / 18.0, n / 18.0, SEED_POOL, 3))
        if (pool > 0) c = Argb.mix(c, WATER, pool)
        return c
    }

    // -----------------------------------------------------------------------
    // Linien und Grundrisse
    // -----------------------------------------------------------------------

    private val cov = FloatArray(size * size)
    private val col = IntArray(size * size)
    private var touchY0 = size
    private var touchY1 = -1

    /** Stempelt ein Segment mit halber Breite [hw]; [colorAt] bekommt den Abstand zur Mittellinie in Metern. */
    private inline fun stamp(ax: Double, ay: Double, bx: Double, by: Double, hw: Double, colorAt: (Double) -> Int) {
        val px0 = max(0, floor((min(ax, bx) - hw - spec.minE) / mpp - 1).toInt())
        val px1 = min(size - 1, ceil((max(ax, bx) + hw - spec.minE) / mpp + 1).toInt())
        val py0 = max(0, floor((spec.maxN - max(ay, by) - hw) / mpp - 1).toInt())
        val py1 = min(size - 1, ceil((spec.maxN - min(ay, by) + hw) / mpp + 1).toInt())
        if (px0 > px1 || py0 > py1) return
        touchY0 = min(touchY0, py0)
        touchY1 = max(touchY1, py1)
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        // Linien unter einem Pixel Breite bleiben sichtbar, aber entsprechend blass.
        val wEff = max(hw, mpp * 0.5)
        val fade = (hw / wEff).coerceAtMost(1.0)
        for (py in py0..py1) {
            val n = nOf(py)
            for (px in px0..px1) {
                val e = eOf(px)
                val t = if (len2 > 0) (((e - ax) * dx + (n - ay) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
                val d = hypot(ax + dx * t - e, ay + dy * t - n)
                val c = (((wEff - d) / mpp + 0.5).coerceIn(0.0, 1.0) * fade).toFloat()
                val k = py * size + px
                if (c > cov[k]) {
                    cov[k] = c
                    col[k] = colorAt(d)
                }
            }
        }
    }

    /** Legt die gestempelten Linien ueber das Bild und leert den Puffer. */
    private fun composite(opacity: Double = 1.0) {
        if (touchY1 < touchY0) return
        for (k in touchY0 * size until (touchY1 + 1) * size) {
            val c = cov[k]
            if (c > 0f) {
                img.argb[k] = Argb.mix(img.argb[k], col[k], c * opacity)
                cov[k] = 0f
            }
        }
        touchY0 = size
        touchY1 = -1
    }

    fun paintBuildings(map: MapData) {
        val m = 4.0
        val list = map.buildings(spec.minE - m, spec.minN - m, spec.maxE + m, spec.maxN + m)
        if (list.isEmpty()) return
        // Hofflaechen: befestigter Saum um jedes Gebaeude.
        for (b in list) {
            for (r in b.rings) {
                val cnt = r.size / 2
                for (i in 0 until cnt) {
                    val j = (i + 1) % cnt
                    stamp(r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1], 2.6) { YARD }
                }
            }
        }
        composite(0.75)
        // Daecher: Farbe je Gebaeude (Ziegelrot, Anthrazit, Grau).
        for (b in list) {
            val h = Noise.hash01(b.id.toInt(), (b.id ushr 32).toInt(), SEED_ROOF)
            val roof = when {
                h < 0.48 -> Argb.mix(ROOF_RED, ROOF_RED_DARK, h * 2)
                h < 0.84 -> ROOF_ANTHRACITE
                else -> ROOF_GREY
            }
            fillPolygon(b.rings, roof)
        }
    }

    /** Fuellt Ringe (gerade/ungerade) an Pixelmitten, Raender halbtransparent geglaettet. */
    private fun fillPolygon(rings: List<DoubleArray>, color: Int) {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (r in rings) for (i in 1 until r.size step 2) {
            lo = min(lo, r[i])
            hi = max(hi, r[i])
        }
        val y0 = max(0, floor((spec.maxN - hi) / mpp).toInt())
        val y1 = min(size - 1, ceil((spec.maxN - lo) / mpp).toInt())
        var xs = DoubleArray(16)
        for (py in y0..y1) {
            val n = nOf(py)
            var cnt = 0
            for (r in rings) {
                val m = r.size / 2
                var p = m - 1
                for (q in 0 until m) {
                    val yq = r[2 * q + 1]
                    val yp = r[2 * p + 1]
                    if ((yq > n) != (yp > n)) {
                        if (cnt == xs.size) xs = xs.copyOf(cnt * 2)
                        xs[cnt++] = r[2 * q] + (n - yq) / (yp - yq) * (r[2 * p] - r[2 * q])
                    }
                    p = q
                }
            }
            if (cnt < 2) continue
            java.util.Arrays.sort(xs, 0, cnt)
            var k = 0
            while (k + 1 < cnt) {
                val fx0 = (xs[k] - spec.minE) / mpp
                val fx1 = (xs[k + 1] - spec.minE) / mpp
                val i0 = max(0, floor(fx0).toInt())
                val i1 = min(size - 1, floor(fx1).toInt())
                for (px in i0..i1) {
                    // Anteil des Pixels innerhalb [fx0, fx1].
                    val a = (min(px + 1.0, fx1) - max(px.toDouble(), fx0)).coerceIn(0.0, 1.0)
                    val idx = py * size + px
                    img.argb[idx] = Argb.mix(img.argb[idx], color, a)
                }
                k += 2
            }
        }
    }

    fun paintWaterways(map: MapData) {
        val m = 6.0
        for (w in map.waterways(spec.minE - m, spec.minN - m, spec.maxE + m, spec.maxN + m)) {
            if (w.brunnel == "tunnel") continue
            val hw = when (w.cls) {
                "river" -> 4.0
                "canal" -> 5.0
                "stream" -> 1.1
                else -> 0.6
            }
            val l = w.line
            for (i in 0 until l.size / 2 - 1) stamp(l[2 * i], l[2 * i + 1], l[2 * i + 2], l[2 * i + 3], hw) { WATER }
        }
        composite()
    }

    fun paintRoads(map: MapData) {
        val m = 10.0
        val roads = map.roads(spec.minE - m, spec.minN - m, spec.maxE + m, spec.maxN + m).filter { it.brunnel != "tunnel" }
        if (roads.isEmpty()) return
        // Zeichenreihenfolge: schmal und unbefestigt zuerst, Hauptstrassen zuletzt.
        val order = listOf(
            listOf("path"), listOf("track"), listOf("service"), listOf("rail", "transit"),
            listOf("minor"), listOf("tertiary", "busway", "raceway"), listOf("secondary", "primary"), listOf("trunk", "motorway"),
        )
        for (group in order) {
            var any = false
            for (r in roads) {
                if (r.cls !in group) continue
                val hw = CarvedGround.roadHalfWidthM(r.cls) ?: continue
                val unpaved = r.cls == "track" || r.cls == "path" || r.surface == "unpaved"
                val color = when {
                    r.cls == "path" -> PATH
                    r.cls == "rail" || r.cls == "transit" -> BALLAST
                    unpaved -> TRACK_GRAVEL
                    r.cls == "minor" || r.cls == "service" -> ASPHALT_LIGHT
                    else -> ASPHALT
                }
                val grassMiddle = r.cls == "track" && unpaved
                val l = r.line
                for (i in 0 until l.size / 2 - 1) {
                    stamp(l[2 * i], l[2 * i + 1], l[2 * i + 2], l[2 * i + 3], hw) { d ->
                        if (grassMiddle) Argb.mix(color, TRACK_GRASS, (1 - smoothstep(0.2, 0.45, d)) * bl(0.9)) else color
                    }
                }
                any = true
            }
            if (any) composite()
        }
    }

    fun paintTrack(track: TrackLine) {
        val wt = track.track
        val m = 8.0
        for (i in 0 until wt.sampleCount - 1) {
            val ax = wt.e[i].toDouble()
            val ay = wt.n[i].toDouble()
            val bx = wt.e[i + 1].toDouble()
            val by = wt.n[i + 1].toDouble()
            if (max(ax, bx) < spec.minE - m || min(ax, bx) > spec.maxE + m || max(ay, by) < spec.minN - m || min(ay, by) > spec.maxN + m) continue
            val d = (wt.distAt(i) + wt.distAt(i + 1)) / 2
            val s = track.surfaceAt(d)
            val color = trackSurfaceColor(s.kind)
            val grassMiddle = s.highway == "track" && s.kind.visual in GRASS_MIDDLE
            stamp(ax, ay, bx, by, track.halfWidthAt(d)) { dd ->
                if (grassMiddle) Argb.mix(color, TRACK_GRASS, (1 - smoothstep(0.2, 0.45, dd)) * bl(0.9)) else color
            }
        }
        composite()
    }

    /** Feines Korn je Pixel (aus Welt-Koordinaten, also nahtlos) — wie das Rauschen eines Luftbilds. */
    fun grain() {
        val amp = if (mpp <= 0.5) 7.0 else if (mpp <= 1.0) 5.0 else 2.5
        for (y in 0 until size) {
            val gy = floor(nOf(y) / mpp).toInt()
            for (x in 0 until size) {
                val gx = floor(eOf(x) / mpp).toInt()
                val v = ((Noise.hash01(gx, gy, SEED_GRAIN) - 0.5) * 2 * amp).toInt()
                val c = img.argb[y * size + x]
                img.argb[y * size + x] = Argb.rgb(Argb.red(c) + v, Argb.green(c) + v, Argb.blue(c) + v)
            }
        }
    }

    companion object {
        const val FIELD_ANGLE = 0.42
        const val FIELD_W = 240.0
        const val FIELD_H = 90.0

        const val SEED_WARP = 0x3a71
        const val SEED_PATCH = 0x51e2
        const val SEED_PATCH2 = 0x6c03
        const val SEED_PLOT = 0x7d14
        const val SEED_TUFT = 0x1f25
        const val SEED_FIELD = 0x2e36
        const val SEED_SOIL = 0x3d47
        const val SEED_STAND = 0x4c58
        const val SEED_CROWN = 0x5b69
        const val SEED_CONIFER = 0x6a7a
        const val SEED_BUSH = 0x798b
        const val SEED_YARD = 0x089c
        const val SEED_PARKTREE = 0x17ad
        const val SEED_POOL = 0x26be
        const val SEED_ROOF = 0x35cf
        const val SEED_GRAIN = 0x44d0
        const val SEED_JITTER = 0x53e1
        const val SEED_OPEN = 0x62f2
        const val SEED_LEAF = 0x7103

        val MEADOW_LUSH = Argb.rgb(88, 110, 70)
        val MEADOW_MOWN = Argb.rgb(104, 122, 80)
        val MEADOW_DRY = Argb.rgb(122, 126, 90)
        val MEADOW_YELLOW = Argb.rgb(136, 134, 94)
        val CROP_GREEN = Argb.rgb(84, 108, 58)
        val CROP_RIPE = Argb.rgb(152, 142, 104)
        val SOIL = Argb.rgb(112, 100, 88)
        val STUBBLE = Argb.rgb(154, 146, 128)
        val MAIZE = Argb.rgb(70, 94, 52)
        val LEY = Argb.rgb(96, 116, 74)
        val TRAMLINE = Argb.rgb(116, 106, 86)
        val BROADLEAF = Argb.rgb(56, 74, 48)
        val BROADLEAF_LIGHT = Argb.rgb(72, 92, 56)
        val CONIFER = Argb.rgb(40, 54, 44)
        val CONIFER_LIGHT = Argb.rgb(52, 68, 52)
        val LAWN = Argb.rgb(98, 120, 76)
        val PARK_LAWN = Argb.rgb(100, 124, 76)
        val GARDEN = Argb.rgb(66, 86, 52)
        val PAVED = Argb.rgb(126, 122, 116)
        val YARD = Argb.rgb(128, 124, 116)
        val CONCRETE = Argb.rgb(138, 138, 132)
        val ASPHALT = Argb.rgb(108, 108, 106)
        val ASPHALT_LIGHT = Argb.rgb(118, 117, 113)
        val GRAVEL = Argb.rgb(144, 137, 124)
        val WATER = Argb.rgb(44, 56, 56)
        val WETLAND = Argb.rgb(88, 100, 66)
        val BARE = Argb.rgb(150, 140, 124)
        val PATH = Argb.rgb(134, 124, 102)
        val BALLAST = Argb.rgb(104, 98, 92)
        val TRACK_GRAVEL = Argb.rgb(150, 140, 118)
        val TRACK_GRASS = Argb.rgb(98, 114, 72)
        val ROOF_RED = Argb.rgb(134, 72, 56)
        val ROOF_RED_DARK = Argb.rgb(108, 60, 48)
        val ROOF_ANTHRACITE = Argb.rgb(68, 68, 72)
        val ROOF_GREY = Argb.rgb(108, 106, 102)

        val GRASS_MIDDLE = setOf(SurfaceKind.COMPACTED, SurfaceKind.GRAVEL, SurfaceKind.DIRT, SurfaceKind.GRASS)
    }
}
