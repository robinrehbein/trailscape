package de.trailscape.core.garage

import de.trailscape.core.KeyValueStore
import de.trailscape.core.buildBackupJson
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.parseBackupJson
import de.trailscape.core.writeBackupJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Format des Avatars (alt/neu), Speicher und Backup. */
class RiderAvatarTest {
    /** Festgeschriebene Bytes von Version 1 — aendert sich das, ist das Format gebrochen. */
    private val v1Default =
        """{"v":1,"body":{"skin":2},"kit":{"jersey":"#D8432C","jerseyAccent":"#F1EEE8","pattern":"band","bibs":"#25262B",""" +
            """"shoes":"#F1EEE8","helmet":"#F1EEE8"},"bike":{"frame":"#1F6272","frameAccent":"#F1EEE8","barTape":"#25262B"}}"""

    @Test
    fun `Vorgabe schreibt genau das Format von Version 1`() {
        assertEquals(v1Default, RiderAvatar.DEFAULT.toJson())
        assertTrue(RiderAvatar.DEFAULT.toJson().length < 300)
    }

    @Test
    fun `hin und zurueck bleibt alles`() {
        val a = RiderAvatar(
            skin = 7, jersey = 0x2C5BB8, jerseyAccent = 0xE8B923, pattern = JerseyPattern.FADE, bibs = 0x1E2E5C,
            shoes = 0x25262B, helmet = 0xD8432C, frame = 0xF1EEE8, frameAccent = 0x000000, barTape = 0xB28B5C,
        )
        assertEquals(a, RiderAvatar.parse(a.toJson()))
        for (p in JerseyPattern.entries) assertEquals(p, RiderAvatar.parse(a.copy(pattern = p).toJson())!!.pattern)
    }

    @Test
    fun `gespeicherte Version 1 wird gelesen`() {
        val stored = """{"v":1,"body":{"skin":5},"kit":{"jersey":"#1f7a4d","jerseyAccent":"#F1EEE8","pattern":"stripes","bibs":"#25262B",""" +
            """"shoes":"#F1EEE8","helmet":"#E8B923"},"bike":{"frame":"#D8432C","frameAccent":"#25262B","barTape":"#B28B5C"}}"""
        val a = assertNotNull(RiderAvatar.parse(stored))
        assertEquals(5, a.skin)
        assertEquals(0x1F7A4D, a.jersey)
        assertEquals(JerseyPattern.STRIPES, a.pattern)
        assertEquals(0xE8B923, a.helmet)
        assertEquals(0xB28B5C, a.barTape)
    }

    @Test
    fun `kuenftige Version mit weiteren Feldern liest die bekannten`() {
        // So koennte eine spaetere Version aussehen (Konzept 3.6: Haar, Socken, Aermel, Radtyp, Reifen).
        val future = """{"v":3,"body":{"skin":1,"hair":4},"kit":{"jersey":"#2C5BB8","pattern":"polka","sleeves":"long","socks":"#FFFFFF"},""" +
            """"bike":{"type":"road","frame":"#25262B","tires":"black"},"extra":[1,2,3]}"""
        val a = assertNotNull(RiderAvatar.parse(future))
        assertEquals(1, a.skin)
        assertEquals(0x2C5BB8, a.jersey)
        // Unbekanntes Muster → Vorgabe; fehlende Felder → Vorgabe.
        assertEquals(RiderAvatar.DEFAULT.pattern, a.pattern)
        assertEquals(RiderAvatar.DEFAULT.jerseyAccent, a.jerseyAccent)
        assertEquals(0x25262B, a.frame)
        assertEquals(RiderAvatar.DEFAULT.barTape, a.barTape)
    }

