package de.trailscape.app.indoor.world

import de.trailscape.app.ui.indoor.IndoorWorldEntryUi
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldManifest
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Die Welten auf dem Geraet und der Zwischenspeicher der Geodaten
 * (docs/indoor.md, Abschnitte 8.4 und 10.4).
 *
 * Fertige Welten liegen unter `<filesDir>/indoor/worlds/<worldId>/`, der
 * Geodaten-Cache unter `<cacheDir>/indoor/geo/`. Neben dem `manifest.json` des
 * Weltbaus legt die App je Welt eine kleine Datei [SOURCE_FILE]
 * (`app-source.json`) mit `{rideId, routeName}` ab: Die `routeId` im Manifest
 * ist ein Hash der Geometrie und sagt nichts darueber, ob die Tour noch
 * existiert — die `rideId` schon. Eine Welt ist **verwaist**, wenn ihre
 * `rideId` nicht mehr unter den Touren vorkommt; verwaiste Welten werden zum
 * Loeschen angeboten, nie stillschweigend geloescht. Die Testwelt
 * ([TEST_WORLD_ID]) ist nie verwaist.
 *
 * Alle Dateizugriffe blockieren und gehoeren auf `Dispatchers.IO`.
 *
 * @param worldsDir `<filesDir>/indoor/worlds`
 * @param geoCacheDir `<cacheDir>/indoor/geo`
 */
class WorldLibrary(private val worldsDir: File, private val geoCacheDir: File) {

    /**
     * Eine Welt (oder angefangene Welt) auf der Platte.
     *
     * @property dirName Verzeichnisname = `worldId`.
     * @property manifest `null` bei einer angefangenen Welt (ohne `manifest.json`)
     *   oder wenn das Manifest unlesbar ist.
     * @property source die App-Zuordnung zur Tour, `null` wenn nicht vorhanden.
     */
    data class WorldInfo(
        val dirName: String,
        val dir: File,
        val manifest: WorldManifest?,
        val source: WorldSource?,
        val bytes: Long,
    ) {
        /** `manifest.json` liegt vor und ist lesbar. */
        val complete: Boolean get() = manifest != null
        val isTestWorld: Boolean get() = dirName == TEST_WORLD_ID
    }

    /** Zuordnung einer Welt zur Tour, aus der sie gebaut wurde. */
    data class WorldSource(val rideId: String, val routeName: String)

    /** Verzeichnis der Welt [worldId]; wirft bei einer unzulaessigen Kennung. */
    fun worldDirFor(worldId: String): File {
        require(isSafeName(worldId)) { "Unzulaessige Welt-Kennung: $worldId" }
        return File(worldsDir, worldId)
    }

    /** Verzeichnis der Testwelt (noch nicht zwingend vorhanden). */
    fun testWorldDir(): File = File(worldsDir, TEST_WORLD_ID)

    /** Alle Verzeichnisse unter [worldsDir], neueste zuerst (angefangene am Ende). */
    fun listWorlds(): List<WorldInfo> {
        val dirs = worldsDir.listFiles { f -> f.isDirectory && isSafeName(f.name) }.orEmpty()
        return dirs.map { describe(it) }
            .sortedWith(
                compareByDescending<WorldInfo> { it.complete }
                    .thenByDescending { it.manifest?.createdAtMs ?: it.dir.lastModified() }
                    .thenBy { it.dirName },
            )
    }

    /** Die eine Welt [worldId] oder `null`. */
    fun get(worldId: String): WorldInfo? {
        if (!isSafeName(worldId)) return null
        val dir = File(worldsDir, worldId)
        return if (dir.isDirectory) describe(dir) else null
    }

    /** Gesamtgroesse aller Welten in Bytes. */
    fun totalBytes(): Long = dirSize(worldsDir)

    /** Groesse der Welt [worldId] in Bytes, 0 wenn es sie nicht gibt. */
    fun sizeOf(worldId: String): Long {
        if (!isSafeName(worldId)) return 0L
        return dirSize(File(worldsDir, worldId))
    }

    /**
     * Loescht die Welt [worldId] samt Inhalt — nur innerhalb von [worldsDir]
     * (Pfadpruefung auf die kanonische Lage). `true`, wenn danach nichts mehr
     * da ist.
     */
    fun delete(worldId: String): Boolean {
        if (!isSafeName(worldId)) return false
        val dir = File(worldsDir, worldId)
        if (!dir.exists()) return true
        if (!isInside(worldsDir, dir)) return false
        return deleteRecursivelyNoFollow(dir) && !dir.exists()
    }

    /** Groesse des Geodaten-Zwischenspeichers in Bytes. */
    fun cacheBytes(): Long = dirSize(geoCacheDir)

