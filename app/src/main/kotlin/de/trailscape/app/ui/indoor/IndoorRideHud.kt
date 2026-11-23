package de.trailscape.app.ui.indoor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.theme.DarkSignalColors
import de.trailscape.core.indoor.GhostState
import de.trailscape.core.indoor.IndoorSessionState
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Farben und Schrift
// ---------------------------------------------------------------------------

/**
 * Farben des Fahr-HUD. Immer helle Schrift auf dunklem, halbdurchsichtigem
 * Grund — hinter dem HUD liegt die 3D-Welt, oben meist heller Himmel. Ohne
 * Welt liegt ein dunkler Hintergrund dahinter, damit dieselben Farben gelten.
 */
internal object HudColors {
    val ink = Color.White
    val inkMuted = Color.White.copy(alpha = 0.78f)
    val panel = Color.Black.copy(alpha = 0.40f)
    val panelStrong = Color.Black.copy(alpha = 0.56f)
    val outline = Color.White.copy(alpha = 0.22f)
    val accent = DarkSignalColors.good
    val ghost = DarkSignalColors.accentBlue
    val warning = DarkSignalColors.warning
    val finish = Color(0xFFD93025)

    /** Oben: kraeftig hinter den Zahlen, nach unten auslaufend. */
    val topScrim = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.62f),
        0.6f to Color.Black.copy(alpha = 0.40f),
        1f to Color.Transparent,
    )

    /** Unten: spiegelbildlich, hinter Gang und Hoehenprofil. */
    val bottomScrim = Brush.verticalGradient(
        0f to Color.Transparent,
        0.35f to Color.Black.copy(alpha = 0.38f),
        1f to Color.Black.copy(alpha = 0.62f),
    )

    /** Ohne 3D-Welt: ruhiger dunkler Grund. */
    val backdrop = Brush.verticalGradient(listOf(Color(0xFF1C2630), Color(0xFF0D1217)))

    val profile = IndoorProfileColors(
        line = Color.White.copy(alpha = 0.70f),
        fill = Color.White.copy(alpha = 0.14f),
        riddenLine = DarkSignalColors.good,
        riddenFill = DarkSignalColors.good.copy(alpha = 0.38f),
        rider = DarkSignalColors.good,
        riderRing = Color.White,
        ghost = DarkSignalColors.accentBlue,
        baseline = Color.White.copy(alpha = 0.25f),
    )
}

/** Schatten unter der Schrift: lesbar auch dort, wo der Schleier duenn ist. */
private val HudShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 6f)

/** Schrift des HUD: weiss, mit Schatten, Ziffern gleich breit (springen nicht). */
@Composable
internal fun hudStyle(base: TextStyle, color: Color = HudColors.ink): TextStyle =
    base.copy(color = color, shadow = HudShadow, fontFeatureSettings = "tnum")

// ---------------------------------------------------------------------------
// Kernwerte oben
// ---------------------------------------------------------------------------

/**
 * Leistung gross und links, daneben Tempo, Trittfrequenz und Puls kompakt.
 * Die Zahlen passen ihre Groesse dem Platz an ([TextAutoSize]) — so bricht
 * auch grosse Schrift oder ein schmales Geraet die Zeile nicht.
 */
@Composable
internal fun HudMetrics(session: IndoorSessionState, powerColor: Color, modifier: Modifier = Modifier) {
    val formats = LocalAppFormats.current
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val powerLabel = stringResource(R.string.indoor_app_hud_power)
        val watts = stringResource(R.string.indoor_app_unit_watts)
        val power = formatIndoorInt(session.powerW)
        val powerCd = stringResource(R.string.indoor_app_hud_metric_cd, powerLabel, power, watts)
        BasicText(
            text = buildAnnotatedString {
                append(power)
                withStyle(SpanStyle(fontSize = 0.36.em, fontWeight = FontWeight.Medium)) { append(" $watts") }
            },
            style = hudStyle(MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold), powerColor),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 60.sp, stepSize = 2.sp),
            modifier = Modifier
                .weight(1.15f)
                .clearAndSetSemantics { contentDescription = powerCd },
        )
        Spacer(modifier = Modifier.width(8.dp))
        SmallMetric(
            label = stringResource(R.string.indoor_app_hud_speed),
            value = formats.indoorSpeedKmh(session.speedMps),
            unit = stringResource(R.string.indoor_app_unit_kmh),
            modifier = Modifier.weight(1f),
        )
        SmallMetric(
            label = stringResource(R.string.indoor_app_hud_cadence),
            value = formatIndoorInt(session.cadenceRpm),
            unit = stringResource(R.string.indoor_app_unit_rpm),
            modifier = Modifier.weight(0.85f),
        )
        SmallMetric(
            label = stringResource(R.string.indoor_app_hud_heart_rate),
            value = formatIndoorInt(session.hrBpm),
            unit = stringResource(R.string.indoor_app_unit_bpm),
            modifier = Modifier.weight(0.85f),
        )
    }
}

