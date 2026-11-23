package de.trailscape.core.indoor.world

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Vorschau der Gebaeude: ein Dorf aus echten Grundrissen entlang der
 * Vorschau-Strecke, geschrieben nach `core/build/indoor-preview/buildings/`.
 * Ansehen mit `tool/indoor-dev/viewer/shots.mjs`.
 */
class BuildingsPreviewTest {
    @Test
    fun `Dorf entlang der Strecke entsteht und haelt den Vertrag`() {
        val track = PreviewSupport.curvyTrack()
        val village = BuildingsVillage.along(track)
        assertTrue(village.size >= 35, "nur ${village.size} Gebaeude gestellt")
        val dir = PreviewSupport.write(
            "buildings",
            listOf(PreviewSupport.plainTerrain, BuildingsLayer()),
            track = track,
            map = ListMapData(village),
        )
        val manifest = WorldManifest.parse(File(dir, WorldManifest.FILE_NAME).readText())
        assertTrue(manifest.tiles.sumOf { it.triangles } > 3000, "kaum Dreiecke in der Vorschau")
    }
}
