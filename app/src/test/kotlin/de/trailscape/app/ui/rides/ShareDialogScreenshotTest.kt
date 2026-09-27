package de.trailscape.app.ui.rides

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder des Teilen-Dialogs mit dem Schalter „Start und Ziel ausblenden":
 * an (gekuerzte Linie ohne Marken), nach dem Ausschalten (ganze Linie samt
 * Start und Ziel) und eine zu kurze Tour (nur Kennzahlen). Der Dialog ist ein
 * eigenes Fenster, daher `captureScreenRoboImage`. Die Sprache ist fest auf
 * Deutsch gestellt (`de` im Qualifier), damit die Bilder auch dann
 * unveraendert bleiben, wenn es spaeter uebersetzte Texte gibt. Laeuft nur mit
 * `-Pscreenshots`; die PNGs landen in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w411dp-h891dp-xxhdpi", application = ScreenshotApplication::class)
class ShareDialogScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    /** Ein Rundkurs von gut 12 km mit einem Anstieg; Start und Ziel am selben Ort. */
    private fun loop(): List<TrackPoint> {
        val n = 400
        return (0..n).map { k ->
            val a = 2 * PI * k / n
            // Etwas unrund, damit die Form nach einer echten Runde aussieht.
            val r = 0.016 + 0.004 * sin(3 * a)
            TrackPoint(
                lat = 51.05 + r * sin(a),
                lon = 13.74 + 1.6 * r * (1 - cos(a)),
                ele = 120.0 + 60.0 * sin(a).coerceAtLeast(0.0),
            )
        }
    }

    private fun ride(points: List<TrackPoint>, km: Double) = Ride(
        id = "r-share",
        name = "Feierabendrunde",
        createdAt = LocalDateTime.of(2026, 9, 22, 18, 12).toEpochSecond(ZoneOffset.UTC) * 1000,
        stats = RideStats(distanceKm = km, ascentM = 112.0, descentM = 112.0, durationS = 2940),
        points = points,
    )

    private fun show(ride: Ride, initialHideEnds: Boolean) {
        compose.setContent {
            TrailscapeTheme {
                var hideEnds by remember { mutableStateOf(initialHideEnds) }
                ShareRideDialog(
                    ride = ride,
                    load = null,
                    hideEnds = hideEnds,
                    onHideEndsChange = { hideEnds = it },
                    onDismiss = {},
                    onShareGpx = {},
                    onShareImage = {},
                )
            }
        }
    }

    /**
     * Inhalt und Vorschau entstehen auf `Dispatchers.Default` — warten, bis
     * beide stehen. Grosszuegig bemessen: Der erste Lauf in einer frischen
     * Robolectric-Umgebung laedt die Grafik-Natives und braucht auf der
     * CI-Maschine mehr als zehn Sekunden.
     */
    private fun awaitHintAndPreview(hint: String) {
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithContentDescription("Vorschau des Tour-Bilds")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @Test
    fun teilenStartZielAusgeblendet() {
        show(ride(loop(), km = 12.4), initialHideEnds = true)
        awaitHintAndPreview(HINT_HIDDEN)
        compose.onNodeWithText("Start und Ziel ausblenden").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/80-teilen-start-ziel-ausgeblendet.png")

        // Ausschalten: Die Vorschau zeigt wieder die ganze Runde samt Marken.
        compose.onNodeWithText("Start und Ziel ausblenden").performClick()
        awaitHintAndPreview(HINT_FULL)
        captureScreenRoboImage("build/outputs/roborazzi/81-teilen-start-ziel-sichtbar.png")
    }

    @Test
    fun teilenZuKurz() {
        val degPerM = 1.0 / 111_195.0
        val short = (0..10).map { TrackPoint(lat = 51.05 + it * 40 * degPerM, lon = 13.74, ele = 120.0 + it) }
        show(ride(short, km = 0.4), initialHideEnds = true)
        awaitHintAndPreview(HINT_TOO_SHORT)
        captureScreenRoboImage("build/outputs/roborazzi/82-teilen-zu-kurz.png")
    }

    private companion object {
        const val HINT_HIDDEN = "Das Bild zeigt die Form deiner Strecke ohne Karte und ohne Start und Ziel – " +
            "wer die Gegend kennt, erkennt die übrige Strecke trotzdem."
        const val HINT_FULL = "Das Bild zeigt die Form deiner Strecke ohne Karte – " +
            "wer die Gegend kennt, erkennt trotzdem Start und Ziel."
        const val HINT_TOO_SHORT = "Die Tour ist zu kurz, um Start und Ziel auszublenden – " +
            "das Bild zeigt deshalb nur die Kennzahlen."
    }
}
