package de.trailscape.core

import java.time.LocalDateTime

/**
 * Ableitungen fuer Vitaldaten, die Health Connect nicht (zuverlaessig) fertig
 * liefert: der Ruhepuls aus dem Nacht-Puls und die Schlafdauer ohne
 * Doppelzaehlung.
 *
 * Beides ist reine Rechnerei auf den Plattform-Datentypen und damit ohne
 * Geraet testbar; `HealthSyncService.readVitals` bindet es an.
 */

// ---------------------------------------------------------------------------
// Schlaf: ueberlappende Sitzungen zusammenfuehren
// ---------------------------------------------------------------------------

/**
 * Fuehrt zeitlich **ueberlappende** Schlafsitzungen zu einer zusammen.
 *
 * ## Warum
 * Schreiben zwei Apps dieselbe Nacht nach Health Connect (Samsung Health von
 * der Uhr und z. B. eine Schlaf-App am Handy), lagen frueher beide Sitzungen
 * in der Tagessumme — eine Nacht mit 7 h erschien als 14 h Schlaf. Eine
 * Quelle zu bevorzugen waere zerbrechlich (welche ist „die richtige"?, was ist
 * mit Naechten, in denen nur die zweite App lief?). Das Vereinigen der
 * Intervalle ist dagegen quellenunabhaengig: Wer auch immer die Nacht
 * geschrieben hat, sie zaehlt genau einmal, und zwar als die Zeitspanne, in
 * der mindestens eine Quelle Schlaf gesehen hat.
 *
 * ## Was als Ueberlappung gilt
 * Nur echte Ueberschneidung (`naechster Start < bisheriges Ende`). Aneinander
 * stossende Sitzungen (Ende == Start) bleiben getrennt — so aendert sich fuer
 * Nutzerinnen mit nur einer Quelle rechnerisch nichts (die Minuten werden je
 * Sitzung gekuerzt, wie bisher).
 *
 * Die Quelle ([HealthSleepSession.source]) einer zusammengefuehrten Sitzung
 * bleibt erhalten, wenn alle Teile aus derselben App stammen, sonst `null`.
 * Sitzungen mit Ende vor Start werden verworfen.
 */
fun mergeOverlappingSleep(sessions: Iterable<HealthSleepSession>): List<HealthSleepSession> {
    val sorted = sessions
        .filter { !it.end.isBefore(it.start) }
        .sortedWith(compareBy<HealthSleepSession> { dartEpochMs(it.start) }.thenBy { dartEpochMs(it.end) })
    val merged = ArrayList<HealthSleepSession>(sorted.size)
    for (session in sorted) {
        val last = merged.lastOrNull()
        if (last != null && session.start.isBefore(last.end)) {
            merged[merged.size - 1] = HealthSleepSession(
                start = last.start,
                end = if (session.end.isAfter(last.end)) session.end else last.end,
                source = if (last.source == session.source) last.source else null,
            )
        } else {
            merged.add(session)
        }
    }
    return merged
}

// ---------------------------------------------------------------------------
// Ruhepuls aus dem Nacht-Puls
// ---------------------------------------------------------------------------

/**
 * Laenge des gleitenden Fensters fuer den Nacht-Ruhepuls in Minuten.
 *
 * 30 Minuten statt der oft genannten 5: Die Galaxy Watch misst den Puls im
 * Schlaf je nach Einstellung nur alle paar Minuten (bis zu alle 10 min). Ein
 * 5-Minuten-Fenster enthielte dann eine einzige Messung, und der „Ruhepuls"
 * waere schlicht das Minimum der Nacht — ein Ausreisser nach unten
 * (Messfehler, Bewegungsartefakt) bestimmte den Wert. 30 Minuten mit
 * mindestens [nightlyRestingHrMinSamplesPerWindow] Messungen glaetten das
 * bei jeder ueblichen Abtastrate und entsprechen dem, was Garmin als
 * „niedrigster 30-Minuten-Durchschnitt" ausweist.
 */
const val nightlyRestingHrWindowMinutes: Long = 30

/** Mindestanzahl Messungen in einem Fenster, damit es zaehlt. */
const val nightlyRestingHrMinSamplesPerWindow: Int = 3

/**
 * Mindestanzahl gueltiger Messungen in der ganzen Nacht. Darunter (Uhr erst
 * gegen Morgen angelegt, Akku leer) gibt es keinen Wert — ein fehlender Tag
 * ist besser als ein verzerrter, die Baseline-Gates kommen mit Luecken klar.
 */
const val nightlyRestingHrMinSamples: Int = 10

/**
 * Rueckfall-Nachtfenster ohne Schlafsitzung: 00:00 bis 06:00 Uhr lokaler Zeit
 * am jeweiligen Tag (Ende exklusiv).
 */
const val nightlyFallbackEndHour: Int = 6

/**
 * Mindestdauer einer Schlafsitzung, damit sie als Nachtfenster taugt (2 h) —
 * kuerzere sind Nickerchen, deren Puls nicht mit dem naechtlichen vergleichbar
 * ist.
 */
