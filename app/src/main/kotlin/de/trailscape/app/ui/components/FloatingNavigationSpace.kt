package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.ScreenPadding

/**
 * # Bodenfreiheit fuer die schwebende Navigationskapsel
 *
 * Die [OneUiNavigationBar] liegt — wie in One UI 8.5/9 ueblich — **ueber** dem
 * Inhalt: Eine Liste scrollt unter ihr hindurch, statt an einer Leiste
 * abgeschnitten zu werden. Damit das letzte Element trotzdem erreichbar bleibt,
 * braucht jeder Bildschirm am Ende so viel Luft, wie die Kapsel samt Rand und
 * Gestenleiste hoch ist. Genau diese Zahl steht hier; gestellt wird sie einmal
 * von `TrailscapeApp`, gelesen von jedem Bildschirm, der etwas am unteren Rand
 * hat:
 *
 *  * scrollende Listen und Spalten → [screenContentPadding] bzw.
 *    `Modifier.padding(bottom = LocalFloatingNavigationBarSpace.current)`,
 *  * schwebende Knoepfe und Kartenpanels → dasselbe Padding,
 *  * `SnackbarHost` → dasselbe Padding, sonst erscheint die Meldung hinter der
 *    Kapsel.
 *
 * Der Default ist 0 dp: Wer die Huelle nicht darum herum hat (Einfuehrung,
 * Vorschauen, Tests), rechnet mit keiner Kapsel — und bekommt dann auch keine
 * ins Leere laufende Luft.
 */
val LocalFloatingNavigationBarSpace = compositionLocalOf { 0.dp }

/**
 * Der Innenabstand einer Bildschirmliste: rundherum [ScreenPadding], unten
 * zusaetzlich die Bodenfreiheit der Kapsel
 * ([LocalFloatingNavigationBarSpace]).
 *
 * Wer eine `LazyColumn` auf oberster Ebene baut, nimmt diesen Wert als
 * `contentPadding` — dann steht die Liste ueberall gleich und laeuft trotzdem
 * sichtbar unter der Kapsel aus.
 */
@Composable
@ReadOnlyComposable
fun screenContentPadding(extraBottom: Dp = 0.dp): PaddingValues = PaddingValues(
    start = ScreenPadding,
    top = ScreenPadding,
    end = ScreenPadding,
    bottom = ScreenPadding + extraBottom + LocalFloatingNavigationBarSpace.current,
)
