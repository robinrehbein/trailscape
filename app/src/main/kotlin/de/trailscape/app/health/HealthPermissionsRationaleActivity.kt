package de.trailscape.app.health

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.unit.dp
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
                        Text("Gesundheitsdaten in Trailscape", style = MaterialTheme.typography.headlineSmall)
                        RATIONALE_PARAGRAPHS.forEach { paragraph ->
                            Text(paragraph, style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(
                            onClick = { openPrivacyPolicy() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Vollständige Datenschutzerklärung") }
                        TextButton(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Schließen") }
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
            "Trailscape liest Gesundheitsdaten ausschließlich aus Health Connect und " +
                "schreibt nichts zurück. Alles wird nur auf diesem Gerät verarbeitet; " +
                "es gibt keinen Trailscape-Server, und der Entwickler hat keinen Zugriff " +
                "auf deine Daten.",
            "Trainings, Trainingsrouten, Distanz und Kalorien: um die Fahrten deiner " +
                "Uhr als Touren zu importieren.",
            "Herzfrequenz: für Puls-Kennzahlen deiner Touren und — falls deine Uhr " +
                "keinen Ruhepuls liefert — für einen aus dem Nacht-Puls abgeleiteten " +
                "Ruhepuls.",
            "Ruhepuls, HRV, Schlaf und VO₂max: für die Erholungs- und " +
                "Formberechnung (Readiness, Trainingsempfehlung).",
            "Verlauf älter als 30 Tage (optional): damit die Baselines für Ruhepuls " +
                "und HRV nicht erst nach Wochen stehen.",
            "Die Daten verlassen das Gerät nur, wenn du selbst den optionalen Sync " +
                "mit deinem eigenen Server eingerichtet hast.",
        )
    }
}
