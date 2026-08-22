package de.trailscape.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Zentrale Datentypen von Trailscape.
 *
 * 1:1-Portierung von `lib/models.dart` (Flutter-App). Die JSON-Formate sind
 * absichtlich bytegetreu kompatibel zum Selfhost-Sync-Server (server/) und
 * zur frueheren Web-App, damit bestehende Tour-/Trainings-Dateien der Nutzer
 * beim Umstieg auf die native App weiter lesbar bleiben: gleiche
 * Feldnamen, gleiche Nullable-/Default-Semantik, gleiche Zeitstempel-Form
 * (durchgehend ms seit Epoch als JSON-Zahl — Dart nutzt hier nirgends
 * `toIso8601String()`).
 */

/** Einzelner aufgezeichneter Trackpunkt. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    /** Hoehe in Metern. */
    val ele: Double? = null,
    /** Zeitstempel in ms seit Epoch. */
    val time: Long? = null,
    /**
     * Herzfrequenz in Schlaegen pro Minute, falls bekannt (z. B. aus einer
     * ueber Health Connect importierten Watch-Aufzeichnung). Optional und
     * wird nur serialisiert, wenn gesetzt — bestehende Tour-Dateien und der
     * Sync-Server bleiben damit unveraendert kompatibel.
     */
    val hr: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("lat", lat)
        put("lon", lon)
        ele?.let { put("ele", it) }
        time?.let { put("time", it) }
        hr?.let { put("hr", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): TrackPoint = TrackPoint(
            lat = json.requiredDouble("lat"),
            lon = json.requiredDouble("lon"),
            ele = json.optionalDouble("ele"),
            time = json.optionalLong("time"),
            hr = json.optionalInt("hr"),
        )
    }
}

/** Kennzahlen einer Fahrt. */
data class RideStats(
    val distanceKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationS: Int? = null,
    val movingTimeS: Int? = null,
    val avgSpeedKmh: Double? = null,
    /** Durchschnittliche Herzfrequenz in bpm, falls bekannt. */
    val avgHrBpm: Int? = null,
    /** Maximale Herzfrequenz in bpm, falls bekannt. */
    val maxHrBpm: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("distanceKm", distanceKm)
        // durationS/movingTimeS/avgSpeedKmh werden immer geschrieben (auch als
        // explizites JSON-`null`) — nur avgHrBpm/maxHrBpm werden bei Abwesenheit
        // ganz weggelassen. So bleibt das JSON fuer Touren ohne Herzfrequenz
        // identisch zum bisherigen Format (Sync-Server, Web-App).
        put("durationS", durationS)
        put("movingTimeS", movingTimeS)
        put("avgSpeedKmh", avgSpeedKmh)
        put("ascentM", ascentM)
        put("descentM", descentM)
        avgHrBpm?.let { put("avgHrBpm", it) }
        maxHrBpm?.let { put("maxHrBpm", it) }
    }

    companion object {
        /** Leere Stats wie `_emptyStats`/der Default-Fallback in `Ride.fromJson`. */
        val empty = RideStats(distanceKm = 0.0, ascentM = 0.0, descentM = 0.0)

        fun fromJson(json: JsonObject): RideStats = RideStats(
            distanceKm = json.optionalDouble("distanceKm") ?: 0.0,
            durationS = json.optionalInt("durationS"),
            movingTimeS = json.optionalInt("movingTimeS"),
            avgSpeedKmh = json.optionalDouble("avgSpeedKmh"),
            ascentM = json.optionalDouble("ascentM") ?: 0.0,
            descentM = json.optionalDouble("descentM") ?: 0.0,
            avgHrBpm = json.optionalInt("avgHrBpm"),
            maxHrBpm = json.optionalInt("maxHrBpm"),
        )
    }
}

