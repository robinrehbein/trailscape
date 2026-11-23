package de.trailscape.core.indoor.world

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Die Modelle `rider.glb` und `ghost.glb`
 *
 * Ein Gravel-Fahrer in Lebensgroesse, vollstaendig hier erzeugt (keine
 * fremden Bestandteile). Vertrag nach docs/indoor.md 7.5: Ursprung am Boden
 * mittig zwischen den Radaufstandspunkten, Blick nach +Z, Y oben, Meter —
 * die linke Seite des Fahrers liegt damit bei +X, der Antrieb bei −X.
 *
 * **Aufbau.** Starre Knotenhierarchie ohne Skins: Rahmen, zwei Laufraeder,
 * Kurbel mit zwei Pedalen, Oberkoerper, je Bein Oberschenkel → Unterschenkel
 * → Fuss. Die Gelenke sind Kugelenden gleicher Dicke, die ineinander stecken;
 * dadurch klafft in keiner Stellung eine Luecke.
 *
 * **Animationen.** `pedal` (eine Kurbelumdrehung in 1,0 s) dreht Kurbel und
 * Pedale und fuehrt die Beine ueber eine Zwei-Gelenk-Kinematik
 * ([RiderRig.legPose]): Der Fuss steht mit natuerlichem Winkel auf der
 * Pedalachse, das Knie liegt in der Ebene durch Huefte und Knoechel und
 * knickt immer nach vorn. `roll` dreht nur die beiden Laufraeder. Beide
 * haben [RiderRig.KEYFRAMES] + 1 Stuetzstellen, linear; die Darstellung setzt
 * nur die Phase.
 *
 * **Geist.** Dieselbe Geometrie mit einem einzigen durchscheinenden Material
 * ohne Vertexfarben; Teile, die nur im Inneren liegen oder als feine Linien
 * flimmern wuerden, fehlen.
 */

/** Materialien des Fahrermodells; alle Kernmaterial, `metallic = 0`. */
internal enum class RiderMat(val matName: String, val roughness: Float) {
    SKIN("x_mat_skin", 0.75f),
    KIT("x_mat_kit", 0.92f),
    GEAR("x_mat_gear", 0.6f),
    BIKE("x_mat_bike", 0.62f),
    RUBBER("x_mat_tire", 0.95f),
}

internal const val GHOST_MATERIAL = "x_mat_ghost"

/** Knotennamen der Modelle. */
internal object RiderNodes {
    const val ROOT = "x_rider"
    const val BIKE = "x_bike"
    const val WHEEL_FRONT = "x_wheel_front"
    const val WHEEL_REAR = "x_wheel_rear"
    const val CRANK = "x_crank"
    const val PEDAL_L = "x_pedal_l"
    const val PEDAL_R = "x_pedal_r"
    const val BODY = "x_body"
    const val THIGH_L = "x_thigh_l"
    const val SHANK_L = "x_shank_l"
    const val FOOT_L = "x_foot_l"
    const val THIGH_R = "x_thigh_r"
    const val SHANK_R = "x_shank_r"
    const val FOOT_R = "x_foot_r"
}

/** Stellung eines Beins: Gelenkpunkte im Modellraum und Drehungen der drei Knoten (jeweils zum Elternknoten). */
internal class LegPose(
    val hip: Vec3,
    val knee: Vec3,
    val ankle: Vec3,
    val axle: Vec3,
    val thigh: Quat,
    val shank: Quat,
    val foot: Quat,
    /** Innenwinkel am Knie in Grad (180 = gestreckt). */
    val kneeAngleDeg: Double,
)

/** Masse von Rad und Fahrer und die Beinkinematik. */
internal object RiderRig {
    /** Aussenradius der Laufraeder aus dem Abrollumfang. */
    val WHEEL_R = WorldModels.WHEEL_CIRCUMFERENCE_M / (2 * PI)
    const val REAR_Z = -0.51
    const val FRONT_Z = 0.51
    const val BB_Y = 0.28
    private const val CHAINSTAY = 0.425
    val BB_Z = REAR_Z + sqrt(CHAINSTAY * CHAINSTAY - (WHEEL_R - BB_Y) * (WHEEL_R - BB_Y))

    /** Kurbellaenge. */
    const val CRANK = 0.1725

    /** Seitlicher Abstand der Fussmitte von der Radebene. */
    const val PEDAL_X = 0.125

    const val HIP_X = 0.092
    const val HIP_Y = 1.06
    const val HIP_Z = -0.30
    const val THIGH = 0.44
    const val SHANK = 0.43

    /** Vom Knoechel zur Pedalachse, im Rahmen des Fusses (Y senkrecht zur Sohle, Z zur Fussspitze). */
    val FOOT_TO_AXLE = Vec3(0.0, -0.105, 0.12)

    /** Stuetzstellen je Umdrehung (dazu die schliessende). */
    const val KEYFRAMES = 48

    /** Drehpunkt des leichten Oberkoerper-Wiegens: die Sattelmitte. */
    val BODY_PIVOT = Vec3(0.0, 0.98, -0.31)
    private const val BODY_SWAY_RAD = 0.021

    val REAR_AXLE get() = Vec3(0.0, WHEEL_R, REAR_Z)
    val FRONT_AXLE get() = Vec3(0.0, WHEEL_R, FRONT_Z)
    val BB get() = Vec3(0.0, BB_Y, BB_Z)

    fun hip(left: Boolean) = Vec3(if (left) HIP_X else -HIP_X, HIP_Y, HIP_Z)

    /** Kurbelwinkel der Seite: 0 = Kurbel oben, wachsend nach vorn. Die linke Kurbel steht bei Phase 0 oben. */
    fun crankAngle(phase: Double, left: Boolean): Double = 2 * PI * phase + (if (left) 0.0 else PI)

    fun pedalAxle(phase: Double, left: Boolean): Vec3 {
        val th = crankAngle(phase, left)
        return Vec3(if (left) PEDAL_X else -PEDAL_X, BB_Y + CRANK * cos(th), BB_Z + CRANK * sin(th))
    }

    /**
     * Neigung der Sohle (Spitze nach unten positiv) ueber den Kurbelwinkel:
     * im Druck nach vorn-unten fast flach, im Hochziehen deutlich gesenkte Spitze.
     */
    fun footPitch(crankAngle: Double): Double = Math.toRadians(14.0 - 9.0 * cos(crankAngle - Math.toRadians(80.0)))

    /** Wiegen des Oberkoerpers um die Laengsachse durch den Sattel: zur druckenden Seite hin. */
    fun bodySway(phase: Double): Quat = Quat.fromAxisAngle(Vec3.Z, -BODY_SWAY_RAD * sin(2 * PI * phase))

    /** Beinstellung zur Kurbelphase [phase] in Umdrehungen. */
    fun legPose(phase: Double, left: Boolean): LegPose {
        val axle = pedalAxle(phase, left)
        val footWorld = Quat.fromAxisAngle(Vec3.X, footPitch(crankAngle(phase, left)))
        val ankle = axle - footWorld.rotate(FOOT_TO_AXLE)
        val hip = hip(left)
        val d = ankle - hip
        val dist = d.length()
        check(dist < THIGH + SHANK - 1e-3 && dist > abs(THIGH - SHANK) + 1e-3) { "Bein erreicht das Pedal nicht (Abstand $dist)" }
        val dn = d * (1 / dist)
        // Beinebene: enthaelt Huefte und Knoechel, ihre Normale liegt so nah an der Querachse wie moeglich.
        val n = (Vec3.X - dn * (dn dot Vec3.X)).normalized(Vec3.X)
        val forward = dn cross n
        val a = (THIGH * THIGH - SHANK * SHANK + dist * dist) / (2 * dist)
        val h = sqrt(THIGH * THIGH - a * a)
        val knee = hip + dn * a + forward * h
        val thighUp = (hip - knee) * (1 / THIGH)
        val shankUp = (knee - ankle) * (1 / SHANK)
        val thighWorld = quatFromBasis(n, thighUp, n cross thighUp)
        val shankWorld = quatFromBasis(n, shankUp, n cross shankUp)
        val kneeAngle = Math.toDegrees(acos((thighUp dot (ankle - knee) * (1 / SHANK)).coerceIn(-1.0, 1.0)))
        return LegPose(
            hip, knee, ankle, axle,
            thighWorld, (thighWorld.conjugate() * shankWorld).normalized(), (shankWorld.conjugate() * footWorld).normalized(),
            kneeAngle,
        )
    }
}

