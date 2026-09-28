package de.trailscape.core.i18n

/**
 * Allgemeine Zahl-, Zeit- und Wochentagswoerter, die mehrere Bereiche
 * brauchen.
 *
 * Die Wochentage eines Trainingsplans stehen im Plan-JSON als deutsche
 * Kuerzel „Mo" … „So" — das ist ein **interner Code** (mit Web-App und
 * Sync-Server abgestimmt) und bleibt so. Angezeigt wird er nur ueber
 * [weekdayShort]/[weekdayLong].
 */
interface FormatTexts {
    val language: AppLanguage

    /** Kurzform eines Plan-Wochentagscodes: „Mo" → „Mo" / „Mon". Fremdes bleibt unveraendert. */
    fun weekdayShort(planCode: String): String

    /** Langform eines Plan-Wochentagscodes: „Mo" → „Montag" / „Monday". */
    fun weekdayLong(planCode: String): String

    /**
     * Stunden ohne Einheit: ganze Werte ohne Nachkommastelle („5"), sonst
     * eine Stelle („4,5" / „4.5").
     */
    fun hours(hours: Double): String = formatCompactDecimal(hours, language)

    /**
     * Stunden fuer eine Fahrtdauer mit Einheit: „1 h", „1½ h", „2 h"; alles
     * andere ueber [hours] („2,3 h"). Das Bruchzeichen, weil „1,5 h" nach
     * Messwert aussieht und „1½ h" nach dem, was man sagt — in beiden Sprachen.
     */
    fun roundHours(hours: Double): String {
        val halves = hours * 2
        if (halves == kotlin.math.floor(halves)) {
            val whole = (halves / 2).toInt()
            return when {
                halves.toInt() % 2 == 0 -> "$whole h"
                whole == 0 -> "½ h"
                else -> "$whole½ h"
            }
        }
        return "${hours(hours)} h"
    }

    /** Dezimalzahl mit [digits] Stellen im Format der Sprache. */
    fun decimal(value: Double, digits: Int): String = formatDecimal(value, digits, language)
}

/** Interne Plan-Wochentagscodes in Wochenreihenfolge (Mo … So). */
internal val PLAN_WEEKDAY_CODES = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

internal object FormatTextsDe : FormatTexts {
    override val language = AppLanguage.DE
    private val long = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

    override fun weekdayShort(planCode: String): String = planCode

    override fun weekdayLong(planCode: String): String =
        PLAN_WEEKDAY_CODES.indexOf(planCode).let { if (it < 0) planCode else long[it] }
}

internal object FormatTextsEn : FormatTexts {
    override val language = AppLanguage.EN
    private val short = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val long = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    override fun weekdayShort(planCode: String): String =
        PLAN_WEEKDAY_CODES.indexOf(planCode).let { if (it < 0) planCode else short[it] }

    override fun weekdayLong(planCode: String): String =
        PLAN_WEEKDAY_CODES.indexOf(planCode).let { if (it < 0) planCode else long[it] }
}
