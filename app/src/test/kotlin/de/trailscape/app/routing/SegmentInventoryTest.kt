package de.trailscape.app.routing

import de.trailscape.core.KeyValueStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der Bestandsverwaltung (`SegmentInventory.kt`).
 *
 * Laeuft ohne Android: Das Verzeichnis ist ein Temp-Verzeichnis, der
 * [KeyValueStore] eine HashMap. Genau dafuer nimmt der Bestand die
 * `:core`-Schnittstelle statt `SharedPreferences` entgegen — `:app` hat
 * bewusst kein Robolectric.
 */
class SegmentInventoryTest {

    private val store = MemoryKeyValueStore()
    private val dir = createTempDir()
    private val inventory = SegmentInventory(dir, SegmentMetadataStore(store))

    @Test
    fun `zaehlt nur echte Kacheln und uebergeht alles andere`() {
        writeFile("E10_N50.rd5", 100)
        writeFile("E5_N45.rd5", 200)
        // Teildatei eines laufenden Downloads, Zwischendatei, Fremdes.
        writeFile("E15_N50.rd5.part", 50)
        writeFile("E15_N50.rd5.new", 50)
        writeFile("lookups.dat", 10)
        // Ein Name, der zwar auf .rd5 endet, aber kein Kachelraster trifft.
        writeFile("E11_N50.rd5", 10)

        val list = inventory.list()
        assertEquals(listOf("E10_N50.rd5", "E5_N45.rd5"), list.map { it.fileName })
        assertEquals(300L, inventory.totalBytes())
        assertTrue(inventory.contains("E10_N50.rd5"))
        assertFalse(inventory.contains("E15_N50.rd5"))
        assertEquals(50L, inventory.partialBytes("E15_N50.rd5"))
    }

    @Test
    fun `sortiert nach Kachelname, nicht nach Dateisystemreihenfolge`() {
        writeFile("W5_N50.rd5", 1)
        writeFile("E10_N50.rd5", 1)
        writeFile("E5_N45.rd5", 1)
        assertEquals(
            listOf("E10_N50", "E5_N45", "W5_N50"),
            inventory.list().map { it.tile.name },
        )
    }

    @Test
    fun `das Alter kommt aus der Serverangabe, nicht aus dem Dateidatum`() {
        writeFile("E10_N50.rd5", 100)
        // Die Datei ist gerade eben entstanden; die Kartendaten darin sind
        // aber vom 3. August. Genau diesen Unterschied soll die Anzeige sehen.
        inventory.metadata.write(
            "E10_N50.rd5",
            SegmentMetadata(
                eTag = "\"abc\"",
                lastModified = "Mon, 03 Aug 2026 01:03:01 GMT",
                downloadedAtMs = 1_786_582_981_000L,
            ),
        )

        val segment = assertNotNull(inventory.list().firstOrNull())
        // 13.08.2026 01:03:01 GMT — zehn Tage nach dem Stand der Daten.
        assertEquals(10L, segment.ageDays(nowMs = 1_786_582_981_000L))
        assertEquals(1_785_718_981_000L, segment.dataTimestampMs)
    }

    @Test
    fun `ohne gemerkte Serverangabe bleibt das Alter unbekannt statt geraten`() {
        writeFile("E10_N50.rd5", 100)
        val segment = assertNotNull(inventory.list().firstOrNull())
        assertNull(segment.dataTimestampMs)
        assertNull(segment.ageDays())
    }

    @Test
    fun `localSegment liefert den Stand fuer den Abgleich mit dem Server`() {
        assertNull(inventory.localSegment("E10_N50.rd5"))

        writeFile("E10_N50.rd5", 4711)
        inventory.metadata.write(
            "E10_N50.rd5",
            SegmentMetadata(eTag = "\"abc\"", lastModified = "Mon, 03 Aug 2026 01:03:01 GMT"),
        )

        val local = assertNotNull(inventory.localSegment("E10_N50.rd5"))
        assertEquals(4711L, local.sizeBytes)
        assertEquals("\"abc\"", local.eTag)
        assertEquals("Mon, 03 Aug 2026 01:03:01 GMT", local.lastModified)
    }

    @Test
    fun `Loeschen raeumt Datei, Zwischendateien und Vermerk zusammen weg`() {
        writeFile("E10_N50.rd5", 100)
        writeFile("E10_N50.rd5.part", 10)
        writeFile("E10_N50.rd5.df5", 10)
        writeFile("E10_N50.rd5.new", 10)
        inventory.metadata.write("E10_N50.rd5", SegmentMetadata(eTag = "\"abc\""))

        assertTrue(inventory.delete("E10_N50.rd5"))
        assertEquals(emptyList(), dir.list()?.toList())
        assertEquals(SegmentMetadata(), inventory.metadata.read("E10_N50.rd5"))
    }

    @Test
    fun `Teildateien ueberleben einen Fehlschlag, aber nicht das Aufraeumen`() {
        writeFile("E10_N50.rd5.part", 4711)
        assertEquals(4711L, inventory.partialBytes("E10_N50.rd5"))
        inventory.deleteTemporaries("E10_N50.rd5")
        assertEquals(0L, inventory.partialBytes("E10_N50.rd5"))
    }

    @Test
    fun `kaputte Metadaten gelten als unbekannt und sprengen nichts`() {
        writeFile("E10_N50.rd5", 100)
        store.setString("trailscape.segment.E10_N50.rd5", "{das ist kein JSON")
        val segment = assertNotNull(inventory.list().firstOrNull())
        assertEquals(SegmentMetadata(), segment.metadata)
    }

    @Test
    fun `Metadaten ueberstehen den Weg durch den Speicher unveraendert`() {
        val meta = SegmentMetadata(
            eTag = "\"6a7d17c5-76c804e\"",
            lastModified = "Thu, 13 Aug 2026 01:03:01 GMT",
            downloadedAtMs = 1_786_582_981_000L,
        )
        inventory.metadata.write("E10_N50.rd5", meta)
        assertEquals(meta, inventory.metadata.read("E10_N50.rd5"))
    }

    // -----------------------------------------------------------------------
    // Hilfen
    // -----------------------------------------------------------------------

    private fun writeFile(name: String, bytes: Int) {
        File(dir, name).writeBytes(ByteArray(bytes))
    }

    private fun createTempDir(): File {
        val dir = File.createTempFile("trailscape-bestand", "")
        check(dir.delete() && dir.mkdirs())
        dir.deleteOnExit()
        return dir
    }
}

/** [KeyValueStore] im Speicher — der Ersatz fuer `SharedPreferences` im Test. */
internal class MemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun setString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
