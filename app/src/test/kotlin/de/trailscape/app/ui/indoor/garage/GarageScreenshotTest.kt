package de.trailscape.app.ui.indoor.garage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.R
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.garage.GaragePalettes
import de.trailscape.core.garage.GarageProgress
import de.trailscape.core.garage.JerseyPattern
import de.trailscape.core.garage.RiderAvatar
import de.trailscape.core.i18n.AppLanguage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Bilder der Garage (docs/indoor/garage.md) mit festen Zustaenden. Laeuft wie
 * die anderen Screenshot-Tests nur mit `-Pscreenshots`; die Bilder liegen in
 * `app/build/outputs/roborazzi/garage-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "${TestLocales.DE}-w411dp-h1900dp-xxhdpi", application = ScreenshotApplication::class)
class GarageScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun str(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    @Test
    fun vorgabe() = shot("garage-01-vorgabe") {
        GarageScreen(avatar = RiderAvatar.DEFAULT, onChange = {}, onBack = {})
    }

    @Test
    fun verlaufBlauGelb() {
        val a = RiderAvatar(
            skin = 6, jersey = GaragePalettes.BLUE.srgb, jerseyAccent = GaragePalettes.YELLOW.srgb, pattern = JerseyPattern.FADE,
            bibs = GaragePalettes.NAVY.srgb, helmet = GaragePalettes.YELLOW.srgb, frame = GaragePalettes.BLACK.srgb,
            frameAccent = GaragePalettes.YELLOW.srgb, barTape = GaragePalettes.CORK.srgb, shoes = GaragePalettes.BLACK.srgb,
        )
        shot("garage-02-verlauf") { GarageScreen(avatar = a, onChange = {}, onBack = {}) }
        compose.onNodeWithContentDescription(str(R.string.garage_option_selected_cd, str(R.string.garage_color_blue)), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun antippenAendertDieVorschau() {
        var current = RiderAvatar.DEFAULT
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    var a by remember { mutableStateOf(RiderAvatar.DEFAULT) }
                    GarageScreen(avatar = a, onChange = { a = it; current = it }, onBack = {})
                }
            }
        }
        compose.onNodeWithText(str(R.string.garage_pattern_stripes)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(JerseyPattern.STRIPES, current.pattern)
        // Einfarbig blendet die Akzentfarbe aus.
        compose.onNodeWithText(str(R.string.garage_pattern_solid)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.garage_slot_jersey_accent)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.garage_reset_action)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(RiderAvatar.DEFAULT, current)
    }

    /** So saehe es mit den Erfolgen aus: Stufe 1 ohne Abzeichen, sicherer Grafikmodus an. */
    @Test
    fun gesperrtUndSichererModus() {
        val progress = object : GarageProgress {
            override val level = 1
            override fun hasBadge(badgeId: String) = false
        }
        shot("garage-03-gesperrt") {
            GarageScreen(avatar = RiderAvatar(pattern = JerseyPattern.SOLID), onChange = {}, onBack = {}, progress = progress, safeMode = true)
        }
        compose.onNodeWithText("${str(R.string.garage_pattern_split)} · ${str(R.string.garage_locked_level, 2)}").assertExists()
        compose.onNodeWithText(str(R.string.garage_safe_mode_hint)).assertExists()
    }

    @Test
    @Config(qualifiers = "+night")
    fun dunkel() = shot("garage-04-dunkel") {
        GarageScreen(avatar = RiderAvatar(jersey = GaragePalettes.BLACK.srgb, pattern = JerseyPattern.SPLIT, jerseyAccent = GaragePalettes.LIME.srgb), onChange = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun englisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("garage-05-en") { GarageScreen(avatar = RiderAvatar.DEFAULT, onChange = {}, onBack = {}) }
        compose.onNodeWithText(str(R.string.garage_slot_bar_tape)).performScrollTo().assertExists()
        assertEquals("Bar tape", str(R.string.garage_slot_bar_tape))
    }

    /** Alle Muster nebeneinander in der Vorschau (Grund Rot, Akzent Weiss). */
    @Test
    fun alleMuster() = shot("garage-06-muster") {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in JerseyPattern.entries) {
                Text(str(patternName(p)), style = MaterialTheme.typography.titleSmall)
                GaragePreview(
                    avatar = RiderAvatar(pattern = p, jersey = GaragePalettes.RED.srgb, jerseyAccent = GaragePalettes.WHITE.srgb),
                    description = str(patternName(p)),
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                )
            }
        }
    }
}
