package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.TabletCardGap
import de.trailscape.app.ui.theme.useTwoPaneLayout

/**
 * Zwei-Spalten-Geruest fuer das Querformat auf Tablets ([WindowLayoutClass.Expanded]).
 * Auf Telefon und Tablet hochkant stehen beide Bereiche untereinander.
 */
@Composable
fun TwoPaneLayout(
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    primaryWeight: Float = 0.56f,
    gap: Dp = TabletCardGap,
) {
    val twoPane = useTwoPaneLayout(LocalWindowLayoutClass.current)
    if (twoPane) {
        Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Box(modifier = Modifier.weight(primaryWeight)) { primary() }
            Box(modifier = Modifier.weight(1f - primaryWeight)) { secondary() }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            primary()
            secondary()
        }
    }
}
