package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.catalog.CatalogClimbClass
import de.trailscape.core.indoor.catalog.CatalogFilter
import de.trailscape.core.indoor.catalog.CatalogLengthClass
import de.trailscape.core.indoor.catalog.CatalogProfile
import de.trailscape.core.indoor.catalog.CatalogRideKind
import de.trailscape.core.indoor.catalog.CatalogRideRecord
import de.trailscape.core.indoor.catalog.CatalogRider
import de.trailscape.core.indoor.catalog.CatalogSort
import de.trailscape.core.indoor.catalog.CatalogText
import de.trailscape.core.indoor.catalog.CatalogTimeBasis
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder der Streckenkarten (Idee 10): Liste mit Filterleiste, kein Treffer,
 * Detailansicht, Detail mit grosser Schrift. Laeuft nur mit `-Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class IndoorCatalogBrowseScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
        CatalogBrowseSession.reset()
    }

    private fun shot(name: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun profile(vararg stepsM: Pair<Int, Double>): CatalogProfile {
        val e = ArrayList<Double>()
        var h = 700.0
        e += h
        for ((n, perStep) in stepsM) repeat(n) { h += perStep; e += h }
        return CatalogProfile(stepM = 126.0, elevM = e)
    }

    private fun item(
        id: String, de: String, km: Double, hm: Double, kind: CatalogRideKind, country: String,
        surfaces: List<Pair<SurfaceKind, Double>>, state: IndoorCatalogState, profile: CatalogProfile?, mb: Long = 80,
    ) = IndoorCatalogItemUi(
        id = id, name = CatalogText(de, de), region = CatalogText("Region", "Region"),
        description = CatalogText("Eine kurze, sachliche Beschreibung der Strecke und wofür der Abschnitt bekannt ist.", "A short, factual description."),
        country = country, kind = kind, distanceKm = km, ascentM = hm, surfaces = surfaces,
        packageBytes = mb * 1024 * 1024, aerial = true, state = state, profile = profile,
        diskBytes = mb * 1024 * 1024 * 3 / 2, aerialSource = "© IGN – BD ORTHO®, Licence Ouverte Etalab (2026)",
    )

    private val items = listOf(
        item("alpe-dhuez", "Alpe d'Huez", 15.1, 1253.0, CatalogRideKind.ROAD, "FR", listOf(SurfaceKind.ASPHALT to 1.0), IndoorCatalogState.Ready, profile(100 to 10.5, 20 to 0.0)),
        item("enztal", "Enztal-Radweg", 24.0, 95.0, CatalogRideKind.ROAD, "DE", listOf(SurfaceKind.ASPHALT to 0.9, SurfaceKind.CONCRETE to 0.07, SurfaceKind.UNKNOWN to 0.03), IndoorCatalogState.NotLoaded, profile(60 to 0.3, 60 to -0.2, 60 to 0.5)),
        item("schoenbuch", "Schönbuch", 19.9, 287.0, CatalogRideKind.GRAVEL, "DE", listOf(SurfaceKind.COMPACTED to 0.44, SurfaceKind.GRAVEL to 0.34, SurfaceKind.ASPHALT to 0.22), IndoorCatalogState.NotLoaded, null),
        item("vlaamse-ardennen", "Oude Kwaremont, Paterberg und Koppenberg", 9.9, 179.0, CatalogRideKind.COBBLES, "BE", listOf(SurfaceKind.ASPHALT to 0.59, SurfaceKind.COBBLESTONE to 0.29, SurfaceKind.CONCRETE to 0.12), IndoorCatalogState.Downloading(31L shl 20, 71L shl 20), profile(30 to 2.0, 20 to -1.5, 30 to 3.0)),
    )

    private val extras = IndoorCatalogExtras(
        rider = CatalogRider(250.0, 75.0, 10.0, 0.38, 0.008, 0.97, CatalogTimeBasis.PROFILE),
        rides = listOf(CatalogRideRecord("Indoor · Alpe d'Huez", 15.1, 4980)),
    )

    @Composable
    private fun ListScreen(extras: IndoorCatalogExtras = this.extras, filterOpen: Boolean = false) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            IndoorCatalogList(
                catalog = IndoorCatalogUi(items), selectedKey = "catalog:alpe-dhuez", onSelect = {},
                extras = extras, initialFilterOpen = filterOpen,
            )
        }
    }

    @Test
    fun listeMitFilter() = shot("idee10-01-liste-filter") { ListScreen(filterOpen = true) }

    @Test
    fun listeNeutraleFahrzeit() = shot("idee10-02-liste-neutral") {
        ListScreen(extras = extras.copy(rider = CatalogRider.NEUTRAL))
    }

    @Test
    fun keinTreffer() {
        CatalogBrowseSession.set(CatalogFilter(lengths = setOf(CatalogLengthClass.LONG), climbs = setOf(CatalogClimbClass.HILLY), onlyLoaded = true))
        shot("idee10-03-kein-treffer") { ListScreen() }
    }

    @Test
    fun sortiertNachFahrzeit() {
        CatalogBrowseSession.set(CatalogFilter(sort = CatalogSort.TIME))
        shot("idee10-04-sortiert-fahrzeit") { ListScreen() }
    }

    private fun detail(row: CatalogRow, selected: Boolean = false, canRide: Boolean = true) = @Composable {
        CatalogDetailContent(
            row = row, selected = selected, canRide = canRide, onSelect = {}, onRide = {}, onDownload = {},
            onCancel = {}, onDiscard = {}, onClose = {},
        )
    }

    @Test
    fun detailGefahren() = shot("idee10-05-detail-gefahren", content = detail(catalogRows(items, extras)[0], selected = true))

    @Test
    fun detailNichtGeladen() = shot("idee10-06-detail-nicht-geladen", content = detail(catalogRows(items, extras)[1], canRide = false))

    @Test
    fun detailGrosseSchrift() = shot("idee10-07-detail-grosse-schrift", fontScale = 2f, content = detail(catalogRows(items, extras)[0]))

    @Test
    fun listeGrosseSchrift() = shot("idee10-08-liste-grosse-schrift", fontScale = 2f) { ListScreen(filterOpen = true) }
}
