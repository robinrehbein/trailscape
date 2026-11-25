package de.trailscape.core.indoor.preview

import de.trailscape.core.indoor.world.ActorState
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.effectiveCrr
import de.trailscape.core.indoor.sampleAt
import de.trailscape.core.indoor.steadySpeedMps
import de.trailscape.core.indoor.world.WorldState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * # Strecke vor der Tour ansehen (Idee 4)
 *
 * Reine Logik der 3D-Vorschau einer Strecke — ohne Trainer, ohne Sitzung, ohne
 * Android. Die App treibt damit die vorhandene Darstellung (`WorldRenderer`)
 * ueber `setState`, genau wie die Fahrt es tut; die Kamera ist eine der
 * vorhandenen (`CameraMode`). Beschrieben in `docs/indoor/vorschau.md`.
 *
 *  * [PreviewTimeline]: Zeitplan einer gedachten, **realistischen** Fahrt ueber
 *    die Strecke — je Stueck das Gleichgewichtstempo der Fahrphysik
 *    (`steadySpeedMps`, dieselbe wie in der Sitzung) bei einer festen
 *    Ausdauerleistung, begrenzt auf [PreviewTimeline.MIN_SPEED_MPS]..
 *    [PreviewTimeline.MAX_SPEED_MPS]. Fuer eine gefahrene Tour laesst er
 *    sich auf deren Schnitt strecken („1× = so schnell wie damals“).
 *  * [PreviewPlayer]: der Abspieler — Wiedergabe/Pause, Geschwindigkeit
 *    1×/2×/4×/8× dieses Zeitplans, Springen auf eine Stelle, Warten auf
 *    Kacheln (wie ein Video beim Puffern, mit Obergrenze) und das Ende.
 *
 * Alle Zeiten sind Millisekunden einer monotonen Uhr, die der Aufrufer
 * hineinreicht (in der App `SystemClock.elapsedRealtime()`, im Test eine
 * eigene). Nichts hier wirft bei kaputten Zahlen; sie werden geklemmt.
 */

/** Die waehlbaren Geschwindigkeiten der Vorschau (Vielfache der realistischen Fahrt). */
val PREVIEW_SPEED_FACTORS: List<Int> = listOf(1, 2, 4, 8)

/** Der naechste Wert in [PREVIEW_SPEED_FACTORS] nach [current] (nach 8× wieder 1×). */
fun nextPreviewSpeedFactor(current: Int): Int {
    val i = PREVIEW_SPEED_FACTORS.indexOf(current)
    return if (i < 0) PREVIEW_SPEED_FACTORS.first() else PREVIEW_SPEED_FACTORS[(i + 1) % PREVIEW_SPEED_FACTORS.size]
}

/**
 * Zeitplan einer gedachten Fahrt: zu jedem Stuetzpunkt der Strecke die Zeit
 * in Sekunden ab dem Start. Zwischen zwei Punkten gilt ein festes Tempo.
 */
