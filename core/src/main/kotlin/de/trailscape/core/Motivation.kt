package de.trailscape.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * # Dranbleiben — Serie, Bestwerte, Meilensteine, Jahresziel, Bestweite
 *
 * Die Trainingsrechnung (`PerformanceManagement.kt`, `Readiness.kt`) sagt, was
 * heute *vernuenftig* ist. Diese Datei beantwortet die andere Frage: **Lohnt
 * es sich, heute aufs Rad zu steigen?** — mit Dingen, die man ohne Fachwort
 * versteht und die mit jeder Fahrt sichtbar wachsen.
 *
 * Alles hier ist reine Rechnung ueber [RideFact]s: kein Android, keine Uhr,
 * keine Zeitzone. Den Kalendertag einer Tour bestimmt der Aufrufer
 * ([rideFacts] bekommt dafuer `dateOf`), „heute" kommt als Parameter herein —
 * so bleibt jede Funktion mit festen Daten testbar.
 *
 * Geplante Routen zaehlen nirgends mit: [rideFacts] laesst sie weg.
 */

/** Was die Motivationsrechnung von einer gefahrenen Tour wissen muss. */
data class RideFact(
    val id: String,
    /** Kalendertag der Tour in der Zeitzone des Nutzers. */
    val date: LocalDate,
    val distanceKm: Double,
    val ascentM: Double,
    /** Fahrzeit in Sekunden — Bewegungszeit, ersatzweise Gesamtdauer. */
    val timeS: Int?,
    val avgSpeedKmh: Double?,
)

/**
 * Die gefahrenen Touren als [RideFact]s, aelteste zuerst. Bei gleichem Tag
 * entscheidet der Aufzeichnungszeitpunkt — die Reihenfolge ist damit dieselbe,
 * in der die Touren tatsaechlich gefahren wurden, und [recordsSetBy] kann
 * „alle frueheren" als „alle davor in der Liste" lesen.
 */
fun <T : RideInfo> rideFacts(rides: List<T>, dateOf: (Long) -> LocalDate): List<RideFact> =
    riddenRides(rides)
        .sortedBy { it.createdAt }
        .map {
            RideFact(
                id = it.id,
                date = dateOf(it.createdAt),
                distanceKm = it.stats.distanceKm.takeIf(Double::isFinite) ?: 0.0,
                ascentM = it.stats.ascentM.takeIf(Double::isFinite) ?: 0.0,
                timeS = it.stats.movingTimeS ?: it.stats.durationS,
                avgSpeedKmh = it.stats.avgSpeedKmh?.takeIf(Double::isFinite),
            )
        }

private fun mondayOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

// ---------------------------------------------------------------------------
// Wochenserie
// ---------------------------------------------------------------------------

/**
 * Die Wochenserie: wie viele Kalenderwochen (Mo–So) in Folge mindestens eine
 * Tour hatten.
 *
 * Bewusst **Wochen statt Tage**. Eine Tagesserie bestraft Ruhetage und
 * erzieht zu genau dem, wovor die Bereitschaftsrechnung warnt. Eine
 * Wochenserie verlangt nur, dranzubleiben — und sie laesst sich auch nach
 * einer verregneten Woche am Sonntag noch retten.
 *
 * @property weeks Laenge der laufenden Serie. Die aktuelle Woche zaehlt mit,
 *   sobald in ihr gefahren wurde. Ist sie noch leer, steht hier die Serie bis
 *   einschliesslich letzter Woche — sie *lebt* noch, siehe [thisWeekDone].
 * @property thisWeekDone ob diese Woche schon eine Tour hat.
 * @property best laengste Serie ueberhaupt (mindestens [weeks]).
 * @property daysLeft Tage bis einschliesslich Sonntag, an denen die Serie
 *   diese Woche noch zu halten ist (heute zaehlt mit, 1…7).
 */
