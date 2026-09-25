package de.trailscape.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * # Schotteranteil einer Route — „ca. 62 % unbefestigt"
 *
 * Fuer Gravel-Fahrer ist die wichtigste Frage an einen Routenvorschlag nicht
 * die Distanz, sondern **worauf** er faehrt. Diese Datei beantwortet sie aus
 * den Daten, die BRouter ohnehin mitschickt.
 *
 * ## Woher die Weg-Merkmale kommen (Pruefung vor dem Bau)
 * Jede GeoJSON-Antwort von BRouter traegt unter `properties.messages` eine
 * Tabelle: eine Kopfzeile (`Longitude`, …, `Distance`, …, `WayTags`, …) und je
 * **Wegabschnitt** eine Zeile mit dessen Laenge in Metern und den OSM-Merkmalen
 * des Wegs (`highway=track surface=gravel tracktype=grade2 …`). Aufeinander
 * folgende Punkte auf demselben Weg fasst die Engine zu einer Zeile zusammen
 * (`OsmTrack.aggregateMessages`), die Summe aller `Distance`-Werte ist exakt
 * `track-length`.
 *
 * Geprueft mit echten Antworten (liegen als Fixtures unter
 * `src/test/resources/brouter/`, Strecke durch die Dresdner Heide):
 *
 *  * **brouter.de** mit `trekking`, `shortest` und dem hochgeladenen
 *    Gravel-Profil — alle mit `WayTags`.
 *  * **Eingebettete Engine** (`routeOffline`, Kachel `E10_N50.rd5`) mit
 *    `gravel.brf`, `trekking.brf`, `fastbike.brf`, `shortest.brf` — ebenfalls
 *    mit `WayTags`, und fuer dieselbe Kachelversion Zeile fuer Zeile identisch
 *    mit der Serverantwort (derselbe `FormatJson`-Code erzeugt beide).
 *
 * Offline und online liefern also dasselbe; der Abbruchweg „nur online" war
 * nicht noetig.
 *
 * ## Warum nicht alle Profile gleich viel verraten
 * Die Engine gibt **nur die Merkmale aus, die das Profil selbst abfragt**
 * (`TagValueCoder` verwirft beim Lesen der Kachel alle anderen, sofern das
 * Profil nicht `processUnusedTags` setzt). `shortest.brf` fragt `surface`
 * nie ab — dort gibt es nur `highway` und `tracktype`. Deshalb faellt
 * [classifySurface] stufenweise zurueck (Belag → Tracktyp → Wegart), und
 * deshalb gibt es [SurfaceClass.UNKNOWN] samt der Ausblend-Regel
 * [maxUnknownSurfaceShare]: Lieber keine Angabe als eine, die ueberwiegend
 * geraten ist.
 *
 * ## Robustheit
 * Die Anzeige ist Zusatzwissen. Fehlen die `messages`, sind sie kaputt oder
 * passt eine Zeile nicht zur Kopfzeile, gibt es `null` — die Route selbst wird
 * davon nie beruehrt, [parseBrouterGeoJson] wirft deswegen nicht.
 */

/** Belagsklasse eines Wegabschnitts, siehe [classifySurface]. */
enum class SurfaceClass { PAVED, UNPAVED, UNKNOWN }

/**
 * `surface`-Werte, die als **befestigt** gelten.
 *
 * Neben Asphalt und Beton auch Pflaster (`sett`, `cobblestone`,
 * `paving_stones`): Es rumpelt, ist aber eine feste Decke — die Frage des
 * Gravelfahrers ist „Schotter oder nicht", nicht „bequem oder nicht". Holz und
 * Metall kommen praktisch nur auf Bruecken und Stegen vor. Die Namen sind die
 * **kanonischen** Werte aus BRouters `lookups.dat` — Synonyme wie `bricks`
 * oder `concrete:plates` hat die Engine beim Kachelbau bereits darauf
 * abgebildet (`paved` bzw. `concrete`).
 */
private val PAVED_SURFACES: Set<String> = setOf(
    "asphalt", "paved", "concrete", "paving_stones", "sett", "cobblestone",
    "grass_paver", "wood", "metal",
)

/** `surface`-Werte, die als **unbefestigt** gelten — alles, was staubt oder matscht. */
private val UNPAVED_SURFACES: Set<String> = setOf(
    "unpaved", "gravel", "fine_gravel", "compacted", "pebblestone", "dirt", "ground",
    "earth", "grass", "sand", "mud", "clay", "rock", "stone",
)

/**
 * Wegarten, die ohne jede Belagsangabe als **befestigt** gelten.
 *
 * Das ist die OSM-Konvention: Strassen fuer den Kraftverkehr sind asphaltiert,
 * solange nichts anderes getaggt ist — Kartenstile und Router (BRouters
 * eigene Profile eingeschlossen) nehmen genau das an. Fuer `track`, `path`,
 * `cycleway`, `footway`, `bridleway` gibt es diese Konvention **nicht**; ohne
 * `surface`/`tracktype` bleiben sie [SurfaceClass.UNKNOWN] statt geraten.
 */
