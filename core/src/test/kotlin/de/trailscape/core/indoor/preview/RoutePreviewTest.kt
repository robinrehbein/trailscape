package de.trailscape.core.indoor.preview

import de.trailscape.core.indoor.IndoorTestRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Zeitplan und Abspieler der 3D-Vorschau (RoutePreview.kt). */
class RoutePreviewTest {
    private val rider = IndoorTestRoutes.RIDER

    /** Flach bis 1000 m, 6 % bis 2000 m, dann flach bis 3000 m. */
    private val hilly = IndoorTestRoutes.straight(lengthM = 3000.0, ele = IndoorTestRoutes.climbProfile(1000.0, 2000.0, 6.0))
    private val flat = IndoorTestRoutes.straight(lengthM = 2000.0)

    @Test
    fun zeitplanIstStetigUndUmkehrbar() {
        val tl = PreviewTimeline.of(hilly, rider)
        assertEquals(3000.0, tl.lengthM, 1e-9)
        assertTrue(tl.totalS > 0)
        var prev = -1.0
        for (d in 0..3000 step 50) {
            val t = tl.timeAt(d.toDouble())
            assertTrue(t >= prev, "Zeit steigt mit der Strecke")
            prev = t
            assertEquals(d.toDouble(), tl.distanceAt(t), 1e-6)
        }
        // Geklemmt statt Ausnahme.
        assertEquals(0.0, tl.timeAt(-5.0), 1e-9)
        assertEquals(tl.totalS, tl.timeAt(1e9), 1e-9)
        assertEquals(0.0, tl.timeAt(Double.NaN), 1e-9)
        assertEquals(3000.0, tl.distanceAt(1e9), 1e-9)
    }

    @Test
    fun bergaufLangsamerAlsInDerEbeneUndRealistisch() {
        val tl = PreviewTimeline.of(hilly, rider)
        val flatSpeed = tl.speedAt(500.0)
        val climbSpeed = tl.speedAt(1500.0)
        assertTrue(climbSpeed < flatSpeed * 0.7, "Berg $climbSpeed gegen Ebene $flatSpeed")
        // Ausdauerleistung in der Ebene: grob 22–36 km/h.
        assertTrue(flatSpeed * 3.6 in 22.0..36.0, "Ebene ${flatSpeed * 3.6} km/h")
        assertTrue(climbSpeed >= PreviewTimeline.MIN_SPEED_MPS)
    }

    @Test
    fun bergabGedeckelt() {
        val down = IndoorTestRoutes.straight(lengthM = 2000.0, ele = { d -> -d * 0.2 })
        val tl = PreviewTimeline.of(down, rider)
        assertTrue(tl.speedAt(1000.0) <= PreviewTimeline.MAX_SPEED_MPS + 1e-9)
    }

    @Test
    fun aufDenSchnittEinerTourGestreckt() {
        val tl = PreviewTimeline.of(hilly, rider, targetAvgSpeedMps = 5.0)
        assertEquals(3000.0 / 5.0, tl.totalS, 1e-6)
        // Das Verhaeltnis Berg/Ebene bleibt.
        val model = PreviewTimeline.of(hilly, rider)
        assertEquals(model.speedAt(500.0) / model.speedAt(1500.0), tl.speedAt(500.0) / tl.speedAt(1500.0), 1e-6)
        // Unplausibler Schnitt: beim Modell bleiben.
        assertEquals(model.totalS, PreviewTimeline.of(hilly, rider, targetAvgSpeedMps = 40.0).totalS, 1e-9)
        assertEquals(model.totalS, PreviewTimeline.of(hilly, rider, targetAvgSpeedMps = Double.NaN).totalS, 1e-9)
    }

    @Test
    fun geschwindigkeitenUndWeiterschalten() {
        assertEquals(listOf(1, 2, 4, 8), PREVIEW_SPEED_FACTORS)
        assertEquals(2, nextPreviewSpeedFactor(1))
        assertEquals(1, nextPreviewSpeedFactor(8))
        assertEquals(1, nextPreviewSpeedFactor(3))
        val p = PreviewPlayer(PreviewTimeline.of(flat, rider))
        p.setSpeedFactor(7)
        assertEquals(8, p.speedFactor)
        p.setSpeedFactor(-3)
        assertEquals(1, p.speedFactor)
    }

    @Test
    fun beginntAngehaltenUndSpieltMitDerUhr() {
        val tl = PreviewTimeline.of(flat, rider)
        val p = PreviewPlayer(tl)
        assertFalse(p.playing)
        assertEquals(0.0, p.tick(1_000).distM, 1e-9)
        p.play(1_000)
        var now = 1_000L
        repeat(40) {
            now += 50
            p.tick(now)
        }
        // 2 s bei 1× ≈ 2 s Weg der gedachten Fahrt.
        assertEquals(tl.distanceAt(2.0), p.distM, 0.5)
        val frame = p.frame(now)
        assertTrue(frame.playing)
        assertEquals(tl.speedAt(10.0), frame.speedMps, 0.05)
        assertEquals((tl.totalS - 2.0), frame.remainingS, 0.2)
    }

