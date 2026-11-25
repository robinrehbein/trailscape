package de.trailscape.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # Plan-Rezepte — Trainingsplaene und Einheiten als teilbare JSON-Datei
 *
 * Ein Rezept beschreibt einen Plan (Wochen mit Einheiten) oder eine einzelne
 * Einheit **ohne jede persoenliche Angabe**: Intensitaeten stehen relativ
 * (Zone oder Prozent der FTP), erst beim Empfaenger werden daraus Watt. Das
 * Format ist in `docs/plan-rezepte.md` beschrieben und von Hand schreibbar.
 *
 * Diese Datei ist das **Dateiformat**: Modell, Lesen (streng gegen fremde
 * Dateien) und Schreiben (formatstabil, Schluesselreihenfolge fest). Die
 * Umrechnung in einen [TrainingPlan] und zurueck steht in `PlanRecipeConvert.kt`.
 *
 * ## Vertraege
 *  * `format` = [PLAN_RECIPE_FORMAT] und `version` sind Pflicht. Eine neuere
 *    `version` als [PLAN_RECIPE_VERSION] wird **nicht** halb gelesen, sondern
 *    mit [RecipeProblem.NEWER_VERSION] abgelehnt.
 *  * Unbekannte Schluessel werden ueberlesen (Vorwaertskompatibilitaet
 *    innerhalb einer Version, neue Felder werden nur angehaengt).
 *  * Grenzen ([MAX_RECIPE_BYTES], Tiefe, Anzahlen, Wertebereiche) schuetzen
 *    vor boesartigen und kaputten Dateien; [parsePlanRecipe] wirft **nie**.
 */

const val PLAN_RECIPE_FORMAT = "trailscape-plan-recipe"

/** Aktuelle Formatversion. Eine Aenderung, die aeltere Leser bricht, erhoeht sie. */
const val PLAN_RECIPE_VERSION = 1

/** Groesste angenommene Datei (ein 52-Wochen-Plan mit 7 Einheiten liegt bei etwa 150 KiB). */
const val MAX_RECIPE_BYTES = 256 * 1024

const val MAX_RECIPE_DEPTH = 8
const val MAX_RECIPE_WEEKS = 52
const val MAX_RECIPE_SESSIONS_PER_WEEK = 14
const val MAX_RECIPE_SEGMENTS = 40
const val MAX_SEGMENT_MINUTES = 600
const val MAX_SEGMENT_REPEAT = 60
const val MAX_SEGMENT_RECOVERY_MIN = 60
const val MAX_SESSION_MINUTES = 12 * 60
const val MIN_FTP_PERCENT = 20
const val MAX_FTP_PERCENT = 250
const val MAX_TITLE_CHARS = 80
const val MAX_AUTHOR_CHARS = 80
const val MAX_NOTES_CHARS = 2000
const val MAX_LABEL_CHARS = 60

/** Wochentage im Rezept: kurze englische Kuerzel, unabhaengig von der App-Sprache. */
internal val RECIPE_DAY_CODES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

/** Art einer Woche; unbekannte Werte lesen als [BUILD]. */
enum class RecipeWeekKind(val jsonName: String, val weekKind: WeekKind) {
    BUILD("build", WeekKind.AUFBAU),
    RECOVERY("recovery", WeekKind.ERHOLUNG),
    TAPER("taper", WeekKind.TAPER),
    PEAK("peak", WeekKind.ZIELWOCHE),
    ;

    companion object {
        fun fromJsonNameOrDefault(name: String?): RecipeWeekKind =
            entries.firstOrNull { it.jsonName == name } ?: BUILD

        fun of(kind: WeekKind): RecipeWeekKind = entries.first { it.weekKind == kind }
    }
}

/**
 * Ein Abschnitt einer Einheit: [repeat] mal [minutes] Minuten im Bereich
 * [ftpLowPercent]–[ftpHighPercent] der FTP, dazwischen je [recoveryMinutes]
 * Minuten Erholung (Zone [TargetZone.RECOVERY], nur *zwischen* den
 * Wiederholungen).
 *
 * [zone] merkt sich, ob der Bereich als Zone benannt war (Rueckschreiben im
 * selben Wortlaut); die Prozente sind dann die der Zone.
 */
