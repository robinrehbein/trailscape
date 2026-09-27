package de.trailscape.app.ui.training

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ONBOARDING_STORAGE_KEY
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.Goal
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import de.trailscape.core.assessFitness
import de.trailscape.core.generatePlan
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import de.trailscape.core.savePlan
import kotlin.math.cos
import kotlin.math.sin
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
 * Der Trainings-Tab auf Englisch — Tab, Blaetter und Formular.
 *
 * Eigene Klasse statt weiterer Methoden in `ui/ScreenshotTest.kt`: Dort
 * ergaenzen alle Uebersetzungs-Zweige ihre Bilder, und der Trainings-Tab
 * braucht mehrere (Tab lang, Formblatt mit allen Werten, Prognoseblatt,
 * Zielformular, Koerperwerte, grosse Schrift). Die deutschen Gegenstuecke von
 * Tab und Hinweisen macht weiter `ScreenshotTest` (`03-training`,
 * `13-training-lang`, `16-training-hinweise`); die Blaetter zeigt diese Klasse
 * zusaetzlich auf Deutsch, damit man beide Sprachen nebeneinanderlegen kann.
 *
 * Jeder englische Test prueft neben dem Bild mindestens einen sichtbaren
 * Knoten, gelesen ueber `getString` — das Bild allein beweist nichts.
 *
 * Laeuft nur auf Wunsch:
 * `./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*TrainingScreenshotTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class TrainingScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun seed() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
        AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
    }

    /** Der ganze Tab auf einem hohen Bildschirm: Ziel, Woche, Form, Koerperwerte, Profil-Hinweis. */
    @Test
    @Config(qualifiers = "${TestLocales.EN}-w411dp-h2400dp-xxhdpi")
    fun trainingEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        savePlan(goal(distanceKm = 60.0), CoreTextsEn)
        start()
        openTraining()
        assertVisible(R.string.training_goal_explain_action)
        assertVisible(R.string.training_screen_all_weeks_action)
        assertEquals("How is this calculated?", string(R.string.training_goal_explain_action))
        shot("training-lang-en")

        click(R.string.training_screen_all_weeks_action)
        shot("training-alle-wochen-en")
    }

    /** Tragfaehigkeit als eine Warnkarte, Profil als ruhige Zeile, Anpassungs-Notiz. */
    @Test
    @Config(qualifiers = "${TestLocales.EN}-w411dp-h2400dp-xxhdpi")
    fun trainingHinweiseEnglisch() {
        savePlan(goal(distanceKm = 120.0), CoreTextsEn, startedWeeksAgo = 3)
        start()
        openTraining()
        assertVisible(R.string.training_plan_feasibility_title)
        assertVisible(R.string.training_notice_profile_action)
        shot("training-hinweise-en")
    }

    /** Ohne Touren und ohne Ziel: Leerzustand und „Set a goal". */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun trainingLeerEnglisch() {
        // `AppServices` lebt ueber die Tests einer Klasse hinweg — Touren und
        // Plan eines vorigen Tests ausdruecklich entfernen.
        AppServices.rideStorage.listSummaries().summaries.forEach { AppServices.rideStorage.deleteRide(it.id) }
        savePlan(AppServices.trainingPlanStore, null)
        start()
        openTraining()
        assertVisible(R.string.training_screen_empty_title)
        shot("training-leer-en")
    }

    @Test
    @Config(qualifiers = "${TestLocales.EN}-w411dp-h2400dp-xxhdpi")
    fun formblattEnglisch() {
        savePlan(goal(distanceKm = 60.0), CoreTextsEn)
        start()
        openTraining()
        formSheet()
        assertVisible(R.string.training_form_jargon)
        shot("training-form-en")
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h2400dp-xxhdpi")
    fun formblatt() {
        savePlan(goal(distanceKm = 60.0), CoreTextsDe)
        start()
        openTraining()
        formSheet()
        shot("training-form")
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun prognoseEnglisch() {
        savePlan(goal(distanceKm = 60.0), CoreTextsEn)
        start()
        openTraining()
        click(R.string.training_goal_explain_action)
        assertVisible(R.string.training_prognosis_title)
        shot("training-prognose-en")
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun zielformularEnglisch() {
        savePlan(goal(distanceKm = 60.0), CoreTextsEn)
        start()
        openTraining()
        click(R.string.training_goal_edit_action)
        assertVisible(R.string.training_goal_editor_ascent_label)
        shot("training-ziel-en")
    }

    @Test
    fun zielformular() {
        savePlan(goal(distanceKm = 60.0), CoreTextsDe)
        start()
        openTraining()
        click(R.string.training_goal_edit_action)
        shot("training-ziel")
    }

    @Test
    @Config(qualifiers = "${TestLocales.EN}-w411dp-h2400dp-xxhdpi")
    fun koerperwerteEnglisch() {
        AppServices.rideStorage.saveRides(sampleRides())
        start()
        openTraining()
        val tile = compose.onAllNodesWithText(string(R.string.training_vitals_resting_hr_label))[0]
        tile.performScrollTo()
        settle()
        shot("training-koerperwerte-kacheln-en")
        tile.performClick()
        settle()
        assertVisible(R.string.training_vitals_sheet_title)
        shot("training-koerperwerte-en")
    }

    /** Grosse Systemschrift (130 %) auf Englisch — findet abgeschnittene Beschriftungen. */
    @Test
    @Config(qualifiers = "${TestLocales.EN}-w411dp-h2400dp-xxhdpi")
    fun grosseSchriftEnglisch() {
        RuntimeEnvironment.setFontScale(1.3f)
        savePlan(goal(distanceKm = 60.0), CoreTextsEn)
        start()
        openTraining()
        assertVisible(R.string.training_goal_current_label)
        shot("training-gross-en")
    }

    private fun goal(distanceKm: Double) = Goal(
        name = "Alb-Gold",
        distanceKm = distanceKm,
        ascentM = 700.0,
        date = NOW + 8 * WEEK_MS,
        targetDurationMin = 130,
    )

    /** Beispieltouren und ein Plan zum Ziel, in der Sprache des Tests erzeugt. */
    private fun savePlan(goal: Goal, texts: CoreTexts, startedWeeksAgo: Int = 0) {
        AppServices.rideStorage.saveRides(sampleRides())
        savePlan(
            AppServices.trainingPlanStore,
            generatePlan(goal, assessFitness(sampleRides()), now = NOW - startedWeeksAgo * WEEK_MS, texts = texts),
        )
    }

    private fun formSheet() {
        // Die ganze Formkarte ist der Knopf; gefunden ueber ihre Klick-Beschriftung.
        val label = string(R.string.training_form_open_cd)
        compose.onAllNodes(
            SemanticsMatcher("onClickLabel = $label") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == label
            },
        )[0].performScrollTo().performClick()
        settle()
        click(R.string.training_form_all_values_action)
    }

    private fun openTraining() {
        compose.onAllNodesWithText(string(R.string.training_screen_title))[0].performClick()
        settle()
    }

    private fun click(id: Int) {
        compose.onAllNodesWithText(string(id))[0].performScrollTo().performClick()
        settle()
    }

    private fun assertVisible(id: Int) {
        compose.onAllNodesWithText(string(id), substring = true)[0].assertExists()
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun start() {
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val WEEK_MS = 7 * DAY_MS

/** Relativ zur echten Uhr, wie in `ScreenshotTest` (dort steht, warum). */
private val NOW = System.currentTimeMillis()

/** Sechs gefahrene Touren der letzten Wochen mit Puls und Hoehenprofil. */
private fun sampleRides(): List<Ride> {
    val specs = listOf(
        Triple("Feierabendrunde", 2, 32.4),
        Triple("GA1 Kanalrunde", 4, 46.1),
        Triple("Schotterschleife Nord", 12, 61.0),
        Triple("Waldkante West", 16, 28.7),
        Triple("Intervalle am Deich", 28, 38.9),
        Triple("Sonntagsrunde", 32, 55.2),
    )
    return specs.mapIndexed { index, (name, daysAgo, km) ->
        val start = NOW - daysAgo * DAY_MS
        val durationS = (km / 23.0 * 3600).toInt()
        val points = List(120) { i ->
            val t = i / 119.0 * 2 * Math.PI
            TrackPoint(
                lat = 48.40 + 0.03 * sin(t) * (1 + index * 0.1),
                lon = 9.05 + 0.05 * cos(t) + 0.01 * sin(3 * t),
                ele = 500.0 + 40 * sin(2 * t),
                time = start + (durationS * 1000L * i / 119),
                hr = 128 + (10 * sin(t)).toInt(),
            )
        }
        Ride(
            id = "sample-$index",
            name = name,
            createdAt = start,
            stats = RideStats(
                distanceKm = km,
                ascentM = km * 9,
                descentM = km * 9,
                durationS = durationS,
                movingTimeS = durationS,
                avgSpeedKmh = 23.0,
                avgHrBpm = 132,
                maxHrBpm = 168,
            ),
            points = points,
        )
    }
}