data class WeekStreak(
    val weeks: Int,
    val thisWeekDone: Boolean,
    val best: Int,
    val daysLeft: Int,
) {
    /** Eine Serie laeuft, ist diese Woche aber noch nicht verlaengert. */
    val atRisk: Boolean get() = weeks > 0 && !thisWeekDone
}

fun weekStreak(facts: List<RideFact>, today: LocalDate): WeekStreak {
    val thisMonday = mondayOf(today)
    val weeksWithRide = facts.asSequence()
        .filter { !it.date.isAfter(today) }
        .map { mondayOf(it.date) }
        .toSortedSet()

    val thisWeekDone = thisMonday in weeksWithRide
    var cursor = if (thisWeekDone) thisMonday else thisMonday.minusWeeks(1)
    var current = 0
    while (cursor in weeksWithRide) {
        current += 1
        cursor = cursor.minusWeeks(1)
    }

    var best = 0
    var run = 0
    var previous: LocalDate? = null
    for (monday in weeksWithRide) {
        run = if (previous != null && previous.plusWeeks(1) == monday) run + 1 else 1
        best = max(best, run)
        previous = monday
    }

    return WeekStreak(
        weeks = current,
        thisWeekDone = thisWeekDone,
        best = max(best, current),
        daysLeft = 8 - today.dayOfWeek.value,
    )
}

// ---------------------------------------------------------------------------
// Bestwerte
// ---------------------------------------------------------------------------

enum class RecordKind {
    /** Weiteste Tour in km. */
    DISTANCE,

    /** Meiste Hoehenmeter einer Tour. */
    ASCENT,

    /** Laengste Fahrzeit in Sekunden. */
    TIME,

    /** Hoechster Schnitt in km/h — nur Touren ab [fastestMinKm]. */
    SPEED,
}

/**
 * Mindestlaenge fuer den Tempo-Bestwert. Ohne sie haelt die 3-km-Fahrt zum
 * Baecker mit Rueckenwind den Rekord fuer immer.
 */
const val fastestMinKm: Double = 20.0

/** Schnitte ausserhalb dieser Spanne sind Messfehler, keine Bestwerte. */
private val plausibleSpeedKmh = 5.0..60.0

/**
 * Untergrenzen, ab denen ein Wert als Bestwert *gemeldet* wird. Eine Runde um
 * den Block ist formal die „weiteste Tour", wenn es die zweite ueberhaupt
 * ist — gefeiert wird das nicht.
 */
private fun worthReporting(kind: RecordKind, value: Double): Boolean = when (kind) {
    RecordKind.DISTANCE -> value >= 5.0
    RecordKind.ASCENT -> value >= 100.0
    RecordKind.TIME -> value >= 20 * 60
    RecordKind.SPEED -> true
}

private fun valueOf(fact: RideFact, kind: RecordKind): Double? = when (kind) {
    RecordKind.DISTANCE -> fact.distanceKm.takeIf { it > 0 }
    RecordKind.ASCENT -> fact.ascentM.takeIf { it > 0 }
    RecordKind.TIME -> fact.timeS?.takeIf { it > 0 }?.toDouble()
    RecordKind.SPEED -> fact.avgSpeedKmh?.takeIf { fact.distanceKm >= fastestMinKm && it in plausibleSpeedKmh }
}

/** Ein Bestwert samt der Tour, die ihn haelt. */
data class RideRecord(
    val kind: RecordKind,
    /** km, m, s oder km/h — je nach [kind]. */
    val value: Double,
    val rideId: String,
    val date: LocalDate,
)

/**
 * Die aktuellen Bestwerte, in der Reihenfolge von [RecordKind]. Eine Art ohne
 * verwertbare Tour fehlt. Bei Gleichstand haelt die fruehere Tour den Wert.
 */
fun personalRecords(facts: List<RideFact>): List<RideRecord> =
    RecordKind.entries.mapNotNull { kind ->
        var best: RideRecord? = null
        for (fact in facts) {
            val value = valueOf(fact, kind) ?: continue
            if (best == null || value > best.value) {
                best = RideRecord(kind, value, fact.id, fact.date)
            }
        }
        best?.takeIf { worthReporting(kind, it.value) }
    }

