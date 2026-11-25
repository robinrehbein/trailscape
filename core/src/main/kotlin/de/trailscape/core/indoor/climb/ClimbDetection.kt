package de.trailscape.core.indoor.climb

import de.trailscape.core.indoor.IndoorRoute
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * # Bergwertung: Anstiege aus dem Hoehenprofil
 *
 * Erkennt die Anstiege einer Strecke allein aus ihrem Hoehenprofil
 * (Laufmeter → Hoehe), ohne Netz und ohne Kartendaten. Ergebnis sind
 * unveraenderliche [Climb]s in Fahrtrichtung — die Grundlage fuer die
 * Ansage vor dem Anstieg, die Einblendung am Anstieg, die Bestzeiten
 * ([ClimbBestBook]) und spaeter fuer Abzeichen.
 *
 * ## Verfahren ([detectClimbs])
 *  1. Das Profil wird in Schritten von [STEP_M] neu abgetastet (linear
 *     zwischen den Punkten). Eine `IndoorRoute` ist schon geglaettet; die
 *     Schrittweite daempft verbleibendes Rauschen zusaetzlich.
 *  2. Ein Schritt „steigt", wenn seine Steigung mindestens
 *     [STEP_MIN_GRADE_PCT] betraegt. Aufeinanderfolgende steigende Schritte
 *     bilden einen Lauf.
 *  3. **Kurze Flachstuecke werden zusammengefasst:** Zwei Laeufe gehoeren zu
 *     einem Anstieg, wenn die Luecke dazwischen hoechstens [MAX_FLAT_GAP_M]
 *     lang ist und darin hoechstens [MAX_DIP_M] Hoehe verloren geht (kurze
 *     Flachstuecke, Kehren, eine kleine Senke).
 *  4. Ein Kandidat zaehlt als Anstieg, wenn er mindestens [MIN_LENGTH_M] lang
 *     ist, im Mittel mindestens [MIN_AVG_GRADE_PCT] steigt und seine
 *     Wertungszahl (Laenge in m × mittlere Steigung in %) mindestens
 *     [MIN_SCORE] erreicht.
 *  5. Die Kategorie folgt der Wertungszahl ([ClimbCategory.of]), angelehnt an
 *     verbreitete Bergwertungen fuer Radfahrer (keine offizielle Einteilung).
 *
 * Anstiege laufen nie ueber das Streckenende hinaus, auch nicht auf einer
 * Runde (ein Anstieg, der ueber die Ziellinie fuehrt, wird an ihr geteilt).
 */
object ClimbDetection {
    /** Schrittweite der Neuabtastung in Metern. */
    const val STEP_M: Double = 50.0

    /** Ab dieser Steigung (in %) zaehlt ein Schritt als steigend. */
    const val STEP_MIN_GRADE_PCT: Double = 2.0

    /** Laengstes Flachstueck (in m), das zwei steigende Laeufe noch verbindet. */
    const val MAX_FLAT_GAP_M: Double = 300.0

    /** Groesster Hoehenverlust (in m) in einem verbindenden Flachstueck. */
    const val MAX_DIP_M: Double = 10.0

    /** Mindestlaenge eines Anstiegs in Metern. */
    const val MIN_LENGTH_M: Double = 400.0

    /** Mindeststeigung im Mittel in Prozent. */
    const val MIN_AVG_GRADE_PCT: Double = 3.0

    /** Mindest-Wertungszahl (Laenge in m × mittlere Steigung in %), z. B. 1 km bei 3 %. */
    const val MIN_SCORE: Double = 3000.0

    /** Fenster fuer die steilste Stelle ([Climb.maxGradePct]) in Metern. */
    const val MAX_GRADE_WINDOW_M: Double = 100.0
}

/**
 * Kategorie eines Anstiegs nach der Wertungszahl `Laenge (m) × mittlere
 * Steigung (%)`. Die Schwellen sind [minScore]; [UNCATEGORIZED] ist ein
 * Anstieg unterhalb der vierten Kategorie.
 *
 * @property jsonName stabiler Name fuer Speicherung und spaetere Abzeichen.
 */
enum class ClimbCategory(val minScore: Double, val jsonName: String) {
    UNCATEGORIZED(ClimbDetection.MIN_SCORE, "none"),
    CAT4(8_000.0, "4"),
    CAT3(16_000.0, "3"),
    CAT2(32_000.0, "2"),
    CAT1(64_000.0, "1"),
    HC(80_000.0, "hc"),
    ;

    companion object {
        /** Kategorie zur Wertungszahl [score]; unter [ClimbDetection.MIN_SCORE] ebenfalls [UNCATEGORIZED]. */
        fun of(score: Double): ClimbCategory = entries.lastOrNull { score >= it.minScore } ?: UNCATEGORIZED

        fun fromJsonName(name: String?): ClimbCategory? = entries.firstOrNull { it.jsonName == name }
    }
}

/**
 * Ein erkannter Anstieg.
 *
 * @property index Nummer in Fahrtrichtung, ab 0.
 * @property startM Laufmeter des Fusses (innerhalb der Runde).
 * @property endM Laufmeter der Kuppe.
 * @property gainM Hoehenunterschied Fuss → Kuppe (immer > 0).
 * @property avgGradePct mittlere Steigung in Prozent.
 * @property maxGradePct steilste Stelle ueber [ClimbDetection.MAX_GRADE_WINDOW_M].
 * @property topEleM Hoehe der Kuppe (Passhoehe) in m ueber dem Meer.
 */
