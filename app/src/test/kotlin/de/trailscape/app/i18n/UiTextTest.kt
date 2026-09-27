package de.trailscape.app.i18n

import de.trailscape.app.R
import de.trailscape.app.ui.more.languageStatusText
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.LanguagePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [UiText] als reine Datenklasse: Formulierungslogik liefert UiText statt
 * fertiger Strings und ist damit ohne Robolectric pruefbar. Das Aufloesen
 * gegen echte Ressourcen testet [AppLocaleTest].
 */
class UiTextTest {

    @Test
    fun `gleiche Ressource und gleiche Argumente sind gleich`() {
        assertEquals(
            UiText.Res(R.string.rides_health_route_added_status, listOf("Albrunde")),
            UiText.Res(R.string.rides_health_route_added_status, listOf("Albrunde")),
        )
        assertNotEquals(
            UiText.Res(R.string.rides_health_route_added_status, listOf("Albrunde")),
            UiText.Res(R.string.rides_health_route_added_status, listOf("Feierabendrunde")),
        )
    }

    @Test
    fun `Plural nimmt ohne Angabe die Zahl als einziges Argument`() {
        val text = UiText.Plural(R.plurals.map_explorer_new_tiles_count, 3)
        assertEquals(listOf<Any>(3), text.args)
    }

    @Test
    fun `verschachtelte Argumente bleiben vergleichbar`() {
        assertEquals(
            UiText.Res(R.string.more_language_status_system, listOf(UiText.Res(R.string.language_name_en))),
            languageStatusText(LanguagePreference.SYSTEM, AppLanguage.EN),
        )
        assertEquals(UiText.Res(R.string.language_name_de), languageStatusText(LanguagePreference.DE, AppLanguage.EN))
        assertEquals(UiText.Res(R.string.language_name_en), languageStatusText(LanguagePreference.EN, AppLanguage.DE))
    }
}
