package de.trailscape.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import de.trailscape.app.ui.theme.OneUiMotion

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
    bottomInset: Dp = 0.dp,
    expandedHeight: Dp? = null,
    onRevealFraction: ((Float) -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    SwipeableSheet(
        stop = if (expanded) SheetStop.Full else SheetStop.Peek,
        onStopChange = { onExpandedChange(it != SheetStop.Peek) },
        peek = peek,
        modifier = modifier,
        bottomInset = bottomInset,
        halfStop = false,
        expandedHeight = expandedHeight,
        onRevealFraction = onRevealFraction,
        body = body,
    )
}

/**
 * Die drei Stufen eines Blatts (One UI / Google Maps): nur der Kopf, halb
 * aufgezogen, ganz aufgezogen.
 */
internal enum class SheetStop { Peek, Half, Full }

/**
 * Das Blatt mit bis zu drei Rastpunkten. Mit [halfStop] rastet es zusaetzlich
 * auf halber Koerperhoehe ein — aber nur, wenn der Koerper hoch genug ist,
 * dass die Mitte etwas anderes zeigt als eines der Enden
 * ([MinHalfStopBody]). Sonst verhaelt es sich wie das Zwei-Stufen-Blatt.
 *
 * @param expandedHeight Gesamthoehe der Karte, ganz aufgezogen. Gesetzt,
 *   bekommt der Koerper genau den Rest nach Griff und Peek (gemessen) — die
 *   Karte reicht dann bis oben, egal wie wenig Inhalt drin steht. Der Koerper
 *   fuellt die Hoehe und scrollt selbst. Ohne: natuerliche Koerperhoehe.
 * @param onRevealFraction Wie weit das Blatt gerade aufgezogen ist, 0 bis 1 —
 *   bei jedem Bild waehrend des Ziehens. Damit blendet die Karte ihre Knoepfe
 *   ueber dem Blatt im selben Mass aus, in dem das Blatt ihren Platz braucht.
 */
