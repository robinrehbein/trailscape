package de.trailscape.app

import android.app.Application
import android.content.res.Configuration
import de.trailscape.app.data.AppServices
import de.trailscape.app.feedback.AppDiagnostics
import de.trailscape.app.feedback.CrashReporter
import de.trailscape.app.i18n.AppLocale
import de.trailscape.app.record.RecordingService
import de.trailscape.app.reminder.ReminderScheduler
import kotlinx.coroutines.launch

/**
 * Application-Klasse einzig zu dem Zweck, [AppServices] mit einem
 * `Context` zu initialisieren, bevor irgendeine Activity/ViewModel darauf
 * zugreift, liegengebliebene Aufzeichnungs-Journale eines abgestuerzten
 * Prozesses als Tour zu retten, den lokalen Absturzberichter und das
 * Diagnose-Log einzurichten und den Zeitplan der Erinnerungen wieder auszurichten. Enthaelt bewusst
 * sonst nichts — kein globaler Zustand ausserhalb von [AppServices].
 */
class TrailscapeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Als Allererstes, noch vor AppServices: Ein Fehler beim Hochfahren
        // soll bereits einen Bericht hinterlassen. Der Berichter braucht
        // seinerseits nichts ausser dem Context.
        CrashReporter.install(this)
        // Gleich danach: Ab hier landen Diagnose-Eintraege in der Datei, auch
        // die aus AppServices und der Journal-Wiederherstellung unten.
        AppDiagnostics.install(this)
        // Vor AppServices: Dessen Sprach-Startwert soll eine unter Android 12
        // gespeicherte Wahl schon sehen (siehe AppLocale.migrateLegacyPreference).
        AppLocale.migrateLegacyPreference(this)
        AppServices.init(this)
        AppServices.appScope.launch {
            // Binder- und Datei-I/O, deshalb nicht im onCreate selbst.
            AppDiagnostics.recordExitReasons(this@TrailscapeApplication)
        }
        AppServices.appScope.launch {
            // Wartet von sich aus kurz ab, ob gerade ein Aufzeichnungsdienst
            // hochfaehrt — der hat Vorrang, denn nur er weiss, ob er eine
            // laufende Fahrt fortsetzen will. Siehe `RecoveryGate`.
            RecordingService.recoverIfNeeded(this@TrailscapeApplication, AppServices.rideStorage)
        }
        AppServices.appScope.launch {
            // Nachziehen, was der Hintergrundlauf allein nicht kann: Wurde das
            // Geraet lange nicht eingeschaltet oder die App aus dem
            // Energiesparmodus geworfen, steht der naechste Termin womoeglich
            // in der Vergangenheit. Ist alles abgeschaltet, raeumt derselbe
            // Aufruf die Arbeit ab (siehe [ReminderScheduler.reschedule]).
            ReminderScheduler.reschedule(
                context = this@TrailscapeApplication,
                settings = AppServices.reminderStore.readSettings(),
            )
        }
    }

    /**
     * Wechselt die Systemsprache, waehrend die App im Hintergrund laeuft
     * (Aufzeichnung, Segment-Download, Health-Sync), muss auch
     * [AppServices.coreTexts] umschwenken — sonst mischte ein Dienst die
     * alte Sprache aus dem Zwischenstand mit der neuen aus [de.trailscape.app.i18n.localized].
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.refresh(this)
    }
}
