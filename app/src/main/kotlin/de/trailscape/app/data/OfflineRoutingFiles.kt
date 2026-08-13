package de.trailscape.app.data

import android.content.Context
import de.trailscape.core.RouteProfile
import de.trailscape.core.gravelProfileText
import de.trailscape.core.offlineBrouterProfile
import java.io.File

/**
 * Stellt die Dateien bereit, die die eingebettete BRouter-Engine zum Starten
 * braucht: ein Routing-Profil je Fahrmodus und die Merkmalstabelle
 * `lookups.dat`.
 *
 * ## Warum ueberhaupt Dateien?
 *
 * BRouter liest beides ueber `java.io.File` — `ProfileCache.parseProfile`
 * oeffnet das Profil per Pfad und daneben **fest** `new File(profileDir,
 * "lookups.dat")`. Aus einem Android-Asset heraus geht das nicht (Assets
 * liegen komprimiert im APK und haben keinen Dateipfad), also muessen sie
 * beim ersten Bedarf in den App-Speicher ausgepackt werden. Und weil
 * `ProfileCache` das Nachbarschaftsverhaeltnis fest verdrahtet, landen sie
 * zwingend im **selben** Verzeichnis.
 *
 * ## Woher die Profile kommen — und warum aus zwei Quellen
 *
 * Das sieht nach einer Ungereimtheit aus, ist aber genau umgekehrt: Beide
 * Wege fuehren zur einzigen Wahrheit, die es fuer das jeweilige Profil gibt.
 *
 *  * **`gravel.brf`** (Fahrmodus „Schotter & Kieswege") liegt seit der
 *    Server-Zeit als Textblob `de.trailscape.core.GRAVEL_BRF` in `:core` und
 *    wird von dort auch auf brouter.de **hochgeladen** (siehe `Routing.kt`).
 *    Ein Abzug derselben Datei unter `assets/` waere eine zweite Wahrheit —
 *    und schlimmer: online und offline wuerden dann unterschiedliche Strecken
 *    liefern, ohne dass es jemandem auffiele. Es wird deshalb aus
 *    [gravelProfileText] herausgeschrieben.
 *  * **Alle anderen Profile** haben in `:core` kein Gegenstueck: Online
 *    benutzt die App dafuer die **serverseitigen** Profile (`trekking`,
 *    `fastbike`, `shortest`), es gibt also nichts zu verdoppeln. Offline
 *    kommen sie aus dem gepinnten BRouter-Submodul und werden beim Bauen in
 *    die Assets kopiert (siehe `stageBrouterAssets` in `app/build.gradle.kts`)
 *    — kein eingecheckter Abzug, sondern der Upstream-Stand.
 *
 * `lookups.dat` ist eine Binaerdatei ohne Gegenstueck im Quellcode und gehoert
 * zur Engine-Version; sie kommt auf demselben Weg aus dem Submodul.
 *
 * ## Fahrmodi ohne Offline-Profil
 *
 * „Radwege bevorzugt" laeuft online auf dem Server-Profil `safety`, das es im
 * Engine-Repo nicht gibt. [profileFile] liefert dafuer `null`, und das Routing
 * faellt sauber auf den Server zurueck (siehe
 * `de.trailscape.core.offlineBrouterProfile`).
 *
 * ## Wann wird neu ausgepackt?
 *
 * Immer dann, wenn eine Datei fehlt oder in der Groesse nicht mehr passt. Ein
 * App-Update kann sowohl ein neues `lookups.dat` (neue Engine) als auch ein
 * geaendertes Profil mitbringen; die Dateien sind einzeln unter 40 KB, ein
 * Groessenvergleich ist billiger als jede Buchfuehrung darueber.
 */
object OfflineRoutingFiles {

    /** Dateiname der Merkmalstabelle. Von BRouter fest vorgegeben. */
    private const val LOOKUPS_NAME = "lookups.dat"

    /**
     * Unterverzeichnis der Assets, in das `app/build.gradle.kts` das Beipack
     * aus dem Submodul kopiert.
     */
    private const val ASSET_DIR = "brouter"

