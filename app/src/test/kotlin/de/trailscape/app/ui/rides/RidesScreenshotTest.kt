package de.trailscape.app.ui.rides

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.ONBOARDING_STORAGE_KEY
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.map.LONG_PRESS_HINT_STORAGE_KEY
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import de.trailscape.core.i18n.AppLanguage
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Der Verlauf auf Englisch — Liste, Import-Menue, Tourdetail mit „All values",
 * Teilen-Dialog und das geteilte Tour-Bild selbst.
 *
 * Eigene Klasse statt Methoden in `ui/ScreenshotTest.kt`: Die teilen sich
 * sechs Uebersetzungszweige, und jede Methode dort waere ein Merge-Konflikt.
 * Der Tab wird ueber [AppViewModel.requestTab] gewechselt, nicht per Klick auf
 * die Beschriftung der Navigationskapsel — die zieht ein anderer Zweig um,
 * ein Klick auf „History" braeche vor dessen Merge, einer auf „Verlauf" danach.
 * Angetippt werden nur Tournamen (Daten) und Texte aus `strings_rides.xml`,
 * gelesen ueber `getString`.
 *
 * Laeuft nur mit `-Pscreenshots`; die PNGs landen in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_EN, application = ScreenshotApplication::class)
class RidesScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()

    /** Touren, die vor diesem Test im Speicher lagen — nach dem Test zurueck. */
    private var saved: List<Ride> = emptyList()

    /**
     * Nur die eigenen Beispieltouren im Verlauf: [AppServices] lebt ueber die
     * Testklasse hinaus, und Touren anderer Screenshot-Klassen (etwa aus
     * `ScreenshotTest`) doppelten sonst die Liste und verfaelschten Wochenziel
     * und Belastung — die Bilder hingen von der Testreihenfolge ab. Deshalb
     * erst sichern und leeren, dann die eigenen Touren speichern.
     */
    @Before
    fun seed() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
        AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
        AppServices.keyValueStore.setString(LONG_PRESS_HINT_STORAGE_KEY, "1")
        val storage = AppServices.rideStorage
        val ids = storage.listSummaries().summaries.map { it.id }
        saved = ids.mapNotNull { storage.loadRide(it) }
        ids.forEach { storage.deleteRide(it) }
        storage.saveRides(sampleRides())
    }

    /**
     * Den Speicher wiederherstellen: eigene Touren weg (auch fuer
     * `ScreenshotTest.ohneGefahreneTourKeinKartenknopf`, das einen Verlauf
     * ohne fremde gefahrene Touren erwartet), gesicherte zurueck.
     */
    @After
    fun cleanUp() {
        if (System.getProperty("trailscape.screenshots") != "true") return
        val storage = AppServices.rideStorage
        storage.listSummaries().summaries.forEach { storage.deleteRide(it.id) }
        if (saved.isNotEmpty()) storage.saveRides(saved)
    }

    /** Liste mit Abschnitt „Planned", Monatsgruppen und Haerte-Pillen; danach das Import-Menue. */
    @Test
    fun verlaufEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        startInHistory()
        assertEquals("All rides on the map", string(R.string.rides_screen_show_map_action))
        compose.onAllNodesWithText(string(R.string.rides_screen_show_map_action)).onFirst().assertExists()
        compose.onAllNodesWithText(string(R.string.rides_list_planned_pill)).onFirst().assertExists()
        shot("70-verlauf-en")

        compose.onAllNodesWithContentDescription(string(R.string.rides_screen_import_cd))[0].performClick()
        settle()
        compose.onAllNodesWithText(string(R.string.rides_import_menu_archive_action)).onFirst().assertExists()
        shot("71-verlauf-import-en")
    }

    /** Tourdetail auf hohem Bildschirm, „All values" aufgeklappt — alle Abschnitte auf einmal. */
    @Test
    @Config(qualifiers = "${TestLocales.EN}-w411dp-h2400dp-xxhdpi")
    fun tourEnglisch() {
        startInHistory()
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        settle()
        compose.onAllNodesWithText(string(R.string.rides_detail_ride_again_action)).onFirst().assertExists()
        compose.onAllNodesWithText(string(R.string.rides_detail_all_values_action))[0].performClick()
        settle()
        compose.onAllNodesWithText(string(R.string.rides_detail_moving_time_label)).onFirst().assertExists()
        shot("72-tour-lang-en")
    }

    /** Tourdetail auf dem normalen Bildschirm: Kopf, Zahlenzeile, Satz, Kachelreihe. */
    @Test
    fun tourKopfEnglisch() {
        startInHistory()
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        settle()
        compose.onAllNodesWithText(string(R.string.rides_detail_show_map_action)).onFirst().assertExists()
        shot("73-tour-en")
    }

    /** Eine gespeicherte Planung: Hinweis statt Klartext-Satz. */
    @Test
    fun planungEnglisch() {
        startInHistory()
        compose.onAllNodesWithText("Alb-Runde über Hayingen")[0].performClick()
        settle()
        compose.onAllNodesWithText(string(R.string.rides_detail_planned_notice)).onFirst().assertExists()
        shot("74-planung-en")
    }

    /** Teilen-Dialog mit Vorschau (Story) und das Bild in voller Groesse. */
    @Test
    fun teilenEnglisch() {
        startInHistory()
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        settle()
        compose.onAllNodesWithContentDescription(string(R.string.rides_detail_share_cd))[0].performClick()
        settle()
        compose.onAllNodesWithText(string(R.string.rides_share_square_option)).onFirst().assertExists()
        // Inhalt und Vorschau entstehen abseits des Hauptthreads (produceState);
        // ohne Warten zeigte das Bild nur die leere Flaeche und keinen Hinweis.
        val hint = string(R.string.rides_share_image_track_hint)
        assertEquals(
            "The image shows the shape of your route without a map – anyone who knows the area " +
                "can still spot the start and finish.",
            hint,
        )
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription(string(R.string.rides_share_preview_cd))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        settle()
        compose.onAllNodesWithText(hint).onFirst().assertExists()
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/75-teilen-en.png")

        val ride = sampleRides().first()
        val content = shareCardContent(
            ride,
            load = 84.0,
            language = AppLanguage.EN,
            resolve = { it.resolve(compose.activity) },
        )
        assertEquals(listOf("km", "h", "m climbed", "Avg HR"), content.stats.map { it.label })
        saveBitmap(renderShareCard(content, ShareCardFormat.STORY), "76-tourbild-story-en")
        // Ohne Puls steht die Trainingslast als vierte Zahl — das laengste Wort.
        val noHr = ride.copy(stats = ride.stats.copy(avgHrBpm = null))
        val square = shareCardContent(noHr, 84.0, AppLanguage.EN, resolve = { it.resolve(compose.activity) })
        assertTrue(square.stats.any { it.label == "Training load" })
        saveBitmap(renderShareCard(square, ShareCardFormat.SQUARE), "77-tourbild-square-en")
    }

    /** Leerer Verlauf: Satz und die zwei Wege zu Daten. */
    @Test
    fun leerEnglisch() {
        // Fremde Touren hat seed() schon beiseitegelegt; hier auch die eigenen weg.
        AppServices.rideStorage.listSummaries().summaries.forEach { AppServices.rideStorage.deleteRide(it.id) }
        startInHistory()
        compose.onAllNodesWithText(string(R.string.rides_list_empty_title)).onFirst().assertExists()
        shot("78-verlauf-leer-en")
    }

    // ------------------------------------------------------------ Helfer

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun startInHistory() {
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.RIDES)
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

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        val file = File("build/outputs/roborazzi/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** Drei gefahrene Touren (eine davon aus dem Vormonat) und eine Planung. */
    private fun sampleRides(): List<Ride> {
        val day = 24L * 60 * 60 * 1000
        val specs = listOf(
            Triple("Feierabendrunde", 2, 32.4),
            Triple("GA1 Kanalrunde", 4, 46.1),
            Triple("Intervalle am Deich", 40, 38.9),
        )
        return specs.mapIndexed { index, (name, daysAgo, km) ->
            val start = now - daysAgo * day
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
                id = "rides-en-$index",
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
        } + Ride(
            id = "rides-en-planned",
            name = "Alb-Runde über Hayingen",
            createdAt = now - day,
            stats = RideStats(distanceKm = 58.3, ascentM = 640.0, descentM = 640.0),
            planned = true,
            points = List(80) { i ->
                val t = i / 79.0 * 2 * Math.PI
                TrackPoint(lat = 48.30 + 0.04 * sin(t), lon = 9.40 + 0.06 * cos(t), ele = 700.0 + 60 * sin(t))
            },
        )
    }
}
