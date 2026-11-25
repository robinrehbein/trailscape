package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Dachbild aus dem Luftbild (Detailtreue)
 *
 * Bisher trugen alle Daecher eine Atlastextur (Pfanne, Blech, Bitumen) mit einer gewuerfelten oder grob
 * aus dem Luftbild abgeleiteten Farbe (fuenf Klassen). In echten Orten stimmte die Dachfarbe des
 * einzelnen Hauses deshalb oft nicht: Schiefer wurde Ziegelrot, Solardaecher rot, weisse Silos schwarz
 * (`docs/indoor/detailtreue.md`, Maengel 2 und 3).
 *
 * Jetzt bekommt ein Dach in Stufe 0 das **Luftbild der eigenen Kachel** als Textur — dieselbe Bildtextur
 * wie der Boden ([TileContext.aerialTexture]); die GLB legt das Bild nur einmal ab ([GlbScene.addTexture]
 * fasst gleiche Bytes und gleichen Sampler zusammen), es kostet also keinen Texturspeicher, nur einen
 * Zeichenaufruf je Kachel (Material [MAT_ROOF_AERIAL], gltfio-Standardmaterial, ohne Erweiterung).
 * Die UV sind die des Gelaendes (senkrechte Projektion, `u` nach Osten, `v` nach Sueden, halber Texel
 * Einzug), Solaranlagen, Oberlichter, Gruendaecher, Gauben und Flecken erscheinen so, wie sie sind.
 *
 * **Nicht genommen** (das Dach behaelt dann Atlas und Farbe wie bisher):
 *  * Dachpunkte mehr als [OVERHANG_M] ausserhalb der Kachel (das Bild endet an der Kachelgrenze);
 *  * Daecher, die im Luftbild ueberwiegend gruen sind ([GREEN_SHARE_MAX]) — meist Baumkronen ueber dem
 *    Dach oder ein Versatz zwischen Grundriss und Bild; Gruendaecher fallen damit leider auch heraus;
 *  * Daecher, deren Bild fast schwarz ist (Schlagschatten eines Hochhauses, [DARK_LUM_MIN]).
 *
 * **Doppelte Beleuchtung:** Im Luftbild ist die sonnenzugewandte Dachflaeche heller als die
 * abgewandte; der Renderer beleuchtet sie noch einmal. Je Dachflaeche (Gruppe gleicher Normale) wird
 * deshalb die Vertexfarbe auf das Verhaeltnis zur mittleren Dachhelligkeit gesetzt (nur abdunkeln,
 * hoechstens auf [MIN_SHADE]) — die Sonnenseite wird auf das Mittel gezogen, die Schattenseite bleibt.
 *
 * **Grenze:** Die amtlichen Orthofotos sind (ausser TrueDOP) auf das Gelaendemodell entzerrt; hohe
 * Gebaeude „kippen“ im Bild vom Bildmittelpunkt weg (bei 10 m Hoehe je nach Lage im Bild 0–3 m). Dann
 * liegt am Rand des Dachs ein Streifen Boden oder Wand. Fuer Gebaeude ueber [TALL_M] wird das Bild
 * deshalb nicht genommen.
 */
