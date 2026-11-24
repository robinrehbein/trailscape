package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Prozedurale Vegetations-Texturen
 *
 * Alles hier wird im Code gezeichnet (keine fremden Bilder): ein **Atlas**
 * 512² fuer Blattkarten (`mat_tree_leaves`, `MASK`, doppelseitig) und ein
 * kachelndes **Kronendach** 256² fuer die Waldkoerper.
 *
 * Die Bilder sind fast farblos (helles Gelbgruen mit Helligkeits- und leichter
 * Farbtonstreuung); die eigentliche Farbe kommt aus den Eckpunkten
 * (`COLOR_0` × Textur): Art, Abtoenung je Baum, Verschattung im Kroneninneren.
 *
 * **Mipmaps.** gltfio erzeugt die Mip-Stufen selbst (Kastenfilter,
 * `Texture.generateMipmaps`), eigene Stufen lassen sich in einer GLB mit PNG
 * nicht mitgeben. Damit Alpha-Kanten in der Ferne nicht ausduennen, sind die
 * Motive so gezeichnet, dass ihre Deckung im **Umriss** hoch ist (≥ 70 %,
 * dichte, ueberlappende Blaetter/Nadeln) — der Mittelwert einer groben
 * Stufe bleibt dort ueber der Schwelle 0,5, nur einzelne abstehende Spitzen
 * verschwinden. Durchsichtige Pixel tragen die mittlere Farbe ihres Motivs,
 * damit gemittelte Stufen keinen dunklen oder hellen Saum bekommen. Zwischen
 * den Motiven liegen mindestens 6 Pixel Rand (bis Stufe 3 kein Uebersprechen).
 */
internal object FoliageAtlas {
    const val SIZE = 512

    /** Ein Motiv im Atlas (Pixelrechteck, Bildzeilen von oben). */
    class Region(val x: Int, val y: Int, val w: Int, val h: Int, val size: Int = SIZE) {
        /** UV links→rechts fuer `s` in 0..1. */
        fun u(s: Double): Double = (x + s * w) / size

        /** UV fuer `t` in 0..1 von **unten** nach oben (glTF: v = 0 ist die oberste Bildzeile). */
        fun v(t: Double): Double = (y + h - t * h) / size
    }

    /** Laubzweig: Ansatz unten Mitte, Blaetter in rundem Umriss. */
    val BROAD = Region(0, 0, 256, 256)

    /** Nadelzweig: Ansatz links Mitte, Spitze rechts, flach nach rechts verjuengt (Kiefernbueschel). */
    val NEEDLE = Region(256, 0, 256, 128)

    /**
     * Fichten-Astquirl ("Schuerze") von der Seite: oben dicht (am Stamm), unten ein
     * gewellter, haengender Rand aus Zweigspitzen. Wird als Trapez um den Stamm gelegt.
     */
    val SKIRT = Region(256, 128, 256, 128)

    /** Ganzer Laubbaum (Kreuzbild): Stamm unten Mitte. */
    val TREE_BROAD = Region(0, 256, 128, 256)

    /** Ganze Fichte (Kreuzbild). */
    val TREE_CONIFER = Region(128, 256, 128, 256)

    /** Grasbueschel, Halme von der Unterkante. */
    val GRASS = Region(256, 256, 256, 128)

    /** Dichte Laubmasse mit gelapptem Rand (mittlere Stufe, Buesche, Kern). */
    val MASS = Region(256, 384, 128, 128)

    /** Farn/Kraut von unten Mitte. */
    val FERN = Region(384, 384, 128, 128)

    /** Kantenlaenge des Fern-Atlas (nur Kreuzbilder ganzer Baeume, fuer Stufe 1). */
    const val FAR_SIZE = 256
    val FAR_TREE_BROAD = Region(0, 0, 128, 256, FAR_SIZE)
    val FAR_TREE_CONIFER = Region(128, 0, 128, 256, FAR_SIZE)

    /** Fern-Atlas 256² fuer die Kreuzbilder der Waldkoerper in Stufe 1 (spart dort den grossen Atlas). */
    fun farImage(): RgbaImage {
        val c = Canvas(FAR_SIZE, FAR_SIZE)
        broadTree(c, FAR_TREE_BROAD)
        coniferTree(c, FAR_TREE_CONIFER)
        return c.toImage(listOf(FAR_TREE_BROAD, FAR_TREE_CONIFER))
    }

