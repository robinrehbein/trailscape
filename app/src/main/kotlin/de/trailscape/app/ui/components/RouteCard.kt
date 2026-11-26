package de.trailscape.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalAtlasColors
import de.trailscape.app.ui.theme.tabular

private val RouteMediaShape = RoundedCornerShape(14.dp)

/**
 * Einheitliche Streckenkarte im Atlas-Stil.
 *
 * Zeigt immer Bild/Vorschau, **Laenge**, **Hoehenmeter** und **Dauer**
 * (Regel aus `HANDOFF-antigravity.md`), optional Status-Chip auf dem Bild
 * sowie eine untergeordnete oder primaere Aktion.
 */
@Composable
fun RouteCard(
    title: String,
    distanceText: String,
    ascentText: String,
    durationText: String,
    media: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    statusLabel: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val atlas = LocalAtlasColors.current
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val border = if (selected) MaterialTheme.colorScheme.primary else atlas.cardBorder

    AtlasCard(
        modifier = modifier,
        onClick = onClick,
        containerColor = container,
        borderColor = border,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 10.dp)
                .aspectRatio(16f / 10f)
                .clip(RouteMediaShape)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Box(modifier = Modifier.fillMaxSize(), content = media)
            if (statusLabel != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = CircleShape,
                    color = atlas.routeMediaScrim,
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RouteFactText(distanceText)
                RouteFactText(ascentText)
                RouteFactText(durationText)
            }
            if (action != null) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    action()
                }
            }
        }
    }
}

@Composable
private fun RouteFactText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.tabular(),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
