package de.trailscape.core.i18n

import de.trailscape.core.FitSport
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Texte des Datei-Ein- und -Ausgangs: GPX, FIT, ZIP-Archive, Sicherungen und
 * die Rueckmeldungen eines Sammelimports.
 *
 * Namen, die beim Import entstehen („Radfahrt 14.03.2024", „Importierte
 * Tour"), werden in der Sprache zum Zeitpunkt des Imports gespeichert — sie
 * sind danach der Name der Tour, den man auch umbenennen kann.
 */
interface FileTexts {
    // Namen
    fun unnamedFile(): String
    fun importedRideFallbackName(): String
    fun rideFallbackName(): String

    /** Name einer importierten FIT-Aktivitaet: Sportart plus Startdatum (UTC). */
    fun fitActivityName(sport: FitSport, startEpochMs: Long): String

    // Dateiarten und Lesefehler
    fun notAnActivityFile(): String
    fun extensionMismatch(): String
    fun fileUnreadable(): String
    fun fileTooLarge(): String
    fun gzipBroken(): String
    fun fitInvalid(): String
    fun fitNoTrackPoints(): String
    fun gpxInvalidXml(): String
    fun gpxNotGpx(): String
    fun gpxNoTrackPoints(): String
    fun gpxInvalidCoordinates(): String
    fun archiveUnreadable(): String
    fun notAZip(): String
    fun duplicateRide(): String

    // Sicherung
    fun backupInvalidJson(): String
    fun backupNotTrailscape(): String
    fun backupNoVersion(): String
    fun backupTooNew(version: Int, supported: Int): String
    fun backupNoRideList(): String
    fun backupInvalidRide(): String
    fun backupInvalidProfile(): String

    // Sammelimport
    fun importedOne(name: String, planned: Boolean): String
    fun nothingToImport(): String
    fun importedAllPlanned(count: Int): String
    fun importedSomePlanned(imported: Int, planned: Int): String
    fun imported(count: Int): String
    fun alreadyPresent(count: Int): String
    fun unreadable(count: Int): String

    /** Stehender Fehlertext, wenn keine Datei ankam; [firstError] ist der erste Grund. */
    fun importFailed(errorCount: Int, firstError: String): String
}

private val FIT_DATE_DE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC)
private val FIT_DATE_EN: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", AppLanguage.EN.locale).withZone(ZoneOffset.UTC)

internal object FileTextsDe : FileTexts {
    override fun unnamedFile() = "Datei ohne Namen"
    override fun importedRideFallbackName() = "Importierte Tour"
    override fun rideFallbackName() = "Tour"

    override fun fitActivityName(sport: FitSport, startEpochMs: Long): String {
        val label = when (sport) {
            FitSport.RUN -> "Lauf"
            FitSport.RIDE -> "Radfahrt"
            FitSport.SWIM -> "Schwimmen"
            FitSport.WALK -> "Spaziergang"
            FitSport.CROSS_COUNTRY_SKI -> "Skilanglauf"
            FitSport.ROW -> "Rudern"
            FitSport.HIKE -> "Wanderung"
            FitSport.OTHER -> "Aktivität"
        }
        return "$label ${FIT_DATE_DE.format(Instant.ofEpochMilli(startEpochMs))}"
    }

    override fun notAnActivityFile() = "Die Datei ist weder eine GPX- noch eine FIT-Datei."
    override fun extensionMismatch() =
        "Die Dateiendung passt nicht zum Inhalt — die Datei ist beschädigt oder umbenannt."
    override fun fileUnreadable() = "Die Datei konnte nicht gelesen werden."
    override fun fileTooLarge() = "Die Datei ist zu groß für eine GPX- oder FIT-Datei."
    override fun gzipBroken() = "Die Datei ist GZIP-komprimiert, konnte aber nicht entpackt werden."
    override fun fitInvalid() = "Die Datei ist keine gültige FIT-Datei."
    override fun fitNoTrackPoints() = "Die FIT-Datei enthält keine Trackpunkte."
    override fun gpxInvalidXml() = "Die GPX-Datei enthält ungültiges XML."
    override fun gpxNotGpx() = "Die Datei ist keine gültige GPX-Datei."
    override fun gpxNoTrackPoints() = "Die GPX-Datei enthält keine Trackpunkte."
    override fun gpxInvalidCoordinates() = "Ungültige Koordinaten in der GPX-Datei."
    override fun archiveUnreadable() = "Das Archiv konnte nicht gelesen werden."
    override fun notAZip() = "Die Datei ist kein gültiges ZIP-Archiv."
    override fun duplicateRide() = "Diese Tour ist bereits vorhanden."

