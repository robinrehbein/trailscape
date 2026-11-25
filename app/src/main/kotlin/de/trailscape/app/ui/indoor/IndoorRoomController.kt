package de.trailscape.app.ui.indoor

import de.trailscape.app.challenge.ChallengeAvailability
import de.trailscape.app.challenge.ChallengeRouteInfo
import de.trailscape.app.room.CloseReason
import de.trailscape.app.room.ExecutorRoomScheduler
import de.trailscape.app.room.OkHttpRoomTransport
import de.trailscape.app.room.ReconnectingRoomClient
import de.trailscape.app.room.RoomClient
import de.trailscape.app.room.RoomConnectionStatus
import de.trailscape.app.room.RoomJoinParams
import de.trailscape.core.HttpClient
import de.trailscape.core.KeyValueStore
import de.trailscape.core.SyncConfig
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.challenge.routeOfKey
import de.trailscape.core.room.ClientMessage
import de.trailscape.core.room.RoomApi
import de.trailscape.core.room.RoomCreateResult
import de.trailscape.core.room.RoomInvite
import de.trailscape.core.room.RoomRoute
import de.trailscape.core.room.RoomState
import de.trailscape.core.room.SelfPosition
import de.trailscape.core.room.ServerMessage
import java.net.URI
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Die eigene Stelle aus der laufenden Indoor-Fahrt — nur während einer Fahrt
 * auf der Strecke des Raums, sonst liefert die Quelle `null`.
 *
 * @property distM Laufmeter seit dem Start (über Runden fortlaufend).
 * @property held die Fahrt wartet noch am Start.
 */
data class RoomSelfState(
    val distM: Double,
    val speedMps: Double,
    val powerW: Int?,
    val held: Boolean,
)

/**
 * Was der Raum vom Rest der App braucht — im Betrieb aus [of], in Tests Attrappen.
 *
 * @property newClient baut einen frischen Client je Raum mit der monotonen Uhr des Ablaufs.
 * @property createRoom `POST /api/rooms` auf dem eigenen Server (blockierend).
 * @property syncConfig der Eintrag aus Einstellungen → Sync mit eigenem Server.
 * @property autoTick `false` = Tests treiben den Takt selbst.
 */
class IndoorRoomDeps(
    val newClient: (clock: () -> Long) -> RoomClient,
    val createRoom: (serverUrl: String, token: String, route: RoomRoute) -> RoomCreateResult,
    val syncConfig: () -> SyncConfig?,
    val autoTick: Boolean = true,
) {
    companion object {
        /**
         * Betrieb: WebSocket über OkHttp, Anlegen über den [HttpClient] der App.
         * Transport und Zeitgeber entstehen erst mit dem **ersten Raum** — ohne
         * Raum gibt es weder Verbindung noch Thread.
         */
        fun of(http: () -> HttpClient, syncConfig: () -> SyncConfig?): IndoorRoomDeps {
            val transport by lazy { OkHttpRoomTransport() }
            val scheduler by lazy { ExecutorRoomScheduler() }
            return IndoorRoomDeps(
                newClient = { clock -> ReconnectingRoomClient(transport = transport, scheduler = scheduler, clientClock = clock) },
                createRoom = { serverUrl, token, route -> RoomApi(http()).create(serverUrl, token, route) },
                syncConfig = syncConfig,
            )
        }
    }
}

