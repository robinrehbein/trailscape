package de.trailscape.app.indoor.render

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * # Cockpit der Lenkerperspektive
 *
 * Was man vom eigenen Rad sieht: Rennlenker mit Bremsgriffen, Vorbau,
 * Oberkante des Steuerrohrs, ein Radcomputer und die eigenen Haende auf den
 * Griffen samt Unterarmen. Im Code gebaut (wie [AvatarModel]), geladen wie
 * jede Kachel ueber gltfio. Rahmen wie die Fahrermodelle (`WorldModels`):
 * Ursprung am Boden mittig zwischen den Raedern, Blick nach +Z, Y oben, Meter;
 * Masse und Farben grob wie `rider.glb` (Gravelrad, petrolfarbener Rahmen).
 *
 * Lenker, Griffe, Haende und Unterarme haengen am Knoten [NODE_BARS], der sich
 * um die Lenkachse dreht ([steerMatrix]); Steuerrohr und Radcomputer stehen
 * fest. Die Kamera sitzt an [CameraRig.HANDLEBAR_EYE] im selben Rahmen — so
 * liegt das Cockpit immer an derselben Stelle im Bild, nur der Lenker schlaegt
 * in Kurven leicht ein.
 */
object CockpitModel {
    const val NODE_ROOT = "x_cockpit"
    const val NODE_BARS = "x_cockpit_bars"

    /** Mitte des Lenkers (Klemmung) im Rahmen des Rads. */
    val BAR_CENTER = V3(0.0, 0.93, 0.43)

    /** Oberkante des Steuerrohrs; die Lenkachse laeuft hier durch. */
    val HEAD_TOP = V3(0.0, 0.88, 0.34)

    /** Lenkachse (72° Lenkwinkel), nach oben und hinten. */
    val STEER_AXIS: V3 = V3(0.0, sin(Math.toRadians(72.0)), -cos(Math.toRadians(72.0)))

    private const val HALF_WIDTH = 0.21

    private val FRAME = srgb(0x1F6272)
    private val BLACK = srgb(0x1D1D20)
    private val TAPE = srgb(0x2A2B2F)
    private val GLOVE = srgb(0x26272C)
    private val GLOVE_PAD = srgb(0x3A3C42)
    private val SKIN = srgb(0xC99272)
    private val SLEEVE = srgb(0xD8432C)
    private val SCREEN = srgb(0x6E8A7E)

    fun buildGlb(): ByteArray = build().build()

