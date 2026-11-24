package de.trailscape.core

import de.trailscape.core.i18n.SessionTextKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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
    /**
     * Leistung in Watt — das **Mittel seit dem vorigen Punkt**, nicht ein
     * Momentwert, damit die Energie des Intervalls stimmt (Leistung schwankt
     * je Pedaltritt stark). Quelle: ein per Bluetooth gekoppelter
     * Leistungsmesser oder ein GPX-Import (`<power>`).
     *
     * Rueckwaertskompatibel wie [hr]: Der Schluessel `power` (Name wie in der
     * GPX-Konvention von Strava) wird nur geschrieben, wenn er gesetzt ist;
     * Punkte ohne Messung bleiben byteweise beim alten Format, und ein
     * fehlender Schluessel liest sich als `null` (nicht als 0 W).
     */
    val power: Int? = null,
    /**
     * Trittfrequenz in Kurbelumdrehungen pro Minute zum Zeitpunkt des Punkts.
     * Quelle: Bluetooth-Trittfrequenzsensor, Kurbeldaten des Leistungsmessers
     * oder ein GPX-Import (`gpxtpx:cad`). Schluessel `cad` wie in der
     * Garmin-TrackPointExtension; geschrieben nur mit Wert, siehe [power].
     */
    val cad: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("lat", lat)
        put("lon", lon)
        ele?.let { put("ele", it) }
        time?.let { put("time", it) }
        hr?.let { put("hr", it) }
        // Angehaengt und nur mit Wert (siehe [power]/[cad]).
        power?.let { put("power", it) }
        cad?.let { put("cad", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): TrackPoint = TrackPoint(
            lat = json.requiredDouble("lat"),
            lon = json.requiredDouble("lon"),
            ele = json.optionalDouble("ele"),
            time = json.optionalLong("time"),
            hr = json.optionalInt("hr"),
            power = json.optionalInt("power"),
            cad = json.optionalInt("cad"),
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

    /**
     * Siehe [Ride.indoor]. Mit Vorgabe `false`, damit bestehende
     * Implementierungen (auch Test-Attrappen) unveraendert bleiben.
     */
    val indoor: Boolean get() = false
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
    /** Siehe [Ride.indoor]; im Index nur geschrieben, wenn `true`. */
    override val indoor: Boolean = false,
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
        // Angehaengt und nur im Ausnahmefall (siehe [Ride.indoor]).
        if (indoor) {
            put("indoor", true)
        }
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
                indoor = json.optionalBoolean("indoor") ?: false,
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
    /**
     * `true`, wenn die Fahrt **auf dem Rollentrainer** entstanden ist (Feature
     * „Indoor", Vertrag in `docs/indoor.md`). Die Punkte tragen dann die
     * Koordinaten der *virtuell* gefahrenen Strecke samt Leistung und
     * Trittfrequenz — niemand war dort unterwegs.
     *
     * ## Was daraus folgt
     * Eine Indoor-Fahrt ist **gefahren** ([planned] bleibt `false`): Sie zaehlt
     * fuer Belastung, Wochenziel und Statistik. Sie zaehlt **nicht** fuer
     * alles, was „draussen gewesen" meint — Neue Wege, Entdeckt-Kacheln,
     * gefahrene Wege, Rundenvergleich. Wer das meint, filtert ueber
     * [outdoorRides].
     *
     * ## Rueckwaertskompatibilitaet
     * Wie [planned]: Der Schluessel `indoor` wird **nur geschrieben, wenn er
     * `true` ist**, und steht vor `updatedAt` — jede bisherige Tour bleibt
     * byteweise beim alten Format, alte Dateien lesen sich als `false`.
     */
    override val indoor: Boolean = false,
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
        indoor = indoor,
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
        // Wie `planned` nur im Ausnahmefall (siehe [indoor]).
        if (indoor) {
            put("indoor", true)
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
                indoor = json.optionalBoolean("indoor") ?: false,
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

/**
 * Nur die **draussen** gefahrenen Touren — ohne Planungen ([Ride.planned])
 * und ohne Fahrten auf dem Rollentrainer ([Ride.indoor]).
 *
 * Das Gegenstueck zu [riddenRides] fuer alles, was einen echten Ort meint:
 * Neue Wege, Entdeckt-Kacheln, gefahrene Wege, Rundenvergleich. Belastung und
 * Statistik nehmen weiter [riddenRides] — dort zaehlt die Indoor-Fahrt mit.
 */
fun <T : RideInfo> outdoorRides(rides: List<T>): List<T> = rides.filter { !it.planned && !it.indoor }

/** Fitness-Stufen wie in der Flutter-App und der Web-Referenz. */
enum class FitnessLevel(
    /** Exakter Dart-Enum-Name (`FitnessLevel.name`), wie er im JSON steht. */
    val jsonName: String,
) {
    EINSTEIGER("einsteiger"),
    FORTGESCHRITTEN("fortgeschritten"),
    AMBITIONIERT("ambitioniert"),
    ;

    companion object {
        /** Entspricht Darts `FitnessLevel.values.byName(...)`: wirft bei unbekanntem Namen. */
        fun fromJsonName(name: String): FitnessLevel =
            entries.firstOrNull { it.jsonName == name }
                ?: throw MissingOrInvalidFieldException("Unbekannter FitnessLevel: '$name'")
    }
}

// Beschriftung: `texts.training.fitnessLevel(level)` (siehe `core/i18n`).

/** Ergebnis von [assessFitness]. */
data class FitnessAssessment(
    val level: FitnessLevel,
    val weeklyKm: Double,
    val weeklyHm: Double,
    val weeklyRides: Double,
    val longestRideKm: Double,
    val rideCount: Int,
)

/**
 * Trainingsziel: Zieldistanz zu einem bestimmten Datum, optional mit
 * Hoehenmetern und einer Zielzeit.
 *
 * ## Rueckwaertskompatibilitaet des Formats
 * [ascentM] steht — wie im Dart-Original — **immer** im JSON, notfalls als
 * explizites `null`. [targetDurationMin] ist spaeter dazugekommen und folgt der
 * Konvention der angehaengten Felder von [TrainingSession]: Der Schluessel wird
 * **hinten angehaengt** und **nur geschrieben, wenn es einen Wert gibt**. Ein
 * Ziel ohne Zielzeit ergibt damit byteidentisch dasselbe JSON wie vorher, und
 * ein altes Ziel ohne den Schluessel liest sich als „keine Zielzeit".
 *
 * [type] folgt derselben Regel: geschrieben wird `"type":"fitness"` **nur**
 * fuer ein Fitnessziel; ein Eventziel bleibt byteidentisch. Ein unbekannter
 * Wert (Ziel aus einer neueren Version) liest sich als [GoalType.EVENT] —
 * `distanceKm` und `date` sind fuer jeden Typ Pflicht, damit auch eine
 * aeltere App-Version, die das Feld nicht kennt, das Ziel lesen kann.
 */
data class Goal(
    val name: String,
    val distanceKm: Double,
    val ascentM: Double? = null,
    /** ms seit Epoch. */
    val date: Long,
    /**
     * Angestrebte Zielzeit in Minuten (z. B. 130 fuer „2:10 h"); `null`, wenn
     * keine eingetragen ist. Grundlage des Vergleichs mit der Prognose aus
     * [predictGoalFinish] — und seit dem Planumbau auch des Plans selbst:
     * Mit Zielzeit beginnen die Zieltempo-Abschnitte der langen Tour schon in
     * der Aufbauphase, und ihr Text nennt den Zielschnitt (siehe
     * [generatePlan]). [assessPlanFeasibility] sagt, wenn die Zielzeit
     * deutlich schneller ist als die Prognose.
     */
    val targetDurationMin: Int? = null,
    /**
     * Art des Ziels — ein Event an einem Datum (Vorgabe) oder ein
     * Fitnessblock ohne Event, der mit einer Testwoche endet (siehe
     * [GoalType]). Bei [GoalType.FITNESS] ist [date] das Ende des Blocks
     * (Tag der Vergleichsrunde) und [distanceKm] die Laenge der
     * Vergleichsrunde; `0` heisst „keine feste Runde".
     */
    val type: GoalType = GoalType.EVENT,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("distanceKm", distanceKm)
        put("ascentM", ascentM)
        put("date", date)
        // Angehaengt und nur mit Wert (siehe Klassen-KDoc).
        targetDurationMin?.let { put("targetDurationMin", it) }
        if (type != GoalType.EVENT) {
            put("type", type.jsonName)
        }
    }

    companion object {
        fun fromJson(json: JsonObject): Goal = Goal(
            name = json.requiredString("name"),
            distanceKm = json.requiredDouble("distanceKm"),
            ascentM = json.optionalDouble("ascentM"),
            date = json.requiredLong("date"),
            targetDurationMin = json.optionalInt("targetDurationMin")?.takeIf { it > 0 },
            type = GoalType.fromJsonNameOrDefault(json.optionalString("type")),
        )
    }
}

/**
 * Art eines Trainingsziels.
 *
 * Bewusst als eigenes, optionales Feld statt als neuer [WeekKind]-Wert: Ein
 * unbekannter Wochentyp macht in aelteren App-Versionen den **ganzen Plan**
 * unlesbar ([WeekKind.fromJsonName] wirft), ein unbekannter Zieltyp faellt
 * dagegen hier lautlos auf [EVENT] zurueck.
 */
enum class GoalType(
    /** Name im JSON; stabil unabhaengig vom Kotlin-Bezeichner. */
    val jsonName: String,
) {
    /** „Distanz X am Datum Y" — der bisherige und einzige Zieltyp. */
    EVENT("event"),

    /**
     * „Fitness/Tempo verbessern": ein Block ueber einige Wochen im
     * 3:1-Rhythmus mit Intensitaetsprogression, der mit einer Testwoche
     * (20-min-Test und Vergleichsrunde) statt eines Events endet.
     */
    FITNESS("fitness"),
    ;

    companion object {
        /** Unbekannt oder fehlend → [EVENT] (siehe Klassen-KDoc). */
        fun fromJsonNameOrDefault(name: String?): GoalType =
            entries.firstOrNull { it.jsonName == name } ?: EVENT
    }
}

/** Art einer Trainingswoche. */
enum class WeekKind(
    /** Exakter Dart-Enum-Name (`WeekKind.name`), wie er im JSON steht. */
    val jsonName: String,
) {
    AUFBAU("aufbau"),
    ERHOLUNG("erholung"),
    TAPER("taper"),
    ZIELWOCHE("zielwoche"),
    ;

    companion object {
        /** Entspricht Darts `WeekKind.values.byName(...)`: wirft bei unbekanntem Namen. */
        fun fromJsonName(name: String): WeekKind =
            entries.firstOrNull { it.jsonName == name }
                ?: throw MissingOrInvalidFieldException("Unbekannter WeekKind: '$name'")
    }
}

// Beschriftung: `texts.training.weekKind(kind)` (siehe `core/i18n`).

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
     * der Wert aus der Struktur der Einheit (Dauer × IF² je Abschnitt, siehe
     * `PlanWorkouts.kt`), sodass Last, Dauer und Kilometer nie unabhaengig voneinander
     * entstehen.
     *
     * `null` bei Plaenen aus der Zeit vor diesem Feld — ein fehlender
     * Schluessel im JSON ist der Normalfall beim Lesen alter Plaene, kein
     * Fehler.
     */
    val targetLoad: Double? = null,
    /**
     * Schluessel des Plantexts — damit [title] und [description] in der
     * aktuellen Sprache neu gebaut werden koennen (siehe
     * `de.trailscape.core.i18n.sessionTitle`/`sessionDescription`).
     *
     * [title] und [description] bleiben trotzdem gespeichert, in der Sprache,
     * in der der Plan erzeugt wurde: als Rueckfall fuer Plaene aus der Zeit
     * vor diesem Feld, fuer Backups und fuer aeltere App-Versionen. `null`
     * bei solchen Altplaenen — dann gilt der gespeicherte Text.
     */
    val textKey: SessionTextKey? = null,
    /** Ganzzahlige Argumente zu [textKey] (Minuten, Wiederholungen, km …). */
    val textArgs: List<Int> = emptyList(),
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
        textKey?.let { put("textKey", it.jsonName) }
        if (textArgs.isNotEmpty()) {
            put("textArgs", buildJsonArray { textArgs.forEach { add(JsonPrimitive(it)) } })
        }
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
                textKey = SessionTextKey.fromJsonNameOrNull(json.optionalString("textKey")),
                textArgs = (json["textArgs"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    .orEmpty(),
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

/**
 * Ein vollstaendiger Trainingsplan auf ein [Goal] hin.
 *
 * ## Angehaengte Felder
 * [preferences] und [planSpeedKmh] sind spaeter dazugekommen und folgen der
 * Konvention von [TrainingSession]: hinten angehaengt, **nur mit Wert
 * geschrieben**. Ein Plan, der ohne Praeferenzen und ohne persoenliches Tempo
 * erzeugt wurde, hat damit exakt die bisherigen Schluessel; ein alter Plan
 * liest sich als „keine Praeferenzen, Planungstempo 18 km/h". Gespeichert
 * werden beide, damit [adaptPlan] und [assessPlanFeasibility] spaeter mit
 * **denselben** Annahmen neu rechnen, mit denen der Plan entstand.
 */
data class TrainingPlan(
    val createdAt: Long,
    val goal: Goal,
    val level: FitnessLevel,
    val weeks: List<TrainingWeek>,
    /** Plan-Praeferenzen (Trainingstage, Tag der langen Tour, Zeitbudget); `null` = keine. */
    val preferences: PlanPreferences? = null,
    /**
     * Persoenliches Grundtempo in km/h, mit dem Dauer und Kilometer der
     * Einheiten umgerechnet wurden (z. B. [typicalAvgSpeedKmh]); `null` =
     * [fallbackGravelSpeedKmh].
     */
    val planSpeedKmh: Double? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("createdAt", createdAt)
        put("goal", goal.toJson())
        put("level", level.jsonName)
        put("weeks", buildJsonArray { weeks.forEach { add(it.toJson()) } })
        // Ab hier ausschliesslich angehaengte Felder, nur mit Wert.
        preferences?.takeUnless { it.isEmpty }?.let { put("preferences", it.toJson()) }
        planSpeedKmh?.let { put("planSpeedKmh", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): TrainingPlan = TrainingPlan(
            createdAt = json.requiredLong("createdAt"),
            goal = Goal.fromJson(json.requiredObject("goal")),
            level = FitnessLevel.fromJsonName(json.requiredString("level")),
            weeks = json.requiredArray("weeks").map { TrainingWeek.fromJson(it.asRequiredObject()) },
            preferences = (json.fieldOrNull("preferences") as? JsonObject)
                ?.let { PlanPreferences.fromJson(it) }
                ?.takeUnless { it.isEmpty },
            planSpeedKmh = (json.fieldOrNull("planSpeedKmh") as? JsonPrimitive)
                ?.content?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in minPlausibleAvgSpeedKmh..maxPlausibleAvgSpeedKmh },
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

/**
 * Eine geplante Route. Rein In-Memory, siehe [Waypoint].
 *
 * [pavedKm]/[unpavedKm] sind die Kilometer auf befestigtem bzw. unbefestigtem
 * Untergrund aus BRouters Wegmerkmalen (siehe `RouteSurface.kt`); der Rest bis
 * [distanceKm] ist unklassifiziert. `null` heisst „keine Belagsdaten" — eine
 * Antwort ohne verwertbare `messages` oder eine Route, die aus einer Quelle
 * ohne Belag stammt. Beide Felder haben einen Vorgabewert, damit alle
 * bestehenden Aufrufer (und gerettete Zustaende von vorher) unveraendert
 * bleiben.
 */
data class PlannedRoute(
    val points: List<TrackPoint>,
    val distanceKm: Double,
    val ascentM: Double,
    val pavedKm: Double? = null,
    val unpavedKm: Double? = null,
    /**
     * Die Wegabschnitte der Route in Fahrtrichtung, wie BRouter sie in
     * `properties.messages` liefert — Grundlage der feinen Belagsklassen des
     * Indoor-Trainings (`de.trailscape.core.indoor.SurfaceKind`, Vertrag in
     * `docs/indoor.md`). `null` heisst „keine Wegmerkmale" (Antwort ohne
     * verwertbare `messages`, oder ein Teilstueck kennt sie nicht); die Summe
     * der Laengen ist sonst die Routenlaenge. Rein In-Memory wie die ganze
     * Klasse.
     */
    val waySections: List<RouteWaySection>? = null,
)

/**
 * Ein Wegabschnitt einer [PlannedRoute]: [lengthM] Meter auf einem Weg mit den
 * OSM-Merkmalen [wayTags] (`highway=track surface=gravel tracktype=grade2 …`,
 * Format siehe `RouteSurface.kt`).
 */
data class RouteWaySection(val lengthM: Double, val wayTags: String)

/** Navigationszustand waehrend einer laufenden Fahrt. Rein In-Memory, siehe [Waypoint]. */
data class NavState(
    val nearestIndex: Int,
    val distanceToRouteM: Double,
    val doneKm: Double,
    val remainingKm: Double,
    val offRoute: Boolean,
)
