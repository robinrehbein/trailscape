package de.trailscape.core.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {

    private fun system(vararg tags: String) = resolveAppLanguage(LanguagePreference.SYSTEM, tags.toList())

    @Test
    fun `deutschsprachige Systeme ergeben Deutsch`() {
        assertEquals(AppLanguage.DE, system("de-DE"))
        assertEquals(AppLanguage.DE, system("de-AT"))
        assertEquals(AppLanguage.DE, system("de-CH"))
        assertEquals(AppLanguage.DE, system("de"))
        assertEquals(AppLanguage.DE, system("de_DE"))
    }

    @Test
    fun `englischsprachige und fremde Systeme ergeben Englisch`() {
        assertEquals(AppLanguage.EN, system("en-US"))
        assertEquals(AppLanguage.EN, system("en-GB"))
        assertEquals(AppLanguage.EN, system("fr-FR"))
        assertEquals(AppLanguage.EN, system("ja"))
        assertEquals(AppLanguage.EN, system())
    }

    @Test
    fun `der erste deutsch- oder englischsprachige Eintrag der Liste zaehlt`() {
        assertEquals(AppLanguage.DE, system("fr-FR", "de-DE"))
        assertEquals(AppLanguage.EN, system("fr-FR", "en-GB", "de-DE"))
        assertEquals(AppLanguage.DE, system("de-DE", "en-US"))
    }

    @Test
    fun `eine ausdrueckliche Wahl schlaegt das System`() {
        assertEquals(AppLanguage.EN, resolveAppLanguage(LanguagePreference.EN, listOf("de-DE")))
        assertEquals(AppLanguage.DE, resolveAppLanguage(LanguagePreference.DE, listOf("en-US")))
        assertEquals(AppLanguage.DE, resolveAppLanguage(LanguagePreference.DE, emptyList()))
    }

    @Test
    fun `Speicherwerte gehen hin und zurueck`() {
        for (preference in LanguagePreference.entries) {
            assertEquals(preference, LanguagePreference.fromTag(preference.tag))
        }
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromTag(null))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromTag("fr"))
        assertEquals(listOf("system", "de", "en"), LanguagePreference.entries.map { it.tag })
    }

    @Test
    fun `languageOfTag und die Locales der Sprachen`() {
        assertEquals(AppLanguage.DE, languageOfTag("DE-at"))
        assertEquals(AppLanguage.EN, languageOfTag("es"))
        assertEquals("de", AppLanguage.DE.tag)
        assertEquals("en", AppLanguage.EN.tag)
        assertEquals("GB", AppLanguage.EN.locale.country)
        assertEquals(CoreTextsDe, coreTexts(AppLanguage.DE))
        assertEquals(CoreTextsEn, coreTexts(AppLanguage.EN))
    }
}
