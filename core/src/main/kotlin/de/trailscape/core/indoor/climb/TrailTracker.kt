package de.trailscape.core.indoor.climb

import kotlin.math.floor
import kotlin.math.max

/**
 * # Leben auf der Strecke: Begleiter waehrend der Fahrt
 *
 * Reine Zustandsmaschine ohne Android und ohne Uhr: Die App fuettert sie mit
 * jedem Zustand der Sitzung ([update]) und zeigt, was zurueckkommt
 * ([TrailLive]). Sie kennt
 *  * die **Ankuendigung** eines Anstiegs ab [ANNOUNCE_M] vor dem Fuss,
 *  * den **laufenden Anstieg** (Rest, Rest-Hoehenmeter, Zeit, Abstand zur
 *    eigenen Bestzeit),
 *  * das **Ergebnis an der Kuppe** (Passhoehe, Zeit, „Neue Bestzeit"), das
 *    [RESULT_SHOW_MS] stehen bleibt,
 *  * **Kilometermarken** im Abstand [kmMarkIntervalM], je [MARKER_SHOW_MS].
 *
 * ## Wertung
 * Gewertet wird ein Anstieg nur, wenn er vom Fuss an gefahren wurde. Wer
 * mittendrin startet oder in der Pause versetzt wird (Sprung um mehr als
 * [JUMP_M] oder rueckwaerts), faehrt ihn „ohne Wertung" zu Ende. Die Zeit
 * zaehlt nur, solange die Sitzung laeuft (Pausen nicht), jeder Schritt
 * hoechstens [MAX_STEP_MS] — wie die Sitzung selbst. Fuss und Kuppe werden
 * zwischen zwei Zustaenden linear eingepasst.
 *
 * Auf einer Runde wird jeder Anstieg je Runde neu gefahren und gewertet;
 * eine neue Bestzeit gilt sofort auch fuer die naechste Runde.
 *
 * @param routeKey Streckenkennung der App (Schluessel der Bestzeiten).
 * @param bests die bisherigen Bestzeiten.
 */
