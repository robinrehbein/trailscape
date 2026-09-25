package de.trailscape.app.ui.today

import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.core.formatGoalDuration
import de.trailscape.core.HrvAssessment
import de.trailscape.core.HrvStatus
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.RestingHrAssessment
import de.trailscape.core.RideInfo
import de.trailscape.core.SessionIntensity
import de.trailscape.core.SleepAssessment
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrainingSession
import de.trailscape.core.TsbBand
import de.trailscape.core.classifyTsb
import de.trailscape.core.riddenRides
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * # Klartext fuer „Heute" — die Uebersetzung von `:core` in Alltagssprache
 *
 * Gestaltungsvorlage ist `docs/design/prototyp-klartext.html` (Screen „Heute"
 * und Blatt „Warum?"). Deren Regel fuer diese Seite: **kein Fachwort**. Kein
 * GA1, kein Z2, kein TSB, keine „Last" — `:core` rechnet in genau diesen
 * Groessen, die Seite spricht in „locker", „etwas muede" und „45 km ruhig
 * fahren".
 *
 * Alles hier ist eine reine Funktion ohne Compose und ohne Android — dieselbe
 * Trennung wie bei `TrainingInsights.kt`, damit die Wortwahl als gewoehnlicher
 * JVM-Test pruefbar bleibt (`TodayWordingTest`). Die Composables in
 * `TodayCards.kt` setzen die fertigen Saetze nur noch.
 *
 * ## Was hier NICHT passiert
 * Keine Trainingsentscheidung. Ob heute gefahren wird, wie weit und wie hart,
 * steht fertig in [TodayRoute] ([de.trailscape.core.decideTodayRoute]). Diese
 * Datei ordnet das Ergebnis nur einer von sechs Tagesarten ([TodayEffort]) zu
 * und findet die Worte dafuer.
 */

/**
 * Die Tagesart in den Worten der Seite — die fuenf Wortstufen der Vorlage
 * (locker / mittel / hart / lange Fahrt / Ruhetag) plus der Zieltag.
 */
enum class TodayEffort { LOCKER, MITTEL, HART, LANG, RUHETAG, ZIELTAG }

/**
 * Ordnet die Tagesentscheidung einer [TodayEffort] zu.
 *
 * ## Die Regeln, in dieser Reihenfolge
 *  1. Das Zielevent ist der **Zieltag** — egal, was die Tagesform sagt.
 *  2. Kein Routenziel (die Tagesform raet zur Pause) oder ein planfreier Tag
 *     mitten im Plan ([planRestDay]) ist ein **Ruhetag**. Der zweite Fall ist
 *     neu: Frueher bot die Seite an einem Ruhetag des Plans trotzdem eine Runde
 *     aus der Tagesempfehlung an — und der Wochenstreifen darunter zeigte
 *     zugleich „–". Jetzt sagen beide dasselbe.
 *  3. Eine harte Intensitaet (nach Kappung durch die Tagesform) ist **hart**.
 *  4. Die laengste Grundlagen-Einheit einer Woche mit mehreren Einheiten ist
 *     die **lange Fahrt** — aber nur ungekuerzt; heruntergestuft ist sie eine
 *     lockere Runde und wird auch so genannt.
 *  5. Eine lockere Intensitaet oder eine geplante Grundlagen-Einheit ist
 *     **locker**. GA1 heisst in der Vorlage woertlich „eine lockere Runde … so
 *     dass du dich noch unterhalten kannst" — Grundlage *ist* locker.
 *  6. Was bleibt, ist **mittel**: ein normaler Tag ohne Plan-Einheit oder eine
 *     geplante harte Einheit, die die Tagesform auf Grundlage gekappt hat.
 *
 * @param weekSessions alle Einheiten der laufenden Planwoche (leer ohne Plan);
 *   noetig fuer „die laengste der Woche".
 */
fun todayEffort(
    route: TodayRoute,
    planRestDay: Boolean,
    weekSessions: List<TrainingSession>,
): TodayEffort {
    val session = route.session
    if (session?.isEvent == true) return TodayEffort.ZIELTAG
    val target = route.target ?: return TodayEffort.RUHETAG
    if (session == null && planRestDay) return TodayEffort.RUHETAG
    return when (target.intensity) {
        SessionIntensity.HART -> TodayEffort.HART
        SessionIntensity.LOCKER -> TodayEffort.LOCKER
        SessionIntensity.GRUNDLAGE -> when {
            session != null && !route.downgraded && isLongestOfWeek(session, weekSessions) ->
                TodayEffort.LANG

            session != null && session.intensity == SessionIntensity.GRUNDLAGE -> TodayEffort.LOCKER
            else -> TodayEffort.MITTEL
        }
    }
}

/** Die laengste Einheit einer Woche mit mindestens zwei Einheiten. */
private fun isLongestOfWeek(session: TrainingSession, weekSessions: List<TrainingSession>): Boolean {
    val rides = weekSessions.filterNot { it.isEvent }
    if (rides.size < 2) return false
    return session.targetKm >= rides.maxOf { it.targetKm }
}

/**
 * Das eine Wort unter der Zahl im Ring. Nie das feste „bereit" von frueher —
 * das stand auch unter einer 23 und war dann schlicht falsch.
 */
fun readinessWord(band: ReadinessBand): String = when (band) {
    ReadinessBand.HART -> "erholt"
    ReadinessBand.NORMAL -> "normal"
    ReadinessBand.LOCKER -> "müde"
    ReadinessBand.RUHE -> "Ruhe"
}

/** Der erste Halbsatz der Schlagzeile — nur mit Gesamtwert. */
private fun readinessLead(band: ReadinessBand): String = when (band) {
    ReadinessBand.HART -> "Gut erholt."
    ReadinessBand.NORMAL -> "Normal erholt."
    ReadinessBand.LOCKER -> "Etwas müde."
    ReadinessBand.RUHE -> "Ziemlich müde."
}

/** „eine lockere Runde" — die Tagesart als Satzglied. */
private fun effortPhrase(effort: TodayEffort): String = when (effort) {
    TodayEffort.LOCKER -> "eine lockere Runde"
    TodayEffort.MITTEL -> "eine normale Runde"
    TodayEffort.HART -> "eine harte Einheit"
    TodayEffort.LANG -> "die lange Fahrt"
    TodayEffort.RUHETAG -> "ein Ruhetag"
    TodayEffort.ZIELTAG -> "dein Zielevent"
}

/**
 * Die Schlagzeile der Hero-Karte: „Gut erholt. Heute eine lockere Runde."
 *
 * Ruhetag, Zieltag und Herunterstufung sagen das **ausdruecklich** — eine
 * Seite, die still 55 statt 90 km anbietet, laesst die Nutzerin raten, ob sie
 * sich verlesen hat.
 *
 * @param band das Readiness-Band, oder `null` ohne Gesamtwert (dann faellt
 *   der erste Halbsatz weg — die Empfehlung kommt dann aus dem Plan).
 */
fun todayHeadline(
    effort: TodayEffort,
    route: TodayRoute,
    band: ReadinessBand?,
    planRestDay: Boolean,
): String {
    val body = when (effort) {
        TodayEffort.ZIELTAG -> "Heute ist dein großer Tag."
        TodayEffort.RUHETAG -> when {
            route.session != null -> "Heute lieber Pause statt Training."
            planRestDay -> "Heute ist Ruhetag."
            else -> "Heute lieber ein Ruhetag."
        }

        TodayEffort.LANG -> "Heute steht die lange Fahrt an."
        TodayEffort.HART -> "Heute darf es hart werden."
        else -> if (route.downgraded) {
            "Heute weniger als geplant: ${effortPhrase(effort)}."
        } else {
            "Heute ${effortPhrase(effort)}."
        }
    }
    return if (band != null) "${readinessLead(band)} $body" else body
}

/**
 * Der eine Satz unter der Schlagzeile: was konkret zu fahren ist, in Worten
 * statt Zonen. „45 km ruhig fahren, so dass du dich noch unterhalten kannst."
 */
fun todaySentence(effort: TodayEffort, route: TodayRoute): String {
    val km = route.target?.distanceKm?.roundToInt()
    val planned = route.plannedKm
    val kmText = when {
        km == null -> ""
        route.downgraded && planned != null && planned != km -> "$km km statt $planned km"
        else -> "$km km"
    }
    return when (effort) {
        TodayEffort.LOCKER -> if (route.target?.intensity == SessionIntensity.LOCKER) {
            "$kmText ganz locker rollen, mit leichten Gängen und ohne Druck."
        } else {
            "$kmText ruhig fahren, so dass du dich noch unterhalten kannst."
        }

        TodayEffort.MITTEL -> "$kmText in gleichmäßigem Tempo, ohne Sprints."
        TodayEffort.HART -> "$kmText mit ein paar kräftigen Abschnitten, dazwischen locker rollen."
        TodayEffort.LANG -> "$kmText in ruhigem Tempo. Iss und trink unterwegs genug."
        TodayEffort.RUHETAG -> route.session?.let {
            "Im Plan standen ${it.targetKm} km. Schieb die Fahrt lieber um einen Tag."
        } ?: "Kein Training heute. Ein Spaziergang tut trotzdem gut."

        TodayEffort.ZIELTAG -> "${route.session?.targetKm ?: km ?: 0} km, die Strecke steht schon. Viel Erfolg!"
    }
}

/** Titel des „Warum?"-Blatts: „Warum eine lockere Runde?" */
fun whyTitle(effort: TodayEffort, route: TodayRoute): String = when {
    effort == TodayEffort.ZIELTAG -> "Heute zählt es"
    effort == TodayEffort.RUHETAG -> "Warum heute Pause?"
    route.downgraded -> "Warum weniger als geplant?"
    else -> "Warum ${effortPhrase(effort)}?"
}

// ---------------------------------------------------------------------------
// „Warum?"-Blatt: die vier Signale
// ---------------------------------------------------------------------------

/** Farbstufe der Wort-Pille. */
enum class SignalTone { GUT, NEUTRAL, ACHTUNG, WARNUNG }

/**
 * Eine Zeile im „Warum?"-Blatt: Name, Wort-Einordnung, ein Satz mit dem Wert.
 * Ersetzt die frueheren Coach-Saetze („HRV 7-Tage-Mittel … Normalband").
 */
data class WhySignal(
    val label: String,
    val word: String,
    val tone: SignalTone,
    val sentence: String,
)

/** Satz fuer ein fehlendes Signal — die Uhr liefert nichts oder noch zu wenig. */
private fun missingSignal(label: String, collectedDays: Int): WhySignal = if (collectedDays <= 0) {
    WhySignal(label, "keine Daten", SignalTone.NEUTRAL, "Noch keine Daten von der Uhr.")
} else {
    WhySignal(
        label,
        "sammelt noch",
        SignalTone.NEUTRAL,
        "Noch zu wenige Werte von der Uhr für eine Einschätzung.",
    )
}

/** „7 h 40 min" */
internal fun formatHoursMinutes(hours: Double): String {
    var h = floor(hours).toInt()
    var min = ((hours - h) * 60).roundToInt()
    if (min == 60) {
        h += 1
        min = 0
    }
    return when {
        h == 0 -> "$min min"
        min == 0 -> "$h h"
        else -> "$h h $min min"
    }
}

/** Schlaf der letzten Nacht gegen den eigenen Schnitt. */
fun sleepSignal(sleep: SleepAssessment): WhySignal {
    val label = "Schlaf"
    val last = sleep.lastNightH
    val dev = sleep.deviationH
    if (!sleep.available || last == null || dev == null) return missingSignal(label, sleep.validNights)
    val (word, tone) = when (sleep.flag) {
        RecoveryFlag.GRUEN, RecoveryFlag.UNBEKANNT -> "gut" to SignalTone.GUT
        RecoveryFlag.GELB -> "etwas kurz" to SignalTone.ACHTUNG
        RecoveryFlag.ORANGE -> "zu kurz" to SignalTone.ACHTUNG
        RecoveryFlag.ROT -> "viel zu kurz" to SignalTone.WARNUNG
    }
    val comparison = when {
        abs(dev) < 0.25 -> "etwa so viel wie sonst"
        dev > 0 -> "etwas mehr als dein Schnitt"
        else -> "${formatHoursMinutes(-dev)} weniger als dein Schnitt"
    }
    return WhySignal(label, word, tone, "${formatHoursMinutes(last)}, $comparison.")
}

/** Ruhepuls gegen den eigenen Normalwert. */
fun restingHrSignal(restingHr: RestingHrAssessment): WhySignal {
    val label = "Ruhepuls"
    val current = restingHr.current
    val delta = restingHr.deltaBpm
    if (!restingHr.available || current == null || delta == null) {
        return missingSignal(label, restingHr.baselineDays)
    }
    val (word, tone) = when (restingHr.flag) {
        RecoveryFlag.GRUEN, RecoveryFlag.UNBEKANNT -> "normal" to SignalTone.GUT
        RecoveryFlag.GELB -> "leicht erhöht" to SignalTone.ACHTUNG
        RecoveryFlag.ORANGE -> "erhöht" to SignalTone.ACHTUNG
        RecoveryFlag.ROT -> "deutlich erhöht" to SignalTone.WARNUNG
    }
    val roundedDelta = delta.roundToInt()
    val comparison = when {
        abs(delta) < 1.5 -> "wie sonst auch"
        delta > 0 -> "$roundedDelta mehr als sonst"
        else -> "etwas niedriger als sonst"
    }
    return WhySignal(label, word, tone, "${current.roundToInt()} Schläge pro Minute, $comparison.")
}

/** HRV gegen den eigenen Normalbereich — im Blatt heisst sie „Erholung (HRV)". */
fun hrvSignal(hrv: HrvAssessment): WhySignal {
    val label = "Erholung (HRV)"
    val current = hrv.currentRmssd
    val low = hrv.bandLowRmssd
    val high = hrv.bandHighRmssd
    if (!hrv.available || current == null || low == null || high == null) {
        return missingSignal(label, hrv.historyDays)
    }
    val range = "${low.roundToInt()}–${high.roundToInt()} ms"
    val value = "${current.roundToInt()} ms"
    return when (hrv.status) {
        HrvStatus.IM_BAND, HrvStatus.UNBEKANNT ->
            WhySignal(label, "normal", SignalTone.GUT, "$value, in deinem Normalbereich von $range.")

        HrvStatus.UEBER_BAND ->
            WhySignal(label, "gut", SignalTone.GUT, "$value, über deinem Normalbereich von $range.")

        HrvStatus.NIEDRIG -> if (hrv.flag == RecoveryFlag.GELB) {
            WhySignal(
                label,
                "etwas niedrig",
                SignalTone.ACHTUNG,
                "$value, leicht unter deinem Normalbereich von $range.",
            )
        } else {
            WhySignal(
                label,
                "niedrig",
                if (hrv.flag == RecoveryFlag.ROT) SignalTone.WARNUNG else SignalTone.ACHTUNG,
                "$value, deutlich unter deinem Normalbereich von $range.",
            )
        }

        HrvStatus.SAETTIGUNG ->
            WhySignal(
                label,
                "auffällig",
                SignalTone.ACHTUNG,
                "$value, hoch bei zugleich erhöhtem Ruhepuls. Das kommt auch bei starker Müdigkeit vor.",
            )
    }
}

/**
 * Die Belastung der letzten Wochen — der Formwert, ohne ihn beim Namen zu
 * nennen. `null` heisst: noch keine Fitnesskurve.
 */
fun loadSignal(tsb: Double?): WhySignal {
    val label = "Belastung"
    if (tsb == null) {
        return WhySignal(
            label,
            "keine Daten",
            SignalTone.NEUTRAL,
            "Noch zu wenige Fahrten, um die Belastung einzuschätzen.",
        )
    }
    return when (classifyTsb(tsb)) {
        TsbBand.SEHR_FRISCH ->
            WhySignal(label, "sehr frisch", SignalTone.GUT, "Du bist zuletzt wenig gefahren. Die Beine sind ausgeruht.")

        TsbBand.FORMSPITZE ->
            WhySignal(label, "frisch", SignalTone.GUT, "Die Beine sind ausgeruht und bereit.")

        TsbBand.NEUTRAL ->
            WhySignal(label, "ausgeglichen", SignalTone.GUT, "Training und Erholung halten sich die Waage.")

        TsbBand.PRODUKTIV ->
            WhySignal(
                label,
                "etwas müde",
                SignalTone.ACHTUNG,
                "Die letzten Tage waren intensiv. Der Körper baut gerade auf.",
            )

        TsbBand.UEBERLASTUNG ->
            WhySignal(
                label,
                "sehr müde",
                SignalTone.WARNUNG,
                "Die letzten Wochen waren sehr hart. Der Körper braucht Erholung.",
            )
    }
}

/**
 * Die Einordnung aus Plan und Coach unter den Signalen — der Inhalt der
 * frueheren Coach-Karte, jetzt als getoente Notiz im Blatt.
 *
 * Die frueheren Coach-Saetze (`DailyRecommendation.reasons`) waren die
 * Signalmeldungen von `:core` im Wortlaut; die stehen jetzt als eigene Zeilen
 * darueber ([WhySignal]). Hier bleibt, was keine Zeile hat: warum der Plan
 * heute angepasst wurde, was als Naechstes ansteht und ob die Woche ruhiger
 * werden sollte.
 *
 * @param upcoming die naechste gewichtige Einheit dieser Woche nach heute
 *   (siehe [upcomingKeySession]).
 */
fun whyNote(
    effort: TodayEffort,
    route: TodayRoute,
    planRestDay: Boolean,
    upcoming: UpcomingSession?,
    deloadRecommended: Boolean,
    hasPlan: Boolean,
): List<String> = buildList {
    route.note?.let { add(it) }
    if (effort == TodayEffort.RUHETAG && planRestDay && route.session == null) {
        add("Laut Plan ist heute frei. Erholung gehört zum Training dazu.")
    }
    upcoming?.let {
        val head = "${it.weekday} steht ${it.what} mit ${it.km} km an."
        add(
            if (effort == TodayEffort.LOCKER || effort == TodayEffort.MITTEL) {
                "$head Heute nicht überziehen, dann hast du dafür genug Kraft."
            } else {
                head
            },
        )
    }
    if (deloadRecommended) {
        add("Deine Werte sprechen dafür, diese Woche etwas kürzer zu treten.")
    }
    if (!hasPlan && isEmpty()) {
        add("Ohne Trainingsziel richtet sich die Empfehlung nach deiner Tagesform und deinen letzten Fahrten.")
    }
}

/** „Samstag steht die lange Fahrt mit 80 km an." — die Bausteine dazu. */
data class UpcomingSession(val weekday: String, val what: String, val km: Int)

/**
 * Die naechste gewichtige Einheit dieser Woche **nach** heute: das Zielevent,
 * eine harte Einheit oder die laengste Fahrt — die, auf die heute Ruecksicht
 * nehmen sollte. `null`, wenn danach nichts Laengeres oder Haerteres kommt.
 *
 * @param todayIndex 0 = Montag … 6 = Sonntag.
 * @param todayKm was heute ansteht; eine spaetere Fahrt zaehlt nur, wenn sie
 *   laenger ist oder hart bzw. das Event.
 */
fun upcomingKeySession(
    weekSessions: List<TrainingSession>,
    todayIndex: Int,
    todayKm: Int?,
): UpcomingSession? {
    val later = weekSessions
        .map { it to planDayIndex(it.day) }
        .filter { (_, index) -> index > todayIndex }
    val pick = later.firstOrNull { (s, _) -> s.isEvent }
        ?: later.filter { (s, _) -> s.intensity == SessionIntensity.HART || s.targetKm > (todayKm ?: 0) }
            .maxByOrNull { (s, _) -> s.targetKm }
        ?: return null
    val (session, index) = pick
    val what = when {
        session.isEvent -> "dein Zielevent"
        session.intensity == SessionIntensity.HART -> "eine harte Einheit"
        isLongestOfWeek(session, weekSessions) -> "die lange Fahrt"
        else -> "eine längere Fahrt"
    }
    return UpcomingSession(WEEKDAY_LONG[index], what, session.targetKm)
}

// ---------------------------------------------------------------------------
// Wochenstreifen
// ---------------------------------------------------------------------------

/**
 * Wochentagskuerzel, wie sie in [TrainingSession.day] stehen. `:core` haelt
 * dieselbe Tabelle privat (`Training.kt`, `weekdays`); eine Aenderung dort
 * muss hier nachgezogen werden.
 */
internal val WEEKDAY_SHORT = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

/** Ausgeschriebene Wochentage fuer Vorlesetext und Coach-Satz. */
internal val WEEKDAY_LONG =
    listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

/** 0 = Montag … 6 = Sonntag; `-1` bei fremdem Kuerzel. */
internal fun planDayIndex(day: String): Int = WEEKDAY_SHORT.indexOf(day)

/** Zustand eines Tages im Wochenstreifen. */
enum class StripState {
    /** Gefahren — gefuellter Akzentkreis mit km. */
    DONE,

    /** Heute, noch nicht gefahren — Akzentring mit den geplanten km. */
    TODAY,

    /** Kommt noch, Einheit geplant — gestrichelter Ring mit km. */
    PLANNED,

    /** Nichts geplant oder vorbei ohne Fahrt — „–". */
    REST,
}

/** Ein Tag im Streifen samt fertigem Vorlesetext. */
data class StripDay(
    val label: String,
    val state: StripState,
    val km: Int?,
    val isToday: Boolean,
    val description: String,
)

/**
 * Gefahrene Kilometer je Kalendertag (lokal) im Zeitraum [[from], [to]].
 * Gespeicherte Planungen zaehlen nicht ([riddenRides]) — dieselbe Regel wie
 * [de.trailscape.core.weekKm].
 */
fun riddenKmByDate(rides: List<RideInfo>, from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
    riddenRides(rides)
        .map { localOfEpochMs(it.createdAt).toLocalDate() to it.stats.distanceKm }
        .filter { (date, _) -> !date.isBefore(from) && !date.isAfter(to) }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, km) -> km.sum() }

