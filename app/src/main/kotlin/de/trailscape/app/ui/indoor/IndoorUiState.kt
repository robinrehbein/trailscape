package de.trailscape.app.ui.indoor

import de.trailscape.app.indoor.IndoorProblem
import de.trailscape.core.indoor.GhostSection
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.ShifterConnection
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.VirtualGearTable
import de.trailscape.core.indoor.sky.IndoorTimeOfDay
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
 * Ablauf (docs/indoor/app.md, „Nutzerfluss"): Der Ablauf beginnt auf der
 * **Startseite** [ROUTE] (Trainer-Status, Streckenliste, „Fahren"). Von dort
 * fuehren zwei Nebenseiten weg und wieder zurueck: [PAIR] (Trainer koppeln)
 * und [SETTINGS] (Einstellungen, Erweitert). „Fahren" fuehrt ueber [WORLD]
 * (Strecke vorbereiten, nur wenn noetig sichtbar) in die Fahrt [RIDE] und
 * danach in die Auswertung [SUMMARY].
 */
enum class IndoorStage {
    /** Startseite: Trainer, Strecke, Optionen, „Fahren". */
    ROUTE,

    /** Nebenseite der Startseite: Trainer und Schalttaster koppeln, Demo-Trainer. */
    PAIR,

    /** Nebenseite der Startseite: Fahrgefuehl, 3D-Ansicht, Erweitert. */
    SETTINGS,

    /** Strecke vorbereiten (Voranschlag, Bau, Fehler) bzw. „Bereit". */
    WORLD,
    RIDE,
    SUMMARY,

    /** 3D-Vorschau einer Strecke ohne Trainer (Idee 4, docs/indoor/vorschau.md). */
    PREVIEW,
}

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
 * @property timeOfDay Tageszeit der 3D-Welt (Vorgabe: automatisch nach der Uhr).
 * @property otherRiders andere Radfahrer in der 3D-Welt anzeigen (rein dekorativ, Vorgabe an).
 * @property graphics Grafikstufe der 3D-Ansicht (Vorgabe Standard = wie bisher).
 * @property canContinue Demo an oder ein Trainer gemerkt.
 */
data class IndoorPairUi(
    val demoTrainer: Boolean,
    val trainerName: String?,
    val shifterName: String?,
    val chainringTeeth: Int = IndoorViewModel.CHAINRING_DEFAULT_TEETH,
    val gearTable: VirtualGearTable = VirtualGearTable.EVEN,
    val timeOfDay: IndoorTimeOfDay = IndoorTimeOfDay.AUTO,
    val otherRiders: Boolean = true,
    /** „Einblendungen auf der Strecke" (Bergwertung, Kilometer) im HUD, Vorgabe an. */
    val trailOverlays: Boolean = true,
    val graphics: de.trailscape.core.indoor.world.IndoorGraphics = de.trailscape.core.indoor.world.IndoorGraphics.STANDARD,
    val bluetoothOn: Boolean = true,
    val permissionGranted: Boolean = true,
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
        val durationS: Int? = null,
        val previewPoints: List<Float> = emptyList(),
    ) : IndoorRouteOption {
        override val key: String get() = "ride:$rideId"
    }

    /**
     * Eine bekannte Strecke aus dem Katalog mit fertig geladener Welt
     * (docs/indoor/catalog.md). Waehlbar nur, wenn die Welt bereit ist.
     *
     * @property name Anzeigename in der aktuellen Sprache.
     */
    data class Catalog(
        val catalogId: String,
        val name: String,
        val distanceKm: Double,
        val ascentM: Double,
    ) : IndoorRouteOption {
        override val key: String get() = "catalog:$catalogId"
    }

    /**
     * Die generierte Welt zur Einheit des Tages (docs/indoor/app.md): eine
     * erfundene Landschaft aus dem Zielprofil [profile], gebaut auf dem Geraet
     * ohne Netz. Nicht in der Liste, nur ueber die Karte „Einheit des Tages".
     */
    data class Generated(
        val profile: de.trailscape.core.indoor.generated.GeneratedProfile,
        val name: String,
    ) : IndoorRouteOption {
        override val key: String get() = "generated:${java.lang.Long.toHexString(profile.seed)}"
        val distanceKm: Double get() = profile.lengthM / 1000.0
    }
}

/** Wer mitfaehrt. */
sealed interface IndoorGhostOption {
    data object None : IndoorGhostOption

    /** Die gewaehlte gefahrene Tour selbst als frueheres Ich. */
    data class PastRide(val rideId: String, val label: String) : IndoorGhostOption

    /** Ein gedachter Fahrer mit fester Leistung. */
    data class ConstantPower(val watts: Int) : IndoorGhostOption

    /**
     * Eine geteilte Herausforderung (docs/herausforderung.md): die Fahrt einer
     * anderen Person auf derselben Strecke. [label] ist deren Anzeigename.
     */
    data class Challenge(val challengeId: String, val label: String) : IndoorGhostOption
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
// Bekannte Strecken (Katalog, docs/indoor/catalog.md)
// ---------------------------------------------------------------------------

/** Warum ein Katalogpaket nicht geladen werden konnte. */
enum class IndoorCatalogFailure {
    /** Das Paket (oder der Katalog im Release) ist noch nicht veroeffentlicht (404). */
    NOT_AVAILABLE,

    /** Kein Netz oder Verbindung abgebrochen — „Fortsetzen" setzt am Stand fort. */
    OFFLINE,

    /** Pruefsumme oder Groesse stimmen nicht; das Paket wurde verworfen. */
    CHECKSUM,

    /** Kein Platz auf dem Geraet. */
    NO_SPACE,

    /** Paket falsch aufgebaut oder sonst ein Fehler. */
    OTHER,
}

/** Zustand einer bekannten Strecke. */
sealed interface IndoorCatalogState {
    /** Nichts auf dem Geraet. */
    data object NotLoaded : IndoorCatalogState

    /** Ein angefangener Download liegt vor; „Fortsetzen" setzt dort an. */
    data class Paused(val doneBytes: Long, val totalBytes: Long) : IndoorCatalogState

    /** Laedt. [totalBytes] 0 = unbekannt. */
    data class Downloading(val doneBytes: Long, val totalBytes: Long, val cancelling: Boolean = false) : IndoorCatalogState {
        val fraction: Float get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
    }

    /** Geladen, wird geprueft und entpackt. */
    data object Installing : IndoorCatalogState

    /** Welt liegt bereit. */
    data object Ready : IndoorCatalogState

    /** Welt liegt bereit, im Katalog gibt es eine neuere Version. */
    data class UpdateAvailable(val installedVersion: Int, val newVersion: Int) : IndoorCatalogState

    /**
     * Laden scheiterte. [installed]: eine aeltere Version liegt weiter bereit.
     * [doneBytes] > 0: ein Teil ist geladen und kann fortgesetzt werden.
     */
    data class Failed(
        val reason: IndoorCatalogFailure,
        val detail: String? = null,
        val installed: Boolean = false,
        val doneBytes: Long = 0L,
    ) : IndoorCatalogState
}

/**
 * Eine Karte im Bereich „Bekannte Strecken". Texte in beiden Sprachen; die
 * Oberflaeche waehlt nach der App-Sprache.
 *
 * @property surfaces Anteile der Belagsklassen, groesste zuerst.
 * @property laser Welt in Laserqualitaet (Kennzeichen „Laser").
 * @property profile ausgeduenntes Hoehenprofil (Katalog, sonst die geladene Welt), `null` = keins.
 * @property previewPath zwischengespeichertes Vorschaubild (JPEG), `null` = keins (noch nicht).
 * @property isNew kam mit dem letzten Abruf des Release-Katalogs neu dazu (Kennzeichen „Neu").
 */
data class IndoorCatalogItemUi(
    val id: String,
    val name: de.trailscape.core.indoor.catalog.CatalogText,
    val region: de.trailscape.core.indoor.catalog.CatalogText,
    val description: de.trailscape.core.indoor.catalog.CatalogText,
    val country: String,
    val kind: de.trailscape.core.indoor.catalog.CatalogRideKind,
    val distanceKm: Double,
    val ascentM: Double,
    val surfaces: List<Pair<de.trailscape.core.indoor.SurfaceKind, Double>>,
    val packageBytes: Long,
    val aerial: Boolean,
    val state: IndoorCatalogState,
    val laser: Boolean = false,
    val profile: de.trailscape.core.indoor.catalog.CatalogProfile? = null,
    val previewPath: String? = null,
    val isNew: Boolean = false,
    /** Platz des entpackten Pakets (Katalog), 0 = unbekannt. */
    val diskBytes: Long = 0L,
    /** Quelle des Luftbilds (Katalog), `null` = keine. */
    val aerialSource: String? = null,
) {
    /** Eine Welt liegt bereit (auch wenn es eine neuere gibt). */
    val ready: Boolean
        get() = state is IndoorCatalogState.Ready || state is IndoorCatalogState.UpdateAvailable ||
            (state is IndoorCatalogState.Failed && state.installed)
}

/** Ergebnis von „Katalog aktualisieren". */
enum class IndoorCatalogRefresh { UPDATED, NOT_AVAILABLE, FAILED }

data class IndoorCatalogUi(
    val items: List<IndoorCatalogItemUi>,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val refreshResult: IndoorCatalogRefresh? = null,
)

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

    /**
     * Die Welt ist fertig (gerade gebaut oder schon vorhanden).
     *
     * @property fromServer die Welt kommt vom Server-Bau (docs/indoor/serverbau.md).
     * @property serverLaser Laser-Quelle des Server-Baus, `null` = ohne Laser.
     * @property replacedDeviceWorld sie hat gerade eine auf dem Geraet gebaute Welt derselben Strecke ersetzt.
     */
    data class Ready(
        val routeName: String,
        val attribution: List<WorldAttribution>,
        val fromServer: Boolean = false,
        val serverLaser: String? = null,
        val replacedDeviceWorld: Boolean = false,
    ) : IndoorWorldUi

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
 * Startpunkt waehlen (docs/indoor/app.md, „Startpunkt und Fortsetzen") — nur
 * gesetzt, solange sich der Fahrer versetzen laesst: vor dem ersten Tritt und
 * in der Pause.
 *
 * @property selectedM Stelle des Fahrers in Metern innerhalb der Runde.
 * @property maxM spaeteste waehlbare Stelle (auf einer Strecke ohne
 *   Rundschluss bleibt bis zum Ziel ein Rest).
 * @property resumeM die gemerkte Stelle der letzten Fahrt auf dieser Strecke —
 *   angeboten als „Weiter bei km …" neben „Von vorn"; `null` = kein Angebot.
 * @property held die Fahrt wartet auf „Losfahren".
 * @property pedalStarts mit echtem Trainer, solange der Startpunkt nicht
 *   angefasst wurde: der erste Tritt startet ebenso (sonst bewegt Treten nichts).
 */
data class IndoorStartUi(
    val selectedM: Double,
    val maxM: Double,
    val resumeM: Double?,
    val held: Boolean,
    val pedalStarts: Boolean = false,
)

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
    /** Startpunkt waehlen; `null` = gerade nicht moeglich (es wird gefahren). */
    val start: IndoorStartUi? = null,
    /** Einheit oder Strecke ist zu Ende, die andere nicht ([IndoorDayEndUi]); `null` = nichts. */
    val dayEnd: IndoorDayEndUi? = null,
    /** Der Mitfahrer ist die eigene Aussenfahrt: das HUD zeigt auch ihre Leistung an ihrer Stelle. */
    val ghostOutdoor: Boolean = false,
    /** Bergwertung und Kilometermarken (`IndoorTrail.kt`); `null` = nichts zu zeigen oder abgeschaltet. */
    val trail: de.trailscape.core.indoor.climb.TrailLive? = null,
    /** Die Fahrt gehoert zu einem Raum fuer Gruppenfahrten ([RoomRideUi]); `null` = nicht. */
    val room: RoomRideUi? = null,
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
 * @property startKm wo die Fahrt begann, wenn nicht am Anfang der Strecke
 *   (fortgesetzt oder mittendrin gestartet); `null` = am Anfang.
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
    val startKm: Double? = null,
    /** Der Mitfahrer war die eigene Aussenfahrt („x s vor/hinter deiner Außenfahrt"). */
    val ghostOutdoor: Boolean = false,
    /** Vergleich mit der Aussenfahrt auf demselben Abschnitt; `null` ohne Aussenfahrt. */
    val ghostSection: GhostSection? = null,
    /** Gefahrene Anstiege mit Zeiten (Bergwertung), in Reihenfolge; leer = keine. */
    val climbs: List<de.trailscape.core.indoor.climb.ClimbEffort> = emptyList(),
    /** Name des Absenders, wenn die Fahrt gegen eine Herausforderung ging; sonst `null`. */
    val challengeOpponent: String? = null,
    /** Die Fahrt lässt sich als Herausforderung teilen (docs/herausforderung.md). */
    val canShareChallenge: Boolean = false,
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
    /** aus dem Katalog bekannter Strecken geladen (docs/indoor/catalog.md). */
    val fromCatalog: Boolean = false,
    /** auf dem eigenen Server gebaut (docs/indoor/serverbau.md). */
    val fromServer: Boolean = false,
    /** Laser-Quelle des Server-Baus (`nw`, `bw`), `null` = ohne Laser. */
    val serverLaser: String? = null,
)

data class IndoorWorldsUi(
    val worlds: List<IndoorWorldEntryUi>,
    val totalBytes: Long,
    val cacheBytes: Long,
    val loading: Boolean = false,
)
