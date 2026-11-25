package de.trailscape.core.indoor.tree

import de.trailscape.core.indoor.world.Argb
import de.trailscape.core.indoor.world.RgbaImage
import de.trailscape.core.indoor.world.encodeJpegBaseline
import de.trailscape.core.indoor.world.encodePng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Texturen des Baum-Prototyps (alles im Code gezeichnet)
 *
 * **Laubatlas** 512² RGBA-PNG mit **kleinen** Blaettern (Buche: 14–26 px
 * lang auf einem Zweigbueschel von rund 1 m → 5–9 cm je Blatt, statt der
 * 0,3–0,5 m grossen „Blaetter" der heutigen 4,4-m-Karten). Die Aufteilung folgt
 * den Quadranten, aus denen `foliage.mat` das Windgewicht liest
 * (`windWeight`): links oben Laubzweig mit Ansatz unten (dort bleibt die Karte
 * stehen, die Spitze flattert), rechts oben Nadel- und Kiefernzweig
 * (gleichmaessiges Wiegen), links unten Birkenzweig (Ansatz unten fest).
 * Durchsichtige Pixel tragen die mittlere Farbe des Motivs (kein Saum in
 * gemittelten Mip-Stufen). Farbe: fast neutral mit leichter Farbtonstreuung je
 * Blatt; das Grün der Art kommt aus `COLOR_0` (Textur × Vertexfarbe).
 *
 * **Rindenatlas** 512² JPEG mit vier Spalten zu 128 px (glatt/Buche,
 * gefurcht/Eiche–Kiefer–Pappel, Birke, schuppig/Fichte), je Spalte waagrecht
 * und senkrecht kachelnd; v laeuft ueber die Stammlaenge (2 m je Bildhoehe).
 */
internal object TreeTextures {
    const val LEAF_SIZE = 512
    const val BARK_W = 512
    const val BARK_H = 512

    /**
     * Ein Motiv: Pixelrechteck (Zeilen von oben). [baseLeft]: Ansatz links, Spitze rechts
     * (sonst Ansatz unten, Spitze oben). [aspect] = Breite / Laenge der Karte.
     */
    class Region(val x: Int, val y: Int, val w: Int, val h: Int, val baseLeft: Boolean) {
        val aspect: Double = if (baseLeft) h.toDouble() / w else w.toDouble() / h

        /** u fuer Querlage [s] (0..1) und Laengslage [t] (0 Ansatz .. 1 Spitze). */
        fun u(s: Double, t: Double): Double = if (baseLeft) (x + t * w) / LEAF_SIZE.toDouble() else (x + s * w) / LEAF_SIZE.toDouble()
        fun v(s: Double, t: Double): Double = if (baseLeft) (y + h - s * h) / LEAF_SIZE.toDouble() else (y + h - t * h) / LEAF_SIZE.toDouble()
    }

    val BROAD = Region(0, 0, 256, 256, baseLeft = false)
    val NEEDLE = Region(256, 0, 256, 128, baseLeft = true)
    val PINE = Region(256, 128, 256, 128, baseLeft = true)
    val SMALL = Region(0, 256, 256, 256, baseLeft = false)

    fun region(m: LeafMotif): Region = when (m) {
        LeafMotif.BROAD -> BROAD
        LeafMotif.SMALL -> SMALL
        LeafMotif.NEEDLE -> NEEDLE
        LeafMotif.PINE -> PINE
    }

    val leafPng: ByteArray by lazy { encodePng(leafImage(), withAlpha = true) }
    val barkJpeg: ByteArray by lazy { encodeJpegBaseline(barkImage(), quality = 86) }

    // -----------------------------------------------------------------------
    // Laub
    // -----------------------------------------------------------------------

    /** Zeichenflaeche mit Deckung und Farbe (linear 0..1, vor dem Schreiben nach sRGB). */
    private class Canvas(val w: Int, val h: Int) {
        val a = FloatArray(w * h)
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)

        fun paint(x: Int, y: Int, cov: Double, cr: Double, cg: Double, cb: Double) {
            if (x !in 0 until w || y !in 0 until h || cov <= 0) return
            val i = y * w + x
            val c = cov.coerceIn(0.0, 1.0).toFloat()
            r[i] = r[i] * (1 - c) + cr.toFloat() * c
            g[i] = g[i] * (1 - c) + cg.toFloat() * c
            b[i] = b[i] * (1 - c) + cb.toFloat() * c
            a[i] = a[i] + (1 - a[i]) * c
        }
    }

    private class Rnd(seed: Long) {
        val t = TreeRandom(seed)
        fun next() = t.next()
        fun range(a: Double, b: Double) = t.range(a, b)
    }

    /**
     * Ein Blatt: Ansatz ([bx], [by]) in Pixeln, Winkel [ang] (0 = nach oben, im Bild y nach unten),
     * Laenge, groesste Breite, Helligkeit, Farbton (−1 blaeulich … +1 gelblich), [tipPow] Form.
     */
    private fun leaf(c: Canvas, bx: Double, by: Double, ang: Double, len: Double, wid: Double, lum: Double, hue: Double, tipPow: Double = 0.9) {
        val dx = sin(ang)
        val dy = -cos(ang)
        val ex = -dy
        val ey = dx
        val reach = len + 2
        val x0 = floor(min(bx, bx + dx * len) - wid - 2).toInt()
        val x1 = floor(max(bx, bx + dx * len) + wid + 2).toInt()
        val y0 = floor(min(by, by + dy * len) - wid - 2).toInt()
        val y1 = floor(max(by, by + dy * len) + wid + 2).toInt()
        for (y in y0..y1) for (x in x0..x1) {
            val px = x + 0.5 - bx
            val py = y + 0.5 - by
            val along = px * dx + py * dy
            val across = px * ex + py * ey
            if (along < 0 || along > reach) continue
            val t = along / len
            if (t > 1.02) continue
            // Umriss: breiteste Stelle bei 40 %, spitz zulaufend
            val hw = wid * 0.5 * (t.coerceIn(0.0, 1.0).pow(0.55) * (1 - t.coerceIn(0.0, 1.0)).pow(tipPow)) / (0.42.pow(0.55) * 0.58.pow(tipPow))
            val edge = hw - abs(across)
            val cov = (edge + 0.5).coerceIn(0.0, 1.0)
            if (cov <= 0) continue
            // Mittelrippe dunkler, eine Blatthaelfte etwas dunkler (Woelbung), zur Spitze heller
            val rib = if (abs(across) < 0.7) 0.82 else 1.0
            val fold = if (across > 0) 0.9 else 1.0
            val l = lum * rib * fold * (0.88 + 0.16 * t)
            c.paint(x, y, cov, l * (1 + 0.1 * hue), l, l * (1 - 0.2 * hue))
        }
    }

    /** Ein Zweigstueck von (x0, y0) nach (x1, y1), Breite w0 → w1, Farbe grau-braun. */
    private fun twig(c: Canvas, x0: Double, y0: Double, x1: Double, y1: Double, w0: Double, w1: Double, lum: Double) {
        val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        if (len < 0.5) return
        val dx = (x1 - x0) / len
        val dy = (y1 - y0) / len
        val xa = floor(min(x0, x1) - max(w0, w1) - 2).toInt()
        val xb = floor(max(x0, x1) + max(w0, w1) + 2).toInt()
        val ya = floor(min(y0, y1) - max(w0, w1) - 2).toInt()
        val yb = floor(max(y0, y1) + max(w0, w1) + 2).toInt()
        for (y in ya..yb) for (x in xa..xb) {
            val px = x + 0.5 - x0
            val py = y + 0.5 - y0
            val t = ((px * dx + py * dy) / len).coerceIn(0.0, 1.0)
            val qx = px - dx * t * len
            val qy = py - dy * t * len
            val d = sqrt(qx * qx + qy * qy)
            val hw = (w0 + (w1 - w0) * t) / 2
            val cov = (hw - d + 0.5).coerceIn(0.0, 1.0)
            c.paint(x, y, cov, lum * 1.08, lum, lum * 0.9)
        }
    }

    /**
     * Laubbueschel (Buche, Eiche, Obstbaum, Strauch): Zweig vom Ansatz unten Mitte, Seitenzweige
     * faechern ueber die ganze Breite, Blaetter in wechselnden Winkeln (ein Klumpen, kein Fiederwedel);
     * eine hintere, dunklere Blattlage fuellt den Umriss (Deckung im Inneren hoch, Rand gelappt).
     */
    private fun broadSpray(c: Canvas, ox: Int, oy: Int, w: Int, h: Int, seed: Long, leafLen: Double, leafWid: Double, droop: Double) {
        val rnd = Rnd(seed)
        val sub = Canvas(w, h)
        val bx = w / 2.0
        val by = h - 2.0
        // hintere Blattlage im ovalen Umriss (dunkler)
        val cx = w / 2.0
        val cy = h * 0.47
        for (i in 0 until 70) {
            val a = rnd.range(0.0, 2 * PI)
            val r = sqrt(rnd.next()) * 0.92
            val x = cx + cos(a) * r * w * 0.42
            val y = cy + sin(a) * r * h * 0.4
            leaf(sub, x, y, rnd.range(0.0, 2 * PI), leafLen * rnd.range(0.8, 1.1), leafWid * rnd.range(0.85, 1.1), rnd.range(0.45, 0.62), rnd.range(-1.0, 1.0))
        }
        // Zweige
        val segs = 10
        val main = ArrayList<DoubleArray>()
        var x = bx
        var y = by
        var ang = rnd.range(-0.12, 0.12)
        for (i in 0..segs) {
            main += doubleArrayOf(x, y, ang)
            val step = (h * 0.8) / segs
            x += sin(ang) * step
            y -= cos(ang) * step
            ang += rnd.range(-0.12, 0.12)
        }
        for (i in 0 until segs) {
            val a = main[i]
            val b = main[i + 1]
            twig(sub, a[0], a[1], b[0], b[1], 3.4 * (1 - i / segs.toDouble()) + 1.0, 3.4 * (1 - (i + 1) / segs.toDouble()) + 1.0, 0.3)
        }
        val front = ArrayList<DoubleArray>()
        var side = 1.0
        for (k in 2 until segs) {
            val p = main[k]
            val t = k / segs.toDouble()
            // Umriss oval: Seitenzweige in der Mitte am laengsten
            val sl = w * 0.46 * sin(PI * (0.15 + 0.85 * t)) * rnd.range(0.8, 1.1)
            val sa = p[2] + side * rnd.range(0.7, 1.35) - droop * 0.2 * side
            val ex = p[0] + sin(sa) * sl
            val ey = p[1] - cos(sa) * sl
            twig(sub, p[0], p[1], ex, ey, 1.9, 0.7, 0.32)
            val n = (sl / (leafLen * 0.38)).toInt().coerceAtLeast(2)
            for (j in 1..n) {
                val f = j / n.toDouble()
                val lx = p[0] + (ex - p[0]) * f
                val ly = p[1] + (ey - p[1]) * f
                val m = if (rnd.next() < 0.5) 1 else 2
                for (q in 0 until m) {
                    val la = sa + rnd.range(-1.5, 1.5)
                    front += doubleArrayOf(lx, ly, la, leafLen * rnd.range(0.75, 1.15), leafWid * rnd.range(0.8, 1.15), rnd.range(0.72, 1.0), rnd.range(-1.0, 1.0))
                }
            }
            front += doubleArrayOf(ex, ey, sa, leafLen, leafWid, rnd.range(0.8, 1.0), rnd.range(-1.0, 1.0))
            side = -side
        }
        for (k in 3..segs) {
            val p = main[k]
            front += doubleArrayOf(p[0], p[1], p[2] + rnd.range(-1.3, 1.3), leafLen * rnd.range(0.8, 1.1), leafWid, rnd.range(0.75, 1.0), rnd.range(-1.0, 1.0))
        }
        front.shuffle(java.util.Random(seed))
        for (l in front) leaf(sub, l[0], l[1], l[2], l[3], l[4], l[5], l[6])
        blit(c, sub, ox, oy)
    }

    /** Nadelzweig (Fichte): Ansatz links, Achse nach rechts, dicht benadelte Seitentriebe. */
    private fun needleSpray(c: Canvas, ox: Int, oy: Int, w: Int, h: Int, seed: Long, pine: Boolean) {
        val rnd = Rnd(seed)
        val sub = Canvas(w, h)
        val y0 = h / 2.0
        fun axisY(x: Double) = y0 + 6 * (x / w).pow(2.0)
        if (!pine) {
            // Fichte: Hauptachse, wechselstaendige Seitentriebe, kurze Nadeln rundum (flach gezeichnet)
            val xs = 4.0
            val xe = w - 6.0
            val shoots = ArrayList<DoubleArray>()
            var side = 1.0
            var x = xs + 18
            while (x < xe - 20) {
                val len = (h * 0.42) * (1 - 0.55 * (x / w)) * rnd.range(0.8, 1.1)
                val ang = side * rnd.range(0.7, 1.0)
                shoots += doubleArrayOf(x, axisY(x), x + cos(ang) * len * 0.9 + len * 0.35, axisY(x) + sin(ang) * len)
                side = -side
                x += rnd.range(14.0, 22.0)
            }
            fun needles(ax: Double, ay: Double, bx: Double, by: Double, nl: Double, lum: Double) {
                val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                val n = (len / 1.6).toInt()
                val dx = (bx - ax) / len
                val dy = (by - ay) / len
                for (i in 0..n) {
                    val f = i / n.toDouble()
                    val px = ax + (bx - ax) * f
                    val py = ay + (by - ay) * f
                    for (s2 in listOf(-1.0, 1.0)) {
                        val l = nl * rnd.range(0.7, 1.1) * (1 - 0.35 * f)
                        val ang = s2 * rnd.range(0.7, 1.15)
                        val ca = cos(ang)
                        val sa = sin(ang)
                        val nx = dx * ca - dy * sa
                        val ny = dx * sa + dy * ca
                        twig(sub, px, py, px + nx * l, py + ny * l, 1.5, 0.8, lum * rnd.range(0.8, 1.1))
                    }
                }
            }
            for (s in shoots) needles(s[0], s[1], s[2], s[3], 9.0, 0.78)
            needles(xs, axisY(xs), xe, axisY(xe), 12.0, 0.9)
            twig(sub, xs, axisY(xs), xe, axisY(xe), 3.0, 1.0, 0.28)
        } else {
            // Kiefer: duenner Zweig nach rechts, an den Enden Nadelbuesche (lange Nadeln, strahlig)
            val xs = 2.0
            val xe = w * 0.62
            twig(sub, xs, axisY(xs), xe, axisY(xe), 4.0, 2.0, 0.34)
            val tufts = listOf(doubleArrayOf(xe, axisY(xe), 1.0), doubleArrayOf(w * 0.4, axisY(w * 0.4) - 18, 0.75), doubleArrayOf(w * 0.45, axisY(w * 0.45) + 20, 0.75))
            twig(sub, w * 0.32, axisY(w * 0.32), w * 0.4, axisY(w * 0.4) - 18, 2.0, 1.4, 0.34)
            twig(sub, w * 0.36, axisY(w * 0.36), w * 0.45, axisY(w * 0.45) + 20, 2.0, 1.4, 0.34)
            for (t in tufts) {
                val n = (120 * t[2]).toInt()
                for (i in 0 until n) {
                    val ang = rnd.range(-1.25, 1.25) + 0.1 * (rnd.next() - 0.5)
                    val l = (w * 0.36) * t[2] * rnd.range(0.6, 1.0)
                    val sx = t[0] - 10 * t[2] + rnd.range(0.0, 14.0) * t[2]
                    val sy = t[1] + rnd.range(-3.0, 3.0)
                    twig(sub, sx, sy, sx + cos(ang) * l, sy + sin(ang) * l * 0.85, 1.6, 0.7, rnd.range(0.62, 1.0))
                }
            }
        }
        blit(c, sub, ox, oy)
    }

    /** Birkenzweig: duenne, haengende Zweige mit kleinen dreieckig-spitzen Blaettern, locker. */
    private fun birchSpray(c: Canvas, ox: Int, oy: Int, w: Int, h: Int, seed: Long) {
        val rnd = Rnd(seed)
        val sub = Canvas(w, h)
        val leaves = ArrayList<DoubleArray>()
        // vier Zweiglein vom Ansatz unten Mitte, auffaechernd
        for (k in 0 until 5) {
            var x = w / 2.0 + rnd.range(-6.0, 6.0)
            var y = h - 2.0
            var ang = -0.7 + 1.4 * k / 4 + rnd.range(-0.1, 0.1)
            val segs = 9
            val step = h * rnd.range(0.085, 0.1)
            for (i in 0 until segs) {
                val nx = x + sin(ang) * step
                val ny = y - cos(ang) * step
                twig(sub, x, y, nx, ny, 1.8 * (1 - i / segs.toDouble()) + 0.6, 1.6 * (1 - (i + 1) / segs.toDouble()) + 0.6, 0.3)
                for (s2 in listOf(-1.0, 1.0)) {
                    if (rnd.next() < 0.85) leaves += doubleArrayOf(nx, ny, ang + s2 * rnd.range(0.5, 1.1), rnd.range(11.0, 17.0), rnd.range(8.0, 11.0), rnd.range(0.72, 1.0), rnd.range(-1.0, 1.0))
                }
                x = nx; y = ny
                ang += rnd.range(-0.15, 0.15)
            }
        }
        leaves.shuffle(java.util.Random(seed))
        for ((i, l) in leaves.withIndex()) leaf(sub, l[0], l[1], l[2], l[3], l[4], l[5] * (if (i < leaves.size / 3) 0.75 else 1.0), l[6], tipPow = 1.3)
        blit(c, sub, ox, oy)
    }

    private fun blit(dst: Canvas, src: Canvas, ox: Int, oy: Int) {
        for (y in 0 until src.h) for (x in 0 until src.w) {
            val i = y * src.w + x
            val j = (oy + y) * dst.w + (ox + x)
            dst.a[j] = src.a[i]; dst.r[j] = src.r[i]; dst.g[j] = src.g[i]; dst.b[j] = src.b[i]
        }
    }

    fun leafImage(): RgbaImage {
        val c = Canvas(LEAF_SIZE, LEAF_SIZE)
        // Rand von 4 px je Motiv (kein Uebersprechen bis Mip-Stufe 2)
        broadSpray(c, BROAD.x + 4, BROAD.y + 4, BROAD.w - 8, BROAD.h - 8, 0xB10AD, leafLen = 24.0, leafWid = 12.0, droop = 0.0)
        needleSpray(c, NEEDLE.x + 4, NEEDLE.y + 4, NEEDLE.w - 8, NEEDLE.h - 8, 0x5EED1, pine = false)
        needleSpray(c, PINE.x + 4, PINE.y + 4, PINE.w - 8, PINE.h - 8, 0x913E, pine = true)
        birchSpray(c, SMALL.x + 4, SMALL.y + 4, SMALL.w - 8, SMALL.h - 8, 0xB12C4)
        val img = RgbaImage(LEAF_SIZE, LEAF_SIZE)
        for (reg in listOf(BROAD, NEEDLE, PINE, SMALL, Region(256, 256, 256, 256, false))) {
            // mittlere Farbe der gedeckten Pixel als Farbe der durchsichtigen
            var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0.0
            for (y in reg.y until reg.y + reg.h) for (x in reg.x until reg.x + reg.w) {
                val i = y * LEAF_SIZE + x
                if (c.a[i] > 0.5f) { sr += c.r[i]; sg += c.g[i]; sb += c.b[i]; n++ }
            }
            val mr = if (n > 0) sr / n else 0.7
            val mg = if (n > 0) sg / n else 0.7
            val mb = if (n > 0) sb / n else 0.7
            for (y in reg.y until reg.y + reg.h) for (x in reg.x until reg.x + reg.w) {
                val i = y * LEAF_SIZE + x
                val a = c.a[i].toDouble()
                // Kanten: Farbe mit der Mittelfarbe auffuellen (vormultipliziert gezeichnet)
                val cr = if (a > 0.01) c.r[i] / a else mr
                val cg = if (a > 0.01) c.g[i] / a else mg
                val cb = if (a > 0.01) c.b[i] / a else mb
                // Deckung steiler (Kante bleibt bei 0,5, Maske MASK 0,5), Farbe unvermischt
                val alpha = if (a >= 0.5) min(1.0, 0.5 + (a - 0.5) * 2.0) else a
                img[x, y] = Argb.of((alpha * 255 + 0.5).toInt().coerceIn(0, 255), srgb(cr), srgb(cg), srgb(cb))
            }
        }
        return img
    }

    /** Lineare Helligkeit 0..1 → sRGB-Byte. */
    private fun srgb(v: Double): Int {
        val l = v.coerceIn(0.0, 1.0)
        val s = if (l <= 0.0031308) l * 12.92 else 1.055 * l.pow(1 / 2.4) - 0.055
        return (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    // -----------------------------------------------------------------------
    // Rinde
    // -----------------------------------------------------------------------

    /** Kachelndes Wertrauschen mit Perioden [px], [py] (in Gitterzellen). */
    private fun vnoise(x: Double, y: Double, px: Int, py: Int, seed: Int): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val fx = x - xi
        val fy = y - yi
        fun h(i: Int, j: Int): Double {
            val a = Math.floorMod(i, px)
            val b = Math.floorMod(j, py)
            var k = a * 374761393 + b * 668265263 + seed * 1442695041
            k = (k xor (k ushr 13)) * 1274126177
            k = k xor (k ushr 16)
            return (k and 0xFFFFFF) / 16777215.0
        }
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = h(xi, yi) + (h(xi + 1, yi) - h(xi, yi)) * sx
        val b = h(xi, yi + 1) + (h(xi + 1, yi + 1) - h(xi, yi + 1)) * sx
        return a + (b - a) * sy
    }

    /** Zufallswert 0..1 einer Gitterzelle, kachelnd mit Perioden [px] × [py]. */
    private fun cellHash(i: Int, j: Int, px: Int, py: Int, seed: Int): Double {
        var k = Math.floorMod(i, px) * 374761393 + Math.floorMod(j, py) * 668265263 + seed * 1442695041
        k = (k xor (k ushr 13)) * 1274126177
        k = k xor (k ushr 16)
        return (k and 0xFFFFFF) / 16777215.0
    }

    /** Fraktal aus [oct] Oktaven, kachelnd mit Grundperioden [px] × [py]. */
    private fun fbm(x: Double, y: Double, px: Int, py: Int, seed: Int, oct: Int): Double {
        var s = 0.0
        var amp = 0.5
        var f = 1.0
        var norm = 0.0
        for (o in 0 until oct) {
            s += amp * vnoise(x * f, y * f, (px * f).toInt(), (py * f).toInt(), seed + o * 31)
            norm += amp
            amp *= 0.5
            f *= 2
        }
        return s / norm
    }

    fun barkImage(): RgbaImage {
        val img = RgbaImage(BARK_W, BARK_H)
        val cw = BARK_W / 4
        for (col in 0 until 4) for (y in 0 until BARK_H) for (xl in 0 until cw) {
            val u = xl / cw.toDouble() // 0..1 rundum
            val v = y / BARK_H.toDouble() // 0..1 ueber 2 m
            val (l, warm) = when (col) {
                0 -> smoothBark(u, v)
                1 -> furrowedBark(u, v)
                2 -> birchBark(u, v)
                else -> scalyBark(u, v)
            }
            val r = l * (1 + 0.06 * warm)
            val g = l
            val b = l * (1 - 0.1 * warm)
            img[col * cw + xl, y] = Argb.of(255, srgb(r), srgb(g), srgb(b))
        }
        return img
    }

    /** Buche: glatt, hellgrau, wolkig gefleckt, wenige feine Querrunzeln. */
    private fun smoothBark(u: Double, v: Double): Pair<Double, Double> {
        val m = fbm(u * 6, v * 12, 6, 12, 11, 4)
        val fine = fbm(u * 24, v * 96, 24, 96, 12, 2)
        var l = 0.55 + 0.25 * (m - 0.5) + 0.08 * (fine - 0.5)
        val wr = vnoise(u * 8, v * 40, 8, 40, 13)
        if (wr > 0.82) l *= 0.82
        return l.coerceIn(0.05, 1.0) to 0.2
    }

    /** Gefurcht (Eiche, Kiefer unten, Pappel, Obstbaum): senkrechte Borkenrippen, dunkle Furchen, Querrisse. */
    private fun furrowedBark(u: Double, v: Double): Pair<Double, Double> {
        val warp = (fbm(u * 4, v * 6, 4, 6, 21, 3) - 0.5) * 0.9
        val ridges = 7.0
        val ph = (u * ridges + warp) % 1.0
        val ridge = sin(PI * ((ph + 1) % 1.0)).pow(0.6)
        val cracks = vnoise(u * ridges * 2, v * 22, (ridges * 2).toInt(), 22, 23)
        var l = 0.12 + 0.55 * ridge
        if (cracks > 0.78 && ridge > 0.3) l *= 0.45
        l *= 0.85 + 0.3 * (fbm(u * 16, v * 48, 16, 48, 24, 2) - 0.5)
        return l.coerceIn(0.03, 1.0) to 0.6
    }

    /** Birke: weiss mit waagrechten dunklen Korkporen-Strichen und einzelnen dunklen Flecken. */
    private fun birchBark(u: Double, v: Double): Pair<Double, Double> {
        var l = 0.86 + 0.08 * (fbm(u * 6, v * 10, 6, 10, 31, 3) - 0.5)
        // Korkporen: kurze waagrechte Striche
        val band = (v * 70) % 1.0
        val row = floor(v * 70).toInt()
        val dash = vnoise(u * 10 + row * 0.37, row * 1.0, 10, 70, 32)
        if (band < 0.18 && dash > 0.68) l *= 0.25
        val patch = fbm(u * 3, v * 5, 3, 5, 33, 3)
        if (patch > 0.68) l *= 0.35 + 0.4 * (1 - (patch - 0.68) / 0.32)
        return l.coerceIn(0.03, 1.0) to 0.05
    }

    /** Fichte: rotbraune, schuppige Platten mit dunklen Fugen (Zellen). */
    private fun scalyBark(u: Double, v: Double): Pair<Double, Double> {
        val nx = 6
        val ny = 26
        val x = u * nx
        val y = v * ny
        var d1 = 9.0
        var d2 = 9.0
        var id = 0
        for (j in -1..1) for (i in -1..1) {
            val cx = floor(x) + i
            val cy = floor(y) + j
            val hx = cellHash(cx.toInt(), cy.toInt(), nx, ny, 41)
            val hy = cellHash(cx.toInt(), cy.toInt(), nx, ny, 42)
            val px = cx + 0.15 + 0.7 * hx
            val py = cy + 0.15 + 0.7 * hy
            val dx = (x - px) * 1.0
            val dy = (y - py) * 1.6
            val d = sqrt(dx * dx + dy * dy)
            if (d < d1) { d2 = d1; d1 = d; id = (Math.floorMod(cx.toInt(), nx) * 31 + Math.floorMod(cy.toInt(), ny)) } else if (d < d2) d2 = d
        }
        val edge = ((d2 - d1) * 4).coerceIn(0.0, 1.0)
        val plate = 0.5 + 0.2 * ((id * 0.618) % 1.0)
        val l = 0.1 + plate * edge.pow(0.6) * (0.9 + 0.2 * fbm(u * 20, v * 80, 20, 80, 43, 2))
        return l.coerceIn(0.03, 1.0) to 0.8
    }
}
