package de.trailscape.app.ui.erfolge

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.ErfolgeStore
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.localizedFor
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.map.rideHighlightText
import de.trailscape.core.AchievementId
import de.trailscape.core.AchievementProgressData
import de.trailscape.core.AchievementState
import de.trailscape.core.AchievementStatus
import de.trailscape.core.KeyValueStore
import de.trailscape.core.RideHighlight
import de.trailscape.core.i18n.AppLanguage
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Saetze der Erfolge ohne Compose ([badgeStatusText], [badgeHighlightText])
 * gegen die echten Ressourcen, in beiden Sprachen, und der gespeicherte
 * Stand ([ErfolgeStore]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class ErfolgeTextsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun resources(language: AppLanguage): Resources = app.localizedFor(language).resources

    private fun de(state: AchievementState, hasPlan: Boolean = true) =
        badgeStatusText(resources(AppLanguage.DE), AppFormats(AppLanguage.DE), state, hasPlan)

    private fun en(state: AchievementState, hasPlan: Boolean = true) =
        badgeStatusText(resources(AppLanguage.EN), AppFormats(AppLanguage.EN), state, hasPlan)

    @Test
    fun everyBadgeHasANameAndAConditionInBothLanguages() {
        for (language in AppLanguage.entries) {
            val res = resources(language)
            val titles = AchievementId.entries.map { res.getString(badgeTitleRes(it)) }
            assertEquals(titles.size, titles.toSet().size, "doppelte Namen in $language")
            for (id in AchievementId.entries) {
                assertTrue(res.getString(badgeBodyRes(id)).isNotBlank(), id.key)
            }
        }
        assertNotEquals(
            resources(AppLanguage.DE).getString(badgeTitleRes(AchievementId.RIDE_100_KM)),
            resources(AppLanguage.EN).getString(badgeTitleRes(AchievementId.RIDE_100_KM)),
        )
    }

    @Test
    fun reachedWithAndWithoutDate() {
        val withDate = AchievementState(AchievementId.RIDE_100_KM, AchievementStatus.REACHED, 104.0, date = LocalDate.of(2026, 5, 3))
        assertEquals("Erreicht am 03.05.2026", de(withDate))
        assertEquals("Reached on 3 May 2026", en(withDate))
        assertEquals("Erreicht", de(withDate.copy(date = null)))
    }

    @Test
    fun progressIsRoundedDown() {
        val open = AchievementState(AchievementId.RIDE_100_KM, AchievementStatus.OPEN, 99.97)
        assertEquals("99 von 100 km", de(open))
        assertEquals("99 of 100 km", en(open))
        val hours = AchievementState(AchievementId.INDOOR_10_H, AchievementStatus.OPEN, 3.46)
        assertEquals("3,4 von 10 h", de(hours))
        val ascent = AchievementState(AchievementId.TOTAL_EVEREST, AchievementStatus.OPEN, 1200.0)
        assertEquals("1200 von 8849 hm", de(ascent))
        assertEquals("1200 of 8849 m", en(ascent))
    }

    @Test
    fun specialProgressLines() {
        assertEquals(
            "Serie jetzt: 3 von 12 Wochen",
            de(AchievementState(AchievementId.STREAK_12, AchievementStatus.OPEN, 3.0)),
        )
        assertEquals(
            "Größtes Quadrat: 3 × 3",
            de(AchievementState(AchievementId.TILES_SQUARE_5, AchievementStatus.OPEN, 3.0)),
        )
        assertEquals(
            "Diesen Monat: 2 von 4 Challenges",
            de(AchievementState(AchievementId.MONTH_COMPLETE, AchievementStatus.OPEN, 2.0, target = 4.0)),
        )
        assertEquals(
            "Bisher 75 % der Einheiten erledigt",
            de(AchievementState(AchievementId.PLAN_FINISHED, AchievementStatus.OPEN, 75.0)),
        )
        assertEquals(
            "Wird berechnet …",
            de(AchievementState(AchievementId.ROUND_5, AchievementStatus.UNKNOWN)),
        )
    }

    @Test
    fun missingGoalOrPlanIsSaid() {
        assertEquals(
            "Noch kein Jahresziel gesetzt",
            de(AchievementState(AchievementId.YEAR_GOAL, AchievementStatus.OPEN, null, target = 0.0)),
        )
        assertEquals(
            "Mit einem Trainingsplan erreichbar",
            de(AchievementState(AchievementId.PLAN_FIRST, AchievementStatus.OPEN), hasPlan = false),
        )
        // Die Erholungswoche gibt es auch ohne Plan.
        assertEquals("Noch offen", de(AchievementState(AchievementId.RECOVERY_WEEK, AchievementStatus.OPEN), hasPlan = false))
        assertEquals(
            "Diese Woche gibt es keine Planeinheiten",
            de(AchievementState(AchievementId.PLAN_FULL_WEEK, AchievementStatus.OPEN, null, target = 0.0)),
        )
    }

    @Test
    fun highlightLineForOneOrMoreBadges() {
        val de = resources(AppLanguage.DE)
        val en = resources(AppLanguage.EN)
        assertEquals("Neues Abzeichen: Hunderter", badgeHighlightText(de, listOf(AchievementId.RIDE_100_KM)))
        assertEquals("New badge: Century", badgeHighlightText(en, listOf(AchievementId.RIDE_100_KM)))
        val three = listOf(AchievementId.RIDE_50_KM, AchievementId.RIDE_100_KM, AchievementId.CHALLENGE_BONUS)
        assertEquals("Neue Abzeichen: Halbe Sache und 2 weitere", badgeHighlightText(de, three))
        assertEquals("Neue Abzeichen: Halbe Sache und 1 weiteres", badgeHighlightText(de, three.take(2)))
        assertEquals("New badges: Fifty and 2 more", badgeHighlightText(en, three))
        // Dieselbe Zeile im Tourblatt und auf der Uhr, und sie passt dort (60 Zeichen).
        val line = rideHighlightText(de, AppFormats(AppLanguage.DE), RideHighlight.AchievementsUnlocked(three))
        assertEquals(badgeHighlightText(de, three), line)
        for (id in AchievementId.entries) {
            for (res in listOf(de, en)) {
                assertTrue(badgeHighlightText(res, listOf(id, id)).length <= 60, id.key)
            }
        }
    }

    private class CountingStore : KeyValueStore {
        val values = HashMap<String, String>()
        var writes = 0

        override fun getString(key: String): String? = values[key]

        override fun setString(key: String, value: String) {
            writes += 1
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    @Test
    fun storeKeepsAndMergesTheState() {
        val kv = CountingStore()
        val store = ErfolgeStore(kv)
        assertEquals(AchievementProgressData(), store.value)
        store.setShown(false)
        assertTrue(store.value.hidden)
        assertTrue(kv.getString(ErfolgeStore.STORAGE_KEY)!!.contains("\"hidden\":true"))
        // Ein neuer Store liest denselben Stand.
        assertTrue(ErfolgeStore(kv).value.hidden)
        store.restore(AchievementProgressData(keptLevel = 4, hidden = false))
        assertEquals(4, store.value.keptLevel)
        assertEquals(false, store.value.hidden)
        // Unveraendert: nichts geschrieben.
        kv.writes = 0
        store.update { it }
        assertEquals(0, kv.writes)
        // Kaputter Inhalt: leerer Stand statt Absturz.
        kv.setString(ErfolgeStore.STORAGE_KEY, "{kaputt")
        assertEquals(AchievementProgressData(), ErfolgeStore(kv).value)
    }
}
