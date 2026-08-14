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
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

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

private fun locationManager(context: Context): LocationManager? =
    context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

/**
 * Holt eine einzelne, moeglichst frische Position. Liefert `null`, wenn das
 * nicht klappt (kein Fix, kein Recht, Dienst aus) — der Aufrufer zeigt dann
 * eine Meldung.
 *
 * Der Aufrufer muss die Berechtigung vorher geprueft haben
 * ([hasLocationPermission]); ohne sie liefert die Methode `null`, statt zu
 * werfen.
 *
 * Hier — anders als im [de.trailscape.app.record.RecordingService] — kommt
 * [LocationManagerCompat] zum Einsatz: Ein sauberer *einmaliger* Fix ist
 * unterhalb von API 30 nicht mit einem Aufruf zu haben, sondern verlangt
 * einen selbstgebauten Einweg-Listener samt Abmeldung und Zeitschranke.
 * Genau das erledigt AndroidX bereits, und zwar in der ersten Adresse dafuer
 * (`androidx.core`, ohnehin im Projekt). Der Aufzeichnungsdienst kommt
 * dagegen mit den Plattformaufrufen direkt aus.
 *
 * Die Zeitschranke ist bewusst kuerzer als die 30 s, die
 * [LocationManagerCompat] von sich aus zulaesst: Hinter dieser Funktion
 * haengt der „Meine Position"-Knopf, und wer ihn drueckt, will keine halbe
 * Minute auf eine Antwort warten. Nach [CURRENT_LOCATION_TIMEOUT_MS] ohne
 * frischen Fix ist die zuletzt bekannte Position die bessere Antwort.
 */
@SuppressLint("MissingPermission")
internal suspend fun currentLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) return null
    val manager = locationManager(context) ?: return null

    val fresh = withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            val executor = Executor { command -> command.run() }
            runCatching {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    LocationManager.GPS_PROVIDER,
                    cancellation,
                    executor,
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
            continuation.invokeOnCancellation { runCatching { cancellation.cancel() } }
        }
    }
    if (fresh != null) return fresh

    // Kein frischer Fix (z. B. in Gebaeuden): die zuletzt bekannte Position ist
    // besser als gar keine.
    return lastKnownLocation(manager)
}

/**
 * Die juengste zuletzt bekannte Position ueber alle Provider des Geraets.
 *
 * Bewusst ueber [LocationManager.getAllProviders] und nicht ueber eine feste
 * Namensliste: Welche Provider es gibt, unterscheidet sich je nach
 * Android-Version und Geraet (`passive`, `network`, ab API 31 `fused`), und
 * fuer eine *zuletzt bekannte* Position ist jede Quelle recht — sie landet
 * nicht in einer Tour, sondern schiebt nur die Karte an die ungefaehr
 * richtige Stelle.
 */
@SuppressLint("MissingPermission")
private fun lastKnownLocation(manager: LocationManager): Location? =
    runCatching { manager.allProviders }.getOrNull()
        ?.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        ?.maxByOrNull { it.time }

/**
 * Fortlaufende Positionen fuer die Navigation — dieselben Parameter wie im
 * Flutter-Original (`LocationAccuracy.best`, `distanceFilter: 5`).
 *
 * Quelle ist wie beim Aufzeichnungsdienst allein
 * [LocationManager.GPS_PROVIDER]; die Begruendung steht dort ausfuehrlich
 * (`RecordingService.requestUpdates`). Hier wiegt sie sogar schwerer: Eine
 * um 30 m versetzte Netzposition wuerde die Abweichungswarnung der Navigation
 * grundlos ausloesen.
 *
 * Angemeldet wird ueber die klassische Signatur mit `minTimeMs`/`minDistanceM`,
 * die es auf allen unterstuetzten Versionen unveraendert gibt. Der zusaetzliche
 * Zweig ab API 31 wie im Aufzeichnungsdienst braeuchte es nur fuer Angaben, die
 * dieser Strom gar nicht macht — Intervall und Mindestabstand sind alles, was
 * er will, und beides kennt schon die alte Signatur.
 *
 * Der Strom endet still, wenn die Berechtigung fehlt oder sich niemand
 * anmelden laesst.
 */
@SuppressLint("MissingPermission")
internal fun locationUpdates(context: Context): Flow<Location> = callbackFlow {
    if (!hasLocationPermission(context)) {
        close()
        return@callbackFlow
    }

    val manager = locationManager(context)
    if (manager == null) {
        close()
        return@callbackFlow
    }

    // LocationListenerCompat statt des nackten LocationListener: Dessen
    // Zusatzmethoden sind erst ab API 30 `default` — ohne die AndroidX-
    // Variante gaebe es auf API 26 bis 29 einen AbstractMethodError.
    val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            trySend(location)
        }
    }

    val started = runCatching {
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            NAV_INTERVAL_MS,
            NAV_MIN_DISTANCE_M,
            listener,
            Looper.getMainLooper(),
        )
    }.isSuccess
    if (!started) {
        close()
        return@callbackFlow
    }

    awaitClose { runCatching { manager.removeUpdates(listener) } }
}

/**
 * Wartezeit auf einen frischen Fix, bevor [currentLocation] auf die zuletzt
 * bekannte Position ausweicht.
 */
private const val CURRENT_LOCATION_TIMEOUT_MS = 10_000L

private const val NAV_INTERVAL_MS = 2_000L
private const val NAV_MIN_DISTANCE_M = 5f
