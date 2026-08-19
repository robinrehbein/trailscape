package de.trailscape.app.record

import de.trailscape.core.TrackPoint
import de.trailscape.core.haversineM

/**
 * Zustandsmaschine der Auto-Pause — wie [RecordingLogic] bewusst ohne einen
 * einzigen Android-Import, damit sie in diesem Modul pruefbar bleibt
 * (`:app` hat kein Robolectric, siehe `app/build.gradle.kts`).
 *
 * ## Was sie entscheidet
 * Zwei Zustaende: **faehrt** und **autopausiert**. Der [RecordingService]
 * reicht fuer jede rohe Standortmeldung eine Probe herein ([probe]) und fuehrt
 * das Urteil aus — pausieren heisst dort exakt dasselbe wie eine manuelle
 * Pause (keine Punkte, Pausenkonto), nur mit eigenem Journal-Vermerk
 * (`autoPause`/`autoResume`) und eigener Anzeige.
 *
 * ## Schwellen und Hysterese
 *  * **Eintritt**: Tempo unter [EINTRITT_KMH] ueber mindestens
 *    [EINTRITT_DAUER_MS] — eine einzelne langsame Probe (Kurve, kurzes
 *    Abbremsen) pausiert noch nichts.
 *  * **Rueckkehr**: eine einzige Probe ab [FORTSETZUNG_KMH]. Die Luecke
 *    zwischen beiden Schwellen ist die Hysterese gegen GPS-Jitter im Stand:
 *    Der [de.trailscape.core.PointFilter] laesst im Stand Jitter durch
 *    (nur exakte Positions-Duplikate werden verworfen), und aus ein paar
 *    Metern Rauschen in fuenf Sekunden werden schnell 1–2 km/h — unter der
 *    Eintrittsschwelle bliebe die Aufzeichnung damit dauernd am Rand des
 *    Umschaltens. Ein einzelner Jitter-Ausschlag ueber die
 *    Rueckkehrschwelle ist verkraftbar: Er setzt nur fort, und nach
 *    [EINTRITT_DAUER_MS] unter der Eintrittsschwelle pausiert es wieder.
 *
 * ## Woher das Tempo kommt
 * Bevorzugt die vom Geraet gemessene Momentangeschwindigkeit (Doppler, deutlich
 * jitterfester als Positionsdifferenzen). Meldet das Geraet keine — bei rohem
 * GNSS ist `hasSpeed()` durchaus `false` —, wird sie ersatzweise aus zwei
 * aufeinanderfolgenden **rohen** Proben abgeleitet: Waehrend der Auto-Pause
 * nimmt der PointFilter keine Punkte an, aufgezeichnete Punkte stuenden also
 * gar nicht zur Verfuegung, um die Weiterfahrt zu erkennen. Laesst sich auch
 * das nicht ableiten (erste Probe, zu grosse Zeitluecke), ist die Probe kein
 * Beleg fuer irgendetwas und aendert nichts am Zustand.
 *
 * Nicht thread-sicher — der Service ruft ausschliesslich auf seinem
 * Aufzeichnungs-Thread (siehe dessen Klassendoc).
 */
