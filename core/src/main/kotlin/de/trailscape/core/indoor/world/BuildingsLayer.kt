package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.world.BuildingTextures.BAY_M
import de.trailscape.core.indoor.world.BuildingTextures.Band
import de.trailscape.core.indoor.world.BuildingTextures.Decal
import de.trailscape.core.indoor.world.BuildingTextures.STOREY_M
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * # Gebaeude
 *
 * Macht aus den Grundrissen der Karte Haeuser, wie sie in einem Dorf im
 * Bergischen Land stehen: Grundriss und Lage stimmen (OpenStreetMap), Hoehe,
 * Dachform, Fassade und Farben werden je Gebaeude **fest** aus Flaeche, Form
 * und Schwerpunkt abgeleitet ([BuildingDesign]) — derselbe Entwurf in jeder
 * Stufe und bei jedem Bau.
 *
 * Ein Gebaeude gehoert genau der Kachel, in der sein Schwerpunkt liegt, und
 * wird dort vollstaendig gebaut. Wie fein, entscheidet der Abstand zur Strecke
 * und das Dreiecksbudget der Kachel ([BuildingDetail]): nah mit Dachueberstand,
 * Rinne, Schornstein, Gauben, Tuer und Sockel, fern als Kubatur mit Dach.
 * Reicht das Budget nicht, wird zuerst das Fernste vereinfacht, zuletzt fallen
 * die kleinsten Gebaeude weg. Stufe 1 baut alle Gebaeude ohne Textur in den
 * mittleren Farben des Atlas; Stufe 2 baut keine.
 *
 * Waende, Dachhaut und Zubehoer liegen in zwei Materialien
 * ([WorldNames.MAT_BUILDING_WALL], [WorldNames.MAT_BUILDING_ROOF]) auf demselben
 * Atlas ([BuildingTextures]); die Farbe des einzelnen Hauses steckt in den
 * Vertexfarben.
 */
internal class BuildingsLayer : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        if (ctx.level >= 2) return
        val budget = ctx.budget.buildings
        if (budget <= 0) return
        val designs = designs(ctx)
        if (designs.isEmpty()) return
        val textured = ctx.level == 0
        val atlas = if (textured) BuildingTextures.atlas(ctx.textures) else null
        val wallMat = TileMaterial(WorldNames.MAT_BUILDING_WALL, atlas, repeat = true, roughness = 0.92f)
        val roofMat = TileMaterial(WorldNames.MAT_BUILDING_ROOF, atlas, repeat = true, roughness = 0.85f)

        val details = Array(designs.size) { desiredDetail(designs[it], textured) }
        val meshes = Array(designs.size) { mesh(ctx, designs[it], details[it], textured) }
        var total = meshes.sumOf { it.triangles }
        // Wer zuerst vereinfacht wird: in Stufe 0 das Fernste, in Stufe 1 das Kleinste.
        val order = designs.indices.sortedWith(
            if (textured) compareBy<Int>({ -designs[it].trackDistance }, { it }) else compareBy<Int>({ designs[it].areaM2 }, { it }),
        )
        while (total > budget) {
            var changed = false
            for (i in order) {
                if (total <= budget) break
                val next = simpler(details[i], textured) ?: continue
                total -= meshes[i].triangles
                details[i] = next
                meshes[i] = mesh(ctx, designs[i], next, textured)
                total += meshes[i].triangles
                changed = true
            }
            if (!changed) break
        }
        val dropped = BooleanArray(designs.size)
        if (total > budget) {
            // Selbst als Bloecke zu viele: die kleinsten weglassen.
            for (i in designs.indices.sortedWith(compareBy<Int>({ designs[it].areaM2 }, { it }))) {
                if (total <= budget) break
                dropped[i] = true
                total -= meshes[i].triangles
            }
        }
        val aerialRoofs = AerialRoofs.of(ctx) // Dachbild aus dem Luftbild (Detailtreue), sonst Atlas
        for (i in designs.indices) {
            if (dropped[i]) continue
            val d = designs[i]
            out.mesh(WorldNames.NODE_BUILDINGS, wallMat, d.ce, d.cn).append(meshes[i].wall)
            if (aerialRoofs?.adopt(meshes[i].roof, d.ridgeU - d.groundMinU) { out.mesh(WorldNames.NODE_BUILDINGS, aerialRoofs.material, d.ce, d.cn) } == true) continue
            out.mesh(WorldNames.NODE_BUILDINGS, roofMat, d.ce, d.cn).append(meshes[i].roof)
        }
    }

    /** Die Entwuerfe aller Gebaeude, deren Schwerpunkt in der Kachel liegt — in fester Reihenfolge. */
    fun designs(ctx: TileContext): List<BuildingDesign> {
        val s = ctx.spec
        val seen = HashSet<Long>()
        return ctx.map.buildings(s.minE, s.minN, s.maxE, s.maxN)
            .mapNotNull { b ->
                val outer = b.rings.firstOrNull() ?: return@mapNotNull null
                if (outer.size < 6 || !outer.all { it.isFinite() }) return@mapNotNull null
                val c = FootprintShape.centroid(outer)
                if (!s.contains(c[0], c[1])) return@mapNotNull null
                // Dasselbe Gebaeude doppelt in den Daten (Kachelrand der Quelle): nur einmal bauen.
                val key = ((c[0] * 4).roundToLong() shl 32) xor ((c[1] * 4).roundToLong() and 0xffffffffL)
                if (!seen.add(key)) return@mapNotNull null
                BuildingDesign.of(b, ctx)
            }
            .sortedWith(compareBy({ it.ce }, { it.cn }, { it.areaM2 }))
    }

    private fun desiredDetail(d: BuildingDesign, textured: Boolean): BuildingDetail = when {
        !textured -> BuildingDetail.SIMPLE
        d.trackDistance < FULL_DETAIL_M -> BuildingDetail.FULL
        d.trackDistance < MID_DETAIL_M -> BuildingDetail.MID
        else -> BuildingDetail.SIMPLE
    }

    private fun simpler(d: BuildingDetail, textured: Boolean): BuildingDetail? = when (d) {
        BuildingDetail.FULL -> BuildingDetail.MID
        BuildingDetail.MID -> BuildingDetail.SIMPLE
        BuildingDetail.SIMPLE -> if (textured) BuildingDetail.PLAIN else BuildingDetail.BLOCK
        BuildingDetail.PLAIN -> BuildingDetail.BLOCK
        BuildingDetail.BLOCK -> null
    }

    companion object {
        /** Bis hierher (Abstand Gebaeude–Strecke) volle Einzelheiten. */
        const val FULL_DETAIL_M = 60.0

        /** Bis hierher Dachueberstand und Schornstein. */
        const val MID_DETAIL_M = 130.0

        /** Baut ein einzelnes Gebaeude in der verlangten Feinheit (Lagen relativ zum Kachelanker). */
        fun mesh(ctx: TileContext, d: BuildingDesign, detail: BuildingDetail, textured: Boolean): BuildingMesh {
            val m = BuildingMesher(ctx, d, detail, textured)
            m.build()
            return BuildingMesh(m.wall, m.roof)
        }
    }
}

/** Wie fein ein Gebaeude gebaut wird — vom Nahbereich bis zum Notbehelf bei knappem Budget. */
internal enum class BuildingDetail {
    /** Alles: Fensterachsen mit Randstuecken, Sockel, Tuer, Stufe, Dachkoerper mit Ueberstand, Rinne, Schornstein, Gauben. */
    FULL,

    /** Fensterachsen, Sockel, Dachkoerper mit Ueberstand, Schornstein. */
    MID,

    /** Kubatur mit Fenstertextur und Dachhaut ohne Ueberstand. */
    SIMPLE,

    /** Kubatur mit glatter Wand und Dachhaut. */
    PLAIN,

    /** Quader in halber Dachhoehe. */
    BLOCK,
}

internal enum class BuildingKind { SHED, GARAGE, HOUSE, BARN, HALL }

internal enum class FacadeStyle(val windows: Band?, val plain: Band?) {
    PLASTER(Band.PLASTER_WINDOWS, Band.PLASTER),
    BRICK(Band.BRICK_WINDOWS, Band.BRICK),
    SLATE(Band.SLATE_WINDOWS, Band.SLATE),

    /** Fachwerk: fensterlose Flaechen nehmen das Feld ohne Fenster aus dem Fensterband. */
    TIMBER(Band.TIMBER_WINDOWS, null),
    WOOD(null, Band.WOOD),
    SHEET(null, Band.SHEET),
}

internal enum class RoofCover(val band: Band) { TILE(Band.PANTILE), SHEET(Band.SHEET), BITUMEN(Band.BITUMEN) }

internal enum class RoofForm { WINGS, PENT, FLAT }

internal class BuildingMesh(val wall: MeshBuilder, val roof: MeshBuilder) {
    val triangles: Int get() = wall.triangleCount + roof.triangleCount
}

/** Aufteilung einer Wand in Fensterachsen: Rand links/rechts, Zahl und Breite der Achsen. */
internal class BayLayout(val margin: Double, val count: Int, val width: Double)

/**
 * Fensterachsen einer Wand der Laenge [len]. Passt die Laenge bis auf 11 % in
 * ganze Achsen, werden sie leicht gedehnt; sonst bleiben glatte Randstuecke
 * (nie halbe Fenster an den Ecken). [exact] dehnt immer (fuer die Ferne).
 */
internal fun bayLayout(len: Double, exact: Boolean): BayLayout? {
    if (len < 1.9) return null
    val n0 = (len / BAY_M).roundToInt()
    if (exact) return BayLayout(0.0, max(1, n0), len / max(1, n0))
    if (n0 >= 1 && abs(len / n0 - BAY_M) <= 0.30) return BayLayout(0.0, n0, len / n0)
    val n = floor((len - 0.5) / BAY_M).toInt()
    if (n < 1) return null
    return BayLayout((len - n * BAY_M) / 2, n, BAY_M)
}

/** Eine Wandkante des Grundrisses im Gebaeudesystem, Normale (`nx`, `ny`) nach aussen. */
internal class BuildingEdge(val index: Int, val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val len: Double = hypot(x1 - x0, y1 - y0)
    val dx: Double = (x1 - x0) / len
    val dy: Double = (y1 - y0) / len
    val nx: Double = dy
    val ny: Double = -dx

    /** Der Fluegel, dessen Giebel auf dieser Kante steht. */
    var gable: RoofWing? = null

    /** Der Fluegel, zu dessen Teil des Grundrisses die Wand gehoert (`null`: alle Fluegel gleich hoch). */
    var piece: RoofWing? = null

    /**
     * Unterkante als ENU-`u`, wenn die Wand nicht am Boden beginnt: die Wand
     * des Hauptbaus ueber dem Dach eines niedrigeren Anbaus. Sonst `NaN`.
     */
    var fromU: Double = Double.NaN
    val upper: Boolean get() = !fromU.isNaN()

    /** Gelaendehoehe an den beiden Enden der Kante (ENU-`u`). */
    var ground0 = Double.NaN
    var ground1 = Double.NaN

    /** Enden der Kante: [CONVEX] Aussenecke, [REFLEX] einspringende Ecke, [SPLIT] die Wand laeuft gerade weiter. */
    var end0 = CONVEX
    var end1 = CONVEX

    companion object {
        const val CONVEX = 0
        const val REFLEX = 1
        const val SPLIT = 2
    }
}

/** Ein Aufsetzteil (Tuer, Tor) an einer Kante: Mitte [s] entlang der Kante, Breite und Hoehe in Metern. */
internal class BuildingDecal(val edge: Int, val s: Double, val decal: Decal, val widthM: Double, val heightM: Double, val color: Int)

/**
 * Der Entwurf eines Gebaeudes: alles, was nicht von der Feinheit abhaengt.
 * Koordinaten des Grundrisses im **Gebaeudesystem**: Ursprung im Schwerpunkt
 * (`ce`, `cn`), x entlang der Hauptachse (um [angle] gegen Osten gedreht), y
 * quer dazu. Hoehen als ENU-`u`.
 */
