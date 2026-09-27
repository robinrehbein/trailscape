package de.trailscape.core.i18n

import de.trailscape.core.Confidence
import de.trailscape.core.EftpSource
import de.trailscape.core.LoadRatioBand
import de.trailscape.core.LoadSource
import de.trailscape.core.RampBand
import de.trailscape.core.TsbBand

/**
 * Saetze und Labels des Lastmodells: `TrainingLoad.kt`,
 * `PerformanceManagement.kt`, `RideLoadFacts.kt` und `RideAnalysis.kt`.
 *
 * Die Zonenbezeichnungen der Zonenverteilungen („Z2 Grundlage", „LIT") sind
 * **keine** Anzeigetexte, sondern Schluessel im gespeicherten Datenmodell —
 * sie bleiben deshalb in `TrainingLoad.kt` und laufen nicht hierueber.
 */
interface LoadTexts {
    fun confidence(confidence: Confidence): String
    fun loadSource(source: LoadSource): String
    fun eftpSource(source: EftpSource): String
    fun tsbBand(band: TsbBand): String
    fun tsbBandMessage(band: TsbBand): String
    fun rampBand(band: RampBand): String
    fun loadRatioBand(band: LoadRatioBand): String

    // Herzfrequenz- und Physikpfad
    fun noTimedPoints(): String
    fun noMovingTime(): String
    fun noHeartRateForRide(): String
    fun heartRateCoverageTooLow(coveragePercent: Int, minPercent: Int): String
    fun rideFileNotLoadable(): String
    fun powerNoElevation(): String
    fun powerNoWeight(): String
    fun powerTooLittleMovingTime(): String
    fun powerTooShort(): String
    fun powerNotEstimable(): String
    fun powerText(avgPowerW: Int): String

    /** Kurzform der FTP mit Herkunft: „230 W (von dir eingetragen)". */
    fun eftpLabel(watts: Int, source: EftpSource): String

    // Kalibrierung
    fun calibrationTooFarApart(rawAlpha: Double): String
    fun calibrationFactor(sampleCount: Int, rawAlpha: Double): String

    // Herkunft der Tourlast
    fun noteFromHeartRate(coveragePercent: Int): String
    fun noteFromPower(): String
    fun noteFromRpe(): String
    fun noteHeuristic(): String
    fun noteNoData(): String

    // Wochenziel
    fun weeklyCapRecentWeeks(): String
    fun weeklyCapTimeBudget(weeklyHours: Double): String

    // Entkopplung
    fun decouplingRating(percent: Double): String
    fun decouplingNoPowerModel(): String
    fun decouplingTooShort(): String
    fun decouplingHeartRateCoverage(): String
    fun decouplingNoHeartRate(): String
    fun decouplingNotAerobic(): String
    fun decouplingTooUneven(): String
    fun decouplingNoHalves(): String
    fun decouplingElevationMismatch(): String
    fun decouplingMissingValues(): String

    // VO2max
    fun vo2MaxBand(lower: Int, upper: Int): String
    fun vo2MaxNotEstimable(): String
    fun vo2MaxNoRestingHr(): String
    fun vo2MaxNoWeight(): String
    fun vo2MaxTooFewSegments(have: Int, needed: Int): String
    fun vo2MaxHeartRateSpanTooSmall(): String
    fun vo2MaxRegressionUndetermined(): String
    fun vo2MaxRegressionFuzzy(r2: Double): String
    fun vo2MaxRegressionImplausible(): String
}

internal object LoadTextsDe : LoadTexts {
    private val lang = AppLanguage.DE

    override fun confidence(confidence: Confidence) = when (confidence) {
        Confidence.NONE -> "nicht berechenbar"
        Confidence.LOW -> "grobe Schätzung"
        Confidence.MEDIUM -> "Schätzung"
        Confidence.HIGH -> "belastbar"
    }

