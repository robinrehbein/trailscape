package de.trailscape.core

/**
 * Der Schalter „Wind berücksichtigen" der Rundkurs-Suche.
 *
 * Er ist die Einwilligung in die Windabfrage bei Open-Meteo (siehe
 * `WeatherClient.kt`) und deshalb **ab Werk aus**: Ein fehlender Schluessel
 * heisst aus, nur der exakte Wert „1" heisst an — ein Fremdwert wie „true"
 * zaehlt nicht als Einwilligung. Die Einstellung gilt global fuer jeden
 * Einstieg in die Suche (Karte, Heute, Training), weil der Controller in
 * `:app` sie bei jedem Start selbst liest. Liegt in `:core`, damit sie ohne
 * Android testbar ist; Muster wie der Entdeckt-Kachel-Schalter.
 */

/** Prefs-Schluessel des Schalters. */
const val ROUTE_WIND_STORAGE_KEY: String = "trailscape.route.wind.v1"

/** `true` genau dann, wenn der Schalter eingeschaltet wurde (Wert „1"). */
fun readRouteWindEnabled(store: KeyValueStore): Boolean =
    store.getString(ROUTE_WIND_STORAGE_KEY) == "1"

/** An schreibt „1", aus entfernt den Schluessel. */
fun writeRouteWindEnabled(store: KeyValueStore, enabled: Boolean) {
    if (enabled) {
        store.setString(ROUTE_WIND_STORAGE_KEY, "1")
    } else {
        store.remove(ROUTE_WIND_STORAGE_KEY)
    }
}
