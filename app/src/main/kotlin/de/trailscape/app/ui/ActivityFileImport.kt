package de.trailscape.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.AppLocale
import de.trailscape.app.i18n.localized
import de.trailscape.core.ActivityFileInput
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
 *
 * ## Obergrenzen
 * Die Bytes aller Dateien liegen gleichzeitig im Speicher (erst lesen, solange
 * die Berechtigung gilt, dann importieren). Ohne Grenzen koennte eine fremde
 * App per `SEND_MULTIPLE` beliebig viel davon anliefern. Deshalb gelten je
 * Datei [MAX_ACTIVITY_FILE_BYTES], je Durchgang [MAX_IMPORT_TOTAL_BYTES] und
 * [MAX_IMPORT_FILES]; was darueber liegt, wird gar nicht erst gelesen und
 * zaehlt als unlesbar mit Grund. Den **entpackten** Inhalt einer `.gz`-Datei
 * begrenzt `:core` (`MAX_GUNZIPPED_BYTES` in `Fit.kt`).
 */

/**
 * Rueckfallmeldung, falls eine geworfene Exception keinen Text traegt
 * (Ressource `rides_import_unreadable_error`).
 *
 * Mit Handlungsanweisung: „Die Datei konnte nicht gelesen werden." allein sagt
 * der Nutzerin nur, dass etwas nicht ging — nicht, was sie als Naechstes tun
 * kann. Der haeufigste Grund ist eine Datei, die der Anbieter (Cloud-Speicher,
 * Mailanhang) gar nicht lokal vorhaelt. Neutral formuliert („versuche es
 * erneut" statt „waehle sie erneut aus"): Beim Teilen aus WhatsApp oder
 * Komoot waehlt niemand etwas aus.
 */
fun unreadableFileMessage(context: Context): String =
    context.localized().getString(R.string.rides_import_unreadable_error)

/**
 * [unreadableFileMessage] in der aktuellen App-Sprache, ohne eigenen Kontext.
 *
 * Frueher eine deutsche Konstante; als Eigenschaft mit Getter bleibt der Name
 * fuer die Backup-Karte (`ui/more/BackupCard.kt`, Zweig i18n-more) gueltig
 * und liefert trotzdem die richtige Sprache. Neue Aufrufe nehmen
 * [unreadableFileMessage] mit Kontext.
 */
val UNREADABLE_FILE_MESSAGE: String
    get() = AppServices.localizedContext().getString(R.string.rides_import_unreadable_error)

/**
 * Obergrenze je Datei. Aktivitaetsdateien sind einige zehn Kilobyte bis
 * hoechstens ~10 MB (lange Aufzeichnung im Sekundentakt); wer versehentlich
 * ein Video teilt (`application/octet-stream` nimmt alles an), soll nicht den
 * Speicher der App fluten. Knapp bemessen, weil eine GPX als String und DOM
 * ein Vielfaches ihrer Dateigroesse belegt.
 */
internal const val MAX_ACTIVITY_FILE_BYTES = 16L * 1024 * 1024

/** Obergrenze fuer alle Dateien eines Durchgangs zusammen — siehe Datei-KDoc. */
internal const val MAX_IMPORT_TOTAL_BYTES = 64L * 1024 * 1024

/** Hoechstzahl an Dateien je Durchgang — siehe Datei-KDoc. */
internal const val MAX_IMPORT_FILES = 200

/**
 * Meldung fuer Dateien, die nach Erreichen einer Durchgangs-Grenze nicht mehr
 * gelesen werden (Ressource `rides_import_too_many_files_error`).
 */
internal fun tooManyFilesMessage(context: Context): String =
    context.localized().getString(R.string.rides_import_too_many_files_error)

/**
 * Liest jede der [uris] komplett in den Speicher — auf [Dispatchers.IO] und
 * **sofort**: Eine per Teilen/Oeffnen gereichte Leseberechtigung gilt nur, so
 * lange die empfangende Activity lebt. Ein Lesefehler bricht nicht ab, sondern
 * landet als [ActivityFileInput.readError] im Ergebnis — ebenso jede Datei
 * ueber einer der Obergrenzen (siehe Datei-KDoc).
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
    // Einmal je Durchgang: die Meldungen in der App-Sprache (die gereichte
    // Activity hat den Override schon, ein Anwendungskontext bekommt ihn hier).
    val tooMany = tooManyFilesMessage(context)
    val unreadable = unreadableFileMessage(context)
    var totalBytes = 0L
    uris.mapIndexed { index, uri ->
        val name = runCatching { queryDisplayName(context, uri) }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
        val mime = mimeTypes[uri] ?: runCatching { resolver.getType(uri) }.getOrNull()
        if (index >= MAX_IMPORT_FILES) {
            return@mapIndexed ActivityFileInput(name, mime, ByteArray(0), readError = tooMany)
        }
        val budget = MAX_IMPORT_TOTAL_BYTES - totalBytes
        try {
            val stream = resolver.openInputStream(uri)
                ?: return@mapIndexed ActivityFileInput(name, mime, ByteArray(0), readError = unreadable)
            val bytes = stream.use { readBounded(it, minOf(MAX_ACTIVITY_FILE_BYTES, budget)) }
            when {
                bytes != null -> {
                    totalBytes += bytes.size
                    ActivityFileInput(name, mime, bytes)
                }
                // Fuer sich allein haette die Datei noch gepasst — zu viel ist
                // nur der ganze Durchgang.
                budget < MAX_ACTIVITY_FILE_BYTES ->
                    ActivityFileInput(name, mime, ByteArray(0), readError = tooMany)
                else -> ActivityFileInput(name, mime, ByteArray(0), readError = AppLocale.coreTexts(context).files.fileTooLarge())
            }
        } catch (e: Exception) {
            ActivityFileInput(name, mime, ByteArray(0), readError = unreadable)
        }
    }
}

/**
 * Liest [stream] bis zum Ende, aber hoechstens [limit] Bytes; `null`, sobald
 * mehr kaeme. Stueckweise statt `readBytes()`, damit eine zu grosse Datei nie
 * vollstaendig im Speicher landet.
 */
internal fun readBounded(stream: InputStream, limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        total += read
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** Anzeigename eines `content://`-Dokuments, falls der Anbieter ihn liefert. */
fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
