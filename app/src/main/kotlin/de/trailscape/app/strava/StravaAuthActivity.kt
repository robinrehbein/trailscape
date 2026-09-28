package de.trailscape.app.strava

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import de.trailscape.app.MainActivity
import de.trailscape.app.data.AppServices
import kotlinx.coroutines.launch

/**
 * Durchsichtiges Trampolin fuer den Ruecksprung der Strava-Anmeldung
 * (`trailscape://strava-callback?state=…&code=…&scope=…`).
 *
 * Nimmt die Query-Parameter, uebergibt sie an
 * [StravaServices.handleCallback] (prueft das Nonce und tauscht den Code —
 * auf dem App-Scope, damit das Beenden dieser Activity den Tausch nicht
 * abbricht) und holt die [MainActivity] mit `CLEAR_TOP|SINGLE_TOP` nach vorn.
 * Das schliesst den Custom Tab, der darueber lag, und die Einstellungen
 * stehen wieder auf der Seite „Strava" (ihr Zustand ist `rememberSaveable`)
 * — dort erscheint „Verbinde mit Strava …" und kurz danach das Ergebnis.
 *
 * Nimmt nichts anderes an als genau dieses Schema und diesen Host; ein
 * fremder Aufruf ohne passendes Nonce endet als „ungueltig" und verbindet
 * nichts.
 */
class StravaAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val data = intent?.data
        if (savedInstanceState != null || launchedFromHistory || !StravaConfig.available ||
            data == null || data.scheme != "trailscape" || data.host != "strava-callback"
        ) {
            finish()
            return
        }

        val params = data.queryParameterNames.associateWith { data.getQueryParameter(it) }
        AppServices.init(this)
        StravaServices.init(this)
        AppServices.appScope.launch { StravaServices.handleCallback(params) }

        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}