data class RecipeSegment(
    val minutes: Int,
    val ftpLowPercent: Int,
    val ftpHighPercent: Int,
    val zone: TargetZone? = null,
    val repeat: Int = 1,
    val recoveryMinutes: Int = 0,
    val label: String? = null,
) {
    /** Gesamtdauer inklusive der Erholungen zwischen den Wiederholungen. */
    val totalMinutes: Int get() = repeat * minutes + (repeat - 1) * recoveryMinutes

    /** Mitte des Bereichs als Anteil der FTP (0,88 = 88 %). */
    val midFtp: Double get() = (ftpLowPercent + ftpHighPercent) / 200.0

    companion object {
        /** Segment in einer benannten Zone. */
        fun inZone(
            minutes: Int,
            zone: TargetZone,
            repeat: Int = 1,
            recoveryMinutes: Int = 0,
            label: String? = null,
        ): RecipeSegment = RecipeSegment(
            minutes = minutes,
            ftpLowPercent = zonePercent(zone.powerLowFtp),
            ftpHighPercent = zonePercent(zone.powerHighFtp),
            zone = zone,
            repeat = repeat,
            recoveryMinutes = recoveryMinutes,
            label = label,
        )

        internal fun zonePercent(share: Double): Int = Math.round(share * 100).toInt()
    }
}

/**
 * Eine Einheit: [day] 0 = Mo … 6 = So (`null` bei einer einzelnen Einheit),
 * [title], optionale [notes] und mindestens ein Abschnitt.
 */
data class RecipeSession(
    val day: Int?,
    val title: String,
    val notes: String? = null,
    val segments: List<RecipeSegment>,
) {
    val totalMinutes: Int get() = segments.sumOf { it.totalMinutes }
}

data class RecipeWeek(
    val kind: RecipeWeekKind,
    val sessions: List<RecipeSession>,
)

/** Ein gelesenes (oder zum Schreiben vorbereitetes) Rezept. */
sealed interface PlanRecipe {
    val title: String
    val author: String?
    val notes: String?
}

/** Ein Rezept fuer einen ganzen Plan. */
data class PlanRecipePlan(
    override val title: String,
    override val author: String? = null,
    override val notes: String? = null,
    val weeks: List<RecipeWeek>,
) : PlanRecipe

/** Ein Rezept fuer eine einzelne Einheit. */
data class PlanRecipeWorkout(
    override val title: String,
    override val author: String? = null,
    override val notes: String? = null,
    val session: RecipeSession,
) : PlanRecipe

/** Warum eine Datei abgelehnt wurde — die Oberflaeche macht daraus einen Satz. */
enum class RecipeProblem {
    /** Groesser als [MAX_RECIPE_BYTES] (oder leer). */
    TOO_LARGE,

    /** Kein gueltiges UTF-8. */
    NOT_TEXT,

    /** Kein lesbares JSON. */
    NOT_JSON,

    /** Zu tief verschachtelt (mehr als [MAX_RECIPE_DEPTH] Ebenen). */
    TOO_DEEP,

    /** JSON, aber kein Plan-Rezept (`format` fehlt oder passt nicht), z. B. ein Backup. */
    NOT_A_RECIPE,

    /** `version` ist neuer als [PLAN_RECIPE_VERSION]; [RecipeRejection.version] nennt sie. */
    NEWER_VERSION,

    /** Ein Feld fehlt oder liegt ausserhalb des erlaubten Bereichs; [RecipeRejection.path] sagt welches. */
    INVALID_FIELD,

    /** Zu viele Wochen, Einheiten oder Abschnitte. */
    TOO_MANY,

    /** Gueltig, aber ohne Einheit. */
    EMPTY,
}

/** Ablehnung samt Ort: [path] ist der JSON-Pfad (`weeks[2].sessions[0].segments[1].minutes`). */
data class RecipeRejection(
    val problem: RecipeProblem,
    val path: String? = null,
    val version: Int? = null,
)

/** Ergebnis von [parsePlanRecipe]. */
sealed interface PlanRecipeParse {
    data class Ok(val recipe: PlanRecipe) : PlanRecipeParse
    data class Rejected(val rejection: RecipeRejection) : PlanRecipeParse
}

private class RecipeReject(val rejection: RecipeRejection) : Exception(null, null, false, false)

private fun reject(problem: RecipeProblem, path: String? = null, version: Int? = null): Nothing =
    throw RecipeReject(RecipeRejection(problem, path, version))

