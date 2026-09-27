package de.trailscape.app.voice

import de.trailscape.core.i18n.AppLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Welche Stimmen die Sprachausgabe je Sprache versucht ([ttsLocaleCandidates]). */
class TtsLocaleTest {

    @Test
    fun `Englisch versucht erst britisch, dann amerikanisch, dann irgendein Englisch`() {
        assertEquals(listOf(Locale.UK, Locale.US, Locale.ENGLISH), ttsLocaleCandidates(AppLanguage.EN))
    }

    @Test
    fun `Deutsch versucht erst Deutschland, dann irgendein Deutsch`() {
        assertEquals(listOf(Locale.GERMANY, Locale.GERMAN), ttsLocaleCandidates(AppLanguage.DE))
    }
}
