package de.trailscape.app.routing

import android.content.Context
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.OfflineRoutingFiles
import de.trailscape.core.OfflineRoutingSetup
import de.trailscape.core.RouteProfile
import de.trailscape.core.RoutingResult
import de.trailscape.core.RoutingSource
import de.trailscape.core.SegmentTile
import de.trailscape.core.Waypoint
import de.trailscape.core.brouterProfile
import de.trailscape.core.chooseRoutingSource
import de.trailscape.core.parseSegmentTile
import de.trailscape.core.routeOfflineFirst
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Die Verkabelung zwischen der Oberflaeche und `:core`s Entscheidung
 * „offline zuerst, Server als Rueckfall" (`OfflineFirstRouting.kt`).
 *
 * ## Was hier passiert — und was ausdruecklich nicht
 *
 * Hier steht **keine** Entscheidung. Ob lokal oder ueber den Server gerechnet
 * wird, beantwortet `:core` (`chooseRoutingSource`); diese Datei besorgt nur
 * das, was `:core` nicht besorgen kann, ohne Android zu kennen: das
 * Kachelverzeichnis, die Profildatei und den Bestand — und sie waehlt den
 * richtigen Thread-Pool.
 *
 * ## Warum die Wahl des Dispatchers hier liegt
 *
 * Die beiden Wege haben gegensaetzliche Anforderungen: Die lokale Berechnung
 * ist **rechenintensiv** (gemessen 88 km in 4,4 s auf dem Entwicklungsrechner,
 * auf einem Telefon das Zwei- bis Vierfache) und gehoert auf
 * [Dispatchers.Default]; der Serverweg wartet dagegen fast nur auf das Netz
 * und gehoert auf [Dispatchers.IO], wo ein blockierter Thread nichts kostet.
 * `:core` kann das nicht entscheiden — es kennt keine Coroutines und soll
 * keine kennen.
 *
 * Deshalb wird [chooseRoutingSource] hier **einmal zusaetzlich** aufgerufen,
 * nur um den Pool zu waehlen. Das ist keine zweite Entscheidung, sondern
 * dieselbe reine Funktion mit demselben Ergebnis; die verbindliche Antwort
 * gibt gleich darauf [routeOfflineFirst] selbst. Die Alternative waere
 * gewesen, `:core` die Ausfuehrung wegzunehmen und den Rueckfall in der
 * Oberflaeche nachzubauen — genau die Logik, die dort niemand testen kann.
 *
 * ## Warum die Aufrufe nicht abbrechbar sind
 *
 * `routeOffline` blockiert seinen Thread und kennt kein Interrupt (BRouters
 * `doRun` fragt nichts ab). Eine abgebrochene Coroutine beendet die Rechnung
 * also **nicht**, sie wartet nur nicht mehr auf sie. Zwei Dinge halten das im
 * Rahmen: die Zeitgrenze je Etappe (`offlineLegTimeoutMs`) und die
 * Entprellung in der Planung (`MapScreen.kt`), die dafuer sorgt, dass beim
 * Setzen mehrerer Wegpunkte hintereinander nur die letzte Rechnung ueberhaupt
 * anlaeuft.
 */

/**
 * Baut den [OfflineRoutingSetup] fuer [profile] zusammen.
 *
 * Greift auf das Dateisystem zu (Bestand aufzaehlen, Profil bei Bedarf
 * auspacken) und gehoert auf [Dispatchers.IO].
 */
private fun offlineRoutingSetup(context: Context, profile: RouteProfile): OfflineRoutingSetup =
    OfflineRoutingSetup(
        segmentDir = OfflineRoutingFiles.segmentDir(context),
        // Bewusst abgesichert: Laesst sich das Profil nicht auspacken (Asset
        // fehlt, Speicher voll), ist das ein Grund, ueber den Server zu routen
        // — kein Grund, die Planung scheitern zu lassen. Ohne dieses
        // `runCatching` waere ein vergessener Eintrag in `stageBrouterAssets`
        // ein harter Fehler statt eines stillen Rueckfalls.
        profileFile = runCatching { OfflineRoutingFiles.profileFile(context, profile) }.getOrNull(),
        installedSegmentFiles = AppServices.segmentInventory.list().map { it.fileName }.toSet(),
    )

/**
 * Berechnet eine Route auf dem besseren der beiden Wege.
 *
 * @param onSource meldet, welcher Weg gerade rechnet — und noch einmal, wenn
 *   nach einem lokalen Fehlschlag doch der Server drankommt. Wird **auf dem
 *   Arbeitsthread** aufgerufen; Compose-Zustaende duerfen von dort gesetzt
 *   werden (Snapshot-Zustand ist threadsicher), alles andere nicht.
 * @param onProgress `(fertige Etappen, Etappen gesamt)`, gleiche Bedingungen.
 *
 * ## Woher die Server-URL kommt
 * Bewusst kein eigener Parameter dafuer: [AppServices.routingServerSettings]
 * wird bei jedem Aufruf frisch gelesen, damit eine gerade im Mehr-Tab
 * geaenderte URL auch ohne Neustart der Planung sofort gilt — dieselbe
 * Direktheit wie bei [AppServices.httpClient] und [AppServices.segmentInventory]
 * hier direkt darunter.
 */