// ---------------------------------------------------------------------------
// Farben (sRGB)
// ---------------------------------------------------------------------------

private val C_SKIN = lin(0xD6A07E)
private val C_HAIR = lin(0x4A3526)
private val C_JERSEY = lin(0xD8432C)
private val C_JERSEY_DARK = lin(0x8C2519)
private val C_JERSEY_LIGHT = lin(0xF1EEE8)
private val C_BIBS = lin(0x25262B)
private val C_BIBS_BAND = lin(0x3C3E45)
private val C_SOCK = lin(0xEEEEEA)
private val C_SHOE = lin(0xF2F2EE)
private val C_SOLE = lin(0x1E1E21)
private val C_GLOVE = lin(0x202126)
private val C_HELMET = lin(0xF0F0EC)
private val C_VENT = lin(0x19191C)
private val C_GLASSES = lin(0x16181C)
private val C_FRAME = lin(0x1F6272)
private val C_FRAME_LIGHT = lin(0xE8E6DF)
private val C_BLACK = lin(0x1D1D20)
private val C_DARK = lin(0x2E2F33)
private val C_METAL = lin(0xA9ACB1)
private val C_CHAIN = lin(0x6C6E73)
private val C_TREAD = lin(0x1C1C1D)
private val C_SIDEWALL = lin(0xB28B5C)
private val C_RIM = lin(0x1A1A1C)
private val C_BOTTLE = lin(0xE6ECEF)

private val MIRROR_X = Mat4.scale(-1.0, 1.0, 1.0)

/** Netze je Knoten und Material, in fester Reihenfolge. */
private class Parts {
    private val nodes = LinkedHashMap<String, LinkedHashMap<RiderMat, MeshBuilder>>()

    fun mesh(node: String, mat: RiderMat): MeshBuilder =
        nodes.getOrPut(node) { LinkedHashMap() }.getOrPut(mat) { MeshBuilder(2048, 4096) }

    /** Baut links (+X) und haengt das Ergebnis zusaetzlich gespiegelt an. */
    fun both(node: String, mat: RiderMat, build: MeshBuilder.() -> Unit) {
        val t = MeshBuilder(512, 1024)
        t.build()
        val m = mesh(node, mat)
        m.append(t)
        m.append(t, MIRROR_X)
    }

    fun of(node: String): Map<RiderMat, MeshBuilder> = nodes[node] ?: emptyMap()
}

private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

/** Rundes Rohr durch [keys] (weich verfeinert), Enden wahlweise geschlossen. */
private fun MeshBuilder.addTube(keys: List<Key>, sub: Int = 1, segments: Int = 14, caps: Boolean = true, ref: Vec3 = Vec3.X) {
    addSkin(buildRings(keys, sub, ref), segments, caps, caps)
}

// ---------------------------------------------------------------------------
// Rad
// ---------------------------------------------------------------------------

/** Abgeleitete Punkte der Rahmengeometrie (Seitenansicht, x = 0). */
private object Frame {
    private val HEAD_ANGLE = Math.toRadians(72.0)
    private val SEAT_ANGLE = Math.toRadians(73.5)
    private const val RAKE = 0.047
    private const val AXLE_TO_CROWN = 0.395

    /** Lenkachse nach oben (und hinten). */
    val steer = Vec3(0.0, sin(HEAD_ANGLE), -cos(HEAD_ANGLE))

    /** Senkrecht zur Lenkachse nach vorn. */
    val steerFwd = Vec3(0.0, cos(HEAD_ANGLE), sin(HEAD_ANGLE))
    val crown = RiderRig.FRONT_AXLE - steerFwd * RAKE + steer * sqrt(AXLE_TO_CROWN * AXLE_TO_CROWN - RAKE * RAKE)
    val headBottom = crown + steer * 0.008
    val headTop = headBottom + steer * 0.155
    val stemBase = headTop + steer * 0.036
    private val STEM_ANGLE = Math.toRadians(11.0)
    val barCenter = stemBase + Vec3(0.0, sin(STEM_ANGLE), cos(STEM_ANGLE)) * 0.10

    val seatDir = Vec3(0.0, sin(SEAT_ANGLE), -cos(SEAT_ANGLE))
    val seatTop = RiderRig.BB + seatDir * 0.50
    val postTop = RiderRig.BB + seatDir * 0.672

    /** Hoehe der Satteloberflaeche. */
    const val SADDLE_Y = 0.98
    const val HUB_HALF = 0.05
    const val DROPOUT_X = 0.066
    const val CHAIN_X = -0.048
    const val CHAINRING_R = 0.085
    const val COG_R = 0.05
}

