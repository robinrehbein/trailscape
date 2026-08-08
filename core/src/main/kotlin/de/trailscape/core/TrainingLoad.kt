package de.trailscape.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sportwissenschaftlicher Rechenkern fuer Trainingslast, Fitness/Form,
 * Erholung und Tour-Auswertung.
 *
 * 1:1-Portierung von `lib/training_load.dart` (Abschnitt 1–3 plus
 * Fallback-Kaskade; §4 liegt in `PerformanceManagement.kt`, §5/§6 in
 * `Readiness.kt`, §7 in `RideAnalysis.kt`).
 *
 * Die Datei ist bewusst **UI-frei und ohne Persistenz**: nur reine Funktionen
 * und Wertobjekte. Grundlage ist das Dokument „Trailscape — Sportwissen-
 * schaftliche Berechnungsbasis" (Stand 2026-08-08). Abschnittsnummern in den
 * Kommentaren (§2.1 usw.) verweisen darauf.
 *
 * Leitplanken, die ueberall gelten:
 *
 *  * **Nie werfen.** Jede Funktion nimmt leere Serien, fehlende Herzfrequenz,
 *    Luecken und Ausreisser entgegen und liefert einen „nicht berechenbar"-
 *    Zustand (`available == false` plus deutschsprachige Begruendung).
 *  * **Jede abgeleitete Groesse traegt eine [Confidence].** Geschaetzte Werte
 *    duerfen im UI nie wie Messwerte auftreten (§8.5).
 *  * **Eine einzige Lastskala.** Alles muendet in `load` mit der Semantik
 *    „1 h an der Schwelle = 100 Punkte" (§2.4), gedeckelt bei [maxLoad].
 */

// ---------------------------------------------------------------------------
// Konstanten
// ---------------------------------------------------------------------------

/** Erdbeschleunigung in m/s². */
const val gravity: Double = 9.80665

/** Obergrenze fuer eine Tourlast (Plausibilitaet, §2.4). */
const val maxLoad: Double = 500.0

/** Default-Setup-Masse (Rad + Kleidung + Flaschen + Tasche) in kg (§3.2). */
const val defaultSetupMassKg: Double = 12.0

/** Default-CdA fuer Gravel in Hoods-Position in m² (§3.2). */
const val defaultCda: Double = 0.38

/** Default-Rollwiderstandsbeiwert fuer Gravelreifen auf Mischuntergrund (§3.2). */
const val defaultCrr: Double = 0.008

/** Antriebsstrang-Wirkungsgrad (§3.2). */
const val defaultDriveEfficiency: Double = 0.97

/** Default-Ruhepuls fuer Freizeitsportler, wenn keine Serie vorliegt (§1.2). */
const val defaultRestingHrBpm: Double = 60.0

/** LTHR-Default als Anteil der HFmax (§1.3). */
const val defaultLthrFactor: Double = 0.89

/** Zulaessiges Plausibilitaetsfenster fuer LTHR relativ zur HFmax (§1.3). */
const val lthrMinFactor: Double = 0.80
const val lthrMaxFactor: Double = 0.95

/** Grenzen fuer die geschaetzte FTP in Watt (§3.3). */
const val minEftpW: Double = 100.0
const val maxEftpW: Double = 400.0

/** Default-eFTP in W/kg Fahrergewicht, wenn keine harte Tour vorliegt (§3.3). */
const val defaultEftpWPerKg: Double = 2.4

/**
 * Mindestabdeckung der Bewegungszeit mit gueltiger Herzfrequenz, damit der
 * HF-Pfad benutzt wird (§3.1, Stufe A).
 */
const val minHrCoverage: Double = 0.80

/** Ab dieser Luecke gilt die Herzfrequenz eines Segments als unbekannt (§2.1). */
const val maxHrGapS: Double = 30.0

/**
 * Segmente, die laenger dauern, gelten als Aufzeichnungsluecke und zaehlen nicht
 * zur Bewegungszeit. Eigene Schutzregel (das Dokument nennt nur die
 * HF-Lueckenregel), damit ein gestoppter Recorder keine Last erzeugt.
 */
const val maxSegmentDtS: Double = 120.0

/**
 * Bewegungsschwelle: Geschwindigkeit in m/s bzw. HF-Faktor ueber Ruhepuls
 * (§2.1, „moving time").
 */
const val movingSpeedMs: Double = 1.0
const val movingHrRestFactor: Double = 1.15

/** EWMA-Zeitkonstanten der PMC (§4.2). */
val lambdaCtl: Double = 1 - exp(-1.0 / 42)
val lambdaAtl: Double = 1 - exp(-1.0 / 7)

/** Antwort der CTL-EWMA ueber 7 Tage: `1 − (1 − λ_ctl)^7` (§6.3). */
val ctlWeeklyResponse: Double = 1 - (1 - lambdaCtl).pow(7)

/** Optimalband des Belastungsverhaeltnisses (Garmin-Konvention, §4.4). */
const val loadRatioBandLow: Double = 0.8
const val loadRatioBandHigh: Double = 1.5

/**
 * Unterhalb dieser chronischen Wochenlast ist das Verhaeltnis numerisch
 * instabil und wird unterdrueckt (§4.4).
 */
const val minChronicWeeklyLoad: Double = 20.0

/** Kalibrierungsfaktor HF↔Physik: gueltiges Fenster, sonst auf 1,0 (§3.3). */
const val alphaMin: Double = 0.6
const val alphaMax: Double = 1.6

/** Default-Faktor der sRPE-Kalibrierung (§3.4). */
const val defaultRpeFactor: Double = 1.0 / 6

/**
 * HRV (rMSSD): Baselinefenster in Tagen und Breite des Normalbands als
 * Vielfaches der Streuung von `ln(rMSSD)` (Plews & Laursen / HRV4Training).
 */
const val hrvBaselineDays: Int = 28
const val hrvRollingDays: Int = 7
const val hrvBandFactor: Double = 0.75

/** Mindestzahl an HRV-Tagen im Baselinefenster fuer eine volle Wertung. */
const val hrvMinBaselineDays: Int = 14

/** Mindestzahl an Messungen im 7-Tage-Rollfenster. */
const val hrvMinRecentDays: Int = 3

/**
 * Untergrenze der Streuung von `ln(rMSSD)`. Ohne sie wuerde eine zufaellig sehr
 * ruhige Woche jede Alltagsschwankung zum Ausreisser machen (≈ ±3,8 % Band).
 */
const val hrvMinSigmaLn: Double = 0.05

/** Plausibilitaetsfenster einzelner rMSSD-Tageswerte in ms. */
const val hrvMinMs: Double = 5.0
const val hrvMaxMs: Double = 300.0

/**
 * Gewichte des Readiness-Scores, **sobald HRV vorliegt** (§5.3/§5.4).
 *
 * Begruendung der Reihenfolge: rMSSD misst den parasympathischen Zustand
 * direkt und ist das Signal, das Garmin, Polar und Whoop am hoechsten
 * gewichten; der Ruhepuls beschreibt dieselbe Achse, reagiert aber traeger und
 * groeber; Schlaf ist ein *Einflussfaktor* auf Erholung, keine Messung davon;
 * TSB ist ein Modellwert aus geschaetzten Lasten und traegt entsprechend am
 * wenigsten. Ohne HRV bleibt die alte Formel aus §5.4 unveraendert bestehen.
 */
const val readinessWeightHrv: Double = 0.40
const val readinessWeightRhr: Double = 0.25
const val readinessWeightSleep: Double = 0.20
const val readinessWeightLoad: Double = 0.15

/**
 * Obergrenzen der Einzel-Strafterme aus §5.4 — Normierungsanker, wenn die
 * Strafterme gewichtet zusammengefuehrt werden.
 */
const val maxPenaltyRhr: Double = 45.0
const val maxPenaltySleep: Double = 45.0
const val maxPenaltyLoad: Double = 30.0

