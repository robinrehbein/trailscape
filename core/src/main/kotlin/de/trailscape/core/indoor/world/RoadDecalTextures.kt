package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * # Texturen der Abziehbilder ([RoadDecals])
 *
 * Klein und erzeugt (keine Bilddateien), PNG mit Deckung (alle drei werden mit `alphaMode = MASK`,
 * Schwelle 0,5, gezeichnet — ausgefranste Stellen werden verworfen, nicht gemischt; siehe
 * [RouteTextures.roadEdgeTexture], warum Vertex-Deckung auf dem Gerät helle Säume gibt):
 *
 * | Textur | Größe | Inhalt |
 * |---|---|---|
 * | `route_marking` | 32 × 256 | Markierungsfarbe: gebrochenes Weiß, Schmutz, Glasperlen-Körnung; abgefahrene Stellen (Deckung 0) längs in Rollspuren und als Abplatzungen. `u` quer über den Strich, `v` 3 m längs |
 * | `route_crumble` | 64 × 256 | Übergang Asphalt → Bankett: innen ausgefranste Asphaltkante mit Ausbrüchen, außen eingestreuter Splitt und Asphaltbrocken auf dem Schotter. `u` quer (0 = 10 cm auf dem Asphalt, 1 = 24 cm auf dem Bankett), `v` 2 m |
 * | `route_decal` | 128 × 128 | Atlas: links unten Schachtdeckel (Guss, Rautenmuster, Rand), rechts oben Straßenablauf (Rost mit Querstäben im Rahmen) |
 *
 * Größe je Kachel, die sie einbettet: zusammen rund 30–45 KB (gemessen beim Bau, `docs/indoor/strassenausstattung.md`).
 */
internal object RoadDecalTextures {
    fun marking(cache: TextureCache): EncodedTexture = cache.get("route_marking", alpha = true) { markingImage() }
    fun crumble(cache: TextureCache): EncodedTexture = cache.get("route_crumble", alpha = true) { crumbleImage() }
    fun atlas(cache: TextureCache): EncodedTexture = cache.get("route_decal", alpha = true) { atlasImage() }

    private fun srgb(v: Double): Int = Argb.linearToSrgb8(v.coerceIn(0.0, 1.0).toFloat())
    private fun px(r: Double, g: Double, b: Double, a: Double): Int =
        Argb.of((a.coerceIn(0.0, 1.0) * 255 + 0.5).toInt(), srgb(r), srgb(g), srgb(b))

    /** Kachelnde Wertrauschen-Summe in 0..1 (Perioden in Zellen [px] × [py]). */
    private fun fbm(x: Double, y: Double, px: Int, py: Int, seed: Int, oct: Int = 3): Double {
        var sum = 0.0
        var amp = 0.5
        var fx = px
        var fy = py
        var norm = 0.0
        for (o in 0 until oct) {
            sum += amp * value(x * fx, y * fy, fx, fy, seed + o * 131)
            norm += amp
            amp *= 0.5
            fx *= 2
            fy *= 2
        }
        return sum / norm
    }