class PreviewTimeline private constructor(
    val route: IndoorRoute,
    private val distM: DoubleArray,
    private val timeS: DoubleArray,
) {
    /** Laenge der Strecke (eine Runde) in Metern. */
    val lengthM: Double get() = distM.last()

    /** Dauer der ganzen Strecke bei 1× in Sekunden. */
    val totalS: Double get() = timeS.last()

    /** Zeit in s, zu der die gedachte Fahrt an [d] ist (auf `0..lengthM` geklemmt). */
    fun timeAt(d: Double): Double {
        val x = clampDist(d)
        val i = segmentOf(distM, x)
        val span = distM[i + 1] - distM[i]
        val t = if (span > 0) (x - distM[i]) / span else 0.0
        return timeS[i] + (timeS[i + 1] - timeS[i]) * t
    }

    /** Stelle in Metern zur Zeit [t] in s (auf `0..totalS` geklemmt). */
    fun distanceAt(t: Double): Double {
        val x = if (t.isFinite()) t.coerceIn(0.0, totalS) else 0.0
        val i = segmentOf(timeS, x)
        val span = timeS[i + 1] - timeS[i]
        val f = if (span > 0) (x - timeS[i]) / span else 0.0
        return distM[i] + (distM[i + 1] - distM[i]) * f
    }

    /** Tempo der gedachten Fahrt (1×) an der Stelle [d] in m/s. */
    fun speedAt(d: Double): Double {
        val i = segmentOf(distM, clampDist(d))
        val dt = timeS[i + 1] - timeS[i]
        return if (dt > 0) (distM[i + 1] - distM[i]) / dt else MIN_SPEED_MPS
    }

    private fun clampDist(d: Double): Double = if (d.isFinite()) d.coerceIn(0.0, lengthM) else 0.0

    companion object {
        /** Unter diesem Tempo kriecht niemand eine Vorschau hinauf (5,4 km/h). */
        const val MIN_SPEED_MPS: Double = 1.5

        /** Bergab wird nicht schneller als 60 km/h gerollt. */
        const val MAX_SPEED_MPS: Double = 60.0 / 3.6

        /** Anteil der FTP, mit dem die gedachte Fahrt tritt (lockere Ausdauer). */
        const val ENDURANCE_FTP_SHARE: Double = 0.65

        /** Ohne brauchbare FTP: diese Leistung in Watt. */
        const val FALLBACK_POWER_W: Double = 160.0

        /**
         * Strecken auf einen Schnitt ([of], `targetAvgSpeedMps`) nur in diesem
         * Rahmen — ein offensichtlich kaputter Schnitt (Pausen als Fahrzeit,
         * Tippfehler) bleibt beim Modell.
         */
        const val MIN_TARGET_SPEED_MPS: Double = 2.0
        const val MAX_TARGET_SPEED_MPS: Double = 15.0

        /**
         * Rechnet den Zeitplan fuer [route].
         *
         * @param powerW Leistung der gedachten Fahrt; Vorgabe [ENDURANCE_FTP_SHARE] der FTP.
         * @param targetAvgSpeedMps wenn gesetzt und plausibel: Der ganze Zeitplan
         *   wird gleichmaessig so gestreckt, dass sein Schnitt diesem Wert
         *   entspricht (gefahrene Tour: Strecke / Bewegungszeit). Das Verhaeltnis
         *   von Berg zu Ebene bleibt das des Modells.
         */
        fun of(route: IndoorRoute, rider: RiderSetup, powerW: Double? = null, targetAvgSpeedMps: Double? = null): PreviewTimeline {
            val p = powerW?.takeIf { it.isFinite() && it > 0 }
                ?: (rider.ftpW * ENDURANCE_FTP_SHARE).takeIf { it.isFinite() && it > 0 }
                ?: FALLBACK_POWER_W
            val pts = route.points
            val n = pts.size
            val dist = DoubleArray(n) { pts[it].distM }
            val time = DoubleArray(n)
            for (i in 1 until n) {
                val a = pts[i - 1]
                val b = pts[i]
                val span = b.distM - a.distM
                if (span <= 0) {
                    time[i] = time[i - 1]
                    continue
                }
                val grade = (b.eleM - a.eleM) / span * 100.0
                val mid = (a.distM + b.distM) / 2
                val kind = route.surfaces.firstOrNull { mid >= it.fromM && mid < it.toM }?.kind ?: SurfaceKind.UNKNOWN
                val crr = effectiveCrr(kind, rider.profileCrr)
                val v = runCatching { steadySpeedMps(p, grade, crr, (a.eleM + b.eleM) / 2, 0.0, rider) }.getOrDefault(0.0)
                val speed = if (v.isFinite()) v.coerceIn(MIN_SPEED_MPS, MAX_SPEED_MPS) else MIN_SPEED_MPS
                time[i] = time[i - 1] + span / speed
            }
            val total = time.last()
            val target = targetAvgSpeedMps?.takeIf { it.isFinite() && it in MIN_TARGET_SPEED_MPS..MAX_TARGET_SPEED_MPS }
            if (target != null && total > 0 && dist.last() > 0) {
                val scale = (dist.last() / target) / total
                for (i in time.indices) time[i] *= scale
            }
            return PreviewTimeline(route, dist, time)
        }

        /** Index i mit `xs[i] <= x <= xs[i+1]` (xs aufsteigend, mindestens zwei Werte). */
        private fun segmentOf(xs: DoubleArray, x: Double): Int {
            var lo = 0
            var hi = xs.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (xs[mid] <= x) lo = mid else hi = mid
            }
            return lo
        }
    }
}

