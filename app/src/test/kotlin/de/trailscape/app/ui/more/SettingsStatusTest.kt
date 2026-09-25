package de.trailscape.app.ui.more

import de.trailscape.core.HealthAvailability
import de.trailscape.core.HealthConnection
import de.trailscape.core.ReminderSettings
import de.trailscape.core.Sex
import de.trailscape.core.SyncConfig
import de.trailscape.core.TrainingProfile
import de.trailscape.core.defaultSetupMassKg
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Statuszeilen der Einstellungsliste und die Sofort-Speicherung des Profils. */
class SettingsStatusTest {

    private val today = LocalDate.of(2026, 9, 25)

    @Test
    fun profilStatusNenntAlterGewichtUndRad() {
        val profile = TrainingProfile(ageYears = 40, weightKg = 75.0, setupMassKg = 11.0)
        assertEquals("40 J · 75 kg · Rad 11 kg", profileStatusText(profile, confirmed = true))
        assertEquals("Noch nicht eingetragen", profileStatusText(profile, confirmed = false))
        assertEquals(
            "40 J · 72,5 kg · Rad 11 kg",
            profileStatusText(profile.copy(weightKg = 72.5), confirmed = true),
        )
    }

    @Test
    fun gesundheitsStatusZeigtLetztenImport() {
        val ready = HealthConnection(HealthAvailability.VERFUEGBAR, hasPermissions = true)
        assertEquals(
            "Verbunden · zuletzt 6:12",
            healthStatusText(ready, LocalDateTime.of(today, LocalTime.of(6, 12)), today),
        )
        assertEquals(
            "Verbunden · zuletzt gestern",
            healthStatusText(ready, LocalDateTime.of(today.minusDays(1), LocalTime.NOON), today),
        )
        assertEquals("Verbunden", healthStatusText(ready, null, today))
        assertEquals(
            "Nicht verbunden",
            healthStatusText(HealthConnection(HealthAvailability.VERFUEGBAR, false), null, today),
        )
        assertEquals(
            "Nicht installiert",
            healthStatusText(HealthConnection(HealthAvailability.NICHT_INSTALLIERT, false), null, today),
        )
    }

    @Test
    fun erinnerungsStatus() {
        assertEquals("Aus", reminderStatusText(ReminderSettings()))
        assertEquals(
            "Tagesplan um 7:00",
            reminderStatusText(ReminderSettings(dailySessionEnabled = true)),
        )
        assertEquals(
            "Wochenrückblick · Anstupser",
            reminderStatusText(ReminderSettings(weeklyReviewEnabled = true, nudgeEnabled = true)),
        )
    }

    @Test
    fun aufzeichnungsStatus() {
        assertEquals("Auto-Pause an · Ansagen aus", recordingStatusText(autoPause = true, voice = false))
    }

    @Test
    fun offlineStatus() {
        assertEquals("Nichts gespeichert", offlineStatusText(0, 0, 0L))
        assertEquals("2 Regionen · 184,0 MB", offlineStatusText(2, 0, 184L * 1024 * 1024))
        assertEquals("1 Region · 1 Routing-Kachel", offlineStatusText(1, 1, 0L))
    }

    @Test
    fun backupStatus() {
        assertNull(backupStatusText(null, today))
        assertEquals(
            "Zuletzt: 12.9.",
            backupStatusText(LocalDateTime.of(2026, 9, 12, 20, 0), today),
        )
        assertEquals(
            "Zuletzt: 3.1.2025",
            backupStatusText(LocalDateTime.of(2025, 1, 3, 20, 0), today),
        )
    }

    @Test
    fun syncStatus() {
        assertEquals("Aus", syncStatusText(null))
        assertEquals(
            "Eingerichtet · sync.example.org",
            syncStatusText(SyncConfig(url = "https://sync.example.org/api", token = "x")),
        )
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
