package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Tests der Plan-Rezepte (`PlanRecipe.kt`, `PlanRecipeConvert.kt`):
 * Formatstabilitaet (alt lesen, neu schreiben), strenge Pruefung fremder
 * Dateien, Umrechnung in einen Plan und der Export ohne persoenliche Daten.
 */
class PlanRecipeTest {

    // --- Das Format, wie es 1.0 schreibt: eingefroren. Aendert sich dieser Text, ----------
    // --- aendert sich das Dateiformat — dann `version` erhoehen und docs anpassen. --------
    private val goldenV1 = """
        {
          "format": "trailscape-plan-recipe",
          "version": 1,
          "title": "Grundlage 2 Wochen",
          "author": "RV Beispiel",
          "notes": "Zum Ausprobieren.",
          "weeks": [
            {
              "kind": "build",
              "sessions": [
                {
                  "day": "tue",
                  "title": "Sweet Spot",
                  "segments": [
                    {
                      "minutes": 15,
                      "zone": "endurance"
                    },
                    {
                      "minutes": 12,
                      "zone": "sweetSpot",
                      "repeat": 3,
                      "recoveryMinutes": 5
                    },
                    {
                      "minutes": 10,
                      "zone": "recovery"
                    }
                  ]
                },
                {
                  "day": "sat",
                  "title": "Lange Fahrt",
                  "notes": "Ruhig bleiben.",
                  "segments": [
                    {
                      "label": "Einrollen",
                      "minutes": 60,
                      "ftpPercent": [
                        60,
                        70
                      ]
                    }
                  ]
                }
              ]
            },
            {
              "kind": "recovery",
              "sessions": []
            }
          ]
        }
    """.trimIndent() + "\n"

    private val golden: PlanRecipePlan = PlanRecipePlan(
        title = "Grundlage 2 Wochen",
        author = "RV Beispiel",
        notes = "Zum Ausprobieren.",
        weeks = listOf(
            RecipeWeek(
                RecipeWeekKind.BUILD,
                listOf(
                    RecipeSession(
                        day = 1,
                        title = "Sweet Spot",
                        segments = listOf(
                            RecipeSegment.inZone(15, TargetZone.ENDURANCE),
                            RecipeSegment.inZone(12, TargetZone.SWEET_SPOT, repeat = 3, recoveryMinutes = 5),
                            RecipeSegment.inZone(10, TargetZone.RECOVERY),
                        ),
                    ),
                    RecipeSession(
                        day = 5,
                        title = "Lange Fahrt",
                        notes = "Ruhig bleiben.",
                        segments = listOf(RecipeSegment(60, 60, 70, label = "Einrollen")),
                    ),
                ),
            ),
            RecipeWeek(RecipeWeekKind.RECOVERY, emptyList()),
        ),
    )

    private fun parseOk(text: String): PlanRecipe {
        val result = parsePlanRecipe(text)
        assertIs<PlanRecipeParse.Ok>(result, "erwartet: lesbar, war: $result")
        return result.recipe
    }

    private fun rejection(text: String): RecipeRejection {
        val result = parsePlanRecipe(text)
        assertIs<PlanRecipeParse.Rejected>(result, "erwartet: abgelehnt")
        return result.rejection
    }

    // ------------------------------------------------------------- alt / neu

    @Test
    fun `alte Datei liest sich in genau das erwartete Modell`() {
        assertEquals(golden, parseOk(goldenV1))
    }

    @Test
    fun `Schreiben ergibt byteweise die eingefrorene Datei`() {
        assertEquals(goldenV1, golden.toJsonText())
    }

    @Test
    fun `Lesen und Schreiben sind zueinander stabil`() {
        val text = golden.toJsonText()
        assertEquals(text, (parseOk(text) as PlanRecipePlan).toJsonText())
    }

    @Test
    fun `Einzeleinheit laeuft ueber Schreiben und Lesen`() {
        val workout = PlanRecipeWorkout(
            title = "4x4 VO2max",
            author = null,
            session = RecipeSession(
                day = null,
                title = "4x4 VO2max",
                segments = listOf(
                    RecipeSegment.inZone(15, TargetZone.ENDURANCE),
                    RecipeSegment.inZone(4, TargetZone.VO2MAX, repeat = 4, recoveryMinutes = 3),
                ),
            ),
        )
        assertEquals(workout, parseOk(workout.toJsonText()))
    }

