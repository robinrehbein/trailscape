package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [decideTodayRoute] — die Verkettung Tagesform → Trainingsplan →
 * Routenziel.
 *
 * Diese Logik lag frueher als `when`-Block in `ui/today/TodayScreen.kt` und war
 * damit ungetestet: Genau die Kette, die das Alleinstellungsmerkmal der App
 * ausmacht, hatte keinen einzigen Fall abgedeckt.
 */
class TodayRouteTest {
    private companion object {
        const val EPS = 1e-9

        val profile = TrainingProfile(ageYears = 40)

        /** Zehn Touren mit 20 km/h Schnitt — der Median der Historie ist damit 20. */
        val rides: List<Ride> = List(10) { i ->
            Ride(
                id = "r$i",
                name = "Tour",
                createdAt = i.toLong(),
                stats = RideStats(
                    distanceKm = 40.0,
                    ascentM = 0.0,
                    descentM = 0.0,
                    avgSpeedKmh = 20.0,
                ),
            )
        }

        fun recommendation(kind: DailyRecommendationKind) = DailyRecommendation(
            kind = kind,
            title = "Titel",
            detail = "Detail",
            reasons = emptyList(),
        )

        /** Die „Lange Tour" eines echten Plans mit Kletterauftrag (Ziel ≥ 1000 Hm). */
        fun longTour(targetKm: Int = 90): TrainingSession = TrainingSession(
            day = "Sa",
            title = "Lange Tour",
            description = "Die Schlüsseleinheit der Woche: gleichmäßig im Grundlagentempo " +
                "fahren. Baue dabei bewusst Anstiege ein, um dich an die Höhenmeter des " +
                "Ziels zu gewöhnen.",
            targetKm = targetKm,
            intensity = SessionIntensity.GRUNDLAGE,
            durationMin = 300,
        )

        fun decide(
            kind: DailyRecommendationKind,
            session: TrainingSession?,
        ): TodayRoute = decideTodayRoute(
            recommendation = recommendation(kind),
            session = session,
            profile = profile,
            recentRides = rides,
        )
    }

    // -----------------------------------------------------------------------
    // Die Tagesform wirkt — und zwar nicht nur binaer
    // -----------------------------------------------------------------------

    @Test
    fun `bei lockerer Empfehlung wird die Plandistanz gekuerzt und flach gemacht`() {
        val session = longTour(targetKm = 90)
        val route = decide(DailyRecommendationKind.LOCKER_Z2, session)
        val target = assertNotNull(route.target)

        // Frueher: 90 km bergig, unveraendert. Jetzt: 0,6 × 90 = 54 km, flach.
        assertEquals(54.0, target.distanceKm, EPS)
        assertEquals(AscentPreference.FLACH, target.ascentPreference)
        assertEquals(90, route.plannedKm)
        assertEquals(0.6, route.factor, EPS)
        assertTrue(route.downgraded)

        // Die Karte muss beide Zahlen und den Grund nennen.
        val note = assertNotNull(route.note)
        assertTrue(note.contains("90 km"), note)
        assertTrue(note.contains("54 km"), note)
        assertTrue(note.contains("flach"), note)
        assertTrue(note.contains("weil"), note)
    }

    @Test
    fun `die Hoehenpraeferenz allein reicht fuer eine Ansage`() {
        // Grundlagentag: 0,9 kuerzt, und ein bergiger Kletterauftrag wird auf
        // wellig zurueckgenommen. Beides steht im Text.
        val route = decide(DailyRecommendationKind.GRUNDLAGE, longTour(targetKm = 100))
        val target = assertNotNull(route.target)

        assertEquals(90.0, target.distanceKm, EPS)
        assertEquals(AscentPreference.MODERAT, target.ascentPreference)
        assertTrue(route.downgraded)
        assertTrue(assertNotNull(route.note).contains("wellig"))
    }

    @Test
    fun `an einem guten Tag gilt der Plan unveraendert`() {
        val session = longTour(targetKm = 90)
        val route = decide(DailyRecommendationKind.HARTE_EINHEIT, session)
        val target = assertNotNull(route.target)

        assertEquals(90.0, target.distanceKm, EPS)
        assertEquals(AscentPreference.BERGIG, target.ascentPreference)
        assertEquals(1.0, route.factor, EPS)
        assertFalse(route.downgraded)
        assertNull(route.note)
    }