/**
 * Umrechnung Wochenstunden → Wochenlast (§6.3, Sicherheitsdeckel).
 *
 * Hergeleitet aus der eigenen Lastnormierung (`load = Dauer_h × IF² × 100`,
 * §3.3) und der pyramidalen Zielverteilung fuer Fahrer mit wenig Zeit
 * (75 : 15 : 10, §6.3): LIT ≈ IF 0,70 → 49 Last/h, MIT ≈ IF 0,85 → 72 Last/h,
 * HIT ≈ IF 1,00 → 100 Last/h. Gewichtet ergibt das
 * `0,75 × 49 + 0,15 × 72 + 0,10 × 100 ≈ 58` Last pro tatsaechlich gefahrener
 * Stunde. Das Dokument nennt 75 Last/h — das ist das obere GA2-Mittel und
 * damit die optimistische Obergrenze, keine realistische Wochenmischung.
 */
const val weeklyLoadPerHour: Double = 58.0

/**
 * Unsicherheitsband der VO2max-Schaetzung (§7.3). Fuer die Uth-Formel nennt das
 * Dokument ±15 %; fuer die Regression (Methode B) setzen wir ±10 % an — enger,
 * weil individuell gemessene Submaximalpunkte eingehen, aber weiterhin als
 * Band und nie als Einzelzahl.
 */
const val vo2MaxBandRatio: Double = 0.15
const val vo2MaxBandRegression: Double = 0.10

// ---------------------------------------------------------------------------
// Kleine Helfer
// ---------------------------------------------------------------------------

/**
 * Entspricht Darts `_clamp`: NaN faellt auf [lo], sonst wird hart geklemmt.
 *
 * Bewusst **nicht** `coerceIn`: Kotlin liefert fuer NaN wieder NaN, Dart
 * dagegen die Untergrenze — der Unterschied wuerde in `hrCoverage` und den
 * Straftermen durchschlagen.
 */
internal fun clamp(v: Double, lo: Double, hi: Double): Double =
    if (v.isNaN()) lo else if (v < lo) lo else if (v > hi) hi else v

private fun medianSorted(sorted: List<Double>): Double {
    val n = sorted.size
    if (n == 0) {
        return Double.NaN
    }
    if (n % 2 != 0) {
        return sorted[n / 2]
    }
    return (sorted[n / 2 - 1] + sorted[n / 2]) / 2
}

/** Median einer Liste, `null` bei leerer Liste. */
fun median(values: Iterable<Double>): Double? {
    val list = values.filter { it.isFinite() }.sorted()
    if (list.isEmpty()) {
        return null
    }
    return medianSorted(list)
}

/** Robuste Streuungsschaetzung `1.4826 × MAD`, `null` bei leerer Liste (§5.1). */
fun madSigma(values: Iterable<Double>, center: Double? = null): Double? {
    val list = values.filter { it.isFinite() }
    if (list.isEmpty()) {
        return null
    }
    val med = center ?: median(list)!!
    val deviations = list.map { abs(it - med) }
    return 1.4826 * median(deviations)!!
}

/** Entspricht Darts `DateTime(d.year, d.month, d.day)`. */
internal fun atMidnight(d: LocalDateTime): LocalDateTime = d.toLocalDate().atStartOfDay()

/** Entspricht Darts `DateTime(d.year, d.month, d.day + days)`. */
internal fun addDays(d: LocalDateTime, days: Int): LocalDateTime =
    d.toLocalDate().plusDays(days.toLong()).atStartOfDay()

internal fun dayDifference(a: LocalDateTime, b: LocalDateTime): Int =
    ChronoUnit.DAYS.between(atMidnight(b), atMidnight(a)).toInt()

/**
 * Entspricht Darts `double.toStringAsFixed(digits)`: gerundet wird der Betrag
 * (kaufmaennisch, also bei genau .5 vom Nullpunkt weg), das Vorzeichen wird
 * separat vorangestellt — dieselbe Regel wie ECMAScripts `toFixed`, dem Darts
 * Implementierung folgt.
 */
