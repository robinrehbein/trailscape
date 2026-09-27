package de.trailscape.app.ui.rides

import de.trailscape.app.ui.map.ElevationSample
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import de.trailscape.core.i18n.AppLanguage
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der reinen Rechnung hinter dem Tour-Bild (`ShareCard.kt`): Einpassen
 * der Spur, Profil, Kennzahlen, Datumszeile, Dateiname und Layout.
 */
class ShareCardTest {

    // Die Tests unten pruefen den deutschen Wortlaut wie vor der Uebersetzung.
    // Diese Member ueberdecken die echten Top-Level-Funktionen (Member vor
    // Top-Level) und loesen die Ressourcen ueber [RidesXmlStrings] auf.

    private fun shareCardContent(
        ride: Ride,
        load: Double?,
        endRadiusM: Double? = null,
        toLocal: (Long) -> LocalDateTime,
        strings: RidesXmlStrings = RidesXmlStrings.DE,
    ): ShareCardContent = de.trailscape.app.ui.rides.shareCardContent(
        ride,
        load,
        strings.language,
        strings::resolve,
        endRadiusM = endRadiusM,
        toLocal = toLocal,
    )

    private fun shareCardStats(
        stats: RideStats,
        load: Double?,
        planned: Boolean,
        hasElevation: Boolean,
        strings: RidesXmlStrings = RidesXmlStrings.DE,
    ): List<ShareStat> = de.trailscape.app.ui.rides.shareCardStats(stats, load, planned, hasElevation, strings.language)
        .map { ShareStat(it.value, strings.resolve(it.label)) }

    private fun shareCardDateLine(
        at: LocalDateTime,
        planned: Boolean,
        strings: RidesXmlStrings = RidesXmlStrings.DE,
    ): String = strings.resolve(de.trailscape.app.ui.rides.shareCardDateLine(at, planned, strings.language))

    private val eps = 0.01f

    /** Feste UTC-Umrechnung, damit der Test nicht von der Zeitzone abhaengt. */
    private val utc: (Long) -> LocalDateTime = { LocalDateTime.ofEpochSecond(it / 1000, 0, ZoneOffset.UTC) }

    private fun stats(
        distanceKm: Double = 42.34,
        ascentM: Double = 612.6,
        durationS: Int? = 5040,
        movingTimeS: Int? = null,
        avgHrBpm: Int? = null,
    ) = RideStats(
        distanceKm = distanceKm,
        ascentM = ascentM,
        descentM = ascentM,
        durationS = durationS,
        movingTimeS = movingTimeS,
        avgHrBpm = avgHrBpm,
    )

    private fun ride(
        name: String = "Feierabendrunde",
        points: List<TrackPoint> = emptyList(),
        stats: RideStats = stats(),
        planned: Boolean = false,
    ) = Ride(
        id = "r1",
        name = name,
        createdAt = LocalDateTime.of(2025, 9, 23, 18, 12).toEpochSecond(ZoneOffset.UTC) * 1000,
        stats = stats,
        points = points,
        planned = planned,
    )

    private fun xs(coords: FloatArray) = coords.filterIndexed { i, _ -> i % 2 == 0 }
    private fun ys(coords: FloatArray) = coords.filterIndexed { i, _ -> i % 2 == 1 }

    private val squareTrack = shareCardLayout(ShareCardFormat.SQUARE, hasTrack = true, hasProfile = true).track!!

    // ------------------------------------------------------------ fitPolyline

    @Test
    fun `Nord-Sued-Strecke steht senkrecht in der Mitte statt breitgezerrt`() {
        val points = (0..20).map { TrackPoint(lat = 53.0 + it * 0.001, lon = 10.0) }
        val unit = thumbnailPolyline(points, SHARE_TRACK_MAX_POINTS)

        val fitted = fitPolyline(unit, squareTrack)

        val centerX = (squareTrack.left + squareTrack.right) / 2
        xs(fitted).forEach { assertEquals(centerX, it, eps) }
        assertEquals(squareTrack.top, ys(fitted).min(), eps)
        assertEquals(squareTrack.bottom, ys(fitted).max(), eps)
    }

