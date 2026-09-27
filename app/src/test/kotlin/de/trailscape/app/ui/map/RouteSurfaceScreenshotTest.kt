package de.trailscape.app.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.i18n.AppLanguage
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
 * Bilder der beiden Stellen, an denen der Schotteranteil steht: die
 * Kandidatenzeilen der Rundenwahl und der Koerper des Planungsblatts.
 *
 * Die Blaetter werden **direkt** gerendert statt ueber die ganze App wie in
 * `ui/ScreenshotTest.kt`: Dorthin kaeme man nur ueber eine echte
 * Routenberechnung, und die braucht Netz oder eine Kachel. Laeuft wie dort nur
 * mit `-Pscreenshots`; die PNGs landen in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class RouteSurfaceScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private val target = RouteTarget(
        distanceKm = 45.0,
        ascentPreference = AscentPreference.MODERAT,
        durationH = 2.5,
        speedKmh = 18.0,
        intensity = SessionIntensity.GRUNDLAGE,
        label = "Grundlageneinheit",
        source = RouteTargetSource.PLAN,
    )

    /** Eine Runde mit etwas Hoehenprofil, damit das Profil im Koerper etwas zeigt. */
    private fun route(distanceKm: Double, ascentM: Double, paved: Double?, unpaved: Double?): PlannedRoute {
        val points = (0..120).map { i ->
            TrackPoint(
                lat = 48.4 + 0.002 * i,
                lon = 9.4 + 0.001 * i,
                ele = 700.0 + 60.0 * sin(i / 12.0),
            )
        }
        return PlannedRoute(points, distanceKm, ascentM, pavedKm = paved, unpavedKm = unpaved)
    }

    private fun candidate(route: PlannedRoute, bearing: Double) = RouteCandidate(
        route = route,
        distanceKm = route.distanceKm,
        ascentM = route.ascentM,
        score = 0.0,
        bearingDeg = bearing,
        targetKm = target.distanceKm,
    )

    @Test
    fun rundenwahl() = rundenwahl("60-rundenwahl-belag.png")

    /** Die Rundenwahl auf Englisch: Zielzeile, Quelle, Kandidaten mit „m/km" und „vs. target". */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun rundenwahlEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        rundenwahl("60-rundenwahl-belag-en.png")
        val context = compose.activity
        compose.onNodeWithText(context.getString(R.string.map_generation_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_generation_apply_action)).assertExists()
        compose.onNodeWithText("46.2 km · 540 m ↑").assertExists()
    }

    /** Das Planungsblatt mit fertiger Route auf Englisch. */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun planungRouteStehtEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val context = compose.activity
        planning(
            waypoints = listOf(
                Waypoint(48.4, 9.4, name = context.getString(R.string.map_planning_my_position_name)),
                Waypoint(48.5, 9.45),
                Waypoint(48.6, 9.5, name = "Bärenschlössle"),
            ),
            route = route(38.4, 420.0, paved = 20.0, unpaved = 18.4),
            file = "63-planung-route-en.png",
        )
        compose.onNodeWithText("My location → Bärenschlössle").assertExists()
        compose.onNodeWithText("38.4 km · 420 m ↑ · Server").assertExists()
        compose.onNodeWithText(context.getString(R.string.map_planning_start_action)).assertExists()
    }

    private fun rundenwahl(file: String) {
        val candidates = listOf(
            // 62 % unbefestigt
            candidate(route(46.2, 540.0, paved = 17.5, unpaved = 28.7), 0.0),
            // flach, fast nur Asphalt
            candidate(route(43.1, 210.0, paved = 41.0, unpaved = 2.1), 120.0),
            // bergig, Belag ueberwiegend unbekannt → keine Belagszeile
            candidate(route(48.9, 890.0, paved = 5.0, unpaved = 10.0), 240.0),
        )
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            RouteGenerationSheet(
                                state = RouteGenerationState(
                                    target = target,
                                    candidates = candidates,
                                    selectedIndex = 0,
                                ),
                                expanded = false,
                                onExpandedChange = {},
                                route = candidates[0].route,
                                candidatesMaxHeight = 400.dp,
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
    fun planungsblatt() {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            PlanningSheet(
                                expanded = true,
                                onExpandedChange = {},
                                half = false,
                                onHalfChange = {},
                                profile = de.trailscape.core.RouteProfile.SCHOTTER,
                                onProfileChange = {},
                                roundTrip = false,
                                onRoundTripChange = {},
                                waypoints = emptyList(),
                                route = route(46.2, 540.0, paved = 17.5, unpaved = 28.7),
                                busy = false,
                                error = null,
                                maxHeight = 600.dp,
                                generated = true,
                                onUseMyPosition = {},
                                onRemoveWaypoint = {},
                                onAddWaypointViaSearch = {},
                                onClear = {},
                                onSave = {},
                                onShare = {},
                                onNavigate = {},
                                onHoverPoint = {},
                                onClose = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/61-planung-belag.png")
    }

    /** Das Planungsblatt mit genau einem Punkt — der Zustand nach „+ Als Wegpunkt". */
    @Test
    fun planungEinPunkt() {
        planning(
            waypoints = listOf(Waypoint(48.4, 9.4, name = "Bärenschlössle, 14, Mahdental")),
            route = null,
            file = "62-planung-ein-punkt.png",
        )
    }

    /** Das Planungsblatt mit fertiger Route von Hand. */
    @Test
    fun planungRouteSteht() {
        planning(
            waypoints = listOf(
                Waypoint(48.4, 9.4, name = "Mein Standort"),
                Waypoint(48.5, 9.45),
                Waypoint(48.6, 9.5, name = "Bärenschlössle"),
            ),
            route = route(38.4, 420.0, paved = 20.0, unpaved = 18.4),
            file = "63-planung-route.png",
        )
    }

    private fun planning(waypoints: List<Waypoint>, route: PlannedRoute?, file: String) {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            PlanningSheet(
                                expanded = true,
                                onExpandedChange = {},
                                half = false,
                                onHalfChange = {},
                                profile = de.trailscape.core.RouteProfile.GRAVEL,
                                onProfileChange = {},
                                roundTrip = false,
                                onRoundTripChange = {},
                                waypoints = waypoints,
                                route = route,
                                busy = false,
                                error = null,
                                maxHeight = 700.dp,
                                source = de.trailscape.core.RoutingSource.SERVER,
                                onUseMyPosition = {},
                                onRemoveWaypoint = {},
                                onAddWaypointViaSearch = {},
                                onClear = {},
                                onSave = {},
                                onShare = {},
                                onNavigate = {},
                                onHoverPoint = {},
                                onClose = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file")
    }
}
