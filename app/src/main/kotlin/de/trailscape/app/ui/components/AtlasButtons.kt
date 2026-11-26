package de.trailscape.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.LocalAtlasColors
import de.trailscape.app.ui.theme.PrimaryButtonMinHeight

private val PrimaryButtonShape = RoundedCornerShape(16.dp)

/**
 * Die **eine** orangefarbene Hauptaktion pro Atlas-Bildschirm.
 *
 * Nur die wichtigste Handlung einer Ansicht nutzt diesen Baustein; alle
 * weiteren Aktionen nutzen [TrailscapeSecondaryButton] oder Textknoepfe.
 */
@Composable
fun TrailscapePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val atlas = LocalAtlasColors.current
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = PrimaryButtonMinHeight),
        enabled = enabled,
        shape = PrimaryButtonShape,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = atlas.action,
            contentColor = atlas.onAction,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        content = content,
    )
}

/**
 * Ruhiger Nebenknopf im Atlas-Stil mit feiner Kartenkante.
 */
@Composable
fun TrailscapeSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val atlas = LocalAtlasColors.current
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = PrimaryButtonShape,
        border = if (destructive) null else BorderStroke(1.dp, atlas.cardBorder),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
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
