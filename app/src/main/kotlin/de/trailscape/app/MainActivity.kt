package de.trailscape.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import de.trailscape.app.indoor.background.IndoorRideHost
import de.trailscape.app.indoor.background.IndoorRideService
import kotlinx.coroutines.launch
import de.trailscape.app.feedback.CrashReportPrompt
import de.trailscape.app.health.HealthPermissionRequester
import de.trailscape.app.i18n.AppLocale
import de.trailscape.app.reminder.EXTRA_OPEN_TODAY
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.IndoorDestination
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.app.ui.training.PlanRecipeImportHost
import androidx.lifecycle.viewmodel.compose.viewModel

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

    /**
     * Setzt die App-Sprache als reines Locale-Delta, bevor irgendeine
     * Ressource gelesen wird (siehe [AppLocale]).
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLocale.overrideConfiguration(newBase)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppLocale.refresh(this)
        showTodayIfRequested(intent)
        importIfRequested(intent)
        openIndoorIfRequested(intent, appStart = savedInstanceState == null)
        collectIndoorSavedRides()
        planRecipeIfRequested(intent)
        challengesIfRequested(intent)
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
                    // Vorschau und Bestaetigung geoeffneter Plan-Rezepte
                    // (siehe PlanRecipeImportActivity); ohne offenes Rezept
                    // gibt dieses Composable nichts aus.
                    PlanRecipeImportHost(viewModel())
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
        importIfRequested(intent)
        openIndoorIfRequested(intent, appStart = false)
        planRecipeIfRequested(intent)
        challengesIfRequested(intent)
    }

    /**
     * „Trailscape öffnen" nach dem Speichern einer Herausforderung von aussen
     * ([de.trailscape.app.challenge.ChallengeImportActivity]): gleich zum
     * Segment „Herausforderungen" im Tab „Indoor". Das Extra wird entfernt,
     * damit eine Drehung nichts doppelt tut.
     */
    private fun challengesIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(de.trailscape.app.challenge.EXTRA_OPEN_CHALLENGES, false) != true) return
        intent.removeExtra(de.trailscape.app.challenge.EXTRA_OPEN_CHALLENGES)
        ViewModelProvider(this)[AppViewModel::class.java].requestChallenges()
    }

    /**
     * Zurueck in die laufende Indoor-Fahrt (docs/indoor/hintergrund.md): aus
     * der Benachrichtigung des [IndoorRideService] und beim App-Start, wenn
     * im Hintergrund noch eine Fahrt laeuft. Ohne Fahrt fuehrt das Extra auf
     * die Indoor-Startseite (z. B. nach „Fahrt gespeichert"). Touren, die
     * ohne Oberflaeche gespeichert wurden, landen ueber [IndoorRideHost.savedRides]
     * in der Tourenliste.
     */
    private fun openIndoorIfRequested(intent: Intent?, appStart: Boolean) {
        val host = IndoorRideHost.shared
        val requested = intent?.getBooleanExtra(IndoorRideService.EXTRA_OPEN_INDOOR, false) == true
        if (requested) intent?.removeExtra(IndoorRideService.EXTRA_OPEN_INDOOR)
        if (requested || (appStart && host.isRiding)) {
            ViewModelProvider(this)[AppViewModel::class.java].requestIndoor(IndoorDestination.FLOW)
        }
    }

    /** Je Activity-Instanz (auch nach einer Drehung): ohne Oberflaeche gespeicherte Indoor-Touren nachladen. */
    private fun collectIndoorSavedRides() {
        val appViewModel = ViewModelProvider(this)[AppViewModel::class.java]
        lifecycleScope.launch { IndoorRideHost.shared.savedRides.collect { appViewModel.onIndoorRideSaved(it) } }
    }

    /**
     * Die [PlanRecipeImportActivity] hat ein Plan-Rezept gelesen und in
     * [de.trailscape.app.ui.training.PendingPlanRecipe] abgelegt. Hier geht es
     * nur zur Sicht „Plan" (Tab „Indoor"); die Vorschau zeigt der Host im
     * Compose-Inhalt.
     * Das Extra wird entfernt, damit eine Drehung nichts doppelt tut.
     */
    private fun planRecipeIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_PLAN_RECIPE_PENDING, false) != true) return
        intent.removeExtra(EXTRA_PLAN_RECIPE_PENDING)
        ViewModelProvider(this)[AppViewModel::class.java].requestPlan()
    }

    /**
     * Die [ImportActivity] hat geteilte bzw. geoeffnete Dateien eingelesen und
     * in [PendingImports] abgelegt — hier gehen sie an das [AppViewModel], das
     * importiert und in den Verlauf springt.
     *
     * [PendingImports.take] leert die Ablage, und das Extra wird entfernt:
     * Eine Drehung oder die Wiederherstellung nach einem Prozesstod ruft
     * `onCreate` mit demselben Intent erneut auf und darf nicht noch einmal
     * importieren.
     */
    private fun importIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_IMPORT_PENDING, false) != true) return
        intent.removeExtra(EXTRA_IMPORT_PENDING)
        val files = PendingImports.take()
        if (files.isEmpty()) return
        ViewModelProvider(this)[AppViewModel::class.java].importActivityFiles(files, openInHistory = true)
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
