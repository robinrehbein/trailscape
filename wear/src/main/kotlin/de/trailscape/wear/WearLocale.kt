package de.trailscape.wear

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.LanguagePreference
import de.trailscape.core.i18n.resolveAppLanguage

/**
 * Die Sprache der Uhr-App.
 *
 * Die Uhr folgt **nur ihrer eigenen Systemsprache** — Deutsch fuer `de-*`,
 * sonst Englisch, dieselbe Regel wie am Telefon (`resolveAppLanguage` aus
 * `:core`). Eine eigene Wahl gibt es auf der Uhr nicht, und die Wahl am
 * Telefon wird bewusst nicht uebertragen: Dafuer braeuchte es einen weiteren
 * Kanal ueber die Datenschicht, und wer die Uhr auf Deutsch stellt, liest dort
 * Deutsch.
 *
 * Wie am Telefon sind die Default-Ressourcen deutsch; eine Uhr auf
 * Franzoesisch bekaeme ohne Override Deutsch statt Englisch.
 */
object WearLocale {
    /** Die aufgeloeste Sprache der Uhr. */
    fun current(context: Context): AppLanguage {
        val locales = context.applicationContext.resources.configuration.locales
        val tags = (0 until locales.size()).map { locales[it].toLanguageTag() }
        return resolveAppLanguage(LanguagePreference.SYSTEM, tags)
    }

    /** Locale-Delta fuer `applyOverrideConfiguration`, `null` wenn es schon passt. */
    fun overrideConfiguration(base: Context): Configuration? {
        val language = current(base)
        val configured = base.resources.configuration.locales[0]
        if (configured != null && configured.language == language.locale.language) return null
        return Configuration().apply { setLocales(LocaleList(language.locale)) }
    }
}

/** Ein Kontext in der Sprache der Uhr — fuer den Aufzeichnungsdienst und seine Benachrichtigung. */
fun Context.localized(): Context {
    val language = WearLocale.current(this)
    val configured = resources.configuration.locales[0]
    if (configured != null && configured.language == language.locale.language) return this
    val config = Configuration(resources.configuration).apply { setLocales(LocaleList(language.locale)) }
    return createConfigurationContext(config)
}
