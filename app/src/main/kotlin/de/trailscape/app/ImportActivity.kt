package de.trailscape.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import de.trailscape.app.ui.readActivityFiles
import kotlinx.coroutines.launch

/**
 * Trampolin fuer „Teilen an Trailscape" und „Oeffnen mit Trailscape" (GPX
 * und FIT aus Komoot, Strava, Dateimanager, Mailanhang).
 *
 * Durchsichtig und ohne eigenes UI: Sie liest die gereichten Dateien ein,
 * legt sie in [PendingImports] ab, holt die [MainActivity] nach vorn und ist
 * wieder weg. Den eigentlichen Import (Erkennung, Duplikatpruefung,
 * Speichern, Snackbar) macht das `AppViewModel` — derselbe Weg wie die
 * Mehrfachauswahl im App-Dialog.
 *
 * ## Warum eine eigene Activity statt Filter an der MainActivity
 *  * Die Leseberechtigung einer geteilten URI gilt nur, solange die
 *    empfangende Activity lebt. Hier wird deshalb **sofort** gelesen, bevor
 *    irgendetwas anderes passiert.
 *  * Geteilt wird in den Task der teilenden App. Die MainActivity dort ein
 *    zweites Mal zu starten ergaebe eine zweite App-Instanz neben der
 *    laufenden; das Trampolin springt stattdessen in den Trailscape-Task.
 *
 * ## Schutz gegen Doppelimport
 *  * `configChanges` im Manifest: Eine Drehung erzeugt die Activity nicht
 *    neu, das Lesen laeuft einfach weiter.
 *  * Wird sie doch neu erzeugt (Prozesstod, Start aus dem Verlauf), liest sie
 *    nicht erneut — die Dateien sind entweder schon uebergeben oder die
 *    Berechtigung ohnehin verfallen.
 *  * Dieselben URIs zweimal kurz hintereinander (Doppeltipp auf „Oeffnen")
 *    laufen nur einmal durch ([inFlight]); ein spaeteres erneutes Teilen
 *    faengt die Duplikatpruefung ab.
 */
class ImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState != null || launchedFromHistory) {
            finish()
            return
        }

        val sources = importSourcesFromIntent(intent)
        if (sources.isEmpty()) {
            Toast.makeText(this, "Keine Datei zum Importieren gefunden.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val key = sources.map { it.uri.toString() }.toSet()
        if (!enter(key)) {
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val files = readActivityFiles(
                    context = this@ImportActivity,
                    uris = sources.map { it.uri },
                    mimeTypes = sources.associate { it.uri to it.mimeType },
                )
                PendingImports.offer(files)
                startActivity(
                    Intent(this@ImportActivity, MainActivity::class.java)
                        .putExtra(EXTRA_IMPORT_PENDING, true)
                        // In den bestehenden Trailscape-Task springen und dort
                        // die laufende MainActivity wiederverwenden
                        // (onNewIntent), statt eine zweite zu stapeln.
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        ),
                )
            } finally {
                leave(key)
                finish()
            }
        }
    }

    private companion object {
        /** URI-Saetze, die gerade gelesen werden — siehe Klassen-KDoc. */
        private val inFlight = mutableSetOf<Set<String>>()

        @Synchronized
        fun enter(key: Set<String>): Boolean = inFlight.add(key)

        @Synchronized
        fun leave(key: Set<String>) {
            inFlight.remove(key)
        }
    }
}
