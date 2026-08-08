package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Portierung der Gruppen `TrainingProfile` und `Zonenmodell` aus
 * `test/training_load_test.dart`. Alle Erwartungswerte unveraendert.
 */
class TrainingProfileTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun roundTrip(json: JsonObject): JsonObject =
        Json.parseToJsonElement(json.toString()).jsonObject

    // --- group('TrainingProfile') ---

    @Test
    fun `leitet HFmax nach Tanaka ab (208 minus 0,7 mal Alter)`() {
        val p = TrainingProfile(ageYears = 40)
        assertEquals(180.0, p.tanakaHrMax, 1e-9)
        assertEquals(180.0, p.hrMax, 1e-9)
        assertEquals(ValueSource.GESCHAETZT, p.hrMaxSource)
    }

    @Test
    fun `leitet LTHR als 0,89 mal HFmax ab`() {
        val p = TrainingProfile(ageYears = 40)
        assertEquals(0.89 * 180, p.lthr, 1e-9)
        assertEquals(ValueSource.GESCHAETZT, p.lthrSource)
    }

    @Test
    fun `nutzt Ruhepuls-Default 60 ohne Angabe`() {
        val p = TrainingProfile(ageYears = 40)
        assertEquals(60.0, p.restingHr, 0.0)
        assertEquals(120.0, p.hrReserve, 1e-9)
    }

    @Test
    fun `Overrides gewinnen und heben die Confidence an`() {
        assertEquals(190.0, refProfile.hrMax, 0.0)
        assertEquals(170.0, refProfile.lthr, 0.0)
        assertEquals(50.0, refProfile.restingHr, 0.0)
        assertEquals(ValueSource.TEST, refProfile.hrMaxSource)
        assertEquals(Confidence.HIGH, refProfile.anchorConfidence)
        assertEquals(Confidence.LOW, TrainingProfile(ageYears = 40).anchorConfidence)
    }

    @Test
    fun `klemmt eine unplausible LTHR ins Fenster 0,80 bis 0,95 mal HFmax`() {
        val low = TrainingProfile(ageYears = 40, hrMaxOverride = 190.0, lthrOverride = 100.0)
        val high = TrainingProfile(ageYears = 40, hrMaxOverride = 190.0, lthrOverride = 200.0)
        assertEquals(152.0, low.lthr, 1e-9)
        assertEquals(180.5, high.lthr, 1e-9)
    }

    @Test
    fun `Gravel-Physik-Defaults und Massen`() {
        val p = TrainingProfile(ageYears = 40, weightKg = 75.0)
        assertEquals(0.38, p.cda, 0.0)
        assertEquals(0.008, p.crr, 0.0)
        assertEquals(0.97, p.driveEfficiency, 0.0)
        assertEquals(12.0, p.setupMassKg, 0.0)
        assertEquals(87.0, p.totalMassKg, 1e-9)
    }

    @Test
    fun `eFTP-Default 2,4 W pro kg, geklemmt auf 100 bis 400 W`() {
        assertEquals(180.0, TrainingProfile(ageYears = 40, weightKg = 75.0).eftpW, 1e-9)
        assertEquals(100.0, TrainingProfile(ageYears = 40, weightKg = 30.0).eftpW, 0.0)
        assertEquals(400.0, TrainingProfile(ageYears = 40, weightKg = 200.0).eftpW, 0.0)
    }

    @Test
    fun `TRIMP-Koeffizienten je Geschlecht, unbekannt ergibt den maennlichen Satz`() {
        assertEquals(0.64, TrainingProfile(ageYears = 40, sex = Sex.MAENNLICH).trimpA, 0.0)
        assertEquals(1.92, TrainingProfile(ageYears = 40, sex = Sex.MAENNLICH).trimpB, 0.0)
        assertEquals(0.86, TrainingProfile(ageYears = 40, sex = Sex.WEIBLICH).trimpA, 0.0)
        assertEquals(1.67, TrainingProfile(ageYears = 40, sex = Sex.WEIBLICH).trimpB, 0.0)
        assertEquals(0.64, TrainingProfile(ageYears = 40).trimpA, 0.0)
    }

    @Test
    fun `JSON-Roundtrip erhaelt alle Felder`() {
        val json = roundTrip(refProfile.toJson())
        val back = TrainingProfile.fromJson(json)
        assertEquals(refProfile.ageYears, back.ageYears)
        assertEquals(Sex.MAENNLICH, back.sex)
        assertEquals(refProfile.hrMax, back.hrMax, 0.0)
        assertEquals(refProfile.lthr, back.lthr, 0.0)
        assertEquals(refProfile.restingHr, back.restingHr, 0.0)
        assertEquals(refProfile.cda, back.cda, 0.0)
        assertEquals(refProfile.crr, back.crr, 0.0)
        assertEquals(refProfile.setupMassKg, back.setupMassKg, 0.0)
    }

    @Test
    fun `fromJson verkraftet leeres oder kaputtes JSON`() {
        val p = TrainingProfile.fromJson(obj("{}"))
        assertEquals(40, p.ageYears)
        assertEquals(Sex.UNBEKANNT, p.sex)
        assertEquals(defaultCda, p.cda, 0.0)
        val q = TrainingProfile.fromJson(obj("""{"sex":"quatsch"}"""))
        assertEquals(Sex.UNBEKANNT, q.sex)
    }

    @Test
    fun `copyWith aendert nur die angegebenen Felder`() {
        val p = refProfile.copyWith(weightKg = 80.0)
        assertEquals(80.0, p.weightKg, 0.0)
        assertEquals(190.0, p.hrMax, 0.0)
    }

    @Test
    fun `Wochen-Zeitbudget ist optional und JSON-abwaertskompatibel`() {
        assertNull(refProfile.weeklyHours)
        // Altes Profil ohne das Feld bleibt gueltig.
        assertNull(TrainingProfile.fromJson(obj("""{"ageYears":35}""")).weeklyHours)
        assertFalse(refProfile.toJson().containsKey("weeklyHours"))

        val withBudget = refProfile.copyWith(weeklyHours = 6.5)
        val json = roundTrip(withBudget.toJson())
        assertEquals(6.5, TrainingProfile.fromJson(json).weeklyHours!!, 0.0)
    }

    // --- group('Zonenmodell') ---

    private val zones = refProfile.zones

    @Test
    fun `Friel-Grenzen bei LTHR 170`() {
        assertEquals(137.7, zones.frielBoundsBpm[0], 1e-9)
        assertEquals(153.0, zones.frielBoundsBpm[1], 1e-9)
        assertEquals(159.8, zones.frielBoundsBpm[2], 1e-9)
        assertEquals(170.0, zones.frielBoundsBpm[3], 1e-9)
    }

    @Test
    fun `Friel-Zuordnung inklusive Grenzfaelle`() {
        assertEquals(0, zones.frielZoneIndex(130.0))
        assertEquals(0, zones.frielZoneIndex(137.69))
        assertEquals(1, zones.frielZoneIndex(137.71))
        assertEquals(1, zones.frielZoneIndex(152.9))
        assertEquals(2, zones.frielZoneIndex(153.0))
        assertEquals(3, zones.frielZoneIndex(159.8))
        assertEquals(3, zones.frielZoneIndex(169.9))
        assertEquals(4, zones.frielZoneIndex(170.0))
        assertEquals(4, zones.frielZoneIndex(200.0))
    }

    @Test
    fun `Lucia LIT MIT HIT an den Schwellen`() {
        assertEquals(0, zones.luciaZoneIndex(144.4))
        assertEquals(1, zones.luciaZoneIndex(144.5))
        assertEquals(1, zones.luciaZoneIndex(170.0))
        assertEquals(2, zones.luciaZoneIndex(170.1))
    }

    @Test
    fun `Edwards-Zonen in Prozent HFmax, unter 50 Prozent kein Beitrag`() {
        assertNull(zones.edwardsZoneIndex(90.0))
        assertEquals(0, zones.edwardsZoneIndex(95.0))
        assertEquals(1, zones.edwardsZoneIndex(130.0))
        assertEquals(2, zones.edwardsZoneIndex(150.0))
        assertEquals(3, zones.edwardsZoneIndex(160.0))
        assertEquals(4, zones.edwardsZoneIndex(180.0))
    }

    @Test
    fun `Karvonen als Fallback-Anker`() {
        assertEquals(50 + 0.7 * 140, zones.karvonenHr(0.7), 1e-9)
    }

    @Test
    fun `Zonen-Snapshot serialisiert`() {
        val back = TrainingZones.fromJson(zones.toJson())
        assertEquals(190.0, back.hrMax, 0.0)
        assertEquals(170.0, back.lthr, 0.0)
    }
}
