package de.trailscape.app.ui.indoor

import de.trailscape.core.indoor.world.WorldBuildPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildProgressTest {
    @Test
    fun verschraenkteMeldungenLassenDenBalkenNieZurueckspringen() {
        val progress = BuildProgress()
        var last = 0f
        fun check(phase: WorldBuildPhase, done: Int, total: Int): IndoorWorldUi.Building {
            val ui = progress.report(phase, done, total)
            assertTrue("Balken ging zurueck: ${ui.fraction} < $last bei $phase $done/$total", ui.fraction >= last)
            last = ui.fraction
            return ui
        }
        for (i in 0..10) check(WorldBuildPhase.ELEVATION, i, 10)
        for (i in 0..20) check(WorldBuildPhase.VECTOR, i, 20)
        // Fliessband wie im Weltbau: Luftbild laeuft voraus, Geometrie und Speichern hinterher.
        for (i in 0..349) {
            val aerial = check(WorldBuildPhase.AERIAL, minOf(349, i + 25), 349)
            val meshed = (i - 20).coerceAtLeast(0)
            check(WorldBuildPhase.MESHING, meshed, 349)
            val writing = check(WorldBuildPhase.WRITING, meshed, 350)
            if (i + 25 < 349) {
                // Solange Luftbilder fehlen, bleibt die Anzeige bei den Luftbildern.
                assertEquals(WorldBuildPhase.AERIAL, aerial.phase)
                assertEquals(WorldBuildPhase.AERIAL, writing.phase)
            }
        }
        for (i in 330..349) {
            check(WorldBuildPhase.MESHING, i, 349)
            check(WorldBuildPhase.WRITING, i, 350)
        }
        val end = check(WorldBuildPhase.WRITING, 350, 350)
        assertEquals(1f, end.fraction, 0.0001f)
        assertEquals(WorldBuildPhase.WRITING, end.phase)
    }

    @Test
    fun fortgesetzterBauOhneLadephasenBeginntNichtBeiNull() {
        val ui = BuildProgress().report(WorldBuildPhase.AERIAL, 0, 100)
        assertEquals(0.20f, ui.fraction, 0.0001f)
        assertEquals(WorldBuildPhase.AERIAL, ui.phase)
    }
}
