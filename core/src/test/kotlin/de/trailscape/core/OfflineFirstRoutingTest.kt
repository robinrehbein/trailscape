package de.trailscape.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Entscheidung „offline zuerst, Server als Rueckfall"
 * (`OfflineFirstRouting.kt`).
 *
 * Ohne eine einzige Kacheldatei pruefbar — und genau das ist der Grund, warum
 * die Entscheidung in `:core` liegt: Der Bestand kommt als Menge von
 * Dateinamen herein, der Serverweg als gecannter [HttpClient]. Der einzige
 * Fall, der hier nicht bis zum Ende laeuft, ist die tatsaechliche lokale
 * Berechnung — dafuer braeuchte es die 119 MB, die es im Repository nicht gibt
 * (siehe `OfflineRoutingTest`). Ihr **Scheitern** ist dagegen pruefbar, und
 * das ist der interessantere Teil: Es ist der Weg zum Server.
 */
class OfflineFirstRoutingTest {

    private val dresden = Waypoint(lat = 51.0504, lon = 13.7373)
    private val pirna = Waypoint(lat = 50.9787, lon = 13.9450)

    /** Die Kachel, in der beide Punkte liegen. */
    private val tile = "E10_N50.rd5"

    // -----------------------------------------------------------------------
    // Die Entscheidung
    // -----------------------------------------------------------------------

    @Test
    fun `ohne Einrichtung geht es zum Server`() {
        val choice = chooseRoutingSource(listOf(dresden, pirna), null)

        assertEquals(RoutingSource.SERVER, choice.source)
        assertEquals(ServerFallbackReason.NOT_SET_UP, choice.fallbackReason)
        assertTrue(choice.missingSegmentFiles.isEmpty())
    }

    @Test
    fun `vollstaendiger Bestand und Profil ergeben die lokale Berechnung`() {
        val choice = chooseRoutingSource(
            listOf(dresden, pirna),
            setup(installed = setOf(tile)),
        )

        assertEquals(RoutingSource.OFFLINE, choice.source)
        assertNull(choice.fallbackReason)
        assertTrue(choice.missingSegmentFiles.isEmpty())
    }

    @Test
    fun `eine fehlende Kachel schickt zum Server und nennt sie beim Namen`() {
        val choice = chooseRoutingSource(
            listOf(dresden, pirna),
            setup(installed = emptySet()),
        )

        assertEquals(RoutingSource.SERVER, choice.source)
        assertEquals(ServerFallbackReason.MISSING_SEGMENTS, choice.fallbackReason)
        assertEquals(listOf(tile), choice.missingSegmentFiles)
    }

    @Test
    fun `eine lange Strecke verlangt alle ueberflogenen Kacheln`() {
        // Hamburg → Muenchen quert mehrere 5°-Kacheln; liegt nur eine davon
        // lokal, reicht das nicht.
        val choice = chooseRoutingSource(
            listOf(Waypoint(lat = 53.551, lon = 9.994), Waypoint(lat = 48.137, lon = 11.576)),
            setup(installed = setOf("E5_N50.rd5")),
        )

        assertEquals(RoutingSource.SERVER, choice.source)
        assertEquals(ServerFallbackReason.MISSING_SEGMENTS, choice.fallbackReason)
        assertTrue(
            choice.missingSegmentFiles.isNotEmpty(),
            "es muessen Kacheln fehlen: ${choice.missingSegmentFiles}",
        )
        assertTrue(
            "E5_N50.rd5" !in choice.missingSegmentFiles,
            "die vorhandene Kachel darf nicht im Angebot stehen",
        )
    }

    @Test
    fun `ohne lokales Profil geht es zum Server, das Angebot bleibt aber stehen`() {
        // „Radwege bevorzugt" hat offline kein Profil. Die fehlenden Kacheln
        // werden trotzdem gemeldet: Sie fehlen fuer jeden anderen Fahrmodus
        // genauso, und der Nutzer soll sie laden koennen.
        val choice = chooseRoutingSource(
            listOf(dresden, pirna),
            setup(profile = null, installed = emptySet()),
        )

        assertEquals(RoutingSource.SERVER, choice.source)
        assertEquals(ServerFallbackReason.NO_LOCAL_PROFILE, choice.fallbackReason)
        assertEquals(listOf(tile), choice.missingSegmentFiles)
    }

