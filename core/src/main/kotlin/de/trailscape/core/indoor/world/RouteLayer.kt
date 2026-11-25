package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Der befahrene Weg (Knoten `route`)
 *
 * Zeichnet die Fahrbahn der Strecke und alles, was sie mit dem Gelaende
 * verbindet — das, was der Fahrer die ganze Zeit vor sich sieht:
 *
 *  * **Fahrbahn** je Belag (`mat_route_<key>`, kachelnde Textur, `u` quer in
 *    Metern, `v` mit den Laufmetern) auf `track.u`, mit dezentem Dachprofil.
 *    Kurven sind per Catmull-Rom verfeinert, Innenkurven ueberschneiden nicht.
 *  * **Belagswechsel**: Der Abschnitt mit ungeradem Index laeuft als zweites
 *    Band mit Vertex-Alpha rund 6 m ueber seine Nachbarn aus (1,2 cm hoeher).
 *  * **Rand** je Belag und Umgebung: Bankett an Strassen, Bordstein und Gehweg
 *    im Ort, ausgefranste Graskante an Feld- und Waldwegen.
 *  * **Uebergangsstreifen** mit Boden-Detailtextur (Gras, Waldboden …), der als
 *    Boeschung vom Fahrbahnrand auf den Boden fuehrt und mit Vertex-Alpha ins
 *    Luftbild auslaeuft; seine Farbe ist an das Luftbild angeglichen.
 *  * **Markierung**, Bröselkante, Flicken, Risse, Rinne, Schachtdeckel: [RoadDecals] (nach der
 *    Fahrbahn gezeichnet, mit freigehaltenem Budget).
 *
 * Welche Kachel welches Stueck zeichnet, steht in [RouteGeometry]. Reicht das
 * Dreiecksbudget nicht, wird stufenweise vereinfacht ([DETAILS]) — erst die
 * Kurvenverfeinerung, dann die Laengsteilung, zuletzt Fransen und Markierung;
 * nie entsteht ein Loch im Band.
 */
internal class RouteLayer : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        if (ctx.level != 0 || ctx.budget.route <= 0) return
        val units = RouteGeometry.unitsOf(ctx.track, ctx.spec)
        if (units.isEmpty()) return
        val env = RouteEnv(ctx)
        // Markierung und Abziehbilder (RoadDecals): Platz im Budget freihalten, nach der Fahrbahn zeichnen.
        val decals = RoadDecals(ctx, env, units)
        val reserve = decals.reserve()
        for ((k, detail) in DETAILS.withIndex()) {
            if (k < DETAILS.lastIndex) {
                val probe = TileOutput(ctx.spec)
                RouteEmitter(ctx, env, detail, probe).run(units)
                if (probe.triangles(WorldNames.NODE_ROUTE) > ctx.budget.route - reserve) continue
            }
            RouteEmitter(ctx, env, detail, out).run(units)
            decals.emit(detail, out, ctx.budget.route - out.triangles(WorldNames.NODE_ROUTE))
            return
        }
    }

    /** Eine Vereinfachungsstufe. */
    internal class Detail(val maxSub: Int, val merge: Boolean, val extras: Boolean, val vergeRings: Int)

    companion object {
        val DETAILS = listOf(
            Detail(4, false, true, 3), Detail(2, false, true, 3), Detail(1, false, true, 3),
            Detail(1, true, true, 3), Detail(1, true, false, 3), Detail(1, true, false, 2),
            // Zuletzt ohne Auslauf des Uebergangsstreifens (nur der deckende Teil).
            Detail(1, true, false, 1),
        )

        /** Hoehe, um die Ueberblend-Baender und Markierungen ueber der Fahrbahn liegen. */
        const val OVERLAY_LIFT_M = 0.012

        /** Auf dieser Laenge laeuft der Uebergangsstreifen an offenen Enden der Strecke aus. */
        const val END_TAPER_M = 20.0

        /** Laenge des weichen Belagswechsels in Metern. */
        const val FADE_M = 6.0

        const val KERB_HEIGHT_M = 0.12
        const val KERB_WIDTH_M = 0.15

        fun routeMaterialName(kind: SurfaceKind): String = WorldNames.MAT_ROUTE_PREFIX + kind.visual.textureKey
        fun fadeMaterialName(kind: SurfaceKind): String = "x_mat_route_${kind.visual.textureKey}_fade"
        fun vergeMaterialName(v: RouteTextures.Verge): String = "x_mat_route_${v.key}"
        fun vergeFadeMaterialName(v: RouteTextures.Verge): String = "x_mat_route_${v.key}_fade"
        const val MAT_SHOULDER = "x_mat_route_shoulder"
        const val MAT_SIDEWALK = "x_mat_route_sidewalk"
        const val MAT_PLAIN = "x_mat_route_plain"
        const val MAT_FRINGE = "x_mat_route_fringe"

        /** Dachprofil (Quergefaelle zu beiden Seiten) eines Belags. */
        fun crownOf(kind: SurfaceKind): Double = when (kind.visual) {
            SurfaceKind.ASPHALT, SurfaceKind.CONCRETE -> 0.022
            SurfaceKind.PAVING_STONES, SurfaceKind.COBBLESTONE -> 0.018
            SurfaceKind.WOOD -> 0.0
            else -> 0.014
        }

        private val MARKED_HIGHWAYS = setOf(
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "road",
        )

        /** Kraftverkehrsstrasse mit festem Belag: bekommt Bankett, ab 5,5 m Randlinien, ab 6 m Mittellinie. */
        fun isMotorRoad(s: SurfaceSection): Boolean {
            val k = s.kind.visual
            if (k != SurfaceKind.ASPHALT && k != SurfaceKind.CONCRETE) return false
            return if (s.highway == null) s.effectiveWidthM >= 5.5 else s.highway in MARKED_HIGHWAYS
        }

        fun hasEdgeLines(s: SurfaceSection): Boolean = isMotorRoad(s) && s.effectiveWidthM >= 5.5
        fun hasCentreLine(s: SurfaceSection): Boolean = isMotorRoad(s) && s.effectiveWidthM >= 6.0
    }
}

/** Randgestaltung einer Wegseite. */
internal enum class EdgeStyle {
    /** Strasse ausserorts: Bankett, dann Gras. */
    BANKETT,

    /** Im Ort: Bordstein und Gehweg. */
    URBAN,

    /** Feld-/Waldweg: ausgefranste Kante, Grasnarbe. */
    SOFT,

    /** Holzsteg: glatte Kante. */
    PLAIN,
}

/** Randgestaltung aus Belag, Wegart und Umgebung. */
internal fun edgeStyleOf(s: SurfaceSection, land: LandClass): EdgeStyle {
    val k = s.kind.visual
    if (k == SurfaceKind.WOOD) return EdgeStyle.PLAIN
    val paved = k == SurfaceKind.ASPHALT || k == SurfaceKind.CONCRETE || k == SurfaceKind.PAVING_STONES || k == SurfaceKind.COBBLESTONE
    val smallWay = s.highway == "path" || s.highway == "track" || s.highway == "footway" || s.highway == "cycleway" || s.highway == "bridleway"
    if (!paved || smallWay) return EdgeStyle.SOFT
    if ((land == LandClass.RESIDENTIAL || land == LandClass.INDUSTRIAL) && s.effectiveWidthM >= 3.0) return EdgeStyle.URBAN
    return if (s.effectiveWidthM >= 3.5) EdgeStyle.BANKETT else EdgeStyle.SOFT
}

/** Bodentextur des Uebergangsstreifens je Landnutzung. */
internal fun vergeOf(land: LandClass): RouteTextures.Verge = when (land) {
    LandClass.FOREST -> RouteTextures.Verge.FOREST
    LandClass.BARE, LandClass.INDUSTRIAL -> RouteTextures.Verge.SHOULDER
    else -> RouteTextures.Verge.GRASS
}

