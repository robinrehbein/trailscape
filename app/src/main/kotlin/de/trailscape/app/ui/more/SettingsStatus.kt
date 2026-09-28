package de.trailscape.app.ui.more

import androidx.compose.runtime.Composable
import de.trailscape.app.R
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.core.HealthAvailability
import de.trailscape.core.HealthConnection
import de.trailscape.core.ReminderSettings
import de.trailscape.core.SyncConfig
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDateFull
import de.trailscape.core.i18n.formatDateShort
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Die Statuszeilen der Einstellungsliste (siehe `SettingsNavRow` und
 * `MoreScreen.kt`) — als reine Funktionen, damit sie ohne Compose pruefbar
 * sind (`SettingsStatusTest`).
 *
 * Jede Zeile ist **kurz** (eine Zeile auf einem schmalen Telefon) und sagt
 * den Zustand, nicht die Funktion: „Auto-Pause an", nicht „Auto-Pause und
 * Ansagen einstellen".
 *
 * Die Funktionen liefern [UiText] statt fertiger Saetze (siehe
 * docs/i18n.md, D): So bleibt die Formulierung ohne Compose pruefbar, und
 * erst die Anzeige loest sie in der Sprache der Oberflaeche auf. Zeilen aus
 * mehreren gleichrangigen Teilen („Wochenrückblick · Anstupser") kommen als
 * Liste und werden erst in [joinedStatus] mit „ · " verbunden — ein Satz
 * aus uebersetzten Bruchstuecken waere es nicht, jeder Teil steht fuer sich.
 * Wo Zahlen oder Datum drinstehen, nimmt die Funktion die [AppLanguage] mit:
 * Deren Format steht schon fest, bevor die Ressource aufgeloest wird.
 */

/** „40 J · 75 kg · Rad 11 kg" — oder der Hinweis, dass noch nichts eingetragen ist. */
internal fun profileStatusText(profile: TrainingProfile, confirmed: Boolean, language: AppLanguage): UiText =
    if (!confirmed) {
        UiText.Res(R.string.more_status_profile_missing)
    } else {
        UiText.Res(
            R.string.more_status_profile,
            listOf(
                profile.ageYears,
                profileNumber(profile.weightKg, language),
                profileNumber(profile.setupMassKg, language),
            ),
        )
    }

/**
 * „Verbunden · zuletzt 6:12" / „Nicht verbunden".
 *
 * @param lastImportAt Zeitpunkt des letzten Imports aus Health Connect, oder
 *   `null`, wenn es noch keinen gab.
 */
internal fun healthStatusText(
    connection: HealthConnection?,
    lastImportAt: LocalDateTime?,
    language: AppLanguage,
    today: LocalDate = LocalDate.now(),
): UiText = when {
    connection == null -> UiText.Res(R.string.more_status_checking)
    connection.isReady -> if (lastImportAt == null) {
        UiText.Res(R.string.more_status_health_connected)
    } else {
        UiText.Res(
            R.string.more_status_health_connected_last,
            listOf(relativeMoment(lastImportAt, today, language)),
        )
    }
    connection.availability == HealthAvailability.NICHT_INSTALLIERT ->
        UiText.Res(R.string.more_status_health_not_installed)
    connection.availability == HealthAvailability.UPDATE_NOETIG ->
        UiText.Res(R.string.more_status_health_update_needed)
    connection.availability == HealthAvailability.NICHT_UNTERSTUETZT ->
        UiText.Res(R.string.more_status_health_unavailable)
    else -> UiText.Res(R.string.more_status_health_not_connected)
}

/** „Auto-Pause an · Ansagen an". */
internal fun recordingStatusText(autoPause: Boolean, voice: Boolean): UiText =
    UiText.Res(R.string.more_status_recording, listOf(onOff(autoPause), onOff(voice)))

/** „Tagesplan um 7:00" / „Wochenrückblick · Anstupser" / „Aus" — Teile fuer [joinedStatus]. */
internal fun reminderStatusText(settings: ReminderSettings): List<UiText> {
    val parts = buildList {
        if (settings.dailySessionEnabled) {
            add(UiText.Res(R.string.more_status_reminder_daily, listOf(shortTime(settings.dailySessionTime))))
        }
        if (settings.weeklyReviewEnabled) add(UiText.Res(R.string.more_status_reminder_weekly))
        if (settings.nudgeEnabled) add(UiText.Res(R.string.more_status_reminder_nudge))
    }
    return parts.ifEmpty { listOf(UiText.Res(R.string.more_status_reminder_off)) }
}

/**
 * „2 Regionen · 184 MB" — Kartenbild und Routingdaten zusammen, denn beide
 * liegen auf derselben Seite und belegen denselben Speicher. Teile fuer
 * [joinedStatus].
 *
 * @param mapRegions Anzahl der Offline-Kartenausschnitte, `null` = unbekannt.
 * @param routingTiles Anzahl der Routing-Kacheln, `null` = unbekannt.
 */
internal fun offlineStatusText(
    mapRegions: Int?,
    routingTiles: Int?,
    totalBytes: Long,
    language: AppLanguage,
): List<UiText> {
    if (mapRegions == null && routingTiles == null) return listOf(UiText.Res(R.string.more_status_checking))
    val maps = mapRegions ?: 0
    val tiles = routingTiles ?: 0
    if (maps == 0 && tiles == 0) return listOf(UiText.Res(R.string.more_status_offline_empty))
    return buildList {
        if (maps > 0) add(UiText.Plural(R.plurals.more_status_offline_regions_count, maps))
        if (tiles > 0) add(UiText.Plural(R.plurals.more_status_offline_tiles_count, tiles))
        AppFormats(language).bytes(totalBytes)?.let { add(UiText.Plain(it)) }
    }
}

/** „Zuletzt: 12.9." — oder `null`, wenn noch nie gesichert wurde (die Liste zeigt dann eine Warnung). */
internal fun backupStatusText(
    lastBackupAt: LocalDateTime?,
    language: AppLanguage,
    today: LocalDate = LocalDate.now(),
): UiText? =
    lastBackupAt?.let {
        UiText.Res(R.string.more_status_backup_last, listOf(relativeDay(it.toLocalDate(), today, language)))
    }

/** Die Warnung fuer [backupStatusText] ohne Zeitstempel. */
internal val BACKUP_NEVER_TEXT: UiText = UiText.Res(R.string.more_status_backup_never)

/** „Aus" oder „Eingerichtet · server.example" — geprueft wird die Verbindung erst beim Abgleich. */
internal fun syncStatusText(config: SyncConfig?): UiText {
    if (config == null || config.url.isBlank()) return UiText.Res(R.string.more_status_sync_off)
    val host = runCatching { URI(config.url.trim()).host }.getOrNull()
    return if (host.isNullOrBlank()) {
        UiText.Res(R.string.more_status_sync_configured)
    } else {
        UiText.Res(R.string.more_status_sync_configured_host, listOf(host))
    }
}

/** Verbindet die Teile einer Statuszeile mit „ · " — in jeder Sprache dasselbe Trennzeichen. */
@Composable
internal fun List<UiText>.joinedStatus(): String = map { it.asString() }.joinToString(" · ")

// ---------------------------------------------------------------------------
// Hilfen
// ---------------------------------------------------------------------------

private fun onOff(value: Boolean): UiText =
    UiText.Res(if (value) R.string.more_status_on else R.string.more_status_off)

/** `7:00` statt `07:00` — in einer Statuszeile liest sich die kurze Form ruhiger. */
private fun shortTime(time: LocalTime): String = "${time.hour}:${time.minute.toString().padStart(2, '0')}"

/** Uhrzeit fuer heute, „gestern", sonst das kurze Datum. */
private fun relativeMoment(at: LocalDateTime, today: LocalDate, language: AppLanguage): Any =
    if (at.toLocalDate() == today) shortTime(at.toLocalTime()) else relativeDay(at.toLocalDate(), today, language)

/**
 * „heute", „gestern", `12.9.` im laufenden Jahr, sonst `12.9.2025`. Englisch
 * nimmt die gemeinsamen Datumsmuster („12 Sep", „3 Jan 2025"); das Deutsche
 * behaelt die knappe Form ohne fuehrende Null, die die Statuszeile schon
 * immer hatte.
 */
private fun relativeDay(day: LocalDate, today: LocalDate, language: AppLanguage): Any = when {
    day == today -> UiText.Res(R.string.more_status_today)
    day == today.minusDays(1) -> UiText.Res(R.string.more_status_yesterday)
    language == AppLanguage.EN && day.year == today.year -> formatDateShort(day, language)
    language == AppLanguage.EN -> formatDateFull(day, language)
    day.year == today.year -> "${day.dayOfMonth}.${day.monthValue}."
    else -> "${day.dayOfMonth}.${day.monthValue}.${day.year}"
}

/** Ganze Werte ohne Nachkommastellen, sonst mit dem Dezimalzeichen der Sprache. */
private fun profileNumber(value: Double, language: AppLanguage): String {
    val plain = formatProfileNumber(value)
    return if (language == AppLanguage.DE) plain.replace('.', ',') else plain
}