    override fun loadSource(source: LoadSource) = when (source) {
        LoadSource.HERZFREQUENZ -> "aus Herzfrequenz"
        LoadSource.PHYSIK -> "aus GPS-Leistungsschätzung"
        LoadSource.RPE -> "aus Anstrengungsempfinden"
        LoadSource.HEURISTIK -> "grob geschätzt aus Distanz und Höhenmetern"
        LoadSource.KEINE -> "nicht berechenbar"
    }

    override fun eftpSource(source: EftpSource) = when (source) {
        EftpSource.EINGETRAGEN -> "von dir eingetragen"
        EftpSource.ZWANZIG_MINUTEN -> "aus deinem besten 20-Minuten-Abschnitt geschätzt"
        EftpSource.KALIBRIERT -> "aus dem Vergleich mit deiner Herzfrequenz nachgeführt"
        EftpSource.GESCHAETZT -> "grob aus deinem Gewicht geschätzt"
    }

    override fun tsbBand(band: TsbBand) = when (band) {
        TsbBand.SEHR_FRISCH -> "Sehr ausgeruht"
        TsbBand.FORMSPITZE -> "Formspitze"
        TsbBand.NEUTRAL -> "Neutral"
        TsbBand.PRODUKTIV -> "Produktiver Bereich"
        TsbBand.UEBERLASTUNG -> "Sehr hohe Ermüdung"
    }

    override fun tsbBandMessage(band: TsbBand) = when (band) {
        TsbBand.SEHR_FRISCH ->
            "Sehr ausgeruht — typischerweise ein guter Zeitpunkt, wieder Reize zu setzen."
        TsbBand.FORMSPITZE ->
            "Dein Formwert liegt im Bereich, in dem viele Fahrer gute Leistungen zeigen."
        TsbBand.NEUTRAL -> "Form und Ermüdung halten sich ungefähr die Waage."
        TsbBand.PRODUKTIV ->
            "Erwünschte Ermüdung beim Aufbau — viele Fahrer trainieren in diesem Bereich."
        TsbBand.UEBERLASTUNG ->
            "Deine Ermüdung ist deutlich höher als deine Fitness. Eine Entlastungswoche ist " +
                "typischerweise sinnvoll."
    }

    override fun rampBand(band: RampBand) = when (band) {
        RampBand.FORMVERLUST -> "Formverlust / Entlastung"
        RampBand.ERHALTUNG -> "Erhaltung"
        RampBand.AUFBAU -> "Nachhaltiger Aufbau"
        RampBand.AGGRESSIV -> "Aggressiver Aufbau"
        RampBand.ZU_SCHNELL -> "Sehr schneller Aufbau"
    }

    override fun loadRatioBand(band: LoadRatioBand) = when (band) {
        LoadRatioBand.UNBEKANNT -> "noch keine Aussage möglich"
        LoadRatioBand.NIEDRIG -> "Belastung zuletzt niedriger als gewohnt"
        LoadRatioBand.IM_BAND -> "Belastung im gewohnten Rahmen"
        LoadRatioBand.BELASTUNGSSPRUNG -> "Belastungssprung"
    }

    override fun noTimedPoints() = "Keine auswertbaren Trackpunkte mit Zeitstempel."
    override fun noMovingTime() = "Keine Bewegungszeit erkannt."
    override fun noHeartRateForRide() = "Für diese Tour liegt keine Herzfrequenz vor."
    override fun heartRateCoverageTooLow(coveragePercent: Int, minPercent: Int) =
        "Herzfrequenz deckt nur $coveragePercent % der Bewegungszeit ab " +
            "(mindestens $minPercent % nötig)."
    override fun rideFileNotLoadable() = "Die Tour-Datei konnte nicht geladen werden."
    override fun powerNoElevation() = "Ohne Höhenprofil lässt sich die Leistung nicht schätzen."
    override fun powerNoWeight() = "Ohne Gewichtsangabe lässt sich die Leistung nicht schätzen."
    override fun powerTooLittleMovingTime() = "Zu wenig Bewegungszeit für eine Leistungsschätzung."
    override fun powerTooShort() = "Zu kurze Strecke für eine Leistungsschätzung."
    override fun powerNotEstimable() = "Leistung nicht schätzbar"
    override fun powerText(avgPowerW: Int) =
        "Geschätzte Leistung ≈ $avgPowerW W (aus GPS & Profil, ±15–25 %)"

