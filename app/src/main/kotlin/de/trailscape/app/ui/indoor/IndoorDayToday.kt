package de.trailscape.app.ui.indoor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.indoor.IndoorDayPlan
import de.trailscape.app.indoor.IndoorDayRouteFinder
import de.trailscape.app.indoor.indoorDayPlanOf
import de.trailscape.app.indoor.rank
import de.trailscape.core.RouteTarget
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.sessionTitle
import de.trailscape.core.indoor.DayRouteAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Der Indoor-Eintrag auf „Heute" (docs/indoor/app.md, „Einheit des Tages").
 *
 * Mit einer Einheit fuer die Rolle die Karte „Einheit des Tages" mit der
 * passenden Strecke — „Fahren" oeffnet den Indoor-Ablauf mit diesem Vorschlag
 * ([onRideDay] mit dem Schluessel), „Runde planen" die Routensuche
 * ([onPlanRoute]). Am Ruhetag die ehrliche Auskunft samt „Trotzdem frei
 * fahren". Sonst (Zieltag, Suche laeuft, Kern liefert nichts) die bisherige
 * Einstiegskarte [IndoorEntryCard].
 *
 * Die Suche laeuft im Hintergrund ([IndoorDayRouteFinder], ohne Netz) und
 * wiederholt sich, wenn sich Tagesentscheidung, Profil oder die Zahl der Touren
 * aendern.
 */
@Composable
fun IndoorTodayEntry(
    today: TodayRoute,
    profile: TrainingProfile,
    rideCount: Int,
    coreTexts: CoreTexts,
    fallbackErgTitle: String?,
    onOpen: () -> Unit,
    onRideDay: (String) -> Unit,
    onPlanRoute: (RouteTarget) -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
) {
    val formats = LocalAppFormats.current
    var state by remember { mutableStateOf<Pair<IndoorDayPlan, IndoorDayUi>?>(null) }
    LaunchedEffect(today, profile, rideCount, formats.language) {
        state = withContext(Dispatchers.IO) {
            runCatching {
                val context = AppServices.localizedContext()
                val plan = indoorDayPlanOf(
                    today = today,
                    parts = AppServices.indoorParts,
                    profile = profile,
                    plannedTitle = { d -> d.planned?.let { s -> runCatching { sessionTitle(s, coreTexts) }.getOrElse { s.title } } },
                    easyTitle = context.getString(de.trailscape.app.R.string.indoor_app_day_easy_title),
                )
                val first = indoorDayUiOf(plan, emptyList()) ?: return@runCatching null
                if (first !is IndoorDayUi.Offer) return@runCatching plan to first
                val finder = IndoorDayRouteFinder(AppServices.rideStorage, AppServices.indoorWorldLibrary, AppServices.indoorCatalogStore)
                val matches = finder.rank(
                    plan,
                    testWorldName = context.getString(de.trailscape.app.R.string.indoor_app_route_testworld),
                    languageTag = formats.language.tag,
                    planName = context.getString(de.trailscape.app.R.string.indoor_app_day_plan_name),
                )
                val catalogEntries = runCatching { AppServices.indoorCatalogStore.catalog().entries }.getOrDefault(emptyList())
                val bytes = catalogEntries.associate { it.id to it.packageBytes }
                val previewPaths = catalogEntries.mapNotNull { entry ->
                    val file = runCatching { AppServices.indoorCatalogStore.cachedPreview(entry) }.getOrNull()
                    if (file != null) entry.id to file.path else null
                }.toMap()
                val genReady = runCatching {
                    val wp = de.trailscape.app.indoor.DefaultIndoorGeneratedWorlds
                        .plan(de.trailscape.core.indoor.dayGeneratedProfile(plan.goal!!), de.trailscape.core.indoor.world.IndoorQuality.MEDIUM, "")
                    AppServices.indoorWorldLibrary.get(wp.worldId)?.complete == true
                }.getOrDefault(false)
                indoorDayUiOf(
                    plan,
                    matches,
                    generatedReady = genReady,
                    previewPathFor = { id -> previewPaths[id] },
                ) { id -> bytes[id] }?.let { plan to it }
            }.getOrNull()
        }
    }
    val current = state
    when (val ui = current?.second) {
        is IndoorDayUi.Offer -> IndoorDayCard(
            ui = ui,
            onRide = { s ->
                if (s.availability == DayRouteAvailability.PLAN) {
                    current.first.planTarget?.let(onPlanRoute)
                } else {
                    onRideDay(s.key)
                }
            },
            onNext = { state = current.first to ui.next() },
            onRideGenerated = { onRideDay(INDOOR_DAY_GENERATED_KEY) },
            onPathChange = { p -> state = current.first to ui.copy(path = p) },
            modifier = modifier,
            primary = primary,
        )
        is IndoorDayUi.Rest -> IndoorDayCard(ui = ui, onRide = {}, onNext = {}, onOpenIndoor = onOpen, modifier = modifier, primary = primary)
        else -> IndoorEntryCard(ergTitle = fallbackErgTitle, onClick = onOpen, modifier = modifier)
    }
}
