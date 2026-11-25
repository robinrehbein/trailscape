package de.trailscape.core

/**
 * Woran eine GPX-Datei als **Planung** oder als **Aufzeichnung** erkannt wird
 * (Idee „GPX ohne Zeitstempel als geplante Route öffnen").
 *
 * Eine Aufzeichnung (Sportuhr, Strava, Trailscape selbst) traegt je Punkt
 * einen Zeitstempel, eine Route (Komoot, Routenplaner, `<rte>`) nur die
 * Strecke. Die Unterscheidung ist wichtig, weil eine Fahrt in Wochenfortschritt,
 * Fitness, Statistik und „Neue Wege" zaehlt und eine Planung nicht
 * (siehe [Ride.planned]).
 *
 * ## Regeln ([classifyTrackOrigin])
 * Aufzeichnung ist die Datei nur, wenn **alle** drei Bedingungen gelten:
 *  1. mindestens **zwei verschiedene** gueltige Zeitstempel — ein einzelner
 *     Stempel (oder lauter gleiche, wie ihn manche Exporter als Dateizeit auf
 *     jeden Punkt schreiben) liefert weder Dauer noch Tempo;
 *  2. mindestens die **Haelfte** der Punkte traegt einen Stempel — wenige
 *     Stempel in einer sonst stempellosen Strecke (etwa nur am Start) sind
 *     eine Route mit Zeitangabe, keine Fahrt;
 *  3. der Zeitstempel ist **plausibel** ([MIN_PLAUSIBLE_TRACK_TIME_MS]) —
 *     Platzhalter wie `1970-01-01T00:00:00Z` zaehlen als „kein Stempel".
 *
 * Alles andere ist eine Planung. Im Zweifel also Planung: Eine falsch als
 * Planung erkannte Fahrt laesst sich mit einem Handgriff umdrehen
 * ([asRidden]), eine falsch als Fahrt gezaehlte verfaelscht stillschweigend
 * die Statistik.
 *
 * Nicht gezaehlt werden Zeitstempel in `<metadata>` (Komoot schreibt dort die
 * Exportzeit hin), sie erreichen die Punkte gar nicht. Wegpunkte (`<wpt>`)
 * sind keine Strecke und werden vom Parser ohnehin ignoriert; mehrere Tracks
 * oder Segmente werden vor der Erkennung zu einer Punktliste zusammengelegt.
 */
enum class TrackOrigin { PLANNED_ROUTE, RECORDED_TRACK }

/** Fruehester Zeitstempel, der als echt gilt: 1990-01-01 UTC. Davor ist es ein Platzhalter. */
const val MIN_PLAUSIBLE_TRACK_TIME_MS: Long = 631_152_000_000L

/** Mindestanteil gestempelter Punkte fuer eine Aufzeichnung (siehe [classifyTrackOrigin]). */
private const val MIN_TIMED_SHARE = 0.5

private fun validTime(point: TrackPoint): Long? = point.time?.takeIf { it >= MIN_PLAUSIBLE_TRACK_TIME_MS }

/** Siehe [TrackOrigin]. */
fun classifyTrackOrigin(points: List<TrackPoint>): TrackOrigin {
    if (points.isEmpty()) return TrackOrigin.PLANNED_ROUTE
    var timed = 0
    var first: Long? = null
    var distinct = false
    for (p in points) {
        val t = validTime(p) ?: continue
        timed++
        if (first == null) first = t else if (t != first) distinct = true
    }
    return if (distinct && timed >= points.size * MIN_TIMED_SHARE) TrackOrigin.RECORDED_TRACK else TrackOrigin.PLANNED_ROUTE
}

/** Erster gueltiger Zeitstempel der Punkte — Startzeit einer Aufzeichnung; `null` ohne. */
fun firstTrackTime(points: List<TrackPoint>): Long? = points.firstNotNullOfOrNull(::validTime)

/**
 * Kennzahlen aus den Punkten allein: Strecke, Hoehenmeter, Zeiten
 * ([computeStats]) und Puls-Durchschnitt/-Maximum. Der eine Rechenweg fuer GPX-
 * Import ([rideFromGpx]) und das Umdrehen einer Planung in eine Fahrt
 * ([asRidden]).
 */
fun rideStatsFromPoints(points: List<TrackPoint>): RideStats {
    val base = computeStats(points)
    val hr = points.mapNotNull { it.hr }
    if (hr.isEmpty()) return base
    return base.copy(
        avgHrBpm = dartRound(hr.sum().toDouble() / hr.size).toInt(),
        maxHrBpm = hr.max(),
    )
}

/**
 * Dieselbe Strecke als **Planung**: gehoert nicht mehr zu Statistik, Fitness
 * und „Neue Wege" (alle filtern ueber [Ride.planned]). Die Punkte bleiben,
 * wie sie sind — auch ihre Zeitstempel, damit [asRidden] verlustfrei zurueck
 * kann. Zeitkennzahlen (Dauer, Tempo) fallen weg, eine Planung hat keine.
 *
 * [now] ist der Zeitpunkt der Aenderung; er geht in [Ride.updatedAt], damit
 * der Selfhost-Sync die Umwandlung per Last-Write-Wins uebernimmt. Liegt er
 * nicht ueber dem bisherigen `updatedAt`, wird dieses um 1 ms erhoeht — sonst
 * ginge die Aenderung auf einer Uhr, die nachgeht, still verloren.
 */
fun Ride.asPlanned(now: Long): Ride = copy(
    planned = true,
    stats = stats.copy(durationS = null, movingTimeS = null, avgSpeedKmh = null),
    updatedAt = maxOf(now, updatedAt + 1),
)

/**
 * Dieselbe Strecke als **gefahrene Tour** (Gegenstueck zu [asPlanned]): Die
 * Kennzahlen werden aus den Punkten neu berechnet, der Startzeitpunkt ist der
 * erste Zeitstempel der Punkte, sonst bleibt [Ride.createdAt] (Importzeit).
 */
fun Ride.asRidden(now: Long): Ride = copy(
    planned = false,
    createdAt = firstTrackTime(points) ?: createdAt,
    stats = rideStatsFromPoints(points),
    updatedAt = maxOf(now, updatedAt + 1),
)
