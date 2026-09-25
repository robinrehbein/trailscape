package de.trailscape.core

/**
 * Erkennung und Sammelimport einzelner Aktivitaetsdateien (GPX oder FIT, je
 * auch `.gz`) — der Kern hinter „Teilen an Trailscape", „Oeffnen mit
 * Trailscape" und der Mehrfachauswahl im App-Dialog.
 *
 * Wie [importArchive] bewusst plattformfrei: Die App liest die Dateien
 * (Content-URIs, Storage Access Framework) selbst zu [ActivityFileInput]s ein
 * und reicht nur Bytes, Anzeigenamen und MIME-Typ herein. Damit laesst sich
 * die ganze Entscheidungslogik — welche Datei ist was, was ist doppelt, was
 * unlesbar — ohne Android testen.
 *
 * ## Warum Endung UND Inhalt
 * Beim Teilen aus fremden Apps ist auf keines der beiden Merkmale Verlass:
 * Android kennt fuer `.gpx`/`.fit` keinen MIME-Typ und meldet oft
 * `application/octet-stream`, Mail-Apps liefern Anhaenge mitunter ganz ohne
 * Namen, und umbenannte Dateien gibt es auch. Deshalb entscheidet der
 * **Inhalt** (Magic Bytes) ueber die Dateiart, und eine vorhandene Endung
 * muss dazu passen. Das verhindert, dass ein beliebiges geteiltes Binaerfile
 * (`application/octet-stream` ist der Sammeltopf) erst tief im Parser mit
 * einer unverstaendlichen Meldung scheitert.
 */

/** Dateiart einer einzelnen Aktivitaetsdatei. */
enum class ActivityFileKind { GPX, FIT }

/** Eine eingelesene, noch nicht ausgewertete Datei (Bytes liegen schon im Speicher). */
class ActivityFileInput(
    /** Anzeigename (z. B. `Morgenrunde.gpx`), falls der Anbieter einen liefert. */
    val displayName: String?,
    /** Vom Anbieter bzw. Intent gemeldeter MIME-Typ, falls bekannt. */
    val mimeType: String?,
    /** Rohinhalt der Datei — ggf. noch GZIP-gepackt. */
    val bytes: ByteArray,
    /**
     * Gesetzt, wenn schon das Einlesen scheiterte (Cloud-Datei nicht lokal,
     * Berechtigung abgelaufen). Die Datei zaehlt dann als unlesbar, statt
     * den ganzen Sammelimport abzubrechen.
     */
    val readError: String? = null,
) {
    /** Bezeichnung fuer Meldungen und [BulkImportResult]: Name oder Platzhalter. */
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: "Datei ohne Namen"
}

/** Meldung fuer Dateien, die weder GPX noch FIT sind. */
const val NOT_AN_ACTIVITY_FILE_MESSAGE: String =
    "Die Datei ist weder eine GPX- noch eine FIT-Datei."

/** MIME-Typen, die eine Datei bereits als GPX bzw. FIT ausweisen. */
private val GPX_MIME_TYPES = setOf("application/gpx+xml", "application/x-gpx+xml", "application/gpx")
private val FIT_MIME_TYPES = setOf("application/vnd.ant.fit", "application/fit", "application/x-fit")

/**
 * Dateiart anhand der Magic Bytes; `null`, wenn der Inhalt weder GPX noch
 * FIT ist. `.gz` wird vorher transparent entpackt.
 *
 *  * **FIT:** Header-Laenge 12 oder 14 im ersten Byte und die Signatur
 *    `.FIT` an Offset 8 — so steht es in der FIT-Spezifikation.
 *  * **GPX:** Text, der (nach BOM und Leerraum) mit `<` beginnt und in den
 *    ersten Kilobytes ein `<gpx`-Element enthaelt. Nur der Anfang wird
 *    angesehen, weil davor hoechstens XML-Deklaration und Kommentare stehen.
 */
