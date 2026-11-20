package de.trailscape.app.ui.map

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.localizedFor
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.RecordGain
import de.trailscape.core.RecordKind
import de.trailscape.core.RideFact
import de.trailscape.core.RideHighlight
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.rideHighlights
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Saetze „was die Tour gebracht hat" ohne Compose ([rideHighlightText],
 * [rideHighlightTexts]) — dieselbe Funktion, die das Tourblatt rendert
 * (`karte-tour-highlights*` in `ScreenshotTest`) und die die Uhr nach der
 * Fahrt bekommt (`wear/WearAbschluss.kt`). Gegen die echten Ressourcen
 * (Robolectric), in beiden Sprachen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class RideHighlightTextTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun resources(language: AppLanguage): Resources = app.localizedFor(language).resources

    private fun de(highlight: RideHighlight) =
        rideHighlightText(resources(AppLanguage.DE), AppFormats(AppLanguage.DE), highlight)

    private fun en(highlight: RideHighlight) =
        rideHighlightText(resources(AppLanguage.EN), AppFormats(AppLanguage.EN), highlight)

    @Test
    fun bestweiteMitAltemWertUndGeschuetztemLeerzeichen() {
        val record = RideHighlight.Record(RecordGain(RecordKind.DISTANCE, value = 82.4, previous = 61.0))
        assertEquals("Neue Bestweite: 82,4 km (vorher 61,0)", de(record))
        assertEquals("New longest ride: 82.4 km (before 61.0)", en(record))
    }

    @Test
    fun wochenzielGeschafft() {
        val reached = RideHighlight.WeekGoalReached(targetKm = 150, riddenKm = 152.3)
        assertEquals("Wochenziel geschafft: 152 von 150 km", de(reached))
        assertEquals("Weekly goal reached: 152 of 150 km", en(reached))
    }

    @Test
    fun wochenzielRestWirdAufgerundetUndPluralisiert() {
        val progress = RideHighlight.WeekGoalProgress(targetKm = 100, riddenKm = 78.4, remainingKm = 21.6, daysLeft = 1)
        assertEquals("Noch 22 km bis zum Wochenziel (1 Tag)", de(progress))
    }

    @Test
    fun ausDerRechnungBisZumSatzHoechstensZwei() {
        val today = LocalDate.of(2026, 11, 20) // Freitag
        val facts = listOf(
            RideFact("a", LocalDate.of(2026, 11, 16), 61.0, 300.0, 9000, 24.0),
            RideFact("b", LocalDate.of(2026, 11, 20), 82.4, 200.0, 12000, 23.0),
        )
        val highlights = rideHighlights("b", facts, today, weekTargetKm = 140, routeComparison = null, max = 2)
        val zeilen = rideHighlightTexts(app.localizedFor(AppLanguage.DE), highlights)
        assertEquals(2, zeilen.size)
        assertEquals("Neue Bestweite: 82,4 km (vorher 61,0)", zeilen[0])
        assertTrue(zeilen[1].startsWith("Wochenziel geschafft"), zeilen[1])
    }

    @Test
    fun ohneHighlightsKeineZeilen() {
        assertEquals(emptyList(), rideHighlightTexts(app, emptyList()))
        // Unbekannte Tour-ID: `rideHighlights` liefert nichts, also auch keine Zeile
        // (und `WearAbschluss` sendet nichts).
        val today = LocalDate.of(2026, 11, 20)
        val facts = listOf(RideFact("a", today, 10.0, 0.0, 1800, 20.0))
        assertEquals(emptyList(), rideHighlightTexts(app, rideHighlights("x", facts, today, null, null, max = 2)))
    }
}