private val PAVED_HIGHWAYS: Set<String> = setOf(
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "living_street", "service", "road",
)

/**
 * Zerlegt eine `WayTags`-Zelle (`highway=track surface=gravel …`) in eine Map.
 *
 * Leerzeichen trennen, weil BRouter die Werte ausschliesslich als einzelne
 * Tokens aus `lookups.dat` ausgibt — ein Wert mit Leerzeichen kommt nicht vor.
 * Eintraege ohne `=` werden uebergangen.
 */
internal fun parseWayTags(wayTags: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (token in wayTags.trim().split(Regex("\\s+"))) {
        val eq = token.indexOf('=')
        if (eq <= 0 || eq == token.length - 1) continue
        out[token.substring(0, eq)] = token.substring(eq + 1)
    }
    return out
}

/**
 * Ordnet einen Wegabschnitt anhand seiner `WayTags` einer [SurfaceClass] zu.
 *
 * Reihenfolge, genauste Quelle zuerst:
 *  1. `surface` — der Belag selbst ([PAVED_SURFACES] / [UNPAVED_SURFACES]).
 *     Ein unbekannter Wert faellt zur naechsten Stufe durch, statt den
 *     Abschnitt gleich aufzugeben.
 *  2. `tracktype` — `grade1` ist per Definition befestigt (Asphalt/Beton),
 *     `grade2` bis `grade5` sind Schotter bis Wiese.
 *  3. `highway` — nur fuer Kraftverkehrsstrassen, siehe [PAVED_HIGHWAYS].
 *
 * `smoothness` wird bewusst nicht ausgewertet: „schlecht" gibt es auf Asphalt
 * wie auf Schotter, es sagt nichts ueber befestigt/unbefestigt.
 */
fun classifySurface(wayTags: String): SurfaceClass {
    val tags = parseWayTags(wayTags)
    when (tags["surface"]) {
        in PAVED_SURFACES -> return SurfaceClass.PAVED
        in UNPAVED_SURFACES -> return SurfaceClass.UNPAVED
    }
    when (tags["tracktype"]) {
        "grade1" -> return SurfaceClass.PAVED
        "grade2", "grade3", "grade4", "grade5" -> return SurfaceClass.UNPAVED
    }
    if (tags["highway"] in PAVED_HIGHWAYS) {
        return SurfaceClass.PAVED
    }
    return SurfaceClass.UNKNOWN
}

/** Kilometer je [SurfaceClass] einer Route. */
data class SurfaceBreakdown(
    val pavedKm: Double,
    val unpavedKm: Double,
    val unknownKm: Double,
)

/**
 * Liest die `messages`-Tabelle einer BRouter-Antwort und summiert die
 * Abschnittslaengen je [SurfaceClass].
 *
 * `null`, sobald irgendetwas nicht passt: fehlende oder falsch getypte
 * Tabelle, Kopfzeile ohne `Distance`/`WayTags`, eine Zeile, die zu kurz ist
 * oder keine endliche, nichtnegative Laenge traegt, oder eine Gesamtlaenge
 * von 0. Bewusst **alles oder nichts**: Eine halb gelesene Tabelle ergaebe
 * einen Anteil, der falsch waere, ohne falsch auszusehen.
 */
