package de.trailscape.core.indoor.catalog

import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.optionalString
import de.trailscape.core.requiredInt
import de.trailscape.core.requiredLong
import de.trailscape.core.requiredString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # Paketformat einer Katalogwelt
 *
 * Ein Paket ist ein ZIP mit genau diesem Inhalt (Reihenfolge fest):
 *
 * ```
 * package.json            {format, id, version, worldId, files:[{path, bytes, sha256}]}
 * manifest.json           WorldManifest der Welt
 * tiles/<name>.glb …      alle Kacheln, sortiert
 * ```
 *
 * Der Bau ist **stabil**: gleiche Welt-Dateien → bytegleiches Paket (feste
 * Zeitstempel, sortierte Eintraege, gleiche Kompression). Was nicht im
 * Paket steht, nimmt die App nicht an; jede Datei wird beim Entpacken gegen
 * ihre Pruefsumme aus `package.json` geprueft, das ganze Paket vorher gegen
 * [CatalogEntry.sha256].
 */
data class CatalogPackageFile(val path: String, val bytes: Long, val sha256: String)

/** Inhalt von `package.json`. */
data class CatalogPackageInfo(
    val id: String,
    val version: Int,
    val worldId: String,
    val files: List<CatalogPackageFile>,
) {
    fun toJson(): String = buildJsonObject {
        put("format", FORMAT)
        put("id", id)
        put("version", version)
        put("worldId", worldId)
        put(
            "files",
            buildJsonArray {
                files.forEach { f ->
                    add(
                        buildJsonObject {
                            put("path", f.path)
                            put("bytes", f.bytes)
                            put("sha256", f.sha256)
                        },
                    )
                }
            },
        )
    }.toString()

    companion object {
        const val FORMAT: Int = 1
        const val FILE_NAME: String = "package.json"

        fun parse(text: String): CatalogPackageInfo {
            val o = Json.parseToJsonElement(text) as? JsonObject ?: throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "package.json ist kein Objekt")
            val format = o.requiredInt("format")
            if (format != FORMAT) throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "Paketformat $format unbekannt")
            val files = (o["files"] as? JsonArray).orEmpty().map {
                val f = it as JsonObject
                CatalogPackageFile(f.requiredString("path"), f.requiredLong("bytes"), f.requiredString("sha256").lowercase())
            }
            return CatalogPackageInfo(o.requiredString("id"), o.requiredInt("version"), o.optionalString("worldId") ?: "", files)
        }
    }
}

/** Ein Paket ist unbrauchbar. [reason] waehlt den Text der Oberflaeche. */
class CatalogPackageException(val reason: Reason, detail: String, cause: Throwable? = null) :
    IOException("Katalogpaket: $detail", cause) {
    enum class Reason {
        /** Pruefsumme oder Groesse stimmt nicht (Paket oder einzelne Datei). */
        CHECKSUM,

        /** Aufbau falsch: fehlende/fremde Dateien, unzulaessige Pfade, Manifest unlesbar. */
        INVALID,

        /** Schreiben auf der Platte scheiterte (Platz). */
        IO,
    }
}

/** Fester Zeitstempel aller Eintraege (1.1.2020, lokale Zeit des ZIP-Formats). */
private const val FIXED_ZIP_TIME_MS = 1_577_836_800_000L

/** Nur diese Pfade duerfen in einem Paket stehen. */
private val ALLOWED_PATH = Regex("^(package\\.json|manifest\\.json|tiles/[A-Za-z0-9_.-]{1,120}\\.glb)$")

fun isAllowedPackagePath(path: String): Boolean = ALLOWED_PATH.matches(path) && !path.contains("..")

/** SHA-256 einer Datei, klein hex. */
fun sha256Hex(file: File): String = FileInputStream(file).use { sha256Hex(it) }

