package de.trailscape.app.ui.training

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.OneUiTextField
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.Goal
import de.trailscape.core.GoalFinishPrediction
import de.trailscape.core.PROGNOSIS_LOOKBACK_DAYS
import de.trailscape.core.RideInfo
import de.trailscape.core.TrainingPlan
import de.trailscape.core.assessFitness
import de.trailscape.core.formatGoalDuration
import de.trailscape.core.generatePlan
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDistanceKm
import de.trailscape.core.parseGoalDuration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * # „Dein Ziel" — Ziel, Zielzeit und Prognose
 *
 * Die oberste Karte des Trainings-Tabs im Redesign „Klartext"
 * (`docs/design/prototyp-klartext.html`, Screen `#s-training`). Sie
 * beantwortet die Frage, fuer die man den Tab oeffnet: **„Schaffe ich mein
 * Ziel?"** — mit zwei Zahlen nebeneinander („Stand heute" gegen „Dein Ziel"),
 * einer kleinen Skala und einem Satz, wo der Plan einen bis zum Renntag
 * hinbringt.
 *
 * Frueher stand hier ein Formular ganz unten im Plan-Kapitel. Das Formular
 * gibt es weiter — samt Validierung, „Plan erstellen" und „Plan löschen" —,
 * aber als Blatt hinter „Ändern" ([GoalEditorSheet]): Man liest das Ziel
 * taeglich und aendert es selten. Neu ist die **Zielzeit**
 * ([Goal.targetDurationMin]) und die **Prognose** aus `:core`
 * ([de.trailscape.core.predictGoalFinish]); gerechnet wird hier nichts.
 *
 * Ohne Ziel steht an derselben Stelle [GoalSetupCard].
 */

/** Formatiert Minuten als „2:10 h". */
internal fun formatHoursMinutes(minutes: Int): String = "${formatGoalDuration(minutes)} h"

/** Zieldistanz ohne „,0" bei ganzen Kilometern („60", aber „42,2" / „42.2"). */
internal fun formatGoalKm(km: Double, language: AppLanguage): String =
    if (km == Math.rint(km)) km.toLong().toString() else formatDistanceKm(km, language)

/**
 * Zieldatum mit kurzem Wochentag: „Sa, 19. Dezember" / „Sat 19 December".
 *
 * Eigenes Muster statt `DateFormats`: Die Zielzeile ist die einzige Stelle mit
 * abgekuerztem Wochentag, und Java schreibt ihn im Deutschen mit Punkt
 * („Sa."), der hier stoeren wuerde.
 */
internal fun formatGoalDate(date: LocalDate, language: AppLanguage): String {
    val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, language.locale).removeSuffix(".")
    return when (language) {
        AppLanguage.DE -> "$day, ${date.format(DateTimeFormatter.ofPattern("d. MMMM", language.locale))}"
        AppLanguage.EN -> "$day ${date.format(DateTimeFormatter.ofPattern("d MMMM", language.locale))}"
    }
}

/**
 * „60 km · 700 Hm · Sa, 20. Dezember · noch 12 Wochen" — die Kopfzeile des
 * Ziels, als Teile, die die Anzeige mit „ · " verbindet. Unter zwei Wochen
 * zaehlt sie Tage; nach dem Renntag sagt sie das.
 */
internal fun goalSummaryParts(
    goal: Goal,
    language: AppLanguage,
    today: LocalDate = LocalDate.now(),
): List<UiText> {
    val date = Instant.ofEpochMilli(goal.date).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, date)
    val remaining = when {
        days < 0 -> UiText.Res(R.string.training_goal_date_passed)
        days == 0L -> UiText.Res(R.string.training_goal_date_today)
        days == 1L -> UiText.Res(R.string.training_goal_date_tomorrow)
        days < 14 -> UiText.Plural(R.plurals.training_goal_days_left_count, days.toInt())
        else -> UiText.Plural(R.plurals.training_goal_weeks_left_count, (days / 7.0).roundToInt())
    }
    val parts = mutableListOf<UiText>(
        UiText.Res(R.string.common_value_km, listOf(formatGoalKm(goal.distanceKm, language))),
    )
    goal.ascentM?.takeIf { it > 0 }?.let { parts.add(UiText.Res(R.string.training_goal_ascent, listOf(it.roundToInt()))) }
    parts.add(UiText.Plain(formatGoalDate(date, language)))
    parts.add(remaining)
    return parts
}