    @Test
    fun `Runde liegt vollstaendig und mittig im Rechteck und fuellt eine Achse`() {
        val points = (0 until 40).map {
            val a = 2 * Math.PI * it / 40
            TrackPoint(lat = 53.5 + 0.01 * sin(a), lon = 10.0 + 0.02 * cos(a))
        }
        val rect = CardRect(96f, 440f, 984f, 1300f)

        val fitted = fitPolyline(thumbnailPolyline(points, SHARE_TRACK_MAX_POINTS), rect)

        xs(fitted).forEach { assertTrue(it >= rect.left - eps && it <= rect.right + eps) }
        ys(fitted).forEach { assertTrue(it >= rect.top - eps && it <= rect.bottom + eps) }
        val minX = xs(fitted).min()
        val maxX = xs(fitted).max()
        val minY = ys(fitted).min()
        val maxY = ys(fitted).max()
        assertEquals((rect.left + rect.right) / 2, (minX + maxX) / 2, 0.5f)
        assertEquals((rect.top + rect.bottom) / 2, (minY + maxY) / 2, 0.5f)
        val fillsX = abs((maxX - minX) - rect.width) < 0.5f
        val fillsY = abs((maxY - minY) - rect.height) < 0.5f
        assertTrue(fillsX || fillsY)
    }

    @Test
    fun `identische Punkte oder keine Punkte ergeben keine Spur`() {
        val same = List(5) { TrackPoint(lat = 53.0, lon = 10.0) }
        val unit = thumbnailPolyline(same, SHARE_TRACK_MAX_POINTS)

        assertEquals(0, fitPolyline(unit, squareTrack).size)
        assertEquals(0, fitPolyline(FloatArray(0), squareTrack).size)
        val content = shareCardContent(ride(points = same), load = null, toLocal = utc)
        assertEquals(false, content.hasTrack)
    }

    @Test
    fun `Aufzeichnung auf der Stelle mit Hoehen bekommt weder Spur noch Profil`() {
        val same = List(5) { TrackPoint(lat = 53.0, lon = 10.0, ele = 40.0 + it) }
        val content = shareCardContent(ride(points = same), load = null, toLocal = utc)

        assertTrue(content.profile.size >= 2)
        assertEquals(false, content.hasTrack)
        assertEquals(false, content.hasProfile)
        for (format in ShareCardFormat.entries) {
            assertNull(shareCardLayout(format, content.hasTrack, content.hasProfile).profile)
        }
    }

    // ------------------------------------------------------- shareCardContent

    @Test
    fun `erster und letzter Punkt bleiben auch bei 10000 Punkten erhalten`() {
        // Diagonal nach Nordosten: Start unten links, Ziel oben rechts.
        val points = (0 until 10_000).map { TrackPoint(lat = 53.0 + it * 1e-5, lon = 10.0 + it * 1.6e-5) }

        val content = shareCardContent(ride(points = points), load = null, toLocal = utc)
        val rect = CardRect(0f, 0f, 1000f, 1000f)
        val fitted = fitPolyline(content.trackUnit, rect)

        assertTrue(content.trackUnit.size <= SHARE_TRACK_MAX_POINTS * 2)
        assertTrue(content.hasTrack)
        val n = fitted.size
        assertEquals(xs(fitted).min(), fitted[0], eps)
        assertEquals(ys(fitted).max(), fitted[1], eps)
        assertEquals(xs(fitted).max(), fitted[n - 2], eps)
        assertEquals(ys(fitted).min(), fitted[n - 1], eps)
    }

