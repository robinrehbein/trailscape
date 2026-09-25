package de.trailscape.app.ui.today

import de.trailscape.core.AscentPreference
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.SleepAssessment
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrainingSession
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der Klartext-Wortwahl von „Heute" (`TodayWording.kt`): Tagesart,
 * Schlagzeile, Satz, Signalzeilen, Wochenstreifen und Zielzeile. Reine
 * JVM-Tests — die Composables selbst bleiben, wie ueberall in diesem Modul,
 * ungetestet.
 */
class TodayWordingTest {

    private fun session(
        day: String,
        km: Int,
        intensity: SessionIntensity = SessionIntensity.GRUNDLAGE,
        isEvent: Boolean = false,
        title: String = "GA1",
    ) = TrainingSession(
        day = day,
        title = title,
        description = "",
        targetKm = km,
        intensity = intensity,
        isEvent = isEvent,
    )

    private fun target(km: Double, intensity: SessionIntensity) = RouteTarget(
        distanceKm = km,
        ascentPreference = AscentPreference.FLACH,
        durationH = null,
        speedKmh = 22.0,
        intensity = intensity,
        label = "x",
        source = RouteTargetSource.PLAN,
    )

    private fun route(
        target: RouteTarget?,
        session: TrainingSession?,
        downgraded: Boolean = false,
        note: String? = null,
    ) = TodayRoute(
        target = target,
        session = session,
        plannedKm = session?.targetKm,
        factor = 1.0,
        downgraded = downgraded,
        note = note,
    )

    private val week = listOf(
        session("Di", 32),
        session("Do", 45),
        session("Sa", 80, title = "Lange Tour"),
    )

    // ------------------------------------------------------------ Tagesart

    @Test
    fun `GA1-Einheit ist eine lockere Runde`() {
        val r = route(target(45.0, SessionIntensity.GRUNDLAGE), week[1])
        val effort = todayEffort(r, planRestDay = false, weekSessions = week)
        assertEquals(TodayEffort.LOCKER, effort)
        assertEquals("Gut erholt. Heute eine lockere Runde.", todayHeadline(effort, r, ReadinessBand.HART, false))
        assertEquals("45 km ruhig fahren, so dass du dich noch unterhalten kannst.", todaySentence(effort, r))
        assertEquals("Warum eine lockere Runde?", whyTitle(effort, r))
    }

    @Test
    fun `laengste Einheit der Woche ist die lange Fahrt`() {
        val r = route(target(80.0, SessionIntensity.GRUNDLAGE), week[2])
        assertEquals(TodayEffort.LANG, todayEffort(r, false, week))
    }

    @Test
    fun `heruntergestufte lange Fahrt wird locker und sagt es`() {
        val r = route(target(48.0, SessionIntensity.GRUNDLAGE), week[2], downgraded = true)
        val effort = todayEffort(r, false, week)
        assertEquals(TodayEffort.LOCKER, effort)
        assertEquals(
            "Etwas müde. Heute weniger als geplant: eine lockere Runde.",
            todayHeadline(effort, r, ReadinessBand.LOCKER, false),
        )
        assertTrue(todaySentence(effort, r).startsWith("48 km statt 80 km"))
    }

    @Test
    fun `harte Intensitaet ist hart, gekappte ohne Plan-Grundlage ist mittel`() {
        val hard = session("Do", 40, SessionIntensity.HART, title = "Intervalle")
        assertEquals(TodayEffort.HART, todayEffort(route(target(40.0, SessionIntensity.HART), hard), false, week))
        assertEquals(
            TodayEffort.MITTEL,
            todayEffort(route(target(36.0, SessionIntensity.GRUNDLAGE), hard, downgraded = true), false, week),
        )
        // Ohne Plan: normale Tagesempfehlung.
        assertEquals(
            TodayEffort.MITTEL,
            todayEffort(route(target(40.0, SessionIntensity.GRUNDLAGE), null), false, emptyList()),
        )
    }

    @Test
    fun `Ruhetag ohne Routenziel und planfreier Tag`() {
        val skipped = route(null, week[1], downgraded = true)
        val effort = todayEffort(skipped, false, week)
        assertEquals(TodayEffort.RUHETAG, effort)
        assertEquals("Heute lieber Pause statt Training.", todayHeadline(effort, skipped, null, false))
        assertEquals("Im Plan standen 45 km. Schieb die Fahrt lieber um einen Tag.", todaySentence(effort, skipped))

        // Planfreier Tag mitten im Plan: kein Angebot, auch wenn die
        // Tagesempfehlung eines haette.
        val free = route(target(40.0, SessionIntensity.GRUNDLAGE), null)
        val freeEffort = todayEffort(free, planRestDay = true, weekSessions = week)
        assertEquals(TodayEffort.RUHETAG, freeEffort)
        assertEquals("Normal erholt. Heute ist Ruhetag.", todayHeadline(freeEffort, free, ReadinessBand.NORMAL, true))
    }

    @Test
    fun `Zieltag gewinnt immer`() {
        val event = session("Sa", 120, SessionIntensity.HART, isEvent = true)
        val r = route(null, event)
        val effort = todayEffort(r, false, week)
        assertEquals(TodayEffort.ZIELTAG, effort)
        assertEquals("Heute ist dein großer Tag.", todayHeadline(effort, r, null, false))
    }

    @Test
    fun `kein Fachwort in Schlagzeile und Satz`() {
        val jargon = listOf("GA1", "Z2", "Zone", "TSB", "Last", "bereit")
        val cases = listOf(
            route(target(45.0, SessionIntensity.GRUNDLAGE), week[1]),
            route(target(30.0, SessionIntensity.LOCKER), null),
            route(target(40.0, SessionIntensity.HART), session("Do", 40, SessionIntensity.HART)),
            route(null, null),
        )
        for (r in cases) {
            val effort = todayEffort(r, false, week)
            for (band in ReadinessBand.entries) {
                val text = todayHeadline(effort, r, band, false) + " " + todaySentence(effort, r)
                for (word in jargon) assertFalse(text.contains(word), "„$word“ in: $text")
            }
        }
    }