/** Querschnitt an einer Probe der Strecke (alles, was nur von Strecke und Karte abhaengt — in jeder Kachel gleich). */
internal class RouteProfile(
    val index: Int,
    val section: SurfaceSection,
    val sectionIndex: Int,
    val halfWidth: Double,
    val crown: Double,
) {
    val land = arrayOfNulls<LandClass>(2)
    val style = arrayOfNulls<EdgeStyle>(2)
    val verge = arrayOfNulls<RouteTextures.Verge>(2)

    /** Breite des Randstreifens (Bankett, Gehweg, Grasnarbe) ab Fahrbahnrand. */
    val shoulderW = DoubleArray(2)

    /** Hoehe der Aussenkante des Randstreifens ueber dem Fahrbahnrand. */
    val drop = DoubleArray(2)

    /** Wo der Uebergangsstreifen beginnt (ab Fahrbahnrand) und mit welcher Deckung. */
    val v0Off = DoubleArray(2)
    val v0Alpha = DoubleArray(2)

    /** Voll deckende Breite und Gesamtbreite des Uebergangsstreifens ab Randstreifen. */
    val inner = DoubleArray(2)
    val end = DoubleArray(2)

    /** Abtoenung der Randtextur (linear, je Kanal) ohne und mit Luftbild. */
    val tint = arrayOf(FloatArray(3), FloatArray(3))
    val aerial = arrayOfNulls<FloatArray>(2)
    val kerb = DoubleArray(2)

    /** Eine andere Strasse bzw. ein anderer Streckenteil ist nah — dort feiner teilen und Raender aussparen. */
    var nearRoad = false
    var nearOther = false
}

/** Was je Kachel einmal gerechnet und von allen Vereinfachungsstufen geteilt wird. */
internal class RouteEnv(val ctx: TileContext) {
    val track = ctx.track
    val roads = RoadNet(ctx)
    val hood = RouteGeometry.Neighbourhood(ctx.track, ctx.spec)

    /**
     * Sperrflaechen fuer die Randgestaltung ([PlanMask]): die Baender der uebrigen Strassen genau so
     * abgetastet wie [RoadsLayer] sie zeichnet (Schritt [RoadsLayer.STEP_M], Gehrung), dazu die
     * Fahrbahn der anderen (nicht doppelten) Streckenteile mit ihrem Laufmeter-Bereich.
     */
    val mask: PlanMask by lazy { buildMask() }

    /**
     * Nur die Fahrbahnen der Streckenteile (mit Laufmeter-Bereich): Wo sich zwei Teile ueberlagern
     * (Wendeschleife, Hin- und Rueckweg dicht nebeneinander), gewinnt der fruehere — Fahrbahn, Fransen
     * und Linien des spaeteren werden an ihm abgeschnitten ([RouteEmitter.flush]).
     */
    val trackMask: PlanMask by lazy { mask; trackParts }
    private val trackParts = PlanMask()
    private val profiles = HashMap<Int, RouteProfile>()
    private val rawLand = HashMap<Int, LandClass>()
    private val tmp = DoubleArray(5)
    private val oth = DoubleArray(3)

    private fun clampIndex(i: Int): Int = i.coerceIn(0, track.count - 1)

    private fun rawLandAt(i0: Int, side: Int): LandClass {
        val i = clampIndex(i0)
        return rawLand.getOrPut(i * 2 + side) {
            RouteGeometry.centerAt(track, min(i, track.count - 2), if (i == track.count - 1) 1.0 else 0.0, tmp)
            val off = (track.halfWidthAt(track.track.distAt(i)) + 3.0) * (if (side == 0) -1 else 1)
            ctx.land.classAt(tmp[0] + tmp[4] * off, tmp[1] - tmp[3] * off)
        }
    }

    /** Landnutzung neben der Strecke, ueber ±15 m gemittelt (Mehrheit), damit der Rand nicht flattert. */
    private fun landAt(i: Int, side: Int): LandClass {
        val counts = IntArray(LandClass.entries.size)
        for (k in -3..3) counts[rawLandAt(i + k, side).ordinal] += if (k == 0) 2 else 1
        var best = 0
        for (c in counts.indices) if (counts[c] > counts[best]) best = c
        return LandClass.entries[best]
    }

    private fun baseStyle(i0: Int, side: Int): EdgeStyle {
        val i = clampIndex(i0)
        val sec = track.track.surfaces[RouteGeometry.segSection(track, i)]
        return edgeStyleOf(sec, landAt(i, side))
    }

    fun profile(i0: Int): RouteProfile {
        val i = clampIndex(i0)
        return profiles.getOrPut(i) { makeProfile(i) }
    }

    private fun makeProfile(i: Int): RouteProfile {
        val si = RouteGeometry.segSection(track, i)
        val sec = track.track.surfaces[si]
        val d = track.track.distAt(i)
        val hw = track.halfWidthAt(d)
        val p = RouteProfile(i, sec, si, hw, RouteLayer.crownOf(sec.kind))
        RouteGeometry.centerAt(track, min(i, track.count - 2), if (i == track.count - 1) 1.0 else 0.0, tmp)
        val ce = tmp[0]
        val cn = tmp[1]
        val re = tmp[4]
        val rn = -tmp[3]
        for (side in 0..1) {
            val sgn = if (side == 0) -1.0 else 1.0
            val land = landAt(i, side)
            val style = baseStyle(i, side)
            p.land[side] = land
            p.style[side] = style
            p.verge[side] = vergeOf(land)
            when (style) {
                EdgeStyle.BANKETT -> {
                    p.shoulderW[side] = 0.6; p.drop[side] = -0.035; p.v0Off[side] = 0.15; p.v0Alpha[side] = 0.0
                }
                EdgeStyle.URBAN -> {
                    p.shoulderW[side] = RouteLayer.KERB_WIDTH_M + 1.5; p.drop[side] = 0.02; p.v0Off[side] = RouteLayer.KERB_WIDTH_M + 1.5; p.v0Alpha[side] = 1.0
                }
                EdgeStyle.SOFT -> {
                    p.shoulderW[side] = 0.5; p.drop[side] = 0.025; p.v0Off[side] = 0.0; p.v0Alpha[side] = 1.0
                }
                EdgeStyle.PLAIN -> {
                    p.shoulderW[side] = 0.3; p.drop[side] = -0.05; p.v0Off[side] = 0.0; p.v0Alpha[side] = 1.0
                }
            }
            val urban = style == EdgeStyle.URBAN && baseStyle(i - 1, side) == EdgeStyle.URBAN && baseStyle(i + 1, side) == EdgeStyle.URBAN
            p.kerb[side] = if (urban) RouteLayer.KERB_HEIGHT_M else 0.0
            when (land) {
                LandClass.FOREST -> {
                    p.inner[side] = 4.0; p.end[side] = 10.0
                }
                LandClass.MEADOW, LandClass.ORCHARD, LandClass.PARK, LandClass.SCRUB, LandClass.WETLAND -> {
                    p.inner[side] = 3.5; p.end[side] = 9.0
                }
                // Breiter deckend als frueher (1,2/1,0 m): Das Luftbild zeigt die echte Strasse oft
                // etwas versetzt — sonst stand neben dem Rand ein heller, harter Saum aus Strassenpixeln.
                // Breiter auslaufend als frueher (5,0/4,4 m): Der Streifen deckt so den groessten Teil der flachen
                // Mulde neben der Fahrbahn (CarvedGround) und laeuft weicher ins Gelaende aus.
                LandClass.FARMLAND -> {
                    p.inner[side] = 2.0; p.end[side] = 6.5
                }
                else -> {
                    p.inner[side] = 1.8; p.end[side] = 6.0
                }
            }
            val t = DEFAULT_TINT.getValue(land)
            p.tint[side][0] = t[0]; p.tint[side][1] = t[1]; p.tint[side][2] = t[2]
            // Luftbildfarbe ein Stueck draussen (nicht auf der echten Strasse im Bild), in die Kachel geklemmt.
            val off = (hw + p.shoulderW[side] + max(1.5, p.inner[side] * 0.8)) * sgn
            val s = ctx.spec
            val ae = (ce + re * off).coerceIn(s.minE + 1.0, s.maxE - 1.0)
            val an = (cn + rn * off).coerceIn(s.minN + 1.0, s.maxN - 1.0)
            ctx.aerialMeanAt(ae, an, 2.0)?.let { c ->
                val near = floatArrayOf(Argb.SRGB_TO_LINEAR[Argb.red(c)], Argb.SRGB_TO_LINEAR[Argb.green(c)], Argb.SRGB_TO_LINEAR[Argb.blue(c)])
                // Neben Feldern (Maulbronn km 7/13, lidar.md 9.1): Der Rain direkt am Weg ist im Luftbild satt
                // gruen, das Winterfeld dahinter blass. Der Streifen nimmt dort zur Haelfte die Farbe des Felds
                // 9 m draussen — er laeuft sonst als knallgruenes Band ueber das helle Feld aus.
                if (land == LandClass.FARMLAND) {
                    val fe = (ce + re * (hw + 9.0) * sgn).coerceIn(s.minE + 1.0, s.maxE - 1.0)
                    val fn = (cn + rn * (hw + 9.0) * sgn).coerceIn(s.minN + 1.0, s.maxN - 1.0)
                    ctx.aerialMeanAt(fe, fn, 2.5)?.let { f ->
                        val far = floatArrayOf(Argb.SRGB_TO_LINEAR[Argb.red(f)], Argb.SRGB_TO_LINEAR[Argb.green(f)], Argb.SRGB_TO_LINEAR[Argb.blue(f)])
                        for (k in 0..2) near[k] = near[k] * 0.5f + far[k] * 0.5f
                    }
                }
                p.aerial[side] = near
            }
        }
        p.nearRoad = roads.roads.isNotEmpty() && roads.clearance(ce, cn) < hw + 14.0
        hood.other(ce, cn, d, OTHER_GAP_M, oth)
        p.nearOther = oth[0] < 40.0
        return p
    }

