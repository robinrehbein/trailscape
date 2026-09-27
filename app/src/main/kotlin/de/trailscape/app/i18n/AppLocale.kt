package de.trailscape.app.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import de.trailscape.app.data.AppServices
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.LanguagePreference
import de.trailscape.core.i18n.coreTexts
import de.trailscape.core.i18n.languageOfTag
import de.trailscape.core.i18n.resolveAppLanguage

/**
 * # Die Sprache der App
 *
 * Trailscape spricht Deutsch und Englisch. Ohne eigene Wahl folgt die App der
 * Systemsprache — Deutsch fuer `de-*`, sonst Englisch (Aufloesung in `:core`,
 * [resolveAppLanguage]). Die Wahl in Mehr → Sprache wird so
 * gespeichert:
 *
 *  * **Ab Android 13** ueber [LocaleManager.setApplicationLocales]: Das
 *    System merkt sich die Sprache, zeigt sie auch in den App-Einstellungen
 *    von Android (dank `android:localeConfig`) und erzeugt die Activity
 *    selbst neu.
 *  * **Darunter** in einer eigenen, kleinen SharedPreferences-Datei; danach
 *    wird die Activity neu erzeugt.
 *
 * ## Warum ohne AppCompat
 * `AppCompatDelegate.setApplicationLocales` wirkt unter Android 13 nur mit
 * `AppCompatActivity` und AppCompat-Theme — das waere ein Umbau aller
 * Activities fuer eine Bibliothek, die sonst nichts beitraegt.
 *
 * ## Warum ein eigener Override
 * Die Default-Ressourcen (`values/`) sind deutsch. Ein Geraet auf
 * Franzoesisch bekaeme ohne Eingriff Deutsch; gewollt ist Englisch. Jede
 * Activity setzt deshalb in `attachBaseContext` per
 * `applyOverrideConfiguration` ein reines Locale-Delta ([overrideConfiguration]),
 * sobald die aufgeloeste Sprache von der Konfiguration abweicht. Bewusst
 * `applyOverrideConfiguration` und kein `createConfigurationContext`: Ein
 * Override-Delta reicht der ResourcesManager bei spaeteren
 * Konfigurationswechseln (Dunkelmodus) weiter, ein fest gewickelter
 * Kontext wuerde davon nichts mitbekommen.
 *
 * Dienste und Worker haben keine Activity; sie lesen Texte ueber
 * [localized] (ein einmaliger Konfigurationskontext je Aufbau).
 */
object AppLocale {
    /** Eigene Datei, damit die Wahl nicht an den Sicherungs-Einstellungen haengt. */
    private const val PREFS_NAME = "trailscape_locale"
    private const val KEY_LANGUAGE = "language"

    /** Die gespeicherte Wahl (System/Deutsch/English). */
    fun preference(context: Context): LanguagePreference {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = localeManager(context)?.applicationLocales
            if (locales == null || locales.isEmpty) return LanguagePreference.SYSTEM
            return when (languageOfTag(locales[0].toLanguageTag())) {
                AppLanguage.DE -> LanguagePreference.DE
                AppLanguage.EN -> LanguagePreference.EN
            }
        }
        val stored = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, null)
        }.getOrNull()
        return LanguagePreference.fromTag(stored)
    }

    /**
     * Nimmt eine unter Android 12/12L gespeicherte Wahl ins System mit.
     *
     * Warum: Ab Android 13 liest [preference] nur noch
     * [LocaleManager.getApplicationLocales]. Wer vor dem System-Update
     * „English" auf einem deutschen Geraet gewaehlt hatte, fiele danach
     * stillschweigend auf die Systemsprache zurueck — die alte Datei liest
     * niemand mehr. Einmal beim Start: Hat das System noch keine eigene
     * App-Sprache, bekommt es die gespeicherte; danach wird der Schluessel
     * entfernt (eine im System gesetzte Wahl hat Vorrang).
     */
    fun migrateLegacyPreference(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        val stored = prefs.getString(KEY_LANGUAGE, null) ?: return
        val manager = localeManager(context) ?: return
        val legacy = LanguagePreference.fromTag(stored)
        if (legacy != LanguagePreference.SYSTEM && manager.applicationLocales.isEmpty) {
            manager.applicationLocales = LocaleList.forLanguageTags(legacy.tag)
        }
        prefs.edit().remove(KEY_LANGUAGE).apply()
    }

    /**
     * Speichert die Wahl und wendet sie an. Ab Android 13 erzeugt das System
     * die Activity selbst neu; darunter uebernimmt das [Activity.recreate].
     */
    fun setPreference(activity: Activity, preference: LanguagePreference) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager(activity)?.applicationLocales = when (preference) {
                LanguagePreference.SYSTEM -> LocaleList.getEmptyLocaleList()
                else -> LocaleList.forLanguageTags(preference.tag)
            }
            refresh(activity)
            return
        }
        activity.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, preference.tag)
            .apply()
        refresh(activity)
        activity.recreate()
    }

    /**
     * Die Sprache, in der die App gerade spricht.
     *
     * Die System-Sprachliste kommt aus der Konfiguration des
     * Application-Kontexts: Die ist nie von einem Activity-Override
     * betroffen und spiegelt ab Android 13 eine per-App-Sprache bereits
     * wider (dann ist [preference] ohnehin ausdruecklich).
     */
    fun current(context: Context): AppLanguage {
        val locales = context.applicationContext.resources.configuration.locales
        val tags = (0 until locales.size()).map { locales[it].toLanguageTag() }
        return resolveAppLanguage(preference(context), tags)
    }

    /**
     * Das Konfigurations-Delta fuer `applyOverrideConfiguration` — nur die
     * Locale, sonst nichts. `null`, wenn die Konfiguration von [base] schon
     * die richtige Sprache hat (dann bleibt z. B. `de-AT` unangetastet).
     */
    fun overrideConfiguration(base: Context): Configuration? {
        val language = current(base)
        val configured = base.resources.configuration.locales[0]
        if (configured != null && languageOfTag(configured.toLanguageTag()) == language &&
            configured.language == language.locale.language
        ) {
            return null
        }
        return Configuration().apply { setLocales(LocaleList(language.locale)) }
    }

    /** Uebertraegt die aktuelle Sprache in [AppServices.appLanguage]. */
    fun refresh(context: Context) {
        AppServices.setAppLanguage(current(context))
    }

    /** Die `:core`-Texte in der aktuellen Sprache — fuer Dienste und Worker. */
    fun coreTexts(context: Context): CoreTexts = coreTexts(current(context))

    private fun localeManager(context: Context): LocaleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
        } else {
            null
        }
}

/**
 * Ein Kontext, dessen Ressourcen in der App-Sprache antworten — fuer Dienste,
 * Worker und Benachrichtigungen, die keine Activity mit Override haben.
 *
 * Bei jedem Aufbau (einer Benachrichtigung, einer Meldung) neu holen, nicht
 * zwischenspeichern: So greift ein Sprachwechsel beim naechsten Mal. Kein
 * `attachBaseContext` in Diensten — der wuerde die Sprache fuer die ganze
 * Laufzeit des Dienstes festnageln.
 */
fun Context.localized(): Context = localizedFor(AppLocale.current(this))

/** Wie [localized], aber fuer eine bereits bekannte [language]. */
fun Context.localizedFor(language: AppLanguage): Context {
    val configured = resources.configuration.locales[0]
    if (configured != null && configured.language == language.locale.language) return this
    val config = Configuration(resources.configuration).apply { setLocales(LocaleList(language.locale)) }
    return createConfigurationContext(config)
}
