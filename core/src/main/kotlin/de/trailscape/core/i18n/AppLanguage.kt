package de.trailscape.core.i18n

import java.util.Locale

/**
 * Die Sprachen, in denen Trailscape Texte ausgeben kann.
 *
 * ## Warum eine eigene Aufloesung statt der Android-Ressourcenwahl
 * Die Default-Ressourcen (`values/`) der App sind **deutsch**, Englisch liegt
 * in `values-en/`. Ein Geraet auf Franzoesisch bekaeme von Android deshalb
 * die deutschen Default-Texte — gewollt ist aber „Deutsch fuer de-*, sonst
 * Englisch". [resolveAppLanguage] trifft diese Wahl einmal, reines Kotlin und
 * getestet; die App setzt das Ergebnis dann als Locale-Override.
 *
 * @property tag BCP-47-Sprachkuerzel, wie es auch in HTTP-Anfragen
 *   (`accept-language`) und in den Einstellungen gespeichert wird.
 * @property locale Locale fuer Datums-/Zahlformate und die Sprachausgabe.
 *   Englisch ist bewusst britisch (metres, d MMM yyyy), passend zu den
 *   metrischen Einheiten der App.
 */
enum class AppLanguage(val tag: String, val locale: Locale) {
    DE("de", Locale.GERMANY),
    EN("en", Locale.UK),
}

/**
 * Die Wahl in Einstellungen → Sprache.
 *
 * [SYSTEM] heisst: der Systemsprache folgen (siehe [resolveAppLanguage]).
 *
 * @property tag stabiler Speicherwert (`system`, `de`, `en`) — nie aendern,
 *   er steht in den gespeicherten Einstellungen.
 */
enum class LanguagePreference(val tag: String) {
    SYSTEM("system"),
    DE("de"),
    EN("en"),
    ;

    companion object {
        /** Liest einen gespeicherten Wert; Unbekanntes und `null` → [SYSTEM]. */
        fun fromTag(tag: String?): LanguagePreference =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/**
 * Sprache eines einzelnen BCP-47-Tags: `de`, `de-AT`, `de_CH` … → [AppLanguage.DE],
 * alles andere → [AppLanguage.EN].
 */
fun languageOfTag(tag: String): AppLanguage =
    if (primaryLanguage(tag) == "de") AppLanguage.DE else AppLanguage.EN

/**
 * Entscheidet die Sprache der App.
 *
 *  * Eine ausdrueckliche Wahl ([LanguagePreference.DE]/[LanguagePreference.EN])
 *    gewinnt immer.
 *  * Bei [LanguagePreference.SYSTEM] zaehlt der erste Eintrag der
 *    System-Sprachliste, dessen Sprache Deutsch oder Englisch ist — wer
 *    „Franzoesisch, Deutsch" eingestellt hat, liest Deutsch lieber als
 *    Englisch.
 *  * Ohne Treffer: Englisch.
 *
 * @param systemLanguageTags die Sprachliste des Systems in Vorzugsreihenfolge
 *   (BCP-47, z. B. `["fr-FR", "de-DE"]`).
 */
fun resolveAppLanguage(
    preference: LanguagePreference,
    systemLanguageTags: List<String>,
): AppLanguage = when (preference) {
    LanguagePreference.DE -> AppLanguage.DE
    LanguagePreference.EN -> AppLanguage.EN
    LanguagePreference.SYSTEM -> systemLanguageTags
        .map(::primaryLanguage)
        .firstOrNull { it == "de" || it == "en" }
        ?.let { if (it == "de") AppLanguage.DE else AppLanguage.EN }
        ?: AppLanguage.EN
}

/** Sprachteil eines Tags, klein: `de-AT` → `de`, `en_GB` → `en`. */
private fun primaryLanguage(tag: String): String =
    tag.trim().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
