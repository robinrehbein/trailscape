package de.trailscape.app.feedback

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.RollingDiagFile
import java.io.File

/**
 * Android-Seite des Diagnose-Logs ([DiagLog] in `:core`): Speicherort setzen,
 * im Debug-Build nach Logcat spiegeln, fruehere Prozessenden nachtragen.
 *
 * Das Log liegt unter `<filesDir>/diag/` — privat, **nicht** im Auto Backup
 * (siehe `res/xml/backup_rules.xml` und `data_extraction_rules.xml`) und damit
 * nur auf diesem Geraet. Eine eigene „Alle Daten zuruecksetzen"-Funktion hat
 * die App nicht; „Daten loeschen" in den Android-Einstellungen und die
 * Deinstallation raeumen `filesDir` samt Log ab. Zusaetzlich loescht der
 * Knopf im Problembericht das Log gezielt ([clear]).
 */
object AppDiagnostics {

    private const val TAG = "TrailscapeDiag"

    /**
     * Merker, bis zu welchem Zeitpunkt `ApplicationExitInfo` schon
     * protokolliert wurde (nur eine Zahl). Liegt im selben Verzeichnis wie das
     * Log und teilt damit dessen Backup-Ausnahme.
     */
    private const val EXIT_SEEN_FILE_NAME = "exit-seen"

    /** Wie viele fruehere Prozessenden hoechstens pro Start nachgetragen werden. */
    private const val MAX_EXIT_INFOS = 5

    /** Obergrenze fuer das gelesene Absturzprotokoll des Systems. */
    private const val MAX_TOMBSTONE_BYTES = 8 * 1024 * 1024

    @Volatile
    private var installed = false

    fun diagDir(context: Context): File =
        File(context.applicationContext.filesDir, RollingDiagFile.DIR_NAME)

    /** Ob der laufende Build debuggable ist (Debug-Variante). */
    fun isDebuggable(context: Context): Boolean =
        (context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Setzt den Speicherort und protokolliert den Start. Direkt nach
     * `CrashReporter.install` aufrufen — keine Datei-Operation ausser dem
     * Anhaengen einer Zeile.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        DiagLog.shared.attach(RollingDiagFile(diagDir(appContext)))
        if (isDebuggable(appContext)) {
            // Nur im Debug-Build: Im Release soll Logcat nicht mitlesen koennen,
            // was die App protokolliert (andere Apps sehen Logcat zwar nicht,
            // per adb angeschlossene Rechner aber schon).
            DiagLog.shared.mirror = { line -> Log.d(TAG, line) }
        }
        DiagLog.shared.log(DiagEvent.APP_START, code = Build.VERSION.SDK_INT)
    }

    /**
     * Traegt neue Eintraege aus `ApplicationExitInfo` nach (ab Android 11):
     * Grund, Wichtigkeit und Zeitpunkt, wie das System das Ende der
     * vorherigen Prozesse gesehen hat. Das faengt, was der
     * `uncaughtExceptionHandler` nie sieht — ANRs, Low-Memory-Kills, native
     * Abstuerze, „vom Hersteller-Energiesparen beendet".
     *
     * Blockiert (Datei- und Binder-I/O) — aus `Dispatchers.IO` aufrufen.
     */
    @Synchronized
    fun recordExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { recordExitReasonsApi30(context.applicationContext) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordExitReasonsApi30(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val marker = File(diagDir(context), EXIT_SEEN_FILE_NAME)
        val seenUntil = runCatching { marker.readText().trim().toLong() }.getOrDefault(0L)

        val fresh = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_INFOS)
            .filter { it.timestamp > seenUntil }
            .sortedBy { it.timestamp }
        if (fresh.isEmpty()) return

        // Nur Zahlen: `description` und `processName` bleiben draussen — die
        // Beschreibung ist Freitext des Systems und kann alles enthalten.
        for (info in fresh) {
            DiagLog.shared.log(
                DiagEvent.APP_EXIT_INFO,
                code = info.reason,
                count = info.importance.toLong(),
                atMs = info.timestamp,
            )
        }
        // Nativer Absturz (z. B. Filament bricht ab, weil der Grafiktreiber einen
        // Shader ablehnt): Den sieht der `uncaughtExceptionHandler` nie. Ab
        // Android 12 hebt das System sein Absturzprotokoll auf — daraus entsteht
        // derselbe lokale Bericht wie bei einem Kotlin-Absturz, den der Nutzer
        // beim naechsten Start ansehen und selbst weitergeben kann.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fresh.lastOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
                ?.let { runCatching { writeNativeCrashReport(context, it) } }
        }
        marker.parentFile?.mkdirs()
        marker.writeText(fresh.last().timestamp.toString())
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun writeNativeCrashReport(context: Context, info: ApplicationExitInfo) {
        val file = CrashReporter.crashFile(context)
        // Ein Kotlin-Bericht desselben Absturzes ist genauer — nicht ueberschreiben.
        if (file.isFile) return
        val bytes = info.traceInputStream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < MAX_TOMBSTONE_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return
        val text = TombstoneText.extract(bytes) ?: return
        val runtime = Runtime.getRuntime()
        val zone = runCatching { java.time.ZoneId.systemDefault() }.getOrDefault(java.time.ZoneId.of("UTC"))
        val report = buildCrashReport(
            info = CrashReporter.currentDeviceInfo(context),
            timestamp = formatReportTimestamp(info.timestamp, zone),
            threadName = "nativ",
            stackTrace = "Nativer Absturz (Absturzprotokoll des Systems)\n$text",
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
     * Loescht das Log (Knopf im Problembericht). Blockiert.
     *
     * Der Merker fuer `ApplicationExitInfo` bleibt bewusst stehen — sonst
     * tauchten die eben geloeschten Prozessenden beim naechsten Start wieder
     * auf, und „Loeschen" saehe aus, als haette es nicht funktioniert.
     */
    fun clear() {
        DiagLog.shared.clear()
    }
}
