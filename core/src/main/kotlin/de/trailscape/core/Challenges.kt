package de.trailscape.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * # Monats-Challenges — ein Grund, jeden Monat neu loszufahren
 *
 * Die Wochenserie und das Jahresziel in `Motivation.kt` sind langfristig.
 * Eine Monats-Challenge setzt dazwischen einen Rahmen, der am Monatsersten
 * von vorn beginnt: Wer im Oktober schwach war, hat im November wieder eine
 * saubere Tafel.
 *
 * Die Ziele kommen **nur aus der eigenen Historie** — kein Server, keine
 * anderen Fahrer. Sie sind so bemessen, dass sie erreichbar, aber nicht
 * geschenkt sind: ein kleiner Aufschlag auf das, was man zuletzt ohnehin
 * gefahren ist.
 *
 * Wie der Rest von `:core` ist alles reine Rechnung ueber [RideFact]s: kein
 * Android, keine Uhr, keine Zeitzone, keine Zufallswerte. „Heute" kommt als
 * Parameter herein. Sichtbare Saetze baut die Oberflaeche aus diesen Daten.
 */

enum class ChallengeKind {
    /** Kilometer im Monat. */
    DISTANCE,

    /** Hoehenmeter im Monat. */
    ASCENT,

    /** Kalendertage mit mindestens einer Tour. */
    ACTIVE_DAYS,

    /** Weiteste einzelne Tour des Monats in km. */
    LONG_RIDE,
}

/**
 * Eine Monats-Challenge.
 *
 * @property target km, m, Tage bzw. km (einzelne Tour) — je nach [kind].
 */
data class Challenge(
    val kind: ChallengeKind,
    val month: YearMonth,
    val target: Double,
)

/**
 * Stand einer [Challenge].
 *
 * @property current km / m / Tage bisher im Monat; bei [ChallengeKind.LONG_RIDE]
 *   die weiteste Tour des Monats (nicht die Summe).
 * @property daysLeft Tage bis einschliesslich Monatsende, heute eingeschlossen
 *   (1…31); 0 fuer vergangene Monate.
 */
data class ChallengeProgress(
    val challenge: Challenge,
    val current: Double,
    val daysLeft: Int,
) {
    val fraction: Double
        get() = if (challenge.target > 0) (current / challenge.target).coerceIn(0.0, 1.0) else 0.0
    val reached: Boolean get() = challenge.target > 0 && current >= challenge.target
    val remaining: Double get() = (challenge.target - current).coerceAtLeast(0.0)
}

/** Wie viele Monate vor dem Zielmonat die Referenz umfasst. */
const val challengeReferenceMonths: Int = 3

/**
 * Aufschlag auf Kilometer und Hoehenmeter. Zehn Prozent ist die bekannte
 * Steigerungsregel (siehe [stretchFactor]) — spuerbar, aber kein Sprung.
 */
const val challengeVolumeFactor: Double = 1.10

/** Aufschlag auf die weiteste Tour; kleiner, weil eine Tour kein Durchschnitt ist. */
const val challengeLongRideFactor: Double = 1.05

/** Rundungsschritte der Ziele; unterhalb der Schwelle feiner, damit kleine Ziele nicht aufgeblaeht werden. */
const val challengeDistanceStepKm: Double = 25.0
const val challengeDistanceFineStepKm: Double = 10.0
const val challengeDistanceFineBelowKm: Double = 100.0
const val challengeAscentStepM: Double = 250.0
const val challengeAscentFineStepM: Double = 100.0
const val challengeAscentFineBelowM: Double = 1000.0
const val challengeLongRideStepKm: Double = 5.0

/** Grenzen der Zieltage und der Einzeltour. */
const val challengeMinActiveDays: Int = 4
const val challengeMaxActiveDays: Int = 20
const val challengeMaxLongRideKm: Double = 200.0

/**
 * Unter diesem Monatsschnitt bekommt man keine Hoehenmeter-Challenge: Wer im
 * Flachland faehrt, soll nicht mit einem Ziel starten, das die Gegend nicht
 * hergibt.
 */
const val challengeMinAscentM: Double = 200.0

/** Startziele, solange es keine Referenztouren gibt (neuer Nutzer, drei leere Monate). */
const val starterDistanceKm: Double = 100.0
const val starterActiveDays: Double = 4.0
const val starterLongRideKm: Double = 30.0

/**
 * Rundet [value] auf das naechste Vielfache von [step] auf. Das Epsilon
 * faengt 100 × 1,1 = 110,00000000000001 ab, das sonst einen Schritt weiter
 * spraenge (wie bei [stretchChallenge]).
 */
private fun roundUp(value: Double, step: Double): Double = ceil(value / step - 1e-9) * step

private data class MonthStats(
    val km: Double,
    val ascentM: Double,
    val activeDays: Int,
    val longestKm: Double,
)

private fun statsOf(rides: List<RideFact>) = MonthStats(
    km = rides.sumOf { it.distanceKm },
    ascentM = rides.sumOf { it.ascentM },
    // Zwei Touren am selben Tag sind ein aktiver Tag.
    activeDays = rides.map { it.date }.toSet().size,
    longestKm = rides.maxOfOrNull { it.distanceKm } ?: 0.0,
)

private fun ridesIn(facts: List<RideFact>, month: YearMonth, until: LocalDate? = null): List<RideFact> =
    facts.filter {
        YearMonth.from(it.date) == month && (until == null || !it.date.isAfter(until))
    }

