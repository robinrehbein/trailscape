package de.trailscape.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.theme.TrailscapeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TrailscapeApp()
                }
            }
        }
    }
}