    @Test
    fun `eine harte Planeinheit wird an einem lockeren Tag nicht hart gefahren`() {
        val intervals = TrainingSession(
            day = "Do",
            title = "Intervalle",
            description = "Nach 20 Minuten Einfahren 4×8 Minuten zügig im Schwellenbereich.",
            targetKm = 30,
            intensity = SessionIntensity.HART,
            durationMin = 74,
        )
        val route = decide(DailyRecommendationKind.LOCKER_Z2, intervals)
        val target = assertNotNull(route.target)

        // Sonst stuende „Keine Intervalle" ueber einem Ziel, das als intensiv
        // gefuehrt wird — und die Dauer waere mit dem falschen Tempo gerechnet.
        assertEquals(SessionIntensity.LOCKER, target.intensity)
        assertEquals(20.0 * 0.9, target.speedKmh, EPS)
        assertEquals(target.distanceKm / target.speedKmh, target.durationH!!, EPS)
    }

    @Test
    fun `die Reduktion greift auf jeder Stufe der Empfehlung`() {
        val session = longTour(targetKm = 100)
        val expected = mapOf(
            DailyRecommendationKind.RECOVERY to 60.0,
            DailyRecommendationKind.LOCKER_Z2 to 60.0,
            DailyRecommendationKind.GRUNDLAGE to 90.0,
            DailyRecommendationKind.HARTE_EINHEIT to 100.0,
        )
        for ((kind, km) in expected) {
            assertEquals(km, decide(kind, session).target!!.distanceKm, EPS, "$kind")
        }
        // Ruhetag: gar kein Angebot.
        assertNull(decide(DailyRecommendationKind.RUHETAG, session).target)
    }

    // -----------------------------------------------------------------------
    // Ruhetag
    // -----------------------------------------------------------------------

    @Test
    fun `am Ruhetag gibt es kein Routenziel, aber eine Erklaerung zur Planeinheit`() {
        val route = decide(DailyRecommendationKind.RUHETAG, longTour(targetKm = 90))

        assertNull(route.target)
        assertEquals(90, route.plannedKm)
        assertTrue(route.downgraded)
        val note = assertNotNull(route.note)
        assertTrue(note.contains("Lange Tour"), note)
        assertTrue(note.contains("90 km"), note)
    }

    @Test
    fun `Ruhetag ohne Planeinheit bleibt wortlos`() {
        val route = decide(DailyRecommendationKind.RUHETAG, null)
        assertNull(route.target)
        assertNull(route.note)
        assertFalse(route.downgraded)
    }

    // -----------------------------------------------------------------------
    // Ohne Plan bleibt alles beim Alten
    // -----------------------------------------------------------------------

    @Test
    fun `ohne Planeinheit entscheidet allein die Tagesempfehlung`() {
        val route = decide(DailyRecommendationKind.GRUNDLAGE, null)
        val target = assertNotNull(route.target)

        val expected = routeTargetForToday(
            recommendation = recommendation(DailyRecommendationKind.GRUNDLAGE),
            profile = profile,
            recentRides = rides,
        )
        assertEquals(expected, target)
        assertEquals(RouteTargetSource.TAGESEMPFEHLUNG, target.source)
        assertNull(route.note)
    }

    // -----------------------------------------------------------------------
    // M4 — fuer das Zielevent gibt es keine Runde vor der Haustuer
    // -----------------------------------------------------------------------

    @Test
    fun `am Zieltag wird keine Runde angeboten`() {
        val event = TrainingSession(
            day = "Sa",
            title = "Zielevent: Gravel Grinder",
            description = "Dein Zielevent über 200 km.",
            targetKm = 200,
            intensity = SessionIntensity.HART,
            isEvent = true,
        )
        // Selbst an einem Tag mit bester Bereitschaft.
        val route = decide(DailyRecommendationKind.HARTE_EINHEIT, event)

        assertNull(route.target)
        assertFalse(canGenerateRouteFor(event))
        val note = assertNotNull(route.note)
        assertTrue(note.contains("Zielevent"), note)
        assertTrue(note.contains("200 km"), note)
    }

    @Test
    fun `jede andere Einheit darf eine Runde bekommen`() {
        assertTrue(canGenerateRouteFor(longTour()))
    }
}
