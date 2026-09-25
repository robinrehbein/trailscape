package de.trailscape.app.ui.map

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import de.trailscape.app.ui.components.OneUiSearchField
import de.trailscape.core.GeoResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Ortssuche fragt Nominatim erst beim **Absenden**, nie beim Tippen —
 * die Nominatim-Richtlinie verbietet Autovervollstaendigung (siehe KDoc in
 * `SearchSheet.kt`).
 *
 * Geprueft wird die Verdrahtung, an der das haengt: Tippen landet nur im
 * Text-Rueckruf, die Suchtaste der Tastatur und die Zeile „„…" suchen" im
 * Such-Rueckruf. Dazu der Zustand, auf dem der Karten-Screen seine Suche
 * aufbaut ([PlaceSearchState] mit [PlaceSearchEffect]): Nur eine Abgabe
 * loest eine Anfrage aus, Tippen nie — auch dann nicht, wenn jemand den
 * Effekt wieder an den Suchtext haengen wollte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaceSearchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tippenLoestKeineSucheAus() {
        var searches = 0
        compose.setContent {
            var text by remember { mutableStateOf("") }
            OneUiSearchField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Wohin?",
                onSearch = { searches++ },
            )
        }
        compose.onNode(hasSetTextAction()).performTextInput("Tübingen")
        compose.waitForIdle()
        assertEquals(0, searches)
    }

    @Test
    fun suchtasteDerTastaturLoestGenauEineSucheAus() {
        var searches = 0
        compose.setContent {
            var text by remember { mutableStateOf("") }
            OneUiSearchField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Wohin?",
                onSearch = { searches++ },
            )
        }
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("Tübingen")
        field.performImeAction()
        compose.waitForIdle()
        assertEquals(1, searches)
    }

    @Test
    fun trefferlisteBietetDasAbsendenSichtbarAn() {
        var searches = 0
        compose.setContent {
            PlaceResults(
                query = "Tübingen",
                error = null,
                results = emptyList(),
                history = emptyList(),
                onSelect = {},
                onSearch = { searches++ },
            )
        }
        compose.onNodeWithText("„Tübingen“ suchen").performClick()
        compose.waitForIdle()
        assertEquals(1, searches)
    }

    @Test
    fun zuKurzeEingabenZeigenDenHinweisStattDerSuchzeile() {
        compose.setContent {
            PlaceResults(
                query = "Ul",
                error = null,
                results = emptyList(),
                history = emptyList(),
                onSelect = {},
                onSearch = {},
            )
        }
        compose.onNodeWithText("Mindestens $MIN_PLACE_SEARCH_LENGTH Zeichen eingeben, dann suchen.")
            .assertExists()
        compose.onNodeWithText("„Ul“ suchen").assertDoesNotExist()
    }

    @Test
    fun nachEinemFehlerHeisstDieZeileErneutSuchen() {
        compose.setContent {
            PlaceResults(
                query = "Tübingen",
                error = "Keine Verbindung zum Server.",
                results = emptyList(),
                history = emptyList(),
                onSelect = {},
                onSearch = {},
            )
        }
        compose.onNodeWithText("Erneut suchen").assertExists()
    }

    @Test
    fun derSuchzustandFragtNurBeimAbsenden() {
        val state = PlaceSearchState()
        val asked = mutableListOf<String>()
        compose.setContent {
            PlaceSearchEffect(state = state, maxResults = 5) { query ->
                asked += query
                listOf(GeoResult(displayName = "$query, Deutschland", lat = 48.5, lon = 9.05))
            }
        }

        // Tippen, Buchstabe fuer Buchstabe — keine einzige Anfrage.
        for (end in 1.."Tübingen".length) {
            compose.runOnIdle { state.changeQuery("Tübingen".take(end)) }
            compose.mainClock.advanceTimeBy(1_000)
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), asked)

        // Absenden: genau eine Anfrage, mit dem getrimmten Text.
        compose.runOnIdle { state.changeQuery(" Tübingen ") }
        compose.runOnIdle { assertTrue(state.submit()) }
        compose.waitForIdle()
        assertEquals(listOf("Tübingen"), asked)
        assertEquals(1, state.results.size)

        // Weitertippen verwirft die Treffer, fragt aber nicht neu.
        compose.runOnIdle { state.changeQuery("Tübingen Altstadt") }
        compose.waitForIdle()
        assertEquals(listOf("Tübingen"), asked)
        assertEquals(emptyList<GeoResult>(), state.results)

        // Zu kurz abgesendet: Meldung statt Anfrage.
        compose.runOnIdle { state.changeQuery("Ul") }
        compose.runOnIdle { assertFalse(state.submit()) }
        compose.waitForIdle()
        assertEquals(listOf("Tübingen"), asked)
        assertNotNull(state.error)
    }

    @Test
    fun zuKurzeEingabenGehenNichtAnNominatim() {
        assertNull(placeSearchQueryOrNull("  Ul  "))
        assertEquals("Ulm", placeSearchQueryOrNull("  Ulm "))
    }
}
