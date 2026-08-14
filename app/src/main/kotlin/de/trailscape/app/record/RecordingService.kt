package de.trailscape.app.record

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.RideStorage
import de.trailscape.app.ui.formatKmDe
import de.trailscape.core.PointFilter
import de.trailscape.core.PointFilterResult
import de.trailscape.core.Ride
import de.trailscape.core.computeStats
import de.trailscape.core.formatDuration
import de.trailscape.core.haversineM
import de.trailscape.core.toLocationSample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Gestarteter Vordergrunddienst, der eine Tour aufzeichnet.
 *
 * Portierung von `lib/recorder.dart` + der Aufnahme-Semantik aus
 * `lib/screens/map_screen.dart` — mit einem entscheidenden Unterschied zur
 * Flutter-App: Dort lebt die Aufzeichnung im UI-Prozess und stirbt mit ihm
 * (`geolocator` haelt lediglich die Activity per Notification am Leben und
 * sammelt die Punkte im RAM). Hier ist die Aufzeichnung ein eigenstaendiger,
 * *gestarteter* Service, und jeder angenommene Punkt liegt vor der Rueckkehr
 * aus dem Callback auf dem Datentraeger (siehe [RecordingJournal]).
 *
 * Daraus folgt das Verhalten in den drei Toedlichkeitsstufen:
 *
 *  * **Activity weg** (Nutzer wischt die App aus den Recents): Der Service
 *    laeuft weiter, die Notification zeigt Distanz und Dauer.
 *  * **Service vom System beendet** (Speicherdruck): `START_STICKY` startet
 *    ihn neu, das `onStartCommand` bekommt `null` als Intent und setzt die
 *    Aufzeichnung aus dem Journal fort — inklusive Punktezahl,
 *    Duplikatpruefung und Pausenkonto.
 *  * **Prozess abgestuertzt / Akku leer**: Beim naechsten App-Start
 *    finalisiert [recoverIfNeeded] das verwaiste Journal zu einer Tour mit
 *    dem Namenszusatz „(wiederhergestellt)".
 *
 * Zwischen der zweiten und der dritten Stufe steht eine Rangordnung: **Der
 * Dienst gewinnt.** Beim `START_STICKY`-Neustart laufen die Wiederherstellung
 * (aus `TrailscapeApplication`) und das `ACTION_CONTINUE` gleichzeitig an, und
 * nur der Dienst weiss, ob er fortsetzen will. Die Wiederherstellung wartet
 * deshalb auf seine Entscheidung ([RecoveryGate]), und findet er hinterher
 * nichts mehr zum Fortsetzen, meldet er das laut, statt sich stillschweigend
 * zu beenden.
 *
 * ## Warum kein WakeLock
 * Der Dienst haelt bewusst keinen `PARTIAL_WAKE_LOCK`. Ein Wecker ueber drei
 * Stunden Fahrt kostet spuerbar Akku, und er wird fuer die Daten nicht
 * gebraucht: Jede GNSS-Meldung weckt das Geraet ohnehin auf, und genau dann
 * (und nur dann) gibt es etwas zu schreiben. Steht das Rad, kommen keine
 * Meldungen — und es geht auch nichts verloren. Was im Suspend stehen bleibt,
 * ist der sekuendliche [ticker] und mit ihm die Frische des Lebenszeichens;
 * dass daraus kein Datenverlust mehr folgt, ist der Zweck der Rangordnung oben
 * und der konservativen Auswertung in [beurteileJournal].
 *
 * Die Entscheidung, ob ein Punkt aufgenommen wird, faellt ausschliesslich in
 * [PointFilter] (`:core`, plattformfrei und dort getestet). Dieser Service
 * holt aus [Location] nur die Rohwerte heraus; was ein *fehlender* Rohwert
 * bedeutet, entscheidet [toLocationSample] (ebenfalls `:core`, ebenfalls dort
 * getestet).
 *
 * Berechtigungen holt die Oberflaeche (Phase 4) ein; hier wird nur defensiv
 * geprueft und bei Fehlen sauber mit Fehler-Notification abgebrochen.
 */
class RecordingService : Service() {

    private lateinit var journal: RecordingJournal
    private lateinit var recordingThread: HandlerThread
    private lateinit var handler: Handler

    private val rideStorage: RideStorage by lazy { resolveRideStorage(this) }

    private val locationManager: LocationManager? by lazy {
        getSystemService(LocationManager::class.java)
    }

    /**
     * Zustellt-Ziel fuer die Standort-Callbacks ab API 31, wo
     * `requestLocationUpdates` einen [Executor] statt eines `Looper`
     * entgegennimmt. Postet auf denselben Handler wie alles andere — die
     * Callbacks landen also weiterhin auf dem Aufzeichnungs-Thread.
     */
    private val recordingExecutor = Executor { command -> handler.post(command) }

    /**
     * Filter und Aufzeichnungszustand werden ausschliesslich auf
     * [recordingThread] *veraendert* — alle Kommandos aus `onStartCommand`
     * werden dorthin gepostet, die Standort-Callbacks laufen ohnehin dort.
     * Gelesen werden die Felder zusaetzlich beim Bauen der Notification, das
     * in `onStartCommand` noch auf dem Main-Thread passieren muss; deshalb
     * `@Volatile`. Ein dabei um Sekundenbruchteile veralteter Distanzwert in
     * der Notification ist folgenlos.
     */
    private val filter = PointFilter()

    @Volatile
    private var active = false

    @Volatile
    private var startedAtMs = 0L

    @Volatile
    private var pausedMsAccum = 0L

    @Volatile
    private var pauseStartedAtMs: Long? = null

    @Volatile
    private var distanceM = 0.0
    private var lastNotificationMs = 0L
    private var updatesRequested = false

    /**
     * Wanduhr-Zeitpunkt des zuletzt *angenommenen* Punktes — der Wachhund ueber
     * den Standort-Strom (siehe [ticker]). `0`, solange noch keiner kam; dann
     * gilt der Beginn der Aufzeichnung als Bezugspunkt.
     */
    @Volatile
    private var lastAcceptedAtMs = 0L

    /**
     * Wanduhr-Zeitpunkt, seit dem der Standort-Strom fuer diese Aufzeichnung
     * abonniert ist. Bezugspunkt des Wachhunds, solange noch kein Punkt
     * angekommen ist.
     */
    @Volatile
    private var stromSeitMs = 0L

    /**
     * Wann der Wachhund die Anmeldung zuletzt erneuert hat. Bremse gegen ein
     * An- und Abmelden im Sekundentakt; bewusst getrennt von [stromSeitMs],
     * damit die Anzeige „Kein GPS-Signal seit N min" durch ein erneutes
     * Abonnieren nicht wieder bei null anfaengt.
     */
    private var letzterResubscribeMs = 0L

