package de.trailscape.app.ui

import de.trailscape.core.Confidence
import de.trailscape.core.DailyRecommendation
import de.trailscape.core.DeloadRecommendation
import de.trailscape.core.FitnessPoint
import de.trailscape.core.FitnessSeries
import de.trailscape.core.HrvAssessment
import de.trailscape.core.LoadCalibration
import de.trailscape.core.LoadCalibrationSample
import de.trailscape.core.LoadEntry
import de.trailscape.core.LoadSource
import de.trailscape.core.Readiness
import de.trailscape.core.RestingHrAssessment
import de.trailscape.core.Ride
import de.trailscape.core.RideLoad
import de.trailscape.core.SleepAssessment
import de.trailscape.core.SteadySegment
import de.trailscape.core.TrainingProfile
import de.trailscape.core.VitalsSummary
import de.trailscape.core.Vo2MaxEstimate
import de.trailscape.core.WeeklyLoadTarget
import de.trailscape.core.assessDeload
import de.trailscape.core.assessHrv
import de.trailscape.core.assessRestingHeartRate
import de.trailscape.core.assessSleep
import de.trailscape.core.availableReadinessScores
import de.trailscape.core.computeFitnessSeries
import de.trailscape.core.computeLoadCalibration
import de.trailscape.core.computeReadiness
import de.trailscape.core.computeReadinessSeries
import de.trailscape.core.computeRideLoadForRide
import de.trailscape.core.dailyLoadsFrom
import de.trailscape.core.estimateVo2Max
import de.trailscape.core.extractSteadySegments
import de.trailscape.core.maxLoad
import de.trailscape.core.median
import de.trailscape.core.recommendToday
import de.trailscape.core.weeklyLoadTarget
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.min

/**
 * Rechenteil der Zustandsschicht — Port von `_computeInsights()` aus
 * `lib/state.dart`.
 *
 * Bewusst in einer eigenen Datei OHNE jeden Android-Import: [AppViewModel]
 * haelt nur noch Zustand und Nebenlaeufigkeit, die eigentliche Auswertung ist
 * eine reine Funktion ueber (Touren, Vitaldaten, Profil, Uhrzeit) und laesst
 * sich damit als normaler JVM-Unit-Test pruefen (siehe
 * `app/src/test/.../TrainingInsightsTest.kt`) — `:app` braucht dafuer kein
 * Robolectric.
 */

/** SharedPreferences-Schluessel des Trainingsprofils (wie in Dart). */
const val PROFILE_STORAGE_KEY: String = "trailscape.profile"

/** Profil, solange die Nutzerin noch nichts eingetragen hat. */
val defaultTrainingProfile: TrainingProfile = TrainingProfile(ageYears = 40)

/**
 * Fenster der Vitaldaten in Tagen.
 *
 * Der Rechenkern braucht fuer die Ruhepuls-Baseline die Tage −8 … −60 (≥ 21
 * Werte) und fuer die Schlaf-Baseline 28 Naechte.
 */
const val VITALS_WINDOW_DAYS: Int = 60

/** Ziel-Rampenrate (CTL-Punkte pro Woche) fuer das empfohlene Wochenziel. */
const val DEFAULT_TARGET_RAMP_PER_WEEK: Double = 4.0

/**
 * Entspricht Darts `DateTime.fromMillisecondsSinceEpoch(ms)`: der Zeitstempel
 * wird in *lokaler* Zeit interpretiert.
 *
 * `:core` hat dieselbe Hilfsfunktion (`dartLocalOf`), sie ist dort aber
 * `internal` und damit ausserhalb des Moduls nicht sichtbar — hier deshalb
 * bewusst eine zweite, identische Umrechnung statt einer Aenderung an `:core`.
 */
fun localOfEpochMs(ms: Long): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

/**
 * Gebuendelte Trainingsauswertung — 1:1 die Felder von `TrainingInsights` aus
 * `lib/state.dart`.
 */
data class TrainingInsights(
    /**
     * Effektiv benutztes Profil (Nutzerprofil plus gemessener Ruhepuls, falls
     * die Nutzerin keinen eigenen Wert hinterlegt hat).
     */
    val profile: TrainingProfile,
    /** Tourlast je Tour-ID, bereits mit der Kalibrierung α skaliert. */
    val rideLoads: Map<String, RideLoad>,
    val calibration: LoadCalibration,
    val fitness: FitnessSeries,
    val restingHr: RestingHrAssessment,
    val hrv: HrvAssessment,
    val sleep: SleepAssessment,
    val readiness: Readiness,
    /**
     * Rueckwirkend berechnete Readiness der letzten sieben Tage (nur Tage mit
     * Gesamtscore) — Grundlage des Deload-Triggers.
     */
    val readinessLast7: List<Double>,
    val recommendation: DailyRecommendation,
    val deload: DeloadRecommendation,
    /**
     * Empfohlene Wochenlast fuer [DEFAULT_TARGET_RAMP_PER_WEEK]; `null`,
     * solange keine Tageswerte vorliegen.
     */
    val weeklyTarget: WeeklyLoadTarget?,
    val vo2max: Vo2MaxEstimate,
    /** Summe der Tageslasten der letzten 7 Tage. */
    val weeklyLoad: Double,
    /**
     * Auf eine Woche hochgerechneter Mittelwert der letzten (bis zu) 4 Wochen;
     * `null`, solange keine Tageswerte vorliegen.
     */
    val fourWeekMeanWeeklyLoad: Double?,
) {
    /** Aktuellster Punkt der Fitness-Kurve. */
    val latest: FitnessPoint? get() = fitness.latest

    /** Trainingslast einer einzelnen Tour; `null`, wenn unbekannt. */
    fun rideLoad(rideId: String): RideLoad? = rideLoads[rideId]
}

