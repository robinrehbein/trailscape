package de.trailscape.app.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.OneUiTextField
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.formatDurationHm
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.Goal
import de.trailscape.core.GoalTimeAssessment
import de.trailscape.core.GoalTimeVerdict
import de.trailscape.core.RideInfo
import de.trailscape.core.TrainingPlan
import de.trailscape.core.assessFitness
import de.trailscape.core.assessGoalTime
import de.trailscape.core.errorTooFar
import de.trailscape.core.errorTooSoon
import de.trailscape.core.generatePlan
import de.trailscape.core.goalTimeMaxRequiredSpeedKmh
import de.trailscape.core.goalTimeMinRequiredSpeedKmh
import de.trailscape.core.requiredPaceKmh
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Parst eine Zielzeit-Eingabe in Minuten; `null`, wenn die Eingabe keine ist.
 *
 * Akzeptierte Formen: `h:mm` („6:30"), Dezimalstunden mit Punkt oder Komma
 * („6.5", „6,5") und nackte Stunden („7"). Ein optionaler Suffix „h"/„H"
 * (auch „6:30 h") wird ignoriert — die Vorbefuellung aus einem gespeicherten
 * Plan und die Anzeige im UI schreiben beide mit Suffix, und die Eingabe muss
 * denselben Text zuruecklesen koennen. Eine **leere** Eingabe ist keine
 * Zielzeit und kein Fehler — der Aufrufer unterscheidet beides selbst:
 * nicht leer + `null` = ungültige Eingabe, leer = keine Angabe.
 */
private fun parseGoalTimeInput(input: String): Int? {
    val trimmed = input.trim().trimEnd { it == ' ' || it == 'h' || it == 'H' }
    if (trimmed.isEmpty()) {
        return null
    }
    val colon = trimmed.indexOf(':')
    val minutes = if (colon >= 0) {
        val hours = trimmed.substring(0, colon).trim().toIntOrNull()
        val mins = trimmed.substring(colon + 1).trim().toIntOrNull()
        if (hours == null || mins == null || mins !in 0..59) {
            return null
        }
        hours * 60 + mins
    } else {
        val hours = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
        (hours * 60).roundToInt()
    }
    return minutes.takeIf { it > 0 }
}

/**
 * Karte „Dein Ziel": Zielformular (Name, Distanz, optional Höhenmeter,
 * Zielzeit und Zieldatum) samt Validierung, Live-Bewertung der Zielzeit
 * ([goalTimeAssessmentText]) sowie „Plan erstellen"/„Plan löschen".
 *
 * Port von `_buildGoalCard` (`lib/screens/training_screen.dart`).
 *
 * Die Formularfelder werden — wie im Dart-Original (`_loadPlan()` in
 * `initState`) — genau **einmal** aus einem vorhandenen Plan vorbefuellt,
 * sobald dieser das erste Mal vorliegt ([initializedFromPlan]); spaetere
 * eigene Aenderungen an [plan] (z. B. durchs eigene „Plan erstellen") ueber-
 * schreiben die Eingabe danach nicht mehr.
 *
 * @param currentCtl aktuelle chronische Last aus der Auswertung
 *   (`insights.latest?.ctl`) — damit rechnet `generatePlan` jeder Woche ein
 *   Last-Budget aus; `null`, solange es keine Fitnesskurve gibt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalCard(
    plan: TrainingPlan?,
    rides: List<RideInfo>,
    onSetPlan: (TrainingPlan?) -> Unit,
    currentCtl: Double? = null,
) {
    val theme = MaterialTheme.colorScheme

    var name by rememberSaveable { mutableStateOf("") }
    var distanceText by rememberSaveable { mutableStateOf("") }
    var ascentText by rememberSaveable { mutableStateOf("") }
    var targetTimeText by rememberSaveable { mutableStateOf("") }
    var goalDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var statusIsError by rememberSaveable { mutableStateOf(false) }
    var initializedFromPlan by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(plan) {
        if (initializedFromPlan) return@LaunchedEffect
        val goal = plan?.goal ?: return@LaunchedEffect
        name = goal.name
        distanceText = formatGoalNumber(goal.distanceKm)
        ascentText = goal.ascentM?.let { formatGoalNumber(it) } ?: ""
        targetTimeText = goal.targetTimeMin?.let(::formatDurationHm) ?: ""
        goalDate = Instant.ofEpochMilli(goal.date).atZone(ZoneId.systemDefault()).toLocalDate()
        initializedFromPlan = true
    }

    fun createPlan() {
        val trimmedName = name.trim()
        val distance = distanceText.trim().replace(',', '.').toDoubleOrNull()
        val ascentRaw = ascentText.trim().replace(',', '.')
        val ascent = if (ascentRaw.isEmpty()) null else ascentRaw.toDoubleOrNull()

        if (trimmedName.isEmpty()) {
            status = "Bitte einen Namen für das Ziel angeben."
            statusIsError = true
            return
        }
        if (distance == null || distance <= 0) {
            status = "Bitte eine gültige Distanz angeben."
            statusIsError = true
            return
        }

        val targetTimeMin = if (targetTimeText.isNotBlank()) {
            val parsed = parseGoalTimeInput(targetTimeText)
            if (parsed == null) {
                status = "Zielzeit als Stunden:Minuten (z. B. 6:30) oder in Stunden angeben."
                statusIsError = true
                return
            }
            val pace = requiredPaceKmh(distance, parsed)
            if (pace == null || pace < goalTimeMinRequiredSpeedKmh || pace > goalTimeMaxRequiredSpeedKmh) {
                val paceText = pace?.let(::formatOneDecimalDe) ?: "–"
                status = "Für diese Zielzeit bräuchtest du Ø $paceText km/h – das ist kein Gravel-Schnitt."
                statusIsError = true
                return
            }
            parsed
        } else {
            null
        }

        val date = goalDate
        if (date == null) {
            status = "Bitte ein Zieldatum angeben."
            statusIsError = true
            return
        }

        val dateMs = date.atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val goal = Goal(
            name = trimmedName,
            distanceKm = distance,
            ascentM = ascent,
            targetTimeMin = targetTimeMin,
            date = dateMs,
        )
        val assessment = assessFitness(rides)
        try {
            val newPlan = generatePlan(goal, assessment, currentCtl = currentCtl)
            onSetPlan(newPlan)
            status = "Plan mit ${newPlan.weeks.size} Wochen erstellt."
            statusIsError = false
        } catch (e: IllegalArgumentException) {
            status = e.message ?: "Ungültiges Ziel."
            statusIsError = true
        }
    }

    // Die Bewertung der eingegebenen Zielzeit — live, schon vor dem
    // „Plan erstellen". Sie rechnet gegen dieselbe Historie, die spaeter der
    // Plan nutzt; ohne gueltige Distanz oder Zeit sagt sie nichts.
    val timeAssessment = remember(distanceText, targetTimeText, rides) {
        val distance = distanceText.trim().replace(',', '.').toDoubleOrNull()
        val minutes = parseGoalTimeInput(targetTimeText)
        if (distance == null || distance <= 0 || minutes == null) {
            null
        } else {
            assessGoalTime(
                Goal(name = "x", distanceKm = distance, targetTimeMin = minutes, date = 0L),
                rides,
            )
        }
    }

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Dein Ziel", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            OneUiTextField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

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
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OneUiTextField(
                    label = "Zielzeit, z. B. 6:30 (optional)",
                    value = targetTimeText,
                    onValueChange = { targetTimeText = it },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    OneUiTextField(
                        label = "Zieldatum",
                        value = goalDate?.let { formatDate(it) } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        // „Datum wählen" ist eine Aufforderung, kein Wert — als
                        // `value` sah sie aus wie ein bereits gesetztes Datum und
                        // haette bei einer Bildschirmvorlesung als Inhalt des Felds
                        // gegolten. Als `placeholder` verschwindet sie, sobald ein
                        // echtes Datum darin steht.
                        placeholder = "Datum wählen",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Ueberlagerung statt `enabled = false`: das Feld soll normal
                    // aussehen (Rahmenfarbe, Label), aber nur den Datumsdialog
                    // oeffnen — dasselbe Muster wie Darts `InkWell` um `InputDecorator`.
                    //
                    // Fuer eine Bildschirmvorlesung war das bisher eine Falle: An
                    // derselben Stelle lagen zwei Ziele — ein Textfeld, das nichts
                    // tut, und darueber eine namenlose Flaeche, die alles tut. Die
                    // gebuendelte Semantik macht daraus einen einzigen, benannten
                    // Halt, der auch sagt, was er ist.
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
            }

            // Die Live-Bewertung der Zielzeit: noetiger Schnitt, Prognose aus
            // der eigenen Historie, Urteil in einem Wort. Sie steht direkt
            // unter der Eingabe, weil sie die Eingabe bewertet — und faellt
            // ganz weg, solange keine bewertbare Zeit (und Distanz) dasteht.
            timeAssessment?.let { assessment ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = goalTimeAssessmentText(assessment),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (assessment.verdict == GoalTimeVerdict.UNREALISTISCH) {
                        theme.error
                    } else {
                        theme.primary
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            status?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusIsError) theme.error else theme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = ::createPlan) { Text("Plan erstellen") }
                if (plan != null) {
                    // Loeschen ist die zerstoerende Aktion dieser Karte und
                    // traegt deshalb — wie jeder Loeschweg der App — die
                    // Fehlerfarbe des Themes.
                    NeutralButton(
                        onClick = { showDeleteConfirm = true },
                        destructive = true,
                    ) { Text("Plan löschen") }
                }
            }
        }
    }

    if (showDatePicker) {
        val today = LocalDate.now()
        val minMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val maxMillis = today.plusDays(400).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val initial = (goalDate ?: today.plusDays(60))
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
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Abbrechen") }
            },
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
                    status = null
                    showDeleteConfirm = false
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Abbrechen") }
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
 * Der Satz zur Zielzeit: noetiger Schnitt, Prognose, Urteil — eine Zeile.
 *
 *  * „Zielzeit 6:30 h → dafür Ø 18,5 km/h."
 *  * Mit Historie: „ Deine Touren sagen ≈ 6:10 h — knapp."
 *  * Ohne (gefahren) Historie sagt die Prognose nichts Persoenliches aus und
 *    wird nicht behauptet, sondern gekennzeichnet.
 */
private fun goalTimeAssessmentText(assessment: GoalTimeAssessment): String {
    val pace = formatOneDecimalDe(assessment.requiredAvgSpeedKmh)
    val builder = StringBuilder("Zielzeit ${formatDurationHm(assessment.targetTimeMin)}")
    builder.append(" → dafür Ø $pace km/h.")
    if (assessment.basedOnHistory) {
        builder.append(
            " Deine Touren sagen ≈ ${formatDurationHm(assessment.estimatedTimeMin)}" +
                " — ${assessment.verdict.label}.",
        )
    } else {
        builder.append(" Ohne Fahrhistorie lässt sich deine Zeit noch nicht einschätzen.")
    }
    return builder.toString()
}
