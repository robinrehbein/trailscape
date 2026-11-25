package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Belags- und Randtexturen der Wege
 *
 * Alle Texturen sind erzeugt, kachelbar (in beiden Richtungen periodisch) und
 * reine Albedo: keine eingebrannten Schatten, nur dezente Verschattung in
 * Fugen und Ritzen. Die Belagstexturen sind 256 × 1024 Pixel: `u` quer zur
 * Fahrbahn, `v` in Fahrtrichtung. Massstab je Belag in [RouteTextures.spec] —
 * rund 1 cm je Pixel (Schottersteine von 2–5 cm sind 2–5 Pixel gross) und
 * 8–16 m bis zur Wiederholung; die grobe Variation darueber liefern die
 * Vertexfarben der Baender.
 *
 * Wege mit zwei Fahrspuren (Schotter, wassergebundene Decke, Erde, Wiese,
 * Sand) haben die Spuren bei ±[RouteTextures.TRACK_OFFSET_M] um die Mitte der
 * Textur, dazwischen den groeberen, bewachsenen Mittelstreifen.
 */
internal object RouteTextures {
    /** Abstand der Fahrspurmitten von der Wegmitte in Metern. */
    const val TRACK_OFFSET_M = 0.62

    const val SURFACE_W = 256
    const val SURFACE_H = 1024

    /**
     * Massstab einer Belagstextur.
     *
     * @property widthM Meter quer, die die Texturbreite deckt.
     * @property lengthM Meter laengs bis zur Wiederholung.
     * @property twoTrack die Textur hat zwei Fahrspuren und einen Mittelstreifen.
     */
    class Spec(val key: String, val widthM: Double, val lengthM: Double, val twoTrack: Boolean)

    private val SPECS: Map<SurfaceKind, Spec> = mapOf(
        SurfaceKind.ASPHALT to Spec("asphalt", 4.0, 16.0, false),
        SurfaceKind.CONCRETE to Spec("concrete", 4.0, 16.0, false),
        SurfaceKind.PAVING_STONES to Spec("paving", 2.0, 8.0, false),
        SurfaceKind.COBBLESTONE to Spec("cobble", 2.0, 8.0, false),
        SurfaceKind.WOOD to Spec("wood", 2.56, 10.24, false),
        SurfaceKind.COMPACTED to Spec("compacted", 2.56, 10.24, true),
        SurfaceKind.GRAVEL to Spec("gravel", 2.56, 10.24, true),
        SurfaceKind.DIRT to Spec("dirt", 2.56, 10.24, true),
        SurfaceKind.GRASS to Spec("grass", 2.56, 10.24, true),
        SurfaceKind.SAND to Spec("sand", 2.56, 10.24, true),
    )

    fun spec(kind: SurfaceKind): Spec = SPECS.getValue(kind.visual)

    /** Die Randtexturen (Boden neben dem Weg). */
    enum class Verge(
        val key: String,
        /** Meter je Wiederholung (quadratisch, in Weltkoordinaten aufgelegt). */
        val sizeM: Double,
        /**
         * Mittlere Farbe der Textur in linearem Licht — die Textur wird exakt darauf normiert. Seit
         * `indoor-world/12` deutlich heller als die Farben, die am Weg vorkommen: Die Vertexfarbe
         * (hoechstens 1) kann nur abdunkeln, und vorher konnte der Streifen helle Luftbilder (trockene
         * Wiese, Kalkfels, Winterfeld) nicht erreichen — er lief als dunkles, knallgruenes Band daneben.
         */
        val meanLinear: FloatArray,
    ) {
        /** Wiese/Grasnarbe aus der Naehe. */
        GRASS("verge_grass", 5.12, floatArrayOf(0.330f, 0.420f, 0.190f)),

        /** Waldboden: Laubstreu, Zweige, Moos. */
        FOREST("verge_forest", 5.12, floatArrayOf(0.255f, 0.215f, 0.150f)),

        /** Bankett: Schotter und Erde, schuetter bewachsen (auch Rand ueber Fels und kahlem Boden). */
        SHOULDER("verge_shoulder", 2.56, floatArrayOf(0.430f, 0.395f, 0.330f)),

        /** Gehwegplatten. */
        SIDEWALK("verge_sidewalk", 2.56, floatArrayOf(0.300f, 0.295f, 0.280f)),
    }

    /** Mittlere Farbe (linear) der Wiesenweg-Textur; die Vertexfarbe des Wiesenwegs wird auf sie bezogen. */
    val GRASS_WAY_MEAN = floatArrayOf(0.205f, 0.265f, 0.095f)

    /** Meter, die die Fransenkante ([fringe]) laengs und quer deckt. */
    const val FRINGE_LENGTH_M = 5.12
    const val FRINGE_WIDTH_M = 0.8

    // -----------------------------------------------------------------------
    // Zugriff ueber den Texturspeicher der Welt
    // -----------------------------------------------------------------------

    /** [half] = halbe Aufloesung (128 × 512) fuer niedrige Qualitaet: ein Viertel des Grafikspeichers. */
    fun surfaceTexture(cache: TextureCache, kind: SurfaceKind, half: Boolean = false): EncodedTexture {
        val k = kind.visual
        return if (half) {
            cache.get("route_${k.textureKey}_half", jpegQuality = 90) { surface(k).resizeBox(SURFACE_W / 2, SURFACE_H / 2) }
        } else {
            cache.get("route_${k.textureKey}", jpegQuality = 90) { surface(k) }
        }
    }

    /** [sizePx] 512 (Vorgabe) oder 256 fuer niedrige Qualitaet. */
    fun vergeTexture(cache: TextureCache, verge: Verge, sizePx: Int = 512): EncodedTexture {
        val px = if (verge == Verge.SHOULDER || verge == Verge.SIDEWALK) 256 else sizePx
        return cache.get("${verge.key}_$px", jpegQuality = 90) { verge(verge, px) }
    }

    fun fringeTexture(cache: TextureCache): EncodedTexture = cache.get("route_fringe", alpha = true) { fringe() }

    /**
     * Weiche Kante einer uebrigen Strasse (PNG, RGBA): `u` laengs (kachelnd, eine Belagslaenge),
     * `v` quer (geklemmt) von der Fahrbahnkante (`v = 0`, deckend) nach aussen (`v = 1`, durchsichtig).
     *
     * Die Deckung steckt in der **Textur**, nicht in den Vertexfarben: Filament rechnet mit 4× MSAA in
     * einen Float-Puffer und wertet den Shader am Pixelmittelpunkt aus, auch wenn der ausserhalb des
     * Dreiecks liegt — eine Vertex-Deckung, die am Rand auf null faellt, wird dort negativ
     * fortgeschrieben und hellt den Hintergrund auf (weisse gepunktete Linien auf dem Geraet). Ein
     * Texturwert bleibt in 0..1, fortgeschriebene `v` > 1 landen am geklemmten, durchsichtigen Rand.
     */
    fun roadEdgeTexture(cache: TextureCache, kind: SurfaceKind): EncodedTexture =
        cache.get("route_${kind.visual.textureKey}_edge", alpha = true) { edgeStrip(surface(kind.visual)) }

