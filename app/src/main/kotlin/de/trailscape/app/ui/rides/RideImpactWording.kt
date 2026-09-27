package de.trailscape.app.ui.rides

import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.app.ui.training.formatSigned
import de.trailscape.core.RideImpact
import de.trailscape.core.formatDuration
import de.trailscape.core.freshnessWord
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
 * ([RideImpactLine.body]), wie beim Klartext-Satz der Detailansicht. Der Ton
 * bleibt ruhig und ehrlich: kein „stark", kein „super" — eine kleine Last
 * heisst „Kleiner Reiz", und der Rat zur Erholung nach einer harten Tour
 * steht schon im Klartext-Satz darueber, nicht hier ein zweites Mal.
 */
internal data class RideImpactLine(val lead: String, val body: String)

/** Ab so vielen Bestzeiten fasst eine Zeile sie zusammen statt je eine Zeile. */
private const val BESTS_SUMMARY_FROM = 3

/** Die Zeilen in fester Ordnung: Form, Wochenziel, Kacheln, Bestzeiten. */
internal fun rideImpactLines(impact: RideImpact): List<RideImpactLine> = buildList {
    impact.form?.let { form ->
        if (form.noticeable) {
            add(
                RideImpactLine(
                    // Komma am Ende: Anfang und Rest werden nur mit Leerzeichen
                    // verbunden und muessen als ein Satz lesbar sein.
                    lead = "Fitness ${formatSignedDecimalDe(form.fitnessGain)},",
                    body = "Müdigkeit ${formatSignedDecimalDe(form.fatigueGain)}, " +
                        "Frische danach ${formatSigned(form.freshnessAfter)} " +
                        "(${freshnessWord(form.freshnessAfter)}).",
                ),
            )
        } else {
            add(
                RideImpactLine(
                    lead = "Kleiner Reiz.",
                    body = "Für die Fitness kaum messbar (${formatSignedDecimalDe(form.fitnessGain)}), " +
                        "dafür auch kaum Müdigkeit.",
                ),
            )
        }
    }

    impact.weekGoal?.let { goal ->
        // Dieselbe Rundung wie `weekSummary` auf „Heute".
        val after = goal.kmAfter.roundToInt()
        if (goal.reachedByThisRide) {
            add(RideImpactLine("Wochenziel geschafft.", "Mit dieser Tour $after von ${goal.targetKm} km."))
        } else if (after >= goal.targetKm) {
            // Schon vorher erreicht: wie `weekSummary` nicht „212 von 180 km"
            // ohne Wort dazu, das liest sich sonst wie ein Rueckstand.
            add(
                RideImpactLine(
                    "Wochenziel erreicht.",
                    "$after von ${goal.targetKm} km, diese Tour +${formatRideKm(goal.rideKm)} km.",
                ),
            )
        } else {
            add(
                RideImpactLine(
                    "Wochenziel:",
                    "$after von ${goal.targetKm} km, diese Tour +${formatRideKm(goal.rideKm)} km.",
                ),
            )
        }
    }

    impact.newTiles?.let { n ->
        val lead = if (n == 1) "1 neue Kachel" else "$n neue Kacheln"
        add(RideImpactLine(lead, "zum ersten Mal befahren."))
    }

    val bests = impact.newBests
    if (bests.size >= BESTS_SUMMARY_FROM) {
        add(
            RideImpactLine(
                "Neue Bestzeiten",
                "auf ${bests.size} Segmenten — Details unter „Alle Werte“.",
            ),
        )
    } else {
        for (best in bests) {
            add(
                RideImpactLine(
                    "Neue Bestzeit",
                    "auf „${best.name}“: ${formatDuration(best.timeS)}, " +
                        "${formatImprovementDe(best.improvementS)} schneller.",
                ),
            )
        }
    }
}

/**
 * Eine Nachkommastelle mit Vorzeichen, deutsch: „+1,9", „−0,4" (echtes
 * Minuszeichen U+2212 wie [formatSigned]), „±0,0", wenn gerundet nichts
 * uebrig bleibt.
 */
internal fun formatSignedDecimalDe(value: Double): String {
    val digits = formatOneDecimalDe(abs(value))
    return when {
        digits == "0,0" -> "±0,0"
        value > 0 -> "+$digits"
        else -> "−$digits"
    }
}

/**
 * „14 s" unter einer Minute, sonst „1:15 min" — fuer die Karte und die
 * Bestzeit-Snackbar nach der Fahrt (`AppViewModel.reportNewBests`), damit
 * dieselbe Zahl an beiden Stellen gleich geschrieben wird.
 */
internal fun formatImprovementDe(seconds: Int): String =
    if (seconds < 60) "$seconds s" else "${formatDuration(seconds)} min"

/** Kurze Touren mit Komma („4,2"), ab 10 km ganze Kilometer. */
private fun formatRideKm(km: Double): String =
    if (km < 10) formatKmDe(km) else km.roundToInt().toString()