    @Test
    fun `Tour ohne Punkte bekommt ein Bild nur mit Zahlen und Titel-Ersatz`() {
        val content = shareCardContent(ride(name = "   ", points = emptyList()), load = null, toLocal = utc)

        assertEquals("Tour", content.title)
        assertEquals(0, content.trackUnit.size)
        assertTrue(content.profile.isEmpty())
        assertEquals(false, content.hasTrack)
        assertEquals(false, content.hasProfile)
        assertEquals(listOf("km", "Std.", "Hm"), content.stats.map { it.label })
        assertEquals("Dienstag, 23. September 2025", content.dateLine)
    }

    // ------------------------------------------------ Start und Ziel ausblenden

    /** Gerade Strecke nach Nordosten mit Hoehen, ein Punkt je ~[stepM] Meter. */
    private fun straight(lengthM: Double, stepM: Double = 50.0): List<TrackPoint> {
        val n = (lengthM / stepM).toInt()
        val degPerM = 1.0 / 111_195.0
        return (0..n).map {
            TrackPoint(lat = 53.0 + it * stepM * degPerM, lon = 10.0 + it * stepM * degPerM, ele = 40.0 + it % 7)
        }
    }

    @Test
    fun `ohne Radius bleibt alles wie bisher`() {
        val points = straight(5000.0)
        val content = shareCardContent(ride(points = points), load = null, toLocal = utc)

        assertEquals(ShareTrackNote.FULL, content.trackNote)
        assertEquals(false, content.endsHidden)
        assertTrue(thumbnailPolyline(points, SHARE_TRACK_MAX_POINTS).contentEquals(content.trackUnit))
    }

    @Test
    fun `mit Radius wird die Linie gekuerzt, Zahlen und Profil bleiben die der ganzen Tour`() {
        val ride = ride(points = straight(5000.0))
        val full = shareCardContent(ride, load = 55.0, toLocal = utc)
        val hidden = shareCardContent(ride, load = 55.0, endRadiusM = 300.0, toLocal = utc)

        assertTrue(hidden.hasTrack)
        assertEquals(ShareTrackNote.ENDS_HIDDEN, hidden.trackNote)
        assertTrue(hidden.endsHidden)
        assertEquals(full.stats, hidden.stats)
        assertEquals(full.hasProfile, hidden.hasProfile)
        assertTrue(hidden.hasProfile)
        assertEquals(full.profile, hidden.profile)
    }

    @Test
    fun `zu kurze Tour zeigt mit Radius nur die Kennzahlen`() {
        val content = shareCardContent(ride(points = straight(500.0)), load = null, endRadiusM = 300.0, toLocal = utc)

        assertEquals(false, content.hasTrack)
        assertEquals(ShareTrackNote.TOO_SHORT, content.trackNote)
        assertEquals(false, content.endsHidden)
        for (format in ShareCardFormat.entries) {
            assertNull(shareCardLayout(format, content.hasTrack, content.hasProfile).track)
        }
    }

    @Test
    fun `ohne Punkte oder auf der Stelle ist mit Radius nichts zu kuerzen`() {
        val none = shareCardContent(ride(points = emptyList()), load = null, endRadiusM = 300.0, toLocal = utc)
        assertEquals(ShareTrackNote.NONE, none.trackNote)

        val same = List(5) { TrackPoint(lat = 53.0, lon = 10.0) }
        val still = shareCardContent(ride(points = same), load = null, endRadiusM = 300.0, toLocal = utc)
        assertEquals(ShareTrackNote.NONE, still.trackNote)

        val bare = shareCardContent(ride(points = emptyList()), load = null, toLocal = utc)
        assertEquals(ShareTrackNote.NONE, bare.trackNote)
    }

    // --------------------------------------------------------- shareCardStats

    @Test
    fun `Kennzahlen deutsch formatiert`() {
        val result = shareCardStats(stats(), load = null, planned = false, hasElevation = true)

        assertEquals(
            listOf(ShareStat("42,3", "km"), ShareStat("1:24", "Std."), ShareStat("613", "Hm")),
            result,
        )
    }

