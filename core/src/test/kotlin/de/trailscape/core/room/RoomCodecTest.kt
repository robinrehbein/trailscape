package de.trailscape.core.room

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Codec gegen die gemeinsamen Beispiele `server/test/fixtures/raum-v1.json`
 * (dieselbe Datei prüft der Server in `server/test/protocol-fixtures.test.mjs`).
 */
class RoomCodecTest {
    private val fixtures: JsonObject = Json.parseToJsonElement(
        File("../server/test/fixtures/raum-v1.json").readText(),
    ).jsonObject

    private fun server(group: String, name: String): String =
        fixtures["server"]!!.jsonObject[group]!!.jsonObject[name]!!.toString()

    private fun client(name: String): JsonObject = fixtures["client"]!!.jsonObject[name]!!.jsonObject

    @Test
    fun `aktuelles welcome wird vollstaendig gelesen`() {
        val w = assertIs<ServerMessage.Welcome>(RoomCodec.decodeServer(server("aktuell", "welcome")))
        assertEquals(1, w.v)
        assertEquals(1_764_072_000_000L, w.serverTime)
        assertEquals("Rdr_0001", w.you.id)
        assertEquals("rejoin-key-rejoin-key-x", w.you.rejoinKey)
        assertFalse(w.you.host)
        assertEquals(RoomRoute("katalog/eifel", 12345.6, true, "3", "Eifel-Runde"), w.room.route)
        assertNull(w.room.startAt)
        assertEquals(2, w.riders.size)
        assertEquals(JsonPrimitive("#d33"), (w.riders[0].avatar as JsonObject)["jersey"])
        assertTrue(w.riders[1].host)
        assertFalse(w.riders[1].sharePower)
        assertEquals(listOf(RiderStateEntry("Rdr_0002", 1_764_071_999_750L, 50.5, 7.1, null, 85, null)), w.states)
        assertEquals(10, w.limits.maxRidersPerRoom)
        assertEquals(30, w.limits.rejoinGraceS)
    }

    @Test
    fun `alle aktuellen Server-Nachrichten werden gelesen`() {
        assertEquals(
            ServerMessage.Rider(RiderInfo("Rdr_0003", "Cleo", host = false, sharePower = true)),
            RoomCodec.decodeServer(server("aktuell", "rider")),
        )
        assertEquals(ServerMessage.Left("Rdr_0003", "timeout"), RoomCodec.decodeServer(server("aktuell", "left")))
        assertEquals(
            ServerMessage.Snapshot(1_764_072_000_250L, listOf(RiderStateEntry("Rdr_0002", 1_764_072_000_100L, 52.3, 7.2, 180, 86, 1))),
            RoomCodec.decodeServer(server("aktuell", "snapshot")),
        )
        assertEquals(ServerMessage.Countdown(1_764_072_030_000L, 1_764_072_000_000L), RoomCodec.decodeServer(server("aktuell", "countdown")))
        assertEquals(ServerMessage.Ended("host", 1_764_075_600_000L), RoomCodec.decodeServer(server("aktuell", "ended")))
        assertEquals(ServerMessage.Pong(987_654_321L, 1_764_072_000_123L), RoomCodec.decodeServer(server("aktuell", "pong")))
        assertEquals(
            ServerMessage.Error("VERSION", "Protokollversion nicht unterstuetzt", listOf(1)),
            RoomCodec.decodeServer(server("aktuell", "error")),
        )
    }

    @Test
    fun `alte Mindestform ohne optionale Felder bekommt Vorgaben`() {
        val w = assertIs<ServerMessage.Welcome>(RoomCodec.decodeServer(server("alt", "welcome")))
        assertFalse(w.you.host)
        assertFalse(w.room.route.loop)
        assertNull(w.room.route.version)
        assertEquals(10, w.room.maxRiders)
        assertEquals(RoomLimits(), w.limits)
        assertEquals(emptyList(), w.states)
        assertTrue(w.riders.single().sharePower)
        assertNull(w.riders.single().avatar)
        val s = assertIs<ServerMessage.Snapshot>(RoomCodec.decodeServer(server("alt", "snapshot")))
        assertNull(s.states.single().powerW)
        assertEquals(ServerMessage.Ended("unknown", null), RoomCodec.decodeServer(server("alt", "ended")))
    }

    @Test
    fun `neuer Server mit mehr Feldern und Typen wird toleriert`() {
        val w = assertIs<ServerMessage.Welcome>(RoomCodec.decodeServer(server("neu", "welcome")))
        // Kaputte Einträge fallen einzeln weg, der Rest bleibt.
        assertEquals(listOf("Rdr_0001"), w.riders.map { it.id })
        assertEquals(12, w.limits.maxRidersPerRoom)
        val s = assertIs<ServerMessage.Snapshot>(RoomCodec.decodeServer(server("neu", "snapshot")))
        // "52.3" als String und negative Strecke sind ungültig.
        assertEquals(listOf("Rdr_0002"), s.states.map { it.id })
        assertEquals(ServerMessage.Unknown("emote"), RoomCodec.decodeServer(server("neu", "unbekannt")))
    }

