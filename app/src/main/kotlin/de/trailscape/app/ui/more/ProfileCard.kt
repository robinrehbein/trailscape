package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.components.OneUiDropdownField
import de.trailscape.app.ui.components.OneUiTextField
import de.trailscape.app.ui.defaultTrainingProfile
import de.trailscape.core.Sex
import de.trailscape.core.TrainingProfile
import de.trailscape.core.defaultEftpWPerKg
import de.trailscape.core.defaultSetupMassKg
import de.trailscape.core.maxEftpW
import de.trailscape.core.minEftpW
import kotlin.math.round
import kotlinx.coroutines.delay

/**
 * Trainingsprofil — Inhalt der Seite „Profil" der Einstellungen (siehe
 * `MoreScreen.kt`). Urspruenglich ein Port von `_buildProfileCard()` aus
 * `lib/screens/more_screen.dart`. Alter, Geschlecht und Gewicht sind Pflicht,
 * alle anderen Felder optional.
 *
 * ## Kein Speichern-Knopf
 * Jedes Feld wird fuer sich geprueft ([profileFieldError]); gueltige Werte
 * uebernimmt die Seite kurz nach der Eingabe ([PERSIST_DELAY_MS]) und noch
 * einmal beim Verlassen ([buildProfileFromForm] entscheidet, was davon
 * gespeichert wird). Ein ungueltiges Feld haelt die uebrigen nicht auf: Es
 * behaelt schlicht seinen gespeicherten Wert, und die Fehlermeldung
 * erscheint darunter, sobald man das Feld verlaesst — nicht schon beim
 * ersten Tastendruck, wenn „1" auf dem Weg zu „15" noch kein Fehler ist.
 * Frueher sammelte ein Knopf „Profil speichern" alle Felder ein und meldete
 * nur den ersten Fehler; wer die Seite ohne ihn verliess, verlor alles.
 *
 * Uebernimmt ein von aussen (Backup-Import, initialer Ladevorgang) neu
 * gesetztes [AppViewModel.profile] in die Eingabefelder — aber nur, wenn sich
 * die Signatur wirklich geaendert hat. Eigene Speichervorgaenge setzen die
 * Signatur vorher selbst, damit das Speichern die gerade getippten Felder
 * nicht umformatiert (Aequivalent zu `_adoptProfile` im Original).
 *
 * ## Leere Felder statt fremder Zahlen
 * Solange [AppViewModel.profileConfirmed] aus ist, bleiben Alter, Gewicht und
 * „Rad + Gepäck" **leer**; die Standardwerte stehen nur als Platzhalter darin.
 * Vorher waren die Felder mit Alter 40 und 75 kg vorbelegt — den Werten aus
 * [de.trailscape.app.ui.defaultTrainingProfile] — und sahen damit aus wie eine
 * eigene, bereits getaetigte Eingabe. Gespeichert (und damit bestaetigt) wird
 * erst, wenn Alter **und** Gewicht gueltig eingetragen sind.
 */
