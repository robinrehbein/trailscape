package de.trailscape.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Abbiegehinweise aus der Routengeometrie.
 *
 * Trailscape hat keine Turn-by-Turn-Daten: Die Navigation ([RouteNavigator])
 * ist reine Streckenverfolgung entlang einer Punktliste, und die Router
 * (BRouter, Selfhost) liefern nur Geometrie, keine Manoever. Was sich aus der
 * Geometrie aber sehr wohl ablesen laesst, ist, WO die Route ihre Richtung
 * signifikant aendert — genug fuer eine Ansage „In 100 Metern links", die den
 * Blick vom Display auf den Weg holt.
 *
 * Zwei Teile, beide bewusst reine Zustandsmaschinen ohne Android-Import:
 *
 *  * [extractTurnHints] destilliert aus der Punktliste einmalig die
 *    Kurvenpunkte ([TurnHint]) — Kurswinkelaenderung ueber ein Fenster von
 *    rund 2×[TURN_FENSTER_M] Metern, ab [TURN_SCHWELLE_GRAD] ein Hinweis, ab
 *    [KEHRE_SCHWELLE_GRAD] eine Kehre. Dicht aufeinanderfolgende Krümmung
 *    (die vielen Stuetzpunkte EINER Kurve, aber auch die Kehren einer
 *    Serpentinenstrecke) wird zu einem Hinweis gebuendelt — wer in die erste
 *    Kehre hineingesagt bekommt „scharf links", braucht fuer die fuenf
 *    folgenden keine Einzeldurchsage im Sekundentakt.
 *  * [TurnAnnouncer] entscheidet zur Laufzeit, wann welcher Hinweis faellig
 *    ist. Eingabe je GPS-Punkt: der Routenfortschritt und das aktuelle Tempo;
 *    Ausgabe: der Ansagetext oder `null`.
 *
 * ## Warum der Announcer den Routenfortschritt nimmt statt der Rohposition
 * Die Entfernung zum Kurvenpunkt muss ENTLANG der Route gemessen werden — die
 * Luftlinie luegt genau dort, wo es darauf ankommt (vor einer Kehre liegt der
 * Punkt hinter der Kehre naeher als ihr Scheitel). Die Projektion der
 * Position auf die Route rechnet der [RouteNavigator] ohnehin bei jedem
 * GPS-Punkt ([NavState.doneKm]); sie hier ein zweites Mal zu implementieren
 * waere dieselbe Arbeit mit einer zweiten Fehlerquelle. Der Aufrufer reicht
 * deshalb `doneM = NavState.doneKm * 1000` herein — das IST die aktuelle
 * Position, nur bereits in Routenkoordinaten.
 */

/** Richtung eines Abbiegehinweises. */
enum class TurnRichtung {
    LINKS,
    RECHTS,

    /** Scharfe Linkskurve (Spitzkehre), Winkel ab [KEHRE_SCHWELLE_GRAD]. */
    KEHRE_LINKS,

    /** Scharfe Rechtskurve (Spitzkehre), Winkel ab [KEHRE_SCHWELLE_GRAD]. */
    KEHRE_RECHTS,
}

/**
 * Ein Kurvenpunkt der Route.
 *
 * @property index Index des Punktes in der Punktliste, aus der der Hinweis
 *   extrahiert wurde (bei gebuendelter Kruemmung: der erste Punkt des
 *   Abschnitts — angesagt wird VOR der Einfahrt, nicht am staerksten Knick).
 * @property winkelGrad Betrag der Kurswinkelaenderung in Grad (bei
 *   gebuendelter Kruemmung: das Maximum der Punkte in Fahrtrichtung des
 *   ersten — eine sanfte Einleitung darf die Kehre dahinter nicht
 *   verharmlosen).
 * @property distanzM Position des Hinweises als Distanz entlang der Route ab
 *   deren Anfang, in Metern — die Groesse, gegen die der [TurnAnnouncer] den
 *   Fortschritt vergleicht.
 */
data class TurnHint(
    val index: Int,
    val lat: Double,
    val lon: Double,
    val richtung: TurnRichtung,
    val winkelGrad: Double,
    val distanzM: Double,
)

/**
 * Halbe Fensterbreite der Kurswinkelmessung: verglichen wird der Kurs von
 * rund [TURN_FENSTER_M] Metern vor einem Punkt mit dem von rund
 * [TURN_FENSTER_M] Metern danach. Gross genug, um GPS-/Geometrie-Zacken
 * einzelner Stuetzpunkte zu glaetten, klein genug, dass zwei getrennte Kurven
 * nicht zu einer verschmieren.
 */
const val TURN_FENSTER_M = 25.0

/** Ab dieser Kurswinkelaenderung wird ein Hinweis erzeugt. */
const val TURN_SCHWELLE_GRAD = 40.0

/** Ab dieser Kurswinkelaenderung gilt die Kurve als Kehre („scharf"). */
const val KEHRE_SCHWELLE_GRAD = 100.0

