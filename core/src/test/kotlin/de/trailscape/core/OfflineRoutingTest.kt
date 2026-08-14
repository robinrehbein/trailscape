package de.trailscape.core

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests fuer den Wrapper um die eingebettete BRouter-Engine
 * (`OfflineRouting.kt`).
 *
 * ## Was hier bewusst NICHT getestet wird
 *
 * Eine vollstaendig durchgerechnete Route. Dafuer braucht die Engine eine
 * `*.rd5`-Kachel, und die kleinste sinnvolle (E10_N50, Deutschland-Mitte)
 * ist rund 119 MB gross. So etwas gehoert weder ins Repository noch in einen
 * Testlauf, der bei jedem Push durchlaeuft.
 *
 * Der Volltest laeuft deshalb **von Hand**, siehe [manualRouteWithRealSegment]
 * am Ende dieser Datei — dort steht auch der Befehl dazu.
 *
 * Alles andere ist ohne Kacheldatei pruefbar und darum Pflicht: die
 * Koordinaten-Kodierung, die Zuordnung Punkt → Kacheldateiname, die
 * Uebersetzung der Engine-Fehler ins Deutsche (inklusive der fehlenden
 * Kachel, die die Engine selbst meldet — dafuer genuegt ein *leeres*
 * Segmentverzeichnis), das Verhalten bei fehlendem Profil und die
 * Serialisierung der Aufrufe.
 */
class OfflineRoutingTest {

    // -----------------------------------------------------------------------
    // Koordinaten-Kodierung
    // -----------------------------------------------------------------------

    @Test
    fun `encodeLon und encodeLat rechnen in Mikrograd mit positivem Nullpunkt`() {
        // Nullmeridian / Aequator liegen in der Mitte des Wertebereichs.
        assertEquals(180_000_000, encodeLon(0.0))
        assertEquals(90_000_000, encodeLat(0.0))

        // Dresden, ein realer Punkt mit sechs Nachkommastellen.
        assertEquals(193_738_000, encodeLon(13.738000))
        assertEquals(141_050_000, encodeLat(51.050000))

        // Westlich von Greenwich bzw. suedlich des Aequators bleibt der Wert
        // positiv — genau das ist der Zweck der Verschiebung.
        assertEquals(171_500_000, encodeLon(-8.5))
        assertEquals(56_250_000, encodeLat(-33.75))

        // Die Raender des Wertebereichs.
        assertEquals(0, encodeLon(-180.0))
        assertEquals(360_000_000, encodeLon(180.0))
        assertEquals(0, encodeLat(-90.0))
        assertEquals(180_000_000, encodeLat(90.0))
    }

    @Test
    fun `encodeLon rundet statt abzuschneiden`() {
        // 13.7380004 liegt naeher an 193_738_000 als an 193_738_001.
        assertEquals(193_738_000, encodeLon(13.7380004))
        // 13.7380006 rundet auf.
        assertEquals(193_738_001, encodeLon(13.7380006))
    }

    // -----------------------------------------------------------------------
    // Kachelnamen
    // -----------------------------------------------------------------------

    @Test
    fun `segmentFileName benennt Kacheln nach ihrer Suedwestecke`() {
        // Dresden liegt in E10_N50 — die Kachel, gegen die auch der
        // Handtest unten laeuft.
        assertEquals("E10_N50.rd5", segmentFileName(51.05, 13.74))
        // Koeln: knapp westlich der 10-Grad-Grenze.
        assertEquals("E5_N50.rd5", segmentFileName(50.94, 6.96))
        // Genau auf der Kachelgrenze gehoert der Punkt zur oestlicheren
        // bzw. noerdlicheren Kachel.
        assertEquals("E10_N50.rd5", segmentFileName(50.0, 10.0))
    }

