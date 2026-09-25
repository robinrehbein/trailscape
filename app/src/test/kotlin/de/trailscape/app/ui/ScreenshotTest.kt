package de.trailscape.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import de.trailscape.app.ui.map.LONG_PRESS_HINT_CALM_MS
import de.trailscape.app.ui.map.LONG_PRESS_HINT_STORAGE_KEY
import de.trailscape.app.ui.map.LONG_PRESS_HINT_TEXT
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasSetTextAction
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.data.AppServices
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.Goal
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import de.trailscape.core.assessFitness
import de.trailscape.core.generatePlan
import de.trailscape.core.savePlan
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Screenshots der vier Tabs und der Einstellungen — das Werkzeug fuer den
 * visuellen Feinschliff ohne Geraet. Rendert die echte [TrailscapeApp] mit
 * Beispieldaten (sechs Touren, ein Ziel mit Plan) auf der JVM.
 *
 * Laeuft nur auf Wunsch:
 * `./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'`.
 * Die PNGs landen in `app/build/outputs/roborazzi/`.
 *
 * Geraet: Galaxy S25 (1080 × 2340 px, rund 411 × 891 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = ScreenshotApplication::class)
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
        val goal = Goal(
            name = "Alb-Gold",
            distanceKm = 60.0,
            ascentM = 700.0,
            date = NOW + 8 * WEEK_MS,
            targetDurationMin = 130,
        )
        savePlan(AppServices.trainingPlanStore, generatePlan(goal, assessFitness(rides), now = NOW))
    }

    @Test
    fun tabs() {
        start()
        shot("01-heute")
        tab("Verlauf")
        shot("02-verlauf")
        tab("Training")
        shot("03-training")
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
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun tall() {
        start()
        shot("11-heute-lang")
        tab("Training")
        shot("13-training-lang")
        tab("Verlauf")
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
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
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
            generatePlan(goal, assessFitness(sampleRides()), now = NOW - 3 * WEEK_MS),
        )
        start()
        tab("Training")
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
        tab("Training")
        shot("24-training-gross")
    }

    /**
     * Schmales, niedriges Geraet (360 × 640 dp) mit Samsungs erster
     * Vergroesserungsstufe (115 %): Hier reichen vier Aktionskacheln nebeneinander
     * nicht mehr fuer „Umbenennen" — sie muessen auf zwei Spalten umbrechen,
     * statt das Wort zu trennen. Dazu das Karten-Tourblatt, ob ueber ihm noch
     * Karte bleibt.
     */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
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
        // Auf 640 dp liegen die Kacheln unter dem Rand — hochgewischt.
        compose.onAllNodesWithText("Locker", substring = true)[0].performTouchInput { swipeUp() }
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
        // Hochgewischt: gespeicherte Routen und Offline-Karten.
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
        var waited = 0L
        while (compose.onAllNodesWithText(LONG_PRESS_HINT_TEXT).fetchSemanticsNodes().isEmpty() &&
            waited < LONG_PRESS_HINT_CALM_MS + 2_000
        ) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            waited += 100
        }
        compose.onAllNodesWithText(LONG_PRESS_HINT_TEXT)[0].assertIsDisplayed()
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
        tab("Training")
        shot("53-training-dunkel")
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

    private fun tab(label: String) {
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
