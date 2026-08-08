package de.trailscape.app.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Standortzugriff des Karten-Screens — Berechtigungen, eine einzelne Position
 * und ein Positions-Strom fuer die Navigation.
 *
 * Warum eigener Code und nicht der Standortstrom des
 * [de.trailscape.app.record.RecordingService]: Der Service ist ausschliesslich
 * fuer die *Aufzeichnung* zustaendig (Vordergrunddienst, Journal, Tour am
 * Ende). Der Karten-Screen braucht Standorte aber auch, wenn gerade **nicht**
 * aufgezeichnet wird — fuer den „Meine Position"-Knopf, fuer „Meine Position
 * als Start" in der Planung und fuer die Navigation. Genau so machte es das
 * Flutter-Original (`map_screen.dart` nutzte einen eigenen
 * `Geolocator.getPositionStream` unabhaengig vom `Recorder`).
 *
 * Wird dagegen aufgezeichnet, speist die Navigation ihre Positionen aus
 * [de.trailscape.app.record.RecordingRepository] — das spart einen zweiten,
 * parallelen GPS-Abonnenten (siehe `MapScreen.kt`).
 */

/** Berechtigungen fuer die Standortanzeige. */
internal val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Ob (mindestens grob) auf den Standort zugegriffen werden darf. */
internal fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Ob die Benachrichtigung des Aufzeichnungsdienstes gezeigt werden darf. Vor
 * Android 13 gibt es die Berechtigung nicht, dort ist die Antwort immer `true`.
 */
internal fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Die Berechtigungen, die fuer die naechste Aktion noch fehlen.
 *
 * @param forRecording nimmt ab Android 13 zusaetzlich `POST_NOTIFICATIONS`
 *   auf — ohne sie laeuft der Vordergrunddienst zwar, seine Notification
 *   bliebe aber unsichtbar.
 */
internal fun missingPermissions(context: Context, forRecording: Boolean): Array<String> {
    val missing = mutableListOf<String>()
    if (!hasLocationPermission(context)) {
        missing += LOCATION_PERMISSIONS
    }
    if (forRecording && !hasNotificationPermission(context)) {
        missing += Manifest.permission.POST_NOTIFICATIONS
    }
    return missing.toTypedArray()
}

/** Ob der Standortdienst des Geraets ueberhaupt eingeschaltet ist. */
internal fun isLocationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return runCatching {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)
}

/**
 * Ob das Geraet einen Kompass hat. Entscheidet ueber den Darstellungsmodus des
 * MapLibre-Standortpunkts (Richtungskegel vs. schlichter Punkt).
 */
internal fun hasCompass(context: Context): Boolean {
    val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
    return sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
        sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
}

/**
 * Holt eine einzelne, moeglichst frische Position. Liefert `null`, wenn das
 * nicht klappt (kein Fix, kein Recht, Dienst aus) — der Aufrufer zeigt dann
 * eine Meldung.
 *
 * Der Aufrufer muss die Berechtigung vorher geprueft haben
 * ([hasLocationPermission]); ohne sie liefert die Methode `null`, statt zu
 * werfen.
 */
@SuppressLint("MissingPermission")
internal suspend fun currentLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) return null

    val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    val fresh = suspendCancellableCoroutine<Location?> { continuation ->
        val cancellation = CancellationTokenSource()
        runCatching {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }.onFailure {
            if (continuation.isActive) continuation.resume(null)
        }
        continuation.invokeOnCancellation { cancellation.cancel() }
    }
    if (fresh != null) return fresh

    // Kein frischer Fix (z. B. in Gebaeuden): die zuletzt bekannte Position ist
    // besser als gar keine.
    return suspendCancellableCoroutine { continuation ->
        runCatching {
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }.onFailure {
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

/**
 * Fortlaufende Positionen fuer die Navigation — dieselben Parameter wie im
 * Flutter-Original (`LocationAccuracy.best`, `distanceFilter: 5`).
 *
 * Der Strom endet still, wenn die Berechtigung fehlt.
 */
@SuppressLint("MissingPermission")
internal fun locationUpdates(context: Context): Flow<Location> = callbackFlow {
    if (!hasLocationPermission(context)) {
        close()
        return@callbackFlow
    }

    val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, NAV_INTERVAL_MS)
        .setMinUpdateIntervalMillis(NAV_MIN_INTERVAL_MS)
        .setMinUpdateDistanceMeters(NAV_MIN_DISTANCE_M)
        .build()

    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { trySend(it) }
        }
    }

    val started = runCatching {
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }.isSuccess
    if (!started) {
        close()
        return@callbackFlow
    }

    awaitClose { runCatching { client.removeLocationUpdates(callback) } }
}

private const val NAV_INTERVAL_MS = 2_000L
private const val NAV_MIN_INTERVAL_MS = 1_000L
private const val NAV_MIN_DISTANCE_M = 5f