    /**
     * Auslauf des Uebergangsstreifens (PNG, RGBA, 256 × 32): `u` laengs (kachelnd, [Verge.sizeM]),
     * `v` quer (geklemmt) mit der Deckung [VERGE_FADE_ALPHA0] → 0. Farbe: die Randtextur, stark
     * weichgezeichnet (nur die grobe Fleckung) und auf dieselbe mittlere Farbe normiert — die
     * Abtoenung der Vertexfarben passt also unveraendert. Warum die Deckung in der Textur steckt:
     * [roadEdgeTexture].
     */
    fun vergeFadeTexture(cache: TextureCache, verge: Verge): EncodedTexture =
        cache.get("${verge.key}_fade", alpha = true) { vergeFade(verge) }

    /** Deckung des Auslaufs an seiner Innenkante (dort endet der deckende Streifen). */
    const val VERGE_FADE_ALPHA0 = 1.0

    internal fun edgeStrip(src: RgbaImage): RgbaImage {
        val w = 512
        val h = 16
        val out = RgbaImage(w, h)
        for (x in 0 until w) {
            // Laengs: zwei Zeilen der Belagstextur je Pixel (1024 → 512), quer: die Spalten am Rand.
            val sy = (x * src.height / w).coerceIn(0, src.height - 1)
            for (y in 0 until h) {
                val c = src[(4 + y * 2).coerceIn(0, src.width - 1), sy]
                val t = y / (h - 1.0)
                val a = 1.0 - smoothstep(0.0, 1.0, t)
                out[x, y] = Argb.withAlpha(c, Argb.unitTo8(a.toFloat()))
            }
        }
        return out
    }

