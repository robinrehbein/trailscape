package de.trailscape.app.ui

import de.trailscape.core.Confidence
import de.trailscape.core.DailyRecommendation
import de.trailscape.core.DailyValue
import de.trailscape.core.DeloadRecommendation
import de.trailscape.core.EftpEstimate
import de.trailscape.core.EftpSource
import de.trailscape.core.FitnessPoint
import de.trailscape.core.FitnessSeries
import de.trailscape.core.HrvAssessment
import de.trailscape.core.LoadCalibration
import de.trailscape.core.LoadCalibrationSample
import de.trailscape.core.LoadEntry
import de.trailscape.core.LoadSource
import de.trailscape.core.PowerSeries
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
import de.trailscape.core.eftpWindowDays
import de.trailscape.core.estimateVo2Max
import de.trailscape.core.extractSteadySegments
import de.trailscape.core.maxLoad
import de.trailscape.core.median
import de.trailscape.core.recommendToday
import de.trailscape.core.resolveEftp
import de.trailscape.core.weeklyLoadTarget
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
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

/**
 * Fenster, das aus der **lokalen** Vitalhistorie in die Auswertung geht.
 *
 * Groesser als [VITALS_WINDOW_DAYS], weil die rueckwirkende Readiness-Reihe
 * (`computeReadinessSeries`, 7 Tage) fuer jeden dieser Tage wieder ein
 * 60-Tage-Baselinefenster braucht — 67 Tage waeren das Minimum, 120 lassen
 * Luft und kosten nichts, weil die Werte ohnehin auf der Platte liegen
 * (siehe [de.trailscape.core.VitalsHistory]).
 */
const val VITALS_HISTORY_WINDOW_DAYS: Int = 120

/** Ziel-Rampenrate (CTL-Punkte pro Woche) fuer das empfohlene Wochenziel. */
const val DEFAULT_TARGET_RAMP_PER_WEEK: Double = 4.0

/**
 * Halbe Breite des Fensters, aus dem der tourzeitnahe Ruhepuls kommt (Tage).
 *
 * Der Ruhepuls geht ueber die Herzfrequenzreserve in **jeden** TRIMP ein. Ein
 * globaler Median ueber die gesamte Vitalhistorie hiesse, die Tour von vor drei
 * Jahren mit dem Ruhepuls von heute zu rechnen — dabei ist genau dieser Wert
 * eine der Groessen, die sich mit dem Trainingszustand veraendern. Ein Fenster
 * von ±30 Tagen um den Tourtag ist lang genug, um die Tagesschwankung
 * herauszumitteln, und kurz genug, um einer Formveraenderung zu folgen.
 *
 * Dasselbe Prinzip fuehrt der Rechenkern schon fuer die Zonengrenzen mit
 * (`zonesUsed`, `TrainingLoad.kt`): Was zur Tour galt, bleibt bei der Tour.
 */
const val RESTING_HR_SNAPSHOT_HALF_WINDOW_DAYS: Long = 30

/** Mindestzahl an Messungen im Fenster, damit der Snapshot benutzt wird. */
const val RESTING_HR_SNAPSHOT_MIN_VALUES: Int = 5

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
    /**
     * Die FTP, an der die gesamte Lastskala haengt — samt Herkunft. Muss im UI
     * sichtbar sein: Sie bestimmt CTL, ATL, TSB, Rampenrate und Wochenziel
     * gleichermassen (`eTSS = h × IF² × 100`, `IF = NP / FTP`).
     */
    val eftp: EftpEstimate,
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

    /**
     * Ein Satz zur Lastskala, der immer stimmt — Herkunft der FTP plus, wo
     * noetig, der Hinweis, dass sich damit **die gesamte Historie** mitbewegt.
     *
     * Das ist der ehrliche Teil von K1: CTL, ATL, TSB und Wochenziel sind
     * relativ zu dieser einen Zahl. Aendert sie sich — weil ein FTP-Wert
     * eingetragen oder die Kalibrierung neu gerechnet wurde —, verschieben
     * sich alle historischen Werte mit. Wer das nicht weiss, haelt den Sprung
     * fuer einen Fehler.
     */
    val loadScaleNote: String
        get() {
            val perKg = eftp.perKg(profile.weightKg)
            val head = "Alle Lastwerte rechnen mit ${dartRoundInt(eftp.watts)} W Schwelle " +
                "(${germanOneDecimal(perKg)} W/kg), ${eftpSourceText(eftp.source)}."
            val tail = when (eftp.source) {
                EftpSource.EINGETRAGEN ->
                    " Änderst du den Wert im Profil, verschiebt sich die Skala — auch " +
                        "rückwirkend für alle bisherigen Touren."

                EftpSource.GESCHAETZT ->
                    " Das ist eine grobe Annahme (2,4 W/kg). Trage im Profil deine FTP " +
                        "ein oder fahre eine Tour mit Puls und Höhenprofil — beides macht " +
                        "die Skala belastbarer und verschiebt dann alle bisherigen Werte."

                EftpSource.ZWANZIG_MINUTEN ->
                    " Grundlage ist dein bester 20-Minuten-Abschnitt aus der " +
                        "GPS-Leistungsschätzung (±15–25 %). Eine eingetragene FTP wäre " +
                        "genauer."

                EftpSource.KALIBRIERT ->
                    " Grundlage ist der Abgleich mit deiner gemessenen Herzfrequenz. " +
                        "Der Wert kann sich mit neuen Touren verschieben — und mit ihm " +
                        "die Lastwerte der Vergangenheit."
            }
            return head + tail
        }
}

