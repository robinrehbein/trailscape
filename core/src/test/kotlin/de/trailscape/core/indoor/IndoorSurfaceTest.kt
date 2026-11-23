package de.trailscape.core.indoor

import de.trailscape.core.SurfaceClass
import de.trailscape.core.classifySurface
import de.trailscape.core.defaultCrr
import de.trailscape.core.parseWayTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests fuer `IndoorSurface.kt` gegen die Tabelle in docs/indoor.md, Abschnitt 6. */
class IndoorSurfaceTest {

    private fun kindOf(wayTags: String) = surfaceKindFromTags(parseWayTags(wayTags))

    // -----------------------------------------------------------------------
    // Tabelle aus docs/indoor.md Abschnitt 6
    // -----------------------------------------------------------------------

    private val surfaceTable: Map<SurfaceKind, List<String>> = mapOf(
        SurfaceKind.ASPHALT to listOf("asphalt", "paved", "chipseal"),
        SurfaceKind.CONCRETE to listOf("concrete", "concrete:plates", "concrete:lanes"),
        SurfaceKind.PAVING_STONES to listOf("paving_stones", "bricks", "grass_paver"),
        SurfaceKind.COBBLESTONE to listOf("sett", "cobblestone", "unhewn_cobblestone"),
        SurfaceKind.WOOD to listOf("wood", "metal"),
        SurfaceKind.COMPACTED to listOf("compacted", "fine_gravel"),
        SurfaceKind.GRAVEL to listOf("gravel", "pebblestone", "unpaved", "rock", "stone"),
        SurfaceKind.DIRT to listOf("ground", "dirt", "earth", "clay"),
        SurfaceKind.GRASS to listOf("grass"),
        SurfaceKind.SAND to listOf("sand", "mud"),
    )

    private val asphaltHighways = listOf(
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
        "residential", "living_street", "service", "road",
    )

    @Test
    fun `surface-Werte der Tabelle`() {
        for ((kind, values) in surfaceTable) {
            for (v in values) {
                assertEquals(kind, surfaceKindFromTags(mapOf("surface" to v)), "surface=$v")
                // Auch ueber die WayTags-Zelle wie von BRouter.
                assertEquals(kind, kindOf("highway=footway surface=$v"), "WayTags surface=$v")
            }
        }
    }

    @Test
    fun `tracktype-Werte der Tabelle`() {
        val cases = mapOf(
            "grade1" to SurfaceKind.ASPHALT,
            "grade2" to SurfaceKind.COMPACTED,
            "grade3" to SurfaceKind.GRAVEL,
            "grade4" to SurfaceKind.DIRT,
            "grade5" to SurfaceKind.GRASS,
        )
        for ((tt, kind) in cases) {
            assertEquals(kind, kindOf("tracktype=$tt"), tt)
            assertEquals(kind, kindOf("highway=track tracktype=$tt"), "track $tt")
        }
    }

    @Test
    fun `highway ohne Belag`() {
        for (hw in asphaltHighways) assertEquals(SurfaceKind.ASPHALT, kindOf("highway=$hw"), hw)
        assertEquals(SurfaceKind.GRAVEL, kindOf("highway=track"))
        assertEquals(SurfaceKind.DIRT, kindOf("highway=path"))
        assertEquals(SurfaceKind.DIRT, kindOf("highway=bridleway"))
        for (hw in listOf("cycleway", "footway", "pedestrian", "steps", "unbekannt")) {
            assertEquals(SurfaceKind.UNKNOWN, kindOf("highway=$hw"), hw)
        }
        assertEquals(SurfaceKind.UNKNOWN, surfaceKindFromTags(emptyMap()))
        assertEquals(SurfaceKind.UNKNOWN, kindOf(""))
    }

