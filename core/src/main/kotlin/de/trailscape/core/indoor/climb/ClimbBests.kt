package de.trailscape.core.indoor.climb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Die eigene Bestzeit an einem Anstieg einer Strecke.
 *
 * @property routeKey Streckenkennung der App (`ride:<id>`, `catalog:<id>`,
 *   `testwelt`, `generated:<seed>`).
 * @property startM / [endM] Lage des Anstiegs beim Erreichen der Bestzeit.
 * @property timeMs Fahrzeit vom Fuss zur Kuppe (Pausen nicht mitgezaehlt).
 * @property splitsMs Zwischenzeiten an jedem Zehntel der Strecke
 *   (`SPLITS + 1` Werte, erster 0, letzter [timeMs]) — fuer den Abstand zur
 *   Bestzeit waehrend des Anstiegs; leer = unbekannt (dann gleichmaessig).
 * @property achievedAtEpochMs Wanduhrzeit der Fahrt.
 */
data class ClimbBest(
    val routeKey: String,
    val startM: Double,
    val endM: Double,
    val timeMs: Long,
    val splitsMs: List<Long>,
    val achievedAtEpochMs: Long,
) {
    val key: String get() = "$routeKey@${Math.round(startM / 10.0) * 10}-${Math.round(endM / 10.0) * 10}"

    /** Zwischenzeit der Bestzeit bei Anteil [fraction] (0..1) des Anstiegs, linear zwischen den Zehnteln. */
    fun splitAt(fraction: Double): Double {
        val f = fraction.coerceIn(0.0, 1.0)
        if (splitsMs.size != SPLITS + 1) return timeMs * f
        val x = f * SPLITS
        val i = x.toInt().coerceAtMost(SPLITS - 1)
        val t = x - i
        return splitsMs[i] + (splitsMs[i + 1] - splitsMs[i]) * t
    }

    fun matches(routeKey: String, climb: Climb): Boolean =
        this.routeKey == routeKey &&
            abs(startM - climb.startM) <= MATCH_TOLERANCE_M &&
            abs(endM - climb.endM) <= MATCH_TOLERANCE_M

    companion object {
        /** Anzahl der Abschnitte fuer Zwischenzeiten. */
        const val SPLITS: Int = 10

        /** Verschiebung von Fuss und Kuppe, bis zu der ein Anstieg derselbe bleibt. */
        const val MATCH_TOLERANCE_M: Double = 150.0
    }
}

/**
 * Alle Bestzeiten an Anstiegen — lokal gespeichert, im Backup enthalten.
 *
 * ## Format (stabil, Version [FORMAT])
 * ```
 * {"format":1,"bests":[{"route":"ride:abc","startM":1250.0,"endM":3400.0,
 *   "timeMs":512345,"splitsMs":[0,…,512345],"atMs":1764000000000}]}
 * ```
 * Unbekannte Felder werden ignoriert, kaputte Eintraege uebersprungen; eine
 * hoehere Formatversion wird trotzdem gelesen, soweit die Felder passen
 * (Bestzeiten sind kein Grund, ein Backup abzulehnen).
 */
data class ClimbBestBook(val bests: List<ClimbBest> = emptyList()) {
    /** Die Bestzeit zu [climb] auf [routeKey] (bei mehreren passenden die schnellste), `null` = keine. */
    fun bestFor(routeKey: String, climb: Climb): ClimbBest? =
        bests.filter { it.matches(routeKey, climb) }.minByOrNull { it.timeMs }

    /**
     * Traegt [candidate] ein, wenn sie schneller ist als die passende
     * Bestzeit (oder es keine gibt). Ersetzt alle passenden Eintraege.
     */
    fun record(candidate: ClimbBest): ClimbBestBook {
        val probe = probeClimb(candidate)
        val existing = bestFor(candidate.routeKey, probe)
        if (existing != null && existing.timeMs <= candidate.timeMs) return this
        return ClimbBestBook(bests.filterNot { it.matches(candidate.routeKey, probe) } + candidate)
    }

    /** Fuehrt zwei Buecher zusammen (Backup-Import): je Anstieg gewinnt die schnellere Zeit. */
    fun merge(other: ClimbBestBook): ClimbBestBook = other.bests.fold(this) { acc, b -> acc.record(b) }

    fun isEmpty(): Boolean = bests.isEmpty()

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put(
            "bests",
            buildJsonArray {
                bests.sortedWith(compareBy({ it.routeKey }, { it.startM })).forEach { b ->
                    add(
                        buildJsonObject {
                            put("route", b.routeKey)
                            put("startM", b.startM)
                            put("endM", b.endM)
                            put("timeMs", b.timeMs)
                            put("splitsMs", buildJsonArray { b.splitsMs.forEach { add(JsonPrimitive(it)) } })
                            put("atMs", b.achievedAtEpochMs)
                        },
                    )
                }
            },
        )
    }

    fun toJson(): String = toJsonObject().toString()

    companion object {
        const val FORMAT: Int = 1

        /** Liest ein Buch; `null`/kaputt = leeres Buch. */
        fun fromJson(text: String?): ClimbBestBook {
            if (text.isNullOrBlank()) return ClimbBestBook()
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return ClimbBestBook()
            return fromJsonObject(o)
        }

        fun fromJsonObject(o: JsonObject?): ClimbBestBook {
            val arr = o?.get("bests") as? JsonArray ?: return ClimbBestBook()
            val list = arr.mapNotNull { e -> runCatching { parseBest(e as JsonObject) }.getOrNull() }
            return list.fold(ClimbBestBook()) { acc, b -> acc.record(b) }
        }

        private fun parseBest(o: JsonObject): ClimbBest? {
            val route = (o["route"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
            val start = (o["startM"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 } ?: return null
            val end = (o["endM"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > start } ?: return null
            val time = (o["timeMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
            val splits = (o["splitsMs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
                ?.takeIf { s -> s.size == ClimbBest.SPLITS + 1 && s.zipWithNext().all { (a, b) -> b >= a } }
                ?: emptyList()
            val at = (o["atMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            return ClimbBest(route, start, end, time, splits, at)
        }

        /** Ein Anstieg mit Lage der Bestzeit — fuer die Zuordnung in [record]. */
        private fun probeClimb(b: ClimbBest) = Climb(
            index = 0, startM = b.startM, endM = b.endM, gainM = 1.0, avgGradePct = 0.0, maxGradePct = 0.0,
            startEleM = 0.0, topEleM = 0.0, category = ClimbCategory.UNCATEGORIZED,
        )
    }
}
