package de.trailscape.app.challenge

import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.indoor.catalog.CatalogEntry
import de.trailscape.core.indoor.catalog.CatalogText

/**
 * Wie eine Herausforderung zur Strecke auf diesem Gerät passt.
 *
 * Eine geteilte Kurve gilt für **genau eine Version** einer Strecke: Hat der
 * Katalog sie seitdem neu gebaut, kann die Länge anders sein. Deshalb wird nur
 * mit gleicher Version mitgefahren (docs/konzept-spiel-community.md, 5.1).
 */
enum class ChallengeAvailability {
    /** Die Strecke liegt in der passenden Version bereit. */
    READY,

    /** Die Strecke fehlt oder ist älter: der vorhandene Ablauf „Laden"/„Aktualisieren" holt sie. */
    DOWNLOAD,

    /** Hier liegt eine **neuere** Version als die der Herausforderung: nicht fahrbar. */
    MISMATCH,

    /** Der Katalog kennt die Strecke nicht (zu alte App oder fremde Kennung). */
    UNKNOWN,
}

/**
 * @property name Anzeigename in beiden Sprachen; `null` bei der Teststrecke.
 * @property installedVersion installierte Version der Strecke, falls geladen.
 * @property distanceKm Länge laut Katalog, falls bekannt.
 * @property ascentM Anstieg laut Katalog, falls bekannt.
 */
data class ChallengeRouteInfo(
    val name: CatalogText?,
    val availability: ChallengeAvailability,
    val installedVersion: Int? = null,
    val distanceKm: Double? = null,
    val ascentM: Double? = null,
)

/**
 * Ordnet [route] dem Katalog zu. [entry] liefert den Katalogeintrag zur
 * Kennung, [installedVersion] die installierte Welt-Version (oder `null`).
 */
fun challengeRouteInfo(
    route: ChallengeRoute,
    entry: (String) -> CatalogEntry?,
    installedVersion: (String) -> Int?,
): ChallengeRouteInfo = when (route) {
    ChallengeRoute.TestWorld -> ChallengeRouteInfo(null, ChallengeAvailability.READY)
    is ChallengeRoute.Catalog -> {
        val e = entry(route.id)
        val installed = installedVersion(route.id)
        val availability = when {
            installed != null && installed == route.version -> ChallengeAvailability.READY
            installed != null && installed > route.version -> ChallengeAvailability.MISMATCH
            e == null -> ChallengeAvailability.UNKNOWN
            else -> ChallengeAvailability.DOWNLOAD
        }
        ChallengeRouteInfo(
            name = e?.name,
            availability = availability,
            installedVersion = installed,
            distanceKm = e?.let { it.lengthM / 1000.0 },
            ascentM = e?.ascentM,
        )
    }
}
