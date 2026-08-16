package de.trailscape.core

import java.io.File

/**
 * Die Entscheidung **offline oder Server** — und die Ausfuehrung, die daraus
 * folgt.
 *
 * ## Warum diese Entscheidung hier liegt und nicht in `:app`
 *
 * Sie besteht aus drei Fragen, und keine davon ist eine Android-Frage:
 *
 *  1. Welche Kacheln braucht diese Route? ([requiredSegmentFiles])
 *  2. Sind sie alle da, und gibt es fuer den Fahrmodus ueberhaupt ein lokales
 *     Profil? ([chooseRoutingSource])
 *  3. Wenn die lokale Rechnung scheitert — war es die fehlende Kachel (dann
 *     hat der Nutzer etwas davon, es zu erfahren) oder etwas anderes (dann ist
 *     der Server der stille Rueckfall)?
 *
 * Das ist Regelwerk, kein Dateisystem und kein Netz. In `:app` waere es genau
 * die Sorte Logik, die niemand testen kann, ohne einen Emulator zu starten —
 * hier steht sie neben den beiden Wegen, zwischen denen sie waehlt
 * ([routeOffline], [fetchRoute]), und ist mit gewoehnlichen JVM-Tests
 * abgedeckt.
 *
 * Damit `:core` dabei android-frei bleibt, kommt alles Ortsgebundene
 * **hereingereicht**: [OfflineRoutingSetup] traegt das Kachelverzeichnis, die
 * Profildatei und den Bestand als fertige Werte. Wo diese Verzeichnisse
 * liegen, weiss weiterhin nur `:app` (`data/OfflineRoutingFiles.kt`), und wer
 * den Bestand fuehrt, ebenfalls (`routing/SegmentInventory.kt`). Dasselbe
 * Prinzip wie bei [routeOffline], nur eine Ebene hoeher.
 *
 * ## Warum die Etappen-Aufteilung offline **bleibt**
 *
 * Naheliegend waere, sie offline wegzulassen: Sie entstand allein gegen den
 * Watchdog des geteilten Servers (siehe `Routing.kt`), und den gibt es auf dem
 * Geraet nicht. Trotzdem bleibt sie — aus zwei Gruenden, von denen der erste
 * der wichtigere ist:
 *
 *  1. **Sonst haengt die Route davon ab, ob eine Kachel zufaellig da ist.**
 *     Stufe 1 hat nachgewiesen, dass die eingebettete Engine byte-identisch
 *     zum Server rechnet — *bei gleicher Eingabe*. Die Aufteilung aendert die
 *     Eingabe (sie setzt Zwischenpunkte auf die Geodaete). Wuerde nur der
 *     Serverweg aufteilen, bekaeme dieselbe Wegpunktliste offline eine andere
 *     Strecke als online, und der Nutzer saehe seine Route springen, sobald er
 *     eine Kachel laedt oder loescht. Gleiche Eingabe, gleiches Ergebnis —
 *     dafuer muessen beide Wege dieselben Legs bilden.
 *  2. **Die Rechenzeit waechst ungefaehr quadratisch mit der Luftlinie.**
 *     Gemessen (Entwicklungsrechner, einkernig): 23 km in 1,6 s, 88 km in
 *     4,4 s; auf einem Telefon das Zwei- bis Vierfache. Ein Leg von 300 km
 *     laege danach bei rund einer Minute auf dem Rechner und mehreren auf dem
 *     Telefon. Zwei Legs von 150 km kosten zusammen etwa die Haelfte davon —
 *     die Aufteilung ist offline also nicht nur unschaedlich, sie ist der
 *     Grund, warum eine lange Strecke ueberhaupt in ertraeglicher Zeit fertig
 *     wird.
 *
 * Nicht uebernommen wird die **Pause** zwischen zwei Legs
 * ([legRequestPauseMs]): Sie ist Ruecksicht auf eine Gemeinschaftsressource.
 * Der eigene Prozessor braucht keine.
 */

// ---------------------------------------------------------------------------
// Was `:app` hereinreicht
// ---------------------------------------------------------------------------

