package de.trailscape.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min

/**
 * Kompaktes, **punktfreies** Destillat einer Tour fuer die Trainingsauswertung.
 *
 * ## Das Problem
 * Die Tourlast ([computeRideLoadForRide]) braucht die kompletten GPS-Punkte —
 * sie baut daraus HF- und Leistungsreihe. Solange die Auswertung bei jedem
 * Lauf alle Touren mit allen Punkten sah, mussten die deshalb dauerhaft im
 * RAM liegen. Dieses Destillat haelt stattdessen genau die wenigen Zahlen
 * fest, die die Auswertung aus den Punkten zieht — einmal gerechnet, danach
 * kommt jeder weitere Lauf ohne Punktzugriff aus.
 *
 * ## Was drinsteckt — und warum genau das reicht
 *  * **HF-Pfad** (Stufe A der Kaskade, siehe [computeRideLoad]): Die
 *    normalisierte HF-Last haengt nur vom Profil ab, nicht von der FTP — sie
 *    steht fertig gerechnet hier.
 *  * **Physik-Pfad** (Stufe B): `eTSS = h × (NP/FTP)² × 100` — NP und
 *    Bewegungszeit sind FTP-unabhaengig. Beide stehen hier, die Last laesst
 *    sich damit fuer **jede** FTP nachrechnen ([rideLoadFromFacts]) — deshalb
 *    genuegt EIN Eintrag je Tour, obwohl die Auswertung zwei Durchgaenge mit
 *    verschiedenen FTP-Werten faehrt.
 *  * **Bestes 20-min-Mittel** fuer die FTP-Aufloesung ([resolveEftp]) und die
 *    **Steady-Segmente** fuer die VO2max-Regression ([estimateVo2Max]) — die
 *    beiden einzigen weiteren Stellen, an denen die Auswertung in die
 *    Leistungsreihe schaut.
 *  * **Fallback-Kennzahlen** (Stufe D): Distanz/Dauer/Hoehenmeter, bereits so
 *    aufgeloest, wie [computeRideLoad] sie aus Stats und Reihe ziehen wuerde.
 *
 * HF-Last, Leistungsreihe und Segmente haengen am **Profil** (Ruhepuls,
 * Gewicht, cw-Wert, ...) — ein Eintrag gilt deshalb nur fuer die
 * Profil-Signatur, mit der er gerechnet wurde (siehe [StoredRideLoadFacts]).
 */
