package de.trailscape.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * # Navi-Kamera — die reine Rechnung hinter dem Fahr-Blick
 *
 * Waehrend der Navigation soll die Karte wie bei Komoot oder Google Maps
 * mitfahren: Fahrtrichtung oben (course-up), Zoom nach Tempo, Position im
 * unteren Drittel. Die **Karten**-Seite davon (MapLibre-Kamera, Padding)
 * wohnt in `app/.../ui/map/MapViewHost.kt` — hier stehen ausschliesslich die
 * reinen Zahlenrechnungen, die sich ohne Android testen lassen:
 *
 *  * [daempfeKurs]: exponentielle Glaettung des GPS-Kurses mit korrektem
 *    Winkel-Wraparound (359° → 1° dreht ueber Norden, nicht einmal rum) und
 *    **Einfrieren im Stand** — der GPS-Kurs unterhalb von
 *    [NAV_KURS_EINFRIER_KMH] ist Rauschen, und eine Karte, die an der Ampel
 *    kreiselt, ist schlimmer als eine, die kurz stehen bleibt.
 *  * [zoomFuerTempo] und [glaetteZoom]: je schneller, desto weiter der
 *    Blick — linear zwischen den beiden Stuetzpunkten, geklemmt, und
 *    geglaettet, damit der Zoom bei springendem GPS-Tempo nicht pumpt.
 *  * [kursZwischen]: Anfangskurs zwischen zwei Positionen (Grosskreis) —
 *    die Kursquelle, wenn die Positionen als nackte Koordinaten kommen
 *    (Aufzeichnungspunkte tragen keinen GPS-Kurs).
 *  * [klemmeOffRouteZoom]: Zoomgrenzen fuer die Abseits-Ansicht, in der
 *    Position und Route gemeinsam im Bild stehen.
 *
 * Getestet in `NavCameraTest`.
 */

/**
 * Unterhalb dieses Tempos (km/h) wird der Kurs **eingefroren**: [daempfeKurs]
 * gibt den alten Wert zurueck, egal was der neue Messwert sagt. Im Stand
 * zeigt der aus Positionsdifferenzen gebildete Kurs nur noch das
 * GPS-Zittern — dieselbe Schwelle wie `NAV_TEMPO_MIN_KMH` der
 * Restzeit-Schaetzung („steht gerade").
 */
const val NAV_KURS_EINFRIER_KMH = 3.0

/**
 * Gewicht des neuen Kurs-Messwerts in [daempfeKurs] (0..1). Etwas schwerer
 * als die Tempo-Glaettung: Der Kurs soll einer echten Kurve binnen zwei,
 * drei GPS-Punkten folgen — eine traege Karte in der Kehre waere gefaehrlicher
 * als eine leicht zappelige auf der Geraden.
 */
const val NAV_KURS_GLAETTUNG_FAKTOR = 0.4

/** Zoomstufe im langsamen Fahren (bis [NAV_ZOOM_TEMPO_LANGSAM_KMH]). */
const val NAV_ZOOM_NAH = 17.0

/** Zoomstufe im schnellen Fahren (ab [NAV_ZOOM_TEMPO_SCHNELL_KMH]). */
const val NAV_ZOOM_FERN = 15.5

/** Bis zu diesem Tempo (km/h) bleibt der Zoom auf [NAV_ZOOM_NAH]. */
const val NAV_ZOOM_TEMPO_LANGSAM_KMH = 15.0

/** Ab diesem Tempo (km/h) steht der Zoom auf [NAV_ZOOM_FERN]. */
const val NAV_ZOOM_TEMPO_SCHNELL_KMH = 35.0

/**
 * Gewicht des neuen Zielzooms in [glaetteZoom] (0..1). Bewusst traege: Der
 * Zoom darf dem Tempo ruhig ein paar Sekunden hinterherlaufen — ein Zoom,
 * der mit jedem GPS-Tempozacken pumpt, macht die Karte unlesbar.
 */
const val NAV_ZOOM_GLAETTUNG_FAKTOR = 0.15

/** Obergrenze des Zooms der Abseits-Ansicht (nicht naeher heranzoomen). */
const val NAV_OFFROUTE_ZOOM_MAX = 16.0

/** Untergrenze der Abseits-Ansicht — weiter raus hilft niemandem mehr. */
const val NAV_OFFROUTE_ZOOM_MIN = 12.0

