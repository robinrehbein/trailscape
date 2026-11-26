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
import de.trailscape.app.ui.garageEntryVisible
import de.trailscape.app.ui.indoorSegmentUsesFlow
import de.trailscape.app.ui.shippedIndoorSegments
import de.trailscape.app.ui.shownIndoorSegment
import de.trailscape.app.ui.training.PlanScreen

/**
 * # Der Tab „Indoor" — Strecken, Plan und der Start der Indoor-Fahrt
 *
 * Dritter Platz der Navigationsleiste (Heute · Karte · Indoor · Verlauf); er
 * ersetzt den frueheren Tab „Training" (Seitenkarte `docs/ux/seitenkarte.md`,
 * Entscheidung 1 des Eigentuemers; Umsetzung `docs/ux/umbau.md`).
 *
 * ## Segmente ([shippedIndoorSegments]): Strecken | Plan | Gemeinsam
 *  * **Strecken** — die Indoor-Startseite ([IndoorFlowScreen] als
 *    [IndoorFlowMode.TAB]): Trainer-Status, Einheit des Tages, eigene und
 *    bekannte Strecken, „Fahren". Koppeln, Strecke vorbereiten, Fahrt und
 *    Auswertung laufen im selben Ablauf weiter; die Navigationsleiste tritt
 *    dafuer zurueck ([AppViewModel.indoorTaskActive]).
 *  * **Plan** — der Trainingsplan ([PlanScreen]), frueher das zweite Segment
 *    von „Training". [AppViewModel.requestPlan] springt direkt hierher.
 *  * **Gemeinsam** — zwei Abschnitte in einer Sicht ([IndoorSegment.TOGETHER];
 *    vier Segmente passen nicht in die Zeile, `docs/ux/umbau.md`):
 *    **Raum** (Gruppenfahrt in Echtzeit auf eigenem Server: „Raum anlegen",
 *    „Raum beitreten", Teilnehmer, gemeinsamer Start; `docs/raum-protokoll.md`)
 *    und **Herausforderungen** (gespeicherte Herausforderungen anderer und
 *    „Datei wählen", `docs/herausforderung.md`). Die Sicht lebt im selben
 *    Ablauf wie „Strecken" ([indoorSegmentUsesFlow]), weil „Zum Start" und
 *    „Gegen … fahren" dort Strecke und Mitfahrer setzen.
 *    [AppViewModel.requestChallenges] und [AppViewModel.requestRoom] springen
 *    hierher.
 *
 * In der Kopfzeile der Sicht „Strecken" steht neben dem Zahnrad das Symbol
 * der Garage (Fahrer und Rad anpassen, `docs/indoor/garage.md`) — ihr einziger
 * Einstieg.
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
    onOpenGarage: () -> Unit,
) {
    // Strecken | Plan | Gemeinsam (Raum und Herausforderungen, docs/ux/umbau.md).
    val segments = shippedIndoorSegments()
    // Ein von aussen geoeffneter Einladungslink; der Abschnitt „Raum" uebernimmt ihn.
    val roomInvite by appViewModel.pendingRoomInvite.collectAsStateWithLifecycle()
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
    val segment = shownIndoorSegment(wantedSegment, segments, taskActive)

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

        // Strecken und „Gemeinsam" leben im selben Ablauf (ein `IndoorViewModel`,
        // eine Stelle in der Komposition — ein Wechsel zwischen beiden baut
        // nichts neu, und ein offener Raum bleibt offen). CHALLENGES und ROOM
        // als eigene Segmente liefert [shippedIndoorSegments] nicht mehr; kaemen
        // sie zurueck, zeigten sie dieselbe Sicht.
        IndoorSegment.ROUTES,
        IndoorSegment.CHALLENGES,
        IndoorSegment.ROOM,
        IndoorSegment.TOGETHER,
        -> IndoorFlowScreen(
            appViewModel = appViewModel,
            // Im Tab gibt es nichts zu verlassen: Zurueck gehoert der Huelle.
            onExit = {},
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenWorlds = onOpenWorlds,
            mode = IndoorFlowMode.TAB,
            tabHeader = segmentRow,
            // Die Garage: Symbol in der Kopfzeile der Sicht „Strecken" (docs/ux/umbau.md).
            onOpenGarage = onOpenGarage.takeIf { garageEntryVisible(indoorFlowInTab = true) },
            togetherSegment = segment != IndoorSegment.ROUTES,
            // „Gegen … fahren", „Strecke herunterladen", „Zu den Strecken":
            // weiter geht es unter „Strecken".
            onShowRoutes = { wantedSegment = IndoorSegment.ROUTES },
            roomInvite = roomInvite,
            onRoomInviteConsumed = appViewModel::consumeRoomInvite,
        )
    }
}

/** Die Beschriftung eines Segments des Indoor-Tabs. */
@Composable
private fun indoorSegmentLabel(segment: IndoorSegment): String = stringResource(
    when (segment) {
        IndoorSegment.ROUTES -> R.string.indoor_app_tab_segment_routes
        IndoorSegment.PLAN -> R.string.indoor_app_tab_segment_plan
        IndoorSegment.CHALLENGES -> R.string.indoor_app_tab_segment_challenges
        IndoorSegment.ROOM -> R.string.raum_section_title
        IndoorSegment.TOGETHER -> R.string.indoor_app_tab_segment_together
    },
)