/**
 * Die sieben Tage Mo–So der Woche von [today].
 *
 * Gefahrene Tage stehen immer als erledigt da, auch wenn nichts geplant war —
 * die Woche zeigt, was *passiert* ist, nicht nur, was der Plan wollte. Ein
 * verpasster Plantag in der Vergangenheit ist „–": Die Seite fragt nach heute,
 * nicht nach Versaeumtem; das steht im Trainings-Tab.
 *
 * @param sessions Einheiten der laufenden Planwoche (leer ohne Plan).
 * @param todayKm was heute ansteht (`null` am Ruhetag) — dieselbe Zahl wie in
 *   der Hero-Karte, damit Ring und Satz nicht auseinanderlaufen.
 */
fun weekStrip(
    today: LocalDate,
    sessions: List<TrainingSession>,
    riddenKm: Map<LocalDate, Double>,
    todayKm: Int?,
): List<StripDay> {
    val monday = today.with(DayOfWeek.MONDAY)
    return (0..6).map { index ->
        val date = monday.plusDays(index.toLong())
        val isToday = date == today
        val ridden = riddenKm[date]?.takeIf { it > 0 }
        val plannedKm = sessions.filter { planDayIndex(it.day) == index }.maxOfOrNull { it.targetKm }
        val name = if (isToday) "${WEEKDAY_LONG[index]}, heute" else WEEKDAY_LONG[index]
        when {
            ridden != null -> StripDay(
                WEEKDAY_SHORT[index],
                StripState.DONE,
                ridden.roundToInt(),
                isToday,
                "$name: ${ridden.roundToInt()} km gefahren",
            )

            isToday -> StripDay(
                WEEKDAY_SHORT[index],
                StripState.TODAY,
                todayKm,
                true,
                if (todayKm != null) "$name: $todayKm km geplant" else "$name: frei",
            )

            date.isAfter(today) && plannedKm != null -> StripDay(
                WEEKDAY_SHORT[index],
                StripState.PLANNED,
                plannedKm,
                false,
                "$name: $plannedKm km geplant",
            )

            else -> StripDay(WEEKDAY_SHORT[index], StripState.REST, null, false, "$name: frei")
        }
    }
}

