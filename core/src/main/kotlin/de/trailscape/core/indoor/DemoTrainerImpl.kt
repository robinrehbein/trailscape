package de.trailscape.core.indoor

/**
 * Der simulierte Trainer ([DemoTrainer], docs/indoor.md Abschnitt 5.5).
 *
 * ## Zeit
 * Wie jeder Trainer laeuft er nur ueber [tick]: Der erste Aufruf nach [start]
 * merkt die Startzeit, [TrainerConnection.READY] kommt im ersten Aufruf, der
 * mindestens [READY_DELAY_MS] spaeter liegt. In diesem Aufruf geht auch der
 * erste Messwert hinaus; danach je Aufruf, wenn seit dem letzten Messwert
 * mindestens [TELEMETRY_INTERVAL_MS] vergangen sind. Ohne [tick] passiert
 * nichts — es gibt keinen Timer und keinen eigenen Thread.
 *
 * ## Modell
 *  * **SIM** (letzter Modusbefehl [TrainerCommand.SetSimulation] oder noch
 *    keiner): Leistung = [riderPowerW].
 *  * **ERG** ([TrainerCommand.SetTargetPower]): Die Leistung laeuft dem Ziel
 *    erster Ordnung nach, je Messwert um [ERG_STEP_FRACTION] der Differenz
 *    (Zeitkonstante etwa 2 s bei Sekundentakt). Das Ziel wird auf
 *    0..[MAX_POWER_W] geklemmt. Bei Trittfrequenz 0 gibt es keine Leistung,
 *    und der Nachlauf beginnt danach wieder bei 0, wie bei einem echten
 *    Trainer, der ohne Tritt keine Last aufbauen kann.
 *  * **Pause/Stop**: Leistung 0 bis zum naechsten [TrainerCommand.Start]. Die
 *    Trittfrequenz bleibt die des Fahrers.
 *  * [TrainerTelemetry.speedKmh] ist eine grobe Schwungradschaetzung aus der
 *    Trittfrequenz (Uebersetzung 40/14, Abrollumfang 2,1 m).
 *
 * Befehle vor `READY` werden still gemerkt (je Art gilt der letzte, die
 * Reihenfolge von Start/Pause/Stop bleibt erhalten) und wirken ab dem ersten
 * Messwert; ein Fehlschlag wird nie gemeldet, denn der Demo-Trainer lehnt
 * nichts ab. Befehle ohne laufenden [start] (oder nach [stop]) werden
 * verworfen.
 *
 * ## Threads
 * Alle Methoden duerfen von jedem Thread gerufen werden. Der Zustand liegt
 * hinter einem Lock; Listener werden NIE unter dem Lock gerufen, ein Listener
 * darf also aus seinem Aufruf heraus [send] oder [stop] rufen.
 */
internal class DemoTrainerImpl : DemoTrainer {

    override val name: String = "Demo-Trainer"

    @Volatile
    override var riderPowerW: Int = DEFAULT_POWER_W

    @Volatile
    override var riderCadenceRpm: Int = DEFAULT_CADENCE_RPM

    private val lock = Any()

    // Alles Folgende nur unter [lock].
    private var listener: TrainerListener? = null
    private var ready = false
    private var startMs: Long? = null
    private var lastTelemetryMs: Long = 0L

    /** `true` = ERG (Zielleistung), `false` = SIM. */
    private var erg = false
    private var targetW = 0
    private var paused = false

    /** Gleitende Leistung des ERG-Nachlaufs in Watt. */
    private var ergPowerW = 0.0

    /** Was ein [tick] unter dem Lock entschieden hat und danach ausserhalb meldet. */
    private class Outcome(
        val listener: TrainerListener,
        val connection: TrainerConnection?,
        val telemetry: TrainerTelemetry?,
    )

    override fun start(listener: TrainerListener) {
        synchronized(lock) {
            this.listener = listener
            ready = false
            startMs = null
            lastTelemetryMs = 0L
            erg = false
            targetW = 0
            paused = false
            ergPowerW = 0.0
        }
        listener.onConnection(TrainerConnection.CONNECTING, TrainerCapabilities.NONE)
    }

