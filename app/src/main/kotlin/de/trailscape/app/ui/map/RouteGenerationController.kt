package de.trailscape.app.ui.map

import android.content.Context
import de.trailscape.app.data.AppServices
import de.trailscape.app.routing.missingSegmentsFor
import de.trailscape.app.routing.planRouteOfflineFirst
import de.trailscape.core.RouteCandidate
import de.trailscape.core.RouteProfile
import de.trailscape.core.RouteTarget
import de.trailscape.core.RoutingBackend
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.generateRoutes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Haelt die Rundkurs-Suche **ausserhalb** der Komposition — genau aus dem
 * Grund, aus dem das auch [OfflineDownloadController] tut.
 *
 * `generateRoutes` aus `:core` macht bis zu neun sequenzielle Routing-Aufrufe
 * und braucht real 20–40 s. Ein `rememberCoroutineScope()` des Karten-Screens
 * stirbt aber, sobald der `NavHost` den Screen beim Tab-Wechsel entsorgt: Wer
 * waehrend der Suche kurz in den Trainings-Tab schaut, kaeme zurueck und faende
 * nichts vor. Deshalb laeuft die Suche in [AppServices.appScope] (der ohnehin
 * auf [Dispatchers.IO] liegt), und der Screen liest nur [state].
 *
 * Aus demselben Grund liegt hier auch das **Ergebnis** und nicht im Screen: Die
 * Kandidatenliste, die Auswahl und der Seed ueberleben so den Tab-Wechsel. Der
 * Screen spiegelt lediglich die aktuell ausgewaehlte Route in seinen
 * Planungszustand (siehe `MapScreen.kt`).
 *
 * ## Offline zuerst — wie die manuelle Planung
 * Geroutet wird nicht mehr direkt gegen den BRouter-Server, sondern ueber das
 * [RoutingBackend] von `generateRoutes`, das hier mit
 * [planRouteOfflineFirst] verdrahtet ist: Liegen die Kacheln der Gegend auf
 * dem Geraet, rechnen die Kandidaten lokal, sonst faellt jeder Aufruf still
 * auf den Server zurueck — exakt das Verhalten der manuellen Planung, mit
 * demselben [de.trailscape.core.RouteProfile] aus dem Planungsblatt statt
 * frueher hart Gravel.
 *
 * ## Abbrechen
 * Der `sleeper`-Parameter von `generateRoutes` wird vor *jedem* Routing-Aufruf
 * ausser dem allerersten aufgerufen — ausserhalb des `try`, mit dem die
 * Funktion einzelne Kandidaten abfaengt. Wirft er, verlaesst der Aufruf die
 * Generierung sofort; genau das tut [cancel] ueber [AtomicBoolean]. Das Warten
 * selbst laeuft ueber `delay()` und ist damit zusaetzlich kooperativ
 * abbrechbar, falls der umgebende Scope stirbt.
 *
 * Steckt die Suche dagegen gerade **in** einem Routing-Aufruf (Server-Request
 * oder lokale Engine, beides blockierend und ohne Unterbrechungspunkt), laeuft
 * dieser zu Ende; sein Ergebnis wird verworfen. Die Oberflaeche ist trotzdem
 * sofort wieder frei — der Zustand geht bei [cancel] unmittelbar auf
 * `running = false`, und die abgebrochene Coroutine schreibt danach nichts
 * mehr in [state] (jeder Schreibzugriff prueft ihr eigenes Abbruch-Flag).
 */
object RouteGenerationController {

    /** Wie viele Vorschlaege je Durchlauf gesucht werden. */
    const val CANDIDATE_COUNT: Int = 3

    private val _state = MutableStateFlow(RouteGenerationState())

    /** Zustand des Generierungs-Panels; `target == null` heisst „Panel zu". */
    val state: StateFlow<RouteGenerationState> = _state.asStateFlow()

    /** Abbruch-Flag des gerade laufenden Durchlaufs. */
    private var cancelFlag: AtomicBoolean? = null

    /** Startpunkt des letzten Durchlaufs — „Andere Vorschläge" benutzt ihn erneut. */
    private var lastStart: TrackPoint? = null

    /** Application-Context des letzten Durchlaufs (fuer [nextSuggestions]). */
    private var lastContext: Context? = null

    /** Routenprofil des letzten Durchlaufs (fuer [nextSuggestions]). */
    private var lastProfile: RouteProfile = RouteProfile.SCHOTTER

    /** Kachel-Angebots-Kanal des letzten Durchlaufs (fuer [nextSuggestions]). */
    private var lastOfferMissingSegments: (List<String>) -> Unit = {}

    /**
     * Oeffnet das Panel fuer ein neues Ziel und verwirft alles Bisherige
     * (laufende Suche inklusive). Wird vom Karten-Screen gerufen, sobald er
     * `AppViewModel.pendingRouteTarget` abholt.
     */
    fun open(target: RouteTarget) {
        cancelFlag?.set(true)
        cancelFlag = null
        clearLastRun()
        _state.value = RouteGenerationState(target = target)
    }

