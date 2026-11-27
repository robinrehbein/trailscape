package de.trailscape.app.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.components.MetricBlock
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.components.TrailscapePrimaryButton
import de.trailscape.app.ui.components.TrailscapeSecondaryButton
import de.trailscape.app.ui.progress.WeeklyGoalBar
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.WindowLayoutClass
import de.trailscape.app.ui.theme.tabular
import de.trailscape.app.ui.training.SessionTargetLines
import de.trailscape.core.AscentPreference
import de.trailscape.core.ReadinessBand
import de.trailscape.core.SessionPersonalTargets
import kotlin.math.roundToInt

/**
 * Die Bausteine der Startseite „Heute" nach `docs/design/prototyp-klartext.html`
 * (Screen `#s-heute`, Blatt `#m-warum`). Reine Darstellung — alle Saetze kommen
 * fertig aus `TodayWording.kt`, welcher Baustein erscheint, entscheidet
 * [TodayScreen].
 *
 * ## Was die Klartext-Fassung weggenommen hat
 * Die Vorgaengerseite hatte bis zu acht Elemente in der Empfehlungskarte, dazu
 * Zahlenzeile (Wochen-km, Form, Planwoche), Coach-Karte, Plan-Ausblick,
 * Plan-Tragfaehigkeit und „Letzte Tour". Jetzt:
 *
 *  1. [HeroCard] — Ring mit passendem Wort, ein Satz, ein Knopf, ein Link.
 *  2. [WeekCard] — die Woche als Streifen: erledigt, heute, geplant, frei.
 *  3. [GoalCard] / [GoalPromptCard] — eine Zeile zum Ziel, Tipp oeffnet
 *     Training.
 *
 * Die Gruende hinter der Empfehlung stehen im [WhySheet] statt als eigene
 * Karte; die Plan-Tragfaehigkeit liegt als Umzugskandidat in
 * `PlanFeasibilityCard.kt`; „Letzte Tour" findet man im Verlauf.
 *
 * One UI: Karten erben Rundung und Flaeche vom Theme. Die Ampelfarben kommen
 * aus [LocalSignalColors] — bewusst nicht aus `ui/training/`, damit diese Seite
 * nicht an der Farblogik eines anderen Tabs haengt.
 */

/**
 * Beschriftung des Heute-Knopfs auf der Karte ([offerChipText]) als fertiger
 * String in der Sprache der Oberflaeche.
 *
 * Eigene Composable-Huelle, damit die Karte (`ui/map/ExploreSheet.kt`) ihren
 * Aufruf `offerChipLabel(todayOffer)` unveraendert behalten kann — die reine
 * Formulierung bleibt in `TodayWording.kt` ohne Compose testbar.
 */
@Composable
fun offerChipLabel(offer: TodayOffer): String = offerChipText(offer).asString()

/** Zustand der kleinen Uhren-Zeile in der Hero-Karte. */
enum class HealthHint {
    /** Signale liegen vor (oder der Ring steht) — keine Zeile. */
    NONE,

    /** Gar kein Erholungssignal: Einladung, die Uhr zu verbinden. */
    CONNECT,

    /** Uhr liefert, aber noch zu wenig fuer einen Gesamtwert. */
    COLLECTING,
}

/**
 * Stufe 1: die Hero-Karte — die eine Antwort auf „Was fahre ich heute?".
 *
 * Ring (nur mit Gesamtwert) neben Schlagzeile und Satz, darunter der **eine**
 * volle Knopf der Seite und der Link ins „Warum?"-Blatt. Der Knopf steht jetzt
 * *in* der Karte wie in der Vorlage — er gehoert zur Empfehlung, nicht neben
 * sie. Am Zieltag entfaellt er ersatzlos; am Ruhetag steht an seiner Stelle
 * das ruhigere, neutrale „Locker rollen" ([offer] mit `restDay`) — dasselbe
 * Angebot, das Karte und Losfahren-Dialog machen.
 *
 * Ohne Gesamtwert bleibt der Ring weg statt leer zu stehen: Ein Bogen bei 0 %
 * waere eine Aussage ueber den Nutzer, die niemand getroffen hat. Die
 * Empfehlung kommt dann aus dem Plan, und [healthHint] sagt, warum der Ring
 * fehlt.
 *
 * [sessionTargets]: persoenliche Puls-/Wattbereiche der heutigen Planeinheit.
 */
