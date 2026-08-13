package de.trailscape.core

import btools.mapaccess.PhysicalFile
import btools.mapaccess.Rd5DiffManager
import btools.mapaccess.Rd5DiffTool
import btools.util.ProgressListener
import java.io.File

/**
 * Der Umgang mit dem **Kachelformat** selbst: Pruefsumme bilden, ein Delta
 * anwenden, eine Kachel auf Unversehrtheit pruefen.
 *
 * ## Warum das hier steht und nicht in `:app`
 * Alle drei Aufgaben erledigen Klassen aus `brouter-mapaccess`, und `btools.*`
 * ist ausserhalb von `:core` nicht sichtbar (siehe `OfflineRouting.kt`,
 * Abschnitt „Warum ein eigener Wrapper"). Diese Datei ist die zweite und
 * letzte Stelle in `:core`, die die Engine-Klassen anfasst; `:app` sieht nur
 * die drei Funktionen unten mit `java.io.File` und deutschen Fehlermeldungen.
 *
 * ## Was der Submodul-Quelltext zum Delta-Weg wirklich sagt
 * Nachgelesen in `brouter-routing-app/.../DownloadWorker.java`
 * (`downloadSegment`) und `brouter-mapaccess/.../Rd5DiffManager.java`:
 *
 *  * Der Client bildet die **MD5-Summe der lokalen Kacheldatei**
 *    (`Rd5DiffManager.getMD5`) und laedt
 *    `…/segments4/diff/<Kachel>/<md5>.df5`. Der Dateiname ist also die
 *    Pruefsumme des **alten** Stands, nicht des neuen.
 *  * Angewendet wird das Delta mit
 *    `Rd5DiffTool.recoverFromDelta(alteKachel, delta, neueKachel, progress)`.
 *    Die Ausgabe ist eine **neue Datei**; die alte bleibt waehrenddessen
 *    unangetastet und wird erst danach ersetzt.
 *  * Ein Delta der Laenge **0** ist kein Fehler, sondern ein Signal: Der
 *    Server legt zu jedem neuen Kachelstand eine leere `<md5-des-neuen-
 *    Stands>.df5` an (`Rd5DiffManager.calcDiffs`, „dummyDiffFile"). Wer diese
 *    Datei bekommt, hat bereits den aktuellen Stand. `recoverFromDelta` faengt
 *    den Fall ab und kopiert die Datei bloss — der Aufrufer sollte sich das
 *    sparen (siehe [segmentDeltaIsDummy]).
 *  * Deltas gibt es nur fuer Kacheln ueber 1 MB und nur fuer die letzten neun
 *    Tage (`calcDiffs`: „limit diff history to 9 days"), siehe
 *    [segmentDeltaHistoryDays].
 *  * Vor dem Umbenennen prueft der Upstream das Ergebnis mit
 *    `PhysicalFile.checkFileIntegrity` gegen die im Format eingebauten
 *    CRC-Summen ([checkSegmentIntegrity]).
 *
 * Abweichung zur Auftragsbeschreibung: Die Kacheln werden **taeglich** neu
 * gebaut, nicht woechentlich — der Verzeichnisindex von
 * `segments4/diff/E10_N50/` zeigte am 13.08.2026 elf Deltas mit Datum
 * 04.–13.08., also fuer jeden Tag eines. Das aendert nichts am Verfahren,
 * macht den Delta-Weg aber noch wichtiger.
 */

/**
 * Die MD5-Summe einer Kacheldatei in Kleinbuchstaben — der Name, unter dem
 * der Server das passende Delta fuehrt.
 *
 * Liest die ganze Datei (rund 120 MB), gehoert also auf einen
 * Hintergrund-Thread. Gemessen auf dem Entwicklungsrechner: rund 0,3 s fuer
 * 119 MB.
 */
fun segmentMd5(file: File): String = Rd5DiffManager.getMD5(file)

