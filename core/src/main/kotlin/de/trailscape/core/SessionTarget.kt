package de.trailscape.core

import kotlin.math.max
import kotlin.math.pow

/**
 * Von der Trainingsempfehlung zum Routenziel.
 *
 * Bindeglied zwischen der Trainingsintelligenz (`Training.kt` fuer geplante
 * Einheiten, `Readiness.kt` fuer die Tagesempfehlung) und der
 * Rundkurs-Generierung (`RouteGenerator.kt`): beide Seiten kennen einander
 * nicht, die gemeinsame Waehrung ist [RouteTarget].
 *
 * **Was das reale Modell hergibt.** [TrainingSession] hat *keine* Dauer- und
 * kein Intensitaetsfeld — es traegt `day`, `title`, `description` und
 * `targetKm` (siehe `Models.kt`). Die Kilometer der Einheit sind damit die
 * *autoritative* Groesse und werden 1:1 zur Zieldistanz; abgeleitet wird
 * stattdessen die **Dauer** (`km / Geschwindigkeit`), weil das UI sie anzeigen
 * will und weil die Tagesempfehlung genau andersherum funktioniert. Die
 * Intensitaet wird aus Titel und Beschreibungstext klassifiziert — den
 * Wortlaut erzeugt `Training.kt` deterministisch, die Schluesselwoerter unten
 * stammen also nicht aus freiem Nutzertext, sondern aus generierten Bausteinen
 * (siehe [classifySessionIntensity]).
 *
 * [DailyRecommendation] aus `Readiness.kt` traegt umgekehrt *keine*
 * Kilometer, sondern nur eine Art ([DailyRecommendationKind]) plus Fliesstext
 * ("Locker in Z2, 60–90 min"). Dort ist die Dauer autoritativ und die Distanz
 * wird ueber die Geschwindigkeit abgeleitet.
 */

/** Gewuenschtes Hoehenprofil eines generierten Rundkurses. */
enum class AscentPreference { FLACH, MODERAT, BERGIG }

/** Deutsche Labels fuer [AscentPreference] (Reihenfolge bedeutsam fuer Dropdowns). */
val ascentPreferenceLabels: Map<AscentPreference, String> = linkedMapOf(
    AscentPreference.FLACH to "Flach",
    AscentPreference.MODERAT to "Wellig",
    AscentPreference.BERGIG to "Bergig",
)

/**
 * Grobe Intensitaetsstufe einer Einheit.
 *
 * Bewusst nur drei Stufen: feiner laesst sich aus Titel/Beschreibung bzw. aus
 * [DailyRecommendationKind] nichts belastbar ableiten.
 */
enum class SessionIntensity { LOCKER, GRUNDLAGE, HART }

/** Deutsche Labels fuer [SessionIntensity]. */
val sessionIntensityLabels: Map<SessionIntensity, String> = linkedMapOf(
    SessionIntensity.LOCKER to "locker",
    SessionIntensity.GRUNDLAGE to "Grundlage",
    SessionIntensity.HART to "intensiv",
)

/** Woher ein [RouteTarget] stammt — fuer die Beschriftung im UI. */
enum class RouteTargetSource {
    /** Aus einer Einheit des Trainingsplans ([routeTargetForSession]). */
    PLAN,

    /** Aus der Tagesempfehlung ohne Plan ([routeTargetForToday]). */
    TAGESEMPFEHLUNG,
}

/**
 * Was der Rundkurs treffen soll.
 *
 * [distanceKm] ist die einzige harte Vorgabe; [ascentPreference] steuert die
 * Bewertung der Kandidaten, [durationH] und [speedKmh] sind erklaerende
 * Groessen fuer das UI ("≈ 2:15 h bei 20 km/h").
 */
data class RouteTarget(
    /** Zieldistanz des Rundkurses in km. */
    val distanceKm: Double,
    val ascentPreference: AscentPreference,
    /** Erwartete Fahrzeit in Stunden; `null`, wenn keine sinnvolle Schaetzung moeglich ist. */
    val durationH: Double?,
    /** Der Ableitung zugrunde liegende Geschwindigkeit in km/h (bereits intensitaetsmoduliert). */
    val speedKmh: Double,
    val intensity: SessionIntensity,
    /** Kurzbeschriftung der Einheit, z. B. "Lange Tour" oder "Grundlageneinheit". */
    val label: String,
    val source: RouteTargetSource,
)