/**
 * Die punktfreien Kerndaten einer Tour — das, was Listen, Trainingsauswertung
 * und Sync-Entscheidung brauchen, ohne die GPS-Punkte im Speicher zu halten.
 *
 * Implementiert von [Ride] (der vollen Tour) und [RideSummary] (dem
 * Index-Eintrag). Funktionen, die nur ueber Kennzahlen und Zeitstempel
 * rechnen (Wochenkilometer, Fitness-Einstufung, Duplikatpruefung, ...),
 * nehmen dieses Interface entgegen — sie laufen damit unveraendert ueber
 * volle Touren UND ueber Zusammenfassungen. Wer die Punkte wirklich braucht,
 * verlangt weiterhin ein [Ride].
 */
interface RideInfo {
    val id: String
    val name: String

    /** ms seit Epoch. */
    val createdAt: Long

    /** ms seit Epoch; siehe [Ride.updatedAt]. */
    val updatedAt: Long
    val stats: RideStats

    /** Siehe [Ride.planned]. */
    val planned: Boolean

    /**
     * Anzahl der Trackpunkte. Teil der Zusammenfassung, weil die
     * Duplikatpruefung ([findDuplicateRide]) sie braucht — sie vergleicht
     * Startzeitpunkt UND Punktzahl, ohne die Punktlisten selbst zu laden.
     */
    val pointCount: Int
}

/**
 * Punktfreie Zusammenfassung einer gespeicherten Tour — der Eintrag des
 * Touren-Index (`rides/index.json` in `:app`).
 *
 * Existiert, damit die Tourenliste nicht mehr saemtliche GPS-Punkte aller
 * Touren dauerhaft im RAM halten muss: Bei ~500 Touren × 4000 Punkten sind
 * das 200+ MB geboxter Nullable-Felder ([TrackPoint]). Die volle Tour wird
 * nur noch bei Bedarf geladen (Detailansicht, Kartenzeichnung, GPX-Export,
 * Sync-Push).
 *
 * ## Format
 * Eigenes JSON NUR fuer den Index — das Tour-Dateiformat ([Ride.toJson])
 * bleibt unangetastet und rueckwaertskompatibel. Der Index ist ein reiner
 * Cache: Fehlt er oder ist er kaputt, wird er aus den Tour-Dateien neu
 * aufgebaut.
 */
data class RideSummary(
    override val id: String,
    override val name: String,
    /** ms seit Epoch. */
    override val createdAt: Long,
    override val updatedAt: Long,
    override val stats: RideStats,
    override val planned: Boolean = false,
    override val pointCount: Int = 0,
) : RideInfo {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("pointCount", pointCount)
        if (planned) {
            put("planned", true)
        }
        put("stats", stats.toJson())
    }

    companion object {
        fun fromJson(json: JsonObject): RideSummary {
            val createdAt = json.requiredLong("createdAt")
            return RideSummary(
                id = json.requiredString("id"),
                name = json.requiredString("name"),
                createdAt = createdAt,
                updatedAt = json.optionalLong("updatedAt") ?: createdAt,
                pointCount = json.optionalInt("pointCount") ?: 0,
                planned = json.optionalBoolean("planned") ?: false,
                stats = (json.fieldOrNull("stats") as? JsonObject)?.let { RideStats.fromJson(it) }
                    ?: RideStats.empty,
            )
        }
    }
}

