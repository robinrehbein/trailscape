package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.update.RELEASE_PAGE_URL

/**
 * Der Update-Hinweis oben im Mehr-Tab.
 *
 * Die App verteilt sich als APK ueber GitHub-Releases; es gibt keinen Store,
 * der von selbst aktualisiert. Diese Karte ist — neben der einmaligen
 * Snackbar beim Start — die einzige Stelle, an der eine neue Version
 * ueberhaupt auffaellt.
 *
 * Bewusst **dezent und schliessbar**: getoente Karte statt Signalfarbe, kein
 * Dialog, kein Sperren der Oberflaeche. Wer sie wegwischt, sieht sie fuer
 * diese Version nie wieder (siehe
 * [de.trailscape.app.update.UpdateChecker.dismiss]); die naechste Version
 * meldet sich erneut. „Herunterladen" oeffnet die Release-Seite im Browser —
 * die Installation der APK bleibt Sache des Systems, die App laedt und
 * installiert nichts selbst.
 */
@Composable
fun UpdateNoticeCard(
    versionName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Version $versionName ist verfügbar",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Die neue APK liegt auf der Release-Seite — herunterladen und " +
                            "über die bestehende Installation legen. Deine Touren bleiben " +
                            "erhalten.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Hinweis ausblenden",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { uriHandler.openUri(RELEASE_PAGE_URL) }) {
                    Text("Herunterladen")
                }
            }
        }
    }
}
