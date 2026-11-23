package de.trailscape.core.indoor

import de.trailscape.core.SurfaceClass

/**
 * # Feine Belagsklassen des Indoor-Trainings
 *
 * VERTRAGSDATEI (docs/indoor.md, Abschnitt 6). Besitzer: Slice indoor-core.
 * Signaturen und Enum-Namen sind eingefroren; die Namen stehen im
 * Welt-Manifest (`surfaces[].kind`) und in Materialnamen der GLB-Kacheln
 * (`mat_route_<textureKey>`).
 *
 * `RouteSurface.kt` kennt nur befestigt/unbefestigt — genug fuer „ca. 62 %
 * unbefestigt", zu grob fuer eine Fahrt, die sich je Wegabschnitt anders
 * anfuehlen und anders aussehen soll. Diese Liste ist die **eine** Wahrheit
 * fuer beides: Die Fahrphysik liest [crr], der Weltbau [textureKey] und
 * [defaultWidthM].
 *
 * ## Woher die Rollwiderstaende kommen
 * Es sind begruendete Annahmen fuer einen etwa 40 mm breiten Gravelreifen bei
 * rund 2,5–3 bar, keine Messungen: Asphalt wie die Rollentests von
 * bicyclerollingresistance.com mal dem ueblichen Aufschlag von der Stahltrommel
 * zur echten Strasse; die losen Belaege in der Groessenordnung, die Zwift fuer
 * Gravelreifen ansetzt (Asphalt 0,008, Schotter 0,012, Erde 0,018) und die
 * Feldmessungen von Silca/Tom Anhalt nahelegen. [UNKNOWN] ist exakt
 * `defaultCrr` (0,008, „Gravelreifen auf Mischuntergrund") aus
 * `TrainingLoad.kt` — eine Strecke ohne Belagsdaten faehrt sich damit wie
 * bisher gerechnet.
 *
 * Der wirksame Beiwert einer Fahrt ist `crr × (Profil.crr / 0,008)`
 * ([effectiveCrr]): Wer im Profil einen leichter rollenden Reifen eintraegt,
 * bekommt ihn auf jedem Belag.
 *
 * @property crr Rollwiderstandsbeiwert der Klasse (dimensionslos).
 * @property textureKey stabiler Kleinbuchstaben-Schluessel fuer Texturen und
 *   Materialnamen.
 * @property defaultWidthM Breite des gezeichneten Wegs in Metern, wenn die
 *   Wegart nichts Genaueres sagt.
 * @property coarse die grobe Klasse aus `RouteSurface.kt`, zu der diese
 *   feine gehoert.
 */