internal class BuildingDesign private constructor(
    val seed: Int,
    val ce: Double,
    val cn: Double,
    val areaM2: Double,
    val angle: Double,
    /** Aussenring gegen, Loecher im Uhrzeigersinn. */
    val rings: List<DoubleArray>,
    /** `true`, wenn der Grundriss rechtwinklig ausgerichtet wurde. */
    val squared: Boolean,
    val kind: BuildingKind,
    val style: FacadeStyle,
    val form: RoofForm,
    val wings: List<RoofWing>,
    /** Dachneigung als Tangens. */
    val tan: Double,
    val cover: RoofCover,
    /** Oberkante Fussboden = hoechster Gelaendepunkt + Sockel. */
    val floorU: Double,
    val groundMinU: Double,
    val groundMaxU: Double,
    /** Traufhoehe: Dachhaut an der Wandflucht (bei Flachdach die Oberkante der Attika). */
    val eaveTopU: Double,
    /** Hoechster First. */
    val ridgeU: Double,
    val storeys: Int,
    val storeyH: Double,
    val eaveOver: Double,
    val vergeOver: Double,
    val roofThickness: Double,
    /** Schwebt ueber dem Boden (`min_height`): kein Sockel, Unterseite geschlossen. */
    val raised: Boolean,
    val trackDistance: Double,
    val edges: List<BuildingEdge>,
    val decals: List<BuildingDecal>,
    val wallTint: Int,
    val socleTint: Int,
    val roofTint: Int,
    val trimTint: Int,
    val soffitTint: Int,
    /** Deckung und Farbe der Pult- und Flachdaecher niedriger Anbauten. */
    val annexCover: RoofCover,
    val annexTint: Int,
) {
    private val cosA = cos(angle)
    private val sinA = sin(angle)

    fun eOf(x: Double, y: Double): Double = ce + x * cosA - y * sinA
    fun nOf(x: Double, y: Double): Double = cn + x * sinA + y * cosA

    fun rnd(k: Int): Double = Noise.hash01(k, 0x5eed, seed)

    /** Hoehe der Dachhaut ueber einer Stelle des Grundrisses (ENU-`u`). */
    fun roofTopAt(x: Double, y: Double): Double {
        if (form == RoofForm.FLAT) return eaveTopU
        var h = Double.NEGATIVE_INFINITY
        for (w in wings) if (w.contains(x, y, 1e-6)) h = max(h, w.topAt(x, y))
        return if (h == Double.NEGATIVE_INFINITY) eaveTopU else eaveTopU + h
    }

    /** Mittlere Wandfarbe (linear, 8 Bit je Kanal) fuer untexturierte Darstellung. */
    val wallFlatColor: Int
        get() = tinted(BuildingTextures.meanLinear(if (storeys > 0 && style.windows != null) style.windows else style.plain ?: Band.TIMBER_WINDOWS), wallTint)

    /** Mittlere Dachfarbe (linear) fuer untexturierte Darstellung. */
    val roofFlatColor: Int get() = tinted(BuildingTextures.meanLinear(cover.band), roofTint)
    val annexFlatColor: Int get() = tinted(BuildingTextures.meanLinear(annexCover.band), annexTint)

    companion object {
        private const val LEVEL_HEIGHT_M = 3.66

        fun lin(r: Double, g: Double, b: Double): Int = Argb.ofUnit(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))

        private fun tinted(mean: FloatArray, tint: Int): Int = lin(
            mean[0] * Argb.red(tint) / 255.0,
            mean[1] * Argb.green(tint) / 255.0,
            mean[2] * Argb.blue(tint) / 255.0,
        )

        /** Vertexfarbe, die ein Band der mittleren Farbe [band] auf die Zielfarbe (linear) bringt. */
        private fun tintTo(r: Double, g: Double, b: Double, band: Band, gain: Double = 1.0): Int {
            val m = BuildingTextures.meanLinear(band)
            return lin(r * gain / m[0], g * gain / m[1], b * gain / m[2])
        }

        /**
         * Die Gebaeudeart nach Grundflaeche und Breite — mehr gibt die Karte
         * nicht her. [r] ist eine feste Zufallszahl des Gebaeudes.
         */
        fun kindOf(areaM2: Double, widthM: Double, r: Double): BuildingKind = when {
            areaM2 < 12 -> BuildingKind.SHED
            areaM2 < 45 -> if (r < 0.65 || widthM > 4.5) BuildingKind.GARAGE else BuildingKind.SHED
            areaM2 < 65 -> if (r < 0.55 && widthM >= 5.5) BuildingKind.HOUSE else BuildingKind.GARAGE
            areaM2 < 320 -> BuildingKind.HOUSE
            areaM2 < 800 -> if (r < 0.45) BuildingKind.HOUSE else BuildingKind.BARN
            else -> BuildingKind.HALL
        }

        /**
         * `true`, wenn [heightM] nichts aussagt: fehlt oder ist der Vorgabewert
         * der Kacheln (`render_height` = 5, wenn in OpenStreetMap weder Hoehe
         * noch Geschosszahl steht).
         */
        fun heightUnknown(heightM: Double): Boolean = !heightM.isFinite() || heightM <= 0.5 || heightM == 5.0 || heightM > 120.0

        /** Geschosszahl, falls [heightM] aus `building:levels` stammt (3,66 m je Geschoss, abgeschnitten), sonst 0. */
        fun levelsOf(heightM: Double): Int {
            for (l in 1..8) if (heightM == floor(l * LEVEL_HEIGHT_M)) return l
            return 0
        }

        /**
         * Geschaetzte Traufhoehe ueber dem Fussboden, wenn die Daten keine
         * Hoehe nennen: Schuppen und Garagen 2–3 m, Wohnhaeuser anderthalb
         * oder zwei Geschosse, Scheunen und Hallen hoeher. [r] wie bei [kindOf].
         */
        fun estimateEave(kind: BuildingKind, areaM2: Double, r: Double, r2: Double): Double = when (kind) {
            BuildingKind.SHED -> 2.0 + 0.3 * r2
            BuildingKind.GARAGE -> 2.45 + 0.35 * r2
            BuildingKind.HOUSE -> when {
                areaM2 < 65 -> 2.95 + 0.3 * r2
                // Anderthalb Geschosse (Kniestock) oder zwei volle.
                r < (if (areaM2 < 110) 0.55 else 0.32) -> 3.75 + 0.75 * r2
                else -> 5.7 + 0.6 * r2
            }
            BuildingKind.BARN -> 4.6 + 1.8 * r2
            BuildingKind.HALL -> 5.2 + 2.2 * r2
        }

        fun of(b: MapBuilding, ctx: TileContext): BuildingDesign? =
            of(b, ctx.ground, ctx.track, ctx.level, ctx.land) { e, n, radius -> ctx.aerialMeanAt(e, n, radius) }

        /**
         * Entwirft ein Gebaeude. `null` fuer Grundrisse, aus denen nichts
         * werden kann (unter 4 m², weniger als drei Punkte, nicht endlich).
         */
        fun of(
            b: MapBuilding,
            ground: GroundSurface,
            track: TrackLine?,
            level: Int,
            land: LandMap? = null,
            aerial: (Double, Double, Double) -> Int?,
        ): BuildingDesign? {
            val outer0 = FootprintShape.clean(b.rings.firstOrNull() ?: return null) ?: return null
            val cen = FootprintShape.centroid(outer0)
            val area = cen[2]
            if (area < 4.0 || area > 250_000.0) return null
            val ce = cen[0]
            val cn = cen[1]
            val seed = Noise.seedOf((ce * 10).roundToLong(), (cn * 10).roundToLong())
            fun rnd(k: Int) = Noise.hash01(k, 0x5eed, seed)

            // --- Grundriss ausrichten -------------------------------------
            var angle = FootprintShape.dominantAngle(outer0)
            var f = FootprintShape.toFrame(outer0, ce, cn, angle)
            if (extent(f, 1) > extent(f, 0)) {
                angle += PI / 2
                f = FootprintShape.toFrame(outer0, ce, cn, angle)
            }
            if (ringArea(f) < 0) f = FootprintShape.reversed(f)
            val holes = b.rings.drop(1).mapNotNull { FootprintShape.clean(it) }.map { FootprintShape.toFrame(it, ce, cn, angle) }
            val sq = FootprintShape.square(listOf(f) + holes)
            val rings: List<DoubleArray> = sq?.rings ?: fallbackRings(f, holes)
            val outer = rings[0]
            val width = extent(outer, 1)
            val lengthM = extent(outer, 0)

            // --- Art, Hoehe, Dachform -------------------------------------
            val unknown = heightUnknown(b.heightM)
            val levels = if (unknown) 0 else levelsOf(b.heightM)
            var kind = kindOf(area, width, rnd(1))
            if (!unknown && levels == 0 && b.heightM < 3.6 && kind == BuildingKind.HOUSE) kind = BuildingKind.GARAGE
            if (levels >= 2 && (kind == BuildingKind.GARAGE || kind == BuildingKind.SHED)) kind = BuildingKind.HOUSE
            // Die Vektorkacheln nennen keine Gebaeudeart. Eine Wellblechhalle ist ein grosser Grundriss nur in
            // Gewerbe- und Landwirtschaftsflaechen (ohne Landnutzung wie bisher); sonst (Kloster, Schule, Hotel, Wohnblock) ein Putz-/Ziegelbau mit Fenstern.
            val workLand = (land?.classAt(ce, cn) ?: LandClass.INDUSTRIAL).let { it == LandClass.INDUSTRIAL || it == LandClass.FARMLAND }
            val civic = kind == BuildingKind.HALL && !workLand

            val isRect = sq != null && rings.size == 1 && outer.size == 8
            var form = RoofForm.FLAT
            var wings: List<RoofWing> = emptyList()
            var tanP = 0.0
            when (kind) {
                BuildingKind.SHED -> if (isRect && rnd(2) < 0.55) {
                    form = RoofForm.PENT
                    tanP = tan(Math.toRadians(7.0 + 6.0 * rnd(3)))
                }
                BuildingKind.GARAGE -> if (isRect && rnd(2) < 0.22 && width >= 3.2 && lengthM <= 9.5) {
                    form = RoofForm.WINGS
                    tanP = tan(Math.toRadians(22.0 + 8.0 * rnd(3)))
                }
                BuildingKind.HOUSE -> {
                    form = RoofForm.WINGS
                    tanP = tan(Math.toRadians(36.0 + 9.0 * rnd(3)))
                }
                BuildingKind.BARN -> {
                    form = RoofForm.WINGS
                    tanP = tan(Math.toRadians(28.0 + 10.0 * rnd(3)))
                }
                BuildingKind.HALL -> if (rnd(2) < 0.7) {
                    form = RoofForm.WINGS
                    tanP = tan(Math.toRadians(10.0 + 6.0 * rnd(3)))
                }
            }
            if (form == RoofForm.WINGS) {
                // Selten ein Walmdach rundum; sonst Hauptbau mit Giebeln, Fluegeln und Anbauten.
                val allHip = kind == BuildingKind.HOUSE && rnd(4) < 0.06
                val w = when {
                    sq == null -> null
                    allHip -> RoofWings.decompose(sq, gables = false)
                    else -> RoofWings.mainAndAnnexes(sq) ?: RoofWings.decompose(sq, gables = true)
                }
                if (w != null) {
                    wings = w
                } else if (sq == null && rings.size == 1 && kind != BuildingKind.HALL && area >= 0.72 * width * lengthM) {
                    // Schiefwinkliger Grundriss: ein Satteldach ueber sein Huellrechteck, die Waende folgen dem Grundriss bis unter die Dachhaut.
                    val loose = RoofWing(outer.minX(), outer.minY(), outer.maxX(), outer.maxY(), alongX = true, hipLo = false, hipHi = false, role = WingRole.MAIN)
                    loose.loose = true
                    wings = listOf(loose)
                } else {
                    form = RoofForm.FLAT
                }
            } else if (form == RoofForm.PENT) {
                wings = listOf(RoofWing(outer.minX(), outer.minY(), outer.maxX(), outer.maxY(), alongX = true, hipLo = false, hipHi = false, pent = if (rnd(5) < 0.5) 1 else -1))
            }
            val hwRef = when {
                wings.isEmpty() -> 0.0
                wings[0].role == WingRole.MAIN -> wings[0].halfWidth
                else -> wings.maxOf { it.halfWidth }
            }
            if (form == RoofForm.WINGS) {
                // Sehr breite Fluegel: flacher neigen, sonst wird das Dach hoeher als das Haus.
                val cap = when (kind) {
                    BuildingKind.HOUSE -> 5.0
                    BuildingKind.BARN -> 6.0
                    else -> 4.5
                }
                if (hwRef * tanP > cap) tanP = cap / hwRef
            }

            // --- Gelaende -------------------------------------------------
            var gMin = Double.MAX_VALUE
            var gMax = -Double.MAX_VALUE
            val cA = cos(angle)
            val sA = sin(angle)
            fun sampleGround(x: Double, y: Double) {
                val h = ground.heightAt(ce + x * cA - y * sA, cn + x * sA + y * cA)
                if (h.isFinite()) {
                    gMin = min(gMin, h)
                    gMax = max(gMax, h)
                }
            }
            sampleGround(0.0, 0.0)
            for (r in rings) {
                val n = r.size / 2
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val steps = max(1, (hypot(r[2 * j] - r[2 * i], r[2 * j + 1] - r[2 * i + 1]) / 2.5).toInt() + 1)
                    for (k in 0 until steps) {
                        val t = k.toDouble() / steps
                        sampleGround(r[2 * i] + (r[2 * j] - r[2 * i]) * t, r[2 * i + 1] + (r[2 * j + 1] - r[2 * i + 1]) * t)
                    }
                }
            }
            if (gMin == Double.MAX_VALUE) {
                gMin = 0.0
                gMax = 0.0
            }
            val raised = b.minHeightM > 0.3
            val socle = when (kind) {
                BuildingKind.HOUSE -> 0.22 + 0.2 * rnd(6)
                BuildingKind.BARN -> 0.12
                else -> 0.05
            }
            val floorU = gMax + (if (raised) b.minHeightM else socle)

            // --- Hoehen ---------------------------------------------------
            var rise = (if (form == RoofForm.PENT) 2 else 1) * hwRef * tanP
            var eaveH = when {
                unknown -> if (civic) 6.6 + 3.0 * rnd(8) else estimateEave(kind, area, rnd(7), rnd(8))
                levels > 0 -> levels * 2.8 + 0.2
                else -> {
                    // Echte Hoehe = Oberkante Dach. Reicht sie nicht fuer Wand und Dach, wird das Dach flacher.
                    val total = b.heightM - (if (raised) b.minHeightM else 0.0)
                    var e = total - rise
                    val minEave = max(2.1, 0.5 * total)
                    if (form != RoofForm.FLAT && e < minEave) {
                        e = min(minEave, total)
                        val scale = if (rise > 1e-6) ((total - e) / rise).coerceIn(0.0, 1.0) else 0.0
                        tanP *= scale
                        if (tanP < 0.10) {
                            form = RoofForm.FLAT
                            wings = emptyList()
                            e = total
                        }
                    }
                    max(e, 1.8)
                }
            }
            if (form == RoofForm.FLAT) {
                tanP = 0.0
                if (unknown && kind == BuildingKind.HOUSE) eaveH = max(eaveH, 5.6 + 0.5 * rnd(8))
            }
            val eaveTopU = floorU + eaveH
            val thickness = when (kind) {
                BuildingKind.HOUSE, BuildingKind.BARN -> 0.20
                else -> 0.14
            }
            assignRoof(wings, tanP, eaveH, thickness, seed)
            rise = wings.maxOfOrNull { it.base + it.rise } ?: 0.0
            val edges = buildEdges(rings, wings, eaveTopU, thickness)
            for (e in edges) {
                e.ground0 = ground.heightAt(ce + e.x0 * cA - e.y0 * sA, cn + e.x0 * sA + e.y0 * cA)
                e.ground1 = ground.heightAt(ce + e.x1 * cA - e.y1 * sA, cn + e.x1 * sA + e.y1 * cA)
            }
            val eaveOver = when (kind) {
                BuildingKind.HOUSE -> 0.38 + 0.16 * rnd(9)
                BuildingKind.BARN -> 0.40
                BuildingKind.HALL -> 0.30
                BuildingKind.GARAGE -> 0.25
                BuildingKind.SHED -> 0.20
            }
            val vergeOver = when (kind) {
                BuildingKind.HOUSE -> 0.30
                BuildingKind.BARN -> 0.28
                BuildingKind.HALL -> 0.20
                else -> 0.14
            }

            // --- Fassade und Farben ---------------------------------------
            val style = when (kind) {
                BuildingKind.SHED -> pick(rnd(10), FacadeStyle.WOOD to 0.68, FacadeStyle.SHEET to 0.14, FacadeStyle.PLASTER to 0.18)
                BuildingKind.GARAGE -> pick(rnd(10), FacadeStyle.PLASTER to 0.80, FacadeStyle.BRICK to 0.08, FacadeStyle.WOOD to 0.12)
                BuildingKind.HOUSE -> pick(rnd(10), FacadeStyle.PLASTER to 0.64, FacadeStyle.BRICK to 0.13, FacadeStyle.SLATE to 0.14, FacadeStyle.TIMBER to 0.09)
                BuildingKind.BARN -> pick(rnd(10), FacadeStyle.PLASTER to 0.30, FacadeStyle.BRICK to 0.22, FacadeStyle.WOOD to 0.30, FacadeStyle.SHEET to 0.18)
                BuildingKind.HALL -> if (civic) {
                    pick(rnd(10), FacadeStyle.PLASTER to 0.68, FacadeStyle.BRICK to 0.22, FacadeStyle.SLATE to 0.10)
                } else {
                    pick(rnd(10), FacadeStyle.SHEET to 0.70, FacadeStyle.PLASTER to 0.22, FacadeStyle.BRICK to 0.08)
                }
            }
            val wallH = eaveH - (if (form == RoofForm.FLAT) 0.0 else thickness)
            var storeys = 0
            var storeyH = STOREY_M
            if (style.windows != null && kind != BuildingKind.GARAGE && kind != BuildingKind.SHED && wallH >= 2.4) {
                storeys = max(1, floor((wallH + 0.35) / STOREY_M).toInt())
                storeyH = if (wallH / storeys <= 3.0) wallH / storeys else STOREY_M
            }

            val bright = 0.88 + 0.12 * rnd(11)
            val wallTint = wallTint(style, kind, b.colour, rnd(12), bright)
            val socleTint = when (style) {
                FacadeStyle.PLASTER, FacadeStyle.TIMBER -> lin(0.62 + 0.3 * rnd(13), 0.60 + 0.3 * rnd(13), 0.58 + 0.3 * rnd(13))
                FacadeStyle.SLATE -> lin(0.9, 0.9, 0.88)
                else -> lin(0.55, 0.53, 0.50)
            }
            val cover = when {
                form == RoofForm.FLAT -> RoofCover.BITUMEN
                (kind == BuildingKind.HALL && !civic) || style == FacadeStyle.SHEET -> RoofCover.SHEET
                kind == BuildingKind.SHED || (form == RoofForm.PENT) -> if (rnd(14) < 0.5) RoofCover.SHEET else RoofCover.BITUMEN
                kind == BuildingKind.BARN && rnd(14) < 0.3 -> RoofCover.SHEET
                else -> RoofCover.TILE
            }
            val seen = aerial(ce, cn, (sqrt(area) / 3.5).coerceIn(1.5, 6.0))
            val roofTint = roofTint(cover, style, seen, rnd(15), rnd(16))
            val trimTint = when {
                cover == RoofCover.TILE -> scaleTint(roofTint, BuildingTextures.meanLinear(Band.PANTILE), BuildingTextures.meanLinear(Band.TRIM), 0.85)
                else -> tintTo(0.20, 0.205, 0.21, Band.TRIM)
            }
            val annexCover = if (rnd(18) < 0.45) RoofCover.SHEET else RoofCover.BITUMEN
            val annexTint = roofTint(annexCover, style, null, rnd(19), rnd(16))
            val soffitTint = if (rnd(17) < 0.6) tintTo(0.72, 0.71, 0.68, Band.TRIM) else tintTo(0.17, 0.11, 0.07, Band.TRIM)

            // --- Abstand zur Strecke, Tueren ------------------------------
            val hit = track?.nearest(ce, cn, 400.0)
            val trackDistance = if (hit == null) 400.0 else max(0.0, hit.distance - sqrt(area) / 2)
            val toTrack: DoubleArray? = if (hit != null) {
                val p = track.poseAt(hit.distM)
                val de = p.e - ce
                val dn = p.n - cn
                // ins Gebaeudesystem
                doubleArrayOf(de * cA + dn * sA, -de * sA + dn * cA)
            } else {
                null
            }
            val decals = decals(kind, style, edges.filter { !it.upper }, storeys, eaveH, toTrack, seed)

            return BuildingDesign(
                seed, ce, cn, area, angle, rings, sq != null, kind, style, form, wings, tanP, cover,
                floorU, gMin, gMax, eaveTopU, eaveTopU + rise, storeys, storeyH,
                eaveOver, vergeOver, thickness, raised, trackDistance, edges, decals,
                wallTint, socleTint, roofTint, trimTint, soffitTint, annexCover, annexTint,
            )
        }

        private fun extent(r: DoubleArray, axis: Int): Double {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (i in 0 until r.size / 2) {
                lo = min(lo, r[2 * i + axis])
                hi = max(hi, r[2 * i + axis])
            }
            return hi - lo
        }

        private fun DoubleArray.minX() = (0 until size / 2).minOf { this[2 * it] }
        private fun DoubleArray.maxX() = (0 until size / 2).maxOf { this[2 * it] }
        private fun DoubleArray.minY() = (0 until size / 2).minOf { this[2 * it + 1] }
        private fun DoubleArray.maxY() = (0 until size / 2).maxOf { this[2 * it + 1] }

        private fun <T> pick(r: Double, vararg options: Pair<T, Double>): T {
            var acc = 0.0
            for ((v, p) in options) {
                acc += p
                if (r < acc) return v
            }
            return options.last().first
        }

        /**
         * Grundriss, der sich nicht rechtwinklig ausrichten liess: der Ring
         * selbst, wenn er sich sauber in Dreiecke zerlegen laesst (mit seinen
         * brauchbaren Loechern), sonst seine konvexe Huelle.
         */
        private fun fallbackRings(outer: DoubleArray, holes: List<DoubleArray>): List<DoubleArray> {
            if (FootprintShape.isSimple(outer)) {
                val good = holes.filter { h ->
                    abs(ringArea(h)) >= 6.0 && FootprintShape.isSimple(h) &&
                        (0 until h.size / 2).all { pointInRings(listOf(outer), h[2 * it], h[2 * it + 1]) && FootprintShape.distanceToRing(outer, h[2 * it], h[2 * it + 1]) > 0.5 }
                }.map { if (ringArea(it) < 0) it else FootprintShape.reversed(it) }
                if (good.isNotEmpty() && triangulates(listOf(outer) + good)) return listOf(outer) + good
                if (triangulates(listOf(outer))) return listOf(outer)
            }
            return listOf(FootprintShape.convexHull(outer))
        }

        /** `true`, wenn die Dreiecke der Zerlegung die Flaeche der Ringe bis auf 1 % treffen. */
        private fun triangulates(rings: List<DoubleArray>): Boolean {
            val tris = Triangulator.triangulate(rings)
            val pts = rings.flatMap { r -> (0 until r.size / 2).map { doubleArrayOf(r[2 * it], r[2 * it + 1]) } }
            var a = 0.0
            var k = 0
            while (k + 2 < tris.size) {
                val p = pts[tris[k]]
                val q = pts[tris[k + 1]]
                val r = pts[tris[k + 2]]
                a += abs((q[0] - p[0]) * (r[1] - p[1]) - (r[0] - p[0]) * (q[1] - p[1])) / 2
                k += 3
            }
            val want = abs(ringArea(rings[0])) - rings.drop(1).sumOf { abs(ringArea(it)) }
            return want > 0 && abs(a - want) <= 0.01 * want
        }

        /** Neigungen und Hoehen der Fluegel: Fluegel nie hoeher als der Hauptfirst, Anbauten ein Geschoss hoch. */
        private fun assignRoof(wings: List<RoofWing>, tanP: Double, eaveH: Double, thickness: Double, seed: Int) {
            if (wings.isEmpty()) return
            val main = wings[0]
            main.tan = tanP
            for (i in 1 until wings.size) {
                val w = wings[i]
                when (w.role) {
                    WingRole.CROSS, WingRole.EXTENSION -> w.tan = min(tanP, main.rise / w.halfWidth)
                    WingRole.ANNEX -> {
                        val r = Noise.hash01(i, 0xa77e, seed)
                        val r2 = Noise.hash01(i, 0xa77f, seed)
                        val annexEave = if (eaveH >= 3.4) min(eaveH - 0.7, 2.5 + 0.45 * r) else max(1.9, eaveH - 0.45)
                        w.base = annexEave - eaveH
                        val depth = 2 * w.halfWidth
                        var t = tan(Math.toRadians(9.0 + 9.0 * r2))
                        // Die hohe Seite des Pultdachs muss unter der Traufe des Hauptbaus bleiben.
                        val room = -w.base - thickness - 0.2
                        if (depth * t > room) t = room / depth
                        if (depth <= 5.5 && t >= 0.09 && r2 < 0.65) w.tan = t else w.flat = true
                    }
                    else -> w.tan = tanP
                }
            }
        }

        /**
         * Die Wandkanten: der Umriss, aufgeteilt nach dem Fluegel, zu dem das
         * Wandstueck gehoert, dazu die Waende des Hauptbaus ueber den Daechern
         * niedrigerer Anbauten — und an jedem freien Fluegelende der Giebel.
         */
        private fun buildEdges(rings: List<DoubleArray>, wings: List<RoofWing>, eaveTopU: Double, thickness: Double): List<BuildingEdge> {
            val out = ArrayList<BuildingEdge>()
            val pieces = wings.size > 1 && wings[0].role == WingRole.MAIN
            for (r in rings) {
                val n = r.size / 2
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val h = (i + n - 1) % n
                    val k = (j + 1) % n
                    val x0 = r[2 * i]
                    val y0 = r[2 * i + 1]
                    val x1 = r[2 * j]
                    val y1 = r[2 * j + 1]
                    val len = hypot(x1 - x0, y1 - y0)
                    if (len < 1e-9) continue
                    fun corner(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) =
                        if ((bx - ax) * (cy - by) - (by - ay) * (cx - bx) > 0) BuildingEdge.CONVEX else BuildingEdge.REFLEX
                    val c0 = corner(r[2 * h], r[2 * h + 1], x0, y0, x1, y1)
                    val c1 = corner(x0, y0, x1, y1, r[2 * k], r[2 * k + 1])
                    // Abschnitte [s0, s1] je Fluegel
                    val subs = ArrayList<Triple<Double, Double, RoofWing>>()
                    if (pieces) {
                        val horizontal = abs(y1 - y0) < 1e-7
                        val nx = (y1 - y0) / len
                        val ny = -(x1 - x0) / len
                        for (w in wings) {
                            val match: Boolean
                            val lo: Double
                            val hi: Double
                            val from: Double
                            if (horizontal) {
                                match = if (ny < 0) abs(w.ownY0 - y0) < 1e-6 else abs(w.ownY1 - y0) < 1e-6
                                lo = max(min(x0, x1), w.ownX0)
                                hi = min(max(x0, x1), w.ownX1)
                                from = x0
                            } else {
                                match = if (nx < 0) abs(w.ownX0 - x0) < 1e-6 else abs(w.ownX1 - x0) < 1e-6
                                lo = max(min(y0, y1), w.ownY0)
                                hi = min(max(y0, y1), w.ownY1)
                                from = y0
                            }
                            if (match && hi - lo > 1e-6) subs.add(Triple(min(abs(lo - from), abs(hi - from)), max(abs(lo - from), abs(hi - from)), w))
                        }
                        subs.sortBy { it.first }
                        if (abs(subs.sumOf { it.second - it.first } - len) > 1e-4) subs.clear()
                    }
                    val loose = wings.size == 1 && wings[0].loose
                    if (loose && (y0 - wings[0].mid) * (y1 - wings[0].mid) < -1e-9) {
                        // Die Wand kreuzt die Firstlinie: dort teilen, damit jede Haelfte unter einer Dachschraege liegt.
                        val t = (wings[0].mid - y0) / (y1 - y0)
                        val xm = x0 + (x1 - x0) * t
                        val a = BuildingEdge(out.size, x0, y0, xm, wings[0].mid)
                        a.end0 = c0
                        a.end1 = BuildingEdge.SPLIT
                        a.piece = wings[0]
                        out.add(a)
                        val b2 = BuildingEdge(out.size, xm, wings[0].mid, x1, y1)
                        b2.end0 = BuildingEdge.SPLIT
                        b2.end1 = c1
                        b2.piece = wings[0]
                        out.add(b2)
                    } else if (subs.isEmpty()) {
                        val e = BuildingEdge(out.size, x0, y0, x1, y1)
                        e.end0 = c0
                        e.end1 = c1
                        if (pieces) e.piece = wings[0] else if (wings.size == 1 && (wings[0].pent != 0 || loose)) e.piece = wings[0]
                        out.add(e)
                    } else {
                        val dx = (x1 - x0) / len
                        val dy = (y1 - y0) / len
                        for ((si, s) in subs.withIndex()) {
                            val e = BuildingEdge(out.size, x0 + dx * s.first, y0 + dy * s.first, x0 + dx * s.second, y0 + dy * s.second)
                            e.piece = s.third
                            e.end0 = if (si == 0) c0 else BuildingEdge.SPLIT
                            e.end1 = if (si == subs.size - 1) c1 else BuildingEdge.SPLIT
                            out.add(e)
                        }
                    }
                }
            }
            if (pieces) {
                // Wand des Hauptbaus ueber dem Dach eines Anbaus bzw. ueber einer schmaleren Verlaengerung.
                val m = wings[0]
                for (o in wings) {
                    if (o.role != WingRole.ANNEX && o.role != WingRole.EXTENSION) continue
                    var e: BuildingEdge? = null
                    var atLo = false
                    var atHi = false
                    if (abs(o.ownY1 - m.y0) < 1e-6) {
                        val lo = max(o.ownX0, m.x0)
                        val hi = min(o.ownX1, m.x1)
                        if (hi - lo > 1e-6) e = BuildingEdge(out.size, lo, m.y0, hi, m.y0)
                        atLo = !m.alongX
                    } else if (abs(o.ownY0 - m.y1) < 1e-6) {
                        val lo = max(o.ownX0, m.x0)
                        val hi = min(o.ownX1, m.x1)
                        if (hi - lo > 1e-6) e = BuildingEdge(out.size, hi, m.y1, lo, m.y1)
                        atHi = !m.alongX
                    } else if (abs(o.ownX1 - m.x0) < 1e-6) {
                        val lo = max(o.ownY0, m.y0)
                        val hi = min(o.ownY1, m.y1)
                        if (hi - lo > 1e-6) e = BuildingEdge(out.size, m.x0, hi, m.x0, lo)
                        atLo = m.alongX
                    } else if (abs(o.ownX0 - m.x1) < 1e-6) {
                        val lo = max(o.ownY0, m.y0)
                        val hi = min(o.ownY1, m.y1)
                        if (hi - lo > 1e-6) e = BuildingEdge(out.size, m.x1, lo, m.x1, hi)
                        atHi = m.alongX
                    }
                    if (e == null) continue
                    if (o.role == WingRole.EXTENSION) {
                        // Nur der Giebel des Hauptbaus ragt ueber das Dach der Verlaengerung.
                        if ((atLo && m.hipLo) || (atHi && m.hipHi) || (!atLo && !atHi)) continue
                        e.fromU = eaveTopU - thickness - 0.1
                    } else {
                        e.fromU = eaveTopU + o.base - (if (o.flat) 0.2 else 0.35)
                    }
                    e.piece = m
                    e.end0 = BuildingEdge.SPLIT
                    e.end1 = BuildingEdge.SPLIT
                    out.add(e)
                }
            }
            for (w in wings) {
                if (w.pent != 0 || w.flat || w.loose) continue
                if (!w.hipLo && !w.buriedLo) attachGable(w, lo = true, out)
                if (!w.hipHi && !w.buriedHi) attachGable(w, lo = false, out)
            }
            return out
        }

        /** Sucht die Wandkanten unter dem freien Ende eines Fluegels; decken sie es nicht ganz, wird das Ende zum Walm. */
        private fun attachGable(w: RoofWing, lo: Boolean, edges: List<BuildingEdge>) {
            val a = if (lo) w.a0 else w.a1
            val b0 = w.mid - w.halfWidth
            val b1 = w.mid + w.halfWidth
            val eps = 1e-5
            val hits = edges.filter { e ->
                val ea0 = if (w.alongX) e.x0 else e.y0
                val ea1 = if (w.alongX) e.x1 else e.y1
                val eb0 = if (w.alongX) e.y0 else e.x0
                val eb1 = if (w.alongX) e.y1 else e.x1
                val out = if (w.alongX) e.nx else e.ny
                abs(ea0 - a) < eps && abs(ea1 - a) < eps && min(eb0, eb1) > b0 - eps && max(eb0, eb1) < b1 + eps &&
                    (if (lo) out < -0.5 else out > 0.5) && e.gable == null && (e.piece == null || e.piece === w)
            }
            if (abs(hits.sumOf { it.len } - (b1 - b0)) < 1e-3) {
                for (e in hits) e.gable = w
            } else if (lo) {
                w.hipLo = true
            } else {
                w.hipHi = true
            }
        }

        private fun wallTint(style: FacadeStyle, kind: BuildingKind, colour: String?, r: Double, bright: Double): Int {
            val given = parseColour(colour)
            if (given != null && style == FacadeStyle.PLASTER) {
                val m = BuildingTextures.meanLinear(Band.PLASTER)
                return lin(given[0] / m[0], given[1] / m[1], given[2] / m[2])
            }
            return when (style) {
                // Putz: gebrochenes Weiss ueberwiegt, dazu Creme, Hellgrau, zartes Gelb, Beige; selten ein Farbton.
                FacadeStyle.PLASTER -> when {
                    r < 0.40 -> lin(0.97 * bright, 0.96 * bright, 0.93 * bright)
                    r < 0.58 -> lin(0.95 * bright, 0.89 * bright, 0.74 * bright)
                    r < 0.72 -> lin(0.80 * bright, 0.80 * bright, 0.79 * bright)
                    r < 0.82 -> lin(0.96 * bright, 0.87 * bright, 0.62 * bright)
                    r < 0.92 -> lin(0.86 * bright, 0.79 * bright, 0.66 * bright)
                    r < 0.96 -> lin(0.88 * bright, 0.70 * bright, 0.56 * bright)
                    else -> lin(0.80 * bright, 0.85 * bright, 0.78 * bright)
                }
                FacadeStyle.BRICK -> lin(bright, (0.92 + 0.08 * r) * bright, (0.90 + 0.10 * r) * bright)
                FacadeStyle.SLATE, FacadeStyle.TIMBER -> lin(bright, bright, bright)
                FacadeStyle.WOOD -> if (kind == BuildingKind.BARN || r < 0.6) lin(bright, 0.97 * bright, 0.94 * bright) else lin(0.62 * bright, 0.50 * bright, 0.44 * bright)
                // Blech: Hellgrau, Lichtgrau, Moosgruen, Beige.
                FacadeStyle.SHEET -> when {
                    r < 0.45 -> lin(0.80 * bright, 0.81 * bright, 0.82 * bright)
                    r < 0.65 -> lin(0.50 * bright, 0.52 * bright, 0.54 * bright)
                    r < 0.82 -> lin(0.24 * bright, 0.36 * bright, 0.27 * bright)
                    else -> lin(0.78 * bright, 0.72 * bright, 0.58 * bright)
                }
            }
        }

        /** `#rrggbb` → linear `[r, g, b]`, sonst `null`. */
        private fun parseColour(c: String?): DoubleArray? {
            if (c == null || c.length != 7 || c[0] != '#') return null
            val v = c.substring(1).toIntOrNull(16) ?: return null
            return doubleArrayOf(
                Argb.SRGB_TO_LINEAR[(v shr 16) and 0xFF].toDouble(),
                Argb.SRGB_TO_LINEAR[(v shr 8) and 0xFF].toDouble(),
                Argb.SRGB_TO_LINEAR[v and 0xFF].toDouble(),
            )
        }

        /** Dachfarben (linear): Tonziegel rot, rotbraun, Betondachstein anthrazit, grau, braun verwittert. */
        private val CLAY_RED = doubleArrayOf(0.225, 0.076, 0.047)
        private val RED_BROWN = doubleArrayOf(0.190, 0.074, 0.050)
        private val ANTHRACITE = doubleArrayOf(0.042, 0.044, 0.050)
        private val GREY = doubleArrayOf(0.150, 0.146, 0.140)
        private val BROWN = doubleArrayOf(0.120, 0.086, 0.066)

        /**
         * Ordnet die mittlere Luftbildfarbe eines Dachs einem Dachton zu
         * (linear `[r, g, b]`); `null`, wenn sie nichts hergibt (Baumkronen,
         * Schatten).
         */
        fun roofToneFromAerial(argb: Int): DoubleArray? {
            val r = Argb.SRGB_TO_LINEAR[Argb.red(argb)].toDouble()
            val g = Argb.SRGB_TO_LINEAR[Argb.green(argb)].toDouble()
            val b = Argb.SRGB_TO_LINEAR[Argb.blue(argb)].toDouble()
            val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if (lum < 0.012) return null
            if (g > r * 1.08 && g > b * 1.08) return null
            val sat = (max(r, max(g, b)) - min(r, min(g, b))) / max(1e-6, max(r, max(g, b)))
            return when {
                r > g * 1.30 && r > b * 1.35 -> if (lum > 0.085) CLAY_RED else RED_BROWN
                r > g * 1.12 && r > b * 1.2 -> BROWN
                sat < 0.28 && lum < 0.075 -> ANTHRACITE
                sat < 0.28 -> GREY
                else -> null
            }
        }

        private fun roofTint(cover: RoofCover, style: FacadeStyle, aerial: Int?, r: Double, r2: Double): Int {
            val gain = 0.8 + 0.4 * r2
            val seen = aerial?.let { roofToneFromAerial(it) }
            return when (cover) {
                RoofCover.TILE -> {
                    // Grau im Luftbild ist bei Ziegeldaechern oft nur verblasster Ton oder Dunst: dann teils rotbraun/braun.
                    val fromAerial = if (seen === GREY && r < 0.6) (if (r < 0.35) RED_BROWN else BROWN) else seen
                    val t = fromAerial ?: when {
                        style == FacadeStyle.SLATE -> ANTHRACITE
                        r < 0.24 -> ANTHRACITE
                        r < 0.56 -> CLAY_RED
                        r < 0.80 -> RED_BROWN
                        r < 0.92 -> BROWN
                        else -> GREY
                    }
                    tintTo(t[0], t[1], t[2], Band.PANTILE, gain)
                }
                RoofCover.SHEET -> {
                    val t = seen ?: when {
                        r < 0.5 -> doubleArrayOf(0.30, 0.31, 0.32)
                        r < 0.7 -> doubleArrayOf(0.52, 0.53, 0.54)
                        r < 0.85 -> ANTHRACITE
                        else -> RED_BROWN
                    }
                    tintTo(t[0], t[1], t[2], Band.SHEET, gain)
                }
                RoofCover.BITUMEN -> {
                    // Bitumenbahn dunkel oder Kiesschuettung grau.
                    val t = if (seen != null && seen !== CLAY_RED && seen !== RED_BROWN) seen else if (r < 0.55) doubleArrayOf(0.060, 0.062, 0.066) else doubleArrayOf(0.27, 0.26, 0.245)
                    tintTo(t[0], t[1], t[2], Band.BITUMEN, gain)
                }
            }
        }

        /** Uebertraegt eine Abtoenung von einem Band auf ein anderes (gleiche Endfarbe mal [gain]). */
        private fun scaleTint(tint: Int, from: FloatArray, to: FloatArray, gain: Double): Int = lin(
            Argb.red(tint) / 255.0 * from[0] / to[0] * gain,
            Argb.green(tint) / 255.0 * from[1] / to[1] * gain,
            Argb.blue(tint) / 255.0 * from[2] / to[2] * gain,
        )

        /** Tueren und Tore: an der Wand, die zur Strecke zeigt. */
        private fun decals(
            kind: BuildingKind,
            style: FacadeStyle,
            edges: List<BuildingEdge>,
            storeys: Int,
            eaveH: Double,
            toTrack: DoubleArray?,
            seed: Int,
        ): List<BuildingDecal> {
            fun rnd(k: Int) = Noise.hash01(k, 0xd0c, seed)
            fun facing(e: BuildingEdge): Double {
                if (toTrack == null) return Noise.hash01(e.index, 0xfac, seed) - 0.5
                val mx = (e.x0 + e.x1) / 2
                val my = (e.y0 + e.y1) / 2
                val dx = toTrack[0] - mx
                val dy = toTrack[1] - my
                val l = max(1e-6, hypot(dx, dy))
                return (e.nx * dx + e.ny * dy) / l
            }
            val out = ArrayList<BuildingDecal>()
            val white = lin(0.93, 0.93, 0.92)
            when (kind) {
                BuildingKind.HOUSE -> {
                    // Angebaute Garage: Tor an der freien Schmalseite des Anbaus.
                    val annexEdge = edges.filter { it.piece?.role == WingRole.ANNEX && it.len in 2.8..4.6 && 2 * it.piece!!.halfWidth * it.piece!!.length >= 14.0 && it.piece!!.area / it.len >= 4.6 }
                        .maxByOrNull { facing(it) }
                    if (annexEdge != null) {
                        val h = min(Decal.GARAGE_DOOR.heightM, eaveH + annexEdge.piece!!.base - 0.3)
                        if (h >= 1.9) out.add(BuildingDecal(annexEdge.index, annexEdge.len / 2, Decal.GARAGE_DOOR, min(Decal.GARAGE_DOOR.widthM, annexEdge.len - 0.4), h, white))
                    }
                    val e = edges.filter { it.piece?.role != WingRole.ANNEX && (bayLayout(it.len, false)?.count ?: 0) >= 1 }.maxByOrNull { facing(it) + (if (it.gable == null) 0.15 else 0.0) } ?: return out
                    val lay = bayLayout(e.len, false)!!
                    val bay = if (lay.count >= 3) lay.count / 2 else (rnd(1) * lay.count).toInt().coerceIn(0, lay.count - 1)
                    val s = lay.margin + (bay + 0.5) * lay.width
                    if (style == FacadeStyle.TIMBER || rnd(2) < 0.3) {
                        out.add(BuildingDecal(e.index, s, Decal.DOOR_WOOD, Decal.DOOR_WOOD.widthM, Decal.DOOR_WOOD.heightM, lin(0.95, 0.95, 0.95)))
                    } else {
                        val c = when {
                            rnd(3) < 0.45 -> white
                            rnd(3) < 0.70 -> lin(0.12, 0.13, 0.15)
                            rnd(3) < 0.85 -> lin(0.10, 0.22, 0.14)
                            else -> lin(0.32, 0.08, 0.06)
                        }
                        out.add(BuildingDecal(e.index, s, Decal.DOOR_PANEL, Decal.DOOR_PANEL.widthM, Decal.DOOR_PANEL.heightM, c))
                    }
                }
                BuildingKind.GARAGE -> {
                    val c = if (rnd(3) < 0.6) white else if (rnd(3) < 0.8) lin(0.30, 0.31, 0.33) else lin(0.42, 0.26, 0.14)
                    val h = min(Decal.GARAGE_DOOR.heightM, eaveH - 0.25)
                    val short = edges.filter { it.len in 2.8..7.0 }
                    if (short.isNotEmpty() && edges.none { it.len > 9.0 }) {
                        val e = short.maxByOrNull { facing(it) + (if (it.len < 4.5) 0.3 else 0.0) }!!
                        val n = if (e.len >= 5.6) 2 else 1
                        for (i in 0 until n) out.add(BuildingDecal(e.index, e.len * (i + 0.5) / n, Decal.GARAGE_DOOR, Decal.GARAGE_DOOR.widthM, h, c))
                    } else {
                        // Garagenzeile: ein Tor je drei Meter an der Laengsseite.
                        val e = edges.filter { it.len > 5.6 }.maxByOrNull { facing(it) } ?: return out
                        val n = max(1, (e.len / 3.0).toInt())
                        for (i in 0 until n) out.add(BuildingDecal(e.index, e.len * (i + 0.5) / n, Decal.GARAGE_DOOR, Decal.GARAGE_DOOR.widthM, h, c))
                    }
                }
                BuildingKind.SHED -> {
                    val e = edges.filter { it.len >= 1.5 }.maxByOrNull { facing(it) } ?: return out
                    val d = if (style == FacadeStyle.WOOD) Decal.DOOR_WOOD else Decal.DOOR_PLAIN
                    out.add(BuildingDecal(e.index, e.len * (0.35 + 0.3 * rnd(1)), d, min(d.widthM, e.len - 0.3), min(d.heightM, eaveH - 0.12), lin(0.85, 0.85, 0.85)))
                }
                BuildingKind.BARN, BuildingKind.HALL -> {
                    if (storeys > 0 && kind == BuildingKind.BARN) return out
                    val e = edges.filter { it.len >= 5.0 }.maxByOrNull { facing(it) } ?: return out
                    val h = min(if (kind == BuildingKind.HALL) 3.9 else Decal.BARN_GATE.heightM, eaveH - 0.4)
                    if (h < 2.2) return out
                    val wood = style == FacadeStyle.WOOD || style == FacadeStyle.BRICK
                    val d = if (wood) Decal.BARN_GATE else Decal.GARAGE_DOOR
                    val w = if (wood) d.widthM else min(4.2, e.len - 1.0)
                    val c = if (wood) lin(0.9, 0.9, 0.9) else lin(0.55, 0.56, 0.58)
                    val n = if (kind == BuildingKind.HALL && e.len > 22) 2 else 1
                    for (i in 0 until n) out.add(BuildingDecal(e.index, e.len * (i + 0.5) / n + (if (n == 1) (rnd(1) - 0.5) * (e.len - w - 1.0) * 0.5 else 0.0), d, w, h, c))
                    if (storeys == 0) {
                        // Nebentuer daneben.
                        val s = e.len * (if (n == 1) 0.12 else 0.5)
                        if (e.len > w + 5.0) out.add(BuildingDecal(e.index, max(0.9, s), Decal.DOOR_PLAIN, Decal.DOOR_PLAIN.widthM, Decal.DOOR_PLAIN.heightM, white))
                    }
                }
            }
            return out
        }
    }
}

