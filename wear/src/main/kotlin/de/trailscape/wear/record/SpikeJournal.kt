package de.trailscape.wear.record

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Das Messprotokoll des Spikes — JSON Lines, eine Zeile pro Ereignis.
 *
 * Gleiches Haltbarkeitsversprechen wie
 * `de.trailscape.app.record.RecordingJournal`: Jede Zeile geht sofort mit
 * `flush()` + `FileDescriptor.sync()` auf den Datentraeger. Hier waegt das
 * noch schwerer als in der Telefon-App, denn Frage 3 lautet ausdruecklich
 * „wie stark zieht das am Akku" — der wahrscheinlichste Ausgang eines
 * gelungenen Versuchs ist eine leere Uhr. Genau dann muss das Protokoll bis
 * zur letzten geschriebenen Sekunde auswertbar sein, ohne dass ein
 * Abschluss-Schreibvorgang je stattgefunden hat.
 *
 * Geschrieben wird nach `getExternalFilesDir(null)`, nicht in `filesDir`:
 * Nur von dort laesst sich die Datei ohne Root per
 * `adb pull /sdcard/Android/data/<paket>/files/<name>.jsonl` holen.
 *
 * ## Zeilenformat
 *
 * Jede Zeile ist ein JSON-Objekt mit `type` und `at` (Wall-Clock in ms seit
 * Epoch), plus typabhaengige Felder:
 *
 * ```json
 * {"type":"header","at":1754899200000,"v":1,"device":{"manufacturer":"samsung","model":"SM-L705F","sdkInt":34},"caps":{"bikingSupported":true,"requested":["Distance","HeartRate","Location"],"missing":["Absolute Elevation"],"deviceSupportsForBiking":["Calories","Distance"],"autoPauseAndResume":true},"batteryPct":88}
 * {"type":"point","at":1754899261000,"lat":52.5163,"lon":13.3777,"altitude":38.2,"accuracyM":4.7,"vAccuracyM":6.1,"bootOffsetMs":91231}
 * {"type":"metric","at":1754899261000,"name":"HeartRate","kind":"sample","value":132.0,"bootOffsetMs":91231}
 * {"type":"availability","at":1754899205000,"dataType":"Location","state":"ACQUIRED_TETHERED"}
 * {"type":"battery","at":1754899260000,"pct":87}
 * {"type":"screen","at":1754899290000,"on":false}
 * {"type":"note","at":1754899210000,"text":"startExercise abgeschlossen"}
 * {"type":"end","at":1754906400000,"batteryPct":41,"points":6120,"reason":"benutzer"}
 * ```
 *
 * Fehlt die Hoehe, steht dort ausdruecklich `"altitude":null` — siehe [punkt].
 *
 * Alle Methoden sind synchronisiert: Der Service schreibt aus seinem
 * Coroutine-Scope, der Akku-Ticker und der Display-Empfaenger aus anderen
 * Threads.
 */
internal class SpikeJournal(private val file: File) {

    private var stream: FileOutputStream? = null

    /** Voller Pfad der Protokolldatei — wird am Ende auf dem Display gezeigt. */
    val pfad: String get() = file.absolutePath

    /**
     * Legt die Datei an und schreibt die Kopfzeile.
     *
     * [geraet] und [faehigkeiten] sind bereits fertige JSON-Objekte, damit
     * das Journal nichts ueber Health Services oder `Build.*` wissen muss.
     */
    fun beginn(
        startMs: Long,
        geraet: JsonObject,
        faehigkeiten: JsonObject,
        akkuProzent: Int,
    ) = synchronized(this) {
        file.parentFile?.mkdirs()
        stream = FileOutputStream(file, /* append = */ true)
        schreibe("header", startMs) {
            put("v", FORMAT_VERSION)
            put("device", geraet)
            put("caps", faehigkeiten)
            put("batteryPct", akkuProzent)
        }
    }

    /**
     * Eine Position.
     *
     * [hoeheM] ist ausdruecklich nullable und wird als JSON-`null`
     * geschrieben, wenn die Uhr keine absolute Hoehe liefert
     * (`LocationData.altitude == Double.NaN`). Ein fehlendes Feld waere hier
     * die schlechtere Wahl: Beim Auswerten liesse sich „Uhr kann es nicht"
     * nicht mehr von „Zeile abgeschnitten" unterscheiden.
     */
    fun punkt(
        zeitMs: Long,
        lat: Double,
        lon: Double,
        hoeheM: Double?,
        genauigkeitM: Double?,
        vertikaleGenauigkeitM: Double?,
        bootOffsetMs: Long,
    ) = synchronized(this) {
        schreibe("point", zeitMs) {
            put("lat", lat)
            put("lon", lon)
            put("altitude", hoeheM)
            put("accuracyM", genauigkeitM)
            put("vAccuracyM", vertikaleGenauigkeitM)
            put("bootOffsetMs", bootOffsetMs)
        }
    }

