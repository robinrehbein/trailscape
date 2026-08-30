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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Warum ueberhaupt eine eigene Ueberschriftenform: Die Zielstruktur ersetzt
 * die frueheren Kartentitel („Form", „Vitalwerte", „Diese Woche") durch
 * *Abschnitte*. Ein Abschnittstitel im selben `titleMedium` wie die Kartentitel
 * darunter waere keine Ebene, sondern eine Wiederholung — die Augenbraue ist
 * bewusst kleiner, versal und gedaempft und tritt damit hinter den Inhalt
 * zurueck, den sie sortiert.
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
    mono: Boolean = false,
) {
    val base = MaterialTheme.typography.labelSmall
    Text(
        text = text.uppercase(),
        style = if (mono) {
            base.copy(letterSpacing = EyebrowTracking, fontFamily = FontFamily.Monospace)
        } else {
            base.copy(letterSpacing = EyebrowTracking)
        },
        color = color,
        modifier = modifier,
    )
}

/**
 * Die Kapitelmarke eines Screens — „FORM", „PLAN", „WERTE".
 *
 * Sie steht auf blankem Grund, nicht in einer Karte, und ist deshalb um
 * [CardPadding] eingerueckt: So sitzt sie auf derselben Kante wie der Text *in*
 * den Karten darunter statt weiter aussen als er — dasselbe Idiom, mit dem auch
 * die Datumszeile der Startseite und die Gruppenlabels des Mehr-Tabs gesetzt
 * sind.
 *
 * Diese drei Marken sind der ganze Ersatz fuer eine zweite Reiterleiste: Der
 * Trainings-Tab ist **ein** Scroll-Screen, seine Kapitel erkennt man beim
 * Vorbeiscrollen, nicht durch Antippen.
 */
@Composable
fun SectionEyebrow(text: String, modifier: Modifier = Modifier) {
    Eyebrow(
        text = text,
        mono = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = CardPadding, top = SectionEyebrowTopGap, bottom = 2.dp),
    )
}

/**
 * Die Karte, in der der Coach spricht: Akzentflaeche statt Kartenweiss.
 *
 * `primaryContainer`/`onPrimaryContainer` sind die einzige getoente Flaeche, die
 * das Schema fuer *Zuspruch* vorhaelt — nicht fuer Warnung (das ist
 * [NoticeBox] mit einer Ampelfarbe) und nicht fuer Neutrales (das ist die
 * normale Karte). Genau so trennt die Referenz `.card` und `.card.coach`.
 *
 * Ohne Schatten und ohne Rand, wie jede Flaeche dieser App: Die Form erbt sie
 * aus `MaterialTheme.shapes`, die Farben aus dem `colorScheme` — hier steht
 * keine eigene Zahl und kein eigener Farbwert.
 *
 * @param eyebrow Absender der Karte. Vorgabe „Coach", weil das der Fall ist,
 *   fuer den es sie gibt.
 */
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
private val EyebrowTracking = 1.1.sp

/**
 * Luft ueber einer Kapitelmarke — sie trennt zwei Abschnitte und braucht
 * deshalb mehr Abstand nach oben als der [de.trailscape.app.ui.theme.CardGap]
 * zwischen zwei Karten desselben Abschnitts.
 */
private val SectionEyebrowTopGap = 12.dp
