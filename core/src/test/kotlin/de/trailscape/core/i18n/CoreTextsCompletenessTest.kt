package de.trailscape.core.i18n

import de.trailscape.core.AscentPreference
import de.trailscape.core.Confidence
import de.trailscape.core.DailyRecommendationKind
import de.trailscape.core.EftpSource
import de.trailscape.core.FitSport
import de.trailscape.core.FitnessDirection
import de.trailscape.core.FitnessLevel
import de.trailscape.core.HrvStatus
import de.trailscape.core.LoadRatioBand
import de.trailscape.core.LoadSource
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.RampBand
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.RouteProfile
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TsbBand
import de.trailscape.core.TurnRichtung
import de.trailscape.core.WeekKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Beide Sprachen sind vollstaendig: jedes Label-Enum hat in DE und EN einen
 * nicht-leeren, verschiedenen Text, und im Englischen steht kein deutsches
 * Zeichen („…", Umlaute).
 */
class CoreTextsCompletenessTest {

    /** Woerter, die in beiden Sprachen gleich lauten duerfen. */
    private val sameInBoth = setOf("Neutral", "Taper")

    private fun <E> checkLabels(name: String, values: Iterable<E>, label: (CoreTexts, E) -> String) {
        val problems = mutableListOf<String>()
        for (value in values) {
            val de = label(CoreTextsDe, value)
            val en = label(CoreTextsEn, value)
            if (de.isBlank()) problems += "$name.$value: DE leer"
            if (en.isBlank()) problems += "$name.$value: EN leer"
            if (de == en && de !in sameInBoth) problems += "$name.$value: DE = EN (»$de«)"
            if (!looksEnglish(en)) problems += "$name.$value: EN mit deutschen Zeichen (»$en«)"
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun looksEnglish(text: String): Boolean = text.none { it in "äöüÄÖÜß„" }

    @Test
    fun `alle Label-Enums haben zwei Sprachen`() {
        checkLabels("RecoveryFlag", RecoveryFlag.entries) { t, v -> t.readiness.recoveryFlag(v) }
        checkLabels("HrvStatus", HrvStatus.entries) { t, v -> t.readiness.hrvStatus(v) }
        checkLabels("ReadinessBand", ReadinessBand.entries) { t, v -> t.readiness.readinessBand(v) }
        checkLabels("ReadinessSignal", ReadinessSignal.entries) { t, v -> t.readiness.signalName(v) }
        checkLabels("DailyRecommendationKind.title", DailyRecommendationKind.entries) { t, v ->
            t.readiness.recommendationTitle(v)
        }
        checkLabels("DailyRecommendationKind.detail", DailyRecommendationKind.entries) { t, v ->
            t.readiness.recommendationDetail(v)
        }
        checkLabels("TsbBand", TsbBand.entries) { t, v -> t.load.tsbBand(v) }
        checkLabels("TsbBand.message", TsbBand.entries) { t, v -> t.load.tsbBandMessage(v) }
        checkLabels("RampBand", RampBand.entries) { t, v -> t.load.rampBand(v) }
        checkLabels("LoadRatioBand", LoadRatioBand.entries) { t, v -> t.load.loadRatioBand(v) }
        checkLabels("Confidence", Confidence.entries) { t, v -> t.load.confidence(v) }
        checkLabels("LoadSource", LoadSource.entries) { t, v -> t.load.loadSource(v) }
        checkLabels("EftpSource", EftpSource.entries) { t, v -> t.load.eftpSource(v) }
        checkLabels("Freshness", TsbBand.entries) { t, v -> t.goal.freshnessWord(v) }
        checkLabels("FitnessDirection", FitnessDirection.entries) { t, v -> t.goal.fitnessTrend(v, 3) }
        checkLabels("RouteProfile", RouteProfile.entries) { t, v -> t.routing.routeProfile(v) }
        checkLabels("TurnRichtung", TurnRichtung.entries) { t, v -> t.speech.turnDirection(v) }
        checkLabels("WindVerdict", WindVerdict.entries) { t, v -> t.routing.windLine(10, "x", null, v) }
        checkLabels("AscentPreference", AscentPreference.entries) { t, v -> t.today.ascentPreference(v) }
        checkLabels("DowngradeReason", DailyRecommendationKind.entries) { t, v -> t.today.downgradeReason(v) }
        checkLabels("FitnessLevel", FitnessLevel.entries) { t, v -> t.training.fitnessLevel(v) }
        checkLabels("WeekKind", WeekKind.entries) { t, v -> t.training.weekKind(v) }
        checkLabels("SessionIntensity", SessionIntensity.entries) { t, v -> t.training.sessionIntensity(v) }
        checkLabels("PlanSessionStatus", PlanSessionStatus.entries) { t, v -> t.training.planSessionStatus(v) }
        checkLabels("FitSport", FitSport.entries) { t, v -> t.files.fitActivityName(v, 0L) }
        checkLabels("Weekday.short", PLAN_WEEKDAY_CODES.drop(1)) { t, v -> t.format.weekdayShort(v) }
        checkLabels("Weekday.long", PLAN_WEEKDAY_CODES) { t, v -> t.format.weekdayLong(v) }
    }

    @Test
    fun `jeder Plantext-Schluessel hat Titel und Beschreibung in beiden Sprachen`() {
        fun argsFor(key: SessionTextKey): List<Int> = when (key) {
            SessionTextKey.LONG_RIDE -> listOf(1)
            SessionTextKey.INTERVALS -> listOf(20, 4, 8, 4, 10, 94, 0)
            SessionTextKey.GOAL_EVENT -> listOf(120, 1400)
            else -> emptyList()
        }
        checkLabels("SessionTextKey.title", SessionTextKey.entries) { t, v ->
            t.training.sessionTitle(v, argsFor(v), "Alb-Gold")
        }
        checkLabels("SessionTextKey.description", SessionTextKey.entries) { t, v ->
            t.training.sessionDescription(v, argsFor(v))
        }
        // Die JSON-Namen sind eindeutig und stabil.
        assertEquals(SessionTextKey.entries.size, SessionTextKey.entries.map { it.jsonName }.toSet().size)
        for (key in SessionTextKey.entries) {
            assertEquals(key, SessionTextKey.fromJsonNameOrNull(key.jsonName))
        }
    }

    /**
     * Alle Texte ohne Argumente, per Reflexion aus jedem Bereich: in beiden
     * Sprachen nicht leer, im Englischen ohne deutsche Zeichen.
     */
    @Test
    fun `alle argumentlosen Saetze sind vollstaendig`() {
        val areas: List<(CoreTexts) -> Any> = listOf(
            { it.speech }, { it.readiness }, { it.load }, { it.goal }, { it.training }, { it.today },
            { it.routing }, { it.health }, { it.files }, { it.sync },
        )
        val problems = mutableListOf<String>()
        var checked = 0
        for (area in areas) {
            val de = area(CoreTextsDe)
            val en = area(CoreTextsEn)
            val methods = de.javaClass.methods.filter {
                it.parameterCount == 0 && it.returnType == String::class.java && it.declaringClass != Any::class.java
            }
            for (method in methods) {
                val deText = method.invoke(de) as String
                val enText = en.javaClass.getMethod(method.name).invoke(en) as String
                checked++
                if (deText.isBlank() || enText.isBlank()) problems += "${method.name}: leer"
                if (!looksEnglish(enText)) problems += "${method.name}: EN mit deutschen Zeichen (»$enText«)"
            }
        }
        assertTrue(checked > 60, "zu wenige Saetze gefunden: $checked")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
