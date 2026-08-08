package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Export, Backup und Import von Nutzerdaten.
 *
 * 1:1-Portierung von `lib/export.dart`. Baut auf dem bestehenden GPX-Code
 * ([Gpx.kt]), den vorhandenen JSON-Serialisierungen aus [Models.kt] sowie
 * [TrainingProfile] (`TrainingLoad.kt`, Portierung von
 * `lib/training_load.dart`) auf, damit Sicherungen und Einzeltour-Exporte
 * jederzeit zum Speicherformat der App kompatibel bleiben. Enthaelt keine
 * Plattform-Zugriffe (kein Dateisystem, kein Share-Sheet) — das uebernehmen
 * die aufrufenden Screens.
 */

/**
 * Aktuelle Version des Backup-JSON-Formats (siehe [buildBackupJson]).
 *
 * Wird erhoeht, sobald sich das Format inkompatibel aendert — aeltere
 * App-Versionen koennen dann anhand der Nummer erkennen, dass sie eine
 * Sicherung nicht lesen koennen, statt sie fehlerhaft zu interpretieren.
 */
const val backupFormatVersion: Int = 1

/**
 * Name der App, wie er im Backup-JSON unter `"app"` steht — dient als
 * einfache Signatur, um fremde JSON-Dateien frueh zurueckzuweisen.
 */
const val backupAppName: String = "trailscape"

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val prettyJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

// ---------------------------------------------------------------------------
// GPX-Export einer einzelnen Tour
// ---------------------------------------------------------------------------

/**
 * Erzeugt eine valide GPX-1.1-Datei fuer eine einzelne Tour: Metadaten (Name,
 * Aufnahmezeitpunkt), ein Track mit einem Segment sowie — falls vorhanden —
 * Herzfrequenz je Trackpunkt als Garmin-TrackPointExtension.
 */
fun rideToGpx(ride: Ride): String = buildGpx(ride.name, ride.points, time = ride.createdAt)

/**
 * Macht einen Tournamen dateisystemtauglich (nur Buchstaben, Ziffern, `-`
 * und `_`), z. B. fuer den Dateinamen eines GPX- oder Backup-Exports.
 */
fun safeFileName(name: String): String {
    val cleaned = name.trim()
        .replace(Regex("[^a-zA-Z0-9\\-_]+"), "_")
        .replace(Regex("^_+|_+$"), "")
    return cleaned.ifEmpty { "tour" }
}

/** Dateiname fuer einen Backup-Export, z. B. `trailscape-backup-2026-08-08.json`. */
fun backupFileName(at: LocalDate): String {
    fun pad2(v: Int): String = v.toString().padStart(2, '0')
    return "trailscape-backup-${at.year}-${pad2(at.monthValue)}-${pad2(at.dayOfMonth)}.json"
}

// ---------------------------------------------------------------------------
// GPX-Import (einzelne Datei, z. B. von Komoot/Strava)
// ---------------------------------------------------------------------------

/**
 * Baut aus dem Inhalt einer GPX-Datei eine vollstaendige Tour, inklusive
 * berechneter Statistiken (Distanz, Hoehenmeter, Ø-/Max-Puls aus den
 * Trackpunkten). Wirft [FormatException], falls die Datei kein gueltiges GPX
 * mit Trackpunkten ist (siehe [parseGpx]).
 *
 * [fallbackName] wird verwendet, wenn die GPX-Datei selbst keinen Tracknamen
 * traegt (ueblicherweise der Dateiname ohne Endung). [id] ueberschreibt die
 * sonst aus der aktuellen Uhrzeit generierte Tour-ID — fuer Tests gedacht.
 */
fun rideFromGpx(xml: String, fallbackName: String, id: String? = null): Ride {
    val parsed = parseGpx(xml)
    val points = parsed.points
    val baseStats = computeStats(points)

    val parsedName = parsed.name?.trim()
    val name = if (!parsedName.isNullOrEmpty()) parsedName else fallbackName
    val createdAt = points.first().time ?: System.currentTimeMillis()

    val hrValues = points.mapNotNull { it.hr }
    var avgHr: Int? = null
    var maxHr: Int? = null
    if (hrValues.isNotEmpty()) {
        val sum = hrValues.sum()
        avgHr = dartRound(sum.toDouble() / hrValues.size).toInt()
        maxHr = hrValues.max()
    }

    return Ride(
        id = id ?: System.currentTimeMillis().toString(),
        name = name,
        createdAt = createdAt,
        points = points,
        stats = RideStats(
            distanceKm = baseStats.distanceKm,
            durationS = baseStats.durationS,
            movingTimeS = baseStats.movingTimeS,
            avgSpeedKmh = baseStats.avgSpeedKmh,
            ascentM = baseStats.ascentM,
            descentM = baseStats.descentM,
            avgHrBpm = avgHr,
            maxHrBpm = maxHr,
        ),
    )
}

