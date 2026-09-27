package de.trailscape.app.ui.rides

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppLanguage
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
 * Vorschau-Bitmap haelt nur dieser Dialog. Solange sie entsteht, steht der
 * dunkle Grund des Bildes ([BG_TOP]) im Seitenverhaeltnis da — kein Ladekreis
 * (Repo-Muster [RideThumbnail]); eine getoente Flaeche in Dialogfarbe waere
 * unsichtbar.
 *
 * Die Vorschau ist hoechstens 280 dp und hoechstens 40 % der Bildschirmhoehe
 * hoch, und der Textbereich rollt: Quer oder mit grosser Systemschrift soll
 * „Teilen" nie aus dem Fenster rutschen.
 *
 * ## Datenschutz
 * Das Bild zeigt keine Karte, aber die Form der Strecke samt Start und Ziel —
 * wer die Gegend kennt, erkennt beides. Der Hinweis darunter sagt das ruhig,
 * statt es zu verschweigen. Hat die Tour keine Spur, sagt er stattdessen,
 * dass nur die Kennzahlen draufstehen.
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

    // Der Inhalt haengt nicht vom Format ab: einmal je Fassung der Tour, und
    // wie das Zeichnen abseits des Hauptthreads (eine lange Aufzeichnung hat
    // zehntausende Punkte). Hier oben, weil auch der Hinweis davon abhaengt.
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val content by produceState<ShareCardContent?>(null, ride.id, ride.updatedAt, load, language) {
        value = withContext(Dispatchers.Default) {
            shareCardContent(ride, load, language, resolve = { it.resolve(context) })
        }
    }
    val previewHeight = min(PreviewMaxHeight, LocalConfiguration.current.screenHeightDp.dp * 0.4f)

    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rides_share_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PillSegments(
                    options = listOf(
                        stringResource(R.string.rides_share_story_option),
                        stringResource(R.string.rides_share_square_option),
                        stringResource(R.string.rides_share_gpx_option),
                    ),
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    // Der Dialog ist selbst surfaceContainerHigh; eine leichte
                    // Toenung aus der Textfarbe hebt die Spur in beiden Themen ab.
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
                if (format != null) {
                    ShareCardPreview(content = content, format = format, height = previewHeight)
                    val card = content
                    if (card != null) {
                        Text(
                            text = stringResource(
                                if (card.hasTrack) R.string.rides_share_image_track_hint else R.string.rides_share_image_stats_hint,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.rides_share_gpx_hint),
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
            ) { Text(stringResource(R.string.common_action_share)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

private const val SEGMENT_STORY = 0
private const val SEGMENT_SQUARE = 1

/**
 * Hoechste Hoehe der Vorschau — passt die Story auch auf kleinen Telefonen in
 * den Dialog. Quer begrenzt zusaetzlich die Bildschirmhoehe (siehe oben).
 */
private val PreviewMaxHeight = 280.dp

/** Die halb grosse Vorschau des Bildes, mittig und in seinem Seitenverhaeltnis. */
@Composable
private fun ShareCardPreview(content: ShareCardContent?, format: ShareCardFormat, height: Dp) {
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
        .height(height)
        .aspectRatio(format.widthPx.toFloat() / format.heightPx)
        .clip(shape)

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val image = preview
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.rides_share_preview_cd),
                contentScale = ContentScale.Fit,
                modifier = frame,
            )
        } else {
            Box(modifier = frame.background(Color(BG_TOP)))
        }
    }
}

/** Die Vorschau zeichnet in halber Groesse: 540 px Breite genuegen fuer hoechstens 280 dp. */
private const val PREVIEW_SCALE = 0.5f
