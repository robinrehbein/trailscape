package de.trailscape.core.room

import kotlin.math.max
import kotlin.math.min

/**
 * # Raumzustand als reine Funktion
 *
 * [RoomState.reduce] verarbeitet eine [ServerMessage] und liefert einen neuen
 * Zustand — keine Uhr, kein Netz, keine Nebenwirkung. [RoomState.positions]
 * rechnet daraus für einen Zeitpunkt (Server-Uhr) die Stelle jedes fremden
 * Fahrers, die Rangfolge und die Abstände.
 *
 * Die Welt ist eindimensional: `distM` sind Laufmeter seit dem Start der
 * Strecke, bei Rundkursen über die Runden fortlaufend (Runde 2 beginnt bei
 * `lengthM`). Wer weiter ist, liegt vorn — auch über Runden hinweg.
 *
 * ## Fortschreiben zwischen den Meldungen (2–4 Hz)
 *
 * Zustände kommen alle 250–500 ms (dazu Laufzeit und Takt des Servers),
 * gezeichnet wird mit 30–60 Hz. Für den Zeitpunkt `t = jetzt − renderDelayMs`:
 *  * liegt `t` zwischen zwei Meldungen: lineare **Interpolation**;
 *  * liegt `t` nach der letzten: **Extrapolation** mit deren Tempo, höchstens
 *    [PositionParams.maxExtrapolationMs] weit; danach bleibt der Fahrer stehen
 *    (lieber stehen als ins Blaue fahren);
 *  * liegt `t` vor der ältesten: deren Stelle.
 *
 * `renderDelayMs = 0` (Vorgabe) zeigt die geschätzte **aktuelle** Stelle — das
 * braucht Rangfolge und Windschatten. Ein Renderer kann mit 250–500 ms
 * Verzögerung glatter interpolieren; das Weichzeichnen pro Bild bleibt Sache
 * von `MotionSmoother` in `:app`.
 *
 * ## Zeitüberschreitung
 *
 * Ohne neue Meldung seit [PositionParams.staleAfterMs] (3 s) gilt ein Fahrer
 * als `stale` (ausgegraut, kein Windschatten). Nach
 * [PositionParams.dropAfterMs] (30 s) fällt er aus der Liste, auch wenn der
 * Server (noch) kein `left` geschickt hat — etwa weil die eigene Verbindung
 * weg ist.
 */
data class StateSample(
    val at: Long,
    val distM: Double,
    val speedMps: Double,
    val powerW: Int? = null,
    val cadenceRpm: Int? = null,
    val lane: Int? = null,
)

data class RemoteRider(
    val info: RiderInfo,
    /** Die letzten Meldungen, älteste zuerst, höchstens [RoomState.SAMPLES_KEPT]. */
    val samples: List<StateSample> = emptyList(),
)

enum class RoomPhase { WAITING_FOR_WELCOME, IN_ROOM, ENDED }

