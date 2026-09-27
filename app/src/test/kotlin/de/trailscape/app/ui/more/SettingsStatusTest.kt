package de.trailscape.app.ui.more

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.HealthAvailability
import de.trailscape.core.HealthConnection
import de.trailscape.core.ReminderSettings
import de.trailscape.core.Sex
import de.trailscape.core.SyncConfig
import de.trailscape.core.SyncResult
import de.trailscape.core.TrainingProfile
import de.trailscape.core.defaultSetupMassKg
import de.trailscape.core.i18n.AppLanguage.DE
import de.trailscape.core.i18n.AppLanguage.EN
import de.trailscape.core.i18n.formatDateFull
import de.trailscape.core.i18n.formatDateShort
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Statuszeilen der Einstellungsliste, die zusammengesetzten Meldungen der
 * Einstellungsseiten und die Sofort-Speicherung des Profils — als reiner
 * JVM-Test (docs/i18n.md, D).
 *
 * Die Formulierungslogik liefert [UiText]; hier wird verglichen, welche
 * Ressource mit welchen Argumenten herauskommt. Zahlen- und Datumsformat
 * stehen schon in den Argumenten fest und sind deshalb je Sprache pruefbar.
 * Die fertigen Saetze gegen die echten Ressourcen prueft
 * [SettingsStatusResourcesTest].
 */
class SettingsStatusTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun res(id: Int, vararg args: Any) = UiText.Res(id, args.toList())

    @Test
    fun profilStatusNenntAlterGewichtUndRad() {
        val profile = TrainingProfile(ageYears = 40, weightKg = 75.0, setupMassKg = 11.0)
        assertEquals(res(R.string.more_status_profile, 40, "75", "11"), profileStatusText(profile, true, DE))
        assertEquals(res(R.string.more_status_profile_missing), profileStatusText(profile, false, DE))
        // Das Dezimalzeichen folgt der Sprache.
        assertEquals(
            res(R.string.more_status_profile, 40, "72,5", "11"),
            profileStatusText(profile.copy(weightKg = 72.5), true, DE),
        )
        assertEquals(
            res(R.string.more_status_profile, 40, "72.5", "11"),
            profileStatusText(profile.copy(weightKg = 72.5), true, EN),
        )
    }

    @Test
    fun gesundheitsStatusZeigtLetztenImport() {
        val ready = HealthConnection(HealthAvailability.VERFUEGBAR, hasPermissions = true)
        assertEquals(
            res(R.string.more_status_health_connected_last, "6:12"),
            healthStatusText(ready, LocalDateTime.of(today, LocalTime.of(6, 12)), DE, today),
        )
        assertEquals(
            res(R.string.more_status_health_connected_last, res(R.string.more_status_yesterday)),
            healthStatusText(ready, LocalDateTime.of(today.minusDays(1), LocalTime.NOON), DE, today),
        )
        assertEquals(
            res(R.string.more_status_health_connected_last, "12.9."),
            healthStatusText(ready, LocalDateTime.of(2026, 9, 12, 8, 0), DE, today),
        )
        assertEquals(
            res(R.string.more_status_health_connected_last, formatDateShort(LocalDate.of(2026, 9, 12), EN)),
            healthStatusText(ready, LocalDateTime.of(2026, 9, 12, 8, 0), EN, today),
        )
        assertEquals(res(R.string.more_status_health_connected), healthStatusText(ready, null, DE, today))
        assertEquals(res(R.string.more_status_checking), healthStatusText(null, null, DE, today))
        assertEquals(
            res(R.string.more_status_health_not_connected),
            healthStatusText(HealthConnection(HealthAvailability.VERFUEGBAR, false), null, DE, today),
        )
        assertEquals(
            res(R.string.more_status_health_not_installed),
            healthStatusText(HealthConnection(HealthAvailability.NICHT_INSTALLIERT, false), null, DE, today),
        )
        assertEquals(
            res(R.string.more_status_health_update_needed),
            healthStatusText(HealthConnection(HealthAvailability.UPDATE_NOETIG, false), null, DE, today),
        )
    }

    @Test
    fun erinnerungsStatus() {
        assertEquals(listOf(res(R.string.more_status_reminder_off)), reminderStatusText(ReminderSettings()))
        assertEquals(
            listOf(
                res(R.string.more_status_reminder_daily, "7:00"),
                res(R.string.more_status_reminder_weekly),
                res(R.string.more_status_reminder_nudge),
            ),
            reminderStatusText(
                ReminderSettings(dailySessionEnabled = true, weeklyReviewEnabled = true, nudgeEnabled = true),
            ),
        )
    }

    @Test
    fun aufzeichnungsStatus() {
        assertEquals(
            res(R.string.more_status_recording, res(R.string.more_status_on), res(R.string.more_status_off)),
            recordingStatusText(autoPause = true, voice = false),
        )
    }

    @Test
    fun offlineStatus() {
        assertEquals(listOf(res(R.string.more_status_checking)), offlineStatusText(null, null, 0L, DE))
        assertEquals(listOf(res(R.string.more_status_offline_empty)), offlineStatusText(0, 0, 0L, DE))
        assertEquals(
            listOf(UiText.Plural(R.plurals.more_status_offline_regions_count, 2), UiText.Plain("184,0 MB")),
            offlineStatusText(2, 0, 184L * 1024 * 1024, DE),
        )
        assertEquals(
            listOf(UiText.Plural(R.plurals.more_status_offline_regions_count, 2), UiText.Plain("184.0 MB")),
            offlineStatusText(2, 0, 184L * 1024 * 1024, EN),
        )
        assertEquals(
            listOf(
                UiText.Plural(R.plurals.more_status_offline_regions_count, 1),
                UiText.Plural(R.plurals.more_status_offline_tiles_count, 3),
            ),
            offlineStatusText(1, 3, 0L, EN),
        )
    }

    @Test
    fun backupStatus() {
        assertNull(backupStatusText(null, DE, today))
        assertEquals(
            res(R.string.more_status_backup_last, res(R.string.more_status_today)),
            backupStatusText(LocalDateTime.of(today, LocalTime.NOON), EN, today),
        )
        assertEquals(
            res(R.string.more_status_backup_last, "12.9."),
            backupStatusText(LocalDateTime.of(2026, 9, 12, 20, 0), DE, today),
        )
        assertEquals(
            res(R.string.more_status_backup_last, "3.1.2025"),
            backupStatusText(LocalDateTime.of(2025, 1, 3, 20, 0), DE, today),
        )
        // Die englischen Muster kommen aus `:core` (DateFormats, dort getestet).
        assertEquals(
            res(R.string.more_status_backup_last, formatDateShort(LocalDate.of(2026, 9, 12), EN)),
            backupStatusText(LocalDateTime.of(2026, 9, 12, 20, 0), EN, today),
        )
        assertEquals(
            res(R.string.more_status_backup_last, formatDateFull(LocalDate.of(2025, 1, 3), EN)),
            backupStatusText(LocalDateTime.of(2025, 1, 3, 20, 0), EN, today),
        )
        assertEquals(res(R.string.more_status_backup_never), BACKUP_NEVER_TEXT)
    }

    @Test
    fun syncStatus() {
        assertEquals(res(R.string.more_status_sync_off), syncStatusText(null))
        assertEquals(
            res(R.string.more_status_sync_configured_host, "sync.example.org"),
            syncStatusText(SyncConfig(url = "https://sync.example.org/api", token = "x")),
        )
        assertEquals(
            res(R.string.more_status_sync_configured),
            syncStatusText(SyncConfig(url = "kein host", token = "x")),
        )
    }

    @Test
    fun syncErgebnisNenntNurWasPassiertIst() {
        val join = R.string.more_sync_result_join
        assertEquals(
            res(
                join,
                res(join, res(R.string.more_sync_result_pushed, 3), res(R.string.more_sync_result_pulled, 2)),
                UiText.Plural(R.plurals.more_sync_result_total_count, 42),
            ),
            syncResultText(SyncResult(pushed = 3, pulled = 2, total = 42)),
        )
        assertEquals(
            res(
                join,
                res(
                    join,
                    res(join, res(R.string.more_sync_result_pushed, 0), res(R.string.more_sync_result_pulled_updated, 2, 1)),
                    res(R.string.more_sync_result_deleted, 3),
                ),
                UiText.Plural(R.plurals.more_sync_result_total_count, 1),
            ),
            syncResultText(SyncResult(pushed = 0, pulled = 2, total = 1, updated = 1, deletedLocal = 1, deletedRemote = 2)),
        )
    }

    @Test
    fun backupImportMeldung() {
        assertEquals(
            UiText.Plural(R.plurals.more_backup_imported_count, 3),
            backupImportMessage(3, 0, profileRestored = false),
        )
        assertEquals(
            res(
                R.string.more_backup_imported_with_profile,
                UiText.Plural(R.plurals.more_backup_imported_skipped_count, 1, listOf(1, 2)),
            ),
            backupImportMessage(1, 2, profileRestored = true),
        )
    }

    @Test
    fun profilFehlerNenntDenBereich() {
        assertEquals(
            res(R.string.more_profile_ftp_range_error, "100", "400"),
            profileFieldError(ProfileField.FTP, "50", confirmed = true),
        )
        assertNull(profileFieldError(ProfileField.FTP, "250", confirmed = true))
    }

    @Test
    fun unbestaetigtesProfilWirdErstMitAlterUndGewichtGespeichert() {
        val current = TrainingProfile(ageYears = 40)
        val onlyAge = ProfileForm().with(ProfileField.AGE, "35")
        assertNull(buildProfileFromForm(current, confirmed = false, form = onlyAge))

        val both = onlyAge.with(ProfileField.WEIGHT, "68,5").copy(sex = Sex.WEIBLICH)
        val saved = assertNotNull(buildProfileFromForm(current, confirmed = false, form = both))
        assertEquals(35, saved.ageYears)
        assertEquals(68.5, saved.weightKg)
        assertEquals(Sex.WEIBLICH, saved.sex)
        assertEquals(defaultSetupMassKg, saved.setupMassKg)
    }

    @Test
    fun ungueltigesFeldBehaeltGespeichertenWertUndHaeltAndereNichtAuf() {
        val current = TrainingProfile(ageYears = 40, weightKg = 75.0, eftpOverrideW = 250.0)
        val form = ProfileForm.of(current, confirmed = true)
            .with(ProfileField.AGE, "7")
            .with(ProfileField.FTP, "280")
            .with(ProfileField.HR_MAX, "")
        val saved = assertNotNull(buildProfileFromForm(current, confirmed = true, form = form))
        assertEquals(40, saved.ageYears)
        assertEquals(280.0, saved.eftpOverrideW)
        assertNull(saved.hrMaxOverride)
        assertNotNull(profileFieldError(ProfileField.AGE, "7", confirmed = true))
        assertNotNull(profileFieldError(ProfileField.AGE, "", confirmed = true))
        assertNull(profileFieldError(ProfileField.AGE, "", confirmed = false))
    }
}
