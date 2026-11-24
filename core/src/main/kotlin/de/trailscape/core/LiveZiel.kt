package de.trailscape.core

import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDecimal
import kotlin.math.ceil

/**
 * # Zielzeile waehrend der Fahrt
 *
 * „Noch 12 km bis zum Wochenziel", „Wochenziel geschafft ✓", „Noch 8 km bis
 * zur neuen Bestweite": eine Zeile auf Uhr und Handy, genau fuer den Moment,
 * in dem man ueberlegt umzukehren.
 *
 * ## Snapshot zum Start
 * Was die Zeile braucht, steht beim Start der Tour fest ([ZielSnapshot]): der
 * Rest zum Wochenziel und die bisher weiteste Tour. Waehrend der Fahrt kommt
 * nur noch die Distanz dieser Tour dazu, und die Zeile ist eine reine Funktion
 * daraus ([zielZeile]). So kann die Uhr die Zeile selbst bilden — in ihrer
 * eigenen Sprache, aus zwei Zahlen im `AufzeichnungsZustand` — und Handy und
 * Uhr zeigen bei gleicher Distanz dasselbe.
 *
 * ## Vorrang
 *  1. **Wochenziel**, solange es zum Start offen war und noch nicht erreicht
 *     ist: [ZielZeile.WochenzielRest].
 *  2. Ist es mit dieser Tour erreicht: [ZielZeile.WochenzielGeschafft] — fuer
 *     die ersten [GESCHAFFT_FENSTER_KM] danach in jedem Fall (der Moment mit
 *     Haken), danach nur noch, solange keine Bestweite in Reichweite ist.
 *  3. **Bestweite**, wenn die Tour mindestens [BESTWEITE_NAEHE_ANTEIL] der
 *     weitesten Tour erreicht hat: [ZielZeile.BestweiteRest], und sobald sie
 *     weiter ist, [ZielZeile.NeueBestweite] (bleibt stehen).
 *  4. Sonst keine Zeile.
 *
 * Pausen spielen keine Rolle: Die Zeile haengt nur an der Distanz, und die
 * steht in der Pause still — gerade dann soll die Zeile stehen bleiben.
 */
data class ZielSnapshot(
    /** Was zum Start der Tour noch zum Wochenziel fehlte, `null` ohne Ziel oder wenn schon erreicht. */
    val wochenRestKm: Double?,
    /** Die bisher weiteste Tour, `null` ohne eine ab [BESTWEITE_MIN_KM]. */
    val laengsteKm: Double?,
) {
    /** Beide leer: nichts zu zeigen, nichts zu senden. */
    val leer: Boolean get() = gueltigeKm(wochenRestKm) == null && gueltigeLaengste(laengsteKm) == null
}

/** Die Zeile als Wert; den Satz baut die Anzeige in ihrer Sprache. */
sealed interface ZielZeile {
    /** Noch [restKm] bis zum Wochenziel. */
    data class WochenzielRest(val restKm: Double) : ZielZeile

    /** Mit dieser Tour ist das Wochenziel erreicht (mit Haken). */
    data object WochenzielGeschafft : ZielZeile

    /** Noch [restKm] bis zur neuen Bestweite. */
    data class BestweiteRest(val restKm: Double) : ZielZeile

    /** Diese Tour ist schon weiter als jede zuvor (mit Haken). */
    data object NeueBestweite : ZielZeile
}

/** Wie lange (in km dieser Tour) nach dem Erreichen des Wochenziels der Haken in jedem Fall stehen bleibt. */
const val GESCHAFFT_FENSTER_KM: Double = 1.0

/**
 * Ab diesem Anteil der weitesten Tour ist die Bestweite „nah" — bei 60 km
 * also ab 42 km, mit hoechstens 18 km Rest. Frueher waere „Noch 50 km bis
 * zur neuen Bestweite" eher Last als Ansporn.
 */
const val BESTWEITE_NAEHE_ANTEIL: Double = 0.7

/** Kuerzere Touren sind keine Bestweite, die man feiert (wie `personalRecords`). */
const val BESTWEITE_MIN_KM: Double = 5.0

/** Obergrenze fuer beide Snapshot-Werte; was darueber liegt, ist ein kaputtes Telegramm. */
const val ZIEL_MAX_KM: Double = 10_000.0

private fun gueltigeKm(km: Double?): Double? = km?.takeIf { it.isFinite() && it > 0.0 && it <= ZIEL_MAX_KM }

