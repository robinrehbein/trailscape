package de.trailscape.core.indoor

import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrainingSession
import de.trailscape.core.i18n.SessionTextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ERG-Einheit aus der Planeinheit: alle Pfade, exakte Summe, Mindestlaenge. */
class ErgWorkoutsTest {
    private fun session(
        intensity: SessionIntensity = SessionIntensity.GRUNDLAGE,
        durationMin: Int? = null,
        targetKm: Int = 0,
        textKey: SessionTextKey? = null,
        textArgs: List<Int> = emptyList(),
        isEvent: Boolean = false,
    ) = TrainingSession(
        day = "2026-11-23",
        title = "x",
        description = "y",
        targetKm = targetKm,
        intensity = intensity,
        durationMin = durationMin,
        isEvent = isEvent,
        textKey = textKey,
        textArgs = textArgs,
    )

    private fun assertWellFormed(w: ErgWorkout, totalS: Int) {
        assertEquals(totalS, w.totalS)
        assertTrue(w.steps.all { it.durationS >= MIN_STEP_S }, "${w.steps}")
        assertTrue(w.steps.all { it.ftpFraction in 0.3..1.2 })
    }

    @Test
    fun `das Zielevent ist keine Rolleneinheit`() {
        assertNull(ergWorkoutForImpl(session(isEvent = true, durationMin = 60), "Rennen"))
    }

    @Test
    fun `Intervalle folgen genau den Plan-Argumenten`() {
        val w = ergWorkoutForImpl(
            session(SessionIntensity.HART, 74, 30, SessionTextKey.INTERVALS, listOf(20, 4, 8, 4, 10, 74, 0)),
            "Intervalle",
        )!!
        assertEquals("Intervalle", w.title)
        val expected = listOf(
            ErgStep(ErgStepKind.WARMUP, 400, 0.50),
            ErgStep(ErgStepKind.WARMUP, 400, 0.60),
            ErgStep(ErgStepKind.WARMUP, 400, 0.70),
            ErgStep(ErgStepKind.WORK, 480, 0.95),
            ErgStep(ErgStepKind.RECOVER, 240, 0.50),
            ErgStep(ErgStepKind.WORK, 480, 0.95),
            ErgStep(ErgStepKind.RECOVER, 240, 0.50),
            ErgStep(ErgStepKind.WORK, 480, 0.95),
            ErgStep(ErgStepKind.RECOVER, 240, 0.50),
            ErgStep(ErgStepKind.WORK, 480, 0.95),
            ErgStep(ErgStepKind.COOLDOWN, 600, 0.45),
        )
        assertEquals(expected, w.steps)
        assertWellFormed(w, 74 * 60)
    }

    @Test
    fun `hart an der Schwelle heisst volle FTP`() {
        val w = ergWorkoutForImpl(
            session(SessionIntensity.HART, 68, 0, SessionTextKey.INTERVALS, listOf(20, 5, 6, 3, 10, 72, 1)),
            "t",
        )!!
        val work = w.steps.filter { it.kind == ErgStepKind.WORK }
        assertEquals(5, work.size)
        assertTrue(work.all { it.ftpFraction == 1.0 && it.durationS == 360 })
        assertEquals(4, w.steps.count { it.kind == ErgStepKind.RECOVER })
        // Die Struktur bestimmt die Dauer (20 + 30 + 12 + 10 = 72 min), nicht durationMin.
        assertWellFormed(w, 72 * 60)
    }

    @Test
    fun `kurzes Einfahren hat weniger Stufen`() {
        val one = ergWorkoutForImpl(session(textKey = SessionTextKey.INTERVALS, textArgs = listOf(1, 3, 10, 0, 5, 36, 0)), "t")!!
        assertEquals(listOf(ErgStep(ErgStepKind.WARMUP, 60, 0.55)), one.steps.filter { it.kind == ErgStepKind.WARMUP })
        assertEquals(0, one.steps.count { it.kind == ErgStepKind.RECOVER }) // Pause 0 → keine Erholung
        assertWellFormed(one, 36 * 60)
        val two = ergWorkoutForImpl(session(textKey = SessionTextKey.INTERVALS, textArgs = listOf(2, 3, 10, 2, 0, 36, 0)), "t")!!
        assertEquals(listOf(0.5, 0.7), two.steps.filter { it.kind == ErgStepKind.WARMUP }.map { it.ftpFraction })
        assertEquals(0, two.steps.count { it.kind == ErgStepKind.COOLDOWN })
        assertWellFormed(two, 36 * 60)
        val odd = ergWorkoutForImpl(session(textKey = SessionTextKey.INTERVALS, textArgs = listOf(11, 2, 10, 5, 5, 0, 0)), "t")!!
        assertEquals(listOf(220, 220, 220), odd.steps.take(3).map { it.durationS })
    }

