package de.trailscape.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor

/**
 * Die **reine Rechnung** rund um BRouters Routing-Kacheln (`*.rd5`): welche
 * Kachel deckt was ab, wie heisst sie fuer Menschen, welche Kacheln liegen in
 * einem Kartenausschnitt — und die Entscheidung, ob eine vorhandene Kachel per
 * Delta oder als Vollabzug aufgefrischt wird.
 *
 * ## Warum in `:core` und nicht in `:app`?
 * Nichts davon braucht Android: Es sind Zahlen, Zeichenketten und eine
 * Entscheidungstabelle. In `:core` laeuft das ohne Emulator und ist mit Tests
 * abgedeckt (`RoutingSegmentsTest.kt`); in `:app` bleibt nur, was ohne Geraet
 * ohnehin nicht zu pruefen waere — Dateisystem, Netz, WorkManager.
 *
 * Die Zuordnung Punkt → Kachel ([segmentFileName]) und die Kachelliste einer
 * Route ([requiredSegmentFiles]) stehen weiterhin in `OfflineRouting.kt`, weil
 * sie dort schon fuer die Fehlermeldungen der Engine gebraucht werden. Diese
 * Datei ergaenzt die **Gegenrichtung** (Kachelname → Flaeche) und alles, was
 * die Kachelverwaltung darueber hinaus braucht.
 *
 * ## Was hier bewusst NICHT steht
 * Kein Netzzugriff und kein Dateizugriff. Beides erledigt `:app`
 * (`routing/SegmentDownloader.kt`), weil dafuer OkHttp und ein
 * Anwendungsverzeichnis noetig sind. Auch die Bytes der Kacheln fasst diese
 * Datei nicht an — der schmale Umgang mit dem Kachelformat selbst (MD5, Delta
 * anwenden, Integritaet pruefen) steht in `SegmentDelta.kt`, weil er die
 * BRouter-Klassen braucht.
 */

// ---------------------------------------------------------------------------
// Raster und Bezugsquelle
// ---------------------------------------------------------------------------

/**
 * Rasterweite der BRouter-Kacheln in Grad. Fest im Dateiformat verankert
 * (`NodesCache.fileForSegment`), keine Einstellung.
 */
const val segmentGridDeg: Int = 5

/** Dateiendung einer vollstaendigen Kachel. */
const val segmentFileSuffix: String = ".rd5"

/** Dateiendung eines inkrementellen Kachel-Deltas. */
const val segmentDeltaSuffix: String = ".df5"

/**
 * Verzeichnis der offiziellen Kacheln auf brouter.de.
 *
 * Bewusst `segments4` (das aktuelle Format, das auch die eingebettete Engine
 * v1.7.10 liest) und bewusst mit Schraegstrich am Ende, damit
 * [segmentDownloadUrl] nur noch anhaengen muss. Geprueft: Der Server
 * beantwortet `HEAD` mit `Content-Length`, `Last-Modified`, `ETag` und
 * `Accept-Ranges: bytes` — Grundlage sowohl fuer die Aktualitaetspruefung
 * ohne Download als auch fuer das Fortsetzen abgebrochener Downloads.
 */
const val brouterSegmentBaseUrl: String = "https://brouter.de/brouter/segments4/"

/**
 * Wie viele Tage Delta-Geschichte der Server vorhaelt.
 *
 * Nicht geraten, sondern aus `Rd5DiffManager.calcDiffs` im Submodul abgelesen:
 * Beim Erzeugen eines neuen Kachelstands werden alte Deltas nur dann
 * fortgeschrieben, wenn sie juenger als `9 * 86400000L` ms sind. Fuer eine
 * lokale Kachel, die aelter ist, existiert also garantiert **kein** Delta mehr
 * — den HEAD darauf kann man sich sparen (siehe [planSegmentUpdate]).
 */
const val segmentDeltaHistoryDays: Int = 9

/** Die Adresse, unter der die vollstaendige Kachel [fileName] liegt. */
fun segmentDownloadUrl(fileName: String, baseUrl: String = brouterSegmentBaseUrl): String =
    baseUrl + fileName

/**
 * Die Adresse des Deltas, das eine lokale Kachel mit der Pruefsumme [md5] auf
 * den aktuellen Stand hebt.
 *
 * Aufbau aus `DownloadWorker.downloadSegment` im Submodul:
 * `<basis>diff/<Kachel ohne Endung>/<md5>.df5`.
 */