    @Test
    fun `Reihenfolge surface vor tracktype vor highway`() {
        assertEquals(SurfaceKind.SAND, kindOf("highway=residential tracktype=grade1 surface=sand"))
        assertEquals(SurfaceKind.GRASS, kindOf("highway=residential tracktype=grade5"))
        assertEquals(SurfaceKind.ASPHALT, kindOf("highway=track surface=asphalt tracktype=grade4"))
        // Unbekannter surface-Wert faellt zum Tracktyp durch, unbekannter Tracktyp zur Wegart.
        assertEquals(SurfaceKind.DIRT, kindOf("highway=track surface=wunderbar tracktype=grade4"))
        assertEquals(SurfaceKind.GRAVEL, kindOf("highway=track surface=wunderbar tracktype=grade9"))
        assertEquals(SurfaceKind.UNKNOWN, kindOf("highway=cycleway surface=wunderbar"))
    }

    @Test
    fun `Crr und Breite je Klasse wie in der Tabelle`() {
        val expected = mapOf(
            SurfaceKind.ASPHALT to (0.0055 to 5.0),
            SurfaceKind.CONCRETE to (0.0060 to 3.5),
            SurfaceKind.PAVING_STONES to (0.0075 to 3.0),
            SurfaceKind.COBBLESTONE to (0.0110 to 4.0),
            SurfaceKind.WOOD to (0.0070 to 2.5),
            SurfaceKind.COMPACTED to (0.0080 to 3.0),
            SurfaceKind.GRAVEL to (0.0120 to 3.0),
            SurfaceKind.DIRT to (0.0160 to 1.5),
            SurfaceKind.GRASS to (0.0250 to 2.0),
            SurfaceKind.SAND to (0.0350 to 2.0),
            SurfaceKind.UNKNOWN to (0.0080 to 3.0),
        )
        assertEquals(SurfaceKind.entries.toSet(), expected.keys)
        for ((kind, cw) in expected) {
            assertEquals(cw.first, kind.crr, 1e-12, "$kind crr")
            assertEquals(cw.second, kind.defaultWidthM, 1e-12, "$kind Breite")
        }
        // UNKNOWN rechnet exakt wie bisher.
        assertEquals(defaultCrr, SurfaceKind.UNKNOWN.crr)
        assertEquals(defaultCrr, SurfaceKind.REFERENCE_PROFILE_CRR)
    }

    @Test
    fun `Aussehen, Texturschluessel und grobe Klasse`() {
        assertEquals(SurfaceKind.COMPACTED, SurfaceKind.UNKNOWN.visual)
        for (k in SurfaceKind.entries.filter { it != SurfaceKind.UNKNOWN }) {
            assertEquals(k, k.visual)
            assertEquals(k.textureKey.lowercase(), k.textureKey)
        }
        assertEquals(SurfaceKind.COMPACTED.textureKey, SurfaceKind.UNKNOWN.textureKey)
        val keys = SurfaceKind.entries.filter { it != SurfaceKind.UNKNOWN }.map { it.textureKey }
        assertEquals(keys.size, keys.toSet().size, "Texturschluessel eindeutig")
        assertEquals(SurfaceClass.UNKNOWN, SurfaceKind.UNKNOWN.coarse)
        for (k in listOf(SurfaceKind.ASPHALT, SurfaceKind.CONCRETE, SurfaceKind.PAVING_STONES, SurfaceKind.COBBLESTONE, SurfaceKind.WOOD)) {
            assertEquals(SurfaceClass.PAVED, k.coarse, "$k")
        }
        for (k in listOf(SurfaceKind.COMPACTED, SurfaceKind.GRAVEL, SurfaceKind.DIRT, SurfaceKind.GRASS, SurfaceKind.SAND)) {
            assertEquals(SurfaceClass.UNPAVED, k.coarse, "$k")
        }
    }

    @Test
    fun `feine Klasse widerspricht der groben nie`() {
        // Wo classifySurface etwas weiss, muss die feine Klasse dazu gehoeren.
        val samples = surfaceTable.values.flatten().map { "surface=$it" } +
            (1..5).map { "tracktype=grade$it" } +
            asphaltHighways.map { "highway=$it" } +
            listOf("highway=track", "highway=path", "highway=cycleway", "highway=track surface=asphalt tracktype=grade4")
        for (s in samples) {
            val coarse = classifySurface(s)
            if (coarse == SurfaceClass.UNKNOWN) continue
            assertEquals(coarse, kindOf(s).coarse, s)
        }
    }

