package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portierung der Gruppen `Physikmodell`, `Kalibrierung α (HF ↔ Physik)` und
 * `Fallback-Kaskade der Tourlast` aus `test/training_load_test.dart`.
 */
class PhysicsAndCascadeTest {
    private fun roundTrip(json: JsonObject): JsonObject =
        Json.parseToJsonElement(json.toString()).jsonObject

    // --- group('Physikmodell') ---

    @Test
    fun `Luftdichte faellt mit der Hoehe`() {
        assertEquals(1.225, airDensity(0.0), 1e-9)
        assertTrue(airDensity(1000.0) < 1.225)
        assertEquals(1.2105629259049062, airDensity(100.0), 1e-9)
    }

    @Test
    fun `Kraftbilanz an einer 5-Prozent-Steigung`() {
        // m = 87 kg, v = 4 m/s, tanθ = 0,05, h = 100 m
        val p = estimateSamplePowerW(
            speedMs = 4.0,
            accelMs2 = 0.0,
            gradeTan = 0.05,
            elevationM = 100.0,
            profile = refProfile,
        )
        assertEquals(218.98031953707047, p, 1e-6)
    }

    @Test
    fun `flache Fahrt nutzt nur Roll- und Luftwiderstand`() {
        val p = estimateSamplePowerW(
            speedMs = 8.0,
            accelMs2 = 0.0,
            gradeTan = 0.0,
            elevationM = 0.0,
            profile = refProfile,
        )
        assertEquals(179.1458012371134, p, 1e-6)
    }

    @Test
    fun `Beschleunigungsterm geht mit m mal dv durch dt ein`() {
        val p = estimateSamplePowerW(
            speedMs = 5.0,
            accelMs2 = 1.0,
            gradeTan = 0.0,
            elevationM = 0.0,
            profile = refProfile,
        )
        assertEquals(513.6297855670103, p, 1e-6)
    }

    @Test
    fun `Bergabfahrt liefert nie negative Leistung`() {
        val p = estimateSamplePowerW(
            speedMs = 10.0,
            accelMs2 = 0.0,
            gradeTan = -0.20,
            elevationM = 500.0,
            profile = refProfile,
        )
        assertEquals(0.0, p, 0.0)
    }

    @Test
    fun `Stillstand liefert 0 W`() {
        assertEquals(
            0.0,
            estimateSamplePowerW(
                speedMs = 0.0,
                accelMs2 = 0.0,
                gradeTan = 0.05,
                elevationM = 0.0,
                profile = refProfile,
            ),
            0.0,
        )
    }

    @Test
    fun `gleichmaessige Steigungsfahrt ergibt plausible Leistung und VI nahe 1`() {
        // 20 min, 4 m/s, 5 % - 1 Hz, damit Glaettung und Steigungsfenster greifen.
        val points = track(
            pointCount = 1201,
            speedMs = 4.0,
            stepS = 1,
            gradeTan = 0.05,
            startEle = 0.0,
        )
        val series = buildRideSeries(points, refProfile)
        assertTrue(series.hasElevation)
        // 1200 s × 4 m/s × 5 % = 240 m Anstieg (Hysterese 3 m).
        assertEquals(240.0, series.ascentM, 6.0)

        val physics = computePhysicsEstimate(series, refProfile)
        assertTrue(physics.available)
        assertEquals(1200.0, physics.movingTimeS, 1.0)
        // Referenzrechnung bei h = 120 m ≈ 219 W; Randeffekte der Glaettung
        // erlauben eine kleine Abweichung.
        assertEquals(219.0, physics.avgPowerW, 12.0)
        assertEquals(1.0, physics.variabilityIndex, 0.05)
        assertTrue(physics.eTss > 0)
        assertTrue(physics.kcal > 0)
        assertEquals(Confidence.MEDIUM, physics.confidence)
        assertTrue(physics.powerText.contains("±15–25 %"))
    }

