package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

private const val REPOSITORY_URL = "https://github.com/robinrehbein/trailscape"

/**
 * „Über"-Karte — Port des Fliesstexts aus der letzten Karte in
 * `lib/screens/more_screen.dart`.
 *
 * Ergaenzt gegenueber dem Original um Versionsnummer und Repository-Link
 * (explizit im Task fuer den nativen Rewrite gefordert; das Flutter-Original
 * zeigt beides nicht an). Die Version kommt ueber [android.content.pm.PackageManager]
 * statt `BuildConfig`, weil `:app` das `buildConfig`-Feature nicht aktiviert
 * (`build.gradle.kts` dieser Datei darf nicht geaendert werden).
 */
@Composable
fun AboutCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unbekannt"
    }

    MoreSectionCard(title = "Über", modifier = modifier) {
        Text(
            text = "Trailscape ist kostenlos und local-first: deine Touren bleiben auf " +
                "deinem Gerät, ein Sync-Server ist optional. Kartendaten © " +
                "OpenStreetMap-Mitwirkende, Routing über BRouter.",
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Version $versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            onClick = { uriHandler.openUri(REPOSITORY_URL) },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text("Quellcode auf GitHub")
        }
    }
}