    @Test
    fun vierfachIstViermalSoWeit() {
        val tl = PreviewTimeline.of(flat, rider)
        // Ebene bei 4× (rund 30 m/s) bleibt sicher unter dem Deckel von 60 m/s; 8× laege knapp darueber.
        val p = PreviewPlayer(tl)
        p.setSpeedFactor(4)
        p.play(0)
        var now = 0L
        repeat(20) {
            now += 50
            p.tick(now)
        }
        // 1 s bei 4×.
        assertEquals(tl.distanceAt(4.0), p.distM, 0.5)
        assertEquals((tl.totalS - 4.0) / 4.0, p.frame(now).remainingS, 0.1)
    }

    @Test
    fun tempoIstGedeckelt() {
        val down = IndoorTestRoutes.straight(lengthM = 4000.0, ele = { d -> -d * 0.2 })
        val p = PreviewPlayer(PreviewTimeline.of(down, rider))
        p.setSpeedFactor(8)
        p.play(0)
        var now = 0L
        repeat(20) {
            now += 50
            p.tick(now)
        }
        assertTrue(p.distM <= PreviewPlayer.MAX_PLAYBACK_MPS * 1.0 + 1e-6, "nach 1 s ${p.distM} m")
        assertTrue(p.frame(now).speedMps <= PreviewPlayer.MAX_PLAYBACK_MPS + 1e-6)
    }

    @Test
    fun langeLueckeSpringtNicht() {
        val tl = PreviewTimeline.of(flat, rider)
        val p = PreviewPlayer(tl)
        p.play(0)
        // App 60 s im Hintergrund: nur ein Schritt von hoechstens MAX_STEP_MS.
        p.tick(60_000)
        assertEquals(tl.distanceAt(PreviewPlayer.MAX_STEP_MS / 1000.0), p.distM, 0.01)
    }

    @Test
    fun pauseHaeltAnUndSpringenGehtImmer() {
        val tl = PreviewTimeline.of(hilly, rider)
        val p = PreviewPlayer(tl)
        p.play(0)
        p.tick(100)
        p.pause()
        val d = p.distM
        p.tick(5_000)
        assertEquals(d, p.distM, 1e-9)
        assertEquals(0.0, p.frame(5_000).speedMps, 1e-9)
        p.seekTo(1500.0)
        val f = p.frame(5_000)
        assertEquals(1500.0, f.distM, 1e-9)
        assertEquals(6.0, f.gradePct, 0.01)
        assertEquals(30.0, f.eleM, 0.01)
        p.seekTo(-100.0)
        assertEquals(0.0, p.distM, 1e-9)
        p.seekTo(1e9)
        assertEquals(3000.0, p.distM, 1e-9)
    }

    @Test
    fun amZielStehtSieUndBeginntDannVonVorn() {
        val tl = PreviewTimeline.of(flat, rider)
        val p = PreviewPlayer(tl)
        p.seekTo(1999.0)
        p.play(0)
        p.tick(250)
        assertTrue(p.atEnd)
        assertFalse(p.playing)
        assertEquals(2000.0, p.distM, 1e-9)
        assertTrue(p.frame(250).atEnd)
        p.play(300)
        assertEquals(0.0, p.distM, 1e-9)
        assertTrue(p.playing)
    }

    @Test
    fun wartetAufKachelnAberNichtEwig() {
        val tl = PreviewTimeline.of(flat, rider)
        val p = PreviewPlayer(tl)
        p.play(0)
        p.setTilesMissing(true)
        var now = 0L
        // Wartet: kein Fortschritt
        repeat(20) {
            now += 100
            p.tick(now)
        }
        assertEquals(0.0, p.distM, 1e-9)
        assertTrue(p.frame(now).waitingForTiles)
        // Kacheln da: weiter
        p.setTilesMissing(false)
        now += 100
        p.tick(now)
        assertTrue(p.distM > 0.0)
        assertFalse(p.frame(now).waitingForTiles)
        // Kachel kommt nie: nach MAX_WAIT_MS geht es trotzdem weiter …
        p.setTilesMissing(true)
        val stuckAt = p.distM
        while (now < 2_100 + PreviewPlayer.MAX_WAIT_MS) {
            now += 100
            p.tick(now)
        }
        now += 100
        p.tick(now)
        assertTrue(p.distM > stuckAt, "nach der Wartezeit weiter")
        // … und wartet waehrend der Ruhezeit nicht erneut.
        assertFalse(p.isWaiting(now + 1_000))
    }

    @Test
    fun zustandFuerDieDarstellung() {
        val tl = PreviewTimeline.of(flat, rider)
        val p = PreviewPlayer(tl)
        val still = p.worldState(10)
        assertTrue(still.paused)
        assertEquals(0.0, still.rider.cadenceRpm, 1e-9)
        assertEquals(10L, still.timeMs)
        p.play(0)
        p.tick(50)
        val moving = p.worldState(50)
        assertFalse(moving.paused)
        assertEquals(p.distM, moving.rider.distM, 1e-9)
        assertTrue(moving.rider.speedMps > 0)
        assertEquals(PreviewPlayer.PREVIEW_CADENCE_RPM, moving.rider.cadenceRpm, 1e-9)
        assertEquals(null, moving.ghost)
    }
}