    @Test
    fun `segmentFileName arbeitet auch westlich und suedlich korrekt`() {
        // Dublin: westlich von Greenwich.
        assertEquals("W10_N50.rd5", segmentFileName(53.35, -6.26))
        // Kapstadt: suedliche Halbkugel.
        assertEquals("E15_S35.rd5", segmentFileName(-33.92, 18.42))
        // Rio de Janeiro: beides negativ.
        assertEquals("W45_S25.rd5", segmentFileName(-22.91, -43.17))
        // Nullpunkt.
        assertEquals("E0_N0.rd5", segmentFileName(0.0, 0.0))
    }

    @Test
    fun `requiredSegmentFiles deckt auch die Kacheln zwischen den Wegpunkten ab`() {
        // Koeln → Dresden: die Luftlinie kreuzt die Kachelgrenze bei 10 Grad
        // Ost, obwohl kein Wegpunkt in einer dritten Kachel liegt.
        val needed = requiredSegmentFiles(
            listOf(Waypoint(lat = 50.94, lon = 6.96), Waypoint(lat = 51.05, lon = 13.74)),
        )
        assertEquals(setOf("E5_N50.rd5", "E10_N50.rd5"), needed)
    }

    @Test
    fun `requiredSegmentFiles liefert bei einem kurzen Leg genau eine Kachel`() {
        val needed = requiredSegmentFiles(
            listOf(Waypoint(lat = 51.05, lon = 13.74), Waypoint(lat = 51.08, lon = 13.80)),
        )
        assertEquals(setOf("E10_N50.rd5"), needed)
    }

    // -----------------------------------------------------------------------
    // Fehleruebersetzung (ohne Engine)
    // -----------------------------------------------------------------------

    @Test
    fun `missingSegmentFileOf findet den Kachelnamen in beiden Schreibweisen`() {
        assertEquals("E5_N50.rd5", missingSegmentFileOf("datafile E5_N50.rd5 not found"))
        // Ueber HTTP stellt der Server derselben Meldung ein ERROR voran.
        assertEquals("E5_N50.rd5", missingSegmentFileOf("ERROR: datafile E5_N50.rd5 not found"))
        assertEquals("W10_S35.rd5", missingSegmentFileOf("datafile W10_S35.rd5 not found"))
        assertNull(missingSegmentFileOf("no track found"))
    }

    @Test
    fun `offlineRoutingErrorMessage fuehrt den Kachelnamen maschinenlesbar mit`() {
        val e = offlineRoutingErrorMessage("datafile E5_N50.rd5 not found")
        assertEquals("E5_N50.rd5", e.missingSegmentFile)
        val message = assertNotNull(e.message)
        assertTrue(message.contains("Offline-Kartendaten"), "Meldung ist Deutsch: $message")
        // Der Dateiname steht zusaetzlich im Text, damit ein Bugreport ohne
        // Debugger auswertbar bleibt.
        assertTrue(message.contains("E5_N50.rd5"), "Kachelname fehlt im Text: $message")
    }

    @Test
    fun `offlineRoutingErrorMessage uebersetzt die uebrigen bekannten Faelle`() {
        assertEquals(errorOfflineNoTrack, offlineRoutingErrorMessage("no track found").message)
        assertEquals(
            errorOfflineNoTrack,
            offlineRoutingErrorMessage("wp-position not mapped in existing datafile").message,
        )
        assertEquals(
            errorOfflineNoTrack,
            offlineRoutingErrorMessage("start island detected for section 0").message,
        )
        assertEquals(
            errorOfflineTimeout,
            offlineRoutingErrorMessage("routing timeout after 60 seconds").message,
        )
        // Kein Kachelname bei diesen Faellen — sie sind nicht durch einen
        // Download zu beheben.
        assertNull(offlineRoutingErrorMessage("no track found").missingSegmentFile)
    }

