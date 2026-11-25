package de.trailscape.app.ui.indoor

import de.trailscape.app.indoor.ClimbBestStore
import de.trailscape.core.KeyValueStore
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.climb.ClimbEffort
import de.trailscape.core.indoor.climb.TrailLive
import de.trailscape.core.indoor.climb.TrailTracker
import de.trailscape.core.indoor.climb.detectClimbs

/**
 * # „Leben auf der Strecke" im Indoor-Ablauf (Bergwertung, Kilometer)
 *
 * Duenne Huelle um den reinen [TrailTracker] aus `:core`: legt ihn je Fahrt
 * an, fuettert ihn mit jedem Zustand der Sitzung und schreibt neue
 * Bestzeiten sofort in den [ClimbBestStore]. Das `IndoorViewModel` ruft nur
 * [start], [update], [efforts] und [stop].
 *
 * Gemessen wird immer, auch wenn die Einblendungen ausgeschaltet sind
 * ([KEY_OVERLAYS]) — sonst gaebe es Luecken in den Bestzeiten und der
 * Auswertung. Abgeschaltet wird nur die Anzeige waehrend der Fahrt.
 *
 * Jeder Fehler hier bleibt hier: Die Fahrt laeuft ohne Bergwertung weiter.
 */
internal class IndoorTrailController(
    private val store: KeyValueStore,
    private val wallClock: () -> Long,
) {
    private var tracker: TrailTracker? = null

    /**
     * Neue Fahrt auf [route]. [routeKey] ist die Streckenkennung der
     * Streckenwahl (`IndoorRouteOption.key`) — der Schluessel der Bestzeiten.
     */
    fun start(route: IndoorRoute, routeKey: String) {
        tracker = runCatching {
            TrailTracker(
                routeKey = routeKey,
                climbs = detectClimbs(route),
                routeLengthM = route.lengthM,
                isLoop = route.isLoop,
                bests = ClimbBestStore.read(store),
            )
        }.getOrNull()
    }

    /** Ein Zustand der Sitzung; liefert, was zu zeigen ist (`null` = nichts). */
    fun update(session: IndoorSessionState?): TrailLive? {
        val t = tracker ?: return null
        val s = session ?: return null
        return runCatching {
            val live = t.update(
                nowMs = s.nowMs,
                running = s.phase == IndoorPhase.RUNNING,
                routeDistM = s.routeDistM,
                gradePct = s.gradePct,
                epochMs = wallClock(),
            )
            val fresh = t.drainNewBests()
            if (fresh.isNotEmpty()) ClimbBestStore.record(store, fresh)
            live.takeIf { it.climb != null || it.marker != null }
        }.getOrNull()
    }

    /** Die gefahrenen Anstiege dieser Fahrt (fuer die Auswertung). */
    fun efforts(): List<ClimbEffort> = runCatching { tracker?.efforts() }.getOrNull().orEmpty()

    fun stop() {
        tracker = null
    }

    companion object {
        /** Schalter „Einblendungen auf der Strecke" (Vorgabe an). */
        const val KEY_OVERLAYS: String = "trailscape.indoor.trail_overlays"

        fun readOverlays(store: KeyValueStore): Boolean =
            runCatching { store.getString(KEY_OVERLAYS) != "false" }.getOrDefault(true)

        fun writeOverlays(store: KeyValueStore, enabled: Boolean) {
            runCatching { store.setString(KEY_OVERLAYS, enabled.toString()) }
        }
    }
}
