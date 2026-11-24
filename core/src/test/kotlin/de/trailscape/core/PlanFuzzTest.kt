package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.PLAN_WEEKDAY_CODES
import de.trailscape.core.i18n.SessionTextKey
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Deterministischer Property-Test des Plangenerators: ein fester Seed,
 * zufaellige Eingaben ueber den ganzen zulaessigen Raum (Stufen, 3–52
 * Wochen, Startumfang 0–300 km/Wo, Ziel 20–400 km, CTL keine/klein/gross,
 * 1–7 Trainingstage, lange Tour an jedem Tag, Budget 1–20 h, Tempo
 * 8–35 km/h, Eventtag an jedem Wochentag, Fitnessblock 4–16 Wochen) — und
 * fuer jeden Plan die fachlichen Invarianten. Ein Teil der Faelle laeuft
 * zusaetzlich durch [adaptPlan].
 *
 * Schlaegt der Test fehl, nennt er je Invariante die Zahl der Verstoesse und
 * die ersten Beispiele mit ihren Eingaben — der Fall ist mit demselben Seed
 * jederzeit reproduzierbar.
 */
class PlanFuzzTest {
    private val firstMonday: LocalDate = LocalDate.of(2026, 1, 5)
    private val now: Long = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))

    private data class Case(
        val n: Int,
        val type: GoalType,
        val level: FitnessLevel,
        val weeks: Int,
        val startKm: Double,
        val goalKm: Double,
        val ascentM: Double?,
        val ctl: Double?,
        val days: List<Int>?,
        val longDay: Int?,
        val hours: Double?,
        val speed: Double?,
        val goalDay: Int,
        val targetMin: Int?,
    )

    private fun randomCase(r: Random, n: Int): Case {
        val type = if (r.nextInt(100) < 15) GoalType.FITNESS else GoalType.EVENT
        val weeks = if (type == GoalType.FITNESS) r.nextInt(FITNESS_MIN_WEEKS, FITNESS_MAX_WEEKS + 1) else r.nextInt(MIN_WEEKS, MAX_WEEKS + 1)
        val goalKm = if (type == GoalType.FITNESS) {
            if (r.nextBoolean()) 0.0 else r.nextInt(10, 121).toDouble()
        } else {
            r.nextInt(20, 401).toDouble()
        }
        val ctl = when (r.nextInt(3)) {
            0 -> null
            1 -> r.nextDouble(0.5, 8.0)
            else -> r.nextDouble(20.0, 120.0)
        }
        val days = if (r.nextInt(100) < 15) null else (0..6).shuffled(r).take(r.nextInt(1, 8)).sorted()
        return Case(
            n = n,
            type = type,
            level = FitnessLevel.entries[r.nextInt(3)],
            weeks = weeks,
            startKm = if (r.nextInt(10) == 0) 0.0 else r.nextInt(1, 301).toDouble(),
            goalKm = goalKm,
            ascentM = if (r.nextInt(3) == 0) r.nextInt(0, 4000).toDouble() else null,
            ctl = ctl,
            days = days,
            longDay = if (r.nextInt(4) == 0) null else r.nextInt(7),
            hours = if (r.nextInt(10) < 3) null else r.nextInt(2, 41) / 2.0,
            speed = if (r.nextInt(10) == 0) null else r.nextDouble(8.0, 35.0),
            goalDay = r.nextInt(7),
            targetMin = if (type == GoalType.EVENT && r.nextInt(4) == 0) (goalKm / r.nextDouble(15.0, 32.0) * 60).toInt() else null,
        )
    }

    private fun planFor(c: Case): TrainingPlan {
        val goal = if (c.type == GoalType.FITNESS) {
            fitnessGoal("Block", c.weeks, now, c.goalKm, c.goalDay)
        } else {
            Goal(
                name = "Ziel",
                distanceKm = c.goalKm,
                ascentM = c.ascentM,
                date = dartEpochMs(firstMonday.plusDays((c.weeks - 1) * 7L + c.goalDay).atTime(9, 0)),
                targetDurationMin = c.targetMin,
            )
        }
        val assessment = FitnessAssessment(c.level, c.startKm, c.startKm * 8, 3.0, c.startKm / 2, if (c.startKm > 0) 20 else 0)
        val prefs = PlanPreferences(trainingDays = c.days, longRideDay = c.longDay, weeklyHours = c.hours)
        return generatePlan(goal, assessment, now, c.ctl, prefs, c.speed, CoreTextsDe)
    }

    private class Violations {
        val counts = sortedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        fun add(name: String, case: Case, detail: String) {
            counts[name] = (counts[name] ?: 0) + 1
            if (counts.getValue(name) <= 3) examples += "[$name] $detail — $case"
        }
    }

    private fun minutes(sessions: List<TrainingSession>) = sessions.sumOf { it.durationMin ?: 0 }
    private fun load(s: List<TrainingSession>) = s.filterNot { it.isEvent }.sumOf { it.targetLoad ?: 0.0 }
    private fun dayOf(s: TrainingSession) = PLAN_WEEKDAY_CODES.indexOf(s.day)
    private fun isFixed(s: TrainingSession) = s.isEvent || s.textKey == SessionTextKey.BENCHMARK_RIDE

    /**
     * Die dokumentierte Untergrenze einer Woche (siehe `MIN_SESSION_MIN`):
     * genau eine Einheit, und zwar die kuerzeste Strecke, die 30 Minuten dauert.
     */
    private fun floorWeek(plan: TrainingPlan, sessions: List<TrainingSession>): Boolean {
        val only = sessions.singleOrNull() ?: return false
        val speed = maxOf((plan.planSpeedKmh ?: fallbackGravelSpeedKmh) * intensitySpeedFactor(only.intensity), 1.0)
        return only.targetKm == kotlin.math.ceil(MIN_SESSION_MIN / 60.0 * speed).toInt()
    }

    /** Die Einheiten, die das Zeitbudget tragen muss: ohne Event und ohne festgelegte Vergleichsrunde. */
    private fun budgeted(plan: TrainingPlan, sessions: List<TrainingSession>): List<TrainingSession> {
        val fixedBench = plan.goal.type == GoalType.FITNESS &&
            plan.goal.distanceKm in FITNESS_BENCHMARK_MIN_KM..FITNESS_BENCHMARK_MAX_KM
        return sessions.filterNot { it.isEvent || (fixedBench && it.textKey == SessionTextKey.BENCHMARK_RIDE) }
    }

    private fun check(c: Case, plan: TrainingPlan, v: Violations, adapted: Boolean) {
        val tag = if (adapted) "adapt:" else ""
        val event = plan.goal.type == GoalType.EVENT
        val budget = plan.preferences?.validWeeklyHours()?.let { it * 60 }
        val builds = plan.weeks.filter { it.kind == WeekKind.AUFBAU }

        // Zeitbudget ohne Event (Rundungstoleranz 1 min).
        if (budget != null) {
            for (w in plan.weeks) {
                val m = minutes(budgeted(plan, w.sessions))
                if (m > budget + 1) v.add("${tag}Budget", c, "Woche ${w.index} ${w.kind}: $m > $budget")
            }
        }
        for (w in plan.weeks) {
            if (w.targetKm != w.sessions.sumOf { it.targetKm }) v.add("${tag}Wochensumme", c, "Woche ${w.index}")
            for (s in w.sessions.filterNot { it.isEvent }) {
                val d = s.durationMin ?: 0
                if (d < MIN_SESSION_MIN && s in budgeted(plan, w.sessions)) v.add("${tag}Mindestdauer", c, "Woche ${w.index} ${s.title} $d min")
                if (s.targetLoad == null) v.add("${tag}Last fehlt", c, "Woche ${w.index} ${s.title}")
                when (s.textKey) {
                    SessionTextKey.RECOVERY_SPIN, SessionTextKey.ENDURANCE_COMPENSATION ->
                        if (d > RECOVERY_RIDE_MAX_MIN) v.add("${tag}Regeneration zu lang", c, "Woche ${w.index} ${s.title} $d min")
                    SessionTextKey.TAPER_SHORT_EASY ->
                        if (d > TAPER_SHORT_MAX_MIN) v.add("${tag}Kurze Ausfahrt zu lang", c, "Woche ${w.index} $d min")
                    SessionTextKey.LONG_RIDE -> {
                        if (d < LONG_RIDE_MIN_MIN) v.add("${tag}Lange Tour zu kurz", c, "Woche ${w.index} $d min")
                        if (w.kind == WeekKind.TAPER && d > TAPER_LONG_RIDE_MAX_MIN) v.add("${tag}Taper-Tour zu lang", c, "Woche ${w.index} $d min")
                    }
                    SessionTextKey.RECOVERY_LONG ->
                        if (d > TAPER_LONG_RIDE_MAX_MIN) v.add("${tag}Erholungs-Tour zu lang", c, "Woche ${w.index} $d min")
                    SessionTextKey.ENDURANCE_FILL, SessionTextKey.ENDURANCE_STEADY, SessionTextKey.BASE_EASY_RIDE ->
                        if (d > ENDURANCE_RIDE_MAX_MIN) v.add("${tag}GA1 zu lang", c, "Woche ${w.index} ${s.title} $d min")
                    else -> if (s.intensity == SessionIntensity.LOCKER && d > EASY_RIDE_MAX_MIN) {
                        v.add("${tag}Lockere Fahrt zu lang", c, "Woche ${w.index} ${s.title} $d min")
                    }
                }
                if (event && s.intensity == SessionIntensity.GRUNDLAGE && s.targetKm > plan.goal.distanceKm) v.add("${tag}Fahrt laenger als Event", c, "Woche ${w.index} ${s.title} ${s.targetKm} km")
            }
        }

        // Lange Tour ist im Aufbau eines Eventplans die laengste Einheit.
        if (event) {
            for (w in builds) {
                val training = w.sessions.filterNot { it.isEvent }
                val hardMax = training.filter { it.intensity == SessionIntensity.HART }.maxOfOrNull { it.durationMin ?: 0 } ?: 0
                val easyMax = training.filter { it.intensity != SessionIntensity.HART }.maxOfOrNull { it.durationMin ?: 0 } ?: 0
                if (hardMax > easyMax) v.add("${tag}Lange Tour laengste", c, "Woche ${w.index}: ${training.map { it.title to it.durationMin }}")
                val long = training.firstOrNull { isLongRideSession(it) }
                val longer = training.any {
                    (it.durationMin ?: 0) > (long?.durationMin ?: 0) ||
                        (it.intensity != SessionIntensity.HART && it.targetKm > (long?.targetKm ?: 0))
                }
                if (long != null && longer) {
                    v.add("${tag}Lange Tour laengste", c, "Woche ${w.index}: ${training.map { it.title to it.durationMin }}")
                }
            }
        }

        // Harte Tage: nie am Tag nach/vor der langen Tour, nie nebeneinander, hoechstens 2 je 7 Tage.
        val line = plan.weeks.flatMap { w -> w.sessions.map { w.index * 7 + dayOf(it) to it } }
        val longDays = line.filter { isLongRideSession(it.second) }.map { it.first }.toSet()
        val hard = line.filter { it.second.intensity == SessionIntensity.HART }
        val hardDays = hard.map { it.first }.toSet()
        for ((d, s) in hard) {
            if (isFixed(s)) continue
            if ((d - 1) in longDays) v.add("${tag}Hart nach langer Tour", c, "Tag $d ${s.title}")
            if ((d + 1) in longDays) v.add("${tag}Hart vor langer Tour", c, "Tag $d ${s.title}")
            if ((d + 1) in hardDays || (d - 1) in hardDays) v.add("${tag}Harte Tage nebeneinander", c, "Tag $d ${s.title}")
            if ((d - 6..d).any { start -> hardDays.count { it in start..start + 6 } > 2 }) v.add("${tag}Mehr als 2 harte Tage", c, "Tag $d")
        }

        if (adapted) return

        // Lastdeckel: ≤ 1,30 × Referenz, oder die dokumentierte Untergrenze (eine Einheit mit Mindestdauer).
        val ctl = c.ctl
        if (ctl != null) {
            var reference = 7 * ctl
            for (w in builds) {
                val l = load(w.sessions)
                val floorWeek = floorWeek(plan, w.sessions)
                if (l > LOAD_RAMP_CAP * reference + 1e-6 && !floorWeek) v.add("Lastdeckel", c, "Woche ${w.index}: $l > ${LOAD_RAMP_CAP * reference}")
                reference = maxOf(reference, l)
            }
        }

        // km-Rampe auf die tatsaechliche Wochensumme.
        var last = startKmFor(FitnessAssessment(c.level, c.startKm, 0.0, 0.0, 0.0, if (c.startKm > 0) 20 else 0))
        for (w in builds) {
            if (w.targetKm > rampCapKm(last) + 1e-6 && !floorWeek(plan, w.sessions)) {
                v.add("km-Rampe", c, "Woche ${w.index}: ${w.targetKm} > ${rampCapKm(last)}")
            }
            last = w.targetKm.toDouble()
        }

        // Erholung: ≤ round5(55 %) der letzten Aufbauwoche.
        for (w in plan.weeks.filter { it.kind == WeekKind.ERHOLUNG }) {
            val prev = plan.weeks.subList(0, w.index).lastOrNull { it.kind == WeekKind.AUFBAU } ?: continue
            val floorWeek = floorWeek(plan, w.sessions)
            if (w.targetKm > maxOf(5, dartRound(prev.targetKm * 0.55 / 5).toInt() * 5) && !floorWeek) {
                v.add("Erholung", c, "Woche ${w.index}: ${w.targetKm} vs ${prev.targetKm}")
            }
        }

        // Taper: Anteil am erreichten Spitzenumfang, absteigend, Haeufigkeit nicht hoeher.
        if (event) {
            val tapers = plan.weeks.filter { it.kind == WeekKind.TAPER }
            val peak = builds.maxOfOrNull { it.targetKm }?.toDouble() ?: last
            val factors = if (tapers.size >= 2) listOf(0.65, 0.45) else listOf(0.55)
            tapers.forEachIndexed { i, w ->
                val km = w.sessions.filter { it.textKey != SessionTextKey.ACTIVATION }.sumOf { it.targetKm }
                val allowed = maxOf(5, dartRound(peak * factors[i] / 5).toInt() * 5)
                val floorWeek = floorWeek(plan, w.sessions.filter { it.textKey != SessionTextKey.ACTIVATION })
                if (km > allowed && !floorWeek) v.add("Taper-Umfang", c, "Woche ${w.index}: $km > $allowed (Peak $peak)")
                val lastBuild = builds.lastOrNull()
                if (lastBuild != null && w.sessions.count { it.textKey != SessionTextKey.ACTIVATION } > lastBuild.sessions.size) {
                    v.add("Taper-Haeufigkeit", c, "Woche ${w.index}")
                }
            }
        }

        // Fitnessblock: nichts nach dem Zieltag, Vergleichsrunde am Zieltag, Test wo moeglich.
        if (!event) {
            val goalDay = dartLocalOf(plan.goal.date).dayOfWeek.value - 1
            val test = plan.weeks.last().sessions
            if (test.any { dayOf(it) > goalDay }) v.add("Test nach Zieltag", c, test.map { it.day }.toString())
            val bench = test.filter { it.textKey == SessionTextKey.BENCHMARK_RIDE }
            if (bench.size != 1 || dayOf(bench.single()) != goalDay) v.add("Vergleichsrunde", c, test.map { it.title }.toString())
            val contents = fitnessTestWeekContents(plan)!!
            if (contents.hasFtpTest != test.any { it.textKey == SessionTextKey.FTP_TEST }) v.add("Testabfrage", c, "")
        }

        // JSON: verlustfrei und byteidentisch zurueckschreibbar.
        val json = plan.toJson().toString()
        val read = TrainingPlan.fromJson(Json.parseToJsonElement(json).jsonObject)
        if (read != plan || read.toJson().toString() != json) v.add("JSON", c, "")
    }

    /** Eine Fahrt pro Planeinheit der ersten Woche, aber nur mit [share] ihrer Laenge. */
    private fun ridesFor(plan: TrainingPlan, share: Double): List<Ride> = plan.weeks.first().sessions.mapIndexed { i, s ->
        Ride(
            id = "f$i",
            name = "Fahrt",
            createdAt = plan.weeks.first().start + dayOf(s) * 86_400_000L + 9 * 3_600_000L,
            stats = RideStats(distanceKm = s.targetKm * share, ascentM = 0.0, descentM = 0.0),
        )
    }

    @Test
    fun `Plangenerator - Invarianten ueber zufaellige Eingaben`() {
        val random = Random(20261124)
        val v = Violations()
        val cases = 2500
        repeat(cases) { n ->
            val c = randomCase(random, n)
            val plan = planFor(c)
            check(c, plan, v, adapted = false)
            if (n % 5 == 0 && plan.weeks.size >= 4 && plan.weeks.first().kind == WeekKind.AUFBAU) {
                val result = adaptPlan(plan, ridesFor(plan, 0.4), now = plan.weeks[1].start + 3_600_000L, currentCtl = c.ctl, texts = CoreTextsDe)
                if (result.adapted) check(c, result.plan, v, adapted = true)
            }
        }
        assertTrue(
            v.counts.isEmpty(),
            "Verstoesse in $cases Faellen: ${v.counts}\n" + v.examples.joinToString("\n"),
        )
    }
}
