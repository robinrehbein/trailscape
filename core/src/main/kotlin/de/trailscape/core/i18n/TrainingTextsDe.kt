package de.trailscape.core.i18n

import de.trailscape.core.FitnessLevel
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.SessionIntensity
import de.trailscape.core.WeekKind

internal object TrainingTextsDe : TrainingTexts {
    override fun errorTooSoon() = "Das Ziel liegt zu nah in der Zukunft – plane mindestens 3 Wochen ein."
    override fun errorTooFar() = "Das Ziel liegt mehr als ein Jahr entfernt."

    override fun fitnessLevel(level: FitnessLevel) = when (level) {
        FitnessLevel.EINSTEIGER -> "Einsteiger"
        FitnessLevel.FORTGESCHRITTEN -> "Fortgeschritten"
        FitnessLevel.AMBITIONIERT -> "Ambitioniert"
    }

    override fun weekKind(kind: WeekKind) = when (kind) {
        WeekKind.AUFBAU -> "Aufbau"
        WeekKind.ERHOLUNG -> "Erholung"
        WeekKind.TAPER -> "Taper"
        WeekKind.ZIELWOCHE -> "Zielwoche"
    }

    override fun sessionIntensity(intensity: SessionIntensity) = when (intensity) {
        SessionIntensity.LOCKER -> "locker"
        SessionIntensity.GRUNDLAGE -> "Grundlage"
        SessionIntensity.HART -> "intensiv"
    }

    override fun planSessionStatus(status: PlanSessionStatus) = when (status) {
        PlanSessionStatus.OFFEN -> "Offen"
        PlanSessionStatus.ERLEDIGT -> "Erledigt"
        PlanSessionStatus.TEILWEISE -> "Teilweise"
        PlanSessionStatus.VERPASST -> "Verpasst"
    }

    override fun sessionTitle(key: SessionTextKey, args: List<Int>, eventName: String?) = when (key) {
        SessionTextKey.RECOVERY_EASY_RIDE -> "Lockere Ausfahrt"
        SessionTextKey.RECOVERY_CALM_LOOP -> "Ruhige Runde"
        SessionTextKey.TAPER_EASY_SURGES -> "Locker mit Antritten"
        SessionTextKey.TAPER_SHORT_EASY -> "Kurze lockere Ausfahrt"
        SessionTextKey.BASE_EASY_RIDE -> "Lockere Ausfahrt GA1"
        SessionTextKey.LONG_RIDE -> "Lange Tour"
        SessionTextKey.RECOVERY_SPIN -> "Regeneration locker"
        SessionTextKey.ENDURANCE_FILL, SessionTextKey.ENDURANCE_STEADY -> "GA1"
        SessionTextKey.ENDURANCE_COMPENSATION -> "GA1 kompensatorisch"
        SessionTextKey.INTERVALS -> "Intervalle"
        SessionTextKey.GOAL_EVENT -> "Zielevent: ${eventName.orEmpty()}"
        SessionTextKey.ACTIVATION -> "Aktivierung locker"
    }

