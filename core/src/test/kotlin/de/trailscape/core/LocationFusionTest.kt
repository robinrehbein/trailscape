package de.trailscape.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer den inkrementellen Kalman-Filter aus LocationFusion.kt.
 *
 * Die Testkoordinaten werden wie in NavigationTest ueber ein lokales
 * ENU-Meter-Raster um ([LAT0], [LON0]) aufgebaut (dieselbe aequirektangulaere
 * Naeherung, die auch [LocationFusion] intern verwendet), damit sich
 * Erwartungswerte in Metern statt in winzigen Gradbruchteilen formulieren
 * lassen.
 */
class LocationFusionTest {

    private companion object {
        const val LAT0 = 48.0
        const val LON0 = 11.0
        const val M_PER_DEG_LAT = 111320.0
        val M_PER_DEG_LON = M_PER_DEG_LAT * cos(LAT0 * Math.PI / 180)
        const val T0 = 1_700_000_000_000L
    }

    private data class Pos(val lat: Double, val lon: Double)

    /** Position [eastM] Meter oestlich, [northM] Meter noerdlich von ([LAT0], [LON0]). */
    private fun pos(eastM: Double, northM: Double): Pos =
        Pos(lat = LAT0 + northM / M_PER_DEG_LAT, lon = LON0 + eastM / M_PER_DEG_LON)

    private fun eastOf(lat: Double, lon: Double): Double = (lon - LON0) * M_PER_DEG_LON
    private fun northOf(lat: Double, lon: Double): Double = (lat - LAT0) * M_PER_DEG_LAT

