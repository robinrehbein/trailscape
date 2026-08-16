package de.trailscape.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * GPS-Fusion aus zwei Quellen (Telefon, Uhr) zu einer gemeinsamen Spur.
 *
 * Wenn eine gekoppelte Uhr am Handgelenk sitzt und das Telefon in der
 * Trikottasche, liefern beide Geraete unabhaengig voneinander Positionen —
 * mit unterschiedlicher Frequenz, unterschiedlicher Genauigkeit und nicht
 * zwingend in strikter Zeitreihenfolge (Bluetooth-Uebertragung puffert und
 * verzoegert). [LocationFusion] verschmilzt beide Stroeme laufend zu *einer*
 * Spur statt sie hinterher zu mitteln: Ein inkrementeller Kalman-Filter mit
 * Zustand [x, y, vx, vy] (konstante Geschwindigkeit) gewichtet jede neue Probe
 * automatisch nach ihrer gemeldeten Genauigkeit — eine praezise Telefon-Probe
 * zieht den Zustand staerker zu sich als eine verrauschte Uhr-Probe, und
 * umgekehrt.
 *
 * ## Warum ein Kalman-Filter und kein gleitender Mittelwert
 * Ein einfacher Mittelwert kennt weder Geschwindigkeit noch Vertrauenswuerdigkeit
 * der einzelnen Quelle. Der Kalman-Filter fuehrt beides mit: Er praediziert die
 * Position ueber die Zeit anhand der zuletzt geschaetzten Geschwindigkeit (auch
 * waehrend eine Quelle schweigt) und korrigiert bei jeder Probe proportional zu
 * ihrer Unsicherheit. Fuer eine Radfahrt reicht ein Modell konstanter
 * Geschwindigkeit — Lenk-/Bremsmanoever aeussern sich einfach als Prozessrauschen
 * (siehe [SIGMA_A_MPS2]).
 *
 * ## Lokales Koordinatensystem
 * Die Zustandsrechnung laeuft in lokalen ENU-Metern (Ost/Nord) um den *ersten*
 * empfangenen Fix, nicht in Grad: Die Kovarianzmatrix des Kalman-Filters
 * unterstellt gleichfoermige Metrik in x/y, was in Grad wegen der
 * Breitengrad-abhaengigen Laengengrad-Skalierung nicht gilt. Die Umrechnung
 * nutzt dieselbe aequirektangulaere Naeherung wie [RouteNavigator]
 * (Navigation.kt): fuer die Ausdehnung einer einzelnen Fahrt (wenige zehn
 * Kilometer) mehr als genau genug, und viel billiger als eine echte
 * Kartenprojektion.
 *
 * ## Hoehe: bewusst kein zweiter Kalman-Filter
 * Barometrische/GPS-Hoehe hat ein voellig anderes Rauschprofil als die
 * horizontale Position (kein sinnvolles Geschwindigkeitsmodell, oft nur grob
 * quantisiert) und eigene Sensorik. Ein zweiter 2D-Kalman-Filter dafuer waere
 * Overkill fuer den Nutzen; eine nach Genauigkeit gewichtete EMA
 * (exponentiell gleitender Mittelwert, siehe [aktualisiereHoehe]) daempft das
 * Rauschen fast genauso gut bei einem Bruchteil des Codes.
 *
 * Deterministisch: haengt ausschliesslich von den uebergebenen Werten ab,
 * nicht von der Systemzeit oder Zufallszahlen. Nicht thread-sicher — wie
 * [PointFilter] serialisieren Aufrufer die Zugriffe selbst.
 */

/** Herkunft einer Positionsprobe: das Telefon selbst oder eine gekoppelte Uhr. */
enum class Quelle { TELEFON, UHR }

/**
 * Aktuelle fusionierte Position nach einem [LocationFusion.fuege]-Aufruf.
 *
 * @param unsicherheitM Wurzel aus der Summe der Positions-Varianzen (x- und
 * y-Richtung) des Kalman-Zustands — ein grobes Mass fuer "wie sicher ist sich
 * der Filter gerade", nuetzlich fuer die UI (z. B. Genauigkeitskreis).
 * @param zuletzt Quelle, deren Probe diesen Aufruf ausgeloest hat.
 */
data class FusedPoint(
    val zeitMs: Long,
    val lat: Double,
    val lon: Double,
    val hoeheM: Double?,
    val geschwindigkeitMps: Double,
    val unsicherheitM: Double,
    val zuletzt: Quelle,
)

/** Meter pro Breitengrad (aequirektangulaere Naeherung, siehe Navigation.kt). */
private const val M_PER_DEG_LAT = 111320.0