    /**
     * Eine Messgroesse (HF, Geschwindigkeit, Distanz, Hoehenzuwachs, …).
     *
     * [art] ist nicht schmueckendes Beiwerk, sondern noetig, um die Zeile
     * ueberhaupt deuten zu koennen: Health Services vergibt fuer den
     * Zuwachs und den Gesamtwert desselben Datentyps DENSELBEN Namen —
     * `DataType.DISTANCE` und `DataType.DISTANCE_TOTAL` heissen beide
     * „Distance". Ohne `kind` liesse sich in der Auswertung ein Meter
     * Zuwachs nicht von einem Kilometerstand unterscheiden.
     *
     * Erlaubte Werte: `sample` (Momentanwert), `interval` (Zuwachs im
     * Intervall), `cumulative` (Gesamtwert seit Start), `statistical`
     * (Kennzahlen ueber einen Zeitraum; [wert] ist dann der Mittelwert).
     */
    fun messwert(
        zeitMs: Long,
        name: String,
        wert: Double,
        art: String,
        bootOffsetMs: Long?,
        minWert: Double? = null,
        maxWert: Double? = null,
    ) = synchronized(this) {
        schreibe("metric", zeitMs) {
            put("name", name)
            put("kind", art)
            put("value", wert)
            put("bootOffsetMs", bootOffsetMs)
            minWert?.let { put("min", it) }
            maxWert?.let { put("max", it) }
        }
    }

    /** Jede Aenderung der Sensor-Verfuegbarkeit (GPS suchend/gefunden/weg). */
    fun verfuegbarkeit(
        zeitMs: Long,
        datentyp: String,
        zustand: String,
    ) = synchronized(this) {
        schreibe("availability", zeitMs) {
            put("dataType", datentyp)
            put("state", zustand)
        }
    }

    /** Akkustand in Prozent — periodisch, damit sich die Verbrauchskurve zeichnen laesst. */
    fun akku(zeitMs: Long, prozent: Int) = synchronized(this) {
        schreibe("battery", zeitMs) {
            put("pct", prozent)
        }
    }

    /**
     * Display an/aus.
     *
     * Der Schluessel zur Auswertung von Frage 2: Bei dunklem Display batcht
     * Health Services die Standortdaten (ca. alle 150 s statt ~1x/s). Ohne
     * diese Zeilen liessen sich die entstehenden Luecken spaeter nicht von
     * echten GPS-Aussetzern unterscheiden.
     */
    fun display(zeitMs: Long, an: Boolean) = synchronized(this) {
        schreibe("screen", zeitMs) {
            put("on", an)
        }
    }

    /** Freitext-Notiz (Zustandswechsel der Uebung, Fehler, Abbruchgruende). */
    fun notiz(zeitMs: Long, text: String) = synchronized(this) {
        schreibe("note", zeitMs) {
            put("text", text)
        }
    }

    /** Abschlusszeile und Schliessen der Datei. */
    fun ende(
        zeitMs: Long,
        akkuProzent: Int,
        punktzahl: Int,
        grund: String,
    ) = synchronized(this) {
        schreibe("end", zeitMs) {
            put("batteryPct", akkuProzent)
            put("points", punktzahl)
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
        const val FORMAT_VERSION = 1

        /**
         * Dateiname mit Startzeitpunkt, damit mehrere Ausfahrten
         * nebeneinander liegen bleiben und sich beim `adb pull` sofort
         * zuordnen lassen.
         */
        fun datei(verzeichnis: File, startMs: Long): File {
            val stempel = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date(startMs))
            return File(verzeichnis, "spike-$stempel.jsonl")
        }

        /** Hilfsbau fuer die `caps`-Struktur der Kopfzeile. */
        fun faehigkeitenJson(
            radfahrenUnterstuetzt: Boolean,
            unterstuetzt: List<String>,
            vermisst: List<String>,
            geraet: List<String>,
            autoPause: Boolean,
        ): JsonObject = buildJsonObject {
            put("bikingSupported", radfahrenUnterstuetzt)
            put("requested", buildJsonArray { unterstuetzt.forEach { add(it) } })
            put("missing", buildJsonArray { vermisst.forEach { add(it) } })
            put("deviceSupportsForBiking", buildJsonArray { geraet.forEach { add(it) } })
            put("autoPauseAndResume", autoPause)
        }
    }
}
