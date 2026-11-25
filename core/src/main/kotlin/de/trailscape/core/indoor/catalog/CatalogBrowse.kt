package de.trailscape.core.indoor.catalog

import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.effectiveCrr
import de.trailscape.core.indoor.steadySpeedMps

/**
 * # Streckenkarten: Klassen, Fahrzeit, Filter, Sortierung, Bestzeit
 *
 * Reine Logik fuer die Liste „Bekannte Strecken" (docs/indoor/catalog.md,
 * Abschnitt „Streckenkarten"). Alles beruht auf den Feldern, die der Katalog
 * schon hat (Laenge, Hoehenmeter, Belaege, Art, Profil) — das Format bleibt 1.
 */

/** Laengenklasse einer Strecke. Grenzen: unter 15 km kurz, bis 30 km mittel, darueber lang. */
enum class CatalogLengthClass {
    SHORT,
    MEDIUM,
    LONG,
    ;

    companion object {
        const val SHORT_MAX_KM: Double = 15.0
        const val MEDIUM_MAX_KM: Double = 30.0

        fun of(lengthM: Double): CatalogLengthClass {
            val km = lengthM / 1000.0
            return when {
                km < SHORT_MAX_KM -> SHORT
                km < MEDIUM_MAX_KM -> MEDIUM
                else -> LONG
            }
        }
    }
}

/** Anstiegsklasse nach Hoehenmetern je km: unter 8 flach, bis 20 wellig, darueber bergig. */
enum class CatalogClimbClass {
    FLAT,
    ROLLING,
    HILLY,
    ;

    companion object {
        const val FLAT_MAX_M_PER_KM: Double = 8.0
        const val ROLLING_MAX_M_PER_KM: Double = 20.0

        fun of(lengthM: Double, ascentM: Double): CatalogClimbClass {
            if (lengthM <= 0.0) return FLAT
            val perKm = ascentM / (lengthM / 1000.0)
            return when {
                perKm < FLAT_MAX_M_PER_KM -> FLAT
                perKm < ROLLING_MAX_M_PER_KM -> ROLLING
                else -> HILLY
            }
        }
    }
}

/** Belag-Gruppe fuer den Filter (grob; die Leiste zeigt die feinen Klassen). */
enum class CatalogSurfaceGroup {
    ASPHALT,
    COBBLES,
    GRAVEL,
    ;

    companion object {
        /** Mindestanteil, ab dem ein Belag den Filter erfuellt. */
        const val MIN_SHARE: Double = 0.15

        fun of(kind: SurfaceKind): CatalogSurfaceGroup? = when (kind) {
            SurfaceKind.ASPHALT, SurfaceKind.CONCRETE, SurfaceKind.WOOD -> ASPHALT
            SurfaceKind.PAVING_STONES, SurfaceKind.COBBLESTONE -> COBBLES
            SurfaceKind.COMPACTED, SurfaceKind.GRAVEL, SurfaceKind.DIRT, SurfaceKind.GRASS, SurfaceKind.SAND -> GRAVEL
            SurfaceKind.UNKNOWN -> null
        }
    }
}

/** Mittlere und maximale Steigung aus dem Hoehenprofil, in Prozent (nur bergauf gemittelt). */
data class CatalogGradeStats(val avgClimbPct: Double, val maxPct: Double)

/**
 * Steigung der Strecke: Mittel = Hoehenmeter / Laenge **der Anstiegsabschnitte**
 * (so nennt es auch ein Radfahrer: „8 % im Schnitt am Berg"), Maximum = steilster
 * 200-m-Abschnitt (einzelne Messrauschen-Spitzen zaehlen so nicht). Ohne Profil
 * nur das Mittel ueber die ganze Strecke (Hoehenmeter / Laenge), Maximum `null`.
 */
fun catalogGradeStats(profile: CatalogProfile?, lengthM: Double, ascentM: Double): Pair<Double, Double?> {
    if (profile == null || profile.elevM.size < 3) {
        val avg = if (lengthM > 0) ascentM / lengthM * 100.0 else 0.0
        return avg to null
    }
    val e = profile.elevM
    val step = profile.stepM
    var climbM = 0.0
    var climbDist = 0.0
    for (i in 1 until e.size) {
        val dh = e[i] - e[i - 1]
        if (dh > 0) {
            climbM += dh
            climbDist += step
        }
    }
    val avg = if (climbDist > 0) climbM / climbDist * 100.0 else 0.0
    val win = maxOf(1, Math.round(200.0 / step).toInt()).coerceAtMost(e.size - 1)
    var max = 0.0
    for (i in win until e.size) {
        val g = (e[i] - e[i - win]) / (win * step) * 100.0
        if (g > max) max = g
    }
    return avg to max
}

/** Wie die Fahrzeit entstand — davon haengt der Hinweis in der Karte ab. */
enum class CatalogTimeBasis {
    /** FTP aus dem Profil (eingetragen oder aus der Trainingshistorie geschaetzt). */
    PROFILE,

    /** Kein Profilwert: neutrale Annahme (Richtwert). */
    NEUTRAL,
}

