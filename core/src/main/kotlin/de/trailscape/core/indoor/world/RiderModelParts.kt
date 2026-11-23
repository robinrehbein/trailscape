package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Formwerkzeuge fuer die Modelle (Fahrer und Rad)
 *
 * Organische Koerper entstehen als **Haut ueber Querschnitten**: wenige
 * Stuetzquerschnitte ([Key]) werden entlang ihrer Mittellinie weich
 * verfeinert ([buildRings]) und zu einem geschlossenen Netz verbunden
 * ([addSkin]). Ein Querschnitt ist eine Superellipse mit getrennten Radien je
 * Halbachse — damit lassen sich Muskelbaeuche (Wade nach hinten), flache
 * Sohlen und der breite, flache Rumpf ohne Bildhauerei beschreiben.
 * Drehkoerper (Reifen, Felge, Nabe, Kassette) baut [addRevolveX].
 *
 * Die Netze haben keine Naht (Ringe sind geschlossen, die Normalen werden
 * ueber gemeinsame Eckpunkte gemittelt) — Texturkoordinaten tragen deshalb
 * nichts, die Modelle leben von Form und Vertexfarbe.
 */

/**
 * Stuetzquerschnitt: Mitte [c], Halbachsen [ru] (seitlich, entlang der
 * Bezugsrichtung) und [rv] (senkrecht dazu), fuer die Gegenrichtung
 * [ruNeg]/[rvNeg]. [power] = 2 ist eine Ellipse, groesser wird kantiger.
 */
internal class Key(
    val c: Vec3,
    val ru: Double,
    val rv: Double = ru,
    val ruNeg: Double = ru,
    val rvNeg: Double = rv,
    val power: Double = 2.0,
    val color: Int = Argb.WHITE,
)

/**
 * Fertiger Querschnitt mit Rahmen: [u] seitlich, [v] senkrecht dazu, beide
 * senkrecht zur Laufrichtung. [s] ist die Lage in Stuetzstellen (3,5 = mittig
 * zwischen der vierten und fuenften), fuer Bemalung nach Abschnitt.
 */
internal class Ring(
    val c: Vec3,
    val u: Vec3,
    val v: Vec3,
    val ru: Double,
    val rv: Double,
    val ruNeg: Double,
    val rvNeg: Double,
    val power: Double,
    val color: Int,
    val s: Double,
) {
    /** Punkt des Umrisses beim Winkel [theta] (0 = +u, π/2 = +v). */
    fun point(theta: Double): Vec3 {
        val cu = superCos(cos(theta))
        val sv = superCos(sin(theta))
        val a = cu * (if (cu >= 0) ru else ruNeg)
        val b = sv * (if (sv >= 0) rv else rvNeg)
        return Vec3(c.x + u.x * a + v.x * b, c.y + u.y * a + v.y * b, c.z + u.z * a + v.z * b)
    }

    private fun superCos(x: Double): Double = if (power == 2.0) x else sign(x) * abs(x).pow(2.0 / power)

    /** Derselbe Querschnitt, verschoben und gleichmaessig skaliert (fuer Kuppen). */
    fun scaled(center: Vec3, k: Double) = Ring(center, u, v, ru * k, rv * k, ruNeg * k, rvNeg * k, power, color, s)
}

/** sRGB-Farbe `0xRRGGBB` als Vertexfarbe: `COLOR_0` ist linear, also umgerechnet. */
internal fun lin(srgb: Int, alpha: Int = 255): Int {
    fun ch(shift: Int): Int = (Argb.SRGB_TO_LINEAR[(srgb shr shift) and 0xFF] * 255f + 0.5f).toInt()
    return Argb.of(alpha, ch(16), ch(8), ch(0))
}

/** Punkt in der Seitenansicht: [z] nach vorn, [y] nach oben, [x] seitlich (links positiv). */
internal fun sv(z: Double, y: Double, x: Double = 0.0) = Vec3(x, y, z)

private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
}

private fun catmull(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double) =
    Vec3(catmull(p0.x, p1.x, p2.x, p3.x, t), catmull(p0.y, p1.y, p2.y, p3.y, t), catmull(p0.z, p1.z, p2.z, p3.z, t))

/** Weiche Kurve durch [pts] (Catmull-Rom), je Abschnitt [sub] Teilstuecke. */
internal fun smoothPath(pts: List<Vec3>, sub: Int): List<Vec3> {
    if (pts.size < 3 || sub <= 1) return pts
    val out = ArrayList<Vec3>((pts.size - 1) * sub + 1)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[max(0, i - 1)]
        val p3 = pts[minOf(pts.size - 1, i + 2)]
        for (k in 0 until sub) out.add(catmull(p0, pts[i], pts[i + 1], p3, k.toDouble() / sub))
    }
    out.add(pts.last())
    return out
}

