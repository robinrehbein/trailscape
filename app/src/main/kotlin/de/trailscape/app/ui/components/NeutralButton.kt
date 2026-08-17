package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * # Der neutrale contained-Knopf von One UI
 *
 * One UI kennt nur zwei Knopffamilien: **contained** (gefuellte Flaeche) und
 * **flat** (Text). Eine Outline-Variante wie bei Material existiert nicht —
 * die Nebenaktion ist dort eine gefuellte *helle* Flaeche neben der gefuellten
 * Primaeraktion, keine Umrandung. Dieser Knopf ist genau das: Pille aus dem
 * Theme, 48 dp hoch wie jeder Contained-Knopf von One UI, Flaeche
 * `surfaceContainerHighest`, Schrift `onSurface`.
 *
 * Fuer destruktive Nebenaktionen („Plan loeschen", „Alle loeschen") gibt es
 * [destructive]: dieselbe Flaeche in der Fehler-Tonung des Schemas — rot als
 * *Flaeche*, nicht als Umrandung, denn Farbe als Flaeche faellt auch im
 * Augenwinkel auf.
 */
@Composable
fun NeutralButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        // `heightIn` statt `height`: 48 dp bleiben die Mindestflaeche, aber
        // der Knopf darf wachsen. Bei doppelter Schriftgroesse — bis dahin
        // muss die App laut Leitfaden ohne Funktionsverlust tragen — braucht
        // schon eine einzeilige Beschriftung rund 40 dp; eine zweizeilige
        // wurde von der frueheren festen Hoehe glatt abgeschnitten.
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
        content = content,
    )
}