    @Test
    fun `offlineRoutingErrorMessage haengt unbekannte Meldungen in Klammern an`() {
        val message = assertNotNull(offlineRoutingErrorMessage("something\n  odd").message)
        assertTrue(message.startsWith("Route konnte nicht berechnet werden."), message)
        // Zeilenumbrueche und Mehrfach-Leerzeichen sind zu einem Leerzeichen
        // zusammengezogen, damit die Meldung einzeilig bleibt.
        assertTrue(message.contains("(Meldung der Routing-Engine: something odd)"), message)
    }

    @Test
    fun `offlineRoutingErrorMessage kuerzt sehr lange Engine-Texte`() {
        val message = assertNotNull(offlineRoutingErrorMessage("x".repeat(500)).message)
        assertTrue(message.contains("…"), "Text muss gekuerzt sein: $message")
        assertTrue(message.length < 300, "Meldung bleibt kurz, war ${message.length}")
    }

    // -----------------------------------------------------------------------
    // Engine-Aufruf ohne Kacheldatei
    // -----------------------------------------------------------------------

    @Test
    fun `routeOffline meldet die fehlende Kachel mit Dateinamen`() {
        val profile = writeProfileDir()
        // Verzeichnis existiert, ist aber leer: genau der Zustand vor dem
        // ersten Kartendownload.
        val segments = createTempDir("segments")

        val e = assertFailsWithOfflineError {
            routeOffline(
                waypoints = listOf(
                    Waypoint(lat = 51.0504, lon = 13.7373),
                    Waypoint(lat = 51.0800, lon = 13.8000),
                ),
                segmentDir = segments,
                profileFile = profile,
            )
        }
        assertEquals("E10_N50.rd5", e.missingSegmentFile)
        val message = assertNotNull(e.message)
        assertTrue(message.contains("E10_N50.rd5"), message)
    }

    @Test
    fun `routeOffline meldet ein fehlendes Kartenverzeichnis als Zustand vor dem ersten Download`() {
        val profile = writeProfileDir()
        val segments = File(createTempDir("nichts"), "gibtesnicht")

        val e = assertFailsWithOfflineError {
            routeOffline(
                waypoints = listOf(
                    Waypoint(lat = 51.0504, lon = 13.7373),
                    Waypoint(lat = 51.0800, lon = 13.8000),
                ),
                segmentDir = segments,
                profileFile = profile,
            )
        }
        assertEquals(errorOfflineNoSegments, e.message)
        // Trotz der allgemeinen Meldung die konkret noetige Kachel — sonst
        // haette eine spaetere Kachelverwaltung hier nichts anzubieten.
        assertEquals("E10_N50.rd5", e.missingSegmentFile)
    }

    @Test
    fun `offlineRoutingErrorMessage uebersetzt auch das fehlende Kartenverzeichnis`() {
        // Greift, wenn das Verzeichnis waehrend eines laufenden Aufrufs
        // verschwindet — dann kommt die Meldung aus der Engine.
        assertEquals(
            errorOfflineNoSegments,
            offlineRoutingErrorMessage("segment directory /data/segments does not exist").message,
        )
    }

    @Test
    fun `routeOffline meldet ein fehlendes Profil verstaendlich`() {
        val dir = createTempDir("ohne-profil")
        lookupsDatFromSubmodule().copyTo(File(dir, "lookups.dat"))

        val e = assertFailsWithOfflineError {
            routeOffline(
                waypoints = listOf(Waypoint(lat = 51.05, lon = 13.74), Waypoint(lat = 51.08, lon = 13.8)),
                segmentDir = dir,
                profileFile = File(dir, "gravel.brf"),
            )
        }
        assertEquals(errorOfflineProfileMissing, e.message)
        assertNull(e.missingSegmentFile)
    }