// ---------------------------------------------------------------------------
// Geschwindigkeit
// ---------------------------------------------------------------------------

/**
 * Fallback-Schnitt fuer eine Gravel-Runde, wenn die Historie nichts hergibt.
 *
 * 18 km/h ist der uebliche Bereich fuer gemischtes Gravel-Terrain mit
 * Schotteranteil (Strasse waere deutlich schneller, reiner Trail langsamer).
 */
const val fallbackGravelSpeedKmh: Double = 18.0

/** Wie viele der juengsten Touren die Geschwindigkeitsschaetzung heranzieht. */
const val speedHistoryRides: Int = 10

/** Untere Plausibilitaetsgrenze fuer einen Tourenschnitt in km/h. */
const val minPlausibleAvgSpeedKmh: Double = 5.0

/** Obere Plausibilitaetsgrenze fuer einen Tourenschnitt in km/h. */
const val maxPlausibleAvgSpeedKmh: Double = 45.0

/** Kurze Touren tragen zu viel Rauschen (Rangieren, Ampeln) — erst ab hier zaehlt eine Tour. */
const val minSpeedSampleKm: Double = 3.0

/**
 * Median des Tourenschnitts der letzten [rideCount] Touren mit brauchbarem
 * `avgSpeedKmh`; `null`, wenn keine einzige Tour verwertbar ist.
 *
 * "Brauchbar" heisst: `avgSpeedKmh` gesetzt, endlich, zwischen
 * [minPlausibleAvgSpeedKmh] und [maxPlausibleAvgSpeedKmh] und die Tour
 * mindestens [minSpeedSampleKm] lang. Median statt Mittelwert, weil eine
 * einzelne Renn- oder Schiebe-Tour den Schnitt sonst verzerrt.
 */
fun typicalAvgSpeedKmh(recentRides: List<Ride>, rideCount: Int = speedHistoryRides): Double? {
    if (rideCount <= 0) {
        return null
    }
    val usable = recentRides
        .sortedByDescending { it.createdAt }
        .asSequence()
        .mapNotNull { ride ->
            val speed = ride.stats.avgSpeedKmh
            when {
                speed == null || !speed.isFinite() -> null
                speed < minPlausibleAvgSpeedKmh || speed > maxPlausibleAvgSpeedKmh -> null
                ride.stats.distanceKm < minSpeedSampleKm -> null
                else -> speed
            }
        }
        .take(rideCount)
        .toList()

    return median(usable)
}

/**
 * Fallback-Geschwindigkeit aus dem Profil, wenn die Historie leer ist.
 *
 * Basis ist [fallbackGravelSpeedKmh]. Wer eine gemessene FTP hinterlegt hat,
 * bekommt sie skaliert: In der Ebene geht der Grossteil der Leistung gegen den
 * Luftwiderstand, dort gilt `P ∝ v³` (siehe [estimateSamplePowerW]) — die
 * Geschwindigkeit skaliert also mit der **dritten Wurzel** des Verhaeltnisses
 * von relativer Leistung zum Referenzwert [defaultEftpWPerKg]. Ohne
 * FTP-Override ist das Verhaeltnis exakt 1 und es bleibt bei 18 km/h.
 *
 * Geklemmt auf 12…32 km/h, damit ein extremer Profilwert die Routenlaenge
 * nicht ins Absurde zieht.
 */
fun fallbackSpeedKmh(profile: TrainingProfile): Double {
    val weight = max(profile.weightKg, 1.0)
    val ratio = (profile.eftpW / weight) / defaultEftpWPerKg
    if (!ratio.isFinite() || ratio <= 0) {
        return fallbackGravelSpeedKmh
    }
    return clamp(fallbackGravelSpeedKmh * ratio.pow(1.0 / 3.0), 12.0, 32.0)
}

