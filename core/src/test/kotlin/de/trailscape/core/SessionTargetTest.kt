package de.trailscape.core

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer `SessionTarget.kt` — die Ableitung eines [RouteTarget] aus einer
 * geplanten Einheit bzw. aus der Tagesempfehlung.
 *
 * Die Einheiten stammen wo immer moeglich aus [generatePlan], damit die
 * Klassifikation gegen echte, vom Generator erzeugte Titel und
 * Beschreibungstexte laeuft und nicht gegen erfundene.
 */
class SessionTargetTest {
    private companion object {
        const val EPS = 1e-9
        const val DAY_MS = 24L * 60 * 60 * 1000

        val defaultProfile = TrainingProfile(ageYears = 40)

        fun ride(
            createdAt: Long,
            avgSpeedKmh: Double?,
            distanceKm: Double = 40.0,
        ): Ride = Ride(
            id = "r$createdAt",
            name = "Tour",
            createdAt = createdAt,
            stats = RideStats(
                distanceKm = distanceKm,
                ascentM = 0.0,
                descentM = 0.0,
                avgSpeedKmh = avgSpeedKmh,
            ),
        )

        fun session(title: String, description: String = "", targetKm: Int = 40): TrainingSession =
            TrainingSession(day = "Di", title = title, description = description, targetKm = targetKm)

        fun recommendation(kind: DailyRecommendationKind): DailyRecommendation =
            DailyRecommendation(kind = kind, title = "Titel", detail = "Detail", reasons = emptyList())

        /**
         * Realer Plan aus [generatePlan]: Ziel 12 Wochen entfernt, damit alle
         * Wochenarten (Aufbau, Erholung, Taper, Zielwoche) vorkommen.
         */
        fun plan(ascentM: Double?): TrainingPlan {
            val now = 1_700_000_000_000L
            val goal = Goal(
                name = "Gravel-Marathon",
                distanceKm = 120.0,
                ascentM = ascentM,
                date = now + 84 * DAY_MS,
            )
            val assessment = FitnessAssessment(
                level = FitnessLevel.FORTGESCHRITTEN,
                weeklyKm = 120.0,
                weeklyHm = 900.0,
                weeklyRides = 3.0,
                longestRideKm = 80.0,
                rideCount = 20,
            )
            return generatePlan(goal, assessment, now)
        }

        /** Erste Einheit im Plan, deren Titel [title] enthaelt. */
        fun sessionFromPlan(plan: TrainingPlan, title: String): TrainingSession =
            plan.weeks.flatMap { it.sessions }.first { it.title.contains(title) }
    }

    // --- Geschwindigkeit aus der Historie ---

    @Test
    fun `Median der letzten zehn Touren bestimmt die Geschwindigkeit`() {
        // 12 Touren, absteigend im Alter: die zwei aeltesten (5 und 6 km h)
        // duerfen den Median NICHT mehr beeinflussen.
        val speeds = listOf(24.0, 23.0, 22.0, 21.0, 20.0, 19.0, 18.0, 17.0, 16.0, 15.0, 6.0, 7.0)
        val rides = speeds.mapIndexed { i, s -> ride(createdAt = 10_000L - i, avgSpeedKmh = s) }

        // Median der zehn juengsten (15…24) = (19 + 20) / 2.
        assertEquals(19.5, typicalAvgSpeedKmh(rides)!!, EPS)
    }

    @Test
    fun `unbrauchbare Schnitte werden aussortiert`() {
        val rides = listOf(
            ride(createdAt = 5, avgSpeedKmh = null),
            ride(createdAt = 4, avgSpeedKmh = Double.NaN),
            // zu langsam / zu schnell fuer eine echte Radausfahrt
            ride(createdAt = 3, avgSpeedKmh = 2.0),
            ride(createdAt = 2, avgSpeedKmh = 90.0),
            // zu kurz, um aussagekraeftig zu sein
            ride(createdAt = 1, avgSpeedKmh = 30.0, distanceKm = 1.2),
            ride(createdAt = 0, avgSpeedKmh = 21.0),
        )

        assertEquals(21.0, typicalAvgSpeedKmh(rides)!!, EPS)
    }

    @Test
    fun `ohne brauchbare Historie gibt es keinen Median`() {
        assertNull(typicalAvgSpeedKmh(emptyList()))
        assertNull(typicalAvgSpeedKmh(listOf(ride(createdAt = 1, avgSpeedKmh = null))))
    }

    @Test
    fun `Fallback ohne Historie sind 18 km h`() {
        assertEquals(fallbackGravelSpeedKmh, fallbackSpeedKmh(defaultProfile), EPS)
        assertEquals(18.0, fallbackSpeedKmh(defaultProfile), EPS)

        val target = routeTargetForSession(session("GA1", targetKm = 36), defaultProfile, emptyList())
        // GA1 = Grundlage -> Faktor 0,95.
        assertEquals(18.0 * 0.95, target.speedKmh, EPS)
        assertEquals(36.0 / (18.0 * 0.95), target.durationH!!, EPS)
    }