@Composable
internal fun SwipeableSheet(
    stop: SheetStop,
    onStopChange: (SheetStop) -> Unit,
    peek: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    halfStop: Boolean = false,
    expandedHeight: Dp? = null,
    onRevealFraction: ((Float) -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var bodyHeightPx by remember { mutableIntStateOf(0) }
    var headHeightPx by remember { mutableIntStateOf(0) }
    // Vor dem ersten Messen des Kopfs ein Schaetzwert — der Koerper braucht
    // schon im ersten Bild eine feste Hoehe, sonst misst sein Scrollbereich
    // unendlich.
    val fixedBodyHeight = expandedHeight?.let { total ->
        val head = if (headHeightPx > 0) with(density) { headHeightPx.toDp() } else FallbackHeadHeight
        (total - head).coerceAtLeast(0.dp)
    }
    val withHalf = halfStop && with(density) { bodyHeightPx.toDp() } >= MinHalfStopBody
    // Ohne Mittelstufe gibt es sie auch als Zustand nicht: aus „halb" wird
    // dann „ganz", damit das Blatt nicht auf einem fehlenden Anker haengt.
    val target = if (stop == SheetStop.Half && !withHalf) SheetStop.Full else stop

    val haptics = LocalHapticFeedback.current

    // Von Anfang an MIT Ankern: Ohne Anker findet `anchoredDraggable` beim
    // Loslassen keinen Rastpunkt und stuerzt ab (NPE in `computeTarget`,
    // Absturzbericht 2.0.160). Bis der Koerper gemessen ist, liegen alle
    // Zustaende auf 0 — das Blatt laesst sich dann schlicht nicht aufziehen.
    val drag = remember {
        AnchoredDraggableState(
            initialValue = target,
            anchors = DraggableAnchors {
                SheetStop.Peek at 0f
                SheetStop.Full at 0f
            },
        )
    }

    // Das Einrasten laeuft sonst auf der Compose-Vorgabe: eine Feder ohne
    // begrenzte Dauer, die sich mit dem naechsten BOM-Update lautlos aendern
    // kann. Der Leitfaden verlangt 100 bis 500 ms und die One-UI-Kurve —
    // beides steht jetzt hier. (Das Feld `snapAnimationSpec` am Zustand selbst
    // ist `internal`; der offizielle Weg fuehrt ueber das Fling-Verhalten.)
    val fling = AnchoredDraggableDefaults.flingBehavior(
        state = drag,
        animationSpec = OneUiMotion.standard(),
    )

    // Anker folgen der gemessenen Koerperhoehe. `updateAnchors` haelt dabei
    // den aktuellen Wert und setzt den Offset passend um — beim allerersten
    // Messen springt ein wiederhergestelltes „aufgeklappt" damit ohne
    // Animation an seinen Platz, genau richtig nach einer Drehung.
    LaunchedEffect(bodyHeightPx, withHalf) {
        if (bodyHeightPx <= 0) return@LaunchedEffect
        val full = bodyHeightPx.toFloat()
        drag.updateAnchors(
            DraggableAnchors {
                SheetStop.Peek at 0f
                if (withHalf) SheetStop.Half at full / 2f
                SheetStop.Full at full
            },
            newTarget = if (drag.anchors.hasPositionFor(drag.targetValue)) drag.targetValue else target,
        )
    }

    // Aussenzustand -> Blatt (z. B. „Touren aufschlagen" aus einem anderen
    // Tab oder die Zurueck-Geste): animiert nachziehen, aber nur bei echter
    // Abweichung — sonst wuerde jede Einrast-Meldung sofort eine zweite,
    // leere Animation anstossen.
    LaunchedEffect(target, bodyHeightPx, withHalf) {
        if (bodyHeightPx > 0 && drag.settledValue != target && drag.anchors.hasPositionFor(target)) {
            drag.animateTo(target)
        }
    }

    // Blatt -> Aussenzustand: erst beim Einrasten, nicht waehrend des Ziehens.
    val currentStop by rememberUpdatedState(target)
    val currentOnStopChange by rememberUpdatedState(onStopChange)
    LaunchedEffect(drag) {
        snapshotFlow { drag.settledValue }.collect { settled ->
            if (settled != currentStop) {
                // Der Rastpunkt ist genau die Stelle, an der One UI ein
                // fuehlbares Echo setzt: Die Bewegung endet, und die Hand
                // erfaehrt das, ohne hinzusehen.
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                currentOnStopChange(settled)
            }
        }
    }

    // Listen im Blatt (Vorschlaege, Planungskoerper) scrollen selbst — ohne
    // diese Verbindung schluckten sie jedes Wischen, und das Blatt liess sich
    // ueber ihnen weder auf- noch zuziehen. Jetzt wie bei One UI und Google
    // Maps: Hochwischen zieht erst das Blatt ganz auf und scrollt dann den
    // Inhalt; Runterwischen scrollt den Inhalt an den Anfang und zieht dann
    // das Blatt zu. Beim Loslassen rastet es wie beim Ziehen am Griff ein.
    val scope = rememberCoroutineScope()
    val velocityThresholdPx = with(density) { SettleVelocity.toPx() }
    val nestedScroll = remember(drag) {
        SheetNestedScrollConnection(
            state = drag,
            settle = { velocity ->
                scope.launch { drag.settleWith(velocity, velocityThresholdPx) }
            },
        )
    }

    val currentOnReveal by rememberUpdatedState(onRevealFraction)
    if (onRevealFraction != null) {
        LaunchedEffect(drag) {
            snapshotFlow {
                val offset = drag.offset.takeIf { !it.isNaN() } ?: 0f
                if (bodyHeightPx > 0) (offset / bodyHeightPx).coerceIn(0f, 1f) else 0f
            }.collect { currentOnReveal?.invoke(it) }
        }
    }

    val revealed = drag.offset.takeIf { !it.isNaN() } ?: 0f
    val revealedDp = with(density) { revealed.coerceAtLeast(0f).toDp() }
    val canExpand = bodyHeightPx > 0
    // Tippen auf den Griff: eine Stufe weiter, ganz oben wieder zu.
    val next = when (target) {
        SheetStop.Peek -> if (withHalf) SheetStop.Half else SheetStop.Full
        SheetStop.Half -> SheetStop.Full
        SheetStop.Full -> SheetStop.Peek
    }

    FloatingSheetSurface(modifier = modifier, bottomInset = bottomInset) {
        Column(
            modifier = Modifier
                .nestedScroll(nestedScroll)
                .anchoredDraggable(
                    state = drag,
                    orientation = Orientation.Vertical,
                    // Erst ziehbar, wenn es etwas aufzuziehen gibt.
                    enabled = canExpand,
                    flingBehavior = fling,
                    // Hochziehen (negatives dy) soll den Offset — die
                    // sichtbare Koerperhoehe — VERGROESSERN.
                    reverseDirection = true,
                )
                // TalkBack bekommt Auf- und Zuklappen als Aktionen am ganzen
                // Blatt; der schmale Griff muss dafuer nicht getroffen werden.
                .semantics {
                    if (canExpand) {
                        if (target != SheetStop.Full) {
                            expand { onStopChange(SheetStop.Full); true }
                        }
                        if (target != SheetStop.Peek) {
                            collapse { onStopChange(SheetStop.Peek); true }
                        }
                    }
                },
        ) {
            // Griff und Peek zusammen gemessen: Was davon uebrig bleibt, ist
            // mit [expandedHeight] die Hoehe des Koerpers.
            Column(Modifier.onSizeChanged { headHeightPx = it.height }) {
            // Der Griff: One UIs stehende Einladung zum Ziehen. Tippen
            // schaltet eine Stufe weiter — fuer alle, die nicht wischen
            // moegen. Die Zeile ist bildschirmbreit und 24 dp hoch; die
            // Bedienhilfen nehmen die Aktionen des Blatts oben.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SheetHandleHeight)
                    .clickable(enabled = canExpand, onClickLabel = null) { onStopChange(next) }
                    .clearAndSetSemantics {},
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
            }

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
                        .then(if (fixedBodyHeight != null) Modifier.height(fixedBodyHeight) else Modifier)
                        .onSizeChanged { bodyHeightPx = it.height },
                ) {
                    body()
                }
            }
        }
    }
}

