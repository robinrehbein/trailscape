package de.trailscape.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trailscape.app.ui.theme.CardPadding

/**
 * # Die eine Kopfzeile aller Screens (Fuehrung „Klartext")
 *
 * Heute, Verlauf, Training, Einstellungen und die Tour-Detailansicht hatten
 * vier verschiedene Koepfe: eine eigene Datumszeile, eine Symbolreihe ueber
 * dem Titel, die zentrierte One-UI-Grossleiste (40 % leerer Bildschirm) und
 * eine Leiste ohne Titel. Jetzt teilen sie diesen Baustein und stehen damit
 * pixelgleich:
 *
 *  * oben eine 48-dp-Zeile — links optional „‹ Zurück", rechts die Aktionen
 *    (Suche, +, ⚙),
 *  * darunter optional eine gedaempfte Zeile ([overline], z. B. das Datum),
 *  * dann der Titel: 32 sp, fett, linksbuendig — buendig mit dem Text in
 *    den Karten darunter ([CardPadding] innerhalb der Bildschirmkante).
 *
 * Der Kopf scrollt mit dem Inhalt weg; er gehoert als erstes Element in die
 * Liste des Screens.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(HeaderBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                // Zurueck als Pille aus Pfeil und Wort — eine grosse,
                // eindeutige Trefferflaeche statt eines nackten Pfeils.
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onBack)
                        .heightIn(min = 48.dp)
                        .padding(start = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (backLabel == null) "Zurück" else null,
                        modifier = Modifier.padding(8.dp).size(24.dp),
                    )
                    if (backLabel != null) {
                        Text(
                            text = backLabel,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        Column(modifier = Modifier.padding(start = CardPadding, end = CardPadding)) {
            if (overline != null) {
                Text(
                    text = overline,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                style = HeaderTitleStyle(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(HeaderBottomGap))
    }
}

@Composable
private fun HeaderTitleStyle() = MaterialTheme.typography.headlineLarge.copy(
    fontSize = 32.sp,
    lineHeight = 40.sp,
    fontWeight = FontWeight.W700,
    letterSpacing = (-0.5).sp,
)

/** Hoehe der oberen Zeile mit Zurueck und Aktionen. */
private val HeaderBarHeight = 48.dp

/** Luft zwischen Titel und erstem Inhalt. */
private val HeaderBottomGap = 4.dp
