package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import kotlin.math.max

/**
 * Lokal gehaltene Historie der Vitaldaten (Ruhepuls, HRV, Schlaf, VO2max).
 *
 * ## Warum es diese Datei ueberhaupt gibt
 * Health Connect ist ein **Durchlauferhitzer**, kein Archiv: Die Plattform
 * loescht Datensaetze standardmaessig nach 30 Tagen. Die Baselines dieser App
 * brauchen aber deutlich mehr — die Ruhepuls-Baseline verlangt ≥ 21 gueltige
 * Werte im Fenster Tag −8 … −60, die HRV-Baseline ≥ 14 Werte im Fenster
 * −7 … −59. Wer bei jedem App-Start nur die letzten 60 Tage aus Health Connect
 * liest, sieht faktisch 30 Tage, davon vielleicht 23 verwertbare — der Hinweis
 * „Ruhepuls-Baseline wird aufgebaut (18 von 21 Tagen)" konnte damit
 * **monatelang** stehen bleiben, und ohne Gesamtscore gab es weder harte
 * Einheiten noch Ruhetage.
 *
 * Deshalb: Jeder Sync wird lokal angehaengt, und nachgeladen wird nur die
 * Luecke seit dem letzten Lauf. Das passt zum Local-first-Anspruch der App —
 * die Daten liegen ohnehin schon auf dem Geraet, sie waren nur nicht unser.
 *
 * ## Ablageform
 * Ein JSON-String im vorhandenen [KeyValueStore] (auf Android:
 * `SharedPreferences`, siehe `app/data/PrefsStores.kt`) unter
 * [vitalsHistoryStorageKey]. Eine eigene Datei waere hier Mehraufwand ohne
 * Gewinn: Bei [vitalsHistoryRetentionDays] Tagen × drei Serien reden wir ueber
 * gut 1.000 Zahlen, also wenige zehn Kilobyte — eine Groessenordnung, die
 * `SharedPreferences` problemlos traegt und die neben den Tourdateien nicht
 * ins Gewicht faellt. Der Backup-Export (`RideStorage`) bleibt davon
 * unberuehrt: Vitaldaten stehen weiterhin in Health Connect und werden von
 * dort neu geholt, falls dieser Cache verloren geht.
 *
 * ## Semantik
 * Append-only mit „ein Wert je Kalendertag, neuere gewinnen": Health Connect
 * kann einen Tag nachtraeglich korrigieren (die Uhr synchronisiert
 * verspaetet), und dann soll der neue Wert zaehlen. Geloescht wird nur, was
 * aelter als [vitalsHistoryRetentionDays] ist.
 */

/** Schluessel im [KeyValueStore]. Das `.v1` erlaubt spaeter ein neues Format. */
const val vitalsHistoryStorageKey: String = "trailscape.vitals.v1"

/**
 * Aufbewahrungsdauer der lokalen Tagesserien in Tagen.
 *
 * 400 Tage: Das laengste Baselinefenster sind 60 Tage, ein Jahresrueckblick
 * braucht 365 — mit etwas Reserve sind 400 der runde Wert, ab dem nichts mehr
 * dazugewinnt.
 */
const val vitalsHistoryRetentionDays: Int = 400

/**
 * Sicherheitsueberlappung beim Nachladen in Tagen.
 *
 * Der letzte Sync-Tag wird immer noch einmal mitgelesen: Ein Tag kann zum
 * Zeitpunkt des Syncs unvollstaendig gewesen sein (die Uhr schreibt den
 * Schlaf der Nacht erst am Vormittag), und ein zweiter Blick kostet nichts.
 */
const val vitalsSyncOverlapDays: Int = 2

