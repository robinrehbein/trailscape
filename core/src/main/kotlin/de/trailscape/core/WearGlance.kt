package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Serie und Wochenziel „auf einen Blick" fuer Kachel und Komplikation der Uhr
 * (Telefon → Uhr, Pfad [PFAD_WOCHE], als DataItem).
 *
 * ## Warum ein DataItem und keine Nachricht
 * Kachel und Zifferblatt muessen etwas zeigen, auch wenn die Uhr gerade nicht
 * mit dem Telefon verbunden ist und keine Aufzeichnung laeuft. Ein DataItem
 * bleibt im Data Layer liegen und wird bei der naechsten Verbindung
 * abgeglichen; eine Nachricht ginge ins Leere. Das Telefon rechnet (die Uhr
 * hat die Touren nicht, „local-first"), die Uhr bekommt nur dieses kleine,
 * fertige Ergebnis.
 *
 * ## Warum der Wochenstart mitreist
 * Die Uhr rechnet nichts nach, muss aber wissen, **fuer welche Woche** der
 * Stand gilt: Wurde er am Sonntag gesendet und die Uhr schaut am Dienstag,
 * waere „diese Woche erledigt" falsch. Deshalb steht der Montag der Woche
 * ([montagEpochTag]) im Stand, und [glanceFuerHeute] entscheidet auf der Uhr
 * rein daraus, was noch gilt.
 *
 * ## Versionierung
 * [version] steht im Telegramm. Eine Uhr dekodiert nur Versionen, die sie
 * kennt ([GLANCE_VERSION]); alles andere ergibt `null`, und die Uhr behaelt
 * den zuletzt gespeicherten Stand, statt falsche Zahlen zu zeigen.
 *
 * @property serieWochen Laenge der Wochenserie (siehe [weekStreak]), 0…[MAX_SERIE_WOCHEN].
 * @property wocheErledigt Diese Woche hat schon eine Tour.
 * @property wochenKm Kilometer dieser Woche, gerundet auf 0,1, 0…[MAX_KM].
 * @property zielKm Wochenziel in km oder `null` ohne Ziel (1…[MAX_KM]).
 * @property montagEpochTag Montag der Woche, fuer die der Stand gilt, als
 *   [LocalDate.toEpochDay] im Kalender des Telefons.
 * @property standMs Zeitpunkt der Berechnung (ms seit Epoch). Nur zur
 *   Diagnose; er zaehlt **nicht** zum Inhalt ([gleicherInhalt]).
 * @property heute der Drinnen/Draussen-Vorschlag zur Einheit des Tages
 *   ([HeuteVorschlag]), optional. Steht im Telegramm unter `heute` und wird
 *   nur geschrieben, wenn vorhanden — ohne ihn ist das Telegramm byte-gleich
 *   zum alten. Alte Uhren lesen Felder per Namen und uebergehen es; deshalb
 *   bleibt [GLANCE_VERSION] bei 1.
 */
data class WochenGlance(
    val serieWochen: Int,
    val wocheErledigt: Boolean,
    val wochenKm: Double,
    val zielKm: Int?,
    val montagEpochTag: Long,
    val standMs: Long,
    val version: Int = GLANCE_VERSION,
    val heute: HeuteVorschlag? = null,
) {
    /** Serie laeuft, ist diese Woche aber noch nicht verlaengert. */
    val serieInGefahr: Boolean get() = serieWochen > 0 && !wocheErledigt

    fun toJson(): JsonObject = buildJsonObject {
        put("v", version)
        put("serie", serieWochen)
        put("erledigt", wocheErledigt)
        put("km", wochenKm)
        zielKm?.let { put("ziel", it) }
        put("montag", montagEpochTag)
        put("stand", standMs)
        heute?.let { put("heute", it.toJson()) }
    }

    /** Gleich bis auf den Zeitstempel — dann lohnt kein erneutes Senden. */
    fun gleicherInhalt(andere: WochenGlance?): Boolean =
        andere != null && copy(standMs = 0L) == andere.copy(standMs = 0L)

    companion object {
        /** Oberer Rand fuer die Serie: 100 Jahre. Alles darueber ist ein kaputtes Telegramm. */
        const val MAX_SERIE_WOCHEN = 5200

        /** Oberer Rand fuer Kilometer und Ziel einer Woche (kein Mensch faehrt mehr). */
        const val MAX_KM = 10_000

        /** Zehntel-Kilometer auf dem Draht: eine Nachkommastelle reicht der Anzeige. */
        private const val KM_RUNDUNG = 10.0

        /**
         * Baut den Stand fuer [heute]. Werte ausserhalb der Grenzen werden
         * eingeklemmt, damit der Sender nie etwas verschickt, das die Uhr
         * wieder verwirft; ein Ziel ausserhalb 1…[MAX_KM] gilt als „kein Ziel".
         */
        fun von(
            serieWochen: Int,
            wocheErledigt: Boolean,
            wochenKm: Double,
            zielKm: Int?,
            heute: LocalDate,
            standMs: Long,
            vorschlag: HeuteVorschlag? = null,
        ): WochenGlance = WochenGlance(
            serieWochen = serieWochen.coerceIn(0, MAX_SERIE_WOCHEN),
            wocheErledigt = wocheErledigt,
            wochenKm = rundeKm(wochenKm.takeIf { it.isFinite() } ?: 0.0),
            zielKm = zielKm?.takeIf { it in 1..MAX_KM },
            montagEpochTag = heute.with(DayOfWeek.MONDAY).toEpochDay(),
            standMs = standMs,
            heute = vorschlag?.takeIf { it.gueltig() },
        )

        private fun rundeKm(km: Double): Double =
            (km.coerceIn(0.0, MAX_KM.toDouble()) * KM_RUNDUNG).roundToInt() / KM_RUNDUNG
    }
}

/** Datenpfad des Wochenstands (Telefon → Uhr, DataItem). */
const val PFAD_WOCHE = "/trailscape/woche"

/** Aktuelle Telegramm-Version von [WochenGlance]. */
const val GLANCE_VERSION = 1

/** Kodiert [glance] als UTF-8-JSON fuer das DataItem unter [PFAD_WOCHE]. */
fun kodiereWochenGlance(glance: WochenGlance): ByteArray =
    glance.toJson().toString().toByteArray(StandardCharsets.UTF_8)

/**
 * Dekodiert ein DataItem unter [PFAD_WOCHE]. Wirft nie: kaputtes JSON, fremde
 * Version, fehlende Pflichtfelder, falsche Typen oder Werte ausserhalb der
 * Grenzen ergeben `null`. Die Uhr soll lieber nichts Neues anzeigen als etwas
 * Falsches; ein Absturz im Systemdienst waere noch schlimmer.
 */
fun dekodiereWochenGlance(bytes: ByteArray): WochenGlance? = runCatching {
    val json = Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
        ?: return null
    if (json.zahl("v")?.ganz() != GLANCE_VERSION) return null
    val serie = json.zahl("serie")?.ganz() ?: return null
    val km = json.zahl("km") ?: return null
    val montag = json.zahl("montag")?.ganzLong() ?: return null
    val stand = json.zahl("stand")?.ganzLong() ?: return null
    val erledigt = (json["erledigt"] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: return null
    val ziel = if ("ziel" in json) (json.zahl("ziel")?.ganz() ?: return null) else null

    if (serie !in 0..WochenGlance.MAX_SERIE_WOCHEN) return null
    if (!km.isFinite() || km < 0.0 || km > WochenGlance.MAX_KM) return null
    if (ziel != null && ziel !in 1..WochenGlance.MAX_KM) return null
    if (stand < 0L) return null
    // Ein Montag muss auch einer sein; sonst waere die Wochenpruefung sinnlos.
    if (LocalDate.ofEpochDay(montag).dayOfWeek != DayOfWeek.MONDAY) return null

    WochenGlance(
        serieWochen = serie,
        wocheErledigt = erledigt,
        wochenKm = km,
        zielKm = ziel,
        montagEpochTag = montag,
        standMs = stand,
        // Ein kaputter Vorschlag kostet nur den Vorschlag, nicht den Wochenstand.
        heute = (json["heute"] as? JsonObject)?.let(::dekodiereHeuteVorschlag),
    )
}.getOrNull()

// ---------------------------------------------------------------------------
// Vorschlag zur Einheit des Tages (Feld `heute`)
// ---------------------------------------------------------------------------

/**
 * Der Drinnen/Draussen-Vorschlag zur Einheit des Tages, wie „Heute" ihn
 * zuletzt gezeigt hat (`core/…/TodayPlace.kt`, samt Wahl des Nutzers). **Kein
 * Text**: Die Uhr bildet die Zeile in ihrer Sprache.
 *
 * Auf dem Draht: `{"tag": <Epochentag>, "ort": "drinnen"|"draussen",
 * "min": <Minuten>, "erledigt": true, "gewaehlt": true}`; `min`, `erledigt`
 * und `gewaehlt` nur, wenn vorhanden bzw. `true`. Unbekannte Werte von `ort`
 * ergeben keinen Vorschlag.
 *
 * @property tagEpochTag Kalendertag des Vorschlags ([LocalDate.toEpochDay]);
 *   die Uhr zeigt ihn nur an genau diesem Tag ([heuteFuerTag]).
 * @property drinnen `true` drinnen auf der Rolle, `false` draussen.
 * @property minuten Dauer der Einheit, 1…[MAX_MINUTEN], oder `null`.
 * @property erledigt die Einheit ist heute schon gefahren.
 * @property gewaehlt der Nutzer hat den Vorschlag umgedreht.
 */
data class HeuteVorschlag(
    val tagEpochTag: Long,
    val drinnen: Boolean,
    val minuten: Int? = null,
    val erledigt: Boolean = false,
    val gewaehlt: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tag", tagEpochTag)
        put("ort", if (drinnen) ORT_DRINNEN else ORT_DRAUSSEN)
        minuten?.let { put("min", it) }
        if (erledigt) put("erledigt", true)
        if (gewaehlt) put("gewaehlt", true)
    }

    /** Innerhalb der Grenzen, die auch der Dekoder prueft. */
    fun gueltig(): Boolean = minuten == null || minuten in 1..MAX_MINUTEN

    companion object {
        /** Mehr als zehn Stunden ist keine Tageseinheit, sondern ein kaputtes Telegramm. */
        const val MAX_MINUTEN = 600
        const val ORT_DRINNEN = "drinnen"
        const val ORT_DRAUSSEN = "draussen"
    }
}

/** Liest das Feld `heute`; tolerant, wirft nie, `null` bei allem Unpassenden. */
internal fun dekodiereHeuteVorschlag(json: JsonObject): HeuteVorschlag? = runCatching {
    val tag = json.zahl("tag")?.ganzLong() ?: return null
    val ort = (json["ort"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val drinnen = when (ort) {
        HeuteVorschlag.ORT_DRINNEN -> true
        HeuteVorschlag.ORT_DRAUSSEN -> false
        else -> return null
    }
    val minuten = if ("min" in json) {
        json.zahl("min")?.ganz()?.takeIf { it in 1..HeuteVorschlag.MAX_MINUTEN } ?: return null
    } else {
        null
    }
    fun flag(key: String): Boolean =
        (json[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: false
    HeuteVorschlag(tag, drinnen, minuten, erledigt = flag("erledigt"), gewaehlt = flag("gewaehlt"))
}.getOrNull()

/** [HeuteVorschlag] aus seinem JSON-Text (wie [HeuteVorschlag.toJson]); tolerant, `null` bei allem Unpassenden. */
fun parseHeuteVorschlag(text: String?): HeuteVorschlag? = runCatching {
    (Json.parseToJsonElement(text ?: return null) as? JsonObject)?.let(::dekodiereHeuteVorschlag)
}.getOrNull()

/** Der Vorschlag aus [stand], wenn er fuer [heute] gilt — sonst `null` (ein Vorschlag von gestern zeigt nichts). */
fun heuteFuerTag(stand: WochenGlance?, heute: LocalDate): HeuteVorschlag? =
    stand?.heute?.takeIf { it.tagEpochTag == heute.toEpochDay() }

/** Zahl ohne Anfuehrungszeichen, sonst `null`. */
private fun JsonObject.zahl(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

private fun Double.ganz(): Int? =
    takeIf { it == floor(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()

/** Grenze, bis zu der ein Double Ganzzahlen noch exakt traegt. */
private const val MAX_EXAKT = 9e15

private fun Double.ganzLong(): Long? = takeIf { it == floor(it) && abs(it) < MAX_EXAKT }?.toLong()

// ---------------------------------------------------------------------------
// Anzeige auf der Uhr
// ---------------------------------------------------------------------------

/**
 * Was die Uhr heute anzeigen darf, abgeleitet aus dem gespeicherten Stand.
 *
 * @property serieWochen Serie, soweit sie heute noch gilt.
 * @property wocheErledigt Nur `true`, wenn der Stand aus **dieser** Kalenderwoche ist.
 * @property serieInGefahr Serie laeuft, diese Woche ist aber noch keine Tour verbucht.
 * @property wochenKm Kilometer dieser Woche (0 in einer neuen Woche, bis das Telefon neu sendet).
 * @property zielKm Zuletzt bekanntes Wochenziel oder `null`.
 * @property veraltet Der Stand stammt aus einer frueheren Woche und wurde
 *   fortgeschrieben (nicht von einem aktuellen Telefon bestaetigt).
 */
data class GlanceAnzeige(
    val serieWochen: Int,
    val wocheErledigt: Boolean,
    val serieInGefahr: Boolean,
    val wochenKm: Double,
    val zielKm: Int?,
    val veraltet: Boolean,
) {
    /** Anteil 0…1 des Wochenziels; 0 ohne Ziel. */
    val anteil: Float
        get() = if (zielKm == null) 0f else (wochenKm / zielKm).coerceIn(0.0, 1.0).toFloat()

    val zielErreicht: Boolean get() = zielKm != null && wochenKm >= zielKm
}

/**
 * Entscheidet, was der gespeicherte [stand] am Tag [heute] (Kalender der Uhr,
 * Wochenstart Montag wie ueberall im Projekt) noch bedeutet.
 *
 * - **Gleiche Woche**: unveraendert.
 * - **Eine Woche aelter und dort erledigt**: Die Serie lebt noch (genau wie
 *   [weekStreak] am Montag nach einer erledigten Woche), diese Woche ist aber
 *   noch nicht erledigt und die Kilometer stehen auf 0 — „in Gefahr". Das
 *   alte Ziel bleibt als letzte bekannte Zahl stehen.
 * - **Eine Woche aelter, nicht erledigt, oder noch aelter**: Die Serie ist
 *   gerissen, sie zaehlt 0. Die Uhr behauptet lieber zu wenig als eine Serie,
 *   die es nicht mehr gibt.
 * - **Stand aus der Zukunft** (Uhr geht anders als das Telefon, Zeitzonen):
 *   unveraendert; Rechnen mit negativem Abstand waere Raterei.
 *
 * `null` als [stand] (noch nie Daten bekommen) liefert `null` — der Aufrufer
 * zeigt den Leerzustand.
 */
fun glanceFuerHeute(stand: WochenGlance?, heute: LocalDate): GlanceAnzeige? {
    if (stand == null) return null
    val dieseWoche = heute.with(DayOfWeek.MONDAY).toEpochDay()
    val abstandWochen = (dieseWoche - stand.montagEpochTag) / TAGE_PRO_WOCHE
    if (abstandWochen <= 0L) {
        return GlanceAnzeige(
            serieWochen = stand.serieWochen,
            wocheErledigt = stand.wocheErledigt,
            serieInGefahr = stand.serieInGefahr,
            wochenKm = stand.wochenKm,
            zielKm = stand.zielKm,
            veraltet = false,
        )
    }
    val serieLebt = abstandWochen == 1L && stand.wocheErledigt && stand.serieWochen > 0
    val serie = if (serieLebt) stand.serieWochen else 0
    return GlanceAnzeige(
        serieWochen = serie,
        wocheErledigt = false,
        serieInGefahr = serie > 0,
        wochenKm = 0.0,
        zielKm = stand.zielKm,
        veraltet = true,
    )
}

private const val TAGE_PRO_WOCHE = 7L
