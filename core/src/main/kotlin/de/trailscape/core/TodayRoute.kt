package de.trailscape.core

import kotlin.math.max

/**
 * Die Entscheidung „was fahre ich heute — und wie weit?".
 *
 * ## Warum das hier liegt und nicht in der Oberflaeche
 * Bis hierher stand sie als `when`-Block mitten in `ui/today/TodayScreen.kt`:
 *
 * ```
 * fromRecommendation == null -> null        // nur Ruhetag
 * todaySession == null       -> fromRecommendation
 * else                       -> routeTargetForSession(...)   // Plan gewinnt IMMER
 * ```
 *
 * Zwei Dinge waren daran falsch. Erstens war sie in Compose-Code und damit
 * praktisch unpruefbar — die zentrale Verkettung der App hatte keinen einzigen
 * Test. Zweitens wirkte die Bereitschaft **binaer**: entweder Ruhetag oder
 * volle Plandistanz. Bei einer Erholung von 42 stand auf ein und derselben
 * Karte „Locker in Z2, 60–90 min" und darunter „Heute im Plan · Lange Tour ·
 * 90 km" — und der Knopf erzeugte 90 km bergig. Die Tagesform steuerte gar
 * nichts; sie war eine Verdrahtung, keine Steuerung.
 *
 * [decideTodayRoute] ist jetzt die eine Stelle, an der Plan und Tagesform
 * zusammenkommen, und sie liefert **beides**: das Routenziel und den Satz, der
 * die Abweichung erklaert. Ein Text, der die Zahl nicht erklaert, waere
 * derselbe Widerspruch von der anderen Seite.
 */

/**
 * Der Faktor, mit dem die Tagesform auf die geplante Distanz wirkt.
 *
 * ## Warum die Empfehlungsart und nicht das Readiness-Band
 * Naheliegend waere [ReadinessBand]. Es ist aber nur gesetzt, wenn ueberhaupt
 * ein Gesamtscore berechenbar ist (drei Gates: Ruhepuls, Schlaf, Historie) —
 * ohne Uhr gaebe es dann gar keine Steuerung. [DailyRecommendationKind] ist die
 * Groesse, die `recommendToday` in **jedem** Fall liefert: mit Score aus dem
 * Score, ohne Score aus den vorhandenen Einzelampeln. Die Zuordnung ist
 * deckungsgleich mit den Baendern — Score < 40 → Ruhetag, < 60 → locker in Z2,
 * ≥ 80 mit passender Form → harte Einheit, sonst Grundlage.
 *
 * ## Warum diese Zahlen
 *  * **Ruhetag → 0.** Kein Routenangebot. Eine Route an einem Tag, an dem die
 *    App zur Pause raet, ist kein Angebot, sondern ein Widerspruch.
 *  * **Regeneration / locker Z2 → 0,6.** Die Empfehlungstexte nennen selbst
 *    „kurz und locker" bzw. „60–90 min"; 60 % einer typischen Planeinheit
 *    landet genau in diesem Bereich.
 *  * **Grundlage → 0,9.** Der Plan wurde vor Wochen geschrieben und kennt den
 *    heutigen Tag nicht. Zehn Prozent Abzug sind kein Eingriff in den Aufbau,
 *    sondern der Sicherheitsabstand, den ein „normal, aber nicht bestens
 *    erholt" verdient. Die Karte sagt es trotzdem an — eine stille Kuerzung
 *    waere derselbe Fehler in klein.
 *  * **Harte Einheit → 1,0.** Erholung und Form passen; der Plan gilt
 *    unveraendert.
 */
fun readinessDistanceFactor(kind: DailyRecommendationKind): Double = when (kind) {
    DailyRecommendationKind.RUHETAG -> 0.0
    DailyRecommendationKind.RECOVERY -> 0.6
    DailyRecommendationKind.LOCKER_Z2 -> 0.6
    DailyRecommendationKind.GRUNDLAGE -> 0.9
    DailyRecommendationKind.HARTE_EINHEIT -> 1.0
}

