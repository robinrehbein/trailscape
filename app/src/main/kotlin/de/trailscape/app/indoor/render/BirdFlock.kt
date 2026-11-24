package de.trailscape.app.indoor.render

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * # Voegel am Himmel
 *
 * Ab und zu (alle 35–110 s) quert ein kleiner Schwarm (3–7) oder ein einzelner Vogel den
 * Himmel vor dem Fahrer: 25–55 m ueber ihm, 120–200 m voraus, quer zur Fahrtrichtung mit
 * 10–13 m/s, leicht wellend, mit Flattern und Gleitphasen. Reine Logik, getestet
 * (`BirdFlockTest`); gezeichnet wird [BirdModel] (je Vogel zwei Fluegel-Knoten).
 * Ausserhalb eines Fluges ist nichts sichtbar (keine Draw Calls).
 */
internal class BirdFlock(seed: Long = 3L) {
    /** Ein Vogel: Lage (glTF-Raum der Welt), Blickrichtung (Gier, Bogenmass um +Y) und Fluegelwinkel. */
    data class Bird(val position: V3, val yawRad: Double, val flapRad: Double)

    private class Member(val offset: V3, val phase: Double, val flapHz: Double, val glideBias: Double)

    private val rnd = java.util.Random(seed)
    private var idleS = 20.0 + rnd.nextDouble() * 30.0
    private var flightS = -1.0
    private var durationS = 0.0
    private var start = V3.ZERO
    private var velocity = V3.ZERO
    private val members = ArrayList<Member>()

    val flying: Boolean get() = flightS >= 0

    /**
     * Schreibt um [dt] fort. [center]: Fahrer (Welt, glTF), [forward]: waagrechte Fahrtrichtung.
     * Liefert die Voegel des laufenden Fluges (leer, wenn keiner fliegt).
     */
    fun update(dt: Double, center: V3, forward: V3, maxBirds: Int = MAX_BIRDS): List<Bird> {
        val step = dt.coerceIn(0.0, 0.25)
        if (!flying) {
            idleS -= step
            if (idleS > 0 || maxBirds <= 0) return emptyList()
            begin(center, forward, maxBirds)
        }
        flightS += step
        if (flightS > durationS) {
            flightS = -1.0
            idleS = IDLE_MIN_S + rnd.nextDouble() * (IDLE_MAX_S - IDLE_MIN_S)
            return emptyList()
        }
        val t = flightS
        val dir = velocity.normalized()
        val yaw = atan2(dir.x, dir.z)
        return members.map { m ->
            val bob = sin(t * 0.9 + m.phase) * 0.8
            val p = start + velocity * t + m.offset + V3(0.0, bob, 0.0)
            // Flattern mit Gleitphasen: langsame Huellkurve schaltet das Schlagen ab
            val glide = sin(t * 0.55 + m.phase * 1.3) + m.glideBias > 0.9
            val flap = if (glide) 0.12 else sin((t * m.flapHz + m.phase) * 2 * PI) * 0.75
            Bird(p, yaw, flap)
        }
    }

    private fun begin(center: V3, forward: V3, maxBirds: Int) {
        val f = V3(forward.x, 0.0, forward.z).normalized().let { if (it == V3.ZERO) V3(0.0, 0.0, -1.0) else it }
        val right = V3(-f.z, 0.0, f.x) // rechts von f im glTF-Raum (Y oben)
        val side = if (rnd.nextBoolean()) 1.0 else -1.0
        val ahead = 120.0 + rnd.nextDouble() * 80.0
        val height = 25.0 + rnd.nextDouble() * 30.0
        val speed = 10.0 + rnd.nextDouble() * 3.0
        val cross = 110.0 + rnd.nextDouble() * 40.0
        start = center + f * ahead - right * (side * cross) + V3(0.0, height, 0.0)
        // Quer, leicht dem Fahrer entgegen oder mit ihm
        velocity = (right * side + f * (rnd.nextDouble() * 0.5 - 0.25)).normalized() * speed
        durationS = 2 * cross / speed
        flightS = 0.0
        members.clear()
        val n = if (rnd.nextDouble() < 0.3) 1 else (3 + rnd.nextInt(5)).coerceAtMost(maxBirds)
        val back = velocity.normalized() * -1.0
        val lat = V3(-back.z, 0.0, back.x)
        for (k in 0 until n) {
            // Lockere Keil-/Haufenform hinter dem ersten
            val row = (k + 1) / 2
            val s = if (k % 2 == 0) 1.0 else -1.0
            val off = back * (row * (1.6 + rnd.nextDouble())) + lat * (s * row * (1.2 + rnd.nextDouble())) +
                V3(0.0, (rnd.nextDouble() - 0.5) * 1.5, 0.0)
            members += Member(off, rnd.nextDouble() * 6.0, 3.6 + rnd.nextDouble() * 1.4, rnd.nextDouble() * 0.4 - 0.3)
        }
    }

