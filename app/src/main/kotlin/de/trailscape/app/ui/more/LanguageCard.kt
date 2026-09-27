package de.trailscape.app.ui.more

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.AppLocale
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.LanguagePreference

/** Die Optionen der Sprachwahl in der Reihenfolge der Segmente. */
private val languageOptions = listOf(LanguagePreference.SYSTEM, LanguagePreference.DE, LanguagePreference.EN)

/**
 * Mehr → Sprache: System, Deutsch oder English.
 *
 * Die Wahl wirkt sofort: Ab Android 13 stellt das System die Sprache um und
 * erzeugt die Activity neu, darunter uebernimmt das [AppLocale.setPreference].
 * Die Sprachnamen stehen als Endonyme da („Deutsch", „English"), damit sie
 * auch lesen kann, wer versehentlich die falsche Sprache eingestellt hat.
 * Damit das auch fuer TalkBack gilt, traegt jedes Endonym seine eigene
 * Sprachmarke ([endonymLocale]) — sonst spraeche eine deutsche Stimme
 * „English" falsch aus und umgekehrt.
 */
@Composable
fun LanguageCardContent() {
    val context = LocalContext.current
    var preference by remember { mutableStateOf(AppLocale.preference(context)) }
    PillSegments(
        options = listOf(
            stringResource(R.string.more_language_option_system),
            stringResource(R.string.language_name_de),
            stringResource(R.string.language_name_en),
        ),
        optionLocales = languageOptions.map { option ->
            option.language?.let(::endonymLocale)
        },
        selectedIndex = languageOptions.indexOf(preference).coerceAtLeast(0),
        onSelect = { index ->
            val chosen = languageOptions[index]
            if (chosen != preference) {
                preference = chosen
                context.findActivity()?.let { AppLocale.setPreference(it, chosen) }
            }
        },
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.more_language_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.more_language_voice_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Statuszeile der Listenzeile „Sprache": bei eigener Wahl der Sprachname,
 * sonst „Wie System (Deutsch)" mit der tatsaechlich aufgeloesten Sprache.
 */
internal fun languageStatusText(preference: LanguagePreference, resolved: AppLanguage): UiText =
    when (preference) {
        LanguagePreference.SYSTEM -> UiText.Res(
            R.string.more_language_status_system,
            listOf(UiText.Res(languageNameRes(resolved))),
        )
        LanguagePreference.DE -> UiText.Res(R.string.language_name_de)
        LanguagePreference.EN -> UiText.Res(R.string.language_name_en)
    }

/**
 * Die Statuszeile wie [languageStatusText], der Sprachname darin mit seiner
 * eigenen Sprachmarke fuer TalkBack.
 */
@Composable
internal fun languageStatusAnnotated(preference: LanguagePreference, resolved: AppLanguage): AnnotatedString {
    val shown = preference.language ?: resolved
    return tagEndonym(
        text = languageStatusText(preference, resolved).asString(),
        name = stringResource(languageNameRes(shown)),
        language = shown,
    )
}

/** Markiert das erste Vorkommen von [name] in [text] mit der Sprache [language]. */
internal fun tagEndonym(text: String, name: String, language: AppLanguage): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val start = text.indexOf(name)
        if (start >= 0) {
            addStyle(SpanStyle(localeList = endonymLocale(language)), start, start + name.length)
        }
    }

/** Sprachmarke eines Endonyms. */
internal fun endonymLocale(language: AppLanguage): LocaleList = LocaleList(language.tag)

/** Die feste Sprache einer Wahl; `null` fuer „System". */
private val LanguagePreference.language: AppLanguage?
    get() = when (this) {
        LanguagePreference.SYSTEM -> null
        LanguagePreference.DE -> AppLanguage.DE
        LanguagePreference.EN -> AppLanguage.EN
    }

/** Endonym einer Sprache als Ressource. */
private fun languageNameRes(language: AppLanguage): Int = when (language) {
    AppLanguage.DE -> R.string.language_name_de
    AppLanguage.EN -> R.string.language_name_en
}

/** Die Activity hinter einem (evtl. gewickelten) Kontext. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
