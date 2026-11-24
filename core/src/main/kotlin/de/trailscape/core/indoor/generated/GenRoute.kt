package de.trailscape.core.indoor.generated

import de.trailscape.core.indoor.world.GeoOrigin
import de.trailscape.core.indoor.world.Noise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * # Linie und Hoehenverlauf einer generierten Strecke
 *
 * Aus einem [GeneratedProfile] entsteht ein dichter Linienzug (alle
 * [GenRoutePlan.STEP_M] Meter) im Entwurfs-ENU um [GEN_DESIGN_ORIGIN] mit
 * Hoehe je Station. Alles folgt aus dem Samen; kein `Math.random`, keine Uhr.
 *
 * Hoehe: Steigung je Leg (gekappt auf [MAX_LEG_GRADE_PCT]), Wellen je nach
 * Art (mittelwertfrei je Leg), weiche Uebergaenge (Gauss, σ 40 m), danach
 * aufintegriert.
 *
 * Lage: „Schildkroete" mit Kruemmung aus glattem Rauschen, einer langsam
 * wandernden Hauptrichtung (die Strecke kommt voran, statt sich zu
 * verknaeueln), Ausweichen vor bereits befahrenen Stellen (Vorausschau bis
 * 240 m) und an steilen Anstiegen Serpentinen (gerade Rampen quer zum Hang,
 * Kehren mit 15 m Radius). Gelingt keine Linie ohne Selbstannaeherung
 * (< 45 m bei mehr als 250 m Laufmeterabstand), wird mit abgeleitetem Samen
 * neu gezogen.
 */
internal enum class GenLandscape {
    /** Bewaldete Bergruecken, Taeler, Serpentinen an steilen Anstiegen. */
    MITTELGEBIRGE,

    /** Sanfte Wellen, Felder, Waldinseln, Seen, mehr Doerfer. */
    HUEGELLAND,
}

/** Bezugspunkt des Entwurfs: offener Atlantik suedwestlich von Irland — keine echte Landschaft, mitteleuropaeische Breite (Sonnenstand). */
internal val GEN_DESIGN_ORIGIN = GeoOrigin(50.0, -12.0, 0.0)

/** Steilste mittlere Steigung eines Legs, die gebaut wird; steilere werden gekappt. */
internal const val MAX_LEG_GRADE_PCT = 18.0

/** Steilste Steigung zwischen zwei Stationen (nach Wellen und Uebergaengen). */
internal const val MAX_STATION_GRADE_PCT = 22.0

internal class GenRoutePlan(
    val profile: GeneratedProfile,
    val landscape: GenLandscape,
    val lengthM: Double,
    /** Laufmeter je Station (streng steigend, erste 0, letzte [lengthM]). */
    val dist: DoubleArray,
    val e: DoubleArray,
    val n: DoubleArray,
    val ele: DoubleArray,
    /** Ortsdurchfahrten als `[von, bis]` in Laufmetern. */
    val villages: List<DoubleArray>,
    /** Serpentinen als `[von, bis]`. */
    val serpentines: List<DoubleArray>,
    /** Legs nach dem Kappen: Start in Laufmetern, Steigung, Art. */
    val legs: List<GenLeg>,
    /** Wie viele Linien verworfen wurden, bis eine passte. */
    val attempts: Int,
) {
    val count: Int get() = dist.size

    /** Station am Laufmeter [s] (abgerundet). */
    fun indexAt(s: Double): Int = (s / STEP_M).toInt().coerceIn(0, count - 1)

    fun legAt(s: Double): GenLeg = legs.lastOrNull { it.fromM <= s } ?: legs.first()

    fun inVillage(s: Double, margin: Double = 0.0): Boolean = villages.any { s >= it[0] - margin && s <= it[1] + margin }

    companion object {
        const val STEP_M = 5.0
    }
}

