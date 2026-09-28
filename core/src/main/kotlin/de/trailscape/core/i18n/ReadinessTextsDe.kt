package de.trailscape.core.i18n

import de.trailscape.core.DailyRecommendationKind
import de.trailscape.core.HrvStatus
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag

internal object ReadinessTextsDe : ReadinessTexts {
    private val lang = AppLanguage.DE
    private fun d1(value: Double) = formatDecimal(value, 1, lang)

    override fun recoveryFlag(flag: RecoveryFlag) = when (flag) {
        RecoveryFlag.UNBEKANNT -> "keine Aussage"
        RecoveryFlag.GRUEN -> "unauffällig"
        RecoveryFlag.GELB -> "leicht erhöht"
        RecoveryFlag.ORANGE -> "deutlich auffällig"
        RecoveryFlag.ROT -> "stark auffällig"
    }

    override fun hrvStatus(status: HrvStatus) = when (status) {
        HrvStatus.UNBEKANNT -> "keine Aussage"
        HrvStatus.NIEDRIG -> "unter deinem Normalband"
        HrvStatus.IM_BAND -> "im Normalband"
        HrvStatus.UEBER_BAND -> "über deinem Normalband"
        HrvStatus.SAETTIGUNG -> "über dem Band bei erhöhtem Ruhepuls"
    }

    override fun readinessBand(band: ReadinessBand) = when (band) {
        ReadinessBand.HART -> "bereit für eine harte Einheit"
        ReadinessBand.NORMAL -> "normales Training"
        ReadinessBand.LOCKER -> "locker (Grundlagentempo)"
        ReadinessBand.RUHE -> "Ruhe oder sehr locker"
    }

    override fun noStatement() = "Keine Aussage möglich."

    override fun restingHrNoValues() = "Noch keine Ruhepuls-Werte vorhanden."
    override fun restingHrBaselineBuilding(days: Int, needed: Int) =
        "Ruhepuls-Baseline wird aufgebaut ($days von $needed Tagen)."
    override fun restingHrNoRecent() = "Kein aktueller Ruhepuls-Wert (letzte 3 Tage)."

    override fun restingHrGreen(deltaBpm: Double) =
        "Dein Ruhepuls liegt im gewohnten Bereich (${signedOneDecimal(deltaBpm, lang)} bpm gegenüber " +
            "deinem Normalwert)."

    override fun restingHrYellow(deltaBpm: Double, afterHardDay: Boolean): String {
        val rounded = absOneDecimal(deltaBpm, lang)
        return if (afterHardDay) {
            "Dein Ruhepuls liegt +$rounded bpm über deinem Normalwert — " +
                "nach der gestrigen Belastung erwartbar."
        } else {
            "Dein Ruhepuls liegt seit mindestens zwei Messungen +$rounded bpm " +
                "über deinem Normalwert. Das kann an Training, Schlaf, Stress, " +
                "Alkohol, Hitze oder einem beginnenden Infekt liegen."
        }
    }

    override fun restingHrOrange(deltaBpm: Double) =
        "Dein Ruhepuls liegt deutlich über deinem Normalwert (+${absOneDecimal(deltaBpm, lang)} bpm). " +
            "Das kann an Training, Schlaf, Stress, Alkohol, Hitze oder einem " +
            "Infekt liegen."

    override fun restingHrRed(deltaBpm: Double) =
        "Dein Ruhepuls liegt seit mehreren Tagen klar über deinem Normalwert " +
            "(+${absOneDecimal(deltaBpm, lang)} bpm) — das kann an Training, Schlaf, Stress oder einem " +
            "Infekt liegen."

    override fun hrvNoValues() = "Noch keine HRV-Werte vorhanden."
    override fun hrvNeedsDays(missing: Int, have: Int, needed: Int) =
        "Braucht noch $missing ${plural(missing, "Tag", "Tage")} HRV-Daten " +
            "($have von $needed im Vergleichszeitraum)."
    override fun hrvTooFewRecent(have: Int, needed: Int) =
        "Zu wenige HRV-Messungen in den letzten sieben Tagen ($have von $needed)."

    override fun hrvLow(currentMs: Int, lowMs: Int, highMs: Int, clearly: Boolean) = if (!clearly) {
        "Deine HRV liegt im 7-Tage-Mittel mit $currentMs ms knapp unter deinem " +
            "Normalband ($lowMs–$highMs ms). Das kann an Training, Schlaf, Stress, " +
            "Alkohol oder einem beginnenden Infekt liegen."
    } else {
        "Deine HRV liegt im 7-Tage-Mittel mit $currentMs ms deutlich unter deinem " +
            "Normalband ($lowMs–$highMs ms). Das kann an Training, Schlaf, Stress, " +
            "Alkohol oder einem Infekt liegen."
    }

    override fun hrvInBand(currentMs: Int, lowMs: Int, highMs: Int) =
        "Deine HRV liegt im 7-Tage-Mittel mit $currentMs ms in deinem Normalband " +
            "($lowMs–$highMs ms)."

    override fun hrvAboveBand(currentMs: Int, lowMs: Int, highMs: Int) =
        "Deine HRV liegt im 7-Tage-Mittel mit $currentMs ms über deinem Normalband " +
            "($lowMs–$highMs ms) — dein Nervensystem wirkt gut erholt."

    override fun hrvSaturation(currentMs: Int, lowMs: Int, highMs: Int) =
        "Deine HRV liegt im 7-Tage-Mittel mit $currentMs ms über deinem Normalband " +
            "($lowMs–$highMs ms), gleichzeitig ist dein Ruhepuls erhöht. Diese " +
            "Kombination kommt auch bei starker Ermüdung vor — beobachte die " +
            "nächsten Tage, bevor du hart trainierst."

