package de.trailscape.app.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.DayForecast
import de.trailscape.core.PlannedRide
import de.trailscape.core.RidingHabits
import de.trailscape.core.WeekPlan
import de.trailscape.core.WeekPlanInput
import de.trailscape.core.WeekPlanTightCause
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import de.trailscape.core.planWeek
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sichtpruefung der Karte „Wochen-Fahrplan" ([WeekPlanCard]): mit Wetter,
 * ohne Wetter, knapp, ohne Tage, dunkel und Englisch. Die Plaene kommen aus
 * der echten Rechnung in `:core` ([planWeek]) — das Bild zeigt also, was die
 * Regeln fuer diese Woche wirklich vorschlagen.
 *
 * Laeuft nur mit `-Pscreenshots`; die PNGs landen in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class WeekPlanCardScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        /** Mittwoch, 18. November 2026, 9 Uhr. */
        val TODAY: LocalDate = LocalDate.of(2026, 11, 18)
        val HABITS = RidingHabits(
            weekdayShare = mapOf(DayOfWeek.THURSDAY to 0.6, DayOfWeek.SATURDAY to 0.9),
            typicalRideKm = 35.0,
            longestRideKm = 55.0,
            ridesInWindow = 12,
        )

        fun forecast(): List<DayForecast> = listOf(
            DayForecast(TODAY, 20, 0.3, 18.0, 2.0, 9.0),
            DayForecast(TODAY.plusDays(1), 80, 6.5, 24.0, 4.0, 8.0),
            DayForecast(TODAY.plusDays(2), 10, 0.0, 11.0, 3.0, 12.0),
            DayForecast(TODAY.plusDays(3), 85, 7.0, 35.0, 5.0, 9.0),
            DayForecast(TODAY.plusDays(4), 5, 0.0, 14.0, 4.0, 14.0),
        )

        fun plan(remainingKm: Double, withForecast: Boolean, hour: Int = 9, day: LocalDate = TODAY): WeekPlan =
            planWeek(
                WeekPlanInput(
                    remainingKm = remainingKm,
                    now = day.atTime(hour, 0),
                    habits = HABITS,
                    forecast = if (withForecast) forecast() else emptyList(),
                ),
            )!!
    }

    @Before
    fun nurMitScreenshots() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    @Test
    fun mitWetter() {
        val plan = plan(70.0, withForecast = true)
        show(plan, WeekPlanWeatherUi.VORHANDEN)
        // Der Regen am Donnerstag schiebt die Fahrt vom ueblichen Tag weg.
        assertEquals(false, plan.rides.any { it.date == TODAY.plusDays(1) })
        compose.onNodeWithText("Wochen-Fahrplan").assertExists()
        compose.onNodeWithText("Wetter: Open-Meteo").assertExists()
        compose.onNodeWithText("trocken, 12 °C").assertExists()
        shot("heute-wochenplan-wetter")
    }

    @Test
    fun ohneWetter() {
        var planned: PlannedRide? = null
        var switched: Boolean? = null
        val plan = plan(70.0, withForecast = false)
        show(plan, WeekPlanWeatherUi.AUS, onPlanRide = { planned = it }, onSwitch = { switched = it })
        compose.onNodeWithText("Ohne Wetter geplant.").assertExists()
        assertEquals(2, compose.onAllNodesWithText("dein üblicher Fahrtag").fetchSemanticsNodes().size)
        shot("heute-wochenplan-ohne-wetter")
        val first = plan.rides.first()
        compose.onNodeWithContentDescription("Runde über ${first.km} km für Donnerstag planen").performClick()
        assertEquals(first, planned)
        compose.onNodeWithText("Wetter für den Wochenplan").performClick()
        assertEquals(true, switched)
    }

    @Test
    fun knapp() {
        val plan = plan(240.0, withForecast = false, day = TODAY.plusDays(3))
        show(plan, WeekPlanWeatherUi.NICHT_ERREICHBAR, today = TODAY.plusDays(3))
        assertEquals(WeekPlanTightCause.ZU_WENIGE_TAGE, plan.tightCause)
        compose.onNodeWithText("zu wenige Tage", substring = true).assertExists()
        compose.onNodeWithText("nicht erreichbar", substring = true).assertExists()
        shot("heute-wochenplan-knapp")
    }

    @Test
    fun knappObergrenze() {
        // Mittwoch bis Sonntag frei, aber selbst fuenf Fahrten bis zur Obergrenze reichen nicht.
        val plan = plan(450.0, withForecast = false)
        show(plan, WeekPlanWeatherUi.AUS)
        assertEquals(WeekPlanTightCause.OBERGRENZE, plan.tightCause)
        assertEquals(5, plan.rides.size)
        compose.onNodeWithText("Selbst mit 5 Touren", substring = true).assertExists()
        shot("heute-wochenplan-knapp-obergrenze")
    }

    @Test
    fun keineTageMehr() {
        val plan = plan(25.0, withForecast = false, hour = 20, day = TODAY.plusDays(4))
        show(plan, WeekPlanWeatherUi.AUS, today = TODAY.plusDays(4))
        compose.onNodeWithText("kein Fahrtag mehr", substring = true).assertExists()
        // Ausschalten geht auch ohne Fahrtage.
        compose.onNodeWithText("Wetter für den Wochenplan").assertExists()
        shot("heute-wochenplan-keine-tage")
    }

    @Test
    @Config(qualifiers = "+night")
    fun mitWetterDunkel() {
        show(plan(70.0, withForecast = true), WeekPlanWeatherUi.VORHANDEN, dark = true)
        shot("heute-wochenplan-wetter-dunkel")
    }

    @Test
    @Config(qualifiers = "+night")
    fun knappDunkel() {
        show(plan(240.0, withForecast = false, day = TODAY.plusDays(3)), WeekPlanWeatherUi.AUS, dark = true, today = TODAY.plusDays(3))
        shot("heute-wochenplan-knapp-dunkel")
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun mitWetterEnglisch() {
        show(plan(70.0, withForecast = true), WeekPlanWeatherUi.VORHANDEN, texts = CoreTextsEn)
        compose.onNodeWithText(string(R.string.today_week_plan_title)).assertExists()
        compose.onNodeWithText(string(R.string.today_week_plan_weather_switch)).assertExists()
        compose.onNodeWithText("Fri · ", substring = true).assertExists()
        shot("heute-wochenplan-wetter-en")
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun ohneWetterEnglisch() {
        show(plan(70.0, withForecast = false), WeekPlanWeatherUi.KEIN_ORT, texts = CoreTextsEn)
        compose.onNodeWithText(string(R.string.today_week_plan_weather_no_location)).assertExists()
        shot("heute-wochenplan-ohne-wetter-en")
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun knappEnglisch() {
        show(plan(240.0, withForecast = false, day = TODAY.plusDays(3)), WeekPlanWeatherUi.AUS, today = TODAY.plusDays(3), texts = CoreTextsEn)
        compose.onNodeWithText("Tight", substring = true).assertExists()
        shot("heute-wochenplan-knapp-en")
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun show(
        plan: WeekPlan,
        weather: WeekPlanWeatherUi,
        dark: Boolean = false,
        today: LocalDate = TODAY,
        texts: CoreTexts = CoreTextsDe,
        onPlanRide: (PlannedRide) -> Unit = {},
        onSwitch: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalCoreTexts provides texts) {
                TrailscapeTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            WeekPlanCard(
                                plan = plan,
                                today = today,
                                weather = weather,
                                onWeatherEnabledChange = onSwitch,
                                onPlanRide = onPlanRide,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