@Composable
fun ProfileCardContent(appViewModel: AppViewModel) {
    val profile by appViewModel.profile.collectAsStateWithLifecycle()
    val confirmed by appViewModel.profileConfirmed.collectAsStateWithLifecycle()
    val formats = LocalAppFormats.current

    var form by remember { mutableStateOf(ProfileForm()) }
    var edited by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<ProfileField?>(null) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var appliedSignature by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile, confirmed) {
        val signature = profile.toJson().toString()
        if (signature == appliedSignature) return@LaunchedEffect
        appliedSignature = signature
        form = ProfileForm.of(profile, confirmed)
    }

    // Speichert, was gueltig ist und sich vom gespeicherten Profil
    // unterscheidet. Die Signatur wird vor dem Speichern gesetzt, damit das
    // zurueckkommende Profil die Felder nicht ueberschreibt (siehe KDoc).
    fun persist() {
        val next = buildProfileFromForm(profile, confirmed, form) ?: return
        if (confirmed && next == profile) return
        appliedSignature = next.toJson().toString()
        appViewModel.setProfile(next)
    }

    LaunchedEffect(form) {
        if (!edited) return@LaunchedEffect
        delay(PERSIST_DELAY_MS)
        persist()
    }
    val latestPersist by rememberUpdatedState(::persist)
    val latestEdited by rememberUpdatedState(edited)
    DisposableEffect(Unit) {
        onDispose { if (latestEdited) latestPersist() }
    }

    fun update(next: ProfileForm) {
        edited = true
        form = next
    }

    /** Fehlertext eines Felds — erst, wenn es nicht (mehr) den Fokus hat. */
    fun shownError(field: ProfileField): UiText? =
        if (focused == field) null else profileFieldError(field, form.text(field), confirmed)

    @Composable
    fun NumberField(field: ProfileField, label: String, modifier: Modifier, placeholder: String? = null) {
        ProfileNumberField(
            label = label,
            value = form.text(field),
            onValueChange = { update(form.with(field, it)) },
            error = shownError(field)?.asString(),
            placeholder = placeholder,
            decimal = field.decimal,
            onFocus = { hasFocus ->
                if (hasFocus) focused = field else if (focused == field) focused = null
            },
            modifier = modifier,
        )
    }

    SettingsHint(stringResource(R.string.more_profile_hint))
    Spacer(modifier = Modifier.height(12.dp))

    Row {
        // Der Platzhalter nennt genau die Zahl, mit der bis zur Eingabe
        // gerechnet wird — sichtbar als Vorschlag (grau, im leeren Feld)
        // statt als scheinbar eigene Angabe.
        NumberField(
            ProfileField.AGE,
            stringResource(R.string.more_profile_age_label),
            Modifier.weight(1f),
            placeholder = defaultTrainingProfile.ageYears.toString(),
        )
        Spacer(modifier = Modifier.width(12.dp))
        OneUiDropdownField(
            label = stringResource(R.string.more_profile_sex_label),
            value = form.sex,
            options = sexOptions(),
            onChange = { update(form.copy(sex = it)) },
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(modifier = Modifier.height(12.dp))

    Row {
        NumberField(
            ProfileField.WEIGHT,
            stringResource(R.string.more_profile_weight_label),
            Modifier.weight(1f),
            placeholder = formatProfileNumber(defaultTrainingProfile.weightKg),
        )
        Spacer(modifier = Modifier.width(12.dp))
        NumberField(
            ProfileField.SETUP_MASS,
            stringResource(R.string.more_profile_setup_mass_label),
            Modifier.weight(1f),
            placeholder = formatProfileNumber(defaultSetupMassKg),
        )
    }
    if (!confirmed) {
        Spacer(modifier = Modifier.height(4.dp))
        SettingsHint(
            stringResource(
                R.string.more_profile_defaults_hint,
                defaultTrainingProfile.ageYears,
                formatProfileNumber(defaultTrainingProfile.weightKg),
            ),
        )
    }
    Spacer(modifier = Modifier.height(12.dp))

    NumberField(
        ProfileField.WEEKLY_HOURS,
        stringResource(R.string.more_profile_weekly_hours_label),
        Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(4.dp))
    SettingsHint(stringResource(R.string.more_profile_weekly_hours_hint))
    Spacer(modifier = Modifier.height(8.dp))

    // Der Text „Erweitert" bleibt in beiden Zustaenden gleich und das Icon
    // traegt keinen Alternativtext — deshalb meldet die Semantik den Zustand
    // eigens.
    val expandedState = stringResource(R.string.more_learn_more_expanded_state)
    val collapsedState = stringResource(R.string.more_learn_more_collapsed_state)
    TextButton(
        onClick = { advancedOpen = !advancedOpen },
        modifier = Modifier.semantics {
            stateDescription = if (advancedOpen) expandedState else collapsedState
        },
    ) {
        Icon(
            imageVector = if (advancedOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.more_profile_advanced_action), style = MaterialTheme.typography.titleSmall)
    }

    if (advancedOpen) {
        SettingsHint(stringResource(R.string.more_profile_advanced_hint))
        LearnMore(stringResource(R.string.more_profile_hr_max_learn_more))
        Spacer(modifier = Modifier.height(8.dp))
        NumberField(ProfileField.HR_MAX, stringResource(R.string.more_profile_hr_max_label), Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))
        NumberField(ProfileField.LTHR, stringResource(R.string.more_profile_lthr_label), Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))
        NumberField(
            ProfileField.RESTING_HR,
            stringResource(R.string.more_profile_resting_hr_label),
            Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        SettingsHint(stringResource(R.string.more_profile_resting_hr_hint))

        Spacer(modifier = Modifier.height(12.dp))
        NumberField(ProfileField.FTP, stringResource(R.string.more_profile_ftp_label), Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        SettingsHint(stringResource(R.string.more_profile_ftp_hint))
        LearnMore(
            stringResource(R.string.more_profile_ftp_learn_more, formats.decimal(defaultEftpWPerKg, 1)),
        )
    }
}

/** Ein Zahlenfeld mit Fehlerzeile darunter. */
@Composable
private fun ProfileNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    placeholder: String?,
    decimal: Boolean,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OneUiTextField(
            label = label,
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            ),
            fieldModifier = Modifier.onFocusChanged { onFocus(it.isFocused) },
        )
        error?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Wie lange nach dem letzten Tastendruck gespeichert wird. Kurz genug, dass
 * nichts verloren geht, lang genug, dass nicht jede Ziffer die ganze
 * Trainingsauswertung neu anstoesst.
 */