/** Ein Bestwert, den eine Tour gesetzt hat, mit dem Wert, den sie abloest. */
data class RecordGain(
    val kind: RecordKind,
    val value: Double,
    /** Der bisherige Bestwert aller frueheren Touren. */
    val previous: Double,
)

/**
 * Welche Bestwerte die Tour [rideId] **zum Zeitpunkt ihrer Fahrt** gesetzt
 * hat — verglichen wird nur mit frueheren Touren. Eine alte Tour behaelt so
 * ihre Auszeichnung, auch wenn sie laengst uebertroffen ist („damals deine
 * weiteste").
 *
 * Die allererste Tour mit einem Messwert setzt nichts: Ohne Vergleich ist es
 * kein Bestwert, sondern ein Anfang ([milestonesReachedBy] feiert den).
 */
fun recordsSetBy(rideId: String, facts: List<RideFact>): List<RecordGain> {
    val index = facts.indexOfFirst { it.id == rideId }
    if (index < 0) return emptyList()
    val ride = facts[index]
    val earlier = facts.subList(0, index)
    return RecordKind.entries.mapNotNull { kind ->
        val value = valueOf(ride, kind) ?: return@mapNotNull null
        val previous = earlier.mapNotNull { valueOf(it, kind) }.maxOrNull() ?: return@mapNotNull null
        if (value > previous && worthReporting(kind, value)) RecordGain(kind, value, previous) else null
    }
}

// ---------------------------------------------------------------------------
// Meilensteine
// ---------------------------------------------------------------------------

enum class MilestoneKind {
    /** Gesamtkilometer aller Touren. */
    TOTAL_KM,

    /** Gesamte Hoehenmeter aller Touren. */
    TOTAL_ASCENT,

    /** Anzahl der Touren. */
    RIDE_COUNT,

    /** Weiteste einzelne Tour in km. */
    LONGEST_RIDE,
}

/** Hoehe des Mount Everest in m — die eine Schwelle mit Namen. */
const val everestM: Double = 8849.0

/** Erdumfang am Aequator in km. */
const val earthCircumferenceKm: Double = 40075.0

/**
 * Die Leitern. Unten eng gestuft, damit die ersten Wochen etwas zu holen
 * haben; oben weit, damit sie nach Jahren nicht ausgehen.
 */
fun milestoneLadder(kind: MilestoneKind): List<Double> = when (kind) {
    MilestoneKind.TOTAL_KM -> listOf(
        50.0, 100.0, 250.0, 500.0, 1000.0, 1500.0, 2000.0, 3000.0, 5000.0, 7500.0,
        10000.0, 15000.0, 20000.0, 30000.0, earthCircumferenceKm, 50000.0, 75000.0, 100000.0,
    )
    MilestoneKind.TOTAL_ASCENT -> listOf(
        500.0, 1000.0, 2500.0, 5000.0, everestM, 15000.0, 25000.0, 50000.0,
        10 * everestM, 150000.0, 250000.0,
    )
    MilestoneKind.RIDE_COUNT -> listOf(1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 200.0, 365.0, 500.0, 1000.0)
    MilestoneKind.LONGEST_RIDE -> listOf(25.0, 40.0, 60.0, 80.0, 100.0, 130.0, 160.0, 200.0, 300.0)
}

private fun milestoneValue(facts: List<RideFact>, kind: MilestoneKind): Double = when (kind) {
    MilestoneKind.TOTAL_KM -> facts.sumOf { it.distanceKm }
    MilestoneKind.TOTAL_ASCENT -> facts.sumOf { it.ascentM }
    MilestoneKind.RIDE_COUNT -> facts.size.toDouble()
    MilestoneKind.LONGEST_RIDE -> facts.maxOfOrNull { it.distanceKm } ?: 0.0
}

/** Eine erreichte oder anstehende Stufe. */
data class Milestone(val kind: MilestoneKind, val threshold: Double)