    // -----------------------------------------------------------------------
    // effectiveCrr
    // -----------------------------------------------------------------------

    @Test
    fun `effectiveCrr skaliert mit dem Profil`() {
        for (k in SurfaceKind.entries) {
            assertEquals(k.crr, effectiveCrr(k, 0.008), 1e-15)
            assertEquals(k.crr / 2, effectiveCrr(k, 0.004), 1e-15)
            assertEquals(k.crr * 1.5, effectiveCrr(k, 0.012), 1e-15)
        }
    }

    @Test
    fun `effectiveCrr mit unbrauchbarem Profilwert nimmt den Bezugswert`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.005)) {
            assertEquals(SurfaceKind.GRAVEL.crr, effectiveCrr(SurfaceKind.GRAVEL, bad), "Profil $bad")
        }
    }

    // -----------------------------------------------------------------------
    // Vektorkacheln
    // -----------------------------------------------------------------------

    @Test
    fun `Rueckfall aus Vektorkacheln`() {
        assertEquals(SurfaceKind.ASPHALT, surfaceKindFromVectorTile("track", "paved"))
        assertEquals(SurfaceKind.GRAVEL, surfaceKindFromVectorTile("track", "unpaved"))
        assertEquals(SurfaceKind.GRAVEL, surfaceKindFromVectorTile("track", null))
        assertEquals(SurfaceKind.ASPHALT, surfaceKindFromVectorTile("path", "paved"))
        assertEquals(SurfaceKind.DIRT, surfaceKindFromVectorTile("path", "unpaved"))
        assertEquals(SurfaceKind.UNKNOWN, surfaceKindFromVectorTile("path", null))
        assertEquals(SurfaceKind.GRAVEL, surfaceKindFromVectorTile("minor", "unpaved"))
        assertEquals(SurfaceKind.GRAVEL, surfaceKindFromVectorTile(null, "unpaved"))
        for (rc in listOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service")) {
            assertEquals(SurfaceKind.ASPHALT, surfaceKindFromVectorTile(rc, null), rc)
            assertEquals(SurfaceKind.ASPHALT, surfaceKindFromVectorTile(rc, "paved"), rc)
        }
        assertEquals(SurfaceKind.UNKNOWN, surfaceKindFromVectorTile(null, null))
        assertEquals(SurfaceKind.UNKNOWN, surfaceKindFromVectorTile(null, "paved"))
    }

    @Test
    fun `Vektorkacheln ergeben hoechstens Asphalt, Schotter, Erde oder unbekannt`() {
        val classes = listOf(null, "motorway", "minor", "service", "track", "path", "raceway")
        val surfaces = listOf(null, "paved", "unpaved", "gravel")
        val allowed = setOf(SurfaceKind.ASPHALT, SurfaceKind.GRAVEL, SurfaceKind.DIRT, SurfaceKind.UNKNOWN)
        for (rc in classes) for (s in surfaces) {
            assertTrue(surfaceKindFromVectorTile(rc, s) in allowed, "$rc/$s")
        }
    }

    // -----------------------------------------------------------------------
    // fromNameOrNull
    // -----------------------------------------------------------------------

    @Test
    fun `fromNameOrNull liest jeden Namen und sonst null`() {
        for (k in SurfaceKind.entries) assertEquals(k, SurfaceKind.fromNameOrNull(k.name))
        assertNull(SurfaceKind.fromNameOrNull(null))
        assertNull(SurfaceKind.fromNameOrNull(""))
        assertNull(SurfaceKind.fromNameOrNull("asphalt"))
        assertNull(SurfaceKind.fromNameOrNull("LAVA"))
    }
}
