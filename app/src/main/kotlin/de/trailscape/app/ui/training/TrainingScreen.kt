package de.trailscape.app.ui.training

import androidx.compose.runtime.Composable
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.components.PlaceholderScreen

/**
 * PLATZHALTER — gehoert dem Trainings-Agenten.
 *
 * Diese Datei wird als Ganzes durch den echten Trainings-Screen ersetzt
 * (Fitness-Kurve CTL/ATL/TSB, Readiness, Ampeln, Tagesempfehlung, Deload,
 * Wochenziel, VO2max, Trainingsplan). Die Signatur
 * `TrainingScreen(appViewModel: AppViewModel)` ist fest — `TrailscapeApp.kt`
 * ruft genau so auf und darf dabei nicht angefasst werden.
 *
 * Die komplette Auswertung liegt fertig in `appViewModel.insights`
 * ([de.trailscape.app.ui.TrainingInsights]), der Plan in `appViewModel.plan`,
 * das Profil in `appViewModel.profile`.
 */
@Composable
fun TrainingScreen(appViewModel: AppViewModel) {
    PlaceholderScreen(
        title = "Training",
        hint = "Der Trainings-Screen wird gerade gebaut.",
    )
}
