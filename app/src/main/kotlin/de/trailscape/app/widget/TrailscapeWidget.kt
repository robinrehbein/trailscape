package de.trailscape.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.trailscape.app.MainActivity
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.localized
import de.trailscape.app.ui.theme.DarkOnPrimary
import de.trailscape.app.ui.theme.DarkOnSurface
import de.trailscape.app.ui.theme.DarkOnSurfaceVariant
import de.trailscape.app.ui.theme.DarkOutlineVariant
import de.trailscape.app.ui.theme.DarkPrimary
import de.trailscape.app.ui.theme.DarkSurface
import de.trailscape.app.ui.theme.LightOnPrimary
import de.trailscape.app.ui.theme.LightOnSurface
import de.trailscape.app.ui.theme.LightOnSurfaceVariant
import de.trailscape.app.ui.theme.LightOutlineVariant
import de.trailscape.app.ui.theme.LightPrimary
import de.trailscape.app.ui.theme.LightSurface
import de.trailscape.core.loadPlan
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Das Startbildschirm-Widget: Wochenserie, Wochenfortschritt und ein Tipp-Ziel,
 * das die App oeffnet.
 *
 * **Warum es existiert:** Die App soll den Fahrer auch zurueckholen, wenn sie
 * nicht offen ist. Eine Benachrichtigung draengt sich auf; ein Widget liegt nur
 * da und ist da, wenn man ohnehin aufs Handy schaut.
 *
 * **Alles lokal:** Gelesen werden nur die Tourzusammenfassungen und der Plan
 * auf dem Geraet — kein Netz, kein Konto, keine Berechtigung. Die Rechnung
 * steht in [buildWidgetState] (reine Funktion mit Tests); diese Datei zeichnet
 * nur.
 *
 * ## Groessenstufen
 * [SizeMode.Responsive] mit zwei Stufen: [Small] (ab ca. 2 x 1 Zellen) und
 * [Medium] (ab ca. 4 x 2). Responsive statt `Exact`, weil Glance dann je Stufe
 * ein eigenes RemoteViews-Layout vorbereitet und der Launcher beim Ziehen am
 * Rand ohne neuen Aufruf von [provideGlance] umschalten kann (ab Android 12;
 * davor ruft Glance die Komposition je Stufe auf).
 *
 * ## Farben
 * Aus `ui/theme/Color.kt`, hell und dunkel ueber `ColorProvider(day, night)` —
 * keine zweite Palette. Die gerundete Flaeche ist eine weisse Formressource,
 * die per Farbfilter eingefaerbt wird: So folgt der Radius dem Systemwert
 * (`values-v31`), und die Farbe bleibt an einer Stelle. Glance folgt dem
 * Hell/Dunkel-Modus des Systems, wie es die App selbst auch tut.
 *
 * ## Sprache
 * Die Texte kommen aus [localized]: Die App-Sprache kann von der
 * Systemsprache abweichen, ein Widget-Kontext kennt sie aber nicht.
 */
class TrailscapeWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(Small, Medium))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Daten vor der Komposition und abseits des Main-Threads: Glance ruft
        // diese Funktion auf einem Hintergrund-Dispatcher auf, das Lesen der
        // Dateien soll aber ausdruecklich auf IO laufen.
        val state = loadState(context)
        val texts = context.localized()
        provideContent { WidgetContent(texts, state) }
    }

    private suspend fun loadState(context: Context): WidgetState = withContext(Dispatchers.IO) {
        runCatching {
            // Das Widget kann einen Prozess wecken, dessen Application gerade
            // erst hochfaehrt; `init` ist idempotent (siehe AppServices).
            AppServices.init(context)
            val zone = ZoneId.systemDefault()
            buildWidgetState(
                // Zusammenfassungen genuegen: Die Rechnung braucht nur
                // Kennzahlen und Zeitstempel, nie GPS-Punkte.
                rides = AppServices.rideStorage.listSummaries().summaries,
                plan = loadPlan(AppServices.trainingPlanStore),
                now = LocalDateTime.now(zone),
                zoneDateOf = { ms -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() },
                zone = zone,
            )
        }.getOrDefault(WidgetState.Empty)
    }

    companion object {
        /** Mindestmass 2 x 1 Zellen (siehe `trailscape_widget_info.xml`). */
        val Small = DpSize(110.dp, 40.dp)

        /** Vorgabe 4 x 2 Zellen. */
        val Medium = DpSize(250.dp, 110.dp)
    }
}

// ---------------------------------------------------------------------------
// Farben (ui/theme/Color.kt, hell/dunkel)
// ---------------------------------------------------------------------------

private fun colors(day: Color, night: Color) = ColorProvider(day = day, night = night)

private val surface = colors(LightSurface, DarkSurface)
private val onSurface = colors(LightOnSurface, DarkOnSurface)
private val onSurfaceVariant = colors(LightOnSurfaceVariant, DarkOnSurfaceVariant)
private val track = colors(LightOutlineVariant, DarkOutlineVariant)
private val accent = colors(LightPrimary, DarkPrimary)
private val onAccent = colors(LightOnPrimary, DarkOnPrimary)

// ---------------------------------------------------------------------------
// Oberflaeche
// ---------------------------------------------------------------------------

