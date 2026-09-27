package de.trailscape.app.ui.rides

import androidx.annotation.StringRes
import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.RideInfo
import de.trailscape.core.RideLoad
import de.trailscape.core.RideStats
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.weekSessionProgress

/**
 * # „locker / mittel / hart" statt „TL 68"
 *
 * Die Verlaufsliste zeigte je Tour die Trainingslast als Zahl („TL 68"). Die
 * Zahl ist richtig, aber ohne Erklaerung stumm: Ob 68 viel ist, haengt davon
 * ab, wie lange man dafuer unterwegs war. Das Zieldesign
 * (`docs/design/prototyp-klartext.html`, `NOTES.verlauf`) ersetzt sie durch ein
 * Wort; die Zahl selbst bleibt in der Detailansicht unter „Alle Werte".
 *
 * ## Die Rechnung: Last pro Stunde
 * Die Last lebt auf der einheitlichen 100er-Skala aus `:core`
 * (`TrainingLoad.kt`): **eine Stunde an der Schwelle = 100**. Teilt man sie
 * durch die Stunden, bleibt ein Mass fuer die *Dichte* der Belastung, das von
 * der Tourlaenge unabhaengig ist — rechnerisch das Quadrat des
 * Intensitaetsfaktors mal 100 (IF 0,75 ≙ 56 pro Stunde, IF 0,87 ≙ 75).
 *
 * | Last pro Stunde | Wort   | entspricht etwa                          |
 * |-----------------|--------|------------------------------------------|
 * | unter 55        | locker | IF < 0,74 — Grundlage, Kaffeefahrt       |
 * | 55 bis unter 75 | mittel | IF 0,74–0,87 — Tempo, zuegige Gruppe     |
 * | ab 75           | hart   | IF ≥ 0,87 — Schwelle, Intervalle, Rennen |
 *
 * Die Grenzen folgen den ueblichen Leistungszonen (Grundlage endet bei etwa
 * 75 % der Schwelle, der Schwellenbereich beginnt bei etwa 88 %). Die grobe
 * Heuristik aus `:core` (55 pro Stunde mal Faktor 0,7–1,5) landet fuer eine
 * gemuetliche Flachtour so bei „locker" und fuer eine steile, schnelle bei
 * „hart" — die Woerter bleiben also auch ohne Puls ehrlich.
 *
 * Als Dauer zaehlt die **Fahrzeit**, nicht die Gesamtdauer: Eine Stunde
 * Biergarten mitten in der Tour macht sie nicht lockerer. Fehlt die Fahrzeit,
 * springt die Gesamtdauer ein.
 */
internal enum class RideEffort(@StringRes val labelRes: Int) {
    LOCKER(R.string.rides_effort_easy_label),
    MITTEL(R.string.rides_effort_moderate_label),
    HART(R.string.rides_effort_hard_label),
}

/** Ab dieser Last pro Stunde gilt eine Tour als „mittel" (siehe Tabelle oben). */
internal const val EffortModerateLoadPerHour: Double = 55.0

/** Ab dieser Last pro Stunde gilt eine Tour als „hart" (siehe Tabelle oben). */
internal const val EffortHardLoadPerHour: Double = 75.0

/**
 * Kuerzer als eine Minute ergibt keine sinnvolle Dichte — die Last einer
 * Handvoll Punkte, hochgerechnet auf eine Stunde, waere Zufall.
 */
private const val MinEffortDurationS: Int = 60

/**
 * Das Wort fuer eine Tour aus ihrer Last und ihrer Dauer; `null`, wenn eines
 * von beiden fehlt oder nicht positiv ist — dann zeigt die Liste gar keine
 * Pille statt einer geratenen.
 */
internal fun rideEffort(load: Double?, durationS: Int?): RideEffort? {
    if (load == null || load <= 0.0) return null
    if (durationS == null || durationS < MinEffortDurationS) return null
    val perHour = load / (durationS / 3600.0)
    return when {
        perHour >= EffortHardLoadPerHour -> RideEffort.HART
        perHour >= EffortModerateLoadPerHour -> RideEffort.MITTEL
        else -> RideEffort.LOCKER
    }
}

/**
 * Bequeme Form fuer die Oberflaeche: nimmt die [RideLoad] aus der
 * Trainingsauswertung (nur, wenn sie [RideLoad.available] ist) und die
 * Fahrzeit (ersatzweise Gesamtdauer) aus [stats].
 */
internal fun rideEffort(load: RideLoad?, stats: RideStats): RideEffort? =
    rideEffort(load?.takeIf { it.available }?.load, stats.movingTimeS ?: stats.durationS)

// ---------------------------------------------------------------- Plan

/** Die Planeinheit, die eine Tour erledigt (oder teilweise erledigt) hat. */
internal data class RidePlanMatch(
    val session: TrainingSession,
    val status: PlanSessionStatus,
)

private const val DayMs: Long = 24L * 60 * 60 * 1000

/**
 * Sucht die Planeinheit, der `:core` diese Tour zuordnet — ueber dieselbe
 * Zuordnung, mit der die Planwoche im Trainings-Tab ihre Haken setzt
 * ([weekSessionProgress]). Hier wird also nichts neu entschieden: „Passt zum
 * Plan" in der Detailansicht und „Erledigt" im Plan sagen immer dasselbe.
 *
 * Geprueft werden nur Wochen, in die die Tour (samt der einen Tag Toleranz
 * der Zuordnung) faellt. Nur ERLEDIGT und TEILWEISE zaehlen; eine Tour ohne
 * Einheit liefert `null`.
 *
 * @param rideLoads Last je Tour-ID, wie sie auch die Planwoche bekommt.
 */