    /** Der Atlas (RGBA). */
    fun image(): RgbaImage {
        val c = Canvas(SIZE, SIZE)
        broadSprig(c, BROAD)
        needleSpray(c, NEEDLE)
        skirt(c, SKIRT)
        broadTree(c, TREE_BROAD)
        coniferTree(c, TREE_CONIFER)
        grass(c, GRASS)
        mass(c, MASS)
        fern(c, FERN)
        return c.toImage(listOf(BROAD, NEEDLE, SKIRT, TREE_BROAD, TREE_CONIFER, GRASS, MASS, FERN))
    }

    // -----------------------------------------------------------------------
    // Leinwand: Deckung (A), Helligkeit (L, vormultipliziert) und Farbton (H)
    // -----------------------------------------------------------------------

    class Canvas(val w: Int, val h: Int) {
        val a = FloatArray(w * h)
        val l = FloatArray(w * h)
        val t = FloatArray(w * h)

        /** Malt Deckung [cov] mit Helligkeit [lum] und Farbton [hue] ueber das Bisherige. */
        fun paint(x: Int, y: Int, cov: Double, lum: Double, hue: Double) {
            if (cov <= 0.0 || x < 0 || y < 0 || x >= w || y >= h) return
            val i = y * w + x
            val c = cov.coerceAtMost(1.0).toFloat()
            a[i] = c + a[i] * (1 - c)
            l[i] = (lum * c).toFloat() + l[i] * (1 - c)
            t[i] = (hue * c).toFloat() + t[i] * (1 - c)
        }