    private fun buildMask(): PlanMask {
        val m = PlanMask()
        val s = ctx.spec
        val margin = 30.0
        val clipNet = RoadNet(ctx, marginM = margin, maxStepM = RoadsLayer.STEP_M)
        // Ausgerundete Ecken an Einmuendungen (JunctionCorners): Gras und Gehweg enden am Bogen bzw. am Bordstein.
        for (c in JunctionCorners.find(track, ctx.land, clipNet)) c.addTo(m)
        for (r in clipNet.roads) {
            if (r.maxE < s.minE - margin || r.minE > s.maxE + margin || r.maxN < s.minN - margin || r.minN > s.maxN + margin) continue
            // Bahnen sperren nicht (wie frueher): Ihr Schotterbett liegt unter dem Rand, die Schienen darueber.
            if (r.spec.rail) continue
            val hw = r.halfWidth
            for (i in 0 until r.size - 1) {
                // Stuecke „auf der Strecke" zeichnet niemand als Strasse. Laufen sie schon neben der Fahrbahn
                // auseinander (Kreiseleinfahrt, Gabelung: Mitte > 1,5 m von der Strecke — mehr, als die
                // Glaettung der Strecke versetzt), liegt dort im Boden gemalter Asphalt: Sie sperren dann
                // Gras und Gehweg, nicht aber Fransen, Bordstein und Linien.
                val on = r.segOnTrack(i)
                if (on && min(r.trackDist[i], r.trackDist[i + 1]) <= 1.5) continue
                // Ein Weg, der laengs in der eigenen Fahrbahn liegt, ohne als „auf der Strecke" zu gelten
                // (Richtung oder Abstand knapp daneben), schneidet nur den aeusseren Rand — sonst fehlten die
                // Fransen auf langen Stuecken und endeten mit einer Querkante.
                var inside = false
                if (!on) {
                    val ha = track.nearest(r.e[i], r.n[i], 30.0)
                    val hb = track.nearest(r.e[i + 1], r.n[i + 1], 30.0)
                    if (ha != null && hb != null && ha.distance < track.halfWidthAt(ha.distM) + 0.5 && hb.distance < track.halfWidthAt(hb.distM) + 0.5) {
                        val p = track.poseAt(ha.distM)
                        val h = Math.toRadians(p.headingDeg)
                        val dx = r.e[i + 1] - r.e[i]
                        val dy = r.n[i + 1] - r.n[i]
                        val l = kotlin.math.hypot(dx, dy)
                        inside = l > 1e-6 && abs((dx * kotlin.math.sin(h) + dy * kotlin.math.cos(h)) / l) > 0.8
                    }
                }
                val w = hw
                val oa = w * min(r.miter[i], 2.0)
                val ob = w * min(r.miter[i + 1], 2.0)
                m.addQuad(
                    r.e[i] - r.re[i] * oa, r.n[i] - r.rn[i] * oa, r.e[i] + r.re[i] * oa, r.n[i] + r.rn[i] * oa,
                    r.e[i + 1] - r.re[i + 1] * ob, r.n[i + 1] - r.rn[i + 1] * ob, r.e[i + 1] + r.re[i + 1] * ob, r.n[i + 1] + r.rn[i + 1] * ob,
                    outerOnly = on || inside,
                )
            }
        }
        // Andere Streckenteile: nur, wo die Strecke sich selbst nahe kommt.
        val t = track.track
        val c0 = DoubleArray(5)
        val c1 = DoubleArray(5)
        // Alle Streckenteile im Umkreis, auch solche, die eine Nachbarkachel zeichnet.
        for (i in 0 until track.count - 1) {
            val pe = t.e[i].toDouble()
            val pn = t.n[i].toDouble()
            if (pe < s.minE - margin || pe > s.maxE + margin || pn < s.minN - margin || pn > s.maxN + margin) continue
            if (isDuplicate(i / 2)) continue
            run {
                // Fein unterteilt (8 je Probe): In engen Kehren (Radius um 5 m bei 5 m Probenabstand) traf die Sehne
                // sonst das gezeichnete Band nicht genau genug, Reste blieben uebereinander liegen.
                for (h in 0 until 8) {
                    RouteGeometry.centerAt(track, i, h / 8.0, c0)
                    RouteGeometry.centerAt(track, i, (h + 1) / 8.0, c1)
                    val d0 = t.distAt(i)
                    val hw0 = track.halfWidthAt(d0)
                    trackParts.addQuad(
                        c0[0] - c0[4] * hw0, c0[1] + c0[3] * hw0, c0[0] + c0[4] * hw0, c0[1] - c0[3] * hw0,
                        c1[0] - c1[4] * hw0, c1[1] + c1[3] * hw0, c1[0] + c1[4] * hw0, c1[1] - c1[3] * hw0,
                        fromM = d0, toM = t.distAt(i + 1),
                    )
                    m.addQuad(
                        c0[0] - c0[4] * hw0, c0[1] + c0[3] * hw0, c0[0] + c0[4] * hw0, c0[1] - c0[3] * hw0,
                        c1[0] - c1[4] * hw0, c1[1] + c1[3] * hw0, c1[0] + c1[4] * hw0, c1[1] - c1[3] * hw0,
                        fromM = d0, toM = t.distAt(i + 1),
                        // Nur der aeussere Rand: Fransen und Linien eines Streckenteils bleiben auch dort, wo ein anderer
                        // (Wendeschleife, Hin- und Rueckweg dicht nebeneinander) darueber liegt — sonst endeten sie mit
                        // einer Querkante (Altenberg kurz, km 1,5).
                        outerOnly = true,
                    )
                }
            }
        }
        return m
    }

    /**
     * Einheit [k] liegt deckungsgleich auf einem frueheren Teil der Strecke
     * (Hin- und Rueckweg auf demselben Weg) und wird deshalb nicht gezeichnet.
     */
    fun isDuplicate(k: Int): Boolean {
        val t = track.track
        for (i in RouteGeometry.unitStart(k)..RouteGeometry.unitEnd(track, k)) {
            val d = t.distAt(i)
            hood.other(t.e[i].toDouble(), t.n[i].toDouble(), d, OTHER_GAP_M, oth)
            if (oth[1] < 0 || oth[1] >= i || oth[0] > max(0.5, 0.4 * track.halfWidthAt(d))) return false
            if (abs(t.u[i] - t.u[oth[1].toInt()]) > 0.6) return false
        }
        return true
    }

    companion object {
        /** Laufmeter-Abstand, ab dem ein Streckenteil als „anderer" gilt. */
        const val OTHER_GAP_M = 50.0

        /** Abtoenung der Randtextur ohne Luftbild (die Texturen sind hell gemalt, damit die Farbe auch aufhellen kann). */
        val DEFAULT_TINT: Map<LandClass, FloatArray> = mapOf(
            LandClass.MEADOW to floatArrayOf(0.60f, 0.62f, 0.58f),
            LandClass.PARK to floatArrayOf(0.52f, 0.63f, 0.50f),
            LandClass.FARMLAND to floatArrayOf(0.64f, 0.62f, 0.52f),
            LandClass.SCRUB to floatArrayOf(0.50f, 0.54f, 0.47f),
            LandClass.ORCHARD to floatArrayOf(0.60f, 0.62f, 0.58f),
            LandClass.RESIDENTIAL to floatArrayOf(0.55f, 0.62f, 0.52f),
            LandClass.WETLAND to floatArrayOf(0.50f, 0.56f, 0.47f),
            LandClass.WATER to floatArrayOf(0.50f, 0.56f, 0.47f),
            LandClass.FOREST to floatArrayOf(0.52f, 0.52f, 0.52f),
            LandClass.BARE to floatArrayOf(0.90f, 0.90f, 0.90f),
            LandClass.INDUSTRIAL to floatArrayOf(0.85f, 0.85f, 0.85f),
        )
    }
}