    @Test
    fun `hinterlegte FTP hebt den Fallback mit der dritten Wurzel an`() {
        // 300 W bei 75 kg = 4,0 W/kg gegen 2,4 W/kg Referenz.
        val strong = TrainingProfile(ageYears = 40, eftpOverrideW = 300.0)
        val expected = 18.0 * (4.0 / 2.4).pow(1.0 / 3.0)

        assertEquals(expected, fallbackSpeedKmh(strong), 1e-9)
        assertTrue(fallbackSpeedKmh(strong) > fallbackSpeedKmh(defaultProfile))
    }

    @Test
    fun `Historie schlaegt den Profil-Fallback`() {
        val rides = List(5) { ride(createdAt = it.toLong(), avgSpeedKmh = 25.0) }
        val target = routeTargetForSession(session("GA1"), defaultProfile, rides)

        assertEquals(25.0 * 0.95, target.speedKmh, EPS)
    }

    // --- Klassifikation echter Plan-Einheiten ---

    @Test
    fun `Intensitaet echter Plan-Einheiten`() {
        val p = plan(ascentM = 400.0)

        assertEquals(SessionIntensity.GRUNDLAGE, classifySessionIntensity(sessionFromPlan(p, "GA1")))
        assertEquals(SessionIntensity.HART, classifySessionIntensity(sessionFromPlan(p, "Intervalle")))
        assertEquals(
            SessionIntensity.GRUNDLAGE,
            classifySessionIntensity(sessionFromPlan(p, "Lange Tour")),
        )
        assertEquals(
            SessionIntensity.LOCKER,
            classifySessionIntensity(sessionFromPlan(p, "Lockere Ausfahrt")),
        )
        assertEquals(
            SessionIntensity.LOCKER,
            classifySessionIntensity(sessionFromPlan(p, "Ruhige Runde")),
        )
        // Taper: "Locker mit Antritten" traegt Antritte, bleibt aber locker.
        assertEquals(
            SessionIntensity.LOCKER,
            classifySessionIntensity(sessionFromPlan(p, "Locker mit Antritten")),
        )
        assertEquals(SessionIntensity.HART, classifySessionIntensity(sessionFromPlan(p, "Zielevent")))
    }

    @Test
    fun `unbekannter Titel faellt auf Grundlage zurueck`() {
        assertEquals(SessionIntensity.GRUNDLAGE, classifySessionIntensity(session("Hausrunde")))
    }

    @Test
    fun `Grundlage und Erholung wollen flach`() {
        val p = plan(ascentM = 400.0)

        assertEquals(AscentPreference.FLACH, ascentPreferenceForSession(sessionFromPlan(p, "GA1")))
        assertEquals(
            AscentPreference.FLACH,
            ascentPreferenceForSession(sessionFromPlan(p, "Lockere Ausfahrt")),
        )
        // Ohne Kletterhinweis bleibt auch die lange Tour flach.
        assertEquals(
            AscentPreference.FLACH,
            ascentPreferenceForSession(sessionFromPlan(p, "Lange Tour")),
        )
    }

    @Test
    fun `Intervalle wollen welliges Terrain`() {
        val p = plan(ascentM = 400.0)

        assertEquals(
            AscentPreference.MODERAT,
            ascentPreferenceForSession(sessionFromPlan(p, "Intervalle")),
        )
    }

    @Test
    fun `Kletterhinweis im Plantext ergibt eine bergige Runde`() {
        // Ab 1000 Hm Zielhoehe haengt generatePlan den Kletterhinweis an.
        val bergig = plan(ascentM = 1800.0)
        val lang = sessionFromPlan(bergig, "Lange Tour")

        assertTrue(lang.description.contains("Anstiege"))
        assertEquals(AscentPreference.BERGIG, ascentPreferenceForSession(lang))
        assertEquals(AscentPreference.BERGIG, routeTargetForSession(lang, defaultProfile, emptyList()).ascentPreference)

        // Auch das Zielevent nennt dann die Anstiege.
        assertEquals(
            AscentPreference.BERGIG,
            ascentPreferenceForSession(sessionFromPlan(bergig, "Zielevent")),
        )
    }

    // --- routeTargetForSession ---

    @Test
    fun `Plan-Kilometer sind die Zieldistanz`() {
        val rides = List(3) { ride(createdAt = it.toLong(), avgSpeedKmh = 20.0) }
        val s = sessionFromPlan(plan(ascentM = 400.0), "Lange Tour")

        val target = routeTargetForSession(s, defaultProfile, rides)

        assertEquals(s.targetKm.toDouble(), target.distanceKm, EPS)
        assertEquals(RouteTargetSource.PLAN, target.source)
        assertEquals(s.title, target.label)
    }

