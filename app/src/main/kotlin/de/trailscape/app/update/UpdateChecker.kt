package de.trailscape.app.update

import de.trailscape.core.HttpClient
import de.trailscape.core.HttpMethod
import de.trailscape.core.HttpRequest
import de.trailscape.core.KeyValueStore

/** Ergebnis einer Update-Pruefung. */
sealed interface UpdateCheckResult {
    /** Es gibt eine neuere Version als die installierte. */
    data class Available(val versionName: String, val runNumber: Int) : UpdateCheckResult

    /** Die installierte Version ist die neueste veroeffentlichte. */
    data object UpToDate : UpdateCheckResult

    /** Die Drosselung hat den Netzzugriff verhindert (siehe [UPDATE_CHECK_INTERVAL_MS]). */
    data object Skipped : UpdateCheckResult

    /**
     * Die Pruefung ist gescheitert — offline, GitHub nicht erreichbar,
     * unlesbare Antwort. **Kein Fehlerfall fuer die Nutzerin:** Offline ist
     * der Normalzustand einer Fahrrad-App unterwegs.
     */
    data object Failed : UpdateCheckResult
}

/**
 * Was die Oberflaeche nach dem stillen Start-Check anzeigen soll.
 *
 * Zwei getrennte Felder, weil Karte und Snackbar unterschiedlich lange
 * leben: Die Karte im Mehr-Tab steht, bis die Nutzerin sie wegwischt
 * ([noticeVersion]); die Snackbar erscheint **einmal** pro entdeckter Version
 * ([announceVersion] ist danach `null`).
 */
data class StartupUpdate(
    val noticeVersion: String? = null,
    val announceVersion: String? = null,
)

/**
 * Der Update-Kanal der App: fragt die veroeffentlichten GitHub-Releases ab
 * und vergleicht die hoechste dort gefundene Lauf-Nummer mit der der
 * installierten APK.
 *
 * Diese App wird als APK per Sideload verteilt — es gibt keinen Store, der
 * von sich aus aktualisiert. Ohne diese Pruefung erfaehrt niemand je von
 * einer neuen Version.
 *
 * ## Regeln
 *  * **Blockierend, aber nie auf dem Main-Thread.** Alle Methoden hier rufen
 *    [HttpClient.execute] und [KeyValueStore] synchron auf (wie ueberall in
 *    `:core`); der Aufrufer bringt sie auf `Dispatchers.IO` — siehe
 *    `AppViewModel`.
 *  * **Still.** Kein Aufruf wirft. Netzwerkfehler, HTTP-Fehlerstatus und
 *    kaputtes JSON enden allesamt in [UpdateCheckResult.Failed].
 *  * **Sparsam.** Der Start-Check greift hoechstens alle
 *    [UPDATE_CHECK_INTERVAL_MS] wirklich aufs Netz zu; der Zeitstempel wird
 *    nur nach einer *erfolgreichen* Abfrage fortgeschrieben, damit ein
 *    Start ohne Empfang die Pruefung nicht fuer einen ganzen Tag verbraucht.
 *
 * @param installedRunNumber die Lauf-Nummer der laufenden App
 *   ([runNumberFromVersionCode] auf dem `versionCode` des PackageManagers);
 *   `null`, wenn sie sich nicht ermitteln laesst — dann wird gar nicht
 *   geprueft, statt gegen einen geratenen Wert zu vergleichen.
 */