/**
 * # Der Raum für Gruppenfahrten im Indoor-Ablauf
 *
 * Zustandslogik zwischen dem Netz-Client ([RoomClient]) und der Oberfläche —
 * getrennt vom `IndoorViewModel` wie [IndoorChallengeController]. Der Zustand
 * des Raums selbst ist die reine Funktion `RoomState.reduce` aus `:core`;
 * hier kommen Anlegen, Beitreten, Verlassen, die Übersetzung von
 * Verbindungsstatus und Schließgründen in Sätze und der Takt dazu.
 *
 * ## Datenschutz (verbindlich, docs/raum-protokoll.md Abschnitt 14)
 *  * Eine Verbindung gibt es **nur, solange ein Raum offen ist**: [create]
 *    spricht den selbst eingetragenen Server an, [join] den Server aus dem
 *    Einladungslink, den der Nutzer eingefügt und mit sichtbarer Adresse
 *    bestätigt hat. Ohne Raum existiert kein [RoomClient].
 *  * Gesendet werden Name, Laufmeter auf der Strecke, Tempo und — nur wenn
 *    eingeschaltet — die Leistung. **Nicht** gesendet: Trittfrequenz, Puls,
 *    Spur, Aussehen (Avatar), Standort, Gerätekennungen.
 *  * Nichts vom Raum wird gespeichert: Einladung und Gastgeber-Geheimnis
 *    leben nur im Speicher. Gemerkt werden allein der eigene Anzeigename und
 *    die Wahl „Leistung zeigen".
 *
 * ## Threads
 * Alle Methoden laufen auf dem Thread von [scope] (Hauptthread). Nachrichten
 * des Clients kommen auf Netz-Threads und werden über [scope] hereingereicht;
 * deshalb braucht der Controller keine Sperre und ruft den Client nie aus
 * einem Rückruf des Clients heraus.
 *
 * @param newClient baut einen frischen Client je Raum (Betrieb: `ReconnectingRoomClient` über OkHttp).
 * @param createRoom `POST /api/rooms` (blockierend, läuft über [io]).
 * @param syncConfig der eigene Server aus den Einstellungen, `null` = keiner.
 * @param routeInfo ordnet eine Strecke dem Katalog zu (wie bei den Herausforderungen).
 * @param clientClock monotone Uhr des Geräts in ms — dieselbe, die der Client bekommt.
 * @param self die eigene Stelle, solange eine Fahrt auf der Strecke des Raums läuft.
 * @param onStart der gemeinsame Start ist da (oder der Raum ist weg): die gehaltene Fahrt freigeben.
 * @param autoTick `false` = kein eigener Takt; Tests rufen [tick] selbst.
 */
