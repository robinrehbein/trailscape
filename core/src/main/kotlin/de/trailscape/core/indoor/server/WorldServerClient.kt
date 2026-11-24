package de.trailscape.core.indoor.server

import de.trailscape.core.HttpClient
import de.trailscape.core.HttpMethod
import de.trailscape.core.HttpRequest
import de.trailscape.core.HttpResponse
import de.trailscape.core.SyncConfig
import de.trailscape.core.indoor.world.worldBuilderGeneration
import de.trailscape.core.indoor.world.worldBuilderVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * # Client des Bau-Dienstes (docs/indoor/serverbau.md)
 *
 * Spricht mit dem eigenen Selfhost-Server (dieselbe Adresse und derselbe
 * Token wie der Sync, [SyncConfig]) unter `<url>/api/worlds/…`:
 *
 * | Aufruf | Zweck |
 * |---|---|
 * | `GET /api/worlds/info` | Bietet der Server den Bau an? Welche Welt-Version? |
 * | `POST /api/worlds/jobs` | Auftrag anlegen (oder vorhandenen gleichen Auftrag zurueck) |
 * | `GET /api/worlds/jobs/<id>` | Stand: Warteschlange, Bau, fertig, Fehler |
 * | `GET /api/worlds/jobs/<id>/package` | das Paket (Range-faehig) — laedt die App selbst, binaer |
 * | `DELETE /api/worlds/jobs/<id>` | abbrechen bzw. Paket loeschen |
 *
 * Rein, ueber [HttpClient]; die Oberflaeche bekommt nur Typen ([ServerCall]),
 * keine Saetze. Blockierend (Aufrufer auf `Dispatchers.IO`).
 */
class WorldServerClient(private val http: HttpClient, private val config: SyncConfig) {
    private val base: String = config.url.trim().trimEnd('/') + "/api/worlds"

    /** Adresse des Pakets eines Auftrags (fuer den Download der App). */
    fun packageUrl(jobId: String): String = "$base/jobs/${checkedId(jobId)}/package"

    /** Kopfzeilen fuer jeden Aufruf (auch den Download). */
    fun authHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer ${config.token.trim()}")

    fun info(): ServerCall<ServerWorldInfo> = call(HttpMethod.GET, "$base/info", noServiceOn404 = true) { body ->
        ServerWorldInfo.parse(body)
    }.let { r ->
        // Andere Welt-Version: der Dienst ist da, aber das Paket passte nicht zur App.
        if (r is ServerCall.Ok && r.value.builderId != worldBuilderVersion) ServerCall.Incompatible(r.value.builderId) else r
    }

    fun submit(request: ServerWorldRequest): ServerCall<ServerJobStatus> =
        call(HttpMethod.POST, "$base/jobs", body = request.toJson()) { ServerJobStatus.parse(it) }

    fun status(jobId: String): ServerCall<ServerJobStatus> =
        call(HttpMethod.GET, "$base/jobs/${checkedId(jobId)}") { ServerJobStatus.parse(it) }

    /** Abbrechen bzw. loeschen; „schon weg" (404) gilt als Erfolg. */
    fun delete(jobId: String): ServerCall<Unit> =
        call(HttpMethod.DELETE, "$base/jobs/${checkedId(jobId)}", okOn404 = true) { }