/**
 * Kurvenpunkte, deren Abstand entlang der Route hoechstens so gross ist,
 * werden zu EINEM Hinweis gebuendelt. 80 m fangen sowohl die Stuetzpunkte
 * einer einzelnen Kurve als auch die typischen Kehrenabstaende einer
 * Serpentine — zwei echte, getrennte Abbiegungen liegen im Wegenetz
 * praktisch immer weiter auseinander oder sind als EIN Zug gemeint.
 */
const val TURN_BUENDEL_ABSTAND_M = 80.0

/** Kursrichtung (Anfangspeilung) von [a] nach [b] in Grad, 0–360, 0 = Nord. */
private fun kursGrad(a: TrackPoint, b: TrackPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val grad = Math.toDegrees(atan2(y, x))
    return (grad + 360.0) % 360.0
}

/** Normalisiert eine Winkeldifferenz auf (-180, 180]; positiv = rechts herum. */
private fun normalisiereGrad(diff: Double): Double {
    var d = diff % 360.0
    if (d > 180.0) d -= 360.0
    if (d <= -180.0) d += 360.0
    return d
}

/** Roh-Kandidat vor der Buendelung. */
private data class KurvenKandidat(val index: Int, val deltaGrad: Double, val distanzM: Double)

/**
 * Extrahiert die Kurvenpunkte einer Route (siehe Datei-KDoc).
 *
 * Fuer jeden inneren Punkt wird der Kurs eines rund [fensterM] Meter langen
 * Stuecks DAVOR mit dem eines gleich langen Stuecks DANACH verglichen; ab
 * [schwelleGrad] Differenz ist der Punkt ein Kandidat. Aufeinanderfolgende
 * Kandidaten mit hoechstens [buendelAbstandM] Metern Routenabstand werden zu
 * einem [TurnHint] gebuendelt: Position und Richtung vom ERSTEN Kandidaten
 * (angesagt wird die Einfahrt), der Winkel ist das Maximum der
 * gleichsinnigen Kandidaten der Gruppe.
 *
 * Laufzeit O(n): die Fensterraender wandern als zwei Zeiger mit.
 *
 * @return Kurvenpunkte in Routenreihenfolge; leer bei weniger als 3 Punkten
 *   oder einer Route ohne signifikante Richtungsaenderung.
 */
fun extractTurnHints(
    points: List<TrackPoint>,
    fensterM: Double = TURN_FENSTER_M,
    schwelleGrad: Double = TURN_SCHWELLE_GRAD,
    kehreGrad: Double = KEHRE_SCHWELLE_GRAD,
    buendelAbstandM: Double = TURN_BUENDEL_ABSTAND_M,
): List<TurnHint> {
    if (points.size < 3) return emptyList()

    val cumM = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cumM[i] = cumM[i - 1] + haversineM(points[i - 1], points[i])
    }

    val kandidaten = mutableListOf<KurvenKandidat>()
    var hinten = 0 // groesster Index mit cum[i] - cum[hinten] >= fensterM
    var vorn = 0 // kleinster Index mit cum[vorn] - cum[i] >= fensterM

    for (i in 1 until points.size - 1) {
        while (hinten + 1 < i && cumM[i] - cumM[hinten + 1] >= fensterM) hinten++
        if (vorn <= i) vorn = i + 1
        while (vorn < points.size - 1 && cumM[vorn] - cumM[i] < fensterM) vorn++

        val a = points[hinten]
        val b = points[i]
        val c = points[vorn]
        // Deckungsgleiche Punkte (Duplikate, Stillstand) haben keinen Kurs.
        if (cumM[i] - cumM[hinten] < 1.0 || cumM[vorn] - cumM[i] < 1.0) continue

        val delta = normalisiereGrad(kursGrad(b, c) - kursGrad(a, b))
        if (abs(delta) >= schwelleGrad) {
            kandidaten.add(KurvenKandidat(index = i, deltaGrad = delta, distanzM = cumM[i]))
        }
    }

    // Buendeln: eine Gruppe endet, sobald der naechste Kandidat weiter als
    // buendelAbstandM hinter dem letzten der Gruppe liegt.
    val hints = mutableListOf<TurnHint>()
    var start = 0
    while (start < kandidaten.size) {
        var ende = start
        while (
            ende + 1 < kandidaten.size &&
            kandidaten[ende + 1].distanzM - kandidaten[ende].distanzM <= buendelAbstandM
        ) {
            ende++
        }

        val erster = kandidaten[start]
        var winkel = abs(erster.deltaGrad)
        for (k in start..ende) {
            val kandidat = kandidaten[k]
            if (kandidat.deltaGrad > 0 == erster.deltaGrad > 0) {
                winkel = maxOf(winkel, abs(kandidat.deltaGrad))
            }
        }

        val rechts = erster.deltaGrad > 0
        val kehre = winkel >= kehreGrad
        val punkt = points[erster.index]
        hints.add(
            TurnHint(
                index = erster.index,
                lat = punkt.lat,
                lon = punkt.lon,
                richtung = when {
                    kehre && rechts -> TurnRichtung.KEHRE_RECHTS
                    kehre -> TurnRichtung.KEHRE_LINKS
                    rechts -> TurnRichtung.RECHTS
                    else -> TurnRichtung.LINKS
                },
                winkelGrad = winkel,
                distanzM = erster.distanzM,
            ),
        )
        start = ende + 1
    }

    return hints
}