@Composable
internal fun HeroCard(
    score: Int?,
    band: ReadinessBand?,
    headline: UiText,
    sentence: UiText,
    offer: TodayOffer?,
    onBuildRoute: () -> Unit,
    onWhy: () -> Unit,
    healthHint: HealthHint,
    onOpenHealth: () -> Unit,
    sessionTargets: SessionPersonalTargets? = null,
    goalDistanceKm: Double? = null,
    goalAscentM: Int? = null,
    goalDurationMin: Int? = null,
) {
    val theme = MaterialTheme.colorScheme
    val layoutClass = LocalWindowLayoutClass.current
    val expanded = layoutClass == WindowLayoutClass.Expanded
    val validGoalDistanceKm = goalDistanceKm?.takeIf { it.isFinite() && it > 0.0 }
    val routeMetrics = when {
        offer != null -> {
            val ascentM = estimatedAscentM(offer.target.distanceKm, offer.target.ascentPreference)
            rememberTodayRouteMetrics(
                distanceKm = offer.target.distanceKm,
                ascentM = ascentM,
                exactAscent = false,
                durationMin = routeDurationMinutes(offer.target),
                ascentPreference = offer.target.ascentPreference,
                restDay = offer.restDay,
            )
        }
        validGoalDistanceKm != null -> {
            val ascentM = goalAscentM ?: estimatedAscentM(validGoalDistanceKm, AscentPreference.MODERAT)
            val durationMin = goalDurationMin ?: ((validGoalDistanceKm / 22.0) * 60.0).roundToInt().coerceAtLeast(1)
            rememberTodayRouteMetrics(
                distanceKm = validGoalDistanceKm,
                ascentM = ascentM,
                exactAscent = goalAscentM != null,
                durationMin = durationMin,
                ascentPreference = AscentPreference.MODERAT,
                isEvent = true,
            )
        }
        else -> null
    }

    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (score != null && band != null) {
                    ReadinessRing(
                        score = score,
                        word = readinessWord(band).asString(),
                        color = readinessColor(band),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headline.asString(),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = sentence.asString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.onSurfaceVariant,
                    )
                    // Persoenliche Zielbereiche der heutigen Planeinheit
                    // (`planSessionTargets`); ohne Plan oder am Zieltag leer.
                    SessionTargetLines(targets = sessionTargets, modifier = Modifier.padding(top = 4.dp))
                }
            }

            when (healthHint) {
                HealthHint.NONE -> Unit
                HealthHint.CONNECT -> WatchLine(
                    text = stringResource(R.string.today_hero_connect_watch_hint),
                    modifier = Modifier.clickable(
                        onClickLabel = stringResource(R.string.today_hero_open_health_action),
                        role = Role.Button,
                        onClick = onOpenHealth,
                    ),
                )

                HealthHint.COLLECTING -> WatchLine(
                    text = stringResource(R.string.today_hero_collecting_hint),
                )
            }

            if (routeMetrics != null) {
                if (expanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MetricBlock(
                            value = routeMetrics.distanceValue,
                            unit = routeMetrics.distanceUnit,
                            label = stringResource(R.string.today_route_metric_distance_label),
                            modifier = Modifier.weight(1f),
                        )
                        MetricBlock(
                            value = routeMetrics.ascentValue,
                            unit = routeMetrics.ascentUnit,
                            label = stringResource(R.string.today_route_metric_ascent_label),
                            modifier = Modifier.weight(1f),
                        )
                        MetricBlock(
                            value = routeMetrics.durationValue,
                            unit = routeMetrics.durationUnit,
                            label = stringResource(R.string.today_route_metric_duration_label),
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = routeMetrics.distanceText,
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            color = theme.onSurface,
                        )
                        Text(
                            text = routeMetrics.ascentText,
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            color = theme.onSurface,
                        )
                        Text(
                            text = routeMetrics.durationText,
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            color = theme.onSurface,
                        )
                    }
                }
            }

            if (offer != null && !offer.restDay) {
                TrailscapePrimaryButton(
                    onClick = onBuildRoute,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Route, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = offerButtonLabel(offer).asString(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (offer != null) {
                // Am Ruhetag: dasselbe Angebot wie auf der Karte, aber ruhig
                // (Nebenknopf im Atlas-Stil) — die Seite raet zur Pause und
                // soll nicht mit dem einen orangefarbenen Knopf das Gegenteil sagen.
                TrailscapeSecondaryButton(
                    onClick = onBuildRoute,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Dasselbe Spa-Symbol wie der Ruhetag-Knopf auf der Karte.
                    Icon(Icons.Rounded.Spa, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = offerButtonLabel(offer).asString(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            TextButton(
                onClick = onWhy,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.today_hero_why_action))
            }
        }
    }
}

/** Die kleine Uhren-Zeile: Symbol plus ein Satz, gedaempft. */
@Composable
private fun WatchLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Watch,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ampelfarbe eines Readiness-Bands: gruen → gelb → orange → rot. */
@Composable
@ReadOnlyComposable
private fun readinessColor(band: ReadinessBand): Color {
    val signals = LocalSignalColors.current
    return when (band) {
        ReadinessBand.HART -> signals.good
        ReadinessBand.NORMAL -> signals.caution
        ReadinessBand.LOCKER -> signals.warning
        ReadinessBand.RUHE -> signals.danger
    }
}

/**
 * Der Tagesform-Ring — gefuellter Bogen als Anteil an 100, Zahl darin, das
 * Band-Wort ([readinessWord]) darunter.
 *
 * Die Semantik ist **gebuendelt**: Von aussen ist der Ring ein einziger Halt,
 * der „Tagesform 82 von 100, erholt" vorliest — sonst kaemen „82" und
 * „erholt" als zwei zusammenhanglose Fetzen und die Skala gar nicht.
 */
@Composable
private fun ReadinessRing(score: Int, word: String, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val description = stringResource(R.string.today_ring_cd, score, word)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
        },
    ) {
        Box(modifier = Modifier.size(RingSize), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = RingStroke.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val arcOffset = Offset(stroke / 2f, stroke / 2f)
                drawArc(
                    color = track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcOffset,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = color,
                    // Bei −90° beginnt der Bogen oben, wie jede Fortschrittsuhr.
                    startAngle = -90f,
                    sweepAngle = 360f * (score.coerceIn(0, 100) / 100f),
                    useCenter = false,
                    topLeft = arcOffset,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Text(text = score.toString(), style = MaterialTheme.typography.headlineSmall.tabular())
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = word,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

/** Aussenmass des Rings — die 74 px der Vorlage. */
private val RingSize = 74.dp

/** Staerke des Rings. */
private val RingStroke = 7.dp

/**
 * Balken und Satz unter dem Wochenstreifen: wie weit die Woche zum Kilometerziel
 * ist — beim Plan wie beim mitwachsenden Wochenziel. [caption] gibt es nur fuer
 * das mitwachsende Ziel (siehe `weeklyGoalCaption`); ein Plan erklaert sich selbst.
 */
internal data class WeekGoalLine(
    val fraction: Float,
    val reached: Boolean,
    val description: String,
    val caption: String? = null,
)

/**
 * Stufe 2: „Diese Woche" — Stand in einer Zeile, darunter der Streifen Mo–So.
 *
 * Ersetzt Zahlenzeile **und** Plan-Ausblick, die dieselben Wochenkilometer
 * zweimal zeigten. Der Streifen sagt auf einen Blick, was erledigt ist, was
 * heute und was noch ansteht — ohne Wochentyp, Planwoche oder
 * Schluessel-Einheit.
 *
 * Fuer TalkBack: Die Kopfzeile ist eine Ueberschrift (ein Satz statt zweier
 * Schnipsel), der Streifen eine Liste mit sieben Eintraegen — so kuendigt
 * TalkBack die Liste mit ihrer Laenge an, und jeder Tag wird als ganzer Satz
 * vorgelesen ([StripDay.description]) statt als Kuerzel und nackte Zahl.
 */
@Composable
internal fun WeekCard(
    summary: Pair<UiText, UiText?>,
    strip: List<StripDay>,
    footer: (@Composable () -> Unit)? = null,
    goal: WeekGoalLine? = null,
) {
    val first = summary.first.asString()
    val second = summary.second?.asString()
    val summarySpoken = second?.let { "$first, $it" } ?: first
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.clearAndSetSemantics {
                    heading()
                    contentDescription = summarySpoken
                },
            ) {
                Text(text = first, style = MaterialTheme.typography.titleMedium.tabular())
                second?.let {
                    Text(
                        text = " · $it",
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = strip.size) },
            ) {
                strip.forEachIndexed { index, day ->
                    StripDayCell(day = day, index = index, modifier = Modifier.weight(1f))
                }
            }
            if (goal != null) {
                Spacer(modifier = Modifier.height(14.dp))
                WeeklyGoalBar(
                    fraction = goal.fraction,
                    reached = goal.reached,
                    description = goal.description,
                    caption = goal.caption,
                )
            }
            if (footer != null) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                footer()
            }
        }
    }
}

