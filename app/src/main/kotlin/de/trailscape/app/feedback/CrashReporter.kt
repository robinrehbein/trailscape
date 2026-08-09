package de.trailscape.app.feedback

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.time.ZoneId

/**
 * Lokale Absturzberichte — **ohne jeden Drittanbieter-Dienst**.
 *
 * Trailscape hat kein Crashlytics, kein Sentry, keine Telemetrie. Statt einen
 * Absturz irgendwohin zu senden, legt [install] einen Bericht als Textdatei
 * unter `<filesDir>/crash/last-crash.txt` ab. Beim naechsten Start fragt
 * `CrashReportPrompt` (siehe `CrashReportPrompt.kt`), ob der Nutzer ihn
 * ansehen, auf GitHub melden, teilen oder verwerfen moechte. Verschickt wird
 * **nur**, was der Nutzer selbst verschickt.
 *
 * ## Regeln im Absturzpfad
 * Der Handler laeuft in einem Prozess, der gleich stirbt. Deshalb:
 *  * **synchron** schreiben — keine Coroutine, kein Executor, kein
 *    `WorkManager`. Alles davon braucht einen Scheduler, den es in dieser
 *    Sekunde nicht mehr gibt.
 *  * **nichts nachladen**: Versions- und Geraetedaten werden schon bei
 *    [install] eingesammelt und gemerkt, damit im Crash-Fall kein
 *    `PackageManager`-Aufruf mehr noetig ist.
 *  * **niemals selbst werfen**: alles in `runCatching`. Ein Fehler beim
 *    Schreiben des Berichts darf den urspruenglichen Absturz nicht verdecken.
 *  * am Ende **immer** an den vorherigen Handler weiterreichen — Android soll
 *    den Prozess wie gewohnt beenden. Kein „App am Leben halten"-Trick.
 *
 * ## Datenschutz
 * Der Bericht enthaelt ausschliesslich Technik (siehe `ReportFormat.kt`):
 * keine Standortpunkte, keine Touren, keine Gesundheitsdaten, keine
 * Sync-Zugangsdaten. Er liegt im privaten `filesDir` der App und ist damit
 * fuer andere Apps unlesbar.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    /** Unterverzeichnis in `filesDir`, in dem der Bericht liegt. */
    const val CRASH_DIR_NAME: String = "crash"

    /** Dateiname des letzten Berichts (genau einer, der aelteste faellt raus). */
    const val CRASH_FILE_NAME: String = "last-crash.txt"

    /**
     * Obergrenze fuer den geschriebenen Stacktrace. Ein durchgedrehter
     * rekursiver Aufruf erzeugt sonst megabytegrosse Dateien im `filesDir`.
     */
    private const val MAX_STACK_TRACE_CHARS = 200_000

    @Volatile
    private var installed = false

    /** Die Absturzdatei — auch fuer Aufrufer, die nur pruefen/loeschen wollen. */
    fun crashFile(context: Context): File =
        File(File(context.filesDir, CRASH_DIR_NAME), CRASH_FILE_NAME)

    /**
     * Haengt den Berichts-Handler vor den bestehenden. Genau einmal beim
     * App-Start aufrufen (siehe `TrailscapeApplication.onCreate`); weitere
     * Aufrufe sind wirkungslos.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        // Jetzt einsammeln, nicht erst im Absturz: PackageManager-Aufrufe im
        // sterbenden Prozess sind genau die Sorte Risiko, die man dort nicht
        // eingeht.
        val deviceInfo = currentDeviceInfo(appContext)
        val file = crashFile(appContext)
        val zone = runCatching { ZoneId.systemDefault() }.getOrDefault(ZoneId.of("UTC"))

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(file, deviceInfo, zone, thread, throwable) }
                .onFailure { Log.w(TAG, "Absturzbericht konnte nicht geschrieben werden", it) }
            // Die App darf normal sterben: Der Standard-Handler von Android
            // erledigt ANR-Dialog, Logcat-Eintrag und Prozessende.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Schreibt den Bericht synchron. Bewusst ein einziger
     * [File.writeText]-Aufruf: ein `open`, ein `write`, ein `close`.
     */
    private fun writeReport(
        file: File,
        deviceInfo: DeviceInfo,
        zone: ZoneId,
        thread: Thread,
        throwable: Throwable,
    ) {
        val runtime = Runtime.getRuntime()
        val report = buildCrashReport(
            info = deviceInfo,
            timestamp = formatReportTimestamp(System.currentTimeMillis(), zone),
            threadName = thread.name,
            stackTrace = throwable.stackTraceToString().take(MAX_STACK_TRACE_CHARS),
            memory = MemoryInfo(
                freeBytes = runtime.freeMemory(),
                totalBytes = runtime.totalMemory(),
                maxBytes = runtime.maxMemory(),
            ),
        )
        file.parentFile?.mkdirs()
        file.writeText(report, Charsets.UTF_8)
    }

    /**
     * Liest einen liegengebliebenen Bericht, oder `null` wenn keiner da ist.
     * Blockiert (Datei-I/O) — vom Aufrufer auf `Dispatchers.IO` aufrufen.
     */
    fun readPendingReport(context: Context): String? {
        val file = crashFile(context)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse {
                Log.w(TAG, "Absturzbericht nicht lesbar — wird verworfen", it)
                file.delete()
                null
            }
            ?.takeIf { it.isNotBlank() }
    }

    /** Loescht den Bericht („Verwerfen" im Dialog). */
    fun clearPendingReport(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    /**
     * Technische Eckdaten der laufenden Installation. Faellt bei jedem Problem
     * auf Platzhalter zurueck — ein unvollstaendiger Bericht ist besser als
     * keiner.
     *
     * Die Version kommt ueber den [android.content.pm.PackageManager] statt
     * ueber `BuildConfig`, weil `:app` das `buildConfig`-Feature nicht
     * aktiviert (gleiche Begruendung wie in `ui/more/AboutCard.kt`).
     */
    fun currentDeviceInfo(context: Context): DeviceInfo {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return DeviceInfo(
            appVersionName = packageInfo?.versionName ?: DeviceInfo.UNKNOWN.appVersionName,
            appVersionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
            androidRelease = Build.VERSION.RELEASE ?: "unbekannt",
            androidSdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "unbekannt",
            model = Build.MODEL ?: "unbekannt",
        )
    }
}