/**
 * Die **hoechste** Hoehenpraeferenz, die heute noch vertretbar ist.
 *
 * Die Distanz allein genuegt nicht: 55 km bergig sind fuer einen erschoepften
 * Koerper haerter als 90 km flach. Hoehenmeter bringen die Intensitaet ueber die
 * Topografie herein — dasselbe Argument, mit dem
 * [ascentPreferenceForSession] Grundlagen- und Regenerationseinheiten
 * grundsaetzlich flach haelt.
 *
 * Deshalb an einem lockeren Tag **flach erzwingen**, an einem
 * Grundlagentag hoechstens **wellig** (eine bergige 80-km-Tour an einem
 * mittelmaessigen Tag ist der klassische Griff daneben), und nur an einem
 * wirklich guten Tag alles erlauben.
 */
fun readinessAscentCap(kind: DailyRecommendationKind): AscentPreference = when (kind) {
    DailyRecommendationKind.RUHETAG,
    DailyRecommendationKind.RECOVERY,
    DailyRecommendationKind.LOCKER_Z2,
    -> AscentPreference.FLACH

    DailyRecommendationKind.GRUNDLAGE -> AscentPreference.MODERAT
    DailyRecommendationKind.HARTE_EINHEIT -> AscentPreference.BERGIG
}

/** Warum heute heruntergestuft wird — der „weil …"-Teil des Kartentexts. */
private fun downgradeReason(kind: DailyRecommendationKind): String = when (kind) {
    DailyRecommendationKind.RUHETAG -> "deine Erholungssignale für eine Pause sprechen"
    DailyRecommendationKind.RECOVERY -> "deine Ermüdung gerade hoch ist"
    DailyRecommendationKind.LOCKER_Z2 ->
        "deine Erholungswerte heute nur für eine lockere Einheit reichen"

    DailyRecommendationKind.GRUNDLAGE ->
        "deine Erholungswerte für normales, aber nicht für volles Training sprechen"

    DailyRecommendationKind.HARTE_EINHEIT -> "deine Erholung passt"
}

/**
 * Das Ergebnis der Tagesentscheidung.
 *
 * @param target das Routenziel fuer den „Passende Runde planen"-Knopf, oder
 *   `null`, wenn heute keine Runde angeboten werden soll (Ruhetag, Zieltag).
 * @param session die heute geplante Einheit, oder `null` (Ruhetag im Plan bzw.
 *   gar kein Plan).
 * @param plannedKm was der Plan fuer heute vorsah — `null` ohne Planeinheit.
 * @param factor der angewandte [readinessDistanceFactor].
 * @param downgraded `true`, wenn Distanz oder Hoehenprofil gegenueber dem Plan
 *   zurueckgenommen wurden. Genau dann **muss** [note] auf der Karte stehen.
 * @param note der fertige deutsche Satz zur Abweichung; `null`, wenn es keine
 *   gibt.
 */
data class TodayRoute(
    val target: RouteTarget?,
    val session: TrainingSession?,
    val plannedKm: Int?,
    val factor: Double,
    val downgraded: Boolean,
    val note: String?,
)

/**
 * Verbindet Tagesform und Trainingsplan zu genau einem Routenziel.
 *
 * ## Die Reihenfolge der Faelle
 *  1. **Zieltag.** Steht heute das Zielevent an, gibt es kein Routenangebot:
 *     Das Event hat eine eigene Strecke (siehe [canGenerateRouteFor]). Eine
 *     200-km-Schleife vor der Haustuer dafuer zu bauen war nie das, was jemand
 *     wollte.
 *  2. **Ruhetag.** [routeTargetForToday] liefert dann kein Ziel, und daran
 *     aendert auch eine Planeinheit nichts — der Plan kennt den heutigen Tag
 *     nicht, die Erholungssignale schon. Steht etwas im Plan, sagt [note], was
 *     damit passiert.
 *  3. **Keine Planeinheit.** Dann ist die Tagesempfehlung die ganze Auskunft,
 *     unveraendert wie bisher.
 *  4. **Planeinheit an einem Fahrtag.** Die Kilometer des Plans sind der
 *     Ausgangspunkt, [readinessDistanceFactor] und [readinessAscentCap] die
 *     Korrektur. Auch die **Intensitaet** wird mitgezogen: Wenn die Empfehlung
 *     „keine Intervalle" sagt, darf aus der Planeinheit keine harte werden —
 *     sonst stuenden zwei Ansagen auf einer Karte, die einander widersprechen.
 *     Aus der korrigierten Intensitaet folgt das Planungstempo und damit die
 *     angezeigte Dauer.
 */