data class RideLoadFacts(
    // ------------------------------------------------------------- HF-Pfad
    val hrAvailable: Boolean,
    /** Normalisierte HF-Last („hrTSS"), fertig gerechnet — FTP-unabhaengig. */
    val hrLoad: Double,
    val hrCoverage: Double,
    /** Zeit ueber der Schwelle in s — fuettert das HIT-Budget. */
    val hrSecondsAboveLthr: Double,
    val hrMovingTimeS: Double,
    val hrAvgHr: Double?,
    val hrMaxHr: Int?,
    val hrConfidence: Confidence,
    val hrUnavailableReason: String?,
    // ---------------------------------------------------------- Physik-Pfad
    val physicsAvailable: Boolean,
    /** Normalized Power in W — die FTP steckt NICHT drin. */
    val physicsNpW: Double,
    val physicsAvgPowerW: Double,
    val physicsMovingTimeS: Double,
    val physicsConfidence: Confidence,
    // ------------------------------------------- FTP-Aufloesung und VO2max
    /** Bestes nachlaufendes 20-min-Leistungsmittel, `null` ohne volle 20 min. */
    val bestTwentyMinW: Double?,
    /** Stabile Submaximal-Segmente fuer die VO2max-Regression (§7.3 B). */
    val steadySegments: List<SteadySegment>,
    // ----------------------------------------------- Stufe D (Heuristik)
    val fallbackDistanceKm: Double,
    val fallbackDurationS: Double,
    val fallbackAscentM: Double,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("hrAvailable", hrAvailable)
        put("hrLoad", hrLoad)
        put("hrCoverage", hrCoverage)
        put("hrSecondsAboveLthr", hrSecondsAboveLthr)
        put("hrMovingTimeS", hrMovingTimeS)
        hrAvgHr?.let { put("hrAvgHr", it) }
        hrMaxHr?.let { put("hrMaxHr", it) }
        put("hrConfidence", hrConfidence.jsonName)
        hrUnavailableReason?.let { put("hrUnavailableReason", it) }
        put("physicsAvailable", physicsAvailable)
        put("physicsNpW", physicsNpW)
        put("physicsAvgPowerW", physicsAvgPowerW)
        put("physicsMovingTimeS", physicsMovingTimeS)
        put("physicsConfidence", physicsConfidence.jsonName)
        bestTwentyMinW?.let { put("bestTwentyMinW", it) }
        put(
            "steadySegments",
            buildJsonArray {
                steadySegments.forEach { segment ->
                    add(
                        buildJsonObject {
                            put("avgPowerW", segment.avgPowerW)
                            put("avgHr", segment.avgHr)
                            put("durationS", segment.durationS)
                        },
                    )
                }
            },
        )
        put("fallbackDistanceKm", fallbackDistanceKm)
        put("fallbackDurationS", fallbackDurationS)
        put("fallbackAscentM", fallbackAscentM)
    }

    companion object {
        private fun confidenceOf(name: String?): Confidence =
            Confidence.entries.firstOrNull { it.jsonName == name } ?: Confidence.NONE

        fun fromJson(json: JsonObject): RideLoadFacts = RideLoadFacts(
            hrAvailable = json.optionalBoolean("hrAvailable") ?: false,
            hrLoad = json.optionalDouble("hrLoad") ?: 0.0,
            hrCoverage = json.optionalDouble("hrCoverage") ?: 0.0,
            hrSecondsAboveLthr = json.optionalDouble("hrSecondsAboveLthr") ?: 0.0,
            hrMovingTimeS = json.optionalDouble("hrMovingTimeS") ?: 0.0,
            hrAvgHr = json.optionalDouble("hrAvgHr"),
            hrMaxHr = json.optionalInt("hrMaxHr"),
            hrConfidence = confidenceOf(json.optionalString("hrConfidence")),
            hrUnavailableReason = json.optionalString("hrUnavailableReason"),
            physicsAvailable = json.optionalBoolean("physicsAvailable") ?: false,
            physicsNpW = json.optionalDouble("physicsNpW") ?: 0.0,
            physicsAvgPowerW = json.optionalDouble("physicsAvgPowerW") ?: 0.0,
            physicsMovingTimeS = json.optionalDouble("physicsMovingTimeS") ?: 0.0,
            physicsConfidence = confidenceOf(json.optionalString("physicsConfidence")),
            bestTwentyMinW = json.optionalDouble("bestTwentyMinW"),
            steadySegments = (json.fieldOrNull("steadySegments") as? JsonArray)
                ?.mapNotNull { entry ->
                    (entry as? JsonObject)?.let { obj ->
                        SteadySegment(
                            avgPowerW = obj.optionalDouble("avgPowerW") ?: return@let null,
                            avgHr = obj.optionalDouble("avgHr") ?: return@let null,
                            durationS = obj.optionalDouble("durationS") ?: return@let null,
                        )
                    }
                }
                ?: emptyList(),
            fallbackDistanceKm = json.optionalDouble("fallbackDistanceKm") ?: 0.0,
            fallbackDurationS = json.optionalDouble("fallbackDurationS") ?: 0.0,
            fallbackAscentM = json.optionalDouble("fallbackAscentM") ?: 0.0,
        )
    }
}

/**
 * Destilliert eine volle Tour zu [RideLoadFacts] — der EINE Punktdurchlauf,
 * den die Auswertung je (Tour, Profil) noch braucht.
 *
 * Rechnet exakt die Bausteine, die auch [computeRideLoad] rechnet
 * ([buildRideSeries] → [computeHeartRateLoad]/[computePhysicsEstimate]),
 * plus Bestwert und Steady-Segmente aus der Leistungsreihe. Die
 * Fallback-Kennzahlen werden hier bereits so aufgeloest, wie Stufe D der
 * Kaskade sie ziehen wuerde (Stats vor Reihe) — die Rekonstruktion in
 * [rideLoadFromFacts] muss dann nicht mehr unterscheiden.
 */
fun computeRideLoadFacts(ride: Ride, profile: TrainingProfile): RideLoadFacts {
    val series = buildRideSeries(ride.points, profile)
    val hr = computeHeartRateLoad(series, profile)
    val physics = computePhysicsEstimate(series, profile)

    val stats = ride.stats
    // Wie Stufe D in [computeRideLoad] mit uebergebenen Stats: Die Distanz
    // kommt IMMER aus den Stats (auch 0,0 faellt nicht auf die Reihe zurueck),
    // nur die Dauer kaskadiert ueber ihre Nullable-Felder in die Reihe.
    val fallbackDistanceKm = stats.distanceKm
    val fallbackDurationS = stats.movingTimeS?.toDouble()
        ?: stats.durationS?.toDouble()
        ?: (if (series.movingTimeS > 0) series.movingTimeS else series.totalTimeS)

    return RideLoadFacts(
        hrAvailable = hr.available,
        hrLoad = hr.load,
        hrCoverage = hr.hrCoverage,
        hrSecondsAboveLthr = hr.secondsAboveLthr,
        hrMovingTimeS = hr.movingTimeS,
        hrAvgHr = hr.avgHr,
        hrMaxHr = hr.maxHr,
        hrConfidence = hr.confidence,
        hrUnavailableReason = hr.unavailableReason,
        physicsAvailable = physics.available,
        physicsNpW = physics.normalizedPowerW,
        physicsAvgPowerW = physics.avgPowerW,
        physicsMovingTimeS = physics.movingTimeS,
        physicsConfidence = physics.confidence,
        bestTwentyMinW = if (physics.available) bestRollingMeanPowerW(physics.series) else null,
        steadySegments = if (physics.available) {
            extractSteadySegments(physics.series, profile)
        } else {
            emptyList()
        },
        fallbackDistanceKm = fallbackDistanceKm,
        fallbackDurationS = fallbackDurationS,
        fallbackAscentM = stats.ascentM,
    )
}

