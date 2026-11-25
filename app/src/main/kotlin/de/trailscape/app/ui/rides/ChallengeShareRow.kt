package de.trailscape.app.ui.rides

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.challenge.ChallengeDraftStore
import de.trailscape.app.challenge.ChallengeRouteInfo
import de.trailscape.app.challenge.challengeRouteInfo
import de.trailscape.app.challenge.shareChallengeFile
import de.trailscape.app.data.AppServices
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.indoor.ChallengeShareDialog
import de.trailscape.app.ui.indoor.IndoorChallengeController
import de.trailscape.core.Ride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * # „Herausforderung teilen" im Blatt „Teilen" einer Indoor-Tour
 *
 * Der Platz laut `docs/ux/umbau.md`: nicht als Block in der Auswertung,
 * sondern als Zeile unter den anderen Wegen des Teilens ([RideShareDialog],
 * `extraRows`). Die Zeile erscheint nur fuer Touren, zu denen der
 * Indoor-Ablauf beim Speichern einen Entwurf abgelegt hat
 * ([ChallengeDraftStore]) — bekannte Strecke oder Teststrecke, ab dem Start,
 * lang genug. Fuer alle anderen Touren steht hier nichts.
 *
 * Der Dialog danach ist derselbe wie frueher in der Auswertung
 * ([ChallengeShareDialog]): Name und Datum sind frei, die Vorschau zeigt den
 * Inhalt der Datei. Geteilt wird ueber das System, Trailscape laedt nichts hoch.
 */
@Composable
internal fun ChallengeShareRow(ride: Ride, modifier: Modifier = Modifier) {
    if (!ride.indoor) return
    val loaded by produceState<Pair<ChallengeDraftStore.Draft, ChallengeRouteInfo>?>(null, ride.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val draft = AppServices.challengeDraftStore.load(ride.id) ?: return@runCatching null
                // Die Passung zur Strecke einmal vorab lesen: Die Vorschau baut sich bei jedem Tastendruck neu.
                val info = challengeRouteInfo(
                    draft.challenge.route,
                    entry = { id -> runCatching { AppServices.indoorCatalogStore.catalog().entry(id) }.getOrNull() },
                    installedVersion = { id -> runCatching { AppServices.indoorCatalogStore.installed()[id]?.version }.getOrNull() },
                )
                draft to info
            }.getOrNull()
        }
    }
    val (draft, info) = loaded ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(ride.id, draft) {
        IndoorChallengeController(
            store = null,
            routeInfo = { info },
            scope = scope,
            io = Dispatchers.IO,
            kv = AppServices.keyValueStore,
            nowMs = System::currentTimeMillis,
        )
    }
    val share by controller.share.collectAsStateWithLifecycle()

    ChallengeShareRowContent(reply = draft.reply, onClick = { controller.openShareOf(draft) }, modifier = modifier)

    share?.let { ui ->
        ChallengeShareDialog(
            ui = ui,
            onNameChange = controller::setShareName,
            onIncludeDateChange = controller::setShareIncludeDate,
            onShare = {
                val json = ui.json
                val fileId = ui.fileId
                if (json != null && fileId != null) {
                    scope.launch {
                        runCatching { shareChallengeFile(context, json, fileId) }
                        controller.closeShare()
                    }
                }
            },
            onDismiss = controller::closeShare,
        )
    }
}

/** Die Zeile selbst — zustandslos, fuer den Screenshot-Test. */
@Composable
internal fun ChallengeShareRowContent(reply: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NeutralButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.EmojiEvents, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (reply) R.string.spiel3_result_reply else R.string.spiel3_share_entry))
    }
}