internal fun surfaceBreakdownFromMessages(messages: JsonElement?): SurfaceBreakdown? {
    val rows = messages as? JsonArray ?: return null
    val header = rows.firstOrNull() as? JsonArray ?: return null
    val names = header.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    val distanceIdx = names.indexOf("Distance")
    val tagsIdx = names.indexOf("WayTags")
    if (distanceIdx < 0 || tagsIdx < 0) return null

    var pavedM = 0.0
    var unpavedM = 0.0
    var unknownM = 0.0
    for (i in 1 until rows.size) {
        val row = rows[i] as? JsonArray ?: return null
        if (row.size <= max(distanceIdx, tagsIdx)) return null
        val distanceM = (row[distanceIdx] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        if (!distanceM.isFinite() || distanceM < 0) return null
        val tags = (row[tagsIdx] as? JsonPrimitive)?.content ?: return null
        when (classifySurface(tags)) {
            SurfaceClass.PAVED -> pavedM += distanceM
            SurfaceClass.UNPAVED -> unpavedM += distanceM
            SurfaceClass.UNKNOWN -> unknownM += distanceM
        }
    }
    if (pavedM + unpavedM + unknownM <= 0) return null
    return SurfaceBreakdown(
        pavedKm = pavedM / 1000,
        unpavedKm = unpavedM / 1000,
        unknownKm = unknownM / 1000,
    )
}

/**
 * Hoechster Anteil unklassifizierter Strecke, bei dem der Schotteranteil noch
 * angezeigt wird. Darueber waere die Zahl mehr Vermutung als Messung —
 * typisch fuer `shortest`, das `surface` gar nicht ausgibt, oder fuer Gegenden
 * mit duenn getaggten Pfaden.
 */
const val maxUnknownSurfaceShare: Double = 0.5

/**
 * Anteil unbefestigter Strecke in ganzen Prozent — oder `null`, wenn es nichts
 * Verlaessliches zu sagen gibt (keine Belagsdaten, siehe
 * [surfaceBreakdownFromMessages], oder mehr als [maxUnknownSurfaceShare]
 * unklassifiziert).
 *
 * ## Warum der Anteil an der *klassifizierten* Strecke
 * Die unbekannten Kilometer (hoechstens die Haelfte) werden weder zu
 * „befestigt" noch zu „unbefestigt" gezaehlt, sondern im selben Verhaeltnis
 * wie der Rest angenommen. Gegen die Gesamtstrecke gerechnet waere die Zahl
 * eine Untergrenze, die bei 40 % Unbekanntem glatt die Haelfte des Schotters
 * verschweigen koennte — das „ca." der Anzeige deckt die Hochrechnung ehrlicher
 * ab als eine systematisch zu kleine Zahl.
 *
 * Die unbekannte Strecke ergibt sich aus [distanceKm] minus der beiden
 * klassifizierten Summen; so bleibt das Speicherformat bei zwei Feldern
 * (siehe [PlannedRoute.pavedKm]). Die `messages` summieren sich exakt zu
 * `track-length`, der Rest ist also genau der unklassifizierte Teil.
 */
fun unpavedPercent(distanceKm: Double, pavedKm: Double?, unpavedKm: Double?): Int? {
    if (pavedKm == null || unpavedKm == null) return null
    if (!pavedKm.isFinite() || !unpavedKm.isFinite() || pavedKm < 0 || unpavedKm < 0) return null
    val knownKm = pavedKm + unpavedKm
    val totalKm = if (distanceKm.isFinite()) max(distanceKm, knownKm) else knownKm
    if (knownKm <= 0 || totalKm <= 0) return null
    val unknownShare = (totalKm - knownKm) / totalKm
    if (unknownShare > maxUnknownSurfaceShare) return null
    return (unpavedKm / knownKm * 100).roundToInt()
}

/** [unpavedPercent] der Route, siehe dort. */
val PlannedRoute.unpavedPercent: Int?
    get() = unpavedPercent(distanceKm, pavedKm, unpavedKm)

/** [unpavedPercent] der Runde, siehe dort. */
val RouteCandidate.unpavedPercent: Int?
    get() = route.unpavedPercent

/**
 * „ca. 62 % unbefestigt" — oder `null`, wenn der Anteil nicht angezeigt werden
 * soll (siehe [unpavedPercent]). Das „ca." ist Pflicht: OSM-Beläge sind
 * Freiwilligendaten, und ein Teil der Strecke ist hochgerechnet.
 */
fun unpavedLabel(route: PlannedRoute): String? =
    route.unpavedPercent?.let { "ca. $it % unbefestigt" }

// ---------------------------------------------------------------------------
// Gelaendeart „Flach / Wellig / Bergig"
// ---------------------------------------------------------------------------

/**
 * Gelaendeart einer Runde aus ihrer Steigungsdichte (Hoehenmeter je km).
 *
 * ## Warum genau diese Schwellen
 * Es sind **dieselben** Grenzen, mit denen der Rundkurs-Generator die Runden
 * bewertet ([ascentScore]): bis [flatAscentPerKmLimit] (8 Hm/km) ist eine
 * Runde fuer „Flach" straffrei, das Band bis [moderateAscentPerKmHigh]
 * (16 Hm/km) ist genau das, was „Wellig" sucht. Eigene Schwellen fuer die
 * Anzeige haetten zur Folge, dass eine Runde, die der Generator als perfekt
 * „wellig" gewertet hat, als „Bergig" beschriftet wird — die Beschriftung
 * muss dieselbe Sprache sprechen wie die Auswahl oben im Blatt.
 *
 * Zur Einordnung: 8 Hm/km sind auf 50 km 400 Hm — Flachland mit ein paar
 * Wellen; 16 Hm/km sind 800 Hm, das ist Mittelgebirge wie die Schwaebische
 * Alb. Nicht endliche oder negative Werte gelten als flach.
 */
fun terrainClass(ascentPerKm: Double): AscentPreference {
    val mkm = if (ascentPerKm.isFinite() && ascentPerKm > 0) ascentPerKm else 0.0
    return when {
        mkm < flatAscentPerKmLimit -> AscentPreference.FLACH
        mkm <= moderateAscentPerKmHigh -> AscentPreference.MODERAT
        else -> AscentPreference.BERGIG
    }
}

/** „Flach", „Wellig" oder „Bergig" — dieselben Worte wie [ascentPreferenceLabels]. */
fun terrainLabel(ascentPerKm: Double): String =
    ascentPreferenceLabels.getValue(terrainClass(ascentPerKm))
