package de.trailscape.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import de.trailscape.core.ActivityFileInput

/**
 * Was ein „Teilen"- oder „Oeffnen mit"-Intent an Dateien mitbringt.
 *
 * Getrennt von der [ImportActivity], damit sich das Auslesen ohne Activity
 * (Robolectric) pruefen laesst — gerade die Faelle, die im Alltag schiefgehen:
 * Apps, die beim Einzel-Teilen nur `clipData` fuellen, oder solche, die
 * dieselbe URI in `EXTRA_STREAM` **und** `clipData` legen.
 */
data class ImportSource(val uri: Uri, val mimeType: String?)

/**
 * Liest alle Datei-URIs aus einem `ACTION_VIEW`-, `ACTION_SEND`- oder
 * `ACTION_SEND_MULTIPLE`-Intent, in Reihenfolge und ohne Doppelte. Andere
 * Aktionen liefern eine leere Liste.
 *
 * `clipData` wird immer mit ausgewertet: Seit Android 4.1 legt das System
 * die geteilten URIs dort ab, weil nur so die Leseberechtigung mitreist —
 * manche Apps fuellen **ausschliesslich** `clipData`.
 *
 * Nur `content://` und `file://` gelten; ein `http(s)`-Link (Komoot teilt
 * gern Links statt Dateien) waere eine Netzanfrage, die hier niemand
 * erwartet.
 */
fun importSourcesFromIntent(intent: Intent?): List<ImportSource> {
    if (intent == null) return emptyList()
    val found = LinkedHashMap<Uri, String?>()
    fun add(uri: Uri?, mimeType: String? = intent.type) {
        if (uri == null) return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content" && scheme != "file") return
        if (uri !in found || found[uri] == null) found[uri] = mimeType
    }
    when (intent.action) {
        Intent.ACTION_VIEW -> add(intent.data)
        Intent.ACTION_SEND -> add(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.forEach { add(it) }
        else -> return emptyList()
    }
    intent.clipData?.let { clip ->
        // Beim Mehrfach-Teilen ist `intent.type` oft nur der gemeinsame
        // Obertyp (`*/*`); der Typ je Eintrag steht dann in der ClipDescription
        // allenfalls als Liste — pro URI ist er nicht zuzuordnen.
        for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
    }
    return found.map { (uri, mime) -> ImportSource(uri, mime?.takeUnless { it == "*/*" }) }
}

/**
 * Uebergabe zwischen [ImportActivity] und [MainActivity] — ein Stapel
 * bereits **eingelesener** Dateien.
 *
 * Warum nicht als Intent-Extra: Die Bytes koennen Megabyte gross sein, ein
 * Intent vertraegt knapp ein Megabyte (TransactionTooLargeException). Und
 * warum nicht die URIs weiterreichen: Die Leseberechtigung haengt an der
 * empfangenden Activity und ist mit deren Ende womoeglich weg.
 *
 * [take] leert die Ablage: Wird die [MainActivity] danach gedreht oder nach
 * einem Prozesstod mit demselben Intent neu erzeugt, findet sie nichts mehr
 * und importiert nicht ein zweites Mal.
 */
object PendingImports {
    private val batches = mutableListOf<List<ActivityFileInput>>()

    @Synchronized
    fun offer(files: List<ActivityFileInput>) {
        if (files.isNotEmpty()) batches.add(files)
    }

    /** Alle wartenden Dateien auf einmal — oder eine leere Liste. */
    @Synchronized
    fun take(): List<ActivityFileInput> {
        val all = batches.flatten()
        batches.clear()
        return all
    }
}

/** Extra an der [MainActivity]: „In [PendingImports] liegt etwas fuer dich." */
const val EXTRA_IMPORT_PENDING = "de.trailscape.app.extra.IMPORT_PENDING"
