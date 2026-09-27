package de.trailscape.app.i18n

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.LanguagePreference
import org.junit.Assert.assertEquals
import kotlin.test.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Sprachwahl der App ([AppLocale]) gegen echte Android-Konfigurationen.
 *
 * Deutsch ist die Ausgangslage der Klasse; einzelne Methoden stellen die
 * Systemsprache per Qualifier um („fr-rFR" ersetzt die Klassen-Qualifier
 * bewusst komplett).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class AppLocaleTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun init() {
        AppServices.init(app)
    }

    @Test
    fun `deutsches System braucht keinen Override`() {
        assertEquals(LanguagePreference.SYSTEM, AppLocale.preference(app))
        assertEquals(AppLanguage.DE, AppLocale.current(app))
        assertNull(AppLocale.overrideConfiguration(app))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `franzoesisches System bekommt Englisch als Override`() {
        assertEquals(AppLanguage.EN, AppLocale.current(app))
        val override = assertNotNull(AppLocale.overrideConfiguration(app))
        assertEquals("en", override.locales[0].language)
        assertEquals("GB", override.locales[0].country)
        // Der Kontext in der App-Sprache liest die englischen Ressourcen.
        assertEquals("Language", app.localized().getString(R.string.more_language_title))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `englisches System bleibt unangetastet`() {
        assertEquals(AppLanguage.EN, AppLocale.current(app))
        assertNull(AppLocale.overrideConfiguration(app))
        assertEquals("Language", app.getString(R.string.more_language_title))
    }

    @Test
    fun `deutsche Ressourcen sind die Vorgabe`() {
        assertEquals("Sprache", app.getString(R.string.more_language_title))
        assertEquals("Sprache", app.localized().getString(R.string.more_language_title))
        assertEquals("Language", app.localizedFor(AppLanguage.EN).getString(R.string.more_language_title))
    }

    @Test
    fun `languageOf liest die erste Locale der Konfiguration`() {
        assertEquals(AppLanguage.DE, languageOf(app.resources.configuration))
        assertEquals(
            AppLanguage.EN,
            languageOf(app.localizedFor(AppLanguage.EN).resources.configuration),
        )
    }

    @Test
    @Config(sdk = [32])
    fun `unter Android 13 wird die Wahl gespeichert und neu erzeugt`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        AppLocale.setPreference(activity, LanguagePreference.EN)

        assertEquals(LanguagePreference.EN, AppLocale.preference(app))
        assertEquals(AppLanguage.EN, AppLocale.current(app))
        assertEquals(AppLanguage.EN, AppServices.appLanguage.value)
        val override = assertNotNull(AppLocale.overrideConfiguration(app))
        assertEquals("en", override.locales[0].language)

        AppLocale.setPreference(activity, LanguagePreference.SYSTEM)
        assertEquals(LanguagePreference.SYSTEM, AppLocale.preference(app))
        assertEquals(AppLanguage.DE, AppLocale.current(app))
    }

    @Test
    fun `UiText loest Argumente rekursiv in der Sprache des Kontexts auf`() {
        val status = UiText.Res(
            R.string.more_language_status_system,
            listOf(UiText.Res(R.string.language_name_de)),
        )
        assertEquals("Wie System (Deutsch)", status.resolve(app))
        assertEquals("Same as system (Deutsch)", status.resolve(app.localizedFor(AppLanguage.EN)))
        assertEquals(
            "2 neue Kacheln entdeckt.",
            UiText.Plural(R.plurals.map_explorer_new_tiles_count, 2).resolve(app).removePrefix("⊞ +"),
        )
        assertEquals(
            "1 new tile discovered.",
            UiText.Plural(R.plurals.map_explorer_new_tiles_count, 1)
                .resolve(app.localizedFor(AppLanguage.EN)).removePrefix("⊞ +"),
        )
    }
}