    fun build(): GlbWriter {
        val w = GlbWriter()
        fun mat(name: String, c: FloatArray, rough: Float) = w.material(GlbWriter.Material(name, c, roughness = rough))
        val mFrame = mat("x_mat_bike", FRAME, 0.55f)
        val mBlack = mat("x_mat_gear", BLACK, 0.6f)
        val mTape = mat("x_mat_tape", TAPE, 0.9f)
        val mGlove = mat("x_mat_glove", GLOVE, 0.85f)
        val mPad = mat("x_mat_glove_pad", GLOVE_PAD, 0.9f)
        val mSkin = mat("x_mat_skin", SKIN, 0.75f)
        val mSleeve = mat("x_mat_kit", SLEEVE, 0.9f)
        val mScreen = mat("x_mat_screen", SCREEN, 0.6f)

        // Fest: Steuerrohr, Spacer, Radcomputer-Halter
        // Steuerrohr und das vordere Stueck von Ober- und Unterrohr (laufen hinten aus dem Bild)
        val headLow = HEAD_TOP - STEER_AXIS * 0.16
        val frame = MeshBuilder()
            .tube(headLow, HEAD_TOP, 0.026, 16, 0.024)
            .tube(HEAD_TOP - STEER_AXIS * 0.03, V3(0.0, 0.83, -0.30), 0.02, 12, 0.018)
            .tube(headLow + STEER_AXIS * 0.03, V3(0.0, 0.42, -0.05), 0.026, 12, 0.028)
        val fixed = MeshBuilder()
            .tube(HEAD_TOP, HEAD_TOP + STEER_AXIS * 0.035, 0.0205, 16)
        // Lenker (dreht sich mit), relativ zur Lenkachse gebaut
        val stemBase = HEAD_TOP + STEER_AXIS * 0.035
        val black = MeshBuilder()
            .tube(stemBase - STEER_AXIS * 0.015, stemBase + STEER_AXIS * 0.02, 0.021, 16)
            .tube(stemBase, BAR_CENTER, 0.0175, 12, 0.019)
            .tube(BAR_CENTER + V3(-0.024, 0.0, 0.0), BAR_CENTER + V3(0.024, 0.0, 0.0), 0.021, 14)
            // Halter vorn am Vorbau
            .tube(BAR_CENTER + V3(0.0, 0.005, 0.0), BAR_CENTER + V3(0.0, 0.012, 0.075), 0.007, 8)
        val computer = MeshBuilder().box(BAR_CENTER + V3(0.0, 0.026, 0.085), V3(0.05, 0.018, 0.078))
        val screen = MeshBuilder().box(BAR_CENTER + V3(0.0, 0.036, 0.085), V3(0.04, 0.002, 0.06))

        val tape = MeshBuilder()
        val hoods = MeshBuilder()
        val gloves = MeshBuilder()
        val pads = MeshBuilder()
        val skin = MeshBuilder()
        val sleeves = MeshBuilder()
        val by = BAR_CENTER.y
        val bz = BAR_CENTER.z
        // Oberlenker
        tape.tube(V3(-0.03, by, bz), V3(-HALF_WIDTH + 0.02, by, bz), 0.0135, 12)
        tape.tube(V3(0.03, by, bz), V3(HALF_WIDTH - 0.02, by, bz), 0.0135, 12)
        for (side in listOf(-1.0, 1.0)) {
            val x = side * HALF_WIDTH
            // Bogen nach vorn zu den Griffen, dann der Unterlenker nach unten und zurueck
            val path = listOf(
                V3(x - side * 0.02, by, bz),
                V3(x, by + 0.002, bz + 0.03),
                V3(x + side * 0.004, by + 0.004, bz + 0.065),
                V3(x + side * 0.006, by - 0.02, bz + 0.105),
                V3(x + side * 0.008, by - 0.075, bz + 0.12),
                V3(x + side * 0.01, by - 0.125, bz + 0.09),
                V3(x + side * 0.01, by - 0.14, bz + 0.02),
            )
            for (i in 0 until path.size - 1) tape.tube(path[i], path[i + 1], 0.0135, 12)
            // Bremsgriff (Hood) obenauf, nach vorn ansteigend, Hebel nach unten
            val hoodBack = V3(x, by + 0.012, bz + 0.055)
            val hoodFront = V3(x, by + 0.05, bz + 0.135)
            hoods.tube(hoodBack, hoodFront, 0.019, 12, 0.016)
            hoods.sphere(hoodFront + V3(0.0, 0.006, 0.004), 0.017, 6, 10)
            hoods.tube(hoodFront + V3(0.0, -0.005, 0.012), V3(x + side * 0.004, by - 0.08, bz + 0.145), 0.007, 8, 0.006)
            // Hand auf dem Griff: Handruecken, Finger um den Griff, Daumen innen
            val palm = V3(x + side * 0.004, by + 0.052, bz + 0.085)
            gloves.sphere(palm, 0.05, 8, 12, V3(0.82, 0.55, 1.18))
            gloves.tube(palm + V3(side * 0.01, -0.01, 0.05), V3(x + side * 0.016, by + 0.0, bz + 0.13), 0.02, 10, 0.017)
            gloves.tube(palm + V3(-side * 0.03, 0.0, 0.02), V3(x - side * 0.03, by + 0.03, bz + 0.12), 0.012, 8, 0.01)
            pads.sphere(palm + V3(0.0, 0.025, -0.01), 0.03, 6, 10, V3(0.9, 0.35, 1.1))
            // Unterarm: kurz nach hinten-unten-aussen, verlaeuft unter dem Bildrand (kommt der
            // Kamera auch mit in die Kurve gedrehtem Blick nicht nah), Aermelsaum am Ende
            val wrist = palm + V3(side * 0.006, 0.012, -0.055)
            val elbowDir = V3(side * 0.18, 0.05, -1.0).normalized()
            val forearmEnd = wrist + elbowDir * 0.30
            skin.tube(wrist, forearmEnd, 0.028, 12, 0.036)
            skin.sphere(wrist, 0.028, 6, 10)
            sleeves.tube(forearmEnd - elbowDir * 0.01, forearmEnd + elbowDir * 0.12, 0.043, 12, 0.048)
        }

        val root = w.node(GlbWriter.Node(NODE_ROOT))
        val fixedMesh = w.mesh(
            "x_cockpit_fixed",
            listOf(frame.primitive(mFrame), fixed.primitive(mBlack)),
        )
        w.node(GlbWriter.Node("x_cockpit_frame", mesh = fixedMesh), root)
        // Lenkerknoten: Ursprung auf der Lenkachse (Oberkante Steuerrohr), Eckpunkte relativ dazu
        val pivot = HEAD_TOP
        val barsPrims = listOf(
            black.primitive(mBlack), computer.primitive(mBlack), screen.primitive(mScreen), tape.primitive(mTape),
            hoods.primitive(mBlack), gloves.primitive(mGlove), pads.primitive(mPad), skin.primitive(mSkin), sleeves.primitive(mSleeve),
        ).map { shifted(it, pivot) }
        val barsMesh = w.mesh("x_cockpit_bars", barsPrims)
        w.node(GlbWriter.Node(NODE_BARS, mesh = barsMesh, translation = doubleArrayOf(pivot.x, pivot.y, pivot.z)), root)
        return w
    }