data class Climb(
    val index: Int,
    val startM: Double,
    val endM: Double,
    val gainM: Double,
    val avgGradePct: Double,
    val maxGradePct: Double,
    val startEleM: Double,
    val topEleM: Double,
    val category: ClimbCategory,
) {
    val lengthM: Double get() = endM - startM

    /** Wertungszahl: Laenge in m × mittlere Steigung in %. */
    val score: Double get() = lengthM * avgGradePct
}

/**
 * Stabiler Schluessel eines Anstiegs auf einer Strecke, z. B.
 * `ride:abc@1250-3400` — Strecke ([routeKey], die Streckenwahl-Kennung der
 * App) und Lage auf 10 m gerundet. Fuer Abzeichen und Anzeige; Bestzeiten
 * werden zusaetzlich mit Toleranz zugeordnet ([ClimbBestBook.bestFor]), weil
 * sich dieselbe Strecke nach einem neuen Streckenbau um einige Meter
 * verschieben kann.
 */
fun climbKey(routeKey: String, climb: Climb): String =
    "$routeKey@${round10(climb.startM)}-${round10(climb.endM)}"

private fun round10(m: Double): Long = (m / 10.0).roundToLong() * 10

/** Anstiege einer Strecke (siehe [ClimbDetection]). */
fun detectClimbs(route: IndoorRoute): List<Climb> =
    detectClimbs(
        DoubleArray(route.points.size) { route.points[it].distM },
        DoubleArray(route.points.size) { route.points[it].eleM },
    )

/**
 * Anstiege eines Profils aus Laufmetern [distM] (streng steigend, ab 0) und
 * Hoehen [eleM] gleicher Laenge. Unbrauchbare Eingaben (zu kurz, nicht
 * endlich, ungleich lang) ergeben eine leere Liste, nie einen Fehler.
 */
fun detectClimbs(distM: DoubleArray, eleM: DoubleArray): List<Climb> {
    if (distM.size < 2 || distM.size != eleM.size) return emptyList()
    if (distM.any { !it.isFinite() } || eleM.any { !it.isFinite() }) return emptyList()
    val length = distM.last() - distM.first()
    if (length < ClimbDetection.MIN_LENGTH_M) return emptyList()

    // 1. Neu abtasten.
    val step = ClimbDetection.STEP_M
    val n = (length / step).toInt()
    val d = DoubleArray(n + 2)
    var count = 0
    for (k in 0..n) d[count++] = distM.first() + k * step
    if (d[count - 1] < distM.last() - 1e-6) d[count++] = distM.last()
    val sd = d.copyOf(count)
    val se = DoubleArray(count)
    var j = 0
    for (i in 0 until count) {
        while (j < distM.size - 2 && distM[j + 1] < sd[i]) j++
        val span = distM[j + 1] - distM[j]
        val t = if (span > 0) ((sd[i] - distM[j]) / span).coerceIn(0.0, 1.0) else 0.0
        se[i] = eleM[j] + (eleM[j + 1] - eleM[j]) * t
    }

    // 2. Steigende Schritte zu Laeufen [von, bis] (Indizes der Abtastpunkte).
    fun climbing(k: Int): Boolean {
        val span = sd[k + 1] - sd[k]
        return span > 0 && (se[k + 1] - se[k]) / span * 100.0 >= ClimbDetection.STEP_MIN_GRADE_PCT
    }
    val runs = ArrayList<IntArray>()
    var k = 0
    while (k < count - 1) {
        if (!climbing(k)) { k++; continue }
        val from = k
        while (k < count - 1 && climbing(k)) k++
        runs.add(intArrayOf(from, k))
    }

    // 3. Kurze Flachstuecke zusammenfassen.
    val merged = ArrayList<IntArray>()
    for (r in runs) {
        val last = merged.lastOrNull()
        if (last != null) {
            val gapM = sd[r[0]] - sd[last[1]]
            var low = se[last[1]]
            for (i in last[1]..r[0]) low = minOf(low, se[i])
            if (gapM <= ClimbDetection.MAX_FLAT_GAP_M && se[last[1]] - low <= ClimbDetection.MAX_DIP_M) {
                last[1] = r[1]
                continue
            }
        }
        merged.add(intArrayOf(r[0], r[1]))
    }

    // 4./5. Pruefen und beschreiben.
    val out = ArrayList<Climb>()
    for (m in merged) {
        val a = m[0]
        val b = m[1]
        val len = sd[b] - sd[a]
        val gain = se[b] - se[a]
        if (len < ClimbDetection.MIN_LENGTH_M || gain <= 0) continue
        val avg = gain / len * 100.0
        if (avg < ClimbDetection.MIN_AVG_GRADE_PCT || len * avg < ClimbDetection.MIN_SCORE) continue
        out.add(
            Climb(
                index = out.size,
                startM = sd[a],
                endM = sd[b],
                gainM = gain,
                avgGradePct = avg,
                maxGradePct = steepest(sd, se, a, b, avg),
                startEleM = se[a],
                topEleM = se[b],
                category = ClimbCategory.of(len * avg),
            ),
        )
    }
    return out
}

/** Steilste Stelle ueber [ClimbDetection.MAX_GRADE_WINDOW_M] zwischen den Abtastpunkten [a] und [b]. */
private fun steepest(sd: DoubleArray, se: DoubleArray, a: Int, b: Int, fallback: Double): Double {
    var best = fallback
    for (i in a until b) {
        var e = i + 1
        while (e < b && sd[e] - sd[i] < ClimbDetection.MAX_GRADE_WINDOW_M - 1e-6) e++
        val span = sd[e] - sd[i]
        if (span <= 0) continue
        best = max(best, (se[e] - se[i]) / span * 100.0)
    }
    return best
}
