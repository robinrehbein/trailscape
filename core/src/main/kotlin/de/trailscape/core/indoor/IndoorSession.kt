package de.trailscape.core.indoor

import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint

/**
 * # Die Indoor-Sitzung — ein reiner Automat
 *
 * VERTRAGSDATEI (docs/indoor.md, Abschnitt 5). Besitzer: Slice indoor-core.
 * Typen und Signaturen sind eingefroren.
 *
 * Die Sitzung ist das Herz der Fahrt und kennt weder Funk noch Bildschirm:
 * Die Laufzeit in `:app` reicht ihr etwa zehnmal je Sekunde die letzten
 * Messwerte ([IndoorSession.step]) und bekommt den neuen Zustand samt den
 * Befehlen fuer den Trainer zurueck.
 *
 * ```
 *  Trainer ──Telemetrie──► Laufzeit ──step(nowMs, input)──► IndoorSession
 *     ▲                       │  ◄── IndoorStep(state, commands) ──┘
 *     └──── send(command) ────┤
 *                             ├──► WorldRenderer.setState(…)   (3D)
 *                             └──► HUD (Compose)
 * ```
 *
 * Zeit ist ausschliesslich das uebergebene `nowMs` (monotone Uhr); die Sitzung
 * liest nie selbst eine Uhr. Damit ist jede Fahrt im Test Sekunde fuer Sekunde
 * nachstellbar.
 */

/** Fahrmodus der Sitzung. */
enum class IndoorMode {
    /**
     * Freie Fahrt: Der Widerstand folgt Steigung, Belag und Wind der Strecke
     * (FTMS-Simulation), geschaltet wird mit den virtuellen Gaengen.
     */
    SIM,

    /**
     * Einheit nach Plan: Der Trainer haelt die Zielleistung des aktuellen
     * Abschnitts ([ErgWorkout]), gleich welche Trittfrequenz. Die Strecke
     * laeuft trotzdem unter dem Rad durch — das Tempo rechnet die Sitzung aus
     * der Leistung. Gaenge sind ohne Wirkung.
     */
    ERG,
}

/**
 * Fahrer und Rad, wie die Physik sie braucht. Die App fuellt es aus dem
 * `TrainingProfile` (Gewicht, Setup-Masse, CdA, Crr, FTP).
 *
 * @property ftpW Schwellenleistung fuer die Zielleistungen der ERG-Einheit und
 *   die Vergleichsleistung des Geists.
 * @property difficulty „Trainer-Schwierigkeit" 0..1: Anteil der Steigung, den
 *   der Trainer spuert (1 = volle Steigung). Wirkt nur auf den Widerstand,
 *   nie auf das Tempo der virtuellen Fahrt.
 * @property trainerScale kalibrierbarer Faktor des Widerstandsmodells
 *   (docs/indoor.md, Abschnitt 5.3): gleicht aus, dass die vom Trainer
 *   angenommene Masse unbekannt ist. 1,0 = keine Korrektur.
 * @property realGearRatio siehe dort.
 */
data class RiderSetup(
    val riderMassKg: Double,
    val bikeMassKg: Double,
    val cdaM2: Double,
    val profileCrr: Double,
    val ftpW: Double,
    val driveEfficiency: Double = 0.97,
    val difficulty: Double = 1.0,
    val trainerScale: Double = 1.0,
    /**
     * Die **echte** Uebersetzung am Trainer (Kettenblatt / Ritzel) — beim
     * Zwift Cog ein einziges 14er-Ritzel. Bezug der virtuellen Gaenge.
     */
    val realGearRatio: Double = DEFAULT_REAL_GEAR_RATIO,
    /**
     * Welche Tabelle der virtuellen Gaenge gilt (siehe [VirtualGearTable]):
     * gleichmaessig gestuft oder wie am eigenen Rad. Ergaenzt von indoor-core.
     */
    val gearTable: VirtualGearTable = VirtualGearTable.EVEN,
    /**
     * Untergrenze der an den Trainer gesendeten Ersatzsteigung in Prozent.
     * Trainer klemmen selbst (KICKR etwa −10..+20 %); die Vorgabe −40 laesst
     * ihnen den vollen Wert. Ergaenzt von indoor-core.
     */
    val minSimGradePct: Double = -40.0,
    /** Obergrenze der gesendeten Ersatzsteigung in Prozent, siehe [minSimGradePct]. Ergaenzt von indoor-core. */
    val maxSimGradePct: Double = 40.0,
    /**
     * Wie deutlich der Belag zu spueren ist (Spreizung der Crr-Tabelle um den
     * Mischwert, siehe [RideFeel.surfaceStrength]); 1,0 = Tabelle unveraendert.
     * Ergaenzt von idee-06 (Kalibrierung).
     */
    val surfaceStrength: Double = 1.0,
) {
    val totalMassKg: Double get() = riderMassKg + bikeMassKg

    companion object {
        /** 40er-Kettenblatt auf dem 14er-Ritzel des Zwift Cog. */
        const val DEFAULT_REAL_GEAR_RATIO: Double = 40.0 / 14.0
    }
}

