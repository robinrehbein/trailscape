package de.trailscape.app.ui.map

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import de.trailscape.core.PlannedRoute
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint

/**
 * # Die Planung ueberlebt Tabwechsel und Drehung
 *
 * Der Karten-Screen rettete bisher `planning`, `routeProfile`, `rideMode` und
 * die Kameraposition — ausgerechnet die **Arbeit** der Nutzerin nicht: Wer
 * sechs Wegpunkte gesetzt hatte und kurz in den Touren-Tab schaute, kam auf
 * eine leere Karte zurueck (der `NavHost` entsorgt den Screen beim
 * Tabwechsel, `remember` stirbt mit ihm). Am Lenker passiert dasselbe beim
 * Drehen. Diese Saver sind die fehlende Haelfte dazu.
 *
 * ## Warum eigene Saver und kein `@Parcelize`
 * [Waypoint], [TrackPoint] und [PlannedRoute] liegen in `:core`, einem reinen
 * Kotlin-Modul ohne Android-Abhaengigkeit — dort kann (und soll) kein
 * `Parcelable` stehen. Die Umrechnung gehoert deshalb hierher, in die
 * Oberflaeche, die sie braucht.
 *
 * ## Warum ein `DoubleArray` und keine Liste von Objekten
 * Eine geplante Route hat je nach Laenge einige tausend Punkte. Als Liste von
 * Objekten waere jeder davon ein eigener Eintrag im Bundle; als **ein**
 * `DoubleArray` sind es 8 Byte je Zahl und ein einziger Eintrag. Gerettet
 * werden nur `lat`, `lon` und `ele` — `time` und `hr` traegt eine berechnete
 * Route ohnehin nicht.
 *
 * ## Obergrenze
 * Der Bundle-Weg laeuft ueber eine Binder-Transaktion mit rund einem Megabyte
 * Gesamtgroesse; wird sie gesprengt, stuerzt die App beim Drehen ab
 * (`TransactionTooLargeException`). Deshalb [MAX_SAVEABLE_TRACK_POINTS]:
 * Darueber wird die Route **nicht** gerettet (die Wegpunkte bleiben, die Route
 * wird neu berechnet) statt den Absturz zu riskieren. Der Wert entspricht rund
 * 290 kB und liegt weit ueber jeder real geplanten Route.
 *
 * ## Was hier bewusst NICHT liegt
 * Das Navigationsziel. Es traegt die Punktliste einer **gespeicherten Tour**,
 * und die kann bei stundenlangen Aufzeichnungen zehntausende Punkte haben. Der
 * Screen rettet davon nur die Tour-Kennung und die Beschriftung und setzt das
 * Ziel danach aus den ohnehin geladenen Touren bzw. aus der geretteten
 * geplanten Route wieder zusammen (siehe `MapScreen.kt`).
 */

/** Obergrenze geretteter Punkte je Route — siehe Datei-KDoc. */
internal const val MAX_SAVEABLE_TRACK_POINTS: Int = 12_000

/** Rettet die gesetzten Wegpunkte (lat/lon-Paare in einem Array). */
internal val WaypointListSaver: Saver<List<Waypoint>, Any> = listSaver(
    save = { waypoints ->
        val values = DoubleArray(waypoints.size * 2)
        waypoints.forEachIndexed { index, waypoint ->
            values[index * 2] = waypoint.lat
            values[index * 2 + 1] = waypoint.lon
        }
        listOf(values)
    },
    restore = { saved ->
        val values = saved.firstOrNull() ?: return@listSaver emptyList()
        List(values.size / 2) { index ->
            Waypoint(lat = values[index * 2], lon = values[index * 2 + 1])
        }
    },
)

/**
 * Rettet die berechnete Route samt Distanz und Hoehenmetern.
 *
 * Eine leere Liste heisst „nichts zu retten" — [androidx.compose.runtime.saveable.rememberSaveable]
 * faellt dann auf den Anfangswert `null` zurueck. Genau das ist auch das
 * Verhalten oberhalb von [MAX_SAVEABLE_TRACK_POINTS].
 */
internal val PlannedRouteSaver: Saver<PlannedRoute?, Any> = listSaver(
    save = { route ->
        if (route == null || route.points.size > MAX_SAVEABLE_TRACK_POINTS) {
            emptyList()
        } else {
            listOf(route.distanceKm, route.ascentM, trackPointsToArray(route.points))
        }
    },
    restore = { saved ->
        if (saved.size < 3) {
            null
        } else {
            PlannedRoute(
                points = trackPointsFromArray(saved[2] as? DoubleArray ?: DoubleArray(0)),
                distanceKm = saved[0] as? Double ?: 0.0,
                ascentM = saved[1] as? Double ?: 0.0,
            )
        }
    },
)

/**
 * Punkte als flaches Array `[lat, lon, ele, lat, lon, ele, …]`.
 *
 * Eine unbekannte Hoehe wird als `NaN` abgelegt: Sie ist in `Double` der
 * einzige Wert, der garantiert keine echte Hoehe sein kann, und sie ueberlebt
 * den Weg durch das Bundle unveraendert.
 */
private fun trackPointsToArray(points: List<TrackPoint>): DoubleArray {
    val values = DoubleArray(points.size * 3)
    points.forEachIndexed { index, point ->
        values[index * 3] = point.lat
        values[index * 3 + 1] = point.lon
        values[index * 3 + 2] = point.ele ?: Double.NaN
    }
    return values
}

private fun trackPointsFromArray(values: DoubleArray): List<TrackPoint> =
    List(values.size / 3) { index ->
        val ele = values[index * 3 + 2]
        TrackPoint(
            lat = values[index * 3],
            lon = values[index * 3 + 1],
            ele = if (ele.isNaN()) null else ele,
        )
    }