private const val PERSIST_DELAY_MS = 600L

/**
 * Die Auswahl des Geschlechts — als geordnete Liste, weil die Reihenfolge im
 * aufgeklappten Menue genau diese ist (siehe [OneUiDropdownField]).
 */
@Composable
private fun sexOptions(): List<Pair<Sex, String>> = listOf(
    Sex.MAENNLICH to stringResource(R.string.more_profile_sex_male),
    Sex.WEIBLICH to stringResource(R.string.more_profile_sex_female),
    Sex.UNBEKANNT to stringResource(R.string.more_profile_sex_unknown),
)

/** Die Textfelder des Profils. */
internal enum class ProfileField(val decimal: Boolean) {
    AGE(false),
    WEIGHT(true),
    SETUP_MASS(true),
    WEEKLY_HOURS(true),
    HR_MAX(false),
    LTHR(false),
    RESTING_HR(false),
    FTP(false),
}

/** Der Rohzustand des Formulars: die Texte, wie getippt, plus das Geschlecht. */
internal data class ProfileForm(
    val texts: Map<ProfileField, String> = emptyMap(),
    val sex: Sex = Sex.UNBEKANNT,
) {
    fun text(field: ProfileField): String = texts[field].orEmpty()

    fun with(field: ProfileField, value: String): ProfileForm = copy(texts = texts + (field to value))

    companion object {
        /**
         * Die Felder zu einem gespeicherten Profil. Die drei Pflicht- bzw.
         * Standardfelder bleiben leer, solange nichts bestaetigt ist — siehe
         * KDoc von [ProfileCardContent].
         */
        fun of(profile: TrainingProfile, confirmed: Boolean): ProfileForm = ProfileForm(
            texts = mapOf(
                ProfileField.AGE to if (confirmed) profile.ageYears.toString() else "",
                ProfileField.WEIGHT to if (confirmed) formatProfileNumber(profile.weightKg) else "",
                ProfileField.SETUP_MASS to
                    if (confirmed) formatProfileNumber(profile.setupMassKg) else "",
                ProfileField.WEEKLY_HOURS to profile.weeklyHours?.let(::formatProfileNumber).orEmpty(),
                ProfileField.HR_MAX to profile.hrMaxOverride?.let(::formatProfileNumber).orEmpty(),
                ProfileField.LTHR to profile.lthrOverride?.let(::formatProfileNumber).orEmpty(),
                ProfileField.RESTING_HR to
                    profile.restingHrOverride?.let(::formatProfileNumber).orEmpty(),
                ProfileField.FTP to profile.eftpOverrideW?.let(::formatProfileNumber).orEmpty(),
            ),
            sex = profile.sex,
        )
    }
}

/**
 * Prueft ein einzelnes Feld; `null` = in Ordnung. Dieselben Grenzen wie
 * frueher `_saveProfile()` im Original.
 *
 * @param confirmed Ob schon ein Profil gespeichert ist. Nur dann ist ein
 *   **leeres** Pflichtfeld ein Fehler — vorher ist leer schlicht „noch nicht
 *   eingetragen".
 */