// ---------------------------------------------------------------------------
// Vollstaendiges Backup (alle Touren + Trainingsprofil)
// ---------------------------------------------------------------------------

/**
 * Baut eine vollstaendige Sicherung aus allen Touren und optional dem
 * Trainingsprofil als eingeruecktes JSON.
 */
fun buildBackupJson(rides: List<Ride>, profile: TrainingProfile?): String {
    val json = buildJsonObject {
        put("app", backupAppName)
        put("backupVersion", backupFormatVersion)
        put("exportedAt", formatIso8601Utc(System.currentTimeMillis()))
        put("profile", profile?.toJson() ?: JsonNull)
        put("rides", buildJsonArray { rides.forEach { add(it.toJson()) } })
    }
    return prettyJson.encodeToString(JsonObject.serializer(), json)
}

/**
 * Ergebnis von [parseBackupJson]: die enthaltenen Touren sowie ein
 * optionales Trainingsprofil.
 */
data class BackupData(val rides: List<Ride>, val profile: TrainingProfile? = null)

private fun JsonPrimitive.asDartIntOrNull(): Int? {
    if (isString) return null
    // Dart unterscheidet beim JSON-Decodieren `int` von `double` anhand der
    // Literal-Syntax (kein Punkt/Exponent), nicht anhand des Werts — das
    // bilden wir hier ueber den Text der Zahl nach, nicht ueber ihren Wert.
    if (!Regex("^-?\\d+$").matches(content)) return null
    return content.toIntOrNull()
}

/**
 * Liest eine Trailscape-Sicherung (siehe [buildBackupJson]) und liefert
 * Touren sowie optionales Profil. Wirft [FormatException] mit deutscher
 * Meldung bei kaputtem JSON, fremdem Format oder einer neueren, hier noch
 * unbekannten Backup-Version.
 */
fun parseBackupJson(raw: String): BackupData {
    val decoded = try {
        Json.parseToJsonElement(raw)
    } catch (e: Exception) {
        throw FormatException("Die Datei enthält kein gültiges JSON und kann nicht importiert werden.")
    }

    if (decoded !is JsonObject) {
        throw FormatException("Die Datei ist keine gültige Trailscape-Sicherung.")
    }

    val appValue = (decoded["app"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (appValue != backupAppName) {
        throw FormatException("Die Datei ist keine gültige Trailscape-Sicherung.")
    }

    val version = (decoded["backupVersion"] as? JsonPrimitive)?.asDartIntOrNull()
    if (version == null) {
        throw FormatException("Die Sicherung enthält keine gültige Versionsangabe.")
    }
    if (version > backupFormatVersion) {
        throw FormatException(
            "Diese Sicherung wurde mit einer neueren Trailscape-Version erstellt " +
                "(Format $version, unterstützt wird bis $backupFormatVersion) und kann " +
                "von dieser App-Version nicht gelesen werden. Bitte Trailscape " +
                "aktualisieren.",
        )
    }

    val ridesRaw = decoded["rides"] as? JsonArray
        ?: throw FormatException("Die Sicherung enthält keine gültige Touren-Liste.")

    val rides = mutableListOf<Ride>()
    for (entry in ridesRaw) {
        val entryObj = entry as? JsonObject
            ?: throw FormatException("Die Sicherung enthält eine ungültige Tour.")
        try {
            rides.add(Ride.fromJson(entryObj))
        } catch (e: Exception) {
            throw FormatException("Die Sicherung enthält eine ungültige Tour.")
        }
    }

    var profile: TrainingProfile? = null
    val profileRaw = decoded["profile"] as? JsonObject
    if (profileRaw != null) {
        profile = try {
            TrainingProfile.fromJson(profileRaw)
        } catch (e: Exception) {
            throw FormatException("Die Sicherung enthält ein ungültiges Trainingsprofil.")
        }
    }

    return BackupData(rides = rides, profile = profile)
}
