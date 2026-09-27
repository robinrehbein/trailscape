package de.trailscape.app.strava

import de.trailscape.app.BuildConfig
import de.trailscape.core.StravaAppCredentials

/**
 * Ob dieser Build Strava kann.
 *
 * Die Zugangsdaten der Strava-API-App kommen beim Bauen aus der Umgebung
 * (siehe `app/build.gradle.kts`) und stehen nie im Repository. Fehlen sie —
 * lokale Builds, Forks, PR-Laeufe der CI —, ist [available] `false` und die
 * ganze Funktion unsichtbar: keine Einstellungsseite, keine Aktion in der
 * Tour, kein registriertes Rueckruf-Schema.
 */
object StravaConfig {
    val credentials: StravaAppCredentials? by lazy {
        stravaCredentialsOrNull(BuildConfig.STRAVA_CLIENT_ID, BuildConfig.STRAVA_CLIENT_SECRET)
    }

    val available: Boolean get() = credentials != null
}

/** Beide Werte gesetzt → Zugangsdaten (getrimmt), sonst `null`. */
internal fun stravaCredentialsOrNull(id: String, secret: String): StravaAppCredentials? {
    val cleanId = id.trim()
    val cleanSecret = secret.trim()
    if (cleanId.isEmpty() || cleanSecret.isEmpty()) return null
    return StravaAppCredentials(clientId = cleanId, clientSecret = cleanSecret)
}
