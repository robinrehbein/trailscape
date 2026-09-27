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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import de.trailscape.app.R
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.more.SettingsSwitchRow
import de.trailscape.core.Ride
import de.trailscape.core.SHARE_END_RADIUS_M
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
 * Das Bild zeigt keine Karte, aber die Form der Strecke — und die verraet an
 * Start und Ziel meist die Haustuer. Deshalb steht unter der Vorschau der
 * Schalter „Start und Ziel ausblenden" ([hideEnds], ab Werk an, gemerkt vom
 * Wirt [RideShareDialog]): Die Linie beginnt und endet dann erst
 * [SHARE_END_RADIUS_M] Luftlinie von beiden entfernt, ohne Start- und
 * Zielmarke. Die Vorschau zeigt das sofort, weil der Inhalt neu entsteht.
 *
 * Der Hinweis darunter sagt ruhig, was trotzdem zu erkennen ist
 * ([ShareCardContent.trackNote]): die ganze Strecke samt Start und Ziel
 * (Schalter aus), die uebrige Strecke (Schalter an), nur die Kennzahlen, weil
 * die Tour zum Kuerzen zu kurz ist, oder nur die Kennzahlen, weil es keine
 * Spur gibt. Der Schalter fehlt, wo es nichts zu kuerzen gibt (weniger als
 * zwei Punkte oder eine Spur ohne Strecke, [ShareTrackNote.NONE]) und beim GPX, das immer die vollstaendige Spur enthaelt.
 */
@Composable
internal fun ShareRideDialog(
    ride: Ride,
    load: Double?,
    hideEnds: Boolean,
    onHideEndsChange: (Boolean) -> Unit,
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
    val content by produceState<ShareCardContent?>(null, ride.id, ride.updatedAt, load, hideEnds) {
        value = withContext(Dispatchers.Default) {
            shareCardContent(ride, load, endRadiusM = if (hideEnds) SHARE_END_RADIUS_M else null)
        }
    }
    val previewHeight = min(PreviewMaxHeight, LocalConfiguration.current.screenHeightDp.dp * 0.4f)

    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tour teilen") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PillSegments(
                    options = listOf("Story", "Quadrat", "GPX"),
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    // Der Dialog ist selbst surfaceContainerHigh; eine leichte
                    // Toenung aus der Textfarbe hebt die Spur in beiden Themen ab.
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
                if (format != null) {
                    ShareCardPreview(content = content, format = format, height = previewHeight)
                    // Vorpruefung auf die Punkte, damit die Zeile nicht erst
                    // nach dem Laden auftaucht; NONE haengt nicht von hideEnds
                    // ab, der Schalter verschwindet also nie beim Umlegen.
                    if (ride.points.size >= 2 && content?.trackNote != ShareTrackNote.NONE) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.share_hide_ends_title),
                            subtitle = stringResource(R.string.share_hide_ends_subtitle, SHARE_END_RADIUS_M.toInt()),
                            checked = hideEnds,
                            onCheckedChange = onHideEndsChange,
                        )
                    }
                    val card = content
                    if (card != null) {
                        Text(
                            text = when (card.trackNote) {
                                ShareTrackNote.FULL -> stringResource(R.string.share_hint_full)
                                ShareTrackNote.ENDS_HIDDEN -> stringResource(R.string.share_hint_ends_hidden)
                                ShareTrackNote.TOO_SHORT -> stringResource(R.string.share_hint_too_short)
                                ShareTrackNote.NONE -> stringResource(R.string.share_hint_no_track)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                contentDescription = "Vorschau des Tour-Bilds",
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
