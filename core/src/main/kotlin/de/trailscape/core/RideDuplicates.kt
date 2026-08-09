package de.trailscape.core

/**
 * Duplikatpruefung fuer importierte Touren.
 *
 * Lag zuvor in `app/ui/RideImport.kt`, gehoert aber ins Domaenenmodell: der
 * Massenimport ([importArchive]) braucht dieselbe Regel wie die Tourenliste
 * und die Backup-Karte, und er lebt in `:core`. `app/ui/RideImport.kt`
 * delegiert seitdem nur noch hierher.
 *
 * Bewusst **inhaltsbasiert** und nicht ueber die ID: [rideFromGpx] und
 * [rideFromFit] vergeben beim Import `System.currentTimeMillis().toString()`,
 * also bei jedem Aufruf eine neue ID. Ein reiner ID-Vergleich koennte deshalb
 * nie anschlagen — dieselbe Datei liesse sich beliebig oft importieren.
 *
 * Merkmale, an denen zwei Touren als dieselbe gelten:
 *  * gleiche ID (Backup-Import: dort sind die IDs echt und stabil), **oder**
 *  * gleicher Startzeitpunkt ([Ride.createdAt], beim Datei-Import der
 *    Zeitstempel des ersten Trackpunkts) **und** gleiche Punktzahl.
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