private fun buildFrame(p: Parts, ghost: Boolean) {
    val node = RiderNodes.BIKE
    val f = p.mesh(node, RiderMat.BIKE)
    val bb = RiderRig.BB
    val rear = RiderRig.REAR_AXLE
    val front = RiderRig.FRONT_AXLE

    // Hauptdreieck
    val ttHead = Frame.headTop - Frame.steer * 0.04
    val ttSeat = bb + Frame.seatDir * 0.465
    val dtHead = Frame.headBottom + Frame.steer * 0.042
    f.addLoft(listOf(bb, Frame.seatTop), doubleArrayOf(0.019, 0.0165), 16, color = C_FRAME)
    f.addLoft(listOf(Frame.headBottom, Frame.headTop), doubleArrayOf(0.029, 0.0245), 18, true, true, C_FRAME)
    f.addTube(
        listOf(Key(ttHead, 0.019, 0.023, color = C_FRAME), Key(ttHead.lerp(ttSeat, 0.5), 0.018, 0.019, color = C_FRAME), Key(ttSeat, 0.016, 0.016, color = C_FRAME)),
        2, 16, false,
    )
    f.addTube(
        // Helles Feld in der Mitte des Unterrohrs, mit scharfen Kanten (eng gesetzte Querschnitte).
        listOf(0.0 to C_FRAME, 0.3 to C_FRAME, 0.31 to C_FRAME_LIGHT, 0.7 to C_FRAME_LIGHT, 0.71 to C_FRAME, 1.0 to C_FRAME).map { (t, color) ->
            Key(dtHead.lerp(bb, t), lerp(0.022, 0.03, t), lerp(0.027, 0.029, t), color = color)
        },
        1, 18, false,
    )
    f.addCylinder(Vec3(-0.036, bb.y, bb.z), Vec3(0.036, bb.y, bb.z), 0.025, 0.025, 18, color = C_FRAME)
    // Sattelklemme und Stuetze
    f.addLoft(listOf(Frame.seatTop - Frame.seatDir * 0.012, Frame.seatTop + Frame.seatDir * 0.006), doubleArrayOf(0.0195, 0.0195), 16, true, true, C_BLACK)
    f.addLoft(listOf(Frame.seatTop, Frame.postTop), doubleArrayOf(0.0136, 0.0136), 14, false, true, C_BLACK)
    f.addBox(-0.018, Frame.postTop.y - 0.004, Frame.postTop.z - 0.03, 0.018, Frame.SADDLE_Y - 0.026, Frame.postTop.z + 0.03, C_BLACK)

    // Hinterbau, links und rechts
    p.both(node, RiderMat.BIKE) {
        addTube(
            listOf(
                Key(sv(bb.z - 0.012, bb.y, 0.026), 0.012, 0.015, color = C_FRAME),
                Key(sv(bb.z - 0.13, bb.y + 0.014, 0.05), 0.0095, 0.013, color = C_FRAME),
                Key(sv(rear.z + 0.09, rear.y - 0.012, 0.064), 0.008, 0.011, color = C_FRAME),
                Key(sv(rear.z, rear.y, Frame.DROPOUT_X), 0.008, 0.011, color = C_FRAME),
            ),
            3, 12,
        )
        val stay = bb + Frame.seatDir * 0.41
        addTube(
            listOf(
                Key(sv(stay.z + 0.004, stay.y, 0.012), 0.0085, color = C_FRAME),
                Key(sv(stay.z - 0.09, stay.y - 0.085, 0.046), 0.0075, color = C_FRAME),
                Key(sv(rear.z + 0.012, rear.y + 0.03, 0.064), 0.007, color = C_FRAME),
                Key(sv(rear.z, rear.y, Frame.DROPOUT_X), 0.007, color = C_FRAME),
            ),
            3, 10,
        )
        // Gabelscheide
        val top = Frame.crown + Frame.steer * 0.012
        addTube(
            listOf(
                Key(sv(top.z, top.y, 0.0), 0.024, 0.027, color = C_FRAME),
                Key(sv(Frame.crown.z + 0.002, Frame.crown.y - 0.022, 0.038), 0.02, 0.024, color = C_FRAME),
                Key(sv(lerp(Frame.crown.z, front.z, 0.3), lerp(Frame.crown.y, front.y, 0.3), 0.055), 0.015, 0.02, color = C_FRAME),
                Key(sv(lerp(Frame.crown.z, front.z, 0.7), lerp(Frame.crown.y, front.y, 0.7), 0.059), 0.011, 0.016, color = C_FRAME),
                Key(sv(front.z, front.y, 0.06), 0.009, 0.012, color = C_FRAME),
            ),
            3, 14,
        )
        // Ausfallenden und Steckachsen-Enden
        addCylinder(Vec3(Frame.HUB_HALF, rear.y, rear.z), Vec3(Frame.DROPOUT_X + 0.01, rear.y, rear.z), 0.011, 0.009, 12, color = C_BLACK)
        addCylinder(Vec3(Frame.HUB_HALF, front.y, front.z), Vec3(0.07, front.y, front.z), 0.011, 0.009, 12, color = C_BLACK)
    }

    // Steuersatz, Vorbau
    f.addLoft(listOf(Frame.headTop, Frame.stemBase - Frame.steer * 0.019), doubleArrayOf(0.0235, 0.019), 16, false, false, C_BLACK)
    f.addLoft(listOf(Frame.stemBase - Frame.steer * 0.02, Frame.stemBase + Frame.steer * 0.021), doubleArrayOf(0.0205, 0.0205), 16, true, true, C_BLACK)
    f.addTube(
        listOf(
            Key(Frame.stemBase, 0.017, 0.018, power = 3.0, color = C_BLACK),
            Key(Frame.stemBase.lerp(Frame.barCenter, 0.5), 0.0165, 0.016, power = 3.0, color = C_BLACK),
            Key(Frame.barCenter, 0.02, 0.019, power = 3.0, color = C_BLACK),
        ),
        2, 14,
    )
    f.addCylinder(Vec3(-0.022, Frame.barCenter.y, Frame.barCenter.z), Vec3(0.022, Frame.barCenter.y, Frame.barCenter.z), 0.0195, 0.0195, 14, color = C_BLACK)

    // Lenker mit Rennbuegel
    val rub = p.mesh(node, RiderMat.RUBBER)
    val half = smoothPath(handlebarHalf(), 3)
    val bar = half.reversed().map { Vec3(-it.x, it.y, it.z) } + half.drop(1)
    rub.addLoft(
        bar, DoubleArray(bar.size) { if (abs(bar[it].x) > 0.055) 0.0148 else 0.0128 }, 10, true, true,
        ringColors = IntArray(bar.size) { if (abs(bar[it].x) > 0.055) C_BLACK else C_DARK },
    )
    // Bremsgriffe
    val by = Frame.barCenter.y
    val bz = Frame.barCenter.z
    p.both(node, RiderMat.RUBBER) {
        val rings = buildRings(
            listOf(
                Key(sv(bz + 0.058, by + 0.002, HOOD_X), 0.0175, 0.016, power = 2.6, color = C_BLACK),
                Key(sv(bz + 0.085, by + 0.012, HOOD_X), 0.017, 0.018, rvNeg = 0.02, power = 2.6, color = C_BLACK),
                Key(sv(bz + 0.115, by + 0.018, HOOD_X), 0.0165, 0.018, rvNeg = 0.022, power = 2.6, color = C_BLACK),
                Key(sv(bz + 0.142, by + 0.03, HOOD_X), 0.0155, 0.02, rvNeg = 0.02, power = 2.6, color = C_BLACK),
                Key(sv(bz + 0.156, by + 0.05, HOOD_X), 0.0135, 0.016, power = 2.6, color = C_BLACK),
                Key(sv(bz + 0.158, by + 0.066, HOOD_X), 0.0105, 0.011, power = 2.6, color = C_BLACK),
            ),
            3,
        )
        addSkin(withDome(rings, false, 0.008, 2), 12)
    }
    p.both(node, RiderMat.BIKE) {
        addTube(
            listOf(
                Key(sv(bz + 0.15, by + 0.012, HOOD_X), 0.007, 0.008, color = C_DARK),
                Key(sv(bz + 0.156, by - 0.035, HOOD_X + 0.002), 0.0065, 0.009, color = C_DARK),
                Key(sv(bz + 0.148, by - 0.085, HOOD_X + 0.005), 0.006, 0.008, color = C_DARK),
                Key(sv(bz + 0.128, by - 0.118, HOOD_X + 0.008), 0.0055, 0.007, color = C_DARK),
            ),
            3, 8,
        )
    }

    // Sattel
    val saddle = buildRings(
        listOf(
            Key(sv(-0.425, 0.970), 0.05, 0.007, rvNeg = 0.01, power = 2.6, color = C_BLACK),
            Key(sv(-0.403, 0.968), 0.068, 0.012, rvNeg = 0.018, power = 2.6, color = C_BLACK),
            Key(sv(-0.353, 0.966), 0.072, 0.014, rvNeg = 0.022, power = 2.6, color = C_BLACK),
            Key(sv(-0.29, 0.964), 0.052, 0.015, rvNeg = 0.022, power = 2.6, color = C_BLACK),
            Key(sv(-0.232, 0.964), 0.03, 0.015, rvNeg = 0.02, power = 2.6, color = C_BLACK),
            Key(sv(-0.178, 0.964), 0.022, 0.014, rvNeg = 0.018, power = 2.6, color = C_BLACK),
            Key(sv(-0.153, 0.963), 0.017, 0.011, rvNeg = 0.013, power = 2.6, color = C_BLACK),
        ),
        3,
    )
    rub.addSkin(withDome(withDome(saddle, true, 0.006, 2), false, 0.01, 2), 16)

    // Bremssaettel
    f.addEllipsoid(0.045, front.y + 0.062, front.z - 0.058, 0.014, 0.024, 0.02, 10, 6, C_BLACK)
    f.addEllipsoid(0.045, rear.y + 0.05, rear.z + 0.07, 0.014, 0.018, 0.026, 10, 6, C_BLACK)

    buildDrivetrain(p, ghost)
    buildBottle(p)
}

private const val HOOD_X = 0.205

/** Linke Haelfte des Lenkers von der Mitte bis zum Buegelende. */
private fun handlebarHalf(): List<Vec3> {
    val by = Frame.barCenter.y
    val bz = Frame.barCenter.z
    return listOf(
        sv(bz, by, 0.0),
        sv(bz, by, 0.07),
        sv(bz, by, 0.135),
        sv(bz + 0.012, by, 0.178),
        sv(bz + 0.042, by - 0.002, 0.199),
        sv(bz + 0.074, by - 0.006, 0.204),
        sv(bz + 0.094, by - 0.028, 0.207),
        sv(bz + 0.092, by - 0.068, 0.212),
        sv(bz + 0.066, by - 0.104, 0.218),
        sv(bz + 0.022, by - 0.124, 0.223),
        sv(bz - 0.035, by - 0.13, 0.227),
    )
}

/** Punkte eines Kreisbogens in der Seitenansicht um [c]; Winkel in Grad, 0 = nach vorn, 90 = nach oben. */
private fun arc(c: Vec3, r: Double, fromDeg: Double, toDeg: Double, stepDeg: Double, x: Double): List<Vec3> {
    val n = maxOf(1, Math.round(abs(toDeg - fromDeg) / stepDeg).toInt())
    return (0..n).map {
        val a = Math.toRadians(lerp(fromDeg, toDeg, it.toDouble() / n))
        Vec3(x, c.y + r * sin(a), c.z + r * cos(a))
    }
}

