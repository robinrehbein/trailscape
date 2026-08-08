package de.trailscape.app.ui

import de.trailscape.core.Ride

/**
 * Duplikatpruefung fuer importierte Touren — geteilt von der Tourenliste
 * (`ui/rides/RidesScreen.kt`) und der Backup-Karte (`ui/more/BackupCard.kt`).
 *
 * Bewusst **inhaltsbasiert** und nicht ueber die ID: `rideFromGpx` (`:core`,
 * `Export.kt`) vergibt beim Import `System.currentTimeMillis().toString()`,
 * also bei jedem Aufruf eine neue ID. Ein ID-Vergleich koennte deshalb nie
 * anschlagen — dieselbe GPX-Datei liesse sich beliebig oft importieren.
 *
 * Merkmale, an denen zwei Touren als dieselbe gelten:
 *  * gleiche ID (Backup-Import: dort sind die IDs echt und stabil), **oder**
 *  * gleicher Startzeitpunkt ([Ride.createdAt], bei GPX der Zeitstempel des
 *    ersten Trackpunkts) **und** gleiche Punktzahl.
 *
 * Der Startzeitpunkt allein reicht nicht: Zwei Ausschnitte derselben Tour
 * beginnen zur selben Sekunde, sind aber unterschiedlich lang. Die Punktzahl
 * allein reicht erst recht nicht. Zusammen sind sie fuer den Zweck
 * („versehentlich zweimal dieselbe Datei gewaehlt") trennscharf genug, ohne
 * die Punktlisten Punkt fuer Punkt vergleichen zu muessen.
 */
fun findDuplicateRide(existing: List<Ride>, candidate: Ride): Ride? = existing.firstOrNull { ride ->
    ride.id == candidate.id ||
        (ride.createdAt == candidate.createdAt && ride.points.size == candidate.points.size)
}

/** Kurzform von [findDuplicateRide] fuer den blossen Ja/Nein-Fall. */
fun isDuplicateRide(existing: List<Ride>, candidate: Ride): Boolean =
    findDuplicateRide(existing, candidate) != null

/** Meldung, wenn ein Import an der Duplikatpruefung haengen bleibt. */
const val DUPLICATE_RIDE_MESSAGE: String = "Diese Tour ist bereits vorhanden."