fun decideTodayRoute(
    recommendation: DailyRecommendation,
    session: TrainingSession?,
    profile: TrainingProfile,
    recentRides: List<RideInfo>,
    weeklyTarget: WeeklyLoadTarget? = null,
): TodayRoute {
    val kind = recommendation.kind
    val factor = readinessDistanceFactor(kind)

    // 1. Zieltag — die Strecke steht bereits.
    if (session != null && !canGenerateRouteFor(session)) {
        return TodayRoute(
            target = null,
            session = session,
            plannedKm = session.targetKm,
            factor = factor,
            downgraded = false,
            note = "Heute ist dein Zielevent über ${session.targetKm} km. Dafür braucht es " +
                "keine Runde vor der Haustür – die Strecke steht schon.",
        )
    }

    val fromRecommendation = routeTargetForToday(
        recommendation = recommendation,
        profile = profile,
        recentRides = recentRides,
        weeklyTarget = weeklyTarget,
    )

    // 2. Ruhetag — kein Angebot, aber eine Erklaerung, falls der Plan etwas will.
    if (fromRecommendation == null) {
        return TodayRoute(
            target = null,
            session = session,
            plannedKm = session?.targetKm,
            factor = factor,
            downgraded = session != null,
            note = session?.let {
                "Im Plan steht heute „${it.title}“ über ${it.targetKm} km – ausgesetzt, weil " +
                    "${downgradeReason(kind)}. Schieb die Einheit lieber um einen Tag."
            },
        )
    }

    // 3. Kein Plan bzw. Ruhetag im Plan: die Tagesempfehlung ist die Auskunft.
    if (session == null) {
        return TodayRoute(
            target = fromRecommendation,
            session = null,
            plannedKm = null,
            factor = factor,
            downgraded = false,
            note = null,
        )
    }

    // 4. Planeinheit, gedaempft durch die Tagesform.
    val planned = routeTargetForSession(session, profile, recentRides)
    val cappedAscent = minOf(planned.ascentPreference, readinessAscentCap(kind))
    val cappedIntensity = minOf(planned.intensity, intensityForRecommendation(kind))
    val speed = planningSpeedKmh(cappedIntensity, profile, recentRides)
    val distanceKm = max(dartRound1(planned.distanceKm * factor), 1.0)

    val distanceChanged = dartRound(distanceKm).toInt() != dartRound(planned.distanceKm).toInt()
    val ascentChanged = cappedAscent != planned.ascentPreference
    val downgraded = distanceChanged || ascentChanged

    return TodayRoute(
        target = RouteTarget(
            distanceKm = distanceKm,
            ascentPreference = cappedAscent,
            durationH = distanceKm / speed,
            speedKmh = speed,
            intensity = cappedIntensity,
            label = session.title,
            source = RouteTargetSource.PLAN,
        ),
        session = session,
        plannedKm = session.targetKm,
        factor = factor,
        downgraded = downgraded,
        note = if (downgraded) {
            downgradeNote(
                plannedKm = session.targetKm,
                plannedAscent = planned.ascentPreference,
                adjustedKm = dartRound(distanceKm).toInt(),
                adjustedAscent = cappedAscent,
                ascentChanged = ascentChanged,
                distanceChanged = distanceChanged,
                reason = downgradeReason(kind),
            )
        } else {
            null
        },
    )
}

/**
 * „Plan: 90 km bergig — heute auf 55 km flach reduziert, weil …"
 *
 * Der Satz nennt **beide** Zahlen. Nur die neue zu zeigen waere bequemer und
 * genau das Problem: Die Nutzerin sieht im Trainings-Tab weiterhin 90 km und
 * muss verstehen, warum hier 55 stehen.
 */
private fun downgradeNote(
    plannedKm: Int,
    plannedAscent: AscentPreference,
    adjustedKm: Int,
    adjustedAscent: AscentPreference,
    ascentChanged: Boolean,
    distanceChanged: Boolean,
    reason: String,
): String {
    val plannedLabel = ascentPreferenceLabels.getValue(plannedAscent).lowercase()
    val adjustedLabel = ascentPreferenceLabels.getValue(adjustedAscent).lowercase()
    val head = "Plan: $plannedKm km $plannedLabel"
    val change = when {
        distanceChanged && ascentChanged -> "heute auf $adjustedKm km $adjustedLabel reduziert"
        distanceChanged -> "heute auf $adjustedKm km reduziert"
        else -> "heute $adjustedLabel statt $plannedLabel"
    }
    return "$head – $change, weil $reason."
}
