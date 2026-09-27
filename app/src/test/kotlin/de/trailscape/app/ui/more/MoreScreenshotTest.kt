package de.trailscape.app.ui.more

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.ONBOARDING_STORAGE_KEY
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.map.LONG_PRESS_HINT_STORAGE_KEY
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.i18n.AppLanguage
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Die Einstellungen auf Englisch (und zum Vergleich auf Deutsch): die Liste
 * und jede Seite auf einem hohen Bildschirm, damit auch das Ende der Seite
 * im Bild liegt. Werkzeug fuer die Sichtpruefung abgeschnittener
 * Beschriftungen nach dem Umzug in `strings_more.xml`.
 *
 * Eigene Klasse statt Schritten in `ui/ScreenshotTest`: Die gehoert allen
 * Bereichen, und jeder Zweig der Uebersetzung erweitert sie — hier bleiben
 * die Einstellungen unter sich. Laeuft nur mit `-Pscreenshots`.
 *
 * Jede englische Methode prueft mindestens einen sichtbaren englischen
 * Knoten, gelesen ueber `getString` (docs/i18n.md, E).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class MoreScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = compose.activity

    @Before
    fun seed() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
        AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
        AppServices.keyValueStore.setString(LONG_PRESS_HINT_STORAGE_KEY, "1")
        // Die Seite „Karten offline" beobachtet die Routing-Downloads ueber
        // WorkManager; die App startet ihn selbst (Initializer im Manifest
        // abgeschaltet), die ScreenshotApplication nicht.
        runCatching {
            WorkManager.initialize(
                ApplicationProvider.getApplicationContext(),
                Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build(),
            )
        }
    }

    /** Die Liste auf dem S25: Titel und Statuszeilen muessen je in eine Zeile passen. */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun listeEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        openSettings()
        assertEquals("Settings", context.getString(R.string.more_screen_title))
        compose.onAllNodesWithText(context.getString(R.string.more_page_recording_title))[0].assertExists()
        compose.onAllNodesWithText(context.getString(R.string.more_status_backup_never))[0].assertExists()
        compose.onAllNodesWithText(context.getString(R.string.more_status_offline_empty))[0].assertExists()
        shot("70-einstellungen-liste-en")
    }

    /** Jede Seite einmal ganz, auf Englisch. */
    @Test
    @Config(qualifiers = "+en-rGB-h2400dp")
    fun seitenEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        allPages(suffix = "en")
        // Stichproben: je Seite ein Knoten aus strings_more.xml, der nur im
        // Englischen so lautet.
        assertEquals("Import & backup", context.getString(R.string.more_page_backup_title))
    }

    /** Dieselben Seiten auf Deutsch — der Vergleich zeigt, dass sich dort nichts verschoben hat. */
    @Test
    @Config(qualifiers = "+h2400dp")
    fun seitenDeutsch() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        allPages(suffix = "de")
        compose.onAllNodesWithText("Einstellungen")[0].assertExists()
    }

    /**
     * „Karten offline", Abschnitt Routingdaten, einzeln: Der Abschnitt
     * Kartenbild fragt MapLibre nach seinen Regionen, und dessen native
     * Bibliothek gibt es unter Robolectric nicht — die ganze Seite laesst
     * sich hier deshalb nicht zeichnen.
     */
    @Test
    @Config(qualifiers = "+en-rGB-h1200dp")
    fun routingdatenEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        compose.setContent {
            val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsSection(label = stringResource(R.string.more_offline_section_routing)) {
                            OfflineRoutingCardContent(viewModel)
                        }
                    }
                }
            }
        }
        settle()
        compose.onAllNodesWithText(context.getString(R.string.more_offline_routing_wifi_title))[0].assertExists()
        compose.onAllNodesWithText(context.getString(R.string.more_offline_routing_empty))[0].assertExists()
        shot("75-offline-routing-en")
    }

    /** Der Update-Hinweis oben in der Liste — erscheint nur mit neuer Version, deshalb einzeln. */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun updateHinweisEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        // Ein Literal dazu: `getString` allein bestuende auch in stillem Deutsch.
        assertEquals("Download", context.getString(R.string.more_update_card_download_action))
        compose.setContent {
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        UpdateNoticeCard(versionName = "2.0.512", onDismiss = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText(context.getString(R.string.more_update_card_title, "2.0.512"))[0].assertExists()
        compose.onAllNodesWithText(context.getString(R.string.more_update_card_download_action))[0].assertExists()
        shot("79-einstellungen-update-en")
    }

    private fun allPages(suffix: String) {
        openSettings()
        page(R.string.more_page_profile_title, "71-profil-$suffix") {
            click(R.string.more_profile_advanced_action)
            compose.onAllNodesWithText(context.getString(R.string.more_profile_ftp_label))[0].assertExists()
        }
        page(R.string.more_page_health_title, "72-uhr-$suffix") {
            compose.onAllNodesWithText(context.getString(R.string.more_health_intro))[0].assertExists()
        }
        page(R.string.more_page_recording_title, "73-aufzeichnung-$suffix") {
            compose.onAllNodesWithText(context.getString(R.string.more_recording_off_route_title))[0].assertExists()
        }
        page(R.string.more_page_reminders_title, "74-erinnerungen-$suffix") {
            compose.onAllNodesWithText(context.getString(R.string.more_reminder_weekly_time_label))[0].assertExists()
        }
        page(R.string.more_page_backup_title, "76-backup-$suffix") {
            compose.onAllNodesWithText(context.getString(R.string.more_backup_import_archive_action))[0].assertExists()
        }
        page(R.string.more_page_sync_title, "77-sync-$suffix") {
            compose.onAllNodesWithText(context.getString(R.string.more_sync_action))[0].assertExists()
        }
        page(R.string.more_page_about_title, "78-ueber-$suffix") {
            click(R.string.more_about_notices_show_action)
            compose.onAllNodesWithText(context.getString(R.string.more_about_notices_data_title))[0].assertExists()
        }
    }

    private fun openSettings() {
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        compose.onAllNodesWithContentDescription(context.getString(R.string.common_settings_cd))[0].performClick()
        settle()
    }

    /** Oeffnet eine Seite ueber ihre Listenzeile, prueft und fotografiert sie und kehrt zurueck. */
    private fun page(title: Int, name: String, check: () -> Unit) {
        click(title)
        check()
        shot(name)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun click(text: Int) {
        compose.onAllNodesWithText(context.getString(text))[0].performClick()
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
