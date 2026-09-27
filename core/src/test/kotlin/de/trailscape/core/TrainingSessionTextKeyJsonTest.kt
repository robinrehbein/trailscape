package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import de.trailscape.core.i18n.SessionTextKey
import de.trailscape.core.i18n.sessionDescription
import de.trailscape.core.i18n.sessionTitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plantexte mit Schluessel: angehaengte JSON-Felder `textKey`/`textArgs`,
 * Neurendern in der aktuellen Sprache, Altplaene unveraendert.
 */
class TrainingSessionTextKeyJsonTest {

    private val now = dartEpochMs(java.time.LocalDateTime.of(2026, 6, 1, 9, 0))
    private val goal = Goal(
        name = "Alb-Gold",
        distanceKm = 120.0,
        ascentM = 1400.0,
        date = now + 11L * 7 * 24 * 60 * 60 * 1000,
    )
    private val assessment = FitnessAssessment(
        level = FitnessLevel.AMBITIONIERT,
        weeklyKm = 150.0,
        weeklyHm = 1200.0,
        weeklyRides = 4.0,
        longestRideKm = 90.0,
        rideCount = 20,
    )

    private fun planDe() = generatePlan(goal, assessment, now = now, texts = CoreTextsDe)

    @Test
    fun `jede erzeugte Einheit traegt einen Textschluessel`() {
        val sessions = planDe().weeks.flatMap { it.sessions }
        assertTrue(sessions.isNotEmpty())
        assertTrue(sessions.all { it.textKey != null }, "ohne Schluessel: ${sessions.filter { it.textKey == null }}")
        val event = sessions.single { it.isEvent }
        assertEquals(SessionTextKey.GOAL_EVENT, event.textKey)
        assertEquals(listOf(120, 1400), event.textArgs)
        assertEquals("Zielevent: Alb-Gold", event.title)
    }

    @Test
    fun `JSON geht mit Schluessel und Argumenten hin und zurueck`() {
        val plan = planDe()
        val restored = TrainingPlan.fromJson(Json.parseToJsonElement(plan.toJson().toString()).jsonObject)
        assertEquals(plan, restored)
        val intervals = restored.weeks.flatMap { it.sessions }.first { it.textKey == SessionTextKey.INTERVALS }
        assertEquals(7, intervals.textArgs.size)
        val json = intervals.toJson()
        assertEquals("intervals", (json["textKey"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `Altplan ohne die neuen Felder laedt unveraendert und zeigt den gespeicherten Text`() {
        val raw = """{"day":"Sa","title":"Lange Tour","description":"Alter Text.","targetKm":80}"""
        val session = TrainingSession.fromJson(Json.parseToJsonElement(raw) as JsonObject)
        assertNull(session.textKey)
        assertTrue(session.textArgs.isEmpty())
        assertFalse(session.toJson().containsKey("textKey"))
        assertFalse(session.toJson().containsKey("textArgs"))
        // Ohne Schluessel gilt der gespeicherte Text — auch auf Englisch.
        assertEquals("Lange Tour", sessionTitle(session, CoreTextsEn))
        assertEquals("Alter Text.", sessionDescription(session, CoreTextsEn))
        assertTrue(isLongRideSession(session))
    }

    @Test
    fun `ein auf Deutsch erzeugter Plan erscheint auf Englisch in Englisch`() {
        val sessions = planDe().weeks.flatMap { it.sessions }
        val event = sessions.single { it.isEvent }
        assertEquals("Goal event: Alb-Gold", sessionTitle(event, CoreTextsEn))
        assertEquals(
            "Your goal event over 120 km and about 1400 m of climbing – pace yourself on the climbs " +
                "and drink regularly from the start.",
            sessionDescription(event, CoreTextsEn),
        )
        val long = sessions.first { it.textKey == SessionTextKey.LONG_RIDE }
        assertEquals("Long ride", sessionTitle(long, CoreTextsEn))
        assertTrue(sessionDescription(long, CoreTextsEn).endsWith("elevation gain of your goal."))
        assertTrue(isLongRideSession(long))
        // Der Anstiegs-Hinweis kommt aus den Argumenten, nicht mehr aus dem deutschen Text.
        val english = long.copy(title = sessionTitle(long, CoreTextsEn), description = sessionDescription(long, CoreTextsEn))
        assertEquals(AscentPreference.BERGIG, ascentPreferenceForSession(english))

        val intervals = sessions.first { it.textKey == SessionTextKey.INTERVALS }
        val args = intervals.textArgs
        assertEquals(
            "After ${args[0]} minutes of warming up, ${args[1]}×${args[2]} minutes hard at threshold " +
                "with ${args[3]} easy minutes in between; finish with ${args[4]} minutes of cooling down – " +
                "about ${args[5]} minutes in total.",
            sessionDescription(intervals, CoreTextsEn),
        )
        assertEquals(intervals.description, sessionDescription(intervals, CoreTextsDe))
    }

    @Test
    fun `ein auf Englisch erzeugter Plan speichert englische Rueckfalltexte und dieselben Schluessel`() {
        val en = generatePlan(goal, assessment, now = now, texts = CoreTextsEn)
        val de = planDe()
        assertEquals(
            de.weeks.flatMap { w -> w.sessions.map { it.textKey to it.textArgs } },
            en.weeks.flatMap { w -> w.sessions.map { it.textKey to it.textArgs } },
        )
        val event = en.weeks.flatMap { it.sessions }.single { it.isEvent }
        assertEquals("Goal event: Alb-Gold", event.title)
        assertEquals("Zielevent: Alb-Gold", sessionTitle(event, CoreTextsDe))
        // Die Intensitaet steht als Feld — die deutsche Titel-Heuristik wird nicht gebraucht.
        assertEquals(SessionIntensity.HART, event.intensity)
    }

    @Test
    fun `Klartext der Wochenliste in beiden Sprachen`() {
        val sessions = planDe().weeks.first().sessions
        val long = sessions.first { it.textKey == SessionTextKey.LONG_RIDE }
        assertEquals("Lange Fahrt ${long.targetKm} km", plainSessionTitle(long, CoreTextsDe))
        assertEquals("Long ride ${long.targetKm} km", plainSessionTitle(long, CoreTextsEn))
        assertEquals("steady, with climbing like in the race", plainSessionHint(long, goal, CoreTextsEn))
    }
}
