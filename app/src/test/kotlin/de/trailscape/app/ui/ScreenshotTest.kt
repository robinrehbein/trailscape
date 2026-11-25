package de.trailscape.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.progress.FitnessTrendCard
import de.trailscape.app.ui.progress.WeeklyRecapCard
import de.trailscape.core.FitnessComparison
import de.trailscape.core.FitnessSignal
import de.trailscape.core.FitnessSignalKind
import de.trailscape.core.FitnessVerdict
import de.trailscape.core.PeriodTotals
import de.trailscape.core.RecordKind
import de.trailscape.core.WeeklyRecap
import java.time.LocalDate
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.map.LONG_PRESS_HINT_CALM_MS
import de.trailscape.app.ui.map.LONG_PRESS_HINT_STORAGE_KEY
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.Goal
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import de.trailscape.core.assessFitness
import de.trailscape.core.generatePlan
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import de.trailscape.core.savePlan
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
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
 * Screenshots der vier Tabs und der Einstellungen — das Werkzeug fuer den
 * visuellen Feinschliff ohne Geraet. Rendert die echte [TrailscapeApp] mit
 * Beispieldaten (sechs Touren, ein Ziel mit Plan) auf der JVM.
 *
 * Laeuft nur auf Wunsch:
 * `./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'`.
 * Die PNGs landen in `app/build/outputs/roborazzi/`.
 *
 * Geraet: Galaxy S25 (1080 × 2340 px, rund 411 × 891 dp). Sprache: fest
 * Deutsch ([TestLocales.S25_DE]) — die Tests klicken auf deutsche Texte.
 * Englische Bilder tragen „-en" im Namen und laufen mit „+en-rGB".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun seed() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
        AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
        // Der einmalige Tipp zum langen Druecken gilt als erledigt — sonst
        // laege er je nach Wartezeit mal in einem Kartenbild, mal nicht. Sein
        // eigenes Bild macht [karteLangDruckTipp].
        AppServices.keyValueStore.setString(LONG_PRESS_HINT_STORAGE_KEY, "1")
        val rides = sampleRides()
        AppServices.rideStorage.saveRides(rides)
        savePlan(AppServices.trainingPlanStore, generatePlan(SAMPLE_GOAL, assessFitness(rides), now = NOW, texts = CoreTextsDe))
    }

    @Test
    fun tabs() {
        start()
        shot("01-heute")
        tab("Verlauf")
        shot("02-verlauf")
        segment("Fortschritt")
        shot("02b-verlauf-fortschritt")
        tab("Indoor")
        shot("03-indoor")
        segment("Plan")
        shot("03b-indoor-plan")
        tab("Heute")
        compose.onAllNodesWithContentDescription("Einstellungen", substring = true)[0].performClick()
        settle()
        shot("04-einstellungen")
    }

    @Test
    fun details() {
        start()
        compose.onAllNodesWithText("Warum", substring = true)[0].performClick()
        settle()
        shot("05-warum")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
        tab("Verlauf")
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        settle()
        shot("06-tour")
    }

    /** Ganze Seiten auf einem sehr hohen Bildschirm — zeigt, was sonst unter dem Rand liegt. */
    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h2400dp-xxhdpi")
    fun tall() {
        start()
        shot("11-heute-lang")
        tab("Verlauf")
        segment("Fortschritt")
        shot("13-fortschritt-lang")
        tab("Indoor")
        segment("Plan")
        shot("13b-plan-lang")
        tab("Verlauf")
        // Der Verlauf merkt sich die Sicht: zurueck auf die Tourenliste.
        segment("Touren")
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        settle()
        shot("15-tour-lang")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
        tab("Heute")
        compose.onAllNodesWithContentDescription("Einstellungen", substring = true)[0].performClick()
        settle()
        shot("14-einstellungen-lang")
    }

    /**
     * Training mit allem, was um Aufmerksamkeit konkurriert: Profil fehlt,
     * „Plan und Ziel passen nicht zusammen" und die Anpassungs-Notiz. Der Plan
     * begann vor drei Wochen, damit abgeschlossene, zu leichte Wochen die
     * Anpassung ausloesen; 120 km sind weit mehr, als die Beispieltouren
     * tragen. Pruefpunkt beim Ansehen: hoechstens EINE Flaeche in
     * Warnfarbe, der Rest ruhige Zeilen.
     */
    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h2400dp-xxhdpi")
    fun trainingHinweise() {
        val goal = Goal(
            name = "Alb-Gold",
            distanceKm = 120.0,
            ascentM = 700.0,
            date = NOW + 8 * WEEK_MS,
            targetDurationMin = 130,
        )
        savePlan(
            AppServices.trainingPlanStore,
            generatePlan(goal, assessFitness(sampleRides()), now = NOW - 3 * WEEK_MS, texts = CoreTextsDe),
        )
        start()
        tab("Indoor")
        segment("Plan")
        shot("16-training-hinweise")
    }

    /** Grosse Systemschrift (130 %) — findet abgeschnittene Beschriftungen. */
    @Test
    fun grosseSchrift() {
        RuntimeEnvironment.setFontScale(1.3f)
        start()
        shot("21-heute-gross")
        tab("Karte")
        shot("22-karte-gross")
        tab("Verlauf")
        shot("23-verlauf-gross")
        // Die Aktionskacheln im Tourdetail brechen ab 130 % auf zwei Spalten
        // um — hier sieht man, ob „Umbenennen" dabei ganz bleibt.
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        settle()
        shot("25-tour-gross")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
        tab("Indoor")
        shot("24-indoor-gross")
    }

    /**
     * Der Wochenrueckblick direkt gerendert: Die App zeigt ihn nur Montag bis
     * Mittwoch, ein Bild an jedem Wochentag braeuchte eine einschleusbare Uhr.
     * Hell/dunkel und bei 130 % Schrift untereinander.
     */
    @Test
    fun wochenrueckblickKarte() {
        RuntimeEnvironment.setFontScale(1.3f)
        val recap = WeeklyRecap(
            monday = LocalDate.of(2026, 11, 9),
            totals = PeriodTotals(km = 86.0, ascentM = 700.0, timeS = 13_000, rides = 3),
            previous = PeriodTotals(km = 70.0, ascentM = 500.0, timeS = 10_000, rides = 2),
            activeDays = 3,
            longestRideKm = 40.0,
            records = listOf(RecordKind.DISTANCE, RecordKind.ASCENT),
            streakWeeks = 4,
        )
        compose.setContent {
            Column {
                for (dark in listOf(false, true)) {
                    TrailscapeTheme(darkTheme = dark) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.padding(16.dp)) { WeeklyRecapCard(recap) }
                        }
                    }
                }
            }
        }
        settle()
        compose.onAllNodesWithText("86", substring = true)[0].assertExists()
        shot("15-wochenrueckblick")
    }

    /**
     * Die Fitness-Karte („Du wirst fitter") mit allen drei Beobachtungen, dazu
     * die Variante ohne Puls und „zu wenig Daten" — hell/dunkel bei 130 % Schrift.
     */
    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h3200dp-xxhdpi")
    fun fitnessTrendKarte() {
        RuntimeEnvironment.setFontScale(1.3f)
        val fitter = FitnessComparison(
            verdict = FitnessVerdict.FITTER,
            signals = listOf(
                FitnessSignal(FitnessSignalKind.EFFICIENCY, 3.1, 2.98, 4, 5, 4),
                FitnessSignal(FitnessSignalKind.PACE, 24.1, 23.2, 4, 5, 4),
                FitnessSignal(FitnessSignalKind.ENDURANCE, 82.0, 61.0, 34, 5, 4),
            ),
            windowDays = 42,
        )
        val noHr = FitnessComparison(
            verdict = FitnessVerdict.DECLINING,
            signals = listOf(FitnessSignal(FitnessSignalKind.PACE, 22.4, 23.2, -3, 4, 5)),
            windowDays = 42,
        )
        val unknown = FitnessComparison(FitnessVerdict.NOT_ENOUGH_DATA, emptyList(), 42)
        compose.setContent {
            Column {
                for (dark in listOf(false, true)) {
                    TrailscapeTheme(darkTheme = dark) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                FitnessTrendCard(fitter)
                                if (dark) {
                                    FitnessTrendCard(noHr)
                                    FitnessTrendCard(unknown)
                                }
                            }
                        }
                    }
                }
            }
        }
        settle()
        compose.onAllNodesWithText("82", substring = true)[0].assertExists()
        shot("training-fitness-trend")
    }

    /**
     * Schmales, niedriges Geraet (360 × 640 dp) mit Samsungs erster
     * Vergroesserungsstufe (115 %): Hier reichen vier Aktionskacheln nebeneinander
     * nicht mehr fuer „Umbenennen" — sie muessen auf zwei Spalten umbrechen,
     * statt das Wort zu trennen. Dazu das Karten-Tourblatt, ob ueber ihm noch
     * Karte bleibt.
     */
    @Test
    @Config(qualifiers = "${TestLocales.DE}-w360dp-h640dp-xxhdpi")
    fun schmal() {
        RuntimeEnvironment.setFontScale(1.15f)
        start()
        // Zuerst die Karte: Nach einem Besuch im Verlauf steht dort ein
        // anderes Blatt, und der Weg zur gespeicherten Route waere ein anderer.
        tab("Karte")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))[0]
            .performSemanticsAction(SemanticsActions.Expand)
        settle()
        compose.onAllNodesWithText("Alb-Runde über Hayingen")[0].performClick()
        settle()
        shot("17-karte-tour-schmal")
        // Mit offenem Tourblatt ist die Navigationsleiste ausgeblendet.
        compose.onAllNodesWithContentDescription("Auswahl aufheben")[0].performClick()
        settle()
        tab("Verlauf")
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        settle()
        // Auf 640 dp liegen die Kacheln unter dem Rand — hochgewischt, ueber
        // den ganzen Bildschirm. Frueher auf dem Plan-Urteil der Tour: Das
        // haengt vom heutigen Wochentag ab („Locker gefahren." vs. „Passt zum
        // Plan."), und der Test fiel dann je nach Kalendertag um.
        compose.onRoot().performTouchInput { swipeUp() }
        settle()
        shot("16-tour-schmal")
    }

    /**
     * Einzelbilder mitten in den Uebergaengen (M3: Top level, Forward and
     * backward) — zeigt, dass ausgeblendet wird, bevor eingeblendet wird.
     */
    @Test
    fun uebergaenge() {
        start()
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText("Verlauf")[0].performClick()
        frames("31-tab")
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        frames("32-detail")
    }

    /** Karte betreten: Die Karte blendet ein, das Blatt faehrt von unten herauf. */
    @Test
    fun uebergangKarte() {
        start()
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText("Karte")[0].performClick()
        frames("33-karte")
    }

    @Test
    fun karte() {
        start()
        tab("Karte")
        shot("07-karte")
        // Hochgewischt: gespeicherte Routen und zuletzt gesuchte Orte.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))[0]
            .performSemanticsAction(SemanticsActions.Expand)
        settle()
        shot("08-karte-wohin-offen")
        // Eine gespeicherte Route antippen: das Tour-Blatt.
        compose.onAllNodesWithText("Alb-Runde über Hayingen")[0].performClick()
        settle()
        shot("09-karte-tour")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))[0]
            .performSemanticsAction(SemanticsActions.Expand)
        settle()
        shot("10-karte-tour-offen")
    }

    /**
     * Das Tourblatt direkt nach dem Beenden einer Aufzeichnung: „Was die Tour
     * gebracht hat" ueber den Aktionen (`ui/map/RideHighlights.kt`).
     *
     * Der Uebergang ist der echte: Die fertige Tour liegt in der Ablage und
     * wird ueber [RecordingRepository.publishFinishedRide] gemeldet — genau
     * wie der Aufnahmedienst es tut; das ViewModel laedt neu und waehlt sie
     * aus. Die Touren sind so gelegt, dass mindestens drei Zeilen erscheinen:
     * drei Wochen mit je einer 30-km-Tour (Serie, mitwachsendes Wochenziel um
     * 30 km), dann heute 82,4 km — neue Bestweite, und sie bringt die Woche
     * allein ueber das Ziel.
     */
    @Test
    fun karteTourHighlights() = highlightsShot("karte-tour-highlights")

    @Test
    @Config(qualifiers = "+night")
    fun karteTourHighlightsDunkel() = highlightsShot("karte-tour-highlights-dunkel", dark = true)

    /** Wie [schmal]: 360 × 640 dp mit 115 % Schrift — nur zwei Zeilen, damit ueber dem Blatt Karte bleibt. */
    @Test
    @Config(qualifiers = "${TestLocales.DE}-w360dp-h640dp-xxhdpi")
    fun karteTourHighlightsSchmal() {
        RuntimeEnvironment.setFontScale(1.15f)
        highlightsShot("karte-tour-highlights-schmal")
    }

    /** Wie [grosseSchrift]: 130 % Systemschrift. */
    @Test
    fun karteTourHighlightsGross() {
        RuntimeEnvironment.setFontScale(1.3f)
        highlightsShot("karte-tour-highlights-gross")
    }

    private fun highlightsShot(name: String, dark: Boolean = false) {
        // `AppServices` und seine Ablage leben ueber die Tests der Klasse
        // hinweg: Beispieltouren und Plan aus [seed] weg (ohne Plan gilt das
        // mitwachsende Wochenziel, dessen Zahl sich aus den Touren hier
        // ergibt), eigene Touren hinein — und am Ende alles zurueck, damit
        // kein anderer Test von der Reihenfolge abhaengt.
        val samples = sampleRides()
        samples.forEach { AppServices.rideStorage.deleteRide(it.id) }
        savePlan(AppServices.trainingPlanStore, null)
        val rides = highlightRides()
        AppServices.rideStorage.saveRides(rides)
        try {
            start(dark)
            tab("Karte")
            compose.runOnUiThread { RecordingRepository.publishFinishedRide(HIGHLIGHT_RIDE_ID) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("Neue Bestweite", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            settle()
            compose.onAllNodesWithText("Neue Bestweite: 82,4", substring = true)[0].assertExists()
            compose.onAllNodesWithText("Wochenziel geschafft", substring = true)[0].assertExists()
            // Kein Snackbar-Text im Bild: Nach dem Speichern meldet das
            // ViewModel „Tour gespeichert" — die verschwindet von selbst.
            compose.mainClock.advanceTimeBy(10_000)
            settle()
            shot(name)
        } finally {
            rides.forEach { AppServices.rideStorage.deleteRide(it.id) }
            AppServices.rideStorage.saveRides(samples)
            savePlan(
                AppServices.trainingPlanStore,
                generatePlan(SAMPLE_GOAL, assessFitness(samples), now = NOW, texts = CoreTextsDe),
            )
        }
    }

    /**
     * Der einmalige Tipp zum langen Druecken: erscheint beim ersten ruhigen
     * Erkunden nach der Wartezeit (`LongPressHint.kt`) als Snackbar ueber der
     * Kapsel.
     *
     * Gewartet wird ausdruecklich auf den Text, in kleinen Schritten und
     * hoechstens Ruhezeit plus Reserve — nicht auf die Dauer von [settle].
     * Wird die kuerzer oder die Ruhezeit laenger, schlaegt der Test fehl,
     * statt still ein Bild ohne Tipp abzulegen.
     */
    @Test
    fun karteLangDruckTipp() {
        AppServices.keyValueStore.remove(LONG_PRESS_HINT_STORAGE_KEY)
        start()
        tab("Karte")
        val hint = compose.activity.getString(R.string.map_long_press_hint_snackbar)
        var waited = 0L
        while (compose.onAllNodesWithText(hint).fetchSemanticsNodes().isEmpty() &&
            waited < LONG_PRESS_HINT_CALM_MS + 2_000
        ) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            waited += 100
        }
        compose.onAllNodesWithText(hint)[0].assertIsDisplayed()
        // Die Einblende-Animation der Snackbar zu Ende laufen lassen.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        shot("44-karte-langdruck-tipp")
    }

    /**
     * Stil-Blatt: Mit der Standard-Strassenkarte steht statt des
     * Speichern-Knopfs die Begruendung samt Wechsel-Knopf; nach dem Wechsel
     * auf die Vektorkarte der Speichern-Knopf.
     */
    @Test
    fun karteStil() {
        start()
        tab("Karte")
        compose.onAllNodesWithContentDescription("Karte, Kacheln und Offline")[0].performClick()
        settle()
        // Das Blatt steht erst halb offen; hochgewischt zeigt es den
        // Offline-Abschnitt unten.
        compose.onAllNodesWithText("OpenStreetMap")[0].performTouchInput { swipeUp() }
        settle()
        shot("40-karte-stil-raster")
        compose.onAllNodesWithText("Zur Vektorkarte wechseln")[0].performClick()
        settle()
        shot("41-karte-stil-offline")
    }

    /**
     * Die Karte auf Englisch (i18n-map): das „Wohin?"-Blatt, hochgewischt mit
     * gespeicherten Routen, das Tour-Blatt und das Stil-Blatt. Geklickt wird
     * ueber Ressourcen, nicht ueber Literale — nur der Tab heisst je nach
     * Stand der Shell-Uebersetzung noch „Karte" oder schon „Map".
     */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun karteEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val context = compose.activity
        start()
        compose.onAllNodes(hasText("Karte") or hasText("Map"))[0].performClick()
        settle()
        compose.onAllNodesWithText(context.getString(R.string.map_explore_search_placeholder))[0].assertExists()
        assertEquals("Where to?", context.getString(R.string.map_explore_search_placeholder))
        shot("07-karte-en")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))[0]
            .performSemanticsAction(SemanticsActions.Expand)
        settle()
        compose.onAllNodesWithText(context.getString(R.string.map_explore_saved_routes_label))[0].assertExists()
        shot("08-karte-wohin-offen-en")
        compose.onAllNodesWithText("Alb-Runde über Hayingen")[0].performClick()
        settle()
        compose.onAllNodesWithText(context.getString(R.string.map_ride_card_start_action))[0].assertExists()
        shot("09-karte-tour-en")
        compose.onAllNodesWithContentDescription(context.getString(R.string.map_ride_card_close_cd))[0]
            .performClick()
        settle()
        compose.onAllNodesWithContentDescription(context.getString(R.string.map_layers_cd))[0].performClick()
        settle()
        compose.onAllNodesWithText("OpenStreetMap")[0].performTouchInput { swipeUp() }
        settle()
        compose.onAllNodesWithText(context.getString(R.string.map_style_sheet_switch_action))[0].assertExists()
        shot("40-karte-stil-raster-en")
    }

    /**
     * Ortssuche mit eingetipptem Text: das Angebot zum Absenden, bei zu kurzem
     * Text stattdessen der Hinweis auf die Mindestlaenge.
     *
     * Nur das **Bild**. Dass Tippen keine Anfrage ausloest, sichert
     * `map/PlaceSearchTest` an [de.trailscape.app.ui.map.PlaceSearchState]
     * und [de.trailscape.app.ui.map.PlaceSearchEffect] ab, auf denen der
     * Karten-Screen seine Suche aufbaut; der Screen selbst reicht nur Text und
     * Absenden an diesen Halter durch.
     */
    @Test
    fun karteSuche() {
        start()
        tab("Karte")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Tübingen")
        settle()
        shot("42-karte-suche-getippt")
        compose.onAllNodes(hasSetTextAction())[0].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Ul")
        settle()
        shot("43-karte-suche-zu-kurz")
    }

    /**
     * Dunkelmodus — die Stellen, an denen eigene Farben (Karte, Vorschaubilder,
     * Trainingsfarben) am Schema vorbei gesetzt sind, fallen nur hier auf.
     * `+night` zusaetzlich zum Theme-Schalter, weil manche Stellen
     * `isSystemInDarkTheme()` selbst lesen statt das Schema zu fragen.
     */
    @Test
    @Config(qualifiers = "+night")
    fun dunkel() {
        start(dark = true)
        shot("51-heute-dunkel")
        tab("Verlauf")
        shot("52-verlauf-dunkel")
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        settle()
        shot("56-tour-dunkel")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
        tab("Indoor")
        shot("53-indoor-dunkel")
        tab("Karte")
        shot("54-karte-dunkel")
    }

    /**
     * „Karte" gibt es im Verlauf genau einmal — als Knopf „Alle Touren auf der
     * Karte". Frueher stand dort zusaetzlich das Segment „Liste | Karte",
     * gleichnamig mit dem Tab in der Navigationskapsel. Die Kapsel selbst
     * zaehlt nicht mit (Knoten mit [Role.Tab] und ihre Kinder).
     */
    @Test
    fun verlaufNenntKarteNurEinmal() {
        start()
        tab("Verlauf")
        shot("25-verlauf-kartenknopf")
        val karteImInhalt = compose.onAllNodes(karteAusserhalbDerNavigation()).fetchSemanticsNodes()
        assertEquals(
            listOf("Alle Touren auf der Karte"),
            karteImInhalt.map { it.config[SemanticsProperties.Text].joinToString("") },
        )
        compose.onAllNodesWithText("Alle Touren auf der Karte")[0].performClick()
        settle()
        shot("26-verlauf-karte")
    }

    /**
     * Ohne gefahrene Tour gibt es nichts auf die Verlaufskarte zu zeichnen —
     * eine gespeicherte Planung allein blendet den Knopf nicht ein.
     */
    @Test
    fun ohneGefahreneTourKeinKartenknopf() {
        // `saveRides` ergaenzt nur — die gefahrenen Beispieltouren aus
        // [seed] einzeln wieder loeschen, die Planung bleibt.
        sampleRides().filterNot { it.planned }.forEach { AppServices.rideStorage.deleteRide(it.id) }
        start()
        tab("Verlauf")
        shot("27-verlauf-nur-geplant")
        assertEquals(0, compose.onAllNodes(karteAusserhalbDerNavigation()).fetchSemanticsNodes().size)
    }

    private fun karteAusserhalbDerNavigation(): SemanticsMatcher {
        val navTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        return hasText("Karte", substring = true) and !navTab and !hasAnyAncestor(navTab)
    }

    /**
     * Der Heute-Tab auf Englisch (`01-heute-en`), danach die Sprachseite
     * (`45-einstellungen-sprache-en`).
     *
     * Das Bild allein beweist nichts; die Knoten pruefen, dass wirklich
     * englisch gezeichnet wird: Titel und „Warum"-Link aus
     * `values-en/strings_today.xml`, das Zahnrad („Settings" aus
     * `strings_common.xml`) und die Texte der Sprachseite aus
     * `values-en/strings_more.xml`.
     */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun heuteEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        start()
        assertEquals(AppLanguage.EN, AppServices.appLanguage.value)
        // Die Auswertung rechnet ihre `:core`-Saetze in der Sprache der
        // Oberflaeche neu (AppViewModel.insights kombiniert appLanguage).
        val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.waitUntil(timeoutMillis = 10_000) {
            val recommendation = viewModel.insights.value.recommendation
            recommendation.title == CoreTextsEn.readiness.recommendationTitle(recommendation.kind)
        }
        val context = compose.activity
        val settings = context.getString(R.string.common_settings_cd)
        assertEquals("Settings", settings)
        compose.onAllNodesWithContentDescription(settings)[0].assertExists()
        assertEquals("Today", context.getString(R.string.today_screen_title))
        compose.onAllNodesWithText(context.getString(R.string.today_screen_title))[0].assertExists()
        compose.onAllNodesWithText(context.getString(R.string.today_hero_why_action))[0].assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.today_week_eyebrow))[0].assertExists()
        shot("01-heute-en")

        compose.onAllNodesWithContentDescription(settings)[0].performClick()
        settle()
        compose.onAllNodesWithText(context.getString(R.string.more_language_title))[0].performClick()
        settle()
        compose.onAllNodesWithText(context.getString(R.string.more_language_hint))[0].assertExists()
        assertEquals(
            1,
            compose.onAllNodesWithText("If you don’t choose a language", substring = true)
                .fetchSemanticsNodes().size,
        )
        shot("45-einstellungen-sprache-en")
    }

    /** Mehr → Sprache: Auswahl System/Deutsch/English und die beiden Hinweise. */
    @Test
    fun spracheSeite() {
        start()
        compose.onAllNodesWithContentDescription("Einstellungen", substring = true)[0].performClick()
        settle()
        compose.onAllNodesWithText("Sprache")[0].performClick()
        settle()
        shot("45-einstellungen-sprache")
    }

    /** Die deutschen Bilder laufen wirklich deutsch — sonst braechen die Klicks auf Texte. */
    @Test
    fun spracheIstDeutsch() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        start()
        assertEquals(AppLanguage.DE, AppServices.appLanguage.value)
    }

    /** Losfahren-Dialog: Hauptaktion gefuellt, Alternative umrandet, Abbrechen als Text. */
    @Test
    fun losfahren() {
        start()
        compose.onAllNodesWithContentDescription("Aufzeichnung starten")[0].performClick()
        settle()
        shot("18-losfahren")
    }

    /**
     * „Warum?"-Blatt und Losfahren-Dialog auf Englisch — die beiden Stellen
     * mit den laengsten Saetzen von „Heute" (`05-warum-en`, `18-losfahren-en`).
     */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun heuteEnglischDetails() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        start()
        val context = compose.activity
        compose.onAllNodesWithText(context.getString(R.string.today_hero_why_action))[0].performClick()
        settle()
        compose.onAllNodesWithText(context.getString(R.string.today_signal_sleep_label))[0].assertExists()
        compose.onAllNodesWithText(context.getString(R.string.today_signal_load_label))[0].assertExists()
        shot("05-warum-en")
        compose.onAllNodesWithText(context.getString(R.string.today_why_sheet_dismiss_action))[0].performClick()
        settle()

        recordButton().performClick()
        settle()
        assertEquals("Start riding", context.getString(R.string.today_ready_title))
        compose.onAllNodesWithText(context.getString(R.string.today_ready_title))[0].assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.today_ready_free_body))[0].assertIsDisplayed()
        shot("18-losfahren-en")
    }

    /**
     * „Heute" auf Englisch auf einem schmalen Geraet (360 dp) mit Samsungs
     * erster Vergroesserungsstufe — findet abgeschnittene englische
     * Beschriftungen im Hero-Knopf, im Wochenstreifen und in der Zielzeile.
     */
    @Test
    @Config(qualifiers = "${TestLocales.EN}-w360dp-h1400dp-xxhdpi")
    fun heuteEnglischSchmal() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        RuntimeEnvironment.setFontScale(1.15f)
        start()
        // Montags bis mittwochs steht der Wochenrueckblick oben auf „Heute"
        // und schiebt die Zielzeile aus dem Bild — erst hinrollen, dann
        // pruefen. Ohne das war der Test vom Wochentag abhaengig.
        val zielzeile = compose.activity.getString(R.string.today_goal_eyebrow)
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(zielzeile))
        settle()
        compose.onAllNodesWithText(zielzeile)[0].assertIsDisplayed()
        shot("12-heute-schmal-en")
    }

    /**
     * Erststart auf Englisch: keine gefahrene Tour — die erste Runde im
     * Hero-Knopf („Build loop · 26 km") und die Einladung zum Import.
     */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun heuteEnglischErststart() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        sampleRides().filterNot { it.planned }.forEach { AppServices.rideStorage.deleteRide(it.id) }
        start()
        val context = compose.activity
        assertEquals("No rides yet", context.getString(R.string.today_first_ride_title))
        compose.onAllNodesWithText(context.getString(R.string.today_first_ride_title))[0].assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.today_first_ride_import_action))[0].assertExists()
        shot("19-heute-erststart-en")
    }

    /**
     * Ohne Trainingsplan: „Diese Woche" bekommt trotzdem ein Ziel — das
     * mitwachsende Wochenziel aus den Touren, mit Balken und einem ruhigen
     * Satz darunter (oder, falls die Beispieltouren es diese Woche schon
     * erfuellen, der Hinweis, dass alles Weitere Zugabe ist).
     */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun heuteOhnePlanWochenzielEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        // `AppServices` lebt ueber die Tests der Klasse hinweg — der Plan aus
        // `seed()` muss ausdruecklich weg.
        savePlan(AppServices.trainingPlanStore, null)
        start()
        val context = compose.activity
        compose.onAllNodesWithText(context.getString(R.string.today_week_eyebrow))[0].assertExists()
        compose.onAllNodesWithContentDescription("Weekly goal:", substring = true)[0].assertExists()
        // Der Satz hangt von der Woche ab; eine der beiden Formen muss da sein.
        val caption = compose.onAllNodesWithText("to go", substring = true).fetchSemanticsNodes().size
        val done = compose.onAllNodesWithText(context.getString(R.string.progress_week_goal_done_hint))
            .fetchSemanticsNodes().size
        assertTrue("Wochenziel-Satz fehlt", caption + done > 0)
        shot("14-heute-wochenziel-en")
    }

    /**
     * Der schwebende Aufnahme-Knopf. Seine Beschreibung gehoert dem Bereich
     * der App-Huelle (`RecCapsuleButton.kt`): Solange dort noch der deutsche
     * Text steht, wird nach ihm gesucht; sobald die Huelle ihre eigene
     * Ressource `shell_rec_start_cd` mitbringt, nach deren Wert. Der Schluessel
     * wird per Name aufgeloest, damit dieser Test ohne den Huellen-Zweig
     * kompiliert und nach dem Zusammenfuehren nicht an einem geaenderten
     * englischen Text der Huelle bricht.
     */
    @Suppress("DiscouragedApi")
    private fun recordButton(): SemanticsNodeInteraction {
        val context = compose.activity
        val shellKey = context.resources.getIdentifier("shell_rec_start_cd", "string", context.packageName)
        val matcher = if (shellKey != 0) {
            hasContentDescription(context.getString(shellKey))
        } else {
            hasContentDescription("Aufzeichnung starten")
        }
        return compose.onAllNodes(matcher)[0]
    }

    private fun start(dark: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
    }

    /**
     * Tippt einen Tab der Navigationsleiste an. Sie liegt ueber dem Inhalt,
     * ist also der letzte Treffer — der erste waere etwa der Seitentitel
     * „Verlauf" oder ein gleichlautendes Wort im Inhalt.
     */
    private fun tab(label: String) {
        compose.onAllNodesWithText(label).onLast().performClick()
        settle()
    }

    /** Wechselt die Sicht eines Tabs (Segmente unter dem Titel von „Verlauf" und „Indoor"). */
    private fun segment(label: String) {
        compose.onAllNodesWithText(label)[0].performClick()
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun frames(name: String) {
        for (ms in listOf(60L, 150L, 240L, 400L)) {
            compose.mainClock.advanceTimeBy(if (ms == 60L) 60L else if (ms == 400L) 160L else 90L)
            shot("$name-${ms}ms")
        }
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}

/** Minimale Application: nur die Dienste, ohne Hintergrundarbeit. */
class ScreenshotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val WEEK_MS = 7 * DAY_MS
/**
 * Bewusst die echte Uhrzeit und KEIN fester Zeitpunkt: Die App liest die Zeit
 * an vielen Stellen selbst (`System.currentTimeMillis()`, ohne einschleusbare
 * Uhr), und Robolectric friert nur `SystemClock` ein, nicht die Wanduhr. Ein
 * fester Wert hier liesse Beispieldaten und App-Uhr auseinanderlaufen — der
 * Plan laege irgendwann in der Vergangenheit, die Touren in der Zukunft.
 *
 * Fuer die CI trotzdem stabil, weil alle Daten RELATIV zu [NOW] liegen: Das
 * Ziel liegt immer genau acht Wochen voraus (also immer ein Plan mit 9
 * Wochen, weit weg von MIN_WEEKS/MAX_WEEKS), die Touren immer 2 bis 32 Tage
 * zurueck, und jeder angetippte Text („Warum diese Empfehlung?",
 * „Feierabendrunde", die Tab-Namen) steht unabhaengig vom Wochentag da.
 * Was sich von Tag zu Tag aendert, ist nur der INHALT der Bilder (Datum,
 * Tagesempfehlung) — fuer die Sichtpruefung gewollt, fuer einen spaeteren
 * Pixelvergleich aber erst mit einer einschleusbaren App-Uhr loesbar.
 */
private val NOW = System.currentTimeMillis()

private val SAMPLE_GOAL = Goal(
    name = "Alb-Gold",
    distanceKm = 60.0,
    ascentM = 700.0,
    date = NOW + 8 * WEEK_MS,
    targetDurationMin = 130,
)

private const val HIGHLIGHT_RIDE_ID = "highlight-today"

/**
 * Fuer [ScreenshotTest.karteTourHighlights]: je eine 30-km-Tour am Mittwoch
 * der drei Vorwochen und die gerade beendete Tour von heute (82,4 km). Alles
 * relativ zum Kalendertag in der Zeitzone des Geraets — dieselbe Umrechnung
 * wie in der App —, damit „diese Woche" in jedem Fall nur die neue Tour hat.
 */
private fun highlightRides(): List<Ride> {
    val zone = java.time.ZoneId.systemDefault()
    val monday = java.time.Instant.ofEpochMilli(NOW).atZone(zone).toLocalDate().with(java.time.DayOfWeek.MONDAY)
    fun ride(id: String, name: String, startMs: Long, km: Double, ascentM: Double, lat: Double): Ride {
        val durationS = (km / 23.0 * 3600).toInt()
        return Ride(
            id = id,
            name = name,
            createdAt = startMs,
            stats = RideStats(
                distanceKm = km,
                ascentM = ascentM,
                descentM = ascentM,
                durationS = durationS,
                movingTimeS = durationS,
                avgSpeedKmh = 23.0,
            ),
            points = List(60) { i ->
                val t = i / 59.0 * 2 * Math.PI
                TrackPoint(
                    lat = lat + 0.03 * sin(t),
                    lon = 9.05 + 0.05 * cos(t),
                    ele = 500.0 + 30 * sin(2 * t),
                    time = startMs + durationS * 1000L * i / 59,
                )
            },
        )
    }
    val earlier = (1..3).map { weeksAgo ->
        val wednesdayNoon = monday.minusWeeks(weeksAgo.toLong()).plusDays(2).atTime(12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        ride("highlight-week-$weeksAgo", "Mittwochsrunde $weeksAgo", wednesdayNoon, 30.0, 270.0, 48.40)
    }
    // Die neue Tour hat weniger Hoehenmeter als die Vorwochen (kein zweiter
    // Bestwert, der die Wochenziel-Zeile verdraengte) und eine andere Spur
    // (kein Rundenvergleich).
    return earlier + ride(HIGHLIGHT_RIDE_ID, "Tour heute", NOW, 82.4, 200.0, 48.60)
}

/** Sechs gefahrene Touren der letzten Wochen, jede mit einer kleinen Schleife als Spur. */
private fun sampleRides(): List<Ride> {
    val specs = listOf(
        Triple("Feierabendrunde", 2, 32.4),
        Triple("GA1 Kanalrunde", 4, 46.1),
        Triple("Schotterschleife Nord", 12, 61.0),
        Triple("Waldkante West", 16, 28.7),
        Triple("Intervalle am Deich", 28, 38.9),
        Triple("Sonntagsrunde", 32, 55.2),
    )
    return specs.mapIndexed { index, (name, daysAgo, km) ->
        val start = NOW - daysAgo * DAY_MS
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
            id = "sample-$index",
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
        id = "sample-planned",
        name = "Alb-Runde über Hayingen",
        createdAt = NOW - DAY_MS,
        stats = RideStats(distanceKm = 58.3, ascentM = 640.0, descentM = 640.0),
        planned = true,
        points = List(80) { i ->
            val t = i / 79.0 * 2 * Math.PI
            TrackPoint(lat = 48.30 + 0.04 * sin(t), lon = 9.40 + 0.06 * cos(t), ele = 700.0 + 60 * sin(t))
        },
    )
}