/**
 * Liest ein Rezept aus den Bytes einer Datei. Wirft **nie**: Jede Auffaelligkeit
 * wird zu [PlanRecipeParse.Rejected].
 */
fun parsePlanRecipe(bytes: ByteArray): PlanRecipeParse {
    if (bytes.isEmpty() || bytes.size > MAX_RECIPE_BYTES) {
        return PlanRecipeParse.Rejected(RecipeRejection(RecipeProblem.TOO_LARGE))
    }
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        return PlanRecipeParse.Rejected(RecipeRejection(RecipeProblem.NOT_TEXT))
    }
    return parsePlanRecipe(text)
}

/** Wie [parsePlanRecipe] fuer Bytes, aber fuer bereits dekodierten Text (Tests, Zwischenablage). */
fun parsePlanRecipe(text: String): PlanRecipeParse = try {
    PlanRecipeParse.Ok(readRecipe(text))
} catch (e: RecipeReject) {
    PlanRecipeParse.Rejected(e.rejection)
} catch (_: Exception) {
    // Netz und doppelter Boden: Nichts, was die Pruefung uebersieht, darf die App beenden.
    PlanRecipeParse.Rejected(RecipeRejection(RecipeProblem.NOT_JSON))
} catch (_: StackOverflowError) {
    PlanRecipeParse.Rejected(RecipeRejection(RecipeProblem.TOO_DEEP))
}

private fun readRecipe(raw: String): PlanRecipe {
    val text = raw.removePrefix("﻿")
    if (text.length > MAX_RECIPE_BYTES) reject(RecipeProblem.TOO_LARGE)
    if (jsonNestingDepth(text) > MAX_RECIPE_DEPTH) reject(RecipeProblem.TOO_DEEP)
    val parsed: JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        reject(RecipeProblem.NOT_JSON)
    }
    val root = parsed as? JsonObject ?: reject(RecipeProblem.NOT_A_RECIPE)

    if (root.str("format") != PLAN_RECIPE_FORMAT) reject(RecipeProblem.NOT_A_RECIPE)
    val version = root.intField("version", "version", min = 1, max = Int.MAX_VALUE)
    if (version > PLAN_RECIPE_VERSION) reject(RecipeProblem.NEWER_VERSION, version = version)

    val title = root.requiredText("title", "title", MAX_TITLE_CHARS)
    val author = root.optionalText("author", "author", MAX_AUTHOR_CHARS)
    val notes = root.optionalText("notes", "notes", MAX_NOTES_CHARS)

    val weeksElement = root["weeks"]
    val workoutElement = root["workout"]
    return when {
        weeksElement != null && weeksElement !is JsonNull -> {
            val weeksArray = weeksElement as? JsonArray ?: reject(RecipeProblem.INVALID_FIELD, "weeks")
            if (weeksArray.isEmpty()) reject(RecipeProblem.EMPTY, "weeks")
            if (weeksArray.size > MAX_RECIPE_WEEKS) reject(RecipeProblem.TOO_MANY, "weeks")
            val weeks = weeksArray.mapIndexed { i, element -> readWeek(element, "weeks[$i]") }
            if (weeks.all { it.sessions.isEmpty() }) reject(RecipeProblem.EMPTY, "weeks")
            PlanRecipePlan(title, author, notes, weeks)
        }
        workoutElement != null && workoutElement !is JsonNull -> {
            PlanRecipeWorkout(title, author, notes, readSession(workoutElement, "workout", dayRequired = false))
        }
        else -> reject(RecipeProblem.EMPTY, "weeks")
    }
}

private fun readWeek(element: JsonElement, path: String): RecipeWeek {
    val obj = element as? JsonObject ?: reject(RecipeProblem.INVALID_FIELD, path)
    val sessionsElement = obj["sessions"]
    val sessions = when (sessionsElement) {
        null, is JsonNull -> emptyList()
        is JsonArray -> {
            if (sessionsElement.size > MAX_RECIPE_SESSIONS_PER_WEEK) {
                reject(RecipeProblem.TOO_MANY, "$path.sessions")
            }
            sessionsElement.mapIndexed { i, e -> readSession(e, "$path.sessions[$i]", dayRequired = true) }
        }
        else -> reject(RecipeProblem.INVALID_FIELD, "$path.sessions")
    }
    return RecipeWeek(RecipeWeekKind.fromJsonNameOrDefault(obj.str("kind")), sessions)
}