    @Test
    fun `Ring-Wort passt zum Band`() {
        assertEquals("erholt", readinessWord(ReadinessBand.HART))
        assertEquals("normal", readinessWord(ReadinessBand.NORMAL))
        assertEquals("müde", readinessWord(ReadinessBand.LOCKER))
        assertEquals("Ruhe", readinessWord(ReadinessBand.RUHE))
    }

    // ------------------------------------------------------- Warum-Blatt

    @Test
    fun `Schlafzeile nennt Dauer und Vergleich`() {
        val sleep = SleepAssessment(
            available = true,
            unavailableReason = null,
            baselineH = 7.2,
            sigmaH = 0.5,
            lastNightH = 7.0 + 40.0 / 60,
            deviationH = 0.47,
            z = 0.9,
            debt7dH = 0.0,
            flag = RecoveryFlag.GRUEN,
            validNights = 28,
            shortSleeper = false,
            message = "",
        )
        val row = sleepSignal(sleep)
        assertEquals("gut", row.word)
        assertEquals(SignalTone.GUT, row.tone)
        assertEquals("7 h 40 min, etwas mehr als dein Schnitt.", row.sentence)
    }

    @Test
    fun `fehlende Uhrdaten werden benannt`() {
        assertEquals("Noch keine Daten von der Uhr.", sleepSignal(SleepAssessment.unavailable("x", 0)).sentence)
        assertEquals(SignalTone.NEUTRAL, sleepSignal(SleepAssessment.unavailable("x", 5)).tone)
    }

    @Test
    fun `Belastung ohne Formwert und im Aufbau`() {
        assertEquals("keine Daten", loadSignal(null).word)
        assertEquals("etwas müde", loadSignal(-15.0).word)
        assertEquals(SignalTone.WARNUNG, loadSignal(-40.0).tone)
    }

    @Test
    fun `Notiz nennt die naechste gewichtige Einheit`() {
        val upcoming = upcomingKeySession(week, todayIndex = 3, todayKm = 45)
        assertEquals(UpcomingSession("Samstag", "die lange Fahrt", 80), upcoming)
        val note = whyNote(TodayEffort.LOCKER, route(null, null), false, upcoming, false, hasPlan = true)
        assertEquals(
            listOf("Samstag steht die lange Fahrt mit 80 km an. Heute nicht überziehen, dann hast du dafür genug Kraft."),
            note,
        )
        assertNull(upcomingKeySession(week, todayIndex = 5, todayKm = 80))
    }

    // ----------------------------------------------------- Wochenstreifen

    @Test
    fun `Streifen zeigt erledigt, heute, geplant und frei`() {
        val thursday = LocalDate.of(2026, 9, 24)
        val ridden = mapOf(LocalDate.of(2026, 9, 22) to 31.6, LocalDate.of(2026, 9, 27) to 0.0)
        val strip = weekStrip(thursday, week, ridden, todayKm = 45)
        assertEquals(listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"), strip.map { it.label })
        assertEquals(
            listOf(
                StripState.REST, StripState.DONE, StripState.REST, StripState.TODAY,
                StripState.REST, StripState.PLANNED, StripState.REST,
            ),
            strip.map { it.state },
        )
        assertEquals(32, strip[1].km)
        assertEquals("Donnerstag, heute: 45 km geplant", strip[3].description)
        assertEquals("Samstag: 80 km geplant", strip[5].description)

        assertEquals("32 von 120 km" to "noch 2 Fahrten", weekSummary(31.6, 120, strip, rideCount = 1))
        assertEquals("32 km diese Woche" to "1 Fahrt", weekSummary(31.6, null, strip, rideCount = 1))
    }

    @Test
    fun `heute schon gefahren zaehlt als erledigt`() {
        val thursday = LocalDate.of(2026, 9, 24)
        val strip = weekStrip(thursday, week, mapOf(thursday to 44.0), todayKm = 45)
        assertEquals(StripState.DONE, strip[3].state)
        assertTrue(strip[3].isToday)
    }

    // --------------------------------------------------------------- Ziel

    @Test
    fun `Zielzeile mit Datum, Restzeit und Planwoche`() {
        val today = LocalDate.of(2026, 9, 25)
        val goal = LocalDate.of(2026, 12, 19)
        assertEquals("Sa, 19. Dezember · noch 12 Wochen · Woche 3 von 15", goalLine(today, goal, 2, 15))
        assertEquals("Sa, 19. Dezember · noch 12 Wochen", goalLine(today, goal, -1, 15))
        assertEquals("noch 5 Tage", goalCountdown(today, today.plusDays(5)))
        assertEquals("morgen", goalCountdown(today, today.plusDays(1)))
        assertEquals("vorbei", goalCountdown(today, today.minusDays(1)))
        assertEquals("Fr, 1. Januar 2027", formatGoalDate(today, LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `Fortschritt ueber die Planlaufzeit`() {
        val start = LocalDate.of(2026, 9, 1)
        assertEquals(0.5f, goalProgress(start, start.plusDays(20), start.plusDays(10)))
        assertEquals(1f, goalProgress(start, start.plusDays(20), start.plusDays(30)))
    }

    @Test
    fun `Stunden und Minuten`() {
        assertEquals("7 h 40 min", formatHoursMinutes(7.0 + 40.0 / 60))
        assertEquals("8 h", formatHoursMinutes(7.999))
        assertEquals("45 min", formatHoursMinutes(0.75))
    }
}
