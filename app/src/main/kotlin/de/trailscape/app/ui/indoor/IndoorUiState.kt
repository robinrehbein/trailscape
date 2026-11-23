package de.trailscape.app.ui.indoor

import de.trailscape.app.indoor.IndoorProblem
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.ShifterConnection
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.VirtualGearTable
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.WorldAttribution
import de.trailscape.core.indoor.world.WorldBuildFailed
import de.trailscape.core.indoor.world.WorldBuildPhase

/**
 * # Zustaende des Indoor-Ablaufs fuer die Oberflaeche
 *
 * Die Bildschirme unter `ui/indoor/` sind **zustandslos**: Sie bekommen genau
 * diese Typen und melden Aktionen ueber Rueckrufe. Den Zustand haelt das
 * `IndoorViewModel`; Screenshot-Tests bauen die Typen von Hand.
 *
 * Ablauf (docs/indoor.md, Abschnitt 10.1):
 * Koppeln → Strecke und Modus → Welt laden → Fahren → Auswertung.
 */
enum class IndoorStage { PAIR, ROUTE, WORLD, RIDE, SUMMARY }

// ---------------------------------------------------------------------------
// Koppeln
// ---------------------------------------------------------------------------

/**
 * @property demoTrainer der Schalter „Demo-Trainer" ist an — dann wird kein
 *   echter Trainer gebraucht.
 * @property trainerName Name des gemerkten Trainers, `null` = keiner gemerkt.
 * @property shifterName Name des gemerkten Schalttasters, `null` = keiner.
 * @property chainringTeeth Kettenblatt am Trainer in Zaehnen (Ritzel: Zwift
 *   Cog, 14 Zaehne) — Bezug der virtuellen Gaenge.
 * @property gearTable Gangtabelle der virtuellen Gaenge (`RiderSetup.gearTable`).
 * @property canContinue Demo an oder ein Trainer gemerkt.
 */
data class IndoorPairUi(
    val demoTrainer: Boolean,
    val trainerName: String?,
    val shifterName: String?,
    val chainringTeeth: Int = IndoorViewModel.CHAINRING_DEFAULT_TEETH,
    val gearTable: VirtualGearTable = VirtualGearTable.EVEN,
) {
    val canContinue: Boolean get() = demoTrainer || trainerName != null
}

// ---------------------------------------------------------------------------
// Strecke und Modus
// ---------------------------------------------------------------------------

/** Eine waehlbare Strecke. */
sealed interface IndoorRouteOption {
    val key: String

    /** Die eingebaute Testwelt (ohne Netz, etwa 3 km). */
    data object TestWorld : IndoorRouteOption {
        override val key: String = "testwelt"
    }

    /**
     * Eine gespeicherte Planung oder gefahrene Tour.
     *
     * @property planned `true` = gespeicherte Planung, `false` = gefahrene Tour
     *   (dann auch als Geist waehlbar).
     * @property hasWorld fuer diese Strecke liegt schon eine fertige Welt bereit.
     */
    data class Saved(
        val rideId: String,
        val name: String,
        val distanceKm: Double,
        val ascentM: Double,
        val createdAt: Long,
        val planned: Boolean,
        val hasWorld: Boolean = false,
    ) : IndoorRouteOption {
        override val key: String get() = "ride:$rideId"
    }
}

/** Wer mitfaehrt. */
sealed interface IndoorGhostOption {
    data object None : IndoorGhostOption

    /** Die gewaehlte gefahrene Tour selbst als frueheres Ich. */
    data class PastRide(val rideId: String, val label: String) : IndoorGhostOption

    /** Ein gedachter Fahrer mit fester Leistung. */
    data class ConstantPower(val watts: Int) : IndoorGhostOption
}

/**
 * @property ergTitle Titel der Einheit des Tages, wenn sie sich fuer die Rolle
 *   eignet; `null` = ERG nicht waehlbar.
 * @property ergMinutes Dauer dieser Einheit in Minuten.
 * @property ghostPastRideAvailable die gewaehlte Strecke ist eine gefahrene Tour mit Zeitstempeln.
 * @property defaultGhostWatts Vorschlag fuer die Vergleichsleistung (aus der FTP).
 */