    /** Wertrauschen, periodisch in [perX] × [perY] (x, y in Zellen). */
    private fun value(x: Double, y: Double, perX: Int, perY: Int, seed: Int): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun h(i: Int, j: Int) = Noise.hash01(Math.floorMod(i, perX), Math.floorMod(j, perY), seed)
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * sx
        val b = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * sx
        return a + (b - a) * sy
    }

    internal fun markingImage(): RgbaImage {
        val w = 32
        val h = 256
        val img = RgbaImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + 0.5) / w
            val v = (y + 0.5) / h
            // Grundton: gebrochenes Weiss, grossflaechig verschmutzt.
            val dirt = fbm(u, v, 2, 16, 0x71)
            val grain = Noise.hash01(x, y, 0x72)
            var c = 0.80 - 0.16 * dirt + 0.08 * (grain - 0.5)
            // Glasperlen/Koernung: einzelne helle und dunkle Punkte.
            if (grain > 0.97) c += 0.12
            if (grain < 0.03) c -= 0.25
            // Abrieb: laengs gestreckte Fehlstellen (Rollspuren), dazu kleine Abplatzungen; an den Raendern weniger.
            val wear = fbm(u * 0.6, v, 3, 24, 0x73)
            val chips = Noise.hash01(x / 2, y / 3, 0x74)
            val edge = min(u, 1 - u)
            var alpha = 1.0
            if (wear > 0.70 - 0.25 * edge) alpha = 0.0
            if (chips > 0.985) alpha = 0.0
            // Weniger deckende Stellen bleiben sichtbar, aber grauer (Asphalt scheint durch die duenne Farbe).
            if (wear > 0.60 && alpha > 0) c -= 0.18 * (wear - 0.60) / 0.1
            img[x, y] = px(c, c * 0.985, c * 0.94, alpha)
        }
        return img
    }

    internal fun crumbleImage(): RgbaImage {
        val w = 64
        val h = 256
        val img = RgbaImage(w, h)
        // Lage der Asphaltkante quer (0..1) je Zeile: unregelmaessig, mit Ausbruechen.
        val edgeU = 0.10 / 0.34
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + 0.5) / w
            val v = (y + 0.5) / h
            val wob = (fbm(0.0, v, 1, 12, 0x81) - 0.5) * 0.22 + (fbm(0.0, v, 1, 48, 0x82) - 0.5) * 0.10
            val bite = fbm(u, v, 4, 32, 0x83)
            val edge = edgeU + wob - (if (bite > 0.72) 0.12 else 0.0)
            val grain = Noise.hash01(x, y, 0x84)
            var r: Double
            var g: Double
            var b: Double
            var a: Double
            if (u < edge) {
                // Asphalt (Farbe wie die Fahrbahn im Mittel), zur Kante hin heller/rauer.
                val k = 0.17 + 0.03 * (grain - 0.5) + 0.03 * smoothstep(edge - 0.12, edge, u)
                r = k; g = k; b = k * 1.01
                // Innen voll deckend — die Kante liegt unter der Fahrbahnoberflaeche hin aus; ganz innen ausblenden,
                // damit die Textur nicht als Streifen auf dem Asphalt endet.
                a = if (u < 0.06 && fbm(u, v, 4, 64, 0x85) > 0.35) 0.0 else 1.0
            } else {
                // Bankett: Splitt und Asphaltbrocken auf dem Schotter, nach aussen ausduennend.
                val outside = (u - edge) / max(0.05, 1 - edge)
                val stones = Noise.hash01(x / 2, y / 2, 0x86)
                val dense = 1.0 - outside
                val isChip = stones < 0.55 * dense * dense
                if (isChip) {
                    val dark = Noise.hash01(x / 2, y / 2, 0x87) < 0.45
                    val k = if (dark) 0.10 + 0.05 * grain else 0.26 + 0.12 * grain
                    r = k; g = k * 0.97; b = k * 0.92
                    a = 1.0
                } else {
                    r = 0.0; g = 0.0; b = 0.0; a = 0.0
                }
            }
            if (x == 0 || x == w - 1) a = 0.0
            img[x, y] = px(r, g, b, a)
        }
        return img
    }

    internal fun atlasImage(): RgbaImage {
        val s = 128
        val img = RgbaImage(s, s)
        val half = s / 2
        // Schachtdeckel: links unten (x 0..63, y 64..127).
        for (y in half until s) for (x in 0 until half) {
            val cx = half / 2.0
            val cy = half + half / 2.0
            val dx = x + 0.5 - cx
            val dy = y + 0.5 - cy
            val r = hypot(dx, dy) / (half / 2.0 - 0.5)
            if (r > 1.0) continue
            val grain = Noise.hash01(x, y, 0x91)
            var k: Double
            when {
                r > 0.88 -> k = 0.07 + 0.03 * grain // Rahmen (Guss, dunkel)
                r > 0.82 -> k = 0.02 // Fuge
                else -> {
                    // Rautenmuster aus erhabenen Stegen, Mitte mit zwei Griffloechern.
                    val a = atan2(dy, dx)
                    val p = (dx + dy) / 3.2
                    val q = (dx - dy) / 3.2
                    val rib = min(abs(p - floor(p) - 0.5), abs(q - floor(q) - 0.5)) < 0.12
                    k = if (rib) 0.17 + 0.05 * grain else 0.10 + 0.03 * grain
                    if (r in 0.30..0.42 && abs(abs(a) - PI / 2) < 0.18) k = 0.02
                }
            }
            // Rost und Schmutz.
            val rust = fbm(x / 64.0, y / 64.0, 4, 4, 0x92)
            img[x, y] = px(k * (1 + 0.5 * rust), k * (1 + 0.2 * rust), k, 1.0)
        }
        // Strassenablauf: rechts oben (x 64..127, y 0..63), Rahmen und Querstaebe.
        for (y in 0 until half) for (x in half until s) {
            val u = (x - half + 0.5) / half
            val v = (y + 0.5) / half
            val grain = Noise.hash01(x, y, 0x93)
            val frame = u < 0.08 || u > 0.92 || v < 0.06 || v > 0.94
            val k = if (frame) {
                0.09 + 0.03 * grain
            } else {
                // Querstaebe (quer zur Fahrtrichtung, also laengs `u`): sicher fuer Radreifen.
                val bar = ((v - 0.06) / 0.88 * 7).let { it - floor(it) } < 0.55
                if (bar) 0.13 + 0.04 * grain else 0.012
            }
            img[x, y] = px(k * 1.1, k, k * 0.95, 1.0)
        }
        return img
    }
}
