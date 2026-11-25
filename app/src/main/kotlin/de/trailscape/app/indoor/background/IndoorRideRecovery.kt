package de.trailscape.app.indoor.background

import android.content.Context
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.localized

/**
 * Rettet beim Start des Prozesses verwaiste Sicherungen von Indoor-Fahrten
 * (docs/indoor/hintergrund.md): Was der Prozesstod mitten in einer Fahrt
 * zurueckliess, wird zur Tour „… (wiederhergestellt)", und die Stelle wird
 * gemerkt, damit die naechste Fahrt auf dieser Strecke dort weitermachen kann.
 *
 * Aufgerufen aus `TrailscapeApplication.onCreate` (auf IO). Laeuft in diesem
 * Prozess schon eine Fahrt (kann beim Prozessstart nicht sein, aber sicher ist
 * sicher), bleibt ihre Sicherung unberuehrt; beiseitegelegte aeltere werden
 * immer mitgenommen.
 */
object IndoorRideRecovery {

    fun run(context: Context, host: IndoorRideHost = IndoorRideHost.shared) {
        val journal = IndoorRideJournal.inFilesDir(context.filesDir)
        // Erst beiseitelegen, dann lesen: So kann eine eben begonnene Fahrt
        // nie die Datei verlieren, die hier gerade verarbeitet wird.
        if (!host.isRiding) journal.setAside()
        val files = IndoorRideJournal.orphanFiles(journal)
        if (files.isEmpty()) return
        val texts = context.localized()
        for (file in files) {
            val result = runCatching {
                IndoorRideJournal(file).recover(
                    save = { ride -> AppServices.rideStorage.saveRide(ride) },
                    store = AppServices.keyValueStore,
                    nameOf = { name -> texts.getString(R.string.idee05_recovered_name, name) },
                    nowEpochMs = System.currentTimeMillis(),
                )
            }.getOrNull()
            if (result is IndoorRideJournal.Recovery.Saved) {
                host.announceSaved(result.rideId)
                IndoorRideService.postResult(
                    context,
                    texts.getString(R.string.idee05_recovered_title),
                    texts.getString(R.string.idee05_recovered_body, result.name),
                )
            }
        }
    }
}
