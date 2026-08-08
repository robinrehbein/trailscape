package de.trailscape.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reine Datentypen aus `lib/health_sync.dart`.
 *
 * Portiert ist hier alles, was **plattformneutral** ist: die Datentypen der
 * Abstraktionsschicht (Workouts, Sessions, Messwerte), der Diagnose-Bericht
 * eines Import-Laufs und die Vitaldaten-Serien. Die eigentliche Logik liegt in
 * `HealthSyncLogic.kt`.
 *
 * **Nicht** portiert (Phase 3, Android-Seite):
 *
 *  * `HealthPluginGateway` (Paket `health`, Health-Connect-SDK-Status,
 *    `requestAuthorization`, `installHealthConnect`, `requestHistoryAccess`),
 *  * `healthReadTypes` / `healthOptionalReadTypes` (`hc.HealthDataType`),
 *  * der Platform-Channel `trailscape/health_extra`
 *    (`healthExtraChannelName`, `readVo2Max`, `readExerciseSessions`,
 *    `requestVo2MaxPermission`) samt Map→Objekt-Umsetzung,
 *  * `mapActivityKind(hc.HealthWorkoutActivityType)`,
 *  * die `SharedPreferences`-Anbindung des Import-Zeitstempels — dafuer gibt
 *    es in `HealthSyncLogic.kt` die schmale Schnittstelle
 *    [HealthSyncStore].
 */

// ---------------------------------------------------------------------------
// Dart-kompatible Zeit- und Rundungshilfen
// ---------------------------------------------------------------------------

/**
 * Entspricht Darts `DateTime.fromMillisecondsSinceEpoch(ms)`: der Zeitstempel
 * wird in *lokaler* Zeit interpretiert, genau wie im Original.
 */
internal fun dartLocalOf(epochMs: Long): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())

/** Entspricht Darts `DateTime.millisecondsSinceEpoch` fuer eine lokale `DateTime`. */
internal fun dartEpochMs(value: LocalDateTime): Long =
    value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * Entspricht Darts `DateTime.subtract(Duration(...))`/`add(...)`: gerechnet
 * wird auf der absoluten Zeitachse (nicht auf der Wanduhr), Sommerzeitspruenge
 * verschieben die Uhrzeit also mit.
 */
internal fun dartPlusMillis(value: LocalDateTime, millis: Long): LocalDateTime =
    dartLocalOf(dartEpochMs(value) + millis)

/** Entspricht Darts `(value * 10).round() / 10`. */
internal fun dartRound1(value: Double): Double = dartRound(value * 10) / 10

/**
 * Entspricht Darts `Duration.inSeconds`/`inMinutes`: ganzzahlig gekuerzt in
 * Richtung Null. Java-`Duration.getSeconds()` rundet dagegen abwaerts.
 */
internal fun dartDurationMs(start: LocalDateTime, end: LocalDateTime): Long =
    dartEpochMs(end) - dartEpochMs(start)

// ---------------------------------------------------------------------------
// Datentypen der Abstraktionsschicht
// ---------------------------------------------------------------------------

/** Verfuegbarkeit von Health Connect auf diesem Geraet. */
enum class HealthAvailability {
    /** Health Connect ist installiert und nutzbar. */
    VERFUEGBAR,

    /** Health Connect ist nicht installiert. */
    NICHT_INSTALLIERT,

    /** Health Connect ist installiert, muss aber aktualisiert werden. */
    UPDATE_NOETIG,

    /** Die Plattform unterstuetzt Health Connect nicht (z. B. Desktop/Web). */
    NICHT_UNTERSTUETZT,
}