    internal fun vergeFade(verge: Verge): RgbaImage {
        val src = verge(verge, 256).resizeBox(32, 32).resizeBox(256, 256)
        val w = 256
        val h = 32
        val out = RgbaImage(w, h)
        for (x in 0 until w) for (y in 0 until h) {
            val t = y / (h - 1.0)
            val a = VERGE_FADE_ALPHA0 * (1.0 - smoothstep(0.0, 1.0, t))
            out[x, y] = Argb.withAlpha(src[x, (y * 8) % 256], Argb.unitTo8(a.toFloat()))
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Erzeuger
    // -----------------------------------------------------------------------

    /** Die Belagstextur einer Klasse (256 × 1024, deckend). */
    fun surface(kind: SurfaceKind): RgbaImage {
        val c = Canvas(SURFACE_W, SURFACE_H)
        when (kind.visual) {
            SurfaceKind.ASPHALT -> asphalt(c)
            SurfaceKind.CONCRETE -> concrete(c)
            SurfaceKind.PAVING_STONES -> paving(c)
            SurfaceKind.COBBLESTONE -> cobble(c)
            SurfaceKind.WOOD -> wood(c)
            SurfaceKind.COMPACTED -> compacted(c)
            SurfaceKind.GRAVEL -> gravel(c)
            SurfaceKind.DIRT -> dirt(c)
            SurfaceKind.GRASS -> grassWay(c)
            SurfaceKind.SAND -> sand(c)
            SurfaceKind.UNKNOWN -> compacted(c)
        }
        return c.toImage()
    }

    fun verge(verge: Verge, sizePx: Int): RgbaImage {
        // Immer in 512 gemalt (gleiche Strichstaerken), fuer 256 gemittelt verkleinert.
        val paint = if (verge == Verge.SHOULDER || verge == Verge.SIDEWALK) 256 else 512
        val c = Canvas(paint, paint)
        when (verge) {
            Verge.GRASS -> {
                c.fill(0.19f, 0.22f, 0.11f)
                grassBlades(c, 0x6a11, density = 1.0) { _, _ -> 1.0 }
                c.mottle(4, 4, 0.07f, 0x51)
            }
            Verge.FOREST -> forestFloor(c)
            Verge.SHOULDER -> shoulder(c)
            Verge.SIDEWALK -> sidewalk(c)
        }
        c.normalizeMean(verge.meanLinear)
        val img = c.toImage()
        return if (sizePx < paint && paint == 512) img.resizeBox(sizePx, sizePx) else img
    }

    /**
     * Fransenkante (512 × 64, RGBA): heller, fast farbloser Grasfilz, dessen
     * Deckung von `v = 0` (Wegseite, durchsichtig) nach `v = 1` (Randseite,
     * deckend) unregelmaessig zunimmt. Die Farbe kommt aus den Vertexfarben.
     */
    fun fringe(): RgbaImage {
        val w = 512
        val h = 64
        val c = Canvas(w, h)
        c.fill(0.80f, 0.80f, 0.80f)
        val rng = Rng(0x7f31)
        // Helligkeitsstruktur wie Halme: kurze Striche quer und schraeg.
        for (k in 0 until 5200) {
            val v = 0.62f + rng.f() * 0.38f
            val a = (rng.f() - 0.5f) * 1.6f + (PI / 2).toFloat()
            c.stroke(rng.f() * w, rng.f() * h, a, 3f + rng.f() * 6f, v, v, v, 0.85f)
        }
        val alpha = FloatArray(w * h)
        for (x in 0 until w) {
            // Kantenlage: grobe Buchten, Bueschel und einzelne Halme, die verschieden weit in den Weg ragen.
            val coarse = PNoise.fbm(x.toDouble(), 0.0, w, h, 5, 1, 0x91, 2)
            val clump = PNoise.fbm(x.toDouble(), 0.0, w, h, 40, 1, 0x92, 2)
            val blade = PNoise.value(x * 256.0 / w, 0.5, 256, 1, 0x93) - 0.5
            val edge = 0.56 + coarse * 0.3 + clump * 0.12 + blade * blade * blade * 0.45
            for (y in 0 until h) {
                val v = (y + 0.5) / h
                // Weicher Uebergang (vorher ±0,035 — las sich aus Fahrerhoehe als Saegezahn).
                val a = smoothstep(edge - 0.16, edge + 0.12, v)
                // Ganz am Randstreifen voll, ganz zur Wegmitte leer — die Kanten der Geometrie bleiben unsichtbar.
                alpha[y * w + x] = (max(a, smoothstep(0.80, 0.97, v)) * smoothstep(0.0, 0.10, v)).toFloat()
            }
        }
        return c.toImage(alpha)
    }

    // --- befestigte Belaege --------------------------------------------------

    private fun asphalt(c: Canvas) {
        val w = c.w
        val h = c.h
        c.fill(0.47f, 0.47f, 0.475f)
        // Grossflaechige Flecken (Alterung, Feuchte) — schwach, damit die Kachelung nicht auffaellt.
        c.mottle(2, 8, 0.030f, 0x11)
        c.mottle(8, 32, 0.022f, 0x12)
        // Laengsschlieren der Reifen.
        c.each { x, y, i ->
            val s = PNoise.fbm(x.toDouble(), y.toDouble(), w, h, 10, 3, 0x13, 3).toFloat() * 0.03f
            c.add(i, s, s, s)
        }
        // Flickstellen: scharf begrenzte, etwas dunklere und glattere Rechtecke mit Fugenverguss.
        val rng = Rng(0xa5f)
        for (k in 0 until 2) {
            val pw = 40 + rng.i(50)
            val ph = 70 + rng.i(120)
            val px = rng.i(w)
            val py = rng.i(h)
            val tone = -0.020f + rng.f() * 0.008f
            for (y in 0 until ph) for (x in 0 until pw) {
                val i = c.idx(px + x, py + y)
                val edge = x < 1 || y < 1 || x >= pw - 1 || y >= ph - 1
                if (edge) c.mixTo(i, 0.36f, 0.36f, 0.37f, 0.4f) else c.add(i, tone, tone, tone)
            }
        }
        // Koernung: Gesteinssplitt, einzelne helle und dunkle Koerner.
        c.speckle(0.055f, 0x14)
        for (k in 0 until 26000) {
            val x = rng.i(w)
            val y = rng.i(h)
            val v = if (rng.f() < 0.6f) 0.60f + rng.f() * 0.14f else 0.30f + rng.f() * 0.08f
            c.mixTo(c.idx(x, y), v, v, v * 0.99f, 0.55f)
            if (rng.f() < 0.3f) c.mixTo(c.idx(x + 1, y), v, v, v, 0.35f)
        }
        // Risse, einige mit Bitumen vergossen (breiter, schwarz glaenzend → dunkel).
        for (k in 0 until 4) {
            var x = rng.f() * w
            var y = rng.f() * h
            var dir = if (k % 2 == 0) (PI / 2).toFloat() + (rng.f() - 0.5f) * 0.5f else (rng.f() - 0.5f) * 0.6f
            val sealed = k < 1
            val len = 120 + rng.i(260)
            for (s in 0 until len) {
                dir += (rng.f() - 0.5f) * 0.35f
                x += cos(dir)
                y += sin(dir)
                val i = c.idx(x.toInt(), y.toInt())
                if (sealed) {
                    c.mixTo(i, 0.28f, 0.28f, 0.29f, 0.7f)
                    c.mixTo(c.idx(x.toInt() + 1, y.toInt()), 0.28f, 0.28f, 0.29f, 0.45f)
                } else {
                    c.mixTo(i, 0.36f, 0.36f, 0.36f, 0.4f)
                }
            }
        }
    }

    private fun concrete(c: Canvas) {
        val w = c.w
        val h = c.h
        val rng = Rng(0xc0c)
        val plateH = h / 4
        val plateW = w / 2
        // Platten mit leicht verschiedenem Ton.
        c.each { x, y, i ->
            val px = x / plateW
            val py = y / plateH
            val t = (Noise.hash01(px, py, 0x21).toFloat() - 0.5f) * 0.06f
            c.set(i, 0.640f + t, 0.630f + t, 0.605f + t)
        }
        c.mottle(4, 16, 0.035f, 0x22)
        // Besenstrich quer.
        c.each { x, y, i ->
            val s = (PNoise.value(x * 16.0 / w, y * 0.5, 16, h / 2, 0x23).toFloat() - 0.5f) * 0.035f
            c.add(i, s, s, s)
        }
        c.speckle(0.035f, 0x24)
        for (k in 0 until 9000) {
            val v = 0.48f + rng.f() * 0.30f
            c.mixTo(c.idx(rng.i(w), rng.i(h)), v, v, v * 0.97f, 0.5f)
        }
        // Fugen: vergossen, dunkel, mit hellem Schmutzsaum.
        for (y in 0 until h) for (x in 0 until w) {
            val dx = min(x % plateW, plateW - 1 - x % plateW)
            val dy = min(y % plateH, plateH - 1 - y % plateH)
            val d = min(dx, dy)
            val i = y * w + x
            if (d == 0) c.mixTo(i, 0.20f, 0.20f, 0.20f, 0.92f) else if (d == 1) c.mixTo(i, 0.34f, 0.33f, 0.31f, 0.55f) else if (d < 5) c.add(i, -0.02f, -0.02f, -0.022f)
        }
        // Flecken und ein paar Haarrisse.
        for (k in 0 until 14) c.blob(rng.f() * w, rng.f() * h, 6f + rng.f() * 16f, 5f + rng.f() * 12f, rng.f() * 3f, 0.50f, 0.49f, 0.46f, 0.22f)
        for (k in 0 until 3) {
            var x = rng.f() * w
            var y = rng.f() * h
            var dir = rng.f() * 6.28f
            for (s in 0 until 140 + rng.i(120)) {
                dir += (rng.f() - 0.5f) * 0.5f
                x += cos(dir)
                y += sin(dir)
                c.mixTo(c.idx(x.toInt(), y.toInt()), 0.36f, 0.36f, 0.35f, 0.6f)
            }
        }
    }

    /** Rechteckpflaster 20 × 10 cm im Laeuferverband, quer zur Fahrtrichtung verlegt. */
    private fun paving(c: Canvas) {
        val w = c.w
        val h = c.h
        val cols = 10
        val rows = 80
        val bw = w.toFloat() / cols
        val bh = h.toFloat() / rows
        c.each { x, y, i ->
            val row = floor(y / bh).toInt()
            val off = if (row % 2 == 0) 0f else bw / 2
            val fx = (x + off) / bw
            val col = Math.floorMod(floor(fx).toInt(), cols)
            val lx = (fx - floor(fx)) * bw
            val ly = y - row * bh
            val d = min(min(lx, bw - lx), min(ly, bh - ly))
            val id = Noise.hash(col, row, 0x31)
            val t = ((id and 0xff) / 255f - 0.5f) * 0.10f
            val warm = (((id ushr 8) and 0xff) / 255f - 0.5f) * 0.03f
            // Einzelne dunklere (anthrazit) und rötliche Steine.
            val kind = (id ushr 16) and 0xff
            var r = 0.560f + t + warm
            var g = 0.555f + t
            var b = 0.540f + t - warm
            if (kind < 22) {
                r -= 0.10f; g -= 0.10f; b -= 0.09f
            } else if (kind < 40) {
                r += 0.05f; g -= 0.01f; b -= 0.03f
            }
            if (d < 0.9f) {
                c.set(i, 0.27f, 0.26f, 0.23f)
            } else {
                // Gefaste Kante: schmaler, etwas dunklerer Saum.
                val edge = if (d < 2.2f) -0.045f else 0f
                c.set(i, r + edge, g + edge, b + edge)
            }
        }
        c.speckle(0.04f, 0x32)
        c.mottle(3, 12, 0.03f, 0x33)
        // Moos/Schmutz in einzelnen Fugenbereichen.
        c.each { x, y, i ->
            val m = PNoise.fbm(x.toDouble(), y.toDouble(), w, h, 5, 20, 0x34, 3)
            if (m > 0.25) {
                val row = floor(y / bh).toInt()
                val ly = y - row * bh
                if (ly < 2.0f || ly > bh - 2.0f) c.mixTo(i, 0.25f, 0.29f, 0.17f, ((m - 0.25) * 2.2).coerceAtMost(0.7).toFloat())
            }
        }
    }

    /** Kopfsteinpflaster in Reihen: Granitsteine 10–16 cm, breite dunkle Fugen. */
    private fun cobble(c: Canvas) {
        val w = c.w
        val h = c.h
        val rows = 64
        val rh = h / rows
        c.fill(0.29f, 0.28f, 0.25f)
        val rng = Rng(0xc0bb)
        for (row in 0 until rows) {
            // Steinbreiten der Reihe: zufaellig, auf die Texturbreite normiert.
            val widths = ArrayList<Float>()
            var sum = 0f
            while (sum < w - 8) {
                val bw = 12f + rng.f() * 9f
                widths.add(bw)
                sum += bw
            }
            val scale = w / sum
            var x0 = rng.f() * 14f
            for (bw0 in widths) {
                val bw = bw0 * scale
                val base = 0.48f + rng.f() * 0.12f
                val tint = rng.f()
                val r = base + (if (tint < 0.25f) 0.035f else if (tint > 0.8f) -0.015f else 0f)
                val g = base
                val b = base + (if (tint > 0.8f) 0.03f else if (tint < 0.25f) -0.03f else 0f)
                val cx = x0 + bw / 2
                val cy = row * rh + rh / 2f + (rng.f() - 0.5f) * 1.2f
                val hx = bw / 2 - 1.3f - rng.f() * 0.7f
                val hy = rh / 2f - 1.3f - rng.f() * 0.7f
                c.roundRect(cx, cy, hx, hy, 2.6f, r, g, b)
                x0 += bw
            }
        }
        c.speckle(0.05f, 0x41)
        c.mottle(3, 12, 0.03f, 0x42)
        // Abgefahrene Kuppen sind etwas heller, Fugen mit Sand/Moos.
        c.each { x, y, i ->
            val m = PNoise.fbm(x.toDouble(), y.toDouble(), w, h, 6, 24, 0x43, 3)
            if (c.r[i] < 0.36f && m > 0.2) c.mixTo(i, 0.29f, 0.32f, 0.20f, ((m - 0.2) * 1.6).coerceAtMost(0.55).toFloat())
        }
    }

    /** Holzbohlen quer zur Fahrtrichtung, verwittert. */
    private fun wood(c: Canvas) {
        val w = c.w
        val h = c.h
        val planks = 64
        val ph = h / planks
        c.each { x, y, i ->
            val p = y / ph
            val ly = y % ph
            val id = Noise.hash(p, 7, 0x51)
            val t = ((id and 0xff) / 255f - 0.5f) * 0.11f
            val warm = (((id ushr 8) and 0xff) / 255f - 0.5f) * 0.05f
            // Maserung: in Bohlenrichtung (x) lang gezogen.
            val grain = (PNoise.value(x * 12.0 / w + (id and 7), y * 0.5, 12, h / 2, 0x52).toFloat() - 0.5f) * 0.13f
            val fine = (PNoise.value(x * 0.5, y * 2.0, w / 2, h * 2, 0x53).toFloat() - 0.5f) * 0.05f
            var r = 0.50f + t + warm + grain + fine
            var g = 0.44f + t + grain + fine
            var b = 0.36f + t - warm + grain + fine
            if (ly == 0) {
                r = 0.13f; g = 0.11f; b = 0.09f
            } else if (ly == 1 || ly == ph - 1) {
                r -= 0.09f; g -= 0.09f; b -= 0.08f
            }
            c.set(i, r, g, b)
        }
        val rng = Rng(0x30d)
        // Aeste und Schraubenkoepfe.
        for (k in 0 until 40) c.blob(rng.f() * w, rng.f() * h, 2f + rng.f() * 2f, 1.5f + rng.f() * 1.5f, 0f, 0.26f, 0.20f, 0.14f, 0.8f)
        for (p in 0 until planks) for (sx in intArrayOf(w / 8, w / 2, w * 7 / 8)) {
            c.blob(sx + (Noise.hash01(p, sx, 0x54).toFloat() - 0.5f) * 6f, p * ph + ph / 2f, 1.3f, 1.3f, 0f, 0.30f, 0.30f, 0.31f, 0.85f)
        }
        c.mottle(3, 12, 0.035f, 0x55)
    }

    // --- unbefestigte Belaege -------------------------------------------------

    /** Gewicht 0..1: Stelle liegt in einer Fahrspur. Die Spurraender schwingen laengs leicht. */
    private fun trackWeight(c: Canvas, x: Int, y: Int, halfPx: Float = 21f): Float {
        val pxPerM = c.w / 2.56f
        val wob = (PNoise.fbm(0.0, y.toDouble(), 1, c.h, 1, 14, 0x61, 2) * 5.0).toFloat()
        val wob2 = (PNoise.fbm(7.0, y.toDouble(), 1, c.h, 1, 40, 0x62, 2) * 3.0).toFloat()
        val l = abs(x - (c.w / 2f - TRACK_OFFSET_M.toFloat() * pxPerM + wob))
        val r = abs(x - (c.w / 2f + TRACK_OFFSET_M.toFloat() * pxPerM + wob * 0.6f + wob2))
        val d = min(l, r)
        val wid = halfPx + wob2
        return (1f - smoothstep(wid * 0.55, wid * 1.25, d.toDouble()).toFloat())
    }

    /** Gewicht 0..1: Mittelstreifen. */
    private fun centerWeight(c: Canvas, x: Int, y: Int): Float {
        val wob = (PNoise.fbm(3.0, y.toDouble(), 1, c.h, 1, 18, 0x63, 2) * 6.0).toFloat()
        val d = abs(x - c.w / 2f - wob)
        return 1f - smoothstep(9.0, 26.0, d.toDouble()).toFloat()
    }

    /** Gewicht 0..1: Aussenrand der Textur (jenseits der Spuren). */
    private fun outerWeight(c: Canvas, x: Int): Float {
        val d = min(x, c.w - 1 - x)
        return 1f - smoothstep(8.0, 36.0, d.toDouble()).toFloat()
    }

    private fun compacted(c: Canvas) {
        val w = c.w
        val h = c.h
        val tw = FloatArray(w * h)
        c.each { x, y, i ->
            val t = trackWeight(c, x, y)
            tw[i] = t
            // Spuren heller und glatter, dazwischen etwas dunkler.
            c.set(i, 0.615f + 0.065f * t, 0.585f + 0.060f * t, 0.520f + 0.050f * t)
        }
        c.mottle(3, 12, 0.035f, 0x71)
        c.mottle(12, 48, 0.02f, 0x72)
        val rng = Rng(0x7c0)
        // Feiner Splitt: ueberall kleine Koerner, ausserhalb der Spuren mehr und groeber.
        for (k in 0 until 52000) {
            val x = rng.i(w)
            val y = rng.i(h)
            val t = tw[y * w + x]
            if (rng.f() < t * 0.6f) continue
            val v = 0.45f + rng.f() * 0.36f
            val rad = if (t > 0.5f) 0.6f else 0.6f + rng.f() * 0.9f
            c.blob(x.toFloat(), y.toFloat(), rad, rad * (0.7f + rng.f() * 0.5f), rng.f() * 3f, v + 0.01f, v, v - 0.03f, 0.75f)
        }
        c.speckle(0.04f, 0x73)
        // Mittelstreifen und Raender: schuetteres Gras, dunklere Erde.
        grassBlades(c, 0x76, density = 0.6) { x, y -> (max(centerWeight(c, x, y) * 0.8f, outerWeight(c, x)) * patch(c, x, y, 0x75, -0.10)).toDouble() }
        tufts(c, 0x74, 1800) { x, y -> max(centerWeight(c, x, y) * 0.75f, outerWeight(c, x) * 0.9f) * patch(c, x, y, 0x75, 0.0) }
    }

    private fun gravel(c: Canvas) {
        val w = c.w
        val h = c.h
        val tw = FloatArray(w * h)
        c.each { x, y, i ->
            val t = trackWeight(c, x, y)
            tw[i] = t
            // Feinmaterial in den Spuren (sandig-hell), dazwischen dunklere Erde zwischen den Steinen.
            c.set(i, 0.40f + 0.17f * t, 0.375f + 0.155f * t, 0.325f + 0.125f * t)
        }
        c.mottle(3, 12, 0.04f, 0x81)
        c.speckle(0.05f, 0x82)
        val rng = Rng(0x96a)
        // Steine 2–5 cm (Radius 1–2,6 px), in den Spuren nur kleine, eingefahrene.
        for (k in 0 until 30000) {
            val x = rng.f() * w
            val y = rng.f() * h
            val t = tw[c.idx(x.toInt(), y.toInt())]
            if (rng.f() < t * 0.72f) continue
            val rad = if (t > 0.45f) 0.6f + rng.f() * 0.6f else 0.8f + rng.f() * rng.f() * 1.5f
            val v = 0.40f + rng.f() * 0.24f
            val tint = rng.f()
            val r = v + (if (tint < 0.3f) 0.03f else 0f)
            val b = v + (if (tint > 0.75f) 0.02f else -0.03f)
            val rot = rng.f() * 3.14f
            val ry = rad * (0.62f + rng.f() * 0.38f)
            if (rad > 1.3f) c.blob(x + 0.4f, y + 0.5f, rad + 0.6f, ry + 0.6f, rot, 0.27f, 0.25f, 0.22f, 0.35f)
            c.blob(x, y, rad, ry, rot, r, v, b, 0.9f)
        }
        // Mittelstreifen: Grasnarbe in zusammenhaengenden Flecken; an den Raendern waechst sie herein.
        grassBlades(c, 0x85, density = 0.75) { x, y -> (max(centerWeight(c, x, y), outerWeight(c, x)) * patch(c, x, y, 0x84, 0.06)).toDouble() }
        tufts(c, 0x83, 2600) { x, y -> max(centerWeight(c, x, y), outerWeight(c, x) * 0.9f) * patch(c, x, y, 0x84, -0.05) }
    }

    private fun dirt(c: Canvas) {
        val w = c.w
        val h = c.h
        val tw = FloatArray(w * h)
        c.each { x, y, i ->
            val t = trackWeight(c, x, y, 24f)
            tw[i] = t
            c.set(i, 0.310f + 0.050f * t, 0.265f + 0.045f * t, 0.210f + 0.035f * t)
        }
        c.mottle(2, 8, 0.045f, 0x91)
        c.mottle(10, 40, 0.035f, 0x92)
        c.speckle(0.04f, 0x93)
        val rng = Rng(0xd127)
        // Steinchen.
        for (k in 0 until 2600) {
            val x = rng.f() * w
            val y = rng.f() * h
            val v = 0.36f + rng.f() * 0.17f
            val rad = 0.8f + rng.f() * rng.f() * 2.0f
            c.blob(x, y, rad, rad * (0.6f + rng.f() * 0.4f), rng.f() * 3f, v, v - 0.01f, v - 0.04f, 0.85f)
        }
        // Wurzeln: quer und schraeg laufende, dunkle Wuelste mit hellerem Ruecken.
        for (k in 0 until 7) {
            var x = rng.f() * w
            var y = rng.f() * h
            var dir = (rng.f() - 0.5f) * 1.4f + (if (rng.f() < 0.5f) 0f else PI.toFloat())
            val thick = 1.6f + rng.f() * 1.6f
            val len = 60 + rng.i(150)
            for (s in 0 until len) {
                dir += (rng.f() - 0.5f) * 0.22f
                x += cos(dir)
                y += sin(dir) * 0.8f
                val th = thick * (1f - s / len.toFloat() * 0.6f)
                c.blob(x, y, th + 0.8f, th + 0.8f, 0f, 0.22f, 0.18f, 0.14f, 0.4f)
                c.blob(x, y, th * 0.55f, th * 0.55f, 0f, 0.40f, 0.34f, 0.27f, 0.5f)
            }
        }
        // Laub: ausserhalb der Spuren dichter.
        leaves(c, 0x94, 5200) { x, y -> 0.22f + 0.78f * (1f - tw[y * w + x]) * (0.35f + 0.65f * max(centerWeight(c, x, y), outerWeight(c, x))) }
        // Etwas Moos/Gras ganz am Rand.
        tufts(c, 0x95, 900) { x, y -> outerWeight(c, x) * patch(c, x, y, 0x96, 0.05) }
    }

    private fun grassWay(c: Canvas) {
        val w = c.w
        val h = c.h
        val tw = FloatArray(w * h)
        c.each { x, y, i ->
            val t = trackWeight(c, x, y, 19f)
            // Kahle Stellen nur fleckweise in den Spuren.
            val bare = t * smoothstep(-0.22, 0.22, PNoise.fbm(x.toDouble(), y.toDouble(), w, h, 4, 16, 0xa1, 3)).toFloat()
            tw[i] = bare
            c.set(i, 0.20f + 0.19f * bare, 0.225f + 0.105f * bare, 0.115f + 0.10f * bare)
        }
        c.speckle(0.035f, 0xa2)
        grassBlades(c, 0xa3, density = 0.5) { x, y -> 1.0 - tw[y * w + x] * 0.85 }
        // In den Spuren kurzes, gelbliches, niedergefahrenes Gras.
        val rng = Rng(0xa4)
        for (k in 0 until 9000) {
            val x = rng.i(w)
            val y = rng.i(h)
            val t = trackWeight(c, x, y, 19f)
            if (rng.f() > t * 0.8f) continue
            val v = rng.f()
            c.stroke(x.toFloat(), y.toFloat(), (PI / 2).toFloat() + (rng.f() - 0.5f) * 0.9f, 2f + rng.f() * 3f, 0.44f + v * 0.10f, 0.45f + v * 0.08f, 0.24f + v * 0.05f, 0.7f)
        }
        c.mottle(3, 12, 0.04f, 0xa5)
        // Hell gemalt wie die Wiese am Rand: die Vertexfarbe toent beide gleich ab.
        c.normalizeMean(GRASS_WAY_MEAN)
    }

    private fun sand(c: Canvas) {
        val w = c.w
        val h = c.h
        c.each { x, y, i ->
            val t = trackWeight(c, x, y, 24f)
            // Lockerer Sand hell, gefahrene Spuren etwas dunkler und fester.
            val warp = PNoise.fbm(x.toDouble(), y.toDouble(), w, h, 3, 12, 0xb1, 3) * 9.0
            val ripple = (sin((y + warp) * 2 * PI * 34 / h) * 0.012).toFloat() * (1f - t)
            c.set(i, 0.630f - 0.05f * t + ripple, 0.555f - 0.05f * t + ripple, 0.415f - 0.045f * t + ripple)
        }
        c.mottle(2, 8, 0.05f, 0xb2)
        c.mottle(10, 40, 0.045f, 0xb3)
        c.speckle(0.045f, 0xb4)
        val rng = Rng(0x5a9d)
        for (k in 0 until 1500) {
            val v = 0.40f + rng.f() * 0.35f
            val rad = 0.7f + rng.f() * rng.f() * 1.8f
            c.blob(rng.f() * w, rng.f() * h, rad, rad * 0.8f, rng.f() * 3f, v, v - 0.02f, v - 0.06f, 0.9f)
        }
        tufts(c, 0xb5, 700) { x, y -> outerWeight(c, x) * patch(c, x, y, 0xb6, 0.1) }
    }

    // --- Raender --------------------------------------------------------------

    private fun forestFloor(c: Canvas) {
        val w = c.w
        val h = c.h
        c.fill(0.27f, 0.215f, 0.16f)
        c.mottle(4, 4, 0.05f, 0xc1)
        c.speckle(0.04f, 0xc2)
        leaves(c, 0xc3, 26000) { _, _ -> 1f }
        // Dunkle, feuchte Stellen ohne frisches Laub — bricht die Gleichfoermigkeit im Grossen.
        c.each { x, y, i ->
            val m = PNoise.fbm(x.toDouble(), y.toDouble(), w, h, 3, 3, 0xc7, 4)
            if (m > 0.02) {
                val k = ((m - 0.02) * 2.4).coerceAtMost(0.5).toFloat()
                c.mixTo(i, 0.20f, 0.165f, 0.125f, k)
            }
        }
        val rng = Rng(0xf0e)
        // Zweige und Aststuecke.
        for (k in 0 until 260) {
            val light = rng.f() < 0.35f
            val v = if (light) 0.46f + rng.f() * 0.1f else 0.17f + rng.f() * 0.06f
            c.stroke(rng.f() * w, rng.f() * h, rng.f() * 6.28f, 8f + rng.f() * 30f, v, v * 0.86f, v * 0.68f, 0.9f, width = if (rng.f() < 0.25f) 2 else 1)
        }
        // Moospolster.
        c.each { x, y, i ->
            val m = PNoise.fbm(x.toDouble(), y.toDouble(), w, h, 5, 5, 0xc4, 4)
            if (m > 0.30) {
                val g = (PNoise.value(x * 0.5, y * 0.5, w / 2, h / 2, 0xc5).toFloat() - 0.5f) * 0.08f
                c.mixTo(i, 0.20f + g, 0.29f + g, 0.11f + g * 0.5f, ((m - 0.30) * 5.0).coerceAtMost(0.85).toFloat())
            }
        }
        leaves(c, 0xc6, 1800) { _, _ -> 1f }
    }

    private fun shoulder(c: Canvas) {
        val w = c.w
        val h = c.h
        c.fill(0.40f, 0.365f, 0.31f)
        c.mottle(4, 4, 0.05f, 0xd1)
        c.speckle(0.05f, 0xd2)
        val rng = Rng(0xba9)
        for (k in 0 until 6500) {
            val rad = 0.8f + rng.f() * rng.f() * 2.0f
            val v = 0.42f + rng.f() * 0.32f
            val x = rng.f() * w
            val y = rng.f() * h
            if (rad > 1.4f) c.blob(x + 0.5f, y + 0.6f, rad + 0.6f, rad + 0.5f, 0f, 0.26f, 0.24f, 0.21f, 0.4f)
            c.blob(x, y, rad, rad * (0.62f + rng.f() * 0.38f), rng.f() * 3f, v + 0.01f, v, v - 0.03f, 0.95f)
        }
        tufts(c, 0xd3, 1500) { x, y -> patch(c, x, y, 0xd4, -0.02) * 0.8f }
    }

    private fun sidewalk(c: Canvas) {
        // Betonsteinpflaster im Laeuferverband: 12 Steine quer (21 cm) und 24 Reihen (10,7 cm) je 2,56 m.
        // Vorher 32-cm-Platten mit zwei Pixel breiten, dunklen Fugen — aus Fahrerhoehe ein grobes Gitter.
        val w = c.w
        val h = c.h
        val nx = 12
        val ny = 24
        val sx = w.toFloat() / nx
        val sy = h.toFloat() / ny
        c.each { x, y, i ->
            val row = (y / sy).toInt()
            val xx = x + if (row % 2 == 0) 0f else sx / 2
            val col = Math.floorMod((xx / sx).toInt(), nx)
            val lx = xx - floor(xx / sx) * sx
            val ly = y - row * sy
            val d = min(min(lx, sx - 1 - lx), min(ly, sy - 1 - ly))
            val t = (Noise.hash01(col, row, 0xe1).toFloat() - 0.5f) * 0.08f
            val joint = if (d < 0.6f) 0.13f else if (d < 1.5f) 0.035f else 0f
            c.set(i, 0.60f + t - joint, 0.595f + t - joint, 0.575f + t - joint)
        }
        c.speckle(0.04f, 0xe2)
        c.mottle(4, 4, 0.035f, 0xe3)
        val rng = Rng(0x51d)
        for (k in 0 until 2500) {
            val v = 0.45f + rng.f() * 0.3f
            c.mixTo(c.idx(rng.i(w), rng.i(h)), v, v, v, 0.5f)
        }
        c.each { x, y, i ->
            val m = PNoise.fbm(x.toDouble(), y.toDouble(), w, h, 4, 4, 0xe4, 3)
            if (c.r[i] < 0.36f && m > 0.1) c.mixTo(i, 0.26f, 0.31f, 0.17f, ((m - 0.1) * 2.0).coerceAtMost(0.7).toFloat())
        }
    }

    // --- Bausteine ------------------------------------------------------------

    /** Fleckigkeit 0..1 (grob), um Bewuchs in Horsten statt gleichmaessig zu verteilen. */
    private fun patch(c: Canvas, x: Int, y: Int, seed: Int, bias: Double): Float {
        val cellsX = max(2, c.w / 40)
        val n = PNoise.fbm(x.toDouble(), y.toDouble(), c.w, c.h, cellsX, cellsX * c.h / c.w, seed, 3)
        return smoothstep(-0.10, 0.30, n + bias).toFloat()
    }

    private val GRASS_PALETTE = arrayOf(
        floatArrayOf(0.31f, 0.39f, 0.19f), floatArrayOf(0.36f, 0.44f, 0.22f), floatArrayOf(0.27f, 0.35f, 0.17f),
        floatArrayOf(0.41f, 0.48f, 0.25f), floatArrayOf(0.33f, 0.41f, 0.18f), floatArrayOf(0.45f, 0.49f, 0.28f),
        floatArrayOf(0.24f, 0.31f, 0.15f), floatArrayOf(0.38f, 0.44f, 0.26f),
    )

    /** Grasbueschel: kurze Halme, die aus einem Punkt strahlen. [weight] 0..1 je Stelle. */
    private fun tufts(c: Canvas, seed: Int, count: Int, weight: (Int, Int) -> Float) {
        val rng = Rng(seed)
        for (k in 0 until count) {
            val x = rng.i(c.w)
            val y = rng.i(c.h)
            if (rng.f() > weight(x, y)) continue
            // Erdiger, dunkler Fuss unter dem Bueschel.
            c.blob(x.toFloat(), y.toFloat(), 3.0f, 2.8f, 0f, 0.27f, 0.28f, 0.17f, 0.22f)
            val blades = 7 + rng.i(8)
            for (b in 0 until blades) {
                val p = GRASS_PALETTE[rng.i(GRASS_PALETTE.size)]
                val sh = 0.92f + rng.f() * 0.16f
                c.stroke(x + (rng.f() - 0.5f) * 3f, y + (rng.f() - 0.5f) * 3f, rng.f() * 6.28f, 2.5f + rng.f() * 4.5f, p[0] * sh, p[1] * sh, p[2] * sh, 0.9f)
            }
        }
    }

    /** Dichter Rasen aus einzelnen Halmen (auf 512 px ≙ 5,12 m bzw. 256 px ≙ 2,56 m abgestimmt). */
    private fun grassBlades(c: Canvas, seed: Int, density: Double, weight: (Int, Int) -> Double) {
        val rng = Rng(seed)
        val count = (c.w * c.h * 0.42 * density).toInt()
        for (k in 0 until count) {
            val x = rng.i(c.w)
            val y = rng.i(c.h)
            if (rng.f() > weight(x, y)) continue
            val p = GRASS_PALETTE[rng.i(GRASS_PALETTE.size)]
            // Halmgruppen aehnlicher Richtung (Wind/Wuchs), grob ueber die Flaeche wechselnd.
            val flow = PNoise.value(x * 6.0 / c.w, y * 6.0 / c.w, 6, max(1, 6 * c.h / c.w), seed + 5) * 6.28
            val a = (flow + (rng.f() - 0.5f) * 2.4f).toFloat()
            val sh = 0.86f + rng.f() * 0.26f
            val dry = rng.f() < 0.07f
            if (dry) {
                c.stroke(x.toFloat(), y.toFloat(), a, 4f + rng.f() * 7f, 0.60f, 0.56f, 0.34f, 0.85f)
            } else {
                c.stroke(x.toFloat(), y.toFloat(), a, 3f + rng.f() * 7f, p[0] * sh, p[1] * sh, p[2] * sh, 0.88f)
            }
        }
        // Klee/Kraeuter: kleine runde, dunklere Blaettchen.
        for (k in 0 until count / 60) {
            val x = rng.i(c.w)
            val y = rng.i(c.h)
            if (rng.f() > weight(x, y)) continue
            c.blob(x.toFloat(), y.toFloat(), 1.6f + rng.f(), 1.4f + rng.f(), rng.f() * 3f, 0.22f, 0.34f, 0.15f, 0.8f)
        }
    }

    private val LEAF_PALETTE = arrayOf(
        floatArrayOf(0.40f, 0.32f, 0.23f), floatArrayOf(0.44f, 0.37f, 0.27f), floatArrayOf(0.35f, 0.29f, 0.22f),
        floatArrayOf(0.47f, 0.41f, 0.31f), floatArrayOf(0.32f, 0.27f, 0.21f), floatArrayOf(0.41f, 0.33f, 0.22f),
        floatArrayOf(0.38f, 0.34f, 0.27f),
    )

    /** Laubstreu: Buchen-/Eichenblaetter als kleine Ellipsen mit dunklem Saum darunter. */
    private fun leaves(c: Canvas, seed: Int, count: Int, weight: (Int, Int) -> Float) {
        val rng = Rng(seed)
        for (k in 0 until count) {
            val x = rng.i(c.w)
            val y = rng.i(c.h)
            if (rng.f() > weight(x, y)) continue
            val p = LEAF_PALETTE[rng.i(LEAF_PALETTE.size)]
            val sh = 0.9f + rng.f() * 0.2f
            val rx = 1.8f + rng.f() * 1.9f
            val ry = rx * (0.45f + rng.f() * 0.2f)
            val rot = rng.f() * 3.14f
            if (k % 3 == 0) c.blob(x + 0.3f, y + 0.4f, rx + 0.5f, ry + 0.5f, rot, 0.22f, 0.175f, 0.13f, 0.18f)
            c.blob(x.toFloat(), y.toFloat(), rx, ry, rot, p[0] * sh, p[1] * sh, p[2] * sh, 0.85f)
        }
    }

    // -----------------------------------------------------------------------
    // Zeichenflaeche und Rauschen
    // -----------------------------------------------------------------------

    /** Kleiner, fester Zufallsgenerator (xorshift) — unabhaengig von `java.util.Random`. */
    private class Rng(seed: Int) {
        private var s = Noise.mix(seed) or 1

        fun next(): Int {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            return s
        }

        fun f(): Float = (next() ushr 8) / 16777216f
        fun i(n: Int): Int = ((next() ushr 8).toLong() * n / 16777216L).toInt()
    }

    /** Zeichenflaeche in sRGB-Gleitkommawerten; alle Zugriffe laufen um (kachelbar). */
    private class Canvas(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)

        fun idx(x: Int, y: Int): Int = Math.floorMod(y, h) * w + Math.floorMod(x, w)

        fun fill(cr: Float, cg: Float, cb: Float) {
            r.fill(cr); g.fill(cg); b.fill(cb)
        }

        fun set(i: Int, cr: Float, cg: Float, cb: Float) {
            r[i] = cr; g[i] = cg; b[i] = cb
        }

        fun add(i: Int, cr: Float, cg: Float, cb: Float) {
            r[i] += cr; g[i] += cg; b[i] += cb
        }

        fun mixTo(i: Int, cr: Float, cg: Float, cb: Float, a: Float) {
            r[i] += (cr - r[i]) * a; g[i] += (cg - g[i]) * a; b[i] += (cb - b[i]) * a
        }

        inline fun each(f: (Int, Int, Int) -> Unit) {
            var i = 0
            for (y in 0 until h) for (x in 0 until w) f(x, y, i++)
        }

        /** Weisses Rauschen je Pixel, ±[amp]. */
        fun speckle(amp: Float, seed: Int) {
            each { x, y, i ->
                val v = (Noise.hash01(x, y, seed).toFloat() - 0.5f) * 2f * amp
                add(i, v, v, v)
            }
        }

        /** Weiche Flecken: fraktales Rauschen mit [cellsX] × [cellsY] Grundzellen, ±[amp], leicht farbstichig. */
        fun mottle(cellsX: Int, cellsY: Int, amp: Float, seed: Int) {
            each { x, y, i ->
                val v = PNoise.fbm(x.toDouble(), y.toDouble(), w, h, cellsX, cellsY, seed, 4).toFloat() * amp * 1.6f
                val t = PNoise.fbm(x.toDouble(), y.toDouble(), w, h, cellsX, cellsY, seed + 77, 2).toFloat() * amp * 0.35f
                add(i, v + t, v, v - t)
            }
        }

        /** Weiche Ellipse (Halbachsen [rx], [ry] in Pixeln, um [rot] gedreht). */
        fun blob(cx: Float, cy: Float, rx: Float, ry: Float, rot: Float, cr: Float, cg: Float, cb: Float, opacity: Float) {
            val ext = max(rx, ry) + 1f
            val co = cos(rot)
            val si = sin(rot)
            val x0 = floor(cx - ext).toInt()
            val x1 = floor(cx + ext).toInt() + 1
            val y0 = floor(cy - ext).toInt()
            val y1 = floor(cy + ext).toInt() + 1
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val u = (dx * co + dy * si) / rx
                val v = (-dx * si + dy * co) / ry
                val d = sqrt(u * u + v * v)
                // Kante ueber etwa einen Pixel geglaettet.
                val edge = 1f / max(0.6f, min(rx, ry))
                val a = ((1f - d) / edge + 0.5f).coerceIn(0f, 1f)
                if (a > 0f) mixTo(idx(x, y), cr, cg, cb, a * opacity)
            }
        }

        /** Gerader Strich ab ([x], [y]) in Richtung [angle], [len] Pixel lang. */
        fun stroke(x: Float, y: Float, angle: Float, len: Float, cr: Float, cg: Float, cb: Float, opacity: Float, width: Int = 1) {
            val dx = cos(angle)
            val dy = sin(angle)
            val n = len.toInt().coerceAtLeast(1)
            for (s in 0..n) {
                val px = (x + dx * s).toInt()
                val py = (y + dy * s).toInt()
                // Zur Spitze hin duenner (durchscheinender).
                val a = opacity * (1f - 0.5f * s / n)
                mixTo(idx(px, py), cr, cg, cb, a)
                if (width > 1) mixTo(idx(px + 1, py), cr, cg, cb, a * 0.8f)
            }
        }

        /** Stein mit gerundeten Ecken (Halbachsen [hx], [hy], Eckradius [rad]). */
        fun roundRect(cx: Float, cy: Float, hx: Float, hy: Float, rad: Float, cr: Float, cg: Float, cb: Float) {
            val x0 = floor(cx - hx).toInt() - 1
            val x1 = floor(cx + hx).toInt() + 1
            val y0 = floor(cy - hy).toInt() - 1
            val y1 = floor(cy + hy).toInt() + 1
            for (y in y0..y1) for (x in x0..x1) {
                val qx = abs(x + 0.5f - cx) - (hx - rad)
                val qy = abs(y + 0.5f - cy) - (hy - rad)
                val ox = max(qx, 0f)
                val oy = max(qy, 0f)
                val d = sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - rad
                val a = (0.5f - d).coerceIn(0f, 1f)
                if (a <= 0f) continue
                // Zur Fuge hin leicht dunkler (Rundung der Kuppe, Schmutz).
                val shade = if (d > -2.2f) -0.05f * (1f + d / 2.2f).coerceIn(0f, 1f) else 0f
                mixTo(idx(x, y), cr + shade, cg + shade, cb + shade, a)
            }
        }

        /** Skaliert so, dass der Mittelwert in linearem Licht genau [target] ist. */
        fun normalizeMean(target: FloatArray) {
            for ((ch, arr) in arrayOf(r, g, b).withIndex()) {
                repeat(3) {
                    var sum = 0.0
                    for (v in arr) sum += Argb.srgbToLinear(v.coerceIn(0f, 1f).toDouble())
                    val mean = sum / arr.size
                    if (mean <= 1e-6) return@repeat
                    val k = target[ch] / mean
                    for (i in arr.indices) {
                        val lin = Argb.srgbToLinear(arr[i].coerceIn(0f, 1f).toDouble()) * k
                        arr[i] = Argb.linearToSrgb(lin.coerceIn(0.0, 1.0)).toFloat()
                    }
                }
            }
        }

        fun toImage(alpha: FloatArray? = null): RgbaImage {
            val out = IntArray(w * h)
            for (i in out.indices) {
                val a = if (alpha == null) 255 else Argb.unitTo8(alpha[i])
                out[i] = Argb.of(a, Argb.unitTo8(r[i]), Argb.unitTo8(g[i]), Argb.unitTo8(b[i]))
            }
            return RgbaImage(w, h, out)
        }
    }

