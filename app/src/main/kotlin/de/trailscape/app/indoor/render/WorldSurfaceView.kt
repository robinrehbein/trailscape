package de.trailscape.app.indoor.render

import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.WorldTrack
import kotlinx.coroutines.delay

/**
 * Die Zeichenflaeche zu einer [FilamentWorldRenderer]: eine `SurfaceView`
 * in Compose (`AndroidView`, nicht `TextureView` — Vertrag 7.6), am
 * Lebenszyklus der Seite (im Hintergrund kein Zeichnen). Faellt die
 * Darstellung aus, steht hier die 2D-Ersatzansicht ([RenderFallback]).
 */
@Composable
internal fun FilamentSurface(renderer: FilamentWorldRenderer, modifier: Modifier = Modifier) {
    val failure = renderer.failure
    if (failure != null) {
        RenderFallback(renderer = renderer, failure = failure, modifier = modifier)
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, renderer) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // START/STOP statt RESUME/PAUSE: im Mehrfenster-Modus oder unter einem
                // Systemdialog ist die Fahrt noch sichtbar und soll weiterlaufen.
                Lifecycle.Event.ON_START -> renderer.setSurfacePaused(false)
                Lifecycle.Event.ON_STOP -> renderer.setSurfacePaused(true)
                else -> Unit
            }
        }
        renderer.setSurfacePaused(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    // Neue Darstellung an derselben Stelle = neue SurfaceView (die Fabrik haengt genau eine an).
    key(renderer) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { view ->
                    // Liefert false bei Ausfall; dann zeigt die naechste Komposition die Ersatzansicht.
                    renderer.attach(view)
                }
            },
            onRelease = { renderer.detach() },
            modifier = modifier,
        )
    }
}

/**
 * Schlichte 2D-Ersatzansicht: Hoehenprofil des Streckenbands mit der Stelle
 * des Fahrers und ein kurzer Hinweis, warum es kein 3D-Bild gibt. Die Fahrt
 * (HUD) laeuft daneben unveraendert weiter.
 */
@Composable
internal fun RenderFallback(renderer: FilamentWorldRenderer, failure: RenderFailure, modifier: Modifier = Modifier) {
    var distM by remember(renderer) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(renderer) {
        while (true) {
            renderer.lastState?.let { distM = it.rider.distM }
            delay(250)
        }
    }
    val track = renderer.fallbackTrack
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.indoor_render_fallback_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(failureHint(failure)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (track != null && track.sampleCount >= 2) {
                ProfileWithPosition(
                    track = track,
                    distM = distM,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
            }
        }
    }
}

/** Kurzer Hinweis je Ausfallart (UI-Text, keine Diagnose). */
internal fun failureHint(f: RenderFailure): Int = when (f) {
    RenderFailure.NO_GRAPHICS -> R.string.indoor_render_fallback_no_graphics
    RenderFailure.BAD_WORLD -> R.string.indoor_render_fallback_bad_world
    RenderFailure.BAD_ASSETS -> R.string.indoor_render_fallback_bad_assets
    RenderFailure.OUT_OF_MEMORY -> R.string.indoor_render_fallback_memory
    RenderFailure.SURFACE_LOST -> R.string.indoor_render_fallback_surface
    RenderFailure.INTERNAL -> R.string.indoor_render_fallback_internal
}

/** Hoehenprofil des Bands mit Punkt an der Stelle [distM] (fortlaufend, wird auf das Band gefaltet). */
@Composable
internal fun ProfileWithPosition(track: WorldTrack, distM: Double, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.18f)
    val dot = MaterialTheme.colorScheme.onSurface
    val km = LocalAppFormats.current.decimal(track.fold(distM) / 1000.0, 1)
    val description = stringResource(R.string.indoor_render_fallback_profile_description, km)
    val profile = remember(track) { ProfileGeometry.of(track) }
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || profile.count < 2) return@Canvas
        val pad = 8.dp.toPx()
        fun x(d: Double) = (d / profile.lengthM).toFloat().coerceIn(0f, 1f) * w
        fun y(u: Double) = pad + (h - 2 * pad) * (1f - profile.norm(u))
        val path = Path()
        val area = Path()
        for (i in 0 until profile.count) {
            val px = x(profile.dist[i])
            val py = y(profile.u[i])
            if (i == 0) {
                path.moveTo(px, py)
                area.moveTo(px, h)
                area.lineTo(px, py)
            } else {
                path.lineTo(px, py)
                area.lineTo(px, py)
            }
        }
        area.lineTo(x(profile.dist[profile.count - 1]), h)
        area.close()
        drawPath(area, fill)
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        val d = track.fold(distM)
        val p = track.poseAt(d)
        drawCircle(dot, radius = 6.dp.toPx(), center = Offset(x(d), y(p.u)))
        drawCircle(line, radius = 4.dp.toPx(), center = Offset(x(d), y(p.u)))
    }
}

/** Ausgeduennte Probe des Bands fuer das Profil (hoechstens rund 600 Punkte). */
internal class ProfileGeometry(val dist: DoubleArray, val u: DoubleArray, val lengthM: Double) {
    val count: Int get() = dist.size
    private val lo = u.minOrNull() ?: 0.0
    private val span = maxOf((u.maxOrNull() ?: 0.0) - lo, 20.0)
    private val mid = lo + ((u.maxOrNull() ?: 0.0) - lo) / 2

    /** Hoehe auf 0..1, mindestens 20 m Spanne (flache Strecken nicht wie Gebirge). */
    fun norm(v: Double): Float = (((v - (mid - span / 2)) / span).toFloat()).coerceIn(0f, 1f)

    companion object {
        fun of(t: WorldTrack, maxPoints: Int = 600): ProfileGeometry {
            val n = t.sampleCount
            val stride = maxOf(1, n / maxPoints)
            val idx = (0 until n step stride).toMutableList()
            if (idx.last() != n - 1) idx += n - 1
            return ProfileGeometry(
                DoubleArray(idx.size) { t.distAt(idx[it]) },
                DoubleArray(idx.size) { t.u[idx[it]].toDouble() },
                t.lengthM,
            )
        }
    }
}