/**
 * Effektiv benutztes Profil: fehlt ein eigener Ruhepuls, wird der aus den
 * Vitaldaten gemessene Median eingesetzt (Port von `AppState.effectiveProfile`).
 */
fun effectiveProfile(profile: TrainingProfile, vitals: VitalsSummary?): TrainingProfile {
    if (profile.restingHrOverride != null) {
        return profile
    }
    val series = vitals?.restingHeartRate?.series
    if (series.isNullOrEmpty()) {
        return profile
    }
    val baseline = median(series.map { it.value }) ?: return profile
    return profile.copyWith(restingHrOverride = baseline)
}

/**
 * Cache-Schluessel einer Tourlast: Tour-Identitaet inklusive der Teile, die
 * sich nachtraeglich aendern koennen (HF-Merge ergaenzt Punkte und Ø-Puls),
 * plus das komplette Profil.
 */
private fun loadKey(ride: Ride, profileSignature: String): String =
    "${ride.id}|${ride.createdAt}|${ride.points.size}|" +
        "${ride.stats.avgHrBpm ?: "-"}|$profileSignature"

/**
 * Berechnet die komplette Trainingsauswertung.
 *
 * @param baseLoadCache Cache der **unkalibrierten** Tourlasten. Wird in-place
 *   auf die aktuell gebrauchten Eintraege reduziert (wie das Dart-Original,
 *   das `_baseLoadCache` bei jedem Lauf leert und neu fuellt) — dadurch
 *   waechst er nicht mit geloeschten Touren mit. Ein leerer/uebergebener
 *   Default rechnet einfach alles neu.
 */
fun computeInsights(
    rides: List<Ride>,
    vitals: VitalsSummary?,
    profile: TrainingProfile,
    now: LocalDateTime = LocalDateTime.now(),
    baseLoadCache: MutableMap<String, RideLoad> = mutableMapOf(),
): TrainingInsights {
    val effective = effectiveProfile(profile, vitals)
    val profileSignature = effective.toJson().toString()

    val ordered = rides.sortedBy { it.createdAt }

    // Rohlasten je Tour — nur fuer neue/geaenderte Touren wirklich gerechnet.
    val base = LinkedHashMap<String, RideLoad>()
    for (ride in ordered) {
        val key = loadKey(ride, profileSignature)
        base[key] = baseLoadCache[key] ?: computeRideLoadForRide(ride, effective)
    }
    baseLoadCache.clear()
    baseLoadCache.putAll(base)

    // Kalibrierung α aus allen Touren, fuer die beide Lastpfade tragen.
    val samples = mutableListOf<LoadCalibrationSample>()
    for (ride in ordered) {
        val load = base.getValue(loadKey(ride, profileSignature))
        if (load.heartRate.available &&
            load.heartRate.load > 0 &&
            load.physics.available &&
            load.physics.eTss > 0
        ) {
            samples.add(
                LoadCalibrationSample(
                    loadHr = load.heartRate.load,
                    loadPhysics = load.physics.eTss,
                ),
            )
        }
    }
    val calibration = computeLoadCalibration(samples)

    val rideLoads = LinkedHashMap<String, RideLoad>()
    for (ride in ordered) {
        rideLoads[ride.id] = calibrated(base.getValue(loadKey(ride, profileSignature)), calibration)
    }

    val fitness = computeFitnessSeries(
        dailyLoadsFrom(
            ordered.map { ride ->
                LoadEntry(
                    at = localOfEpochMs(ride.createdAt),
                    load = rideLoads.getValue(ride.id).load,
                )
            },
        ),
        until = now,
    )

    val restingHrSeries = vitals?.restingHeartRate?.series ?: emptyList()
    val sleepSeries = vitals?.sleepHours?.series ?: emptyList()
    val hrvSeries = vitals?.heartRateVariability?.series ?: emptyList()

    val restingHr = assessRestingHeartRate(restingHrSeries, today = now)
    // Reihenfolge ist verbindlich: HRV- und Schlafampel kennen die
    // Ruhepuls-Ampel (Saettigungsfall bzw. rote Schlafstufe).
    val hrv = assessHrv(hrvSeries, today = now, restingHrFlag = restingHr.flag)
    val sleep = assessSleep(sleepSeries, today = now, restingHrFlag = restingHr.flag)

    val tsb = fitness.latest?.tsb
    val readiness = computeReadiness(
        restingHr = restingHr,
        sleep = sleep,
        hrv = hrv,
        tsb = tsb,
        trainingHistoryDays = fitness.historyDays,
    )
    val recommendation = recommendToday(readiness = readiness, tsb = tsb)

    val weeklyLoad = sumLastDays(fitness, 7)
    val coveredDays = min(fitness.historyDays, 28)
    val fourWeekMean = if (coveredDays > 0) sumLastDays(fitness, 28) * 7 / coveredDays else null

    // Readiness wird nicht persistiert, sondern aus den vorliegenden
    // Vitalserien rueckwirkend nachgerechnet — damit greift der Deload-Trigger
    // „Readiness < 40 an ≥ 3 von 7 Tagen" ohne zusaetzlichen Speicher.
    val readinessLast7 = availableReadinessScores(
        computeReadinessSeries(
            restingHrSeries = restingHrSeries,
            sleepSeries = sleepSeries,
            hrvSeries = hrvSeries,
            fitness = fitness,
            today = now,
        ),
    )

    val deload = assessDeload(
        fitness,
        readinessLast7 = readinessLast7,
        weeklyLoad = if (fitness.points.isEmpty()) null else weeklyLoad,
        fourWeekMeanWeeklyLoad = fourWeekMean,
    )

    val latest = fitness.latest
    val weeklyTarget = latest?.let {
        weeklyLoadTarget(
            ctl = it.ctl,
            targetRamp = DEFAULT_TARGET_RAMP_PER_WEEK,
            recentWeeklyMean = fourWeekMean,
            weeklyHours = effective.weeklyHours,
        )
    }

    return TrainingInsights(
        profile = effective,
        rideLoads = rideLoads,
        calibration = calibration,
        fitness = fitness,
        restingHr = restingHr,
        hrv = hrv,
        sleep = sleep,
        readiness = readiness,
        readinessLast7 = readinessLast7,
        recommendation = recommendation,
        deload = deload,
        weeklyTarget = weeklyTarget,
        vo2max = estimateVo2max(ordered, rideLoads, effective, vitals),
        weeklyLoad = weeklyLoad,
        fourWeekMeanWeeklyLoad = fourWeekMean,
    )
}

