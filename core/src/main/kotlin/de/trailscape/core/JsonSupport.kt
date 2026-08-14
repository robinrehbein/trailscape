package de.trailscape.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Kleine Hilfsfunktionen fuer das manuelle Auf-/Abbauen von JSON, das
 * bytegetreu kompatibel zu `lib/models.dart` sein muss.
 *
 * Bewusst kein `@Serializable`-Datenklassen-Mapping: Dart unterscheidet pro
 * Feld, ob ein fehlender/`null`-Wert als fehlender Schluessel (z. B.
 * `TrackPoint.hr`) oder als explizites JSON-`null` (z. B.
 * `RideStats.durationS`) geschrieben wird, und ob beim Lesen ein fehlendes
 * Pflichtfeld hart wirft (`as num`) oder auf einen Default faellt
 * (`as num? ?? 0`). Diese Nuancen lassen sich mit generierten Serializern
 * nur schwer 1:1 abbilden, mit expliziten Feldzugriffen pro Typ dagegen
 * exakt.
 */

/** Fehlerklasse fuer Pflichtfelder, die in Dart einen harten Cast-Fehler ausloesen wuerden. */
class MissingOrInvalidFieldException(message: String) : IllegalArgumentException(message)

private fun missing(key: String): Nothing =
    throw MissingOrInvalidFieldException("Pflichtfeld '$key' fehlt oder hat einen falschen Typ")

/** Liefert das Element zu [key], wobei ein JSON-`null` wie ein fehlender Schluessel behandelt wird. */
internal fun JsonObject.fieldOrNull(key: String): JsonElement? =
    this[key]?.takeUnless { it is JsonNull }

private fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? =
    fieldOrNull(key)?.let { it as? JsonPrimitive ?: missing(key) }

internal fun JsonObject.requiredString(key: String): String {
    val prim = primitiveOrNull(key) ?: missing(key)
    if (!prim.isString) missing(key)
    return prim.content
}

/**
 * Optionaler String — fehlender Schluessel, JSON-`null` oder ein nicht-String
 * ergeben `null`.
 *
 * Bewusst tolerant statt [requiredString]: Die Felder, die darueber gelesen
 * werden, sind **nachtraeglich** ergaenzt worden (z. B.
 * [TrainingSession.intensity]). Eine aeltere Datei kennt sie nicht, und das ist
 * kein Fehler, sondern der Normalfall beim Lesen alter Plaene.
 */
internal fun JsonObject.optionalString(key: String): String? =
    primitiveOrNull(key)?.takeIf { it.isString }?.content

/**
 * Optionaler Wahrheitswert — `null`, wenn der Schluessel fehlt.
 *
 * Akzeptiert sowohl `true`/`false` als auch die Strings `"true"`/`"false"`,
 * weil Dart je nach Codierweg beides erzeugen kann. Alles andere ergibt `null`
 * und ueberlaesst dem Aufrufer den Default — genau die Semantik, die ein
 * nachtraeglich ergaenztes Feld braucht (siehe [Ride.planned]).
 */
internal fun JsonObject.optionalBoolean(key: String): Boolean? =
    primitiveOrNull(key)?.content?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

internal fun JsonObject.requiredDouble(key: String): Double =
    primitiveOrNull(key)?.content?.toDoubleOrNull() ?: missing(key)

internal fun JsonObject.optionalDouble(key: String): Double? =
    primitiveOrNull(key)?.content?.toDoubleOrNull()

internal fun JsonObject.requiredInt(key: String): Int =
    requiredDouble(key).toInt()

internal fun JsonObject.optionalInt(key: String): Int? =
    optionalDouble(key)?.toInt()

internal fun JsonObject.requiredLong(key: String): Long =
    // Long zuerst exakt aus dem Text parsen (Dart-Zeitstempel sind ganze
    // Zahlen); Fallback ueber Double nur fuer den (in der Praxis nicht
    // vorkommenden) Fall eines nicht-ganzzahligen Werts.
    primitiveOrNull(key)?.content?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
        ?: missing(key)

internal fun JsonObject.optionalLong(key: String): Long? =
    primitiveOrNull(key)?.content?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

internal fun JsonObject.requiredObject(key: String): JsonObject =
    fieldOrNull(key) as? JsonObject ?: missing(key)

internal fun JsonObject.requiredArray(key: String): JsonArray =
    fieldOrNull(key) as? JsonArray ?: missing(key)

internal fun JsonElement.asRequiredObject(): JsonObject =
    this as? JsonObject ?: throw MissingOrInvalidFieldException("Erwartetes JSON-Objekt fehlt oder hat falschen Typ")
