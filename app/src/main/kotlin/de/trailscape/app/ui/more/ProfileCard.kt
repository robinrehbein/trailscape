package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.core.Sex
import de.trailscape.core.TrainingProfile
import de.trailscape.core.defaultEftpWPerKg
import de.trailscape.core.defaultSetupMassKg
import de.trailscape.core.maxEftpW
import de.trailscape.core.minEftpW
import kotlin.math.round

/**
 * Trainingsprofil-Formular — Port von `_buildProfileCard()` aus
 * `lib/screens/more_screen.dart`. Alter, Geschlecht und Gewicht sind Pflicht,
 * alle anderen Felder optional.
 *
 * Uebernimmt ein von aussen (Backup-Import, initialer Ladevorgang) neu
 * gesetztes [AppViewModel.profile] in die Eingabefelder — aber nur, wenn sich
 * die Signatur wirklich geaendert hat, damit eigene Tastatureingaben nicht
 * durch einen Rebuild ueberschrieben werden (Aequivalent zu `_adoptProfile`
 * im Original).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileCard(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val profile by appViewModel.profile.collectAsStateWithLifecycle()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    var ageText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var setupMassText by remember { mutableStateOf("") }
    var weeklyHoursText by remember { mutableStateOf("") }
    var hrMaxText by remember { mutableStateOf("") }
    var lthrText by remember { mutableStateOf("") }
    var restingHrText by remember { mutableStateOf("") }
    var ftpText by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(Sex.UNBEKANNT) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var appliedSignature by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile) {
        val signature = profile.toJson().toString()
        if (signature == appliedSignature) return@LaunchedEffect
        appliedSignature = signature
        ageText = profile.ageYears.toString()
        weightText = formatProfileNumber(profile.weightKg)
        setupMassText = formatProfileNumber(profile.setupMassKg)
        weeklyHoursText = profile.weeklyHours?.let { formatProfileNumber(it) } ?: ""
        hrMaxText = profile.hrMaxOverride?.let { formatProfileNumber(it) } ?: ""
        lthrText = profile.lthrOverride?.let { formatProfileNumber(it) } ?: ""
        restingHrText = profile.restingHrOverride?.let { formatProfileNumber(it) } ?: ""
        ftpText = profile.eftpOverrideW?.let { formatProfileNumber(it) } ?: ""
        sex = profile.sex
    }

    MoreSectionCard(title = "Profil", modifier = modifier) {
        Text(
            text = "Alter, Geschlecht und Gewicht sind die Grundlage für Trainingslast, " +
                "Fitness-Kurve und Erholungswerte.",
            style = MaterialTheme.typography.bodySmall,
            color = hintColor,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row {
            OutlinedTextField(
                value = ageText,
                onValueChange = { ageText = it },
                label = { Text("Alter") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            SexDropdown(value = sex, onChange = { sex = it }, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row {
            OutlinedTextField(
                value = weightText,
                onValueChange = { weightText = it },
                label = { Text("Gewicht (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = setupMassText,
                onValueChange = { setupMassText = it },
                label = { Text("Rad + Gepäck (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Ohne Angabe rechnen wir mit ${formatProfileNumber(defaultSetupMassKg)} kg " +
                "für Rad und Gepäck.",
            style = MaterialTheme.typography.bodySmall,
            color = hintColor,
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = weeklyHoursText,
            onValueChange = { weeklyHoursText = it },
            label = { Text("Zeit pro Woche (Stunden, optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Mit deinem Zeitbudget deckeln wir das Wochenziel auf das, was sich in " +
                "dieser Zeit realistisch fahren lässt.",
            style = MaterialTheme.typography.bodySmall,
            color = hintColor,
        )
        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = { advancedOpen = !advancedOpen }) {
            Icon(
                imageVector = if (advancedOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = hintColor,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Erweitert", style = MaterialTheme.typography.titleSmall)
        }

        if (advancedOpen) {
            Text(
                text = "Ohne eigene Werte schätzen wir die maximale Herzfrequenz aus deinem " +
                    "Alter (208 − 0,7 × Alter) und die Schwelle daraus. Ein HFmax-Feldtest — " +
                    "nach gutem Aufwärmen ein harter Anstieg über 3–5 Minuten mit maximalem " +
                    "Endspurt — verbessert die Genauigkeit aller Auswertungen deutlich.",
                style = MaterialTheme.typography.bodySmall,
                color = hintColor,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = hrMaxText,
                onValueChange = { hrMaxText = it },
                label = { Text("HFmax (bpm, optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = lthrText,
                onValueChange = { lthrText = it },
                label = { Text("Schwellenpuls LTHR (bpm, optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = restingHrText,
                onValueChange = { restingHrText = it },
                label = { Text("Ruhepuls (bpm, optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Ohne eigenen Ruhepuls nehmen wir den aus deinen Vitaldaten gemessenen Wert.",
                style = MaterialTheme.typography.bodySmall,
                color = hintColor,
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = ftpText,
                onValueChange = { ftpText = it },
                label = { Text("Schwellenleistung FTP (Watt, optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Die FTP ist die Leistung, die du rund eine Stunde am Stück halten " +
                    "kannst. Sie ist der Massstab für jede Trainingslast: An ihr hängen " +
                    "Fitness (CTL), Ermüdung (ATL), Form (TSB) und dein Wochenziel — " +
                    "änderst du sie, verschieben sich auch alle bisherigen Werte.\n\n" +
                    "Ohne Eintrag schätzen wir: zuerst aus deinem besten " +
                    "20-Minuten-Abschnitt (× 0,95), dann aus dem Abgleich mit deiner " +
                    "gemessenen Herzfrequenz, notfalls grob mit " +
                    "${formatProfileNumber(defaultEftpWPerKg)} W/kg — für ambitionierte " +
                    "Fahrer:innen deutlich zu niedrig. Ein eigener Wert ist deshalb die " +
                    "wirksamste Einzelangabe in diesem Formular. Für eine belastbare Zahl " +
                    "fährst du nach gutem Aufwärmen 20 Minuten am Anschlag und trägst " +
                    "95 % deiner Durchschnittsleistung ein; ohne Leistungsmesser bleibt " +
                    "es auch hier ein Schätzwert.",
                style = MaterialTheme.typography.bodySmall,
                color = hintColor,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        statusText?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = {
                statusText = saveProfile(
                    current = profile,
                    ageText = ageText,
                    sex = sex,
                    weightText = weightText,
                    setupMassText = setupMassText,
                    weeklyHoursText = weeklyHoursText,
                    hrMaxText = hrMaxText,
                    lthrText = lthrText,
                    restingHrText = restingHrText,
                    ftpText = ftpText,
                    onSave = appViewModel::setProfile,
                )
            },
        ) {
            Text("Profil speichern")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SexDropdown(value: Sex, onChange: (Sex) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = sexLabels.getValue(value),
            onValueChange = {},
            readOnly = true,
            label = { Text("Geschlecht") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sexLabels.forEach { (sexValue, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onChange(sexValue)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val sexLabels: Map<Sex, String> = linkedMapOf(
    Sex.MAENNLICH to "männlich",
    Sex.WEIBLICH to "weiblich",
    Sex.UNBEKANNT to "keine Angabe",
)

/** Entspricht Darts `_formatNumber`: ganze Werte ohne Nachkommastellen. */
internal fun formatProfileNumber(value: Double): String =
    if (value == round(value)) value.toLong().toString() else value.toString()

