package de.trailscape.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * # Das wischbare Blatt — Griff, Ziehen, Einrasten
 *
 * Die unteren Blaetter der Karte (Erkunden, Planung) klappten bisher nur per
 * Tipp auf einen Pfeil um — zwei Zustaende, harter Wechsel. Dieses Wrapper-
 * Composable macht daraus ein echtes Blatt im Sinn von One UI und Google
 * Maps: Ein Griff oben, der ganze Kopf laesst sich mit dem Finger hochziehen,
 * der Koerper folgt dem Finger stufenlos und rastet beim Loslassen am
 * naeheren Ende ein (Geschwindigkeit zaehlt mit — ein Schwung reicht).
 *
 * ## Warum ein eigener Wrapper und kein `ModalBottomSheet`/`BottomSheetScaffold`
 * Das modale Blatt (siehe `SearchSheet.kt`) legt einen Scrim ueber die Karte
 * und nimmt ihr die Gesten — beim Planen und Erkunden muss die Karte aber
 * bedienbar bleiben. `BottomSheetScaffold` wiederum besitzt die ganze Seite
 * und laesst sich nicht in den bestehenden Stapel am unteren Kartenrand
 * einreihen (Aufnahmeknopf, Karten wie [RideCard], schwebende
 * Navigationskapsel darunter). Der Wrapper laesst den Stapel unangetastet und
 * tauscht nur das Innenleben: Klapp-Pfeil raus, [anchoredDraggable] rein.
 *
 * ## Wie der Koerper stufenlos erscheint
 * Der Koerper wird **unbegrenzt** gemessen (seine natuerliche Hoehe ist der
 * obere Anker) und in einem Fenster gezeigt, dessen Hoehe dem Zieh-Offset
 * folgt — darunter schneidet [clipToBounds] ab. So braucht es keine zweite
 * Vermessung und keine Prozentrechnung: Offset in Pixeln IST die sichtbare
 * Koerperhoehe.
 *
 * @param expanded Aussensicht des Zustands — bleibt beim Aufrufer (und dort
 *   in `rememberSaveable`), damit Tabwechsel und Drehung das Blatt nicht
 *   zuruecksetzen. Der Wrapper meldet Einrasten ueber [onExpandedChange].
 * @param peek Der immer sichtbare Kopf (Statuszeile, Suchzeile …).
 * @param body Der Teil, den Hochwischen freigibt.
 */
@Composable
internal fun SwipeableSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    peek: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var bodyHeightPx by remember { mutableIntStateOf(0) }

    val drag = remember { AnchoredDraggableState(initialValue = expanded) }

    // Anker folgen der gemessenen Koerperhoehe. `updateAnchors` haelt dabei
    // den aktuellen Wert und setzt den Offset passend um — beim allerersten
    // Messen springt ein wiederhergestelltes „aufgeklappt" damit ohne
    // Animation an seinen Platz, genau richtig nach einer Drehung.
    LaunchedEffect(bodyHeightPx) {
        if (bodyHeightPx <= 0) return@LaunchedEffect
        drag.updateAnchors(
            DraggableAnchors {
                false at 0f
                true at bodyHeightPx.toFloat()
            },
        )
    }

    // Aussenzustand -> Blatt (z. B. „Touren aufschlagen" aus einem anderen
    // Tab oder die Zurueck-Geste): animiert nachziehen, aber nur bei echter
    // Abweichung — sonst wuerde jede Einrast-Meldung sofort eine zweite,
    // leere Animation anstossen.
    LaunchedEffect(expanded, bodyHeightPx) {
        if (bodyHeightPx > 0 && drag.settledValue != expanded) drag.animateTo(expanded)
    }

    // Blatt -> Aussenzustand: erst beim Einrasten, nicht waehrend des Ziehens.
    LaunchedEffect(drag) {
        snapshotFlow { drag.settledValue }.collect { settled ->
            if (settled != expanded) onExpandedChange(settled)
        }
    }

    val revealed = drag.offset.takeIf { !it.isNaN() } ?: 0f
    val revealedDp = with(density) { revealed.coerceAtLeast(0f).toDp() }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.anchoredDraggable(
                state = drag,
                orientation = Orientation.Vertical,
                // Hochziehen (negatives dy) soll den Offset — die sichtbare
                // Koerperhoehe — VERGROESSERN.
                reverseDirection = true,
            ),
        ) {
            // Der Griff: One UIs stehende Einladung zum Ziehen. Tippen
            // klappt weiterhin um — fuer alle, die nicht wischen moegen,
            // und fuer Bedienhilfen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .semantics {
                        contentDescription = if (expanded) {
                            "Blatt einklappen"
                        } else {
                            "Blatt aufklappen"
                        }
                    }
                    .padding(top = 8.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }

            peek()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(revealedDp)
                    .clipToBounds(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Unbegrenzt messen, oben ausrichten: Das Fenster
                        // zeigt den Anfang des Koerpers und waechst mit dem
                        // Offset, statt den Inhalt zu stauchen.
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .onSizeChanged { bodyHeightPx = it.height },
                ) {
                    body()
                }
            }
        }
    }
}
