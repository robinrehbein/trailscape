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
import org.junit.Assert.assertEquals
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
 * Such-Rueckruf. Der Karten-Screen startet die Anfrage ausschliesslich aus
 * letzterem (`submitSearch` → `LaunchedEffect(searchSubmission)`).
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
    fun zuKurzeEingabenGehenNichtAnNominatim() {
        assertNull(placeSearchQueryOrNull("  Ul  "))
        assertEquals("Ulm", placeSearchQueryOrNull("  Ulm "))
    }
}
