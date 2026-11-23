package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.WorldModels
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * # Der eingebaute Fahrer
 *
 * Rad und Fahrer als prozedurale Geometrie, im Code zu einer GLB gebaut —
 * fuer die Testszene (die keine Datei braucht) und als Rueckfall, wenn
 * `assets/indoor/models/rider.glb` fehlt oder sich nicht laden laesst.
 *
 * Haelt die Vereinbarung aus `WorldModels`: Ursprung am Boden mittig
 * zwischen den Radaufstandspunkten, Blick nach +Z, Y oben, Meter,
 * Lebensgroesse; Animation `pedal` = eine Kurbelumdrehung in 1,0 s (Kurbel,
 * Pedale, Beine), `roll` = eine Radumdrehung in 1,0 s bei 2,2 m
 * Abrollumfang. Die Beine folgen den Pedalen ueber eine Zwei-Gelenk-
 * Rechnung ([legAngles]) je Schluesselbild.
 */
object AvatarModel {
    /** Radius der Laufraeder: Abrollumfang 2,2 m. */
    val WHEEL_RADIUS_M: Double = WorldModels.WHEEL_CIRCUMFERENCE_M / (2 * PI)
    const val WHEELBASE_M = 1.04
    const val CRANK_M = 0.17
    const val THIGH_M = 0.44
    const val SHANK_M = 0.45
    const val KEYS = 16

    /** Tretlager (Kurbelachse) relativ zum Ursprung. */
    val BOTTOM_BRACKET = V3(0.0, 0.29, 0.02)

    /** Huefte (Drehpunkt der Oberschenkel), ohne Seitenversatz. */
    val HIP = V3(0.0, 0.99, -0.20)
    const val HIP_HALF_WIDTH = 0.10
    const val PEDAL_HALF_WIDTH = 0.12

    /** Gelenkwinkel eines Beins: Drehung um +X fuer Oberschenkel (Huefte) und Unterschenkel (Knie, relativ). */
    data class LegAngles(val hipRad: Double, val kneeRad: Double, val knee: V3, val foot: V3)

    /** Lage des Pedals (Mitte der Achse in der Seitenebene) fuer den Kurbelwinkel [crankRad]. */
    fun pedal(crankRad: Double): V3 =
        BOTTOM_BRACKET + V3(0.0, CRANK_M * cos(crankRad), CRANK_M * sin(crankRad))

    /**
     * Zwei-Gelenk-Rechnung in der Seitenebene (Y, Z): Huefte fest, Fuss auf
     * dem Pedal, Knie nach vorn. Ruhelage beider Glieder ist senkrecht nach
     * unten (−Y); eine Drehung um +X um `α` zeigt dann nach `(0, −cos α, −sin α)`.
     */
    fun legAngles(crankRad: Double): LegAngles {
        val foot = pedal(crankRad) + V3(0.0, 0.06, -0.03)
        val h = HIP
        val dz = foot.z - h.z
        val dy = foot.y - h.y
        val dist = kotlin.math.sqrt(dz * dz + dy * dy).coerceIn(0.05, THIGH_M + SHANK_M - 0.005)
        val uz = dz / dist
        val uy = dy / dist
        // Knie vorn: Senkrechte zur Linie Huefte→Fuss mit positivem Z-Anteil.
        val nz = -uy
        val ny = uz
        val cosA = ((THIGH_M * THIGH_M + dist * dist - SHANK_M * SHANK_M) / (2 * THIGH_M * dist)).coerceIn(-1.0, 1.0)
        val a = acos(cosA)
        val kz = h.z + THIGH_M * (cosA * uz + sin(a) * nz)
        val ky = h.y + THIGH_M * (cosA * uy + sin(a) * ny)
        val hip = atan2(-(kz - h.z), -(ky - h.y))
        val shankWorld = atan2(-(foot.z - kz), -(foot.y - ky))
        return LegAngles(hip, shankWorld - hip, V3(0.0, ky, kz), foot)
    }

    /** Quaternion `(x, y, z, w)` fuer eine Drehung um +X. */
    fun quatX(rad: Double): DoubleArray = doubleArrayOf(sin(rad / 2), 0.0, 0.0, cos(rad / 2))