data class RoomState(
    val phase: RoomPhase = RoomPhase.WAITING_FOR_WELCOME,
    val selfId: String? = null,
    val isHost: Boolean = false,
    val room: RoomInfo? = null,
    /** Alle Fahrer außer einem selbst. */
    val riders: Map<String, RemoteRider> = emptyMap(),
    val startAt: Long? = null,
    val endedReason: String? = null,
    /** Letzter Fehlercode vom Server (zur Anzeige/Diagnose). */
    val lastErrorCode: String? = null,
) {
    fun reduce(message: ServerMessage): RoomState = when (message) {
        is ServerMessage.Welcome -> {
            val selfId = message.you.id
            // Bekannte Meldungen behalten (Wiederbeitritt), Fahrer aus der Liste übernehmen.
            val next = mutableMapOf<String, RemoteRider>()
            for (info in message.riders) {
                if (info.id == selfId) continue
                next[info.id] = RemoteRider(info, riders[info.id]?.samples.orEmpty())
            }
            copy(
                phase = RoomPhase.IN_ROOM,
                selfId = selfId,
                isHost = message.you.host,
                room = message.room,
                riders = next,
                startAt = message.room.startAt,
                endedReason = null,
            ).applyStates(message.states)
        }
        is ServerMessage.Rider -> {
            if (message.rider.id == selfId) {
                this
            } else {
                val old = riders[message.rider.id]
                copy(riders = riders + (message.rider.id to RemoteRider(message.rider, old?.samples.orEmpty())))
            }
        }
        is ServerMessage.Left -> copy(riders = riders - message.id)
        is ServerMessage.Snapshot -> applyStates(message.states)
        is ServerMessage.Countdown -> copy(startAt = message.startAt)
        is ServerMessage.Ended -> copy(phase = RoomPhase.ENDED, endedReason = message.reason)
        is ServerMessage.Error -> copy(lastErrorCode = message.code)
        is ServerMessage.Pong, is ServerMessage.Unknown -> this
    }

    private fun applyStates(states: List<RiderStateEntry>): RoomState {
        if (states.isEmpty()) return this
        val next = riders.toMutableMap()
        for (s in states) {
            if (s.id == selfId) continue
            val r = next[s.id] ?: continue // unbekannter Fahrer: `rider` kommt immer vorher
            val last = r.samples.lastOrNull()
            if (last != null && s.at <= last.at) continue // alt oder doppelt
            val sample = StateSample(s.at, s.distM, s.speedMps, s.powerW, s.cadenceRpm, s.lane)
            next[s.id] = r.copy(samples = (r.samples + sample).takeLast(SAMPLES_KEPT))
        }
        return copy(riders = next)
    }

    /**
     * Stellen aller Fahrer zum Server-Zeitpunkt [nowServerMs], nach Rang
     * sortiert (1 = vorn). [self] ist die eigene Stelle aus der lokalen
     * Sitzung (genauer als das Echo vom Server); ohne [self] nur die Fremden.
     * Fahrer ohne jede Meldung fehlen (Stelle unbekannt).
     */
    fun positions(nowServerMs: Long, self: SelfPosition? = null, params: PositionParams = PositionParams()): List<RiderPosition> {
        data class Raw(val id: String, val name: String, val isSelf: Boolean, val distM: Double, val speedMps: Double,
                       val powerW: Int?, val lane: Int?, val ageMs: Long?)
        val raws = mutableListOf<Raw>()
        if (self != null) {
            raws += Raw(selfId ?: SELF_ID, self.name, true, self.distM, self.speedMps, self.powerW, self.lane, 0L)
        }
        for (r in riders.values) {
            val last = r.samples.lastOrNull() ?: continue
            val age = nowServerMs - last.at
            if (age > params.dropAfterMs) continue
            val d = estimateDistance(r.samples, nowServerMs - params.renderDelayMs, params.maxExtrapolationMs) ?: continue
            val moving = age <= params.maxExtrapolationMs
            raws += Raw(
                r.info.id, r.info.name, false, d,
                if (moving) last.speedMps else 0.0,
                if (r.info.sharePower) last.powerW else null,
                last.lane, age,
            )
        }
        val sorted = raws.sortedWith(compareByDescending<Raw> { it.distM }.thenBy { it.id })
        val leader = sorted.firstOrNull()?.distM ?: return emptyList()
        val me = sorted.firstOrNull { it.isSelf }
        return sorted.mapIndexed { i, r ->
            val gapToSelf = me?.let { if (r.isSelf) 0.0 else r.distM - it.distM }
            RiderPosition(
                id = r.id,
                name = r.name,
                isSelf = r.isSelf,
                distM = r.distM,
                speedMps = r.speedMps,
                powerW = r.powerW,
                lane = r.lane,
                ageMs = r.ageMs ?: 0L,
                stale = !r.isSelf && (r.ageMs ?: 0L) > params.staleAfterMs,
                rank = i + 1,
                gapToLeaderM = leader - r.distM,
                gapToSelfM = gapToSelf,
                timeGapToSelfS = if (me == null || gapToSelf == null) {
                    null
                } else {
                    // Zeitabstand = Strecke / Tempo des Hinterherfahrenden (wie Zeitnahme an einem Punkt).
                    val followerSpeed = if (gapToSelf >= 0) me.speedMps else r.speedMps
                    gapToSelf / max(MIN_SPEED_FOR_TIME_GAP, followerSpeed)
                },
            )
        }
    }

    companion object {
        const val SAMPLES_KEPT: Int = 3
        const val SELF_ID: String = "self"
        const val MIN_SPEED_FOR_TIME_GAP: Double = 1.0
    }
}

/** Eigene Stelle aus der lokalen Fahrsitzung. */
data class SelfPosition(
    val distM: Double,
    val speedMps: Double,
    val name: String = "",
    val powerW: Int? = null,
    val lane: Int? = null,
)

data class PositionParams(
    val renderDelayMs: Long = 0L,
    val maxExtrapolationMs: Long = 2_000L,
    val staleAfterMs: Long = 3_000L,
    val dropAfterMs: Long = 30_000L,
)

data class RiderPosition(
    val id: String,
    val name: String,
    val isSelf: Boolean,
    /** Geschätzte Stelle (Laufmeter, fortlaufend). */
    val distM: Double,
    /** Letztes gemeldetes Tempo; 0, wenn länger als die Extrapolationsgrenze nichts kam. */
    val speedMps: Double,
    /** Nur, wenn der Fahrer Leistung teilt. */
    val powerW: Int?,
    val lane: Int?,
    /** Alter der letzten Meldung in ms (eigener Fahrer: 0). */
    val ageMs: Long,
    val stale: Boolean,
    val rank: Int,
    /** Rückstand auf den Ersten in m (≥ 0). */
    val gapToLeaderM: Double,
    /** Abstand zu mir in m: positiv = vor mir, negativ = hinter mir; `null` ohne eigene Stelle. */
    val gapToSelfM: Double?,
    /** Zeitabstand zu mir in s (Vorzeichen wie [gapToSelfM]); `null` ohne eigene Stelle. */
    val timeGapToSelfS: Double?,
)

/**
 * Stelle eines Fahrers zum Zeitpunkt [atMs] aus seinen Meldungen (älteste
 * zuerst); siehe Abschnitt „Fortschreiben“ oben. `null` ohne Meldung.
 */
fun estimateDistance(samples: List<StateSample>, atMs: Long, maxExtrapolationMs: Long): Double? {
    val last = samples.lastOrNull() ?: return null
    if (atMs >= last.at) {
        val dtS = min(atMs - last.at, maxExtrapolationMs).coerceAtLeast(0L) / 1000.0
        return last.distM + last.speedMps * dtS
    }
    val first = samples.first()
    if (atMs <= first.at) return first.distM
    for (i in samples.size - 1 downTo 1) {
        val a = samples[i - 1]
        val b = samples[i]
        if (atMs >= a.at && atMs <= b.at) {
            val span = (b.at - a.at).toDouble()
            if (span <= 0) return b.distM
            val f = (atMs - a.at) / span
            return a.distM + (b.distM - a.distM) * f
        }
    }
    return last.distM
}
