package de.trailscape.tool.indoor

import de.trailscape.core.indoor.world.AerialSource

/** Cache der Geodaten des Werkzeugs (git-ignoriert). */
const val CACHE_DIR_TOOL = "tool/indoor-dev/cache"

/**
 * Luftbild-Dienste **nur fuer das Vorbauen** der Katalogwelten
 * (docs/indoor/catalog.md, Pruefung in `catalog/LUFTBILD.md`). Die App kennt
 * sie nicht: Ihr Register (`aerialSources` in `:core`) und ihre Positivliste
 * bleiben unveraendert, ob sie dort freigeschaltet werden, entscheidet der
 * Gruender. Die Quellenangabe jedes genutzten Dienstes landet ueber das
 * Manifest in der Welt und damit in der App.
 */
val catalogAerialSources: List<AerialSource> = listOf()
