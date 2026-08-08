package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Portierung von `lib/sync_client.dart`.
 *
 * Direkt aus der `sync_client`-Gruppe in `test/routing_test.dart` uebernommen
 * (dort mitgetestet, weil das Original `sync_client_test.dart` nicht als
 * eigene Datei existiert) — gleiche Faelle, gleiche Erwartungswerte.
 *
 * [HttpClient] wird durch ein Fake ersetzt (Analogon zu Darts
 * `package:http/testing.dart`-`MockClient`); [KeyValueStore] durch ein
 * simples In-Memory-Fake (Analogon zu Darts
 * `SharedPreferences.setMockInitialValues`).
 */
class SyncClientTest {
    private class FakeKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
        private val values = initial.toMutableMap()

        override fun getString(key: String): String? = values[key]
        override fun setString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private fun failingClient(): HttpClient = HttpClient { throw AssertionError("sollte nicht aufgerufen werden") }

    @Test
    fun `getSyncConfig-setSyncConfig normalisieren die URL`() {
        val store = FakeKeyValueStore()

        assertNull(getSyncConfig(store))

        setSyncConfig(
            store,
            SyncConfig(url = "  https://sync.example.com/// ", token = "  secret  "),
        )

        val config = getSyncConfig(store)
        assertEquals("https://sync.example.com", config?.url)
        assertEquals("secret", config?.token)

        setSyncConfig(store, null)
        assertNull(getSyncConfig(store))
    }

    @Test
    fun `syncRides pusht fehlende lokale und pullt fehlende remote Touren`() {
        val store = FakeKeyValueStore(
            mapOf(
                "trailscape.sync" to """{"url":"https://sync.example.com","token":"secret"}""",
            ),
        )

        val pushedBodies = mutableListOf<String>()

        val client = HttpClient { request ->
            assertEquals("Bearer secret", request.headers["Authorization"])

            when {
                request.method == HttpMethod.GET && request.url == "https://sync.example.com/api/rides" -> {
                    HttpResponse(
                        200,
                        """
                        [
                          {"id": "shared", "name": "Gemeinsame Tour", "createdAt": 1000},
                          {"id": "remote-only", "name": "Nur remote", "createdAt": 2000}
                        ]
                        """,
                    )
                }
                request.method == HttpMethod.PUT && request.url == "https://sync.example.com/api/rides/local-only" -> {
                    pushedBodies.add(request.body ?: "")
                    HttpResponse(200, "")
                }
                request.method == HttpMethod.GET && request.url == "https://sync.example.com/api/rides/remote-only" -> {
                    HttpResponse(
                        200,
                        """
                        {
                          "id": "remote-only",
                          "name": "Nur remote",
                          "createdAt": 2000,
                          "points": [],
                          "stats": {"distanceKm": 0, "ascentM": 0, "descentM": 0}
                        }
                        """,
                    )
                }
                else -> throw AssertionError("unerwarteter Request: ${request.method} ${request.url}")
            }
        }

        val sharedRide = Ride(
            id = "shared",
            name = "Gemeinsame Tour",
            createdAt = 1000,
            points = emptyList(),
            stats = RideStats(distanceKm = 0.0, ascentM = 0.0, descentM = 0.0),
        )
        val localOnlyRide = Ride(
            id = "local-only",
            name = "Nur lokal",
            createdAt = 3000,
            points = emptyList(),
            stats = RideStats(distanceKm = 0.0, ascentM = 0.0, descentM = 0.0),
        )

        val saved = mutableListOf<Ride>()

        val result = syncRides(
            listLocal = { listOf(sharedRide, localOnlyRide) },
            saveLocal = { saved.add(it) },
            client = client,
            store = store,
        )

        assertEquals(1, result.pushed)
        assertEquals(1, result.pulled)
        assertEquals(3, result.total)
        assertEquals(1, pushedBodies.size)
        val pushedBody = Json.parseToJsonElement(pushedBodies.single()) as JsonObject
        assertEquals("local-only", (pushedBody["id"] as JsonPrimitive).content)
        assertEquals(1, saved.size)
        assertEquals("remote-only", saved.single().id)
    }

    @Test
    fun `syncRides wirft bei 401`() {
        val store = FakeKeyValueStore(
            mapOf(
                "trailscape.sync" to """{"url":"https://sync.example.com","token":"falsch"}""",
            ),
        )

        val client = HttpClient { HttpResponse(401, "nope") }

        val e = assertFailsWith<Exception> {
            syncRides(listLocal = { emptyList() }, saveLocal = {}, client = client, store = store)
        }
        assertEquals("Token wird vom Server abgelehnt.", e.message)
    }

    @Test
    fun `syncRides wirft bei Netzwerkfehler`() {
        val store = FakeKeyValueStore(
            mapOf(
                "trailscape.sync" to """{"url":"https://sync.example.com","token":"secret"}""",
            ),
        )

        val client = HttpClient { throw RuntimeException("Netzwerkfehler") }

        val e = assertFailsWith<Exception> {
            syncRides(listLocal = { emptyList() }, saveLocal = {}, client = client, store = store)
        }
        assertEquals("Sync-Server nicht erreichbar.", e.message)
    }

    @Test
    fun `syncRides wirft, wenn nicht konfiguriert`() {
        val store = FakeKeyValueStore()

        val e = assertFailsWith<Exception> {
            syncRides(listLocal = { emptyList() }, saveLocal = {}, client = failingClient(), store = store)
        }
        assertEquals("Sync ist nicht konfiguriert.", e.message)
    }
}
