package de.trailscape.core

import de.trailscape.core.i18n.CoreTexts
import java.time.LocalDateTime
import kotlin.math.max

/**
 * Fitness / Form: CTL, ATL, TSB (§4.2–§4.4) und das Wochenziel (§6.3).
 *
 * 1:1-Portierung des entsprechenden Abschnitts aus `lib/training_load.dart`.
 */

/** Tagessumme aller Tourlasten eines Kalendertags. */
class DailyLoad(day: LocalDateTime, val load: Double) {
    val day: LocalDateTime = atMidnight(day)
}

/** Ein Tag der Performance-Management-Kurve. */
data class FitnessPoint(
    val day: LocalDateTime,
    val load: Double,
    /** Chronische Last (Fitness), EWMA τ = 42 d. */
    val ctl: Double,
    /** Akute Last (Ermuedung), EWMA τ = 7 d. */
    val atl: Double,
    /** Form: `CTL_{t−1} − ATL_{t−1}` (TrainingPeaks-Konvention). */
    val tsb: Double,
    /** CTL-Punkte pro Woche, `null` in den ersten 7 Tagen. */
    val rampRate7d: Double?,
    /**
     * Entkoppeltes EWMA-Belastungsverhaeltnis, `null` bei zu kleiner
     * chronischer Last (§4.4).
     */
    val loadRatio: Double?,
)

/** Baender der Form (§4.2). */
enum class TsbBand { SEHR_FRISCH, FORMSPITZE, NEUTRAL, PRODUKTIV, UEBERLASTUNG }

fun classifyTsb(tsb: Double): TsbBand {
    if (tsb > 25) return TsbBand.SEHR_FRISCH
    if (tsb >= 5) return TsbBand.FORMSPITZE
    if (tsb >= -10) return TsbBand.NEUTRAL
    if (tsb >= -30) return TsbBand.PRODUKTIV
    return TsbBand.UEBERLASTUNG
}

/** Baender der CTL-Rampenrate (§4.3). */
enum class RampBand { FORMVERLUST, ERHALTUNG, AUFBAU, AGGRESSIV, ZU_SCHNELL }

fun classifyRampRate(ramp: Double): RampBand {
    if (ramp < 0) return RampBand.FORMVERLUST
    if (ramp < 3) return RampBand.ERHALTUNG
    if (ramp <= 6) return RampBand.AUFBAU
    if (ramp <= 8) return RampBand.AGGRESSIV
    return RampBand.ZU_SCHNELL
}

/**
 * Bewertung des Belastungsverhaeltnisses — bewusst **nicht** als
 * „Verletzungsrisiko" benannt (§4.4).
 */
enum class LoadRatioBand { UNBEKANNT, NIEDRIG, IM_BAND, BELASTUNGSSPRUNG }

fun classifyLoadRatio(ratio: Double?): LoadRatioBand {
    if (ratio == null || !ratio.isFinite()) {
        return LoadRatioBand.UNBEKANNT
    }
    if (ratio < loadRatioBandLow) return LoadRatioBand.NIEDRIG
    if (ratio <= loadRatioBandHigh) return LoadRatioBand.IM_BAND
    return LoadRatioBand.BELASTUNGSSPRUNG
}

/** Ergebnis der PMC-Berechnung ueber eine lueckenlose Tagesserie. */
data class FitnessSeries(
    val points: List<FitnessPoint>,
    /** Anzahl der abgedeckten Kalendertage. */
    val historyDays: Int,
    /** Startwert fuer CTL und ATL (Seeding, §4.2). */
    val seedLoad: Double,
    /** Ob die Kurve im UI gezeigt werden darf (≥ 28 Tage Historie). */
    val displayReady: Boolean,
) {
    val latest: FitnessPoint? get() = points.lastOrNull()

    /** Verbleibende Tage bis zur Anzeigereife. */
    val daysUntilDisplayReady: Int get() = max(0, 28 - historyDays)

    fun at(day: LocalDateTime): FitnessPoint? {
        val target = atMidnight(day)
        for (p in points) {
            if (p.day == target) {
                return p
            }
        }
        return null
    }

    /** Die letzten [days] Punkte (hoechstens so viele, wie vorhanden sind). */
    fun lastDays(days: Int): List<FitnessPoint> {
        if (days <= 0 || points.isEmpty()) {
            return emptyList()
        }
        return points.subList(max(0, points.size - days), points.size)
    }

    companion object {
        val EMPTY = FitnessSeries(
            points = emptyList(),
            historyDays = 0,
            seedLoad = 0.0,
            displayReady = false,
        )
    }
}

