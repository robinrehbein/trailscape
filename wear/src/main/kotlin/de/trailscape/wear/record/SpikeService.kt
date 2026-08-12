package de.trailscape.wear.record

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAccuracy
import androidx.health.services.client.data.LocationData
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import de.trailscape.core.TrackPoint
import de.trailscape.core.computeStats
import de.trailscape.wear.MainActivity
import de.trailscape.wear.R
import de.trailscape.wear.exercise.ExerciseRecorder
import de.trailscape.wear.exercise.ermittleFaehigkeiten
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Der Vordergrunddienst, der den Versuch traegt.
 *
 * Er ist bewusst mehr Messgeraet als App: Er haelt den
 * [androidx.health.services.client.ExerciseClient], schreibt jede
 * Rueckmeldung ins [SpikeJournal] und spiegelt nur so viel nach
 * [SpikeStatus], wie die Oberflaeche zum Anzeigen braucht.
 *
 * Warum ueberhaupt ein Vordergrunddienst und KEIN WakeLock: Health Services
 * haelt die Uebung genau so lange am Leben, wie ein Dienst mit
 * `foregroundServiceType="health|location"` laeuft — mehr braucht es nicht.
 * Ein zusaetzlicher WakeLock wuerde die CPU wachhalten und damit exakt den
 * Akkuvorteil vernichten, dessen Vermessung Frage 3 des Spikes ist. Der
 * Dienst darf und soll schlafen; die Daten kommen trotzdem, notfalls
 * gebatcht.
 *
 * Der Dienst ist bewusst NICHT `START_STICKY` und stellt sich nach einem
 * Prozesstod auch nicht wieder her. Anders als die Telefon-App hat er nichts
 * zu retten: Das Journal liegt vollstaendig auf dem Datentraeger, und ein
 * abgebrochener Versuch ist als Messergebnis genauso brauchbar wie ein
 * beendeter — er endet nur ohne `end`-Zeile.
 */
class SpikeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var journal: SpikeJournal
    private lateinit var recorder: ExerciseRecorder

    /**
     * Wall-Clock-Zeitpunkt des letzten Bootvorgangs.
     *
     * Health Services stempelt jeden Datenpunkt mit `timeDurationFromBoot` —
     * einem Offset seit dem Systemstart, NICHT mit einer Uhrzeit. Erst dieser
     * Bezugspunkt macht daraus einen Zeitstempel, den man mit dem
     * Akkuprotokoll oder einer zweiten Aufzeichnung vergleichen kann.
     */
    private lateinit var bootZeitpunkt: Instant

    /**
     * Alle empfangenen Positionen, im RAM — die Grundlage fuer den
     * `:core`-Gegenrechnung (Frage 4). Das Protokoll auf dem Datentraeger
     * bleibt die Wahrheit; diese Liste ist nur die Rechenkopie.
     */
    private val punkte = mutableListOf<TrackPoint>()

    private var startMs = 0L
    private var startElapsedMs = 0L
    private var pausiertSeitMs: Long? = null
    private var pausierteMs = 0L
    private var laeuft = false
    private var hsDistanzM = 0.0
    private var letzteCoreRechnungMs = 0L
    private var letzteBenachrichtigungMs = 0L

    @Volatile
    private var letzteHf: Int? = null

    private var chronometerJob: Job? = null
    private var akkuJob: Job? = null

    /**
     * Display an/aus. Ohne diese Markierungen liesse sich beim Auswerten
     * nicht unterscheiden, ob eine Luecke in den Punkten vom Batching bei
     * dunklem Display (ca. alle 150 s) oder von einem echten GPS-Aussetzer
     * stammt.
     */
    private val displayEmpfaenger = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val an = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                else -> return
            }
            journal.display(System.currentTimeMillis(), an)
        }
    }
    private var displayRegistriert = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        legeBenachrichtigungskanalAn()
        recorder = ExerciseRecorder(HealthServices.getClient(this).exerciseClient)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                gehInDenVordergrund()
                if (!laeuft) scope.launch { beginneVersuch() }
            }

            ACTION_PAUSE -> scope.launch { pausiere() }
            ACTION_RESUME -> scope.launch { setzeFort() }
            ACTION_STOP -> scope.launch { beendeVersuch("benutzer") }
            else -> stopSelf()
        }
        // Kein START_STICKY: siehe Klassendoc.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (displayRegistriert) {
            runCatching { unregisterReceiver(displayEmpfaenger) }
            displayRegistriert = false
        }
        chronometerJob?.cancel()
        akkuJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // --- Ablauf --------------------------------------------------------------

    private suspend fun beginneVersuch() {
        laeuft = true
        SpikeStatus.zuruecksetzen()
        punkte.clear()
        hsDistanzM = 0.0
        pausierteMs = 0L
        pausiertSeitMs = null
        letzteCoreRechnungMs = 0L
        letzteBenachrichtigungMs = 0L

        startMs = System.currentTimeMillis()
        startElapsedMs = SystemClock.elapsedRealtime()
        bootZeitpunkt = Instant.ofEpochMilli(startMs - startElapsedMs)

        val verzeichnis = getExternalFilesDir(null) ?: filesDir
        journal = SpikeJournal(SpikeJournal.datei(verzeichnis, startMs))
        SpikeStatus.setzeJournalPfad(journal.pfad)

        try {
            val client = HealthServices.getClient(this).exerciseClient
            val bericht = ermittleFaehigkeiten(client)
            SpikeStatus.setzeBericht(bericht)

            journal.beginn(
                startMs = startMs,
                geraet = geraeteJson(),
                faehigkeiten = SpikeJournal.faehigkeitenJson(
                    radfahrenUnterstuetzt = bericht.radfahrenUnterstuetzt,
                    unterstuetzt = bericht.unterstuetzteNamen,
                    vermisst = bericht.vermissteNamen,
                    geraet = bericht.geraeteNamen,
                    autoPause = bericht.unterstuetztAutoPause,
                ),
                akkuProzent = akkustand(),
            )

            if (!bericht.radfahrenUnterstuetzt || bericht.angeforderte.isEmpty()) {
                scheitere("Uhr meldet keine Radfahr-Faehigkeiten")
                return
            }

            registriereDisplayEmpfaenger()

            // Erst zuhoeren, dann vorwaermen, dann starten. Andersherum gingen
            // die Verfuegbarkeits-Meldungen der Aufwaermphase verloren — und
            // genau die beantworten, wie lange die Uhr bis zum ersten Fix
            // braucht.
            scope.launch { sammleEreignisse() }

            SpikeStatus.setzePhase(SpikeStatus.Phase.VORBEREITEN)
            recorder.vorbereiten(bericht.angeforderte)
            journal.notiz(System.currentTimeMillis(), "prepareExercise abgeschlossen")

            recorder.starten(bericht.angeforderte)
            journal.notiz(System.currentTimeMillis(), "startExercise abgeschlossen")

            // Der Chronometer laeuft ab dem tatsaechlichen Start der Uebung,
            // nicht ab dem Vorwaermen.
            startElapsedMs = SystemClock.elapsedRealtime()
            SpikeStatus.setzePhase(SpikeStatus.Phase.LAEUFT)

            starteChronometer()
            starteAkkuProtokoll()
        } catch (e: Exception) {
            scheitere(e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun sammleEreignisse() {
        recorder.ereignisse().collect { ereignis ->
            val jetzt = System.currentTimeMillis()
            when (ereignis) {
                is ExerciseRecorder.Ereignis.Registriert ->
                    journal.notiz(jetzt, "Callback registriert")

                is ExerciseRecorder.Ereignis.RegistrierungFehlgeschlagen -> {
                    journal.notiz(jetzt, "Callback-Registrierung fehlgeschlagen: ${ereignis.ursache}")
                    scheitere("Callback nicht registriert: ${ereignis.ursache.message}")
                }

                is ExerciseRecorder.Ereignis.Verfuegbarkeit -> {
                    val name = ereignis.datentyp.name
                    val zustand = ereignis.zustand.toString()
                    journal.verfuegbarkeit(jetzt, name, zustand)
                    when (name) {
                        DataType.LOCATION.name -> SpikeStatus.setzeGpsZustand(zustand)
                        DataType.HEART_RATE_BPM.name -> SpikeStatus.setzeHfZustand(zustand)
                    }
                }

                is ExerciseRecorder.Ereignis.Runde ->
                    journal.notiz(jetzt, "Rundenzusammenfassung empfangen")

                is ExerciseRecorder.Ereignis.Aktualisierung -> verarbeite(ereignis.update)
            }
        }
    }

    private fun verarbeite(update: ExerciseUpdate) {
        val zustand = update.exerciseStateInfo.state
        if (zustand.isEnded) {
            journal.notiz(System.currentTimeMillis(), "Uebung von Health Services beendet: ${zustand.name}")
        }

        verarbeiteMesswerte(update)
        verarbeitePositionen(update)
    }

    /**
     * Alle Nicht-Positions-Messwerte ins Protokoll.
     *
     * Bewusst generisch ueber die drei Datenpunkt-Arten statt Datentyp fuer
     * Datentyp: Der Spike soll auch das aufzeichnen, was niemand erwartet
     * hat — eine Uhr, die zusaetzliche Typen liefert, ist ein Ergebnis.
     */
    private fun verarbeiteMesswerte(update: ExerciseUpdate) {
        for (punkt in update.latestMetrics.sampleDataPoints) {
            if (punkt.dataType == DataType.LOCATION) continue
            val wert = (punkt.value as? Number)?.toDouble() ?: continue
            val offsetMs = punkt.timeDurationFromBoot.toMillis()
            journal.messwert(
                zeitMs = bootZeitpunkt.toEpochMilli() + offsetMs,
                name = punkt.dataType.name,
                wert = wert,
                art = "sample",
                bootOffsetMs = offsetMs,
            )
            when (punkt.dataType) {
                DataType.HEART_RATE_BPM -> {
                    letzteHf = wert.toInt()
                    SpikeStatus.setzeHf(wert.toInt())
                }

                DataType.ABSOLUTE_ELEVATION -> SpikeStatus.setzeHoehe(wert)
                // Health Services liefert m/s; die Anzeige will km/h.
                DataType.SPEED -> SpikeStatus.setzeGeschwindigkeit(wert * 3.6)
            }
        }

        for (punkt in update.latestMetrics.intervalDataPoints) {
            val wert = (punkt.value as? Number)?.toDouble() ?: continue
            val offsetMs = punkt.endDurationFromBoot.toMillis()
            journal.messwert(
                zeitMs = bootZeitpunkt.toEpochMilli() + offsetMs,
                name = punkt.dataType.name,
                wert = wert,
                art = "interval",
                bootOffsetMs = offsetMs,
            )
            if (punkt.dataType == DataType.DISTANCE) {
                // DISTANCE ist ein Zuwachs pro Intervall, kein Gesamtwert.
                hsDistanzM += wert
                SpikeStatus.setzeHsDistanz(hsDistanzM / 1000.0)
            }
        }

        for (punkt in update.latestMetrics.cumulativeDataPoints) {
            val wert = punkt.total.toDouble()
            journal.messwert(
                zeitMs = punkt.end.toEpochMilli(),
                name = punkt.dataType.name,
                wert = wert,
                art = "cumulative",
                bootOffsetMs = null,
            )
            if (punkt.dataType == DataType.DISTANCE_TOTAL) {
                // Der Gesamtwert der Uhr sticht die selbst summierten
                // Zuwaechse — er ueberlebt auch eine verpasste Zustellung.
                hsDistanzM = wert
                SpikeStatus.setzeHsDistanz(wert / 1000.0)
            }
        }

        // Kennzahlen (min/max/avg) schickt Health Services ungefragt mit,
        // sobald ein Datentyp eine Aggregat-Entsprechung hat. Mitschreiben
        // kostet nichts und verraet spaeter, ob die Uhr intern mehr weiss,
        // als sie an Momentanwerten herausrueckt.
        for (punkt in update.latestMetrics.statisticalDataPoints) {
            journal.messwert(
                zeitMs = punkt.end.toEpochMilli(),
                name = punkt.dataType.name,
                wert = punkt.average.toDouble(),
                art = "statistical",
                bootOffsetMs = null,
                minWert = punkt.min.toDouble(),
                maxWert = punkt.max.toDouble(),
            )
        }
    }

    private fun verarbeitePositionen(update: ExerciseUpdate) {
        val gemeldete = update.latestMetrics.getData(DataType.LOCATION)
        if (gemeldete.isEmpty()) return

        for (punkt in gemeldete) {
            val ort: LocationData = punkt.value
            // altitude ist Double.NaN, wenn die Uhr keine absolute Hoehe
            // liefert — ungeprueft weitergereicht wuerde daraus im Protokoll
            // ein „NaN", das kein JSON-Parser annimmt, und in `:core` eine
            // Hoehenberechnung, die stillschweigend Unsinn ergibt.
            val hoehe = ort.altitude.takeIf { !it.isNaN() }
            val genauigkeit = punkt.accuracy as? LocationAccuracy
            val offsetMs = punkt.timeDurationFromBoot.toMillis()
            val zeitMs = bootZeitpunkt.toEpochMilli() + offsetMs

            journal.punkt(
                zeitMs = zeitMs,
                lat = ort.latitude,
                lon = ort.longitude,
                hoeheM = hoehe,
                genauigkeitM = genauigkeit?.horizontalPositionErrorMeters,
                vertikaleGenauigkeitM = genauigkeit?.verticalPositionErrorMeters,
                bootOffsetMs = offsetMs,
            )

            punkte.add(
                TrackPoint(
                    lat = ort.latitude,
                    lon = ort.longitude,
                    ele = hoehe,
                    time = zeitMs,
                    hr = letzteHf,
                ),
            )
        }

        SpikeStatus.setzePunktzahl(punkte.size)

        // Frage 4: dieselbe Fahrt, gerechnet vom plattformfreien `:core`.
        //
        // Gedrosselt, und zwar aus Ruecksicht auf Frage 3: `computeStats`
        // rechnet ueber die GESAMTE Punktliste, also nach vier Stunden ueber
        // rund 14 000 Punkte mit je einer Haversine-Formel. Jede Sekunde
        // ausgefuehrt waere das eine spuerbare Dauerlast auf einem
        // Uhren-Prozessor — und der Spike wuerde am Ende seinen eigenen
        // Rechenaufwand als Akkuverbrauch der Aufzeichnung messen.
        val jetzt = SystemClock.elapsedRealtime()
        if (jetzt - letzteCoreRechnungMs >= CORE_INTERVALL_MS) {
            letzteCoreRechnungMs = jetzt
            val stats = computeStats(punkte)
            SpikeStatus.setzeCoreWerte(stats.distanceKm, stats.ascentM)
        }

        // Aus demselben Grund gedrosselt: Eine sekuendlich neu gebaute
        // Notification weckt jedes Mal die Systemoberflaeche.
        if (jetzt - letzteBenachrichtigungMs >= BENACHRICHTIGUNG_INTERVALL_MS) {
            letzteBenachrichtigungMs = jetzt
            aktualisiereBenachrichtigung()
        }
    }

    private suspend fun pausiere() {
        if (!laeuft || pausiertSeitMs != null) return
        runCatching { recorder.pausieren() }
            .onFailure { journal.notiz(System.currentTimeMillis(), "pauseExercise fehlgeschlagen: $it") }
        pausiertSeitMs = SystemClock.elapsedRealtime()
        journal.notiz(System.currentTimeMillis(), "pausiert")
        SpikeStatus.setzePhase(SpikeStatus.Phase.PAUSIERT)
    }

    private suspend fun setzeFort() {
        val seit = pausiertSeitMs ?: return
        runCatching { recorder.fortsetzen() }
            .onFailure { journal.notiz(System.currentTimeMillis(), "resumeExercise fehlgeschlagen: $it") }
        pausierteMs += SystemClock.elapsedRealtime() - seit
        pausiertSeitMs = null
        journal.notiz(System.currentTimeMillis(), "fortgesetzt")
        SpikeStatus.setzePhase(SpikeStatus.Phase.LAEUFT)
    }

    private suspend fun beendeVersuch(grund: String) {
        if (!laeuft) {
            stopSelf()
            return
        }
        laeuft = false
        chronometerJob?.cancel()
        akkuJob?.cancel()

        runCatching { recorder.beenden() }
            .onFailure { journal.notiz(System.currentTimeMillis(), "endExercise fehlgeschlagen: $it") }

        // Abschliessende `:core`-Rechnung ohne Drosselung — die Zahl auf dem
        // Endbildschirm soll ueber ALLE Punkte gehen, nicht ueber den Stand
        // der letzten Zwischenrechnung.
        val stats = computeStats(punkte)
        SpikeStatus.setzeCoreWerte(stats.distanceKm, stats.ascentM)
        journal.notiz(
            System.currentTimeMillis(),
            "core: %.3f km / %.0f m Anstieg aus %d Punkten".format(
                stats.distanceKm,
                stats.ascentM,
                punkte.size,
            ),
        )

        journal.ende(
            zeitMs = System.currentTimeMillis(),
            akkuProzent = akkustand(),
            punktzahl = punkte.size,
            grund = grund,
        )
        SpikeStatus.setzePhase(SpikeStatus.Phase.BEENDET)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheitere(text: String) {
        SpikeStatus.setzeFehler(text)
        SpikeStatus.setzePhase(SpikeStatus.Phase.FEHLER)
        journal.notiz(System.currentTimeMillis(), "Abbruch: $text")
        journal.ende(
            zeitMs = System.currentTimeMillis(),
            akkuProzent = akkustand(),
            punktzahl = punkte.size,
            grund = "fehler",
        )
        laeuft = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Nebenlaeufige Messreihen --------------------------------------------

    /**
     * Eigener Chronometer, sekuendlich. Siehe [SpikeStatus.laufzeitMs] fuer
     * die Begruendung, warum die Dauer nicht aus dem `ExerciseUpdate` kommt.
     */
    private fun starteChronometer() {
        chronometerJob?.cancel()
        chronometerJob = scope.launch {
            while (isActive) {
                val offen = pausiertSeitMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
                SpikeStatus.setzeLaufzeit(
                    SystemClock.elapsedRealtime() - startElapsedMs - pausierteMs - offen,
                )
                delay(1_000)
            }
        }
    }

    /** Akkustand jede Minute — daraus entsteht die Verbrauchskurve zu Frage 3. */
    private fun starteAkkuProtokoll() {
        akkuJob?.cancel()
        akkuJob = scope.launch {
            while (isActive) {
                val stand = akkustand()
                SpikeStatus.setzeAkku(stand)
                journal.akku(System.currentTimeMillis(), stand)
                delay(60_000)
            }
        }
    }

    private fun akkustand(): Int =
        getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1

    private fun registriereDisplayEmpfaenger() {
        if (displayRegistriert) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            displayEmpfaenger,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        displayRegistriert = true
    }

    // --- Vordergrund + Ongoing Activity --------------------------------------

    private fun gehInDenVordergrund() {
        val typ = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, baueBenachrichtigung(), typ)
    }

    private fun aktualisiereBenachrichtigung() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, baueBenachrichtigung())
    }

    /**
     * Vordergrund-Benachrichtigung mit aufgesetzter Ongoing Activity.
     *
     * Die Ongoing Activity ist reine Jetpack-Verzierung der Notification —
     * es gibt keine eigene Berechtigung dafuer. Was sie braucht, ist
     * POST_NOTIFICATIONS, und zwar fuer die Notification selbst.
     */
    private fun baueBenachrichtigung(): android.app.Notification {
        val tippIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = "${SpikeStatus.punktzahl.value} Punkte"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_spike_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(tippIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)

        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_spike_launcher)
            .setTouchIntent(tippIntent)
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
            .apply(applicationContext)

        return builder.build()
    }

    private fun legeBenachrichtigungskanalAn() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            },
        )
    }

    /** Alles, was den Versuch spaeter einem konkreten Geraet zuordnet. */
    private fun geraeteJson(): JsonObject = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("fingerprint", Build.FINGERPRINT)
    }

    companion object {
        const val ACTION_START = "de.trailscape.wear.START"
        const val ACTION_PAUSE = "de.trailscape.wear.PAUSE"
        const val ACTION_RESUME = "de.trailscape.wear.RESUME"
        const val ACTION_STOP = "de.trailscape.wear.STOP"

        private const val CHANNEL_ID = "spike"
        private const val NOTIFICATION_ID = 1

        /** Abstand zwischen zwei `:core`-Gegenrechnungen (siehe verarbeitePositionen). */
        private const val CORE_INTERVALL_MS = 10_000L

        /** Abstand zwischen zwei Notification-Aktualisierungen. */
        private const val BENACHRICHTIGUNG_INTERVALL_MS = 15_000L
    }
}
