package de.trailscape.app.ui.training

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.PlanRecipe
import de.trailscape.core.PlanRecipeOrigin
import de.trailscape.core.PlanRecipeParse
import de.trailscape.core.TrainingPlan
import de.trailscape.core.FitnessLevel
import de.trailscape.core.Goal
import de.trailscape.core.GoalType
import de.trailscape.core.parsePlanRecipe
import de.trailscape.core.previewRecipe
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sichtpruefung der Plan-Rezepte: die Karte auf der Plan-Seite und die
 * Vorschau eines geoeffneten Rezepts (Plan mit Ersetzen-Warnung, einzelne
 * Einheit, ohne FTP-Eintrag). Locale `de`, hell und dunkel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class PlanRecipeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun nurMitScreenshots() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private val planRecipeJson = """
        {"format":"trailscape-plan-recipe","version":1,"title":"Grundlage 8 Wochen","author":"RV Beispiel",
         "notes":"Zwei Qualitätseinheiten pro Woche, am Wochenende lang und ruhig.",
         "weeks":[
          {"kind":"build","sessions":[
            {"day":"tue","title":"Sweet Spot","segments":[
              {"minutes":15,"zone":"endurance"},
              {"minutes":12,"zone":"sweetSpot","repeat":3,"recoveryMinutes":5},
              {"minutes":10,"zone":"recovery"}]},
            {"day":"thu","title":"Schwelle","segments":[
              {"minutes":15,"zone":"endurance"},
              {"minutes":8,"zone":"threshold","repeat":3,"recoveryMinutes":3},
              {"minutes":10,"zone":"recovery"}]},
            {"day":"sat","title":"Lange Fahrt","segments":[{"minutes":150,"ftpPercent":[60,70]}]}]},
          {"kind":"build","sessions":[
            {"day":"tue","title":"Sweet Spot","segments":[{"minutes":12,"zone":"sweetSpot","repeat":4,"recoveryMinutes":5}]},
            {"day":"sat","title":"Lange Fahrt","segments":[{"minutes":180,"ftpPercent":[60,70]}]}]},
          {"kind":"recovery","sessions":[{"day":"sat","title":"Ruhige Runde","segments":[{"minutes":60,"zone":"recovery"}]}]}]}
    """.trimIndent()

    private val workoutJson = """
        {"format":"trailscape-plan-recipe","version":1,"title":"4 × 4 VO2max","workout":{"title":"4 × 4 VO2max","segments":[
          {"minutes":15,"zone":"endurance"},
          {"minutes":4,"zone":"vo2max","repeat":4,"recoveryMinutes":3},
          {"minutes":10,"zone":"recovery"}]}}
    """.trimIndent()

    private fun parse(text: String): PlanRecipe =
        (parsePlanRecipe(text) as PlanRecipeParse.Ok).recipe

    private val someDay = LocalDate.of(2026, 2, 2)

    @Test
    fun karteOhneUndMitImportiertemPlan() {
        val imported = TrainingPlan(
            createdAt = 0L,
            goal = Goal("Grundlage 8 Wochen", 0.0, null, 0L, type = GoalType.FITNESS),
            level = FitnessLevel.FORTGESCHRITTEN,
            weeks = emptyList(),
            recipe = PlanRecipeOrigin("Grundlage 8 Wochen", "RV Beispiel"),
        )
        show(dark = false) {
            PlanRecipeCard(plan = null, canExport = false, onOpen = {}, onSharePlan = {}, onShareSession = {})
            PlanRecipeCard(plan = imported, canExport = true, onOpen = {}, onSharePlan = {}, onShareSession = {})
        }
        compose.onNodeWithText("Importiert: „Grundlage 8 Wochen“ von RV Beispiel").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/81-plan-rezept-karte.png")
    }

    @Test
    fun vorschauPlanErsetztBestehenden() {
        val preview = previewRecipe(parse(planRecipeJson), ftpW = 250, pauseWord = "Pause")
        show(dark = false) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    PlanRecipePreviewBody(
                        preview = preview,
                        ftpEstimated = false,
                        start = someDay,
                        existingPlanName = "Alpen-Marathon",
                        hasPlan = true,
                        dayIndex = 0,
                        onDayChange = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Dein aktueller Plan „Alpen-Marathon“ wird ersetzt. Das lässt sich nicht rückgängig machen.").assertExists()
        compose.onNodeWithText("3 Wochen").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/82-plan-rezept-vorschau.png")
    }

    @Test
    fun vorschauPlanDunkelOhneFtp() {
        val preview = previewRecipe(parse(planRecipeJson), ftpW = null, pauseWord = "Pause")
        show(dark = true) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    PlanRecipePreviewBody(
                        preview = preview,
                        ftpEstimated = true,
                        start = someDay,
                        existingPlanName = null,
                        hasPlan = false,
                        dayIndex = 0,
                        onDayChange = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/83-plan-rezept-vorschau-dunkel.png")
    }

    @Test
    fun vorschauEinzeleinheit() {
        val preview = previewRecipe(parse(workoutJson), ftpW = 240, pauseWord = "Pause")
        show(dark = false) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    PlanRecipePreviewBody(
                        preview = preview,
                        ftpEstimated = true,
                        start = someDay,
                        existingPlanName = null,
                        hasPlan = true,
                        dayIndex = 3,
                        onDayChange = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Die Einheit wird in die laufende Woche deines Plans eingetragen.").assertExists()
        assertTrue(preview.isWorkout)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/84-plan-rezept-einheit.png")
    }

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            TrailscapeTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }
}