@Composable
private fun SmallMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.indoor_app_hud_metric_cd, label, value, unit)
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = cd },
        horizontalAlignment = Alignment.End,
    ) {
        BasicText(
            text = value,
            style = hudStyle(MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold)),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 28.sp, stepSize = 1.sp),
        )
        Text(
            text = unit,
            style = hudStyle(MaterialTheme.typography.labelMedium, HudColors.inkMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Farbe der Steigung: flach weiss, bergab blau, bergauf von gruen ueber gelb und orange nach rot. */
internal fun gradeColor(gradePct: Double): Color = when {
    abs(gradePct) < 1.0 -> HudColors.ink
    gradePct < 0.0 -> DarkSignalColors.accentBlue
    gradePct < 4.0 -> DarkSignalColors.good
    gradePct < 7.0 -> DarkSignalColors.caution
    gradePct < 10.0 -> DarkSignalColors.warning
    else -> DarkSignalColors.danger
}

/** Steigung (farbig, mit Pfeil) und Belag in einer kleinen Zeile. */
@Composable
internal fun HudGradeLine(session: IndoorSessionState, modifier: Modifier = Modifier) {
    val formats = LocalAppFormats.current
    val grade = formats.indoorGrade(session.gradePct)
    val percent = stringResource(R.string.indoor_app_unit_percent)
    val gradeLabel = stringResource(R.string.indoor_app_hud_grade)
    val surfaceLabelText = stringResource(R.string.indoor_app_hud_surface)
    val surface = surfaceLabel(session.surface)
    val color = gradeColor(session.gradePct)
    val icon = when {
        abs(session.gradePct) < 1.0 -> Icons.AutoMirrored.Filled.TrendingFlat
        session.gradePct > 0 -> Icons.AutoMirrored.Filled.TrendingUp
        else -> Icons.AutoMirrored.Filled.TrendingDown
    }
    val cd = "${stringResource(R.string.indoor_app_hud_metric_cd, gradeLabel, grade, percent)}, $surfaceLabelText: $surface"
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$grade $percent",
            style = hudStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = "  ·  $surface",
            style = hudStyle(MaterialTheme.typography.titleSmall, HudColors.inkMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Kleiner runder Hintergrund fuer Zeilen im HUD. */
@Composable
internal fun HudChip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = HudColors.panel, shape = CircleShape, modifier = modifier) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { content() }
    }
}

/** Abstand zum Geist: Punkt in dessen Farbe (wie im Hoehenprofil) und ein kurzer Satz. */
@Composable
internal fun HudGhostChip(ghost: GhostState, label: String?, modifier: Modifier = Modifier) {
    val name = label ?: stringResource(R.string.indoor_app_hud_ghost_default)
    val ahead = ghost.gapM >= 0.0
    val gapS = ghost.gapS
    val text = if (gapS != null) {
        val time = formatIndoorGapSeconds(gapS)
        stringResource(if (ahead) R.string.indoor_app_hud_ghost_ahead else R.string.indoor_app_hud_ghost_behind, name, time)
    } else {
        val meters = indoorGapMeters(ghost.gapM)
        stringResource(if (ahead) R.string.indoor_app_hud_ghost_ahead_m else R.string.indoor_app_hud_ghost_behind_m, name, meters)
    }
    HudChip(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).background(HudColors.ghost, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = hudStyle(MaterialTheme.typography.labelLarge),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Fortschritt beim Laden der Welt. */
@Composable
internal fun HudLoadingChip(loading: IndoorRenderUi.Loading, modifier: Modifier = Modifier) {
    Surface(color = HudColors.panel, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = if (loading.neededTiles > 0) {
                    stringResource(R.string.indoor_app_ride_world_loading_count, loading.loadedTiles, loading.neededTiles)
                } else {
                    stringResource(R.string.indoor_app_ride_world_loading)
                },
                style = hudStyle(MaterialTheme.typography.labelMedium),
            )
            if (loading.neededTiles > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (loading.loadedTiles.toFloat() / loading.neededTiles).coerceIn(0f, 1f) },
                    color = HudColors.ink,
                    trackColor = HudColors.outline,
                    modifier = Modifier.widthIn(min = 160.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Unten: Schalten / Zielleistung, Hoehenprofil
// ---------------------------------------------------------------------------

/** Mindestgroesse der Schalt-Tasten: sie sind der Ersatz fuer die Schalthebel. */
internal val HudStepButtonSize = 64.dp

/**
 * Eine grosse, runde Taste fuer den Daumen am Bildschirmrand (− / +).
 */
@Composable
internal fun HudStepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = HudColors.panelStrong,
        contentColor = HudColors.ink,
        border = BorderStroke(1.dp, HudColors.outline),
        modifier = modifier
            .size(HudStepButtonSize)
            .alpha(if (enabled) 1f else 0.45f)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}

/** Gang „14 / 24" als kompakte Pille zwischen den Tasten. */
@Composable
internal fun HudGearPill(index: Int, count: Int, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.indoor_app_hud_gear_label)
    val value = stringResource(R.string.indoor_app_hud_gear_value, index, count)
    Surface(
        color = HudColors.panel,
        shape = CircleShape,
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label $value" },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = index.toString(),
                    style = hudStyle(MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)),
                    maxLines = 1,
                )
                Text(
                    text = " / $count",
                    style = hudStyle(MaterialTheme.typography.titleSmall, HudColors.inkMuted),
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = label,
                style = hudStyle(MaterialTheme.typography.labelSmall, HudColors.inkMuted),
                maxLines = 1,
            )
        }
    }
}