    data class Palette(
        val kit: FloatArray,
        val kitDark: FloatArray,
        val skin: FloatArray,
        val helmet: FloatArray,
        val frame: FloatArray,
        val tire: FloatArray,
        val metal: FloatArray,
        val blend: Boolean,
    )

    val RIDER = Palette(
        kit = floatArrayOf(0.02f, 0.30f, 0.34f, 1f),
        kitDark = floatArrayOf(0.02f, 0.02f, 0.025f, 1f),
        skin = floatArrayOf(0.62f, 0.40f, 0.28f, 1f),
        helmet = floatArrayOf(0.85f, 0.85f, 0.82f, 1f),
        frame = floatArrayOf(0.55f, 0.12f, 0.05f, 1f),
        tire = floatArrayOf(0.03f, 0.03f, 0.03f, 1f),
        metal = floatArrayOf(0.35f, 0.35f, 0.36f, 1f),
        blend = false,
    )

    /** Der Geist: dasselbe Modell, hell und durchscheinend (`alphaMode = BLEND`). */
    val GHOST = Palette(
        kit = floatArrayOf(0.55f, 0.85f, 1.0f, 0.38f),
        kitDark = floatArrayOf(0.45f, 0.70f, 0.95f, 0.38f),
        skin = floatArrayOf(0.60f, 0.85f, 1.0f, 0.38f),
        helmet = floatArrayOf(0.75f, 0.92f, 1.0f, 0.38f),
        frame = floatArrayOf(0.50f, 0.80f, 1.0f, 0.38f),
        tire = floatArrayOf(0.40f, 0.65f, 0.90f, 0.38f),
        metal = floatArrayOf(0.55f, 0.80f, 1.0f, 0.38f),
        blend = true,
    )

    /** Baut die GLB. Ergebnis wie `rider.glb`/`ghost.glb` aus dem Weltbau. */
    fun buildGlb(p: Palette = RIDER): ByteArray = build(p).build()

