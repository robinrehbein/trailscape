package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import de.trailscape.core.i18n.SessionTextKey
import de.trailscape.core.indoor.ergWorkoutFor
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Die fachlichen Invarianten des umgebauten Plangenerators — je eine Gruppe
 * pro Befund der Pruefung (Last ↔ Einheiten, Taper, Erholung, Intensitaet,
 * Persoenliches, Zieltypen) plus JSON-Kompatibilitaet.
 */
class PlanInvariantsTest {
    private val now: Long = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))
    private val firstMonday: LocalDate = LocalDate.of(2026, 1, 5)

    /** Ziel am Samstag der Woche [weeks] − 1 (Plan mit [weeks] Wochen). */
    private fun goal(weeks: Int, km: Double, ascentM: Double? = null, targetMin: Int? = null) = Goal(
        name = "Ziel",
        distanceKm = km,
        ascentM = ascentM,
        date = dartEpochMs(firstMonday.plusDays((weeks - 1) * 7L + 5).atTime(9, 0)),
        targetDurationMin = targetMin,
    )

    private fun assessment(level: FitnessLevel, weeklyKm: Double, rides: Int = 20) =
        FitnessAssessment(level, weeklyKm, weeklyKm * 8, 3.0, weeklyKm / 2, rides)

    /** Die Beispielplaene der Pruefung. */
    private val advanced = generatePlan(goal(12, 150.0), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, 35.0, texts = CoreTextsDe)
    private val ambitious = generatePlan(goal(16, 200.0), assessment(FitnessLevel.AMBITIONIERT, 150.0), now, 60.0, texts = CoreTextsDe)
    private val ambitiousShort = generatePlan(goal(10, 80.0), assessment(FitnessLevel.AMBITIONIERT, 150.0), now, 60.0, texts = CoreTextsDe)
    private val beginner = generatePlan(goal(12, 100.0), assessment(FitnessLevel.EINSTEIGER, 20.0), now, texts = CoreTextsDe)
    private val hillyWithPrefs = generatePlan(
        goal(12, 150.0, ascentM = 1500.0),
        assessment(FitnessLevel.FORTGESCHRITTEN, 80.0),
        now,
        35.0,
        PlanPreferences(trainingDays = listOf(0, 2, 5, 6), longRideDay = 6, weeklyHours = 5.0),
        speedKmh = 24.0,
        texts = CoreTextsDe,
    )
    private val allPlans = listOf(advanced, ambitious, ambitiousShort, beginner, hillyWithPrefs)

    private fun weekLoad(week: TrainingWeek) = week.sessions.filterNot { it.isEvent }.sumOf { it.targetLoad ?: 0.0 }

    // -----------------------------------------------------------------------
    // 1. Eine Wahrheit: Last passt zu Dauer und Struktur
    // -----------------------------------------------------------------------

    /** IF aus der Struktur, unabhaengig vom Generator nachgerechnet. */
    private fun expectedIf(session: TrainingSession, hilly: Boolean): Double {
        val total = session.durationMin!!.toDouble()
        val a = session.textArgs
        fun mean(parts: List<Pair<Double, Double>>) = sqrt(parts.sumOf { it.first * it.second * it.second } / parts.sumOf { it.first })
        val ga = 0.70 + if (hilly) 0.05 else 0.0
        return when (session.textKey) {
            SessionTextKey.WORKOUT -> {
                val zoneIf = mapOf(0 to 0.82, 1 to 0.91, 2 to 1.00, 3 to 1.12).getValue(a[0])
                mean(listOf(a[1].toDouble() to 0.60, a[2] * a[3].toDouble() to zoneIf, (a[2] - 1) * a[4].toDouble() to 0.55, a[5].toDouble() to 0.55))
            }
            SessionTextKey.LONG_RIDE -> {
                val pace = (a.getOrElse(1) { 0 } * a.getOrElse(2) { 0 }).toDouble()
                mean(listOf(total - pace to ga, pace to 0.80))
            }
            SessionTextKey.FTP_TEST -> mean(listOf(a[0].toDouble() to 0.60, a[1].toDouble() to 1.05, a[2].toDouble() to 0.55))
            SessionTextKey.BENCHMARK_RIDE -> mean(listOf(15.0 to 0.60, total - 15 to 0.85))
            else -> when (session.intensity) {
                SessionIntensity.LOCKER -> 0.60
                SessionIntensity.GRUNDLAGE -> ga
                SessionIntensity.HART -> 1.0
            }
        }
    }

    @Test
    fun `implizierter IF jeder Einheit passt zu ihrer Struktur`() {
        for (plan in allPlans + fitnessPlan()) {
            val hilly = (plan.goal.ascentM ?: 0.0) >= 1000
            for (session in plan.weeks.flatMap { it.sessions }.filterNot { it.isEvent }) {
                val implied = assertNotNull(impliedSessionIf(session), "${session.title} ohne Last/Dauer")
                val expected = expectedIf(session, hilly)
                assertTrue(
                    abs(implied - expected) <= 0.05,
                    "${session.title} (${session.textKey}): IF $implied statt ≈ $expected",
                )
                // Keine Grundlagen- oder Regenerationsfahrt ist in Wahrheit eine Schwelleneinheit.
                if (session.intensity != SessionIntensity.HART) {
                    assertTrue(implied <= 0.80, "${session.title}: IF $implied")
                }
            }
        }
    }

    @Test
    fun `Aufbauwochen bleiben unter dem 1,3-fachen der Referenz`() {
        for ((plan, ctl) in listOf(advanced to 35.0, ambitious to 60.0, ambitiousShort to 60.0, hillyWithPrefs to 35.0)) {
            var reference = 7 * ctl
            for (week in plan.weeks.filter { it.kind == WeekKind.AUFBAU }) {
                val load = weekLoad(week)
                assertTrue(load <= 1.30 * reference + 1, "Woche ${week.index}: $load > 1,3 × $reference")
                reference = maxOf(reference, load)
            }
            // Woche 1 liegt nicht mehr 74 % ueber der gewohnten Wochenlast.
            assertTrue(weekLoad(plan.weeks.first()) <= 1.30 * 7 * ctl)
        }
    }

    @Test
    fun `die Dauer folgt dem persoenlichen Tempo`() {
        val slow = generatePlan(goal(12, 150.0), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, speedKmh = 15.0, texts = CoreTextsDe)
        val fast = generatePlan(goal(12, 150.0), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, speedKmh = 27.0, texts = CoreTextsDe)
        val longSlow = slow.weeks.first().sessions.first { isLongRideSession(it) }
        val longFast = fast.weeks.first().sessions.first { isLongRideSession(it) }
        assertTrue(longSlow.durationMin!! > longFast.durationMin!!)
        // Dauer = km / (Tempo × Intensitaetsfaktor).
        assertEquals(dartRound(longFast.targetKm / (27.0 * 0.95) * 60).toInt(), longFast.durationMin)
        assertEquals(27.0, fast.planSpeedKmh)
        // Ein unplausibles Tempo wird ignoriert (Vorgabe 18 km/h).
        val absurd = generatePlan(goal(12, 150.0), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, speedKmh = 90.0, texts = CoreTextsDe)
        assertNull(absurd.planSpeedKmh)
    }

    @Test
    fun `mit Historie startet der Plan beim tatsaechlichen Umfang`() {
        // 20 km/Woche: Woche 1 springt nicht mehr auf 40 km.
        assertTrue(beginner.weeks.first().targetKm in 18..22, "Woche 1: ${beginner.weeks.first().targetKm}")
        // Der Umfang waechst trotzdem (Mindestschritt 5 km bei kleinem Umfang).
        val builds = beginner.weeks.filter { it.kind == WeekKind.AUFBAU }.map { it.targetKm }
        assertTrue(builds.last() >= builds.first() + 20, "Aufbau $builds")
    }

    @Test
    fun `liegt das Ziel unter dem Umfang, waechst die Last ueber Qualitaet`() {
        val builds = ambitiousShort.weeks.filter { it.kind == WeekKind.AUFBAU }
        val km = builds.map { it.targetKm }
        assertTrue(km.max() - km.min() <= 10, "km sollen ungefaehr flach bleiben: $km")
        assertTrue(weekLoad(builds.last()) >= weekLoad(builds.first()) * 1.08, "Last ${builds.map { weekLoad(it) }}")
        // Zieltempo-Abschnitte kommen schon in der Aufbauphase.
        val phases = planWeekPhases(ambitiousShort)
        assertTrue(
            builds.any { w ->
                phases[w.index] == TrainingPhase.AUFBAU &&
                    w.sessions.any { it.textKey == SessionTextKey.LONG_RIDE && it.textArgs.getOrElse(1) { 0 } > 0 }
            },
        )
    }

    // -----------------------------------------------------------------------
    // 2. Taper
    // -----------------------------------------------------------------------

    @Test
    fun `Taper - zwei Wochen, progressiv, Haeufigkeit und Intensitaet bleiben`() {
        for (plan in listOf(advanced, ambitious, ambitiousShort, beginner)) {
            val tapers = plan.weeks.filter { it.kind == WeekKind.TAPER }
            assertEquals(2, tapers.size)
            val builds = plan.weeks.filter { it.kind == WeekKind.AUFBAU }
            val peak = builds.maxOf { it.targetKm }
            val lastBuild = plan.weeks[tapers.first().index - 1]
            val longestBuildLong = builds.flatMap { it.sessions }.filter { isLongRideSession(it) }.maxOf { it.targetKm }
            assertTrue(tapers[0].targetKm in (peak * 0.55).toInt()..(peak * 0.75).toInt(), "Taper 1 ${tapers[0].targetKm}/$peak")
            assertTrue(tapers[1].targetKm < tapers[0].targetKm)
            assertTrue(tapers[1].targetKm <= peak * 0.55, "Taper 2 ${tapers[1].targetKm}/$peak")
            for (taper in tapers) {
                // Mujika & Padilla 2003: Haeufigkeit hoechstens um ~20 % senken → Zahl halten.
                assertEquals(lastBuild.sessions.size, taper.sessions.size, "Haeufigkeit in Woche ${taper.index}")
                assertEquals(
                    lastBuild.sessions.count { it.intensity == SessionIntensity.HART },
                    taper.sessions.count { it.intensity == SessionIntensity.HART },
                    "Intensitaet in Woche ${taper.index}",
                )
                // Keine Taperfahrt laenger als 60 % der laengsten Aufbaufahrt.
                assertTrue(
                    taper.sessions.maxOf { it.targetKm } <= longestBuildLong * 0.6 + 1,
                    "Woche ${taper.index}: ${taper.sessions.maxOf { it.targetKm }} vs $longestBuildLong",
                )
            }
            // Die harten Reize im Taper behalten ihre Art, werden aber kuerzer.
            val buildWork = lastBuild.sessions.mapNotNull { parseWorkoutArgs(it.textArgs).takeIf { _ -> it.textKey == SessionTextKey.WORKOUT } }
            val taperWork = tapers.last().sessions.mapNotNull { parseWorkoutArgs(it.textArgs).takeIf { _ -> it.textKey == SessionTextKey.WORKOUT } }
            assertEquals(buildWork.map { it.kind }.toSet(), taperWork.map { it.kind }.toSet())
            assertTrue(taperWork.sumOf { it.workTotalMin } < buildWork.sumOf { it.workTotalMin })
        }
    }

    @Test
    fun `Zielwoche - kurze Vorbelastung und Aktivierung passend zum Eventtag`() {
        val week = ambitious.weeks.last()
        val event = week.sessions.single { it.isEvent }
        assertEquals("Sa", event.day)
        val activation = week.sessions.single { it.textKey == SessionTextKey.ACTIVATION }
        assertEquals("Fr", activation.day)
        val opener = week.sessions.single { it.textKey == SessionTextKey.WORKOUT }
        assertTrue(planWeekdayIndex(opener.day) in 0..2)
        assertEquals(2, parseWorkoutArgs(opener.textArgs)!!.reps)
        // Ein Montagsevent bekommt nichts davor; ein Mittwochsevent die Aktivierung am Dienstag.
        val monday = generatePlan(goal(6, 80.0).copy(date = dartEpochMs(firstMonday.plusDays(35).atTime(9, 0))), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, texts = CoreTextsDe)
        assertEquals(1, monday.weeks.last().sessions.size)
        val wednesday = generatePlan(goal(6, 80.0).copy(date = dartEpochMs(firstMonday.plusDays(37).atTime(9, 0))), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, texts = CoreTextsDe)
        assertEquals("Di", wednesday.weeks.last().sessions.single { it.textKey == SessionTextKey.ACTIVATION }.day)
    }

    // -----------------------------------------------------------------------
    // 3. Erholungswochen
    // -----------------------------------------------------------------------

    @Test
    fun `Erholung - minus 40 bis 50 Prozent, Haeufigkeit gehalten, halber Reiz`() {
        for (plan in listOf(advanced, ambitious)) {
            for (week in plan.weeks.filter { it.kind == WeekKind.ERHOLUNG }) {
                val previous = plan.weeks.subList(0, week.index).last { it.kind == WeekKind.AUFBAU }
                val share = week.targetKm.toDouble() / previous.targetKm
                assertTrue(share in 0.45..0.60, "Woche ${week.index}: $share")
                assertEquals(maxOf(2, previous.sessions.size - 1), week.sessions.size, "Woche ${week.index}")
                val reiz = week.sessions.filter { it.textKey == SessionTextKey.WORKOUT }.map { parseWorkoutArgs(it.textArgs)!! }
                assertEquals(1, reiz.size)
                val buildMax = previous.sessions.filter { it.textKey == SessionTextKey.WORKOUT }
                    .maxOf { parseWorkoutArgs(it.textArgs)!!.workTotalMin }
                assertTrue(reiz.single().workTotalMin <= buildMax * 0.75 + 1)
                val longBuild = previous.sessions.first { isLongRideSession(it) }.targetKm
                assertTrue(week.sessions.maxOf { it.targetKm } <= longBuild * 0.75)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 4. Intensitaet: Progression, Variation, harte Tage
    // -----------------------------------------------------------------------

    @Test
    fun `Belastungszeit steigt je Qualitaetsart ueber die Aufbauwochen`() {
        for (plan in listOf(advanced, ambitious, beginner)) {
            val byKind = mutableMapOf<WorkoutKind, MutableList<Int>>()
            for (week in plan.weeks.filter { it.kind == WeekKind.AUFBAU }) {
                for (s in week.sessions.filter { it.textKey == SessionTextKey.WORKOUT }) {
                    val shape = parseWorkoutArgs(s.textArgs)!!
                    byKind.getOrPut(shape.kind) { mutableListOf() } += shape.workTotalMin
                }
            }
            for ((kind, minutes) in byKind) {
                assertEquals(minutes.sorted(), minutes, "$kind nicht monoton: $minutes")
            }
            assertTrue(byKind.values.any { it.last() > it.first() }, "keine Progression: $byKind")
        }
        // Ambitioniert startet nicht mit der hoechsten Stufe.
        val first = ambitious.weeks.first().sessions.first { it.textKey == SessionTextKey.WORKOUT }
        val firstShape = parseWorkoutArgs(first.textArgs)!!
        assertTrue(firstShape.workTotalMin < workoutAtStep(firstShape.kind, lastWorkoutStep(firstShape.kind)).workTotalMin)
    }

    @Test
    fun `Variation ueber die Phasen und bis zu zwei Qualitaetseinheiten bei Ambitionierten`() {
        val kinds = ambitious.weeks.flatMap { it.sessions }
            .filter { it.textKey == SessionTextKey.WORKOUT }
            .map { parseWorkoutArgs(it.textArgs)!!.kind }
            .toSet()
        assertTrue(kinds.containsAll(listOf(WorkoutKind.SWEET_SPOT, WorkoutKind.THRESHOLD, WorkoutKind.VO2MAX)), "$kinds")
        val phases = planWeekPhases(ambitious)
        assertTrue(TrainingPhase.GRUNDLAGE in phases && TrainingPhase.AUFBAU in phases && TrainingPhase.SPEZIFISCH in phases)
        for (week in ambitious.weeks.filter { it.kind == WeekKind.AUFBAU }) {
            assertTrue(week.sessions.count { it.intensity == SessionIntensity.HART } in 1..2)
        }
        assertTrue(ambitious.weeks.any { w -> w.kind == WeekKind.AUFBAU && w.sessions.count { it.intensity == SessionIntensity.HART } == 2 })
        // Grob 80/20 nach Einheiten: hoechstens 40 % der Einheiten einer Aufbauwoche sind hart.
        for (week in allPlans.flatMap { it.weeks }.filter { it.kind == WeekKind.AUFBAU }) {
            assertTrue(week.sessions.count { it.intensity == SessionIntensity.HART } <= maxOf(1, (week.sessions.size * 0.4).toInt()))
        }
        // Gegen Ende eventspezifisch: Zieltempo in der langen Tour, am Berg bei Hoehenmetern.
        assertTrue(ambitious.weeks.any { w -> w.sessions.any { it.textKey == SessionTextKey.LONG_RIDE && it.textArgs.getOrElse(1) { 0 } > 0 } })
        val hillWorkout = hillyWithPrefs.weeks.flatMap { it.sessions }.first { it.textKey == SessionTextKey.WORKOUT && it.textArgs[7] == 1 }
        assertEquals(AscentPreference.BERGIG, ascentPreferenceForSession(hillWorkout))
    }

    @Test
    fun `Einsteiger bekommen nach den Grundlagenwochen einen sanften Reiz`() {
        val builds = beginner.weeks.filter { it.kind == WeekKind.AUFBAU }
        assertTrue(builds.take(3).all { w -> w.sessions.none { it.intensity == SessionIntensity.HART } })
        val later = builds.drop(3).flatMap { it.sessions }.filter { it.intensity == SessionIntensity.HART }
        assertTrue(later.isNotEmpty())
        assertTrue(later.all { parseWorkoutArgs(it.textArgs)!!.kind == WorkoutKind.TEMPO })
    }

    /** Alle Plantage mit ihren Einheiten in zeitlicher Reihenfolge. */
    private fun timeline(plan: TrainingPlan) = plan.weeks.flatMap { w ->
        w.sessions.map { w.index * 7 + planWeekdayIndex(it.day) to it }
    }

    @Test
    fun `harte Tage - hoechstens zwei je 7 Tage, nie nebeneinander, nie vor der langen Tour`() {
        val extra = listOf(
            generatePlan(goal(20, 160.0, 2000.0), assessment(FitnessLevel.AMBITIONIERT, 140.0), now, texts = CoreTextsDe,
                preferences = PlanPreferences(trainingDays = listOf(0, 1, 2, 4, 6), longRideDay = 0)),
            generatePlan(goal(14, 120.0), assessment(FitnessLevel.AMBITIONIERT, 200.0), now, texts = CoreTextsDe,
                preferences = PlanPreferences(trainingDays = listOf(5, 6))),
            fitnessPlan(),
        )
        for (plan in allPlans + extra) {
            val line = timeline(plan)
            val hard = line.filter { it.second.intensity == SessionIntensity.HART }.map { it.first }.sorted()
            val long = line.filter { isLongRideSession(it.second) }.map { it.first }.toSet()
            for (d in hard) {
                assertFalse((d + 1) in hard, "harte Tage nebeneinander an Tag $d")
                assertFalse((d + 1) in long, "harter Tag $d vor der langen Tour")
            }
            for (start in (hard.minOrNull() ?: 0) - 6..(hard.maxOrNull() ?: 0)) {
                assertTrue(hard.count { it in start..start + 6 } <= 2, "mehr als 2 harte Tage ab Tag $start")
            }
        }
    }

    // -----------------------------------------------------------------------
    // 4b. Persoenliche Zielbereiche
    // -----------------------------------------------------------------------

    @Test
    fun `persoenliche Puls- und Wattbereiche mit Verlaesslichkeit`() {
        val profile = TrainingProfile(ageYears = 40, weightKg = 75.0, lthrOverride = 165.0, eftpOverrideW = 240.0)
        val threshold = personalTargetRange(TargetZone.THRESHOLD, profile)
        assertEquals(dartRound(165 * 0.94).toInt(), threshold.hrLowBpm)
        assertEquals(dartRound(165 * 1.02).toInt(), threshold.hrHighBpm)
        assertEquals(228, threshold.powerLowW)
        assertEquals(252, threshold.powerHighW)
        assertEquals(Confidence.HIGH, threshold.hrConfidence)
        assertEquals(Confidence.HIGH, threshold.powerConfidence)

        // VO2max bezieht sich auf HFmax (Helgerud); ohne eingetragene HFmax nur LOW.
        val vo2 = personalTargetRange(TargetZone.VO2MAX, profile)
        assertEquals(dartRound(profile.hrMax * 0.90).toInt(), vo2.hrLowBpm)
        assertEquals(Confidence.LOW, vo2.hrConfidence)

        // Geschaetztes Profil → geschaetzte Werte, gemessene eFTP → deren Verlaesslichkeit.
        val guess = TrainingProfile(ageYears = 40)
        val endurance = personalTargetRange(
            TargetZone.ENDURANCE,
            guess,
            eftp = EftpEstimate(260.0, EftpSource.ZWANZIG_MINUTEN, Confidence.MEDIUM),
        )
        assertEquals(Confidence.LOW, endurance.hrConfidence)
        assertEquals(Confidence.MEDIUM, endurance.powerConfidence)
        assertEquals(dartRound(260 * 0.56).toInt(), endurance.powerLowW)

        // Aus der Planeinheit: Grundlage zwischen den Belastungen, Belastung im Bereich der Art.
        val workout = advanced.weeks[2].sessions.first { it.textKey == SessionTextKey.WORKOUT }
        val targets = assertNotNull(sessionPersonalTargets(workout, profile))
        assertEquals(TargetZone.ENDURANCE, targets.zones.main)
        assertEquals(parseWorkoutArgs(workout.textArgs)!!.kind.zone, targets.zones.work)
        assertNotNull(targets.work)
        assertNull(sessionTargetZones(advanced.weeks.last().sessions.single { it.isEvent }))
        val recovery = advanced.weeks.flatMap { it.sessions }.first { it.intensity == SessionIntensity.LOCKER }
        assertEquals(TargetZone.RECOVERY, sessionTargetZones(recovery)!!.main)
        assertTrue(CoreTextsDe.training.targetZone(TargetZone.ENDURANCE).contains("Z2"))
    }

    // -----------------------------------------------------------------------
    // 5. Praeferenzen
    // -----------------------------------------------------------------------

    @Test
    fun `Praeferenzen - verfuegbare Tage, Tag der langen Tour, Zeitbudget`() {
        val allowed = setOf("Mo", "Mi", "Sa", "So")
        for (week in hillyWithPrefs.weeks.filter { it.kind != WeekKind.ZIELWOCHE }) {
            assertTrue(week.sessions.all { it.day in allowed }, "Woche ${week.index}: ${week.sessions.map { it.day }}")
            assertTrue(week.sessions.size <= 4)
            val minutes = week.sessions.sumOf { it.durationMin ?: 0 }
            assertTrue(minutes <= 5 * 60, "Woche ${week.index}: $minutes min > 5 h")
            week.sessions.filter { isLongRideSession(it) }.forEach { assertEquals("So", it.day) }
        }
        // Zielwoche: vor dem Event nur verfuegbare Tage.
        assertTrue(hillyWithPrefs.weeks.last().sessions.filterNot { it.isEvent }.all { it.day in allowed })

        // Zahl der Einheiten = min(Raster, Tage).
        val twoDays = generatePlan(goal(12, 150.0), assessment(FitnessLevel.AMBITIONIERT, 150.0), now,
            preferences = PlanPreferences(trainingDays = listOf(2, 5)), texts = CoreTextsDe)
        assertTrue(twoDays.weeks.filter { it.kind == WeekKind.AUFBAU }.all { it.sessions.size == 2 })

        // Gespeichert und wieder gelesen; adaptPlan rechnet mit denselben Annahmen.
        val restored = TrainingPlan.fromJson(Json.parseToJsonElement(hillyWithPrefs.toJson().toString()).jsonObject)
        assertEquals(hillyWithPrefs, restored)
        val rides = listOf(Ride("r", "R", dartEpochMs(firstMonday.plusDays(6).atTime(9, 0)), RideStats(distanceKm = 20.0, ascentM = 0.0, descentM = 0.0)))
        val adapted = adaptPlan(restored, rides, now = dartEpochMs(firstMonday.plusDays(8).atTime(9, 0)), texts = CoreTextsDe)
        assertTrue(adapted.adapted)
        for (week in adapted.plan.weeks.drop(1).filter { it.kind != WeekKind.ZIELWOCHE }) {
            assertTrue(week.sessions.all { it.day in allowed })
            assertTrue(week.sessions.sumOf { it.durationMin ?: 0 } <= 5 * 60)
        }
    }

    @Test
    fun `das Zeitbudget als Engpass wird in der Machbarkeit benannt`() {
        val plan = generatePlan(
            goal(20, 150.0),
            assessment(FitnessLevel.FORTGESCHRITTEN, 80.0),
            now,
            preferences = PlanPreferences(weeklyHours = 4.0),
            texts = CoreTextsDe,
        )
        assertTrue(plan.weeks.all { w -> w.sessions.filterNot { it.isEvent }.sumOf { it.durationMin ?: 0 } <= 240 })
        val verdict = assessPlanFeasibility(plan, texts = CoreTextsDe)
        assertFalse(verdict.feasible)
        assertTrue(verdict.budgetLimited)
        val hours = assertNotNull(verdict.hoursNeededForGoal)
        assertTrue(hours > 4.0)
        assertTrue(assertNotNull(verdict.message).contains("Zeitbudget von 4 h"), verdict.message)
        // Ohne Budget kein Budget-Hinweis.
        assertFalse(assessPlanFeasibility(advanced, texts = CoreTextsDe).budgetLimited)
    }

    @Test
    fun `ohne neue Features schreibt der Plan keine neuen Schluessel`() {
        val plain = generatePlan(goal(12, 150.0), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, texts = CoreTextsDe)
        val json = plain.toJson()
        assertEquals(listOf("createdAt", "goal", "level", "weeks"), json.keys.toList())
        assertEquals(listOf("name", "distanceKm", "ascentM", "date"), json["goal"]!!.jsonObject.keys.toList())
        // Leere Praeferenzen werden nicht gespeichert.
        val empty = generatePlan(goal(12, 150.0), assessment(FitnessLevel.FORTGESCHRITTEN, 80.0), now, preferences = PlanPreferences(), texts = CoreTextsDe)
        assertNull(empty.preferences)
    }

    @Test
    fun `alte und fremde Plan-JSONs bleiben lesbar`() {
        val old = """{"createdAt":1,"goal":{"name":"X","distanceKm":100,"ascentM":null,"date":2},"level":"fortgeschritten",""" +
            """"weeks":[{"index":0,"start":0,"end":1,"kind":"aufbau","targetKm":50,"sessions":[""" +
            """{"day":"Di","title":"Intervalle","description":"…","targetKm":14}]}]}"""
        val plan = TrainingPlan.fromJson(Json.parseToJsonElement(old).jsonObject)
        assertNull(plan.preferences)
        assertNull(plan.planSpeedKmh)
        assertEquals(GoalType.EVENT, plan.goal.type)
        assertEquals(SessionTargetZones(TargetZone.ENDURANCE, TargetZone.THRESHOLD), sessionTargetZones(plan.weeks[0].sessions[0]))

        // Unbekannter Zieltyp, unbekannte Tage und Praeferenz-Unsinn: tolerant.
        val future = """{"createdAt":1,"goal":{"name":"X","distanceKm":100,"ascentM":null,"date":2,"type":"marathon"},""" +
            """"level":"fortgeschritten","weeks":[],"preferences":{"trainingDays":["Di","Xx",3],"longRideDay":"Foo","weeklyHours":-2},""" +
            """"planSpeedKmh":"schnell"}"""
        val read = TrainingPlan.fromJson(Json.parseToJsonElement(future).jsonObject)
        assertEquals(GoalType.EVENT, read.goal.type)
        assertEquals(listOf(1), read.preferences!!.trainingDays)
        assertNull(read.preferences!!.longRideDay)
        assertNull(read.preferences!!.weeklyHours)
        assertNull(read.planSpeedKmh)

        // Unbekannter Textschluessel (neuere Version): gespeicherter Text gilt.
        val session = TrainingSession.fromJson(
            Json.parseToJsonElement("""{"day":"Di","title":"Neu","description":"Text","targetKm":20,"textKey":"zukunft"}""").jsonObject,
        )
        assertNull(session.textKey)
        assertEquals("Neu", de.trailscape.core.i18n.sessionTitle(session, CoreTextsEn))
    }

    // -----------------------------------------------------------------------
    // 6. Zieltypen
    // -----------------------------------------------------------------------

    private fun fitnessPlan(weeks: Int = 8) = generatePlan(
        fitnessGoal("Schneller werden", weeks, now),
        assessment(FitnessLevel.FORTGESCHRITTEN, 80.0),
        now,
        35.0,
        texts = CoreTextsDe,
    )

    @Test
    fun `Fitnessblock - 3 zu 1, Progression und Testwoche statt Event`() {
        val plan = fitnessPlan(8)
        assertEquals(
            listOf(WeekKind.AUFBAU, WeekKind.AUFBAU, WeekKind.AUFBAU, WeekKind.ERHOLUNG, WeekKind.AUFBAU, WeekKind.AUFBAU, WeekKind.AUFBAU, WeekKind.ZIELWOCHE),
            plan.weeks.map { it.kind },
        )
        assertEquals(TrainingPhase.TEST, planWeekPhases(plan).last())
        val all = plan.weeks.flatMap { it.sessions }
        assertTrue(all.none { it.isEvent })
        val test = plan.weeks.last().sessions
        assertTrue(test.any { it.textKey == SessionTextKey.FTP_TEST })
        assertTrue(test.any { it.textKey == SessionTextKey.BENCHMARK_RIDE })
        assertTrue(test.all { canGenerateRouteFor(it) })
        assertTrue(assessPlanFeasibility(plan, texts = CoreTextsDe).feasible)
        // Kein Zieltempo ohne Event.
        assertTrue(all.none { it.textKey == SessionTextKey.LONG_RIDE && it.textArgs.size > 1 })
        // JSON: der Typ steht nur beim Fitnessziel.
        assertEquals("fitness", plan.goal.toJson()["type"].toString().trim('"'))
        assertEquals(plan, TrainingPlan.fromJson(Json.parseToJsonElement(plan.toJson().toString()).jsonObject))
        // Laufzeit 4–16 Wochen.
        assertFailsWith<IllegalArgumentException> { fitnessPlan(3) }
        val tooLong = assertFailsWith<IllegalArgumentException> { fitnessPlan(17) }
        assertEquals(errorFitnessBlockLength(CoreTextsDe), tooLong.message)
        // ERG kann den Test fahren.
        assertNotNull(ergWorkoutFor(test.first { it.textKey == SessionTextKey.FTP_TEST }, "Test"))
    }

    @Test
    fun `Zielzeit - Zieltempo in der langen Tour und Hinweis bei zu ehrgeiziger Zeit`() {
        val plan = generatePlan(goal(14, 120.0, targetMin = 300), assessment(FitnessLevel.FORTGESCHRITTEN, 90.0), now, texts = CoreTextsDe)
        val pace = plan.weeks.flatMap { it.sessions }.filter { it.textKey == SessionTextKey.LONG_RIDE && it.textArgs.getOrElse(1) { 0 } > 0 }
        assertTrue(pace.isNotEmpty())
        // 120 km in 5 h = 24,0 km/h.
        assertEquals(240, pace.first().textArgs[3])
        assertTrue(pace.first().description.contains("24,0 km/h"), pace.first().description)
        // Zieltempo beginnt mit Zielzeit schon in der Aufbauphase.
        val phases = planWeekPhases(plan)
        assertTrue(plan.weeks.any { w -> phases[w.index] == TrainingPhase.AUFBAU && w.sessions.any { it in pace } })

        val prognosis = GoalPrognosis(
            currentMin = 380, atEventMin = 360, uncertaintyMin = 20, ridesUsed = 5,
            longestRideKm = 90.0, flatSpeedKmh = 20.0, beyondLongestRide = true,
        )
        val verdict = assessPlanFeasibility(plan, prognosis, texts = CoreTextsDe)
        assertEquals(60, verdict.targetTimeGapMin)
        assertTrue(assertNotNull(verdict.targetTimeMessage).contains("5:00"))
        // Realistische Zielzeit: kein Hinweis.
        assertNull(assessPlanFeasibility(plan, prognosis.copy(atEventMin = 310), texts = CoreTextsDe).targetTimeMessage)
    }

    // -----------------------------------------------------------------------
    // Leser des Plans
    // -----------------------------------------------------------------------

    @Test
    fun `projectedEventCtl folgt den geplanten Wochenlasten`() {
        val projected = assertNotNull(projectedEventCtl(advanced, 35.0, now))
        // Eigene Rechnung: Exponentialglaettung ueber die Wochen vor der Zielwoche.
        var ctl = 35.0
        for (week in advanced.weeks.filter { it.kind != WeekKind.ZIELWOCHE }) {
            ctl += (week.sessions.sumOf { it.targetLoad!! } / 7 - ctl) * ctlWeeklyResponse
        }
        assertEquals(ctl, projected, 1e-9)
        // Plant der Plan weniger als die heutige CTL, sinkt die Vorschau.
        assertTrue(projectedEventCtl(advanced, 120.0, now)!! < 120.0)
    }

    @Test
    fun `neue Einheiten funktionieren in Klartext, Routing, ERG und Texten`() {
        for (session in allPlans.flatMap { p -> p.weeks.flatMap { it.sessions } }) {
            assertTrue(plainSessionTitle(session, CoreTextsDe).isNotBlank())
            assertTrue(plainSessionHint(session, null, CoreTextsEn).isNotBlank())
            assertTrue(de.trailscape.core.i18n.sessionTitle(session, CoreTextsEn).isNotBlank())
            assertTrue(de.trailscape.core.i18n.sessionDescription(session, CoreTextsEn).isNotBlank())
            if (canGenerateRouteFor(session)) {
                val target = routeTargetForSession(session, TrainingProfile(40), emptyList(), texts = CoreTextsDe)
                assertEquals(session.targetKm.toDouble().coerceAtLeast(1.0), target.distanceKm)
            }
        }
        val workout = advanced.weeks[2].sessions.first { it.textKey == SessionTextKey.WORKOUT }
        val erg = assertNotNull(ergWorkoutFor(workout, "Workout"))
        assertEquals(workout.durationMin!! * 60, erg.steps.sumOf { it.durationS })
    }
}