/**
 * Kopfzeile der Wochenkarte: „78 von 120 km" und „noch 2 Fahrten".
 *
 * Ohne Plan gibt es kein Wochenziel: dann nur die gefahrenen Kilometer und die
 * Zahl der Fahrten.
 *
 * @return Hauptzahl und (optional) gedaempfter Zusatz.
 */
fun weekSummary(
    riddenKm: Double,
    targetKm: Int?,
    strip: List<StripDay>,
    rideCount: Int,
): Pair<String, String?> {
    val km = riddenKm.roundToInt()
    if (targetKm == null) {
        val rides = when (rideCount) {
            0 -> "noch keine Fahrt"
            1 -> "1 Fahrt"
            else -> "$rideCount Fahrten"
        }
        return "$km km diese Woche" to rides
    }
    val open = strip.count {
        it.state == StripState.PLANNED || (it.state == StripState.TODAY && it.km != null)
    }
    val extra = when {
        open == 1 -> "noch 1 Fahrt"
        open > 1 -> "noch $open Fahrten"
        km >= targetKm -> "Wochenziel geschafft"
        else -> null
    }
    return "$km von $targetKm km" to extra
}

// ---------------------------------------------------------------------------
// Ziel
// ---------------------------------------------------------------------------

/** „noch 12 Wochen" / „noch 5 Tage" / „morgen" / „heute" / „vorbei". */
fun goalCountdown(today: LocalDate, goalDate: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, goalDate)
    return when {
        days < 0 -> "vorbei"
        days == 0L -> "heute"
        days == 1L -> "morgen"
        days < 14 -> "noch $days Tage"
        else -> "noch ${days / 7} Wochen"
    }
}