        fun toImage(regions: List<Region>): RgbaImage {
            val img = RgbaImage(w, h)
            fun rgb(lum: Double, hue: Double): IntArray {
                val r = lum * (0.86 + 0.12 * hue)
                val g = lum * (1.0 + 0.02 * hue)
                val b = lum * (0.72 - 0.14 * hue)
                return intArrayOf(q(r), q(g), q(b))
            }
            // Mittlere Farbe je Motiv fuer die durchsichtigen Pixel.
            val fill = IntArray(w * h) { -1 }
            for (r in regions) {
                var sl = 0.0; var sh = 0.0; var n = 0.0
                for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) {
                    val i = y * w + x
                    if (a[i] > 0.5f) { sl += l[i] / a[i]; sh += t[i] / a[i]; n += 1.0 }
                }
                val c = if (n > 0) rgb(sl / n, sh / n) else intArrayOf(200, 210, 170)
                val col = Argb.of(0, c[0], c[1], c[2])
                for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) fill[y * w + x] = col
            }
            for (i in 0 until w * h) {
                val av = a[i]
                if (av < 1f / 255f) {
                    img.argb[i] = if (fill[i] != -1) fill[i] else Argb.of(0, 200, 210, 170)
                    continue
                }
                val c = rgb((l[i] / av).toDouble(), (t[i] / av).toDouble())
                img.argb[i] = Argb.of(ch(av.toDouble()), c[0], c[1], c[2])
            }
            return img
        }

        private fun ch(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()

        /** Farbkanal auf 6 Bit gerundet (PNG/Deflate packt das deutlich dichter; unsichtbar unter der Vertexfarbe). */
        private fun q(v: Double): Int = min(255, (ch(v) + 2) / 4 * 4)
    }

    private class Rnd(seed: Int) {
        private var k = 0
        private val s = seed
        fun next(): Double = Noise.hash01(k++, 0, s)
        fun range(a: Double, b: Double) = a + (b - a) * next()
    }

    /**
     * Ein Blatt: Ansatz (bx, by), Richtung [ang] (Bildkoordinaten, y nach unten),
     * Laenge [len], groesste Breite [wid]. Eine Haelfte heller (Licht), Mittelrippe dunkler.
     */
    private fun leaf(c: Canvas, bx: Double, by: Double, ang: Double, len: Double, wid: Double, lum: Double, hue: Double, rib: Boolean = true) {
        val dx = cos(ang)
        val dy = sin(ang)
        val r = max(len, wid) + 2
        val x0 = floor(bx - r).toInt(); val x1 = floor(bx + r).toInt() + 1
        val y0 = floor(by - r).toInt(); val y1 = floor(by + r).toInt() + 1
        for (y in y0..y1) for (x in x0..x1) {
            val px = x + 0.5 - bx
            val py = y + 0.5 - by
            val al = px * dx + py * dy
            val ac = -px * dy + py * dx
            val tt = al / len
            if (tt < -0.05 || tt > 1.05) continue
            val tc = tt.coerceIn(0.0, 1.0)
            val half = 0.5 * wid * sin(PI * tc).pow(0.75) * (1.0 - 0.2 * tc)
            // Abstand zum Rand (quer) und zu den Enden (laengs), in Pixeln.
            val d = max(abs(ac) - half, max(-al, al - len))
            val cov = (0.6 - d).coerceIn(0.0, 1.0)
            if (cov <= 0) continue
            var v = lum * (if (ac > 0) 1.06 else 0.86) * (0.92 + 0.08 * (1.0 - abs(ac) / (half + 0.5)))
            if (rib && abs(ac) < 0.7 && tc in 0.04..0.85) v *= 0.8
            c.paint(x, y, cov, v, hue)
        }
    }

    /** Linie mit Breite [w0] → [w1] (Zweig, Halm, Stamm). */
    private fun stroke(c: Canvas, x0: Double, y0: Double, x1: Double, y1: Double, w0: Double, w1: Double, lum: Double, hue: Double, shadeSide: Boolean = false) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        if (len2 <= 0) return
        val r = max(w0, w1) / 2 + 2
        val bx0 = floor(min(x0, x1) - r).toInt(); val bx1 = floor(max(x0, x1) + r).toInt() + 1
        val by0 = floor(min(y0, y1) - r).toInt(); val by1 = floor(max(y0, y1) + r).toInt() + 1
        val len = sqrt(len2)
        for (y in by0..by1) for (x in bx0..bx1) {
            val px = x + 0.5 - x0
            val py = y + 0.5 - y0
            val tt = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
            val qx = px - dx * tt
            val qy = py - dy * tt
            val dist = hypot(qx, qy)
            val half = (w0 + (w1 - w0) * tt) / 2
            val cov = (half + 0.5 - dist).coerceIn(0.0, 1.0)
            if (cov <= 0) continue
            val side = (-qx * dy + qy * dx) / len
            val v = if (shadeSide) lum * (if (side > 0) 1.1 else 0.8) else lum
            c.paint(x, y, cov, v, hue)
        }
    }

    // -----------------------------------------------------------------------
    // Motive
    // -----------------------------------------------------------------------

    private fun broadSprig(c: Canvas, r: Region) {
        val rnd = Rnd(0x5b01)
        val cx = r.x + r.w * 0.5
        val cy = r.y + r.h * 0.46
        val rad = r.w * 0.42
        val baseX = cx
        val baseY = r.y + r.h - 8.0
        // Zweige: Hauptzweig nach oben, Seitenzweige
        val twigs = ArrayList<DoubleArray>()
        twigs += doubleArrayOf(baseX, baseY, cx + 6, cy - rad * 0.7)
        for (k in 0 until 6) {
            val t = 0.2 + 0.12 * k
            val sx = baseX + (cx + 6 - baseX) * t
            val sy = baseY + (cy - rad * 0.7 - baseY) * t
            val side = if (k % 2 == 0) -1 else 1
            val ang = -PI / 2 + side * rnd.range(0.6, 1.1)
            val ln = rad * rnd.range(0.55, 0.85) * (1.0 - 0.3 * t)
            twigs += doubleArrayOf(sx, sy, sx + cos(ang) * ln, sy + sin(ang) * ln)
        }
        for ((k, tw) in twigs.withIndex()) stroke(c, tw[0], tw[1], tw[2], tw[3], if (k == 0) 3.6 else 2.0, 1.0, 0.34, 1.6)
        // Blaetter: hinten (dunkel) zuerst, dann nach vorne heller.
        class L(val x: Double, val y: Double, val a: Double, val len: Double, val w: Double, val lum: Double, val hue: Double)
        val leaves = ArrayList<L>()
        var tries = 0
        while (leaves.size < 240 && tries < 12000) {
            tries++
            val ang = rnd.next() * 2 * PI
            val rr = rad * sqrt(rnd.next())
            val lobe = 0.86 + 0.14 * sin(ang * 5 + 1.3)
            if (rr > rad * lobe * 0.95) continue
            val x = cx + cos(ang) * rr
            val y = cy + sin(ang) * rr * 0.95
            if (y > baseY - 14) continue
            // Blatt zeigt vom naechsten Zweigpunkt weg (nach aussen), etwas gestreut.
            val out = atan2(y - (cy + rad * 0.5), x - cx)
            val a = out + rnd.range(-0.7, 0.7)
            val len = rnd.range(17.0, 25.0)
            leaves += L(x - cos(a) * len * 0.35, y - sin(a) * len * 0.35, a, len, len * rnd.range(0.5, 0.62), 0.0, rnd.range(-0.35, 0.55))
        }
        val order = leaves.indices.map { it to rnd.next() }.sortedBy { it.second }
        for ((rank, p) in order.withIndex()) {
            val f = rank.toDouble() / order.size
            val lf = leaves[p.first]
            leaf(c, lf.x, lf.y, lf.a, lf.len, lf.w, 0.55 + 0.47 * f, lf.hue)
        }
    }

    private fun needleSpray(c: Canvas, r: Region) {
        val rnd = Rnd(0x5b02)
        val y0 = r.y + r.h * 0.5
        val x0 = r.x + 4.0
        val x1 = r.x + r.w - 6.0
        fun mainY(x: Double): Double = y0 + 10 * ((x - x0) / (x1 - x0)).pow(2.0) // Spitze haengt leicht
        // Nadeln entlang einer Achse (beide Seiten, nach vorn geneigt).
        fun needlesAlong(ax: Double, ay: Double, bx: Double, by: Double, nLen: Double, lumBase: Double) {
            val len = hypot(bx - ax, by - ay)
            val dx = (bx - ax) / len
            val dy = (by - ay) / len
            var s = 0.0
            while (s < len) {
                val px = ax + dx * s
                val py = ay + dy * s
                val k = 1.0 - 0.45 * (s / len)
                for (side in intArrayOf(-1, 1)) {
                    val tilt = side * rnd.range(0.75, 1.05)
                    val ca = cos(tilt); val sa = sin(tilt)
                    val ndx = dx * ca - dy * sa
                    val ndy = dx * sa + dy * ca
                    val l = nLen * k * rnd.range(0.8, 1.15)
                    val lum = lumBase * rnd.range(0.82, 1.12) * (if (side < 0) 1.06 else 0.9)
                    stroke(c, px, py, px + ndx * l, py + ndy * l, 2.1, 1.0, lum, rnd.range(-0.7, 0.1))
                }
                s += 1.6
            }
        }
        // Seitenzweige zuerst (dahinter), abwechselnd oben/unten, nach vorn geneigt.
        val sides = ArrayList<DoubleArray>()
        var x = x0 + 18
        var k = 0
        while (x < x1 - 30) {
            val t = (x - x0) / (x1 - x0)
            val up = if (k % 2 == 0) -1 else 1
            val ang = up * rnd.range(0.6, 0.95)
            val ln = r.h * 0.42 * (1.0 - 0.72 * t) * rnd.range(0.85, 1.1)
            val sx = x
            val sy = mainY(x)
            sides += doubleArrayOf(sx, sy, sx + cos(ang) * ln, sy + sin(ang) * ln)
            x += rnd.range(5.0, 6.5)
            k++
        }
        for (s in sides) {
            stroke(c, s[0], s[1], s[2], s[3], 2.0, 1.0, 0.3, 1.2)
            needlesAlong(s[0], s[1], s[2], s[3], 7.0, 0.74)
        }
        // Hauptachse
        var px = x0
        var py = mainY(x0)
        val steps = 24
        for (i in 1..steps) {
            val nx = x0 + (x1 - x0) * i / steps
            val ny = mainY(nx)
            stroke(c, px, py, nx, ny, 3.4 - 2.0 * i / steps, 3.4 - 2.0 * (i + 1) / steps, 0.32, 1.4)
            needlesAlong(px, py, nx, ny, 8.0 * (1.0 - 0.4 * i / steps), 0.86)
            px = nx; py = ny
        }
    }

    /** Signierter Abstand eines Klumpen-Umrisses (Kreise mit Rauschrand). */
    private fun lumpy(x: Double, y: Double, blobs: List<DoubleArray>, rough: Double, seed: Int): Pair<Double, Int> {
        var best = Double.MAX_VALUE
        var bi = -1
        for ((i, b) in blobs.withIndex()) {
            val d = hypot(x - b[0], y - b[1]) - b[2]
            if (d < best) { best = d; bi = i }
        }
        val n = (Noise.value(x / 5.0, y / 5.0, seed) - 0.5) * rough + (Noise.value(x / 2.2, y / 2.2, seed + 1) - 0.5) * rough * 0.6
        return (best + n) to bi
    }

    /** Laubmasse in einem Umriss: Helligkeit je Klumpen oben heller, Blattsprenkel. */
    private fun fillMass(c: Canvas, x0: Int, y0: Int, x1: Int, y1: Int, blobs: List<DoubleArray>, rough: Double, seed: Int, lumScale: Double = 1.0) {
        for (y in y0 until y1) for (x in x0 until x1) {
            val px = x + 0.5
            val py = y + 0.5
            val (d, bi) = lumpy(px, py, blobs, rough, seed)
            val cov = (0.5 - d).coerceIn(0.0, 1.0)
            if (cov <= 0) continue
            val b = blobs[bi]
            val up = ((b[1] - py) / b[2]).coerceIn(-1.0, 1.0)
            val leafy = Noise.value(px / 3.0, py / 3.0, seed + 7)
            val deep = (-d / b[2]).coerceIn(0.0, 1.0)
            val v = lumScale * (0.6 + 0.22 * up + 0.3 * leafy - 0.12 * deep)
            c.paint(x, y, cov, v, (leafy - 0.5) * 1.4)
        }
    }

    private fun broadTree(c: Canvas, r: Region) {
        val rnd = Rnd(0x5b03)
        val cx = r.x + r.w * 0.5
        stroke(c, cx, r.y + r.h - 2.0, cx, r.y + r.h * 0.42, 5.0, 3.0, 0.26, 1.6, shadeSide = true)
        stroke(c, cx, r.y + r.h * 0.6, cx - 22, r.y + r.h * 0.38, 2.6, 1.4, 0.26, 1.6)
        stroke(c, cx, r.y + r.h * 0.58, cx + 20, r.y + r.h * 0.34, 2.6, 1.4, 0.26, 1.6)
        val blobs = ArrayList<DoubleArray>()
        val ccy = r.y + r.h * 0.36
        for (k in 0 until 11) {
            val a = rnd.next() * 2 * PI
            val rr = sqrt(rnd.next())
            blobs += doubleArrayOf(cx + cos(a) * rr * r.w * 0.27, ccy + sin(a) * rr * r.h * 0.2, rnd.range(17.0, 27.0))
        }
        blobs += doubleArrayOf(cx, ccy, r.w * 0.33)
        fillMass(c, r.x + 4, r.y + 4, r.x + r.w - 4, r.y + r.h - 4, blobs, 9.0, 0x5b13)
    }

    private fun coniferTree(c: Canvas, r: Region) {
        val cx = r.x + r.w * 0.5
        val top = r.y + 5.0
        val bottom = r.y + r.h - 34.0
        stroke(c, cx, r.y + r.h - 2.0, cx, bottom - 20, 4.5, 3.0, 0.26, 1.4)
        val maxHalf = r.w * 0.4
        val tiers = 9.0
        for (y in r.y + 4 until r.y + r.h - 18) for (x in r.x + 4 until r.x + r.w - 4) {
            val py = y + 0.5
            val px = x + 0.5
            val t = ((py - top) / (bottom - top))
            if (t < 0) continue
            val tc = t.coerceAtMost(1.0)
            // Etagen: Rand haengt je Etage nach aussen-unten (Saegezahn), stetig verrauscht.
            val phase = (tc * tiers) % 1.0
            val tier = 0.7 + 0.3 * phase.pow(1.6)
            val half = maxHalf * tc.pow(0.92) * tier * (0.9 + 0.2 * Noise.value(py / 6.0, 0.5, 0x5b14)) + 1.5
            val dx = abs(px - cx)
            val rough = (Noise.value(px / 2.4, py / 2.4, 0x5b15) - 0.5) * 5.0
            var d = dx - half + rough
            if (t > 1.0) d = max(d, (py - bottom) * 2.0)
            val cov = (0.5 - d).coerceIn(0.0, 1.0)
            if (cov <= 0) continue
            val v = (0.52 + 0.34 * phase.pow(0.7) + 0.2 * Noise.value(px / 2.0, py / 2.0, 0x5b16))
            c.paint(x, y, cov, v, -0.5 + 0.6 * Noise.value(px / 9.0, py / 9.0, 0x5b18))
        }
    }

    private fun skirt(c: Canvas, r: Region) {
        val rnd = Rnd(0x5b07)
        // Rand: haengende Zweigspitzen (Wellen) entlang der Unterkante
        val tips = 9
        fun edge(x: Double): Double {
            val t = (x - r.x) / r.w * tips
            val ph = t - floor(t)
            val k = floor(t).toInt()
            val depth = 0.2 + 0.1 * Noise.hash01(k, 0, 0x5b37)
            // jede Spitze haengt in der Mitte tiefer
            return r.y + r.h - 3.0 - depth * r.h * (2 * ph - 1) * (2 * ph - 1)
        }
        for (y in r.y + 2 until r.y + r.h - 1) for (x in r.x + 1 until r.x + r.w - 1) {
            val px = x + 0.5
            val py = y + 0.5
            val bottom = edge(px) + (Noise.value(px / 3.0, py / 3.0, 0x5b38) - 0.5) * 7.0
            val side = min(px - r.x, r.x + r.w - px)
            val sideRough = 3.0 + (Noise.value(py / 4.0, px / 9.0, 0x5b39) - 0.2) * 9.0
            val d = max(py - bottom, sideRough - side)
            val top = (py - r.y) / r.h
            val holes = Noise.value(px / 5.0, py / 4.0, 0x5b3a)
            var cov = (0.5 - d).coerceIn(0.0, 1.0)
            // einzelne Luecken im mittleren Bereich (Licht zwischen den Zweigen), oben dicht
            if (top > 0.35 && holes < 0.2) cov *= ((holes - 0.12) / 0.08).coerceIn(0.0, 1.0)
            if (cov <= 0) continue
            val streak = Noise.value(px / 1.6, py / 7.0, 0x5b3b)
            val v = 0.5 + 0.28 * top + 0.3 * streak + 0.1 * Noise.value(px / 11.0, py / 11.0, 0x5b3c)
            c.paint(x, y, cov, v, -0.4 + 0.5 * Noise.value(px / 13.0, py / 13.0, 0x5b3d))
        }
        // Nadeln als kurze helle Striche nach aussen-unten
        repeat(900) {
            val x = r.x + rnd.range(4.0, r.w - 4.0)
            val y = r.y + rnd.range(4.0, r.h * 0.9)
            if (y > edge(x) - 3) return@repeat
            val a = PI / 2 + rnd.range(-0.9, 0.9)
            val l = rnd.range(4.0, 8.0)
            stroke(c, x, y, x + cos(a) * l, y + sin(a) * l, 1.6, 0.9, rnd.range(0.62, 1.05), rnd.range(-0.7, 0.1))
        }
    }

    private fun grass(c: Canvas, r: Region) {
        val rnd = Rnd(0x5b04)
        val base = r.y + r.h - 2.0
        repeat(95) {
            val bx = r.x + rnd.range(14.0, r.w - 14.0)
            val mid = 1.0 - abs((bx - r.x) / r.w - 0.5) * 1.4
            val height = (r.h - 10) * rnd.range(0.35, 1.0) * (0.55 + 0.45 * mid)
            val lean = rnd.range(-0.45, 0.45)
            val bend = rnd.range(-0.25, 0.25) + lean * 0.6
            val w0 = rnd.range(3.0, 5.2)
            val lum = rnd.range(0.62, 1.05)
            val hue = if (rnd.next() < 0.22) rnd.range(0.8, 1.8) else rnd.range(-0.6, 0.5)
            var px = bx
            var py = base
            val seg = 8
            for (i in 1..seg) {
                val t = i.toDouble() / seg
                val ang = -PI / 2 + lean + bend * t * t
                val nx = px + cos(ang) * height / seg
                val ny = py + sin(ang) * height / seg
                val shade = lum * (0.72 + 0.34 * t)
                stroke(c, px, py, nx, ny, w0 * (1 - (t - 1.0 / seg) * 0.85), w0 * (1 - t * 0.85) + 0.4, shade, hue)
                px = nx; py = ny
            }
        }
    }

    private fun mass(c: Canvas, r: Region) {
        val rnd = Rnd(0x5b05)
        val cx = r.x + r.w * 0.5
        val cy = r.y + r.h * 0.5
        val blobs = ArrayList<DoubleArray>()
        blobs += doubleArrayOf(cx, cy, r.w * 0.36)
        for (k in 0 until 7) {
            val a = k * 2 * PI / 7 + rnd.range(-0.3, 0.3)
            blobs += doubleArrayOf(cx + cos(a) * r.w * 0.22, cy + sin(a) * r.h * 0.22, rnd.range(17.0, 24.0))
        }
        fillMass(c, r.x + 3, r.y + 3, r.x + r.w - 3, r.y + r.h - 3, blobs, 10.0, 0x5b25)
        // Einzelne Blaetter am Rand und obenauf fuer eine blattartige Kante.
        repeat(46) {
            val a = rnd.next() * 2 * PI
            val rr = r.w * rnd.range(0.2, 0.42)
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr
            leaf(c, x, y, a + rnd.range(-0.6, 0.6), rnd.range(10.0, 15.0), rnd.range(5.0, 7.5), rnd.range(0.7, 1.02), rnd.range(-0.5, 0.8), rib = false)
        }
    }

    private fun fern(c: Canvas, r: Region) {
        val rnd = Rnd(0x5b06)
        val bx = r.x + r.w * 0.5
        val by = r.y + r.h - 3.0
        val fronds = 8
        for (k in 0 until fronds) {
            val spread = (k - (fronds - 1) / 2.0) / ((fronds - 1) / 2.0)
            val len = r.h * rnd.range(0.62, 0.86) * (1.0 - 0.2 * abs(spread))
            var ang = -PI / 2 + spread * 1.1
            val curl = spread * 0.5
            var px = bx
            var py = by
            val seg = 10
            val lum = rnd.range(0.7, 1.0)
            for (i in 1..seg) {
                val t = i.toDouble() / seg
                ang += curl / seg
                val nx = px + cos(ang) * len / seg
                val ny = py + sin(ang) * len / seg
                stroke(c, px, py, nx, ny, 2.0, 1.4, 0.45, 0.6)
                // Fiedern beidseitig, zur Spitze kuerzer.
                val pl = r.w * 0.16 * sin(PI * min(1.0, t * 1.15)).coerceAtLeast(0.15)
                if (t > 0.12) for (side in intArrayOf(-1, 1)) {
                    leaf(c, nx, ny, ang + side * 1.25, pl, pl * 0.42, lum * (if (side > 0) 1.0 else 0.85), rnd.range(-0.3, 0.6), rib = false)
                }
                px = nx; py = ny
            }
        }
    }

    // -----------------------------------------------------------------------
    // Kodierung: Grau + Alpha
    // -----------------------------------------------------------------------

    /** Der Atlas als fertige Textur (PNG Grau+Alpha), einmal je Prozess gerechnet. */
    val atlasTexture: EncodedTexture by lazy { EncodedTexture(encodeGreyAlpha(image()), "image/png", SIZE, SIZE) }

    /** Der Fern-Atlas als fertige Textur (PNG Grau+Alpha). */
    val farTexture: EncodedTexture by lazy { EncodedTexture(encodeGreyAlpha(farImage()), "image/png", FAR_SIZE, FAR_SIZE) }

    /**
     * PNG mit Farbtyp 4 (Grau + Alpha, 8 Bit): halb so viele Bytes wie RGBA und
     * dichter zu packen — die Farbe der Vegetation kommt aus den Eckpunkten, das Bild
     * traegt nur Helligkeit (Gruenkanal) und Ausschnitt. gltfio (stb_image, auf
     * RGBA erweitert) und Browser lesen das wie jedes PNG.
     */
    fun encodeGreyAlpha(img: RgbaImage): ByteArray {
        val w = img.width
        val h = img.height
        val stride = w * 2
        val raw = ByteArray((stride + 1) * h)
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        var rp = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = img.argb[y * w + x]
                cur[x * 2] = Argb.green(c).toByte()
                cur[x * 2 + 1] = Argb.alpha(c).toByte()
            }
            // Filter "Up" (2): Zeilen aehneln einander stark
            raw[rp++] = 2
            for (i in 0 until stride) raw[rp++] = (cur[i] - prev[i]).toByte()
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        val deflater = java.util.zip.Deflater(9)
        val packed = try {
            deflater.setInput(raw)
            deflater.finish()
            val bos = java.io.ByteArrayOutputStream(raw.size / 4 + 64)
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) bos.write(buf, 0, deflater.deflate(buf))
            bos.toByteArray()
        } finally {
            deflater.end()
        }
        val out = java.io.ByteArrayOutputStream(packed.size + 64)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun chunk(type: String, data: ByteArray) {
            out.write(be(data.size))
            val t = type.toByteArray(Charsets.US_ASCII)
            out.write(t)
            out.write(data)
            val crc = java.util.zip.CRC32()
            crc.update(t)
            crc.update(data)
            out.write(be(crc.value.toInt()))
        }
        chunk("IHDR", be(w) + be(h) + byteArrayOf(8, 4, 0, 0, 0))
        chunk("IDAT", packed)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    // -----------------------------------------------------------------------
    // Rinde (kachelnd)
    // -----------------------------------------------------------------------

    /**
     * Rinde 64 × 128, kachelnd: senkrechte Furchen (gestreckte Perioden-Rauschen), dunkle
     * Risse, hellere Borkenplatten; grau, die Farbe kommt aus den Eckpunkten. u laeuft einmal
     * um den Stamm, v 0,5 je Meter.
     */
    fun barkImage(): RgbaImage {
        val w = 64
        val h = 128
        val img = RgbaImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val furrow = Noise.value(x / 4.0, y / 22.0, 0xba01, w / 4).let { 1 - kotlin.math.abs(it - 0.5) * 2 }
            val plates = Noise.value(x / 8.0, y / 10.0, 0xba02, w / 8)
            val fine = Noise.value(x / 2.0, y / 4.0, 0xba03, w / 2)
            var v = 0.42 + 0.36 * plates + 0.12 * fine
            v *= 0.55 + 0.45 * smoothstep(0.15, 0.6, furrow)
            val c = (v.coerceIn(0.0, 1.0) * 255).toInt()
            img.argb[y * w + x] = Argb.of(255, c, c, (c * 0.94).toInt())
        }
        return img
    }

    // -----------------------------------------------------------------------
    // Kronendach der Waldkoerper (kachelnd)
    // -----------------------------------------------------------------------

    /** Kantenlaenge des Kronendachs in Pixeln. */
    const val CANOPY_SIZE = 256

    /** So viele Meter deckt eine Kachel des Kronendachs. */
    const val CANOPY_M = 30.0

    /**
     * Kronendach von oben/schraeg: dicht an dicht runde Kronen (5×5 je Kachel, also
     * gut 6 m), oben heller, in den Zwischenraeumen dunkel, Blattsprenkel. Kachelt
     * nahtlos (alles mit Periode). Voll deckend.
     */
    fun canopyImage(): RgbaImage {
        val n = CANOPY_SIZE
        val grid = 5
        val cell = n.toDouble() / grid
        val cx = DoubleArray(grid * grid)
        val cy = DoubleArray(grid * grid)
        val cr = DoubleArray(grid * grid)
        val ch = DoubleArray(grid * grid)
        for (j in 0 until grid) for (i in 0 until grid) {
            val k = j * grid + i
            cx[k] = (i + 0.2 + 0.6 * Noise.hash01(i, j, 0xca01)) * cell
            cy[k] = (j + 0.2 + 0.6 * Noise.hash01(i, j, 0xca02)) * cell
            cr[k] = cell * (0.62 + 0.22 * Noise.hash01(i, j, 0xca03))
            ch[k] = 0.85 + 0.3 * Noise.hash01(i, j, 0xca04)
        }
        val img = RgbaImage(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            var best = -1.0
            var shade = 0.32
            for (j in 0 until grid) for (i in 0 until grid) {
                val k = j * grid + i
                // naechste periodische Kopie
                var dx = x + 0.5 - cx[k]
                var dy = y + 0.5 - cy[k]
                dx -= n * Math.round(dx / n).toDouble()
                dy -= n * Math.round(dy / n).toDouble()
                val d = hypot(dx, dy) / cr[k]
                if (d >= 1.0) continue
                val dome = sqrt(1 - d * d) * ch[k]
                if (dome > best) {
                    best = dome
                    val lit = 0.5 + 0.5 * ((-dy * 0.8 - dx * 0.35) / cr[k])
                    shade = 0.36 + 0.42 * dome + 0.18 * lit
                }
            }
            val leafy = Noise.value(x / 4.0, y / 4.0, 0xca05, n / 4)
            val fine = Noise.hash01(x, y, 0xca06)
            val v = (shade * (0.82 + 0.3 * leafy) * (0.94 + 0.1 * fine)).coerceIn(0.0, 1.0)
            val c = (v * 255).toInt()
            img.argb[y * n + x] = Argb.of(255, min(255, (c * 0.92).toInt()), c, (c * 0.8).toInt())
        }
        return img
    }
}
