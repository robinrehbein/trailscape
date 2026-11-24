package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kreuzungen und Ortsdurchfahrten: Zuschnitt der Raender im Grundriss, gezeichnete Belagsabschnitte. */
class JunctionsTest {
    private fun area(m: MeshBuilder): Double {
        var s = 0.0
        for (t in 0 until m.triangleCount) {
            val a = m.indices[3 * t]; val b = m.indices[3 * t + 1]; val c = m.indices[3 * t + 2]
            val ux = (m.x(b) - m.x(a)).toDouble(); val uz = (m.z(b) - m.z(a)).toDouble()
            val vx = (m.x(c) - m.x(a)).toDouble(); val vz = (m.z(c) - m.z(a)).toDouble()
            s += abs(ux * vz - uz * vx) / 2
        }
        return s
    }

    @Test
    fun `Rand wird genau an der Sperrflaeche geschnitten und Attribute laufen linear weiter`() {
        // 10 × 10 m Flaeche (x = e, z = −n), Farbe/UV laufen in x von 0 nach 1.
        val src = MeshBuilder()
        val a = src.vertex(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, Argb.of(255, 0, 0, 0))
        val b = src.vertex(10.0, 1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, Argb.of(255, 255, 255, 255))
        val c = src.vertex(10.0, 1.0, -10.0, 0.0, 1.0, 0.0, 1.0, 1.0, Argb.of(255, 255, 255, 255))
        val d = src.vertex(0.0, 0.0, -10.0, 0.0, 1.0, 0.0, 0.0, 1.0, Argb.of(255, 0, 0, 0))
        src.quad(a, b, c, d)
        // Strasse quer durch: e in 4..6 m, n in −5..15 m.
        val mask = PlanMask()
        mask.add(doubleArrayOf(4.0, -5.0, 6.0, -5.0, 6.0, 15.0, 4.0, 15.0))
        val dst = MeshBuilder()
        clipInto(src, dst, mask, { it }, { -it })
        assertEquals(80.0, area(dst), 1e-6, "Flaeche ohne den 2-m-Streifen")
        for (i in 0 until dst.vertexCount) {
            val x = dst.x(i).toDouble()
            assertTrue(x <= 4.0 + 1e-6 || x >= 6.0 - 1e-6, "Eckpunkt in der Sperrflaeche: x = $x")
            // Hoehe, UV und Farbe linear in x — wie die Grafikkarte das ungeschnittene Dreieck gezeichnet haette.
            assertEquals(x / 10.0, dst.y(i).toDouble(), 1e-5)
            assertEquals(x / 10.0, dst.uvs[2 * i].toDouble(), 1e-5)
            assertEquals(x / 10.0 * 255, Argb.red(dst.colors[i]).toDouble(), 1.0)
        }
        // Umlaufsinn bleibt erhalten (von oben gegen den Uhrzeigersinn in x/−z).
        for (t in 0 until dst.triangleCount) {
            val p = dst.indices[3 * t]; val q = dst.indices[3 * t + 1]; val r = dst.indices[3 * t + 2]
            val cross = (dst.x(q) - dst.x(p)) * (dst.z(r) - dst.z(p)) - (dst.z(q) - dst.z(p)) * (dst.x(r) - dst.x(p))
            assertTrue(cross < 0f, "Dreieck $t verkehrt herum")
        }
    }

    @Test
    fun `Sperrflaechen der eigenen Strecke in der Naehe sperren nicht, Strassen immer`() {
        val mask = PlanMask()
        mask.add(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), fromM = 100.0, toM = 105.0)
        mask.add(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), outerOnly = true)
        val out = ArrayList<PlanMask.Poly>()
        mask.candidates(-1.0, -1.0, 2.0, 2.0, skipFrom = 90.0, skipTo = 110.0, out = out)
        assertEquals(1, out.size, "nur die Strasse")
        mask.candidates(-1.0, -1.0, 2.0, 2.0, skipFrom = 200.0, skipTo = 210.0, out = out)
        assertEquals(2, out.size, "fernes Streckenstueck sperrt")
        mask.candidates(-1.0, -1.0, 2.0, 2.0, skipFrom = 200.0, skipTo = 210.0, out = out, inner = true)
        assertEquals(1, out.size, "Stuecke nur fuer den aeusseren Rand zaehlen innen nicht")
    }

    @Test
    fun `kurze Belagsabschnitte gehen fuer die Zeichnung im laengeren Nachbarn auf`() {
        val s = listOf(
            SurfaceSection(0.0, 400.0, SurfaceKind.ASPHALT, "residential"),
            SurfaceSection(400.0, 412.0, SurfaceKind.COMPACTED, "track"),
            SurfaceSection(412.0, 900.0, SurfaceKind.ASPHALT, "residential"),
            SurfaceSection(900.0, 1000.0, SurfaceKind.GRAVEL, "track"),
        )
        val d = TrackLine.drawnSections(s)
        // Die 12 m Schotterweg gehen im Asphalt auf, beide Asphaltstuecke werden eins.
        assertEquals(2, d.size, "$d")
        assertEquals(SurfaceSection(0.0, 900.0, SurfaceKind.ASPHALT, "residential"), d[0])
        assertEquals(SurfaceKind.GRAVEL, d[1].kind)
        assertEquals(1000.0, d.last().toM)
        // Lange Abschnitte bleiben unberuehrt.
        val long = listOf(SurfaceSection(0.0, 30.0, SurfaceKind.ASPHALT), SurfaceSection(30.0, 60.0, SurfaceKind.GRAVEL))
        assertEquals(long, TrackLine.drawnSections(long))
    }
}
