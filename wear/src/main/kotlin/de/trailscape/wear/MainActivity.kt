package de.trailscape.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.MaterialTheme
import de.trailscape.wear.ui.SpikeScreen

/**
 * Einziger Bildschirm des Spikes.
 *
 * [MaterialTheme] stammt aus `androidx.wear.compose.material3` — im ganzen
 * Modul gibt es keinen einzigen Import aus `androidx.compose.material3`. Die
 * beiden Bibliotheken teilen sich Klassennamen, aber weder Themes noch
 * Abmessungen; sie zu mischen fuehrt laut Google zu unvorhersehbarem
 * Verhalten, und auf einem runden 45-mm-Display faellt das sofort auf.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SpikeScreen()
            }
        }
    }
}
