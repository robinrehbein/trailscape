package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Baum- und Bewuchsmodelle fuer [TreesLayer]
 *
 * Kronen sind **Blattkarten**: viele kleine, verdrehte Vierecke mit einem
 * Motiv aus dem [FoliageAtlas] (Laubzweig, Nadelzweig, Laubmasse) und
 * Alpha-Ausschnitt (`mat_tree_leaves`: `MASK`, doppelseitig). Stamm und — in
 * der feinsten Stufe — Hauptaeste sind Geometrie.
 *
 * **Licht.** Jede Karte zeigt mit ihrer Vorderseite vom Kronenmittelpunkt weg
 * (Reihenfolge der Ecken), und ihre Eckpunkt-Normalen sind zu rund 80 % die
 * Richtung vom Kronenmittelpunkt aus ("gebogene Normalen"): Die Krone wird wie
 * ein weicher Koerper beleuchtet statt Karte fuer Karte zu flimmern. Filament
 * (wie three.js) dreht bei doppelseitigen Materialien die Normale der
 * Rueckseite um; sichtbar sind fast nur Vorderseiten, Rueckseiten liegen auf
 * der abgewandten Kronenseite. Verschattung im Inneren und unten steckt in den
 * Vertexfarben (Baeume empfangen keine Schatten, `render.md`).
 *
 * **Arten:** [Species.BROAD] (Buche/Eiche/Linde, breit), [Species.TALL]
 * (Pappel/Birke, schlank), [Species.SMALL] (Feld-/Obstbaum), [Species.CONIFER]
 * (Fichte: Astquirle aus Nadelzweig-Karten um einen dunklen Kern),
 * [Species.PINE] (Kiefer: hoher kahler Stamm, Schirmkrone), [Species.BUSH]
 * (Strauch; dicht gesetzt ergibt das Hecken). Dazu Bodenbewuchs
 * ([grassTuft], [fernTuft]).
 *
 * **Stufen:** `NEAR` volle Karten (Laubbaum ≈ 150 Karten), `MID` wenige grosse
 * Laubmasse-Karten, `FAR` ein Dutzend Karten bzw. bei der Fichte ein
 * Kreuzbild. Die Dreieckszahl haengt nur von Art und Stufe ab ([cost]).
 * Alles um den Fusspunkt (0, 0, 0), glTF-Achsen (y oben).
 */
internal object TreeModels {
    enum class Species { BROAD, TALL, CONIFER, SMALL, PINE, BUSH }

    enum class Lod { FAR, MID, NEAR }

    /**
     * Ein Baum: Art, Hoehe, Breitenfaktor, Abtoenung, Zufall fuer Form und Drehung, heller Stamm;
     * [bare] = kahler Anteil des Stamms unter der Krone (Fichte im Bestand).
     */
    class Spec(
        val species: Species,
        val heightM: Double,
        val width: Double,
        val tint: Double,
        val seed: Int,
        val turn: Double,
        val pale: Boolean,
        val bare: Double = 0.06,
        /** Farbton der Krone −1 (blaeulich) … +1 (gelblich). */
        val hue: Double = 0.0,
    )

    /** Grundfarben der Kronen in sRGB (mit der hellen Atlas-Textur multipliziert). */
    private val LEAF = mapOf(
        Species.BROAD to intArrayOf(76, 124, 34),
        Species.TALL to intArrayOf(92, 138, 40),
        Species.CONIFER to intArrayOf(40, 88, 44),
        Species.SMALL to intArrayOf(84, 130, 36),
        Species.PINE to intArrayOf(58, 102, 44),
        Species.BUSH to intArrayOf(74, 122, 32),
    )

    /** Grundfarbe (sRGB) der Krone einer Art — fuer Kreuzbilder der Waldkoerper. */
    fun leafBase(species: Species): IntArray = LEAF.getValue(species)

