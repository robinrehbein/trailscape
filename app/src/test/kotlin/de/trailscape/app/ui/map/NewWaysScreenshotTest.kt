package de.trailscape.app.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.AscentPreference
import de.trailscape.core.PlannedRoute
import de.trailscape.core.RouteCandidate
import de.trailscape.core.RouteProfile
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.i18n.AppLanguage
import kotlin.math.sin
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder zu „Neue Wege": die Wahl „Neues entdecken" im Blatt *Runde ab hier*
 * und die Zeile „davon 23 km neu" an den Vorschlaegen — hell, dunkel und
 * englisch. Die Blaetter werden direkt gerendert wie in
 * `RouteSurfaceScreenshotTest`; laeuft nur mit `-Pscreenshots`, die PNGs
 * landen in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class NewWaysScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    // ------------------------------------------------------- Runde ab hier

    private fun setup(mode: DiscoveryMode, file: String, windOn: Boolean = false) {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            RoundTripSetupSheet(
                                startLabel = compose.activity.getString(R.string.map_screen_round_trip_from_position),
                                distanceKm = 45,
                                onDistanceChange = {},
                                profile = RouteProfile.SCHOTTER,
                                onProfileChange = {},
                                discoveryMode = mode,
                                onDiscoveryModeChange = {},
                                considerWind = windOn,
                                onConsiderWindChange = {},
                                onShowSuggestions = {},
                                onClose = {},
                                bottomInset = 0.dp,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file")
    }

    @Test
    fun rundeAbHierNeueWege() {
        setup(DiscoveryMode.NEW_WAYS, "70-runde-neue-wege.png")
        val c = compose.activity
        compose.onNodeWithText(c.getString(R.string.map_round_trip_discover_title)).assertExists()
        compose.onNodeWithText(c.getString(R.string.map_round_trip_discover_ways)).assertIsSelected()
        compose.onNodeWithText(c.getString(R.string.map_round_trip_discover_ways_body)).assertExists()
        // Bedienungshilfen: Ueberschrift als Ueberschrift, Segmente als
        // Optionsfelder mit der Ueberschrift in der Beschreibung.
        compose.onNode(hasText(c.getString(R.string.map_round_trip_discover_title)) and isHeading()).assertExists()
        compose.onNode(hasContentDescription("Neu entdecken: Wege") and hasRole(Role.RadioButton)).assertIsSelected()
        compose.onNode(hasContentDescription("Neu entdecken: Aus") and hasRole(Role.RadioButton)).assertIsNotSelected()
        // Auch der Untergrund ist jetzt eine Auswahl, kein Tab.
        assertEquals(0, compose.onAllNodes(hasRole(Role.Tab)).fetchSemanticsNodes().size)
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    @Config(qualifiers = "+night")
    fun rundeAbHierNeueWegeDunkel() {
        setup(DiscoveryMode.NEW_WAYS, "70-runde-neue-wege-dunkel.png", windOn = true)
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun rundeAbHierNeueWegeEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        setup(DiscoveryMode.NEW_WAYS, "70-runde-neue-wege-en.png")
        compose.onNodeWithText("Discover something new").assertExists()
        compose.onNodeWithText("New roads").assertIsSelected()
        compose.onNode(hasContentDescription("Discover: new roads") and hasRole(Role.RadioButton)).assertIsSelected()
    }

    /** Die Vorgabe (wie der fruehere Schalter): Neue Gegenden. */
    @Test
    fun rundeAbHierNeueGegenden() {
        setup(DiscoveryMode.NEW_AREAS, "71-runde-neue-gegenden.png")
        compose.onNodeWithText(compose.activity.getString(R.string.map_round_trip_discover_areas)).assertIsSelected()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun rundeAbHierAusEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        setup(DiscoveryMode.OFF, "72-runde-entdecken-aus-en.png")
        compose.onNodeWithText("Off").assertIsSelected()
    }

    // ------------------------------------------------------- Vorschlaege

    private val target = RouteTarget(
        distanceKm = 45.0,
        ascentPreference = AscentPreference.MODERAT,
        durationH = 2.5,
        speedKmh = 18.0,
        intensity = SessionIntensity.GRUNDLAGE,
        label = "Grundlageneinheit",
        source = RouteTargetSource.SELBST_GEWAEHLT,
    )

    private fun route(distanceKm: Double, ascentM: Double): PlannedRoute {
        val points = (0..120).map { i ->
            TrackPoint(lat = 48.4 + 0.002 * i, lon = 9.4 + 0.001 * i, ele = 700.0 + 60.0 * sin(i / 12.0))
        }
        return PlannedRoute(points, distanceKm, ascentM, pavedKm = distanceKm * 0.6, unpavedKm = distanceKm * 0.4)
    }

    private fun candidates() = listOf(
        candidate(route(46.2, 540.0), 0.0, newKm = 23.4),
        candidate(route(43.1, 410.0), 120.0, newKm = 4.3),
        candidate(route(48.9, 610.0), 240.0, newKm = 0.0),
    )

    private fun candidate(route: PlannedRoute, bearing: Double, newKm: Double) = RouteCandidate(
        route = route,
        distanceKm = route.distanceKm,
        ascentM = route.ascentM,
        score = 0.0,
        bearingDeg = bearing,
        targetKm = target.distanceKm,
        newTileCount = if (newKm > 10) 3 else 0,
        totalTileCount = 40,
        newWayKm = newKm,
        newWayShare = newKm / route.distanceKm,
    )

    private fun suggestions(file: String, hints: List<String> = emptyList()) {
        val list = candidates()
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            RouteGenerationSheet(
                                state = RouteGenerationState(target = target, candidates = list, selectedIndex = 0, hints = hints),
                                expanded = false,
                                onExpandedChange = {},
                                route = list[0].route,
                                candidatesMaxHeight = 420.dp,
                                bodyMaxHeight = 300.dp,
                                onStart = {},
                                onCancel = {},
                                onSelect = {},
                                onNextSuggestions = {},
                                onApply = {},
                                onDiscard = {},
                                onHoverPoint = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file")
    }

    @Test
    fun vorschlaegeNeueWege() {
        suggestions("73-vorschlaege-neue-wege.png")
        compose.onNodeWithText("davon 23 km neu").assertExists()
        compose.onNodeWithText("davon 4,3 km neu").assertExists()
        compose.onNodeWithText("nur bekannte Wege").assertExists()
    }

    @Test
    @Config(qualifiers = "+night")
    fun vorschlaegeNeueWegeDunkel() {
        suggestions("73-vorschlaege-neue-wege-dunkel.png")
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun vorschlaegeNeueWegeEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        suggestions("73-vorschlaege-neue-wege-en.png")
        compose.onNodeWithText("23 km of it new").assertExists()
        compose.onNodeWithText("only roads you know").assertExists()
    }

    /** Konnte der Wege-Index nicht aufgebaut werden, sagt es der Hinweis (statt still ohne „Wege" zu suchen). */
    @Test
    fun vorschlaegeWegeNichtBeruecksichtigt() {
        val hint = compose.activity.getString(R.string.map_generation_new_ways_unavailable_hint)
        suggestions("74-vorschlaege-wege-nicht-beruecksichtigt.png", hints = listOf(hint))
        compose.onNodeWithText("„Wege“ konnte diesmal nicht berücksichtigt werden.").assertExists()
    }
}
