package de.trailscape.app.ui.rides

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.app.ui.training.formatSigned
import de.trailscape.core.RideImpact
import de.trailscape.core.formatDuration
import de.trailscape.core.freshnessWord
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.formatDecimal
import de.trailscape.core.i18n.formatDistanceKm
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * # „Was die Tour gebracht hat" in Worten
 *
 * Die Rechnung steht in `:core`/`RideImpact.kt`; hier wird nur formuliert —
 * plattformfrei, damit die Saetze ohne Robolectric pruefbar sind
 * (`RideImpactWordingTest`), dasselbe Muster wie `RideEffort.kt`.
 *
 * Jede Zeile ist ein fetter Anfang ([RideImpactLine.lead]) plus Rest
 * ([RideImpactLine.body]), wie beim Klartext-Satz der Detailansicht — beide als
 * [UiText] (Ressourcen `rides_impact_*`); Zahlen und Kernwoerter kommen in der
 * Sprache von [CoreTexts] als Argumente hinein. Der Ton
 * bleibt ruhig und ehrlich: kein „stark", kein „super" — eine kleine Last
 * heisst „Kleiner Reiz", und der Rat zur Erholung nach einer harten Tour
 * steht schon im Klartext-Satz darueber, nicht hier ein zweites Mal.
 */
internal data class RideImpactLine(val lead: UiText, val body: UiText)

/** Ab so vielen Bestzeiten fasst eine Zeile sie zusammen statt je eine Zeile. */
private const val BESTS_SUMMARY_FROM = 3

/** Die Zeilen in fester Ordnung: Form, Wochenziel, Kacheln, Bestzeiten. */
internal fun rideImpactLines(impact: RideImpact, texts: CoreTexts): List<RideImpactLine> = buildList {
    val language = texts.format.language
    impact.form?.let { form ->
        if (form.noticeable) {
            add(
                RideImpactLine(
                    // Komma am Ende: Anfang und Rest werden nur mit Leerzeichen
                    // verbunden und muessen als ein Satz lesbar sein.
                    lead = UiText.Res(
                        R.string.rides_impact_form_lead,
                        listOf(formatSignedDecimal(form.fitnessGain, language)),
                    ),
                    body = UiText.Res(
                        R.string.rides_impact_form_body,
                        listOf(
                            formatSignedDecimal(form.fatigueGain, language),
                            formatSigned(form.freshnessAfter),
                            freshnessWord(form.freshnessAfter, texts),
                        ),
                    ),
                ),
            )
        } else {
            add(
                RideImpactLine(
                    lead = UiText.Res(R.string.rides_impact_small_lead),
                    body = UiText.Res(
                        R.string.rides_impact_small_body,
                        listOf(formatSignedDecimal(form.fitnessGain, language)),
                    ),
                ),
            )
        }
    }

    impact.weekGoal?.let { goal ->
        // Dieselbe Rundung wie `weekSummary` auf „Heute".
        val after = goal.kmAfter.roundToInt()
        val rideKm = formatRideKm(goal.rideKm, language)
        if (goal.reachedByThisRide) {
            add(
                RideImpactLine(
                    UiText.Res(R.string.rides_impact_week_reached_lead),
                    UiText.Res(R.string.rides_impact_week_reached_body, listOf(after, goal.targetKm)),
                ),
            )
        } else if (after >= goal.targetKm) {
            // Schon vorher erreicht: wie `weekSummary` nicht „212 von 180 km"
            // ohne Wort dazu, das liest sich sonst wie ein Rueckstand.
            add(
                RideImpactLine(
                    UiText.Res(R.string.rides_impact_week_met_lead),
                    UiText.Res(R.string.rides_impact_week_body, listOf(after, goal.targetKm, rideKm)),
                ),
            )
        } else {
            add(
                RideImpactLine(
                    UiText.Res(R.string.rides_impact_week_open_lead),
                    UiText.Res(R.string.rides_impact_week_body, listOf(after, goal.targetKm, rideKm)),
                ),
            )
        }
    }

    impact.newTiles?.let { n ->
        add(
            RideImpactLine(
                UiText.Plural(R.plurals.rides_impact_new_tiles_count, n),
                UiText.Res(R.string.rides_impact_new_tiles_body),
            ),
        )
    }

    val bests = impact.newBests
    if (bests.size >= BESTS_SUMMARY_FROM) {
        // Ab drei Bestzeiten ist die Zahl nie 1 — ein einfacher String genuegt.
        add(
            RideImpactLine(
                UiText.Res(R.string.rides_impact_bests_lead),
                UiText.Res(R.string.rides_impact_bests_body, listOf(bests.size)),
            ),
        )
    } else {
        for (best in bests) {
            add(
                RideImpactLine(
                    UiText.Res(R.string.rides_impact_best_lead),
                    UiText.Res(
                        R.string.rides_impact_best_body,
                        listOf(best.name, formatDuration(best.timeS), formatImprovementDe(best.improvementS)),
                    ),
                ),
            )
        }
    }
}

/**
 * Eine Nachkommastelle mit Vorzeichen im Zahlformat von [language]: „+1,9" /
 * „+1.9", „−0,4" (echtes Minuszeichen U+2212 wie [formatSigned]), „±0,0",
 * wenn gerundet nichts uebrig bleibt.
 */
internal fun formatSignedDecimal(value: Double, language: AppLanguage): String {
    val digits = formatDecimal(abs(value), 1, language)
    return when {
        digits == formatDecimal(0.0, 1, language) -> "±$digits"
        value > 0 -> "+$digits"
        else -> "−$digits"
    }
}

/** Wie [formatSignedDecimal], deutsch: „+1,9", „−0,4", „±0,0". */
internal fun formatSignedDecimalDe(value: Double): String = formatSignedDecimal(value, AppLanguage.DE)

/**
 * „14 s" unter einer Minute, sonst „1:15 min" — fuer die Karte und die
 * Bestzeit-Snackbar nach der Fahrt (`AppViewModel.reportNewBests`), damit
 * dieselbe Zahl an beiden Stellen gleich geschrieben wird. Trotz des
 * Namens sprachneutral: „s" und „min" schreiben beide Sprachen gleich.
 */
internal fun formatImprovementDe(seconds: Int): String =
    if (seconds < 60) "$seconds s" else "${formatDuration(seconds)} min"

/** Kurze Touren mit einer Nachkommastelle („4,2" / „4.2"), ab 10 km ganze Kilometer. */
private fun formatRideKm(km: Double, language: AppLanguage): String =
    if (km < 10) formatDistanceKm(km, language) else km.roundToInt().toString()