    private fun variance(values: List<Double>): Double {
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    // Deterministisches "Rauschen" (kein Zufall, siehe Klassen-KDoc) fuer die
    // stillstehende Quelle: Amplitude von wenigen Metern, Mittelwert nahe 0.
    private val rauschOstM = listOf(
        3.0, -4.0, 2.5, -1.0, 4.5, -3.5, 1.0, -2.0, 3.0, -3.0,
        2.0, -1.5, 4.0, -4.5, 1.5, -2.5, 3.5, -1.0, 2.0, -3.0,
    )
    private val rauschNordM = listOf(
        -2.0, 3.0, -1.5, 2.5, -3.0, 1.0, -4.0, 3.5, -1.0, 2.0,
        -3.5, 4.0, -2.5, 1.5, -3.0, 2.0, -4.5, 3.0, -1.0, 2.5,
    )

    // --- Stillstehende Quelle: Varianzreduktion ---

    @Test
    fun `stillstehende Quelle wird ruhiger als die Rohmessungen`() {
        val fusion = LocationFusion()
        val fusedOst = mutableListOf<Double>()
        val fusedNord = mutableListOf<Double>()

        for (i in rauschOstM.indices) {
            val p = pos(rauschOstM[i], rauschNordM[i])
            val punkt = assertNotNull(
                fusion.fuege(Quelle.TELEFON, T0 + i * 1000L, p.lat, p.lon, genauigkeitM = 5.0),
            )
            fusedOst += eastOf(punkt.lat, punkt.lon)
            fusedNord += northOf(punkt.lat, punkt.lon)
        }

        val rohVarianz = variance(rauschOstM) + variance(rauschNordM)
        val fusionsVarianz = variance(fusedOst) + variance(fusedNord)

        assertTrue(
            fusionsVarianz < rohVarianz * 0.7,
            "Fusionierte Varianz ($fusionsVarianz) sollte deutlich unter der Rohvarianz ($rohVarianz) liegen",
        )
    }

    // --- Bewegte Spur: keine systematische Abweichung ---

    @Test
    fun `bewegte Spur folgt ohne systematischen Versatz`() {
        val fusion = LocationFusion()
        val geschwindigkeitMps = 5.0
        val anzahl = 30

        val residuenOst = mutableListOf<Double>()
        var letzterPunkt: FusedPoint? = null

        for (i in 0 until anzahl) {
            val wahrOstM = geschwindigkeitMps * i
            // Kleines, deterministisches Messrauschen um die wahre Position.
            val rauschen = rauschOstM[i % rauschOstM.size] * 0.4
            val p = pos(wahrOstM + rauschen, 0.0)
            val punkt = assertNotNull(
                fusion.fuege(Quelle.TELEFON, T0 + i * 1000L, p.lat, p.lon, genauigkeitM = 5.0),
            )
            letzterPunkt = punkt
            // Einschwingphase der ersten Sekunden ausgeklammert: zu Beginn kennt
            // der Filter die Geschwindigkeit noch nicht und hinkt bewusst hinterher.
            if (i >= 8) {
                residuenOst += eastOf(punkt.lat, punkt.lon) - wahrOstM
            }
        }

        val mittleresResiduum = residuenOst.average()
        assertTrue(
            abs(mittleresResiduum) < 2.0,
            "Mittleres Residuum $mittleresResiduum deutet auf einen systematischen Versatz hin",
        )
        assertNotNull(letzterPunkt)
        assertEquals(geschwindigkeitMps, letzterPunkt.geschwindigkeitMps, 1.0)
    }

    // --- Uhr fuellt Telefon-Luecke ---

    @Test
    fun `Uhr-Proben waehrend einer Telefon-Luecke halten die Unsicherheit niedriger`() {
        val geschwindigkeitMps = 4.0

        fun wahrePosition(zeitMs: Long): Pos = pos(geschwindigkeitMps * (zeitMs - T0) / 1000.0, 0.0)

        // Szenario A: nur das Telefon meldet sich, mit einer 10-Sekunden-Luecke.
        val nurTelefon = LocationFusion()
        var unsicherheitOhneUhr = 0.0
        for (zeitMs in listOf(T0, T0 + 5_000L, T0 + 15_000L)) {
            val p = wahrePosition(zeitMs)
            val punkt = assertNotNull(nurTelefon.fuege(Quelle.TELEFON, zeitMs, p.lat, p.lon, genauigkeitM = 5.0))
            unsicherheitOhneUhr = punkt.unsicherheitM
        }

        // Szenario B: dieselben Telefon-Proben, aber die Uhr fuellt die Luecke
        // dazwischen im 1-Sekunden-Takt.
        val mitUhr = LocationFusion()
        mitUhr.fuege(Quelle.TELEFON, T0, wahrePosition(T0).lat, wahrePosition(T0).lon, genauigkeitM = 5.0)
        mitUhr.fuege(
            Quelle.TELEFON,
            T0 + 5_000L,
            wahrePosition(T0 + 5_000L).lat,
            wahrePosition(T0 + 5_000L).lon,
            genauigkeitM = 5.0,
        )
        for (deltaS in 6..14) {
            val zeitMs = T0 + deltaS * 1000L
            val p = wahrePosition(zeitMs)
            mitUhr.fuege(Quelle.UHR, zeitMs, p.lat, p.lon, genauigkeitM = 8.0)
        }
        val letzterMitUhr = assertNotNull(
            mitUhr.fuege(
                Quelle.TELEFON,
                T0 + 15_000L,
                wahrePosition(T0 + 15_000L).lat,
                wahrePosition(T0 + 15_000L).lon,
                genauigkeitM = 5.0,
            ),
        )

        assertTrue(
            letzterMitUhr.unsicherheitM < unsicherheitOhneUhr,
            "Mit Uhr-Proben in der Luecke (${letzterMitUhr.unsicherheitM}) sollte die " +
                "Unsicherheit kleiner sein als ohne (${unsicherheitOhneUhr})",
        )
        // Der letzte verarbeitete Aufruf kam vom Telefon (Abschluss der Luecke).
        assertEquals(Quelle.TELEFON, letzterMitUhr.zuletzt)
    }

    // --- Schlechte Genauigkeit wird schwach gewichtet ---

    @Test
    fun `Probe mit schlechter Genauigkeit zieht den Zustand nur schwach`() {
        fun aufgewaermt(): LocationFusion {
            val fusion = LocationFusion()
            for (i in 0..5) {
                val p = pos(0.0, 0.0)
                fusion.fuege(Quelle.TELEFON, T0 + i * 1000L, p.lat, p.lon, genauigkeitM = 3.0)
            }
            return fusion
        }

        val versatzOstM = 80.0
        val abweicherPos = pos(versatzOstM, 0.0)

        val schlecht = aufgewaermt()
        val punktSchlecht = assertNotNull(
            schlecht.fuege(Quelle.TELEFON, T0 + 6000L, abweicherPos.lat, abweicherPos.lon, genauigkeitM = 90.0),
        )
        val bewegungSchlecht = abs(eastOf(punktSchlecht.lat, punktSchlecht.lon))

        val gut = aufgewaermt()
        val punktGut = assertNotNull(
            gut.fuege(Quelle.TELEFON, T0 + 6000L, abweicherPos.lat, abweicherPos.lon, genauigkeitM = 3.0),
        )
        val bewegungGut = abs(eastOf(punktGut.lat, punktGut.lon))

        assertTrue(
            bewegungSchlecht < 15.0,
            "Schlecht genaue Probe hat den Zustand um $bewegungSchlecht m verschoben, erwartet < 15 m",
        )
        assertTrue(
            bewegungSchlecht < bewegungGut * 0.5,
            "Schlecht genaue Probe ($bewegungSchlecht m) sollte deutlich schwaecher ziehen als eine " +
                "genaue ($bewegungGut m)",
        )
    }

    // --- Out-of-order / Ausreisser werden verworfen ---

    @Test
    fun `zu alte Probe jenseits der Toleranz wird verworfen`() {
        val fusion = LocationFusion()
        fusion.fuege(Quelle.TELEFON, T0, pos(0.0, 0.0).lat, pos(0.0, 0.0).lon, genauigkeitM = 5.0)
        val zweite = assertNotNull(
            fusion.fuege(Quelle.TELEFON, T0 + 2000L, pos(2.0, 0.0).lat, pos(2.0, 0.0).lon, genauigkeitM = 5.0),
        )
        assertNotNull(zweite)

        val zuAlt = T0 + 2000L - LocationFusion.TOLERANZ_MS - 1
        val ergebnis = fusion.fuege(Quelle.UHR, zuAlt, pos(0.0, 0.0).lat, pos(0.0, 0.0).lon, genauigkeitM = 5.0)

        assertNull(ergebnis)
    }

    @Test
    fun `Probe genau an der Toleranzgrenze wird noch angenommen`() {
        val fusion = LocationFusion()
        fusion.fuege(Quelle.TELEFON, T0, pos(0.0, 0.0).lat, pos(0.0, 0.0).lon, genauigkeitM = 5.0)

        val anGrenze = T0 - LocationFusion.TOLERANZ_MS
        val ergebnis = fusion.fuege(Quelle.UHR, anGrenze, pos(0.0, 0.0).lat, pos(0.0, 0.0).lon, genauigkeitM = 5.0)

        assertNotNull(ergebnis)
    }

    @Test
    fun `Probe mit Genauigkeit ueber 100 m wird als Ausreisser verworfen`() {
        val fusion = LocationFusion()
        fusion.fuege(Quelle.TELEFON, T0, pos(0.0, 0.0).lat, pos(0.0, 0.0).lon, genauigkeitM = 5.0)

        val ergebnis = fusion.fuege(
            Quelle.UHR,
            T0 + 1000L,
            pos(500.0, 0.0).lat,
            pos(500.0, 0.0).lon,
            genauigkeitM = 100.1,
        )

        assertNull(ergebnis)
    }

    @Test
    fun `Probe mit genau 100 m Genauigkeit wird noch angenommen`() {
        val fusion = LocationFusion()
        fusion.fuege(Quelle.TELEFON, T0, pos(0.0, 0.0).lat, pos(0.0, 0.0).lon, genauigkeitM = 5.0)

        val ergebnis = fusion.fuege(
            Quelle.UHR,
            T0 + 1000L,
            pos(1.0, 0.0).lat,
            pos(1.0, 0.0).lon,
            genauigkeitM = 100.0,
        )

        assertNotNull(ergebnis)
    }

    @Test
    fun `Verwerfen einer Probe veraendert den Zustand nicht - Filter arbeitet danach normal weiter`() {
        val fusion = LocationFusion()
        fusion.fuege(Quelle.TELEFON, T0, pos(0.0, 0.0).lat, pos(0.0, 0.0).lon, genauigkeitM = 5.0)
        fusion.fuege(Quelle.TELEFON, T0 + 1000L, pos(1.0, 0.0).lat, pos(1.0, 0.0).lon, genauigkeitM = 5.0)

        // Ausreisser verwerfen.
        assertNull(
            fusion.fuege(Quelle.UHR, T0 + 1500L, pos(50.0, 0.0).lat, pos(50.0, 0.0).lon, genauigkeitM = 500.0),
        )

        // Der Filter nimmt danach ganz normal weitere Proben an.
        val danach = fusion.fuege(
            Quelle.TELEFON,
            T0 + 2000L,
            pos(2.0, 0.0).lat,
            pos(2.0, 0.0).lon,
            genauigkeitM = 5.0,
        )
        assertNotNull(danach)
    }
}
