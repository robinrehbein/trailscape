package de.trailscape.core.garage

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Muster, Farben der Zonen, andere Fahrer und Freischaltungen. */
class RiderLookTest {
    @Test
    fun `jede Zone bekommt eine Farbe`() {
        for (p in JerseyPattern.entries) {
            val z = RiderLook.zoneColors(RiderAvatar(pattern = p))
            assertEquals(RiderZone.entries.toSet(), z.keys)
            assertTrue(z.values.all { it in 0..0xFFFFFF })
        }
        val f = RiderLook.materialFactors(RiderAvatar.DEFAULT)
        assertEquals(RiderZone.entries.map { it.materialName }.toSet(), f.keys)
        assertTrue(f.values.all { it.size == 4 && it[3] == 1f && it.all { c -> c in 0f..1f } })
    }

    @Test
    fun `Vorgabe entspricht dem bisherigen Trikot`() {
        val z = RiderLook.zoneColors(RiderAvatar.DEFAULT)
        assertEquals(0xD8432C, z[RiderZone.JERSEY_LOW])
        assertEquals(0xF1EEE8, z[RiderZone.JERSEY_BAND])
        // Dunkle Seitenteile und Saeume nahe am bisherigen 0x8C2519.
        val dark = z.getValue(RiderZone.JERSEY_SIDE)
        assertTrue(abs(((dark shr 16) and 0xFF) - 0x8C) <= 6, "Seitenteil ${dark.toString(16)}")
        assertEquals(dark, z[RiderZone.JERSEY_TRIM])
        assertEquals(GaragePalettes.SKIN[RiderAvatar.DEFAULT_SKIN], z[RiderZone.SKIN])
        assertEquals(0xD6A07E, z[RiderZone.SKIN])
    }

    @Test
    fun `Muster verteilen Grund- und Akzentfarbe`() {
        val p = 0x2C5BB8
        val a = 0xE8B923
        fun zones(pattern: JerseyPattern) = RiderLook.zoneColors(RiderAvatar(jersey = p, jerseyAccent = a, pattern = pattern))
        val fields = listOf(RiderZone.JERSEY_LOW, RiderZone.JERSEY_MID, RiderZone.JERSEY_BAND, RiderZone.JERSEY_UP, RiderZone.JERSEY_YOKE)
        assertTrue(fields.all { zones(JerseyPattern.SOLID)[it] == p })
        assertEquals(p, zones(JerseyPattern.SOLID)[RiderZone.JERSEY_SIDE])
        assertEquals(listOf(p, p, a, p, p), fields.map { zones(JerseyPattern.BAND)[it] })
        assertEquals(listOf(p, p, a, a, a), fields.map { zones(JerseyPattern.SPLIT)[it] })
        assertEquals(listOf(p, a, p, a, p), fields.map { zones(JerseyPattern.STRIPES)[it] })
        val fade = fields.map { zones(JerseyPattern.FADE).getValue(it) }
        assertEquals(p, fade.first())
        assertEquals(a, fade.last())
        // Der Verlauf laeuft im Rotkanal stetig von Blau (0x2C) zu Gelb (0xE8).
        val reds = fade.map { (it shr 16) and 0xFF }
        assertEquals(reds.sorted(), reds)
        assertEquals(5, fade.toSet().size)
    }

    @Test
    fun `Farbrechnung ist umkehrbar und bleibt im Bereich`() {
        for (c in listOf(0x000000, 0xFFFFFF, 0xD8432C, 0x123456)) {
            assertEquals(c, RiderLook.mixSrgb(c, c, 0.5))
            assertEquals(c, RiderLook.shade(c, 1.0))
        }
        assertEquals(0x000000, RiderLook.shade(0xFFFFFF, 0.0))
        val lin = RiderLook.linearRgba(0xFFFFFF)
        assertTrue(lin.all { abs(it - 1f) < 1e-6f })
        assertEquals(0f, RiderLook.linearRgba(0)[0])
    }

