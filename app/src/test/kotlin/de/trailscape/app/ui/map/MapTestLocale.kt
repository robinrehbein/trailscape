package de.trailscape.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.languageOf
import de.trailscape.core.i18n.coreTexts

/**
 * Stellt fuer Einzel-Screenshots der Karte dieselben Sprach-Locals bereit wie
 * `TrailscapeApp()` — aus der Konfiguration der Test-Activity (also aus den
 * Robolectric-Qualifiern).
 *
 * Warum: Die Bausteine der Karte lesen Zahlen- und `:core`-Texte ueber
 * [LocalAppFormats] und [LocalCoreTexts]. Ohne Provider gilt deren
 * deutscher Vorgabewert — ein englischer Screenshot zeigte dann „12,3 km"
 * neben englischen Beschriftungen.
 */
@Composable
internal fun ProvideTestLanguage(content: @Composable () -> Unit) {
    val language = languageOf(LocalConfiguration.current)
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalCoreTexts provides coreTexts(language),
        LocalAppFormats provides AppFormats(language),
        content = content,
    )
}
