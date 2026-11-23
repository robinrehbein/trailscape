package de.trailscape.app.ui.indoor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R
import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorProblem
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.ShifterConnection
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.WorldBuildFailed
import de.trailscape.core.indoor.world.WorldBuildPhase

/**
 * # Texte zu den Typen des Indoor-Ablaufs
 *
 * Alle Abbildungen Typ → Satz an einer Stelle, damit Ablauf-, Fahr- und
 * Weltbildschirm dieselben Worte benutzen. Diagnosetexte der Teile (`detail`,
 * `reason`) sind Deutsch fuer Fehlerberichte und gehen **nie** in die
 * Oberflaeche — hier steht stattdessen, was der Nutzer tun kann.
 */

/** Belag als Wort („Schotter"). */
@Composable
fun surfaceLabel(kind: SurfaceKind): String = stringResource(
    when (kind) {
        SurfaceKind.ASPHALT -> R.string.indoor_app_surface_asphalt
        SurfaceKind.CONCRETE -> R.string.indoor_app_surface_concrete
        SurfaceKind.PAVING_STONES -> R.string.indoor_app_surface_paving
        SurfaceKind.COBBLESTONE -> R.string.indoor_app_surface_cobblestone
        SurfaceKind.WOOD -> R.string.indoor_app_surface_wood
        SurfaceKind.COMPACTED -> R.string.indoor_app_surface_compacted
        SurfaceKind.GRAVEL -> R.string.indoor_app_surface_gravel
        SurfaceKind.DIRT -> R.string.indoor_app_surface_dirt
        SurfaceKind.GRASS -> R.string.indoor_app_surface_grass
        SurfaceKind.SAND -> R.string.indoor_app_surface_sand
        SurfaceKind.UNKNOWN -> R.string.indoor_app_surface_unknown
    },
)

/** Kameraname. */
@Composable
fun cameraLabel(mode: CameraMode): String = stringResource(
    when (mode) {
        CameraMode.CHASE -> R.string.indoor_app_camera_chase
        CameraMode.HANDLEBAR -> R.string.indoor_app_camera_handlebar
        CameraMode.DRONE -> R.string.indoor_app_camera_drone
    },
)

/** Modus: freie Fahrt (SIM) oder Einheit des Tages (ERG). */
@Composable
fun modeLabel(mode: IndoorMode): String = stringResource(
    when (mode) {
        IndoorMode.SIM -> R.string.indoor_app_mode_sim
        IndoorMode.ERG -> R.string.indoor_app_mode_erg
    },
)

/** Kurzer Zustand der Sitzung, `null` wenn sie normal laeuft. */
@Composable
fun phaseLabel(phase: IndoorPhase): String? = when (phase) {
    IndoorPhase.PAUSED -> stringResource(R.string.indoor_app_phase_paused)
    IndoorPhase.AUTO_PAUSED -> stringResource(R.string.indoor_app_phase_auto_paused)
    IndoorPhase.READY -> stringResource(R.string.indoor_app_phase_ready)
    IndoorPhase.FINISHED -> stringResource(R.string.indoor_app_phase_finished)
    IndoorPhase.RUNNING -> null
}

/** Zustand des Trainers fuer die Statuszeile des HUD. */
@Composable
fun trainerStatusLabel(state: TrainerConnection): String = stringResource(
    when (state) {
        TrainerConnection.OFF -> R.string.indoor_app_trainer_off
        TrainerConnection.CONNECTING -> R.string.indoor_app_trainer_connecting
        TrainerConnection.CONNECTED -> R.string.indoor_app_trainer_connected
        TrainerConnection.READY -> R.string.indoor_app_trainer_ready
        TrainerConnection.NO_PERMISSION -> R.string.indoor_app_trainer_no_permission
        TrainerConnection.BLUETOOTH_OFF -> R.string.indoor_app_trainer_bluetooth_off
        TrainerConnection.UNSUPPORTED -> R.string.indoor_app_trainer_unsupported
    },
)

/** Zustand des Schalttasters fuer die Statuszeile des HUD. */
@Composable
fun shifterStatusLabel(state: ShifterConnection): String = stringResource(
    when (state) {
        ShifterConnection.OFF -> R.string.indoor_app_shifter_off
        ShifterConnection.CONNECTING -> R.string.indoor_app_shifter_connecting
        ShifterConnection.READY -> R.string.indoor_app_shifter_ready
        ShifterConnection.NO_PERMISSION -> R.string.indoor_app_shifter_no_permission
        ShifterConnection.BLUETOOTH_OFF -> R.string.indoor_app_shifter_bluetooth_off
        ShifterConnection.UNSUPPORTED -> R.string.indoor_app_shifter_unsupported
    },
)

/** Ein Hinweis waehrend der Fahrt, als ganzer Satz mit Handlungsanweisung. */
@Composable
fun noticeText(notice: IndoorNotice): String = stringResource(
    when (notice) {
        IndoorNotice.TRAINER_LOST -> R.string.indoor_app_notice_trainer_lost
        IndoorNotice.TRAINER_BACK -> R.string.indoor_app_notice_trainer_back
        IndoorNotice.TRAINER_COMMAND_FAILED -> R.string.indoor_app_notice_trainer_command_failed
        IndoorNotice.TRAINER_UNSUPPORTED -> R.string.indoor_app_notice_trainer_unsupported
        IndoorNotice.SHIFTER_UNSUPPORTED -> R.string.indoor_app_notice_shifter_unsupported
        IndoorNotice.BLUETOOTH_OFF -> R.string.indoor_app_notice_bluetooth_off
        IndoorNotice.NO_PERMISSION -> R.string.indoor_app_notice_no_permission
        IndoorNotice.TILE_FAILED -> R.string.indoor_app_notice_tile_failed
        IndoorNotice.PART_FAILED -> R.string.indoor_app_notice_part_failed
    },
)