/** Eine aufgezeichnete Fahrt. */
data class Ride(
    override val id: String,
    override val name: String,
    /** ms seit Epoch. */
    override val createdAt: Long,
    override val stats: RideStats,
    val points: List<TrackPoint> = emptyList(),
    /**
     * `true`, wenn dieser Eintrag eine **Planung** ist und niemand dafuer im
     * Sattel sass — „Als Tour speichern" auf der Karte legt genau so einen
     * Eintrag an.
     *
     * ## Warum das ein eigenes Feld sein muss
     * Ohne Kennzeichen ist eine gespeicherte Planung von einer gefahrenen Tour
     * nicht zu unterscheiden. Der Wochenfortschritt sprang dann durch eine
     * reine Planungsaktion, und Fitness, Ermuedung und Form rechneten mit
     * Kilometern, die es nie gegeben hat. Wo „gefahren" gemeint ist, filtert
     * [riddenRides]; wo die Planung dazugehoert (Tourenliste, Export, Sync),
     * bleibt sie sichtbar und wird beschriftet.
     *
     * ## Rueckwaertskompatibilitaet
     * Das Feld wird **nur geschrieben, wenn es `true` ist** — dasselbe Muster
     * wie [TrackPoint.hr] und [RideStats.avgHrBpm]. Fuer jede gefahrene Tour
     * bleibt das JSON damit byteweise identisch zu bisher (Sync-Server,
     * Web-App, bestehende Sicherungen), und alte Dateien ohne den Schluessel
     * lesen sich als `false` — also als gefahren, was fuer alles, was vor
     * dieser Aenderung entstanden ist, auch stimmt.
     */
    override val planned: Boolean = false,
    /**
     * Zeitpunkt der letzten inhaltlichen Aenderung (ms seit Epoch) — der
     * Dreh- und Angelpunkt des bidirektionalen Syncs: [syncRides] entscheidet
     * per Last-Write-Wins ueber genau diesen Wert, welche Seite die neuere
     * Fassung einer Tour hat (Umbenennung, HF-Anreicherung, ...).
     *
     * ## Rueckwaertskompatibilitaet
     * Das Feld ist **nachtraeglich** ergaenzt. Alte Tour-Dateien (Flutter-App,
     * Web-App, bestehende Server-Bestaende) kennen den Schluessel nicht; beim
     * Lesen faellt [fromJson] dann auf [createdAt] zurueck — eine nie
     * bearbeitete Tour ist so alt wie ihre Aufzeichnung, was fuer alles vor
     * dieser Aenderung auch stimmt. Beim Schreiben wird der Schluessel
     * dagegen **immer** mitgeschrieben (angehaengt, hinter `planned`), damit
     * jede neu gespeicherte Datei sync-faehig ist; alte Leser ignorieren
     * unbekannte Schluessel.
     */
    override val updatedAt: Long = createdAt,
) : RideInfo {
    override val pointCount: Int get() = points.size

    /** Punktfreie Zusammenfassung dieser Tour (siehe [RideSummary]). */
    fun toSummary(): RideSummary = RideSummary(
        id = id,
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        stats = stats,
        planned = planned,
        pointCount = points.size,
    )

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("points", buildJsonArray { points.forEach { add(it.toJson()) } })
        put("stats", stats.toJson())
        // Angehaengt und nur im Ausnahmefall: siehe [planned]. Die Reihenfolge
        // der bestehenden Schluessel bleibt damit unangetastet.
        if (planned) {
            put("planned", true)
        }
        // Immer geschrieben (siehe [updatedAt]) — als letzter Schluessel,
        // damit alles davor byteweise beim alten Format bleibt.
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JsonObject): Ride {
            val createdAt = json.requiredLong("createdAt")
            return Ride(
                id = json.requiredString("id"),
                name = json.requiredString("name"),
                createdAt = createdAt,
                points = json.requiredArray("points").map { TrackPoint.fromJson(it.asRequiredObject()) },
                // Entspricht Darts `json['stats'] is Map<String, dynamic> ? ... : const RideStats(...)`:
                // fehlt 'stats' oder ist es kein Objekt, wird lautlos auf leere Stats zurueckgefallen.
                stats = (json.fieldOrNull("stats") as? JsonObject)?.let { RideStats.fromJson(it) } ?: RideStats.empty,
                planned = json.optionalBoolean("planned") ?: false,
                // Fehlender Schluessel = alte Datei: siehe [updatedAt].
                updatedAt = json.optionalLong("updatedAt") ?: createdAt,
            )
        }
    }
}

/**
 * Nur die tatsaechlich **gefahrenen** Touren — gespeicherte Planungen fallen
 * heraus (siehe [Ride.planned]).
 *
 * Die eine Stelle, an der diese Unterscheidung ausbuchstabiert ist. Jede
 * Auswertung, die „gefahren" meint, geht hierdurch; wer sie vergisst, zaehlt
 * Kilometer, die nie gefahren wurden.
 */
