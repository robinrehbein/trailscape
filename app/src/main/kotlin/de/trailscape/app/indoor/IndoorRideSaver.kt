package de.trailscape.app.indoor

import de.trailscape.core.Ride
import de.trailscape.core.indoor.IndoorRideRecord

/**
 * Mindestdauer einer Indoor-Fahrt in Sekunden, ab der sie als Tour gespeichert
 * wird (`docs/indoor.md` Abschnitt 10.1 Nr. 6): Kuerzere Fahrten sind
 * Probelaeufe und wuerden Liste, Belastung und Serie nur verfaelschen.
 */
const val MIN_INDOOR_RIDE_S: Int = 60

/**
 * Macht aus der fertigen Aufzeichnung einer Indoor-Fahrt eine Tour
 * (`docs/indoor.md` Abschnitt 10.2): `indoor = true`, `planned = false`,
 * Punkte und Kennzahlen unveraendert aus [record].
 *
 * Liefert `null`, wenn die Fahrt nicht gespeichert werden soll:
 *  * die bewegte Zeit ([de.trailscape.core.indoor.IndoorTotals.movingS])
 *    liegt unter [MIN_INDOOR_RIDE_S], oder
 *  * es gibt weniger als zwei Punkte (ohne Strecke laesst sich nichts zeigen,
 *    exportieren oder auswerten).
 *
 * Der Name kommt fertig herein (Ressource „Indoor · <Strecke>“), damit diese
 * Funktion ohne Android-Kontext testbar bleibt. `createdAt` ist der
 * Startzeitpunkt der Fahrt — wie bei jeder aufgezeichneten Tour —, damit
 * Sortierung, Wochenziel und Duplikatpruefung denselben Bezug haben.
 */
fun indoorRideToRide(
    record: IndoorRideRecord,
    name: String,
    id: String = java.util.UUID.randomUUID().toString(),
): Ride? {
    if (record.totals.movingS < MIN_INDOOR_RIDE_S) return null
    if (record.points.size < 2) return null
    return Ride(
        id = id,
        name = name,
        createdAt = record.startedAtEpochMs,
        stats = record.stats,
        points = record.points,
        planned = false,
        indoor = true,
    )
}