/** Eine Station des Bands: Mittellinie und der fertige Querschnitt beider Seiten. */
private class Station(val x: Double) {
    var seg = 0
    var dist = 0.0
    var e = 0.0
    var n = 0.0
    var u = 0.0
    var te = 0.0
    var tn = 0.0
    var grade = 0.0
    var kappa = 0.0
    var hw = 0.0
    var crown = 0.0
    var lift = 0.0
    var tone = 0.0

    // Je Seite (0 links, 1 rechts): Abstaende ab Mitte und Hoehen der Ringe.
    val kerb = DoubleArray(2)
    val t2 = DoubleArray(2)
    val tV0 = DoubleArray(2)
    val tA = DoubleArray(2)
    val tB = DoubleArray(2)
    val tE = DoubleArray(2)
    val h1 = DoubleArray(2)
    val h2 = DoubleArray(2)
    val hA = DoubleArray(2)
    val hB = DoubleArray(2)
    val hE = DoubleArray(2)
    val a0 = DoubleArray(2)

    /** Breite der Fransen 0..1: laeuft an Wechseln der Randgestaltung ueber rund 10 m aus (keine Querkante). */
    val fringeW = DoubleArray(2)

    val tint = arrayOf(FloatArray(3), FloatArray(3))
    val aerial = arrayOf(FloatArray(3), FloatArray(3))
    val hasAerial = BooleanArray(2)
    val vergeW = arrayOf(FloatArray(RouteTextures.Verge.entries.size), FloatArray(RouteTextures.Verge.entries.size))
}

private class Lane {
    var mesh: MeshBuilder? = null
    var station = -1
    var first = 0
    var count = 0
}