    @Test
    fun `eTSS folgt Dauer mal IF hoch 2 mal 100`() {
        val points = track(
            pointCount = 1201,
            speedMs = 4.0,
            stepS = 1,
            gradeTan = 0.05,
            startEle = 0.0,
        )
        val physics = computePhysicsEstimate(buildRideSeries(points, refProfile), refProfile)
        val hours = physics.movingTimeS / 3600
        val expected = hours * physics.intensityFactor * physics.intensityFactor * 100
        assertEquals(expected, physics.eTss, 1e-6)
    }

    @Test
    fun `flache Fahrt hat weniger Last als dieselbe Zeit bergauf`() {
        val flat = computePhysicsEstimate(
            buildRideSeries(
                track(pointCount = 1201, speedMs = 4.0, stepS = 1, startEle = 0.0),
                refProfile,
            ),
            refProfile,
        )
        val climb = computePhysicsEstimate(
            buildRideSeries(
                track(
                    pointCount = 1201,
                    speedMs = 4.0,
                    stepS = 1,
                    gradeTan = 0.05,
                    startEle = 0.0,
                ),
                refProfile,
            ),
            refProfile,
        )
        assertTrue(flat.eTss < climb.eTss)
        assertTrue(flat.avgPowerW < climb.avgPowerW)
    }

    @Test
    fun `Steigung wird auf plus minus 25 Prozent geklemmt`() {
        val points = track(
            pointCount = 300,
            speedMs = 4.0,
            stepS = 1,
            gradeTan = 0.60,
            startEle = 0.0,
        )
        val series = buildRideSeries(points, refProfile)
        for (s in series.segments) {
            assertTrue(abs(s.gradeTan) <= 0.2500001)
        }
    }

    @Test
    fun `ohne Hoehenprofil ist das Physikmodell nicht berechenbar`() {
        val series = buildRideSeries(
            track(pointCount = 400, stepS = 1, withElevation = false),
            refProfile,
        )
        val physics = computePhysicsEstimate(series, refProfile)
        assertFalse(physics.available)
        assertTrue(physics.unavailableReason!!.contains("Höhenprofil"))
        assertEquals(0.0, physics.eTss, 0.0)
    }

    @Test
    fun `zu kurze Tour ist nicht berechenbar`() {
        val physics = computePhysicsEstimate(
            buildRideSeries(track(pointCount = 5, stepS = 1), refProfile),
            refProfile,
        )
        assertFalse(physics.available)
        assertNotNull(physics.unavailableReason)
    }

    @Test
    fun `leere Serie wirft nicht`() {
        val physics = computePhysicsEstimate(RideSeries.EMPTY, refProfile)
        assertFalse(physics.available)
        assertTrue(physics.series.isEmpty)
    }

    @Test
    fun `eFTP ist 0,95 mal bestes 20-min-Mittel, geklemmt`() {
        val power = buildPowerSeries(
            buildRideSeries(
                track(
                    pointCount = 1501,
                    speedMs = 4.0,
                    stepS = 1,
                    gradeTan = 0.05,
                    startEle = 0.0,
                ),
                refProfile,
            ),
            refProfile,
        )
        val best = bestRollingMeanPowerW(power)
        assertNotNull(best)
        assertEquals(0.95 * best, estimateEftpW(listOf(power), refProfile), 1e-6)
        // Ohne 20-min-Material bleibt der Profil-Default.
        assertEquals(180.0, estimateEftpW(emptyList(), refProfile), 1e-9)
        assertNull(bestRollingMeanPowerW(PowerSeries.EMPTY))
    }

    // --- group('Kalibrierung α (HF ↔ Physik)') ---

    @Test
    fun `zu wenige Paare ergeben alpha 1,0 mit niedriger Confidence`() {
        val c = computeLoadCalibration(
            listOf(
                LoadCalibrationSample(loadHr = 100.0, loadPhysics = 80.0),
                LoadCalibrationSample(loadHr = 100.0, loadPhysics = 80.0),
            ),
        )
        assertEquals(1.0, c.alpha, 0.0)
        assertEquals(Confidence.LOW, c.confidence)
        assertFalse(c.clamped)
    }

