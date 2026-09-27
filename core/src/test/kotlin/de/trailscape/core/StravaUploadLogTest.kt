package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StravaUploadLogTest {

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun setString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    @Test
    fun `put, get und remove mit allen Feldern`() {
        val store = MemoryStore()
        val log = StravaUploadLog(store)
        val failed = StravaUploadRecord(
            rideId = "r1",
            state = StravaUploadState.FAILED,
            uploadId = 42L,
            activityId = 7L,
            error = StravaError.RATE_LIMITED,
            detail = "HTTP 429",
            updatedAtMs = 1234L,
        )
        val done = StravaUploadRecord(rideId = "r2", state = StravaUploadState.DONE, activityId = 9L, updatedAtMs = 5L)

        log.put(failed)
        log.put(done)

        // Neu gelesen aus dem Speicher: verlustfrei.
        val fresh = StravaUploadLog(store)
        assertEquals(failed, fresh.get("r1"))
        assertEquals(done, fresh.get("r2"))
        assertEquals(2, fresh.all().size)

        fresh.remove("r1")
        assertNull(fresh.get("r1"))
        fresh.remove("r2")
        assertTrue(store.values.isEmpty(), "leeres Protokoll raeumt den Schluessel ab")
    }

    @Test
    fun `removeWhere entfernt nur Passendes`() {
        val log = StravaUploadLog(MemoryStore())
        log.put(StravaUploadRecord("a", StravaUploadState.UPLOADING, updatedAtMs = 1))
        log.put(StravaUploadRecord("b", StravaUploadState.DONE, activityId = 1, updatedAtMs = 1))
        log.removeWhere { it.state == StravaUploadState.UPLOADING }
        assertEquals(setOf("b"), log.all().keys)
    }

    @Test
    fun `kaputtes JSON ergibt ein leeres Protokoll`() {
        val store = MemoryStore()
        store.values[STRAVA_UPLOAD_LOG_KEY] = "{nicht json"
        assertEquals(emptyMap(), StravaUploadLog(store).all())
        store.values[STRAVA_UPLOAD_LOG_KEY] = "[1,2]"
        assertEquals(emptyMap(), StravaUploadLog(store).all())
    }

    @Test
    fun `unbekannte Felder und kaputte Eintraege werden uebergangen`() {
        val store = MemoryStore()
        store.values[STRAVA_UPLOAD_LOG_KEY] = """
            {
              "r1": {"state":"DONE","activityId":5,"updatedAtMs":10,"neuesFeld":true,"error":"GIBTSNICHT"},
              "r2": {"state":"UNBEKANNT","updatedAtMs":10},
              "r3": "kein Objekt",
              "r4": {"state":"UPLOADING"}
            }
        """.trimIndent()
        val all = StravaUploadLog(store).all()
        assertEquals(setOf("r1"), all.keys)
        assertEquals(StravaUploadRecord("r1", StravaUploadState.DONE, activityId = 5, updatedAtMs = 10), all["r1"])
    }

    @Test
    fun `isStale nur fuer UPLOADING aelter als sechs Stunden`() {
        val sixHours = 6 * 60 * 60 * 1000L
        val uploading = StravaUploadRecord("r", StravaUploadState.UPLOADING, updatedAtMs = 0)
        assertFalse(uploading.isStale(sixHours))
        assertTrue(uploading.isStale(sixHours + 1))
        assertFalse(uploading.copy(state = StravaUploadState.FAILED).isStale(sixHours * 10))
    }
}