fun segmentDeltaUrl(
    fileName: String,
    md5: String,
    baseUrl: String = brouterSegmentBaseUrl,
): String {
    val stem = fileName.removeSuffix(segmentFileSuffix)
    return "${baseUrl}diff/$stem/$md5$segmentDeltaSuffix"
}

// ---------------------------------------------------------------------------
// Kachel ↔ Flaeche
// ---------------------------------------------------------------------------

/**
 * Eine Routing-Kachel: 5°×5°, benannt nach ihrer **Suedwestecke**.
 *
 * ## Wie die Kachel zu einer Bezeichnung kommt, die man lesen mag
 *
 * Der Nutzer soll nicht „E10_N50" entziffern muessen. Drei Wege standen zur
 * Wahl:
 *
 * 1. **Ein Gebietsname** („Sachsen", „Ostdeutschland"). Verworfen: Eine
 *    5°-Kachel ist bei uns rund 350 × 550 km gross. E10_N50 enthaelt Berlin,
 *    Dresden **und** Prag, dazu halb Tschechien und ein Stueck Polen. Jeder
 *    Gebietsname waere eine Behauptung, die fuer den groesseren Teil der
 *    Flaeche falsch ist — und teuer bezahlt: Wer „Sachsen" laedt und in
 *    Bayern kein Routing bekommt, haelt die App fuer kaputt.
 * 2. **Nur das Gradfeld** („50°–55° N, 10°–15° O"). Exakt, aber fuer die
 *    meisten Menschen keine Ortsangabe.
 * 3. **Beides** — und das ist es geworden: bekannte Orte, die **tatsaechlich
 *    in der Kachel liegen**, ausdruecklich als Beispiele markiert („u. a."),
 *    plus das exakte Gradfeld. Die Orte machen die Kachel wiedererkennbar,
 *    das „u. a." verhindert den Kurzschluss „nur diese drei Staedte", und das
 *    Gradfeld bleibt die ueberpruefbare Wahrheit.
 *
 * Die Orte sind dabei **nicht** von Hand einer Kachel zugeordnet, sondern
 * ueber ihre Koordinaten mit [segmentFileName] einsortiert — eine falsche
 * Zuordnung kann so gar nicht entstehen (siehe [landmarksByTile]).
 *
 * Ebenfalls verworfen: den Kachelmittelpunkt per Reverse-Geocoding benennen
 * lassen (`Geocoding.kt`). Das braucht Netz — ausgerechnet in der Verwaltung
 * der Offline-Daten —, liefert genau einen Landesnamen (dieselbe falsche
 * Praezision wie Weg 1) und waere bei jedem Oeffnen der Liste eine Anfrage an
 * einen fremden Dienst.
 */
data class SegmentTile(
    /** Suedgrenze in Grad, immer ein Vielfaches von 5. */
    val southLat: Int,
    /** Westgrenze in Grad, immer ein Vielfaches von 5. */
    val westLon: Int,
) {
    /** Nordgrenze in Grad. */
    val northLat: Int get() = southLat + segmentGridDeg

    /** Ostgrenze in Grad. */
    val eastLon: Int get() = westLon + segmentGridDeg

    /** Der Name ohne Endung, z. B. `E10_N50`. */
    val name: String
        get() {
            val lonPart = if (westLon < 0) "W${-westLon}" else "E$westLon"
            val latPart = if (southLat < 0) "S${-southLat}" else "N$southLat"
            return "${lonPart}_$latPart"
        }

    /** Der Dateiname, z. B. `E10_N50.rd5`. */
    val fileName: String get() = name + segmentFileSuffix

    /** Mittelpunkt der Kachel — fuer eine spaetere Anzeige auf der Karte. */
    val centerLat: Double get() = southLat + segmentGridDeg / 2.0

    /** Mittelpunkt der Kachel, siehe [centerLat]. */
    val centerLon: Double get() = westLon + segmentGridDeg / 2.0

    /**
     * Das Gradfeld in lesbarer Form, z. B. `50°–55° N, 10°–15° O`.
     *
     * Die Grenzen stehen immer mit dem kleineren Betrag zuerst; die Halbkugel
     * steht als Buchstabe dahinter. Weil das Raster bei 0° ansetzt, liegt eine
     * Kachel nie halb auf beiden Halbkugeln — der Buchstabe gilt also fuer
     * beide Grenzen.
     */
    val boundsLabel: String
        get() {
            val lat = if (northLat <= 0) {
                "${abs(northLat)}°–${abs(southLat)}° S"
            } else {
                "$southLat°–$northLat° N"
            }
            val lon = if (eastLon <= 0) {
                "${abs(eastLon)}°–${abs(westLon)}° W"
            } else {
                "$westLon°–$eastLon° O"
            }
            return "$lat, $lon"
        }

    /**
     * Bis zu drei bekannte Orte **innerhalb** dieser Kachel, nach Bekanntheit
     * geordnet. Leer, wenn die Kachel keinen der hinterlegten Orte enthaelt
     * (etwa mitten im Atlantik oder in duenn besiedelten Gegenden).
     */
    val landmarks: List<String> get() = landmarksByTile[name].orEmpty()

    /**
     * Kurze Bezeichnung fuer Listen: die Beispielorte, sonst das Gradfeld.
     * Nie leer, nie erfunden — siehe Klassendoc.
     */
    val title: String
        get() = if (landmarks.isEmpty()) boundsLabel else "${landmarks.joinToString(", ")} u. a."

    /**
     * Vollstaendige Bezeichnung: Beispielorte **und** Gradfeld. Fuer die
     * Detailanzeige, in der beides Platz hat.
     */
    val description: String
        get() = if (landmarks.isEmpty()) boundsLabel else "$title · $boundsLabel"
}