    @Test
    fun `Median der Verhaeltnisse`() {
        val c = computeLoadCalibration(
            listOf(
                LoadCalibrationSample(loadHr = 110.0, loadPhysics = 100.0), // 1,10
                LoadCalibrationSample(loadHr = 120.0, loadPhysics = 100.0), // 1,20
                LoadCalibrationSample(loadHr = 130.0, loadPhysics = 100.0), // 1,30
                LoadCalibrationSample(loadHr = 140.0, loadPhysics = 100.0), // 1,40
                LoadCalibrationSample(loadHr = 90.0, loadPhysics = 100.0), // 0,90
            ),
        )
        assertEquals(1.20, c.alpha, 1e-9)
        assertEquals(5, c.sampleCount)
        assertFalse(c.clamped)
    }

    @Test
    fun `alpha ausserhalb 0,4 bis 2,0 wird verworfen, meldet aber den Rohwert`() {
        val c = computeLoadCalibration(
            List(6) { LoadCalibrationSample(loadHr = 250.0, loadPhysics = 100.0) },
        )
        assertEquals(1.0, c.alpha, 0.0)
        assertTrue(c.clamped)
        assertFalse(c.usable)
        assertEquals(Confidence.LOW, c.confidence)
        // Der Rohwert bleibt sichtbar — frueher verschwand der Fall lautlos.
        assertEquals(2.5, c.rawAlpha!!, 1e-9)
        assertTrue(c.note!!.contains("2,50"))
    }

    @Test
    fun `das alte Fenster haette genau den Fall verworfen, der korrigiert werden muss`() {
        // Eine um 25 % zu tief geschaetzte FTP erzeugt Faktor 1,8 zu hohe
        // Physiklast, also α ≈ 0,56 — unter dem alten Minimum von 0,6.
        val c = computeLoadCalibration(
            List(8) { LoadCalibrationSample(loadHr = 56.0, loadPhysics = 100.0) },
        )
        assertFalse(c.clamped)
        assertTrue(c.usable)
        assertEquals(0.56, c.alpha, 1e-9)
        assertTrue(c.alpha > alphaMin)
    }

    @Test
    fun `alpha wird als FTP-Korrektur zurueckgespeist statt verworfen`() {
        val profile = TrainingProfile(ageYears = 40, weightKg = 78.0)
        // 2,4 W/kg × 78 kg = 187,2 W — die zu tiefe Ausgangsannahme.
        assertEquals(187.2, profile.eftpW, 1e-9)

        val calibration = computeLoadCalibration(
            List(8) { LoadCalibrationSample(loadHr = 56.0, loadPhysics = 100.0) },
        )
        val eftp = resolveEftp(profile, emptyList(), calibration)
        assertEquals(EftpSource.KALIBRIERT, eftp.source)
        // FTP_korrigiert = FTP / √α = 187,2 / √0,56 ≈ 250 W ≈ 3,2 W/kg.
        assertEquals(187.2 / kotlin.math.sqrt(0.56), eftp.watts, 1e-6)
        assertEquals(3.2, eftp.perKg(78.0), 0.05)
        assertEquals(0.56, eftp.alphaApplied!!, 1e-9)
        assertEquals(Confidence.MEDIUM, eftp.confidence)

        // Und die Korrektur ist ein Fixpunkt: Mit der neuen FTP faellt die
        // Physiklast um genau den Faktor α, α_neu ist also 1,0.
        val recomputed = computeLoadCalibration(
            List(8) { LoadCalibrationSample(loadHr = 56.0, loadPhysics = 100.0 * 0.56) },
        )
        assertEquals(1.0, recomputed.alpha, 1e-9)
    }

    @Test
    fun `eingetragene FTP wird nicht von alpha verbogen`() {
        val profile = TrainingProfile(ageYears = 40, weightKg = 78.0, eftpOverrideW = 260.0)
        val calibration = computeLoadCalibration(
            List(8) { LoadCalibrationSample(loadHr = 56.0, loadPhysics = 100.0) },
        )
        val eftp = resolveEftp(profile, emptyList(), calibration)
        assertEquals(EftpSource.EINGETRAGEN, eftp.source)
        assertEquals(260.0, eftp.watts, 0.0)
        assertNull(eftp.alphaApplied)
        assertEquals(Confidence.HIGH, eftp.confidence)
    }

