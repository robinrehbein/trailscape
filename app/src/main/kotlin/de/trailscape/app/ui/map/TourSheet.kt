package de.trailscape.app.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical

/**
 * # Das Tourenblatt — dieselbe Bauart wie [PlanningSheet], anderer Inhalt
 *
 * Seit dem Wegfall des eigenen Touren-Tabs (siehe `ui/TrailscapeApp.kt`,
 * „Warum Touren und Karte eine Seite sind") liegt die Tourenliste hier als
 * unteres Blatt ueber der Karte — Google Maps, Komoot und Samsung zeigen
 * genau dieses Muster. Aufbau, Karte und Farben sind bewusst eine Kopie des
 * Planungsblatts ([PlanningSheet] in `PlanningPanel.kt`): dieselbe `Card` mit
 * 4-dp-Erhebung, dieselbe klickbare Kopfzeile mit Pfeilsymbol als Griff,
 * dieselbe `maxHeight`-Deckelung des Inhalts. Wer eines der beiden Blaetter
 * kennt, kennt das andere.
 *
 * ## Drei Stufen statt zwei — und warum kein gemeinsamer Baustein
 * Das Planungsblatt kennt nur "eingeklappt" und "aufgeklappt"; der Screen
 * entscheidet selbst, ob es ueberhaupt in der Komposition steht (`if
 * (planning) { PlanningSheet(...) }`). Das Tourenblatt braucht dagegen eine
 * dritte, *innere* Stufe ([TourSheetState.HIDDEN]): Es steht praktisch immer
 * zur Verfuegung und muss deshalb selbst wissen, dass es waehrend einer
 * Aufzeichnung, einer Navigation oder mit ausgewaehlter Tour unsichtbar
 * bleibt (siehe die Rangfolge im Klassen-KDoc von `MapScreen.kt`) — der
 * Aufrufer reicht den Zustand nur durch, entscheidet ihn aber in
 * `MapScreen.kt`, nicht hier. Diese eine zusaetzliche Stufe war nicht genug
 * Grund, `PlanningSheet` anzufassen oder eine gemeinsame Basis
 * herauszuziehen: Eine vorzeitige Abstraktion fuer zwei Aufrufstellen haette
 * mehr Indirektion gekostet, als sie an Duplikation gespart haette, und
 * `PlanningPanel.kt` liegt ausserhalb dieser Aenderung.
 *
 * ## Kopfzeile statt Statuszeile
 * Die Planung zeigt eingeklappt eine Zustandszeile ("3 Wegpunkte · 42,1 km").
 * Eine Tourenliste hat keinen vergleichbaren Einzelwert — an ihre Stelle
 * tritt der schlichteste, ehrlichste Titel: "Touren" links, die Anzahl
 * rechts. Auf/Zu-Pfeil und die 48-dp-Trefferflaeche sind unveraendert vom
 * Planungsblatt uebernommen.
 *
 * ## Animiert, wo das Planungsblatt hart umschaltet
 * `PlanningSheet` blendet seinen Koerper ohne Uebergang ein und aus — dort
 * faellt das kaum auf, weil "Route planen" ohnehin einen ganzen Kartenmodus
 * an- und abschaltet. Das Tourenblatt dagegen wird im Ruhezustand der App
 * erwartet und geht dort staendig zwischen PEEK und FULL hin und her; ein
 * hartes Umschalten waere hier das staendige Springen, das One UI vermeidet.
 * `AnimatedVisibility` ist dafuer bereits der etablierte Weg dieser Datei-
 * familie (siehe `MapPanels.kt`: "Compose animiert Sichtbarkeit ueber
 * `AnimatedVisibility` im Screen") — ihre Standardanimation (Expand/Shrink
 * plus Fade) ist genau der ruhige Ton, den One UI zeigt, ohne dass dafuer
 * eine neue Animationskurve erfunden werden muesste.
 *
 * ## Der Inhalt kennt sein Format nicht
 * [content] bekommt nur eine [PaddingValues] gereicht und weiss nichts von
 * `Card`, Kopfzeile oder `maxHeight` — dieselbe Trennung wie bei jedem
 * Bottom-Sheet-Inhalt in Material. Der Aufrufer (`MapScreen.kt`) fuellt sie
 * mit `TourListContent` aus `ui/rides/TourList.kt`; deren `LazyColumn` nimmt
 * die Werte unveraendert als eigenes `contentPadding`, damit weder die
 * Kopfzeile ueberdeckt wird noch der letzte Eintrag am unteren Blattrand
 * klebt. Waagerecht und senkrecht gelten dieselben Randmasse wie im
 * aufgeklappten Planungsblatt ([CardPadding]/[OverlayCardPaddingVertical]) —
 * ein drittes Randmass haette hier nichts erklaert, was diese beiden nicht
 * schon tun.
 */
enum class TourSheetState { HIDDEN, PEEK, FULL }

@Composable
fun TourSheet(
    state: TourSheetState,
    onStateChange: (TourSheetState) -> Unit,
    rideCount: Int,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    // HIDDEN zeichnet nichts — nicht einmal eine leere Karte. Der Aufrufer
    // entscheidet ueber diesen Zustand (siehe Klassen-KDoc), hier wird er nur
    // befolgt: eine leere `Card` waere ein 4-dp-Schatten ohne jeden Inhalt.
    if (state == TourSheetState.HIDDEN) return

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column {
            // Dieselbe ganze Zeile als Trefferflaeche wie im Planungsblatt —
            // ein Pfeil allein waere ein 24-dp-Ziel am Daumen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onStateChange(
                            if (state == TourSheetState.FULL) {
                                TourSheetState.PEEK
                            } else {
                                TourSheetState.FULL
                            },
                        )
                    }
                    .heightIn(min = 48.dp)
                    .padding(start = CardPadding, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Touren",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = rideCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (state == TourSheetState.FULL) {
                        Icons.Filled.ArrowDropDown
                    } else {
                        Icons.Filled.ArrowDropUp
                    },
                    contentDescription = if (state == TourSheetState.FULL) {
                        "Tourenblatt einklappen"
                    } else {
                        "Tourenblatt aufklappen"
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .padding(12.dp),
                )
            }

            AnimatedVisibility(visible = state == TourSheetState.FULL) {
                // Nur die Obergrenze wird hier erzwungen, keine eigene
                // `verticalScroll` — der Inhalt ist eine `LazyColumn` und
                // scrollt selbst; ein zweiter Scrollcontainer aussen wuerde
                // nur widerspruechliche Gesten erzeugen.
                Box(modifier = Modifier.heightIn(max = maxHeight)) {
                    content(
                        PaddingValues(
                            horizontal = CardPadding,
                            vertical = OverlayCardPaddingVertical,
                        ),
                    )
                }
            }
        }
    }
}
