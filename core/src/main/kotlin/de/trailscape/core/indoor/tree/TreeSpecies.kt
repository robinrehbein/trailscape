package de.trailscape.core.indoor.tree

/**
 * # Arten des Baum-Prototyps (Nahzone)
 *
 * Parametrisches Wachstumsmodell nach Weber/Penn („Creation and Rendering of
 * Realistic Trees", SIGGRAPH 1995), vereinfacht: ein Stamm, zwei Astordnungen
 * als Geometrie, Laub als Zweigbueschel an den Enden der feinsten Ordnung.
 * Jede Art beschreibt ihre Krone ueber eine **Kronenform** (Laenge der
 * Hauptaeste ueber der Hoehe am Stamm), Abgangswinkel, Kruemmung, Zahl und
 * Verjuengung der Aeste und das Laub (Motiv, Bueschelgroesse, Farbe).
 *
 * Die Bibliothek aendert nichts im Weltbau (`world/`): sie nutzt von dort nur
 * die Bausteine `MeshBuilder`, `Vec3`/`Quat`, `GlbScene` und die Bild-Encoder
 * und erzeugt Vorlagen um den Fusspunkt (0, 0, 0), glTF-Achsen (y oben).
 */
enum class TreeKind(val label: String) {
    /** Rotbuche: glatter grauer Stamm, gewoelbte, geschlossene Krone. */
    BEECH("Buche"),

    /** Stieleiche: kurzer, kraeftiger Stamm, breite, knorrige Krone mit Luecken. */
    OAK("Eiche"),

    /** Haengebirke: weisser Stamm, lockere Krone mit haengenden Zweigen. */
    BIRCH("Birke"),

    /** Pyramidenpappel: Saeule aus steil aufstrebenden Aesten. */
    POPLAR("Pappel"),

    /** Fichte: durchgehender Stamm, Astquirle, Kegel. */
    SPRUCE("Fichte"),

    /** Waldkiefer: hoher kahler Stamm (rot oben), lockere Schirmkrone. */
    PINE("Kiefer"),

    /** Aleppo-Kiefer (Mallorca, Sa Calobra): schiefer Stamm, offene, unregelmaessige Wolkenkrone. */
    ALEPPO_PINE("Aleppo-Kiefer"),

    /** Obstbaum (Apfel): kurzer Stamm, breite, flache Krone aus Geruestaesten. */
    FRUIT("Obstbaum"),

    /** Strauch (Hasel/Weissdorn): mehrere Triebe aus dem Boden. */
    SHRUB("Strauch"),
}

/** Detailstufe des Prototyps. Die Fernstufe bleibt die heutige (`TreeModels.Lod.FAR`). */
enum class TreeLod { NEAR, MID }

/** Huellform der Krone (Weber/Penn `Shape`): Laenge der Hauptaeste je Lage im Kronenbereich. */
internal enum class CrownShape {
    CONICAL, SPHERICAL, HEMISPHERICAL, CYLINDRICAL, TAPERED_CYLINDRICAL, FLAME, INVERSE_CONICAL, TEND_FLAME;

    /** Laengenfaktor 0..1 an der relativen Lage [r] (0 = Kronenansatz, 1 = Spitze). */
    fun ratio(r: Double): Double {
        val x = r.coerceIn(0.0, 1.0)
        return when (this) {
            CONICAL -> 0.2 + 0.8 * (1 - x)
            SPHERICAL -> 0.2 + 0.8 * kotlin.math.sin(Math.PI * x)
            HEMISPHERICAL -> 0.2 + 0.8 * kotlin.math.sqrt(1 - x * x)
            CYLINDRICAL -> 1.0
            TAPERED_CYLINDRICAL -> 0.5 + 0.5 * (1 - x)
            FLAME -> if (x <= 0.7) x / 0.7 else (1 - x) / 0.3
            INVERSE_CONICAL -> 1 - 0.8 * (1 - x)
            TEND_FLAME -> if (x <= 0.7) 0.5 + 0.5 * x / 0.7 else 0.5 + 0.5 * (1 - x) / 0.3
        }
    }
}

/** Laubmotiv im Laubatlas ([TreeTextures]). */
internal enum class LeafMotif { BROAD, SMALL, NEEDLE, PINE }

/** Rindenspalte im Rindenatlas ([TreeTextures]). */
internal enum class BarkMotif(val column: Int) { SMOOTH(0), FURROWED(1), BIRCH(2), SCALY(3) }

