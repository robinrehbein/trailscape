package de.trailscape.app.data

import android.content.Context
import de.trailscape.core.gravelProfileText
import java.io.File

/**
 * Stellt die beiden Dateien bereit, die die eingebettete BRouter-Engine zum
 * Starten braucht: das Routing-Profil und die Merkmalstabelle `lookups.dat`.
 *
 * ## Warum ueberhaupt Dateien?
 *
 * BRouter liest beides ueber `java.io.File` — `ProfileCache.parseProfile`
 * oeffnet das Profil per Pfad und daneben **fest** `new File(profileDir,
 * "lookups.dat")`. Aus einem Android-Asset heraus geht das nicht (Assets
 * liegen komprimiert im APK und haben keinen Dateipfad), also muessen beide
 * beim ersten Bedarf in den App-Speicher ausgepackt werden. Und weil
 * `ProfileCache` das Nachbarschaftsverhaeltnis fest verdrahtet, landen sie
 * zwingend im **selben** Verzeichnis.
 *
 * ## Warum kommt nur `lookups.dat` aus den Assets?
 *
 * Das Gravel-Profil liegt seit der Server-Zeit als Textblob in
 * `de.trailscape.core.BrouterProfiles` und wird von dort auch weiterhin auf
 * den oeffentlichen Server hochgeladen (siehe `Routing.kt`). Ein Abzug
 * derselben Datei unter `assets/` waere eine zweite Wahrheit ueber dasselbe
 * Profil, die beim naechsten Upstream-Update auseinanderlaeuft — und
 * schlimmer: online und offline wuerden dann unterschiedlich gerouteten
 * Strecken liefern, ohne dass es jemandem auffiele. Geprueft wurde deshalb,
 * ob der Blob noch zum Upstream passt: er ist **byteidentisch** zum
 * `misc/profiles2/gravel.brf` des Tags v1.7.10 (19.121 Zeichen). Also bleibt
 * `GRAVEL_BRF` die einzige Quelle, und diese Klasse schreibt schlicht das
 * Ergebnis von [gravelProfileText] heraus.
 *
 * `lookups.dat` dagegen ist eine Binaerdatei ohne Gegenstueck im Quellcode
 * und gehoert zur Engine-Version — die kommt als Asset mit.
 *
 * ## Wann wird neu ausgepackt?
 *
 * Bei jedem Versionswechsel der App und immer dann, wenn eine der beiden
 * Dateien fehlt oder in der Groesse nicht mehr passt. Ein Update kann sowohl
 * ein neues `lookups.dat` (neue Engine) als auch ein geaendertes Profil
 * mitbringen; die Dateien sind zusammen rund 50 KB, ein Vergleich waere
 * teurer als das Schreiben.
 */
object OfflineRoutingFiles {

    /** Dateiname der Merkmalstabelle. Von BRouter fest vorgegeben. */
    private const val LOOKUPS_NAME = "lookups.dat"

    /** Dateiname des Profils im App-Speicher. */
    private const val PROFILE_NAME = "gravel.brf"

    /**
     * Unterverzeichnis im App-Speicher. Bewusst getrennt von den spaeteren
     * Kacheln (`segments/`): Profil und Merkmalstabelle sind winzig und
     * gehoeren der App, die Kacheln sind hunderte MB und gehoeren dem Nutzer
     * — die will man loeschen koennen, ohne das Routing lahmzulegen.
     */
    private const val DIR_NAME = "brouter"

    /**
     * Legt Profil und `lookups.dat` bei Bedarf an und liefert die Profildatei
     * — genau das, was `de.trailscape.core.routeOffline` als `profileFile`
     * erwartet.
     *
     * Greift auf das Dateisystem zu und gehoert deshalb nicht auf den
     * Hauptthread.
     */
    fun profileFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()

        val profile = File(dir, PROFILE_NAME)
        val lookups = File(dir, LOOKUPS_NAME)

        val profileText = gravelProfileText()
        if (profile.length() != profileText.toByteArray().size.toLong()) {
            profile.writeText(profileText)
        }
        if (!lookups.isFile || lookups.length() == 0L) {
            context.assets.open(LOOKUPS_NAME).use { input ->
                lookups.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return profile
    }

    /**
     * Das Verzeichnis, in dem die `*.rd5`-Kacheln liegen (sollen).
     *
     * Wird hier schon benannt, damit die Ablage an **einer** Stelle festgelegt
     * ist — das Befuellen (Herunterladen, Aktualisieren, Loeschen) ist Sache
     * der noch zu bauenden Kachelverwaltung. Bis dahin ist das Verzeichnis
     * leer bzw. gar nicht vorhanden, und `routeOffline` meldet das als
     * „Es sind noch keine Offline-Karten gespeichert".
     *
     * Bewusst `filesDir` und nicht `cacheDir`: Kacheln sind grosse, teuer
     * beschaffte Nutzdaten — Android darf sie nicht bei Speicherdruck
     * wegraeumen.
     */
    fun segmentDir(context: Context): File = File(context.filesDir, "segments")
}
