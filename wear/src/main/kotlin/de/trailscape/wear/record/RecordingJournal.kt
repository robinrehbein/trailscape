package de.trailscape.wear.record

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Schlankes Ereignisprotokoll der Aufzeichnung — JSON Lines, eine Zeile pro
 * Ereignis, zur Fehlersuche auf einem Geraet ohne angeschlossenen Debugger.
 *
 * ## Absichtliche Schrumpfung gegenueber dem Spike-Vorlaeufer
 * Der Vorlaeufer (`SpikeJournal`) schrieb JEDEN Health-Services-Datenpunkt
 * einzeln mit — das war fuer die Geraete-/Akkuanalyse des Spikes (Fragen 1–4,
 * siehe docs/wear-spike.md) der eigentliche Zweck. Diese Fragen sind
 * beantwortet, der Spike ist jetzt eine Begleit-App. Die Rohdaten laufen
 * ohnehin schon gebuendelt ans Telefon ([de.trailscape.wear.comm.SensorSender]) —
 * DAS ist jetzt die Aufzeichnung. Was auf der Uhr bleibt, ist ein duennes
 * Ereignisprotokoll fuer genau eine Frage: "Was ist waehrend dieser Fahrt auf
 * der Uhr passiert, falls sich etwas komisch angefuehlt hat?" Dafuer reichen
 * Start/Pause/Fehler/Ende-Zeilen.
 *
 * ## Haltbarkeit
 * Gleiches Versprechen wie beim Vorlaeufer und wie
 * `de.trailscape.app.record.RecordingJournal`: Jede Zeile geht sofort mit
 * `flush()` + `FileDescriptor.sync()` auf den Datentraeger. Stirbt die
 * Uhr mitten in der Fahrt (Akku leer, Prozess vom System beendet), bleibt das
 * Protokoll bis zur zuletzt geschriebenen Zeile lesbar.
 *
 * Geschrieben wird nach `getExternalFilesDir(null)`, nicht in `filesDir`: Nur
 * von dort laesst sich die Datei ohne Root per
 * `adb pull /sdcard/Android/data/<paket>/files/<name>.jsonl` holen — siehe
 * docs/wear-spike.md Abschnitt e) fuer den vollen Befehlsablauf.
 *
 * Alle Methoden sind synchronisiert: Der Dienst schreibt sowohl aus seinem
 * Coroutine-Scope (Ereignisverarbeitung) als auch aus `onDestroy`.
 */
internal class RecordingJournal(private val file: File) {

    private var stream: FileOutputStream? = null

    fun beginn(startMs: Long, hinweis: String) = synchronized(this) {
        file.parentFile?.mkdirs()
        stream = FileOutputStream(file, /* append = */ true)
        schreibe("start", startMs) {
            put("info", hinweis)
        }
    }

    /** Freitext-Notiz (Zustandswechsel, Fehler, Verbindungsereignisse). */
    fun notiz(zeitMs: Long, text: String) = synchronized(this) {
        schreibe("note", zeitMs) {
            put("text", text)
        }
    }

    /** Abschlusszeile und Schliessen der Datei. */
    fun ende(zeitMs: Long, grund: String) = synchronized(this) {
        schreibe("end", zeitMs) {
            put("reason", grund)
        }
        try {
            stream?.close()
        } catch (e: Exception) {
            // Beim Schliessen ist nichts mehr zu retten.
        }
        stream = null
    }

    private inline fun schreibe(typ: String, zeitMs: Long, felder: JsonObjectBuilder.() -> Unit) {
        val out = stream ?: return
        val zeile = buildJsonObject {
            put("type", typ)
            put("at", zeitMs)
            felder()
        }
        out.write((zeile.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
        try {
            out.fd.sync()
        } catch (e: Exception) {
            // Manche Dateisysteme lehnen sync() ab; flush() bleibt wirksam.
        }
    }

    companion object {
        /**
         * Dateiname mit Startzeitpunkt, damit mehrere Ausfahrten nebeneinander
         * liegen bleiben und sich beim `adb pull` sofort zuordnen lassen.
         */
        fun datei(verzeichnis: File, startMs: Long): File {
            val stempel = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date(startMs))
            return File(verzeichnis, "aufzeichnung-$stempel.jsonl")
        }
    }
}
