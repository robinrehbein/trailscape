package io.github.robinrehbein.trailscape

import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.PermissionController
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine

// FlutterFragmentActivity statt FlutterActivity: Das health-Plugin fragt die
// Health-Connect-Berechtigungen ueber registerForActivityResult an und braucht
// dafuer eine ComponentActivity (Vorgabe aus dem README des Pakets).
class MainActivity : FlutterFragmentActivity(), Vo2MaxPermissionRequester {

    /** Callback des gerade laufenden Berechtigungsdialogs. */
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    // Der Launcher wird bewusst als Feld der Activity angelegt: Feldinitia-
    // lisierer laufen noch im Konstruktor, also lange vor onStart.
    // registerForActivityResult wirft eine IllegalStateException, sobald die
    // Activity gestartet ist — erst beim MethodCall zu registrieren, ginge
    // also schief. Das gilt hier besonders, weil configureFlutterEngine bei
    // FlutterFragmentActivity ueber eine (asynchron committete)
    // Fragment-Transaktion laeuft und damit erst nach onStart passieren kann.
    private val vo2MaxPermissionLauncher: ActivityResultLauncher<Set<String>> =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            val callback = pendingPermissionCallback
            pendingPermissionCallback = null
            callback?.invoke(
                granted.contains(HealthExtraChannel.VO2MAX_READ_PERMISSION)
            )
        }

    private val healthExtra = HealthExtraChannel(this, this)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        healthExtra.attachTo(flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        healthExtra.detach()
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onDestroy() {
        // Erst hier abbrechen: cleanUpFlutterEngine kann bei einem erneuten
        // Attach der Engine noch ein attachTo nach sich ziehen.
        healthExtra.dispose()
        pendingPermissionCallback = null
        super.onDestroy()
    }

    override fun requestVo2MaxPermission(onResult: (Boolean) -> Unit) {
        // Ein noch offener Dialog wird verworfen, damit kein Callback haengen
        // bleibt (die Dart-Seite wartet sonst ewig auf ihr Ergebnis).
        pendingPermissionCallback?.invoke(false)
        pendingPermissionCallback = onResult
        try {
            vo2MaxPermissionLauncher.launch(
                setOf(HealthExtraChannel.VO2MAX_READ_PERMISSION)
            )
        } catch (error: Throwable) {
            // Konnte der Dialog nicht starten, darf kein Callback zurueck-
            // bleiben — sonst antwortet der naechste Aufruf auf ein bereits
            // beantwortetes Result.
            pendingPermissionCallback = null
            throw error
        }
    }
}
