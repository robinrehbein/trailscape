package de.trailscape.app.ui.map

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.swipeDown
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Absturzbericht 2.0.160: Blatt angefasst, gezogen, losgelassen — NPE in
 * `AnchoredDraggable.computeTarget`, weil das Blatt noch keine Anker hatte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SwipeableSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun wischenOhneKoerperStuerztNichtAb() {
        var expanded = false
        compose.setContent {
            SwipeableSheet(
                expanded = false,
                onExpandedChange = { expanded = it },
                peek = { Text("Kopf") },
                body = {},
            )
        }
        compose.onNodeWithText("Kopf").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertFalse(expanded)
    }

    @Test
    fun hochwischenKlapptAuf() {
        var expanded = false
        compose.setContent {
            SwipeableSheet(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                peek = { Text("Kopf") },
                body = { Spacer(Modifier.height(200.dp)) },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Kopf").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(expanded)
    }

    /**
     * Geraetebericht: Ueber einer scrollenden Liste im Blatt (Vorschlaege,
     * Planung) liess sich das Blatt nicht wischen — die Liste schluckte die
     * Geste. Jetzt zieht Hochwischen ueber der Liste das Blatt auf und
     * Runterwischen es wieder zu.
     */
    @Test
    fun wischenUeberScrollenderListeBewegtDasBlatt() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            SwipeableSheet(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                peek = {
                    Column(
                        Modifier
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("liste"),
                    ) {
                        repeat(10) { Text("Zeile $it", Modifier.height(40.dp)) }
                    }
                },
                body = {
                    Column(
                        Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        repeat(10) { Text("Koerper $it", Modifier.height(40.dp)) }
                    }
                },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("liste").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(expanded)

        compose.onNodeWithTag("liste").performTouchInput { swipeDown() }
        compose.onNodeWithTag("liste").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertFalse(expanded)
    }
}