private fun toRad(deg: Double): Double = deg * Math.PI / 180

// ---------------------------------------------------------------------------
// Kleine 4x4-Matrixhilfen (Zustand [x, y, vx, vy] ist immer genau vierdimensional)
// ---------------------------------------------------------------------------

private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
    val n = a.size
    val m = b[0].size
    val k = b.size
    return Array(n) { i -> DoubleArray(m) { j -> (0 until k).sumOf { l -> a[i][l] * b[l][j] } } }
}

private fun matAdd(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
    Array(a.size) { i -> DoubleArray(a[i].size) { j -> a[i][j] + b[i][j] } }

private fun transpose(a: Array<DoubleArray>): Array<DoubleArray> {
    val rows = a.size
    val cols = a[0].size
    return Array(cols) { j -> DoubleArray(rows) { i -> a[i][j] } }
}

private fun matVec(a: Array<DoubleArray>, v: DoubleArray): DoubleArray =
    DoubleArray(a.size) { i -> v.indices.sumOf { j -> a[i][j] * v[j] } }

private fun identity4(): Array<DoubleArray> = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0 else 0.0 } }

/**
 * Inkrementeller Kalman-Filter, der Positionsproben zweier Quellen
 * (Telefon/Uhr) laufend zu einer Spur verschmilzt. Siehe Datei-KDoc fuer das
 * Modell.
 */
class LocationFusion {

    /** Zustand [x, y, vx, vy] in Metern bzw. m/s relativ zu ([lat0], [lon0]); `null` vor dem ersten Fix. */
    private var zustand: DoubleArray? = null

    /** Kovarianz des Zustands, `null` vor dem ersten Fix. */
    private var kovarianz: Array<DoubleArray>? = null

    private var lat0 = 0.0
    private var lon0 = 0.0
    private var mProGradLon = 0.0

    /**
     * Zeitstempel der zuletzt *verarbeiteten* Probe (maximal je gesehener
     * Zeitstempel, siehe [fuege]) — Referenzpunkt fuer den Toleranzfilter und
     * fuer `dt` der naechsten Praediktion.
     */
    private var letzteVerarbeiteteZeitMs = 0L

    /** Geglaetteter Hoehenwert, siehe [aktualisiereHoehe]. */
    private var hoeheEmaM: Double? = null

    /**
     * Verarbeitet eine neue Positionsprobe und liefert die aktuelle fusionierte
     * Position, oder `null`, wenn die Probe verworfen wurde.
     *
     * Verwirft:
     *  - Proben aelter als `letzte verarbeitete Zeit − [TOLERANZ_MS]` (Out-of-order
     *    jenseits dessen, was durch Uebertragungsjitter zwischen zwei
     *    unabhaengigen Quellen zu erwarten ist),
     *  - Proben mit `genauigkeitM > `[MAX_GENAUIGKEIT_M] (Ausreisser).
     *
     * Fehlt [genauigkeitM], wird konservativ [DEFAULT_GENAUIGKEIT_M] angenommen
     * — eine Probe ohne Genauigkeitsangabe darf den Zustand nicht staerker
     * ziehen als eine mittelmaessige echte Messung.
     */
    fun fuege(
        quelle: Quelle,
        zeitMs: Long,
        lat: Double,
        lon: Double,
        hoeheM: Double? = null,
        genauigkeitM: Double? = null,
    ): FusedPoint? {
        if (genauigkeitM != null && genauigkeitM > MAX_GENAUIGKEIT_M) {
            return null
        }
        val effGenauigkeitM = genauigkeitM ?: DEFAULT_GENAUIGKEIT_M

        if (zustand == null) {
            // Erster Fix ueberhaupt: definiert den ENU-Ursprung. Die Messung IST
            // hier der Zustand — eine Kalman-Korrektur gegen sich selbst waere
            // ein Nullschritt.
            lat0 = lat
            lon0 = lon
            mProGradLon = M_PER_DEG_LAT * cos(toRad(lat))
            zustand = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            kovarianz = arrayOf(
                doubleArrayOf(effGenauigkeitM * effGenauigkeitM, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, effGenauigkeitM * effGenauigkeitM, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, ANFANGS_GESCHWINDIGKEITS_VARIANZ, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, ANFANGS_GESCHWINDIGKEITS_VARIANZ),
            )
            letzteVerarbeiteteZeitMs = zeitMs
            hoeheM?.let { aktualisiereHoehe(it, effGenauigkeitM) }
            return ausgabe(zeitMs, quelle)
        }

        if (zeitMs < letzteVerarbeiteteZeitMs - TOLERANZ_MS) {
            return null
        }

        // Proben, die innerhalb der Toleranz leicht "aus der Reihe" ankommen
        // (dt < 0), werden ohne Rueckwaerts-Praediktion verarbeitet: dt wird auf
        // 0 gekappt, die Probe fliesst also als quasi-gleichzeitige Korrektur in
        // den aktuellen Zustand ein, statt die Zeitachse zurueckzudrehen.
        val dtS = max(0.0, (zeitMs - letzteVerarbeiteteZeitMs) / 1000.0)
        praediziere(dtS)

        val xM = (lon - lon0) * mProGradLon
        val yM = (lat - lat0) * M_PER_DEG_LAT
        korrigiere(xM, yM, effGenauigkeitM)

        letzteVerarbeiteteZeitMs = max(letzteVerarbeiteteZeitMs, zeitMs)
        hoeheM?.let { aktualisiereHoehe(it, effGenauigkeitM) }

        return ausgabe(zeitMs, quelle)
    }

