package de.trailscape.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * # Werde ich fitter? — in Klartext
 *
 * Die Belastungsrechnung (`PerformanceManagement.kt`, „Fitness 14 →") ist
 * fuer Fachleute gedacht. Die Frage der meisten Fahrer ist einfacher: *Bin
 * ich schneller geworden — und fahre ich weiter?* Diese Datei beantwortet sie
 * mit drei Beobachtungen, die jeder versteht:
 *
 *  * [FitnessSignalKind.EFFICIENCY] — bei gleichem Puls schneller;
 *  * [FitnessSignalKind.PACE] — auf vergleichbaren Runden schneller;
 *  * [FitnessSignalKind.ENDURANCE] — die laengste Tour waechst.
 *
 * Verglichen werden immer **die letzten 42 Tage** mit **den 42 Tagen davor**.
 * Kurze Zeitraeume schwanken mit Wetter und Laune, sehr lange verschleiern,
 * was gerade passiert; sechs Wochen sind ein voller Trainingsblock.
 *
 * Reine Rechnung wie `Motivation.kt`: kein Android, keine Uhr, keine Saetze —
 * die Anzeige macht aus Aufzaehlungen und Zahlen den Text. Geplante Touren
 * zaehlen nicht, Touren nach „heute" werden ignoriert.
 */

/** Gesamturteil. */
enum class FitnessVerdict { FITTER, STABLE, DECLINING, NOT_ENOUGH_DATA }

/** Art der Beobachtung. */
enum class FitnessSignalKind { EFFICIENCY, PACE, ENDURANCE }

/**
 * Eine Beobachtung, in beiden Zeitraeumen gemessen.
 *
 * @property recent Wert der letzten 42 Tage. EFFICIENCY: Meter je Herzschlag;
 *   PACE: km/h; ENDURANCE: km (laengste Tour).
 * @property before Wert der 42 Tage davor, gleiche Einheit.
 * @property changePct Veraenderung in Prozent, gerundet; positiv heisst bei
 *   allen drei Arten „besser".
 */
data class FitnessSignal(
    val kind: FitnessSignalKind,
    val recent: Double,
    val before: Double,
    val changePct: Int,
    val sampleRecent: Int,
    val sampleBefore: Int,
)

data class FitnessComparison(
    val verdict: FitnessVerdict,
    val signals: List<FitnessSignal>,
    val windowDays: Int,
)

/** Laenge jedes der beiden Vergleichszeitraeume: ein voller Trainingsblock. */
const val FITNESS_WINDOW_DAYS = 42

/** Mindestfahrzeit einer vergleichbaren Fahrt: kuerzere Runden sind zu stark vom Anfahren gepraegt. */
const val FITNESS_MIN_TIME_S = 40 * 60

/** Mindestlaenge einer vergleichbaren Fahrt: erst ab hier mittelt sich Ampel- und Kreuzungsstopp heraus. */
const val FITNESS_MIN_KM = 15.0

/** Untere Grenze fuer einen glaubwuerdigen Schnitt: darunter ist es eher ein Spaziergang oder ein Aufzeichnungsfehler. */
const val FITNESS_MIN_SPEED_KMH = 8.0

/** Obere Grenze fuer einen glaubwuerdigen Schnitt: darueber ist es ein Motor, ein Auto oder ein Fehler. */
const val FITNESS_MAX_SPEED_KMH = 50.0

/** Untere Grenze fuer einen glaubwuerdigen Durchschnittspuls (bpm). */
const val FITNESS_MIN_HR = 80

/** Obere Grenze fuer einen glaubwuerdigen Durchschnittspuls (bpm). */
const val FITNESS_MAX_HR = 200

/**
 * Hoechste Steigungsdichte (Hoehenmeter je km) fuer EFFICIENCY und PACE:
 * Berge senken die Geschwindigkeit bei gleicher Anstrengung und liessen eine
 * huegelige Phase wie einen Leistungseinbruch aussehen — es zaehlen nur
 * flache bis wellige Runden.
 */
const val FITNESS_MAX_CLIMB_M_PER_KM = 20.0

/** Mindestzahl Fahrten je Zeitraum fuer EFFICIENCY (mit Puls) und PACE: ein Median aus zwei Werten ist keiner. */
const val FITNESS_MIN_RIDES_COMPARABLE = 3

/** Mindestzahl Fahrten je Zeitraum fuer ENDURANCE: zwei reichen, weil nur der Hoechstwert zaehlt. */
const val FITNESS_MIN_RIDES_ENDURANCE = 2

/** Ab dieser Veraenderung (Prozent, gerundet) bewegt sich EFFICIENCY oder PACE: darunter ist es Messrauschen. */
const val FITNESS_MOVE_PCT = 3

/** Ab dieser Veraenderung (Prozent, gerundet) bewegt sich ENDURANCE: die laengste Tour schwankt mit der Gelegenheit stark. */
const val FITNESS_MOVE_PCT_ENDURANCE = 10

/**
 * Die drei Beobachtungen und das Urteil.
 *
 * **Urteil:** Gibt es keine Beobachtung, ist es [FitnessVerdict.NOT_ENOUGH_DATA].
 * Sonst entscheidet bei vorhandener *und* bewegter EFFICIENCY sie allein —
 * Puls und Tempo zusammen sind das aussagekraeftigste Mass; Rueckenwind
 * betrifft PACE, nicht den Puls. Andernfalls: mindestens eine Beobachtung
 * besser und keine schlechter → FITTER; spiegelbildlich DECLINING; alles
 * andere STABLE.
 *
 * **Schwaechen:** Wind, Hitze, Gruppenfahrten (Windschatten) und E-Bikes
 * veraendern Tempo und Puls ohne Aenderung der Fitness; der Median ueber
 * mindestens drei Fahrten daempft das, beseitigt es aber nicht.
 */
fun fitnessComparison(rides: List<RideInfo>, dateOf: (Long) -> LocalDate, today: LocalDate): FitnessComparison {
    val recent = ArrayList<RideInfo>()
    val before = ArrayList<RideInfo>()
    for (ride in riddenRides(rides)) {
        val daysAgo = ChronoUnit.DAYS.between(dateOf(ride.createdAt), today)
        when {
            daysAgo < 0 -> Unit // nach „heute": Uhr- oder Zeitzonenfehler
            daysAgo < FITNESS_WINDOW_DAYS -> recent += ride
            daysAgo < 2 * FITNESS_WINDOW_DAYS -> before += ride
        }
    }

    val signals = listOfNotNull(
        efficiency(recent, before),
        pace(recent, before),
        endurance(recent, before),
    )
    return FitnessComparison(verdict(signals), signals, FITNESS_WINDOW_DAYS)
}

private fun verdict(signals: List<FitnessSignal>): FitnessVerdict {
    if (signals.isEmpty()) return FitnessVerdict.NOT_ENOUGH_DATA
    val efficiency = signals.firstOrNull { it.kind == FitnessSignalKind.EFFICIENCY }
    if (efficiency != null && efficiency.moves()) {
        return if (efficiency.changePct > 0) FitnessVerdict.FITTER else FitnessVerdict.DECLINING
    }
    val up = signals.any { it.moves() && it.changePct > 0 }
    val down = signals.any { it.moves() && it.changePct < 0 }
    return when {
        up && !down -> FitnessVerdict.FITTER
        down && !up -> FitnessVerdict.DECLINING
        else -> FitnessVerdict.STABLE
    }
}

private fun FitnessSignal.moves(): Boolean {
    val limit = if (kind == FitnessSignalKind.ENDURANCE) FITNESS_MOVE_PCT_ENDURANCE else FITNESS_MOVE_PCT
    return abs(changePct) >= limit
}

private fun timeS(ride: RideInfo): Int? =
    (ride.stats.movingTimeS ?: ride.stats.durationS)?.takeIf { it > 0 }

private fun distanceKm(ride: RideInfo): Double =
    ride.stats.distanceKm.takeIf { it.isFinite() } ?: 0.0

/** Schnitt in km/h: der gespeicherte, ersatzweise Strecke durch Fahrzeit. */
private fun speedKmh(ride: RideInfo): Double? {
    val stored = ride.stats.avgSpeedKmh?.takeIf { it.isFinite() && it > 0 }
    if (stored != null) return stored
    val t = timeS(ride) ?: return null
    return distanceKm(ride) / (t / 3600.0)
}

/** Vergleichbar: lang genug, weit genug, glaubwuerdiger Schnitt — und auf Wunsch flach genug. */
private fun isComparable(ride: RideInfo, flatOnly: Boolean): Boolean {
    val t = timeS(ride) ?: return false
    val km = distanceKm(ride)
    if (t < FITNESS_MIN_TIME_S || km < FITNESS_MIN_KM) return false
    val speed = speedKmh(ride) ?: return false
    if (speed < FITNESS_MIN_SPEED_KMH || speed > FITNESS_MAX_SPEED_KMH) return false
    if (flatOnly) {
        val ascent = ride.stats.ascentM.takeIf { it.isFinite() } ?: return false
        if (ascent / km > FITNESS_MAX_CLIMB_M_PER_KM) return false
    }
    return true
}

private fun efficiencyOf(ride: RideInfo): Double? {
    if (!isComparable(ride, flatOnly = true)) return null
    val hr = ride.stats.avgHrBpm?.takeIf { it in FITNESS_MIN_HR..FITNESS_MAX_HR } ?: return null
    val speed = speedKmh(ride) ?: return null
    return speed * 1000.0 / 60.0 / hr
}

private fun efficiency(recent: List<RideInfo>, before: List<RideInfo>): FitnessSignal? =
    medianSignal(FitnessSignalKind.EFFICIENCY, recent.mapNotNull(::efficiencyOf), before.mapNotNull(::efficiencyOf))

private fun pace(recent: List<RideInfo>, before: List<RideInfo>): FitnessSignal? {
    fun speeds(rides: List<RideInfo>) =
        rides.filter { isComparable(it, flatOnly = true) }.mapNotNull(::speedKmh)
    return medianSignal(FitnessSignalKind.PACE, speeds(recent), speeds(before))
}

private fun medianSignal(kind: FitnessSignalKind, recent: List<Double>, before: List<Double>): FitnessSignal? {
    if (recent.size < FITNESS_MIN_RIDES_COMPARABLE || before.size < FITNESS_MIN_RIDES_COMPARABLE) return null
    return signal(kind, median(recent), median(before), recent.size, before.size)
}

private fun endurance(recent: List<RideInfo>, before: List<RideInfo>): FitnessSignal? {
    fun lengths(rides: List<RideInfo>) = rides.map(::distanceKm).filter { it >= FITNESS_MIN_KM }
    val r = lengths(recent)
    val b = lengths(before)
    if (r.size < FITNESS_MIN_RIDES_ENDURANCE || b.size < FITNESS_MIN_RIDES_ENDURANCE) return null
    return signal(FitnessSignalKind.ENDURANCE, r.max(), b.max(), r.size, b.size)
}

private fun signal(kind: FitnessSignalKind, recent: Double, before: Double, nRecent: Int, nBefore: Int): FitnessSignal? {
    if (!recent.isFinite() || !before.isFinite() || before <= 0.0) return null
    val pct = ((recent - before) / before * 100.0).roundToInt()
    return FitnessSignal(kind, recent, before, pct, nRecent, nBefore)
}

/** Median; bei gerader Anzahl der Mittelwert der beiden mittleren. [values] ist nicht leer. */
private fun median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}
