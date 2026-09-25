package de.trailscape.app.ui.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.OneUiTextField
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.formatKmDe
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
import de.trailscape.core.parseGoalDuration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
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

/** Zieldistanz ohne „,0" bei ganzen Kilometern („60", aber „42,2"). */
internal fun formatGoalKm(km: Double): String =
    if (km == Math.rint(km)) km.toLong().toString() else formatKmDe(km)

/**
 * „60 km · 700 Hm · Sa, 20. Dezember · noch 12 Wochen" — die Kopfzeile des
 * Ziels. Unter zwei Wochen zaehlt sie Tage; nach dem Renntag sagt sie das.
 */
internal fun goalSummaryLine(goal: Goal, today: LocalDate = LocalDate.now()): String {
    val date = Instant.ofEpochMilli(goal.date).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMANY).removeSuffix(".")
    val dateText = "$day, ${date.format(DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMANY))}"
    val days = ChronoUnit.DAYS.between(today, date)
    val remaining = when {
        days < 0 -> "vorbei"
        days == 0L -> "heute"
        days == 1L -> "morgen"
        days < 14 -> "noch $days Tage"
        else -> "noch ${(days / 7.0).roundToInt()} Wochen"
    }
    val parts = mutableListOf("${formatGoalKm(goal.distanceKm)} km")
    goal.ascentM?.takeIf { it > 0 }?.let { parts.add("${it.roundToInt()} Hm") }
    parts.add(dateText)
    parts.add(remaining)
    return parts.joinToString(" · ")
}

/**
 * Der Satz unter den Zeiten: wohin der Plan einen bringt, plus ein schlichter
 * Hinweis — oder, ohne genug Touren, was fuer eine Prognose fehlt.
 *
 * @return Paar aus (Hauptsatz-Anfang, fette Zahl, Hauptsatz-Ende, Hinweis).
 */
internal data class PrognosisNote(
    val lead: String,
    val bold: String?,
    val tail: String,
    val hint: String?,
)

