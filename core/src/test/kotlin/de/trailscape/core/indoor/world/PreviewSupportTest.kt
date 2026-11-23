package de.trailscape.core.indoor.world

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PreviewSupportTest {
    @Test
    fun `Mini-Welt mit Platzhalter-Gelaende entsteht und ist lesbar`() {
        val dir = PreviewSupport.write("basis", listOf(PreviewSupport.plainTerrain))
        val manifest = WorldManifest.parse(File(dir, WorldManifest.FILE_NAME).readText())
        assertTrue(manifest.tiles.isNotEmpty())
        assertTrue(manifest.tiles.all { File(dir, it.file).length() == it.bytes })
    }

    @Test
    fun `naechster Streckenpunkt liefert Laufmeter und Seite`() {
        val track = TrackLine(PreviewSupport.curvyTrack())
        val pose = track.poseAt(200.0)
        val h = Math.toRadians(pose.headingDeg)
        // 3 m rechts der Fahrtrichtung.
        val hit = track.nearest(pose.e + Math.cos(h) * 3.0, pose.n - Math.sin(h) * 3.0)!!
        assertTrue(kotlin.math.abs(hit.distM - 200.0) < 3.0, "Laufmeter ${hit.distM}")
        assertTrue(kotlin.math.abs(hit.lateral - 3.0) < 0.2, "Versatz ${hit.lateral}")
    }
}