    /** Schliesst das Panel und bricht eine laufende Suche ab. */
    fun close() {
        cancelFlag?.set(true)
        cancelFlag = null
        clearLastRun()
        _state.value = RouteGenerationState()
    }

    private fun clearLastRun() {
        lastStart = null
        lastContext = null
        lastProfile = RouteProfile.SCHOTTER
        lastOfferMissingSegments = {}
    }

    /**
     * Startet die Suche ab [start].
     *
     * @param context nur fuer das Offline-Routing (Kachelverzeichnis,
     *   Profildatei); gehalten wird ausschliesslich der Application-Context.
     * @param profile das im Planungsblatt gewaehlte Routenprofil — jeder
     *   Kandidat wird damit gerechnet.
     * @param fromMapCenter ob [start] die Kartenmitte statt der echten Position
     *   ist — das Blatt weist darauf hin.
     * @param onMessage geteilter Meldungskanal
     *   ([de.trailscape.app.ui.AppViewModel.showMessage]) fuer Hinweise, die
     *   auch dann noch ankommen sollen, wenn das Panel schon zu ist.
     * @param onOfferMissingSegments bekommt die Dateinamen lokal fehlender
     *   Kacheln — dieselbe Stelle wie bei der manuellen Planung
     *   ([de.trailscape.app.ui.AppViewModel.offerMissingSegments]); eine leere
     *   Liste wird gar nicht erst gemeldet.
     */
    fun start(
        context: Context,
        start: TrackPoint,
        profile: RouteProfile,
        fromMapCenter: Boolean,
        onMessage: (String) -> Unit,
        onOfferMissingSegments: (List<String>) -> Unit = {},
    ) {
        val current = _state.value
        val target = current.target ?: return
        if (current.running) return

        val appContext = context.applicationContext
        lastStart = start
        lastContext = appContext
        lastProfile = profile
        lastOfferMissingSegments = onOfferMissingSegments
        val flag = AtomicBoolean(false)
        cancelFlag = flag

        _state.value = current.copy(
            running = true,
            done = 0,
            total = CANDIDATE_COUNT,
            candidates = emptyList(),
            selectedIndex = -1,
            error = null,
            hints = emptyList(),
            fromMapCenter = fromMapCenter,
        )

        AppServices.appScope.launch(Dispatchers.IO) {
            // Was das Backend unterwegs erfaehrt, gesammelt fuer das
            // Kachel-Angebot: die versuchten Wegpunktrunden (fuer den
            // Fehlerzweig) und die vom Server-Rueckfall gemeldeten fehlenden
            // Kacheln (fuer den Erfolgsfall — wie die manuelle Planung nach
            // einer Server-Route).
            val attemptedWaypointSets = mutableListOf<List<Waypoint>>()
            val missingFromFallbacks = linkedSetOf<String>()
            val backend = RoutingBackend { waypoints, routeProfile ->
                attemptedWaypointSets.add(waypoints)
                val outcome = planRouteOfflineFirst(
                    context = appContext,
                    waypoints = waypoints,
                    profile = routeProfile,
                )
                missingFromFallbacks.addAll(outcome.missingSegmentFiles)
                outcome.route
            }
            try {
                val result = generateRoutes(
                    backend = backend,
                    start = start,
                    target = target,
                    profile = profile,
                    seed = current.seed,
                    candidates = CANDIDATE_COUNT,
                    // Der einzige Punkt, an dem `generateRoutes` von aussen
                    // unterbrechbar ist (siehe Klassen-KDoc).
                    sleeper = { ms ->
                        if (flag.get()) throw GenerationCancelled()
                        if (ms > 0) delay(ms)
                    },
                    onProgress = { done, total ->
                        if (!flag.get()) {
                            _state.update { it.copy(done = done, total = total) }
                        }
                    },
                )
                if (flag.get()) return@launch
                _state.update {
                    it.copy(
                        running = false,
                        candidates = result,
                        // Bester zuerst: `generateRoutes` sortiert aufsteigend
                        // nach Strafpunkten, der erste ist also die Empfehlung
                        // — und wird gleich auf der Karte gezeigt.
                        selectedIndex = 0,
                        hints = result.firstOrNull()?.hints.orEmpty(),
                        error = null,
                    )
                }
                if (missingFromFallbacks.isNotEmpty()) {
                    onOfferMissingSegments(missingFromFallbacks.toList())
                }
            } catch (_: GenerationCancelled) {
                // [cancel] hat den Zustand bereits freigegeben.
                onMessage("Routensuche abgebrochen.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (flag.get()) return@launch
                _state.update {
                    it.copy(
                        running = false,
                        done = 0,
                        total = 0,
                        error = e.message?.takeIf(String::isNotBlank)
                            ?: "Die Routensuche ist fehlgeschlagen.",
                    )
                }
                // Derselbe Ausweg wie im Fehlerzweig der manuellen Planung
                // (siehe `missingSegmentsFor`-KDoc): Fehlen fuer die
                // versuchten Runden Kacheln, soll die Nutzerin das Angebot
                // sehen und nicht nur die Servermeldung lesen.
                offerMissingForFailedRun(
                    context = appContext,
                    profile = profile,
                    attemptedWaypointSets = attemptedWaypointSets,
                    alreadyKnownMissing = missingFromFallbacks,
                    onOfferMissingSegments = onOfferMissingSegments,
                )
            }
        }
    }

    /**
     * Sammelt fuer alle in diesem Durchlauf versuchten Runden die lokal
     * fehlenden Kacheln ein und reicht sie — dedupliziert, in Routen-
     * Reihenfolge — an das Angebot weiter. Still bei leerem Ergebnis und bei
     * Fehlern der Bestandsabfrage: Das Angebot ist eine Zugabe zum
     * Fehlerzweig, kein zweiter Fehler.
     */
    private suspend fun offerMissingForFailedRun(
        context: Context,
        profile: RouteProfile,
        attemptedWaypointSets: List<List<Waypoint>>,
        alreadyKnownMissing: Set<String>,
        onOfferMissingSegments: (List<String>) -> Unit,
    ) {
        val missing = linkedSetOf<String>()
        missing.addAll(alreadyKnownMissing)
        for (waypoints in attemptedWaypointSets) {
            runCatching { missingSegmentsFor(context, waypoints, profile) }
                .getOrNull()
                ?.let(missing::addAll)
        }
        if (missing.isNotEmpty()) {
            onOfferMissingSegments(missing.toList())
        }
    }

    /**
     * Bricht die laufende Suche ab. Die Oberflaeche ist sofort wieder frei;
     * ein bereits laufender Routing-Aufruf laeuft im Hintergrund aus und sein
     * Ergebnis wird verworfen (siehe Klassen-KDoc).
     */
    fun cancel() {
        val flag = cancelFlag ?: return
        if (!_state.value.running) return
        flag.set(true)
        cancelFlag = null
        _state.update { it.copy(running = false, done = 0, total = 0) }
    }

    /**
     * Naechster Satz Vorschlaege: `seed + 1` (in `:core` der goldene Winkel —
     * die Runden liegen dadurch maximal weit auseinander) mit demselben
     * Startpunkt, demselben Profil und demselben Angebots-Kanal. Ohne
     * vorherigen Durchlauf passiert nichts.
     */
    fun nextSuggestions(onMessage: (String) -> Unit) {
        val startPoint = lastStart ?: return
        val context = lastContext ?: return
        if (_state.value.running) return
        val fromMapCenter = _state.value.fromMapCenter
        _state.update { it.copy(seed = it.seed + 1) }
        start(
            context = context,
            start = startPoint,
            profile = lastProfile,
            fromMapCenter = fromMapCenter,
            onMessage = onMessage,
            onOfferMissingSegments = lastOfferMissingSegments,
        )
    }

    /** Waehlt einen Vorschlag aus; der Screen zeichnet ihn daraufhin. */
    fun select(index: Int) {
        _state.update {
            if (index in it.candidates.indices) it.copy(selectedIndex = index) else it
        }
    }
}

/** Zustand des Generierungs-Panels. */
data class RouteGenerationState(
    /** Das Ziel aus der Trainingsempfehlung; `null` = kein Panel. */
    val target: RouteTarget? = null,
    val running: Boolean = false,
    /** Fertige Kandidaten des laufenden Durchlaufs. */
    val done: Int = 0,
    /** Gesamtzahl der Kandidaten des laufenden Durchlaufs. */
    val total: Int = 0,
    /** Variation der Runden; „Andere Vorschläge" zaehlt ihn hoch. */
    val seed: Int = 0,
    /** Ergebnis, bester Vorschlag zuerst. */
    val candidates: List<RouteCandidate> = emptyList(),
    /** Index in [candidates], oder −1. */
    val selectedIndex: Int = -1,
    val error: String? = null,
    /** Hinweise aus `:core`, z. B. „Zieldistanz auf 5 km angehoben". */
    val hints: List<String> = emptyList(),
    /** Ob der Startpunkt die Kartenmitte war statt der echten Position. */
    val fromMapCenter: Boolean = false,
) {
    /** Der ausgewaehlte Vorschlag, oder `null`. */
    val selected: RouteCandidate? get() = candidates.getOrNull(selectedIndex)
}

/** Signal des Abbruchs — verlaesst `generateRoutes` ueber dessen `sleeper`. */
private class GenerationCancelled : Exception("Routensuche abgebrochen.")