internal fun planMatchForRide(
    plan: TrainingPlan?,
    rides: List<RideInfo>,
    rideId: String,
    rideLoads: Map<String, Double>,
    now: Long,
): RidePlanMatch? {
    if (plan == null) return null
    val ride = rides.firstOrNull { it.id == rideId } ?: return null
    val at = ride.createdAt
    for (week in plan.weeks) {
        if (at < week.start - DayMs || at >= week.end + DayMs) continue
        val hit = weekSessionProgress(week, rides, now = now, rideLoads = rideLoads)
            .firstOrNull { it.rideId == rideId }
            ?: continue
        if (hit.status == PlanSessionStatus.ERLEDIGT || hit.status == PlanSessionStatus.TEILWEISE) {
            return RidePlanMatch(hit.session, hit.status)
        }
    }
    return null
}

// ---------------------------------------------------------------- Satz

/**
 * Der getoente Klartext-Hinweis der Detailansicht: fetter Anfang, dann ein Satz.
 * Als [UiText], damit der Satz in der Sprache der Oberflaeche entsteht und
 * trotzdem ohne Robolectric pruefbar bleibt (`RideEffortTest`).
 */
internal data class RideNote(val headline: UiText, val body: UiText)

/** Unterhalb dieser Pe:Hr-Entkopplung (in %) blieb der Puls „bis zum Schluss ruhig". */
private const val CalmDecouplingPercent: Double = 5.0

/** Wie eine Einheit im Satz heisst — „Lockere Einheit erledigt". */
private fun unitName(intensity: SessionIntensity): UiText = UiText.Res(
    when (intensity) {
        SessionIntensity.LOCKER -> R.string.rides_note_unit_easy
        SessionIntensity.GRUNDLAGE -> R.string.rides_note_unit_endurance
        SessionIntensity.HART -> R.string.rides_note_unit_hard
    },
)

/** Welche Woerter zu einer geplanten Intensitaet passen. */
private fun expectedEfforts(intensity: SessionIntensity): Set<RideEffort> = when (intensity) {
    SessionIntensity.LOCKER -> setOf(RideEffort.LOCKER)
    SessionIntensity.GRUNDLAGE -> setOf(RideEffort.LOCKER, RideEffort.MITTEL)
    SessionIntensity.HART -> setOf(RideEffort.MITTEL, RideEffort.HART)
}

/**
 * Baut den einen Satz, der unter den Kennzahlen steht (Zieldesign `.note`:
 * „**Passt zum Plan.** Lockere Einheit erledigt, dein Puls blieb bis zum
 * Schluss ruhig.").
 *
 * Vorrang hat der Plan: Hat die Tour eine Einheit erledigt, ist das die
 * Nachricht. Wich sie dabei in der Haerte ab, sagt der Satz das, statt
 * stumm zu loben; blieb sie im Rahmen und ist der Puls stabil geblieben
 * ([decouplingPercent] unter 5 %), sagt er das. Ohne Plantreffer ordnet der
 * Satz nur die Haerte ein. Ohne beides gibt es keinen Satz (`null`) — lieber
 * keiner als ein leerer.
 */
internal fun rideNote(
    effort: RideEffort?,
    match: RidePlanMatch?,
    decouplingPercent: Double?,
): RideNote? {
    if (match != null) {
        val intensity = match.session.intensity
        val unit = unitName(intensity)
        if (match.status == PlanSessionStatus.TEILWEISE) {
            return RideNote(
                headline = UiText.Res(R.string.rides_note_partial_title),
                body = UiText.Res(R.string.rides_note_partial_body, listOf(unit, match.session.targetKm)),
            )
        }
        val expected = expectedEfforts(intensity)
        // Je Nachsatz ein ganzer Satz als Ressource — Satzteile aneinander-
        // zuhaengen hiesse, die Wortstellung der Uebersetzung festzulegen.
        val body = when {
            effort != null && effort.ordinal > expected.maxOf { it.ordinal } ->
                R.string.rides_note_plan_done_harder
            effort != null && effort.ordinal < expected.minOf { it.ordinal } ->
                R.string.rides_note_plan_done_easier
            decouplingPercent != null && decouplingPercent < CalmDecouplingPercent ->
                R.string.rides_note_plan_done_calm
            else -> R.string.rides_note_plan_done
        }
        return RideNote(
            headline = UiText.Res(R.string.rides_note_plan_title),
            body = UiText.Res(body, listOf(unit)),
        )
    }
    return when (effort) {
        RideEffort.LOCKER -> RideNote(
            headline = UiText.Res(R.string.rides_note_easy_title),
            body = UiText.Res(R.string.rides_note_easy_body),
        )
        RideEffort.MITTEL -> RideNote(
            headline = UiText.Res(R.string.rides_note_moderate_title),
            body = UiText.Res(R.string.rides_note_moderate_body),
        )
        RideEffort.HART -> RideNote(
            headline = UiText.Res(R.string.rides_note_hard_title),
            body = UiText.Res(R.string.rides_note_hard_body),
        )
        null -> null
    }
}
