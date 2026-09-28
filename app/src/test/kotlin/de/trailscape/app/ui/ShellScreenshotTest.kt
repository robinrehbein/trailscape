package de.trailscape.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.languageOf
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.components.CoachCard
import de.trailscape.app.ui.components.HoldToEndButton
import de.trailscape.app.ui.components.RecButtonState
import de.trailscape.app.ui.components.RecCapsuleButton
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.i18n.AppLanguage
import org.junit.Assert.assertEquals
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
 * Bilder der App-Huelle: die Erststart-Einfuehrung (alle fuenf Seiten), die
 * Navigationsleiste mit dem Fahren-Knopf und die geteilten Knoepfe aus
 * `ui/components` — auf Deutsch und auf Englisch (Dateiname „…-en").
 *
 * Die englischen Tests pruefen sichtbare Knoten ueber `getString(…)`, nicht
 * nur das Bild (docs/i18n.md, Abschnitt E). Laeuft wie alle Screenshot-Tests
 * nur mit `-Pscreenshots`; die PNGs liegen in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class ShellScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    /** Die fuenf Seiten der Einfuehrung auf Deutsch — Klicks auf die deutschen Texte. */
    @Test
    fun einfuehrung() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        startApp(onboardingDone = false)
        compose.onAllNodesWithText("Willkommen")[0].assertExists()
        shot("80-einfuehrung-1")
        for (page in 2..5) {
            compose.onAllNodesWithText("Weiter")[0].performClick()
            settle()
            shot("80-einfuehrung-$page")
        }
        compose.onAllNodesWithText("Schritt 4 von 4")[0].assertExists()
    }

    /** Dieselben Seiten auf Englisch, dazu der Eingabefehler der Profilseite. */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun einfuehrungEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        startApp(onboardingDone = false)
        val context = compose.activity
        assertEquals("Welcome", context.getString(R.string.shell_onboarding_welcome_eyebrow))
        compose.onAllNodesWithText(context.getString(R.string.shell_onboarding_welcome_eyebrow))[0].assertExists()
        compose.onAllNodesWithText(context.getString(R.string.shell_onboarding_skip_action))[0].assertExists()
        compose.onAllNodesWithText(context.getString(R.string.shell_onboarding_welcome_body_3))[0].assertExists()
        shot("80-einfuehrung-1-en")

        val next = context.getString(R.string.shell_onboarding_next_action)
        for (page in 2..5) {
            compose.onAllNodesWithText(next)[0].performClick()
            settle()
            shot("80-einfuehrung-$page-en")
            if (page == 3) {
                // Profilseite: ein unmoegliches Alter haelt die Seite fest und
                // zeigt den Fehler unter den Feldern.
                compose.onAllNodes(hasSetTextAction())[0]
                    .performTextInput("5")
                compose.onAllNodesWithText(next)[0].performClick()
                settle()
                compose.onAllNodesWithText(context.getString(R.string.shell_onboarding_age_error))[0].assertExists()
                shot("80-einfuehrung-3-fehler-en")
                compose.onAllNodes(hasSetTextAction())[0]
                    .performTextInput("0")
            }
        }
        compose.onAllNodesWithText("Step 4 of 4")[0].assertExists()
        // Haben andere Tests derselben Sandbox schon Touren gespeichert, traegt
        // die Seite den Titel fuer Wiederholer und steht auf „Later" — beide
        // Varianten sind englisch zu lesen.
        assertTrue(
            anyText(
                context.getString(R.string.shell_onboarding_first_round_title),
                context.getString(R.string.shell_onboarding_first_round_title_history),
            ),
        )
        assertTrue(
            anyText(
                context.getString(R.string.shell_onboarding_build_loop_action),
                context.getString(R.string.shell_onboarding_finish_action),
            ),
        )
    }

    private fun anyText(vararg texts: String): Boolean =
        texts.any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }

    /** Die Huelle nach der Einfuehrung: Tabs und Fahren-Knopf auf Englisch. */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun huelleEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        startApp(onboardingDone = true)
        val context = compose.activity
        listOf(
            R.string.shell_nav_today_label to "Today",
            R.string.shell_nav_map_label to "Map",
            R.string.shell_nav_history_label to "History",
            R.string.shell_nav_training_label to "Training",
        ).forEach { (id, expected) ->
            assertEquals(expected, context.getString(id))
            compose.onAllNodesWithText(expected)[0].assertExists()
        }
        // Der Fahren-Knopf ersetzt seine Semantik durch die Beschreibung;
        // das sichtbare „Ride" steht deshalb nur im Bild.
        assertEquals("Ride", context.getString(R.string.shell_rec_idle_label))
        compose.onAllNodesWithContentDescription(context.getString(R.string.shell_rec_start_cd))[0].assertExists()
        shot("81-huelle-en")
    }

    /** Die geteilten Knoepfe in allen Zustaenden — Deutsch. */
    @Test
    fun knoepfe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        knoepfeSetzen()
        compose.onAllNodesWithText("Halten zum Beenden")[0].assertExists()
        shot("82-knoepfe")
    }

    /** Die geteilten Knoepfe in allen Zustaenden — Englisch. */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun knoepfeEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        knoepfeSetzen()
        val context = compose.activity
        compose.onAllNodesWithText(context.getString(R.string.shell_hold_to_end_label))[0].assertExists()
        compose.onAllNodesWithContentDescription(context.getString(R.string.shell_rec_route_cd))[0].assertExists()
        compose.onAllNodesWithContentDescription(context.getString(R.string.shell_rec_paused_cd))[0].assertExists()
        shot("82-knoepfe-en")
    }

    private fun knoepfeSetzen() {
        compose.setContent {
            // Ausserhalb von TrailscapeApp stellt niemand die Sprache bereit —
            // hier dieselbe Regel wie dort, aus der Konfiguration der Activity.
            CompositionLocalProvider(LocalAppLanguage provides languageOf(LocalConfiguration.current)) {
                TrailscapeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            HoldToEndButton(onEnd = {}, modifier = Modifier.fillMaxWidth())
                            RecCapsuleButton(state = RecButtonState.Idle, onClick = {})
                            RecCapsuleButton(state = RecButtonState.RouteReady(12.34), onClick = {})
                            RecCapsuleButton(state = RecButtonState.Recording(754_000L, paused = true), onClick = {})
                            CoachCard { }
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun startApp(onboardingDone: Boolean) {
        // AppServices lebt ueber die Tests einer Klasse hinweg — den Schalter
        // deshalb in beide Richtungen ausdruecklich setzen.
        if (onboardingDone) {
            AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
        } else {
            AppServices.keyValueStore.remove(ONBOARDING_STORAGE_KEY)
        }
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        if (!onboardingDone) {
            // Ob die Einfuehrung kommt, liest das ViewModel asynchron von der Platte.
            val skip = compose.activity.getString(R.string.shell_onboarding_skip_action)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
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