    /**
     * Ob seit Beginn dieser Aufzeichnung mindestens ein Schreibvorgang ins
     * Journal fehlgeschlagen ist (typischerweise voller Speicher). Ab dann ist
     * die Datei nicht mehr die vollstaendige Wahrheit, und der Abschluss muss
     * die RAM-Punkte hinzunehmen (siehe [vereinigePunkte]).
     */
    @Volatile
    private var journalSchreibfehler = false

    /**
     * Ob der Fehlschlag bereits gemeldet wurde. Ein volles Dateisystem
     * scheitert bei *jedem* Punkt; ohne diese Bremse haette der Nutzer im
     * Sekundentakt dieselbe Meldung.
     */
    private var schreibfehlerGemeldet = false

    /**
     * [LocationListenerCompat] und nicht das nackte
     * `android.location.LocationListener`: Dessen drei Zusatzmethoden sind
     * erst ab API 30 `default`. Ein Kotlin-Objekt, das nur
     * `onLocationChanged` ueberschreibt, uebersetzt gegen compileSdk 36
     * anstandslos — und wuerde auf einem Geraet mit API 26 bis 29 mit einem
     * `AbstractMethodError` abstuerzen, sobald das System
     * `onStatusChanged` ruft. Die AndroidX-Variante bringt die
     * Standardimplementierungen selbst mit und ist damit auf allen von der
     * App unterstuetzten Versionen dieselbe Schnittstelle.
     *
     * Alle Rueckrufe laufen auf dem Aufzeichnungs-Thread (siehe
     * [requestUpdates]).
     */
    private val locationListener = object : LocationListenerCompat {

        override fun onLocationChanged(location: Location) {
            onLocation(location)
        }

        /**
         * Der Nutzer hat den Standort mitten in der Fahrt abgeschaltet.
         *
         * Bewusst KEIN [failAndStop]: Die Registrierung beim
         * [LocationManager] bleibt bestehen, und sobald der Standort wieder
         * an ist, fliessen die Punkte von selbst weiter. Die Aufzeichnung
         * abzubrechen wuerde eine versehentliche Beruehrung der
         * Schnelleinstellungen zu einem Tourabbruch machen. Gemeldet wird es
         * trotzdem laut — sonst zeichnet die App still nichts mehr auf.
         */
        override fun onProviderDisabled(provider: String) {
            if (!active) return
            val message = getString(R.string.recording_error_location_off_during_ride)
            RecordingRepository.publishError(message)
            notifyError(message)
        }

        override fun onProviderEnabled(provider: String) {
            if (!active) return
            // Die Meldung von oben ist erledigt; sie soll weder in der
            // Oberflaeche noch als Notification stehen bleiben.
            RecordingRepository.clearError()
            try {
                getSystemService(NotificationManager::class.java)?.cancel(ERROR_NOTIFICATION_ID)
            } catch (e: Exception) {
                // Ohne POST_NOTIFICATIONS gab es ohnehin keine Notification.
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RecordingRepository.attach(this)
        journal = RecordingJournal(RecordingJournal.directory(filesDir), AndroidHeartbeatClock)
        recordingThread = HandlerThread("trailscape-recording").apply { start() }
        handler = Handler(recordingThread.looper)
        ensureNotificationChannels()

        // Hier stand frueher ein `handler.post { recoverIfNeeded(...) }`, um
        // verwaiste Journale aufzuraeumen, bevor ein Kommando verarbeitet wird.
        // Das war genau verkehrt herum: Was in `onCreate` gepostet wird, laeuft
        // zwangslaeufig VOR dem `ACTION_CONTINUE` aus `onStartCommand` — die
        // Wiederherstellung schloss also die gerade laufende Fahrt als Tour ab,
        // und der Dienst fand nichts mehr zum Fortsetzen. Der Dienst muss
        // gewinnen; das Aufraeumen passiert deshalb erst NACH dem ersten
        // Kommando (siehe [handleCommand]), und die Wiederherstellung beim
        // App-Start wartet auf seine Entscheidung (siehe [RecoveryGate]).
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY liefert beim Neustart durch das System `null` — genau
        // der Fall, in dem aus dem Journal weiter aufgezeichnet werden muss.
        val systemNeustart = intent == null
        val action = intent?.action ?: ACTION_CONTINUE

        if (!enterForeground()) {
            // Der Dienst darf nicht im Vordergrund laufen (fehlende
            // Standortberechtigung ab Android 14). Aufraeumen passiert wie
            // alles andere auf dem Aufzeichnungs-Thread.
            handler.post {
                failAndStop(getString(R.string.recording_error_permission))
                // Auch das ist eine Entscheidung: Eine wartende
                // Wiederherstellung braucht auf diesen Dienst nicht laenger zu
                // warten (siehe [RecoveryGate]).
                recoveryGate.freigeben()
            }
            return START_NOT_STICKY
        }

        handler.post { handleCommand(action, systemNeustart) }
        return START_STICKY
    }

    override fun onDestroy() {
        stopUpdates()
        handler.removeCallbacksAndMessages(null)
        journal.close()
        recordingThread.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Kommandos

    /**
     * @param systemNeustart ob dieses Kommando aus einem
     *   `START_STICKY`-Neustart stammt (Intent war `null`). Nur dann ist ein
     *   fehlendes Journal ein *Verlust* und keine Belanglosigkeit — das System
     *   startet einen Dienst nur neu, wenn er lief.
     */
    private fun handleCommand(action: String, systemNeustart: Boolean = false) {
        try {
            when (action) {
                ACTION_START -> startRecording()
                ACTION_PAUSE -> setPaused(true)
                ACTION_RESUME -> setPaused(false)
                ACTION_TOGGLE_PAUSE -> setPaused(pauseStartedAtMs == null)
                ACTION_STOP -> finishAndStop()
                ACTION_CONTINUE -> continueFromJournal(systemNeustart)
                else -> Unit
            }
        } finally {
            // Der Dienst hat entschieden — eine wartende Wiederherstellung darf
            // jetzt los (siehe [RecoveryGate]). Im `finally`, damit auch eine
            // Ausnahme die Wiederherstellung nicht bis zum Ablauf der
            // Gnadenfrist blockiert.
            recoveryGate.freigeben()
        }

        if (action == ACTION_CONTINUE) {
            // Das Aufraeumen liegengebliebener Journale, das frueher in
            // `onCreate` stand — jetzt aber nachweislich NACH der Entscheidung
            // des Dienstes. `warten = false`, weil dieser Dienst der ist, auf
            // den zu warten waere.
            handler.post { recoverIfNeeded(this, rideStorage, force = false, warten = false) }
        }
    }

    private fun startRecording() {
        if (active) return

        val now = System.currentTimeMillis()
        val id = RecordingJournal.newRideId(now)

        filter.reset()
        startedAtMs = now
        pausedMsAccum = 0L
        pauseStartedAtMs = null
        distanceM = 0.0
        lastNotificationMs = 0L
        lastAcceptedAtMs = 0L
        stromSeitMs = now
        letzterResubscribeMs = now
        journalSchreibfehler = false
        schreibfehlerGemeldet = false

        try {
            // Abschluss des alten und Anlegen des neuen Journals unter einer
            // Sperre: dazwischen darf keine parallele Wiederherstellung das
            // frische Journal beanspruchen.
            RecordingJournal.withClaimLock {
                // Ein noch herumliegendes Journal gehoert zu einer frueheren
                // Tour und wird abgeschlossen, bevor eine neue beginnt — sonst
                // wuerde `journal.begin()` es kommentarlos ueberschreiben.
                //
                // `force`, weil das Lebenszeichen hier nichts mehr aussagt: Wir
                // SIND der Dienst, und `active == false` heisst, dass niemand
                // aufzeichnet. Ohne das Erzwingen ginge eine Tour verloren, die
                // vor weniger als 30 s abgestuerzt ist und die der Nutzer
                // sofort mit einer neuen Aufnahme ueberholt.
                recoverIfNeeded(this, rideStorage, force = true, warten = false)
                journal.begin(id, now)
                meldeLebenszeichen(journal.touchHeartbeat(now))
            }
        } catch (e: Exception) {
            failAndStop(getString(R.string.recording_error_journal))
            return
        }

        if (!requestUpdates(neueAufzeichnung = true)) return

        active = true
        meldeLebenszeichen(journal.touchHeartbeat(now))
        RecordingRepository.publishStarted(now, emptyList(), paused = false)
        updateNotification(now, force = true)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /**
     * Setzt eine Aufzeichnung fort, deren Service das System beendet und per
     * `START_STICKY` neu gestartet hat.
     *
     * @param systemNeustart siehe [handleCommand]. Steuert, ob ein leeres
     *   Journal als Verlust gemeldet wird.
     */
    private fun continueFromJournal(systemNeustart: Boolean = false) {
        if (active) return

        // Lesen, zum Weiterschreiben oeffnen und das Lebenszeichen setzen
        // gehoeren in EINEN Sperrabschnitt: Sonst kann die Wiederherstellung
        // (anderer Thread, eigene Journal-Instanz) das Journal genau dazwischen
        // beanspruchen und dieselbe Fahrt ein zweites Mal als Tour speichern,
        // waehrend dieser Dienst in eine neue, kopfzeilenlose Datei
        // weiterschreibt.
        val snapshot = try {
            RecordingJournal.withClaimLock {
                val read = journal.read()
                if (read != null) {
                    journal.reopenForAppend()
                    // Ab jetzt gilt das Journal als lebendig — die
                    // Wiederherstellung laesst es in Ruhe.
                    meldeLebenszeichen(journal.touchHeartbeat(System.currentTimeMillis()))
                }
                read
            }
        } catch (e: Exception) {
            failAndStop(getString(R.string.recording_error_journal))
            return
        }

        if (snapshot == null) {
            // Nichts fortzusetzen. Bei einem `START_STICKY`-Neustart ist das
            // kein Normalfall, sondern ein Verlust: Das System startet einen
            // Dienst nur neu, wenn er lief — es gab also eine Aufzeichnung, und
            // jetzt ist ihr Journal weg (von der Wiederherstellung abgeschlossen
            // oder beim Absturz beschaedigt). Frueher endete der Dienst hier
            // ohne jede Meldung, und der Nutzer fuhr zwei Stunden weiter, ohne
            // zu ahnen, dass nichts mehr aufgezeichnet wird.
            if (systemNeustart) {
                val message = getString(R.string.recording_error_continue_lost)
                RecordingRepository.publishError(message)
                notifyError(message)
            }
            stopSelfSafely()
            return
        }

        startedAtMs = snapshot.startedAtMs
        pausedMsAccum = snapshot.pausedMs
        pauseStartedAtMs = snapshot.pausedSinceMs
        filter.restore(snapshot.points)
        filter.paused = snapshot.pausedSinceMs != null
        distanceM = computeStats(snapshot.points).distanceKm * 1000
        lastNotificationMs = 0L
        lastAcceptedAtMs = 0L
        journalSchreibfehler = false
        schreibfehlerGemeldet = false

        if (!requestUpdates(neueAufzeichnung = false)) return

        active = true
        val now = System.currentTimeMillis()
        stromSeitMs = now
        letzterResubscribeMs = now
        meldeLebenszeichen(journal.touchHeartbeat(now))
        RecordingRepository.publishStarted(
            startedAtMs = snapshot.startedAtMs,
            points = snapshot.points,
            paused = snapshot.pausedSinceMs != null,
        )
        updateNotification(now, force = true)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun setPaused(paused: Boolean) {
        if (!active) return
        val currentlyPaused = pauseStartedAtMs != null
        if (paused == currentlyPaused) return

        val now = System.currentTimeMillis()
        // Der Pausenvermerk darf die Aufzeichnung nicht abbrechen: Seit
        // [RecordingJournal.writeLine] bei geschlossenem Stream wirft (statt die
        // Zeile still zu verlieren), koennte eine Ausnahme hier den
        // Aufzeichnungs-Thread und damit den ganzen Prozess reissen. Der
        // Pausenzustand im RAM stimmt auch ohne den Vermerk; nur das
        // Pausenkonto einer *wiederhergestellten* Tour waere dann unvollstaendig.
        if (paused) {
            pauseStartedAtMs = now
            filter.paused = true
            journalSchreiben { journal.appendPause(now) }
        } else {
            pauseStartedAtMs?.let { pausedMsAccum += now - it }
            pauseStartedAtMs = null
            filter.paused = false
            journalSchreiben { journal.appendResume(now) }
        }

        RecordingRepository.publishPaused(paused)
        updateNotification(now, force = true)
    }

    /**
     * Beendet die Aufzeichnung: Journal → Punkte → [computeStats] → [Ride] →
     * `RideStorage`. Die Punkte kommen aus der Datei — sie ist die Wahrheit,
     * solange sie sich schreiben liess.
     *
     * Nur wenn das *nicht* der Fall war (voller Speicher, siehe
     * [journalSchreibfehler]), kommen die Punkte aus dem RAM dazu; siehe
     * [vereinigePunkte] fuer die Begruendung.
     */
    private fun finishAndStop() {
        stopUpdates()
        handler.removeCallbacks(ticker)
        active = false

        val snapshot = journal.read()?.let { mitRamPunkten(it) }
        journal.close()
        // Ab hier zeichnet niemand mehr auf: Bleibt das Journal wegen eines
        // Speicherfehlers liegen, soll es sofort als verwaist gelten.
        journal.clearHeartbeat()

        var savedId: String? = null
        if (snapshot != null) {
            val ride = buildRide(this, snapshot, recovered = false)
            if (ride == null) {
                // Wie im Dart-Original: unter zwei Punkten gibt es nichts zu
                // speichern ("Zu wenige GPS-Punkte.").
                RecordingRepository.publishError(getString(R.string.recording_error_too_few_points))
                journal.discard()
            } else if (trySave(ride)) {
                savedId = ride.id
                journal.discard()
            }
            // Beim Speicherfehler bleibt das Journal liegen: der naechste
            // Aufruf von [recoverIfNeeded] versucht es erneut.
        } else {
            journal.discard()
        }

        RecordingRepository.publishStopped(savedId)
        stopSelfSafely()
    }

    private fun trySave(ride: Ride): Boolean = try {
        rideStorage.saveRide(ride)
        true
    } catch (e: Exception) {
        RecordingRepository.publishError(getString(R.string.recording_error_save_failed))
        false
    }

    // ------------------------------------------------------------- Standort

    /**
     * Fordert Standortaktualisierungen an. Liefert `false`, wenn die
     * Berechtigung fehlt, der Standort abgeschaltet ist oder der Provider den
     * Auftrag ablehnt — der Service hat sich dann bereits mit einer
     * Fehler-Notification beendet.
     *
     * ## Warum ausschliesslich [LocationManager.GPS_PROVIDER]
     * Aufgezeichnet wird eine Radtour im Freien; genau dafuer ist GNSS die
     * richtige und einzige gute Quelle. Die beiden naheliegenden Beimischungen
     * bringen hier nichts und schaden eher:
     *
     *  * **`NETWORK_PROVIDER`** schaetzt die Position aus Funkzellen und
     *    WLAN-Netzen. Im Feld liegt seine Genauigkeit typischerweise bei
     *    hunderten Metern — solche Punkte wirft [PointFilter] ohnehin weg
     *    (ueber 50 m). Gefaehrlich sind die *guten* Netzmeldungen in der
     *    Stadt: Mit 20 bis 30 m Genauigkeit rutschen sie durch den Filter und
     *    versetzen die Spur sprunghaft gegen die GPS-Punkte. Zwei Quellen
     *    ohne Fusionsalgorithmus einfach in denselben Track zu schreiben ist
     *    keine Verbesserung, sondern Rauschen — und der Genauigkeitsfilter
     *    ist kein Fusionsalgorithmus. Obendrein braucht dieser Provider einen
     *    Netz-Standortdienst, den ein Geraet ohne Google-Dienste gar nicht
     *    unbedingt hat.
     *  * **`FUSED_PROVIDER`** (ab API 31) waere eine solche Fusion — aber eine,
     *    deren Verhalten vom jeweiligen Systemabbild abhaengt und die auf
     *    manchen Geraeten gar nicht existiert. Ihr Gewinn liegt im Sparen von
     *    Strom und im Zurechtfinden in Gebaeuden; draussen mit freier
     *    Himmelssicht bringt sie gegenueber rohem GNSS keine besseren Punkte.
     *    Ihn zusaetzlich zu abonnieren hiesse, dieselbe Position doppelt
     *    einzusammeln, ohne zu wissen, welche der beiden die bessere ist.
     *
     * Der Preis dieser Entscheidung ist der erste Fix: Ohne die
     * Anlaufhilfe eines gebuendelten Dienstes kann er nach einem Kaltstart
     * eine halbe Minute und mehr dauern. Solange zeigt die Notification
     * „Warte auf GPS-Signal …".
     *
     * ## Warum zwei Wege zur Anmeldung
     * Ab API 31 nimmt der [LocationManager] ein
     * [android.location.LocationRequest] und einen [Executor] entgegen;
     * darunter gibt es nur die aeltere Signatur mit `minTimeMs`/`minDistanceM`
     * und einem `Looper`. Die App unterstuetzt ab API 26, also werden beide
     * Wege bedient statt einer erzwungen. Die inhaltlichen Vorgaben
     * ([PointFilter.UPDATE_INTERVAL_MS], [PointFilter.MIN_UPDATE_DISTANCE_M])
     * sind in beiden Faellen dieselben.
     *
     * `MissingPermission` ist unterdrueckt, weil die Berechtigung hier
     * ausdruecklich zur Laufzeit geprueft UND die [SecurityException]
     * zusaetzlich abgefangen wird (Phase 4 holt die Berechtigung im UI ein).
     *
     * @param neueAufzeichnung ob gerade eine *neue* Tour beginnt. Nur dann
     *   fuehrt ein abgeschalteter Standort zum Abbruch: Der Nutzer steht in
     *   diesem Moment mit dem Telefon in der Hand vor der App und kann den
     *   Schalter umlegen. Beim Fortsetzen aus dem Journal (`START_STICKY`,
     *   der Nutzer faehrt gerade) waere ein Abbruch das falsche Mittel — dort
     *   wird nur gewarnt und trotzdem abonniert, denn die Anmeldung ueberlebt
     *   einen abgeschalteten Provider und liefert wieder, sobald er zurueck
     *   ist.
     */
    @SuppressLint("MissingPermission")
    private fun requestUpdates(neueAufzeichnung: Boolean): Boolean {
        if (updatesRequested) return true

        if (!hasLocationPermission()) {
            failAndStop(getString(R.string.recording_error_permission))
            return false
        }

        val manager = locationManager
        if (manager == null) {
            failAndStop(getString(R.string.recording_error_start_failed))
            return false
        }

        // Ist der Standort ueberhaupt an? Ohne diese Pruefung liefe der
        // Vordergrunddienst mit Notification und Timer los und zeichnete
        // stillschweigend nichts auf — der aergerlichste aller Fehler, weil er
        // erst am Ende der Tour auffaellt. `isProviderEnabled` wirft, wenn es
        // den Provider gar nicht gibt (Geraet ohne GNSS); das faellt hier
        // ebenfalls unter „hier kommt nichts".
        val gpsBereit = try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
        if (!gpsBereit) {
            if (neueAufzeichnung) {
                failAndStop(getString(R.string.recording_error_location_disabled))
                return false
            }
            val message = getString(R.string.recording_error_location_off_during_ride)
            RecordingRepository.publishError(message)
            notifyError(message)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestUpdatesApi31(manager)
            } else {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    PointFilter.UPDATE_INTERVAL_MS,
                    PointFilter.MIN_UPDATE_DISTANCE_M,
                    locationListener,
                    recordingThread.looper,
                )
            }
            updatesRequested = true
            true
        } catch (e: SecurityException) {
            failAndStop(getString(R.string.recording_error_permission))
            false
        } catch (e: Exception) {
            failAndStop(getString(R.string.recording_error_start_failed))
            false
        }
    }

    /**
     * Der Weg ab API 31. Bewusst eine eigene Methode: So beruehrt die
     * Klassenpruefung von ART das erst ab Android 12 vorhandene
     * [android.location.LocationRequest] nur auf Geraeten, die diese Methode
     * auch wirklich ausfuehren.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestUpdatesApi31(manager: LocationManager) {
        val request = LocationRequest.Builder(PointFilter.UPDATE_INTERVAL_MS)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(PointFilter.UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(PointFilter.MIN_UPDATE_DISTANCE_M)
            .build()
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            request,
            recordingExecutor,
            locationListener,
        )
    }

    /**
     * Meldet die Standortaktualisierungen ab. Zwei Aufrufer: das Ende der
     * Aufzeichnung und der Wachhund, der nach langer Stille neu abonniert
     * ([pruefeStandortStrom]).
     */
    private fun stopUpdates() {
        if (!updatesRequested) return
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {
            // Nichts zu tun: Entweder ist der Dienst gleich weg, oder es folgt
            // ohnehin gleich eine frische Anmeldung.
        }
        updatesRequested = false
    }

    private fun onLocation(location: Location) {
        if (!active) return

        // `null` heisst hier ausdruecklich „das Geraet hat den Wert nicht
        // gemeldet". Was daraus wird, entscheidet `:core` (siehe
        // [toLocationSample]) — bei rohem GNSS koennen `hasAltitude()`,
        // `hasAccuracy()` und `hasSpeed()` durchaus `false` sein, anders als
        // beim frueher benutzten gebuendelten Standortdienst.
        val sample = toLocationSample(
            lat = location.latitude,
            lon = location.longitude,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            timeMs = location.time,
            fallbackTimeMs = System.currentTimeMillis(),
        )

        val previous = filter.lastPoint
        when (val result = filter.offer(sample)) {
            is PointFilterResult.Accepted -> {
                val point = result.point
                lastAcceptedAtMs = System.currentTimeMillis()
                // Zuerst auf den Datentraeger, dann in den RAM.
                journalSchreiben { journal.appendPoint(point) }
                if (previous != null) {
                    distanceM += haversineM(previous, point)
                }
                RecordingRepository.publishPoint(point, distanceM / 1000, filter.currentSpeedKmh)
                updateNotification(System.currentTimeMillis(), force = false)
            }

            is PointFilterResult.Rejected -> Unit
        }
    }

    /**
     * Aufgezeichnet wird nur mit **genauem** Standort.
     *
     * Frueher genuegte hier `ACCESS_COARSE_LOCATION` allein — das war eine
     * Luege gegenueber dem Rest der Methode: `requestLocationUpdates` auf dem
     * [LocationManager.GPS_PROVIDER] verlangt zwingend
     * `ACCESS_FINE_LOCATION` und wirft sonst eine [SecurityException]. Der
     * Dienst kam also bis in den Vordergrund, brach dann ab, und weil
     * `missingPermissions` die Freigabe fuer erteilt hielt, ging der
     * Berechtigungsdialog nie wieder auf. Seit Android 12 sitzt „Ungefaehr"
     * direkt neben „Genau" — ein Fehlgriff genuegte, und die App war
     * dauerhaft kaputt.
     *
     * Die Kartenanzeige kommt weiterhin mit „Ungefaehr" aus; die Trennung
     * steht in `ui/map/LocationAccess.kt`.
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------- Journal-Hilfen

    /**
     * Fuehrt einen Schreibvorgang ins Journal aus und macht einen Fehlschlag
     * *laut*.
     *
     * Frueher landete ein Schreibfehler nur im [RecordingRepository] —
     * sichtbar allein als Snackbar in einer geoeffneten App. Wer mit dem
     * Telefon in der Lenkertasche faehrt, sieht davon nichts: Die Anzeige
     * zaehlt munter weiter (die Punkte liegen ja im RAM), und beim Abschluss
     * ist alles ab dem ersten Fehler weg. Deshalb zusaetzlich eine
     * Fehler-Notification — und ein Merker, damit der Abschluss die RAM-Punkte
     * hinzunimmt.
     */
    private inline fun journalSchreiben(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            journalSchreibfehler = true
            val message = getString(R.string.recording_error_journal_write)
            RecordingRepository.publishError(message)
            if (!schreibfehlerGemeldet) {
                schreibfehlerGemeldet = true
                notifyError(message)
            }
        }
    }

    /**
     * Meldet ein fehlgeschlagenes Lebenszeichen. Es ist keine Kuer: Ohne
     * Lebenszeichen kann eine parallele Wiederherstellung das Journal
     * beanspruchen, waehrend dieser Dienst weiterschreibt. Die Auswertung ist
     * inzwischen zwar konservativ (siehe [beurteileJournal]), aber der Nutzer
     * soll trotzdem erfahren, dass der Speicher klemmt.
     */
    private fun meldeLebenszeichen(erfolgreich: Boolean) {
        if (erfolgreich || schreibfehlerGemeldet) return
        schreibfehlerGemeldet = true
        val message = getString(R.string.recording_error_journal_write)
        RecordingRepository.publishError(message)
        notifyError(message)
    }

    /**
     * Ergaenzt den aus der Datei gelesenen Schnappschuss um die Punkte, die nur
     * im RAM stehen — der Rettungsanker fuer den Fall, dass das Journal
     * zwischendurch nicht schreibbar war (siehe [vereinigePunkte]).
     *
     * Der RAM-Stand kommt aus dem [RecordingRepository]: Er ist derselbe
     * Punkteverlauf, den die Karte zeichnet, und er ist genau dann laenger als
     * die Datei, wenn ein `appendPoint` gescheitert ist.
     */
    private fun mitRamPunkten(snapshot: RecordingJournal.Snapshot): RecordingJournal.Snapshot {
        if (!journalSchreibfehler) return snapshot
        val vereint = vereinigePunkte(snapshot.points, RecordingRepository.points.value)
        if (vereint.size == snapshot.points.size) return snapshot
        return snapshot.copy(points = vereint)
    }

    // ----------------------------------------------------------------- Takt

    /**
     * Sekuendlicher Takt fuer die verstrichene Zeit in der Oberflaeche; die
     * Notification und der Heartbeat werden davon gedrosselt bedient
     * (alle [NOTIFICATION_INTERVAL_MS] ms), damit weder der
     * NotificationManager noch der Flash unnoetig beschaeftigt werden.
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            val now = System.currentTimeMillis()
            RecordingRepository.publishTick(elapsedMs(now), filter.currentSpeedKmh)
            pruefeStandortStrom(now)
            updateNotification(now, force = false)
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    /**
     * Wachhund ueber den Standort-Strom.
     *
     * Ein stillgelegter Strom sieht von aussen aus wie eine Aufzeichnung: Die
     * Notification zaehlt die Zeit weiter, die Distanz steht still, und
     * niemand erfaehrt, dass seit zwanzig Minuten kein Punkt mehr ankommt. Die
     * Ursachen sind vielfaeltig und alle unsichtbar — der Energiesparer eines
     * Herstellers meldet den Listener ab, das GNSS findet unter Baeumen keinen
     * Fix, oder alle Meldungen scheitern am Genauigkeitsfilter (ueber 50 m,
     * siehe [PointFilter]).
     *
     * Deshalb zwei Stufen:
     *
     *  1. Nach [GPS_SILENT_WARN_MS] steht „Kein GPS-Signal seit N min" in der
     *     Notification. Bewusst nur dort und nicht als Fehler-Notification: Ein
     *     Signalabriss ist waehrend einer Fahrt normal (Tunnel, Unterfuehrung,
     *     dichter Wald) und darf nicht bei jedem Mal Alarm schlagen.
     *  2. Nach [GPS_RESUBSCRIBE_MS] wird die Anmeldung einmal erneuert. Das ist
     *     das einzige Mittel gegen einen Listener, den ein OEM-Energiesparer
     *     im Hintergrund abgeraeumt hat — und es ist billig: `removeUpdates` +
     *     `requestLocationUpdates` kosten nichts, solange sie nicht im
     *     Sekundentakt passieren. Kommt trotzdem nichts, ist es kein
     *     Softwarefehler, sondern der Himmel.
     *
     * Waehrend einer Pause schweigt der Wachhund: Dass dann keine Punkte
     * kommen, ist der Zweck der Pause.
     */
    private fun pruefeStandortStrom(nowMs: Long) {
        val stilleMs = gpsStilleMs(nowMs) ?: return
        if (stilleMs < GPS_RESUBSCRIBE_MS) return
        if (nowMs - letzterResubscribeMs < GPS_RESUBSCRIBE_MS) return

        letzterResubscribeMs = nowMs
        stopUpdates()
        requestUpdates(neueAufzeichnung = false)
    }

    /**
     * Dauer der GPS-Stille in ms, oder `null`, wenn sie noch unterhalb der
     * Meldeschwelle liegt bzw. gerade pausiert wird.
     */
    private fun gpsStilleMs(nowMs: Long): Long? {
        if (!active || pauseStartedAtMs != null) return null
        val bezug = maxOf(lastAcceptedAtMs, stromSeitMs)
        if (bezug <= 0L) return null
        val stilleMs = nowMs - bezug
        return if (stilleMs >= GPS_SILENT_WARN_MS) stilleMs else null
    }

    private fun elapsedMs(nowMs: Long): Long {
        val paused = pausedMsAccum + (pauseStartedAtMs?.let { nowMs - it } ?: 0L)
        return (nowMs - startedAtMs - paused).coerceAtLeast(0L)
    }

    // -------------------------------------------------------- Notifications

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        // Ab Android 14 verweigert das System den Start eines
        // location-Vordergrunddienstes ohne Standortberechtigung. Der Aufrufer
        // raeumt auf (siehe onStartCommand).
        false
    }

    private fun updateNotification(nowMs: Long, force: Boolean) {
        if (!force && nowMs - lastNotificationMs < NOTIFICATION_INTERVAL_MS) return
        lastNotificationMs = nowMs
        if (active) meldeLebenszeichen(journal.touchHeartbeat(nowMs))
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Ohne POST_NOTIFICATIONS bleibt die Aufzeichnung trotzdem gueltig.
        }
    }