/** Tagesserien plus der Stand, bis zu dem sie als vollstaendig gelten. */
data class VitalsHistory(
    val restingHeartRate: List<DailyValue> = emptyList(),
    val sleepHours: List<DailyValue> = emptyList(),
    val heartRateVariability: List<DailyValue> = emptyList(),
    val vo2max: Double? = null,
    val vo2maxAt: LocalDateTime? = null,
    /** Letzter Tag, den ein Sync abgedeckt hat; `null` = noch nie gelesen. */
    val syncedThroughDay: LocalDateTime? = null,
) {
    val isEmpty: Boolean
        get() = restingHeartRate.isEmpty() &&
            sleepHours.isEmpty() &&
            heartRateVariability.isEmpty() &&
            vo2max == null

    /**
     * Wie viele Tage der naechste Sync betrachten muss, damit keine Luecke
     * bleibt — inklusive [vitalsSyncOverlapDays] Ueberlappung, gedeckelt auf
     * [fullWindowDays].
     *
     * Ohne bisherigen Stand (Erststart, geleerter Speicher) ist das das volle
     * Fenster.
     */
    fun daysToFetch(now: LocalDateTime, fullWindowDays: Int): Int {
        val through = syncedThroughDay ?: return fullWindowDays
        val gap = dayDifference(now, through)
        if (gap < 0) {
            // Uhrzeit rueckwaerts gestellt oder Zeitzonenwechsel: lieber neu.
            return fullWindowDays
        }
        return (gap + 1 + vitalsSyncOverlapDays).coerceIn(1, fullWindowDays)
    }

    /**
     * Legt eine frisch gelesene Zusammenfassung ueber die Historie: Tage aus
     * [summary] gewinnen, alles andere bleibt stehen.
     *
     * Serien, die Health Connect gar nicht liefern konnte
     * ([VitalsSummary.unavailable]), werden **nicht** angefasst — sonst wuerde
     * eine entzogene Berechtigung die gesammelte Historie mit leeren Werten
     * ueberschreiben.
     */
    fun merge(summary: VitalsSummary, now: LocalDateTime = summary.to): VitalsHistory {
        val cutoff = addDays(atMidnight(now), -vitalsHistoryRetentionDays)
        fun combine(old: List<DailyValue>, fresh: List<DailyValue>, missing: Boolean) =
            if (missing) pruneDaily(old, cutoff) else mergeDaily(old, fresh, cutoff)

        return VitalsHistory(
            restingHeartRate = combine(
                restingHeartRate,
                summary.restingHeartRate.series,
                summary.unavailable.contains(VitalsDataKind.RUHEPULS),
            ),
            sleepHours = combine(
                sleepHours,
                summary.sleepHours.series,
                summary.unavailable.contains(VitalsDataKind.SCHLAF),
            ),
            heartRateVariability = combine(
                heartRateVariability,
                summary.heartRateVariability.series,
                summary.unavailable.contains(VitalsDataKind.HRV),
            ),
            vo2max = summary.vo2max ?: vo2max,
            vo2maxAt = if (summary.vo2max != null) summary.vo2maxAt else vo2maxAt,
            syncedThroughDay = atMidnight(now),
        )
    }

    /**
     * Baut aus der Historie wieder eine [VitalsSummary] — das Format, mit dem
     * der Rest der App arbeitet. [unavailable] wird durchgereicht, damit die
     * Diagnose im Mehr-Tab weiterhin stimmt.
     */
    fun toSummary(
        now: LocalDateTime,
        days: Int = vitalsHistoryRetentionDays,
        unavailable: Set<VitalsDataKind> = emptySet(),
    ): VitalsSummary {
        val today = atMidnight(now)
        val from = addDays(today, -(max(days, 1) - 1))
        fun window(series: List<DailyValue>) =
            series.filter { !atMidnight(it.day).isBefore(from) }

        return VitalsSummary(
            days = max(days, 1),
            from = from,
            to = now,
            restingHeartRate = buildVitalsTrend(window(restingHeartRate), now),
            sleepHours = buildVitalsTrend(window(sleepHours), now),
            heartRateVariability = buildVitalsTrend(window(heartRateVariability), now),
            vo2max = vo2max,
            vo2maxAt = vo2maxAt,
            unavailable = unavailable,
        )
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("restingHeartRate", dailyToJson(restingHeartRate))
        put("sleepHours", dailyToJson(sleepHours))
        put("heartRateVariability", dailyToJson(heartRateVariability))
        vo2max?.let { put("vo2max", it) }
        vo2maxAt?.let { put("vo2maxAtMs", dartEpochMs(it)) }
        syncedThroughDay?.let { put("syncedThroughMs", dartEpochMs(it)) }
    }

    companion object {
        val EMPTY = VitalsHistory()

        fun fromJson(json: JsonObject): VitalsHistory = VitalsHistory(
            restingHeartRate = dailyFromJson(json.fieldOrNull("restingHeartRate")),
            sleepHours = dailyFromJson(json.fieldOrNull("sleepHours")),
            heartRateVariability = dailyFromJson(json.fieldOrNull("heartRateVariability")),
            vo2max = json.optionalDouble("vo2max"),
            vo2maxAt = json.optionalLong("vo2maxAtMs")?.let { dartLocalOf(it) },
            syncedThroughDay = json.optionalLong("syncedThroughMs")?.let { dartLocalOf(it) },
        )
    }
}