@Composable
private fun WidgetContent(texts: Context, state: WidgetState) {
    val compact = LocalSize.current.let { it.height < TrailscapeWidget.Medium.height || it.width < TrailscapeWidget.Medium.width }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            // Das ganze Widget ist ein Tipp-Ziel: Wer irgendwo tippt, landet
            // in der App. Die Schaltflaeche darin hat dieselbe Aktion.
            .clickable(openApp(texts)),
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_shape_rounded),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(surface),
            modifier = GlanceModifier.fillMaxSize(),
        )
        when {
            !state.hasRides -> if (compact) EmptySmall(texts) else EmptyMedium(texts)
            compact -> SmallContent(texts, state)
            else -> MediumContent(texts, state)
        }
    }
}

/**
 * Oeffnet die App. Der Intent nennt die Activity ausdruecklich (nicht den
 * Launcher-Filter), damit er auch ohne passenden Intent-Filter-Treffer immer
 * dort landet; NEW_TASK, weil der Start aus dem Launcher-Prozess kommt.
 */
private fun openApp(context: Context) = actionStartActivity(
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

@Composable
private fun Flame(size: Int, tint: ColorProvider = accent) {
    Image(
        provider = ImageProvider(R.drawable.widget_ic_flame),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = GlanceModifier.size(size.dp),
    )
}

/** Wochen der Serie, kurz: „5 Wochen" bzw. „Neue Serie" ohne Serie. */
private fun streakShort(texts: Context, state: WidgetState): String =
    if (state.streakWeeks > 0) {
        texts.resources.getQuantityString(R.plurals.widget_streak_short, state.streakWeeks, state.streakWeeks)
    } else {
        texts.getString(R.string.widget_streak_none_short)
    }

private fun streakHeadline(texts: Context, state: WidgetState): String =
    if (state.streakWeeks > 0) {
        texts.resources.getQuantityString(R.plurals.widget_streak_headline, state.streakWeeks, state.streakWeeks)
    } else {
        texts.getString(R.string.widget_streak_none_headline)
    }

private fun streakStatus(texts: Context, state: WidgetState): String = when {
    state.thisWeekSecured -> texts.getString(R.string.widget_status_secured)
    state.streakAtRisk ->
        texts.resources.getQuantityString(R.plurals.widget_status_days_left, state.daysLeft, state.daysLeft)
    else -> texts.getString(R.string.widget_status_start)
}

/**
 * Gefahrene km fuer die Anzeige: abgerundet, damit „60 / 60 km" nur dasteht,
 * wenn das Ziel wirklich erreicht ist (59,6 km sind noch nicht 60).
 */
private fun shownKm(state: WidgetState): Int = floor(state.riddenKm + 1e-9).toInt()

@Composable
private fun SmallContent(texts: Context, state: WidgetState) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Flame(18)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = streakShort(texts, state),
                maxLines = 1,
                style = TextStyle(color = onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        ProgressBar(state)
        Spacer(GlanceModifier.height(3.dp))
        Text(
            text = compactProgress(texts, state),
            maxLines = 1,
            style = TextStyle(color = onSurfaceVariant, fontSize = 12.sp),
        )
    }
}

private fun compactProgress(texts: Context, state: WidgetState): String =
    state.targetKm?.let { texts.getString(R.string.widget_progress_compact, shownKm(state), it) }
        ?: texts.getString(R.string.widget_progress_compact_no_goal, shownKm(state))

@Composable
private fun ProgressBar(state: WidgetState) {
    if (state.targetKm == null) return
    LinearProgressIndicator(
        progress = state.fraction,
        modifier = GlanceModifier.fillMaxWidth(),
        color = accent,
        backgroundColor = track,
    )
}

@Composable
private fun MediumContent(texts: Context, state: WidgetState) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Flame(26)
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = streakHeadline(texts, state),
                    maxLines = 1,
                    style = TextStyle(color = onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = streakStatus(texts, state),
                    maxLines = 1,
                    style = TextStyle(
                        color = if (state.streakAtRisk) accent else onSurfaceVariant,
                        fontSize = 13.sp,
                    ),
                )
            }
        }
        Spacer(GlanceModifier.height(10.dp))
        ProgressBar(state)
        Spacer(GlanceModifier.height(4.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = fullProgress(texts, state),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = onSurfaceVariant, fontSize = 13.sp),
            )
            RideButton(texts)
        }
    }
}

private fun fullProgress(texts: Context, state: WidgetState): String =
    state.targetKm?.let { texts.getString(R.string.widget_progress_full, shownKm(state), it) }
        ?: texts.getString(R.string.widget_progress_full_no_goal, shownKm(state))

/** Pillenfoermige Schaltflaeche „Fahren"; oeffnet die App wie das ganze Widget. */
@Composable
private fun RideButton(texts: Context) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier
            .height(36.dp)
            .clickable(openApp(texts)),
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_shape_pill),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(accent),
            modifier = GlanceModifier.fillMaxSize(),
        )
        Text(
            text = texts.getString(R.string.widget_button_ride),
            maxLines = 1,
            modifier = GlanceModifier.padding(horizontal = 20.dp),
            style = TextStyle(color = onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
    }
}

// ---------------------------------------------------------------------------
// Leerzustand: noch keine Tour
// ---------------------------------------------------------------------------

@Composable
private fun EmptySmall(texts: Context) {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Flame(22)
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = texts.getString(R.string.widget_empty_title),
            maxLines = 2,
            style = TextStyle(color = onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun EmptyMedium(texts: Context) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Flame(26)
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = texts.getString(R.string.widget_empty_title),
                    maxLines = 1,
                    style = TextStyle(color = onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = texts.getString(R.string.widget_empty_hint),
                    maxLines = 1,
                    style = TextStyle(color = onSurfaceVariant, fontSize = 13.sp),
                )
            }
        }
        Spacer(GlanceModifier.height(12.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            RideButton(texts)
        }
    }
}
