package de.trailscape.app.data

import android.content.Context
import de.trailscape.core.AchievementProgressData
import de.trailscape.core.KeyValueStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Der kleine gespeicherte Stand der Erfolge ([AchievementProgressData], unter
 * 1 kB): hoechste Stufe, Punktebeginn, Pausenwochen, festgehaltene und
 * gesehene Abzeichen, „Erfolge anzeigen". Alles andere rechnet `:core` aus den
 * Touren (`Achievements.kt`, `docs/erfolge.md`).
 *
 * Eine Zeile JSON im [KeyValueStore] unter [STORAGE_KEY]; im Backup derselbe
 * Inhalt unter `progress`. Schreiben geht ueber `SharedPreferences.apply()`
 * und blockiert nicht.
 */
class ErfolgeStore(private val keyValueStore: KeyValueStore) {

    private val _state = MutableStateFlow(read())

    /** Der aktuelle Stand; aendert sich nur ueber [update]. */
    val state: StateFlow<AchievementProgressData> = _state.asStateFlow()

    val value: AchievementProgressData get() = _state.value

    /**
     * Aendert den Stand. Liefert [transform] dasselbe Objekt zurueck (die
     * Helfer in [AchievementProgressData] tun das, wenn sich nichts aendert),
     * wird nichts geschrieben.
     */
    fun update(transform: (AchievementProgressData) -> AchievementProgressData) {
        while (true) {
            val old = _state.value
            val new = transform(old)
            if (new == old) return
            if (_state.compareAndSet(old, new)) {
                runCatching { keyValueStore.setString(STORAGE_KEY, new.encode()) }
                return
            }
        }
    }

    /** „Erfolge anzeigen". */
    fun setShown(shown: Boolean) = update { it.copy(hidden = !shown) }

    /**
     * Legt den Punktebeginn einmalig fest — auf den Tag der Installation
     * (Konzept 2.7): Importierte Touren von davor zaehlen fuer Abzeichen, nicht
     * fuer Punkte. Ein schon gesetzter Tag bleibt.
     */
    fun ensurePointsSince(context: Context) {
        if (_state.value.pointsSince != null) return
        val date = installDate(context) ?: return
        update { if (it.pointsSince == null) it.copy(pointsSince = date) else it }
    }

    /** Nach dem Wiederherstellen eines Backups: zusammenfuehren statt ueberschreiben. */
    fun restore(fromBackup: AchievementProgressData) = update { it.mergedWith(fromBackup) }

    private fun read(): AchievementProgressData =
        runCatching { AchievementProgressData.decode(keyValueStore.getString(STORAGE_KEY)) }.getOrNull()
            ?: AchievementProgressData()

    companion object {
        /** Schluessel im [KeyValueStore]. */
        const val STORAGE_KEY: String = "trailscape.erfolge.progress.v1"

        /** Der eine Stand der App, auf [AppServices.keyValueStore]. */
        val shared: ErfolgeStore by lazy { ErfolgeStore(AppServices.keyValueStore) }

        /** Tag der Erstinstallation in der Zeitzone des Geraets; `null`, wenn unbekannt. */
        internal fun installDate(context: Context): LocalDate? = runCatching {
            @Suppress("DEPRECATION")
            val ms = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
            if (ms <= 0L) null else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
    }
}
