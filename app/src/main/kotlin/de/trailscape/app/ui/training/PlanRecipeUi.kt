package de.trailscape.app.ui.training

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.UiText
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.EftpEstimate
import de.trailscape.core.EftpSource
import de.trailscape.core.PlanRecipe
import de.trailscape.core.PlanRecipePlan
import de.trailscape.core.PlanRecipePreview
import de.trailscape.core.PlanRecipeWorkout
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.nextMondayOnOrAfter
import de.trailscape.core.planFromRecipe
import de.trailscape.core.planWeekdayCodes
import de.trailscape.core.planFromWorkout
import de.trailscape.core.planRecipeFileName
import de.trailscape.core.planWithInsertedWorkout
import de.trailscape.core.previewRecipe
import de.trailscape.core.recipeFromPlan
import de.trailscape.core.recipeFromSession
import de.trailscape.core.typicalAvgSpeedKmh
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Plan-Rezepte in der Oberflaeche (Format: `docs/plan-rezepte.md`):
 *
 *  * [PlanRecipeSection] — die Karte auf der Plan-Seite: Rezept oeffnen, Plan
 *    und Einheit teilen bzw. als Datei speichern;
 *  * [PlanRecipeImportHost] — Vorschau und ausdrueckliche Bestaetigung eines
 *    geoeffneten Rezepts (aus dem Dateiwaehler oder per „Oeffnen mit");
 *  * zustandslose Bausteine ([PlanRecipeCard], [PlanRecipePreviewBody]) fuer die
 *    Screenshot-Tests.
 *
 * Es wird nie etwas uebernommen, bevor „Uebernehmen" getippt wurde; ein
 * vorhandener Plan wird dabei ausdruecklich als „wird ersetzt" benannt.
 */

// ---------------------------------------------------------------------------
// Karte auf der Plan-Seite
// ---------------------------------------------------------------------------

/** Zustandslose Karte: Erklaerung, Herkunft eines importierten Plans und die Knoepfe. */
@Composable
fun PlanRecipeCard(
    plan: TrainingPlan?,
    canExport: Boolean,
    onOpen: () -> Unit,
    onSharePlan: () -> Unit,
    onShareSession: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.recipe_card_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.recipe_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan?.recipe?.let { origin ->
                Text(
                    text = origin.author
                        ?.let { stringResource(R.string.recipe_card_source_author, origin.title, it) }
                        ?: stringResource(R.string.recipe_card_source, origin.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NeutralButton(onClick = onOpen) { Text(stringResource(R.string.recipe_action_open)) }
                if (canExport) {
                    NeutralButton(onClick = onSharePlan) { Text(stringResource(R.string.recipe_action_share_plan)) }
                    NeutralButton(onClick = onShareSession) { Text(stringResource(R.string.recipe_action_share_session)) }
                }
            }
        }
    }
}

/** Was gerade exportiert wird: der ganze Plan oder eine Einheit. */
private sealed interface ExportTarget {
    data object WholePlan : ExportTarget
    data class OneSession(val session: TrainingSession) : ExportTarget
}

/**
 * Die Karte samt Dateiwaehler, Export-Dialogen und Einheiten-Auswahl.
 * [plan] ist der gespeicherte Plan (`null` ohne Plan).
 */
@Composable
fun PlanRecipeSection(appViewModel: AppViewModel, plan: TrainingPlan?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coreTexts = LocalCoreTexts.current

    var pickSession by rememberSaveable { mutableStateOf(false) }
    var target by remember { mutableStateOf<ExportTarget?>(null) }
    var pendingSave by remember { mutableStateOf<PlanRecipe?>(null) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { PendingPlanRecipe.offer(readRecipeFile(context, uri)) }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val recipe = pendingSave
        pendingSave = null
        if (uri == null || recipe == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                writeRecipeFile(context, uri, recipe)
                appViewModel.showMessage(UiText.Res(R.string.recipe_export_saved_status))
            } catch (_: Exception) {
                appViewModel.showMessage(UiText.Res(R.string.recipe_export_write_error))
            }
        }
    }

    val exportableSessions = remember(plan) {
        plan?.let { p ->
            val week = p.weeks.getOrNull(currentWeekIndex(p))
            week?.sessions.orEmpty().filter { recipeFromSession(it, null, null, coreTexts) != null }
        }.orEmpty()
    }

    PlanRecipeCard(
        plan = plan,
        canExport = plan != null,
        onOpen = { openLauncher.launch(arrayOf("*/*")) },
        onSharePlan = { target = ExportTarget.WholePlan },
        onShareSession = {
            if (exportableSessions.isEmpty()) {
                appViewModel.showMessage(UiText.Res(R.string.recipe_export_nothing_error))
            } else {
                pickSession = true
            }
        },
    )

    if (pickSession) {
        OneUiDialog(
            onDismissRequest = { pickSession = false },
            title = { Text(stringResource(R.string.recipe_export_pick_session_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    exportableSessions.forEach { session ->
                        val label = stringResource(
                            R.string.recipe_export_session_option,
                            coreTexts.format.weekdayLong(session.day),
                            de.trailscape.core.i18n.sessionTitle(session, coreTexts),
                        )
                        TextButton(
                            onClick = {
                                pickSession = false
                                target = ExportTarget.OneSession(session)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickSession = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }

    val current = target
    if (current != null && plan != null) {
        val defaultTitle = when (current) {
            ExportTarget.WholePlan -> plan.recipe?.title ?: stringResource(R.string.recipe_default_plan_title)
            is ExportTarget.OneSession -> de.trailscape.core.i18n.sessionTitle(current.session, coreTexts)
        }
        PlanRecipeExportDialog(
            isPlan = current == ExportTarget.WholePlan,
            defaultTitle = defaultTitle,
            defaultAuthor = plan.recipe?.author.orEmpty().takeIf { current == ExportTarget.WholePlan }.orEmpty(),
            onDismiss = { target = null },
            onShare = { title, author ->
                target = null
                val recipe = buildRecipe(plan, current, title, author, coreTexts)
                if (recipe == null) {
                    appViewModel.showMessage(UiText.Res(R.string.recipe_export_nothing_error))
                } else {
                    scope.launch {
                        try {
                            shareRecipe(context, recipe)
                        } catch (_: Exception) {
                            appViewModel.showMessage(UiText.Res(R.string.recipe_export_write_error))
                        }
                    }
                }
            },
            onSave = { title, author ->
                target = null
                val recipe = buildRecipe(plan, current, title, author, coreTexts)
                if (recipe == null) {
                    appViewModel.showMessage(UiText.Res(R.string.recipe_export_nothing_error))
                } else {
                    pendingSave = recipe
                    saveLauncher.launch(planRecipeFileName(recipe.title))
                }
            },
        )
    }
}

private fun buildRecipe(
    plan: TrainingPlan,
    target: ExportTarget,
    title: String,
    author: String,
    texts: de.trailscape.core.i18n.CoreTexts,
): PlanRecipe? = when (target) {
    ExportTarget.WholePlan -> recipeFromPlan(plan, title, author, texts)
    is ExportTarget.OneSession -> recipeFromSession(target.session, title, author, texts)
}

/** Titel und (freiwillig) Autor abfragen, dazu der Datenschutz-Satz; Teilen oder als Datei speichern. */
@Composable
fun PlanRecipeExportDialog(
    isPlan: Boolean,
    defaultTitle: String,
    defaultAuthor: String,
    onDismiss: () -> Unit,
    onShare: (title: String, author: String) -> Unit,
    onSave: (title: String, author: String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var author by rememberSaveable { mutableStateOf(defaultAuthor) }
    OneUiDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (isPlan) R.string.recipe_export_title_plan else R.string.recipe_export_title_session))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.recipe_export_privacy_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(de.trailscape.core.MAX_TITLE_CHARS) },
                    label = { Text(stringResource(R.string.recipe_export_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it.take(de.trailscape.core.MAX_AUTHOR_CHARS) },
                    label = { Text(stringResource(R.string.recipe_export_author_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onSave(title, author) }) {
                    Text(stringResource(R.string.recipe_action_save_plan))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onShare(title, author) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.common_action_share))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// Import: Vorschau und Bestaetigung
// ---------------------------------------------------------------------------

/**
 * Zeigt ein gerade geoeffnetes Rezept ([PendingPlanRecipe]) als Dialog:
 * Vorschau samt Umrechnung auf die eigene FTP, bei einer Ablehnung die
 * Meldung. Ohne offenes Rezept gibt es nichts aus.
 */
@Composable
fun PlanRecipeImportHost(appViewModel: AppViewModel) {
    val offer by PendingPlanRecipe.offer.collectAsStateWithLifecycle()
    val current = offer ?: return
    val context = LocalContext.current

    when (current) {
        is PlanRecipeOffer.Rejected, PlanRecipeOffer.Unreadable -> {
            val message = when (current) {
                is PlanRecipeOffer.Rejected -> recipeProblemMessage(context, current.rejection)
                else -> stringResource(R.string.recipe_problem_read)
            }
            OneUiDialog(
                onDismissRequest = PendingPlanRecipe::clear,
                title = { Text(stringResource(R.string.recipe_problem_title)) },
                text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(onClick = PendingPlanRecipe::clear) { Text(stringResource(R.string.common_action_ok)) }
                },
            )
        }
        is PlanRecipeOffer.Readable -> ReadableRecipeDialog(appViewModel, current.recipe)
    }
}

@Composable
private fun ReadableRecipeDialog(appViewModel: AppViewModel, recipe: PlanRecipe) {
    val coreTexts = LocalCoreTexts.current
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    val eftp = remember(insights.profile) { EftpEstimate.fromProfile(insights.profile) }
    val ftpW = eftp.watts.takeIf { it.isFinite() && it > 0 }?.roundToInt()
    val pauseWord = stringResource(R.string.recipe_pause_word)
    val preview = remember(recipe, ftpW, pauseWord) { previewRecipe(recipe, ftpW, pauseWord) }
    val today = remember { LocalDate.now() }
    val start = remember(today) { nextMondayOnOrAfter(today) }
    var dayIndex by rememberSaveable { mutableIntStateOf(today.dayOfWeek.value - 1) }
    val existing = plan

    OneUiDialog(
        onDismissRequest = PendingPlanRecipe::clear,
        title = {
            Text(stringResource(if (preview.isWorkout) R.string.recipe_import_title_session else R.string.recipe_import_title_plan))
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                PlanRecipePreviewBody(
                    preview = preview,
                    ftpEstimated = eftp.source != EftpSource.EINGETRAGEN,
                    start = start,
                    existingPlanName = existing?.recipe?.title ?: existing?.goal?.name,
                    hasPlan = existing != null,
                    dayIndex = dayIndex,
                    onDayChange = { dayIndex = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val nowMs = System.currentTimeMillis()
                    val speed = typicalAvgSpeedKmh(rides)
                    val newPlan = when (recipe) {
                        is PlanRecipePlan -> planFromRecipe(recipe, start, nowMs, speed, pauseWord)
                        is PlanRecipeWorkout ->
                            existing?.let { planWithInsertedWorkout(it, recipe, dayIndex, nowMs, pauseWord) }
                                ?: planFromWorkout(recipe, dayIndex, today, nowMs, speed, pauseWord)
                    }
                    appViewModel.setPlan(newPlan)
                    appViewModel.showMessage(
                        UiText.Res(
                            if (preview.isWorkout) R.string.recipe_import_done_session_status else R.string.recipe_import_done_plan_status,
                            listOf(recipe.title),
                        ),
                    )
                    appViewModel.requestPlan()
                    PendingPlanRecipe.clear()
                },
            ) {
                Text(
                    stringResource(
                        when {
                            preview.isWorkout -> R.string.recipe_import_confirm_session
                            existing != null -> R.string.recipe_import_confirm_replace
                            else -> R.string.recipe_import_confirm_new
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = PendingPlanRecipe::clear) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

/**
 * Inhalt der Vorschau: Titel, Autor, Umfang, Umrechnung auf die eigene FTP,
 * die Einheiten der ersten Woche und — wichtig — die Ersetzen-Warnung, wenn es
 * schon einen Plan gibt. Zustandslos, damit ihn die Screenshot-Tests ohne
 * ViewModel zeigen koennen.
 */
@Composable
fun PlanRecipePreviewBody(
    preview: PlanRecipePreview,
    ftpEstimated: Boolean,
    start: LocalDate,
    existingPlanName: String?,
    hasPlan: Boolean,
    dayIndex: Int,
    onDayChange: (Int) -> Unit,
) {
    val formats = LocalAppFormats.current
    val coreTexts = LocalCoreTexts.current
    Column(verticalArrangement = Arrangement.spacedBy(CardGap / 2)) {
        Text(preview.title, style = MaterialTheme.typography.titleMedium)
        preview.author?.let {
            Text(
                stringResource(R.string.recipe_import_author, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        preview.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        if (preview.isWorkout) {
            Text(
                stringResource(R.string.recipe_import_session_duration, formatMinutes(preview.totalMinutes, formats::decimal)),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                pluralStringResource(R.plurals.recipe_import_weeks_count, preview.weekCount, preview.weekCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (preview.minSessionsPerWeek == preview.maxSessionsPerWeek) {
                    pluralStringResource(
                        R.plurals.recipe_import_sessions_per_week_count,
                        preview.maxSessionsPerWeek,
                        preview.maxSessionsPerWeek,
                    )
                } else {
                    stringResource(R.string.recipe_import_sessions_range, preview.minSessionsPerWeek, preview.maxSessionsPerWeek)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.recipe_import_total,
                    formatMinutes(preview.totalMinutes, formats::decimal),
                    formatMinutes(preview.peakWeekMinutes, formats::decimal),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        preview.ftpW?.let { w ->
            Text(
                stringResource(if (ftpEstimated) R.string.recipe_import_ftp_estimated else R.string.recipe_import_ftp_entered, w),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!preview.isWorkout) {
            Text(
                stringResource(R.string.recipe_import_first_week_eyebrow),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        preview.firstWeek.forEach { session ->
            val dayLabel = session.day?.let { coreTexts.format.weekdayShort(planWeekdayCodes[it]) }
            Text(
                text = listOfNotNull(dayLabel, session.title, formatMinutes(session.minutes, formats::decimal)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = session.structure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (preview.isWorkout) {
            Text(
                stringResource(R.string.recipe_import_day_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                planWeekdayCodes.forEachIndexed { index, code ->
                    FilterChip(
                        selected = index == dayIndex,
                        onClick = { onDayChange(index) },
                        label = { Text(coreTexts.format.weekdayShort(code)) },
                    )
                }
            }
            Text(
                stringResource(if (hasPlan) R.string.recipe_import_session_into_plan else R.string.recipe_import_session_new_plan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.recipe_import_start, formats.dateFull(start)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (hasPlan) {
                Text(
                    stringResource(R.string.recipe_import_replace_warning, existingPlanName.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** „7,5 h" ab 60 Minuten, sonst „45 min" — sprachneutral ueber die Zahlenformate der App. */
internal fun formatMinutes(minutes: Int, decimal: (Double, Int) -> String): String =
    if (minutes < 60) "$minutes min" else "${decimal(minutes / 60.0, 1)} h"
