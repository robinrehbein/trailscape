package de.trailscape.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.map.PrimaryButton
import de.trailscape.app.ui.today.TodayOffer
import de.trailscape.app.ui.today.offerDialogAction
import de.trailscape.app.ui.today.offerHint

/**
 * # Der Bereit-Dialog des schwebenden Aufnahme-Knopfs
 *
 * Ein Tipp auf den runden Knopf neben der Navigationskapsel
 * (`ui/components/RecCapsuleButton.kt`, eingesetzt in `ui/TrailscapeApp.kt`)
 * startet **nicht** sofort eine Aufzeichnung. Er fragt zuerst, was gefahren
 * werden soll — und zwar genau die eine Frage, die im jeweiligen Zustand offen
 * ist:
 *
 *  * **Ohne geplante Route** („Losfahren", ohne Route): losfahren, die Strecke entsteht
 *    unterwegs. Darunter, dezent, der Hinweis auf die Tagesempfehlung mit dem
 *    kuerzesten Weg dorthin — „Runde zum Plan bauen".
 *  * **Mit geplanter Route** („Losfahren", mit Route): mit Navigation
 *    losfahren, ersatzweise nur aufzeichnen, oder die Route wegwerfen.
 *
 * ## Warum ueberhaupt eine Nachfrage
 * Der Knopf ist der einzige Startpunkt einer Fahrt und liegt jetzt auf jedem
 * Bildschirm in Daumenreichweite — ein Fehlgriff waere also billig zu machen
 * und teuer zu bemerken (ein Vordergrunddienst, der Positionen schreibt).
 * Wichtiger noch: Genau hier faellt die Entscheidung, die vorher niemand
 * gestellt bekam. Wer eine Route geplant hatte, musste die Navigation
 * anschliessend im Planungsblatt der Karte selbst starten; wer nur den
 * Aufnahme-Knopf drueckte, fuhr die geplante Route ohne Fuehrung. Der Dialog
 * macht aus zwei stillschweigenden Wegen eine sichtbare Wahl.
 *
 * ## Wer die Arbeit tut
 * Dieser Dialog **startet nichts selbst**. Jede Aktion legt eine Bitte im
 * [AppViewModel] ab, die der Karten-Screen mit seinen bestehenden lokalen
 * Funktionen beantwortet — `startRecording()` bzw. `navigatePlannedRoute()`,
 * beide samt Standort- und Benachrichtigungsabfrage:
 *
 *  * „Aufzeichnung starten" / „Ohne Route, nur aufzeichnen" →
 *    [AppViewModel.requestRecording] (derselbe Weg, den die Startseite seit
 *    jeher nimmt),
 *  * „Mit Navigation starten" → [AppViewModel.requestNavigatePlanned],
 *  * „Route verwerfen" → [AppViewModel.requestDiscardPlannedRoute],
 *  * „Runde zum Plan bauen" → [AppViewModel.requestRouteGeneration] mit
 *    demselben Ziel, das die Startseite ihrem Knopf „Passende Runde planen"
 *    unterlegt.
 *
 * Die ersten drei wechseln dabei zuerst in den Karten-Tab, damit der Screen
 * ueberhaupt komponiert ist, wenn er die Bitte abholt. Eine zweite Kopie der
 * Berechtigungslogik gibt es dadurch nirgends.
 *
 * ## Warum die Tagesentscheidung hier noch einmal gerechnet wird
 * Der Hinweis auf die Tagesempfehlung braucht dasselbe Angebot
 * ([TodayOffer]), das `ui/today/TodayScreen.kt` seinem Knopf unterlegt — und
 * das entsteht in [decideToday] aus `adaptPlan` → `sessionsForDay` →
 * `decideTodayRoute` → `offeredTarget`. Diese
 * Kette laeuft hier ein zweites Mal, statt das Ergebnis im ViewModel zu
 * hinterlegen: Die Startseite braucht das **ganze** Ergebnis (Notiz,
 * Abwertung, Plandistanz) fuer ihre Karte, dieser Dialog nur die eine Zeile
 * und das Ziel. Ein gemeinsamer Zustand haette das groessere Ergebnis dauerhaft
 * gehalten, damit die kleinere Frage einmal beantwortet werden kann. Die
 * Rechnung selbst ist billig und laeuft ausserdem nur, solange der Dialog
 * wirklich offen ist — er wird in der Huelle erst dann komponiert.
 *
 * @param plannedRouteKm Laenge der geplanten Route, oder `null` fuer die freie
 *   Fahrt. Kommt aus [AppViewModel.plannedRouteKm]; der Dialog liest ihn
 *   bewusst nicht selbst, damit Knopf und Dialog nachweislich denselben
 *   Zustand zeigen.
 */
