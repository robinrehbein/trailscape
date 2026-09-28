package de.trailscape.app.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import de.trailscape.app.i18n.AppLocale
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.feedback.ISSUE_REPOSITORY_URL
import de.trailscape.app.ui.theme.TrailscapeTheme

/**
 * Die Datenschutzerklaerung als Adresse — dieselbe Datei, die „Mehr → Über →
 * Datenschutz" oeffnet (`PRIVACY.md` im Repository).
 */
const val HEALTH_PRIVACY_URL: String = "$ISSUE_REPOSITORY_URL/blob/main/PRIVACY.md"

/**
 * Begruendung der Health-Connect-Leserechte.
 *
 * Health Connect verlangt, dass eine lesende App erklaert, wozu sie die Daten
 * braucht, und ruft dafuer
 *
 *  * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (bis Android 13, Link
 *    „Datenschutzerklärung" im Berechtigungsdialog) bzw.
 *  * `android.intent.action.VIEW_PERMISSION_USAGE` mit Kategorie
 *    `HEALTH_PERMISSIONS` (ab Android 14, ueber das `activity-alias`)
 *
 * auf. Frueher zeigten beide auf die `MainActivity`, die den Intent gar nicht
 * auswertete — wer im Dialog auf den Link tippte, landete kommentarlos in der
 * Karte. Diese eigene, schlanke Activity zeigt stattdessen die Kurzfassung aus
 * `PRIVACY.md` und bietet den Volltext im Browser an. Sie ist bewusst
 * unabhaengig vom `AppViewModel`: Health Connect startet sie in eigener
 * Aufgabe, ohne dass die App sonst laufen muss.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {

    /** App-Sprache als Locale-Delta, siehe [AppLocale]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLocale.overrideConfiguration(newBase)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.more_health_rationale_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        RATIONALE_PARAGRAPHS.forEach { paragraph ->
                            Text(stringResource(paragraph), style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(
                            onClick = { openPrivacyPolicy() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.more_health_rationale_privacy_action)) }
                        TextButton(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.common_action_close)) }
                    }
                }
            }
        }
    }

    private fun openPrivacyPolicy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HEALTH_PRIVACY_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, HEALTH_PRIVACY_URL, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        /** Kurzfassung von `PRIVACY.md`, Abschnitte „Kurz gesagt" und 3. */
        val RATIONALE_PARAGRAPHS = listOf(
            R.string.more_health_rationale_intro_body,
            R.string.more_health_rationale_workouts_body,
            R.string.more_health_rationale_heart_rate_body,
            R.string.more_health_rationale_recovery_body,
            R.string.more_health_rationale_history_body,
            R.string.more_health_rationale_sync_body,
        )
    }
}