/**
 * Was die Vorschau in einem Bild zeigt.
 *
 * @property distM Stelle auf der Strecke in Metern (`0..lengthM`).
 * @property speedMps gezeigtes Tempo (1×-Tempo × Faktor, gedeckelt); 0, wenn es steht.
 * @property elapsedS Zeit der gedachten Fahrt an dieser Stelle (bei 1×).
 * @property remainingS was bei der gewaehlten Geschwindigkeit noch zu sehen ist, in Sekunden echter Zeit.
 * @property gradePct Steigung um die Stelle (ueber [PreviewPlayer.GRADE_WINDOW_M] gemittelt).
 * @property waitingForTiles die Wiedergabe wartet, bis die Kacheln der Stelle da sind.
 * @property atEnd am Ziel angekommen (Wiedergabe steht).
 */
data class PreviewFrame(
    val distM: Double,
    val lengthM: Double,
    val eleM: Double,
    val gradePct: Double,
    val speedMps: Double,
    val elapsedS: Double,
    val totalS: Double,
    val remainingS: Double,
    val playing: Boolean,
    val speedFactor: Int,
    val waitingForTiles: Boolean,
    val atEnd: Boolean,
)

/**
 * Der Abspieler der Vorschau (siehe Dateikopf). Nicht threadsicher — die App
 * ruft ihn nur vom Hauptthread.
 *
 * Die Wiedergabe beginnt angehalten. [tick] schreibt mit der Uhr fort; ein
 * einzelner Schritt ist hoechstens [MAX_STEP_MS] lang (nach einer Pause der
 * App springt sie nicht). Das gezeigte Tempo ist auf [MAX_PLAYBACK_MPS]
 * gedeckelt, damit Darstellung und Kachelnachschub mitkommen — bei 8× bergab
 * laeuft die Vorschau also etwas langsamer als 8×.
 */
class PreviewPlayer(val timeline: PreviewTimeline, startDistM: Double = 0.0) {
    private val route: IndoorRoute get() = timeline.route

    /** Stelle in Metern. */
    var distM: Double = clamp(startDistM)
        private set

    var playing: Boolean = false
        private set

    var speedFactor: Int = PREVIEW_SPEED_FACTORS.first()
        private set

    private var lastTickMs: Long? = null
    private var tilesMissing = false
    private var waitStartedMs: Long? = null
    private var noWaitUntilMs: Long = Long.MIN_VALUE
    private var lastSpeedMps = 0.0

    val atEnd: Boolean get() = distM >= timeline.lengthM - END_EPS_M

    /** Wiedergabe. Am Ziel beginnt sie wieder am Start. */
    fun play(nowMs: Long) {
        if (atEnd) distM = 0.0
        playing = true
        lastTickMs = nowMs
    }

    fun pause() {
        playing = false
        lastTickMs = null
        lastSpeedMps = 0.0
    }

    fun togglePlay(nowMs: Long) = if (playing) pause() else play(nowMs)

    /** Setzt die Geschwindigkeit; Werte ausserhalb von [PREVIEW_SPEED_FACTORS] werden auf den naechsten gelegt. */
    fun setSpeedFactor(factor: Int) {
        speedFactor = PREVIEW_SPEED_FACTORS.minByOrNull { abs(it - factor) } ?: 1
    }

    /** Springt an die Stelle [d] (Schieber ueber dem Hoehenprofil). Die Wiedergabe laeuft weiter, wenn sie lief. */
    fun seekTo(d: Double) {
        distM = clamp(d)
        // Nach einem Sprung ist an der neuen Stelle noch nichts geladen: neu warten duerfen.
        noWaitUntilMs = Long.MIN_VALUE
        waitStartedMs = null
    }

    /**
     * Meldung der Darstellung: Fuer die Stelle fehlen noch Kacheln
     * (`onLoading(geladen < noetig)`). Die Wiedergabe wartet dann, hoechstens
     * [MAX_WAIT_MS] am Stueck; danach geht sie weiter und wartet
     * [WAIT_COOLDOWN_MS] lang nicht erneut — eine Kachel, die nie kommt
     * (Lesefehler), haelt die Vorschau so nicht auf.
     */
    fun setTilesMissing(missing: Boolean) {
        tilesMissing = missing
        if (!missing) waitStartedMs = null
    }

    /** Ob die Wiedergabe gerade auf Kacheln wartet (zur Zeit [nowMs]). */
    fun isWaiting(nowMs: Long): Boolean {
        if (!playing || !tilesMissing || nowMs < noWaitUntilMs) return false
        val started = waitStartedMs ?: return true
        return nowMs - started < MAX_WAIT_MS
    }