/** Die Kachel, in der [lat]/[lon] liegt. Gegenstueck zu [segmentFileName]. */
fun segmentTileAt(lat: Double, lon: Double): SegmentTile =
    parseSegmentTile(segmentFileName(lat, lon))
        ?: error("segmentFileName lieferte einen unlesbaren Namen")

/**
 * Liest einen Kachelnamen zurueck in seine Flaeche — mit oder ohne Endung
 * (`E10_N50` wie `E10_N50.rd5`).
 *
 * `null` bei allem, was nicht dem Muster entspricht: falsche Endung, kein
 * Vielfaches der Rasterweite, Grenzen ausserhalb der Erde. Bewusst `null` und
 * keine Ausnahme — die Namen kommen teils aus Fehlermeldungen der Engine und
 * teils aus dem Dateisystem, und eine fremde Datei im Kachelverzeichnis darf
 * die Liste nicht sprengen.
 */
fun parseSegmentTile(fileName: String): SegmentTile? {
    val match = SEGMENT_NAME_REGEX.matchEntire(fileName.trim()) ?: return null
    val (lonSign, lonValue, latSign, latValue) = match.destructured
    val lon = lonValue.toIntOrNull()?.let { if (lonSign == "W") -it else it } ?: return null
    val lat = latValue.toIntOrNull()?.let { if (latSign == "S") -it else it } ?: return null
    if (lon % segmentGridDeg != 0 || lat % segmentGridDeg != 0) return null
    if (lon < -180 || lon > 180 - segmentGridDeg) return null
    if (lat < -90 || lat > 90 - segmentGridDeg) return null
    return SegmentTile(southLat = lat, westLon = lon)
}

private val SEGMENT_NAME_REGEX =
    Regex("""([EW])(\d{1,3})_([NS])(\d{1,2})(?:\.rd5)?""", RegexOption.IGNORE_CASE)

/**
 * Alle Kacheln, die ein Kartenausschnitt beruehrt — die Grundlage fuer
 * „welche Karten brauche ich fuer diese Gegend?".
 *
 * Die Reihenfolge ist stabil (Sued nach Nord, dann West nach Ost), damit eine
 * Liste in der Oberflaeche nicht bei jedem Neuzeichnen springt.
 *
 * [east] darf **kleiner** als [west] sein; das ist der Ausschnitt ueber den
 * 180. Laengengrad hinweg, und er wird korrekt umlaufend abgetastet. Ein
 * Ausschnitt mit [north] < [south] dagegen ist keine sinnvolle Eingabe und
 * ergibt eine leere Liste (dieselbe Haltung wie `estimateTileCount` fuer die
 * Kartenkacheln).
 */
