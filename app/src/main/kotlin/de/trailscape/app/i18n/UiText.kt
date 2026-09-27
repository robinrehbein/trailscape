package de.trailscape.app.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Ein Text, der erst beim Anzeigen in der aktuellen Sprache aufgeloest wird.
 *
 * Fuer Formulierungslogik ausserhalb von Composables (…Wording.kt, Status-
 * Texte, ViewModel-Meldungen): Sie liefert ein [UiText] statt eines fertigen
 * Strings und bleibt damit als reiner JVM-Unit-Test pruefbar — der Test
 * vergleicht `UiText.Res(R.string.x, listOf(…))`, ganz ohne Robolectric.
 *
 * Argumente duerfen selbst [UiText] sein; sie werden rekursiv aufgeloest
 * (etwa ein Sprachname in „Wie System (%1$s)").
 */
sealed interface UiText {
    /** Ein fertiger Text — Namen, Nutzereingaben, Fremdtexte (Fehlermeldungen). */
    data class Plain(val text: String) : UiText

    /** Ein String-Ressourcen-Text mit positionsbezogenen Argumenten. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /**
     * Ein Plural-Text. [args] sind die Formatargumente; ohne Angabe ist das
     * einzige Argument [count] selbst (`%1$d`).
     */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Loest den Text ueber die Ressourcen von [context] auf. */
    fun resolve(context: Context): String = when (this) {
        is Plain -> text
        is Res -> if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *resolveArgs(args, context))
        }
        is Plural -> context.resources.getQuantityString(id, count, *resolveArgs(args, context))
    }

    companion object {
        private fun resolveArgs(args: List<Any>, context: Context): Array<Any> =
            args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()
    }
}

/** Loest den Text in der Sprache der Komposition auf. */
@Composable
fun UiText.asString(): String {
    // Lesen der Konfiguration, damit ein Konfigurationswechsel neu aufloest.
    LocalConfiguration.current
    return resolve(LocalContext.current)
}
