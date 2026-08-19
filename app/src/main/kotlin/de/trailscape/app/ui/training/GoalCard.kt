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
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.Goal
import de.trailscape.core.Ride
import de.trailscape.core.TrainingPlan
import de.trailscape.core.assessFitness
import de.trailscape.core.errorTooFar
import de.trailscape.core.errorTooSoon
import de.trailscape.core.generatePlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Karte „Dein Ziel": Zielformular (Name, Distanz, optional Höhenmeter,
 * Zieldatum) samt Validierung sowie „Plan erstellen"/„Plan löschen".
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
    rides: List<Ride>,
    onSetPlan: (TrainingPlan?) -> Unit,
    currentCtl: Double? = null,
) {
    val theme = MaterialTheme.colorScheme

    var name by rememberSaveable { mutableStateOf("") }
    var distanceText by rememberSaveable { mutableStateOf("") }
    var ascentText by rememberSaveable { mutableStateOf("") }
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
        val goal = Goal(name = trimmedName, distanceKm = distance, ascentM = ascent, date = dateMs)
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

            Box(modifier = Modifier.fillMaxWidth()) {
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
