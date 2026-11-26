package de.trailscape.app.ui.more

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.record.setzeAutoPauseAktiviert
import de.trailscape.app.record.setzeSprachansagenAktiviert
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.languageOf
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.app.ui.theme.WindowLayoutClass
import de.trailscape.app.ui.theme.windowLayoutClassFor
import de.trailscape.core.i18n.coreTexts
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verhaltens- und Layout-Tests fuer [MoreScreen] in `Compact` und `Expanded`
 * (Master-Detail, Auswahlzustand, `pendingMoreSection`, `onOpenIndoorSettings`
 * und Zurueck-Verhalten).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class MoreScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = compose.activity

    @Before
    fun setup() {
        runCatching {
            WorkManager.initialize(
                ApplicationProvider.getApplicationContext(),
                Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build(),
            )
        }
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun expandedDefaultsToProfileAndHighlightsSelection() {
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        setMoreContent(viewModel)

        val profileTitle = context.getString(R.string.more_page_profile_title)
        val backupTitle = context.getString(R.string.more_page_backup_title)

        // Linke Spalte markiert Profil; rechte Spalte zeigt Profil-Inhalt direkt.
        compose.onAllNodesWithText(profileTitle)[0].assertIsSelected()
        compose.onAllNodesWithText(backupTitle)[0].assertIsNotSelected()
        compose.onNodeWithText(context.getString(R.string.more_profile_ftp_label)).assertExists()

        // Wechsel auf Backup aktualisiert Auswahl links und Inhalt rechts.
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(backupTitle))
        settle()
        compose.onAllNodesWithText(backupTitle)[0].performClick()
        settle()

        compose.onAllNodesWithText(backupTitle)[0].assertIsSelected()
        compose.onAllNodesWithText(profileTitle)[0].assertIsNotSelected()
        compose.onNodeWithText(context.getString(R.string.more_backup_export_action)).assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun expandedHonorsPendingMoreSection() {
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        viewModel.requestMoreSection(MoreSection.HEALTH)

        setMoreContent(viewModel)

        val healthTitle = context.getString(R.string.more_page_health_title)
        val profileTitle = context.getString(R.string.more_page_profile_title)

        compose.onAllNodesWithText(healthTitle)[0].assertIsSelected()
        compose.onAllNodesWithText(profileTitle)[0].assertIsNotSelected()
        compose.onNodeWithText(context.getString(R.string.more_health_intro)).assertExists()
        assertNull(viewModel.pendingMoreSection.value)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun expandedBackExitsDirectlyWithoutTrapping() {
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        var backClicks = 0
        var parentSystemBacks = 0
        setMoreContent(
            viewModel = viewModel,
            onBack = { backClicks++ },
            parentSystemBack = { parentSystemBacks++ },
        )

        // Auch nach Wechsel auf Sync fuehren System-Zurueck und Kopfzeilen-Zurueck direkt hinaus.
        val syncTitle = context.getString(R.string.more_page_sync_title)
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(syncTitle))
        settle()
        compose.onAllNodesWithText(syncTitle)[0].performClick()
        settle()
        compose.onNodeWithText(context.getString(R.string.more_sync_action)).assertExists()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(1, parentSystemBacks)

        compose.onNodeWithText(context.getString(R.string.common_action_back)).performClick()
        settle()
        assertEquals(1, backClicks)
    }

    @Test
    fun compactListToDetailBackReturnsToList() {
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        var backClicks = 0
        var parentSystemBacks = 0
        setMoreContent(
            viewModel = viewModel,
            onBack = { backClicks++ },
            parentSystemBack = { parentSystemBacks++ },
        )

        val backupTitle = context.getString(R.string.more_page_backup_title)
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(backupTitle))
        settle()
        compose.onNodeWithText(backupTitle).performClick()
        settle()
        compose.onNodeWithText(context.getString(R.string.more_backup_export_action)).assertExists()

        // Erstes Zurueck kehrt zur Liste zurueck, ohne onBack auszuloesen.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(0, backClicks)
        assertEquals(0, parentSystemBacks)
        compose.onNodeWithText(context.getString(R.string.more_page_profile_title)).assertExists()

        // Zweites Zurueck auf der Liste reicht an das System weiter.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(1, parentSystemBacks)
    }

    @Test
    fun compactPendingMoreSectionBackCallsOnBack() {
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        viewModel.requestMoreSection(MoreSection.HEALTH)
        var backClicks = 0
        setMoreContent(viewModel = viewModel, onBack = { backClicks++ })

        compose.onNodeWithText(context.getString(R.string.more_health_intro)).assertExists()
        assertNull(viewModel.pendingMoreSection.value)

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(1, backClicks)
    }

    @Test
    fun compactIndoorRowDispatchesOnOpenIndoorSettings() {
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        var indoorClicks = 0
        setMoreContent(viewModel = viewModel, onOpenIndoorSettings = { indoorClicks++ })

        compose.onNodeWithText(context.getString(R.string.more_list_indoor_title)).performClick()
        settle()
        assertEquals(1, indoorClicks)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun expandedIndoorRowDispatchesOnOpenIndoorSettings() {
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        var indoorClicks = 0
        setMoreContent(viewModel = viewModel, onOpenIndoorSettings = { indoorClicks++ })

        compose.onNodeWithText(context.getString(R.string.more_list_indoor_title)).performClick()
        settle()
        assertEquals(1, indoorClicks)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun expandedToggleAutoPauseUpdatesLeftPaneStatusImmediately() {
        setzeAutoPauseAktiviert(context, true)
        setzeSprachansagenAktiviert(context, false)
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        setMoreContent(viewModel = viewModel)

        val recordingTitle = context.getString(R.string.more_page_recording_title)
        val on = context.getString(R.string.more_status_on)
        val off = context.getString(R.string.more_status_off)
        val statusBefore = context.getString(R.string.more_status_recording, on, off)
        val statusAfter = context.getString(R.string.more_status_recording, off, off)

        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(recordingTitle))
        settle()
        compose.onAllNodesWithText(recordingTitle)[0].performClick()
        settle()

        compose.onNodeWithText(statusBefore).assertExists()

        compose.onNodeWithText(context.getString(R.string.more_recording_auto_pause_title)).performClick()
        settle()

        compose.onNodeWithText(statusAfter).assertExists()
        compose.onNodeWithText(statusBefore).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun expandedToCompactWhenPageNullShowsList() {
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        var layoutClass by mutableStateOf(WindowLayoutClass.Expanded)
        compose.setContent {
            val config = LocalConfiguration.current
            val language = languageOf(config)
            AppServices.setAppLanguage(language)
            CompositionLocalProvider(
                LocalAppLanguage provides language,
                LocalCoreTexts provides coreTexts(language),
                LocalAppFormats provides AppFormats(language),
                LocalWindowLayoutClass provides layoutClass,
            ) {
                TrailscapeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MoreScreen(appViewModel = viewModel)
                    }
                }
            }
        }
        settle()

        compose.onNodeWithText(context.getString(R.string.more_profile_ftp_label)).assertExists()

        layoutClass = WindowLayoutClass.Compact
        settle()

        compose.onNodeWithText(context.getString(R.string.more_profile_ftp_label)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.more_page_profile_title)).assertExists()
    }

    private fun setMoreContent(
        viewModel: AppViewModel,
        onBack: () -> Unit = {},
        onOpenIndoorSettings: () -> Unit = {},
        parentSystemBack: (() -> Unit)? = null,
    ) {
        compose.setContent {
            val config = LocalConfiguration.current
            val language = languageOf(config)
            val layoutClass = windowLayoutClassFor(config.screenWidthDp)
            AppServices.setAppLanguage(language)
            if (parentSystemBack != null) {
                BackHandler(enabled = true) { parentSystemBack() }
            }
            CompositionLocalProvider(
                LocalAppLanguage provides language,
                LocalCoreTexts provides coreTexts(language),
                LocalAppFormats provides AppFormats(language),
                LocalWindowLayoutClass provides layoutClass,
            ) {
                TrailscapeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MoreScreen(
                            appViewModel = viewModel,
                            onBack = onBack,
                            onOpenIndoorSettings = onOpenIndoorSettings,
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }
}
