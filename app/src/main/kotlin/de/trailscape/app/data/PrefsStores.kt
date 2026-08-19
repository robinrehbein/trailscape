package de.trailscape.app.data

import android.content.Context
import android.content.SharedPreferences
import de.trailscape.core.HealthSyncStore
import de.trailscape.core.KeyValueStore
import de.trailscape.core.TrainingPlanStore
import de.trailscape.core.healthSyncStorageKey
import de.trailscape.core.trainingPlanStorageKey

/**
 * `SharedPreferences`-basierte Implementierungen der `:core`-Speicher-
 * Schnittstellen [KeyValueStore], [HealthSyncStore] und [TrainingPlanStore].
 *
 * Namensraum: alle drei Stores teilen sich EINE `SharedPreferences`-Datei
 * ([PREFS_FILE_NAME]) im normalen App-eigenen Speicherbereich
 * (`Context.getSharedPreferences`, kein `MODE_MULTI_PROCESS` o. Ae. noetig —
 * es gibt nur einen Prozess). Das ist bewusst NICHT der Namespace, den
 * `shared_preferences` unter Flutter auf Android anlegt (`"FlutterSharedPreferences"`
 * mit `"flutter."`-Praefix vor jedem Schluessel): diese native App hat keine
 * Flutter-Engine und braucht daher keine Kompatibilitaet zu deren Ablage.
 * Die Uebernahme bestehender Nutzerdaten (Touren, Trainingsplan,
 * Sync-Konfiguration) laeuft ausschliesslich ueber den Backup-Import-Kanal
 * (JSON-Dateien, siehe [RideStorage]) — NICHT ueber das Auslesen der
 * Flutter-`SharedPreferences`-Datei.
 *
 * Schluessel:
 *  * [HealthSyncStore] nutzt [healthSyncStorageKey] ("trailscape.healthsync")
 *    — dieser Schluessel ist bereits in `:core` (`HealthSyncLogic.kt`) als
 *    Konstante exportiert und deren KDoc verlangt ausdruecklich denselben
 *    Namen auf Android, kein Grund, hier abzuweichen.
 *  * [TrainingPlanStore] nutzt [trainingPlanStorageKey] ("trailscape.plan")
 *    aus `:core` (`Training.kt`) aus demselben Grund.
 *  * [KeyValueStore] ist generisch (beliebiger Schluessel pro Aufruf) und
 *    reicht Schluessel/Werte 1:1 an `SharedPreferences` durch. `SyncClient`
 *    in `:core` nutzt intern den eigenen Schluessel `"trailscape.sync"`
 *    (privates `STORAGE_KEY` in `SyncClient.kt`) — auch das ist bereits ein
 *    `de.trailscape.core`-eigener Name, keine Flutter-Altlast.
 *
 * Backup: Die Datei `trailscape_prefs.xml` ist in `res/xml/backup_rules.xml`
 * und `res/xml/data_extraction_rules.xml` ausdruecklich vom Auto Backup und
 * vom Geraetewechsel-Transfer AUSGESCHLOSSEN — sie enthaelt das Sync-Token im
 * Klartext und die Vitalhistorie (Begruendung dort und in `PRIVACY.md`,
 * Abschnitt 7). Wer hier einen neuen Schluessel anlegt, darf sich also darauf
 * verlassen, dass er das Geraet nicht ueber das Systembackup verlaesst.
 */
private const val PREFS_FILE_NAME = "trailscape_prefs"

internal fun trailscapePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

/** Generischer String-Key-Value-Speicher, siehe Klassendoc oben. */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * Persistiert den Zeitstempel des letzten Health-Connect-Imports.
 *
 * `SharedPreferences` kennt kein nullable `Long` — `null` wird deshalb ueber
 * `contains(key)` abgebildet statt ueber einen Sentinel-Wert wie `-1`, damit
 * ein tatsaechlicher (theoretisch negativer, vor 1970 liegender) Zeitstempel
 * nicht mit "nicht gesetzt" verwechselt werden kann.
 */
class PrefsHealthSyncStore(private val prefs: SharedPreferences) : HealthSyncStore {
    override fun lastImportAtMs(): Long? =
        if (prefs.contains(healthSyncStorageKey)) prefs.getLong(healthSyncStorageKey, 0L) else null

    override fun setLastImportAtMs(value: Long?) {
        if (value == null) {
            prefs.edit().remove(healthSyncStorageKey).apply()
        } else {
            prefs.edit().putLong(healthSyncStorageKey, value).apply()
        }
    }
}

/** Persistiert den zuletzt gespeicherten Trainingsplan als JSON-String. */
class PrefsTrainingPlanStore(private val prefs: SharedPreferences) : TrainingPlanStore {
    override fun read(): String? = prefs.getString(trainingPlanStorageKey, null)

    override fun write(value: String) {
        prefs.edit().putString(trainingPlanStorageKey, value).apply()
    }

    override fun remove() {
        prefs.edit().remove(trainingPlanStorageKey).apply()
    }
}
