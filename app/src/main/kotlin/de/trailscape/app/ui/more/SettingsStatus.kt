package de.trailscape.app.ui.more

import de.trailscape.app.ui.formatBytes
import de.trailscape.core.HealthAvailability
import de.trailscape.core.HealthConnection
import de.trailscape.core.ReminderSettings
import de.trailscape.core.SyncConfig
import de.trailscape.core.TrainingProfile
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
 */

/** „40 J · 75 kg · Rad 11 kg" — oder der Hinweis, dass noch nichts eingetragen ist. */
internal fun profileStatusText(profile: TrainingProfile, confirmed: Boolean): String =
    if (!confirmed) {
        "Noch nicht eingetragen"
    } else {
        "${profile.ageYears} J · ${germanNumber(profile.weightKg)} kg · " +
            "Rad ${germanNumber(profile.setupMassKg)} kg"
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
    today: LocalDate = LocalDate.now(),
): String = when {
    connection == null -> "Wird geprüft …"
    connection.isReady -> buildString {
        append("Verbunden")
        lastImportAt?.let { append(" · zuletzt ").append(relativeMoment(it, today)) }
    }
    connection.availability == HealthAvailability.NICHT_INSTALLIERT -> "Nicht installiert"
    connection.availability == HealthAvailability.UPDATE_NOETIG -> "Update nötig"
    connection.availability == HealthAvailability.NICHT_UNTERSTUETZT -> "Nicht verfügbar"
    else -> "Nicht verbunden"
}

/** „Auto-Pause an · Ansagen an". */
internal fun recordingStatusText(autoPause: Boolean, voice: Boolean): String =
    "Auto-Pause ${onOff(autoPause)} · Ansagen ${onOff(voice)}"

/** „Tagesplan um 7:00" / „Wochenrückblick · Anstupser" / „Aus". */
internal fun reminderStatusText(settings: ReminderSettings): String {
    val parts = buildList {
        if (settings.dailySessionEnabled) add("Tagesplan um ${shortTime(settings.dailySessionTime)}")
        if (settings.weeklyReviewEnabled) add("Wochenrückblick")
        if (settings.nudgeEnabled) add("Anstupser")
    }
    return if (parts.isEmpty()) "Aus" else parts.joinToString(" · ")
}

/**
 * „2 Regionen · 184 MB" — Kartenbild und Routingdaten zusammen, denn beide
 * liegen auf derselben Seite und belegen denselben Speicher.
 *
 * @param mapRegions Anzahl der Offline-Kartenausschnitte, `null` = unbekannt.
 * @param routingTiles Anzahl der Routing-Kacheln, `null` = unbekannt.
 */
internal fun offlineStatusText(mapRegions: Int?, routingTiles: Int?, totalBytes: Long): String {
    if (mapRegions == null && routingTiles == null) return "Wird geprüft …"
    val maps = mapRegions ?: 0
    val tiles = routingTiles ?: 0
    if (maps == 0 && tiles == 0) return "Nichts gespeichert"
    return buildList {
        if (maps > 0) add(if (maps == 1) "1 Region" else "$maps Regionen")
        if (tiles > 0) add(if (tiles == 1) "1 Routing-Kachel" else "$tiles Routing-Kacheln")
        if (totalBytes > 0L) add(formatBytes(totalBytes))
    }.joinToString(" · ")
}

/** „Zuletzt: 12.9." — oder `null`, wenn noch nie gesichert wurde (die Liste zeigt dann eine Warnung). */
internal fun backupStatusText(lastBackupAt: LocalDateTime?, today: LocalDate = LocalDate.now()): String? =
    lastBackupAt?.let { "Zuletzt: ${relativeDay(it.toLocalDate(), today)}" }

/** Die Warnung fuer [backupStatusText] ohne Zeitstempel. */
internal const val BACKUP_NEVER_TEXT = "Noch nie gesichert"

/** „Aus" oder „Eingerichtet · server.example" — geprueft wird die Verbindung erst beim Abgleich. */
internal fun syncStatusText(config: SyncConfig?): String {
    if (config == null || config.url.isBlank()) return "Aus"
    val host = runCatching { URI(config.url.trim()).host }.getOrNull()
    return if (host.isNullOrBlank()) "Eingerichtet" else "Eingerichtet · $host"
}

// ---------------------------------------------------------------------------
// Hilfen
// ---------------------------------------------------------------------------

private fun onOff(value: Boolean): String = if (value) "an" else "aus"

/** `7:00` statt `07:00` — in einer Statuszeile liest sich die kurze Form ruhiger. */
private fun shortTime(time: LocalTime): String = "${time.hour}:${time.minute.toString().padStart(2, '0')}"

/** Uhrzeit fuer heute, „gestern", sonst das kurze Datum. */
private fun relativeMoment(at: LocalDateTime, today: LocalDate): String =
    if (at.toLocalDate() == today) shortTime(at.toLocalTime()) else relativeDay(at.toLocalDate(), today)

/** „heute", „gestern", `12.9.` im laufenden Jahr, sonst `12.9.2025`. */
private fun relativeDay(day: LocalDate, today: LocalDate): String = when {
    day == today -> "heute"
    day == today.minusDays(1) -> "gestern"
    day.year == today.year -> "${day.dayOfMonth}.${day.monthValue}."
    else -> "${day.dayOfMonth}.${day.monthValue}.${day.year}"
}

/** Ganze Werte ohne Nachkommastellen, sonst mit deutschem Komma. */
private fun germanNumber(value: Double): String = formatProfileNumber(value).replace('.', ',')