/**
 * Verfeinert [keys] weich (je Abschnitt [sub] Teilstuecke) und gibt jedem
 * Querschnitt einen Rahmen: `u` ist [ref], senkrecht zur Laufrichtung
 * gestellt, `v = t × u`. Mit `ref = X` und einer Mittellinie in der
 * Seitenansicht zeigt `v` beim Rumpf zum Ruecken, bei einem nach vorn
 * laufenden Koerper nach oben.
 */
internal fun buildRings(keys: List<Key>, sub: Int = 1, ref: Vec3 = Vec3.X): List<Ring> {
    require(keys.size >= 2) { "Mindestens zwei Querschnitte" }
    class Raw(val c: Vec3, val ru: Double, val rv: Double, val ruNeg: Double, val rvNeg: Double, val power: Double, val color: Int, val s: Double)

    val raw = ArrayList<Raw>()
    val n = keys.size
    for (i in 0 until n - 1) {
        val k0 = keys[max(0, i - 1)]
        val k1 = keys[i]
        val k2 = keys[i + 1]
        val k3 = keys[minOf(n - 1, i + 2)]
        for (j in 0 until sub) {
            val t = j.toDouble() / sub
            // Radien duerfen durch das Ueberschwingen der Kurve nicht unter die kleinere Stuetzstelle fallen.
            fun r(a: Double, b: Double, c: Double, d: Double): Double = catmull(a, b, c, d, t).coerceAtLeast(minOf(b, c) * 0.9)
            raw.add(
                Raw(
                    catmull(k0.c, k1.c, k2.c, k3.c, t),
                    r(k0.ru, k1.ru, k2.ru, k3.ru), r(k0.rv, k1.rv, k2.rv, k3.rv),
                    r(k0.ruNeg, k1.ruNeg, k2.ruNeg, k3.ruNeg), r(k0.rvNeg, k1.rvNeg, k2.rvNeg, k3.rvNeg),
                    k1.power + (k2.power - k1.power) * t, Argb.mix(k1.color, k2.color, t), i + t,
                ),
            )
        }
    }
    val last = keys.last()
    raw.add(Raw(last.c, last.ru, last.rv, last.ruNeg, last.rvNeg, last.power, last.color, (n - 1).toDouble()))

    val m = raw.size
    var prevU = ref
    return List(m) { i ->
        val t = (raw[minOf(m - 1, i + 1)].c - raw[max(0, i - 1)].c).normalized(Vec3.UP)
        val proj = ref - t * (ref dot t)
        // Laeuft die Mittellinie fast in Bezugsrichtung, den Rahmen des Vorgaengers weitertragen.
        val base = if (proj.length() > 0.2) proj else prevU - t * (prevU dot t)
        val u = base.normalized(Vec3.X)
        prevU = u
        val r = raw[i]
        Ring(r.c, u, t cross u, r.ru, r.rv, r.ruNeg, r.rvNeg, r.power, r.color, r.s)
    }
}

/**
 * Rundet ein Ende ab: haengt an [rings] (vorn bei [atStart], sonst hinten)
 * [steps] schrumpfende Querschnitte einer Halbkugel der Tiefe [depth] an.
 */
internal fun withDome(rings: List<Ring>, atStart: Boolean, depth: Double, steps: Int = 4): List<Ring> {
    val edge = if (atStart) rings.first() else rings.last()
    val dir = (edge.u cross edge.v) * (if (atStart) -1.0 else 1.0)
    val dome = (1..steps).map { k ->
        val phi = (PI / 2) * k / (steps + 0.35)
        edge.scaled(edge.c + dir * (depth * sin(phi)), cos(phi))
    }
    return if (atStart) dome.reversed() + rings else rings + dome
}

/**
 * Haut ueber [rings] mit [segments] Punkten je Umriss, Enden als Faecher
 * geschlossen ([capStart]/[capEnd]), Normalen geglaettet. [paint] bestimmt
 * die Farbe je Eckpunkt aus Querschnitt und Winkel, sonst gilt [Ring.color].
 */
