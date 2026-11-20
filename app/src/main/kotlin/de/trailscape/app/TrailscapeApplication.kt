package de.trailscape.app

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import de.trailscape.app.data.AppServices
import de.trailscape.app.feedback.AppDiagnostics
import de.trailscape.app.feedback.CrashReporter
import de.trailscape.app.i18n.AppLocale
import de.trailscape.app.record.RecordingService
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.reminder.ReminderScheduler
import de.trailscape.app.strava.StravaConfig
import de.trailscape.app.strava.StravaServices
import de.trailscape.app.widget.WidgetUpdater
import kotlinx.coroutines.launch

/**
 * Application-Klasse einzig zu dem Zweck, [AppServices] mit einem
 * `Context` zu initialisieren, bevor irgendeine Activity/ViewModel darauf
 * zugreift, liegengebliebene Aufzeichnungs-Journale eines abgestuerzten
 * Prozesses als Tour zu retten, den lokalen Absturzberichter und das
 * Diagnose-Log einzurichten, den Zeitplan der Erinnerungen wieder auszurichten
 * und — nur in Builds mit Strava — den Auto-Upload anzuhaengen. Enthaelt bewusst
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
        StravaServices.init(this)
        installWidgetRefresh()
        if (StravaConfig.available) {
            AppServices.appScope.launch {
                // Strava-Auto-Upload: haengt am Ende der Aufzeichnung und an
                // der Wiederherstellung eines Journals, ohne den
                // RecordingService anzufassen. Doppelmeldungen fangen der
                // eindeutige Arbeitsname (KEEP) und die Pruefung auf einen
                // vorhandenen Vermerk ab (siehe StravaServices.onRideFinished).
                RecordingRepository.finishedRides.collect { rideId ->
                    // Ein Fehler hier darf den Sammler nicht beenden, sonst
                    // fiele der Auto-Upload bis zum naechsten App-Start aus.
                    runCatching { StravaServices.onRideFinished(rideId) }
                }
            }
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
     * Haelt das Startbildschirm-Widget (`widget/`) aktuell, ohne dass
     * ViewModel oder Speicherschicht davon wissen.
     *
     * Zwei Ausloeser, bewusst hier in der Application:
     *  - **Aufzeichnung fertig** ([RecordingRepository.finishedRides]): Die
     *    Tour ist gespeichert, das Widget soll die neue Serie zeigen, noch
     *    bevor der Fahrer die App wieder oeffnet — genau dann schaut er
     *    aufs Handy. Der Fluss kommt vom Dienst (auch bei der Wiederherstellung
     *    eines Journals) und ist unabhaengig davon, ob ein ViewModel lebt.
     *  - **App geht in den Hintergrund** (letzte Activity gestoppt): Fangt
     *    alles auf, was nur in der Oberflaeche passiert — Import, Health-Sync,
     *    Loeschen, Bearbeiten, Planaenderung, Sprachwechsel. Diese
     *    Schreibzugriffe laufen im `AppViewModel`; ein Haken je Aufrufstelle
     *    waere fehleranfaellig und wuerde bei jeder neuen vergessen. Ein
     *    Aufruf pro Verlassen der App ist guenstig und trifft den Moment, in
     *    dem das Widget wieder sichtbar wird.
     */
    private fun installWidgetRefresh() {
        AppServices.appScope.launch {
            RecordingRepository.finishedRides.collect {
                WidgetUpdater.request(this@TrailscapeApplication)
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started += 1
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // Beim Drehen/Sprachwechsel wird die Activity neu erzeugt und
                // zaehlt kurz auf 0 — das ist ein harmloser Zusatzaufruf.
                if (started == 0) WidgetUpdater.request(this@TrailscapeApplication)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
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