/**
 * Berechnet CTL/ATL/TSB, Rampenrate und Belastungsverhaeltnis (§4.2–§4.4).
 *
 * [loads] darf Luecken, Dubletten und unsortierte Tage enthalten; es wird auf
 * eine lueckenlose Tagesserie normalisiert (Ruhetage = 0).
 */
fun computeFitnessSeries(
    loads: List<DailyLoad>,
    until: LocalDateTime? = null,
): FitnessSeries {
    val usable = loads.filter { it.load.isFinite() && it.load >= 0 }
    if (usable.isEmpty()) {
        return FitnessSeries.EMPTY
    }

    val byDay = linkedMapOf<LocalDateTime, Double>()
    for (l in usable) {
        byDay[l.day] = (byDay[l.day] ?: 0.0) + l.load
    }
    val days = byDay.keys.sorted()
    val first = days.first()
    var last = days.last()
    if (until != null) {
        val end = atMidnight(until)
        if (end.isAfter(last)) {
            last = end
        }
    }

    val dayList = mutableListOf<LocalDateTime>()
    var cursor = first
    while (!cursor.isAfter(last)) {
        dayList.add(cursor)
        cursor = addDays(cursor, 1)
    }
    val dailyLoads = dayList.map { byDay[it] ?: 0.0 }
    val historyDays = dayList.size

    // Seeding (§4.2).
    val seed: Double = if (historyDays >= 42) {
        dailyLoads.take(42).fold(0.0) { a, b -> a + b } / 42
    } else if (historyDays >= 14) {
        dailyLoads.fold(0.0) { a, b -> a + b } / historyDays
    } else {
        0.0
    }

    val lambdaAcute = 2.0 / (7 + 1)
    val lambdaChronic = 2.0 / (28 + 1)

    var ctl = seed
    var atl = seed
    var ewmaAcute = seed
    var ewmaChronic = seed
    val ctlHistory = mutableListOf<Double>()
    val chronicHistory = mutableListOf<Double>()
    val points = mutableListOf<FitnessPoint>()

    for (i in 0 until historyDays) {
        val load = dailyLoads[i]
        val tsb = ctl - atl
        ctl += lambdaCtl * (load - ctl)
        atl += lambdaAtl * (load - atl)
        ewmaAcute = load * lambdaAcute + ewmaAcute * (1 - lambdaAcute)
        ewmaChronic = load * lambdaChronic + ewmaChronic * (1 - lambdaChronic)

        ctlHistory.add(ctl)
        chronicHistory.add(ewmaChronic)

        val ramp = if (i >= 7) ctl - ctlHistory[i - 7] else null

        // Entkoppelt: der chronische Nenner stammt von vor 7 Tagen, damit die
        // akute Last nicht in beiden Termen steckt (§4.4).
        val chronicRef = if (i >= 7) chronicHistory[i - 7] else ewmaChronic
        var ratio: Double? = null
        if (chronicRef * 7 >= minChronicWeeklyLoad && chronicRef > 0) {
            ratio = ewmaAcute / chronicRef
        }

        points.add(
            FitnessPoint(
                day = dayList[i],
                load = load,
                ctl = ctl,
                atl = atl,
                tsb = tsb,
                rampRate7d = ramp,
                loadRatio = ratio,
            ),
        )
    }

    return FitnessSeries(
        points = points,
        historyDays = historyDays,
        seedLoad = seed,
        displayReady = historyDays >= 28,
    )
}

/**
 * Ein Eintrag fuer [dailyLoadsFrom] — entspricht Darts Record-Typ
 * `({DateTime at, double load})`.
 */
data class LoadEntry(val at: LocalDateTime, val load: Double)

/** Fasst Tourlasten zu Tagessummen zusammen. */
fun dailyLoadsFrom(entries: Iterable<LoadEntry>): List<DailyLoad> {
    val byDay = linkedMapOf<LocalDateTime, Double>()
    for (e in entries) {
        if (!e.load.isFinite() || e.load < 0) {
            continue
        }
        val day = atMidnight(e.at)
        byDay[day] = (byDay[day] ?: 0.0) + e.load
    }
    val days = byDay.keys.sorted()
    return days.map { DailyLoad(day = it, load = byDay[it]!!) }
}