private fun buildDrivetrain(p: Parts, ghost: Boolean) {
    val f = p.mesh(RiderNodes.BIKE, RiderMat.BIKE)
    val bb = RiderRig.BB
    val rear = RiderRig.REAR_AXLE
    val x = Frame.CHAIN_X
    val guide = Vec3(x, rear.y - 0.082, rear.z + 0.012)
    val tension = Vec3(x, rear.y - 0.165, rear.z + 0.04)
    val pulleyR = 0.021

    // Schaltwerk: Koerper, Kaefig, Roellchen
    f.addTube(
        listOf(
            Key(Vec3(x - 0.02, rear.y - 0.012, rear.z - 0.004), 0.008, 0.012, power = 3.0, color = C_BLACK),
            Key(Vec3(x - 0.024, rear.y - 0.045, rear.z + 0.002), 0.011, 0.019, power = 3.0, color = C_BLACK),
            Key(Vec3(x - 0.018, guide.y + 0.004, guide.z), 0.009, 0.014, power = 3.0, color = C_BLACK),
        ),
        2, 10,
    )
    f.addTube(
        listOf(
            Key(Vec3(x - 0.009, guide.y + 0.012, guide.z - 0.004), 0.0025, 0.012, color = C_DARK),
            Key(Vec3(x - 0.009, tension.y - 0.012, tension.z + 0.004), 0.0025, 0.012, color = C_DARK),
        ),
        1, 8,
    )
    for (c in listOf(guide, tension)) {
        f.addCylinder(Vec3(x - 0.004, c.y, c.z), Vec3(x + 0.004, c.y, c.z), pulleyR - 0.004, pulleyR - 0.004, 14, color = C_BLACK)
    }
    if (ghost) return

    // Kette: Kettenblatt vorn herum, unten zurueck, S-foermig durch das Schaltwerk, ueber das Ritzel.
    val dz = rear.z - bb.z
    val dy = rear.y - bb.y
    // Beruehrwinkel des oberen Trums (gemeinsame aeussere Tangente beider Kreise).
    val top = Math.toDegrees(atan2(dy, dz) + asin((Frame.CHAINRING_R - Frame.COG_R) / sqrt(dz * dz + dy * dy))) - 90.0
    val ringC = Vec3(x, bb.y, bb.z)
    val cogC = Vec3(x, rear.y, rear.z)
    val path = ArrayList<Vec3>()
    path += arc(ringC, Frame.CHAINRING_R, -86.0, top, 12.0, x)
    path += arc(cogC, Frame.COG_R, top, 252.0, 14.0, x)
    path += arc(guide, pulleyR, 62.0, -70.0, 22.0, x)
    path += arc(tension, pulleyR, 118.0, 268.0, 25.0, x)
    path += path.first()
    f.addSkin(buildRings(path.map { Key(it, 0.0033, 0.0042, power = 3.0, color = C_CHAIN) }), 6, false, false)
}

private fun buildBottle(p: Parts) {
    val bb = RiderRig.BB
    val dtHead = Frame.headBottom + Frame.steer * 0.042
    val dir = (dtHead - bb).normalized()
    val up = Vec3.X cross dir // in das Rahmendreieck hinein
    val inward = if (up.y > 0) up else -up
    val base = bb + dir * 0.17 + inward * 0.071
    val gear = p.mesh(RiderNodes.BIKE, RiderMat.GEAR)
    val along = doubleArrayOf(0.0, 0.006, 0.135, 0.152, 0.162, 0.176, 0.181, 0.196)
    val radii = doubleArrayOf(0.031, 0.0365, 0.0365, 0.031, 0.022, 0.022, 0.016, 0.014)
    val cols = IntArray(along.size) { if (it >= 4) C_DARK else C_BOTTLE }
    gear.addLoft(along.map { base + dir * it }, radii, 18, true, true, ringColors = cols)
    // Flaschenhalter: zwei Buegel
    p.both(RiderNodes.BIKE, RiderMat.BIKE) {
        val side = Vec3.X * 0.03
        val low = base - inward * 0.036
        addTube(
            listOf(
                Key(low + dir * 0.0, 0.003, color = C_BLACK),
                Key(low + dir * 0.02 + side * 0.9 + inward * 0.012, 0.003, color = C_BLACK),
                Key(low + dir * 0.11 + side + inward * 0.02, 0.003, color = C_BLACK),
                Key(low + dir * 0.125 + side * 0.6 + inward * 0.05, 0.003, color = C_BLACK),
            ),
            3, 6,
        )
    }
}

private fun buildWheel(p: Parts, node: String, rear: Boolean, ghost: Boolean) {
    val r = RiderRig.WHEEL_R
    val tireR = 0.02
    val rub = p.mesh(node, RiderMat.RUBBER)
    // Reifen: Lauffläche dunkel, Flanken hell (Gravel-Reifen)
    val tire = (0 until 12).map {
        val a = 2 * PI * it / 12
        ProfilePoint(tireR * cos(a), r - tireR + tireR * sin(a), if (sin(a) > 0.3) C_TREAD else C_SIDEWALL)
    }
    rub.addRevolveX(tire, 48, closed = true)

    val b = p.mesh(node, RiderMat.BIKE)
    val rim = listOf(
        ProfilePoint(0.0125, 0.3125), ProfilePoint(-0.0125, 0.3125), ProfilePoint(-0.0135, 0.302), ProfilePoint(-0.0095, 0.287),
        ProfilePoint(0.0, 0.2825), ProfilePoint(0.0095, 0.287), ProfilePoint(0.0135, 0.302),
    )
    // Zwei helle Felder auf den Felgenflanken machen die Drehung sichtbar.
    b.addRevolveX(rim, 48, closed = true) { pt, phi ->
        val onFlank = pt.r in 0.286..0.303
        val deg = Math.toDegrees(phi)
        if (onFlank && (deg in 20.0..50.0 || deg in 200.0..230.0)) C_METAL else C_RIM
    }
    val hub = listOf(
        ProfilePoint(0.05, 0.0), ProfilePoint(0.05, 0.011), ProfilePoint(0.04, 0.012), ProfilePoint(0.035, 0.024), ProfilePoint(0.031, 0.024),
        ProfilePoint(0.026, 0.015), ProfilePoint(-0.026, 0.015), ProfilePoint(-0.031, 0.024), ProfilePoint(-0.035, 0.024), ProfilePoint(-0.04, 0.012),
        ProfilePoint(-0.05, 0.011), ProfilePoint(-0.05, 0.0),
    ).map { ProfilePoint(it.x, it.r, C_BLACK) }
    b.addRevolveX(hub, 12)
    // Bremsscheibe links
    b.addRevolveX(
        listOf(ProfilePoint(0.0405, 0.03), ProfilePoint(0.0405, 0.08), ProfilePoint(0.0385, 0.08), ProfilePoint(0.0385, 0.03)).map { ProfilePoint(it.x, it.r, C_METAL) },
        20, closed = true, hard = true,
    )
    if (rear) {
        val steps = doubleArrayOf(0.076, 0.063, 0.05, 0.037, 0.024)
        val cassette = ArrayList<ProfilePoint>()
        cassette += ProfilePoint(-0.0345, 0.013, C_METAL)
        for ((i, radius) in steps.withIndex()) {
            cassette += ProfilePoint(-0.0345 - 0.0056 * i, radius, C_METAL)
            cassette += ProfilePoint(-0.0345 - 0.0056 * (i + 1), radius, C_METAL)
        }
        cassette += ProfilePoint(-0.0345 - 0.0056 * steps.size, 0.013, C_METAL)
        b.addRevolveX(cassette, 20, hard = true)
    }
    if (ghost) return
    // Speichen: dunkel und etwas kraeftiger als echt, damit sie aus der Verfolgerkamera nicht flimmern.
    val count = 20
    for (i in 0 until count) {
        val rimA = 2 * PI * (i + 0.5) / count
        val hubA = rimA + (if ((i / 2) % 2 == 0) 0.62 else -0.62)
        val side = if (i % 2 == 0) 0.033 else -0.033
        b.addCylinder(
            Vec3(side, 0.022 * cos(hubA), 0.022 * sin(hubA)), Vec3(0.0, 0.285 * cos(rimA), 0.285 * sin(rimA)),
            0.0013, 0.0013, 4, capStart = false, capEnd = false, color = C_DARK,
        )
    }
}