private fun readSession(element: JsonElement, path: String, dayRequired: Boolean): RecipeSession {
    val obj = element as? JsonObject ?: reject(RecipeProblem.INVALID_FIELD, path)
    val day = when (val d = obj["day"]) {
        null, is JsonNull -> if (dayRequired) reject(RecipeProblem.INVALID_FIELD, "$path.day") else null
        else -> {
            val code = (d as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()
            val index = RECIPE_DAY_CODES.indexOf(code)
            if (index < 0) reject(RecipeProblem.INVALID_FIELD, "$path.day")
            index
        }
    }
    val title = obj.requiredText("title", "$path.title", MAX_TITLE_CHARS)
    val notes = obj.optionalText("notes", "$path.notes", MAX_NOTES_CHARS)
    val segmentsArray = obj["segments"] as? JsonArray ?: reject(RecipeProblem.INVALID_FIELD, "$path.segments")
    if (segmentsArray.isEmpty()) reject(RecipeProblem.EMPTY, "$path.segments")
    if (segmentsArray.size > MAX_RECIPE_SEGMENTS) reject(RecipeProblem.TOO_MANY, "$path.segments")
    val segments = segmentsArray.mapIndexed { i, e -> readSegment(e, "$path.segments[$i]") }
    if (segments.sumOf { it.totalMinutes } > MAX_SESSION_MINUTES) reject(RecipeProblem.INVALID_FIELD, "$path.segments")
    return RecipeSession(day, title, notes, segments)
}

private fun readSegment(element: JsonElement, path: String): RecipeSegment {
    val obj = element as? JsonObject ?: reject(RecipeProblem.INVALID_FIELD, path)
    val minutes = obj.intField("minutes", "$path.minutes", min = 1, max = MAX_SEGMENT_MINUTES)
    val repeat = obj.optionalIntField("repeat", "$path.repeat", min = 1, max = MAX_SEGMENT_REPEAT) ?: 1
    val recovery = obj.optionalIntField("recoveryMinutes", "$path.recoveryMinutes", min = 0, max = MAX_SEGMENT_RECOVERY_MIN) ?: 0
    val label = obj.optionalText("label", "$path.label", MAX_LABEL_CHARS)

    val percentElement = obj["ftpPercent"]
    val zoneName = obj.str("zone")
    return when {
        percentElement != null && percentElement !is JsonNull -> {
            val range = percentElement as? JsonArray ?: reject(RecipeProblem.INVALID_FIELD, "$path.ftpPercent")
            if (range.size != 2) reject(RecipeProblem.INVALID_FIELD, "$path.ftpPercent")
            val low = range[0].asPercent("$path.ftpPercent[0]")
            val high = range[1].asPercent("$path.ftpPercent[1]")
            if (low > high) reject(RecipeProblem.INVALID_FIELD, "$path.ftpPercent")
            RecipeSegment(minutes, low, high, null, repeat, recovery, label)
        }
        zoneName != null -> {
            val zone = TargetZone.entries.firstOrNull { it.jsonName == zoneName }
                ?: reject(RecipeProblem.INVALID_FIELD, "$path.zone")
            RecipeSegment.inZone(minutes, zone, repeat, recovery, label)
        }
        else -> reject(RecipeProblem.INVALID_FIELD, "$path.zone")
    }
}

private fun JsonElement.asPercent(path: String): Int {
    val value = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it == Math.floor(it) }
        ?: reject(RecipeProblem.INVALID_FIELD, path)
    if (value < MIN_FTP_PERCENT || value > MAX_FTP_PERCENT) reject(RecipeProblem.INVALID_FIELD, path)
    return value.toInt()
}

// ---------------------------------------------------------------------------
// Strenge Feldzugriffe (anders als die toleranten Helfer in JsonSupport.kt:
// hier ist ein falscher Typ eine Ablehnung, kein Default)
// ---------------------------------------------------------------------------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intField(key: String, path: String, min: Int, max: Int): Int =
    optionalIntField(key, path, min, max) ?: reject(RecipeProblem.INVALID_FIELD, path)

private fun JsonObject.optionalIntField(key: String, path: String, min: Int, max: Int): Int? {
    val element = this[key]
    if (element == null || element is JsonNull) return null
    val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it == Math.floor(it) }
        ?: reject(RecipeProblem.INVALID_FIELD, path)
    if (value < min || value > max) reject(RecipeProblem.INVALID_FIELD, path)
    return value.toInt()
}