/** Ergebnis von [HealthSyncService.checkAvailability]. */
data class HealthConnection(
    val availability: HealthAvailability,
    /** Ob alle benoetigten Leserechte bereits erteilt sind. */
    val hasPermissions: Boolean,
) {
    /** Ob sofort gelesen werden kann. */
    val isReady: Boolean
        get() = availability == HealthAvailability.VERFUEGBAR && hasPermissions

    /** Ob eine Berechtigungsabfrage sinnvoll ist. */
    val needsPermissions: Boolean
        get() = availability == HealthAvailability.VERFUEGBAR && !hasPermissions

    /** Fuer die UI verwendbare deutsche Beschreibung des Zustands. */
    val message: String
        get() = when (availability) {
            HealthAvailability.NICHT_UNTERSTUETZT ->
                "Health Connect wird auf diesem Gerät nicht unterstützt."

            HealthAvailability.NICHT_INSTALLIERT ->
                "Health Connect ist nicht installiert. Bitte installiere die App aus " +
                    "dem Play Store, damit Trailscape auf die Watch-Daten zugreifen kann."

            HealthAvailability.UPDATE_NOETIG ->
                "Health Connect muss aktualisiert werden, bevor Trailscape darauf " +
                    "zugreifen kann."

            HealthAvailability.VERFUEGBAR -> if (hasPermissions) {
                "Health Connect ist verbunden."
            } else {
                "Trailscape braucht noch deine Zustimmung, um Health-Connect-Daten " +
                    "zu lesen."
            }
        }
}

/** Fehler, der eine Synchronisation komplett verhindert. */
class HealthSyncException(
    /** Fuer die UI geeignete deutsche Meldung. */
    override val message: String,
) : Exception(message) {
    override fun toString(): String = message
}

/** Art eines Workouts, soweit fuer Trailscape relevant. */
enum class HealthActivityKind(
    /** Exakter Dart-Enum-Name (`HealthActivityKind.name`), so wie er in den
     *  Diagnosezeilen auftaucht. */
    val dartName: String,
) {
    /** Radfahren im Freien. */
    RADFAHREN("radfahren"),

    /** Radfahren auf der Rolle / im Studio (ohne GPS-Route). */
    RADFAHREN_INDOOR("radfahrenIndoor"),

    /** Alles andere (Laufen, Wandern, ...). */
    SONSTIGES("sonstiges"),
}

/** Ein Workout (ExerciseSession) aus Health Connect. */
data class HealthWorkout(
    /** Stabile ID des Health-Connect-Datensatzes. */
    val id: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val kind: HealthActivityKind,
    /** Vom Geraet gemessene Gesamtdistanz in Metern. */
    val distanceM: Double? = null,
    /** Verbrauchte Energie in kcal. */
    val energyKcal: Int? = null,
    /** Name der Quell-App (z. B. `com.sec.android.app.shealth`). */
    val sourceName: String? = null,
) {
    /** Ob es sich um ein Rad-Workout handelt (drinnen oder draussen). */
    val isCycling: Boolean
        get() = kind == HealthActivityKind.RADFAHREN ||
            kind == HealthActivityKind.RADFAHREN_INDOOR

    /** Dauer in Millisekunden (entspricht Darts `end.difference(start)`). */
    val durationMs: Long
        get() = dartDurationMs(start, end)

    /** Entspricht Darts `duration.inSeconds` (gekuerzt Richtung Null). */
    val durationS: Long
        get() = durationMs / 1000
}

/**
 * Eine Trainings-Session, wie sie der native Reader
 * (`HealthExtraChannel.readExerciseSessions`) roh aus Health Connect liefert.
 *
 * Absichtlich unangereichert: nur die Felder des `ExerciseSessionRecord`
 * selbst, ohne Distanz-, Kalorien- oder Schrittdaten.
 */
data class HealthSessionInfo(
    /**
     * `metadata.id` des Datensatzes — dieselbe ID, die das `health`-Paket als
     * `uuid` bzw. `workoutUuid` meldet.
     */
    val uid: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** Rohe androidx-Konstante (`ExerciseSessionRecord.EXERCISE_TYPE_*`). */
    val typeCode: Int,
    /** Name der Konstante, z. B. `EXERCISE_TYPE_BIKING`, sonst `TYPE_<int>`. */
    val typeName: String,
    /** Titel der Session, sofern die Quell-App einen setzt. */
    val title: String? = null,
    /** Paketname der Quell-App. */
    val source: String? = null,
    /** Ob Health Connect die GPS-Route ohne weitere Zustimmung herausrueckt. */
    val hasRoute: Boolean = false,
)

/**
 * Rohdiagnose eines [HealthGateway.readWorkouts]-Aufrufs.
 *
 * Beantwortet die Frage, ob das `health`-Paket ueberhaupt Datenpunkte geliefert
 * hat und ob deren `value` der erwartete `WorkoutHealthValue` war.
 */
