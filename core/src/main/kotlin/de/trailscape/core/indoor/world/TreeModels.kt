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
        /**
         * Variante der Wuchsform ([formOf]): −1 aus dem Samen gewuerfelt, 0 die erste, 1 die zweite
         * (Laubbaum: Eiche, schlanker Laubbaum: Saeulenpappel, kleiner Baum: Obstbaum).
         */
        val variant: Int = -1,
    )

    /** Grundfarben der Kronen in sRGB (mit der hellen Atlas-Textur multipliziert). */
    private val LEAF = mapOf(
        Species.BROAD to intArrayOf(88, 118, 50),
        Species.TALL to intArrayOf(100, 128, 56),
        Species.CONIFER to intArrayOf(52, 80, 52),
        Species.SMALL to intArrayOf(94, 122, 50),
        Species.PINE to intArrayOf(68, 92, 54),
        Species.BUSH to intArrayOf(86, 114, 46),
    )

    /** Grundfarbe (sRGB) der Krone einer Art — fuer Kreuzbilder der Waldkoerper. */
    fun leafBase(species: Species): IntArray = LEAF.getValue(species)

    /** Baut den Baum [s] in Stufe [lod] in die Netze [trunk] (Rinde) und [crown] (Blattkarten). */
    fun build(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder, toward: Vec3 = Vec3(0.0, 0.0, 0.0)) {
        when (val f = formOf(s)) {
            Form.SPRUCE -> spruce(s, lod, trunk, crown, toward)
            else -> leafy(s, f, lod, trunk, crown, toward)
        }
    }

    private val costs = HashMap<Pair<Form, Lod>, Int>()

    /**
     * Dreiecke des Baums [s] in Stufe [lod]: haengen nur von Wuchsform und Stufe ab
     * (Lappen-, Karten- und Kernzahlen sind je Form fest), nicht von Groesse oder Zufall.
     */
    fun cost(s: Spec, lod: Lod): Int = cost(formOf(s), lod)

    fun cost(form: Form, lod: Lod): Int = synchronized(costs) {
        costs.getOrPut(form to lod) {
            val t = MeshBuilder(64, 64)
            val c = MeshBuilder(256, 256)
            build(specOf(form), lod, t, c)
            t.triangleCount + c.triangleCount
        }
    }

    /** Hoechste Dreieckszahl ueber alle Formen einer Art (fuer Schaetzungen ohne Spec). */
    fun cost(species: Species, lod: Lod): Int = Form.entries.filter { speciesOf(it) == species }.maxOf { cost(it, lod) }

    private fun speciesOf(f: Form): Species = when (f) {
        Form.BEECH, Form.OAK -> Species.BROAD
        Form.ASH, Form.BIRCH, Form.POPLAR -> Species.TALL
        Form.FRUIT, Form.FIELD -> Species.SMALL
        Form.SPRUCE -> Species.CONIFER
        Form.PINE -> Species.PINE
        Form.BUSH -> Species.BUSH
    }

    /** Ein Muster-Baum der Form (fuer [cost]). */
    private fun specOf(f: Form): Spec = Spec(
        speciesOf(f), 12.0, 1.0, 1.0, 1, 0.0,
        pale = f == Form.BIRCH,
        variant = if (f == Form.OAK || f == Form.POPLAR || f == Form.FRUIT) 1 else 0,
    )

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
            var shade = tint * (0.58 + 0.2 * depth.pow(1.3) + 0.16 * ht)
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

    /** Laengste Zweigkarte (nah; mittel × 1,35) und Massekarte in Metern. */
    private const val TWIG_MAX_M = 3.0
    private const val MASS_MAX_M = 4.5
    private const val FAR_MASS_MAX_M = 6.5

    private fun barkColor(s: Spec): Int =
        if (s.pale) lin(Argb.rgb(214, 210, 200)) else lin(Argb.rgb(170, 152, 132))

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
    // Wuchsformen
    // -----------------------------------------------------------------------

    /**
     * Wuchsform — feiner als [Species], aus Art, [Spec.pale] und [Spec.variant]:
     * [BEECH] Buche/Linde/Ahorn (gewoelbte, geschlossene Krone), [OAK] Eiche (breit,
     * unregelmaessig, Luecken mit Aesten), [ASH] hoher schlanker Laubbaum (Esche,
     * Hainbuche, Bergahorn im Bestand), [BIRCH] Birke (lockere, haengende Krone,
     * weisser Stamm mit dunklen Baendern), [POPLAR] Saeulenpappel, [FRUIT]
     * Obstbaum (kurzer Stamm, breite flache Krone, kraeftige Gerueststaemme),
     * [FIELD] Feldgehoelz (Feldahorn, Weissdorn), [SPRUCE] Fichte, [PINE] Kiefer,
     * [BUSH] Strauch.
     */
    enum class Form { BEECH, OAK, ASH, BIRCH, POPLAR, FRUIT, FIELD, SPRUCE, PINE, BUSH }

    fun formOf(s: Spec): Form = when (s.species) {
        Species.BROAD -> if (s.variant == 1 || (s.variant < 0 && Noise.hash01(s.seed, 3, 0x0a4) < 0.3)) Form.OAK else Form.BEECH
        Species.TALL -> when {
            s.pale -> Form.BIRCH
            s.variant == 1 -> Form.POPLAR
            else -> Form.ASH
        }
        Species.SMALL -> if (s.variant == 1 || (s.variant < 0 && Noise.hash01(s.seed, 5, 0x0a5) < 0.5)) Form.FRUIT else Form.FIELD
        Species.CONIFER -> Form.SPRUCE
        Species.PINE -> Form.PINE
        Species.BUSH -> Form.BUSH
    }

    /** Ein Kronenlappen: Mitte, Halbachse waagrecht [rh] und senkrecht [rv]. */
    private class Lobe(val c: Vec3, val rh: Double, val rv: Double) {
        /** Normierter Abstand von der Lappenmitte (1 = Oberflaeche). */
        fun rel(p: Vec3): Double {
            val d = p - c
            return sqrt((d.x * d.x + d.z * d.z) / (rh * rh) + d.y * d.y / (rv * rv))
        }
    }

    /** Lappen- und Kartenzahlen einer Form je Stufe. */
    private class Plan(val lobes: Int, val cards: Int, val coreLon: Int, val coreLat: Int)

    private fun plan(form: Form, lod: Lod): Plan = when (lod) {
        Lod.NEAR -> when (form) {
            Form.BEECH -> Plan(7, 18, 0, 0)
            Form.OAK -> Plan(8, 15, 0, 0)
            Form.ASH -> Plan(6, 15, 0, 0)
            Form.BIRCH -> Plan(7, 13, 0, 0)
            Form.POPLAR -> Plan(6, 14, 0, 0)
            Form.FRUIT -> Plan(6, 14, 0, 0)
            Form.FIELD -> Plan(5, 14, 0, 0)
            Form.BUSH -> Plan(3, 12, 0, 0)
            Form.PINE -> Plan(4, 13, 0, 0)
            Form.SPRUCE -> Plan(0, 0, 0, 0)
        }
        Lod.MID -> when (form) {
            Form.BEECH, Form.OAK -> Plan(5, 16, 5, 3)
            Form.ASH, Form.BIRCH, Form.POPLAR, Form.FRUIT -> Plan(4, 14, 5, 3)
            Form.FIELD -> Plan(4, 11, 4, 3)
            Form.BUSH -> Plan(2, 7, 4, 3)
            Form.PINE -> Plan(3, 8, 4, 3)
            Form.SPRUCE -> Plan(0, 0, 0, 0)
        }
        Lod.FAR -> when (form) {
            Form.BEECH, Form.OAK -> Plan(4, 8, 4, 3)
            Form.ASH, Form.BIRCH, Form.POPLAR, Form.FRUIT -> Plan(3, 8, 4, 3)
            Form.FIELD, Form.PINE -> Plan(3, 6, 4, 3)
            Form.BUSH -> Plan(2, 4, 4, 3)
            Form.SPRUCE -> Plan(0, 0, 0, 0)
        }
    }

    /** Groesste Kantenlaenge einer Laubkarte je Stufe (Meter). */
    private fun cardMax(lod: Lod): Double = when (lod) {
        Lod.NEAR -> 4.4
        Lod.MID -> 2.7
        Lod.FAR -> 4.8
    }

    /**
     * Krone einer Form: Huelle (Mitte, Radien), Kronenansatz und die Lappen. Masse
     * aus der Hoehe (bei Laserbaeumen gemessen) und dem Breitenfaktor (aus dem
     * gemessenen Kronenradius), Kronenansatz je Form bzw. aus [Spec.bare].
     */
    private fun crownOf(s: Spec, form: Form, nLobes: Int, rnd: Rnd, toward: Vec3): Pair<Crown, List<Lobe>> {
        val h = s.heightM
        val w = s.width
        val cbF = when (form) {
            Form.BEECH -> if (s.bare > 0.1) s.bare else 0.26
            Form.OAK -> if (s.bare > 0.1) s.bare else 0.24
            Form.ASH -> if (s.bare > 0.1) s.bare + 0.04 else 0.3
            Form.BIRCH -> 0.3
            Form.POPLAR -> 0.07
            Form.FRUIT -> 0.32
            Form.FIELD -> 0.2
            Form.BUSH -> 0.0
            Form.PINE -> 0.5
            Form.SPRUCE -> 0.0
        }
        val cb = cbF * h
        val ch = h - cb
        val (rxF, ryF) = when (form) {
            Form.BEECH -> 0.47 to 0.5
            Form.OAK -> 0.56 to 0.47
            Form.ASH -> 0.3 to 0.5
            Form.BIRCH -> 0.27 to 0.5
            Form.POPLAR -> 0.15 to 0.5
            Form.FRUIT -> 0.64 to 0.44
            Form.FIELD -> 0.52 to 0.5
            Form.BUSH -> 0.62 to 0.5
            Form.PINE -> 0.52 to 0.42
            Form.SPRUCE -> 0.2 to 0.5
        }
        val rx = (if (form == Form.POPLAR) h else ch) * rxF * w
        val ry = ch * ryF
        val cy = cb + ch * (if (form == Form.FRUIT || form == Form.OAK) 0.52 else 0.5)
        val crown = Crown(Vec3(0.0, cy, 0.0), rx, ry, cb, h, toward)
        val lobes = ArrayList<Lobe>()
        val stacked = form == Form.POPLAR
        if (form == Form.ASH || form == Form.BIRCH) {
            // lockere Wolke im schlanken Eiförmigen: Lappen auf Fibonacci-Richtungen, oben einer
            val ph = rnd.next()
            lobes += Lobe(Vec3(0.0, cy + ry * 0.5, 0.0), rx * 0.62, ry * 0.4)
            for (k in 0 until nLobes - 1) {
                val d = fib(k, nLobes - 1, ph)
                val r = rnd.range(0.38, 0.55)
                val c = Vec3(d.x * rx * r, cy + d.y * ry * r * 1.15, d.z * rx * r)
                val taper = 1.0 - 0.25 * abs(d.y)
                lobes += Lobe(c, rx * 0.6 * taper * rnd.range(0.9, 1.1), ry * 0.42 * rnd.range(0.9, 1.1))
            }
        } else if (stacked) {
            // Saeule: ein durchgehender Koerper, darauf unregelmaessige Wuelste
            lobes += Lobe(Vec3(0.0, cy + ry * 0.05, 0.0), rx * 0.92, ry * 0.98)
            for (k in 0 until nLobes - 1) {
                val t = (k + 0.5) / (nLobes - 1)
                val y = cy + ry * (-0.7 + 1.35 * t)
                val a = s.turn + k * 2.4 + rnd.range(-0.5, 0.5)
                val taper = sqrt(max(0.1, 1 - (2 * t - 1) * (2 * t - 1)))
                val off = rx * 0.55 * taper
                lobes += Lobe(Vec3(cos(a) * off, y, -sin(a) * off), rx * 0.5 * rnd.range(0.85, 1.1), ry * 0.3 * rnd.range(0.85, 1.1))
            }
        } else if (form == Form.PINE) {
            // flache Wolken im oberen Drittel, gegeneinander versetzt (Schirmkrone)
            for (k in 0 until nLobes) {
                val top = k == nLobes - 1
                val a = s.turn + k * 2 * PI / max(1, nLobes - 1) + rnd.range(-0.5, 0.5)
                val off = if (top) rx * 0.1 else rx * rnd.range(0.35, 0.6)
                val y = if (top) h - ch * 0.25 else cb + ch * rnd.range(0.3, 0.62)
                lobes += Lobe(Vec3(cos(a) * off, y, -sin(a) * off), rx * rnd.range(0.52, 0.66), ch * rnd.range(0.22, 0.3))
            }
        } else {
            // Kuppel: ein Lappen oben, die uebrigen abwechselnd im oberen und unteren Kranz
            val ring = nLobes - 1
            val f = when (form) {
                Form.OAK -> 0.56
                Form.FRUIT -> 0.6
                Form.BUSH -> 0.68
                else -> 0.66
            }
            lobes += Lobe(Vec3(rnd.range(-0.1, 0.1) * rx, cy + ry * 0.42, rnd.range(-0.1, 0.1) * rx), rx * f * 1.05, ry * f * 1.05)
            for (k in 0 until ring) {
                val a = s.turn + k * 2 * PI / ring + rnd.range(-0.35, 0.35)
                val upper = k % 2 == 0
                val off = rx * (if (form == Form.OAK) rnd.range(0.48, 0.62) else rnd.range(0.38, 0.5))
                val dy = if (form == Form.BUSH) rnd.range(-0.25, 0.05) else if (upper) rnd.range(0.0, 0.22) else rnd.range(-0.42, -0.24)
                val ff = f * rnd.range(0.85, 1.08)
                val ly = if (form == Form.BUSH) ry * ff * 0.8 + rnd.range(0.0, 0.2) * ry else cy + ry * dy
                lobes += Lobe(Vec3(cos(a) * off, ly, -sin(a) * off), rx * ff, ry * ff * (if (form == Form.FRUIT) 0.9 else 1.0))
            }
        }
        return crown to lobes
    }

    /**
     * Kronenradius (aeussere Huelle der Lappen, bei Breite 1) und Kronenansatz je Meter Baumhoehe —
     * fuer die Laserbaeume ([laserTreeSpec]), die aus dem gemessenen Kronenradius die Breite rechnen.
     * Muss zu [crownOf] passen: Radius ≈ 1,1 × Huellradius (die Lappen ragen etwas ueber die Huelle).
     */
    fun crownFactors(form: Form): Pair<Double, Double> = when (form) {
        Form.BEECH -> 0.38 to 0.26
        Form.OAK -> 0.47 to 0.24
        Form.ASH -> 0.23 to 0.3
        Form.BIRCH -> 0.21 to 0.3
        Form.POPLAR -> 0.15 to 0.07
        Form.FRUIT -> 0.48 to 0.32
        Form.FIELD -> 0.46 to 0.2
        Form.BUSH -> 0.62 to 0.0
        Form.PINE -> 0.29 to 0.5
        Form.SPRUCE -> 0.22 to 0.06
    }

    /** Farbe der Krone einer Form (sRGB, mit dem grauen Atlas multipliziert). */
    private fun formLeaf(form: Form): IntArray = when (form) {
        Form.BEECH -> intArrayOf(86, 120, 48)
        Form.OAK -> intArrayOf(80, 106, 46)
        Form.ASH -> intArrayOf(92, 122, 54)
        Form.BIRCH -> intArrayOf(112, 138, 58)
        Form.POPLAR -> intArrayOf(96, 124, 56)
        Form.FRUIT -> intArrayOf(104, 130, 54)
        Form.FIELD -> intArrayOf(90, 118, 50)
        Form.BUSH -> intArrayOf(84, 112, 46)
        Form.PINE -> intArrayOf(70, 96, 58)
        Form.SPRUCE -> intArrayOf(52, 80, 52)
    }

    /**
     * Laubbaum, Strauch und Kiefer aus **Kronenlappen**: Jeder Lappen ist ein
     * Ellipsoid, auf dessen Oberflaeche Laubkarten **tangential** liegen
     * (Vorderseite nach aussen, nur diese Wicklung) — von jeder Seite und von
     * oben eine geschlossene, laubige Flaeche statt radial abstehender „Federn".
     * In jedem Lappen sitzt ein dunkler, deckender Kern (wenige Dreiecke), der
     * den Durchblick auf den Himmel schliesst; durch Luecken zwischen den Karten
     * sieht man ihn als Schatten im Kroneninnern. Nah fuehren Hauptaeste vom
     * Stamm zu den Lappen.
     */
    private fun leafy(s: Spec, form: Form, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder, toward: Vec3) {
        val rnd = Rnd(s.seed, 0x7b11 + form.ordinal)
        val p = plan(form, lod)
        val (cr, lobes) = crownOf(s, form, p.lobes, rnd, toward)
        val h = s.heightM
        val leaf = formLeaf(form)
        val bark = barkColorOf(s, form)
        val seg = when (lod) {
            Lod.NEAR -> 7
            Lod.MID -> 5
            Lod.FAR -> 3
        }
        // Stamm
        when (form) {
            Form.BUSH -> Unit
            Form.BIRCH -> birchStem(trunk, lod, trunkRadius(h, 0.008, 0.26), 0.86 * h, rnd, seg)
            Form.PINE -> {
                val r = trunkRadius(h, 0.01, 0.34)
                val upper = lin(Argb.rgb(196, 148, 110))
                if (lod == Lod.NEAR) trunk.bark(Vec3(0.0, -0.3, 0.0), Vec3(0.0, 0.6, 0.0), r * 1.7, r * 1.05, seg, false, false, bark)
                val lean = Vec3(rnd.range(-0.03, 0.03) * h, 0.0, rnd.range(-0.03, 0.03) * h)
                trunk.addLoft(listOf(Vec3(0.0, -0.3, 0.0), Vec3(0.0, 0.45 * h, 0.0) + lean * 0.4, Vec3(0.0, 0.9 * h, 0.0) + lean), doubleArrayOf(r * 1.1, r * 0.8, r * 0.4), seg, false, false, bark, intArrayOf(scaleColor(bark, 0.8), bark, upper), 1.0, 0.5)
            }
            else -> {
                val r = when (form) {
                    Form.OAK -> trunkRadius(h, 0.016, 0.55)
                    Form.FRUIT -> trunkRadius(h, 0.024, 0.2)
                    Form.POPLAR -> trunkRadius(h, 0.011, 0.4)
                    Form.ASH -> trunkRadius(h, 0.01, 0.36)
                    Form.FIELD -> trunkRadius(h, 0.016, 0.22)
                    else -> trunkRadius(h, 0.013, 0.45)
                }
                val top = when (form) {
                    Form.POPLAR, Form.ASH -> cr.c.y + cr.ry * 0.55
                    Form.FRUIT -> cr.bottom + 0.15 * (cr.top - cr.bottom)
                    else -> cr.c.y
                }
                if (lod == Lod.NEAR) trunk.bark(Vec3(0.0, -0.3, 0.0), Vec3(0.0, 0.7, 0.0), r * 1.5, r * 1.04, seg, false, false, bark)
                trunk.bark(Vec3(0.0, -0.3, 0.0), Vec3(0.0, top, 0.0), r * 1.08, r * (if (form == Form.FRUIT) 0.8 else 0.55), seg, false, false, bark)
                // Hauptaeste zu den Lappen (nah alle, mittel nur die unteren — sie sind durch Luecken zu sehen)
                if (lod != Lod.FAR) {
                    for ((k, lb) in lobes.withIndex()) {
                        if (k == 0 && form != Form.POPLAR) continue
                        if (lod == Lod.MID && (lb.c.y > cr.c.y || form == Form.POPLAR)) continue
                        val fromY = if (form == Form.FRUIT) top - 0.1 else cr.bottom + (lb.c.y - cr.bottom) * rnd.range(0.2, 0.5)
                        val from = Vec3(0.0, max(cr.bottom * 0.9, fromY), 0.0)
                        val to = Vec3(lb.c.x * 0.9, lb.c.y + lb.rv * 0.1, lb.c.z * 0.9)
                        val mid = from.lerp(to, 0.5) + Vec3(0.0, (to - from).length() * 0.12, 0.0)
                        val rb = r * (if (form == Form.FRUIT || form == Form.OAK) 0.62 else 0.45)
                        trunk.addLoft(listOf(from, mid, to), doubleArrayOf(rb, rb * 0.6, rb * 0.25), if (lod == Lod.NEAR) 5 else 3, false, false, bark, null, 1.0, 0.5)
                    }
                }
            }
        }
        // Kerne
        val coreTint = s.tint * (if (form == Form.BIRCH || form == Form.ASH) 0.92 else 0.82)
        for (lb in lobes) core(crown, lb, cr, p.coreLon, p.coreLat, leaf, coreTint, s.hue - 0.1)
        // Karten
        val maxCard = cardMax(lod)
        val droop = when (form) {
            Form.BIRCH -> 0.45
            Form.POPLAR -> 0.1
            else -> 0.0
        }
        // Karten nach Oberflaeche der Lappen verteilt (Summe fest: die Dreieckszahl haengt nur von der Form ab)
        fun areaOf(lb: Lobe) = 4 * PI * (lb.rh * lb.rh * 2 + lb.rv * lb.rh) / 3
        val totalArea = lobes.sumOf { areaOf(it) }
        val total = p.cards * lobes.size
        val counts = IntArray(lobes.size) { max(2, (total * areaOf(lobes[it]) / totalArea).toInt()) }
        while (counts.sum() > total) counts[counts.indices.maxBy { counts[it] }]--
        while (counts.sum() < total) counts[counts.indices.maxBy { areaOf(lobes[it]) / counts[it] }]++
        for ((li, lb) in lobes.withIndex()) {
            val nCards = counts[li]
            val area = areaOf(lb)
            val size = sqrt(area * 2.8 / nCards).coerceIn(0.5, maxCard)
            val lobeHue = s.hue + rnd.range(-0.3, 0.3)
            val lobeTint = s.tint * rnd.range(0.93, 1.07)
            val phase = rnd.next()
            for (i in 0 until nCards) {
                // Fibonacci-Punkte auf dem Lappen, verdeckte (in einem anderen Lappen) neu gewuerfelt
                var d = fib(i, nCards, phase)
                var q = surface(lb, d, if (lod == Lod.NEAR) rnd.range(0.84, 1.0) else rnd.range(0.72, 0.94))
                var tries = 0
                while (tries < 4 && lobes.withIndex().any { (k, o) -> k != li && o.rel(q) < 0.82 }) {
                    d = rnd.unit()
                    q = surface(lb, d, if (lod == Lod.NEAR) rnd.range(0.84, 1.0) else rnd.range(0.72, 0.94))
                    tries++
                }
                // Flaechennormale der Ellipsoid-Oberflaeche, zufaellig verkippt
                // zur Strecke gedreht: Karten am Kronenrand stehen sonst von dort aus gesehen auf der Kante
                // (dunkle Striche); von oben bleiben die oberen Karten flach liegend.
                val sn = Vec3(d.x / lb.rh, d.y / lb.rv, d.z / lb.rh).normalized()
                val nrm = (sn + facing(sn, cr) + rnd.unit() * (if (lod == Lod.NEAR) 0.3 else 0.18) + Vec3(0.0, -droop, 0.0)).normalized()
                val sz = min(size * rnd.range(0.82, 1.15), maxCard)
                val reg = if (lod == Lod.NEAR && form != Form.PINE && i % 3 == 0) FoliageAtlas.BROAD else if (form == Form.PINE && i % 2 == 0 && lod != Lod.FAR) FoliageAtlas.NEEDLE else FoliageAtlas.MASS
                tangentCard(crown, q, nrm, sz, rnd.range(0.0, 2 * PI), reg, cr, lb, leaf, lobeTint * rnd.range(0.92, 1.08), lobeHue + rnd.range(-0.12, 0.12), droop)
            }
            // Nah: zweite, dunklere Schale im Lappen statt des Kerns — Tiefe im Laub, durch die
            // Luecken sieht man Schatten, Aeste und Himmel wie in einer echten Krone.
            val inner = if (lod == Lod.NEAR) nCards / 2 else 0
            for (i in 0 until inner) {
                val d = fib(i, inner, phase + 0.37)
                val q = surface(lb, d, rnd.range(0.5, 0.68))
                val sn = Vec3(d.x / lb.rh, d.y / lb.rv, d.z / lb.rh).normalized()
                val nrm = (sn + facing(sn, cr) + rnd.unit() * 0.4).normalized()
                tangentCard(crown, q, nrm, size * rnd.range(0.85, 1.1), rnd.range(0.0, 2 * PI), FoliageAtlas.MASS, cr, lb, leaf, lobeTint * 0.72, lobeHue - 0.1, droop)
            }
        }
    }

    /**
     * Zusatz zur Kartennormale: zur Strecke (die Kamera ist immer dort, tief) — seitlich und unten
     * liegende Karten stehen von dort gesehen sonst auf der Kante (dunkle Striche, „Federn“); obere
     * Karten bleiben flach (Drohne).
     */
    private fun facing(sn: Vec3, cr: Crown): Vec3 {
        val side = 1 - max(0.0, sn.y)
        // Obere Karten ebenfalls etwas zur Strecke geneigt: ganz flach liegend bildeten sie vom Rad aus
        // gesehen die Kronenoberkante aus Strichen.
        return cr.toward * (0.6 * side + 0.45 * (1 - side) + 0.12) + Vec3(0.0, -0.45 * side * side, 0.0)
    }

    private fun fib(i: Int, n: Int, phase: Double): Vec3 {
        val y = 1 - 2 * (i + 0.5) / n
        val r = sqrt(max(0.0, 1 - y * y))
        val a = i * 2.399963 + phase * 2 * PI
        return Vec3(r * cos(a), y, r * sin(a))
    }

    private fun surface(lb: Lobe, d: Vec3, f: Double): Vec3 = Vec3(lb.c.x + d.x * lb.rh * f, lb.c.y + d.y * lb.rv * f, lb.c.z + d.z * lb.rh * f)

    /** Gebogene Normale einer Kronenstelle: Lappen-, Kronenrichtung, zur Strecke, etwas nach oben. */
    private fun crownNormal(p: Vec3, lb: Lobe, cr: Crown): Vec3 {
        val ln = Vec3((p.x - lb.c.x) / lb.rh, (p.y - lb.c.y) / lb.rv, (p.z - lb.c.z) / lb.rh).normalized()
        val r = cr.rel(p)
        val cn = Vec3(r.x, if (r.y < 0) r.y * 0.6 else r.y, r.z).normalized(Vec3(0.0, 1.0, 0.0))
        return (ln * 0.4 + cn * 0.4 + cr.toward * 0.5 + Vec3(0.0, 0.3, 0.0)).normalized()
    }

    /** Verschattung einer Kronenstelle: unten und innen dunkler, oben und aussen heller. */
    private fun crownShade(p: Vec3, cr: Crown): Double {
        val ht = ((p.y - cr.bottom) / max(0.5, cr.top - cr.bottom)).coerceIn(0.0, 1.0)
        val out = cr.rel(p).length().coerceIn(0.0, 1.1)
        return 0.58 + 0.18 * ht + 0.12 * out
    }

    /**
     * Laubkarte tangential auf einem Lappen: Mitte [q], Flaechennormale [nrm]
     * (Vorderseite, einzige Wicklung), Kantenlaenge [size], Drehung [turn] in der
     * Ebene. Beim Zweigmotiv zeigt der Stiel zur Lappenmitte.
     */
    private fun tangentCard(m: MeshBuilder, q: Vec3, nrm: Vec3, size: Double, turn: Double, reg: FoliageAtlas.Region, cr: Crown, lb: Lobe, leaf: IntArray, tint: Double, hue: Double, droop: Double) {
        val a = perp(nrm, turn)
        var b = nrm cross a
        if (reg !== FoliageAtlas.MASS) {
            // Zweig/Nadelbueschel: Motiv „aufwaerts" moeglichst nach aussen bzw. oben in der Kartenebene
            val upInPlane = (Vec3(0.0, 1.0 - droop * 2.2, 0.0) - nrm * (nrm.y)).normalized(b)
            b = upInPlane
        }
        val ax = (b cross nrm).normalized()
        val c = q - b * (size * 0.12)
        val corners = arrayOf(
            c - ax * (size * 0.5) - b * (size * 0.5),
            c + ax * (size * 0.5) - b * (size * 0.5),
            c + ax * (size * 0.5) + b * (size * 0.5),
            c - ax * (size * 0.5) + b * (size * 0.5),
        )
        val st = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 1.0))
        val idx = IntArray(4)
        for (k in 0 until 4) {
            val pt = corners[k]
            val n = crownNormal(pt, lb, cr)
            val shade = tint * crownShade(pt, cr) * (if (st[k][1] == 0.0) 0.9 else 1.0)
            val u = if (reg === FoliageAtlas.NEEDLE) reg.u(st[k][1]) else reg.u(st[k][0])
            val v = if (reg === FoliageAtlas.NEEDLE) reg.v(st[k][0]) else reg.v(st[k][1])
            idx[k] = m.vertex(pt.x, pt.y, pt.z, n.x, n.y, n.z, u, v, leafColor(leaf, shade, hue))
        }
        // (ax, b, nrm) ist rechtshaendig: 0-1-2-3 laeuft von vorn gesehen gegen den Uhrzeigersinn
        m.quad(idx[0], idx[1], idx[2], idx[3])
    }

    /**
     * Dunkler, deckender Kern eines Lappens (Ellipsoid aus [lon] × [lat] Feldern, 0,8 der
     * Lappengroesse) mit derselben gebogenen Normale wie die Karten; Texturkoordinate in der
     * deckenden Mitte des Laubmasse-Motivs.
     */
    private fun core(m: MeshBuilder, lb: Lobe, cr: Crown, lon: Int, lat: Int, leaf: IntArray, tint: Double, hue: Double) {
        if (lon < 3) return
        val f = 0.6
        // Texturkoordinaten im deckenden Innern des Laubmasse-Motivs (0,3…0,7), nach Laenge/Breite verteilt:
        // der Kern zeigt Laub statt einer glatten Flaeche.
        fun vert(p: Vec3, s: Double, t: Double): Int {
            val n = crownNormal(p, lb, cr)
            return m.vertex(p.x, p.y, p.z, n.x, n.y, n.z, FoliageAtlas.MASS.u(0.3 + 0.4 * s), FoliageAtlas.MASS.v(0.3 + 0.4 * t), leafColor(leaf, tint * crownShade(p, cr), hue))
        }
        // Mitte etwas nach oben, Unterseite flach: von unten (vom Rad aus) sieht man in die Krone hinein
        // auf Aeste und Laubkarten, nicht auf den Kern.
        val cy = lb.c.y + lb.rv * 0.2
        val top = vert(Vec3(lb.c.x, cy + lb.rv * f, lb.c.z), 0.5, 1.0)
        val bottom = vert(Vec3(lb.c.x, cy - lb.rv * f * 0.45, lb.c.z), 0.5, 0.0)
        val rings = Array(lat - 1) { j ->
            val phi = PI * (j + 1) / lat
            IntArray(lon) { i ->
                val a = 2 * PI * i / lon + j * 0.5
                val p = Vec3(lb.c.x + sin(phi) * cos(a) * lb.rh * f, cy + cos(phi) * lb.rv * f * (if (cos(phi) < 0) 0.45 else 1.0), lb.c.z - sin(phi) * sin(a) * lb.rh * f)
                vert(p, abs(((i.toDouble() / lon) * 2) % 2.0 - 1.0), 1 - (j + 1.0) / lat)
            }
        }
        for (i in 0 until lon) m.tri(top, rings[0][(i + 1) % lon], rings[0][i])
        for (j in 0 until lat - 2) for (i in 0 until lon) {
            val a0 = rings[j][i]; val a1 = rings[j][(i + 1) % lon]
            val b0 = rings[j + 1][i]; val b1 = rings[j + 1][(i + 1) % lon]
            m.tri(a0, b1, b0); m.tri(a0, a1, b1)
        }
        val last = rings[lat - 2]
        for (i in 0 until lon) m.tri(bottom, last[i], last[(i + 1) % lon])
    }

    private fun barkColorOf(s: Spec, form: Form): Int = when (form) {
        Form.BIRCH -> lin(Argb.rgb(222, 218, 208))
        Form.OAK -> lin(Argb.rgb(150, 136, 120))
        Form.FRUIT -> lin(Argb.rgb(150, 130, 112))
        Form.PINE -> lin(Argb.rgb(162, 142, 126))
        Form.ASH, Form.POPLAR -> lin(Argb.rgb(176, 168, 152))
        else -> if (s.pale) lin(Argb.rgb(214, 210, 200)) else lin(Argb.rgb(178, 168, 156))
    }

    /** Birkenstamm: leicht gekruemmt, weiss mit dunklen Querbaendern (Vertexfarben) und dunklem Fuss. */
    private fun birchStem(m: MeshBuilder, lod: Lod, r: Double, top: Double, rnd: Rnd, seg: Int) {
        val white = lin(Argb.rgb(222, 218, 208))
        val dark = lin(Argb.rgb(70, 66, 62))
        val lean = Vec3(rnd.range(-0.05, 0.05) * top, 0.0, rnd.range(-0.05, 0.05) * top)
        val n = if (lod == Lod.NEAR) 9 else if (lod == Lod.MID) 4 else 2
        val pts = ArrayList<Vec3>()
        val radii = ArrayList<Double>()
        val cols = ArrayList<Int>()
        for (k in 0..n) {
            val t = k.toDouble() / n
            pts += Vec3(0.0, -0.3 + (top + 0.3) * t, 0.0) + lean * (t * t)
            radii += r * (1.1 - 0.65 * t)
            cols += when {
                t < 0.12 -> dark
                lod == Lod.NEAR && k % 2 == 1 && rnd.next() < 0.6 -> scaleColor(white, 0.45)
                else -> white
            }
        }
        m.addLoft(pts, radii.toDoubleArray(), seg, false, false, white, cols.toIntArray(), 1.0, 0.5)
    }

    // -----------------------------------------------------------------------
    // Fichte
    // -----------------------------------------------------------------------

    /**
     * Fichte: dunkler Kegelkern, darum Astquirle aus waagrecht abstehenden, leicht
     * haengenden Nadelzweig-Karten (um die Astachse gerollt, damit sie von der Seite
     * nicht als Strich erscheinen; von oben ein Astkranz statt Kegelstapel) und —
     * nah und mittel — gewoelbte Schuerzen mit haengendem Nadelrand. Quirle mit
     * unregelmaessigem Abstand, Laenge und Drehung; die Spitze schmal und hell.
     */
    private fun spruce(s: Spec, lod: Lod, trunk: MeshBuilder, crown: MeshBuilder, toward: Vec3) {
        val rnd = Rnd(s.seed, 0x7b21)
        val h = s.heightM
        val leaf = formLeaf(Form.SPRUCE)
        val bark = lin(Argb.rgb(158, 138, 118))
        val b0 = s.bare.coerceIn(0.04, 0.4) * h
        val span = h - b0
        val r0 = 0.22 * h * s.width
        fun env(y: Double): Double {
            val t = ((y - b0) / span).coerceIn(0.0, 1.0)
            return r0 * (1 - t).pow(0.95) * (0.84 + 0.16 * (1 - t)) + 0.05
        }
        val cr = Crown(Vec3(0.0, b0 + span * 0.38, 0.0), r0 * 0.8, span * 0.6, b0, h, toward)
        val seg = if (lod == Lod.NEAR) 6 else if (lod == Lod.MID) 4 else 3
        trunk.bark(Vec3(0.0, -0.3, 0.0), Vec3(0.0, 0.92 * h, 0.0), trunkRadius(h, 0.009, 0.32) * 1.1, 0.04, seg, false, false, bark)
        // Kern: Kegel 0,55 der Huelle
        val coreLon = if (lod == Lod.FAR) 5 else 6
        coneCore(crown, b0 + span * 0.02, h * 0.93, env(b0) * (if (lod == Lod.NEAR) 0.5 else 0.38), coreLon, cr, leaf, s.tint * 0.8, s.hue)
        val (tiers, perTier, skirts) = when (lod) {
            Lod.NEAR -> Triple(10, 7, 4)
            Lod.MID -> Triple(8, 6, 3)
            Lod.FAR -> Triple(6, 5, 0)
        }
        for (k in 0 until tiers) {
            val t = (k + rnd.range(0.0, 0.4)) / tiers
            val y = b0 + span * 0.92 * t.pow(0.9)
            val rr = env(y)
            val tierTint = s.tint * (0.86 + 0.24 * t) * rnd.range(0.94, 1.06)
            val n = if (t > 0.8) max(3, perTier - 2) else perTier
            for (j in 0 until n) {
                val a = s.turn + j * 2 * PI / n + k * 0.83 + rnd.range(-0.25, 0.25)
                val out = Vec3(cos(a), 0.0, -sin(a))
                val len = rr * rnd.range(0.95, 1.18) + 0.2
                val droopA = rnd.range(0.18, 0.42) + 0.15 * (1 - t)
                val along = (out * cos(droopA) + Vec3(0.0, -sin(droopA), 0.0)).normalized()
                // Kartenebene um die Astachse gerollt (20–45° aus der Waagrechten)
                val side = (Vec3(0.0, 1.0, 0.0) cross out).normalized()
                val roll = rnd.range(0.35, 0.8) * (if (j % 2 == 0) 1 else -1)
                val across = (side * cos(roll) + Vec3(0.0, 1.0, 0.0) * sin(roll)).normalized()
                val base = Vec3(0.0, y + len * 0.05, 0.0) + out * (rr * 0.12)
                card(crown, base, along, across, len, len * (if (lod == Lod.NEAR) 0.7 else 0.95), FoliageAtlas.NEEDLE, true, cr, leaf, tierTint, s.hue + rnd.range(-0.15, 0.15), bend = 0.7, darkBase = 0.6, clampTip = false)
            }
            if (k < tiers - 1 && skirts > 0 && (k % 2 == 0 || lod == Lod.NEAR)) {
                val drop = span / tiers * 1.7
                for (j in 0 until skirts) {
                    val a0 = s.turn + j * 2 * PI / skirts + k * 0.47 + rnd.range(-0.3, 0.3)
                    val rb = env(y - drop * 0.6) * rnd.range(0.8, 0.95)
                    skirt(crown, a0, PI / skirts * 1.3, rr * 0.2 + 0.05, rb, y + drop * 0.25, y - drop * 0.65, rb * 0.12, cr, leaf, tierTint * 0.95, s.hue)
                }
            }
        }
        // Spitze
        val tipBase = b0 + span * 0.88
        for (j in 0 until 3) {
            val a = s.turn + j * PI / 3
            card(crown, Vec3(0.0, tipBase, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(cos(a), 0.0, -sin(a)), h - tipBase + 0.2, env(tipBase) * 1.6 + 0.3, FoliageAtlas.TREE_CONIFER, false, cr, leaf, s.tint * 1.08, s.hue, bend = 0.6, darkBase = 0.8, clampTip = false)
        }
    }

    /** Dunkler Kegelkern der Fichte (deckend, Texturkoordinate in der Laubmasse-Mitte). */
    private fun coneCore(m: MeshBuilder, y0: Double, y1: Double, r: Double, lon: Int, cr: Crown, leaf: IntArray, tint: Double, hue: Double) {
        val u = FoliageAtlas.MASS.u(0.5)
        val v = FoliageAtlas.MASS.v(0.5)
        val mid = y0 + (y1 - y0) * 0.45
        fun ring(y: Double, rad: Double): IntArray = IntArray(lon) { i ->
            val a = 2 * PI * i / lon
            val n = (Vec3(cos(a), 0.45, -sin(a)) + cr.toward * 0.5).normalized()
            val ht = ((y - cr.bottom) / max(0.5, cr.top - cr.bottom)).coerceIn(0.0, 1.0)
            m.vertex(cos(a) * rad, y, -sin(a) * rad, n.x, n.y, n.z, u, v, leafColor(leaf, tint * (0.7 + 0.3 * ht), hue))
        }
        val r0 = ring(y0, r)
        val r1 = ring(mid, r * 0.6)
        val tip = m.vertex(0.0, y1, 0.0, 0.0, 1.0, 0.0, u, v, leafColor(leaf, tint, hue))
        val bot = m.vertex(0.0, y0 - 0.2, 0.0, 0.0, -1.0, 0.0, u, v, leafColor(leaf, tint * 0.6, hue))
        for (i in 0 until lon) {
            val j = (i + 1) % lon
            m.tri(r0[i], r0[j], r1[j]); m.tri(r0[i], r1[j], r1[i])
            m.tri(r1[i], r1[j], tip)
            m.tri(bot, r0[j], r0[i])
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
                val shade = tint * (0.62 + 0.2 * ht) * (1.0 - 0.25 * t)
                val u = reg.u(ci.toDouble())
                val v = reg.v(t)
                top[ri * 2 + ci] = m.vertex(p.x, p.y, p.z, nUp.x, nUp.y, nUp.z, u, v, leafColor(leaf, shade, hue))
                val nDown = (out * 0.6 + crown.toward * 0.5 + Vec3(0.0, -0.2, 0.0)).normalized()
                bot[ri * 2 + ci] = m.vertex(p.x, p.y, p.z, nDown.x, nDown.y, nDown.z, u, v, leafColor(leaf, tint * 0.55 * (1.0 - 0.2 * t), hue - 0.2))
            }
        }
        for (r in 0..1) {
            val a = r * 2
            val b = r * 2 + 1
            val c = r * 2 + 3
            val d = r * 2 + 2
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