    fun build(p: Palette = RIDER): GlbWriter {
        val w = GlbWriter()
        fun mat(name: String, c: FloatArray, rough: Float = 0.8f) =
            w.material(GlbWriter.Material(name, c, roughness = rough, blend = p.blend))
        val mKit = mat("x_mat_kit", p.kit)
        val mDark = mat("x_mat_kit_dark", p.kitDark)
        val mSkin = mat("x_mat_skin", p.skin)
        val mHelmet = mat("x_mat_helmet", p.helmet, 0.6f)
        val mFrame = mat("x_mat_bike", p.frame, 0.6f)
        val mTire = mat("x_mat_tire", p.tire, 0.95f)
        val mMetal = mat("x_mat_gear", p.metal, 0.6f)

        val root = w.node(GlbWriter.Node("x_rider"))
        val r = WHEEL_RADIUS_M
        val rear = V3(0.0, r, -WHEELBASE_M / 2)
        val front = V3(0.0, r, WHEELBASE_M / 2)
        val bb = BOTTOM_BRACKET

        // Rahmen (fest)
        val seatTop = V3(0.0, 0.86, -0.17)
        val headTop = V3(0.0, 0.88, 0.36)
        val headLow = V3(0.0, 0.72, 0.40)
        val frame = MeshBuilder()
            .tube(bb, seatTop, 0.017)
            .tube(bb, headLow, 0.02)
            .tube(seatTop, headTop, 0.016)
            .tube(headLow, headTop, 0.022)
            .tube(bb, rear + V3(0.06, 0.0, 0.0), 0.011)
            .tube(bb, rear + V3(-0.06, 0.0, 0.0), 0.011)
            .tube(seatTop, rear + V3(0.06, 0.0, 0.0), 0.009)
            .tube(seatTop, rear + V3(-0.06, 0.0, 0.0), 0.009)
            .tube(headLow, front + V3(0.05, 0.0, 0.0), 0.012)
            .tube(headLow, front + V3(-0.05, 0.0, 0.0), 0.012)
        val dark = MeshBuilder()
            .tube(seatTop, seatTop + V3(0.0, 0.10, -0.03), 0.013) // Sattelstuetze
            .box(seatTop + V3(0.0, 0.12, -0.02), V3(0.13, 0.04, 0.26)) // Sattel
            .tube(headTop, headTop + V3(0.0, 0.06, 0.09), 0.015) // Vorbau
            .tube(V3(-0.21, 0.95, 0.46), V3(0.21, 0.95, 0.46), 0.013) // Lenker
            .tube(V3(-0.21, 0.95, 0.46), V3(-0.21, 0.86, 0.50), 0.012)
            .tube(V3(0.21, 0.95, 0.46), V3(0.21, 0.86, 0.50), 0.012)
        val bikeMesh = w.mesh("x_bike", listOf(frame.primitive(mFrame), dark.primitive(mDark)))
        w.node(GlbWriter.Node("x_bike", mesh = bikeMesh), root)

        // Laufraeder: Reifen, Felge, Speichen, Nabe — drehen um +X.
        fun wheelMesh(name: String): Int {
            val tire = MeshBuilder().torusX(V3.ZERO, r - 0.022, 0.022)
            val metal = MeshBuilder().torusX(V3.ZERO, r - 0.05, 0.012, seg = 24, side = 4)
                .tube(V3(-0.05, 0.0, 0.0), V3(0.05, 0.0, 0.0), 0.022)
            for (k in 0 until 8) {
                val a = 2 * PI * k / 8
                val dir = V3(0.0, cos(a), sin(a))
                metal.tube(dir * 0.02, dir * (r - 0.055), 0.004, seg = 4)
            }
            return w.mesh(name, listOf(tire.primitive(mTire), metal.primitive(mMetal)))
        }
        val wheelRear = w.node(GlbWriter.Node("x_wheel_rear", mesh = wheelMesh("x_wheel_rear"), translation = rear.arr()), root)
        val wheelFront = w.node(GlbWriter.Node("x_wheel_front", mesh = wheelMesh("x_wheel_front"), translation = front.arr()), root)

        // Kurbel mit Pedalen — dreht um +X; die rechte Kurbel (−X) zeigt bei 0 nach oben.
        // Blick nach +Z, oben +Y: rechts vom Fahrer ist −X. Kettenblatt und rechte Kurbel liegen dort.
        val crankMesh = MeshBuilder()
            .torusX(V3(-0.075, 0.0, 0.0), 0.09, 0.008, seg = 20, side = 4) // Kettenblatt
            .tube(V3(-0.09, 0.0, 0.0), V3(-0.09, CRANK_M, 0.0), 0.011, seg = 6)
            .tube(V3(0.09, 0.0, 0.0), V3(0.09, -CRANK_M, 0.0), 0.011, seg = 6)
            .tube(V3(-0.09, 0.0, 0.0), V3(0.09, 0.0, 0.0), 0.015, seg = 6)
            .box(V3(-PEDAL_HALF_WIDTH - 0.02, CRANK_M, 0.0), V3(0.08, 0.02, 0.09))
            .box(V3(PEDAL_HALF_WIDTH + 0.02, -CRANK_M, 0.0), V3(0.08, 0.02, 0.09))
        val crank = w.node(GlbWriter.Node("x_crank", mesh = w.mesh("x_crank", listOf(crankMesh.primitive(mMetal))), translation = bb.arr()), root)

        // Oberkoerper (fest): Rumpf vorgebeugt, Kopf mit Helm, Arme zum Lenker.
        val hipC = HIP
        val shoulder = V3(0.0, 1.36, 0.17)
        val torso = MeshBuilder()
            .tube(hipC + V3(0.0, 0.02, 0.0), shoulder, 0.15, seg = 10, r2 = 0.14)
            .tube(V3(-0.18, shoulder.y, shoulder.z), V3(-0.215, 0.96, 0.44), 0.042, seg = 6)
            .tube(V3(0.18, shoulder.y, shoulder.z), V3(0.215, 0.96, 0.44), 0.042, seg = 6)
            .sphere(V3(-0.17, shoulder.y, shoulder.z), 0.07, rings = 4, seg = 8)
            .sphere(V3(0.17, shoulder.y, shoulder.z), 0.07, rings = 4, seg = 8)
        val shorts = MeshBuilder().sphere(hipC, 0.16, rings = 6, seg = 10, scale = V3(1.05, 0.7, 1.2))
        val head = MeshBuilder()
            .tube(shoulder + V3(0.0, 0.02, 0.02), shoulder + V3(0.0, 0.12, 0.08), 0.05, seg = 6)
            .sphere(shoulder + V3(0.0, 0.22, 0.15), 0.10, rings = 6, seg = 10)
        val helmet = MeshBuilder().sphere(shoulder + V3(0.0, 0.25, 0.14), 0.13, rings = 6, seg = 10, scale = V3(0.95, 0.75, 1.25))
        val bodyMesh = w.mesh(
            "x_body",
            listOf(torso.primitive(mKit), shorts.primitive(mDark), head.primitive(mSkin), helmet.primitive(mHelmet)),
        )
        w.node(GlbWriter.Node("x_body", mesh = bodyMesh), root)

        // Beine: Oberschenkel an der Huefte, Unterschenkel am Knie, Fuss am Unterschenkel.
        fun legMeshes(side: Double): Pair<Int, Int> {
            val thigh = MeshBuilder().tube(V3.ZERO, V3(0.0, -THIGH_M, 0.0), 0.075, seg = 8, r2 = 0.055)
            val shank = MeshBuilder().tube(V3.ZERO, V3(0.0, -SHANK_M, 0.0), 0.05, seg = 8, r2 = 0.035)
            val shoe = MeshBuilder().box(V3(0.0, -SHANK_M - 0.03, 0.05), V3(0.09, 0.07, 0.25))
            val s = if (side < 0) "r" else "l"
            return w.mesh("x_thigh_$s", listOf(thigh.primitive(mDark))) to
                w.mesh("x_shank_$s", listOf(shank.primitive(mSkin), shoe.primitive(mDark)))
        }
        // side = −1: rechtes Bein (−X), side = +1: linkes Bein (+X).
        val legNodes = listOf(-1.0, 1.0).map { side ->
            val (tm, sm) = legMeshes(side)
            val s = if (side < 0) "r" else "l"
            val x = side * HIP_HALF_WIDTH
            val thigh = w.node(GlbWriter.Node("x_thigh_$s", mesh = tm, translation = doubleArrayOf(x, HIP.y, HIP.z)), root)
            val shank = w.node(GlbWriter.Node("x_shank_$s", mesh = sm, translation = doubleArrayOf(side * 0.02, -THIGH_M, 0.0)), thigh)
            Triple(side, thigh, shank)
        }

        // Animationen: 16 Schluessel je Umdrehung plus der Schluss = Anfang.
        val times = FloatArray(KEYS + 1) { it.toFloat() / KEYS }
        fun spin(node: Int) = GlbWriter.RotationChannel(
            node,
            times,
            FloatArray((KEYS + 1) * 4).also { q ->
                for (k in 0..KEYS) quatX(2 * PI * k / KEYS).forEachIndexed { c, v -> q[k * 4 + c] = v.toFloat() }
            },
        )
        val legChannels = legNodes.flatMap { (side, thigh, shank) ->
            val offset = if (side < 0) 0.0 else PI // rechte Kurbel bei 0 oben, linke gegenueber
            val hipQ = FloatArray((KEYS + 1) * 4)
            val kneeQ = FloatArray((KEYS + 1) * 4)
            for (k in 0..KEYS) {
                val a = legAngles(2 * PI * k / KEYS + offset)
                quatX(a.hipRad).forEachIndexed { c, v -> hipQ[k * 4 + c] = v.toFloat() }
                quatX(a.kneeRad).forEachIndexed { c, v -> kneeQ[k * 4 + c] = v.toFloat() }
            }
            listOf(GlbWriter.RotationChannel(thigh, times, hipQ), GlbWriter.RotationChannel(shank, times, kneeQ))
        }
        w.animation(GlbWriter.Animation(WorldModels.ANIM_PEDAL, listOf(spin(crank)) + legChannels))
        w.animation(GlbWriter.Animation(WorldModels.ANIM_ROLL, listOf(spin(wheelRear), spin(wheelFront))))
        return w
    }

    private fun V3.arr() = doubleArrayOf(x, y, z)
}