data class HealthWorkoutReadDiagnostics(
    /** Wie viele Datenpunkte das Plugin zurueckgegeben hat. */
    val rawPointCount: Int,
    /** Laufzeittyp von `HealthDataPoint.value` → Anzahl. */
    val valueTypeCounts: Map<String, Int>,
    /** Aktivitaetstyp des Plugins → Anzahl (nur fuer Workout-Punkte). */
    val activityTypeCounts: Map<String, Int>,
) {
    /** Kompakte deutsche Zusammenfassung fuer [HealthSyncReport.debugLines]. */
    fun describe(): String {
        val types = if (valueTypeCounts.isEmpty()) {
            "keine"
        } else {
            valueTypeCounts.entries.joinToString(", ") { "${it.key}×${it.value}" }
        }
        val kinds = if (activityTypeCounts.isEmpty()) {
            "keine"
        } else {
            activityTypeCounts.entries.joinToString(", ") { "${it.key}×${it.value}" }
        }
        return "Plugin: $rawPointCount Rohpunkt(e); Werttypen: $types; " +
            "Aktivitätstypen: $kinds"
    }

    companion object {
        /** Entspricht Darts `HealthWorkoutReadDiagnostics.empty()`. */
        val empty = HealthWorkoutReadDiagnostics(
            rawPointCount = 0,
            valueTypeCounts = emptyMap(),
            activityTypeCounts = emptyMap(),
        )
    }
}

/** Ein einzelner GPS-Punkt einer Trainingsroute. */
data class HealthRoutePoint(
    val lat: Double,
    val lon: Double,
    val time: LocalDateTime,
    val ele: Double? = null,
)

/** Eine Herzfrequenz-Messung. */
data class HealthHeartRateSample(val time: LocalDateTime, val bpm: Double)

/** Ein Messwert mit Zeitpunkt (Ruhepuls, VO2max, ...). */
data class HealthNumericSample(val time: LocalDateTime, val value: Double)

/** Eine Schlafphase bzw. -sitzung. */
data class HealthSleepSession(val start: LocalDateTime, val end: LocalDateTime) {
    /** Dauer in Millisekunden. */
    val durationMs: Long
        get() = dartDurationMs(start, end)

    /** Entspricht Darts `duration.inMinutes` (gekuerzt Richtung Null). */
    val durationMinutes: Long
        get() = durationMs / 60000
}

/**
 * Ergebnis eines Import-Laufs — fuer Diagnose und UI-Rueckmeldung.
 *
 * Wird von [HealthSyncService.importWithReport] geliefert. Weder [imported]
 * noch [mergedRides] sind gespeichert; das uebernimmt der Aufrufer.
 */
data class HealthSyncReport(
    /** Betrachteter Zeitraum. */
    val from: LocalDateTime,
    val to: LocalDateTime,
    /** Anzahl der im Fenster gefundenen **Rad**-Sessions (vor jeder Filterung). */
    val workoutsFound: Int,
    /** Neu angelegte Touren. */
    val imported: List<Ride>,
    /**
     * Bestehende Touren, die um Herzfrequenzdaten aus einer ueberlappenden
     * Watch-Session ergaenzt wurden (gleiche ID wie das Original).
     */
    val mergedRides: List<Ride>,
    /**
     * Sessions, die als Duplikat verworfen wurden (gleiche ID oder
     * ueberlappende Tour, die bereits Herzfrequenzdaten hat).
     */
    val duplicatesSkipped: Int,
    /**
     * Importierte Outdoor-Touren, fuer die Health Connect keine Route
     * herausgerueckt hat (Trackpunkte fehlen).
     */
    val routesMissing: Int,
    /**
     * Technische Notizen des Laufs (deutsch, kompakt) fuer die Fehlersuche auf
     * dem Geraet: was das Plugin roh geliefert hat, welche Sessions daraus
     * wurden, was der native Reader sah und ob die Rueckfallebene griff.
     */
    val debugLines: List<String> = emptyList(),
) {
    /** Wie viele Touren der Aufrufer speichern muss. */
    val changedCount: Int
        get() = imported.size + mergedRides.size

    /** Ob der Lauf nichts veraendert hat. */
    val isEmpty: Boolean
        get() = changedCount == 0

    companion object {
        /** Leerer Bericht (kein Fenster betrachtet). */
        fun empty(from: LocalDateTime, to: LocalDateTime) = HealthSyncReport(
            from = from,
            to = to,
            workoutsFound = 0,
            imported = emptyList(),
            mergedRides = emptyList(),
            duplicatesSkipped = 0,
            routesMissing = 0,
            debugLines = emptyList(),
        )
    }
}