internal class GenLeg(val fromM: Double, val toM: Double, val gradePct: Double, val kind: GeneratedProfile.Kind, val cappedFromPct: Double?)

/** Samen fuer Teilaufgaben, aus dem Profil-Samen abgeleitet. */
internal fun genSeed(seed: Long, salt: Int): Int = Noise.seedOf(seed, salt.toLong())

/** Glattes 1D-Rauschen etwa in [-1, 1]. */
internal fun noise1(x: Double, seed: Int): Double = (Noise.gradient(x, 0.371, seed) * 1.5).coerceIn(-1.0, 1.0)

internal fun wrapAngle(a: Double): Double {
    var x = a % (2 * PI)
    if (x > PI) x -= 2 * PI
    if (x < -PI) x += 2 * PI
    return x
}

internal fun landscapeOf(profile: GeneratedProfile): GenLandscape {
    // Viel Hoehe je km macht Mittelgebirge wahrscheinlicher, entschieden wird ueber den Samen.
    val climb = profile.legs.sumOf { max(0.0, it.gradePct.coerceIn(-MAX_LEG_GRADE_PCT, MAX_LEG_GRADE_PCT)) * it.lengthM / 100 }
    val perKm = climb / max(1.0, profile.lengthM / 1000)
    val bias = (perKm / 40.0).coerceIn(0.0, 0.35)
    val h = Noise.hash01(genSeed(profile.seed, 1), 7, 11)
    return if (h < 0.5 + bias) GenLandscape.MITTELGEBIRGE else GenLandscape.HUEGELLAND
}

internal fun planGeneratedRoute(profile: GeneratedProfile): GenRoutePlan {
    val legsIn = profile.legs.filter { it.lengthM.isFinite() && it.lengthM > 0 && it.gradePct.isFinite() }
    require(legsIn.isNotEmpty()) { "Profil ohne Abschnitte" }
    val length = legsIn.sumOf { it.lengthM }
    require(length >= 200.0) { "Profil zu kurz: ${length.roundToInt()} m" }
    require(length <= 150_000.0) { "Profil zu lang: ${length.roundToInt()} m" }
    val landscape = landscapeOf(profile)

    // Legs mit gekappter Steigung
    val legs = ArrayList<GenLeg>()
    var acc = 0.0
    for (l in legsIn) {
        val g = when (l.kind) {
            GeneratedProfile.Kind.FLAT -> l.gradePct.coerceIn(-2.0, 2.0)
            else -> l.gradePct
        }
        val capped = g.coerceIn(-MAX_LEG_GRADE_PCT, MAX_LEG_GRADE_PCT)
        legs += GenLeg(acc, acc + l.lengthM, capped, l.kind, if (capped != l.gradePct) l.gradePct else null)
        acc += l.lengthM
    }

    val step = GenRoutePlan.STEP_M
    val count = ceil(length / step - 1e-9).toInt() + 1
    val dist = DoubleArray(count) { min(it * step, length) }
    dist[count - 1] = length
    val ele = elevations(profile.seed, landscape, legs, dist)

    val villages = chooseVillages(profile.seed, landscape, legs, dist, ele, length)
    val serp = chooseSerpentines(profile.seed, landscape, legs, villages)

    var best: Pair<DoubleArray, DoubleArray>? = null
    var bestConflicts = Int.MAX_VALUE
    var used = 0
    for (attempt in 0 until 16) {
        used = attempt + 1
        val line = turtle(profile.seed, attempt, landscape, dist, serp, villages)
        val c = conflicts(line.first, line.second, dist)
        if (c < bestConflicts) {
            bestConflicts = c
            best = line
        }
        if (c == 0) break
    }
    val (e, n) = best!!
    // Umgebungsrechteck mittig auf den Ursprung: Der Weltbau legt seinen Ursprung genau dorthin.
    val cE = (e.min() + e.max()) / 2
    val cN = (n.min() + n.max()) / 2
    for (i in e.indices) {
        e[i] -= cE
        n[i] -= cN
    }
    return GenRoutePlan(profile, landscape, length, dist, e, n, ele, villages, serp, legs, used)
}

