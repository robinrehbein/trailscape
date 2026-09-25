package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding

/**
 * # Die Augenbraue — kleine Ueberschrift ueber dem, was sie ankuendigt
 *
 * Der Referenzprototyp (`docs/design/prototyp-eine-leiste.html`) benutzt
 * denselben Baustein an drei Stellen: als Kapitelmarke der drei
 * Trainings-Abschnitte („FORM", „PLAN", „WERTE"), als Absender einer
 * Coach-Karte („COACH") und als Etikett kleiner Karten („PLAN-AUSBLICK",
 * „RUHEPULS"). Weil daraus in der App ein knappes Dutzend Aufrufe wurden,
 * steht der Stil hier einmal statt an jeder Stelle neu.
 *
 * Warum ueberhaupt eine eigene Ueberschriftenform: Ein Abschnittstitel im
 * selben `titleMedium` wie die Kartentitel darunter waere keine Ebene, sondern
 * eine Wiederholung — die Augenbraue ist bewusst kleiner und gedaempft und
 * tritt damit hinter den Inhalt zurueck, den sie sortiert.
 *
 * Verwandt, aber nicht dasselbe: [de.trailscape.app.ui.more.MoreGroupLabel]
 * beschriftet die Gruppen der Einstellungsliste. Sie bleibt dort, weil sie an
 * einer Einstellungs-Gruppenkarte haengt und nicht am Kapitel eines Screens.
 */

/**
 * Versale, gedaempfte Kleinstueberschrift.
 *
 * Schrift und Farbe kommen aus dem Theme (`labelSmall`, `onSurfaceVariant`);
 * eigen ist nur die weitere Laufweite, die die Versalien atmen laesst — genau
 * das `letter-spacing` der Referenz.
 *
 * @param mono setzt die Zeile in die Systemschreibmaschine. Reserviert fuer die
 *   **Kapitelmarken** eines Screens ([SectionEyebrow]); sie sollen sich von den
 *   Etiketten innerhalb einer Karte unterscheiden lassen, ohne dafuer groesser
 *   oder kraeftiger zu werden.
 * @param color Vorgabe ist die gedaempfte Textfarbe des Schemas. Auf einer
 *   Akzentflaeche uebergibt [CoachCard] stattdessen deren eigene Textfarbe —
 *   `onSurfaceVariant` waere dort nicht nur zu leise, sondern schlicht die
 *   falsche Farbe.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    // Fuehrung „Klartext": in Satzschreibung, halbfett, ohne Sperrung und
    // ohne Monospace. Frueher standen hier drei Stile nebeneinander
    // (versal gesperrt, versal in Monospace, Satzschreibung) — je nach
    // Screen, fuer dieselbe Aufgabe.
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier,
    )
}

/**
 * Die Abschnittsueberschrift eines Screens („Diese Woche", „Dein Ziel",
 * „September") — ueberall dieselbe Groesse, Farbe und derselbe Abstand, und
 * buendig mit dem Text in den Karten darunter.
 */
@Composable
fun SectionEyebrow(text: String, modifier: Modifier = Modifier) {
    Eyebrow(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = CardPadding, end = CardPadding, top = SectionEyebrowTopGap, bottom = 2.dp),
    )
}

@Composable
fun CoachCard(
    modifier: Modifier = Modifier,
    eyebrow: String = "Coach",
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Eyebrow(
                text = eyebrow,
                // Die eigene Textfarbe der Flaeche, nur zurueckgenommen: Ein
                // zweiter Farbwert fuer „gedaempft auf Akzent" waere ein
                // Sonderfall im Schema, den sonst niemand braucht.
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                modifier = Modifier.padding(bottom = 4.dp),
            )
            content()
        }
    }
}

/**
 * Laufweite der Augenbraue. Die Referenz setzt 0,14 em auf 7,5 px, also rund
 * ein Zehntel der Schriftgroesse; auf den 11 sp des `labelSmall`-Slots sind das
 * diese 1,1 sp.
 */

/**
 * Luft ueber einer Kapitelmarke — sie trennt zwei Abschnitte und braucht
 * deshalb mehr Abstand nach oben als der [de.trailscape.app.ui.theme.CardGap]
 * zwischen zwei Karten desselben Abschnitts.
 */
private val SectionEyebrowTopGap = 16.dp
