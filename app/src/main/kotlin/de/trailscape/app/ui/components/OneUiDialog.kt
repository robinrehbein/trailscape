package de.trailscape.app.ui.components

import android.view.Gravity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import de.trailscape.app.ui.theme.CardPadding

/**
 * # Der One-UI-Dialog: unten, nicht in der Mitte
 *
 * Samsungs Designleitfaden ist an dieser Stelle unmissverstaendlich: Ein
 * Dialog, der **eine Entscheidung verlangt**, sitzt am **unteren** Rand und
 * nimmt am Telefon die **volle Breite** ein. Mittig steht nur, was gar keine
 * Aktion zulaesst — eine Fortschrittsanzeige etwa, waehrend ein Import laeuft.
 * Samsungs eigenes Codebeispiel zeigt dafuer schlicht
 * `setGravity(Gravity.BOTTOM)`.
 *
 * Der Grund ist derselbe wie bei der schwebenden Navigationskapsel und der
 * hohen Kopfzeile: One UI teilt den Bildschirm in eine ruhige Ansichtszone
 * oben und eine Bedienzone unten. Ein Dialog, dessen Knoepfe in
 * Bildschirmmitte schweben, zwingt den Daumen quer ueber ein 6,7-Zoll-Display.
 *
 * Vorher liefen in dieser App **alle** Dialoge ueber den Material-Standard:
 * mittig, rund 90 % breit. Von den sechzehn Fundstellen verlangten dreizehn
 * eine Entscheidung.
 *
 * ## Warum nicht einfach [AlertDialog] mit anderem Modifier
 * Die Position eines Dialogs bestimmt nicht sein Inhalt, sondern sein
 * **Fenster** — und an das kommt man in Compose nur ueber
 * [DialogWindowProvider]. [BasicAlertDialog] ist der von Material dafuer
 * vorgesehene Baustein: Er bringt Scrim, Abbruch per Zurueck-Geste, Antippen
 * ausserhalb und die Bildschirmlesehilfen-Semantik mit und ueberlaesst den
 * Inhalt vollstaendig dem Aufrufer.
 *
 * ## Knoepfe
 * Flach, rechtsbuendig, Bestaetigung rechts. Der Leitfaden verlangt flache
 * Knoepfe in Dialogen ausdruecklich — eine gefuellte Flaeche waere hier eine
 * zusaetzliche Ebene ohne Gewinn — und er verbietet, flache und gefuellte
 * Knoepfe zu mischen. Beides ergibt sich hier von selbst, weil die Knoepfe
 * nicht mehr vom Aufrufer kommen, sondern von dieser Datei.
 *
 * @param confirmButton Die bejahende Aktion. Steht rechts.
 * @param dismissButton Die verneinende Aktion, falls es eine gibt. Steht links
 *   davon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneUiDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        // Ohne dies bemisst Android das Fenster auf die Plattformbreite und
        // haelt es mittig; erst danach duerfen Schwerkraft und Breite unten
        // gesetzt werden.
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
        ),
        modifier = modifier,
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setGravity(Gravity.BOTTOM)
            // Volle Breite, Hoehe nach Inhalt. Dass das Fenster den oberen
            // Bildschirmteil frei laesst, ist kein Nebeneffekt, sondern der
            // Grund, warum Antippen ausserhalb weiterhin schliesst.
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                // Nicht ganz bis an die Kante: Die 8 dp lassen die Rundung
                // stehen, ohne die geforderte volle Breite aufzugeben.
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            Column(modifier = Modifier.padding(CardPadding)) {
                if (icon != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.secondary,
                            content = icon,
                        )
                    }
                }
                if (title != null) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                        }
                    }
                }
                if (text != null) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissButton != null) dismissButton()
                    confirmButton()
                }
            }
        }
    }
}