// ---------------------------------------------------------------------------
// Hoehe
// ---------------------------------------------------------------------------

private fun elevations(seed: Long, landscape: GenLandscape, legs: List<GenLeg>, dist: DoubleArray): DoubleArray {
    val count = dist.size
    val grade = DoubleArray(count) // Prozent, fuer das Stueck i → i+1
    val sd = genSeed(seed, 2)
    for ((li, leg) in legs.withIndex()) {
        val (amp, wave) = when (leg.kind) {
            GeneratedProfile.Kind.FLAT -> 0.6 to 900.0
            GeneratedProfile.Kind.ROLLING -> (if (landscape == GenLandscape.HUEGELLAND) 3.2 else 2.6) to 650.0
            GeneratedProfile.Kind.CLIMB, GeneratedProfile.Kind.DESCENT -> 1.1 to 420.0
        }
        val i0 = (leg.fromM / GenRoutePlan.STEP_M).toInt().coerceIn(0, count - 1)
        val i1 = ceil(leg.toM / GenRoutePlan.STEP_M - 1e-9).toInt().coerceIn(i0 + 1, count - 1)
        var mean = 0.0
        var wsum = 0.0
        val w = DoubleArray(i1 - i0)
        for (i in i0 until i1) {
            val s = (dist[i] + dist[i + 1]) / 2
            // Huellkurve: an den Leg-Enden keine Welle (Uebergang bleibt sauber)
            val env = smooth01((s - leg.fromM) / 120.0) * smooth01((leg.toM - s) / 120.0)
            val v = amp * env * (0.7 * noise1(s / wave, sd + li) + 0.3 * noise1(s / (wave * 0.37), sd + 97 + li))
            w[i - i0] = v
            val ds = dist[i + 1] - dist[i]
            mean += v * ds
            wsum += ds
        }
        mean /= max(1e-9, wsum)
        for (i in i0 until i1) {
            val s = (dist[i] + dist[i + 1]) / 2
            val env = smooth01((s - leg.fromM) / 120.0) * smooth01((leg.toM - s) / 120.0)
            grade[i] = leg.gradePct + w[i - i0] - mean * env / max(1e-9, envMean(leg))
        }
    }
    // weiche Uebergaenge
    val sigma = 40.0 / GenRoutePlan.STEP_M
    val half = (3 * sigma).toInt()
    val kernel = DoubleArray(2 * half + 1) { exp(-0.5 * ((it - half) / sigma) * ((it - half) / sigma)) }
    val smoothed = DoubleArray(count)
    for (i in 0 until count - 1) {
        var s = 0.0
        var ws = 0.0
        for (k in -half..half) {
            val j = (i + k).coerceIn(0, count - 2)
            s += grade[j] * kernel[k + half]
            ws += kernel[k + half]
        }
        smoothed[i] = (s / ws).coerceIn(-MAX_STATION_GRADE_PCT, MAX_STATION_GRADE_PCT)
    }
    val ele = DoubleArray(count)
    for (i in 1 until count) ele[i] = ele[i - 1] + smoothed[i - 1] / 100.0 * (dist[i] - dist[i - 1])
    val base = when (landscape) {
        GenLandscape.MITTELGEBIRGE -> 280.0 + 160.0 * Noise.hash01(genSeed(seed, 3), 1, 2)
        GenLandscape.HUEGELLAND -> 90.0 + 90.0 * Noise.hash01(genSeed(seed, 3), 1, 2)
    }
    val lo = ele.min()
    val shift = base + max(0.0, 40.0 - (base + lo))
    for (i in ele.indices) ele[i] += shift
    return ele
}