    override fun sleepNoData() = "Noch keine Schlafdaten vorhanden."
    override fun sleepBaselineBuilding(nights: Int, needed: Int) =
        "Schlaf-Baseline wird aufgebaut ($nights von $needed Nächten)."
    override fun sleepNoRecent() = "Keine aktuelle Schlafmessung vorhanden."
    override fun sleepGreen(baselineH: Double) =
        "Dein Schlaf entspricht deinem Normalwert (${d1(baselineH)} h)."
    override fun sleepYellow(deficitH: Double) =
        "Du hast ${absOneDecimal(deficitH, lang)} h weniger geschlafen als sonst."
    override fun sleepOrange(deficitH: Double, debt7dH: Double) =
        "Dein Schlaf liegt deutlich unter deinem Normalwert " +
            "(−${absOneDecimal(deficitH, lang)} h; 7-Tage-Defizit ${d1(debt7dH)} h)."
    override fun sleepRed(deficitH: Double) =
        "Deutlich zu wenig Schlaf (−${absOneDecimal(deficitH, lang)} h) bei gleichzeitig erhöhtem " +
            "Ruhepuls."

    override fun shortSleeperHint() =
        "Dein üblicher Schlaf liegt seit Wochen unter 6,5 Stunden. Für Erwachsene " +
            "werden 7–9 Stunden empfohlen, bei viel Training eher mehr — mehr Schlaf " +
            "verbessert Regeneration und Leistung. Deine Tagesempfehlung ändert das " +
            "nicht."

    override fun signalName(signal: ReadinessSignal) = when (signal) {
        ReadinessSignal.RESTING_HR -> "Ruhepuls"
        ReadinessSignal.SLEEP -> "Schlaf"
        ReadinessSignal.TRAINING_HISTORY -> "Trainingshistorie"
    }

    override fun readinessUnavailable(missing: List<ReadinessSignal>) =
        "Noch nicht genug Daten für einen Gesamtwert " +
            "(${missing.joinToString(", ") { signalName(it) }}). Die einzelnen Signale siehst du trotzdem."

    override fun readinessHeadline(score: Int, band: ReadinessBand) =
        "Erholung: $score — ${readinessBand(band)}"

    override fun readinessHeadlineUnavailable() = "Erholung noch nicht berechenbar"

    override fun readinessDetail(usesHrv: Boolean) = if (usesHrv) {
        "Basierend auf HRV, Ruhepuls, Schlaf und Trainingslast — " +
            "ein Trendindikator, keine Messung."
    } else {
        "Basierend auf Ruhepuls, Schlaf und Trainingslast (ohne HRV) — " +
            "ein Trendindikator, keine Messung. Ohne HRV fehlt das " +
            "direkteste Signal; der Wert ist deshalb unsicherer."
    }

    override fun readinessDetailUnavailable() =
        "Sobald genug Tage vorliegen, fassen wir Ruhepuls, Schlaf und " +
            "Trainingslast zu einem Wert zusammen."

    override fun recommendationTitle(kind: DailyRecommendationKind) = when (kind) {
        DailyRecommendationKind.RUHETAG -> "Heute besser Ruhetag"
        DailyRecommendationKind.LOCKER_Z2 -> "Locker im Grundlagentempo (Zone 2), 60–90 min"
        DailyRecommendationKind.RECOVERY -> "Regenerationsfahrt, ganz ruhig (Zone 1–2)"
        DailyRecommendationKind.HARTE_EINHEIT -> "Harte Einheit möglich (Z4/Z5)"
        DailyRecommendationKind.GRUNDLAGE -> "Grundlageneinheit"
    }

    override fun recommendationDetail(kind: DailyRecommendationKind) = when (kind) {
        DailyRecommendationKind.RUHETAG -> "Deine Erholungssignale sprechen für Pause statt Training."
        DailyRecommendationKind.LOCKER_Z2 ->
            "Keine Intervalle — halte die Intensität heute im Grundlagenbereich."
        DailyRecommendationKind.RECOVERY -> "Deine Ermüdung ist gerade hoch — kurz und locker fahren."
        DailyRecommendationKind.HARTE_EINHEIT ->
            "Erholung und Form passen — heute darf ein harter Reiz rein."
        DailyRecommendationKind.GRUNDLAGE ->
            "Fahre nach dem Restbudget deiner Woche, überwiegend im Grundlagentempo " +
                "(Zone 2 — Tempo, bei dem du dich noch unterhalten kannst)."
    }

    override fun deloadTriggerLowForm() = "Dein Formwert liegt seit drei Tagen sehr tief."
    override fun deloadTriggerFastRamp() = "Deine Fitness ist seit drei Wochen sehr schnell gestiegen."
    override fun deloadTriggerLowReadiness(days: Int) =
        "Deine Erholung lag an $days von sieben Tagen im unteren Bereich."
    override fun deloadWarningWeeklyJump() = "Deine Belastung ist diese Woche deutlich gestiegen."
    override fun deloadWarningAcuteLoad() = "Deine akute Belastung liegt klar über deinem gewohnten Niveau."
    override fun deloadTitle(recommended: Boolean) =
        if (recommended) "Entlastungswoche empfohlen" else "Kein Deload nötig"
    override fun deloadDetail(recommended: Boolean) = if (recommended) {
        "Nimm das Wochenvolumen um 40–50 % zurück und behalte die Intensität " +
            "bei — kurze harte Reize dürfen drinbleiben."
    } else {
        "Deine Belastung sieht aktuell tragfähig aus."
    }
}