    @Test
    fun `Mitfahrer sind untereinander und vom eigenen Fahrer unterscheidbar`() {
        val others = RiderLook.otherRiders(RiderAvatar.DEFAULT)
        assertEquals(6, others.size)
        assertEquals(6, others.map { it.jersey }.toSet().size)
        assertTrue(others.none { it.jersey == RiderAvatar.DEFAULT.jersey })
        // Traegt der Nutzer eine ihrer Farben, weicht diese aus.
        for (own in others) {
            val o = RiderLook.otherRiders(RiderAvatar(jersey = own.jersey))
            assertTrue(o.none { it.jersey == own.jersey }, "Trikot ${own.jersey.toString(16)}")
            assertEquals(6, o.map { it.jersey }.toSet().size)
        }
        val purple = RiderLook.otherRiders(RiderAvatar(jersey = GaragePalettes.PURPLE.srgb))
        assertTrue(purple.none { it.jersey == GaragePalettes.PURPLE.srgb })
    }

    @Test
    fun `Paletten enthalten die Vorgaben und sind eindeutig`() {
        val d = RiderAvatar.DEFAULT
        fun has(slot: GarageSlot, c: Int) = GaragePalettes.forSlot(slot).any { it.srgb == c }
        assertTrue(has(GarageSlot.JERSEY, d.jersey))
        assertTrue(has(GarageSlot.JERSEY_ACCENT, d.jerseyAccent))
        assertTrue(has(GarageSlot.BIBS, d.bibs))
        assertTrue(has(GarageSlot.HELMET, d.helmet))
        assertTrue(has(GarageSlot.SHOES, d.shoes))
        assertTrue(has(GarageSlot.FRAME, d.frame))
        assertTrue(has(GarageSlot.FRAME_ACCENT, d.frameAccent))
        assertTrue(has(GarageSlot.BAR_TAPE, d.barTape))
        assertEquals(GaragePalettes.ALL.size, GaragePalettes.ALL.map { it.id }.toSet().size)
        assertEquals(GaragePalettes.ALL.size, GaragePalettes.ALL.map { it.srgb }.toSet().size)
        for (slot in GarageSlot.entries) assertTrue(GaragePalettes.forSlot(slot).size >= 6, "$slot")
        assertEquals(8, GaragePalettes.SKIN.size)
        assertEquals(GaragePalettes.RED, GaragePalettes.find(0xD8432C))
    }

    private class Progress(override val level: Int, val badges: Set<String> = emptySet()) : GarageProgress {
        override fun hasBadge(badgeId: String) = badgeId in badges
    }

    @Test
    fun `ohne Erfolge ist alles frei`() {
        for (p in JerseyPattern.entries) assertTrue(GarageUnlocks.isUnlocked(p, null))
        for (c in GaragePalettes.ALL) assertTrue(GarageUnlocks.isUnlocked(c, null))
    }

    @Test
    fun `mit Erfolgen gelten Stufen und Abzeichen, Grundfarben immer frei`() {
        val start = Progress(level = 1)
        // Grundsatz: Grundfarben und ein Muster sind immer frei.
        assertTrue(GarageUnlocks.isUnlocked(JerseyPattern.SOLID, start))
        assertTrue(GarageUnlocks.isUnlocked(JerseyPattern.BAND, start))
        for (c in GaragePalettes.ALL - setOf(GaragePalettes.GOLD, GaragePalettes.COPPER)) assertTrue(GarageUnlocks.isUnlocked(c, start), c.id)
        assertTrue(!GarageUnlocks.isUnlocked(JerseyPattern.SPLIT, start))
        assertTrue(GarageUnlocks.isUnlocked(JerseyPattern.SPLIT, Progress(level = 2)))
        assertTrue(!GarageUnlocks.isUnlocked(JerseyPattern.FADE, Progress(level = 50)))
        assertTrue(GarageUnlocks.isUnlocked(JerseyPattern.FADE, Progress(level = 1, badges = setOf(GarageUnlocks.BADGE_FASTER_UP))))
        assertTrue(!GarageUnlocks.isUnlocked(GaragePalettes.GOLD, start))
        assertTrue(GarageUnlocks.isUnlocked(GaragePalettes.GOLD, Progress(1, setOf(GarageUnlocks.BADGE_EVEREST))))
        assertEquals(GarageRequirement.Level(10), GarageUnlocks.color(GaragePalettes.COPPER))
        // Hauttoene haben keine Bedingung (sie sind keine Freischaltung).
    }
}
