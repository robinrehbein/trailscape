package de.trailscape.core

/**
 * Annahme-/Verwerfungslogik fuer GPS-Punkte waehrend einer Aufzeichnung.
 *
 * 1:1-Portierung der Filterlogik aus `lib/recorder.dart` (`_handlePosition`,
 * `currentSpeedKmh`). Bewusst plattformfrei in `:core` und damit ohne
 * Emulator testbar: Die Android-Seite (`RecordingService`) mappt lediglich
 * `android.location.Location` auf [LocationSample] und uebernimmt sonst
 * keinerlei Entscheidung darueber, ob ein Punkt in die Tour aufgenommen wird.
 *
 * Reihenfolge und Schwellwerte entsprechen exakt dem Dart-Original:
 *
 *  1. Geschwindigkeit merken (`speed >= 0` → `speed * 3.6`) — passiert VOR
 *     allen Filtern, also auch fuer Punkte, die anschliessend verworfen
 *     werden bzw. waehrend einer Pause.
 *  2. Genauigkeitsfilter: `accuracy > 50 m` → verwerfen.
 *  3. Pause: waehrend einer Pause wird kein Punkt aufgenommen.
 *  4. Duplikat: exakt gleiche lat/lon wie der zuletzt aufgenommene Punkt →
 *     verwerfen.
 *
 * Der eigentliche *Mindestabstand* zwischen zwei Punkten (3 m) ist im
 * Dart-Original kein Filter dieser Klasse, sondern eine Einstellung des
 * Standort-Streams (`AndroidSettings.distanceFilter = 3`); auf der
 * Android-Seite entspricht das `LocationRequest.setMinUpdateDistanceMeters(3f)`
 * — siehe [MIN_UPDATE_DISTANCE_M].
 */

/**
 * Plattformfreie Sicht auf eine rohe Standortmeldung.
 *
 * Die Defaults bilden das Verhalten von `geolocator`/`Position.fromMap` nach:
 * Felder, die `android.location.Location` nicht liefert (`hasAltitude()`,
 * `hasAccuracy()`, `hasSpeed()` sind `false`), kommen in Dart als `0.0` an —
 * nicht als `null`. Das ist relevant, weil `accuracy == 0.0` damit den
 * Genauigkeitsfilter passiert und `ele` dann als `0.0` (nicht `null`)
 * aufgezeichnet wird.
 *
 * @param altitudeM Hoehe in Metern (WGS84 bzw. MSL, je nach Geraet).
 * @param accuracyM Horizontale Genauigkeit in Metern (kleiner ist besser).
 * @param speedMps Momentangeschwindigkeit in m/s.
 * @param timeMs Zeitstempel der Messung in ms seit Epoch.
 */
data class LocationSample(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double = 0.0,
    val accuracyM: Double = 0.0,
    val speedMps: Double = 0.0,
    val timeMs: Long,
)

/** Grund, aus dem ein Punkt nicht in die Tour aufgenommen wurde. */
enum class PointRejection {
    /** `accuracy > 50 m` — zu ungenaue Messung. */
    LOW_ACCURACY,

    /** Die Aufzeichnung ist gerade pausiert. */
    PAUSED,

    /** Exakt dieselbe Position wie der zuletzt aufgenommene Punkt. */
    DUPLICATE,
}

/** Ergebnis von [PointFilter.offer]. */
sealed interface PointFilterResult {
    /** Punkt wurde aufgenommen und muss persistiert werden. */
    data class Accepted(val point: TrackPoint) : PointFilterResult

    /** Punkt wurde verworfen. */
    data class Rejected(val reason: PointRejection) : PointFilterResult
}

/**
 * Zustandsbehaftete, aber vollstaendig plattformfreie Filterinstanz.
 *
 * Nicht thread-sicher: Aufrufer serialisieren die Zugriffe (im
 * `RecordingService` laufen Standort-Callbacks und Kommandos auf demselben
 * Handler-Thread).
 */
class PointFilter {

    private var lastPointValue: TrackPoint? = null
    private var previousPointValue: TrackPoint? = null
    private var lastKnownSpeedKmhValue: Double? = null
    private var acceptedCountValue: Int = 0

    /**
     * Ob die Aufzeichnung pausiert ist. Entspricht `Recorder._paused`:
     * Punkte werden waehrend der Pause verworfen, die Geschwindigkeit wird
     * aber weiter mitgefuehrt.
     */
    var paused: Boolean = false

    /** Zuletzt aufgenommener Punkt, oder `null` vor dem ersten Punkt. */
    val lastPoint: TrackPoint?
        get() = lastPointValue

    /** Anzahl der bisher aufgenommenen Punkte. */
    val acceptedCount: Int
        get() = acceptedCountValue