/**
 * Der Satz unter den Zeiten: wohin der Plan einen bringt, plus ein schlichter
 * Hinweis — oder, ohne genug Touren, was fuer eine Prognose fehlt.
 *
 * [sentence] ist ein ganzer Satz; [bold] ist der Teil darin, den die Anzeige
 * fett setzt (die Zeit am Renntag). So bleibt der Satz ein Schluessel und wird
 * nicht aus uebersetzten Bruchstuecken zusammengesetzt.
 */
internal data class PrognosisNote(
    val sentence: UiText,
    val bold: UiText?,
    val hint: UiText?,
)

internal fun prognosisNote(goal: Goal, prediction: GoalFinishPrediction): PrognosisNote {
    val p = prediction.prognosis
        ?: return PrognosisNote(
            // `missing` kommt aus `:core` bereits in der App-Sprache.
            prediction.missing?.let { UiText.Plain(it) } ?: UiText.Res(R.string.training_goal_no_prognosis),
            null,
            null,
        )
    val target = goal.targetDurationMin
    val reference = p.atEventMin ?: p.currentMin
    val hint = when {
        p.beyondLongestRide -> UiText.Res(R.string.training_goal_hint_long_rides)
        target != null && reference > target ->
            UiText.Res(R.string.training_goal_hint_short, listOf(reference - target))
        target != null -> UiText.Res(R.string.training_goal_hint_on_track)
        else -> UiText.Res(R.string.training_goal_hint_no_target)
    }
    val atEvent = p.atEventMin
    return if (atEvent != null) {
        val bold = UiText.Res(R.string.training_goal_prognosis_event_value, listOf(formatHoursMinutes(atEvent)))
        PrognosisNote(
            sentence = UiText.Res(R.string.training_goal_prognosis_event, listOf(bold)),
            bold = bold,
            hint = hint,
        )
    } else {
        PrognosisNote(
            sentence = UiText.Res(R.string.training_goal_prognosis_pending),
            bold = null,
            hint = hint,
        )
    }
}

/**
 * Die Zielkarte mit Prognose.
 *
 * @param prediction Ergebnis von [de.trailscape.core.predictGoalFinish] fuer
 *   [goal].
 * @param onEdit oeffnet [GoalEditorSheet] (auch aus der Kachel
 *   „Zielzeit eintragen").
 * @param onExplain oeffnet [PrognosisSheet].
 */
@Composable
fun GoalOverviewCard(
    goal: Goal,
    prediction: GoalFinishPrediction,
    onEdit: () -> Unit,
    onExplain: () -> Unit,
) {
    val theme = MaterialTheme.colorScheme
    val language = LocalAppLanguage.current
    val p = prediction.prognosis
    val note = prognosisNote(goal, prediction)
    val sentence = note.sentence.asString()
    val bold = note.bold?.asString()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            // Name und „Ändern" auf einer Linie; die Abschnittsueberschrift
            // „Dein Ziel" steht wie auf „Heute" ueber der Karte.
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = goal.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onEdit,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text(stringResource(R.string.training_goal_edit_action)) }
                }
                Text(
                    text = goalSummaryParts(goal, language).map { it.asString() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onSurfaceVariant,
                )
            }

            // Die beiden Zeiten nebeneinander; IntrinsicSize.Min haelt die
            // Kacheln gleich hoch, auch wenn eine davon zweizeilig wird.
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TimeTile(
                    label = stringResource(R.string.training_goal_current_label),
                    value = p?.let { formatHoursMinutes(it.currentMin) } ?: "–",
                    accent = false,
                )
                val target = goal.targetDurationMin
                if (target != null) {
                    TimeTile(
                        label = stringResource(R.string.training_goal_target_label),
                        value = formatHoursMinutes(target),
                        accent = true,
                    )
                } else {
                    TimeTile(
                        label = stringResource(R.string.training_goal_target_label),
                        value = stringResource(R.string.training_goal_target_add_action),
                        accent = true,
                        small = true,
                        onClick = onEdit,
                    )
                }
            }

            if (p != null) {
                PrognosisTrack(
                    currentMin = p.currentMin,
                    targetMin = goal.targetDurationMin,
                    atEventMin = p.atEventMin,
                    uncertaintyMin = p.uncertaintyMin,
                )
            }

            Surface(
                color = theme.primaryContainer,
                contentColor = theme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = buildAnnotatedString {
                            append(sentence)
                            // Die fette Zeit steht als Argument im Satz; hier
                            // wird sie nur wiedergefunden und hervorgehoben.
                            val start = bold?.let { sentence.indexOf(it) } ?: -1
                            if (bold != null && start >= 0) {
                                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + bold.length)
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    note.hint?.let {
                        Text(
                            text = it.asString(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            TextButton(
                onClick = onExplain,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.training_goal_explain_action)) }
        }
    }
}