class UpdateChecker(
    private val httpClient: HttpClient,
    private val store: KeyValueStore,
    private val installedRunNumber: () -> Int?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Der stille Check beim App-Start: erst der gespeicherte Stand (damit die
     * Karte auch ohne Netz sofort steht), dann — falls faellig — eine
     * Abfrage.
     *
     * Vermerkt die angekuendigte Version gleich mit; ein zweiter Aufruf
     * liefert fuer dieselbe Version [StartupUpdate.announceVersion] `null`.
     */
    fun startupCheck(): StartupUpdate {
        val fresh = check(force = false)
        // Bei Skipped/Failed zaehlt, was beim letzten erfolgreichen Lauf
        // herauskam — sonst verschwaende ein Start ohne Empfang den Hinweis.
        val effective = when (fresh) {
            is UpdateCheckResult.Available, UpdateCheckResult.UpToDate -> fresh
            else -> cachedResult()
        }
        val available = effective as? UpdateCheckResult.Available ?: return StartupUpdate()

        val notice = available.versionName.takeIf { read(DISMISSED_KEY) != it }
        val announce = available.versionName
            .takeIf { shouldAnnounce(read(ANNOUNCED_KEY), it) }
        if (announce != null) write(ANNOUNCED_KEY, announce)
        return StartupUpdate(noticeVersion = notice, announceVersion = announce)
    }

    /**
     * Die manuelle Pruefung („Mehr → Über → Nach Updates suchen"): immer mit
     * Netzzugriff, Ergebnis fuer die Anzeige.
     *
     * Wer von Hand prueft, will die Antwort sehen — deshalb wird eine
     * gefundene Version wieder eingeblendet, auch wenn ihre Karte schon
     * einmal weggewischt war, und sie gilt als angekuendigt (die Nutzerin
     * hat sie ja gerade gelesen).
     */
    fun checkNow(): UpdateCheckResult {
        val result = check(force = true)
        if (result is UpdateCheckResult.Available) {
            remove(DISMISSED_KEY)
            write(ANNOUNCED_KEY, result.versionName)
        }
        return result
    }

    /**
     * Blendet die Hinweis-Karte fuer genau diese Version aus — dauerhaft, bis
     * eine neuere erscheint. Gilt zugleich als „gesehen", damit fuer sie auch
     * keine Snackbar mehr kommt.
     */
    fun dismiss(versionName: String) {
        write(DISMISSED_KEY, versionName)
        write(ANNOUNCED_KEY, versionName)
    }

    /**
     * Eine Pruefung; [force] uebergeht die Drosselung.
     *
     * Ohne [force] und innerhalb des Intervalls kommt [UpdateCheckResult.Skipped]
     * zurueck, **ohne** dass eine Anfrage gestellt wird.
     */
    fun check(force: Boolean = false): UpdateCheckResult {
        val installed = installedRunNumber() ?: return UpdateCheckResult.Failed
        if (!force && !shouldCheckNow(readLong(LAST_CHECK_KEY), nowMs())) {
            return UpdateCheckResult.Skipped
        }

        val response = runCatching {
            httpClient.execute(
                HttpRequest(
                    method = HttpMethod.GET,
                    url = RELEASES_API_URL,
                    headers = mapOf(
                        // Ohne User-Agent antwortet GitHub mit 403.
                        "User-Agent" to UPDATE_USER_AGENT,
                        "Accept" to "application/vnd.github+json",
                    ),
                ),
            )
        }.getOrNull() ?: return UpdateCheckResult.Failed

        if (response.statusCode !in 200..299) return UpdateCheckResult.Failed
        val newest = newestRunNumber(response.body) ?: return UpdateCheckResult.Failed

        // Erst jetzt, nach einer verwertbaren Antwort: Ein Fehlversuch soll
        // die naechste Pruefung nicht um 24 Stunden verschieben.
        write(LAST_CHECK_KEY, nowMs().toString())
        write(LATEST_KNOWN_KEY, newest.toString())
        return resultFor(installed = installed, newest = newest)
    }

    /**
     * Das Ergebnis aus dem gespeicherten Stand — ohne Netzzugriff. Wird
     * gebraucht, wenn die Drosselung greift oder die Abfrage scheitert.
     */
    fun cachedResult(): UpdateCheckResult {
        val installed = installedRunNumber() ?: return UpdateCheckResult.Failed
        val newest = readLong(LATEST_KNOWN_KEY)?.toInt() ?: return UpdateCheckResult.Failed
        return resultFor(installed = installed, newest = newest)
    }

    private fun resultFor(installed: Int, newest: Int): UpdateCheckResult =
        if (newest > installed) {
            UpdateCheckResult.Available(versionNameForRun(newest), newest)
        } else {
            UpdateCheckResult.UpToDate
        }

    // SharedPreferences wirft im Normalbetrieb nicht; `runCatching` deckt den
    // Rest ab (defekte Datei, Fake-Store in Tests) — der Update-Hinweis ist
    // nichts, wofuer die App abstuerzen darf.
    private fun read(key: String): String? = runCatching { store.getString(key) }.getOrNull()

    private fun readLong(key: String): Long? = read(key)?.toLongOrNull()

    private fun write(key: String, value: String) {
        runCatching { store.setString(key, value) }
    }

    private fun remove(key: String) {
        runCatching { store.remove(key) }
    }

    private companion object {
        // Gleicher `trailscape.*`-Namensraum wie alle uebrigen Schluessel
        // (siehe `data/PrefsStores.kt`).

        /** Zeitstempel (ms) der letzten *erfolgreichen* Abfrage. */
        const val LAST_CHECK_KEY = "trailscape.update.lastCheckAt"

        /** Hoechste je gesehene Lauf-Nummer — der Stand fuer den Offline-Fall. */
        const val LATEST_KNOWN_KEY = "trailscape.update.latestKnown"

        /** Version, zu der die Snackbar schon lief. */
        const val ANNOUNCED_KEY = "trailscape.update.announced"

        /** Version, deren Hinweis-Karte weggewischt wurde. */
        const val DISMISSED_KEY = "trailscape.update.dismissed"
    }
}
