package de.trailscape.app.ui.rides

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.core.Ride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # „Tour teilen": Bild oder GPX
 *
 * Die Kachel „Teilen" der Detailansicht fragt seit dem Tour-Bild, **was**
 * geteilt wird: Story (9:16), Quadrat (1:1) oder die Spur als GPX. Eine fuenfte
 * Kachel „Als Bild teilen" haette die Kachelreihe als 2+2+1 umbrechen lassen
 * (`actionTileColumns` erlaubt bewusst keine Dreierreihen); ein Dialog mit drei
 * Segmenten haelt die Reihe ruhig und zeigt vor dem Teilen, wie das Bild
 * aussieht.
 *
 * Vorausgewaehlt ist „Story" — das Format, in dem ein Bild am ehesten
 * weitergereicht wird. GPX kostet damit einen Tipp mehr, steht aber
 * beschriftet daneben.
 *
 * ## Vorschau
 * Dieselbe Zeichnung wie beim Teilen ([renderShareCard]), nur in halber
 * Groesse (540 px breit, rund 2 MB statt 8 MB) und auf `Dispatchers.Default`.
 * Geteilt wird immer eine frisch gerenderte Fassung in voller Groesse; die
 * Vorschau-Bitmap haelt nur dieser Dialog. Solange sie entsteht, steht eine
 * getoente Flaeche im Seitenverhaeltnis da — kein Ladekreis (Repo-Muster
 * [RideThumbnail]).
 *
 * ## Datenschutz
 * Das Bild zeigt keine Karte, aber die Form der Strecke samt Start und Ziel —
 * wer die Gegend kennt, erkennt beides. Der Hinweis darunter sagt das ruhig,
 * statt es zu verschweigen.
 */
@Composable
internal fun ShareRideDialog(
    ride: Ride,
    load: Double?,
    onDismiss: () -> Unit,
    onShareGpx: () -> Unit,
    onShareImage: (ShareCardFormat) -> Unit,
) {
    var selected by rememberSaveable(ride.id) { mutableIntStateOf(SEGMENT_STORY) }
    val format = when (selected) {
        SEGMENT_SQUARE -> ShareCardFormat.SQUARE
        SEGMENT_STORY -> ShareCardFormat.STORY
        else -> null
    }

    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tour teilen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PillSegments(
                    options = listOf("Story", "Quadrat", "GPX"),
                    selectedIndex = selected,
                    onSelect = { selected = it },
                )
                if (format != null) {
                    ShareCardPreview(ride = ride, load = load, format = format)
                    Text(
                        text = "Das Bild zeigt die Form deiner Strecke ohne Karte – " +
                            "wer die Gegend kennt, erkennt trotzdem Start und Ziel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Die Spur als GPX-Datei – zum Nachfahren in Komoot, " +
                            "Strava oder auf dem Radcomputer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (format == null) onShareGpx() else onShareImage(format)
                },
            ) { Text("Teilen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

private const val SEGMENT_STORY = 0
private const val SEGMENT_SQUARE = 1

/** Hoehe der Vorschau — passt die Story auch auf kleinen Telefonen in den Dialog. */
private val PreviewHeight = 280.dp

/** Die halb grosse Vorschau des Bildes, mittig und in seinem Seitenverhaeltnis. */
@Composable
private fun ShareCardPreview(ride: Ride, load: Double?, format: ShareCardFormat) {
    // Der Inhalt haengt nicht vom Format ab: einmal je Fassung der Tour, und
    // wie das Zeichnen abseits des Hauptthreads (eine lange Aufzeichnung hat
    // zehntausende Punkte).
    val content by produceState<ShareCardContent?>(null, ride.id, ride.updatedAt, load) {
        value = withContext(Dispatchers.Default) { shareCardContent(ride, load) }
    }
    val preview by produceState<ImageBitmap?>(null, content, format) {
        // Beim Formatwechsel nicht kurz das alte Format zeigen.
        value = null
        val card = content ?: return@produceState
        value = withContext(Dispatchers.Default) {
            try {
                renderShareCard(card, format, PREVIEW_SCALE).asImageBitmap()
            } catch (_: OutOfMemoryError) {
                // Ohne Vorschau bleibt die getoente Flaeche stehen; das Teilen
                // selbst meldet einen echten Speichermangel.
                null
            }
        }
    }

    val shape = RoundedCornerShape(16.dp)
    val frame = Modifier
        .height(PreviewHeight)
        .aspectRatio(format.widthPx.toFloat() / format.heightPx)
        .clip(shape)

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val image = preview
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Vorschau des Tour-Bilds",
                contentScale = ContentScale.Fit,
                modifier = frame,
            )
        } else {
            Box(modifier = frame.background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
    }
}

/** Die Vorschau zeichnet in halber Groesse: 540 px Breite genuegen fuer 280 dp. */
private const val PREVIEW_SCALE = 0.5f
