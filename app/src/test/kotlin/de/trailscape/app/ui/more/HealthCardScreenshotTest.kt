package de.trailscape.app.ui.more

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.runtime.CompositionLocalProvider
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.HealthSyncReport
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.RouteConsentRequest
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import java.time.LocalDateTime
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sichtpruefung der zustandslosen Teile der Health-Karte: Importbericht mit
 * Touren ohne Route und der Hinweis zur Historien-Freigabe.
 *
 * Eigene Klasse statt eines Schritts in `ScreenshotTest`: Die ganze
 * Health-Karte haengt am `AppViewModel` und an einem echten Health Connect,
 * das Robolectric nicht hat — dort stuende nur „Health Connect nicht
 * installiert". Die beiden Bausteine hier sind reine Funktionen ihrer
 * Parameter und lassen sich mit Beispieldaten zeigen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class HealthCardScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun nurMitScreenshots() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    @Test
    fun historieUndBericht() {
        show(dark = false)
        compose.onNodeWithText("12 Touren importiert · 3 ohne Route (Freigabe in Health Connect nötig)").assertExists()
        compose.onNodeWithText("Ältere Fahrten freigeben").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/61-health-historie.png")
    }

    /** Dieselben Bausteine auf Englisch — Plurals und die laengeren englischen Saetze. */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun historieUndBerichtEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        show(dark = false, coreTexts = CoreTextsEn)
        val context = compose.activity
        compose.onNodeWithText(context.getString(R.string.more_health_history_action)).assertExists()
        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.more_health_summary_found_count, 14, 14, 2),
        ).assertExists()
        compose.onNodeWithText("14 rides found · 2 already imported").assertExists()
        compose.onNodeWithText("12 rides imported", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/61-health-historie-en.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun historieUndBerichtDunkel() {
        show(dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/62-health-historie-dunkel.png")
    }

    /**
     * Ohne `TrailscapeApp` stellt niemand die `:core`-Texte bereit (Vorgabe:
     * Deutsch) — die englische Methode reicht sie deshalb selbst herein.
     */
    private fun show(dark: Boolean, coreTexts: CoreTexts = CoreTextsDe) {
        compose.setContent {
            CompositionLocalProvider(LocalCoreTexts provides coreTexts) {
                TrailscapeTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            HealthSyncSummary(sampleReport())
                            Spacer(modifier = Modifier.height(12.dp))
                            HealthHistoryNotice(enabled = true, onRequest = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}

/** 14 Radfahrten gefunden, 12 importiert, davon 3 mit gesperrter Route; 2 schon vorhanden. */
private fun sampleReport(): HealthSyncReport {
    val start = LocalDateTime.of(2026, 9, 1, 10, 0)
    val rides = (0 until 12).map { i ->
        Ride(
            id = "health-$i",
            name = "Radfahrt $i",
            createdAt = 0L,
            points = emptyList(),
            stats = RideStats(distanceKm = 30.0, durationS = 3600, ascentM = 200.0, descentM = 200.0),
        )
    }
    return HealthSyncReport(
        from = start.minusDays(365),
        to = start,
        workoutsFound = 14,
        imported = rides,
        mergedRides = emptyList(),
        duplicatesSkipped = 2,
        routesMissing = 3,
        routeConsentPending = (0 until 3).map { i ->
            RouteConsentRequest(
                sessionId = "s$i",
                rideId = "health-$i",
                start = start,
                end = start.plusHours(1),
                source = "com.sec.android.app.shealth",
            )
        },
        historyImport = true,
    )
}
