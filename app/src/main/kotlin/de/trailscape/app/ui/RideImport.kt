package de.trailscape.app.ui

import de.trailscape.core.Ride

/**
 * Duplikatpruefung fuer importierte Touren — geteilt von der Tourenliste
 * (`ui/rides/TourList.kt`) und der Backup-Karte (`ui/more/BackupCard.kt`).
 *
 * Die eigentliche Regel liegt seit dem Massenimport in `:core`
 * (`RideDuplicates.kt`), weil `importArchive` sie ebenfalls braucht. Diese
 * Datei bleibt als duenne Weiterleitung bestehen, damit die bestehenden
 * Aufrufer im UI-Paket unveraendert weiterlaufen.
 */
fun findDuplicateRide(existing: List<Ride>, candidate: Ride): Ride? =
    de.trailscape.core.findDuplicateRide(existing, candidate)

/** Kurzform von [findDuplicateRide] fuer den blossen Ja/Nein-Fall. */
fun isDuplicateRide(existing: List<Ride>, candidate: Ride): Boolean =
    de.trailscape.core.isDuplicateRide(existing, candidate)

/** Meldung, wenn ein Import an der Duplikatpruefung haengen bleibt. */
const val DUPLICATE_RIDE_MESSAGE: String = de.trailscape.core.DUPLICATE_RIDE_MESSAGE