    @Test
    fun `Fahrzeit springt ein, ohne jede Dauer entfaellt die Zeit`() {
        val moving = shareCardStats(
            stats(durationS = null, movingTimeS = 3600),
            load = null,
            planned = false,
            hasElevation = true,
        )
        assertEquals(ShareStat("1:00", "Std."), moving[1])

        val none = shareCardStats(
            stats(durationS = null, movingTimeS = null),
            load = null,
            planned = false,
            hasElevation = true,
        )
        assertEquals(listOf("km", "Hm"), none.map { it.label })
    }

    @Test
    fun `kein 0 Hm ohne Hoehendaten, aber ehrliche 0 mit`() {
        val without = shareCardStats(stats(ascentM = 0.0), load = null, planned = false, hasElevation = false)
        assertTrue(without.none { it.label == "Hm" })

        val with = shareCardStats(stats(ascentM = 0.0), load = null, planned = false, hasElevation = true)
        assertTrue(ShareStat("0", "Hm") in with)
    }

    @Test
    fun `vierte Zahl Puls vor Trainingslast, nie bei Planung, nie mehr als vier`() {
        val hr = shareCardStats(stats(avgHrBpm = 142), load = 85.0, planned = false, hasElevation = true)
        assertEquals(ShareStat("142", "Ø Puls"), hr.last())
        assertEquals(4, hr.size)

        val load = shareCardStats(stats(), load = 84.6, planned = false, hasElevation = true)
        assertEquals(ShareStat("85", "Trainingslast"), load.last())

        listOf(
            shareCardStats(stats(), load = 84.6, planned = true, hasElevation = true),
            shareCardStats(stats(), load = null, planned = false, hasElevation = true),
            shareCardStats(stats(), load = 0.0, planned = false, hasElevation = true),
        ).forEach { assertEquals(3, it.size) }

        listOf(true, false).forEach { planned ->
            val all = shareCardStats(stats(avgHrBpm = 150), load = 120.0, planned = planned, hasElevation = true)
            assertTrue(all.size <= 4)
        }
    }

    // ------------------------------------------------------ profilePolyline

    private fun samples(vararg eles: Double) = eles.mapIndexed { i, e ->
        ElevationSample(distanceKm = i * 1.0, eleM = e, point = TrackPoint(lat = 53.0, lon = 10.0, ele = e))
    }

    @Test
    fun `Profil laeuft von links nach rechts und nutzt bei Bergen die volle Hoehe`() {
        val rect = CardRect(96f, 1340f, 984f, 1500f)
        val line = profilePolyline(samples(100.0, 250.0, 400.0, 180.0), rect)

        val x = xs(line)
        assertEquals(rect.left, x.first(), eps)
        assertEquals(rect.right, x.last(), eps)
        x.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
        assertEquals(rect.top, ys(line).min(), eps)
        assertEquals(rect.bottom, ys(line).max(), eps)
    }

    @Test
    fun `flache Tour bleibt im mittleren Band, zu wenig Stuetzstellen ergeben nichts`() {
        val rect = CardRect(0f, 0f, 1000f, 600f)
        val line = profilePolyline(samples(100.0, 101.0, 102.0), rect)

        ys(line).forEach { assertTrue(it > 250f && it < 350f, "y=$it") }
        assertEquals(0, profilePolyline(samples(100.0), rect).size)
        assertEquals(0, profilePolyline(emptyList(), rect).size)
    }

    // -------------------------------------------------------- shareCardLayout