/**
 * Notnagel-Destillat aus der blossen Zusammenfassung — fuer Touren, deren
 * Datei sich nicht (mehr) laden laesst (geloescht, in Quarantaene).
 *
 * HF- und Physikpfad sind ohne Punkte nicht rekonstruierbar; die Kaskade
 * faellt damit auf Stufe D (Distanz/Dauer/Hoehenmeter aus den Stats) zurueck
 * — dieselbe Groessenordnung, die auch die Flutter-App fuer Touren ohne
 * auswertbare Punkte ansetzte. Besser eine grobe Last als eine lautlos aus
 * der Fitnesskurve verschwundene Tour.
 */
fun rideLoadFactsFromSummary(summary: RideInfo): RideLoadFacts = RideLoadFacts(
    hrAvailable = false,
    hrLoad = 0.0,
    hrCoverage = 0.0,
    hrSecondsAboveLthr = 0.0,
    hrMovingTimeS = 0.0,
    hrAvgHr = null,
    hrMaxHr = null,
    hrConfidence = Confidence.NONE,
    hrUnavailableReason = "Die Tour-Datei konnte nicht geladen werden.",
    physicsAvailable = false,
    physicsNpW = 0.0,
    physicsAvgPowerW = 0.0,
    physicsMovingTimeS = 0.0,
    physicsConfidence = Confidence.NONE,
    bestTwentyMinW = null,
    steadySegments = emptyList(),
    fallbackDistanceKm = summary.stats.distanceKm,
    fallbackDurationS = summary.stats.movingTimeS?.toDouble()
        ?: summary.stats.durationS?.toDouble()
        ?: 0.0,
    fallbackAscentM = summary.stats.ascentM,
)

/**
 * Rekonstruiert die [RideLoad] einer Tour aus ihrem Destillat — dieselbe
 * Fallback-Kaskade A → B → D wie [computeRideLoad] (Stufe C/RPE entfaellt,
 * die Auswertung uebergibt nie ein RPE), nur ohne Punktzugriff.
 *
 * Die eingebetteten [HeartRateLoad]/[PhysicsEstimate] tragen die im Destillat
 * festgehaltenen Kennzahlen; **Reihen und Zonenverteilungen sind bewusst
 * leer** (bis auf die Schwellenzeit, die als Lucia-HIT-Sekunden eingesetzt
 * wird, damit [HeartRateLoad.secondsAboveLthr] weiter stimmt). Wer die volle
 * Leistungsreihe braucht (Tourdetail: Entkopplung, Kurven), rechnet sie aus
 * der on demand geladenen Volltour — nicht aus diesem Objekt.
 *
 * @param eftpW FTP fuer den Physikpfad; `null` = Profilwert. `eTSS ∝ 1/FTP²`
 *   wird hier exakt wie in [computePhysicsEstimate] gerechnet — Ergebnis und
 *   Original sind fuer dieselben Eingaben bitgleich.
 */
