package de.trailscape.app.ui.rides

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.ViewModelProvider
import kotlin.math.roundToInt
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
import kotlin.math.cos
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * Screenshot-Matrix und Kernweg-5-Pruefung fuer den Verlauf (`RidesScreen`,
 * `TourList`, `RideDetailScreen`) ueber 360 dp, 412 dp, Galaxy Tab S11
 * hochkant (`800×1280`) und quer (`1280×800`, 42/58 Master-Detail), jeweils
 * mit normaler und 1,3× Schrift sowie auf Deutsch und Englisch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class RidesMatrixScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private var saved: List<Ride> = emptyList()

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

    @After
    fun cleanUp() {
        RuntimeEnvironment.setFontScale(1.0f)
        if (System.getProperty("trailscape.screenshots") != "true") return
        val storage = AppServices.rideStorage
        storage.listSummaries().summaries.forEach { storage.deleteRide(it.id) }
        if (saved.isNotEmpty()) storage.saveRides(saved)
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun listeSchmalDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        startInHistory()
        awaitText("Feierabendrunde")
        awaitText("32,4 km")
        awaitText("292 hm")
        awaitText("1:24 h")
        awaitText("~2:40 h")
        shot("rides-360-liste-de")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun listeSchmalEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        startInHistory()
        awaitText("Feierabendrunde")
        awaitText("32.4 km")
        awaitText("292 m")
        awaitText("1:24 h")
        awaitText("~2:40 h")
        shot("rides-360-liste-en")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun listeSchmalGrossDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        RuntimeEnvironment.setFontScale(1.3f)
        startInHistory()
        awaitText("Feierabendrunde")
        awaitText("Sweet-Spot auf der Rolle")
        shot("rides-360-gross-de")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun listeSchmalGrossEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        RuntimeEnvironment.setFontScale(1.3f)
        startInHistory()
        awaitText("Feierabendrunde")
        awaitText(string(R.string.rides_list_planned_pill))
        shot("rides-360-gross-en")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun detailSchmalDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        startInHistory()
        openRide("Feierabendrunde")
        shot("rides-360-detail-de")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun detailSchmalGrossDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        RuntimeEnvironment.setFontScale(1.3f)
        startInHistory()
        openRide("Feierabendrunde")
        shot("rides-360-detail-gross-de")
    }

    @Test
    @Config(qualifiers = TestLocales.S25_DE)
    fun s25ListeUndDetailDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        startInHistory()
        awaitText("Feierabendrunde")
        shot("rides-s25-liste-de")

        openRide("Feierabendrunde")
        shot("rides-s25-detail-de")
    }

    @Test
    @Config(qualifiers = TestLocales.S25_DE)
    fun s25DetailGrossDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        RuntimeEnvironment.setFontScale(1.3f)
        startInHistory()
        openRide("Feierabendrunde")
        shot("rides-s25-detail-gross-de")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun tabletHochkantDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        startInHistory()
        awaitText("Heute")
        awaitText("Feierabendrunde")
        // Auf Medium bleibt die Liste einspaltig (keine direkte Detailansicht rechts).
        compose.onAllNodesWithText(string(R.string.rides_detail_ride_again_action)).assertCountEquals(0)
        shot("rides-tab-s11-hochkant-de")

        // Ein Tipp oeffnet das Vollbild-Detail samt Zurueck-Pille „Verlauf".
        openRide("Feierabendrunde")
        awaitText(string(R.string.rides_detail_back_label))
        shot("rides-tab-s11-hochkant-detail-de")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun tabletHochkantEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        startInHistory()
        awaitText("Today")
        awaitText("Feierabendrunde")
        shot("rides-tab-s11-hochkant-en")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabletQuerMasterDetailDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        startInHistory()
        // Rail bleibt sichtbar, waehrend das 42/58-Master-Detail aktiv ist.
        awaitText("Heute")
        awaitText("Feierabendrunde")
        // Erste sichtbare Tour (Planung „Alb-Runde über Hayingen") wird direkt rechts gezeigt.
        awaitText(string(R.string.rides_again_planned_action))
        awaitText(string(R.string.rides_detail_planned_notice))
        // Im Inline-Detail gibt es keinen „‹ Verlauf"-Zurueckknopf.
        compose.onAllNodesWithContentDescription(string(R.string.rides_detail_share_cd)).onFirst().assertIsDisplayed()
        shot("rides-tab-s11-quer-de")

        // Tipp auf eine gefahrene Tour aktualisiert nur rechts, Rail und Liste bleiben sichtbar.
        compose.onAllNodesWithText("GA1 Kanalrunde").onFirst().performClick()
        settle()
        awaitText(string(R.string.rides_detail_ride_again_action))
        awaitText("Heute")
        awaitText("Feierabendrunde")
        shot("rides-tab-s11-quer-auswahl-de")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun tabletQuerMasterDetailEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        startInHistory()
        awaitText("Today")
        awaitText(string(R.string.rides_again_planned_action))
        shot("rides-tab-s11-quer-en")

        compose.onAllNodesWithText("GA1 Kanalrunde").onFirst().performClick()
        settle()
        awaitText(string(R.string.rides_detail_ride_again_action))
        awaitText("Today")
        shot("rides-tab-s11-quer-auswahl-en")
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w1280dp-h800dp-night-xhdpi")
    fun tabletQuerDunkelDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        startInHistory(dark = true)
        awaitText("Heute")
        awaitText(string(R.string.rides_again_planned_action))
        awaitThumbnails()
        shot("rides-tab-s11-quer-dunkel-de")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabletQuerNurPlanungDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        val storage = AppServices.rideStorage
        storage.listSummaries().summaries.forEach { storage.deleteRide(it.id) }
        storage.saveRides(listOf(sampleRides().last()))
        startInHistory()
        awaitText("Heute")
        awaitText(string(R.string.rides_list_nothing_ridden_title))
        awaitText(string(R.string.rides_list_empty_record_action))
        awaitText(string(R.string.rides_again_planned_action))

        shot("rides-tab-s11-quer-nur-planung-de")
        val bitmap = BitmapFactory.decodeFile("build/outputs/roborazzi/rides-tab-s11-quer-nur-planung-de.png")
        try {
            val primaryBounds = compose.onAllNodesWithText(string(R.string.rides_again_planned_action))
                .onFirst().fetchSemanticsNode().boundsInRoot
            val secondaryBounds = compose.onAllNodesWithText(string(R.string.rides_list_empty_record_action))
                .onFirst().fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Rechte Detail-Aktion muss der einzige orange TrailscapePrimaryButton sein",
                hasOrangeActionPixels(bitmap, primaryBounds),
            )
            assertFalse(
                "Tour aufzeichnen im Leerzustand muss auf Expanded als Sekundaerknopf erscheinen",
                hasOrangeActionPixels(bitmap, secondaryBounds),
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabletQuerSegmentWechselUndDrehung() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        var landscape by mutableStateOf(true)
        compose.setContent {
            val baseConfig = LocalConfiguration.current
            val config = remember(landscape, baseConfig) {
                Configuration(baseConfig).apply {
                    screenWidthDp = if (landscape) 1280 else 800
                    screenHeightDp = if (landscape) 800 else 1280
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalMapRenderingAvailable provides false,
            ) {
                TrailscapeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.RIDES)
        settle()
        awaitText("Feierabendrunde")

        // Tour im 42/58-Master-Detail antippen (setzt detailRideId), dann zu Fortschritt wechseln.
        compose.onAllNodesWithText("Feierabendrunde").onFirst().performClick()
        settle()
        awaitText(string(R.string.rides_detail_ride_again_action))

        compose.onAllNodesWithText(string(R.string.rides_screen_segment_progress)).onFirst().performClick()
        settle()
        awaitText(string(R.string.progress_trend_eyebrow))

        // Drehung Expanded -> Medium darf kein veraltetes Vollbild-Tourdetail oeffnen.
        landscape = false
        settle()
        awaitText(string(R.string.progress_trend_eyebrow))
        compose.onAllNodesWithText(string(R.string.rides_detail_ride_again_action)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(string(R.string.rides_detail_share_cd)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(string(R.string.rides_detail_menu_cd)).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabletQuerLeerDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        AppServices.rideStorage.listSummaries().summaries.forEach { AppServices.rideStorage.deleteRide(it.id) }
        startInHistory()
        awaitText("Heute")
        awaitText(string(R.string.rides_list_empty_title))
        awaitText(string(R.string.rides_list_empty_record_action))
        shot("rides-tab-s11-quer-leer-de")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabletQuerSucheOhneTrefferDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        startInHistory()
        awaitText("Heute")
        compose.onAllNodesWithContentDescription(string(R.string.rides_screen_search_cd)).onFirst().performClick()
        settle()
        compose.onAllNodesWithText(string(R.string.rides_screen_search_placeholder)).onFirst().performTextInput("gibtsnicht")
        settle()
        awaitText(string(R.string.rides_detail_empty_title))
        shot("rides-tab-s11-quer-suche-leer-de")
    }

    /**
     * Kernweg 5: Verlauf -> Tour -> Detail (<= 1 Tipp) -> „Nochmal fahren"-Blatt
     * -> Option waehlen (<= 3 Tipps) bzw. „Teilen" (<= 2 Tipps), auf Smartphone
     * und Tab S11 quer.
     */
    @Test
    @Config(qualifiers = TestLocales.S25_DE)
    fun corePath5TapBudgetCompact() {
        startInHistory()
        var taps = 0
        // Tipp 1: Tour oeffnen -> Detailansicht
        compose.onAllNodesWithText("Feierabendrunde").onFirst().performClick()
        taps++
        settle()
        awaitText(string(R.string.rides_detail_ride_again_action))
        assertTrue("Detail muss in <= 1 Tipp erreichbar sein", taps <= 1)

        // Tipp 2: „Nochmal fahren" -> Blatt mit 3 Optionen
        compose.onAllNodesWithText(string(R.string.rides_detail_ride_again_action)).onFirst().performClick()
        taps++
        settle()
        awaitText(string(R.string.rides_again_outdoor_title))
        assertTrue("Nachfahren-Blatt muss in <= 3 Tipps erreichbar sein", taps <= 3)

        // Tipp 3: Option waehlen („Indoor fahren") -> schliesst Blatt & Detail und oeffnet Indoor-Ablauf
        compose.onAllNodesWithText(string(R.string.rides_again_indoor_title)).onFirst().performClick()
        taps++
        settle()
        assertTrue("Kernweg 5 komplett muss <= 3 Tipps benoetigen", taps <= 3)
        compose.onAllNodesWithText(string(R.string.rides_again_indoor_title)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.rides_detail_ride_again_action)).assertCountEquals(0)
        awaitText("Feierabendrunde")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun corePath5TapBudgetExpanded() {
        startInHistory()
        awaitText("Heute")
        var taps = 0
        // Tipp 1: Gefahrene Tour links antippen -> Detail rechts aktualisiert sich direkt
        compose.onAllNodesWithText("Feierabendrunde").onFirst().performClick()
        taps++
        settle()
        awaitText(string(R.string.rides_detail_ride_again_action))
        awaitText("Heute")
        assertTrue("Master-Detail-Wechsel darf hoechstens 1 Tipp kosten", taps <= 1)

        // Tipp 2: „Nochmal fahren" direkt im rechten Pane
        compose.onAllNodesWithText(string(R.string.rides_detail_ride_again_action)).onFirst().performClick()
        taps++
        settle()
        awaitText(string(R.string.rides_again_outdoor_title))
        assertTrue("Nachfahren-Blatt muss auf Tablet quer in <= 3 Tipps erreichbar sein", taps <= 3)

        // Tipp 3: Option waehlen („Indoor fahren") -> schliesst Blatt & Detail und oeffnet Indoor-Ablauf
        compose.onAllNodesWithText(string(R.string.rides_again_indoor_title)).onFirst().performClick()
        taps++
        settle()
        assertTrue("Kernweg 5 auf Tablet quer in <= 3 Tipps", taps <= 3)
        compose.onAllNodesWithText(string(R.string.rides_again_indoor_title)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.rides_detail_ride_again_action)).assertCountEquals(0)
        awaitText("Feierabendrunde")
    }

    @Test
    @Config(qualifiers = TestLocales.S25_DE)
    fun corePath5ShareTapBudgetCompact() {
        startInHistory()
        var taps = 0
        compose.onAllNodesWithText("Feierabendrunde").onFirst().performClick()
        taps++
        settle()
        awaitText(string(R.string.rides_detail_ride_again_action))

        compose.onAllNodesWithContentDescription(string(R.string.rides_detail_share_cd)).onFirst().performClick()
        taps++
        settle()
        awaitText(string(R.string.rides_share_square_option))
        assertTrue("Teilen-Dialog muss in <= 2 Tipps ab Verlauf erreichbar sein", taps <= 2)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun corePath5ShareTapBudgetExpanded() {
        startInHistory()
        awaitText("Heute")
        var taps = 0
        compose.onAllNodesWithText("Feierabendrunde").onFirst().performClick()
        taps++
        settle()
        awaitText(string(R.string.rides_detail_ride_again_action))

        compose.onAllNodesWithContentDescription(string(R.string.rides_detail_share_cd)).onFirst().performClick()
        taps++
        settle()
        awaitText(string(R.string.rides_share_square_option))
        assertTrue("Teilen-Dialog auf Tablet quer muss in <= 2 Tipps erreichbar sein", taps <= 2)
    }

    // ------------------------------------------------------------ Helfer

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun openRide(name: String) {
        compose.onAllNodesWithText(name).onFirst().performClick()
        settle()
        awaitText(string(R.string.rides_detail_ride_again_action))
    }

    private fun startInHistory(dark: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.RIDES)
        settle()
    }

    private fun awaitThumbnails() {
        val summaries = AppServices.rideStorage.listSummaries().summaries
        compose.waitUntil(timeoutMillis = 15_000) {
            summaries.all { RideThumbnailCache.peek(it) != null }
        }
        settle()
    }

    private fun hasOrangeActionPixels(bitmap: Bitmap, bounds: androidx.compose.ui.geometry.Rect): Boolean {
        val rect = IntRect(
            left = bounds.left.roundToInt().coerceIn(0, bitmap.width - 1),
            top = bounds.top.roundToInt().coerceIn(0, bitmap.height - 1),
            right = bounds.right.roundToInt().coerceIn(0, bitmap.width),
            bottom = bounds.bottom.roundToInt().coerceIn(0, bitmap.height),
        )
        var hits = 0
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r > 165 && g in 38..105 && b < 45) {
                    hits++
                    if (hits >= 50) return true
                }
            }
        }
        return false
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun sampleRides(): List<Ride> {
        val day = 24L * 60 * 60 * 1000
        val outdoorSpecs = listOf(
            Triple("Feierabendrunde", 2, 32.4),
            Triple("GA1 Kanalrunde", 5, 46.1),
            Triple("Intervalle am Deich", 40, 38.9),
        )
        val outdoor = outdoorSpecs.mapIndexed { index, (name, daysAgo, km) ->
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
                id = "matrix-outdoor-$index",
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
        val indoorStart = now - 3 * day
        val indoorDurationS = (36.0 / 25.0 * 3600).toInt()
        val indoor = Ride(
            id = "matrix-indoor",
            name = "Sweet-Spot auf der Rolle",
            createdAt = indoorStart,
            stats = RideStats(
                distanceKm = 36.0,
                ascentM = 320.0,
                descentM = 320.0,
                durationS = indoorDurationS,
                movingTimeS = indoorDurationS,
                avgSpeedKmh = 25.0,
                avgHrBpm = 142,
                maxHrBpm = 171,
            ),
            points = List(80) { i ->
                val t = i / 79.0 * 2 * Math.PI
                TrackPoint(
                    lat = 48.42 + 0.02 * sin(t),
                    lon = 9.08 + 0.03 * cos(t),
                    ele = 480.0 + 30 * sin(t),
                    time = indoorStart + (indoorDurationS * 1000L * i / 79),
                    hr = 142,
                    power = 225,
                )
            },
            indoor = true,
        )
        val planned = Ride(
            id = "matrix-planned",
            name = "Alb-Runde über Hayingen",
            createdAt = now - day,
            stats = RideStats(distanceKm = 58.3, ascentM = 640.0, descentM = 640.0),
            planned = true,
            points = List(80) { i ->
                val t = i / 79.0 * 2 * Math.PI
                TrackPoint(lat = 48.30 + 0.04 * sin(t), lon = 9.40 + 0.06 * cos(t), ele = 700.0 + 60 * sin(t))
            },
        )
        return outdoor + indoor + planned
    }
}