private fun JsonObject.requiredText(key: String, path: String, maxChars: Int): String =
    optionalText(key, path, maxChars) ?: reject(RecipeProblem.INVALID_FIELD, path)

/** Text ohne Steuerzeichen (ausser Zeilenumbruch), getrimmt; leer = nicht vorhanden. */
private fun JsonObject.optionalText(key: String, path: String, maxChars: Int): String? {
    val element = this[key]
    if (element == null || element is JsonNull) return null
    val prim = element as? JsonPrimitive
    if (prim == null || !prim.isString) reject(RecipeProblem.INVALID_FIELD, path)
    val cleaned = prim.content.filter { it == '\n' || !it.isISOControl() }.trim()
    if (cleaned.length > maxChars) reject(RecipeProblem.INVALID_FIELD, path)
    return cleaned.ifEmpty { null }
}

/**
 * Tiefste Verschachtelung von `[`/`{` im Text, Zeichenketten ausgenommen.
 * Laeuft vor dem Parser, damit tief geschachtelte Dateien gar nicht erst in
 * dessen Rekursion kommen.
 */
internal fun jsonNestingDepth(text: String): Int {
    var depth = 0
    var max = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '[', '{' -> {
                depth++
                if (depth > max) max = depth
                if (max > MAX_RECIPE_DEPTH) return max
            }
            ']', '}' -> if (depth > 0) depth--
        }
    }
    return max
}

// ---------------------------------------------------------------------------
// Schreiben — formatstabil: feste Schluesselreihenfolge, nur Felder mit Wert
// ---------------------------------------------------------------------------

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

/** Das Rezept als JSON-Objekt (siehe `docs/plan-rezepte.md`). */
fun PlanRecipe.toJson(): JsonObject = buildJsonObject {
    put("format", PLAN_RECIPE_FORMAT)
    put("version", PLAN_RECIPE_VERSION)
    put("title", title)
    author?.let { put("author", it) }
    notes?.let { put("notes", it) }
    when (this@toJson) {
        is PlanRecipePlan -> put("weeks", buildJsonArray { weeks.forEach { add(it.toJson()) } })
        is PlanRecipeWorkout -> put("workout", session.toJson())
    }
}

/** Das Rezept als lesbarer, eingerueckter JSON-Text — das, was in die Datei kommt. */
fun PlanRecipe.toJsonText(): String = prettyJson.encodeToString(JsonObject.serializer(), toJson()) + "\n"

private fun RecipeWeek.toJson(): JsonObject = buildJsonObject {
    put("kind", kind.jsonName)
    put("sessions", buildJsonArray { sessions.forEach { add(it.toJson()) } })
}

private fun RecipeSession.toJson(): JsonObject = buildJsonObject {
    day?.let { put("day", RECIPE_DAY_CODES[it]) }
    put("title", title)
    notes?.let { put("notes", it) }
    put("segments", buildJsonArray { segments.forEach { add(it.toJson()) } })
}

private fun RecipeSegment.toJson(): JsonObject = buildJsonObject {
    label?.let { put("label", it) }
    put("minutes", minutes)
    if (zone != null) {
        put("zone", zone.jsonName)
    } else {
        put("ftpPercent", buildJsonArray { add(JsonPrimitive(ftpLowPercent)); add(JsonPrimitive(ftpHighPercent)) })
    }
    if (repeat != 1) put("repeat", repeat)
    if (recoveryMinutes != 0) put("recoveryMinutes", recoveryMinutes)
}

/** Dateiname fuer ein Rezept, ohne Pfadzeichen: `Grundlage-12-Wochen.tsplan.json`. */
fun planRecipeFileName(title: String): String {
    val slug = title.trim()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-{2,}"), "-")
        .trim('-')
        .take(48)
        .ifEmpty { "plan" }
    return "$slug$PLAN_RECIPE_FILE_SUFFIX"
}

/** Endung der Rezeptdateien; endet auf `.json`, damit Texteditoren sie oeffnen. */
const val PLAN_RECIPE_FILE_SUFFIX = ".tsplan.json"

/** MIME-Typ beim Teilen — eigener Typ, damit weder Backup- noch GPX-Import ihn annehmen. */
const val PLAN_RECIPE_MIME_TYPE = "application/vnd.trailscape.plan+json"
