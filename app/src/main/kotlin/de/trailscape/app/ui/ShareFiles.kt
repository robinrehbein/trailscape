package de.trailscape.app.ui

import java.io.File

/**
 * Gemeinsame Verwaltung des Cache-Verzeichnisses, aus dem der FileProvider
 * geteilte GPX-Dateien ausliefert (`res/xml/file_paths.xml`).
 *
 * Geteilt von der Tourenliste (`ui/rides/TourList.kt`) und der Karte
 * (`ui/map/MapScreen.kt`), weil beide dieselbe Datei-Uebergabe benutzen und
 * sich vorher genau nicht in die Quere kommen duerfen:
 *
 *  * Die Tourenliste hat frueher **alle** Dateien im Verzeichnis geloescht,
 *    bevor sie die neue schrieb. Die Empfaenger-App liest die Datei aber erst,
 *    nachdem der Chooser sie ausgewaehlt hat — ein zweites Teilen kurz danach
 *    zog dem noch laufenden Lesevorgang die Datei unter den Fuessen weg.
 *  * Die Karte hat gar nicht aufgeraeumt, ihr Verzeichnis wuchs also mit
 *    jedem geteilten Routenexport.
 *
 * Deshalb: aufraeumen ja, aber nur was aelter als [SHARE_FILE_MAX_AGE_MS] ist.
 * So bleibt der gerade uebergebene Export garantiert stehen, und der Cache
 * waechst trotzdem nicht.
 *
 * Bewusst ohne Android-Import (der Aufrufer reicht `context.cacheDir` herein),
 * damit die Aufraeumlogik als reiner JVM-Test pruefbar bleibt.
 */

/** Unterverzeichnis im Cache, das `res/xml/file_paths.xml` freigibt. */
const val SHARE_DIR_NAME: String = "geteilte-touren"

/** Ab diesem Alter darf ein geteilter Export weggeraeumt werden (1 Stunde). */
const val SHARE_FILE_MAX_AGE_MS: Long = 60L * 60 * 1000

/**
 * Legt das Freigabeverzeichnis unterhalb von [cacheDir] an (falls noetig),
 * raeumt alte Exporte weg und liefert es zurueck.
 */
fun prepareShareDirectory(cacheDir: File, nowMs: Long = System.currentTimeMillis()): File {
    val dir = File(cacheDir, SHARE_DIR_NAME)
    dir.mkdirs()
    pruneShareDirectory(dir, nowMs)
    return dir
}

/**
 * Loescht alle Dateien in [dir], deren letzte Aenderung laenger als
 * [maxAgeMs] zurueckliegt.
 *
 * @return Anzahl der geloeschten Dateien.
 */
fun pruneShareDirectory(
    dir: File,
    nowMs: Long = System.currentTimeMillis(),
    maxAgeMs: Long = SHARE_FILE_MAX_AGE_MS,
): Int {
    val files = dir.listFiles() ?: return 0
    var deleted = 0
    for (file in files) {
        if (!file.isFile) continue
        val age = nowMs - file.lastModified()
        // Dateien mit Zeitstempel in der Zukunft (Uhrumstellung) bleiben
        // liegen: lieber ein Kilobyte zu viel als eine gerade uebergebene
        // Datei zu loeschen.
        if (age > maxAgeMs && file.delete()) deleted++
    }
    return deleted
}