/**
 * Skaliert die Physiklast mit α. Bei geklemmter Kalibrierung ist α = 1,0 —
 * dann bleibt die Rohlast unveraendert.
 */
private fun calibrated(base: RideLoad, calibration: LoadCalibration): RideLoad {
    if (base.source != LoadSource.PHYSIK || calibration.alpha == 1.0) {
        return base
    }
    return RideLoad(
        load = min(calibration.alpha * base.physics.eTss, maxLoad),
        source = base.source,
        confidence = base.confidence,
        heartRate = base.heartRate,
        physics = base.physics,
        note = base.note,
    )
}

private fun sumLastDays(series: FitnessSeries, days: Int): Double =
    series.lastDays(days).sumOf { it.load }

/**
 * VO2max aus den juengsten Touren mit brauchbarer Leistungsreihe; die
 * Plattform (Samsung Health) gewinnt, falls sie einen Wert liefert.
 */
private fun estimateVo2max(
    ordered: List<Ride>,
    loads: Map<String, RideLoad>,
    profile: TrainingProfile,
    vitals: VitalsSummary?,
): Vo2MaxEstimate {
    val segments = mutableListOf<SteadySegment>()
    if (vitals?.vo2max == null) {
        // Hoechstens die 20 juengsten Touren betrachten, damit die Auswertung
        // auch bei langer Historie schnell bleibt.
        var scanned = 0
        for (ride in ordered.asReversed()) {
            if (scanned >= 20 || segments.size >= 12) {
                break
            }
            scanned++
            val physics = loads[ride.id]?.physics ?: continue
            if (!physics.available) {
                continue
            }
            segments.addAll(extractSteadySegments(physics.series, profile))
        }
    }
    return estimateVo2Max(
        profile = profile,
        segments = segments,
        platformValue = vitals?.vo2max,
    )
}

/**
 * Auswertung ohne jede Datengrundlage — Startwert der [AppViewModel]-Flows,
 * bis die erste echte Berechnung durch ist. Bewusst nicht `null`, damit die
 * Screens keinen Sonderfall brauchen.
 */
fun emptyTrainingInsights(
    profile: TrainingProfile = defaultTrainingProfile,
    now: LocalDateTime = LocalDateTime.now(),
): TrainingInsights = computeInsights(
    rides = emptyList(),
    vitals = null,
    profile = profile,
    now = now,
)

/** Ob eine Auswertung mangels Confidence gar nichts hergibt. */
val TrainingInsights.hasUsableLoad: Boolean
    get() = rideLoads.values.any { it.available && it.confidence != Confidence.NONE }