class TrailTracker(
    private val routeKey: String,
    val climbs: List<Climb>,
    private val routeLengthM: Double,
    private val isLoop: Boolean,
    bests: ClimbBestBook,
    private val kmMarkIntervalM: Double = kmMarkIntervalFor(routeLengthM),
) {
    private var book: ClimbBestBook = bests
    private val newBests = ArrayList<ClimbBest>()
    private val efforts = ArrayList<ClimbEffort>()

    private var prevRouteM: Double? = null
    private var prevNowMs: Long? = null
    private var attempt: Attempt? = null
    private var lastResult: ClimbEffort? = null
    private var lastResultAtMs: Long = 0
    private var marker: TrailMarker? = null
    private var markerAtMs: Long = 0
    private var lap: Int = 1

    private class Attempt(
        val climb: Climb,
        val ranked: Boolean,
        var doneM: Double,
        var timeMs: Double,
        val best: ClimbBest?,
    ) {
        val splits = DoubleArray(ClimbBest.SPLITS + 1) { if (it == 0) 0.0 else -1.0 }
    }

    /** Alle beendeten Anstiege dieser Fahrt in Reihenfolge (fuer die Auswertung). */
    fun efforts(): List<ClimbEffort> = efforts.toList()

    /** Das Bestzeiten-Buch mit allen Verbesserungen dieser Fahrt. */
    fun book(): ClimbBestBook = book

    /** Neue Bestzeiten seit dem letzten Aufruf (zum Speichern). */
    fun drainNewBests(): List<ClimbBest> = newBests.toList().also { newBests.clear() }

    /**
     * Ein Zustand der Sitzung.
     *
     * @param nowMs monotone Uhr der Sitzung.
     * @param running die Sitzung laeuft (Phase RUNNING).
     * @param routeDistM Stelle innerhalb der Runde.
     * @param gradePct aktuelle Steigung (fuer die Einblendung).
     * @param epochMs Wanduhrzeit (fuer den Zeitpunkt einer Bestzeit).
     */
    fun update(nowMs: Long, running: Boolean, routeDistM: Double, gradePct: Double, epochMs: Long): TrailLive {
        val cur = routeDistM.coerceIn(0.0, routeLengthM)
        val prev = prevRouteM
        val dtMs = prevNowMs?.let { (nowMs - it).coerceIn(0, MAX_STEP_MS) } ?: 0L
        prevRouteM = cur
        prevNowMs = nowMs

        if (prev == null) {
            // Erster Zustand: am Fuss = gewertet, mitten im Anstieg = ohne Wertung.
            attempt = startedAt(cur)
            return live(nowMs, cur, gradePct)
        }

        var fwd = cur - prev
        var wrapped = false
        if (isLoop && fwd < -routeLengthM / 2) { fwd += routeLengthM; wrapped = true }
        if (fwd < -1.0 || fwd > JUMP_M) {
            // Versetzt: laufender Versuch endet ohne Ergebnis; neu im Anstieg = ohne Wertung (am Fuss gewertet).
            attempt = startedAt(cur)
            return live(nowMs, cur, gradePct)
        }
        if (wrapped) lap++
        val runMs = if (running) dtMs.toDouble() else 0.0
        if (fwd <= 0.0) {
            attempt?.let { it.timeMs += runMs }
            return live(nowMs, cur, gradePct)
        }

        // Kilometermarken, die in diesem Schritt ueberfahren wurden (die letzte gewinnt).
        crossedKm(prev, cur, wrapped)?.let { km -> marker = TrailMarker.Kilometer(km); markerAtMs = nowMs }

        // Laufender Anstieg.
        attempt?.let { a ->
            val remaining = a.climb.lengthM - a.doneM
            if (fwd >= remaining) {
                val part = if (fwd > 0) remaining / fwd else 1.0
                a.timeMs += runMs * part
                a.doneM = a.climb.lengthM
                finish(a, nowMs, epochMs)
                attempt = null
            } else {
                a.doneM += fwd
                a.timeMs += runMs
                recordSplits(a)
            }
        }

        // Neuer Anstieg, dessen Fuss in diesem Schritt ueberfahren wurde.
        if (attempt == null) {
            crossedStart(prev, cur, wrapped)?.let { c ->
                val past = if (wrapped && cur < c.startM) cur + routeLengthM - c.startM else cur - c.startM
                val part = if (fwd > 0) (past / fwd).coerceIn(0.0, 1.0) else 0.0
                val a = Attempt(c, ranked = true, doneM = past.coerceAtLeast(0.0), timeMs = runMs * part, best = book.bestFor(routeKey, c))
                recordSplits(a)
                attempt = a
                if (a.doneM >= c.lengthM) { finish(a, nowMs, epochMs); attempt = null }
            }
        }
        return live(nowMs, cur, gradePct)
    }

    private fun recordSplits(a: Attempt) {
        val step = a.climb.lengthM / ClimbBest.SPLITS
        for (i in 1..ClimbBest.SPLITS) {
            if (a.splits[i] < 0 && a.doneM >= i * step - 1e-6) a.splits[i] = a.timeMs
        }
    }

    private fun finish(a: Attempt, nowMs: Long, epochMs: Long) {
        val timeMs = Math.round(a.timeMs)
        val prevBest = if (a.ranked) book.bestFor(routeKey, a.climb) else null
        val newBest = a.ranked && timeMs > 0 && (prevBest == null || timeMs < prevBest.timeMs)
        val effort = ClimbEffort(
            routeKey = routeKey,
            climbKey = climbKey(routeKey, a.climb),
            climb = a.climb,
            lap = lap,
            ranked = a.ranked,
            timeMs = timeMs,
            previousBestMs = prevBest?.timeMs,
            newBest = newBest,
            achievedAtEpochMs = epochMs,
        )
        efforts.add(effort)
        lastResult = effort
        lastResultAtMs = nowMs
        if (newBest) {
            a.splits[ClimbBest.SPLITS] = a.timeMs
            // Fehlende Zehntel (sehr grobe Schritte) gleichmaessig auffuellen.
            for (i in 1 until ClimbBest.SPLITS) if (a.splits[i] < 0) a.splits[i] = a.timeMs * i / ClimbBest.SPLITS
            val splits = a.splits.map { Math.round(it) }.runningReduce { x, y -> max(x, y) }
            val best = ClimbBest(routeKey, a.climb.startM, a.climb.endM, timeMs, splits, epochMs)
            book = book.record(best)
            newBests.add(best)
        }
    }

    private fun climbAt(m: Double): Climb? = climbs.firstOrNull { m >= it.startM && m < it.endM }

    /** Versuch fuer einen Start an der Stelle [m]: am Fuss (bis [START_SLACK_M] dahinter) gewertet, sonst ohne Wertung. */
    private fun startedAt(m: Double): Attempt? = climbAt(m)?.let { c ->
        val ranked = m - c.startM <= START_SLACK_M
        Attempt(c, ranked = ranked, doneM = m - c.startM, timeMs = 0.0, best = if (ranked) book.bestFor(routeKey, c) else null)
    }

    /** Der Anstieg, dessen Fuss im Schritt (prev, cur] liegt. */
    private fun crossedStart(prev: Double, cur: Double, wrapped: Boolean): Climb? =
        if (!wrapped) {
            climbs.firstOrNull { it.startM > prev && it.startM <= cur }
        } else {
            climbs.firstOrNull { it.startM > prev } ?: climbs.firstOrNull { it.startM <= cur }
        }

    private fun crossedKm(prev: Double, cur: Double, wrapped: Boolean): Int? {
        if (kmMarkIntervalM <= 0) return null
        val end = if (wrapped) cur + routeLengthM else cur
        val k = floor(end / kmMarkIntervalM)
        if (k * kmMarkIntervalM <= prev || k <= 0) return null
        val markM = (k * kmMarkIntervalM).let { if (it >= routeLengthM) it - routeLengthM else it }
        // Am Ziel (bzw. am Rundenende) keine Marke — dort steht das Ziel.
        if (markM <= 0.0 || routeLengthM - markM < MARK_END_GAP_M) return null
        return Math.round(markM / 1000.0).toInt()
    }

    private fun live(nowMs: Long, cur: Double, gradePct: Double): TrailLive {
        val a = attempt
        val climbLive: ClimbLive? = when {
            a != null -> {
                val fraction = (a.doneM / a.climb.lengthM).coerceIn(0.0, 1.0)
                val eleNow = a.climb.startEleM + a.climb.gainM * fraction
                ClimbLive.On(
                    climb = a.climb,
                    remainingM = (a.climb.lengthM - a.doneM).coerceAtLeast(0.0),
                    remainingGainM = (a.climb.topEleM - eleNow).coerceAtLeast(0.0),
                    gradePct = gradePct,
                    elapsedMs = Math.round(a.timeMs),
                    deltaToBestMs = a.best?.takeIf { a.ranked }?.let { Math.round(a.timeMs - it.splitAt(fraction)) },
                    ranked = a.ranked,
                    bestMs = a.best?.timeMs?.takeIf { a.ranked },
                )
            }
            lastResult != null && nowMs - lastResultAtMs < RESULT_SHOW_MS -> ClimbLive.Done(lastResult!!)
            else -> nextClimbWithin(cur)
        }
        val m = marker?.takeIf { nowMs - markerAtMs < MARKER_SHOW_MS }
        return TrailLive(climbLive, m)
    }

    private fun nextClimbWithin(cur: Double): ClimbLive.Ahead? {
        var best: ClimbLive.Ahead? = null
        for (c in climbs) {
            var to = c.startM - cur
            if (to < 0 && isLoop) to += routeLengthM
            if (to <= 0 || to > ANNOUNCE_M) continue
            if (best == null || to < best.toStartM) best = ClimbLive.Ahead(c, to, book.bestFor(routeKey, c)?.timeMs)
        }
        return best
    }

    companion object {
        /** Ab hier vor dem Fuss wird ein Anstieg angekuendigt (m). */
        const val ANNOUNCE_M: Double = 500.0

        /** So lange bleibt das Ergebnis an der Kuppe stehen (ms). */
        const val RESULT_SHOW_MS: Long = 12_000

        /** So lange bleibt eine Kilometermarke stehen (ms). */
        const val MARKER_SHOW_MS: Long = 5_000

        /** Groesster Vorwaertsschritt zwischen zwei Zustaenden, der noch als gefahren gilt (m). */
        const val JUMP_M: Double = 100.0

        /** Laengster Zeitschritt, der zaehlt (ms) — wie die Sitzung. */
        const val MAX_STEP_MS: Long = 2_000

        /** Wer hoechstens so weit hinter dem Fuss startet, faehrt den Anstieg gewertet (m). */
        const val START_SLACK_M: Double = 5.0

        /** Keine Kilometermarke so kurz vor dem Ziel (m). */
        const val MARK_END_GAP_M: Double = 200.0

        /** Kilometermarken: jeden Kilometer, auf Strecken ab 40 km alle 5 km. */
        fun kmMarkIntervalFor(lengthM: Double): Double = if (lengthM >= 40_000.0) 5_000.0 else 1_000.0
    }
}

