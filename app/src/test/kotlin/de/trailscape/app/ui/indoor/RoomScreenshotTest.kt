package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.challenge.ChallengeAvailability
import de.trailscape.app.room.TEST_INVITE_LINK
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.components.SectionEyebrow
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.indoor.catalog.CatalogText
import de.trailscape.core.room.RoomRoute
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder zum Raum für Gruppenfahrten (docs/raum-protokoll.md, docs/ux/umbau.md):
 * der Abschnitt „Raum" in Indoor → Gemeinsam in allen Zuständen, die Inhalte
 * der beiden Dialoge und die Einblendungen der Fahransicht. Läuft wie die
 * anderen Screenshot-Tests nur mit `-Pscreenshots`.
 *
 * Dialoge (eigenes Fenster) werden nicht fotografiert; ihr Inhalt ist als
 * Karte gezeichnet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class RoomScreenshotTest {

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

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }

    /** Der Abschnitt, wie ihn die Sicht „Gemeinsam" zeigt (mit der Überschrift darüber). */
    @Composable
    private fun Section(
        ui: IndoorRoomUi,
        server: RoomServerUi = RoomServerUi("sync.example.org"),
    ) = Frame {
        SectionEyebrow("Raum")
        IndoorRoomSection(
            ui = ui,
            server = server,
            createRoute = RoomRoute("catalog:alpe-dhuez", 15_100.0, version = "3", name = "Alpe d'Huez"),
            savedName = "Ben",
            savedSharePower = false,
            pendingInvite = null,
            onInviteConsumed = {},
            onCreate = { _, _ -> },
            onJoin = { _, _, _ -> true },
            onLeave = {},
            onEnd = {},
            onStartCountdown = {},
            onSharePower = {},
            onRide = {},
            onDownload = {},
            onDismissNotice = {},
            onOpenSettings = {},
            onChooseRoute = {},
        )
    }

    private val alpe = RoomRouteUi(
        key = "catalog:alpe-dhuez",
        name = CatalogText("Alpe d'Huez", "Alpe d'Huez"),
        fallbackName = "Alpe d'Huez",
        lengthKm = 15.1,
        availability = ChallengeAvailability.READY,
        catalogId = "alpe-dhuez",
    )

    private fun lobby(
        host: Boolean = false,
        route: RoomRouteUi = alpe,
        connection: RoomConnectionUi = RoomConnectionUi.Online,
        countdownS: Int? = null,
        started: Boolean = false,
    ) = IndoorRoomUi.Open(
        serverHost = "sync.example.org",
        host = host,
        route = route,
        riders = listOf(
            RoomRiderUi("a", "Ben", self = true, host = host, atStart = false),
            RoomRiderUi("b", "Anna", self = false, host = !host, atStart = !started, riding = started, distKm = if (started) 2.3 else null),
            RoomRiderUi("c", "Cem", self = false, host = false, atStart = false),
        ),
        maxRiders = 6,
        connection = connection,
        countdownS = countdownS,
        started = started,
        inviteLink = TEST_INVITE_LINK,
        sharePower = false,
    )

    // -----------------------------------------------------------------------
    // Kein Raum offen
    // -----------------------------------------------------------------------

    @Test
    fun geschlossenMitServer() = shot("spiel5-01-raum-geschlossen") {
        Section(IndoorRoomUi.Closed())
    }

    @Test
    fun geschlossenOhneServer() {
        shot("spiel5-02-raum-ohne-server") {
            Section(IndoorRoomUi.Closed(), server = RoomServerUi(null))
        }
        // Die Zeile sagt, wohin man dafür muss; Beitreten bleibt möglich.
        compose.onNodeWithText("Sync mit eigenem Server", substring = true).assertExists()
        compose.onNodeWithText("Raum beitreten").assertExists()
    }

    @Test
    fun geschlossenMitHinweis() = shot("spiel5-03-raum-beendet-hinweis") {
        Section(IndoorRoomUi.Closed(RoomNotice.ENDED_BY_HOST))
    }

    @Test
    fun wirdGeoeffnet() = shot("spiel5-04-raum-verbindet") {
        Section(IndoorRoomUi.Opening(creating = false, serverHost = "sync.example.org", attempt = 2))
    }

    // -----------------------------------------------------------------------
    // Dialoge
    // -----------------------------------------------------------------------

    @Test
    fun anlegenDialog() = shot("spiel5-05-anlegen-dialog") {
        Frame {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    RoomCreateForm(
                        serverHost = "sync.example.org",
                        route = RoomRoute("catalog:alpe-dhuez", 15_100.0, version = "3", name = "Alpe d'Huez"),
                        name = "Ben",
                        onNameChange = {},
                        sharePower = false,
                        onSharePowerChange = {},
                        onChooseRoute = {},
                    )
                }
            }
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    RoomCreateForm(
                        serverHost = "sync.example.org",
                        route = null,
                        name = "",
                        onNameChange = {},
                        sharePower = false,
                        onSharePowerChange = {},
                        onChooseRoute = {},
                    )
                }
            }
        }
    }

    @Test
    fun beitretenDialog() {
        shot("spiel5-06-beitreten-dialog") {
            Frame {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        RoomJoinForm(
                            link = TEST_INVITE_LINK,
                            onLinkChange = {},
                            serverHost = "sync.example.org",
                            name = "Ben",
                            onNameChange = {},
                            sharePower = true,
                            onSharePowerChange = {},
                        )
                    }
                }
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        RoomJoinForm(
                            link = "https://example.org/kein-link",
                            onLinkChange = {},
                            serverHost = null,
                            name = "Ben",
                            onNameChange = {},
                            sharePower = false,
                            onSharePowerChange = {},
                        )
                    }
                }
            }
        }
        // Vor dem Beitritt steht in einem Satz, was andere sehen, und mit welchem Server verbunden wird.
        compose.onNodeWithText("Server: sync.example.org").assertExists()
    }

    // -----------------------------------------------------------------------
    // Im Raum
    // -----------------------------------------------------------------------

    @Test
    fun imRaumAlsGast() = shot("spiel5-07-raum-gast") {
        Section(lobby())
    }

    @Test
    fun imRaumAlsGastgeber() = shot("spiel5-08-raum-gastgeber") {
        Section(lobby(host = true))
    }

    /** Schmales Handy, Systemschrift 130 %, dunkel: „Raum verlassen" und „Raum für alle beenden" brechen in Zeilen, nicht in Buchstaben. */
    @Test
    @Config(qualifiers = "${TestLocales.DE}-w360dp-h1500dp-night-xxhdpi")
    fun imRaumAlsGastgeberSchmal() = shot("schmal-11-raum-gastgeber") {
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale = 1.3f),
        ) { Section(lobby(host = true)) }
    }

    @Test
    fun imRaumStreckeFehlt() = shot("spiel5-09-raum-strecke-fehlt") {
        Section(lobby(route = alpe.copy(availability = ChallengeAvailability.DOWNLOAD)))
    }

    @Test
    fun imRaumCountdown() = shot("spiel5-10-raum-countdown") {
        Section(lobby(countdownS = 7))
    }

    @Test
    fun imRaumVerbindungUnterbrochen() = shot("spiel5-11-raum-verbindung") {
        Section(lobby(connection = RoomConnectionUi.Reconnecting(attempt = 2, retryInS = 4), started = true))
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun imRaumEnglisch() {
        shot("spiel5-12-raum-en") {
            Section(lobby(host = true))
        }
        compose.onNodeWithText("Room on sync.example.org").assertExists()
        compose.onNodeWithText("Share invite").assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun geschlossenOhneServerEnglisch() {
        shot("spiel5-13-raum-ohne-server-en") {
            Section(IndoorRoomUi.Closed(), server = RoomServerUi(null))
        }
        compose.onNodeWithText("Sync with your own server", substring = true).assertExists()
    }

    // -----------------------------------------------------------------------
    // Fahransicht
    // -----------------------------------------------------------------------

    private val gaps = listOf(
        RoomGapUi("b", "Anna", gapM = 12.0, stale = false, powerW = 240),
        RoomGapUi("c", "Cem", gapM = -35.0, stale = false),
        RoomGapUi("d", "Dana", gapM = 180.0, stale = true),
    )

    private fun rideUi(
        waiting: Boolean,
        countdownS: Int? = null,
        host: Boolean = false,
        connection: RoomConnectionUi = RoomConnectionUi.Online,
        closed: Boolean = false,
    ) = RoomRideUi(
        connection = connection,
        waiting = waiting,
        countdownS = countdownS,
        host = host,
        atStart = 2,
        total = 3,
        riders = if (waiting) emptyList() else gaps,
        closed = closed,
    )

    /** Dunkle Fläche wie die Fahransicht ohne 3D-Ansicht. */
    @Composable
    private fun RideBackdrop(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF26323A)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }

    @Test
    fun fahrtWartenAufDenStart() = shot("spiel5-14-fahrt-warten") {
        RideBackdrop {
            RoomStartCard(room = rideUi(waiting = true, host = true), onStart = {}, onLeave = {})
            RoomStartCard(room = rideUi(waiting = true), onStart = {}, onLeave = {})
            RoomStartCard(room = rideUi(waiting = true, countdownS = 4), onStart = {}, onLeave = {})
        }
    }

    @Test
    fun fahrtAbstaende() = shot("spiel5-15-fahrt-abstaende") {
        RideBackdrop {
            Box { HudRoomChips(room = rideUi(waiting = false)) }
            Box { HudRoomChips(room = rideUi(waiting = false, connection = RoomConnectionUi.Reconnecting(1, 2))) }
            Box { HudRoomChips(room = rideUi(waiting = false, closed = true)) }
        }
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun fahrtEnglisch() {
        shot("spiel5-16-fahrt-en") {
            RideBackdrop {
                RoomStartCard(room = rideUi(waiting = true, host = true), onStart = {}, onLeave = {})
                Box { HudRoomChips(room = rideUi(waiting = false)) }
            }
        }
        compose.onNodeWithText("Waiting for the start").assertExists()
        compose.onNodeWithText("Anna · 12 m ahead · 240 W").assertExists()
    }
}