private fun buildCrank(p: Parts) {
    val c = p.mesh(RiderNodes.CRANK, RiderMat.BIKE)
    val len = RiderRig.CRANK
    c.addCylinder(Vec3(-0.07, 0.0, 0.0), Vec3(0.07, 0.0, 0.0), 0.0125, 0.0125, 12, color = C_BLACK)
    // Kurbelarme: links nach oben (+Y), rechts nach unten
    for (side in listOf(1.0, -1.0)) {
        val x = side * 0.073
        val arm = buildRings(
            listOf(
                Key(Vec3(x, 0.0, 0.0), 0.0085, 0.021, power = 3.0, color = C_BLACK),
                Key(Vec3(x + side * 0.004, side * len * 0.5, 0.0), 0.0075, 0.016, power = 3.0, color = C_BLACK),
                Key(Vec3(x + side * 0.008, side * len, 0.0), 0.007, 0.014, power = 3.0, color = C_BLACK),
            ),
            2,
        )
        c.addSkin(withDome(withDome(arm, true, 0.02, 2), false, 0.013, 2), 10)
        // Pedalachse
        c.addCylinder(Vec3(x, side * len, 0.0), Vec3(side * (RiderRig.PEDAL_X + 0.03), side * len, 0.0), 0.0065, 0.0065, 8, color = C_METAL)
    }
    // Kettenblatt mit Stern (Antriebsseite −X)
    val x0 = Frame.CHAIN_X + 0.0016
    val x1 = Frame.CHAIN_X - 0.0016
    val ro = Frame.CHAINRING_R - 0.002
    c.addRevolveX(
        listOf(ProfilePoint(x0, 0.064), ProfilePoint(x0, ro), ProfilePoint(x1, ro), ProfilePoint(x1, 0.064)).map { ProfilePoint(it.x, it.r, C_DARK) },
        48, closed = true, hard = true,
    )
    for (k in 0 until 4) {
        val a = PI / 4 + k * PI / 2
        c.addTube(
            listOf(
                Key(Vec3(Frame.CHAIN_X - 0.006, 0.0, 0.0), 0.004, 0.014, power = 3.0, color = C_BLACK),
                Key(Vec3(Frame.CHAIN_X - 0.002, 0.068 * cos(a), 0.068 * sin(a)), 0.003, 0.009, power = 3.0, color = C_BLACK),
            ),
            1, 8,
        )
    }
    for (node in listOf(RiderNodes.PEDAL_L, RiderNodes.PEDAL_R)) {
        p.mesh(node, RiderMat.BIKE).addBox(-0.032, -0.011, -0.03, 0.032, 0.008, 0.042, C_DARK)
    }
}

// ---------------------------------------------------------------------------
// Fahrer
// ---------------------------------------------------------------------------

/** Schneidet [rings] bei der Stuetzstelle [s]: das Stueck davor (einschliesslich des ersten Rings dahinter) und das danach. */
private fun cut(rings: List<Ring>, s: Double): Pair<List<Ring>, List<Ring>> {
    val i = rings.indexOfFirst { it.s >= s }.coerceIn(1, rings.size - 2)
    return rings.subList(0, i + 1) to rings.subList(i - 1, rings.size)
}

/** Stofflage: der Koerper darunter, leicht aufgedickt. */
private fun cloth(rings: List<Ring>, k: Double = 1.035): List<Ring> = rings.map { it.scaled(it.c, k) }

private fun buildLeg(p: Parts, left: Boolean, ghost: Boolean) {
    val thighNode = if (left) RiderNodes.THIGH_L else RiderNodes.THIGH_R
    val shankNode = if (left) RiderNodes.SHANK_L else RiderNodes.SHANK_R
    val footNode = if (left) RiderNodes.FOOT_L else RiderNodes.FOOT_R
    val m = if (left) null else MIRROR_X
    val seg = 20

    // Oberschenkel: Y nach oben zur Huefte, Z nach vorn (Kniescheibe), +X aussen (links)
    val thigh = buildRings(
        listOf(
            Key(Vec3(0.0, 0.0, 0.0), 0.086, 0.09, ruNeg = 0.07, rvNeg = 0.094),
            Key(Vec3(0.002, -0.08, 0.004), 0.086, 0.093, ruNeg = 0.07, rvNeg = 0.09),
            Key(Vec3(0.002, -0.18, 0.007), 0.079, 0.09, ruNeg = 0.068, rvNeg = 0.08),
            Key(Vec3(0.0, -0.28, 0.005), 0.067, 0.078, ruNeg = 0.062, rvNeg = 0.068),
            Key(Vec3(0.0, -0.36, 0.002), 0.058, 0.064, rvNeg = 0.058),
            Key(Vec3(0.0, -0.41, 0.0), 0.054, 0.058, rvNeg = 0.054),
            Key(Vec3(0.0, -RiderRig.THIGH, 0.0), 0.052, 0.055, rvNeg = 0.053),
        ),
        2,
    )
    if (ghost) {
        // Ein Stueck je Glied, Kugelende nur am Knie des Oberschenkels: so liegt moeglichst wenig doppelt.
        val whole = MeshBuilder()
        whole.addSkin(withDome(thigh.filter { it.s >= 0.3 }, false, 0.05), seg, capStart = false)
        p.mesh(thighNode, RiderMat.KIT).append(whole, m)
    }
    val (thighCloth, thighSkin) = cut(thigh, 3.3)
    if (!ghost) {
    val kit = MeshBuilder()
    kit.addSkin(withDome(cloth(thighCloth), true, 0.08), seg, capEnd = false) { r, _ -> if (r.s > 3.0) C_BIBS_BAND else C_BIBS }
    p.mesh(thighNode, RiderMat.KIT).append(kit, m)
    val skin = MeshBuilder()
    skin.addSkin(withDome(thighSkin, false, 0.05).map { recolor(it, C_SKIN) }, seg, capStart = false)
    p.mesh(thighNode, RiderMat.SKIN).append(skin, m)
    }

    // Unterschenkel: Wade mit Muskelbauch nach hinten
    val shank = buildRings(
        listOf(
            Key(Vec3(0.0, 0.0, 0.0), 0.052, 0.054, rvNeg = 0.054),
            Key(Vec3(0.0, -0.05, -0.002), 0.053, 0.05, rvNeg = 0.058),
            Key(Vec3(0.0, -0.12, -0.006), 0.055, 0.046, rvNeg = 0.07),
            Key(Vec3(0.0, -0.19, -0.006), 0.05, 0.042, rvNeg = 0.063),
            Key(Vec3(0.0, -0.27, -0.003), 0.04, 0.036, rvNeg = 0.046),
            Key(Vec3(0.0, -0.35, 0.0), 0.032, 0.032, rvNeg = 0.038),
            Key(Vec3(0.0, -0.40, 0.0), 0.029, 0.031, rvNeg = 0.036),
            Key(Vec3(0.0, -RiderRig.SHANK, 0.0), 0.03, 0.033, rvNeg = 0.037),
        ),
        2,
    )
    if (ghost) {
        val whole = MeshBuilder()
        whole.addSkin(shank, seg, capStart = false, capEnd = false)
        p.mesh(shankNode, RiderMat.SKIN).append(whole, m)
    }
    val (shankSkin, shankSock) = cut(shank, 5.2)
    if (!ghost) {
    val calf = MeshBuilder()
    calf.addSkin(withDome(shankSkin, true, 0.05).map { recolor(it, C_SKIN) }, seg, capEnd = false)
    p.mesh(shankNode, RiderMat.SKIN).append(calf, m)
    val sock = MeshBuilder()
    sock.addSkin(withDome(cloth(shankSock, 1.05), false, 0.03).map { recolor(it, C_SOCK) }, seg, capStart = false)
    p.mesh(shankNode, RiderMat.KIT).append(sock, m)
    }

    // Schuh: Z zur Spitze, Y senkrecht zur Sohle
    val shoe = buildRings(
        listOf(
            Key(Vec3(0.0, -0.055, -0.062), 0.024, 0.028, rvNeg = 0.027, power = 2.5),
            Key(Vec3(0.0, -0.048, -0.03), 0.034, 0.045, rvNeg = 0.037, power = 2.5),
            Key(Vec3(0.0, -0.047, 0.0), 0.038, 0.05, rvNeg = 0.038, power = 2.5),
            Key(Vec3(0.0, -0.052, 0.04), 0.042, 0.04, rvNeg = 0.033, power = 2.5),
            Key(Vec3(0.002, -0.058, 0.09), 0.05, 0.03, ruNeg = 0.046, rvNeg = 0.027, power = 2.5),
            Key(Vec3(0.002, -0.062, 0.14), 0.052, 0.024, ruNeg = 0.047, rvNeg = 0.023, power = 2.5),
            Key(Vec3(0.0, -0.064, 0.185), 0.045, 0.02, rvNeg = 0.02, power = 2.5),
            Key(Vec3(-0.004, -0.064, 0.215), 0.03, 0.015, rvNeg = 0.016, power = 2.5),
        ),
        2,
    )
    val shoeMesh = MeshBuilder()
    shoeMesh.addSkin(withDome(withDome(shoe, true, 0.012, 3), false, 0.012, 3), 16) { r, th ->
        if (sin(th) < -0.5) C_SOLE else if (sin(th) > 0.2 && (r.s in 2.9..3.2 || r.s in 3.9..4.2)) C_DARK else C_SHOE
    }
    shoeMesh.addBox(-0.02, -0.097, 0.09, 0.02, -0.08, 0.15, C_SOLE)
    p.mesh(footNode, RiderMat.GEAR).append(shoeMesh, m)
}

