package de.trailscape.app.ui.map

import de.trailscape.app.data.AppServices
import de.trailscape.core.RouteCandidate
import de.trailscape.core.RouteTarget
import de.trailscape.core.TrackPoint
import de.trailscape.core.generateRoutes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Haelt die Rundkurs-Suche **ausserhalb** der Komposition — genau aus dem
 * Grund, aus dem das auch [OfflineDownloadController] tut.
 *
 * `generateRoutes` aus `:core` macht bis zu neun sequenzielle BRouter-Aufrufe
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
 * ## Abbrechen
 * `generateRoutes` ist synchron und kennt kein `Job`; eine Coroutine-Cancellation
 * wuerde den laufenden `Thread.sleep`/HTTP-Aufruf nicht unterbrechen. Der
 * Abbruch laeuft deshalb ueber zwei Wege — **ohne** Aenderung an `:core`:
 *
 *  1. **Hart zwischen zwei Server-Aufrufen.** Der `sleeper`-Parameter von
 *     `generateRoutes` ist injizierbar und wird vor *jedem* Request ausser dem
 *     allerersten aufgerufen — und zwar ausserhalb des `try`, mit dem die
 *     Funktion einzelne Kandidaten abfaengt. Wirft er, verlaesst der Aufruf
 *     die Generierung sofort. Genau das tut [cancel] ueber [AtomicBoolean].
 *  2. **Weich waehrend eines laufenden Requests.** Steckt die Suche gerade in
 *     einem HTTP-Aufruf (bis zu einige Sekunden), laeuft dieser zu Ende; sein
 *     Ergebnis wird verworfen. Die Oberflaeche ist trotzdem sofort wieder frei
 *     — der Zustand geht bei [cancel] unmittelbar auf `running = false`, und
 *     die abgebrochene Coroutine schreibt danach nichts mehr in [state]
 *     (jeder Schreibzugriff prueft ihr eigenes Abbruch-Flag).
 *
 * Die Einschraenkung ist also: Ein einzelner, bereits laufender BRouter-Request
 * wird zu Ende geladen. Sichtbar ist davon nichts — nur der Server bekommt eine
 * Anfrage, deren Antwort niemanden mehr interessiert.
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

    /**
     * Oeffnet das Panel fuer ein neues Ziel und verwirft alles Bisherige
     * (laufende Suche inklusive). Wird vom Karten-Screen gerufen, sobald er
     * `AppViewModel.pendingRouteTarget` abholt.
     */
    fun open(target: RouteTarget) {
        cancelFlag?.set(true)
        cancelFlag = null
        lastStart = null
        _state.value = RouteGenerationState(target = target)
    }

    /** Schliesst das Panel und bricht eine laufende Suche ab. */
    fun close() {
        cancelFlag?.set(true)
        cancelFlag = null
        lastStart = null
        _state.value = RouteGenerationState()
    }

    /**
     * Startet die Suche ab [start].
     *
     * @param fromMapCenter ob [start] die Kartenmitte statt der echten Position
     *   ist — das Blatt weist darauf hin.
     * @param onMessage geteilter Meldungskanal
     *   ([de.trailscape.app.ui.AppViewModel.showMessage]) fuer Hinweise, die
     *   auch dann noch ankommen sollen, wenn das Panel schon zu ist.
     */
    fun start(start: TrackPoint, fromMapCenter: Boolean, onMessage: (String) -> Unit) {
        val current = _state.value
        val target = current.target ?: return
        if (current.running) return

        lastStart = start
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
            try {
                val result = generateRoutes(
                    client = AppServices.httpClient,
                    start = start,
                    target = target,
                    seed = current.seed,
                    candidates = CANDIDATE_COUNT,
                    // Der einzige Punkt, an dem `generateRoutes` von aussen
                    // unterbrechbar ist (siehe Klassen-KDoc).
                    sleeper = { ms ->
                        if (flag.get()) throw GenerationCancelled()
                        if (ms > 0) Thread.sleep(ms)
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
            }
        }
    }

    /**
     * Bricht die laufende Suche ab. Die Oberflaeche ist sofort wieder frei;
     * ein bereits laufender Server-Aufruf laeuft im Hintergrund aus und sein
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
     * Startpunkt. Ohne vorherigen Durchlauf passiert nichts.
     */
    fun nextSuggestions(onMessage: (String) -> Unit) {
        val startPoint = lastStart ?: return
        if (_state.value.running) return
        val fromMapCenter = _state.value.fromMapCenter
        _state.update { it.copy(seed = it.seed + 1) }
        start(startPoint, fromMapCenter, onMessage)
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