/**
 * Geschwindigkeitsfaktor je Intensitaet.
 *
 * Verankert ist die **harte** Einheit bei 1,0: Der Median der Historie ist der
 * Schnitt ueber alle Touren, und die schnellsten davon sind genau die
 * intensiven. Grundlage liegt knapp darunter, Regeneration deutlich — die
 * Spanne von 10 % entspricht grob dem Unterschied zwischen Z1/Z2-Rollen und
 * einer Einheit mit Schwellenanteilen auf demselben Terrain.
 */
fun intensitySpeedFactor(intensity: SessionIntensity): Double = when (intensity) {
    SessionIntensity.LOCKER -> 0.90
    SessionIntensity.GRUNDLAGE -> 0.95
    SessionIntensity.HART -> 1.00
}

/**
 * Effektive Planungsgeschwindigkeit: Historie (oder Profil-Fallback), moduliert
 * mit [intensitySpeedFactor].
 */
fun planningSpeedKmh(
    intensity: SessionIntensity,
    profile: TrainingProfile,
    recentRides: List<Ride>,
): Double {
    val base = typicalAvgSpeedKmh(recentRides) ?: fallbackSpeedKmh(profile)
    return max(base * intensitySpeedFactor(intensity), 1.0)
}

// ---------------------------------------------------------------------------
// Klassifikation geplanter Einheiten
// ---------------------------------------------------------------------------

/** Schluesselwoerter fuer lockere Einheiten (Titel-Bausteine aus `Training.kt`). */
private val lockerKeywords = listOf("locker", "ruhig", "regeneration", "kompensator", "erholung")

/** Schluesselwoerter fuer intensive Einheiten. */
private val hartKeywords = listOf("intervall", "zielevent")

/** Schluesselwoerter, die im Beschreibungstext auf bewusst gesuchte Anstiege deuten. */
private val climbKeywords = listOf("anstieg", "höhenmeter", "hoehenmeter")

/**
 * Klassifiziert eine geplante Einheit in [SessionIntensity].
 *
 * Grundlage sind die Titel, die `Training.kt` deterministisch erzeugt:
 * "Lockere Ausfahrt", "Ruhige Runde", "Regeneration locker", "Locker mit
 * Antritten", "Aktivierung locker" (→ [SessionIntensity.LOCKER]),
 * "Intervalle" und "Zielevent: …" (→ [SessionIntensity.HART]), "GA1",
 * "GA1 kompensatorisch" und "Lange Tour" (→ [SessionIntensity.GRUNDLAGE],
 * bzw. LOCKER wo "kompensatorisch"/"locker" im Titel steht).
 *
 * Die Reihenfolge der Pruefungen ist bedeutsam: "Locker mit Antritten" traegt
 * Antritte, ist aber eine Taper-Lockereinheit — deshalb gewinnt "locker" vor
 * allem anderen. Unbekannte Titel landen auf [SessionIntensity.GRUNDLAGE],
 * dem mit Abstand haeufigsten und im Zweifel unschaedlichsten Fall.
 */
fun classifySessionIntensity(session: TrainingSession): SessionIntensity {
    val title = session.title.lowercase()
    if (lockerKeywords.any { title.contains(it) }) {
        return SessionIntensity.LOCKER
    }
    if (hartKeywords.any { title.contains(it) }) {
        return SessionIntensity.HART
    }
    return SessionIntensity.GRUNDLAGE
}

/**
 * Leitet die Hoehenpraeferenz aus der Einheit ab.
 *
 * 1. **Beschreibung schlaegt Titel.** Erwaehnt der Text bewusst Anstiege oder
 *    Hoehenmeter, ist das ein direkter Auftrag: `Training.kt` haengt genau
 *    dann den Klettersatz an ("Baue dabei bewusst Anstiege ein …"), wenn das
 *    Ziel ≥ 1000 Hm hat — und die Zielevent-Beschreibung nennt die Anstiege
 *    ebenfalls. → [AscentPreference.BERGIG]
 * 2. Intensive Einheiten (Intervalle, Zielevent ohne Hoehenbezug) vertragen
 *    welliges Terrain, brauchen aber lange gleichmaessige Abschnitte für die
 *    Intervalle. → [AscentPreference.MODERAT]
 * 3. Alles andere — Grundlage und Regeneration — soll flach bleiben, damit die
 *    Intensitaet nicht ueber die Topografie hereinkommt. → [AscentPreference.FLACH]
 */
