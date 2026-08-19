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
        val loadedIds = mutableListOf<String>()
        val local = listOf(sharedRide, localOnlyRide)

        val result = syncRides(
            listLocal = { local.map { it.toLocalRideSummary() } },
            loadLocal = { id ->
                loadedIds.add(id)
                local.firstOrNull { it.id == id }
            },
            saveLocal = { saved.add(it) },
            client = client,
            store = store,
        )

        // Volltouren werden nur fuer den Push nachgeladen — nie der ganze
        // Bestand (die Entscheidung faellt allein ueber die Zusammenfassungen).
        assertEquals(listOf("local-only"), loadedIds)

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
            syncRides(
                listLocal = { emptyList() },
                loadLocal = { null },
                saveLocal = {},
                client = client,
                store = store,
            )
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
            syncRides(
                listLocal = { emptyList() },
                loadLocal = { null },
                saveLocal = {},
                client = client,
                store = store,
            )
        }
        assertEquals("Sync-Server nicht erreichbar.", e.message)
    }

    @Test
    fun `syncRides wirft, wenn nicht konfiguriert`() {
        val store = FakeKeyValueStore()

        val e = assertFailsWith<Exception> {
            syncRides(
                listLocal = { emptyList() },
                loadLocal = { null },
                saveLocal = {},
                client = failingClient(),
                store = store,
            )
        }
        assertEquals("Sync ist nicht konfiguriert.", e.message)
    }

    // -------------------------------------------------------------------------
    // planSync — die reine Entscheidungsfunktion
    // -------------------------------------------------------------------------

    /** Erwarteter [SyncPlan] mit leeren Defaults — haelt die Asserts kompakt. */
    private fun plan(
        pushNew: List<String> = emptyList(),
        pushUpdated: List<String> = emptyList(),
        pullNew: List<String> = emptyList(),
        pullUpdated: List<String> = emptyList(),
        deleteRemote: List<String> = emptyList(),
        deleteLocal: List<String> = emptyList(),
        tombstonesAfterSync: List<RideTombstone> = emptyList(),
    ) = SyncPlan(
        pushNew = pushNew,
        pushUpdated = pushUpdated,
        pullNew = pullNew,
        pullUpdated = pullUpdated,
        deleteRemote = deleteRemote,
        deleteLocal = deleteLocal,
        tombstonesAfterSync = tombstonesAfterSync,
    )

    @Test
    fun `planSync - nur lokal vorhandene Tour wird als neu gepusht`() {
        val result = planSync(
            local = listOf(LocalRideSummary("a", updatedAt = 100)),
            remote = emptyList(),
            tombstones = emptyList(),
        )
        assertEquals(plan(pushNew = listOf("a")), result)
    }

    @Test
    fun `planSync - nur remote vorhandene Tour wird als neu gepullt`() {
        val result = planSync(
            local = emptyList(),
            remote = listOf(RemoteRideSummary("a", name = "A", updatedAt = 100)),
            tombstones = emptyList(),
        )
        assertEquals(plan(pullNew = listOf("a")), result)
    }

    @Test
    fun `planSync - beidseitig gleiches updatedAt tut nichts`() {
        val result = planSync(
            local = listOf(LocalRideSummary("a", updatedAt = 100)),
            remote = listOf(RemoteRideSummary("a", name = "A", updatedAt = 100)),
            tombstones = emptyList(),
        )
        assertEquals(plan(), result)
    }

    @Test
    fun `planSync - lokal neuere Tour wird als Update gepusht`() {
        val result = planSync(
            local = listOf(LocalRideSummary("a", updatedAt = 200)),
            remote = listOf(RemoteRideSummary("a", name = "A", updatedAt = 100)),
            tombstones = emptyList(),
        )
        assertEquals(plan(pushUpdated = listOf("a")), result)
    }

    @Test
    fun `planSync - remote neuere Tour wird als Update gepullt`() {
        val result = planSync(
            local = listOf(LocalRideSummary("a", updatedAt = 100)),
            remote = listOf(RemoteRideSummary("a", name = "A", updatedAt = 200)),
            tombstones = emptyList(),
        )
        assertEquals(plan(pullUpdated = listOf("a")), result)
    }

    @Test
    fun `planSync - alter Server ohne updatedAt verhaelt sich wie bisher`() {
        // Beidseitig vorhanden, aber der Server liefert kein updatedAt: kein
        // Vergleich moeglich, also nichts tun (das alte Verhalten).
        val result = planSync(
            local = listOf(LocalRideSummary("a", updatedAt = 200)),
            remote = listOf(RemoteRideSummary("a", name = "A", updatedAt = null)),
            tombstones = emptyList(),
        )
        assertEquals(plan(), result)
    }

    @Test
    fun `planSync - lokaler Tombstone loescht die aeltere Remote-Tour und bleibt bestehen`() {
        val tombstone = RideTombstone("a", deletedAt = 300)
        val result = planSync(
            local = emptyList(),
            remote = listOf(RemoteRideSummary("a", name = "A", updatedAt = 100)),
            tombstones = listOf(tombstone),
        )
        assertEquals(
            plan(deleteRemote = listOf("a"), tombstonesAfterSync = listOf(tombstone)),
            result,
        )
    }

    @Test
    fun `planSync - lokaler Tombstone und remote bereits geloescht tut nichts, Tombstone bleibt`() {
        val tombstone = RideTombstone("a", deletedAt = 300)
        val result = planSync(
            local = emptyList(),
            remote = listOf(RemoteRideSummary("a", deleted = true, deletedAt = 250)),
            tombstones = listOf(tombstone),
        )
        assertEquals(plan(tombstonesAfterSync = listOf(tombstone)), result)
    }

    @Test
    fun `planSync - remote Tombstone loescht die aeltere lokale Tour und wird uebernommen`() {
        val result = planSync(
            local = listOf(LocalRideSummary("a", updatedAt = 100)),
            remote = listOf(RemoteRideSummary("a", deleted = true, deletedAt = 300)),
            tombstones = emptyList(),
        )
        // Der uebernommene Tombstone verhindert, dass die lokale Loeschung
        // beim naechsten Sync als eigene Loeschung erneut zum Server wandert.
        assertEquals(
            plan(
                deleteLocal = listOf("a"),
                tombstonesAfterSync = listOf(RideTombstone("a", deletedAt = 300)),
            ),
            result,
        )
    }

    @Test
    fun `planSync - Wiederbelebung, remote neuer als lokaler Tombstone wird gepullt`() {
        val result = planSync(
            local = emptyList(),
            remote = listOf(RemoteRideSummary("a", name = "A", updatedAt = 400)),
            tombstones = listOf(RideTombstone("a", deletedAt = 300)),
        )
        // Tombstone verfaellt: tombstonesAfterSync ist leer.
        assertEquals(plan(pullNew = listOf("a")), result)
    }

    @Test
    fun `planSync - Wiederbelebung, lokale Bearbeitung neuer als remote Tombstone wird gepusht`() {
        val result = planSync(
            local = listOf(LocalRideSummary("a", updatedAt = 400)),
            remote = listOf(RemoteRideSummary("a", deleted = true, deletedAt = 300)),
            tombstones = emptyList(),
        )
        assertEquals(plan(pushUpdated = listOf("a")), result)
    }

    @Test
    fun `planSync - hinfaelliger lokaler Tombstone unter einer neueren lokalen Datei verfaellt`() {
        // Die lokale Datei ist NEUER als der eigene Tombstone (Tour wurde nach
        // der Loeschung wieder angelegt): normale Regeln gelten, der
        // Tombstone verschwindet aus dem Bestand.
        val result = planSync(
            local = listOf(LocalRideSummary("a", updatedAt = 500)),
            remote = emptyList(),
            tombstones = listOf(RideTombstone("a", deletedAt = 300)),
        )
        assertEquals(plan(pushNew = listOf("a")), result)
    }

    @Test
    fun `planSync - remote Tombstone ohne lokale Tour tut nichts`() {
        val result = planSync(
            local = emptyList(),
            remote = listOf(RemoteRideSummary("a", deleted = true, deletedAt = 300)),
            tombstones = emptyList(),
        )
        assertEquals(plan(), result)
    }

    @Test
    fun `planSync - remote Tombstone ohne deletedAt loescht im Zweifel nichts`() {
        val result = planSync(
            local = listOf(LocalRideSummary("a", updatedAt = 100)),
            remote = listOf(RemoteRideSummary("a", deleted = true, deletedAt = null)),
            tombstones = emptyList(),
        )
        assertEquals(plan(), result)
    }

    // -------------------------------------------------------------------------
    // Tombstone-Serialisierung
    // -------------------------------------------------------------------------

    @Test
    fun `Tombstone-Liste uebersteht den JSON-Roundtrip`() {
        val list = listOf(
            RideTombstone("a", deletedAt = 1700000000000L),
            RideTombstone("b-2", deletedAt = 1700000060000L),
        )

        assertEquals(list, tombstonesFromJsonString(tombstonesToJsonString(list)))
        assertEquals(emptyList(), tombstonesFromJsonString(tombstonesToJsonString(emptyList())))
    }

    @Test
    fun `tombstonesFromJsonString toleriert kaputtes JSON und falsche Formen`() {
        assertEquals(emptyList(), tombstonesFromJsonString("kein json"))
        assertEquals(emptyList(), tombstonesFromJsonString("""{"id":"a","deletedAt":1}"""))
        // Unlesbare Eintraege werden uebersprungen, lesbare bleiben.
        assertEquals(
            listOf(RideTombstone("a", deletedAt = 1)),
            tombstonesFromJsonString("""[{"id":"a","deletedAt":1},{"kaputt":true},42]"""),
        )
    }

    // -------------------------------------------------------------------------
    // syncRides — bidirektional mit Tombstones
    // -------------------------------------------------------------------------

    private fun ride(id: String, name: String = "Tour $id", createdAt: Long = 1000, updatedAt: Long = createdAt) =
        Ride(
            id = id,
            name = name,
            createdAt = createdAt,
            points = emptyList(),
            stats = RideStats(distanceKm = 0.0, ascentM = 0.0, descentM = 0.0),
            updatedAt = updatedAt,
        )

    @Test
    fun `syncRides propagiert Updates und Loeschungen in beide Richtungen`() {
        val store = FakeKeyValueStore(
            mapOf(
                "trailscape.sync" to """{"url":"https://sync.example.com","token":"secret"}""",
            ),
        )

        val pushedBodies = mutableListOf<String>()
        val deletedRemoteUrls = mutableListOf<String>()

        val client = HttpClient { request ->
            assertEquals("Bearer secret", request.headers["Authorization"])
            when {
                request.method == HttpMethod.GET && request.url == "https://sync.example.com/api/rides" -> {
                    HttpResponse(
                        200,
                        """
                        [
                          {"id": "remote-deleted", "name": "Weg", "deleted": true, "deletedAt": 5000},
                          {"id": "kill-me", "name": "Zombie", "createdAt": 1000, "updatedAt": 1000},
                          {"id": "newer-remote", "name": "Neu vom Server", "createdAt": 1000, "updatedAt": 9000},
                          {"id": "newer-local", "name": "Neu von hier", "createdAt": 1000, "updatedAt": 1000}
                        ]
                        """,
                    )
                }
                request.method == HttpMethod.PUT && request.url == "https://sync.example.com/api/rides/newer-local" -> {
                    pushedBodies.add(request.body ?: "")
                    HttpResponse(204, "")
                }
                request.method == HttpMethod.GET && request.url == "https://sync.example.com/api/rides/newer-remote" -> {
                    HttpResponse(
                        200,
                        """
                        {
                          "id": "newer-remote",
                          "name": "Neu vom Server",
                          "createdAt": 1000,
                          "points": [],
                          "stats": {"distanceKm": 0, "ascentM": 0, "descentM": 0},
                          "updatedAt": 9000
                        }
                        """,
                    )
                }
                request.method == HttpMethod.DELETE && request.url == "https://sync.example.com/api/rides/kill-me" -> {
                    deletedRemoteUrls.add(request.url)
                    HttpResponse(204, "")
                }
                else -> throw AssertionError("unerwarteter Request: ${request.method} ${request.url}")
            }
        }

        val localRides = listOf(
            // Aelter als der Server-Tombstone (deletedAt 5000) -> lokal loeschen.
            ride("remote-deleted", updatedAt = 4000),
            // Server hat die neuere Fassung -> pullen.
            ride("newer-remote", updatedAt = 1000),
            // Lokal ist die neuere Fassung -> pushen.
            ride("newer-local", updatedAt = 9000),
        )
        val tombstones = listOf(RideTombstone("kill-me", deletedAt = 2000))

        val saved = mutableListOf<Ride>()
        val deletedLocal = mutableListOf<String>()
        var tombstonesAfter: List<RideTombstone>? = null

        val result = syncRides(
            listLocal = { localRides.map { it.toLocalRideSummary() } },
            loadLocal = { id -> localRides.firstOrNull { it.id == id } },
            saveLocal = { saved.add(it) },
            client = client,
            store = store,
            deleteLocal = { deletedLocal.add(it) },
            listTombstones = { tombstones },
            replaceTombstones = { tombstonesAfter = it },
        )

        // Push: nur die lokal neuere Tour, mit updatedAt im Body.
        assertEquals(1, pushedBodies.size)
        val pushedBody = Json.parseToJsonElement(pushedBodies.single()) as JsonObject
        assertEquals("newer-local", (pushedBody["id"] as JsonPrimitive).content)
        assertEquals("9000", (pushedBody["updatedAt"] as JsonPrimitive).content)

        // Pull: die remote neuere Fassung, mit Server-updatedAt.
        assertEquals(1, saved.size)
        assertEquals("newer-remote", saved.single().id)
        assertEquals(9000L, saved.single().updatedAt)

        // Loeschungen in beide Richtungen.
        assertEquals(listOf("https://sync.example.com/api/rides/kill-me"), deletedRemoteUrls)
        assertEquals(listOf("remote-deleted"), deletedLocal)

        // Tombstone-Bestand danach: der eigene bleibt, der remote kommt dazu.
        assertEquals(
            listOf(
                RideTombstone("remote-deleted", deletedAt = 5000),
                RideTombstone("kill-me", deletedAt = 2000),
            ).sortedBy { it.id },
            tombstonesAfter!!.sortedBy { it.id },
        )

        assertEquals(1, result.pushed)
        assertEquals(1, result.pulled)
        assertEquals(2, result.updated)
        assertEquals(1, result.deletedLocal)
        assertEquals(1, result.deletedRemote)
        // 3 lokale - 1 geloescht + 0 neu gepullt.
        assertEquals(2, result.total)
    }

    @Test
    fun `syncRides toleriert 404 beim Loeschen auf dem Server`() {
        val store = FakeKeyValueStore(
            mapOf(
                "trailscape.sync" to """{"url":"https://sync.example.com","token":"secret"}""",
            ),
        )

        val client = HttpClient { request ->
            when {
                request.method == HttpMethod.GET && request.url == "https://sync.example.com/api/rides" ->
                    HttpResponse(200, """[{"id":"a","name":"A","createdAt":1,"updatedAt":1}]""")
                request.method == HttpMethod.DELETE && request.url == "https://sync.example.com/api/rides/a" ->
                    HttpResponse(404, """{"error":"Nicht gefunden"}""")
                else -> throw AssertionError("unerwarteter Request: ${request.method} ${request.url}")
            }
        }

        val result = syncRides(
            listLocal = { emptyList() },
            loadLocal = { throw AssertionError("nichts zu pushen") },
            saveLocal = { throw AssertionError("nichts zu pullen") },
            client = client,
            store = store,
            listTombstones = { listOf(RideTombstone("a", deletedAt = 100)) },
        )

        assertEquals(1, result.deletedRemote)
        assertEquals(0, result.pushed)
        assertEquals(0, result.pulled)
    }
}