const val nightlySleepMinDurationMs: Long = 2L * 60 * 60 * 1000

/** Plausibler Pulsbereich; alles ausserhalb ist ein Messfehler. */
private const val PLAUSIBLE_BPM_MIN = 25.0
private const val PLAUSIBLE_BPM_MAX = 220.0

/** Zeitraum, in dem der Nacht-Ruhepuls eines Tages gesucht wird. */
data class NightWindow(
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** `true`, wenn das Fenster aus einer Schlafsitzung stammt. */
    val fromSleep: Boolean,
)

/**
 * Das Nachtfenster, dessen Puls den Ruhepuls von [day] bestimmt.
 *
 * Bevorzugt die laengste (bereits zusammengefuehrte, siehe
 * [mergeOverlappingSleep]) Schlafsitzung, die an [day] **endet** — dieselbe
 * Zuordnung „Aufwachtag", die auch die Schlafdauer benutzt. Ist keine
 * mindestens [nightlySleepMinDurationMs] lang, gilt 00:00 bis
 * [nightlyFallbackEndHour] Uhr an [day].
 */
fun nightWindowForDay(day: LocalDateTime, sleep: Iterable<HealthSleepSession>): NightWindow {
    val midnight = atMidnight(day)
    val best = mergeOverlappingSleep(sleep)
        .filter { atMidnight(it.end) == midnight && it.durationMs >= nightlySleepMinDurationMs }
        .maxByOrNull { it.durationMs }
    if (best != null) {
        return NightWindow(start = best.start, end = best.end, fromSleep = true)
    }
    return NightWindow(
        start = midnight,
        end = midnight.plusHours(nightlyFallbackEndHour.toLong()),
        fromSleep = false,
    )
}

/**
 * Ruhepuls aus dem Nacht-Puls — der Ersatz, wenn die Quell-App keinen
 * `RestingHeartRateRecord` schreibt.
 *
 * ## Warum es das braucht
 * Die Readiness verlangt Ruhepuls **und** Schlaf. Samsung Health spiegelt den
 * Ruhepuls der Galaxy Watch je nach Version und Einstellung nicht nach Health
 * Connect; die Readiness erschien dann nie, obwohl die Uhr die ganze Nacht
 * Puls gemessen hat. Genutzt wird dieser Wert deshalb **nur** an Tagen, fuer
 * die kein Ruhepuls-Datensatz existiert — ein vorhandener gewinnt immer.
 *
 * ## Methode
 *  1. Messungen im Fenster `[windowStart, windowEnd)` (siehe
 *     [nightWindowForDay]); Werte ausserhalb 25…220 bpm fallen als
 *     Messfehler weg.
 *  2. Weniger als [nightlyRestingHrMinSamples] Messungen → kein Wert.
 *  3. Fuer jede Messung als Fensteranfang: Mittelwert aller Messungen der
 *     folgenden [nightlyRestingHrWindowMinutes] Minuten, sofern es mindestens
 *     [nightlyRestingHrMinSamplesPerWindow] sind.
 *  4. Ergebnis ist der **niedrigste** dieser Mittelwerte — der ruhigste
 *     zusammenhaengende Abschnitt der Nacht, typischerweise im Tiefschlaf.
 *
 * Der Wert liegt erfahrungsgemaess etwas unter dem Tages-Ruhepuls mancher
 * Hersteller, ist aber von Nacht zu Nacht in sich vergleichbar — und nur
 * darauf kommt es an: Readiness und Baseline bewerten Abweichungen vom
 * eigenen Mittel, nicht Absolutwerte.
 *
 * @return Ruhepuls in bpm oder `null`, wenn die Nacht zu wenig hergibt.
 */
fun nightlyRestingHeartRate(
    samples: Iterable<HealthHeartRateSample>,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
): Double? {
    val startMs = dartEpochMs(windowStart)
    val endMs = dartEpochMs(windowEnd)
    val night = samples
        .filter {
            it.bpm.isFinite() && it.bpm >= PLAUSIBLE_BPM_MIN && it.bpm <= PLAUSIBLE_BPM_MAX
        }
        .map { dartEpochMs(it.time) to it.bpm }
        .filter { (ms, _) -> ms >= startMs && ms < endMs }
        .sortedBy { it.first }
    if (night.size < nightlyRestingHrMinSamples) {
        return null
    }

    val windowMs = nightlyRestingHrWindowMinutes * 60_000
    // Zwei Zeiger ueber die sortierte Reihe: [i, j) ist das aktuelle Fenster.
    var best: Double? = null
    var j = 0
    var sum = 0.0
    for (i in night.indices) {
        if (j < i) {
            j = i
            sum = 0.0
        }
        val limit = night[i].first + windowMs
        while (j < night.size && night[j].first < limit) {
            sum += night[j].second
            j++
        }
        val count = j - i
        if (count >= nightlyRestingHrMinSamplesPerWindow) {
            val mean = sum / count
            if (best == null || mean < best) {
                best = mean
            }
        }
        sum -= night[i].second
    }
    return best
}
