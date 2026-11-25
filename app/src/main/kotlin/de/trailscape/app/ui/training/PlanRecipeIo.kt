package de.trailscape.app.ui.training

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import de.trailscape.app.R
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.core.MAX_RECIPE_BYTES
import de.trailscape.core.PLAN_RECIPE_MIME_TYPE
import de.trailscape.core.PlanRecipe
import de.trailscape.core.PlanRecipeParse
import de.trailscape.core.RecipeProblem
import de.trailscape.core.RecipeRejection
import de.trailscape.core.parsePlanRecipe
import de.trailscape.core.planRecipeFileName
import de.trailscape.core.toJsonText
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Datei-Ein- und Ausgang der Plan-Rezepte (Format: `docs/plan-rezepte.md`).
 *
 * Lesen: hoechstens [MAX_RECIPE_BYTES] Bytes werden gelesen — eine groessere
 * Datei wird nach dem ersten Byte darueber abgebrochen, nie ganz geladen.
 * Schreiben: ueber den FileProvider-Cache (Teilen) oder einen vom
 * Speicherdialog gelieferten Uri.
 */

/** Liest hoechstens [MAX_RECIPE_BYTES] + 1 Bytes aus [input]; mehr heisst „zu gross". */
internal fun readLimited(input: InputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total <= MAX_RECIPE_BYTES) {
        val n = input.read(buffer, 0, minOf(buffer.size, MAX_RECIPE_BYTES + 1 - total))
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}

/** Liest und prueft die Rezeptdatei hinter [uri]; wirft nie. */
internal suspend fun readRecipeFile(context: Context, uri: Uri): PlanRecipeOffer = withContext(Dispatchers.IO) {
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
            ?: return@withContext PlanRecipeOffer.Unreadable
        PlanRecipeOffer.fromParse(parsePlanRecipe(bytes))
    } catch (_: Exception) {
        PlanRecipeOffer.Unreadable
    }
}

/** Schreibt das Rezept an den vom Speicherdialog gelieferten [uri]. */
internal suspend fun writeRecipeFile(context: Context, uri: Uri, recipe: PlanRecipe) {
    withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw java.io.IOException("Kein Ausgabestrom")
        stream.use { it.write(recipe.toJsonText().toByteArray(Charsets.UTF_8)) }
    }
}

/** Teilt das Rezept ueber das System-Share-Sheet (eigener MIME-Typ, nie GPX/JSON-Backup). */
internal suspend fun shareRecipe(context: Context, recipe: PlanRecipe) {
    val uri = withContext(Dispatchers.IO) {
        val dir = prepareShareDirectory(context.cacheDir)
        val file = File(dir, planRecipeFileName(recipe.title))
        file.writeText(recipe.toJsonText(), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = PLAN_RECIPE_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, recipe.title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.recipe_share_chooser_title)))
}

/** Satz zu einer Ablehnung — Pfad und Version werden eingesetzt. */
internal fun recipeProblemMessage(context: Context, rejection: RecipeRejection): String = when (rejection.problem) {
    RecipeProblem.TOO_LARGE -> context.getString(R.string.recipe_problem_too_large)
    RecipeProblem.NOT_TEXT -> context.getString(R.string.recipe_problem_not_text)
    RecipeProblem.NOT_JSON -> context.getString(R.string.recipe_problem_not_json)
    RecipeProblem.TOO_DEEP -> context.getString(R.string.recipe_problem_too_deep)
    RecipeProblem.NOT_A_RECIPE -> context.getString(R.string.recipe_problem_not_recipe)
    RecipeProblem.NEWER_VERSION -> context.getString(R.string.recipe_problem_newer, rejection.version ?: 0)
    RecipeProblem.INVALID_FIELD ->
        rejection.path?.let { context.getString(R.string.recipe_problem_invalid, it) }
            ?: context.getString(R.string.recipe_problem_invalid_nopath)
    RecipeProblem.TOO_MANY -> context.getString(R.string.recipe_problem_too_many)
    RecipeProblem.EMPTY -> context.getString(R.string.recipe_problem_empty)
}

/** Ergebnis des Einlesens einer Rezeptdatei — das, was der Dialog zeigt. */
sealed interface PlanRecipeOffer {
    data class Readable(val recipe: PlanRecipe) : PlanRecipeOffer
    data class Rejected(val rejection: RecipeRejection) : PlanRecipeOffer

    /** Die Datei liess sich gar nicht oeffnen. */
    data object Unreadable : PlanRecipeOffer

    companion object {
        fun fromParse(parse: PlanRecipeParse): PlanRecipeOffer = when (parse) {
            is PlanRecipeParse.Ok -> Readable(parse.recipe)
            is PlanRecipeParse.Rejected -> Rejected(parse.rejection)
        }
    }
}

/**
 * Ablage fuer ein gerade geoeffnetes Rezept: Die `PlanRecipeImportActivity`
 * („Oeffnen mit"/„Teilen") und der Dateiwaehler der Plan-Seite legen hier ab,
 * der Dialog des Hosts ([PlanRecipeImportHost]) zeigt es an.
 *
 * Prozessweit und fluechtig — wie `PendingImports`: Stirbt der Prozess vor der
 * Bestaetigung, ist das Oeffnen verloren und die Datei laesst sich erneut
 * oeffnen. Es wird nie etwas uebernommen, bevor jemand bestaetigt.
 */
object PendingPlanRecipe {
    private val state = MutableStateFlow<PlanRecipeOffer?>(null)
    val offer: StateFlow<PlanRecipeOffer?> = state.asStateFlow()

    fun offer(value: PlanRecipeOffer) {
        state.value = value
    }

    fun clear() {
        state.value = null
    }
}