    /**
     * Unterverzeichnis im App-Speicher. Bewusst getrennt von den Kacheln
     * (`segments/`): Profile und Merkmalstabelle sind winzig und gehoeren der
     * App, die Kacheln sind hunderte MB und gehoeren dem Nutzer — die will man
     * loeschen koennen, ohne das Routing lahmzulegen.
     */
    private const val DIR_NAME = "brouter"

    /**
     * Legt das Profil fuer [profile] samt `lookups.dat` bei Bedarf an und
     * liefert die Profildatei — genau das, was
     * `de.trailscape.core.routeOffline` als `profileFile` erwartet.
     *
     * `null`, wenn dieser Fahrmodus offline nicht abgedeckt ist; der Aufrufer
     * routet dann ueber den Server.
     *
     * Greift auf das Dateisystem zu und gehoert deshalb nicht auf den
     * Hauptthread.
     */
    fun profileFile(context: Context, profile: RouteProfile): File? {
        val name = offlineBrouterProfile(profile) ?: return null

        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()

        val freshUntilMs = appUpdateTimeMs(context)

        val lookups = File(dir, LOOKUPS_NAME)
        if (isStale(lookups, freshUntilMs)) {
            copyAsset(context, LOOKUPS_NAME, lookups)
        }

        val file = File(dir, name)
        if (name == GRAVEL_PROFILE_NAME) {
            // Der Blob aus `:core` — eine Zeichenkette, kein Asset.
            val text = gravelProfileText()
            if (isStale(file, freshUntilMs) || file.length() != text.toByteArray().size.toLong()) {
                file.writeText(text)
            }
        } else if (isStale(file, freshUntilMs)) {
            copyAsset(context, name, file)
        }
        return file
    }

    /**
     * Das Verzeichnis, in dem die `*.rd5`-Kacheln liegen.
     *
     * Bewusst `filesDir` und nicht `cacheDir`: Kacheln sind grosse, teuer
     * beschaffte Nutzdaten — Android darf sie nicht bei Speicherdruck
     * wegraeumen. Befuellt wird es von `routing/SegmentDownloader.kt`.
     */
    fun segmentDir(context: Context): File = File(context.filesDir, "segments")

    /**
     * Der Name des einen Profils, das **nicht** aus den Assets kommt (siehe
     * Klassen-KDoc). Muss zu `offlineBrouterProfile(RouteProfile.SCHOTTER)`
     * passen; ein `:core`-Test haelt die Zuordnung fest.
     */
    private const val GRAVEL_PROFILE_NAME = "gravel.brf"

    private fun copyAsset(context: Context, name: String, target: File) {
        // Erst daneben schreiben, dann umbenennen: Ein Abbruch mitten im
        // Kopieren darf keine halbe Profildatei hinterlassen, die die Engine
        // beim naechsten Start als „vorhanden" ansaehe.
        val temp = File(target.parentFile, target.name + ".tmp")
        context.assets.open("$ASSET_DIR/$name").use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Konnte $name nicht in den App-Speicher schreiben." }
        }
    }

    /**
     * Ob eine ausgepackte Datei neu geschrieben werden muss: wenn sie fehlt,
     * leer ist — oder aelter als die installierte APK.
     *
     * Der Zeitvergleich ist der Ersatz fuer eine Buchfuehrung darueber, welche
     * App-Version welche Datei geschrieben hat. Ein Update kann ein neues
     * `lookups.dat` (neue Engine) oder ein geaendertes Profil mitbringen; alles
     * vor dem Update Ausgepackte ist danach verdaechtig und wird einmal
     * erneuert. Ist der Zeitpunkt nicht zu ermitteln, gilt eine vorhandene,
     * nicht leere Datei als gut — lieber ein altes Profil als gar kein
     * Offline-Routing.
     */
    private fun isStale(file: File, appUpdateTimeMs: Long): Boolean =
        !file.isFile || file.length() == 0L || file.lastModified() < appUpdateTimeMs

    private fun appUpdateTimeMs(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
}