/**
 * Baut das Netz eines Gebaeudes: Waende und Giebel ins Wandmaterial, Dach und
 * Zubehoer ins Dachmaterial (beide zeigen auf denselben Atlas).
 */
private class BuildingMesher(
    private val ctx: TileContext,
    private val d: BuildingDesign,
    private val detail: BuildingDetail,
    private val textured: Boolean,
) {
    val wall = MeshBuilder(96, 64)
    val roof = MeshBuilder(64, 48)

    /** Dach als Koerper mit Ueberstand (sonst nur die Dachhaut buendig mit den Waenden). */
    private val solidRoof = textured && d.form != RoofForm.FLAT && (detail == BuildingDetail.FULL || detail == BuildingDetail.MID)

    /**
     * Dicke des Dachkoerpers. Ein Dach ueber dem Huellrechteck eines schiefwinkligen Grundrisses steht immer
     * ueber die Waende hinaus und braucht deshalb in jeder Feinheit eine Untersicht.
     */
    private val thickness = if (solidRoof || (d.wings.size == 1 && d.wings[0].loose)) d.roofThickness else 0.0

    /** Waende reichen bis unter den tiefsten Gelaendepunkt; in Stufe 1 tiefer, weil das Gelaende dort groeber ist. */
    private val bottomU = if (d.raised) d.floorU else d.groundMinU - (if (ctx.level == 0) 0.5 else 3.0)
    private val cosA = cos(d.angle)
    private val sinA = sin(d.angle)
    private val buf = DoubleArray(5 * 16)

    /** Untergrenze `z ≥ c + k·s` fuer die naechsten Wandstuecke (Wand ueber einem schraegen Sockel), sonst `null`. */
    private var floorLine: DoubleArray? = null
    private val trimU = 0.5
    private val trimV = (Band.TRIM.vTop + Band.TRIM.vBottom) / 2

    fun build() {
        if (detail == BuildingDetail.BLOCK) {
            block()
            return
        }
        for (e in d.edges) wallOf(e)
        if (d.raised) flatPolygon(wall, d.floorU, up = false, color = if (textured) flat(d.wallFlatColor) else d.wallFlatColor, band = null)
        when (d.form) {
            RoofForm.FLAT -> flatRoof()
            else -> for (w in d.wings) {
                for (f in RoofWings.faces(w, if (solidRoof) d.eaveOver else 0.0, if (solidRoof) d.vergeOver else 0.0, thickness)) roofFace(w, f)
            }
        }
        if (!textured) return
        if (detail == BuildingDetail.FULL || detail == BuildingDetail.MID) {
            for (dc in d.decals) decal(dc)
            val dormerSide = if (detail == BuildingDetail.FULL) dormers() else 0
            chimney(dormerSide)
        }
        if (detail == BuildingDetail.FULL) gutters()
    }

    // -----------------------------------------------------------------------
    // Grundbausteine
    // -----------------------------------------------------------------------

    /** Farbe fuer eine Flaeche, die im Atlas auf dem hellen Zubehoer-Feld liegt und [linear] zeigen soll. */
    private fun flat(linear: Int): Int {
        val m = BuildingTextures.meanLinear(Band.TRIM)
        return BuildingDesign.lin(Argb.red(linear) / 255.0 / m[0], Argb.green(linear) / 255.0 / m[1], Argb.blue(linear) / 255.0 / m[2])
    }

    /**
     * Ebenes, konvexes Vieleck aus [n] Punkten in [p] (`x, y, u, tu, tv` im
     * Gebaeudesystem), als Faecher. Der Umlaufsinn wird so gewaehlt, dass die
     * Vorderseite zur Normalen (`nx`, `ny`, `nu`) zeigt.
     */
    private fun poly(m: MeshBuilder, p: DoubleArray, n: Int, nx: Double, ny: Double, nu: Double, color: Int) {
        if (n < 3) return
        var ax = 0.0
        var ay = 0.0
        var az = 0.0
        val z0 = p[2]
        for (i in 0 until n) {
            val a = i * 5
            val b = ((i + 1) % n) * 5
            val zi = p[a + 2] - z0
            val zj = p[b + 2] - z0
            ax += (p[a + 1] - p[b + 1]) * (zi + zj)
            ay += (zi - zj) * (p[a] + p[b])
            az += (p[a] - p[b]) * (p[a + 1] + p[b + 1])
        }
        if (ax * ax + ay * ay + az * az < 1e-10) return
        val flip = ax * nx + ay * ny + az * nu < 0
        val l = sqrt(nx * nx + ny * ny + nu * nu)
        val ne = (nx * cosA - ny * sinA) / l
        val nn = (nx * sinA + ny * cosA) / l
        val up = nu / l
        val base = m.vertexCount
        for (i in 0 until n) {
            val a = i * 5
            m.vertex(ctx.x(d.eOf(p[a], p[a + 1])), ctx.y(p[a + 2]), ctx.z(d.nOf(p[a], p[a + 1])), ne, up, -nn, p[a + 3], p[a + 4], color)
        }
        for (i in 1 until n - 1) {
            // Entartete Dreiecke (doppelte Punkte am Zeltdach) auslassen.
            val a = 0
            val b = i * 5
            val c = (i + 1) * 5
            val ux = p[b] - p[a]
            val uy = p[b + 1] - p[a + 1]
            val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]
            val vy = p[c + 1] - p[a + 1]
            val vz = p[c + 2] - p[a + 2]
            val cx = uy * vz - uz * vy
            val cy = uz * vx - ux * vz
            val cz = ux * vy - uy * vx
            if (cx * cx + cy * cy + cz * cz < 1e-12) continue
            if (flip) m.tri(base, base + i + 1, base + i) else m.tri(base, base + i, base + i + 1)
        }
    }

    /** Schneidet ein Vieleck in (`s`, `z`) auf `z ≤ c + k·s` zurecht ([above]: auf `z ≥ c + k·s`). */
    private fun clipBelow(pts: DoubleArray, c: Double, k: Double, above: Boolean = false): DoubleArray {
        val n = pts.size / 2
        val out = ArrayList<Double>(pts.size + 4)
        val sg = if (above) -1.0 else 1.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val s0 = pts[2 * i]
            val z0 = pts[2 * i + 1]
            val s1 = pts[2 * j]
            val z1 = pts[2 * j + 1]
            val f0 = (z0 - c - k * s0) * sg
            val f1 = (z1 - c - k * s1) * sg
            if (f0 <= 1e-9) {
                out.add(s0)
                out.add(z0)
            }
            if ((f0 < -1e-9 && f1 > 1e-9) || (f0 > 1e-9 && f1 < -1e-9)) {
                val t = f0 / (f0 - f1)
                out.add(s0 + (s1 - s0) * t)
                out.add(z0 + (z1 - z0) * t)
            }
        }
        return out.toDoubleArray()
    }

    /** Obergrenzen einer Wand als Geraden `z ≤ c + k·s` (Giebel: zwei, Pultdach: eine); leer bei waagerechter Oberkante. */
    private fun topLines(e: BuildingEdge): DoubleArray {
        val g = e.gable
        if (g != null) {
            // Quer zum First: b laeuft entlang der Kante, die Schraegen fallen von der Mitte nach beiden Seiten.
            val base = d.eaveTopU + g.base - thickness
            val b0 = (if (g.alongX) e.y0 else e.x0) - g.mid
            val db = if (g.alongX) e.dy else e.dx
            return doubleArrayOf(base + (g.halfWidth + b0) * g.tan, db * g.tan, base + (g.halfWidth - b0) * g.tan, -db * g.tan)
        }
        val w = e.piece
        if (w != null && (w.pent != 0 || w.loose) && !w.flat) {
            val t0 = d.eaveTopU - thickness + w.topAt(e.x0, e.y0)
            val t1 = d.eaveTopU - thickness + w.topAt(e.x1, e.y1)
            if (abs(t1 - t0) > 1e-9) return doubleArrayOf(t0, (t1 - t0) / e.len)
        }
        return DoubleArray(0)
    }

    /** Oberkante einer Wand ohne Schraege. */
    private fun levelTop(e: BuildingEdge): Double {
        val w = e.piece
        return when {
            d.form == RoofForm.FLAT -> d.eaveTopU
            w == null -> d.eaveTopU - thickness
            w.flat -> d.eaveTopU + w.base
            w.pent != 0 || w.loose -> d.eaveTopU - thickness + w.topAt(e.x0, e.y0)
            else -> d.eaveTopU + w.base - thickness
        }
    }

    private fun topAt(lines: DoubleArray, s: Double): Double {
        var lo = Double.MAX_VALUE
        var k = 0
        while (k < lines.size) {
            lo = min(lo, lines[k] + lines[k + 1] * s)
            k += 2
        }
        return lo
    }

    private fun lowTop(e: BuildingEdge, lines: DoubleArray): Double =
        if (lines.isEmpty()) levelTop(e) else min(topAt(lines, 0.0), topAt(lines, e.len))

    private fun highTop(e: BuildingEdge, lines: DoubleArray): Double {
        if (lines.isEmpty()) return levelTop(e)
        var hi = max(topAt(lines, 0.0), topAt(lines, e.len))
        if (lines.size == 4 && abs(lines[1] - lines[3]) > 1e-12) {
            // Schnittpunkt der beiden Schraegen = Giebelspitze, falls sie ueber dieser Kante liegt.
            val s = (lines[2] - lines[0]) / (lines[1] - lines[3])
            if (s > 0 && s < e.len) hi = max(hi, topAt(lines, s))
        }
        return hi
    }

    /** Kleinste Obergrenze der Wand im Abschnitt [s0, s1]. */
    private fun topBetween(lines: DoubleArray, s0: Double, s1: Double): Double {
        var lo = Double.MAX_VALUE
        var k = 0
        while (k < lines.size) {
            lo = min(lo, min(lines[k] + lines[k + 1] * s0, lines[k] + lines[k + 1] * s1))
            k += 2
        }
        return lo
    }

    /**
     * Ein Wandstueck: Rechteck [s0, s1] × [zA, zB] auf der Kante, mit [lines]
     * beschnitten. Textur: `u0`/`u1` an den Seiten, `vA` unten, `vB` oben.
     */
    private fun wallRect(
        e: BuildingEdge, s0: Double, s1: Double, zA: Double, zB: Double,
        u0: Double, u1: Double, vA: Double, vB: Double, color: Int, lines: DoubleArray,
        m: MeshBuilder = wall, offset: Double = 0.0,
    ) {
        if (s1 - s0 < 1e-6 || zB - zA < 1e-6) return
        var pts = doubleArrayOf(s0, zA, s1, zA, s1, zB, s0, zB)
        val fl = floorLine
        if (fl != null && zA < max(fl[0] + fl[1] * s0, fl[0] + fl[1] * s1) + 1e-9) pts = clipBelow(pts, fl[0], fl[1], above = true)
        var k = 0
        while (k < lines.size) {
            if (zB > min(lines[k] + lines[k + 1] * s0, lines[k] + lines[k + 1] * s1) - 1e-9) pts = clipBelow(pts, lines[k], lines[k + 1])
            k += 2
        }
        val n = pts.size / 2
        if (n < 3 || n > 16) return
        for (i in 0 until n) {
            val s = pts[2 * i]
            val z = pts[2 * i + 1]
            buf[5 * i] = e.x0 + e.dx * s + e.nx * offset
            buf[5 * i + 1] = e.y0 + e.dy * s + e.ny * offset
            buf[5 * i + 2] = z
            buf[5 * i + 3] = u0 + (s - s0) / (s1 - s0) * (u1 - u0)
            buf[5 * i + 4] = vA + (z - zA) / (zB - zA) * (vB - vA)
        }
        poly(m, buf, n, e.nx, e.ny, 0.0, color)
    }

    // -----------------------------------------------------------------------
    // Waende
    // -----------------------------------------------------------------------

    private fun wallOf(e: BuildingEdge) {
        val lines = topLines(e)
        val low = lowTop(e, lines)
        val high = highTop(e, lines)
        if (!textured) {
            wallRect(e, 0.0, e.len, if (e.upper) e.fromU else bottomU, high, 0.0, 0.0, 0.0, 0.0, d.wallFlatColor, lines)
            return
        }
        val style = d.style
        val tint = d.wallTint
        if (e.upper) {
            plainZone(e, 0.0, e.len, e.fromU, high, 1.0, 0.0, tint, lines)
            return
        }
        // Sockel: vom Fussboden bis unter das Gelaende. Am Hang folgt seine Oberkante dem Gelaende, darueber
        // laeuft die Wand bis zum Fussboden weiter (Kellergeschoss).
        if (!d.raised && d.floorU - bottomU > 1e-3) {
            val h = d.floorU - bottomU
            val u0 = Band.SOCLE.u(e.index * 3.1)
            val u1 = Band.SOCLE.u(e.index * 3.1 + e.len)
            val c = e.ground0 + SOCLE_ABOVE_GROUND_M
            val k = (e.ground1 - e.ground0) / e.len
            val follows = (detail == BuildingDetail.FULL || detail == BuildingDetail.MID) && style.plain != null &&
                c.isFinite() && k.isFinite() && min(c, c + k * e.len) < d.floorU - 0.12
            if (!follows) {
                wallRect(e, 0.0, e.len, bottomU, d.floorU, u0, u1, Band.SOCLE.vBottom, Band.SOCLE.v(min(1.0, h / 2.0)), d.socleTint, lines)
            } else {
                wallRect(e, 0.0, e.len, bottomU, d.floorU, u0, u1, Band.SOCLE.vBottom, Band.SOCLE.v(min(1.0, h / 2.0)), d.socleTint, doubleArrayOf(c, k))
                floorLine = doubleArrayOf(c, k)
                plainZone(e, 0.0, e.len, max(bottomU, min(c, c + k * e.len)), d.floorU, 1.0, 0.0, tint, lines)
                floorLine = null
            }
        }
        // Flachdach: die oberste Handbreit ist die Blechabdeckung der Attika.
        var top = high
        if (d.form == RoofForm.FLAT && (detail == BuildingDetail.FULL || detail == BuildingDetail.MID)) {
            top = high - 0.16
            wallRect(e, 0.0, e.len, top, high, trimU, trimU, trimV, trimV, d.trimTint, lines)
        }
        val topLow = min(low, top)
        val exact = detail != BuildingDetail.FULL && detail != BuildingDetail.MID
        // Anbauten haben ein Geschoss, soweit ihre Wand dafuer reicht.
        val annex = e.piece?.role == WingRole.ANNEX
        val annexH = topLow - d.floorU
        val storeys = if (!annex) d.storeys else if (d.storeys > 0 && annexH >= 2.35) 1 else 0
        val gate = d.decals.any { it.edge == e.index && it.decal == Decal.GARAGE_DOOR }
        val lay = if (style.windows != null && storeys > 0 && detail != BuildingDetail.PLAIN && !gate) bayLayout(e.len, exact) else null
        if (lay == null) {
            plainZone(e, 0.0, e.len, d.floorU, top, 1.0, 0.0, tint, lines)
            return
        }
        val band = style.windows!!
        val hs = if (!annex) d.storeyH else if (annexH <= 3.0) annexH else STOREY_M
        val zScale = STOREY_M / hs
        val door = if (exact) null else d.decals.firstOrNull { it.edge == e.index && it.decal != Decal.GARAGE_DOOR }
        var zs = d.floorU
        for (k in 0 until storeys) {
            val zA = d.floorU + k * hs
            var zB = zA + hs
            var vB = band.vTop
            val last = k == storeys - 1
            if (last && lines.isEmpty() && abs(topLow - zB) < 0.36) {
                // Kleiner Rest bis zur Traufe: das Geschoss abschneiden bzw. hochziehen statt eines schmalen Streifens.
                if (topLow < zB) vB = band.v((topLow - zA) * zScale)
                zB = topLow
            } else if (zB > topLow) {
                vB = band.v((topLow - zA) * zScale)
                zB = topLow
            }
            if (zB <= zA) break
            val sA = lay.margin
            val sB = lay.margin + lay.count * lay.width
            margin(e, 0.0, sA, zA, zB, zScale, left = true, vB = vB, tint = tint, lines = lines, sRef = sA)
            margin(e, sB, e.len, zA, zB, zScale, left = false, vB = vB, tint = tint, lines = lines, sRef = sB)
            val cell = (Noise.hash(e.index, k, d.seed) ushr 4) and 3
            if (k == 0 && door != null) {
                // Achse der Tuer bleibt fensterlos.
                val bay = ((door.s - sA) / lay.width).toInt().coerceIn(0, lay.count - 1)
                val d0 = sA + bay * lay.width
                val d1 = d0 + lay.width
                if (bay > 0) wallRect(e, sA, d0, zA, zB, cell / 4.0, (cell + bay) / 4.0, band.vBottom, vB, tint, lines)
                blind(e, d0, d1, zA, zB, zScale, vB, tint, lines, d0)
                if (bay < lay.count - 1) wallRect(e, d1, sB, zA, zB, (cell + bay + 1) / 4.0, (cell + lay.count) / 4.0, band.vBottom, vB, tint, lines)
            } else {
                wallRect(e, sA, sB, zA, zB, cell / 4.0, (cell + lay.count) / 4.0, band.vBottom, vB, tint, lines)
            }
            zs = zB
        }
        if (top - zs < 1e-3) return
        if (lines.isEmpty() || detail == BuildingDetail.SIMPLE) {
            plainZone(e, 0.0, e.len, zs, top, 1.0, lay.margin, tint, lines)
            return
        }
        // Dachgeschoss im Giebel: Fenster, wo sie unter die Dachschraege passen.
        val zB = min(zs + STOREY_M, top)
        var first = -1
        var lastBay = -1
        val need = zs + BuildingTextures.WINDOW_HEAD_M + 0.28
        val reach = if (style == FacadeStyle.SLATE) 1.12 else 0.72
        for (j in 0 until lay.count) {
            val sc = lay.margin + (j + 0.5) * lay.width
            if (topBetween(lines, sc - reach, sc + reach) >= need) {
                if (first < 0) first = j
                lastBay = j
            } else if (first >= 0) {
                break
            }
        }
        if (first >= 0 && zB - zs >= BuildingTextures.WINDOW_HEAD_M + 0.2) {
            val sA = lay.margin + first * lay.width
            val sB = lay.margin + (lastBay + 1) * lay.width
            val vB = band.v(zB - zs)
            blind(e, 0.0, sA, zs, zB, 1.0, vB, tint, lines, sA)
            blind(e, sB, e.len, zs, zB, 1.0, vB, tint, lines, sB)
            val cell = (Noise.hash(e.index, 9, d.seed) ushr 4) and 3
            wallRect(e, sA, sB, zs, zB, cell / 4.0, (cell + lastBay - first + 1) / 4.0, band.vBottom, vB, tint, lines)
            if (top - zB > 1e-3) plainZone(e, 0.0, e.len, zB, top, 1.0, lay.margin, tint, lines)
        } else {
            plainZone(e, 0.0, e.len, zs, top, 1.0, lay.margin, tint, lines)
        }
    }

    /** Randstueck neben den Fensterachsen eines Geschosses. */
    private fun margin(
        e: BuildingEdge, s0: Double, s1: Double, zA: Double, zB: Double, zScale: Double,
        left: Boolean, vB: Double, tint: Int, lines: DoubleArray, sRef: Double,
    ) {
        if (s1 - s0 < 1e-6) return
        if (d.style == FacadeStyle.TIMBER) {
            // Stueck des fensterlosen Felds, an dessen Pfosten die Fensterachse anschliesst.
            val band = Band.TIMBER_WINDOWS
            val part = ((s1 - s0) / BAY_M).coerceAtMost(1.0)
            if (left) wallRect(e, s0, s1, zA, zB, (3 - part) / 4.0, 3 / 4.0, band.vBottom, vB, tint, lines)
            else wallRect(e, s0, s1, zA, zB, 2 / 4.0, (2 + part) / 4.0, band.vBottom, vB, tint, lines)
            return
        }
        plainZone(e, s0, s1, zA, zB, zScale, sRef, tint, lines)
    }

    /** Fensterlose Flaeche in Geschosshoehe (Tuerachse, Giebelrand). */
    private fun blind(
        e: BuildingEdge, s0: Double, s1: Double, zA: Double, zB: Double, zScale: Double,
        vB: Double, tint: Int, lines: DoubleArray, sRef: Double,
    ) {
        if (s1 - s0 < 1e-6) return
        if (d.style == FacadeStyle.TIMBER) {
            val band = Band.TIMBER_WINDOWS
            val n = max(1, ((s1 - s0) / BAY_M).roundToInt())
            val w = (s1 - s0) / n
            for (i in 0 until n) wallRect(e, s0 + i * w, s0 + (i + 1) * w, zA, zB, 2 / 4.0, 3 / 4.0, band.vBottom, vB, tint, lines)
            return
        }
        plainZone(e, s0, s1, zA, zB, zScale, sRef, tint, lines)
    }

    /**
     * Glatte Wandflaeche. [zScale] = Texturmeter je Weltmeter in der Hoehe
     * (gedehnte Geschosse), [sRef] = Stelle, an der eine Fensterachse beginnt
     * (damit Fugen durchlaufen).
     */
    private fun plainZone(
        e: BuildingEdge, s0: Double, s1: Double, zA: Double, zB: Double, zScale: Double,
        sRef: Double, tint: Int, lines: DoubleArray,
    ) {
        if (s1 - s0 < 1e-6 || zB - zA < 1e-6) return
        val band = d.style.plain
        if (band == null) {
            // Fachwerk: Felder ohne Fenster, geschossweise.
            var z = zA
            while (z < zB - 1e-6) {
                val z2 = min(z + STOREY_M / zScale, zB)
                blind(e, s0, s1, z, z2, zScale, Band.TIMBER_WINDOWS.v((z2 - z) * zScale), tint, lines, sRef)
                z = z2
            }
            return
        }
        val u0 = band.u(s0 - sRef)
        val u1 = band.u(s1 - sRef)
        if (band.stretch) {
            wallRect(e, s0, s1, zA, zB, u0, u1, band.vBottom, band.v(min((zB - zA) * zScale, band.heightM)), tint, lines)
            return
        }
        val step = band.heightM / zScale
        var z = zA
        while (z < zB - 1e-6) {
            val z2 = min(z + step, zB)
            wallRect(e, s0, s1, z, z2, u0, u1, band.vBottom, band.v((z2 - z) * zScale), tint, lines)
            z = z2
        }
    }

    /** Tuer oder Tor knapp vor der Wand, bei Haustueren mit Stufe davor. */
    private fun decal(dc: BuildingDecal) {
        val e = d.edges[dc.edge]
        val s0 = (dc.s - dc.widthM / 2).coerceAtLeast(0.05)
        val s1 = (dc.s + dc.widthM / 2).coerceAtMost(e.len - 0.05)
        if (s1 - s0 < 0.5) return
        val none = DoubleArray(0)
        // Tore stehen auf dem Boden (vor dem Sockel), Tueren auf dem Fussboden.
        val gate = dc.decal == Decal.GARAGE_DOOR || dc.decal == Decal.BARN_GATE
        val g = max(e.ground0, e.ground1)
        val from = if (gate && g.isFinite()) min(d.floorU, g + 0.03) else d.floorU
        wallRect(e, s0, s1, from, d.floorU + dc.heightM, dc.decal.u0, dc.decal.u1, dc.decal.vBottom, dc.decal.vTop, dc.color, none, offset = 0.035)
        if (detail != BuildingDetail.FULL || d.kind != BuildingKind.HOUSE || gate) return
        // Eingangsstufe: Podest bis unter das Gelaende.
        val depth = 0.42
        val a = s0 - 0.12
        val b = s1 + 0.12
        val top = d.floorU - 0.03
        val c = flat(BuildingDesign.lin(0.30, 0.29, 0.27))
        box(wall, e, a, b, 0.0, depth, bottomU, top, c, c)
    }

    /**
     * Quader an einer Kante: [s0, s1] entlang, [o0, o1] nach aussen, [zA, zB]
     * hoch. Ohne Rueckseite und Boden (liegen an der Wand bzw. im Boden).
     */
    private fun box(m: MeshBuilder, e: BuildingEdge, s0: Double, s1: Double, o0: Double, o1: Double, zA: Double, zB: Double, side: Int, topColor: Int, bottomFace: Boolean = false) {
        fun pt(i: Int, s: Double, o: Double, z: Double) {
            buf[5 * i] = e.x0 + e.dx * s + e.nx * o
            buf[5 * i + 1] = e.y0 + e.dy * s + e.ny * o
            buf[5 * i + 2] = z
            buf[5 * i + 3] = trimU
            buf[5 * i + 4] = trimV
        }
        // vorn
        pt(0, s0, o1, zA); pt(1, s1, o1, zA); pt(2, s1, o1, zB); pt(3, s0, o1, zB)
        poly(m, buf, 4, e.nx, e.ny, 0.0, side)
        // Seiten
        pt(0, s0, o0, zA); pt(1, s0, o1, zA); pt(2, s0, o1, zB); pt(3, s0, o0, zB)
        poly(m, buf, 4, -e.dx, -e.dy, 0.0, side)
        pt(0, s1, o0, zA); pt(1, s1, o1, zA); pt(2, s1, o1, zB); pt(3, s1, o0, zB)
        poly(m, buf, 4, e.dx, e.dy, 0.0, side)
        // oben
        pt(0, s0, o0, zB); pt(1, s1, o0, zB); pt(2, s1, o1, zB); pt(3, s0, o1, zB)
        poly(m, buf, 4, 0.0, 0.0, 1.0, topColor)
        if (bottomFace) {
            pt(0, s0, o0, zA); pt(1, s1, o0, zA); pt(2, s1, o1, zA); pt(3, s0, o1, zA)
            poly(m, buf, 4, 0.0, 0.0, -1.0, side)
        }
    }

    // -----------------------------------------------------------------------
    // Dach
    // -----------------------------------------------------------------------

    /** Laengste Dachschraege des Gebaeudes vom Ueberstand bis zum First, in Metern entlang der Dachhaut. */
    private val slopeMax: Double = run {
        var m = 0.0
        for (w in d.wings) {
            if (w.flat || w.tan < 1e-6) continue
            m = max(m, (w.rise + d.eaveOver * w.tan) / (w.tan / sqrt(1.0 + w.tan * w.tan)))
        }
        if (m > 0) m else 1.0
    }

    private fun roofFace(w: RoofWing, f: RoofFace) {
        val n = f.pts.size / 3
        val low = w.role == WingRole.ANNEX
        when (f.kind) {
            RoofFaceKind.TOP -> {
                if (!textured) {
                    fill(f.pts, n) { _, _, _ -> }
                    poly(roof, buf, n, f.nx, f.ny, f.nz, if (low) d.annexFlatColor else d.roofFlatColor)
                    return
                }
                val band = (if (low) d.annexCover else d.cover).band
                val tint = if (low) d.annexTint else d.roofTint
                if (abs(f.downX) + abs(f.downY) < 0.5) {
                    // Ebene Dachflaeche eines Anbaus.
                    val ext = max(1e-6, w.y1 - w.y0)
                    fill(f.pts, n) { i, x, y ->
                        buf[5 * i + 3] = band.u(x)
                        buf[5 * i + 4] = band.v((y - w.y0) / ext * min(ext, band.heightM))
                    }
                    poly(roof, buf, n, f.nx, f.ny, f.nz, tint)
                    return
                }
                val sinP = w.tan / sqrt(1.0 + w.tan * w.tan)
                // Tiefster Punkt der Dachhaut: die Kante des Ueberstands.
                val edgeH = w.base - (if (solidRoof) d.eaveOver else 0.0) * w.tan
                val alongX = abs(f.downY) > 0.5
                val uOf = { x: Double, y: Double -> band.u(if (alongX) x else y) }
                if (band.stretch || detail != BuildingDetail.FULL || slopeMax <= band.heightM) {
                    // Eine Bahn ueber die ganze Schraege; ist sie laenger als das Band, werden die Reihen etwas gestreckt.
                    val scale = if (band.stretch) band.heightM / slopeMax else min(1.0, band.heightM / slopeMax)
                    fill(f.pts, n) { i, x, y ->
                        buf[5 * i + 3] = uOf(x, y)
                        buf[5 * i + 4] = band.v(((f.pts[3 * i + 2] - edgeH) / sinP * scale).coerceIn(0.0, band.heightM))
                    }
                    poly(roof, buf, n, f.nx, f.ny, f.nz, tint)
                    return
                }
                // Nah: in Bahnen von der Hoehe des Bands schneiden, damit die Pfannenreihen ihre Groesse behalten.
                val stripH = band.heightM * sinP
                val ridge = w.base + w.rise
                var k = 0
                while (edgeH + k * stripH < ridge - 1e-6 && k < 12) {
                    val lo = edgeH + k * stripH
                    val part = clipHeight(clipHeight(f.pts, lo, keepAbove = true), lo + stripH, keepAbove = false)
                    val m = part.size / 3
                    if (m in 3..16) {
                        fill(part, m) { i, x, y ->
                            buf[5 * i + 3] = uOf(x, y)
                            buf[5 * i + 4] = band.v(((part[3 * i + 2] - lo) / sinP).coerceIn(0.0, band.heightM))
                        }
                        poly(roof, buf, m, f.nx, f.ny, f.nz, tint)
                    }
                    k++
                }
            }
            RoofFaceKind.SOFFIT -> trim(f, n, d.soffitTint)
            RoofFaceKind.FASCIA_EAVE -> trim(f, n, if (d.kind == BuildingKind.HOUSE || d.kind == BuildingKind.BARN) GUTTER_TINT else d.trimTint)
            RoofFaceKind.FASCIA_VERGE, RoofFaceKind.PARAPET -> trim(f, n, d.trimTint)
        }
    }

    private fun trim(f: RoofFace, n: Int, color: Int) {
        fill(f.pts, n) { i, _, _ ->
            buf[5 * i + 3] = trimU
            buf[5 * i + 4] = trimV
        }
        poly(roof, buf, n, f.nx, f.ny, f.nz, if (textured) color else d.roofFlatColor)
    }

    /** Kopiert Punkte `x, y, h` nach [buf] (mit absoluter Hoehe) und laesst [uv] die Texturkoordinaten setzen. */
    private inline fun fill(pts: DoubleArray, n: Int, uv: (Int, Double, Double) -> Unit) {
        for (i in 0 until n) {
            buf[5 * i] = pts[3 * i]
            buf[5 * i + 1] = pts[3 * i + 1]
            buf[5 * i + 2] = d.eaveTopU + pts[3 * i + 2]
            buf[5 * i + 3] = 0.0
            buf[5 * i + 4] = 0.0
            uv(i, pts[3 * i], pts[3 * i + 1])
        }
    }

    /** Schneidet ein Vieleck (`x, y, h`) an der Hoehe [h] ab. */
    private fun clipHeight(pts: DoubleArray, h: Double, keepAbove: Boolean): DoubleArray {
        val n = pts.size / 3
        if (n < 3) return pts
        val out = ArrayList<Double>(pts.size + 6)
        val sgn = if (keepAbove) 1.0 else -1.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val f0 = (pts[3 * i + 2] - h) * sgn
            val f1 = (pts[3 * j + 2] - h) * sgn
            if (f0 >= -1e-9) {
                out.add(pts[3 * i]); out.add(pts[3 * i + 1]); out.add(pts[3 * i + 2])
            }
            if ((f0 < -1e-9 && f1 > 1e-9) || (f0 > 1e-9 && f1 < -1e-9)) {
                val t = f0 / (f0 - f1)
                out.add(pts[3 * i] + (pts[3 * j] - pts[3 * i]) * t)
                out.add(pts[3 * i + 1] + (pts[3 * j + 1] - pts[3 * i + 1]) * t)
                out.add(h)
            }
        }
        return out.toDoubleArray()
    }

    /** Waagerechte Flaeche ueber dem ganzen Grundriss (Flachdach, Deckel, Unterseite). */
    private fun flatPolygon(m: MeshBuilder, u: Double, up: Boolean, color: Int, band: Band?) {
        val tris = Triangulator.triangulate(d.rings)
        if (tris.isEmpty()) return
        val out = d.rings[0]
        var yMin = Double.MAX_VALUE
        var yMax = -Double.MAX_VALUE
        for (i in 0 until out.size / 2) {
            yMin = min(yMin, out[2 * i + 1])
            yMax = max(yMax, out[2 * i + 1])
        }
        val base = m.vertexCount
        val nu = if (up) 1.0 else -1.0
        for (r in d.rings) for (i in 0 until r.size / 2) {
            val x = r[2 * i]
            val y = r[2 * i + 1]
            val tu = band?.u(x) ?: trimU
            val tv = band?.v((y - yMin) / max(1e-6, yMax - yMin) * band.heightM) ?: trimV
            m.vertex(ctx.x(d.eOf(x, y)), ctx.y(u), ctx.z(d.nOf(x, y)), 0.0, nu, 0.0, tu, tv, color)
        }
        var k = 0
        while (k + 2 < tris.size) {
            // Der Triangulator liefert gegen den Uhrzeigersinn in (x, y) = von oben gesehen.
            if (up) m.tri(base + tris[k], base + tris[k + 1], base + tris[k + 2]) else m.tri(base + tris[k], base + tris[k + 2], base + tris[k + 1])
            k += 3
        }
    }

    private fun flatRoof() {
        if (!textured) {
            flatPolygon(roof, d.eaveTopU, up = true, color = d.roofFlatColor, band = null)
            return
        }
        val recess = if (detail == BuildingDetail.FULL || detail == BuildingDetail.MID) 0.14 else 0.0
        flatPolygon(roof, d.eaveTopU - recess, up = true, color = d.roofTint, band = Band.BITUMEN)
        if (recess <= 0) return
        // Innenseite der Attika.
        for (e in d.edges) {
            buf[0] = e.x0; buf[1] = e.y0; buf[2] = d.eaveTopU - recess; buf[3] = trimU; buf[4] = trimV
            buf[5] = e.x1; buf[6] = e.y1; buf[7] = d.eaveTopU - recess; buf[8] = trimU; buf[9] = trimV
            buf[10] = e.x1; buf[11] = e.y1; buf[12] = d.eaveTopU; buf[13] = trimU; buf[14] = trimV
            buf[15] = e.x0; buf[16] = e.y0; buf[17] = d.eaveTopU; buf[18] = trimU; buf[19] = trimV
            poly(roof, buf, 4, -e.nx, -e.ny, 0.0, d.trimTint)
        }
    }

    /** Notbehelf bei knappem Budget: Quader bis zur halben Dachhoehe, Deckel in Dachfarbe. */
    private fun block() {
        val top = d.eaveTopU + 0.45 * (d.ridgeU - d.eaveTopU)
        val wc = if (textured) flat(d.wallFlatColor) else d.wallFlatColor
        val rc = if (textured) flat(d.roofFlatColor) else d.roofFlatColor
        val none = DoubleArray(0)
        for (e in d.edges) if (!e.upper) wallRect(e, 0.0, e.len, bottomU, top, trimU, trimU, trimV, trimV, wc, none)
        flatPolygon(roof, top, up = true, color = rc, band = null)
        if (d.raised) flatPolygon(wall, bottomU, up = false, color = wc, band = null)
    }

    // -----------------------------------------------------------------------
    // Zubehoer
    // -----------------------------------------------------------------------

    /** Dachrinnen an den Traufen und ein Fallrohr je langer Traufe. */
    private fun gutters() {
        if (d.form == RoofForm.FLAT || d.kind == BuildingKind.SHED) return
        val zinc = GUTTER_TINT
        val dark = BuildingDesign.lin(0.10, 0.10, 0.11)
        var pipes = 0
        for (e in d.edges) {
            val w = e.piece ?: d.wings[0]
            if (e.gable != null || e.upper || w.flat || w.loose || w.role == WingRole.ANNEX) continue
            if (w.pent != 0 && (abs(w.topAt(e.x0, e.y0)) > 1e-6 || abs(w.topAt(e.x1, e.y1)) > 1e-6)) continue
            val tanW = w.tan
            fun ext(end: Int, neighbourGable: Boolean) = when {
                end == BuildingEdge.SPLIT -> 0.0
                end == BuildingEdge.REFLEX -> -d.eaveOver
                neighbourGable -> d.vergeOver
                else -> d.eaveOver + 0.11
            }
            val s0 = -ext(e.end0, neighbour(e, -1).gable != null || w.pent != 0)
            val s1 = e.len + ext(e.end1, neighbour(e, 1).gable != null || w.pent != 0)
            if (s1 - s0 < 0.8) continue
            val zTop = d.eaveTopU + w.base - d.eaveOver * tanW - 0.02
            box(roof, e, s0, s1, d.eaveOver, d.eaveOver + 0.11, zTop - 0.11, zTop, zinc, dark, bottomFace = true)
            if (e.len >= 4.0 && pipes < 2 && e.end1 == BuildingEdge.CONVEX) {
                // Fallrohr nahe der Ecke, von der Rinne bis in den Boden.
                val s = e.len - 0.22
                box(roof, e, s - 0.045, s + 0.045, 0.0, 0.09, bottomU, d.eaveTopU + w.base - thickness, zinc, zinc)
                pipes++
            }
        }
    }

    private fun neighbour(e: BuildingEdge, step: Int): BuildingEdge {
        val px = if (step < 0) e.x0 else e.x1
        val py = if (step < 0) e.y0 else e.y1
        return d.edges.firstOrNull { o -> o !== e && !o.upper && (if (step < 0) abs(o.x1 - px) + abs(o.y1 - py) else abs(o.x0 - px) + abs(o.y0 - py)) < 1e-9 } ?: e
    }

    /** `true`, wenn die Stelle (Fluegelkoordinaten) frei auf der Dachflaeche von [w] liegt — kein anderer Fluegel darueber. */
    private fun free(w: RoofWing, a: Double, b: Double, margin: Double): Boolean {
        val x = w.px(a, b)
        val y = w.py(a, b)
        for (o in d.wings) {
            if (o === w) continue
            if (x > o.x0 - margin && x < o.x1 + margin && y > o.y0 - margin && y < o.y1 + margin) return false
        }
        return true
    }

    /**
     * Schleppgauben auf der Dachseite zur Strecke hin (nur steile Wohnhausdaecher,
     * vereinzelt). Liefert die Seite (+1/−1), auf der sie sitzen, sonst 0.
     */
    private fun dormers(): Int {
        if (d.kind != BuildingKind.HOUSE || d.form != RoofForm.WINGS || d.tan < 0.68 || d.rnd(40) > 0.42) return 0
        val w = d.wings[0]
        val hw = w.halfWidth
        if (w.length < 7.5 || hw < 3.4) return 0
        val wide = if (d.style == FacadeStyle.SLATE) 1.95 else 1.45
        val df = 0.75
        val fh = 1.22
        val tanL = 0.16
        val z1 = df * d.tan + fh
        val d1 = (z1 - df * tanL) / (d.tan - tanL)
        if (d1 > hw - 0.25) return 0
        // Seite: wo die Traufe frei liegt; bevorzugt zur Strecke.
        val hit = ctx.track.nearest(d.ce, d.cn, 200.0)
        var side = if (d.rnd(41) < 0.5) 1 else -1
        if (hit != null) {
            val p = ctx.track.poseAt(hit.distM)
            val de = p.e - d.ce
            val dn = p.n - d.cn
            val fx = de * cosA + dn * sinA
            val fy = -de * sinA + dn * cosA
            val across = if (w.alongX) fy - w.mid else fx - w.mid
            side = if (across >= 0) 1 else -1
        }
        val count = if (w.length >= 11.5) 2 else 1
        val band = d.style.windows ?: Band.PLASTER_WINDOWS
        val cheek = BuildingDesign.lin(0.55, 0.56, 0.58)
        var built = 0
        for (i in 0 until count) {
            val a = w.a0 + w.length * (if (count == 1) 0.5 + 0.12 * (d.rnd(42) - 0.5) else (if (i == 0) 0.30 else 0.70))
            val a0 = a - wide / 2
            val a1 = a + wide / 2
            if (a0 < w.a0 + (if (w.hipLo) hw else 0.6) || a1 > w.a1 - (if (w.hipHi) hw else 0.6)) continue
            if (!free(w, a0, side * (hw - 0.2), 0.3) || !free(w, a1, side * (hw - 0.2), 0.3) || !free(w, a, side * (hw - d1), 0.3)) continue
            val bF = side * (hw - df)
            val bB = side * (hw - d1)
            val zF0 = d.eaveTopU + df * d.tan
            val zF1 = d.eaveTopU + z1
            val zB = d.eaveTopU + d1 * d.tan
            // Richtung „nach vorn" (aus dem Dach heraus) im Gebaeudesystem.
            val ox = if (w.alongX) 0.0 else side.toDouble()
            val oy = if (w.alongX) side.toDouble() else 0.0
            val lx = if (w.alongX) 1.0 else 0.0
            val ly = if (w.alongX) 0.0 else 1.0
            fun pt(k: Int, aa: Double, bb: Double, z: Double, tu: Double, tv: Double) {
                buf[5 * k] = w.px(aa, bb); buf[5 * k + 1] = w.py(aa, bb); buf[5 * k + 2] = z; buf[5 * k + 3] = tu; buf[5 * k + 4] = tv
            }
            // Front mit Fenster: Ausschnitt einer Fensterachse rund um das Fenster.
            val sc = 0.86
            val cx = BAY_M / 2
            val uL = band.u(cx - wide / 2 / sc)
            val uR = band.u(cx + wide / 2 / sc)
            val vLo = band.v(0.83)
            val vHi = band.v(0.83 + fh / sc)
            // Von aussen gesehen laeuft die Front bei side = +1 von a1 nach a0 (links nach rechts).
            val left = if (side > 0) a1 else a0
            val right = if (side > 0) a0 else a1
            pt(0, left, bF, zF0, uL, vLo); pt(1, right, bF, zF0, uR, vLo); pt(2, right, bF, zF1, uR, vHi); pt(3, left, bF, zF1, uL, vHi)
            poly(wall, buf, 4, ox, oy, 0.0, d.wallTint)
            // Wangen
            for (aa in doubleArrayOf(a0, a1)) {
                val sU = Band.SLATE.u(0.0)
                pt(0, aa, bF, zF0, sU, Band.SLATE.vBottom); pt(1, aa, bF, zF1, sU, Band.SLATE.v(fh)); pt(2, aa, bB, zB, Band.SLATE.u(d1 - df), Band.SLATE.v(fh))
                val sgn = if (aa == a0) -1.0 else 1.0
                poly(wall, buf, 3, sgn * lx, sgn * ly, 0.0, cheek)
            }
            // Deckel in Dachdeckung, faellt flach nach vorn.
            val rb = d.cover.band
            val lidLen = hypot(d1 - df, zB - zF1)
            pt(0, a0, bF, zF1, rb.u(a0), rb.v(0.0)); pt(1, a1, bF, zF1, rb.u(a1), rb.v(0.0))
            pt(2, a1, bB, zB, rb.u(a1), rb.v(min(lidLen, rb.heightM))); pt(3, a0, bB, zB, rb.u(a0), rb.v(min(lidLen, rb.heightM)))
            poly(roof, buf, 4, ox * tanL, oy * tanL, 1.0, d.roofTint)
            built++
        }
        return if (built > 0) side else 0
    }

    /** Schornstein nahe dem First des Hauptfluegels, auf der Seite ohne Gauben. */
    private fun chimney(dormerSide: Int) {
        if (d.kind != BuildingKind.HOUSE || d.form != RoofForm.WINGS || d.rnd(30) > 0.88) return
        val w = d.wings[0]
        val hw = w.halfWidth
        if (hw < 2.0 || w.length < 5.0) return
        val side = if (dormerSide != 0) -dormerSide else if (d.rnd(31) < 0.5) 1 else -1
        val sa = 0.25
        val sb = 0.30
        val b = side * min(hw - 0.8, 0.55 + 0.7 * d.rnd(32))
        var a = Double.NaN
        for (t in doubleArrayOf(0.30 + 0.16 * d.rnd(33), 0.70 - 0.16 * d.rnd(33), 0.5)) {
            val c = w.a0 + w.length * t
            if (c - sa < w.a0 + (if (w.hipLo) hw * 0.6 else 0.5) || c + sa > w.a1 - (if (w.hipHi) hw * 0.6 else 0.5)) continue
            if (free(w, c, b, 0.6)) {
                a = c
                break
            }
        }
        if (a.isNaN()) return
        val x = w.px(a, b)
        val y = w.py(a, b)
        val zTop = d.eaveTopU + w.rise + 0.42 + 0.3 * d.rnd(34)
        val zBot = d.eaveTopU + w.topAt(w.px(a, b + side * sb), w.py(a, b + side * sb)) - 0.15
        val brick = d.style != FacadeStyle.PLASTER || d.rnd(35) < 0.55
        val band = if (brick) Band.BRICK else Band.PLASTER
        val tint = if (brick) BuildingDesign.lin(0.85, 0.80, 0.78) else d.wallTint
        val h = zTop - zBot
        val vT = band.v(min(h, band.heightM))
        // Vier Seiten im Gebaeudesystem: Halbmasse entlang x und y.
        val hx = if (w.alongX) sa else sb
        val hy = if (w.alongX) sb else sa
        fun face(x0: Double, y0: Double, x1: Double, y1: Double, nx: Double, ny: Double, u: Double) {
            val len = hypot(x1 - x0, y1 - y0)
            buf[0] = x0; buf[1] = y0; buf[2] = zBot; buf[3] = band.u(u); buf[4] = band.vBottom
            buf[5] = x1; buf[6] = y1; buf[7] = zBot; buf[8] = band.u(u + len); buf[9] = band.vBottom
            buf[10] = x1; buf[11] = y1; buf[12] = zTop; buf[13] = band.u(u + len); buf[14] = vT
            buf[15] = x0; buf[16] = y0; buf[17] = zTop; buf[18] = band.u(u); buf[19] = vT
            poly(wall, buf, 4, nx, ny, 0.0, tint)
        }
        face(x - hx, y - hy, x + hx, y - hy, 0.0, -1.0, 0.0)
        face(x + hx, y - hy, x + hx, y + hy, 1.0, 0.0, 2 * hx)
        face(x + hx, y + hy, x - hx, y + hy, 0.0, 1.0, 2 * hx + 2 * hy)
        face(x - hx, y + hy, x - hx, y - hy, -1.0, 0.0, 4 * hx + 2 * hy)
        val cap = flat(BuildingDesign.lin(0.05, 0.05, 0.05))
        buf[0] = x - hx; buf[1] = y - hy; buf[2] = zTop; buf[3] = trimU; buf[4] = trimV
        buf[5] = x + hx; buf[6] = y - hy; buf[7] = zTop; buf[8] = trimU; buf[9] = trimV
        buf[10] = x + hx; buf[11] = y + hy; buf[12] = zTop; buf[13] = trimU; buf[14] = trimV
        buf[15] = x - hx; buf[16] = y + hy; buf[17] = zTop; buf[18] = trimU; buf[19] = trimV
        poly(wall, buf, 4, 0.0, 0.0, 1.0, cap)
    }

    private companion object {
        /** So weit steht der Sockel ueber dem Gelaende. */
        const val SOCLE_ABOVE_GROUND_M = 0.38

        /** Zinkblech: Rinnen, Fallrohre, Traufkante. */
        val GUTTER_TINT: Int = run {
            val m = BuildingTextures.meanLinear(Band.TRIM)
            BuildingDesign.lin(0.30 / m[0], 0.31 / m[1], 0.32 / m[2])
        }
    }
}

/** Arcus-Tangens in Grad — fuer Tests und Diagnose der Dachneigung. */
internal fun roofPitchDeg(tan: Double): Double = Math.toDegrees(atan(tan))
