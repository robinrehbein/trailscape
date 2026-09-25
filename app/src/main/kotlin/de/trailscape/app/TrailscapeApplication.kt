package de.trailscape.app

import android.app.Application
import de.trailscape.app.data.AppServices
import de.trailscape.app.feedback.AppDiagnostics
import de.trailscape.app.feedback.CrashReporter
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
}