    @Test
    fun `lockere Einheit rechnet mit 0,9-fachem Tempo, harte mit vollem`() {
        val rides = List(3) { ride(createdAt = it.toLong(), avgSpeedKmh = 20.0) }

        val locker = routeTargetForSession(session("Ruhige Runde", targetKm = 30), defaultProfile, rides)
        val hart = routeTargetForSession(session("Intervalle", targetKm = 30), defaultProfile, rides)

        assertEquals(18.0, locker.speedKmh, EPS)
        assertEquals(20.0, hart.speedKmh, EPS)
        // Gleiche Distanz, lockerer also laenger unterwegs.
        assertTrue(locker.durationH!! > hart.durationH!!)
        assertEquals(30.0 / 18.0, locker.durationH, EPS)
    }

    // --- routeTargetForToday ---

    @Test
    fun `Ruhetag liefert kein Routenziel`() {
        assertNull(
            routeTargetForToday(
                recommendation(DailyRecommendationKind.RUHETAG),
                defaultProfile,
                emptyList(),
            ),
        )
    }

    @Test
    fun `Locker in Z2 ergibt 75 Minuten flach`() {
        val rides = List(3) { ride(createdAt = it.toLong(), avgSpeedKmh = 20.0) }

        val target = routeTargetForToday(
            recommendation(DailyRecommendationKind.LOCKER_Z2),
            defaultProfile,
            rides,
        )

        assertNotNull(target)
        assertEquals(1.25, target.durationH!!, EPS)
        assertEquals(20.0 * 0.9, target.speedKmh, EPS)
        assertEquals(1.25 * 20.0 * 0.9, target.distanceKm, EPS)
        assertEquals(AscentPreference.FLACH, target.ascentPreference)
        assertEquals(RouteTargetSource.TAGESEMPFEHLUNG, target.source)
    }

    @Test
    fun `Regenerationsfahrt ist kuerzer als eine Grundlageneinheit`() {
        val recovery = routeTargetForToday(
            recommendation(DailyRecommendationKind.RECOVERY),
            defaultProfile,
            emptyList(),
        )!!
        val base = routeTargetForToday(
            recommendation(DailyRecommendationKind.GRUNDLAGE),
            defaultProfile,
            emptyList(),
        )!!

        assertEquals(1.0, recovery.durationH!!, EPS)
        assertEquals(2.0, base.durationH!!, EPS)
        assertTrue(recovery.distanceKm < base.distanceKm)
    }

    @Test
    fun `harte Einheit will welliges Terrain und volles Tempo`() {
        val rides = List(3) { ride(createdAt = it.toLong(), avgSpeedKmh = 22.0) }

        val target = routeTargetForToday(
            recommendation(DailyRecommendationKind.HARTE_EINHEIT),
            defaultProfile,
            rides,
        )!!

        assertEquals(SessionIntensity.HART, target.intensity)
        assertEquals(22.0, target.speedKmh, EPS)
        assertEquals(1.5 * 22.0, target.distanceKm, EPS)
        assertEquals(AscentPreference.MODERAT, target.ascentPreference)
    }

    @Test
    fun `Wochenziel steuert die Dauer der Grundlageneinheit`() {
        // weeklyLoad so gewaehlt, dass estimatedHours = 9 -> ein Drittel = 3 h.
        val weekly = weeklyLoadTarget(ctl = 50.0, targetRamp = 0.0)
            .copy(weeklyLoad = 9 * weeklyLoadPerHour)

        val target = routeTargetForToday(
            recommendation(DailyRecommendationKind.GRUNDLAGE),
            defaultProfile,
            emptyList(),
            weeklyTarget = weekly,
        )!!

        assertEquals(3.0, target.durationH!!, 1e-9)
    }

    @Test
    fun `Zeitbudget im Profil deckelt die Ausfahrt`() {
        // 3 h pro Woche -> hoechstens 1,5 h fuer eine einzelne Runde.
        val knapp = TrainingProfile(ageYears = 40, weeklyHours = 3.0)

        val target = routeTargetForToday(
            recommendation(DailyRecommendationKind.GRUNDLAGE),
            knapp,
            emptyList(),
        )!!

        assertEquals(1.5, target.durationH!!, EPS)
    }

    @Test
    fun `Tagesempfehlung aus recommendToday laesst sich direkt weiterreichen`() {
        // Ohne Vitaldaten liefert der Rechenkern die Grundlageneinheit.
        val readiness = computeReadiness(
            restingHr = RestingHrAssessment.unavailable("keine Daten", 0),
            sleep = SleepAssessment.unavailable("keine Daten", 0),
        )
        val rec = recommendToday(readiness = readiness)
        assertEquals(DailyRecommendationKind.GRUNDLAGE, rec.kind)

        val target = routeTargetForToday(rec, defaultProfile, emptyList())!!

        assertEquals(rec.title, target.label)
        assertEquals(2.0 * 18.0 * 0.95, target.distanceKm, EPS)
    }
}
