package de.trailscape.app.ui.training

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding

/**
 * # „Was bedeuten diese Zahlen?" — das Glossar am Fuss des Trainings-Tabs
 *
 * Die App zeigt an rund einem Dutzend Stellen Fachbegriffe, die nirgends
 * erklaert werden: CTL, ATL, TSB, Rampenrate, Trainingslast, HRV, RMSSD,
 * Z1–Z5, GA1, Schwelle, LTHR, Deload und Taper. Vier Begriffe machen es
 * vorbildlich vor — HFmax (mit Feldtest-Anleitung im Profil), VO2max,
 * Entkopplung und Readiness stehen dort, wo sie auftauchen, mit einem Satz
 * dabei. Fuer die uebrigen ist an ihrer jeweiligen Stelle kein Platz: Sie
 * stehen in Kennzahlen-Kacheln, Achsenbeschriftungen und Chips.
 *
 * Deshalb eine Sammelstelle — nach demselben Muster wie
 * „Open-Source-Lizenzen" in `ui/more/AboutCard.kt`: eingeklappt eine einzige
 * Textzeile, aufgeklappt die Liste. Wer die Begriffe kennt, sieht nichts als
 * eine Zeile; wer sie nicht kennt, findet sie genau dort, wo die Zahlen
 * stehen.
 *
 * ## Wie die Texte geschrieben sind
 * Kein Lehrbuch: je ein bis zwei Saetze, in der Sprache eines Menschen, der
 * Rad faehrt und keine Sportwissenschaft studiert hat — was die Zahl *fuer
 * ihn* bedeutet, nicht wie sie berechnet wird. Formeln stehen bewusst nicht
 * drin; wo eine Zahl von einer Schaetzung abhaengt, sagt das die Karte an
 * Ort und Stelle (siehe `TrainingInsights.loadScaleNote`).
 *
 * Der Zustand ist `rememberSaveable`: Wer aufklappt, um beim Lesen der Karten
 * darueber nachzuschlagen, soll das Glossar nach dem Drehen des Geraets nicht
 * wieder zuklappen sehen.
 */
@Composable
fun GlossaryCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Was bedeuten diese Zahlen?", style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (expanded) "Erklärungen ausblenden" else "Erklärungen anzeigen")
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    glossaryEntries.forEachIndexed { index, entry ->
                        if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                        Text(text = entry.term, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = entry.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Ein Begriff und sein erklaerender Satz. */
private data class GlossaryEntry(val term: String, val explanation: String)

/**
 * Die Begriffe in der Reihenfolge, in der man ihnen im Tab begegnet: erst die
 * drei Kurven-Kennzahlen der Form-Karte, dann was daraus folgt (Rampenrate,
 * Deload, Taper), dann die Werte aus Uhr und Profil.
 */
private val glossaryEntries: List<GlossaryEntry> = listOf(
    GlossaryEntry(
        term = "Trainingslast",
        explanation = "Eine Zahl je Tour, die Dauer und Härte zusammenfasst: Zwei ruhige " +
            "Stunden können dieselbe Last ergeben wie eine harte. Sie ist der Rohstoff " +
            "für alles Weitere auf dieser Seite. 100 entspricht ungefähr einer Stunde am " +
            "Anschlag.",
    ),
    GlossaryEntry(
        term = "Fitness (CTL)",
        explanation = "Dein Trainingsstand: der träge Durchschnitt deiner Trainingslast über " +
            "die letzten Wochen. Steigt langsam, fällt langsam — deshalb dauert es rund " +
            "zwei Wochen, bis die Zahl etwas taugt.",
    ),
    GlossaryEntry(
        term = "Ermüdung (ATL)",
        explanation = "Dasselbe, nur über die letzten Tage gerechnet. Eine harte Tour hebt " +
            "sie sofort deutlich an; nach ein paar ruhigen Tagen ist sie wieder unten.",
    ),
    GlossaryEntry(
        term = "Form (TSB)",
        explanation = "Fitness minus Ermüdung. Deutlich positiv heißt frisch und bereit für " +
            "einen harten Tag, deutlich negativ heißt müde vom Aufbau. Beides hat seine " +
            "Zeit — dauerhaft tief im Minus ist die Warnung.",
    ),
    GlossaryEntry(
        term = "Rampenrate",
        explanation = "Wie schnell deine Fitness steigt, in Punkten pro Woche. Mehr als etwa " +
            "fünf ist der klassische Weg in eine Überlastung: Der Kopf will schneller " +
            "aufbauen, als Sehnen und Bänder mitkommen.",
    ),
    GlossaryEntry(
        term = "Deload und Taper",
        explanation = "Zwei Arten, weniger zu fahren. Ein Deload ist eine eingeschobene " +
            "ruhige Woche, wenn die Ermüdung überhandnimmt. Ein Taper ist die geplante " +
            "Entlastung vor dem Zieltermin — Umfang runter, Schärfe drin, damit du am " +
            "Tag X ausgeruht und trotzdem spritzig bist.",
    ),
    GlossaryEntry(
        term = "Zonen Z1–Z5 und GA1",
        explanation = "Fünf Intensitätsstufen nach Puls. Z1 ist Rollen zur Erholung, Z2 das " +
            "lockere Grundlagentempo, bei dem du dich noch unterhalten kannst (früher " +
            "GA1 genannt), Z3 zügig, Z4 an der Schwelle, Z5 alles darüber. Die meisten " +
            "Kilometer gehören in Z2 — auch wenn sich das zu langsam anfühlt.",
    ),
    GlossaryEntry(
        term = "Schwelle, LTHR und FTP",
        explanation = "Die Grenze, oberhalb derer du nicht mehr lange durchhältst — rund eine " +
            "Stunde am Stück. Als Puls heißt sie LTHR (Schwellenpuls), als Leistung FTP " +
            "(Watt). Sie ist der Maßstab, an dem alle Zonen und jede Trainingslast " +
            "hängen; ohne eigenen Wert schätzen wir sie aus deinem Alter, Gewicht und " +
            "deinen Touren.",
    ),
    GlossaryEntry(
        term = "HRV und RMSSD",
        explanation = "Die Herzfrequenzvariabilität: wie stark die Abstände zwischen zwei " +
            "Herzschlägen schwanken. Erstaunlicherweise ist mehr Schwankung das gute " +
            "Zeichen — sie steht für einen erholten Körper. RMSSD ist das Maß dafür, das " +
            "deine Uhr nachts misst. Interessant ist nur die Abweichung von deinem " +
            "eigenen Normalwert, nie der Vergleich mit anderen.",
    ),
    GlossaryEntry(
        term = "Erholung (Readiness)",
        explanation = "Ein Wert von 0 bis 100, der Ruhepuls, HRV, Schlaf und deine aktuelle " +
            "Belastung zusammenzieht. Er sagt, wie viel du dir heute zumuten kannst — " +
            "ein Trend, keine Messung, und erst mit vier Wochen Historie vollständig.",
    ),
)
