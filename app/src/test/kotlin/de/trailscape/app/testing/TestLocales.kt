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
