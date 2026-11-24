package de.trailscape.core.indoor.world

/**
 * Oeffentliche Lesefassung von [WORLD_BUILDER_ID] fuer den Server-Bau
 * (docs/indoor/serverbau.md): App und Bau-Dienst muessen dieselbe Welt-Version
 * sprechen, sonst passt die Welt-ID nicht und das Paket waere fuer die App eine
 * fremde Welt. Nur gelesen — die Konstante selbst bleibt, wo sie ist.
 */
val worldBuilderVersion: String get() = WORLD_BUILDER_ID

/** Laufende Nummer einer Welt-Version (`indoor-world/9` → 9), `null` bei fremdem Aufbau. */
fun worldBuilderGeneration(id: String): Int? =
    Regex("^indoor-world/(\\d{1,6})$").find(id)?.groupValues?.get(1)?.toIntOrNull()