internal fun profileFieldError(field: ProfileField, text: String, confirmed: Boolean): UiText? {
    val empty = text.isBlank()
    return when (field) {
        ProfileField.AGE -> when {
            empty -> if (confirmed) UiText.Res(R.string.more_profile_age_missing_error) else null
            text.trim().toIntOrNull()?.let { it in 10..100 } != true ->
                UiText.Res(R.string.more_profile_age_range_error)
            else -> null
        }
        ProfileField.WEIGHT -> when {
            empty -> if (confirmed) UiText.Res(R.string.more_profile_weight_missing_error) else null
            parseProfileNumber(text)?.let { it in 30.0..250.0 } != true ->
                UiText.Res(R.string.more_profile_weight_range_error)
            else -> null
        }
        ProfileField.SETUP_MASS -> when {
            empty -> null
            parseProfileNumber(text)?.let { it in 0.0..60.0 } != true ->
                UiText.Res(R.string.more_profile_setup_mass_range_error)
            else -> null
        }
        ProfileField.WEEKLY_HOURS -> when {
            empty -> null
            parseProfileNumber(text)?.let { it > 0 && it <= 40 } != true ->
                UiText.Res(R.string.more_profile_weekly_hours_range_error)
            else -> null
        }
        ProfileField.HR_MAX, ProfileField.LTHR, ProfileField.RESTING_HR -> when {
            empty -> null
            parseProfileNumber(text) == null -> UiText.Res(R.string.more_profile_number_error)
            else -> null
        }
        // Dieselben Grenzen wie im Rechenkern (`minEftpW`/`maxEftpW`): Ein
        // Wert ausserhalb wuerde dort ohnehin geklemmt — dann sagen wir es
        // lieber hier.
        ProfileField.FTP -> when {
            empty -> null
            parseProfileNumber(text)?.let { it in minEftpW..maxEftpW } != true -> UiText.Res(
                R.string.more_profile_ftp_range_error,
                listOf(formatProfileNumber(minEftpW), formatProfileNumber(maxEftpW)),
            )
            else -> null
        }
    }
}

/**
 * Das Profil, das aus dem Formular gespeichert wird — oder `null`, wenn
 * (noch) nichts zu speichern ist.
 *
 * Gueltige Felder gehen ein; ein ungueltiges Feld behaelt den Wert aus
 * [current]. Ein leeres optionales Feld heisst „keine Angabe" (bzw. beim
 * Rad-Gewicht: der Standardwert). Solange kein Profil bestaetigt ist, braucht
 * es gueltiges Alter **und** Gewicht — sonst wuerde der Standardkoerper aus
 * [current] als eigene Angabe bestaetigt.
 */
internal fun buildProfileFromForm(
    current: TrainingProfile,
    confirmed: Boolean,
    form: ProfileForm,
): TrainingProfile? {
    fun valid(field: ProfileField) = profileFieldError(field, form.text(field), confirmed) == null
    fun number(field: ProfileField) = parseProfileNumber(form.text(field))

    val ageValid = valid(ProfileField.AGE) && form.text(ProfileField.AGE).isNotBlank()
    val weightValid = valid(ProfileField.WEIGHT) && form.text(ProfileField.WEIGHT).isNotBlank()
    if (!confirmed && !(ageValid && weightValid)) return null

    fun optional(field: ProfileField, fallback: Double?): Double? =
        if (valid(field)) number(field) else fallback

    return current.copy(
        ageYears = if (ageValid) form.text(ProfileField.AGE).trim().toInt() else current.ageYears,
        sex = form.sex,
        weightKg = if (weightValid) number(ProfileField.WEIGHT)!! else current.weightKg,
        setupMassKg = if (valid(ProfileField.SETUP_MASS)) {
            number(ProfileField.SETUP_MASS) ?: defaultSetupMassKg
        } else {
            current.setupMassKg
        },
        weeklyHours = optional(ProfileField.WEEKLY_HOURS, current.weeklyHours),
        hrMaxOverride = optional(ProfileField.HR_MAX, current.hrMaxOverride),
        lthrOverride = optional(ProfileField.LTHR, current.lthrOverride),
        restingHrOverride = optional(ProfileField.RESTING_HR, current.restingHrOverride),
        eftpOverrideW = optional(ProfileField.FTP, current.eftpOverrideW),
    )
}

/** Entspricht Darts `_formatNumber`: ganze Werte ohne Nachkommastellen. */
internal fun formatProfileNumber(value: Double): String =
    if (value == round(value)) value.toLong().toString() else value.toString()

/** Entspricht Darts `_parseNumber`: Komma als Dezimaltrennzeichen erlaubt. */
internal fun parseProfileNumber(raw: String): Double? {
    val trimmed = raw.trim().replace(',', '.')
    if (trimmed.isEmpty()) return null
    return trimmed.toDoubleOrNull()
}