/** Vorlauf der Ansage als Fahrzeit: rund so viele Sekunden vor der Kurve. */
const val ANSAGE_VORLAUF_S = 8.0

/** Untergrenze des Vorlaufwegs — auch im Schritttempo nicht erst im Scheitel. */
const val ANSAGE_VORLAUF_MIN_M = 60.0

/** Obergrenze des Vorlaufwegs — auch bergab keine Ansage einen halben Kilometer vorher. */
const val ANSAGE_VORLAUF_MAX_M = 250.0

/** Unter diesem Restweg heisst es „Gleich …" statt „In N Metern …". */
const val ANSAGE_GLEICH_M = 40.0

/** Tempoannahme in km/h, wenn (noch) kein Tempo bekannt ist. */
const val ANSAGE_ANNAHME_KMH = 15.0

/** Ansageform eines Richtungswortes fuer die Sprachausgabe. */
fun richtungsWort(richtung: TurnRichtung): String = when (richtung) {
    TurnRichtung.LINKS -> "links"
    TurnRichtung.RECHTS -> "rechts"
    TurnRichtung.KEHRE_LINKS -> "scharf links"
    TurnRichtung.KEHRE_RECHTS -> "scharf rechts"
}

/**
 * Deutscher Ansagetext fuer einen Abbiegehinweis in [abstandM] Metern —
 * „In 100 Metern links." bzw. im Nahbereich (< [ANSAGE_GLEICH_M]) „Gleich
 * links." Der Abstand wird auf 50er-Schritte gerundet: Eine Sprachausgabe,
 * die „In 137 Metern" sagt, behauptet eine Genauigkeit, die GPS und
 * Routengeometrie nicht hergeben.
 */
fun turnAnsageText(richtung: TurnRichtung, abstandM: Double): String {
    val wort = richtungsWort(richtung)
    if (abstandM < ANSAGE_GLEICH_M) return "Gleich $wort."
    val gerundet = ((abstandM / 50.0).roundToInt() * 50).coerceAtLeast(50)
    return "In $gerundet Metern $wort."
}

/**
 * Laufzeit-Zustandsmaschine der Abbiegehinweise: entscheidet je GPS-Punkt, ob
 * eine Ansage faellig ist (siehe Datei-KDoc).
 *
 *  * **Vorlauf tempoabhaengig**: rund [ANSAGE_VORLAUF_S] Sekunden Fahrzeit,
 *    begrenzt auf [ANSAGE_VORLAUF_MIN_M]–[ANSAGE_VORLAUF_MAX_M] Meter. Ohne
 *    bekanntes Tempo gilt [ANSAGE_ANNAHME_KMH].
 *  * **Jeder Hinweis hoechstens einmal**: Der Zeiger [naechster] wandert nur
 *    vorwaerts. Bereits ueberfahrene Hinweise (Routenfortschritt hinter der
 *    Kurve, etwa nach einem Off-Route-Umweg mit Wiedereinstieg dahinter)
 *    verfallen stumm — eine Ansage fuer eine Kurve, die hinter einem liegt,
 *    waere schlimmer als keine.
 *  * **Reset bei Routenwechsel**: je Route eine frische Instanz (oder
 *    [reset]).
 *
 * Nicht thread-sicher; der Aufrufer (Navigations-Effekt in `MapScreen.kt`)
 * ruft sequenziell je Positionsupdate.
 */
class TurnAnnouncer(private val hints: List<TurnHint>) {

    /** Index des naechsten noch nicht angesagten Hinweises. */
    private var naechster = 0

    /**
     * Wertet den aktuellen Routenfortschritt aus.
     *
     * @param doneM zurueckgelegte Distanz entlang der Route in Metern
     *   (`NavState.doneKm * 1000`, siehe Datei-KDoc).
     * @param speedKmh aktuelles Tempo in km/h, oder `null` wenn unbekannt.
     * @return der faellige Ansagetext, oder `null` wenn nichts anzusagen ist.
     */
    fun melde(doneM: Double, speedKmh: Double?): String? {
        // Ueberfahrene Hinweise verfallen (siehe Klassen-KDoc).
        while (naechster < hints.size && hints[naechster].distanzM <= doneM) naechster++
        if (naechster >= hints.size) return null

        val hint = hints[naechster]
        val restM = hint.distanzM - doneM
        val kmh = speedKmh?.takeIf { it > 0.0 } ?: ANSAGE_ANNAHME_KMH
        val vorlaufM = (kmh / 3.6 * ANSAGE_VORLAUF_S)
            .coerceIn(ANSAGE_VORLAUF_MIN_M, ANSAGE_VORLAUF_MAX_M)
        if (restM > vorlaufM) return null

        naechster++
        return turnAnsageText(hint.richtung, restM)
    }

    /** Setzt die Zustandsmaschine auf den Routenanfang zurueck. */
    fun reset() {
        naechster = 0
    }
}