    override fun eftpLabel(watts: Int, source: EftpSource) = "$watts W (${eftpSource(source)})"

    override fun calibrationTooFarApart(rawAlpha: Double) =
        "Deine Herzfrequenz und die Leistungsschätzung liegen um Faktor " +
            "${formatDecimal(rawAlpha, 2, lang)} auseinander — zu weit " +
            "für eine sinnvolle Korrektur. Prüfe Gewicht, Rad-/Gepäckgewicht und " +
            "ob deine Touren ein Höhenprofil haben."

    override fun calibrationFactor(sampleCount: Int, rawAlpha: Double) =
        "Aus $sampleCount Touren mit Puls und Höhenprofil ergibt sich ein " +
            "Korrekturfaktor von ${formatDecimal(rawAlpha, 2, lang)} zwischen " +
            "Herzfrequenz- und Leistungsschätzung."

    override fun noteFromHeartRate(coveragePercent: Int) =
        "Last aus der Herzfrequenz berechnet ($coveragePercent % Abdeckung)."
    override fun noteFromPower() = "Last aus der geschätzten Leistung berechnet (GPS & Profil, ±15–25 %)."
    override fun noteFromRpe() = "Last aus deinem Anstrengungsempfinden geschätzt."
    override fun noteHeuristic() =
        "Grobe Schätzung aus Distanz, Dauer und Höhenmetern — " +
            "ohne Herzfrequenz oder Höhenprofil nur eine Näherung."
    override fun noteNoData() = "Für diese Tour liegen zu wenige Daten für eine Lastberechnung vor."

    override fun weeklyCapRecentWeeks() = "Begrenzt auf 130 % deiner letzten vier Wochen."
    override fun weeklyCapTimeBudget(weeklyHours: Double) =
        "Begrenzt auf dein Zeitbudget von ${formatCompactDecimal(weeklyHours, lang)} h pro Woche."

    override fun decouplingRating(percent: Double) = when {
        percent < 5 -> "gute aerobe Ausdauer"
        percent <= 10 -> "aerobe Ausdauer im Aufbau"
        else -> "mehr Grundlagenarbeit sinnvoll"
    }

    override fun decouplingNoPowerModel() = "Kein Leistungsmodell verfügbar."
    override fun decouplingTooShort() = "Für die Entkopplung braucht es mindestens 60 Minuten Bewegungszeit."
    override fun decouplingHeartRateCoverage() =
        "Für die Entkopplung braucht es Herzfrequenz auf mindestens 90 % der Fahrt."
    override fun decouplingNoHeartRate() = "Keine Herzfrequenz vorhanden."
    override fun decouplingNotAerobic() =
        "Die Tour lag nicht im gleichmäßig-aeroben Bereich — die Entkopplung wäre nicht aussagekräftig."
    override fun decouplingTooUneven() = "Die Fahrt war zu ungleichmäßig für eine Entkopplungs-Analyse."
    override fun decouplingNoHalves() = "Die Tour lässt sich nicht in zwei vergleichbare Hälften teilen."
    override fun decouplingElevationMismatch() =
        "Die beiden Tourhälften unterscheiden sich zu stark im Höhenprofil."
    override fun decouplingMissingValues() = "Für eine der Tourhälften fehlen auswertbare Werte."

