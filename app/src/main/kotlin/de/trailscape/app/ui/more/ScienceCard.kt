package de.trailscape.app.ui.more

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.theme.OneUiMotion

/** Wie gut eine Aussage belegt ist — steht als kleines Label neben der Quelle. */
private enum class ScienceEvidence(@StringRes val labelRes: Int) {
    EVIDENCE_BASED(R.string.science_evidence_based),
    COMMON_PRACTICE(R.string.science_evidence_common_practice),
    RULE_OF_THUMB(R.string.science_evidence_rule_of_thumb),
}

/** Ein Eintrag der Karte: Titel, Erklaerung, Quelle und Einstufung. */
private data class ScienceEntry(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @StringRes val sourceRes: Int,
    val evidence: ScienceEvidence,
)

private val scienceEntries: List<ScienceEntry> = listOf(
    ScienceEntry(R.string.science_hr_load_title, R.string.science_hr_load_body, R.string.science_hr_load_source, ScienceEvidence.COMMON_PRACTICE),
    ScienceEntry(R.string.science_power_load_title, R.string.science_power_load_body, R.string.science_power_load_source, ScienceEvidence.COMMON_PRACTICE),
    ScienceEntry(R.string.science_rpe_title, R.string.science_rpe_body, R.string.science_rpe_source, ScienceEvidence.EVIDENCE_BASED),
    ScienceEntry(R.string.science_fitness_fatigue_title, R.string.science_fitness_fatigue_body, R.string.science_fitness_fatigue_source, ScienceEvidence.COMMON_PRACTICE),
    ScienceEntry(R.string.science_load_spike_title, R.string.science_load_spike_body, R.string.science_load_spike_source, ScienceEvidence.COMMON_PRACTICE),
    ScienceEntry(R.string.science_hrv_baseline_title, R.string.science_hrv_baseline_body, R.string.science_hrv_baseline_source, ScienceEvidence.EVIDENCE_BASED),
    ScienceEntry(R.string.science_hrv_guided_title, R.string.science_hrv_guided_body, R.string.science_hrv_guided_source, ScienceEvidence.COMMON_PRACTICE),
    ScienceEntry(R.string.science_polarized_title, R.string.science_polarized_body, R.string.science_polarized_source, ScienceEvidence.EVIDENCE_BASED),
    ScienceEntry(R.string.science_intervals_title, R.string.science_intervals_body, R.string.science_intervals_source, ScienceEvidence.COMMON_PRACTICE),
    ScienceEntry(R.string.science_gradual_build_title, R.string.science_gradual_build_body, R.string.science_gradual_build_source, ScienceEvidence.RULE_OF_THUMB),
    ScienceEntry(R.string.science_recovery_week_title, R.string.science_recovery_week_body, R.string.science_recovery_week_source, ScienceEvidence.COMMON_PRACTICE),
    ScienceEntry(R.string.science_taper_title, R.string.science_taper_body, R.string.science_taper_source, ScienceEvidence.EVIDENCE_BASED),
)

/**
 * „Wissenschaftliche Grundlage" — einklappbarer Abschnitt auf der Seite
 * „Über Trailscape" (siehe `MoreScreen.kt`). Zeigt, worauf die Berechnungen
 * beruhen und wie gut das belegt ist. Startet eingeklappt; der Zustand
 * ueberdauert Drehungen des Geraets.
 */
@Composable
fun ScienceCardContent() {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.science_card_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                stringResource(
                    if (expanded) R.string.science_card_hide_action else R.string.science_card_show_action,
                ),
            )
        }
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(OneUiMotion.standard()) + fadeIn(OneUiMotion.standard()),
        exit = shrinkVertically(OneUiMotion.standard()) + fadeOut(OneUiMotion.standard()),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.science_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                scienceEntries.forEach { ScienceEntryRow(it) }
            }
            Spacer(modifier = Modifier.height(14.dp))
            SettingsHint(stringResource(R.string.science_footer_hint))
        }
    }
}

@Composable
private fun ScienceEntryRow(entry: ScienceEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(entry.titleRes),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(entry.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.science_source_format, stringResource(entry.sourceRes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(entry.evidence.labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