    @Test
    fun `Layouts liegen im Bild und in der richtigen Reihenfolge`() {
        for (format in ShareCardFormat.entries) {
            for (hasTrack in listOf(true, false)) {
                for (hasProfile in listOf(true, false)) {
                    val layout = shareCardLayout(format, hasTrack, hasProfile)
                    assertEquals(format.widthPx, layout.width)
                    assertEquals(format.heightPx, layout.height)
                    val rects = listOfNotNull(layout.track, layout.profile, layout.stats)
                    rects.forEach { r ->
                        assertTrue(r.left >= layout.padding && r.right <= layout.width - layout.padding)
                        assertTrue(r.top >= layout.padding && r.bottom <= layout.height - layout.padding)
                        assertTrue(r.width > 0f && r.height > 0f)
                    }
                    layout.track?.let { t -> layout.profile?.let { p -> assertTrue(t.bottom <= p.top) } }
                    layout.track?.let { t -> assertTrue(t.bottom <= layout.stats.top) }
                    layout.profile?.let { p -> assertTrue(p.bottom <= layout.stats.top) }
                    assertEquals(hasTrack, layout.track != null)
                    assertEquals(hasProfile, layout.profile != null)
                }
            }
        }
        assertEquals(1920, shareCardLayout(ShareCardFormat.STORY, true, true).height)
        assertEquals(1080, shareCardLayout(ShareCardFormat.SQUARE, true, true).height)
    }

    @Test
    fun `ohne Profil waechst die Spur, ohne Spur werden die Zahlen groesser`() {
        for (format in ShareCardFormat.entries) {
            val full = shareCardLayout(format, hasTrack = true, hasProfile = true)
            val noProfile = shareCardLayout(format, hasTrack = true, hasProfile = false)
            val noTrack = shareCardLayout(format, hasTrack = false, hasProfile = false)

            assertNotNull(noProfile.track)
            assertTrue(noProfile.track!!.bottom > full.track!!.bottom)
            assertNull(noTrack.track)
            assertTrue(noTrack.statValueSize > full.statValueSize)
        }
    }

    // ------------------------------------------------ Datumszeile, Dateiname

    @Test
    fun `Datumszeile immer mit Jahr, Planung beschriftet`() {
        val at = LocalDateTime.of(2025, 9, 23, 18, 12)

        assertEquals("Dienstag, 23. September 2025", shareCardDateLine(at, planned = false))
        assertEquals("Geplante Route · 23. September 2025", shareCardDateLine(at, planned = true))
    }

    @Test
    fun `Dateiname traegt das Format`() {
        assertEquals("Feierabendrunde-story.png", shareCardFileName("Feierabendrunde", ShareCardFormat.STORY))
        assertEquals("tour-square.png", shareCardFileName("  ", ShareCardFormat.SQUARE))
        assertNotEquals(
            shareCardFileName("Runde", ShareCardFormat.STORY),
            shareCardFileName("Runde", ShareCardFormat.SQUARE),
        )
    }

    @Test
    fun `Planung zeigt keine Trainingslast und sagt Geplante Route`() {
        val content = shareCardContent(ride(planned = true), load = 90.0, toLocal = utc)

        assertTrue(content.dateLine.startsWith("Geplante Route · "))
        assertTrue(content.stats.none { it.label == "Trainingslast" })
    }

    // -------------------------------------------------------------- Englisch

    @Test
    fun `Bildinhalt auf Englisch`() {
        val en = RidesXmlStrings.EN
        val content = shareCardContent(ride(name = "   ", points = emptyList()), load = null, toLocal = utc, strings = en)
        assertEquals("Ride", content.title)
        assertEquals("Tuesday 23 September 2025", content.dateLine)
        assertEquals(
            listOf(ShareStat("42.3", "km"), ShareStat("1:24", "h"), ShareStat("613", "m climbed"), ShareStat("85", "Training load")),
            shareCardStats(stats(), load = 84.6, planned = false, hasElevation = true, strings = en),
        )
        assertEquals("Avg HR", shareCardStats(stats(avgHrBpm = 142), null, false, true, en).last().label)
        assertEquals(
            "Planned route · 23 September 2025",
            shareCardDateLine(LocalDateTime.of(2025, 9, 23, 18, 12), planned = true, strings = en),
        )
    }
}
