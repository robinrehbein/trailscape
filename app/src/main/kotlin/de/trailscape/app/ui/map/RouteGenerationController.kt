package de.trailscape.app.ui.map

import android.content.Context
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.UiText
import de.trailscape.app.routing.missingSegmentsFor
import de.trailscape.app.routing.planRouteOfflineFirst
import de.trailscape.core.ExplorerTile
import de.trailscape.core.RouteCandidate
import de.trailscape.core.RouteProfile
import de.trailscape.core.RouteTarget
import de.trailscape.core.RoutingBackend
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.WindConditions
import de.trailscape.core.fetchCurrentWind
import de.trailscape.core.generateRoutes
import de.trailscape.core.readRouteWindEnabled
import de.trailscape.core.shouldReuseWind
import de.trailscape.core.windCacheKey
import de.trailscape.core.writeRouteWindEnabled
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
 *
 * ## Wind (Opt-in)
 * Mit dem Schalter „Wind berücksichtigen" (Blatt *Runde ab hier*, ab Werk
 * aus) holt [start] vor dem ersten Routing einmal den aktuellen Wind am
 * gerundeten Startpunkt bei Open-Meteo (`core/.../WeatherClient.kt`) und
 * reicht ihn an `generateRoutes` weiter, das die Runden danach umsortiert.
 * Ohne Schalter geht keine Anfrage raus. Scheitert sie (offline, Timeout,
 * kaputte Antwort), rechnet die Suche **still** ohne Wind weiter — keine
 * Meldung, denn der Wind ist eine Zugabe. „Andere Vorschläge" und „Neu
 * suchen" am selben gerundeten Ort fragen 30 Minuten lang nicht erneut.
 *
 * Den Schalter liest der Controller bei jedem Start selbst aus dem Speicher,
 * statt ihn als Parameter zu bekommen: So behandelt jeder Einstieg (Karte,
 * Heute, Training) den Wind gleich, ohne dass jede Aufrufstelle davon wissen
 * muss. [windEnabled] ist nur der Spiegel fuer die Oberflaeche.
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
    private var lastPreferNewAreas: Boolean = false
    private var lastExploredTiles: suspend () -> Set<ExplorerTile> = { emptySet() }

    private val _windEnabled = MutableStateFlow(false)

    /** Spiegel des Schalters „Wind berücksichtigen" fuer das Setup-Blatt. */
    val windEnabled: StateFlow<Boolean> = _windEnabled.asStateFlow()

    @Volatile
    private var windRestored = false

    /** Ob die Nutzerin den Schalter schon selbst gesetzt hat (gewinnt gegen [restoreWindSetting]). */
    @Volatile
    private var windSetByUser = false

    /**
     * Ob [windEnabled] den gueltigen Stand traegt — gespeichert gelesen oder
     * von der Nutzerin gesetzt. Erst dann ist der Speicher nicht mehr die
     * Quelle fuer [start]: Das Schreiben in [setWindEnabled] laeuft auf IO und
     * koennte sonst gegen das Lesen der Suche verlieren (Schalter aus, sofort
     * „Vorschläge zeigen" — und die Anfrage ginge trotzdem raus).
     */
    @Volatile
    private var windKnown = false

    /** Zuletzt geholter Wind samt gerundetem Ort und Zeitpunkt (siehe „Wind"). */
    @Volatile
    private var cachedWind: WindConditions? = null

    @Volatile
    private var cachedWindKey: String? = null

    @Volatile
    private var cachedWindAtMs: Long = 0L

    /**
     * Laedt den gespeicherten Schalter einmal in [windEnabled]. Idempotent;
     * liest auf IO, weil die Prefs beim ersten Zugriff von der Platte kommen.
     */
    fun restoreWindSetting() {
        if (windRestored) return
        windRestored = true
        AppServices.appScope.launch(Dispatchers.IO) {
            val stored = runCatching { readRouteWindEnabled(AppServices.keyValueStore) }.getOrDefault(false)
            // Hat die Nutzerin waehrend des Lesens schon umgeschaltet, gilt ihr Wert.
            if (!windSetByUser) {
                _windEnabled.value = stored
                windKnown = true
            }
        }
    }

    /** Setzt den Schalter „Wind berücksichtigen" und merkt ihn. */
    fun setWindEnabled(enabled: Boolean) {
        windRestored = true
        windSetByUser = true
        _windEnabled.value = enabled
        windKnown = true
        AppServices.appScope.launch(Dispatchers.IO) {
            runCatching { writeRouteWindEnabled(AppServices.keyValueStore, enabled) }
        }
    }

    /**
     * Wind fuer [start] — aus dem Cache oder mit genau einer Anfrage. Nur
     * aufrufen, wenn der Schalter an ist. `null` bei jedem Fehler; ein
     * Fehlschlag landet nicht im Cache.
     */
    private fun windFor(start: TrackPoint): WindConditions? {
        val key = windCacheKey(start)
        val cached = cachedWind
        if (cached != null && shouldReuseWind(cachedWindKey, cachedWindAtMs, key, System.currentTimeMillis())) {
            return cached
        }
        val fresh = runCatching { fetchCurrentWind(start, AppServices.weatherHttpClient) }.getOrNull()
        if (fresh != null) {
            cachedWind = fresh
            cachedWindKey = key
            cachedWindAtMs = System.currentTimeMillis()
        }
        return fresh
    }

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
        preferNewAreas: Boolean = false,
        exploredTiles: suspend () -> Set<ExplorerTile> = { emptySet() },
    ) {
        val current = _state.value
        val target = current.target ?: return
        if (current.running) return

        val appContext = context.applicationContext
        lastStart = start
        lastContext = appContext
        lastProfile = profile
        lastOfferMissingSegments = onOfferMissingSegments
        lastPreferNewAreas = preferNewAreas
        lastExploredTiles = exploredTiles
        val flag = AtomicBoolean(false)
        cancelFlag = flag
        // Schalterstand jetzt, auf dem Aufruf-Thread, festhalten — nicht erst
        // im IO-Coroutine aus dem Speicher (siehe [windKnown]). Nur wenn er
        // noch nie geladen wurde (Einstieg aus Heute/Training, Blatt nie
        // offen), gilt der gespeicherte Wert; dann schreibt auch niemand.
        val windSnapshot: Boolean? = if (windKnown) _windEnabled.value else null

        _state.value = current.copy(
            running = true,
            done = 0,
            total = CANDIDATE_COUNT,
            candidates = emptyList(),
            selectedIndex = -1,
            error = null,
            hints = emptyList(),
            fromMapCenter = fromMapCenter,
            wind = null,
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
                // Immer holen, auch ohne „Neue Gegenden bevorzugen": Jeder
                // Vorschlag zeigt „+N neu", und das stimmt nur gegen den
                // echten Bestand. Bevorzugt wird nur mit Schalter.
                val explored = runCatching { exploredTiles() }.getOrDefault(emptySet())
                // Wind nur mit Schalter (siehe „Wind (Opt-in)"): der beim
                // Start festgehaltene Stand, sonst der gespeicherte — so wird
                // jeder Einstieg gleich behandelt.
                val considerWind = windSnapshot
                    ?: runCatching { readRouteWindEnabled(AppServices.keyValueStore) }.getOrDefault(false)
                val wind = if (considerWind) windFor(start) else null
                // Die Windabfrage blockiert bis zu 4 s — ein Abbruch in dieser
                // Zeit soll nicht erst noch eine Suche starten. Derselbe Weg
                // wie ueber den `sleeper`, samt Meldung.
                if (flag.get()) throw GenerationCancelled()
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
                    exploredTiles = explored,
                    preferNewAreas = preferNewAreas && explored.isNotEmpty(),
                    wind = wind,
                    texts = AppServices.coreTexts(),
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
                        wind = wind,
                    )
                }
                if (missingFromFallbacks.isNotEmpty()) {
                    onOfferMissingSegments(missingFromFallbacks.toList())
                }
            } catch (_: GenerationCancelled) {
                // [cancel] hat den Zustand bereits freigegeben.
                onMessage(AppServices.localizedContext().getString(R.string.map_generation_cancelled_status))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (flag.get()) return@launch
                _state.update {
                    it.copy(
                        running = false,
                        done = 0,
                        total = 0,
                        error = e.message?.takeIf(String::isNotBlank)?.let(UiText::Plain)
                            ?: UiText.Res(R.string.map_generation_failed_error),
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
            preferNewAreas = lastPreferNewAreas,
            exploredTiles = lastExploredTiles,
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
    /**
     * Die Fehlermeldung als [UiText]: Der Zustand liegt im ViewModel und
     * ueberlebt den Sprachwechsel — aufgeloest wird erst im Blatt.
     */
    val error: UiText? = null,
    /** Hinweise aus `:core`, z. B. „Zieldistanz auf 5 km angehoben". */
    val hints: List<String> = emptyList(),
    /** Ob der Startpunkt die Kartenmitte war statt der echten Position. */
    val fromMapCenter: Boolean = false,
    /** Wind, mit dem sortiert wurde; `null` = aus, offline oder fehlgeschlagen. */
    val wind: WindConditions? = null,
) {
    /** Der ausgewaehlte Vorschlag, oder `null`. */
    val selected: RouteCandidate? get() = candidates.getOrNull(selectedIndex)
}

/** Signal des Abbruchs — verlaesst `generateRoutes` ueber dessen `sleeper`. */
private class GenerationCancelled : Exception("Routensuche abgebrochen.")