/** „Sa, 20. Dezember" — mit Jahr, wenn es nicht das laufende ist. */
fun formatGoalDate(today: LocalDate, goalDate: LocalDate): String {
    val weekday = WEEKDAY_SHORT[goalDate.dayOfWeek.value - 1]
    val month = GERMAN_MONTHS[goalDate.monthValue - 1]
    val year = if (goalDate.year != today.year) " ${goalDate.year}" else ""
    return "$weekday, ${goalDate.dayOfMonth}. $month$year"
}

private val GERMAN_MONTHS = listOf(
    "Januar", "Februar", "März", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
)

/**
 * Die gedaempfte Zeile unter dem Zielnamen: „Sa, 20. Dezember · noch 12
 * Wochen · Woche 3 von 15". Die Planwoche entfaellt vor Planbeginn
 * ([weekIndex] < 0).
 */
fun goalLine(today: LocalDate, goalDate: LocalDate, weekIndex: Int, weekCount: Int): String =
    buildList {
        add(formatGoalDate(today, goalDate))
        add(goalCountdown(today, goalDate))
        if (weekIndex >= 0 && weekCount > 0) add("Woche ${weekIndex + 1} von $weekCount")
    }.joinToString(" · ")

/**
 * Die Ziel-Zeile, wenn eine Zielzeit eingetragen ist (Fuehrung „Klartext"):
 * „Ziel 2:10 h · Stand heute ca. 2:25 h · noch 12 Wochen". Ohne Prognose
 * (zu wenige passende Touren) entfaellt der mittlere Teil.
 */
fun goalTimeLine(today: LocalDate, goalDate: LocalDate, targetMin: Int, currentMin: Int?): String =
    buildList {
        add("Ziel ${formatGoalDuration(targetMin)} h")
        currentMin?.let { add("Stand heute ca. ${formatGoalDuration(it)} h") }
        add(goalCountdown(today, goalDate))
    }.joinToString(" · ")

/** Anteil der Zeit von Planbeginn bis Zieltag, der schon hinter uns liegt (0…1). */
fun goalProgress(planStart: LocalDate, goalDate: LocalDate, today: LocalDate): Float {
    val total = ChronoUnit.DAYS.between(planStart, goalDate)
    if (total <= 0) return 1f
    val done = ChronoUnit.DAYS.between(planStart, today)
    return (done.toFloat() / total).coerceIn(0f, 1f)
}