/** ERG: Zielleistung, Abschnitt und Restzeit als Pille zwischen den Tasten. */
@Composable
internal fun HudErgPill(title: String?, targetW: Int, remainingS: Int, done: Boolean, modifier: Modifier = Modifier) {
    Surface(color = HudColors.panel, shape = RoundedCornerShape(24.dp), modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                text = title ?: stringResource(R.string.indoor_app_hud_erg_label),
                style = hudStyle(MaterialTheme.typography.labelMedium, HudColors.inkMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.indoor_app_hud_erg_target, targetW),
                style = hudStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)),
                maxLines = 1,
            )
            Text(
                text = if (done) {
                    stringResource(R.string.indoor_app_hud_erg_done)
                } else {
                    stringResource(R.string.indoor_app_hud_erg_remaining, formatIndoorRemaining(remainingS))
                },
                style = hudStyle(MaterialTheme.typography.labelMedium, HudColors.inkMuted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** „4,3 von 12,0 km" links, die Fahrzeit rechts — ueber dem Hoehenprofil. */
@Composable
internal fun HudProfileHeader(ui: IndoorRideUi, session: IndoorSessionState, modifier: Modifier = Modifier) {
    val profile = ui.profile
    val formats = LocalAppFormats.current
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (profile != null) {
            val text = if (profile.isLoop) {
                stringResource(
                    R.string.indoor_app_ride_position_loop,
                    formats.indoorKm(session.routeDistM),
                    formats.indoorKm(profile.lengthM),
                    session.lap,
                )
            } else {
                stringResource(
                    R.string.indoor_app_ride_position,
                    formats.indoorKm(session.routeDistM),
                    formats.indoorKm(profile.lengthM),
                )
            }
            Text(
                text = text,
                style = hudStyle(MaterialTheme.typography.labelLarge),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        val clock = formatIndoorClock(session.elapsedS)
        val clockCd = stringResource(R.string.indoor_app_hud_elapsed_cd, clock)
        Text(
            text = clock,
            style = hudStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)),
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics { contentDescription = clockCd },
        )
    }
}

/** Das Hoehenprofil in HUD-Farben (gefahrener Teil gruen, Geist blau). */
@Composable
internal fun HudProfileCanvas(ui: IndoorRideUi, session: IndoorSessionState, height: Dp, modifier: Modifier = Modifier) {
    val profile = ui.profile ?: return
    val formats = LocalAppFormats.current
    val ghost = session.ghost?.let { routePositionOf(it.distM, profile) }
    IndoorProfileCanvas(
        profile = profile,
        routeDistM = session.routeDistM,
        ghostRouteDistM = ghost,
        description = stringResource(R.string.indoor_app_ride_profile_cd, formats.indoorKm(session.routeDistM)),
        height = height,
        colors = HudColors.profile,
        modifier = modifier,
    )
}

/** Laufmeter des Geists (ueber Runden fortlaufend) auf die Strecke zurueckgefuehrt. */
private fun routePositionOf(distM: Double, profile: IndoorProfileUi): Double =
    if (profile.isLoop && profile.lengthM > 0.0) distM % profile.lengthM else distM.coerceIn(0.0, profile.lengthM)

// ---------------------------------------------------------------------------
// Runde Symbolknoepfe
// ---------------------------------------------------------------------------

/** Groesse der runden Symbolknoepfe (Kamera, Pause, Beenden, Geraete). */
internal val HudRoundButtonSize = 52.dp

@Composable
internal fun HudRoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = HudColors.panelStrong,
    tint: Color = HudColors.ink,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = tint,
        border = BorderStroke(1.dp, HudColors.outline),
        modifier = modifier
            .size(HudRoundButtonSize)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
        }
    }
}

