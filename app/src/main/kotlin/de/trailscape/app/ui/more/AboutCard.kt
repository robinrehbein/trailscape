package de.trailscape.app.ui.more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.feedback.ISSUE_REPOSITORY_URL
import de.trailscape.app.feedback.ProblemReportDialog
import de.trailscape.app.ui.AppViewModel

private const val REPOSITORY_URL = ISSUE_REPOSITORY_URL

/**
 * Die Datenschutzerklaerung liegt als `PRIVACY.md` im Repository und wird
 * bewusst dort geoeffnet statt in die App eingebettet: So ist sie fuer alle
 * gleich einsehbar (auch vor der Installation, auch fuer Health Connect, das
 * ab Android 14 einen solchen Einstiegspunkt verlangt), und sie kann ohne
 * App-Update korrigiert werden. GitHub rendert das Markdown lesbar.
 */
private const val PRIVACY_URL = "$REPOSITORY_URL/blob/main/PRIVACY.md"

/** Volltext der Lizenz — dieselbe Datei, die als `LICENSE` im Repo liegt. */
private const val LICENSE_URL = "$REPOSITORY_URL/blob/main/LICENSE"

/**
 * „Über"-Karte — Port des Fliesstexts aus der letzten Karte in
 * `lib/screens/more_screen.dart`, seither um alles erweitert, was eine App
 * fuer fremde Nutzer vertrauenswuerdig macht:
 *
 *  * **Version und Quellcode-Link** (schon im nativen Rewrite ergaenzt; das
 *    Flutter-Original zeigt beides nicht an). Die Version kommt ueber den
 *    [android.content.pm.PackageManager] statt ueber `BuildConfig`, weil
 *    `:app` das `buildConfig`-Feature nicht aktiviert.
 *  * **Einführung erneut ansehen** — startet die Erststart-Einfuehrung
 *    (`ui/onboarding/OnboardingScreen.kt`) noch einmal.
 *  * **Datenschutz** — oeffnet `PRIVACY.md` im Repository.
 *  * **Problem melden** — der einzige Meldeweg dieser App (siehe
 *    `feedback/ProblemReportDialog.kt`). Es gibt keine Telemetrie, die von
 *    selbst berichtet; ohne diesen Knopf erfaehrt niemand von einem Fehler.
 *  * **Open-Source-Lizenzen** — aufklappbare, handgepflegte Liste aus
 *    `OpenSourceNotices.kt` samt Daten-Attributionen (OSM, Kachelserver,
 *    Routing, Ortssuche). Kein Lizenz-Plugin, Begruendung dort.
 *
 * Braucht das [AppViewModel] fuer die `debugLines` des letzten Health-Syncs —
 * den optionalen Anhang im Problem-Bericht.
 */
@Composable
fun AboutCard(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val syncReport by appViewModel.lastSyncReport.collectAsStateWithLifecycle()

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unbekannt"
    }

    var showProblemDialog by remember { mutableStateOf(false) }
    var licensesExpanded by remember { mutableStateOf(false) }

    MoreSectionCard(title = "Über", modifier = modifier) {
        Text(
            text = "Trailscape ist kostenlos und local-first: deine Touren bleiben auf " +
                "deinem Gerät, ein Sync-Server ist optional. Kartendaten © " +
                "OpenStreetMap-Mitwirkende, Routing über BRouter.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Version $versionName · freie Software unter der GNU GPL v3 oder später",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Die Erststart-Einfuehrung ist sonst unwiederbringlich weg, sobald
            // sie einmal weggeklickt wurde — und sie ist die einzige Stelle,
            // die den Ueberblick ueber alle vier Tabs am Stueck gibt.
            TextButton(
                onClick = { appViewModel.showOnboardingAgain() },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Einführung erneut ansehen") }
            TextButton(
                onClick = { uriHandler.openUri(REPOSITORY_URL) },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Quellcode auf GitHub") }
            TextButton(
                onClick = { uriHandler.openUri(PRIVACY_URL) },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Datenschutz") }
            TextButton(
                onClick = { showProblemDialog = true },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Problem melden") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        TextButton(
            onClick = { licensesExpanded = !licensesExpanded },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(if (licensesExpanded) "Open-Source-Lizenzen ausblenden" else "Open-Source-Lizenzen")
        }

        AnimatedVisibility(visible = licensesExpanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                NoticeGroup(title = "Verwendete Bibliotheken", notices = libraryNotices)
                Spacer(modifier = Modifier.height(12.dp))
                NoticeGroup(title = "Daten und Dienste", notices = dataNotices)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Trailscape selbst steht unter der GNU General Public License, " +
                        "Version 3 oder später. Du darfst die App benutzen, weitergeben und " +
                        "verändern — abgeleitete Versionen müssen ihrerseits quelloffen " +
                        "unter der GPL stehen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { uriHandler.openUri(LICENSE_URL) },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Lizenztext lesen") }
            }
        }
    }

    if (showProblemDialog) {
        ProblemReportDialog(
            healthDiagnostics = syncReport?.debugLines.orEmpty(),
            onDismiss = { showProblemDialog = false },
        )
    }
}

/**
 * Ein Block der Lizenzliste. Jeder Eintrag ist antippbar und oeffnet die
 * Quelle im Browser — ohne Link waere die Angabe fuer niemanden pruefbar.
 */
@Composable
private fun NoticeGroup(title: String, notices: List<LicenseNotice>) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
    notices.forEach { notice ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { uriHandler.openUri(notice.url) }
                .padding(vertical = 4.dp),
        ) {
            Text(text = notice.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = notice.license,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