private fun recolor(r: Ring, color: Int) = Ring(r.c, r.u, r.v, r.ru, r.rv, r.ruNeg, r.rvNeg, r.power, color, r.s)

/** Mitte des Kopfes und seine Neigung nach vorn. */
private val HEAD_CENTER = sv(0.232, 1.592)
private val HEAD_PITCH = Math.toRadians(14.0)

private fun buildTorso(p: Parts) {
    val node = RiderNodes.BODY
    val keys = listOf(
        Key(sv(-0.325, 0.992), 0.085, 0.05, rvNeg = 0.045, power = 2.2),
        Key(sv(-0.328, 1.025), 0.15, 0.09, rvNeg = 0.08, power = 2.2),
        Key(sv(-0.308, 1.075), 0.166, 0.106, rvNeg = 0.10, power = 2.2),
        Key(sv(-0.268, 1.14), 0.16, 0.102, rvNeg = 0.105, power = 2.3),
        Key(sv(-0.21, 1.205), 0.152, 0.10, rvNeg = 0.105, power = 2.3),
        Key(sv(-0.14, 1.265), 0.153, 0.10, rvNeg = 0.11, power = 2.4),
        Key(sv(-0.065, 1.325), 0.166, 0.104, rvNeg = 0.115, power = 2.4),
        Key(sv(0.005, 1.375), 0.18, 0.104, rvNeg = 0.112, power = 2.4),
        Key(sv(0.065, 1.413), 0.186, 0.095, rvNeg = 0.10, power = 2.3),
        Key(sv(0.115, 1.445), 0.15, 0.08, rvNeg = 0.085, power = 2.2),
        Key(sv(0.15, 1.47), 0.09, 0.066, rvNeg = 0.062, power = 2.0),
        Key(sv(0.172, 1.492), 0.064, 0.061, rvNeg = 0.057, power = 2.0),
    )
    val rings = withDome(buildRings(keys, 3), true, 0.02, 3)
    p.mesh(node, RiderMat.KIT).addSkin(rings, 26, capEnd = false) { r, th ->
        val side = abs(cos(th))
        when {
            r.s < 3.25 -> C_BIBS
            r.s < 3.5 -> C_JERSEY_DARK
            r.s > 10.5 -> C_JERSEY_DARK
            side > 0.84 && r.s < 8.2 -> C_JERSEY_DARK
            r.s in 6.75..7.5 -> C_JERSEY_LIGHT
            else -> C_JERSEY
        }
    }
    // Hals
    p.mesh(node, RiderMat.SKIN).addTube(
        listOf(
            Key(sv(0.14, 1.455), 0.06, 0.06, color = C_SKIN),
            Key(sv(0.175, 1.498), 0.056, 0.059, rvNeg = 0.054, color = C_SKIN),
            Key(sv(0.2, 1.535), 0.054, 0.058, rvNeg = 0.052, color = C_SKIN),
            Key(sv(0.218, 1.57), 0.055, 0.06, rvNeg = 0.05, color = C_SKIN),
        ),
        3, 20,
    )
}

private fun buildArms(p: Parts, ghost: Boolean) {
    val node = RiderNodes.BODY
    val by = Frame.barCenter.y
    val bz = Frame.barCenter.z
    val shoulder = Vec3(0.178, 1.412, 0.08)
    val wrist = Vec3(0.203, by + 0.07, bz + 0.03)
    val upper = 0.31
    val fore = 0.255
    val d = wrist - shoulder
    val dist = d.length()
    val dn = d * (1 / dist)
    val poleHint = Vec3(0.45, -0.6, -0.6)
    val pole = (poleHint - dn * (poleHint dot dn)).normalized()
    val a = (upper * upper - fore * fore + dist * dist) / (2 * dist)
    val elbow = shoulder + dn * a + pole * sqrt((upper * upper - a * a).coerceAtLeast(0.0))

    val arm = buildRings(
        listOf(
            Key(shoulder + Vec3(-0.035, 0.004, -0.012), 0.05, 0.052),
            Key(shoulder, 0.056, 0.058),
            Key(shoulder.lerp(elbow, 0.3), 0.048, 0.052),
            Key(shoulder.lerp(elbow, 0.6), 0.043, 0.047),
            Key(shoulder.lerp(elbow, 0.9), 0.037, 0.039),
            Key(elbow, 0.036, 0.037),
            Key(elbow.lerp(wrist, 0.22), 0.04, 0.041),
            Key(elbow.lerp(wrist, 0.5), 0.036, 0.036),
            Key(elbow.lerp(wrist, 0.85), 0.029, 0.024),
            Key(wrist, 0.028, 0.02),
        ),
        2,
    )
    val (sleeve, rest) = cut(arm, 2.6)
    val (bare, cuff) = cut(rest, 8.3)
    p.both(node, RiderMat.KIT) {
        if (ghost) {
            addSkin(arm.filter { it.s >= 0.5 }, 14)
        } else {
            addSkin(cloth(sleeve, 1.05), 14, capEnd = false) { r, _ -> if (r.s > 2.35) C_JERSEY_DARK else C_JERSEY }
            addSkin(cloth(cuff, 1.07).map { recolor(it, C_GLOVE) }, 14, capStart = false)
        }
        // Handflaeche auf dem Griffkoerper
        val palm = buildRings(
            listOf(
                Key(wrist, 0.031, 0.019, power = 2.6, color = C_GLOVE),
                Key(Vec3(0.207, by + 0.058, bz + 0.07), 0.04, 0.016, power = 2.6, color = C_GLOVE),
                Key(Vec3(0.212, by + 0.05, bz + 0.112), 0.042, 0.014, power = 2.6, color = C_GLOVE),
                Key(Vec3(0.215, by + 0.045, bz + 0.132), 0.038, 0.011, power = 2.6, color = C_GLOVE),
            ),
            3,
        )
        addSkin(withDome(palm, false, 0.008, 2), 14)
        // Finger: um die Aussenseite des Griffs nach unten gelegt
        val z = bz + 0.106
        val fingers = buildRings(
            listOf(
                Key(Vec3(0.228, by + 0.049, z), 0.036, 0.009, power = 3.0, color = C_GLOVE),
                Key(Vec3(0.239, by + 0.034, z), 0.036, 0.009, power = 3.0, color = C_GLOVE),
                Key(Vec3(0.242, by + 0.012, z), 0.036, 0.009, power = 3.0, color = C_GLOVE),
                Key(Vec3(0.235, by - 0.008, z), 0.035, 0.009, power = 3.0, color = C_GLOVE),
                Key(Vec3(0.219, by - 0.017, z), 0.034, 0.0085, power = 3.0, color = C_GLOVE),
                Key(Vec3(0.203, by - 0.015, z), 0.033, 0.008, power = 3.0, color = C_GLOVE),
            ),
            3, Vec3.Z,
        )
        addSkin(withDome(withDome(fingers, true, 0.006, 2), false, 0.006, 2), 12)
        // Daumen innen
        addCapsule(Vec3(0.186, by + 0.052, bz + 0.072), Vec3(0.181, by + 0.03, bz + 0.126), 0.0115, 10, 3, C_GLOVE)
    }
    if (!ghost) p.both(node, RiderMat.SKIN) {
        addSkin(bare.map { recolor(it, C_SKIN) }, 14, capStart = false, capEnd = false)
    }
}