data class IndoorRouteUi(
    val options: List<IndoorRouteOption>,
    val selected: IndoorRouteOption?,
    val mode: IndoorMode,
    val ergTitle: String?,
    val ergMinutes: Int?,
    val ghost: IndoorGhostOption,
    val ghostPastRideAvailable: Boolean,
    val defaultGhostWatts: Int,
    val loadingOptions: Boolean = false,
) {
    val canContinue: Boolean get() = selected != null && (mode == IndoorMode.SIM || ergTitle != null)
}

// ---------------------------------------------------------------------------
// Welt laden
// ---------------------------------------------------------------------------

/** Schritt „Welt laden". */
sealed interface IndoorWorldUi {
    /** Strecke wird aufbereitet (Belagsabgleich, Voranschlag). */
    data object Preparing : IndoorWorldUi

    /**
     * Voranschlag steht, noch nichts geladen.
     *
     * @property hosts alle Hosts, die angefragt wuerden.
     * @property aerialLabels Laender mit Luftbild; leer = erzeugter Boden.
     * @property surfaceEstimated die Belaege sind nur geschaetzt (kein Abgleich).
     */
    data class Plan(
        val routeName: String,
        val distanceKm: Double,
        val quality: IndoorQuality,
        val downloadBytes: Long,
        val diskBytes: Long,
        val hosts: List<String>,
        val aerialLabels: List<String>,
        val surfaceSource: IndoorRouteSource,
        val resumable: Boolean = false,
    ) : IndoorWorldUi

    /**
     * Der Bau laeuft. [fraction] 0..1 ueber alle Phasen.
     *
     * @property cancelling „Abbrechen" ist getippt; der Weltbau rechnet die
     *   gerade laufenden Kacheln bzw. Anfragen noch zu Ende (Sekunden), erst
     *   dann kommt der Voranschlag mit „Fortsetzen" zurueck.
     */
    data class Building(
        val phase: WorldBuildPhase,
        val done: Int,
        val total: Int,
        val fraction: Float,
        val cancelling: Boolean = false,
    ) : IndoorWorldUi

    /** Die Welt ist fertig (gerade gebaut oder schon vorhanden). */
    data class Ready(val routeName: String, val attribution: List<WorldAttribution>) : IndoorWorldUi

    /**
     * Kein Weltbau moeglich oder gescheitert — „Ohne Welt fahren" bleibt.
     *
     * @property reason Grund des Weltbaus, `null` = ein anderer Fehler ([problem]).
     * @property detail technische Einzelheit zum Melden (`technicalDetailOf`),
     *   kopierbar unter dem Text; `null` = keine bekannt.
     */
    data class Failed(
        val reason: WorldBuildFailed.Reason?,
        val problem: IndoorProblem?,
        val canRetry: Boolean,
        val detail: String? = null,
    ) : IndoorWorldUi

    /** Strecke liess sich gar nicht aufbereiten — zurueck zur Streckenwahl. */
    data class RouteUnavailable(val problem: IndoorProblem?) : IndoorWorldUi
}

// ---------------------------------------------------------------------------
// Fahren
// ---------------------------------------------------------------------------

/** Was die 3D-Flaeche gerade kann. */
sealed interface IndoorRenderUi {
    /** Ohne Welt gefahren (gewaehlt oder ohne Weltbau): keine 3D-Flaeche, Hoehenprofil gross. */
    data object NoWorld : IndoorRenderUi

    data class Loading(val loadedTiles: Int, val neededTiles: Int) : IndoorRenderUi

    data object Ready : IndoorRenderUi

    /** Meldung statt 3D-Flaeche; die Fahrt laeuft weiter. */
    data class Failed(val failure: RenderFailure?, val problem: IndoorProblem?) : IndoorRenderUi
}

/** Hoehenprofil der Strecke, fuer das HUD vorberechnet (hoechstens einige hundert Stuetzpunkte). */
data class IndoorProfileUi(val lengthM: Double, val distM: FloatArray, val eleM: FloatArray, val isLoop: Boolean) {
    override fun equals(other: Any?): Boolean =
        other is IndoorProfileUi && lengthM == other.lengthM && isLoop == other.isLoop &&
            distM.contentEquals(other.distM) && eleM.contentEquals(other.eleM)

    override fun hashCode(): Int = (lengthM.hashCode() * 31 + distM.contentHashCode()) * 31 + eleM.contentHashCode()
}

