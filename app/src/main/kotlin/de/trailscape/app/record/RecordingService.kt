package de.trailscape.app.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import de.trailscape.app.R

/**
 * Platzhalter fuer den kommenden Aufzeichnungs-Service.
 *
 * Baut nur das Minimum, das fuer einen gueltigen Foreground-Service mit Typ
 * `location` noetig ist (Notification-Channel + `startForeground`), damit
 * Manifest und Build gruen bleiben. Die eigentliche Aufzeichnungslogik
 * (GPS-Sampling via [com.google.android.gms.location.FusedLocationProviderClient],
 * Ride-Aufbau ueber `Ride`/`TrackPoint` aus `:core`, Pause/Resume, Persistenz
 * ueber `RideStorage`) folgt im Recording-Service-Parallelstrang.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
    }
}