internal fun MeshBuilder.addSkin(
    rings: List<Ring>,
    segments: Int,
    capStart: Boolean = true,
    capEnd: Boolean = true,
    paint: ((ring: Ring, theta: Double) -> Int)? = null,
) {
    require(rings.size >= 2 && segments >= 3) { "Haut braucht zwei Querschnitte und drei Segmente" }
    val firstTri = triangleCount
    val base = vertexCount
    ensureVertexCapacity(base + rings.size * segments + 2)
    val total = (rings.size - 1).toDouble()
    for ((i, r) in rings.withIndex()) {
        for (j in 0 until segments) {
            val th = 2 * PI * j / segments
            val p = r.point(th)
            vertex(p.x, p.y, p.z, 0.0, 1.0, 0.0, j.toDouble() / segments, i / total, paint?.invoke(r, th) ?: r.color)
        }
    }
    // Der Umriss laeuft von u nach v; zeigt u × v gegen die Laufrichtung, wird der Umlaufsinn gedreht.
    var along = 0.0
    for (i in 0 until rings.size - 1) along += (rings[i].u cross rings[i].v) dot (rings[i + 1].c - rings[i].c)
    val flip = along < 0
    for (i in 0 until rings.size - 1) {
        for (j in 0 until segments) {
            val a = base + i * segments + j
            val b = base + i * segments + (j + 1) % segments
            if (flip) quad(a, a + segments, b + segments, b) else quad(a, b, b + segments, a + segments)
        }
    }
    if (capStart) {
        val r = rings.first()
        val c = vertex(r.c.x, r.c.y, r.c.z, 0.0, 1.0, 0.0, 0.5, 0.0, paint?.invoke(r, PI / 2) ?: r.color)
        for (j in 0 until segments) {
            val a = base + j
            val b = base + (j + 1) % segments
            if (flip) tri(c, a, b) else tri(c, b, a)
        }
    }
    if (capEnd) {
        val r = rings.last()
        val off = base + (rings.size - 1) * segments
        val c = vertex(r.c.x, r.c.y, r.c.z, 0.0, 1.0, 0.0, 0.5, 1.0, paint?.invoke(r, PI / 2) ?: r.color)
        for (j in 0 until segments) {
            val a = off + j
            val b = off + (j + 1) % segments
            if (flip) tri(c, b, a) else tri(c, a, b)
        }
    }
    computeSmoothNormals(firstTri, triangleCount)
}

/** Ein Punkt eines Drehprofils: Lage [x] auf der Achse, Radius [r]. */
internal class ProfilePoint(val x: Double, val r: Double, val color: Int = Argb.WHITE)

/**
 * Drehkoerper um die X-Achse durch `(0, cy, cz)`. Das Profil laeuft so, dass
 * die Aussenseite rechts der Laufrichtung liegt (geschlossene Profile gegen
 * den Uhrzeigersinn in der Ebene x nach rechts, r nach oben). [hard] gibt
 * jedem Profilabschnitt eigene Eckpunkte (scharfe Kanten zwischen den
 * Abschnitten, rund um die Achse bleibt es glatt). [paint] faerbt nach
 * Profilpunkt und Drehwinkel.
 */
internal fun MeshBuilder.addRevolveX(
    profile: List<ProfilePoint>,
    segments: Int,
    cy: Double = 0.0,
    cz: Double = 0.0,
    closed: Boolean = false,
    hard: Boolean = false,
    paint: ((point: ProfilePoint, phi: Double) -> Int)? = null,
) {
    require(profile.size >= 2 && segments >= 3) { "Drehkoerper braucht zwei Profilpunkte und drei Segmente" }
    val firstTri = triangleCount
    fun row(p: ProfilePoint): Int {
        val first = vertexCount
        for (j in 0 until segments) {
            val phi = 2 * PI * j / segments
            vertex(p.x, cy + p.r * cos(phi), cz + p.r * sin(phi), 1.0, 0.0, 0.0, j.toDouble() / segments, 0.0, paint?.invoke(p, phi) ?: p.color)
        }
        return first
    }

    fun band(r0: Int, r1: Int) {
        for (j in 0 until segments) {
            val j1 = (j + 1) % segments
            quad(r0 + j, r1 + j, r1 + j1, r0 + j1)
        }
    }

    val count = if (closed) profile.size else profile.size - 1
    if (hard) {
        for (k in 0 until count) {
            val a = profile[k]
            val b = profile[(k + 1) % profile.size]
            if (a.r <= 1e-9 && b.r <= 1e-9) continue
            band(row(a), row(b))
        }
    } else {
        val rows = IntArray(profile.size) { row(profile[it]) }
        for (k in 0 until count) band(rows[k], rows[(k + 1) % profile.size])
    }
    computeSmoothNormals(firstTri, triangleCount)
}

/** Drehung aus den Bildern der drei Achsen (orthonormal, rechtshaendig). */
internal fun quatFromBasis(ex: Vec3, ey: Vec3, ez: Vec3): Quat {
    val tr = ex.x + ey.y + ez.z
    val q = if (tr > 0) {
        val s = sqrt(tr + 1.0) * 2
        Quat((ey.z - ez.y) / s, (ez.x - ex.z) / s, (ex.y - ey.x) / s, 0.25 * s)
    } else if (ex.x > ey.y && ex.x > ez.z) {
        val s = sqrt(1.0 + ex.x - ey.y - ez.z) * 2
        Quat(0.25 * s, (ey.x + ex.y) / s, (ez.x + ex.z) / s, (ey.z - ez.y) / s)
    } else if (ey.y > ez.z) {
        val s = sqrt(1.0 + ey.y - ex.x - ez.z) * 2
        Quat((ey.x + ex.y) / s, 0.25 * s, (ez.y + ey.z) / s, (ez.x - ex.z) / s)
    } else {
        val s = sqrt(1.0 + ez.z - ex.x - ey.y) * 2
        Quat((ez.x + ex.z) / s, (ez.y + ey.z) / s, 0.25 * s, (ex.y - ey.x) / s)
    }
    return q.normalized()
}