    @Test
    fun `routeOffline meldet eine fehlende lookups-Datei verstaendlich`() {
        // Profil da, Merkmalstabelle nicht: BRouters ProfileCache liest sie
        // fest als new File(profileDir, "lookups.dat") — ohne sie startet
        // nichts, und der rohe Fehler waere fuer Nutzer unlesbar.
        val dir = createTempDir("ohne-lookups")
        val profile = File(dir, "gravel.brf")
        profile.writeText(gravelProfileText())

        val e = assertFailsWithOfflineError {
            routeOffline(
                waypoints = listOf(Waypoint(lat = 51.05, lon = 13.74), Waypoint(lat = 51.08, lon = 13.8)),
                segmentDir = dir,
                profileFile = profile,
            )
        }
        assertEquals(errorOfflineLookupsMissing, e.message)
    }

    @Test
    fun `routeOffline verlangt mindestens zwei Wegpunkte`() {
        val profile = writeProfileDir()
        val e = assertFailsWithOfflineError {
            routeOffline(
                waypoints = listOf(Waypoint(lat = 51.05, lon = 13.74)),
                segmentDir = profile.parentFile,
                profileFile = profile,
            )
        }
        assertEquals("Mindestens zwei Wegpunkte nötig.", e.message)
    }

    // -----------------------------------------------------------------------
    // Serialisierung
    // -----------------------------------------------------------------------