/**
 * Eine der beiden Zeit-Kacheln. [accent] toent sie in der Akzentflaeche (das
 * eigene Ziel); mit [onClick] wird sie zum Knopf („Zielzeit eintragen").
 */
@Composable
private fun RowScope.TimeTile(
    label: String,
    value: String,
    accent: Boolean,
    small: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val theme = MaterialTheme.colorScheme
    val container = if (accent) theme.primaryContainer else theme.surfaceVariant
    val content = if (accent) theme.onPrimaryContainer else theme.onSurface
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(container, MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content.copy(alpha = 0.75f),
        )
        Text(
            text = value,
            style = if (small) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
            color = if (small) theme.primary.takeIf { !accent } ?: content else content,
        )
    }
}

/**
 * Die Markierungen der Prognose-Skala, in der Reihenfolge der Legende.
 * [labelRes] steht in der Legendenzeile unter der Skala.
 */
internal enum class PrognosisMarker(@StringRes val labelRes: Int) {
    TODAY(R.string.training_goal_marker_today),
    EVENT(R.string.training_goal_marker_event),
    TARGET(R.string.training_goal_marker_target),
}

/**
 * Welche Markierungen die Skala zeigt — und damit, was die Legende nennt.
 * „heute" steht immer; Renntag und Ziel nur, wenn es sie gibt. Eine Legende,
 * die einen fehlenden Punkt erklaert, liesse suchen.
 */
internal fun prognosisMarkers(targetMin: Int?, atEventMin: Int?): List<PrognosisMarker> =
    buildList {
        add(PrognosisMarker.TODAY)
        if (atEventMin != null) add(PrognosisMarker.EVENT)
        if (targetMin != null) add(PrognosisMarker.TARGET)
    }

/**
 * Die kleine Skala unter den Zeiten: links langsamer, rechts schneller. Der
 * graue Ring ist „heute", der Strich die Zielzeit, der gefuellte Punkt in
 * `tertiary` die Prognose mit Plan am Renntag.
 *
 * Darunter eine kurze Legende („● heute · ● am Renntag · | Ziel") in genau
 * den Farben und Formen der Skala: Ohne sie waren die zwei Punkte nicht
 * zuzuordnen — man musste raten, welcher heute und welcher der Renntag ist.
 * Die Prognose traegt `tertiary` statt `primary`, damit sie sich vom
 * Zielstrich (`primary`) unterscheidet: Punkt und Strich in derselben Farbe
 * lasen sich als ein Ding.
 *
 * Bewusst **Zeit** auf der Achse, nicht das Datum: Der Prototyp beschriftet
 * die Enden mit „heute" und „Renntag", die Punkte stehen aber nach Zeit —
 * „schneller" rechts erzaehlt dasselbe (der Weg geht nach rechts) und stimmt
 * dabei auch, wenn die Prognose ueber dem Ziel liegt.
 */