private fun buildHead(p: Parts, ghost: Boolean) {
    val place = Mat4.translation(HEAD_CENTER) * Mat4.rotationX(HEAD_PITCH)

    // Kopf: Y zum Scheitel, Z zum Gesicht; v zeigt nach hinten
    val head = MeshBuilder()
    val skull = buildRings(
        listOf(
            Key(Vec3(0.0, -0.117, 0.05), 0.022, 0.024, rvNeg = 0.02),
            Key(Vec3(0.0, -0.096, 0.032), 0.047, 0.05, rvNeg = 0.046),
            Key(Vec3(0.0, -0.066, 0.016), 0.06, 0.066, rvNeg = 0.07),
            Key(Vec3(0.0, -0.03, 0.005), 0.068, 0.086, rvNeg = 0.085),
            Key(Vec3(0.0, 0.0, 0.0), 0.073, 0.095, rvNeg = 0.088),
            Key(Vec3(0.0, 0.04, 0.0), 0.075, 0.098, rvNeg = 0.09),
            Key(Vec3(0.0, 0.08, 0.0), 0.066, 0.085, rvNeg = 0.078),
            Key(Vec3(0.0, 0.105, 0.0), 0.042, 0.052, rvNeg = 0.047),
        ),
        3,
    )
    // Beim Geist endet der Kopf unter dem Helmrand.
    val crown = if (ghost) skull.filter { it.s <= 5.0 } else withDome(skull, false, 0.012, 2)
    head.addSkin(withDome(crown, true, 0.008, 2), 24, capEnd = !ghost) { r, th ->
        if (sin(th) > 0.1 && r.c.y > -0.05) C_HAIR else C_SKIN
    }
    head.addEllipsoid(0.0, -0.03, 0.09, 0.013, 0.024, 0.017, 10, 6, C_SKIN)
    for (side in listOf(1.0, -1.0)) head.addEllipsoid(side * 0.073, -0.014, -0.012, 0.008, 0.026, 0.016, 8, 6, C_SKIN)
    p.mesh(RiderNodes.BODY, RiderMat.SKIN).append(head, place)

    val gear = MeshBuilder()
    // Brille
    val lens = listOf(Vec3(0.079, 0.012, -0.03), Vec3(0.078, 0.01, 0.01), Vec3(0.07, 0.006, 0.048), Vec3(0.05, 0.003, 0.078), Vec3(0.026, 0.002, 0.094), Vec3(0.0, 0.002, 0.099))
    val band = lens + lens.reversed().drop(1).map { Vec3(-it.x, it.y, it.z) }
    val bandRings = buildRings(
        band.mapIndexed { i, c ->
            val arm = i <= 1 || i >= band.size - 2
            Key(c, if (arm) 0.004 else 0.02, 0.004, power = 2.6, color = C_GLASSES)
        },
        3, Vec3.UP,
    )
    gear.addSkin(bandRings, 8)
    buildHelmet(gear, ghost)
    p.mesh(RiderNodes.BODY, RiderMat.GEAR).append(gear, place)
}

/** Helm im Rahmen des Kopfes: gestreckte Schale mit eingetieften, dunklen Lueftungsschlitzen. */
private fun buildHelmet(m: MeshBuilder, ghost: Boolean) {
    val cy = 0.03
    val cz = -0.012
    val rx = 0.101
    val ry = 0.108
    val rzFront = 0.128
    val rzBack = 0.142
    val aMin = -10
    val aMax = 200
    // Der Geist hat keine Schlitze und kommt mit dem halben Raster aus.
    val aStep = if (ghost) 10 else 5
    val bStep = if (ghost) 9 else 4
    val na = (aMax - aMin) / aStep
    val nb = 180 / bStep

    fun vent(aDeg: Int, bDeg: Int): Double {
        val ab = abs(bDeg)
        val inBand = when {
            ab <= 8 -> aDeg in 20..75 || aDeg in 100..165
            ab in 16..32 -> aDeg in 25..80 || aDeg in 105..160
            ab in 40..56 -> aDeg in 40..90 || aDeg in 115..150
            else -> false
        }
        if (!inBand) return 0.0
        // Rand des Schlitzes: halb eingetieft, so entsteht eine Fase.
        val bEdge = ab == 8 || ab == 16 || ab == 32 || ab == 40 || ab == 56
        return if (bEdge) 0.0 else 1.0
    }

    val first = m.vertexCount
    val firstTri = m.triangleCount
    for (ia in 0..na) {
        val aDeg = aMin + ia * aStep
        val a = Math.toRadians(aDeg.toDouble())
        for (ib in 0..nb) {
            val bDeg = -90 + ib * bStep
            val b = Math.toRadians(bDeg.toDouble())
            val v = if (ghost) 0.0 else vent(aDeg, bDeg)
            val dx = sin(b)
            val dy = cos(b) * sin(a)
            val dz = cos(b) * cos(a)
            // Hinten laeuft die Schale leicht spitz aus, vorn ist sie stumpfer.
            val k = 1.0 - 0.085 * v
            val rz = if (dz >= 0) rzFront else rzBack
            m.vertex(dx * rx * k, cy + dy * ry * k, cz + dz * rz * k, 0.0, 1.0, 0.0, ib.toDouble() / nb, ia.toDouble() / na, if (v > 0) C_VENT else C_HELMET)
        }
    }
    val row = nb + 1
    for (ia in 0 until na) {
        for (ib in 0 until nb) {
            val a = first + ia * row + ib
            m.quad(a, a + 1, a + row + 1, a + row)
        }
    }
    // Unterseite: vom Rand nach innen zum Kopf
    if (ghost) {
        m.computeSmoothNormals(firstTri, m.triangleCount, byPosition = true)
        return
    }
    val inner = m.vertexCount
    for (edge in listOf(0, na)) {
        for (ib in 0..nb) {
            val i = first + edge * row + ib
            m.vertex(m.x(i) * 0.8, cy + (m.y(i) - cy) * 0.8 + 0.004, cz + (m.z(i) - cz) * 0.8, 0.0, -1.0, 0.0, 0.0, 0.0, C_VENT)
        }
    }
    for (ib in 0 until nb) {
        val p0 = first + ib
        val q0 = inner + ib
        m.quad(p0, q0, q0 + 1, p0 + 1)
        val p1 = first + na * row + ib
        val q1 = inner + row + ib
        m.quad(p1, p1 + 1, q1 + 1, q1)
    }
    m.computeSmoothNormals(firstTri, m.triangleCount, byPosition = true)
}

// ---------------------------------------------------------------------------
// Szene und Animationen
// ---------------------------------------------------------------------------

/**
 * Ein Knoten: [translation] und [rotation] sind die Ruhelage (Phase 0 — auch ohne
 * angewandte Animation sitzt der Fahrer richtig), [origin] der Punkt, um den die
 * im Modellraum gebauten Netze des Knotens verschoben werden.
 */
private class NodeSpec(val name: String, val parent: String?, val translation: Vec3?, val origin: Vec3 = Vec3.ZERO, val rotation: Quat? = null)

private fun pedalRest(left: Boolean) = Quat.fromAxisAngle(Vec3.X, RiderRig.footPitch(RiderRig.crankAngle(0.0, left)))