private fun gueltigeLaengste(km: Double?): Double? = gueltigeKm(km)?.takeIf { it >= BESTWEITE_MIN_KM }

/**
 * Der Snapshot zum Start einer Tour.
 *
 * @param facts die gespeicherten Touren (ohne die gerade beginnende).
 * @param wochenZielKm das Wochenziel, wie es Widget und Uhr-Kachel zeigen
 *   (mit aktivem Trainingsplan dessen Wochenziel), `null` ohne Ziel.
 * @param wochenGefahrenKm die dazu passenden Kilometer dieser Woche.
 * @return `null`, wenn es weder ein offenes Wochenziel noch eine Bestweite gibt.
 */
fun zielSnapshot(facts: List<RideFact>, wochenZielKm: Int?, wochenGefahrenKm: Double): ZielSnapshot? {
    val gefahren = wochenGefahrenKm.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val rest = wochenZielKm?.takeIf { it > 0 }?.let { it - gefahren }
    val laengste = personalRecords(facts).firstOrNull { it.kind == RecordKind.DISTANCE }?.value
    val snapshot = ZielSnapshot(
        wochenRestKm = gueltigeKm(rest),
        laengsteKm = gueltigeLaengste(laengste),
    )
    return snapshot.takeUnless { it.leer }
}

/**
 * Die Zielzeile bei [tourKm] gefahrenen Kilometern dieser Tour (Regeln siehe
 * Datei-KDoc). `null` ohne Snapshot oder wenn nichts in Reichweite ist.
 * Ungueltige Snapshot-Werte (negativ, unendlich, zu gross) zaehlen als nicht
 * vorhanden; eine ungueltige Distanz als 0.
 */
fun zielZeile(snapshot: ZielSnapshot?, tourKm: Double): ZielZeile? {
    if (snapshot == null) return null
    val km = tourKm.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    val wochenRest = gueltigeKm(snapshot.wochenRestKm)
    val bestweite = bestweiteZeile(gueltigeLaengste(snapshot.laengsteKm), km)

    if (wochenRest != null) {
        if (km < wochenRest) return ZielZeile.WochenzielRest(wochenRest - km)
        if (km < wochenRest + GESCHAFFT_FENSTER_KM) return ZielZeile.WochenzielGeschafft
        return bestweite ?: ZielZeile.WochenzielGeschafft
    }
    return bestweite
}

private fun bestweiteZeile(laengste: Double?, km: Double): ZielZeile? {
    if (laengste == null) return null
    if (km > laengste) return ZielZeile.NeueBestweite
    if (km < laengste * BESTWEITE_NAEHE_ANTEIL) return null
    return ZielZeile.BestweiteRest(laengste - km)
}

/**
 * Ein Rest zum Anzeigen: [wert] mit [nachkommastellen] Stellen.
 *
 * Aufgerundet, nie ab: Die Zeile verspricht nie weniger, als noch fehlt, und
 * zeigt nie „Noch 0 km". Ab 1 km in ganzen Kilometern, darunter in Zehnteln
 * (mindestens 0,1).
 */
data class ZielRest(val wert: Double, val nachkommastellen: Int)

private const val RUNDUNG_EPS = 1e-9

fun zielRestAnzeige(restKm: Double): ZielRest {
    val rest = restKm.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val zehntel = (ceil(rest * 10.0 - RUNDUNG_EPS) / 10.0).coerceAtLeast(0.1)
    return if (zehntel < 1.0) {
        ZielRest(zehntel, 1)
    } else {
        ZielRest(ceil(rest - RUNDUNG_EPS).coerceAtLeast(1.0), 0)
    }
}

/**
 * Die Zahl der Zeile im Zahlformat von [sprache] („12", „0,4" / „0.4"),
 * `null` fuer die Zeilen mit Haken, die keine Zahl haben.
 */
fun zielZeileZahl(zeile: ZielZeile, sprache: AppLanguage): String? {
    val rest = when (zeile) {
        is ZielZeile.WochenzielRest -> zeile.restKm
        is ZielZeile.BestweiteRest -> zeile.restKm
        ZielZeile.WochenzielGeschafft, ZielZeile.NeueBestweite -> return null
    }
    val anzeige = zielRestAnzeige(rest)
    return formatDecimal(anzeige.wert, anzeige.nachkommastellen, sprache)
}
