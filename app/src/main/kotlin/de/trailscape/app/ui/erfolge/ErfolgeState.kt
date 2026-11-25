package de.trailscape.app.ui.erfolge

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.ErfolgeStore
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.YEAR_GOAL_STORAGE_KEY
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.rememberNow
import de.trailscape.core.AchievementId
import de.trailscape.core.AchievementInput
import de.trailscape.core.AchievementOverview
import de.trailscape.core.AchievementProgressData
import de.trailscape.core.AchievementSources
import de.trailscape.core.ExplorationAchievementFacts
import de.trailscape.core.RideHighlight
import de.trailscape.core.RideInfo
import de.trailscape.core.TrainingPlan
import de.trailscape.core.YearGoal
import de.trailscape.core.achievementOverview
import de.trailscape.core.achievementsUnlockedBy
import de.trailscape.core.distinctRiddenWaysKm
import de.trailscape.core.largestExplorerSquare
import de.trailscape.core.loadPlan
import de.trailscape.core.planAchievementFacts
import de.trailscape.core.rideFacts
import de.trailscape.core.withAchievementHighlight
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * # Die Erfolge in der App — woher die Eingaben kommen
 *
 * `:core` rechnet (`Achievements.kt`); hier werden die Eingaben
 * zusammengetragen: Touren, gespeicherter Plan, Jahresziel, Tourlasten der
 * Trainingsauswertung und der kleine gespeicherte Stand ([ErfolgeStore]).
 *
 * ## Der Plan
 * Es zaehlt der **gespeicherte** Plan, nicht der angepasste Anzeige-Plan des
 * Plan-Tabs (`adaptPlan`): Die Anpassung skaliert nur kuenftige Wochen und
 * aendert sich mit jeder Fahrt; ein Abzeichen soll nicht davon abhaengen, wann
 * man hinsieht.
 *
 * ## Teure Quellen
 * Runden, Entdeckt-Kacheln und gefahrene Wege brauchen die Punkte aller
 * Touren (beim ersten Mal von der Platte, danach aus den Caches). Sie werden
 * nur auf dem Bildschirm „Erfolge" geladen ([loadErfolgeSources]); Karte,
 * Startseite und Uhr rechnen ohne sie, die drei Abzeichen stehen dort auf
 * „wird berechnet". Was einmal erreicht ist, haelt [ErfolgeStore] fest.
 */

/** Die Eingabe fuer [achievementOverview] aus dem, was die App hat. Ohne Compose. */
internal fun erfolgeInput(
    rides: List<RideInfo>,
    plan: TrainingPlan?,
    yearGoal: YearGoal?,
    progress: AchievementProgressData,
    rideLoads: Map<String, Double>,
    now: LocalDateTime,
    sources: AchievementSources = AchievementSources(),
): AchievementInput {
    val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return AchievementInput(
        // Dieselbe Tageszuordnung wie die Oberflaeche (`rememberRideFacts`).
        facts = rideFacts(rides) { localOfEpochMs(it).toLocalDate() },
        today = now.toLocalDate(),
        indoorRideIds = rides.filter { it.indoor && !it.planned }.mapTo(HashSet()) { it.id },
        plan = plan?.let { planAchievementFacts(it, rides, nowMs, rideLoads) },
        yearGoal = yearGoal,
        progress = progress,
        sources = sources,
    )
}

/**
 * Der Stand der Erfolge fuer eine Composable-Stelle; `null`, solange er
 * gerechnet wird, [active] `false` ist oder „Erfolge anzeigen" aus ist.
 * Gerechnet wird auf `Dispatchers.Default`. Was erreicht ist, wird im
 * [ErfolgeStore] festgehalten.
 *
 * @param loadSources auch Runden, Kacheln und Wege laden (nur der Bildschirm).
 */