    /** Baut den Baum [s] in Stufe [lod] in die Netze [trunk] (Rinde) und [crown] (Blattkarten). */
    fun build(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder, toward: Vec3 = Vec3(0.0, 0.0, 0.0)) {
        when (s.species) {
            Species.BROAD -> broadLike(s, lod, trunk, crown, Shape.BROAD, toward)
            Species.TALL -> broadLike(s, lod, trunk, crown, Shape.TALL, toward)
            Species.SMALL -> broadLike(s, lod, trunk, crown, Shape.SMALL, toward)
            Species.BUSH -> broadLike(s, lod, trunk, crown, Shape.BUSH, toward)
            Species.CONIFER -> spruce(s, lod, trunk, crown, toward)
            Species.PINE -> pine(s, lod, trunk, crown, toward)
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
    // Hilfen
    // -----------------------------------------------------------------------

    /** Wuerfel je Baum: dieselbe Folge fuer denselben Samen. */
    private class Rnd(private val seed: Int, private val salt: Int) {
        private var k = 0
        fun next(): Double = Noise.hash01(k++, salt, seed)
        fun range(a: Double, b: Double) = a + (b - a) * next()
        fun unit(): Vec3 {
            val z = range(-1.0, 1.0)
            val a = range(0.0, 2 * PI)
            val r = sqrt(max(0.0, 1 - z * z))
            return Vec3(r * cos(a), z, r * sin(a))
        }
    }

    /** Krone als Ellipsoid (Mitte, Radien) — Bezug fuer Normalen und Verschattung. */
    /**
     * [toward]: waagrechte Richtung zur Strecke (glTF-Achsen) oder null-Vektor. Die Kamera ist
     * immer auf der Strecke und tief; Normalen, die von ihr weg zeigen (N·V ≈ 0), bekommen in
     * Filament wie im Betrachter einen ueberhoehten, grauen Spiegelanteil (Fresnel/GGX bei
     * streifendem Blick) — die Krone wirkte "ausgebleicht".
     */
    private class Crown(val c: Vec3, val rx: Double, val ry: Double, val bottom: Double, val top: Double, val toward: Vec3 = Vec3(0.0, 0.0, 0.0)) {
        /** Normierte Lage (0 = Mitte, 1 = Rand). */
        fun rel(p: Vec3): Vec3 = Vec3((p.x - c.x) / rx, (p.y - c.y) / ry, (p.z - c.z) / rx)
    }

    /**
     * Eine Blattkarte: Ansatz [base], Richtung zum Ende [along] (Einheitsvektor),
     * quer [across] (Einheitsvektor), Laenge [len], Breite [wid], Motiv [reg].
     * [alongIsS]: Das Motiv laeuft im Atlas von links nach rechts (Nadelzweig),
     * sonst von unten nach oben. Vorderseite zeigt von der Kronenmitte weg.
     */
    private fun card(
        m: MeshBuilder, base: Vec3, along: Vec3, across: Vec3, length: Double, wid: Double,
        reg: FoliageAtlas.Region, alongIsS: Boolean, crown: Crown, leaf: IntArray, tint: Double, hue: Double,
        bend: Double = 0.8, inset: Double = 0.0, darkBase: Double = 0.88, clampTip: Boolean = true,
    ) {
        // Keine langen Kartenzungen aus der Krone: Spitze hoechstens knapp ueber der Kronenhuelle.
        var len = length
        if (clampTip) {
            var guard = 0
            while (crown.rel(base + along * len).length() > 1.12 && guard++ < 4) len *= 0.8
        }
        val plane = (across cross along).normalized()
        val fs = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        val gs = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
        val ps = Array(4) { base + along * (len * fs[it]) + across * (wid * 0.5 * gs[it]) }
        // Richtung vom Kronenmittelpunkt (unten etwas abgeflacht, damit die Kronenunterseite nicht
        // ganz ins Umgebungslicht von unten kippt).
        val radials = Array(4) {
            val r = crown.rel(ps[it])
            Vec3(r.x, if (r.y < 0) r.y * 0.6 else r.y, r.z).normalized(Vec3(0.0, 1.0, 0.0))
        }
        // Vorderseite dorthin, wohin die gebogenen Normalen im Mittel zeigen: Von hinten gesehen
        // dreht Filament die Normale um — sie zeigt dann vom Licht der Kronenoberseite weg (dunkel)
        // statt, wie bei einer falsch herum liegenden Karte, als helle Flaeche zur Sonne.
        val avg = radials[0] + radials[1] + radials[2] + radials[3]
        val front = (plane dot avg) >= 0
        val pn = if (front) plane else -plane
        val idx = IntArray(4)
        for (k in 0 until 4) {
            val f = fs[k]
            val g = gs[k]
            val p = ps[k]
            val rl = crown.rel(p).length()
            // Weicher Verlauf: kraeftiger Anteil nach oben (Himmelslicht auch auf der Schattenseite),
            // radial nur noch zur Haelfte — vorher zerfiel die Krone in ausgebleichte Sonnen- und
            // fast schwarze Schattenfetzen.
            val n = (pn * ((1 - bend) * 0.5) + radials[k] * (bend * 0.6) + crown.toward * 0.55 + Vec3(0.0, 0.25, 0.0)).normalized()
            // Verschattung: innen und unten dunkler, oben und aussen heller (geringe Spanne).
            val depth = rl.coerceIn(0.0, 1.15)
            val ht = ((p.y - crown.bottom) / max(0.5, crown.top - crown.bottom)).coerceIn(0.0, 1.0)
            var shade = tint * (0.66 + 0.22 * depth.pow(1.3) + 0.18 * ht)
            if (f == 0.0) shade *= darkBase
            val s = (if (alongIsS) f else (g + 1) / 2).coerceIn(0.0, 1.0)
            val t = (if (alongIsS) (g + 1) / 2 else f).coerceIn(0.0, 1.0)
            val u = reg.u(inset + s * (1 - 2 * inset))
            val v = reg.v(inset + t * (1 - 2 * inset))
            idx[k] = m.vertex(p.x, p.y, p.z, n.x, n.y, n.z, u, v, leafColor(leaf, shade, hue))
        }
        bothSides(m, idx)
    }

    /**
     * Beide Wicklungen auf denselben Eckpunkten: Das Material ist einseitig, je Blickrichtung
     * wird genau eine der beiden Flaechen gezeichnet (kein Z-Kampf), und beide tragen dieselbe
     * gebogene Normale. Damit haengt die Beleuchtung nicht von der Normalenumkehr doppelseitiger
     * Materialien ab (gl_FrontFacing), die Rueckseiten schwarz oder grell machte. Kostet nur
     * Indizes (6 × 2 Byte je Karte), keine Eckpunkte.
     */
    private fun bothSides(m: MeshBuilder, idx: IntArray) {
        m.quad(idx[0], idx[1], idx[2], idx[3])
        m.quad(idx[0], idx[3], idx[2], idx[1])
    }

    /** Ein Querachse senkrecht zu [a], zufaellig um [a] gedreht. */
    private fun perp(a: Vec3, angle: Double): Vec3 {
        val ref = if (abs(a.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val u = (ref cross a).normalized()
        val w = a cross u
        return (u * cos(angle) + w * sin(angle)).normalized()
    }

    /**
     * Vertexfarbe einer Karte: Grundfarbe × Helligkeit [factor], Farbton [hue]. Der Atlas ist grau
     * (nur Helligkeit), deshalb steckt hier auch dessen frueherer Gelbgruen-Stich (0,86 / 1 / 0,72).
     */
    private fun leafColor(srgb: IntArray, factor: Double, hue: Double = 0.0): Int {
        val f = factor.coerceIn(0.0, 1.5)
        val r = srgb[0] * f * 0.86 * (1 + 0.14 * hue)
        val g = srgb[1] * f * (1 + 0.04 * hue)
        val b = srgb[2] * f * 0.72 * (1 - 0.16 * hue)
        return lin(Argb.rgb(min(255, r.toInt()), min(255, g.toInt()), min(255, b.toInt())))
    }

    /**
     * Stamm- oder Aststueck mit Rinden-UV (u einmal rundum, v 0,5 je Meter fuer die kachelnde
     * Rindentextur) und am Fuss dunklerer Farbe (Bodennaehe, Schatten des Bestands).
     */
    private fun MeshBuilder.bark(p0: Vec3, p1: Vec3, r0: Double, r1: Double, seg: Int, cap0: Boolean, cap1: Boolean, color: Int) {
        val foot = if (p0.y < 1.0) scaleColor(color, 0.78) else color
        addLoft(listOf(p0, p1), doubleArrayOf(r0, r1), seg, cap0, cap1, color, intArrayOf(foot, color), 1.0, 0.5)
    }

    private fun scaleColor(c: Int, f: Double): Int =
        Argb.of(Argb.alpha(c), (Argb.red(c) * f).toInt(), (Argb.green(c) * f).toInt(), (Argb.blue(c) * f).toInt())

    /**
     * Stammradius (Brusthoehe) aus der Hoehe: [perM] je Meter plus Grundmass, hoechstens [max].
     * Vorher 2,4 % der Hoehe — bei Laserbaeumen bis 42 m wurden das Stammdurchmesser um 2 m.
     * Richtwerte: Buche 30 m ≈ 0,8 m Durchmesser, Fichte 30 m ≈ 0,6 m.
     */
    private fun trunkRadius(h: Double, perM: Double, max: Double): Double = (0.05 + perM * h).coerceAtMost(max)

    private fun barkColor(s: Spec): Int =
        if (s.pale) lin(Argb.rgb(168, 164, 154)) else lin(Argb.rgb(116, 102, 88))

    /** Stamm bis [top] mit Radius [r], in der feinsten Stufe mit Wurzelanlauf. */
    private fun stem(m: MeshBuilder, lod: Lod, r: Double, top: Double, color: Int) {
        val seg = when (lod) {
            Lod.NEAR -> 6
            Lod.MID -> 4
            Lod.FAR -> 3
        }
        if (lod == Lod.NEAR) {
            m.bark(Vec3(0.0, -0.3, 0.0), Vec3(0.0, 0.7, 0.0), r * 1.45, r * 1.05, seg, false, false, color)
        }
        m.bark(Vec3(0.0, -0.3, 0.0), Vec3(0.0, top, 0.0), r * 1.1, r * 0.55, seg, false, false, color)
    }

    // -----------------------------------------------------------------------
    // Laubbaeume und Straeucher
    // -----------------------------------------------------------------------

    private enum class Shape { BROAD, TALL, SMALL, BUSH }

    /** Kartenzahlen je Form und Stufe: (Zweigkarten, Massekarten innen, Massekarten aussen). */
    private fun counts(shape: Shape, lod: Lod): IntArray = when (lod) {
        Lod.NEAR -> when (shape) {
            Shape.BROAD -> intArrayOf(104, 16, 0)
            Shape.TALL -> intArrayOf(76, 12, 0)
            Shape.SMALL -> intArrayOf(66, 11, 0)
            Shape.BUSH -> intArrayOf(20, 4, 0)
        }
        Lod.MID -> when (shape) {
            Shape.BROAD -> intArrayOf(44, 4, 0)
            Shape.TALL -> intArrayOf(32, 3, 0)
            Shape.SMALL -> intArrayOf(28, 3, 0)
            Shape.BUSH -> intArrayOf(0, 2, 9)
        }
        Lod.FAR -> when (shape) {
            Shape.BROAD -> intArrayOf(0, 0, 8)
            Shape.TALL -> intArrayOf(0, 0, 7)
            Shape.SMALL -> intArrayOf(0, 0, 6)
            Shape.BUSH -> intArrayOf(0, 0, 4)
        }
    }

    private fun broadLike(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder, shape: Shape, toward: Vec3) {
        val rnd = Rnd(s.seed, 0x7a11 + shape.ordinal)
        val h = s.heightM
        val w = s.width
        // Kronenmasse je Form
        val cb: Double
        val rx: Double
        val ry: Double
        val cy: Double
        when (shape) {
            Shape.BROAD -> { cb = (if (s.bare > 0.1) s.bare else 0.3) * h; val ch = h - cb; rx = ch * 0.48 * w; ry = ch * 0.46; cy = cb + ch * 0.52 }
            Shape.TALL -> { cb = 0.22 * h; val ch = h - cb; rx = ch * 0.21 * w; ry = ch * 0.5; cy = cb + ch * 0.5 }
            Shape.SMALL -> { cb = 0.32 * h; val ch = h - cb; rx = ch * 0.62 * w; ry = ch * 0.48; cy = cb + ch * 0.5 }
            Shape.BUSH -> { cb = 0.0; rx = h * 0.62 * w; ry = h * 0.5; cy = h * 0.5 }
        }
        val cr = Crown(Vec3(0.0, cy, 0.0), rx, ry, cb, h, toward)
        val leaf = LEAF.getValue(when (shape) {
            Shape.BROAD -> Species.BROAD
            Shape.TALL -> Species.TALL
            Shape.SMALL -> Species.SMALL
            Shape.BUSH -> Species.BUSH
        })
        val trunkR = when (shape) {
            Shape.BROAD -> trunkRadius(h, 0.012, 0.42)
            Shape.TALL -> trunkRadius(h, 0.009, 0.3)
            Shape.SMALL -> trunkRadius(h, 0.02, 0.22)
            Shape.BUSH -> 0.0
        }
        if (shape != Shape.BUSH) stem(trunk, lod, trunkR, cy + (if (shape == Shape.TALL) ry * 0.5 else 0.0), barkColor(s))

        // Teilkronen (Klumpen) an den Enden der Hauptaeste
        val clumpCount = when (shape) {
            Shape.BROAD -> 5
            Shape.TALL -> 3
            Shape.SMALL -> 4
            Shape.BUSH -> 3
        }
        val clumps = ArrayList<Vec3>()
        for (k in 0 until clumpCount) {
            val c = when (shape) {
                Shape.TALL -> Vec3(rnd.range(-0.2, 0.2) * rx, cb + (h - cb) * (0.25 + 0.27 * k) + rnd.range(-0.05, 0.05) * ry, rnd.range(-0.2, 0.2) * rx)
                else -> {
                    if (k == clumpCount - 1) {
                        Vec3(rnd.range(-0.1, 0.1) * rx, cy + ry * 0.3, rnd.range(-0.1, 0.1) * rx)
                    } else {
                        val a = s.turn + k * 2 * PI / (clumpCount - 1) + rnd.range(-0.4, 0.4)
                        val o = rx * rnd.range(0.4, 0.55)
                        Vec3(cos(a) * o, cy + ry * rnd.range(-0.22, 0.12), -sin(a) * o)
                    }
                }
            }
            clumps += c
        }
        val clumpR = when (shape) {
            Shape.TALL -> rx * 0.95
            Shape.BUSH -> rx * 0.62
            else -> rx * 0.6
        }
        // Hauptaeste (nur nah): vom Stamm zu den Klumpen
        if (lod == Lod.NEAR && shape != Shape.BUSH) {
            for ((k, c) in clumps.withIndex()) {
                if (shape == Shape.TALL && k == 0) continue
                val from = Vec3(0.0, cb + (cy - cb) * rnd.range(0.15, 0.55), 0.0)
                val to = Vec3(c.x * 0.85, c.y - ry * 0.05, c.z * 0.85)
                trunk.bark(from, to, trunkR * 0.5, trunkR * 0.18, 4, false, false, barkColor(s))
            }
        }
        val (nTwig, nInner, nMass) = counts(shape, lod).let { Triple(it[0], it[1], it[2]) }
        val baseLen = when (shape) {
            Shape.BROAD -> rx * 0.58
            Shape.TALL -> rx * 0.85
            Shape.SMALL -> rx * 0.55
            Shape.BUSH -> rx * 0.78
        }
        fun clampY(p: Vec3, len: Double): Vec3 = if (shape == Shape.BUSH) Vec3(p.x, max(p.y, len * 0.35), p.z) else p
        fun envelope(p: Vec3, limit: Double): Vec3 {
            val r = cr.rel(p).length()
            return if (r <= limit) p else cr.c + (p - cr.c) * (limit / r)
        }
        // Zweigkarten (nah)
        for (i in 0 until nTwig) {
            val c = clumps[i % clumpCount]
            var d = rnd.unit()
            if (d.y < 0) d = Vec3(d.x, d.y * 0.6, d.z).normalized()
            val f = 0.42 + 0.58 * sqrt(rnd.next())
            val yScale = ry / rx
            var p = Vec3(c.x + d.x * clumpR * f, c.y + d.y * clumpR * f * yScale, c.z + d.z * clumpR * f)
            p = envelope(p, 1.04)
            val len = baseLen * rnd.range(0.8, 1.2) * (if (lod == Lod.MID) 1.35 else 1.0)
            p = clampY(p, len)
            val along = (d + rnd.unit() * 0.55 + Vec3(0.0, 0.35, 0.0)).normalized()
            val across = perp(along, rnd.range(0.0, 2 * PI))
            val base = p - along * (len * 0.45)
            val hue = s.hue + rnd.range(-0.35, 0.35)
            card(crown, base, along, across, len, len, FoliageAtlas.BROAD, false, cr, leaf, s.tint * rnd.range(0.9, 1.08), hue)
        }
        // Laubmasse innen (dunkel, gegen Durchblick)
        for (i in 0 until nInner) {
            val d = rnd.unit()
            // nicht durch die Kronenmitte (sonst zeigen die gebogenen Normalen einer Karte in
            // entgegengesetzte Richtungen), eher oben: von unten verdecken die Zweigkarten sie
            val dd = Vec3(d.x, abs(d.y) * 0.8 + 0.1, d.z).normalized()
            val p = cr.c + Vec3(dd.x * rx, dd.y * ry, dd.z * rx) * rnd.range(0.35, 0.58)
            val len = rx * rnd.range(0.6, 0.78)
            val along = (Vec3(0.0, 1.0, 0.0) + rnd.unit() * 0.6).normalized()
            val across = perp(along, rnd.range(0.0, 2 * PI))
            card(crown, clampY(p, len) - along * (len * 0.5), along, across, len, len, FoliageAtlas.MASS, false, cr, leaf, s.tint * 0.84, s.hue, bend = 0.9)
        }
        // Laubmasse aussen (mittel/fern): grosse Karten auf der Kronenschale
        for (i in 0 until nMass) {
            val c = if (lod == Lod.FAR) cr.c else clumps[i % clumpCount]
            val rr = if (lod == Lod.FAR) rx * 0.78 else clumpR
            var d = rnd.unit()
            if (d.y < 0) d = Vec3(d.x, d.y * 0.6, d.z).normalized()
            val f = 0.5 + 0.45 * sqrt(rnd.next())
            var p = Vec3(c.x + d.x * rr * f, c.y + d.y * rr * f * (ry / rx), c.z + d.z * rr * f)
            p = envelope(p, 1.0)
            val len = rx * (if (lod == Lod.FAR) rnd.range(0.95, 1.15) else rnd.range(0.62, 0.8)) * (if (shape == Shape.TALL) 1.4 else 1.0)
            p = clampY(p, len)
            val along = (d * 0.6 + rnd.unit() * 0.4 + Vec3(0.0, 0.5, 0.0)).normalized()
            val across = perp(along, rnd.range(0.0, 2 * PI))
            card(crown, p - along * (len * 0.5), along, across, len, len, FoliageAtlas.MASS, false, cr, leaf, s.tint * rnd.range(0.92, 1.06), s.hue + rnd.range(-0.25, 0.25), bend = 0.85, darkBase = 0.95)
        }
    }

    // -----------------------------------------------------------------------
    // Nadelbaeume
    // -----------------------------------------------------------------------

    /**
     * Fichte: Astquirle aus Nadelzweig-Karten (vom Stamm nach aussen, haengend,
     * um die Astachse verkippt, damit sie von der Seite nicht als Strich
     * erscheinen) um einen dunklen, schlanken Kern gegen Durchblick.
     */
    private fun spruce(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder, toward: Vec3) {
        val rnd = Rnd(s.seed, 0x7a21)
        val h = s.heightM
        val leaf = LEAF.getValue(Species.CONIFER)
        val bark = lin(Argb.rgb(108, 92, 78))
        val b0 = s.bare.coerceIn(0.04, 0.4) * h
        val span = h - b0
        val r0 = 0.21 * h * s.width
        fun env(y: Double): Double {
            val t = ((y - b0) / span).coerceIn(0.0, 1.0)
            return r0 * (1 - t).pow(0.92) * (0.82 + 0.18 * (1 - t))
        }
        val cr = Crown(Vec3(0.0, b0 + span * 0.38, 0.0), r0 * 0.8, span * 0.6, b0, h, toward)
        if (lod == Lod.FAR) {
            // Kreuzbild aus drei senkrechten Karten (60° versetzt) mit dem ganzen Baum
            for (k in 0 until 3) {
                val a = s.turn + k * PI / 3
                val across = Vec3(cos(a), 0.0, -sin(a))
                val wid = r0 * 2.3
                val base = Vec3(0.0, b0 - 0.02 * h, 0.0)
                crossCard(crown, base, across, h - b0 + 0.04 * h, wid, FoliageAtlas.TREE_CONIFER, 0.13, cr, leaf, s.tint * 1.04, s.hue)
            }
            return
        }
        val (tiers, perTier) = when (lod) {
            Lod.NEAR -> 10 to 7
            else -> 6 to 5
        }
        stem(trunk, lod, trunkRadius(h, 0.009, 0.32), 0.9 * h, bark)
        // Astquirle als gewoelbte Schuerzen: je Quirl ein Kranz schmaler Karten (oben am Stamm,
        // unten weit und haengend), in der Mitte nach aussen gewoelbt, je Karte eigene Neigung,
        // Weite und Hoehe — sonst wirkten die Quirle wie gestapelte Platten.
        val drop = 2.1 * span / tiers
        for (k in 0..tiers) {
            val apex = k == tiers
            val yb = if (apex) h - drop * 0.9 else b0 + (span - drop * 0.6) * k / tiers + rnd.range(-0.12, 0.12) * drop
            val yt = if (apex) h else yb + drop * 0.8
            val rb = if (apex) r0 * 0.12 + 0.15 else env(yb) * 1.04 + 0.15
            val rt = if (apex) 0.02 else rb * 0.3 + 0.08
            val n = if (apex) 4 else perTier
            val half = PI / n * 1.25
            for (j in 0 until n) {
                val a0 = s.turn + j * 2 * PI / n + k * 0.47 + rnd.range(-0.2, 0.2)
                val rr = rb * rnd.range(0.82, 1.12)
                val sag = rnd.range(-0.15, 0.2) * drop
                val tint = s.tint * rnd.range(0.92, 1.08) * (0.9 + 0.2 * k / tiers)
                skirt(crown, a0, half * rnd.range(0.85, 1.1), rt, rr, yt, yb - sag, rr * rnd.range(0.08, 0.16), cr, leaf, tint, s.hue + rnd.range(-0.15, 0.15))
            }
        }
    }

    /**
     * Eine gewoelbte Schuerzen-Karte um die Stammachse: Azimut [a0] ± [half], oben Radius [rt] in
     * Hoehe [yt], unten [rb] in [yb], in der Mitte um [bulge] nach aussen. Drei Zeilen Eckpunkte;
     * Oberseite hell (Normale nach aussen-oben), Unterseite mit eigenen, dunklen Eckpunkten
     * (Normale nach aussen-unten). Einseitiges Material, also je Seite eigene Wicklung.
     */
    private fun skirt(m: MeshBuilder, a0: Double, half: Double, rt: Double, rb: Double, yt: Double, yb: Double, bulge: Double, crown: Crown, leaf: IntArray, tint: Double, hue: Double) {
        val reg = FoliageAtlas.SKIRT
        val rows = doubleArrayOf(0.0, 0.5, 1.0) // 0 = unten (Rand), 1 = oben (Stamm)
        val top = IntArray(6)
        val bot = IntArray(6)
        for ((ri, t) in rows.withIndex()) {
            val r = rb + (rt - rb) * t + bulge * sin(PI * t)
            val y = yb + (yt - yb) * t + bulge * 0.4 * sin(PI * t)
            for (ci in 0..1) {
                val a = a0 + (if (ci == 0) -half else half)
                val p = Vec3(cos(a) * r, y, -sin(a) * r)
                val out = Vec3(cos(a0), 0.0, -sin(a0))
                val ht = ((p.y - crown.bottom) / max(0.5, crown.top - crown.bottom)).coerceIn(0.0, 1.0)
                val nUp = (out * 0.6 + crown.toward * 0.5 + Vec3(0.0, 0.35, 0.0)).normalized()
                val shade = tint * (0.72 + 0.22 * ht) * (1.0 - 0.25 * t)
                val u = reg.u(ci.toDouble())
                val v = reg.v(t)
                top[ri * 2 + ci] = m.vertex(p.x, p.y, p.z, nUp.x, nUp.y, nUp.z, u, v, leafColor(leaf, shade, hue))
                val nDown = (out * 0.6 + crown.toward * 0.5 + Vec3(0.0, -0.2, 0.0)).normalized()
                bot[ri * 2 + ci] = m.vertex(p.x, p.y, p.z, nDown.x, nDown.y, nDown.z, u, v, leafColor(leaf, tint * 0.55 * (1.0 - 0.2 * t), hue - 0.2))
            }
        }
        // Reihenfolge (aussen gesehen gegen den Uhrzeigersinn): unten links, unten rechts, oben rechts, oben links
        for (r in 0..1) {
            val a = r * 2
            val b = r * 2 + 1
            val c = r * 2 + 3
            val d = r * 2 + 2
            // Flaechennormale pruefen: soll nach aussen-oben zeigen
            val p0 = Vec3(m.x(top[a]).toDouble(), m.y(top[a]).toDouble(), m.z(top[a]).toDouble())
            val p1 = Vec3(m.x(top[b]).toDouble(), m.y(top[b]).toDouble(), m.z(top[b]).toDouble())
            val p3 = Vec3(m.x(top[d]).toDouble(), m.y(top[d]).toDouble(), m.z(top[d]).toDouble())
            val outward = ((p1 - p0) cross (p3 - p0)) dot Vec3(cos(a0), 0.4, -sin(a0)) >= 0
            if (outward) {
                m.quad(top[a], top[b], top[c], top[d]); m.quad(bot[a], bot[d], bot[c], bot[b])
            } else {
                m.quad(top[a], top[d], top[c], top[b]); m.quad(bot[a], bot[b], bot[c], bot[d])
            }
        }
    }

    /** Senkrechte Karte mit einem ganzen Baum ([reg] von unten nach oben), [inset] Rand im Motiv. */
    private fun crossCard(m: MeshBuilder, base: Vec3, across: Vec3, height: Double, wid: Double, reg: FoliageAtlas.Region, inset: Double, cr: Crown, leaf: IntArray, tint: Double, hue: Double) {
        card(m, base, Vec3(0.0, 1.0, 0.0), across, height, wid, reg, false, cr, leaf, tint, hue, bend = 0.75, inset = 0.0, darkBase = 0.75, clampTip = false)
    }

    /** Kiefer: hoher kahler Stamm (oben roetlich), Schirmkrone aus Nadelbueschel-Klumpen. */
    private fun pine(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder, toward: Vec3) {
        val rnd = Rnd(s.seed, 0x7a31)
        val h = s.heightM
        val leaf = LEAF.getValue(Species.PINE)
        val r = trunkRadius(h, 0.01, 0.34)
        val lower = lin(Argb.rgb(112, 96, 84))
        val upper = lin(Argb.rgb(150, 110, 82))
        val seg = if (lod == Lod.NEAR) 6 else if (lod == Lod.MID) 4 else 3
        if (lod == Lod.NEAR) trunk.bark(Vec3(0.0, -0.3, 0.0), Vec3(0.0, 0.6, 0.0), r * 1.8, r * 1.05, seg, false, false, lower)
        trunk.bark(Vec3(0.0, -0.3, 0.0), Vec3(0.0, 0.5 * h, 0.0), r * 1.1, r * 0.85, seg, false, false, lower)
        trunk.bark(Vec3(0.0, 0.5 * h, 0.0), Vec3(0.0, 0.88 * h, 0.0), r * 0.85, r * 0.45, seg, false, false, upper)
        val cy = 0.82 * h
        val rx = 0.2 * h * s.width
        val ry = 0.12 * h
        val cr = Crown(Vec3(0.0, cy, 0.0), rx, ry, 0.64 * h, h, toward)
        val clumps = ArrayList<Vec3>()
        val nClump = 5
        for (k in 0 until nClump) {
            if (k == nClump - 1) { clumps += Vec3(0.0, 0.93 * h, 0.0); continue }
            val a = s.turn + k * 2 * PI / (nClump - 1) + rnd.range(-0.4, 0.4)
            val o = rx * rnd.range(0.45, 0.7)
            clumps += Vec3(cos(a) * o, h * rnd.range(0.72, 0.86), -sin(a) * o)
        }
        if (lod == Lod.NEAR) {
            for (k in 0 until nClump - 1) {
                val c = clumps[k]
                trunk.bark(Vec3(0.0, c.y - 0.08 * h, 0.0), Vec3(c.x * 0.85, c.y - 0.01 * h, c.z * 0.85), r * 0.45, r * 0.2, 4, false, false, upper)
            }
        }
        val n = when (lod) {
            Lod.NEAR -> 52
            Lod.MID -> 16
            Lod.FAR -> 8
        }
        val cR = rx * 0.5
        for (i in 0 until n) {
            val c = clumps[i % nClump]
            var d = rnd.unit()
            d = Vec3(d.x, d.y * 0.55, d.z).normalized()
            val p = c + Vec3(d.x * cR, d.y * cR * 0.7, d.z * cR) * (0.4 + 0.6 * sqrt(rnd.next()))
            if (lod == Lod.NEAR) {
                val len = cR * rnd.range(1.0, 1.4)
                val along = (d + Vec3(0.0, 0.45, 0.0) + rnd.unit() * 0.4).normalized()
                val across = perp(along, rnd.range(0.0, 2 * PI))
                card(crown, p - along * (len * 0.45), along, across, len, len * 0.8, FoliageAtlas.NEEDLE, true, cr, leaf, s.tint * rnd.range(0.92, 1.1), s.hue, bend = 0.75, darkBase = 0.75)
            } else {
                val len = cR * (if (lod == Lod.FAR) 2.4 else 1.8)
                val along = (d * 0.5 + Vec3(0.0, 0.6, 0.0) + rnd.unit() * 0.3).normalized()
                val across = perp(along, rnd.range(0.0, 2 * PI))
                card(crown, p - along * (len * 0.5), along, across, len, len, FoliageAtlas.MASS, false, cr, leaf, s.tint * 0.95, s.hue, bend = 0.85, darkBase = 0.95)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Bodenbewuchs
    // -----------------------------------------------------------------------

    /** Dreiecke eines Bueschels ([grassTuft], [fernTuft]). */
    const val TUFT_TRIANGLES = 4

    /**
     * Grasbueschel um (x, y, z): zwei gekreuzte senkrechte Karten, unten etwas
     * eingesenkt. Normalen fast senkrecht (wie der Boden beleuchtet), unten dunkler.
     */
    fun grassTuft(m: MeshBuilder, x: Double, y: Double, z: Double, width: Double, height: Double, turn: Double, toward: Vec3, color: IntArray, tint: Double, hue: Double) =
        tuft(m, x, y, z, width, height, turn, toward, FoliageAtlas.GRASS, color, tint, hue, 0.72)

    /** Farn-/Krautbueschel (Waldboden, Waldrand). */
    fun fernTuft(m: MeshBuilder, x: Double, y: Double, z: Double, size: Double, turn: Double, toward: Vec3, color: IntArray, tint: Double, hue: Double) =
        tuft(m, x, y, z, size, size * 0.8, turn, toward, FoliageAtlas.FERN, color, tint, hue, 0.68)

    /**
     * [toward]: waagrechte Richtung zur Strecke — dorthin zeigt die Vorderseite
     * jeder Karte (die Rueckseite bekaeme in Filament die umgedrehte Normale und
     * waere dunkel; die Kamera ist immer auf der Strecke).
     */
    private fun tuft(m: MeshBuilder, x: Double, y: Double, z: Double, width: Double, height: Double, turn: Double, toward: Vec3, reg: FoliageAtlas.Region, color: IntArray, tint: Double, hue: Double, dark: Double) {
        for (k in 0 until 2) {
            val a = turn + k * PI / 2 + 0.3 * k
            val ax = cos(a)
            val az = -sin(a)
            val i0 = m.vertexCount
            val corners = arrayOf(doubleArrayOf(-1.0, 0.0), doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 1.0), doubleArrayOf(-1.0, 1.0))
            for (c in corners) {
                val px = x + ax * width * 0.5 * c[0]
                val pz = z + az * width * 0.5 * c[0]
                val py = y - 0.06 + (height + 0.06) * c[1]
                // Normale: ueberwiegend oben, leicht vom Bueschel weg geneigt
                val nx = ax * 0.25 * c[0]
                val nz = az * 0.25 * c[0]
                val n = Vec3(nx, 1.0, nz).normalized()
                val shade = tint * (if (c[1] == 0.0) dark else 1.0)
                m.vertex(px, py, pz, n.x, n.y, n.z, reg.u((c[0] + 1) / 2), reg.v(c[1]), leafColor(color, shade, hue))
            }
            // Flaechennormale der Ecken-Reihenfolge: Achse × oben = (−az, 0, ax)
            if (-az * toward.x + ax * toward.z >= 0) m.quad(i0, i0 + 1, i0 + 2, i0 + 3) else m.quad(i0, i0 + 3, i0 + 2, i0 + 1)
        }
    }

    /**
     * Kreuzbild eines ganzen Baums fuer Waldraender und Kronendach: [count]
     * senkrechte Karten um (x, y, z), Vorderseiten zur Strecke ([toward]).
     */
    fun billboardTree(m: MeshBuilder, x: Double, y: Double, z: Double, height: Double, width: Double, reg: FoliageAtlas.Region, turn: Double, toward: Vec3, color: IntArray, tint: Double, hue: Double, count: Int = 2, up: Double = 0.35) {
        val cy = y + height * 0.6
        for (k in 0 until count) {
            val a = turn + k * PI / count
            val ax = cos(a)
            val az = -sin(a)
            val i0 = m.vertexCount
            for (c in arrayOf(doubleArrayOf(-1.0, 0.0), doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 1.0), doubleArrayOf(-1.0, 1.0))) {
                val px = x + ax * width * 0.5 * c[0]
                val pz = z + az * width * 0.5 * c[0]
                val py = y - 0.3 + (height + 0.3) * c[1]
                // gebogene Normale: vom Kronenmittelpunkt weg, zur Strecke hin
                val n = Vec3(ax * c[0] * 0.5 + toward.x * 0.6, (py - cy) / height * 1.2 + up, az * c[0] * 0.5 + toward.z * 0.6).normalized()
                val shade = tint * (0.62 + 0.4 * c[1])
                m.vertex(px, py, pz, n.x, n.y, n.z, reg.u((c[0] + 1) / 2), reg.v(c[1]), leafColor(color, shade, hue))
            }
            if (-az * toward.x + ax * toward.z >= 0) m.quad(i0, i0 + 1, i0 + 2, i0 + 3) else m.quad(i0, i0 + 3, i0 + 2, i0 + 1)
        }
    }
}
