package de.trailscape.app.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.AscentPreference
import de.trailscape.core.PlaceChoice
import de.trailscape.core.PlanSessionProgress
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.RidePlace
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TodayPlace
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingSession
import de.trailscape.core.applyPlaceChoice
import de.trailscape.core.i18n.SessionTextKey
import de.trailscape.core.indoor.DaySession
import de.trailscape.core.indoor.DaySessionKind
import de.trailscape.core.suggestTodayPlace
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
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
 * Sichtpruefung der Karte „Wo fährst du heute?" ([TodayPlaceCard]). Die
 * Vorschlaege kommen aus der echten Rechnung in `:core` ([suggestTodayPlace]),
 * Berlin am 25. November 2026 (Sonnenuntergang ≈ 16:02).
 *
 * Laeuft nur mit `-Pscreenshots`; die PNGs landen in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class TodayPlaceCardScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 11, 25)
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        val BERLIN = TrackPoint(lat = 52.5, lon = 13.4)
        val TARGET = RouteTarget(
            distanceKm = 38.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 1.5,
            speedKmh = 25.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Grundlage",
            source = RouteTargetSource.PLAN,
        )

        fun session(intensity: SessionIntensity, minutes: Int, key: SessionTextKey? = null) = TrainingSession(
            day = "Mi",
            title = "Einheit",
            description = "",
            targetKm = 38,
            intensity = intensity,
            durationMin = minutes,
            textKey = key,
        )

        fun place(s: TrainingSession, hour: Int, minute: Int = 0): Pair<TodayPlace, ZonedDateTime> {
            val now = DAY.atTime(hour, minute).atZone(ZONE)
            val today = TodayRoute(TARGET, s, s.targetKm, 1.0, false, note = null)
            return suggestTodayPlace(DaySession(DaySessionKind.PLANNED, s, s), today, now, BERLIN)!! to now
        }
    }

    @Before
    fun nurMitScreenshots() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    @Test
    fun draussenBeiTageslicht() {
        val (p, now) = place(session(SessionIntensity.GRUNDLAGE, 90), 10)
        assertEquals(RidePlace.OUTDOOR, p.place)
        var planned: RouteTarget? = null
        var flipped = false
        show(TodayPlaceUi.Suggest(p, now), onFlip = { flipped = true }, onPlan = { planned = it })
        compose.onNodeWithText("Heute draußen").assertExists()
        compose.onNodeWithText("Hell bis", substring = true).assertExists()
        shot("heute-ort-draussen")
        compose.onNodeWithText("Runde planen").performClick()
        assertEquals(TARGET, planned)
        compose.onNodeWithText("Lieber drinnen").performClick()
        assertEquals(true, flipped)
    }

    @Test
    fun drinnenIntervalle() {
        val (p, now) = place(session(SessionIntensity.HART, 60, SessionTextKey.INTERVALS), 10)
        assertEquals(RidePlace.INDOOR, p.place)
        show(TodayPlaceUi.Suggest(p, now))
        compose.onNodeWithText("Heute drinnen").assertExists()
        compose.onNodeWithText("Intervalle laufen", substring = true).assertExists()
        shot("heute-ort-drinnen-intervalle")
    }

    @Test
    fun drinnenWeilDunkel() {
        val (p, now) = place(session(SessionIntensity.GRUNDLAGE, 90), 15)
        assertEquals(RidePlace.INDOOR, p.place)
        show(TodayPlaceUi.Suggest(p, now))
        compose.onNodeWithText("Sonnenuntergang um", substring = true).assertExists()
        shot("heute-ort-drinnen-dunkel")
    }

    @Test
    fun umgedreht() {
        val (p, now) = place(session(SessionIntensity.HART, 60, SessionTextKey.INTERVALS), 10)
        val chosen = applyPlaceChoice(p, PlaceChoice(DAY, RidePlace.OUTDOOR), DAY)
        show(TodayPlaceUi.Suggest(chosen, now))
        compose.onNodeWithText("Heute draußen · deine Wahl").assertExists()
        compose.onNodeWithText("• Vorgeschlagen war drinnen.").assertExists()
        shot("heute-ort-umgedreht")
    }

    @Test
    fun erledigtDrinnen() {
        val s = session(SessionIntensity.HART, 60, SessionTextKey.INTERVALS)
        val progress = PlanSessionProgress(
            session = s,
            status = PlanSessionStatus.ERLEDIGT,
            rideId = "r",
            riddenKm = 21.0,
            riddenMinutes = 61,
            riddenIndoor = true,
        )
        show(TodayPlaceUi.Done(progress))
        compose.onNodeWithText("Einheit erledigt").assertExists()
        compose.onNodeWithText("Drinnen gefahren · 61 min").assertExists()
        shot("heute-ort-erledigt")
    }

    @Test
    @Config(qualifiers = "+night")
    fun drinnenDunkel() {
        val (p, now) = place(session(SessionIntensity.GRUNDLAGE, 60), 18)
        show(TodayPlaceUi.Suggest(p, now), dark = true)
        compose.onNodeWithText("Schon dunkel", substring = true).assertExists()
        shot("heute-ort-abend-dunkel")
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun draussenEnglisch() {
        val (p, now) = place(session(SessionIntensity.GRUNDLAGE, 150), 9)
        show(TodayPlaceUi.Suggest(p, now))
        compose.onNodeWithText(string(R.string.idee02_place_title_outdoor)).assertExists()
        compose.onNodeWithText("A long endurance ride", substring = true).assertExists()
        shot("heute-ort-draussen-en")
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun show(
        ui: TodayPlaceUi,
        dark: Boolean = false,
        onFlip: () -> Unit = {},
        onPlan: (RouteTarget) -> Unit = {},
    ) {
        compose.setContent {
            ProvideTestLanguage {
                TrailscapeTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            TodayPlaceCard(ui = ui, onFlip = onFlip, onPlanRoute = onPlan)
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