    @Test
    fun `kaputte Nachrichten ergeben null statt Ausnahme`() {
        val broken = fixtures["server"]!!.jsonObject["kaputt"] as JsonArray
        for (e in broken) {
            val text = (e as JsonPrimitive).content
            assertNull(RoomCodec.decodeServer(text), text)
        }
        assertNull(RoomCodec.decodeServer("{\"type\":\"snapshot\",\"serverTime\":1e400}"))
        assertNull(RoomCodec.decodeServer("{\"type\":\"pong\",\"s\":1.0E300}"))
    }

    @Test
    fun `Geraete-Nachrichten entsprechen den Beispielen`() {
        val join = ClientMessage.Join(
            room = "AbCdEfGhIjKlMnOp",
            secret = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-x",
            name = "Anna",
            avatar = client("join")["avatar"],
            sharePower = true,
        )
        assertEquals(client("join"), parse(RoomCodec.encode(join)))
        val rejoin = ClientMessage.Join(
            room = "AbCdEfGhIjKlMnOp",
            secret = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-x",
            name = "Anna",
            sharePower = false,
            rejoin = ClientMessage.Rejoin("Rdr_0001", "rejoin-key-rejoin-key-x"),
        )
        assertEquals(client("joinRejoin"), parse(RoomCodec.encode(rejoin)))
        assertEquals(
            client("state"),
            parse(RoomCodec.encode(ClientMessage.State(1234.5612, 8.4249, powerW = 214.6, cadenceRpm = 88.2, lane = 0))),
        )
        assertEquals(client("ping"), parse(RoomCodec.encode(ClientMessage.Ping(987_654_321L))))
        assertEquals(client("settings"), parse(RoomCodec.encode(ClientMessage.Settings(sharePower = false))))
        assertEquals(client("leave"), parse(RoomCodec.encode(ClientMessage.Leave)))
        assertEquals(client("start"), parse(RoomCodec.encode(ClientMessage.Start(30))))
        assertEquals(client("end"), parse(RoomCodec.encode(ClientMessage.End)))
    }

    @Test
    fun `Leistung fehlt in der Nachricht, wenn sie nicht geteilt wird`() {
        val text = RoomCodec.encode(ClientMessage.State(10.0, 5.0, powerW = null, cadenceRpm = Double.NaN))
        assertFalse(text.contains("powerW"))
        assertFalse(text.contains("cadenceRpm"))
    }

    @Test
    fun `Geraete-Nachrichten lassen sich zurueck lesen`() {
        val all = listOf(
            ClientMessage.Join("AbCdEfGhIjKlMnOp", "x".repeat(43), "Anna", rejoin = ClientMessage.Rejoin("Rdr_0001", "k")),
            ClientMessage.State(1.5, 2.25, 100.0, 90.0, -1),
            ClientMessage.Ping(5L),
            ClientMessage.Settings(sharePower = true, name = "Neu"),
            ClientMessage.Leave,
            ClientMessage.Start(10),
            ClientMessage.End,
        )
        for (m in all) assertEquals(m, RoomCodec.decodeClient(RoomCodec.encode(m)), m.toString())
        assertNull(RoomCodec.decodeClient("{\"type\":\"state\",\"distM\":\"1\",\"speedMps\":1}"))
        assertNull(RoomCodec.decodeClient("{\"type\":\"was-neues\"}"))
    }

    @Test
    fun `Server-Nachrichten lassen sich zurueck lesen`() {
        val w = assertNotNull(RoomCodec.decodeServer(server("aktuell", "welcome")))
        assertEquals(w, RoomCodec.decodeServer(RoomCodec.encode(w)))
        for (name in listOf("rider", "left", "snapshot", "countdown", "ended", "pong", "error")) {
            val m = assertNotNull(RoomCodec.decodeServer(server("aktuell", name)))
            assertEquals(m, RoomCodec.decodeServer(RoomCodec.encode(m)), name)
        }
    }

    @Test
    fun `Geheimnisse stehen nicht in toString`() {
        val secret = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-x"
        val join = ClientMessage.Join("AbCdEfGhIjKlMnOp", secret, "Anna", rejoin = ClientMessage.Rejoin("Rdr_0001", "rejoin-key"))
        assertFalse(join.toString().contains(secret))
        assertFalse(join.toString().contains("rejoin-key"))
        val w = assertIs<ServerMessage.Welcome>(RoomCodec.decodeServer(server("aktuell", "welcome")))
        assertFalse(w.toString().contains("rejoin-key-rejoin-key-x"))
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