fun sha256Hex(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Packt die fertige Welt [worldDir] (mit `manifest.json` und `tiles/`) nach
 * [outFile]. Andere Dateien der Welt (`build/`, Bilder) bleiben draussen.
 * Liefert die `package.json`-Angaben.
 */
fun writeCatalogPackage(worldDir: File, id: String, version: Int, outFile: File): CatalogPackageInfo {
    require(isSafeCatalogId(id)) { "unzulaessige Kennung $id" }
    val manifestFile = File(worldDir, WorldManifest.FILE_NAME)
    val manifest = WorldManifest.parse(manifestFile.readText(Charsets.UTF_8))
    val tiles = File(worldDir, WorldManifest.TILES_DIR).listFiles { f -> f.isFile && f.name.endsWith(".glb") }.orEmpty()
        .sortedBy { it.name }
    val files = buildList {
        add(WorldManifest.FILE_NAME to manifestFile)
        tiles.forEach { add("${WorldManifest.TILES_DIR}/${it.name}" to it) }
    }
    files.forEach { (path, _) -> require(isAllowedPackagePath(path)) { "unzulaessiger Pfad im Paket: $path" } }
    val info = CatalogPackageInfo(
        id = id,
        version = version,
        worldId = manifest.worldId,
        files = files.map { (path, f) -> CatalogPackageFile(path, f.length(), sha256Hex(f)) },
    )
    outFile.parentFile?.mkdirs()
    val tmp = File(outFile.parentFile, outFile.name + ".tmp")
    ZipOutputStream(FileOutputStream(tmp).buffered()).use { zip ->
        zip.setLevel(6)
        fun entry(path: String, bytes: ByteArray? = null, file: File? = null) {
            val e = ZipEntry(path)
            e.time = FIXED_ZIP_TIME_MS
            e.method = ZipEntry.DEFLATED
            zip.putNextEntry(e)
            if (bytes != null) zip.write(bytes) else FileInputStream(file!!).use { it.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
        }
        entry(CatalogPackageInfo.FILE_NAME, bytes = info.toJson().toByteArray(Charsets.UTF_8))
        files.forEach { (path, f) -> entry(path, file = f) }
    }
    if (outFile.exists()) outFile.delete()
    if (!tmp.renameTo(outFile)) throw IOException("Paket nicht umbenennbar: $outFile")
    return info
}

/**
 * Prueft und entpackt [zipFile] nach [destDir] (wird neu angelegt; ein
 * vorhandenes Verzeichnis wird vorher geleert). Erwartet werden Kennung
 * [expectedId] und — falls angegeben — Version [expectedVersion] und
 * [expectedWorldId]. Jede Datei wird gegen `package.json` geprueft; fremde
 * oder fehlende Dateien, unsichere Pfade und ein unlesbares Manifest lassen
 * das Entpacken scheitern ([CatalogPackageException]). Nach einem Fehler ist
 * [destDir] wieder geloescht.
 *
 * @param isCancelled zwischen den Dateien gefragt; `true` bricht mit
 *   [java.util.concurrent.CancellationException] ab.
 */
fun extractCatalogPackage(
    zipFile: File,
    destDir: File,
    expectedId: String,
    expectedVersion: Int? = null,
    expectedWorldId: String? = null,
    isCancelled: () -> Boolean = { false },
): CatalogPackageInfo {
    deleteTree(destDir)
    try {
        val zip = try {
            ZipFile(zipFile)
        } catch (e: IOException) {
            throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "kein lesbares ZIP", e)
        }
        return zip.use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            names.forEach { n ->
                if (!isAllowedPackagePath(n)) throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "unzulaessiger Pfad: $n")
            }
            if (names.toSet().size != names.size) throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "doppelte Eintraege")
            val infoEntry = z.getEntry(CatalogPackageInfo.FILE_NAME)
                ?: throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "package.json fehlt")
            val info = try {
                CatalogPackageInfo.parse(z.getInputStream(infoEntry).use { it.readBytes().toString(Charsets.UTF_8) })
            } catch (e: CatalogPackageException) {
                throw e
            } catch (e: Exception) {
                throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "package.json unlesbar", e)
            }
            if (info.id != expectedId) throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "Paket ist ${info.id}, erwartet $expectedId")
            if (expectedVersion != null && info.version != expectedVersion) {
                throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "Paket hat Version ${info.version}, erwartet $expectedVersion")
            }
            if (expectedWorldId != null && info.worldId != expectedWorldId) {
                throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "Paket hat Welt ${info.worldId}, erwartet $expectedWorldId")
            }
            val listed = info.files.map { it.path }
            if (listed.toSet() != names.toSet() - CatalogPackageInfo.FILE_NAME || WorldManifest.FILE_NAME !in listed) {
                throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "Dateiliste passt nicht zum Inhalt")
            }
            listed.forEach { if (!isAllowedPackagePath(it)) throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "unzulaessiger Pfad: $it") }
            destDir.mkdirs()
            val root = destDir.canonicalFile
            for (f in info.files) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Entpacken abgebrochen")
                val target = File(root, f.path)
                if (!target.canonicalFile.toPath().startsWith(root.toPath())) {
                    throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "Pfad ausserhalb: ${f.path}")
                }
                target.parentFile?.mkdirs()
                val md = MessageDigest.getInstance("SHA-256")
                var count = 0L
                try {
                    z.getInputStream(z.getEntry(f.path)).use { input ->
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                md.update(buf, 0, n)
                                out.write(buf, 0, n)
                                count += n
                                if (count > f.bytes) break
                            }
                        }
                    }
                } catch (e: java.util.zip.ZipException) {
                    throw CatalogPackageException(CatalogPackageException.Reason.CHECKSUM, "Eintrag ${f.path} beschaedigt", e)
                } catch (e: IOException) {
                    throw CatalogPackageException(CatalogPackageException.Reason.IO, "Schreiben von ${f.path} scheiterte: ${e.message}", e)
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (count != f.bytes || sha != f.sha256) {
                    throw CatalogPackageException(CatalogPackageException.Reason.CHECKSUM, "Pruefsumme von ${f.path} stimmt nicht")
                }
            }
            val manifest = try {
                WorldManifest.parse(File(root, WorldManifest.FILE_NAME).readText(Charsets.UTF_8))
            } catch (e: Exception) {
                throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "Manifest unlesbar", e)
            }
            if (manifest.worldId != info.worldId) throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "worldId im Manifest passt nicht")
            val missing = manifest.tiles.map { "${WorldManifest.TILES_DIR}/${it.file.substringAfterLast('/')}" }.filter { it !in listed }
            if (missing.isNotEmpty()) throw CatalogPackageException(CatalogPackageException.Reason.INVALID, "Kacheln fehlen: ${missing.take(3)}")
            info
        }
    } catch (e: Throwable) {
        deleteTree(destDir)
        throw e
    }
}

/** Loescht rekursiv, ohne Symlinks zu folgen. */
internal fun deleteTree(f: File) {
    if (!f.exists() && !java.nio.file.Files.isSymbolicLink(f.toPath())) return
    if (!java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
    f.delete()
}
