package de.trailscape.core.i18n

import de.trailscape.core.TurnRichtung
import de.trailscape.core.ansageAbstandM

/**
 * Saetze der Sprachausgabe (TTS). Kurz, ohne Abkuerzungen und ohne Zeichen,
 * die eine Engine buchstabieren wuerde — „Kilometer" statt „km".
 *
 * Englisch mit britischer Schreibweise („metres"), passend zur Stimme
 * `Locale.UK`, die der Announcer bevorzugt.
 */
interface SpeechTexts {
    /** Das Richtungswort: „links", „scharf rechts" / „left", „sharp right". */
    fun turnDirection(richtung: TurnRichtung): String

    /**
     * Abbiegeansage fuer [abstandM] Meter voraus. Die Rundung auf 50 m und
     * die Nahbereichsgrenze stecken in [ansageAbstandM] und gelten damit fuer
     * beide Sprachen gleich.
     */
    fun turn(richtung: TurnRichtung, abstandM: Double): String

    /**
     * Kilometer-Meilenstein — „15 Kilometer, 42 Minuten." bzw. ab einer
     * Stunde „25 Kilometer, 1 Stunde 12 Minuten." Sekunden werden bewusst
     * verschwiegen: Beim Fahren interessiert die Groessenordnung.
     */
    fun milestone(km: Int, elapsedMs: Long): String

    fun offRoute(): String
    fun backOnRoute(): String
    fun destinationReached(): String
    fun recordingStarted(): String
    fun recordingPaused(): String
    fun recordingResumed(): String
    fun recordingStopped(): String
}

/** Stunden/Minuten einer Dauer, ohne Sekunden. */
private fun hoursMinutes(elapsedMs: Long): Pair<Int, Int> {
    val total = (elapsedMs / 60_000L).toInt().coerceAtLeast(0)
    return total / 60 to total % 60
}

internal object SpeechTextsDe : SpeechTexts {
    override fun turnDirection(richtung: TurnRichtung): String = when (richtung) {
        TurnRichtung.LINKS -> "links"
        TurnRichtung.RECHTS -> "rechts"
        TurnRichtung.KEHRE_LINKS -> "scharf links"
        TurnRichtung.KEHRE_RECHTS -> "scharf rechts"
    }

    override fun turn(richtung: TurnRichtung, abstandM: Double): String {
        val wort = turnDirection(richtung)
        val meter = ansageAbstandM(abstandM) ?: return "Gleich $wort."
        return "In $meter Metern $wort."
    }

    override fun milestone(km: Int, elapsedMs: Long): String {
        val (stunden, minuten) = hoursMinutes(elapsedMs)
        val minutenWort = plural(minuten, "1 Minute", "$minuten Minuten")
        val stundenWort = plural(stunden, "1 Stunde", "$stunden Stunden")
        val zeit = when {
            stunden > 0 && minuten > 0 -> "$stundenWort $minutenWort"
            stunden > 0 -> stundenWort
            else -> minutenWort
        }
        return "$km Kilometer, $zeit."
    }

    override fun offRoute() = "Du bist abseits der Route."
    override fun backOnRoute() = "Zurück auf der Route."
    override fun destinationReached() = "Ziel erreicht."
    override fun recordingStarted() = "Aufzeichnung gestartet."
    override fun recordingPaused() = "Aufzeichnung pausiert."
    override fun recordingResumed() = "Aufzeichnung fortgesetzt."
    override fun recordingStopped() = "Aufzeichnung beendet."
}

internal object SpeechTextsEn : SpeechTexts {
    override fun turnDirection(richtung: TurnRichtung): String = when (richtung) {
        TurnRichtung.LINKS -> "left"
        TurnRichtung.RECHTS -> "right"
        TurnRichtung.KEHRE_LINKS -> "sharp left"
        TurnRichtung.KEHRE_RECHTS -> "sharp right"
    }

    override fun turn(richtung: TurnRichtung, abstandM: Double): String {
        val word = turnDirection(richtung)
        val metres = ansageAbstandM(abstandM) ?: return "Now turn $word."
        return "In $metres metres, turn $word."
    }

    override fun milestone(km: Int, elapsedMs: Long): String {
        val (hours, minutes) = hoursMinutes(elapsedMs)
        val minuteWord = plural(minutes, "1 minute", "$minutes minutes")
        val hourWord = plural(hours, "1 hour", "$hours hours")
        val time = when {
            hours > 0 && minutes > 0 -> "$hourWord $minuteWord"
            hours > 0 -> hourWord
            else -> minuteWord
        }
        return "${plural(km, "1 kilometre", "$km kilometres")}, $time."
    }

    override fun offRoute() = "You’re off route."
    override fun backOnRoute() = "Back on route."
    override fun destinationReached() = "You’ve arrived."
    override fun recordingStarted() = "Recording started."
    override fun recordingPaused() = "Recording paused."
    override fun recordingResumed() = "Recording resumed."
    override fun recordingStopped() = "Recording stopped."
}