    override fun backupInvalidJson() = "Die Datei enthält kein gültiges JSON und kann nicht importiert werden."
    override fun backupNotTrailscape() = "Die Datei ist keine gültige Trailscape-Sicherung."
    override fun backupNoVersion() = "Die Sicherung enthält keine gültige Versionsangabe."
    override fun backupTooNew(version: Int, supported: Int) =
        "Diese Sicherung wurde mit einer neueren Trailscape-Version erstellt " +
            "(Format $version, unterstützt wird bis $supported) und kann " +
            "von dieser App-Version nicht gelesen werden. Bitte Trailscape " +
            "aktualisieren."
    override fun backupNoRideList() = "Die Sicherung enthält keine gültige Touren-Liste."
    override fun backupInvalidRide() = "Die Sicherung enthält eine ungültige Tour."
    override fun backupInvalidProfile() = "Die Sicherung enthält ein ungültiges Trainingsprofil."

    override fun importedOne(name: String, planned: Boolean) =
        if (planned) "„$name“ als Planung importiert" else "„$name“ importiert"
    override fun nothingToImport() = "Keine Datei zum Importieren gefunden."
    override fun importedAllPlanned(count: Int) = "$count als Planung importiert"
    override fun importedSomePlanned(imported: Int, planned: Int) = "$imported importiert ($planned als Planung)"
    override fun imported(count: Int) = "$count importiert"
    override fun alreadyPresent(count: Int) = "$count schon vorhanden"
    override fun unreadable(count: Int) = "$count unlesbar"

    override fun importFailed(errorCount: Int, firstError: String): String {
        val intro = if (errorCount == 1) {
            "Die Datei konnte nicht importiert werden."
        } else {
            "Keine der $errorCount Dateien konnte importiert werden."
        }
        return "$intro Trailscape liest GPX- und FIT-Dateien, auch als .gz gepackt. ($firstError)"
    }
}

internal object FileTextsEn : FileTexts {
    override fun unnamedFile() = "Unnamed file"
    override fun importedRideFallbackName() = "Imported ride"
    override fun rideFallbackName() = "Ride"

    override fun fitActivityName(sport: FitSport, startEpochMs: Long): String {
        val label = when (sport) {
            FitSport.RUN -> "Run"
            FitSport.RIDE -> "Ride"
            FitSport.SWIM -> "Swim"
            FitSport.WALK -> "Walk"
            FitSport.CROSS_COUNTRY_SKI -> "Cross-country ski"
            FitSport.ROW -> "Row"
            FitSport.HIKE -> "Hike"
            FitSport.OTHER -> "Activity"
        }
        return "$label ${FIT_DATE_EN.format(Instant.ofEpochMilli(startEpochMs))}"
    }

    override fun notAnActivityFile() = "The file is neither a GPX nor a FIT file."
    override fun extensionMismatch() =
        "The file extension doesn’t match the content — the file is damaged or was renamed."
    override fun fileUnreadable() = "The file couldn’t be read."
    override fun fileTooLarge() = "The file is too large for a GPX or FIT file."
    override fun gzipBroken() = "The file is GZIP-compressed but couldn’t be unpacked."
    override fun fitInvalid() = "The file isn’t a valid FIT file."
    override fun fitNoTrackPoints() = "The FIT file contains no track points."
    override fun gpxInvalidXml() = "The GPX file contains invalid XML."
    override fun gpxNotGpx() = "The file isn’t a valid GPX file."
    override fun gpxNoTrackPoints() = "The GPX file contains no track points."
    override fun gpxInvalidCoordinates() = "Invalid coordinates in the GPX file."
    override fun archiveUnreadable() = "The archive couldn’t be read."
    override fun notAZip() = "The file isn’t a valid ZIP archive."
    override fun duplicateRide() = "This ride already exists."

    override fun backupInvalidJson() = "The file doesn’t contain valid JSON and can’t be imported."
    override fun backupNotTrailscape() = "The file isn’t a valid Trailscape backup."
    override fun backupNoVersion() = "The backup doesn’t contain a valid version."
    override fun backupTooNew(version: Int, supported: Int) =
        "This backup was created with a newer version of Trailscape " +
            "(format $version, supported up to $supported) and can’t be read " +
            "by this version of the app. Please update Trailscape."
    override fun backupNoRideList() = "The backup doesn’t contain a valid list of rides."
    override fun backupInvalidRide() = "The backup contains an invalid ride."
    override fun backupInvalidProfile() = "The backup contains an invalid training profile."

    override fun importedOne(name: String, planned: Boolean) =
        if (planned) "“$name” imported as a planned route" else "“$name” imported"
    override fun nothingToImport() = "No file to import found."
    override fun importedAllPlanned(count: Int) = "$count imported as planned routes"
    override fun importedSomePlanned(imported: Int, planned: Int) =
        "$imported imported ($planned as planned routes)"
    override fun imported(count: Int) = "$count imported"
    override fun alreadyPresent(count: Int) = "$count already present"
    override fun unreadable(count: Int) = "$count unreadable"

    override fun importFailed(errorCount: Int, firstError: String): String {
        val intro = if (errorCount == 1) {
            "The file couldn’t be imported."
        } else {
            "None of the $errorCount files could be imported."
        }
        return "$intro Trailscape reads GPX and FIT files, including .gz-compressed ones. ($firstError)"
    }
}
