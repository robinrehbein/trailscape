package de.trailscape.app.indoor.render

import android.view.TextureView
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.trailscape.core.garage.RiderAvatar

/**
 * Erlaubt es Tests (oder einer Vorschau ohne GPU), die 3D-Garagenbuehne
 * sauber abzuschalten, sodass direkt der 2D-Fallback gezeichnet wird.
 */
val LocalGarage3DPreviewEnabled = compositionLocalOf { true }

/**
 * Interaktive 3D-Vorschau von Fahrer und Rad in der Garage (`rider.glb`).
 *
 * Waagerechtes Wischen dreht das Modell um 360°, Doppeltippen setzt die
 * Ansicht zurueck. Scheitert Filament (z. B. fehlendes OpenGL ES 3.0 oder
 * Testumgebung ohne native Bibliothek), schaltet die Ansicht automatisch auf
 * [fallback] (die gezeichnete 2D-Vorschau) um.
 */
@Composable
fun GarageRider3DPreview(
    avatar: RiderAvatar,
    safeMode: Boolean,
    darkTheme: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    onFallbackChanged: (Boolean) -> Unit = {},
    fallback: @Composable (Modifier) -> Unit,
) {
    if (!LocalGarage3DPreviewEnabled.current) {
        LaunchedEffect(Unit) { onFallbackChanged(true) }
        fallback(modifier)
        return
    }

    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderer = remember(context, safeMode) {
        GarageStageRenderer(context, safeMode).apply {
            setAvatar(avatar)
            setDarkTheme(darkTheme)
        }
    }

    DisposableEffect(renderer) {
        onDispose { renderer.destroy() }
    }

    DisposableEffect(lifecycleOwner, renderer) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> renderer.setPaused(false)
                Lifecycle.Event.ON_STOP -> renderer.setPaused(true)
                else -> Unit
            }
        }
        renderer.setPaused(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(renderer, avatar) {
        renderer.setAvatar(avatar)
    }
    LaunchedEffect(renderer, darkTheme) {
        renderer.setDarkTheme(darkTheme)
    }
    LaunchedEffect(renderer.failed) {
        onFallbackChanged(renderer.failed)
    }

    if (renderer.failed) {
        fallback(modifier)
        return
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .semantics { contentDescription = description }
            .pointerInput(renderer, density) {
                detectHorizontalDragGestures { _, dragAmount ->
                    renderer.onDrag(dragAmount / density)
                }
            }
            .pointerInput(renderer) {
                detectTapGestures(onDoubleTap = { renderer.resetCamera() })
            },
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { tv -> renderer.attach(tv) }
            },
            onRelease = { tv -> renderer.detach(tv) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
