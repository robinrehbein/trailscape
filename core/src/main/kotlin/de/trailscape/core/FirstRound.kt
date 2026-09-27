package de.trailscape.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Die erste Runde — das Routenziel fuer jemanden, der noch keine einzige Tour
 * gespeichert hat.
 *
 * ## Warum es das gibt
 * Ohne Historie kann Trainingslast, Form und Tempo nur geraten werden. Bisher
 * lieferte [routeTargetForToday] dann die normale Grundlage-Empfehlung: 2 h,
 * mittleres Tempo, rund 34 km — als Tagesform ausgegeben, obwohl nichts davon
 * gemessen war. Fuer den allerersten Eindruck ist das die falsche Ansage: Wer
 * die App gerade installiert hat, soll in der ersten Minute eine Runde sehen,
 * die sicher fahrbar ist und ehrlich sagt, woher sie kommt.
 *
 * ## Warum kein zweiter Generator
 * Hier entsteht nur ein [RouteTarget]. Gebaut wird die Runde vom bestehenden
 * Rundkurs-Generator, derselbe Weg wie jede andere Tagesrunde. Eine zweite
 * Routenlogik fuer den Erststart waere genau die Stelle, die irgendwann anders
 * rechnet als der Rest.
 */

/**
 * Die Zeitoptionen fuer die erste Runde, so wie die Einfuehrung sie anbietet.
 *
 * Bewusst nur drei Stufen und keine laenger als 2 h: Mehr als zwei Stunden
 * auf einer unbekannten Runde ist fuer einen Einstieg zu viel, weniger als eine
 * Stunde lohnt das Aufsatteln kaum.
 */
enum class FirstRoundDuration(val hours: Double) {
    EINE_STUNDE(1.0),
    ANDERTHALB_STUNDEN(1.5),
    ZWEI_STUNDEN(2.0),
    ;

    /** „1 h", „1½ h", „2 h" — siehe [formatRoundHours]. */
    val label: String get() = formatRoundHours(hours)
}

/**
 * Stunden fuer die Oberflaeche: „1 h", „1½ h", „2 h".
 *
 * Ganze Stunden als „N h", halbe mit dem Bruchzeichen („½ h" bei 0,5), alles
 * andere ueber das allgemeine [formatHours] („2,3 h"). Das Bruchzeichen, weil
 * „1,5 h" nach Messwert aussieht und „1½ h" nach dem, was man sagt.
 */
fun formatRoundHours(hours: Double): String {
    val halves = hours * 2
    if (halves == floor(halves)) {
        val whole = (halves / 2).toInt()
        return when {
            halves.toInt() % 2 == 0 -> "$whole h"
            whole == 0 -> "½ h"
            else -> "$whole½ h"
        }
    }
    return "${formatHours(hours)} h"
}

/** Beschriftung der ersten Runde im Generierungs-Panel („aus: Erste Runde …"). */
const val FIRST_ROUND_LABEL: String = "Erste Runde"

/** Kuerzer wird keine erste Runde — darunter findet der Generator kaum eine Schleife. */
const val FIRST_ROUND_MIN_KM: Double = 10.0

/** Anteil des Wochen-Zeitbudgets, den die erste Runde hoechstens belegen darf. */
private const val FIRST_ROUND_WEEK_HOURS_SHARE = 0.5

/**
 * Die vorausgewaehlte Dauer der ersten Runde.
 *
 * Ohne hinterlegtes Zeitbudget ([TrainingProfile.weeklyHours] fehlt oder ist
 * ≤ 0) sind es 1½ h. Sonst die laengste Option, die in die Haelfte des
 * Wochenbudgets passt — derselbe Deckel wie in [routeTargetForToday] —,
 * hoechstens 1½ h und mindestens 1 h.
 *
 * ## Warum hoechstens 1½ h
 * Die erste Runde soll nicht die laengste Einheit der Woche sein. Wer die App
 * zum ersten Mal oeffnet, soll danach Lust auf die zweite haben, nicht muede
 * auf dem Sofa liegen. 2 h bleibt waehlbar, ist aber nie die Vorauswahl.
 */
fun defaultFirstRoundDuration(profile: TrainingProfile): FirstRoundDuration {
    val budget = profile.weeklyHours
    if (budget == null || !budget.isFinite() || budget <= 0) {
        return FirstRoundDuration.ANDERTHALB_STUNDEN
    }
    val cap = minOf(budget * FIRST_ROUND_WEEK_HOURS_SHARE, FirstRoundDuration.ANDERTHALB_STUNDEN.hours)
    return FirstRoundDuration.entries.lastOrNull { it.hours <= cap } ?: FirstRoundDuration.EINE_STUNDE
}

/**
 * Die Vorauswahl auf der letzten Seite der Einfuehrung: [defaultFirstRoundDuration]
 * fuer jemanden ohne gefahrene Tour, sonst `null` („Später").
 *
 * ## Warum nicht immer eine Dauer
 * Die Einfuehrung ist ueber „Mehr → Über → Einführung erneut ansehen" erneut
 * aufrufbar. Wer dort nur nachlesen will und durchtippt, soll nicht
 * ungefragt in der Karte landen, nach dem Standort gefragt werden und eine
 * Routensuche am Server ausloesen. Mit Historie ist die Runde deshalb eine
 * bewusste Wahl, keine Vorgabe. Gespeicherte Planungen zaehlen nicht als
 * gefahrene Tour ([riddenRides]).
 */
fun onboardingFirstRoundPreselect(
    profile: TrainingProfile,
    rides: List<RideInfo>,
): FirstRoundDuration? =
    if (riddenRides(rides).isEmpty()) defaultFirstRoundDuration(profile) else null

/**
 * Das Routenziel der ersten Runde fuer [hours] Stunden.
 *
 * Die **Dauer** ist maßgeblich, weil die Nutzerin in Zeit denkt („ich hab
 * anderthalb Stunden"); die Distanz ist abgeleitet — genau wie bei
 * [routeTargetForToday]: `Dauer × Grundlagentempo`. Das Tempo kommt aus
 * [planningSpeedKmh]: ohne Historie 18 km/h × 0,95 = 17,1 km/h, mit
 * gefahrenen Touren deren Median. Daraus werden 1 h → 17 km, 1½ h → 26 km,
 * 2 h → 34 km.
 *
 * Immer flach und im Grundlagentempo: Ohne eine einzige gemessene Tour gibt es
 * keinen Grund fuer Hoehenmeter oder Intervalle. Die Distanz wird auf ganze
 * Kilometer gerundet (so steht sie auch ueberall in der Oberflaeche) und nie
 * kuerzer als [FIRST_ROUND_MIN_KM].
 */
fun firstRoundTarget(
    hours: Double,
    profile: TrainingProfile,
    recentRides: List<RideInfo>,
): RouteTarget {
    val speed = planningSpeedKmh(SessionIntensity.GRUNDLAGE, profile, recentRides)
    val distanceKm = max((hours * speed).roundToInt().toDouble(), FIRST_ROUND_MIN_KM)
    return RouteTarget(
        distanceKm = distanceKm,
        ascentPreference = AscentPreference.FLACH,
        durationH = hours,
        speedKmh = speed,
        intensity = SessionIntensity.GRUNDLAGE,
        label = FIRST_ROUND_LABEL,
        source = RouteTargetSource.TAGESEMPFEHLUNG,
    )
}
