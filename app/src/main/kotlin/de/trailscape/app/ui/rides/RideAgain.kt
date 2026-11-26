package de.trailscape.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding

/**
 * # „Nochmal fahren" — die eine Sammelstelle fuers Nachfahren
 *
 * Das Tourdetail hat genau einen festen Hauptknopf (docs/ux/seitenkarte.md,
 * Befund 4 und „Blatt Nochmal fahren"). Was er anbietet, steht hier als reine
 * Funktion ([rideAgainOptions]); das Blatt ([RideAgainSheet]) zeichnet nur.
 *
 * Wer eine weitere Art des Nachfahrens einhaengt, ergaenzt einen Wert in
 * [RideAgainOption], seine Bedingung in [rideAgainOptions] und eine Zeile im
 * Blatt — nicht einen weiteren Knopf im Tourdetail.
 */
internal enum class RideAgainOption {
    /** Die Spur als Route auf die Karte legen und draussen mit Navigation fahren. */
    OUTDOOR,

    /** Dieselbe Strecke im Indoor-Tab fahren (bei einer gefahrenen Tour mit der Aussenfahrt als Mitfahrer). */
    INDOOR,

    /** Die Strecke als 3D-Vorschau ansehen, ohne Trainer. */
    PREVIEW_3D,
}

/**
 * Welche Arten des Nachfahrens eine Tour anbietet, in der Reihenfolge des
 * Blatts. Die Bedingungen sind dieselben, die vorher an den drei Einzelknoepfen
 * des Tourdetails standen:
 *
 *  * draussen: jede Tour oder Planung mit einer Spur (mindestens zwei Punkte),
 *    auch eine Indoor-Tour;
 *  * Indoor fahren und 3D-Vorschau: nur fuer Aussenfahrten und Planungen mit
 *    Spur — die Punkte einer Indoor-Tour sind die virtuelle Spur einer anderen
 *    Strecke.
 *
 * @param indoorAvailable `false`, wenn der Aufrufer keinen Weg ins Indoor hat.
 * @param previewAvailable `false`, wenn der Aufrufer keine 3D-Vorschau hat.
 */
internal fun rideAgainOptions(
    indoor: Boolean,
    pointCount: Int,
    indoorAvailable: Boolean = true,
    previewAvailable: Boolean = true,
): List<RideAgainOption> {
    if (pointCount < 2) return emptyList()
    val options = mutableListOf(RideAgainOption.OUTDOOR)
    if (!indoor && indoorAvailable) options += RideAgainOption.INDOOR
    if (!indoor && previewAvailable) options += RideAgainOption.PREVIEW_3D
    return options
}

/**
 * Das Blatt hinter dem Hauptknopf des Tourdetails: je Art eine beschriftete
 * Zeile mit einem Satz darunter. Zustandslos; die Auswahl geht nach oben, der
 * Aufrufer schliesst das Blatt.
 *
 * @param planned die Tour ist eine gespeicherte Planung (anderer Titel, anderer Satz bei „Indoor fahren").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideAgainSheet(
    options: List<RideAgainOption>,
    planned: Boolean,
    onPick: (RideAgainOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CardPadding)
                .padding(bottom = CardPadding)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            Text(
                text = stringResource(
                    if (planned) R.string.rides_again_planned_action else R.string.rides_detail_ride_again_action,
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            options.forEach { option ->
                when (option) {
                    RideAgainOption.OUTDOOR -> RideAgainRow(
                        icon = Icons.Filled.Navigation,
                        title = stringResource(R.string.rides_again_outdoor_title),
                        body = stringResource(R.string.rides_again_outdoor_body),
                        onClick = { onPick(option) },
                    )
                    RideAgainOption.INDOOR -> RideAgainRow(
                        icon = Icons.Filled.PedalBike,
                        title = stringResource(R.string.rides_again_indoor_title),
                        body = stringResource(
                            if (planned) R.string.rides_again_indoor_planned_body else R.string.rides_again_indoor_body,
                        ),
                        onClick = { onPick(option) },
                    )
                    RideAgainOption.PREVIEW_3D -> RideAgainRow(
                        icon = Icons.Filled.ViewInAr,
                        title = stringResource(R.string.rides_again_preview_title),
                        body = stringResource(R.string.rides_again_preview_body),
                        onClick = { onPick(option) },
                    )
                }
            }
        }
    }
}

/** Eine antippbare Zeile des Blatts: Symbol, Titel, ein Satz. */
@Composable
private fun RideAgainRow(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    AtlasCard(
        onClick = onClick,
        modifier = Modifier.semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = CardPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
