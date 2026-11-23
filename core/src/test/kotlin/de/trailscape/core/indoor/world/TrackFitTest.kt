package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.sampleAt
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackFitTest {
    private val o = TerrainTestSupport.origin

    private fun maxGrade(t: WorldTrack): Double {
        var m = 0.0
        for (i in 0 until t.sampleCount - 1) {
            val span = t.distAt(i + 1) - t.distAt(i)
            if (span > 1e-6) m = max(m, abs((t.u[i + 1] - t.u[i]) / span))
        }
        return m
    }

    @Test
    fun `Laufmeter bleiben unveraendert, letzte Probe genau am Ende`() {
        val route = TerrainTestSupport.routeFromEnu(doubleArrayOf(0.0, 0.0, 400.0, 30.0, 700.0, 300.0, 1203.0, 310.0))
        val t = fitTrack(route, o, RawHeights { e, n -> 0.03 * e + 4 * sin(n / 50) })
        assertEquals(route.lengthM, t.lengthM)
        assertEquals(WorldTrack.DEFAULT_STEP_M, t.stepM)
        assertEquals(route.lengthM, t.distAt(t.sampleCount - 1))
        assertTrue(t.distAt(t.sampleCount - 2) < route.lengthM)
        // Jede Probe liegt hoechstens 1,2 m neben der Eingabe an derselben Stelle.
        for (i in 0 until t.sampleCount) {
            val s = route.sampleAt(t.distAt(i))
            val d = hypot(o.toEast(s.lon) - t.e[i], o.toNorth(s.lat) - t.n[i])
            assertTrue(d <= TRACK_MAX_SHIFT_M + 0.01, "Probe $i weicht $d m ab")
        }
        val back = t.toIndoorRoute(o, "x", "y")
        assertEquals(route.lengthM, back.lengthM, 1e-9)
        assertEquals(route.surfaces, back.surfaces)
    }

    @Test
    fun `Steigung bleibt in den Grenzen, auch an einer Wand`() {
        val route = TerrainTestSupport.routeFromEnu(doubleArrayOf(0.0, 0.0, 1500.0, 0.0))
        // 40 % Rampe zwischen 500 und 700 m, sonst flach.
        val t = fitTrack(route, o, RawHeights { e, _ -> (e.coerceIn(500.0, 700.0) - 500.0) * 0.4 })
        assertTrue(maxGrade(t) <= IndoorRoute.MAX_GRADE_PCT / 100 + 1e-6, "Steigung ${maxGrade(t)}")
        val r = t.toIndoorRoute(o, "x", "y")
        for (i in 0 until r.points.size - 1) {
            val a = r.points[i]
            val b = r.points[i + 1]
            val g = (b.eleM - a.eleM) / (b.distM - a.distM) * 100
            // Float-Speicherung im Band: Zentimeter auf 5 m.
            assertTrue(g in -25.3..25.3, "Stueck $i: $g %")
        }
    }

    @Test
    fun `Runde schliesst sich in Lage und Hoehe`() {
        // Kreis, dessen Ende 30 m neben dem Start liegt.
        val c = TerrainTestSupport.circle(0.0, 0.0, 400.0)
        val open = c.copyOf(c.size - 2 * 3)
        val route = TerrainTestSupport.routeFromEnu(open, loop = true)
        val t = fitTrack(route, o, RawHeights { e, n -> 0.02 * e + 0.01 * n })
        val last = t.sampleCount - 1
        assertEquals(t.e[0], t.e[last])
        assertEquals(t.n[0], t.n[last])
        assertEquals(t.u[0], t.u[last])
        assertTrue(t.loop)
        // Keine Spruenge am Schluss: Nachbarproben hoechstens ~5 m auseinander.
        for (i in 0 until last) {
            val d = hypot((t.e[i + 1] - t.e[i]).toDouble(), (t.n[i + 1] - t.n[i]).toDouble())
            assertTrue(d < 6.5, "Sprung $d m bei Probe $i")
        }
        assertTrue(maxGrade(t) <= 0.25)
    }

    @Test
    fun `Glaettung entfernt die Stufen eines quantisierten Hoehenmodells`() {
        // Start um 2,5 m versetzt, damit keine Probe genau auf einer Stufenkante liegt.
        val route = TerrainTestSupport.routeFromEnu(doubleArrayOf(2.5, 0.0, 2002.5, 0.0))
        // 5 % Gefaelle, auf ganze Meter gerundet: alle 20 m eine 1-m-Stufe.
        val t = fitTrack(route, o, RawHeights { e, _ -> floor(0.05 * e) })
        // Mittlerer Versatz der Rundung (je nach Lage der Proben zu den Stufen).
        val mid = 20 until t.sampleCount - 21
        val offset = mid.map { 0.05 * t.e[it] - t.u[it] }.average()
        for (i in mid) {
            val du = t.u[i + 1] - t.u[i]
            assertTrue(abs(du - 0.25) < 0.03, "Stufe bei Probe $i: Δu = $du")
            val ideal = 0.05 * t.e[i] - offset
            assertTrue(abs(t.u[i] - ideal) < 0.05, "Probe $i: ${t.u[i]} statt $ideal")
        }
    }

    @Test
    fun `quer zum Hang liegt die Fahrbahn auf dem Gelaende`() {
        val route = TerrainTestSupport.routeFromEnu(doubleArrayOf(0.0, 0.0, 800.0, 0.0, 1200.0, 300.0))
        val raw = RawHeights { e, n -> 0.18 * n + 0.04 * e + 3 * sin(e / 120) }
        val t = fitTrack(route, o, raw)
        for (i in 10 until t.sampleCount - 10) {
            // Am Knick bei 800 m rundet die Fahrbahn den Steigungswechsel bewusst aus.
            if (abs(t.distAt(i) - 800.0) < 60.0) continue
            val h = raw.heightAt(t.e[i].toDouble(), t.n[i].toDouble())
            assertTrue(abs(t.u[i] - h) < 0.15, "Probe $i: Fahrbahn ${t.u[i]}, Gelaende $h")
        }
    }

    @Test
    fun `Zacken der Eingabe werden geglaettet, Kurven bleiben`() {
        // Gerade nach Osten mit ±1 m Zickzack alle 10 m, dann eine 90°-Kurve mit 20 m Radius.
        val pts = ArrayList<Double>()
        for (i in 0..60) {
            pts.add(i * 10.0); pts.add(if (i % 2 == 0) 1.0 else -1.0)
        }
        for (k in 1..16) {
            val a = Math.PI / 2 * k / 16
            pts.add(600.0 + 20 * sin(a)); pts.add(20 - 20 * kotlin.math.cos(a))
        }
        pts.add(620.0); pts.add(300.0)
        val route = TerrainTestSupport.routeFromEnu(pts.toDoubleArray(), stepM = 5.0)
        val t = fitTrack(route, o, RawHeights { _, _ -> 0.0 })
        var maxN = 0.0
        for (i in 4 until t.sampleCount) if (t.distAt(i) in 30.0..560.0) maxN = max(maxN, abs(t.n[i].toDouble()))
        assertTrue(maxN < 0.5, "Zickzack bleibt mit $maxN m")
    }

    @Test
    fun `Bruecken verlaufen linear zwischen den Widerlagern`() {
        val route = TerrainTestSupport.routeFromEnu(doubleArrayOf(0.0, 0.0, 1000.0, 0.0))
        // Tal von 20 m Tiefe zwischen 400 und 600 m.
        val raw = RawHeights { e, _ -> if (e in 400.0..600.0) -20.0 * sin((e - 400) / 200 * Math.PI) else 0.0 }
        val t = fitTrack(route, o, raw) { it in 380.0..620.0 }
        for (i in 0 until t.sampleCount) {
            if (t.distAt(i) in 420.0..580.0) assertTrue(abs(t.u[i]) < 0.3, "Bruecke sackt bei ${t.distAt(i)} auf ${t.u[i]}")
        }
    }

    @Test
    fun `Ursprung ist die Mitte des Umgebungsrechtecks auf Starthoehe`() {
        val route = TerrainTestSupport.routeFromEnu(doubleArrayOf(-500.0, -200.0, 1500.0, 800.0))
        val org = worldOrigin(route) { _, _ -> 333.0 }
        val lat = (route.points.minOf { it.lat } + route.points.maxOf { it.lat }) / 2
        val lon = (route.points.minOf { it.lon } + route.points.maxOf { it.lon }) / 2
        assertEquals(lat, org.lat, 1e-12)
        assertEquals(lon, org.lon, 1e-12)
        assertEquals(333.0, org.eleM)
        assertEquals(route.points[0].eleM, worldOrigin(route) { _, _ -> Double.NaN }.eleM)
    }

    @Test
    fun `nicht endliche Hoehen werden ueberbrueckt`() {
        val route = TerrainTestSupport.routeFromEnu(doubleArrayOf(0.0, 0.0, 600.0, 0.0))
        val t = fitTrack(route, o, RawHeights { e, _ -> if (e in 200.0..260.0) Double.NaN else 0.01 * e })
        assertTrue(t.u.all { it.isFinite() })
    }
}