    /** Leert `<cacheDir>/indoor/geo` (das Verzeichnis selbst bleibt). `true` bei Erfolg. */
    fun clearCache(): Boolean {
        if (!geoCacheDir.exists()) return true
        var ok = true
        geoCacheDir.listFiles().orEmpty().forEach { child ->
            if (!deleteRecursivelyNoFollow(child)) ok = false
        }
        return ok
    }

    /**
     * Ob die Welt verwaist ist: Die Zuordnung nennt eine `rideId`, die nicht
     * in [knownRideIds] steht. Ohne Zuordnung und die Testwelt: nie.
     */
    fun isOrphaned(info: WorldInfo, knownRideIds: Set<String>): Boolean {
        if (info.isTestWorld) return false
        val source = info.source ?: return false
        return source.rideId !in knownRideIds
    }

    /**
     * Eine vorhandene **fertige** Welt zur Tour [rideId], optional nur in der
     * Qualitaet [quality]. Bei mehreren die neueste.
     */
    fun findWorldForRide(rideId: String, quality: IndoorQuality? = null): WorldInfo? =
        listWorlds()
            .filter { it.complete && it.source?.rideId == rideId }
            .filter { quality == null || it.manifest?.quality == quality }
            .maxByOrNull { it.manifest?.createdAtMs ?: 0L }

    /** Anzeigeform fuer die Weltverwaltung. */
    fun toEntryUi(info: WorldInfo, knownRideIds: Set<String>): IndoorWorldEntryUi = IndoorWorldEntryUi(
        worldId = info.dirName,
        routeName = info.manifest?.routeName?.takeIf { it.isNotBlank() }
            ?: info.source?.routeName.orEmpty(),
        quality = info.manifest?.quality,
        bytes = info.bytes,
        createdAt = info.manifest?.createdAtMs?.takeIf { it > 0L },
        attribution = info.manifest?.attribution.orEmpty(),
        orphaned = isOrphaned(info, knownRideIds),
        complete = info.complete,
    )

    private fun describe(dir: File): WorldInfo {
        val manifest = runCatching {
            val file = File(dir, WorldManifest.FILE_NAME)
            if (file.isFile) WorldManifest.parse(file.readText(Charsets.UTF_8)) else null
        }.getOrNull()
        return WorldInfo(
            dirName = dir.name,
            dir = dir,
            manifest = manifest,
            source = readSource(dir),
            bytes = dirSize(dir),
        )
    }

    companion object {
        /** Verzeichnisname der Testwelt. */
        const val TEST_WORLD_ID = "testwelt"

        /** Name der Zuordnungsdatei neben dem Manifest. */
        const val SOURCE_FILE = "app-source.json"

        /** Schreibt die Zuordnung Welt → Tour (legt [worldDir] an, falls noetig). */
        fun writeSource(worldDir: File, rideId: String, routeName: String) {
            worldDir.mkdirs()
            val text = buildJsonObject {
                put("rideId", rideId)
                put("routeName", routeName)
            }.toString()
            val target = File(worldDir, SOURCE_FILE)
            val tmp = File(worldDir, "$SOURCE_FILE.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }

        /** Liest die Zuordnung; `null` wenn sie fehlt oder unlesbar ist. */
        fun readSource(worldDir: File): WorldSource? = runCatching {
            val file = File(worldDir, SOURCE_FILE)
            if (!file.isFile) return@runCatching null
            val obj = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
                ?: return@runCatching null
            val rideId = (obj["rideId"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
            val name = (obj["routeName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            WorldSource(rideId, name)
        }.getOrNull()

        /** Ein einzelner Verzeichnisname ohne Trenner und ohne `.`/`..`. */
        private fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." &&
                name.none { it == '/' || it == '\\' || it == '\u0000' }

        private fun isInside(root: File, child: File): Boolean = runCatching {
            val rootPath = root.canonicalFile.toPath()
            val childPath = child.canonicalFile.toPath()
            childPath != rootPath && childPath.startsWith(rootPath)
        }.getOrDefault(false)

        /** Summe der Dateigroessen; Symlinks werden nicht verfolgt. */
        private fun dirSize(dir: File): Long {
            if (!dir.exists()) return 0L
            var sum = 0L
            val stack = ArrayDeque<File>()
            stack.add(dir)
            while (stack.isNotEmpty()) {
                val f = stack.removeLast()
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) {
                    f.listFiles()?.let { stack.addAll(it) }
                } else {
                    sum += f.length()
                }
            }
            return sum
        }

        /** Loescht ohne Symlinks zu folgen (ein Link wird selbst entfernt, sein Ziel bleibt). */
        private fun deleteRecursivelyNoFollow(f: File): Boolean {
            val isLink = java.nio.file.Files.isSymbolicLink(f.toPath())
            if (!isLink && f.isDirectory) {
                f.listFiles()?.forEach { deleteRecursivelyNoFollow(it) }
            }
            return f.delete() || !f.exists()
        }
    }
}