@Composable
private fun PrognosisTrack(
    currentMin: Int,
    targetMin: Int?,
    atEventMin: Int?,
    uncertaintyMin: Int,
) {
    val theme = MaterialTheme.colorScheme
    val values = listOfNotNull(currentMin, targetMin, atEventMin)
    val slow = values.max() + max(uncertaintyMin, 3)
    val fast = values.min() - max(uncertaintyMin, 3)
    fun pos(t: Int): Float = ((slow - t).toFloat() / max(1, slow - fast)).coerceIn(0.03f, 0.97f)

    val now = formatHoursMinutes(currentMin)
    val description = when {
        targetMin != null && atEventMin != null -> stringResource(
            R.string.training_goal_track_target_event_cd,
            now,
            formatHoursMinutes(targetMin),
            formatHoursMinutes(atEventMin),
        )
        targetMin != null -> stringResource(R.string.training_goal_track_target_cd, now, formatHoursMinutes(targetMin))
        atEventMin != null -> stringResource(R.string.training_goal_track_event_cd, now, formatHoursMinutes(atEventMin))
        else -> stringResource(R.string.training_goal_track_cd, now)
    }
    val startColor = theme.primaryContainer
    val endColor = theme.surfaceVariant
    val nowColor = theme.onSurfaceVariant
    val eventColor = theme.tertiary
    val targetColor = theme.primary
    val ring = theme.surface

    Column(modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = 4.dp),
        ) {
            val barH = 10.dp.toPx()
            val top = (size.height - barH) / 2
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(startColor, endColor)),
                topLeft = Offset(0f, top),
                size = Size(size.width, barH),
                cornerRadius = CornerRadius(barH / 2),
            )
            val cy = size.height / 2
            val r = 7.dp.toPx()
            fun dot(t: Int, color: Color, hollow: Boolean = false) {
                val x = size.width * pos(t)
                drawCircle(ring, radius = r, center = Offset(x, cy))
                if (hollow) {
                    val stroke = 2.dp.toPx()
                    drawCircle(
                        color,
                        radius = r - 3.dp.toPx() - stroke / 2 + 1.dp.toPx(),
                        center = Offset(x, cy),
                        style = Stroke(width = stroke),
                    )
                } else {
                    drawCircle(color, radius = r - 3.dp.toPx(), center = Offset(x, cy))
                }
            }
            // „heute" als Ring, der Renntag gefuellt: Die beiden Punkte
            // unterscheiden sich so in der Form, nicht nur im Farbton — der
            // allein reicht bei grau gegen petrol nicht.
            dot(currentMin, nowColor, hollow = true)
            targetMin?.let {
                val x = size.width * pos(it)
                drawRoundRect(
                    color = targetColor,
                    topLeft = Offset(x - 1.5.dp.toPx(), 0f),
                    size = Size(3.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                )
            }
            atEventMin?.let { dot(it, eventColor) }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.training_goal_track_slower),
                style = MaterialTheme.typography.labelSmall,
                color = theme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.training_goal_track_faster), style = MaterialTheme.typography.labelSmall, color = theme.onSurfaceVariant)
        }
        // Legende: dieselben Formen wie auf der Skala, klein und mittig.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            prognosisMarkers(targetMin, atEventMin).forEachIndexed { index, marker ->
                if (index > 0) {
                    Text(
                        " · ",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.onSurfaceVariant,
                    )
                }
                when (marker) {
                    PrognosisMarker.TODAY -> LegendDot(nowColor, hollow = true)
                    PrognosisMarker.EVENT -> LegendDot(eventColor)
                    PrognosisMarker.TARGET -> Box(
                        modifier = Modifier
                            .size(width = 3.dp, height = 12.dp)
                            .background(targetColor, RoundedCornerShape(1.5.dp)),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(marker.labelRes), style = MaterialTheme.typography.labelSmall, color = theme.onSurfaceVariant)
            }
        }
    }
}

