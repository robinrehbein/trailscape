package de.trailscape.app.ui.erfolge

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.RideHighlightsBlock
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.AchievementId
import de.trailscape.core.AchievementInput
import de.trailscape.core.AchievementOverview
import de.trailscape.core.AchievementProgressData
import de.trailscape.core.AchievementSources
import de.trailscape.core.ExplorationAchievementFacts
import de.trailscape.core.PlanAchievementFacts
import de.trailscape.core.PlanSessionFact
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.PlanWeekFact
import de.trailscape.core.RecordGain
import de.trailscape.core.RecordKind
import de.trailscape.core.RideFact
import de.trailscape.core.RideHighlight
import de.trailscape.core.RoundAchievementFacts
import de.trailscape.core.WeekKind
import de.trailscape.core.YearGoal
import de.trailscape.core.achievementOverview
import de.trailscape.core.i18n.AppLanguage
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sichtpruefung der Sicht „Erfolge" ([ErfolgeContent], im Verlauf das dritte
 * Segment) und der Abzeichen-Zeile im Tourblatt. Den Weg dorthin (Verlauf →
 * Erfolge) nimmt `ScreenshotTest.tabs` (`02c-verlauf-erfolge`).
 * Der Stand kommt aus der echten Rechnung in `:core` ([achievementOverview])
 * ueber feste Beispieltouren — das Bild zeigt, was die Regeln ergeben.
 *
 * Laeuft nur mit `-Pscreenshots`; die PNGs landen in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class ErfolgeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        /** Mittwoch, 25. November 2026. */
        val TODAY: LocalDate = LocalDate.of(2026, 11, 25)
        val MONDAY: LocalDate = TODAY.with(DayOfWeek.MONDAY)

        /**
         * Gut drei Monate: zweimal je Woche, samstags laenger, eine Woche
         * Pause im Oktober, zuletzt eine 104-km-Tour — dazu zwei Fahrten
         * drinnen.
         */
        fun facts(): List<RideFact> {
            val list = ArrayList<RideFact>()
            for (week in 14 downTo 0) {
                if (week == 6) continue
                val monday = MONDAY.minusWeeks(week.toLong())
                list += RideFact("w$week-di", monday.plusDays(1), 32.0, 280.0, 80 * 60, 24.0)
                if (week > 0) {
                    val km = if (week == 1) 104.0 else 55.0 + week
                    list += RideFact("w$week-sa", monday.plusDays(5), km, 700.0, (km / 22 * 3600).toInt(), 22.0)
                }
            }
            list += RideFact("indoor-1", MONDAY.minusWeeks(3).plusDays(3), 28.0, 0.0, 60 * 60, 28.0)
            list += RideFact("indoor-2", MONDAY.minusWeeks(2).plusDays(3), 30.0, 0.0, 65 * 60, 28.0)
            return list.sortedBy { it.date }
        }

        fun plan(): PlanAchievementFacts {
            val monday = MONDAY.minusWeeks(1)
            return PlanAchievementFacts(
                listOf(
                    PlanWeekFact(
                        monday,
                        WeekKind.AUFBAU,
                        130,
                        listOf(
                            PlanSessionFact(monday.plusDays(1), PlanSessionStatus.ERLEDIGT, "w1-di"),
                            PlanSessionFact(monday.plusDays(5), PlanSessionStatus.ERLEDIGT, "w1-sa"),
                        ),
                    ),
                    PlanWeekFact(
                        MONDAY,
                        WeekKind.AUFBAU,
                        90,
                        listOf(
                            PlanSessionFact(MONDAY.plusDays(1), PlanSessionStatus.ERLEDIGT, "w0-di"),
                            PlanSessionFact(MONDAY.plusDays(3), PlanSessionStatus.OFFEN, null),
                            PlanSessionFact(MONDAY.plusDays(5), PlanSessionStatus.OFFEN, null),
                        ),
                    ),
                ),
            )
        }

        fun overview(
            facts: List<RideFact> = facts(),
            withPlan: Boolean = true,
            withSources: Boolean = true,
        ): AchievementOverview = achievementOverview(
            AchievementInput(
                facts = facts,
                today = TODAY,
                indoorRideIds = setOf("indoor-1", "indoor-2"),
                plan = if (withPlan) plan() else null,
                yearGoal = YearGoal(3000, LocalDate.of(2026, 1, 1)),
                progress = AchievementProgressData(
                    pauseWeeks = setOf(MONDAY.minusWeeks(6)),
                    pointsSince = MONDAY.minusWeeks(10),
                ),
                sources = if (withSources) {
                    AchievementSources(
                        rounds = RoundAchievementFacts(3),
                        exploration = ExplorationAchievementFacts(tileCount = 64, largestSquare = 3),
                        distinctWaysKm = 141.0,
                    )
                } else {
                    AchievementSources()
                },
            ),
        )
    }

    @Before
    fun nurMitScreenshots() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h3600dp-xhdpi")
    fun erfolge() {
        val o = overview()
        show(o, newIds = setOf(AchievementId.RIDE_100_KM, AchievementId.STREAK_12))
        compose.onNodeWithText(string(R.string.erfolge_badge_ride_100km_title)).assertExists()
        compose.onNodeWithText(string(R.string.erfolge_group_soon)).assertExists()
        // Die Pausenwoche im Oktober haelt die Serie: 14 Wochen mit Tour plus eine Pause.
        assertEquals(14, o.streak.weeks)
        assertEquals(true, o.states.first { it.id == AchievementId.STREAK_12 }.reached)
        shot("erfolge")
    }

    @Test
    @Config(qualifiers = "${TestLocales.EN}-w411dp-h3600dp-xhdpi")
    fun erfolgeEnglisch() {
        show(overview(), newIds = setOf(AchievementId.RIDE_100_KM), language = AppLanguage.EN)
        compose.onNodeWithText(string(R.string.erfolge_badge_ride_100km_title)).assertExists()
        assertEquals("Century", string(R.string.erfolge_badge_ride_100km_title))
        compose.onAllNodesWithText(string(R.string.erfolge_points_explain))[0].assertExists()
        shot("erfolge-en")
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h3600dp-night-xhdpi")
    fun erfolgeDunkel() {
        show(overview(), newIds = emptySet(), dark = true)
        shot("erfolge-dunkel")
    }

    /** Ohne Plan und ohne die teuren Quellen (Runden, Kacheln, Wege): „Mit einem Trainingsplan", „Wird berechnet". */
    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h3600dp-xhdpi")
    fun erfolgeOhnePlan() {
        show(overview(withPlan = false, withSources = false), newIds = emptySet(), hasPlan = false)
        compose.onAllNodesWithText(string(R.string.erfolge_progress_no_plan))[0].assertExists()
        compose.onAllNodesWithText(string(R.string.erfolge_progress_unknown))[0].assertExists()
        shot("erfolge-ohne-plan")
    }

    /** Der erste Tag: eine Tour, Stufe 1. */
    @Test
    fun erfolgeAnfang() {
        show(overview(facts = listOf(RideFact("erste", TODAY, 18.0, 90.0, 55 * 60, 19.6)), withPlan = false), emptySet(), hasPlan = false)
        compose.onNodeWithText(string(R.string.erfolge_badge_first_ride_title)).assertExists()
        shot("erfolge-anfang")
    }

    @Test
    fun pausenschalter() {
        var toggled: LocalDate? = null
        val pause = PauseControls(
            thisWeek = PauseOption(MONDAY, marked = false, canToggle = true),
            lastWeek = PauseOption(MONDAY.minusWeeks(1), marked = true, canToggle = true),
            onToggle = { toggled = it },
        )
        show(overview(), newIds = emptySet(), pause = pause)
        compose.onNodeWithText(string(R.string.erfolge_streak_pause_this_week)).performClick()
        assertEquals(MONDAY, toggled)
        shot("erfolge-pause")
    }

    @Test
    fun abzeichenZeile() {
        compose.setContent {
            Frame {
                RideHighlightsBlock(
                    listOf(
                        RideHighlight.Record(RecordGain(RecordKind.DISTANCE, 104.0, 63.0)),
                        RideHighlight.AchievementsUnlocked(listOf(AchievementId.RIDE_100_KM, AchievementId.STREAK_12)),
                        RideHighlight.StreakExtended(14),
                    ),
                )
            }
        }
        compose.onNodeWithText("Neue Abzeichen: Hunderter und 1 weiteres").assertExists()
        shot("erfolge-abzeichenzeile")
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun abzeichenZeileEnglisch() {
        compose.setContent {
            Frame(language = AppLanguage.EN) {
                RideHighlightsBlock(listOf(RideHighlight.AchievementsUnlocked(listOf(AchievementId.RIDE_100_KM))))
            }
        }
        compose.onNodeWithText("New badge: Century").assertExists()
        shot("erfolge-abzeichenzeile-en")
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun show(
        overview: AchievementOverview,
        newIds: Set<AchievementId>,
        hasPlan: Boolean = true,
        pause: PauseControls? = PauseControls(
            thisWeek = null,
            lastWeek = null,
            onToggle = {},
        ),
        dark: Boolean = false,
        language: AppLanguage = AppLanguage.DE,
    ) {
        compose.setContent {
            Frame(dark = dark, language = language, scroll = true) {
                ErfolgeContent(
                    overview = overview,
                    newIds = newIds,
                    hasPlan = hasPlan,
                    pause = pause,
                    pointsSince = MONDAY.minusWeeks(10),
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Frame(
        dark: Boolean = false,
        language: AppLanguage = AppLanguage.DE,
        scroll: Boolean = false,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        CompositionLocalProvider(
            LocalAppLanguage provides language,
            LocalAppFormats provides AppFormats(language),
        ) {
            TrailscapeTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { content() }
                }
            }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