    override fun vo2MaxBand(lower: Int, upper: Int) = "VO2max geschätzt: $lower–$upper ml/kg/min"
    override fun vo2MaxNotEstimable() = "VO2max nicht schätzbar"
    override fun vo2MaxNoRestingHr() = "Ohne Ruhepuls nicht schätzbar."
    override fun vo2MaxNoWeight() = "Ohne Gewichtsangabe nicht schätzbar."
    override fun vo2MaxTooFewSegments(have: Int, needed: Int) =
        "Zu wenige gleichmäßige Abschnitte ($have von $needed)."
    override fun vo2MaxHeartRateSpanTooSmall() = "Die Herzfrequenz-Spanne der Abschnitte ist zu klein."
    override fun vo2MaxRegressionUndetermined() = "Regression nicht bestimmbar."
    override fun vo2MaxRegressionFuzzy(r2: Double) =
        "Der Zusammenhang zwischen Herzfrequenz und Leistung ist zu unscharf " +
            "(r² = ${formatDecimal(r2, 2, lang)})."
    override fun vo2MaxRegressionImplausible() = "Regression nicht plausibel."
}

internal object LoadTextsEn : LoadTexts {
    private val lang = AppLanguage.EN

    override fun confidence(confidence: Confidence) = when (confidence) {
        Confidence.NONE -> "not calculable"
        Confidence.LOW -> "rough estimate"
        Confidence.MEDIUM -> "estimate"
        Confidence.HIGH -> "reliable"
    }

    override fun loadSource(source: LoadSource) = when (source) {
        LoadSource.HERZFREQUENZ -> "from heart rate"
        LoadSource.PHYSIK -> "from GPS power estimate"
        LoadSource.RPE -> "from perceived exertion"
        LoadSource.HEURISTIK -> "roughly estimated from distance and elevation gain"
        LoadSource.KEINE -> "not calculable"
    }

    override fun eftpSource(source: EftpSource) = when (source) {
        EftpSource.EINGETRAGEN -> "entered by you"
        EftpSource.ZWANZIG_MINUTEN -> "estimated from your best 20-minute stretch"
        EftpSource.KALIBRIERT -> "adjusted by comparing with your heart rate"
        EftpSource.GESCHAETZT -> "roughly estimated from your weight"
    }

    override fun tsbBand(band: TsbBand) = when (band) {
        TsbBand.SEHR_FRISCH -> "Very rested"
        TsbBand.FORMSPITZE -> "Peak form"
        TsbBand.NEUTRAL -> "Neutral"
        TsbBand.PRODUKTIV -> "Productive range"
        TsbBand.UEBERLASTUNG -> "Very high fatigue"
    }

    override fun tsbBandMessage(band: TsbBand) = when (band) {
        TsbBand.SEHR_FRISCH ->
            "Very rested — typically a good time to start adding training stimulus again."
        TsbBand.FORMSPITZE ->
            "Your form is in the range where many riders perform well."
        TsbBand.NEUTRAL -> "Form and fatigue are roughly in balance."
        TsbBand.PRODUKTIV ->
            "Welcome fatigue while building — many riders train in this range."
        TsbBand.UEBERLASTUNG ->
            "Your fatigue is clearly higher than your fitness. A recovery week usually makes sense."
    }

    override fun rampBand(band: RampBand) = when (band) {
        RampBand.FORMVERLUST -> "Losing fitness / recovery"
        RampBand.ERHALTUNG -> "Maintaining"
        RampBand.AUFBAU -> "Sustainable build"
        RampBand.AGGRESSIV -> "Aggressive build"
        RampBand.ZU_SCHNELL -> "Very fast build"
    }

    override fun loadRatioBand(band: LoadRatioBand) = when (band) {
        LoadRatioBand.UNBEKANNT -> "no assessment possible yet"
        LoadRatioBand.NIEDRIG -> "load recently lower than usual"
        LoadRatioBand.IM_BAND -> "load within your usual range"
        LoadRatioBand.BELASTUNGSSPRUNG -> "load spike"
    }