/** Ein Legendenpunkt in der Groesse des Punktkerns auf der Skala, gefuellt oder als Ring. */
@Composable
private fun LegendDot(color: Color, hollow: Boolean = false) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .then(
                if (hollow) {
                    Modifier.border(2.dp, color, CircleShape)
                } else {
                    Modifier.background(color, CircleShape)
                },
            ),
    )
}

/**
 * Ohne Ziel: eine schlichte Karte mit dem Weg dorthin.
 *
 * @param primary ob der Knopf der gefuellte Hauptknopf des Screens ist. Ohne
 *   Touren gehoert diese Rolle dem Leerzustand („Tour aufzeichnen") — dann
 *   bleibt dieser Knopf neutral, damit es nur einen gefuellten gibt.
 */
@Composable
fun GoalSetupCard(onSetUp: () -> Unit, primary: Boolean = true) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.training_goal_setup_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.training_goal_setup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (primary) {
                Button(onClick = onSetUp) { Text(stringResource(R.string.training_goal_setup_action)) }
            } else {
                NeutralButton(onClick = onSetUp) { Text(stringResource(R.string.training_goal_setup_action)) }
            }
        }
    }
}

/**
 * Blatt „Wie die Prognose entsteht": die drei Zutaten (eigene Touren,
 * Fitness, Strecke) und die Unsicherheit — Vorlage ist `#m-prognose` im
 * Prototyp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrognosisSheet(
    goal: Goal,
    prediction: GoalFinishPrediction,
    currentCtl: Double?,
    onDismiss: () -> Unit,
) {
    val p = prediction.prognosis
    val language = LocalAppLanguage.current
    val lookbackWeeks = PROGNOSIS_LOOKBACK_DAYS / 7
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetColumn {
            Text(stringResource(R.string.training_prognosis_title), style = MaterialTheme.typography.titleLarge)
            ExplainRow(
                label = stringResource(R.string.training_prognosis_rides_label),
                pill = pluralStringResource(R.plurals.training_prognosis_rides_weeks_count, lookbackWeeks, lookbackWeeks),
                text = p?.let {
                    pluralStringResource(R.plurals.training_prognosis_rides_body_used_count, it.ridesUsed, it.ridesUsed)
                } ?: stringResource(R.string.training_prognosis_rides_body),
            )
            ExplainRow(
                label = stringResource(R.string.training_prognosis_fitness_label),
                pill = currentCtl?.let { "${it.roundToInt()}" },
                text = stringResource(R.string.training_prognosis_fitness_body),
            )
            val km = formatGoalKm(goal.distanceKm, language)
            ExplainRow(
                label = stringResource(R.string.training_prognosis_route_label),
                pill = goal.ascentM?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.training_prognosis_route_ascent_pill, km, it.roundToInt()) }
                    ?: stringResource(R.string.common_value_km, km),
                text = stringResource(R.string.training_prognosis_route_body),
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (p != null) {
                        pluralStringResource(R.plurals.training_prognosis_uncertainty_count, p.uncertaintyMin, p.uncertaintyMin)
                    } else {
                        prediction.missing ?: stringResource(R.string.training_goal_no_prognosis)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
            NeutralButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.training_prognosis_done_action))
            }
        }
    }
}

/** Gemeinsamer Inhalt-Rahmen der Blaetter dieses Tabs. */
@Composable
internal fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CardPadding)
            .padding(bottom = CardPadding)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) { content() }
}

/**
 * Eine Erklaerzeile der Blaetter: Begriff, optional eine Pille mit dem Wert,
 * darunter ein Satz; [jargon] steht klein und blass dahinter (z. B. „CTL").
 */
@Composable
internal fun ExplainRow(
    label: String,
    pill: String?,
    text: String,
    jargon: String? = null,
    pillColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val theme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            pill?.let { MetricChip(it, pillColor) }
        }
        Text(
            text = buildAnnotatedString {
                append(text)
                jargon?.let {
                    withStyle(SpanStyle(color = theme.onSurfaceVariant.copy(alpha = 0.6f))) { append(" ($it)") }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = theme.onSurfaceVariant,
        )
    }
}