fun <T : RideInfo> riddenRides(rides: List<T>): List<T> = rides.filter { !it.planned }

/** Fitness-Stufen wie in der Flutter-App und der Web-Referenz. */
enum class FitnessLevel(
    /** Exakter Dart-Enum-Name (`FitnessLevel.name`), wie er im JSON steht. */
    val jsonName: String,
    val label: String,
) {
    EINSTEIGER("einsteiger", "Einsteiger"),
    FORTGESCHRITTEN("fortgeschritten", "Fortgeschritten"),
    AMBITIONIERT("ambitioniert", "Ambitioniert"),
    ;

    companion object {
        /** Entspricht Darts `FitnessLevel.values.byName(...)`: wirft bei unbekanntem Namen. */
        fun fromJsonName(name: String): FitnessLevel =
            entries.firstOrNull { it.jsonName == name }
                ?: throw MissingOrInvalidFieldException("Unbekannter FitnessLevel: '$name'")
    }
}

/** Entspricht der Dart-Konstante `levelLabels`. */
val levelLabels: Map<FitnessLevel, String> = FitnessLevel.entries.associateWith { it.label }

/** Ergebnis von [assessFitness]. */
data class FitnessAssessment(
    val level: FitnessLevel,
    val weeklyKm: Double,
    val weeklyHm: Double,
    val weeklyRides: Double,
    val longestRideKm: Double,
    val rideCount: Int,
)

/** Trainingsziel: Zieldistanz zu einem bestimmten Datum. */
data class Goal(
    val name: String,
    val distanceKm: Double,
    val ascentM: Double? = null,
    /**
     * Angestrebte Fahrzeit fuer das Ziel in Minuten — die sportliche Ambition
     * neben der reinen Distanz („120 km unter 6:30 h").
     *
     * `null` heisst: kein Zeitziel, das Ziel ist ein Distanzziel. Wie
     * [ascentM] wird der Schluessel immer geschrieben (auch als explizites
     * `null`); alte Plan-Dateien ohne den Schluessel lesen sich als `null`.
     * Die Bewertung einer Zielzeit (noetiger Schnitt, Prognose, Urteil) steht
     * in `GoalTime.kt` und ist bewusst kein Feld, sondern eine Rechnung ueber
     * dem gespeicherten Ziel.
     */
    val targetTimeMin: Int? = null,
    /** ms seit Epoch. */
    val date: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("distanceKm", distanceKm)
        put("ascentM", ascentM)
        put("targetTimeMin", targetTimeMin)
        put("date", date)
    }

    companion object {
        fun fromJson(json: JsonObject): Goal = Goal(
            name = json.requiredString("name"),
            distanceKm = json.requiredDouble("distanceKm"),
            ascentM = json.optionalDouble("ascentM"),
            targetTimeMin = json.optionalInt("targetTimeMin"),
            date = json.requiredLong("date"),
        )
    }
}

/** Art einer Trainingswoche. */
enum class WeekKind(
    /** Exakter Dart-Enum-Name (`WeekKind.name`), wie er im JSON steht. */
    val jsonName: String,
    val label: String,
) {
    AUFBAU("aufbau", "Aufbau"),
    ERHOLUNG("erholung", "Erholung"),
    TAPER("taper", "Taper"),
    ZIELWOCHE("zielwoche", "Zielwoche"),
    ;

    companion object {
        /** Entspricht Darts `WeekKind.values.byName(...)`: wirft bei unbekanntem Namen. */
        fun fromJsonName(name: String): WeekKind =
            entries.firstOrNull { it.jsonName == name }
                ?: throw MissingOrInvalidFieldException("Unbekannter WeekKind: '$name'")
    }
}

/** Entspricht der Dart-Konstante `weekKindLabels`. */
val weekKindLabels: Map<WeekKind, String> = WeekKind.entries.associateWith { it.label }