/**
 * Alles Ortsgebundene fuer die lokale Berechnung — von `:app` gefuellt, von
 * `:core` nur gelesen.
 *
 * @param segmentDir Verzeichnis mit den `*.rd5`-Kacheln (siehe [routeOffline]).
 * @param profileFile Das Profil (`*.brf`) fuer den gewaehlten Fahrmodus, oder
 *   `null`, wenn dieser Fahrmodus offline **nicht** abgedeckt ist (siehe
 *   [offlineBrouterProfile]). `null` ist kein Fehler, sondern schlicht die
 *   Ansage „fuer diesen Modus bitte den Server".
 * @param installedSegmentFiles Die Dateinamen der vollstaendig vorhandenen
 *   Kacheln. Bewusst eine fertige Menge und keine Rueckfrage-Funktion: Der
 *   Bestand wird einmal aufgezaehlt, danach entscheidet die reine Rechnung —
 *   so kann die Entscheidung nicht mitten im Pruefen mit einem halb
 *   geschriebenen Verzeichnis reden.
 */
data class OfflineRoutingSetup(
    val segmentDir: File,
    val profileFile: File?,
    val installedSegmentFiles: Set<String>,
)

// ---------------------------------------------------------------------------
// Die Entscheidung
// ---------------------------------------------------------------------------

/** Woher eine Route stammt. */
enum class RoutingSource {
    /** Auf dem Geraet gerechnet ([routeOffline]). */
    OFFLINE,

    /** Ueber brouter.de gerechnet ([fetchRoute]). */
    SERVER,
}

/** Warum der Server genommen wurde, obwohl es offline schneller ginge. */
enum class ServerFallbackReason {
    /** Das Offline-Routing ist gar nicht eingerichtet (kein [OfflineRoutingSetup]). */
    NOT_SET_UP,

    /** Fuer diesen Fahrmodus gibt es kein lokales Profil (siehe [offlineBrouterProfile]). */
    NO_LOCAL_PROFILE,

    /** Mindestens eine noetige Kachel fehlt. */
    MISSING_SEGMENTS,

    /** Die lokale Rechnung ist aus einem anderen Grund gescheitert. */
    OFFLINE_FAILED,
}

/**
 * Das Ergebnis der Entscheidung: welcher Weg, und — falls Server — warum.
 *
 * [missingSegmentFiles] ist auch dann gefuellt, wenn der Server genommen wird;
 * genau daraus baut die Oberflaeche ihr Download-Angebot („Für diese Gegend
 * fehlen die Kartendaten: …"). Die Reihenfolge ist die der Route, damit das
 * Angebot bei der Startgegend anfaengt.
 */
data class RoutingSourceChoice(
    val source: RoutingSource,
    val fallbackReason: ServerFallbackReason?,
    val missingSegmentFiles: List<String>,
)

/**
 * Waehlt den Weg fuer [waypoints] — die eine Stelle, an der „offline zuerst"
 * ausbuchstabiert ist.
 *
 * Offline gewinnt nur, wenn **alles** stimmt: eingerichtet, Profil fuer den
 * Fahrmodus vorhanden, und jede von [requiredSegmentFiles] genannte Kachel
 * liegt lokal. Ein „fast vollstaendig" gibt es nicht — die Engine bricht beim
 * ersten Loch ab, und ein absichtlich herbeigefuehrter Abbruch waere nur
 * verlorene Rechenzeit vor demselben Serveraufruf.
 *
 * @param setup `null`, wenn das Offline-Routing nicht eingerichtet ist.
 */
fun chooseRoutingSource(
    waypoints: List<Waypoint>,
    setup: OfflineRoutingSetup?,
): RoutingSourceChoice {
    if (setup == null) {
        return RoutingSourceChoice(RoutingSource.SERVER, ServerFallbackReason.NOT_SET_UP, emptyList())
    }

    // Die fehlenden Kacheln werden auch dann ermittelt, wenn schon das Profil
    // fehlt — waere die Liste in dem Fall leer, koennte die Oberflaeche fuer
    // „Radwege bevorzugt" nichts anbieten, obwohl derselbe Bestand fuer jeden
    // anderen Fahrmodus gebraucht wird.
    val missing = requiredSegmentFiles(waypoints)
        .filterNot { it in setup.installedSegmentFiles }

    if (setup.profileFile == null) {
        return RoutingSourceChoice(
            RoutingSource.SERVER,
            ServerFallbackReason.NO_LOCAL_PROFILE,
            missing,
        )
    }
    if (missing.isNotEmpty()) {
        return RoutingSourceChoice(
            RoutingSource.SERVER,
            ServerFallbackReason.MISSING_SEGMENTS,
            missing,
        )
    }
    return RoutingSourceChoice(RoutingSource.OFFLINE, null, emptyList())
}

// ---------------------------------------------------------------------------
// Die Ausfuehrung
// ---------------------------------------------------------------------------

/**
 * Eine berechnete Route samt der Auskunft, woher sie kommt.
 *
 * [missingSegmentFiles] ist die Grundlage des Download-Angebots und bleibt
 * leer, wenn nichts fehlt.
 */
