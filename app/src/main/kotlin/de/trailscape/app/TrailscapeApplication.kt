package de.trailscape.app

import android.app.Application
import de.trailscape.app.data.AppServices
import de.trailscape.app.feedback.CrashReporter
import de.trailscape.app.record.RecordingService
import kotlinx.coroutines.launch

/**
 * Application-Klasse einzig zu dem Zweck, [AppServices] mit einem
 * `Context` zu initialisieren, bevor irgendeine Activity/ViewModel darauf
 * zugreift, liegengebliebene Aufzeichnungs-Journale eines abgestuerzten
 * Prozesses als Tour zu retten und den lokalen Absturzberichter zu
 * installieren. Enthaelt bewusst sonst nichts — kein globaler Zustand
 * ausserhalb von [AppServices].
 */
class TrailscapeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Als Allererstes, noch vor AppServices: Ein Fehler beim Hochfahren
        // soll bereits einen Bericht hinterlassen. Der Berichter braucht
        // seinerseits nichts ausser dem Context.
        CrashReporter.install(this)
        AppServices.init(this)
        AppServices.appScope.launch {
            RecordingService.recoverIfNeeded(this@TrailscapeApplication, AppServices.rideStorage)
        }
    }
}