    private fun <T> call(
        method: HttpMethod,
        url: String,
        body: String? = null,
        noServiceOn404: Boolean = false,
        okOn404: Boolean = false,
        parse: (String) -> T,
    ): ServerCall<T> {
        val headers = authHeaders() + (if (body != null) mapOf("Content-Type" to "application/json") else emptyMap())
        val response: HttpResponse = try {
            http.execute(HttpRequest(method, url, headers, body))
        } catch (e: Exception) {
            return ServerCall.Unreachable(e.toString())
        }
        val code = response.statusCode
        if (code in 200..299 || (okOn404 && code == 404)) {
            return try {
                ServerCall.Ok(parse(response.body))
            } catch (e: Exception) {
                ServerCall.Failed(code, "Antwort unlesbar: ${e.message}")
            }
        }
        val error = errorOf(response.body)
        return when {
            code == 401 || code == 403 -> ServerCall.Unauthorized
            code == 429 && error?.code == null -> ServerCall.Failed(code, error?.detail ?: "zu viele Fehlversuche")
            code == 404 && noServiceOn404 -> ServerCall.NoService
            code == 404 -> ServerCall.NotFound
            error?.code == "BUILDER_MISMATCH" -> ServerCall.Incompatible(error.serverBuilderId ?: "")
            error?.code != null && code in 400..499 -> ServerCall.Rejected(error.code, error.detail)
            code == 503 && error?.code != null -> ServerCall.Rejected(error.code, error.detail)
            else -> ServerCall.Failed(code, error?.detail)
        }
    }

    private class ErrorBody(val code: String?, val detail: String?, val serverBuilderId: String?)

    private fun errorOf(body: String): ErrorBody? = runCatching {
        val o = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val e = o["error"]
        when (e) {
            is JsonObject -> ErrorBody(e.s("code"), e.s("detail"), e.s("serverBuilderId"))
            is JsonPrimitive -> ErrorBody(null, e.contentOrNull, null)
            else -> null
        }
    }.getOrNull()

    private fun checkedId(id: String): String {
        require(JOB_ID.matches(id)) { "unzulaessige Auftragskennung" }
        return id
    }

    companion object {
        val JOB_ID = Regex("^[a-f0-9]{16,64}$")
    }
}

/** Ergebnis eines Aufrufs. */
sealed interface ServerCall<out T> {
    data class Ok<T>(val value: T) : ServerCall<T>

    /** Token abgelehnt. */
    data object Unauthorized : ServerCall<Nothing>

    /** Der Server kennt `/api/worlds` nicht (nur Sync, kein Bau-Dienst). */
    data object NoService : ServerCall<Nothing>

    /** Den Auftrag gibt es nicht (mehr) — z. B. aufgeraeumt. */
    data object NotFound : ServerCall<Nothing>

    /** Server spricht eine andere Welt-Version ([serverBuilderId]); siehe [compareBuilder]. */
    data class Incompatible(val serverBuilderId: String) : ServerCall<Nothing>

    /** Auftrag abgewiesen: `TOO_LONG`, `TOO_MANY_POINTS`, `QUEUE_FULL`, `INVALID`, `TOO_LARGE`, … */
    data class Rejected(val code: String, val detail: String?) : ServerCall<Nothing>

    /** Kein Netz, Server aus, Zeitueberschreitung. */
    data class Unreachable(val detail: String) : ServerCall<Nothing>

    data class Failed(val status: Int, val detail: String?) : ServerCall<Nothing>
}

/** Wer muss aktualisiert werden, wenn die Welt-Versionen nicht passen? */
enum class BuilderMismatch { UPDATE_SERVER, UPDATE_APP, UNKNOWN }

fun compareBuilder(serverBuilderId: String, appBuilderId: String = worldBuilderVersion): BuilderMismatch {
    val s = worldBuilderGeneration(serverBuilderId) ?: return BuilderMismatch.UNKNOWN
    val a = worldBuilderGeneration(appBuilderId) ?: return BuilderMismatch.UNKNOWN
    return when {
        s < a -> BuilderMismatch.UPDATE_SERVER
        s > a -> BuilderMismatch.UPDATE_APP
        else -> BuilderMismatch.UNKNOWN
    }
}