/** Fahrerangaben fuer die Schaetzung. */
data class CatalogRider(
    val ftpW: Double,
    val riderKg: Double,
    val bikeKg: Double,
    val cdaM2: Double,
    val profileCrr: Double,
    val efficiency: Double,
    val basis: CatalogTimeBasis,
) {
    companion object {
        /** Neutrale Annahme ohne Profil: 75 kg, 2,5 W/kg. */
        val NEUTRAL = CatalogRider(
            ftpW = 188.0,
            riderKg = 75.0,
            bikeKg = 10.0,
            cdaM2 = 0.38,
            profileCrr = SurfaceKind.REFERENCE_PROFILE_CRR,
            efficiency = 0.97,
            basis = CatalogTimeBasis.NEUTRAL,
        )
    }
}

/** Anteil der FTP, den man auf einer ganzen Strecke durchhaelt (bewusst vorsichtig). */
const val CATALOG_SUSTAINED_FTP_FRACTION: Double = 0.75

/** Hoechstes angenommenes Tempo bergab in m/s (rund 54 km/h): Man rollt, aber indoor wird gebremst. */
private const val MAX_DESCENT_MPS: Double = 15.0

/**
 * Geschaetzte Fahrzeit in Sekunden bei [CATALOG_SUSTAINED_FTP_FRACTION] der FTP
 * mit der Fahrphysik der Indoor-Fahrt ([steadySpeedMps]: Roll-, Luft- und
 * Hangabtriebswiderstand, Gleichgewichtstempo je Abschnitt). Je Profilschritt
 * mit dessen Steigung und dem mittleren Belag der Strecke; ohne Profil als
 * gleichmaessiger Anstieg mit der mittleren Steigung (Hoehenmeter / Laenge) —
 * das schaetzt wellige Strecken etwas zu langsam, weil Gefaelle fehlen.
 * Windstill, Luftdichte auf Meereshoehe. `null` bei Laenge 0 oder Leistung 0.
 */
fun estimateCatalogRideSeconds(
    lengthM: Double,
    ascentM: Double,
    profile: CatalogProfile?,
    surfaces: Map<SurfaceKind, Double>,
    rider: CatalogRider,
): Int? {
    if (lengthM <= 0.0 || rider.ftpW <= 0.0 || rider.riderKg <= 0.0) return null
    val setup = RiderSetup(
        riderMassKg = rider.riderKg,
        bikeMassKg = rider.bikeKg,
        cdaM2 = rider.cdaM2,
        profileCrr = rider.profileCrr,
        ftpW = rider.ftpW,
        driveEfficiency = rider.efficiency,
    )
    val power = rider.ftpW * CATALOG_SUSTAINED_FTP_FRACTION
    val share = surfaces.filterValues { it > 0 }
    val total = share.values.sum()
    val crr = if (total > 0) {
        share.entries.sumOf { (k, f) -> effectiveCrr(k, rider.profileCrr) * f } / total
    } else {
        effectiveCrr(SurfaceKind.UNKNOWN, rider.profileCrr)
    }
    fun speed(gradePct: Double, eleM: Double): Double =
        steadySpeedMps(power, gradePct, crr, eleM, 0.0, setup).coerceIn(MIN_SPEED_MPS, MAX_DESCENT_MPS)

    var seconds = 0.0
    if (profile != null && profile.elevM.size >= 2) {
        val e = profile.elevM
        val segM = profile.stepM
        // Das Profil kann die Strecke etwas kuerzer/laenger beschreiben; auf lengthM skalieren.
        val scale = if (profile.lengthM > 0) lengthM / profile.lengthM else 1.0
        for (i in 1 until e.size) {
            val grade = ((e[i] - e[i - 1]) / segM * 100.0).coerceIn(-25.0, 25.0)
            val mid = (e[i] + e[i - 1]) / 2
            seconds += segM * scale / speed(grade, mid)
        }
    } else {
        val grade = (ascentM / lengthM * 100.0).coerceIn(0.0, 25.0)
        seconds = lengthM / speed(grade, 0.0)
    }
    return seconds.toInt().coerceAtLeast(1)
}

private const val MIN_SPEED_MPS: Double = 0.8

// ---------------------------------------------------------------------------
// Filter und Sortierung
// ---------------------------------------------------------------------------

/** Sortierung der Liste; Reihenfolge der Auswahl in der Oberflaeche. */
enum class CatalogSort { NAME, LENGTH, ASCENT, TIME }

/**
 * Auswahl ueber der Liste. Leere Mengen = kein Filter dieser Art; innerhalb
 * einer Art gilt „oder", zwischen den Arten „und".
 */
data class CatalogFilter(
    val lengths: Set<CatalogLengthClass> = emptySet(),
    val climbs: Set<CatalogClimbClass> = emptySet(),
    val surfaces: Set<CatalogSurfaceGroup> = emptySet(),
    val onlyLoaded: Boolean = false,
    val onlyNotRidden: Boolean = false,
    val sort: CatalogSort = CatalogSort.NAME,
) {
    /** Anzahl gesetzter Filter (ohne Sortierung). */
    val activeCount: Int
        get() = lengths.size + climbs.size + surfaces.size + (if (onlyLoaded) 1 else 0) + (if (onlyNotRidden) 1 else 0)

    val isActive: Boolean get() = activeCount > 0

    /** Dieselbe Sortierung, alle Filter zurueckgesetzt („Filter zuruecksetzen"). */
    fun cleared(): CatalogFilter = CatalogFilter(sort = sort)
}