    @Test
    fun `das Beispiel aus docs plan-rezepte md ist lesbar`() {
        val doc = File("../docs/plan-rezepte.md").readText()
        val blocks = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(doc).map { it.groupValues[1] }.toList()
        assertTrue(blocks.size >= 2, "Plan- und Einzelbeispiel erwartet")
        blocks.forEach { block ->
            val result = parsePlanRecipe(block)
            assertIs<PlanRecipeParse.Ok>(result, "Beispiel in der Doku nicht lesbar: $result\n$block")
        }
    }

    @Test
    fun `unbekannte Felder und Werte werden toleriert`() {
        val text = """
            {"format":"trailscape-plan-recipe","version":1,"title":"T","zukunft":{"a":[1,2]},
             "weeks":[{"kind":"irgendwas","extra":1,"sessions":[
               {"day":"MON","title":"E","farbe":"rot","segments":[{"minutes":30,"zone":"endurance","power":5}]}]}]}
        """.trimIndent()
        val recipe = parseOk(text) as PlanRecipePlan
        assertEquals(RecipeWeekKind.BUILD, recipe.weeks[0].kind)
        assertEquals(0, recipe.weeks[0].sessions[0].day)
    }

    @Test
    fun `Byte-Order-Mark vorn stoert nicht`() {
        val result = parsePlanRecipe(("﻿" + goldenV1).toByteArray(Charsets.UTF_8))
        assertIs<PlanRecipeParse.Ok>(result)
    }

    // ------------------------------------------------------ fremde Dateien

    @Test
    fun `neuere Version wird verstaendlich abgelehnt`() {
        val r = rejection("""{"format":"trailscape-plan-recipe","version":7,"title":"x","weeks":[]}""")
        assertEquals(RecipeProblem.NEWER_VERSION, r.problem)
        assertEquals(7, r.version)
    }

    @Test
    fun `ein Backup ist kein Rezept`() {
        assertEquals(RecipeProblem.NOT_A_RECIPE, rejection("""{"version":2,"rides":[],"profile":null}""").problem)
        assertEquals(RecipeProblem.NOT_A_RECIPE, rejection("[1,2,3]").problem)
        assertEquals(RecipeProblem.NOT_A_RECIPE, rejection("""{"format":"anderes","version":1}""").problem)
    }

