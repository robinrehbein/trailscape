package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.SectionEyebrow
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.progress.ChallengesCard
import de.trailscape.app.ui.progress.FitnessTrendCard
import de.trailscape.app.ui.progress.LifetimeCard
import de.trailscape.app.ui.progress.MilestonesCard
import de.trailscape.app.ui.progress.RecordsCard
import de.trailscape.app.ui.progress.TrendCard
import de.trailscape.app.ui.progress.YearGoalCard
import de.trailscape.app.ui.progress.YearGoalDialog
import de.trailscape.app.ui.progress.rememberRideFacts
import de.trailscape.app.ui.rememberNow
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.core.assessFitness
import de.trailscape.core.challengeHistory
import de.trailscape.core.challengeProgress
import de.trailscape.core.fitnessComparison
import de.trailscape.core.lifetimeTotals
import de.trailscape.core.milestoneProgress
import de.trailscape.core.personalRecords
import de.trailscape.core.recentTrend
import de.trailscape.core.suggestYearGoalKm
import de.trailscape.core.weekStreak
import de.trailscape.core.weeklyBuckets
import de.trailscape.core.yearGoalProgress
import java.time.YearMonth

/**
 * # Die Sicht „Fortschritt": was war und wohin es geht
 *
 * Bis zum Umbau nach der Seitenkarte (`docs/ux/umbau.md`) war das das erste
 * Segment des Tabs „Training". Fortschritt ist der Blick zurueck, wie die
 * Tourenliste — deshalb ist er jetzt das Segment „Fortschritt" des Tabs
 * „Verlauf" (`ui/rides/RidesScreen.kt`); [AppViewModel.requestProgress]
 * springt hierher.
 *
 * Eine Liste, von oben nach unten: [header] (Titel und Segmente des Tabs),
 * Trend, Monats-Challenges, **Jahresziel** (der eine Ort dafuer, mit
 * [YearGoalDialog]), Bestwerte (oeffnen die Tour), Meilensteine, Gesamtwerte,
 * **Deine Form** ([FormSummaryCard] → [FormSheet]) und **Körperwerte**
 * ([VitalsTiles] → [VitalsSheet]).
 *
 * Bewusst **ohne** eigenes `Scaffold`: Der Verlauf traegt Snackbar und
 * Meldungen schon; diese Sicht ist nur die Liste samt ihrer Blaetter.
 *
 * ## Leerzustand
 * Bei leerer Tourenliste steht [ProgressEmptyState] oben: Fitness und Erholung
 * brauchen ~2 Wochen Historie, plus die zwei kuerzesten Wege zu echten Daten.
 * Die Koerperwerte bleiben (sie koennen auch ohne Touren aus Health Connect
 * kommen).
 *
 * @param header Kopf des Tabs (Titel, Aktionen, Segmente); erstes Listenelement.
 * @param onImport oeffnet den Tour-Import des Verlaufs („+").
 */
