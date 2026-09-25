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
        assertEquals(
            "Im Plan standen 45 km. Schieb die Fahrt lieber um einen Tag – oder roll nur kurz und locker.",
            todaySentence(effort, skipped),
        )
        // Ohne Planeinheit: kein Widerspruch zum Knopf „Locker rollen" darunter.
        assertEquals(
            "Kein Training heute. Wenn du trotzdem aufs Rad willst: kurz und locker.",
            todaySentence(TodayEffort.RUHETAG, route(null, null)),
        )

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

    // ------------------------------------------------------------ Angebot

    /** Die lockere Ruhetagsrunde, wie `restDayRideTarget` sie liefern koennte. */
    private val easy = target(16.0, SessionIntensity.LOCKER)

    /** Tagesart und Angebot in einem Zug — dieselbe Kette wie `decideToday`. */
    private fun offer(r: TodayRoute, planRestDay: Boolean, weekSessions: List<TrainingSession>) =
        offeredTarget(r, todayEffort(r, planRestDay, weekSessions), easy)

    @Test
    fun `Plan-Ruhetag bietet die lockere Runde statt der Tagesempfehlung`() {
        // Die Tagesempfehlung haette 21 km — genau die stand frueher als
        // „Heute 21 km" auf der Karte, waehrend „Heute" Ruhetag sagte.
        val free = route(target(21.0, SessionIntensity.GRUNDLAGE), null)
        val o = offer(free, planRestDay = true, weekSessions = week)
        assertEquals(TodayOffer(easy, restDay = true), o)
        // Kurz genug fuer den halbbreiten Knopf auf der Karte, mit Kilometern.
        assertEquals("Locker · 16 km", offerChipLabel(o!!))
        assertEquals("Locker rollen · 16 km", offerButtonLabel(o))
        assertEquals("Locker rollen", offerDialogAction(o))
        assertEquals(
            "Heute ist Ruhetag. Wenn du trotzdem fahren magst: 16 km locker rollen.",
            offerHint(o, restHeadline(free, planRestDay = true)),
        )
    }

    @Test
    fun `Tagesform-Ruhetag bietet die lockere Runde, auch mit Planeinheit`() {
        val skipped = route(null, week[1], downgraded = true)
        val o = offer(skipped, false, week)
        assertEquals(TodayOffer(easy, restDay = true), o)
        // Der Dialog nennt denselben Grund wie die Schlagzeile in „Heute".
        assertEquals(
            "Heute lieber Pause statt Training. Wenn du trotzdem fahren magst: 16 km locker rollen.",
            offerHint(o!!, restHeadline(skipped, planRestDay = false)),
        )
        // Ohne Plan genauso.
        assertEquals(TodayOffer(easy, restDay = true), offer(route(null, null), false, emptyList()))
    }

    @Test
    fun `Zieltag bietet keine Runde`() {
        val event = session("Sa", 120, SessionIntensity.HART, isEvent = true)
        assertNull(offer(route(null, event), false, week))
    }

    @Test
    fun `Fahrtag im Plan bietet die Tagesrunde`() {
        val planned = target(45.0, SessionIntensity.GRUNDLAGE)
        val o = offer(route(planned, week[1]), false, week)
        assertEquals(TodayOffer(planned, restDay = false), o)
        assertEquals("Heute 45 km", offerChipLabel(o!!))
        assertEquals("Runde für heute bauen", offerButtonLabel(o))
        assertEquals("Heute stehen 45 km an.", offerHint(o))
    }

    @Test
    fun `Karte, Heute und Dialog runden dieselbe Zahl`() {
        // Stunden × Tempo hat fast immer Nachkommastellen. Frueher schnitt die
        // Karte ab („Heute 44 km"), waehrend „Heute" und der Dialog rundeten.
        val o = TodayOffer(target(44.6, SessionIntensity.GRUNDLAGE), restDay = false)
        assertEquals("Heute 45 km", offerChipLabel(o))
        assertEquals("Heute stehen 45 km an.", offerHint(o))
        val rest = TodayOffer(target(16.7, SessionIntensity.LOCKER), restDay = true)
        assertEquals("Locker · 17 km", offerChipLabel(rest))
        assertEquals("Locker rollen · 17 km", offerButtonLabel(rest))
    }

    @Test
    fun `ohne Plan und ausserhalb der Planwochen gilt die Tagesempfehlung`() {
        // Ausserhalb der Planwochen gibt es keine laufende Woche, also auch
        // keinen Plan-Ruhetag — genau wie ganz ohne Plan.
        val fromRecommendation = target(21.0, SessionIntensity.GRUNDLAGE)
        val o = offer(route(fromRecommendation, null), planRestDay = false, weekSessions = emptyList())
        assertEquals(TodayOffer(fromRecommendation, restDay = false), o)
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
    fun `erreichtes Wochenziel schlaegt offene Fahrten`() {
        val thursday = LocalDate.of(2026, 9, 24)
        // Noch eine Fahrt geplant (Samstag) — trotzdem kein Rueckstand.
        val strip = weekStrip(thursday, week, emptyMap(), todayKm = null)
        assertEquals("79 von 40 km" to "Wochenziel geschafft", weekSummary(79.0, 40, strip, rideCount = 3))
        // Angezeigt wird die gerundete Zahl — „40 von 40 km" ist geschafft.
        assertEquals("40 von 40 km" to "Wochenziel geschafft", weekSummary(39.6, 40, strip, rideCount = 2))
        // Knapp darunter bleibt es beim Offenen.
        assertEquals("39 von 40 km" to "noch 1 Fahrt", weekSummary(39.4, 40, strip, rideCount = 2))
    }

    @Test
    fun `unter Ziel ohne offene Fahrt bleibt der Zusatz leer`() {
        val sunday = LocalDate.of(2026, 9, 27)
        val strip = weekStrip(sunday, week, emptyMap(), todayKm = null)
        assertEquals("30 von 40 km" to null, weekSummary(30.0, 40, strip, rideCount = 2))
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
