package de.trailscape.core

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # Was von den Erfolgen gespeichert wird
 *
 * Fast alles an den Erfolgen ist abgeleitet (`Achievements.kt`). Gespeichert
 * wird nur, was sich **nicht** aus Touren, Plan und Jahresziel ergibt:
 *
 * @property keptLevel hoechste je angezeigte Stufe — eine geaenderte Formel
 *   oder ein ersetzter Plan stuft niemanden herab (Konzept 2.3).
 * @property pointsSince ab diesem Tag gibt es Punkte; frueher datierte
 *   (importierte) Touren zaehlen nur fuer Abzeichen (Konzept 2.7). `null` =
 *   noch nicht festgelegt, dann zaehlen alle.
 * @property pauseWeeks vom Nutzer markierte Pausenwochen (Montage, Konzept 2.2).
 *   Gespeichert wird, was markiert wurde; was davon gilt, rechnet
 *   [acceptedPauseWeeks].
 * @property earned Abzeichen-Schluessel ([AchievementId.key]) → Tag des
 *   Erreichens. Haelt fest, was sich spaeter nicht mehr ableiten laesst
 *   (frueherer Plan, geloeschte Tour). Unbekannte Schluessel aus neueren
 *   Versionen bleiben erhalten.
 * @property seen Abzeichen, die als „neu" schon gezeigt wurden.
 * @property hidden „Erfolge anzeigen" ist aus.
 *
 * ## Format
 * Ein JSON-Objekt, im Schluessel-Wert-Speicher wie im Backup (Schluessel
 * `progress`) dasselbe:
 * ```json
 * {"version":1,"keptLevel":3,"pointsSince":"2026-01-05","pauseWeeks":["2026-08-03"],
 *  "earned":{"first_ride":"2026-01-06"},"seen":["first_ride"],"hidden":true}
 * ```
 * `pointsSince` steht nur mit Wert, `hidden` nur, wenn `true`. Gelesen wird
 * **tolerant** ([fromJson]): Unbekannte Schluessel werden ignoriert, ein
 * kaputter Eintrag faellt einzeln weg, eine hoehere `version` wird gelesen,
 * soweit die Schluessel bekannt sind. Neue Felder kommen nur hinzu.
 */
data class AchievementProgressData(
    val keptLevel: Int = 1,
    val pointsSince: LocalDate? = null,
    val pauseWeeks: Set<LocalDate> = emptySet(),
    val earned: Map<String, LocalDate> = emptyMap(),
    val seen: Set<String> = emptySet(),
    val hidden: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", ACHIEVEMENT_PROGRESS_VERSION)
        put("keptLevel", keptLevel)
        pointsSince?.let { put("pointsSince", it.toString()) }
        put("pauseWeeks", buildJsonArray { pauseWeeks.sorted().forEach { add(JsonPrimitive(it.toString())) } })
        put(
            "earned",
            buildJsonObject { earned.toSortedMap().forEach { (key, date) -> put(key, date.toString()) } },
        )
        put("seen", buildJsonArray { seen.sorted().forEach { add(JsonPrimitive(it)) } })
        if (hidden) put("hidden", true)
    }

    /** Kompakte Zeile fuer den Schluessel-Wert-Speicher. */
    fun encode(): String = Json.encodeToString(JsonObject.serializer(), toJson())

    /**
     * Zusammenfuehren mit einem Stand aus einem Backup: hoechste Stufe,
     * fruehester Punktebeginn, alle Pausen, je Abzeichen das frueheste Datum,
     * alles Gesehene. [hidden] gilt vom wiederhergestellten Stand.
     */
    fun mergedWith(other: AchievementProgressData): AchievementProgressData = AchievementProgressData(
        keptLevel = maxOf(keptLevel, other.keptLevel),
        pointsSince = listOfNotNull(pointsSince, other.pointsSince).minOrNull(),
        pauseWeeks = pauseWeeks + other.pauseWeeks,
        earned = (earned.keys + other.earned.keys).associateWith { key ->
            listOfNotNull(earned[key], other.earned[key]).min()
        },
        seen = seen + other.seen,
        hidden = other.hidden,
    )

    /**
     * Haelt fest, was [overview] erreicht zeigt und noch nicht in [earned]
     * steht, und hebt [keptLevel] auf die angezeigte Stufe. Gibt `this`
     * zurueck, wenn sich nichts aendert — der Aufrufer schreibt dann nicht.
     *
     * @param fallbackDate Tag fuer Abzeichen ohne bekannten Tag (Kacheln, Wege).
     */
    fun recording(overview: AchievementOverview, fallbackDate: LocalDate): AchievementProgressData {
        val newEarned = overview.states
            .filter { it.reached && it.id.key !in earned }
            .associate { it.id.key to (it.date ?: fallbackDate) }
        val level = maxOf(keptLevel, overview.level.level)
        if (newEarned.isEmpty() && level == keptLevel) return this
        return copy(keptLevel = level, earned = earned + newEarned)
    }

    /** Markiert [ids] als gesehen. */
    fun seeing(ids: Collection<AchievementId>): AchievementProgressData {
        val keys = ids.map { it.key }
        return if (seen.containsAll(keys)) this else copy(seen = seen + keys)
    }

    /** Markiert die Woche von [date] als Pause bzw. hebt die Markierung auf. */
    fun togglingPause(date: LocalDate): AchievementProgressData {
        val monday = date.with(java.time.DayOfWeek.MONDAY)
        return copy(pauseWeeks = if (monday in pauseWeeks) pauseWeeks - monday else pauseWeeks + monday)
    }

    companion object {
        /**
         * Liest einen Stand tolerant; `null` nur, wenn [element] gar kein
         * Objekt ist (fehlt, `null`, Text, Zahl).
         */
        fun fromJson(element: JsonElement?): AchievementProgressData? {
            val obj = element as? JsonObject ?: return null
            return AchievementProgressData(
                keptLevel = (obj["keptLevel"] as? JsonPrimitive)?.takeUnless { it.isString }
                    ?.content?.toIntOrNull()?.coerceIn(1, maxAchievementLevel) ?: 1,
                pointsSince = dateOrNull(obj["pointsSince"]),
                pauseWeeks = (obj["pauseWeeks"] as? JsonArray).orEmpty()
                    .mapNotNull { dateOrNull(it)?.with(java.time.DayOfWeek.MONDAY) }
                    .toSet(),
                earned = (obj["earned"] as? JsonObject).orEmpty()
                    .mapNotNull { (key, value) -> dateOrNull(value)?.let { key to it } }
                    .filter { it.first.isNotBlank() }
                    .toMap(),
                seen = (obj["seen"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    .filter { it.isNotBlank() }
                    .toSet(),
                hidden = (obj["hidden"] as? JsonPrimitive)?.takeUnless { it.isString }?.content == "true",
            )
        }

        /** Gegenstueck zu [encode]; `null` bei fehlendem oder unlesbarem Text. */
        fun decode(text: String?): AchievementProgressData? {
            if (text.isNullOrBlank()) return null
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
            return fromJson(element)
        }

        private fun dateOrNull(element: JsonElement?): LocalDate? {
            val p = element as? JsonPrimitive ?: return null
            if (!p.isString) return null
            return runCatching { LocalDate.parse(p.content) }.getOrNull()
        }
    }
}

/** Formatversion von [AchievementProgressData.toJson]. */
const val ACHIEVEMENT_PROGRESS_VERSION: Int = 1