/**
 * Konstanter Wind der Fahrt.
 *
 * @property speedMps Windgeschwindigkeit in m/s, 0 = windstill.
 * @property fromDeg Richtung, AUS der er weht (0 = Nord, 90 = Ost).
 */
data class IndoorWind(val speedMps: Double, val fromDeg: Double) {
    companion object {
        val CALM = IndoorWind(0.0, 0.0)
    }
}

/** Art eines Abschnitts einer ERG-Einheit — fuer Farbe und Beschriftung im HUD. */
enum class ErgStepKind { WARMUP, WORK, RECOVER, STEADY, COOLDOWN }

/**
 * Ein Abschnitt einer ERG-Einheit.
 *
 * @property ftpFraction Zielleistung als Anteil der FTP (0,65 = 65 %).
 */
data class ErgStep(val kind: ErgStepKind, val durationS: Int, val ftpFraction: Double)

/**
 * Eine Einheit im ERG-Modus — abgeleitet aus der Planeinheit des Tages
 * (`ergWorkoutFor` in `IndoorCoreEntry.kt`).
 *
 * @property title Titel der Planeinheit in der Sprache der Oberflaeche — die
 *   App baut ihn ueber `sessionTitle(session, texts)` und reicht ihn hinein.
 */
data class ErgWorkout(val title: String, val steps: List<ErgStep>) {
    val totalS: Int get() = steps.sumOf { it.durationS }
}

/** Gegen wen gefahren wird. */
sealed interface GhostSpec {
    /** Kein Geist. */
    data object None : GhostSpec

    /** Ein gedachter Fahrer mit gleicher Masse, der konstant [watts] tritt. */
    data class ConstantPower(val watts: Double) : GhostSpec

    /**
     * Eine eigene fruehere Fahrt auf dieser Strecke.
     *
     * @property label Anzeigename („12.10.2026"), von der App formatiert.
     * @property points die Punkte der frueheren Tour mit Zeitstempeln; die
     *   Sitzung bildet sie auf die Laufmeter der Strecke ab. Punkte ohne Zeit
     *   machen den Geist unbrauchbar (dann faehrt keiner mit).
     */
    data class PastRide(val label: String, val points: List<TrackPoint>) : GhostSpec

    /**
     * Eine geteilte Herausforderung (`core/.../challenge`): die Kurve einer
     * fremden Fahrt auf derselben Strecke. Faehrt wie [PastRide], nur ohne
     * Punkte — die Kurve liegt schon in Laufmetern vor.
     *
     * @property label Anzeigename des Absenders.
     */
    data class Shared(val label: String, val curve: de.trailscape.core.challenge.ChallengeCurve) : GhostSpec
}

/** Alles, was eine Sitzung von Anfang an wissen muss. */
data class IndoorSessionConfig(
    val route: IndoorRoute,
    val mode: IndoorMode,
    val rider: RiderSetup,
    val wind: IndoorWind = IndoorWind.CALM,
    /** Pflicht bei [IndoorMode.ERG], sonst ohne Bedeutung. */
    val workout: ErgWorkout? = null,
    val ghost: GhostSpec = GhostSpec.None,
    /** Was der Trainer kann — entscheidet z. B., ob ERG ueberhaupt geht. */
    val capabilities: TrainerCapabilities = TrainerCapabilities.NONE,
    /** Wanduhrzeit des Starts (ms seit Epoch) — nur fuer die Zeitstempel der gespeicherten Punkte. */
    val startedAtEpochMs: Long,
)

