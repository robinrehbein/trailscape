package de.trailscape.core.indoor

import java.util.Locale
import kotlin.math.roundToInt

/**
 * # Fahrprotokoll: was der Trainer bekam und was dabei herauskam
 *
 * Nach den ersten echten Fahrten soll der Inhaber nachjustieren können
 * (Fahrgefühl, `trainerScale`, Crr). Dafür hält [RideProtocolRecorder] je
 * [INTERVAL_S] Sekunden eine Zeile fest:
 *
 *  * die **gesetzte** Steigung (`SetSimulation.gradePct`, mit der virtuellen
 *    Übersetzung verrechnet) samt Crr und cw — bzw. das ERG-Ziel,
 *  * die Steigung der Strecke, den Belag, den Gang,
 *  * die **gemessene** Leistung und Trittfrequenz (Mittel über das Intervall),
 *  * die **simulierte** Geschwindigkeit (Mittel).
 *
 * Es bleibt auf dem Gerät: reiner Text (CSV mit `;`, Dezimalpunkt), den die App
 * in einer Datei im privaten Speicher ablegt und auf Wunsch wie die
 * Diagnoseberichte teilt. Nichts verlässt das Gerät von selbst, nichts wird
 * hochgeladen. Der Recorder kennt weder Dateien noch Uhr — er bekommt jeden
 * [IndoorStep] der Sitzung.
 *
 * Zeilen sind auf [MAX_ROWS] begrenzt (etwa 5½ Stunden); danach wird weiter
 * gezählt, aber nichts mehr angehängt (die Kopfzeile sagt es).
 */
class RideProtocolRecorder(
    private val header: List<String>,
    private val intervalS: Int = INTERVAL_S,
    private val maxRows: Int = MAX_ROWS,
) {
    private val rows = ArrayList<String>()
    private var lastSim: TrainerCommand.SetSimulation? = null
    private var lastErgW: Int? = null
    private var accPower = 0.0
    private var accCad = 0.0
    private var accSpeed = 0.0
    private var accN = 0
    private var accHasPower = 0
    private var nextRowS = intervalS
    private var droppedRows = 0
    private var sumPower = 0.0
    private var sumSpeed = 0.0
    private var nTotal = 0
    private var lastElapsed = 0

    /** Zahl der aufgezeichneten Zeilen. */
    val rowCount: Int get() = rows.size

    /** Ob etwas Brauchbares aufgezeichnet wurde (mindestens eine Zeile). */
    val hasData: Boolean get() = rows.isNotEmpty()

    /** Ein Schritt der Sitzung. Nur laufende Phasen zählen. */
    fun onStep(step: IndoorStep) {
        for (c in step.commands) {
            when (c) {
                is TrainerCommand.SetSimulation -> { lastSim = c; lastErgW = null }
                is TrainerCommand.SetTargetPower -> { lastErgW = c.watts }
                else -> Unit
            }
        }
        val s = step.state
        if (s.phase != IndoorPhase.RUNNING) return
        accN++
        accSpeed += s.speedMps
        s.powerW?.let { accPower += it; accHasPower++; sumPower += it }
        accCad += s.cadenceRpm ?: 0
        sumSpeed += s.speedMps
        nTotal++
        lastElapsed = s.elapsedS
        if (s.elapsedS >= nextRowS) {
            flush(s)
            nextRowS = s.elapsedS + intervalS
        }
    }

    private fun flush(s: IndoorSessionState) {
        if (accN == 0) return
        val sim = lastSim
        val power = if (accHasPower > 0) (accPower / accHasPower).roundToInt().toString() else ""
        val cad = (accCad / accN).roundToInt()
        val speedKmh = accSpeed / accN * 3.6
        val row = listOf(
            s.elapsedS.toString(),
            f(s.gradePct, 1),
            if (sim != null) f(sim.gradePct, 2) else "",
            if (sim != null) f(sim.crr, 4) else f(s.crr, 4),
            if (sim != null) f(sim.cwKgPerM, 3) else "",
            lastErgW?.toString() ?: "",
            s.surface.name,
            s.gear.index.toString(),
            f(s.gear.ratio, 2),
            power,
            cad.toString(),
            f(speedKmh, 1),
        ).joinToString(";")
        if (rows.size < maxRows) rows.add(row) else droppedRows++
        accPower = 0.0; accCad = 0.0; accSpeed = 0.0; accN = 0; accHasPower = 0
    }

    /** Der Text des Protokolls. */
    fun toText(): String = buildString {
        append("# Trailscape Fahrprotokoll (nur auf diesem Geraet, Version 1)\n")
        header.forEach { append("# ").append(it).append('\n') }
        if (droppedRows > 0) append("# Hinweis: ").append(droppedRows).append(" weitere Zeilen nicht aufgezeichnet (Obergrenze)\n")
        if (nTotal > 0) {
            append("# Mittel: ")
            append("Leistung ").append(f(sumPower / nTotal, 0)).append(" W, ")
            append("Tempo ").append(f(sumSpeed / nTotal * 3.6, 1)).append(" km/h, ")
            append("Dauer ").append(lastElapsed).append(" s\n")
        }
        append(COLUMNS).append('\n')
        rows.forEach { append(it).append('\n') }
    }

    companion object {
        const val INTERVAL_S: Int = 5
        const val MAX_ROWS: Int = 4000

        /**
         * Spalten: Zeit s; Strecke %; **gesetzte** Steigung % (an den Trainer);
         * Crr; cw kg/m; ERG-Ziel W; Belag; Gang; Übersetzung; **gemessene**
         * Leistung W; Trittfrequenz U/min; **simulierte** Geschwindigkeit km/h.
         */
        const val COLUMNS: String =
            "zeit_s;strecke_pct;gesetzt_pct;crr;cw;erg_w;belag;gang;uebersetzung;leistung_w;trittfrequenz;tempo_kmh"

        private fun f(v: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", v)

        /** Kopfzeilen mit dem Setup, damit sich ein Protokoll später deuten lässt. */
        fun headerOf(rider: RiderSetup, mode: IndoorMode, routeName: String, startedAtEpochMs: Long): List<String> = listOf(
            "Start (ms seit Epoche): $startedAtEpochMs",
            "Strecke: $routeName, Modus: ${mode.name}",
            "Masse Fahrer+Rad: ${f(rider.totalMassKg, 1)} kg, CdA: ${f(rider.cdaM2, 3)} m2, Profil-Crr: ${f(rider.profileCrr, 4)}",
            "Fahrgefuehl: trainerScale ${f(rider.trainerScale, 2)}, Belagsstaerke ${f(rider.surfaceStrength, 2)}",
            "Trainer-Schwierigkeit: ${f(rider.difficulty, 2)}, echte Uebersetzung: ${f(rider.realGearRatio, 3)}, " +
                "Gangtabelle: ${rider.gearTable.name}, Klemmung ${f(rider.minSimGradePct, 0)}..${f(rider.maxSimGradePct, 0)} %",
        )
    }
}