private fun nodeSpecs(): List<NodeSpec> {
    val l = RiderRig.legPose(0.0, true)
    val r = RiderRig.legPose(0.0, false)
    val down = Vec3(0.0, -RiderRig.THIGH, 0.0)
    val downShank = Vec3(0.0, -RiderRig.SHANK, 0.0)
    return listOf(
        NodeSpec(RiderNodes.ROOT, null, null),
        NodeSpec(RiderNodes.BIKE, RiderNodes.ROOT, null),
        NodeSpec(RiderNodes.WHEEL_FRONT, RiderNodes.ROOT, RiderRig.FRONT_AXLE),
        NodeSpec(RiderNodes.WHEEL_REAR, RiderNodes.ROOT, RiderRig.REAR_AXLE),
        NodeSpec(RiderNodes.CRANK, RiderNodes.ROOT, RiderRig.BB),
        NodeSpec(RiderNodes.PEDAL_L, RiderNodes.CRANK, Vec3(RiderRig.PEDAL_X, RiderRig.CRANK, 0.0), rotation = pedalRest(true)),
        NodeSpec(RiderNodes.PEDAL_R, RiderNodes.CRANK, Vec3(-RiderRig.PEDAL_X, -RiderRig.CRANK, 0.0), rotation = pedalRest(false)),
        NodeSpec(RiderNodes.BODY, RiderNodes.ROOT, RiderRig.BODY_PIVOT, RiderRig.BODY_PIVOT),
        NodeSpec(RiderNodes.THIGH_L, RiderNodes.ROOT, l.hip, rotation = l.thigh),
        NodeSpec(RiderNodes.SHANK_L, RiderNodes.THIGH_L, down, rotation = l.shank),
        NodeSpec(RiderNodes.FOOT_L, RiderNodes.SHANK_L, downShank, rotation = l.foot),
        NodeSpec(RiderNodes.THIGH_R, RiderNodes.ROOT, r.hip, rotation = r.thigh),
        NodeSpec(RiderNodes.SHANK_R, RiderNodes.THIGH_R, down, rotation = r.shank),
        NodeSpec(RiderNodes.FOOT_R, RiderNodes.SHANK_R, downShank, rotation = r.foot),
    )
}

private fun buildParts(ghost: Boolean): Parts {
    val p = Parts()
    buildFrame(p, ghost)
    buildWheel(p, RiderNodes.WHEEL_FRONT, false, ghost)
    buildWheel(p, RiderNodes.WHEEL_REAR, true, ghost)
    buildCrank(p)
    buildTorso(p)
    buildArms(p, ghost)
    buildHead(p, ghost)
    buildLeg(p, true, ghost)
    buildLeg(p, false, ghost)
    return p
}

/** Kopie ohne Vertexfarben (fuer den Geist). */
private fun MeshBuilder.appendPlain(src: MeshBuilder, shift: Vec3) {
    val base = vertexCount
    ensureVertexCapacity(base + src.vertexCount)
    for (i in 0 until src.vertexCount) {
        vertex(
            src.x(i) - shift.x, src.y(i) - shift.y, src.z(i) - shift.z,
            src.normals[i * 3].toDouble(), src.normals[i * 3 + 1].toDouble(), src.normals[i * 3 + 2].toDouble(),
            src.uvs[i * 2].toDouble(), src.uvs[i * 2 + 1].toDouble(),
        )
    }
    for (k in 0 until src.triangleCount) tri(base + src.indices[k * 3], base + src.indices[k * 3 + 1], base + src.indices[k * 3 + 2])
}

private fun rotationKeys(count: Int, at: (k: Int) -> Quat): FloatArray {
    val out = FloatArray((count + 1) * 4)
    var prev: Quat? = null
    for (k in 0..count) {
        var q = at(k).normalized()
        val before = prev
        // Kuerzester Weg: Vorzeichen so, dass aufeinanderfolgende Stuetzstellen nie gegenlaeufig sind.
        if (before != null && before.x * q.x + before.y * q.y + before.z * q.z + before.w * q.w < 0) q = Quat(-q.x, -q.y, -q.z, -q.w)
        prev = q
        out[k * 4] = q.x.toFloat(); out[k * 4 + 1] = q.y.toFloat(); out[k * 4 + 2] = q.z.toFloat(); out[k * 4 + 3] = q.w.toFloat()
    }
    return out
}

private fun buildModel(ghost: Boolean): ByteArray {
    val parts = buildParts(ghost)
    val scene = GlbScene()
    val materials = HashMap<RiderMat, Int>()
    val ghostMaterial = if (ghost) {
        scene.addMaterial(GlbMaterial(GHOST_MATERIAL, floatArrayOf(0.7f, 0.86f, 1f, 0.4f), roughness = 0.8f, alphaMode = GlbAlphaMode.BLEND, emissive = floatArrayOf(0.2f, 0.27f, 0.34f)))
    } else {
        for (m in RiderMat.entries) materials[m] = scene.addMaterial(GlbMaterial(m.matName, roughness = m.roughness))
        -1
    }
    val index = HashMap<String, Int>()
    for (spec in nodeSpecs()) {
        val node = scene.addNode(spec.name, spec.parent?.let { index.getValue(it) }, spec.translation, spec.rotation)
        index[spec.name] = node
        val meshes = parts.of(spec.name)
        if (ghost) {
            val all = MeshBuilder(4096, 8192)
            for (mesh in meshes.values) all.appendPlain(mesh, spec.origin)
            scene.addMesh(node, all, ghostMaterial)
        } else {
            for ((mat, mesh) in meshes) {
                if (spec.origin != Vec3.ZERO) mesh.translate(-spec.origin.x, -spec.origin.y, -spec.origin.z)
                scene.addMesh(node, mesh, materials.getValue(mat))
            }
        }
    }

    val n = RiderRig.KEYFRAMES
    val times = FloatArray(n + 1) { it.toFloat() / n }
    fun channel(node: String, at: (k: Int) -> Quat) = GlbChannel(index.getValue(node), GlbPath.ROTATION, times, rotationKeys(n, at))
    fun spin(k: Int) = Quat.fromAxisAngle(Vec3.X, 2 * PI * k / n)
    fun phase(k: Int) = (k % n).toDouble() / n

    val pedal = ArrayList<GlbChannel>()
    pedal += channel(RiderNodes.CRANK, ::spin)
    for (left in listOf(true, false)) {
        pedal += channel(if (left) RiderNodes.PEDAL_L else RiderNodes.PEDAL_R) { k ->
            Quat.fromAxisAngle(Vec3.X, RiderRig.footPitch(RiderRig.crankAngle(phase(k), left)) - 2 * PI * k / n)
        }
        pedal += channel(if (left) RiderNodes.THIGH_L else RiderNodes.THIGH_R) { k -> RiderRig.legPose(phase(k), left).thigh }
        pedal += channel(if (left) RiderNodes.SHANK_L else RiderNodes.SHANK_R) { k -> RiderRig.legPose(phase(k), left).shank }
        pedal += channel(if (left) RiderNodes.FOOT_L else RiderNodes.FOOT_R) { k -> RiderRig.legPose(phase(k), left).foot }
    }
    pedal += channel(RiderNodes.BODY) { k -> RiderRig.bodySway(phase(k)) }
    scene.addAnimation(WorldModels.ANIM_PEDAL, pedal)
    scene.addAnimation(WorldModels.ANIM_ROLL, listOf(channel(RiderNodes.WHEEL_FRONT, ::spin), channel(RiderNodes.WHEEL_REAR, ::spin)))
    return scene.toBytes()
}

/** Erzeugt `rider.glb` (deterministisch). */
internal fun buildRiderGlb(): ByteArray = buildModel(ghost = false)

/** Erzeugt `ghost.glb`: dasselbe Modell, einheitlich durchscheinend. */
internal fun buildGhostGlb(): ByteArray = buildModel(ghost = true)

/**
 * Schreibt [WorldModels.RIDER] und [WorldModels.GHOST] nach [dir] (wird
 * angelegt). Gleiche Eingabe, gleiche Bytes — die eingecheckten Dateien unter
 * `app/src/main/assets/indoor/models/` stammen von hier.
 */
fun writeIndoorModels(dir: File) {
    dir.mkdirs()
    File(dir, WorldModels.RIDER).writeBytes(buildRiderGlb())
    File(dir, WorldModels.GHOST).writeBytes(buildGhostGlb())
}