internal fun prognosisNote(goal: Goal, prediction: GoalFinishPrediction): PrognosisNote {
    val p = prediction.prognosis
        ?: return PrognosisNote(prediction.missing ?: "Noch keine Prognose.", null, "", null)
    val target = goal.targetDurationMin
    val reference = p.atEventMin ?: p.currentMin
    val hint = when {
        p.beyondLongestRide ->
            "Wichtigster Hebel: die langen Fahrten. Das Rennen ist länger als deine " +
                "längste Tour der letzten Wochen."
        target != null && reference > target ->
            "Für deine Zielzeit fehlen noch etwa ${reference - target} Min. Am meisten " +
                "bringen die langen Fahrten."
        target != null -> "Das reicht für deine Zielzeit. Bleib bei den langen Fahrten dran."
        else -> "Trag eine Zielzeit ein, dann siehst du, ob es reicht."
    }
    return if (p.atEventMin != null) {
        PrognosisNote(
            lead = "Mit dem Plan kommst du bis zum Renntag auf ",
            bold = "ca. ${formatHoursMinutes(p.atEventMin!!)}",
            tail = ".",
            hint = hint,
        )
    } else {
        PrognosisNote(
            lead = "Sobald deine Fitnesskurve steht, rechnen wir auch den Renntag aus.",
            bold = null,
            tail = "",
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
    val p = prediction.prognosis
    val note = prognosisNote(goal, prediction)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dein Ziel",
                        style = MaterialTheme.typography.labelLarge,
                        color = theme.onSurfaceVariant,
                    )
                    Text(text = goal.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = goalSummaryLine(goal),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onEdit) { Text("Ändern") }
            }

            // Die beiden Zeiten nebeneinander; IntrinsicSize.Min haelt die
            // Kacheln gleich hoch, auch wenn eine davon zweizeilig wird.
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TimeTile(
                    label = "Stand heute",
                    value = p?.let { formatHoursMinutes(it.currentMin) } ?: "–",
                    accent = false,
                )
                val target = goal.targetDurationMin
                if (target != null) {
                    TimeTile(label = "Dein Ziel", value = formatHoursMinutes(target), accent = true)
                } else {
                    TimeTile(
                        label = "Dein Ziel",
                        value = "Zielzeit eintragen",
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
                            append(note.lead)
                            note.bold?.let { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(it) } }
                            append(note.tail)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    note.hint?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            TextButton(
                onClick = onExplain,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Wie wird das berechnet?") }
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
 * Die kleine Skala unter den Zeiten: links langsamer, rechts schneller. Der
 * graue Punkt ist „heute", der Strich die Zielzeit, der Akzentpunkt die
 * Prognose mit Plan am Renntag.
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

    val description = buildString {
        append("Heute ${formatHoursMinutes(currentMin)}")
        targetMin?.let { append(", Ziel ${formatHoursMinutes(it)}") }
        atEventMin?.let { append(", mit Plan ${formatHoursMinutes(it)}") }
    }
    val startColor = theme.primaryContainer
    val endColor = theme.surfaceVariant
    val nowColor = theme.onSurfaceVariant
    val accent = theme.primary
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
            fun dot(t: Int, color: androidx.compose.ui.graphics.Color) {
                val x = size.width * pos(t)
                drawCircle(ring, radius = r, center = Offset(x, cy))
                drawCircle(color, radius = r - 3.dp.toPx(), center = Offset(x, cy))
            }
            dot(currentMin, nowColor)
            targetMin?.let {
                val x = size.width * pos(it)
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(x - 1.5.dp.toPx(), 0f),
                    size = Size(3.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                )
            }
            atEventMin?.let { dot(it, accent) }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "langsamer",
                style = MaterialTheme.typography.labelSmall,
                color = theme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("schneller", style = MaterialTheme.typography.labelSmall, color = theme.onSurfaceVariant)
        }
    }
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
            Text("Ziel festlegen", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Trag ein Rennen oder eine Tour ein, auf die du hinfährst – mit Distanz, " +
                    "Höhenmetern, Datum und gern einer Zielzeit. Daraus entsteht dein Plan, und " +
                    "wir sagen dir, ob die Zeit drin ist.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (primary) {
                Button(onClick = onSetUp) { Text("Ziel festlegen") }
            } else {
                NeutralButton(onClick = onSetUp) { Text("Ziel festlegen") }
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetColumn {
            Text("Wie die Prognose entsteht", style = MaterialTheme.typography.titleLarge)
            ExplainRow(
                label = "Deine Touren",
                pill = "letzte ${PROGNOSIS_LOOKBACK_DAYS / 7} Wochen",
                text = "Dein Tempo auf Touren ab etwa 40 % der Zieldistanz. Längere und " +
                    "jüngere Touren zählen mehr." +
                    (p?.let { " Eingeflossen: ${it.ridesUsed} Touren." } ?: ""),
            )
            ExplainRow(
                label = "Deine Fitness",
                pill = currentCtl?.let { "${it.roundToInt()}" },
                text = "Je höher die Fitness am Renntag, desto länger hältst du dasselbe Tempo. " +
                    "Wir rechnen mit der Fitness, die dein Plan bis dahin aufbaut – höchstens " +
                    "8 % schneller als heute.",
            )
            ExplainRow(
                label = "Die Strecke",
                pill = buildString {
                    append("${formatGoalKm(goal.distanceKm)} km")
                    goal.ascentM?.takeIf { it > 0 }?.let { append(" · ${it.roundToInt()} Hm") }
                },
                text = "Höhenmeter kosten Zeit: Jeder Höhenmeter zählt wie 9 m flache Strecke. " +
                    "Ist das Rennen länger als deine längste Tour, rechnen wir etwas Zeit dazu.",
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (p != null) {
                        "Die Zahl ist eine Schätzung mit etwa ±${p.uncertaintyMin} Minuten. Sie " +
                            "wird mit jeder Tour genauer. Puls, Wind und Untergrund kennt sie nicht."
                    } else {
                        prediction.missing ?: "Noch keine Prognose."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
            NeutralButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Verstanden") }
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
            trimmedName.isEmpty() -> error = "Bitte einen Namen für das Ziel angeben."
            distance == null || distance <= 0 -> error = "Bitte eine gültige Distanz angeben."
            goalDate == null -> error = "Bitte ein Zieldatum angeben."
            targetText.isNotBlank() && target == null ->
                error = "Die Zielzeit bitte als Stunden:Minuten angeben, z. B. 2:10."
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
            onMessage("Ziel gespeichert.")
            onDismiss()
            return
        }
        try {
            val newPlan = generatePlan(goal, assessFitness(rides), currentCtl = currentCtl)
            onSetPlan(newPlan)
            onMessage("Plan mit ${newPlan.weeks.size} Wochen erstellt.")
            onDismiss()
        } catch (e: IllegalArgumentException) {
            error = e.message ?: "Ungültiges Ziel."
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetColumn {
            Text(if (plan == null) "Ziel festlegen" else "Ziel ändern", style = MaterialTheme.typography.titleLarge)

            OneUiTextField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                placeholder = "z. B. Rennen Hügelland",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                OneUiTextField(
                    label = "Distanz (km)",
                    value = distanceText,
                    onValueChange = { distanceText = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                OneUiTextField(
                    label = "Höhenmeter (optional)",
                    value = ascentText,
                    onValueChange = { ascentText = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) {
                    OneUiTextField(
                        label = "Zieldatum",
                        value = goalDate?.let { formatDate(it) } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        // Aufforderung als `placeholder`, nicht als Wert — sonst
                        // saehe sie aus wie ein gesetztes Datum.
                        placeholder = "Datum wählen",
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
                                contentDescription = goalDate
                                    ?.let { "Zieldatum, ${formatDate(it)}. Datum ändern" }
                                    ?: "Zieldatum wählen"
                            },
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                OneUiTextField(
                    label = "Zielzeit (h:mm)",
                    value = targetText,
                    onValueChange = { targetText = it },
                    placeholder = "optional",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.weight(1f),
                )
            }

            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = theme.error) }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = ::save) { Text(if (onlyMeta) "Speichern" else "Plan erstellen") }
                if (plan != null) {
                    // Loeschen traegt — wie jeder Loeschweg der App — die
                    // Fehlerfarbe des Themes.
                    NeutralButton(onClick = { showDeleteConfirm = true }, destructive = true) {
                        Text("Plan löschen")
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
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Abbrechen") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showDeleteConfirm) {
        OneUiDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Trainingsplan löschen") },
            text = { Text("Soll der Trainingsplan wirklich gelöscht werden?") },
            confirmButton = {
                TextButton(onClick = {
                    onSetPlan(null)
                    showDeleteConfirm = false
                    onMessage("Plan gelöscht.")
                    onDismiss()
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Abbrechen") } },
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
