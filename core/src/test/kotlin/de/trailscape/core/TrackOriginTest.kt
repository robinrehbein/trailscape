package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der Erkennung „Planung oder Aufzeichnung" fuer GPX
 * (`TrackOrigin.kt`) samt Grenzfaellen und dem Umdrehen einer Tour.
 */
class TrackOriginTest {

    private val t0 = 1_700_000_000_000L

    private fun pts(count: Int, time: (Int) -> Long?): List<TrackPoint> =
        (0 until count).map { TrackPoint(lat = 47.0 + it * 0.001, lon = 11.0 + it * 0.001, ele = 600.0 + it, time = time(it)) }

    private fun import(xml: String): Ride = rideFromGpx(xml, "Datei", id = "x", texts = CoreTextsDe)

    private fun trkpt(i: Int, time: String? = null) =
        "<trkpt lat=\"${47.0 + i * 0.001}\" lon=\"${11.0 + i * 0.001}\">" +
            (time?.let { "<time>$it</time>" } ?: "") + "</trkpt>"

    private fun gpx(body: String, metadata: String = "") =
        "<?xml version=\"1.0\"?><gpx version=\"1.1\" creator=\"t\" xmlns=\"http://www.topografix.com/GPX/1/1\">$metadata$body</gpx>"

    // --- Erkennung -------------------------------------------------------

    @Test
    fun `ohne Zeitstempel ist es eine Planung`() {
        assertEquals(TrackOrigin.PLANNED_ROUTE, classifyTrackOrigin(pts(10) { null }))
        assertEquals(TrackOrigin.PLANNED_ROUTE, classifyTrackOrigin(emptyList()))
    }

    @Test
    fun `durchgehende Zeitstempel sind eine Aufzeichnung`() {
        assertEquals(TrackOrigin.RECORDED_TRACK, classifyTrackOrigin(pts(10) { t0 + it * 5_000L }))
    }

    @Test
    fun `teilweise Zeitstempel - Mehrheit gestempelt ist Aufzeichnung, Minderheit Planung`() {
        // Luecken im GPS-Empfang: 6 von 10 gestempelt.
        val mostly = pts(10) { if (it % 5 == 4 || it == 3) null else t0 + it * 5_000L }
        assertEquals(TrackOrigin.RECORDED_TRACK, classifyTrackOrigin(mostly))
        // Nur Start und Ziel gestempelt, sonst eine Route.
        val few = pts(100) { if (it == 0 || it == 99) t0 + it * 5_000L else null }
        assertEquals(TrackOrigin.PLANNED_ROUTE, classifyTrackOrigin(few))
    }

    @Test
    fun `alle Zeitstempel gleich ist eine Planung`() {
        assertEquals(TrackOrigin.PLANNED_ROUTE, classifyTrackOrigin(pts(10) { t0 }))
        // Ein einzelner Stempel genuegt ebenso nicht.
        assertEquals(TrackOrigin.PLANNED_ROUTE, classifyTrackOrigin(pts(10) { if (it == 0) t0 else null }))
    }

    @Test
    fun `Platzhalter-Zeitstempel zaehlen nicht`() {
        // 1970-01-01 und kleine Werte sind kein echter Stempel.
        assertEquals(TrackOrigin.PLANNED_ROUTE, classifyTrackOrigin(pts(10) { it * 1_000L }))
        assertEquals(TrackOrigin.PLANNED_ROUTE, classifyTrackOrigin(pts(10) { 0L }))
    }

    // --- Dateien ----------------------------------------------------------

    @Test
    fun `Route mit rte wird Planung`() {
        val xml = gpx("<rte><name>Komoot</name>" + (0 until 5).joinToString("") {
            "<rtept lat=\"${47.0 + it * 0.001}\" lon=\"11.0\"/>"
        } + "</rte>")
        val ride = import(xml)
        assertTrue(ride.planned)
        assertEquals("Komoot", ride.name)
        assertEquals(5, ride.points.size)
    }

    @Test
    fun `trk ohne time wird Planung, mit time Fahrt`() {
        val noTime = import(gpx("<trk><trkseg>" + (0 until 4).joinToString("") { trkpt(it) } + "</trkseg></trk>"))
        assertTrue(noTime.planned)
        val timed = import(
            gpx(
                "<trk><trkseg>" + (0 until 4).joinToString("") { trkpt(it, "2024-05-01T08:0$it:00Z") } + "</trkseg></trk>",
            ),
        )
        assertFalse(timed.planned)
        assertEquals(java.time.Instant.parse("2024-05-01T08:00:00Z").toEpochMilli(), timed.createdAt)
        assertEquals(180, timed.stats.durationS)
    }

    @Test
    fun `Zeitstempel nur in den Metadaten bleiben eine Planung`() {
        val ride = import(
            gpx(
                "<trk><trkseg>" + (0 until 4).joinToString("") { trkpt(it) } + "</trkseg></trk>",
                metadata = "<metadata><name>Route</name><time>2024-05-01T08:00:00Z</time></metadata>",
            ),
        )
        assertTrue(ride.planned)
        assertNull(ride.stats.durationS)
    }