/**
 * Ist [deltaSizeBytes] das leere „Dummy"-Delta, mit dem der Server sagt „du
 * hast bereits den aktuellen Stand"? Siehe Datei-KDoc.
 */
fun segmentDeltaIsDummy(deltaSizeBytes: Long): Boolean = deltaSizeBytes == 0L

/**
 * Wendet [delta] auf [base] an und schreibt das Ergebnis nach [out].
 *
 * @param onPercent Fortschritt in Prozent (0–100), so wie ihn die Engine
 *   selbst meldet — sie zaehlt Kachelbloecke, nicht Bytes.
 * @param isCancelled wird bei jedem Block gefragt. Bricht der Lauf ab, loescht
 *   die Engine [out] selbst und diese Funktion liefert `false`.
 *
 * Nebenwirkung, die sich nicht abstellen laesst: `recoverFromDelta` schreibt
 * am Ende eine Zeile („recovering from diffs took … ms") auf `System.out`, auf
 * Android also ins Logcat. Ein Schalter dafuer existiert im Upstream nicht,
 * und dafuer die Ausgabe umzubiegen waere schlimmer als die eine Zeile.
 *
 * @return `true`, wenn [out] vollstaendig geschrieben wurde.
 * @throws OfflineRoutingException mit deutscher Meldung, wenn das Delta nicht
 *   passt. Das ist **kein** Grund zur Panik, sondern der normale Anlass, auf
 *   den Vollabzug umzuschwenken: Die Engine wirft hier auch nackte
 *   `RuntimeException`s („size mismatch at …"), die deshalb mitgefangen
 *   werden.
 */
fun applySegmentDelta(
    base: File,
    delta: File,
    out: File,
    onPercent: (Int) -> Unit = {},
    isCancelled: () -> Boolean = { false },
): Boolean {
    val listener = object : ProgressListener {
        override fun updateProgress(task: String?, progress: Int) {
            onPercent(progress.coerceIn(0, 100))
        }

        override fun isCanceled(): Boolean = isCancelled()
    }
    try {
        Rd5DiffTool.recoverFromDelta(base, delta, out, listener)
    } catch (e: Exception) {
        // Aufraeumen: Bei einem Abbruch mitten im Schreiben liegt eine
        // unbrauchbare Teildatei herum, die sonst den naechsten Versuch
        // verwirrt.
        out.delete()
        throw OfflineRoutingException(
            "Die Karten-Aktualisierung ließ sich nicht anwenden " +
                "(${e.message ?: e.javaClass.simpleName}).",
        )
    }
    // Bei Abbruch loescht `recoverFromDelta` die Ausgabedatei selbst — genau
    // daran erkennt der Aufrufer den Abbruch.
    return out.isFile && out.length() > 0
}

/**
 * Prueft eine Kachel gegen die im Format eingebauten CRC-Summen.
 *
 * Wird **nach dem Anwenden eines Deltas** gebraucht: Ein Delta setzt die
 * Datei aus lokalen Bytes und Serverbytes neu zusammen, da genuegt kein
 * Groessenvergleich. Ein Vollabzug dagegen ist ueber `Content-Length` und den
 * Umweg ueber die Teildatei bereits belegt.
 *
 * Gemessen an E10_N50 (119 MB, Entwicklungsrechner): 365 ms — die Pruefung
 * ist also bezahlbar. Und sie hat Zaehne: 4 kB mitten in der Datei
 * ueberschrieben, und sie meldet „checkum error" (nachgestellt in
 * `app/.../SegmentDownloadManualTest.kt`).
 *
 * @return `null`, wenn alles stimmt, sonst eine deutsche Meldung.
 */
fun checkSegmentIntegrity(file: File): String? = try {
    PhysicalFile.checkFileIntegrity(file)
    null
} catch (e: Exception) {
    "Die Kacheldatei ${file.name} ist beschädigt (${e.message ?: e.javaClass.simpleName})."
}