suspend fun planRouteOfflineFirst(
    context: Context,
    waypoints: List<Waypoint>,
    profile: RouteProfile,
    onSource: (RoutingSource) -> Unit = {},
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): RoutingResult {
    val appContext = context.applicationContext
    val setup = withContext(Dispatchers.IO) { offlineRoutingSetup(appContext, profile) }

    val dispatcher: CoroutineDispatcher =
        if (chooseRoutingSource(waypoints, setup).source == RoutingSource.OFFLINE) {
            Dispatchers.Default
        } else {
            Dispatchers.IO
        }

    return withContext(dispatcher) {
        routeOfflineFirst(
            waypoints = waypoints,
            serverProfileId = brouterProfile(profile),
            client = AppServices.httpClient,
            setup = setup,
            onSource = onSource,
            onProgress = onProgress,
            serverBaseUrl = AppServices.routingServerSettings.effectiveUrl(),
        )
    }
}

/**
 * Welche Kacheln fuer [waypoints] und [profile] lokal fehlen — unabhaengig
 * davon, ob am Ende ueberhaupt lokal gerechnet wird.
 *
 * ## Wozu, wenn [planRouteOfflineFirst] das doch laengst weiss
 * Nur im Erfolgsfall: Ein zurueckgegebenes [RoutingResult] traegt
 * [RoutingResult.missingSegmentFiles] bereits fertig. Scheitert die
 * Berechnung dagegen komplett (lokal UND Server, oder Server allein — siehe
 * `de.trailscape.core.routeOfflineFirst`), fliegt statt eines Ergebnisses
 * eine Ausnahme, und die darin verpackte Fehlermeldung sagt nichts ueber
 * fehlende Kacheln. Genau der Fall aus der Praxis: eine 600-km-Route ohne
 * lokale Kacheln, der Server lehnt sie als zu gross ab — die Nutzerin liest
 * „Der Routing-Server ist gerade überlastet" und erfaehrt nie, dass es einen
 * Ausweg gaebe. Diese Funktion baut denselben [OfflineRoutingSetup] noch
 * einmal (billig: nur Bestand auflisten, kein Netz) und fragt
 * [chooseRoutingSource] direkt — dieselbe reine Antwort wie innerhalb von
 * [routeOfflineFirst], nur diesmal fuer die Oberflaeche im Fehlerzweig.
 */
suspend fun missingSegmentsFor(
    context: Context,
    waypoints: List<Waypoint>,
    profile: RouteProfile,
): List<String> {
    val appContext = context.applicationContext
    val setup = withContext(Dispatchers.IO) { offlineRoutingSetup(appContext, profile) }
    return chooseRoutingSource(waypoints, setup).missingSegmentFiles
}

// ---------------------------------------------------------------------------
// Das Angebot: fehlende Kacheln mit Namen und Groesse
// ---------------------------------------------------------------------------

/**
 * Ein Download-Angebot fuer die Kacheln, die einer geplanten Route fehlen.
 *
 * [totalBytes] kommt aus `HEAD`-Anfragen, **nicht** aus einer Schaetzung: Eine
 * Kachel ist 120–240 MB, und diese Zahl gehoert vor den Download, nicht danach.
 * Ist der Server nicht erreichbar, gibt es kein Angebot statt einer geratenen
 * Zahl — wer offline ist, kann ohnehin nichts laden.
 */
data class SegmentOffer(
    val fileNames: List<String>,
    /** Die Kacheln in lesbarer Form, z. B. „Berlin, Dresden, Prag u. a.". */
    val title: String,
    val totalBytes: Long,
)

/**
 * Fragt fuer [fileNames] die Groessen beim Server ab und baut daraus das
 * Angebot. `null`, wenn nichts fehlt oder der Server nicht antwortet.
 *
 * Blockiert auf dem Netz und gehoert auf [Dispatchers.IO].
 */
suspend fun describeSegmentOffer(fileNames: List<String>): SegmentOffer? {
    val tiles = fileNames.distinct().mapNotNull { name -> parseSegmentTile(name)?.let { name to it } }
    if (tiles.isEmpty()) return null

    return withContext(Dispatchers.IO) {
        var total = 0L
        for ((name, _) in tiles) {
            val remote = runCatching { AppServices.segmentDownloader.remoteSegment(name) }.getOrNull()
                ?: return@withContext null
            if (remote.sizeBytes <= 0L) return@withContext null
            total += remote.sizeBytes
        }
        SegmentOffer(
            fileNames = tiles.map { it.first },
            title = segmentOfferTitle(tiles.map { it.second }),
            totalBytes = total,
        )
    }
}

/**
 * Die Kacheln eines Angebots als ein Satzteil.
 *
 * Eine Kachel nennt ihre Beispielorte („Berlin, Dresden, Prag u. a."), mehrere
 * werden mit „und" verbunden. Bewusst nicht mehr als [MAX_NAMED_TILES] beim
 * Namen genannt: Eine Route quer durch Europa braucht ein halbes Dutzend
 * Kacheln, und eine Aufzaehlung von zwanzig Staedten liest niemand.
 */
private fun segmentOfferTitle(tiles: List<SegmentTile>): String {
    val named = tiles.take(MAX_NAMED_TILES).map { it.title }
    val rest = tiles.size - named.size
    val joined = when (named.size) {
        1 -> named.first()
        else -> named.dropLast(1).joinToString(", ") + " und " + named.last()
    }
    return if (rest > 0) "$joined (+ $rest weitere)" else joined
}

/** Wie viele Kacheln in einem Angebot beim Namen genannt werden. */
private const val MAX_NAMED_TILES = 2