/** So lange muss der Finger auf „Beenden" liegen bleiben (wie [de.trailscape.app.ui.components.HoldToEndButton]). */
private const val HOLD_TO_FINISH_MS = 1100

/** Unterhalb dieses Fortschritts gilt ein Druck als Tipp und zeigt den Hinweis. */
private const val HOLD_SHORT_TAP = 0.15f

/** Wie lange „Zum Beenden gedrückt halten" nach einem Tipp stehen bleibt. */
private const val HOLD_HINT_MS = 1800L

/**
 * „Fahrt beenden" als runder Knopf: Der Finger muss [HOLD_TO_FINISH_MS] lang
 * liegen bleiben, dabei laeuft ein Ring um den Knopf. Ein kurzer Tipp zeigt
 * links daneben „Zum Beenden gedrückt halten". Fuer TalkBack ist es ein
 * gewoehnlicher Knopf — das Doppeltippen ist dort die bewusste Bestaetigung.
 */
@Composable
internal fun HudHoldToFinishButton(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnFinish by rememberUpdatedState(onFinish)
    val progress = remember { Animatable(0f) }
    var showHint by remember { mutableStateOf(false) }
    val cd = stringResource(R.string.indoor_app_hud_finish_cd)

    LaunchedEffect(showHint) {
        if (showHint) {
            delay(HOLD_HINT_MS)
            showHint = false
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showHint) {
            HudChip(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = stringResource(R.string.indoor_app_hud_finish_hold_hint),
                    style = hudStyle(MaterialTheme.typography.labelLarge),
                    maxLines = 1,
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(HudRoundButtonSize)
                .background(HudColors.finish.copy(alpha = 0.92f), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            showHint = false
                            val hold = scope.launch {
                                progress.animateTo(1f, tween(HOLD_TO_FINISH_MS, easing = LinearEasing))
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnFinish()
                                progress.snapTo(0f)
                            }
                            tryAwaitRelease()
                            if (hold.isActive) {
                                hold.cancel()
                                if (progress.value < HOLD_SHORT_TAP) showHint = true
                                scope.launch { progress.animateTo(0f, tween(150)) }
                            }
                        },
                    )
                }
                .semantics {
                    role = Role.Button
                    contentDescription = cd
                    onClick(label = cd) {
                        currentOnFinish()
                        true
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                drawArc(
                    color = Color.White.copy(alpha = 0.35f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = 1.dp.toPx()),
                )
                if (progress.value > 0f) {
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.value,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Demo-Trainer
// ---------------------------------------------------------------------------

/** Reglerbereiche des Demo-Trainers. */
private const val DEMO_POWER_MAX_W = 600
private const val DEMO_CADENCE_MAX_RPM = 130

/** Regler des Demo-Trainers: Leistung 0..600 W, Trittfrequenz 0..130. */
@Composable
internal fun HudDemoSliders(
    powerW: Int?,
    cadenceRpm: Int?,
    onDemoPower: (Int) -> Unit,
    onDemoCadence: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = HudColors.panelStrong, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            powerW?.let { power ->
                DemoSliderRow(
                    label = stringResource(R.string.indoor_app_demo_power, power),
                    value = power,
                    max = DEMO_POWER_MAX_W,
                    description = stringResource(R.string.indoor_app_demo_power_cd),
                    onChange = onDemoPower,
                )
            }
            cadenceRpm?.let { cadence ->
                DemoSliderRow(
                    label = stringResource(R.string.indoor_app_demo_cadence, cadence),
                    value = cadence,
                    max = DEMO_CADENCE_MAX_RPM,
                    description = stringResource(R.string.indoor_app_demo_cadence_cd),
                    onChange = onDemoCadence,
                )
            }
        }
    }
}

@Composable
private fun DemoSliderRow(label: String, value: Int, max: Int, description: String, onChange: (Int) -> Unit) {
    Column {
        Text(text = label, style = hudStyle(MaterialTheme.typography.labelMedium, HudColors.inkMuted), maxLines = 1)
        Slider(
            value = value.toFloat().coerceIn(0f, max.toFloat()),
            onValueChange = { onChange(kotlin.math.round(it).toInt()) },
            valueRange = 0f..max.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = HudColors.ink,
                activeTrackColor = HudColors.accent,
                inactiveTrackColor = HudColors.outline,
            ),
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}