    @Test
    fun `kaputte Werte fallen auf die Vorgabe zurueck, nie ein Fehler`() {
        val bad = """{"v":"x","body":{"skin":99},"kit":{"jersey":"rot","jerseyAccent":"#12345","pattern":7,"bibs":123,""" +
            """"helmet":"#GGGGGG"},"bike":"kaputt"}"""
        assertEquals(RiderAvatar.DEFAULT, RiderAvatar.parse(bad))
        assertEquals(RiderAvatar.DEFAULT, RiderAvatar.parse("{}"))
        assertEquals(RiderAvatar.DEFAULT, RiderAvatar.parse("""{"body":{"skin":-1}}"""))
        assertEquals(RiderAvatar.DEFAULT, RiderAvatar.parse("""{"body":{"skin":"2"}}"""))
        assertNull(RiderAvatar.parse("[1,2]"))
        assertNull(RiderAvatar.parse("kein json"))
        assertNull(RiderAvatar.parse(""))
        assertNull(RiderAvatar.parse(null))
    }

    @Test
    fun `ungueltige Werte lassen sich nicht bauen`() {
        assertFailsWith<IllegalArgumentException> { RiderAvatar(skin = 8) }
        assertFailsWith<IllegalArgumentException> { RiderAvatar(jersey = 0x1000000) }
        assertFailsWith<IllegalArgumentException> { RiderAvatar(barTape = -1) }
    }

    @Test
    fun `kein Freitext im Format`() {
        // Nur Zahlen, Farbcodes und feste Kennungen.
        val strings = Regex("\"([^\"]*)\"").findAll(RiderAvatar(pattern = JerseyPattern.SPLIT).toJson()).map { it.groupValues[1] }.toList()
        val keys = setOf("v", "body", "skin", "kit", "jersey", "jerseyAccent", "pattern", "bibs", "shoes", "helmet", "bike", "frame", "frameAccent", "barTape")
        for (s in strings) {
            assertTrue(s in keys || Regex("^#[0-9A-F]{6}$").matches(s) || JerseyPattern.fromId(s) != null, "unerwarteter Text \"$s\"")
        }
    }

    private class MemoryStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun setString(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun `Speicher liefert die Vorgabe ohne Eintrag und bei Muell`() {
        val m = MemoryStore()
        val store = RiderAvatarStore(m)
        assertEquals(RiderAvatar.DEFAULT, store.load())
        assertTrue(!store.hasSaved())
        val a = RiderAvatar(jersey = 0x6A3D9A, pattern = JerseyPattern.SOLID)
        store.save(a)
        assertTrue(store.hasSaved())
        assertEquals(a, store.load())
        m.map[RiderAvatarStore.KEY] = "{{{"
        assertEquals(RiderAvatar.DEFAULT, store.load())
    }

    @Test
    fun `Backup traegt den Avatar unter avatar, beide Schreibweisen gleich`() {
        val a = RiderAvatar(skin = 0, jersey = 0xE07A1F, pattern = JerseyPattern.SPLIT)
        val json = buildBackupJson(emptyList(), null, exportedAtMs = 1_700_000_000_000L, avatar = a)
        assertTrue(json.contains("\"avatar\""))
        assertEquals(a, parseBackupJson(json, CoreTextsDe).avatar)
        val streamed = StringBuilder()
        writeBackupJson(streamed, emptySequence(), null, exportedAtMs = 1_700_000_000_000L, avatar = a)
        assertEquals(json, streamed.toString())
    }

    @Test
    fun `altes Backup ohne avatar bleibt lesbar und ohne Avatar`() {
        val old = """{"app": "trailscape", "backupVersion": 1, "exportedAt": "2026-01-01T00:00:00.000Z", "profile": null, "rides": []}"""
        assertNull(parseBackupJson(old, CoreTextsDe).avatar)
        val json = buildBackupJson(emptyList(), null, exportedAtMs = 1L)
        assertTrue(!json.contains("avatar"))
        // Kaputter Eintrag: kein Fehler, nur kein Avatar bzw. die Vorgabe.
        val broken = """{"app": "trailscape", "backupVersion": 1, "avatar": "x", "rides": []}"""
        assertNull(parseBackupJson(broken, CoreTextsDe).avatar)
    }
}