/**
 * Das Zielformular als Blatt: Name, Distanz, Hoehenmeter (optional),
 * Zieldatum und — neu — Zielzeit (optional, „h:mm").
 *
 * Port von `_buildGoalCard` (`lib/screens/training_screen.dart`), frueher die
 * Karte „Dein Ziel" am Ende des Plan-Kapitels. Die Felder werden beim Oeffnen
 * aus dem vorhandenen Plan vorbefuellt.
 *
 * ## Zwei Wege zum Speichern
 *  * **„Plan erstellen"** — ohne Plan oder wenn sich Distanz, Hoehenmeter oder
 *    Datum geaendert haben: Dann passt der alte Plan nicht mehr und wird mit
 *    `generatePlan` neu gerechnet.
 *  * **„Speichern"** — wenn nur Name oder Zielzeit anders sind: Der Plan
 *    bleibt, nur das Ziel darin wird aktualisiert. Eine Zielzeit einzutragen
 *    soll nicht zwoelf Wochen Planung verwerfen.
 *
 * „Plan löschen" bleibt die zerstoererische Aktion mit Rueckfrage.
 *
 * @param onMessage meldet das Ergebnis („Plan mit 12 Wochen erstellt.") an die
 *   Snackbar des Screens — das Blatt schliesst sich danach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalEditorSheet(
    plan: TrainingPlan?,
    rides: List<RideInfo>,
    onSetPlan: (TrainingPlan?) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    currentCtl: Double? = null,
) {
    val coreTexts = LocalCoreTexts.current
    val formats = LocalAppFormats.current
    // Fuer Meldungen und Fehler ausserhalb der Komposition (Knopf-Handler);
    // der Activity-Kontext traegt die App-Sprache.
    val resources = LocalContext.current.resources
    val theme = MaterialTheme.colorScheme
    val existing = plan?.goal

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var distanceText by rememberSaveable {
        mutableStateOf(existing?.let { formatGoalNumber(it.distanceKm) } ?: "")
    }
    var ascentText by rememberSaveable {
        mutableStateOf(existing?.ascentM?.let { formatGoalNumber(it) } ?: "")
    }
    var goalDate by rememberSaveable {
        mutableStateOf(
            existing?.let { Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).toLocalDate() },
        )
    }
    var targetText by rememberSaveable {
        mutableStateOf(existing?.targetDurationMin?.let { formatGoalDuration(it) } ?: "")
    }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    /** Liest das Formular; `null` bei Fehler (dann steht er in [error]). */
    fun readGoal(): Goal? {
        val trimmedName = name.trim()
        val distance = distanceText.trim().replace(',', '.').toDoubleOrNull()
        val ascentRaw = ascentText.trim().replace(',', '.')
        val ascent = if (ascentRaw.isEmpty()) null else ascentRaw.toDoubleOrNull()
        val target = if (targetText.isBlank()) null else parseGoalDuration(targetText)
        when {
            trimmedName.isEmpty() -> error = resources.getString(R.string.training_goal_editor_name_error)
            distance == null || distance <= 0 ->
                error = resources.getString(R.string.training_goal_editor_distance_error)
            goalDate == null -> error = resources.getString(R.string.training_goal_editor_date_error)
            targetText.isNotBlank() && target == null ->
                error = resources.getString(R.string.training_goal_editor_target_error)
            else -> {
                val dateMs = goalDate!!.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                return Goal(
                    name = trimmedName,
                    distanceKm = distance!!,
                    ascentM = ascent,
                    date = dateMs,
                    targetDurationMin = target,
                )
            }
        }
        return null
    }

    // Muss der Plan neu gerechnet werden? Nur, wenn sich etwas geaendert hat,
    // woraus er entsteht — Name und Zielzeit gehoeren nicht dazu.
    val sameDate = existing != null && goalDate ==
        Instant.ofEpochMilli(existing.date).atZone(ZoneId.systemDefault()).toLocalDate()
    val sameShape = existing != null &&
        distanceText.trim().replace(',', '.').toDoubleOrNull() == existing.distanceKm &&
        ascentText.trim().replace(',', '.').let { if (it.isEmpty()) null else it.toDoubleOrNull() } == existing.ascentM &&
        sameDate
    val onlyMeta = plan != null && sameShape

    fun save() {
        val goal = readGoal() ?: return
        if (onlyMeta) {
            onSetPlan(plan!!.copy(goal = goal))
            onMessage(resources.getString(R.string.training_goal_editor_saved_status))
            onDismiss()
            return
        }
        try {
            val newPlan = generatePlan(goal, assessFitness(rides), currentCtl = currentCtl, texts = coreTexts)
            onSetPlan(newPlan)
            onMessage(
                resources.getQuantityString(
                    R.plurals.training_goal_editor_plan_created_count,
                    newPlan.weeks.size,
                    newPlan.weeks.size,
                ),
            )
            onDismiss()
        } catch (e: IllegalArgumentException) {
            error = e.message ?: resources.getString(R.string.training_goal_editor_invalid_error)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetColumn {
            Text(
                stringResource(
                    if (plan == null) R.string.training_goal_editor_title_new else R.string.training_goal_editor_title_edit,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            OneUiTextField(
                label = stringResource(R.string.training_goal_editor_name_label),
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.training_goal_editor_name_placeholder),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                OneUiTextField(
                    label = stringResource(R.string.training_goal_editor_distance_label),
                    value = distanceText,
                    onValueChange = { distanceText = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                OneUiTextField(
                    label = stringResource(R.string.training_goal_editor_ascent_label),
                    value = ascentText,
                    onValueChange = { ascentText = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) {
                    val dateText = goalDate?.let { formats.dateFull(it) }
                    val dateCd = dateText
                        ?.let { stringResource(R.string.training_goal_editor_date_cd, it) }
                        ?: stringResource(R.string.training_goal_editor_date_choose_cd)
                    OneUiTextField(
                        label = stringResource(R.string.training_goal_editor_date_label),
                        value = dateText ?: "",
                        onValueChange = {},
                        readOnly = true,
                        // Aufforderung als `placeholder`, nicht als Wert — sonst
                        // saehe sie aus wie ein gesetztes Datum.
                        placeholder = stringResource(R.string.training_goal_editor_date_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Ueberlagerung mit gebuendelter Semantik: ein einziger,
                    // benannter Halt statt eines stummen Felds unter einer
                    // namenlosen Flaeche.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showDatePicker = true }
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = dateCd
                            },
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                OneUiTextField(
                    label = stringResource(R.string.training_goal_editor_target_label),
                    value = targetText,
                    onValueChange = { targetText = it },
                    placeholder = stringResource(R.string.training_goal_editor_target_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.weight(1f),
                )
            }

            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = theme.error) }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = ::save) {
                    Text(
                        stringResource(
                            if (onlyMeta) R.string.common_action_save else R.string.training_goal_editor_create_action,
                        ),
                    )
                }
                if (plan != null) {
                    // Loeschen traegt — wie jeder Loeschweg der App — die
                    // Fehlerfarbe des Themes.
                    NeutralButton(onClick = { showDeleteConfirm = true }, destructive = true) {
                        Text(stringResource(R.string.training_goal_editor_delete_action))
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val today = LocalDate.now()
        val minMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val maxMillis = today.plusDays(400).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val initial = (goalDate ?: today.plusDays(60).with(DayOfWeek.SATURDAY))
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis in minMillis..maxMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        goalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.training_goal_editor_date_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showDeleteConfirm) {
        OneUiDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.training_goal_editor_delete_confirm_title)) },
            text = { Text(stringResource(R.string.training_goal_editor_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onSetPlan(null)
                    showDeleteConfirm = false
                    onMessage(resources.getString(R.string.training_goal_editor_deleted_status))
                    onDismiss()
                }) { Text(stringResource(R.string.common_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

/**
 * Entspricht Darts `_formatNum`: ganze Werte ohne Nachkommastellen, sonst die
 * Standard-Textdarstellung.
 */
private fun formatGoalNumber(value: Double): String {
    val rounded = Math.round(value)
    return if (rounded.toDouble() == value) rounded.toString() else value.toString()
}