fun rideLoadFromFacts(
    facts: RideLoadFacts,
    profile: TrainingProfile,
    eftpW: Double? = null,
    calibration: LoadCalibration = LoadCalibration.NEUTRAL,
): RideLoad {
    val zones = profile.zones

    val hr = HeartRateLoad(
        available = facts.hrAvailable,
        unavailableReason = facts.hrUnavailableReason,
        trimpBanister = 0.0,
        trimpEdwards = 0.0,
        load = facts.hrLoad,
        hrCoverage = facts.hrCoverage,
        movingTimeS = facts.hrMovingTimeS,
        avgHr = facts.hrAvgHr,
        maxHr = facts.hrMaxHr,
        frielZones = ZoneDistribution.empty(frielZoneLabels),
        luciaZones = ZoneDistribution(
            labels = luciaZoneLabels,
            seconds = listOf(0.0, 0.0, facts.hrSecondsAboveLthr),
        ),
        edwardsZones = ZoneDistribution.empty(edwardsZoneLabels),
        zonesUsed = zones,
        confidence = facts.hrConfidence,
    )

    val physics = if (facts.physicsAvailable) {
        // Exakt die Formeln aus [computePhysicsEstimate], nur ueber die
        // festgehaltenen NP/Zeit statt der Reihe.
        val avg = facts.physicsAvgPowerW
        val np = facts.physicsNpW
        val ftp = clamp(eftpW ?: profile.eftpW, minEftpW, maxEftpW)
        val ifValue = if (ftp > 0) np / ftp else 0.0
        val hours = facts.physicsMovingTimeS / 3600
        PhysicsEstimate(
            available = true,
            unavailableReason = null,
            series = PowerSeries.EMPTY,
            movingTimeS = facts.physicsMovingTimeS,
            avgPowerW = avg,
            normalizedPowerW = np,
            variabilityIndex = if (avg > 0) np / avg else 0.0,
            intensityFactor = ifValue,
            eTss = min(hours * ifValue * ifValue * 100, maxLoad),
            eftpW = ftp,
            kcal = estimateKcal(avgPowerW = avg, movingTimeS = facts.physicsMovingTimeS),
            confidence = facts.physicsConfidence,
        )
    } else {
        PhysicsEstimate.unavailable("Leistung nicht schätzbar.")
    }

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

    // Stufe D — reine Distanz/Hoehen-Heuristik (Stufe C/RPE entfaellt).
    if (facts.fallbackDistanceKm > 0 && facts.fallbackDurationS > 0) {
        return RideLoad(
            load = heuristicLoad(
                distanceKm = facts.fallbackDistanceKm,
                durationH = facts.fallbackDurationS / 3600,
                ascentM = facts.fallbackAscentM,
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

/**
 * Ein Cache-Eintrag: Destillat plus die Werte, die seine Gueltigkeit
 * bestimmen.
 *
 * Ein Eintrag gilt, solange `updatedAt` (Tour unveraendert — Umbenennen zaehlt
 * mit, aendert die Punkte aber nicht; ein HF-Merge setzt `updatedAt` neu und
 * invalidiert damit korrekt) und `profileSignature` (das fuer diese Tour
 * benutzte Profil, inklusive tourzeitnahem Ruhepuls) uebereinstimmen. Die
 * FTP gehoert bewusst NICHT dazu — das Destillat ist FTP-unabhaengig, siehe
 * [RideLoadFacts].
 */
data class StoredRideLoadFacts(
    val updatedAt: Long,
    val profileSignature: String,
    val facts: RideLoadFacts,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("updatedAt", updatedAt)
        put("profileSignature", profileSignature)
        put("facts", facts.toJson())
    }

    companion object {
        fun fromJson(json: JsonObject): StoredRideLoadFacts? {
            val facts = json.fieldOrNull("facts") as? JsonObject ?: return null
            return StoredRideLoadFacts(
                updatedAt = json.optionalLong("updatedAt") ?: return null,
                profileSignature = json.optionalString("profileSignature") ?: return null,
                facts = RideLoadFacts.fromJson(facts),
            )
        }
    }
}

/**
 * Speicher fuer die Destillate, je Tour-ID eines.
 *
 * `:app` haengt hier eine Datei an (`rides/last-cache.json`, siehe
 * `RideLoadCacheStore` in `:app`); Tests und Aufrufer ohne Persistenz nehmen
 * [InMemoryRideLoadFactsStore]. [flush] wird von der Auswertung genau einmal
 * am Ende eines Laufs gerufen — eine Datei-Implementierung schreibt dann
 * gesammelt statt je Eintrag.
 */
interface RideLoadFactsStore {
    fun get(id: String): StoredRideLoadFacts?
    fun put(id: String, entry: StoredRideLoadFacts)

    /** Wirft alle Eintraege weg, deren ID nicht in [ids] liegt (geloeschte Touren). */
    fun retainAll(ids: Set<String>)

    /** Persistiert den Stand, falls die Implementierung persistiert. */
    fun flush()
}

/** [RideLoadFactsStore] ohne Persistenz — Default fuer Tests. */
class InMemoryRideLoadFactsStore : RideLoadFactsStore {
    private val entries = mutableMapOf<String, StoredRideLoadFacts>()

    val size: Int get() = entries.size

    override fun get(id: String): StoredRideLoadFacts? = entries[id]

    override fun put(id: String, entry: StoredRideLoadFacts) {
        entries[id] = entry
    }

    override fun retainAll(ids: Set<String>) {
        entries.keys.retainAll(ids)
    }

    override fun flush() = Unit
}
