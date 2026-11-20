package de.trailscape.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * # Was die Tour gebracht hat — die zwei, drei Dinge direkt nach dem Stopp
 *
 * Der Moment gleich nach dem Beenden einer Aufzeichnung ist der
 * motivierendste, den die App hat. Bestwerte, Meilensteine, der Vergleich auf
 * derselben Runde und das Wochenziel waren bis hierher aber nur im Tourdetail
 * zu finden, einen Tipp entfernt. [rideHighlights] fasst sie zu einer kurzen,
 * **geordneten** Liste zusammen, die das Tourblatt auf der Karte sofort zeigen
 * kann.
 *
 * Wie der Rest von `:core` reine Rechnung: keine Saetze (die baut die
 * Oberflaeche aus Art und Zahlen), keine Uhr — „heute" kommt herein.
 */
sealed interface RideHighlight {
    /** Ein neuer persoenlicher Bestwert ([recordsSetBy]). */
    data class Record(val gain: RecordGain) : RideHighlight

    /**
     * Die schnellste Fahrt auf dieser Runde — nur mit Vorgaenger: Die erste
     * Fahrt einer Runde ist zwar die schnellste, aber nie eine neue Bestzeit.
     *
     * @property deltaToBestOtherS gegen die bisher schnellste andere Fahrt,
     *   immer negativ (Sekunden schneller).
     */
    data class RouteBest(val count: Int, val deltaToBestOtherS: Int) : RideHighlight

    /**
     * Schneller als beim letzten Mal auf dieser Runde, aber nicht die
     * Bestzeit.
     *
     * @property deltaToPreviousS immer negativ (Sekunden schneller).
     * @property rank Platz unter allen [count] Fahrten der Runde (1 = schnellste).
     */
    data class RouteFaster(val deltaToPreviousS: Int, val rank: Int, val count: Int) : RideHighlight

    /** Eine Meilenstein-Stufe, die genau diese Tour ueberschritten hat ([milestonesReachedBy]). */
    data class MilestoneReached(val milestone: Milestone) : RideHighlight

    /** Diese Tour hat die Woche ueber das Wochenziel gebracht. */
    data class WeekGoalReached(val targetKm: Int, val riddenKm: Double) : RideHighlight

    /**
     * Stand zum Wochenziel, solange es noch offen ist — nur als Rueckfall,
     * wenn sonst nichts zu sagen ist (siehe [rideHighlights]).
     *
     * @property daysLeft Tage bis einschliesslich Sonntag, heute zaehlt mit (1…7).
     */
    data class WeekGoalProgress(
        val targetKm: Int,
        val riddenKm: Double,
        val remainingKm: Double,
        val daysLeft: Int,
    ) : RideHighlight

    /**
     * Die erste Tour der Woche haelt eine Wochenserie am Leben (bzw. macht
     * sie mindestens zwei Wochen lang) — [weeks] inklusive dieser Woche.
     */
    data class StreakExtended(val weeks: Int) : RideHighlight

    /** Diese Tour hat eine Monats-Challenge geschafft. */
    data class ChallengeReached(val challenge: Challenge) : RideHighlight
}

/**
 * Was die Tour [rideId] gebracht hat — hoechstens [max] Eintraege, das
 * Motivierendste zuerst.
 *
 * ## „Diese Tour hat es geschafft"
 * Wochenziel, Challenge, Meilenstein und Bestwert zaehlen nur, wenn die
 * Bedingung **mit** der Tour gilt und **ohne** sie nicht galt: verglichen
 * werden die Touren bis einschliesslich dieser mit denen davor. Wer nach dem
 * erreichten Wochenziel noch einmal faehrt, bekommt das Wochenziel nicht ein
 * zweites Mal gemeldet. Wochenziel und Challenge gelten ausserdem nur fuer
 * eine Tour der laufenden Woche bzw. des laufenden Monats von [today] — eine
 * alte Tour bringt dem heutigen Wochenziel nichts.
 *
 * ## Reihenfolge
 *  1. **Neue Bestweite** — die weiteste Tour ist das, was jeder sofort
 *     versteht und am liebsten hoert.
 *  2. **Bestzeit auf dieser Runde** — der direkte Vergleich mit sich selbst
 *     auf derselben Strecke.
 *  3. **Die uebrigen Bestwerte** (Hoehenmeter, Fahrzeit, Schnitt).
 *  4. **Wochenziel geschafft** — das Ziel, das „Heute" die ganze Woche zeigt.
 *  5. **Monats-Challenge geschafft.**
 *  6. **Meilenstein** (Gesamt-km, Hoehenmeter, Tourenzahl).
 *  7. **Wochenserie verlaengert.**
 *  8. **Schneller als beim letzten Mal** auf dieser Runde (ohne Bestzeit).
 *  9. **Noch x km bis zum Wochenziel** — nur, wenn sonst nichts zutrifft und
 *     das Ziel noch offen ist, damit nach einer gewoehnlichen Tour trotzdem
 *     eine Zeile mit Richtung dasteht.
 *
 * Danach wird auf [max] gekuerzt. Zwei Doppelungen fallen vorher heraus, weil
 * sie dieselbe Nachricht ein zweites Mal sagen: der Fahrzeit-Bestwert und die
 * Meilenstein-Leiter „weiteste Tour", sobald dieselbe Tour die Bestweite
 * gesetzt hat (laengere Strecke heisst fast immer laengere Zeit; die Stufe
 * „80 km am Stueck" sagt nichts, was „Neue Bestweite: 82,4 km" nicht schon
 * sagt).
 *
 * Trifft nichts zu, ist die Liste leer — die Oberflaeche zeigt dann nichts
 * Zusaetzliches. Lob wird nie erfunden. Ebenso leer bei unbekannter [rideId].
 *
 * @param facts alle gefahrenen Touren inkl. dieser, aelteste zuerst ([rideFacts]).
 * @param weekTargetKm das Wochenziel, das „Heute" zeigt (Planwoche oder
 *   mitwachsendes Ziel); `null` oder ≤ 0 = keins.
 * @param routeComparison Vergleich auf derselben Runde, `null` wenn unbekannt
 *   oder keine Wiederholung.
 */
