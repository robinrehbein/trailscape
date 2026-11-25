package de.trailscape.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.training.readLimited
import de.trailscape.app.ui.training.recipeProblemMessage
import de.trailscape.core.MAX_RECIPE_BYTES
import de.trailscape.core.RecipeProblem
import de.trailscape.core.RecipeRejection
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Annahme einer Plan-Rezept-Datei von aussen („Oeffnen mit"/„Teilen"):
 * welche URIs die [PlanRecipeImportActivity] ueberhaupt anfasst, die
 * Lesegrenze und die Saetze zu jeder Ablehnung.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class PlanRecipeImportTest {

    private val own = "de.trailscape.app"
    private val recipe = Uri.parse("content://com.android.providers.downloads.documents/document/17")

    @Test
    fun `VIEW mit content-URI wird angenommen`() {
        val intent = Intent(Intent.ACTION_VIEW).setData(recipe)
        assertEquals(recipe, recipeUriFromIntent(intent, own))
    }

    @Test
    fun `SEND nimmt den Stream`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, recipe)
        assertEquals(recipe, recipeUriFromIntent(intent, own))
    }

    @Test
    fun `file-URIs und eigene Anbieter werden abgewiesen`() {
        assertNull(recipeUriFromIntent(Intent(Intent.ACTION_VIEW).setData(Uri.parse("file:///data/data/de.trailscape.app/files/rides/a.json")), own))
        assertNull(recipeUriFromIntent(Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://de.trailscape.app.fileprovider/x/y.tsplan.json")), own))
        assertNull(recipeUriFromIntent(Intent(Intent.ACTION_VIEW), own))
        assertNull(recipeUriFromIntent(Intent(Intent.ACTION_SEND), own))
    }

    @Test
    fun `Lesen hoert nach der Grenze auf`() {
        val big = ByteArray(MAX_RECIPE_BYTES * 3) { 'a'.code.toByte() }
        val read = readLimited(ByteArrayInputStream(big))
        assertEquals(MAX_RECIPE_BYTES + 1, read.size)
        assertEquals(10, readLimited(ByteArrayInputStream(ByteArray(10))).size)
        assertEquals(0, readLimited(ByteArrayInputStream(ByteArray(0))).size)
    }

    @Test
    fun `jede Ablehnung hat einen Satz`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        RecipeProblem.entries.forEach { problem ->
            val text = recipeProblemMessage(context, RecipeRejection(problem, path = "weeks[0].title", version = 3))
            assertTrue("Satz fuer $problem", text.isNotBlank())
        }
        assertTrue(recipeProblemMessage(context, RecipeRejection(RecipeProblem.NEWER_VERSION, version = 3)).contains("3"))
        assertTrue(recipeProblemMessage(context, RecipeRejection(RecipeProblem.INVALID_FIELD, path = "weeks[2].title")).contains("weeks[2].title"))
        assertNotNull(recipeProblemMessage(context, RecipeRejection(RecipeProblem.INVALID_FIELD)))
    }
}