/**
 * Stand auf einer Leiter.
 *
 * @property current der aktuelle Wert (km, m, Anzahl).
 * @property reached Anzahl erreichter Stufen.
 * @property last die zuletzt erreichte Stufe, `null` vor der ersten.
 * @property next die naechste Stufe, `null` oberhalb der Leiter.
 * @property fraction Weg von [last] (bzw. 0) nach [next], 0…1.
 */
data class MilestoneProgress(
    val kind: MilestoneKind,
    val current: Double,
    val reached: Int,
    val last: Double?,
    val next: Double?,
    val fraction: Double,
) {
    /** Was bis zur naechsten Stufe fehlt; 0 oberhalb der Leiter. */
    val remaining: Double get() = next?.let { (it - current).coerceAtLeast(0.0) } ?: 0.0
}

fun milestoneProgress(facts: List<RideFact>, kind: MilestoneKind): MilestoneProgress {
    val current = milestoneValue(facts, kind)
    val ladder = milestoneLadder(kind)
    val reached = ladder.count { current >= it }
    val last = ladder.getOrNull(reached - 1)
    val next = ladder.getOrNull(reached)
    val base = last ?: 0.0
    val fraction = if (next == null) 1.0 else ((current - base) / (next - base)).coerceIn(0.0, 1.0)
    return MilestoneProgress(kind, current, reached, last, next, fraction)
}

fun milestoneProgress(facts: List<RideFact>): List<MilestoneProgress> =
    MilestoneKind.entries.map { milestoneProgress(facts, it) }

/**
 * Die Leiter, auf der die naechste Stufe am naechsten liegt — das eine
 * Ziel, das die Startseite zeigt. Die Tourenzahl bleibt aussen vor: „noch
 * 3 Touren" ist kein Ziel, das eine einzelne Fahrt greifbar naeher bringt,
 * wenn daneben „noch 12 km" stehen koennte; sie gewinnt nur, wenn sonst
 * nichts offen ist.
 */
fun nearestMilestone(facts: List<RideFact>): MilestoneProgress? {
    val open = milestoneProgress(facts).filter { it.next != null }
    return open.filter { it.kind != MilestoneKind.RIDE_COUNT }.maxByOrNull { it.fraction }
        ?: open.firstOrNull()
}

/**
 * Stufen, die genau mit der Tour [rideId] ueberschritten wurden — je Leiter
 * hoechstens die hoechste, damit eine lange Tour nicht drei Zeilen
 * derselben Sorte erzeugt.
 */
fun milestonesReachedBy(rideId: String, facts: List<RideFact>): List<Milestone> {
    val index = facts.indexOfFirst { it.id == rideId }
    if (index < 0) return emptyList()
    val before = facts.subList(0, index)
    val after = facts.subList(0, index + 1)
    return MilestoneKind.entries.mapNotNull { kind ->
        val was = milestoneValue(before, kind)
        val now = milestoneValue(after, kind)
        milestoneLadder(kind).lastOrNull { it > was && it <= now }?.let { Milestone(kind, it) }
    }
}

// ---------------------------------------------------------------------------
// Jahresziel
// ---------------------------------------------------------------------------

/**
 * Stand zum Jahresziel in Kilometern.
 *
 * @property expectedKm wo man heute bei gleichmaessigem Tempo stehen muesste.
 * @property aheadKm Vorsprung (positiv) oder Rueckstand (negativ) dazu.
 * @property neededPerWeekKm was ab heute je Woche noetig ist, um anzukommen.
 * @property projectedKm Hochrechnung aus dem bisherigen Jahresschnitt.
 */
data class YearGoalProgress(
    val year: Int,
    val targetKm: Double,
    val riddenKm: Double,
    val expectedKm: Double,
    val neededPerWeekKm: Double,
    val projectedKm: Double,
) {
    val aheadKm: Double get() = riddenKm - expectedKm
    val remainingKm: Double get() = (targetKm - riddenKm).coerceAtLeast(0.0)
    val fraction: Double get() = if (targetKm > 0) (riddenKm / targetKm).coerceIn(0.0, 1.0) else 0.0
    val reached: Boolean get() = targetKm > 0 && riddenKm >= targetKm
}