fun ascentPreferenceForSession(session: TrainingSession): AscentPreference {
    val text = session.description.lowercase()
    if (climbKeywords.any { text.contains(it) }) {
        return AscentPreference.BERGIG
    }
    return when (classifySessionIntensity(session)) {
        SessionIntensity.HART -> AscentPreference.MODERAT
        SessionIntensity.GRUNDLAGE, SessionIntensity.LOCKER -> AscentPreference.FLACH
    }
}

/**
 * Routenziel fuer eine geplante Einheit.
 *
 * [TrainingSession.targetKm] ist gesetzt und autoritativ — der Plan rechnet in
 * Kilometern, also ist die Zieldistanz genau dieser Wert. Abgeleitet wird die
 * **Dauer**: `km / (Historien-Median × Intensitaetsfaktor)`, siehe
 * [planningSpeedKmh]. [profile] dient dabei nur als Fallback-Quelle, wenn
 * [recentRides] keine brauchbaren Schnitte enthaelt.
 */
fun routeTargetForSession(
    session: TrainingSession,
    profile: TrainingProfile,
    recentRides: List<Ride>,
): RouteTarget {
    val intensity = classifySessionIntensity(session)
    val speed = planningSpeedKmh(intensity, profile, recentRides)
    val distanceKm = max(session.targetKm.toDouble(), 1.0)

    return RouteTarget(
        distanceKm = distanceKm,
        ascentPreference = ascentPreferenceForSession(session),
        durationH = distanceKm / speed,
        speedKmh = speed,
        intensity = intensity,
        label = session.title,
        source = RouteTargetSource.PLAN,
    )
}

// ---------------------------------------------------------------------------
// Tagesempfehlung ohne Plan
// ---------------------------------------------------------------------------

/**
 * Richtdauer je [DailyRecommendationKind] in Stunden.
 *
 * Die Werte stehen so (oder als Spanne) in den Empfehlungstexten von
 * `Readiness.kt` bzw. folgen unmittelbar daraus:
 *
 *  * [DailyRecommendationKind.RECOVERY] — "kurz und locker fahren" → 1,0 h
 *  * [DailyRecommendationKind.LOCKER_Z2] — Titel nennt "60–90 min" → 1,25 h (Mitte)
 *  * [DailyRecommendationKind.HARTE_EINHEIT] — Z4/Z5 mit Ein-/Ausfahren → 1,5 h
 *  * [DailyRecommendationKind.GRUNDLAGE] — "nach dem Restbudget deiner Woche"
 *    → 2,0 h, sofern kein Wochenziel bekannt ist (siehe [routeTargetForToday])
 *  * [DailyRecommendationKind.RUHETAG] — keine Ausfahrt, kein Routenziel
 */
fun baseDurationForRecommendation(kind: DailyRecommendationKind): Double? = when (kind) {
    DailyRecommendationKind.RUHETAG -> null
    DailyRecommendationKind.RECOVERY -> 1.0
    DailyRecommendationKind.LOCKER_Z2 -> 1.25
    DailyRecommendationKind.HARTE_EINHEIT -> 1.5
    DailyRecommendationKind.GRUNDLAGE -> 2.0
}

/** Intensitaetsstufe der Tagesempfehlung. */
fun intensityForRecommendation(kind: DailyRecommendationKind): SessionIntensity = when (kind) {
    DailyRecommendationKind.RUHETAG,
    DailyRecommendationKind.RECOVERY,
    DailyRecommendationKind.LOCKER_Z2,
    -> SessionIntensity.LOCKER

    DailyRecommendationKind.GRUNDLAGE -> SessionIntensity.GRUNDLAGE
    DailyRecommendationKind.HARTE_EINHEIT -> SessionIntensity.HART
}