    @Test
    fun `20-min-Mittel zaehlt nur als Untergrenze`() {
        // Gemuetliche Stunde: das beste 20-min-Mittel liegt weit unter der
        // Default-Annahme und darf sie deshalb nicht ersetzen.
        val easy = buildPowerSeries(
            buildRideSeries(track(pointCount = 3601, speedMs = 5.0, stepS = 1), refProfile),
            refProfile,
        )
        val profile = TrainingProfile(ageYears = 40, weightKg = 78.0)
        val eftp = resolveEftp(profile, listOf(easy))
        assertEquals(EftpSource.GESCHAETZT, eftp.source)
        assertEquals(profile.eftpW, eftp.watts, 1e-9)
        assertNotNull(eftp.bestTwentyMinW)
        assertTrue(eftp.bestTwentyMinW!! < profile.eftpW)
    }

    @Test
    fun `kcal rechnet Kilojoule in Kilokalorien um`() {
        // 200 W × 3600 s = 720 kJ mechanisch; / 0,24 / 4,184 ≈ 717 kcal.
        assertEquals(717.0, estimateKcal(avgPowerW = 200.0, movingTimeS = 3600.0), 1.0)
        // Der alte Fehler waere um Faktor 4,184 daneben gelegen.
        assertEquals(
            200.0 * 3600 / (1000 * 0.24) / 4.184,
            estimateKcal(avgPowerW = 200.0, movingTimeS = 3600.0),
            1e-9,
        )
        assertEquals(0.0, estimateKcal(avgPowerW = 0.0, movingTimeS = 3600.0), 0.0)
        assertEquals(0.0, estimateKcal(avgPowerW = 200.0, movingTimeS = 0.0), 0.0)
    }

    @Test
    fun `nur die letzten 20 Paare zaehlen`() {
        val samples = List(10) { LoadCalibrationSample(loadHr = 300.0, loadPhysics = 100.0) } +
            List(20) { LoadCalibrationSample(loadHr = 110.0, loadPhysics = 100.0) }
        val c = computeLoadCalibration(samples)
        assertEquals(1.1, c.alpha, 1e-9)
        assertEquals(20, c.sampleCount)
        assertEquals(Confidence.MEDIUM, c.confidence)
    }

    @Test
    fun `unbrauchbare Paare werden verworfen, leere Liste ist neutral`() {
        val c = computeLoadCalibration(
            listOf(
                LoadCalibrationSample(loadHr = 0.0, loadPhysics = 100.0),
                LoadCalibrationSample(loadHr = 100.0, loadPhysics = 0.0),
                LoadCalibrationSample(loadHr = Double.NaN, loadPhysics = 100.0),
            ),
        )
        assertEquals(1.0, c.alpha, 0.0)
        assertEquals(0, c.sampleCount)
        assertEquals(1.0, computeLoadCalibration(emptyList()).alpha, 0.0)
    }

    @Test
    fun `Kalibrierung JSON-Roundtrip`() {
        val c = computeLoadCalibration(
            List(6) { LoadCalibrationSample(loadHr = 110.0, loadPhysics = 100.0) },
        )
        val back = LoadCalibration.fromJson(roundTrip(c.toJson()))
        assertEquals(c.alpha, back.alpha, 1e-9)
        assertEquals(c.confidence, back.confidence)
        assertEquals(1.0, LoadCalibration.NEUTRAL.alpha, 0.0)
    }

    // --- group('Fallback-Kaskade der Tourlast') ---