/** Gute Nachricht statt Warnung (Trainer wieder da). */
fun IndoorNotice.isGood(): Boolean = this == IndoorNotice.TRAINER_BACK

/** Name des Teils, der ein Problem meldet, fuer die Oberflaeche. */
@Composable
fun partLabel(part: IndoorPart): String = stringResource(
    when (part) {
        IndoorPart.CORE -> R.string.indoor_app_part_core
        IndoorPart.WORLD -> R.string.indoor_app_part_world
        IndoorPart.BLE -> R.string.indoor_app_part_ble
        IndoorPart.RENDER -> R.string.indoor_app_part_render
        IndoorPart.APP -> R.string.indoor_app_part_app
    },
)

/**
 * Verstaendlicher Satz zu einem [IndoorProblem]. Nicht fertige Teile bekommen
 * den festen Satz „Dieser Teil ist in dieser Version noch nicht fertig.";
 * der deutsche `detail`-Text bleibt im Fehlerbericht.
 */
@Composable
fun problemText(problem: IndoorProblem?): String =
    when {
        problem == null -> stringResource(R.string.indoor_app_problem_unknown)
        problem.notImplemented -> stringResource(R.string.indoor_app_problem_not_ready)
        else -> stringResource(R.string.indoor_app_problem_failed, partLabel(problem.part))
    }

/** Grund eines gescheiterten Weltbaus mit dem, was der Nutzer tun kann. */
@Composable
fun worldFailureText(reason: WorldBuildFailed.Reason): String = stringResource(
    when (reason) {
        WorldBuildFailed.Reason.OFFLINE -> R.string.indoor_app_world_failed_offline
        WorldBuildFailed.Reason.SOURCE_ERROR -> R.string.indoor_app_world_failed_source
        WorldBuildFailed.Reason.NO_SPACE -> R.string.indoor_app_world_failed_no_space
        WorldBuildFailed.Reason.ROUTE_UNSUPPORTED -> R.string.indoor_app_world_failed_route
        WorldBuildFailed.Reason.INTERNAL -> R.string.indoor_app_world_failed_internal
    },
)

/** Warum die 3D-Flaeche fehlt; `null` = nicht naeher bekannt. */
@Composable
fun renderFailureText(failure: RenderFailure?): String = stringResource(
    when (failure) {
        RenderFailure.NO_GRAPHICS -> R.string.indoor_app_render_failed_no_graphics
        RenderFailure.BAD_WORLD -> R.string.indoor_app_render_failed_bad_world
        RenderFailure.BAD_ASSETS -> R.string.indoor_app_render_failed_bad_assets
        RenderFailure.OUT_OF_MEMORY -> R.string.indoor_app_render_failed_memory
        RenderFailure.SURFACE_LOST -> R.string.indoor_app_render_failed_surface
        RenderFailure.INTERNAL, null -> R.string.indoor_app_render_failed_internal
    },
)

/** Phase des Weltbaus als Satzteil („Höhendaten laden"). */
@Composable
fun buildPhaseLabel(phase: WorldBuildPhase): String = stringResource(
    when (phase) {
        WorldBuildPhase.ELEVATION -> R.string.indoor_app_build_phase_elevation
        WorldBuildPhase.VECTOR -> R.string.indoor_app_build_phase_vector
        WorldBuildPhase.AERIAL -> R.string.indoor_app_build_phase_aerial
        WorldBuildPhase.MESHING -> R.string.indoor_app_build_phase_meshing
        WorldBuildPhase.WRITING -> R.string.indoor_app_build_phase_writing
    },
)

/** Woher die Belaege der Strecke stammen. */
@Composable
fun routeSourceLabel(source: IndoorRouteSource): String = stringResource(
    when (source) {
        IndoorRouteSource.BROUTER_WAY_TAGS -> R.string.indoor_app_surface_source_waytags
        IndoorRouteSource.BROUTER_REMATCH -> R.string.indoor_app_surface_source_rematch
        IndoorRouteSource.VECTOR_TILES -> R.string.indoor_app_surface_source_vector
        IndoorRouteSource.NONE -> R.string.indoor_app_surface_source_none
    },
)

/** Art der Quelle in den Quellenangaben („Höhendaten"). */
@Composable
fun geoSourceLabel(kind: GeoSourceKind): String = stringResource(
    when (kind) {
        GeoSourceKind.ELEVATION -> R.string.indoor_app_source_elevation
        GeoSourceKind.VECTOR -> R.string.indoor_app_source_vector
        GeoSourceKind.AERIAL -> R.string.indoor_app_source_aerial
    },
)

/** Qualitaetsstufe der Welt als Wort. */
@Composable
fun qualityLabel(quality: IndoorQuality): String = stringResource(
    when (quality) {
        IndoorQuality.LOW -> R.string.indoor_app_quality_low
        IndoorQuality.MEDIUM -> R.string.indoor_app_quality_medium
        IndoorQuality.HIGH -> R.string.indoor_app_quality_high
    },
)
