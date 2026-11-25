package de.trailscape.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import de.trailscape.app.i18n.AppLocale
import de.trailscape.app.ui.training.PendingPlanRecipe
import de.trailscape.app.ui.training.PlanRecipeOffer
import de.trailscape.app.ui.training.readRecipeFile
import kotlinx.coroutines.launch

/**
 * Trampolin fuer „Oeffnen mit Trailscape" und „Teilen an Trailscape" bei einer
 * Plan-Rezept-Datei (`.tsplan.json`, MIME `application/vnd.trailscape.plan+json`,
 * Format in `docs/plan-rezepte.md`).
 *
 * Bewusst eine **eigene** Activity neben der `ImportActivity` (GPX/FIT): Beide
 * haben eigene Intent-Filter und teilen sich keinen Typ — der Backup-Import
 * (Dateiwaehler) und der GPX-Import nehmen ein Rezept deshalb nie an.
 *
 * Ablauf: Datei sofort lesen (die Leseberechtigung einer geteilten URI gilt nur,
 * solange diese Activity lebt; hoechstens 256 KiB), pruefen, das Ergebnis in
 * [PendingPlanRecipe] ablegen und die [MainActivity] nach vorn holen. Dort zeigt
 * der Host die Vorschau; **uebernommen wird erst nach ausdruecklicher
 * Bestaetigung**. Eine kaputte Datei fuehrt zur Meldung im selben Dialog, nie
 * zum Absturz.
 *
 * Nur `content:`-URIs, und nie die eigenen (der FileProvider der App): sonst
 * koennte eine fremde App Trailscape dazu bringen, eigene Dateien zu oeffnen.
 */
class PlanRecipeImportActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLocale.overrideConfiguration(newBase)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState != null || launchedFromHistory) {
            finish()
            return
        }
        val uri = recipeUriFromIntent(intent, packageName)
        lifecycleScope.launch {
            try {
                val offer = if (uri == null) PlanRecipeOffer.Unreadable else readRecipeFile(this@PlanRecipeImportActivity, uri)
                PendingPlanRecipe.offer(offer)
                startActivity(
                    Intent(this@PlanRecipeImportActivity, MainActivity::class.java)
                        .putExtra(EXTRA_PLAN_RECIPE_PENDING, true)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        ),
                )
            } finally {
                finish()
            }
        }
    }
}

/** Extra an der [MainActivity]: „In [PendingPlanRecipe] liegt ein Rezept fuer dich." */
const val EXTRA_PLAN_RECIPE_PENDING = "de.trailscape.app.extra.PLAN_RECIPE_PENDING"

/**
 * Die Datei hinter einem VIEW- oder SEND-Intent; `null`, wenn es keine
 * zulaessige gibt (kein `content:`-Schema oder ein Anbieter dieser App).
 */
internal fun recipeUriFromIntent(intent: Intent, ownPackage: String): Uri? {
    val candidate: Uri? = when (intent.action) {
        Intent.ACTION_SEND -> streamExtra(intent)
        else -> intent.data
    }
    return candidate?.takeIf { it.scheme == "content" && it.authority?.startsWith(ownPackage) != true }
}

@Suppress("DEPRECATION")
private fun streamExtra(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