/** Ein Hinweis waehrend der Fahrt (oben eingeblendet, verschwindet von selbst oder auf Tipp). */
enum class IndoorNotice {
    /** Trainer-Verbindung weg, Fahrt pausiert automatisch. */
    TRAINER_LOST,

    /** Trainer wieder da. */
    TRAINER_BACK,

    /** Trainer nimmt Befehle nicht an (Widerstand folgt der Strecke nicht). */
    TRAINER_COMMAND_FAILED,

    /** Trainer spricht kein FTMS / verweigert die Kontrolle. */
    TRAINER_UNSUPPORTED,

    /** Schalttaster nicht nutzbar — Knoepfe auf dem Bildschirm. */
    SHIFTER_UNSUPPORTED,

    /** Bluetooth aus / keine Berechtigung. */
    BLUETOOTH_OFF,
    NO_PERMISSION,

    /** Eine Kachel fehlt — dort ist die Welt lueckenhaft. */
    TILE_FAILED,

    /** Laufzeitfehler eines Teils; die Fahrt laeuft weiter, so gut es geht. */
    PART_FAILED,
}

/**
 * Alles, was das HUD zeigt.
 *
 * @property session der letzte Zustand der Sitzung; `null`, solange sie nicht
 *   laeuft (Start scheiterte → [startProblem]).
 * @property demoPowerW / [demoCadenceRpm] Reglerstand des Demo-Trainers; `null` = echter Trainer.
 * @property ghostLabel Anzeigename des Geists, `null` = keiner.
 */
data class IndoorRideUi(
    val routeName: String,
    val session: IndoorSessionState?,
    val trainerName: String,
    val trainer: TrainerConnection,
    val shifter: ShifterConnection?,
    val shifterBatteryPct: Int?,
    val render: IndoorRenderUi,
    val camera: CameraMode,
    val profile: IndoorProfileUi?,
    val notice: IndoorNotice?,
    val demoPowerW: Int?,
    val demoCadenceRpm: Int?,
    val ghostLabel: String?,
    val ergTitle: String?,
    val attribution: List<WorldAttribution>,
    val startProblem: IndoorProblem? = null,
    val runtimeProblem: IndoorProblem? = null,
)

// ---------------------------------------------------------------------------
// Auswertung
// ---------------------------------------------------------------------------

/**
 * @property tooShort unter 60 s — wird nicht gespeichert.
 * @property recordMissing die Aufzeichnung fehlt (ein Teil scheiterte beim
 *   Beenden) — nichts zu speichern; Grund in [recordProblem], falls bekannt.
 * @property ghostGapS Abstand zum Geist am Ende (positiv = Geist vorn), `null` ohne Geist.
 * @property savedRideId gesetzt, sobald gespeichert.
 */
data class IndoorSummaryUi(
    val routeName: String,
    val mode: IndoorMode,
    val distanceKm: Double,
    val movingS: Int,
    val ascentM: Double,
    val avgPowerW: Int?,
    val normalizedPowerW: Int?,
    val avgCadenceRpm: Int?,
    val avgHrBpm: Int?,
    val workKj: Int,
    val avgSpeedKmh: Double,
    val laps: Int,
    val ghostLabel: String?,
    val ghostGapS: Double?,
    val tooShort: Boolean,
    val recordMissing: Boolean = false,
    val recordProblem: IndoorProblem? = null,
    val saving: Boolean = false,
    val savedRideId: String? = null,
    val saveFailed: Boolean = false,
)

// ---------------------------------------------------------------------------
// Weltverwaltung
// ---------------------------------------------------------------------------

/**
 * Eine fertige oder angefangene Welt auf dem Geraet.
 *
 * @property orphaned die Strecke dazu gibt es nicht mehr — zum Loeschen angeboten.
 * @property complete `manifest.json` liegt vor.
 */
data class IndoorWorldEntryUi(
    val worldId: String,
    val routeName: String,
    val quality: IndoorQuality?,
    val bytes: Long,
    val createdAt: Long?,
    val attribution: List<WorldAttribution>,
    val orphaned: Boolean,
    val complete: Boolean,
)

data class IndoorWorldsUi(
    val worlds: List<IndoorWorldEntryUi>,
    val totalBytes: Long,
    val cacheBytes: Long,
    val loading: Boolean = false,
)
