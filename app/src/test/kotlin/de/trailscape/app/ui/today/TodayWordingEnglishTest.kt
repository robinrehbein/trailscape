package de.trailscape.app.ui.today

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.AscentPreference
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.SleepAssessment
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrainingSession
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Die englische Fassung von „Heute" dort, wo Logik Saetze zusammensetzt:
 * Readiness-Vorsatz + Schlagzeile, Kilometer im Satz, der Ruhetag-Hinweis mit
 * eingesetzter Schlagzeile, die Notiz mit Wochentag (der im Englischen hinten
 * steht), Vorlesetexte des Streifens, Plurals und die Zielzeile.
 *
 * Aufgeloest gegen `values-en/strings_today.xml` ([TodayStrings]); die
 * deutschen Faelle stehen in [TodayWordingTest].
 */
class TodayWordingEnglishTest {

    private fun en(text: UiText): String = TodayStrings.en(text)

    private fun session(day: String, km: Int, intensity: SessionIntensity = SessionIntensity.GRUNDLAGE) =
        TrainingSession(day = day, title = "GA1", description = "", targetKm = km, intensity = intensity)

    private fun target(km: Double, intensity: SessionIntensity) = RouteTarget(
        distanceKm = km,
        ascentPreference = AscentPreference.FLACH,
        durationH = null,
        speedKmh = 22.0,
        intensity = intensity,
        label = "x",
        source = RouteTargetSource.PLAN,
    )

    private fun route(target: RouteTarget?, session: TrainingSession?, downgraded: Boolean = false) = TodayRoute(
        target = target,
        session = session,
        plannedKm = session?.targetKm,
        factor = 1.0,
        downgraded = downgraded,
        note = null,
    )

    private val week = listOf(session("Di", 32), session("Do", 45), session("Sa", 80))

    @Test
    fun `Schlagzeile und Satz auf Englisch`() {
        val r = route(target(45.0, SessionIntensity.GRUNDLAGE), week[1])
        val effort = todayEffort(r, false, week)
        assertEquals("Well recovered. Today: an easy loop.", en(todayHeadline(effort, r, ReadinessBand.HART, false)))
        assertEquals(
            "45 km at a relaxed pace, easy enough to hold a conversation.",
            en(todaySentence(effort, r)),
        )
        assertEquals("Why an easy loop?", en(whyTitle(effort, r)))
    }

    @Test
    fun `heruntergestuft nennt beide Zahlen`() {
        val r = route(target(48.0, SessionIntensity.GRUNDLAGE), week[2], downgraded = true)
        val effort = todayEffort(r, false, week)
        assertEquals(
            "A little tired. Less than planned today: an easy loop.",
            en(todayHeadline(effort, r, ReadinessBand.LOCKER, false)),
        )
        assertEquals(
            "48 km instead of 80 km at a relaxed pace, easy enough to hold a conversation.",
            en(todaySentence(effort, r)),
        )
        assertEquals("Why less than planned?", en(whyTitle(effort, r)))
    }

    @Test
    fun `Ruhetag im Dialog nennt denselben Grund`() {
        val free = route(target(21.0, SessionIntensity.GRUNDLAGE), null)
        val effort = todayEffort(free, planRestDay = true, weekSessions = week)
        val offer = offeredTarget(free, effort, target(16.0, SessionIntensity.LOCKER))!!
        assertEquals("Easy · 16 km", en(offerChipText(offer)))
        assertEquals("Easy spin · 16 km", en(offerButtonLabel(offer)))
        assertEquals("Easy spin", en(offerDialogAction(offer)))
        assertEquals(
            "Today is a rest day. If you still feel like riding: an easy 16 km spin.",
            en(offerHint(offer, restHeadline(free, planRestDay = true))),
        )
        assertEquals("Recovered as usual. Today is a rest day.", en(todayHeadline(effort, free, ReadinessBand.NORMAL, true)))

        val planned = TodayOffer(target(44.6, SessionIntensity.GRUNDLAGE), restDay = false)
        assertEquals("Today 45 km", en(offerChipText(planned)))
        assertEquals("Build today’s loop", en(offerButtonLabel(planned)))
        assertEquals("Today calls for 45 km.", en(offerHint(planned)))
    }

