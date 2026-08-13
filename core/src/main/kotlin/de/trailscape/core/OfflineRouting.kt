package de.trailscape.core

import btools.router.FormatJson
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Routenberechnung **auf dem Geraet** mit der eingebetteten
 * BRouter-Engine — das Gegenstueck zu [fetchRoute], das dafuer den
 * oeffentlichen Server brouter.de anruft.
 *
 * ## Warum ueberhaupt offline?
 *
 * Der oeffentliche Server ist eine Gemeinschaftsressource mit einer festen
 * Zahl Routing-Threads. Unter Last wirft er Anfragen ab („operation killed by
 * thread-priority-watchdog", ausfuehrlich beschrieben in `Routing.kt`), und
 * das sieht der Nutzer als Fehler — obwohl mit seiner Anfrage nichts falsch
 * ist. Rechnet die App selbst, entfaellt der Watchdog, die Netzabhaengigkeit
 * und die Wartezeit auf einen fremden Rechner.
 *
 * ## Warum ein eigener Wrapper und nicht direkt `btools.*`?
 *
 * BRouters Klassen sind als **interne** API des Projekts gedacht und geben
 * kein Rueckwaertskompatibilitaetsversprechen: Felder sind oeffentlich und
 * veraenderlich, Fehler kommen als `IllegalArgumentException` mit englischem
 * Freitext, und beim Fork fuer c:geo mussten Klassen bereits umbenannt
 * werden. Wanderte das quer durch die App, muesste bei jedem Engine-Update
 * an vielen Stellen nachgezogen werden. `btools.*` ist deshalb ausserhalb von
 * `:core` ueberhaupt nicht sichtbar (`:core` bindet `:brouter` als
 * `implementation` ein, nicht als `api`), und innerhalb von `:core` beruehren
 * es genau zwei Dateien: diese hier fuer das Routing und `SegmentDelta.kt`
 * fuer den Umgang mit dem Kachelformat. Der Schaden eines Upstream-Umbaus
 * bleibt damit lokal.
 *
 * ## Warum liegt das in `:core` und nicht in einem eigenen `:routing`-Modul?
 *
 * Erwogen und verworfen. Der Wrapper besteht im Kern aus zwei Uebersetzungen —
 * Wegpunkte hinein, GeoJSON heraus — und beide Seiten gehoeren `:core`:
 * [Waypoint]/[PlannedRoute] als Datentypen und vor allem [parseBrouterGeoJson]
 * als Parser. Ein Zwischenmodul muesste `:core` ohnehin benutzen (oder die
 * Typen verdoppeln) und braechte fuer eine einzige Datei eine dritte
 * Modulgrenze plus einen neuen Eintrag in jedem abhaengigen Bau-Skript.
 * Die Sorge, die ein eigenes Modul adressieren wuerde — `:core` verliert seine
 * Unabhaengigkeit —, greift hier nicht: die fuenf eingebundenen
 * BRouter-Module sind reines Java **ohne einen einzigen `android.*`-Import**,
 * `:core` bleibt also android-frei und weiterhin ohne Emulator testbar.
 * Sollte spaeter mehr Engine-nahe Logik dazukommen (Kachelverwaltung, eigene
 * Profile, Hoehenmodelle), ist das Herausloesen in `:routing` ein reiner
 * Verschiebe-Schritt — genau eine Datei zieht um.
 *
 * ## Was hier bewusst NICHT passiert
 *
 * Kein Herunterladen, Aktualisieren oder Loeschen von Kartendaten: Der
 * Wrapper bekommt Segmentverzeichnis und Profildatei als fertige
 * `java.io.File` **hereingereicht** (siehe [routeOffline]). Das ist der Grund,
 * warum `:core` android-frei bleiben kann — wo diese Verzeichnisse liegen
 * (App-Speicher, SD-Karte, Testverzeichnis), weiss nur der Aufrufer.
 *
 * Auch kein Aufteilen langer Strecken in Teilanfragen wie in [planRouteLegs]:
 * Das ist ausschliesslich ein Gegenmittel gegen den Watchdog des geteilten
 * Servers. Lokal gibt es keinen Watchdog; eine lange Strecke darf einfach
 * lange rechnen (gemessen auf dem Entwicklungsrechner, einkernig: 23 km in
 * rund 1,6 s, 88 km in rund 4,4 s, Spitzenverbrauch im Heap unter 60 MB).
 */

/**
 * Fehler bei der Berechnung auf dem Geraet. Die [message] ist bereits eine
 * fertige deutsche Meldung fuer die Oberflaeche.
 *
 * [missingSegmentFile] traegt den Dateinamen der fehlenden Kachel **zusaetzlich
 * maschinenlesbar** mit (z. B. `E5_N50.rd5`). Nur so kann eine spaetere Stufe
 * aus dem Fehler einen konkreten Download anbieten, ohne die Meldung wieder
 * zerlegen zu muessen — Text ist fuer Menschen, dieses Feld fuer Code.
 */
class OfflineRoutingException(
    message: String,
    val missingSegmentFile: String? = null,
) : Exception(message)

/** Meldung, wenn das Profil (`*.brf`) nicht am erwarteten Ort liegt. */
const val errorOfflineProfileMissing: String =
    "Das Routing-Profil fehlt. Starte die App neu, damit sie es neu anlegt."

/** Meldung, wenn `lookups.dat` neben dem Profil fehlt. */
const val errorOfflineLookupsMissing: String =
    "Die Routing-Merkmalstabelle (lookups.dat) fehlt neben dem Profil. " +
        "Starte die App neu, damit sie sie neu anlegt."

/**
 * Meldung, wenn ueberhaupt kein Kartenverzeichnis da ist — der Zustand vor
 * dem allerersten Download.
 */
const val errorOfflineNoSegments: String =
    "Es sind noch keine Offline-Karten gespeichert. Lade zuerst die Karte für " +
        "deine Gegend herunter."

/** Meldung, wenn die Engine ueberhaupt keine Verbindung zwischen den Punkten findet. */
const val errorOfflineNoTrack: String =
    "Zwischen diesen Punkten wurde keine Route gefunden. Setz sie näher an " +
        "einen befahrbaren Weg."

/** Meldung, wenn die lokale Berechnung ihr Zeitlimit reisst. */
const val errorOfflineTimeout: String =
    "Die Berechnung hat zu lange gedauert. Versuch es mit näheren Wegpunkten " +
        "noch einmal."

/** Hoechstlaenge des in die Meldung uebernommenen Engine-Texts. */
private const val MAX_ENGINE_TEXT_CHARS = 200

/**
 * Speicher in MB fuer BRouters Knoten-Cache (`RoutingContext.memoryclass`).
 *
 * 64 MB ist der Wert, den auch die offizielle BRouter-Android-App fuer
 * gewoehnliche Geraete setzt. Gemessener Spitzenverbrauch einer 170-km-Route
 * lag bei 28–30 MB, es ist also Reserve nach oben — und gleichzeitig weit
 * unter dem, was Android einer App zugesteht.
 */
private const val NODE_CACHE_MB = 64

// ---------------------------------------------------------------------------
// Koordinaten-Kodierung
// ---------------------------------------------------------------------------

/**
 * Kodiert einen Laengengrad in BRouters ganzzahlige Darstellung:
 * Mikrograd mit Nullpunkt am 180. Laengengrad, also immer positiv.
 *
 * `Math.round` und nicht Abschneiden: Bei Abschneiden landete `-0.0000004`
 * einen Mikrograd daneben, was zwar nur rund 7 cm sind, aber unnoetig — die
 * Engine rechnet ausschliesslich in diesen Ganzzahlen weiter.
 */
internal fun encodeLon(lon: Double): Int = ((lon + 180.0) * 1_000_000.0).roundToLong().toInt()

/** Kodiert einen Breitengrad analog zu [encodeLon], Nullpunkt am Suedpol. */
internal fun encodeLat(lat: Double): Int = ((lat + 90.0) * 1_000_000.0).roundToLong().toInt()

/**
 * Der Dateiname der Kachel, in der [lat]/[lon] liegt — z. B. `E10_N50.rd5`.
 *
 * BRouter benennt Kacheln nach ihrer **Suedwestecke** in einem 5°×5°-Raster
 * ([segmentGridDeg]) und legt sie flach in das Segmentverzeichnis.
 * Nachgebildet aus `NodesCache.fileForSegment`; oeffentlich, weil die
 * Kachelverwaltung genau diese Zuordnung braucht (welche Kachel muss fuer eine
 * geplante Route da sein?) und weil sie sich so ohne Kacheldatei testen
 * laesst. Die Gegenrichtung — vom Namen zurueck zur Flaeche — steht in
 * `RoutingSegments.kt` ([parseSegmentTile]).
 */
fun segmentFileName(lat: Double, lon: Double): String {
    // Wie in BRouter: erst in die immer positive Mikrograd-Darstellung, dann
    // ganzzahlig auf volle Grad. Der Umweg ueber die positive Achse ist
    // wichtig, damit das Abrunden auch westlich von Greenwich bzw. sued-
    // lich des Aequators in die richtige Richtung geht.
    val lonDegree = floor(lon + 180.0).toInt()
    val latDegree = floor(lat + 90.0).toInt()
    val lonSw = lonDegree - 180 - lonDegree.mod(segmentGridDeg)
    val latSw = latDegree - 90 - latDegree.mod(segmentGridDeg)
    val lonPart = if (lonSw < 0) "W${-lonSw}" else "E$lonSw"
    val latPart = if (latSw < 0) "S${-latSw}" else "N$latSw"
    return "${lonPart}_$latPart.rd5"
}

// ---------------------------------------------------------------------------
// Fehleruebersetzung
// ---------------------------------------------------------------------------

/**
 * Erkennt in einer Engine-Meldung den Namen der fehlenden Kacheldatei.
 *
 * Die Engine meldet `datafile E5_N50.rd5 not found` (der Server stellt dem
 * noch ein `ERROR: ` voran, wenn dieselbe Meldung ueber HTTP geht — beide
 * Formen werden erkannt, damit die Uebersetzung auch fuer gecannte
 * Serverantworten in Tests greift).
 */
internal fun missingSegmentFileOf(engineMessage: String): String? =
    Regex("""datafile\s+(\S+\.rd5)\s+not found""", RegexOption.IGNORE_CASE)
        .find(engineMessage)
        ?.groupValues
        ?.get(1)

/**
 * Uebersetzt eine rohe Engine-Meldung in eine deutsche Meldung — dasselbe
 * Muster wie [routingErrorMessage] fuer die Serverantworten: bekannte Faelle
 * bekommen einen verstaendlichen Satz, alles Unbekannte eine generische
 * Meldung, die den Originaltext **in Klammern** mitfuehrt, damit Bugreports
 * diagnostizierbar bleiben.
 */
internal fun offlineRoutingErrorMessage(engineMessage: String): OfflineRoutingException {
    val text = engineMessage.trim().replace(Regex("\\s+"), " ")

    val missing = missingSegmentFileOf(text)
    if (missing != null) {
        return OfflineRoutingException(
            "Für diesen Bereich fehlen die Offline-Kartendaten (Kachel $missing). " +
                "Lade sie herunter, um hier ohne Netz zu routen.",
            missingSegmentFile = missing,
        )
    }

    val lower = text.lowercase()
    // Kein Kartenverzeichnis: die Engine wirft das, bevor sie ueberhaupt nach
    // einer einzelnen Kachel sucht, kann also keinen Dateinamen nennen.
    // [routeOffline] faengt den Fall schon vorher ab und ergaenzt ihn dort um
    // die noetige Kachel; dieser Zweig greift nur, wenn das Verzeichnis
    // *waehrend* eines Laufs verschwindet.
    if (lower.contains("segment directory") && lower.contains("does not exist")) {
        return OfflineRoutingException(errorOfflineNoSegments)
    }
    // "…-position not mapped in existing datafile" heisst: die Kachel ist da,
    // aber am Wegpunkt liegt nichts Befahrbares in Reichweite.
    if (lower.contains("not mapped in existing datafile") ||
        lower.contains("no track found") ||
        lower.contains("island detected")
    ) {
        return OfflineRoutingException(errorOfflineNoTrack)
    }
    if (lower.contains("timeout after")) {
        return OfflineRoutingException(errorOfflineTimeout)
    }

    if (text.isEmpty()) {
        return OfflineRoutingException(errorRouteFailed)
    }
    val shortened = if (text.length > MAX_ENGINE_TEXT_CHARS) {
        text.take(MAX_ENGINE_TEXT_CHARS) + "…"
    } else {
        text
    }
    return OfflineRoutingException(
        "Route konnte nicht berechnet werden. (Meldung der Routing-Engine: $shortened)",
    )
}

// ---------------------------------------------------------------------------
// Der eigentliche Aufruf
// ---------------------------------------------------------------------------

/**
 * Sperre, die **alle** Aufrufe der Engine hintereinander reiht.
 *
 * Zwingend: BRouters `ProfileCache` ist ein statischer Singleton mit einem
 * festen Satz Cache-Plaetze, die er beim Betreten belegt („busy") und erst
 * am Ende von `doRun` wieder freigibt. Zwei gleichzeitige Berechnungen
 * wuerden sich denselben `BExpressionContext` teilen bzw. sich gegenseitig
 * die Plaetze wegnehmen — mit Ergebnissen, die von der Verschraenkung
 * abhaengen. Die Engine ist also nicht nebenlaeufig benutzbar, und keine
 * Menge Vorsicht im Aufrufer aendert daran etwas; die einzige verlaessliche
 * Antwort ist, hier zu serialisieren.
 *
 * Ein eigenes Objekt statt `synchronized(this)` o. ae., damit die Sperre
 * nicht versehentlich von aussen gehalten werden kann.
 */
private val routingEngineLock = Any()

/**
 * Fuehrt [block] unter [routingEngineLock] aus — die einzige Stelle, an der
 * die Engine betreten wird.
 *
 * Eigene Funktion statt eines `synchronized`-Blocks direkt in [routeOffline],
 * damit sich die Serialisierung testen laesst, ohne dafuer Kacheldaten zu
 * brauchen: Der Test schickt mehrere Threads hier hindurch und prueft, dass
 * sich nie zwei ueberlappen.
 */
internal fun <T> withRoutingEngineLock(block: () -> T): T =
    synchronized(routingEngineLock) { block() }

/**
 * Zaehlt abgeschlossene Engine-Laeufe. Nur fuer Tests gedacht: Das Hochzaehlen
 * ist bewusst **nicht** atomar, damit ein verlorener Zaehlschritt auffliegt,
 * wenn die Serialisierung je kaputtgeht.
 */
@Volatile
internal var offlineRoutingRunCount: Int = 0
    private set

/**
 * Berechnet eine Route mit der eingebetteten BRouter-Engine.
 *
 * @param waypoints Mindestens zwei Punkte in Reihenfolge (Start, Zwischen…, Ziel).
 * @param segmentDir Verzeichnis mit den `*.rd5`-Kacheln, **flach** abgelegt.
 *   Darf fehlen oder leer sein; in beiden Faellen kommt eine verstaendliche
 *   Meldung samt der benoetigten Kachel zurueck (siehe
 *   [OfflineRoutingException.missingSegmentFile]).
 * @param profileFile Das BRouter-Profil (`*.brf`). **In seinem Verzeichnis muss
 *   `lookups.dat` liegen** — BRouters `ProfileCache` liest die Merkmalstabelle
 *   fest als `new File(profileDir, "lookups.dat")`, ohne sie startet nichts.
 * @param maxRunningTimeMs Zeitlimit der Berechnung; `0` bedeutet ohne Limit.
 *   Der Aufruf laeuft **synchron im aufrufenden Thread**, gehoert auf Android
 *   also auf einen Hintergrund-Dispatcher.
 *
 * @throws OfflineRoutingException mit fertiger deutscher Meldung.
 */
fun routeOffline(
    waypoints: List<Waypoint>,
    segmentDir: File,
    profileFile: File,
    maxRunningTimeMs: Long = 0,
): PlannedRoute {
    if (waypoints.size < 2) {
        throw OfflineRoutingException("Mindestens zwei Wegpunkte nötig.")
    }

    // Vorab pruefen statt die Engine hineinlaufen zu lassen: Bei fehlendem
    // Profil wirft schon der Konstruktor von RoutingEngine — bei
    // `localFunction == null` sogar eine nackte NullPointerException, weil er
    // ungeprueft `new File(rc.localFunction).getParentFile()` aufruft. Ein
    // eigener, klarer Fehler ist da in jedem Fall besser als ein Stacktrace.
    if (!profileFile.isFile) {
        throw OfflineRoutingException(errorOfflineProfileMissing)
    }
    val lookups = File(profileFile.parentFile ?: File("."), "lookups.dat")
    if (!lookups.isFile) {
        throw OfflineRoutingException(errorOfflineLookupsMissing)
    }

    // Fehlt das Kartenverzeichnis komplett, wirft die Engine
    // "segment directory … does not exist" — ohne einen Kachelnamen, weil sie
    // gar nicht erst danach sucht. Hier vorher abzufangen kostet nichts und
    // erlaubt es, die zuerst benoetigte Kachel trotzdem mitzugeben, damit die
    // spaetere Kachelverwaltung auch aus diesem Fall einen konkreten Download
    // machen kann.
    if (!segmentDir.isDirectory) {
        throw OfflineRoutingException(
            errorOfflineNoSegments,
            missingSegmentFile = segmentFileName(waypoints[0].lat, waypoints[0].lon),
        )
    }

    val nodes = waypoints.map { wp ->
        OsmNodeNamed().apply {
            ilon = encodeLon(wp.lon)
            ilat = encodeLat(wp.lat)
            // Die Engine benutzt den Namen in ihren Fehlermeldungen
            // ("from-position not mapped …"); ohne Namen stuende dort `null`.
            name = "wp"
        }
    }

    val geoJson = withRoutingEngineLock {
        try {
            runEngine(nodes, segmentDir, profileFile, maxRunningTimeMs)
        } finally {
            offlineRoutingRunCount++
        }
    }

    // Bewusst durch denselben Parser wie die Serverantwort: `FormatJson` ist
    // exakt die Klasse, die auch brouter.de zum Ausliefern benutzt — gleiche
    // Struktur, gleiche Eigenheiten (`track-length`/`filtered ascend` als
    // Zeichenketten, Hoehe als drittes Element der Koordinate). Ein zweiter
    // Parser waere eine zweite Wahrheit, die auseinanderlaufen kann.
    return parseBrouterGeoJson(geoJson)
}

/** Der Engine-Aufruf selbst. Laeuft immer unter [routingEngineLock]. */
private fun runEngine(
    nodes: List<OsmNodeNamed>,
    segmentDir: File,
    profileFile: File,
    maxRunningTimeMs: Long,
): String {
    val rc = RoutingContext()
    // Pflichtfeld: ein Dateipfad, kein Profilname. Siehe Konstruktor-Hinweis
    // oben — bei `null` wirft die Engine eine NullPointerException.
    rc.localFunction = profileFile.absolutePath
    rc.memoryclass = NODE_CACHE_MB

    val engine = try {
        RoutingEngine(
            null, // kein Ausgabe-Dateiname: wir wollen das Ergebnis im Speicher
            null, // kein Protokoll-Dateiname
            segmentDir,
            nodes,
            rc,
            RoutingEngine.BROUTER_ENGINEMODE_ROUTING,
        )
    } catch (e: Exception) {
        // Der Konstruktor liest bereits das Profil (`ProfileCache.parseProfile`)
        // und wirft bei kaputtem oder unlesbarem Profil.
        throw offlineRoutingErrorMessage(e.message ?: e.toString())
    }
    // Sonst schreibt die Engine ihren Fortschritt auf System.out — auf Android
    // ist das nur Rauschen im Logcat.
    engine.quite = true

    try {
        engine.doRun(maxRunningTimeMs)
    } catch (e: Exception) {
        // `doRun` faengt intern eigentlich alles ab und legt es in
        // `getErrorMessage()`; dieser Zweig ist die Sicherung fuer alles, was
        // dort durchrutscht (z. B. Fehler ausserhalb des inneren try).
        throw offlineRoutingErrorMessage(e.message ?: e.toString())
    }

    engine.errorMessage?.let { throw offlineRoutingErrorMessage(it) }

    val track = engine.foundTrack ?: throw OfflineRoutingException(errorOfflineNoTrack)
    return FormatJson(rc).format(track)
}

/**
 * Die Kacheln, die fuer eine Route mit [waypoints] mindestens vorhanden sein
 * muessen — die Kacheln aller Wegpunkte plus die dazwischen ueberflogenen.
 *
 * Bewusst nur die Luftlinie abgetastet und nicht die spaetere echte Strecke:
 * Die kennt vor der Berechnung niemand. Fuer die Frage „reicht mein
 * Kartenbestand ungefaehr?" genuegt das; die verlaessliche Antwort bleibt
 * [OfflineRoutingException.missingSegmentFile] aus einem echten Lauf.
 * Steht hier schon bereit, weil die Kachelverwaltung (naechste Stufe) genau
 * darauf aufsetzt.
 */
fun requiredSegmentFiles(waypoints: List<Waypoint>): Set<String> {
    val out = linkedSetOf<String>()
    for (wp in waypoints) {
        out.add(segmentFileName(wp.lat, wp.lon))
    }
    for (i in 1 until waypoints.size) {
        val a = waypoints[i - 1]
        val b = waypoints[i]
        // Alle rund 25 km ein Abtastpunkt: deutlich feiner als die 5°-Kacheln
        // (die am Aequator rund 550 km breit sind), also kann keine dazwischen
        // uebersprungen werden.
        val steps = (airDistanceM(a, b) / 25_000.0).roundToInt().coerceIn(1, 400)
        for (k in 1 until steps) {
            val p = geodesicPoint(a, b, k.toDouble() / steps)
            out.add(segmentFileName(p.lat, p.lon))
        }
    }
    return out
}