fun segmentTilesForBounds(
    north: Double,
    south: Double,
    east: Double,
    west: Double,
): List<SegmentTile> {
    if (north < south) return emptyList()

    // Der Nordpol selbst gehoert zu keiner Kachel: die noerdlichste beginnt
    // bei 85°. Deshalb vor dem Abrunden knapp unter 90° klemmen.
    val southSw = floorToGrid(south.coerceIn(-90.0, 90.0 - 1e-9))
    val northSw = floorToGrid(north.coerceIn(-90.0, 90.0 - 1e-9))
    val westSw = floorToGrid(normalizeLon(west))
    val eastSw = floorToGrid(normalizeLon(east))

    val lons = buildList {
        var lon = westSw
        // 360 / 5 = 72 Spalten; die Schranke schuetzt vor einer Endlosschleife,
        // falls je ein unerwarteter Wert hereinkommt.
        repeat(360 / segmentGridDeg) {
            add(lon)
            if (lon == eastSw) return@buildList
            lon += segmentGridDeg
            if (lon >= 180) lon = -180
        }
    }

    val out = mutableListOf<SegmentTile>()
    var lat = southSw
    while (lat <= northSw) {
        for (lon in lons) {
            out.add(SegmentTile(southLat = lat, westLon = lon))
        }
        lat += segmentGridDeg
    }
    return out
}

/** Auf das naechstkleinere Vielfache der Rasterweite abrunden. */
private fun floorToGrid(value: Double): Int =
    (floor(value / segmentGridDeg) * segmentGridDeg).toInt()

/** Laengengrad in den Bereich [-180, 180) drehen. */
private fun normalizeLon(lon: Double): Double {
    var v = (lon + 180.0) % 360.0
    if (v < 0) v += 360.0
    return v - 180.0
}

// ---------------------------------------------------------------------------
// Ortsmarken
// ---------------------------------------------------------------------------

/**
 * Ein bekannter Ort als Ankerpunkt fuer die Kachelbezeichnung.
 *
 * Bewusst nur Name und Koordinate: Welcher Kachel er angehoert, rechnet
 * [landmarksByTile] aus. Eine von Hand gepflegte Kachelspalte waere eine
 * zweite Wahrheit, die beim ersten Tippfehler auseinanderlaeuft.
 */
private data class Landmark(val name: String, val lat: Double, val lon: Double)

/**
 * Die Ankerpunkte, **nach Bekanntheit geordnet** — innerhalb einer Kachel
 * werden die ersten drei genommen.
 *
 * Schwerpunkt Europa, weil dort gefahren wird; ein paar Weltstaedte kommen
 * dazu, damit eine Kachel auf Reisen nicht voellig namenlos bleibt. Wo nichts
 * hinterlegt ist, zeigt die Kachel schlicht ihr Gradfeld — das ist der
 * ehrliche Ausgang und ausdruecklich kein Mangel.
 */
