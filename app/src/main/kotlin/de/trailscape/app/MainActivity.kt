package de.trailscape.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import de.trailscape.app.feedback.CrashReportPrompt
import de.trailscape.app.health.HealthPermissionRequester
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.theme.TrailscapeTheme

class MainActivity : ComponentActivity() {

    /**
     * Registriert den Health-Connect-Berechtigungsdialog.
     *
     * Bewusst als Feld: Der Feldinitialisierer laeuft noch im Konstruktor,
     * `registerForActivityResult` wirft dagegen, sobald die Activity gestartet
     * ist. Der Requester meldet sich selbst beim
     * [de.trailscape.app.health.HealthPermissionHub] an und beim Zerstoeren
     * wieder ab; benutzt wird er vom `HealthConnectGateway`, das die Activity
     * seinerseits nicht kennt.
     */
    @Suppress("unused")
    private val healthPermissionRequester = HealthPermissionRequester(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TrailscapeApp()
                    // Liegt neben der Navigationshuelle, nicht darin: Der
                    // Absturz-Dialog gehoert der Activity (siehe
                    // Zustaendigkeits-KDoc in ui/TrailscapeApp.kt). Ein
                    // AlertDialog zeichnet in ein eigenes Fenster und belegt
                    // im Layout keinen Platz; ohne liegengebliebenen Bericht
                    // gibt dieses Composable gar nichts aus.
                    CrashReportPrompt()
                }
            }
        }
    }
}
