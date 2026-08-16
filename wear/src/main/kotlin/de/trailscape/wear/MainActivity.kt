package de.trailscape.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.MaterialTheme
import de.trailscape.wear.ui.RecordingScreen

/**
 * Einziger Bildschirm der App.
 *
 * [MaterialTheme] stammt aus `androidx.wear.compose.material3` — im ganzen
 * Modul gibt es keinen einzigen Import aus `androidx.compose.material3`. Die
 * beiden Bibliotheken teilen sich Klassennamen, aber weder Themes noch
 * Abmessungen; sie zu mischen fuehrt laut Google zu unvorhersehbarem
 * Verhalten, und auf einem runden 45-mm-Display faellt das sofort auf.
 *
 * Kein eigenes `ColorScheme`: Wear Compose Material3s Standardschema ist
 * bereits der dunkle Grund, den diese App will (siehe `ui/theme/WearColors.kt`);
 * die einzige App-eigene Farbe (`AccentGreen`) setzen die Bildschirme dort,
 * wo sie sie brauchen, statt das ganze Schema zu ueberschreiben.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RecordingScreen()
            }
        }
    }
}