/**
 * Ein gefahrener Anstieg — das Ergebnis fuer Auswertung, Bestzeiten und
 * spaeter Abzeichen.
 *
 * @property ranked vom Fuss an gefahren; sonst „ohne Wertung" (keine Bestzeit).
 * @property timeMs Fahrzeit am Anstieg (bei ohne Wertung nur der gefahrene Teil).
 * @property previousBestMs Bestzeit vor diesem Versuch, `null` = keine (erste Zeit).
 * @property newBest schneller als [previousBestMs] — oder die erste gewertete Zeit.
 */
data class ClimbEffort(
    val routeKey: String,
    val climbKey: String,
    val climb: Climb,
    val lap: Int,
    val ranked: Boolean,
    val timeMs: Long,
    val previousBestMs: Long?,
    val newBest: Boolean,
    val achievedAtEpochMs: Long,
) {
    /** Erste gewertete Zeit an diesem Anstieg (es gab noch keine Bestzeit). */
    val firstTime: Boolean get() = ranked && previousBestMs == null

    /** Verbesserung gegenueber der alten Bestzeit in ms (positiv = schneller), `null` ohne alte Bestzeit. */
    val improvementMs: Long? get() = previousBestMs?.let { it - timeMs }?.takeIf { ranked }

    /** Mittleres Tempo am Anstieg in m/s. */
    val avgSpeedMps: Double get() = if (timeMs > 0) climb.lengthM / (timeMs / 1000.0) else 0.0

    /** Hoehenmeter je Stunde (VAM). */
    val vamMph: Double get() = if (timeMs > 0) climb.gainM / (timeMs / 3_600_000.0) else 0.0
}