    @Test
    fun `teilweise und gleiche Stempel in der Datei - Planung ohne Dauer und Tempo`() {
        val same = import(
            gpx("<trk><trkseg>" + (0 until 6).joinToString("") { trkpt(it, "2024-05-01T08:00:00Z") } + "</trkseg></trk>"),
        )
        assertTrue(same.planned)
        assertNull(same.stats.durationS)
        assertNull(same.stats.avgSpeedKmh)

        val partial = import(
            gpx(
                "<trk><trkseg>" + (0 until 20).joinToString("") {
                    trkpt(it, if (it == 0 || it == 19) "2024-05-01T08:0${it % 10}:00Z" else null)
                } + "</trkseg></trk>",
            ),
        )
        assertTrue(partial.planned)
        assertNull(partial.stats.durationS)
    }

    @Test
    fun `mehrere Tracks und Segmente werden zu einer Strecke`() {
        val seg = { range: IntRange -> "<trkseg>" + range.joinToString("") { trkpt(it) } + "</trkseg>" }
        val ride = import(gpx("<trk>${seg(0..2)}${seg(3..5)}</trk><trk>${seg(6..8)}</trk>"))
        assertTrue(ride.planned)
        assertEquals(9, ride.points.size)

        // Ein gestempelter und ein ungestempelter Track: Mehrheit entscheidet.
        val timed = (0..5).joinToString("") { trkpt(it, "2024-05-01T08:0$it:00Z") }
        val plain = (6..8).joinToString("") { trkpt(it) }
        assertFalse(import(gpx("<trk><trkseg>$timed</trkseg></trk><trk><trkseg>$plain</trkseg></trk>")).planned)
    }

    @Test
    fun `Wegpunkte allein sind keine Strecke, neben einer Route werden sie ignoriert`() {
        assertFailsWith<FormatException> {
            import(gpx("<wpt lat=\"47\" lon=\"11\"><name>Hütte</name></wpt>"))
        }
        val ride = import(
            gpx(
                "<wpt lat=\"1\" lon=\"1\"><name>Hütte</name></wpt>" +
                    "<rte>" + (0 until 3).joinToString("") { "<rtept lat=\"${47.0 + it * 0.001}\" lon=\"11.0\"/>" } + "</rte>",
            ),
        )
        assertTrue(ride.planned)
        assertEquals(3, ride.points.size)
    }

    @Test
    fun `kaputte Dateien scheitern mit FormatException statt als Tour anzukommen`() {
        assertFailsWith<FormatException> { import("<gpx><trk>") }
        assertFailsWith<FormatException> { import("kein xml") }
        assertFailsWith<FormatException> { import(gpx("<trk><trkseg><trkpt lat=\"x\" lon=\"11\"/></trkseg></trk>")) }
        assertFailsWith<FormatException> { import(gpx("<trk><trkseg/></trk>")) }
    }

    @Test
    fun `sehr grosse Datei ohne Zeitstempel wird als Planung gelesen`() {
        val xml = gpx("<trk><trkseg>" + (0 until 60_000).joinToString("") { trkpt(it % 1000) } + "</trkseg></trk>")
        val ride = import(xml)
        assertTrue(ride.planned)
        assertEquals(60_000, ride.points.size)
    }

    // --- Umdrehen ----------------------------------------------------------

    private fun plannedRide(): Ride = import(gpx("<trk><trkseg>" + (0 until 5).joinToString("") { trkpt(it) } + "</trkseg></trk>"))

    @Test
    fun `asRidden macht aus der Planung eine Fahrt mit Kennzahlen aus den Punkten`() {
        val plan = plannedRide()
        val ridden = plan.asRidden(now = plan.updatedAt + 5_000)
        assertFalse(ridden.planned)
        assertEquals(plan.points, ridden.points)
        assertEquals(plan.stats.distanceKm, ridden.stats.distanceKm, 1e-9)
        assertEquals(plan.updatedAt + 5_000, ridden.updatedAt)
    }

    @Test
    fun `asPlanned nimmt Tour aus Statistik und behaelt Punkte`() {
        val xml = gpx("<trk><trkseg>" + (0 until 5).joinToString("") { trkpt(it, "2024-05-01T08:0$it:00Z") } + "</trkseg></trk>")
        val ride = import(xml)
        assertFalse(ride.planned)
        val plan = ride.asPlanned(now = 0L) // Uhr geht nach: updatedAt muss trotzdem wachsen
        assertTrue(plan.planned)
        assertEquals(ride.updatedAt + 1, plan.updatedAt)
        assertEquals(ride.points, plan.points)
        assertNull(plan.stats.durationS)
        assertTrue(riddenRides(listOf(plan)).isEmpty())
        // Und zurueck: die Zeitstempel sind noch da.
        val back = plan.asRidden(now = plan.updatedAt + 1)
        assertEquals(ride.createdAt, back.createdAt)
        assertEquals(ride.stats.durationS, back.stats.durationS)
    }

    @Test
    fun `Planungskennzeichen ueberlebt Sicherung und Zusammenfassung`() {
        val ride = import(gpx("<trk><trkseg>" + (0 until 5).joinToString("") { trkpt(it, "2024-05-01T08:0$it:00Z") } + "</trkseg></trk>"))
            .asPlanned(now = 99_999_999_999_999L)
        val json = Json.parseToJsonElement(Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), ride.toJson()))
        val restored = Ride.fromJson(json as kotlinx.serialization.json.JsonObject)
        assertTrue(restored.planned)
        assertEquals(ride.updatedAt, restored.updatedAt)
        assertTrue(ride.toSummary().planned)
        assertNotNull(RideSummary.fromJson(ride.toSummary().toJson()).takeIf { it.planned })
    }
}