    @Test
    fun `Stufe A - mit Herzfrequenz gewinnt der HF-Pfad`() {
        val result = computeRideLoad(
            points = track(pointCount = 361, hr = { 130 }),
            profile = refProfile,
        )
        assertEquals(LoadSource.HERZFREQUENZ, result.source)
        assertEquals(38.518307560003834, result.load, 1e-6)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.note.contains("Herzfrequenz"))
    }

    @Test
    fun `Stufe B - ohne HF greift das Physikmodell inklusive alpha`() {
        val points = track(
            pointCount = 1201,
            speedMs = 4.0,
            stepS = 1,
            gradeTan = 0.05,
            startEle = 0.0,
        )
        val plain = computeRideLoad(points = points, profile = refProfile)
        assertEquals(LoadSource.PHYSIK, plain.source)
        assertTrue(plain.load > 0)

        val scaled = computeRideLoad(
            points = points,
            profile = refProfile,
            calibration = LoadCalibration(
                alpha = 1.25,
                sampleCount = 12,
                clamped = false,
                confidence = Confidence.MEDIUM,
            ),
        )
        assertEquals(1.25 * plain.load, scaled.load, 1e-6)
    }

    @Test
    fun `Stufe C - ohne HF und ohne Hoehe rettet RPE die Tour`() {
        val points = track(pointCount = 400, stepS = 1, withElevation = false)
        val result = computeRideLoad(points = points, profile = refProfile, rpe = 6.0)
        assertEquals(LoadSource.RPE, result.source)
        // 399 s Bewegungszeit ≈ 6,65 min × 6 × 1/6
        assertEquals(399.0 / 60 * 6 / 6, result.load, 1e-6)
        assertEquals(Confidence.LOW, result.confidence)
    }

    @Test
    fun `Stufe D - reine Distanz- und Hoehen-Heuristik`() {
        val result = computeRideLoad(
            points = emptyList(),
            profile = refProfile,
            stats = RideStats(
                distanceKm = 44.0,
                durationS = 7200,
                movingTimeS = 7200,
                ascentM = 0.0,
                descentM = 0.0,
            ),
        )
        assertEquals(LoadSource.HEURISTIK, result.source)
        // 2 h × 55 × clamp(44/(2×22)) = 110 × 1,0
        assertEquals(110.0, result.load, 1e-9)
        assertEquals(Confidence.LOW, result.confidence)
        assertTrue(result.note.contains("Schätzung"))
    }

    @Test
    fun `Heuristik-Korrekturterm ist auf 0,7 bis 1,5 geklemmt`() {
        assertEquals(
            2 * 55 * 1.5,
            heuristicLoad(distanceKm = 200.0, durationH = 2.0, ascentM = 0.0),
            1e-9,
        )
        assertEquals(
            2 * 55 * 0.7,
            heuristicLoad(distanceKm = 5.0, durationH = 2.0, ascentM = 0.0),
            1e-9,
        )
        // Hoehenmeter zaehlen als 10 km Flachaequivalent je 100 hm.
        assertEquals(
            110.0,
            heuristicLoad(distanceKm = 34.0, durationH = 2.0, ascentM = 100.0),
            1e-9,
        )
        assertEquals(
            2 * 55 * 1.5,
            heuristicLoad(distanceKm = 34.0, durationH = 2.0, ascentM = 1000.0),
            1e-9,
        )
        assertEquals(0.0, heuristicLoad(distanceKm = 10.0, durationH = 0.0, ascentM = 0.0), 0.0)
    }

    @Test
    fun `ohne jede Datengrundlage ist die Quelle keine und die Last 0`() {
        val result = computeRideLoad(points = emptyList(), profile = refProfile)
        assertEquals(LoadSource.KEINE, result.source)
        assertFalse(result.available)
        assertEquals(0.0, result.load, 0.0)
        assertTrue(result.note.isNotEmpty())
    }

    @Test
    fun `computeRideLoadForRide nutzt Punkte und Stats des Rides`() {
        val points = track(pointCount = 361, hr = { 130 })
        val ride = Ride(
            id = "x",
            name = "Test",
            createdAt = T0,
            points = points,
            stats = RideStats(distanceKm = 18.0, ascentM = 0.0, descentM = 0.0),
        )
        val result = computeRideLoadForRide(ride, refProfile)
        assertEquals(LoadSource.HERZFREQUENZ, result.source)
        assertEquals(38.518307560003834, result.load, 1e-6)
    }

    @Test
    fun `Last ist auf 500 gedeckelt`() {
        assertEquals(maxLoad, normalizeTrimp(100000.0, refProfile), 0.0)
    }
}
