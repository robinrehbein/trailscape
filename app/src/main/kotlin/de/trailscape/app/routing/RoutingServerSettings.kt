package de.trailscape.app.routing

import de.trailscape.core.KeyValueStore
import de.trailscape.core.defaultBrouterServerUrl

/**
 * Die eine Einstellung fuer einen selbst betriebenen BRouter-Server: die
 * Basis-URL fuer die ROUTENBERECHNUNG.
 *
 * ## Warum das ausdruecklich NICHT die Kachel-Downloads betrifft
 * [SegmentDownloader] und [SegmentDownloadWorker] laden weiterhin
 * ausschliesslich von brouter.de (siehe `brouterSegmentBaseUrl` in
 * `:core`s `RoutingSegments.kt`): Dort liegen die offiziellen `*.rd5`-
 * Kacheln, ein selbst betriebener Routing-Server bietet sie in aller Regel
 * gar nicht an — und ein Server, der sie doch anbietet, waere kein
 * vertrauenswuerdiger Ersatz fuer den offiziellen Kachelstand. Diese
 * Einstellung entscheidet nur, wohin eine Route zur BERECHNUNG geschickt
 * wird (siehe [de.trailscape.core.fetchRoute] bzw.
 * [de.trailscape.core.routeOfflineFirst]).
 *
 * ## Warum leer = brouter.de, statt die Vorgabe ins Feld zu schreiben
 * Ein leeres Feld ist unmissverstaendlich "Vorgabe"; eine vorausgefuellte URL
 * suggeriert dagegen, sie muesste erst geaendert werden. Der eigentliche
 * Vorgabewert ([defaultBrouterServerUrl]) steht deshalb nur im Hinweistext
 * neben dem Feld, nicht im Feld selbst.
 *
 * Liegt auf demselben [KeyValueStore] wie Profil, Kartenstil und Erinnerungen.
 */
class RoutingServerSettings(private val store: KeyValueStore) {

    /**
     * Roh wie zuletzt eingegeben, nur getrimmt — leer heisst "oeffentlicher
     * brouter.de". Ein etwaiger Schluss-Slash bleibt hier unangetastet;
     * [de.trailscape.core.fetchRoute] toleriert ihn beim Aufbau der Anfrage.
     */
    var url: String
        get() = store.getString(KEY_URL)?.trim().orEmpty()
        set(value) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                store.remove(KEY_URL)
            } else {
                store.setString(KEY_URL, trimmed)
            }
        }

    /** Die tatsaechlich zu benutzende Basis-URL: [url], sonst [defaultBrouterServerUrl]. */
    fun effectiveUrl(): String = url.ifEmpty { defaultBrouterServerUrl }

    private companion object {
        const val KEY_URL = "trailscape.routing.serverurl"
    }
}
