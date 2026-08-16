package de.trailscape.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import de.trailscape.core.FormatException
import de.trailscape.core.Ride
import de.trailscape.core.rideFromFit
import de.trailscape.core.rideFromGpx
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Einzelimport einer per SAF ausgewaehlten Aktivitaetsdatei (GPX oder FIT,
 * je auch `.gz`) — geteilt zwischen dem Import-Knopf im Touren-Screen
 * (`ui/rides/TourList.kt`) und der „Aktivitaet importieren"-Schaltflaeche
 * der Backup-Karte (`ui/more/BackupCard.kt`), damit beide dieselbe
 * Dateitypenerkennung nutzen.
 *
 * Fuer den Massenimport eines ganzen Archivs siehe stattdessen
 * `de.trailscape.core.importArchive` (`:core`, `BulkImport.kt`), das dieselbe
 * Zuordnung fuer Archiv-Eintraege bereits mitbringt.
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
 * Liest die per SAF gewaehlte Datei komplett und baut daraus eine Tour.
 *
 * Die Dateiart wird an der Endung des SAF-`DISPLAY_NAME` entschieden: endet
 * sie (nach Abzug eines etwaigen `.gz`) auf `.fit`, geht es ueber
 * [rideFromFit] — sonst wie bisher ueber [rideFromGpx]. `.gz` wird dabei
 * generisch behandelt, also anhand der GZIP-Magic-Bytes am Inhalt erkannt und
 * nicht nur am Namen: `parseFit`/[rideFromFit] entpackt `.fit.gz` bereits
 * selbst, fuer GPX entpackt diese Funktion vorher, weil [rideFromGpx] reinen
 * Text erwartet. Ohne verwertbaren Anzeigenamen (mancher Anbieter liefert
 * keinen) faellt die Erkennung auf GPX zurueck — das bisherige Verhalten.
 *
 * Laeuft auf [Dispatchers.IO]; wirft [FormatException] bzw.
 * [IllegalStateException] mit einer fuer die UI geeigneten deutschen Meldung.
 */
suspend fun importActivityFile(context: Context, uri: Uri): Ride = withContext(Dispatchers.IO) {
    val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw FormatException(UNREADABLE_FILE_MESSAGE)

    val displayName = queryDisplayName(context, uri)
    val fallbackName = displayName
        ?.let { activityBaseName(it) }
        ?.takeIf { it.isNotBlank() }
        ?: "Importierte Tour"

    if (isFitFileName(displayName)) {
        rideFromFit(rawBytes, fallbackName = fallbackName)
    } else {
        val xml = gunzipIfNeeded(rawBytes).toString(Charsets.UTF_8)
        rideFromGpx(xml, fallbackName = fallbackName)
    }
}

/** true bei `.fit`/`.fit.gz` (Gross-/Kleinschreibung egal) — alles andere gilt als GPX. */
private fun isFitFileName(name: String?): Boolean {
    val lower = name?.lowercase()?.trim() ?: return false
    val stem = if (lower.endsWith(".gz")) lower.removeSuffix(".gz") else lower
    return stem.endsWith(".fit")
}

/** Dateiname ohne Verzeichnis und ohne (auch doppelte, z. B. `.fit.gz`) Endung. */
private fun activityBaseName(name: String): String {
    var stem = name.substringAfterLast('/')
    if (stem.lowercase().endsWith(".gz")) stem = stem.dropLast(3)
    val dot = stem.lastIndexOf('.')
    return if (dot > 0) stem.substring(0, dot) else stem
}

/**
 * Entpackt GZIP-Daten transparent (Strava exportiert z. B. `.gpx.gz`), laesst
 * alles andere unveraendert durch. Entspricht `gunzipIfNeeded` aus `:core`
 * (`Fit.kt`) — dort `internal` und darum von hier aus nicht erreichbar.
 */
private fun gunzipIfNeeded(bytes: ByteArray): ByteArray {
    if (bytes.size < 2 || bytes[0] != 0x1F.toByte() || bytes[1] != 0x8B.toByte()) return bytes
    return try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    } catch (e: Exception) {
        throw FormatException("Die Datei ist GZIP-komprimiert, konnte aber nicht entpackt werden.")
    }
}

/** Anzeigename eines `content://`-Dokuments, falls der Anbieter ihn liefert. */
fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