/** Antwort von `GET /api/worlds/info`. */
data class ServerWorldInfo(
    val builderId: String,
    val laserSources: List<String>,
    val maxLengthM: Double,
    val maxPoints: Int,
    val queueLength: Int,
) {
    companion object {
        fun parse(text: String): ServerWorldInfo {
            val o = Json.parseToJsonElement(text) as JsonObject
            require(o.s("service") == SERVICE) { "kein Bau-Dienst" }
            return ServerWorldInfo(
                builderId = o.s("builderId") ?: error("builderId fehlt"),
                laserSources = (o["laserSources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                maxLengthM = o.d("maxLengthM") ?: ServerRouteLimits().maxLengthM,
                maxPoints = o.i("maxPoints") ?: ServerRouteLimits().maxPoints,
                queueLength = o.i("queueLength") ?: 0,
            )
        }

        const val SERVICE = "trailscape-worlds"
    }
}

/** Zustand eines Auftrags auf dem Server. */
enum class ServerJobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/**
 * Antwort von `POST /api/worlds/jobs` und `GET /api/worlds/jobs/<id>`.
 *
 * @property queuePosition 1 = als Naechstes dran (nur [ServerJobState.QUEUED]).
 * @property phase Name einer `WorldBuildPhase`, solange der Bau laeuft.
 * @property laserSource Kurzname der Laser-Quelle, die der Bau tatsaechlich
 *   benutzt hat (`nw`, `bw`), `null` = ohne Laser gebaut oder noch unbekannt.
 * @property errorCode bei [ServerJobState.FAILED]: `BUILD_FAILED`, `TIMEOUT`,
 *   `WORLD_ID_MISMATCH`, `CHECK_FAILED`, …
 */
data class ServerJobStatus(
    val id: String,
    val state: ServerJobState,
    val queuePosition: Int = 0,
    val phase: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val fraction: Double = 0.0,
    val worldId: String? = null,
    val packageBytes: Long = 0,
    val sha256: String? = null,
    val laserSource: String? = null,
    val errorCode: String? = null,
    val errorDetail: String? = null,
) {
    companion object {
        fun parse(text: String): ServerJobStatus {
            val o = Json.parseToJsonElement(text) as JsonObject
            val id = o.s("id") ?: error("id fehlt")
            require(WorldServerClient.JOB_ID.matches(id)) { "id unzulaessig" }
            val err = o["error"] as? JsonObject
            return ServerJobStatus(
                id = id,
                state = ServerJobState.entries.firstOrNull { it.name == o.s("state") } ?: error("state unbekannt"),
                queuePosition = o.i("queuePosition") ?: 0,
                phase = o.s("phase"),
                done = o.i("done") ?: 0,
                total = o.i("total") ?: 0,
                fraction = (o.d("fraction") ?: 0.0).coerceIn(0.0, 1.0),
                worldId = o.s("worldId"),
                packageBytes = o.l("packageBytes") ?: 0L,
                sha256 = o.s("sha256")?.lowercase()?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) },
                laserSource = o.s("laserSource"),
                errorCode = err?.s("code"),
                errorDetail = err?.s("detail"),
            )
        }
    }
}

/**
 * Ein laufender Auftrag, den die App sich merkt (Fortsetzen nach Neustart).
 * Ohne Token: der liegt nur in der Sync-Einstellung.
 */
data class ServerJobRecord(
    val jobId: String,
    val rideId: String,
    val worldId: String,
    val routeName: String,
    val serverUrl: String,
    val createdAtMs: Long,
) {
    fun toJson(): String = buildJsonObject {
        put("jobId", jobId)
        put("rideId", rideId)
        put("worldId", worldId)
        put("routeName", routeName)
        put("serverUrl", serverUrl)
        put("createdAtMs", createdAtMs)
    }.toString()

    companion object {
        fun parseOrNull(text: String?): ServerJobRecord? = runCatching {
            val o = Json.parseToJsonElement(text ?: return null) as JsonObject
            ServerJobRecord(
                jobId = o.s("jobId")!!.also { require(WorldServerClient.JOB_ID.matches(it)) },
                rideId = o.s("rideId")!!,
                worldId = o.s("worldId")!!,
                routeName = o.s("routeName").orEmpty(),
                serverUrl = o.s("serverUrl")!!,
                createdAtMs = o.l("createdAtMs") ?: 0L,
            )
        }.getOrNull()
    }
}

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
