package de.trailscape.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import de.trailscape.app.feedback.CrashReportPrompt
import de.trailscape.app.health.HealthPermissionRequester
import de.trailscape.app.reminder.EXTRA_OPEN_TODAY
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
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
        showTodayIfRequested(intent)
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

    /**
     * Die App laeuft schon und wird von einer Erinnerung nach vorne geholt.
     * `setIntent` ist noetig, damit spaetere Zugriffe auf [getIntent] den
     * neuen Intent sehen und nicht den vom Kaltstart.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showTodayIfRequested(intent)
    }

    /**
     * Tippen auf eine Erinnerung soll den „Heute"-Tab zeigen — auch dann, wenn
     * die App zuletzt auf einem anderen Tab stand (siehe
     * [de.trailscape.app.reminder.ReminderNotifications]).
     *
     * Umgesetzt ueber denselben Weg, den auch die Screens untereinander
     * benutzen: eine Bitte an die Navigationshuelle
     * ([AppViewModel.requestTab]), die `TrailscapeApp()` beobachtet. Das
     * [AppViewModel] wird hier ueber die Activity als Store-Owner geholt —
     * dieselbe Instanz, die `viewModel()` in der Komposition liefert, kein
     * zweites Exemplar.
     */
    private fun showTodayIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_TODAY, false) != true) return
        ViewModelProvider(this)[AppViewModel::class.java].requestTab(AppTab.HOME)
    }
}