    override fun sessionDescription(key: SessionTextKey, args: List<Int>) = when (key) {
        SessionTextKey.RECOVERY_EASY_RIDE ->
            "Entspannt rollen, kleine Gänge und hohe Trittfrequenz – diese " +
                "Woche dient ausschließlich der Erholung."
        SessionTextKey.RECOVERY_CALM_LOOP ->
            "Gemütliche Ausfahrt ohne Leistungsdruck, halte den Puls " +
                "durchgehend im niedrigen Bereich."
        SessionTextKey.TAPER_EASY_SURGES ->
            "Locker rollen und dabei 3 kurze Antritte über je 30 Sekunden " +
                "einstreuen, um spritzig zu bleiben."
        SessionTextKey.TAPER_SHORT_EASY ->
            "Kurz und ruhig fahren, danach Material checken und die Beine " +
                "bewusst schonen."
        SessionTextKey.BASE_EASY_RIDE ->
            "Ruhiges Grundlagentempo – du solltest dich während der gesamten " +
                "Fahrt unterhalten können."
        SessionTextKey.LONG_RIDE -> {
            val base = "Die Schlüsseleinheit der Woche: gleichmäßig im Grundlagentempo fahren und " +
                "konsequent essen und trinken."
            if (args.firstOrNull() == 1) {
                "$base Baue dabei bewusst Anstiege ein, um dich an die Höhenmeter des Ziels zu gewöhnen."
            } else {
                base
            }
        }
        SessionTextKey.RECOVERY_SPIN ->
            "Kurze Regenerationsrunde im leichten Gang, bewusst niedrige " +
                "Intensität für frische Beine."
        SessionTextKey.ENDURANCE_FILL ->
            "Lockere Grundlageneinheit zum Auffüllen des Wochenvolumens, Puls " +
                "konstant im GA1-Bereich halten."
        SessionTextKey.ENDURANCE_STEADY ->
            "Ruhige Grundlageneinheit, gleichmäßige Belastung ohne Spitzen und " +
                "ohne Sprints."
        SessionTextKey.ENDURANCE_COMPENSATION ->
            "Kompensationsrunde mit hoher Trittfrequenz, um die Beine nach der " +
                "langen Tour wieder locker zu fahren."
        SessionTextKey.INTERVALS -> {
            val effort = if (args[INTERVAL_ARG_EFFORT] == 1) "hart an der Schwelle" else "zügig im Schwellenbereich"
            "Nach ${args[0]} Minuten Einfahren ${args[1]}×${args[2]} Minuten " +
                "$effort, dazwischen je ${args[3]} Minuten locker rollen; zum " +
                "Abschluss ${args[4]} Minuten ausfahren – zusammen rund ${args[5]} Minuten."
        }
        SessionTextKey.GOAL_EVENT -> if (args[1] >= 0) {
            "Dein Zielevent über ${args[0]} km und rund ${args[1]} Hm – " +
                "teile dir die Kraft an den Anstiegen ein und trinke von Beginn an regelmäßig."
        } else {
            "Dein Zielevent über ${args[0]} km – starte kontrolliert, halte dein Tempo und " +
                "versorge dich unterwegs konsequent."
        }
        SessionTextKey.ACTIVATION ->
            "Kurze lockere Runde mit ein paar Antritten, danach Rad und " +
                "Verpflegung für den Zieltag vorbereiten."
    }

    override fun plainTitleEvent(km: Int) = "Rennen $km km"
    override fun plainTitleLongRide(km: Int) = "Lange Fahrt $km km"
    override fun plainTitleHard(km: Int) = "Hart $km km"
    override fun plainTitleEasy(km: Int) = "Locker $km km"
    override fun plainHintEvent() = "dein Ziel"
    override fun plainHintLongRide(hilly: Boolean) =
        if (hilly) "gleichmäßig, mit Höhenmetern wie im Rennen" else "gleichmäßig, lange im Sattel"
    override fun plainHintHard() = "mit harten Abschnitten, dazwischen locker"
    override fun plainHintRecovery() = "ganz ruhig, für frische Beine"
    override fun plainHintEndurance() = "ruhig, du kannst dich dabei unterhalten"

    override fun planAdapted(weekNumber: Int, percent: Int) =
        "Plan angepasst: In Woche $weekNumber hast du nur $percent % des " +
            "Wochen-Solls erreicht. Die verbleibenden Wochen bauen wieder von deinem " +
            "tatsächlichen Umfang auf – Ziel und Termin bleiben unverändert."

    override fun planNotFeasible(
        longestKm: Int,
        percent: Int,
        goalKm: Int,
        suggestedKm: Int,
        suggestedWeeks: Int?,
        needsLongerPlan: Boolean,
    ): String {
        val outlook = when {
            suggestedWeeks != null && needsLongerPlan ->
                "Für die vollen $goalKm km brauchst du von deinem heutigen Umfang aus rund " +
                    "$suggestedWeeks Wochen."
            suggestedWeeks != null ->
                "Mit einem etwas anderen Zuschnitt wären die $goalKm km in $suggestedWeeks " +
                    "Wochen erreichbar."
            else ->
                "Für die vollen $goalKm km reicht selbst ein Jahr Vorbereitung von deinem " +
                    "heutigen Umfang aus nicht — bau erst über eine Zwischendistanz auf."
        }
        return "Die längste Fahrt in diesem Plan sind $longestKm km – nur $percent % deiner " +
            "Zieldistanz von $goalKm km. Realistisch trägt dieser Plan ein Ziel um " +
            "$suggestedKm km. $outlook"
    }
}
