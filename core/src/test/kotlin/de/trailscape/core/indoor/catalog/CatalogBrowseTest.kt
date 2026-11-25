package de.trailscape.core.indoor.catalog

import de.trailscape.core.indoor.SurfaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Klassen, Fahrzeitschaetzung, Filter, Sortierung und Bestzeit der Streckenkarten. */
class CatalogBrowseTest {

    private val rider = CatalogRider(250.0, 75.0, 10.0, 0.38, 0.008, 0.97, CatalogTimeBasis.PROFILE)

    private fun entry(id: String, name: String, lengthM: Double, ascentM: Double = 100.0) = CatalogEntry(
        id = id, version = 1, worldId = "w-$id",
        name = CatalogText(name, name), region = CatalogText("", ""), country = "DE",
        kind = CatalogRideKind.ROAD, lengthM = lengthM, ascentM = ascentM,
        surfaces = mapOf(SurfaceKind.ASPHALT to 1.0), description = CatalogText("", ""),
        packageFile = "$id.zip", packageBytes = 1, sha256 = null, aerial = false,
    )

    private fun facts(
        name: String = "A",
        lengthM: Double = 10_000.0,
        ascentM: Double = 100.0,
        surfaces: Map<SurfaceKind, Double> = mapOf(SurfaceKind.ASPHALT to 1.0),
        loaded: Boolean = false,
        ridden: Boolean = false,
        t: Int? = 1000,
    ) = CatalogFacts(name, lengthM, ascentM, surfaces, loaded, ridden, t)

    @Test
    fun laengenklassenAnDenGrenzen() {
        assertEquals(CatalogLengthClass.SHORT, CatalogLengthClass.of(14_999.0))
        assertEquals(CatalogLengthClass.MEDIUM, CatalogLengthClass.of(15_000.0))
        assertEquals(CatalogLengthClass.MEDIUM, CatalogLengthClass.of(29_999.0))
        assertEquals(CatalogLengthClass.LONG, CatalogLengthClass.of(30_000.0))
    }

    @Test
    fun anstiegsklassenNachHoehenmeternJeKm() {
        // Enztal: 95 Hm auf 24 km = 4 m/km
        assertEquals(CatalogClimbClass.FLAT, CatalogClimbClass.of(24_000.0, 95.0))
        // Marbach: 390 Hm auf 24,1 km = 16 m/km
        assertEquals(CatalogClimbClass.ROLLING, CatalogClimbClass.of(24_100.0, 390.0))
        // Alpe d'Huez: 83 m/km
        assertEquals(CatalogClimbClass.HILLY, CatalogClimbClass.of(15_100.0, 1253.0))
        assertEquals(CatalogClimbClass.FLAT, CatalogClimbClass.of(0.0, 50.0))
    }

    @Test
    fun belaggruppenOhneUnbekannt() {
        assertEquals(CatalogSurfaceGroup.ASPHALT, CatalogSurfaceGroup.of(SurfaceKind.CONCRETE))
        assertEquals(CatalogSurfaceGroup.COBBLES, CatalogSurfaceGroup.of(SurfaceKind.COBBLESTONE))
        assertEquals(CatalogSurfaceGroup.GRAVEL, CatalogSurfaceGroup.of(SurfaceKind.COMPACTED))
        assertNull(CatalogSurfaceGroup.of(SurfaceKind.UNKNOWN))
    }

    @Test
    fun steigungAusDemProfil() {
        // 1 km flach, 1 km mit 10 %, 1 km flach; Schritt 100 m
        val ele = ArrayList<Double>()
        var h = 100.0
        repeat(10) { ele += h }
        repeat(10) { h += 10.0; ele += h }
        repeat(11) { ele += h }
        val p = CatalogProfile(100.0, ele)
        val (avg, max) = catalogGradeStats(p, p.lengthM, 100.0)
        assertEquals(10.0, avg, 0.01)
        assertEquals(10.0, max!!, 0.01)
    }

    @Test
    fun steigungOhneProfilNurMittel() {
        val (avg, max) = catalogGradeStats(null, 10_000.0, 500.0)
        assertEquals(5.0, avg, 1e-9)
        assertNull(max)
    }

    @Test
    fun fahrzeitFlachPlausibel() {
        // 20 km flach bei 188 W (75 % von 250), Asphalt: rund 30 km/h -> 40 min
        val s = estimateCatalogRideSeconds(20_000.0, 0.0, null, mapOf(SurfaceKind.ASPHALT to 1.0), rider)!!
        assertTrue(s in 2000..2900, "flach: $s s")
    }

    @Test
    fun fahrzeitBergBrauchtLaenger() {
        val flat = estimateCatalogRideSeconds(15_000.0, 0.0, null, mapOf(SurfaceKind.ASPHALT to 1.0), rider)!!
        val climb = estimateCatalogRideSeconds(15_000.0, 1250.0, null, mapOf(SurfaceKind.ASPHALT to 1.0), rider)!!
        assertTrue(climb > flat * 2, "Alpe-d'Huez-artig $climb s gegen flach $flat s")
        // Alpe d'Huez mit 188 W und 85 kg: etwa 1 h 15 bis 1 h 25 min
        assertTrue(climb in 3800..6000, "$climb")
    }

    @Test
    fun fahrzeitMitProfilBergabSchneller() {
        val up = ArrayList<Double>().apply { repeat(61) { add(100.0 + it * 5.0) } }
        val down = up.reversed()
        val s = mapOf(SurfaceKind.ASPHALT to 1.0)
        val tUp = estimateCatalogRideSeconds(6000.0, 300.0, CatalogProfile(100.0, up), s, rider)!!
        val tDown = estimateCatalogRideSeconds(6000.0, 0.0, CatalogProfile(100.0, down), s, rider)!!
        assertTrue(tDown < tUp / 2)
    }