    override fun noTimedPoints() = "No usable track points with timestamps."
    override fun noMovingTime() = "No moving time detected."
    override fun noHeartRateForRide() = "There’s no heart rate for this ride."
    override fun heartRateCoverageTooLow(coveragePercent: Int, minPercent: Int) =
        "Heart rate covers only $coveragePercent% of the moving time " +
            "(at least $minPercent% needed)."
    override fun rideFileNotLoadable() = "The ride file couldn’t be loaded."
    override fun powerNoElevation() = "Power can’t be estimated without an elevation profile."
    override fun powerNoWeight() = "Power can’t be estimated without your weight."
    override fun powerTooLittleMovingTime() = "Too little moving time for a power estimate."
    override fun powerTooShort() = "Too short a distance for a power estimate."
    override fun powerNotEstimable() = "Power can’t be estimated"
    override fun powerText(avgPowerW: Int) =
        "Estimated power ≈ $avgPowerW W (from GPS & profile, ±15–25%)"

    override fun eftpLabel(watts: Int, source: EftpSource) = "$watts W (${eftpSource(source)})"

    override fun calibrationTooFarApart(rawAlpha: Double) =
        "Your heart rate and the power estimate differ by a factor of " +
            "${formatDecimal(rawAlpha, 2, lang)} — too far apart for a sensible " +
            "correction. Check your weight, bike and luggage weight, and whether " +
            "your rides have an elevation profile."

    override fun calibrationFactor(sampleCount: Int, rawAlpha: Double) =
        "$sampleCount rides with heart rate and elevation profile give a correction " +
            "factor of ${formatDecimal(rawAlpha, 2, lang)} between the heart rate " +
            "and power estimates."

    override fun noteFromHeartRate(coveragePercent: Int) =
        "Load calculated from heart rate ($coveragePercent% coverage)."
    override fun noteFromPower() = "Load calculated from estimated power (GPS & profile, ±15–25%)."
    override fun noteFromRpe() = "Load estimated from your perceived exertion."
    override fun noteHeuristic() =
        "Rough estimate from distance, duration and elevation gain — " +
            "only an approximation without heart rate or an elevation profile."
    override fun noteNoData() = "There’s too little data to calculate a load for this ride."

    override fun weeklyCapRecentWeeks() = "Capped at 130% of your last four weeks."
    override fun weeklyCapTimeBudget(weeklyHours: Double) =
        "Capped at your time budget of ${formatCompactDecimal(weeklyHours, lang)} h per week."

    override fun decouplingRating(percent: Double) = when {
        percent < 5 -> "good aerobic endurance"
        percent <= 10 -> "aerobic endurance still building"
        else -> "more endurance work would help"
    }

    override fun decouplingNoPowerModel() = "No power model available."
    override fun decouplingTooShort() = "Decoupling needs at least 60 minutes of moving time."
    override fun decouplingHeartRateCoverage() =
        "Decoupling needs heart rate for at least 90% of the ride."
    override fun decouplingNoHeartRate() = "No heart rate available."
    override fun decouplingNotAerobic() =
        "The ride wasn’t in the steady aerobic range — decoupling wouldn’t be meaningful."
    override fun decouplingTooUneven() = "The ride was too uneven for a decoupling analysis."
    override fun decouplingNoHalves() = "The ride can’t be split into two comparable halves."
    override fun decouplingElevationMismatch() =
        "The two halves of the ride differ too much in elevation."
    override fun decouplingMissingValues() = "Usable values are missing for one half of the ride."

    override fun vo2MaxBand(lower: Int, upper: Int) = "Estimated VO2max: $lower–$upper ml/kg/min"
    override fun vo2MaxNotEstimable() = "VO2max can’t be estimated"
    override fun vo2MaxNoRestingHr() = "Can’t be estimated without a resting heart rate."
    override fun vo2MaxNoWeight() = "Can’t be estimated without your weight."
    override fun vo2MaxTooFewSegments(have: Int, needed: Int) =
        "Too few steady stretches ($have of $needed)."
    override fun vo2MaxHeartRateSpanTooSmall() = "The heart rate range of the stretches is too small."
    override fun vo2MaxRegressionUndetermined() = "Regression can’t be determined."
    override fun vo2MaxRegressionFuzzy(r2: Double) =
        "The relationship between heart rate and power is too loose " +
            "(r² = ${formatDecimal(r2, 2, lang)})."
    override fun vo2MaxRegressionImplausible() = "Regression isn’t plausible."
}