/** Entspricht Darts `_parseNumber`: Komma als Dezimaltrennzeichen erlaubt. */
internal fun parseProfileNumber(raw: String): Double? {
    val trimmed = raw.trim().replace(',', '.')
    if (trimmed.isEmpty()) return null
    return trimmed.toDoubleOrNull()
}

/**
 * Validiert die Eingabefelder wie `_saveProfile()` im Original und speichert
 * bei Erfolg ueber [onSave]. Liefert den anzuzeigenden Statustext (Fehler
 * oder Erfolgsmeldung).
 */
private fun saveProfile(
    current: TrainingProfile,
    ageText: String,
    sex: Sex,
    weightText: String,
    setupMassText: String,
    weeklyHoursText: String,
    hrMaxText: String,
    lthrText: String,
    restingHrText: String,
    ftpText: String,
    onSave: (TrainingProfile) -> Unit,
): String {
    val age = ageText.trim().toIntOrNull()
    if (age == null || age < 10 || age > 100) {
        return "Bitte ein Alter zwischen 10 und 100 Jahren angeben."
    }
    val weight = parseProfileNumber(weightText)
    if (weight == null || weight < 30 || weight > 250) {
        return "Bitte ein Gewicht zwischen 30 und 250 kg angeben."
    }
    val setupMass = parseProfileNumber(setupMassText)
    if (setupMass != null && (setupMass < 0 || setupMass > 60)) {
        return "Das Gewicht von Rad und Gepäck sollte unter 60 kg liegen."
    }
    val weeklyHours = parseProfileNumber(weeklyHoursText)
    if (weeklyHours != null && (weeklyHours <= 0 || weeklyHours > 40)) {
        return "Bitte eine Wochenzeit zwischen 1 und 40 Stunden angeben."
    }
    // Dieselben Grenzen wie im Rechenkern (`minEftpW`/`maxEftpW`): Ein Wert
    // ausserhalb wuerde dort ohnehin geklemmt — dann sagen wir es lieber hier.
    val ftp = parseProfileNumber(ftpText)
    if (ftp != null && (ftp < minEftpW || ftp > maxEftpW)) {
        return "Bitte eine FTP zwischen ${formatProfileNumber(minEftpW)} und " +
            "${formatProfileNumber(maxEftpW)} Watt angeben."
    }

    onSave(
        TrainingProfile(
            ageYears = age,
            sex = sex,
            weightKg = weight,
            setupMassKg = setupMass ?: defaultSetupMassKg,
            hrMaxOverride = parseProfileNumber(hrMaxText),
            lthrOverride = parseProfileNumber(lthrText),
            restingHrOverride = parseProfileNumber(restingHrText),
            cda = current.cda,
            crr = current.crr,
            driveEfficiency = current.driveEfficiency,
            eftpOverrideW = ftp,
            weeklyHours = weeklyHours,
        ),
    )
    return "Profil gespeichert."
}