fun yearGoalProgress(facts: List<RideFact>, targetKm: Double, today: LocalDate): YearGoalProgress {
    val year = today.year
    val ridden = facts.filter { it.date.year == year && !it.date.isAfter(today) }.sumOf { it.distanceKm }
    val daysInYear = today.lengthOfYear()
    val dayOfYear = today.dayOfYear
    val daysLeft = daysInYear - dayOfYear
    val remaining = (targetKm - ridden).coerceAtLeast(0.0)
    return YearGoalProgress(
        year = year,
        targetKm = targetKm,
        riddenKm = ridden,
        expectedKm = targetKm * dayOfYear / daysInYear,
        // Am letzten Tag des Jahres ist „je Woche" der Rest selbst.
        neededPerWeekKm = if (daysLeft <= 0) remaining else remaining / (daysLeft / 7.0).coerceAtLeast(1.0),
        projectedKm = ridden * daysInYear / dayOfYear,
    )
}

/**
 * Vorschlag fuer ein Jahresziel: der Wochenschnitt der letzten acht Wochen
 * aufs Jahr hochgerechnet, plus [yearGoalStretch] — erreichbar, aber nicht
 * geschenkt. Auf volle 250 km gerundet, mindestens [minYearGoalKm]. Ohne
 * Touren der Mindestwert.
 */
fun suggestYearGoalKm(facts: List<RideFact>, today: LocalDate): Int {
    val from = today.minusDays(55)
    val recentKm = facts.filter { !it.date.isBefore(from) && !it.date.isAfter(today) }.sumOf { it.distanceKm }
    val perWeek = recentKm / 8.0
    val raw = perWeek * 52 * yearGoalStretch
    val rounded = (raw / 250.0).roundToInt() * 250
    return rounded.coerceIn(minYearGoalKm, maxYearGoalKm)
}

const val yearGoalStretch: Double = 1.15
const val minYearGoalKm: Int = 500
const val maxYearGoalKm: Int = 30000

// ---------------------------------------------------------------------------
// Naechste Bestweite
// ---------------------------------------------------------------------------

/** Zeitraum, aus dem die Ausgangsweite fuer [stretchChallenge] stammt. */
const val stretchWindowDays: Long = 42

/** Aufschlag auf die Ausgangsweite — die bekannte Zehn-Prozent-Regel. */
const val stretchFactor: Double = 1.10

/**
 * Die naechste Bestweite: ein Stueck weiter als die laengste Tour der
 * letzten sechs Wochen.
 *
 * Die Ausgangsweite ist bewusst die *juengste* Bestweite, nicht die aller
 * Zeiten: Wer im Sommer 140 km gefahren ist und im Fruehjahr wieder bei 50
 * anfaengt, braucht 55 als naechsten Schritt, nicht 150.
 *
 * @property targetKm das Ziel, auf volle 5 km aufgerundet.
 * @property baseKm die laengste Tour im Fenster.
 * @property beatsAllTime ob [targetKm] auch die weiteste Tour ueberhaupt
 *   uebertraefe.
 */
data class StretchChallenge(
    val targetKm: Int,
    val baseKm: Double,
    val beatsAllTime: Boolean,
)

/**
 * `null`, wenn im Fenster keine Tour ab 10 km liegt — dann gibt es nichts zu
 * steigern, sondern erst einmal wieder loszufahren.
 */