/** Eine Astordnung (1 = Hauptaeste am Stamm, 2 = Seitenaeste). */
internal class BranchLevel(
    /** Aeste je Elternast (Ordnung 1: am Stamm; Ordnung 2: je Meter Elternast). */
    val count: Double,
    /** Laenge relativ zur Elternlaenge (Ordnung 1: zur Baumhoehe, vor der Kronenform). */
    val lengthRatio: Double,
    /** Abgangswinkel zur Elternachse (Grad) und seine Streuung. */
    val downAngle: Double,
    val downAngleVar: Double,
    /** Kruemmung ueber die Astlaenge (Grad, + nach oben, − nach unten haengend). */
    val curve: Double,
    /** Zusaetzliche Kruemmung zur Spitze hin (Grad), z. B. Fichtenzweige, die sich wieder heben. */
    val curveBack: Double = 0.0,
    /** Radius am Ansatz relativ zum Elternradius an dieser Stelle. */
    val radiusRatio: Double = 0.55,
    /** Ab welchem Anteil der Elternlaenge Kinder ansetzen. */
    val start: Double = 0.15,
    /** Unregelmaessigkeit der Richtung (Grad je Segment). */
    val gnarl: Double = 6.0,
)

/** Alle Wuchsparameter einer Art. */
internal class SpeciesParams(
    val kind: TreeKind,
    /** Typische Hoehe (m) der Varianten 0..3. */
    val heights: DoubleArray,
    val shape: CrownShape,
    /** Kahler Stammanteil unter der Krone (Weber/Penn `BaseSize`). */
    val baseSize: Double,
    /** Stammradius (Brusthoehe) je Meter Hoehe und Deckel. */
    val trunkRadiusPerM: Double,
    val trunkRadiusMax: Double,
    /** Stamm endet bei diesem Anteil der Hoehe (Laubbaeume teilen sich in Geruestaeste). */
    val trunkTop: Double,
    /** Stammfuss-Anlauf (Wurzelanlauf, Faktor am Boden). */
    val flare: Double,
    /** Schraeglage des Stamms (Grad, Streuung) und Kruemmung je Segment. */
    val lean: Double,
    val trunkGnarl: Double,
    /** Hauptaeste in Quirlen (Nadelbaeume) statt spiralig. */
    val whorls: Boolean,
    val level1: BranchLevel,
    val level2: BranchLevel?,
    /** Mehrere Triebe aus dem Boden (Strauch). */
    val stems: Int = 1,
    /** Laubmotiv der Zweigbueschel. */
    val leafMotif: LeafMotif,
    /** Kantenlaenge eines Zweigbueschels (m, nah); mittel × [midClusterScale]. */
    val clusterM: Double,
    /** Bueschel je Meter Endast (nah). */
    val clustersPerM: Double,
    /** Zahl der Karten je Bueschel (nah). */
    val cardsPerCluster: Int,
    /** Grundfarbe des Laubs (sRGB) und Spanne der Helligkeit. */
    val leafSrgb: IntArray,
    val barkMotif: BarkMotif,
    /** Rindenfarbe (sRGB, mit dem hellen Rindenatlas multipliziert); oberer Stamm bei der Kiefer. */
    val barkSrgb: IntArray,
    val barkTopSrgb: IntArray? = null,
    /** Zweige der feinsten Ordnung haengen (Birke) — Bueschel zeigen nach unten. */
    val hanging: Boolean = false,
    /** Mittlere Stufe: Bueschel groesser um diesen Faktor. */
    val midClusterScale: Double = 2.2,
    /** Breitenfaktor der Krone (Hauptastlaenge) der Varianten. */
    val widthVar: Double = 0.12,
)

internal object Species {
    fun of(kind: TreeKind): SpeciesParams = TABLE.getValue(kind)