internal class AutoPauseLogic(
    private val eintrittKmh: Double = EINTRITT_KMH,
    private val fortsetzungKmh: Double = FORTSETZUNG_KMH,
    private val eintrittDauerMs: Long = EINTRITT_DAUER_MS,
) {

    /** Urteil einer Probe — `null` heisst: nichts zu tun. */
    enum class Uebergang {
        /** Uebergang faehrt → autopausiert. */
        PAUSIEREN,

        /** Uebergang autopausiert → faehrt. */
        FORTSETZEN,
    }

    /** Ob die Maschine gerade im Zustand „autopausiert" steht. */
    var autoPausiert: Boolean = false
        private set

    /** Beginn der laufenden Langsamphase, oder `null` ausserhalb einer. */
    private var langsamSeitMs: Long? = null

    /** Vorherige rohe Probe fuer die ersatzweise Tempoableitung. */
    private var letzteProbe: TrackPoint? = null

    /**
     * Wertet eine rohe Standortmeldung aus.
     *
     * @param zeitMs Zeitstempel der Messung in ms seit Epoch.
     * @param gemesseneKmh vom Geraet gemeldetes Tempo in km/h, oder `null`,
     *   wenn das Geraet keines gemeldet hat (dann wird es aus den Positionen
     *   abgeleitet, siehe Klassendoc).
     * @return der auszufuehrende Uebergang, oder `null`.
     */
    fun probe(zeitMs: Long, lat: Double, lon: Double, gemesseneKmh: Double?): Uebergang? {
        val vorherige = letzteProbe
        letzteProbe = TrackPoint(lat = lat, lon = lon, time = zeitMs)

        val tempoKmh = gemesseneKmh
            ?: abgeleitetesTempoKmh(vorherige, zeitMs, lat, lon)
            ?: return null

        return if (autoPausiert) pruefeFortsetzung(tempoKmh) else pruefeEintritt(zeitMs, tempoKmh)
    }

    private fun pruefeEintritt(zeitMs: Long, tempoKmh: Double): Uebergang? {
        if (tempoKmh >= eintrittKmh) {
            langsamSeitMs = null
            return null
        }
        val seit = langsamSeitMs
        if (seit == null || zeitMs < seit) {
            // Auch ein ruecklaeufiger Zeitstempel (Uhrkorrektur) beginnt die
            // Langsamphase neu — eine negative Dauer darf nie pausieren.
            langsamSeitMs = zeitMs
            return null
        }
        if (zeitMs - seit < eintrittDauerMs) return null

        autoPausiert = true
        langsamSeitMs = null
        return Uebergang.PAUSIEREN
    }

    private fun pruefeFortsetzung(tempoKmh: Double): Uebergang? {
        if (tempoKmh < fortsetzungKmh) return null
        autoPausiert = false
        langsamSeitMs = null
        return Uebergang.FORTSETZEN
    }

    /**
     * Ersatz-Tempo aus zwei aufeinanderfolgenden rohen Proben, oder `null`,
     * wenn es keine brauchbare Basis gibt (keine Vorgaengerprobe, Zeitluecke
     * ausserhalb von (0, [ABLEITUNG_MAX_DT_MS]]).
     */
    private fun abgeleitetesTempoKmh(
        vorherige: TrackPoint?,
        zeitMs: Long,
        lat: Double,
        lon: Double,
    ): Double? {
        val vorher = vorherige ?: return null
        val vorherZeit = vorher.time ?: return null
        val dtMs = zeitMs - vorherZeit
        if (dtMs <= 0L || dtMs > ABLEITUNG_MAX_DT_MS) return null
        val distanzM = haversineM(vorher, TrackPoint(lat = lat, lon = lon))
        return distanzM / (dtMs / 1000.0) * 3.6
    }

    /**
     * Setzt die Maschine in den Zustand „autopausiert", ohne einen Uebergang
     * zu melden — fuer die Wiederherstellung aus dem Journal, wenn die
     * Aufzeichnung mit offener Auto-Pause fortgesetzt wird (siehe
     * `RecordingService.continueFromJournal`): Die Weiterfahrt soll dann
     * genauso erkannt werden, als haette dieser Prozess selbst pausiert.
     */
    fun stelleAutoPauseWiederHer() {
        autoPausiert = true
        langsamSeitMs = null
        letzteProbe = null
    }

    /**
     * Zurueck auf Anfang (Zustand „faehrt", keine Langsamphase, keine
     * Vorgaengerprobe) — bei Aufzeichnungsbeginn und bei jedem manuellen
     * Pausieren/Fortsetzen, damit alte Proben nicht ueber eine manuelle Pause
     * hinweg verrechnet werden.
     */
    fun reset() {
        autoPausiert = false
        langsamSeitMs = null
        letzteProbe = null
    }

    companion object {

        /** Unter diesem Tempo beginnt die Langsamphase (siehe Klassendoc). */
        const val EINTRITT_KMH: Double = 2.0

        /** Ab diesem Tempo setzt eine einzige Probe die Aufzeichnung fort. */
        const val FORTSETZUNG_KMH: Double = 3.5

        /** So lange muss das Tempo unter [EINTRITT_KMH] bleiben. */
        const val EINTRITT_DAUER_MS: Long = 5_000L

        /**
         * Obergrenze fuer die Zeitluecke der ersatzweisen Tempoableitung —
         * dieselbe Groessenordnung wie
         * [de.trailscape.core.PointFilter.MAX_SPEED_FALLBACK_INTERVAL_S],
         * grosszuegiger bemessen, weil rohe Proben im Stand auch mal
         * ausbleiben (`minUpdateDistanceMeters`).
         */
        const val ABLEITUNG_MAX_DT_MS: Long = 30_000L
    }
}