@Composable
fun ProgressContent(
    appViewModel: AppViewModel,
    header: @Composable () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = screenContentPadding(),
) {
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val vitalsSyncedAt by appViewModel.vitalsSyncedAt.collectAsStateWithLifecycle()
    val assessment = remember(rides) { assessFitness(rides) }
    // Ob Alter und Gewicht vom Nutzer stammen — sonst rechnet diese Sicht mit
    // den Annahmen aus `defaultTrainingProfile`.
    val profileConfirmed by appViewModel.profileConfirmed.collectAsStateWithLifecycle()
    // Der Kurzschlaefer-Hinweis: hoechstens einmal pro Monat
    // (`shouldShowShortSleeperHint`), entschieden im ViewModel.
    val showShortSleeperHint by appViewModel.shortSleeperHintVisible
        .collectAsStateWithLifecycle()

    var showForm by rememberSaveable { mutableStateOf(false) }
    var showVitals by rememberSaveable { mutableStateOf(false) }

    val now = rememberNow()
    val today = now.toLocalDate()
    val facts = rememberRideFacts(rides)
    val fitnessTrend = remember(rides, today) {
        fitnessComparison(rides, { localOfEpochMs(it).toLocalDate() }, today)
    }
    val buckets = remember(facts, today) { weeklyBuckets(facts, today) }
    val trend = remember(facts, today) { recentTrend(facts, today) }
    val challenges = remember(facts, today) { challengeProgress(facts, today) }
    val lastMonthChallenges = remember(facts, today) {
        val lastMonth = YearMonth.from(today).minusMonths(1)
        challengeHistory(facts, today).firstOrNull { it.month == lastMonth }
    }
    val records = remember(facts) { personalRecords(facts) }
    val milestones = remember(facts) { milestoneProgress(facts) }
    val lifetime = remember(facts) { lifetimeTotals(facts) }
    val bestStreak = remember(facts, today) { weekStreak(facts, today).best }
    val yearGoalSetting by appViewModel.yearGoal.collectAsStateWithLifecycle()
    val yearGoal = remember(facts, yearGoalSetting, today) {
        yearGoalSetting?.let { yearGoalProgress(facts, it, today) }
    }
    val suggestedYearGoalKm = remember(facts, today) { suggestYearGoalKm(facts, today) }
    var showYearGoal by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        item(key = "kopf") { header() }

        if (rides.isEmpty()) {
            item(key = "empty") {
                ProgressEmptyState(
                    // Derselbe Text startet auf „Heute" die Aufzeichnung — hier auch.
                    onRecord = { appViewModel.requestRecording() },
                    onImport = onImport,
                )
            }
        }

        if (!profileConfirmed) {
            item(key = "profil-hinweis") {
                UnconfirmedProfileNotice(
                    asCard = true,
                    onOpenProfile = { appViewModel.requestMoreSection(MoreSection.PROFILE) },
                )
            }
        }

        if (facts.isNotEmpty()) {
            item(key = "sec-trend") { SectionEyebrow(stringResource(R.string.progress_trend_eyebrow)) }
            item(key = "trend") { TrendCard(buckets = buckets, trend = trend) }
            item(key = "challenges") {
                ChallengesCard(
                    progress = challenges,
                    month = today,
                    lastMonth = lastMonthChallenges,
                )
            }
            item(key = "jahresziel") {
                YearGoalCard(
                    progress = yearGoal,
                    year = today.year,
                    suggestedKm = suggestedYearGoalKm,
                    onEdit = { showYearGoal = true },
                )
            }
            item(key = "bestwerte") {
                RecordsCard(records = records, onOpenRide = appViewModel::requestRideDetail)
            }
            item(key = "meilensteine") { MilestonesCard(milestones) }
            item(key = "gesamt") { LifetimeCard(totals = lifetime, bestStreak = bestStreak) }
        }

        // --------------------------------------------------- Deine Form
        if (rides.isNotEmpty() || insights.fitness.latest != null) {
            item(key = "fitness-trend") { FitnessTrendCard(fitnessTrend) }
            item(key = "sec-form") { SectionEyebrow(stringResource(R.string.training_screen_form_eyebrow)) }
            item(key = "form") {
                FormSummaryCard(insights, onClick = { showForm = true })
            }
        }

        // -------------------------------------------------- Koerperwerte
        item(key = "sec-werte") {
            SectionEyebrow(stringResource(R.string.training_screen_vitals_eyebrow))
        }
        item(key = "vitals") {
            VitalsTiles(
                insights = insights,
                syncedAt = vitalsSyncedAt,
                onOpenDetails = { showVitals = true },
                showShortSleeperHint = showShortSleeperHint,
                onShortSleeperHintShown = appViewModel::markShortSleeperHintShown,
            )
        }
    }

    if (showYearGoal) {
        YearGoalDialog(
            currentKm = yearGoalSetting?.targetKm,
            suggestedKm = suggestedYearGoalKm,
            weeksLeft = (today.lengthOfYear() - today.dayOfYear) / 7.0,
            riddenKm = facts.filter { it.date.year == today.year }.sumOf { it.distanceKm },
            onSave = {
                appViewModel.setYearGoalKm(it)
                showYearGoal = false
            },
            onRemove = {
                appViewModel.setYearGoalKm(null)
                showYearGoal = false
            },
            onDismiss = { showYearGoal = false },
        )
    }
    if (showForm) {
        FormSheet(
            insights = insights,
            assessment = assessment,
            showDetails = rides.isNotEmpty(),
            onOpenProfile = {
                showForm = false
                appViewModel.requestMoreSection(MoreSection.PROFILE)
            },
            onDismiss = { showForm = false },
        )
    }
    if (showVitals) {
        VitalsSheet(insights = insights, onDismiss = { showVitals = false })
    }
}

/**
 * Was der Fortschritt kann, solange er noch keine Tour kennt.
 *
 * Textbudget: zwei kurze Saetze, dann die Knoepfe. Die Groessenordnung
 * („rund zwei Wochen") bleibt die einzige Ausnahme vom Ein-Satz-Budget der
 * uebrigen Leerzustaende, weil sie verhindert, dass eine leere Sicht am
 * zweiten Tag wie ein Fehler wirkt.
 */
@Composable
private fun ProgressEmptyState(onRecord: () -> Unit, onImport: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.training_screen_empty_title),
        body = stringResource(R.string.training_screen_empty_body),
        actions = {
            Button(onClick = onRecord) { Text(stringResource(R.string.training_screen_empty_record_action)) }
            NeutralButton(onClick = onImport) { Text(stringResource(R.string.training_screen_empty_import_action)) }
        },
    )
}
