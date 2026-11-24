package de.trailscape.app.indoor.catalog

import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.core.indoor.catalog.CatalogEntry
import de.trailscape.core.indoor.catalog.IndoorCatalog
import de.trailscape.core.indoor.catalog.extractCatalogPackage
import de.trailscape.core.indoor.catalog.isAllowedCatalogUrl
import de.trailscape.core.indoor.catalog.isSafeCatalogId
import de.trailscape.core.indoor.world.WorldManifest
import java.io.File
import java.io.IOException

/**
 * Katalog bekannter Strecken auf dem Geraet (docs/indoor/catalog.md):
 *
 * * der beigelegte Katalog (Asset `indoor/catalog.json`, ohne Netz),
 * * der zuletzt mit „Katalog aktualisieren" geholte Katalog des Release
 *   (`<filesDir>/indoor/catalog/catalog-remote.json`),
 * * angefangene Downloads (`<filesDir>/indoor/catalog/downloads/<paket>.part`),
 * * das Entpacken (`<filesDir>/indoor/catalog/tmp/<id>`) und Einsortieren in
 *   die Weltverwaltung (`<filesDir>/indoor/worlds/<worldId>/`, mit
 *   [WorldLibrary.CATALOG_FILE]) — danach ist die Welt eine Welt wie jede
 *   andere: fahrbar, in der Weltverwaltung sichtbar und loeschbar.
 *
 * Alle Zugriffe blockieren und gehoeren auf `Dispatchers.IO`.
 *
 * @param bundled liefert den Text des beigelegten Katalogs, `null` = keiner.
 * @param allowUrl Positivliste fuer die Paketadresse eines Katalogs (Tests: lokaler Server).
 */
class CatalogStore(
    private val baseDir: File,
    private val worlds: WorldLibrary,
    private val worldsDir: File,
    private val bundled: () -> String?,
    private val allowUrl: (String) -> Boolean = ::isAllowedCatalogUrl,
) {
    private val remoteFile get() = File(baseDir, "catalog-remote.json")
    private val downloadsDir get() = File(baseDir, "downloads")
    private val tmpDir get() = File(baseDir, "tmp")

    /** Eine installierte Katalogwelt. */
    data class Installed(val catalogId: String, val version: Int, val worldId: String, val dir: File, val manifest: WorldManifest)

    /** Die Weltverwaltung, in die dieser Speicher installiert. */
    fun worldLibrary(): WorldLibrary = worlds

    fun bundledCatalog(): IndoorCatalog? = runCatching { bundled()?.let { IndoorCatalog.parse(it, allowUrl) } }.getOrNull()

    fun remoteCatalog(): IndoorCatalog? = runCatching {
        remoteFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.let { IndoorCatalog.parse(it, allowUrl) }
    }.getOrNull()

    /** Der wirksame Katalog: beigelegt, ergaenzt um den Release-Katalog. */
    fun catalog(): IndoorCatalog {
        val base = bundledCatalog() ?: IndoorCatalog(IndoorCatalog.DEFAULT_BASE_URL, 0, emptyList())
        return base.mergedWith(remoteCatalog())
    }

    /** Merkt den gerade geholten Release-Katalog (wirft bei unlesbarem Text). */
    fun saveRemote(text: String): IndoorCatalog {
        val parsed = IndoorCatalog.parse(text, allowUrl)
        baseDir.mkdirs()
        val tmp = File(baseDir, "catalog-remote.json.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(remoteFile)) {
            remoteFile.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
        return parsed
    }

    /** Installierte Katalogwelten je Kennung (bei mehreren die hoechste Version). */
    fun installed(): Map<String, Installed> = worlds.listWorlds()
        .mapNotNull { info ->
            val marker = info.catalog ?: return@mapNotNull null
            val manifest = info.manifest ?: return@mapNotNull null
            Installed(marker.catalogId, marker.version, info.dirName, info.dir, manifest)
        }
        .groupBy { it.catalogId }
        .mapValues { (_, l) -> l.maxBy { it.version } }

    /** Teildatei des Pakets von [entry]. */
    fun partFile(entry: CatalogEntry): File {
        require(isSafeCatalogId(entry.packageFile.removeSuffix(".zip")))
        return File(downloadsDir, entry.packageFile + ".part")
    }

    /** Schon geladene Bytes eines angefangenen Downloads (0 = keiner). */
    fun partialBytes(entry: CatalogEntry): Long = partFile(entry).let { if (it.isFile) it.length() else 0L }

    /** Verwirft einen angefangenen Download. */
    fun discardPartial(entry: CatalogEntry) {
        partFile(entry).delete()
    }

    /**
     * Entpackt das gepruefte Paket [zip] von [entry] und sortiert es in die
     * Weltverwaltung ein. Aeltere Versionen derselben Strecke werden danach
     * entfernt; die Paketdatei auch. Wirft
     * [de.trailscape.core.indoor.catalog.CatalogPackageException] bzw.
     * [IOException]; dann bleibt die vorherige Version unberuehrt.
     */
    fun install(entry: CatalogEntry, zip: File, isCancelled: () -> Boolean = { false }): Installed {
        val previous = installed()[entry.id]
        val (target, manifest) = installPackage(entry.id, entry.version, entry.worldId, zip, isCancelled) { staging ->
            WorldLibrary.writeCatalogMarker(staging, entry.id, entry.version)
        }
        if (previous != null && previous.worldId != manifest.worldId) worlds.delete(previous.worldId)
        return Installed(entry.id, entry.version, manifest.worldId, target, manifest)
    }

    /**
     * Der gemeinsame Installationsweg fuer Katalog- und Server-Pakete
     * (docs/indoor/serverbau.md): pruefen und entpacken nach `tmp/<id>`,
     * [mark] schreibt die Herkunftsdateien in das entpackte Verzeichnis, dann
     * ersetzt es eine vorhandene Welt derselben `worldId` (z. B. die auf dem
     * Geraet gebaute Welt derselben Strecke). Die Paketdatei wird danach
     * geloescht. Bei einem Fehler bleibt die vorhandene Welt unberuehrt.
     */
    fun installPackage(
        id: String,
        version: Int,
        worldId: String?,
        zip: File,
        isCancelled: () -> Boolean = { false },
        mark: (File) -> Unit,
    ): Pair<File, WorldManifest> {
        val staging = File(tmpDir, id)
        extractCatalogPackage(zip, staging, id, version, worldId, isCancelled)
        mark(staging)
        val manifest = WorldManifest.parse(File(staging, WorldManifest.FILE_NAME).readText(Charsets.UTF_8))
        // Gleiche worldId (Neubau mit unveraenderter Strecke, Geraetewelt derselben Strecke): alte Fassung erst weg.
        worlds.get(manifest.worldId)?.let { if (!worlds.delete(it.dirName)) throw IOException("Alte Welt ${it.dirName} nicht loeschbar") }
        worldsDir.mkdirs()
        val target = worlds.worldDirFor(manifest.worldId)
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        zip.delete()
        return target to manifest
    }

    /** Raeumt Reste abgebrochener Entpack-Vorgaenge weg. */
    fun cleanupStaging() {
        tmpDir.deleteRecursively()
    }
}