/**
 * Ein Tag des Streifens: Kuerzel ueber dem Punkt. Vorgelesen wird der fertige
 * Satz aus [StripDay.description] („Donnerstag, heute: 45 km geplant").
 */
@Composable
private fun StripDayCell(day: StripDay, index: Int, modifier: Modifier = Modifier) {
    val theme = MaterialTheme.colorScheme
    val description = day.description.asString()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            collectionItemInfo = CollectionItemInfo(rowIndex = 0, rowSpan = 1, columnIndex = index, columnSpan = 1)
        },
    ) {
        Text(
            text = day.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (day.isToday) theme.primary else theme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(5.dp))
        StripDot(day)
    }
}

@Composable
private fun StripDot(day: StripDay) {
    val theme = MaterialTheme.colorScheme
    val text = day.km?.toString() ?: "–"
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold).tabular()
    when (day.state) {
        StripState.DONE -> Box(
            modifier = Modifier
                .size(DotSize)
                .clip(CircleShape)
                .background(theme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, style = labelStyle, color = theme.onPrimary)
        }

        StripState.TODAY, StripState.PLANNED -> {
            val dashed = day.state == StripState.PLANNED
            val ringColor = if (dashed) theme.outlineVariant else theme.primary
            Box(modifier = Modifier.size(DotSize), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = (if (dashed) 2.dp else 2.5.dp).toPx()
                    drawCircle(
                        color = ringColor,
                        radius = (size.minDimension - stroke) / 2f,
                        style = Stroke(
                            width = stroke,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null,
                        ),
                    )
                }
                Text(
                    text = text,
                    style = labelStyle,
                    color = if (dashed) theme.onSurfaceVariant else theme.primary,
                )
            }
        }

        StripState.REST -> Box(modifier = Modifier.size(DotSize), contentAlignment = Alignment.Center) {
            Text(text = "–", style = labelStyle, color = theme.outline)
        }
    }
}