private class RouteEmitter(
    private val ctx: TileContext,
    private val env: RouteEnv,
    private val detail: RouteLayer.Detail,
    private val out: TileOutput,
) {
    private val track = ctx.track
    private val node = WorldNames.NODE_ROUTE
    private val materials = HashMap<String, TileMaterial>()
    private val tmp = DoubleArray(5)
    private val oth = DoubleArray(3)
    private var stations: Array<Station> = emptyArray()
    private var runStartDist = 0.0

    /**
     * Offene Enden der Strecke: Dort endet das Band mitten auf dem Weg (Start/Ziel einer Strecke ohne Runde,
     * oder eine Runde, die auf demselben Weg zurueckkommt, auf dem sie losfuhr). Der breite, deckende
     * Uebergangsstreifen endete dort mit einer geraden Querkante — im Luftbild ein Rechteck in anderem Gruen
     * (Fehlerbericht Solitude, Befund 5). An offenen Enden laeuft er ueber [RouteLayer.END_TAPER_M] auf den Randstreifen aus.
     */
    private val openStart: Boolean
    private val openEnd: Boolean

    init {
        val t = track.track
        val n = t.sampleCount
        val joined = t.loop && n > 3 && run {
            val de = t.e[1] - t.e[0]; val dn = t.n[1] - t.n[0]
            val ee = t.e[n - 1] - t.e[n - 2]; val en = t.n[n - 1] - t.n[n - 2]
            val l = kotlin.math.hypot(de, dn) * kotlin.math.hypot(ee, en)
            l > 1e-9 && (de * ee + dn * en) / l > 0.5 && kotlin.math.hypot(t.e[0] - t.e[n - 1], t.n[0] - t.n[n - 1]) < 30.0
        }
        openStart = !joined
        openEnd = !joined
    }

    /** 0 am offenen Ende, 1 ab [RouteLayer.END_TAPER_M] davor bzw. dahinter. */
    private fun endTaper(dist: Double): Double {
        var k = 1.0
        if (openStart) k = min(k, smoothstep(0.0, RouteLayer.END_TAPER_M, dist))
        if (openEnd) k = min(k, smoothstep(0.0, RouteLayer.END_TAPER_M, track.lengthM - dist))
        return k
    }

    fun run(units: IntArray) {
        val keep = units.filter { !env.isDuplicate(it) }
        var a = 0
        while (a < keep.size) {
            var b = a
            while (b + 1 < keep.size && keep[b + 1] == keep[b] + 1) b++
            emitRun(RouteGeometry.unitStart(keep[a]), RouteGeometry.unitEnd(track, keep[b]))
            a = b + 1
        }
    }

    // --- Materialien ---------------------------------------------------------

    private fun mesh(material: TileMaterial): MeshBuilder = out.mesh(node, material)

    /**
     * Randgestaltung (Gehweg, Bordstein, Bankett, Fransen, Uebergangsstreifen, Linien) entsteht je
     * Stueck in Zwischennetzen und wird dann gegen die Sperrflaechen ([RouteEnv.mask]) geschnitten
     * ([flush]) — sie endet genau an der Kante einmuendender Strassen und anderer Streckenteile.
     */
    private val temp = LinkedHashMap<String, Pair<TileMaterial, MeshBuilder>>()

    private fun edgeMesh(material: TileMaterial): MeshBuilder = temp.getOrPut(material.name) { material to MeshBuilder() }.second

    private val temp2 = MeshBuilder()

    private fun flush(fromM: Double, toM: Double, lanes: Array<Lane>?) {
        val mask = env.mask
        val parts = env.trackMask
        val toE: (Double) -> Double = { x -> x + ctx.spec.anchorE }
        val toN: (Double) -> Double = { z -> ctx.spec.anchorN - z }
        for ((mat, mb) in temp.values) {
            if (mb.isEmpty) continue
            // Fahrbahn und Belagswechsel: nur an frueheren Streckenteilen abschneiden (der fruehere gewinnt).
            val road = mat.name.startsWith(WorldNames.MAT_ROUTE_PREFIX) || (mat.name.endsWith("_fade") && !mat.name.contains("verge"))
            // Was in die eigene Fahrbahn hineinreicht (Fransen, Bordstein, Linien), schneidet die eigene
            // Fahrbahn weiter vor und hinter dem Stueck nicht; was draussen liegt, schon ab wenigen Metern.
            val inner = mat.name == RouteLayer.MAT_FRINGE || mat.name == RouteLayer.MAT_PLAIN
            val own = if (inner) MASK_OWN_M else MASK_OWN_OUTER_M
            val dst = out.mesh(node, mat)
            if (road || inner) {
                val src = if (inner) {
                    temp2.clear()
                    clipInto(mb, temp2, mask, toE, toN, skipFrom = fromM - own, skipTo = toM + own, inner = true)
                    temp2
                } else {
                    mb
                }
                clipInto(src, dst, parts, toE, toN, skipFrom = fromM - MASK_OWN_ROAD_M, skipTo = Double.MAX_VALUE, inner = true)
            } else {
                clipInto(mb, dst, mask, toE, toN, skipFrom = fromM - own, skipTo = toM + own)
            }
            mb.clear()
        }
        if (lanes != null) for (k in lanes.indices) lanes[k].station = -1
    }

    private fun roadMesh(kind: SurfaceKind): MeshBuilder = edgeMesh(
        materials.getOrPut(RouteLayer.routeMaterialName(kind)) {
            TileMaterial(
                RouteLayer.routeMaterialName(kind), RouteTextures.surfaceTexture(ctx.textures, kind, ctx.quality == IndoorQuality.LOW),
                roughness = if (kind.visual == SurfaceKind.ASPHALT) 0.86f else 0.95f,
            )
        },
    )

    private fun fadeMesh(kind: SurfaceKind): MeshBuilder = edgeMesh(
        materials.getOrPut(RouteLayer.fadeMaterialName(kind)) {
            TileMaterial(
                RouteLayer.fadeMaterialName(kind), RouteTextures.surfaceTexture(ctx.textures, kind, ctx.quality == IndoorQuality.LOW),
                roughness = if (kind.visual == SurfaceKind.ASPHALT) 0.86f else 0.95f, alphaMode = GlbAlphaMode.MASK,
            )
        },
    )

    private fun vergePx(): Int = if (ctx.quality == IndoorQuality.LOW) 256 else 512

    private fun vergeMesh(v: RouteTextures.Verge): MeshBuilder = edgeMesh(
        materials.getOrPut(RouteLayer.vergeMaterialName(v)) {
            TileMaterial(RouteLayer.vergeMaterialName(v), RouteTextures.vergeTexture(ctx.textures, v, vergePx()), roughness = 1f)
        },
    )

    private fun vergeFadeMesh(v: RouteTextures.Verge): MeshBuilder = edgeMesh(
        materials.getOrPut(RouteLayer.vergeFadeMaterialName(v)) {
            TileMaterial(
                RouteLayer.vergeFadeMaterialName(v), RouteTextures.vergeFadeTexture(ctx.textures, v),
                roughness = 1f, alphaMode = GlbAlphaMode.BLEND, clampV = true,
            )
        },
    )

    private fun shoulderMesh(): MeshBuilder = edgeMesh(
        materials.getOrPut(RouteLayer.MAT_SHOULDER) {
            TileMaterial(RouteLayer.MAT_SHOULDER, RouteTextures.vergeTexture(ctx.textures, RouteTextures.Verge.SHOULDER), roughness = 1f)
        },
    )

    private fun sidewalkMesh(): MeshBuilder = edgeMesh(
        materials.getOrPut(RouteLayer.MAT_SIDEWALK) {
            TileMaterial(RouteLayer.MAT_SIDEWALK, RouteTextures.vergeTexture(ctx.textures, RouteTextures.Verge.SIDEWALK), roughness = 0.95f)
        },
    )

    private fun plainMesh(): MeshBuilder = edgeMesh(materials.getOrPut(RouteLayer.MAT_PLAIN) { TileMaterial(RouteLayer.MAT_PLAIN, roughness = 0.85f) })

    private fun fringeMesh(): MeshBuilder = edgeMesh(
        materials.getOrPut(RouteLayer.MAT_FRINGE) {
            TileMaterial(RouteLayer.MAT_FRINGE, RouteTextures.fringeTexture(ctx.textures), roughness = 1f, alphaMode = GlbAlphaMode.BLEND)
        },
    )

    // --- Stationen -----------------------------------------------------------

    private fun emitRun(a: Int, b: Int) {
        if (b <= a) return
        val xs = ArrayList<Double>()
        var i = a
        while (i < b) {
            val pa = env.profile(i)
            var span = 1
            if (detail.merge && i % 2 == 0 && i + 2 <= b) {
                val pm = env.profile(i + 1)
                val pb = env.profile(i + 2)
                val same = pa.sectionIndex == pm.sectionIndex && RouteGeometry.segSection(track, i + 1) == pa.sectionIndex
                if (same && !pa.nearRoad && !pm.nearRoad && !pb.nearRoad && !pa.nearOther && !pb.nearOther) span = 2
            }
            var sub = 1
            for (q in 0 until span) sub = max(sub, RouteGeometry.subdivisions(track, i + q, detail.maxSub))
            for (p in 0 until sub) xs.add(i + span.toDouble() * p / sub)
            i += span
        }
        xs.add(b.toDouble())
        runStartDist = track.track.distAt(a)
        stations = Array(xs.size) { station(xs[it]) }
        val lanes = Array(LANES) { Lane() }
        for (j in 0 until stations.size - 1) {
            piece(j, lanes)
            flush(stations[j].dist, stations[j + 1].dist, lanes)
        }
        flush(track.track.distAt(a), track.track.distAt(b), null)
    }

    private fun station(x: Double): Station {
        val t = track.track
        val st = Station(x)
        val seg = min(floor(x).toInt(), t.sampleCount - 2).coerceAtLeast(0)
        val f = x - seg
        st.seg = seg
        RouteGeometry.centerAt(track, seg, f, tmp)
        st.e = tmp[0]; st.n = tmp[1]; st.u = tmp[2]; st.te = tmp[3]; st.tn = tmp[4]
        st.dist = min(x * track.stepM, track.lengthM)
        st.grade = (t.u[seg + 1] - t.u[seg]) / max(1e-6, t.distAt(seg + 1) - t.distAt(seg))
        st.kappa = RouteGeometry.curvatureAt(track, seg) * (1 - f) + RouteGeometry.curvatureAt(track, seg + 1) * f
        st.hw = track.halfWidthAt(st.dist)
        val p0 = env.profile(seg)
        val p1 = env.profile(seg + 1)
        st.crown = p0.crown + (p1.crown - p0.crown) * f
        st.tone = RouteGeometry.tone(st.e, st.n, 0x70e)
        if (p0.nearOther || p1.nearOther) {
            env.hood.other(st.e, st.n, st.dist, RouteEnv.OTHER_GAP_M, oth)
            if (oth[1] >= 0 && oth[1] < seg && oth[0] < st.hw + oth[2]) st.lift = 0.008
        }
        for (side in 0..1) {
            var soft = 0.0
            for (q in -4..5) if (env.profile(seg + q).style[side] == EdgeStyle.SOFT) soft += if (q == 0 || q == 1) 1.0 - abs(q - f) else 1.0
            st.fringeW[side] = smoothstep(0.15, 0.85, soft / 9.0)
        }
        for (side in 0..1) {
            val sgn = if (side == 0) -1.0 else 1.0
            fun mix(a: Double, b: Double) = a + (b - a) * f
            val shoulder = mix(p0.shoulderW[side], p1.shoulderW[side])
            val innerRaw = mix(p0.inner[side], p1.inner[side])
            val taper = endTaper(st.dist)
            val inner = innerRaw * taper
            val end = mix(p0.end[side], p1.end[side]) * taper
            st.kerb[side] = mix(p0.kerb[side], p1.kerb[side])
            st.a0[side] = mix(p0.v0Alpha[side], p1.v0Alpha[side])
            st.t2[side] = st.hw + shoulder
            st.tV0[side] = st.hw + mix(p0.v0Off[side], p1.v0Off[side])
            st.tA[side] = st.t2[side] + min(1.5, inner * 0.5)
            st.tB[side] = st.t2[side] + inner
            st.tE[side] = st.t2[side] + end
            st.h1[side] = st.u - st.crown * st.hw + st.lift
            st.h2[side] = max(st.h1[side] + mix(p0.drop[side], p1.drop[side]) + st.kerb[side], groundAt(st, sgn * st.t2[side]) + 0.03)
            st.hE[side] = groundAt(st, sgn * st.tE[side]) + 0.03
            // Der Streifen laeuft von der Aussenkante des Randstreifens gleichmaessig auf den Boden am Ende
            // hinaus, statt der Mulde neben der Fahrbahn zu folgen (CarvedGround legt dort den Boden bis
            // 25 cm unter die Fahrbahn, damit das 4-m-Netz nicht durchsticht): frueher fiel das Gras neben
            // dem Gehweg auf 75 cm um bis zu 30 cm ab und stieg dahinter wieder an. Nie unter dem Boden.
            val span = max(0.1, st.tE[side] - st.t2[side])
            fun dressed(t: Double): Double {
                val f = smoothstep(0.0, 1.0, ((t - st.t2[side]) / span).coerceIn(0.0, 1.0))
                return st.h2[side] + (st.hE[side] - st.h2[side]) * f
            }
            st.hA[side] = max(groundAt(st, sgn * st.tA[side]) + 0.05, dressed(st.tA[side]))
            st.hB[side] = max(groundAt(st, sgn * st.tB[side]) + 0.04, dressed(st.tB[side]))
            for (c in 0..2) {
                st.tint[side][c] = (p0.tint[side][c] + (p1.tint[side][c] - p0.tint[side][c]) * f).toFloat()
            }
            val a0 = p0.aerial[side]
            val a1 = p1.aerial[side]
            if (a0 != null || a1 != null) {
                val x0 = a0 ?: a1!!
                val x1 = a1 ?: a0!!
                st.hasAerial[side] = true
                for (c in 0..2) st.aerial[side][c] = (x0[c] + (x1[c] - x0[c]) * f).toFloat()
            }
            val w = st.vergeW[side]
            w[p0.verge[side]!!.ordinal] += (1 - f).toFloat()
            w[p1.verge[side]!!.ordinal] += f.toFloat()
        }
        return st
    }

    private fun pointE(st: Station, t: Double): Double = st.e + st.tn * RouteGeometry.limitOffset(t, st.kappa)
    private fun pointN(st: Station, t: Double): Double = st.n - st.te * RouteGeometry.limitOffset(t, st.kappa)
    private val meshCell = level0TerrainCellM(ctx.quality)

    /** Boden fuer die Raender: das Maximum aus wahrem Boden und gezeichnetem Netz ([terrainMeshHeightAt]). */
    private fun groundAt(st: Station, t: Double): Double {
        val e = pointE(st, t)
        val n = pointN(st, t)
        return max(ctx.ground.heightAt(e, n), ctx.meshHeightAt(e, n, meshCell))
    }

    // --- Eckpunkte -----------------------------------------------------------

    /**
     * Eckpunkt bei seitlichem Versatz [t] (positiv = rechts) auf Hoehe [h];
     * [slope] ist die Querneigung `dh/dt` fuer die Normale.
     */
    private fun vert(m: MeshBuilder, st: Station, t: Double, h: Double, slope: Double, u: Double, v: Double, color: Int): Int {
        // Normale = oben − Steigung·Tangente − Querneigung·Rechts (ENU), dann nach glTF.
        val ne = -st.grade * st.te - slope * st.tn
        val nn = -st.grade * st.tn + slope * st.te
        val len = sqrt(ne * ne + nn * nn + 1.0)
        return m.vertex(
            ctx.x(pointE(st, t)), ctx.y(h), ctx.z(pointN(st, t)),
            ne / len, 1.0 / len, -nn / len, u, v, color,
        )
    }

    private inline fun strip(lane: Lane, m: MeshBuilder, j: Int, count: Int, emit: (Station) -> Int) {
        val a = if (lane.mesh === m && lane.station == j && lane.count == count) lane.first else emit(stations[j])
        val b = emit(stations[j + 1])
        for (q in 0 until count - 1) m.quad(a + q, a + q + 1, b + q + 1, b + q)
        lane.mesh = m
        lane.station = j + 1
        lane.first = b
        lane.count = count
    }

    private fun roadColor(st: Station, alpha: Double = 1.0): Int {
        val c = 0.84 + 0.16 * st.tone
        val w = (RouteGeometry.tone(st.e, st.n, 0x3a1) - 0.5) * 0.05
        return RouteGeometry.linColor(min(1.0, c + w), c, min(1.0, c - w), alpha)
    }

    /** Hoehe der Fahrbahn bei seitlichem Abstand [t] (≥ 0) von der Mitte. */
    private fun roadH(st: Station, t: Double): Double = st.u - st.crown * t + st.lift

    // --- Ein Stueck zwischen zwei Stationen ----------------------------------

    private fun piece(j: Int, lanes: Array<Lane>) {
        val a = stations[j]
        val b = stations[j + 1]
        val seg = min(floor((a.x + b.x) / 2).toInt(), track.count - 2)
        val p0 = env.profile(seg)
        val p1 = env.profile(seg + 1)
        val sec = p0.section
        val spec = RouteTextures.spec(sec.kind)

        // Fahrbahn.
        strip(lanes[0], roadMesh(sec.kind), j, 3) { st -> roadVerts(roadMesh(sec.kind), st, sec, spec, 0.0, 1.0) }

        // Belagswechsel: der „obere" Nachbarabschnitt laeuft ueber dieses Stueck aus.
        fade(j, seg, p0.sectionIndex, forward = true, lanes[1])
        fade(j, seg, p0.sectionIndex, forward = false, lanes[2])

        for (side in 0..1) {
            val sgn = if (side == 0) -1.0 else 1.0
            val s0 = p0.style[side]!!
            val s1 = p1.style[side]!!
            val base = 3 + side * LANES_PER_SIDE
            if (s0 == EdgeStyle.BANKETT && s1 == EdgeStyle.BANKETT) {
                val m = shoulderMesh()
                strip(lanes[base], m, j, 2) { st ->
                    val col = roadColor(st)
                    val slope = sgn * (st.h2[side] - st.h1[side]) / max(0.05, st.t2[side] - st.hw)
                    val v = (st.dist - runStartDist) / 2.56
                    if (side == 0) {
                        val i0 = vert(m, st, -st.t2[side], st.h2[side], slope, st.t2[side] / 2.56, v, col)
                        vert(m, st, -st.hw, st.h1[side], slope, st.hw / 2.56, v, col)
                        i0
                    } else {
                        val i0 = vert(m, st, st.hw, st.h1[side], slope, st.hw / 2.56, v, col)
                        vert(m, st, st.t2[side], st.h2[side], slope, st.t2[side] / 2.56, v, col)
                        i0
                    }
                }
            }
            // Der Gehweg beginnt und endet mit einem kurzen Keil (Bordstein laeuft auf null aus).
            val urban = s0 == EdgeStyle.URBAN || s1 == EdgeStyle.URBAN
            if (urban) kerbAndSidewalk(j, side, lanes, base)
            val grassWay = sec.kind.visual == SurfaceKind.GRASS
            if (detail.extras && (a.fringeW[side] > 0.01 || b.fringeW[side] > 0.01) && !grassWay) fringe(j, side, lanes[base + 3])
            // Ein Belag je Stueck (der staerkste): Der Streifen ist deckend, zwei Belaege koennen nicht mehr
            // ueber Vertex-Deckung ineinander laufen (warum: RouteTextures.roadEdgeTexture).
            var best: RouteTextures.Verge? = null
            var bestW = 0f
            for (kind in RouteTextures.Verge.entries) {
                if (kind == RouteTextures.Verge.SIDEWALK) continue
                val w = a.vergeW[side][kind.ordinal] + b.vergeW[side][kind.ordinal]
                if (w > bestW) { bestW = w; best = kind }
            }
            if (best != null) verge(j, side, best, lanes[base + 5 + best.ordinal], lanes[base + 5 + VERGE_FADE_LANE], lanes[base + 5 + VERGE_FADE_LANE + 1], urban)
        }
    }

    /** Drei Eckpunkte der Fahrbahn (links, Mitte, rechts); [lift] hebt an, [alpha] ist die Deckung. */
    private fun roadVerts(m: MeshBuilder, st: Station, sec: SurfaceSection, spec: RouteTextures.Spec, lift: Double, alpha: Double): Int {
        // Schmale Wege zeigen nur eine Fahrspur der zweispurigen Texturen — die Spur liegt dann in der Wegmitte.
        val shift = if (spec.twoTrack && sec.effectiveWidthM < 2.2) RouteTextures.TRACK_OFFSET_M else 0.0
        val v = (st.dist - floor(runStartDist / spec.lengthM) * spec.lengthM) / spec.lengthM
        var cl = roadColor(st, alpha)
        var cc = cl
        var cr = cl
        if (sec.kind.visual == SurfaceKind.GRASS) {
            // Ein Wiesenweg hat die Farbe der Wiese, durch die er fuehrt.
            cl = vergeColor(st, 0, RouteTextures.Verge.GRASS, 0.7, alpha)
            cr = vergeColor(st, 1, RouteTextures.Verge.GRASS, 0.7, alpha)
            cc = Argb.mix(cl, cr, 0.5)
        }
        val i0 = vert(m, st, -st.hw, roadH(st, st.hw) + lift, st.crown, 0.5 + (-st.hw + shift) / spec.widthM, v, cl)
        vert(m, st, 0.0, roadH(st, 0.0) + lift, 0.0, 0.5 + shift / spec.widthM, v, cc)
        vert(m, st, st.hw, roadH(st, st.hw) + lift, -st.crown, 0.5 + (st.hw + shift) / spec.widthM, v, cr)
        return i0
    }

    /**
     * Uebergang am Belagswechsel: Liegt innerhalb von [RouteLayer.FADE_M] vor ([forward]) bzw. hinter
     * diesem Stueck die Grenze zu einem Abschnitt, der „oben" liegt, greift dessen Belag mit einer
     * unregelmaessigen Kante (`alphaMode = MASK`, Schwelle 0,5) in diesen hinein — je Spur verschieden
     * weit (±1,5 m um 3 m). Frueher lief er mit Vertex-Deckung aus (BLEND); die wird von Filaments
     * MSAA am Dreiecksrand negativ fortgeschrieben (helle Punkte). Bei MASK verwirft der Shader solche
     * Stellen nur.
     */
    private fun fade(j: Int, seg: Int, own: Int, forward: Boolean, lane: Lane) {
        val reach = ceil(RouteLayer.FADE_M / track.stepM).toInt()
        var boundary = -1
        var other = -1
        for (k in 1..reach) {
            val q = if (forward) seg + k else seg - k
            if (q < 0 || q > track.count - 2) break
            val s = RouteGeometry.segSection(track, q)
            if (s != own) {
                boundary = if (forward) q else q + 1
                other = s
                break
            }
        }
        if (other < 0 || !isUpper(other, own)) return
        val sec = track.track.surfaces[other]
        if (sec.kind.visual == track.track.surfaces[own].kind.visual) return
        val bd = track.track.distAt(boundary)
        val a = stations[j]
        val b = stations[j + 1]
        fun base(st: Station) = 1.0 - abs(st.dist - bd) / RouteLayer.FADE_M
        if (base(a) <= 0.0 && base(b) <= 0.0) return
        val m = fadeMesh(sec.kind)
        val spec = RouteTextures.spec(sec.kind)
        val shift = if (spec.twoTrack && sec.effectiveWidthM < 2.2) RouteTextures.TRACK_OFFSET_M else 0.0
        // Versatz der Kante je Spur, fest je Grenze (in jeder Kachel gleich).
        val jitter = DoubleArray(5) { q -> (RouteGeometry.tone(bd * 0.37 + q * 1.7, bd * 0.11 - q * 2.3, 0x5f3) - 0.5) * 0.5 }
        strip(lane, m, j, 5) { st ->
            val v = (st.dist - floor(runStartDist / spec.lengthM) * spec.lengthM) / spec.lengthM
            // Volle Breite (vorher endete das Band an der Innenkante der Fransen: im Bild eine Rechteck-Stufe).
            // Die Fransen liegen 1,3 cm darueber (FRINGE_LIFT_M), nicht 3 mm.
            val inner = max(st.hw - 0.6, st.hw * 0.4)
            var first = -1
            for (q in 0..4) {
                val t = when (q) {
                    0 -> -st.hw
                    1 -> -inner
                    2 -> 0.0
                    3 -> inner
                    else -> st.hw
                }
                // Kante je Eckpunkt aus der Weltlage verschoben (±2 m): unregelmaessige Flecken statt einer Querlinie.
                val wob = (RouteGeometry.tone(pointE(st, t) * 6.0, pointN(st, t) * 6.0, 0x5f7) - 0.5) * 0.8
                val al = (base(st) + jitter[q] * 0.5 + wob).coerceIn(0.0, 1.0)
                val slope = if (t < 0) st.crown else if (t > 0) -st.crown else 0.0
                val idx = vert(m, st, t, roadH(st, kotlin.math.abs(t)) + RouteLayer.OVERLAY_LIFT_M, slope, 0.5 + (t + shift) / spec.widthM, v, roadColor(st, al))
                if (first < 0) first = idx
            }
            first
        }
    }

    /** Von zwei benachbarten Abschnitten liegt der mit ungeradem Index oben; bei gleicher Paritaet der spaetere. */
    private fun isUpper(candidate: Int, other: Int): Boolean =
        if ((candidate and 1) != (other and 1)) (candidate and 1) == 1 else candidate > other

    private fun kerbAndSidewalk(j: Int, side: Int, lanes: Array<Lane>, base: Int) {
        val sgn = if (side == 0) -1.0 else 1.0
        val plain = plainMesh()
        val kw = RouteLayer.KERB_WIDTH_M
        // Bordstein: Ansichtsflaeche und Oberseite, Granitgrau.
        strip(lanes[base + 1], plain, j, 3) { st ->
            val g = 0.30 + 0.05 * st.tone
            val col = RouteGeometry.linColor(g, g, g * 0.97)
            val top = st.h1[side] + st.kerb[side]
            if (side == 0) {
                val i0 = vert(plain, st, -(st.hw + kw), top, 0.0, 0.0, 0.0, col)
                vert(plain, st, -st.hw - 0.02, top, 0.0, 0.0, 0.0, col)
                vert(plain, st, -st.hw, st.h1[side] - 0.01, -3.0, 0.0, 0.0, RouteGeometry.linColor(g * 0.8, g * 0.8, g * 0.78))
                i0
            } else {
                val i0 = vert(plain, st, st.hw, st.h1[side] - 0.01, 3.0, 0.0, 0.0, RouteGeometry.linColor(g * 0.8, g * 0.8, g * 0.78))
                vert(plain, st, st.hw + 0.02, top, 0.0, 0.0, 0.0, col)
                vert(plain, st, st.hw + kw, top, 0.0, 0.0, 0.0, col)
                i0
            }
        }
        val m = sidewalkMesh()
        strip(lanes[base + 2], m, j, 2) { st ->
            val c = 0.86 + 0.14 * st.tone
            val col = RouteGeometry.linColor(c, c, c)
            val v = (st.dist - runStartDist) / 2.56
            val inH = st.h1[side] + st.kerb[side]
            val w = st.t2[side] - st.hw - kw
            if (side == 0) {
                val i0 = vert(m, st, -st.t2[side], st.h2[side], sgn * 0.015, w / 2.56, v, col)
                vert(m, st, -(st.hw + kw), inH, sgn * 0.015, 0.0, v, col)
                i0
            } else {
                val i0 = vert(m, st, st.hw + kw, inH, sgn * 0.015, 0.0, v, col)
                vert(m, st, st.t2[side], st.h2[side], sgn * 0.015, w / 2.56, v, col)
                i0
            }
        }
    }

    /** Farbe des Uebergangsstreifens: Abtoenung, je weiter draussen desto staerker am Luftbild. */
    private fun vergeColor(st: Station, side: Int, kind: RouteTextures.Verge, strength: Double, alpha: Double): Int {
        val t = st.tint[side]
        val tone = 0.88 + 0.12 * st.tone
        var r = t[0].toDouble()
        var g = t[1].toDouble()
        var b = t[2].toDouble()
        if (st.hasAerial[side]) {
            val a = st.aerial[side]
            val m = kind.meanLinear
            if (kind == RouteTextures.Verge.GRASS) {
                // Gras nimmt Farbe und Helligkeit des Luftbilds an (Wiese, Rain und Rasen sehen von oben aus wie von nah).
                r += ((a[0] / m[0]).toDouble().coerceIn(r * 0.5, r * 1.7) - r) * strength
                g += ((a[1] / m[1]).toDouble().coerceIn(g * 0.5, g * 1.7) - g) * strength
                b += ((a[2] / m[2]).toDouble().coerceIn(b * 0.5, b * 1.7) - b) * strength
            } else {
                // Waldboden und Schotter: frueher nur zum Rand hin (Staerke hoch drei) ans Luftbild angeglichen —
                // solange der Streifen durchschien, passte das. Deckend stand er dunkelbraun und hart begrenzt neben
                // Weg und Boden. Jetzt wie Gras an die Farbe des Bodens daneben angeglichen, etwas weniger stark.
                val s = strength * 0.85
                r += ((a[0] / m[0]).toDouble().coerceIn(r * 0.4, r * 2.0) - r) * s
                g += ((a[1] / m[1]).toDouble().coerceIn(g * 0.4, g * 2.0) - g) * s
                b += ((a[2] / m[2]).toDouble().coerceIn(b * 0.4, b * 2.0) - b) * s
            }
        }
        return RouteGeometry.linColor(min(1.0, r * tone), min(1.0, g * tone), min(1.0, b * tone), alpha.coerceIn(0.0, 1.0))
    }

    /**
     * Uebergangsstreifen einer Seite: deckend vom Randstreifen bis `tB` (Randtextur in Weltkoordinaten),
     * dann ein Auslauf bis `tE`, dessen Deckung in der Textur steckt ([RouteTextures.vergeFadeTexture]).
     * Keine Vertex-Deckung: Filaments MSAA schreibt sie am Dreiecksrand negativ fort (weisse Punkte).
     */
    private fun verge(j: Int, side: Int, kind: RouteTextures.Verge, lane: Lane, fadeLane: Lane, fadeInLane: Lane, urban: Boolean) {
        val a = stations[j]
        val b = stations[j + 1]
        // Am Gehweg beginnt der Streifen erst an dessen Aussenkante; am Bankett ebenso (frueher lief er
        // durchsichtig ueber das Bankett ein — mit deckendem Streifen wuerde er es verdecken).
        val skipFirst = urban || a.a0[side] < 0.5 || b.a0[side] < 0.5 ||
            (abs(a.tV0[side] - a.t2[side]) < 1e-3 && abs(b.tV0[side] - b.t2[side]) < 1e-3)
        // Auf der Innenseite enger Kurven drueckt limitOffset die Ringe zusammen; Gras, Auslauf und Gehweg fielen
        // dort uebereinander (Flimmern). Dort entfaellt der Streifen fuer dieses Stueck.
        for (st in arrayOf(a, b)) {
            val tA = st.tA[side] * (if (side == 0) -1.0 else 1.0)
            if (abs(RouteGeometry.limitOffset(tA, st.kappa) - tA) > 0.02) return
        }
        val m = vergeMesh(kind)
        val size = kind.sizeM
        val e0 = floor(ctx.spec.minE / size) * size
        val n0 = floor(ctx.spec.minN / size) * size
        // Deckend nur bis tA (hoechstens 1,5 m hinter dem Randstreifen), dann laeuft der Streifen weich aus.
        val rings = (if (skipFirst) 0 else 1) + 2
        val sgn = if (side == 0) -1.0 else 1.0
        strip(lane, m, j, rings) { st ->
            val ts = DoubleArray(rings)
            val hs = DoubleArray(rings)
            val strength = DoubleArray(rings)
            var k = 0
            if (!skipFirst) {
                val span = max(0.05, st.t2[side] - st.hw)
                ts[k] = st.tV0[side]
                hs[k] = st.h1[side] + (st.h2[side] - st.h1[side]) * ((st.tV0[side] - st.hw) / span).coerceIn(0.0, 1.0) + RouteLayer.OVERLAY_LIFT_M
                strength[k] = 0.8
                k++
            }
            ts[k] = st.t2[side]; hs[k] = st.h2[side] + RouteLayer.OVERLAY_LIFT_M; strength[k] = 0.8; k++
            ts[k] = st.tA[side]; hs[k] = st.hA[side]; strength[k] = 0.95
            var first = -1
            for (q in 0 until rings) {
                // Links laeuft die Reihenfolge von aussen nach innen (Eckpunkte immer von links nach rechts).
                val r = if (side == 0) rings - 1 - q else q
                val lo = max(0, r - 1)
                val hi = min(rings - 1, r + 1)
                val slope = sgn * (hs[hi] - hs[lo]) / max(0.05, ts[hi] - ts[lo])
                val t = sgn * ts[r]
                val pe = pointE(st, t)
                val pn = pointN(st, t)
                val idx = vert(m, st, t, hs[r], slope, (pe - e0) / size, -(pn - n0) / size, vergeColor(st, side, kind, strength[r], 1.0))
                if (first < 0) first = idx
            }
            first
        }
        // Auslauf: tB → tE, u laengs (Laufmeter), v quer 0 → 1.
        val f = vergeFadeMesh(kind)
        val uBase = floor(runStartDist / size) * size
        if (detail.extras && !urban && (a.a0[side] < 0.5 || b.a0[side] < 0.5)) {
            // Am Bankett laeuft der Streifen von V0 bis zum deckenden Teil ein (wie frueher, jetzt mit der
            // Deckung aus der Textur: v = 1 an V0, v = 0 am Randstreifen).
            strip(fadeInLane, f, j, 2) { st ->
                val span = max(0.05, st.t2[side] - st.hw)
                val h0 = st.h1[side] + (st.h2[side] - st.h1[side]) * ((st.tV0[side] - st.hw) / span).coerceIn(0.0, 1.0) + RouteLayer.OVERLAY_LIFT_M
                val h2 = st.h2[side] + RouteLayer.OVERLAY_LIFT_M
                val slope = sgn * (h2 - h0) / max(0.05, st.t2[side] - st.tV0[side])
                val u = (st.dist - uBase) / size
                val col = vergeColor(st, side, kind, 0.7, 1.0)
                if (side == 0) {
                    val i0 = vert(f, st, -st.t2[side], h2, slope, u, 0.0, col)
                    vert(f, st, -st.tV0[side], h0, slope, u, 1.0, col)
                    i0
                } else {
                    val i0 = vert(f, st, st.tV0[side], h0, slope, u, 1.0, col)
                    vert(f, st, st.t2[side], h2, slope, u, 0.0, col)
                    i0
                }
            }
        }
        val fadeRings = if (detail.vergeRings >= 3) 3 else 2
        if (detail.vergeRings >= 2) strip(fadeLane, f, j, fadeRings) { st ->
            // tA → tB → tE; die Textur laeuft ueber v = 0 … 1 aus, tB liegt dazwischen nach Abstand.
            val span = max(0.05, st.tE[side] - st.tA[side])
            val vB = ((st.tB[side] - st.tA[side]) / span).coerceIn(0.05, 0.95)
            val u = (st.dist - uBase) / size
            val tsF = if (fadeRings == 3) doubleArrayOf(st.tA[side], st.tB[side], st.tE[side]) else doubleArrayOf(st.tA[side], st.tE[side])
            val hsF = if (fadeRings == 3) doubleArrayOf(st.hA[side], st.hB[side], st.hE[side]) else doubleArrayOf(st.hA[side], st.hE[side])
            val vs = if (fadeRings == 3) doubleArrayOf(0.0, vB, 1.0) else doubleArrayOf(0.0, 1.0)
            val last = fadeRings - 1
            var first = -1
            for (q in 0..last) {
                val r = if (side == 0) last - q else q
                val lo = max(0, r - 1)
                val hi = min(last, r + 1)
                val slope = sgn * (hsF[hi] - hsF[lo]) / max(0.05, tsF[hi] - tsF[lo])
                val idx = vert(f, st, sgn * tsF[r], hsF[r], slope, u, vs[r], vergeColor(st, side, kind, 1.0, 1.0))
                if (first < 0) first = idx
            }
            first
        }
    }

    /** Ausgefranste Kante: ein schmales Band ueber dem Fahrbahnrand, dessen Textur unregelmaessig deckt. */
    private fun fringe(j: Int, side: Int, lane: Lane) {
        val m = fringeMesh()
        val sgn = if (side == 0) -1.0 else 1.0
        strip(lane, m, j, 2) { st ->
            val w = st.vergeW[side]
            var r = 0.0
            var g = 0.0
            var b = 0.0
            for (kind in RouteTextures.Verge.entries) {
                val k = w[kind.ordinal]
                if (k <= 0f) continue
                val c = vergeColor(st, side, kind, 0.7, 1.0)
                r += k * kind.meanLinear[0] * Argb.red(c) / 255.0
                g += k * kind.meanLinear[1] * Argb.green(c) / 255.0
                b += k * kind.meanLinear[2] * Argb.blue(c) / 255.0
            }
            // Die Fransentextur ist im Mittel FRINGE_BASE hell; die Vertexfarbe bringt sie auf die Farbe des Rands.
            val k = FRINGE_SHADE / FRINGE_BASE
            val col = RouteGeometry.linColor(min(1.0, r * k), min(1.0, g * k), min(1.0, b * k))
            // Die Fransen liegen nur auf der Fahrbahn und enden an ihrer Kante, wo der deckende Rand beginnt:
            // Frueher reichten sie 28 cm ueber den Rand und lagen dort 0,3–0,8 cm ueber dem Gras — seit das
            // Gras deckend ist, ein Z-Kampf (Flimmern am Fahrbahnrand). Jetzt stossen beide aneinander.
            val tout = st.hw + FRINGE_OUT_M
            val tin = max(st.hw - FRINGE_IN_M * st.fringeW[side], max(st.hw * 0.4, st.hw - FRINGE_IN_M))
            val hin = roadH(st, tin) + FRINGE_LIFT_M
            val hout = roadH(st, tout) + FRINGE_LIFT_M
            val u = st.dist / RouteTextures.FRINGE_LENGTH_M - floor(runStartDist / RouteTextures.FRINGE_LENGTH_M) + side * 0.37
            val slope = sgn * (hout - hin) / (tout - tin)
            if (side == 0) {
                val i0 = vert(m, st, -tout, hout, slope, u, 1.0, col)
                vert(m, st, -tin, hin, slope, u, 0.0, col)
                i0
            } else {
                val i0 = vert(m, st, tin, hin, slope, u, 0.0, col)
                vert(m, st, tout, hout, slope, u, 1.0, col)
                i0
            }
        }
    }

    private companion object {
        const val LANES_PER_SIDE = 11

        /**
         * Die eigene Fahrbahn bis zu so vielen Laufmetern vor und hinter einem Stueck sperrt dessen Rand
         * nicht; weiter entfernte Streckenteile schon (enge Kurven, Kreisel, Hin- und Rueckweg nebeneinander).
         */
        const val MASK_OWN_M = 15.0

        /**
         * Fahrbahn und was auf ihr liegt wird an frueheren Streckenteilen ab so vielen Laufmetern Abstand
         * abgeschnitten — auch in engen Wendeschleifen (Radius 2–5 m), wo sich Stuecke 5–18 m auseinander
         * mit 2–5 mm Hoehenabstand ueberlagerten.
         */
        const val MASK_OWN_ROAD_M = 3.0

        /** Dasselbe fuer Rand ausserhalb der Fahrbahn (Gehweg, Bankett, Uebergangsstreifen). */
        const val MASK_OWN_OUTER_M = 4.0

        /** Spur des Auslaufs im Seitenblock (hinter den Randtexturen). */
        const val VERGE_FADE_LANE = 4
        const val LANES = 3 + 2 * LANES_PER_SIDE

        /** Mittlere Helligkeit der Fransentextur (linear). */
        const val FRINGE_BASE = 0.60

        /** Die Kante ist etwas dunkler als die Flaeche (dichter, niedergetretener Wuchs; halb deckende Stellen hellt der Weg auf). */
        const val FRINGE_SHADE = 0.78

        /** So weit reicht die Fransenkante ueber den Fahrbahnrand hinaus. */
        const val FRINGE_OUT_M = 0.0

        /** So weit reicht die Fransenkante vom Fahrbahnrand in die Fahrbahn hinein. */
        const val FRINGE_IN_M = 0.52

        /** So hoch liegen die Fransen ueber der Fahrbahn — 1,3 cm ueber dem Band des Belagswechsels. */
        const val FRINGE_LIFT_M = 0.025
    }
}