    @Test
    fun fahrzeitSchotterLangsamerAlsAsphalt() {
        val a = estimateCatalogRideSeconds(20_000.0, 0.0, null, mapOf(SurfaceKind.ASPHALT to 1.0), rider)!!
        val g = estimateCatalogRideSeconds(20_000.0, 0.0, null, mapOf(SurfaceKind.GRAVEL to 1.0), rider)!!
        assertTrue(g > a)
    }

    @Test
    fun fahrzeitMehrLeistungKuerzer() {
        val s = mapOf(SurfaceKind.ASPHALT to 1.0)
        val low = estimateCatalogRideSeconds(15_000.0, 600.0, null, s, rider.copy(ftpW = 180.0))!!
        val high = estimateCatalogRideSeconds(15_000.0, 600.0, null, s, rider.copy(ftpW = 300.0))!!
        assertTrue(high < low)
    }

    @Test
    fun fahrzeitOhneSinnlosenEingangKeineZahl() {
        val s = emptyMap<SurfaceKind, Double>()
        assertNull(estimateCatalogRideSeconds(0.0, 0.0, null, s, rider))
        assertNull(estimateCatalogRideSeconds(1000.0, 0.0, null, s, rider.copy(ftpW = 0.0)))
        assertNotNull(estimateCatalogRideSeconds(1000.0, 0.0, null, s, CatalogRider.NEUTRAL))
        assertEquals(CatalogTimeBasis.NEUTRAL, CatalogRider.NEUTRAL.basis)
    }

    @Test
    fun filterOderInnerhalbUndInnerhalbZwischenArten() {
        val short = facts(name = "k", lengthM = 10_000.0)
        val long = facts(name = "l", lengthM = 40_000.0)
        val mid = facts(name = "m", lengthM = 20_000.0)
        val f = CatalogFilter(lengths = setOf(CatalogLengthClass.SHORT, CatalogLengthClass.LONG))
        assertTrue(f.matches(short))
        assertTrue(f.matches(long))
        assertFalse(f.matches(mid))
        val g = f.copy(onlyLoaded = true)
        assertFalse(g.matches(short))
        assertTrue(g.matches(long.copy(loaded = true)))
        assertEquals(3, g.copy(climbs = setOf(CatalogClimbClass.FLAT)).activeCount)
    }

    @Test
    fun belagFilterBrauchtMindestanteil() {
        val mixed = facts(surfaces = mapOf(SurfaceKind.ASPHALT to 0.9, SurfaceKind.GRAVEL to 0.1))
        assertFalse(CatalogFilter(surfaces = setOf(CatalogSurfaceGroup.GRAVEL)).matches(mixed))
        assertTrue(CatalogFilter(surfaces = setOf(CatalogSurfaceGroup.ASPHALT)).matches(mixed))
        val mixed2 = facts(surfaces = mapOf(SurfaceKind.ASPHALT to 0.7, SurfaceKind.COBBLESTONE to 0.3))
        assertTrue(CatalogFilter(surfaces = setOf(CatalogSurfaceGroup.COBBLES)).matches(mixed2))
    }

    @Test
    fun nochNichtGefahren() {
        val f = CatalogFilter(onlyNotRidden = true)
        assertTrue(f.matches(facts(ridden = false)))
        assertFalse(f.matches(facts(ridden = true)))
    }

    @Test
    fun sortierungenUndLeereTreffer() {
        val items = listOf(
            facts(name = "b", lengthM = 20_000.0, ascentM = 50.0, t = 3000),
            facts(name = "A", lengthM = 30_000.0, ascentM = 500.0, t = null),
            facts(name = "c", lengthM = 10_000.0, ascentM = 300.0, t = 1000),
        )
        fun order(s: CatalogSort) = CatalogFilter(sort = s).apply(items) { it }.map { it.name }
        assertEquals(listOf("A", "b", "c"), order(CatalogSort.NAME))
        assertEquals(listOf("c", "b", "A"), order(CatalogSort.LENGTH))
        assertEquals(listOf("b", "c", "A"), order(CatalogSort.ASCENT))
        assertEquals(listOf("c", "b", "A"), order(CatalogSort.TIME))
        val none = CatalogFilter(lengths = setOf(CatalogLengthClass.MEDIUM), onlyLoaded = true).apply(items) { it }
        assertTrue(none.isEmpty())
        val cleared = CatalogFilter(onlyLoaded = true, sort = CatalogSort.TIME).cleared()
        assertFalse(cleared.isActive)
        assertEquals(CatalogSort.TIME, cleared.sort)
    }

    @Test
    fun bestzeitNurVolleFahrtenMitPassendemNamen() {
        val e = listOf(entry("a", "Sa Calobra", 10_200.0), entry("b", "Enztal", 24_000.0))
        val rides = listOf(
            CatalogRideRecord("Indoor · Sa Calobra", 10.2, 2400),
            CatalogRideRecord("Indoor · Sa Calobra", 10.1, 2300),
            CatalogRideRecord("Indoor · Sa Calobra", 3.0, 600), // abgebrochen
            CatalogRideRecord("Indoor · Sa Calobra (ab km 5,0)", 5.1, 900), // anderer Name
            CatalogRideRecord("Sa Calobra", 10.2, 100), // keine Indoor-Fahrt
            CatalogRideRecord("Indoor · Enztal", 24.0, null), // ohne Zeit
        )
        val best = catalogBestTimes(e, rides)
        assertEquals(mapOf("a" to 2300), best)
        assertEquals(setOf("a"), catalogRiddenIds(e, rides))
    }
}