fun sniffActivityFileKind(bytes: ByteArray): ActivityFileKind? {
    val data = try {
        gunzipIfNeeded(bytes)
    } catch (e: FormatException) {
        return null
    }
    if (data.size >= 12) {
        val headerSize = data[0].toInt() and 0xFF
        if ((headerSize == 12 || headerSize == 14) &&
            data[8] == '.'.code.toByte() && data[9] == 'F'.code.toByte() &&
            data[10] == 'I'.code.toByte() && data[11] == 'T'.code.toByte()
        ) {
            return ActivityFileKind.FIT
        }
    }
    val head = String(data, 0, minOf(data.size, GPX_SNIFF_BYTES), Charsets.UTF_8)
        .removePrefix("﻿")
        .trimStart()
    if (head.startsWith("<") && head.contains("<gpx", ignoreCase = true)) {
        return ActivityFileKind.GPX
    }
    return null
}

/** Wie weit [sniffActivityFileKind] nach `<gpx` sucht — grosszuegig fuer lange Kopfkommentare. */
private const val GPX_SNIFF_BYTES = 4096

/**
 * Dateiart anhand der Endung (`.gpx`, `.fit`, je auch `.gz`); `null` ohne
 * Namen oder bei einer anderen Endung.
 */
fun activityKindFromName(name: String?): ActivityFileKind? {
    val lower = name?.trim()?.lowercase() ?: return null
    val stem = lower.removeSuffix(".gz")
    return when {
        stem.endsWith(".gpx") -> ActivityFileKind.GPX
        stem.endsWith(".fit") -> ActivityFileKind.FIT
        else -> null
    }
}

/**
 * Entscheidet, als was [input] gelesen wird — oder wirft [FormatException].
 *
 * Regeln (siehe Datei-KDoc fuer das Warum):
 *  1. Der Inhalt muss per Magic Bytes GPX oder FIT sein.
 *  2. Traegt der Name eine GPX-/FIT-Endung, muss sie zum Inhalt passen.
 *  3. Traegt der Name eine **andere** Endung (`foto.jpg`, `notiz.txt`), wird
 *     nur angenommen, wenn der MIME-Typ die Datei ausdruecklich als GPX/FIT
 *     ausweist — `application/octet-stream` allein reicht dann nicht.
 *  4. Ganz ohne Endung (Mailanhang ohne Namen) entscheidet der Inhalt.
 */
fun classifyActivityFile(input: ActivityFileInput): ActivityFileKind {
    val content = sniffActivityFileKind(input.bytes)
        ?: throw FormatException(NOT_AN_ACTIVITY_FILE_MESSAGE)
    val byName = activityKindFromName(input.displayName)
    if (byName != null) {
        if (byName != content) {
            throw FormatException(
                "Die Dateiendung passt nicht zum Inhalt — die Datei ist beschädigt oder umbenannt.",
            )
        }
        return content
    }
    if (hasForeignExtension(input.displayName)) {
        val mime = input.mimeType?.lowercase()?.substringBefore(';')?.trim()
        val byMime = when (mime) {
            in GPX_MIME_TYPES -> ActivityFileKind.GPX
            in FIT_MIME_TYPES -> ActivityFileKind.FIT
            else -> null
        }
        if (byMime != content) throw FormatException(NOT_AN_ACTIVITY_FILE_MESSAGE)
    }
    return content
}

/** true, wenn der Name eine Endung traegt, die nicht GPX/FIT ist. */
private fun hasForeignExtension(name: String?): Boolean {
    val file = name?.trim()?.substringAfterLast('/') ?: return false
    val dot = file.lastIndexOf('.')
    return dot > 0 && dot < file.length - 1
}

/**
 * Baut aus einer einzelnen Datei eine Tour (GPX ueber [rideFromGpx], FIT ueber
 * [rideFromFit]). Der Dateiname ohne Endung dient als Fallback-Name.
 */
fun rideFromActivityFile(input: ActivityFileInput, id: String? = null): Ride {
    input.readError?.let { throw FormatException(it) }
    val kind = classifyActivityFile(input)
    val fallbackName = input.displayName
        ?.let { archiveBaseName(it) }
        ?.takeIf { it.isNotBlank() }
        ?: "Importierte Tour"
    return when (kind) {
        ActivityFileKind.GPX ->
            rideFromGpx(gunzipIfNeeded(input.bytes).toString(Charsets.UTF_8), fallbackName, id)
        ActivityFileKind.FIT -> rideFromFit(input.bytes, fallbackName, id)
    }
}

