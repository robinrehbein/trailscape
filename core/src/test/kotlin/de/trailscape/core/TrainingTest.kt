package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vollstaendige Portierung von `test/training_test.dart` (29 Faelle).
 *
 * Die Gruppe `savePlan / loadPlan` laeuft statt gegen `SharedPreferences`
 * gegen [InMemoryTrainingPlanStore] — die Erwartungswerte sind unveraendert.
 */
class TrainingTest {

    /**
     * Feste Zeitpunkte statt `LocalDateTime.now()`, damit die Tests
     * reproduzierbar sind. Mittwoch, 7. Januar 2026, 12:00 lokaler Zeit.
     */
    private val now: Long = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))

    /** Montag der Woche von [now]. */
    private val firstMonday: LocalDate = LocalDate.of(2026, 1, 5)

    private fun ms(date: LocalDateTime): Long = dartEpochMs(date)

    /** Zeitstempel am Tag [dayOffset] nach dem ersten Montag, 09:00 lokal. */
    private fun dayAfterFirstMonday(dayOffset: Int, hour: Int = 9): Long =
        ms(firstMonday.plusDays(dayOffset.toLong()).atTime(hour, 0))

    private fun goalAt(
        timestamp: Long,
        distanceKm: Double = 160.0,
        ascentM: Double? = 2200.0,
    ): Goal = Goal(
        name = "Gravel Grinder",
        distanceKm = distanceKm,
        ascentM = ascentM,
        date = timestamp,
    )

    private val advanced = FitnessAssessment(
        level = FitnessLevel.FORTGESCHRITTEN,
        weeklyKm = 95.0,
        weeklyHm = 900.0,
        weeklyRides = 2.0,
        longestRideKm = 80.0,
        rideCount = 16,
    )

    private val beginner = FitnessAssessment(
        level = FitnessLevel.EINSTEIGER,
        weeklyKm = 12.0,
        weeklyHm = 120.0,
        weeklyRides = 1.0,
        longestRideKm = 25.0,
        rideCount = 8,
    )

    private fun ride(createdAt: Long, distanceKm: Double): Ride = Ride(
        id = "r$createdAt",
        name = "Fahrt",
        createdAt = createdAt,
        points = emptyList(),
        stats = RideStats(distanceKm = distanceKm, ascentM = 0.0, descentM = 0.0),
    )

    // -----------------------------------------------------------------------
    // group('generatePlan – 12-Wochen-Plan (Fortgeschritten)')
    // Zielwoche ist Index 11, Event am Samstag dieser Woche.
    // -----------------------------------------------------------------------

    private val twelveWeekGoal = goalAt(dayAfterFirstMonday(11 * 7 + 5))
    private val twelveWeekPlan = generatePlan(twelveWeekGoal, advanced, now = now, texts = CoreTextsDe)

    @Test
    fun `12 Wochen - Plan-Rahmendaten stimmen`() {
        assertEquals(12, twelveWeekPlan.weeks.size)
        assertEquals(FitnessLevel.FORTGESCHRITTEN, twelveWeekPlan.level)
        assertEquals(now, twelveWeekPlan.createdAt)
        assertEquals(160.0, twelveWeekPlan.goal.distanceKm)
    }

    @Test
    fun `12 Wochen - Wochenraster liegt Montag bis Sonntag lokal`() {
        assertEquals(ms(firstMonday.atStartOfDay()), twelveWeekPlan.weeks.first().start)
        for (i in twelveWeekPlan.weeks.indices) {
            val week = twelveWeekPlan.weeks[i]
            assertEquals(i, week.index)
            val start = dartLocalOf(week.start)
            val end = dartLocalOf(week.end)
            assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
            assertEquals(0, start.hour)
            assertEquals(DayOfWeek.MONDAY, end.dayOfWeek)
            assertEquals(0, end.hour)
            if (i > 0) {
                assertEquals(twelveWeekPlan.weeks[i - 1].end, week.start)
            }
        }
        // Der Zieltermin liegt in der letzten Woche.
        assertTrue(twelveWeekGoal.date >= twelveWeekPlan.weeks.last().start)
        assertTrue(twelveWeekGoal.date < twelveWeekPlan.weeks.last().end)
    }

    @Test
    fun `12 Wochen - Wochenfolge mit zwei Taperwochen vor der Zielwoche`() {
        // Ab 8 Wochen zwei Taperwochen (Bosquet 2007: ~2 Wochen Taper); die
        // Erholungswoche faellt nie auf die letzte Aufbauwoche vor dem Taper.
        assertEquals(
            listOf(
                WeekKind.AUFBAU,
                WeekKind.AUFBAU,
                WeekKind.AUFBAU,
                WeekKind.ERHOLUNG,
                WeekKind.AUFBAU,
                WeekKind.AUFBAU,
                WeekKind.AUFBAU,
                WeekKind.ERHOLUNG,
                WeekKind.AUFBAU,
                WeekKind.TAPER,
                WeekKind.TAPER,
                WeekKind.ZIELWOCHE,
            ),
            twelveWeekPlan.weeks.map { it.kind },
        )
    }

    @Test
    fun `12 Wochen - Progression vom tatsaechlichen Umfang, gedeckelt auf plus 15 Prozent je Aufbauwoche`() {
        val builds = twelveWeekPlan.weeks
            .filter { it.kind == WeekKind.AUFBAU }
            .map { it.targetKm }
        // Woche 0 startet beim tatsaechlichen Umfang (95 km), nicht hoeher.
        assertTrue(builds.first() in 90..100, "Woche 0: ${builds.first()} km")
        // Der Umfang waechst, aber nie um mehr als 15 % (mindestens 5 km) — gemessen
        // an der tatsaechlichen Wochensumme, ohne Toleranz.
        assertTrue(builds.last() > builds.first())
        for (i in 1 until builds.size) {
            assertTrue(
                builds[i] <= rampCapKm(builds[i - 1].toDouble()),
                "Sprung ${builds[i - 1]} → ${builds[i]} km ist zu gross",
            )
        }
        // Das Wochenziel ist die Summe der Einheiten.
        for (week in twelveWeekPlan.weeks) {
            assertEquals(week.sessions.sumOf { it.targetKm }, week.targetKm, "Woche ${week.index}")
        }
    }

    @Test
    fun `12 Wochen - Erholungswochen kuerzen den Umfang um 40 bis 50 Prozent und halten Haeufigkeit und Reiz`() {
        for (week in twelveWeekPlan.weeks) {
            if (week.kind != WeekKind.ERHOLUNG) continue
            val previous = twelveWeekPlan.weeks[week.index - 1]
            val share = week.targetKm.toDouble() / previous.targetKm
            assertTrue(share in 0.45..0.60, "Erholungswoche ${week.index}: ${(share * 100).toInt()} %")
            // Genau eine Einheit weniger (mindestens zwei).
            assertEquals(maxOf(2, previous.sessions.size - 1), week.sessions.size, "Haeufigkeit in Woche ${week.index}")
            // Ein kurzer Intensitaetsreiz bleibt (assessDeload: „Intensitaet bleibt").
            assertEquals(1, week.sessions.count { it.intensity == SessionIntensity.HART })
            // Die lange Fahrt ist deutlich gekuerzt.
            assertTrue(week.sessions.maxOf { it.targetKm } < previous.sessions.maxOf { it.targetKm } * 0.8)
        }
    }

    @Test
    fun `12 Wochen - Taper reduziert progressiv und haelt Haeufigkeit und Intensitaet`() {
        val peakWeek = twelveWeekPlan.weeks.filter { it.kind == WeekKind.AUFBAU }.maxBy { it.targetKm }
        val tapers = twelveWeekPlan.weeks.filter { it.kind == WeekKind.TAPER }
        assertEquals(2, tapers.size)
        val shares = tapers.map { it.targetKm.toDouble() / peakWeek.targetKm }
        assertTrue(shares[0] in 0.55..0.75, "Taper 1: $shares")
        assertTrue(shares[1] in 0.38..0.55, "Taper 2: $shares")
        val lastBuild = twelveWeekPlan.weeks[tapers.first().index - 1]
        for (taper in tapers) {
            // Haeufigkeit bleibt im Taper gleich (Bosquet 2007, Mujika & Padilla 2003).
            assertEquals(lastBuild.sessions.size, taper.sessions.size, "Haeufigkeit in Woche ${taper.index}")
            assertTrue(taper.sessions.any { it.intensity == SessionIntensity.HART }, "Intensitaet in Woche ${taper.index}")
        }
    }

    @Test
    fun `12 Wochen - Zielwoche enthaelt Vorbelastung, Aktivierung und Zielevent`() {
        val zielwoche = twelveWeekPlan.weeks.last()
        assertEquals(WeekKind.ZIELWOCHE, zielwoche.kind)

        val event = zielwoche.sessions.last()
        assertEquals("Sa", event.day)
        assertEquals("Zielevent: Gravel Grinder", event.title)
        // targetKm der Zieleinheit entspricht der Zieldistanz.
        assertEquals(160, event.targetKm)
        // 2200 Hm ≥ 1000 → Hoehenmeter-Hinweis in der Beschreibung.
        assertTrue(event.description.contains("2200 Hm"))

        // Aktivierung am Vortag, kurze Vorbelastung 3–5 Tage vorher.
        val activation = zielwoche.sessions.single { it.title == "Aktivierung locker" }
        assertEquals("Fr", activation.day)
        assertTrue(activation.durationMin!! <= 45)
        val opener = zielwoche.sessions.single { !it.isEvent && it.intensity == SessionIntensity.HART }
        assertTrue(opener.day in listOf("Mo", "Di", "Mi"), opener.day)

        assertEquals(zielwoche.sessions.sumOf { it.targetKm }, zielwoche.targetKm)
    }

    @Test
    fun `12 Wochen - Session-Summen treffen das Wochenziel auf plusminus 10 Prozent`() {
        for (week in twelveWeekPlan.weeks) {
            if (week.kind == WeekKind.ZIELWOCHE) continue
            val sum = week.sessions.sumOf { it.targetKm }
            assertTrue(
                sum >= floor(week.targetKm * 0.9).toInt(),
                "Woche ${week.index}",
            )
            assertTrue(
                sum <= ceil(week.targetKm * 1.1).toInt(),
                "Woche ${week.index}",
            )
        }
    }

    @Test
    fun `12 Wochen - Aufbauwochen Fortgeschritten haben drei Einheiten mit Hoehenmeter-Hinweis`() {
        val build = twelveWeekPlan.weeks.first()
        assertEquals(listOf("Di", "Do", "Sa"), build.sessions.map { it.day })
        // Grundlagenphase: Sweet Spot als Qualitaet, dazu GA1 und die lange Tour.
        assertEquals(listOf("Sweet-Spot-Intervalle", "GA1", "Lange Tour"), build.sessions.map { it.title })
        assertTrue(build.sessions.last().description.contains("Baue dabei bewusst Anstiege ein"))
    }

    @Test
    fun `12 Wochen - ohne nennenswerte Hoehenmeter fehlt der Anstiegs-Hinweis`() {
        val flat = generatePlan(
            goalAt(dayAfterFirstMonday(11 * 7 + 5), ascentM = 400.0),
            advanced,
            now = now,
            texts = CoreTextsDe,
        )
        assertFalse(
            flat.weeks.first().sessions.last().description
                .contains("Baue dabei bewusst Anstiege ein"),
        )
        assertFalse(flat.weeks.last().sessions.last().description.contains("Hm"))
    }

    // -----------------------------------------------------------------------
    // group('generatePlan – 3-Wochen-Minimalplan (Einsteiger)')
    // Zielwoche ist Index 2, Event am Samstag.
    // -----------------------------------------------------------------------

    private val minimalPlan = generatePlan(
        goalAt(dayAfterFirstMonday(2 * 7 + 5), distanceKm = 30.0, ascentM = null),
        beginner,
        now = now,
        texts = CoreTextsDe,
    )

    @Test
    fun `3 Wochen - drei Wochen Aufbau, Taper, Zielwoche`() {
        assertEquals(3, minimalPlan.weeks.size)
        assertEquals(
            listOf(WeekKind.AUFBAU, WeekKind.TAPER, WeekKind.ZIELWOCHE),
            minimalPlan.weeks.map { it.kind },
        )
    }

    @Test
    fun `3 Wochen - Start beim tatsaechlichen Umfang, Basisvolumen nur ohne Historie`() {
        // Mit Historie (12 km/Woche) startet der Plan beim Umfang, mindestens 15 km —
        // frueher sprang er auf das Basisvolumen von 40 km.
        // Einzige Aufbauwoche: hoechstens +5 km gegen den Start.
        assertTrue(minimalPlan.weeks[0].targetKm in 15..20, "Woche 0: ${minimalPlan.weeks[0].targetKm}")
        assertEquals(FitnessLevel.EINSTEIGER, minimalPlan.level)

        // Ohne Historie gilt das Basisvolumen der Stufe.
        val noHistory = generatePlan(
            goalAt(dayAfterFirstMonday(2 * 7 + 5), distanceKm = 30.0, ascentM = null),
            beginner.copy(weeklyKm = 0.0, rideCount = 0),
            now = now,
            texts = CoreTextsDe,
        )
        assertEquals(40, noHistory.weeks[0].targetKm)
        // Der Taper rechnet mit dem erreichten Umfang (eine Taperwoche bei kurzen Plaenen).
        assertTrue(noHistory.weeks[1].targetKm < 40)
    }

    @Test
    fun `3 Wochen - Einsteiger-Aufbau unter 60 km hat nur zwei Einheiten`() {
        val sessions = minimalPlan.weeks[0].sessions
        assertEquals(2, sessions.size)
        assertEquals(listOf("Di", "Sa"), sessions.map { it.day })
        // Die lange Tour traegt den groesseren Teil (Gewicht 1,5 : 1).
        assertTrue(sessions[1].targetKm > sessions[0].targetKm)
        // Einsteiger fahren in den ersten Wochen keine Intensitaet.
        assertTrue(sessions.none { it.intensity == SessionIntensity.HART })
    }

    @Test
    fun `3 Wochen - ein 90-km-Ziel treibt den Einsteiger nicht mehr auf 90 km in Woche 1`() {
        val big = generatePlan(
            goalAt(dayAfterFirstMonday(2 * 7 + 5), distanceKm = 90.0, ascentM = null),
            beginner,
            now = now,
            texts = CoreTextsDe,
        )
        // Eine einzige Aufbauwoche: hoechstens +15 % bzw. +5 km gegen den Start (15 km).
        assertTrue(big.weeks[0].targetKm <= 20, "Woche 1: ${big.weeks[0].targetKm} km")
        assertEquals(listOf("Di", "Sa"), big.weeks[0].sessions.map { it.day })
    }

    @Test
    fun `Einsteiger-Aufbau ab 60 km bekommt eine dritte Einheit`() {
        val long = generatePlan(
            goalAt(dayAfterFirstMonday(23 * 7 + 5), distanceKm = 200.0, ascentM = null),
            beginner.copy(weeklyKm = 45.0),
            now = now,
            texts = CoreTextsDe,
        )
        val week = long.weeks.first { it.kind == WeekKind.AUFBAU && it.targetKm >= 60 }
        assertEquals(listOf("Di", "Sa", "So"), week.sessions.map { it.day })
        // Die lange Tour ist die laengste Einheit der Woche.
        assertEquals(week.sessions.maxBy { it.targetKm }.title, "Lange Tour")
    }

    @Test
    fun `3 Wochen - Montags-Event bekommt keine Aktivierung`() {
        val mondayGoal = generatePlan(
            goalAt(dayAfterFirstMonday(2 * 7), distanceKm = 30.0, ascentM = null),
            beginner,
            now = now,
            texts = CoreTextsDe,
        )
        val zielwoche = mondayGoal.weeks.last()
        assertEquals(1, zielwoche.sessions.size)
        assertEquals("Mo", zielwoche.sessions.single().day)
        assertEquals(30, zielwoche.targetKm)
    }

    @Test
    fun `3 Wochen - Dienstags-Event bekommt Aktivierung am Montag`() {
        val tuesdayGoal = generatePlan(
            goalAt(dayAfterFirstMonday(2 * 7 + 1), distanceKm = 30.0, ascentM = null),
            beginner,
            now = now,
            texts = CoreTextsDe,
        )
        val zielwoche = tuesdayGoal.weeks.last()
        assertEquals(2, zielwoche.sessions.size)
        assertEquals("Mo", zielwoche.sessions.first().day)
        assertEquals("Di", zielwoche.sessions.last().day)
        assertEquals(zielwoche.sessions.sumOf { it.targetKm }, zielwoche.targetKm)
    }

    // -----------------------------------------------------------------------
    // group('generatePlan – Fehlerfälle und Grenzen')
    // -----------------------------------------------------------------------

    @Test
    fun `Fehler - Ziel in dieser Woche ist zu nah`() {
        val error = assertFailsWith<IllegalArgumentException> {
            generatePlan(goalAt(dayAfterFirstMonday(4)), advanced, now = now, texts = CoreTextsDe)
        }
        assertEquals(errorTooSoon(CoreTextsDe), error.message)
    }

    @Test
    fun `Fehler - Ziel in 2 Wochen ist zu nah`() {
        val error = assertFailsWith<IllegalArgumentException> {
            generatePlan(goalAt(dayAfterFirstMonday(7 + 3)), advanced, now = now, texts = CoreTextsDe)
        }
        assertEquals(errorTooSoon(CoreTextsDe), error.message)
    }

    @Test
    fun `Fehler - Ziel in der Vergangenheit ist zu nah`() {
        val error = assertFailsWith<IllegalArgumentException> {
            generatePlan(goalAt(dayAfterFirstMonday(-5)), advanced, now = now, texts = CoreTextsDe)
        }
        assertEquals(errorTooSoon(CoreTextsDe), error.message)
    }

    @Test
    fun `Fehler - Ziel ueber 52 Wochen entfernt ist zu weit`() {
        val error = assertFailsWith<IllegalArgumentException> {
            generatePlan(goalAt(dayAfterFirstMonday(52 * 7 + 3)), advanced, now = now, texts = CoreTextsDe)
        }
        assertEquals(errorTooFar(CoreTextsDe), error.message)
    }

    @Test
    fun `Fehler - Grenzen 3 und 52 Wochen sind gueltig`() {
        val min = generatePlan(goalAt(dayAfterFirstMonday(2 * 7 + 3)), advanced, now = now, texts = CoreTextsDe)
        assertEquals(3, min.weeks.size)

        val max = generatePlan(goalAt(dayAfterFirstMonday(51 * 7 + 3)), advanced, now = now, texts = CoreTextsDe)
        assertEquals(52, max.weeks.size)
    }

    // -----------------------------------------------------------------------
    // group('currentWeekIndex')
    // -----------------------------------------------------------------------

    @Test
    fun `currentWeekIndex - minus 1 vor Planbeginn`() {
        assertEquals(-1, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks.first().start - 1))
        assertEquals(-1, currentWeekIndex(twelveWeekPlan, now = dayAfterFirstMonday(-3)))
    }

    @Test
    fun `currentWeekIndex - liefert die laufende Woche`() {
        assertEquals(0, currentWeekIndex(twelveWeekPlan, now = now))
        assertEquals(0, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks.first().start))
        assertEquals(5, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks[5].start))
        assertEquals(5, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks[5].end - 1))
        assertEquals(6, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks[5].end))
    }

    @Test
    fun `currentWeekIndex - klemmt nach Planende auf die letzte Woche`() {
        assertEquals(11, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks.last().end))
        assertEquals(
            11,
            currentWeekIndex(
                twelveWeekPlan,
                now = twelveWeekPlan.weeks.last().end + 90L * 86400000,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // group('sessionsForDay') — Grundlage der Startseite „Heute"
    // -----------------------------------------------------------------------

    @Test
    fun `sessionsForDay - liefert die Einheit des laufenden Wochentags`() {
        // Fortgeschritten, Aufbauwoche: Di Sweet Spot, Do GA1, Sa Lange Tour.
        val tuesday = sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(1))
        assertEquals(1, tuesday.size)
        assertEquals("Sweet-Spot-Intervalle", tuesday.first().title)
        assertEquals("Di", tuesday.first().day)

        val saturday = sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(5))
        assertEquals(listOf("Lange Tour"), saturday.map { it.title })
    }

    @Test
    fun `sessionsForDay - an einem Ruhetag leer`() {
        // Montag und Freitag tragen im Fortgeschrittenen-Raster keine Einheit.
        assertTrue(sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(0)).isEmpty())
        assertTrue(sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(4)).isEmpty())
    }

    @Test
    fun `sessionsForDay - beruecksichtigt die Art der laufenden Woche`() {
        // Woche 3 ist Erholung (eine Einheit weniger als im Aufbau): Di kurzer Reiz, Sa verkuerzte lange Tour.
        assertEquals(WeekKind.ERHOLUNG, twelveWeekPlan.weeks[3].kind)
        assertEquals(
            listOf("Verkürzte lange Tour"),
            sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(3 * 7 + 5)).map { it.title },
        )
        assertTrue(sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(3 * 7 + 3)).isEmpty())
    }

    @Test
    fun `sessionsForDay - ausserhalb der Planlaufzeit leer`() {
        // Vor Planbeginn …
        assertTrue(sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(-6)).isEmpty())
        // … und nach Planende, wo currentWeekIndex auf die letzte Woche klemmt.
        assertTrue(sessionsForDay(twelveWeekPlan, now = twelveWeekPlan.weeks.last().end).isEmpty())
        assertTrue(
            sessionsForDay(
                twelveWeekPlan,
                now = twelveWeekPlan.weeks.last().end + 30L * 86400000,
            ).isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // group('weekKm')
    // -----------------------------------------------------------------------

    @Test
    fun `weekKm - summiert halboffen start bis end auf eine Nachkommastelle`() {
        val week = twelveWeekPlan.weeks[1]
        val rides = listOf(
            ride(week.start - 1, 100.0), // davor
            ride(week.start, 12.34), // exakt am Start → zaehlt
            ride(week.start + 3L * 86400000, 20.01), // mitten drin
            ride(week.end - 1, 7.6), // letzte Millisekunde → zaehlt
            ride(week.end, 500.0), // exakt am Ende → zaehlt nicht
        )
        assertEquals(40.0, weekKm(week, rides))
    }

    @Test
    fun `weekKm - ohne passende Fahrten 0`() {
        assertEquals(0.0, weekKm(twelveWeekPlan.weeks[2], emptyList()))
        assertEquals(
            0.0,
            weekKm(twelveWeekPlan.weeks[2], listOf(ride(twelveWeekPlan.weeks[2].end, 50.0))),
        )
    }

    // -----------------------------------------------------------------------
    // group('generatePlan – Last-Budgets (currentCtl)')
    // -----------------------------------------------------------------------

    /** Summe der Last-Ziele einer Woche. */
    private fun weekLoad(plan: TrainingPlan, index: Int): Double =
        plan.weeks[index].sessions.sumOf { it.targetLoad ?: 0.0 }

    @Test
    fun `alle Einheiten tragen Last-Ziele aus ihrer Struktur`() {
        for (week in twelveWeekPlan.weeks) {
            for (session in week.sessions) {
                assertNotNull(session.targetLoad, "${week.index}/${session.title}")
                assertTrue(session.targetLoad!! >= 1.0)
            }
        }
        // Eine Qualitaetseinheit kostet pro Stunde mehr als Grundlage (IF²) —
        // im flachen Plan, ohne Hoehenmeter-Aufschlag auf die Grundlage.
        val flat = generatePlan(goalAt(dayAfterFirstMonday(11 * 7 + 5), ascentM = null), advanced, now = now, texts = CoreTextsDe)
        val build = flat.weeks.first()
        val ga = build.sessions.first { it.title == "GA1" }
        val hart = build.sessions.first { it.intensity == SessionIntensity.HART }
        assertTrue(
            hart.targetLoad!! / hart.durationMin!! > ga.targetLoad!! / ga.durationMin!!,
            "harte Last/min vs GA",
        )
    }

    @Test
    fun `mit currentCtl bleibt jede Aufbauwoche unter dem 1,3-fachen der Referenz`() {
        val ctl = 30.0
        val plan = generatePlan(twelveWeekGoal, advanced, now = now, currentCtl = ctl, texts = CoreTextsDe)
        var reference = 7 * ctl
        for (week in plan.weeks.filter { it.kind == WeekKind.AUFBAU }) {
            val load = weekLoad(plan, week.index)
            assertTrue(load <= 1.30 * reference + 1, "Woche ${week.index}: $load gegen ${1.3 * reference}")
            reference = maxOf(reference, load)
        }
        // Woche 0 liegt nah an der gewohnten Wochenlast — kein Sprung um +74 % mehr.
        assertTrue(weekLoad(plan, 0) <= 1.30 * 7 * ctl)
        // Das Zielevent hat eine Last, wird aber nicht budgetiert.
        assertNotNull(plan.weeks.last().sessions.last().targetLoad)
    }

    @Test
    fun `eine sehr kleine CTL kuerzt den Umfang statt die Last hochzuskalieren`() {
        // CTL 5 → Referenz 35 Lastpunkte; der Deckel (≤ 1,3 × Referenz bzw.
        // letzte Aufbauwoche) muss den Umfang kuerzen.
        val free = generatePlan(twelveWeekGoal, advanced, now = now, texts = CoreTextsDe)
        val capped = generatePlan(twelveWeekGoal, advanced, now = now, currentCtl = 5.0, texts = CoreTextsDe)
        assertTrue(capped.weeks[0].targetKm < free.weeks[0].targetKm)
        // Exakt unter dem Deckel — die Untergrenze (eine Mindesteinheit) greift bei CTL 5 noch nicht.
        assertTrue(weekLoad(capped, 0) <= 1.30 * 35, "Woche 0: ${weekLoad(capped, 0)}")
        var reference = 35.0
        for (week in capped.weeks.filter { it.kind == WeekKind.AUFBAU }) {
            assertTrue(weekLoad(capped, week.index) <= 1.30 * reference, "Woche ${week.index}: ${weekLoad(capped, week.index)}")
            reference = maxOf(reference, weekLoad(capped, week.index))
        }
        // Last und Dauer passen weiterhin zusammen (keine Skalierung der Zahlen).
        for (s in capped.weeks.flatMap { it.sessions }.filter { it.intensity == SessionIntensity.GRUNDLAGE }) {
            val implied = impliedSessionIf(s) ?: continue
            assertTrue(implied in 0.65..0.80, "${s.title}: IF $implied")
        }
    }

    @Test
    fun `currentCtl null oder unbrauchbar verhaelt sich wie bisher`() {
        assertEquals(
            twelveWeekPlan,
            generatePlan(twelveWeekGoal, advanced, now = now, currentCtl = null, texts = CoreTextsDe),
        )
        assertEquals(
            twelveWeekPlan,
            generatePlan(twelveWeekGoal, advanced, now = now, currentCtl = 0.0, texts = CoreTextsDe),
        )
        assertEquals(
            twelveWeekPlan,
            generatePlan(twelveWeekGoal, advanced, now = now, currentCtl = Double.NaN, texts = CoreTextsDe),
        )
    }

    // -----------------------------------------------------------------------
    // group('savePlan / loadPlan')
    // -----------------------------------------------------------------------

    @Test
    fun `Speicher - ohne gespeicherten Plan kommt null zurueck`() {
        assertNull(loadPlan(InMemoryTrainingPlanStore()))
    }

    @Test
    fun `Speicher - JSON-Roundtrip erhaelt den vollstaendigen Plan`() {
        val store = InMemoryTrainingPlanStore()
        val plan = generatePlan(goalAt(dayAfterFirstMonday(11 * 7 + 5)), advanced, now = now, texts = CoreTextsDe)
        savePlan(store, plan)

        val loaded = loadPlan(store)
        assertNotNull(loaded)
        assertEquals(plan.createdAt, loaded.createdAt)
        assertEquals(plan.level, loaded.level)
        assertEquals(plan.goal.name, loaded.goal.name)
        assertEquals(plan.goal.distanceKm, loaded.goal.distanceKm)
        assertEquals(plan.goal.ascentM, loaded.goal.ascentM)
        assertEquals(plan.goal.date, loaded.goal.date)
        assertEquals(plan.weeks.size, loaded.weeks.size)

        for (i in plan.weeks.indices) {
            val a = plan.weeks[i]
            val b = loaded.weeks[i]
            assertEquals(a.index, b.index)
            assertEquals(a.start, b.start)
            assertEquals(a.end, b.end)
            assertEquals(a.kind, b.kind)
            assertEquals(a.targetKm, b.targetKm)
            assertEquals(a.sessions.map { it.day }, b.sessions.map { it.day })
            assertEquals(a.sessions.map { it.title }, b.sessions.map { it.title })
            assertEquals(a.sessions.map { it.description }, b.sessions.map { it.description })
            assertEquals(a.sessions.map { it.targetKm }, b.sessions.map { it.targetKm })
            assertEquals(a.sessions.map { it.targetLoad }, b.sessions.map { it.targetLoad })
        }
    }

    @Test
    fun `Speicher - ein Plan-JSON ohne targetLoad laedt mit null-Last-Zielen`() {
        // Alte gespeicherte Plaene (Web-App, fruehere App-Versionen) kennen den
        // Schluessel nicht — fehlender Schluessel ist der Normalfall, kein Fehler.
        val plan = generatePlan(goalAt(dayAfterFirstMonday(11 * 7 + 5)), advanced, now = now, texts = CoreTextsDe)
        // `targetLoad` steht als letzter Schluessel jeder Session im JSON —
        // herausschneiden ergibt exakt das alte Format.
        val legacyJson = plan.toJson().toString()
            .replace(Regex(""","targetLoad":[0-9.Ee+-]+"""), "")
        assertFalse(legacyJson.contains("targetLoad"))

        val store = InMemoryTrainingPlanStore(legacyJson)
        val loaded = loadPlan(store)
        assertNotNull(loaded)
        assertTrue(loaded.weeks.flatMap { it.sessions }.all { it.targetLoad == null })
        // Alles Uebrige kommt unveraendert an.
        assertEquals(plan.weeks.map { it.targetKm }, loaded.weeks.map { it.targetKm })
        assertEquals(
            plan.weeks.flatMap { w -> w.sessions.map { it.targetKm } },
            loaded.weeks.flatMap { w -> w.sessions.map { it.targetKm } },
        )
    }

    @Test
    fun `Speicher - savePlan null entfernt den gespeicherten Plan`() {
        val store = InMemoryTrainingPlanStore()
        val plan = generatePlan(goalAt(dayAfterFirstMonday(11 * 7 + 5)), advanced, now = now, texts = CoreTextsDe)
        savePlan(store, plan)
        assertNotNull(loadPlan(store))

        savePlan(store, null)
        assertNull(loadPlan(store))
    }
}