/** Mittel der Huellkurve eines Legs (fuer die Mittelwertkorrektur nur dort, wo Welle ist). */
private fun envMean(leg: GenLeg): Double {
    val len = leg.toM - leg.fromM
    var s = 0.0
    val k = 200
    for (i in 0 until k) {
        val x = leg.fromM + (i + 0.5) / k * len
        s += smooth01((x - leg.fromM) / 120.0) * smooth01((leg.toM - x) / 120.0)
    }
    return s / k
}

internal fun smooth01(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * (3 - 2 * x)
}

// ---------------------------------------------------------------------------
// Orte und Serpentinen
// ---------------------------------------------------------------------------

private fun chooseVillages(
    seed: Long,
    landscape: GenLandscape,
    legs: List<GenLeg>,
    dist: DoubleArray,
    ele: DoubleArray,
    length: Double,
): List<DoubleArray> {
    val spacing = if (landscape == GenLandscape.HUEGELLAND) 3_600.0 else 4_800.0
    val maxCount = min(10, max(1, (length / spacing).toInt()))
    val sd = genSeed(seed, 4)
    val out = ArrayList<DoubleArray>()
    // Kandidaten der Reihe nach, mit Samen versetzt
    var s = 150.0 + 900.0 * Noise.hash01(sd, 0, 1)
    var k = 0
    while (s < length - 250 && out.size < maxCount) {
        val len = 420.0 + 380.0 * Noise.hash01(sd, k, 2)
        val a = s
        val b = min(length - 50, s + len)
        if (b - a > 300 && flatEnough(legs, dist, ele, a, b)) {
            out += doubleArrayOf(a, b)
            s = b + spacing * (0.7 + 0.6 * Noise.hash01(sd, k, 3))
        } else {
            s += 150.0
        }
        k++
    }
    return out
}

private fun flatEnough(legs: List<GenLeg>, dist: DoubleArray, ele: DoubleArray, a: Double, b: Double): Boolean {
    if (legs.any { it.fromM < b && it.toM > a && abs(it.gradePct) > 4.0 }) return false
    val i0 = (a / GenRoutePlan.STEP_M).toInt()
    val i1 = min(dist.size - 1, (b / GenRoutePlan.STEP_M).toInt())
    for (i in i0 until i1 step 4) {
        val j = min(i1, i + 4)
        val g = (ele[j] - ele[i]) / max(1.0, dist[j] - dist[i]) * 100
        if (abs(g) > 5.0) return false
    }
    return true
}

private fun chooseSerpentines(seed: Long, landscape: GenLandscape, legs: List<GenLeg>, villages: List<DoubleArray>): List<DoubleArray> {
    val minGrade = if (landscape == GenLandscape.MITTELGEBIRGE) 5.5 else 9.0
    val sd = genSeed(seed, 5)
    val out = ArrayList<DoubleArray>()
    for ((i, leg) in legs.withIndex()) {
        val len = leg.toM - leg.fromM
        if (abs(leg.gradePct) < minGrade || len < 900) continue
        // je steiler, desto eher Kehren (Mittelgebirge: 6 % → 0,31, 8 % → 0,55, ab 10 % → 0,8)
        val chance = if (landscape == GenLandscape.MITTELGEBIRGE) (0.25 + 0.12 * (abs(leg.gradePct) - 5.5)).coerceAtMost(0.8) else 0.5
        if (Noise.hash01(sd, i, 1) > chance) continue
        val frac = 0.45 + 0.3 * Noise.hash01(sd, i, 2)
        val span = min(len * frac, 2_600.0)
        val start = leg.fromM + (len - span) * (0.3 + 0.4 * Noise.hash01(sd, i, 3))
        val a = start
        val b = start + span
        if (villages.any { it[0] < b + 100 && it[1] > a - 100 }) continue
        if (serpentinePieces(b - a, 1.0) != null) out += doubleArrayOf(a, b)
    }
    return out
}

/** Stueck einer Serpentine: Laenge und Kruemmung. */
private class Piece(val length: Double, val kappa: Double)