/**
 * Eine einzelne Trainingseinheit innerhalb einer [TrainingWeek].
 *
 * ## Intensitaet und Dauer sind Felder, keine Textfunde
 * Frueher las `classifySessionIntensity` die Intensitaet per Stichwortsuche aus
 * [title] zurueck („intervall", „locker", …). Damit haing die Routenwahl an
 * einer Formulierung: Wer „Intervalle" in „Schwellenblock" umbenannt haette,
 * haette lautlos aus einer harten eine Grundlageneinheit gemacht — ohne dass
 * ein Test etwas gemerkt haette. [intensity] und [durationMin] stehen deshalb
 * am Datensatz; erzeugt werden sie zusammen mit dem Text an genau einer Stelle
 * ([generatePlan]).
 *
 * ## Rueckwaertskompatibilitaet des Planformats
 * Die drei neuen Schluessel werden **hinten angehaengt**; `day`, `title`,
 * `description` und `targetKm` behalten Reihenfolge und Bedeutung. Beim Lesen
 * eines Plans ohne die neuen Schluessel greifen Defaults, die genau das alte
 * Verhalten nachbilden: [intensity] faellt auf die frueheren Titel-Stichwoerter
 * zurueck ([sessionIntensityFromTitle]), [durationMin] bleibt `null` und
 * [isEvent] erkennt das Zielevent an seinem festen Titelpraefix.
 */
data class TrainingSession(
    val day: String,
    val title: String,
    val description: String,
    val targetKm: Int,
    /** Wie hart die Einheit gefahren werden soll — steuert Tempoannahme und Routenprofil. */
    val intensity: SessionIntensity = SessionIntensity.GRUNDLAGE,
    /**
     * Vorgesehene Fahrzeit in Minuten bei planmaessigem Tempo; `null` bei
     * Plaenen aus der Zeit vor diesem Feld.
     *
     * Sie ist die Groesse, an der [description] und [targetKm] zusammenhaengen:
     * Der Text einer Intervalleinheit nennt Ein- und Ausfahren plus Belastungen,
     * und genau deren Summe ist diese Zahl.
     */
    val durationMin: Int? = null,
    /**
     * Das Zielevent selbst — keine Trainingseinheit, sondern der Wettkampf.
     *
     * Fuer das Event darf **keine** Runde generiert werden: Es hat eine eigene
     * Strecke, und eine 200-km-Schleife vor der Haustuer ist dafuer das falsche
     * Angebot. Wer einen „Passende Runde"-Knopf anbietet, muss das hier fragen
     * (siehe [canGenerateRouteFor]).
     */
    val isEvent: Boolean = false,
    /**
     * Ziel-Trainingslast der Einheit auf der eTSS-Skala (1 h an der Schwelle
     * = 100), abgestimmt auf das Lastmodell aus `TrainingLoad.kt` /
     * `PerformanceManagement.kt`.
     *
     * Die Bruecke zwischen Plan und Lastmodell: [targetKm] bleibt die Groesse
     * fuers Routing, [targetLoad] die fuer CTL/ATL/Wochenbudget. Erzeugt wird
     * der Wert zusammen mit den Kilometern in `generatePlan` (siehe dort
     * `attachSessionLoads`), sodass beide Zahlen nie unabhaengig voneinander
     * entstehen.
     *
     * `null` bei Plaenen aus der Zeit vor diesem Feld — ein fehlender
     * Schluessel im JSON ist der Normalfall beim Lesen alter Plaene, kein
     * Fehler.
     */
    val targetLoad: Double? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("day", day)
        put("title", title)
        put("description", description)
        put("targetKm", targetKm)
        // Ab hier ausschliesslich angehaengte Felder (siehe Klassen-KDoc).
        put("intensity", intensity.jsonName)
        durationMin?.let { put("durationMin", it) }
        if (isEvent) {
            put("isEvent", true)
        }
        targetLoad?.let { put("targetLoad", it) }
    }

    companion object {
        /** Titelpraefix des Zielevents aus [generatePlan] — Notnagel fuer alte Plaene. */
        private const val EVENT_TITLE_PREFIX = "zielevent"

        fun fromJson(json: JsonObject): TrainingSession {
            val title = json.requiredString("title")
            return TrainingSession(
                day = json.requiredString("day"),
                title = title,
                description = json.requiredString("description"),
                targetKm = json.requiredInt("targetKm"),
                intensity = json.optionalString("intensity")
                    ?.let { SessionIntensity.fromJsonNameOrNull(it) }
                    ?: sessionIntensityFromTitle(title),
                durationMin = json.optionalInt("durationMin"),
                isEvent = json.optionalBoolean("isEvent")
                    ?: title.lowercase().startsWith(EVENT_TITLE_PREFIX),
                targetLoad = json.optionalDouble("targetLoad"),
            )
        }
    }
}