@Composable
internal fun rememberErfolge(
    appViewModel: AppViewModel,
    active: Boolean = true,
    loadSources: Boolean = false,
): AchievementOverview? {
    val context = LocalContext.current
    val store = ErfolgeStore.shared
    val progress by store.state.collectAsStateWithLifecycle()
    val enabled = active && !progress.hidden
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val yearGoal by appViewModel.yearGoal.collectAsStateWithLifecycle()
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val now = rememberNow()
    val today = now.toLocalDate()
    val rideLoads = remember(insights) { insights.rideLoads.mapValues { it.value.load } }
    val sources by produceState(AchievementSources(), enabled, loadSources, rides) {
        if (enabled && loadSources) value = loadErfolgeSources(appViewModel)
    }
    val overview by produceState<AchievementOverview?>(
        null,
        enabled,
        rides,
        plan,
        yearGoal,
        progress,
        rideLoads,
        sources,
        today,
    ) {
        value = if (!enabled) {
            null
        } else {
            // Erst den Punktebeginn festlegen, dann rechnen: Sonst hielte die
            // allererste Rechnung mit allen importierten Touren eine Stufe fest
            // (`keptLevel`), die es ohne sie nie gaebe.
            val current = if (progress.pointsSince == null) {
                withContext(Dispatchers.IO) {
                    store.ensurePointsSince(context.applicationContext)
                    store.value
                }
            } else {
                progress
            }
            withContext(Dispatchers.Default) {
                computeSafely { achievementOverview(erfolgeInput(rides, plan, yearGoal, current, rideLoads, now, sources)) }
            }
        }
    }
    LaunchedEffect(overview) {
        overview?.let { o -> store.update { it.recording(o, today) } }
    }
    return if (enabled) overview else null
}

/**
 * Runden, Entdeckt-Kacheln und gefahrene Wege — ueber dieselben Wege wie die
 * Routenwahl („Neue Gegenden", „Neue Wege") und das Tourblatt
 * (Rundenvergleich), also mit deren Caches. Was scheitert, bleibt `null`
 * (Abzeichen „wird berechnet").
 */
internal suspend fun loadErfolgeSources(appViewModel: AppViewModel): AchievementSources = coroutineScope {
    val rounds = async { computeSafely { appViewModel.achievementRounds() } }
    val tiles = async { computeSafely { appViewModel.exploredTilesForPlanning() } }
    val ways = async { computeSafely { appViewModel.riddenWaysForPlanning() } }
    AchievementSources(
        rounds = rounds.await(),
        exploration = tiles.await()?.let { ExplorationAchievementFacts(it.size, largestExplorerSquare(it)?.size ?: 0) },
        distinctWaysKm = ways.await()?.let { distinctRiddenWaysKm(it.size) },
    )
}

/** Wie `runCatching`, laesst aber einen Abbruch der Coroutine durch. */
private inline fun <T> computeSafely(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * Die Abzeichen, die die Tour [rideId] gebracht hat — fuer das Tourblatt.
 * Leer, solange gerechnet wird, bei „Erfolge anzeigen" aus oder wenn
 * [active] `false` ist.
 */
@Composable
internal fun rememberErfolgeOfRide(appViewModel: AppViewModel, rideId: String, active: Boolean): List<AchievementId> {
    val overview = rememberErfolge(appViewModel, active = active)
    return remember(overview, rideId) { overview?.let { achievementsUnlockedBy(rideId, it.states) }.orEmpty() }
}

/**
 * [highlights] samt Abzeichen der Tour, ausserhalb der Oberflaeche (Uhr nach
 * der Fahrt, `wear/WearAbschluss.kt`). Liest Plan, Jahresziel und Stand
 * selbst; ohne Tourlasten, Runden, Kacheln und Wege. Bei „Erfolge anzeigen"
 * aus unveraendert. Blockierend: nur von IO aufrufen. Wirft nie.
 */
internal fun withErfolgeOutsideUi(
    context: Context,
    rides: List<RideInfo>,
    rideId: String,
    now: LocalDateTime,
    highlights: List<RideHighlight>,
    max: Int,
): List<RideHighlight> {
    val store = ErfolgeStore.shared
    if (store.value.hidden) return highlights
    store.ensurePointsSince(context.applicationContext)
    val overview = computeSafely {
        achievementOverview(
            erfolgeInput(
                rides = rides,
                plan = runCatching { loadPlan(AppServices.trainingPlanStore) }.getOrNull(),
                yearGoal = YearGoal.decode(
                    runCatching { AppServices.keyValueStore.getString(YEAR_GOAL_STORAGE_KEY) }.getOrNull(),
                ),
                progress = store.value,
                rideLoads = emptyMap(),
                now = now,
            ),
        )
    } ?: return highlights
    store.update { it.recording(overview, now.toLocalDate()) }
    return withAchievementHighlight(highlights, achievementsUnlockedBy(rideId, overview.states), max)
}