@Composable
fun ReadyToRideDialog(
    appViewModel: AppViewModel,
    plannedRouteKm: Double?,
    onDismiss: () -> Unit,
) {
    if (plannedRouteKm == null) {
        FreeRideDialog(appViewModel = appViewModel, onDismiss = onDismiss)
    } else {
        PlannedRideDialog(
            appViewModel = appViewModel,
            distanceKm = plannedRouteKm,
            onDismiss = onDismiss,
        )
    }
}

/**
 * „Losfahren" ohne Route: aufzeichnen — plus der dezente Hinweis auf die
 * heutige Empfehlung. Am Zieltag gibt es keine Runde zu bauen, dann steht der
 * Hinweis nicht da; am Ruhetag sagt er „Ruhetag" und bietet die lockere Runde
 * an, wie „Heute" und die Karte auch.
 */
@Composable
private fun FreeRideDialog(appViewModel: AppViewModel, onDismiss: () -> Unit) {
    // Dieselbe Rechnung wie „Heute" und die Karte (siehe [decideToday]) —
    // am Ruhetag also auch hier das ruhigere Angebot statt der Tagesrunde.
    val decision = rememberTodayDecision(appViewModel)
    val offer: TodayOffer? = decision.offer

    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitleWithClose(onClose = onDismiss) },
        contentPadding = 24.dp,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Die App zeichnet auf, die Route entsteht unterwegs.")
                if (offer != null) {
                    // Dezent und einen Schriftgrad kleiner: Der Hinweis ist ein
                    // Angebot, keine Aufforderung — wer den Knopf gedrueckt hat,
                    // will meistens einfach losfahren.
                    Text(
                        text = offerHint(offer, decision.restHeadline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            DialogButtonStack(
                primaryLabel = "Aufzeichnung starten",
                onPrimary = {
                    appViewModel.requestRecording()
                    onDismiss()
                },
                secondaryLabel = offer?.let { offerDialogAction(it) },
                onSecondary = {
                    offer?.let { appViewModel.requestRouteGeneration(it.target) }
                    onDismiss()
                },
            )
        },
    )
}

/**
 * „Losfahren" mit Route: Es liegt eine Route bereit — die Frage ist nur noch,
 * ob mit Fuehrung, ohne, oder gar nicht.
 *
 * Verwerfen gibt es hier nicht mehr (Fuehrung „Klartext"): Es vernichtete
 * ohne Rueckweg eine halbe Stunde Planung. Verworfen wird eine Route jetzt
 * nur noch an ihr selbst auf der Karte — mit „Rückgängig".
 */
@Composable
private fun PlannedRideDialog(
    appViewModel: AppViewModel,
    distanceKm: Double,
    onDismiss: () -> Unit,
) {
    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitleWithClose(onClose = onDismiss) },
        contentPadding = 24.dp,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Route · ${formatKmDe(distanceKm)} km, mit Abbiegehinweisen.")
            }
        },
        confirmButton = {
            DialogButtonStack(
                primaryLabel = "Mit Route starten",
                onPrimary = {
                    appViewModel.requestNavigatePlanned()
                    onDismiss()
                },
                secondaryLabel = "Ohne Route starten",
                onSecondary = {
                    appViewModel.requestRecording()
                    onDismiss()
                },
            )
        },
    )
}

/**
 * Die Knoepfe des Losfahren-Dialogs untereinander, in klarer Rangfolge:
 * oben die Hauptaktion gefuellt, darunter die Alternative nur umrandet.
 * Abbrechen ist das X oben rechts ([DialogTitleWithClose]). Nebeneinander passten drei Aktionen
 * nicht gleichwertig in eine Zeile, und drei gleich aussehende Textknoepfe
 * liessen offen, was der naheliegende Weg ist.
 */
@Composable
private fun DialogButtonStack(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrimaryButton(
            text = primaryLabel,
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (secondaryLabel != null) {
            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                // Kraeftigere Kante als die Vorgabe: Auf der hellen
                // Dialogflaeche verschwand der Rahmen sonst fast.
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) { Text(secondaryLabel, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** „Losfahren" mit dem Schliessen-X oben rechts — statt „Abbrechen" unten. */
@Composable
private fun DialogTitleWithClose(onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Losfahren", modifier = Modifier.weight(1f))
        IconButton(onClick = onClose, modifier = Modifier.offset(x = 12.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Schließen")
        }
    }
}