private fun dartRoundInt(value: Double): Int = kotlin.math.round(value).toInt()

private fun germanOneDecimal(value: Double): String =
    String.format(java.util.Locale.GERMANY, "%.1f", value)

private fun eftpSourceText(source: EftpSource): String = when (source) {
    EftpSource.EINGETRAGEN -> "von dir eingetragen"
    EftpSource.ZWANZIG_MINUTEN -> "geschätzt aus deinen Touren"
    EftpSource.KALIBRIERT -> "aus deinem Puls nachgeführt"
    EftpSource.GESCHAETZT -> "nur aus deinem Gewicht geschätzt"
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
 * Ruhepuls-Baseline zum Zeitpunkt [day] statt ueber die ganze Historie.
 *
 * Median der Messungen im Fenster ±[RESTING_HR_SNAPSHOT_HALF_WINDOW_DAYS] Tage
 * um den Tourtag; unter [RESTING_HR_SNAPSHOT_MIN_VALUES] Werten `null` — dann
 * bleibt es beim Wert des uebergebenen Profils (globaler Median bzw.
 * Profileintrag). Absichtlich zentriert und nicht nur nachlaufend: Fuer eine
 * Tour von gestern gibt es links vom Tag mehr Material als rechts, fuer eine
 * Tour von vor einem Jahr auf beiden Seiten gleich viel — beides soll zaehlen.
 */
internal fun restingHrNear(series: List<DailyValue>, day: LocalDateTime): Double? {
    if (series.isEmpty()) {
        return null
    }
    val center = day.toLocalDate()
    val from = center.minusDays(RESTING_HR_SNAPSHOT_HALF_WINDOW_DAYS)
    val to = center.plusDays(RESTING_HR_SNAPSHOT_HALF_WINDOW_DAYS)
    val window = series
        .filter { val d = it.day.toLocalDate(); !d.isBefore(from) && !d.isAfter(to) }
        .map { it.value }
    if (window.size < RESTING_HR_SNAPSHOT_MIN_VALUES) {
        return null
    }
    return median(window)
}

/**
 * Profil fuer **eine** Tour: wie [base], nur mit dem zur Tour zeitnahen
 * Ruhepuls. Ein eigener Profileintrag gewinnt weiterhin.
 */
private fun profileForRide(
    base: TrainingProfile,
    userOverride: Double?,
    restingSeries: List<DailyValue>,
    at: LocalDateTime,
): TrainingProfile {
    if (userOverride != null) {
        return base
    }
    // copyWith laesst `null` unveraendert — genau das gewuenschte Verhalten:
    // ohne Material bleibt der globale Median aus [effectiveProfile] stehen.
    return base.copyWith(restingHrOverride = restingHrNear(restingSeries, at))
}

/**
 * Cache-Schluessel einer Tourlast: Tour-Identitaet inklusive der Teile, die
 * sich nachtraeglich aendern koennen (HF-Merge ergaenzt Punkte und Ø-Puls),
 * plus das fuer diese Tour benutzte Profil und die benutzte FTP.
 */
private fun loadKey(ride: Ride, profileSignature: String, eftpW: Double): String =
    "${ride.id}|${ride.createdAt}|${ride.points.size}|" +
        "${ride.stats.avgHrBpm ?: "-"}|$profileSignature|${dartRoundInt(eftpW)}"

/**
 * Berechnet die komplette Trainingsauswertung.
 *
 * ## Zwei Durchgaenge — und warum
 * Die Lastskala haengt an der FTP (`eTSS = h × IF² × 100`), die FTP aber an
 * den Touren. Deshalb:
 *
 *  1. **Durchgang 1** rechnet mit dem reinen Profilwert
 *     ([TrainingProfile.eftpW]). Er liefert die Leistungsreihen (die von der
 *     FTP gar nicht abhaengen) und damit sowohl das beste 20-min-Mittel als
 *     auch α = `median(Last_HF / Last_Physik)`.
 *  2. **[resolveEftp]** waehlt daraus die FTP. Steckt α darin, ist der Wert
 *     der Fixpunkt `Profil-FTP / √α` — genau deshalb muss Durchgang 1 mit
 *     eben diesem Profilwert gerechnet haben.
 *  3. **Durchgang 2** rechnet die Lasten mit der gewaehlten FTP neu — und nur
 *     dann, wenn sie sich ueberhaupt geaendert hat.
 *
 * α wird danach **nicht mehr** als Faktor auf die Physiklast gelegt: Es steckt
 * bereits in der FTP, und zweimal korrigieren waere schlicht falsch. Der
 * Faktorweg bleibt nur fuer den Fall, dass die Nutzerin eine eigene FTP
 * eingetragen hat — deren Zahl fassen wir nicht an.
 *
 * @param baseLoadCache Cache der **unkalibrierten** Tourlasten. Wird in-place
 *   auf die aktuell gebrauchten Eintraege reduziert (wie das Dart-Original,
 *   das `_baseLoadCache` bei jedem Lauf leert und neu fuellt) — dadurch
 *   waechst er nicht mit geloeschten Touren mit. Weil der Schluessel die
 *   benutzte FTP enthaelt, stehen darin bis zu zwei Eintraege je Tour (einer
 *   je Durchgang); im eingeschwungenen Zustand ist damit beides ein Treffer.
 */
fun computeInsights(
    rides: List<Ride>,
    vitals: VitalsSummary?,
    profile: TrainingProfile,
    now: LocalDateTime = LocalDateTime.now(),
    baseLoadCache: MutableMap<String, RideLoad> = mutableMapOf(),
): TrainingInsights {
    val effective = effectiveProfile(profile, vitals)
    val restingHrSeries = vitals?.restingHeartRate?.series ?: emptyList()

    val ordered = rides.sortedBy { it.createdAt }

    // Profil je Tour: zeitnaher Ruhepuls statt eines Medians ueber die ganze
    // Historie (der Ruhepuls steckt ueber die HF-Reserve in jedem TRIMP).
    val rideProfiles = LinkedHashMap<String, TrainingProfile>()
    val rideSignatures = LinkedHashMap<String, String>()
    for (ride in ordered) {
        val rideProfile = profileForRide(
            base = effective,
            userOverride = profile.restingHrOverride,
            restingSeries = restingHrSeries,
            at = localOfEpochMs(ride.createdAt),
        )
        rideProfiles[ride.id] = rideProfile
        rideSignatures[ride.id] = rideProfile.toJson().toString()
    }

    val kept = LinkedHashMap<String, RideLoad>()
    fun loadFor(ride: Ride, eftpW: Double): RideLoad {
        val key = loadKey(ride, rideSignatures.getValue(ride.id), eftpW)
        val value = baseLoadCache[key]
            ?: computeRideLoadForRide(ride, rideProfiles.getValue(ride.id), eftpW = eftpW)
        kept[key] = value
        return value
    }

    // --- Durchgang 1: Profil-FTP. Liefert Leistungsreihen und α.
    val anchorEftpW = effective.eftpW
    val firstPass = ordered.map { loadFor(it, anchorEftpW) }

    val calibration = computeLoadCalibration(
        firstPass.mapNotNull { load ->
            if (load.heartRate.available &&
                load.heartRate.load > 0 &&
                load.physics.available &&
                load.physics.eTss > 0
            ) {
                LoadCalibrationSample(
                    loadHr = load.heartRate.load,
                    loadPhysics = load.physics.eTss,
                )
            } else {
                null
            }
        },
    )

    // --- FTP aufloesen. Fuer das 20-min-Mittel zaehlt nur das juengste Fenster.
    val windowStart = now.minusDays(eftpWindowDays.toLong())
    val recentSeries = mutableListOf<PowerSeries>()
    for ((index, ride) in ordered.withIndex()) {
        if (localOfEpochMs(ride.createdAt).isBefore(windowStart)) {
            continue
        }
        val physics = firstPass[index].physics
        if (physics.available) {
            recentSeries.add(physics.series)
        }
    }
    val eftp = resolveEftp(effective, recentSeries, calibration)

    // --- Durchgang 2 nur, wenn sich die Skala wirklich verschiebt.
    val secondPass = if (abs(eftp.watts - anchorEftpW) < 0.5) {
        firstPass
    } else {
        ordered.map { loadFor(it, eftp.watts) }
    }

    baseLoadCache.clear()
    baseLoadCache.putAll(kept)

    // α steckt schon in der FTP, wenn es dort angewandt wurde — dann darf es
    // nicht noch einmal als Faktor auf die Physiklast wirken.
    val appliedCalibration = if (eftp.alphaApplied != null) {
        calibration.copy(alpha = 1.0)
    } else {
        calibration
    }

    val rideLoads = LinkedHashMap<String, RideLoad>()
    for ((index, ride) in ordered.withIndex()) {
        rideLoads[ride.id] = calibrated(secondPass[index], appliedCalibration)
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
        eftp = eftp,
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
