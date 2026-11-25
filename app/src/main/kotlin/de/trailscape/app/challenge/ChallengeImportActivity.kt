package de.trailscape.app.challenge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import de.trailscape.app.MainActivity
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.AppLocale
import de.trailscape.app.importSourcesFromIntent
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.languageOf
import de.trailscape.app.ui.indoor.ChallengeImportDialog
import de.trailscape.app.ui.indoor.ChallengeImportUi
import de.trailscape.app.ui.indoor.challengePreviewOf
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.challenge.CHALLENGE_MAX_BYTES
import de.trailscape.core.challenge.ChallengeParse
import de.trailscape.core.challenge.parseChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * „Öffnen mit" und „Teilen an" Trailscape für eine Datei `*.trailscape-challenge`.
 *
 * Im Unterschied zur [de.trailscape.app.ImportActivity] (GPX/FIT, durchsichtiges
 * Trampolin) hat diese Activity ein **eigenes kleines Fenster**: Sie liest die
 * Datei sofort (die Leseberechtigung gilt nur, solange sie lebt), prüft sie
 * streng ([parseChallenge]) und zeigt die **Vorschau** — was die Datei
 * enthält —, bevor etwas gespeichert wird. Erst „Speichern" legt sie im
 * [ChallengeStore] ab. Danach kann man Trailscape öffnen und landet im Tab
 * „Indoor" auf dem Segment „Herausforderungen" ([EXTRA_OPEN_CHALLENGES]).
 *
 * Es gelten dieselben Schutzregeln wie bei der ImportActivity
 * (`importSourcesFromIntent`): nur `content://` und nie Trailscapes eigene
 * Anbieter, weil die Activity exportiert ist. Gelesen werden höchstens
 * [CHALLENGE_MAX_BYTES] Bytes.
 */
class ChallengeImportActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLocale.overrideConfiguration(newBase)?.let(::applyOverrideConfiguration)
    }

    private var ui by mutableStateOf<ChallengeImportUi?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrailscapeTheme {
                CompositionLocalProvider(
                    LocalAppLanguage provides languageOf(resources.configuration),
                    LocalAppFormats provides AppFormats(languageOf(resources.configuration)),
                ) {
                    ChallengeImportDialog(
                        ui = ui,
                        onSave = ::save,
                        onDismiss = { finish() },
                        onOpenApp = {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    // Gleich zum Segment „Herausforderungen" (docs/ux/umbau.md).
                                    .putExtra(EXTRA_OPEN_CHALLENGES, true),
                            )
                            finish()
                        },
                    )
                }
            }
        }
        if (savedInstanceState != null) return // Drehung/Neustart: Zustand bleibt, nicht neu lesen
        val uri = importSourcesFromIntent(intent, ownPackage = packageName).firstOrNull()?.uri
        if (uri == null) {
            ui = ChallengeImportUi.Failed(de.trailscape.core.challenge.ChallengeReject.NOT_JSON, null)
            return
        }
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { readChallengeBytes(this@ChallengeImportActivity, uri) }
            ui = when (val parsed = if (bytes == null) null else parseChallenge(bytes)) {
                null -> ChallengeImportUi.Failed(de.trailscape.core.challenge.ChallengeReject.TOO_LARGE, null)
                is ChallengeParse.Rejected -> ChallengeImportUi.Failed(parsed.reason, parsed.fileVersion)
                is ChallengeParse.Ok -> ChallengeImportUi.Preview(
                    challengePreviewOf(
                        parsed.challenge,
                        challengeRouteInfo(
                            parsed.challenge.route,
                            entry = { id -> runCatching { AppServices.indoorCatalogStore.catalog().entry(id) }.getOrNull() },
                            installedVersion = { id -> runCatching { AppServices.indoorCatalogStore.installed()[id]?.version }.getOrNull() },
                        ),
                    ),
                    parsed.challenge,
                )
            }
        }
    }

    private fun save() {
        val preview = ui as? ChallengeImportUi.Preview ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppServices.challengeStore.save(preview.challenge, System.currentTimeMillis())
            }
            ui = ChallengeImportUi.Saved(preview.preview, result)
        }
    }
}

/** Extra an der [MainActivity]: „Zeig die gespeicherten Herausforderungen." */
const val EXTRA_OPEN_CHALLENGES = "de.trailscape.app.extra.OPEN_CHALLENGES"

/**
 * Liest höchstens [CHALLENGE_MAX_BYTES] aus [uri]; `null`, wenn die Datei
 * größer ist oder nicht zu lesen war (dann gilt sie als zu groß/unlesbar).
 */
internal fun readChallengeBytes(context: Context, uri: Uri): ByteArray? = try {
    context.contentResolver.openInputStream(uri)?.use { readBoundedChallenge(it) }
} catch (_: Exception) {
    null
}