    /** Wertrauschen mit getrennten Perioden in x und y (fuer nicht quadratische, kachelnde Texturen). */
    private object PNoise {
        private fun fade(t: Double): Double = t * t * (3 - 2 * t)

        /** In `[0, 1]`; [x], [y] in Gitterzellen, periodisch mit [perX] × [perY] Zellen. */
        fun value(x: Double, y: Double, perX: Int, perY: Int, seed: Int): Double {
            val xf = floor(x)
            val yf = floor(y)
            val tx = fade(x - xf)
            val ty = fade(y - yf)
            val x0 = Math.floorMod(xf.toInt(), perX)
            val x1 = Math.floorMod(xf.toInt() + 1, perX)
            val y0 = Math.floorMod(yf.toInt(), perY)
            val y1 = Math.floorMod(yf.toInt() + 1, perY)
            val a = Noise.hash01(x0, y0, seed)
            val b = Noise.hash01(x1, y0, seed)
            val c = Noise.hash01(x0, y1, seed)
            val d = Noise.hash01(x1, y1, seed)
            val ab = a + (b - a) * tx
            val cd = c + (d - c) * tx
            return ab + (cd - ab) * ty
        }

        /**
         * Fraktales Rauschen etwa in `[-0,5, 0,5]` an Pixel ([px], [py]) einer
         * Textur [w] × [h] mit [cellsX] × [cellsY] Grundzellen; kachelbar.
         */
        fun fbm(px: Double, py: Double, w: Int, h: Int, cellsX: Int, cellsY: Int, seed: Int, octaves: Int): Double {
            var sum = 0.0
            var amp = 1.0
            var norm = 0.0
            var cx = cellsX
            var cy = cellsY
            for (o in 0 until octaves) {
                sum += (value(px * cx / w, py * cy / h, cx, cy, seed + o * 1013) - 0.5) * amp
                norm += amp
                amp *= 0.5
                cx *= 2
                cy *= 2
            }
            return sum / norm
        }
    }

    /** Mittlere Farbe eines Bilds in linearem Licht (fuer Tests und Abtoenung). */
    fun meanLinear(img: RgbaImage): FloatArray {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (c in img.argb) {
            r += Argb.SRGB_TO_LINEAR[Argb.red(c)]
            g += Argb.SRGB_TO_LINEAR[Argb.green(c)]
            b += Argb.SRGB_TO_LINEAR[Argb.blue(c)]
        }
        val n = img.argb.size
        return floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }
}