fun rideHighlights(
    rideId: String,
    facts: List<RideFact>,
    today: LocalDate,
    weekTargetKm: Int?,
    routeComparison: RouteComparison?,
    max: Int = 3,
): List<RideHighlight> {
    val index = facts.indexOfFirst { it.id == rideId }
    if (index < 0 || max <= 0) return emptyList()
    val ride = facts[index]
    val before = facts.subList(0, index)
    val upTo = facts.subList(0, index + 1)

    val records = recordsSetBy(rideId, facts)
    val distanceRecord = records.firstOrNull { it.kind == RecordKind.DISTANCE }
    val otherRecords = records.filter {
        it.kind != RecordKind.DISTANCE && !(it.kind == RecordKind.TIME && distanceRecord != null)
    }

    val comparison = routeComparison?.takeIf { it.current.rideId == rideId }
    val routeBest = comparison?.let { c ->
        val delta = c.deltaToBestOtherS
        if (c.isBest && c.previous != null && delta != null && delta < 0) {
            RideHighlight.RouteBest(c.count, delta)
        } else {
            null
        }
    }
    val routeFaster = comparison?.let { c ->
        val delta = c.deltaToPreviousS
        if (!c.isBest && delta != null && delta < 0) RideHighlight.RouteFaster(delta, c.rank, c.count) else null
    }

    // Wochenziel: nur fuer eine Tour der laufenden Woche.
    val monday = today.with(DayOfWeek.MONDAY)
    val inThisWeek = !ride.date.isBefore(monday) && !ride.date.isAfter(today)
    val target = weekTargetKm?.takeIf { it > 0 }
    var weekReached: RideHighlight.WeekGoalReached? = null
    var weekProgress: RideHighlight.WeekGoalProgress? = null
    if (target != null && inThisWeek) {
        val withRide = periodTotals(upTo, monday, today).km
        val withoutRide = periodTotals(before, monday, today).km
        if (withRide >= target && withoutRide < target) {
            weekReached = RideHighlight.WeekGoalReached(target, withRide)
        } else if (withRide < target) {
            weekProgress = RideHighlight.WeekGoalProgress(
                targetKm = target,
                riddenKm = withRide,
                remainingKm = target - withRide,
                daysLeft = 8 - today.dayOfWeek.value,
            )
        }
    }

    // Challenges: nur fuer eine Tour des laufenden Monats — die Ziele haengen
    // an den Vormonaten, sind also mit und ohne diese Tour dieselben.
    val challenges = if (YearMonth.from(ride.date) == YearMonth.from(today) && !ride.date.isAfter(today)) {
        val was = challengeProgress(before, today).filter { it.reached }.map { it.challenge }
        challengeProgress(upTo, today)
            .filter { it.reached && it.challenge !in was }
            .map { RideHighlight.ChallengeReached(it.challenge) }
    } else {
        emptyList()
    }

    val milestones = milestonesReachedBy(rideId, facts)
        .filterNot { it.kind == MilestoneKind.LONGEST_RIDE && distanceRecord != null }
        .map { RideHighlight.MilestoneReached(it) }

    // Serie: nur die erste Tour ihrer Woche verlaengert sie.
    val rideMonday = ride.date.with(DayOfWeek.MONDAY)
    val firstOfWeek = before.none { it.date.with(DayOfWeek.MONDAY) == rideMonday }
    val streak = if (firstOfWeek) {
        weekStreak(upTo, ride.date).weeks.takeIf { it >= 2 }?.let { RideHighlight.StreakExtended(it) }
    } else {
        null
    }

    val ordered = buildList {
        distanceRecord?.let { add(RideHighlight.Record(it)) }
        routeBest?.let(::add)
        otherRecords.forEach { add(RideHighlight.Record(it)) }
        weekReached?.let(::add)
        addAll(challenges)
        addAll(milestones)
        streak?.let(::add)
        routeFaster?.let(::add)
        if (isEmpty()) weekProgress?.let(::add)
    }
    return ordered.take(max)
}
