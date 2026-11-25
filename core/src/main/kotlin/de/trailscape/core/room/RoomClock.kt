package de.trailscape.core.room

/**
 * # Uhrenabgleich mit dem Server
 *
 * Alle Zeiten im Raum (`at`, `startAt`, `serverTime`) sind **Server-Uhr** in
 * ms seit Epoch. Das Gerät rechnet mit seiner eigenen monotonen Uhr (etwa
 * `SystemClock.elapsedRealtime()`) und braucht nur den Versatz:
 * `Server-Uhr ≈ Geräte-Uhr + offsetMs`.
 *
 * Verfahren wie bei NTP, vereinfacht: Das Gerät schickt `ping {c}`, der
 * Server antwortet `pong {c, s}`. Mit Empfangszeit `r` ist die Laufzeit
 * `rtt = r − c` und der Versatz `s − (c + rtt/2)`. Genommen wird die Probe mit
 * der **kleinsten Laufzeit** unter den letzten [window] — bei Mobilfunk
 * schwankt die Laufzeit stark, und die schnellste Antwort hat den kleinsten
 * Fehler (höchstens `rtt/2`, wenn Hin- und Rückweg ungleich lang sind).
 *
 * Reine Datenstruktur: [add] liefert einen neuen Stand.
 */
data class ClockSample(val clientSentMs: Long, val serverMs: Long, val clientReceivedMs: Long) {
    val rttMs: Long get() = clientReceivedMs - clientSentMs
    val offsetMs: Long get() = serverMs - (clientSentMs + rttMs / 2)
}

data class RoomClock(
    val samples: List<ClockSample> = emptyList(),
    /** Grobe erste Schätzung aus `welcome.serverTime` (Laufzeit unbekannt). */
    val roughOffsetMs: Long? = null,
    val window: Int = 8,
) {
    /** Neue Probe aus einem Pong; unplausible Proben (rtt < 0 oder > [MAX_RTT_MS]) fallen weg. */
    fun add(sample: ClockSample): RoomClock {
        if (sample.rttMs < 0 || sample.rttMs > MAX_RTT_MS) return this
        return copy(samples = (samples + sample).takeLast(window))
    }

    /** Erste grobe Schätzung aus einer Server-Zeit, die zur Geräte-Zeit [clientReceivedMs] ankam. */
    fun withServerTime(serverMs: Long, clientReceivedMs: Long): RoomClock =
        copy(roughOffsetMs = serverMs - clientReceivedMs)

    /** Beste Probe (kleinste Laufzeit) oder `null`. */
    val best: ClockSample? get() = samples.minByOrNull { it.rttMs }

    /** Versatz Gerät → Server oder `null`, solange nichts bekannt ist. */
    val offsetMs: Long? get() = best?.offsetMs ?: roughOffsetMs

    /** Größtmöglicher Fehler des Versatzes in ms (halbe Laufzeit), `null` ohne Probe. */
    val uncertaintyMs: Long? get() = best?.let { it.rttMs / 2 }

    /** Geräte-Zeit → Server-Zeit (ohne Kenntnis: unverändert). */
    fun toServer(clientMs: Long): Long = clientMs + (offsetMs ?: 0L)

    /** Server-Zeit → Geräte-Zeit. */
    fun toClient(serverMs: Long): Long = serverMs - (offsetMs ?: 0L)

    companion object {
        const val MAX_RTT_MS: Long = 10_000L
    }
}