/**
 * Die Challenges von [month], abgeleitet **ausschliesslich aus Touren vor dem
 * Ersten dieses Monats**. Damit bleiben die Ziele den ganzen Monat stehen:
 * Eine lange Tour am 3. verschiebt nicht das Ziel vom 4. — sonst liefe man
 * seinem eigenen Ziel davon.
 *
 * Referenz sind die drei Kalendermonate davor. Je Art zaehlt der Schnitt
 * ueber die davon, in denen ueberhaupt gefahren wurde: Ein Verletzungsmonat
 * wird uebersprungen statt als null mitgerechnet, sonst haelbierte er das
 * Ziel. Ohne jede Referenztour gilt der Startsatz (ohne Hoehenmeter).
 *
 * Die Reihenfolge ist immer die von [ChallengeKind].
 */
fun monthlyChallenges(facts: List<RideFact>, month: YearMonth): List<Challenge> {
    val stats = (1..challengeReferenceMonths)
        .map { ridesIn(facts, month.minusMonths(it.toLong())) }
        .filter { it.isNotEmpty() }
        .map(::statsOf)

    fun challenge(kind: ChallengeKind, target: Double) = Challenge(kind, month, target)

    if (stats.isEmpty()) {
        return listOf(
            challenge(ChallengeKind.DISTANCE, starterDistanceKm),
            challenge(ChallengeKind.ACTIVE_DAYS, starterActiveDays),
            challenge(ChallengeKind.LONG_RIDE, starterLongRideKm),
        )
    }

    val avgKm = stats.map { it.km }.average()
    val avgAscent = stats.map { it.ascentM }.average()
    val avgDays = stats.map { it.activeDays }.average()
    // Der Schnitt der laengsten Touren je Monat, nicht das Maximum: Ein
    // einzelnes 180-km-Event im September machte sonst drei Monate lang
    // „eine Tour ueber 190 km" zur Challenge — fuer jemanden, der sonst 60 faehrt.
    val longest = stats.map { it.longestKm }.average()

    val result = mutableListOf<Challenge>()

    val rawKm = avgKm * challengeVolumeFactor
    val kmStep = if (rawKm < challengeDistanceFineBelowKm) challengeDistanceFineStepKm else challengeDistanceStepKm
    result += challenge(ChallengeKind.DISTANCE, max(roundUp(rawKm, kmStep), kmStep))

    if (avgAscent >= challengeMinAscentM) {
        val rawAscent = avgAscent * challengeVolumeFactor
        val step = if (rawAscent < challengeAscentFineBelowM) challengeAscentFineStepM else challengeAscentStepM
        result += challenge(ChallengeKind.ASCENT, max(roundUp(rawAscent, step), step))
    }

    val days = (avgDays.roundToInt() + 1).coerceIn(challengeMinActiveDays, challengeMaxActiveDays)
    result += challenge(ChallengeKind.ACTIVE_DAYS, days.toDouble())

    val rawLong = longest * challengeLongRideFactor
    result += challenge(
        ChallengeKind.LONG_RIDE,
        min(max(roundUp(rawLong, challengeLongRideStepKm), challengeLongRideStepKm), challengeMaxLongRideKm),
    )
    return result
}

private fun progressOf(
    challenges: List<Challenge>,
    monthRides: List<RideFact>,
    daysLeft: Int,
): List<ChallengeProgress> {
    val stats = statsOf(monthRides)
    return challenges.map {
        val current = when (it.kind) {
            ChallengeKind.DISTANCE -> stats.km
            ChallengeKind.ASCENT -> stats.ascentM
            ChallengeKind.ACTIVE_DAYS -> stats.activeDays.toDouble()
            ChallengeKind.LONG_RIDE -> stats.longestKm
        }
        ChallengeProgress(it, current, daysLeft)
    }
}

/**
 * Stand der Challenges des Monats, in dem [today] liegt. Touren nach [today]
 * zaehlen nicht (z. B. fehlerhafte Zeitstempel).
 */
fun challengeProgress(facts: List<RideFact>, today: LocalDate): List<ChallengeProgress> {
    val month = YearMonth.from(today)
    return progressOf(
        monthlyChallenges(facts, month),
        ridesIn(facts, month, until = today),
        daysLeft = month.lengthOfMonth() - today.dayOfMonth + 1,
    )
}

/**
 * Bilanz eines vergangenen Monats.
 *
 * @property reached erreichte Challenges.
 * @property total Challenges dieses Monats (je nach Historie 3 oder 4).
 */
data class ChallengeMonth(val month: YearMonth, val reached: Int, val total: Int)

/**
 * Wie viele Challenges in den vergangenen Monaten erreicht wurden, neuester
 * zuerst. Betrachtet werden die letzten [months] Kalendermonate vor dem
 * laufenden (der laufende zaehlt nicht, er ist noch offen); Monate ohne jede
 * Tour fehlen — eine Pause ist kein Misserfolg. Jeder Monat wird an seinen
 * eigenen Zielen gemessen, die nur aus den Touren davor stammen.
 */
fun challengeHistory(facts: List<RideFact>, today: LocalDate, months: Int = 12): List<ChallengeMonth> {
    val current = YearMonth.from(today)
    return (1..months).mapNotNull { back ->
        val month = current.minusMonths(back.toLong())
        val rides = ridesIn(facts, month)
        if (rides.isEmpty()) return@mapNotNull null
        val progress = progressOf(monthlyChallenges(facts, month), rides, daysLeft = 0)
        ChallengeMonth(month, reached = progress.count { it.reached }, total = progress.size)
    }
}
