package de.trailscape.app.record

import android.os.SystemClock
import java.io.File

/**
 * Die Android-Umsetzung von [HeartbeatClock] — die einzige Stelle, an der das
 * Lebenszeichen der Aufzeichnung Android beruehrt.
 *
 * Bewusst eine eigene, winzige Datei: [RecordingJournal] und [RecordingLogic]
 * bleiben dadurch frei von Android-Importen und damit als reine JVM-Tests
 * pruefbar (`:app` hat kein Robolectric, siehe `app/build.gradle.kts`).
 *
 * ## Woher die Boot-Kennung kommt
 * Android hat keine oeffentliche API dafuer. Der Kernel legt sie unter
 * `/proc/sys/kernel/random/boot_id` ab — eine UUID, die sich bei jedem
 * Systemstart aendert und die aus einer App gelesen werden darf. Klappt das
 * nicht (SELinux-Regel eines Herstellers, kuenftige Einschraenkung), bleibt sie
 * `null`; [bewerteLebenszeichen] faellt dann auf den Vergleich der monotonen
 * Uhr allein zurueck, und wenn auch die fehlt, auf die Wanduhr. Es gibt also
 * keinen Fall, in dem ein fehlender Wert die Absturzsicherung ausser Kraft
 * setzt — nur schrittweise weniger Gewissheit.
 *
 * Gelesen wird die Datei genau einmal je Prozess: Sie aendert sich waehrend
 * eines Boot-Vorgangs per Definition nicht, und das Lebenszeichen wird alle
 * paar Sekunden geschrieben.
 */
object AndroidHeartbeatClock : HeartbeatClock {

    override fun wallClockMs(): Long = System.currentTimeMillis()

    /**
     * `elapsedRealtime()` und nicht `uptimeMillis()`: Die App zeichnet Touren
     * ueber Stunden auf, in denen das Geraet zwischen den GPS-Meldungen
     * schlafen darf. `uptimeMillis()` steht im Suspend still und wuerde ein
     * stundenaltes Lebenszeichen als frisch ausweisen.
     */
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun bootId(): String? = bootIdCache

    private val bootIdCache: String? by lazy {
        try {
            File("/proc/sys/kernel/random/boot_id").readText(Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
