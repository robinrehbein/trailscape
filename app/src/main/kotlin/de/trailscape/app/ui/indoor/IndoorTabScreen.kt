package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.IndoorSegment
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.indoorSegments
import de.trailscape.app.ui.resolveSegment
import de.trailscape.app.ui.training.PlanScreen

/**
 * # Der Tab „Indoor" — Strecken, Plan und der Start der Indoor-Fahrt
 *
 * Dritter Platz der Navigationsleiste (Heute · Karte · Indoor · Verlauf); er
 * ersetzt den frueheren Tab „Training" (Seitenkarte `docs/ux/seitenkarte.md`,
 * Entscheidung 1 des Eigentuemers; Umsetzung `docs/ux/umbau.md`).
 *
 * ## Segmente ([indoorSegments])
 *  * **Strecken** — die Indoor-Startseite ([IndoorFlowScreen] als
 *    [IndoorFlowMode.TAB]): Trainer-Status, Einheit des Tages, eigene und
 *    bekannte Strecken, „Fahren". Koppeln, Strecke vorbereiten, Fahrt und
 *    Auswertung laufen im selben Ablauf weiter; die Navigationsleiste tritt
 *    dafuer zurueck ([AppViewModel.indoorTaskActive]).
 *  * **Plan** — der Trainingsplan ([PlanScreen]), frueher das zweite Segment
 *    von „Training". [AppViewModel.requestPlan] springt direkt hierher.
 *  * **Herausforderungen** und **Raum** — festgelegte Plaetze
 *    ([IndoorSegment.CHALLENGES], [IndoorSegment.ROOM]), unsichtbar, solange
 *    es die Funktionen nicht gibt.
 *
 * Das Zahnrad oeffnet in jeder Sicht dieselbe Einstellungsseite der App; die
 * Indoor-Einstellungen liegen dort unter „Indoor".
 *
 * ## Insets
 * Der `NavHost` loest fuer diesen Tab oben keine Insets auf (die Fahransicht
 * ist randlos): Der Ablauf tut es je Seite selbst, die Sicht „Plan" hier.
 */
@Composable
fun IndoorTabScreen(
    appViewModel: AppViewModel,
    onOpenDiagnostics: () -> Unit,
    onOpenWorlds: () -> Unit,
) {
    // Herausforderungen und Raum gibt es noch nicht — ihre Plaetze bleiben
    // unsichtbar (docs/ux/umbau.md: hier die beiden Schalter setzen).
    val segments = indoorSegments(challengesAvailable = false, roomAvailable = false)
    var wantedSegment by rememberSaveable { mutableStateOf(IndoorSegment.ROUTES) }

    // Laeuft im Tab eine Aufgabe (Koppeln, Fahrt, Auswertung), bleibt die
    // Sicht „Strecken" stehen: Ein Sprungwunsch von aussen (etwa ein geoeffnetes
    // Plan-Rezept) darf eine Fahrt nicht aus der Komposition nehmen. Er wartet
    // und greift danach.
    val taskActive by appViewModel.indoorTaskActive.collectAsStateWithLifecycle()
    val pendingSegment by appViewModel.pendingIndoorSegment.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSegment, taskActive) {
        val requested = pendingSegment ?: return@LaunchedEffect
        if (taskActive) return@LaunchedEffect
        wantedSegment = requested
        appViewModel.consumeIndoorSegmentRequest()
    }
    val segment = if (taskActive) IndoorSegment.ROUTES else resolveSegment(wantedSegment, segments)

    val segmentRow: @Composable () -> Unit = {
        PillSegments(
            options = segments.map { indoorSegmentLabel(it) },
            selectedIndex = segments.indexOf(segment).coerceAtLeast(0),
            onSelect = { index -> segments.getOrNull(index)?.let { wantedSegment = it } },
            // Wechselt die Ansicht, ist also ein Tab und keine Auswahl.
            role = Role.Tab,
        )
    }

    when (segment) {
        IndoorSegment.PLAN -> Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            // Wie [InsetPadded] der Huelle: die volle Flaeche als Mindestmass.
            propagateMinConstraints = true,
        ) {
            PlanScreen(
                appViewModel = appViewModel,
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScreenHeader(
                            title = stringResource(R.string.indoor_app_tab_title),
                            actions = {
                                SettingsAction(onClick = { appViewModel.requestTab(AppTab.MORE) })
                            },
                        )
                        segmentRow()
                    }
                },
            )
        }

        // Strecken — und die Plaetze, die es noch nicht gibt: [indoorSegments]
        // liefert CHALLENGES und ROOM erst mit ihrer Funktion; bis dahin
        // werden diese Zweige nie erreicht.
        IndoorSegment.ROUTES,
        IndoorSegment.CHALLENGES,
        IndoorSegment.ROOM,
        -> IndoorFlowScreen(
            appViewModel = appViewModel,
            // Im Tab gibt es nichts zu verlassen: Zurueck gehoert der Huelle.
            onExit = {},
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenWorlds = onOpenWorlds,
            mode = IndoorFlowMode.TAB,
            tabHeader = segmentRow,
        )
    }
}

/** Die Beschriftung eines Segments des Indoor-Tabs. */
@Composable
private fun indoorSegmentLabel(segment: IndoorSegment): String = stringResource(
    when (segment) {
        IndoorSegment.ROUTES -> R.string.indoor_app_tab_segment_routes
        IndoorSegment.PLAN -> R.string.indoor_app_tab_segment_plan
        // Kommen mit ihren Funktionen; bis dahin sind die Segmente nie sichtbar.
        IndoorSegment.CHALLENGES, IndoorSegment.ROOM -> R.string.indoor_app_tab_segment_routes
    },
)
