package de.trailscape.core

import kotlin.math.abs

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
 *
 * Fuer importierte **Planungen** (GPX ohne `<time>`, siehe [rideFromGpx])
 * taugt der Startzeitpunkt nicht — er ist dort der Importzeitpunkt, also bei
 * jedem Import ein anderer. Zwei Planungen gelten deshalb als dieselbe, wenn
 * Punktzahl **und** Distanz uebereinstimmen: Die Distanz ist aus den Punkten
 * berechnet und damit fuer dieselbe Datei gleich, fuer eine abweichende Route
 * praktisch nie. Verglichen wird mit einer Toleranz von
 * [PLANNED_DISTANCE_TOLERANCE_KM] statt bitgenau, damit Rundungen (etwa beim
 * Speichern oder Synchronisieren der Zusammenfassung) nicht stoeren.
 *
 * Grenzen, bewusst in Kauf genommen: Eine auf anderem Weg entstandene Fassung
 * derselben Route (neu abgetastet, andere Punktzahl) gilt als neu, und aendert
 * sich einmal die Distanzberechnung des Parsers um mehr als die Toleranz,
 * erkennt die Pruefung frueher importierte Planungen nicht mehr. Ebenso
 * zaehlen Planungen, die **vor** der Erkennung „GPX ohne Zeit = Planung"
 * als Fahrt importiert wurden (Startzeit = damaliger Importzeitpunkt), nicht
 * als Dublette einer neu importierten Planung.
 *
 * Der Bestand kommt als [RideInfo] herein — die Pruefung braucht nur
 * Startzeitpunkt und [RideInfo.pointCount], laeuft also unveraendert ueber
 * die punktfreien Zusammenfassungen der Tourenliste.
 */
fun findDuplicateRide(existing: List<RideInfo>, candidate: Ride): RideInfo? =
    existing.firstOrNull { ride ->
        ride.id == candidate.id ||
            (ride.createdAt == candidate.createdAt && ride.pointCount == candidate.points.size) ||
            (
                candidate.planned && ride.planned &&
                    ride.pointCount == candidate.points.size &&
                    abs(ride.stats.distanceKm - candidate.stats.distanceKm) < PLANNED_DISTANCE_TOLERANCE_KM
                )
    }

/** Toleranz des Distanzvergleichs fuer Planungen in [findDuplicateRide]: ein Meter. */
internal const val PLANNED_DISTANCE_TOLERANCE_KM: Double = 0.001

/** Kurzform von [findDuplicateRide] fuer den blossen Ja/Nein-Fall. */
fun isDuplicateRide(existing: List<RideInfo>, candidate: Ride): Boolean =
    findDuplicateRide(existing, candidate) != null

/** Meldung, wenn ein Import an der Duplikatpruefung haengen bleibt. */
const val DUPLICATE_RIDE_MESSAGE: String = "Diese Tour ist bereits vorhanden."
