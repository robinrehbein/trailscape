package de.trailscape.core.indoor.catalog

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.optionalBoolean
import de.trailscape.core.optionalDouble
import de.trailscape.core.optionalInt
import de.trailscape.core.optionalLong
import de.trailscape.core.optionalString
import de.trailscape.core.requiredDouble
import de.trailscape.core.requiredInt
import de.trailscape.core.requiredString
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # Katalog bekannter Strecken mit vorgebauten Welten
 *
 * Neben den selbst gebauten Welten bietet das Indoor-Training einige bekannte
 * Abschnitte (Paesse, Pflaster, Schotter) mit fertig gebauter Welt an. Die
 * Pakete sind gross (rund 6–16 MB je km) und liegen deshalb **nicht** in der
 * APK, sondern als Assets eines GitHub-Releases ([IndoorCatalog.baseUrl]);
 * die App laedt ein Paket erst nach der Nutzeraktion „Laden".
 *
 * Der Katalog selbst ist klein und liegt der App bei (Asset
 * `indoor/catalog.json`) — die Liste ist ohne Netz sichtbar. Dasselbe Format
 * liegt als `catalog.json` im Release; „Katalog aktualisieren" holt es.
 * Erzeugt wird beides vom Entwicklerwerkzeug (`catalog-build`,
 * docs/indoor/catalog.md).
 *
 * Namen sind Ortsnamen, keine Veranstaltermarken (docs/indoor/catalog.md).
 * Reines Kotlin ohne Android, JSON von Hand wie `WorldManifest`.
 */

/** Ein Text in beiden Sprachen der App. */
data class CatalogText(val de: String, val en: String) {
    /** Englisch fuer `en…`, sonst Deutsch (die Quellsprache). */
    fun pick(languageTag: String): String =
        if (languageTag.lowercase().startsWith("en")) en.ifBlank { de } else de.ifBlank { en }

    fun toJson(): JsonObject = buildJsonObject {
        put("de", de)
        put("en", en)
    }

    companion object {
        fun fromJson(o: JsonObject?, fallback: String = ""): CatalogText {
            if (o == null) return CatalogText(fallback, fallback)
            val de = o.optionalString("de") ?: fallback
            return CatalogText(de, o.optionalString("en") ?: de)
        }
    }
}

/** Grobe Art der Strecke, fuer Sortierung und Kennzeichen. */
enum class CatalogRideKind {
    /** Asphalt, Rennrad. */
    ROAD,

    /** Ueberwiegend Schotter/Waldweg. */
    GRAVEL,

    /** Mit nennenswertem Kopfsteinpflaster. */
    COBBLES,
    ;

