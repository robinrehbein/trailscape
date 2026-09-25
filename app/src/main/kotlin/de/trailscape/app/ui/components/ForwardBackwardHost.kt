package de.trailscape.app.ui.components

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import de.trailscape.app.ui.theme.M3Transitions

/**
 * # Eine Ebene tiefer, ohne die Liste zu verlieren
 *
 * M3 „Forward and backward" (siehe [M3Transitions]) fuer eine Liste und ihr
 * Detail im selben Screen: Das Detail kommt 30 dp von rechts und blendet
 * ein, die Liste weicht 30 dp nach links und blendet aus — beim Zurueckgehen
 * spiegelverkehrt.
 *
 * Anders als ein `AnimatedContent`, das die Liste beim Wechsel verwirft,
 * bleibt [base] die ganze Zeit komponiert: Scrollstand, Suche und offene
 * Abschnitte sind nach dem Zurueckgehen genau wie vorher. Waehrend das
 * Detail steht, ist die Liste fuer TalkBack ausgeblendet.
 *
 * [detail] zeigt den zuletzt geoeffneten Wert auch waehrend des
 * Zurueckgleitens, obwohl [key] dann schon `null` ist.
 */
@Composable
fun <T : Any> ForwardBackwardHost(
    key: T?,
    modifier: Modifier = Modifier,
    base: @Composable () -> Unit,
    detail: @Composable (T) -> Unit,
) {
    val distancePx = with(LocalDensity.current) { M3Transitions.SlideDistance.toPx() }
    val open = key != null
    val lastKey = remember { arrayOfNulls<Any>(1) }
    if (key != null) lastKey[0] = key

    val transition = updateTransition(targetState = open, label = "Vor und zurück")
    val slide = tween<Float>(M3Transitions.DurationMillis, easing = FastOutSlowInEasing)
    val fadeOut = tween<Float>(M3Transitions.OutgoingMillis, easing = FastOutLinearInEasing)
    val fadeIn = tween<Float>(
        M3Transitions.IncomingMillis,
        delayMillis = M3Transitions.OutgoingMillis,
        easing = LinearOutSlowInEasing,
    )
    // Offen heisst: Detail vorn, Liste 30 dp links und ausgeblendet.
    val baseOffset = transition.animateFloat({ slide }, label = "Liste x") { if (it) -distancePx else 0f }
    val baseAlpha = transition.animateFloat(
        { if (targetState) fadeOut else fadeIn },
        label = "Liste Deckkraft",
    ) { if (it) 0f else 1f }
    val detailOffset = transition.animateFloat({ slide }, label = "Detail x") { if (it) 0f else distancePx }
    val detailAlpha = transition.animateFloat(
        { if (targetState) fadeIn else fadeOut },
        label = "Detail Deckkraft",
    ) { if (it) 1f else 0f }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = baseOffset.value
                    alpha = baseAlpha.value
                }
                .then(if (open) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            base()
        }
        if (transition.currentState || transition.targetState) {
            @Suppress("UNCHECKED_CAST")
            val shown = (key ?: lastKey[0]) as T?
            if (shown != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = detailOffset.value
                            alpha = detailAlpha.value
                        },
                ) {
                    detail(shown)
                }
            }
        }
    }
}