/** Durchmesser eines Tagespunkts — die 30 px der Vorlage. */
private val DotSize = 30.dp

/**
 * Stufe 3: „Dein Ziel" — eine antippbare Zeile, die in den Trainings-Tab
 * fuehrt. Name und Distanz, eine gedaempfte Zeile (Datum · Restzeit ·
 * Planwoche), ein Fortschrittsbalken ueber die Planlaufzeit.
 */
@Composable
internal fun GoalCard(title: String, line: String, progress: Float, onOpenTraining: () -> Unit) {
    ChevronCard(onClick = onOpenTraining) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
    }
}

/** Ohne Plan: dieselbe Zeile als Einladung, ein Ziel festzulegen. */
@Composable
internal fun GoalPromptCard(onOpenTraining: () -> Unit) {
    ChevronCard(onClick = onOpenTraining) {
        Text(text = stringResource(R.string.today_goal_prompt_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.today_goal_prompt_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Karte als Zeile mit Chevron rechts — der Chevron sagt „fuehrt woandershin". */
@Composable
private fun ChevronCard(onClick: () -> Unit, content: @Composable () -> Unit) {
    val openLabel = stringResource(R.string.today_goal_open_training_action)
    AtlasCard(
        onClick = onClick,
        modifier = Modifier.semantics {
            role = Role.Button
            onClick(label = openLabel, action = null)
        },
    ) {
        Row(
            modifier = Modifier.padding(CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) { content() }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Das „Warum?"-Blatt: je Signal Name, Wort-Pille und ein Satz; darunter die
 * Einordnung aus Plan und Coach als getoente Notiz, dann „Verstanden".
 *
 * Es ersetzt die fruehere Coach-Karte der Startseite: Die Gruende sind da, wer
 * sie sucht, aber sie stehen nicht mehr zwischen der Empfehlung und dem Knopf.
 * „Verstanden" ist bewusst ein [TrailscapeSecondaryButton] — der eine volle Knopf dieser
 * Seite bleibt „Runde für heute bauen".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhySheet(
    title: UiText,
    signals: List<WhySignal>,
    note: List<UiText>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CardPadding + 8.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title.asString(),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Column {
                signals.forEachIndexed { index, signal ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SignalRow(signal)
                }
            }
            if (note.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (sentence in note) {
                            Text(text = sentence.asString(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            TrailscapeSecondaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.today_why_sheet_dismiss_action))
            }
        }
    }
}

/** Eine Signalzeile: Name links, Wort-Pille rechts, Satz darunter. */
@Composable
private fun SignalRow(signal: WhySignal) {
    val (container, content) = toneColors(signal.tone)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = signal.label.asString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TagPill(text = signal.word.asString(), containerColor = container, contentColor = content)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = signal.sentence.asString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Flaeche und Schrift der Wort-Pille je [SignalTone]. */
@Composable
@ReadOnlyComposable
private fun toneColors(tone: SignalTone): Pair<Color, Color> {
    val theme = MaterialTheme.colorScheme
    val signals = LocalSignalColors.current
    return when (tone) {
        SignalTone.GUT -> theme.primaryContainer to theme.onPrimaryContainer
        SignalTone.NEUTRAL -> theme.surfaceContainerHigh to theme.onSurfaceVariant
        SignalTone.ACHTUNG -> signals.caution.copy(alpha = 0.16f) to signals.caution
        SignalTone.WARNUNG -> signals.danger.copy(alpha = 0.14f) to signals.danger
    }
}
