package de.trailscape.app

import android.app.Application
import de.trailscape.app.data.AppServices

/**
 * Application-Klasse einzig zu dem Zweck, [AppServices] mit einem
 * `Context` zu initialisieren, bevor irgendeine Activity/ViewModel darauf
 * zugreift. Enthaelt bewusst sonst nichts — kein globaler Zustand ausserhalb
 * von [AppServices].
 */
class TrailscapeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)
    }
}
