package de.trailscape.app

import android.app.Application
import de.trailscape.app.data.AppServices
import de.trailscape.app.record.RecordingService
import kotlinx.coroutines.launch

/**
 * Application-Klasse einzig zu dem Zweck, [AppServices] mit einem
 * `Context` zu initialisieren, bevor irgendeine Activity/ViewModel darauf
 * zugreift, und liegengebliebene Aufzeichnungs-Journale eines abgestuerzten
 * Prozesses als Tour zu retten. Enthaelt bewusst sonst nichts — kein
 * globaler Zustand ausserhalb von [AppServices].
 */
class TrailscapeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)
        AppServices.appScope.launch {
            RecordingService.recoverIfNeeded(this@TrailscapeApplication, AppServices.rideStorage)
        }
    }
}
