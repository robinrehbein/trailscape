package de.trailscape.core

/**
 * Umrechnung einer *rohen* Standortmeldung des Betriebssystems in ein
 * [LocationSample], das [PointFilter] bewerten kann.
 *
 * ## Warum diese Umrechnung eine eigene, getestete Funktion ist
 * Solange die Aufzeichnung am gebuendelten Standortdienst von Google hing,
 * war das Mapping eine Fingeruebung: Jener Dienst lieferte Hoehe, Genauigkeit
 * und Geschwindigkeit praktisch immer, weil er mehrere Quellen (GNSS, WLAN,
 * Mobilfunk, Beschleunigungssensoren) zu einer vollstaendigen Meldung
 * zusammenrechnet. Androids eigener `LocationManager` reicht dagegen durch,
 * was der GNSS-Chip gerade hergibt: `hasAltitude()`, `hasAccuracy()` und
 * `hasSpeed()` koennen `false` sein, und `getTime()` kann bei einem noch
 * nicht dekodierten Satellitenzeitstempel `0` sein.
 *
 * *Was* ein fehlender Wert bedeuten soll, ist damit keine Fleissarbeit mehr,
 * sondern eine Entscheidung, die die Qualitaet der ganzen Tour bestimmt —
 * also Rechenlogik und damit ein Fall fuer `:core`. `:app` hat bewusst kein
 * Robolectric und koennte sie dort gar nicht pruefen; hier haengen Tests
 * daran (`LocationMappingTest`).
 *
 * ## Die drei Entscheidungen
 *
 *  * **Fehlende Hoehe → [UNKNOWN_ALTITUDE_M] (`NaN`), nicht `0.0`.**
 *    [PointFilter] macht daraus ueber seine `isFinite()`-Pruefung
 *    `TrackPoint.ele == null`, und `computeStats` laesst Punkte ohne Hoehe
 *    beim Zaehlen der Hoehenmeter aus. Eine erfundene `0.0` waere dagegen ein
 *    Sprung auf Meereshoehe und wuerde in der Tour zwei falsche Anstiege
 *    erzeugen (hinunter und wieder hinauf).
 *  * **Fehlende Genauigkeit → [UNKNOWN_ACCURACY_M] (`0.0`).** Der
 *    Genauigkeitsfilter verwirft ueber 50 m; eine unbekannte Genauigkeit als
 *    „unendlich schlecht" zu lesen wuerde jeden solchen Punkt wegwerfen,
 *    obwohl er in Ordnung sein kann. `0.0` heisst hier „kein Grund zur
 *    Verwerfung" und entspricht dem bisherigen Verhalten.
 *  * **Fehlende Geschwindigkeit → [UNKNOWN_SPEED_MPS] (`-1.0`), nicht `0.0`.**
 *    Das ist die einzige echte Verhaltensaenderung gegenueber frueher, und
 *    zwar eine noetige: [PointFilter] uebernimmt eine gemeldete
 *    Geschwindigkeit nur bei `speedMps >= 0` und rechnet sie sonst aus den
 *    letzten beiden Punkten aus. Eine erfundene `0.0` wuerde diesen Ersatzweg
 *    abschneiden und der Oberflaeche stattdessen „0 km/h" anzeigen, waehrend
 *    der Fahrer faehrt.
 *
 * Nicht entschieden wird hier ueber unplausible *Zeitstempel*: Nur ein
 * Zeitstempel `<= 0` gilt als fehlend. Ein Abgleich mit der Geraeteuhr waere
 * verlockend, aber verkehrt herum — bei einem Geraet ohne Netzzeit ist die
 * Satellitenzeit die genauere von beiden, und ein „Plausibilitaetsfenster"
 * wuerde ausgerechnet die bessere Quelle verwerfen.
 */

/**
 * Hoehe, die als „nicht gemeldet" gilt. `NaN` und nicht `0.0`, siehe
 * Dateikommentar — [PointFilter] macht daraus `TrackPoint.ele == null`.
 */
const val UNKNOWN_ALTITUDE_M: Double = Double.NaN

/**
 * Genauigkeit, die als „nicht gemeldet" gilt. `0.0` passiert den
 * Genauigkeitsfilter von [PointFilter], verwirft den Punkt also nicht.
 */
const val UNKNOWN_ACCURACY_M: Double = 0.0

/**
 * Geschwindigkeit, die als „nicht gemeldet" gilt. Negativ, damit
 * [PointFilter] sie nicht uebernimmt und auf seine Berechnung aus zwei
 * Punkten ausweicht.
 */
const val UNKNOWN_SPEED_MPS: Double = -1.0

/**
 * Baut ein [LocationSample] aus den Rohwerten einer Standortmeldung.
 *
 * Die Aufrufseite (`RecordingService`) reicht `null` durch, wo
 * `android.location.Location` mit `hasAltitude()`/`hasAccuracy()`/
 * `hasSpeed()` „habe ich nicht" sagt — sie trifft selbst keine Entscheidung
 * darueber, was das bedeutet.
 *
 * @param altitudeM gemeldete Hoehe in Metern, oder `null`.
 * @param accuracyM gemeldete horizontale Genauigkeit in Metern, oder `null`.
 *   Negative Werte gelten als fehlend (es gibt keine negative Genauigkeit).
 * @param speedMps gemeldete Geschwindigkeit in m/s, oder `null`. Negative
 *   Werte gelten als fehlend.
 * @param timeMs Zeitstempel der Messung in ms seit Epoch; `<= 0` gilt als
 *   fehlend.
 * @param fallbackTimeMs Zeitstempel, der einspringt, wenn [timeMs] fehlt —
 *   in der Praxis die Geraeteuhr zum Zeitpunkt des Callbacks. Ohne
 *   brauchbaren Zeitstempel waeren Dauer, Tempo und Kurven der Tour hin.
 */
fun toLocationSample(
    lat: Double,
    lon: Double,
    altitudeM: Double?,
    accuracyM: Double?,
    speedMps: Double?,
    timeMs: Long,
    fallbackTimeMs: Long,
): LocationSample = LocationSample(
    lat = lat,
    lon = lon,
    altitudeM = altitudeM?.takeIf { it.isFinite() } ?: UNKNOWN_ALTITUDE_M,
    accuracyM = accuracyM?.takeIf { it.isFinite() && it >= 0.0 } ?: UNKNOWN_ACCURACY_M,
    speedMps = speedMps?.takeIf { it.isFinite() && it >= 0.0 } ?: UNKNOWN_SPEED_MPS,
    timeMs = if (timeMs > 0L) timeMs else fallbackTimeMs,
)