/**
 * Fuegt [fresh] in [old] ein: ein Wert je Kalendertag, der neue gewinnt.
 * Werte aelter als [cutoff] fallen raus. Ergebnis ist aufsteigend sortiert.
 */
internal fun mergeDaily(
    old: List<DailyValue>,
    fresh: List<DailyValue>,
    cutoff: LocalDateTime,
): List<DailyValue> {
    val byDay = linkedMapOf<LocalDateTime, Double>()
    for (v in old) {
        if (!v.value.isFinite()) continue
        byDay[atMidnight(v.day)] = v.value
    }
    for (v in fresh) {
        if (!v.value.isFinite()) continue
        byDay[atMidnight(v.day)] = v.value
    }
    return byDay.keys
        .filter { !it.isBefore(cutoff) }
        .sorted()
        .map { DailyValue(day = it, value = byDay.getValue(it)) }
}

/** Wie [mergeDaily], nur ohne neue Werte — schneidet die Historie zurecht. */
internal fun pruneDaily(old: List<DailyValue>, cutoff: LocalDateTime): List<DailyValue> =
    mergeDaily(old, emptyList(), cutoff)

private fun dailyToJson(series: List<DailyValue>): JsonArray = buildJsonArray {
    for (v in series) {
        add(
            buildJsonObject {
                put("dayMs", dartEpochMs(v.day))
                put("value", v.value)
            },
        )
    }
}

private fun dailyFromJson(element: JsonElement?): List<DailyValue> {
    val array = element as? JsonArray ?: return emptyList()
    val out = mutableListOf<DailyValue>()
    for (entry in array) {
        val obj = entry as? JsonObject ?: continue
        val ms = obj.optionalLong("dayMs") ?: continue
        val value = obj.optionalDouble("value") ?: continue
        if (!value.isFinite()) continue
        out.add(DailyValue(day = atMidnight(dartLocalOf(ms)), value = value))
    }
    return out.sortedBy { it.day }
}

/**
 * Liest die Historie; ein kaputter oder fehlender Eintrag ergibt
 * [VitalsHistory.EMPTY] statt eines Fehlers — verlorene Historie ist
 * aergerlich, ein Absturz beim App-Start waere schlimmer.
 */
fun readVitalsHistory(store: KeyValueStore): VitalsHistory = runCatching {
    val raw = store.getString(vitalsHistoryStorageKey) ?: return VitalsHistory.EMPTY
    val json = Json.parseToJsonElement(raw) as? JsonObject
        ?: return VitalsHistory.EMPTY
    VitalsHistory.fromJson(json)
}.getOrDefault(VitalsHistory.EMPTY)

/** Schreibt die Historie zurueck. */
fun writeVitalsHistory(store: KeyValueStore, history: VitalsHistory) {
    store.setString(vitalsHistoryStorageKey, history.toJson().toString())
}

/** Nur fuer Tests/Diagnose: verwirft die lokale Historie. */
fun clearVitalsHistory(store: KeyValueStore) {
    store.remove(vitalsHistoryStorageKey)
}
