package de.trailscape.app.feedback

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * Die beiden Wege, auf denen ein Bericht das Geraet verlassen kann — **beide
 * ausschliesslich auf Knopfdruck des Nutzers**.
 *
 *  1. [openIssueInBrowser] oeffnet das GitHub-Formular mit vorbefuelltem Titel
 *     und Text. Abgeschickt wird es erst dort, vom Nutzer, mit GitHub-Konto.
 *  2. [shareReportText] uebergibt den Text ans System-Teilen-Menue (Mail,
 *     Messenger, Notizen …). Das ist der Fallback fuer alle ohne
 *     GitHub-Konto — und der Weg fuer Berichte, die fuer einen Link zu lang
 *     sind (siehe [ISSUE_BODY_MAX_CHARS]).
 *
 * Trailscape sendet von sich aus nichts.
 */

private const val TAG = "ReportSharing"

/**
 * Oeffnet `issues/new` mit vorbefuelltem Formular im Browser.
 *
 * @return `false`, wenn kein Browser da ist — der Aufrufer zeigt dann einen
 *   Hinweis und kann auf [shareReportText] verweisen.
 */
fun openIssueInBrowser(context: Context, title: String, body: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(buildIssueUrl(title, body)))
        .withNewTaskIfNeeded(context)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Kein Browser fuer den GitHub-Link gefunden", e)
        false
    }
}

/**
 * Reicht den Bericht als Klartext ans System-Teilen-Menue weiter
 * (`ACTION_SEND`, `text/plain`) — ohne Datei und damit ohne FileProvider.
 *
 * @return `false`, wenn keine App zum Teilen bereitsteht.
 */
fun shareReportText(context: Context, subject: String, text: String): Boolean {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(sendIntent, subject).withNewTaskIfNeeded(context)
    return try {
        context.startActivity(chooser)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Keine App zum Teilen gefunden", e)
        false
    }
}

/**
 * `startActivity` von einem Nicht-Activity-Context (z. B. dem
 * Application-Context) verlangt `FLAG_ACTIVITY_NEW_TASK`. In Compose ist
 * `LocalContext.current` zwar in aller Regel die Activity — der Aufrufer
 * koennte aber auch ein anderer sein, und der Flag kostet nichts.
 */
private fun Intent.withNewTaskIfNeeded(context: Context): Intent =
    if (context is Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Kurze Rueckmeldung aus einem Melde-Dialog heraus.
 *
 * Bewusst ein `Toast` statt der App-Snackbar (`AppViewModel.showMessage`):
 * Die Snackbar haengt am `Scaffold` eines Screens, der Dialog steht in einem
 * eigenen Fenster davor — eine Snackbar waere hinter dem Dialog-Schleier
 * bestenfalls halb zu sehen. Ein Toast liegt ueber allem.
 */
fun showFeedbackToast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
