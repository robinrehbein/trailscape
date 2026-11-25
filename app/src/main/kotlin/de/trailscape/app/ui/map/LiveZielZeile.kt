package de.trailscape.app.ui.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.record.RecordingRepository
import de.trailscape.core.ZielZeile
import de.trailscape.core.zielZeile
import de.trailscape.core.zielZeileZahl

/**
 * Die Zielzeile der laufenden Aufzeichnung aus dem Snapshot zum Start
 * ([RecordingRepository.zielSnapshot]) und der bisherigen Distanz — Regeln in
 * `core/…/LiveZiel.kt`. `null`, solange nichts zu zeigen ist.
 */
@Composable
internal fun rememberLiveZielZeile(distanceKm: Double): ZielZeile? {
    val snapshot by RecordingRepository.zielSnapshot.collectAsStateWithLifecycle()
    return remember(snapshot, distanceKm) { zielZeile(snapshot, distanceKm) }
}

/** Der Satz zur [zeile] in der App-Sprache. */
@Composable
internal fun liveZielText(zeile: ZielZeile): String {
    val zahl = zielZeileZahl(zeile, LocalAppFormats.current.language).orEmpty()
    return when (zeile) {
        is ZielZeile.WochenzielRest -> stringResource(R.string.map_live_goal_week_rest, zahl)
        ZielZeile.WochenzielGeschafft -> stringResource(R.string.map_live_goal_week_done)
        is ZielZeile.BestweiteRest -> stringResource(R.string.map_live_goal_best_rest, zahl)
        ZielZeile.NeueBestweite -> stringResource(R.string.map_live_goal_best_done)
    }
}

/**
 * Die Zielzeile klein und ruhig in der Akzentfarbe — eine Zeile, die beim
 * Ueberlegen umzukehren den Ausschlag gibt, kein Hinweis, der um
 * Aufmerksamkeit kaempft. Hoechstens zwei Zeilen, dann gekuerzt (der Satz ist
 * kurz genug, dass das auf einem Telefon nicht vorkommt).
 */
@Composable
internal fun LiveZielZeile(
    zeile: ZielZeile,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    Text(
        text = liveZielText(zeile),
        modifier = modifier,
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