/** Was die Laufzeit je Schritt hereinreicht. */
data class SessionInput(
    /** Das letzte Paket des Trainers; `null`, solange nie eines kam. */
    val telemetry: TrainerTelemetry?,
    /** Puls vom Gurt oder von der Uhr, falls vorhanden. */
    val hrBpm: Int? = null,
    /** Ob der Trainer gerade Befehle annimmt ([TrainerConnection.READY]). */
    val trainerReady: Boolean = true,
)

/** Ablaufphase der Sitzung. */
enum class IndoorPhase {
    /** Angelegt, wartet auf den ersten Tritt. */
    READY,
    RUNNING,

    /** Vom Nutzer angehalten. */
    PAUSED,

    /** Von selbst angehalten: keine Leistung, oder der Trainer ist weg. */
    AUTO_PAUSED,

    /** Beendet — [IndoorSession.finish] wurde gerufen oder das Ziel einer Strecke ohne Rundschluss ist erreicht. */
    FINISHED,
}

/**
 * Stand der virtuellen Schaltung.
 *
 * @property index aktueller Gang, 1 = leichtester.
 * @property count Zahl der Gaenge ([VIRTUAL_GEAR_COUNT]).
 * @property ratio Uebersetzung des Gangs (Kettenblatt/Ritzel), z. B. 2,4.
 */
data class GearState(val index: Int, val count: Int, val ratio: Double)

/** Zahl der virtuellen Gaenge. */
const val VIRTUAL_GEAR_COUNT: Int = 24

/**
 * Stand des Geists relativ zum Fahrer.
 *
 * @property distM Laufmeter des Geists seit dem Start (ueber Runden fortlaufend).
 * @property gapM Vorsprung des Geists in Metern (positiv = Geist ist vorn).
 * @property gapS derselbe Abstand in Sekunden beim aktuellen Tempo des
 *   Hinteren; `null`, solange der steht.
 * @property powerW Leistung des Geists jetzt (fruehere Fahrt: gleitendes
 *   10-s-Mittel ihres Leistungsverlaufs, konstante Leistung: deren Wert);
 *   `null`, wenn unbekannt oder er im Ziel steht. Ergaenzt fuer
 *   „Draussen gefahren → drinnen nachfahren" (docs/indoor/app.md).
 */
data class GhostState(val distM: Double, val speedMps: Double, val gapM: Double, val gapS: Double?, val powerW: Double? = null)

/**
 * Stand der ERG-Einheit.
 *
 * @property stepIndex Index in [ErgWorkout.steps].
 * @property targetW Zielleistung des Abschnitts in Watt.
 * @property stepRemainingS Restzeit des Abschnitts.
 * @property done die Einheit ist durch (danach faehrt die Sitzung locker im letzten Ziel weiter).
 */
data class ErgState(val stepIndex: Int, val targetW: Int, val stepRemainingS: Int, val done: Boolean)

/**
 * Summen der Fahrt bis jetzt.
 *
 * @property normalizedPowerW gleitende 30-s-Normalleistung; `null` vor der ersten vollen halben Minute.
 */
data class IndoorTotals(
    val distanceM: Double,
    val ascentM: Double,
    val movingS: Int,
    val avgPowerW: Double?,
    val normalizedPowerW: Double?,
    val avgCadenceRpm: Double?,
    val avgHrBpm: Double?,
    val workKj: Double,
)

/**
 * Der vollstaendige Zustand nach einem Schritt — unveraenderlich; HUD und
 * Darstellung lesen nur ihn.
 *
 * @property nowMs die Zeit des Schritts (monotone Uhr) — der Bezug fuer die
 *   Interpolation der Darstellung.
 * @property elapsedS Sekunden seit dem ersten Tritt, Pausen eingeschlossen.
 * @property distM Laufmeter seit dem Start, ueber Runden fortlaufend.
 * @property routeDistM Stelle innerhalb der Runde (`distM` gefaltet, siehe `sampleAt`).
 * @property lap laufende Runde, ab 1.
 * @property speedMps Tempo der virtuellen Fahrt in m/s.
 * @property powerW Leistung als 3-s-Mittel; `null`, solange der Trainer nichts liefert.
 * @property gradePct Steigung der Strecke an der Stelle (nicht die an den Trainer gesendete).
 * @property crr wirksamer Rollwiderstand an der Stelle.
 * @property headwindMps Gegenwindanteil in Fahrtrichtung (negativ = Rueckenwind).
 */