/** Was gerade zur Bergwertung zu zeigen ist. */
sealed interface ClimbLive {
    /** Ein Anstieg kommt; [toStartM] bis zum Fuss. [bestMs] eigene Bestzeit, `null` = keine. */
    data class Ahead(val climb: Climb, val toStartM: Double, val bestMs: Long?) : ClimbLive

    /**
     * Am Anstieg.
     *
     * @property deltaToBestMs Abstand zur eigenen Bestzeit an dieser Stelle
     *   (positiv = langsamer), `null` = keine Bestzeit oder ohne Wertung.
     */
    data class On(
        val climb: Climb,
        val remainingM: Double,
        val remainingGainM: Double,
        val gradePct: Double,
        val elapsedMs: Long,
        val deltaToBestMs: Long?,
        val ranked: Boolean,
        val bestMs: Long?,
    ) : ClimbLive

    /** Kuppe erreicht. */
    data class Done(val effort: ClimbEffort) : ClimbLive
}

/** Eine dezente Marke an der Strecke. */
sealed interface TrailMarker {
    data class Kilometer(val km: Int) : TrailMarker
}

/** Alles, was der Begleiter gerade zeigt; beides `null` = nichts. */
data class TrailLive(val climb: ClimbLive?, val marker: TrailMarker?) {
    companion object {
        val NONE = TrailLive(null, null)
    }
}
