package de.trailscape.app.ui.more

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ONBOARDING_STORAGE_KEY
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.map.LONG_PRESS_HINT_STORAGE_KEY
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.i18n.AppLanguage
import java.util.concurrent.Executors
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Erweiterte Screenshot-Matrix fuer die Einstellungen (`MoreScreen`) im
 * Atlas-Stil ueber alle geforderten Geraeteprofile, Schriftgroessen und
 * Sprachen:
 *  - 360 dp (`PHONE_NARROW_DE` / `PHONE_NARROW_EN`) + 1.3× Schrift
 *  - 412 dp (`S25_DE` / `S25_EN`) + 1.3× Schrift
 *  - Galaxy Tab S11 hochkant (`TAB_S11_PORTRAIT_DE` / `TAB_S11_PORTRAIT_EN`)
 *  - Galaxy Tab S11 quer Master-Detail (`TAB_S11_LANDSCAPE_DE` / `TAB_S11_LANDSCAPE_EN`) + 1.3× Schrift + Dunkelmodus
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class MoreTabletScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = compose.activity

    @Before
    fun seed() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
        AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
        AppServices.keyValueStore.setString(LONG_PRESS_HINT_STORAGE_KEY, "1")
        runCatching {
            WorkManager.initialize(
                ApplicationProvider.getApplicationContext(),
                Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build(),
            )
        }
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun phone360ListeDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        openSettings()
        compose.onAllNodesWithText(context.getString(R.string.more_screen_title))[0].assertExists()
        shot("70b-einstellungen-360-de")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun phone360ListeEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        openSettings()
        compose.onAllNodesWithText(context.getString(R.string.more_screen_title))[0].assertExists()
        shot("70b-einstellungen-360-en")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun phone360GrosseSchriftDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        openSettings(fontScale = 1.3f)
        shot("70c-einstellungen-360-gross-de")
        scrollAndClick(context.getString(R.string.more_page_backup_title))
        compose.onAllNodesWithText(context.getString(R.string.more_backup_export_action))[0].assertExists()
        shot("76c-backup-360-gross-de")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun phone360GrosseSchriftEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        openSettings(fontScale = 1.3f)
        shot("70c-einstellungen-360-gross-en")
    }

    @Test
    fun s25ListeDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        openSettings()
        shot("70-einstellungen-liste-de")
    }

    @Test
    fun s25GrosseSchriftDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        openSettings(fontScale = 1.3f)
        shot("70d-einstellungen-s25-gross-de")
        scrollAndClick(context.getString(R.string.more_page_backup_title))
        shot("76d-backup-s25-gross-de")
    }

    @Test
    @Config(qualifiers = TestLocales.S25_EN)
    fun s25GrosseSchriftEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        openSettings(fontScale = 1.3f)
        shot("70d-einstellungen-s25-gross-en")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun tabPortraitDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        openSettings()
        shot("70e-einstellungen-tab-hochkant-de")
        scrollAndClick(context.getString(R.string.more_page_backup_title))
        shot("76e-backup-tab-hochkant-de")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun tabPortraitEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        openSettings()
        shot("70e-einstellungen-tab-hochkant-en")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabLandscapeMasterDetailDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        openSettings()
        compose.onAllNodesWithText(context.getString(R.string.more_page_profile_title))[0].assertIsSelected()
        compose.onAllNodesWithText(context.getString(R.string.more_profile_ftp_label))[0].assertExists()
        shot("70f-einstellungen-tab-quer-de")

        scrollAndClick(context.getString(R.string.more_page_backup_title))
        compose.onAllNodesWithText(context.getString(R.string.more_page_backup_title))[0].assertIsSelected()
        compose.onAllNodesWithText(context.getString(R.string.more_backup_export_action))[0].assertExists()
        shot("76f-backup-tab-quer-de")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun tabLandscapeMasterDetailEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        openSettings()
        compose.onAllNodesWithText(context.getString(R.string.more_page_profile_title))[0].assertIsSelected()
        compose.onAllNodesWithText(context.getString(R.string.more_profile_ftp_label))[0].assertExists()
        shot("70f-einstellungen-tab-quer-en")

        scrollAndClick(context.getString(R.string.more_page_backup_title))
        compose.onAllNodesWithText(context.getString(R.string.more_page_backup_title))[0].assertIsSelected()
        compose.onAllNodesWithText(context.getString(R.string.more_backup_export_action))[0].assertExists()
        shot("76f-backup-tab-quer-en")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabLandscapeGrosseSchriftDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        openSettings(fontScale = 1.3f)
        scrollAndClick(context.getString(R.string.more_page_backup_title))
        shot("70g-einstellungen-tab-quer-gross-de")
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w1280dp-h800dp-night-xhdpi")
    fun tabLandscapeDunkelDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        openSettings(dark = true)
        scrollAndClick(context.getString(R.string.more_page_backup_title))
        shot("70h-einstellungen-tab-quer-dunkel-de")
    }

    private fun openSettings(fontScale: Float = 1f, dark: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalDensity provides Density(density.density, fontScale = fontScale),
            ) {
                TrailscapeTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        compose.onAllNodesWithContentDescription(context.getString(R.string.common_settings_cd))[0].performClick()
        settle()
    }

    private fun scrollAndClick(label: String) {
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(label))
        settle()
        compose.onAllNodesWithText(label)[0].performClick()
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
