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

/**
 * Trennzeichen der geretteten Wegpunktnamen (siehe [WaypointListSaver]) — das
 * ASCII-Steuerzeichen „Unit Separator" (Code 31), das in einem von Menschen
 * eingegebenen oder von Nominatim gelieferten Ortsnamen praktisch nicht
 * vorkommt. Ueber [Char] mit Zahlencode konstruiert statt als rohes Zeichen im
 * Quelltext — ein unsichtbares Steuerzeichen waere im Editor unlesbar und
 * liesse sich beim naechsten Bearbeiten leicht kaputt-kopieren; kein
 * `const val`, weil der [Char]-Konstruktor kein konstanter Ausdruck ist.
 */
private val WAYPOINT_NAME_SEPARATOR: Char = Char(31)

/**
 * Ersatz fuer einen fehlenden Namen innerhalb der zusammengesetzten
 * Namenszeichenkette (siehe [WaypointListSaver]) — ein zweites Steuerzeichen
 * (Code 0, „Null"), damit sich „kein Name" von einem leeren, aber gesetzten
 * Namen unterscheiden liesse (auch wenn Letzteres heute nirgends vorkommt).
 */
private val WAYPOINT_NO_NAME_MARKER: Char = Char(0)

/**
 * Rettet die gesetzten Wegpunkte: lat/lon-Paare in einem `DoubleArray` (siehe
 * Datei-KDoc), die Namen zusammengesetzt in einem einzigen `String`.
 *
 * ## Warum die Namen nicht in einer eigenen Liste liegen
 * Ein `DoubleArray` ist fuer lat/lon der richtige Bundle-sparsame Weg (siehe
 * Datei-KDoc), fuer `String?` gibt es kein Pendant. Eine `List<String?>` waere
 * ihrerseits ein weiterer Eintrag im `listSaver`-Ergebnis, dessen
 * Bundle-Vertraeglichkeit sich nicht ebenso einfach garantieren liesse wie die
 * eines einzelnen `String` — eines der wenigen Typen, die
 * [androidx.compose.runtime.saveable.Saver] bedingungslos durchlaesst. Ein
 * mit [WAYPOINT_NAME_SEPARATOR] zusammengesetzter `String` bleibt also genau
 * bei diesem einen, sicheren Typ.
 */
internal val WaypointListSaver: Saver<List<Waypoint>, Any> = listSaver(
    save = { waypoints ->
        val values = DoubleArray(waypoints.size * 2)
        waypoints.forEachIndexed { index, waypoint ->
            values[index * 2] = waypoint.lat
            values[index * 2 + 1] = waypoint.lon
        }
        val names = waypoints.joinToString(separator = WAYPOINT_NAME_SEPARATOR.toString()) {
            it.name ?: WAYPOINT_NO_NAME_MARKER.toString()
        }
        listOf(values, names)
    },
    restore = { saved ->
        val values = saved.getOrNull(0) as? DoubleArray ?: return@listSaver emptyList()
        // Vor dieser Aenderung gerettete Zustaende (nur ein Eintrag) liefern
        // hier `null` — dann bleiben alle Wegpunkte schlicht namenlos statt
        // die Wiederherstellung platzen zu lassen.
        val names = (saved.getOrNull(1) as? String)?.split(WAYPOINT_NAME_SEPARATOR)
        List(values.size / 2) { index ->
            Waypoint(
                lat = values[index * 2],
                lon = values[index * 2 + 1],
                name = names?.getOrNull(index)?.takeIf { it != WAYPOINT_NO_NAME_MARKER.toString() },
            )
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
