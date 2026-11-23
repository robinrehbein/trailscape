package de.trailscape.app.indoor.render

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.core.indoor.world.ActorState
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderDeviceInfo
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.RenderSettings
import de.trailscape.core.indoor.world.RenderStats
import de.trailscape.core.indoor.world.SkySetting
import de.trailscape.core.indoor.world.WorldRendererListener
import de.trailscape.core.indoor.world.WorldState
import de.trailscape.core.indoor.world.createWorldBuilder
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Was die Diagnoseseite gerade zeigt. */
private enum class DiagScene { NONE, TEST_SCENE, TEST_WORLD }

/** Zustand der Darstellung, wie ihn die Seite aus den Ereignissen kennt. */
private sealed class DiagStatus {
    data object Idle : DiagStatus()
    data class Loading(val loaded: Int, val needed: Int) : DiagStatus()
    data object Ready : DiagStatus()
    data class Failed(val failure: RenderFailure, val detail: String) : DiagStatus()
}

/**
 * Die Diagnoseseite (docs/indoor.md 7.6 „FPS und Diagnose“): Geraet und
 * Treiber, Messwerte, jeder Schalter aus [RenderSettings] einzeln,
 * Voreinstellungen je Qualitaet und `SAFE`, die Testszene (ohne Dateien)
 * und die Testwelt aus dem Weltbau, Kamera, Tageszeit und eine
 * Probefahrt ohne Trainer. Eigene Darstellung, beim Verlassen zerstoert.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiagnosticsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Neu starten = neue Darstellung (nach einem Ausfall ist eine Darstellung verbraucht).
    var generation by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<DiagStatus>(DiagStatus.Idle) }
    var tileFailures by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(RenderSettings.forQuality(IndoorQuality.MEDIUM)) }
    var camera by remember { mutableStateOf(CameraMode.CHASE) }
    var hour by remember { mutableDoubleStateOf(SkySetting.DEFAULT.hourOfDay) }
    var riding by remember { mutableStateOf(false) }
    var scene by remember { mutableStateOf(DiagScene.NONE) }
    var testWorldNote by remember { mutableStateOf<String?>(null) }
    var testWorldBusy by remember { mutableStateOf(false) }

    val renderer = remember(generation) { FilamentWorldRenderer(context) }
    DisposableEffect(renderer) {
        renderer.setListener(object : WorldRendererListener {
            override fun onReady(info: RenderDeviceInfo) {
                status = DiagStatus.Ready
            }

            override fun onLoading(loadedTiles: Int, neededTiles: Int) {
                if (status !is DiagStatus.Ready && status !is DiagStatus.Failed) status = DiagStatus.Loading(loadedTiles, neededTiles)
            }

            override fun onTileFailed(tileId: String, detail: String) {
                tileFailures++
            }

            override fun onFailure(failure: RenderFailure, detail: String) {
                status = DiagStatus.Failed(failure, detail)
            }

            override fun onStats(stats: RenderStats) = Unit
        })
        renderer.setSettings(settings)
        renderer.setCamera(camera)
        renderer.setSky(SkySetting.DEFAULT.copy(hourOfDay = hour))
        onDispose {
            renderer.setListener(null)
            renderer.destroy()
        }
    }

    // Probefahrt: ein gleichmaessiger Fahrer ohne Trainer (8 m/s, 85 U/min).
    LaunchedEffect(renderer, riding) {
        var dist = renderer.shownDistM
        var last = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val speed = if (riding) 8.0 else 0.0
            dist += speed * (now - last) / 1000.0
            last = now
            renderer.setState(
                WorldState(
                    timeMs = now,
                    rider = ActorState(dist, speed, if (riding) 85.0 else 0.0),
                    ghost = if (riding) ActorState(dist + 12.0, speed, 80.0) else null,
                    paused = !riding,
                ),
            )
            delay(66)
        }
    }

    fun start(which: DiagScene) {
        scene = which
        status = DiagStatus.Idle
        tileFailures = 0
        when (which) {
            DiagScene.NONE -> renderer.unloadWorld()
            DiagScene.TEST_SCENE -> renderer.loadTestScene()
            DiagScene.TEST_WORLD -> {
                testWorldBusy = true
                testWorldNote = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = File(context.cacheDir, "indoor/render-testwelt")
                            dir.deleteRecursively()
                            dir.mkdirs()
                            dir to createWorldBuilder().buildTestWorld(dir, IndoorQuality.MEDIUM)
                        }
                    }
                    testWorldBusy = false
                    result.fold(
                        onSuccess = { (dir, manifest) -> renderer.loadWorld(dir, manifest) },
                        onFailure = { e -> testWorldNote = "${e.javaClass.simpleName}: ${e.message ?: ""}" },
                    )
                }
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(
                title = stringResource(R.string.indoor_render_diag_title),
                backLabel = stringResource(R.string.indoor_render_diag_back),
                onBack = onBack,
            )
            Text(
                stringResource(R.string.indoor_render_diag_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Vorschau
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                if (scene == DiagScene.NONE) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                stringResource(R.string.indoor_render_diag_preview_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                } else {
                    FilamentSurface(renderer = renderer, modifier = Modifier.fillMaxSize())
                }
            }
            Text(statusText(status, tileFailures), style = MaterialTheme.typography.bodyMedium)
            testWorldNote?.let {
                Text(
                    stringResource(R.string.indoor_render_diag_testworld_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { start(DiagScene.TEST_SCENE) }) { Text(stringResource(R.string.indoor_render_diag_test_scene)) }
                OutlinedButton(onClick = { start(DiagScene.TEST_WORLD) }, enabled = !testWorldBusy) {
                    Text(stringResource(if (testWorldBusy) R.string.indoor_render_diag_testworld_busy else R.string.indoor_render_diag_test_world))
                }
                OutlinedButton(onClick = { riding = !riding }, enabled = scene != DiagScene.NONE) {
                    Text(stringResource(if (riding) R.string.indoor_render_diag_ride_stop else R.string.indoor_render_diag_ride_start))
                }
                if (status is DiagStatus.Failed) {
                    OutlinedButton(onClick = {
                        val again = scene
                        generation++
                        status = DiagStatus.Idle
                        scene = DiagScene.NONE
                        if (again != DiagScene.NONE) scope.launch { delay(50); start(again) }
                    }) { Text(stringResource(R.string.indoor_render_diag_restart)) }
                }
            }

            // Kamera
            Eyebrow(stringResource(R.string.indoor_render_diag_camera))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraMode.entries.forEach { m ->
                    FilterChip(
                        selected = camera == m,
                        onClick = {
                            camera = m
                            renderer.setCamera(m)
                        },
                        label = { Text(stringResource(cameraLabel(m))) },
                    )
                }
            }

            // Tageszeit
            Eyebrow(stringResource(R.string.indoor_render_diag_time, formatHour(hour)))
            Slider(
                value = hour.toFloat(),
                onValueChange = {
                    hour = it.toDouble()
                    renderer.setSky(SkySetting.DEFAULT.copy(hourOfDay = hour))
                },
                valueRange = 5f..21f,
            )

            // Messwerte und Geraet
            Eyebrow(stringResource(R.string.indoor_render_diag_stats))
            InfoCard(rows = statsRows(renderer.lastStats))
            Eyebrow(stringResource(R.string.indoor_render_diag_device))
            InfoCard(rows = deviceRows(renderer.info))

            // Voreinstellungen
            Eyebrow(stringResource(R.string.indoor_render_diag_presets))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    R.string.indoor_render_diag_preset_low to RenderSettings.forQuality(IndoorQuality.LOW),
                    R.string.indoor_render_diag_preset_medium to RenderSettings.forQuality(IndoorQuality.MEDIUM),
                    R.string.indoor_render_diag_preset_high to RenderSettings.forQuality(IndoorQuality.HIGH),
                    R.string.indoor_render_diag_preset_safe to RenderSettings.SAFE,
                ).forEach { (label, preset) ->
                    FilterChip(
                        selected = settings == preset,
                        onClick = {
                            settings = preset
                            renderer.setSettings(preset)
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }

            // Schalter
            Eyebrow(stringResource(R.string.indoor_render_diag_switches))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    settingSwitches(settings).forEach { (label, value, change) ->
                        SwitchRow(label = stringResource(label), checked = value) { on ->
                            settings = change(on)
                            renderer.setSettings(settings)
                        }
                    }
                    SwitchRow(label = stringResource(R.string.indoor_render_diag_switch_fps30), checked = settings.maxFps <= 30) { on ->
                        settings = settings.copy(maxFps = if (on) 30 else 60)
                        renderer.setSettings(settings)
                    }
                    Text(
                        stringResource(R.string.indoor_render_diag_resolution, (settings.resolutionScale * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.resolutionScale.toFloat(),
                        onValueChange = { settings = settings.copy(resolutionScale = (it * 20).toInt() / 20.0) },
                        onValueChangeFinished = { renderer.setSettings(settings) },
                        valueRange = 0.5f..1f,
                    )
                }
            }

            // Ausfalldetails (Diagnose, deutsch, fuer Fehlerberichte)
            (status as? DiagStatus.Failed)?.let { f ->
                Eyebrow(stringResource(R.string.indoor_render_diag_failure_detail))
                Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun InfoCard(rows: List<Pair<Int, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        value.ifBlank { stringResource(R.string.indoor_render_diag_unknown) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun statusText(s: DiagStatus, tileFailures: Int): String {
    val base = when (s) {
        DiagStatus.Idle -> stringResource(R.string.indoor_render_diag_status_idle)
        is DiagStatus.Loading -> stringResource(R.string.indoor_render_diag_status_loading, s.loaded, s.needed)
        DiagStatus.Ready -> stringResource(R.string.indoor_render_diag_status_ready)
        is DiagStatus.Failed -> stringResource(R.string.indoor_render_diag_status_failed, stringResource(failureHint(s.failure)))
    }
    return if (tileFailures > 0) base + " " + pluralStringResource(R.plurals.indoor_render_diag_status_tile_failures, tileFailures, tileFailures) else base
}

private fun cameraLabel(m: CameraMode): Int = when (m) {
    CameraMode.CHASE -> R.string.indoor_render_diag_camera_chase
    CameraMode.HANDLEBAR -> R.string.indoor_render_diag_camera_handlebar
    CameraMode.DRONE -> R.string.indoor_render_diag_camera_drone
}

private fun formatHour(h: Double): String {
    val hh = h.toInt().coerceIn(0, 23)
    val mm = ((h - hh) * 60).toInt().coerceIn(0, 59)
    return String.format(Locale.ROOT, "%02d:%02d", hh, mm)
}

private fun statsRows(s: RenderStats?): List<Pair<Int, String>> = listOf(
    R.string.indoor_render_diag_stat_fps to (s?.let { String.format(Locale.ROOT, "%.0f", it.fps) } ?: ""),
    R.string.indoor_render_diag_stat_frame to (s?.let { String.format(Locale.ROOT, "%.1f ms", it.frameMsP95) } ?: ""),
    R.string.indoor_render_diag_stat_scale to (s?.let { "${(it.resolutionScale * 100).toInt()} %" } ?: ""),
    R.string.indoor_render_diag_stat_tiles to (s?.let { "${it.loadedTiles} / ${it.loadedTiles + it.pendingTiles}" } ?: ""),
    R.string.indoor_render_diag_stat_texture to (s?.let { String.format(Locale.ROOT, "%.0f MB", it.textureBytes / 1e6) } ?: ""),
    R.string.indoor_render_diag_stat_triangles to (s?.let { "${it.triangles}" } ?: ""),
)

private fun deviceRows(i: RenderDeviceInfo?): List<Pair<Int, String>> = listOf(
    R.string.indoor_render_diag_dev_model to (i?.deviceModel ?: ""),
    R.string.indoor_render_diag_dev_android to (i?.androidVersion ?: ""),
    R.string.indoor_render_diag_dev_backend to (i?.backend ?: ""),
    R.string.indoor_render_diag_dev_gl_vendor to (i?.glVendor ?: ""),
    R.string.indoor_render_diag_dev_gl_renderer to (i?.glRenderer ?: ""),
    R.string.indoor_render_diag_dev_gl_version to (i?.glVersion ?: ""),
    R.string.indoor_render_diag_dev_filament to (i?.filamentVersion ?: FilamentCore.FILAMENT_VERSION),
)

/** Je Schalter: Beschriftung, Wert, neue Einstellungen fuer einen neuen Wert. */
private fun settingSwitches(s: RenderSettings): List<Triple<Int, Boolean, (Boolean) -> RenderSettings>> = listOf(
    Triple(R.string.indoor_render_diag_switch_shadows, s.shadows) { v -> s.copy(shadows = v) },
    Triple(R.string.indoor_render_diag_switch_ao, s.ambientOcclusion) { v -> s.copy(ambientOcclusion = v) },
    Triple(R.string.indoor_render_diag_switch_bloom, s.bloom) { v -> s.copy(bloom = v) },
    Triple(R.string.indoor_render_diag_switch_fog, s.fog) { v -> s.copy(fog = v) },
    Triple(R.string.indoor_render_diag_switch_aa, s.antiAliasing) { v -> s.copy(antiAliasing = v) },
    Triple(R.string.indoor_render_diag_switch_dynres, s.dynamicResolution) { v -> s.copy(dynamicResolution = v) },
    Triple(R.string.indoor_render_diag_switch_post, s.postProcessing) { v -> s.copy(postProcessing = v) },
    Triple(R.string.indoor_render_diag_switch_sky, s.sky) { v -> s.copy(sky = v) },
    Triple(R.string.indoor_render_diag_switch_trees, s.trees) { v -> s.copy(trees = v) },
    Triple(R.string.indoor_render_diag_switch_buildings, s.buildings) { v -> s.copy(buildings = v) },
    Triple(R.string.indoor_render_diag_switch_props, s.props) { v -> s.copy(props = v) },
)