    @Test
    fun `withRoutingEngineLock laesst nie zwei Aufrufe gleichzeitig hinein`() {
        // BRouters ProfileCache ist ein statischer Singleton mit belegten
        // Cache-Plaetzen. Ueberlappten sich zwei Laeufe, teilten sie sich
        // denselben Ausdrucks-Kontext — das Ergebnis haenge dann von der
        // Verschraenkung ab. Der Test weist nach, dass das nicht passiert.
        val threads = 8
        val roundsPerThread = 20
        val inside = AtomicInteger(0)
        val overlaps = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                start.await()
                repeat(roundsPerThread) {
                    withRoutingEngineLock {
                        if (inside.incrementAndGet() != 1) {
                            overlaps.incrementAndGet()
                        }
                        // Kurz halten, damit ein fehlendes Schloss sicher
                        // auffliegt statt zufaellig durchzuschluepfen.
                        Thread.sleep(1)
                        inside.decrementAndGet()
                    }
                }
                done.countDown()
            }.start()
        }

        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "Threads sind nicht fertig geworden")
        assertEquals(0, overlaps.get(), "Zwei Aufrufe waren gleichzeitig in der Engine")
    }

    @Test
    fun `parallele routeOffline-Aufrufe laufen nacheinander`() {
        // Zweiter Nachweis, diesmal ueber die echte Aufrufkette statt nur
        // ueber das Schloss: offlineRoutingRunCount wird absichtlich nicht
        // atomar hochgezaehlt. Liefen die Aufrufe parallel, gingen
        // Zaehlschritte verloren und die Summe stimmte nicht mehr.
        val profile = writeProfileDir()
        val segments = createTempDir("segments-parallel")
        val threads = 6
        val before = offlineRoutingRunCount
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                start.await()
                // Schlaegt erwartungsgemaess mit „Kachel fehlt" fehl; hier
                // zaehlt nur, dass der Lauf ueberhaupt stattgefunden hat.
                runCatching {
                    routeOffline(
                        waypoints = listOf(
                            Waypoint(lat = 51.0504, lon = 13.7373),
                            Waypoint(lat = 51.0800, lon = 13.8000),
                        ),
                        segmentDir = segments,
                        profileFile = profile,
                    )
                }
                done.countDown()
            }.start()
        }

        start.countDown()
        assertTrue(done.await(120, TimeUnit.SECONDS), "Threads sind nicht fertig geworden")
        assertEquals(before + threads, offlineRoutingRunCount)
    }

    // -----------------------------------------------------------------------
    // Handtest mit echter Kachel
    // -----------------------------------------------------------------------

    /**
     * Rechnet eine vollstaendige Route gegen eine echte `*.rd5`-Kachel.
     *
     * Uebersprungen, solange die Umgebungsvariable `TRAILSCAPE_SEGMENT_DIR`
     * nicht gesetzt ist — die Kachel ist rund 119 MB gross und hat im
     * Repository und im normalen Testlauf nichts zu suchen. So von Hand
     * fahren:
     *
     * ```
     * mkdir -p /tmp/brouter-segments
     * curl -L -o /tmp/brouter-segments/E10_N50.rd5 \
     *   https://brouter.de/brouter/segments4/E10_N50.rd5
     * TRAILSCAPE_SEGMENT_DIR=/tmp/brouter-segments \
     *   ./gradlew :core:test --tests '*OfflineRoutingTest*' -i
     * ```
     */
    @Test
    fun manualRouteWithRealSegment() {
        val dir = System.getenv("TRAILSCAPE_SEGMENT_DIR")
        if (dir.isNullOrBlank()) {
            println(
                "OfflineRoutingTest: Volltest uebersprungen — TRAILSCAPE_SEGMENT_DIR nicht " +
                    "gesetzt (siehe KDoc dieses Tests).",
            )
            return
        }

        val profile = writeProfileDir()
        val started = System.currentTimeMillis()
        val route = routeOffline(
            waypoints = listOf(
                Waypoint(lat = 51.0504, lon = 13.7373), // Dresden Altmarkt
                Waypoint(lat = 50.9787, lon = 13.9450), // Pirna
            ),
            segmentDir = File(dir),
            profileFile = profile,
        )
        val elapsedMs = System.currentTimeMillis() - started

        println(
            "OfflineRoutingTest: ${route.points.size} Punkte, " +
                "${"%.2f".format(route.distanceKm)} km, ${route.ascentM.toInt()} Hm, " +
                "$elapsedMs ms",
        )
        assertTrue(route.points.size > 100, "zu wenige Punkte: ${route.points.size}")
        assertTrue(route.distanceKm > 15, "zu kurz: ${route.distanceKm}")
        assertTrue(route.points.all { it.ele != null }, "Hoehen fehlen")
    }

    // -----------------------------------------------------------------------
    // Hilfen
    // -----------------------------------------------------------------------

    /**
     * Legt ein Verzeichnis mit Profil **und** `lookups.dat` an — der
     * Zustand, den die Engine erwartet — und liefert die Profildatei.
     */
    private fun writeProfileDir(): File {
        val dir = createTempDir("profil")
        val profile = File(dir, "gravel.brf")
        profile.writeText(gravelProfileText())
        lookupsDatFromSubmodule().copyTo(File(dir, "lookups.dat"))
        return profile
    }

    private fun createTempDir(prefix: String): File {
        val dir = File.createTempFile("trailscape-$prefix", "")
        check(dir.delete() && dir.mkdirs())
        dir.deleteOnExit()
        return dir
    }

    /**
     * Die Merkmalstabelle aus dem BRouter-Submodul.
     *
     * Bewusst von dort und nicht als Kopie unter `src/test/resources`: Die
     * Datei gehoert zur Engine-Version und muesste sonst bei jedem
     * Submodul-Update von Hand nachgezogen werden — genau die doppelte
     * Wahrheit, die vermieden werden soll.
     */
    private fun lookupsDatFromSubmodule(): File {
        // Gradle startet Tests mit dem Modulverzeichnis als Arbeitsverzeichnis;
        // der Aufstieg macht den Fund trotzdem unabhaengig davon.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "third_party/brouter/misc/profiles2/lookups.dat")
            if (candidate.isFile) {
                return candidate
            }
            dir = dir.parentFile
        }
        fail(
            "lookups.dat nicht gefunden. Das BRouter-Submodul fehlt vermutlich; " +
                "einmalig nachholen mit: git submodule update --init third_party/brouter",
        )
    }

    private fun assertFailsWithOfflineError(block: () -> Unit): OfflineRoutingException =
        try {
            block()
            fail("Es wurde eine OfflineRoutingException erwartet")
        } catch (e: OfflineRoutingException) {
            e
        }
}