    companion object {
        const val MAX_BIRDS = 7
        const val IDLE_MIN_S = 35.0
        const val IDLE_MAX_S = 110.0
    }
}

/**
 * Das Vogelmodell: [BirdFlock.MAX_BIRDS] Voegel, je ein Knoten `x_bird_<i>` mit zwei
 * Fluegeln `x_bird_<i>_l`/`_r` (Rumpfhaelfte samt Fluegel, Gelenk an der Schulter). Spannweite
 * rund 0,7 m (Kraehe/Taube), dunkel. Je Vogel zwei Draw Calls, nur waehrend eines Fluges.
 */
internal object BirdModel {
    const val SPAN_M = 0.7

    fun node(i: Int) = "x_bird_$i"
    fun wing(i: Int, left: Boolean) = "x_bird_${i}_${if (left) "l" else "r"}"

    fun buildGlb(): ByteArray {
        val w = GlbWriter()
        val mat = w.material(GlbWriter.Material("x_mat_bird", floatArrayOf(0.035f, 0.032f, 0.03f, 1f), roughness = 0.9f, doubleSided = true))
        val half = SPAN_M / 2
        fun wingMesh(sign: Double): Int {
            val m = MeshBuilder()
            val up = V3(0.0, 1.0, 0.0)
            // Rumpfhaelfte (flacher Keil) und Fluegel: Blick nach +Z, Fluegel nach ±X
            m.quad(V3(0.0, 0.0, 0.16), V3(sign * 0.04, 0.0, 0.05), V3(sign * 0.035, 0.0, -0.16), V3(0.0, 0.0, -0.2), up)
            m.quad(V3(sign * 0.035, 0.0, 0.06), V3(sign * half * 0.55, 0.0, 0.05), V3(sign * half * 0.5, 0.0, -0.08), V3(sign * 0.03, 0.0, -0.07), up)
            m.quad(V3(sign * half * 0.55, 0.0, 0.05), V3(sign * half, 0.0, -0.02), V3(sign * half * 0.92, 0.0, -0.09), V3(sign * half * 0.5, 0.0, -0.08), up)
            // Schwanz
            m.quad(V3(0.0, 0.0, -0.18), V3(sign * 0.05, 0.0, -0.3), V3(sign * 0.01, 0.0, -0.32), V3(0.0, 0.0, -0.3), up)
            return w.mesh("x_bird_wing_${if (sign < 0) "l" else "r"}", listOf(m.primitive(mat)))
        }
        val left = wingMesh(-1.0)
        val right = wingMesh(1.0)
        for (i in 0 until BirdFlock.MAX_BIRDS) {
            val root = w.node(GlbWriter.Node(node(i)))
            w.node(GlbWriter.Node(wing(i, true), mesh = left), root)
            w.node(GlbWriter.Node(wing(i, false), mesh = right), root)
        }
        return w.build()
    }

    /** Modellmatrix eines Vogels (Spalten), Blick nach [yawRad] (0 = +Z). */
    fun birdMatrix(p: V3, yawRad: Double): FloatArray {
        val c = cos(yawRad)
        val s = sin(yawRad)
        return floatArrayOf(
            c.toFloat(), 0f, (-s).toFloat(), 0f,
            0f, 1f, 0f, 0f,
            s.toFloat(), 0f, c.toFloat(), 0f,
            p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f,
        )
    }

    /** Fluegelschlag: Drehung um die Laengsachse (Z), links und rechts gespiegelt. */
    fun wingMatrix(flapRad: Double, left: Boolean): FloatArray {
        val a = if (left) -flapRad else flapRad
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
}