enum class SurfaceKind(
    val crr: Double,
    val textureKey: String,
    val defaultWidthM: Double,
    val coarse: SurfaceClass,
) {
    /** Asphalt, auch `paved`/`chipseal`; Strassen ohne Belagsangabe. */
    ASPHALT(0.0055, "asphalt", 5.0, SurfaceClass.PAVED),

    /** Beton, Betonplatten, Betonspurbahn. */
    CONCRETE(0.0060, "concrete", 3.5, SurfaceClass.PAVED),

    /** Verbundpflaster, Gehwegplatten, Rasengitter. */
    PAVING_STONES(0.0075, "paving", 3.0, SurfaceClass.PAVED),

    /** Kopfsteinpflaster (`sett`, `cobblestone`). */
    COBBLESTONE(0.0110, "cobble", 4.0, SurfaceClass.PAVED),

    /** Holz oder Metall — Bruecken und Stege. */
    WOOD(0.0070, "wood", 2.5, SurfaceClass.PAVED),

    /** Wassergebundene Decke, feiner Splitt (`compacted`, `fine_gravel`, Tracktyp 2). */
    COMPACTED(0.0080, "compacted", 3.0, SurfaceClass.UNPAVED),

    /** Grober Schotter (`gravel`, `pebblestone`, `unpaved`, Tracktyp 3). */
    GRAVEL(0.0120, "gravel", 3.0, SurfaceClass.UNPAVED),

    /** Wald- und Erdboden (`ground`, `dirt`, `earth`, `clay`, Tracktyp 4, Pfade). */
    DIRT(0.0160, "dirt", 1.5, SurfaceClass.UNPAVED),

    /** Wiesenweg (`grass`, Tracktyp 5). */
    GRASS(0.0250, "grass", 2.0, SurfaceClass.UNPAVED),

    /** Sand und Schlamm. */
    SAND(0.0350, "sand", 2.0, SurfaceClass.UNPAVED),

    /** Keine Angabe. Physik: Mischwert; Aussehen: [visual]. */
    UNKNOWN(0.0080, "compacted", 3.0, SurfaceClass.UNKNOWN),
    ;

    /** Die Klasse, deren Aussehen gezeichnet wird — [UNKNOWN] sieht aus wie [COMPACTED]. */
    val visual: SurfaceKind get() = if (this == UNKNOWN) COMPACTED else this

    companion object {
        /** Bezugswert des Profils: `defaultCrr` aus `TrainingLoad.kt`. */
        const val REFERENCE_PROFILE_CRR: Double = 0.008

        /** `null` statt Ausnahme: Ein Manifest einer neueren App soll lesbar bleiben. */
        fun fromNameOrNull(name: String?): SurfaceKind? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Der wirksame Rollwiderstandsbeiwert auf [kind] fuer ein Profil mit
 * [profileCrr] (`TrainingProfile.crr`). Nicht endliche oder nicht positive
 * Profilwerte gelten als Bezugswert.
 */
fun effectiveCrr(kind: SurfaceKind, profileCrr: Double): Double {
    val p = if (profileCrr.isFinite() && profileCrr > 0) profileCrr else SurfaceKind.REFERENCE_PROFILE_CRR
    return kind.crr * (p / SurfaceKind.REFERENCE_PROFILE_CRR)
}

private val SURFACE_VALUES: Map<String, SurfaceKind> = mapOf(
    "asphalt" to SurfaceKind.ASPHALT,
    "paved" to SurfaceKind.ASPHALT,
    "chipseal" to SurfaceKind.ASPHALT,
    "concrete" to SurfaceKind.CONCRETE,
    "concrete:plates" to SurfaceKind.CONCRETE,
    "concrete:lanes" to SurfaceKind.CONCRETE,
    "paving_stones" to SurfaceKind.PAVING_STONES,
    "bricks" to SurfaceKind.PAVING_STONES,
    "grass_paver" to SurfaceKind.PAVING_STONES,
    "sett" to SurfaceKind.COBBLESTONE,
    "cobblestone" to SurfaceKind.COBBLESTONE,
    "unhewn_cobblestone" to SurfaceKind.COBBLESTONE,
    "wood" to SurfaceKind.WOOD,
    "metal" to SurfaceKind.WOOD,
    "compacted" to SurfaceKind.COMPACTED,
    "fine_gravel" to SurfaceKind.COMPACTED,
    "gravel" to SurfaceKind.GRAVEL,
    "pebblestone" to SurfaceKind.GRAVEL,
    "unpaved" to SurfaceKind.GRAVEL,
    "rock" to SurfaceKind.GRAVEL,
    "stone" to SurfaceKind.GRAVEL,
    "ground" to SurfaceKind.DIRT,
    "dirt" to SurfaceKind.DIRT,
    "earth" to SurfaceKind.DIRT,
    "clay" to SurfaceKind.DIRT,
    "grass" to SurfaceKind.GRASS,
    "sand" to SurfaceKind.SAND,
    "mud" to SurfaceKind.SAND,
)

private val TRACKTYPE_VALUES: Map<String, SurfaceKind> = mapOf(
    "grade1" to SurfaceKind.ASPHALT,
    "grade2" to SurfaceKind.COMPACTED,
    "grade3" to SurfaceKind.GRAVEL,
    "grade4" to SurfaceKind.DIRT,
    "grade5" to SurfaceKind.GRASS,
)

/** Wegarten, die ohne Belagsangabe als Asphalt gelten — dieselbe Konvention wie `PAVED_HIGHWAYS` in `RouteSurface.kt`. */
private val ASPHALT_HIGHWAYS: Set<String> = setOf(
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "living_street", "service", "road",
)

/**
 * Ordnet einen Weg anhand seiner OSM-Merkmale einer [SurfaceKind] zu.
 *
 * Reihenfolge, genauste Quelle zuerst — dieselbe Stufung wie `classifySurface`:
 *  1. `surface` (Tabelle oben; unbekannte Werte fallen durch),
 *  2. `tracktype` (`grade1` Asphalt … `grade5` Wiese),
 *  3. `highway`: Kraftverkehrsstrassen → Asphalt, `track` → Schotter,
 *     `path`/`bridleway` → Waldboden. `cycleway`, `footway`, `pedestrian`
 *     bleiben [SurfaceKind.UNKNOWN] — dafuer gibt es keine Konvention.
 *
 * [tags] sind die Merkmale als Map (BRouter-`WayTags` ueber `parseWayTags`,
 * Vektorkacheln siehe [surfaceKindFromVectorTile]).
 */
fun surfaceKindFromTags(tags: Map<String, String>): SurfaceKind {
    SURFACE_VALUES[tags["surface"]]?.let { return it }
    TRACKTYPE_VALUES[tags["tracktype"]]?.let { return it }
    return when (tags["highway"]) {
        in ASPHALT_HIGHWAYS -> SurfaceKind.ASPHALT
        "track" -> SurfaceKind.GRAVEL
        "path", "bridleway" -> SurfaceKind.DIRT
        else -> SurfaceKind.UNKNOWN
    }
}

/**
 * Dasselbe fuer ein Objekt der Ebene `transportation` einer
 * OpenMapTiles-Vektorkachel (OpenFreeMap). Das Schema kennt den Belag nur
 * grob (`surface` = `paved`/`unpaved`) und die Wegart als [roadClass]
 * (`motorway` … `minor`, `service`, `track`, `path`) — die Zuordnung ist
 * deshalb groeber als aus BRouters Wegmerkmalen und nur der Rueckfall
 * (docs/indoor.md, Abschnitt 6).
 */
fun surfaceKindFromVectorTile(roadClass: String?, surface: String?): SurfaceKind = when {
    roadClass == "track" && surface == "paved" -> SurfaceKind.ASPHALT
    roadClass == "track" -> SurfaceKind.GRAVEL
    roadClass == "path" && surface == "paved" -> SurfaceKind.ASPHALT
    roadClass == "path" && surface == "unpaved" -> SurfaceKind.DIRT
    roadClass == "path" -> SurfaceKind.UNKNOWN
    surface == "unpaved" -> SurfaceKind.GRAVEL
    roadClass != null -> SurfaceKind.ASPHALT
    else -> SurfaceKind.UNKNOWN
}