    /**
     * Aktuelle Geschwindigkeit in km/h, oder `null` wenn nicht ermittelbar.
     *
     * Entspricht `Recorder.currentSpeedKmh` — inklusive des Fallbacks ueber
     * die letzten beiden aufgenommenen Punkte, wenn das Geraet selbst keine
     * Geschwindigkeit geliefert hat. Der Fallback greift nur, wenn zwischen
     * den beiden Punkten weniger als [MAX_SPEED_FALLBACK_INTERVAL_S] Sekunden
     * liegen (sonst waere der Wert ueber eine zu lange Luecke gemittelt).
     *
     * Die Dart-Vorlage liefert zusaetzlich `null`, solange nicht aufgezeichnet
     * wird; dieser Zustand liegt hier beim Aufrufer.
     */
    val currentSpeedKmh: Double?
        get() {
            lastKnownSpeedKmhValue?.let { return it }

            val last = lastPointValue ?: return null
            val prev = previousPointValue ?: return null
            val lastTime = last.time ?: return null
            val prevTime = prev.time ?: return null

            val dtS = (lastTime - prevTime) / 1000.0
            if (dtS > 0 && dtS < MAX_SPEED_FALLBACK_INTERVAL_S) {
                val distanceKm = haversineM(prev, last) / 1000
                return distanceKm / (dtS / 3600)
            }

            return null
        }

    /**
     * Prueft eine Standortmeldung und liefert entweder den aufzunehmenden
     * [TrackPoint] oder den Grund der Verwerfung.
     */
    fun offer(sample: LocationSample): PointFilterResult {
        // Schritt 1: Geschwindigkeit immer mitfuehren (auch bei Verwerfung).
        if (sample.speedMps >= 0) {
            lastKnownSpeedKmhValue = sample.speedMps * 3.6
        }

        // Schritt 2: Genauigkeitsfilter.
        if (sample.accuracyM > MAX_ACCURACY_M) {
            return PointFilterResult.Rejected(PointRejection.LOW_ACCURACY)
        }

        // Schritt 3: Pause.
        if (paused) {
            return PointFilterResult.Rejected(PointRejection.PAUSED)
        }

        // Schritt 4: exaktes Positions-Duplikat.
        val last = lastPointValue
        if (last != null && last.lat == sample.lat && last.lon == sample.lon) {
            return PointFilterResult.Rejected(PointRejection.DUPLICATE)
        }

        val point = TrackPoint(
            lat = sample.lat,
            lon = sample.lon,
            // Entspricht `position.altitude.isFinite ? position.altitude : null`.
            ele = if (sample.altitudeM.isFinite()) sample.altitudeM else null,
            time = sample.timeMs,
        )

        previousPointValue = lastPointValue
        lastPointValue = point
        acceptedCountValue++

        return PointFilterResult.Accepted(point)
    }

    /**
     * Setzt den Filter auf einen bereits aufgezeichneten Punkteverlauf —
     * gebraucht nach einem Prozess-Neustart, wenn die Aufzeichnung aus dem
     * Journal fortgesetzt wird. Danach verhaelt sich der Filter so, als haette
     * er die Punkte selbst aufgenommen (Duplikatpruefung gegen den letzten
     * Punkt, Punktezahl, Geschwindigkeits-Fallback).
     *
     * Die zuletzt vom Geraet gemeldete Geschwindigkeit ist nach einem
     * Neustart unbekannt und wird zurueckgesetzt.
     */
    fun restore(points: List<TrackPoint>) {
        lastPointValue = points.lastOrNull()
        previousPointValue = if (points.size >= 2) points[points.size - 2] else null
        acceptedCountValue = points.size
        lastKnownSpeedKmhValue = null
    }

    /** Setzt den Filter fuer eine neue Aufzeichnung zurueck. */
    fun reset() {
        lastPointValue = null
        previousPointValue = null
        lastKnownSpeedKmhValue = null
        acceptedCountValue = 0
        paused = false
    }

    companion object {
        /**
         * Punkte mit schlechterer horizontaler Genauigkeit werden verworfen
         * (`_maxAccuracyM` in `lib/recorder.dart`).
         */
        const val MAX_ACCURACY_M: Double = 50.0

        /**
         * Obergrenze fuer den Zeitabstand, ueber den die Geschwindigkeit
         * ersatzweise aus zwei Punkten berechnet werden darf
         * (`_maxSpeedFallbackIntervalS` in `lib/recorder.dart`).
         */
        const val MAX_SPEED_FALLBACK_INTERVAL_S: Double = 10.0

        /**
         * Mindestabstand zwischen zwei Standortmeldungen in Metern
         * (`AndroidSettings.distanceFilter` im Dart-Original). Wird nicht hier,
         * sondern vom Standort-Provider durchgesetzt.
         */
        const val MIN_UPDATE_DISTANCE_M: Float = 3f

        /**
         * Abtastintervall in ms. Das Dart-Original setzt `intervalDuration`
         * nicht, `geolocator_android` faellt dann auf 5000 ms zurueck
         * (`LocationOptions.parseArguments`).
         */
        const val UPDATE_INTERVAL_MS: Long = 5000L
    }
}