    private val TABLE: Map<TreeKind, SpeciesParams> = listOf(
        SpeciesParams(
            TreeKind.BEECH, doubleArrayOf(22.0, 18.0, 26.0, 14.0), CrownShape.SPHERICAL, 0.26,
            trunkRadiusPerM = 0.014, trunkRadiusMax = 0.55, trunkTop = 0.78, flare = 0.55, lean = 3.0, trunkGnarl = 2.5,
            whorls = false,
            level1 = BranchLevel(16.0, 0.36, 52.0, 12.0, curve = 20.0, radiusRatio = 0.55, gnarl = 6.0),
            level2 = BranchLevel(3.4, 0.38, 48.0, 14.0, curve = -12.0, radiusRatio = 0.5, start = 0.12, gnarl = 10.0),
            leafMotif = LeafMotif.BROAD, clusterM = 1.35, clustersPerM = 1.7, cardsPerCluster = 3,
            leafSrgb = intArrayOf(92, 124, 50), barkMotif = BarkMotif.SMOOTH, barkSrgb = intArrayOf(150, 148, 140),
        ),
        SpeciesParams(
            TreeKind.OAK, doubleArrayOf(20.0, 16.0, 24.0, 12.0), CrownShape.HEMISPHERICAL, 0.22,
            trunkRadiusPerM = 0.022, trunkRadiusMax = 0.75, trunkTop = 0.55, flare = 0.7, lean = 4.0, trunkGnarl = 5.0,
            whorls = false,
            level1 = BranchLevel(14.0, 0.42, 58.0, 16.0, curve = 22.0, radiusRatio = 0.62, gnarl = 16.0),
            level2 = BranchLevel(3.2, 0.36, 55.0, 18.0, curve = -6.0, radiusRatio = 0.5, start = 0.12, gnarl = 18.0),
            leafMotif = LeafMotif.BROAD, clusterM = 1.3, clustersPerM = 1.6, cardsPerCluster = 3,
            leafSrgb = intArrayOf(82, 108, 46), barkMotif = BarkMotif.FURROWED, barkSrgb = intArrayOf(120, 112, 102),
            widthVar = 0.16,
        ),
        SpeciesParams(
            TreeKind.BIRCH, doubleArrayOf(17.0, 14.0, 20.0, 11.0), CrownShape.FLAME, 0.3,
            trunkRadiusPerM = 0.010, trunkRadiusMax = 0.3, trunkTop = 0.95, flare = 0.35, lean = 4.0, trunkGnarl = 2.0,
            whorls = false,
            level1 = BranchLevel(18.0, 0.42, 48.0, 10.0, curve = -30.0, curveBack = 0.0, radiusRatio = 0.45, gnarl = 6.0),
            level2 = BranchLevel(3.4, 0.45, 35.0, 12.0, curve = -70.0, radiusRatio = 0.45, start = 0.15, gnarl = 8.0),
            leafMotif = LeafMotif.SMALL, clusterM = 1.1, clustersPerM = 1.7, cardsPerCluster = 2,
            leafSrgb = intArrayOf(112, 140, 58), barkMotif = BarkMotif.BIRCH, barkSrgb = intArrayOf(226, 224, 216),
            hanging = true,
        ),
        SpeciesParams(
            TreeKind.POPLAR, doubleArrayOf(24.0, 20.0, 27.0, 16.0), CrownShape.CYLINDRICAL, 0.06,
            trunkRadiusPerM = 0.013, trunkRadiusMax = 0.45, trunkTop = 0.97, flare = 0.4, lean = 1.5, trunkGnarl = 1.5,
            whorls = false,
            level1 = BranchLevel(26.0, 0.17, 18.0, 6.0, curve = 6.0, radiusRatio = 0.4, gnarl = 4.0),
            level2 = BranchLevel(3.0, 0.45, 25.0, 8.0, curve = 4.0, radiusRatio = 0.5, start = 0.15, gnarl = 6.0),
            leafMotif = LeafMotif.BROAD, clusterM = 1.1, clustersPerM = 1.8, cardsPerCluster = 2,
            leafSrgb = intArrayOf(96, 126, 56), barkMotif = BarkMotif.FURROWED, barkSrgb = intArrayOf(146, 140, 126),
            widthVar = 0.08,
        ),
        SpeciesParams(
            TreeKind.SPRUCE, doubleArrayOf(24.0, 18.0, 28.0, 12.0), CrownShape.CONICAL, 0.12,
            trunkRadiusPerM = 0.011, trunkRadiusMax = 0.42, trunkTop = 1.0, flare = 0.45, lean = 1.0, trunkGnarl = 1.0,
            whorls = true,
            level1 = BranchLevel(60.0, 0.17, 96.0, 8.0, curve = -14.0, curveBack = 22.0, radiusRatio = 0.3, gnarl = 5.0),
            level2 = BranchLevel(2.6, 0.4, 60.0, 14.0, curve = -26.0, radiusRatio = 0.5, start = 0.1, gnarl = 8.0),
            leafMotif = LeafMotif.NEEDLE, clusterM = 1.1, clustersPerM = 1.8, cardsPerCluster = 2,
            leafSrgb = intArrayOf(50, 78, 50), barkMotif = BarkMotif.SCALY, barkSrgb = intArrayOf(112, 92, 78),
            widthVar = 0.08,
        ),
        SpeciesParams(
            TreeKind.PINE, doubleArrayOf(22.0, 18.0, 26.0, 14.0), CrownShape.TEND_FLAME, 0.55,
            trunkRadiusPerM = 0.012, trunkRadiusMax = 0.42, trunkTop = 0.9, flare = 0.4, lean = 3.0, trunkGnarl = 3.0,
            whorls = true,
            level1 = BranchLevel(18.0, 0.24, 70.0, 14.0, curve = 18.0, radiusRatio = 0.45, gnarl = 12.0),
            level2 = BranchLevel(2.6, 0.45, 50.0, 16.0, curve = 12.0, radiusRatio = 0.5, start = 0.25, gnarl = 14.0),
            leafMotif = LeafMotif.PINE, clusterM = 1.1, clustersPerM = 1.8, cardsPerCluster = 2,
            leafSrgb = intArrayOf(66, 92, 56), barkMotif = BarkMotif.FURROWED, barkSrgb = intArrayOf(110, 92, 80),
            barkTopSrgb = intArrayOf(196, 132, 92),
        ),
        SpeciesParams(
            TreeKind.ALEPPO_PINE, doubleArrayOf(13.0, 10.0, 16.0, 8.0), CrownShape.HEMISPHERICAL, 0.4,
            trunkRadiusPerM = 0.018, trunkRadiusMax = 0.4, trunkTop = 0.7, flare = 0.5, lean = 12.0, trunkGnarl = 7.0,
            whorls = false,
            level1 = BranchLevel(10.0, 0.42, 55.0, 18.0, curve = 22.0, radiusRatio = 0.6, gnarl = 18.0),
            level2 = BranchLevel(2.8, 0.42, 45.0, 18.0, curve = 10.0, radiusRatio = 0.5, start = 0.25, gnarl = 18.0),
            leafMotif = LeafMotif.PINE, clusterM = 1.15, clustersPerM = 1.8, cardsPerCluster = 2,
            leafSrgb = intArrayOf(84, 104, 60), barkMotif = BarkMotif.FURROWED, barkSrgb = intArrayOf(124, 108, 96),
            widthVar = 0.2,
        ),
        SpeciesParams(
            TreeKind.FRUIT, doubleArrayOf(7.0, 6.0, 8.5, 5.0), CrownShape.SPHERICAL, 0.3,
            trunkRadiusPerM = 0.022, trunkRadiusMax = 0.24, trunkTop = 0.42, flare = 0.3, lean = 5.0, trunkGnarl = 6.0,
            whorls = false,
            level1 = BranchLevel(7.0, 0.62, 58.0, 14.0, curve = 14.0, radiusRatio = 0.7, start = 0.5, gnarl = 16.0),
            level2 = BranchLevel(2.6, 0.45, 50.0, 16.0, curve = -10.0, radiusRatio = 0.5, start = 0.15, gnarl = 16.0),
            leafMotif = LeafMotif.BROAD, clusterM = 0.95, clustersPerM = 2.0, cardsPerCluster = 3,
            leafSrgb = intArrayOf(104, 132, 54), barkMotif = BarkMotif.FURROWED, barkSrgb = intArrayOf(128, 116, 104),
            widthVar = 0.15,
        ),
        SpeciesParams(
            TreeKind.SHRUB, doubleArrayOf(3.0, 2.4, 3.6, 1.8), CrownShape.SPHERICAL, 0.0,
            trunkRadiusPerM = 0.012, trunkRadiusMax = 0.06, trunkTop = 0.9, flare = 0.0, lean = 22.0, trunkGnarl = 8.0,
            whorls = false,
            level1 = BranchLevel(5.0, 0.42, 45.0, 15.0, curve = -10.0, radiusRatio = 0.6, start = 0.3, gnarl = 16.0),
            level2 = null, stems = 5,
            leafMotif = LeafMotif.BROAD, clusterM = 0.75, clustersPerM = 3.0, cardsPerCluster = 3,
            leafSrgb = intArrayOf(86, 114, 46), barkMotif = BarkMotif.SMOOTH, barkSrgb = intArrayOf(120, 108, 96),
            widthVar = 0.15,
        ),
    ).associateBy { it.kind }
}
