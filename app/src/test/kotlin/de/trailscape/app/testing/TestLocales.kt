package de.trailscape.app.testing

import de.trailscape.app.i18n.languageOf
import de.trailscape.core.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric-Qualifier fuer die Sprache der Tests.
 *
 * ## Warum Tests fest deutsch laufen
 * Robolectric startet ohne Qualifier in `en-US`. Seit es `values-en/` gibt,
 * wuerden alle Tests damit englisch — und jeder Test, der auf einen deutschen
 * Text klickt („Verlauf", „Einstellungen"), braeche. Deshalb setzt jede
 * Robolectric-Testklasse die Sprache ausdruecklich ueber diese Konstanten.
 *
 * ## Methoden-@Config
 * Ein Methoden-Qualifier **ohne** fuehrendes „+" ersetzt die Klassen-Qualifier
 * komplett — die Sprache faellt dabei weg. Solche Qualifier muessen deshalb
 * ebenfalls mit [DE] beginnen (z. B. `"$DE-w360dp-h640dp-xxhdpi"`). Mit „+"
 * wird ergaenzt: `"+en-rGB"` schaltet eine einzelne Methode auf Englisch.
 */
object TestLocales {
    /** Deutsch (Deutschland). */
    const val DE = "de-rDE"

    /** Englisch (Grossbritannien) — die Variante, die die App fuer Englisch nimmt. */
    const val EN = "en-rGB"

    /** Das Geraet der Screenshot-Tests: Galaxy S25 (1080 × 2340 px). */
    const val S25 = "w411dp-h891dp-xxhdpi"

    /** Galaxy S25 auf Deutsch. */
    const val S25_DE = "$DE-$S25"

    /** Galaxy S25 auf Englisch. */
    const val S25_EN = "$EN-$S25"

    /** Schmales Telefon (360 dp Breite). */
    const val PHONE_NARROW = "w360dp-h740dp-xxhdpi"
    const val PHONE_NARROW_DE = "$DE-$PHONE_NARROW"
    const val PHONE_NARROW_EN = "$EN-$PHONE_NARROW"

    /** Galaxy Tab S11 hochkant (~800 × 1280 dp). */
    const val TAB_S11_PORTRAIT = "w800dp-h1280dp-xhdpi"
    const val TAB_S11_PORTRAIT_DE = "$DE-$TAB_S11_PORTRAIT"
    const val TAB_S11_PORTRAIT_EN = "$EN-$TAB_S11_PORTRAIT"

    /** Galaxy Tab S11 quer (~1280 × 800 dp, z. B. auf der Rolle). */
    const val TAB_S11_LANDSCAPE = "w1280dp-h800dp-xhdpi"
    const val TAB_S11_LANDSCAPE_DE = "$DE-$TAB_S11_LANDSCAPE"
    const val TAB_S11_LANDSCAPE_EN = "$EN-$TAB_S11_LANDSCAPE"

    /**
     * Prueft, dass der Test wirklich in [expected] laeuft — sowohl die
     * Ressourcen-Konfiguration als auch die Standard-Locale der JVM.
     */
    fun assertTestLocale(expected: AppLanguage) {
        val configuration = RuntimeEnvironment.getApplication().resources.configuration
        assertEquals(expected, languageOf(configuration))
        assertEquals(expected.tag, java.util.Locale.getDefault().language)
    }
}