/**
 * Ein Blatt ohne aufziehbaren Teil (Ort, Rundenwahl, Verlauf): dieselbe
 * schwebende Flaeche, aber **ohne Griff** — ein Griff, der nichts tut, waere
 * ein Versprechen ohne Einloesung. Statt des Griffs haelt ein Rand oben
 * denselben Abstand zum Inhalt.
 */
@Composable
internal fun StaticSheet(
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    FloatingSheetSurface(modifier = modifier, bottomInset = bottomInset) {
        Column(
            modifier = Modifier.padding(top = SheetHandleHeight),
            content = content,
        )
    }
}

/**
 * Die gemeinsame Flaeche aller Kartenblaetter: eine schwebende Karte mit
 * Rand zu den Seiten und zur Navigationskapsel, alle Ecken rund. Die Karte
 * bleibt darum herum sichtbar — das Blatt liegt auf ihr, statt sie
 * abzuschneiden. Den Platz fuer Kapsel bzw. Gestenleiste ([bottomInset]) haelt
 * die Flaeche als Aussenabstand frei.
 */
@Composable
private fun FloatingSheetSurface(
    modifier: Modifier = Modifier,
    bottomInset: Dp,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .padding(horizontal = SheetSideMargin)
            .padding(bottom = bottomInset + SheetBottomGap)
            .fillMaxWidth(),
        shape = FloatingSheetShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        content()
    }
}

/**
 * Verbindet scrollende Inhalte mit dem Blatt (siehe Kommentar im Blatt).
 * Offset des Blatts = sichtbare Koerperhoehe; ein Finger nach oben (negatives
 * `y`) vergroessert ihn.
 */
private class SheetNestedScrollConnection(
    private val state: AnchoredDraggableState<SheetStop>,
    private val settle: (velocity: Float) -> Unit,
) : NestedScrollConnection {

    private val canMove: Boolean
        get() = state.anchors.size > 1 && state.anchors.maxPosition() > state.anchors.minPosition()

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Hoch: zuerst das Blatt aufziehen, der Inhalt scrollt erst danach.
        if (available.y < 0f && source == NestedScrollSource.UserInput && canMove) {
            val consumed = state.dispatchRawDelta(-available.y)
            return Offset(0f, -consumed)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Runter, und der Inhalt steht schon oben: das Blatt zuziehen.
        if (available.y > 0f && source == NestedScrollSource.UserInput && canMove) {
            val used = state.dispatchRawDelta(-available.y)
            return Offset(0f, -used)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // Ein Schwung nach oben, solange das Blatt noch nicht ganz offen ist,
        // gehoert dem Blatt — nicht dem Inhalt.
        if (available.y < 0f && canMove && state.requireOffset() < state.anchors.maxPosition()) {
            settle(-available.y)
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!canMove) return Velocity.Zero
        // Zwischen zwei Rastpunkten losgelassen: einrasten.
        val offset = state.requireOffset()
        val atAnchor = state.anchors.closestAnchor(offset)?.let { state.anchors.positionOf(it) == offset } == true
        if (!atAnchor) settle(-available.y)
        return available
    }
}

/**
 * Rastet nach einem Wischen im Inhalt ein: bei Schwung eine Stufe in dessen
 * Richtung, sonst am naechsten Rastpunkt. [velocity] positiv = aufziehen.
 */
private suspend fun AnchoredDraggableState<SheetStop>.settleWith(velocity: Float, thresholdPx: Float) {
    val offset = requireOffset()
    val target = if (abs(velocity) >= thresholdPx) {
        anchors.closestAnchor(offset, searchUpwards = velocity > 0f)
    } else {
        null
    } ?: anchors.closestAnchor(offset) ?: return
    animateTo(target, OneUiMotion.standard())
}

/** Ab diesem Schwung rastet das Blatt eine Stufe weiter statt am naechsten Punkt. */
private val SettleVelocity = 125.dp

/** Hoehe der Griffzeile — auch der obere Rand eines Blatts ohne Griff. */
internal val SheetHandleHeight = 24.dp

/** Ab dieser Koerperhoehe lohnt eine Mittelstufe. */
private val MinHalfStopBody = 240.dp

/** Form eines Kartenblatts: rundum rund, wie jede schwebende Karte. */
internal val FloatingSheetShape = RoundedCornerShape(28.dp)

/** Seitlicher Rand der Blatt-Karte zum Bildschirmrand. */
internal val SheetSideMargin = 12.dp

/** Luft zwischen Blatt-Karte und Navigationskapsel. */
internal val SheetBottomGap = 8.dp

/** Startwert fuer Griff + Peek, bis beides gemessen ist. */
private val FallbackHeadHeight = 150.dp
