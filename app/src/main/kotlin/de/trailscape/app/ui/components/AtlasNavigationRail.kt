package de.trailscape.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.LocalNavigationBarColors
import de.trailscape.app.ui.theme.NavRailWidth
import de.trailscape.app.ui.theme.OneUiMotion

/**
 * Linke Navigationsleiste (Rail) fuer Tablets im Hoch- und Querformat
 * (Galaxy Tab S11): oben der orangefarbene Fahren-Knopf, mittig die vier
 * Hauptziele, unten das Zahnrad fuer Einstellungen.
 */
@Composable
fun AtlasNavigationRail(
    topAction: @Composable () -> Unit,
    bottomAction: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalNavigationBarColors.current
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.container)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .width(NavRailWidth)
                .fillMaxHeight()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(contentAlignment = Alignment.Center) {
                topAction()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
            Box(contentAlignment = Alignment.Center) {
                bottomAction()
            }
        }
        Spacer(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(colors.rim),
        )
    }
}

/**
 * Ein Ziel in der [AtlasNavigationRail].
 */
@Composable
fun AtlasNavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNavigationBarColors.current
    val haptics = LocalHapticFeedback.current
    val pillColor by animateColorAsState(
        targetValue = if (selected) colors.indicator else Color.Transparent,
        animationSpec = OneUiMotion.short(),
        label = "RailPill",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.selectedContent else colors.unselectedContent,
        animationSpec = OneUiMotion.short(),
        label = "RailText",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = {
                    if (!selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    onClick()
                },
            ),
        shape = MaterialTheme.shapes.extraSmall,
        color = pillColor,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