    @Test
    fun `Notiz setzt den englischen Wochentag ans Satzende`() {
        val upcoming = upcomingKeySession(week, todayIndex = 3, todayKm = 45)
        val note = whyNote(TodayEffort.LOCKER, route(null, null), false, upcoming, false, hasPlan = true, texts = CoreTextsEn)
        assertEquals(
            listOf("Your long ride of 80 km is on Saturday. Don’t overdo it today, so you have enough energy for it."),
            note.map(::en),
        )
        // Mit deutschen Kerntexten bliebe der Wochentag deutsch — die Sprache
        // des Wochentags haengt an `texts`, nicht an der Ressource.
        val noteDe = whyNote(TodayEffort.HART, route(null, null), false, upcoming, false, hasPlan = true, texts = CoreTextsDe)
        assertEquals(listOf("Samstag steht die lange Fahrt mit 80 km an."), noteDe.map(TodayStrings::de))
    }

    @Test
    fun `Schlafzeile auf Englisch`() {
        val sleep = SleepAssessment(
            available = true,
            unavailableReason = null,
            baselineH = 7.5,
            sigmaH = 0.5,
            lastNightH = 6.5,
            deviationH = -1.0,
            z = -2.0,
            debt7dH = 0.0,
            flag = RecoveryFlag.ORANGE,
            validNights = 28,
            shortSleeper = false,
            message = "",
        )
        val row = sleepSignal(sleep)
        assertEquals("Sleep", en(row.label))
        assertEquals("too short", en(row.word))
        assertEquals("6 h 30 min, 1 h less than your average.", en(row.sentence))
        assertEquals("6 h 30 min, 1 h weniger als dein Schnitt.", TodayStrings.de(row.sentence))
        assertEquals("Training load", en(loadSignal(null).label))
    }

    @Test
    fun `Streifen und Wochenkopf auf Englisch`() {
        val thursday = LocalDate.of(2026, 9, 24)
        val strip = weekStrip(thursday, week, mapOf(LocalDate.of(2026, 9, 22) to 31.6), todayKm = 45, texts = CoreTextsEn)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), strip.map { it.label })
        assertEquals("Thursday, today: 45 km planned", en(strip[3].description))
        assertEquals("Tuesday: 32 km ridden", en(strip[1].description))
        assertEquals("Wednesday: no ride", en(strip[2].description))
        assertEquals("Friday: rest day", en(strip[4].description))

        fun summary(km: Double, target: Int?, rides: Int) =
            weekSummary(km, target, strip, rides).let { en(it.first) to it.second?.let(::en) }
        assertEquals("32 of 120 km" to "2 rides to go", summary(31.6, 120, 1))
        assertEquals("32 km this week" to "1 ride", summary(31.6, null, 1))
        assertEquals("32 km this week" to "3 rides", summary(31.6, null, 3))
        assertEquals("0 km this week" to "no rides yet", summary(0.0, null, 0))
        assertEquals("40 of 40 km" to "Weekly goal reached", summary(39.6, 40, 2))
    }

    @Test
    fun `Plural waehlt one und other`() {
        val oneLeft = UiText.Plural(R.plurals.today_week_open_rides_count, 1)
        assertEquals("1 ride to go", en(oneLeft))
        assertEquals("noch 1 Fahrt", TodayStrings.de(oneLeft))
    }

    @Test
    fun `Zielzeile auf Englisch`() {
        val today = LocalDate.of(2026, 9, 25)
        val goal = LocalDate.of(2026, 12, 19)
        assertEquals(
            "Sat 19 December · 12 weeks to go · Week 3 of 15",
            goalLine(today, goal, 2, 15, texts = CoreTextsEn).joinToString(" · ", transform = ::en),
        )
        assertEquals("Fri 1 January 2027", en(formatGoalDate(today, LocalDate.of(2027, 1, 1), texts = CoreTextsEn)))
        assertEquals("5 days to go", en(goalCountdown(today, today.plusDays(5))))
        assertEquals("tomorrow", en(goalCountdown(today, today.plusDays(1))))
        assertEquals(
            "Goal 2:10 h · Currently approx. 2:25 h · 12 weeks to go",
            goalTimeLine(today, goal, 130, 145).joinToString(" · ", transform = ::en),
        )
        assertEquals(
            "Ziel 2:10 h · Stand heute ca. 2:25 h · noch 12 Wochen",
            goalTimeLine(today, goal, 130, 145).joinToString(" · ", transform = TodayStrings::de),
        )
    }
}