    companion object {
        fun fromNameOrNull(name: String?): CatalogRideKind? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Ein Eintrag des Katalogs.
 *
 * @property id stabile Kennung (`alpe-dhuez`), Teil des Paketnamens.
 * @property version Welt-Version; steigt bei jedem Neubau, der veroeffentlicht
 *   wird. Eine installierte aeltere Version zeigt „Update verfuegbar".
 * @property worldId `worldId` des Manifests im Paket = Verzeichnisname unter
 *   `<filesDir>/indoor/worlds/`.
 * @property country ISO-3166-1-alpha-2 (`FR`, `DE`).
 * @property region Landschaft/Ort zur Einordnung („Isère, Französische Alpen").
 * @property surfaces Anteile der Belagsklassen an der Laenge (Summe ≈ 1).
 * @property packageFile Dateiname des Pakets im Release (`alpe-dhuez-v1.zip`).
 * @property packageBytes Groesse des Pakets; im beigelegten Katalog ggf. nur
 *   geschaetzt, massgeblich ist der Katalog aus dem Release.
 * @property sha256 Pruefsumme des Pakets (klein, hex); `null` = noch unbekannt
 *   (beigelegter Katalog vor dem ersten Release-Bau) — die App holt sie dann
 *   vor dem Laden aus dem Release-Katalog.
 * @property aerial die Welt hat (zumindest teilweise) ein Luftbild; sonst
 *   erzeugter Boden aus der Landnutzung.
 * @property aerialSource Name der Luftbild-Quelle, falls [aerial].
 */
data class CatalogEntry(
    val id: String,
    val version: Int,
    val worldId: String,
    val name: CatalogText,
    val region: CatalogText,
    val country: String,
    val kind: CatalogRideKind,
    val lengthM: Double,
    val ascentM: Double,
    val surfaces: Map<SurfaceKind, Double>,
    val description: CatalogText,
    val packageFile: String,
    val packageBytes: Long,
    val sha256: String?,
    val aerial: Boolean,
    val aerialSource: String? = null,
    val quality: IndoorQuality = IndoorQuality.MEDIUM,
    val diskBytes: Long = 0L,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("version", version)
        put("worldId", worldId)
        put("name", name.toJson())
        put("region", region.toJson())
        put("country", country)
        put("kind", kind.name)
        put("lengthM", Math.round(lengthM).toDouble())
        put("ascentM", Math.round(ascentM).toDouble())
        put(
            "surfaces",
            buildJsonObject {
                surfaces.entries.sortedBy { it.key.ordinal }.forEach { (k, v) -> put(k.name, Math.round(v * 1000) / 1000.0) }
            },
        )
        put("description", description.toJson())
        put("packageFile", packageFile)
        put("packageBytes", packageBytes)
        sha256?.let { put("sha256", it) }
        put("aerial", aerial)
        aerialSource?.let { put("aerialSource", it) }
        put("quality", quality.name)
        if (diskBytes > 0) put("diskBytes", diskBytes)
    }

    companion object {
        fun fromJson(o: JsonObject): CatalogEntry {
            val id = o.requiredString("id")
            require(isSafeCatalogId(id)) { "Katalog: unzulaessige Kennung $id" }
            val file = o.requiredString("packageFile")
            require(isSafeCatalogId(file.removeSuffix(".zip")) && file.endsWith(".zip")) { "Katalog: unzulaessiger Paketname $file" }
            val worldId = o.requiredString("worldId")
            require(isSafeCatalogId(worldId)) { "Katalog: unzulaessige worldId $worldId" }
            val sha = o.optionalString("sha256")?.lowercase()
            require(sha == null || SHA256_HEX.matches(sha)) { "Katalog: Pruefsumme von $id ist kein SHA-256" }
            val surfaces = (o["surfaces"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                val kind = SurfaceKind.fromNameOrNull(k) ?: return@mapNotNull null
                val f = (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
                kind to f
            }.toMap()
            return CatalogEntry(
                id = id,
                version = o.requiredInt("version"),
                worldId = worldId,
                name = CatalogText.fromJson(o["name"] as? JsonObject, id),
                region = CatalogText.fromJson(o["region"] as? JsonObject),
                country = o.optionalString("country").orEmpty().uppercase(),
                kind = CatalogRideKind.fromNameOrNull(o.optionalString("kind")) ?: CatalogRideKind.ROAD,
                lengthM = o.requiredDouble("lengthM"),
                ascentM = o.optionalDouble("ascentM") ?: 0.0,
                surfaces = surfaces,
                description = CatalogText.fromJson(o["description"] as? JsonObject),
                packageFile = file,
                packageBytes = o.optionalLong("packageBytes") ?: 0L,
                sha256 = sha,
                aerial = o.optionalBoolean("aerial") ?: false,
                aerialSource = o.optionalString("aerialSource"),
                quality = IndoorQuality.fromNameOrNull(o.optionalString("quality")) ?: IndoorQuality.MEDIUM,
                diskBytes = o.optionalLong("diskBytes") ?: 0L,
            )
        }
    }
}

/**
 * Der Katalog.
 *
 * @property baseUrl Adresse der Release-Assets mit abschliessendem `/`
 *   (`https://github.com/robinrehbein/trailscape/releases/download/indoor-worlds-v1/`).
 * @property generatedAtMs Zeitpunkt des Baus (0 = unbekannt).
 */
data class IndoorCatalog(
    val baseUrl: String,
    val generatedAtMs: Long,
    val entries: List<CatalogEntry>,
) {
    fun entry(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }

    /** Adresse des Pakets von [entry]. */
    fun packageUrl(entry: CatalogEntry): String = baseUrl + entry.packageFile

    /** Adresse des Katalogs im Release. */
    fun catalogUrl(): String = baseUrl + CATALOG_FILE

    fun toJson(): String = buildJsonObject {
        put("format", FORMAT)
        put("baseUrl", baseUrl)
        put("generatedAtMs", generatedAtMs)
        put("entries", buildJsonArray { entries.forEach { add(it.toJson()) } })
    }.let { PRETTY.encodeToString(JsonObject.serializer(), it) }

    /**
     * Vereinigt den beigelegten Katalog (`this`) mit dem aus dem Release
     * ([remote]): Eintraege des Release gewinnen (neuere Groesse, Pruefsumme,
     * Version), neue Eintraege kommen dazu, die Reihenfolge des beigelegten
     * bleibt vorn. Die Adresse bleibt die beigelegte — ein heruntergeladener
     * Katalog kann den Ort der Pakete nicht umlenken.
     */
    fun mergedWith(remote: IndoorCatalog?): IndoorCatalog {
        if (remote == null) return this
        val byId = remote.entries.associateBy { it.id }
        val merged = entries.map { byId[it.id] ?: it } + remote.entries.filter { r -> entries.none { it.id == r.id } }
        return copy(entries = merged, generatedAtMs = maxOf(generatedAtMs, remote.generatedAtMs))
    }

    companion object {
        const val FORMAT: Int = 1

        /** Dateiname des Katalogs im Release und als Asset der App (`indoor/catalog.json`). */
        const val CATALOG_FILE: String = "catalog.json"

        /** Festes Release-Tag der Pakete. */
        const val RELEASE_TAG: String = "indoor-worlds-v1"

        /** Vorgabe-Adresse der Release-Assets. */
        const val DEFAULT_BASE_URL: String =
            "https://github.com/robinrehbein/trailscape/releases/download/$RELEASE_TAG/"

        private val PRETTY = Json { prettyPrint = true }

        /**
         * Liest einen Katalog. Wirft [IllegalArgumentException] mit deutschem
         * Diagnosetext bei kaputtem JSON oder unbekanntem Format. Einzelne
         * unlesbare Eintraege werden uebersprungen (ein neuerer Katalog mit
         * Feldern, die diese Version nicht kennt, soll die Liste nicht leeren).
         */
        fun parse(text: String): IndoorCatalog = try {
            val o = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("Katalog: kein JSON-Objekt")
            val format = o.requiredInt("format")
            require(format == FORMAT) { "Katalog: Format $format wird nicht unterstuetzt (erwartet $FORMAT)" }
            val base = o.optionalString("baseUrl") ?: DEFAULT_BASE_URL
            require(isAllowedCatalogUrl(base) && base.endsWith("/")) { "Katalog: unzulaessige Adresse $base" }
            val entries = (o["entries"] as? JsonArray).orEmpty().mapNotNull { e ->
                runCatching { CatalogEntry.fromJson(e as JsonObject) }.getOrNull()
            }.distinctBy { it.id }
            IndoorCatalog(base, o.optionalLong("generatedAtMs") ?: 0L, entries)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Katalog unlesbar: ${e.message}", e)
        }
    }
}

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
private val SAFE_ID = Regex("^[a-z0-9][a-z0-9._-]{0,79}$")

/** Kennungen, Paket- und Verzeichnisnamen: klein, ohne Trenner, ohne `..`. */
fun isSafeCatalogId(id: String): Boolean = SAFE_ID.matches(id) && !id.contains("..")

/**
 * Hosts, mit denen die App beim Laden eines Katalogpakets spricht: das Release
 * auf `github.com` und die Weiterleitung auf den Speicher der Release-Assets.
 * Geprueft am 23.11.2026: `github.com/…/releases/download/…` antwortet mit
 * `302` auf `release-assets.githubusercontent.com` (frueher
 * `objects.githubusercontent.com`); dort gehen `Range`-Anfragen (`206`).
 */
val CATALOG_HOSTS: Set<String> = setOf(
    "github.com",
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
)

/**
 * Positivliste fuer den Katalog-Abruf: nur `https`, ohne Benutzerangabe und
 * abweichenden Port, nur [CATALOG_HOSTS]; auf `github.com` nur unter
 * `/robinrehbein/trailscape/releases/download/`. Jede Weiterleitung wird
 * erneut geprueft.
 */
fun isAllowedCatalogUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
    val host = uri.host?.lowercase() ?: return false
    if (host !in CATALOG_HOSTS) return false
    if (host == "github.com") return uri.path?.startsWith("/robinrehbein/trailscape/releases/download/") == true
    return true
}