    override fun send(command: TrainerCommand) {
        synchronized(lock) {
            if (listener == null) return
            when (command) {
                is TrainerCommand.SetSimulation -> erg = false
                is TrainerCommand.SetTargetPower -> {
                    if (!erg) ergPowerW = if (paused) 0.0 else riderPowerW.coerceAtLeast(0).toDouble()
                    erg = true
                    targetW = command.watts.coerceIn(0, MAX_POWER_W)
                }
                TrainerCommand.Start -> paused = false
                TrainerCommand.Pause, TrainerCommand.Stop -> {
                    paused = true
                    ergPowerW = 0.0
                }
            }
        }
    }

    override fun tick(nowMs: Long) {
        val outcome = synchronized(lock) { decide(nowMs) } ?: return
        outcome.connection?.let { outcome.listener.onConnection(it, CAPABILITIES) }
        outcome.telemetry?.let { outcome.listener.onTelemetry(it) }
    }

    override fun stop() {
        val old = synchronized(lock) {
            val l = listener
            listener = null
            ready = false
            startMs = null
            l
        }
        old?.onConnection(TrainerConnection.OFF, TrainerCapabilities.NONE)
    }

    // Unter [lock] zu rufen.
    private fun decide(nowMs: Long): Outcome? {
        val target = listener ?: return null
        val start = startMs
        if (start == null) {
            startMs = nowMs
            return null
        }
        if (!ready) {
            if (nowMs - start < READY_DELAY_MS) return null
            ready = true
            lastTelemetryMs = nowMs
            return Outcome(target, TrainerConnection.READY, nextTelemetry(nowMs))
        }
        if (nowMs - lastTelemetryMs < TELEMETRY_INTERVAL_MS) return null
        lastTelemetryMs = nowMs
        return Outcome(target, null, nextTelemetry(nowMs))
    }

    // Unter [lock] zu rufen.
    private fun nextTelemetry(nowMs: Long): TrainerTelemetry {
        val cadence = riderCadenceRpm.coerceAtLeast(0)
        val power: Double = when {
            paused -> {
                ergPowerW = 0.0
                0.0
            }
            !erg -> riderPowerW.coerceAtLeast(0).toDouble()
            cadence <= 0 -> {
                ergPowerW = 0.0
                0.0
            }
            else -> {
                ergPowerW += (targetW - ergPowerW) * ERG_STEP_FRACTION
                ergPowerW
            }
        }
        return TrainerTelemetry(
            powerW = Math.round(power).toInt(),
            cadenceRpm = cadence.toDouble(),
            speedKmh = cadence * SPEED_KMH_PER_RPM,
            receivedMs = nowMs,
        )
    }

    companion object {
        /** Wartezeit von der ersten Tick-Zeit bis `READY`. */
        const val READY_DELAY_MS = 1000L

        /** Abstand der Messwerte. */
        const val TELEMETRY_INTERVAL_MS = 1000L

        /** Anteil der Differenz zum Ziel, den der ERG-Nachlauf je Messwert aufholt. */
        const val ERG_STEP_FRACTION = 0.4

        const val MAX_POWER_W = 1500

        const val DEFAULT_POWER_W = 150
        const val DEFAULT_CADENCE_RPM = 85

        /** Kadenz · 40/14 Uebersetzung · 2,1 m Umfang · 60 min/h / 1000 = km/h je U/min. */
        private const val SPEED_KMH_PER_RPM = 40.0 / 14.0 * 2.1 * 60.0 / 1000.0

        private val CAPABILITIES = TrainerCapabilities(
            simulation = true,
            targetPower = true,
            minPowerW = 0,
            maxPowerW = MAX_POWER_W,
            reportsCadence = true,
        )
    }
}

/** Fabrik fuer den Einstieg ([createDemoTrainer]). */
internal fun demoTrainer(): DemoTrainer = DemoTrainerImpl()