data class IndoorSessionState(
    val phase: IndoorPhase,
    val mode: IndoorMode,
    val nowMs: Long,
    val elapsedS: Int,
    val distM: Double,
    val routeDistM: Double,
    val lap: Int,
    val speedMps: Double,
    val powerW: Int?,
    val cadenceRpm: Int?,
    val hrBpm: Int?,
    val gradePct: Double,
    val surface: SurfaceKind,
    val crr: Double,
    val headwindMps: Double,
    val gear: GearState,
    val ghost: GhostState?,
    val erg: ErgState?,
    val totals: IndoorTotals,
)

/** Ergebnis eines Schritts: der neue Zustand und die Befehle, die jetzt an den Trainer gehen. */
data class IndoorStep(val state: IndoorSessionState, val commands: List<TrainerCommand>)

/**
 * Die Sitzung. Nicht threadsicher — alle Aufrufe vom selben Thread (dem der
 * Laufzeit-Schleife).
 *
 * ## Vertrag
 *  * [step] mit streng steigendem `nowMs`. Ein Abstand ueber 2 s (App war im
 *    Hintergrund, Haenger) gilt nicht als gefahrene Zeit: Die Sitzung rechnet
 *    hoechstens 2 s weiter.
 *  * Befehle entstehen sparsam: SIM-Vorgaben hoechstens einmal je Sekunde und
 *    nur bei merklicher Aenderung, sofort dagegen beim Schalten; ERG-Ziele beim
 *    Abschnittswechsel. Die Laufzeit schickt sie in der gelieferten Reihenfolge.
 *  * Aufgezeichnet wird ein Punkt je Sekunde, solange die Phase
 *    [IndoorPhase.RUNNING] ist.
 *  * [finish] ist endgueltig; danach liefert [step] den letzten Zustand ohne Befehle.
 */
interface IndoorSession {
    /** Der Zustand nach dem letzten Schritt (vor dem ersten: Phase [IndoorPhase.READY]). */
    val state: IndoorSessionState

    fun step(nowMs: Long, input: SessionInput): IndoorStep

    /** Schaltwunsch (Taster oder Bildschirmknopf). Wirkt im naechsten [step]; im ERG-Modus ohne Wirkung. */
    fun shift(event: ShifterEvent)

    /** Hebt bzw. senkt die Zielleistung der ERG-Einheit dauerhaft um [deltaPct] Prozentpunkte der FTP (Bereich −30..+30). */
    fun adjustErg(deltaPct: Int)

    fun pause()

    fun resume()

    /** Beendet die Fahrt und liefert die Aufzeichnung. Mehrfacher Aufruf liefert dieselbe. */
    fun finish(): IndoorRideRecord
}

/**
 * Die fertige Aufzeichnung einer Indoor-Fahrt — die App macht daraus eine
 * `Ride` mit `indoor = true` (Slice indoor-app).
 *
 * @property points ein Punkt je gefahrener Sekunde: Lage und Hoehe der
 *   **virtuellen** Stelle auf der Strecke, Zeit als Wanduhrzeit
 *   (`startedAtEpochMs` + verstrichene Zeit), dazu `power`, `cad`, `hr`.
 * @property stats Kennzahlen aus genau diesen Punkten (`computeStats`), damit
 *   Liste und Detail dieselben Zahlen zeigen wie bei jeder Tour.
 * @property ghostFinalGapS Abstand zum Geist am Ende (positiv = Geist vorn); `null` ohne Geist.
 * @property ghostSection der mit dem Geist verglichene Abschnitt (Zeit und
 *   Leistung beider, [GhostSection]); `null` ohne Geist oder ohne gefahrene
 *   Meter. Ergaenzt fuer den Vergleich mit der Aussenfahrt.
 */
data class IndoorRideRecord(
    val startedAtEpochMs: Long,
    val routeId: String,
    val routeName: String,
    val mode: IndoorMode,
    val points: List<TrackPoint>,
    val stats: RideStats,
    val totals: IndoorTotals,
    val laps: Int,
    val ghostFinalGapS: Double?,
    val ghostSection: GhostSection? = null,
)