    /** Lenkeinschlag [angleRad] (positiv = nach links) als Drehung um die Lenkachse durch [HEAD_TOP], Quaternion (x, y, z, w). */
    fun steerQuaternion(angleRad: Double): DoubleArray {
        val h = angleRad / 2
        val s = sin(h)
        return doubleArrayOf(STEER_AXIS.x * s, STEER_AXIS.y * s, STEER_AXIS.z * s, cos(h))
    }

    /** Lokale Matrix des Lenkerknotens (spaltenweise) fuer den Einschlag [angleRad]. */
    fun steerMatrix(angleRad: Double): FloatArray {
        val q = steerQuaternion(angleRad)
        val (x, y, z, ww) = listOf(q[0], q[1], q[2], q[3])
        val m = doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y + z * ww), 2 * (x * z - y * ww), 0.0,
            2 * (x * y - z * ww), 1 - 2 * (x * x + z * z), 2 * (y * z + x * ww), 0.0,
            2 * (x * z + y * ww), 2 * (y * z - x * ww), 1 - 2 * (x * x + y * y), 0.0,
            HEAD_TOP.x, HEAD_TOP.y, HEAD_TOP.z, 1.0,
        )
        return FloatArray(16) { m[it].toFloat() }
    }

    private fun shifted(p: GlbWriter.Primitive, o: V3): GlbWriter.Primitive {
        val pos = p.positions.copyOf()
        for (i in pos.indices step 3) {
            pos[i] -= o.x.toFloat()
            pos[i + 1] -= o.y.toFloat()
            pos[i + 2] -= o.z.toFloat()
        }
        return GlbWriter.Primitive(pos, p.normals, p.indices, p.material, p.colors)
    }

    private fun srgb(hex: Int): FloatArray {
        fun lin(c: Int): Float {
            val s = c / 255.0
            return (if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)).toFloat()
        }
        return floatArrayOf(lin((hex shr 16) and 0xFF), lin((hex shr 8) and 0xFF), lin(hex and 0xFF), 1f)
    }
}