/**
 * Exponentielle Glaettung des Fahrkurses in Grad (0..360, 0 = Nord).
 *
 *  * **Wraparound**: gerechnet wird ueber die kuerzeste Winkeldifferenz —
 *    von 350° nach 10° geht es 20° ueber Norden, nicht 340° herum.
 *  * **Einfrieren im Stand**: liegt [tempoKmh] unter
 *    [NAV_KURS_EINFRIER_KMH], bleibt [alterKurs] stehen (siehe Datei-KDoc).
 *    Ein unbekanntes Tempo (`null`) friert dagegen **nicht** ein — wer sich
 *    messbar bewegt hat (sonst gaebe es keinen neuen Kurs), faehrt.
 *  * `null`-Vertraeglichkeit: ohne neuen Messwert bleibt der alte, ohne
 *    alten uebernimmt der neue unveraendert.
 *
 * @param faktor Gewicht des neuen Messwerts (0..1), Default
 *   [NAV_KURS_GLAETTUNG_FAKTOR].
 * @return geglaetteter Kurs in [0, 360), oder `null` wenn noch nie ein Kurs
 *   bekannt war.
 */
fun daempfeKurs(
    alterKurs: Double?,
    neuerKurs: Double?,
    tempoKmh: Double?,
    faktor: Double = NAV_KURS_GLAETTUNG_FAKTOR,
): Double? {
    if (neuerKurs == null) return alterKurs
    if (tempoKmh != null && tempoKmh < NAV_KURS_EINFRIER_KMH) return alterKurs
    val neu = normalisiereKurs(neuerKurs)
    val alt = alterKurs ?: return neu
    // Kuerzeste Winkeldifferenz in (-180, 180]: erst in [0, 360) schieben,
    // dann um 180 zentrieren — das ist der Wraparound.
    val differenz = ((neu - alt + 540.0) % 360.0) - 180.0
    return normalisiereKurs(alt + faktor * differenz)
}

/** Winkel in Grad auf [0, 360) gebracht — auch fuer negative Eingaben. */
fun normalisiereKurs(grad: Double): Double = ((grad % 360.0) + 360.0) % 360.0

/**
 * Ziel-Zoomstufe fuer ein Tempo: [NAV_ZOOM_NAH] bis
 * [NAV_ZOOM_TEMPO_LANGSAM_KMH], linear fallend auf [NAV_ZOOM_FERN] ab
 * [NAV_ZOOM_TEMPO_SCHNELL_KMH], ausserhalb geklemmt. Unbekanntes Tempo
 * (`null`) zaehlt als langsam — nah heranzoomen ist der sichere Fehler.
 */
fun zoomFuerTempo(kmh: Double?): Double {
    if (kmh == null || kmh <= NAV_ZOOM_TEMPO_LANGSAM_KMH) return NAV_ZOOM_NAH
    if (kmh >= NAV_ZOOM_TEMPO_SCHNELL_KMH) return NAV_ZOOM_FERN
    val anteil = (kmh - NAV_ZOOM_TEMPO_LANGSAM_KMH) /
        (NAV_ZOOM_TEMPO_SCHNELL_KMH - NAV_ZOOM_TEMPO_LANGSAM_KMH)
    return NAV_ZOOM_NAH + anteil * (NAV_ZOOM_FERN - NAV_ZOOM_NAH)
}

/**
 * Glaettet den Zoom auf den [zielZoom] zu (exponentiell, Gewicht [faktor]) —
 * der erste Wert wird direkt uebernommen.
 */
fun glaetteZoom(
    bisherigerZoom: Double?,
    zielZoom: Double,
    faktor: Double = NAV_ZOOM_GLAETTUNG_FAKTOR,
): Double = if (bisherigerZoom == null) zielZoom else bisherigerZoom + (zielZoom - bisherigerZoom) * faktor

/** Klemmt den Zoom der Abseits-Ansicht auf [NAV_OFFROUTE_ZOOM_MIN]..[NAV_OFFROUTE_ZOOM_MAX]. */
fun klemmeOffRouteZoom(zoom: Double): Double =
    zoom.coerceIn(NAV_OFFROUTE_ZOOM_MIN, NAV_OFFROUTE_ZOOM_MAX)

/**
 * Anfangskurs (Grad, 0 = Nord, im Uhrzeigersinn) von Position A nach
 * Position B auf dem Grosskreis — die uebliche Formel ueber `atan2`.
 * Fuer die wenigen Meter zwischen zwei GPS-Punkten ist das exakt genug
 * und an den Polen wohldefinierter als jede Plattnaeherung.
 */
fun kursZwischen(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
    val phi1 = Math.toRadians(latA)
    val phi2 = Math.toRadians(latB)
    val deltaLambda = Math.toRadians(lonB - lonA)
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    return normalisiereKurs(Math.toDegrees(atan2(y, x)))
}
