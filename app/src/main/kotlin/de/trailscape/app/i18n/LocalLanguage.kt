package de.trailscape.app.i18n

import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.languageOfTag

/**
 * Die Sprache der gerade komponierten Oberflaeche.
 *
 * Bereitgestellt von `TrailscapeApp()` aus der Konfiguration der Activity
 * (siehe [languageOf]) — nicht aus [AppLocale.current]: Die Activity-
 * Konfiguration traegt den Override bereits und ist in Robolectric-Tests
 * genau das, was die Qualifier setzen.
 *
 * Default Deutsch, damit Vorschauen und Einzeltests ohne Provider in der
 * Ausgangssprache der App zeichnen.
 */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.DE }

/** Die `:core`-Texte zur [LocalAppLanguage]. */
val LocalCoreTexts = staticCompositionLocalOf<CoreTexts> { CoreTextsDe }

/** Zahl- und Datumsformate zur [LocalAppLanguage]. */
val LocalAppFormats = staticCompositionLocalOf { AppFormats(AppLanguage.DE) }

/**
 * Sprache einer Konfiguration: erste Locale `de-*` → Deutsch, sonst Englisch
 * — dieselbe Regel wie die Aufloesung in `:core`, angewandt auf das, was die
 * Ressourcen tatsaechlich benutzen.
 */
fun languageOf(configuration: Configuration): AppLanguage {
    val first = configuration.locales[0] ?: return AppLanguage.EN
    return languageOfTag(first.toLanguageTag())
}