/** Die Merkmale einer Karte, die Filter und Sortierung brauchen. */
data class CatalogFacts(
    val name: String,
    val lengthM: Double,
    val ascentM: Double,
    val surfaces: Map<SurfaceKind, Double>,
    val loaded: Boolean,
    val ridden: Boolean,
    val estimatedSeconds: Int?,
)

fun CatalogFilter.matches(f: CatalogFacts): Boolean {
    if (lengths.isNotEmpty() && CatalogLengthClass.of(f.lengthM) !in lengths) return false
    if (climbs.isNotEmpty() && CatalogClimbClass.of(f.lengthM, f.ascentM) !in climbs) return false
    if (surfaces.isNotEmpty()) {
        val groups = f.surfaces.entries
            .filter { it.value >= CatalogSurfaceGroup.MIN_SHARE }
            .mapNotNull { CatalogSurfaceGroup.of(it.key) }
            .toSet()
        if (groups.none { it in surfaces }) return false
    }
    if (onlyLoaded && !f.loaded) return false
    if (onlyNotRidden && f.ridden) return false
    return true
}

/** Gefilterte und sortierte Liste. Gleiche Werte behalten die Namensreihenfolge. */
fun <T> CatalogFilter.apply(items: List<T>, facts: (T) -> CatalogFacts): List<T> {
    val withFacts = items.map { it to facts(it) }.filter { matches(it.second) }
    val byName = compareBy<Pair<T, CatalogFacts>, String>(String.CASE_INSENSITIVE_ORDER) { it.second.name }
    val order: Comparator<Pair<T, CatalogFacts>> = when (sort) {
        CatalogSort.NAME -> byName
        CatalogSort.LENGTH -> compareBy<Pair<T, CatalogFacts>> { it.second.lengthM }.then(byName)
        CatalogSort.ASCENT -> compareBy<Pair<T, CatalogFacts>> { it.second.ascentM }.then(byName)
        // Ohne Schaetzung ans Ende.
        CatalogSort.TIME -> compareBy<Pair<T, CatalogFacts>> { it.second.estimatedSeconds ?: Int.MAX_VALUE }.then(byName)
    }
    return withFacts.sortedWith(order).map { it.first }
}

// ---------------------------------------------------------------------------
// Gefahren / Bestzeit
// ---------------------------------------------------------------------------

/** Eine gespeicherte Indoor-Fahrt in der Form, die die Zuordnung braucht. */
data class CatalogRideRecord(val name: String, val distanceKm: Double, val seconds: Int?)

/** Das, was die Zuordnung einer Fahrt von einer Katalogstrecke braucht. */
data class CatalogRouteRef(val id: String, val names: Set<String>, val lengthM: Double)

fun CatalogEntry.routeRef(): CatalogRouteRef =
    CatalogRouteRef(id, setOf(name.de, name.en).filter { it.isNotBlank() }.toSet(), lengthM)

/** Name, den die App einer Indoor-Fahrt beim Speichern gibt: „Indoor · <Streckenname>". */
const val INDOOR_RIDE_NAME_PREFIX: String = "Indoor · "

/**
 * Bestzeit je Katalogstrecke aus den Indoor-Fahrten. Eine Fahrt zaehlt zu einer
 * Strecke, wenn ihr Name „Indoor · <Streckenname>" lautet (deutsch oder englisch,
 * so benennt die App die Fahrt beim Speichern) **und** sie fast die ganze Strecke
 * fuhr (mindestens [MIN_COMPLETION] der Laenge — Fahrten „ab km x" oder
 * abgebrochene Fahrten zaehlen nicht). Die Bestzeit ist die kuerzeste Dauer
 * (bewegte Zeit, sonst Gesamtzeit). Schluessel: Katalogkennung.
 *
 * Grenze: Eine Tour kennt ihre Strecke nur ueber den Namen. Wer eine
 * gespeicherte Indoor-Fahrt umbenennt, verliert die Zuordnung.
 */
fun catalogBestTimes(refs: List<CatalogRouteRef>, rides: List<CatalogRideRecord>): Map<String, Int> {
    val out = HashMap<String, Int>()
    for (e in refs) {
        val best = rides.asSequence()
            .filter { r -> r.seconds != null && r.seconds > 0 }
            .filter { r -> r.distanceKm * 1000.0 >= e.lengthM * MIN_COMPLETION }
            .filter { r -> r.name.startsWith(INDOOR_RIDE_NAME_PREFIX) && r.name.removePrefix(INDOOR_RIDE_NAME_PREFIX) in e.names }
            .minOfOrNull { it.seconds!! }
        if (best != null) out[e.id] = best
    }
    return out
}

/** Anteil der Streckenlaenge, den eine Fahrt mindestens haben muss, um als „gefahren" zu gelten. */
const val MIN_COMPLETION: Double = 0.95