data class RoutingResult(
    val route: PlannedRoute,
    val source: RoutingSource,
    val fallbackReason: ServerFallbackReason?,
    val missingSegmentFiles: List<String>,
)

/**
 * Zeitgrenze **je Etappe** der lokalen Berechnung, in Millisekunden.
 *
 * Nicht als Schutz gegen lange Strecken gedacht — dafuer sorgt die
 * Etappen-Aufteilung —, sondern als Notausgang: `routeOffline` blockiert
 * seinen Thread und laesst sich von aussen nicht abbrechen (BRouters
 * `doRun` kennt kein Interrupt). Ohne Grenze koennte eine entartete Anfrage
 * einen Thread dauerhaft binden und ueber die Engine-Sperre jede weitere
 * Berechnung blockieren. 60 s ist derselbe Wert, den der oeffentliche Server
 * fuer eine Anfrage ansetzt; eine Etappe unter [maxLegAirDistanceKm] bleibt
 * auf dem Telefon deutlich darunter.
 */
const val offlineLegTimeoutMs: Long = 60_000

/**
 * Rechnet **auf dem Geraet**, mit derselben Etappen-Aufteilung wie der
 * Serverweg (Begruendung im Datei-KDoc).
 *
 * Blockiert den aufrufenden Thread und ist rechenintensiv — gehoert auf
 * Android auf `Dispatchers.Default`, nicht auf `Dispatchers.IO` und erst recht
 * nicht auf den Hauptthread.
 *
 * @param onProgress `(fertige Etappen, Etappen gesamt)`; wird auch bei nur
 *   einer Etappe aufgerufen (`0/1`, dann `1/1`), damit die Oberflaeche
 *   ueberhaupt anzeigen kann, dass gerechnet wird.
 * @throws OfflineRoutingException mit fertiger deutscher Meldung; bei einer
 *   fehlenden Kachel zusaetzlich mit
 *   [OfflineRoutingException.missingSegmentFile].
 */
fun routeOfflineLegs(
    waypoints: List<Waypoint>,
    setup: OfflineRoutingSetup,
    maxRunningTimeMs: Long = offlineLegTimeoutMs,
    onProgress: ((done: Int, total: Int) -> Unit)? = null,
): PlannedRoute {
    val profile = setup.profileFile
        ?: throw OfflineRoutingException(errorOfflineProfileMissing)

    val legs = planRouteLegs(waypoints)
    onProgress?.invoke(0, legs.size)

    val parts = mutableListOf<PlannedRoute>()
    for ((index, leg) in legs.withIndex()) {
        parts.add(
            routeOffline(
                waypoints = leg,
                segmentDir = setup.segmentDir,
                profileFile = profile,
                maxRunningTimeMs = maxRunningTimeMs,
            ),
        )
        onProgress?.invoke(index + 1, legs.size)
    }

    return if (parts.size == 1) parts.single() else concatRouteLegs(parts)
}

/**
 * Berechnet eine Route auf dem **besseren** der beiden Wege: lokal, wenn alles
 * dafuer da ist, sonst ueber den Server.
 *
 * Das ist die Funktion, die die Oberflaeche aufruft — sie soll nicht selbst
 * entscheiden muessen, und der Nutzer soll von der Entscheidung nichts merken
 * ausser der Geschwindigkeit.
 *
 * ## Wann trotz vorhandener Kacheln der Server drankommt
 * Scheitert die lokale Rechnung, wird **immer** noch der Server versucht — mit
 * einer Ausnahme in der Wirkung, nicht im Ablauf: Meldet die Engine eine
 * fehlende Kachel, landet deren Name in
 * [RoutingResult.missingSegmentFiles], damit die Oberflaeche daraus ein
 * Download-Angebot machen kann. Alles andere (beschaedigte Kachel, Zeitgrenze,
 * kein Weg gefunden) bleibt still: Der Nutzer wollte eine Route, er bekommt
 * eine Route, und ein Fehler ueber eine Technik, die er nie eingeschaltet hat,
 * waere fuer ihn nur Rauschen.
 *
 * Der zusaetzliche Serveraufruf kostet dann die vorher verbrannte Rechenzeit —
 * das ist der Preis dafuer, dass ein einzelner kaputter lokaler Zustand die
 * Planung nicht lahmlegt.
 *
 * @param serverProfileId Profilname bzw. [CUSTOM_GRAVEL_PROFILE] fuer den
 *   Serverweg (siehe [brouterProfile]).
 * @param setup `null`, wenn offline nicht in Frage kommt; dann verhaelt sich
 *   der Aufruf wie [fetchRoute].
 * @param onSource wird **einmal** aufgerufen, sobald feststeht, welcher Weg
 *   gerechnet wird — und noch einmal, wenn nach einem lokalen Fehlschlag doch
 *   der Server drankommt. Die Oberflaeche schreibt daraus ihre Rueckmeldung
 *   („Berechne auf dem Gerät …").
 * @param onProgress `(fertige Etappen, Etappen gesamt)` des gerade laufenden
 *   Wegs.
 * @param serverBaseUrl Basis-URL des Servers fuer den Serverweg (siehe
 *   [fetchRoute]). Vorgabe ist [defaultBrouterServerUrl]; betrifft NUR die
 *   Berechnung, nicht die Segment-Downloads (Begruendung bei
 *   [defaultBrouterServerUrl]).
 */
