package de.trailscape.app.health

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred

/**
 * Bruecke zwischen dem synchronen
 * [HealthGateway.requestPermissions][de.trailscape.core.HealthGateway.requestPermissions]
 * und dem Activity-Result-API von Health Connect.
 *
 * Warum es diese Bruecke ueberhaupt braucht: Der Berechtigungsdialog laeuft
 * ueber `PermissionController.createRequestPermissionResultContract()`, und
 * `registerForActivityResult` darf **nur vor `onStart`** aufgerufen werden —
 * also im Konstruktor bzw. Feldinitialisierer der Activity, nicht erst, wenn
 * ein Sync-Lauf die Rechte braucht. Genau dasselbe Muster nutzt schon die
 * Flutter-`MainActivity` fuer den VO2max-Dialog.
 *
 * Aufteilung:
 *
 *  * [HealthPermissionRequester] wird als Feld der `MainActivity` angelegt,
 *    registriert dort den Contract und meldet sich beim [HealthPermissionHub]
 *    an bzw. beim Zerstoeren wieder ab.
 *  * [HealthPermissionHub] ist der prozessweite Zugriffspunkt, den
 *    [HealthConnectGateway] benutzt — es kennt die Activity nicht, sondern nur
 *    „ist gerade jemand da, der einen Dialog zeigen kann?".
 */
class HealthPermissionRequester(activity: ComponentActivity) : DefaultLifecycleObserver {

    // Feldinitialisierer: laeuft noch im Activity-Konstruktor und damit lange
    // vor onStart — spaeter wuerfe registerForActivityResult eine
    // IllegalStateException.
    //
    // InvalidFragmentVersionForActivityResult ist hier ein Fehlalarm: Lint
    // sieht androidx.fragment:1.1.0 auf dem Klassenpfad (transitiv ueber
    // play-services-location) und warnt, weil dessen FragmentActivity
    // super.onRequestPermissionsResult() nicht aufruft. Trailscape benutzt
    // aber gar keine Fragmente — die Activity hier ist eine reine
    // ComponentActivity aus androidx.activity 1.13.0, und nur deren
    // Registry wird angesprochen.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val launcher: ActivityResultLauncher<Set<String>> =
        activity.registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            HealthPermissionHub.deliver(granted)
        }

    init {
        HealthPermissionHub.attach(this)
        activity.lifecycle.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        HealthPermissionHub.detach(this)
    }

    /** Zeigt den Dialog. Darf nur vom Main-Thread aufgerufen werden. */
    internal fun launch(permissions: Set<String>) {
        launcher.launch(permissions)
    }
}

/**
 * Prozessweiter Zugriffspunkt auf den gerade verfuegbaren
 * [HealthPermissionRequester].
 *
 * Bewusst ein `object` und kein injizierter Dienst: Der Berechtigungsdialog
 * haengt an *der* im Vordergrund stehenden Activity, davon gibt es genau eine.
 * Der Zustand ist entsprechend klein — der aktive Requester und die eine
 * offene Anfrage.
 */
object HealthPermissionHub {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var requester: HealthPermissionRequester? = null
    private var pending: CompletableDeferred<Set<String>>? = null

    /** Ob gerade eine Activity da ist, die einen Dialog zeigen koennte. */
    val isAvailable: Boolean
        get() = synchronized(lock) { requester != null }

    internal fun attach(value: HealthPermissionRequester) {
        synchronized(lock) { requester = value }
    }

    internal fun detach(value: HealthPermissionRequester) {
        val orphaned = synchronized(lock) {
            if (requester !== value) {
                return
            }
            requester = null
            val open = pending
            pending = null
            open
        }
        // Verschwindet die Activity mit offenem Dialog, darf der wartende
        // Aufrufer nicht ewig haengen bleiben.
        orphaned?.complete(emptySet())
    }

    /** Ergebnis des Contracts (Main-Thread). */
    internal fun deliver(granted: Set<String>) {
        val open = synchronized(lock) {
            val value = pending
            pending = null
            value
        }
        open?.complete(granted)
    }

    /**
     * Zeigt den Health-Connect-Berechtigungsdialog fuer [permissions] und
     * wartet auf das Ergebnis.
     *
     * Liefert die Menge der danach erteilten Rechte, wie der Contract sie
     * meldet — bei Abbruch eine leere Menge. `null`, wenn gerade keine Activity
     * da ist, die den Dialog zeigen koennte (App im Hintergrund).
     */
    suspend fun request(permissions: Set<String>): Set<String>? {
        val deferred = CompletableDeferred<Set<String>>()
        var found: HealthPermissionRequester? = null
        var replaced: CompletableDeferred<Set<String>>? = null

        synchronized(lock) {
            val current = requester
            if (current != null) {
                found = current
                // Eine noch offene Anfrage wird verworfen, damit kein Aufrufer
                // auf ein Ergebnis wartet, das nie kommt.
                replaced = pending
                pending = deferred
            }
        }

        val target = found ?: return null
        replaced?.complete(emptySet())

        mainHandler.post {
            try {
                target.launch(permissions)
            } catch (_: Throwable) {
                // z. B. IllegalStateException, wenn die Activity inzwischen weg
                // ist. Ohne dieses complete() wartet der Aufrufer ewig.
                deliver(emptySet())
            }
        }

        return deferred.await()
    }
}