// ---------------------------------------------------------------------------
// Vitaldaten
// ---------------------------------------------------------------------------

/**
 * Ein Tageswert einer Vitalserie (Ruhepuls in bpm, HRV/rMSSD in ms, Schlaf in
 * Stunden, VO2max in ml/kg/min).
 *
 * [day] ist ein Kalendertag in lokaler Zeit; die Rechenkern-Funktionen
 * normalisieren ihn selbst auf Mitternacht, genau wie im Dart-Original.
 */
data class DailyValue(
    val day: LocalDateTime,
    val value: Double,
)

/** Tagesserie mit 7-Tage-Trend gegenueber der Vorwoche. */
data class VitalsTrend(
    /** Tageswerte, aufsteigend nach Datum. */
    val series: List<DailyValue>,
    /** Mittelwert der letzten 7 Tage, `null` wenn keine Werte vorliegen. */
    val lastWeekAvg: Double?,
    /** Mittelwert der 7 Tage davor, `null` wenn keine Werte vorliegen. */
    val previousWeekAvg: Double?,
) {
    val hasData: Boolean
        get() = series.isNotEmpty()

    /** Ob sich beide Wochen vergleichen lassen. */
    val hasTrend: Boolean
        get() = lastWeekAvg != null && previousWeekAvg != null

    /** Absolute Veraenderung (letzte Woche minus Vorwoche). */
    val delta: Double?
        get() = if (hasTrend) dartRound1(lastWeekAvg!! - previousWeekAvg!!) else null

    /** Relative Veraenderung in Prozent. */
    val deltaPercent: Double?
        get() {
            if (!hasTrend || previousWeekAvg == 0.0) {
                return null
            }
            return dartRound1((lastWeekAvg!! - previousWeekAvg!!) / previousWeekAvg * 100)
        }

    /** Neuester Tageswert. */
    val latest: Double?
        get() = if (series.isEmpty()) null else series.last().value

    /** Kleinster Tageswert. */
    val min: Double?
        get() = if (series.isEmpty()) null else series.minOf { it.value }

    /** Groesster Tageswert. */
    val max: Double?
        get() = if (series.isEmpty()) null else series.maxOf { it.value }

    companion object {
        /** Leere Reihe ohne Trend (Darts `VitalsTrend.empty()`). */
        val empty = VitalsTrend(series = emptyList(), lastWeekAvg = null, previousWeekAvg = null)
    }
}

/** Datentypen, die beim Lesen der Vitaldaten fehlschlagen koennen. */
enum class VitalsDataKind { RUHEPULS, SCHLAF, VO2MAX, HRV }

/** Ergebnis von [HealthSyncService.readVitals]. */
data class VitalsSummary(
    /** Betrachtetes Fenster in Tagen. */
    val days: Int,
    val from: LocalDateTime,
    val to: LocalDateTime,
    /** Ruhepuls in bpm je Tag. */
    val restingHeartRate: VitalsTrend,
    /** Schlafdauer in Stunden je Tag (dem Aufwachtag zugeordnet). */
    val sleepHours: VitalsTrend,
    /**
     * Herzratenvariabilitaet (rMSSD) in ms je Tag — ein repraesentativer Wert
     * je Kalendertag, siehe [dailyHrvValues].
     */
    val heartRateVariability: VitalsTrend = VitalsTrend.empty,
    /** Zuletzt gemessener VO2max-Wert, falls die Plattform ihn liefert. */
    val vo2max: Double? = null,
    /** Zeitpunkt der VO2max-Messung. */
    val vo2maxAt: LocalDateTime? = null,
    /**
     * Datentypen, die nicht gelesen werden konnten (fehlende Berechtigung,
     * Plattform-Grenze, Fehler). Die uebrigen Werte bleiben trotzdem gueltig.
     */
    val unavailable: Set<VitalsDataKind> = emptySet(),
) {
    /** Ob ueberhaupt Daten vorliegen. */
    val isEmpty: Boolean
        get() = !restingHeartRate.hasData &&
            !sleepHours.hasData &&
            !heartRateVariability.hasData &&
            vo2max == null
}