    @Test
    fun `kaputtes JSON und Muell werfen nie`() {
        listOf("", "   ", "{", "}{", "nonsense", "{\"format\":", "\u0000\u0001", "null", "\"text\"", "12").forEach {
            assertIs<PlanRecipeParse.Rejected>(parsePlanRecipe(it), "Eingabe: '$it'")
        }
        assertEquals(RecipeProblem.TOO_LARGE, (parsePlanRecipe(ByteArray(0)) as PlanRecipeParse.Rejected).rejection.problem)
        assertEquals(RecipeProblem.NOT_TEXT, (parsePlanRecipe(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)) as PlanRecipeParse.Rejected).rejection.problem)
    }

    @Test
    fun `zu grosse Datei wird abgelehnt ohne sie zu lesen`() {
        val big = ByteArray(MAX_RECIPE_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(RecipeProblem.TOO_LARGE, (parsePlanRecipe(big) as PlanRecipeParse.Rejected).rejection.problem)
    }

    @Test
    fun `tiefe Verschachtelung erreicht den Parser nicht`() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertEquals(RecipeProblem.TOO_DEEP, rejection(deep.take(MAX_RECIPE_BYTES)).problem)
        // Klammern in Zeichenketten zaehlen nicht.
        assertEquals(1, jsonNestingDepth("""{"a":"[[[[[[[[[[[["}"""))
    }

    private fun withSegment(segment: String, week: String = "build", day: String = "mon"): String =
        """{"format":"trailscape-plan-recipe","version":1,"title":"T","weeks":[{"kind":"$week","sessions":[{"day":"$day","title":"E","segments":[$segment]}]}]}"""

    @Test
    fun `Wertebereiche werden geprueft und nennen den Pfad`() {
        fun path(segment: String, day: String = "mon") = rejection(withSegment(segment, day = day)).path

        assertEquals("weeks[0].sessions[0].segments[0].minutes", path("""{"minutes":0,"zone":"endurance"}"""))
        assertEquals("weeks[0].sessions[0].segments[0].minutes", path("""{"minutes":601,"zone":"endurance"}"""))
        assertEquals("weeks[0].sessions[0].segments[0].minutes", path("""{"minutes":"30","zone":"endurance"}"""))
        assertEquals("weeks[0].sessions[0].segments[0].minutes", path("""{"minutes":30.5,"zone":"endurance"}"""))
        assertEquals("weeks[0].sessions[0].segments[0].minutes", path("""{"zone":"endurance"}"""))
        assertEquals("weeks[0].sessions[0].segments[0].repeat", path("""{"minutes":5,"zone":"vo2max","repeat":0}"""))
        assertEquals("weeks[0].sessions[0].segments[0].repeat", path("""{"minutes":5,"zone":"vo2max","repeat":61}"""))
        assertEquals("weeks[0].sessions[0].segments[0].recoveryMinutes", path("""{"minutes":5,"zone":"vo2max","repeat":2,"recoveryMinutes":-1}"""))
        assertEquals("weeks[0].sessions[0].segments[0].zone", path("""{"minutes":5,"zone":"turbo"}"""))
        assertEquals("weeks[0].sessions[0].segments[0].zone", path("""{"minutes":5}"""))
        assertEquals("weeks[0].sessions[0].segments[0].ftpPercent[0]", path("""{"minutes":5,"ftpPercent":[10,90]}"""))
        assertEquals("weeks[0].sessions[0].segments[0].ftpPercent[1]", path("""{"minutes":5,"ftpPercent":[90,900]}"""))
        assertEquals("weeks[0].sessions[0].segments[0].ftpPercent", path("""{"minutes":5,"ftpPercent":[100,90]}"""))
        assertEquals("weeks[0].sessions[0].segments[0].ftpPercent", path("""{"minutes":5,"ftpPercent":[100]}"""))
        assertEquals("weeks[0].sessions[0].segments[0].ftpPercent[0]", path("""{"minutes":5,"ftpPercent":["a",90]}"""))
        assertEquals("weeks[0].sessions[0].day", path("""{"minutes":5,"zone":"endurance"}""", day = "xyz"))
    }

    @Test
    fun `Dauer je Einheit ist begrenzt`() {
        val r = rejection(withSegment("""{"minutes":600,"zone":"endurance","repeat":2,"recoveryMinutes":10}"""))
        assertEquals(RecipeProblem.INVALID_FIELD, r.problem)
    }

    @Test
    fun `Anzahlen sind begrenzt`() {
        val seg = """{"minutes":5,"zone":"endurance"}"""
        val tooManySegments = withSegment(List(MAX_RECIPE_SEGMENTS + 1) { seg }.joinToString(","))
        assertEquals(RecipeProblem.TOO_MANY, rejection(tooManySegments).problem)

        val week = """{"kind":"build","sessions":[{"day":"mon","title":"E","segments":[$seg]}]}"""
        val tooManyWeeks = """{"format":"trailscape-plan-recipe","version":1,"title":"T","weeks":[${List(MAX_RECIPE_WEEKS + 1) { week }.joinToString(",")}]}"""
        assertEquals(RecipeProblem.TOO_MANY, rejection(tooManyWeeks).problem)

        val session = """{"day":"mon","title":"E","segments":[$seg]}"""
        val busyWeek = """{"format":"trailscape-plan-recipe","version":1,"title":"T","weeks":[{"sessions":[${List(MAX_RECIPE_SESSIONS_PER_WEEK + 1) { session }.joinToString(",")}]}]}"""
        assertEquals(RecipeProblem.TOO_MANY, rejection(busyWeek).problem)
    }

    @Test
    fun `leere Rezepte und fehlende Pflichtfelder`() {
        assertEquals(RecipeProblem.EMPTY, rejection("""{"format":"trailscape-plan-recipe","version":1,"title":"T","weeks":[]}""").problem)
        assertEquals(RecipeProblem.EMPTY, rejection("""{"format":"trailscape-plan-recipe","version":1,"title":"T","weeks":[{"sessions":[]}]}""").problem)
        assertEquals(RecipeProblem.EMPTY, rejection("""{"format":"trailscape-plan-recipe","version":1,"title":"T"}""").problem)
        assertEquals("title", rejection("""{"format":"trailscape-plan-recipe","version":1,"weeks":[]}""").path)
        assertEquals("version", rejection("""{"format":"trailscape-plan-recipe","title":"T","weeks":[]}""").path)
        assertEquals("version", rejection("""{"format":"trailscape-plan-recipe","version":0,"title":"T","weeks":[]}""").path)
        assertEquals("title", rejection("""{"format":"trailscape-plan-recipe","version":1,"title":"${"x".repeat(MAX_TITLE_CHARS + 1)}","weeks":[]}""").path)
    }

    @Test
    fun `Steuerzeichen im Text werden entfernt`() {
        val recipe = parseOk(
            """{"format":"trailscape-plan-recipe","version":1,"title":"A\u0007B\nC","workout":{"title":"E","segments":[{"minutes":5,"zone":"endurance"}]}}""",
        )
        assertEquals("AB\nC", recipe.title)
    }

    @Test
    fun `Zufaellig veraenderte Dateien werfen nie`() {
        val random = Random(8)
        val base = goldenV1.toByteArray(Charsets.UTF_8)
        repeat(3000) {
            val bytes = base.copyOf()
            repeat(1 + random.nextInt(6)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            val cut = if (random.nextInt(4) == 0) bytes.copyOf(random.nextInt(bytes.size)) else bytes
            parsePlanRecipe(cut) // darf nicht werfen
        }
    }

    // ------------------------------------------------------------ Umrechnung

    private val nowMs = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))
    private val monday = LocalDate.of(2026, 1, 12)

    @Test
    fun `Intensitaet folgt der Struktur`() {
        assertEquals(SessionIntensity.HART, recipeSessionIntensity(golden.weeks[0].sessions[0]))
        assertEquals(SessionIntensity.GRUNDLAGE, recipeSessionIntensity(golden.weeks[0].sessions[1]))
        val easy = RecipeSession(0, "Locker", segments = listOf(RecipeSegment.inZone(45, TargetZone.RECOVERY)))
        assertEquals(SessionIntensity.LOCKER, recipeSessionIntensity(easy))
        // Kurze Spitzen allein machen die Einheit nicht „hart".
        val surges = RecipeSession(
            0, "Antritte",
            segments = listOf(
                RecipeSegment.inZone(60, TargetZone.ENDURANCE),
                RecipeSegment.inZone(1, TargetZone.VO2MAX, repeat = 5, recoveryMinutes = 3),
            ),
        )
        assertEquals(SessionIntensity.GRUNDLAGE, recipeSessionIntensity(surges))
    }

    @Test
    fun `Plan entsteht mit Wochen ab dem Montag und ohne Event`() {
        val plan = planFromRecipe(golden, monday, nowMs)
        assertEquals(2, plan.weeks.size)
        assertEquals(dartEpochMs(monday.atStartOfDay()), plan.weeks[0].start)
        assertEquals(dartEpochMs(monday.plusWeeks(1).atStartOfDay()), plan.weeks[0].end)
        assertEquals(plan.weeks[0].end, plan.weeks[1].start)
        assertEquals(WeekKind.AUFBAU, plan.weeks[0].kind)
        assertEquals(WeekKind.ERHOLUNG, plan.weeks[1].kind)
        assertEquals(GoalType.FITNESS, plan.goal.type)
        assertEquals(PlanRecipeOrigin("Grundlage 2 Wochen", "RV Beispiel"), plan.recipe)
        assertEquals(listOf("Di", "Sa"), plan.weeks[0].sessions.map { it.day })
        assertTrue(plan.weeks.flatMap { it.sessions }.all { it.fromRecipe && !it.isEvent && it.textKey == null })
        assertEquals(dartEpochMs(monday.plusDays(13).atStartOfDay()), plan.goal.date)

        val ss = plan.weeks[0].sessions[0]
        // 15 + 3*12 + 2*5 + 10 = 71 min
        assertEquals(71, ss.durationMin)
        assertEquals(SessionIntensity.HART, ss.intensity)
        assertNotNull(ss.targetLoad)
        assertTrue(ss.description.contains("3 × 12 min 88–94 % FTP"), ss.description)
        assertTrue(ss.targetKm >= 1)
        assertEquals(plan.weeks[0].sessions.sumOf { it.targetKm }, plan.weeks[0].targetKm)
    }

    @Test
    fun `Last wird aus der Struktur gerechnet`() {
        val session = RecipeSession(0, "Eine Stunde an der FTP", segments = listOf(RecipeSegment(60, 100, 100)))
        val ts = sessionFromRecipe(session, 0, 20.0, "rest")
        assertEquals(100.0, ts.targetLoad!!, 0.001)
        assertEquals(20, ts.targetKm)
    }

    @Test
    fun `Rezeptplan ueberlebt Speichern und Laden und alte Plaene bleiben lesbar`() {
        val plan = planFromRecipe(golden, monday, nowMs, speedKmh = 21.0)
        val text = plan.toJson().toString()
        val loaded = TrainingPlan.fromJson(Json.parseToJsonElement(text).jsonObject)
        assertEquals(plan, loaded)

        // alt: Plan ohne die neuen Schluessel
        val old = Json.parseToJsonElement(text).jsonObject.toMutableMap().apply { remove("recipe") }
        val oldPlan = TrainingPlan.fromJson(kotlinx.serialization.json.JsonObject(old))
        assertNull(oldPlan.recipe)
        // ein erzeugter Plan schreibt die neuen Schluessel nicht
        val generated = generatePlan(
            goal = Goal("Ziel", 80.0, null, dartEpochMs(LocalDateTime.of(2026, 4, 19, 9, 0))),
            assessment = FitnessAssessment(FitnessLevel.FORTGESCHRITTEN, 90.0, 500.0, 3.0, 50.0, 20),
            now = nowMs,
            texts = CoreTextsDe,
        )
        val generatedText = generated.toJson().toString()
        assertFalse(generatedText.contains("fromRecipe"))
        assertFalse(generatedText.contains("\"recipe\""))
    }

    @Test
    fun `adaptPlan laesst einen Rezeptplan unveraendert`() {
        val plan = planFromRecipe(golden, LocalDate.of(2026, 1, 5), nowMs)
        val late = dartEpochMs(LocalDateTime.of(2026, 1, 20, 12, 0))
        val adapted = adaptPlan(plan, rides = emptyList(), now = late, texts = CoreTextsDe)
        assertFalse(adapted.adapted)
        assertEquals(plan, adapted.plan)
    }

    @Test
    fun `eingefuegte Einzeleinheit liegt in der laufenden Woche`() {
        val plan = planFromRecipe(golden, LocalDate.of(2026, 1, 5), nowMs)
        val workout = PlanRecipeWorkout(
            "Antritte", null, null,
            RecipeSession(null, "Antritte", segments = listOf(RecipeSegment.inZone(45, TargetZone.ENDURANCE))),
        )
        val updated = assertNotNull(planWithInsertedWorkout(plan, workout, dayIndex = 3, nowMs = nowMs))
        assertEquals(listOf("Di", "Do", "Sa"), updated.weeks[0].sessions.map { it.day })
        assertEquals(updated.weeks[0].sessions.sumOf { it.targetKm }, updated.weeks[0].targetKm)
        assertEquals(plan.weeks[1], updated.weeks[1])
        assertNull(planWithInsertedWorkout(plan.copy(weeks = emptyList()), workout, 3, nowMs))
    }

    @Test
    fun `Einzeleinheit ohne Plan wird ein Ein-Wochen-Plan`() {
        val workout = PlanRecipeWorkout(
            "Antritte", "A", null,
            RecipeSession(null, "Antritte", segments = listOf(RecipeSegment.inZone(45, TargetZone.ENDURANCE))),
        )
        val plan = planFromWorkout(workout, dayIndex = 2, today = LocalDate.of(2026, 1, 7), nowMs = nowMs)
        assertEquals(1, plan.weeks.size)
        assertEquals("Mi", plan.weeks[0].sessions.single().day)
        assertEquals(dartEpochMs(LocalDate.of(2026, 1, 5).atStartOfDay()), plan.weeks[0].start)
    }

    // --------------------------------------------------------------- Vorschau

    @Test
    fun `Vorschau rechnet auf die eigene FTP um`() {
        val preview = previewRecipe(golden, ftpW = 250, pauseWord = "Pause")
        assertEquals(2, preview.weekCount)
        assertEquals(2, preview.sessionCount)
        assertEquals(0, preview.minSessionsPerWeek)
        assertEquals(2, preview.maxSessionsPerWeek)
        assertEquals(71 + 60, preview.totalMinutes)
        assertEquals(71 + 60, preview.peakWeekMinutes)
        val first = preview.firstWeek[0]
        assertTrue(first.structure.contains("3 × 12 min 88–94 % FTP (220–235 W), 5 min Pause"), first.structure)
        assertEquals(250, preview.ftpW)

        val noFtp = previewRecipe(golden, ftpW = null, pauseWord = "Pause")
        assertFalse(noFtp.firstWeek[0].structure.contains(" W)"))
        assertNull(noFtp.ftpW)
        assertNull(previewRecipe(golden, ftpW = 0, pauseWord = "x").ftpW)
    }

    // ----------------------------------------------------------------- Export

    private val assessment = FitnessAssessment(FitnessLevel.AMBITIONIERT, 150.0, 1500.0, 4.0, 90.0, 40)

    @Test
    fun `Export eines erzeugten Plans enthaelt nichts Persoenliches`() {
        val goalName = "Geheimer-Ort-Marathon-Mueller"
        val plan = generatePlan(
            goal = Goal(goalName, 111.0, 2222.0, dartEpochMs(LocalDateTime.of(2026, 5, 17, 9, 0)), targetDurationMin = 333),
            assessment = assessment,
            now = nowMs,
            speedKmh = 23.7,
            preferences = PlanPreferences(trainingDays = listOf(1, 3, 5), longRideDay = 5, weeklyHours = 7.5),
            texts = CoreTextsDe,
        )
        val recipe = assertNotNull(recipeFromPlan(plan, "Mein Plan", "Trainer A", CoreTextsDe))
        val text = recipe.toJsonText()

        assertFalse(text.contains(goalName), text)
        assertFalse(text.contains("Mueller"))
        assertFalse(text.contains("Zielevent", ignoreCase = true), "das Event gehoert nicht in die Datei")
        listOf("createdAt", "goal", "level", "planSpeedKmh", "preferences", "targetKm", "targetLoad", "distance", "date", "ascent", "weeklyHours")
            .forEach { assertFalse(text.contains("\"$it\""), "Schluessel $it in der Datei") }
        assertFalse(text.contains("23.7"))
        assertEquals(setOf("format", "version", "title", "author", "weeks"), Json.parseToJsonElement(text).jsonObject.keys)

        // Die Datei ist wieder lesbar, und die Struktur bleibt.
        val back = parseOk(text) as PlanRecipePlan
        assertEquals("Mein Plan", back.title)
        assertEquals("Trainer A", back.author)
        assertEquals(plan.weeks.size.coerceAtMost(MAX_RECIPE_WEEKS), back.weeks.size)
        assertEquals(recipe, back)
        val exportedSessions = plan.weeks.sumOf { w -> w.sessions.count { !it.isEvent } }
        assertTrue(back.weeks.sumOf { it.sessions.size } in 1..exportedSessions)
        assertEquals(plan.weeks.map { RecipeWeekKind.of(it.kind) }, back.weeks.map { it.kind })
    }

    @Test
    fun `Export und Import ergeben Plaene gleicher Struktur`() {
        val plan = generatePlan(
            goal = Goal("Ziel", 80.0, null, dartEpochMs(LocalDateTime.of(2026, 3, 29, 9, 0))),
            assessment = assessment,
            now = nowMs,
            texts = CoreTextsDe,
        )
        val recipe = assertNotNull(recipeFromPlan(plan, "Rundlauf", null, CoreTextsDe))
        val imported = planFromRecipe(parseOk(recipe.toJsonText()) as PlanRecipePlan, monday, nowMs)
        assertEquals(plan.weeks.size, imported.weeks.size)
        assertTrue(imported.weeks.sumOf { it.sessions.size } > 0)
    }

    @Test
    fun `Einzeleinheit exportieren`() {
        val plan = generatePlan(
            goal = Goal("Ziel", 80.0, null, dartEpochMs(LocalDateTime.of(2026, 3, 29, 9, 0))),
            assessment = assessment,
            now = nowMs,
            texts = CoreTextsDe,
        )
        val hard = plan.weeks.flatMap { it.sessions }.first { it.textKey == de.trailscape.core.i18n.SessionTextKey.WORKOUT }
        val recipe = assertNotNull(recipeFromSession(hard, title = null, author = "  ", texts = CoreTextsDe))
        assertNull(recipe.author)
        assertNull(recipe.session.day)
        assertEquals(hard.durationMin, recipe.session.totalMinutes)
        val text = recipe.toJsonText()
        assertTrue(text.contains("\"workout\""))
        assertEquals(recipe, parseOk(text))

        val event = plan.weeks.flatMap { it.sessions }.first { it.isEvent }
        assertNull(recipeFromSession(event, null, null, CoreTextsDe))
    }

    @Test
    fun `Dateiname hat keine Pfadzeichen`() {
        assertEquals("Grundlage-12-Wochen.tsplan.json", planRecipeFileName("Grundlage 12 Wochen"))
        assertEquals("plan.tsplan.json", planRecipeFileName("///"))
        assertFalse(planRecipeFileName("../../etc/passwd").contains('/'))
        assertTrue(planRecipeFileName("x".repeat(500)).length < 70)
    }
}