    /** Praediktionsschritt: Zustand und Kovarianz um `dtS` Sekunden fortschreiben (konstante Geschwindigkeit). */
    private fun praediziere(dtS: Double) {
        val f = arrayOf(
            doubleArrayOf(1.0, 0.0, dtS, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, dtS),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0),
        )
        val q = prozessRauschen(dtS)
        zustand = matVec(f, zustand!!)
        kovarianz = matAdd(matMul(matMul(f, kovarianz!!), transpose(f)), q)
    }

    /**
     * Prozessrauschen fuer das "weisses Beschleunigungsrauschen"-Modell:
     * ueber `dtS` wirkt eine unbekannte, aber im Mittel Null-Beschleunigung mit
     * Varianz [SIGMA_A_MPS2]^2, die Positions- und Geschwindigkeitsunsicherheit
     * konsistent mitwachsen laesst (Standardherleitung fuer das
     * Konstante-Geschwindigkeit-Modell).
     */
    private fun prozessRauschen(dtS: Double): Array<DoubleArray> {
        val dt2 = dtS * dtS
        val dt3 = dt2 * dtS
        val dt4 = dt3 * dtS
        val varianzA = SIGMA_A_MPS2 * SIGMA_A_MPS2
        return arrayOf(
            doubleArrayOf(varianzA * dt4 / 4, 0.0, varianzA * dt3 / 2, 0.0),
            doubleArrayOf(0.0, varianzA * dt4 / 4, 0.0, varianzA * dt3 / 2),
            doubleArrayOf(varianzA * dt3 / 2, 0.0, varianzA * dt2, 0.0),
            doubleArrayOf(0.0, varianzA * dt3 / 2, 0.0, varianzA * dt2),
        )
    }

    /**
     * Korrekturschritt mit einer Positionsmessung (x, y in Metern). Die
     * Messmatrix beobachtet nur Position, keine Geschwindigkeit
     * (`H = [[1,0,0,0],[0,1,0,0]]`); die Formeln unten sind fuer genau dieses
     * `H` von Hand ausmultipliziert (2x2-Invertierung statt einer generischen
     * `n`x`m`-Matrixinversion, die fuer einen festen 4x4/2x2-Fall unnoetig waere).
     */
    private fun korrigiere(messX: Double, messY: Double, genauigkeitM: Double) {
        val x = zustand!!
        val p = kovarianz!!
        val r = genauigkeitM * genauigkeitM

        val y0 = messX - x[0]
        val y1 = messY - x[1]

        val s00 = p[0][0] + r
        val s01 = p[0][1]
        val s10 = p[1][0]
        val s11 = p[1][1] + r
        val det = s00 * s11 - s01 * s10

        val sInv00 = s11 / det
        val sInv01 = -s01 / det
        val sInv10 = -s10 / det
        val sInv11 = s00 / det

        // K = P * H^T * S^-1; P*H^T sind hier einfach die ersten beiden Spalten von P.
        val k = Array(4) { i ->
            doubleArrayOf(
                p[i][0] * sInv00 + p[i][1] * sInv10,
                p[i][0] * sInv01 + p[i][1] * sInv11,
            )
        }

        zustand = DoubleArray(4) { i -> x[i] + k[i][0] * y0 + k[i][1] * y1 }

        // P_neu = (I - K*H) * P; K*H hat nur in Spalte 0/1 Eintraege (= Spalten von K).
        val kh = Array(4) { i -> doubleArrayOf(k[i][0], k[i][1], 0.0, 0.0) }
        val ikh = Array(4) { i -> DoubleArray(4) { j -> identity4()[i][j] - kh[i][j] } }
        kovarianz = matMul(ikh, p)
    }

    /**
     * Hoehe wird nicht kalman-gefiltert (siehe Datei-KDoc), sondern per EMA
     * geglaettet, deren Gewicht [alpha] von der gemeldeten Genauigkeit der
     * *aktuellen* Probe abhaengt: Eine Probe nahe [HOEHE_EMA_REFERENZ_GENAUIGKEIT_M]
     * oder besser zieht den Wert kraeftig zu sich (bis [HOEHE_EMA_ALPHA_MAX]),
     * eine ungenaue Probe nur schwach (mindestens [HOEHE_EMA_ALPHA_MIN]). Ohne
     * Vergleich zwischen den Quellen noetig zu sein, naehert das effektiv "die
     * genauere Quelle bestimmt die Hoehe staerker" an — mit einem einzigen
     * gespeicherten Wert statt getrennter Zustaende pro Quelle.
     */
    private fun aktualisiereHoehe(hoeheM: Double, genauigkeitM: Double) {
        val alpha = (HOEHE_EMA_REFERENZ_GENAUIGKEIT_M / genauigkeitM)
            .coerceIn(HOEHE_EMA_ALPHA_MIN, HOEHE_EMA_ALPHA_MAX)
        val bisher = hoeheEmaM
        hoeheEmaM = if (bisher == null) hoeheM else alpha * hoeheM + (1 - alpha) * bisher
    }

    private fun ausgabe(zeitMs: Long, quelle: Quelle): FusedPoint {
        val x = zustand!!
        val p = kovarianz!!
        return FusedPoint(
            zeitMs = zeitMs,
            lat = lat0 + x[1] / M_PER_DEG_LAT,
            lon = lon0 + x[0] / mProGradLon,
            hoeheM = hoeheEmaM,
            geschwindigkeitMps = sqrt(x[2] * x[2] + x[3] * x[3]),
            unsicherheitM = sqrt(max(0.0, p[0][0]) + max(0.0, p[1][1])),
            zuletzt = quelle,
        )
    }

    companion object {
        /** Proben mit schlechterer Genauigkeit sind Ausreisser und werden verworfen. */
        const val MAX_GENAUIGKEIT_M: Double = 100.0

        /**
         * Konservativer Default fuer das Messrauschen, wenn eine Probe keine
         * Genauigkeit meldet — bewusst grosszuegig (schlechter als ein typischer
         * GPS-Fix), damit eine unbekannte Genauigkeit den Zustand nie staerker
         * zieht als eine tatsaechlich gemessene mittelmaessige Probe.
         */
        const val DEFAULT_GENAUIGKEIT_M: Double = 25.0

        /**
         * Toleranzfenster fuer Out-of-order-Proben: Bluetooth-Uebertragung von
         * der Uhr kann Proben um bis zu wenige Sekunden verzoegern/umsortieren,
         * ohne dass es sich um eine wirklich veraltete Messung handelt.
         */
        const val TOLERANZ_MS: Long = 3000L

        /**
         * Standardabweichung der als Prozessrauschen angenommenen
         * Beschleunigung in m/s². Ein Rennrad beschleunigt/bremst im
         * Fahralltag typischerweise mit deutlich unter 1 m/s²; dieser Wert
         * erlaubt dem Filter, echten Tempowechseln zuegig zu folgen, ohne bei
         * ruhiger Fahrt uebermaessig zu "zittern".
         */
        const val SIGMA_A_MPS2: Double = 1.0

        /**
         * Anfangsvarianz der Geschwindigkeitskomponenten (m/s)² beim ersten
         * Fix, an dem noch keine Geschwindigkeit bekannt ist. Entspricht einer
         * Standardabweichung von 3 m/s (~11 km/h) — grob genug, um den ersten
         * echten Geschwindigkeitsschaetzwert nicht auszubremsen, aber nicht so
         * gross, dass die erste Praediktion beliebig wird.
         */
        const val ANFANGS_GESCHWINDIGKEITS_VARIANZ: Double = 9.0

        /** Referenzgenauigkeit fuer volles EMA-Gewicht bei der Hoehenglaettung. */
        const val HOEHE_EMA_REFERENZ_GENAUIGKEIT_M: Double = 5.0

        /** Minimales EMA-Gewicht (auch eine schlechte Hoehenprobe zaehlt noch etwas). */
        const val HOEHE_EMA_ALPHA_MIN: Double = 0.1

        /** Maximales EMA-Gewicht (auch eine perfekte Probe ueberschreibt nicht abrupt). */
        const val HOEHE_EMA_ALPHA_MAX: Double = 0.8
    }
}
