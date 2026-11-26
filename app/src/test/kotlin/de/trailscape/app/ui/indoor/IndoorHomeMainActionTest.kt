package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.indoor.DayRouteAvailability
import de.trailscape.core.indoor.DaySessionKind
import de.trailscape.core.indoor.DayTerrain
import de.trailscape.core.indoor.IndoorMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Sicht „Strecken" des Indoor-Tabs auf einem schmalen Handy (360 dp) mit
 * Systemschrift 130 %: genau eine Hauptaktion, und kein Knopftext bricht um.
 *
 * Laeuft — anders als die Screenshot-Tests — bei jedem Testlauf mit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "${TestLocales.DE}-w360dp-h1400dp-xxhdpi", application = ScreenshotApplication::class)
class IndoorHomeMainActionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val saved = IndoorRouteOption.Saved(
        rideId = "r1", name = "Hausrunde Ammersee", distanceKm = 42.3, ascentM = 310.0,
        createdAt = 1_760_000_000_000, planned = false, hasWorld = true,
    )

    private fun route(selected: IndoorRouteOption?) = IndoorRouteUi(
        options = listOf(IndoorRouteOption.TestWorld, saved),
        selected = selected,
        mode = IndoorMode.SIM,
        ergTitle = "Schwelle 3 x 8 Min",
        ergMinutes = 52,
        ghost = IndoorGhostOption.None,
        ghostPastRideAvailable = false,
        defaultGhostWatts = 180,
    )

    private fun suggestion(i: Int, availability: DayRouteAvailability) = IndoorDaySuggestionUi(
        key = "catalog:s$i", name = "Strecke $i", availability = availability, rideKm = 15.7,
        downloadBytes = 114L * 1024 * 1024, estimated = true,
    )

    /** Sechs Vorschlaege, gezeigt wird der vierte: eine Strecke zum Herunterladen. */
    private val offer = IndoorDayUi.Offer(
        kind = DaySessionKind.PLANNED,
        title = "Schwelle 3 x 8 Min",
        minutes = 52,
        plannedMinutes = null,
        terrain = DayTerrain.FLAT,
        suggestions = List(6) { suggestion(it, if (it == 3) DayRouteAvailability.DOWNLOAD else DayRouteAvailability.READY) },
        index = 3,
    )

    private val paired = IndoorPairUi(demoTrainer = false, trainerName = "KICKR CORE 7B2F", shifterName = null)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f), content = content)
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Home(route: IndoorRouteUi, day: IndoorDayUi?, issue: IndoorTrainerIssue? = null) = IndoorHomeScreen(
        route = route, pair = paired, trainerIssue = issue,
        onSelect = {}, onModeChange = {}, onGhostChange = {}, onRide = {}, onBack = null, onOpenPair = {}, onOpenSettings = {},
        day = day,
    )

    /** Zeilen, in die der Text mit genau dieser Beschriftung gesetzt wurde. */
    private fun lineCount(text: String): Int {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first().lineCount
    }

    @Test
    fun `mit Vorschlag der Tageskarte gibt es unten weder Hinweis noch zweiten Knopf`() {
        show { Home(route(selected = null), offer) }
        compose.onNodeWithText("Herunterladen und fahren").assertIsEnabled()
        compose.onAllNodesWithText("Wähle oben eine Strecke.").assertCountEquals(0)
        compose.onAllNodesWithText("Fahren").assertCountEquals(0)
    }

    @Test
    fun `die Knoepfe der Tageskarte brechen bei 360 dp und Schrift 130 Prozent nicht um`() {
        show { Home(route(selected = null), offer) }
        assertEquals(1, lineCount("Herunterladen und fahren"))
        assertEquals(1, lineCount("Andere Strecke (4/6)"))
    }

    @Test
    fun `ohne Tageskarte bleiben Hinweis und Knopf unten`() {
        show { Home(route(selected = null), day = null) }
        compose.onNodeWithText("Wähle oben eine Strecke.").assertExists()
        compose.onNodeWithText("Fahren").assertExists()
    }

    @Test
    fun `ohne Vorschlag in der Tageskarte bleiben Hinweis und Knopf unten`() {
        show { Home(route(selected = null), offer.copy(suggestions = emptyList(), index = 0)) }
        compose.onNodeWithText("Wähle oben eine Strecke.").assertExists()
        compose.onNodeWithText("Fahren").assertExists()
    }

    @Test
    fun `mit gewaehlter Strecke startet der Knopf unten diese Strecke`() {
        show { Home(route(selected = saved), offer.copy(index = 0)) }
        // Unten „Fahren" (aktiv) fuer die gewaehlte Strecke, in der Karte ihr eigener, getoenter Knopf.
        compose.onAllNodesWithText("Fahren").assertCountEquals(2)
        compose.onAllNodesWithText("Wähle oben eine Strecke.").assertCountEquals(0)
    }

    @Test
    fun `fehlt der Trainer bleibt unten nur der Hinweis dazu`() {
        show { Home(route(selected = null), offer, issue = IndoorTrainerIssue.NOT_PAIRED) }
        compose.onNodeWithText("Koppele zuerst deinen Trainer.").assertExists()
        compose.onAllNodesWithText("Wähle oben eine Strecke.").assertCountEquals(0)
        compose.onAllNodesWithText("Fahren").assertCountEquals(0)
    }
}