private const val HAIRPIN_R = 15.0
private const val SERP_TURN_R = 60.0
private val RAMP_ANGLE = Math.toRadians(70.0)
private val HAIRPIN_ANGLE = Math.toRadians(140.0)

/** Rampen und Kehren, die genau [span] Meter fuellen; `null`, wenn keine zwei Kehren hineinpassen. */
private fun serpentinePieces(span: Double, sign: Double): List<Piece>? {
    val turn = RAMP_ANGLE * SERP_TURN_R
    val hairpin = HAIRPIN_ANGLE * HAIRPIN_R
    var m = 2
    var bestM = -1
    while (true) {
        val ramp = (span - 2 * turn - m * hairpin) / (m + 1)
        if (ramp < 170) break
        if (ramp <= 420) bestM = m
        m++
    }
    if (bestM < 0) {
        // laengere Rampen erlauben, wenn sonst nichts passt
        val ramp2 = (span - 2 * turn - 2 * hairpin) / 3
        if (ramp2 < 170) return null
        bestM = 2
    }
    val ramp = (span - 2 * turn - bestM * hairpin) / (bestM + 1)
    val out = ArrayList<Piece>()
    out += Piece(turn, sign / SERP_TURN_R)
    var dir = -sign
    for (k in 0 until bestM) {
        out += Piece(ramp, 0.0)
        out += Piece(hairpin, dir / HAIRPIN_R)
        dir = -dir
    }
    out += Piece(ramp, 0.0)
    // nach bestM Kehren liegt die Rampe auf +70° (gerade Zahl) bzw. −70° (ungerade): zurueckdrehen
    val back = if (bestM % 2 == 0) -sign else sign
    out += Piece(turn, back / SERP_TURN_R)
    return out
}

// ---------------------------------------------------------------------------
// Linie
// ---------------------------------------------------------------------------

