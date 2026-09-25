package de.trailscape.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * „Halten zum Beenden" — der **eine** Weg, eine Aufzeichnung zu beenden
 * (Fuehrung „Klartext", `docs/design/prototyp-klartext.html`, Cockpit).
 *
 * Vorher gab es drei Beenden-Knoepfe mit verschiedenem Verhalten: die
 * Live-Karte beendete ohne Rueckfrage, Kompaktleiste und Datenseite fragten
 * mit einer Knopfzeile nach. Ein Fehlgriff auf Schotter darf keine laufende
 * Tour beenden; statt einer Rueckfrage (zweiter Tipp an anderer Stelle) muss
 * der Finger hier [HoldDurationMillis] lang liegen bleiben — waehrenddessen
 * fuellt sich die Flaeche von links rot. Loslassen vorher bricht ab, ein
 * kurzer Tipp zeigt „Gedrückt halten". Das klappt auch mit Handschuhen und
 * braucht kein zweites Ziel.
 *
 * Bedienhilfen: Fuer TalkBack ist der Knopf ein gewoehnlicher Knopf mit der
 * Aktion „Aufzeichnung beenden" — dort ist das Doppeltippen bereits die
 * bewusste Bestaetigung.
 */
@Composable
fun HoldToEndButton(
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Halten zum Beenden",
    minHeight: Dp = 52.dp,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnEnd by rememberUpdatedState(onEnd)
    val progress = remember { Animatable(0f) }
    var showHint by remember { mutableStateOf(false) }

    // Der Hinweis nach einem kurzen Tipp verschwindet von selbst wieder.
    LaunchedEffect(showHint) {
        if (showHint) {
            delay(HintVisibleMillis)
            showHint = false
        }
    }

    val colors = MaterialTheme.colorScheme
    val filled = progress.value > 0.5f

    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(CircleShape)
            .background(colors.errorContainer)
            .drawBehind {
                drawRect(
                    color = colors.error,
                    size = Size(size.width * progress.value, size.height),
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        showHint = false
                        val hold = scope.launch {
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(HoldDurationMillis, easing = LinearEasing),
                            )
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnEnd()
                            progress.snapTo(0f)
                        }
                        tryAwaitRelease()
                        if (hold.isActive) {
                            hold.cancel()
                            if (progress.value < ShortTapThreshold) showHint = true
                            scope.launch { progress.animateTo(0f, tween(ReleaseMillis)) }
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = "Aufzeichnung beenden"
                onClick(label = "Aufzeichnung beenden") {
                    currentOnEnd()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (showHint) "Gedrückt halten" else label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (filled) colors.onError else colors.onErrorContainer,
        )
    }
}

/** So lange muss der Finger liegen bleiben. */
private const val HoldDurationMillis = 1100

/** Rueckfall der Fuellung nach zu fruehem Loslassen. */
private const val ReleaseMillis = 150

/** Unterhalb dieses Fortschritts gilt ein Druck als Tipp und zeigt den Hinweis. */
private const val ShortTapThreshold = 0.15f

/** Wie lange „Gedrückt halten" nach einem Tipp stehen bleibt. */
private const val HintVisibleMillis = 1800L
