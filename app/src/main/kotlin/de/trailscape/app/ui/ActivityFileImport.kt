package de.trailscape.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import de.trailscape.core.ActivityFileInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Einlesen per URI gereichter Aktivitaetsdateien (GPX oder FIT, je auch
 * `.gz`) — geteilt zwischen der Mehrfachauswahl im App-Dialog
 * (`ui/ActivityImportAction.kt`, genutzt vom Verlauf und von der
 * Backup-Karte) und dem Teilen/Oeffnen aus fremden Apps (`ImportActivity`).
 *
 * Hier passiert bewusst **nur** das Lesen in den Speicher: Welche Datei was
 * ist, was doppelt und was unlesbar ist, entscheidet `:core`
 * (`ActivityFiles.kt`, [de.trailscape.core.importActivityFiles]) — ohne
 * Android und darum testbar. Fuer den Massenimport eines ganzen Archivs siehe
 * stattdessen `de.trailscape.core.importArchive` (`BulkImport.kt`).
 */

/**
 * Deutsche Rueckfallmeldung, falls eine geworfene Exception keinen Text traegt.
 *
 * Mit Handlungsanweisung: „Die Datei konnte nicht gelesen werden." allein sagt
 * der Nutzerin nur, dass etwas nicht ging — nicht, was sie als Naechstes tun
 * kann. Der haeufigste Grund ist eine Datei, die der Anbieter (Cloud-Speicher,
 * Mailanhang) gar nicht lokal vorhaelt.
 */
const val UNREADABLE_FILE_MESSAGE =
    "Die Datei konnte nicht gelesen werden. Liegt sie in einer Cloud, lade sie " +
        "erst auf das Gerät herunter und wähle sie dann erneut aus."

/**
 * Obergrenze je Datei. Aktivitaetsdateien sind einige zehn bis wenige hundert
 * Kilobyte; wer versehentlich ein Video teilt (`application/octet-stream`
 * nimmt alles an), soll nicht den Speicher der App fluten.
 */
private const val MAX_ACTIVITY_FILE_BYTES = 64L * 1024 * 1024

/**
 * Liest jede der [uris] komplett in den Speicher — auf [Dispatchers.IO] und
 * **sofort**: Eine per Teilen/Oeffnen gereichte Leseberechtigung gilt nur, so
 * lange die empfangende Activity lebt. Ein Lesefehler bricht nicht ab, sondern
 * landet als [ActivityFileInput.readError] im Ergebnis.
 *
 * [mimeTypes] sind die vom Intent gemeldeten Typen (je URI, falls bekannt);
 * sonst fragt die Funktion den Anbieter.
 */
suspend fun readActivityFiles(
    context: Context,
    uris: List<Uri>,
    mimeTypes: Map<Uri, String?> = emptyMap(),
): List<ActivityFileInput> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    uris.map { uri ->
        val name = runCatching { queryDisplayName(context, uri) }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
        val mime = mimeTypes[uri] ?: runCatching { resolver.getType(uri) }.getOrNull()
        try {
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_ACTIVITY_FILE_BYTES) {
                        return@map ActivityFileInput(
                            name,
                            mime,
                            ByteArray(0),
                            readError = "Die Datei ist zu groß für eine GPX- oder FIT-Datei.",
                        )
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (bytes == null) {
                ActivityFileInput(name, mime, ByteArray(0), readError = UNREADABLE_FILE_MESSAGE)
            } else {
                ActivityFileInput(name, mime, bytes)
            }
        } catch (e: Exception) {
            ActivityFileInput(name, mime, ByteArray(0), readError = UNREADABLE_FILE_MESSAGE)
        }
    }
}

/** Anzeigename eines `content://`-Dokuments, falls der Anbieter ihn liefert. */
fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
