package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.challenge.ChallengeAvailability
import de.trailscape.app.challenge.ChallengeRouteInfo
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.catalog.CatalogText
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder zu „Herausforderung teilen" (docs/herausforderung.md): der Abschnitt auf
 * der Startseite, die Vorschau (was die Datei enthält), die Ablehnung einer
 * Datei und das Ergebnis in der Auswertung. Läuft wie die anderen
 * Screenshot-Tests nur mit `-Pscreenshots`.
 *
 * Dialoge (eigenes Fenster) werden nicht fotografiert; ihr Inhalt ist als
 * Karte gezeichnet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class ChallengeScreenshotTest {

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

    private val alpe = CatalogText("Alpe d'Huez", "Alpe d'Huez")

    private val items = listOf(
        IndoorChallengeUi(
            id = "0123456789abcdef", name = "Anna", date = "2026-11-20", routeKey = "catalog:alpe-dhuez",
            catalogId = "alpe-dhuez", routeName = alpe, availability = ChallengeAvailability.READY,
            distanceKm = 15.1, durationS = 3_412,
        ),
        IndoorChallengeUi(
            id = "1123456789abcdef", name = "Ben", date = null, routeKey = "catalog:sa-calobra",
            catalogId = "sa-calobra", routeName = CatalogText("Sa Calobra", "Sa Calobra"),
            availability = ChallengeAvailability.DOWNLOAD, distanceKm = 9.4, durationS = 2_130,
        ),
        IndoorChallengeUi(
            id = "2123456789abcdef", name = "", date = "2026-10-02", routeKey = "testwelt",
            catalogId = null, routeName = null, availability = ChallengeAvailability.READY,
            distanceKm = 3.0, durationS = 540,
        ),
        IndoorChallengeUi(
            id = "3123456789abcdef", name = "Clara", date = "2026-09-14", routeKey = "catalog:arenberg",
            catalogId = "arenberg", routeName = CatalogText("Arenberg", "Arenberg"),
            availability = ChallengeAvailability.MISMATCH, distanceKm = 2.4, durationS = 420,
        ),
    )

    private val route = IndoorRouteUi(
        options = listOf(IndoorRouteOption.TestWorld),
        selected = IndoorRouteOption.TestWorld,
        mode = IndoorMode.SIM,
        ergTitle = null,
        ergMinutes = null,
        ghost = IndoorGhostOption.Challenge("2123456789abcdef", "Herausforderung"),
        ghostPastRideAvailable = false,
        defaultGhostWatts = 180,
    )

    private val pair = IndoorPairUi(demoTrainer = false, trainerName = "KICKR CORE 7B2F", shifterName = null)

    /** Der Inhalt des Segments „Herausforderungen" (Indoor-Tab, docs/ux/umbau.md). */
    @Composable
    private fun Segment(challenges: IndoorChallengesUi) = Frame {
        IndoorChallengeSection(ui = challenges, onRide = { _, _ -> }, onDownload = {}, onDelete = {}, onImport = {})
    }

    @Test
    fun segmentMitHerausforderungen() = shot("spiel3-01-segment-liste") {
        Segment(IndoorChallengesUi(items, selectedId = "2123456789abcdef"))
    }

    @Test
    fun segmentLeer() = shot("spiel3-02-segment-leer") {
        Segment(IndoorChallengesUi(emptyList()))
    }

    /** Die Sicht „Strecken" mit gewählter Herausforderung: die Pille bei „Mitfahrer". */
    @Test
    fun streckenMitGewaehlterHerausforderung() = shot("spiel3-02b-strecken-mitfahrer") {
        IndoorHomeScreen(
            route = route, pair = pair, trainerIssue = null,
            onSelect = {}, onModeChange = {}, onGhostChange = {}, onRide = {}, onBack = {}, onOpenPair = {}, onOpenSettings = {},
            initialOptionsOpen = true,
        )
    }

    private fun preview(
        name: String = "Anna",
        date: String? = "2026-11-20",
        avatar: Boolean = false,
        availability: ChallengeAvailability = ChallengeAvailability.READY,
    ) = ChallengePreviewUi(
        name = name,
        route = ChallengeRoute.Catalog("alpe-dhuez", 2),
        info = ChallengeRouteInfo(alpe, availability, installedVersion = 2, distanceKm = 15.1, ascentM = 1105.0),
        distanceKm = 15.1,
        durationS = 3_412,
        date = date,
        hasAvatar = avatar,
    )

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
        }
    }

    @Test
    fun vorschauMitAllem() = shot("spiel3-03-vorschau-alles") {
        Frame { ChallengePreviewFacts(preview(avatar = true)) }
    }

    @Test
    fun vorschauOhneDatumUndName() = shot("spiel3-04-vorschau-ohne-datum") {
        Frame { ChallengePreviewFacts(preview(name = "", date = null)) }
    }

    @Test
    fun ergebnisVorDemGegner() = shot("spiel3-05-ergebnis-vor") {
        Frame { ChallengeResultCard(opponent = "Anna", gapS = -42.0) }
    }

    @Test
    fun ergebnisHinterDemGegner() = shot("spiel3-06-ergebnis-hinter") {
        Frame { ChallengeResultCard(opponent = "Anna", gapS = 95.0) }
    }

    @Test
    fun ergebnisGleichauf() = shot("spiel3-07-ergebnis-gleichauf") {
        Frame { ChallengeResultCard(opponent = "", gapS = 0.4) }
    }

    @Test
    fun auswertungMitHerausforderung() = shot("spiel3-08-auswertung") {
        IndoorSummaryScreen(
            ui = IndoorSummaryUi(
                routeName = "Alpe d'Huez", mode = IndoorMode.SIM, distanceKm = 15.1, movingS = 3_370,
                ascentM = 1_105.0, avgPowerW = 214, normalizedPowerW = 228, avgCadenceRpm = 88, avgHrBpm = 146,
                workKj = 721, avgSpeedKmh = 16.1, laps = 1, ghostLabel = "Anna", ghostGapS = -42.0,
                tooShort = false, challengeOpponent = "Anna", canShareChallenge = true,
            ),
            onSave = {}, onDiscard = {}, onDone = {},
        )
    }

    /** Die Zeile im Blatt „Teilen" der gespeicherten Indoor-Tour, einmal je Fassung. */
    @Test
    fun teilenZeile() = shot("spiel3-09-teilen-zeile") {
        Frame {
            de.trailscape.app.ui.rides.ChallengeShareRowContent(reply = false, onClick = {})
            de.trailscape.app.ui.rides.ChallengeShareRowContent(reply = true, onClick = {})
        }
    }
}