internal class AerialRoofs private constructor(
    private val ctx: TileContext,
    private val img: RgbaImage,
    private val texPx: Int,
    val material: TileMaterial,
) {
    private val spec = ctx.spec
    private val inset = 0.5 / texPx
    private val scale = 1.0 - 2 * inset

    /** Wie viele Daecher das Luftbild bekamen bzw. abgelehnt wurden (fuer Werkzeug und Tests). */
    var adopted = 0
        private set
    var rejected = 0
        private set

    /**
     * Haengt das Dachnetz [roof] (Koordinaten wie in der Kachel, siehe [TileContext.x]) mit Luftbild-UV
     * an das Netz, das [target] liefert, und gibt `true` zurueck; `false` (nichts angehaengt), wenn das
     * Luftbild fuer dieses Dach nicht taugt. [heightM] = Hoehe des Gebaeudes ueber Grund (Kippen im Bild).
     */
    fun adopt(roof: MeshBuilder, heightM: Double, target: () -> MeshBuilder): Boolean {
        if (roof.isEmpty) return false
        if (heightM > TALL_M) return reject()
        val n = roof.vertexCount
        val es = DoubleArray(n)
        val ns = DoubleArray(n)
        for (i in 0 until n) {
            val e = roof.positions[3 * i] + spec.anchorE
            val nn = spec.anchorN - roof.positions[3 * i + 2]
            if (e < spec.minE - OVERHANG_M || e > spec.maxE + OVERHANG_M || nn < spec.minN - OVERHANG_M || nn > spec.maxN + OVERHANG_M) return reject()
            es[i] = e
            ns[i] = nn
        }
        // Luftbild unter den Dachflaechen abtasten (Raster ~0,5 m in der Draufsicht), je Flaechengruppe.
        val tris = roof.triangleCount
        val group = IntArray(tris)
        val groupKeys = ArrayList<Int>()
        val lumSum = ArrayList<Double>()
        val cnt = ArrayList<Int>()
        var green = 0
        var dark = 0
        var samples = 0
        val idx = roof.indices
        for (t in 0 until tris) {
            val a = idx[3 * t]
            val b = idx[3 * t + 1]
            val c = idx[3 * t + 2]
            val key = normalKey(roof, a, b, c)
            var g = groupKeys.indexOf(key)
            if (g < 0) {
                g = groupKeys.size
                groupKeys += key; lumSum += 0.0; cnt += 0
            }
            group[t] = g
            forEachSample(es[a], ns[a], es[b], ns[b], es[c], ns[c]) { e, nn ->
                val px = pixel(e, nn)
                val r = Argb.SRGB_TO_LINEAR[Argb.red(px)].toDouble()
                val gg = Argb.SRGB_TO_LINEAR[Argb.green(px)].toDouble()
                val bb = Argb.SRGB_TO_LINEAR[Argb.blue(px)].toDouble()
                val lum = 0.2126 * r + 0.7152 * gg + 0.0722 * bb
                if (gg > r * 1.10 && gg > bb * 1.04 && lum > 0.02) green++
                if (lum < DARK_LUM_MIN) dark++
                lumSum[g] = lumSum[g] + lum
                cnt[g] = cnt[g] + 1
                samples++
            }
        }
        if (samples < 4) return reject()
        if (green > samples * GREEN_SHARE_MAX || dark > samples * 0.6) return reject()
        // Mittlere Helligkeit des Dachs; Flaechen heller als das Mittel werden abgedunkelt.
        var tot = 0.0
        var tc = 0
        for (g in groupKeys.indices) { tot += lumSum[g]; tc += cnt[g] }
        val mean = tot / max(1, tc)
        val shade = DoubleArray(groupKeys.size) { g ->
            if (cnt[g] < 3) 1.0 else (mean / max(1e-4, lumSum[g] / cnt[g])).coerceIn(MIN_SHADE, 1.0)
        }
        // Netz uebertragen: Eckpunkte je Flaechengruppe einzeln (die Vertexfarbe gilt je Flaeche).
        val m = target()
        val remap = HashMap<Long, Int>()
        for (t in 0 until tris) {
            val g = group[t]
            val col = if (shade[g] >= 0.999) Argb.WHITE else Argb.ofUnit(shade[g], shade[g], shade[g])
            val v = IntArray(3)
            for (k in 0..2) {
                val i = idx[3 * t + k]
                val key = (i.toLong() shl 20) or g.toLong()
                v[k] = remap.getOrPut(key) {
                    m.vertex(
                        roof.positions[3 * i].toDouble(), roof.positions[3 * i + 1].toDouble(), roof.positions[3 * i + 2].toDouble(),
                        roof.normals[3 * i].toDouble(), roof.normals[3 * i + 1].toDouble(), roof.normals[3 * i + 2].toDouble(),
                        uOf(es[i]), vOf(ns[i]), col,
                    )
                }
            }
            m.tri(v[0], v[1], v[2])
        }
        adopted++
        return true
    }

    private fun reject(): Boolean {
        rejected++
        return false
    }

    private fun uOf(e: Double) = inset + ((e - spec.minE) / spec.cellM).coerceIn(0.0, 1.0) * scale
    private fun vOf(n: Double) = inset + ((spec.maxN - n) / spec.cellM).coerceIn(0.0, 1.0) * scale

    private fun pixel(e: Double, n: Double): Int {
        val x = floor((e - spec.minE) / spec.cellM * img.width).toInt().coerceIn(0, img.width - 1)
        val y = floor((spec.maxN - n) / spec.cellM * img.height).toInt().coerceIn(0, img.height - 1)
        return img[x, y]
    }

    /** Gerundete Flaechennormale (aus den Eckpunkten), damit gleich geneigte Flaechen eine Gruppe bilden. */
    private fun normalKey(m: MeshBuilder, a: Int, b: Int, c: Int): Int {
        val p = m.positions
        val ux = p[3 * b] - p[3 * a]; val uy = p[3 * b + 1] - p[3 * a + 1]; val uz = p[3 * b + 2] - p[3 * a + 2]
        val vx = p[3 * c] - p[3 * a]; val vy = p[3 * c + 1] - p[3 * a + 1]; val vz = p[3 * c + 2] - p[3 * a + 2]
        var nx = (uy * vz - uz * vy).toDouble()
        var ny = (uz * vx - ux * vz).toDouble()
        var nz = (ux * vy - uy * vx).toDouble()
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-9) return 0
        nx /= l; ny /= l; nz /= l
        fun q(x: Double) = (floor(x * 4 + 0.5).toInt() + 8) and 0xF
        return (q(nx) shl 8) or (q(ny) shl 4) or q(nz)
    }

    /** Tastet das Dreieck in der Draufsicht etwa alle [SAMPLE_M] ab; kleine Dreiecke mindestens im Schwerpunkt. */
    private inline fun forEachSample(ae: Double, an: Double, be: Double, bn: Double, ce: Double, cn: Double, f: (Double, Double) -> Unit) {
        val area = abs((be - ae) * (cn - an) - (ce - ae) * (bn - an)) / 2
        if (area < SAMPLE_M * SAMPLE_M) {
            f((ae + be + ce) / 3, (an + bn + cn) / 3)
            return
        }
        val e0 = min(ae, min(be, ce)); val e1 = max(ae, max(be, ce))
        val n0 = min(an, min(bn, cn)); val n1 = max(an, max(bn, cn))
        val d = (be - ae) * (cn - an) - (ce - ae) * (bn - an)
        var nn = n0 + SAMPLE_M / 2
        while (nn < n1) {
            var e = e0 + SAMPLE_M / 2
            while (e < e1) {
                val w1 = ((be - e) * (cn - nn) - (ce - e) * (bn - nn)) / d
                val w2 = ((ce - e) * (an - nn) - (ae - e) * (cn - nn)) / d
                if (w1 >= 0 && w2 >= 0 && w1 + w2 <= 1) f(e, nn)
                e += SAMPLE_M
            }
            nn += SAMPLE_M
        }
    }

    companion object {
        /** Materialname der Daecher mit Luftbild (gltfio-Standardmaterial, `WorldMaterialRules` → UBER). */
        const val MAT_ROOF_AERIAL = "x_mat_roof_aerial"

        /** Hoechstens so weit (m) duerfen Dachpunkte ausserhalb der Kachel liegen (UV dort am Rand geklemmt). */
        const val OVERHANG_M = 1.0

        /** Hoechster Anteil gruener Proben (Kronen ueber dem Dach). */
        const val GREEN_SHARE_MAX = 0.35

        /** Lineare Helligkeit, unter der eine Probe als Schlagschatten zaehlt. */
        const val DARK_LUM_MIN = 0.006

        /** Staerkste Abdunklung einer sonnenzugewandten Flaeche. */
        const val MIN_SHADE = 0.6

        /** Ab dieser Gebaeudehoehe (m) kippt das Dach im Orthofoto zu weit vom Grundriss weg. */
        const val TALL_M = 22.0

        /** Abtastweite (m) in der Draufsicht. */
        const val SAMPLE_M = 0.5

        /** Fuer Kacheln der Stufe 0 mit Luftbild, sonst `null` (dann bleibt alles beim Atlas). */
        fun of(ctx: TileContext): AerialRoofs? {
            if (ctx.level != 0) return null
            val tex = ctx.aerialTexture ?: return null
            val img = ctx.aerialRaw ?: return null
            return AerialRoofs(ctx, img, tex.width, TileMaterial(MAT_ROOF_AERIAL, texture = tex, repeat = false, roughness = 0.85f))
        }
    }
}