private val landmarks: List<Landmark> = listOf(
    // Deutschland und Nachbarn zuerst: das ist die Gegend, in der diese App
    // benutzt wird, und dort soll die Auswahl am dichtesten sein.
    Landmark("Berlin", 52.520, 13.405),
    Landmark("Hamburg", 53.551, 9.994),
    Landmark("München", 48.137, 11.576),
    Landmark("Köln", 50.938, 6.960),
    Landmark("Frankfurt am Main", 50.110, 8.682),
    Landmark("Stuttgart", 48.776, 9.182),
    Landmark("Dresden", 51.050, 13.738),
    Landmark("Wien", 48.208, 16.373),
    Landmark("Zürich", 47.377, 8.540),
    Landmark("Prag", 50.075, 14.437),
    Landmark("Amsterdam", 52.370, 4.895),
    Landmark("Brüssel", 50.851, 4.352),
    Landmark("Kopenhagen", 55.676, 12.568),
    Landmark("Warschau", 52.230, 21.012),
    Landmark("Danzig", 54.352, 18.646),
    Landmark("Krakau", 50.065, 19.945),
    Landmark("Budapest", 47.498, 19.040),
    Landmark("Ljubljana", 46.056, 14.506),
    Landmark("Zagreb", 45.815, 15.982),
    Landmark("Innsbruck", 47.269, 11.404),
    // Uebriges Europa.
    Landmark("Paris", 48.857, 2.352),
    Landmark("London", 51.507, -0.128),
    Landmark("Madrid", 40.417, -3.704),
    Landmark("Rom", 41.903, 12.496),
    Landmark("Mailand", 45.464, 9.190),
    Landmark("Barcelona", 41.385, 2.173),
    Landmark("Lissabon", 38.722, -9.139),
    Landmark("Porto", 41.150, -8.611),
    Landmark("Sevilla", 37.389, -5.984),
    Landmark("Valencia", 39.470, -0.377),
    Landmark("Bilbao", 43.263, -2.935),
    Landmark("Bordeaux", 44.838, -0.579),
    Landmark("Lyon", 45.764, 4.836),
    Landmark("Marseille", 43.296, 5.370),
    Landmark("Toulouse", 43.605, 1.444),
    Landmark("Nizza", 43.700, 7.265),
    Landmark("Neapel", 40.852, 14.268),
    Landmark("Palermo", 38.116, 13.361),
    Landmark("Dublin", 53.350, -6.260),
    Landmark("Edinburgh", 55.953, -3.188),
    Landmark("Oslo", 59.914, 10.752),
    Landmark("Stockholm", 59.329, 18.069),
    Landmark("Göteborg", 57.709, 11.974),
    Landmark("Helsinki", 60.170, 24.938),
    Landmark("Tromsø", 69.649, 18.956),
    Landmark("Reykjavík", 64.147, -21.942),
    Landmark("Riga", 56.949, 24.105),
    Landmark("Tallinn", 59.437, 24.754),
    Landmark("Vilnius", 54.687, 25.280),
    Landmark("Minsk", 53.902, 27.562),
    Landmark("Kiew", 50.451, 30.523),
    Landmark("Moskau", 55.756, 37.617),
    Landmark("Bukarest", 44.427, 26.103),
    Landmark("Belgrad", 44.787, 20.449),
    Landmark("Sofia", 42.698, 23.322),
    Landmark("Athen", 37.984, 23.728),
    Landmark("Istanbul", 41.009, 28.978),
    // Ein Minimum an Welt, damit eine Kachel auf Reisen nicht namenlos ist.
    Landmark("New York", 40.713, -74.006),
    Landmark("Los Angeles", 34.052, -118.244),
    Landmark("Toronto", 43.653, -79.383),
    Landmark("Mexiko-Stadt", 19.433, -99.133),
    Landmark("Buenos Aires", -34.604, -58.382),
    Landmark("São Paulo", -23.551, -46.633),
    Landmark("Kapstadt", -33.925, 18.424),
    Landmark("Kairo", 30.044, 31.236),
    Landmark("Marrakesch", 31.630, -7.981),
    Landmark("Dubai", 25.205, 55.271),
    Landmark("Bangkok", 13.756, 100.502),
    Landmark("Tokio", 35.690, 139.692),
    Landmark("Sydney", -33.869, 151.209),
)

/** Hoechstzahl der Beispielorte je Kachel. Drei passen in eine Listenzeile. */
private const val MAX_LANDMARKS_PER_TILE = 3

/**
 * Die Beispielorte je Kachelname, einmal beim ersten Zugriff aus [landmarks]
 * berechnet. Die Einsortierung laeuft ueber [segmentFileName] — also genau
 * ueber dieselbe Rechnung, mit der auch die Engine ihre Kachel sucht.
 */
private val landmarksByTile: Map<String, List<String>> by lazy {
    landmarks
        .groupBy { parseSegmentTile(segmentFileName(it.lat, it.lon))?.name.orEmpty() }
        .filterKeys { it.isNotEmpty() }
        .mapValues { (_, entries) -> entries.take(MAX_LANDMARKS_PER_TILE).map { it.name } }
}

// ---------------------------------------------------------------------------
// Aktualitaet: Delta oder Vollabzug?
// ---------------------------------------------------------------------------

/**
 * Was der Server ueber eine Kachel sagt — die Antwort auf eine `HEAD`-Anfrage,
 * roh und ohne Deutung.
 *
 * `ETag` und `Last-Modified` werden **als Zeichenkette** gemerkt und spaeter
 * nur verglichen, nicht interpretiert: Ein `ETag` ist fuer den Client ein
 * undurchsichtiges Kennzeichen, und die Aktualitaetsfrage ist eine reine
 * Gleichheitsfrage.
 */
data class RemoteSegment(
    val fileName: String,
    val sizeBytes: Long,
    val eTag: String? = null,
    val lastModified: String? = null,
)

/**
 * Was lokal liegt — Dateigroesse plus die beim Download gemerkten Kopfzeilen.
 *
 * ## Warum [lastModified] und nicht das Datum der Datei?
 * Das Aenderungsdatum der lokalen Datei sagt, wann **wir** sie geschrieben
 * haben, nicht wie alt die **Kartendaten** darin sind. Wer eine drei Wochen
 * alte Kachel heute herunterlaedt, haette danach eine „taufrische" Datei mit
 * drei Wochen alten Wegen. Umgekehrt setzen Kopiervorgaenge, Backups und
 * Wiederherstellungen das Dateidatum neu, ohne dass sich am Inhalt etwas
 * aendert. Massgeblich ist deshalb allein die vom Server gemeldete
 * `Last-Modified`-Angabe, die beim Download mitgeschrieben wird.
 */