/**
 * Hoehenpraeferenz der Tagesempfehlung.
 *
 * Ohne Plan gibt es kein Ziel mit Hoehenmetern, aus dem sich ein Kletterauftrag
 * ableiten liesse — [AscentPreference.BERGIG] entsteht deshalb ausschliesslich
 * ueber [ascentPreferenceForSession]. Locker und Grundlage sollen flach
 * bleiben, eine harte Einheit vertraegt Wellen.
 */
fun ascentPreferenceForRecommendation(kind: DailyRecommendationKind): AscentPreference =
    when (intensityForRecommendation(kind)) {
        SessionIntensity.HART -> AscentPreference.MODERAT
        SessionIntensity.GRUNDLAGE, SessionIntensity.LOCKER -> AscentPreference.FLACH
    }

/** Anteil des Wochenziels, den eine einzelne Grundlageneinheit abdecken soll. */
private const val GRUNDLAGE_WEEK_SHARE = 1.0 / 3

/** Anteil des Wochen-Zeitbudgets, den eine einzelne Ausfahrt hoechstens belegen darf. */
private const val MAX_WEEK_HOURS_SHARE = 0.5

/**
 * Routenziel fuer die heutige Empfehlung ausserhalb eines Plans.
 *
 * Eingang ist genau die bestehende API aus `Readiness.kt`: die
 * [DailyRecommendation] aus [recommendToday] und — optional — das Wochenziel
 * aus [weeklyLoadTarget], wie es `TrainingInsights` ohnehin schon berechnet.
 * Hier ist die **Dauer** autoritativ (die Empfehlung nennt Zeit, keine
 * Kilometer) und die Distanz wird abgeleitet:
 * `Dauer × Historien-Median × Intensitaetsfaktor`.
 *
 * Zwei Korrekturen auf die Richtdauer aus [baseDurationForRecommendation]:
 *
 *  * Bei [DailyRecommendationKind.GRUNDLAGE] sagt die Empfehlung woertlich
 *    "fahre nach dem Restbudget deiner Woche". Liegt [weeklyTarget] vor, wird
 *    daraus [WeeklyLoadTarget.estimatedHours] geholt und gedrittelt (drei
 *    Einheiten pro Woche sind das uebliche Raster der Plaene aus
 *    `Training.kt`), begrenzt auf 1…5 h.
 *  * [TrainingProfile.weeklyHours] deckelt jede Ausfahrt auf die Haelfte des
 *    Wochenbudgets — wer 4 h pro Woche hat, bekommt keine 3-h-Runde empfohlen.
 *
 * Liefert `null` bei [DailyRecommendationKind.RUHETAG]: fuer einen Ruhetag ist
 * eine Route das falsche Angebot.
 */
fun routeTargetForToday(
    recommendation: DailyRecommendation,
    profile: TrainingProfile,
    recentRides: List<Ride>,
    weeklyTarget: WeeklyLoadTarget? = null,
): RouteTarget? {
    val kind = recommendation.kind
    val base = baseDurationForRecommendation(kind) ?: return null

    var hours = base
    if (kind == DailyRecommendationKind.GRUNDLAGE && weeklyTarget != null) {
        val fromWeek = weeklyTarget.estimatedHours * GRUNDLAGE_WEEK_SHARE
        if (fromWeek.isFinite() && fromWeek > 0) {
            hours = clamp(fromWeek, 1.0, 5.0)
        }
    }

    val budget = profile.weeklyHours
    if (budget != null && budget > 0) {
        hours = kotlin.math.min(hours, budget * MAX_WEEK_HOURS_SHARE)
    }
    hours = max(hours, 0.25)

    val intensity = intensityForRecommendation(kind)
    val speed = planningSpeedKmh(intensity, profile, recentRides)

    return RouteTarget(
        distanceKm = hours * speed,
        ascentPreference = ascentPreferenceForRecommendation(kind),
        durationH = hours,
        speedKmh = speed,
        intensity = intensity,
        label = recommendation.title,
        source = RouteTargetSource.TAGESEMPFEHLUNG,
    )
}