    /** Schreibt bis [nowMs] fort und liefert das Bild. */
    fun tick(nowMs: Long): PreviewFrame {
        val last = lastTickMs
        lastTickMs = nowMs
        lastSpeedMps = 0.0
        if (playing && last != null) {
            val dtMs = (nowMs - last).coerceIn(0L, MAX_STEP_MS)
            if (tilesMissing && nowMs >= noWaitUntilMs) {
                val started = waitStartedMs ?: nowMs.also { waitStartedMs = it }
                if (nowMs - started >= MAX_WAIT_MS) {
                    noWaitUntilMs = nowMs + WAIT_COOLDOWN_MS
                    waitStartedMs = null
                }
            }
            if (!isWaiting(nowMs) && dtMs > 0) advance(dtMs / 1000.0)
            if (atEnd) {
                distM = timeline.lengthM
                playing = false
                lastSpeedMps = 0.0
            }
        }
        return frame(nowMs)
    }

    private fun advance(dtS: Double) {
        val t0 = timeline.timeAt(distM)
        val wanted = timeline.distanceAt(t0 + dtS * speedFactor) - distM
        val step = min(wanted, MAX_PLAYBACK_MPS * dtS)
        distM = clamp(distM + max(0.0, step))
        lastSpeedMps = max(0.0, step) / dtS
    }

    /** Das Bild zum jetzigen Stand, ohne fortzuschreiben. */
    fun frame(nowMs: Long): PreviewFrame {
        val sample = route.sampleAt(distM)
        val elapsed = timeline.timeAt(distM)
        val remaining = (timeline.totalS - elapsed).coerceAtLeast(0.0) / speedFactor
        return PreviewFrame(
            distM = distM,
            lengthM = timeline.lengthM,
            eleM = sample.eleM,
            gradePct = gradeAround(distM),
            speedMps = if (playing) lastSpeedMps else 0.0,
            elapsedS = elapsed,
            totalS = timeline.totalS,
            remainingS = remaining,
            playing = playing,
            speedFactor = speedFactor,
            waitingForTiles = isWaiting(nowMs),
            atEnd = atEnd,
        )
    }

    /**
     * Der Zustand fuer die Darstellung: der Fahrer als Markierung an der
     * Stelle, mit Tempo fuer die Fortschreibung zwischen zwei Bildern und
     * tretenden Beinen, solange es laeuft.
     */
    fun worldState(nowMs: Long): WorldState {
        val moving = playing && lastSpeedMps > 0.0
        return WorldState(
            timeMs = nowMs,
            rider = ActorState(
                distM = distM,
                speedMps = if (moving) lastSpeedMps else 0.0,
                cadenceRpm = if (moving) PREVIEW_CADENCE_RPM else 0.0,
            ),
            ghost = null,
            paused = !moving,
        )
    }

    /** Steigung um [d], gemittelt ueber [GRADE_WINDOW_M] (die Rohsteigung eines Stuecks springt). */
    private fun gradeAround(d: Double): Double {
        val half = GRADE_WINDOW_M / 2
        val a = clamp(d - half)
        val b = clamp(d + half)
        if (b - a < 1.0) return 0.0
        val g = (route.sampleAt(b).eleM - route.sampleAt(a).eleM) / (b - a) * 100.0
        return if (g.isFinite()) g else 0.0
    }

    private fun clamp(d: Double): Double = if (d.isFinite()) d.coerceIn(0.0, timeline.lengthM) else 0.0

    companion object {
        /** Hoechstes gezeigtes Tempo (216 km/h) — Grenze fuer Kamera, Fortschreibung und Kachelnachschub. */
        const val MAX_PLAYBACK_MPS: Double = 60.0

        /** Laengster Schritt eines Ticks. */
        const val MAX_STEP_MS: Long = 250L

        /** So lange wartet die Wiedergabe hoechstens am Stueck auf Kacheln. */
        const val MAX_WAIT_MS: Long = 4_000L

        /** Nach einer abgelaufenen Wartezeit so lange nicht erneut warten. */
        const val WAIT_COOLDOWN_MS: Long = 10_000L

        /** Fenster der gemittelten Steigung in Metern. */
        const val GRADE_WINDOW_M: Double = 50.0

        /** Trittfrequenz der Markierung, solange es laeuft. */
        const val PREVIEW_CADENCE_RPM: Double = 85.0

        private const val END_EPS_M = 0.5
    }
}