class IndoorRoomController(
    private val newClient: () -> RoomClient,
    private val createRoom: (serverUrl: String, token: String, route: RoomRoute) -> RoomCreateResult,
    private val syncConfig: () -> SyncConfig?,
    private val routeInfo: (ChallengeRoute) -> ChallengeRouteInfo,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val kv: KeyValueStore,
    private val clientClock: () -> Long,
    private val self: () -> RoomSelfState? = { null },
    private val onStart: () -> Unit = {},
    private val tickMs: Long = TICK_MS,
    private val autoTick: Boolean = true,
) {
    private enum class Phase { CLOSED, CREATING, CONNECTING, OPEN }

    private var phase = Phase.CLOSED
    private var client: RoomClient? = null
    private var invite: RoomInvite? = null
    private var joinedSharePower = false
    private var sharePower = false
    private var name = ""
    private var roomState = RoomState()
    private var status: RoomConnectionStatus = RoomConnectionStatus.Idle
    private var retryAtClientMs: Long? = null
    private var endingMyself = false
    private var statusJob: Job? = null
    private var tickJob: Job? = null

    /** Zählt Räume durch: verspätete Nachrichten eines alten Raums werden verworfen. */
    private var generation = 0

    private val _ui = MutableStateFlow<IndoorRoomUi>(IndoorRoomUi.Closed())

    /** Der Abschnitt „Raum" in Indoor → Gemeinsam. */
    val ui: StateFlow<IndoorRoomUi> = _ui.asStateFlow()

    private val _ride = MutableStateFlow<RoomRideUi?>(null)

    /** Die Einblendung in der Fahransicht; `null` = die Fahrt gehört zu keinem Raum. */
    val ride: StateFlow<RoomRideUi?> = _ride.asStateFlow()

    private val _server = MutableStateFlow(readServer())

    /** Der eigene Server (nur der Rechnername), neu gelesen mit [refreshServer]. */
    val server: StateFlow<RoomServerUi> = _server.asStateFlow()

    /** Liest die Sync-Einstellung neu (beim Öffnen der Sicht). Ohne Netz. */
    fun refreshServer() {
        _server.value = readServer()
    }

    private fun readServer(): RoomServerUi = RoomServerUi(
        host = runCatching { syncConfig() }.getOrNull()
            ?.takeIf { it.url.isNotBlank() && it.token.isNotBlank() }
            ?.let { RoomInvite.normalizeServerUrl(it.url) }
            ?.let { hostOf(it) },
    )

    /** Der zuletzt benutzte Anzeigename (sonst der vom Teilen einer Herausforderung), höchstens [NAME_MAX_CHARS]. */
    fun savedName(): String =
        (kv.getString(KEY_NAME) ?: kv.getString(IndoorChallengeController.KEY_NAME)).orEmpty().let { cleanName(it) }

    /** Die zuletzt gewählte Einstellung „Leistung zeigen" (Vorgabe: aus). */
    fun savedSharePower(): Boolean = kv.getString(KEY_SHARE_POWER) == "1"

    /** Schlüssel der Strecke des offenen Raums (`IndoorRouteOption.key`), sonst `null`. */
    fun openRouteKey(): String? = if (phase == Phase.OPEN) roomState.room?.route?.id else null

    // -----------------------------------------------------------------------
    // Anlegen, Beitreten, Verlassen
    // -----------------------------------------------------------------------

    /**
     * „Raum anlegen" auf dem eigenen Server mit [route]; danach Beitritt als
     * Gastgeber. Ohne eingetragenen Server oder mit offenem Raum geschieht nichts.
     */
    fun create(route: RoomRoute, name: String, sharePower: Boolean) {
        if (phase != Phase.CLOSED) return
        val config = runCatching { syncConfig() }.getOrNull() ?: return
        val server = RoomInvite.normalizeServerUrl(config.url) ?: run {
            _ui.value = IndoorRoomUi.Closed(RoomNotice.CREATE_FAILED)
            return
        }
        val clean = cleanName(name)
        if (clean.isEmpty()) return
        remember(clean, sharePower)
        phase = Phase.CREATING
        val gen = ++generation
        _ui.value = IndoorRoomUi.Opening(creating = true, serverHost = hostOf(server))
        scope.launch {
            val result = withContext(io) {
                runCatching { createRoom(server, config.token, route) }
                    .getOrElse { RoomCreateResult.Failed(0, "NETWORK") }
            }
            // Inzwischen abgebrochen: Der Raum bleibt leer und baut sich auf dem Server von selbst ab.
            if (gen != generation || phase != Phase.CREATING) return@launch
            when (result) {
                is RoomCreateResult.Created -> connect(result.invite, clean, sharePower, result.hostSecret)
                RoomCreateResult.Unauthorized -> closeWith(RoomNotice.CREATE_UNAUTHORIZED)
                RoomCreateResult.Disabled -> closeWith(RoomNotice.CREATE_DISABLED)
                RoomCreateResult.ServerFull -> closeWith(RoomNotice.CREATE_SERVER_FULL)
                is RoomCreateResult.Failed ->
                    closeWith(if (result.code == "NETWORK") RoomNotice.CREATE_NETWORK else RoomNotice.CREATE_FAILED)
            }
        }
    }

    /**
     * „Raum beitreten" mit einem Einladungslink. `false` = der Text ist kein
     * gültiger Einladungslink (nichts geschieht, keine Verbindung).
     */
    fun join(link: String, name: String, sharePower: Boolean): Boolean {
        if (phase != Phase.CLOSED) return false
        val parsed = parseInvite(link) ?: return false
        val clean = cleanName(name)
        if (clean.isEmpty()) return false
        remember(clean, sharePower)
        ++generation
        connect(parsed, clean, sharePower, hostSecret = null)
        return true
    }

    private fun remember(name: String, sharePower: Boolean) {
        runCatching {
            kv.setString(KEY_NAME, name)
            kv.setString(KEY_SHARE_POWER, if (sharePower) "1" else "0")
        }
    }

    private fun connect(invite: RoomInvite, name: String, sharePower: Boolean, hostSecret: String?) {
        val gen = generation
        val c = newClient()
        client = c
        this.invite = invite
        this.name = name
        this.sharePower = sharePower
        joinedSharePower = sharePower
        roomState = RoomState()
        status = RoomConnectionStatus.Connecting(1)
        retryAtClientMs = null
        endingMyself = false
        phase = Phase.CONNECTING
        publish()
        // Nachrichten kommen auf einem Netz-Thread: über den Scope hereinreichen (Reihenfolge bleibt).
        c.setListener { message -> scope.launch { if (gen == generation) onMessage(message) } }
        statusJob = scope.launch { c.status.collect { if (gen == generation) onStatus(it) } }
        c.connect(RoomJoinParams(invite, name, avatar = null, sharePower = sharePower, hostSecret = hostSecret))
        if (autoTick) {
            tickJob = scope.launch {
                while (isActive) {
                    delay(tickMs)
                    tick()
                }
            }
        }
    }

    /** „Raum verlassen" (auch: Beitritt oder Anlegen abbrechen). Der Platz wird sofort frei. */
    fun leave() {
        if (phase == Phase.CLOSED) return
        client?.leave()
        closeWith(null)
    }

    /** Nur Gastgeber: „Raum beenden" — für alle. Die Bestätigung kommt vom Server. */
    fun endRoom() {
        if (phase != Phase.OPEN || !roomState.isHost) return
        endingMyself = client?.send(ClientMessage.End) == true
    }

    /** Nur Gastgeber: gemeinsamer Start in [inS] Sekunden. */
    fun startCountdown(inS: Int = COUNTDOWN_S) {
        if (phase != Phase.OPEN || !roomState.isHost) return
        client?.send(ClientMessage.Start(inS.coerceIn(3, 600)))
    }

    /** „Leistung zeigen" während der Raum offen ist. */
    fun setSharePower(enabled: Boolean) {
        sharePower = enabled
        runCatching { kv.setString(KEY_SHARE_POWER, if (enabled) "1" else "0") }
        if (phase == Phase.OPEN && client?.send(ClientMessage.Settings(sharePower = enabled)) == true) {
            joinedSharePower = enabled
        }
        publish()
    }

    /** Der Hinweis zum Ende des letzten Raums ist gelesen. */
    fun dismissNotice() {
        if (phase == Phase.CLOSED) _ui.value = IndoorRoomUi.Closed()
    }

    /** Die Fahrt ist zu Ende (Auswertung, Abbruch): Die Einblendung verschwindet, der Raum bleibt offen. */
    fun onRideEnded() {
        if (phase == Phase.OPEN) publish() else _ride.value = null
    }

    /** Der Ablauf wird abgebaut: Raum verlassen, ohne eine Fahrt freizugeben. */
    fun close() {
        if (phase == Phase.CLOSED) return
        client?.leave()
        closeWith(null, releaseHeldRide = false)
    }

    // -----------------------------------------------------------------------
    // Vom Client
    // -----------------------------------------------------------------------

    private fun onMessage(message: ServerMessage) {
        if (phase != Phase.CONNECTING && phase != Phase.OPEN) return
        roomState = roomState.reduce(message)
        if (message is ServerMessage.Welcome) {
            phase = Phase.OPEN
            // Der Client tritt nach einem Abbruch mit den Angaben des ersten Beitritts
            // wieder bei; eine inzwischen geänderte Wahl gleich nachreichen.
            if (sharePower != joinedSharePower && client?.send(ClientMessage.Settings(sharePower = sharePower)) == true) {
                joinedSharePower = sharePower
            }
        }
        publish()
    }

    private fun onStatus(next: RoomConnectionStatus) {
        when (next) {
            RoomConnectionStatus.Idle -> Unit
            is RoomConnectionStatus.Connecting -> {
                status = next
                retryAtClientMs = null
            }
            is RoomConnectionStatus.Connected -> {
                status = next
                retryAtClientMs = null
            }
            is RoomConnectionStatus.Reconnecting -> {
                status = next
                retryAtClientMs = clientClock() + next.inMs
            }
            is RoomConnectionStatus.Closed -> {
                closeWith(noticeOf(next.reason))
                return
            }
        }
        publish()
    }

    private fun noticeOf(reason: CloseReason): RoomNotice? = when (reason) {
        CloseReason.BY_USER, CloseReason.LEFT -> null
        // Nach einem Neustart des Servers kennt er den Raum nicht mehr (docs/raum-protokoll.md, 8).
        CloseReason.DENIED -> if (phase == Phase.OPEN) RoomNotice.ENDED_SERVER else RoomNotice.DENIED
        CloseReason.ROOM_FULL -> RoomNotice.FULL
        CloseReason.VERSION -> RoomNotice.VERSION
        CloseReason.ENDED -> when {
            endingMyself -> null
            roomState.endedReason == "host" -> RoomNotice.ENDED_BY_HOST
            roomState.endedReason == "expired" -> RoomNotice.ENDED_EXPIRED
            roomState.endedReason == "idle" -> RoomNotice.ENDED_IDLE
            else -> RoomNotice.ENDED_SERVER
        }
        CloseReason.REPLACED -> RoomNotice.REPLACED
        CloseReason.BLOCKED -> RoomNotice.BLOCKED
        CloseReason.PROTOCOL -> RoomNotice.PROTOCOL
        CloseReason.GAVE_UP -> RoomNotice.GAVE_UP
    }

    private fun closeWith(notice: RoomNotice?, releaseHeldRide: Boolean = true) {
        if (phase == Phase.CLOSED) return
        val me = runCatching { self() }.getOrNull()
        ++generation
        phase = Phase.CLOSED
        statusJob?.cancel()
        statusJob = null
        tickJob?.cancel()
        tickJob = null
        client?.setListener(null)
        client = null
        invite = null
        roomState = RoomState()
        status = RoomConnectionStatus.Idle
        retryAtClientMs = null
        endingMyself = false
        _ui.value = IndoorRoomUi.Closed(notice)
        // Wer gerade fährt, sieht noch, dass der Raum weg ist — bis zum Ende der Fahrt.
        _ride.value = if (me != null) {
            RoomRideUi(RoomConnectionUi.Online, waiting = false, countdownS = null, host = false, atStart = 0, total = 0, riders = emptyList(), closed = true)
        } else {
            null
        }
        // Niemand soll am Start festhängen, weil der Raum verschwunden ist.
        if (releaseHeldRide && me?.held == true) onStart()
    }

    // -----------------------------------------------------------------------
    // Takt: eigene Stelle senden, Start auslösen, Anzeige fortschreiben
    // -----------------------------------------------------------------------

    /** Ein Takt (im Betrieb alle [tickMs]). */
    fun tick() {
        val c = client ?: return
        if (phase != Phase.OPEN) {
            publish()
            return
        }
        val me = runCatching { self() }.getOrNull()
        if (me != null && status is RoomConnectionStatus.Connected) {
            c.send(
                ClientMessage.State(
                    distM = me.distM.coerceAtLeast(0.0),
                    speedMps = me.speedMps.coerceIn(0.0, MAX_SPEED_MPS),
                    powerW = me.powerW?.takeIf { sharePower }?.toDouble(),
                ),
            )
        }
        val now = c.serverTimeMs(clientClock())
        val startAt = roomState.startAt
        if (me != null && me.held && startAt != null && now != null && now >= startAt) onStart()
        publish(me, now)
    }

    private fun publish(
        me: RoomSelfState? = runCatching { self() }.getOrNull(),
        nowServerMs: Long? = client?.serverTimeMs(clientClock()),
    ) {
        val inv = invite
        when (phase) {
            Phase.CLOSED, Phase.CREATING -> return
            Phase.CONNECTING -> {
                val attempt = when (val s = status) {
                    is RoomConnectionStatus.Connecting -> s.attempt
                    is RoomConnectionStatus.Reconnecting -> s.attempt
                    RoomConnectionStatus.Idle, is RoomConnectionStatus.Connected, is RoomConnectionStatus.Closed -> 1
                }
                _ui.value = IndoorRoomUi.Opening(creating = false, serverHost = hostOf(inv?.serverUrl.orEmpty()), attempt = attempt)
                _ride.value = null
                return
            }
            Phase.OPEN -> Unit
        }
        val room = roomState.room ?: return
        if (inv == null) return
        val startAt = roomState.startAt
        val started = startAt != null && nowServerMs != null && nowServerMs >= startAt
        val countdownS = if (startAt != null && nowServerMs != null && nowServerMs < startAt) {
            ceil((startAt - nowServerMs) / 1000.0).toInt()
        } else {
            null
        }
        val positions = if (nowServerMs != null) {
            roomState.positions(nowServerMs, me?.let { SelfPosition(it.distM, it.speedMps, name) })
        } else {
            emptyList()
        }
        val byId = positions.filter { !it.isSelf }.associateBy { it.id }
        val selfRiding = me != null && started && me.distM > START_ZONE_M
        val riders = buildList {
            add(
                RoomRiderUi(
                    id = roomState.selfId.orEmpty(),
                    name = name,
                    self = true,
                    host = roomState.isHost,
                    atStart = me != null && !selfRiding,
                    riding = selfRiding,
                    distKm = if (selfRiding) me!!.distM / 1000.0 else null,
                ),
            )
            roomState.riders.values.sortedBy { it.info.name.lowercase() }.forEach { r ->
                val p = byId[r.info.id]
                val fresh = p != null && p.ageMs <= READY_MAX_AGE_MS
                val riding = fresh && started && p!!.distM > START_ZONE_M
                add(
                    RoomRiderUi(
                        id = r.info.id,
                        name = r.info.name,
                        self = false,
                        host = r.info.host,
                        atStart = fresh && !riding,
                        riding = riding,
                        distKm = if (riding) p!!.distM / 1000.0 else null,
                    ),
                )
            }
        }
        val connection: RoomConnectionUi = when (val s = status) {
            is RoomConnectionStatus.Connected, RoomConnectionStatus.Idle, is RoomConnectionStatus.Closed -> RoomConnectionUi.Online
            is RoomConnectionStatus.Connecting -> RoomConnectionUi.Reconnecting(s.attempt, null)
            is RoomConnectionStatus.Reconnecting -> RoomConnectionUi.Reconnecting(
                attempt = s.attempt,
                retryInS = retryAtClientMs?.let { ceil((it - clientClock()) / 1000.0).toInt().coerceAtLeast(0) },
            )
        }
        _ui.value = IndoorRoomUi.Open(
            serverHost = hostOf(inv.serverUrl),
            host = roomState.isHost,
            route = routeUiOf(room.route),
            riders = riders,
            maxRiders = room.maxRiders,
            connection = connection,
            countdownS = countdownS,
            started = started,
            inviteLink = inv.toLink(),
            sharePower = sharePower,
            selfAtStart = me != null,
        )
        _ride.value = if (me == null) {
            null
        } else {
            RoomRideUi(
                connection = connection,
                waiting = me.held,
                countdownS = countdownS,
                host = roomState.isHost,
                atStart = riders.count { it.atStart || it.riding },
                total = riders.size,
                riders = positions
                    .filter { !it.isSelf && it.gapToSelfM != null }
                    .sortedBy { abs(it.gapToSelfM!!) }
                    .take(RoomRideUi.MAX_SHOWN)
                    .map { RoomGapUi(it.id, it.name, it.gapToSelfM!!, it.stale, it.powerW) },
            )
        }
    }

    private fun routeUiOf(route: RoomRoute): RoomRouteUi {
        val ref = roomRouteRef(route)
        val info = ref?.let { r -> runCatching { routeInfo(r) }.getOrNull() }
        return RoomRouteUi(
            key = route.id,
            name = info?.name,
            fallbackName = route.name,
            lengthKm = route.lengthM / 1000.0,
            availability = info?.availability ?: ChallengeAvailability.UNKNOWN,
            catalogId = (ref as? ChallengeRoute.Catalog)?.id,
        )
    }

    companion object {
        const val KEY_NAME = "indoor.room.name"
        const val KEY_SHARE_POWER = "indoor.room.share_power"

        /** Sendetakt der eigenen Stelle: gut 3-mal je Sekunde (Protokoll: 2–4 Hz). */
        const val TICK_MS = 300L

        /** Vorlauf des gemeinsamen Starts. */
        const val COUNTDOWN_S = 10

        /** Wie das Protokoll: Anzeigename höchstens 32 Zeichen. */
        const val NAME_MAX_CHARS = 32

        /** Obergrenze des Protokolls für `speedMps`. */
        const val MAX_SPEED_MPS = 30.0

        /** Bis hierher gilt ein Fahrer nach dem Start noch als „am Start". */
        const val START_ZONE_M = 1.0

        /** „Bereit" gilt, solange die letzte Meldung nicht älter ist. */
        const val READY_MAX_AGE_MS = 5_000L

        /** Liest einen Einladungslink aus eingefügtem Text (auch mit Text drumherum); `null` = keiner. */
        fun parseInvite(text: String): RoomInvite? {
            val trimmed = text.trim()
            RoomInvite.parse(trimmed)?.let { return it }
            val start = trimmed.indexOf("${RoomInvite.SCHEME}://${RoomInvite.HOST}?", ignoreCase = true)
            if (start < 0) return null
            val candidate = trimmed.substring(start).takeWhile { !it.isWhitespace() }
            return RoomInvite.parse(candidate)
        }

        /** Die Strecke eines Raums als Kennung der Streckenwahl; `null` = diese App kennt die Form nicht. */
        fun roomRouteRef(route: RoomRoute): ChallengeRoute? =
            routeOfKey(route.id, route.version?.toIntOrNull() ?: 0)

        /** Die Strecke für `POST /api/rooms` aus der Streckenwahl. */
        fun roomRouteOf(ref: ChallengeRoute, lengthM: Double, name: String?): RoomRoute? {
            if (!lengthM.isFinite() || lengthM <= 0.0) return null
            return RoomRoute(
                id = ref.key,
                lengthM = lengthM,
                loop = false,
                version = (ref as? ChallengeRoute.Catalog)?.version?.toString(),
                name = name?.take(64)?.takeIf { it.isNotBlank() },
            )
        }

        /** Anzeigename: ohne Steuerzeichen, Leerraum zusammengezogen, höchstens [NAME_MAX_CHARS]. */
        fun cleanName(raw: String): String {
            val collapsed = raw.map { if (it.isWhitespace()) ' ' else it }
                .filter { !it.isISOControl() }
                .joinToString("")
                .trim()
                .replace(Regex(" +"), " ")
            val end = collapsed.offsetByCodePoints(0, minOf(NAME_MAX_CHARS, collapsed.codePointCount(0, collapsed.length)))
            return collapsed.substring(0, end).trim()
        }

        /** Nur der Rechnername einer Server-Adresse, für die Anzeige. */
        fun hostOf(serverUrl: String): String =
            runCatching { URI(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: serverUrl
    }
}