/** Eine Trainingswoche innerhalb eines [TrainingPlan]. */
data class TrainingWeek(
    val index: Int,
    /** Montag 00:00 lokal, ms seit Epoch (inklusiv). */
    val start: Long,
    /** Folgemontag 00:00 lokal, ms seit Epoch (exklusiv). */
    val end: Long,
    val kind: WeekKind,
    val targetKm: Int,
    val sessions: List<TrainingSession>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("index", index)
        put("start", start)
        put("end", end)
        put("kind", kind.jsonName)
        put("targetKm", targetKm)
        put("sessions", buildJsonArray { sessions.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JsonObject): TrainingWeek = TrainingWeek(
            index = json.requiredInt("index"),
            start = json.requiredLong("start"),
            end = json.requiredLong("end"),
            kind = WeekKind.fromJsonName(json.requiredString("kind")),
            targetKm = json.requiredInt("targetKm"),
            sessions = json.requiredArray("sessions").map { TrainingSession.fromJson(it.asRequiredObject()) },
        )
    }
}

/** Ein vollstaendiger Trainingsplan auf ein [Goal] hin. */
data class TrainingPlan(
    val createdAt: Long,
    val goal: Goal,
    val level: FitnessLevel,
    val weeks: List<TrainingWeek>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("createdAt", createdAt)
        put("goal", goal.toJson())
        put("level", level.jsonName)
        put("weeks", buildJsonArray { weeks.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JsonObject): TrainingPlan = TrainingPlan(
            createdAt = json.requiredLong("createdAt"),
            goal = Goal.fromJson(json.requiredObject("goal")),
            level = FitnessLevel.fromJsonName(json.requiredString("level")),
            weeks = json.requiredArray("weeks").map { TrainingWeek.fromJson(it.asRequiredObject()) },
        )
    }
}

/**
 * Ein Wegpunkt fuer die Routenplanung. Rein In-Memory — im Original-Dart-Code
 * ohne `toJson`/`fromJson`, daher auch hier ohne Serialisierung.
 *
 * [name] ist optional und wird nur von Wegpunkten gefuellt, die von einem
 * bekannten Ort stammen (z. B. einem Suchtreffer) — ein Kartentipp legt
 * weiterhin einen namenlosen Wegpunkt an, es gibt dafuer schlicht keinen
 * Namen. `null` bedeutet also nicht „noch nicht geladen", sondern „dieser
 * Punkt hat keinen": die Zustandszeile der Planung darf ihn deshalb gefahrlos
 * ignorieren, solange keine Oberflaeche ihn anzeigt.
 */
data class Waypoint(val lat: Double, val lon: Double, val name: String? = null)

/** Eine geplante Route. Rein In-Memory, siehe [Waypoint]. */
data class PlannedRoute(
    val points: List<TrackPoint>,
    val distanceKm: Double,
    val ascentM: Double,
)

/** Navigationszustand waehrend einer laufenden Fahrt. Rein In-Memory, siehe [Waypoint]. */
data class NavState(
    val nearestIndex: Int,
    val distanceToRouteM: Double,
    val doneKm: Double,
    val remainingKm: Double,
    val offRoute: Boolean,
)