    private fun buildNotification(): Notification {
        val paused = pauseStartedAtMs != null
        val title = getString(
            if (paused) R.string.recording_notification_paused_title else R.string.recording_notification_title,
        )
        val jetzt = System.currentTimeMillis()
        val stilleMs = gpsStilleMs(jetzt)
        val text = when {
            // Die Stille geht vor: Sie ist die einzige Information, die der
            // Fahrerin sagt, dass gerade nichts mehr aufgezeichnet wird.
            stilleMs != null -> getString(
                R.string.recording_notification_no_gps,
                (stilleMs / 60_000L).toInt(),
            )

            !active || filter.acceptedCount == 0 ->
                getString(R.string.recording_notification_waiting)

            else -> getString(
                R.string.recording_notification_progress,
                formatKmDe(distanceM / 1000),
                formatDuration((elapsedMs(jetzt) / 1000).toInt()),
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        launchIntent()?.let { builder.setContentIntent(it) }

        if (active) {
            builder.addAction(
                0,
                getString(if (paused) R.string.recording_action_resume else R.string.recording_action_pause),
                commandIntent(
                    if (paused) ACTION_RESUME else ACTION_PAUSE,
                    if (paused) REQUEST_RESUME else REQUEST_PAUSE,
                ),
            )
        }
        builder.addAction(
            0,
            getString(R.string.recording_action_stop),
            commandIntent(ACTION_STOP, REQUEST_STOP),
        )

        return builder.build()
    }

    private fun commandIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Tippen auf die Notification oeffnet die App. Bewusst ueber den
     * Launcher-Intent statt ueber `MainActivity::class.java`, damit dieser
     * Service nichts ueber den Aufbau der Oberflaeche wissen muss.
     */
    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ERROR_CHANNEL_ID,
                getString(R.string.recording_error_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /**
     * Bricht ab: Fehlermeldung an die Oberflaeche und als eigene Notification
     * (die App ist beim Scheitern typischerweise nicht sichtbar), Journal
     * abschliessen, Dienst beenden. Bereits aufgezeichnete Punkte gehen dabei
     * nicht verloren — sie werden wie beim regulaeren Stopp gespeichert.
     */
    private fun failAndStop(message: String) {
        RecordingRepository.publishError(message)
        notifyError(message)

        if (active || journal.exists()) {
            active = false
            stopUpdates()
            handler.removeCallbacks(ticker)
            val snapshot = journal.read()?.let { mitRamPunkten(it) }
            journal.close()
            journal.clearHeartbeat()
            val ride = snapshot?.let { buildRide(this, it, recovered = true) }
            if (ride != null && trySave(ride)) {
                RecordingRepository.publishFinishedRide(ride.id)
                journal.discard()
            } else if (ride == null) {
                // Nichts Verwertbares im Journal (weniger als zwei Punkte).
                journal.discard()
            }
            // Beim Speicherfehler bleibt das Journal liegen und wird beim
            // naechsten Aufruf von [recoverIfNeeded] erneut versucht.
        }

        RecordingRepository.publishStopped(null)
        stopSelfSafely()
    }

    private fun notifyError(message: String) {
        try {
            val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
                .setContentTitle(getString(R.string.recording_error_title))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .apply { launchIntent()?.let { setContentIntent(it) } }
                .build()
            getSystemService(NotificationManager::class.java)
                ?.notify(ERROR_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Ohne POST_NOTIFICATIONS bleibt nur der Fehler im StateFlow.
        }
    }

    private fun stopSelfSafely() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Der Dienst war noch nicht im Vordergrund.
        }
        stopSelf()
    }

    companion object {

        /** Startet eine neue Aufzeichnung. */
        const val ACTION_START = "de.trailscape.app.record.action.START"

        /** Pausiert die laufende Aufzeichnung. */
        const val ACTION_PAUSE = "de.trailscape.app.record.action.PAUSE"

        /** Setzt eine pausierte Aufzeichnung fort. */
        const val ACTION_RESUME = "de.trailscape.app.record.action.RESUME"

        /** Schaltet zwischen Pause und Fortsetzung um. */
        const val ACTION_TOGGLE_PAUSE = "de.trailscape.app.record.action.TOGGLE_PAUSE"

        /** Beendet die Aufzeichnung, speichert die Tour und stoppt den Dienst. */
        const val ACTION_STOP = "de.trailscape.app.record.action.STOP"

        /**
         * Setzt eine Aufzeichnung aus dem Journal fort. Wird intern beim
         * `START_STICKY`-Neustart verwendet (Intent ist dann `null`), kann
         * aber auch explizit geschickt werden.
         */
        const val ACTION_CONTINUE = "de.trailscape.app.record.action.CONTINUE"

        private const val CHANNEL_ID = "recording"
        private const val ERROR_CHANNEL_ID = "recording_errors"
        private const val NOTIFICATION_ID = 1
        private const val ERROR_NOTIFICATION_ID = 2

        private const val REQUEST_STOP = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_RESUME = 3
        private const val REQUEST_OPEN = 4

        private const val TICK_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_INTERVAL_MS = 5_000L

        /**
         * Ab dieser Stille im Standort-Strom steht „Kein GPS-Signal seit N min"
         * in der Notification (siehe [pruefeStandortStrom]). Drei Minuten sind
         * lang genug, um Tunnel, Unterfuehrungen und dichten Wald zu
         * ueberstehen, und kurz genug, um eine kaputte Aufzeichnung noch
         * waehrend der Fahrt zu bemerken.
         */
        private const val GPS_SILENT_WARN_MS = 3 * 60_000L

        /**
         * Ab dieser Stille wird die Anmeldung beim [LocationManager] einmal
         * erneuert — und danach fruehestens wieder nach derselben Zeit.
         */
        private const val GPS_RESUBSCRIBE_MS = 5 * 60_000L

        /**
         * Ab diesem Alter gilt das Lebenszeichen eines Journals als erloschen
         * und die Aufzeichnung als verwaist. Grosszuegig gewaehlt gegenueber
         * dem 5-Sekunden-Takt des Heartbeats, damit ein kurz haengender oder
         * gerade neu startender Service nicht faelschlich fuer tot erklaert
         * wird.
         */
        private const val HEARTBEAT_STALE_MS = 30_000L

        /**
         * Wie lange die Wiederherstellung am Prozessanfang auf die Entscheidung
         * eines startenden Dienstes wartet. Begruendung und Abwaegung stehen an
         * [RecoveryGate].
         *
         * Die Instanz entsteht mit dem Laden dieser Klasse — praktisch also mit
         * dem Prozessstart, denn `TrailscapeApplication.onCreate` beruehrt
         * [recoverIfNeeded] als eine der ersten Handlungen.
         */
        private const val RECOVERY_GRACE_MS = 5_000L

        private val recoveryGate = RecoveryGate(RECOVERY_GRACE_MS)

        /**
         * Schliesst ein verwaistes Journal zu einer Tour ab, falls es eines
         * gibt — die Absturzsicherung der Aufzeichnung.
         *
         * Abgeschlossen wird nur, wenn *keine* Aufzeichnung laeuft. Erkannt
         * wird das an drei Dingen:
         *
         *  1. **Der Dienst gewinnt.** Faehrt gerade ein Dienst hoch (der
         *     typische `START_STICKY`-Neustart mitten in der Fahrt), wartet
         *     diese Funktion auf seine Entscheidung — nur er weiss, ob er
         *     fortsetzen will. Siehe [RecoveryGate].
         *  2. **Der [RecordingRepository]-Zustand** deckt den gleichen Prozess
         *     ab: Laeuft hier eine Aufzeichnung, wird nichts angefasst.
         *  3. **Das Lebenszeichen** neben dem Journal deckt den Fall eines
         *     inzwischen gestorbenen Prozesses ab. Seine Auswertung ist
         *     bewusst konservativ: Ein fehlendes oder unlesbares Lebenszeichen
         *     heisst „unbekannt", nicht „tot" — dann entscheidet die
         *     Aenderungszeit von `active.jsonl`. Siehe [beurteileJournal].
         *
         * Das Journal wird vor dem Auswerten umbenannt
         * (`recovering-<zeitstempel>.jsonl`) und erst nach erfolgreichem
         * Speichern geloescht. Schlaegt das Speichern fehl oder stirbt der
         * Prozess mittendrin, wird die Datei beim naechsten Aufruf erneut
         * angefasst — verloren geht nichts.
         *
         * **Aufrufort:** [de.trailscape.app.TrailscapeApplication.onCreate],
         * unmittelbar nach `AppServices.init(this)`, und wegen der Datei-IO
         * nicht auf dem Main-Thread:
         * ```kotlin
         * AppServices.appScope.launch {
         *     RecordingService.recoverIfNeeded(this@TrailscapeApplication, AppServices.rideStorage)
         * }
         * ```
         * Der Service ruft die Funktion zusaetzlich selbst auf — nach dem
         * ersten Kommando und vor jedem Start einer neuen Aufzeichnung;
         * mehrfache Aufrufe sind unschaedlich.
         *
         * @param force ueberspringt die Lebenszeichen-Pruefung. Nur fuer den
         *   Dienst selbst gedacht, der sicher weiss, dass gerade nichts
         *   aufgezeichnet wird (siehe `startRecording`).
         * @return die dabei gespeicherten Touren (in der Regel hoechstens eine).
         */
        @JvmStatic
        @JvmOverloads
        fun recoverIfNeeded(
            context: Context,
            rideStorage: RideStorage,
            force: Boolean = false,
        ): List<Ride> = recoverIfNeeded(context, rideStorage, force, warten = !force)

        /**
         * @param warten ob auf die Entscheidung eines gerade startenden
         *   Dienstes gewartet werden soll. Der Dienst selbst ruft mit `false`
         *   — er wuerde sonst auf sich selbst warten.
         */
        private fun recoverIfNeeded(
            context: Context,
            rideStorage: RideStorage,
            force: Boolean,
            warten: Boolean,
        ): List<Ride> {
            val appContext = context.applicationContext
            val dir = RecordingJournal.directory(appContext.filesDir)
            if (!dir.isDirectory) return emptyList()

            // Vor der Sperre, nicht in ihr: Der Dienst, auf dessen Entscheidung
            // hier gewartet wird, braucht dieselbe Sperre, um ueberhaupt
            // entscheiden zu koennen.
            if (warten) recoveryGate.warteAufDienst()

            // Der ganze Ablauf laeuft unter der prozessweiten Journal-Sperre:
            // Ein Dienst, der gerade eine Aufzeichnung fortsetzt oder eine neue
            // beginnt, haelt dieselbe Sperre (siehe RecordingJournal.claimLock)
            // — Lebenszeichen-Pruefung und Inbesitznahme koennen dadurch nicht
            // mehr mit seinem `read()`/`reopenForAppend()` verschraenken.
            return RecordingJournal.withClaimLock {
                val recovered = mutableListOf<Ride>()

                // 1. Journale, die ein frueherer Anlauf bereits beansprucht hat
                //    (Absturz genau waehrend der Wiederherstellung).
                for (file in RecordingJournal.pendingClaimed(dir)) {
                    finalizeClaimed(appContext, rideStorage, file)?.let { recovered.add(it) }
                }

                // 2. Das aktive Journal — nur wenn niemand mehr daran schreibt.
                val journal = RecordingJournal(dir, AndroidHeartbeatClock)
                if (!journal.exists()) return@withClaimLock recovered
                if (!force && RecordingRepository.isRecording.value) return@withClaimLock recovered

                val jetzt = AndroidHeartbeatClock.stempel()
                val urteil = if (force) {
                    JournalUrteil.WIEDERHERSTELLEN
                } else {
                    beurteileJournal(
                        alter = bewerteLebenszeichen(journal.lebenszeichen(), jetzt),
                        journalAlterMs = journal.journalAlterMs(jetzt.wallClockMs),
                        verfallsalterMs = HEARTBEAT_STALE_MS,
                    )
                }
                if (urteil == JournalUrteil.VERSCHONEN) return@withClaimLock recovered

                val claimed = RecordingJournal.claimStale(dir, jetzt.wallClockMs)
                    ?: return@withClaimLock recovered
                File(dir, RecordingJournal.LOCK_FILE_NAME).delete()
                finalizeClaimed(appContext, rideStorage, claimed)?.let { recovered.add(it) }

                recovered
            }
        }

        private fun finalizeClaimed(context: Context, rideStorage: RideStorage, file: File): Ride? {
            val snapshot = RecordingJournal.parse(file)
            val ride = snapshot?.let { buildRide(context, it, recovered = true) }

            if (ride == null) {
                // Kein verwertbarer Inhalt (weniger als zwei Punkte) — wie im
                // Dart-Original gibt es daraus keine Tour.
                file.delete()
                return null
            }

            return try {
                rideStorage.saveRide(ride)
                file.delete()
                RecordingRepository.publishFinishedRide(ride.id)
                ride
            } catch (e: Exception) {
                // Datei bleibt liegen: naechster Versuch beim naechsten Start.
                RecordingRepository.publishError(
                    context.getString(R.string.recording_error_save_failed),
                )
                null
            }
        }

        /**
         * Baut die Tour aus einem Journal-Schnappschuss.
         *
         * Folgt `_stopRecording()` in `lib/screens/map_screen.dart`:
         * unter zwei Punkten entsteht keine Tour, `createdAt` ist der erste
         * bekannte Punkt-Zeitstempel, die Kennzahlen kommen aus
         * [computeStats], der Name lautet „Tour <TT.MM.JJJJ>".
         *
         * Zwei bewusste Abweichungen vom Original:
         *  * Der Name wird nicht erfragt — ein Vordergrunddienst kann keinen
         *    Dialog zeigen. Es wird direkt der Vorschlag verwendet, den die
         *    Flutter-App anbietet; Umbenennen ist Sache der Tourenliste.
         *  * Das Datum im Namen ist der Beginn der Tour, nicht der Zeitpunkt
         *    des Speicherns. Bei einer wiederhergestellten Tour kann das
         *    Speichern Tage spaeter passieren.
         *
         * Dazu kommt die Pausenkorrektur ([ohnePausenzeit]): Die Pausenzeit
         * steht nur im Journal, und ohne sie zeigte die fertige Tour eine
         * andere Dauer als die Notification waehrend der Fahrt.
         */
        private fun buildRide(
            context: Context,
            snapshot: RecordingJournal.Snapshot,
            recovered: Boolean,
        ): Ride? {
            val points = snapshot.points
            if (points.size < 2) return null

            val createdAt = points.firstOrNull { it.time != null }?.time
                ?: snapshot.startedAtMs
            val date = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(createdAt))
            val name = context.getString(
                if (recovered) R.string.recording_ride_name_recovered else R.string.recording_ride_name,
                date,
            )

            return Ride(
                id = snapshot.id,
                name = name,
                createdAt = createdAt,
                stats = ohnePausenzeit(computeStats(points), snapshot.pausedMs),
                points = points,
            )
        }

        /**
         * Bevorzugt die zentral konfigurierte [RideStorage]; faellt auf eine
         * eigene Instanz auf demselben Verzeichnis zurueck, falls
         * `AppServices` (noch) nicht initialisiert ist — ein
         * `START_STICKY`-Neustart darf nicht daran scheitern.
         */
        private fun resolveRideStorage(context: Context): RideStorage = try {
            AppServices.rideStorage
        } catch (e: Exception) {
            RideStorage(File(context.applicationContext.filesDir, "rides"))
        }

        /** Nur fuer Tests/Diagnose: Pfad des aktiven Journals. */
        @JvmStatic
        fun journalFile(context: Context): File =
            File(RecordingJournal.directory(context.filesDir), RecordingJournal.ACTIVE_FILE_NAME)
    }
}
