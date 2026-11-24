package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.PLAN_WEEKDAY_CODES
import de.trailscape.core.i18n.SessionTextKey
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Je ein Test pro Befund des unabhaengigen Reviews (K1, W1–W7 und die
 * kleineren Punkte), jeweils am Beispiel aus dem Review. Die allgemeinen
 * Invarianten ueber zufaellige Eingaben prueft [PlanFuzzTest].
 */
class PlanReviewTest {
    private val now: Long = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))
    private val firstMonday: LocalDate = LocalDate.of(2026, 1, 5)

    private fun goal(weeks: Int, km: Double, day: Int = 5) =
        Goal("Ziel", km, null, dartEpochMs(firstMonday.plusDays((weeks - 1) * 7L + day).atTime(9, 0)))

    private fun assessment(level: FitnessLevel, weeklyKm: Double) =
        FitnessAssessment(level, weeklyKm, weeklyKm * 8, 3.0, weeklyKm / 2, if (weeklyKm > 0) 20 else 0)

    private fun minutes(w: TrainingWeek) = w.sessions.filterNot { it.isEvent }.sumOf { it.durationMin ?: 0 }
    private fun load(w: TrainingWeek) = w.sessions.filterNot { it.isEvent }.sumOf { it.targetLoad ?: 0.0 }
    private fun round5(km: Double) = maxOf(5, dartRound(km / 5).toInt() * 5)
    private fun dayOf(s: TrainingSession) = PLAN_WEEKDAY_CODES.indexOf(s.day)

    // -----------------------------------------------------------------------
    // K1 – Taper vom erreichten Peak
    // -----------------------------------------------------------------------

    @Test
    fun `K1 - der Taper rechnet mit dem erreichten Peak, nicht mit dem Startumfang`() {
        val plan = generatePlan(
            goal(12, 100.0), assessment(FitnessLevel.FORTGESCHRITTEN, 150.0), now,
            preferences = PlanPreferences(weeklyHours = 5.0), speedKmh = 25.0, texts = CoreTextsDe,
        )
        val peak = plan.weeks.filter { it.kind == WeekKind.AUFBAU }.maxOf { it.targetKm }
        assertTrue(peak < 150, "das Budget drueckt den Aufbau unter den Startumfang: $peak")
        val tapers = plan.weeks.filter { it.kind == WeekKind.TAPER }
        assertTrue(tapers[0].targetKm <= round5(peak * 0.65), "Taper 1 ${tapers[0].targetKm} / Peak $peak")
        assertTrue(tapers[0].targetKm >= peak * 0.55, "Taper 1 ${tapers[0].targetKm} / Peak $peak")
        assertTrue(tapers[1].targetKm <= round5(peak * 0.45), "Taper 2 ${tapers[1].targetKm} / Peak $peak")
    }

    @Test
    fun `K1 - auch adaptPlan rechnet den Taper vom neu gebauten Peak`() {
        val plan = generatePlan(goal(12, 150.0), assessment(FitnessLevel.FORTGESCHRITTEN, 120.0), now, texts = CoreTextsDe)
        // Woche 0 nur zu 30 % gefahren → Neuaufbau ab ~36 km.
        val rides = plan.weeks[0].sessions.mapIndexed { i, s ->
            Ride("r$i", "R", plan.weeks[0].start + dayOf(s) * 86_400_000L + 9 * 3_600_000L, RideStats(s.targetKm * 0.3, 0.0, 0.0))
        }
        val adapted = adaptPlan(plan, rides, now = plan.weeks[1].start + 3_600_000L, texts = CoreTextsDe)
        assertTrue(adapted.adapted)
        val rebuiltPeak = adapted.plan.weeks.drop(1).filter { it.kind == WeekKind.AUFBAU }.maxOf { it.targetKm }
        val tapers = adapted.plan.weeks.filter { it.kind == WeekKind.TAPER }
        assertTrue(tapers[0].targetKm <= round5(rebuiltPeak * 0.65), "${tapers[0].targetKm} / $rebuiltPeak")
        assertTrue(tapers[1].targetKm <= round5(rebuiltPeak * 0.45), "${tapers[1].targetKm} / $rebuiltPeak")
        // Die Zielwoche bleibt woertlich.
        assertEquals(plan.weeks.last(), adapted.plan.weeks.last())
    }

    // -----------------------------------------------------------------------
    // W1 – Kuerzen: lange Tour zuletzt, Mindestdauer
    // -----------------------------------------------------------------------

    @Test
    fun `W1 - bei knappem Budget bleibt die lange Tour die laengste Einheit`() {
        for (ctl in listOf(null, 5.0)) {
            val plan = generatePlan(
                goal(12, 100.0), assessment(FitnessLevel.FORTGESCHRITTEN, 70.0), now, ctl,
                PlanPreferences(trainingDays = listOf(1, 3, 5), weeklyHours = 2.0), speedKmh = 25.0, texts = CoreTextsDe,
            )
            for (w in plan.weeks.filter { it.kind == WeekKind.AUFBAU }) {
                val hard = w.sessions.filter { it.intensity == SessionIntensity.HART }.maxOfOrNull { it.durationMin!! } ?: 0
                val easy = w.sessions.filter { it.intensity != SessionIntensity.HART }.maxOf { it.durationMin!! }
                assertTrue(hard <= easy, "CTL $ctl, Woche ${w.index}: ${w.sessions.map { it.title to it.durationMin }}")
                assertTrue(w.sessions.all { it.durationMin!! >= MIN_SESSION_MIN }, "Woche ${w.index}")
                assertTrue(minutes(w) <= 120)
            }
        }
        // Ohne CTL: In Woche 0 bekommt die lange Tour das ganze Budget statt 25 Minuten.
        val plan = generatePlan(
            goal(12, 100.0), assessment(FitnessLevel.FORTGESCHRITTEN, 70.0), now,
            preferences = PlanPreferences(trainingDays = listOf(1, 3, 5), weeklyHours = 2.0), speedKmh = 25.0, texts = CoreTextsDe,
        )
        val long = plan.weeks[0].sessions.single { isLongRideSession(it) }
        assertTrue(long.durationMin!! >= 110, "${long.durationMin}")
    }

    // -----------------------------------------------------------------------
    // W2 – Zeitbudget immer, auch Ziel- und Testwoche
    // -----------------------------------------------------------------------

    @Test
    fun `W2 - das Zeitbudget gilt in jeder Woche, auch in Ziel- und Testwoche`() {
        for (hours in listOf(1.0, 2.0)) {
            val plan = generatePlan(
                goal(12, 100.0), assessment(FitnessLevel.AMBITIONIERT, 100.0), now,
                preferences = PlanPreferences(trainingDays = (0..6).toList(), weeklyHours = hours), texts = CoreTextsDe,
            )
            for (w in plan.weeks) assertTrue(minutes(w) <= hours * 60, "$hours h, Woche ${w.index}: ${minutes(w)} min")
            // Die Zahl der Einheiten folgt dem Budget (hoechstens Budget / Mindestdauer).
            for (w in plan.weeks) assertTrue(w.sessions.count { !it.isEvent } <= (hours * 60 / MIN_SESSION_MIN).toInt())
        }
        val fitness = generatePlan(
            fitnessGoal("Block", 8, now), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now,
            preferences = PlanPreferences(weeklyHours = 1.5), texts = CoreTextsDe,
        )
        for (w in fitness.weeks) assertTrue(minutes(w) <= 90, "Woche ${w.index}: ${minutes(w)} min")
        assertTrue(fitness.weeks.last().sessions.any { it.textKey == SessionTextKey.BENCHMARK_RIDE })
    }

    // -----------------------------------------------------------------------
    // W3 – Lastdeckel bei kleiner CTL, dokumentierte Untergrenze
    // -----------------------------------------------------------------------

    @Test
    fun `W3 - der Lastdeckel gilt exakt, darunter nur die eine Mindesteinheit`() {
        val g = goal(12, 160.0)
        val a = assessment(FitnessLevel.FORTGESCHRITTEN, 95.0)
        // CTL 5: Deckel 45,5 — Woche 0 bleibt darunter, ohne Toleranz.
        val five = generatePlan(g, a, now, currentCtl = 5.0, texts = CoreTextsDe)
        assertTrue(load(five.weeks[0]) <= 1.30 * 7 * 5.0, "Woche 0: ${load(five.weeks[0])}")
        // CTL 1: Deckel 9,1 — selbst 30 min Grundlage liegen darueber. Dann steht
        // genau eine Einheit da: die kuerzeste Strecke, die 30 Minuten dauert.
        val one = generatePlan(g, a, now, currentCtl = 1.0, texts = CoreTextsDe)
        val week = one.weeks[0]
        assertTrue(load(week) > 1.30 * 7)
        val only = week.sessions.single()
        assertEquals(ceil(MIN_SESSION_MIN / 60.0 * fallbackGravelSpeedKmh * 0.95).toInt(), only.targetKm)
        assertEquals(SessionIntensity.GRUNDLAGE, only.intensity)
        assertTrue(only.durationMin!! >= MIN_SESSION_MIN)
        assertTrue((only.targetKm - 1) / (fallbackGravelSpeedKmh * 0.95) * 60 < MIN_SESSION_MIN)
        // Danach waechst die Last wieder um hoechstens 30 % je Aufbauwoche.
        var reference = maxOf(7.0, load(week))
        for (w in one.weeks.drop(1).filter { it.kind == WeekKind.AUFBAU }) {
            assertTrue(load(w) <= 1.30 * reference + 1e-9, "Woche ${w.index}: ${load(w)} / $reference")
            reference = maxOf(reference, load(w))
        }
    }

    // -----------------------------------------------------------------------
    // W4 – keine Qualitaet am Tag nach der langen Tour
    // -----------------------------------------------------------------------

    @Test
    fun `W4 - Tage und Qualitaetstage werden gemeinsam gewaehlt`() {
        val plan = generatePlan(
            goal(12, 150.0), assessment(FitnessLevel.AMBITIONIERT, 150.0), now,
            preferences = PlanPreferences(trainingDays = listOf(0, 1, 2, 4, 5, 6), longRideDay = 5), texts = CoreTextsDe,
        )
        for (w in plan.weeks.dropLast(1)) {
            assertTrue(w.sessions.none { it.day == "So" && it.intensity == SessionIntensity.HART }, "Woche ${w.index}: ${w.sessions.map { it.day to it.title }}")
        }
        // Die Qualitaet faellt dafuer nicht weg: weiterhin zwei harte Einheiten in Aufbauwochen.
        assertTrue(plan.weeks.filter { it.kind == WeekKind.AUFBAU }.all { w -> w.sessions.count { it.intensity == SessionIntensity.HART } == 2 })
        assertEquals(listOf(0, 1, 2, 4, 5, 6).let { chooseTrainingDays(it, 5, 5, 2) }, listOf(0, 2, 4, 5, 6))

        // Nicht vermeidbar (nur Sa/So): keine harte Einheit statt einer am Folgetag.
        val weekend = generatePlan(
            goal(12, 150.0), assessment(FitnessLevel.AMBITIONIERT, 150.0), now,
            preferences = PlanPreferences(trainingDays = listOf(5, 6), longRideDay = 5), texts = CoreTextsDe,
        )
        assertTrue(weekend.weeks.dropLast(1).all { w -> w.sessions.none { it.intensity == SessionIntensity.HART } })
    }

    // -----------------------------------------------------------------------
    // W5 – keine 5-km-Fahrten durch die Untergrenze der langen Tour
    // -----------------------------------------------------------------------

    @Test
    fun `W5 - die Untergrenze der langen Tour nimmt anteilig und streicht statt zu stutzen`() {
        val plan = generatePlan(
            goal(16, 200.0), assessment(FitnessLevel.AMBITIONIERT, 150.0), now, 60.0,
            PlanPreferences(trainingDays = listOf(0, 1, 2, 3, 5, 6)), texts = CoreTextsDe,
        )
        for (s in plan.weeks.flatMap { it.sessions }.filterNot { it.isEvent }) {
            assertTrue(s.durationMin!! >= MIN_SESSION_MIN, "${s.title} ${s.targetKm} km / ${s.durationMin} min")
        }
    }

    // -----------------------------------------------------------------------
    // W6 – Dauern mit Obergrenzen, Texte passend zur Dauer
    // -----------------------------------------------------------------------

    @Test
    fun `W6 - keine absurden Dauern, Titel passen zur Dauer`() {
        val big = generatePlan(goal(20, 400.0), assessment(FitnessLevel.AMBITIONIERT, 250.0), now, texts = CoreTextsDe)
        for (w in big.weeks) {
            for (s in w.sessions.filterNot { it.isEvent }) {
                val d = s.durationMin!!
                when (s.textKey) {
                    SessionTextKey.TAPER_SHORT_EASY -> assertTrue(d <= TAPER_SHORT_MAX_MIN, "${s.title} $d")
                    SessionTextKey.RECOVERY_SPIN, SessionTextKey.ENDURANCE_COMPENSATION -> assertTrue(d <= RECOVERY_RIDE_MAX_MIN, "${s.title} $d")
                    SessionTextKey.LONG_RIDE -> {
                        assertTrue(d >= LONG_RIDE_MIN_MIN)
                        if (w.kind == WeekKind.TAPER) assertTrue(d <= TAPER_LONG_RIDE_MAX_MIN, "Taper ${s.title} $d")
                    }
                    SessionTextKey.ENDURANCE_STEADY -> assertTrue(d <= ENDURANCE_RIDE_MAX_MIN, "${s.title} $d")
                    else -> if (s.intensity == SessionIntensity.LOCKER) assertTrue(d <= EASY_RIDE_MAX_MIN, "${s.title} $d")
                }
            }
        }
        // Ein Trainingstag, 300 km/Wo, Ziel 200 km: keine Fahrt laenger als 80 % des Events.
        val oneDay = generatePlan(
            goal(12, 200.0), assessment(FitnessLevel.AMBITIONIERT, 300.0), now,
            preferences = PlanPreferences(trainingDays = listOf(5)), texts = CoreTextsDe,
        )
        val longest = oneDay.weeks.flatMap { it.sessions }.filterNot { it.isEvent }.maxOf { it.targetKm }
        assertTrue(longest <= 160, "$longest km")
        // Eine kurze Fahrt am Tag der langen Tour heisst nicht „Lange Tour".
        val beginner = generatePlan(goal(12, 100.0), assessment(FitnessLevel.EINSTEIGER, 20.0), now, texts = CoreTextsDe)
        val first = beginner.weeks[0].sessions.first { it.day == "Sa" }
        assertTrue(first.durationMin!! < LONG_RIDE_MIN_MIN)
        assertEquals(SessionTextKey.BASE_EASY_RIDE, first.textKey)
    }

    // -----------------------------------------------------------------------
    // W7 – projectedEventCtl fuer Altplaene
    // -----------------------------------------------------------------------

    /** Ein Plan im Format des alten Generators: Intervalle alten Formats, lockerer Taper, Last-Ziele. */
    private fun legacyOf(plan: TrainingPlan): TrainingPlan = plan.copy(
        planSpeedKmh = null,
        preferences = null,
        weeks = plan.weeks.map { w ->
            w.copy(
                sessions = w.sessions.map { s ->
                    when {
                        s.textKey == SessionTextKey.WORKOUT -> s.copy(
                            title = "Intervalle",
                            textKey = SessionTextKey.INTERVALS,
                            textArgs = listOf(20, 4, 8, 4, 10, 74, 0),
                            targetLoad = 120.0,
                        )
                        w.kind == WeekKind.TAPER -> s.copy(intensity = SessionIntensity.LOCKER, textKey = SessionTextKey.TAPER_EASY_SURGES, textArgs = emptyList())
                        s.textKey == SessionTextKey.RECOVERY_LONG -> s.copy(textKey = SessionTextKey.RECOVERY_CALM_LOOP, intensity = SessionIntensity.LOCKER)
                        else -> s
                    }
                },
            )
        },
    )

    @Test
    fun `W7 - Altplaene behalten die alte CTL-Vorschau`() {
        val fresh = generatePlan(goal(12, 150.0), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, 35.0, texts = CoreTextsDe)
        val legacy = legacyOf(fresh)
        assertTrue(isLegacyGeneratorPlan(legacy))
        val builds = legacy.weeks.count { it.kind == WeekKind.AUFBAU }
        assertEquals(40.0 + builds * defaultTargetRampPerWeek, projectedEventCtl(legacy, 40.0, now))
        // Auch ein Einsteiger-Altplan ohne Intervalle: lockerer Taper, kein Tempo, keine Praeferenzen.
        val beginner = legacyOf(generatePlan(goal(12, 100.0), assessment(FitnessLevel.EINSTEIGER, 20.0), now, texts = CoreTextsDe))
            .let { p -> p.copy(weeks = p.weeks.map { w -> w.copy(sessions = w.sessions.filter { it.textKey != SessionTextKey.WORKOUT && it.textKey != SessionTextKey.INTERVALS }) }) }
        assertTrue(isLegacyGeneratorPlan(beginner))
        // Neue Plaene (auch ohne Tempo und Praeferenzen) rechnen ueber die Wochenlasten.
        for (p in listOf(fresh, generatePlan(goal(3, 30.0), assessment(FitnessLevel.EINSTEIGER, 12.0), now, texts = CoreTextsDe),
            generatePlan(fitnessGoal("B", 6, now), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, texts = CoreTextsDe))) {
            assertFalse(isLegacyGeneratorPlan(p), "${p.goal}")
        }
    }

    // -----------------------------------------------------------------------
    // Kleinigkeiten
    // -----------------------------------------------------------------------

    @Test
    fun `km-Rampe gilt fuer die tatsaechliche Wochensumme, auch mit viel Qualitaet`() {
        val plan = generatePlan(goal(10, 80.0), assessment(FitnessLevel.AMBITIONIERT, 40.0), now, texts = CoreTextsDe)
        var last = 40.0
        for (w in plan.weeks.filter { it.kind == WeekKind.AUFBAU }) {
            assertTrue(w.targetKm <= rampCapKm(last), "Woche ${w.index}: ${w.targetKm} > ${rampCapKm(last)}")
            last = w.targetKm.toDouble()
        }
        for (w in plan.weeks.filter { it.kind == WeekKind.ERHOLUNG }) {
            val prev = plan.weeks.subList(0, w.index).last { it.kind == WeekKind.AUFBAU }
            assertTrue(w.targetKm <= round5(prev.targetKm * 0.55), "Erholung ${w.targetKm} / ${prev.targetKm}")
        }
    }

    @Test
    fun `Zielwoche - kein Opener nach der Sonntagstour, Montagsevent ohne lange Tour am Vortag`() {
        // Donnerstagsevent, lange Tour sonntags: der Opener laege am Montag direkt danach.
        val thursday = generatePlan(
            goal(10, 120.0, day = 3), assessment(FitnessLevel.FORTGESCHRITTEN, 90.0), now,
            preferences = PlanPreferences(longRideDay = 6), texts = CoreTextsDe,
        )
        assertTrue(thursday.weeks.last().sessions.none { it.day == "Mo" && it.intensity == SessionIntensity.HART })
        // Montagsevent: Sonntag davor Aktivierung, keine lange Tour, keine Qualitaet am Wochenende.
        val monday = generatePlan(goal(10, 120.0, day = 0), assessment(FitnessLevel.FORTGESCHRITTEN, 90.0), now, texts = CoreTextsDe)
        val taper = monday.weeks[monday.weeks.size - 2].sessions
        assertEquals(SessionTextKey.ACTIVATION, taper.single { it.day == "So" }.textKey)
        assertTrue(taper.none { (it.day == "Sa" || it.day == "So") && (isLongRideSession(it) || it.intensity == SessionIntensity.HART) })
        assertEquals(1, monday.weeks.last().sessions.size)
    }

    @Test
    fun `Fitnessblock - Vergleichsrunde am Zieltag, nichts danach, Test wo moeglich`() {
        val weekend = generatePlan(
            fitnessGoal("Block", 8, now, testDay = 5), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now,
            preferences = PlanPreferences(trainingDays = listOf(5, 6), longRideDay = 6), texts = CoreTextsDe,
        )
        val test = weekend.weeks.last().sessions
        assertTrue(test.none { dayOf(it) > 5 }, "${test.map { it.day }}")
        assertEquals("Sa", test.single { it.textKey == SessionTextKey.BENCHMARK_RIDE }.day)
        // Vor dem Samstag kein Trainingstag: kein Test — und die Abfrage sagt das.
        assertEquals(FitnessTestWeekContents(hasFtpTest = false, hasBenchmarkRide = true), fitnessTestWeekContents(weekend))
        val normal = generatePlan(fitnessGoal("Block", 8, now), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, texts = CoreTextsDe)
        assertEquals(FitnessTestWeekContents(hasFtpTest = true, hasBenchmarkRide = true), fitnessTestWeekContents(normal))
        val days = normal.weeks.last().sessions.map { dayOf(it) }
        assertTrue(days.all { it <= 5 })
        assertNull(fitnessTestWeekContents(generatePlan(goal(6, 80.0), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, texts = CoreTextsDe)))
    }

    @Test
    fun `20-min-Test - kein Zielbereich als Deckel fuer die Belastung`() {
        val plan = generatePlan(fitnessGoal("Block", 8, now), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, texts = CoreTextsDe)
        val test = plan.weeks.last().sessions.single { it.textKey == SessionTextKey.FTP_TEST }
        val zones = assertNotNull(sessionTargetZones(test))
        assertNull(zones.work)
        assertEquals(TargetZone.ENDURANCE, zones.main)
        assertEquals(20, zones.workMin)
        assertNull(sessionPersonalTargets(test, TrainingProfile(40)))
    }

    @Test
    fun `adaptPlan - Altplaene behalten Progression und Erholungsreiz`() {
        val legacy = legacyOf(generatePlan(goal(12, 150.0), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, texts = CoreTextsDe))
        val state = planBuildStateBefore(legacy.weeks.take(3), 50.0)
        assertEquals(3, state.workoutUses[WorkoutKind.THRESHOLD])
        assertEquals(WorkoutKind.THRESHOLD, state.lastQuality.single().kind)
        assertNull(state.achievedPeakKm)
        val rides = legacy.weeks[2].sessions.mapIndexed { i, s ->
            Ride("r$i", "R", legacy.weeks[2].start + dayOf(s) * 86_400_000L + 9 * 3_600_000L, RideStats(s.targetKm * 0.6, 0.0, 0.0))
        }
        val adapted = adaptPlan(legacy, rides, now = legacy.weeks[3].start + 3_600_000L, texts = CoreTextsDe)
        assertTrue(adapted.adapted, adapted.toString())
        // Woche 3 ist Erholung: der kurze Reiz bleibt, auch im Altplan.
        assertEquals(WeekKind.ERHOLUNG, adapted.plan.weeks[3].kind)
        assertTrue(adapted.plan.weeks[3].sessions.any { it.textKey == SessionTextKey.WORKOUT }, adapted.plan.weeks[3].sessions.map { it.title }.toString())
    }

    @Test
    fun `adaptPlan - die woertlich bleibende Zielwoche zaehlt in die harten Tage`() {
        for (eventDay in 0..6) {
            val plan = generatePlan(goal(10, 120.0, eventDay), assessment(FitnessLevel.AMBITIONIERT, 120.0), now, texts = CoreTextsDe)
            val rides = plan.weeks[0].sessions.mapIndexed { i, s ->
                Ride("r$i", "R", plan.weeks[0].start + dayOf(s) * 86_400_000L + 9 * 3_600_000L, RideStats(s.targetKm * 0.4, 0.0, 0.0))
            }
            val adapted = adaptPlan(plan, rides, now = plan.weeks[1].start + 3_600_000L, texts = CoreTextsDe).plan
            assertEquals(plan.weeks.last(), adapted.weeks.last())
            val line = adapted.weeks.flatMap { w -> w.sessions.map { w.index * 7 + dayOf(it) to it } }
            val hard = line.filter { it.second.intensity == SessionIntensity.HART }.map { it.first }.toSet()
            for ((d, s) in line.filter { it.second.intensity == SessionIntensity.HART && !it.second.isEvent }) {
                assertFalse((d + 1) in hard || (d - 1) in hard, "Eventtag $eventDay: harte Tage nebeneinander an Tag $d (${s.title})")
            }
        }
    }
}