fun routeOfflineFirst(
    waypoints: List<Waypoint>,
    serverProfileId: String,
    client: HttpClient,
    setup: OfflineRoutingSetup?,
    sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    onSource: ((RoutingSource) -> Unit)? = null,
    onProgress: ((done: Int, total: Int) -> Unit)? = null,
    serverBaseUrl: String = defaultBrouterServerUrl,
): RoutingResult {
    if (waypoints.size < 2) {
        throw Exception("Mindestens zwei Wegpunkte nötig.")
    }

    val choice = chooseRoutingSource(waypoints, setup)
    var missing = choice.missingSegmentFiles
    var reason = choice.fallbackReason

    // Die Ursache des lokalen Fehlschlags — gemerkt, nicht verworfen (siehe
    // unten, wo der Server ebenfalls scheitert).
    var offlineFailure: Exception? = null

    if (choice.source == RoutingSource.OFFLINE && setup != null) {
        onSource?.invoke(RoutingSource.OFFLINE)
        try {
            val route = routeOfflineLegs(waypoints, setup, onProgress = onProgress)
            return RoutingResult(route, RoutingSource.OFFLINE, null, emptyList())
        } catch (e: Exception) {
            // Bewusst **jede** Ausnahme, nicht nur [OfflineRoutingException]:
            // Der Rueckfall auf den Server ist genau fuer den Fall da, dass
            // lokal etwas nicht stimmt — und was das ist, darf nicht darueber
            // entscheiden, ob die Nutzerin eine Route bekommt. (Ein
            // `CancellationException` kann hier nicht auflaufen: Dieser Weg ist
            // durchgehend blockierend und hat keinen Unterbrechungspunkt.)
            //
            // Der Bestand sagte ja, die Engine sagt nein: Das kann passieren,
            // wenn die echte Strecke eine Kachel streift, die die Abtastung
            // entlang der Luftlinie nicht getroffen hat (siehe
            // [requiredSegmentFiles]). Der Name aus der Engine ist die
            // verlaessliche Auskunft — er kommt deshalb ins Angebot.
            offlineFailure = e
            missing = listOfNotNull((e as? OfflineRoutingException)?.missingSegmentFile)
            reason = if (missing.isEmpty()) {
                ServerFallbackReason.OFFLINE_FAILED
            } else {
                ServerFallbackReason.MISSING_SEGMENTS
            }
        }
    }

    onSource?.invoke(RoutingSource.SERVER)
    val route = try {
        fetchRoute(
            waypoints = waypoints,
            profileId = serverProfileId,
            client = client,
            sleeper = sleeper,
            onProgress = onProgress,
            baseUrl = serverBaseUrl,
        )
    } catch (serverFailure: Exception) {
        // Beide Wege sind gescheitert. Frueher gewann hier kommentarlos die
        // Servermeldung — der Nutzer las „Routing-Server nicht erreichbar. Bist
        // du online?", obwohl das eigentliche Problem eine beschaedigte Kachel
        // war, die er loeschen und neu laden koennte. Die lokale Ursache ist die
        // konkretere Auskunft und steht deshalb vorn; die Servermeldung folgt
        // als zweiter Satz, und die urspruengliche Ausnahme bleibt als [cause]
        // fuer Fehlerberichte erhalten.
        throw offlineFailure?.let { offline ->
            OfflineRoutingException(
                message = "${offline.message} " +
                    "Der Routing-Server war anschließend ebenfalls nicht erreichbar " +
                    "(${serverFailure.message}).",
                missingSegmentFile = (offline as? OfflineRoutingException)?.missingSegmentFile,
                cause = offline,
            )
        } ?: serverFailure
    }
    return RoutingResult(route, RoutingSource.SERVER, reason, missing)
}