    @Test
    fun `ohne lokales Profil zaehlt der vollstaendige Bestand nicht als Angebot`() {
        val choice = chooseRoutingSource(
            listOf(dresden, pirna),
            setup(profile = null, installed = setOf(tile)),
        )

        assertEquals(RoutingSource.SERVER, choice.source)
        assertEquals(ServerFallbackReason.NO_LOCAL_PROFILE, choice.fallbackReason)
        assertTrue(choice.missingSegmentFiles.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Die Profil-Zuordnung
    // -----------------------------------------------------------------------

    @Test
    fun `jeder Fahrmodus hat eine offline-Antwort — auch das ehrliche Nein`() {
        assertEquals("trekking.brf", offlineBrouterProfile(RouteProfile.GRAVEL))
        assertEquals("gravel.brf", offlineBrouterProfile(RouteProfile.SCHOTTER))
        assertEquals("fastbike.brf", offlineBrouterProfile(RouteProfile.ASPHALT))
        assertEquals("shortest.brf", offlineBrouterProfile(RouteProfile.KUERZESTER))
        // Es gibt keine `safety.brf` im Submodul — dieser Modus faellt bewusst
        // auf den Server zurueck, statt mit einem nachgebauten Profil anders zu
        // routen als online.
        assertNull(offlineBrouterProfile(RouteProfile.RADWEGE))
    }

    @Test
    fun `die offline-Profile liegen wirklich im Submodul`() {
        // Der Schutz gegen einen Tippfehler in der Zuordnung und gegen ein
        // Submodul-Update, das eine Datei umbenennt: Was hier steht, muss `:app`
        // beim Bauen auch in die Assets uebernehmen koennen.
        val dir = profiles2Dir()
        for (profile in RouteProfile.entries) {
            val name = offlineBrouterProfile(profile) ?: continue
            assertTrue(File(dir, name).isFile, "$name fehlt in misc/profiles2/")
        }
    }

    @Test
    fun `das eingebettete Gravel-Profil ist weiterhin byte-identisch zum Submodul`() {
        // Stufe 1 hat bewusst darauf verzichtet, gravel.brf ein zweites Mal
        // abzulegen; diese Pruefung haelt die Begruendung am Leben. Verglichen
        // wird der Rohtext [GRAVEL_BRF] — `gravelProfileText()` ist bewusst
        // eine Abwandlung davon (`prefer_unpaved_paths true`).
        val fromSubmodule = File(profiles2Dir(), "gravel.brf").readText()
        assertEquals(fromSubmodule, GRAVEL_BRF)
    }

    // -----------------------------------------------------------------------
    // Ausfuehrung und Rueckfall
    // -----------------------------------------------------------------------

    @Test
    fun `ohne Einrichtung rechnet der Server, und das steht auch so im Ergebnis`() {
        val client = CountingClient(geoJsonResponse())
        val sources = mutableListOf<RoutingSource>()

        val result = routeOfflineFirst(
            waypoints = listOf(dresden, pirna),
            serverProfileId = "trekking",
            client = client,
            setup = null,
            sleeper = {},
            onSource = { sources.add(it) },
        )

        assertEquals(RoutingSource.SERVER, result.source)
        assertEquals(ServerFallbackReason.NOT_SET_UP, result.fallbackReason)
        assertEquals(listOf(RoutingSource.SERVER), sources)
        assertEquals(1, client.calls)
        assertTrue(result.route.points.size >= 2)
    }

    @Test
    fun `bei fehlender Kachel rechnet der Server und das Angebot nennt die Kachel`() {
        val client = CountingClient(geoJsonResponse())

        val result = routeOfflineFirst(
            waypoints = listOf(dresden, pirna),
            serverProfileId = "trekking",
            client = client,
            setup = setup(installed = emptySet()),
            sleeper = {},
        )

        assertEquals(RoutingSource.SERVER, result.source)
        assertEquals(ServerFallbackReason.MISSING_SEGMENTS, result.fallbackReason)
        assertEquals(listOf(tile), result.missingSegmentFiles)
        // Es wurde gar nicht erst lokal gerechnet — der Bestand reichte nicht.
        assertEquals(1, client.calls)
    }

    @Test
    fun `ein lokaler Fehlschlag landet still beim Server`() {
        // Der Bestand behauptet, die Kachel sei da; das Verzeichnis ist aber
        // leer. Die Engine scheitert also **waehrend** des Laufs — genau der
        // Fall, fuer den es den Rueckfall gibt.
        val client = CountingClient(geoJsonResponse())
        val sources = mutableListOf<RoutingSource>()

        val result = routeOfflineFirst(
            waypoints = listOf(dresden, pirna),
            serverProfileId = "trekking",
            client = client,
            setup = setup(
                profile = writeProfileDir(),
                installed = setOf(tile),
                segmentDir = createTempDir("leer"),
            ),
            sleeper = {},
            onSource = { sources.add(it) },
        )

        assertEquals(RoutingSource.SERVER, result.source)
        assertEquals(1, client.calls)
        // Erst offline versucht, dann Server — beides gemeldet, damit die
        // Oberflaeche nicht faelschlich „berechne lokal" stehen laesst.
        assertEquals(listOf(RoutingSource.OFFLINE, RoutingSource.SERVER), sources)
        // Die Engine nennt die fehlende Kachel; daraus wird das Angebot.
        assertEquals(listOf(tile), result.missingSegmentFiles)
        assertEquals(ServerFallbackReason.MISSING_SEGMENTS, result.fallbackReason)
    }

    @Test
    fun `ein Fehlschlag ohne Kachelbezug bleibt ohne Angebot`() {
        // Kein Profil auf der Platte, obwohl der Setup eines nennt: Das ist ein
        // lokaler Defekt, kein fehlender Download — der Nutzer soll deshalb
        // kein Kachel-Angebot bekommen, sondern schlicht seine Route.
        val client = CountingClient(geoJsonResponse())

        val result = routeOfflineFirst(
            waypoints = listOf(dresden, pirna),
            serverProfileId = "trekking",
            client = client,
            setup = setup(
                profile = File(createTempDir("ohne-profil"), "trekking.brf"),
                installed = setOf(tile),
            ),
            sleeper = {},
        )

        assertEquals(RoutingSource.SERVER, result.source)
        assertEquals(ServerFallbackReason.OFFLINE_FAILED, result.fallbackReason)
        assertTrue(result.missingSegmentFiles.isEmpty())
        assertEquals(1, client.calls)
    }

    @Test
    fun `weniger als zwei Wegpunkte werden abgelehnt, bevor irgendetwas rechnet`() {
        val client = CountingClient(geoJsonResponse())
        val error = runCatching {
            routeOfflineFirst(
                waypoints = listOf(dresden),
                serverProfileId = "trekking",
                client = client,
                setup = null,
                sleeper = {},
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("Mindestens zwei Wegpunkte nötig.", error.message)
        assertEquals(0, client.calls)
    }

    // -----------------------------------------------------------------------
    // Etappen-Aufteilung
    // -----------------------------------------------------------------------

    @Test
    fun `die lokale Berechnung teilt genauso auf wie der Serverweg`() {
        // Der eigentliche Beleg fuer „gleiche Eingabe, gleiches Ergebnis":
        // routeOfflineLegs benutzt planRouteLegs unveraendert. Der Lauf
        // scheitert mangels Kachel schon in der ersten Etappe — der
        // Fortschrittsruf verraet aber, mit wie vielen Etappen gerechnet wurde.
        val hamburg = Waypoint(lat = 53.551, lon = 9.994)
        val muenchen = Waypoint(lat = 48.137, lon = 11.576)
        val expected = planRouteLegs(listOf(hamburg, muenchen)).size
        assertTrue(expected > 1, "die Teststrecke sollte aufgeteilt werden")

        var reportedTotal = 0
        runCatching {
            routeOfflineLegs(
                waypoints = listOf(hamburg, muenchen),
                setup = setup(installed = emptySet()),
                onProgress = { _, total -> reportedTotal = total },
            )
        }

        assertEquals(expected, reportedTotal)
    }

    @Test
    fun `ohne Profil meldet die Etappenrechnung einen verstaendlichen Fehler`() {
        val error = runCatching {
            routeOfflineLegs(listOf(dresden, pirna), setup(profile = null))
        }.exceptionOrNull()

        assertTrue(error is OfflineRoutingException)
        assertEquals(errorOfflineProfileMissing, error.message)
    }

    // -----------------------------------------------------------------------
    // Hilfen
    // -----------------------------------------------------------------------

    private fun setup(
        profile: File? = File(createTempDir("profil"), "trekking.brf"),
        installed: Set<String> = emptySet(),
        segmentDir: File = createTempDir("segmente"),
    ) = OfflineRoutingSetup(
        segmentDir = segmentDir,
        profileFile = profile,
        installedSegmentFiles = installed,
    )

    private fun createTempDir(prefix: String): File {
        val dir = File.createTempFile("trailscape-$prefix", "")
        check(dir.delete() && dir.mkdirs())
        dir.deleteOnExit()
        return dir
    }

    /**
     * Ein Verzeichnis mit einem **echten** Profil und `lookups.dat` daneben —
     * der Zustand, in dem die Engine startet und erst an der fehlenden Kachel
     * scheitert. Ohne das waere jeder Lauf schon am Profil gescheitert und der
     * interessante Fall nie geprueft.
     */
    private fun writeProfileDir(): File {
        val dir = createTempDir("echtes-profil")
        val profile = File(dir, "gravel.brf")
        profile.writeText(gravelProfileText())
        File(profiles2Dir(), "lookups.dat").copyTo(File(dir, "lookups.dat"))
        return profile
    }

    /** Das Verzeichnis der Upstream-Profile im Submodul. */
    private fun profiles2Dir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "third_party/brouter/misc/profiles2")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error(
            "misc/profiles2 nicht gefunden. Das BRouter-Submodul fehlt vermutlich; " +
                "einmalig nachholen mit: git submodule update --init third_party/brouter",
        )
    }

    /** Ein HttpClient, der immer dieselbe Antwort gibt und die Aufrufe zaehlt. */
    private class CountingClient(private val body: String) : HttpClient {
        var calls: Int = 0
            private set

        override fun execute(request: HttpRequest): HttpResponse {
            calls++
            return HttpResponse(statusCode = 200, body = body)
        }
    }

    private fun geoJsonResponse(): String = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
        "properties":{"track-length":"12345","filtered ascend":"210"},
        "geometry":{"type":"LineString","coordinates":[
        [13.7373,51.0504,113],[13.8,51.02,120],[13.945,50.9787,140]]}}]}
    """.trimIndent()
}