data class LocalSegment(
    val fileName: String,
    val sizeBytes: Long,
    val eTag: String? = null,
    val lastModified: String? = null,
)

/** Was mit einer Kachel zu geschehen hat. */
enum class SegmentUpdateAction {
    /** Nichts zu tun — lokaler Stand und Server stimmen ueberein. */
    UP_TO_DATE,

    /** Erst das Delta versuchen; scheitert es, den Vollabzug. */
    DELTA,

    /** Direkt den Vollabzug; ein Delta kann es hier nicht geben. */
    FULL,
}

/**
 * Entscheidet, wie eine Kachel auf den aktuellen Stand kommt.
 *
 * ## Warum das Delta die Regel ist
 * Gemessen am 13.08.2026 fuer `E10_N50`: die volle Kachel 124.551.246 Bytes,
 * die Deltas der letzten Tage 0,25–1,6 MB — rund ein Hundertstel. Ohne diesen
 * Weg waere die (taeglich erneuerte!) Kachel ueber Mobilfunk nicht zu pflegen.
 * Ein Fehlschlag des Delta-Wegs kostet nur eine `HEAD`-Anfrage, danach laeuft
 * der Vollabzug — deshalb ist [SegmentUpdateAction.DELTA] die Vorgabe, sobald
 * ueberhaupt eine lokale Datei da ist.
 *
 * [SegmentUpdateAction.FULL] kommt nur, wenn ein Delta **ausgeschlossen** ist:
 * ohne lokale Datei, oder wenn der lokale Stand aelter als
 * [segmentDeltaHistoryDays] Tage ist — so lange und nicht laenger schreibt der
 * Server alte Deltas fort (siehe dort).
 *
 * @param local `null`, wenn die Kachel noch gar nicht da ist.
 */
fun planSegmentUpdate(local: LocalSegment?, remote: RemoteSegment): SegmentUpdateAction {
    if (local == null || local.sizeBytes <= 0L) return SegmentUpdateAction.FULL
    if (isSameSegmentVersion(local, remote)) return SegmentUpdateAction.UP_TO_DATE

    val localMs = parseHttpDateMs(local.lastModified)
    val remoteMs = parseHttpDateMs(remote.lastModified)
    if (localMs != null && remoteMs != null) {
        val ageMs = remoteMs - localMs
        if (ageMs > segmentDeltaHistoryDays * 86_400_000L) return SegmentUpdateAction.FULL
    }
    return SegmentUpdateAction.DELTA
}

/**
 * Ist der lokale Stand derselbe wie der auf dem Server?
 *
 * Reihenfolge der Belege: Groesse (eine abweichende Groesse ist immer ein
 * Unterschied, egal was die Kopfzeilen sagen — so fliegt auch eine
 * halbgeschriebene Datei auf), dann `ETag`, sonst `Last-Modified`. Fehlt
 * beides, gilt der Stand als **unbekannt und damit veraltet**: lieber einmal
 * zu viel nachfragen als mit alten Wegen routen. Teuer ist das nicht — die
 * Nachfrage endet ueber [SegmentUpdateAction.DELTA] beim 0-Byte-Delta des
 * Servers, das keine Daten kostet.
 */
fun isSameSegmentVersion(local: LocalSegment, remote: RemoteSegment): Boolean {
    if (remote.sizeBytes > 0 && local.sizeBytes != remote.sizeBytes) return false
    val localTag = local.eTag
    val remoteTag = remote.eTag
    if (localTag != null && remoteTag != null) return localTag == remoteTag
    val localDate = local.lastModified
    val remoteDate = remote.lastModified
    if (localDate != null && remoteDate != null) return localDate == remoteDate
    return false
}

/**
 * Liest ein HTTP-Datum (`Thu, 13 Aug 2026 01:03:01 GMT`) in Millisekunden seit
 * 1970. `null`, wenn nichts oder etwas Unlesbares hereinkommt.
 *
 * Nur das heute uebliche RFC-1123-Format: Die beiden veralteten Formate aus
 * RFC 7231 (RFC 850 und `asctime`) liefert kein Server, den diese App
 * anspricht, und ein Fehlschlag ist hier harmlos — er fuehrt lediglich dazu,
 * dass das Alter unbekannt bleibt.
 */
fun parseHttpDateMs(value: String?): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
}