    @Test
    fun `unplausible Argumente fallen auf die Intensitaet zurueck`() {
        val reference = ergWorkoutForImpl(session(SessionIntensity.HART, 60), "t")!!
        for (args in listOf(
            listOf(20, 4, 8),
            listOf(20, 0, 8, 4, 10, 0, 0),
            listOf(20, 4, 8, 4, 10, 74, 3),
            listOf(-5, 4, 8, 4, 10, 74, 0),
            listOf(20, 4, 0, 4, 10, 74, 0),
            listOf(0, 1, 2, 0, 0, 2, 0), // 2 min gesamt: zu kurz
        )) {
            val w = ergWorkoutForImpl(session(SessionIntensity.HART, 60, 0, SessionTextKey.INTERVALS, args), "t")!!
            assertEquals(reference, w, "$args")
        }
    }

    @Test
    fun `hart ohne Argumente 60 Minuten`() {
        val w = ergWorkoutForImpl(session(SessionIntensity.HART, 60), "t")!!
        // 10 + 4×8 + 3×4 + 5 = 59 min, 1 min gleichmaessig @0,70.
        assertEquals(4, w.steps.count { it.kind == ErgStepKind.WORK })
        assertEquals(ErgStep(ErgStepKind.STEADY, 60, 0.70), w.steps[w.steps.size - 2])
        assertEquals(ErgStep(ErgStepKind.COOLDOWN, 300, 0.45), w.steps.last())
        assertEquals(listOf(0.5, 0.6, 0.7), w.steps.take(3).map { it.ftpFraction })
        assertWellFormed(w, 3600)
    }

    @Test
    fun `hart lang hat hoechstens sechs Wiederholungen, kurz wird gestaucht`() {
        val long = ergWorkoutForImpl(session(SessionIntensity.HART, targetKm = 100), "t")!! // 240 min
        assertEquals(MAX_HARD_REPS, long.steps.count { it.kind == ErgStepKind.WORK })
        assertWellFormed(long, 240 * 60)
        val short = ergWorkoutForImpl(session(SessionIntensity.HART, 20), "t")!!
        assertEquals(2, short.steps.count { it.kind == ErgStepKind.WORK })
        assertEquals(0, short.steps.count { it.kind == ErgStepKind.STEADY })
        assertWellFormed(short, 20 * 60)
    }

    @Test
    fun `Grundlage und locker`() {
        val g = ergWorkoutForImpl(session(SessionIntensity.GRUNDLAGE, 90), "t")!!
        assertEquals(
            listOf(
                ErgStep(ErgStepKind.WARMUP, 300, 0.50),
                ErgStep(ErgStepKind.WARMUP, 300, 0.60),
                ErgStep(ErgStepKind.STEADY, 4500, 0.68),
                ErgStep(ErgStepKind.COOLDOWN, 300, 0.45),
            ),
            g.steps,
        )
        val l = ergWorkoutForImpl(session(SessionIntensity.LOCKER, 45), "t")!!
        assertEquals(
            listOf(
                ErgStep(ErgStepKind.WARMUP, 300, 0.45),
                ErgStep(ErgStepKind.STEADY, 2100, 0.55),
                ErgStep(ErgStepKind.COOLDOWN, 300, 0.45),
            ),
            l.steps,
        )
        // Genau so lang wie die festen Teile: kein leerer Mittelteil.
        val exact = ergWorkoutForImpl(session(SessionIntensity.GRUNDLAGE, 15), "t")!!
        assertEquals(3, exact.steps.size)
        assertWellFormed(exact, 900)
    }

    @Test
    fun `Dauer aus Minuten, Kilometern oder Vorgabe`() {
        assertEquals(75, sessionDurationMin(session(durationMin = 75, targetKm = 10)))
        assertEquals(120, sessionDurationMin(session(targetKm = 50))) // 50 km / 25 km/h
        assertEquals(20, sessionDurationMin(session(targetKm = 2)))
        assertEquals(240, sessionDurationMin(session(targetKm = 300)))
        assertEquals(60, sessionDurationMin(session()))
        assertEquals(60, sessionDurationMin(session(durationMin = 0)))
        assertEquals(MIN_DURATION_MIN, sessionDurationMin(session(durationMin = 1)))
        assertEquals(MAX_DURATION_MIN, sessionDurationMin(session(durationMin = 9999)))
    }

    @Test
    fun `jede Dauer ergibt exakte Summe ohne zu kurze Abschnitte`() {
        for (intensity in SessionIntensity.entries) {
            for (min in 1..400) {
                val w = ergWorkoutForImpl(session(intensity, min), "t")!!
                assertWellFormed(w, sessionDurationMin(session(intensity, min)) * 60)
            }
        }
    }
}