internal fun toStringAsFixed(value: Double, digits: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val text = BigDecimal(abs(value)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    return if (value < 0) "-$text" else text
}

/** Zeitgewichteter zentrierter gleitender Mittelwert. */
private fun centeredMean(t: List<Double>, v: List<Double>, windowS: Double): List<Double> {
    val n = v.size
    if (n == 0) {
        return emptyList()
    }
    val half = windowS / 2
    val out = MutableList(n) { 0.0 }
    var lo = 0
    var hi = 0
    var sum = 0.0
    for (i in 0 until n) {
        while (hi < n && t[hi] <= t[i] + half) {
            sum += v[hi]
            hi++
        }
        while (lo < n && t[lo] < t[i] - half) {
            sum -= v[lo]
            lo++
        }
        val count = hi - lo
        out[i] = if (count > 0) sum / count else v[i]
    }
    return out
}

/** Zentrierter gleitender Median (robust gegen einzelne Hoehen-Ausreisser). */
private fun centeredMedian(t: List<Double>, v: List<Double>, windowS: Double): List<Double> {
    val n = v.size
    if (n == 0) {
        return emptyList()
    }
    val half = windowS / 2
    val out = MutableList(n) { 0.0 }
    var lo = 0
    var hi = 0
    for (i in 0 until n) {
        while (hi < n && t[hi] <= t[i] + half) {
            hi++
        }
        while (lo < n && t[lo] < t[i] - half) {
            lo++
        }
        out[i] = if (hi > lo) medianSorted(v.subList(lo, hi).sorted()) else v[i]
    }
    return out
}

/** Nachlaufender, zeitgewichteter gleitender Mittelwert (fuer NP, §3.3). */
private fun trailingWeightedMean(
    t: List<Double>,
    v: List<Double>,
    weight: List<Double>,
    windowS: Double,
): List<Double> {
    val n = v.size
    val out = MutableList(n) { 0.0 }
    var lo = 0
    var sumV = 0.0
    var sumW = 0.0
    for (i in 0 until n) {
        sumV += v[i] * weight[i]
        sumW += weight[i]
        while (lo < i && t[lo] < t[i] - windowS) {
            sumV -= v[lo] * weight[lo]
            sumW -= weight[lo]
            lo++
        }
        out[i] = if (sumW > 0) sumV / sumW else v[i]
    }
    return out
}

// ---------------------------------------------------------------------------
// Basistypen
// ---------------------------------------------------------------------------

/** Geschlecht — beeinflusst nur die TRIMP-Koeffizienten (§2.1). */
enum class Sex(
    /** Exakter Dart-Enum-Name (`Sex.name`), wie er im JSON steht. */
    val jsonName: String,
) {
    MAENNLICH("maennlich"),
    WEIBLICH("weiblich"),
    UNBEKANNT("unbekannt"),
}

/** Verlaesslichkeit einer abgeleiteten Groesse (§0). */
enum class Confidence(
    /** Exakter Dart-Enum-Name (`Confidence.name`), wie er im JSON steht. */
    val jsonName: String,
) {
    NONE("none"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/** Woher ein HF-Grundwert stammt (§1.1/§1.3). */
enum class ValueSource { TEST, BEOBACHTET, GESCHAETZT }

val confidenceLabels: Map<Confidence, String> = mapOf(
    Confidence.NONE to "nicht berechenbar",
    Confidence.LOW to "grobe Schätzung",
    Confidence.MEDIUM to "Schätzung",
    Confidence.HIGH to "belastbar",
)

internal fun downgrade(c: Confidence): Confidence = when (c) {
    Confidence.HIGH -> Confidence.MEDIUM
    Confidence.MEDIUM -> Confidence.LOW
    Confidence.LOW -> Confidence.LOW
    Confidence.NONE -> Confidence.NONE
}

internal fun minConfidence(a: Confidence, b: Confidence): Confidence =
    if (a.ordinal <= b.ordinal) a else b

// ---------------------------------------------------------------------------
// 1. Nutzerprofil
// ---------------------------------------------------------------------------

/**
 * Statisches Nutzerprofil fuer alle Berechnungen.
 *
 * Alle abgeleiteten Groessen (HFmax, LTHR, Ruhepuls, eFTP) haben einen Default
 * nach Dokument und lassen sich einzeln ueberschreiben. Ein Feldtestwert
 * gewinnt immer ueber die Schaetzung.
 */
data class TrainingProfile(
    val ageYears: Int,
    val sex: Sex = Sex.UNBEKANNT,
    /** Fahrergewicht in kg (ohne Rad). */
    val weightKg: Double = 75.0,
    /** Setup-Masse in kg: Rad, Kleidung, Flaschen, Tasche. */
    val setupMassKg: Double = defaultSetupMassKg,
    /** Gemessene bzw. beobachtete HFmax in bpm; ohne Angabe gilt Tanaka. */
    val hrMaxOverride: Double? = null,
    /** Gemessene LTHR in bpm; ohne Angabe [defaultLthrFactor] × HFmax. */
    val lthrOverride: Double? = null,
    /** Ruhepuls-Baseline in bpm zum Zeitpunkt der Tour; ohne Angabe 60. */
    val restingHrOverride: Double? = null,
    val cda: Double = defaultCda,
    val crr: Double = defaultCrr,
    val driveEfficiency: Double = defaultDriveEfficiency,
    /** Geschaetzte FTP in Watt; ohne Angabe [defaultEftpWPerKg] × Gewicht. */
    val eftpOverrideW: Double? = null,
    /**
     * Zeitbudget fuers Training in Stunden pro Woche; `null` = kein Budget
     * hinterlegt (dann deckelt nur die Lasthistorie, §6.3).
     */
    val weeklyHours: Double? = null,
) {
    /** HFmax nach Tanaka: `208 − 0,7 × Alter` (§1.1). SEE ≈ ±10 bpm. */
    val tanakaHrMax: Double get() = 208 - 0.7 * ageYears

    /** Effektive HFmax in bpm. */
    val hrMax: Double get() = clamp(hrMaxOverride ?: tanakaHrMax, 120.0, 230.0)

    val hrMaxSource: ValueSource
        get() = if (hrMaxOverride != null) ValueSource.TEST else ValueSource.GESCHAETZT

    /** Effektive Schwellen-HF in bpm, immer im Fenster 0,80–0,95 × HFmax (§1.3). */
    val lthr: Double
        get() {
            val raw = lthrOverride ?: (defaultLthrFactor * hrMax)
            return clamp(raw, lthrMinFactor * hrMax, lthrMaxFactor * hrMax)
        }

    val lthrSource: ValueSource
        get() = if (lthrOverride != null) ValueSource.TEST else ValueSource.GESCHAETZT

    /** Effektiver Ruhepuls in bpm. */
    val restingHr: Double get() = clamp(restingHrOverride ?: defaultRestingHrBpm, 30.0, 100.0)

    val restingHrSource: ValueSource
        get() = if (restingHrOverride != null) ValueSource.BEOBACHTET else ValueSource.GESCHAETZT

    /** Herzfrequenzreserve (HFmax − HFruhe), nie kleiner als 1. */
    val hrReserve: Double get() = max(hrMax - restingHr, 1.0)

    /** Gesamtmasse Fahrer + Setup in kg. */
    val totalMassKg: Double get() = max(weightKg + setupMassKg, 1.0)

    /** Effektive FTP-Schaetzung in Watt, geklemmt auf [minEftpW]…[maxEftpW]. */
    val eftpW: Double
        get() = clamp(eftpOverrideW ?: (defaultEftpWPerKg * weightKg), minEftpW, maxEftpW)

    /**
     * Banister-Koeffizient `a` (§2.1). Ohne Geschlechtsangabe der maennliche
     * Satz — bei hoher Intensitaet der konservativere.
     */
    val trimpA: Double get() = if (sex == Sex.WEIBLICH) 0.86 else 0.64

    /** Banister-Koeffizient `b` (§2.1). */
    val trimpB: Double get() = if (sex == Sex.WEIBLICH) 1.67 else 1.92

    /** Verlaesslichkeit der HF-Ankerwerte. Feldtestwerte heben sie an. */
    val anchorConfidence: Confidence
        get() {
            if (hrMaxSource == ValueSource.TEST && lthrSource == ValueSource.TEST) {
                return Confidence.HIGH
            }
            if (hrMaxSource == ValueSource.TEST || lthrSource == ValueSource.TEST) {
                return Confidence.MEDIUM
            }
            return Confidence.LOW
        }

    /** Zonenmodell zu diesem Profil. */
    val zones: TrainingZones get() = TrainingZones(hrMax = hrMax, lthr = lthr, restingHr = restingHr)

    /**
     * Entspricht Darts `copyWith`: `null` bedeutet „unveraendert lassen" — ein
     * Override laesst sich damit bewusst **nicht** zurueck auf `null` setzen.
     */
    fun copyWith(
        ageYears: Int? = null,
        sex: Sex? = null,
        weightKg: Double? = null,
        setupMassKg: Double? = null,
        hrMaxOverride: Double? = null,
        lthrOverride: Double? = null,
        restingHrOverride: Double? = null,
        cda: Double? = null,
        crr: Double? = null,
        driveEfficiency: Double? = null,
        eftpOverrideW: Double? = null,
        weeklyHours: Double? = null,
    ): TrainingProfile = TrainingProfile(
        ageYears = ageYears ?: this.ageYears,
        sex = sex ?: this.sex,
        weightKg = weightKg ?: this.weightKg,
        setupMassKg = setupMassKg ?: this.setupMassKg,
        hrMaxOverride = hrMaxOverride ?: this.hrMaxOverride,
        lthrOverride = lthrOverride ?: this.lthrOverride,
        restingHrOverride = restingHrOverride ?: this.restingHrOverride,
        cda = cda ?: this.cda,
        crr = crr ?: this.crr,
        driveEfficiency = driveEfficiency ?: this.driveEfficiency,
        eftpOverrideW = eftpOverrideW ?: this.eftpOverrideW,
        weeklyHours = weeklyHours ?: this.weeklyHours,
    )

    fun toJson(): JsonObject = buildJsonObject {
        put("ageYears", ageYears)
        put("sex", sex.jsonName)
        put("weightKg", weightKg)
        put("setupMassKg", setupMassKg)
        hrMaxOverride?.let { put("hrMaxOverride", it) }
        lthrOverride?.let { put("lthrOverride", it) }
        restingHrOverride?.let { put("restingHrOverride", it) }
        put("cda", cda)
        put("crr", crr)
        put("driveEfficiency", driveEfficiency)
        eftpOverrideW?.let { put("eftpOverrideW", it) }
        weeklyHours?.let { put("weeklyHours", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): TrainingProfile {
            val rawSex = (json.fieldOrNull("sex") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            val sex = Sex.entries.firstOrNull { it.jsonName == rawSex } ?: Sex.UNBEKANNT

            return TrainingProfile(
                ageYears = json.optionalDouble("ageYears")?.let { dartRound(it).toInt() } ?: 40,
                sex = sex,
                weightKg = json.optionalDouble("weightKg") ?: 75.0,
                setupMassKg = json.optionalDouble("setupMassKg") ?: defaultSetupMassKg,
                hrMaxOverride = json.optionalDouble("hrMaxOverride"),
                lthrOverride = json.optionalDouble("lthrOverride"),
                restingHrOverride = json.optionalDouble("restingHrOverride"),
                cda = json.optionalDouble("cda") ?: defaultCda,
                crr = json.optionalDouble("crr") ?: defaultCrr,
                driveEfficiency = json.optionalDouble("driveEfficiency") ?: defaultDriveEfficiency,
                eftpOverrideW = json.optionalDouble("eftpOverrideW"),
                // Fehlt in aelteren Profilen — dann gilt „kein Zeitbudget hinterlegt".
                weeklyHours = json.optionalDouble("weeklyHours"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Zonenmodelle (§1.4)
// ---------------------------------------------------------------------------

val frielZoneLabels: List<String> = listOf(
    "Z1 Regeneration",
    "Z2 Grundlage",
    "Z3 Tempo",
    "Z4 Schwelle",
    "Z5 VO2max+",
)

val luciaZoneLabels: List<String> = listOf("LIT", "MIT", "HIT")

val edwardsZoneLabels: List<String> = listOf(
    "50–60 % HFmax",
    "60–70 % HFmax",
    "70–80 % HFmax",
    "80–90 % HFmax",
    "90–100 % HFmax",
)

/**
 * Zonengrenzen zu einem Satz HF-Ankerwerten.
 *
 * Wird pro Tour als Snapshot mitgefuehrt (`zones_used`, §1.4), damit eine
 * spaetere LTHR-Korrektur die historische Verteilung nicht verschiebt.
 */
data class TrainingZones(
    val hrMax: Double,
    val lthr: Double,
    val restingHr: Double,
) {
    /** Untergrenzen der Friel-Zonen Z2…Z5 in bpm. */
    val frielBoundsBpm: List<Double>
        get() = listOf(0.81 * lthr, 0.90 * lthr, 0.94 * lthr, 1.00 * lthr)

    /** Index 0…4 der Friel-Zone (§1.4 A). */
    fun frielZoneIndex(hr: Double): Int {
        val bounds = frielBoundsBpm
        for (i in bounds.indices) {
            if (hr < bounds[i]) {
                return i
            }
        }
        return 4
    }

    /** Index 0…2 der Lucia-Domaene LIT/MIT/HIT (§1.4 B). */
    fun luciaZoneIndex(hr: Double): Int {
        if (hr < 0.85 * lthr) {
            return 0
        }
        if (hr <= lthr) {
            return 1
        }
        return 2
    }

    /** Index 0…4 der Edwards-Zone (%HFmax) oder `null` unterhalb 50 % HFmax. */
    fun edwardsZoneIndex(hr: Double): Int? {
        val pct = hr / hrMax
        if (pct < 0.5) return null
        if (pct < 0.6) return 0
        if (pct < 0.7) return 1
        if (pct < 0.8) return 2
        if (pct < 0.9) return 3
        return 4
    }

    /** Karvonen-Zielherzfrequenz fuer einen HRR-Anteil (§1.4 C, Fallback-Anker). */
    fun karvonenHr(fraction: Double): Double =
        restingHr + clamp(fraction, 0.0, 1.0) * (hrMax - restingHr)

    fun toJson(): JsonObject = buildJsonObject {
        put("hrMax", hrMax)
        put("lthr", lthr)
        put("restingHr", restingHr)
    }

    companion object {
        fun fromJson(json: JsonObject): TrainingZones = TrainingZones(
            hrMax = json.optionalDouble("hrMax") ?: 185.0,
            lthr = json.optionalDouble("lthr") ?: 165.0,
            restingHr = json.optionalDouble("restingHr") ?: 60.0,
        )
    }
}

/** Zeit je Zone, plus Anteile. */
data class ZoneDistribution(
    val labels: List<String>,
    val seconds: List<Double>,
) {
    val totalSeconds: Double get() = seconds.fold(0.0) { a, b -> a + b }

    /** Anteile 0…1 je Zone; alles 0, wenn keine Zeit erfasst wurde. */
    val fractions: List<Double>
        get() {
            val total = totalSeconds
            if (total <= 0) {
                return List(seconds.size) { 0.0 }
            }
            return seconds.map { it / total }
        }

    fun secondsOf(index: Int): Double =
        if (index >= 0 && index < seconds.size) seconds[index] else 0.0

    /** Gewichtete Summation `Σ (i+1) × t_i` in Minuten — Edwards bzw. Lucia. */
    val weightedMinutes: Double
        get() {
            var sum = 0.0
            for (i in seconds.indices) {
                sum += (i + 1) * seconds[i] / 60
            }
            return sum
        }

    fun toJson(): JsonObject = buildJsonObject {
        put("labels", buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
        put("seconds", buildJsonArray { seconds.forEach { add(JsonPrimitive(it)) } })
    }

    companion object {
        fun empty(labels: List<String>): ZoneDistribution =
            ZoneDistribution(labels = labels, seconds = List(labels.size) { 0.0 })
    }
}

// ---------------------------------------------------------------------------
// GPS-Vorverarbeitung (§3.2)
// ---------------------------------------------------------------------------

/** Ein aufbereitetes Segment zwischen zwei Trackpunkten. */
data class RideSegment(
    /** Sekunden seit Tourstart (Ende des Segments). */
    val timeS: Double,
    val dtS: Double,
    val distanceM: Double,
    /** Geglaettete Geschwindigkeit in m/s. */
    val speedMs: Double,
    /** Beschleunigung in m/s² ueber ein 3-s-Fenster. */
    val accelMs2: Double,
    /**
     * Steigung als `tan θ = Δh / Δs` (§3.2: `θ = atan(Δh/Δs)`), berechnet ueber
     * mindestens 20 m Wegstrecke und geklemmt auf ±25 %.
     */
    val gradeTan: Double,
    /** Geglaettete Hoehe am Segmentende in m. */
    val elevationM: Double,
    /** Geglaettete Hoehendifferenz des Segments in m. */
    val deltaElevationM: Double,
    /** Herzfrequenz in bpm oder `null` (Luecke > [maxHrGapS], kein Sensor). */
    val hr: Int?,
    /** Ob das Segment zur Bewegungszeit zaehlt (§2.1). */
    val moving: Boolean,
)

/** Aufbereitete Tour: geglaettete Hoehe, Geschwindigkeit, Steigung, HF-Zuordnung. */
data class RideSeries(
    val segments: List<RideSegment>,
    val movingTimeS: Double,
    val totalTimeS: Double,
    val movingTimeWithHrS: Double,
    val distanceM: Double,
    val ascentM: Double,
    val hasElevation: Boolean,
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    /** Anteil der Bewegungszeit mit gueltiger Herzfrequenz (0…1). */
    val hrCoverage: Double
        get() = if (movingTimeS > 0) clamp(movingTimeWithHrS / movingTimeS, 0.0, 1.0) else 0.0

    /** Ueber die Bewegungszeit gewichtete Ø-HF, `null` ohne HF-Daten. */
    val avgHr: Double?
        get() {
            var sum = 0.0
            var weight = 0.0
            for (s in segments) {
                if (s.moving && s.hr != null) {
                    sum += s.hr * s.dtS
                    weight += s.dtS
                }
            }
            return if (weight > 0) sum / weight else null
        }

    /** Hoechste Herzfrequenz waehrend der Bewegungszeit. */
    val maxHr: Int?
        get() {
            var best: Int? = null
            for (s in segments) {
                if (s.moving && s.hr != null && (best == null || s.hr > best)) {
                    best = s.hr
                }
            }
            return best
        }

    companion object {
        val EMPTY = RideSeries(
            segments = emptyList(),
            movingTimeS = 0.0,
            totalTimeS = 0.0,
            movingTimeWithHrS = 0.0,
            distanceM = 0.0,
            ascentM = 0.0,
            hasElevation = false,
        )
    }
}

/**
 * Bereitet eine Trackpunktliste fuer alle weiteren Berechnungen auf.
 *
 * Robust gegen: fehlende Zeitstempel, unsortierte Punkte, fehlende Hoehe,
 * GPS-Spruenge (> 25 m/s), Hoehenrauschen und Aufzeichnungsluecken.
 */
fun buildRideSeries(points: List<TrackPoint>, profile: TrainingProfile): RideSeries {
    val timed = points.filter { it.time != null }.sortedBy { it.time!! }
    if (timed.size < 2) {
        return RideSeries.EMPTY
    }

    val n = timed.size
    val t0 = timed.first().time!!
    val times = List(n) { i -> (timed[i].time!! - t0) / 1000.0 }

    // --- Hoehe: fehlende Werte linear fuellen, dann Median 15 s + Mittel 15 s.
    val rawEle = List(n) { i -> timed[i].ele }
    val knownCount = rawEle.count { it != null }
    val hasElevation = knownCount >= 2
    val filled = MutableList(n) { 0.0 }
    if (hasElevation) {
        var lastIdx = -1
        for (i in 0 until n) {
            val value = rawEle[i]
            if (value != null) {
                if (lastIdx < 0) {
                    for (j in 0 until i) {
                        filled[j] = value
                    }
                } else {
                    val span = times[i] - times[lastIdx]
                    for (j in lastIdx + 1 until i) {
                        val f = if (span > 0) (times[j] - times[lastIdx]) / span else 0.0
                        filled[j] = rawEle[lastIdx]!! + f * (value - rawEle[lastIdx]!!)
                    }
                }
                filled[i] = value
                lastIdx = i
            }
        }
        if (lastIdx >= 0) {
            for (j in lastIdx + 1 until n) {
                filled[j] = rawEle[lastIdx]!!
            }
        }
    }
    val smoothEle = if (hasElevation) {
        centeredMean(times, centeredMedian(times, filled, 15.0), 15.0)
    } else {
        filled
    }

    // --- Distanz & Rohgeschwindigkeit, Ausreisser verwerfen.
    val segDist = MutableList(n) { 0.0 }
    val segDt = MutableList(n) { 0.0 }
    val rawSpeed = MutableList(n) { 0.0 }
    for (i in 1 until n) {
        val dt = times[i] - times[i - 1]
        val d = haversineM(timed[i - 1], timed[i])
        segDt[i] = dt
        segDist[i] = d
        if (dt > 0) {
            val v = d / dt
            rawSpeed[i] = if (v > 25) rawSpeed[i - 1] else v
        } else {
            rawSpeed[i] = rawSpeed[i - 1]
        }
    }
    rawSpeed[0] = if (n > 1) rawSpeed[1] else 0.0
    val speed = centeredMean(times, rawSpeed, 5.0)

    // --- kumulierte Distanz fuer die Steigungsberechnung ueber ≥ 20 m.
    val cum = MutableList(n) { 0.0 }
    for (i in 1 until n) {
        cum[i] = cum[i - 1] + segDist[i]
    }

    // --- Beschleunigung ueber 3-s-Fenster.
    val accel = MutableList(n) { 0.0 }
    for (i in 1 until n) {
        var j = i
        while (j > 0 && times[i] - times[j] < 3) {
            j--
        }
        val dt = times[i] - times[j]
        if (dt > 0) {
            val a = (speed[i] - speed[j]) / dt
            accel[i] = if (abs(a) > 3) 0.0 else a
        }
    }

    val restingHr = profile.restingHr
    val segments = mutableListOf<RideSegment>()
    var movingTimeS = 0.0
    var movingWithHrS = 0.0
    var totalTimeS = 0.0
    var ascentM = 0.0
    var ascentReference = if (hasElevation) smoothEle[0] else 0.0

    for (i in 1 until n) {
        val dt = segDt[i]
        if (dt <= 0) {
            continue
        }
        totalTimeS += dt

        // Steigung ueber mindestens 20 m Wegstrecke.
        var gradeTan = 0.0
        if (hasElevation) {
            var j = i
            while (j > 0 && cum[i] - cum[j] < 20) {
                j--
            }
            val ds = cum[i] - cum[j]
            if (ds >= 5) {
                gradeTan = clamp((smoothEle[i] - smoothEle[j]) / ds, -0.25, 0.25)
            }
        }

        // HF-Zuordnung: Luecken > 30 s nicht interpolieren.
        var hr: Int?
        if (dt <= maxHrGapS) {
            hr = timed[i].hr ?: timed[i - 1].hr
        } else {
            hr = timed[i].hr
            if (hr != null && dt > maxSegmentDtS) {
                hr = null
            }
        }
        if (hr != null && (hr <= 20 || hr > 250)) {
            hr = null
        }

        val isGap = dt > maxSegmentDtS
        val moving = !isGap &&
            (speed[i] > movingSpeedMs || (hr != null && hr > movingHrRestFactor * restingHr))

        if (moving) {
            movingTimeS += dt
            if (hr != null) {
                movingWithHrS += dt
            }
        }

        if (hasElevation) {
            val diff = smoothEle[i] - ascentReference
            if (abs(diff) >= 3) {
                if (diff > 0) {
                    ascentM += diff
                }
                ascentReference = smoothEle[i]
            }
        }

        segments.add(
            RideSegment(
                timeS = times[i],
                dtS = dt,
                distanceM = segDist[i],
                speedMs = speed[i],
                accelMs2 = accel[i],
                gradeTan = gradeTan,
                elevationM = if (hasElevation) smoothEle[i] else 0.0,
                deltaElevationM = if (hasElevation) smoothEle[i] - smoothEle[i - 1] else 0.0,
                hr = hr,
                moving = moving,
            ),
        )
    }

    return RideSeries(
        segments = segments,
        movingTimeS = movingTimeS,
        totalTimeS = totalTimeS,
        movingTimeWithHrS = movingWithHrS,
        distanceM = cum[n - 1],
        ascentM = ascentM,
        hasElevation = hasElevation,
    )
}

// ---------------------------------------------------------------------------
// 2. Tourlast aus Herzfrequenz (§2)
// ---------------------------------------------------------------------------

/** Ergebnis der HF-basierten Lastberechnung einer Tour. */
data class HeartRateLoad(
    /** Ob die HF-Last als Primaerlast taugt (Abdeckung ≥ 80 % der Bewegungszeit). */
    val available: Boolean,
    val unavailableReason: String?,
    /** Sample-weiser Banister-TRIMP (§2.1). */
    val trimpBanister: Double,
    /** Edwards-Zonen-TRIMP als Sekundaermetrik (§2.2). */
    val trimpEdwards: Double,
    /** Normalisierte Last („hrTSS", 1 h an der Schwelle = 100, §2.4). */
    val load: Double,
    val hrCoverage: Double,
    val movingTimeS: Double,
    val avgHr: Double?,
    val maxHr: Int?,
    /** Zeit in den 5 Friel-Zonen (LTHR-verankert). */
    val frielZones: ZoneDistribution,
    /** Zeit in den 3 Lucia-Domaenen LIT/MIT/HIT. */
    val luciaZones: ZoneDistribution,
    /** Zeit in den 5 Edwards-Zonen (%HFmax). */
    val edwardsZones: ZoneDistribution,
    /** Snapshot der benutzten Zonengrenzen. */
    val zonesUsed: TrainingZones,
    val confidence: Confidence,
) {
    /** Lucia-TRIMP (`1×LIT + 2×MIT + 3×HIT`, nur informativ, §2.3). */
    val trimpLucia: Double get() = luciaZones.weightedMinutes

    /** Zeit ueber der Schwelle in Sekunden — Proxy fuer „harten Reiz gesetzt". */
    val secondsAboveLthr: Double get() = luciaZones.secondsOf(2)

    companion object {
        fun unavailable(reason: String, zones: TrainingZones): HeartRateLoad = HeartRateLoad(
            available = false,
            unavailableReason = reason,
            trimpBanister = 0.0,
            trimpEdwards = 0.0,
            load = 0.0,
            hrCoverage = 0.0,
            movingTimeS = 0.0,
            avgHr = null,
            maxHr = null,
            frielZones = ZoneDistribution.empty(frielZoneLabels),
            luciaZones = ZoneDistribution.empty(luciaZoneLabels),
            edwardsZones = ZoneDistribution.empty(edwardsZoneLabels),
            zonesUsed = zones,
            confidence = Confidence.NONE,
        )
    }
}

/** Banister-TRIMP-Beitrag eines einzelnen Samples (§2.1). */
fun trimpSampleContribution(
    hr: Double,
    dtS: Double,
    profile: TrainingProfile,
): Double {
    if (dtS <= 0) {
        return 0.0
    }
    val x = clamp((hr - profile.restingHr) / profile.hrReserve, 0.0, 1.05)
    return (dtS / 60) * x * profile.trimpA * exp(profile.trimpB * x)
}

/** TRIMP einer Stunde exakt an der Schwelle — Normierungsanker (§2.4). */
fun trimpReference(profile: TrainingProfile): Double {
    val xRef = clamp((profile.lthr - profile.restingHr) / profile.hrReserve, 0.05, 1.0)
    return 60 * xRef * profile.trimpA * exp(profile.trimpB * xRef)
}

/** Rechnet einen Banister-TRIMP auf die 100er-Skala um (§2.4). */
fun normalizeTrimp(trimp: Double, profile: TrainingProfile): Double {
    val ref = trimpReference(profile)
    if (ref <= 0) {
        return 0.0
    }
    return min(100 * trimp / ref, maxLoad)
}

/** HF-basierte Tourlast inklusive Zonenverteilung. */
fun computeHeartRateLoad(series: RideSeries, profile: TrainingProfile): HeartRateLoad {
    val zones = profile.zones
    if (series.isEmpty) {
        return HeartRateLoad.unavailable(
            "Keine auswertbaren Trackpunkte mit Zeitstempel.",
            zones,
        )
    }
    if (series.movingTimeS <= 0) {
        return HeartRateLoad.unavailable("Keine Bewegungszeit erkannt.", zones)
    }

    var trimp = 0.0
    val friel = MutableList(5) { 0.0 }
    val lucia = MutableList(3) { 0.0 }
    val edwards = MutableList(5) { 0.0 }

    for (s in series.segments) {
        if (!s.moving || s.hr == null) {
            continue
        }
        val hr = s.hr.toDouble()
        trimp += trimpSampleContribution(hr = hr, dtS = s.dtS, profile = profile)
        friel[zones.frielZoneIndex(hr)] += s.dtS
        lucia[zones.luciaZoneIndex(hr)] += s.dtS
        val e = zones.edwardsZoneIndex(hr)
        if (e != null) {
            edwards[e] += s.dtS
        }
    }

    val frielZones = ZoneDistribution(labels = frielZoneLabels, seconds = friel)
    val luciaZones = ZoneDistribution(labels = luciaZoneLabels, seconds = lucia)
    val edwardsZones = ZoneDistribution(labels = edwardsZoneLabels, seconds = edwards)

    val coverage = series.hrCoverage
    if (series.movingTimeWithHrS <= 0) {
        return HeartRateLoad.unavailable(
            "Für diese Tour liegt keine Herzfrequenz vor.",
            zones,
        )
    }

    var confidence = if (coverage >= 0.9) Confidence.HIGH else Confidence.MEDIUM
    if (profile.anchorConfidence == Confidence.LOW) {
        confidence = downgrade(confidence)
    }
    if (profile.sex == Sex.UNBEKANNT) {
        confidence = downgrade(confidence)
    }

    val available = coverage >= minHrCoverage

    return HeartRateLoad(
        available = available,
        unavailableReason = if (available) {
            null
        } else {
            "Herzfrequenz deckt nur " +
                "${dartRound(coverage * 100).toInt()} % der Bewegungszeit ab " +
                "(mindestens ${dartRound(minHrCoverage * 100).toInt()} % nötig)."
        },
        trimpBanister = trimp,
        trimpEdwards = edwardsZones.weightedMinutes,
        load = normalizeTrimp(trimp, profile),
        hrCoverage = coverage,
        movingTimeS = series.movingTimeS,
        avgHr = series.avgHr,
        maxHr = series.maxHr,
        frielZones = frielZones,
        luciaZones = luciaZones,
        edwardsZones = edwardsZones,
        zonesUsed = zones,
        confidence = if (available) confidence else Confidence.NONE,
    )
}

// ---------------------------------------------------------------------------
// 3. Physik-Fallback (§3.2/§3.3)
// ---------------------------------------------------------------------------

/** Luftdichte in kg/m³ auf Hoehe [elevationM] (§3.2). */
fun airDensity(elevationM: Double, temperatureK: Double = 288.15): Double {
    val t = if (temperatureK <= 0) 288.15 else temperatureK
    return 1.225 * (288.15 / t) * exp(-clamp(elevationM, -500.0, 6000.0) / 8435)
}

/** Geschaetzte Fahrerleistung eines Samples in Watt (§3.2). */
fun estimateSamplePowerW(
    speedMs: Double,
    accelMs2: Double,
    gradeTan: Double,
    elevationM: Double,
    profile: TrainingProfile,
): Double {
    if (speedMs <= 0) {
        return 0.0
    }
    val m = profile.totalMassKg
    val tan = clamp(gradeTan, -0.25, 0.25)
    val norm = sqrt(1 + tan * tan)
    val sin = tan / norm
    val cos = 1 / norm
    val rho = airDensity(elevationM)

    val fGrav = m * gravity * sin
    val fRoll = profile.crr * m * gravity * cos
    val fAir = 0.5 * rho * profile.cda * speedMs * speedMs
    val fAcc = m * accelMs2

    val pWheel = (fGrav + fRoll + fAir + fAcc) * speedMs
    val eta = if (profile.driveEfficiency <= 0) 1.0 else profile.driveEfficiency
    return max(0.0, pWheel) / eta
}

/** Ein Sample der geschaetzten Leistungsreihe (nur Bewegungszeit). */
data class PowerSample(
    val timeS: Double,
    val dtS: Double,
    val powerW: Double,
    val speedMs: Double,
    val elevationM: Double,
    val deltaElevationM: Double,
    val hr: Int?,
)

/** Geschaetzte Leistungsreihe einer Tour. */
data class PowerSeries(
    val samples: List<PowerSample>,
    /** Nachlaufender 30-s-Mittelwert der Leistung — Basis fuer NP (§3.3). */
    val rollingMean30sW: List<Double>,
) {
    val isEmpty: Boolean get() = samples.isEmpty()

    val movingTimeS: Double get() = samples.fold(0.0) { a, s -> a + s.dtS }

    val avgPowerW: Double
        get() {
            val t = movingTimeS
            if (t <= 0) {
                return 0.0
            }
            return samples.fold(0.0) { a, s -> a + s.powerW * s.dtS } / t
        }

    /** Normalized Power nach Coggan (§3.3). */
    val normalizedPowerW: Double
        get() {
            val t = movingTimeS
            if (t <= 0) {
                return 0.0
            }
            var sum = 0.0
            for (i in samples.indices) {
                val p = rollingMean30sW[i]
                sum += p.pow(4) * samples[i].dtS
            }
            return (sum / t).pow(0.25)
        }

    val hrCoverage: Double
        get() {
            val t = movingTimeS
            if (t <= 0) {
                return 0.0
            }
            val withHr = samples.filter { it.hr != null }.fold(0.0) { a, s -> a + s.dtS }
            return clamp(withHr / t, 0.0, 1.0)
        }

    val avgHr: Double?
        get() {
            var sum = 0.0
            var weight = 0.0
            for (s in samples) {
                if (s.hr != null) {
                    sum += s.hr * s.dtS
                    weight += s.dtS
                }
            }
            return if (weight > 0) sum / weight else null
        }

    /** Positive Hoehendifferenz der Reihe in m. */
    val ascentM: Double
        get() {
            var sum = 0.0
            for (s in samples) {
                if (s.deltaElevationM > 0) {
                    sum += s.deltaElevationM
                }
            }
            return sum
        }

    /** Teilreihe ueber einen Indexbereich, inklusive neu berechnetem 30-s-Mittel. */
    fun slice(start: Int, end: Int): PowerSeries {
        if (start < 0 || end > samples.size || start >= end) {
            return EMPTY
        }
        return PowerSeries(
            samples = samples.subList(start, end),
            rollingMean30sW = rollingMean30sW.subList(start, end),
        )
    }

    companion object {
        val EMPTY = PowerSeries(samples = emptyList(), rollingMean30sW = emptyList())
    }
}

/** Baut die geschaetzte Leistungsreihe aus der aufbereiteten Tour (§3.2). */
fun buildPowerSeries(series: RideSeries, profile: TrainingProfile): PowerSeries {
    if (series.isEmpty) {
        return PowerSeries.EMPTY
    }
    val samples = mutableListOf<PowerSample>()
    for (s in series.segments) {
        if (!s.moving) {
            continue
        }
        samples.add(
            PowerSample(
                timeS = s.timeS,
                dtS = s.dtS,
                powerW = estimateSamplePowerW(
                    speedMs = s.speedMs,
                    accelMs2 = s.accelMs2,
                    gradeTan = s.gradeTan,
                    elevationM = s.elevationM,
                    profile = profile,
                ),
                speedMs = s.speedMs,
                elevationM = s.elevationM,
                deltaElevationM = s.deltaElevationM,
                hr = s.hr,
            ),
        )
    }
    if (samples.isEmpty()) {
        return PowerSeries.EMPTY
    }
    val t = samples.map { it.timeS }
    val p = samples.map { it.powerW }
    val w = samples.map { it.dtS }
    return PowerSeries(
        samples = samples,
        rollingMean30sW = trailingWeightedMean(t, p, w, 30.0),
    )
}

/** Ergebnis des Physikmodells fuer eine Tour. */
data class PhysicsEstimate(
    val available: Boolean,
    val unavailableReason: String?,
    val series: PowerSeries,
    val movingTimeS: Double,
    /** Geschaetzte Ø-Leistung in W. **Modelliert, nicht gemessen** (±15–25 %). */
    val avgPowerW: Double,
    val normalizedPowerW: Double,
    /** `NP / Ø-Leistung` — > 1,25 sehr stochastisch, < 1,05 sehr gleichmaessig. */
    val variabilityIndex: Double,
    val intensityFactor: Double,
    /** Last auf der 100er-Skala aus dem Physikmodell (vor α-Kalibrierung). */
    val eTss: Double,
    val eftpW: Double,
    val kcal: Double,
    val confidence: Confidence,
) {
    /** Textbaustein ohne Overclaim (§8.5). */
    val powerText: String
        get() = if (available) {
            "Geschätzte Leistung ≈ ${dartRound(avgPowerW).toInt()} W " +
                "(aus GPS & Profil, ±15–25 %)"
        } else {
            "Leistung nicht schätzbar"
        }

    companion object {
        fun unavailable(reason: String): PhysicsEstimate = PhysicsEstimate(
            available = false,
            unavailableReason = reason,
            series = PowerSeries.EMPTY,
            movingTimeS = 0.0,
            avgPowerW = 0.0,
            normalizedPowerW = 0.0,
            variabilityIndex = 0.0,
            intensityFactor = 0.0,
            eTss = 0.0,
            eftpW = 0.0,
            kcal = 0.0,
            confidence = Confidence.NONE,
        )
    }
}

/** Physikbasierte Lastschaetzung einer Tour ohne (oder mit) Herzfrequenz. */
fun computePhysicsEstimate(
    series: RideSeries,
    profile: TrainingProfile,
    eftpW: Double? = null,
): PhysicsEstimate {
    if (series.isEmpty) {
        return PhysicsEstimate.unavailable(
            "Keine auswertbaren Trackpunkte mit Zeitstempel.",
        )
    }
    if (!series.hasElevation) {
        return PhysicsEstimate.unavailable(
            "Ohne Höhenprofil lässt sich die Leistung nicht schätzen.",
        )
    }
    if (profile.weightKg <= 0) {
        return PhysicsEstimate.unavailable(
            "Ohne Gewichtsangabe lässt sich die Leistung nicht schätzen.",
        )
    }
    val power = buildPowerSeries(series, profile)
    if (power.isEmpty || power.movingTimeS < 60) {
        return PhysicsEstimate.unavailable(
            "Zu wenig Bewegungszeit für eine Leistungsschätzung.",
        )
    }
    if (series.distanceM < 200) {
        return PhysicsEstimate.unavailable(
            "Zu kurze Strecke für eine Leistungsschätzung.",
        )
    }

    val avg = power.avgPowerW
    val np = power.normalizedPowerW
    val ftp = clamp(eftpW ?: profile.eftpW, minEftpW, maxEftpW)
    val ifValue = if (ftp > 0) np / ftp else 0.0
    val hours = power.movingTimeS / 3600
    val eTss = min(hours * ifValue * ifValue * 100, maxLoad)
    val kcal = avg * power.movingTimeS / (1000 * 0.24)

    // Das Dokument stuft das Physikmodell grundsaetzlich als „medium" ein (§3.1).
    // Sehr kurze oder sehr stochastische Fahrten stufen wir zusaetzlich ab.
    var confidence = Confidence.MEDIUM
    if (power.movingTimeS < 900 || (avg > 0 && np / avg > 1.3)) {
        confidence = Confidence.LOW
    }

    return PhysicsEstimate(
        available = true,
        unavailableReason = null,
        series = power,
        movingTimeS = power.movingTimeS,
        avgPowerW = avg,
        normalizedPowerW = np,
        variabilityIndex = if (avg > 0) np / avg else 0.0,
        intensityFactor = ifValue,
        eTss = eTss,
        eftpW = ftp,
        kcal = kcal,
        confidence = confidence,
    )
}

/** Bestes nachlaufendes Leistungsmittel ueber [windowS] Sekunden. */
fun bestRollingMeanPowerW(series: PowerSeries, windowS: Double = 1200.0): Double? {
    if (series.isEmpty || series.movingTimeS < windowS) {
        return null
    }
    val t = series.samples.map { it.timeS }
    val p = series.samples.map { it.powerW }
    val w = series.samples.map { it.dtS }
    val rolling = trailingWeightedMean(t, p, w, windowS)
    var best: Double? = null
    for (i in rolling.indices) {
        // Erst werten, wenn das Fenster tatsaechlich voll ist.
        if (t[i] - t.first() < windowS) {
            continue
        }
        if (best == null || rolling[i] > best) {
            best = rolling[i]
        }
    }
    return best
}

/**
 * eFTP aus den Leistungsreihen der letzten 90 Tage (§3.3).
 *
 * `0,95 × bestes 20-min-Mittel`, geklemmt auf 100…400 W. Ohne harte Tour
 * bleibt der Profil-Default (2,4 W/kg).
 */
fun estimateEftpW(recent: Iterable<PowerSeries>, profile: TrainingProfile): Double {
    var best: Double? = null
    for (s in recent) {
        val v = bestRollingMeanPowerW(s)
        if (v != null && (best == null || v > best)) {
            best = v
        }
    }
    if (best == null) {
        return profile.eftpW
    }
    return clamp(0.95 * best, minEftpW, maxEftpW)
}

// ---------------------------------------------------------------------------
// Kalibrierung HF ↔ Physik (§3.3)
// ---------------------------------------------------------------------------

/** Ein Tourenpaar, fuer das beide Lastpfade berechenbar waren. */
data class LoadCalibrationSample(
    val loadHr: Double,
    val loadPhysics: Double,
)

/** Personenspezifischer Faktor `α = median(load_hr / load_phys)`. */
data class LoadCalibration(
    val alpha: Double,
    val sampleCount: Int,
    /** Ob α ausserhalb `[0.6, 1.6]` lag und deshalb auf 1,0 gesetzt wurde. */
    val clamped: Boolean,
    val confidence: Confidence,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("alpha", alpha)
        put("sampleCount", sampleCount)
        put("clamped", clamped)
        put("confidence", confidence.jsonName)
    }

    companion object {
        /** Neutraler Default: keine Korrektur. */
        val NEUTRAL = LoadCalibration(
            alpha = 1.0,
            sampleCount = 0,
            clamped = false,
            confidence = Confidence.LOW,
        )

        fun fromJson(json: JsonObject): LoadCalibration {
            val rawConfidence = (json.fieldOrNull("confidence") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            return LoadCalibration(
                alpha = json.optionalDouble("alpha") ?: 1.0,
                sampleCount = json.optionalInt("sampleCount") ?: 0,
                clamped = (json.fieldOrNull("clamped") as? JsonPrimitive)?.booleanOrNull == true,
                confidence = Confidence.entries.firstOrNull { it.jsonName == rawConfidence }
                    ?: Confidence.LOW,
            )
        }
    }
}

/**
 * Bestimmt α aus Touren, fuer die HF- und Physiklast vorliegen (§3.3).
 *
 * Es zaehlen die juengsten [window] Paare (die Liste wird als chronologisch
 * aufsteigend erwartet). Unter [minSamples] Paaren bleibt α = 1,0.
 */
fun computeLoadCalibration(
    samples: List<LoadCalibrationSample>,
    window: Int = 20,
    minSamples: Int = 5,
): LoadCalibration {
    val usable = samples.filter {
        it.loadHr.isFinite() &&
            it.loadPhysics.isFinite() &&
            it.loadHr > 0 &&
            it.loadPhysics > 0
    }
    val recent = if (usable.size > window) usable.subList(usable.size - window, usable.size) else usable
    if (recent.size < minSamples) {
        return LoadCalibration(
            alpha = 1.0,
            sampleCount = recent.size,
            clamped = false,
            confidence = Confidence.LOW,
        )
    }
    val ratio = median(recent.map { it.loadHr / it.loadPhysics })!!
    if (ratio < alphaMin || ratio > alphaMax) {
        return LoadCalibration(
            alpha = 1.0,
            sampleCount = recent.size,
            clamped = true,
            confidence = Confidence.LOW,
        )
    }
    return LoadCalibration(
        alpha = ratio,
        sampleCount = recent.size,
        clamped = false,
        confidence = if (recent.size >= 10) Confidence.MEDIUM else Confidence.LOW,
    )
}

// ---------------------------------------------------------------------------
// Fallback-Kaskade zur einheitlichen Last (§3.1)
// ---------------------------------------------------------------------------

/** Woher die Tourlast stammt (§3.1). */
enum class LoadSource { HERZFREQUENZ, PHYSIK, RPE, HEURISTIK, KEINE }

val loadSourceLabels: Map<LoadSource, String> = mapOf(
    LoadSource.HERZFREQUENZ to "aus Herzfrequenz",
    LoadSource.PHYSIK to "aus GPS-Leistungsschätzung",
    LoadSource.RPE to "aus Anstrengungsempfinden",
    LoadSource.HEURISTIK to "grob geschätzt aus Distanz und Höhenmetern",
    LoadSource.KEINE to "nicht berechenbar",
)

/** Vollstaendige Lastauswertung einer Tour. */
data class RideLoad(
    /** Last auf der einheitlichen 100er-Skala (1 h an der Schwelle = 100). */
    val load: Double,
    val source: LoadSource,
    val confidence: Confidence,
    val heartRate: HeartRateLoad,
    val physics: PhysicsEstimate,
    /** Deutschsprachiger Hinweis zur Herkunft bzw. zum Fehlen der Last. */
    val note: String,
) {
    val available: Boolean get() = source != LoadSource.KEINE
}

/** Heuristische Last aus Distanz, Dauer und Hoehenmetern (§3.5, letzte Instanz). */
fun heuristicLoad(
    distanceKm: Double,
    durationH: Double,
    ascentM: Double,
): Double {
    if (durationH <= 0) {
        return 0.0
    }
    val equivKm = distanceKm + ascentM / 10
    val base = durationH * 55
    val factor = clamp(equivKm / (durationH * 22), 0.7, 1.5)
    return min(base * factor, maxLoad)
}

/** Bestimmt die Tourlast ueber die Fallback-Kaskade A → B → C → D (§3.1). */
fun computeRideLoad(
    points: List<TrackPoint>,
    profile: TrainingProfile,
    stats: RideStats? = null,
    calibration: LoadCalibration = LoadCalibration.NEUTRAL,
    rpe: Double? = null,
    rpeFactor: Double = defaultRpeFactor,
    eftpW: Double? = null,
): RideLoad {
    val series = buildRideSeries(points, profile)
    val hr = computeHeartRateLoad(series, profile)
    val physics = computePhysicsEstimate(series, profile, eftpW = eftpW)

    // Stufe A — Herzfrequenz.
    if (hr.available && hr.load > 0) {
        return RideLoad(
            load = min(hr.load, maxLoad),
            source = LoadSource.HERZFREQUENZ,
            confidence = hr.confidence,
            heartRate = hr,
            physics = physics,
            note = "Last aus der Herzfrequenz berechnet " +
                "(${dartRound(hr.hrCoverage * 100).toInt()} % Abdeckung).",
        )
    }

    // Stufe B — Physikmodell.
    if (physics.available && physics.eTss > 0) {
        val alpha = calibration.alpha
        return RideLoad(
            load = min(alpha * physics.eTss, maxLoad),
            source = LoadSource.PHYSIK,
            confidence = if (calibration.clamped) {
                downgrade(physics.confidence)
            } else {
                physics.confidence
            },
            heartRate = hr,
            physics = physics,
            note = "Last aus der geschätzten Leistung berechnet " +
                "(GPS & Profil, ±15–25 %).",
        )
    }

    // Stufe C — Anstrengungsempfinden.
    val movingMin = series.movingTimeS / 60
    if (rpe != null && rpe > 0 && movingMin > 0) {
        return RideLoad(
            load = min(rpeFactor * movingMin * clamp(rpe, 0.0, 10.0), maxLoad),
            source = LoadSource.RPE,
            confidence = Confidence.LOW,
            heartRate = hr,
            physics = physics,
            note = "Last aus deinem Anstrengungsempfinden geschätzt.",
        )
    }

    // Stufe D — reine Distanz/Hoehen-Heuristik.
    val distanceKm = stats?.distanceKm ?: (if (series.distanceM > 0) series.distanceM / 1000 else 0.0)
    val durationS = stats?.movingTimeS?.toDouble()
        ?: stats?.durationS?.toDouble()
        ?: (if (series.movingTimeS > 0) series.movingTimeS else series.totalTimeS)
    val ascentM = stats?.ascentM ?: series.ascentM
    if (distanceKm > 0 && durationS > 0) {
        return RideLoad(
            load = heuristicLoad(
                distanceKm = distanceKm,
                durationH = durationS / 3600,
                ascentM = ascentM,
            ),
            source = LoadSource.HEURISTIK,
            confidence = Confidence.LOW,
            heartRate = hr,
            physics = physics,
            note = "Grobe Schätzung aus Distanz, Dauer und Höhenmetern — " +
                "ohne Herzfrequenz oder Höhenprofil nur eine Näherung.",
        )
    }

    return RideLoad(
        load = 0.0,
        source = LoadSource.KEINE,
        confidence = Confidence.NONE,
        heartRate = hr,
        physics = physics,
        note = "Für diese Tour liegen zu wenige Daten für eine Lastberechnung vor.",
    )
}

/** Bequemlichkeits-Variante von [computeRideLoad] fuer ein [Ride]. */
fun computeRideLoadForRide(
    ride: Ride,
    profile: TrainingProfile,
    calibration: LoadCalibration = LoadCalibration.NEUTRAL,
    rpe: Double? = null,
    eftpW: Double? = null,
): RideLoad = computeRideLoad(
    points = ride.points,
    profile = profile,
    stats = ride.stats,
    calibration = calibration,
    rpe = rpe,
    eftpW = eftpW,
)
