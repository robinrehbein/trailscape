package de.trailscape.app.ui.more

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.localizedFor
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.HealthAvailability
import de.trailscape.core.HealthConnection
import de.trailscape.core.ReminderSettings
import de.trailscape.core.SyncConfig
import de.trailscape.core.SyncResult
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.AppLanguage.DE
import de.trailscape.core.i18n.AppLanguage.EN
import de.trailscape.core.i18n.formatDateFull
import de.trailscape.core.i18n.formatDateShort
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die fertigen Saetze der Statuszeilen und Meldungen, gegen die echten
 * Ressourcen aufgeloest (Robolectric) — die deutschen Erwartungen sind
 * dieselben wie vor dem Umzug in `strings_more.xml`, die englischen kommen
 * dazu.
 *
 * Bewusst getrennt von [SettingsStatusTest]: Die Formulierungslogik selbst
 * (welche Ressource, welche Argumente) und die Sofort-Speicherung des
 * Profils pruefen dort reine JVM-Tests ohne Android-Laufzeit (docs/i18n.md,
 * D). Hier steht nur, was wirklich die Ressourcen braucht: dass Platzhalter,
 * Plurale und verschachtelte Teile in beiden Sprachen einen Satz ergeben.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class SettingsStatusResourcesTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun UiText.de(): String = resolve(context(DE))
    private fun UiText.en(): String = resolve(context(EN))
    private fun List<UiText>.de(): String = joinToString(" · ") { it.de() }
    private fun List<UiText>.en(): String = joinToString(" · ") { it.en() }

    private fun context(language: AppLanguage): Context =
        ApplicationProvider.getApplicationContext<Application>().localizedFor(language)

    @Test
    fun profilStatusNenntAlterGewichtUndRad() {
        val profile = TrainingProfile(ageYears = 40, weightKg = 75.0, setupMassKg = 11.0)
        assertEquals("40 J · 75 kg · Rad 11 kg", profileStatusText(profile, confirmed = true, DE).de())
        assertEquals("Noch nicht eingetragen", profileStatusText(profile, confirmed = false, DE).de())
        assertEquals(
            "40 J · 72,5 kg · Rad 11 kg",
            profileStatusText(profile.copy(weightKg = 72.5), confirmed = true, DE).de(),
        )
    }

    @Test
    fun profilStatusEnglisch() {
        val profile = TrainingProfile(ageYears = 40, weightKg = 72.5, setupMassKg = 11.0)
        assertEquals("Age 40 · 72.5 kg · bike 11 kg", profileStatusText(profile, confirmed = true, EN).en())
        assertEquals("Not entered yet", profileStatusText(profile, confirmed = false, EN).en())
    }

    @Test
    fun gesundheitsStatusZeigtLetztenImport() {
        val ready = HealthConnection(HealthAvailability.VERFUEGBAR, hasPermissions = true)
        assertEquals(
            "Verbunden · zuletzt 6:12",
            healthStatusText(ready, LocalDateTime.of(today, LocalTime.of(6, 12)), DE, today).de(),
        )
        assertEquals(
            "Verbunden · zuletzt gestern",
            healthStatusText(ready, LocalDateTime.of(today.minusDays(1), LocalTime.NOON), DE, today).de(),
        )
        assertEquals("Verbunden", healthStatusText(ready, null, DE, today).de())
        assertEquals(
            "Nicht verbunden",
            healthStatusText(HealthConnection(HealthAvailability.VERFUEGBAR, false), null, DE, today).de(),
        )
        assertEquals(
            "Nicht installiert",
            healthStatusText(HealthConnection(HealthAvailability.NICHT_INSTALLIERT, false), null, DE, today).de(),
        )
    }

    @Test
    fun gesundheitsStatusEnglisch() {
        val ready = HealthConnection(HealthAvailability.VERFUEGBAR, hasPermissions = true)
        assertEquals(
            "Connected · updated 6:12",
            healthStatusText(ready, LocalDateTime.of(today, LocalTime.of(6, 12)), EN, today).en(),
        )
        assertEquals(
            "Connected · updated yesterday",
            healthStatusText(ready, LocalDateTime.of(today.minusDays(1), LocalTime.NOON), EN, today).en(),
        )
        assertEquals(
            "Connected · updated ${formatDateShort(LocalDate.of(2026, 9, 12), EN)}",
            healthStatusText(ready, LocalDateTime.of(2026, 9, 12, 8, 0), EN, today).en(),
        )
        assertEquals("Checking…", healthStatusText(null, null, EN, today).en())
        assertEquals(
            "Update needed",
            healthStatusText(HealthConnection(HealthAvailability.UPDATE_NOETIG, false), null, EN, today).en(),
        )
    }

    @Test
    fun erinnerungsStatus() {
        assertEquals("Aus", reminderStatusText(ReminderSettings()).de())
        assertEquals(
            "Tagesplan um 7:00",
            reminderStatusText(ReminderSettings(dailySessionEnabled = true)).de(),
        )
        assertEquals(
            "Wochenrückblick · Anstupser",
            reminderStatusText(ReminderSettings(weeklyReviewEnabled = true, nudgeEnabled = true)).de(),
        )
    }

    @Test
    fun erinnerungsStatusEnglisch() {
        assertEquals("Off", reminderStatusText(ReminderSettings()).en())
        assertEquals(
            "Daily plan at 7:00 · Weekly review · Nudges",
            reminderStatusText(
                ReminderSettings(dailySessionEnabled = true, weeklyReviewEnabled = true, nudgeEnabled = true),
            ).en(),
        )
    }

    @Test
    fun aufzeichnungsStatus() {
        assertEquals("Auto-Pause an · Ansagen aus", recordingStatusText(autoPause = true, voice = false).de())
        assertEquals(
            "Auto-pause on · voice prompts off",
            recordingStatusText(autoPause = true, voice = false).en(),
        )
    }

    @Test
    fun offlineStatus() {
        assertEquals("Nichts gespeichert", offlineStatusText(0, 0, 0L, DE).de())
        assertEquals("2 Regionen · 184,0 MB", offlineStatusText(2, 0, 184L * 1024 * 1024, DE).de())
        assertEquals("1 Region · 1 Routing-Kachel", offlineStatusText(1, 1, 0L, DE).de())
        assertEquals("Wird geprüft …", offlineStatusText(null, null, 0L, DE).de())
    }

    @Test
    fun offlineStatusEnglisch() {
        assertEquals("Nothing saved", offlineStatusText(0, 0, 0L, EN).en())
        assertEquals("2 regions · 184.0 MB", offlineStatusText(2, 0, 184L * 1024 * 1024, EN).en())
        assertEquals("1 region · 3 routing tiles", offlineStatusText(1, 3, 0L, EN).en())
    }

    @Test
    fun backupStatus() {
        assertNull(backupStatusText(null, DE, today))
        assertEquals(
            "Zuletzt: 12.9.",
            backupStatusText(LocalDateTime.of(2026, 9, 12, 20, 0), DE, today)?.de(),
        )
        assertEquals(
            "Zuletzt: 3.1.2025",
            backupStatusText(LocalDateTime.of(2025, 1, 3, 20, 0), DE, today)?.de(),
        )
        assertEquals("Noch nie gesichert", BACKUP_NEVER_TEXT.de())
    }

    @Test
    fun backupStatusEnglisch() {
        assertEquals("Last: today", backupStatusText(LocalDateTime.of(today, LocalTime.NOON), EN, today)?.en())
        // Die englischen Muster kommen aus `:core` (DateFormats, dort getestet).
        assertEquals(
            "Last: ${formatDateShort(LocalDate.of(2026, 9, 12), EN)}",
            backupStatusText(LocalDateTime.of(2026, 9, 12, 20, 0), EN, today)?.en(),
        )
        assertEquals(
            "Last: ${formatDateFull(LocalDate.of(2025, 1, 3), EN)}",
            backupStatusText(LocalDateTime.of(2025, 1, 3, 20, 0), EN, today)?.en(),
        )
        assertEquals("Never backed up", BACKUP_NEVER_TEXT.en())
    }

    @Test
    fun syncStatus() {
        assertEquals("Aus", syncStatusText(null).de())
        assertEquals(
            "Eingerichtet · sync.example.org",
            syncStatusText(SyncConfig(url = "https://sync.example.org/api", token = "x")).de(),
        )
        assertEquals(
            "Set up · sync.example.org",
            syncStatusText(SyncConfig(url = "https://sync.example.org/api", token = "x")).en(),
        )
    }

    @Test
    fun syncErgebnisNenntNurWasPassiertIst() {
        assertEquals(
            "3 hochgeladen, 2 geladen, 42 Touren",
            syncResultText(SyncResult(pushed = 3, pulled = 2, total = 42)).de(),
        )
        assertEquals(
            "0 hochgeladen, 2 geladen (davon 1 aktualisiert), 3 gelöscht, 1 Tour",
            syncResultText(SyncResult(pushed = 0, pulled = 2, total = 1, updated = 1, deletedLocal = 1, deletedRemote = 2))
                .de(),
        )
        assertEquals(
            "3 uploaded, 2 downloaded (1 of them updated), 1 deleted, 42 rides",
            syncResultText(SyncResult(pushed = 3, pulled = 2, total = 42, updated = 1, deletedRemote = 1)).en(),
        )
        assertEquals("0 uploaded, 0 downloaded, 1 ride", syncResultText(SyncResult(0, 0, 1)).en())
    }

    @Test
    fun backupImportMeldung() {
        assertEquals("3 Touren importiert", backupImportMessage(3, 0, profileRestored = false).de())
        assertEquals(
            "1 Tour importiert, 2 übersprungen · Profil übernommen",
            backupImportMessage(1, 2, profileRestored = true).de(),
        )
        assertEquals("1 ride imported", backupImportMessage(1, 0, profileRestored = false).en())
        assertEquals(
            "3 rides imported, 2 skipped · profile restored",
            backupImportMessage(3, 2, profileRestored = true).en(),
        )
    }

    @Test
    fun profilFehlerInBeidenSprachen() {
        val error = assertNotNull(profileFieldError(ProfileField.FTP, "50", confirmed = true))
        assertEquals("Zwischen 100 und 400 Watt.", error.de())
        assertEquals("Between 100 and 400 watts.", error.en())
    }
}