/**
 * Standard-Zielrampe in CTL-Punkten pro Woche (§6.3).
 *
 * 4 CTL/Woche liegt mitten im Band „Nachhaltiger Aufbau" von
 * [classifyRampRate] (3…6) — genau die Rate, die die eigene Auswertung
 * hinterher nicht anmahnt. Dieselbe Zahl benutzt sowohl das empfohlene
 * Wochenziel der Auswertung (`computeInsights`) als auch das Last-Budget des
 * Trainingsplans (`generatePlan`), damit Plan und Lastmodell nicht mit zwei
 * verschiedenen Rampen rechnen.
 */
const val defaultTargetRampPerWeek: Double = 4.0

/**
 * Ein Sicherheitsdeckel des Wochenziels. Ein Typ statt eines fertigen Satzes:
 * Der Plan-Generator rechnet mit dem Ziel, ohne je Text zu brauchen; den Satz
 * baut erst die Anzeige ueber `LoadTexts.weeklyCap…`.
 */
enum class WeeklyLoadCap {
    /** Hoechstens 130 % der letzten vier Wochen. */
    RECENT_WEEKS,

    /** Hoechstens das Zeitbudget aus dem Profil ([WeeklyLoadTarget.weeklyHours]). */
    TIME_BUDGET,
}

/** Empfohlene Wochenlast fuer eine Zielrampe (§6.3). */
data class WeeklyLoadTarget(
    val targetRamp: Double,
    val dailyLoad: Double,
    val weeklyLoad: Double,
    /** Welche Sicherheitsdeckel gegriffen haben, in der Reihenfolge der Pruefung. */
    val caps: List<WeeklyLoadCap>,
    /** Hinterlegtes Zeitbudget in Stunden pro Woche, falls vorhanden. */
    val weeklyHours: Double? = null,
) {
    /**
     * Fahrzeit, die dieser Zielwert bei gemischter Woche ungefaehr bedeutet
     * ([weeklyLoadPerHour]).
     */
    val estimatedHours: Double get() = weeklyLoad / weeklyLoadPerHour
}

/**
 * Empfohlene Wochenlast fuer eine Zielrampe (§6.3).
 *
 * [weeklyHours] ist das Zeitbudget aus dem Profil: Mehr als
 * `weeklyHours × weeklyLoadPerHour` ist in der Woche schlicht nicht fahrbar,
 * deshalb deckelt es das Ziel zusaetzlich zum 130-%-Deckel.
 */
fun weeklyLoadTarget(
    ctl: Double,
    targetRamp: Double,
    recentWeeklyMean: Double? = null,
    weeklyHours: Double? = null,
): WeeklyLoadTarget {
    val daily = max(ctl + targetRamp / ctlWeeklyResponse, 0.0)
    var weekly = 7 * daily
    val caps = mutableListOf<WeeklyLoadCap>()

    if (recentWeeklyMean != null && recentWeeklyMean > 0) {
        val cap = 1.30 * recentWeeklyMean
        if (weekly > cap) {
            weekly = cap
            caps.add(WeeklyLoadCap.RECENT_WEEKS)
        }
    }
    if (weeklyHours != null && weeklyHours > 0) {
        val cap = weeklyHours * weeklyLoadPerHour
        if (weekly > cap) {
            weekly = cap
            caps.add(WeeklyLoadCap.TIME_BUDGET)
        }
    }

    return WeeklyLoadTarget(
        targetRamp = targetRamp,
        dailyLoad = weekly / 7,
        weeklyLoad = weekly,
        caps = caps,
        weeklyHours = weeklyHours,
    )
}

/**
 * Satz zu einem Deckel des Wochenziels in der Sprache von [texts].
 */
fun weeklyLoadCapText(cap: WeeklyLoadCap, target: WeeklyLoadTarget, texts: CoreTexts): String =
    when (cap) {
        WeeklyLoadCap.RECENT_WEEKS -> texts.load.weeklyCapRecentWeeks()
        WeeklyLoadCap.TIME_BUDGET -> texts.load.weeklyCapTimeBudget(target.weeklyHours ?: 0.0)
    }

/**
 * Stundenangabe ohne Einheit: ganze Zahlen ohne Nachkommastelle, sonst eine
 * Stelle — „4,5" auf Deutsch, „4.5" auf Englisch.
 */
fun formatHours(hours: Double, texts: CoreTexts): String = texts.format.hours(hours)

/** Ziel-Intensitaetsverteilung LIT : MIT : HIT in Prozent (§6.3). */
fun intensityDistributionTarget(polarized: Boolean = true): List<Double> =
    if (polarized) listOf(80.0, 5.0, 15.0) else listOf(75.0, 15.0, 10.0)