fun stretchChallenge(facts: List<RideFact>, today: LocalDate): StretchChallenge? {
    val from = today.minusDays(stretchWindowDays)
    val base = facts
        .filter { !it.date.isBefore(from) && !it.date.isAfter(today) }
        .maxOfOrNull { it.distanceKm }
        ?.takeIf { it >= 10.0 }
        ?: return null
    // Mindestens 3 km mehr, damit auch nach dem Runden ein Schritt bleibt.
    val raw = max(base * stretchFactor, base + 3.0)
    // Das Epsilon faengt 50 × 1,1 = 55,000000000000007 ab, das sonst auf 60 spraenge.
    val target = (ceil(raw / 5.0 - 1e-9) * 5).toInt().coerceAtMost(maxRouteTargetKm.toInt())
    if (target <= base) return null
    val allTime = facts.maxOfOrNull { it.distanceKm } ?: base
    return StretchChallenge(targetKm = target, baseKm = base, beatsAllTime = target > allTime)
}

// ---------------------------------------------------------------------------
// Summen und Verlauf
// ---------------------------------------------------------------------------

/** Summen eines Zeitraums. */
data class PeriodTotals(
    val km: Double = 0.0,
    val ascentM: Double = 0.0,
    val timeS: Long = 0,
    val rides: Int = 0,
) {
    companion object {
        val empty = PeriodTotals()
    }
}

/** Summen der Touren von [from] bis einschliesslich [to]. */
fun periodTotals(facts: List<RideFact>, from: LocalDate, to: LocalDate): PeriodTotals {
    var km = 0.0
    var ascent = 0.0
    var time = 0L
    var rides = 0
    for (fact in facts) {
        if (fact.date.isBefore(from) || fact.date.isAfter(to)) continue
        km += fact.distanceKm
        ascent += fact.ascentM
        time += fact.timeS ?: 0
        rides += 1
    }
    return PeriodTotals(km, ascent, time, rides)
}

/** Alle Touren zusammen. */
fun lifetimeTotals(facts: List<RideFact>): PeriodTotals =
    if (facts.isEmpty()) PeriodTotals.empty else periodTotals(facts, LocalDate.MIN, LocalDate.MAX)

/** Eine Kalenderwoche des Verlaufs. */
data class WeekBucket(val monday: LocalDate, val totals: PeriodTotals)

/**
 * Die letzten [weeks] Kalenderwochen, aelteste zuerst; die laufende Woche ist
 * die letzte und reicht nur bis [today]. Wochen ohne Tour stehen mit Nullen
 * drin — eine Luecke ist eine Auskunft.
 */
fun weeklyBuckets(facts: List<RideFact>, today: LocalDate, weeks: Int = 12): List<WeekBucket> {
    val thisMonday = mondayOf(today)
    return (weeks - 1 downTo 0).map { back ->
        val monday = thisMonday.minusWeeks(back.toLong())
        val sunday = monday.plusDays(6)
        WeekBucket(monday, periodTotals(facts, monday, if (sunday.isAfter(today)) today else sunday))
    }
}

/**
 * Vier Wochen gegen die vier davor — die ehrlichste kurze Antwort auf
 * „Fahre ich gerade mehr oder weniger?". Gleitend statt kalendarisch, damit
 * der Vergleich nicht am Monatsersten bei null beginnt.
 *
 * @property kmChangePct Aenderung der Kilometer in Prozent; `null`, wenn der
 *   Vergleichszeitraum leer ist (dann gibt es nichts, wovon ein Prozentwert
 *   sprechen koennte).
 */
data class RecentTrend(
    val recent: PeriodTotals,
    val before: PeriodTotals,
) {
    val kmChangePct: Int?
        get() = if (before.km > 0) ((recent.km - before.km) / before.km * 100).roundToInt() else null
}

fun recentTrend(facts: List<RideFact>, today: LocalDate, days: Long = 28): RecentTrend = RecentTrend(
    recent = periodTotals(facts, today.minusDays(days - 1), today),
    before = periodTotals(facts, today.minusDays(2 * days - 1), today.minusDays(days)),
)

/** Tage seit der letzten Tour; `null` ohne Tour. 0 = heute gefahren. */
fun daysSinceLastRide(facts: List<RideFact>, today: LocalDate): Int? =
    facts.filter { !it.date.isAfter(today) }.maxOfOrNull { it.date }
        ?.let { ChronoUnit.DAYS.between(it, today).toInt() }