private fun turtle(
    seed: Long,
    attempt: Int,
    landscape: GenLandscape,
    dist: DoubleArray,
    serp: List<DoubleArray>,
    villages: List<DoubleArray>,
): Pair<DoubleArray, DoubleArray> {
    val sd = genSeed(seed, 100 + attempt)
    val count = dist.size
    val e = DoubleArray(count)
    val n = DoubleArray(count)
    val psi0 = 2 * PI * Noise.hash01(sd, 1, 1)
    var theta = psi0
    var psi = psi0
    val rMin = if (landscape == GenLandscape.MITTELGEBIRGE) 70.0 else 110.0
    val amp = if (landscape == GenLandscape.MITTELGEBIRGE) 1.0 / 190 else 1.0 / 380
    val lam1 = if (landscape == GenLandscape.MITTELGEBIRGE) 700.0 else 1_100.0
    val lam2 = if (landscape == GenLandscape.MITTELGEBIRGE) 220.0 else 330.0
    val attract = 1.0 / (380.0 + 120.0 * attempt)
    val occ = HashMap<Long, Double>()
    val cell = 25.0
    fun key(x: Double, y: Double): Long = (floor(x / cell).toLong() shl 32) xor (floor(y / cell).toLong() and 0xffffffffL)
    fun blockedAt(x: Double, y: Double, s: Double): Boolean {
        val cx = floor(x / cell).toLong()
        val cy = floor(y / cell).toLong()
        for (dx in -1L..1L) for (dy in -1L..1L) {
            val v = occ[((cx + dx) shl 32) xor ((cy + dy) and 0xffffffffL)] ?: continue
            if (v < s - 220) return true
        }
        return false
    }
    var serpIdx = -1
    var serpPieces: List<Piece> = emptyList()
    var avoidSide = 0.0
    var avoidUntil = -1.0
    for (i in 1 until count) {
        val s = dist[i - 1]
        val ds = dist[i] - dist[i - 1]
        // Serpentine?
        val si = serp.indexOfFirst { s >= it[0] && s < it[1] }
        var kappa: Double
        if (si >= 0) {
            if (si != serpIdx) {
                serpIdx = si
                val sign = if (Noise.hash01(sd, si, 9) < 0.5) 1.0 else -1.0
                serpPieces = serpentinePieces(serp[si][1] - serp[si][0], sign) ?: emptyList()
            }
            var rest = s - serp[si][0]
            kappa = 0.0
            for (p in serpPieces) {
                if (rest < p.length) {
                    kappa = p.kappa
                    break
                }
                rest -= p.length
            }
            // Rampe mit leichter Unruhe
            if (kappa == 0.0) kappa = 0.15 * amp * noise1(s / 90.0, sd + 5)
            psi = theta
        } else {
            val mask = 0.55 + 0.45 * noise1(s / 1_700.0, sd + 3)
            kappa = amp * (0.7 * noise1(s / lam1, sd + 1) + 0.3 * noise1(s / lam2, sd + 2)) * (0.6 + mask)
            if (villages.any { s >= it[0] - 60 && s <= it[1] + 60 }) kappa *= 0.45
            // Hauptrichtung wandert langsam
            psi += ds * 0.8 * (noise1(s / 6_000.0, sd + 4)) / 2_000.0
            kappa += wrapAngle(psi - theta) * attract
            // Ausweichen alle 20 m pruefen
            if (i % 4 == 0) {
                val x = e[i - 1]
                val y = n[i - 1]
                fun freeScore(dir: Double): Int {
                    var sc = 0
                    for (d in doubleArrayOf(60.0, 120.0, 180.0, 240.0)) {
                        if (!blockedAt(x + cos(dir) * d, y + sin(dir) * d, s)) sc++ else break
                    }
                    return sc
                }
                val ahead = freeScore(theta)
                if (ahead < 4) {
                    val left = freeScore(theta + 0.7)
                    val right = freeScore(theta - 0.7)
                    avoidSide = if (left >= right) 1.0 else -1.0
                    avoidUntil = s + 80.0
                    psi = theta + avoidSide * 1.0
                }
            }
            if (s < avoidUntil) kappa += avoidSide / 75.0
            val lim = if (s < avoidUntil) 1.0 / 55.0 else 1.0 / rMin
            kappa = kappa.coerceIn(-lim, lim)
        }
        val mid = theta + kappa * ds / 2
        e[i] = e[i - 1] + cos(mid) * ds
        n[i] = n[i - 1] + sin(mid) * ds
        theta += kappa * ds
        val k = key(e[i], n[i])
        if (!occ.containsKey(k)) occ[k] = dist[i]
    }
    return e to n
}

/** Zahl der Stationen, die einer anderen Stelle der Strecke naeher als 45 m kommen (Laufmeterabstand > 250 m). */
internal fun conflicts(e: DoubleArray, n: DoubleArray, dist: DoubleArray, minGap: Double = 45.0): Int {
    val cell = 50.0
    val grid = HashMap<Long, MutableList<Int>>()
    fun key(cx: Long, cy: Long): Long = (cx shl 32) xor (cy and 0xffffffffL)
    for (i in e.indices step 2) grid.getOrPut(key(floor(e[i] / cell).toLong(), floor(n[i] / cell).toLong())) { ArrayList() }.add(i)
    var c = 0
    for (i in e.indices step 2) {
        val cx = floor(e[i] / cell).toLong()
        val cy = floor(n[i] / cell).toLong()
        var hit = false
        loop@ for (dx in -1L..1L) for (dy in -1L..1L) {
            val list = grid[key(cx + dx, cy + dy)] ?: continue
            for (j in list) {
                if (abs(dist[j] - dist[i]) <= 250) continue
                if (hypot(e[j] - e[i], n[j] - n[i]) < minGap) {
                    hit = true
                    break@loop
                }
            }
        }
        if (hit) c++
    }
    return c
}
