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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.today.PLAN_DAY_CODES
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.Goal
import de.trailscape.core.GoalFinishPrediction
import de.trailscape.core.GoalType
import de.trailscape.core.PlanPreferences
import de.trailscape.core.PROGNOSIS_LOOKBACK_DAYS
import de.trailscape.core.RideInfo
import de.trailscape.core.TrainingPlan
import de.trailscape.core.assessFitness
import de.trailscape.core.errorFitnessBlockLength
import de.trailscape.core.fitnessGoal
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.typicalAvgSpeedKmh
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

/**
 * Formatiert Minuten als „2:10 h". Zwischen Zahl und Einheit steht ein
 * geschuetztes Leerzeichen (U+00A0): Bei grosser Schrift brach die fette
 * Prognose sonst als „ca. 2:31" / „h" um.
 */
internal fun formatHoursMinutes(minutes: Int): String = "${formatGoalDuration(minutes)}\u00A0h"

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
 *
 * Die Hoehe kommt bewusst aus `training_goal_ascent` statt aus
 * `common_value_elevation`: Der Trainings-Tab schreibt im Deutschen „Hm"
 * (gross), die uebrige App „hm" — und das Deutsche bleibt woertlich.
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
    if (goal.type == GoalType.FITNESS) {
        // Fitnessblock: keine Zieldistanz; die Vergleichsrunde ist optional.
        val parts = mutableListOf<UiText>(UiText.Res(R.string.plan_inputs_goal_fitness_kind))
        if (goal.distanceKm > 0) {
            parts.add(UiText.Res(R.string.plan_inputs_goal_fitness_benchmark, listOf(formatGoalKm(goal.distanceKm, language))))
        }
        parts.add(UiText.Res(R.string.plan_inputs_goal_fitness_ends, listOf(formatGoalDate(date, language))))
        parts.add(remaining)
        return parts
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

            if (goal.type == GoalType.FITNESS) {
                // Ein Fitnessblock hat weder Zieldistanz noch Zielzeit: keine
                // Prognose, nur ein Satz, wie der Block endet.
                Surface(
                    color = theme.primaryContainer,
                    contentColor = theme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.plan_inputs_goal_fitness_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
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
internal enum class PrognosisMarker(@param:StringRes val labelRes: Int) {
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

/** Kürzeste und längste Blocklänge eines Fitnessziels (Spiegel der Grenzen in `:core`). */
internal const val FITNESS_BLOCK_MIN_WEEKS = 4
internal const val FITNESS_BLOCK_MAX_WEEKS = 16

/** Vorgabe für die Blocklänge, wenn noch kein Fitnessplan besteht. */
internal const val FITNESS_BLOCK_DEFAULT_WEEKS = 8

/** Obergrenze des Zeitbudgets (Spiegel von `PlanPreferences.MAX_PLAUSIBLE_WEEKLY_HOURS`). */
private const val MAX_WEEKLY_HOURS = 40.0

/** Alle sieben Wochentage als Bitmaske (Bit 0 = Mo … Bit 6 = So). */
internal const val ALL_DAYS_MASK = 0b1111111

internal fun daysOfMask(mask: Int): List<Int> = (0..6).filter { mask and (1 shl it) != 0 }

internal fun maskOfDays(days: Collection<Int>): Int = days.filter { it in 0..6 }.fold(0) { m, d -> m or (1 shl d) }

/** Was am Formular falsch ist; die Anzeige macht daraus den Satz. */
internal enum class GoalFormError { NAME, DISTANCE, DATE, TARGET, FITNESS_WEEKS, BENCHMARK, HOURS }

/** Die Eingaben des Zielblatts als Rohtexte, so wie sie im Formular stehen. */
internal data class GoalFormInput(
    val name: String,
    val fitness: Boolean,
    val distanceText: String = "",
    val ascentText: String = "",
    val date: LocalDate? = null,
    val targetText: String = "",
    val weeksText: String = "",
    val benchmarkText: String = "",
    /** Gewählte Trainingstage als Bitmaske; alle sieben = keine Einschränkung. */
    val daysMask: Int = ALL_DAYS_MASK,
    /** Tag der langen Tour (0 = Mo … 6 = So), `-1` = automatisch. */
    val longRideDay: Int = -1,
    val hoursText: String = "",
)

internal sealed interface GoalFormResult {
    /** [fitnessWeeks] ist nur bei einem Fitnessziel gesetzt. */
    data class Ok(val goal: Goal, val preferences: PlanPreferences?, val fitnessWeeks: Int?) : GoalFormResult

    data class Invalid(val error: GoalFormError) : GoalFormResult
}

/** Zeitbudget aus dem Textfeld: leer = `null`; ungültig = `Result.failure`. */
internal fun parseWeeklyHours(text: String): Result<Double?> {
    val raw = text.trim().replace(',', '.')
    if (raw.isEmpty()) return Result.success(null)
    val value = raw.toDoubleOrNull()
    return if (value != null && value.isFinite() && value > 0 && value <= MAX_WEEKLY_HOURS) {
        Result.success(value)
    } else {
        Result.failure(IllegalArgumentException(raw))
    }
}

/**
 * Die Präferenzen aus den Eingaben; `null`, wenn sie nichts festlegen. Sieben
 * gewählte Tage sind keine Einschränkung; die lange Tour muss auf einen
 * gewählten Tag fallen.
 */
internal fun buildPlanPreferences(daysMask: Int, longRideDay: Int, weeklyHours: Double?): PlanPreferences? {
    val days = daysOfMask(daysMask)
    val restricted = days.takeIf { it.isNotEmpty() && it.size < 7 }
    val longDay = longRideDay.takeIf { it in 0..6 && (days.isEmpty() || it in days) }
    return PlanPreferences(trainingDays = restricted, longRideDay = longDay, weeklyHours = weeklyHours)
        .takeUnless { it.isEmpty }
}

/** Vergleichbare Form von Präferenzen: bereinigt, leer = `null`, sieben Tage = keine Einschränkung. */
internal fun PlanPreferences?.normalizedForCompare(): PlanPreferences? = this
    ?.let {
        PlanPreferences(
            trainingDays = it.normalizedDays()?.takeIf { d -> d.size < 7 },
            longRideDay = it.validLongRideDay(),
            weeklyHours = it.validWeeklyHours(),
        )
    }
    ?.takeUnless { it.isEmpty }

/** Liest das Formular. Reihenfolge der Fehler wie im Blatt von oben nach unten. */
internal fun parseGoalForm(input: GoalFormInput, now: Long): GoalFormResult {
    val name = input.name.trim()
    if (name.isEmpty()) return GoalFormResult.Invalid(GoalFormError.NAME)
    val goal: Goal
    var fitnessWeeks: Int? = null
    if (input.fitness) {
        val weeks = input.weeksText.trim().toIntOrNull()
        if (weeks == null || weeks !in FITNESS_BLOCK_MIN_WEEKS..FITNESS_BLOCK_MAX_WEEKS) {
            return GoalFormResult.Invalid(GoalFormError.FITNESS_WEEKS)
        }
        val benchmarkRaw = input.benchmarkText.trim().replace(',', '.')
        val benchmark = if (benchmarkRaw.isEmpty()) 0.0 else benchmarkRaw.toDoubleOrNull()
        if (benchmark == null || !benchmark.isFinite() || benchmark < 0) {
            return GoalFormResult.Invalid(GoalFormError.BENCHMARK)
        }
        fitnessWeeks = weeks
        goal = fitnessGoal(name, weeks, now, benchmarkKm = benchmark)
    } else {
        val distance = input.distanceText.trim().replace(',', '.').toDoubleOrNull()
        val ascentRaw = input.ascentText.trim().replace(',', '.')
        val ascent = if (ascentRaw.isEmpty()) null else ascentRaw.toDoubleOrNull()
        val target = if (input.targetText.isBlank()) null else parseGoalDuration(input.targetText)
        val date = input.date
        when {
            distance == null || distance <= 0 -> return GoalFormResult.Invalid(GoalFormError.DISTANCE)
            date == null -> return GoalFormResult.Invalid(GoalFormError.DATE)
            input.targetText.isNotBlank() && target == null -> return GoalFormResult.Invalid(GoalFormError.TARGET)
        }
        val dateMs = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        goal = Goal(name = name, distanceKm = distance, ascentM = ascent, date = dateMs, targetDurationMin = target)
    }
    val hours = parseWeeklyHours(input.hoursText).getOrElse { return GoalFormResult.Invalid(GoalFormError.HOURS) }
    return GoalFormResult.Ok(goal, buildPlanPreferences(input.daysMask, input.longRideDay, hours), fitnessWeeks)
}

/**
 * Muss der Plan neu gerechnet werden? Ja, sobald sich etwas ändert, woraus er
 * entsteht: Zieltyp, Strecke und Datum (Fitness: Blocklänge und Vergleichsrunde),
 * **Zielzeit** (sie bestimmt die Zieltempo-Abschnitte) und die Präferenzen.
 * Nur der Name ändert den Plan nicht.
 */
internal fun planNeedsRebuild(
    plan: TrainingPlan?,
    goal: Goal,
    preferences: PlanPreferences?,
    fitnessWeeks: Int?,
): Boolean {
    if (plan == null) return true
    val old = plan.goal
    if (old.type != goal.type) return true
    if (old.targetDurationMin != goal.targetDurationMin) return true
    if (old.distanceKm != goal.distanceKm) return true
    if (preferences.normalizedForCompare() != plan.preferences.normalizedForCompare()) return true
    return when (goal.type) {
        GoalType.FITNESS -> fitnessWeeks != plan.weeks.size
        GoalType.EVENT -> {
            val zone = ZoneId.systemDefault()
            old.ascentM != goal.ascentM ||
                Instant.ofEpochMilli(old.date).atZone(zone).toLocalDate() !=
                Instant.ofEpochMilli(goal.date).atZone(zone).toLocalDate()
        }
    }
}

/**
 * Das Zielformular als Blatt: Zieltyp (Event oder Fitness), Name, je nach Typ
 * Distanz/Höhenmeter/Datum/Zielzeit oder Blocklänge/Vergleichsrunde, dazu die
 * Plan-Präferenzen (Trainingstage, Tag der langen Tour, Wochenstunden).
 *
 * Port von `_buildGoalCard` (`lib/screens/training_screen.dart`), frueher die
 * Karte „Dein Ziel" am Ende des Plan-Kapitels. Die Felder werden beim Oeffnen
 * aus dem vorhandenen Plan (samt [TrainingPlan.preferences]) vorbefuellt; ohne
 * Plan kommt das Zeitbudget aus [profileWeeklyHours].
 *
 * ## Zwei Wege zum Speichern
 *  * **„Plan erstellen"** — ohne Plan oder wenn sich etwas geaendert hat,
 *    woraus der Plan entsteht ([planNeedsRebuild]: Strecke, Datum, Zielzeit,
 *    Zieltyp, Praeferenzen): Der Plan wird mit `generatePlan` neu gerechnet.
 *  * **„Speichern"** — wenn nur der Name anders ist: Der Plan bleibt.
 *
 * „Plan löschen" bleibt die zerstoererische Aktion mit Rueckfrage.
 *
 * @param onMessage meldet das Ergebnis („Plan mit 12 Wochen erstellt.") an die
 *   Snackbar des Screens — das Blatt schliesst sich danach.
 * @param profileWeeklyHours Wochenstunden aus dem Profil; Vorgabe des
 *   Zeitbudgets bei einem neuen Plan.
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
    profileWeeklyHours: Double? = null,
) {
    val coreTexts = LocalCoreTexts.current
    val formats = LocalAppFormats.current
    // Fuer Meldungen und Fehler ausserhalb der Komposition (Knopf-Handler);
    // der Activity-Kontext traegt die App-Sprache.
    val resources = LocalContext.current.resources
    val theme = MaterialTheme.colorScheme
    val existing = plan?.goal
    val existingPrefs = plan?.preferences

    var fitness by rememberSaveable { mutableStateOf(existing?.type == GoalType.FITNESS) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var distanceText by rememberSaveable {
        mutableStateOf(existing?.takeIf { it.type == GoalType.EVENT }?.let { formatGoalNumber(it.distanceKm) } ?: "")
    }
    var ascentText by rememberSaveable {
        mutableStateOf(existing?.ascentM?.let { formatGoalNumber(it) } ?: "")
    }
    var goalDate by rememberSaveable {
        mutableStateOf(
            existing?.takeIf { it.type == GoalType.EVENT }
                ?.let { Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).toLocalDate() },
        )
    }
    var targetText by rememberSaveable {
        mutableStateOf(existing?.targetDurationMin?.let { formatGoalDuration(it) } ?: "")
    }
    var weeksText by rememberSaveable {
        mutableStateOf(
            (
                if (existing?.type == GoalType.FITNESS) {
                    plan.weeks.size.coerceIn(FITNESS_BLOCK_MIN_WEEKS, FITNESS_BLOCK_MAX_WEEKS)
                } else {
                    FITNESS_BLOCK_DEFAULT_WEEKS
                }
                ).toString(),
        )
    }
    var benchmarkText by rememberSaveable {
        mutableStateOf(existing?.takeIf { it.type == GoalType.FITNESS && it.distanceKm > 0 }?.let { formatGoalNumber(it.distanceKm) } ?: "")
    }
    var daysMask by rememberSaveable {
        mutableStateOf(existingPrefs?.normalizedDays()?.let(::maskOfDays) ?: ALL_DAYS_MASK)
    }
    var longRideDay by rememberSaveable { mutableStateOf(existingPrefs?.validLongRideDay() ?: -1) }
    var hoursText by rememberSaveable {
        mutableStateOf(
            (if (plan == null) profileWeeklyHours else existingPrefs?.weeklyHours)
                ?.takeIf { it > 0 }?.let { formatGoalNumber(it) } ?: "",
        )
    }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    fun input() = GoalFormInput(
        name = name,
        fitness = fitness,
        distanceText = distanceText,
        ascentText = ascentText,
        date = goalDate,
        targetText = targetText,
        weeksText = weeksText,
        benchmarkText = benchmarkText,
        daysMask = daysMask,
        longRideDay = longRideDay,
        hoursText = hoursText,
    )

    fun errorText(e: GoalFormError): String = when (e) {
        GoalFormError.NAME -> resources.getString(R.string.training_goal_editor_name_error)
        GoalFormError.DISTANCE -> resources.getString(R.string.training_goal_editor_distance_error)
        GoalFormError.DATE -> resources.getString(R.string.training_goal_editor_date_error)
        GoalFormError.TARGET -> resources.getString(R.string.training_goal_editor_target_error)
        GoalFormError.FITNESS_WEEKS -> errorFitnessBlockLength(coreTexts)
        GoalFormError.BENCHMARK -> resources.getString(R.string.plan_inputs_goal_editor_benchmark_error)
        GoalFormError.HOURS -> resources.getString(R.string.plan_inputs_prefs_hours_error)
    }

    // Muss der Plan neu gerechnet werden? Nur, wenn sich etwas geaendert hat,
    // woraus er entsteht — der Name gehoert nicht dazu, die Zielzeit schon.
    val parsedNow = parseGoalForm(input(), System.currentTimeMillis())
    val rebuild = when (parsedNow) {
        is GoalFormResult.Ok -> planNeedsRebuild(plan, parsedNow.goal, parsedNow.preferences, parsedNow.fitnessWeeks)
        is GoalFormResult.Invalid -> true
    }
    val onlyMeta = plan != null && !rebuild

    fun save() {
        val now = System.currentTimeMillis()
        val parsed = parseGoalForm(input(), now)
        if (parsed is GoalFormResult.Invalid) {
            error = errorText(parsed.error)
            return
        }
        parsed as GoalFormResult.Ok
        if (plan != null && !planNeedsRebuild(plan, parsed.goal, parsed.preferences, parsed.fitnessWeeks)) {
            onSetPlan(plan.copy(goal = plan.goal.copy(name = parsed.goal.name)))
            onMessage(resources.getString(R.string.training_goal_editor_saved_status))
            onDismiss()
            return
        }
        try {
            val newPlan = generatePlan(
                goal = parsed.goal,
                assessment = assessFitness(rides),
                now = now,
                currentCtl = currentCtl,
                preferences = parsed.preferences,
                speedKmh = typicalAvgSpeedKmh(rides),
                texts = coreTexts,
            )
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

            Text(stringResource(R.string.plan_inputs_goal_editor_type_label), style = MaterialTheme.typography.titleSmall)
            PillSegments(
                options = listOf(
                    stringResource(R.string.plan_inputs_goal_editor_type_event),
                    stringResource(R.string.plan_inputs_goal_editor_type_fitness),
                ),
                selectedIndex = if (fitness) 1 else 0,
                onSelect = {
                    fitness = it == 1
                    error = null
                },
                optionDescriptions = listOf(
                    stringResource(R.string.plan_inputs_goal_editor_type_event_cd),
                    stringResource(R.string.plan_inputs_goal_editor_type_fitness_cd),
                ),
            )
            Text(
                stringResource(
                    if (fitness) R.string.plan_inputs_goal_editor_type_fitness_hint else R.string.plan_inputs_goal_editor_type_event_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = theme.onSurfaceVariant,
            )

            OneUiTextField(
                label = stringResource(R.string.training_goal_editor_name_label),
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.training_goal_editor_name_placeholder),
                modifier = Modifier.fillMaxWidth(),
            )
            if (fitness) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OneUiTextField(
                        label = stringResource(R.string.plan_inputs_goal_editor_weeks_label),
                        value = weeksText,
                        onValueChange = { weeksText = it },
                        placeholder = stringResource(R.string.plan_inputs_goal_editor_weeks_placeholder),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    OneUiTextField(
                        label = stringResource(R.string.plan_inputs_goal_editor_benchmark_label),
                        value = benchmarkText,
                        onValueChange = { benchmarkText = it },
                        placeholder = stringResource(R.string.plan_inputs_goal_editor_benchmark_placeholder),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
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
            }

            // ------------------------------------------------ Plan-Praeferenzen
            val selectedDays = daysOfMask(daysMask)
            Text(stringResource(R.string.plan_inputs_prefs_days_label), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (day in 0..6) {
                    val code = PLAN_DAY_CODES[day]
                    val on = day in selectedDays
                    PlanDayChip(
                        label = coreTexts.format.weekdayShort(code),
                        description = coreTexts.format.weekdayLong(code),
                        selected = on,
                        role = Role.Checkbox,
                        onToggle = {
                            // Mindestens ein Tag bleibt gewaehlt.
                            if (!on || selectedDays.size > 1) {
                                daysMask = daysMask xor (1 shl day)
                                if (on && longRideDay == day) longRideDay = -1
                            }
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.plan_inputs_prefs_days_hint),
                style = MaterialTheme.typography.bodySmall,
                color = theme.onSurfaceVariant,
            )

            Text(stringResource(R.string.plan_inputs_prefs_long_ride_label), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlanDayChip(
                    label = stringResource(R.string.plan_inputs_prefs_long_ride_auto),
                    description = stringResource(R.string.plan_inputs_prefs_long_ride_auto_cd),
                    selected = longRideDay !in selectedDays,
                    role = Role.RadioButton,
                    onToggle = { longRideDay = -1 },
                )
                // Nur Tage, die der Plan ueberhaupt nutzt.
                for (day in selectedDays) {
                    val code = PLAN_DAY_CODES[day]
                    PlanDayChip(
                        label = coreTexts.format.weekdayShort(code),
                        description = coreTexts.format.weekdayLong(code),
                        selected = longRideDay == day,
                        role = Role.RadioButton,
                        onToggle = { longRideDay = day },
                    )
                }
            }

            OneUiTextField(
                label = stringResource(R.string.plan_inputs_prefs_hours_label),
                value = hoursText,
                onValueChange = { hoursText = it },
                placeholder = stringResource(R.string.plan_inputs_prefs_hours_placeholder),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.plan_inputs_prefs_hours_hint),
                style = MaterialTheme.typography.bodySmall,
                color = theme.onSurfaceVariant,
            )

            if (plan != null && rebuild && parsedNow is GoalFormResult.Ok) {
                Text(
                    stringResource(R.string.plan_inputs_goal_editor_rebuild_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
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

/**
 * Ein Tag-Chip des Zielblatts. Die Semantik ist gebuendelt: Rolle
 * (Mehrfachauswahl = Checkbox, Einzelwahl = Radio), Zustand und der
 * ausgeschriebene Wochentag statt des Kuerzels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanDayChip(
    label: String,
    description: String,
    selected: Boolean,
    role: Role,
    onToggle: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label) },
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
            this.role = role
            this.selected = selected
            onClick { onToggle(); true }
        },
    )
}
