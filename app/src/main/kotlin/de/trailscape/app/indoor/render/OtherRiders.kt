package de.trailscape.app.indoor.render

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * # Andere Radfahrer (rein dekorativ)
 *
 * Wenige Mitfahrer und Gegenverkehr auf der Strecke, ohne Einfluss auf Physik oder
 * Windschatten. Reine Logik ohne Filament, getestet (`OtherRidersTest`); die Darstellung
 * zeichnet die [Shown] mit dem Fahrermodell in anderen Trikotfarben.
 *
 *  * **Tempo je Steigung:** jeder Fahrer hat eine feste Dauerleistung (150–260 W) und faehrt
 *    das Gleichgewichtstempo aus Rollwiderstand, Hangabtrieb und Luftwiderstand (bergab
 *    hoechstens [MAX_DOWNHILL_MPS]); das Tempo folgt weich (τ 3 s).
 *  * **Gegenverkehr** faehrt links (von uns aus), mit umgekehrter Steigung.
 *  * **Ueberholen statt Durchfahren:** Gleichgerichtete Fahrer fahren in der Spur des
 *    Nutzers. Kommt einer von hinten schneller heran, schert er nach links aus; holt der
 *    Nutzer einen ein, weicht dieser nach rechts aus (zu schmal: nach links). Untereinander
 *    ueberholt der Schnellere links. Das Fenster waechst mit der Annaeherung (2,5 s vorher
 *    ausscheren), der Seitenversatz folgt weich (τ 0,8 s).
 *  * **Kommen und Gehen:** Fahrer leben in einem Fenster von [BEHIND_M] hinter bis [AHEAD_M]
 *    vor dem Nutzer; wer es verlaesst, taucht nach einer Pause am passenden Rand wieder auf
 *    (schnellere hinten, langsamere und Gegenverkehr vorn) — ausserhalb des Blicks bzw. im Dunst.
 */