/**
 * Sammelimport mehrerer Einzeldateien — das Gegenstueck zu [importArchive]
 * fuer „Teilen" und die Mehrfachauswahl, mit demselben Ergebnis-Typ.
 *
 * Eine unlesbare Datei bricht den Lauf nicht ab, sondern landet in
 * [BulkImportResult.errors]. Duplikate werden gegen [existing] **und** gegen
 * die im selben Lauf bereits gelesenen Touren geprueft — wer dieselbe Datei
 * zweimal markiert, bekommt sie einmal. Deshalb muss [existing] der
 * **geladene** Bestand sein; eine noch leere Liste liesse jede Dublette durch.
 */
fun importActivityFiles(
    files: List<ActivityFileInput>,
    existing: List<RideInfo> = emptyList(),
): BulkImportResult {
    val rides = mutableListOf<Ride>()
    val duplicates = mutableListOf<String>()
    val errors = mutableListOf<BulkImportError>()
    val seen = ArrayList<RideInfo>(existing)
    // Fortlaufende IDs aus demselben Grund wie in [importArchive]: Sonst
    // bekaemen alle Touren eines Millisekunden-Ticks dieselbe ID.
    val idBase = System.currentTimeMillis()
    files.forEachIndexed { index, file ->
        try {
            val ride = rideFromActivityFile(file, id = (idBase + index).toString())
            if (findDuplicateRide(seen, ride) != null) {
                duplicates.add(file.label)
            } else {
                rides.add(ride)
                seen.add(ride)
            }
        } catch (e: FormatException) {
            errors.add(BulkImportError(file.label, e.message ?: "Die Datei konnte nicht gelesen werden."))
        } catch (e: Exception) {
            errors.add(BulkImportError(file.label, "Die Datei konnte nicht gelesen werden."))
        }
    }
    return BulkImportResult(rides = rides, duplicates = duplicates, errors = errors)
}

/**
 * Die eine Zeile fuer die Snackbar nach einem Sammelimport, z. B.
 * „7 importiert · 1 schon vorhanden · 1 unlesbar". Leere Posten fallen weg.
 *
 * Bei genau **einer** Datei wird es konkreter: der Tourname (samt Hinweis,
 * wenn sie als Planung angekommen ist — sonst wundert man sich, warum sie
 * nicht in den Wochenkilometern auftaucht), die Duplikatmeldung oder der
 * eigentliche Fehlergrund statt eines blossen „1 unlesbar".
 */
fun bulkImportMessage(result: BulkImportResult): String {
    if (result.totalCount == 1) {
        result.rides.firstOrNull()?.let { ride ->
            return if (ride.planned) "„${ride.name}“ als Planung importiert" else "„${ride.name}“ importiert"
        }
        if (result.duplicateCount == 1) return DUPLICATE_RIDE_MESSAGE
        result.errors.firstOrNull()?.let { return it.message }
    }
    if (result.totalCount == 0) return "Keine Datei zum Importieren gefunden."
    return buildList {
        if (result.importedCount > 0) add("${result.importedCount} importiert")
        if (result.duplicateCount > 0) add("${result.duplicateCount} schon vorhanden")
        if (result.errorCount > 0) add("${result.errorCount} unlesbar")
    }.joinToString(" · ")
}

/**
 * Text fuer den stehenden Fehlerdialog, wenn von einem Import **keine** Datei
 * ankam (nichts importiert, nichts schon vorhanden) — sonst `null`, dann
 * genuegt [bulkImportMessage].
 *
 * Erst, was Trailscape liest, dann der Grund: bei einer Datei der konkrete,
 * bei mehreren der erste, damit der Dialog nicht zur Liste wird.
 */
fun bulkImportFailureText(result: BulkImportResult): String? {
    if (result.rides.isNotEmpty() || result.duplicates.isNotEmpty() || result.errors.isEmpty()) return null
    val intro = if (result.errorCount == 1) {
        "Die Datei konnte nicht importiert werden."
    } else {
        "Keine der ${result.errorCount} Dateien konnte importiert werden."
    }
    return "$intro Trailscape liest GPX- und FIT-Dateien, auch als .gz gepackt. " +
        "(${result.errors.first().message})"
}
