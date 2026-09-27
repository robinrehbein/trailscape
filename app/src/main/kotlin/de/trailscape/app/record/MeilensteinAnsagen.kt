package de.trailscape.app.record

/**
 * Kilometer-Meilensteine der Sprachansagen — wie [AutoPauseLogic] bewusst
 * ohne einen einzigen Android-Import, damit die Logik in diesem Modul
 * pruefbar bleibt; gesprochen wird das Ergebnis vom `RecordingService` ueber
 * den `voice/VoiceAnnouncer` — den Satz („15 Kilometer, 42 Minuten.") baut
 * `SpeechTexts.milestone` in `:core` in der App-Sprache.
 *
 * Alle [schrittKm] Kilometer genau eine Ansage.
 * Die Zustandsmaschine merkt sich nur den naechsten faelligen Meilenstein:
 *
 *  * Ueberspringt die Distanz mehrere Schwellen auf einmal (GPS-Luecke,
 *    zwischenzeitlich stummgeschalteter Dienst), wird nur der JUENGSTE
 *    erreichte Meilenstein angesagt — drei nachgeholte Ansagen in Folge
 *    waeren Laerm ohne Information.
 *  * Nach einer Wiederherstellung aus dem Journal setzt [setzeAufDistanz]
 *    den Zaehler hinter die bereits gefahrene Distanz, damit ein
 *    `START_STICKY`-Neustart bei Kilometer 23 nicht ploetzlich „20
 *    Kilometer" verkuendet.
 *
 * Nicht thread-sicher — der Service ruft ausschliesslich auf seinem
 * Aufzeichnungs-Thread (siehe dessen Klassendoc).
 */
internal class MeilensteinAnsagen(private val schrittKm: Int = SCHRITT_KM) {

    /** Distanz in km, ab der die naechste Ansage faellig ist. */
    private var naechsteKm = schrittKm

    /** Setzt den Zaehler auf den Anfang einer frischen Aufzeichnung. */
    fun reset() {
        naechsteKm = schrittKm
    }

    /**
     * Setzt den Zaehler hinter eine bereits gefahrene Distanz — fuer die
     * Fortsetzung aus dem Journal (siehe Klassen-KDoc).
     */
    fun setzeAufDistanz(distanceKm: Double) {
        naechsteKm = (erreichteStufe(distanceKm) + 1) * schrittKm
    }

    /**
     * Prueft, ob mit [distanceKm] ein Meilenstein erreicht ist.
     *
     * @return der erreichte Meilenstein in km, oder `null` wenn keiner
     *   faellig ist. Ein zurueckgegebener Meilenstein gilt als angesagt — der
     *   Zaehler wandert weiter, auch wenn der Aufrufer ihn verwirft (Schalter
     *   „Kilometer-Ansagen" aus): Wer den Schalter bei Kilometer 23 wieder
     *   einschaltet, soll nicht die Ansage von Kilometer 20 nachgereicht
     *   bekommen.
     */
    fun pruefe(distanceKm: Double): Int? {
        if (distanceKm < naechsteKm) return null
        val erreichtKm = erreichteStufe(distanceKm) * schrittKm
        naechsteKm = erreichtKm + schrittKm
        return erreichtKm
    }

    private fun erreichteStufe(distanceKm: Double): Int = (distanceKm / schrittKm).toInt()

    private companion object {
        /** Abstand der Meilensteine in Kilometern. */
        const val SCHRITT_KM = 5
    }
}