internal class OtherRiders(
    seed: Long = 7L,
    /** Laenge der Strecke (m) und ob sie eine Runde ist. */
    private val lengthM: Double,
    private val loop: Boolean,
    /** Steigung in Prozent an Laufmeter d (in Fahrtrichtung der Strecke). */
    private val gradePctAt: (Double) -> Double,
    /** Breite des Wegs an Laufmeter d. */
    private val widthAt: (Double) -> Double,
) {
    /** Ein gezeigter Fahrer: Laufmeter, Seitenversatz (rechts +), Richtung, Phasen fuer die Animation. */
    data class Shown(
        val id: Int,
        val distM: Double,
        val lateralM: Double,
        val oncoming: Boolean,
        val speedMps: Double,
        val jersey: Int,
        val crankPhase: Double,
        val wheelPhase: Double,
    )

    private class Rider(
        val id: Int,
        var distM: Double,
        val oncoming: Boolean,
        val powerW: Double,
        val cadenceRpm: Double,
        val jersey: Int,
        var speed: Double,
        var lateral: Double,
        var crank: Double = 0.0,
        var wheel: Double = 0.0,
    )

    private val rnd = java.util.Random(seed)
    private val riders = ArrayList<Rider>()
    private var nextId = 0
    private var respawnIn = DoubleArray(0)
    private var initialized = false

    /** Hoechstzahl gleichzeitig; aendern erlaubt (Einstellungen), Ueberzaehlige verschwinden. */
    var maxCount: Int = 3
        set(value) {
            field = value.coerceIn(0, MAX_RIDERS)
        }

    val count: Int get() = riders.size

    /**
     * Schreibt alle Fahrer um [dt] Sekunden fort. [playerDistM]/[playerSpeedMps]: der Nutzer,
     * [playerLateralM]: sein Seitenversatz. Liefert die gezeigten Fahrer.
     */
    fun update(dt: Double, playerDistM: Double, playerSpeedMps: Double, playerLateralM: Double): List<Shown> {
        val step = dt.coerceIn(0.0, 0.25)
        if (!initialized) {
            initialized = true
            respawnIn = DoubleArray(MAX_RIDERS) { 0.0 }
            // Zu Beginn verteilt vor dem Nutzer, damit man nicht allein startet.
            for (k in 0 until maxCount) spawn(playerDistM, playerSpeedMps, initial = true)
        }
        while (riders.size > maxCount) riders.removeAt(riders.lastIndex)

        // Fortschreiben
        for (r in riders) {
            val g = gradeFor(r, r.distM)
            val target = steadySpeed(r.powerW, g)
            r.speed += (target - r.speed) * (1 - exp(-step / SPEED_TAU_S))
            r.distM += (if (r.oncoming) -1 else 1) * r.speed * step
            r.crank = (r.crank + r.cadenceRpm / 60.0 * step * min(1.0, r.speed / 3.0)) % 1.0
            r.wheel = (r.wheel + r.speed / WHEEL_CIRCUMFERENCE_M * step) % 1.0
        }

        // Verlassen das Fenster → weg, spaeter neu
        val it = riders.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val gap = gapTo(r.distM, playerDistM)
            val outside = gap < -BEHIND_M - 20 || gap > AHEAD_M + 40 || (!loop && (r.distM < 0 || r.distM > lengthM))
            if (outside) it.remove()
        }
        if (riders.size < maxCount) {
            val slot = riders.size
            respawnIn[slot] -= step
            if (respawnIn[slot] <= 0) {
                spawn(playerDistM, playerSpeedMps, initial = false)
                respawnIn[slot] = RESPAWN_MIN_S + rnd.nextDouble() * (RESPAWN_MAX_S - RESPAWN_MIN_S)
            }
        }

        // Seitenversatz: Spur, Ueberholen
        val sorted = riders.sortedBy { gapTo(it.distM, playerDistM) }
        val targets = HashMap<Int, Double>()
        for (r in sorted) {
            val w = widthAt(r.distM)
            val edge = max(0.0, w / 2 - EDGE_MARGIN_M)
            var lat = if (r.oncoming) -min(edge, laneOffsetM(w) + 0.25) else playerLateralM
            if (!r.oncoming) {
                val gap = gapTo(r.distM, playerDistM)
                // Fenster nach Annaeherung: rund 2,5 s vorher ausscheren (τ 0,8 s braucht die Zeit)
                val window = max(PASS_WINDOW_M, abs(r.speed - playerSpeedMps) * PASS_LEAD_S + 3.0)
                if (abs(gap) < window) {
                    val faster = r.speed > playerSpeedMps + 0.1
                    val room = edge - playerLateralM
                    val side = r.lateral - playerLateralM
                    lat = when {
                        // Schon seitlich daneben: Seite halten (nie quer durch die Spur des Nutzers)
                        side < -0.3 -> playerLateralM - PASS_SEPARATION_M
                        side > 0.3 && room >= PASS_SEPARATION_M * 0.6 -> playerLateralM + PASS_SEPARATION_M
                        faster || room < PASS_SEPARATION_M * 0.8 -> playerLateralM - PASS_SEPARATION_M
                        else -> playerLateralM + PASS_SEPARATION_M
                    }
                }
                // Gegen den Vordermann: der Schnellere schert links aus — ausser das fuehrte in die
                // Spur des Nutzers, wenn der nah ist; dann rechts am Vordermann vorbei.
                val gapP = gapTo(r.distM, playerDistM)
                val nearPlayer = abs(gapP) < max(PASS_WINDOW_M, abs(r.speed - playerSpeedMps) * PASS_LEAD_S + 3.0)
                for (o in sorted) {
                    if (o === r || o.oncoming) continue
                    val d = gapTo(o.distM, r.distM)
                    if (d > -1.5 && d < max(PASS_WINDOW_M, (r.speed - o.speed) * PASS_LEAD_S + 3.0) && r.speed > o.speed + 0.1) {
                        val ol = targets[o.id] ?: playerLateralM
                        val left = min(lat, ol - PASS_SEPARATION_M)
                        val right = max(lat, ol + PASS_SEPARATION_M)
                        // Neben dem Nutzer nie die Seite wechseln (sonst quer durch seine Spur).
                        val onRight = r.lateral > playerLateralM
                        when {
                            !nearPlayer -> lat = left
                            !onRight && abs(left - playerLateralM) >= PASS_SEPARATION_M * 0.8 && left >= -edge -> lat = left
                            onRight && right <= edge -> lat = right
                            // Kein Platz neben Nutzer und Vordermann: dahinter bleiben (Tempo des Vordermanns)
                            else -> r.speed = min(r.speed, o.speed)
                        }
                    }
                }
            }
            lat = lat.coerceIn(-edge, edge)
            targets[r.id] = lat
            r.lateral += (lat - r.lateral) * (1 - exp(-step / LATERAL_TAU_S))
        }

        return riders.map { Shown(it.id, wrap(it.distM), it.lateral, it.oncoming, it.speed, it.jersey, it.crank, it.wheel) }
    }

    private fun spawn(playerDistM: Double, playerSpeedMps: Double, initial: Boolean) {
        val oncoming = rnd.nextDouble() < ONCOMING_SHARE
        val power = 150.0 + rnd.nextDouble() * 110.0
        val g0 = gradePctAt(wrap(playerDistM))
        val v = steadySpeed(power, if (oncoming) -g0 else g0)
        val d = when {
            initial -> playerDistM + 40 + rnd.nextDouble() * (AHEAD_M - 80)
            oncoming -> playerDistM + AHEAD_M
            v > max(playerSpeedMps, 1.0) -> playerDistM - BEHIND_M
            else -> playerDistM + AHEAD_M - 10
        }
        if (!loop && (d < 0 || d > lengthM)) return
        val lat = if (oncoming) -laneOffsetM(widthAt(wrap(d))) else laneOffsetM(widthAt(wrap(d)))
        riders += Rider(
            id = nextId++,
            distM = d,
            oncoming = oncoming,
            powerW = power,
            cadenceRpm = 78.0 + rnd.nextDouble() * 18.0,
            jersey = rnd.nextInt(JERSEYS),
            speed = v,
            lateral = lat,
            crank = rnd.nextDouble(),
        )
    }

    private fun gradeFor(r: Rider, d: Double): Double {
        val g = gradePctAt(wrap(d))
        return if (r.oncoming) -g else g
    }

    private fun wrap(d: Double): Double = if (loop && lengthM > 0) ((d % lengthM) + lengthM) % lengthM else d

    /** Abstand von [b] nach [a] entlang der Strecke (auf Runden der kuerzere Weg). */
    private fun gapTo(a: Double, b: Double): Double {
        var g = a - b
        if (loop && lengthM > 0) {
            g = ((g % lengthM) + lengthM) % lengthM
            if (g > lengthM / 2) g -= lengthM
        }
        return g
    }

    companion object {
        const val MAX_RIDERS = 6
        const val JERSEYS = 6
        const val AHEAD_M = 340.0
        const val BEHIND_M = 150.0
        const val PASS_WINDOW_M = 8.0
        const val PASS_LEAD_S = 2.5
        const val PASS_SEPARATION_M = 0.95
        const val EDGE_MARGIN_M = 0.3
        const val SPEED_TAU_S = 3.0
        const val LATERAL_TAU_S = 0.8
        const val ONCOMING_SHARE = 0.35
        const val RESPAWN_MIN_S = 6.0
        const val RESPAWN_MAX_S = 30.0
        const val MAX_DOWNHILL_MPS = 15.0
        const val WHEEL_CIRCUMFERENCE_M = 2.2

        private const val MASS_KG = 80.0
        private const val CRR = 0.005
        private const val CDA = 0.32
        private const val RHO = 1.2
        private const val G = 9.81

        /** Gleichgewichtstempo (m/s) bei Leistung [powerW] und Steigung [gradePct] (Bisektion). */
        fun steadySpeed(powerW: Double, gradePct: Double): Double {
            val a = atan(gradePct / 100.0)
            val resist = MASS_KG * G * (CRR * cos(a) + sin(a))
            fun power(v: Double) = (resist + 0.5 * RHO * CDA * v * v) * v
            var lo = 0.3
            var hi = 30.0
            if (power(hi) < powerW) return MAX_DOWNHILL_MPS
            repeat(40) {
                val mid = (lo + hi) / 2
                if (power(mid) < powerW) lo = mid else hi = mid
            }
            return min((lo + hi) / 2, MAX_DOWNHILL_MPS).coerceAtLeast(1.2)
        }
    }
}
