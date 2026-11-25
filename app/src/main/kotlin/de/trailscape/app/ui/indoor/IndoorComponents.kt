package de.trailscape.app.ui.indoor

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.indoor.TECHNICAL_DETAIL_MAX_CHARS
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.core.indoor.world.WorldAttribution
import kotlinx.coroutines.launch

/**
 * # Gemeinsamer Rahmen der Ablauf-Bildschirme
 *
 * Kopfzeile ([ScreenHeader] mit „Zurück"), darunter ein scrollender Inhalt und
 * — wenn gesetzt — eine feste Aktionsleiste unten, damit „Weiter" auf jeder
 * Bildschirmhoehe erreichbar bleibt. Unten bleibt die Bodenfreiheit der
 * Huelle frei ([LocalFloatingNavigationBarSpace]): auf der Startseite des
 * Indoor-Tabs die schwebende Navigationsleiste, auf allen anderen Seiten des
 * Ablaufs (Leiste ausgeblendet) nur der System-Rand.
 *
 * @param headerExtra steht direkt unter der Kopfzeile, vor dem Inhalt — die
 *   Segmente des Indoor-Tabs.
 */
@Composable
internal fun IndoorPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    overline: String? = null,
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    headerExtra: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Mindestens der System-Rand — auch dort, wo keine Huelle die
    // Bodenfreiheit stellt (Vorschauen, Tests).
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = maxOf(LocalFloatingNavigationBarSpace.current, systemBottom)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Oben und seitlich loest der Ablauf die Insets selbst auf (der
        // `NavHost` tut es fuer `indoor` nicht, damit die Fahransicht randlos
        // sein kann); unten nur der System-Rand.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(bottom = bottomSpace),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScreenHeader(
                    title = title,
                    overline = overline,
                    backLabel = if (onBack != null) stringResource(R.string.indoor_app_action_back) else null,
                    onBack = onBack,
                    actions = actions,
                )
                headerExtra?.invoke()
                content()
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (bottomBar != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = bottomBar,
                )
            }
        }
    }
}

/** Ein Absatz gedaempfter Fliesstext. */
@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Die Quellenangaben einer Welt als Liste: Art, Text, Lizenz, Adresse.
 * Verbindlich laut docs/indoor.md 8.6 — waehrend der Fahrt ueber das „ⓘ",
 * dauerhaft in der Weltverwaltung.
 */
@Composable
internal fun AttributionList(attribution: List<WorldAttribution>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (attribution.isEmpty()) {
            MutedText(stringResource(R.string.indoor_app_attribution_none))
        }
        attribution.forEach { item ->
            Column {
                Text(
                    text = geoSourceLabel(item.source),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = item.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.indoor_app_attribution_license, item.license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.url.isNotBlank()) {
                    Text(
                        text = item.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Zeile „Beschriftung … Wert" in Karten. */
@Composable
internal fun LabelValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Innenabstand fuer Karteninhalt dieser Bildschirme. */
internal val IndoorCardPadding = CardPadding

/**
 * Die **technische Einzelheit** unter einer Fehlerkarte: klein, auswaehlbar,
 * mit „Einzelheit kopieren" (Zwischenablage), damit sie sich melden laesst.
 * Kein Text bei leerem [detail].
 */
@Composable
internal fun TechnicalDetail(detail: String?, modifier: Modifier = Modifier) {
    if (detail.isNullOrBlank()) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(detail) { mutableStateOf(false) }
    val short = if (detail.length <= TECHNICAL_DETAIL_MAX_CHARS) detail else detail.take(TECHNICAL_DETAIL_MAX_CHARS - 1) + "…"
    val line = stringResource(R.string.indoor_app_technical_detail, short)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SelectionContainer {
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                scope.launch {
                    runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, line))) }
                }
                copied = true
            },
        ) {
            Text(
                stringResource(
                    if (copied) R.string.indoor_app_technical_detail_copied else R.string.indoor_app_technical_detail_copy,
                ),
            )
        }
    }
}
